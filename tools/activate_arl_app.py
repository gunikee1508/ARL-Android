#!/usr/bin/env python3
"""Activate a signed ARL launcher APK release in launcher.json."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote

BUF = 1024 * 1024


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(BUF)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--launcher", required=True, type=Path)
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--repository", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--version-code", required=True, type=int)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--min-version-code", required=True, type=int)
    ap.add_argument("--mandatory", action="store_true")
    args = ap.parse_args()

    if args.version_code <= 0 or args.min_version_code <= 0:
        raise SystemExit("version codes must be positive")
    if args.min_version_code > args.version_code:
        raise SystemExit("min-version-code cannot exceed latest version-code")
    if not args.apk.is_file() or args.apk.stat().st_size <= 0:
        raise SystemExit("APK missing or empty")
    if "/" not in args.repository:
        raise SystemExit("repository must be owner/repo")

    launcher = json.loads(args.launcher.read_text(encoding="utf-8"))
    base = (
        f"https://github.com/{args.repository}/releases/download/"
        f"{quote(args.tag, safe='._-')}/"
    )
    app = launcher.setdefault("app", {})
    app.clear()
    app.update({
        "latestVersionCode": args.version_code,
        "minVersionCode": args.min_version_code,
        "versionName": args.version_name.strip(),
        "apkUrl": base + quote(args.apk.name, safe="._-"),
        "sha256": sha256_file(args.apk),
        "bytes": args.apk.stat().st_size,
        "mandatory": bool(args.mandatory),
    })

    tmp = args.launcher.with_suffix(args.launcher.suffix + ".tmp")
    tmp.write_text(json.dumps(launcher, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(args.launcher)
    print(json.dumps(app, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
