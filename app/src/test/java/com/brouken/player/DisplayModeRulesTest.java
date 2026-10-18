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
}
