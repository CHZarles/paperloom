from __future__ import annotations

import base64
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
from starlette.responses import JSONResponse
from starlette.routing import Route

MAX_REQUEST_BYTES = 1024 * 1024
MAX_RESULT_BYTES = 16 * 1024
MAX_UPLOAD_CHUNK_BYTES = 512 * 1024
MAX_UPLOAD_CHUNK_BASE64_LENGTH = ((MAX_UPLOAD_CHUNK_BYTES + 2) // 3) * 4

_bearer_token: contextvars.ContextVar[str] = contextvars.ContextVar("mcp_bearer_token", default="")
JsonMap = dict[str, object]


class ReadingMcpGateway:
    def __init__(self, base_url: str | None = None, *, client: httpx.AsyncClient | None = None) -> None:
        if base_url is None and client is not None:
            base_url = str(client.base_url)
        resolved_base_url = (base_url or os.getenv(
            "PAPERLOOM_PUBLIC_API_BASE_URL", "http://127.0.0.1:8081"
        )).rstrip("/")
        self.authorization_server_url = os.getenv("PAPERLOOM_OAUTH_ISSUER", resolved_base_url)
        self.client = client or httpx.AsyncClient(
            base_url=resolved_base_url,
            timeout=httpx.Timeout(20.0, connect=5.0),
            trust_env=False,
        )

    async def query(self, path: str, payload: JsonMap) -> JsonMap:
        return await self._request(path, "POST", json_payload=payload)

    async def upload_chunk(
        self,
        *,
        paper_id: str,
        chunk_index: int,
        total_size: int,
        paper_title: str,
        total_chunks: int,
        content: bytes,
    ) -> JsonMap:
        return await self._request(
            "/api/v1/papers/upload/chunk",
            "POST",
            form={
                "paperId": str(paper_id),
                "chunkIndex": str(chunk_index),
                "totalSize": str(total_size),
                "paperTitle": paper_title,
                "totalChunks": str(total_chunks),
            },
            files={"file": (paper_title, content, "application/pdf")},
        )

    async def upload_status(self, paper_id: str) -> JsonMap:
        return await self._request(
            "/api/v1/papers/upload/status",
            "GET",
            params={"paperId": paper_id},
        )

    async def merge_upload(self, paper_id: str, paper_title: str) -> JsonMap:
        return await self._request(
            "/api/v1/papers/upload/merge",
            "POST",
            json_payload={"paperId": paper_id, "paperTitle": paper_title},
        )

    async def _request(
        self,
        path: str,
        method: str,
        *,
        json_payload: JsonMap | None = None,
        params: JsonMap | None = None,
        form: dict[str, str] | None = None,
        files=None,
    ) -> JsonMap:
        token = _bearer_token.get()
        if not token:
            raise ToolError("UNAUTHORIZED")

        headers = {
            "Authorization": f"Bearer {token}",
            "X-Request-ID": str(uuid.uuid4()),
        }
        request_kwargs = {"headers": headers}
        if json_payload is not None:
            request_kwargs["json"] = json_payload
        if params is not None:
            request_kwargs["params"] = params
        if form is not None:
            request_kwargs["data"] = form
        if files is not None:
            request_kwargs["files"] = files

        try:
            async with self.client.stream(method, path, **request_kwargs) as response:
                if response.status_code in {401, 403}:
                    raise ToolError("UNAUTHORIZED" if response.status_code == 401 else "FORBIDDEN")
                content_length = response.headers.get("content-length")
                if content_length is not None:
                    try:
                        declared_length = int(content_length)
                    except ValueError as error:
                        raise ToolError("INVALID_READING_RESPONSE") from error
                    if declared_length < 0 or declared_length > MAX_RESULT_BYTES:
                        raise ToolError("READING_RESULT_TOO_LARGE")
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > MAX_RESULT_BYTES:
                        raise ToolError("READING_RESULT_TOO_LARGE")
        except ToolError:
            raise
        except httpx.HTTPError as error:
            raise ToolError("UPLOAD_UNAVAILABLE" if path.startswith("/api/v1/papers/upload") else "RETRIEVAL_UNAVAILABLE") from error

        if response.is_error:
            raise ToolError(_status_error(response.status_code, path))
        try:
            result = json.loads(raw)
        except ValueError as error:
            raise ToolError("INVALID_READING_RESPONSE") from error
        if not isinstance(result, dict):
            raise ToolError("INVALID_READING_RESPONSE")
        try:
            jsonschema.validate(result, _RESULT_SCHEMAS[path])
        except (KeyError, jsonschema.ValidationError) as error:
            raise ToolError("INVALID_READING_RESPONSE") from error
        return result


def _status_error(status: int, path: str = "") -> str:
    if path.startswith("/api/v1/papers/upload"):
        return {
            400: "UPLOAD_ARGUMENTS_INVALID",
            409: "UPLOAD_CONFLICT",
            413: "UPLOAD_REQUEST_TOO_LARGE",
            422: "UPLOAD_RESULT_TOO_LARGE",
        }.get(status, "UPLOAD_UNAVAILABLE")
    return {
        400: "READING_ARGUMENTS_INVALID",
        404: "PAPER_NOT_AVAILABLE",
        409: "READING_MODEL_NOT_READY",
        422: "READING_RESULT_TOO_LARGE",
        429: "READING_QUOTA_EXHAUSTED",
    }.get(status, "RETRIEVAL_UNAVAILABLE")


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


def _upload_data_schema() -> JsonMap:
    return _object_schema({
        "uploaded": {"type": "array", "items": {"type": "integer", "minimum": 0}},
        "progress": {"type": "number", "minimum": 0, "maximum": 100},
        "paperId": {"type": "string"},
        "paperTitle": {"type": "string"},
        "originalFilename": {"type": "string"},
        "fileType": {"type": "string"},
        "objectUrl": {"type": "string"},
        "processingStatus": {"type": "string"},
    })


def _tools() -> list[types.Tool]:
    read_only = types.ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True)
    write_action = types.ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True)
    text = {"type": "string", "maxLength": 4000}
    paper_id = {"type": "string", "minLength": 32, "maxLength": 32, "pattern": r"^[a-fA-F0-9]{32}$"}
    filename = {"type": "string", "minLength": 5, "maxLength": 255, "pattern": r"^[^/\\\x00]+\.pdf$"}
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
            inputSchema=_object_schema({"paper_id": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"\S"}}, ["paper_id"]),
        ),
        types.Tool(
            name="get_paper_structure",
            description="Read PAGE or SECTION navigation for one accessible Paper.",
            annotations=read_only,
            inputSchema=_object_schema({
                "paper_id": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"\S"},
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
                "paper_id": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"\S"},
                "query_text": text,
                "section_query": text,
                "section_refs": _string_array(20, 96),
                "element_types": {"type": "array", "maxItems": 3, "uniqueItems": True, "items": {"type": "string", "enum": ["passage", "table", "figure"]}},
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
                "paper_id": {"type": "string", "minLength": 1, "maxLength": 64, "pattern": r"\S"},
                "location_refs": location_refs,
            }, ["paper_id", "location_refs"]),
        ),
        types.Tool(
            name="upload_paper_chunk",
            description=(
                "Upload one base64-encoded PDF chunk through the normal PaperLoom upload flow. "
                "paper_id must be the MD5 of the complete PDF; the server merges automatically when all chunks are present."
            ),
            annotations=write_action,
            inputSchema=_object_schema({
                "paper_id": paper_id,
                "chunk_index": {"type": "integer", "minimum": 0, "maximum": 100000},
                "total_chunks": {"type": "integer", "minimum": 1, "maximum": 100000},
                "total_size": {"type": "integer", "minimum": 1, "maximum": 52428800},
                "paper_title": filename,
                "content_base64": {"type": "string", "minLength": 4, "maxLength": MAX_UPLOAD_CHUNK_BASE64_LENGTH, "pattern": r"^[A-Za-z0-9+/]+={0,2}$"},
            }, ["paper_id", "chunk_index", "total_chunks", "total_size", "paper_title", "content_base64"]),
        ),
        types.Tool(
            name="get_paper_upload_status",
            description="Get the normal PaperLoom upload progress for one PDF.",
            annotations=read_only,
            inputSchema=_object_schema({"paper_id": paper_id}, ["paper_id"]),
        ),
    ]


_PAPER_RESULT = _object_schema({
    "paper_id": {"type": "string"}, "title": {"type": "string"}, "authors": {"type": "array", "items": {"type": "string"}},
    "year": {"type": "integer"}, "venue": {"type": "string"}, "doi": {"type": "string"}, "arxiv_id": {"type": "string"},
    "filename": {"type": "string"}, "preview": {"type": "string"},
}, ["paper_id"])
_PAGE_FIELDS = {"matched_count": {"type": "integer"}, "returned_count": {"type": "integer"}, "coverage": {"type": "string", "enum": ["complete", "truncated"]}, "next_offset": {"type": "integer"}}
_RESULT_SCHEMAS = {
    "/api/v1/reading/papers/search": _object_schema({"query_text": {"type": "string"}, "papers": {"type": "array", "items": _PAPER_RESULT}, **_PAGE_FIELDS}, ["query_text", "papers", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/papers/get": _PAPER_RESULT,
    "/api/v1/reading/structure": _object_schema({"structure_type": {"type": "string", "enum": ["SECTION", "PAGE"]}, "items": {"type": "array"}, **_PAGE_FIELDS}, ["structure_type", "items", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/content/search": _object_schema({"query_text": {"type": "string"}, "locations": {"type": "array"}, **_PAGE_FIELDS}, ["query_text", "locations", "matched_count", "returned_count", "coverage"]),
    "/api/v1/reading/content/read": _object_schema({"items": {"type": "array"}, "missing_location_refs": {"type": "array", "items": {"type": "string"}}}, ["items", "missing_location_refs"]),
    "/api/v1/papers/upload/chunk": _object_schema({"code": {"type": "integer"}, "message": {"type": "string"}, "data": _upload_data_schema()}, ["code", "message", "data"]),
    "/api/v1/papers/upload/status": _object_schema({"code": {"type": "integer"}, "message": {"type": "string"}, "data": _upload_data_schema()}, ["code", "message", "data"]),
    "/api/v1/papers/upload/merge": _object_schema({"code": {"type": "integer"}, "message": {"type": "string"}, "data": _upload_data_schema()}, ["code", "message", "data"]),
}


def _tool_error(code: str) -> types.CallToolResult:
    return types.CallToolResult(content=[types.TextContent(type="text", text=code)], isError=True)


def _tool_result(result: JsonMap):
    return ([types.TextContent(type="text", text=json.dumps(result, ensure_ascii=False, separators=(",", ":")))], result)


def _decode_chunk(value: str) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise ToolError("UPLOAD_ARGUMENTS_INVALID") from error
    if not decoded or len(decoded) > MAX_UPLOAD_CHUNK_BYTES:
        raise ToolError("UPLOAD_ARGUMENTS_INVALID")
    return decoded


def create_mcp_server(gateway: ReadingMcpGateway) -> Server:
    server = Server(
        "paperloom",
        version="1.0.0",
        instructions=(
            "Authenticated PaperLoom paper reading and normal PDF upload tools. "
            "Upload uses the existing chunk, status, and merge workflow."
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
            return _tool_error("UPLOAD_ARGUMENTS_INVALID" if name in {
                "upload_paper_chunk", "get_paper_upload_status",
            } else "READING_ARGUMENTS_INVALID")
        if name == "search_papers":
            return _tool_result(await gateway.query("/api/v1/reading/papers/search", {
                "query_text": values.get("query_text", ""), "paper_ids": values.get("paper_ids"), "authors": values.get("authors"),
                "venues": values.get("venues"), "year_from": values.get("year_from"), "year_to": values.get("year_to"),
                "offset": values.get("offset", 0), "limit": values.get("limit", 20),
            }))
        if name == "get_paper":
            return _tool_result(await gateway.query("/api/v1/reading/papers/get", values))
        if name == "get_paper_structure":
            return _tool_result(await gateway.query("/api/v1/reading/structure", {
                "paper_id": values["paper_id"], "structure_type": values.get("structure_type", "SECTION"),
                "section_query": values.get("section_query", ""), "page_from": values.get("page_from"), "page_to": values.get("page_to"),
                "offset": values.get("offset", 0), "limit": values.get("limit", 100),
            }))
        if name == "search_paper_content":
            return _tool_result(await gateway.query("/api/v1/reading/content/search", {
                "paper_id": values["paper_id"], "query_text": values.get("query_text", ""), "section_query": values.get("section_query", ""),
                "section_refs": values.get("section_refs"), "element_types": values.get("element_types"),
                "page_from": values.get("page_from"), "page_to": values.get("page_to"), "top_k": values.get("top_k", 8),
            }))
        if name == "read_paper_content":
            return _tool_result(await gateway.query("/api/v1/reading/content/read", values))
        if name == "upload_paper_chunk":
            if values["chunk_index"] >= values["total_chunks"]:
                return _tool_error("UPLOAD_ARGUMENTS_INVALID")
            try:
                content = _decode_chunk(values["content_base64"])
            except ToolError as error:
                return _tool_error(str(error))
            chunk_result = await gateway.upload_chunk(
                paper_id=values["paper_id"], chunk_index=values["chunk_index"], total_size=values["total_size"],
                paper_title=values["paper_title"], total_chunks=values["total_chunks"], content=content,
            )
            uploaded = chunk_result.get("data", {}).get("uploaded", [])
            if isinstance(uploaded, list) and len(set(uploaded)) >= values["total_chunks"]:
                return _tool_result(await gateway.merge_upload(values["paper_id"], values["paper_title"]))
            return _tool_result(chunk_result)
        if name == "get_paper_upload_status":
            return _tool_result(await gateway.upload_status(values["paper_id"]))
        return _tool_error("UNKNOWN_TOOL")

    return server


def create_mcp_app(gateway: ReadingMcpGateway, *, host: str = "127.0.0.1", port: int = 8090):
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("MCP V1 only supports loopback hosts")
    manager = StreamableHTTPSessionManager(
        create_mcp_server(gateway), json_response=True, stateless=True,
        security_settings=TransportSecuritySettings(
            allowed_hosts=[f"127.0.0.1:{port}", f"localhost:{port}", f"[::1]:{port}"],
            allowed_origins=[f"http://127.0.0.1:{port}", f"http://localhost:{port}"],
        ),
    )
    metadata_host = f"[{host}]" if ":" in host and not host.startswith("[") else host
    resource_metadata_url = f"http://{metadata_host}:{port}/.well-known/oauth-protected-resource"
    app = Starlette(routes=[
        Route("/.well-known/oauth-protected-resource", endpoint=_protected_resource_metadata),
        Route("/mcp", endpoint=_StreamableApp(manager)),
    ], lifespan=lambda _app: manager.run())
    app.state.paperloom_gateway = gateway
    app.add_middleware(
        _BearerAndSizeMiddleware,
        resource_metadata_url=resource_metadata_url,
    )
    return app


class _StreamableApp:
    def __init__(self, manager: StreamableHTTPSessionManager) -> None:
        self.manager = manager

    async def __call__(self, scope, receive, send) -> None:
        await self.manager.handle_request(scope, receive, send)


class _BearerAndSizeMiddleware:
    def __init__(self, app, resource_metadata_url: str) -> None:
        self.app = app
        self.resource_metadata_url = resource_metadata_url

    async def __call__(self, scope, receive, send) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        if scope.get("path") == "/.well-known/oauth-protected-resource":
            await self.app(scope, receive, send)
            return
        authorization_headers = [value.decode("latin-1") for key, value in scope.get("headers", []) if key.decode("latin-1").lower() == "authorization"]
        authorization = authorization_headers[0] if len(authorization_headers) == 1 else ""
        scheme, separator, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not separator or not token or any(char.isspace() for char in token):
            await _response(
                send,
                401,
                {"error": "UNAUTHORIZED"},
                headers=[(
                    b"www-authenticate",
                    f'Bearer resource_metadata="{self.resource_metadata_url}"'.encode("ascii"),
                )],
            )
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


async def _protected_resource_metadata(request) -> object:
    gateway = request.app.state.paperloom_gateway
    return JSONResponse({
        "resource": str(request.base_url).rstrip("/") + "/mcp",
        "authorization_servers": [gateway.authorization_server_url],
        "scopes_supported": ["paper.read", "paper.upload"],
        "bearer_methods_supported": ["header"],
    })


async def _response(send, status: int, payload: JsonMap, *, headers: list[tuple[bytes, bytes]] | None = None) -> None:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    response_headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]
    if headers:
        response_headers.extend(headers)
    await send({"type": "http.response.start", "status": status, "headers": response_headers})
    await send({"type": "http.response.body", "body": body})


def run_mcp_server(host: str, port: int, java_base_url: str) -> None:
    uvicorn.run(create_mcp_app(ReadingMcpGateway(java_base_url), host=host, port=port), host=host, port=port)
