#!/usr/bin/env python3
"""Empacota um boot.img num zip instalavel pelo TWRP (Install normal).

Uso: make_twrp_zip.py <boot.img> <saida.zip> "<descricao>"
O instalador (twrp/update-binary) confere SHA-256, aparelho e particao,
grava so o boot e le de volta.
"""
import hashlib
import os
import sys
import zipfile

img, out, desc = sys.argv[1:4]
here = os.path.dirname(os.path.abspath(__file__))
data = open(img, "rb").read()
if data[:8] != b"ANDROID!":
    sys.exit("ERRO: nao e um boot.img Android")
files = {
    "boot.img": data,
    "boot.sha256": (hashlib.sha256(data).hexdigest() + "\n").encode(),
    "LEIA-ME.txt": (desc + "\n").encode(),
    "META-INF/com/google/android/update-binary": open(os.path.join(here, "twrp", "update-binary"), "rb").read(),
    "META-INF/com/google/android/updater-script": b"# instalador em shell: ver update-binary\n",
}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name, content in files.items():
        zi = zipfile.ZipInfo(name)
        zi.external_attr = 0o755 << 16
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, content)
bad = zipfile.ZipFile(out).testzip()
if bad:
    sys.exit("ERRO: zip corrompido em " + bad)
print("OK: %s (%d bytes) sha256(boot.img)=%s" % (out, os.path.getsize(out), hashlib.sha256(data).hexdigest()))
