#!/usr/bin/env python3
"""Convert LitSearch HuggingFace parquet shards to PaperLoom eval JSONL.

The output schema matches `LitSearchPaperDocument`:

    paperId, title, abstractText, fullPaperText, citationCorpusIds

This script is intentionally outside the Maven build. It keeps the Java service
classpath free of parquet/Arrow dependencies while still giving the eval system
a resumable full-corpus path when dataset-server JSON pages are unavailable.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Iterable


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert LitSearch parquet shards into PaperLoom JSONL."
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="Destination JSONL path, e.g. generated/litsearch-corpus-clean-full.jsonl.",
    )
    parser.add_argument(
        "--max-papers",
        type=int,
        default=0,
        help="Optional cap for smoke conversions. 0 means all rows.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=256,
        help="Parquet batch size. Smaller values use less memory.",
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        type=Path,
        help="One or more LitSearch parquet shard files.",
    )
    return parser.parse_args()


def require_pyarrow():
    try:
        import pyarrow.parquet as pq  # type: ignore
    except ModuleNotFoundError as exc:
        raise SystemExit(
            "pyarrow is required for parquet conversion. Install it in the local "
            "Python environment, then rerun this script."
        ) from exc
    return pq


def as_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value)


def as_text_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return [str(item) for item in value if item is not None]
    return [str(value)]


def batch_rows(batch: Any) -> Iterable[dict[str, Any]]:
    data = batch.to_pydict()
    row_count = len(next(iter(data.values()), []))
    for index in range(row_count):
        yield {key: values[index] for key, values in data.items()}


def convert(args: argparse.Namespace) -> int:
    pq = require_pyarrow()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    columns = ["corpusid", "title", "abstract", "full_paper", "citations"]
    with args.output.open("w", encoding="utf-8") as writer:
        for input_path in args.inputs:
            parquet = pq.ParquetFile(input_path)
            for batch in parquet.iter_batches(batch_size=args.batch_size, columns=columns):
                for row in batch_rows(batch):
                    document = {
                        "paperId": as_text(row.get("corpusid")),
                        "title": as_text(row.get("title")),
                        "abstractText": as_text(row.get("abstract")),
                        "fullPaperText": as_text(row.get("full_paper")),
                        "citationCorpusIds": as_text_list(row.get("citations")),
                    }
                    writer.write(json.dumps(document, ensure_ascii=False))
                    writer.write("\n")
                    written += 1
                    if args.max_papers > 0 and written >= args.max_papers:
                        return written
    return written


def main() -> int:
    args = parse_args()
    written = convert(args)
    print(f"written={written}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
