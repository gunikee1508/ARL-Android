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

The converter is intentionally streaming-oriented: selected DFF payloads are
copied into the IMG archive in chunks and decoded textures are written to
temporary PNG files one at a time. This keeps peak RAM bounded on GitHub
Actions even for large GTA Brasil trees.
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
    if "/" in value or "\\" in value or ":" in value:
        return False
    try:
        value.encode("ascii")
    except UnicodeEncodeError:
        return False
    return True


def build_ver2(named_files: list[tuple[str, Path]], out_path: Path) -> list[dict[str, int | str]]:
    entries: list[dict[str, int | str]] = []
    directory_bytes = 8 + 32 * len(named_files)
    data_start_sector = (directory_bytes + SECTOR - 1) // SECTOR
    cursor = data_start_sector

    for name, path in named_files:
        encoded = name.lower().encode("ascii")
        if len(encoded) > MAX_IMG_NAME_BYTES:
            raise ValueError(f"IMG entry too long: {name}")
        size = path.stat().st_size
        sectors = (size + SECTOR - 1) // SECTOR
        entries.append(
            {
                "name": name.lower(),
                "posn": cursor,
                "sectors": sectors,
                "bytes": size,
            }
        )
        cursor += sectors

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as fh:
        fh.write(b"VER2")
        fh.write(struct.pack("<I", len(entries)))
        for entry in entries:
            fh.write(
                struct.pack(
                    "<IHH",
                    int(entry["posn"]),
                    int(entry["sectors"]),
                    0,
                )
            )
            fh.write(str(entry["name"]).encode("ascii").ljust(24, b"\0"))

        fh.write(b"\0" * (data_start_sector * SECTOR - fh.tell()))

        for entry, (_, path) in zip(entries, named_files):
            written = 0
            with path.open("rb") as src:
                for chunk in iter(lambda: src.read(1024 * 1024), b""):
                    fh.write(chunk)
                    written += len(chunk)

            padded = int(entry["sectors"]) * SECTOR
            if written != int(entry["bytes"]):
                raise IOError(f"size changed while reading {path}")
            if padded > written:
                fh.write(b"\0" * (padded - written))

    return entries


def select_dffs(source: Path) -> tuple[list[tuple[str, Path]], dict]:
    groups: dict[str, list[Path]] = defaultdict(list)
    for path in source.rglob("*"):
        if path.is_file() and path.suffix.casefold() == ".dff":
            groups[path.name.casefold()].append(path)

    selected: list[tuple[str, Path]] = []
    conflicts = []
    skipped = []
    deduplicated = []

    for key in sorted(groups):
        paths = sorted(groups[key], key=lambda p: p.as_posix().casefold())
        name = paths[0].name
        if not safe_ascii_name(name) or len(name.encode("ascii", "ignore")) > MAX_IMG_NAME_BYTES:
            skipped.append(
                {
                    "name": name,
                    "reason": "non-ascii/unsafe or IMG name >23 bytes",
                    "paths": [str(p.relative_to(source)) for p in paths],
                }
            )
            continue

        by_hash: dict[str, list[Path]] = defaultdict(list)
        for path in paths:
            by_hash[sha256_file(path)].append(path)

        if len(by_hash) > 1:
            conflicts.append(
                {
                    "name": name,
                    "reason": "same DFF basename has different payloads",
                    "variants": [
                        {
                            "sha256": digest,
                            "paths": [str(p.relative_to(source)) for p in members],
                        }
                        for digest, members in sorted(by_hash.items())
                    ],
                }
            )
            continue

        digest, members = next(iter(by_hash.items()))
        winner = members[0]
        selected.append((name.lower(), winner))
        if len(paths) > 1:
            deduplicated.append(
                {
                    "name": name,
                    "sha256": digest,
                    "kept": str(winner.relative_to(source)),
                    "duplicates": [str(p.relative_to(source)) for p in paths[1:]],
                }
            )

    selected.sort(key=lambda item: item[0].casefold())
    return selected, {
        "discovered": sum(len(v) for v in groups.values()),
        "selected": len(selected),
        "conflicts": conflicts,
        "skipped": skipped,
        "deduplicated": deduplicated,
    }


def public_texture_record(record: dict) -> dict:
    return {k: v for k, v in record.items() if not k.startswith("_")}


def collect_textures(source: Path, out: Path) -> tuple[dict[str, dict], dict]:
    textures: dict[str, dict] = {}
    conflicts: dict[str, list[dict]] = defaultdict(list)
    failures = []
    duplicates = []
    decoded_count = 0

    candidate_dir = out / "_texture_candidates"
    candidate_dir.mkdir(parents=True, exist_ok=True)

    txds = sorted(
        (p for p in source.rglob("*") if p.is_file() and p.suffix.casefold() == ".txd"),
        key=lambda p: p.as_posix().casefold(),
    )

    for txd_index, txd_path in enumerate(txds, start=1):
        rel = str(txd_path.relative_to(source))
        if txd_index == 1 or txd_index % 25 == 0 or txd_index == len(txds):
            print(f"[textures] TXD {txd_index}/{len(txds)}: {rel}", flush=True)

        try:
            txd = Txd.from_file(str(txd_path))
        except Exception as exc:
            failures.append({"path": rel, "error": f"parse: {type(exc).__name__}: {exc}"})
            continue

        for tex in txd.textures:
            decoded_count += 1
            name = (tex.name or "").strip()
            if not safe_ascii_name(name):
                failures.append(
                    {
                        "path": rel,
                        "texture": name,
                        "error": "texture name is not safe ASCII filename",
                    }
                )
                continue

            try:
                mipmaps, has_alpha = tex.to_rgba()
                if not mipmaps:
                    raise ValueError("no decoded mipmaps")
                rgba = mipmaps[0]
                width = int(tex.width)
                height = int(tex.height)
                expected = width * height * 4
                if width <= 0 or height <= 0 or len(rgba) != expected:
                    raise ValueError(f"invalid RGBA length {len(rgba)} expected {expected}")
            except Exception as exc:
                failures.append(
                    {
                        "path": rel,
                        "texture": name,
                        "error": f"decode: {type(exc).__name__}: {exc}",
                    }
                )
                continue

            digest = sha256_bytes(struct.pack("<II", width, height) + rgba)
            key = name.casefold()
            base_record = {
                "name": name,
                "width": width,
                "height": height,
                "has_alpha": bool(has_alpha),
                "digest": digest,
                "source": rel,
            }

            if key in conflicts:
                if all(item["digest"] != digest for item in conflicts[key]):
                    conflicts[key].append(dict(base_record))
                del rgba
                continue

            previous = textures.get(key)
            if previous is not None:
                if previous["digest"] == digest:
                    duplicates.append(
                        {
                            "name": name,
                            "kept": previous["source"],
                            "duplicate": rel,
                        }
                    )
                    del rgba
                    continue

                old_candidate = Path(previous["_candidate"])
                old_candidate.unlink(missing_ok=True)
                conflicts[key].append(public_texture_record(previous))
                conflicts[key].append(dict(base_record))
                del textures[key]
                del rgba
                continue

            candidate = candidate_dir / f"{hashlib.sha256(key.encode('ascii')).hexdigest()}.png"
            image = Image.frombytes("RGBA", (width, height), rgba)
            image.save(candidate, format="PNG", optimize=False)
            del image
            del rgba

            textures[key] = {
                **base_record,
                "_candidate": str(candidate),
            }

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
        candidate = Path(tex["_candidate"])
        shutil.move(str(candidate), target)

        png_sha = sha256_file(target)
        alpha = " alphamode=2" if tex["has_alpha"] else ""
        lines.append(
            f'"{name}" width={tex["width"]} height={tex["height"]} streammode=1{alpha}'
        )
        manifest.append(
            {
                "name": name,
                "width": tex["width"],
                "height": tex["height"],
                "has_alpha": tex["has_alpha"],
                "source": tex["source"],
                "decoded_sha256": tex["digest"],
                "png_sha256": png_sha,
            }
        )

    listing = out / "texdb" / "arlbrasil" / "arlbrasil.txt"
    listing.parent.mkdir(parents=True, exist_ok=True)
    listing.write_text("\r\n".join(lines) + "\r\n", encoding="ascii")

    shutil.rmtree(out / "_texture_candidates", ignore_errors=True)
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

    print("[phase8] selecting DFFs without loading them all into RAM", flush=True)
    dffs, dff_report = select_dffs(source)
    if not dffs:
        raise SystemExit("no safe unique DFF candidates found")

    print(f"[phase8] writing IMG from {len(dffs)} streamed DFF files", flush=True)
    entries = build_ver2(dffs, out / "arlbrasil" / "arlbrasil.img")

    print("[phase8] converting TXDs one at a time", flush=True)
    textures, texture_report = collect_textures(source, out)
    if not textures:
        raise SystemExit("no TXD textures were converted")
    tex_manifest = write_texdb(textures, out)

    manifest = {
        "schema": 1,
        "layer": "arlbrasil",
        "source": {
            "repository": args.source_repo,
            "revision": args.source_revision,
        },
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
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    print(
        json.dumps(
            {
                "models_discovered": dff_report["discovered"],
                "models_selected": dff_report["selected"],
                "model_conflicts": len(dff_report["conflicts"]),
                "txd_files": texture_report["txd_files"],
                "textures_selected": texture_report["selected"],
                "texture_conflicts": len(texture_report["conflicts"]),
                "texture_failures": len(texture_report["failures"]),
                "img_bytes": (out / "arlbrasil" / "arlbrasil.img").stat().st_size,
            },
            ensure_ascii=False,
            indent=2,
        ),
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
