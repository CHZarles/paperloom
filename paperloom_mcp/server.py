from __future__ import annotations

import contextvars
import json
import os
import uuid

import httpx
import jsonschema
import uvicorn
from mcp import types
from mcp.server.fastmcp.exceptions import ToolError
from mcp.server.lowlevel import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.routing import Route

MAX_REQUEST_BYTES = 1024 * 1024
MAX_RESULT_BYTES = 16 * 1024
_bearer_token: contextvars.ContextVar[str] = contextvars.ContextVar("mcp_bearer_token", default="")
JsonMap = dict[str, object]


class ReadingMcpGateway:
    def __init__(
        self,
        base_url: str | None = None,
        *,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.client = client or httpx.AsyncClient(
            base_url=(base_url or os.getenv(
                "PAPERLOOM_PUBLIC_API_BASE_URL", "http://127.0.0.1:8081"
            )).rstrip("/"),
            timeout=httpx.Timeout(20.0, connect=5.0),
            trust_env=False,
        )

    async def query(self, path: str, payload: JsonMap) -> JsonMap:
        token = _bearer_token.get()
        if not token:
            raise ToolError("UNAUTHORIZED")
        try:
            async with self.client.stream(
                "POST", path, json=payload, headers={
                    "Authorization": f"Bearer {token}",
                    "X-Request-ID": str(uuid.uuid4()),
                },
            ) as response:
                if response.status_code in {401, 403}:
                    raise ToolError("UNAUTHORIZED" if response.status_code == 401 else "FORBIDDEN")
                content_length = response.headers.get("content-length")
                if content_length is not None:
                    try:
                        declared_length = int(content_length)
                    except ValueError as error:
                        raise ToolError("INVALID_READING_RESPONSE") from error
                    if declared_length < 0:
                        raise ToolError("INVALID_READING_RESPONSE")
                    if declared_length > MAX_RESULT_BYTES:
                        raise ToolError("READING_RESULT_TOO_LARGE")
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > MAX_RESULT_BYTES:
                        raise ToolError("READING_RESULT_TOO_LARGE")
        except httpx.HTTPError as error:
            raise ToolError("RETRIEVAL_UNAVAILABLE") from error
        try:
            result = json.loads(raw)
        except ValueError as error:
            raise ToolError("INVALID_READING_RESPONSE") from error
        if response.is_error:
            raise ToolError(_status_error(response.status_code, path))
        if not isinstance(result, dict):
            raise ToolError("INVALID_READING_RESPONSE")
        try:
            jsonschema.validate(result, _RESULT_SCHEMAS[path])
        except (KeyError, jsonschema.ValidationError) as error:
            raise ToolError("INVALID_READING_RESPONSE") from error
        return result


def _status_error(status: int, path: str = "") -> str:
    if status == 400 and path == "/api/v1/arxiv/import":
        return "ARXIV_ARGUMENTS_INVALID"
    return {
        400: "READING_ARGUMENTS_INVALID",
        404: "PAPER_NOT_AVAILABLE",
        409: "READING_MODEL_NOT_READY",
        422: "READING_RESULT_TOO_LARGE",
        429: "READING_QUOTA_EXHAUSTED",
    }.get(status, "RETRIEVAL_UNAVAILABLE")


def _tools() -> list[types.Tool]:
    read_only = types.ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True)
    import_action = types.ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True)
    text = {"type": "string", "maxLength": 4000}
    paper_id = {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"\S"}
    location_refs = {
        "type": "array", "minItems": 1, "maxItems": 20, "uniqueItems": True,
        "items": {"type": "string", "minLength": 1, "maxLength": 96, "pattern": r"\S"},
    }
    return [
        types.Tool(
            name="search_papers",
            description="Search or browse Papers accessible to the authenticated PaperLoom user.",
            annotations=read_only,
            inputSchema=_object_schema({
                "query_text": text,
                "paper_ids": _string_array(100, 64),
                "authors": _string_array(100, 500),
                "venues": _string_array(100, 500),
                "year_from": {"type": "integer", "minimum": 0},
                "year_to": {"type": "integer", "minimum": 0},
                "offset": {"type": "integer", "minimum": 0},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100},
            }),
        ),
        types.Tool(
            name="get_paper",
            description="Get metadata for one accessible Paper.",
            annotations=read_only,
            inputSchema=_object_schema({"paper_id": paper_id}, ["paper_id"]),
        ),
        types.Tool(
            name="get_paper_structure",
            description="Read PAGE or SECTION navigation for one accessible Paper.",
            annotations=read_only,
            inputSchema=_object_schema({
                "paper_id": paper_id,
                "structure_type": {"type": "string", "enum": ["SECTION", "PAGE"]},
                "section_query": text,
                "page_from": {"type": "integer", "minimum": 1},
                "page_to": {"type": "integer", "minimum": 1},
                "offset": {"type": "integer", "minimum": 0},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100},
            }, ["paper_id"]),
        ),
        types.Tool(
            name="search_paper_content",
            description="Search bounded Location previews in one accessible Paper.",
            annotations=read_only,
            inputSchema=_object_schema({
                "paper_id": paper_id,
                "query_text": text,
                "section_query": text,
                "section_refs": _string_array(20, 96),
                "element_types": {
                    "type": "array", "maxItems": 3, "uniqueItems": True,
                    "items": {"type": "string", "enum": ["passage", "table", "figure"]},
                },
                "page_from": {"type": "integer", "minimum": 1},
                "page_to": {"type": "integer", "minimum": 1},
                "top_k": {"type": "integer", "minimum": 1, "maximum": 20},
            }, ["paper_id"]),
        ),
        types.Tool(
            name="read_paper_content",
            description="Read durable canonical content for bounded Locations in one accessible Paper.",
            annotations=read_only,
            inputSchema=_object_schema({
                "paper_id": paper_id,
                "location_refs": location_refs,
            }, ["paper_id", "location_refs"]),
        ),
        types.Tool(
            name="request_arxiv_import",
            description=(
                "Request import of one exact arXiv version after the MCP client has received explicit user "
                "approval. This is an asynchronous write operation; it returns a queue state and does not make "
                "the paper immediately readable."
            ),
            annotations=import_action,
            inputSchema=_object_schema({
                "arxiv_id": {
                    "type": "string",
                    "minLength": 8,
                    "maxLength": 64,
                    "pattern": r"(?:\d{4}\.\d{4,5}|[A-Za-z-]+(?:\.[A-Za-z-]+)?/\d{7})v\d+",
                },
            }, ["arxiv_id"]),
        ),
    ]


def _object_schema(properties: JsonMap, required: list[str] | None = None) -> JsonMap:
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
        "additionalProperties": False,
    }


def _string_array(max_items: int, max_length: int) -> JsonMap:
    return {
        "type": "array",
        "maxItems": max_items,
        "uniqueItems": True,
        "items": {"type": "string", "minLength": 1, "maxLength": max_length, "pattern": r"\S"},
    }


_PAPER_RESULT = _object_schema({
    "paper_id": {"type": "string"},
    "title": {"type": "string"},
    "authors": {"type": "array", "items": {"type": "string"}},
    "year": {"type": "integer"},
    "venue": {"type": "string"},
    "doi": {"type": "string"},
    "arxiv_id": {"type": "string"},
    "filename": {"type": "string"},
    "preview": {"type": "string"},
}, ["paper_id"])
_STRUCTURE_ITEM = _object_schema({
    "paper_id": {"type": "string"},
    "title": {"type": "string"},
    "location_ref": {"type": "string"},
    "structure_type": {"type": "string", "enum": ["SECTION", "PAGE"]},
    "section_title": {"type": "string"},
    "section_level": {"type": "integer"},
    "section_path": {"type": "string"},
    "display_order": {"type": "integer"},
    "page_from": {"type": "integer"},
    "page_to": {"type": "integer"},
}, ["paper_id", "location_ref", "structure_type"])
_LOCATION_ITEM = _object_schema({
    "paper_id": {"type": "string"},
    "title": {"type": "string"},
    "location_ref": {"type": "string"},
    "section": {"type": "string"},
    "page": {"type": "integer"},
    "element_type": {"type": "string"},
    "preview": {"type": "string"},
}, ["paper_id", "location_ref"])
_CONTENT_ITEM = _object_schema({
    "paper_id": {"type": "string"},
    "location_ref": {"type": "string"},
    "section": {"type": "string"},
    "page": {"type": "integer"},
    "page_end": {"type": "integer"},
    "content_kind": {"type": "string"},
    "content": {"type": "string"},
}, ["paper_id", "location_ref", "content_kind", "content"])
_PAGE_FIELDS = {
    "matched_count": {"type": "integer"},
    "returned_count": {"type": "integer"},
    "coverage": {"type": "string", "enum": ["complete", "truncated"]},
    "next_offset": {"type": "integer"},
}
_RESULT_SCHEMAS = {
    "/api/v1/reading/papers/search": _object_schema({
        "query_text": {"type": "string"},
        "papers": {"type": "array", "items": _PAPER_RESULT},
        **_PAGE_FIELDS,
    }, ["query_text", "papers", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/papers/get": _PAPER_RESULT,
    "/api/v1/reading/structure": _object_schema({
        "structure_type": {"type": "string", "enum": ["SECTION", "PAGE"]},
        "items": {"type": "array", "items": _STRUCTURE_ITEM},
        **_PAGE_FIELDS,
    }, ["structure_type", "items", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/content/search": _object_schema({
        "query_text": {"type": "string"},
        "locations": {"type": "array", "items": _LOCATION_ITEM},
        **_PAGE_FIELDS,
    }, ["query_text", "locations", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/content/read": _object_schema({
        "items": {"type": "array", "items": _CONTENT_ITEM},
        "missing_location_refs": {"type": "array", "items": {"type": "string"}},
    }, ["items", "missing_location_refs"]),
    "/api/v1/arxiv/import": _object_schema({
        "arxiv_id": {"type": "string"},
        "status": {"type": "string", "enum": ["queued", "processing", "ready", "failed"]},
        "paper_id": {"type": "string"},
    }, ["arxiv_id", "status"]),
}


def _tool_error(code: str) -> types.CallToolResult:
    return types.CallToolResult(
        content=[types.TextContent(type="text", text=code)],
        isError=True,
    )


def _tool_result(result: JsonMap):
    return (
        [types.TextContent(type="text", text=json.dumps(
            result, ensure_ascii=False, separators=(",", ":")))],
        result,
    )


def create_mcp_server(
    gateway: ReadingMcpGateway,
) -> Server:
    server = Server(
        "paperloom-reading",
        version="1.0.0",
        instructions=(
            "Stateless queries over the authenticated user's PaperLoom library and explicitly user-approved "
            "asynchronous arXiv imports."
        ),
    )
    tools = _tools()

    @server.list_tools()
    async def list_tools() -> list[types.Tool]:
        return tools

    @server.call_tool(validate_input=False)
    async def call_tool(name: str, arguments: dict | None):
        values = arguments or {}
        tool = next((item for item in tools if item.name == name), None)
        if tool is None:
            return _tool_error("UNKNOWN_TOOL")
        try:
            jsonschema.validate(values, tool.inputSchema)
        except jsonschema.ValidationError:
            return _tool_error("READING_ARGUMENTS_INVALID")
        if name == "search_papers":
            return _tool_result(await gateway.query("/api/v1/reading/papers/search", {
                "query_text": values.get("query_text", ""),
                "paper_ids": values.get("paper_ids"),
                "authors": values.get("authors"),
                "venues": values.get("venues"),
                "year_from": values.get("year_from"),
                "year_to": values.get("year_to"),
                "offset": values.get("offset", 0),
                "limit": values.get("limit", 20),
            }))
        if name == "get_paper":
            return _tool_result(await gateway.query("/api/v1/reading/papers/get", values))
        if name == "get_paper_structure":
            return _tool_result(await gateway.query("/api/v1/reading/structure", {
                "paper_id": values["paper_id"],
                "structure_type": values.get("structure_type", "SECTION"),
                "section_query": values.get("section_query", ""),
                "page_from": values.get("page_from"),
                "page_to": values.get("page_to"),
                "offset": values.get("offset", 0),
                "limit": values.get("limit", 100),
            }))
        if name == "search_paper_content":
            return _tool_result(await gateway.query("/api/v1/reading/content/search", {
                "paper_id": values["paper_id"],
                "query_text": values.get("query_text", ""),
                "section_query": values.get("section_query", ""),
                "section_refs": values.get("section_refs"),
                "element_types": values.get("element_types"),
                "page_from": values.get("page_from"),
                "page_to": values.get("page_to"),
                "top_k": values.get("top_k", 8),
            }))
        if name == "request_arxiv_import":
            return _tool_result(await gateway.query("/api/v1/arxiv/import", {
                "arxiv_id": values["arxiv_id"],
            }))
        return _tool_result(await gateway.query("/api/v1/reading/content/read", values))

    return server


def create_mcp_app(
    gateway: ReadingMcpGateway,
    *,
    host: str = "127.0.0.1",
    port: int = 8090,
):
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("MCP V1 only supports loopback hosts")
    manager = StreamableHTTPSessionManager(
        create_mcp_server(gateway),
        json_response=True,
        stateless=True,
        security_settings=TransportSecuritySettings(
            allowed_hosts=[f"127.0.0.1:{port}", f"localhost:{port}", f"[::1]:{port}"],
            allowed_origins=[f"http://127.0.0.1:{port}", f"http://localhost:{port}"],
        ),
    )
    app = Starlette(
        routes=[Route("/mcp", endpoint=_StreamableApp(manager))],
        lifespan=lambda _app: manager.run(),
    )
    app.add_middleware(_BearerAndSizeMiddleware)
    return app


class _StreamableApp:
    def __init__(self, manager: StreamableHTTPSessionManager) -> None:
        self.manager = manager

    async def __call__(self, scope, receive, send) -> None:
        await self.manager.handle_request(scope, receive, send)


class _BearerAndSizeMiddleware:
    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        authorization_headers = [
            value.decode("latin-1")
            for key, value in scope.get("headers", [])
            if key.decode("latin-1").lower() == "authorization"
        ]
        authorization = authorization_headers[0] if len(authorization_headers) == 1 else ""
        scheme, separator, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not separator or not token or any(char.isspace() for char in token):
            await _response(send, 401, {"error": "UNAUTHORIZED"})
            return
        messages = []
        size = 0
        while True:
            message = await receive()
            messages.append(message)
            if message.get("type") == "http.request":
                size += len(message.get("body", b""))
                if size > MAX_REQUEST_BYTES:
                    await _response(send, 413, {"error": "REQUEST_TOO_LARGE"})
                    return
                if not message.get("more_body", False):
                    break
            else:
                break

        async def replay():
            return messages.pop(0) if messages else {"type": "http.request", "body": b"", "more_body": False}

        context = _bearer_token.set(token)
        try:
            await self.app(scope, replay, send)
        finally:
            _bearer_token.reset(context)


async def _response(send, status: int, payload: JsonMap) -> None:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())],
    })
    await send({"type": "http.response.body", "body": body})


def run_mcp_server(host: str, port: int, java_base_url: str) -> None:
    uvicorn.run(
        create_mcp_app(ReadingMcpGateway(java_base_url), host=host, port=port),
        host=host,
        port=port,
    )
