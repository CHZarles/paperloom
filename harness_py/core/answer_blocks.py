from __future__ import annotations

import re

from .models import JsonMap, as_list


NON_MATERIAL_UNCITED_BLOCK_KINDS = frozenset({"heading", "table_header"})
_NUMERIC_CITATION = re.compile(r"(?<!\[)\[(\d+)\]")
_EVIDENCE_CITATION = re.compile(r"\[\[(ev_[A-Za-z0-9_-]+)\]\]")
_LEGACY_EVIDENCE_CITATION = re.compile(r"(?<!\[)\[(ev_[A-Za-z0-9_-]+)\](?!\])")
_LIST_ITEM = re.compile(r"^\s*(?:[-+*]|\d+[.)])\s+")
_TABLE_SEPARATOR = re.compile(r"^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*$")


def answer_blocks(answer: JsonMap) -> tuple[list[JsonMap], list[str]]:
    markdown = _answer_body(str(answer.get("markdown") or ""))
    cited_ids = [
        str(item).strip()
        for item in as_list(answer.get("cited_evidence_ids"))
        if str(item).strip()
    ]
    raw_blocks = _markdown_blocks(markdown)
    blocks: list[JsonMap] = []
    errors: list[str] = []
    for index, raw_block in enumerate(raw_blocks, start=1):
        raw = str(raw_block["text"])
        evidence_ids: list[str] = []
        for direct in [
            *_EVIDENCE_CITATION.findall(raw),
            *_LEGACY_EVIDENCE_CITATION.findall(raw),
        ]:
            if direct not in evidence_ids:
                evidence_ids.append(direct)
        for raw_number in _NUMERIC_CITATION.findall(raw):
            number = int(raw_number)
            if number < 1 or number > len(cited_ids):
                errors.append(f"CITATION_NUMBER_OUT_OF_RANGE:{number}")
                continue
            evidence_id = cited_ids[number - 1]
            if evidence_id not in evidence_ids:
                evidence_ids.append(evidence_id)
        text = _EVIDENCE_CITATION.sub("", raw)
        text = _LEGACY_EVIDENCE_CITATION.sub("", text)
        text = _NUMERIC_CITATION.sub("", text)
        text = re.sub(r"\s+", " ", text).strip(" |\t")
        if not text and not evidence_ids:
            continue
        blocks.append({
            "block_id": f"block_{index}",
            "kind": raw_block["kind"],
            "text": text,
            "evidence_ids": evidence_ids,
        })
    return blocks, list(dict.fromkeys(errors))


def uncited_material_blocks(answer: JsonMap) -> list[JsonMap]:
    blocks, _errors = answer_blocks(answer)
    return [
        block
        for block in blocks
        if not as_list(block.get("evidence_ids"))
        and str(block.get("kind") or "") not in NON_MATERIAL_UNCITED_BLOCK_KINDS
    ]


def _answer_body(markdown: str) -> str:
    marker = re.search(r"(?:^|\n)Sources\s*\n", markdown)
    return markdown[:marker.start()].rstrip() if marker else markdown


def _markdown_blocks(markdown: str) -> list[JsonMap]:
    blocks: list[JsonMap] = []
    paragraph: list[str] = []

    def flush() -> None:
        if paragraph:
            blocks.append({
                "kind": "paragraph",
                "text": "\n".join(paragraph).strip(),
            })
            paragraph.clear()

    for line in markdown.splitlines():
        stripped = line.strip()
        if not stripped:
            flush()
            continue
        if _TABLE_SEPARATOR.match(stripped):
            flush()
            if blocks and "|" in str(blocks[-1]["text"]):
                blocks[-1]["kind"] = "table_header"
            continue
        if _LIST_ITEM.match(line):
            flush()
            blocks.append({"kind": "list_item", "text": stripped})
            continue
        if "|" in stripped and stripped.startswith("|"):
            flush()
            blocks.append({"kind": "table_row", "text": stripped})
            continue
        if stripped.startswith("#"):
            flush()
            blocks.append({
                "kind": "heading",
                "text": stripped.lstrip("#").strip(),
            })
            continue
        paragraph.append(stripped)
    flush()
    return blocks
