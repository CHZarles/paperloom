#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

matches="$(rg -n -i --hidden \
  -g '!.git/**' \
  -g '!.git' \
  -g '!target/**' \
  -g '!frontend/node_modules/**' \
  -g '!frontend/dist/**' \
  -g '!harness_py/**' \
  -g '!.venv-harness/**' \
  -g '!.qoder/**' \
  -g '!frontend/.understand-anything/**' \
  -g '!docs/archive/**' \
  -g '!docs/superpowers/**' \
  -g '!youtube_scripts/**' \
  -g '!handouts/**' \
  -g '!THIRD_PARTY_NOTICES.md' \
  -g '!AGENTS.md.old' \
  -g '!.env' \
  -g '!scripts/check-legacy-identity.sh' \
  '(PaiSmart|SmartPAI|smartpai|pai_smart|pai-smart|yizhaoqi|派聪明)' . || true)"

if [[ -n "$matches" ]]; then
  echo "Legacy project identity found in active files:" >&2
  printf '%s\n' "$matches" >&2
  exit 1
fi

for path in src/main/java/com/yizhaoqi src/test/java/com/yizhaoqi; do
  if [[ -e "$path" ]]; then
    echo "Legacy Java package directory still exists: ${path}" >&2
    exit 1
  fi
done

echo "Active PaperLoom identity check passed."
