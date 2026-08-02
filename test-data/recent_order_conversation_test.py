#!/usr/bin/env python3
"""Live regression test for recent-order discovery and ordinal follow-up."""

from __future__ import annotations

import argparse
import json
import re
import time
from datetime import datetime
from pathlib import Path

import httpx


ORDER_ID = re.compile(r"ORD-[A-Z0-9][A-Z0-9_-]{2,63}", re.IGNORECASE)


def parse_sse(body: str) -> tuple[list[str], str]:
    events: list[str] = []
    parts: list[str] = []
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
                parts.append(str(payload["content"]))
    return events, "".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://123.56.6.102")
    parser.add_argument(
        "--output",
        default="test-data/recent-order-conversation-results-2026-08-02.json",
    )
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    client = httpx.Client(timeout=httpx.Timeout(60.0, connect=5.0))
    try:
        def login(user_no: int) -> str:
            response = client.post(
                f"{base_url}/api/auth/login",
                json={"username": f"load_user_{user_no:06d}", "password": "password"},
            )
            response.raise_for_status()
            token = str((response.json().get("data") or {}).get("token") or "")
            if not token:
                raise RuntimeError(f"login token missing for user {user_no}")
            return token

        tokens = {user_no: login(user_no) for user_no in (1, 2)}

        def ask(user_no: int, session_id: str, message: str) -> dict[str, object]:
            started = time.perf_counter()
            response = client.get(
                f"{base_url}/api/math/stream/chat",
                params={
                    "message": message,
                    "sessionId": session_id,
                    "showThinking": "false",
                },
                headers={
                    "Accept": "text/event-stream",
                    "Authorization": f"Bearer {tokens[user_no]}",
                },
            )
            events, answer = parse_sse(response.text)
            return {
                "status": response.status_code,
                "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                "events": events,
                "answer": answer,
                "order_ids": list(dict.fromkeys(value.upper() for value in ORDER_ID.findall(answer))),
            }

        user1_session = f"RECENT-ORDER-U1-{time.time_ns()}"
        user2_session = f"RECENT-ORDER-U2-{time.time_ns()}"
        user1_list = ask(1, user1_session, "查询我的订单物流进度")
        user2_list = ask(2, user2_session, "查询我的订单物流进度")

        user1_ids = set(user1_list["order_ids"])
        user2_ids = set(user2_list["order_ids"])
        list_events = {"init", "routed", "text", "done"}
        list_passed = (
            user1_list["status"] == 200
            and user2_list["status"] == 200
            and bool(user1_ids)
            and bool(user2_ids)
            and user1_ids.isdisjoint(user2_ids)
            and "最近的" in str(user1_list["answer"])
            and "第1笔的物流进度" in str(user1_list["answer"])
            and list_events.issubset(set(user1_list["events"]))
        )

        user1_follow_up = ask(1, user1_session, "查询第1笔的物流进度")
        selected_order_id = str(user1_list["order_ids"][0]) if user1_list["order_ids"] else ""
        follow_up_passed = (
            user1_follow_up["status"] == 200
            and selected_order_id
            and selected_order_id in str(user1_follow_up["answer"])
            and list_events.issubset(set(user1_follow_up["events"]))
        )

        direct_session = f"RECENT-ORDER-DIRECT-U1-{time.time_ns()}"
        direct_list = ask(1, direct_session, "查询我的订单物流进度")
        direct_order_id = str(direct_list["order_ids"][0]) if direct_list["order_ids"] else ""
        direct_follow_up = ask(1, direct_session, direct_order_id) if direct_order_id else {
            "status": 0,
            "latency_ms": 0,
            "events": [],
            "answer": "",
            "order_ids": [],
        }
        direct_follow_up_passed = (
            direct_follow_up["status"] == 200
            and bool(direct_order_id)
            and direct_order_id in str(direct_follow_up["answer"])
            and list_events.issubset(set(direct_follow_up["events"]))
        )

        report = {
            "generated_at": datetime.now().astimezone().isoformat(),
            "summary": {
                "passed": bool(list_passed and follow_up_passed and direct_follow_up_passed),
                "recent_list_passed": bool(list_passed),
                "ordinal_follow_up_passed": bool(follow_up_passed),
                "direct_order_number_follow_up_passed": bool(direct_follow_up_passed),
                "cross_account_isolation_passed": user1_ids.isdisjoint(user2_ids),
                "selected_order_id": selected_order_id,
                "direct_selected_order_id": direct_order_id,
            },
            "user1_recent_list": user1_list,
            "user2_recent_list": user2_list,
            "user1_first_order_follow_up": user1_follow_up,
            "user1_direct_order_recent_list": direct_list,
            "user1_direct_order_follow_up": direct_follow_up,
        }
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report["summary"], ensure_ascii=False), flush=True)
        print(f"results_file={output.resolve()}", flush=True)
        if not report["summary"]["passed"]:
            raise SystemExit(1)
    finally:
        client.close()


if __name__ == "__main__":
    main()
