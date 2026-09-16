#!/usr/bin/env python3
"""Audit GTA Brasil PC assets for an Android/SAMP-Mobile migration.

The script is deliberately conservative. It does not claim that a file is safe
for the running client merely because the extension is familiar. Instead it
classifies every file into one of the four migration buckets requested by ARL:

  compatible_direct  - payload format can be reused; it may still need to be
                       inserted/repacked into the mobile game archive.
  needs_conversion   - payload must be transformed to a mobile format/container.
  needs_port         - behaviour/configuration requires an Android-side rewrite
                       or manual semantic review.
  pc_only            - Windows/PC runtime artifact; never copied to Android.

Outputs are deterministic for a pinned source revision: JSON, CSV, Markdown,
and queue manifests. Optionally stages only compatible_direct files into a
candidate layer for later review; it never edits the production ARL DATA.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

SCHEMA_VERSION = 1
CATEGORIES = ("compatible_direct", "needs_conversion", "needs_port", "pc_only")

PC_ONLY_EXTS = {
    ".asi", ".dll", ".exe", ".com", ".bat", ".cmd", ".msi", ".ocx",
    ".sys", ".pdb", ".lib", ".exp", ".lnk", ".url", ".reg", ".vbs",
    ".ps1", ".scr", ".cpl",
}

DOC_EXTS = {
    ".md", ".pdf", ".rtf", ".doc", ".docx", ".chm", ".nfo", ".log",
}

DIRECT_EXTS = {
    ".dff", ".col", ".ifp", ".ipl", ".ide", ".dat", ".cfg",
}

TEXTURE_EXTS = {
    ".txd", ".dds", ".png", ".bmp", ".tga", ".jpg", ".jpeg", ".gif",
    ".webp",
}

CONTAINER_EXTS = {".img", ".zip", ".rar", ".7z", ".gz", ".tar"}

PORT_EXTS = {
    ".cs", ".cleo", ".sc", ".lua", ".lua3", ".js", ".fx", ".fxs",
    ".hlsl", ".glsl", ".xml", ".json", ".ini", ".toml", ".yaml", ".yml",
    ".gxt", ".fxt", ".saa",
}

DOC_NAME_MARKERS = (
    "readme", "leia-me", "leiame", "credit", "license", "licen", "manual",
    "versão", "versao", "info", "changelog", "baixe", "install",
)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def is_doc_txt(path: PurePosixPath) -> bool:
    name = path.name.casefold()
    return path.suffix.casefold() == ".txt" and any(m in name for m in DOC_NAME_MARKERS)


def classify(rel_path: str) -> tuple[str, str, str]:
    """Return (category, action, reason)."""
    p = PurePosixPath(rel_path)
    ext = p.suffix.casefold()
    parts = [part.casefold() for part in p.parts]

    if ext in PC_ONLY_EXTS:
        return (
            "pc_only",
            "exclude",
            "Windows/PC executable or plugin binary; Android cannot load this artifact.",
        )

    if ext in DOC_EXTS or is_doc_txt(p) or p.name.casefold().endswith(".ico"):
        return (
            "pc_only",
            "exclude",
            "Documentation/metadata/desktop-only file; not game payload for Android.",
        )

    if ext in {".cs", ".cleo", ".sc"} or "cleo" in parts:
        if ext in DIRECT_EXTS:
            return (
                "compatible_direct",
                "archive_repack",
                "Portable game asset; payload can be reused but must be mapped/repacked for mobile.",
            )
        if ext in TEXTURE_EXTS:
            return (
                "needs_conversion",
                "texture_to_mobile_texdb",
                "Texture source inside a CLEO package; convert to the mobile TEXDB pipeline.",
            )
        return (
            "needs_port",
            "reimplement_android",
            "CLEO/script behaviour is not directly loadable by the Android client.",
        )

    if ext == ".txd":
        return (
            "needs_conversion",
            "txd_to_mobile_texdb",
            "PC TXD cannot be copied as-is; convert textures into the mobile TEXDB layout.",
        )

    if ext in TEXTURE_EXTS:
        return (
            "needs_conversion",
            "image_to_mobile_texdb",
            "Image/texture source must be imported into an Android-compatible texture database.",
        )

    if ext in CONTAINER_EXTS:
        return (
            "needs_conversion",
            "extract_and_repack_mobile",
            "PC/archive container must be decomposed and mapped to GTA3.IMG/GTA_INT.IMG/TEXDB.",
        )

    if ext in DIRECT_EXTS:
        action = "archive_repack" if ext in {".dff", ".col", ".ifp"} else "validate_then_copy"
        return (
            "compatible_direct",
            action,
            "Payload format is a direct Android migration candidate; no texture-format conversion required.",
        )

    if ext in PORT_EXTS:
        return (
            "needs_port",
            "manual_or_android_port",
            "Script/config/shader semantics depend on the PC mod stack or require mobile validation.",
        )

    if ext in {".txt", ".csv"}:
        return (
            "needs_port",
            "manual_semantic_review",
            "Text file has ambiguous semantics; review before including it in mobile DATA.",
        )

    if ext in {".wav", ".mp3", ".ogg", ".wma", ".aac", ".m4a"}:
        return (
            "needs_conversion",
            "audio_mobile_review",
            "Audio source requires Android/mobile stream or SFX format validation/conversion.",
        )

    return (
        "needs_port",
        "manual_review",
        "Unknown or unclassified payload; conservative manual review required.",
    )


def write_json(path: Path, obj: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def human_bytes(value: int) -> str:
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    n = float(value)
    for unit in units:
        if n < 1024.0 or unit == units[-1]:
            return f"{n:.2f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0
    return f"{value} B"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path, help="Root directory to audit")
    ap.add_argument("--out", type=Path, default=Path("gta-brasil-audit"))
    ap.add_argument("--source-repo", default="pedrolecio/gta-brasil")
    ap.add_argument("--source-revision", default="unknown")
    ap.add_argument("--stage-direct", action="store_true", help="Copy only compatible_direct files to candidate-layer/")
    ap.add_argument("--no-hash", action="store_true", help="Skip SHA-256 calculation (faster, less reproducible)")
    args = ap.parse_args()

    source = args.source.resolve()
    out = args.out.resolve()
    if not source.is_dir():
        print(f"ERROR: source directory not found: {source}", file=sys.stderr)
        return 2

    if out == source or source in out.parents:
        print("ERROR: --out must not be inside the audited source tree", file=sys.stderr)
        return 2

    files: list[dict[str, object]] = []
    by_category = {c: {"count": 0, "bytes": 0} for c in CATEGORIES}
    ext_counts: dict[str, Counter[str]] = defaultdict(Counter)

    for path in sorted((p for p in source.rglob("*") if p.is_file()), key=lambda p: p.as_posix().casefold()):
        if ".git" in path.parts:
            continue
        rel = path.relative_to(source).as_posix()
        category, action, reason = classify(rel)
        size = path.stat().st_size
        digest = None if args.no_hash else sha256_file(path)
        ext = path.suffix.casefold() or "<none>"
        row = {
            "path": rel,
            "extension": ext,
            "size": size,
            "sha256": digest,
            "category": category,
            "action": action,
            "reason": reason,
        }
        files.append(row)
        by_category[category]["count"] += 1
        by_category[category]["bytes"] += size
        ext_counts[category][ext] += 1

    total_bytes = sum(int(r["size"]) for r in files)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    report = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": now,
        "source": {
            "repository": args.source_repo,
            "revision": args.source_revision,
            "root": source.name,
        },
        "policy": {
            "categories": list(CATEGORIES),
            "important": [
                "compatible_direct does not mean loose-file loading; DFF/COL/IFP still require mobile archive mapping/repack.",
                "TXD and texture images are never staged direct; they require a mobile TEXDB conversion path.",
                "ASI/DLL/EXE and other Windows binaries are excluded from Android payloads.",
                "CLEO and PC-mod behaviour is queued for Android reimplementation, not copied into the APK/DATA.",
            ],
        },
        "totals": {
            "files": len(files),
            "bytes": total_bytes,
            "by_category": by_category,
        },
        "extensions_by_category": {
            cat: dict(sorted(counter.items(), key=lambda kv: (-kv[1], kv[0])))
            for cat, counter in ext_counts.items()
        },
        "files": files,
    }

    out.mkdir(parents=True, exist_ok=True)
    write_json(out / "audit.json", report)

    with (out / "audit.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["path", "extension", "size", "sha256", "category", "action", "reason"])
        writer.writeheader()
        writer.writerows(files)

    for cat in CATEGORIES:
        write_json(out / f"{cat}.json", {
            "schema_version": SCHEMA_VERSION,
            "source_repository": args.source_repo,
            "source_revision": args.source_revision,
            "category": cat,
            "files": [r for r in files if r["category"] == cat],
        })

    write_json(out / "conversion-queue.json", {
        "source_revision": args.source_revision,
        "items": [r for r in files if r["category"] == "needs_conversion"],
    })
    write_json(out / "port-queue.json", {
        "source_revision": args.source_revision,
        "items": [r for r in files if r["category"] == "needs_port"],
    })

    if args.stage_direct:
        stage = out / "candidate-layer"
        if stage.exists():
            shutil.rmtree(stage)
        for row in files:
            if row["category"] != "compatible_direct":
                continue
            src = source / str(row["path"])
            dst = stage / str(row["path"])
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        write_json(stage / "ARL_CANDIDATE_MANIFEST.json", {
            "warning": "Review-only candidate layer. Do not merge into production DATA without mobile archive/TEXDB validation.",
            "source_repository": args.source_repo,
            "source_revision": args.source_revision,
            "files": [r for r in files if r["category"] == "compatible_direct"],
        })

    lines = [
        "# GTA Brasil → ARL Android migration audit",
        "",
        f"- Source: `{args.source_repo}` @ `{args.source_revision}`",
        f"- Files audited: **{len(files)}**",
        f"- Total payload seen: **{human_bytes(total_bytes)}**",
        "",
        "## Classification summary",
        "",
        "| Class | Files | Size | Meaning |",
        "|---|---:|---:|---|",
    ]
    meanings = {
        "compatible_direct": "Payload format can be reused; archive/path mapping may still be required.",
        "needs_conversion": "Convert/repack for Android TEXDB/GTA3.IMG/GTA_INT.IMG or mobile audio.",
        "needs_port": "Reimplement behaviour/configuration or manually review semantics.",
        "pc_only": "Exclude from Android payload.",
    }
    for cat in CATEGORIES:
        lines.append(f"| `{cat}` | {by_category[cat]['count']} | {human_bytes(int(by_category[cat]['bytes']))} | {meanings[cat]} |")

    lines += [
        "",
        "## Safety gates",
        "",
        "- `.txd` is never copied directly: it is queued for mobile TEXDB conversion.",
        "- `.asi`, `.dll`, `.exe` and Windows runtime artifacts are always excluded.",
        "- CLEO/scripts are never silently copied; their behaviour is queued for an Android port.",
        "- `.dff`/`.col`/`.ifp` are candidates only: they still need correct insertion/mapping into the mobile model archives.",
        "- This job does **not** modify the active ARL production package or release `1.0.1`.",
        "",
        "## Next machine step",
        "",
        "Use `conversion-queue.json` to feed the TEXDB/archive conversion stage; only after converted outputs validate against a known-good GTA Android base should they enter the managed ARL DATA build.",
        "",
    ]
    (out / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")

    print(json.dumps({"files": len(files), "bytes": total_bytes, "by_category": by_category}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
