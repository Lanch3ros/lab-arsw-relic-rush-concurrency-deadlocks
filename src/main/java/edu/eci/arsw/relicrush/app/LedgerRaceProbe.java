package edu.eci.arsw.relicrush.app;

import edu.eci.arsw.relicrush.concurrency.ForgeLedger;
import edu.eci.arsw.relicrush.model.ForgeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Isolates ForgeLedger so students can study thread safety without first
 * solving the nested-lock deadlock.
 */
public final class LedgerRaceProbe {
    private LedgerRaceProbe() {
    }

    public static void main(String[] args) throws Exception {
        int workers = args.length > 0 ? Integer.parseInt(args[0]) : 32;
        int writesPerWorker = args.length > 1 ? Integer.parseInt(args[1]) : 2_000;

        ForgeLedger ledger = new ForgeLedger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            int workerId = i;
            Thread thread = Thread.ofPlatform().name("ledger-writer-" + i).unstarted(() -> {
                try {
                    start.await();
                    for (int j = 1; j <= writesPerWorker; j++) {
                        ledger.record(new ForgeEvent(j, "writer-" + workerId, "A", "B", j));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        int expected = workers * writesPerWorker;
        System.out.printf("expected=%d totalCrafted=%d eventCount=%d invariant=%s%n",
                expected,
                ledger.totalCrafted(),
                ledger.eventCount(),
                (expected == ledger.totalCrafted() && expected == ledger.eventCount()) ? "OK" : "BROKEN");
    }
}
