# Real PDF Parser Smoke

This benchmark checks real PDF-derived parser outputs. It is the gate for claims about PDF parsing,
OCR/page coverage, parser artifacts, and visual evidence assets. It is separate from QASPER and
LitSearch because those benchmarks use structured text imports and do not prove PDF parser quality.

## Scope

The smoke reads a JSONL manifest and checks existing PaperLoom database rows for each listed PDF.
It does not import or reparse by default. The active runtime must already have uploaded and parsed
the PDF, so MySQL contains the `Paper` row, chunks, parser artifacts, and any required visual
assets.

Structured/eval rows are rejected as parser-smoke evidence. A row is not accepted when `isEval` is
true, `sourceDataset` is present, or the original file is a structured JSON import.

## Manifest

Seed manifest:

```text
eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl
```

Current case:

```json
{"id":"paismart_pdf_smoke","path":"docs/paismart.pdf","expectedMinChunks":1,"expectedMinPages":1,"expectedParser":"mineru-or-configured","requiresTableOrFigure":false}
```

Useful fields:

| Field | Meaning |
|---|---|
| `id` | Stable case id. |
| `path` | Local PDF path. When `paperId` is omitted, the runner hashes this file and looks up `Paper.paperId`. |
| `paperId` | Optional explicit PaperLoom paper id. |
| `originalFilename` | Optional fallback filename match. |
| `expectedMinChunks` | Minimum `paper_text_chunks` rows. Defaults to `1`. |
| `expectedMinPages` | Minimum page-aware chunk count. Defaults to `0`. |
| `requiresParserArtifact` | Require a `paper_parser_artifacts` row. Defaults to `true`. |
| `requiresPageScreenshot` | Require `PAGE_SCREENSHOT` assets. Defaults to `false`. |
| `requiresTableOrFigure` | Require at least one table or figure record. Defaults to `false`. |
| `expectedMinTables` | Minimum table records. Defaults to `0`. |
| `expectedMinFigures` | Minimum figure records. Defaults to `0`. |
| `expectedMinFormulas` | Minimum formula records. Defaults to `0`. |
| `expectedMinTableCrops` | Minimum `TABLE_CROP` assets. Defaults to `0`. |
| `expectedMinFigureCrops` | Minimum `FIGURE_CROP` assets. Defaults to `0`. |
| `expectedMinChartCrops` | Minimum `CHART_CROP` assets. Defaults to `0`. |

## Run

Build test classes and the classpath file:

```bash
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
```

Run the smoke:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  --manifest eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl \
  --runs-root eval/rag/runs
```

The CLI writes standard PaperLoom eval artifacts:

```text
eval/rag/runs/<run-id>/run.json
eval/rag/runs/<run-id>/scorecard.json
eval/rag/runs/<run-id>/report.md
```

## Preconditions

- MinerU must be available when the active runtime imports or reparses PDFs.
- If MinerU is intentionally unavailable, use the configured parser fallback and treat the score as
  a fallback-parser smoke, not as MinerU quality evidence.
- The smoke checks already processed rows. Upload and parse the PDF through the active PaperLoom
  runtime before running the CLI.
- QASPER and LitSearch structured imports cannot satisfy this benchmark.
