Spend time on thinking; you do not need to use the commentary channel to report progress to me.
DO NOT send optional commentary

## Product Direction

- Iterate in small vertical slices; do not broaden the product while a slice is unfinished.
- MCP supports authenticated paper reading and the existing PDF upload flow. Built-in arXiv metadata
  search plus explicit single-paper import is now supported (ADR 0016). Field Extraction, public
  OAuth, citation graphs, multimodal retrieval, and any web-browsing exploration agent remain
  deferred.
- Paper ingestion still ends in the existing PDF parse-and-index flow. Import remains an explicit
  user action; metadata discovery results are navigation data, never content evidence.
- Discovery content reading is a pre-import workflow (ADR 0017): use independent public-source
  adapters, not the Corpus/Reading Model APIs or automatic ingestion. External excerpts retain their
  source URLs and extraction limits; they do not become ordinary Chat evidence or expand its scope.
- Existing PaperLoom corpus search remains the default for ordinary library research questions.
- Production stays unchanged during local development; do not use production as a test environment.
- Keep functional commits separate from drafts, interview notes, evaluation output, `tmp/`, and caches.
