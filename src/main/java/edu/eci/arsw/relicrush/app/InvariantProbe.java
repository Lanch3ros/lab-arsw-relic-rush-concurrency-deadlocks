package edu.eci.arsw.relicrush.app;

import edu.eci.arsw.relicrush.game.ConsoleListener;
import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.game.GameEngine;

/**
 * Convenience entry point for stress runs after students have removed deadlocks.
 */
public final class InvariantProbe {
    private InvariantProbe() {
    }

    public static void main(String[] args) throws Exception {
        int players = args.length > 0 ? Integer.parseInt(args[0]) : 32;
        int stations = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        GameEngine engine = new GameEngine(new GameConfig(players, stations, rounds));
        engine.addListener(new ConsoleListener());
        engine.run();
    }
}
