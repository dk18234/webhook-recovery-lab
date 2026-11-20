#!/usr/bin/env python3
"""Kill and restart the built Java process against an existing local PostgreSQL database.

Set DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, ADMIN_TOKEN, and WEBHOOK_SECRET as for the app.
This script owns only the two Java processes it starts; use a free PORT (default 18080).
"""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from send_event import send


ROOT = Path(__file__).resolve().parents[1]
PORT = os.environ.get("PORT", "18080")
BASE = "http://127.0.0.1:" + PORT
TOKEN = os.environ.get("ADMIN_TOKEN", "local-admin-token-change-before-hosting")
SECRET = os.environ.get("WEBHOOK_SECRET", "local-webhook-secret-change-before-hosting")


def api(path, body=None):
    request = urllib.request.Request(BASE + path, None if body is None else json.dumps(body).encode(),
        {"X-Admin-Token": TOKEN, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def start(log):
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    process = subprocess.Popen([java, "-jar", str(ROOT / "target/webhook-recovery-lab-1.0.0.jar")],
        env={**os.environ, "PORT": PORT, "WORKER_ENABLED": "false"}, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError("Java process exited during startup; inspect test-results/process-restart.log")
        try:
            api("/api/config")
            return process
        except (urllib.error.URLError, OSError):
            time.sleep(0.25)
    process.kill()
    process.wait()
    raise TimeoutError("Backend did not become ready")


def main():
    out = ROOT / "test-results"
    out.mkdir(exist_ok=True)
    identity = "evt_restart_" + uuid.uuid4().hex[:10]
    process = None
    with (out / "process-restart.log").open("w") as log:
        try:
            process = start(log)
            code, _ = send(BASE, {"id": identity, "accountId": "acct_process_restart", "credits": 10}, SECRET)
            assert code == 202
            assert api("/api/events/" + identity)["event"]["status"] == "RECEIVED"
            process.kill()
            process.wait(timeout=10)
            process = start(log)
            assert api("/api/events/" + identity)["event"]["status"] == "RECEIVED"
            api("/api/worker/tick", {})
            detail = api("/api/events/" + identity)
            assert detail["event"]["status"] == "SUCCEEDED"
            assert len(detail["ledger"]) == 1
            assert detail["ledger"][0]["credits"] == 10
            evidence = {"eventId": identity, "processTermination": "SIGKILL", "acceptedBeforeKill": True,
                "presentAfterRestart": True, "finalStatus": detail["event"]["status"], "ledgerEntries": len(detail["ledger"])}
            (out / "process-restart.json").write_text(json.dumps(evidence, indent=2) + "\n")
            print(json.dumps(evidence, indent=2))
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=15)


if __name__ == "__main__":
    main()
