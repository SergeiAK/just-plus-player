package com.brouken.player;

import java.util.ArrayDeque;

/**
 * Frames the video renderer dropped over the last minute. The renderer reports them in batches
 * ({@code onDroppedVideoFrames}), only when there were some, so a clean stream costs nothing here.
 * Fed from the analytics listener whether or not the statistics card is open — a window that only
 * starts when the card does would always open on "0".
 */
final class DropWindow {

    static final long WINDOW_MS = 60_000;

    private final ArrayDeque<long[]> batches = new ArrayDeque<>();
    private long startMs;

    DropWindow(long nowMs) {
        startMs = nowMs;
    }

    /** New file, new player, or a seek (its burst says nothing about the decoder): count from here. */
    void reset(long nowMs) {
        batches.clear();
        startMs = nowMs;
    }

    void add(long nowMs, int frames) {
        if (frames > 0) {
            batches.addLast(new long[]{nowMs, frames});
        }
    }

    int count(long nowMs) {
        drop(nowMs);
        long sum = 0;
        for (long[] batch : batches) {
            sum += batch[1];
        }
        return (int) sum;
    }

    /** How much of the minute has actually been observed; less than a minute right after a reset. */
    long observedMs(long nowMs) {
        return Math.max(0, Math.min(nowMs - startMs, WINDOW_MS));
    }

    private void drop(long nowMs) {
        while (!batches.isEmpty() && nowMs - batches.peekFirst()[0] > WINDOW_MS) {
            batches.removeFirst();
        }
    }
}
