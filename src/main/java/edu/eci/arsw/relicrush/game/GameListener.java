package edu.eci.arsw.relicrush.game;

import edu.eci.arsw.relicrush.model.RoundSnapshot;

/**
 * Receives game news from the engine. The engine plays the match; listeners
 * decide how to show it (console, GUI, ...). Callbacks run on the coordinator
 * thread, so a listener that needs another thread (e.g. Swing) must hand the
 * work over itself.
 */
public interface GameListener {

    /** Called after every completed round, outside any lock. */
    void onRoundCompleted(RoundSnapshot snapshot);

    /** Called once, after all adventurers have finished the last round. */
    void onGameFinished(RoundSnapshot finalSnapshot);
}
