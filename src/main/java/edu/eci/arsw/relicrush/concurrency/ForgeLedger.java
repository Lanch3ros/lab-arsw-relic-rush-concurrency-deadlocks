package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Global match ledger.
 *
 * Thread safety: {@code totalCrafted} and {@code events} are always read and
 * written while holding {@code lock}. Both are updated inside the same critical
 * section, so no thread can ever observe the counter and the event list
 * disagreeing with each other.
 *
 * The lock guards this object only. Adventurers keep crafting concurrently at
 * their forge stations and meet here just long enough to write one entry.
 */
public final class ForgeLedger {

    /** Private so no other class can lock on this ledger and interfere. */
    private final Object lock = new Object();

    private int totalCrafted = 0;
    private final List<ForgeEvent> events = new ArrayList<>();

    public void record(ForgeEvent event) {
        synchronized (lock) {
            totalCrafted++;
            events.add(event);
        }
    }

    public int totalCrafted() {
        synchronized (lock) {
            return totalCrafted;
        }
    }

    public int eventCount() {
        synchronized (lock) {
            return events.size();
        }
    }

    public List<ForgeEvent> snapshot() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }
}
