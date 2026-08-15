package edu.eci.arsw.relicrush.app;

import edu.eci.arsw.relicrush.concurrency.LockPair;
import edu.eci.arsw.relicrush.model.ForgeStation;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;

/**
 * Reproduces the nested-lock problem with two opposite acquisition orders.
 * Threads are daemon threads so the process can exit after the diagnostic.
 */
public final class DeadlockProbe {
    private DeadlockProbe() {
    }

    public static void main(String[] args) throws Exception {
        ForgeStation anvil = new ForgeStation(1, "Arcane Anvil");
        ForgeStation furnace = new ForgeStation(2, "Dragon Furnace");
        CountDownLatch start = new CountDownLatch(1);

        Thread t1 = new Thread(() -> run(start, anvil, furnace), "probe-A-anvil-then-furnace");
        Thread t2 = new Thread(() -> run(start, furnace, anvil), "probe-B-furnace-then-anvil");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        start.countDown();

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + 2_000_000_000L;
        long[] deadlocked = null;
        while (System.nanoTime() < deadline && deadlocked == null) {
            Thread.sleep(25);
            deadlocked = bean.findDeadlockedThreads();
        }

        if (deadlocked == null) {
            System.out.println("NO DEADLOCK DETECTED within 2 seconds.");
            System.out.println("If you already fixed LockPair, this is the expected result.");
            return;
        }

        System.out.println("DEADLOCK DETECTED");
        ThreadInfo[] infos = bean.getThreadInfo(deadlocked, true, true);
        for (ThreadInfo info : infos) {
            System.out.printf("- %s waiting on %s owned by %s%n",
                    info.getThreadName(),
                    info.getLockName(),
                    info.getLockOwnerName());
        }
    }

    private static void run(CountDownLatch start, ForgeStation first, ForgeStation second) {
        try {
            start.await();
            LockPair.withBoth(first, second, () -> {
                // no-op; acquiring both locks is enough for this probe
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
