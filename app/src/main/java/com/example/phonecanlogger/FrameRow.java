package com.example.phonecanlogger;

import java.util.Arrays;
import java.util.Locale;

/** One line in the frame list. A copy of a received frame, safe to hand to the UI thread. */
public class FrameRow {

    /** How a bit (or byte) should be coloured. */
    public static final int STATE_NEVER = 0;    // has never changed: no colour
    public static final int STATE_OLD = 1;      // changed before, but not in the last 5 seconds: blue
    public static final int STATE_RECENT = 2;   // changed in the last 5 seconds: red

    public static final long RECENT_MS = 5000;  // how long a change stays red
    public static final long NEVER = -1;        // "this bit has never changed"

    public final long timestamp;   // microseconds, as sent by the board
    public final long id;
    public final boolean extended;
    public final int bus;
    public final int length;
    public final int[] data;
    public int count = 1;          // only used in overwrite mode: how many times this ID arrived

    /**
     * For every bit: the time (SystemClock.elapsedRealtime) it last changed, or NEVER.
     * Index = byteNumber * 8 + bitNumber, where bit 0 is the least significant bit.
     * Filled in by ChangeTracker. It is a snapshot taken when this frame arrived.
     */
    public long[] bitChangeMs;

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

    /** Frames from the same bus and ID share this key. */
    public long key() {
        return ((long) bus << 32) | id;
    }

    // ---------------- colouring ----------------

    public int bitState(int byteIndex, int bitNumber, long nowMs) {
        if (bitChangeMs == null) return STATE_NEVER;
        long changedAt = bitChangeMs[byteIndex * 8 + bitNumber];
        if (changedAt == NEVER) return STATE_NEVER;
        return (nowMs - changedAt < RECENT_MS) ? STATE_RECENT : STATE_OLD;
    }

    /** A whole byte gets the "hottest" colour of its 8 bits. */
    public int byteState(int byteIndex, long nowMs) {
        int result = STATE_NEVER;
        for (int bit = 0; bit < 8; bit++) {
            result = Math.max(result, bitState(byteIndex, bit, nowMs));
        }
        return result;
    }

    // ---------------- text ----------------

    /** Standard IDs show 3 hex digits, extended IDs show 8. */
    public String idText() {
        return String.format(Locale.US, extended ? "%08X" : "%03X", id);
    }

    public String byteText(int index) {
        return String.format(Locale.US, "%02X", data[index]);
    }

    /** Time, ID, bus and length. Columns line up with the header in strings.xml. */
    public String prefixText() {
        return String.format(Locale.US, "%9.3f %-8s %2d %2d  ",
                timestamp / 1000000.0, idText(), bus, length);
    }

    public String dataText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(byteText(i));
        }
        return sb.toString();
    }

    /** Plain text version of the whole line (no colours). */
    public String toLine(boolean showCount) {
        String line = prefixText() + dataText();
        if (showCount) line += "  x" + count;
        return line;
    }
}
