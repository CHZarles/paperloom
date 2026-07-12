from __future__ import annotations

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .conversation import ConversationState
from .live_chat import LiveResearchChatHarness
from .models import JsonMap, as_list, child_map
from .product_db_dataset import DockerMySqlProductCorpusStore, summarize_product_corpus
from .provider_config import EnvProviderConfigStore
from .runtime import build_harness_runtime


class ResearchHarnessService:
    """Internal HTTP-facing adapter around the accepted live harness runtime."""

    def __init__(
        self,
        *,
        max_completion_tokens: int = 3000,
        corpus_limit: int = 1000,
        runtime_name: str = "",
    ):
        self.provider = EnvProviderConfigStore().load_active_provider("llm")
        self.runtime_name = runtime_name or os.getenv("RESEARCH_HARNESS_RUNTIME", "agents_sdk")
        self.harness = LiveResearchChatHarness(
            build_harness_runtime(
                self.provider,
                self.runtime_name,
                max_completion_tokens=max_completion_tokens,
            ),
        )
        self.corpus_store = DockerMySqlProductCorpusStore()
        self.corpus_limit = max(1, corpus_limit)

    def run_turn(self, request: JsonMap) -> JsonMap:
        return self.run_job(request, lambda _event: None, lambda: False)

    def run_job(
        self,
        request: JsonMap,
        progress_listener,
        should_cancel,
    ) -> JsonMap:
        user_message = str(request.get("user_message") or "").strip()
        if not user_message:
            raise ValueError("user_message is required")
        conversation_id = str(request.get("conversation_id") or "").strip()
        if not conversation_id:
            raise ValueError("conversation_id is required")

        scope = child_map(request.get("scope"))
        paper_ids = _strings(scope.get("paper_ids"))
        if not paper_ids:
            raise ValueError("scope.paper_ids must contain the papers authorized by Java")

        dataset = self.corpus_store.load_dataset(
            paper_ids=paper_ids,
            limit=max(len(paper_ids), self.corpus_limit),
        )
        state = _conversation_state(request, conversation_id, paper_ids)
        run, next_state = self.harness.run_turn(
            dataset,
            state,
            user_message,
            progress_listener=progress_listener,
            should_cancel=should_cancel,
        )
        return _turn_response(request, run, next_state, dataset)


def serve(
    *,
    host: str = "127.0.0.1",
    port: int = 8091,
    internal_token: str = "",
    max_completion_tokens: int = 3000,
    corpus_limit: int = 1000,
    runtime_name: str = "",
) -> None:
    service = ResearchHarnessService(
        max_completion_tokens=max_completion_tokens,
        corpus_limit=corpus_limit,
        runtime_name=runtime_name,
    )
    token = internal_token or os.getenv("RESEARCH_HARNESS_INTERNAL_TOKEN", "")

    class Handler(BaseHTTPRequestHandler):
        server_version = "PaiSmartResearchHarness/1"

        def do_GET(self) -> None:
            if self.path != "/health":
                self._json(404, {"error": "not_found"})
                return
            self._json(200, {
                "status": "ok",
                "harness": service.runtime_name,
                "provider": service.provider.public_diagnostics(),
                "transport": "ndjson-stream",
            })

        def do_POST(self) -> None:
            if self.path not in {"/v1/research/turn", "/v1/research/stream"}:
                self._json(404, {"error": "not_found"})
                return
            if token and self.headers.get("Authorization") != f"Bearer {token}":
                self._json(401, {"error": "unauthorized"})
                return
            try:
                payload = self._request_payload()
                if self.path == "/v1/research/stream":
                    self._stream_turn(payload)
                    return
                self._json(200, service.run_turn(payload))
            except (ValueError, json.JSONDecodeError) as error:
                self._json(400, {"error": "invalid_request", "message": str(error)})
            except Exception as error:
                self._json(500, {
                    "error": "harness_failed",
                    "error_type": type(error).__name__,
                    "message": str(error),
                })

        def log_message(self, format: str, *args: Any) -> None:
            return

        def _request_payload(self) -> JsonMap:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            return payload

        def _stream_turn(self, payload: JsonMap) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            generation_id = str(payload.get("request_id") or "").strip()
            sequence = 0
            disconnected = False

            def emit(event: JsonMap) -> None:
                nonlocal disconnected, sequence
                sequence += 1
                try:
                    self._ndjson({
                        "generationId": generation_id,
                        "sequence": sequence,
                        "timestamp": int(time.time() * 1000),
                        **event,
                    })
                except (BrokenPipeError, ConnectionResetError):
                    disconnected = True
                    raise

            try:
                emit({"type": "job_started"})
                response = service.run_job(payload, emit, lambda: disconnected)
                emit({"type": "answer_completed"})
                self._ndjson({"type": "result", "payload": response})
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception as error:
                try:
                    emit({
                        "type": "job_failed",
                        "status": "failed",
                        "errorType": type(error).__name__,
                        "message": str(error),
                    })
                    self._ndjson({
                        "type": "error",
                        "errorType": type(error).__name__,
                        "message": str(error),
                    })
                except (BrokenPipeError, ConnectionResetError):
                    return

        def _ndjson(self, payload: JsonMap) -> None:
            self.wfile.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
            self.wfile.flush()

        def _json(self, status: int, payload: JsonMap) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer((host, port), Handler)
    print(json.dumps({
        "status": "ready",
        "host": host,
        "port": port,
        "provider": service.provider.public_diagnostics(),
        "runtime": service.runtime_name,
        "transport": "ndjson-stream",
    }, indent=2, sort_keys=True))
    server.serve_forever()


def _conversation_state(request: JsonMap, conversation_id: str, paper_ids: list[str]) -> ConversationState:
    history = [
        {
            "role": str(child_map(item).get("role") or ""),
            "content": str(child_map(item).get("content") or ""),
        }
        for item in as_list(request.get("history"))
        if str(child_map(item).get("role") or "") in {"user", "assistant"}
        and str(child_map(item).get("content") or "").strip()
    ]
    memory = child_map(request.get("research_memory"))
    previous_evidence = {
        str(item.get("evidence_id")): item
        for item in (child_map(raw) for raw in as_list(memory.get("previous_evidence")))
        if item.get("evidence_id")
    }
    selected_evidence_ids = _strings(memory.get("selected_evidence_ids"))
    if not selected_evidence_ids:
        selected_evidence_ids = list(previous_evidence)
    return ConversationState.from_dict({
        "conversation_id": conversation_id,
        "turn_index": len([item for item in history if item["role"] == "user"]),
        "scope_paper_ids": paper_ids,
        "selected_paper_ids": _strings(memory.get("selected_paper_ids")),
        "selected_evidence_ids": selected_evidence_ids,
        "message_history": history,
        "evidence_items_by_id": previous_evidence,
    })


def _turn_response(
    request: JsonMap,
    run: JsonMap,
    state: ConversationState,
    dataset,
) -> JsonMap:
    answer = child_map(run.get("research_answer"))
    ledger = child_map(run.get("evidence_ledger"))
    evidence_by_id = {
        str(item.get("evidence_id")): item
        for item in (child_map(raw) for raw in as_list(ledger.get("items")))
        if item.get("evidence_id")
    }
    cited_ids = _strings(answer.get("cited_evidence_ids"))
    citations = [
        {**evidence_by_id[evidence_id], "reference_number": index}
        for index, evidence_id in enumerate(cited_ids, start=1)
        if evidence_id in evidence_by_id
    ]
    diagnostics = child_map(run.get("diagnostics"))
    include_trace = bool(child_map(request.get("options")).get("include_trace", True))
    response = {
        "request_id": request.get("request_id"),
        "conversation_id": state.conversation_id,
        "status": run.get("status"),
        "answer": answer,
        "citations": citations,
        "research_memory": {
            "selected_paper_ids": state.selected_paper_ids,
            "selected_evidence_ids": state.selected_evidence_ids,
            "previous_evidence": [
                state.evidence_items_by_id[evidence_id]
                for evidence_id in state.selected_evidence_ids
                if evidence_id in state.evidence_items_by_id
            ],
        },
        "usage": {
            "prompt_tokens": diagnostics.get("prompt_tokens", 0),
            "completion_tokens": diagnostics.get("completion_tokens", 0),
            "total_tokens": diagnostics.get("total_tokens", 0),
        },
        "corpus": summarize_product_corpus(dataset).to_dict(),
    }
    if include_trace:
        response["trace"] = {
            "skills_used": run.get("skills_used", []),
            "tool_calls": run.get("react_trace", []),
            "paper_candidates": run.get("paper_candidates", []),
            "evidence_ledger": ledger,
            "citation_validation": run.get("citation_validation", {}),
            "finish_reason": diagnostics.get("finish_reason"),
        }
    return response


def _strings(value: object) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in as_list(value):
        text = str(item or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result
