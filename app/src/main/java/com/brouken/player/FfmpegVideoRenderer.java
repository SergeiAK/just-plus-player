package com.brouken.player;

import android.os.Handler;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/**
 * The FFmpeg software video decoder as a renderer. It sits behind the platform one: track selection
 * reaches it when no platform decoder claims a format at all, or when PlayerActivity has taken a
 * format away from the platform renderer after its decoder failed on it (see
 * recoverByDecodingVideoWithFfmpeg). Everything that plays in hardware today keeps doing so.
 */
final class FfmpegVideoRenderer extends DecoderVideoRenderer {

    private static final String TAG = "FfmpegVideoRenderer";
    /** Enough for an SD or 1080p frame of these codecs; larger samples grow the buffer. */
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 768 * 1024;

    @Nullable
    private FfmpegVideoDecoder decoder;

    FfmpegVideoRenderer(long allowedJoiningTimeMs, @Nullable Handler eventHandler,
                        @Nullable VideoRendererEventListener eventListener,
                        int maxDroppedFramesToNotify) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
        if (!FfmpegVideoDecoder.supportsMime(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS,
                TUNNELING_NOT_SUPPORTED);
    }

    @Override
    protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
            throws DecoderException {
        final int inputSize = format.maxInputSize != Format.NO_VALUE
                ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
        // Frame threads: libavcodec parallelises MPEG-4 Part 2 and MPEG-2 between frames, and these
        // are SD pictures, so each extra thread holds little. Eight is libavcodec's own sensible cap.
        final int threads = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        final FfmpegVideoDecoder created = new FfmpegVideoDecoder(format,
                /* numInputBuffers= */ 8, /* numOutputBuffers= */ 8, inputSize, threads);
        decoder = created;
        return created;
    }

    @Override
    protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws DecoderException {
        if (decoder == null) {
            throw new DecoderException("ffmpeg video decoder is not initialised");
        }
        decoder.renderToSurface(outputBuffer, surface);
        outputBuffer.release();
    }

    @Override
    protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
        if (decoder != null) {
            decoder.setOutputMode(outputMode);
        }
    }

    @Override
    protected DecoderReuseEvaluation canReuseDecoder(String decoderName, Format oldFormat,
                                                     Format newFormat) {
        // The same codec carries a new resolution by itself; anything else opens afresh.
        final boolean sameMime = oldFormat.sampleMimeType != null
                && oldFormat.sampleMimeType.equals(newFormat.sampleMimeType);
        final boolean sameData = oldFormat.initializationDataEquals(newFormat);
        if (sameMime && sameData) {
            return new DecoderReuseEvaluation(decoderName, oldFormat, newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0);
        }
        return new DecoderReuseEvaluation(decoderName, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                sameMime ? DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED
                        : DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED);
    }
}
