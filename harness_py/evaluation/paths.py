from __future__ import annotations

from pathlib import Path


def resolve_authoring_path(manifest_path: str | Path, raw_path: object) -> Path:
    if not raw_path:
        raise ValueError("manifest reference is missing path")
    path = Path(str(raw_path))
    if path.is_absolute():
        raise ValueError("manifest references must be relative to the Golden data root")
    golden_root = Path(manifest_path).resolve().parent
    resolved = (golden_root / path).resolve()
    try:
        resolved.relative_to(golden_root)
    except ValueError as error:
        raise ValueError("manifest references must stay inside the Golden data root") from error
    return resolved


def resolve_pack_data_dir(
    manifest_path: str | Path,
    data_dir: object,
) -> Path:
    value = str(data_dir or "").strip()
    if not value:
        raise ValueError("paper pack data_dir is required")
    path = Path(value)
    if path.is_absolute():
        raise ValueError("paper pack data_dir must be relative to the Golden manifest")
    golden_root = Path(manifest_path).resolve().parent
    resolved = (golden_root / path).resolve()
    try:
        resolved.relative_to(golden_root)
    except ValueError as error:
        raise ValueError("paper pack data_dir must stay inside the Golden data root") from error
    return resolved


def reading_model_path(
    manifest_path: str | Path,
    data_dir: object,
    paper_id: str,
) -> Path:
    return (
        resolve_pack_data_dir(manifest_path, data_dir)
        / "reading-models"
        / f"{paper_id}.reading-model.json"
    )


def display_repo_path(path: str | Path, repo_root: str | Path) -> str:
    resolved = Path(path).resolve()
    root = Path(repo_root).resolve()
    try:
        return resolved.relative_to(root).as_posix()
    except ValueError:
        return str(resolved)
