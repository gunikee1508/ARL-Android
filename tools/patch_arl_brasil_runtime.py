#!/usr/bin/env python3
"""Enable the optional ARL Brasil mobile asset layer in a patched SAMP-Mobile tree.

This is intentionally separate from apply_arl_phase1.py while Phase 8 is under
validation. The ARL native source itself is copied by the normal overlay; this
script only wires it into the upstream main.cpp at stable anchors.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def die(msg: str) -> None:
    raise SystemExit("[ARL BRASIL PATCH] ERRO: " + msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def insert_once(text: str, marker: str, replacement: str, already: str, label: str) -> str:
    if already in text:
        return text
    if marker not in text:
        die(f"âncora ausente para {label}")
    return text.replace(marker, replacement, 1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", type=Path)
    args = ap.parse_args()

    root = args.repo.resolve()
    maincpp = root / "app/src/main/cpp/samp/main.cpp"
    header = root / "app/src/main/cpp/samp/arl/ArlBrasilLayer.h"
    source = root / "app/src/main/cpp/samp/arl/ArlBrasilLayer.cpp"

    for required in (maincpp, header, source):
        if not required.is_file():
            die(f"arquivo obrigatório ausente: {required.relative_to(root)}")

    text = read(maincpp)

    text = insert_once(
        text,
        '#include "settings.h"',
        '#include "settings.h"\n#include "arl/ArlBrasilLayer.h"',
        '#include "arl/ArlBrasilLayer.h"',
        "include nativo",
    )

    text = insert_once(
        text,
        '\tInstallSpecialHooks();\n\tApplyPatches_level0();',
        '\tInstallSpecialHooks();\n\tArlBrasilLayer::InstallHooks();\n\tApplyPatches_level0();',
        'ArlBrasilLayer::InstallHooks();',
        "instalação do hook TEXDB",
    )

    text = insert_once(
        text,
        '\tDoInitStuff();\n\n\tif (bDebug)',
        '\tDoInitStuff();\n\tArlBrasilLayer::Tick();\n\n\tif (bDebug)',
        'ArlBrasilLayer::Tick();',
        "tick da camada",
    )

    write(maincpp, text)

    final = read(maincpp)
    checks = {
        "header": final.count('#include "arl/ArlBrasilLayer.h"') == 1,
        "install": final.count('ArlBrasilLayer::InstallHooks();') == 1,
        "tick": final.count('ArlBrasilLayer::Tick();') == 1,
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        die("auditoria pós-patch falhou: " + ", ".join(failed))

    print("[ARL BRASIL PATCH] OK: runtime IMG/TEXDB ligado ao SAMP-Mobile")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
