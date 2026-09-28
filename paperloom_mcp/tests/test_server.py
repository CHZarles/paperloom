from __future__ import annotations

import base64
import asyncio
import socket
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, parse_qsl, urlparse

import httpx
import jsonschema
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from paperloom_mcp.server import (
    OAuthTokenManager,
    ReadingMcpGateway,
    _bearer_token,
    _decode_chunk,
    create_mcp_app,
    _tools,
)


def _free_loopback_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


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
            duplicate = await client.post(
                "/mcp",
                headers=[("Authorization", "Bearer first"), ("Authorization", "Bearer second")],
            )
            await client.aclose()
        await gateway.client.aclose()

        self.assertEqual(200, metadata.status_code)
        self.assertEqual(["http://java.test"], metadata.json()["authorization_servers"])
        self.assertEqual(401, missing.status_code)
        self.assertEqual(401, duplicate.status_code)
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

    async def test_oauth_client_completes_pkce_callback_and_reuses_token(self) -> None:
        port = _free_loopback_port()
        callback_url = f"http://127.0.0.1:{port}/oauth/callback"
        browser_urls: list[str] = []
        token_requests: list[dict[str, str]] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/.well-known/oauth-authorization-server":
                return httpx.Response(200, json={
                    "authorization_endpoint": "http://auth.test/oauth2/authorize",
                    "token_endpoint": "http://auth.test/oauth2/token",
                })
            if request.url.path == "/oauth2/token":
                token_requests.append(dict(parse_qsl((await request.aread()).decode())))
                return httpx.Response(200, json={
                    "access_token": "access-1", "refresh_token": "refresh-1",
                    "token_type": "Bearer", "expires_in": 600, "scope": "paper.read",
                })
            return httpx.Response(404)

        callback_client = httpx.AsyncClient(trust_env=False)
        async def open_browser(url: str) -> bool:
            browser_urls.append(url)
            query = parse_qs(urlparse(url).query)
            response = await callback_client.get(
                f"{callback_url}?code=code-1&state={query['state'][0]}"
            )
            self.assertEqual(200, response.status_code)
            return True

        def browser_opener(url: str) -> bool:
            asyncio.create_task(open_browser(url))
            return True

        with tempfile.TemporaryDirectory() as directory:
            client = httpx.AsyncClient(
                transport=httpx.MockTransport(handler),
                base_url="http://auth.test",
            )
            manager = OAuthTokenManager(
                "http://auth.test",
                http_client=client,
                redirect_uri=callback_url,
                token_path=str(Path(directory) / "token.json"),
                browser_opener=browser_opener,
            )
            try:
                first = await manager.get_access_token("paper.read")
                self.assertIsNone(manager._server)
                self.assertEqual(0o600, (Path(directory) / "token.json").stat().st_mode & 0o777)
                second = await manager.get_access_token("paper.read")
            finally:
                await manager.close()
                await client.aclose()
                await callback_client.aclose()

        self.assertEqual("access-1", first)
        self.assertEqual("access-1", second)
        self.assertEqual(1, len(browser_urls))
        self.assertEqual("code-1", token_requests[0]["code"])
        self.assertIn("code_verifier", token_requests[0])
        self.assertEqual("S256", parse_qs(urlparse(browser_urls[0]).query)["code_challenge_method"][0])

    async def test_refresh_requires_requested_scope_and_keeps_rotating_token(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={
                "access_token": "access-2", "expires_in": 600,
                "scope": "paper.read",
            })

        client = httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            base_url="http://auth.test",
        )
        manager = OAuthTokenManager("http://auth.test", http_client=client)
        try:
            refreshed = await manager._refresh_token({
                "issuer": "http://auth.test",
                "client_id": "paperloom-local-mcp",
                "refresh_token": "refresh-1",
                "scope": "paper.read",
                "token_endpoint": "http://auth.test/oauth2/token",
            }, ["paper.read", "paper.upload"])
            payload = manager._token_payload(
                {"access_token": "access-3", "expires_in": 600, "scope": "paper.read"},
                ["paper.read"],
                "http://auth.test/oauth2/token",
                fallback_refresh_token="refresh-2",
            )
        finally:
            await client.aclose()

        self.assertIsNone(refreshed)
        self.assertIsNotNone(payload)
        self.assertEqual("refresh-2", payload["refresh_token"])

    async def test_gateway_uses_local_oauth_token_when_request_has_no_bearer(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json={
                "query_text": "", "papers": [], "matched_count": 0,
                "returned_count": 0, "coverage": "complete",
            })

        class StubOAuth:
            async def get_access_token(self, required_scope: str) -> str:
                self.required_scope = required_scope
                return "local-oauth-token"

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://java.test")
        gateway = ReadingMcpGateway(client=client, oauth_manager=StubOAuth())
        try:
            result = await gateway.query("/api/v1/reading/papers/search", {"query_text": ""})
        finally:
            await client.aclose()

        self.assertEqual(0, result["matched_count"])
        self.assertEqual("Bearer local-oauth-token", requests[0].headers["Authorization"])


if __name__ == "__main__":
    unittest.main()
