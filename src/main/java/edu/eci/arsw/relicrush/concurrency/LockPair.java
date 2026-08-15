package edu.eci.arsw.relicrush.concurrency;

import edu.eci.arsw.relicrush.model.ForgeStation;

/**
 * Starter implementation intentionally contains a deadlock risk.
 *
 * Students: do NOT replace this with one global lock. Preserve concurrency
 * between forge operations that use disjoint stations.
 */
public final class LockPair {

    private LockPair() {
    }

    public static void withBoth(ForgeStation first, ForgeStation second, Runnable action) {
        // TODO LAB 3: This acquisition strategy can create circular wait.
        // Fix it using a deterministic ordering strategy (or justify another
        // deadlock-prevention approach) while preserving fine-grained locking.
        synchronized (first) {
            // This small delay makes the deadlock easier to reproduce in the starter.
            sleepQuietly(2);
            synchronized (second) {
                action.run();
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
