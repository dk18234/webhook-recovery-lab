# Reverse-engineering guide

Use working behavior as the starting point. For each session: predict, run, trace, explain, then change one small feature yourself.

## Session 1 — Follow one event

Run a normal event. Trace `LabController.receive` → `Signatures.verify` → `Inbox.accept`. Find the transaction template and the HTTP response. Explain what happens if the process dies before the commit, and what happens if it dies after the commit but before the sender receives 202.

Practice: send the same ID again. Predict the status code and ledger count before checking them.

## Session 2 — Let the database protect identity

Read the events primary key and credit ledger primary key in the migration. These solve different problems: event identity and business-effect identity. Read the `ON CONFLICT` statements and canonical payload hash. Explain why changing credits while keeping an event ID is a conflict.

Practice: add a test for a differently formatted JSON body with unchanged business fields. Locate the existing integration case afterward and compare approaches.

## Session 3 — Understand claiming and ownership

Read `QueueWorker.claim`. Sketch two workers competing for the same row. Explain what `SKIP LOCKED` does and why it does not define a fairness policy. Find the lease token and inspect how `owned` fences writes from stale claims.

Practice: run the stale-worker integration test. Explain why the old worker cannot commit after a new worker has claimed the event.

## Session 4 — Move the crash point

Compare the two crash scenarios. Before commit, ledger insertion and account balance update both disappear. After commit, the ledger is durable but completion tracking is unfinished. Read `recoverExpired`: it checks the ledger before the retry budget.

Practice: use the process restart script and identify which evidence proves that work survived the JVM's termination.

## Session 5 — Make retries understandable

Read `fail`, the exponential delay, jitter, terminal errors, and attempt budget. Explain why a retryable failure can eventually become dead-lettered. Compare `attempt_count` with `cycle_attempt` and show what replay preserves.

Practice: introduce a third transient timeout, predict the attempt count, then change the scenario and verify it.

## Session 6 — Build a feature you own

Choose one bounded extension:

- Add a second synthetic event type with a clearly defined business invariant.
- Add a filtered CSV export of event and attempt evidence.
- Add a test that runs accepted work through two separate JVM restarts.

Write the acceptance criteria first. Explain which existing transaction boundary protects the new behavior. Add a meaningful failure test. Record your design decision in the README.

## Interview questions to answer without reading code

1. Why is persistence before acknowledgement necessary?
2. What is the difference between a duplicate delivery and a conflicting event ID?
3. What does your unique ledger constraint protect?
4. Can a worker lease alone prevent duplicate effects?
5. How does recovery distinguish an uncommitted attempt from a committed business effect?
6. What happens if PostgreSQL is down while the sender is waiting?
7. Why would sending an external email or charging a real card require more design?
8. Which measurements did you actually collect, and under what conditions?

## Résumé wording after you can demonstrate the design

- Built a Java/Spring Boot webhook inbox with HMAC verification, PostgreSQL persistence, fenced worker leases, retries, and dead-letter replay.
- Protected synthetic credit grants with an atomic ledger and balance transaction; verified concurrency, crash recovery, and restart behavior against PostgreSQL.

Add performance numbers only after running a documented measurement on the environment you describe. This repository's delivery and worker counts are correctness scenarios, not production throughput claims.
