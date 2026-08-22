# ARSW Lab 3 - Relic Rush - Delivery Report

## Team

| Student                   | ID         | GitHub                                      |
|---------------------------|------------|---------------------------------------------|
| Jose Luis Lancheros Ayora | 1000102647 | [Lanch3ros](https://github.com/Lanch3ros)   |
| Gina Sofia Garcia Zapata  | 1000100098 | [sofiapeace](https://github.com/sofiapeace) |

Repository: `https://github.com/Lanch3ros/lab-arsw-relic-rush-concurrency-deadlocks`

Final commit: `93ea732`

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

- Mutual exclusion: This is demonstrated by the use of the `synchronized (first)` and `synchronized (second)` blocks in the `LockPair` class. Java's native monitors ensure that only one thread (the “adventurer”) can acquire and hold the monitor for a specific `ForgeStation` at any given time, excluding all others.
- Hold and wait: This occurs when `synchronized` blocks are nested. Since the block containing the second lock is nested inside the first block, the thread does not release the first lock until it reaches the closing statement. Because the thread requests the second lock before reaching the closing statement of the first lock, it is forced to hold the first lock while waiting for the second.
- No preemption: The use of native `synchronized` blocks in Java does not allow for preemption. The Java Virtual Machine (JVM) has no mechanism for forced interruption or “timeout” to remove the monitor from a thread. The thread will only voluntarily release the first lock when it finishes execution (reaching the exit lock), which means that if it becomes blocked while waiting for a second lock, it will hold onto the first one indefinitely without the system being able to intervene.
- Circular wait: This occurs because the LockPair class locks the monitors in the exact order in which they are requested by each adventurer. It is possible for Adventurer A to acquire Station 1 and wait for Station 2, while simultaneously Adventurer B acquires Station 2 and waits for Station 1. This creates a cycle of cross-dependencies where each thread waits indefinitely for a resource that the other thread is holding.

### 4.3 Wait-for graph

Adventurer A needs the Anvil (passes it first) and the Furnace (passes it second).
Adventurer B needs the Furnace (passes it first) and the Anvil (passes it second).

The following diagram illustrates the cycle of cross-dependencies evident in the threads (section 4.1):

```text
[Adventurer A] ------ (waits for) -----> [Dragon Furnace]
      ^                                          |
      |                                          |
  (holds)                                  (holds)
      |                                          v
[Arcane Anvil] <----- (waits for) ------ [Adventurer B]
```

### 4.4 Fix

What condition did you break?
We broke the Circular Wait condition. We achieved this by enforcing a strict global ordering rule in the LockPair class. Regardless of the order in which the adventurer requests the stations, the internal code always determines which station is “less” (by comparing their unique IDs) and locks that one first. By forcing all threads to request resources in the same order, it is impossible for cross-dependency cycles to form.

How did you preserve concurrency between independent forge operations?
We maintained concurrency by using fine-grained locking specific to each ForgeStation, rather than using a single global lock for the entire game. This means that if Adventurer A is using the Anvil and the Hammer, Adventurer B can forge simultaneously as long as they use completely different resources. They only block each other if they try to use the same tool at the same time.

## 5. Verification

| Players | Stations | Rounds | Deadlock? | Invariant result |
|---:|---:|---:|---|---|
| 8 | 6 | 50 |No |Ok |
| 32 | 8 | 100 |No |Ok |
| 128 | 8 | 100 |No |Ok |

**Execution evidence (configuration 8 / 6 / 50):**
```text
java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 8 6 50
ROUND 01 | scoreSum=8 | ledger=8 | events=8 | invariant=OK
ROUND 02 | scoreSum=16 | ledger=16 | events=16 | invariant=OK
ROUND 03 | scoreSum=24 | ledger=24 | events=24 | invariant=OK
ROUND 04 | scoreSum=32 | ledger=32 | events=32 | invariant=OK
ROUND 05 | scoreSum=40 | ledger=40 | events=40 | invariant=OK
ROUND 06 | scoreSum=48 | ledger=48 | events=48 | invariant=OK
ROUND 07 | scoreSum=56 | ledger=56 | events=56 | invariant=OK
ROUND 08 | scoreSum=64 | ledger=64 | events=64 | invariant=OK
ROUND 09 | scoreSum=72 | ledger=72 | events=72 | invariant=OK
ROUND 10 | scoreSum=80 | ledger=80 | events=80 | invariant=OK
ROUND 11 | scoreSum=88 | ledger=88 | events=88 | invariant=OK
ROUND 12 | scoreSum=96 | ledger=96 | events=96 | invariant=OK
ROUND 13 | scoreSum=104 | ledger=104 | events=104 | invariant=OK
ROUND 14 | scoreSum=112 | ledger=112 | events=112 | invariant=OK
ROUND 15 | scoreSum=120 | ledger=120 | events=120 | invariant=OK
ROUND 16 | scoreSum=128 | ledger=128 | events=128 | invariant=OK
ROUND 17 | scoreSum=136 | ledger=136 | events=136 | invariant=OK
ROUND 18 | scoreSum=144 | ledger=144 | events=144 | invariant=OK
ROUND 19 | scoreSum=152 | ledger=152 | events=152 | invariant=OK
ROUND 20 | scoreSum=160 | ledger=160 | events=160 | invariant=OK
ROUND 21 | scoreSum=168 | ledger=168 | events=168 | invariant=OK
ROUND 22 | scoreSum=176 | ledger=176 | events=176 | invariant=OK
ROUND 23 | scoreSum=184 | ledger=184 | events=184 | invariant=OK
ROUND 24 | scoreSum=192 | ledger=192 | events=192 | invariant=OK
ROUND 25 | scoreSum=200 | ledger=200 | events=200 | invariant=OK
ROUND 26 | scoreSum=208 | ledger=208 | events=208 | invariant=OK
ROUND 27 | scoreSum=216 | ledger=216 | events=216 | invariant=OK
ROUND 28 | scoreSum=224 | ledger=224 | events=224 | invariant=OK
ROUND 29 | scoreSum=232 | ledger=232 | events=232 | invariant=OK
ROUND 30 | scoreSum=240 | ledger=240 | events=240 | invariant=OK
ROUND 31 | scoreSum=248 | ledger=248 | events=248 | invariant=OK
ROUND 32 | scoreSum=256 | ledger=256 | events=256 | invariant=OK
ROUND 33 | scoreSum=264 | ledger=264 | events=264 | invariant=OK
ROUND 34 | scoreSum=272 | ledger=272 | events=272 | invariant=OK
ROUND 35 | scoreSum=280 | ledger=280 | events=280 | invariant=OK
ROUND 36 | scoreSum=288 | ledger=288 | events=288 | invariant=OK
ROUND 37 | scoreSum=296 | ledger=296 | events=296 | invariant=OK
ROUND 38 | scoreSum=304 | ledger=304 | events=304 | invariant=OK
ROUND 39 | scoreSum=312 | ledger=312 | events=312 | invariant=OK
ROUND 40 | scoreSum=320 | ledger=320 | events=320 | invariant=OK
ROUND 41 | scoreSum=328 | ledger=328 | events=328 | invariant=OK
ROUND 42 | scoreSum=336 | ledger=336 | events=336 | invariant=OK
ROUND 43 | scoreSum=344 | ledger=344 | events=344 | invariant=OK
ROUND 44 | scoreSum=352 | ledger=352 | events=352 | invariant=OK
ROUND 45 | scoreSum=360 | ledger=360 | events=360 | invariant=OK
ROUND 46 | scoreSum=368 | ledger=368 | events=368 | invariant=OK
ROUND 47 | scoreSum=376 | ledger=376 | events=376 | invariant=OK
ROUND 48 | scoreSum=384 | ledger=384 | events=384 | invariant=OK
ROUND 49 | scoreSum=392 | ledger=392 | events=392 | invariant=OK
ROUND 50 | scoreSum=400 | ledger=400 | events=400 | invariant=OK

=== RELIC RUSH - FINAL SCORE ===
adventurer-1       50 relics
adventurer-2       50 relics
adventurer-3       50 relics
adventurer-4       50 relics
adventurer-5       50 relics
adventurer-6       50 relics
adventurer-7       50 relics
adventurer-8       50 relics
Total by players : 400
Ledger total     : 400
Ledger events    : 400
```

**Execution evidence (configuration 32 / 8 / 100):**
```text
java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 32 8 100
ROUND 01 | scoreSum=32 | ledger=32 | events=32 | invariant=OK
ROUND 02 | scoreSum=64 | ledger=64 | events=64 | invariant=OK
ROUND 03 | scoreSum=96 | ledger=96 | events=96 | invariant=OK
ROUND 04 | scoreSum=128 | ledger=128 | events=128 | invariant=OK
ROUND 05 | scoreSum=160 | ledger=160 | events=160 | invariant=OK
ROUND 06 | scoreSum=192 | ledger=192 | events=192 | invariant=OK
ROUND 07 | scoreSum=224 | ledger=224 | events=224 | invariant=OK
ROUND 08 | scoreSum=256 | ledger=256 | events=256 | invariant=OK
ROUND 09 | scoreSum=288 | ledger=288 | events=288 | invariant=OK
ROUND 10 | scoreSum=320 | ledger=320 | events=320 | invariant=OK
ROUND 11 | scoreSum=352 | ledger=352 | events=352 | invariant=OK
ROUND 12 | scoreSum=384 | ledger=384 | events=384 | invariant=OK
ROUND 13 | scoreSum=416 | ledger=416 | events=416 | invariant=OK
ROUND 14 | scoreSum=448 | ledger=448 | events=448 | invariant=OK
ROUND 15 | scoreSum=480 | ledger=480 | events=480 | invariant=OK
ROUND 16 | scoreSum=512 | ledger=512 | events=512 | invariant=OK
ROUND 17 | scoreSum=544 | ledger=544 | events=544 | invariant=OK
ROUND 18 | scoreSum=576 | ledger=576 | events=576 | invariant=OK
ROUND 19 | scoreSum=608 | ledger=608 | events=608 | invariant=OK
ROUND 20 | scoreSum=640 | ledger=640 | events=640 | invariant=OK
ROUND 21 | scoreSum=672 | ledger=672 | events=672 | invariant=OK
ROUND 22 | scoreSum=704 | ledger=704 | events=704 | invariant=OK
ROUND 23 | scoreSum=736 | ledger=736 | events=736 | invariant=OK
ROUND 24 | scoreSum=768 | ledger=768 | events=768 | invariant=OK
ROUND 25 | scoreSum=800 | ledger=800 | events=800 | invariant=OK
ROUND 26 | scoreSum=832 | ledger=832 | events=832 | invariant=OK
ROUND 27 | scoreSum=864 | ledger=864 | events=864 | invariant=OK
ROUND 28 | scoreSum=896 | ledger=896 | events=896 | invariant=OK
ROUND 29 | scoreSum=928 | ledger=928 | events=928 | invariant=OK
ROUND 30 | scoreSum=960 | ledger=960 | events=960 | invariant=OK
ROUND 31 | scoreSum=992 | ledger=992 | events=992 | invariant=OK
ROUND 32 | scoreSum=1024 | ledger=1024 | events=1024 | invariant=OK
ROUND 33 | scoreSum=1056 | ledger=1056 | events=1056 | invariant=OK
ROUND 34 | scoreSum=1088 | ledger=1088 | events=1088 | invariant=OK
ROUND 35 | scoreSum=1120 | ledger=1120 | events=1120 | invariant=OK
ROUND 36 | scoreSum=1152 | ledger=1152 | events=1152 | invariant=OK
ROUND 37 | scoreSum=1184 | ledger=1184 | events=1184 | invariant=OK
ROUND 38 | scoreSum=1216 | ledger=1216 | events=1216 | invariant=OK
ROUND 39 | scoreSum=1248 | ledger=1248 | events=1248 | invariant=OK
ROUND 40 | scoreSum=1280 | ledger=1280 | events=1280 | invariant=OK
ROUND 41 | scoreSum=1312 | ledger=1312 | events=1312 | invariant=OK
ROUND 42 | scoreSum=1344 | ledger=1344 | events=1344 | invariant=OK
ROUND 43 | scoreSum=1376 | ledger=1376 | events=1376 | invariant=OK
ROUND 44 | scoreSum=1408 | ledger=1408 | events=1408 | invariant=OK
ROUND 45 | scoreSum=1440 | ledger=1440 | events=1440 | invariant=OK
ROUND 46 | scoreSum=1472 | ledger=1472 | events=1472 | invariant=OK
ROUND 47 | scoreSum=1504 | ledger=1504 | events=1504 | invariant=OK
ROUND 48 | scoreSum=1536 | ledger=1536 | events=1536 | invariant=OK
ROUND 49 | scoreSum=1568 | ledger=1568 | events=1568 | invariant=OK
ROUND 50 | scoreSum=1600 | ledger=1600 | events=1600 | invariant=OK
ROUND 51 | scoreSum=1632 | ledger=1632 | events=1632 | invariant=OK
ROUND 52 | scoreSum=1664 | ledger=1664 | events=1664 | invariant=OK
ROUND 53 | scoreSum=1696 | ledger=1696 | events=1696 | invariant=OK
ROUND 54 | scoreSum=1728 | ledger=1728 | events=1728 | invariant=OK
ROUND 55 | scoreSum=1760 | ledger=1760 | events=1760 | invariant=OK
ROUND 56 | scoreSum=1792 | ledger=1792 | events=1792 | invariant=OK
ROUND 57 | scoreSum=1824 | ledger=1824 | events=1824 | invariant=OK
ROUND 58 | scoreSum=1856 | ledger=1856 | events=1856 | invariant=OK
ROUND 59 | scoreSum=1888 | ledger=1888 | events=1888 | invariant=OK
ROUND 60 | scoreSum=1920 | ledger=1920 | events=1920 | invariant=OK
ROUND 61 | scoreSum=1952 | ledger=1952 | events=1952 | invariant=OK
ROUND 62 | scoreSum=1984 | ledger=1984 | events=1984 | invariant=OK
ROUND 63 | scoreSum=2016 | ledger=2016 | events=2016 | invariant=OK
ROUND 64 | scoreSum=2048 | ledger=2048 | events=2048 | invariant=OK
ROUND 65 | scoreSum=2080 | ledger=2080 | events=2080 | invariant=OK
ROUND 66 | scoreSum=2112 | ledger=2112 | events=2112 | invariant=OK
ROUND 67 | scoreSum=2144 | ledger=2144 | events=2144 | invariant=OK
ROUND 68 | scoreSum=2176 | ledger=2176 | events=2176 | invariant=OK
ROUND 69 | scoreSum=2208 | ledger=2208 | events=2208 | invariant=OK
ROUND 70 | scoreSum=2240 | ledger=2240 | events=2240 | invariant=OK
ROUND 71 | scoreSum=2272 | ledger=2272 | events=2272 | invariant=OK
ROUND 72 | scoreSum=2304 | ledger=2304 | events=2304 | invariant=OK
ROUND 73 | scoreSum=2336 | ledger=2336 | events=2336 | invariant=OK
ROUND 74 | scoreSum=2368 | ledger=2368 | events=2368 | invariant=OK
ROUND 75 | scoreSum=2400 | ledger=2400 | events=2400 | invariant=OK
ROUND 76 | scoreSum=2432 | ledger=2432 | events=2432 | invariant=OK
ROUND 77 | scoreSum=2464 | ledger=2464 | events=2464 | invariant=OK
ROUND 78 | scoreSum=2496 | ledger=2496 | events=2496 | invariant=OK
ROUND 79 | scoreSum=2528 | ledger=2528 | events=2528 | invariant=OK
ROUND 80 | scoreSum=2560 | ledger=2560 | events=2560 | invariant=OK
ROUND 81 | scoreSum=2592 | ledger=2592 | events=2592 | invariant=OK
ROUND 82 | scoreSum=2624 | ledger=2624 | events=2624 | invariant=OK
ROUND 83 | scoreSum=2656 | ledger=2656 | events=2656 | invariant=OK
ROUND 84 | scoreSum=2688 | ledger=2688 | events=2688 | invariant=OK
ROUND 85 | scoreSum=2720 | ledger=2720 | events=2720 | invariant=OK
ROUND 86 | scoreSum=2752 | ledger=2752 | events=2752 | invariant=OK
ROUND 87 | scoreSum=2784 | ledger=2784 | events=2784 | invariant=OK
ROUND 88 | scoreSum=2816 | ledger=2816 | events=2816 | invariant=OK
ROUND 89 | scoreSum=2848 | ledger=2848 | events=2848 | invariant=OK
ROUND 90 | scoreSum=2880 | ledger=2880 | events=2880 | invariant=OK
ROUND 91 | scoreSum=2912 | ledger=2912 | events=2912 | invariant=OK
ROUND 92 | scoreSum=2944 | ledger=2944 | events=2944 | invariant=OK
ROUND 93 | scoreSum=2976 | ledger=2976 | events=2976 | invariant=OK
ROUND 94 | scoreSum=3008 | ledger=3008 | events=3008 | invariant=OK
ROUND 95 | scoreSum=3040 | ledger=3040 | events=3040 | invariant=OK
ROUND 96 | scoreSum=3072 | ledger=3072 | events=3072 | invariant=OK
ROUND 97 | scoreSum=3104 | ledger=3104 | events=3104 | invariant=OK
ROUND 98 | scoreSum=3136 | ledger=3136 | events=3136 | invariant=OK
ROUND 99 | scoreSum=3168 | ledger=3168 | events=3168 | invariant=OK
ROUND 100 | scoreSum=3200 | ledger=3200 | events=3200 | invariant=OK

=== RELIC RUSH - FINAL SCORE ===
adventurer-1      100 relics
adventurer-2      100 relics
adventurer-3      100 relics
adventurer-4      100 relics
adventurer-5      100 relics
adventurer-6      100 relics
adventurer-7      100 relics
adventurer-8      100 relics
adventurer-9      100 relics
adventurer-10     100 relics
adventurer-11     100 relics
adventurer-12     100 relics
adventurer-13     100 relics
adventurer-14     100 relics
adventurer-15     100 relics
adventurer-16     100 relics
adventurer-17     100 relics
adventurer-18     100 relics
adventurer-19     100 relics
adventurer-20     100 relics
adventurer-21     100 relics
adventurer-22     100 relics
adventurer-23     100 relics
adventurer-24     100 relics
adventurer-25     100 relics
adventurer-26     100 relics
adventurer-27     100 relics
adventurer-28     100 relics
adventurer-29     100 relics
adventurer-30     100 relics
adventurer-31     100 relics
adventurer-32     100 relics
Total by players : 3200
Ledger total     : 3200
Ledger events    : 3200
```

**Execution evidence (configuration 128 / 8 / 100):**
```text
java -cp target/classes edu.eci.arsw.relicrush.app.InvariantProbe 128 8 100
ROUND 01 | scoreSum=128 | ledger=128 | events=128 | invariant=OK
ROUND 02 | scoreSum=256 | ledger=256 | events=256 | invariant=OK
ROUND 03 | scoreSum=384 | ledger=384 | events=384 | invariant=OK
ROUND 04 | scoreSum=512 | ledger=512 | events=512 | invariant=OK
ROUND 05 | scoreSum=640 | ledger=640 | events=640 | invariant=OK
ROUND 06 | scoreSum=768 | ledger=768 | events=768 | invariant=OK
ROUND 07 | scoreSum=896 | ledger=896 | events=896 | invariant=OK
ROUND 08 | scoreSum=1024 | ledger=1024 | events=1024 | invariant=OK
ROUND 09 | scoreSum=1152 | ledger=1152 | events=1152 | invariant=OK
ROUND 10 | scoreSum=1280 | ledger=1280 | events=1280 | invariant=OK
ROUND 11 | scoreSum=1408 | ledger=1408 | events=1408 | invariant=OK
ROUND 12 | scoreSum=1536 | ledger=1536 | events=1536 | invariant=OK
ROUND 13 | scoreSum=1664 | ledger=1664 | events=1664 | invariant=OK
ROUND 14 | scoreSum=1792 | ledger=1792 | events=1792 | invariant=OK
ROUND 15 | scoreSum=1920 | ledger=1920 | events=1920 | invariant=OK
ROUND 16 | scoreSum=2048 | ledger=2048 | events=2048 | invariant=OK
ROUND 17 | scoreSum=2176 | ledger=2176 | events=2176 | invariant=OK
ROUND 18 | scoreSum=2304 | ledger=2304 | events=2304 | invariant=OK
ROUND 19 | scoreSum=2432 | ledger=2432 | events=2432 | invariant=OK
ROUND 20 | scoreSum=2560 | ledger=2560 | events=2560 | invariant=OK
ROUND 21 | scoreSum=2688 | ledger=2688 | events=2688 | invariant=OK
ROUND 22 | scoreSum=2816 | ledger=2816 | events=2816 | invariant=OK
ROUND 23 | scoreSum=2944 | ledger=2944 | events=2944 | invariant=OK
ROUND 24 | scoreSum=3072 | ledger=3072 | events=3072 | invariant=OK
ROUND 25 | scoreSum=3200 | ledger=3200 | events=3200 | invariant=OK
ROUND 26 | scoreSum=3328 | ledger=3328 | events=3328 | invariant=OK
ROUND 27 | scoreSum=3456 | ledger=3456 | events=3456 | invariant=OK
ROUND 28 | scoreSum=3584 | ledger=3584 | events=3584 | invariant=OK
ROUND 29 | scoreSum=3712 | ledger=3712 | events=3712 | invariant=OK
ROUND 30 | scoreSum=3840 | ledger=3840 | events=3840 | invariant=OK
ROUND 31 | scoreSum=3968 | ledger=3968 | events=3968 | invariant=OK
ROUND 32 | scoreSum=4096 | ledger=4096 | events=4096 | invariant=OK
ROUND 33 | scoreSum=4224 | ledger=4224 | events=4224 | invariant=OK
ROUND 34 | scoreSum=4352 | ledger=4352 | events=4352 | invariant=OK
ROUND 35 | scoreSum=4480 | ledger=4480 | events=4480 | invariant=OK
ROUND 36 | scoreSum=4608 | ledger=4608 | events=4608 | invariant=OK
ROUND 37 | scoreSum=4736 | ledger=4736 | events=4736 | invariant=OK
ROUND 38 | scoreSum=4864 | ledger=4864 | events=4864 | invariant=OK
ROUND 39 | scoreSum=4992 | ledger=4992 | events=4992 | invariant=OK
ROUND 40 | scoreSum=5120 | ledger=5120 | events=5120 | invariant=OK
ROUND 41 | scoreSum=5248 | ledger=5248 | events=5248 | invariant=OK
ROUND 42 | scoreSum=5376 | ledger=5376 | events=5376 | invariant=OK
ROUND 43 | scoreSum=5504 | ledger=5504 | events=5504 | invariant=OK
ROUND 44 | scoreSum=5632 | ledger=5632 | events=5632 | invariant=OK
ROUND 45 | scoreSum=5760 | ledger=5760 | events=5760 | invariant=OK
ROUND 46 | scoreSum=5888 | ledger=5888 | events=5888 | invariant=OK
ROUND 47 | scoreSum=6016 | ledger=6016 | events=6016 | invariant=OK
ROUND 48 | scoreSum=6144 | ledger=6144 | events=6144 | invariant=OK
ROUND 49 | scoreSum=6272 | ledger=6272 | events=6272 | invariant=OK
ROUND 50 | scoreSum=6400 | ledger=6400 | events=6400 | invariant=OK
ROUND 51 | scoreSum=6528 | ledger=6528 | events=6528 | invariant=OK
ROUND 52 | scoreSum=6656 | ledger=6656 | events=6656 | invariant=OK
ROUND 53 | scoreSum=6784 | ledger=6784 | events=6784 | invariant=OK
ROUND 54 | scoreSum=6912 | ledger=6912 | events=6912 | invariant=OK
ROUND 55 | scoreSum=7040 | ledger=7040 | events=7040 | invariant=OK
ROUND 56 | scoreSum=7168 | ledger=7168 | events=7168 | invariant=OK
ROUND 57 | scoreSum=7296 | ledger=7296 | events=7296 | invariant=OK
ROUND 58 | scoreSum=7424 | ledger=7424 | events=7424 | invariant=OK
ROUND 59 | scoreSum=7552 | ledger=7552 | events=7552 | invariant=OK
ROUND 60 | scoreSum=7680 | ledger=7680 | events=7680 | invariant=OK
ROUND 61 | scoreSum=7808 | ledger=7808 | events=7808 | invariant=OK
ROUND 62 | scoreSum=7936 | ledger=7936 | events=7936 | invariant=OK
ROUND 63 | scoreSum=8064 | ledger=8064 | events=8064 | invariant=OK
ROUND 64 | scoreSum=8192 | ledger=8192 | events=8192 | invariant=OK
ROUND 65 | scoreSum=8320 | ledger=8320 | events=8320 | invariant=OK
ROUND 66 | scoreSum=8448 | ledger=8448 | events=8448 | invariant=OK
ROUND 67 | scoreSum=8576 | ledger=8576 | events=8576 | invariant=OK
ROUND 68 | scoreSum=8704 | ledger=8704 | events=8704 | invariant=OK
ROUND 69 | scoreSum=8832 | ledger=8832 | events=8832 | invariant=OK
ROUND 70 | scoreSum=8960 | ledger=8960 | events=8960 | invariant=OK
ROUND 71 | scoreSum=9088 | ledger=9088 | events=9088 | invariant=OK
ROUND 72 | scoreSum=9216 | ledger=9216 | events=9216 | invariant=OK
ROUND 73 | scoreSum=9344 | ledger=9344 | events=9344 | invariant=OK
ROUND 74 | scoreSum=9472 | ledger=9472 | events=9472 | invariant=OK
ROUND 75 | scoreSum=9600 | ledger=9600 | events=9600 | invariant=OK
ROUND 76 | scoreSum=9728 | ledger=9728 | events=9728 | invariant=OK
ROUND 77 | scoreSum=9856 | ledger=9856 | events=9856 | invariant=OK
ROUND 78 | scoreSum=9984 | ledger=9984 | events=9984 | invariant=OK
ROUND 79 | scoreSum=10112 | ledger=10112 | events=10112 | invariant=OK
ROUND 80 | scoreSum=10240 | ledger=10240 | events=10240 | invariant=OK
ROUND 81 | scoreSum=10368 | ledger=10368 | events=10368 | invariant=OK
ROUND 82 | scoreSum=10496 | ledger=10496 | events=10496 | invariant=OK
ROUND 83 | scoreSum=10624 | ledger=10624 | events=10624 | invariant=OK
ROUND 84 | scoreSum=10752 | ledger=10752 | events=10752 | invariant=OK
ROUND 85 | scoreSum=10880 | ledger=10880 | events=10880 | invariant=OK
ROUND 86 | scoreSum=11008 | ledger=11008 | events=11008 | invariant=OK
ROUND 87 | scoreSum=11136 | ledger=11136 | events=11136 | invariant=OK
ROUND 88 | scoreSum=11264 | ledger=11264 | events=11264 | invariant=OK
ROUND 89 | scoreSum=11392 | ledger=11392 | events=11392 | invariant=OK
ROUND 90 | scoreSum=11520 | ledger=11520 | events=11520 | invariant=OK
ROUND 91 | scoreSum=11648 | ledger=11648 | events=11648 | invariant=OK
ROUND 92 | scoreSum=11776 | ledger=11776 | events=11776 | invariant=OK
ROUND 93 | scoreSum=11904 | ledger=11904 | events=11904 | invariant=OK
ROUND 94 | scoreSum=12032 | ledger=12032 | events=12032 | invariant=OK
ROUND 95 | scoreSum=12160 | ledger=12160 | events=12160 | invariant=OK
ROUND 96 | scoreSum=12288 | ledger=12288 | events=12288 | invariant=OK
ROUND 97 | scoreSum=12416 | ledger=12416 | events=12416 | invariant=OK
ROUND 98 | scoreSum=12544 | ledger=12544 | events=12544 | invariant=OK
ROUND 99 | scoreSum=12672 | ledger=12672 | events=12672 | invariant=OK
ROUND 100 | scoreSum=12800 | ledger=12800 | events=12800 | invariant=OK

=== RELIC RUSH - FINAL SCORE ===
adventurer-1      100 relics
adventurer-2      100 relics
adventurer-3      100 relics
adventurer-4      100 relics
adventurer-5      100 relics
adventurer-6      100 relics
adventurer-7      100 relics
adventurer-8      100 relics
adventurer-9      100 relics
adventurer-10     100 relics
adventurer-11     100 relics
adventurer-12     100 relics
adventurer-13     100 relics
adventurer-14     100 relics
adventurer-15     100 relics
adventurer-16     100 relics
adventurer-17     100 relics
adventurer-18     100 relics
adventurer-19     100 relics
adventurer-20     100 relics
adventurer-21     100 relics
adventurer-22     100 relics
adventurer-23     100 relics
adventurer-24     100 relics
adventurer-25     100 relics
adventurer-26     100 relics
adventurer-27     100 relics
adventurer-28     100 relics
adventurer-29     100 relics
adventurer-30     100 relics
adventurer-31     100 relics
adventurer-32     100 relics
adventurer-33     100 relics
adventurer-34     100 relics
adventurer-35     100 relics
adventurer-36     100 relics
adventurer-37     100 relics
adventurer-38     100 relics
adventurer-39     100 relics
adventurer-40     100 relics
adventurer-41     100 relics
adventurer-42     100 relics
adventurer-43     100 relics
adventurer-44     100 relics
adventurer-45     100 relics
adventurer-46     100 relics
adventurer-47     100 relics
adventurer-48     100 relics
adventurer-49     100 relics
adventurer-50     100 relics
adventurer-51     100 relics
adventurer-52     100 relics
adventurer-53     100 relics
adventurer-54     100 relics
adventurer-55     100 relics
adventurer-56     100 relics
adventurer-57     100 relics
adventurer-58     100 relics
adventurer-59     100 relics
adventurer-60     100 relics
adventurer-61     100 relics
adventurer-62     100 relics
adventurer-63     100 relics
adventurer-64     100 relics
adventurer-65     100 relics
adventurer-66     100 relics
adventurer-67     100 relics
adventurer-68     100 relics
adventurer-69     100 relics
adventurer-70     100 relics
adventurer-71     100 relics
adventurer-72     100 relics
adventurer-73     100 relics
adventurer-74     100 relics
adventurer-75     100 relics
adventurer-76     100 relics
adventurer-77     100 relics
adventurer-78     100 relics
adventurer-79     100 relics
adventurer-80     100 relics
adventurer-81     100 relics
adventurer-82     100 relics
adventurer-83     100 relics
adventurer-84     100 relics
adventurer-85     100 relics
adventurer-86     100 relics
adventurer-87     100 relics
adventurer-88     100 relics
adventurer-89     100 relics
adventurer-90     100 relics
adventurer-91     100 relics
adventurer-92     100 relics
adventurer-93     100 relics
adventurer-94     100 relics
adventurer-95     100 relics
adventurer-96     100 relics
adventurer-97     100 relics
adventurer-98     100 relics
adventurer-99     100 relics
adventurer-100    100 relics
adventurer-101    100 relics
adventurer-102    100 relics
adventurer-103    100 relics
adventurer-104    100 relics
adventurer-105    100 relics
adventurer-106    100 relics
adventurer-107    100 relics
adventurer-108    100 relics
adventurer-109    100 relics
adventurer-110    100 relics
adventurer-111    100 relics
adventurer-112    100 relics
adventurer-113    100 relics
adventurer-114    100 relics
adventurer-115    100 relics
adventurer-116    100 relics
adventurer-117    100 relics
adventurer-118    100 relics
adventurer-119    100 relics
adventurer-120    100 relics
adventurer-121    100 relics
adventurer-122    100 relics
adventurer-123    100 relics
adventurer-124    100 relics
adventurer-125    100 relics
adventurer-126    100 relics
adventurer-127    100 relics
adventurer-128    100 relics
Total by players : 12800
Ledger total     : 12800
Ledger events    : 12800
```

## 6. Architectural trade-offs

- Correctness / reliability
The solution is robust because it ensures data integrity through two levels of protection:
1. `synchronized` in `ForgeLedger` to ensure the atomicity of updates.
2. Global sorting in `LockPair` to eliminate the possibility of deadlocks, ensuring that the game never stops. The system is deterministic.

- Performance / Throughput
By using fine-grained locking on each `ForgeStation`, we maximize parallelism. Only adventurers competing for the same specific tool need to wait, while the rest of the system continues to operate at full capacity.

- Contention
Contention is strictly limited to the `ForgeStations`. We eliminate any global contention points, ensuring that the ledger (`ForgeLedger`) does not become a bottleneck, since the locking time there is minimal (just an increment and an `add`).

- Maintainability
The sorting rule by `id()` is explicit and straightforward. It requires no complex configurations or additional maintenance if the number of stations increases. It is a low-cognitive-cost solution for any future developer.

- Scalability
The system is highly scalable. The cost of sorting two integers (the `id`s) is negligible compared to the work of the forge. The system will maintain its performance even with a large number of players, provided the ratio of available stations is adequate.

## 7. Mini ADR

### Context
The game “Relic Rush” uses concurrent threads (platform threads) that compete to acquire two exclusive resources (`ForgeStation`) using the `LockPair` class. The initial implementation acquired the locks in the order provided by the caller, which created a deterministic risk of circular waiting and deadlocks in the execution rounds.

### Decision
Implement a global sorting rule based on the unique identifier (`id()`) of each `ForgeStation` within the `LockPair` class. Regardless of the order in which the adventurer requests the tools, the system always evaluates and acquires the resource with the lowest ID first.

### Alternatives considered
1. **A single global lock:** Rejected because it destroys the game's concurrency, turning execution into a sequential process and violating the lab's restrictions.
2. **Use of timeouts with `tryLock()`:** Discarded because Java’s native `synchronized` blocks do not support timeouts, and this would require migrating to the `Lock` API in `java.util.concurrent`, unnecessarily altering the base design.

### Consequences
- **Performance:** Fine-grained locking is preserved, allowing threads using different stations to work in parallel.
- **Reliability:** The risk of deadlock is completely eliminated, ensuring that games end successfully.

### Evidence
Running `DeadlockProbe` and the stress tests (`InvariantProbe`) with up to 128 players confirms zero deadlocks and compliance with the invariant in all rounds.

### Risks
None significant, since integer comparison is a constant-time operation with no impact on overall performance.

### Summary
The circular wait is eliminated, breaking one of Coffman’s conditions. The code maintains low coupling and high maintainability.


## 8. Conclusions

1. **Barrier-based coordination (`CyclicBarrier`) is essential:** It allows tasks to be synchronized and ensures observation windows without resorting to arbitrary timeouts (`Thread.sleep`), guaranteeing data visibility between threads according to the Java Memory Model.
2. **Shared state protection prevents silent data loss:** The use of appropriate mutual exclusion mechanisms in structures such as `ForgeLedger` ensures that counters and concurrent collections keep system invariants intact under high load.
3. **Resource ordering eliminates deadlocks without sacrificing concurrency:** Breaking the circular wait condition through a deterministic acquisition rule (by ID) allows for a fine-grained model, resulting in a reliable, efficient, and highly scalable concurrent system.


## 9. Bonus: graphical interface

### 9.1 What was built

A Swing window (`RelicRushGuiMain`) that shows the game live and controls it:

| Requirement | Where it appears |
|---|---|
| Adventurers / players | scoreboard table, sorted by relics, updated every round |
| Forge stations and their state | station list with a light per station: green = free, red = crafting, with the name of the thread using it |
| Scores and crafted relics | scoreboard + the `scoreSum / ledger / events` status line |
| Simulation state and invariants | status line (Ready / Running / Paused / Stopped / Finished) and an `invariant=OK` / `invariant=BROKEN` badge |
| Start / Pause / Resume / Stop | buttons, plus a round-delay slider that works while the game runs |

To run it:

```bash
java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushGuiMain
java -cp target/classes edu.eci.arsw.relicrush.app.RelicRushGuiMain 16 8 100
```

### 9.2 How it integrates without touching the concurrency

The window is just **one more viewer**. The engine publishes a `RoundSnapshot`
after every round to whoever subscribed (`GameListener`); the console output and
the window are two subscribers of the same news. The engine does not know the
GUI exists, and none of the game's synchronization was changed to support it:
the barriers, the ledger lock and the station-ordering rule are exactly the ones
verified in sections 2 to 5.

Evidence that nothing moved: after the refactor, `InvariantProbe 8 6 50` output
is **byte-for-byte identical** to the binary built from the commit before the GUI
work, and `DeadlockProbe` stays clean (3/3 runs at every step).

### 9.3 The concurrency rules the GUI must follow

The GUI adds one genuinely new concurrency problem, and it is the one the bonus
is graded on: **Swing only allows its own thread (the EDT) to touch the screen.**
Game threads never draw. The window follows three rules:

1. **Round news crosses threads through `SwingUtilities.invokeLater`.** Listener
   callbacks arrive on the coordinator thread; the GUI hands the update over to
   the Swing thread instead of touching components directly.
2. **Station lights never touch a lock.** Each station carries a small tag
   (`heldBy`) saying which thread is crafting there. `LockPair` writes the tag
   while it already holds the station; the window reads it through an atomic
   reference, without ever locking the station. This matters: a viewer that
   synchronized on a station would become one more participant in the locking
   that this lab spent Parts III-V getting right.
3. **The buttons go through `GameControls`,** a small switch with its own
   private lock (wait/notify, no sleep polling). Pause makes the coordinator not
   show up for the next round; since a round cannot start without it, every
   adventurer waits at the barrier that already existed. Stop interrupts the
   adventurers, which exits them through the exception paths the starter
   already handled.

### 9.4 Two deliberate design decisions

**Pause waits for the round boundary.** The pause button takes effect between
rounds, never in the middle of a craft - and the status bar says so. Freezing a
thread while it holds a forge station is exactly how you would create a new
deadlock, so "pause instantly" would undo the lab. At the boundary, every
adventurer is already waiting at the barrier and nobody holds anything.

**The speed slider is pacing, not coordination.** The game finishes in about two
seconds - too fast to watch. The slider adds a delay between rounds, applied
while every adventurer is parked at the barrier anyway. Correctness never
depends on it: at delay 0 the game is exactly as fast and as correct as the
console version. This is the same distinction section 2.5 draws for the
starter's own sleeps - sampling and pacing are legitimate; *establishing that
another thread finished* by sleeping is not. Stop wakes the delay immediately,
so the window never feels stuck.

One consequence worth stating: **Start after Stop begins a new match.** Stopping
a game ends its adventurer threads for good, so the Start button builds a fresh
engine. That mirrors how the game is designed - one engine, one match.

### 9.5 Verification

The window was exercised by a driver that clicks the real buttons and reads the
real labels:

```text
after start: ROUND 12 | scoreSum=96 | ledger=96 | events=96
rounds are flowing: true
invariant shown OK: true
frozen while paused: true (13 -> 13)
advanced after resume: true (13 -> 21)
after stop: STOPPED at round 22 | scoreSum=176 | ledger=176 | events=176
stopped cleanly: true
adventurer threads alive after stop: 0 (0 expected)
restart works, rounds flowing again: true
```

The stopped totals are exact (22 rounds x 8 players = 176 on all three
counters), the pause genuinely froze the game, and stopping left zero
adventurer threads behind.
