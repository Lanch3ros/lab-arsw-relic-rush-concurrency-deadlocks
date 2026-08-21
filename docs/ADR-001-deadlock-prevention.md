# ADR-001: Deadlock prevention strategy

## Context
The "Relic Rush" game manages concurrent threads that compete to acquire two exclusive resources (`ForgeStation`) through the `LockPair` class. The original implementation acquired monitors in the order provided by the caller (*caller-supplied order*), which created a deterministic risk of waiting cycles and deadlocks when multiple adventurers crossed their requests.

## Decision
Implement a global ordering rule based on the unique identifier (`id()`) of each `ForgeStation` inside the `LockPair` class. Regardless of the order requested by the adventurer, the system always evaluates and acquires the resource with the lower ID first, breaking Coffman's circular wait condition.

## Alternatives considered
1. **Single Global Lock:** Discarded because it completely destroys game concurrency, turning multithreaded execution into a strictly sequential process and violating the lab's performance guidelines.
2. **Use of Timeouts via `tryLock()`:** Discarded because native Java `synchronized` blocks do not support timeout capabilities natively, which would require an unnecessary rewrite of the synchronization API.

## Quality attributes affected
- **Correctness:** System liveness is guaranteed, preventing threads from being blocked indefinitely.
- **Performance / Throughput:** The "fine-grained locking" approach is preserved, allowing adventurers using different stations to operate in parallel without interference.
- **Maintainability:** The solution is clean, encapsulated in a single class, and easy for any developer to understand.
- **Scalability:** The cost of comparing two integers is constant time (O(1)), ensuring performance does not degrade as the number of players or stations increases.

## Evidence
- Successful execution of `DeadlockProbe`, reporting zero deadlocks ("NO DEADLOCK DETECTED").
- Stress tests executed with `InvariantProbe` (for 8, 32, and 128 players), confirming invariant compliance (`invariant=OK`) across all rounds and exact matching between player scores and the ledger.

## Consequences
- **Positive:** The system is immune to cross-resource deadlocks and maintains high concurrent performance.
- **Negative:** None relevant; the computational overhead of ID sorting is negligible.

## Risks
- Future modifications to the model introducing blocking resources outside of `LockPair` without following the same global ordering policy, which could reintroduce circular wait risks.