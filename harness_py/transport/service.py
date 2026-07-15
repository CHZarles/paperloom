from __future__ import annotations

import json
import os
import queue
import threading
import time
import uvicorn
from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse, StreamingResponse
from starlette.routing import Route

from ..core.models import JsonMap, as_list, child_map
from ..corpus.gateway import JavaCorpusGateway
from ..corpus.product_db_dataset import summarize_product_corpus
from ..orchestration.conversation import ConversationState
from ..orchestration.live_chat import LiveResearchChatHarness
from ..orchestration.runtime import build_harness_runtime
from .provider_config import EnvProviderConfigStore


class ResearchHarnessService:
    """Internal HTTP-facing adapter around the accepted live harness runtime."""

    def __init__(
        self,
        *,
        max_completion_tokens: int = 3000,
        corpus_limit: int = 1000,
        provider=None,
        harness=None,
        corpus_store=None,
        corpus_gateway=None,
    ):
        self.provider = provider or EnvProviderConfigStore().load_active_provider("llm")
        self.runtime_name = "agents_sdk"
        self.harness = harness or LiveResearchChatHarness(
            build_harness_runtime(
                self.provider,
                max_completion_tokens=max_completion_tokens,
            ),
        )
        # Product runtime uses the Java Corpus API. The direct MySQL store remains an explicit
        # fixture/CLI adapter and is never selected as a silent production fallback.
        self.corpus_store = corpus_store
        self.corpus_gateway = corpus_gateway or (None if corpus_store is not None else JavaCorpusGateway())
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

        corpus_reader = None
        if self.corpus_gateway is not None:
            raw_user_id = request.get("user_id")
            if raw_user_id in (None, ""):
                raise ValueError("user_id is required for the Java Corpus API")
            corpus_reader = self.corpus_gateway.reader(
                request_id=str(request.get("request_id") or ""),
                conversation_id=conversation_id,
                user_id=int(raw_user_id),
                scope_paper_ids=paper_ids,
            )
            dataset = corpus_reader.load_metadata_dataset()
            missing = sorted(set(paper_ids) - set(dataset.paper_records_by_id))
            if missing:
                raise ValueError(f"Java Corpus API rejected unavailable scope papers: {missing}")
        else:
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
            corpus_reader=corpus_reader,
        )
        return _turn_response(request, run, next_state, dataset)


def serve(
    *,
    host: str = "127.0.0.1",
    port: int = 8091,
    internal_token: str = "",
    max_completion_tokens: int = 3000,
    corpus_limit: int = 1000,
) -> None:
    service = ResearchHarnessService(
        max_completion_tokens=max_completion_tokens,
        corpus_limit=corpus_limit,
    )
    token = internal_token or os.getenv("RESEARCH_HARNESS_INTERNAL_TOKEN", "")
    app = _service_app(service, token)
    print(json.dumps({
        "status": "ready",
        "host": host,
        "port": port,
        "provider": service.provider.public_diagnostics(),
        "runtime": service.runtime_name,
        "transport": "ndjson-stream",
    }, indent=2, sort_keys=True))
    uvicorn.run(app, host=host, port=port, log_level="info")


def _service_app(service: ResearchHarnessService, token: str) -> Starlette:
    def authorized(request: Request) -> bool:
        return not token or request.headers.get("Authorization") == f"Bearer {token}"

    async def health(_request: Request) -> JSONResponse:
        return JSONResponse({
            "status": "ok",
            "harness": service.runtime_name,
            "provider": service.provider.public_diagnostics(),
            "transport": "ndjson-stream",
        })

    async def turn(request: Request) -> JSONResponse:
        if not authorized(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        try:
            payload = await _request_payload(request)
            response = await run_in_threadpool(service.run_turn, payload)
            return JSONResponse(response)
        except (ValueError, json.JSONDecodeError) as error:
            return JSONResponse({"error": "invalid_request", "message": str(error)}, status_code=400)
        except Exception as error:
            return JSONResponse({
                "error": "harness_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, status_code=500)

    async def stream(request: Request) -> JSONResponse | StreamingResponse:
        if not authorized(request):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        try:
            payload = await _request_payload(request)
        except (ValueError, json.JSONDecodeError) as error:
            return JSONResponse({"error": "invalid_request", "message": str(error)}, status_code=400)
        return StreamingResponse(
            _stream_job(service, payload),
            media_type="application/x-ndjson",
            headers={"Cache-Control": "no-cache"},
        )

    return Starlette(routes=[
        Route("/health", health, methods=["GET"]),
        Route("/v1/research/turn", turn, methods=["POST"]),
        Route("/v1/research/stream", stream, methods=["POST"]),
    ])


async def _request_payload(request: Request) -> JsonMap:
    payload = await request.json()
    if not isinstance(payload, dict):
        raise ValueError("request body must be a JSON object")
    return payload


def _stream_job(service: ResearchHarnessService, payload: JsonMap):
    events: queue.Queue[JsonMap | None] = queue.Queue()
    disconnected = threading.Event()
    generation_id = str(payload.get("request_id") or "").strip()
    sequence = 0

    def emit(event: JsonMap) -> None:
        nonlocal sequence
        sequence += 1
        events.put({
            "generationId": generation_id,
            "sequence": sequence,
            "timestamp": int(time.time() * 1000),
            **event,
        })

    def run() -> None:
        try:
            emit({"type": "job_started"})
            response = service.run_job(payload, emit, disconnected.is_set)
            emit({"type": "answer_completed"})
            events.put({"type": "result", "payload": response})
        except Exception as error:
            emit({
                "type": "job_failed",
                "status": "failed",
                "errorType": type(error).__name__,
                "message": str(error),
            })
            events.put({
                "type": "error",
                "errorType": type(error).__name__,
                "message": str(error),
            })
        finally:
            events.put(None)

    threading.Thread(target=run, name=f"research-{generation_id or 'request'}", daemon=True).start()
    try:
        while True:
            event = events.get()
            if event is None:
                return
            yield json.dumps(event, ensure_ascii=False) + "\n"
    finally:
        disconnected.set()


def _conversation_state(request: JsonMap, conversation_id: str, paper_ids: list[str]) -> ConversationState:
    history = [
        {
            "role": str(item.get("role") or ""),
            "content": str(item.get("content") or ""),
        }
        for raw in as_list(request.get("history"))
        if (item := child_map(raw)).get("role") in {"user", "assistant"}
        and str(item.get("content") or "").strip()
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
