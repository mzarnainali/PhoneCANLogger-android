package com.example.phonecanlogger;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Remembers every CAN ID we have seen and which of them are switched on.
 * Frames whose ID is switched off are neither shown nor logged.
 *
 * Rule for brand-new IDs: if everything is currently switched on, a new ID is
 * switched on too. Once you have switched something off, new IDs start off, so
 * a filter you built does not get flooded by IDs that show up later.
 */
public class FrameFilter {

    private final Set<Long> knownIds = new TreeSet<>();   // kept sorted
    private final Set<Long> enabledIds = new HashSet<>();
    private boolean newIdsEnabled = true;

    /** Call for every frame. Records the ID and says whether the frame should be shown/logged. */
    public synchronized boolean accepts(long id) {
        if (knownIds.add(id) && newIdsEnabled) {
            enabledIds.add(id);
        }
        return enabledIds.contains(id);
    }

    public synchronized boolean isEnabled(long id) {
        return enabledIds.contains(id);
    }

    /** All IDs seen so far, lowest first. */
    public synchronized long[] knownIds() {
        long[] result = new long[knownIds.size()];
        int i = 0;
        for (long id : knownIds) result[i++] = id;
        return result;
    }

    /** Applies the choices from the filter dialog: checked[i] says whether ids[i] is on. */
    public synchronized void apply(long[] ids, boolean[] checked) {
        boolean allOn = true;
        for (int i = 0; i < ids.length; i++) {
            if (checked[i]) {
                enabledIds.add(ids[i]);
            } else {
                enabledIds.remove(ids[i]);
                allOn = false;
            }
        }
        newIdsEnabled = allOn;
    }

    /** True when the filter is hiding (or would hide) something. */
    public synchronized boolean isActive() {
        return !newIdsEnabled || enabledIds.size() < knownIds.size();
    }

    public synchronized int enabledCount() {
        return enabledIds.size();
    }

    public synchronized int knownCount() {
        return knownIds.size();
    }
}
