#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import json
import math
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx

from harness_py.evaluation.paperloom31 import _credentials


CHUNK_BYTES = 5 * 1024 * 1024


def percentile(values: list[float], value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * value / 100
    lower, upper = math.floor(position), math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def stats(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "mean_ms": round(sum(values) / len(values), 3) if values else None,
        "p50_ms": round(percentile(values, 50), 3) if values else None,
        "p95_ms": round(percentile(values, 95), 3) if values else None,
        "max_ms": round(max(values), 3) if values else None,
    }


def kafka_partition(key: bytes, partitions: int) -> int:
    length = len(key)
    value = 0x9747B28C ^ length
    for offset in range(0, length - length % 4, 4):
        item = int.from_bytes(key[offset:offset + 4], "little")
        item = item * 0x5BD1E995 & 0xFFFFFFFF
        item ^= item >> 24
        item = item * 0x5BD1E995 & 0xFFFFFFFF
        value = value * 0x5BD1E995 & 0xFFFFFFFF
        value ^= item
    tail = key[length - length % 4:]
    for index, byte in enumerate(tail):
        value ^= byte << (8 * index)
    if tail:
        value = value * 0x5BD1E995 & 0xFFFFFFFF
    value ^= value >> 13
    value = value * 0x5BD1E995 & 0xFFFFFFFF
    value ^= value >> 15
    return (value & 0x7FFFFFFF) % partitions


def variant(
    source: bytes,
    run_id: str,
    concurrency: int,
    repetition: int,
    task: int,
    partitions: int,
) -> tuple[bytes, int]:
    nonce = f"paperloom-concurrent-parse:{run_id}:{concurrency}:{repetition}:{task}:{uuid4().hex}"
    content = source + f"\n% {nonce}\n".encode()
    paper_id = hashlib.md5(content, usedforsecurity=False).hexdigest()
    return content, kafka_partition(paper_id.encode(), partitions)


async def request(client: httpx.AsyncClient, method: str, path: str, **kwargs: object) -> dict[str, object]:
    attempts = 3 if method == "GET" else 1
    for attempt in range(attempts):
        try:
            response = await client.request(method, path, **kwargs)
            break
        except httpx.TransportError:
            if attempt + 1 == attempts:
                raise
            await asyncio.sleep(0.25 * (attempt + 1))
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    if response.is_error:
        detail = payload.get("message") if isinstance(payload, dict) else response.text[:500]
        raise RuntimeError(f"{method} {path}: HTTP {response.status_code}: {detail}")
    if not isinstance(payload, dict):
        raise RuntimeError(f"{method} {path}: response is not an object")
    return payload


async def upload(client: httpx.AsyncClient, paper_id: str, filename: str, content: bytes) -> None:
    total_chunks = math.ceil(len(content) / CHUNK_BYTES)
    for index in range(total_chunks):
        chunk = content[index * CHUNK_BYTES:(index + 1) * CHUNK_BYTES]
        await request(
            client,
            "POST",
            "/papers/upload/chunk",
            data={
                "paperId": paper_id,
                "chunkIndex": str(index),
                "totalSize": str(len(content)),
                "paperTitle": filename,
                "totalChunks": str(total_chunks),
            },
            files={"file": (filename, chunk, "application/pdf")},
        )


async def prepare_task(
    client: httpx.AsyncClient,
    source: bytes,
    source_name: str,
    run_id: str,
    concurrency: int,
    repetition: int,
    task: int,
    kafka_partitions: int,
) -> dict[str, object]:
    content, partition = variant(
        source, run_id, concurrency, repetition, task, kafka_partitions,
    )
    paper_id = hashlib.md5(content, usedforsecurity=False).hexdigest()
    filename = f"concurrent-{concurrency}-r{repetition}-t{task}-{source_name}"
    row: dict[str, object] = {
        "concurrency": concurrency,
        "repetition": repetition,
        "task": task,
        "paper_id": paper_id,
        "filename": filename,
        "source_size_bytes": len(source),
        "variant_size_bytes": len(content),
        "kafka_partition": partition,
        "status": "UPLOADING",
        "success": False,
        "error": None,
    }
    try:
        await upload(client, paper_id, filename, content)
        row["status"] = "UPLOADED"
    except Exception as error:
        row["status"] = "UPLOAD_FAILED"
        row["error"] = str(error)
    return row


async def merge(client: httpx.AsyncClient, row: dict[str, object]) -> None:
    if row["status"] != "UPLOADED":
        return
    started_ns = time.perf_counter_ns()
    row["merge_started_ns"] = started_ns
    row["merge_started_at"] = datetime.now(UTC).isoformat()
    try:
        await request(client, "POST", "/papers/upload/merge", json={
            "paperId": row["paper_id"],
            "paperTitle": row["filename"],
        })
        accepted_ns = time.perf_counter_ns()
        row["merge_accepted_ms"] = round((accepted_ns - started_ns) / 1_000_000, 3)
        row["status"] = "PROCESSING"
    except Exception as error:
        row["status"] = "MERGE_FAILED"
        row["error"] = str(error)


async def wait_for_batch(
    client: httpx.AsyncClient,
    rows: list[dict[str, object]],
    timeout_seconds: float,
    poll_interval_seconds: float,
    failure_grace_seconds: float,
) -> None:
    pending = {str(row["paper_id"]): row for row in rows if row["status"] == "PROCESSING"}
    failed_since_ns: dict[str, int] = {}
    deadline = time.monotonic() + timeout_seconds
    while pending and time.monotonic() < deadline:
        papers = (await request(client, "GET", "/papers/uploads")).get("data") or []
        by_id = {
            str(paper.get("paperId")): paper
            for paper in papers
            if isinstance(paper, dict) and paper.get("paperId") in pending
        }
        observed_ns = time.perf_counter_ns()
        for paper_id, row in list(pending.items()):
            paper = by_id.get(paper_id)
            if not paper:
                continue
            status = str(paper.get("processingStatus") or "").upper()
            row["last_processing_status"] = status
            row["retrieval_indexed_location_count"] = paper.get("retrievalIndexedLocationCount")
            if status == "COMPLETED" and bool(paper.get("searchable")):
                row["status"] = "COMPLETED"
                row["success"] = True
            elif status == "FAILED":
                failed_since_ns.setdefault(paper_id, observed_ns)
                if (observed_ns - failed_since_ns[paper_id]) / 1_000_000_000 < failure_grace_seconds:
                    continue
                row["status"] = "FAILED"
                row["error"] = paper.get("processingErrorMessage")
            else:
                failed_since_ns.pop(paper_id, None)
                continue
            row["latency_ms"] = round(
                (observed_ns - int(row["merge_started_ns"])) / 1_000_000,
                3,
            )
            row["completed_at"] = datetime.now(UTC).isoformat()
            del pending[paper_id]
        if pending:
            await asyncio.sleep(poll_interval_seconds)
    for row in pending.values():
        row["status"] = "TIMEOUT"
        row["error"] = f"not terminal after {timeout_seconds:g}s"


async def cleanup(client: httpx.AsyncClient, rows: list[dict[str, object]]) -> None:
    async def delete(row: dict[str, object]) -> None:
        if row["status"] not in {"COMPLETED", "FAILED", "UPLOAD_FAILED", "MERGE_FAILED"}:
            return
        paper_id = str(row["paper_id"])
        try:
            await client.delete(f"/admin/papers/{paper_id}/publication")
            response = await client.delete(f"/papers/{paper_id}")
            row["cleanup_status"] = "DELETED" if response.is_success else f"HTTP_{response.status_code}"
        except Exception as error:
            row["cleanup_status"] = "FAILED"
            row["cleanup_error"] = str(error)

    await asyncio.gather(*(delete(row) for row in rows))


def summarize(rows: list[dict[str, object]], levels: list[int]) -> list[dict[str, object]]:
    result = []
    for level in levels:
        selected = [row for row in rows if row["concurrency"] == level]
        latencies = [float(row["latency_ms"]) for row in selected if row.get("latency_ms") is not None]
        successes = sum(bool(row["success"]) for row in selected)
        distinct_partitions = [
            len({int(row["kafka_partition"]) for row in selected if row["repetition"] == repetition})
            for repetition in sorted({int(row["repetition"]) for row in selected})
        ]
        result.append({
            "concurrency": level,
            "sample_count": len(selected),
            "success_count": successes,
            "success_rate": successes / len(selected) if selected else None,
            "latency_ms": stats(latencies),
            "observed_distinct_partitions": {
                "min": min(distinct_partitions) if distinct_partitions else None,
                "mean": round(sum(distinct_partitions) / len(distinct_partitions), 3)
                if distinct_partitions else None,
                "max": max(distinct_partitions) if distinct_partitions else None,
            },
        })
    return result


async def run(args: argparse.Namespace) -> dict[str, object]:
    source = args.source.read_bytes()
    if not source.startswith(b"%PDF-"):
        raise ValueError(f"not a PDF: {args.source}")
    username, password = _credentials(args.env_file)
    started_at = datetime.now(UTC)
    run_id = started_at.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    rows: list[dict[str, object]] = []
    limits = httpx.Limits(max_connections=max(args.levels) + 5)
    timeout = httpx.Timeout(args.http_timeout_seconds, connect=10)
    async with httpx.AsyncClient(
        base_url=args.api_base_url.rstrip("/"), timeout=timeout, limits=limits, trust_env=False,
    ) as client:
        login = await request(client, "POST", "/users/login", json={"username": username, "password": password})
        token = str((login.get("data") or {}).get("token") or "")
        if not token:
            raise RuntimeError("login response did not contain a token")
        client.headers["Authorization"] = f"Bearer {token}"

        for level in args.levels:
            for repetition in range(1, args.repetitions + 1):
                batch = await asyncio.gather(*(
                    prepare_task(
                        client,
                        source,
                        args.source.name,
                        run_id,
                        level,
                        repetition,
                        task,
                        args.kafka_partitions,
                    )
                    for task in range(1, level + 1)
                ))
                rows.extend(batch)
                await asyncio.gather(*(merge(client, row) for row in batch))
                await wait_for_batch(
                    client,
                    batch,
                    args.timeout_seconds,
                    args.poll_interval_seconds,
                    args.failure_grace_seconds,
                )
                args.out.parent.mkdir(parents=True, exist_ok=True)
                args.out.with_name(f"{args.out.stem}-partial{args.out.suffix}").write_text(
                    json.dumps({
                        "schema_version": "paperloom-concurrent-parse/partial-v1",
                        "run_id": run_id,
                        "summary": summarize(rows, args.levels),
                        "runs": rows,
                    }, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
                if args.cleanup:
                    await cleanup(client, batch)
                login = await request(
                    client,
                    "POST",
                    "/users/login",
                    json={"username": username, "password": password},
                )
                client.headers["Authorization"] = f"Bearer {(login.get('data') or {}).get('token')}"

    return {
        "schema_version": "paperloom-concurrent-parse/v1",
        "run_id": run_id,
        "started_at": started_at.isoformat(),
        "source": {
            "path": str(args.source),
            "size_bytes": len(source),
            "sha256": hashlib.sha256(source).hexdigest(),
            "variant_method": "unique trailing PDF comment",
        },
        "levels": args.levels,
        "repetitions": args.repetitions,
        "sample_count": len(rows),
        "timing_boundary": "merge request start to first poll observing COMPLETED+searchable or FAILED",
        "poll_interval_seconds": args.poll_interval_seconds,
        "failure_grace_seconds": args.failure_grace_seconds,
        "cleanup": args.cleanup,
        "runtime_topology": {
            "kafka_topic_partitions": args.kafka_partitions,
            "listener_concurrency": args.listener_concurrency,
            "effective_consumer_parallelism_upper_bound": min(args.kafka_partitions, args.listener_concurrency),
            "values_are_operator_supplied": True,
        },
        "summary": summarize(rows, args.levels),
        "runs": rows,
    }


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    fields = [
        "concurrency", "repetition", "task", "paper_id", "filename", "status", "success",
        "kafka_partition", "merge_accepted_ms", "latency_ms", "retrieval_indexed_location_count",
        "error", "cleanup_status",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--levels", type=int, nargs="+", default=[1, 2, 5, 10])
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--api-base-url", default="http://127.0.0.1:8081/api/v1")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--timeout-seconds", type=float, default=3600)
    parser.add_argument("--poll-interval-seconds", type=float, default=2)
    parser.add_argument("--failure-grace-seconds", type=float, default=20)
    parser.add_argument("--http-timeout-seconds", type=float, default=600)
    parser.add_argument("--kafka-partitions", type=int, default=1)
    parser.add_argument("--listener-concurrency", type=int, default=3)
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.repetitions < 1 or any(level < 1 for level in args.levels):
        parser.error("levels and repetitions must be positive")
    result = asyncio.run(run(args))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_csv(args.out.with_suffix(".csv"), result["runs"])
    print(json.dumps({"out": str(args.out), "summary": result["summary"]}, ensure_ascii=False))
    return 0 if all(row["success_rate"] == 1 for row in result["summary"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
