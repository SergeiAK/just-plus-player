package com.brouken.player;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules behind "Forced subtitles only", kept free of the player so they can be tested on their own.
 *
 * <p>Forced subtitles carry the signs and the lines spoken in a language other than the dub's. They are
 * what is wanted when the viewer understands the audio, and only then: a dub in a language from the
 * subtitle list means the viewer reads that language and hears it too, so a full track in it is noise
 * and the forced one is the whole point. A dub in any other language is not touched — full subtitles in
 * a listed language are still the answer there.
 */
final class ForcedSubtitles {

    private ForcedSubtitles() {
    }

    /**
     * What a forced track is called inside a container. Looser than {@code SubtitleSearch}'s pattern,
     * which reads file names off the aggregators and needs letter boundaries there: a track name is
     * short and written by the muxer, and the library has "rusForced", "ukrForced" and
     * "French-forced" — glued forms the boundary would miss. Cyrillic is here for the same reason:
     * "русские, форсированные", "[только надписи и пр.]" (measured 07.10.2026 on 604 films, one of
     * which also carries the flag without a word in the name).
     */
    private static final Pattern FORCED_TRACK_NAME = Pattern.compile(
            "forced|форс|надпис|написи"
                    + "|(^|[^a-z])(forc[eé]s?|forzados?|erzwungene?"
                    + "|foreign.?parts?|parts?.?only)([^a-z]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A full track that also says it shows the signs ("Полные, с надписями", "Full + signs") is still
     * a full track.
     */
    private static final Pattern FULL_TRACK_NAME = Pattern.compile(
            "полн|повн|(^|[^a-z])full([^a-z]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * @param flagged the container's forced flag
     * @param name    the track's name, or null
     */
    static boolean isForced(boolean flagged, String name) {
        if (flagged) {
            return true;
        }
        return name != null && FORCED_TRACK_NAME.matcher(name).find()
                && !FULL_TRACK_NAME.matcher(name).find();
    }

    /**
     * The language whose forced track should be shown, or null when the rule does not apply and the
     * ordinary choice stands.
     *
     * @param audioLanguage ISO 639-2 code of the audio being played, or null when unknown
     * @param preferred     the subtitle language list, ISO 639-2 codes
     */
    static String wantedLanguage(String audioLanguage, List<String> preferred) {
        return audioLanguage != null && preferred.contains(audioLanguage) ? audioLanguage : null;
    }
}
