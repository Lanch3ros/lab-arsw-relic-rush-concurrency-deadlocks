package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.model.RoundSnapshot;

import java.util.Comparator;
import java.util.Map;

/**
 * Reproduces the starter's console output, line for line. Keeping the format
 * identical lets the existing probes and evidence stay valid.
 */
public final class ConsoleListener implements GameListener {

    @Override
    public void onRoundCompleted(RoundSnapshot s) {
        System.out.printf(
                "ROUND %02d | scoreSum=%d | ledger=%d | events=%d | invariant=%s%n",
                s.round(),
                s.scoreSum(),
                s.ledgerTotal(),
                s.eventCount(),
                s.invariantHolds() ? "OK" : "BROKEN");
    }

    @Override
    public void onGameFinished(RoundSnapshot s) {
        System.out.println("\n=== RELIC RUSH - FINAL SCORE ===");
        s.scores().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> System.out.printf("%-16s %4d relics%n", e.getKey(), e.getValue()));

        System.out.printf("Total by players : %d%n", s.scoreSum());
        System.out.printf("Ledger total     : %d%n", s.ledgerTotal());
        System.out.printf("Ledger events    : %d%n", s.eventCount());
    }
}
