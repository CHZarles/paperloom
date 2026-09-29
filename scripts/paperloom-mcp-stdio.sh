#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

PYTHON="${PAPERLOOM_MCP_PYTHON:-$ROOT/.venv-mcp/bin/python}"
if [[ ! -x "$PYTHON" ]]; then
  echo "PaperLoom MCP Python was not found: $PYTHON" >&2
  echo "Create it with: uv venv .venv-mcp && uv pip sync paperloom_mcp/requirements.lock --python .venv-mcp/bin/python" >&2
  exit 1
fi

"$ROOT/scripts/paperloom-start-backend.sh" start >&2
exec "$PYTHON" -m paperloom_mcp --transport stdio "$@"
