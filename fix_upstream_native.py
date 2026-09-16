#!/usr/bin/env python3
from pathlib import Path
import argparse


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8-sig")


def write(p: Path, text: str) -> None:
    p.write_text(text, encoding="utf-8", newline="\n")


def replace_required(path: Path, old: str, new: str) -> None:
    text = read(path)
    if new in text:
        print(f"[ARL NATIVE FIX] já corrigido: {path}")
        return
    if old not in text:
        raise SystemExit(f"[ARL NATIVE FIX] padrão não encontrado em {path}: {old}")
    write(path, text.replace(old, new, 1))
    print(f"[ARL NATIVE FIX] corrigido: {old} -> {new}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo")
    root = Path(ap.parse_args().repo).resolve()

    gui_h = root / "app/src/main/cpp/samp/gui/gui.h"
    if not gui_h.exists():
        raise SystemExit("[ARL NATIVE FIX] gui.h não encontrado")

    # Linux/Android CI é case-sensitive. O upstream inclui playerTabList.h,
    # porém o arquivo real versionado é playertablist.h.
    replace_required(
        gui_h,
        '#include "samp_widgets/playerTabList.h"',
        '#include "samp_widgets/playertablist.h"'
    )

    print("[ARL NATIVE FIX] auditoria v1 concluída")


if __name__ == "__main__":
    main()
