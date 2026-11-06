from __future__ import annotations

# transport/service.py：harness 的 HTTP 入口。
#
# 暴露两个端点：POST /v1/research/turn（一次性返回完整 Run）和 POST /v1/research/stream
# （逐 token 推送 NDJSON 事件）。认证走 Bearer token 共享密钥；先在内部 token 走通再考虑
# OpenAI 兼容接口。Python 端不接 MySQL / Qdrant，所有数据通过 corpus/ 委派给 Java。
# 启动用 uvicorn（走 pyproject 的 [start] 入口），单进程多线程；每个研究 run 串行处理。

import asyncio
import json
import logging
import os
import queue
import threading
import time
from contextlib import asynccontextmanager
from dataclasses import asdict, dataclass, fields
import httpx
import uvicorn
from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse, StreamingResponse
from starlette.routing import Route

from ..utils.models import JsonMap, as_list, child_map, unique_strings
from ..corpus.gateway import JavaCorpusGateway
from ..corpus.product_db_dataset import summarize_product_corpus
from ..orchestration.conversation import ConversationState
from ..orchestration.live_chat import LiveResearchChatHarness
from ..orchestration.runtime import build_harness_runtime
from ..orchestration.run_control import RunLimits
from ..orchestration.run_control import RunControl
from .provider_config import EnvProviderConfigStore


class InFlightRegistry:
    """进程内可枚举的在途研究回合索引。

    只做运行时记账（停机和观测用），不是权威业务状态：权威仍归 Java / MySQL。登记在开始执行
    前、注销在终态事件发出后。线程安全。
    """

    def __init__(self) -> None:
        self._entries: dict[str, InFlightEntry] = {}
        self._lock = threading.Lock()

    def register(self, *, request_id: str, conversation_id: str, user_id: int) -> None:
        with self._lock:
            self._entries[request_id] = InFlightEntry(
                run_id="",
                conversation_id=conversation_id,
                user_id=user_id,
                started_at=time.monotonic(),
                state="running",
            )

    def set_run_id(self, request_id: str, run_id: str) -> None:
        with self._lock:
            entry = self._entries.get(request_id)
            if entry is not None:
                entry.run_id = run_id

    def set_state(self, request_id: str, state: str) -> None:
        with self._lock:
            entry = self._entries.get(request_id)
            if entry is not None:
                entry.state = state

    def unregister(self, request_id: str) -> None:
        with self._lock:
            self._entries.pop(request_id, None)

    def count(self) -> int:
        with self._lock:
            return len(self._entries)

    def snapshot(self) -> list[JsonMap]:
        with self._lock:
            return [
                {"request_id": request_id, **asdict(entry)}
                for request_id, entry in self._entries.items()
            ]


@dataclass
class InFlightEntry:
    run_id: str
    conversation_id: str
    user_id: int
    started_at: float
    state: str


class ReadinessProbe:
    """就绪探测：回答"该 harness 现在能否接活"。

    - provider 配置可加载（服务构造时已加载，故此处只读其诊断）；
    - 最近一次 Corpus 探测未失败（后台定时刷新，带 TTL 缓存，`/ready` 不同步穿透）。

    内部 token 由 harness 与 Corpus 共用同一环境变量 ``RESEARCH_HARNESS_INTERNAL_TOKEN``，
    单一来源天然一致，故不单独探测。本探针仅作 readiness，不影响 liveness（`/health`
    不因依赖故障而失败）。
    """

    def __init__(
        self,
        service: ResearchHarnessService,
        *,
        corpus_probe=None,
        ttl_seconds: float = 30.0,
    ) -> None:
        self.service = service
        self.ttl_seconds = ttl_seconds
        self._corpus_probe = corpus_probe or _default_corpus_probe
        self._corpus_ok = False
        self._corpus_reason = ""
        self._last_probe_monotonic = 0.0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._run,
            name="readiness-probe",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        while not self._stop.is_set():
            self.refresh_corpus()
            self._stop.wait(self.ttl_seconds)

    def refresh_corpus(self) -> None:
        try:
            self._corpus_probe()
            ok, reason = True, ""
        except Exception as error:
            ok, reason = False, type(error).__name__
        with self._lock:
            self._corpus_ok = ok
            self._corpus_reason = reason
            self._last_probe_monotonic = time.monotonic()

    def ready(self) -> tuple[bool, list[str]]:
        reasons: list[str] = []
        diagnostics = self.service.provider.public_diagnostics() if self.service.provider else {}
        if not diagnostics.get("has_api_key"):
            reasons.append("provider_unconfigured")
        with self._lock:
            corpus_ok = self._corpus_ok
            corpus_reason = self._corpus_reason
        if not corpus_ok:
            reasons.append(f"corpus_unavailable:{corpus_reason or 'unknown'}")
        return (not reasons, reasons)


def _default_corpus_probe() -> None:
    """轻量探测 Java Corpus 可达性：任意 HTTP 响应（含 4xx）都视为可达。"""

    base_url = os.getenv("JAVA_CORPUS_BASE_URL", "http://127.0.0.1:8081").rstrip("/")
    with httpx.Client(timeout=httpx.Timeout(2.0, connect=2.0)) as client:
        client.get(base_url)


class ResearchHarnessService:
    """Internal HTTP-facing adapter around the accepted live harness runtime."""

    def __init__(
        self,
        *,
        provider=None,
        harness=None,
        corpus_gateway=None,
    ):
        self.provider = provider or EnvProviderConfigStore().load_active_provider("llm")
        self.runtime_name = "agents_sdk"
        self.harness = harness or LiveResearchChatHarness(
            build_harness_runtime(self.provider),
        )
        self.corpus_gateway = corpus_gateway or JavaCorpusGateway()
        self.in_flight = InFlightRegistry()
        self.shutting_down = threading.Event()
        self.drain_seconds = _positive_env_float("RESEARCH_HARNESS_DRAIN_SECONDS", 30.0)
        self.readiness = ReadinessProbe(self)

    def run_turn(self, request: JsonMap) -> JsonMap:
        return self.run_job(request, lambda _event: None, lambda: False)

    def run_job(
        self,
        request: JsonMap,
        progress_listener,
        should_cancel,
        *,
        defer_unregister: bool = False,
    ) -> JsonMap:
        user_message = str(request.get("user_message") or "").strip()
        if not user_message:
            raise ValueError("user_message is required")
        conversation_id = str(request.get("conversation_id") or "").strip()
        if not conversation_id:
            raise ValueError("conversation_id is required")

        scope = child_map(request.get("scope"))
        paper_ids = unique_strings(scope.get("paper_ids"))
        if not paper_ids:
            raise ValueError("scope.paper_ids must contain the papers authorized by Java")

        raw_user_id = request.get("user_id")
        if raw_user_id in (None, ""):
            raise ValueError("user_id is required for the Java Corpus API")
        request_id = str(request.get("request_id") or "").strip()
        limits = _run_limits(child_map(request.get("options")))
        control = RunControl(limits, should_cancel=should_cancel)
        corpus_reader = self.corpus_gateway.reader(
            request_id=request_id,
            conversation_id=conversation_id,
            user_id=int(raw_user_id),
            scope_paper_ids=paper_ids,
            cancel_check=should_cancel,
            run_control=control,
        )
        dataset = corpus_reader.load_metadata_dataset()
        state = _conversation_state(request, conversation_id, paper_ids)
        self.in_flight.register(
            request_id=request_id,
            conversation_id=conversation_id,
            user_id=int(raw_user_id),
        )
        try:
            run, next_state = self.harness.run_turn(
                dataset,
                state,
                user_message,
                progress_listener=progress_listener,
                should_cancel=should_cancel,
                corpus_reader=corpus_reader,
                retry_context=child_map(request.get("retry")),
                run_limits=limits,
                run_control=control,
                request_id=request_id,
            )
            self.in_flight.set_run_id(request_id, str(run.get("run_id") or ""))
            return _turn_response(request, run, next_state, dataset)
        finally:
            # 流式路径由调用方在终态事件发出后再注销，保证"注销在终态事件之后"。
            if not defer_unregister:
                self.in_flight.unregister(request_id)


def serve(
    *,
    host: str = "127.0.0.1",
    port: int = 8091,
    internal_token: str = "",
) -> None:
    service = ResearchHarnessService()
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

    def draining_response() -> JSONResponse:
        return JSONResponse(
            {"error": "draining", "message": "the research service is shutting down"},
            status_code=503,
            headers={"Retry-After": str(max(1, int(service.drain_seconds)))},
        )

    async def health(_request: Request) -> JSONResponse:
        return JSONResponse({
            "status": "ok",
            "harness": service.runtime_name,
            "provider": service.provider.public_diagnostics(),
            "transport": "ndjson-stream",
            "in_flight": service.in_flight.count(),
            "eval_capture_failures": getattr(service.harness, "eval_capture_failures", 0),
        })

    async def ready(_request: Request) -> JSONResponse:
        ok, reasons = service.readiness.ready()
        body = {
            "status": "ok" if ok else "not_ready",
            "in_flight": service.in_flight.count(),
            "eval_capture_failures": getattr(service.harness, "eval_capture_failures", 0),
        }
        if not ok:
            body["reason"] = "; ".join(reasons)
            return JSONResponse(body, status_code=503)
        return JSONResponse(body)

    async def turn(request: Request) -> JSONResponse:
        if service.shutting_down.is_set():
            return draining_response()
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
        if service.shutting_down.is_set():
            return draining_response()
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

    @asynccontextmanager
    async def lifespan(_app: Starlette):
        service.readiness.start()
        try:
            yield
        finally:
            service.readiness.stop()
            # 进入停机：标记 draining，等待在途回合在限时内发出终态后退出。
            service.shutting_down.set()
            deadline = time.monotonic() + service.drain_seconds
            while service.in_flight.count() > 0 and time.monotonic() < deadline:
                await asyncio.sleep(0.05)
            remaining = service.in_flight.snapshot()
            if remaining:
                logging.getLogger(__name__).warning(
                    "drain timed out with %d in-flight turn(s): %s",
                    len(remaining),
                    [entry["request_id"] for entry in remaining],
                )

    return Starlette(
        lifespan=lifespan,
        routes=[
            Route("/health", health, methods=["GET"]),
            Route("/ready", ready, methods=["GET"]),
            Route("/v1/research/turn", turn, methods=["POST"]),
            Route("/v1/research/stream", stream, methods=["POST"]),
        ],
    )


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
    started_monotonic = time.monotonic()

    def emit(event: JsonMap) -> None:
        nonlocal sequence
        sequence += 1
        events.put({
            "generationId": generation_id,
            "sequence": sequence,
            "timestamp": int(time.time() * 1000),
            **event,
        })

    def should_cancel() -> bool:
        # 客户端断流，或进程进入优雅停机，都会触发取消。
        return disconnected.is_set() or service.shutting_down.is_set()

    def run() -> None:
        try:
            emit({"type": "job_started"})
            response = service.run_job(payload, emit, should_cancel, defer_unregister=True)
            status = str(response.get("status") or "")
            terminal = terminal_event(response)
            if status == "LIMITED":
                emit({"type": "run_limited", **terminal})
                emit({"type": "job_completed", **terminal})
            elif status == "CANCELLED":
                emit({"type": "job_cancelled", **terminal})
            elif status == "FAILED_TECHNICAL":
                emit({"type": "job_failed", **terminal})
            else:
                emit({"type": "answer_completed"})
                emit({"type": "job_completed", **terminal})
            emit({"type": "result", "payload": response})
        except Exception as error:
            emit(_technical_failure_event(error, started_monotonic))
            events.put({
                "type": "error",
                "errorType": type(error).__name__,
                "message": str(error),
            })
        finally:
            # 终态事件（job_cancelled/job_failed/result 等）已在上方发出，此时才注销。
            service.in_flight.unregister(generation_id)
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
        str(item.get("source_quote_ref") or item.get("evidence_id")): item
        for item in (child_map(raw) for raw in as_list(memory.get("previous_evidence")))
        if item.get("source_quote_ref") or item.get("evidence_id")
    }
    selected_evidence_ids = unique_strings(
        memory.get("selected_source_quote_refs") or memory.get("selected_evidence_ids")
    )
    if not selected_evidence_ids:
        selected_evidence_ids = list(previous_evidence)
    return ConversationState.from_dict({
        "conversation_id": conversation_id,
        "turn_index": len([item for item in history if item["role"] == "user"]),
        "scope_paper_ids": paper_ids,
        "selected_paper_ids": unique_strings(memory.get("selected_paper_ids")),
        "selected_evidence_ids": selected_evidence_ids,
        "message_history": history,
        "evidence_items_by_id": previous_evidence,
    })


def _positive_env_float(name: str, default: float) -> float:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be a positive number") from error
    if value <= 0:
        raise ValueError(f"{name} must be a positive number")
    return value


def _run_limits(options: JsonMap) -> RunLimits:
    raw = child_map(options.get("run_limits"))
    if not raw:
        return RunLimits()
    if raw.get("schema_version") != "paperloom-run-limits/v1":
        raise ValueError("options.run_limits must use paperloom-run-limits/v1")
    names = {item.name for item in fields(RunLimits)}
    missing = sorted(names - set(raw))
    unknown = sorted(set(raw) - names - {"schema_version"})
    if missing or unknown:
        raise ValueError(
            "options.run_limits fields are invalid: "
            + ", ".join([*(f"missing {name}" for name in missing), *(f"unknown {name}" for name in unknown)])
        )
    try:
        return RunLimits(**{name: int(raw[name]) for name in names})
    except (TypeError, ValueError) as error:
        raise ValueError("options.run_limits values must be positive integers") from error


def _turn_response(
    request: JsonMap,
    run: JsonMap,
    state: ConversationState,
    dataset,
) -> JsonMap:
    answer = child_map(run.get("research_answer"))
    ledger = child_map(run.get("evidence_ledger"))
    evidence_by_id = {
        str(item.get("source_quote_ref") or item.get("evidence_id")): item
        for item in (child_map(raw) for raw in as_list(ledger.get("items")))
        if item.get("source_quote_ref") or item.get("evidence_id")
    }
    cited_ids = unique_strings(answer.get("cited_source_quote_refs") or answer.get("cited_evidence_ids"))
    citations = [
        {**evidence_by_id[evidence_id], "reference_number": index}
        for index, evidence_id in enumerate(cited_ids, start=1)
        if evidence_id in evidence_by_id
    ]
    diagnostics = child_map(run.get("diagnostics"))
    include_trace = bool(child_map(request.get("options")).get("include_trace", True))
    control = child_map(run.get("control"))
    control_usage = child_map(control.get("usage"))
    response = {
        "request_id": request.get("request_id"),
        "run_id": run.get("run_id"),
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
            "prompt_tokens": diagnostics.get("prompt_tokens", control_usage.get("prompt_tokens", 0)),
            "completion_tokens": diagnostics.get("completion_tokens", control_usage.get("completion_tokens", 0)),
            "total_tokens": diagnostics.get("total_tokens", control_usage.get("total_tokens", 0)),
        },
        "control": control,
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


def terminal_event(response: JsonMap) -> JsonMap:
    control = child_map(response.get("control"))
    usage = child_map(control.get("usage"))
    return {
        "status": response.get("status"),
        "reasonCode": control.get("reason_code"),
        "usage": usage,
        "elapsedMs": usage.get("elapsed_ms"),
    }


def _technical_failure_event(error: Exception, started_monotonic: float) -> JsonMap:
    elapsed_ms = round(max(0.0, time.monotonic() - started_monotonic) * 1000)
    return {
        "type": "job_failed",
        "status": "FAILED_TECHNICAL",
        "reasonCode": "INTERNAL_UNEXPECTED",
        "usage": {
            "model_calls": 0,
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
            "elapsed_ms": elapsed_ms,
        },
        "elapsedMs": elapsed_ms,
        "errorType": type(error).__name__,
        "message": "The research service stopped unexpectedly.",
    }
