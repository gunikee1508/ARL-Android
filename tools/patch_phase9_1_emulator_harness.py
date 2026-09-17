#!/usr/bin/env python3
from pathlib import Path
import argparse
import re


def die(msg: str) -> None:
    raise SystemExit("[ARL PHASE9.1] ERRO: " + msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", type=Path)
    args = ap.parse_args()
    root = args.repo.resolve()

    gradle = root / "app/build.gradle"
    main = root / "app/src/main/java/com/samp/mobile/launcher/MainActivity.java"
    if not gradle.is_file() or not main.is_file():
        die("build.gradle/MainActivity.java ausente")

    g = read(gradle)

    # Emulator CI is x86_64 while the real ARL runtime is ARM. This harness is
    # intentionally launcher-only: no native GTA/SA-MP library is packaged or loaded.
    g2, n_ndk = re.subn(
        r"\n\s*ndk\s*\{\s*abiFilters\s+['\"][^\n]+\n\s*\}\s*",
        "\n",
        g,
        count=1,
        flags=re.S,
    )
    if n_ndk != 1:
        die(f"bloco ndk abiFilters inesperado: {n_ndk}")
    g = g2

    g2, n_cmake = re.subn(
        r"\n\s*externalNativeBuild\s*\{\s*cmake\s*\{\s*path\s+['\"]src/main/cpp/CMakeLists\.txt['\"]\s*\}\s*\}\s*",
        "\n",
        g,
        count=1,
        flags=re.S,
    )
    if n_cmake != 1:
        die(f"bloco externalNativeBuild inesperado: {n_cmake}")
    g = g2

    old_excludes = "excludes += ['META-INF/*']"
    new_excludes = "excludes += ['META-INF/*', '**/*.so']"
    if new_excludes not in g:
        if old_excludes not in g:
            die("packagingOptions jniLibs inesperado")
        g = g.replace(old_excludes, new_excludes, 1)

    debug_marker = "        debug {\n            debuggable false"
    if debug_marker in g:
        g = g.replace(debug_marker, "        debug {\n            debuggable true", 1)
    elif "        debug {\n            debuggable true" not in g:
        die("buildType debug inesperado")

    write(gradle, g)

    m = read(main)
    old = "        startActivity(new Intent(this, SAMP.class));"
    new = '''        // Phase 9.1 emulator harness: exercise every launcher/play prerequisite,
        // but never initialize GTASA/SAMP native classes on the x86_64 CI emulator.
        getSharedPreferences("arl_emulator_harness", MODE_PRIVATE)
                .edit()
                .putBoolean("play_gate_ok", true)
                .putString("nickname", nick)
                .apply();
        Toast.makeText(this, "ARL HARNESS • PLAY GATE OK", Toast.LENGTH_LONG).show();'''
    if new not in m:
        if m.count(old) != 1:
            die(f"start SAMP marker esperado 1x, encontrado {m.count(old)}")
        m = m.replace(old, new, 1)
    write(main, m)

    g = read(gradle)
    m = read(main)
    checks = [
        "**/*.so" in g,
        "externalNativeBuild" not in g,
        "abiFilters 'armeabi-v7a', 'arm64-v8a'" not in g,
        "debuggable true" in g,
        'putBoolean("play_gate_ok", true)' in m,
        "startActivity(new Intent(this, SAMP.class));" not in m,
    ]
    if not all(checks):
        die("auditoria pós-patch falhou")

    print("[ARL PHASE9.1] OK: launcher-only x86_64 emulator harness aplicado")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
