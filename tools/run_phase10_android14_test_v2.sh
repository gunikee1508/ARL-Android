#!/usr/bin/env bash
set -euo pipefail

# Keep the original Phase 10 scenario immutable as evidence of the first run.
# Patch only test assertions that were proven incorrect after inspecting the
# published distribution manifest; the application/downloader code is unchanged.
TMP="${RUNNER_TEMP:-/tmp}/run_phase10_android14_test_v2.sh"
cp tools/run_phase10_android14_test.sh "$TMP"

python3 - "$TMP" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')

old='''[[ "$TOTAL_ARL" = "8558" ]] || { log "FAIL: TOTAL_ARL=$TOTAL_ARL"; exit 1; }
adb shell "test -s '$ROOT/texdb/arlbrasil/arlbrasil.txt'" || { log "FAIL: arlbrasil.txt ausente"; exit 1; }
log "PASS: 8.558 arquivos instalados; 8.552 PNGs; IMG=213.379.072 bytes"'''
new='''[[ "$TOTAL_ARL" = "8555" ]] || { log "FAIL: runtime file count=$TOTAL_ARL (esperado 8555)"; exit 1; }
adb shell "test -s '$ROOT/texdb/arlbrasil/arlbrasil.txt'" || { log "FAIL: arlbrasil.txt ausente"; exit 1; }
adb shell "test -s '$ROOT/upstream/CREDITOS-GTA-BRASIL.md'" || { log "FAIL: CREDITOS ausente"; exit 1; }
adb shell "test -s '$ROOT/upstream/LICENSE-GTA-BRASIL.txt'" || { log "FAIL: LICENSE ausente"; exit 1; }
adb shell "test -s '$ROOT/upstream/README-GTA-BRASIL.md'" || { log "FAIL: README upstream ausente"; exit 1; }
ALL_MANAGED="$(adb shell \"find '$ROOT/arlbrasil' '$ROOT/texdb/arlbrasil' '$ROOT/upstream/CREDITOS-GTA-BRASIL.md' '$ROOT/upstream/LICENSE-GTA-BRASIL.txt' '$ROOT/upstream/README-GTA-BRASIL.md' -type f 2>/dev/null | wc -l\" | tr -d '\\r[:space:]')"
[[ "$ALL_MANAGED" = "8558" ]] || { log "FAIL: total managed=$ALL_MANAGED (esperado 8558)"; exit 1; }
log "PASS: 8.558 arquivos instalados (8.555 runtime + 3 docs); 8.552 PNGs; IMG=213.379.072 bytes"'''
if old not in s:
    raise SystemExit('count assertion block not found')
s=s.replace(old,new,1)

old='''[[ ! -e "$ROOT/download/arl_phase10" ]] || { log "FAIL: diretório de download permaneceu após segundo boot"; exit 1; }'''
new='''if adb shell "test -e '$ROOT/download/arl_phase10'"; then log "FAIL: diretório de download permaneceu após segundo boot"; exit 1; fi'''
if s.count(old)!=2:
    raise SystemExit(f'expected two host-side staging assertions, found {s.count(old)}')
s=s.replace(old,new,1)
# The second original line has a different failure message.
old2='''[[ ! -e "$ROOT/download/arl_phase10" ]] || { log "FAIL: staging/download sobrou após reparo"; exit 1; }'''
new2='''if adb shell "test -e '$ROOT/download/arl_phase10'"; then log "FAIL: staging/download sobrou após reparo"; exit 1; fi'''
if old2 not in s:
    raise SystemExit('repair staging assertion not found')
s=s.replace(old2,new2,1)

p.write_text(s,encoding='utf-8',newline='\n')
PY

chmod +x "$TMP"
exec bash "$TMP"
