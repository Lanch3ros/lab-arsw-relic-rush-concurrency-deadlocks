package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.concurrency.ForgeLedger;
import edu.eci.arsw.relicrush.model.ForgeStation;
import edu.eci.arsw.relicrush.model.RoundSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final List<GameListener> listeners = new ArrayList<>();
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

    /**
     * Registers a viewer. Must be called before {@link #run()}; the listener
     * list is not modified once the game starts, so it needs no locking.
     */
    public void addListener(GameListener listener) {
        listeners.add(listener);
    }

    public List<ForgeStation> stations() {
        return stations;
    }

    public GameConfig config() {
        return config;
    }

    public void run() throws InterruptedException, BrokenBarrierException {
        startDeadlockWatchdog();
        adventurers.forEach(Thread::start);

        for (int round = 1; round <= config.rounds(); round++) {
            // Scenario 2: workers wait until the coordinator starts the round.
            roundStart.await();

            // Scenario 3: coordinator waits until every worker completes the round.
            roundEnd.await();

            RoundSnapshot snapshot = buildSnapshot(round);
            for (GameListener listener : listeners) {
                listener.onRoundCompleted(snapshot);
            }
        }

        for (Adventurer adventurer : adventurers) {
            adventurer.join();
        }

        finished.set(true);
        RoundSnapshot finalSnapshot = buildSnapshot(config.rounds());
        for (GameListener listener : listeners) {
            listener.onGameFinished(finalSnapshot);
        }
    }

    /**
     * Safe to call only where the barriers guarantee no adventurer is writing:
     * after roundEnd, or after all adventurers have been joined.
     */
    private RoundSnapshot buildSnapshot(int round) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Adventurer adventurer : adventurers) {
            scores.put(adventurer.getName(), adventurer.score());
        }
        return new RoundSnapshot(
                round,
                java.util.Collections.unmodifiableMap(scores),
                ledger.totalCrafted(),
                ledger.eventCount());
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
