#!/usr/bin/env python3
"""Verify the destructive lexical Qdrant cutover against MySQL canonical state."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


LEXICAL_VECTOR = "lexical_bm25_v1"
DEFAULT_COLLECTION = "paperloom_reading_locations_bm25_v1"


def main() -> int:
    args = _arguments()
    env = _read_env(args.env_file)
    schema = args.mysql_schema or _schema_from_jdbc(env.get("SPRING_DATASOURCE_URL", ""))
    qdrant_url = args.qdrant_base_url.rstrip("/")
    api_key = os.getenv("QDRANT_API_KEY") or env.get("QDRANT_API_KEY", "")

    models = _mysql_rows(
        args.mysql_container,
        schema,
        """
SELECT paper_id,
       model_version,
       retrieval_index_status,
       COALESCE(retrieval_index_contract, ''),
       COALESCE(retrieval_indexed_location_count, 0)
FROM paper_reading_models
WHERE is_current = 1
  AND model_status = 'READING_MODEL_READY'
ORDER BY paper_id
""".strip(),
    )
    control_rows = _mysql_rows(
        args.mysql_container,
        schema,
        """
SELECT control_name,
       full_rebuild_status,
       snapshot_paper_count,
       completed_paper_count,
       failed_paper_count,
       COALESCE(active_index_contract, ''),
       COALESCE(lexical_average_document_length, 0)
FROM paper_retrieval_control
WHERE control_name = 'QDRANT_FULL_REBUILD'
""".strip(),
    )

    failures: list[str] = []
    control = control_rows[0] if len(control_rows) == 1 else []
    if len(control_rows) != 1:
        failures.append(f"expected one retrieval control row, found {len(control_rows)}")
    active_contract = control[5] if control else ""
    if control and control[1] != "SUCCEEDED":
        failures.append(f"full rebuild status is {control[1]}, expected SUCCEEDED")
    if control and control[4] != "0":
        failures.append(f"full rebuild failed paper count is {control[4]}")
    if control and control[2] != control[3]:
        failures.append(
            f"full rebuild progress mismatch: snapshot={control[2]} completed={control[3]}"
        )
    if not active_contract:
        failures.append("active retrieval index contract is blank")
    if control and float(control[6]) <= 0:
        failures.append("lexical average document length is not positive")

    expected_total = 0
    for paper_id, model_version, status, contract, raw_count in models:
        count = int(raw_count)
        expected_total += count
        if status != "READY":
            failures.append(f"{paper_id}/{model_version} status is {status}")
        if contract != active_contract:
            failures.append(f"{paper_id}/{model_version} contract does not match active contract")
        if count <= 0:
            failures.append(f"{paper_id}/{model_version} indexed location count is {count}")

    collection = _request_json(
        "GET",
        f"{qdrant_url}/collections/{args.collection}",
        api_key=api_key,
    ).get("result", {})
    params = collection.get("config", {}).get("params", {})
    dense = params.get("vectors") or {}
    sparse = params.get("sparse_vectors") or {}
    lexical = sparse.get(LEXICAL_VECTOR) or {}
    if dense:
        failures.append("lexical collection contains dense vectors")
    if set(sparse) != {LEXICAL_VECTOR}:
        failures.append(f"unexpected sparse vectors: {sorted(sparse)}")
    if str(lexical.get("modifier") or "").lower() != "idf":
        failures.append("lexical sparse vector does not use modifier=idf")
    if lexical.get("index", {}).get("on_disk") is not True:
        failures.append("lexical sparse index does not use on_disk=true")

    qdrant_total = int(
        _request_json(
            "POST",
            f"{qdrant_url}/collections/{args.collection}/points/count",
            {"exact": True},
            api_key=api_key,
        ).get("result", {}).get("count", -1)
    )
    if qdrant_total != expected_total:
        failures.append(
            f"global point count mismatch: mysql={expected_total} qdrant={qdrant_total}"
        )

    per_model: list[dict[str, Any]] = []
    for paper_id, model_version, _status, _contract, raw_count in models:
        expected = int(raw_count)
        actual = int(
            _request_json(
                "POST",
                f"{qdrant_url}/collections/{args.collection}/points/count",
                {
                    "exact": True,
                    "filter": {
                        "must": [
                            {"key": "paper_id", "match": {"value": paper_id}},
                            {"key": "model_version", "match": {"value": model_version}},
                        ]
                    },
                },
                api_key=api_key,
            ).get("result", {}).get("count", -1)
        )
        per_model.append({
            "paper_id": paper_id,
            "model_version": model_version,
            "mysql_count": expected,
            "qdrant_count": actual,
            "matched": expected == actual,
        })
        if expected != actual:
            failures.append(
                f"point count mismatch for {paper_id}/{model_version}: "
                f"mysql={expected} qdrant={actual}"
            )

    report = {
        "schema_version": "paperloom-lexical-qdrant-cutover-verification/v1",
        "created_at_epoch_ms": int(time.time() * 1000),
        "status": "passed" if not failures else "failed",
        "mysql_schema": schema,
        "mysql_current_ready_model_count": len(models),
        "active_index_contract": active_contract,
        "lexical_average_document_length": float(control[6]) if control else 0,
        "qdrant_collection": args.collection,
        "qdrant_vector": LEXICAL_VECTOR,
        "mysql_expected_point_count": expected_total,
        "qdrant_exact_point_count": qdrant_total,
        "per_model": per_model,
        "failures": failures,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "status": report["status"],
        "out": str(args.out),
        "model_count": len(models),
        "point_count": qdrant_total,
        "failure_count": len(failures),
    }, indent=2, sort_keys=True))
    return 0 if not failures else 1


def _mysql_rows(container: str, schema: str, sql: str) -> list[list[str]]:
    command = [
        "docker",
        "exec",
        container,
        "sh",
        "-lc",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot "$1" -e "$2"',
        "sh",
        schema,
        sql,
    ]
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def _request_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    *,
    api_key: str = "",
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    if api_key:
        headers["api-key"] = api_key
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            value = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed with HTTP {error.code}: {body}") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"{method} {url} returned a non-object response")
    return value


def _schema_from_jdbc(url: str) -> str:
    marker = "jdbc:mysql://"
    if not url.startswith(marker) or "/" not in url[len(marker):]:
        return "paismart"
    return url.split("/", 3)[-1].split("?", 1)[0] or "paismart"


def _read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--mysql-container", default="pai_smart_mysql")
    parser.add_argument("--mysql-schema", default="")
    parser.add_argument("--qdrant-base-url", default="http://127.0.0.1:6333")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--out", type=Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(main())
