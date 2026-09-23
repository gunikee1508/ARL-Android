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
    rgba_cpp = root / "app/src/main/cpp/samp/game/rgba.cpp"
    network_h = root / "app/src/main/cpp/samp/voice_new/Network.h"
    hooks_cpp = root / "app/src/main/cpp/samp/game/hooks.cpp"

    for required in (gui_h, rgba_cpp, network_h, hooks_cpp):
        if not required.exists():
            raise SystemExit(f"[ARL NATIVE FIX] arquivo não encontrado: {required}")

    # Linux/Android CI é case-sensitive. O upstream inclui playerTabList.h,
    # porém o arquivo real versionado é playertablist.h.
    replace_required(
        gui_h,
        '#include "samp_widgets/playerTabList.h"',
        '#include "samp_widgets/playertablist.h"'
    )

    # Mesmo problema em game/rgba.cpp: o arquivo real é rgba.h (minúsculo).
    replace_required(
        rgba_cpp,
        '#include "RGBA.h"',
        '#include "rgba.h"'
    )

    # A pasta real do RakNet nesta branch é vendor/raknet (minúsculo),
    # enquanto voice_new/Network.h usa vendor/RakNet.
    replace_required(
        network_h,
        '#include "../vendor/RakNet/BitStream.h"',
        '#include "../vendor/raknet/BitStream.h"'
    )
    replace_required(
        network_h,
        '#include "../vendor/RakNet/RakClient.h"',
        '#include "../vendor/raknet/RakClient.h"'
    )

    # The original NvFOpen can read files packaged in APK assets. Upstream's
    # redirect discarded that fallback entirely, so a fresh installation
    # crashes in OS_FileRead when opening Text/AMERICAN.GXT (the file exists in
    # assets, but not in the downloaded external GTA cache).
    replace_required(
        hooks_cpp,
        'stFile* NvFOpen(const char* r0, const char* r1, int r2, int r3)',
        'stFile* (*NvFOpen_original)(const char*, const char*, int, int) = nullptr;\n\n'
        'stFile* NvFOpen(const char* r0, const char* r1, int r2, int r3)'
    )
    replace_required(
        hooks_cpp,
        '        FLog("NVFOpen hook | Error: file not found (%s)", path);\n'
        '        free(st);\n'
        '        return nullptr;',
        '        free(st);\n'
        '        if (NvFOpen_original) return NvFOpen_original(r0, r1, r2, r3);\n'
        '        FLog("NVFOpen hook | Error: file not found (%s)", path);\n'
        '        return nullptr;'
    )
    replace_required(
        hooks_cpp,
        '    CHook::Redirect("_Z7NvFOpenPKcS0_bb", &NvFOpen);',
        '    CHook::InlineHook("_Z7NvFOpenPKcS0_bb", &NvFOpen, &NvFOpen_original);'
    )

    print("[ARL NATIVE FIX] auditoria v4 concluída")


if __name__ == "__main__":
    main()
