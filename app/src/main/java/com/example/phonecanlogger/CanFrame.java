package com.example.phonecanlogger;

import java.util.Locale;

/** One received CAN frame. Just a plain data holder. */
public class CanFrame {
    public long timestamp;
    public long id;
    public boolean extended;
    public int bus;
    public int length;
    public final int[] data = new int[64]; // 64 so CAN FD frames fit too

    /** One CSV row, in the same format the original Qt app wrote. */
    public String toCsvLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp).append(',');
        sb.append(String.format(Locale.US, "%08X", id)).append(',');
        sb.append(extended ? "true," : "false,");
        sb.append("Rx,");
        sb.append(bus).append(',');
        sb.append(length).append(',');
        for (int i = 0; i < 8; i++) {
            int value = (i < length) ? data[i] : 0;
            sb.append(String.format(Locale.US, "%02X", value)).append(',');
        }
        sb.append('\n');
        return sb.toString();
    }
}
