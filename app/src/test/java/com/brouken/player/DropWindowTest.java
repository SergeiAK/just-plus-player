package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DropWindowTest {

    @Test
    public void emptyWindowIsZero() {
        final DropWindow window = new DropWindow(1_000);
        assertEquals(0, window.count(5_000));
    }

    @Test
    public void sumsBatchesInsideTheMinute() {
        final DropWindow window = new DropWindow(0);
        window.add(10_000, 3);
        window.add(30_000, 4);
        assertEquals(7, window.count(40_000));
    }

    @Test
    public void oldBatchesFallOut() {
        final DropWindow window = new DropWindow(0);
        window.add(10_000, 3);
        window.add(50_000, 4);
        assertEquals(4, window.count(75_000));
        assertEquals(0, window.count(111_000));
    }

    @Test
    public void resetForgetsEverythingAndRestartsObservation() {
        final DropWindow window = new DropWindow(0);
        window.add(10_000, 9);
        window.reset(20_000);
        assertEquals(0, window.count(21_000));
        assertEquals(5_000, window.observedMs(25_000));
    }

    @Test
    public void observedTimeIsCappedAtAMinute() {
        final DropWindow window = new DropWindow(0);
        assertEquals(60_000, window.observedMs(600_000));
    }

    @Test
    public void zeroFrameBatchesAreIgnored() {
        final DropWindow window = new DropWindow(0);
        window.add(1_000, 0);
        assertEquals(0, window.count(2_000));
    }
}
