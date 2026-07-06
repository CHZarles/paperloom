# Derive Reading Chunks from the Current Reading Model

Reading Chunks must be generated from PaperLoom-owned Reading Model persistence, not from parser
artifacts or legacy text chunks.

The allowed input boundary is:

```text
PaperReadingElement
PaperLocation
PaperPage
PaperSection
```

The chunk builder may read the current `paper_reading_models` row to choose the active
`modelVersion`, but the chunk content and navigation metadata must come from the four persisted
Reading Model datasets above.

It must not read these as chunk sources:

```text
MinerU result zip
MinerU content_list / middle.json / markdown
ParsedPaper
paper_text_chunks
paper_tables / paper_figures / paper_formulas
```

Parser artifacts remain evidence and debugging material. `ParsedPaper` remains an ingestion-time
adapter. `paper_text_chunks` remains legacy retrieval storage until it is replaced. None of them is
the source of truth for new Reading Chunks.

This keeps retrieval downstream of the product-owned Current Reading Model:

```text
PDF
-> parser output
-> Current Reading Model
-> Reading Chunks
-> Reading Index
-> reading-location search/read tools
```

The trade-off is that chunking must wait for a READY Reading Model. That is intentional: search
results should point to stable `locationRef` and `readingElementId` coordinates instead of parser
ids, parser file offsets, or legacy chunk ids.
