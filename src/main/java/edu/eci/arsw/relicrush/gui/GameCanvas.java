package edu.eci.arsw.relicrush.gui;

import edu.eci.arsw.relicrush.model.ForgeEvent;
import edu.eci.arsw.relicrush.model.ForgeStation;
import edu.eci.arsw.relicrush.model.RoundSnapshot;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game scene: forge stations in a ring, adventurers as colored figures
 * that walk to the two stations they used and strike a crafting spark.
 *
 * Honesty rule: a craft holds its two station monitors for microseconds, far
 * too fast for any animation to catch live. So the scene REPLAYS each round
 * from the ForgeEvents the ledger recorded - the very events the invariant
 * counts. What you watch is the round that just completed, not an invented
 * animation. Live lock state is still shown: a station whose heldBy tag is
 * set at paint time turns red with its owner's name.
 *
 * All fields are written and read on the EDT only (snapshots arrive via
 * invokeLater; the animation timer is a javax.swing.Timer). The only
 * cross-thread reads are each station's AtomicReference tag - never a lock.
 */
public final class GameCanvas extends JPanel {

    private static final Color BG_TOP = new Color(0x141821);
    private static final Color BG_BOTTOM = new Color(0x1d2330);
    private static final Color STATION_FILL = new Color(0x262d3a);
    private static final Color STATION_FREE = new Color(0x3a7a6a);
    private static final Color STATION_USED = new Color(0xd28a2e);
    private static final Color STATION_HELD = new Color(0xc0392b);
    private static final Color TEXT = new Color(0xd8dee9);
    private static final Color SPARK = new Color(0xffe27a);

    private List<ForgeStation> stations = List.of();
    private int adventurers = 0;
    private Color[] palette = new Color[0];
    private double[][] pos = new double[0][2];          // current figure positions
    private Map<String, Integer> scores = Map.of();
    private Map<Integer, int[]> craftedPair = Map.of(); // adventurer idx -> station ids
    private long roundShownAt = 0;
    private int round = 0;
    private int totalRounds = 0;
    private int relics = 0;
    private boolean paused = false;
    private boolean started = false;
    private String banner = null;

    public GameCanvas() {
        setOpaque(true);
        new Timer(33, e -> tick()).start();
    }

    /** New match: fresh geometry, everyone at home. */
    public void reset(List<ForgeStation> stations, int adventurers, int totalRounds) {
        this.stations = stations;
        this.adventurers = adventurers;
        this.totalRounds = totalRounds;
        this.palette = new Color[adventurers];
        this.pos = new double[adventurers][2];
        for (int i = 0; i < adventurers; i++) {
            palette[i] = Color.getHSBColor(i / (float) adventurers, 0.62f, 0.95f);
            double[] home = homeOf(i);
            pos[i][0] = home[0];
            pos[i][1] = home[1];
        }
        scores = Map.of();
        craftedPair = Map.of();
        round = 0;
        relics = 0;
        banner = null;
        paused = false;
        started = true;
    }

    /** Called on the EDT (via invokeLater) with each completed round. */
    public void applySnapshot(RoundSnapshot s) {
        round = s.round();
        relics = s.ledgerTotal();
        scores = s.scores();
        Map<Integer, int[]> pairs = new HashMap<>();
        for (ForgeEvent e : s.newEvents()) {
            int idx = indexOf(e.adventurer());
            if (idx < 0) continue;
            pairs.put(idx, new int[] {stationIdByName(e.firstStation()), stationIdByName(e.secondStation())});
        }
        craftedPair = pairs;
        roundShownAt = System.currentTimeMillis();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void setBanner(String banner) {
        this.banner = banner;
    }

    private void tick() {
        for (int i = 0; i < adventurers; i++) {
            int[] pair = craftedPair.get(i);
            // Blend toward home so figures fan out instead of stacking at the
            // ring's center; the lines to the stations keep the pairing clear.
            double[] target;
            if (pair == null) {
                target = homeOf(i);
            } else {
                double[] mid = midOf(pair);
                double[] home = homeOf(i);
                target = new double[] {
                        mid[0] * 0.6 + home[0] * 0.4,
                        mid[1] * 0.6 + home[1] * 0.4};
            }
            pos[i][0] += (target[0] - pos[i][0]) * 0.18;
            pos[i][1] += (target[1] - pos[i][1]) * 0.18;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
        g.fillRect(0, 0, w, h);

        if (!started) {
            centered(g, "Press Start to begin the match", w / 2, h / 2, 16, TEXT);
            return;
        }

        boolean spark = System.currentTimeMillis() - roundShownAt < 700;

        // connection lines first, under everything
        g.setStroke(new BasicStroke(2f));
        for (Map.Entry<Integer, int[]> e : craftedPair.entrySet()) {
            int i = e.getKey();
            Color c = palette[i];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
            for (int sid : e.getValue()) {
                double[] sp = stationPosById(sid);
                if (sp != null) g.draw(new Line2D.Double(pos[i][0], pos[i][1], sp[0], sp[1]));
            }
        }

        // stations
        for (int i = 0; i < stations.size(); i++) {
            ForgeStation st = stations.get(i);
            double[] p = stationPos(i);
            String owner = st.heldBy();                    // atomic read, no lock
            boolean usedThisRound = spark && pairUses(st.id());
            Color border = owner != null ? STATION_HELD : usedThisRound ? STATION_USED : STATION_FREE;

            RoundRectangle2D box = new RoundRectangle2D.Double(p[0] - 44, p[1] - 26, 88, 52, 14, 14);
            g.setColor(STATION_FILL);
            g.fill(box);
            g.setStroke(new BasicStroke(owner != null || usedThisRound ? 3f : 1.6f));
            g.setColor(border);
            g.draw(box);
            centered(g, "#" + st.id(), (int) p[0], (int) p[1] - 6, 12, border);
            centered(g, shortName(st.name()), (int) p[0], (int) p[1] + 10, 11, TEXT);
            if (owner != null) {
                centered(g, owner, (int) p[0], (int) p[1] + 38, 10, STATION_HELD);
            }
        }

        // adventurers
        boolean compact = adventurers > 24;
        int r = compact ? 5 : 11;
        for (int i = 0; i < adventurers; i++) {
            double x = pos[i][0], y = pos[i][1];
            boolean crafting = craftedPair.containsKey(i) && spark;
            if (crafting) drawSpark(g, x, y - r - 10, compact ? 6 : 10);
            g.setColor(palette[i]);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            g.setColor(BG_TOP);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            if (!compact) {
                Integer sc = scores.get("adventurer-" + (i + 1));
                centered(g, "A" + (i + 1) + (sc != null ? " · " + sc : ""),
                        (int) x, (int) y + r + 12, 11, palette[i]);
            }
        }

        // HUD
        g.setColor(TEXT);
        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        g.drawString(round > 0 ? "Round " + round + " / " + totalRounds : "Waiting for round 1...", 14, 24);
        String relicText = "Relics crafted: " + relics;
        g.drawString(relicText, w - g.getFontMetrics().stringWidth(relicText) - 14, 24);

        if (banner != null) {
            overlay(g, w, h, banner);
        } else if (paused) {
            overlay(g, w, h, "PAUSED - resumes at the round boundary");
        }
    }

    // ---- helpers ----

    private void overlay(Graphics2D g, int w, int h, String text) {
        g.setColor(new Color(0, 0, 0, 110));
        g.fillRect(0, 0, w, h);
        centered(g, text, w / 2, h / 2, 18, Color.WHITE);
    }

    private void drawSpark(Graphics2D g, double cx, double cy, int size) {
        Path2D star = new Path2D.Double();
        for (int k = 0; k < 10; k++) {
            double ang = Math.PI / 5 * k - Math.PI / 2;
            double rad = k % 2 == 0 ? size : size * 0.45;
            double x = cx + rad * Math.cos(ang), y = cy + rad * Math.sin(ang);
            if (k == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.closePath();
        g.setColor(SPARK);
        g.fill(star);
    }

    private void centered(Graphics2D g, String text, int x, int y, int size, Color color) {
        g.setFont(getFont().deriveFont((float) size));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(color);
        g.drawString(text, x - fm.stringWidth(text) / 2, y);
    }

    private double[] stationPos(int i) {
        int w = getWidth(), h = getHeight();
        double a = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, stations.size());
        double rad = Math.min(w, h) * 0.26;
        return new double[] {w / 2.0 + rad * Math.cos(a), h / 2.0 + rad * Math.sin(a)};
    }

    private double[] homeOf(int i) {
        int w = Math.max(getWidth(), 200), h = Math.max(getHeight(), 200);
        double a = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, adventurers) + Math.PI / adventurers;
        double rad = Math.min(w, h) * 0.43;
        return new double[] {w / 2.0 + rad * Math.cos(a), h / 2.0 + rad * Math.sin(a)};
    }

    private double[] midOf(int[] pair) {
        double[] a = stationPosById(pair[0]), b = stationPosById(pair[1]);
        if (a == null || b == null) return new double[] {getWidth() / 2.0, getHeight() / 2.0};
        return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
    }

    private double[] stationPosById(int id) {
        for (int i = 0; i < stations.size(); i++)
            if (stations.get(i).id() == id) return stationPos(i);
        return null;
    }

    private boolean pairUses(int stationId) {
        for (int[] pair : craftedPair.values())
            if (pair[0] == stationId || pair[1] == stationId) return true;
        return false;
    }

    private int stationIdByName(String name) {
        for (ForgeStation st : stations)
            if (st.name().equals(name)) return st.id();
        return -1;
    }

    private static String shortName(String name) {
        return name.length() > 16 ? name.substring(0, 15) + "…" : name;
    }

    private static int indexOf(String threadName) {
        try {
            return Integer.parseInt(threadName.substring(threadName.lastIndexOf('-') + 1)) - 1;
        } catch (Exception e) {
            return -1;
        }
    }
}
