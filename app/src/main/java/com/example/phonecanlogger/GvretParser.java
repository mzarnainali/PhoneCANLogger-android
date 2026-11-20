package com.example.phonecanlogger;

/**
 * Turns the raw byte stream from a GVRET device into CAN frames.
 * Feed it one byte at a time with processByte().
 *
 * A frame on the wire looks like:
 *   0xF1, command, timestamp (4 bytes), id (4 bytes), ...
 * Command 0 is a normal CAN frame, command 20 is a CAN FD frame.
 * Everything else is ignored.
 */
public class GvretParser {

    public interface FrameListener {
        /** The frame object is reused, so use it right away and don't keep it. */
        void onFrame(CanFrame frame);
    }

    private enum State { IDLE, GET_COMMAND, CAN_FRAME, FD_FRAME }

    private final FrameListener listener;
    private final CanFrame frame = new CanFrame();
    private State state = State.IDLE;
    private int step;     // how many bytes of the current frame we have read
    private long rawId;   // id as received, including the "extended" flag in bit 31

    public GvretParser(FrameListener listener) {
        this.listener = listener;
    }

    public void processByte(int c) {
        switch (state) {
            case IDLE:
                if (c == 0xF1) state = State.GET_COMMAND;
                break;
            case GET_COMMAND:
                startCommand(c);
                break;
            case CAN_FRAME:
                readCanFrameByte(c);
                break;
            case FD_FRAME:
                readFdFrameByte(c);
                break;
        }
    }

    private void startCommand(int command) {
        if (command == 0) {
            beginFrame(State.CAN_FRAME);
        } else if (command == 20) {
            beginFrame(State.FD_FRAME);
        } else {
            state = State.IDLE; // a command we don't care about
        }
    }

    private void beginFrame(State newState) {
        state = newState;
        step = 0;
        frame.timestamp = 0;
        rawId = 0;
    }

    private void finishFrame() {
        state = State.IDLE;
        listener.onFrame(frame);
    }

    /** Bytes 0-3 are the timestamp, bytes 4-7 are the id (both little endian). */
    private void readTimestampAndId(int c) {
        if (step < 4) {
            frame.timestamp |= (long) c << (8 * step);
        } else {
            rawId |= (long) c << (8 * (step - 4));
        }
        if (step == 7) {
            frame.extended = (rawId & 0x80000000L) != 0;
            frame.id = rawId & 0x7FFFFFFFL;
        }
    }

    private void readCanFrameByte(int c) {
        if (step < 8) {
            readTimestampAndId(c);
        } else if (step == 8) {
            // low nibble = data length, high nibble = bus number
            frame.length = Math.min(c & 0x0F, 8);
            frame.bus = (c & 0xF0) >> 4;
            if (frame.length == 0) finishFrame();
        } else {
            int index = step - 9;
            frame.data[index] = c;
            if (index == frame.length - 1) finishFrame();
        }
        step++;
    }

    private void readFdFrameByte(int c) {
        if (step < 8) {
            readTimestampAndId(c);
        } else if (step == 8) {
            frame.length = c & 0x3F;
        } else if (step == 9) {
            frame.bus = c;
            if (frame.length == 0) finishFrame();
        } else {
            int index = step - 10;
            frame.data[index] = c;
            if (index == frame.length - 1) finishFrame();
        }
        step++;
    }
}
