#!/usr/bin/env python3
"""Activate one published ARL DATA release in launcher.json."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.parse import quote


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--launcher", type=Path, required=True)
    ap.add_argument("--metadata", type=Path, required=True)
    ap.add_argument("--repository", required=True, help="owner/repo")
    ap.add_argument("--tag", required=True)
    args = ap.parse_args()

    launcher = json.loads(args.launcher.read_text(encoding="utf-8"))
    meta = json.loads(args.metadata.read_text(encoding="utf-8"))

    version = str(meta["version"]).strip()
    package = meta["package"]
    manifest = meta["manifest"]
    if not version:
        raise SystemExit("metadata version is empty")
    if "/" not in args.repository:
        raise SystemExit("repository must be owner/repo")

    base = (
        f"https://github.com/{args.repository}/releases/download/"
        f"{quote(args.tag, safe='._-')}/"
    )

    client = launcher.setdefault("client", {})
    data = client.setdefault("data", {})
    data.clear()
    data.update({
        "version": version,
        "url": base + quote(package["file"], safe="._-"),
        "sha256": package["sha256"],
        "bytes": int(package["bytes"]),
        "manifestUrl": base + quote(manifest["file"], safe="._-"),
    })

    tmp = args.launcher.with_suffix(args.launcher.suffix + ".tmp")
    tmp.write_text(json.dumps(launcher, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(args.launcher)

    print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
