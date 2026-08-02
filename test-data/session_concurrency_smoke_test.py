#!/usr/bin/env python3
"""Live smoke test for multiple concurrent conversations owned by one user."""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from datetime import datetime
from pathlib import Path

import httpx


PROMPTS = (
    "查询我的订单物流进度",
    "如何申请电子发票",
    "商品如何申请退货退款",
)
ORDER_ID = re.compile(r"\bORD-[A-Z0-9][A-Z0-9_-]{2,63}\b", re.IGNORECASE)


def parse_sse(body: str) -> tuple[list[str], list[dict[str, object]]]:
    events: list[str] = []
    payloads: list[dict[str, object]] = []
    for raw_line in body.splitlines():
        line = raw_line.strip()
        if line.startswith("event:"):
            events.append(line[6:].strip())
        elif line.startswith("data:"):
            try:
                payload = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                payloads.append(payload)
    return events, payloads


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://123.56.6.102")
    parser.add_argument(
        "--output",
        default="test-data/session-concurrency-smoke-2026-08-02.json",
    )
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    timeout = httpx.Timeout(90.0, connect=5.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        login = await client.post(
            f"{base_url}/api/auth/login",
            json={"username": "load_user_000001", "password": "password"},
        )
        login.raise_for_status()
        token = str((login.json().get("data") or {}).get("token") or "")
        if not token:
            raise RuntimeError("login token missing")

        started_ns = time.time_ns()

        async def ask(index: int, prompt: str) -> dict[str, object]:
            session_id = f"MULTI-SESSION-{started_ns}-{index}"
            request_id = f"{session_id}-request"
            started = time.perf_counter()
            try:
                response = await client.get(
                    f"{base_url}/api/math/stream/chat",
                    params={
                        "message": prompt,
                        "sessionId": session_id,
                        "requestId": request_id,
                        "showThinking": "false",
                    },
                    headers={
                        "Accept": "text/event-stream",
                        "Authorization": f"Bearer {token}",
                    },
                )
                events, payloads = parse_sse(response.text)
                answer = "".join(
                    str(payload.get("content") or "")
                    for payload in payloads
                    if payload.get("type") == "text"
                )
                order_ids = list(dict.fromkeys(value.upper() for value in ORDER_ID.findall(answer)))
                returned_sessions = {
                    str(payload.get("sessionId"))
                    for payload in payloads
                    if payload.get("sessionId")
                }
                passed = (
                    response.status_code == 200
                    and "init" in events
                    and "done" in events
                    and returned_sessions.issubset({session_id})
                )
                return {
                    "index": index,
                    "prompt": prompt,
                    "session_id": session_id,
                    "request_id": request_id,
                    "status": response.status_code,
                    "events": events,
                    "returned_sessions": sorted(returned_sessions),
                    "order_ids": order_ids,
                    "answer_excerpt": answer[:500],
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                    "passed": passed,
                }
            except Exception as error:  # noqa: BLE001 - preserve live-test evidence
                return {
                    "index": index,
                    "prompt": prompt,
                    "session_id": session_id,
                    "request_id": request_id,
                    "status": 0,
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                    "passed": False,
                    "error": f"{type(error).__name__}: {error}",
                }

        started = time.perf_counter()
        rows = await asyncio.gather(*(ask(i, prompt) for i, prompt in enumerate(PROMPTS)))
        elapsed = time.perf_counter() - started

        detail: dict[str, object] = {"passed": False, "reason": "recent order missing"}
        recent_order_ids = rows[0].get("order_ids") or []
        if recent_order_ids:
            selected_order_id = str(recent_order_ids[0])
            detail_response = await client.get(
                f"{base_url}/api/math/stream/chat",
                params={
                    "message": selected_order_id,
                    "sessionId": rows[0]["session_id"],
                    "requestId": f"{rows[0]['session_id']}-detail-request",
                    "showThinking": "false",
                },
                headers={
                    "Accept": "text/event-stream",
                    "Authorization": f"Bearer {token}",
                },
            )
            detail_events, detail_payloads = parse_sse(detail_response.text)
            detail_answer = "".join(
                str(payload.get("content") or "")
                for payload in detail_payloads
                if payload.get("type") == "text"
            )
            detail_order_ids = list(
                dict.fromkeys(value.upper() for value in ORDER_ID.findall(detail_answer))
            )
            detail_keywords = (
                "待付款", "待发货", "已发货", "物流", "运输中", "已签收",
                "已完成", "退款", "售后", "订单状态", "订单详情", "订单信息",
            )
            unrelated_field_labels = (
                "【订单信息】", "商品类型：", "收货人：", "联系电话：", "收货地址：",
            )
            detail = {
                "selected_order_id": selected_order_id,
                "status": detail_response.status_code,
                "events": detail_events,
                "order_ids": detail_order_ids,
                "answer_excerpt": detail_answer[:800],
                "has_contextual_status": any(keyword in detail_answer for keyword in detail_keywords),
                "customer_service_style": detail_answer.startswith("我帮您看了一下"),
                "omits_unrelated_fields": not any(
                    label in detail_answer for label in unrelated_field_labels
                ),
                "has_next_step": any(
                    phrase in detail_answer
                    for phrase in ("完成付款后", "稍后再查", "预计发货时间", "催件", "取消订单")
                ),
            }
            detail["passed"] = (
                detail_response.status_code == 200
                and "done" in detail_events
                and detail_order_ids == [selected_order_id]
                and bool(detail["has_contextual_status"])
                and bool(detail["customer_service_style"])
                and bool(detail["omits_unrelated_fields"])
                and bool(detail["has_next_step"])
            )

    session_ids = [str(row["session_id"]) for row in rows]
    request_ids = [str(row["request_id"]) for row in rows]
    passed = all(bool(row["passed"]) for row in rows)
    isolation_passed = len(set(session_ids)) == len(rows) and len(set(request_ids)) == len(rows)
    recent_order_choices_available = len(rows[0].get("order_ids") or []) >= 2
    detail_suggestions_available = bool(detail.get("passed"))
    report = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "base_url": base_url,
        "summary": {
            "passed": (
                passed
                and isolation_passed
                and recent_order_choices_available
                and detail_suggestions_available
            ),
            "concurrency": len(rows),
            "completed": sum(bool(row["passed"]) for row in rows),
            "session_request_isolation_passed": isolation_passed,
            "recent_order_choices_available": recent_order_choices_available,
            "detail_suggestions_available": detail_suggestions_available,
            "elapsed_seconds": round(elapsed, 3),
        },
        "results": rows,
        "selected_order_detail": detail,
    }
    output = Path(args.output)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False))
    print(f"results_file={output.resolve()}")
    if not report["summary"]["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
