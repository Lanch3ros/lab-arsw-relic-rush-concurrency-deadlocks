package edu.eci.arsw.relicrush.model;

import java.util.List;
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
 * @param newEvents   the ForgeEvents recorded since the previous snapshot,
 *                    i.e. this round's crafts. Viewers use them to replay the
 *                    round (who used which two stations); the counts above are
 *                    what the invariant checks.
 */
public record RoundSnapshot(
        int round,
        Map<String, Integer> scores,
        int ledgerTotal,
        int eventCount,
        List<ForgeEvent> newEvents) {

    public int scoreSum() {
        return scores.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean invariantHolds() {
        return scoreSum() == ledgerTotal && ledgerTotal == eventCount;
    }
}
