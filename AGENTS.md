Spend time on thinking; you do not need to use the commentary channel to report progress to me.
DO NOT send optional commentary

## Product Direction

- Iterate in small vertical slices; do not broaden the product while a slice is unfinished.
- The current freeze target is the arXiv discovery/import path. MCP, Field Extraction, public OAuth,
  citation graphs, and multimodal retrieval are deferred.
- arXiv starts as an explicit Research Chat discovery tool, not an automatic fallback or a second search page.
- Search returns at most 10 latest versioned candidates with bounded metadata and abstract previews only;
  search metadata is never paper evidence and never imports automatically.
- Existing PaperLoom corpus search remains the default for ordinary research questions.
- Production stays unchanged until the arXiv end-to-end gate passes; do not use production as a test environment.
- Keep functional commits separate from drafts, interview notes, evaluation output, `tmp/`, and caches.
