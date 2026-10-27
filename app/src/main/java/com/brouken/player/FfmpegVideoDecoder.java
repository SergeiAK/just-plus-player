package com.brouken.player;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Software video decoding with FFmpeg's libavcodec, for the streams a TV box's own decoders refuse:
 * XviD with global motion compensation is the case it was written for (Nvidia's parser stops at
 * {@code sprite_enable 0x2}, the Android software decoder at PVInitVideoDecoder), and with it the
 * rest of MPEG-4 Part 2, DivX 3, H.263, MPEG-1/2, VC-1 and Motion JPEG.
 * <p>
 * The native side is {@code ffvideo/} at the root of the repository - FFmpeg built LGPL with only
 * these decoders, and a JNI shaped after media3's libvpx one. Frames leave libavcodec when it has
 * them rather than one per packet, so a decode call either hands one out with its own presentation
 * time or produces a skipped buffer; the few still held when the stream ends are lost, which is a
 * fraction of a second of the last picture.
 */
final class FfmpegVideoDecoder
        extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("ffmpegvideoJNI");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            // x86 builds carry no copy; there the renderer simply refuses every format.
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private final long context;
    private final String codecName;
    private volatile @C.VideoOutputMode int outputMode = C.VIDEO_OUTPUT_MODE_NONE;
    private boolean closed;

    FfmpegVideoDecoder(Format format, int numInputBuffers, int numOutputBuffers,
                       int initialInputBufferSize, int threads) throws DecoderException {
        super(new DecoderInputBuffer[numInputBuffers],
                new VideoDecoderOutputBuffer[numOutputBuffers]);
        if (!AVAILABLE) {
            throw new DecoderException("ffmpeg video library is not loaded");
        }
        final String name = codecName(format.sampleMimeType);
        if (name == null) {
            throw new DecoderException("no ffmpeg decoder for " + format.sampleMimeType);
        }
        codecName = name;
        context = nInit(name, extraData(format.initializationData), threads);
        if (context == 0) {
            throw new DecoderException("ffmpeg " + name + " failed to open");
        }
        setInitialInputBufferSize(initialInputBufferSize);
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static String version() {
        return AVAILABLE ? nVersion() : null;
    }

    /** The libavcodec decoder for a Media3 mime, or null when this build carries none for it. */
    @Nullable
    static String codecName(@Nullable String mime) {
        if (mime == null) {
            return null;
        }
        switch (mime) {
            case MimeTypes.VIDEO_MP4V:
                return "mpeg4";
            case MimeTypes.VIDEO_H263:
                return "h263";
            case MimeTypes.VIDEO_MP42:
                return "msmpeg4v2";
            case MimeTypes.VIDEO_MP43:
                return "msmpeg4v3";
            case MimeTypes.VIDEO_MPEG:
                return "mpeg1video";
            case MimeTypes.VIDEO_MPEG2:
                return "mpeg2video";
            case MimeTypes.VIDEO_VC1:
                return "vc1";
            case MimeTypes.VIDEO_MJPEG:
                return "mjpeg";
            default:
                return null;
        }
    }

    static boolean supportsMime(@Nullable String mime) {
        final String name = codecName(mime);
        return AVAILABLE && name != null && nHasDecoder(name);
    }

    /** Codec-specific data as libavcodec wants it: one block, the container's pieces in order. */
    @Nullable
    private static byte[] extraData(List<byte[]> initializationData) {
        if (initializationData.isEmpty()) {
            return null;
        }
        if (initializationData.size() == 1) {
            return initializationData.get(0);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] piece : initializationData) {
            out.write(piece, 0, piece.length);
        }
        return out.toByteArray();
    }

    @Override
    public String getName() {
        return "ffmpeg-" + codecName;
    }

    void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
        return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        // Keyed on the slot, not on the mode: a buffer only holds one after a surface-mode frame,
        // and the mode can change while that buffer is still queued.
        // A buffer the renderer still holds can come back after release(); the native side is gone
        // by then, and with it every frame it held.
        synchronized (this) {
            if (outputBuffer.decoderPrivate >= 0 && !closed) {
                nReleaseFrame(context, outputBuffer);
            }
        }
        super.releaseOutputBuffer(outputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Unexpected decode error", error);
    }

    @Nullable
    @Override
    protected DecoderException decode(DecoderInputBuffer inputBuffer,
                                      VideoDecoderOutputBuffer outputBuffer, boolean reset) {
        if (reset) {
            nFlush(context);
        }
        final ByteBuffer data = inputBuffer.data;
        if (data == null) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        }
        final int result = nDecode(context, data, data.limit(), inputBuffer.timeUs);
        if (result < 0) {
            return new DecoderException("ffmpeg decode error: " + nErrorString(result));
        }
        outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
        outputBuffer.decoderPrivate = -1;
        final int frame = nGetFrame(context, outputBuffer, outputMode);
        if (frame < 0) {
            return new DecoderException("ffmpeg frame output failed");
        }
        // SimpleDecoder decided this from the input's time, but what comes out is a different frame:
        // with B-frames an anchor goes in before the pictures shown ahead of it. Decide again from the
        // time of the picture itself.
        outputBuffer.shouldBeSkipped = frame == 1 || !isAtLeastOutputStartTimeUs(outputBuffer.timeUs);
        if (outputBuffer.shouldBeSkipped && outputBuffer.decoderPrivate >= 0) {
            nReleaseFrame(context, outputBuffer);
        }
        outputBuffer.format = inputBuffer.format;
        return null;
    }

    void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws DecoderException {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        if (nRenderFrame(context, surface, outputBuffer) != 0) {
            throw new DecoderException("ffmpeg frame render failed");
        }
    }

    @Override
    public void release() {
        super.release();
        synchronized (this) {
            closed = true;
            nClose(context);
        }
    }

    private native long nInit(String codecName, @Nullable byte[] extraData, int threads);

    private native int nDecode(long context, ByteBuffer data, int size, long timeUs);

    private native int nGetFrame(long context, VideoDecoderOutputBuffer outputBuffer, int outputMode);

    private native int nRenderFrame(long context, Surface surface,
                                    VideoDecoderOutputBuffer outputBuffer);

    private native void nReleaseFrame(long context, VideoDecoderOutputBuffer outputBuffer);

    private native void nFlush(long context);

    private native void nClose(long context);

    private native String nErrorString(int error);

    private static native String nVersion();

    private static native boolean nHasDecoder(String codecName);
}
