package com.brouken.player;

import static com.brouken.player.ResumePolicy.Decision.ASK;
import static com.brouken.player.ResumePolicy.Decision.CONTINUE;
import static com.brouken.player.ResumePolicy.Decision.START_OVER;
import static com.brouken.player.ResumePolicy.Start.NEXT;
import static com.brouken.player.ResumePolicy.Start.OPEN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The start rules taken from Just+ 2.4.4 (see {@link ResumePolicy}). */
public class ResumePolicyTest {

    private static final long MIN = 60_000;
    private static final long FILM = 120 * MIN;

    @Test
    public void askOpenAsksOnlyForTheFileOpened() {
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 40 * MIN, FILM, null));
        assertEquals(CONTINUE, ResumePolicy.decide(ResumePolicy.ASK_OPEN, NEXT, 40 * MIN, FILM, null));
    }

    @Test
    public void askEveryAsksForTheNextVideoToo() {
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_EVERY, OPEN, 40 * MIN, FILM, null));
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_EVERY, NEXT, 40 * MIN, FILM, null));
    }

    @Test
    public void alwaysAndNever() {
        assertEquals(CONTINUE, ResumePolicy.decide(ResumePolicy.ALWAYS, OPEN, 40 * MIN, FILM, null));
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.NEVER, OPEN, 40 * MIN, FILM, null));
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.NEVER, NEXT, 40 * MIN, FILM, null));
    }

    @Test
    public void nothingRememberedNothingToDecide() {
        assertEquals(CONTINUE, ResumePolicy.decide(ResumePolicy.NEVER, OPEN, 0, FILM, null));
        assertEquals(CONTINUE, ResumePolicy.decide(ResumePolicy.ASK_EVERY, OPEN, 0, FILM, null));
    }

    @Test
    public void underThirtySecondsStartsOverWithoutAQuestion() {
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 29_999, FILM, null));
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 30_000, FILM, null));
        // Not a rule of the modes that do not ask: there a few seconds in is still where it was left.
        assertEquals(CONTINUE, ResumePolicy.decide(ResumePolicy.ALWAYS, OPEN, 10_000, FILM, null));
    }

    @Test
    public void ninetyFivePercentIsWatched() {
        final long mark = FILM - FILM / 20;
        assertEquals(mark, ResumePolicy.finishedAt(FILM, null));
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.ALWAYS, OPEN, mark, FILM, null));
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, mark, FILM, null));
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, mark - 1, FILM, null));
    }

    @Test
    public void endCreditsFinishTheFilmEarlier() {
        // 8 minutes of credits from 110:00 to the end: inside 15 % and past the 95 % mark (114:00).
        final long[][] credits = {{110 * MIN, FILM}};
        assertEquals(110 * MIN, ResumePolicy.finishedAt(FILM, credits));
        assertEquals(START_OVER, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 111 * MIN, FILM, credits));
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 109 * MIN, FILM, credits));
    }

    @Test
    public void creditsFollowedByATeaserStillCount() {
        // Credits end at 116:00, a scene after them: the end is past the mark, so their start counts.
        assertEquals(108 * MIN, ResumePolicy.finishedAt(FILM, new long[][] {{108 * MIN, 116 * MIN}}));
    }

    @Test
    public void segmentsThatAreNotEndCreditsAreIgnored() {
        final long mark = FILM - FILM / 20;
        // Ends before the 95 % mark: an intro, a recap, credits of an episode inside a compilation.
        assertEquals(mark, ResumePolicy.finishedAt(FILM, new long[][] {{100 * MIN, 105 * MIN}}));
        // Longer than 15 % of the film: not credits, whatever it is labelled.
        assertEquals(mark, ResumePolicy.finishedAt(FILM, new long[][] {{90 * MIN, FILM}}));
    }

    @Test
    public void unknownLengthFinishesNothing() {
        assertEquals(Long.MAX_VALUE, ResumePolicy.finishedAt(0, null));
        assertEquals(ASK, ResumePolicy.decide(ResumePolicy.ASK_OPEN, OPEN, 500 * MIN, 0, null));
    }

    @Test
    public void unknownModeIsTheDefault() {
        assertEquals(ASK, ResumePolicy.decide("someday", OPEN, 40 * MIN, FILM, null));
        assertEquals(CONTINUE, ResumePolicy.decide(null, NEXT, 40 * MIN, FILM, null));
    }
}
