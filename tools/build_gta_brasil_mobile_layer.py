#!/usr/bin/env python3
"""Build a conservative GTA Brasil -> GTA SA Android asset layer.

Input is the PC GTA Brasil mod tree. Output is intentionally isolated from the
stock Rockstar archives:

  arlbrasil/arlbrasil.img              VER2 IMG containing safe, unique DFFs
  texdb/arlbrasil/arlbrasil.txt        Android text TEXDB listing (DF_UNC)
  texdb/arlbrasil/src/<texture>.png    decoded PC TXD textures
  arlbrasil/manifest.json              provenance, skips and conflicts

No ASI/DLL/EXE/CLEO payload is copied. Conflicting DFF basenames are excluded
instead of picking an arbitrary mod. Conflicting texture names are likewise
excluded so a wrong texture cannot silently override another model.

TXD parsing/decoding uses the separately installed MIT-licensed `rwfury`
package pinned by CI. PNG encoding uses Pillow.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
from collections import defaultdict
from pathlib import Path

from PIL import Image
from rwfury.txd import Txd

SECTOR = 2048
MAX_IMG_NAME_BYTES = 23


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_ascii_name(value: str) -> bool:
    if not value or '"' in value or "\r" in value or "\n" in value:
        return False
    try:
        value.encode("ascii")
    except UnicodeEncodeError:
        return False
    return True


def build_ver2(named_blobs: list[tuple[str, bytes]], out_path: Path) -> list[dict[str, int | str]]:
    entries: list[dict[str, int | str]] = []
    directory_bytes = 8 + 32 * len(named_blobs)
    data_start_sector = (directory_bytes + SECTOR - 1) // SECTOR
    cursor = data_start_sector

    for name, blob in named_blobs:
        encoded = name.lower().encode("ascii")
        if len(encoded) > MAX_IMG_NAME_BYTES:
            raise ValueError(f"IMG entry too long: {name}")
        sectors = (len(blob) + SECTOR - 1) // SECTOR
        entries.append({"name": name.lower(), "posn": cursor, "sectors": sectors, "bytes": len(blob)})
        cursor += sectors

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as fh:
        fh.write(b"VER2")
        fh.write(struct.pack("<I", len(entries)))
        for entry in entries:
            fh.write(struct.pack("<IHH", int(entry["posn"]), int(entry["sectors"]), 0))
            fh.write(str(entry["name"]).encode("ascii").ljust(24, b"\0"))
        fh.write(b"\0" * (data_start_sector * SECTOR - fh.tell()))
        for entry, (_, blob) in zip(entries, named_blobs):
            padded = int(entry["sectors"]) * SECTOR
            fh.write(blob)
            fh.write(b"\0" * (padded - len(blob)))
    return entries


def select_dffs(source: Path) -> tuple[list[tuple[str, bytes]], dict]:
    groups: dict[str, list[Path]] = defaultdict(list)
    for path in source.rglob("*"):
        if path.is_file() and path.suffix.casefold() == ".dff":
            groups[path.name.casefold()].append(path)

    selected: list[tuple[str, bytes]] = []
    conflicts = []
    skipped = []
    deduplicated = []

    for key in sorted(groups):
        paths = sorted(groups[key], key=lambda p: p.as_posix().casefold())
        name = paths[0].name
        if not safe_ascii_name(name) or len(name.encode("ascii", "ignore")) > MAX_IMG_NAME_BYTES:
            skipped.append({"name": name, "reason": "non-ascii or IMG name >23 bytes", "paths": [str(p.relative_to(source)) for p in paths]})
            continue

        by_hash: dict[str, list[Path]] = defaultdict(list)
        for path in paths:
            by_hash[sha256_file(path)].append(path)

        if len(by_hash) > 1:
            conflicts.append({
                "name": name,
                "reason": "same DFF basename has different payloads",
                "variants": [
                    {"sha256": digest, "paths": [str(p.relative_to(source)) for p in members]}
                    for digest, members in sorted(by_hash.items())
                ],
            })
            continue

        digest, members = next(iter(by_hash.items()))
        winner = members[0]
        blob = winner.read_bytes()
        selected.append((name.lower(), blob))
        if len(paths) > 1:
            deduplicated.append({
                "name": name,
                "sha256": digest,
                "kept": str(winner.relative_to(source)),
                "duplicates": [str(p.relative_to(source)) for p in paths[1:]],
            })

    selected.sort(key=lambda item: item[0].casefold())
    return selected, {
        "discovered": sum(len(v) for v in groups.values()),
        "selected": len(selected),
        "conflicts": conflicts,
        "skipped": skipped,
        "deduplicated": deduplicated,
    }


def collect_textures(source: Path) -> tuple[dict[str, dict], dict]:
    # name.casefold() -> canonical decoded texture
    textures: dict[str, dict] = {}
    conflicts: dict[str, list[dict]] = defaultdict(list)
    failures = []
    duplicates = []
    decoded_count = 0

    txds = sorted(
        (p for p in source.rglob("*") if p.is_file() and p.suffix.casefold() == ".txd"),
        key=lambda p: p.as_posix().casefold(),
    )

    for txd_path in txds:
        rel = str(txd_path.relative_to(source))
        try:
            txd = Txd.from_file(str(txd_path))
        except Exception as exc:
            failures.append({"path": rel, "error": f"parse: {type(exc).__name__}: {exc}"})
            continue

        for tex in txd.textures:
            decoded_count += 1
            name = (tex.name or "").strip()
            if not safe_ascii_name(name):
                failures.append({"path": rel, "texture": name, "error": "texture name is not safe ASCII"})
                continue
            try:
                mipmaps, has_alpha = tex.to_rgba()
                if not mipmaps:
                    raise ValueError("no decoded mipmaps")
                rgba = mipmaps[0]
                expected = int(tex.width) * int(tex.height) * 4
                if tex.width <= 0 or tex.height <= 0 or len(rgba) != expected:
                    raise ValueError(f"invalid RGBA length {len(rgba)} expected {expected}")
            except Exception as exc:
                failures.append({"path": rel, "texture": name, "error": f"decode: {type(exc).__name__}: {exc}"})
                continue

            digest = sha256_bytes(struct.pack("<II", tex.width, tex.height) + rgba)
            record = {
                "name": name,
                "width": int(tex.width),
                "height": int(tex.height),
                "has_alpha": bool(has_alpha),
                "rgba": rgba,
                "digest": digest,
                "source": rel,
            }
            key = name.casefold()

            if key in conflicts:
                if all(item["digest"] != digest for item in conflicts[key]):
                    conflicts[key].append({k: v for k, v in record.items() if k != "rgba"})
                continue

            previous = textures.get(key)
            if previous is None:
                textures[key] = record
                continue

            if previous["digest"] == digest:
                duplicates.append({"name": name, "kept": previous["source"], "duplicate": rel})
                continue

            conflicts[key].append({k: v for k, v in previous.items() if k != "rgba"})
            conflicts[key].append({k: v for k, v in record.items() if k != "rgba"})
            del textures[key]

    return textures, {
        "txd_files": len(txds),
        "decoded_entries": decoded_count,
        "selected": len(textures),
        "conflicts": [
            {"name_key": key, "variants": variants}
            for key, variants in sorted(conflicts.items())
        ],
        "failures": failures,
        "duplicates": duplicates,
    }


def write_texdb(textures: dict[str, dict], out: Path) -> list[dict]:
    src = out / "texdb" / "arlbrasil" / "src"
    src.mkdir(parents=True, exist_ok=True)
    lines = ["cat=0 name=Default onfoot=5 slow=5 fast=5 defaultformat=0 defaultstream=0"]
    manifest = []

    for key in sorted(textures):
        tex = textures[key]
        name = str(tex["name"])
        target = src / f"{name}.png"
        image = Image.frombytes("RGBA", (int(tex["width"]), int(tex["height"])), tex["rgba"])
        image.save(target, format="PNG", optimize=False)
        png_sha = sha256_file(target)
        alpha = " alphamode=2" if tex["has_alpha"] else ""
        lines.append(
            f'"{name}" width={tex["width"]} height={tex["height"]} streammode=1{alpha}'
        )
        manifest.append({
            "name": name,
            "width": tex["width"],
            "height": tex["height"],
            "has_alpha": tex["has_alpha"],
            "source": tex["source"],
            "decoded_sha256": tex["digest"],
            "png_sha256": png_sha,
        })

    listing = out / "texdb" / "arlbrasil" / "arlbrasil.txt"
    listing.parent.mkdir(parents=True, exist_ok=True)
    listing.write_text("\r\n".join(lines) + "\r\n", encoding="ascii")
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path, help="GTA Brasil mod root to convert")
    ap.add_argument("--out", type=Path, default=Path("gta-brasil-mobile-layer"))
    ap.add_argument("--source-repo", default="pedrolecio/gta-brasil")
    ap.add_argument("--source-revision", required=True)
    args = ap.parse_args()

    source = args.source.resolve()
    out = args.out.resolve()
    if not source.is_dir():
        raise SystemExit(f"source directory not found: {source}")
    if out == source or source in out.parents:
        raise SystemExit("output must not be inside source")

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    dffs, dff_report = select_dffs(source)
    if not dffs:
        raise SystemExit("no safe unique DFF candidates found")

    entries = build_ver2(dffs, out / "arlbrasil" / "arlbrasil.img")
    textures, texture_report = collect_textures(source)
    if not textures:
        raise SystemExit("no TXD textures were converted")
    tex_manifest = write_texdb(textures, out)

    manifest = {
        "schema": 1,
        "layer": "arlbrasil",
        "source": {"repository": args.source_repo, "revision": args.source_revision},
        "policy": {
            "stock_archives_modified": False,
            "windows_binaries_included": False,
            "cleo_included": False,
            "dff_conflicts": "exclude all conflicting basename variants",
            "texture_conflicts": "exclude all conflicting name variants",
        },
        "models": {**dff_report, "img_entries": entries},
        "textures": {**texture_report, "files": tex_manifest},
        "outputs": {
            "img": "arlbrasil/arlbrasil.img",
            "texdb": "texdb/arlbrasil/arlbrasil.txt",
            "texture_root": "texdb/arlbrasil/src",
        },
    }
    manifest_path = out / "arlbrasil" / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    print(json.dumps({
        "models_discovered": dff_report["discovered"],
        "models_selected": dff_report["selected"],
        "model_conflicts": len(dff_report["conflicts"]),
        "txd_files": texture_report["txd_files"],
        "textures_selected": texture_report["selected"],
        "texture_conflicts": len(texture_report["conflicts"]),
        "texture_failures": len(texture_report["failures"]),
        "img_bytes": (out / "arlbrasil" / "arlbrasil.img").stat().st_size,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
