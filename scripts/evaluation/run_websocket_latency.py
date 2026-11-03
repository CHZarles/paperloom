#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import json
import math
import time
from pathlib import Path
from uuid import uuid4

import httpx
import websockets

from harness_py.evaluation.paperloom_product_api import _credentials


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summary(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "mean_ms": round(sum(values) / len(values), 3) if values else None,
        "p50_ms": round(percentile(values, 50), 3) if values else None,
        "p95_ms": round(percentile(values, 95), 3) if values else None,
        "max_ms": round(max(values), 3) if values else None,
    }


async def run_once(
    client: httpx.AsyncClient,
    token: str,
    websocket_base_url: str,
    question: str,
    mode: str,
) -> dict[str, object]:
    headers = {"Authorization": f"Bearer {token}"}
    created = (await client.post("users/conversations", headers=headers)).raise_for_status().json()["data"]
    conversation_id = created["conversationId"]
    client_id = f"quant-eval-{uuid4().hex}"
    uri = f"{websocket_base_url.rstrip('/')}/chat/{token}?clientId={client_id}"
    events: list[dict[str, object]] = []
    request_sent_ns: int | None = None
    first_event_ns: int | None = None
    first_content_ns: int | None = None
    completed_ns: int | None = None
    cancel_sent_ns: int | None = None
    generation_id: str | None = None
    terminal_type: str | None = None

    try:
        async with websockets.connect(uri, origin="http://localhost:8080", open_timeout=10) as websocket:
            connection = json.loads(await asyncio.wait_for(websocket.recv(), timeout=10))
            if connection.get("type") != "connection":
                raise RuntimeError(f"unexpected handshake event: {connection}")
            request_sent_ns = time.perf_counter_ns()
            await websocket.send(json.dumps({
                "type": "user_message",
                "conversationId": conversation_id,
                "message": question,
                "referenceFocus": None,
            }, ensure_ascii=False))

            while True:
                payload = json.loads(await asyncio.wait_for(websocket.recv(), timeout=180))
                received_ns = time.perf_counter_ns()
                payload_type = str(payload.get("type") or "")
                events.append({
                    "received_after_request_ms": round((received_ns - request_sent_ns) / 1_000_000, 3),
                    "payload": payload,
                })
                if first_event_ns is None and payload_type in {"start", "research_progress", "chunk"}:
                    first_event_ns = received_ns
                if first_content_ns is None and payload_type == "chunk":
                    first_content_ns = received_ns
                generation_id = generation_id or payload.get("generationId")

                if mode == "cancel" and cancel_sent_ns is None and generation_id and payload_type == "start":
                    command = (await client.get("chat/websocket-token", headers=headers)).raise_for_status().json()["data"]
                    cancel_sent_ns = time.perf_counter_ns()
                    await websocket.send(json.dumps({
                        "type": "stop",
                        "generationId": generation_id,
                        "_internal_cmd_token": command["cmdToken"],
                    }))

                if payload_type in {"completion", "stop"}:
                    completed_ns = received_ns
                    terminal_type = payload_type
                    break
                if payload_type == "error" and payload.get("code"):
                    completed_ns = received_ns
                    terminal_type = "error"
                    break
    finally:
        await client.delete(f"users/conversations/{conversation_id}", headers=headers)

    assert request_sent_ns is not None
    return {
        "conversation_id": conversation_id,
        "generation_id": generation_id,
        "mode": mode,
        "terminal_type": terminal_type,
        "success": terminal_type == ("stop" if mode == "cancel" else "completion"),
        "ttft_ms": round((first_event_ns - request_sent_ns) / 1_000_000, 3) if first_event_ns else None,
        "first_content_ms": round((first_content_ns - request_sent_ns) / 1_000_000, 3) if first_content_ns else None,
        "complete_ms": round((completed_ns - request_sent_ns) / 1_000_000, 3) if completed_ns else None,
        "cancel_ms": round((completed_ns - cancel_sent_ns) / 1_000_000, 3)
        if completed_ns and cancel_sent_ns else None,
        "events": events,
    }


async def run(args: argparse.Namespace) -> dict[str, object]:
    username, password = _credentials(args.env_file)
    async with httpx.AsyncClient(base_url=args.api_base_url.rstrip("/") + "/", timeout=30) as client:
        login = (await client.post("users/login", json={"username": username, "password": password})).raise_for_status().json()
        token = login["data"]["token"]
        rows = [
            await run_once(client, token, args.websocket_base_url, args.question, args.mode)
            for _ in range(args.repetitions)
        ]

    metrics = {
        key: summary([float(row[key]) for row in rows if row.get(key) is not None])
        for key in ("ttft_ms", "first_content_ms", "complete_ms", "cancel_ms")
    }
    return {
        "schema_version": "paperloom-websocket-latency/v1",
        "mode": args.mode,
        "question": args.question,
        "repetitions": args.repetitions,
        "percentile_method": "linear_interpolation",
        "metrics": metrics,
        "success_count": sum(bool(row["success"]) for row in rows),
        "runs": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("complete", "cancel"), default="complete")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--question", default="你好")
    parser.add_argument("--api-base-url", default="http://127.0.0.1:8081/api/v1")
    parser.add_argument("--websocket-base-url", default="ws://127.0.0.1:8081")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    result = asyncio.run(run(args))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "metrics": result["metrics"]}, ensure_ascii=False))
    return 0 if result["success_count"] == args.repetitions else 1


if __name__ == "__main__":
    raise SystemExit(main())
