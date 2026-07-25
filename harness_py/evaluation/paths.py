from __future__ import annotations

from pathlib import Path


def _resolve_under(
    manifest_path: str | Path,
    raw_path: object,
    *,
    label: str,
) -> Path:
    # 解析 manifest 相对路径：拒绝绝对路径，限定在 manifest 所在目录里。
    value = str(raw_path or "").strip()
    if not value:
        raise ValueError(f"{label} is required")
    path = Path(value)
    if path.is_absolute():
        raise ValueError(f"{label} must be relative to the Golden manifest")
    golden_root = Path(manifest_path).resolve().parent
    resolved = (golden_root / path).resolve()
    try:
        resolved.relative_to(golden_root)
    except ValueError as error:
        raise ValueError(f"{label} must stay inside the Golden data root") from error
    return resolved


def resolve_authoring_path(manifest_path: str | Path, raw_path: object) -> Path:
    # 论文/案例/锚点等 manifest 引用的相对路径解析。
    return _resolve_under(manifest_path, raw_path, label="manifest reference")


def resolve_pack_data_dir(manifest_path: str | Path, data_dir: object) -> Path:
    # 论文包数据目录的相对路径解析。
    return _resolve_under(manifest_path, data_dir, label="paper pack data_dir")


def reading_model_path(manifest_path: str | Path, data_dir: object, paper_id: str) -> Path:
    # 给定论文包的数据目录与论文 id，定位该论文的 reading model JSON。
    return (
        resolve_pack_data_dir(manifest_path, data_dir)
        / "reading-models"
        / f"{paper_id}.reading-model.json"
    )


def display_repo_path(path: str | Path, repo_root: str | Path) -> str:
    # 把绝对路径转成 repo 内的相对路径字符串，越界则原样返回。
    try:
        return Path(path).resolve().relative_to(Path(repo_root).resolve()).as_posix()
    except ValueError:
        return str(Path(path).resolve())
