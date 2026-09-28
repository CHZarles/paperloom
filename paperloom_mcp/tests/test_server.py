from __future__ import annotations

import base64
import unittest

import httpx
import jsonschema
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from paperloom_mcp.server import (
    ReadingMcpGateway,
    _bearer_token,
    _decode_chunk,
    create_mcp_app,
    _tools,
)


class McpUploadContractTest(unittest.IsolatedAsyncioTestCase):
    async def test_tool_surface_contains_upload_flow_without_arxiv_tools(self) -> None:
        names = {tool.name for tool in _tools()}

        self.assertEqual(
            {
                "search_papers",
                "get_paper",
                "get_paper_structure",
                "search_paper_content",
                "read_paper_content",
                "upload_paper_chunk",
                "get_paper_upload_status",
            },
            names,
        )
        self.assertFalse(any("arxiv" in name.lower() for name in names))

    async def test_upload_chunk_forwards_multipart_and_bearer(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            body = await request.aread()
            self.assertIn(b'name="paperId"', body)
            self.assertIn(b'name="chunkIndex"', body)
            self.assertIn(b'name="file"', body)
            self.assertIn(b"PDF bytes", body)
            return httpx.Response(200, json={
                "code": 200,
                "message": "ok",
                "data": {"uploaded": [0], "progress": 100, "paperId": "a" * 32},
            })

        client = httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            base_url="http://java.test",
        )
        gateway = ReadingMcpGateway(client=client)
        token = _bearer_token.set("user-token")
        try:
            result = await gateway.upload_chunk(
                paper_id="a" * 32,
                chunk_index=0,
                total_chunks=1,
                total_size=len(b"PDF bytes"),
                paper_title="paper.pdf",
                content=b"PDF bytes",
            )
        finally:
            _bearer_token.reset(token)
            await client.aclose()

        self.assertEqual(200, result["code"])
        self.assertEqual("Bearer user-token", requests[0].headers["Authorization"])
        self.assertEqual("/api/v1/papers/upload/chunk", requests[0].url.path)

    async def test_status_and_merge_use_existing_upload_endpoints(self) -> None:
        paths: list[tuple[str, str]] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            paths.append((request.method, request.url.path))
            if request.url.path.endswith("/status"):
                payload = {"code": 200, "message": "ok", "data": {
                    "uploaded": [0], "progress": 50, "paperId": "a" * 32,
                    "fileType": "PDF论文",
                }}
            else:
                payload = {"code": 200, "message": "ok", "data": {
                    "paperId": "a" * 32, "processingStatus": "PROCESSING",
                }}
            return httpx.Response(200, json=payload)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://java.test")
        gateway = ReadingMcpGateway(client=client)
        token = _bearer_token.set("user-token")
        try:
            await gateway.upload_status("a" * 32)
            await gateway.merge_upload("a" * 32, "paper.pdf")
        finally:
            _bearer_token.reset(token)
            await client.aclose()

        self.assertEqual([
            ("GET", "/api/v1/papers/upload/status"),
            ("POST", "/api/v1/papers/upload/merge"),
        ], paths)

    async def test_chunk_payload_is_bounded_and_strict_base64(self) -> None:
        with self.assertRaises(Exception):
            _decode_chunk("not base64")

        encoded = base64.b64encode(b"PDF bytes").decode()
        self.assertEqual(b"PDF bytes", _decode_chunk(encoded))

    async def test_upload_schema_rejects_arxiv_or_client_identity_fields(self) -> None:
        upload = next(tool for tool in _tools() if tool.name == "upload_paper_chunk")
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({
                "paper_id": "a" * 32,
                "chunk_index": 0,
                "total_chunks": 1,
                "total_size": 9,
                "paper_title": "paper.pdf",
                "content_base64": base64.b64encode(b"PDF bytes").decode(),
                "user_id": "attacker-controlled",
            }, upload.inputSchema)

    async def test_streamable_http_server_advertises_upload_tools(self) -> None:
        gateway = ReadingMcpGateway(
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(lambda request: httpx.Response(500)),
                base_url="http://java.test",
            )
        )
        app = create_mcp_app(gateway, host="127.0.0.1", port=8090)
        async with app.router.lifespan_context(app):
            http_client = httpx.AsyncClient(
                headers={"Authorization": "Bearer test-token"},
                transport=httpx.ASGITransport(app=app),
                base_url="http://127.0.0.1:8090",
            )
            async with streamable_http_client(
                "http://127.0.0.1:8090/mcp", http_client=http_client
            ) as (read_stream, write_stream, _):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    tools = await session.list_tools()
            await http_client.aclose()
        await gateway.client.aclose()

        self.assertIn("upload_paper_chunk", {tool.name for tool in tools.tools})
        self.assertNotIn("merge_paper_upload", {tool.name for tool in tools.tools})

    async def test_oauth_metadata_is_public_and_missing_bearer_advertises_it(self) -> None:
        gateway = ReadingMcpGateway(
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(lambda request: httpx.Response(500)),
                base_url="http://java.test",
            )
        )
        app = create_mcp_app(gateway, host="127.0.0.1", port=8090)
        async with app.router.lifespan_context(app):
            client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1:8090")
            metadata = await client.get("/.well-known/oauth-protected-resource")
            missing = await client.get("/mcp")
            await client.aclose()
        await gateway.client.aclose()

        self.assertEqual(200, metadata.status_code)
        self.assertEqual(["http://java.test"], metadata.json()["authorization_servers"])
        self.assertEqual(401, missing.status_code)
        self.assertIn(
            'resource_metadata="http://127.0.0.1:8090/.well-known/oauth-protected-resource"',
            missing.headers["www-authenticate"],
        )

    async def test_final_chunk_triggers_merge_inside_mcp_server(self) -> None:
        paths: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            paths.append(request.url.path)
            if request.url.path.endswith("/chunk"):
                payload = {"code": 200, "message": "ok", "data": {
                    "uploaded": [0], "progress": 100, "paperId": "a" * 32,
                }}
            else:
                payload = {"code": 200, "message": "merged", "data": {
                    "paperId": "a" * 32, "processingStatus": "PROCESSING",
                }}
            return httpx.Response(200, json=payload)

        gateway = ReadingMcpGateway(
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(handler),
                base_url="http://java.test",
            )
        )
        app = create_mcp_app(gateway, host="127.0.0.1", port=8090)
        async with app.router.lifespan_context(app):
            http_client = httpx.AsyncClient(
                headers={"Authorization": "Bearer test-token"},
                transport=httpx.ASGITransport(app=app),
                base_url="http://127.0.0.1:8090",
            )
            async with streamable_http_client(
                "http://127.0.0.1:8090/mcp", http_client=http_client
            ) as (read_stream, write_stream, _):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    result = await session.call_tool("upload_paper_chunk", {
                        "paper_id": "a" * 32,
                        "chunk_index": 0,
                        "total_chunks": 1,
                        "total_size": 9,
                        "paper_title": "paper.pdf",
                        "content_base64": base64.b64encode(b"PDF bytes").decode(),
                    })
            await http_client.aclose()
        await gateway.client.aclose()

        self.assertEqual([
            "/api/v1/papers/upload/chunk",
            "/api/v1/papers/upload/merge",
        ], paths)
        self.assertFalse(result.isError)
        self.assertEqual("PROCESSING", result.structuredContent["data"]["processingStatus"])


if __name__ == "__main__":
    unittest.main()
