from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml

from .models import GOLDEN_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map


def load_dataset(manifest_path: str | Path, repo_root: str | Path | None = None) -> GoldenDataset:
    manifest_path = Path(manifest_path).resolve()
    root = Path(repo_root).resolve() if repo_root is not None else _find_repo_root(manifest_path)
    manifest = _load_yaml(manifest_path)
    warnings: list[str] = []
    if manifest.get("schema_version") != GOLDEN_SCHEMA_VERSION:
        warnings.append(f"manifest schema_version is {manifest.get('schema_version')!r}")

    base = manifest_path.parent
    packs: list[JsonMap] = []
    cases: list[JsonMap] = []
    for ref in as_list(manifest.get("paper_packs")):
        pack_path = _resolve_authoring_path(base, root, child_map(ref).get("path"))
        packs.append(_load_yaml(pack_path))
    for ref in as_list(manifest.get("case_files")):
        case_path = _resolve_authoring_path(base, root, child_map(ref).get("path"))
        loaded = _load_yaml(case_path)
        cases.extend(as_list(loaded.get("cases")))

    paper_records = _index_by_id(
        (record for pack in packs for record in as_list(pack.get("paper_records"))),
        "paper_id",
    )
    anchors = _index_by_id(
        (anchor for pack in packs for anchor in as_list(pack.get("evidence_anchors"))),
        "anchor_id",
    )
    citation_edges = [edge for pack in packs for edge in as_list(pack.get("citation_edges"))]
    reading_models = _load_reading_models(root, paper_records, warnings)
    return GoldenDataset(
        root=root,
        manifest_path=manifest_path,
        manifest=manifest,
        paper_packs=packs,
        cases=cases,
        paper_records_by_id=paper_records,
        anchors_by_id=anchors,
        citation_edges=citation_edges,
        reading_models_by_paper_id=reading_models,
        load_warnings=warnings,
    )


def load_artifact_contracts(path: str | Path) -> JsonMap:
    return _load_yaml(Path(path))


def _load_yaml(path: Path) -> JsonMap:
    with path.open("r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}
    if not isinstance(data, dict):
        raise ValueError(f"YAML root must be a mapping: {path}")
    return data


def _resolve_authoring_path(base: Path, root: Path, raw_path: Any) -> Path:
    if not raw_path:
        raise ValueError("manifest reference is missing path")
    path = Path(str(raw_path))
    if path.is_absolute():
        return path
    by_manifest = base / path
    if by_manifest.exists():
        return by_manifest.resolve()
    return (root / path).resolve()


def _load_reading_models(root: Path, paper_records: dict[str, JsonMap], warnings: list[str]) -> dict[str, JsonMap]:
    models: dict[str, JsonMap] = {}
    for paper_id, record in paper_records.items():
        source_assets = child_map(record.get("source_assets"))
        raw_path = source_assets.get("reading_model_path")
        if not raw_path:
            warnings.append(f"paper {paper_id} has no reading_model_path")
            continue
        path = Path(str(raw_path))
        if not path.is_absolute():
            path = root / path
        if not path.exists():
            warnings.append(f"paper {paper_id} reading model missing: {path}")
            continue
        with path.open("r", encoding="utf-8") as handle:
            model = json.load(handle)
        if model.get("paper_id") != paper_id:
            warnings.append(f"paper {paper_id} reading model paper_id mismatch: {model.get('paper_id')}")
        models[paper_id] = model
    return models


def _index_by_id(items: Any, id_field: str) -> dict[str, JsonMap]:
    indexed: dict[str, JsonMap] = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        item_id = item.get(id_field)
        if item_id:
            indexed[str(item_id)] = item
    return indexed


def _find_repo_root(path: Path) -> Path:
    current = path.resolve()
    if current.is_file():
        current = current.parent
    for candidate in [current, *current.parents]:
        if (candidate / ".git").exists() or (candidate / "research").exists():
            return candidate
    return Path.cwd().resolve()
