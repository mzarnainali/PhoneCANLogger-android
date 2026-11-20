package com.example.phonecanlogger;

import java.util.Arrays;
import java.util.Locale;

/** One line in the frame list. An untouched copy of a received frame, safe to hand to the UI thread. */
public class FrameRow {
    public final long timestamp;   // microseconds, as sent by the board
    public final long id;
    public final boolean extended;
    public final int bus;
    public final int length;
    public final int[] data;
    public int count = 1;          // only used in overwrite mode: how many times this ID arrived

    private FrameRow(CanFrame f) {
        timestamp = f.timestamp;
        id = f.id;
        extended = f.extended;
        bus = f.bus;
        length = f.length;
        data = Arrays.copyOf(f.data, f.length);
    }

    public static FrameRow from(CanFrame frame) {
        return new FrameRow(frame);
    }

    /** Standard IDs show 3 hex digits, extended IDs show 8. */
    public String idText() {
        return String.format(Locale.US, extended ? "%08X" : "%03X", id);
    }

    public String dataText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i]));
        }
        return sb.toString();
    }

    /** The text shown in the list. Columns line up with the header in activity_main.xml. */
    public String toLine(boolean showCount) {
        String line = String.format(Locale.US, "%9.3f %-8s %2d %2d  %s",
                timestamp / 1000000.0, idText(), bus, length, dataText());
        if (showCount) line += "  x" + count;
        return line;
    }
}
