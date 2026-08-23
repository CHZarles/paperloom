from __future__ import annotations

import json
import asyncio
import re
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from paperloom_mcp.__main__ import main
from paperloom_mcp.server import ReadingMcpGateway, create_mcp_app


class McpServerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.java_calls: list[tuple[str, str, str, dict]] = []

        async def java(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content or b"{}")
            authorization = request.headers.get("Authorization", "")
            request_id = request.headers.get("X-Request-ID", "")
            self.java_calls.append((request.url.path, authorization, request_id, body))
            if authorization == "Bearer forbidden":
                return httpx.Response(403, content=b"")
            if authorization not in {"Bearer user-a", "Bearer user-b"}:
                return httpx.Response(401, content=b"")
            if request.url.path.endswith("/papers/search"):
                await asyncio.sleep(0.01)
                if body.get("query_text") == "huge":
                    return httpx.Response(200, json={"papers": [{"preview": "x" * 17_000}]})
                if body.get("query_text") == "invalid-length":
                    return httpx.Response(200, content=b"{}", headers={"Content-Length": "invalid"})
                if body.get("query_text") == "unsafe-error":
                    return httpx.Response(503, json={
                        "error": "UPSTREAM_SECRET", "message": "secret paper content",
                    })
                if body.get("query_text") == "non-json-error":
                    return httpx.Response(503, content=b"upstream stack trace")
                if body.get("query_text") == "network-error":
                    raise httpx.ConnectError("secret java.test", request=request)
                return httpx.Response(200, json={
                    "query_text": body.get("query_text") or "",
                    "papers": [{"paper_id": "paper-a", "title": "Paper A"}],
                    "matched_count": 1,
                    "returned_count": 1,
                    "coverage": "complete",
                }, headers={"New-Token": "must-not-leak"})
            if request.url.path.endswith("/content/read"):
                return httpx.Response(200, json={
                    "items": [{
                        "paper_id": "paper-a",
                        "location_ref": "location-a",
                        "content_kind": "PASSAGE",
                        "content": "canonical content",
                    }],
                    "missing_location_refs": [],
                })
            return httpx.Response(200, json={"items": []})

        self.java_client = httpx.AsyncClient(
            base_url="http://java.test",
            transport=httpx.MockTransport(java),
        )
        self.app = create_mcp_app(ReadingMcpGateway(client=self.java_client))

    async def asyncTearDown(self) -> None:
        await self.java_client.aclose()

    async def test_initialize_lists_only_five_independent_query_tools(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("user-a") as (session, initialized):
                self.assertIsNotNone(initialized.capabilities.tools)
                self.assertIsNone(initialized.capabilities.prompts)
                self.assertIsNone(initialized.capabilities.resources)
                tools = (await session.list_tools()).tools

        self.assertEqual({
            "search_papers",
            "get_paper",
            "get_paper_structure",
            "search_paper_content",
            "read_paper_content",
        }, {tool.name for tool in tools})
        forbidden = {"user_id", "role", "scope_paper_ids", "credential", "model_version", "evidence_payloads"}
        for tool in tools:
            properties = set(tool.inputSchema.get("properties", {}))
            self.assertTrue(forbidden.isdisjoint(properties), tool.name)

    async def test_tools_forward_bearer_to_public_java_queries_and_return_equivalent_content(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("user-a") as (session, _):
                search = await session.call_tool("search_papers", {"query_text": "Paper A", "limit": 5})
                await session.call_tool("get_paper", {"paper_id": "paper-a"})
                await session.call_tool("get_paper_structure", {"paper_id": "paper-a"})
                await session.call_tool("search_paper_content", {
                    "paper_id": "paper-a", "query_text": "query",
                })
                read = await session.call_tool("read_paper_content", {
                    "paper_id": "paper-a",
                    "location_refs": ["location-a"],
                })

        self.assertFalse(search.isError)
        self.assertFalse(read.isError)
        for result in (search, read):
            self.assertEqual(result.structuredContent, json.loads(result.content[0].text))
        self.assertNotIn("source_quote_ref", json.dumps(read.structuredContent))
        self.assertEqual(
            [
                "/api/v1/reading/papers/search",
                "/api/v1/reading/papers/get",
                "/api/v1/reading/structure",
                "/api/v1/reading/content/search",
                "/api/v1/reading/content/read",
            ],
            [path for path, _, _, _ in self.java_calls],
        )
        self.assertTrue(all(auth == "Bearer user-a" for _, auth, _, _ in self.java_calls))
        self.assertTrue(all(re.fullmatch(r"[0-9a-f-]{36}", request_id)
                            for _, _, request_id, _ in self.java_calls))
        self.assertTrue(all("user_id" not in body and "scope_paper_ids" not in body
                            for _, _, _, body in self.java_calls))
        self.assertNotIn("must-not-leak", json.dumps([search.structuredContent, read.structuredContent]))

    async def test_each_stateless_call_uses_its_own_bearer(self) -> None:
        async def call(token: str):
            async with self.session(token) as (session, _):
                await session.call_tool("search_papers", {})

        async with self.app.router.lifespan_context(self.app):
            await asyncio.gather(call("user-a"), call("user-b"))

        self.assertEqual(
            {"Bearer user-a", "Bearer user-b"},
            {auth for _, auth, _, _ in self.java_calls},
        )

    async def test_missing_auth_and_size_limits_fail_closed(self) -> None:
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app)) as client:
            unauthorized = await client.post("http://127.0.0.1:8090/mcp", json={
                "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {},
            })
            malformed = await client.post(
                "http://127.0.0.1:8090/mcp",
                headers={"Authorization": "Bearer user-a extra"},
                json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
            )
            oversized = await client.post(
                "http://127.0.0.1:8090/mcp",
                headers={"Authorization": "Bearer user-a"},
                content=b"x" * (1024 * 1024 + 1),
            )
        self.assertEqual(401, unauthorized.status_code)
        self.assertEqual(401, malformed.status_code)
        self.assertEqual(413, oversized.status_code)
        self.assertEqual([], self.java_calls)

    async def test_oversized_java_result_becomes_a_safe_tool_error(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("user-a") as (session, _):
                result = await session.call_tool("search_papers", {"query_text": "huge"})

        self.assertTrue(result.isError)
        self.assertIn("READING_RESULT_TOO_LARGE", result.content[0].text)
        self.assertNotIn("x" * 100, result.content[0].text)

    async def test_java_rejected_bearer_is_a_safe_tool_error(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("invalid") as (session, _):
                unauthorized = await session.call_tool("search_papers", {})
            async with self.session("forbidden") as (session, _):
                forbidden = await session.call_tool("search_papers", {})

        self.assertTrue(unauthorized.isError)
        self.assertEqual("UNAUTHORIZED", unauthorized.content[0].text)
        self.assertTrue(forbidden.isError)
        self.assertEqual("FORBIDDEN", forbidden.content[0].text)

    async def test_invalid_upstream_metadata_and_bodies_do_not_leak(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("user-a") as (session, _):
                invalid_length = await session.call_tool(
                    "search_papers", {"query_text": "invalid-length"})
                unsafe_error = await session.call_tool(
                    "search_papers", {"query_text": "unsafe-error"})
                non_json = await session.call_tool(
                    "search_papers", {"query_text": "non-json-error"})
                network_error = await session.call_tool(
                    "search_papers", {"query_text": "network-error"})

        self.assertTrue(invalid_length.isError)
        self.assertEqual("INVALID_READING_RESPONSE", invalid_length.content[0].text)
        self.assertEqual("RETRIEVAL_UNAVAILABLE", unsafe_error.content[0].text)
        self.assertEqual("INVALID_READING_RESPONSE", non_json.content[0].text)
        self.assertEqual("RETRIEVAL_UNAVAILABLE", network_error.content[0].text)
        self.assertNotRegex(json.dumps([
            unsafe_error.content[0].text, non_json.content[0].text, network_error.content[0].text,
        ]), r"secret|java\.test|stack trace")

    async def test_schema_failure_is_a_stable_tool_error_without_java_call(self) -> None:
        async with self.app.router.lifespan_context(self.app):
            async with self.session("user-a") as (session, _):
                result = await session.call_tool("read_paper_content", {
                    "paper_id": "paper-a", "location_refs": [" "],
                })

        self.assertTrue(result.isError)
        self.assertEqual("READING_ARGUMENTS_INVALID", result.content[0].text)
        self.assertEqual([], self.java_calls)

    async def test_cli_starts_loopback_mcp_server(self) -> None:
        with patch("paperloom_mcp.__main__.run_mcp_server") as run:
            result = main([
                "--host", "127.0.0.1", "--port", "8090",
                "--java-base-url", "http://127.0.0.1:8081",
            ])
        self.assertEqual(0, result)
        run.assert_called_once_with("127.0.0.1", 8090, "http://127.0.0.1:8081")

    def test_non_loopback_host_cannot_start_server(self) -> None:
        with self.assertRaisesRegex(ValueError, "loopback"):
            create_mcp_app(ReadingMcpGateway(client=self.java_client), host="0.0.0.0")

    def test_standalone_package_does_not_import_harness(self) -> None:
        package = Path(__file__).resolve().parents[1]
        for source in package.glob("*.py"):
            self.assertNotIn("harness_py", source.read_text(encoding="utf-8"), source.name)

    def session(self, token: str):
        app = self.app

        class SessionContext:
            async def __aenter__(self):
                self.http_client = httpx.AsyncClient(
                    headers={"Authorization": f"Bearer {token}"},
                    transport=httpx.ASGITransport(app=app),
                )
                self.transport = streamable_http_client(
                    "http://127.0.0.1:8090/mcp", http_client=self.http_client)
                streams = await self.transport.__aenter__()
                self.session = ClientSession(streams[0], streams[1])
                await self.session.__aenter__()
                initialized = await self.session.initialize()
                return self.session, initialized

            async def __aexit__(self, exc_type, exc, traceback):
                await self.session.__aexit__(exc_type, exc, traceback)
                await self.transport.__aexit__(exc_type, exc, traceback)
                await self.http_client.aclose()

        return SessionContext()


if __name__ == "__main__":
    unittest.main()
