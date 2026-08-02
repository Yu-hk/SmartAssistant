#!/usr/bin/env python3
"""Dependency-free SmartAssistant QPS benchmark for Python 3.6 servers."""

import argparse
import concurrent.futures
import http.client
import json
import math
import os
import platform
import statistics
import threading
import time
from collections import Counter
from datetime import datetime
from urllib.parse import urlsplit


def percentile(values, ratio):
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * ratio
    lower = int(math.floor(rank))
    upper = int(math.ceil(rank))
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


class JsonClient(object):
    def __init__(self, base_url, timeout=15):
        parsed = urlsplit(base_url)
        self.scheme = parsed.scheme
        self.host = parsed.hostname
        self.port = parsed.port or (443 if self.scheme == "https" else 80)
        self.prefix = parsed.path.rstrip("/")
        self.timeout = timeout
        self.connection = None

    def close(self):
        if self.connection:
            try:
                self.connection.close()
            except Exception:
                pass
        self.connection = None

    def _connect(self):
        connection_type = http.client.HTTPSConnection if self.scheme == "https" else http.client.HTTPConnection
        self.connection = connection_type(self.host, self.port, timeout=self.timeout)

    def post(self, path, body, headers=None):
        if self.connection is None:
            self._connect()
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers = {"Content-Type": "application/json", "Connection": "keep-alive"}
        request_headers.update(headers or {})
        try:
            self.connection.request("POST", self.prefix + path, body=payload, headers=request_headers)
            response = self.connection.getresponse()
            content = response.read().decode("utf-8", "replace")
            return response.status, content
        except Exception:
            self.close()
            raise


def login_users(user_url, count):
    def login(number):
        client = JsonClient(user_url)
        try:
            status, content = client.post(
                "/api/auth/login",
                {"username": "load_user_{:06d}".format(number), "password": "password"},
            )
            payload = json.loads(content)
            data = payload.get("data") or {}
            if status != 200 or payload.get("code") != 0 or not data.get("token"):
                raise RuntimeError("login failed for user {}: HTTP {}".format(number, status))
            return {"number": number, "user_id": str(data["userId"]), "token": data["token"]}
        finally:
            client.close()

    workers = min(20, count)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        return list(executor.map(login, range(1, count + 1)))


def validate(scenario, status, content, order_id):
    if status != 200:
        return False, "HTTP {}".format(status)
    try:
        if scenario == "order-direct":
            return (order_id in content), (None if order_id in content else "order id missing")
        payload = json.loads(content)
        data = payload.get("data") or {}
        result = str(data.get("result") or "")
        valid = payload.get("code") == 0 and data.get("agentName") == "order_agent" and order_id in result
        return valid, (None if valid else "unexpected router response")
    except (TypeError, ValueError) as exc:
        return False, "response parse error: {}".format(exc)


def run_window(scenario, base_url, path, users, concurrency, duration, stage_no, measure):
    deadline = time.monotonic() + duration
    start_gate = threading.Barrier(concurrency)

    def worker(worker_no):
        client = JsonClient(base_url)
        user = users[worker_no]
        results = []
        sequence = 0
        try:
            start_gate.wait()
            while time.monotonic() < deadline:
                order_no = (sequence % 20) + 1
                order_id = "ORD-LOAD{:06d}{:03d}".format(user["number"], order_no)
                unique_no = stage_no * 100000000 + worker_no * 100000 + sequence
                body = {
                    "question": "查询订单 {} 的当前状态和物流，压测请求序号 {}".format(order_id, unique_no),
                    "userId": user["user_id"],
                    "requestId": "LOAD-SERVER-{:02d}-{:03d}-{:06d}".format(stage_no, worker_no, sequence),
                }
                headers = {}
                if scenario == "gateway-router":
                    headers["Authorization"] = "Bearer " + user["token"]
                started = time.monotonic()
                try:
                    status, content = client.post(path, body, headers)
                    latency = (time.monotonic() - started) * 1000.0
                    success, error = validate(scenario, status, content, order_id)
                except Exception as exc:
                    latency = (time.monotonic() - started) * 1000.0
                    status, success = 0, False
                    error = "{}: {}".format(type(exc).__name__, exc)
                if measure:
                    results.append((latency, status, success, error))
                sequence += 1
        finally:
            client.close()
        return results

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        batches = list(executor.map(worker, range(concurrency)))
    elapsed = time.monotonic() - started
    return [item for batch in batches for item in batch], elapsed


def run_stage(scenario, base_url, path, users, concurrency, duration, warmup, stage_no):
    active_users = users[:concurrency]
    if warmup > 0:
        run_window(scenario, base_url, path, active_users, concurrency, warmup, stage_no, False)
    results, elapsed = run_window(scenario, base_url, path, active_users, concurrency, duration, stage_no, True)
    successful = [row[0] for row in results if row[2]]
    errors = [row[3] for row in results if not row[2] and row[3]]
    statuses = Counter(str(row[1]) for row in results)
    attempted = len(results)
    succeeded = len(successful)
    return {
        "scenario": scenario,
        "concurrency": concurrency,
        "duration_seconds": round(elapsed, 3),
        "attempted": attempted,
        "succeeded": succeeded,
        "failed": attempted - succeeded,
        "achieved_qps": round(attempted / elapsed, 2),
        "success_qps": round(succeeded / elapsed, 2),
        "error_rate_percent": round((attempted - succeeded) * 100.0 / attempted, 3) if attempted else 100.0,
        "latency_min_ms": round(min(successful), 2) if successful else 0.0,
        "latency_mean_ms": round(statistics.mean(successful), 2) if successful else 0.0,
        "latency_p50_ms": round(percentile(successful, 0.50), 2),
        "latency_p95_ms": round(percentile(successful, 0.95), 2),
        "latency_p99_ms": round(percentile(successful, 0.99), 2),
        "latency_max_ms": round(max(successful), 2) if successful else 0.0,
        "status_codes": dict(sorted(statuses.items())),
        "error_samples": list(dict.fromkeys(errors))[:5],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--order-url", required=True)
    parser.add_argument("--router-url", required=True)
    parser.add_argument("--gateway-url", required=True)
    parser.add_argument("--user-url", required=True)
    parser.add_argument("--scenarios", nargs="+", default=["order-direct", "router-direct", "gateway-router"])
    parser.add_argument("--concurrency", nargs="+", type=int, default=[5, 10, 20])
    parser.add_argument("--duration", type=float, default=8.0)
    parser.add_argument("--warmup", type=float, default=1.0)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if any(value < 1 or value > 200 for value in args.concurrency):
        raise SystemExit("concurrency must be between 1 and 200")

    endpoints = {
        "order-direct": (args.order_url, "/api/order/agent/process"),
        "router-direct": (args.router_url, "/api/router/route"),
        "gateway-router": (args.gateway_url, "/assistant/api/router/route"),
    }
    users = login_users(args.user_url, max(args.concurrency))
    print("authenticated_users={}".format(len(users)), flush=True)
    results = []
    stage_no = 0
    for scenario in args.scenarios:
        if scenario not in endpoints:
            raise SystemExit("unknown scenario: " + scenario)
        for concurrency in args.concurrency:
            stage_no += 1
            print(
                "stage_start scenario={} concurrency={} warmup={}s duration={}s".format(
                    scenario, concurrency, args.warmup, args.duration
                ),
                flush=True,
            )
            base_url, path = endpoints[scenario]
            result = run_stage(
                scenario, base_url, path, users, concurrency, args.duration, args.warmup, stage_no
            )
            results.append(result)
            print("stage_result " + json.dumps(result, ensure_ascii=False, sort_keys=True), flush=True)

    payload = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "method": "closed-loop fixed concurrency with persistent HTTP/1.1 connections",
        "server": {"logical_processors": os.cpu_count(), "python": platform.python_version()},
        "dataset": {"users": 1000, "sessions": 5000, "products": 120, "orders": 20000},
        "settings": {
            "scenarios": args.scenarios,
            "concurrency": args.concurrency,
            "duration_seconds": args.duration,
            "warmup_seconds": args.warmup,
            "authenticated_users": len(users),
        },
        "results": results,
    }
    with open(args.output, "w") as output:
        json.dump(payload, output, ensure_ascii=False, indent=2, sort_keys=True)
        output.write("\n")
    print("results_file={}".format(args.output), flush=True)


if __name__ == "__main__":
    main()
