package com.brouken.player.avi;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives MPEG-4 Part 2 video from an AVI the presentation order AVI never stores.
 *
 * <p>AVI has one time per chunk, and a chunk is a frame in decode order. With B-frames that is not
 * the order they are shown in: an I- or P-frame is decoded before the B-frames that sit in front of
 * it on screen. Media3 stamps every chunk with its own slot, so the decoder receives frames whose
 * times go backwards at every B-frame. A decoder that reorders by itself shrugs (Nvidia's); Amlogic's
 * c2.amlogic.mpeg4.decoder shows them unevenly, which is the judder measured on Dune 05.10.2026 and
 * absent in Vimu on the same decoder, whose engine opens AVI with FFmpeg's genpts.
 *
 * <p>Two steps, both after FFmpeg:
 * <ol>
 *   <li><b>Unpacking</b> ({@code mpeg4_unpack_bframes}). DivX 5 "packed bitstream" puts a P-frame and
 *       the B-frame shown before it into one chunk and a placeholder N-VOP into the next. The second
 *       VOP is moved into the placeholder's slot, so every chunk carries one frame again. The
 *       placeholder is recognised by size, as FFmpeg does it. The "p" mark in the DivX user data
 *       is cleared in every chunk that carries it, as FFmpeg does, byte for byte.</li>
 *   <li><b>Presentation times</b> (genpts). A B-frame is shown in its own slot; an I-, P- or S-frame
 *       in the slot of the next one of those, so it waits for the B-frames decoded after it. FFmpeg
 *       then shows everything one slot late; this subtracts that slot, which leaves a stream without
 *       B-frames with exactly the times it had and keeps a stream with them in step with the audio.</li>
 * </ol>
 *
 * <p>An I/P-frame cannot be written out before the next one has been read, and the B-frames after it
 * have to follow it, so a short run of frames is held here — never more than one anchor and the
 * B-frames behind it.
 */
/* package */ final class Mpeg4VopReorderer {

  /** Receives the frames in decode order, each with the slot it is shown in. */
  interface Output {
    void sample(byte[] data, int size, long presentationSlot, boolean keyFrame);
  }

  /** FFmpeg's {@code MAX_NVOP_SIZE}: a placeholder VOP is a header and a byte or two. */
  /* package */ static final int MAX_NVOP_SIZE = 19;

  private static final int VOP_START_CODE = 0xB6;
  private static final int USER_DATA_START_CODE = 0xB2;
  private static final int CODING_TYPE_B = 2;

  private final Output output;

  /** The second VOP of a packed chunk, waiting for the slot of the placeholder that follows. */
  private byte[] packedB;
  private boolean packedBKey;

  /** An I/P/S-frame whose slot is not known yet, and the B-frames read after it. */
  private final List<Frame> held = new ArrayList<>();

  private long lastSlot = -1;

  Mpeg4VopReorderer(Output output) {
    this.output = output;
  }

  /** A chunk of the stream, in file order. Zero-size chunks are not passed in. */
  void onChunk(byte[] data, int size, long slot, boolean keyFrame) {
    lastSlot = slot;
    clearPackedFlag(data, size);
    final int[] vops = vopStarts(data, size);
    if (vops.length >= 2) {
      if (packedB != null) {
        // Two packed chunks in a row with no placeholder between: the held one has no slot left.
        packedB = null;
      }
      final int split = vops[1] - 3;
      onFrame(copy(data, 0, split), slot, keyFrame);
      if (split < size - 3) {
        packedB = copy(data, split, size);
        packedBKey = false;
      }
      return;
    }
    if (packedB != null) {
      final byte[] b = packedB;
      packedB = null;
      if (size <= MAX_NVOP_SIZE) {
        // The placeholder: its slot is the B-frame's.
        onFrame(b, slot, packedBKey);
        return;
      }
      // Not a placeholder after all: the B-frame takes this slot and this chunk the next one.
      onFrame(b, slot, packedBKey);
      packedB = copy(data, 0, size);
      packedBKey = keyFrame;
      return;
    }
    onFrame(copy(data, 0, size), slot, keyFrame);
  }

  /** The stream has ended: whatever is held goes out. */
  void flush() {
    if (packedB != null) {
      final byte[] b = packedB;
      packedB = null;
      onFrame(b, lastSlot + 1, packedBKey);
      lastSlot++;
    }
    releaseHeld(lastSlot + 1);
  }

  /** A seek: what is held belongs to the old position. */
  void reset() {
    packedB = null;
    held.clear();
    lastSlot = -1;
  }

  private void onFrame(byte[] data, long slot, boolean keyFrame) {
    final Frame frame = new Frame(data, slot, keyFrame);
    if (codingType(data) == CODING_TYPE_B) {
      if (held.isEmpty()) {
        // No anchor to wait for (a B-frame straight after a seek): shown as it comes.
        output.sample(data, data.length, Math.max(0, slot - 1), keyFrame);
      } else {
        frame.presentationSlot = slot - 1;
        held.add(frame);
      }
      return;
    }
    releaseHeld(slot);
    held.add(frame);
  }

  /** The held anchor is shown in the slot before {@code nextAnchorSlot}; its B-frames follow it. */
  private void releaseHeld(long nextAnchorSlot) {
    if (held.isEmpty()) {
      return;
    }
    held.get(0).presentationSlot = nextAnchorSlot - 1;
    for (Frame frame : held) {
      output.sample(frame.data, frame.data.length, frame.presentationSlot, frame.keyFrame);
    }
    held.clear();
  }

  /** Offsets just past each VOP start code (00 00 01 B6) in the chunk. */
  /* package */ static int[] vopStarts(byte[] data, int size) {
    int count = 0;
    int[] starts = new int[2];
    for (int i = 0; i + 3 < size; i++) {
      if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1
          && (data[i + 3] & 0xFF) == VOP_START_CODE) {
        if (count == starts.length) {
          starts = java.util.Arrays.copyOf(starts, count * 2);
        }
        starts[count++] = i + 3;
        i += 3;
      }
    }
    return java.util.Arrays.copyOf(starts, count);
  }

  /** vop_coding_type of the first VOP: 0 I, 1 P, 2 B, 3 S; -1 when there is none. */
  /* package */ static int codingType(byte[] data) {
    final int[] vops = vopStarts(data, data.length);
    if (vops.length == 0 || vops[0] + 1 >= data.length) {
      return -1;
    }
    return (data[vops[0] + 1] & 0xFF) >> 6;
  }

  /**
   * DivX marks a packed stream in its user data ("DivX503b1393p"). FFmpeg's mpeg4_unpack_bframes
   * overwrites that p with a zero byte once the stream is unpacked, so a decoder that reads the mark
   * does not unpack it a second time; the same is done here (checked against its output on a DivX 5
   * rip: identical frames).
   */
  private static void clearPackedFlag(byte[] data, int size) {
    for (int i = 0; i + 8 < size; i++) {
      if (data[i] != 0 || data[i + 1] != 0 || data[i + 2] != 1
          || (data[i + 3] & 0xFF) != USER_DATA_START_CODE) {
        continue;
      }
      int end = i + 4;
      while (end + 2 < size && !(data[end] == 0 && data[end + 1] == 0 && data[end + 2] == 1)) {
        end++;
      }
      if (end + 2 >= size) {
        end = size;
      }
      if (data[i + 4] == 'D' && data[i + 5] == 'i' && data[i + 6] == 'v' && data[i + 7] == 'X') {
        // The mark is the last character, possibly followed by zero padding.
        int last = end - 1;
        while (last > i + 7 && data[last] == 0) {
          last--;
        }
        if (data[last] == 'p') {
          data[last] = 0;
        }
      }
      i = end - 1;
    }
  }

  private static byte[] copy(byte[] data, int from, int to) {
    return java.util.Arrays.copyOfRange(data, from, to);
  }

  private static final class Frame {
    final byte[] data;
    final long slot;
    final boolean keyFrame;
    long presentationSlot;

    Frame(byte[] data, long slot, boolean keyFrame) {
      this.data = data;
      this.slot = slot;
      this.keyFrame = keyFrame;
    }
  }
}
