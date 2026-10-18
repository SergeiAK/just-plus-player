package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Refresh rates as Dune HD Homatics reports them (Android 14, LG OLED, 18.10.2026). */
public class DisplayModeRulesTest {

    private static final float NTSC_FILM = 24000f / 1001f;

    @Test
    public void exactMultiples() {
        assertTrue(Utils.isRateMultiple(23.976025f, NTSC_FILM));
        assertTrue(Utils.isRateMultiple(24.000002f, 24f));
        assertTrue(Utils.isRateMultiple(50.0f, 25f));
        assertTrue(Utils.isRateMultiple(25.0f, 25f));
        assertTrue(Utils.isRateMultiple(59.94006f, 29.97003f));
        assertTrue(Utils.isRateMultiple(119.88012f, NTSC_FILM));
    }

    @Test
    public void nearMissesAreNotMultiples() {
        // 720p on that box offers only these, and none of them shows film evenly.
        assertFalse(Utils.isRateMultiple(60.000004f, NTSC_FILM));
        assertFalse(Utils.isRateMultiple(59.94006f, NTSC_FILM));
        assertFalse(Utils.isRateMultiple(50.0f, NTSC_FILM));
        // The integer neighbour of an NTSC rate is a different rate.
        assertFalse(Utils.isRateMultiple(24.000002f, NTSC_FILM));
        assertFalse(Utils.isRateMultiple(120.0f, NTSC_FILM));
        assertFalse(Utils.isRateMultiple(23.976025f, 24f));
        // Slower than the content is never a multiple.
        assertFalse(Utils.isRateMultiple(30.000002f, 60f));
    }

    @Test
    public void densityGroups() {
        assertEquals(0, Utils.densityGroup(480));
        assertEquals(0, Utils.densityGroup(576));
        assertEquals(1, Utils.densityGroup(720));
        assertEquals(2, Utils.densityGroup(1080));
        assertEquals(2, Utils.densityGroup(2160));
        // 1080p and 2160p share a group: that switch keeps the density and is still allowed.
        assertEquals(Utils.densityGroup(1080), Utils.densityGroup(2160));
    }

    @Test
    public void targetWidthUnchanged() {
        // The 720p video still asks for 1280; it is the switch that refuses it now, not the target.
        assertEquals(1280, Utils.targetDisplayWidth(3840, 1280));
        assertEquals(1920, Utils.targetDisplayWidth(3840, 1920));
        assertEquals(3840, Utils.targetDisplayWidth(1920, 3840));
        assertEquals(-1, Utils.targetDisplayWidth(3840, 720));
    }
    // The rates one 4K frame offers on that Dune, in the order it lists them.
    private static final float[] DUNE = {59.94006f, 60.000004f, 50.0f, 29.97003f, 30.000002f, 25.0f,
            NTSC_FILM, 24.000002f};
    // A set without any 24p mode.
    private static final float[] NO_24P = {59.94006f, 60.000004f, 50.0f, 30.000002f, 25.0f};
    // A set with whole rates only.
    private static final float[] WHOLE_ONLY = {60.000004f, 50.0f, 30.000002f, 25.0f};
    // A television's own 120 Hz panel, as an app running on it sees it.
    private static final float[] PANEL_120 = {119.88012f, 120.0f, 100.0f, 59.94006f, 60.000004f,
            50.0f, 47.952f, 48.0f, NTSC_FILM, 24.000002f, 25.0f};

    private static float pick(float[] rates, float fps, String multiple, boolean skip24p) {
        final int i = Utils.chooseRefreshRate(rates, fps, multiple, skip24p);
        return i < 0 ? -1f : rates[i];
    }

    private static final String EXACT = Prefs.RATE_MULTIPLE_EXACT;
    private static final String DOUBLE = Prefs.RATE_MULTIPLE_DOUBLE;
    private static final String HIGHEST = Prefs.RATE_MULTIPLE_HIGHEST;

    @Test
    public void duneHighestIsTheOldRule() {
        assertEquals(NTSC_FILM, pick(DUNE, NTSC_FILM, HIGHEST, false), 0f);
        assertEquals(24.000002f, pick(DUNE, 24.000002f, HIGHEST, false), 0f);
        assertEquals(50.0f, pick(DUNE, 25f, HIGHEST, false), 0f);
        assertEquals(59.94006f, pick(DUNE, 29.97003f, HIGHEST, false), 0f);
        assertEquals(60.000004f, pick(DUNE, 30.000002f, HIGHEST, false), 0f);
        assertEquals(50.0f, pick(DUNE, 50f, HIGHEST, false), 0f);
    }

    @Test
    public void duneAsTheVideoAndDoubled() {
        assertEquals(25.0f, pick(DUNE, 25f, EXACT, false), 0f);
        assertEquals(29.97003f, pick(DUNE, 29.97003f, EXACT, false), 0f);
        assertEquals(50.0f, pick(DUNE, 25f, DOUBLE, false), 0f);
        // No 48 Hz on the box: doubled falls back to the rate itself, not to anything higher.
        assertEquals(NTSC_FILM, pick(DUNE, NTSC_FILM, DOUBLE, false), 0f);
        assertEquals(50.0f, pick(DUNE, 50f, DOUBLE, false), 0f);
    }

    @Test
    public void no24pGoesToPulldownOnItsOwnClock() {
        assertEquals(59.94006f, pick(DUNE, NTSC_FILM, HIGHEST, true), 0f);
        assertEquals(60.000004f, pick(DUNE, 24.000002f, HIGHEST, true), 0f);
        assertEquals(59.94006f, pick(DUNE, NTSC_FILM, EXACT, true), 0f);
        // Rates that are not 24p are left alone.
        assertEquals(25.0f, pick(DUNE, 25f, EXACT, true), 0f);
        assertEquals(50.0f, pick(DUNE, 25f, DOUBLE, true), 0f);
    }

    @Test
    public void setWithout24pModes() {
        // Before: the highest rate, 60.000, and a hitch every 17 s on top of the 3:2.
        assertEquals(59.94006f, pick(NO_24P, NTSC_FILM, HIGHEST, false), 0f);
        assertEquals(60.000004f, pick(NO_24P, 24.000002f, HIGHEST, false), 0f);
        assertEquals(59.94006f, pick(NO_24P, 29.97003f, EXACT, false), 0f);
        // Whole rates only: 23.976 has neither a multiple nor its 3:2, nor its clock — the highest.
        assertEquals(60.000004f, pick(WHOLE_ONLY, NTSC_FILM, EXACT, false), 0f);
    }

    @Test
    public void panel120() {
        assertEquals(119.88012f, pick(PANEL_120, NTSC_FILM, HIGHEST, false), 0f);
        assertEquals(120.0f, pick(PANEL_120, 24.000002f, HIGHEST, false), 0f);
        assertEquals(100.0f, pick(PANEL_120, 25f, HIGHEST, false), 0f);
        assertEquals(47.952f, pick(PANEL_120, NTSC_FILM, DOUBLE, false), 0f);
        assertEquals(48.0f, pick(PANEL_120, 24.000002f, DOUBLE, false), 0f);
        assertEquals(NTSC_FILM, pick(PANEL_120, NTSC_FILM, EXACT, false), 0f);
        // 120 Hz is not left out with 24p: it carries 24 fps evenly.
        assertEquals(119.88012f, pick(PANEL_120, NTSC_FILM, HIGHEST, true), 0f);
        // ...but as the video, 24p left out, the smallest even rate is 119.88, not 3:2 at 59.94.
        assertEquals(119.88012f, pick(PANEL_120, NTSC_FILM, EXACT, true), 0f);
    }

    @Test
    public void ruleHelpers() {
        assertTrue(Utils.is24pRate(NTSC_FILM));
        assertTrue(Utils.is24pRate(48.0f));
        assertFalse(Utils.is24pRate(120.0f));
        assertFalse(Utils.is24pRate(25.0f));
        assertTrue(Utils.isNtscRate(59.94006f));
        assertFalse(Utils.isNtscRate(60.000004f));
        assertTrue(Utils.isRatePulldown(59.94006f, NTSC_FILM));
        assertFalse(Utils.isRatePulldown(60.000004f, NTSC_FILM));
        assertEquals(1, Utils.cadenceTier(DUNE, NTSC_FILM, false));
        assertEquals(2, Utils.cadenceTier(DUNE, NTSC_FILM, true));
        assertEquals(3, Utils.cadenceTier(WHOLE_ONLY, 29.97003f, false));
        assertEquals(-1, Utils.chooseRefreshRate(new float[] {NTSC_FILM, 24f}, NTSC_FILM, HIGHEST, true));
    }
}
