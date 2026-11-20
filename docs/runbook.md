# Failure demonstration runbook

Start the backend, open its dashboard, and enter the operator token. Use the browser simulation for a quick visual walkthrough; verify database guarantees with the Java service and tests.

## Ninety-second walkthrough

1. Open **Failure lab → Duplicate storm**. The event shows ten deliveries and eventually one processing attempt and one ledger entry.
2. Run **Crash after commit**. Open the event while it is `PROCESSING`: a committed ledger entry already exists. After the 15-second backend lease, the worker reconciles it to `SUCCEEDED` and the attempt becomes `RECOVERED`. Credits are not added again.
3. Run **Poison event**. It becomes `DEAD_LETTER` without a credit. Choose **Clear injected fault**, then **Replay event**. The new attempt succeeds; the failed attempt remains visible.
4. Export event evidence. Show the acceptance, claim, commit, recovery, and final ledger record.

The browser companion uses an eight-second simulated lease to keep demonstrations short. Its notice and exported evidence identify it as a simulation.

## Scenarios and expected evidence

| Scenario | What to inspect | Expected result |
| --- | --- | --- |
| Clean delivery | Acceptance before processing | One event, one ledger entry, 10 credits |
| Duplicate storm | Delivery count versus ledger entries | Ten deliveries, one ledger entry |
| Transient failure | Attempt history and next due time | Two retryable attempts, then success |
| Poison event | Terminal outcome | Dead letter with zero ledger entries |
| Crash before commit | Active lease and empty ledger | Both business writes roll back; a later attempt grants credits once |
| Crash after commit | Active lease and existing ledger | Recovery acknowledges the existing effect without a new business attempt |
| Correct and replay | Failed attempt retained | Replay creates a new cycle and preserves history |

## Process restart

Use `scripts/verify_process_restart.py` against an existing development database after building the JAR. Its generated JSON records a real SIGKILL, acceptance before termination, persistence after restart, and the final ledger entry.

The integration tests also close and reopen two Spring application contexts against the same running PostgreSQL database, covering both unclaimed work and an abandoned claim with a committed effect.

## Common setup issues

| Symptom | Check |
| --- | --- |
| Port 8080 is in use | Stop the other local service, or use `PORT` for a direct JAR run and change the Compose host mapping |
| Login rejected | The token must exactly match `ADMIN_TOKEN`; Compose reads `.env`, while direct JAR runs read shell environment |
| Signed sender returns 401 | Match `WEBHOOK_SECRET`, check local clock, and sign the exact bytes being sent |
| Event waits indefinitely | Check worker state, due time, `WORKER_ENABLED`, and database availability |
| Poison replay fails again | Clear the injected fault before replaying |
| Tests report PostgreSQL cannot run as root | Run Maven as a regular user |
| Maven reports unsupported release 21 | Install a full JDK 21 and verify `./mvnw -v` uses it |

## Hosting the Java service

The JAR or container needs a Java-capable host and a reachable PostgreSQL database. Use TLS at ingress, set fresh secrets, and set `DEMO_ENABLED=false` for an operational deployment. The static portfolio host serves the labelled browser companion; it does not host the JVM.
