from __future__ import annotations

import argparse
import os

from .server import run_mcp_server


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the PaperLoom MCP server.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument(
        "--java-base-url",
        default=os.getenv("PAPERLOOM_PUBLIC_API_BASE_URL", "http://127.0.0.1:8081"),
    )
    args = parser.parse_args(argv)
    run_mcp_server(args.host, args.port, args.java_base_url)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
