package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Track names are the ones found in the library on 07.10.2026 (604 films). */
public class ForcedSubtitlesTest {

    @Test
    public void forcedByName() {
        for (final String name : new String[] {
                "Forced", "forced", "FORCED", "Forced ", "Forced 1", "Force", "Rus forced", "rus Forced",
                "ru forced", "rusForced", "ukrForced", "French-forced", "Ukrainian (Forced)",
                "Rus (Forced)", "Forced, HDRezka Studio", "Forced [HDRezka Studio]", "forced bHDRezka StudioB",
                "Forced.Goblin", "forced for songs", "русские, форсированные",
                "Russian @ SRT [только надписи и пр.]",
                "Russian @ SRT [только надписи + не озвученные фразы Dub]",
                "Надписи", "Написи", "Foreign parts only"}) {
            assertTrue(name, ForcedSubtitles.isForced(false, name));
        }
    }

    @Test
    public void fullByName() {
        for (final String name : new String[] {
                "Russian1", "Russian2", "Full", "Blu-ray RUS", "Субтитры", "Субтитры R5", "русский",
                "SDH", "Commentary", "Rus", "Полные", "Полные, с надписями", "Full + signs",
                "Повні з написами", "Navidad", "Reinforcement"}) {
            assertFalse(name, ForcedSubtitles.isForced(false, name));
        }
        assertFalse(ForcedSubtitles.isForced(false, null));
    }

    @Test
    public void flagWinsOverName() {
        // "русский" carries the flag and nothing in the name (Агенты А.Н.К.Л. has "Force" the same way).
        assertTrue(ForcedSubtitles.isForced(true, "русский"));
        assertTrue(ForcedSubtitles.isForced(true, null));
    }

    @Test
    public void wantedLanguageOnlyForAListedDub() {
        final List<String> list = Arrays.asList("rus", "eng");
        assertEquals("rus", ForcedSubtitles.wantedLanguage("rus", list));
        assertEquals("eng", ForcedSubtitles.wantedLanguage("eng", list));
        // A dub the list does not name: full subtitles stay the answer.
        assertNull(ForcedSubtitles.wantedLanguage("fra", list));
        assertNull(ForcedSubtitles.wantedLanguage(null, list));
        assertNull(ForcedSubtitles.wantedLanguage("rus", Collections.<String>emptyList()));
    }
}
