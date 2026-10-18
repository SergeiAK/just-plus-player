package com.brouken.player;

/**
 * Where a video that has a remembered position starts: there, from the beginning, or the viewer is asked.
 *
 * <p>The rules are Just+ 2.4.4's, read off its bytecode (18.10.2026): the four modes, the 95 % mark, the
 * credits that end the film earlier than that, and the 30 seconds below which asking is not worth it.
 * Pure, so the rules are tested without a player; the activity supplies what it knows when the start is
 * about to be spent.
 */
final class ResumePolicy {

    static final String ASK_OPEN = "askOpen";
    static final String ASK_EVERY = "askEvery";
    static final String ALWAYS = "always";
    static final String NEVER = "never";

    /**
     * Why the video is starting. Only these two are decided here: anything else — a rebuild under the
     * viewer, a return from the background, a room telling us where it is, a launcher that names its
     * own position — keeps the position it has and never reaches this class.
     */
    enum Start {
        /** The viewer opened this file: from the browser, another app, a link. */
        OPEN,
        /** Another video inside the session: the next file, a jump to another episode of the playlist. */
        NEXT
    }

    enum Decision { CONTINUE, START_OVER, ASK }

    /** Watched less than this, a question would cost more than the film it saves. Ask modes only. */
    static final long MIN_ASK_MS = 30_000;

    private ResumePolicy() {
    }

    /**
     * Where the film counts as watched: 95 % of it, or the start of the end credits when they begin
     * before that. Credits only count when they run past the 95 % mark and are no longer than 15 % of the
     * film — a long segment at the end is not credits but a mislabelled chunk of the film, and stopping in
     * it is stopping mid-film. {@link Long#MAX_VALUE} while the length is unknown: nothing is finished then.
     *
     * @param creditsMs start and end of each end-credits segment, {@code {startMs, endMs}}; may be null
     */
    static long finishedAt(final long durationMs, final long[][] creditsMs) {
        if (durationMs <= 0) {
            return Long.MAX_VALUE;
        }
        final long mark = durationMs - durationMs / 20;
        long finished = mark;
        if (creditsMs != null) {
            for (final long[] credits : creditsMs) {
                if (credits[1] >= mark && credits[1] - credits[0] <= durationMs * 0.15) {
                    finished = Math.min(finished, credits[0]);
                }
            }
        }
        return finished;
    }

    static Decision decide(final String mode, final Start start, final long positionMs, final long durationMs,
                           final long[][] creditsMs) {
        if (positionMs <= 0) {
            return Decision.CONTINUE;
        }
        if (NEVER.equals(mode) || positionMs >= finishedAt(durationMs, creditsMs)) {
            return Decision.START_OVER;
        }
        final boolean ask = ASK_EVERY.equals(mode) || (asksOnOpen(mode) && start == Start.OPEN);
        if (!ask) {
            return Decision.CONTINUE;
        }
        return positionMs < MIN_ASK_MS ? Decision.START_OVER : Decision.ASK;
    }

    // A value this version does not know — a later one's, or a mangled file — is read as the default.
    private static boolean asksOnOpen(final String mode) {
        return !ASK_EVERY.equals(mode) && !ALWAYS.equals(mode) && !NEVER.equals(mode);
    }
}
