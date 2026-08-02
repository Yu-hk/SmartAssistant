#!/usr/bin/env python3
"""SmartAssistant closed-loop QPS benchmark for deterministic order queries."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import platform
import statistics
import time
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx


SCENARIOS = {
    "order-direct": os.getenv("SMARTASSISTANT_ORDER_URL", "http://localhost:8085").rstrip("/")
        + "/api/order/agent/process",
    "router-direct": os.getenv("SMARTASSISTANT_ROUTER_URL", "http://localhost:8083").rstrip("/")
        + "/api/router/route",
    "gateway-router": os.getenv("SMARTASSISTANT_GATEWAY_URL", "http://localhost:8081").rstrip("/")
        + "/assistant/api/router/route",
}

USER_SERVICE = os.getenv("SMARTASSISTANT_USER_URL", "http://localhost:8086").rstrip("/")


@dataclass(frozen=True)
class TestUser:
    number: int
    user_id: str
    token: str


@dataclass
class StageResult:
    scenario: str
    concurrency: int
    duration_seconds: float
    attempted: int
    succeeded: int
    failed: int
    achieved_qps: float
    success_qps: float
    error_rate_percent: float
    latency_min_ms: float
    latency_mean_ms: float
    latency_p50_ms: float
    latency_p95_ms: float
    latency_p99_ms: float
    latency_max_ms: float
    status_codes: dict[str, int]
    error_samples: list[str]


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


async def load_users(client: httpx.AsyncClient, count: int) -> list[TestUser]:
    semaphore = asyncio.Semaphore(min(20, count))

    async def login(number: int) -> TestUser:
        username = f"load_user_{number:06d}"
        async with semaphore:
            response = await client.post(
                f"{USER_SERVICE}/api/auth/login",
                json={"username": username, "password": "password"},
            )
        response.raise_for_status()
        payload = response.json()
        if payload.get("code") != 0 or not payload.get("data", {}).get("token"):
            raise RuntimeError(f"login failed for {username}: {payload.get('message')}")
        data = payload["data"]
        return TestUser(number=number, user_id=str(data["userId"]), token=data["token"])

    return await asyncio.gather(*(login(number) for number in range(1, count + 1)))


def validate_response(scenario: str, response: httpx.Response, order_id: str) -> tuple[bool, str | None]:
    if response.status_code != 200:
        return False, f"HTTP {response.status_code}"
    try:
        if scenario == "order-direct":
            body = response.text
            return (order_id in body), (None if order_id in body else "order id missing")
        payload = response.json()
        data = payload.get("data") or {}
        result = str(data.get("result") or "")
        valid = payload.get("code") == 0 and data.get("agentName") == "order_agent" and order_id in result
        return valid, (None if valid else "unexpected router response")
    except (ValueError, TypeError) as exc:
        return False, f"response parse error: {exc}"


async def run_stage(
    client: httpx.AsyncClient,
    users: list[TestUser],
    scenario: str,
    concurrency: int,
    duration: float,
    warmup: float,
    stage_number: int,
) -> StageResult:
    sequence = 0

    async def request_once(measure: bool) -> tuple[float, int, bool, str | None]:
        nonlocal sequence
        current = sequence
        sequence += 1
        user = users[current % min(concurrency, len(users))]
        order_no = ((current // len(users)) % 20) + 1
        order_id = f"ORD-LOAD{user.number:06d}{order_no:03d}"
        unique_no = stage_number * 10_000_000 + current
        body = {
            "question": f"查询订单 {order_id} 的状态和物流，压测请求序号 {unique_no}",
            "userId": user.user_id,
            "requestId": f"LOAD-PERF-{stage_number:02d}-{current:09d}",
        }
        headers: dict[str, str] = {}
        if scenario == "gateway-router":
            headers["Authorization"] = f"Bearer {user.token}"
        started = time.perf_counter()
        try:
            response = await client.post(SCENARIOS[scenario], json=body, headers=headers)
            latency = (time.perf_counter() - started) * 1000
            success, error = validate_response(scenario, response, order_id)
            return latency, response.status_code, success, error
        except Exception as exc:  # noqa: BLE001 - benchmark must account for all client failures
            latency = (time.perf_counter() - started) * 1000
            return latency, 0, False, f"{type(exc).__name__}: {exc}"

    async def execute_window(window_seconds: float, measure: bool) -> list[tuple[float, int, bool, str | None]]:
        deadline = asyncio.get_running_loop().time() + window_seconds
        collected: list[tuple[float, int, bool, str | None]] = []

        async def worker() -> None:
            while asyncio.get_running_loop().time() < deadline:
                result = await request_once(measure)
                if measure:
                    collected.append(result)

        await asyncio.gather(*(worker() for _ in range(concurrency)))
        return collected

    if warmup > 0:
        await execute_window(warmup, False)

    started = time.perf_counter()
    results = await execute_window(duration, True)
    elapsed = time.perf_counter() - started
    success_latencies = [latency for latency, _, success, _ in results if success]
    status_codes = Counter(str(status) for _, status, _, _ in results)
    errors = [error for _, _, success, error in results if not success and error]
    succeeded = len(success_latencies)
    attempted = len(results)

    return StageResult(
        scenario=scenario,
        concurrency=concurrency,
        duration_seconds=round(elapsed, 3),
        attempted=attempted,
        succeeded=succeeded,
        failed=attempted - succeeded,
        achieved_qps=round(attempted / elapsed, 2),
        success_qps=round(succeeded / elapsed, 2),
        error_rate_percent=round((attempted - succeeded) * 100 / attempted, 3) if attempted else 100.0,
        latency_min_ms=round(min(success_latencies), 2) if success_latencies else 0.0,
        latency_mean_ms=round(statistics.fmean(success_latencies), 2) if success_latencies else 0.0,
        latency_p50_ms=round(percentile(success_latencies, 0.50), 2),
        latency_p95_ms=round(percentile(success_latencies, 0.95), 2),
        latency_p99_ms=round(percentile(success_latencies, 0.99), 2),
        latency_max_ms=round(max(success_latencies), 2) if success_latencies else 0.0,
        status_codes=dict(sorted(status_codes.items())),
        error_samples=list(dict.fromkeys(errors))[:5],
    )


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=list(SCENARIOS))
    parser.add_argument("--concurrency", nargs="+", type=int, default=[10, 50, 100])
    parser.add_argument("--duration", type=float, default=12.0)
    parser.add_argument("--warmup", type=float, default=2.0)
    parser.add_argument("--output", default="test-data/performance/qps-results-2026-08-01.json")
    args = parser.parse_args()

    if any(value < 1 or value > 500 for value in args.concurrency):
        raise SystemExit("concurrency values must be between 1 and 500")
    if args.duration <= 0 or args.warmup < 0:
        raise SystemExit("duration must be positive and warmup must be non-negative")

    max_concurrency = max(args.concurrency)
    limits = httpx.Limits(
        max_connections=max(200, max_concurrency * 2),
        max_keepalive_connections=max(100, max_concurrency),
        keepalive_expiry=30.0,
    )
    timeout = httpx.Timeout(15.0, connect=5.0)
    async with httpx.AsyncClient(limits=limits, timeout=timeout, http2=False) as client:
        users = await load_users(client, max_concurrency)
        print(f"authenticated_users={len(users)}", flush=True)
        results: list[StageResult] = []
        stage_number = 0
        for scenario in args.scenarios:
            for concurrency in args.concurrency:
                stage_number += 1
                print(
                    f"stage_start scenario={scenario} concurrency={concurrency} "
                    f"warmup={args.warmup}s duration={args.duration}s",
                    flush=True,
                )
                result = await run_stage(
                    client,
                    users,
                    scenario,
                    concurrency,
                    args.duration,
                    args.warmup,
                    stage_number,
                )
                results.append(result)
                print("stage_result " + json.dumps(asdict(result), ensure_ascii=False), flush=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "method": "closed-loop fixed concurrency",
        "dataset": {
            "users": 1000,
            "sessions": 5000,
            "products": 120,
            "orders": 20000,
            "routing_logs_before_test": 30000,
        },
        "client": {
            "python": platform.python_version(),
            "httpx": httpx.__version__,
            "logical_processors": 16,
        },
        "settings": {
            "scenarios": args.scenarios,
            "concurrency": args.concurrency,
            "duration_seconds": args.duration,
            "warmup_seconds": args.warmup,
            "authenticated_users": max_concurrency,
        },
        "results": [asdict(result) for result in results],
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"results_file={output.resolve()}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
