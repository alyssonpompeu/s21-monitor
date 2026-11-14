#!/usr/bin/env python3
"""Confere o Image.gz-dtb novo contra o kernel do boot.img original.

Falha se: nao for gzip, a versao nao for a esperada, ou os device trees
anexados (quantidade e modelos) forem diferentes dos da ROM.
"""
import re
import struct
import sys
import zlib


def split(k):
    o = zlib.decompressobj(31)
    img = o.decompress(k)
    return img, o.unused_data


def models(blob):
    out, i = [], 0
    while True:
        i = blob.find(b"\xd0\x0d\xfe\xed", i)
        if i < 0:
            break
        tot = struct.unpack(">I", blob[i + 4:i + 8])[0]
        if 0x1000 < tot <= len(blob) - i:
            m = re.search(rb"Samsung [A-Z0-9 ]+PROJECT Rev\d+", blob[i:i + tot])
            out.append(m.group(0).decode() if m else "?")
            i += tot
        else:
            i += 4
    return out


new = open(sys.argv[1], "rb").read()
d = open(sys.argv[2], "rb").read()
ks, = struct.unpack("<I", d[8:12])
page, = struct.unpack("<I", d[36:40])
old = d[page:page + ks]
if new[:2] != b"\x1f\x8b":
    sys.exit("ERRO: kernel novo nao e gzip")
ni, nd = split(new)
oi, od = split(old)
ver = re.search(rb"Linux version [^\n]+", ni).group(0).decode()
print("versao:", ver)
if "4.9.186-klabitV6.5-ZRAM " not in ver:
    sys.exit("ERRO: versao inesperada")
nm, om = models(nd), models(od)
print("DTBs novo: %d  original: %d" % (len(nm), len(om)))
if sorted(nm) != sorted(om):
    sys.exit("ERRO: device trees diferentes do original:\n novo=%s\n orig=%s" % (nm, om))
print("Image: %d bytes (original %d)" % (len(ni), len(oi)))
if not 0.8 < len(ni) / len(oi) < 1.25:
    sys.exit("ERRO: tamanho do Image fora da faixa esperada")
print("OK: imagem coerente com a original")
