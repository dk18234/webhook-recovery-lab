#!/usr/bin/env python3
"""Send HMAC-signed synthetic credits using only the Python standard library."""
import argparse
import concurrent.futures
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request
import uuid


def send(base_url, payload, secret):
    body = json.dumps(payload, separators=(",", ":")).encode()
    stamp = str(int(time.time()))
    signature = hmac.new(secret.encode(), stamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    request = urllib.request.Request(base_url.rstrip("/") + "/webhooks/credits", body,
        {"Content-Type": "application/json", "X-Webhook-Timestamp": stamp, "X-Webhook-Signature": "v1=" + signature})
    try:
        with urllib.request.urlopen(request, timeout=20) as result:
            return result.status, json.load(result)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--id", default="evt_" + uuid.uuid4().hex[:12])
    parser.add_argument("--account", default="acct_demo")
    parser.add_argument("--credits", type=int, default=10)
    parser.add_argument("--copies", type=int, default=1)
    parser.add_argument("--concurrency", type=int, default=8)
    args = parser.parse_args()
    if not 1 <= args.copies <= 10000 or not 1 <= args.concurrency <= 64:
        parser.error("copies must be 1..10000; concurrency must be 1..64")
    secret = os.environ.get("WEBHOOK_SECRET", "local-webhook-secret-change-before-hosting")
    payload = {"id": args.id, "accountId": args.account, "credits": args.credits}
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(lambda _: send(args.url, payload, secret), range(args.copies)))
    summary = {"eventId": args.id, "deliveries": len(results), "accepted": sum(code == 202 for code, _ in results),
        "duplicates": sum(code == 200 for code, _ in results), "failures": [value for code, value in results if code >= 400],
        "elapsedSeconds": round(time.perf_counter() - started, 3)}
    print(json.dumps(summary, indent=2))
    raise SystemExit(1 if summary["failures"] else 0)


if __name__ == "__main__":
    main()
