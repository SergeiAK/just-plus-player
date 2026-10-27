package com.brouken.player.avi;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class Mpeg4VopReordererTest {

    private static final int I = 0, P = 1, B = 2;

    /** A VOP of the given type, padded to a size no placeholder has; {@code id} marks it. */
    private static byte[] vop(int type, int id) {
        final byte[] v = new byte[40];
        v[2] = 1;
        v[3] = (byte) 0xB6;
        v[4] = (byte) (type << 6);
        v[5] = (byte) id;
        return v;
    }

    /** A placeholder N-VOP: a P header and nothing coded. */
    private static byte[] nvop() {
        return new byte[]{0, 0, 1, (byte) 0xB6, (byte) (P << 6), 0, 0};
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** "id@slot" for every frame written, in decode order. */
    private static final class Log implements Mpeg4VopReorderer.Output {
        final List<String> out = new ArrayList<>();

        @Override
        public void sample(byte[] data, int size, long slot, boolean keyFrame) {
            final int[] vops = Mpeg4VopReorderer.vopStarts(data, size);
            final String id = vops.length == 0 ? "?" : String.valueOf(data[vops[0] + 2]);
            out.add(id + "@" + slot + (keyFrame ? "k" : ""));
        }
    }

    private static List<String> run(byte[]... chunks) {
        final Log log = new Log();
        final Mpeg4VopReorderer r = new Mpeg4VopReorderer(log);
        for (int i = 0; i < chunks.length; i++) {
            r.onChunk(chunks[i], chunks[i].length, i, i == 0);
        }
        r.flush();
        return log.out;
    }

    @Test
    public void withoutBFramesTimesAreTheSlots() {
        assertEquals(java.util.Arrays.asList("0@0k", "1@1", "2@2", "3@3"),
                run(vop(I, 0), vop(P, 1), vop(P, 2), vop(P, 3)));
    }

    /** Decode order I0 P3 B1 B2 P6 B4 B5: shown 0 1 2 3 4 5 6, written in decode order. */
    @Test
    public void bFramesAreShownBeforeTheirAnchor() {
        assertEquals(java.util.Arrays.asList("0@0k", "3@3", "1@1", "2@2", "6@6", "4@4", "5@5"),
                run(vop(I, 0), vop(P, 3), vop(B, 1), vop(B, 2), vop(P, 6), vop(B, 4), vop(B, 5)));
    }

    /**
     * DivX packed: chunk 1 holds P2 and B1, chunk 2 is the placeholder. Unpacked to P2 in slot 1 and
     * B1 in slot 2, then shown in order.
     */
    @Test
    public void packedBFrameTakesThePlaceholdersSlot() {
        assertEquals(java.util.Arrays.asList("0@0k", "2@2", "1@1", "3@3"),
                run(vop(I, 0), concat(vop(P, 2), vop(B, 1)), nvop(), vop(P, 3)));
    }

    @Test
    public void packedFlagInUserDataIsCleared() {
        final byte[] user = concat(new byte[]{0, 0, 1, (byte) 0xB2}, "DivX503b1393p".getBytes());
        final byte[] chunk = concat(user, vop(P, 2), vop(B, 1));
        final List<byte[]> written = new ArrayList<>();
        final Mpeg4VopReorderer r = new Mpeg4VopReorderer(
                (data, size, slot, key) -> written.add(java.util.Arrays.copyOf(data, size)));
        r.onChunk(vop(I, 0), 40, 0, true);
        r.onChunk(chunk, chunk.length, 1, false);
        r.flush();
        final String text = new String(written.get(1), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(true, text.contains("DivX503b1393\0"));
    }

    /**
     * A seek drops what was held: the frames after it are timed afresh. I0 was already written when P3
     * arrived; P3, still waiting for the next anchor, is the one dropped.
     */
    @Test
    public void resetDropsHeldFrames() {
        final Log log = new Log();
        final Mpeg4VopReorderer r = new Mpeg4VopReorderer(log);
        r.onChunk(vop(I, 0), 40, 0, true);
        r.onChunk(vop(P, 3), 40, 1, false);
        r.reset();
        r.onChunk(vop(I, 9), 40, 9, true);
        r.onChunk(vop(P, 10), 40, 10, false);
        r.flush();
        assertEquals(java.util.Arrays.asList("0@0k", "9@9k", "10@10"), log.out);
    }
}
