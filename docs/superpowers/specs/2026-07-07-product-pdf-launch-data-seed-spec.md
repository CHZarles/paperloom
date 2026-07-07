# Product PDF Launch Data Seed Spec

## Problem

Product Reading now has 9-tool smoke and trace gates, and the 30-PDF parser manifest exists. The remaining launch gap is that the 30 PDFs are not being built through the live product upload path in the active runtime, so the live reading smoke cannot prove frontend-to-backend behavior over real launch data.

## Required Behavior

1. Provide `ProductPdfLaunchDataSeedCli`.
   - Logs in through `/api/v1/users/login`.
   - Reads `eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl` by default.
   - Uses the same upload boundary as the frontend:
     - `POST /api/v1/papers/upload/chunk`
     - `POST /api/v1/papers/upload/merge`
     - `GET /api/v1/papers/uploads`
   - Writes standard eval artifacts under `eval/rag/runs`.

2. Provide `ProductPdfLaunchDataSeedRunner`.
   - Loads manifest cases using the existing parser-smoke manifest format.
   - Resolves each local PDF path relative to the manifest and repository working directory.
   - Computes the MD5 paper id from PDF bytes, matching frontend upload behavior.
   - Uploads each PDF in deterministic chunks; default chunk size is `5 * 1024 * 1024` bytes.
   - Calls merge after all chunks for a PDF are uploaded.
   - Polls uploaded-paper status until each seeded PDF is frontend-searchable or the poll budget is exhausted.

3. Define frontend-searchable exactly as the knowledge-base page does.
   - Upload is completed when `uploadStatus` is `1` or `COMPLETED`.
   - Processing is completed when `processingStatus` is `COMPLETED`.
   - Actual embedding usage is present when `actualEmbeddingTokens` is not null.
   - Actual chunks are present when `actualChunkCount > 0`.

4. Evaluate each manifest PDF as one eval case.
   - Missing local file fails with `LOCAL_PDF_MISSING`.
   - Upload failures fail with `UPLOAD_FAILED`.
   - Merge failures fail with `MERGE_FAILED`.
   - Poll exhaustion without frontend-searchable status fails with `FRONT_SEARCHABLE_MISSING`.
   - Runtime/API failures are recorded in diagnostics; do not synthesize success.

5. Provide `ProductPdfLaunchDataSeedHttpClient`.
   - Sends authenticated multipart upload chunk requests.
   - Sends authenticated merge JSON requests.
   - Parses `/papers/uploads` into status rows used by the runner.
   - Treats non-2xx HTTP responses as failures with status/body diagnostics.

6. Register and document the new gate.
   - Add `product-pdf-launch-data-seed` to `eval/rag/harnesses.yaml`.
   - Document the seed command and gate order in `eval/rag/README.md`.
   - Add registry tests.

## Non-Goals

- Do not add Product Reading tools.
- Do not import PDFs directly into MySQL, Elasticsearch, MinIO, or parser tables.
- Do not replace the parser smoke, live reading smoke, or trace eval.
- Do not start Docker, backend, MinerU, or frontend automatically.
- Do not fake parser artifacts, vector rows, trace artifacts, or frontend status.

## Launch Gate Order

When runtime dependencies are available:

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfLaunchDataSeedCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLiveLaunchSmokeCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLaunchTraceEvalCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  -Dexec.args="--manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"
```

Launch-ready means all four gates produce passing scorecards on the same active runtime/data.
