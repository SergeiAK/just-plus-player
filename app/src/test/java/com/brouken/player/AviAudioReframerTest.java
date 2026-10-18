package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.avi.AviExtractor;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs synthetic AVIs (tools/avi/rechunk.py: MPEG-4 video, 192 kbit/s AC3 re-cut into 960- or 1001-byte
 * chunks) through the same extractor factory chain the player builds, with and without the reframer.
 * The files are passed in with -PaviTestDir=...; without it the test is skipped.
 */
public class AviAudioReframerTest {

    static {
        // ParsableByteArray asks Ascii.equalsIgnoreCase("robolectric", Build.FINGERPRINT) on first use,
        // and the android.jar stub leaves FINGERPRINT null. It is static final, hence Unsafe — by
        // reflection, since the test compiles against android.jar, which has no sun.misc.
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            final Object unsafe = theUnsafe.get(null);
            final java.lang.reflect.Field fingerprint = android.os.Build.class.getField("FINGERPRINT");
            final Object base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field.class)
                    .invoke(unsafe, fingerprint);
            final long offset = (long) unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field.class)
                    .invoke(unsafe, fingerprint);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, "jvm-unit-test");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AC3 192 kbit/s, 960-byte chunks at 25 fps: 1 in 4 chunks starts on a frame. */
    @Test
    public void ac3In960ByteChunks() throws Exception {
        checkFile("a960.avi", MimeTypes.AUDIO_AC3, true, 24_000);
    }

    /** AC3 192 kbit/s, 1001-byte chunks at 23.976 fps: the layout of the rips in #234. */
    @Test
    public void ac3In1001ByteChunks() throws Exception {
        checkFile("a1001.avi", MimeTypes.AUDIO_AC3, true, 24_000);
    }

    /** MPEG-1 Layer II 192 kbit/s (576-byte frames), 1001-byte chunks, tagged as MP3 (0x55). */
    @Test
    public void mp2TaggedMp3In1001ByteChunks() throws Exception {
        checkFile("m1001.avi", MimeTypes.AUDIO_MPEG_L2, true, 24_000);
    }

    /** The same with its own tag, WAVE_FORMAT_MPEG (0x50), which Media3 drops: no control run. */
    @Test
    public void mp2In1001ByteChunks() throws Exception {
        checkFile("m1001-0x50.avi", MimeTypes.AUDIO_MPEG_L2, false, 24_000);
    }

    /**
     * A header Media3 cannot read: an odd-sized strn ("Audio Stream\0", 13 bytes) followed by more
     * chunks in its strl. Written by ffmpeg as is; the audio chunks are whole frames already.
     */
    @Test
    public void oddSizedHeaderChunk() throws Exception {
        checkFile("odd-strn.avi", MimeTypes.AUDIO_AC3, false, 24_000);
    }

    /** DTS core 768 kbit/s, 4004-byte chunks. */
    @Test
    public void dtsIn4004ByteChunks() throws Exception {
        checkFile("d4004.avi", MimeTypes.AUDIO_DTS, true, 96_000);
    }

    /**
     * MPEG-4 with two B-frames between anchors (ffmpeg -c:v mpeg4 -bf 2), against FFmpeg's own genpts
     * (bf2.framecrc: ffmpeg -fflags +genpts -i bf2.avi -map 0:v -c copy -f framecrc). FFmpeg shows
     * everything one frame late; the fork does not, so every frame must sit exactly one slot earlier.
     * Media3 alone, for contrast, stamps the decode order.
     */
    @Test
    public void mpeg4BFramesInPresentationOrder() throws Exception {
        final File file = testFile("bf2.avi");
        final File reference = testFile("bf2.framecrc");
        final List<Long> expected = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(reference.toPath())) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            expected.add(Long.parseLong(line.split(",")[2].trim()) - 1);
        }
        final Track video = extract(file, true, -1).video();
        final Track media3 = extract(file, false, -1).video();
        assertEquals("frames", expected.size(), video.timesUs.size());
        final long frameUs = 40_000;
        int media3Backwards = 0;
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("frame " + i, expected.get(i) * frameUs, (long) video.timesUs.get(i));
            if (i > 0 && media3.timesUs.get(i) < media3.timesUs.get(i - 1)) media3Backwards++;
        }
        final List<Long> sorted = new ArrayList<>(video.timesUs);
        java.util.Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            assertEquals("shown once each, one frame apart", i * frameUs, (long) sorted.get(i));
        }
        System.out.println("bf2.avi: " + expected.size() + " frames, all at FFmpeg genpts - 1 frame; "
                + "Media3 alone goes backwards " + media3Backwards + " times (it never does: decode order)");

        // From a seek on, the frames carry the same times as in the full read.
        final Track sought = extract(file, true, 10_000_000).video();
        final int first = indexOf(video, sought.samples.get(0));
        assertTrue("first frame after seek found", first > 0);
        for (int i = 0; i < sought.samples.size(); i++) {
            assertEquals("after seek, frame " + i, video.timesUs.get(first + i), sought.timesUs.get(i));
        }
        System.out.println("bf2.avi: after seek to 10 s, " + sought.samples.size()
                + " frames match the full read from frame " + first);
    }

    private static int indexOf(Track track, byte[] sample) {
        for (int i = 0; i < track.samples.size(); i++) {
            if (Arrays.equals(track.samples.get(i), sample)) return i;
        }
        return -1;
    }

    /**
     * Not the layer: Media3's own AviExtractor, alone. After a seek the first audio chunk read is
     * stamped with the time of the chunk before it (ChunkReader.seekToPosition takes the last audio
     * chunk at or before the seek position, which is a video chunk, and the audio chunk before that is
     * never read). Prints what it finds rather than failing: it documents an upstream defect.
     */
    @Test
    public void media3AviSeekStampsFirstAudioChunk() throws Exception {
        for (String name : new String[]{"a960.avi", "a1001.avi"}) {
            final File file = testFile(name);
            final Track full = extract(file, false, -1).audio();
            final Track sought = extract(file, false, 10_000_000).audio();
            int index = -1;
            for (int i = 0; i < full.samples.size(); i++) {
                if (Arrays.equals(full.samples.get(i), sought.samples.get(0))) {
                    index = i;
                    break;
                }
            }
            assertTrue("first chunk after seek found in the full read", index > 0);
            final long right = full.timesUs.get(index);
            final long got = sought.timesUs.get(0);
            System.out.println(name + " media3 alone, after seek: first audio chunk is #" + index
                    + ", stamped " + got + " us, its own time " + right + " us (chunk #" + index + "), "
                    + "the time of chunk #" + (index - 1) + " is " + full.timesUs.get(index - 1)
                    + " us -> off by " + (right - got) + " us");
        }
    }

    /**
     * @param media3Control  whether to read the file with Media3's own extractor too, as the control
     *                       (the misaligned chunks it hands on); false where Media3 cannot read it.
     * @param bytesPerSecond the stream's constant byte rate: frame i starts at
     *                       i * frameBytes / bytesPerSecond, exactly.
     */
    private static void checkFile(String name, String mime, boolean media3Control, int bytesPerSecond)
            throws Exception {
        final File file = testFile(name);

        int plainOnSync = 0;
        long streamBytes = -1;
        Track plainAudio = null;
        if (media3Control) {
            plainAudio = extract(file, false, -1).audio();
            streamBytes = 0;
            for (byte[] s : plainAudio.samples) {
                if (startsWithSync(mime, s)) plainOnSync++;
                streamBytes += s.length;
            }
            System.out.println(name + " without reframer: " + plainAudio.samples.size()
                    + " audio samples, " + plainOnSync + " start on a sync word, sizes "
                    + sizes(plainAudio));
        }

        final Capture reframed = extract(file, true, -1);
        final Track audio = reframed.audio();
        System.out.println(name + " with reframer: " + audio.samples.size() + " audio samples, sizes "
                + sizes(audio) + ", first at " + audio.timesUs.get(0) + " us, last at "
                + audio.timesUs.get(audio.timesUs.size() - 1) + " us, " + streamBytes + " stream bytes, "
                + "format " + audio.format);
        assertEquals(mime, audio.format.sampleMimeType);
        assertEquals(48_000, audio.format.sampleRate);
        assertEquals(2, audio.format.channelCount);
        final int frameBytes = audio.samples.get(0).length;
        if (streamBytes >= 0) {
            assertEquals("every byte of the stream lands in a frame", streamBytes / frameBytes,
                    audio.samples.size());
        }
        for (int i = 0; i < audio.samples.size(); i++) {
            final byte[] s = audio.samples.get(i);
            assertEquals("frame " + i + " size", frameBytes, s.length);
            assertTrue("frame " + i + " starts on a sync word", startsWithSync(mime, s));
            assertTrue("frame " + i + " is a key frame", (audio.flags.get(i) & C.BUFFER_FLAG_KEY_FRAME) != 0);
            assertEquals("frame " + i + " time", i * (long) frameBytes * C.MICROS_PER_SECOND / bytesPerSecond,
                    (long) audio.timesUs.get(i));
        }
        // The control: without the layer the decoder really is fed misaligned chunks.
        if (plainAudio != null) {
            assertTrue("plain chunks are mostly misaligned",
                    plainOnSync < plainAudio.samples.size() / 2);
        }

        // Seek to 10 s, read on, and check the first frames after the seek.
        final long seekUs = 10_000_000;
        final Capture afterSeek = extract(file, true, seekUs);
        final Track sought = afterSeek.audio();
        assertTrue("frames after seek", sought.samples.size() > 20);
        final long first = sought.timesUs.get(0);
        // Which frame of the full read it is (the test signal is noise, so frames are unique), and the
        // time the full read gave it: the stamp after a seek must be that same time.
        int k = -1;
        for (int i = 0; i < audio.samples.size(); i++) {
            if (Arrays.equals(audio.samples.get(i), sought.samples.get(0))) {
                k = i;
                break;
            }
        }
        assertTrue("first frame after seek found in the full read", k >= 0);
        final long expected = audio.timesUs.get(k);
        System.out.println(name + " after seek to " + seekUs + " us: first audio frame is #" + k
                + ", stamped " + first + " us, full read " + expected + " us, off by "
                + (first - expected) + " us; " + sought.samples.size() + " frames");
        for (int i = 0; i < sought.samples.size(); i++) {
            final byte[] s = sought.samples.get(i);
            assertEquals("after seek, frame " + i + " size", frameBytes, s.length);
            assertTrue("after seek, frame " + i + " starts on a sync word", startsWithSync(mime, s));
            if (k >= 0 && k + i < audio.timesUs.size()) {
                assertEquals("after seek, frame " + i + " time", audio.timesUs.get(k + i),
                        sought.timesUs.get(i));
            }
        }
        // Rounding only: the chunk time is exact in bytes, the skipped bytes are timed by the header.
        assertTrue("first audio frame after seek stamped as in the full read (off by "
                + (first - expected) + " us)", Math.abs(first - expected) <= 2);

        // Video after the seek: the first chunk read must carry the time the full read gave it.
        final Track fullVideo = reframed.video();
        final Track soughtVideo = afterSeek.video();
        int v = -1;
        for (int i = 0; i < fullVideo.samples.size(); i++) {
            if (Arrays.equals(fullVideo.samples.get(i), soughtVideo.samples.get(0))) {
                v = i;
                break;
            }
        }
        assertTrue("first video frame after seek found in the full read", v >= 0);
        System.out.println(name + " after seek: first video frame is #" + v + ", stamped "
                + soughtVideo.timesUs.get(0) + " us, full read " + fullVideo.timesUs.get(v) + " us");
        assertEquals("first video frame after seek stamped as in the full read",
                fullVideo.timesUs.get(v), soughtVideo.timesUs.get(0));
    }

    private static File testFile(String name) {
        final String dir = System.getProperty("aviTestDir", "");
        Assume.assumeTrue("aviTestDir not set", !dir.isEmpty());
        final File file = new File(dir, name);
        Assume.assumeTrue(file + " missing", file.isFile());
        return file;
    }

    private static boolean startsWithSync(String mime, byte[] s) {
        if (s.length < 4) return false;
        final int b0 = s[0] & 0xFF, b1 = s[1] & 0xFF, b2 = s[2] & 0xFF, b3 = s[3] & 0xFF;
        switch (mime) {
            case MimeTypes.AUDIO_AC3:
                return b0 == 0x0B && b1 == 0x77;
            case MimeTypes.AUDIO_MPEG_L2:
                return b0 == 0xFF && (b1 & 0xE0) == 0xE0;
            case MimeTypes.AUDIO_DTS:
                return b0 == 0x7F && b1 == 0xFE && b2 == 0x80 && b3 == 0x01;
            default:
                throw new IllegalArgumentException(mime);
        }
    }

    private static String sizes(Track t) {
        final Map<Integer, Integer> count = new HashMap<>();
        for (byte[] s : t.samples) count.merge(s.length, 1, Integer::sum);
        return count.toString();
    }

    /**
     * Reads the whole file, or from {@code seekUs} to the end when it is not negative, with the
     * extractor the factory chain picks for it.
     */
    private static Capture extract(File file, boolean reframe, long seekUs) throws IOException {
        // Not DefaultExtractorsFactory: its no-argument createExtractors() reads Uri.EMPTY, which the
        // android.jar stubs leave null, and a Uri cannot be built on the JVM. It constructs a bare
        // AviExtractor the same way (checked against the 1.11.0 bytecode), which is all the layer needs.
        final ExtractorsFactory base = () -> new Extractor[]{
                new AviExtractor(0, new DefaultSubtitleParserFactory())};
        final ExtractorsFactory factory =
                reframe ? new AviAudioReframer(base, new DefaultSubtitleParserFactory()) : base;

        Extractor extractor = null;
        for (Extractor candidate : factory.createExtractors()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                if (candidate.sniff(input(raf, 0))) {
                    extractor = candidate;
                    break;
                }
            }
        }
        assertNotNull("an extractor takes the file", extractor);
        System.out.println("extractor: " + extractor.getClass().getName()
                + " underlying " + extractor.getUnderlyingImplementation().getClass().getSimpleName());

        final Capture capture = new Capture();
        extractor.init(capture);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            ExtractorInput in = input(raf, 0);
            final PositionHolder holder = new PositionHolder();
            boolean seeked = seekUs < 0;
            while (true) {
                if (!seeked && capture.seekMap != null && capture.tracksEnded) {
                    // Headers are in: jump the way the player does, then drop what was read before.
                    final long position = capture.seekMap.getSeekPoints(seekUs).first.position;
                    extractor.seek(position, seekUs);
                    capture.clearSamples();
                    in = input(raf, position);
                    seeked = true;
                    continue;
                }
                final int result = extractor.read(in, holder);
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (result == Extractor.RESULT_SEEK) in = input(raf, holder.position);
            }
        }
        return capture;
    }

    private static ExtractorInput input(RandomAccessFile raf, long position) throws IOException {
        raf.seek(position);
        final DataReader reader = (buffer, offset, length) -> {
            final int n = raf.read(buffer, offset, length);
            return n < 0 ? C.RESULT_END_OF_INPUT : n;
        };
        return new DefaultExtractorInput(reader, position, raf.length());
    }

    private static final class Capture implements ExtractorOutput {
        final Map<Integer, Track> tracks = new HashMap<>();
        final List<Track> order = new ArrayList<>();
        @Nullable SeekMap seekMap;
        boolean tracksEnded;

        @Override
        public TrackOutput track(int id, int type) {
            Track t = tracks.get(id);
            if (t == null) {
                t = new Track(type);
                tracks.put(id, t);
                order.add(t);
            }
            return t;
        }

        @Override
        public void endTracks() {
            tracksEnded = true;
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            this.seekMap = seekMap;
        }

        Track video() {
            for (Track t : order) {
                if (t.format != null && MimeTypes.isVideo(t.format.sampleMimeType)) return t;
            }
            throw new AssertionError("no video track");
        }

        Track audio() {
            Track found = null;
            for (Track t : order) {
                if (t.format != null && MimeTypes.isAudio(t.format.sampleMimeType)) {
                    assertTrue("one audio track", found == null);
                    found = t;
                }
            }
            assertNotNull("audio track", found);
            return found;
        }

        void clearSamples() {
            for (Track t : order) t.clear();
        }
    }

    private static final class Track implements TrackOutput {
        final int type;
        @Nullable Format format;
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> timesUs = new ArrayList<>();
        final List<Integer> flags = new ArrayList<>();
        private byte[] pending = new byte[0];

        Track(int type) {
            this.type = type;
        }

        void clear() {
            samples.clear();
            timesUs.clear();
            flags.clear();
            pending = new byte[0];
        }

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            final byte[] b = new byte[length];
            final int n = input.read(b, 0, length);
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new java.io.EOFException();
            }
            append(b, n);
            return n;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            final byte[] b = new byte[length];
            data.readBytes(b, 0, length);
            append(b, length);
        }

        private void append(byte[] b, int n) {
            final byte[] next = Arrays.copyOf(pending, pending.length + n);
            System.arraycopy(b, 0, next, pending.length, n);
            pending = next;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            final int end = pending.length - offset;
            samples.add(Arrays.copyOfRange(pending, end - size, end));
            timesUs.add(timeUs);
            this.flags.add(flags);
            pending = Arrays.copyOfRange(pending, end, pending.length);
        }
    }
}
