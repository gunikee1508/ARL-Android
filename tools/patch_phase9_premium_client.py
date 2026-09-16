#!/usr/bin/env python3
"""Phase 9 ARL premium client patch.

Safe goals:
- wire the offset-free ARL runtime diagnostics into SAMP-Mobile;
- modernize native SA-MP UI colors/spacing using ARL gold/graphite palette;
- modernize the native in-game loading progress bar;
- never ship or replace Rockstar libGTASA.so and never patch unknown offsets here.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def die(msg: str) -> None:
    raise SystemExit("[ARL PHASE9] ERRO: " + msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        die(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)


def patch_main(path: Path) -> None:
    text = read(path)
    text = replace_once(
        text,
        '#include "arl/ArlBrasilLayer.h"',
        '#include "arl/ArlBrasilLayer.h"\n#include "arl/ArlRuntimeGuard.h"',
        "include RuntimeGuard",
    )
    text = replace_once(
        text,
        '\tCHook::InitHookStuff();\n\tInstallSpecialHooks();',
        '\tCHook::InitHookStuff();\n\tArlRuntimeGuard::BootSnapshot();\n\tInstallSpecialHooks();',
        "boot snapshot",
    )
    text = replace_once(
        text,
        '\t\tpGame->ToggleThePassingOfTime(false);',
        '\t\tpGame->ToggleThePassingOfTime(false);\n\t\tArlRuntimeGuard::MarkGameReady();',
        "game ready marker",
    )
    write(path, text)


def patch_ui_settings(path: Path) -> None:
    text = read(path)
    replacements = [
        ('float UISettings::m_fontSize = 30.0f;//26.0f;//24.0f;',
         'float UISettings::m_fontSize = 28.0f; // ARL: cleaner premium scale',
         'font size'),
        ('ImVec2 UISettings::m_chatPos = ImVec2(100.0f, 10.0f);',
         'ImVec2 UISettings::m_chatPos = ImVec2(24.0f, 18.0f);',
         'chat position'),
        ('ImVec2 UISettings::m_chatSize = ImVec2(400.0f, 0.0f);',
         'ImVec2 UISettings::m_chatSize = ImVec2(430.0f, 0.0f);',
         'chat width'),
        ('ImVec2 UISettings::m_chatItemSize = ImVec2(400.0f, 13.0f);',
         'ImVec2 UISettings::m_chatItemSize = ImVec2(430.0f, 14.0f);',
         'chat item'),
        ('ImColor UISettings::m_buttonColor = ImColor(0.11f, 0.11f, 0.11f, 0.80f);',
         'ImColor UISettings::m_buttonColor = ImColor(18, 21, 25, 230);',
         'button base color'),
        ('ImColor UISettings::m_buttonFocusedColor = ImColor(0x64, 0x95, 0xED);/*ImColor(119, 4, 4, 255);*/ //ImColor(80, 80, 80);',
         'ImColor UISettings::m_buttonFocusedColor = ImColor(216, 187, 87, 255); // ARL gold',
         'button focused color'),
        ('ImColor UISettings::m_keyboardBackgroundColor = ImColor(0, 0, 0, 150);',
         'ImColor UISettings::m_keyboardBackgroundColor = ImColor(7, 9, 12, 235);',
         'keyboard background'),
        ('ImColor UISettings::m_dialogBackgroundColor = ImColor(0, 0, 0, 200);',
         'ImColor UISettings::m_dialogBackgroundColor = ImColor(9, 11, 14, 238);',
         'dialog background'),
        ('ImColor UISettings::m_dialogTitleBackgroundColor = ImColor(0, 0, 0, 200);/*ImColor(0xF5, 0x91, 0x32);*/// ImColor(50, 50, 50, 255);',
         'ImColor UISettings::m_dialogTitleBackgroundColor = ImColor(32, 27, 16, 248); // graphite + gold family',
         'dialog title background'),
    ]
    for old, new, label in replacements:
        text = replace_once(text, old, new, label)
    write(path, text)


def patch_native_splash(path: Path) -> None:
    text = read(path)
    old = '''\tm_progressBar = new ProgressBar(ImColor(0.0f, 0.0f, 0.0f),\n\t\tImColor(190, 0, 0), ImColor(255, 100, 0),\n\t\tImColor(255, 100, 0), ImColor(190, 0, 0));'''
    new = '''\t// ARL Phase 9: no stock red/orange loading bar. Keep the in-game boot\n\t// consistent with the launcher: graphite background + warm premium gold.\n\tm_progressBar = new ProgressBar(ImColor(10, 12, 15, 255),\n\t\tImColor(122, 98, 38, 255), ImColor(216, 187, 87, 255),\n\t\tImColor(216, 187, 87, 255), ImColor(122, 98, 38, 255));'''
    text = replace_once(text, old, new, "native splash palette")
    write(path, text)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", type=Path)
    args = ap.parse_args()
    root = args.repo.resolve()

    required = [
        root / "app/src/main/cpp/samp/main.cpp",
        root / "app/src/main/cpp/samp/arl/ArlRuntimeGuard.h",
        root / "app/src/main/cpp/samp/arl/ArlRuntimeGuard.cpp",
        root / "app/src/main/cpp/samp/gui/uisettings.cpp",
        root / "app/src/main/cpp/samp/gui/samp_widgets/splashscreen.cpp",
    ]
    for path in required:
        if not path.is_file():
            die("arquivo obrigatório ausente: " + str(path.relative_to(root)))

    patch_main(required[0])
    patch_ui_settings(required[3])
    patch_native_splash(required[4])

    maincpp = read(required[0])
    ui = read(required[3])
    splash = read(required[4])
    checks = [
        maincpp.count('#include "arl/ArlRuntimeGuard.h"') == 1,
        maincpp.count('ArlRuntimeGuard::BootSnapshot();') == 1,
        maincpp.count('ArlRuntimeGuard::MarkGameReady();') == 1,
        'ImColor(216, 187, 87, 255)' in ui,
        'ImColor(7, 9, 12, 235)' in ui,
        'ARL Phase 9' in splash,
    ]
    if not all(checks):
        die("auditoria pós-patch falhou")

    print("[ARL PHASE9] OK: Runtime Guard + UI nativa premium aplicados")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
