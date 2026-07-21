from __future__ import annotations

import json
from pathlib import Path
import yaml

from .golden_case import CITATION_POLICIES, OUTCOMES
from .paths import display_repo_path, reading_model_path, resolve_authoring_path
from ..core.models import (
    GOLDEN_CLAIM_SCHEMA_VERSION,
    GOLDEN_CASE_SCHEMA_VERSION,
    GOLDEN_SCHEMA_VERSION,
    PAPER_PACK_SCHEMA_VERSION,
    GoldenDataset,
    JsonMap,
    as_list,
    child_map,
)
from ..corpus.pages import parse_positive_page


REMOVED_CASE_FIELDS = {
    "question",
    "expected_result",
    "expected_intent",
    "expected_retrieval_plan",
    "corpus_scope",
    "gold_evidence",
    "gold_claims",
    "answer_contract",
    "required_trace",
    "compatibility_projection",
}

REMOVED_EXPECTATION_FIELDS = {"papers", "evidence", "claims"}
SUPPORTED_LOCATION_PREFIXES = ("page_ref_", "section_ref_", "table_ref_", "figure_ref_")


def load_dataset(manifest_path: str | Path, repo_root: str | Path | None = None) -> GoldenDataset:
    manifest_path = Path(manifest_path).resolve()
    root = Path(repo_root).resolve() if repo_root is not None else _find_repo_root(manifest_path)
    manifest = _load_yaml(manifest_path)
    if manifest.get("schema_version") != GOLDEN_SCHEMA_VERSION:
        raise ValueError(f"unsupported manifest schema: {manifest.get('schema_version')!r}")
    if not isinstance(manifest.get("dataset_id"), str) or not manifest["dataset_id"].strip():
        raise ValueError("manifest requires a non-empty dataset_id")

    packs = [
        _load_yaml(resolve_authoring_path(manifest_path, path))
        for path in as_list(manifest.get("paper_packs"))
    ]
    cases: list[JsonMap] = []
    for path in as_list(manifest.get("case_files")):
        loaded = _load_yaml(resolve_authoring_path(manifest_path, path))
        cases.extend(child_map(case) for case in as_list(loaded.get("cases")))

    _validate_packs(packs)
    claim_documents = [
        _load_yaml(resolve_authoring_path(manifest_path, path))
        for path in as_list(manifest.get("claim_files"))
    ]
    claims = _normalized_claims(claim_documents, packs)
    _validate_cases(cases, packs, claims)
    paper_records = _normalized_paper_records(packs, manifest_path, root)
    anchors = _normalized_anchors(packs)
    citation_edges = [edge for pack in packs for edge in as_list(pack.get("citation_edges"))]
    warnings: list[str] = []
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
        claims_by_id=claims,
        load_warnings=warnings,
    )


def load_artifact_contracts(path: str | Path) -> JsonMap:
    return _load_yaml(Path(path))


def _validate_packs(packs: list[JsonMap]) -> None:
    pack_ids: set[str] = set()
    paper_ids: set[str] = set()
    anchor_ids: set[str] = set()
    for pack in packs:
        if pack.get("schema_version") != PAPER_PACK_SCHEMA_VERSION:
            raise ValueError(f"unsupported paper pack schema: {pack.get('schema_version')!r}")
        pack_id = str(pack.get("id") or "")
        if not pack_id or pack_id in pack_ids:
            raise ValueError(f"invalid or duplicate paper pack id: {pack_id!r}")
        pack_ids.add(pack_id)
        if not str(pack.get("data_dir") or "").strip():
            raise ValueError(f"paper pack {pack_id} is missing data_dir")
        papers = [child_map(paper) for paper in as_list(pack.get("papers"))]
        local_papers = {str(paper.get("id")) for paper in papers if paper.get("id")}
        if len(local_papers) != len(papers):
            raise ValueError(f"paper pack {pack_id} has missing or duplicate paper ids")
        overlap = paper_ids & local_papers
        if overlap:
            raise ValueError(f"duplicate paper ids across packs: {sorted(overlap)}")
        paper_ids.update(local_papers)
        local_anchor_ids: set[str] = set()
        for raw_anchor in as_list(pack.get("anchors")):
            anchor = child_map(raw_anchor)
            anchor_id = str(anchor.get("id") or "")
            paper_id = str(anchor.get("paper") or "")
            if not anchor_id or anchor_id in anchor_ids:
                raise ValueError(f"invalid or duplicate anchor id: {anchor_id!r}")
            if paper_id not in local_papers:
                raise ValueError(f"anchor {anchor_id} references unknown paper {paper_id}")
            if not str(anchor.get("quote") or "").strip():
                raise ValueError(f"anchor {anchor_id} is missing quote")
            # 作者标注和运行时匹配必须共享同一个正页码约束。
            if parse_positive_page(anchor.get("page")) is None:
                raise ValueError(f"anchor {anchor_id} requires a positive parseable page")
            anchor_ids.add(anchor_id)
            local_anchor_ids.add(anchor_id)
        for raw_edge in as_list(pack.get("citation_edges")):
            edge = child_map(raw_edge)
            if edge.get("from_paper_id") not in local_papers or edge.get("to_paper_id") not in local_papers:
                raise ValueError(f"paper pack {pack_id} has a citation edge with an unknown paper")
            evidence_anchor_id = str(edge.get("evidence_anchor_id") or "")
            if evidence_anchor_id and evidence_anchor_id not in local_anchor_ids:
                raise ValueError(
                    f"paper pack {pack_id} citation edge references unknown anchor {evidence_anchor_id}"
                )


def _validate_cases(
    cases: list[JsonMap],
    packs: list[JsonMap],
    claims_by_id: dict[str, JsonMap],
) -> None:
    packs_by_id = {str(pack["id"]): pack for pack in packs}
    papers_by_pack = {
        pack_id: {str(child_map(paper).get("id")) for paper in as_list(pack.get("papers"))}
        for pack_id, pack in packs_by_id.items()
    }
    case_ids: set[str] = set()
    for case in cases:
        case_id = str(case.get("id") or "")
        if case.get("schema_version") != GOLDEN_CASE_SCHEMA_VERSION:
            raise ValueError(f"case {case_id} has unsupported schema {case.get('schema_version')!r}")
        if not case_id or case_id in case_ids:
            raise ValueError(f"invalid or duplicate case id: {case_id!r}")
        case_ids.add(case_id)
        removed = sorted(REMOVED_CASE_FIELDS & set(case))
        if removed:
            raise ValueError(f"case {case_id} contains removed v1 fields: {removed}")
        if not str(case.get("paradigm") or "").strip():
            raise ValueError(f"case {case_id} is missing paradigm")
        pack_id = str(case.get("paper_pack") or "")
        if pack_id not in packs_by_id:
            raise ValueError(f"case {case_id} references unknown paper pack {pack_id}")
        messages = [child_map(item) for item in as_list(case.get("messages"))]
        if not messages or messages[-1].get("role") != "user":
            raise ValueError(f"case {case_id} must end with a user message")
        for message in messages:
            if message.get("role") not in {"user", "assistant"}:
                raise ValueError(f"case {case_id} has invalid message role {message.get('role')!r}")
            if not str(message.get("content") or "").strip():
                raise ValueError(f"case {case_id} has an empty message")
        expectation = child_map(case.get("expect"))
        removed_expectations = sorted(REMOVED_EXPECTATION_FIELDS & set(expectation))
        if removed_expectations:
            raise ValueError(
                f"case {case_id} contains removed v2 expectations: {removed_expectations}"
            )
        if expectation.get("outcome") not in OUTCOMES:
            raise ValueError(f"case {case_id} has invalid outcome {expectation.get('outcome')!r}")
        if expectation.get("citations", "optional") not in CITATION_POLICIES:
            raise ValueError(f"case {case_id} has invalid citation policy")
        required_claims = [
            str(claim_id).strip()
            for claim_id in as_list(expectation.get("required_claims"))
            if str(claim_id).strip()
        ]
        if not required_claims:
            raise ValueError(f"case {case_id} requires at least one Golden claim")
        if len(set(required_claims)) != len(required_claims):
            raise ValueError(f"case {case_id} contains duplicate required claims")
        for claim_id in required_claims:
            claim = claims_by_id.get(claim_id)
            if claim is None:
                raise ValueError(f"case {case_id} references unknown claim {claim_id}")
            claim_papers = {
                str(child_map(item).get("paper_id") or "")
                for item in as_list(claim.get("required_evidence"))
            }
            if not claim_papers <= papers_by_pack[pack_id]:
                raise ValueError(
                    f"case {case_id} claim {claim_id} uses papers outside pack {pack_id}"
                )


def _normalized_claims(
    documents: list[JsonMap],
    packs: list[JsonMap],
) -> dict[str, JsonMap]:
    if not documents:
        raise ValueError("manifest requires at least one claim_files entry")
    known_papers = {
        str(child_map(paper).get("id"))
        for pack in packs
        for paper in as_list(pack.get("papers"))
        if child_map(paper).get("id")
    }
    claims: dict[str, JsonMap] = {}
    for document in documents:
        if document.get("schema_version") != GOLDEN_CLAIM_SCHEMA_VERSION:
            raise ValueError(
                f"unsupported Golden claim schema: {document.get('schema_version')!r}"
            )
        raw_claims = document.get("claims")
        if not isinstance(raw_claims, dict) or not raw_claims:
            raise ValueError("Golden claim file requires a non-empty claims mapping")
        for raw_claim_id, raw_claim in raw_claims.items():
            claim_id = str(raw_claim_id).strip()
            claim = child_map(raw_claim)
            if not claim_id or claim_id in claims:
                raise ValueError(f"invalid or duplicate Golden claim id: {claim_id!r}")
            statement = str(claim.get("statement") or "").strip()
            if not statement:
                raise ValueError(f"Golden claim {claim_id} requires a statement")
            requirements: list[JsonMap] = []
            for index, raw_requirement in enumerate(
                as_list(claim.get("required_evidence")), start=1
            ):
                requirement = child_map(raw_requirement)
                paper_id = str(requirement.get("paper_id") or "").strip()
                if paper_id not in known_papers:
                    raise ValueError(
                        f"Golden claim {claim_id} requirement {index} references unknown paper {paper_id}"
                    )
                locations = [
                    str(item).strip()
                    for item in as_list(requirement.get("accepted_locations"))
                    if str(item).strip()
                ]
                if not locations or len(locations) != len(set(locations)):
                    raise ValueError(
                        f"Golden claim {claim_id} requirement {index} requires unique accepted locations"
                    )
                invalid = [
                    location
                    for location in locations
                    if not location.startswith(SUPPORTED_LOCATION_PREFIXES)
                ]
                if invalid:
                    raise ValueError(
                        f"Golden claim {claim_id} has unsupported locations: {invalid}"
                    )
                requirements.append({
                    "paper_id": paper_id,
                    "accepted_locations": locations,
                })
            if not requirements:
                raise ValueError(f"Golden claim {claim_id} requires evidence")
            requirement_papers = [str(item["paper_id"]) for item in requirements]
            if len(requirement_papers) != len(set(requirement_papers)):
                raise ValueError(
                    f"Golden claim {claim_id} has duplicate paper evidence requirements"
                )
            forbidden = [
                str(item).strip()
                for item in as_list(claim.get("forbidden_paper_ids"))
                if str(item).strip()
            ]
            unknown_forbidden = sorted(set(forbidden) - known_papers)
            if unknown_forbidden:
                raise ValueError(
                    f"Golden claim {claim_id} has unknown forbidden papers: {unknown_forbidden}"
                )
            required_papers = {str(item["paper_id"]) for item in requirements}
            overlap = sorted(required_papers & set(forbidden))
            if overlap:
                raise ValueError(
                    f"Golden claim {claim_id} both requires and forbids papers: {overlap}"
                )
            fact_keys = [
                str(item).strip()
                for item in as_list(claim.get("fact_keys"))
                if str(item).strip()
            ]
            if len(fact_keys) != len(set(fact_keys)):
                raise ValueError(f"Golden claim {claim_id} has duplicate fact keys")
            claims[claim_id] = {
                "claim_id": claim_id,
                "statement": statement,
                "required_evidence": requirements,
                "forbidden_paper_ids": list(dict.fromkeys(forbidden)),
                "fact_keys": fact_keys,
            }
    return claims


def _normalized_paper_records(
    packs: list[JsonMap],
    manifest_path: Path,
    root: Path,
) -> dict[str, JsonMap]:
    records: dict[str, JsonMap] = {}
    for pack in packs:
        data_dir = str(pack["data_dir"])
        for raw_paper in as_list(pack.get("papers")):
            paper = child_map(raw_paper)
            paper_id = str(paper["id"])
            records[paper_id] = {
                "paper_id": paper_id,
                "role": paper.get("role"),
                "identity": {
                    key: paper.get(key)
                    for key in (
                        "title", "authors", "year", "venue", "doi", "arxiv_id", "version_label"
                    )
                    if paper.get(key) is not None
                },
                "source_assets": {
                    "source_url": paper.get("source_url"),
                    "reading_model_path": display_repo_path(
                        reading_model_path(manifest_path, data_dir, paper_id),
                        root,
                    ),
                },
            }
    return records


def _normalized_anchors(packs: list[JsonMap]) -> dict[str, JsonMap]:
    anchors: dict[str, JsonMap] = {}
    for pack in packs:
        for raw_anchor in as_list(pack.get("anchors")):
            anchor = child_map(raw_anchor)
            anchor_id = str(anchor["id"])
            anchors[anchor_id] = {
                "anchor_id": anchor_id,
                "paper_id": anchor.get("paper"),
                "role": anchor.get("role", "supports"),
                "element": {
                    "type": anchor.get("type", "paragraph"),
                    "page": parse_positive_page(anchor.get("page")),
                    "section": anchor.get("section"),
                },
                "selector": {"exact_text": anchor.get("quote")},
                "normalized_facts": child_map(anchor.get("facts")),
            }
    return anchors


def _load_yaml(path: Path) -> JsonMap:
    with path.open("r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}
    if not isinstance(data, dict):
        raise ValueError(f"YAML root must be a mapping: {path}")
    return data


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
def _find_repo_root(path: Path) -> Path:
    current = path.resolve()
    if current.is_file():
        current = current.parent
    for candidate in [current, *current.parents]:
        if (candidate / ".git").exists() or (candidate / "research").exists():
            return candidate
    return Path.cwd().resolve()
