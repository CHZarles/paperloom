"""Product HTTP API 客户端：benchmark 通过它建用户、传论文、查用量。"""

from __future__ import annotations

import math
import os
import time
from pathlib import Path

import httpx

from .paperloom_common import (
    PreparationError,
    ScannedPaper,
    UPLOAD_CHUNK_BYTES,
    _list,
    _mapping,
    _read_env,
    _records,
)


class _ProductApi:
    def __init__(self, base_url: str) -> None:
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(600.0, connect=5.0),
            trust_env=False,
        )

    def __enter__(self) -> _ProductApi:
        return self

    def __exit__(self, *_args: object) -> None:
        self.client.close()

    def login(self, username: str, password: str) -> None:
        payload = self._request("POST", "/users/login", json={"username": username, "password": password})
        token = str(_mapping(payload.get("data")).get("token") or "")
        if not token:
            raise PreparationError("login", "TOKEN_MISSING", "login response did not contain a token")
        self.client.headers["Authorization"] = f"Bearer {token}"

    def papers(self) -> list[dict[str, object]]:
        return _records(self._request("GET", "/papers/uploads").get("data"))

    def me(self) -> dict[str, object]:
        return _mapping(self._request("GET", "/users/me").get("data"))

    def users(self) -> list[dict[str, object]]:
        return _records(self._request("GET", "/admin/users").get("data"))

    def user_usage(self, user_id: int) -> dict[str, int]:
        page = 1
        while True:
            data = _mapping(self._request(
                "GET", "/admin/users/list", params={"page": page, "size": 100}
            ).get("data"))
            user = next(
                (row for row in _records(data.get("content")) if str(row.get("userId") or "") == str(user_id)),
                None,
            )
            if user:
                usage = _mapping(user.get("usage"))
                balances = {
                    scope: _mapping(usage.get(scope)).get("remainingTokens")
                    for scope in ("llm", "embedding")
                }
                if all(isinstance(value, int) and not isinstance(value, bool) for value in balances.values()):
                    return {scope: int(value) for scope, value in balances.items()}
                break
            if page >= int(data.get("totalPages") or 0):
                break
            page += 1
        raise PreparationError("run", "BENCHMARK_USER_BALANCE_MISSING", str(user_id))

    def prepare(
        self,
        paper: ScannedPaper,
        poll_timeout_seconds: int,
        poll_interval_seconds: float,
    ) -> dict[str, object]:
        row = self._paper(paper.md5)
        if row and int(row.get("sourceFileSizeBytes") or 0) != paper.size_bytes:
            raise PreparationError("upload", "EXISTING_FILE_MISMATCH", paper.relative_path)

        if not row:
            self._upload_missing_chunks(paper, set())
            self._request("POST", "/papers/upload/merge", json={
                "paperId": paper.md5,
                "paperTitle": paper.path.name,
            })
        elif not _upload_completed(row):
            if str(row.get("uploadStatus") or "").upper() != "MERGING" and row.get("uploadStatus") != 2:
                uploaded = self._uploaded_chunks(paper.md5)
                self._upload_missing_chunks(paper, uploaded)
                self._request("POST", "/papers/upload/merge", json={
                    "paperId": paper.md5,
                    "paperTitle": paper.path.name,
                })
        elif str(row.get("processingStatus") or "").upper() == "FAILED":
            self._request("POST", f"/papers/{paper.md5}/processing/retry")

        ready = self._wait_until_ready(paper, poll_timeout_seconds, poll_interval_seconds)
        if str(ready.get("libraryScope") or "").upper() != "GLOBAL":
            self._request("POST", f"/admin/papers/{paper.md5}/publication")
        return {
            "paper_key": paper.key,
            "paper_id": paper.md5,
            "file": paper.relative_path,
            "source_pdf_sha256": paper.sha256,
            "processing_status": ready.get("processingStatus"),
            "retrieval_indexed_location_count": ready.get("retrievalIndexedLocationCount"),
            "publication_status": "PUBLISHED",
        }

    def _upload_missing_chunks(self, paper: ScannedPaper, uploaded: set[int]) -> None:
        total_chunks = math.ceil(paper.size_bytes / UPLOAD_CHUNK_BYTES)
        with paper.path.open("rb") as handle:
            for chunk_index in range(total_chunks):
                chunk = handle.read(UPLOAD_CHUNK_BYTES)
                if chunk_index in uploaded:
                    continue
                self._request(
                    "POST",
                    "/papers/upload/chunk",
                    data={
                        "paperId": paper.md5,
                        "chunkIndex": str(chunk_index),
                        "totalSize": str(paper.size_bytes),
                        "paperTitle": paper.path.name,
                        "totalChunks": str(total_chunks),
                    },
                    files={"file": (paper.path.name, chunk, "application/pdf")},
                )

    def _uploaded_chunks(self, paper_id: str) -> set[int]:
        payload = self._request("GET", "/papers/upload/status", params={"paperId": paper_id})
        return {
            int(value)
            for value in _list(_mapping(payload.get("data")).get("uploaded"))
            if isinstance(value, int) or str(value).isdigit()
        }

    def _wait_until_ready(
        self,
        paper: ScannedPaper,
        timeout_seconds: int,
        interval_seconds: float,
    ) -> dict[str, object]:
        deadline = time.monotonic() + timeout_seconds
        last: dict[str, object] = {}
        while time.monotonic() < deadline:
            last = self._paper(paper.md5)
            status = str(last.get("processingStatus") or "").upper()
            if status == "FAILED":
                raise PreparationError(
                    "processing",
                    "PAPER_PROCESSING_FAILED",
                    str(last.get("processingErrorMessage") or paper.relative_path),
                )
            if status == "COMPLETED" and bool(last.get("searchable")):
                return last
            time.sleep(interval_seconds)
        raise PreparationError(
            "processing",
            "PAPER_PROCESSING_TIMEOUT",
            f"paper={paper.relative_path}, status={last.get('processingStatus')}, timeout={timeout_seconds}s",
        )

    def _paper(self, paper_id: str) -> dict[str, object]:
        payload = self._request("GET", "/papers/uploads")
        return next((row for row in _records(payload.get("data")) if row.get("paperId") == paper_id), {})

    def _request(self, method: str, path: str, **kwargs: object) -> dict[str, object]:
        try:
            response = self.client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            raise PreparationError("http", "PRODUCT_API_UNAVAILABLE", str(error)) from error
        try:
            payload = response.json()
        except ValueError:
            payload = {}
        if response.is_error:
            detail = str(_mapping(payload).get("message") or response.text[:500] or response.reason_phrase)
            raise PreparationError("http", f"HTTP_{response.status_code}", f"{path}: {detail}")
        if not isinstance(payload, dict):
            raise PreparationError("http", "PRODUCT_RESPONSE_INVALID", path)
        return payload


def _upload_completed(row: dict[str, object]) -> bool:
    return row.get("uploadStatus") in {1, "COMPLETED"}


def _credentials(env_path: str | Path) -> tuple[str, str]:
    env = _read_env(env_path)
    username = (os.getenv("PAPERLOOM_BENCHMARK_USERNAME") or env.get("ADMIN_BOOTSTRAP_USERNAME") or "admin").strip()
    password = (
        os.getenv("PAPERLOOM_BENCHMARK_PASSWORD")
        or env.get("PAPERLOOM_SMOKE_PASSWORD")
        or env.get("ADMIN_BOOTSTRAP_PASSWORD")
        or ""
    ).strip()
    if not password:
        raise PreparationError("login", "ADMIN_PASSWORD_MISSING", "set PAPERLOOM_BENCHMARK_PASSWORD")
    return username, password
