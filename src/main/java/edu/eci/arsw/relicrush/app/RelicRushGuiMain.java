package edu.eci.arsw.relicrush.app;

import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.gui.RelicRushGui;

import javax.swing.SwingUtilities;

/** GUI entry point. Same arguments as RelicRushMain. */
public final class RelicRushGuiMain {
    private RelicRushGuiMain() {
    }

    public static void main(String[] args) {
        GameConfig config = args.length == 3
                ? new GameConfig(
                        Integer.parseInt(args[0]),
                        Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]))
                : GameConfig.defaults();

        SwingUtilities.invokeLater(() -> new RelicRushGui(config).show());
    }
}
