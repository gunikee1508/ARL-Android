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
    splash = root / "app/src/main/java/com/samp/mobile/launcher/SplashActivity.java"
    smoke = Path(__file__).resolve().with_name("run_phase9_1_emulator_test.sh")
    if not gradle.is_file() or not main.is_file() or not splash.is_file() or not smoke.is_file():
        die("build.gradle/MainActivity.java/SplashActivity.java/smoke test ausente")

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
                .commit();
        Toast.makeText(this, "ARL HARNESS • PLAY GATE OK", Toast.LENGTH_LONG).show();'''
    if new not in m:
        if m.count(old) != 1:
            die(f"start SAMP marker esperado 1x, encontrado {m.count(old)}")
        m = m.replace(old, new, 1)
    write(main, m)

    # The fake GTA base must be created by the app itself. Creating
    # /sdcard/Android/data/<package>/files via `adb shell mkdir` on Android 14
    # can leave the external-files root with shell ownership/context, which makes
    # the real embedded installer unable to create sibling directories such as
    # `arlbrasil`. This code exists only in the generated Phase 9.1 harness APK.
    s = read(splash)
    if "import java.io.File;" not in s:
        marker = "import java.util.ArrayList;"
        if marker not in s:
            die("imports SplashActivity inesperados")
        s = s.replace(marker, "import java.io.File;\nimport java.util.ArrayList;", 1)

    call = "        ensureEmulatorHarnessBase();\n        handler.post(this::bootstrap);"
    if call not in s:
        old_call = "        handler.post(this::bootstrap);"
        if s.count(old_call) != 1:
            die(f"bootstrap marker esperado 1x, encontrado {s.count(old_call)}")
        s = s.replace(old_call, call, 1)

    method = '''
    /** Phase 9.1 test only: create structural GTA-base sentinels as the app UID. */
    private void ensureEmulatorHarnessBase() {
        try {
            File root = getExternalFilesDir(null);
            if (root == null) return;
            File texdb = new File(root, "texdb");
            File data = new File(root, "data");
            if ((!texdb.mkdirs() && !texdb.isDirectory()) ||
                    (!data.mkdirs() && !data.isDirectory())) {
                throw new IllegalStateException("falha criando fake base app-owned");
            }
            File marker = new File(root, "ARL_EMULATOR_FAKE_BASE.txt");
            if (!marker.exists()) marker.createNewFile();
        } catch (Exception e) {
            Toast.makeText(this, "ARL HARNESS • FAKE BASE FALHOU: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
'''
    if "private void ensureEmulatorHarnessBase()" not in s:
        insert_before = "    private void setSplashStatus(String title, String detail) {"
        if insert_before not in s:
            die("setSplashStatus marker ausente")
        s = s.replace(insert_before, method + "\n" + insert_before, 1)
    write(splash, s)

    # Android 14 may still be animating/tearing down the IME after KEYCODE_BACK.
    # The previous smoke test tapped JOGAR during that transition. Patch the
    # checked-out smoke script to verify the entered nickname, wait for the IME,
    # re-dump the UI and calculate the current button position before tapping.
    t = read(smoke)
    old_smoke = '''read _ NX NY < <(grep '^nick ' "$OUT/tap-points.txt")
read _ PX PY < <(grep '^play ' "$OUT/tap-points.txt")
adb shell input tap "$NX" "$NY"
adb shell input keyevent KEYCODE_MOVE_END || true
for _ in $(seq 1 24); do adb shell input keyevent KEYCODE_DEL >/dev/null || true; done
adb shell input text 'ARL_Test_01'
adb shell input keyevent KEYCODE_BACK || true
sleep 1
adb shell input tap "$PX" "$PY"
sleep 3
'''
    new_smoke = '''read _ NX NY < <(grep '^nick ' "$OUT/tap-points.txt")
adb shell input tap "$NX" "$NY"
sleep 1
adb shell input keyevent KEYCODE_MOVE_END || true
for _ in $(seq 1 30); do adb shell input keyevent KEYCODE_DEL >/dev/null || true; done
adb shell input text 'ARL_Test_01'
sleep 1

adb shell uiautomator dump /sdcard/arl-after-nickname.xml >/dev/null
adb pull /sdcard/arl-after-nickname.xml "$OUT/window-after-nickname.xml" >/dev/null
python3 - "$OUT/window-after-nickname.xml" "$OUT/nickname-state.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
src, out = sys.argv[1:]
root = ET.parse(src).getroot()
node = next((n for n in root.iter('node') if n.attrib.get('resource-id') == 'com.samp.mobile:id/arl_nickname'), None)
if node is None:
    raise SystemExit('nickname control missing after input')
text = node.attrib.get('text', '')
with open(out, 'w', encoding='utf-8') as f:
    f.write(text + '\\n')
if text != 'ARL_Test_01':
    raise SystemExit('unexpected nickname after input: ' + repr(text))
PY
log "PASS: nickname ARL_Test_01 confirmado no EditText"

adb shell input keyevent KEYCODE_BACK || true
sleep 3
FOUND_PLAY_AFTER_IME=0
for attempt in 1 2 3 4; do
  dump_play_ui
  if play_controls_visible; then
    FOUND_PLAY_AFTER_IME=1
    break
  fi
  sleep 1
done
[[ "$FOUND_PLAY_AFTER_IME" = 1 ]] || { log "FAIL: JOGAR não reapareceu após fechar teclado"; exit 1; }

python3 - "$OUT/window-play.xml" "$OUT/play-after-ime.txt" <<'PY'
import re, sys, xml.etree.ElementTree as ET
src, out = sys.argv[1:]
root = ET.parse(src).getroot()
node = next((n for n in root.iter('node') if n.attrib.get('resource-id') == 'com.samp.mobile:id/arl_play'), None)
if node is None:
    raise SystemExit('play control missing after IME close')
if node.attrib.get('enabled') != 'true' or node.attrib.get('clickable') != 'true':
    raise SystemExit('play control not enabled/clickable: ' + str(node.attrib))
m = re.match(r'\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]', node.attrib.get('bounds', ''))
if not m:
    raise SystemExit('invalid play bounds')
x1,y1,x2,y2 = map(int,m.groups())
with open(out,'w') as f:
    f.write(f'play {(x1+x2)//2} {(y1+y2)//2}\\n')
PY
read _ PX PY < "$OUT/play-after-ime.txt"
adb exec-out screencap -p > "$OUT/launcher-before-play.png"
adb shell input tap "$PX" "$PY"
sleep 3

HARNESS_PREFS="$(adb shell run-as "$PKG" cat shared_prefs/arl_emulator_harness.xml 2>/dev/null | tr -d '\\r' || true)"
if ! grep -q 'play_gate_ok' <<<"$HARNESS_PREFS"; then
  log "INFO: primeiro tap não registrou; redetectando JOGAR e tentando uma vez após IME estabilizado"
  sleep 2
  dump_play_ui
  python3 - "$OUT/window-play.xml" "$OUT/play-retry.txt" <<'PY'
import re, sys, xml.etree.ElementTree as ET
src, out = sys.argv[1:]
root = ET.parse(src).getroot()
node = next((n for n in root.iter('node') if n.attrib.get('resource-id') == 'com.samp.mobile:id/arl_play' and n.attrib.get('enabled') == 'true'), None)
if node is None:
    raise SystemExit('enabled play control missing on retry')
m = re.match(r'\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]', node.attrib.get('bounds', ''))
if not m:
    raise SystemExit('invalid retry play bounds')
x1,y1,x2,y2 = map(int,m.groups())
with open(out,'w') as f:
    f.write(f'play {(x1+x2)//2} {(y1+y2)//2}\\n')
PY
  read _ RPX RPY < "$OUT/play-retry.txt"
  adb shell input tap "$RPX" "$RPY"
  sleep 3
fi
'''
    if new_smoke not in t:
        if t.count(old_smoke) != 1:
            die(f"bloco de interação do smoke esperado 1x, encontrado {t.count(old_smoke)}")
        t = t.replace(old_smoke, new_smoke, 1)
    write(smoke, t)

    g = read(gradle)
    m = read(main)
    s = read(splash)
    t = read(smoke)
    checks = [
        "**/*.so" in g,
        "externalNativeBuild" not in g,
        "abiFilters 'armeabi-v7a', 'arm64-v8a'" not in g,
        "debuggable true" in g,
        'putBoolean("play_gate_ok", true)' in m,
        '.commit();' in m,
        "startActivity(new Intent(this, SAMP.class));" not in m,
        "ensureEmulatorHarnessBase();" in s,
        'new File(root, "texdb")' in s,
        'new File(root, "data")' in s,
        'ARL_EMULATOR_FAKE_BASE.txt' in s,
        'window-after-nickname.xml' in t,
        'launcher-before-play.png' in t,
        'primeiro tap não registrou' in t,
    ]
    if not all(checks):
        die("auditoria pós-patch falhou")

    print("[ARL PHASE9.1] OK: launcher-only x86_64 emulator harness aplicado")
    print("[ARL PHASE9.1] OK: fake base criada pelo UID do próprio app")
    print("[ARL PHASE9.1] OK: interação nickname/JOGAR estabilizada para Android 14")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
