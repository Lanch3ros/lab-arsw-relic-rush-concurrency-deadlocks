package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeStation;

/**
 * Acquires the two forge stations needed by one craft operation.
 *
 * Deadlock prevention: the monitors are always taken in ascending order of
 * {@link ForgeStation#id()}, whatever order the caller asked for. Because every
 * thread follows the same order, a circular wait cannot form.
 *
 * Locking stays fine-grained: adventurers working on disjoint stations never
 * block each other, so this is not a global lock.
 *
 * The markHeld/markReleased calls only maintain the stations' observer tag for
 * the GUI. They happen strictly inside the monitors this class already holds,
 * touch no lock themselves, and do not alter the acquisition order.
 */
public final class LockPair {

    private LockPair() {
    }

    public static void withBoth(ForgeStation first, ForgeStation second, Runnable action) {
        ForgeStation low = first.id() < second.id() ? first : second;
        ForgeStation high = first.id() < second.id() ? second : first;
        String owner = Thread.currentThread().getName();

        synchronized (low) {
            low.markHeld(owner);
            try {
                synchronized (high) {
                    high.markHeld(owner);
                    try {
                        action.run();
                    } finally {
                        high.markReleased();
                    }
                }
            } finally {
                low.markReleased();
            }
        }
    }
}
