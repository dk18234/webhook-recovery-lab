# API reference

Base URL: `http://localhost:8080`. All bodies are JSON. Operator endpoints require `X-Admin-Token`. Signed ingestion uses its own HMAC headers instead.

## Signed ingestion

`POST /webhooks/credits`

```json
{"id":"evt_001","accountId":"acct_demo","credits":10}
```

`id` and `accountId` use letters, digits, `_`, or `-`, with 1–80 characters. `credits` is an integer from 1 to 1,000,000. See the README and Python sender for signing the original bytes.

| HTTP status | Meaning |
| --- | --- |
| 202 | Event committed to the inbox; processing is asynchronous |
| 200 | Identical logical event already exists; delivery count incremented |
| 400 | Malformed JSON, unknown fields, or invalid business fields |
| 401 | Missing, stale, future-dated, or invalid signature |
| 409 | Event ID exists with different business fields |
| 413 | Body exceeds 16 KiB |
| 415 | Content type is not application/json |

An ambiguous network response can be retried with the same ID and same business fields. An acknowledged event has been persisted, but may still be waiting for a worker.

## Inspection

| Method and path | Result |
| --- | --- |
| `GET /api/config` | Backend mode, worker state, lease duration, retry budget, demo flag |
| `GET /api/events` | Most recent events; `status`, `q`, `limit` (1–100), `offset` (0–100000) |
| `GET /api/events/{id}` | Event, processing attempts, audit activity, and ledger evidence |
| `GET /api/stats` | Durable counts, credits, account balances, and attempt outcomes |
| `GET /actuator/health` | Public readiness signal without private details |
| `GET /actuator/prometheus` | Authenticated metrics |

Valid statuses: `RECEIVED`, `PROCESSING`, `RETRY_WAIT`, `SUCCEEDED`, `DEAD_LETTER`.

## Operations

| Method and path | Behavior |
| --- | --- |
| `POST /api/events/{id}/replay` | Dead-letter events start a new retry cycle. Completed events return a no-op. Other states return 409. |
| `POST /api/worker/pause` | Pause automatic polling on this instance |
| `POST /api/worker/resume` | Resume automatic polling if enabled by configuration |
| `POST /api/worker/tick` | Reconcile expired leases and process at most one ready event |

The worker operation routes accept an empty body. `WORKER_ENABLED=false` prevents automatic polling even after a resume request; manual ticks still work.

## Authenticated synthetic scenarios

These routes return 404 when `DEMO_ENABLED=false`.

`POST /api/demo`

```json
{"scenario":"crash-after"}
```

Supported scenarios: `normal`, `duplicates`, `transient`, `poison`, `crash-before`, `crash-after`. Each creates a new synthetic event granting 10 credits. The duplicate scenario records ten deliveries. Scenario creation uses the internal trusted inbox path; use `send_event.py` to test the signed HTTP ingestion boundary.

`POST /api/events/{id}/fault`

```json
{"mode":"NONE","remaining":0}
```

Modes: `NONE`, `TRANSIENT`, `POISON`, `CRASH_BEFORE_COMMIT`, `CRASH_AFTER_COMMIT`. Remaining transient failures must be 0–20. Fault changes are rejected while an event is actively processing. Crash injection is consumed once during claiming, so recovery can make progress.

## Error responses

Domain failures return a stable `code` and an operator-readable `message`. A database error is logged server-side; its internal details are not exposed to clients.

```json
{"code":"EVENT_ID_CONFLICT","message":"This event ID already belongs to a different payload."}
```
