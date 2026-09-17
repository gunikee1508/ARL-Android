#!/usr/bin/env python3
"""Build Phase 10 differential CDN chunks from the validated Phase 8 ARL DATA package."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path

VERSION = "phase10-gta-brasil-6417354"
TARGET_TEXTURE_BYTES = 32 * 1024 * 1024
MAX_TEXTURE_FILES = 1200
FIXED_DT = (2026, 9, 17, 0, 0, 0)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_zip(dst: Path, root: Path, files: list[dict]) -> None:
    with zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as z:
        for spec in files:
            rel = spec["path"].replace("\\", "/")
            src = root / rel
            if not src.is_file():
                raise SystemExit(f"missing source file: {rel}")
            info = zipfile.ZipInfo(rel, FIXED_DT)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with src.open("rb") as inp, z.open(info, "w", force_zip64=True) as out:
                shutil.copyfileobj(inp, out, length=1024 * 1024)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--release-base", required=True)
    args = ap.parse_args()

    source_manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    files = source_manifest.get("files") or []
    if len(files) != 8558:
        raise SystemExit(f"unexpected file count: {len(files)}")

    specs = {f["path"].replace("\\", "/"): f for f in files}
    pngs = sorted([f for f in files if f["path"].replace("\\", "/").startswith("texdb/arlbrasil/src/") and f["path"].lower().endswith(".png")], key=lambda x: x["path"])
    core = sorted([f for f in files if f not in pngs], key=lambda x: x["path"])
    if len(pngs) != 8552:
        raise SystemExit(f"unexpected PNG count: {len(pngs)}")

    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="arl10-") as td:
        root = Path(td) / "root"
        root.mkdir()
        with zipfile.ZipFile(args.package) as z:
            names = set(z.namelist())
            missing = [p for p in specs if p not in names]
            if missing:
                raise SystemExit(f"source ZIP missing files: {missing[:5]}")
            z.extractall(root)

        groups: list[tuple[str, list[dict]]] = [("core", core)]
        current: list[dict] = []
        current_bytes = 0
        part = 1
        for spec in pngs:
            b = int(spec.get("bytes", 0))
            if current and (current_bytes + b > TARGET_TEXTURE_BYTES or len(current) >= MAX_TEXTURE_FILES):
                groups.append((f"textures-{part:02d}", current))
                part += 1
                current = []
                current_bytes = 0
            current.append(spec)
            current_bytes += b
        if current:
            groups.append((f"textures-{part:02d}", current))

        packages = []
        for ident, group in groups:
            name = f"arl10-{ident}.zip"
            out = args.output / name
            write_zip(out, root, group)
            packages.append({
                "id": ident,
                "url": args.release_base.rstrip("/") + "/" + name,
                "sha256": sha256(out),
                "bytes": out.stat().st_size,
                "files": [
                    {
                        "path": f["path"].replace("\\", "/"),
                        "sha256": str(f.get("sha256", "")).lower(),
                        "bytes": int(f.get("bytes", -1)),
                    }
                    for f in group
                ],
            })
            print(f"{ident}: {len(group)} files / {out.stat().st_size} bytes")

    distribution = {
        "schema": 1,
        "version": VERSION,
        "fileCount": len(files),
        "packageCount": len(packages),
        "totalPackageBytes": sum(p["bytes"] for p in packages),
        "packages": packages,
    }
    (args.output / "distribution.json").write_text(
        json.dumps(distribution, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (args.output / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(p)}  {p.name}\n" for p in sorted(args.output.glob("*.zip"))),
        encoding="utf-8",
    )
    print(json.dumps({k: distribution[k] for k in ("version", "fileCount", "packageCount", "totalPackageBytes")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
