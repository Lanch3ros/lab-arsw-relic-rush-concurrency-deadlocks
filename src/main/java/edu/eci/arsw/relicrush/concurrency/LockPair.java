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
 */
public final class LockPair {

    private LockPair() {
    }

    public static void withBoth(ForgeStation first, ForgeStation second, Runnable action) {
        if (first.id() < second.id()) {
            synchronized (first) {
                synchronized (second) {
                    action.run();
                }
            }
        } else {
            synchronized (second) {
                synchronized (first) {
                    action.run();
                }
            }
        }
    }
}
