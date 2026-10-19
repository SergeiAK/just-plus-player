package com.brouken.player;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
 * The full statistics card, opened by the remote's INFO key or from the More menu. It is a diagnosis
 * view: every figure the player can state about the file, the decoders and the display, grouped, where
 * the narrow side panel is only what moves. The card takes sections and lays them out; what goes into
 * them is the player's business.
 */
final class InfoCard extends LinearLayout {

    /** A titled group of label/value rows. */
    static final class Section {
        final String title;
        final List<String[]> rows = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        /** Adds a row; a null or empty value leaves the row out, so the caller need not test each one. */
        Section row(String label, String value) {
            if (value != null && !value.isEmpty()) {
                rows.add(new String[]{label, value});
            }
            return this;
        }
    }

    // Monospace, so one pad width lines the values of a column up.
    private static final int LABEL_WIDTH = 11;

    private final TextView[] columns = new TextView[2];
    private final int labelColor;
    private final int valueColor;
    private final int titleColor;

    InfoCard(Context context, UiMetrics ui) {
        super(context);
        labelColor = ContextCompat.getColor(context, R.color.ink_medium);
        valueColor = ContextCompat.getColor(context, R.color.ink_high);
        titleColor = labelColor;
        setOrientation(HORIZONTAL);
        final GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(context, R.color.ui_controls_background));
        background.setCornerRadius(ui.pillCorner() * 2);
        setBackground(background);
        final int pad = ui.dpS(14);
        setPadding(pad, pad, pad, pad / 2);
        for (int i = 0; i < columns.length; i++) {
            final TextView column = new TextView(context);
            column.setTypeface(Typeface.MONOSPACE);
            column.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui.textInfo());
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
        for (Section section : sections) {
            total += section.rows.size() + 1;
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
            filled += section.rows.size() + 1;
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
            out.append("\n");
        }
        final int titleStart = out.length();
        out.append(section.title.toUpperCase(Locale.getDefault())).append("\n");
        out.setSpan(new ForegroundColorSpan(titleColor), titleStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.85f), titleStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (String[] row : section.rows) {
            final int labelStart = out.length();
            out.append(pad(row[0]));
            out.setSpan(new ForegroundColorSpan(labelColor), labelStart, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int valueStart = out.length();
            out.append(row[1]).append("\n");
            out.setSpan(new ForegroundColorSpan(valueColor), valueStart, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // The last row's own line break would leave a blank line under the column.
        out.delete(out.length() - 1, out.length());
    }

    private static String pad(String label) {
        final StringBuilder sb = new StringBuilder(label);
        while (sb.length() < LABEL_WIDTH) {
            sb.append(' ');
        }
        return sb.append(' ').toString();
    }
}
