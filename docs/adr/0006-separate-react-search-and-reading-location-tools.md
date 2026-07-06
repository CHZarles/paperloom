# Separate ReAct paper search from reading-location search

PaperLoom will expose the current grep-based ReAct bridge as two tools,
`search_paper_candidates` and `find_reading_locations`, instead of exposing the combined
`PaperRecommendationCandidateService` as one LLM-facing recommendation tool. The combined service
remains useful for QA/manual inspection, but the ReAct catalog must preserve the user-visible
decision boundary between choosing candidate papers and choosing where to read inside them.

**Considered Options**

- Expose one combined recommendation-candidate tool. Rejected because it can make metadata matches
  and reading-location previews look like recommendation reasons before Source Quotes exist.
- Wait for the full nine-tool Product ReAct catalog. Rejected because the current metadata grep and
  Reading Model grep services can safely support candidate discovery and navigation now if they are
  wrapped behind opaque `paperHandle` and `locationRef` outputs.

**Consequences**

- Phase 1 can answer candidate-list and navigation requests, but not source-quoted paper-content
  questions.
- `read_locations` and Source Quote persistence are still required before content-based
  recommendations, summaries, comparisons, or citations are allowed.
