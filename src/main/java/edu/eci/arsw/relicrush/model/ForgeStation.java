package edu.eci.arsw.relicrush.model;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Exclusive resource used to craft a relic. The station itself is the monitor.
 *
 * The {@code heldBy} tag exists for observers (the GUI): it names the thread
 * currently crafting here, or null when the station is free. It is written by
 * LockPair while already holding this station's monitor, and read by viewers
 * WITHOUT taking the monitor - observers must never synchronize on a station,
 * or they would become a new participant in the locking the lab reasons about.
 */
public final class ForgeStation {
    private final int id;
    private final String name;
    private final AtomicReference<String> heldBy = new AtomicReference<>();

    public ForgeStation(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Thread currently crafting at this station, or null when free. */
    public String heldBy() {
        return heldBy.get();
    }

    /** Called by LockPair only, while holding this station's monitor. */
    public void markHeld(String owner) {
        heldBy.set(owner);
    }

    /** Called by LockPair only, while holding this station's monitor. */
    public void markReleased() {
        heldBy.set(null);
    }

    @Override
    public String toString() {
        return name + "(#" + id + ")";
    }
}
