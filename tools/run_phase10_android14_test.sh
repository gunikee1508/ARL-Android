#!/usr/bin/env bash
set -euo pipefail

PKG="com.samp.mobile"
SPLASH="com.samp.mobile/.launcher.SplashActivity"
ROOT="/sdcard/Android/data/${PKG}/files"
OUT="${GITHUB_WORKSPACE:-.}/phase10-android14-output"
mkdir -p "$OUT"
REPORT="$OUT/PHASE10_ANDROID14_REPORT.txt"
: > "$REPORT"

log(){ echo "$*" | tee -a "$REPORT"; }

is_main_visible(){
  local s
  s="$(adb shell dumpsys activity top 2>/dev/null || true)"
  grep -Eq 'ACTIVITY[[:space:]]+com\.samp\.mobile/\.launcher\.MainActivity' <<<"$s" && return 0
  s="$(adb shell dumpsys activity activities 2>/dev/null || true)"
  grep -Eq '(topResumedActivity|mResumedActivity|ResumedActivity).*com\.samp\.mobile/\.launcher\.MainActivity' <<<"$s" && return 0
  s="$(adb shell dumpsys window windows 2>/dev/null || true)"
  grep -Eq '(mCurrentFocus|mFocusedApp).*com\.samp\.mobile/\.launcher\.MainActivity' <<<"$s"
}

wait_main(){
  local seconds="$1" elapsed=0
  while (( elapsed < seconds )); do
    if is_main_visible; then return 0; fi
    if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt" 2>/dev/null && grep -q 'com.samp.mobile' "$OUT/logcat.txt" 2>/dev/null; then
      log "FAIL: FATAL EXCEPTION durante bootstrap"
      grep -n -A50 -B10 'FATAL EXCEPTION' "$OUT/logcat.txt" | tail -n 180 | tee -a "$REPORT" || true
      return 1
    fi
    sleep 5; elapsed=$((elapsed+5))
  done
  return 1
}

dump_ui(){
  adb shell uiautomator dump /sdcard/arl-phase10.xml >/dev/null
  adb pull /sdcard/arl-phase10.xml "$OUT/window.xml" >/dev/null
}

APK="${ARL_TEST_APK:-}"
[[ -n "$APK" && -f "$APK" ]] || { log "FAIL: ARL_TEST_APK ausente"; exit 1; }

log "ARL Phase 10 • Android 14 End-to-End Test"
log "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
log "Android: $(adb shell getprop ro.build.version.release | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
log "ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
log "APK: $APK"

if unzip -l "$APK" | grep -Eq '(^|[[:space:]])lib/[^/]+/[^[:space:]]+\.so'; then
  log "FAIL: harness contém biblioteca nativa"
  exit 1
fi
log "PASS: harness Java-only; downloader/launcher permanecem os da Phase 10"

curl -fL --retry 4 --retry-delay 2 \
  'https://github.com/gunikee1508/ARL-Android/releases/download/phase10-data-v1/distribution.json' \
  -o "$OUT/distribution.json"
python3 - "$OUT/distribution.json" "$OUT/repair-target.txt" <<'PY'
import json,sys
m=json.load(open(sys.argv[1],encoding='utf-8'))
assert m['version']=='phase10-gta-brasil-6417354'
assert m['fileCount']==8558 and m['packageCount']==12
pkgs=[p for p in m['packages'] if p['id'].startswith('textures-')]
p=min(pkgs,key=lambda x:x['bytes'])
f=p['files'][0]
open(sys.argv[2],'w').write(f"{p['id']}\n{f['path']}\n{f['bytes']}\n{f['sha256']}\n{p['bytes']}\n")
print('manifest OK; repair package',p['id'],p['bytes'],'target',f['path'])
PY

adb install -r "$APK" | tee -a "$REPORT"
adb shell pm clear "$PKG" >/dev/null
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" >/dev/null 2>&1 || true' EXIT

FIRST_START=$(date +%s)
adb shell am force-stop "$PKG"
adb shell am start -W -n "$SPLASH" | tee -a "$REPORT"
log "INFO: primeiro boot baixando e instalando os 12 pacotes CDN reais (~408 MiB)..."
if ! wait_main 2400; then
  log "FAIL: launcher não abriu após instalação CDN"
  tail -n 240 "$OUT/logcat.txt" | tee -a "$REPORT" || true
  exit 1
fi
FIRST_SECONDS=$(( $(date +%s) - FIRST_START ))
log "PASS: primeiro boot concluiu e abriu MainActivity (${FIRST_SECONDS}s)"

adb shell "test -f '$ROOT/ARL_PHASE10_TEST_BASE.txt'" || { log "FAIL: fake base app-owned ausente"; exit 1; }
log "PASS: base estrutural de teste criada pelo UID do app"

PREFS="$(adb shell run-as "$PKG" cat shared_prefs/arl_data_state.xml 2>/dev/null | tr -d '\r' || true)"
grep -q 'phase10-gta-brasil-6417354' <<<"$PREFS" || { log "FAIL: versão DATA não persistida"; echo "$PREFS" >> "$REPORT"; exit 1; }
grep -q '>packages<' <<<"$PREFS" || { log "FAIL: estado package mode não persistido"; echo "$PREFS" >> "$REPORT"; exit 1; }
log "PASS: estado DATA persistido como phase10-gta-brasil-6417354 / packages"

PNG_COUNT="$(adb shell "find '$ROOT/texdb/arlbrasil/src' -type f -name '*.png' 2>/dev/null | wc -l" | tr -d '\r[:space:]')"
IMG_BYTES="$(adb shell "wc -c < '$ROOT/arlbrasil/arlbrasil.img' 2>/dev/null" | tr -d '\r[:space:]')"
TOTAL_ARL="$(adb shell "find '$ROOT/arlbrasil' '$ROOT/texdb/arlbrasil' -type f 2>/dev/null | wc -l" | tr -d '\r[:space:]')"
[[ "$PNG_COUNT" = "8552" ]] || { log "FAIL: PNG_COUNT=$PNG_COUNT"; exit 1; }
[[ "$IMG_BYTES" = "213379072" ]] || { log "FAIL: IMG_BYTES=$IMG_BYTES"; exit 1; }
[[ "$TOTAL_ARL" = "8558" ]] || { log "FAIL: TOTAL_ARL=$TOTAL_ARL"; exit 1; }
adb shell "test -s '$ROOT/texdb/arlbrasil/arlbrasil.txt'" || { log "FAIL: arlbrasil.txt ausente"; exit 1; }
log "PASS: 8.558 arquivos instalados; 8.552 PNGs; IMG=213.379.072 bytes"

dump_ui
adb exec-out screencap -p > "$OUT/01-first-boot.png"
grep -Eq 'AMAZING REAL LIFE|ARL MOBILE' "$OUT/window.xml" || { log "FAIL: identidade premium não renderizada"; exit 1; }
grep -q 'play.arl-samprpg.site:7777' "$OUT/window.xml" || { log "FAIL: endpoint ARL não renderizado"; exit 1; }
grep -q 'ARQUIVOS ARL VERIFICADOS' "$OUT/window.xml" || { log "FAIL: UI não marcou DATA verificada"; exit 1; }
log "PASS: UI premium + endpoint + status DATA verificado renderizados"

# Second boot: must verify/reuse existing files without downloading all packages again.
adb shell am force-stop "$PKG"
SECOND_START=$(date +%s)
adb shell am start -W -n "$SPLASH" >/dev/null
if ! wait_main 180; then log "FAIL: segundo boot não abriu launcher"; exit 1; fi
SECOND_SECONDS=$(( $(date +%s) - SECOND_START ))
[[ ! -e "$ROOT/download/arl_phase10" ]] || { log "FAIL: diretório de download permaneceu após segundo boot"; exit 1; }
log "PASS: segundo boot reutilizou instalação íntegra (${SECOND_SECONDS}s), sem pacote pendente"

# Differential repair: corrupt exactly one file from the smallest texture package.
mapfile -t RT < "$OUT/repair-target.txt"
REPAIR_PKG="${RT[0]}"; REPAIR_PATH="${RT[1]}"; REPAIR_BYTES="${RT[2]}"; REPAIR_SHA="${RT[3]}"; REPAIR_PKG_BYTES="${RT[4]}"
log "INFO: removendo propositalmente $REPAIR_PATH para testar reparo diferencial ($REPAIR_PKG / $REPAIR_PKG_BYTES bytes)"
adb shell "rm -f '$ROOT/$REPAIR_PATH'"
adb shell "test ! -e '$ROOT/$REPAIR_PATH'" || { log "FAIL: alvo de reparo não foi removido"; exit 1; }
adb shell am force-stop "$PKG"
REPAIR_START=$(date +%s)
adb shell am start -W -n "$SPLASH" >/dev/null
if ! wait_main 600; then log "FAIL: launcher não voltou após reparo diferencial"; exit 1; fi
REPAIR_SECONDS=$(( $(date +%s) - REPAIR_START ))
adb shell "test -f '$ROOT/$REPAIR_PATH'" || { log "FAIL: arquivo removido não foi restaurado"; exit 1; }
ACTUAL_REPAIR_BYTES="$(adb shell "wc -c < '$ROOT/$REPAIR_PATH'" | tr -d '\r[:space:]')"
[[ "$ACTUAL_REPAIR_BYTES" = "$REPAIR_BYTES" ]] || { log "FAIL: tamanho do arquivo reparado não confere"; exit 1; }
adb pull "$ROOT/$REPAIR_PATH" "$OUT/repaired-file.bin" >/dev/null
ACTUAL_SHA="$(sha256sum "$OUT/repaired-file.bin" | awk '{print $1}')"
[[ "$ACTUAL_SHA" = "$REPAIR_SHA" ]] || { log "FAIL: SHA do arquivo reparado não confere"; exit 1; }
[[ ! -e "$ROOT/download/arl_phase10" ]] || { log "FAIL: staging/download sobrou após reparo"; exit 1; }
log "PASS: reparo diferencial restaurou $REPAIR_PATH com tamanho+SHA corretos (${REPAIR_SECONDS}s)"

# Verify play controls and pass all launcher gates without loading proprietary/native GTA runtime.
FOUND=0
for attempt in 0 1 2 3 4; do
  dump_ui
  if grep -q 'resource-id="com.samp.mobile:id/arl_nickname"' "$OUT/window.xml" && grep -q 'resource-id="com.samp.mobile:id/arl_play"' "$OUT/window.xml"; then FOUND=1; break; fi
  adb shell input swipe 520 1700 520 650 450 >/dev/null || true
  sleep 1
done
[[ "$FOUND" = 1 ]] || { log "FAIL: nickname/JOGAR não encontrados"; exit 1; }
python3 - "$OUT/window.xml" "$OUT/taps.txt" <<'PY'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); want={'nick':'com.samp.mobile:id/arl_nickname','play':'com.samp.mobile:id/arl_play'}; pts={}
for n in root.iter('node'):
    for k,rid in want.items():
        if n.attrib.get('resource-id')==rid:
            if k=='play' and n.attrib.get('enabled')!='true': raise SystemExit('play disabled')
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups()); pts[k]=((x1+x2)//2,(y1+y2)//2)
if set(pts)!=set(want): raise SystemExit('controls missing')
open(sys.argv[2],'w').write('\n'.join(f'{k} {pts[k][0]} {pts[k][1]}' for k in ('nick','play'))+'\n')
PY
read _ NX NY < <(grep '^nick ' "$OUT/taps.txt")
adb shell input tap "$NX" "$NY"; sleep 1
adb shell input keyevent KEYCODE_MOVE_END || true
for _ in $(seq 1 30); do adb shell input keyevent KEYCODE_DEL >/dev/null || true; done
adb shell input text 'ARL_Test_10'; sleep 1
adb shell input keyevent KEYCODE_BACK || true; sleep 3
# Re-dump after IME closure because layout may move.
dump_ui
python3 - "$OUT/window.xml" "$OUT/playtap.txt" <<'PY'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
n=next((x for x in root.iter('node') if x.attrib.get('resource-id')=='com.samp.mobile:id/arl_play'),None)
if n is None or n.attrib.get('enabled')!='true' or n.attrib.get('clickable')!='true': raise SystemExit('play not enabled/clickable')
m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
if not m: raise SystemExit('bad play bounds')
x1,y1,x2,y2=map(int,m.groups()); open(sys.argv[2],'w').write(f'{(x1+x2)//2} {(y1+y2)//2}\n')
PY
read PX PY < "$OUT/playtap.txt"
adb exec-out screencap -p > "$OUT/02-before-play.png"
adb shell input tap "$PX" "$PY"; sleep 3
HARNESS="$(adb shell run-as "$PKG" cat shared_prefs/arl_phase10_emulator_harness.xml 2>/dev/null | tr -d '\r' || true)"
grep -q 'play_gate_ok' <<<"$HARNESS" || { log "FAIL: JOGAR não atravessou gates"; echo "$HARNESS" >> "$REPORT"; exit 1; }
grep -q 'ARL_Test_10' <<<"$HARNESS" || { log "FAIL: nickname não chegou ao play gate"; exit 1; }
grep -q 'play.arl-samprpg.site:7777' <<<"$HARNESS" || { log "FAIL: endpoint não chegou ao play gate"; exit 1; }
SETTINGS="$(adb shell "cat '$ROOT/SAMP/settings.ini' 2>/dev/null" | tr -d '\r' || true)"
grep -q 'ARL_Test_10' <<<"$SETTINGS" || { log "FAIL: settings.ini sem nickname"; exit 1; }
grep -q 'play.arl-samprpg.site' <<<"$SETTINGS" || { log "FAIL: settings.ini sem host"; exit 1; }
log "PASS: nickname + settings.ini + todos os gates de JOGAR passaram"

if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt" && grep -q 'com.samp.mobile' "$OUT/logcat.txt"; then
  log "FAIL: FATAL EXCEPTION encontrada"
  grep -n -A50 -B10 'FATAL EXCEPTION' "$OUT/logcat.txt" | tail -n 180 | tee -a "$REPORT" || true
  exit 1
fi
log "PASS: nenhum FATAL EXCEPTION do ARL"
log "RESULTADO FINAL: PASS"
