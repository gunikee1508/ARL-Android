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

    replace_required(gui_h,
        '#include "samp_widgets/playerTabList.h"',
        '#include "samp_widgets/playertablist.h"')
    replace_required(rgba_cpp,
        '#include "RGBA.h"',
        '#include "rgba.h"')
    replace_required(network_h,
        '#include "../vendor/RakNet/BitStream.h"',
        '#include "../vendor/raknet/BitStream.h"')
    replace_required(network_h,
        '#include "../vendor/RakNet/RakClient.h"',
        '#include "../vendor/raknet/RakClient.h"')

    # The original NvFOpen fallback crashes because the game's native
    # AssetManager is null. Read external data, including case-only aliases.
    replace_required(hooks_cpp,
        '#include <EGL/egl.h>',
        '#include <EGL/egl.h>\n#include <dirent.h>\n#include <strings.h>\n#include <limits.h>')
    replace_required(hooks_cpp,
        'stFile* NvFOpen(const char* r0, const char* r1, int r2, int r3)',
        '''static FILE* ArlOpenCaseInsensitive(const char* absolute)
{
    if (!absolute || absolute[0] != '/') return nullptr;
    char current[PATH_MAX] = "/";
    const char* part = absolute + 1;
    while (*part) {
        if (*part == '/') { ++part; continue; }
        const char* slash = strchr(part, '/');
        size_t len = slash ? (size_t)(slash - part) : strlen(part);
        if (!len || len >= NAME_MAX ||
            (len == 1 && part[0] == '.') ||
            (len == 2 && part[0] == '.' && part[1] == '.'))
            return nullptr;
        DIR* dir = opendir(current);
        if (!dir) return nullptr;
        char name[NAME_MAX + 1] = {};
        struct dirent* entry;
        while ((entry = readdir(dir))) {
            if (strlen(entry->d_name) == len && !strncasecmp(entry->d_name, part, len)) {
                snprintf(name, sizeof(name), "%s", entry->d_name);
                break;
            }
        }
        closedir(dir);
        if (!name[0]) return nullptr;
        size_t used = strlen(current);
        if (used + strlen(name) + 2 >= sizeof(current)) return nullptr;
        if (used > 1) strcat(current, "/");
        strcat(current, name);
        part = slash ? slash + 1 : part + len;
    }
    return fopen(current, "rb");
}

stFile* NvFOpen(const char* r0, const char* r1, int r2, int r3)''')
    replace_required(hooks_cpp,
        '    FILE *f  = fopen(path, "rb");',
        '    FILE *f  = fopen(path, "rb");\n'
        '    if (!f) f = ArlOpenCaseInsensitive(path);')

    print("[ARL NATIVE FIX] auditoria v5 concluída")


if __name__ == "__main__":
    main()
