package com.brouken.player;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statistics card, opened by the remote's INFO or MENU key or from the More menu. It is a diagnosis
 * view: every figure the player can state about the file, the decoders and the display, grouped. The
 * card takes sections and lays them out; what goes into them is the player's business.
 */
final class InfoCard extends LinearLayout {

    /** How a value reads: plain, as it should be, or worth a second look. */
    enum Tone { PLAIN, GOOD, WARN }

    private static final class Row {
        final String label;
        final String value;
        final Tone tone;
        /** How many leading characters of the value take the tone; the rest stays plain. */
        final int toneLength;

        Row(String label, String value, Tone tone, int toneLength) {
            this.label = label;
            this.value = value;
            this.tone = tone;
            this.toneLength = toneLength;
        }
    }

    /** A titled group of label/value rows. */
    static final class Section {
        final String title;
        final List<Row> rows = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        /** Adds a row; a null or empty value leaves the row out, so the caller need not test each one. */
        Section row(String label, String value) {
            return row(label, value, Tone.PLAIN);
        }

        /** Adds a row whose whole value takes the tone. */
        Section row(String label, String value, Tone tone) {
            return row(label, value, tone, value != null ? value.length() : 0);
        }

        /**
         * Adds a row where only the first {@code toneLength} characters take the tone — "hardware" in
         * "hardware · OMX.…": the verdict is coloured, the decoder name after it is a plain fact.
         */
        Section row(String label, String value, Tone tone, int toneLength) {
            if (value != null && !value.isEmpty()) {
                rows.add(new Row(label, value, tone, Math.min(toneLength, value.length())));
            }
            return this;
        }
    }

    // Monospace, so one pad width lines the values of a column up. Set from the longest label on the card
    // each fill: a fixed width broke on "Dolby Vision", and translations run longer than English.
    private int labelWidth;

    private final TextView[] columns = new TextView[2];
    private final int labelColor;
    private final int valueColor;
    private final int titleColor;
    private final int goodColor;
    private final int warnColor;

    InfoCard(Context context, UiMetrics ui) {
        super(context);
        labelColor = ContextCompat.getColor(context, R.color.ink_medium);
        valueColor = ContextCompat.getColor(context, R.color.ink_high);
        // The brand colour, as the menus' own section headers: on a card of near-identical grey rows the
        // titles are what the eye jumps between, so they must not be one more grey row.
        titleColor = ContextCompat.getColor(context, R.color.brand);
        goodColor = ContextCompat.getColor(context, R.color.ink_good);
        warnColor = ContextCompat.getColor(context, R.color.ink_warn);
        setOrientation(HORIZONTAL);
        final GradientDrawable background = new GradientDrawable();
        // The near-opaque panel surface, not the controls' translucent black: the card is read line by line,
        // and the header and the picture showing through it made the rows hard to follow.
        background.setColor(ContextCompat.getColor(context, R.color.chrome_surface));
        background.setCornerRadius(ui.pillCorner() * 2);
        setBackground(background);
        // Tight on purpose: the card sits over the top half, and its bottom has to clear the centred
        // play/pause and episode buttons the paused controls put mid-screen.
        final int pad = ui.dpS(10);
        setPadding(pad, pad, pad, pad / 2);
        for (int i = 0; i < columns.length; i++) {
            final TextView column = new TextView(context);
            column.setTypeface(Typeface.MONOSPACE);
            column.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textInfo() * 0.92f);
            column.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i > 0) {
                ((LayoutParams) column.getLayoutParams()).leftMargin = ui.dpS(24);
            }
            columns[i] = column;
            addView(column);
        }
        setGravity(Gravity.TOP);
        setVisibility(GONE);
    }

    /**
     * Fills the card. Two columns when there is room (landscape), one when there is not: sections are
     * dealt out in order, filling the left column until it holds half of the rows.
     */
    void setSections(List<Section> sections, boolean twoColumns) {
        int total = 0;
        labelWidth = 0;
        for (Section section : sections) {
            total += section.rows.size() + 2;
            for (Row row : section.rows) {
                labelWidth = Math.max(labelWidth, row.label.length());
            }
        }
        final SpannableStringBuilder[] text = {new SpannableStringBuilder(), new SpannableStringBuilder()};
        int target = 0;
        int filled = 0;
        for (Section section : sections) {
            if (section.rows.isEmpty()) {
                continue;
            }
            if (twoColumns && target == 0 && filled > 0 && filled + section.rows.size() / 2 >= total / 2) {
                target = 1;
            }
            append(text[twoColumns ? target : 0], section);
            filled += section.rows.size() + 2;
        }
        orientationFor(twoColumns);
        for (int i = 0; i < columns.length; i++) {
            columns[i].setText(text[i]);
            columns[i].setVisibility(text[i].length() > 0 ? VISIBLE : GONE);
        }
    }

    private void orientationFor(boolean twoColumns) {
        setOrientation(twoColumns ? HORIZONTAL : VERTICAL);
    }

    private void append(SpannableStringBuilder out, Section section) {
        if (out.length() > 0) {
            // Two breaks: the previous section's last row had its own break trimmed, so one would only
            // end that row and put the title flush under it.
            out.append("\n");
            // The blank line between sections at half height: enough to part them, a whole line per gap
            // was what pushed the card's bottom down onto the paused controls.
            final int gap = out.length();
            out.append("\n");
            out.setSpan(new RelativeSizeSpan(0.5f), gap, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final int titleStart = out.length();
        out.append(section.title.toUpperCase(Locale.getDefault())).append("\n");
        out.setSpan(new ForegroundColorSpan(titleColor), titleStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), titleStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.1f), titleStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (Row row : section.rows) {
            final int labelStart = out.length();
            out.append(pad(row.label));
            out.setSpan(new ForegroundColorSpan(labelColor), labelStart, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int valueStart = out.length();
            out.append(row.value).append("\n");
            out.setSpan(new ForegroundColorSpan(valueColor), valueStart, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (row.tone != Tone.PLAIN && row.toneLength > 0) {
                out.setSpan(new ForegroundColorSpan(row.tone == Tone.GOOD ? goodColor : warnColor),
                        valueStart, valueStart + row.toneLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        // The last row's own line break would leave a blank line under the column.
        out.delete(out.length() - 1, out.length());
    }

    private String pad(String label) {
        final StringBuilder sb = new StringBuilder(label);
        while (sb.length() < labelWidth) {
            sb.append(' ');
        }
        return sb.append(' ').toString();
    }
}
