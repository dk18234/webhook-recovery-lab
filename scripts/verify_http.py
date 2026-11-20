#!/usr/bin/env python3
"""Prove that concurrent signed HTTP deliveries produce one event and one credit effect."""
import concurrent.futures
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid
from send_event import send


def main():
    base = os.environ.get("LAB_URL", "http://localhost:8080").rstrip("/")
    token = os.environ.get("ADMIN_TOKEN", "local-admin-token-change-before-hosting")
    secret = os.environ.get("WEBHOOK_SECRET", "local-webhook-secret-change-before-hosting")
    identity = "evt_http_" + uuid.uuid4().hex[:10]
    event = {"id": identity, "accountId": "acct_http_correctness", "credits": 10}
    headers = {"X-Admin-Token": token}
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda _: send(base, event, secret), range(60)))
    elapsed = time.perf_counter() - started
    # A manual tick also makes this verification usable when automatic polling is disabled.
    with urllib.request.urlopen(urllib.request.Request(base + "/api/worker/tick", b"", headers), timeout=20):
        pass
    for _ in range(100):
        with urllib.request.urlopen(urllib.request.Request(base + "/api/events/" + identity, headers=headers), timeout=10) as response:
            detail = json.load(response)
        if detail["event"]["status"] == "SUCCEEDED":
            break
        time.sleep(0.1)
    assert sum(code == 202 for code, _ in results) == 1
    assert sum(code == 200 for code, _ in results) == 59
    assert detail["event"]["delivery_count"] == 60
    assert detail["event"]["status"] == "SUCCEEDED"
    assert len(detail["ledger"]) == 1 and detail["ledger"][0]["credits"] == 10
    with urllib.request.urlopen(urllib.request.Request(base + "/actuator/prometheus", headers=headers)) as response:
        metrics = response.read().decode()
    assert "lab_processing_seconds" in metrics
    evidence = {"eventId": identity, "deliveries": 60, "concurrency": 12, "http202": 1, "http200": 59,
        "ledgerEntries": 1, "creditsApplied": 10, "finalStatus": detail["event"]["status"], "elapsedSeconds": round(elapsed, 3),
        "note": "Correctness demonstration; elapsed time is not a production capacity benchmark."}
    out = Path(__file__).resolve().parents[1] / "test-results"
    out.mkdir(exist_ok=True)
    (out / "http-correctness.json").write_text(json.dumps(evidence, indent=2) + "\n")
    (out / "metrics.txt").write_text("\n".join(line for line in metrics.splitlines() if line.startswith(("lab_", "# HELP lab_", "# TYPE lab_"))) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
