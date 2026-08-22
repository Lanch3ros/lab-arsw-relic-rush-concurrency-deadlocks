package edu.eci.arsw.relicrush.model;

import java.util.Map;

/**
 * Immutable photo of the game at the end of one completed round.
 *
 * Built by the coordinator after the roundEnd barrier, so every value in it
 * belongs to one well-defined instant in which no adventurer is writing.
 *
 * @param round       the round that just completed (1-based)
 * @param scores      player name -> score, in player order
 * @param ledgerTotal ForgeLedger.totalCrafted() at that instant
 * @param eventCount  number of ForgeEvent entries at that instant
 */
public record RoundSnapshot(
        int round,
        Map<String, Integer> scores,
        int ledgerTotal,
        int eventCount) {

    public int scoreSum() {
        return scores.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean invariantHolds() {
        return scoreSum() == ledgerTotal && ledgerTotal == eventCount;
    }
}
