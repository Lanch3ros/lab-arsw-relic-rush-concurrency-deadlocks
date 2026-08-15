package edu.eci.arsw.relicrush.game;

public record GameConfig(int adventurers, int stations, int rounds) {
    public GameConfig {
        if (adventurers < 2) throw new IllegalArgumentException("adventurers must be >= 2");
        if (stations < 2) throw new IllegalArgumentException("stations must be >= 2");
        if (rounds < 1) throw new IllegalArgumentException("rounds must be >= 1");
    }

    public static GameConfig defaults() {
        return new GameConfig(8, 6, 25);
    }
}
