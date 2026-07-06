# Use reading elements as canonical typed content

PaperLoom will make `PaperReadingElement` the canonical persisted row for tables, figures, charts,
formulas, panel labels, and visual-only parser objects. `PaperLocation` will point TABLE and FIGURE
navigation at reading elements, and `PaperVisualAsset` will link visual evidence to reading elements;
`paper_tables`, `paper_figures`, and `paper_formulas` should be removed or treated only as derived
projections during the refactor. This avoids maintaining several versioned stores for the same parser
object while preserving all MinerU information needed for inspection and later retrieval.
