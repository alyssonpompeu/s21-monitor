#!/usr/bin/env bash
# Testes do instalador TWRP em ambiente simulado (sem aparelho).
# Uso: test_installer.sh <zip-instalavel> <boot.img-esperado>
set -euo pipefail
ZIP="$(readlink -f "$1")"; IMG="$(readlink -f "$2")"
HERE="$(cd "$(dirname "$0")" && pwd)"; UB="$HERE/../twrp/update-binary"
T="$(mktemp -d)"; mkdir -p "$T/bin"
setdev() { printf '#!/bin/sh\necho %s\n' "$1" > "$T/bin/getprop"; chmod +x "$T/bin/getprop"; }
run() { PATH="$T/bin:$PATH" S9_TEST_BOOT="$1" sh "$UB" 3 1 "$2" > "$T/log" 2>&1; }
mkpart() { python3 -c "
import sys
d=bytearray(64*1024*1024); d[:8]=b'ANDROID!'; d[8:16]=b'KEEPTHIS'; d[-32:]=b'Z'*32
open(sys.argv[1],'wb').write(d)" "$1"; }
fail() { echo "FALHA: $1"; cat "$T/log"; exit 1; }

setdev starqltechn; mkpart "$T/p1"
run "$T/p1" "$ZIP" || fail "T1 gravacao normal retornou erro"
python3 - "$T/p1" "$IMG" <<'PY' || fail "T1 conteudo gravado diferente"
import sys
a=open(sys.argv[1],'rb').read(); b=open(sys.argv[2],'rb').read()
assert a[:len(b)]==b and len(a)==64*1024*1024 and a[-32:]==b'Z'*32
PY
setdev beyond1lte; mkpart "$T/p2"
if run "$T/p2" "$ZIP"; then fail "T2 aparelho errado deveria abortar"; fi
python3 -c "import sys; assert open(sys.argv[1],'rb').read()[8:16]==b'KEEPTHIS'" "$T/p2" || fail "T2 particao alterada"
setdev starqltechn
python3 - "$ZIP" "$T/bad.zip" <<'PY'
import sys, zipfile
s=zipfile.ZipFile(sys.argv[1])
with zipfile.ZipFile(sys.argv[2],'w',zipfile.ZIP_DEFLATED) as z:
    for n in s.namelist():
        d=s.read(n)
        if n=='boot.img': d=d[:100000]+bytes([d[100000]^0xff])+d[100001:]
        z.writestr(n,d)
PY
mkpart "$T/p3"
if run "$T/p3" "$T/bad.zip"; then fail "T3 zip corrompido deveria abortar"; fi
python3 -c "import sys; assert open(sys.argv[1],'rb').read()[8:16]==b'KEEPTHIS'" "$T/p3" || fail "T3 particao alterada"
python3 -c "import sys; open(sys.argv[1],'wb').write(b'\0'*1048576)" "$T/p4"
if run "$T/p4" "$ZIP"; then fail "T4 particao nao-boot deveria abortar"; fi
rm -rf "$T"
echo "OK: instalador passou nos 4 testes ($(basename "$ZIP"))"
