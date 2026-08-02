#!/usr/bin/env python3
"""Concurrent regression test for the customer-service quick prompts."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import httpx


PROMPTS = (
    ("查询我的订单物流进度", "查到您最近的"),
    ("如何申请电子发票", "申请发票"),
    ("商品如何申请退货退款", "申请售后"),
    ("咨询商品规格和库存", "商品名称或具体型号"),
)


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = (len(ordered) - 1) * fraction
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def parse_sse(body: str) -> tuple[list[str], str]:
    events: list[str] = []
    answer: list[str] = []
    for raw_line in body.splitlines():
        line = raw_line.strip()
        if line.startswith("event:"):
            events.append(line[6:].strip())
        elif line.startswith("data:"):
            try:
                payload = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            if payload.get("type") == "text" and payload.get("content"):
                answer.append(str(payload["content"]))
    return events, "".join(answer)


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://123.56.6.102")
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument(
        "--output",
        default="test-data/performance/quick-prompt-concurrency-2026-08-02.json",
    )
    args = parser.parse_args()
    if args.concurrency < 1 or args.concurrency > 100:
        raise SystemExit("concurrency must be between 1 and 100")

    base_url = args.base_url.rstrip("/")
    timeout = httpx.Timeout(30.0, connect=5.0)
    limits = httpx.Limits(
        max_connections=args.concurrency * 2,
        max_keepalive_connections=args.concurrency,
    )
    async with httpx.AsyncClient(timeout=timeout, limits=limits) as client:
        async def login(user_no: int) -> str:
            response = await client.post(
                f"{base_url}/api/auth/login",
                json={"username": f"load_user_{user_no:06d}", "password": "password"},
            )
            response.raise_for_status()
            payload = response.json()
            token = str((payload.get("data") or {}).get("token") or "")
            if not token:
                raise RuntimeError(f"login token missing for user {user_no}")
            return token

        tokens = await asyncio.gather(*(login(i + 1) for i in range(args.concurrency)))

        async def request(index: int) -> dict[str, object]:
            prompt, marker = PROMPTS[index % len(PROMPTS)]
            started = time.perf_counter()
            try:
                response = await client.get(
                    f"{base_url}/api/math/stream/chat",
                    params={
                        "message": prompt,
                        "sessionId": f"QUICK-CONCURRENT-{time.time_ns()}-{index:03d}",
                        "showThinking": "false",
                    },
                    headers={
                        "Accept": "text/event-stream",
                        "Authorization": f"Bearer {tokens[index]}",
                    },
                )
                latency_ms = (time.perf_counter() - started) * 1000
                events, answer = parse_sse(response.text)
                passed = (
                    response.status_code == 200
                    and marker in answer
                    and {"init", "routed", "text", "done"}.issubset(events)
                )
                return {
                    "index": index,
                    "prompt": prompt,
                    "status": response.status_code,
                    "latency_ms": round(latency_ms, 2),
                    "passed": passed,
                    "events": events,
                    "answer": answer,
                }
            except Exception as exc:  # noqa: BLE001 - every failed request is test evidence
                return {
                    "index": index,
                    "prompt": prompt,
                    "status": 0,
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                    "passed": False,
                    "error": f"{type(exc).__name__}: {exc}",
                }

        started = time.perf_counter()
        rows = await asyncio.gather(*(request(i) for i in range(args.concurrency)))
        elapsed = time.perf_counter() - started

    latencies = [float(row["latency_ms"]) for row in rows]
    passed = sum(bool(row["passed"]) for row in rows)
    report = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "base_url": base_url,
        "summary": {
            "concurrency": args.concurrency,
            "attempted": len(rows),
            "passed": passed,
            "failed": len(rows) - passed,
            "success_rate_percent": round(passed * 100 / len(rows), 2),
            "elapsed_seconds": round(elapsed, 3),
            "throughput_rps": round(len(rows) / elapsed, 2),
            "latency_mean_ms": round(sum(latencies) / len(latencies), 2),
            "latency_p50_ms": round(percentile(latencies, 0.50), 2),
            "latency_p95_ms": round(percentile(latencies, 0.95), 2),
            "latency_max_ms": round(max(latencies), 2),
            "statuses": dict(Counter(str(row["status"]) for row in rows)),
        },
        "results": rows,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False), flush=True)
    print(f"results_file={output.resolve()}", flush=True)
    if passed != len(rows):
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
