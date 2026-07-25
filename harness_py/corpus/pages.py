from __future__ import annotations

# 页面解析与 anchor 模糊匹配的工具函数。
#
# - parse_positive_page / page_matches：把 raw page 值（int / float / 字符串）统一成
#   "正整数" 或 None。这是 InMemoryTools 的 _matches_identity_hints 等 anchor 匹配
#   与 _build_documents 走同一约定；同输入永远同一输出，避免 1.0 vs 1 错位。
# - normalize_text / contains_normalized_phrase：剥去 inline XML 标签、转小写、只保留
#   [a-zA-Z0-9_] 形态的 token，再做 tolerant 子串匹配。InMemoryTools 的 anchor 文本
#   对齐依赖这一层；只有同 source 的 _matches_token_phrase 缓存才能命中。
# - _matches_token_phrase 接受 1-3 词窗内的字符级 mismatch：纸版 anchor 在跨页 / 排版
#   重排时 token 之间会插入 hyphen 或 whitespace，需要小幅度 fuzz。

import re
from functools import lru_cache


def parse_positive_page(value: object) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        page = value
    elif isinstance(value, float):
        if not value.is_integer():
            return None
        page = int(value)
    elif isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        try:
            page = int(stripped)
        except ValueError:
            return None
    else:
        return None
    return page if page > 0 else None


def page_matches(expected: object, actual: object) -> bool:
    """Match pages with the same strict positive-integer rule used by authoring validation."""
    expected_page = parse_positive_page(expected)
    return expected_page is not None and parse_positive_page(actual) == expected_page


def normalize_text(value: str) -> str:
    without_markup = re.sub(r"<[^>]+>", " ", value)
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", without_markup.casefold()))


def contains_normalized_phrase(text: str, phrase: str) -> bool:
    if not text or not phrase:
        return False
    if f" {phrase} " in f" {text} ":
        return True
    document_tokens = text.split()
    phrase_tokens = phrase.split()
    if not document_tokens or not phrase_tokens:
        return False
    return any(
        _matches_token_phrase(document_tokens, phrase_tokens, start)
        for start in range(len(document_tokens))
    )


def _matches_token_phrase(
    document_tokens: list[str],
    phrase_tokens: list[str],
    start: int,
) -> bool:
    @lru_cache(maxsize=None)
    def matches(document_index: int, phrase_index: int) -> bool:
        if phrase_index == len(phrase_tokens):
            return True
        if document_index == len(document_tokens):
            return False
        for document_width in range(
            1,
            min(3, len(document_tokens) - document_index) + 1,
        ):
            document_value = "".join(
                document_tokens[document_index:document_index + document_width]
            )
            for phrase_width in range(
                1,
                min(3, len(phrase_tokens) - phrase_index) + 1,
            ):
                phrase_value = "".join(
                    phrase_tokens[phrase_index:phrase_index + phrase_width]
                )
                if document_value != phrase_value:
                    continue
                if matches(
                    document_index + document_width,
                    phrase_index + phrase_width,
                ):
                    return True
        return False

    return matches(start, 0)
