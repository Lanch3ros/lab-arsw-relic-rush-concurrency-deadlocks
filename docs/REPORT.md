# ARSW Lab 3 - Relic Rush - Delivery Report

## Team

| Student                   | ID         | GitHub                                      |
|---------------------------|------------|---------------------------------------------|
| Jose Luis Lancheros Ayora | 1000102647 | [Lanch3ros](https://github.com/Lanch3ros)   |
| Gina Sofia Garcia Zapata  | 1000100098 | [sofiapeace](https://github.com/sofiapeace) |

Repository: `https://github.com/Lanch3ros/lab-arsw-relic-rush-concurrency-deadlocks`

Final commit: `TBD`

**Baseline environment**

| Item            | Value                                                      |
|-----------------|------------------------------------------------------------|
| JDK (runtime)   | OpenJDK 21.0.11 (Homebrew), 64-Bit Server VM               |
| Maven           | 3.9.16                                                     |
| OS / CPU        | macOS 26.5.2, Apple Silicon (arm64), 10 logical cores (M5) |
| Baseline commit | `a4bdd20`                                                  |

---

## 1. Baseline observations

This section documents the behavior of the commit `a4bdd20`,
before any fix was applied. Its purpose is to establish, with reproducible evidence,
that the two defect families announced in the README are real and observable.

All output in this section was produced on the machine described above. Each probe
was executed **three consecutive times**, because a single run of a concurrent
program proves nothing: the absence of a failure in one execution is not evidence
of thread safety.

### 1.1 Commands executed

```bash
# build + smoke test
mvn clean test

# defect family 1: shared-state race, isolated from the deadlock
java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000

# defect family 2: nested-lock deadlock, isolated from the ledger
java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe

# the full game
java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain
java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 21 11 57
java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 3 8 25

# OS-level confirmation of the wait-for cycle
jps -l
jcmd <PID> Thread.print
```

### 1.2 What happened

**The project builds and the test suite passes.** This is itself the first
observation worth recording: `mvn clean test` reports `Tests run: 1, Failures: 0`
and `BUILD SUCCESS`. The only test present (`StarterSmokeTest`) merely asserts
`Runtime.version().feature() >= 21`. So **a green build says nothing at all about
correctness here** - there is currently no test that exercises the invariant.

**Defect family 1 - the ledger loses updates (`ForgeLedger`).**
`LedgerRaceProbe` was wrong in 6 out of 6 runs. The two shared fields fail in
*very different proportions*, which is the key diagnostic clue:

| Config | Expected | `totalCrafted` | Counter loss | `eventCount` | List loss |
|---|---:|---:|---:|---:|---:|
| 32 x 2000 | 64 000 | 3 160 | **95.06 %** | 59 893 | 6.42 % |
| 32 x 2000 | 64 000 | 3 442 | **94.62 %** | 59 593 | 6.89 % |
| 32 x 2000 | 64 000 | 3 723 | **94.18 %** | 59 488 | 7.05 % |
| 64 x 5000 | 320 000 | 9 143 | **97.14 %** | 301 397 | 5.81 % |
| 64 x 5000 | 320 000 | 11 903 | **96.28 %** | 299 985 | 6.25 % |
| 64 x 5000 | 320 000 | 9 329 | **97.08 %** | 301 419 | 5.81 % |

Two facts stand out:

1. **The counter loses roughly 15x more than the list** (94-97 % versus 6-7 %),
   consistently in every run.
2. **The counter degrades as concurrency grows** (95 % at 32 workers to 97 % at
   64 workers) while the list loss stays flat at ~6 %.

Both facts are explained by the code itself
([`ForgeLedger.java:21-24`](../src/main/java/edu/eci/arsw/relicrush/concurrency/ForgeLedger.java#L21)):

```java
int next = totalCrafted + 1;   // read
Thread.yield();                // scheduler is invited to switch here
totalCrafted = next;           // write  -> lost update
events.add(event);             // ArrayList: no concurrency guarantee
```

`totalCrafted++` has been split into an explicit **read-modify-write** with a
`Thread.yield()` planted in the middle of the vulnerable window. Every thread
descheduled at that point later writes back a value computed from a stale read,
so almost every concurrent increment is overwritten - and the more threads
compete, the worse it gets. `ArrayList.add()` is a far narrower window (bounds
check, array store, size increment), hence the smaller but still fatal loss rate.

No exception was thrown in these runs, but `ArrayList` offers no such guarantee:
concurrent resizing can also produce `ArrayIndexOutOfBoundsException` or leave
`null` holes. **Silent data loss is the common case; a crash is the lucky case,
because at least a crash is visible.**

**Defect family 2 - deterministic deadlock (`LockPair`).**
`DeadlockProbe` deadlocked in **3 out of 3 runs**, always with the same mirrored
wait pattern. This defect needs no coaxing: the `sleepQuietly(2)` between the two
`synchronized` blocks
([`LockPair.java:20-26`](../src/main/java/edu/eci/arsw/relicrush/concurrency/LockPair.java#L20))
guarantees both threads hold their first monitor before either requests the second.

### 1.3 Was the round invariant always preserved?

**No.** The invariant is violated inside the game as well, not only in the
isolated probe. With the default 8/6/25 configuration the game deadlocks before
printing a single `ROUND` line, so a looser 3/8/25 configuration was used to let a
round actually complete and expose the arithmetic. The same command was run three
times:

| Run | `scoreSum` | `ledger` | `events` | Verdict |
|---|---:|---:|---:|---|
| 1 | 3 | 2 | **3** | `invariant=BROKEN` |
| 2 | 3 | 2 | **2** | `invariant=BROKEN` |
| 3 | 3 | 2 | **2** | `invariant=BROKEN` |

Three conclusions follow directly from this table:

1. **Identical input produced different corruption.** Run 1 lost one counter
   increment but kept all three events; runs 2 and 3 lost one of each. Same
   command, same configuration, same machine - different damage. This is the
   concrete demonstration of why *a single passing run can never be accepted as
   evidence of thread safety*, and why every measurement in this report is
   repeated.
2. **`scoreSum` is never wrong.** Each `score++` executes on the owning player's
   own thread
   ([`Adventurer.java:79`](../src/main/java/edu/eci/arsw/relicrush/game/Adventurer.java#L79))
   and is therefore uncontended. All damage is confined to *shared* state, which
   correctly localises the defect to `ForgeLedger`.
3. **The two ledger fields drift independently of each other.** They are two
   unsynchronised pieces of state mutated non-atomically, so they disagree not
   only with `scoreSum` but with *each other* (run 1: `ledger=2` but `events=3`).

A further subtlety: holding the two station monitors does **not** protect the
ledger. Two adventurers working on disjoint station pairs (say `Anvil+Lens` and
`Press+Altar`) hold completely different monitors and therefore enter
`ledger.record(...)` simultaneously. The ledger requires protection *of its own*,
independent of the station locks.

### 1.4 Did the game stop unexpectedly?

**Yes - every time, and very early.** Every configuration tested deadlocked
during round 1, printing zero or one `ROUND` snapshot before the watchdog
terminated the JVM with **exit code 2**:

| Config (players / stations / rounds) | Players per station | Rounds completed | Outcome |
|---|---:|---:|---|
| 8 / 6 / 25 (default) | 1.33 | 0 | deadlock in round 1 |
| 21 / 11 / 57 | 1.91 | 0 | deadlock in round 1 |
| 3 / 8 / 25 | 0.38 | 1 | deadlock in round 2 (3/3 runs) |

The pattern follows the resource ratio. Each adventurer claims **two** stations,
so 8 players need 16 station-claims from a pool of 6. By the pigeonhole principle
several claims must overlap, and because `LockPair` acquires monitors in
*caller-supplied* order, a circular wait is nearly certain on the first round.
Raising the player count while barely raising the station count (21/11) makes the
ratio worse, not better - which is why the custom configuration also died
immediately.

The process does not hang, because `GameEngine` installs a watchdog thread
([`GameEngine.java:63-84`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java#L63))
that polls `ThreadMXBean.findDeadlockedThreads()` every 100 ms and calls
`System.exit(2)`. **The watchdog is a diagnostic convenience, not a fix** - it
detects the deadlock after the fact and kills the game; it does not prevent it,
and it is not part of the solution required by Part V.

### 1.5 Evidence

**Build and test**

```text
[INFO] Compiling 11 source files with javac [debug release 21] to target/classes
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in edu.eci.arsw.relicrush.StarterSmokeTest
[INFO] BUILD SUCCESS
```

**LedgerRaceProbe - shared-state race (6/6 runs BROKEN)**

```text
$ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
expected=64000 totalCrafted=3160 eventCount=59893 invariant=BROKEN
expected=64000 totalCrafted=3442 eventCount=59593 invariant=BROKEN
expected=64000 totalCrafted=3723 eventCount=59488 invariant=BROKEN

$ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=9143  eventCount=301397 invariant=BROKEN
expected=320000 totalCrafted=11903 eventCount=299985 invariant=BROKEN
expected=320000 totalCrafted=9329  eventCount=301419 invariant=BROKEN
```

**DeadlockProbe - nested-lock deadlock (3/3 runs DEADLOCKED)**

```text
$ java -cp target/classes edu.eci.arsw.relicrush.app.DeadlockProbe
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on ForgeStation@7229724f owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on ForgeStation@65ab7765 owned by probe-A-anvil-then-furnace
```

(The identity hash codes repeat across runs because a fresh JVM allocates the same
two objects in the same order; this carries no meaning beyond object identity.)

**RelicRushMain - the game itself**

```text
$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain
Starting Relic Rush: adventurers=8, stations=6, rounds=25

*** DEADLOCK DETECTED BY GAME WATCHDOG ***
Run DeadlockProbe or jcmd <PID> Thread.print for a focused diagnosis.
The starter exits here so you do not have to kill a frozen process manually.
                                      <- zero ROUND lines: deadlock in round 1

$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 21 11 57
Starting Relic Rush: adventurers=21, stations=11, rounds=57

*** DEADLOCK DETECTED BY GAME WATCHDOG ***
                                      <- zero ROUND lines: deadlock in round 1

$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 3 8 25   (run 1)
Starting Relic Rush: adventurers=3, stations=8, rounds=25
ROUND 01 | scoreSum=3 | ledger=2 | events=3 | invariant=BROKEN
*** DEADLOCK DETECTED BY GAME WATCHDOG ***

$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 3 8 25   (run 2)
Starting Relic Rush: adventurers=3, stations=8, rounds=25
ROUND 01 | scoreSum=3 | ledger=2 | events=2 | invariant=BROKEN
*** DEADLOCK DETECTED BY GAME WATCHDOG ***

$ java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushMain 3 8 25   (run 3)
Starting Relic Rush: adventurers=3, stations=8, rounds=25
ROUND 01 | scoreSum=3 | ledger=2 | events=2 | invariant=BROKEN
*** DEADLOCK DETECTED BY GAME WATCHDOG ***
```

**Operating-system diagnosis - `jcmd <PID> Thread.print`**

The JVM's own deadlock detector confirms the wait-for cycle independently of the
application-level probe:

```text
Found one Java-level deadlock:
=============================
"probe-A-anvil-then-furnace":
  waiting to lock monitor 0x0000000cbf535180 (object 0x0000000697c16f68, a ForgeStation),
  which is held by "probe-B-furnace-then-anvil"

"probe-B-furnace-then-anvil":
  waiting to lock monitor 0x0000000cbf535260 (object 0x0000000697c16f18, a ForgeStation),
  which is held by "probe-A-anvil-then-furnace"

Found 1 deadlock.
```

The full dump, including both thread stacks, is reproduced and analysed in
section 4.1.

*Methodological note.* `DeadlockProbe` cannot itself be inspected with `jcmd`: it
creates its two workers as **daemon** threads and `main` returns after a 2-second
detection window, so the JVM terminates as soon as the diagnostic prints. An
attempt to attach to it after the fact fails with:

```text
com.sun.tools.attach.AttachNotSupportedException: pid: 13275,
state is not ready to participate in attach handshake!
```

which is the attach mechanism reporting that the target process no longer exists.
Capturing a live thread dump therefore requires a driver that reproduces the same
`LockPair` deadlock with **non-daemon** threads and keeps the process alive long
enough to attach. A small `HoldingDeadlock` driver was used for this purpose; it
calls the unmodified `LockPair.withBoth(...)` from the project, so the deadlock it
produces is the project's own, not a re-implementation.

### 1.6 Baseline summary

| # | Defect | Location | Observed symptom | Reproducibility |
|---|---|---|---|---|
| 1 | Non-atomic read-modify-write on `totalCrafted` | `ForgeLedger.java:21-23` | 94-97 % of increments lost | 6/6 runs |
| 2 | `ArrayList` written concurrently | `ForgeLedger.java:24` | 6-7 % of events lost; crash also possible | 6/6 runs |
| 3 | Nested monitors in caller-supplied order | `LockPair.java:20-26` | circular wait, JVM-confirmed deadlock | 3/3 runs |
| 4 | Consequence of 1+2+3 in the game | `RelicRushMain` | `invariant=BROKEN`, then exit code 2 | 5/5 runs |

Defects 1 and 2 threaten **correctness** (the round invariant); defect 3 threatens
**liveness** (the game cannot finish at all). They are independent failures and
were reproduced independently, which is precisely why the starter ships a separate
probe for each. Any proposed fix must therefore be validated against *both*
failure modes, not just the one that is easier to observe.

## 2. Coordination analysis

The round coordination is the one part of the starter that is **already correct**,
and the README asks that it be understood before anything else is modified. This
section answers the four questions of Part I.

### 2.1 The model at a glance

`GameEngine` creates two barriers, each with **`adventurers() + 1`** parties
([`GameEngine.java:27-28`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java#L27)):

```java
this.roundStart = new CyclicBarrier(config.adventurers() + 1);
this.roundEnd   = new CyclicBarrier(config.adventurers() + 1);
```

The `+ 1` is the coordinator itself. It is not a spectator polling from outside -
**it is a participant in both barriers**, which is what allows it to pace the game
and to observe between rounds. The two loops are mirror images:

| Coordinator ([`GameEngine.java:45-53`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java#L45)) | Adventurer ([`Adventurer.java:55-59`](../src/main/java/edu/eci/arsw/relicrush/game/Adventurer.java#L55)) |
|---|---|
| `roundStart.await()` | `roundStart.await()` |
| `roundEnd.await()` | `playTurn(round)` |
| `printRoundSnapshot(round)` | `roundEnd.await()` |

Note that the coordinator does **no work** between the two barriers, and the
worker does **all** of its work there. That asymmetry is the whole design.

```text
                 roundStart             roundEnd                     roundStart
                (N+1 parties)          (N+1 parties)                 (next round)
                     |                      |                             |
coordinator  --------#----------------------#--- printRoundSnapshot ------#------>
                     |                      |     (game state frozen)     |
adventurer-1 --------#---- playTurn(R) -----#--- (parked at barrier) -----#------>
adventurer-2 --------#------ playTurn(R) ---#--- (parked at barrier) -----#------>
adventurer-N --------#-- playTurn(R) -------#--- (parked at barrier) -----#------>
                     |                      |                             |
                 all released           all released              coordinator
                 together               together                  re-opens gate
```

### 2.2 What problem does `roundStart` solve?

**It solves the staggered-start problem.**

`Thread.start()` returns immediately; it does not mean the thread is running. When
`GameEngine` calls `adventurers.forEach(Thread::start)`
([`GameEngine.java:43`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java#L43)),
the operating system is free to schedule those threads whenever it likes, in any
order, with arbitrary delays between them. On a 10-core machine running 128
adventurers, most of them are not even on a CPU yet.

Without a start gate, adventurer-1 could complete several rounds before
adventurer-8 executes its first instruction. "Round 7" would then mean different
things to different threads, and the per-round snapshot would be meaningless
because there would be no shared notion of *which round is in progress*.

`roundStart` converts N independent thread start-ups into **one aligned event**:
no adventurer enters round R until all N adventurers *and* the coordinator have
arrived. It also gives the coordinator a throttle - because the coordinator is a
party, the round cannot begin until the coordinator releases it.


### 2.3 What problem does `roundEnd` solve?

**It solves the read-while-writing problem.**

The coordinator must not read the scoreboard while adventurers are still crafting.
`printRoundSnapshot`
([`GameEngine.java:86-98`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java))
performs three separate reads:

```java
int scoreSum   = adventurers.stream().mapToInt(Adventurer::score).sum();
int ledgerTotal = ledger.totalCrafted();
int eventCount  = ledger.eventCount();
```

If workers were still running, these reads would sweep across a moving target: some
players would have completed round R, others not, and `scoreSum` would be a **torn
read** of a state that never actually existed at any single instant. The invariant
check would then report `BROKEN` constantly - not because of the ledger race, but
because the observer was reading a game in motion.

`roundEnd` guarantees that when the coordinator returns from `await()`, **every**
worker has returned from `playTurn(round)`. The snapshot describes one well-defined
instant.

### 2.4 Why *two* barriers: the quiescent window

The subtle part is what the two barriers achieve *together*, which neither achieves
alone. Trace what happens the moment `roundEnd` trips:

1. All N+1 parties are released simultaneously.
2. Each worker loops back and calls `roundStart.await()` for round R+1 - and
   **blocks**, because only N parties have arrived and the barrier needs N+1.
3. The coordinator, meanwhile, is running `printRoundSnapshot(round)`.
4. Only when the coordinator finishes printing does it reach `roundStart.await()`,
   supplying the final party and releasing round R+1.

So while the snapshot is being taken, **every adventurer is parked at a barrier and
none can touch `score` or the ledger**: `playTurn` is unreachable until `roundStart`
trips, and `roundStart` cannot trip without the coordinator.

This is a **mutual-exclusion window created without a single lock.** The coordinator
reads shared state with no synchronisation of its own and is nonetheless safe,
because the barrier pair has made the rest of the system quiescent. That is why
`printRoundSnapshot` contains no `synchronized` block and needs none.

### 2.5 Why `Thread.sleep(...)` is not a valid replacement

Replacing `roundEnd.await()` with something like `Thread.sleep(50)` fails on four
independent grounds. Any one of them alone would be disqualifying.

**1. It guarantees nothing about completion.** A sleep is a *bet on timing*, not a
statement about work. If any adventurer needs 60 ms - because of a GC pause, a
scheduler decision, contention on a station, or simply 128 threads sharing 10 cores
- the coordinator reads early and prints a snapshot of an unfinished round. Worse,
**nothing detects this**: the program does not fail, it silently reports wrong
numbers. A barrier cannot be early, because it waits on the *event* (all parties
arrived), not on the *clock*.

**2. It guarantees nothing about visibility.** `Thread.sleep` establishes **no
happens-before relationship whatsoever**. Even if every worker has genuinely
finished, the Java Memory Model still permits the coordinator to observe stale
values indefinitely, because `Adventurer.score` is a plain non-`volatile` `int`
read without synchronisation. Sleeping longer does not help: this is a correctness
property of the memory model, not a race that more time can win. This point is
developed in section 2.6.

**3. It is a permanent performance tax.** To be even probabilistically safe, the
constant must cover the *worst plausible* turn, so every round pays the worst case
even when all workers finished in 2 ms. Over 100 rounds that is pure waste, and it
grows with the safety margin. The barrier costs approximately the *actual* duration
of the slowest worker and not one millisecond more.

**4. It is unportable and does not scale.** The "right" constant depends on core
count, machine load, JIT warm-up, player count and station count. A value tuned on
this laptop is wrong on the grader's machine. Worse, the failure is
**load-dependent**: it will appear to work at 8 players and break at 128, which is
precisely the regime the lab requires (README section 13). A barrier needs no
tuning, because `adventurers + 1` is a structural fact rather than a guess.

The lab makes this explicit in its restrictions: *do not use arbitrary sleeps as a
coordination mechanism* (README section 16).

> **A sleep expresses a hope; a barrier expresses a guarantee.** A sleep couples
> correctness to wall-clock timing - the one variable a concurrent program can
> never control. A barrier couples correctness to the completion event itself.

**A necessary distinction.** The starter does contain `Thread.sleep` calls, and they
are *not* violations, because neither is used to coordinate game logic:

| Call site                                                                                                                  | Purpose                                                  | Is it coordination?                                                                |
|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------|
| `LockPair.sleepQuietly(2)` ([`LockPair.java:22`](../src/main/java/edu/eci/arsw/relicrush/concurrency/LockPair.java))       | widens the deadlock window so the defect is reproducible | No - a **test aid** that makes a latent bug deterministic                          |
| `Thread.sleep(100)` in the watchdog ([`GameEngine.java:75`](../src/main/java/edu/eci/arsw/relicrush/game/GameEngine.java)) | polling interval of a daemon monitor                     | No - a **sampling period** for an observer that no correctness property depends on |
| `Thread.sleep(25)` in `DeadlockProbe`                                                                                      | detection polling interval                               | No - same reason                                                                   |
 
Using sleep to *sample* or to *provoke* is legitimate. Using it to *establish that
another thread has finished* is not.

### 2.6 What memory-consistency benefit does reading after the barrier give?

The short answer: **the barrier guarantees the coordinator actually sees the
numbers the workers wrote, instead of old ones.**

This is a different guarantee from the one in section 2.4. There the barrier made
sure the work was *finished*. Here it makes sure the results are *visible*. Those
two things are not the same, and it is easy to assume the first implies the second.

**The problem.** Each adventurer keeps its own score
([`Adventurer.java:25`](../src/main/java/edu/eci/arsw/relicrush/game/Adventurer.java#L25)):

```java
private int score;          // no volatile, no lock
```

The adventurer thread writes it. A *different* thread - the coordinator - reads it
in `printRoundSnapshot`. In Java, when one thread writes an ordinary field and
another thread reads it with no synchronisation between them, **the reader is not
promised an up-to-date value**. For speed, each CPU core keeps recently used values
in its own local cache, and Java allows the reader to keep using its cached copy.
Waiting longer does not fix this: the reader can keep seeing the old number
indefinitely, because nothing ever tells it to look again.

**What the barrier does.** `CyclicBarrier` comes with a promise: everything a
thread did *before* it called `await()` is guaranteed to be visible to the other
threads *after* they come out of that same barrier.

```text
worker: score++ and ledger.record(...)        <-- happens before await()
                    |
              roundEnd barrier
                    |
coordinator: reads score, totalCrafted(), eventCount()   <-- sees all of it
```

So when the coordinator wakes up from `roundEnd.await()`, it is reading fresh
values, not stale cached ones.

**Why this matters here.** Three practical consequences:

1. **`score` does not need to be `volatile`.** The barrier already handles making
   the value visible, so marking the field `volatile` would add cost without adding
   any guarantee. (The usual instinct is to mark every shared field `volatile` -
   here it would be unnecessary.)

2. **One barrier covers every worker at once**, and every value each of them wrote
   during the round - not one field at a time.

3. **It is what makes our evidence believable.** This is the important one for this
   report. Because the coordinator is guaranteed to read fresh values, every
   `invariant=BROKEN` line in section 1 shows **real relics being lost inside
   `ForgeLedger`**. If we were reading through the barrier's guarantee, we could
   not tell the difference between "the ledger genuinely lost an update" and "the
   coordinator just looked at an old copy of the number" - and section 1 would
   prove nothing.

And this is the second reason `Thread.sleep` cannot replace the barrier (section
2.5): sleeping gives no visibility promise at all. A sleeping thread is simply a
thread doing nothing; when it wakes up, it may still be looking at the same stale
cached values it had before.


## 3. Thread-safety problems

### 3.1 The two problems

| Shared state | Problem | Invariant at risk | Solution | Why this solution? |
|---|---|---|---|---|
| `totalCrafted` (an `int`) | `totalCrafted + 1` is three separate steps: read, add, write back. Two threads can both read 41 and both write 42, so one relic disappears. | `ledger == scoreSum` | Increment it inside a lock | The lock makes the three steps behave as one, so no thread can read a value that another is about to overwrite |
| `events` (an `ArrayList`) | `ArrayList` was never built for two threads writing at the same time. Entries get lost, and in the worst case the internal array is left broken. | `events == scoreSum` | Add to it inside the **same** lock | Using the same lock also keeps the counter and the list in step with each other |

Section 1 measured both: the counter lost 94-97 % of its increments and the list
lost 6-7 %, in 6 runs out of 6.

### 3.2 What we changed

The whole fix is in
[`ForgeLedger`](../src/main/java/edu/eci/arsw/relicrush/concurrency/ForgeLedger.java).
No other class was touched.

```java
private final Object lock = new Object();

public void record(ForgeEvent event) {
    synchronized (lock) {
        totalCrafted++;
        events.add(event);
    }
}
```

The read methods (`totalCrafted()`, `eventCount()`, `snapshot()`) take the same
lock, so a reader always sees finished writes rather than a half-updated value.

The lock object is **private**, so no other class can lock on the ledger and
interfere with it by accident.

### 3.3 Why both updates share one lock

This is the important design decision, and it is what the invariant actually
demands.

The invariant is not "the counter is correct" and "the list is correct" as two
separate statements. It is **"the counter and the list agree with each other."**

If we protected them separately - an `AtomicInteger` for the counter and a
thread-safe list for the events - each one would be individually correct, but
`record` would still update them in two steps. In between those two steps, the
counter says 5 while the list says 4. Anyone reading at that moment sees a broken
invariant even though nothing was lost.

Putting both lines inside one lock makes them a **single indivisible step**. There
is no in-between moment for anyone to observe.

In this game a reader would probably never catch that gap anyway, because the
coordinator only reads at the barrier when no one is writing (section 2.4). But
that would make the ledger correct *because of how it happens to be called*, not
because of how it is written. Our version stays correct even if someone later reads
it in the middle of a round.

### 3.4 Why this is better than locking the whole game

The lab forbids solving the problem with one global lock, and for good reason.

A global lock would mean only one adventurer could do anything at a time: pick
stations, craft, and record. The game would still print the right numbers, but it
would no longer be concurrent - it would be a sequential program with extra
threads. Every quality attribute the lab cares about would be lost.

Our lock is much narrower:

|                                       | Global lock                                            | Our ledger lock                       |
|---------------------------------------|--------------------------------------------------------|---------------------------------------|
| What it covers                        | the entire craft operation                             | two lines of bookkeeping              |
| Adventurers crafting at the same time | 1                                                      | as many as have free stations         |
| Time spent holding the lock           | the whole turn, including the 2 ms delay in `LockPair` | a counter increment and a list append |

Adventurers still compete for forge stations exactly as before, still craft in
parallel, and only meet at the ledger for the brief moment it takes to write one
line. The stations remain the thing that limits concurrency - which is the point of
the game - rather than the scoreboard.

### 3.5 A note on the removed `Thread.yield()`

The starter had a `Thread.yield()` sitting between reading and writing the counter.
It was there to make the race easy to reproduce, and it is gone in our version:
holding a lock while inviting the scheduler to switch threads would slow every
craft down for no benefit.

Removing it does not hide anything. The `yield()` sat **before** `events.add(...)`
and never affected it, yet the list still lost 6-7 % of its entries in every
baseline run. That loss came from ordinary concurrent execution with no artificial
help. The race was real; the `yield()` only made the counter's share of it easier
to see.

The fix does not depend on timing at all. The lock makes the lost update
*impossible*, not merely unlikely.

### 3.6 Verification

```text
❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
expected=64000 totalCrafted=64000 eventCount=64000 invariant=OK
❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
expected=64000 totalCrafted=64000 eventCount=64000 invariant=OK
❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe
expected=64000 totalCrafted=64000 eventCount=64000 invariant=OK

❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=320000 eventCount=320000 invariant=OK
❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=320000 eventCount=320000 invariant=OK
❯ java -cp target/classes edu.eci.arsw.relicrush.app.LedgerRaceProbe 64 5000
expected=320000 totalCrafted=320000 eventCount=320000 invariant=OK
```

## 4. Deadlock diagnosis

### 4.1 Evidence

Two independent sources confirm the same defect: the application-level probe
(`ThreadMXBean.findDeadlockedThreads()`) and the JVM's own thread dump.

**Source 1 - `DeadlockProbe` (3/3 runs)**

```text
DEADLOCK DETECTED
- probe-A-anvil-then-furnace waiting on ForgeStation@7229724f owned by probe-B-furnace-then-anvil
- probe-B-furnace-then-anvil waiting on ForgeStation@65ab7765 owned by probe-A-anvil-then-furnace
```

**Source 2 - `jcmd <PID> Thread.print` (full dump)**

```text
Found one Java-level deadlock:
=============================
"probe-A-anvil-then-furnace":
  waiting to lock monitor 0x0000000cbf535180 (object 0x0000000697c16f68, a edu.eci.arsw.relicrush.model.ForgeStation),
  which is held by "probe-B-furnace-then-anvil"

"probe-B-furnace-then-anvil":
  waiting to lock monitor 0x0000000cbf535260 (object 0x0000000697c16f18, a edu.eci.arsw.relicrush.model.ForgeStation),
  which is held by "probe-A-anvil-then-furnace"

Java stack information for the threads listed above:
===================================================
"probe-A-anvil-then-furnace":
        at edu.eci.arsw.relicrush.concurrency.LockPair.withBoth(LockPair.java:24)
        - waiting to lock <0x0000000697c16f68> (a edu.eci.arsw.relicrush.model.ForgeStation)
        - locked         <0x0000000697c16f18> (a edu.eci.arsw.relicrush.model.ForgeStation)
        at HoldingDeadlock.go(HoldingDeadlock.java:19)
        at java.lang.Thread.runWith(java.base@21.0.11/Thread.java:1596)
        at java.lang.Thread.run(java.base@21.0.11/Thread.java:1583)
"probe-B-furnace-then-anvil":
        at edu.eci.arsw.relicrush.concurrency.LockPair.withBoth(LockPair.java:24)
        - waiting to lock <0x0000000697c16f18> (a edu.eci.arsw.relicrush.model.ForgeStation)
        - locked         <0x0000000697c16f68> (a edu.eci.arsw.relicrush.model.ForgeStation)
        at HoldingDeadlock.go(HoldingDeadlock.java:19)
        at java.lang.Thread.runWith(java.base@21.0.11/Thread.java:1596)
        at java.lang.Thread.run(java.base@21.0.11/Thread.java:1583)

Found 1 deadlock.
```

**Reading the dump.** Three facts are established beyond doubt:

1. **Both threads are blocked on the same source line**,
   [`LockPair.java:24`](../src/main/java/edu/eci/arsw/relicrush/concurrency/LockPair.java#L24)
   - the inner `synchronized (second)`. Neither is blocked on the *outer*
   acquisition, which confirms that each thread successfully took its first
   monitor and then stalled requesting the second.

2. **The held/wanted addresses are exact mirror images**, which is the signature
   of a two-node cycle:

   | Thread | Holds | Wants |
   |---|---|---|
   | `probe-A-anvil-then-furnace` | `0x...6f18` | `0x...6f68` |
   | `probe-B-furnace-then-anvil` | `0x...6f68` | `0x...6f18` |

3. **The addresses map to named stations.** Thread A was constructed as
   *anvil-then-furnace*, so the monitor it holds (`0x...6f18`) is **Arcane Anvil**
   `(#1)` and the one it wants (`0x...6f68`) is **Dragon Furnace** `(#2)`.
   Thread B, constructed in the opposite order, holds the Furnace and wants the
   Anvil. The cycle is therefore not an abstraction - it is two concrete forge
   stations acquired in opposite orders.

Note that the monitor addresses (`0x...535180`, `0x...535260`) and the object
addresses (`0x...6f68`, `0x...6f18`) are different things: the first identifies
the JVM's internal monitor structure, the second the `ForgeStation` instance it
guards. Both change between runs and carry no meaning beyond identity.

The critical observation for Part V is that **nothing in this dump is a bug in the
game logic**. `LockPair.withBoth` does exactly what it was written to do; the
defect is the *absence of a global ordering rule* over the monitors it acquires.

### 4.2 Coffman conditions in Relic Rush

- Mutual exclusion:
- Hold and wait:
- No preemption:
- Circular wait:

### 4.3 Wait-for graph

Describe or add a diagram.

### 4.4 Fix

What condition did you break?

How did you preserve concurrency between independent forge operations?

## 5. Verification

| Players | Stations | Rounds | Deadlock? | Invariant result |
|---:|---:|---:|---|---|
| 8 | 6 | 50 | | |
| 32 | 8 | 100 | | |
| 128 | 8 | 100 | | |

## 6. Architectural trade-offs

Discuss:

- Correctness / reliability
- Performance / throughput
- Contention
- Maintainability
- Scalability

## 7. Mini ADR

### Context

### Decision

### Alternatives considered

### Consequences

### Evidence

## 8. Conclusions

1.
2.
3.
