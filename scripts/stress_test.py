#!/usr/bin/env python3
"""Concurrent sensor-reading load generator for the MecaniQA API."""

import argparse
import json
import random
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--url",
        default="http://localhost:30080/api/sensor-readings",
        help="sensor-reading endpoint",
    )
    parser.add_argument("--workers", type=int, default=32, help="number of concurrent workers")
    parser.add_argument("--duration-seconds", type=int, default=120, help="test duration")
    parser.add_argument(
        "--error-rate",
        type=float,
        default=0.0,
        help="fraction of deliberately empty (rejected) readings, from 0 to 1",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("evidence"),
        help="directory for the timestamped JSON result",
    )
    args = parser.parse_args()
    if args.workers < 1:
        parser.error("--workers must be at least 1")
    if args.duration_seconds < 1:
        parser.error("--duration-seconds must be at least 1")
    if not 0 <= args.error_rate <= 1:
        parser.error("--error-rate must be between 0 and 1")
    return args


def main():
    args = parse_args()
    deadline = time.monotonic() + args.duration_seconds
    lock = threading.Lock()
    totals = Counter()
    statuses = Counter()
    transport_errors = []

    def worker():
        local = Counter()
        while time.monotonic() < deadline:
            payload = b"" if random.random() < args.error_rate else (
                b'{"sensorId":"stress-test","temperatureC":92.4,"reading":"ok"}'
            )
            request = urllib.request.Request(
                args.url,
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=5) as response:
                    local["requests"] += 1
                    local[f"http_{response.status}"] += 1
                    if 200 <= response.status < 300:
                        local["accepted"] += 1
                    else:
                        local["rejected"] += 1
            except urllib.error.HTTPError as error:
                local["requests"] += 1
                local[f"http_{error.code}"] += 1
                local["rejected"] += 1
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                local["requests"] += 1
                local["transport_errors"] += 1
                with lock:
                    transport_errors.append(str(error))
        with lock:
            totals.update(local)
            for key, value in local.items():
                if key.startswith("http_"):
                    statuses[key] += value

    started_at = datetime.now(timezone.utc)
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(worker) for _ in range(args.workers)]
        for future in futures:
            future.result()
    elapsed = time.monotonic() - started

    result = {
        "started_at_utc": started_at.isoformat(),
        "url": args.url,
        "duration_seconds": round(elapsed, 2),
        "workers": args.workers,
        "configured_error_rate": args.error_rate,
        "total_requests": totals["requests"],
        "accepted": totals["accepted"],
        "rejected": totals["rejected"],
        "transport_errors": totals["transport_errors"],
        "requests_per_second": round(totals["requests"] / elapsed, 2) if elapsed else 0,
        "http_statuses": dict(sorted(statuses.items())),
    }
    if transport_errors:
        result["last_transport_error"] = transport_errors[-1]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    filename = "stress-test-" + started_at.strftime("%Y%m%dT%H%M%SZ") + ".json"
    output_path = args.output_dir / filename
    output_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    print(f"Evidence report: {output_path}")
    if totals["requests"] == 0 or totals["transport_errors"] > 0:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
