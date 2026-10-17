package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.avi.AviExtractor;
import androidx.media3.extractor.ts.Ac3Reader;
import androidx.media3.extractor.ts.DtsReader;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.MpegAudioReader;
import androidx.media3.extractor.ts.TsPayloadReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Cuts the compressed audio of an AVI into real frames before the decoder sees it.
 *
 * <p>An AVI interleaves one audio chunk per video frame, and the muxer sizes that chunk by time, not by
 * the audio codec's frame. A 192 kbit/s AC3 frame is 768 bytes; the chunks in the files this was written
 * for are 1001 or 960 bytes, and only a handful of them start on a sync word. {@link AviExtractor}
 * hands each chunk to the decoder as one sample, so the decoder is fed half a frame followed by the
 * rest of another. The ffmpeg decoder answers with a header-parse error that Media3 treats as fatal
 * (playback ends on the error screen some seconds in, and again after every seek into the same
 * stretch); the Amlogic hardware decoder aborts its whole codec service. Players that demux with
 * ffmpeg or VLC never see it, because they run the audio through a parser first.
 *
 * <p>This is that parser: the chunk bytes go through the same elementary-stream reader Media3 uses for
 * the codec inside MPEG-TS, which finds the sync word, cuts at frame boundaries and stamps each frame
 * itself. Only the first chunk after a start or a seek needs a time from the container, and it gets
 * the chunk's own time plus the bytes skipped before the first sync word.
 *
 * <p>Only {@link AviExtractor} instances are touched and only their audio tracks of a codec with a
 * reader here (AC3, E-AC3, MPEG audio layers 1-3, DTS core); anything else passes through unchanged,
 * which is why this sits innermost, under every wrapper that looks at extractors by type.
 */
final class AviAudioReframer extends ForwardingExtractorsFactory {

    private static final String CONTAINER_MIME = "video/x-msvideo";
    private static final int DTS_MAX_INPUT_SIZE = 4096;

    AviAudioReframer(ExtractorsFactory delegate) {
        super(delegate);
    }

    @Override
    public Extractor[] createExtractors() {
        return wrap(super.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return wrap(super.createExtractors(uri, responseHeaders));
    }

    private static Extractor[] wrap(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof AviExtractor) {
                extractors[i] = new AviReframingExtractor(extractors[i]);
            }
        }
        return extractors;
    }

    /** Owns the reframers of one read of one file. */
    private static final class AviReframingExtractor extends ForwardingExtractor {

        private final List<ReframingTrackOutput> reframers = new ArrayList<>();

        AviReframingExtractor(Extractor delegate) {
            super(delegate);
        }

        @Override
        public void init(ExtractorOutput output) {
            super.init(new ForwardingExtractorOutput(output) {
                @Override
                public TrackOutput track(int id, int type) {
                    final TrackOutput track = super.track(id, type);
                    if (type != C.TRACK_TYPE_AUDIO) {
                        return track;
                    }
                    final ReframingTrackOutput reframer = new ReframingTrackOutput(track, id);
                    reframers.add(reframer);
                    return reframer;
                }
            });
        }

        @Override
        public void seek(long position, long timeUs) {
            super.seek(position, timeUs);
            for (ReframingTrackOutput reframer : reframers) {
                reframer.reset();
            }
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            final int result = super.read(input, seekPosition);
            if (result == RESULT_END_OF_INPUT) {
                // A reader may hold its last frame until told the stream is over: DtsReader keeps each
                // core frame until the next sync word, which might add a DTS-HD extension to it.
                for (ReframingTrackOutput reframer : reframers) {
                    reframer.endOfInput();
                }
            }
            return result;
        }
    }

    /** One audio track: passes through until it learns the codec, then reframes. */
    private static final class ReframingTrackOutput extends ForwardingTrackOutput {

        private final TrackOutput downstream;
        private final int trackId;

        @Nullable
        private Format sourceFormat;
        @Nullable
        private ElementaryStreamReader reader;
        private byte[] buffer = new byte[4096];
        private int filled;
        private boolean started;

        ReframingTrackOutput(TrackOutput downstream, int trackId) {
            super(downstream);
            this.downstream = downstream;
            this.trackId = trackId;
        }

        void reset() {
            filled = 0;
            started = false;
            if (reader != null) {
                reader.seek();
            }
        }

        void endOfInput() {
            if (reader != null && started) {
                reader.endOfInputReached();
            }
        }

        @Override
        public void format(Format format) {
            sourceFormat = format;
            reader = createReader(format);
            if (reader == null) {
                super.format(format);
                return;
            }
            Utils.log("AVI audio " + format.sampleMimeType + ": reframing chunks");
            reader.createTracks(new ReaderOutput(format),
                    new TsPayloadReader.TrackIdGenerator(0, trackId));
        }

        @Nullable
        private static ElementaryStreamReader createReader(Format format) {
            final String mime = format.sampleMimeType;
            if (mime == null) {
                return null;
            }
            switch (mime) {
                case MimeTypes.AUDIO_AC3:
                case MimeTypes.AUDIO_E_AC3:
                    return new Ac3Reader(format.language, format.roleFlags, CONTAINER_MIME);
                case MimeTypes.AUDIO_MPEG:
                case MimeTypes.AUDIO_MPEG_L2:
                    return new MpegAudioReader(format.language, format.roleFlags, CONTAINER_MIME);
                case MimeTypes.AUDIO_DTS:
                    return new DtsReader(format.language, format.roleFlags, DTS_MAX_INPUT_SIZE,
                            CONTAINER_MIME);
                default:
                    return null;
            }
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput)
                throws IOException {
            return sampleData(input, length, allowEndOfInput, SAMPLE_DATA_PART_MAIN);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            if (reader == null || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                return super.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            ensureCapacity(length);
            final int read = input.read(buffer, filled, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new java.io.EOFException();
            }
            filled += read;
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length) {
            sampleData(data, length, SAMPLE_DATA_PART_MAIN);
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (reader == null || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                super.sampleData(data, length, sampleDataPart);
                return;
            }
            ensureCapacity(length);
            data.readBytes(buffer, filled, length);
            filled += length;
        }

        private void ensureCapacity(int extra) {
            if (filled + extra > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, filled + extra));
            }
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                @Nullable CryptoData cryptoData) {
            if (reader == null) {
                super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            final int end = filled - offset;
            final int start = end - size;
            if (start < 0) {
                filled = 0;
                return;
            }
            if (!started) {
                final int skipped = syncOffset(start, end);
                reader.packetStarted(timeUs + bytesToUs(skipped, start + skipped, end), 0);
                started = true;
            }
            final ParsableByteArray chunk = new ParsableByteArray(buffer, end);
            chunk.setPosition(start);
            try {
                reader.consume(chunk);
            } catch (ParserException e) {
                // A frame the reader cannot make sense of: drop it and look for the next sync word.
                reader.seek();
                started = false;
            }
            // Anything after the sample belongs to the next one; the AVI extractor leaves none.
            System.arraycopy(buffer, end, buffer, 0, offset);
            filled = offset;
        }

        /** Bytes before the first sync word of the codec in [start, end), or 0 if none. */
        private int syncOffset(int start, int end) {
            final String mime = sourceFormat != null ? sourceFormat.sampleMimeType : null;
            if (mime == null) {
                return 0;
            }
            for (int i = start; i + 1 < end; i++) {
                final int b0 = buffer[i] & 0xFF;
                final int b1 = buffer[i + 1] & 0xFF;
                final boolean sync;
                switch (mime) {
                    case MimeTypes.AUDIO_AC3:
                    case MimeTypes.AUDIO_E_AC3:
                        sync = b0 == 0x0B && b1 == 0x77;
                        break;
                    case MimeTypes.AUDIO_MPEG:
                    case MimeTypes.AUDIO_MPEG_L2:
                        sync = b0 == 0xFF && (b1 & 0xE0) == 0xE0;
                        break;
                    case MimeTypes.AUDIO_DTS:
                        sync = b0 == 0x7F && b1 == 0xFE && i + 3 < end
                                && (buffer[i + 2] & 0xFF) == 0x80 && (buffer[i + 3] & 0xFF) == 0x01;
                        break;
                    default:
                        sync = false;
                }
                if (sync) {
                    return i - start;
                }
            }
            return 0;
        }

        /**
         * How long {@code bytes} of this stream play, judged by the frame header at {@code at}. The
         * container cannot say: AviExtractor puts no bitrate on the format. 0 when the header does not
         * parse (too few bytes left in the chunk, or a false sync word), which stamps the first frame up
         * to one frame early — what it was before this existed.
         */
        private long bytesToUs(int bytes, int at, int end) {
            final String mime = sourceFormat != null ? sourceFormat.sampleMimeType : null;
            if (mime == null || bytes <= 0) {
                return 0;
            }
            final byte[] header = Arrays.copyOfRange(buffer, at, end);
            int bitrate = 0;
            try {
                switch (mime) {
                    case MimeTypes.AUDIO_AC3:
                    case MimeTypes.AUDIO_E_AC3:
                        bitrate = Ac3Util.parseAc3SyncframeInfo(new ParsableBitArray(header)).bitrate;
                        break;
                    case MimeTypes.AUDIO_MPEG:
                    case MimeTypes.AUDIO_MPEG_L2:
                        if (header.length >= 4) {
                            final MpegAudioUtil.Header mpeg = new MpegAudioUtil.Header();
                            if (mpeg.setForHeaderData(((header[0] & 0xFF) << 24)
                                    | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                                    | (header[3] & 0xFF))) {
                                bitrate = mpeg.bitrate;
                            }
                        }
                        break;
                    case MimeTypes.AUDIO_DTS:
                        bitrate = DtsUtil.parseDtsFormat(header, null, null, 0, CONTAINER_MIME, null)
                                .averageBitrate;
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // Truncated or bogus header: no correction.
                bitrate = 0;
            }
            if (bitrate <= 0) {
                return 0;
            }
            return bytes * 8L * C.MICROS_PER_SECOND / bitrate;
        }

        /**
         * Where the reader publishes its track and format: straight into the real output, with the
         * container's own id, label and language put back over what the codec header knows.
         */
        private final class ReaderOutput implements ExtractorOutput {

            private final Format source;

            ReaderOutput(Format source) {
                this.source = source;
            }

            @Override
            public TrackOutput track(int id, int type) {
                return new ForwardingTrackOutput(downstream) {
                    @Override
                    public void format(Format readerFormat) {
                        super.format(readerFormat.buildUpon()
                                .setId(source.id)
                                .setLabel(source.label)
                                .setLanguage(source.language != null
                                        ? source.language : readerFormat.language)
                                .build());
                    }
                };
            }

            @Override
            public void endTracks() {
            }

            @Override
            public void seekMap(SeekMap seekMap) {
            }
        }
    }
}
