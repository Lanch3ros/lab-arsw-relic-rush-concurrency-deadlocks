package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.concurrency.ForgeLedger;
import edu.eci.arsw.relicrush.model.ForgeStation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameEngine {
    private final GameConfig config;
    private final ForgeLedger ledger = new ForgeLedger();
    private final List<ForgeStation> stations;
    private final List<Adventurer> adventurers = new ArrayList<>();
    private final CyclicBarrier roundStart;
    private final CyclicBarrier roundEnd;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public GameEngine(GameConfig config) {
        this.config = config;
        this.stations = createStations(config.stations());
        this.roundStart = new CyclicBarrier(config.adventurers() + 1);
        this.roundEnd = new CyclicBarrier(config.adventurers() + 1);

        for (int i = 1; i <= config.adventurers(); i++) {
            adventurers.add(new Adventurer(
                    i,
                    stations,
                    ledger,
                    roundStart,
                    roundEnd,
                    config.rounds()));
        }
    }

    public void run() throws InterruptedException, BrokenBarrierException {
        startDeadlockWatchdog();
        adventurers.forEach(Thread::start);

        for (int round = 1; round <= config.rounds(); round++) {
            // Scenario 2: workers wait until the coordinator starts the round.
            roundStart.await();

            // Scenario 3: coordinator waits until every worker completes the round.
            roundEnd.await();

            printRoundSnapshot(round);
        }

        for (Adventurer adventurer : adventurers) {
            adventurer.join();
        }

        finished.set(true);
        printFinalSummary();
    }

    private void startDeadlockWatchdog() {
        Thread watchdog = new Thread(() -> {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            while (!finished.get()) {
                long[] ids = bean.findDeadlockedThreads();
                if (ids != null && ids.length > 0) {
                    System.err.println("\n*** DEADLOCK DETECTED BY GAME WATCHDOG ***");
                    System.err.println("Run DeadlockProbe or jcmd <PID> Thread.print for a focused diagnosis.");
                    System.err.println("The starter exits here so you do not have to kill a frozen process manually.\n");
                    System.exit(2);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "deadlock-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void printRoundSnapshot(int round) {
        int scoreSum = adventurers.stream().mapToInt(Adventurer::score).sum();
        int ledgerTotal = ledger.totalCrafted();
        int eventCount = ledger.eventCount();

        System.out.printf(
                "ROUND %02d | scoreSum=%d | ledger=%d | events=%d | invariant=%s%n",
                round,
                scoreSum,
                ledgerTotal,
                eventCount,
                (scoreSum == ledgerTotal && ledgerTotal == eventCount) ? "OK" : "BROKEN");
    }

    private void printFinalSummary() {
        System.out.println("\n=== RELIC RUSH - FINAL SCORE ===");
        adventurers.stream()
                .sorted(Comparator.comparingInt(Adventurer::score).reversed())
                .forEach(a -> System.out.printf("%-16s %4d relics%n", a.getName(), a.score()));

        int scoreSum = adventurers.stream().mapToInt(Adventurer::score).sum();
        System.out.printf("Total by players : %d%n", scoreSum);
        System.out.printf("Ledger total     : %d%n", ledger.totalCrafted());
        System.out.printf("Ledger events    : %d%n", ledger.eventCount());
    }

    private static List<ForgeStation> createStations(int count) {
        String[] names = {
                "Arcane Anvil", "Crystal Lens", "Rune Press", "Dragon Furnace",
                "Moon Altar", "Obsidian Table", "Echo Forge", "Solar Crucible"
        };
        List<ForgeStation> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new ForgeStation(i + 1, names[i % names.length] + " " + (i + 1)));
        }
        return List.copyOf(result);
    }
}
