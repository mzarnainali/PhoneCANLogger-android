package com.example.phonecanlogger;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

/** Builds the coloured text for one frame line. */
public class RowTextBuilder {

    private static final int RED = Color.rgb(229, 57, 53);     // changed in the last 5 seconds
    private static final int BLUE = Color.rgb(30, 136, 229);   // changed earlier, quiet for 5+ seconds

    /**
     * binary = false: data as hex bytes, a byte is coloured if any of its bits changed.
     * binary = true:  data as 0/1 bits (4 bytes per line), every bit coloured on its own.
     */
    public static CharSequence build(FrameRow row, boolean binary, boolean showCount, long nowMs) {
        SpannableStringBuilder text = new SpannableStringBuilder(row.prefixText());

        if (binary) {
            if (showCount) text.append("x").append(String.valueOf(row.count));
            for (int i = 0; i < row.length; i++) {
                text.append(i % 4 == 0 ? "\n  " : " ");
                for (int bit = 7; bit >= 0; bit--) {   // most significant bit first
                    String digit = ((row.data[i] >> bit) & 1) == 1 ? "1" : "0";
                    appendColored(text, digit, row.bitState(i, bit, nowMs));
                }
            }
        } else {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) text.append(' ');
                appendColored(text, row.byteText(i), row.byteState(i, nowMs));
            }
            if (showCount) text.append("  x").append(String.valueOf(row.count));
        }
        return text;
    }

    /** The colour key shown above the list. */
    public static CharSequence legend() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        appendColored(text, " changed < 5 s ago ", FrameRow.STATE_RECENT);
        text.append("  ");
        appendColored(text, " changed earlier ", FrameRow.STATE_OLD);
        text.append("  never changed");
        return text;
    }

    private static void appendColored(SpannableStringBuilder text, String piece, int state) {
        int start = text.length();
        text.append(piece);
        if (state == FrameRow.STATE_NEVER) return;

        int background = (state == FrameRow.STATE_RECENT) ? RED : BLUE;
        text.setSpan(new BackgroundColorSpan(background), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.WHITE), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
