package com.example.phonecanlogger;

import java.util.HashMap;
import java.util.Map;

/**
 * Compares every frame with the previous frame that had the same bus and ID,
 * and remembers when each bit last changed.
 *
 * Call update() for every frame, in the order they arrived. It stores a snapshot
 * of the bit times in the row, so the row can be coloured later.
 */
public class ChangeTracker {

    /** What we remember about one bus + ID. */
    private static class IdState {
        final int[] previousData;
        final long[] lastChangeMs;

        IdState(int[] data) {
            previousData = data.clone();
            lastChangeMs = new long[data.length * 8];
            java.util.Arrays.fill(lastChangeMs, FrameRow.NEVER);
        }
    }

    private final Map<Long, IdState> states = new HashMap<>();

    /** Returns true if at least one bit differs from the previous frame with this ID. */
    public boolean update(FrameRow row, long nowMs) {
        IdState state = states.get(row.key());
        boolean anyBitChanged = false;

        if (state == null || state.previousData.length != row.length) {
            // First frame for this ID (or its length changed): nothing to compare with yet.
            state = new IdState(row.data);
            states.put(row.key(), state);
        } else {
            for (int i = 0; i < row.length; i++) {
                int difference = state.previousData[i] ^ row.data[i];
                for (int bit = 0; bit < 8; bit++) {
                    if ((difference & (1 << bit)) != 0) {
                        state.lastChangeMs[i * 8 + bit] = nowMs;
                        anyBitChanged = true;
                    }
                }
                state.previousData[i] = row.data[i];
            }
        }

        row.bitChangeMs = state.lastChangeMs.clone();
        return anyBitChanged;
    }

    /** Forget everything, so the next frame of each ID is a fresh start. */
    public void clear() {
        states.clear();
    }
}
