Spend time on thinking; you do not need to use the commentary channel to report progress to me.
DO NOT send optional commentary

## Product Direction

- Iterate in small vertical slices; do not broaden the product while a slice is unfinished.
- MCP supports authenticated paper reading and the existing PDF upload flow. Built-in arXiv
  search/import, Field Extraction, public OAuth, citation graphs, and multimodal retrieval remain deferred.
- Paper ingestion uses the existing PDF upload flow; external tools handle paper discovery and downloading.
- Existing PaperLoom corpus search remains the default for ordinary research questions.
- Production stays unchanged during local development; do not use production as a test environment.
- Keep functional commits separate from drafts, interview notes, evaluation output, `tmp/`, and caches.
