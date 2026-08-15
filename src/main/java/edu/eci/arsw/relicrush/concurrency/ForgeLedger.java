package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Global match ledger.
 *
 * Starter implementation is intentionally NOT thread-safe.
 */
public final class ForgeLedger {
    private int totalCrafted = 0;
    private final List<ForgeEvent> events = new ArrayList<>();

    public void record(ForgeEvent event) {
        // TODO LAB 3: ++ is a read-modify-write operation and ArrayList is not
        // designed for concurrent writes. Fix both responsibilities without
        // serializing the entire game behind one global monitor.
        int next = totalCrafted + 1;
        Thread.yield();
        totalCrafted = next;
        events.add(event);
    }

    public int totalCrafted() {
        return totalCrafted;
    }

    public int eventCount() {
        return events.size();
    }

    public List<ForgeEvent> snapshot() {
        return List.copyOf(events);
    }
}
