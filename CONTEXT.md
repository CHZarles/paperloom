# PaperLoom

PaperLoom is a research-paper RAG product. Its domain language separates the product paper library
from benchmark corpora so product behavior is not confused with eval data.

## Language

**Product Paper Library**:
The user's permissioned collection of research paper PDFs that have entered the product upload,
parsing, indexing, and evidence pipeline.
_Avoid_: benchmark library, structured import library, eval paper library

**Product Paper**:
A research paper PDF stored in the product data domain. It may be searchable only after the product
parser/indexer has produced text chunks and evidence metadata.
_Avoid_: eval paper, structured import, benchmark record

**Eval Corpus**:
A benchmark-only dataset such as LitSearch or QASPER, stored outside the product data domain and
used only by evaluation harnesses.
_Avoid_: product paper library, uploaded papers

**Retrieval Corpus**:
The explicit data domain selected for a retrieval run, such as `PRODUCT_LIBRARY`,
`EVAL_LITSEARCH`, or `EVAL_QASPER`.
_Avoid_: includeEval flag, mixed corpus

**PDF Evidence Readiness**:
The product-facing state describing which PDF-derived evidence assets exist for a product paper,
such as text chunks, page screenshots, table crops, figure crops, and parser artifacts.
_Avoid_: eval import readiness, structured import readiness
