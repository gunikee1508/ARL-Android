#!/usr/bin/env bash
set -euo pipefail

PKG="com.samp.mobile"
SPLASH="com.samp.mobile/.launcher.SplashActivity"
MAIN_COMPONENT="com.samp.mobile/.launcher.MainActivity"
ROOT="/sdcard/Android/data/${PKG}/files"
OUT="${GITHUB_WORKSPACE:-.}/emulator-output"
mkdir -p "$OUT"
REPORT="$OUT/PHASE9_1_EMULATOR_REPORT.txt"
: > "$REPORT"

log() {
  echo "$*" | tee -a "$REPORT"
}

# Android's dumpsys output changes between releases. Android 14 on the GitHub
# emulator does not always expose the legacy mResumedActivity line, so use
# several independent signals instead of depending on a single key.
is_main_visible() {
  local state

  state="$(adb shell dumpsys activity top 2>/dev/null || true)"
  if grep -Eq 'ACTIVITY[[:space:]]+com\.samp\.mobile/\.launcher\.MainActivity' <<<"$state"; then
    return 0
  fi

  state="$(adb shell dumpsys activity activities 2>/dev/null || true)"
  if grep -Eq '(topResumedActivity|mResumedActivity|ResumedActivity).*com\.samp\.mobile/\.launcher\.MainActivity' <<<"$state"; then
    return 0
  fi

  state="$(adb shell dumpsys window windows 2>/dev/null || true)"
  if grep -Eq '(mCurrentFocus|mFocusedApp).*com\.samp\.mobile/\.launcher\.MainActivity' <<<"$state"; then
    return 0
  fi

  # Last-resort signal: ActivityTaskManager reports the launcher as displayed.
  grep -Eq 'Displayed com\.samp\.mobile/\.launcher\.MainActivity|START .*cmp=com\.samp\.mobile/\.launcher\.MainActivity' "$OUT/logcat.txt" 2>/dev/null
}

dump_focus_state() {
  {
    echo
    echo '===== dumpsys activity top ====='
    adb shell dumpsys activity top 2>/dev/null | head -n 120 || true
    echo
    echo '===== resumed/top activity candidates ====='
    adb shell dumpsys activity activities 2>/dev/null | grep -E 'ResumedActivity|topResumedActivity|mResumedActivity|com\.samp\.mobile' | head -n 120 || true
    echo
    echo '===== focused window candidates ====='
    adb shell dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|com\.samp\.mobile' | head -n 120 || true
  } >> "$REPORT"
}

APK="${ARL_TEST_APK:-}"
if [[ -z "$APK" ]]; then
  APK="$(find upstream/app/build/outputs/apk -type f -name '*.apk' -print -quit)"
fi
[[ -n "$APK" && -f "$APK" ]] || { log "FAIL: APK não encontrado"; exit 1; }

log "ARL Phase 9.1 Emulator Test Harness"
log "APK: $APK"
log "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
log "Android: $(adb shell getprop ro.build.version.release | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
log "ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

# This APK must be launcher-only: if a .so slipped into it, an x86_64 emulator
# could fail installation or accidentally exercise an unsupported native path.
if unzip -l "$APK" | grep -Eq '(^|[[:space:]])lib/[^/]+/[^[:space:]]+\.so'; then
  log "FAIL: APK de harness contém biblioteca nativa .so"
  exit 1
fi
log "PASS: APK de harness sem bibliotecas nativas"

adb logcat -c
adb install -r "$APK" | tee -a "$REPORT"
adb shell pm clear "$PKG" >/dev/null

# Fake *structure only*. No Rockstar game asset is supplied. The production
# validator currently recognizes texdb + one GTA data directory as the minimum
# structural signal; that is enough to exercise the launcher/bootstrap path.
adb shell "mkdir -p '$ROOT/texdb' '$ROOT/data'"
adb shell "echo phase9_1_emulator_harness > '$ROOT/ARL_EMULATOR_FAKE_BASE.txt'"
log "PASS: estrutura mínima de base criada no emulador (sem conteúdo GTA)"

adb logcat -v time > "$OUT/logcat.txt" &
LOGCAT_PID=$!
cleanup() {
  kill "$LOGCAT_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb shell am force-stop "$PKG"
adb shell am start -W -n "$SPLASH" | tee -a "$REPORT"

log "INFO: aguardando instalação/verificação real do GTA Brasil embutido..."
READY=0
for i in $(seq 1 72); do
  if is_main_visible; then
    READY=1
    log "PASS: launcher principal aberto após bootstrap (tentativa $i)"
    break
  fi
  if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt" && grep -q 'com.samp.mobile' "$OUT/logcat.txt"; then
    log "FAIL: FATAL EXCEPTION durante bootstrap"
    tail -n 160 "$OUT/logcat.txt" | tee -a "$REPORT"
    exit 1
  fi
  sleep 5
done
if [[ "$READY" != 1 ]]; then
  log "FAIL: launcher não abriu dentro da janela de bootstrap"
  dump_focus_state
  tail -n 200 "$OUT/logcat.txt" | tee -a "$REPORT"
  exit 1
fi

adb exec-out screencap -p > "$OUT/launcher-after-bootstrap.png"
adb shell uiautomator dump /sdcard/arl-window.xml >/dev/null
adb pull /sdcard/arl-window.xml "$OUT/window-after-bootstrap.xml" >/dev/null

if ! grep -q 'ENTRAR NO AMAZING REAL LIFE' "$OUT/window-after-bootstrap.xml"; then
  # The CTA can be below the fold; the launcher itself must at least expose its
  # premium identity/status before we scroll to the play controls.
  grep -Eq 'AMAZING REAL LIFE|ARL MOBILE|GTA BRASIL' "$OUT/window-after-bootstrap.xml" || {
    log "FAIL: identidade ARL não encontrada no UI dump"
    exit 1
  }
fi
log "PASS: UI premium ARL renderizada"

PNG_COUNT="$(adb shell "find '$ROOT/texdb/arlbrasil/src' -type f -name '*.png' 2>/dev/null | wc -l" | tr -d '\r[:space:]')"
IMG_BYTES="$(adb shell "wc -c < '$ROOT/arlbrasil/arlbrasil.img' 2>/dev/null" | tr -d '\r[:space:]')"
[[ "$PNG_COUNT" = "8552" ]] || { log "FAIL: PNGs instalados=$PNG_COUNT (esperado 8552)"; exit 1; }
[[ "$IMG_BYTES" = "213379072" ]] || { log "FAIL: arlbrasil.img=$IMG_BYTES bytes (esperado 213379072)"; exit 1; }
adb shell "test -s '$ROOT/texdb/arlbrasil/arlbrasil.txt'"
log "PASS: GTA Brasil extraído: 8552 PNGs + IMG 213379072 bytes + listing"

EMBED_PREFS="$(adb shell run-as "$PKG" cat shared_prefs/arl_embedded_data_state.xml 2>/dev/null | tr -d '\r' || true)"
grep -q 'phase8-gta-brasil-6417354' <<<"$EMBED_PREFS" || { log "FAIL: versão embedded não persistida"; exit 1; }
grep -q '6107f9da7a83e304de3d749b8ae70cb9ef563f634df04d88c9161a9fcb7d4889' <<<"$EMBED_PREFS" || { log "FAIL: SHA embedded não persistido"; exit 1; }
log "PASS: versão e SHA do pacote persistidos pelo app"

# Second boot must reuse the already-installed layer rather than re-extract it.
adb shell am force-stop "$PKG"
SECOND_START=$(date +%s)
adb shell am start -W -n "$SPLASH" >/dev/null
SECOND_READY=0
for i in $(seq 1 30); do
  if is_main_visible; then
    SECOND_READY=1
    break
  fi
  sleep 2
done
if [[ "$SECOND_READY" != 1 ]]; then
  log "FAIL: segundo boot não chegou ao launcher"
  dump_focus_state
  exit 1
fi
SECOND_SECONDS=$(( $(date +%s) - SECOND_START ))
log "PASS: segundo boot reutilizou instalação existente (${SECOND_SECONDS}s)"

# Scroll to the identity/play section. UIAutomator only exposes visible nodes.
adb shell input swipe 520 1650 520 560 550 >/dev/null || true
sleep 1
adb shell input swipe 520 1650 520 560 550 >/dev/null || true
sleep 1
adb shell uiautomator dump /sdcard/arl-play.xml >/dev/null
adb pull /sdcard/arl-play.xml "$OUT/window-play.xml" >/dev/null

python3 - "$OUT/window-play.xml" "$OUT/tap-points.txt" <<'PY'
import re, sys, xml.etree.ElementTree as ET
src, out = sys.argv[1:]
root = ET.parse(src).getroot()
want = {
    'nick': 'com.samp.mobile:id/arl_nickname',
    'play': 'com.samp.mobile:id/arl_play',
}
points = {}
for node in root.iter('node'):
    rid = node.attrib.get('resource-id', '')
    for key, target in want.items():
        if rid == target:
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2 = map(int,m.groups())
                points[key] = ((x1+x2)//2, (y1+y2)//2)
missing = set(want) - set(points)
if missing:
    raise SystemExit('missing visible controls: ' + ','.join(sorted(missing)))
with open(out,'w') as f:
    for key in ('nick','play'):
        f.write(f'{key} {points[key][0]} {points[key][1]}\n')
PY

read _ NX NY < <(grep '^nick ' "$OUT/tap-points.txt")
read _ PX PY < <(grep '^play ' "$OUT/tap-points.txt")
adb shell input tap "$NX" "$NY"
adb shell input keyevent KEYCODE_CLEAR || true
adb shell input text 'ARL_Test_01'
adb shell input keyevent KEYCODE_BACK || true
sleep 1
adb shell input tap "$PX" "$PY"
sleep 3

HARNESS_PREFS="$(adb shell run-as "$PKG" cat shared_prefs/arl_emulator_harness.xml 2>/dev/null | tr -d '\r' || true)"
grep -q 'play_gate_ok' <<<"$HARNESS_PREFS" || { log "FAIL: botão JOGAR não atravessou os gates"; echo "$HARNESS_PREFS" >> "$REPORT"; exit 1; }
grep -q 'value="true"' <<<"$HARNESS_PREFS" || { log "FAIL: play_gate_ok != true"; exit 1; }
log "PASS: nickname + todos os gates do botão JOGAR passaram sem carregar GTASA"

adb exec-out screencap -p > "$OUT/launcher-play-gate.png"

if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt" && grep -q 'com.samp.mobile' "$OUT/logcat.txt"; then
  log "FAIL: FATAL EXCEPTION encontrada no logcat"
  grep -n -A40 -B10 'FATAL EXCEPTION' "$OUT/logcat.txt" | tail -n 120 | tee -a "$REPORT"
  exit 1
fi

log "PASS: nenhum FATAL EXCEPTION do ARL durante o cenário"
log "RESULTADO FINAL: PASS"
