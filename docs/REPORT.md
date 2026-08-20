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

Explain the responsibility of both barriers:

- `roundStart`:
- `roundEnd`:

Why is `Thread.sleep(...)` not a valid replacement for a barrier?

## 3. Thread-safety problems

| Shared state | Problem | Invariant at risk | Solution | Why this solution? |
|---|---|---|---|---|
| | | | | |
| | | | | |

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
