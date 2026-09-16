#!/usr/bin/env python3
"""Build an ARL Android DATA release package and full per-file SHA-256 manifest."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile

BUF = 1024 * 1024
EXACT_EXCLUDES = {
    "settings.ini",
    "SAMP/settings.ini",
    "samp_log.txt",
    "SAMP/samp_log.txt",
    "svlog.txt",
    "SAMP/svlog.txt",
    "gta_sa.set",
    "gtasatelem.set",
    ".DS_Store",
    "Thumbs.db",
}
PREFIX_EXCLUDES = (".git/", "download/", "SAMP/download/")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(BUF)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def safe_version(value: str) -> str:
    value = value.strip()
    if not value or any(c in value for c in "/\\\r\n\t"):
        raise SystemExit("invalid version")
    return value


def clean_rel(path: str) -> str:
    p = path.replace("\\", "/").lstrip("/")
    pp = PurePosixPath(p)
    if not p or any(part in ("", ".", "..") for part in pp.parts):
        raise ValueError(f"unsafe path: {path!r}")
    return pp.as_posix()


def excluded(rel: str) -> bool:
    if rel in EXACT_EXCLUDES:
        return True
    if Path(rel).name in {".DS_Store", "Thumbs.db"}:
        return True
    return any(rel.startswith(prefix) for prefix in PREFIX_EXCLUDES)


def safe_extract(source: Path, dest: Path) -> None:
    root = dest.resolve()
    with zipfile.ZipFile(source) as zf:
        for info in zf.infolist():
            rel = clean_rel(info.filename.rstrip("/")) if info.filename.rstrip("/") else ""
            if not rel:
                continue
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode and stat.S_ISLNK(mode):
                raise SystemExit(f"symlink not allowed in source ZIP: {rel}")
            out = (dest / rel).resolve()
            if root != out and root not in out.parents:
                raise SystemExit(f"ZIP path escapes destination: {rel}")
            if info.is_dir():
                out.mkdir(parents=True, exist_ok=True)
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, out.open("wb") as dst:
                shutil.copyfileobj(src, dst, BUF)


def maybe_strip_single_root(root: Path, enabled: bool) -> Path:
    if not enabled:
        return root
    visible = [p for p in root.iterdir() if p.name not in {"__MACOSX"}]
    if len(visible) == 1 and visible[0].is_dir():
        return visible[0]
    return root


def iter_files(root: Path):
    files = []
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = clean_rel(p.relative_to(root).as_posix())
        if excluded(rel):
            continue
        files.append((rel, p))
    files.sort(key=lambda item: item[0].casefold())
    return files


def build(source: Path, version: str, output: Path, strip_single_root: bool) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    version = safe_version(version)

    with tempfile.TemporaryDirectory(prefix="arl-data-") as td:
        tmp = Path(td)
        if source.is_dir():
            root = source.resolve()
        elif source.is_file() and zipfile.is_zipfile(source):
            unpacked = tmp / "source"
            unpacked.mkdir()
            safe_extract(source, unpacked)
            root = maybe_strip_single_root(unpacked, strip_single_root)
        else:
            raise SystemExit("source must be a directory or ZIP")

        entries = iter_files(root)
        if not entries:
            raise SystemExit("DATA source has no distributable files")

        stem = f"arl-data-v{version}"
        package_path = output / f"{stem}.zip"
        manifest_path = output / f"{stem}.manifest.json"
        meta_path = output / f"{stem}.meta.json"

        manifest_files = []
        with zipfile.ZipFile(package_path, "w", allowZip64=True) as zf:
            for rel, src in entries:
                digest = hashlib.sha256()
                zi = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = 0o100644 << 16
                zi.create_system = 3
                size = 0
                with src.open("rb") as inp, zf.open(zi, "w", force_zip64=True) as out:
                    while True:
                        chunk = inp.read(BUF)
                        if not chunk:
                            break
                        digest.update(chunk)
                        size += len(chunk)
                        out.write(chunk)
                manifest_files.append({
                    "path": rel,
                    "bytes": size,
                    "sha256": digest.hexdigest(),
                })

        package_bytes = package_path.stat().st_size
        package_sha = sha256_file(package_path)
        generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()

        manifest = {
            "schema": 1,
            "version": version,
            "generatedAt": generated,
            "fileCount": len(manifest_files),
            "files": manifest_files,
        }
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        metadata = {
            "schema": 1,
            "version": version,
            "generatedAt": generated,
            "fileCount": len(manifest_files),
            "package": {
                "file": package_path.name,
                "bytes": package_bytes,
                "sha256": package_sha,
            },
            "manifest": {
                "file": manifest_path.name,
                "bytes": manifest_path.stat().st_size,
                "sha256": sha256_file(manifest_path),
            },
        }
        meta_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    return metadata


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True, type=Path)
    ap.add_argument("--version", required=True)
    ap.add_argument("--output", type=Path, default=Path("dist-data"))
    ap.add_argument("--no-strip-single-root", action="store_true")
    args = ap.parse_args()
    build(args.source, args.version, args.output, not args.no_strip_single_root)


if __name__ == "__main__":
    main()
