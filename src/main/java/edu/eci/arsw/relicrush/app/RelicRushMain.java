package edu.eci.arsw.relicrush.app;

import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.game.GameEngine;

public final class RelicRushMain {
    private RelicRushMain() {
    }

    public static void main(String[] args) throws Exception {
        GameConfig config = args.length == 3
                ? new GameConfig(
                        Integer.parseInt(args[0]),
                        Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]))
                : GameConfig.defaults();

        System.out.printf(
                "Starting Relic Rush: adventurers=%d, stations=%d, rounds=%d%n",
                config.adventurers(), config.stations(), config.rounds());

        new GameEngine(config).run();
    }
}
