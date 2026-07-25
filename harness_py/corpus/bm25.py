from __future__ import annotations

# BM25 评分与统计的纯函数模块。
#
# 这层只做无状态计算。ReadingCorpusTools 走 Java 委派（生产路径），
# InMemoryTools 走 BM25 离线评分（单元测试 / 评测夹具）—— 两者都从这里 import 同一组函数。
# 任何 doc/collection 状态、tokenization 决策、分数阈值都只此一处。

import math
import re
from collections import Counter
from dataclasses import dataclass


BM25_K1 = 1.2
BM25_B = 0.75


@dataclass(frozen=True)
class Bm25Statistics:
    # 评分所需的语料级统计：文档数、平均长度、词项 df。
    # 任何 BM25 实现的常量（k1, b）走模块顶部的 BM25_K1 / BM25_B，不在这里。
    document_count: int
    average_length: float
    document_frequency: dict[str, int]


def statistics(documents: list[list[str]]) -> Bm25Statistics:
    # 统计 df 与语料级平均长度。空文档返回 0 平均值（避免除零）。
    document_frequency: Counter[str] = Counter()
    total_length = 0
    for tokens in documents:
        document_frequency.update(set(tokens))
        total_length += len(tokens)
    document_count = len(documents)
    return Bm25Statistics(
        document_count=document_count,
        average_length=(total_length / document_count) if document_count else 0.0,
        document_frequency=dict(document_frequency),
    )


def _idf(token: str, s: Bm25Statistics) -> float:
    # 标准 BM25+1 idf，防止负权重 + log(0)。
    document_frequency = s.document_frequency.get(token, 0)
    return math.log(
        1 + (s.document_count - document_frequency + 0.5) / (document_frequency + 0.5)
    )


def score(query_tokens: list[str], document_tokens: list[str], s: Bm25Statistics) -> float:
    # 标准 BM25 公式（带 +1 平滑）。空查询 / 空文档 / 空语料直接 0。
    if not query_tokens or not document_tokens or not s.document_count:
        return 0.0
    term_frequency = Counter(document_tokens)
    document_length = len(document_tokens)
    average_length = s.average_length or 1.0
    total = 0.0
    for token in set(query_tokens):
        frequency = term_frequency.get(token, 0)
        if not frequency:
            continue
        denominator = frequency + BM25_K1 * (
            1 - BM25_B + BM25_B * document_length / average_length
        )
        total += _idf(token, s) * frequency * (BM25_K1 + 1) / denominator
    return total


def query_term_weights(query_tokens: set[str], s: Bm25Statistics) -> dict[str, float]:
    # 给每个查询词项一个 idf 权重，供选择候选时按"未覆盖增益"打分。
    return {token: _idf(token, s) for token in query_tokens}


def token_overlap_ratio(query_tokens: set[str], text_tokens: set[str]) -> float:
    # 简易覆盖率：query 中出现在 text 里的比例。空查询返回 0。
    if not query_tokens:
        return 0.0
    return len(query_tokens & text_tokens) / len(query_tokens)


_TOKEN_PATTERN = re.compile(r"[a-zA-Z0-9_]+")


def tokenize(value: str) -> list[str]:
    # 全文一致的小写 token 化；与 parser 输出格式无关。
    return _TOKEN_PATTERN.findall(value.lower())
