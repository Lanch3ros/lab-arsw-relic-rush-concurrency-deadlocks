package edu.eci.arsw.relicrush.game;

/**
 * Pause / resume / stop switch plus observer pacing, shared between the UI
 * and the coordinator thread.
 *
 * Coordination is a lock + condition (wait/notify) - never a sleep loop. The
 * coordinator consults {@link #awaitRoundGo()} between rounds, while every
 * adventurer is parked at the roundStart barrier, so pausing can never freeze
 * a thread that is holding a forge station.
 *
 * The round delay is pacing for the human observer (the game is otherwise too
 * fast to watch), not a coordination mechanism: correctness never depends on
 * it, and it is applied at the round boundary where all workers are already
 * waiting for the coordinator anyway.
 */
public final class GameControls {

    private final Object lock = new Object();
    private boolean paused = false;
    private boolean stopped = false;
    private volatile int roundDelayMillis = 0;

    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public void stop() {
        synchronized (lock) {
            stopped = true;
            paused = false;
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    /** Delay applied by the coordinator before each round. 0 = full speed. */
    public void setRoundDelayMillis(int millis) {
        this.roundDelayMillis = Math.max(0, millis);
    }

    /**
     * Called by the coordinator before releasing each round. Applies the
     * observer delay, then blocks while paused.
     *
     * @return true to play the round, false if the game was stopped
     */
    boolean awaitRoundGo() throws InterruptedException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + roundDelayMillis;
            long remaining = roundDelayMillis;
            while (remaining > 0 && !stopped) {
                lock.wait(remaining);           // stop() wakes this immediately
                remaining = deadline - System.currentTimeMillis();
            }
            while (paused && !stopped) {
                lock.wait();                    // resume() or stop() wakes this
            }
            return !stopped;
        }
    }
}
