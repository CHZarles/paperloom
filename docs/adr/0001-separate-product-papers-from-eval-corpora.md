# Separate product papers from eval corpora

PaperLoom product data stores only research paper PDFs that pass through the product upload,
parser/OCR, indexing, and evidence pipeline. Benchmark datasets such as LitSearch and QASPER are
stored in a separate `paperloom_eval` schema and separate Elasticsearch eval indices so they can
reuse retrieval algorithms without appearing in product search, paper-library UI, source picker, or
chat history.

**Considered Options**

- Keep eval rows in product tables with `is_eval` or `includeEval` filters. Rejected because it
  allows benchmark data to leak into product behavior and makes structured text look like OCR-backed
  PDF evidence.
- Use separate MySQL/Elasticsearch services for eval. Rejected for now because separate schema and
  index names give the required data boundary while keeping local development and CI simpler.

**Consequences**

- Product tables do not keep `is_eval`, `source_dataset`, `external_corpus_id`, or `eval_split`.
- Product APIs do not expose `evalImport` or `structuredImport`.
- Eval harnesses must explicitly select an eval corpus and write results to eval artifacts, not
  product conversations.
