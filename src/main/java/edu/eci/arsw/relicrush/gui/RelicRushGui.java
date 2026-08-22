package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.game.GameConfig;
import edu.eci.arsw.relicrush.game.GameEngine;
import edu.eci.arsw.relicrush.game.GameListener;
import edu.eci.arsw.relicrush.model.RoundSnapshot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Swing viewer for Relic Rush: a game scene (GameCanvas) plus the controls.
 * It is one more GameListener: the game engine does not know it exists, and
 * no synchronization mechanism of the game was changed to support it.
 *
 * Threading rules:
 * - GameListener callbacks arrive on the game-coordinator thread and are
 *   forwarded to the Swing thread (EDT) with SwingUtilities.invokeLater.
 *   Game threads never touch a Swing component directly.
 * - Station lights are refreshed by a javax.swing.Timer, which runs on the
 *   EDT and reads only each station's AtomicReference tag. The viewer never
 *   synchronizes on a ForgeStation, so it can never become a participant in
 *   the game's locking.
 * - The buttons call GameControls, whose methods use its own private lock.
 * - Stop is permanent for an engine (its adventurer threads exit), so Start
 *   always builds a fresh GameEngine. That mirrors how the game is designed:
 *   one engine, one match.
 */
public final class RelicRushGui implements GameListener {

    private static final Color FREE = new Color(0x2e8b57);
    private static final Color BUSY = new Color(0xc0392b);

    private final GameConfig config;
    private final JFrame frame = new JFrame("Relic Rush");
    private final JButton startBtn = new JButton("Start");
    private final JButton pauseBtn = new JButton("Pause");
    private final JButton resumeBtn = new JButton("Resume");
    private final JButton stopBtn = new JButton("Stop");
    private final JSlider speedSlider = new JSlider(0, 500, 150);
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel invariantLabel = new JLabel(" ");
    private final GameCanvas canvas = new GameCanvas();

    /** Written and read only on the EDT. */
    private GameEngine engine;

    public RelicRushGui(GameConfig config) {
        this.config = config;
        buildUi();
    }

    private void buildUi() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        controls.add(startBtn);
        controls.add(pauseBtn);
        controls.add(resumeBtn);
        controls.add(stopBtn);
        controls.add(new JLabel("   Round delay (ms):"));
        speedSlider.setMajorTickSpacing(250);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        controls.add(speedSlider);

        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        invariantLabel.setFont(invariantLabel.getFont().deriveFont(Font.BOLD));
        JPanel south = new JPanel(new GridLayout(2, 1));
        south.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        south.add(statusLabel);
        south.add(invariantLabel);

        frame.setLayout(new BorderLayout(8, 8));
        frame.add(controls, BorderLayout.NORTH);
        frame.add(canvas, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);

        startBtn.addActionListener(e -> startGame());
        pauseBtn.addActionListener(e -> {
            engine.controls().pause();
            canvas.setPaused(true);
            setButtons(false, false, true, true);
            statusLabel.setText(statusLabel.getText() + "  [pausing at round boundary]");
        });
        resumeBtn.addActionListener(e -> {
            engine.controls().resume();
            canvas.setPaused(false);
            setButtons(false, true, false, true);
        });
        stopBtn.addActionListener(e -> engine.controls().stop());
        speedSlider.addChangeListener(e -> {
            if (engine != null) engine.controls().setRoundDelayMillis(speedSlider.getValue());
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (engine != null) engine.controls().stop();
            }
        });
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        setButtons(true, false, false, false);
        statusLabel.setText(String.format("Ready. adventurers=%d, stations=%d, rounds=%d",
                config.adventurers(), config.stations(), config.rounds()));
        frame.setSize(860, 640);
        frame.setLocationRelativeTo(null);
    }

    public void show() {
        frame.setVisible(true);
    }

    /** Start always builds a fresh engine: stop is permanent for a match. */
    private void startGame() {
        engine = new GameEngine(config);
        engine.addListener(this);
        engine.controls().setRoundDelayMillis(speedSlider.getValue());

        canvas.reset(engine.stations(), config.adventurers(), config.rounds());
        invariantLabel.setText(" ");
        statusLabel.setText("Running...");
        setButtons(false, true, false, true);

        Thread coordinator = new Thread(() -> {
            try {
                engine.run();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Game aborted: " + ex));
            }
        }, "game-coordinator");
        coordinator.start();
    }

    // ---- GameListener callbacks: arrive on the coordinator thread ----

    @Override
    public void onRoundCompleted(RoundSnapshot s) {
        SwingUtilities.invokeLater(() -> {
            canvas.applySnapshot(s);
            statusLabel.setText(String.format(
                    "ROUND %02d | scoreSum=%d | ledger=%d | events=%d",
                    s.round(), s.scoreSum(), s.ledgerTotal(), s.eventCount()));
            invariantLabel.setText(s.invariantHolds() ? "invariant=OK" : "invariant=BROKEN");
            invariantLabel.setForeground(s.invariantHolds() ? FREE : BUSY);
        });
    }

    @Override
    public void onGameFinished(RoundSnapshot s) {
        SwingUtilities.invokeLater(() -> {
            canvas.setBanner("FINISHED - " + s.ledgerTotal() + " relics crafted");
            statusLabel.setText(String.format(
                    "FINISHED after %d rounds | scoreSum=%d | ledger=%d | events=%d",
                    s.round(), s.scoreSum(), s.ledgerTotal(), s.eventCount()));
            setButtons(true, false, false, false);
        });
    }

    @Override
    public void onGameStopped(RoundSnapshot s) {
        SwingUtilities.invokeLater(() -> {
            canvas.setPaused(false);
            canvas.setBanner("STOPPED at round " + s.round());
            statusLabel.setText(String.format(
                    "STOPPED at round %d | scoreSum=%d | ledger=%d | events=%d",
                    s.round(), s.scoreSum(), s.ledgerTotal(), s.eventCount()));
            invariantLabel.setText(s.invariantHolds() ? "invariant=OK" : "invariant=BROKEN");
            invariantLabel.setForeground(s.invariantHolds() ? FREE : BUSY);
            setButtons(true, false, false, false);
        });
    }

    private void setButtons(boolean start, boolean pause, boolean resume, boolean stop) {
        startBtn.setEnabled(start);
        pauseBtn.setEnabled(pause);
        resumeBtn.setEnabled(resume);
        stopBtn.setEnabled(stop);
    }
}
