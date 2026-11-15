#!/usr/bin/env python3
"""Confere que o Image.gz-dtb do v4 carrega as alteracoes esperadas.

Uso: check_v4_image.py <Image.gz-dtb> <boot_original.img>

Compara cada device tree com o do kernel ORIGINAL (mesma posicao), porque
algumas frequencias novas ja existem nos DTBs originais em outros clocks
(o 900 MHz do v3 aparecia 2 vezes): o certo e medir a diferenca.
- 9 DTBs (chips v2/v2.1, inclui os 2 do Rev14): tabela B da GPU
  (870/840/798/636/507/404 entram, 710/675/596/520/414/342 saem);
- 15 DTBs: voto extra de 7980 MBps e os 2 mapas memlat novos;
- kernel: regravacao da LUT do OSM, reforco ao tocar e leitura do DSM.
"""
import struct
import sys
import zlib


def split(gz):
    o = zlib.decompressobj(31)
    img = o.decompress(gz)
    blob = o.unused_data
    dtbs, i = [], 0
    while True:
        i = blob.find(b"\xd0\x0d\xfe\xed", i)
        if i < 0:
            break
        tot = struct.unpack(">I", blob[i + 4:i + 8])[0]
        if 0x1000 < tot <= len(blob) - i:
            dtbs.append(blob[i:i + tot])
            i += tot
        else:
            i += 4
    return img, dtbs


def key(d):
    """(model, qcom,msm-id, qcom,board-id) do no raiz: identifica o DTB sem
    depender da ordem em que o build os concatena."""
    off_struct, off_str = struct.unpack(">II", d[8:16])
    i, depth, props = off_struct, 0, {}
    while True:
        tag, = struct.unpack(">I", d[i:i + 4])
        i += 4
        if tag == 1:  # BEGIN_NODE
            e = d.index(b"\0", i)
            i = (e + 4) & ~3
            depth += 1
        elif tag == 2:  # END_NODE
            depth -= 1
            if depth == 0:
                break
        elif tag == 3:  # PROP
            ln, no = struct.unpack(">II", d[i:i + 8])
            i += 8
            if depth == 1:
                name = d[off_str + no:d.index(b"\0", off_str + no)]
                props[name] = d[i:i + ln]
            i = (i + ln + 3) & ~3
        elif tag in (4, 9):
            if tag == 9:
                break
        else:
            raise ValueError("FDT invalido")
    return (props.get(b"model"), props.get(b"qcom,msm-id"), props.get(b"qcom,board-id"))


be = lambda v: struct.pack(">I", v)
seq = lambda vals: b"".join(be(v) for v in vals)

img, dtbs = split(open(sys.argv[1], "rb").read())
b = open(sys.argv[2], "rb").read()
ks, = struct.unpack("<I", b[8:12])
page, = struct.unpack("<I", b[36:40])
_, orig = split(b[page:page + ks])
if len(dtbs) != 15 or len(orig) != 15:
    sys.exit("ERRO: esperava 15 device trees, achei %d (original %d)" % (len(dtbs), len(orig)))
okeys = {key(o): o for o in orig}
if len(okeys) != 15:
    sys.exit("ERRO: DTBs originais sem identificacao unica")
try:
    orig = [okeys[key(d)] for d in dtbs]
except KeyError:
    sys.exit("ERRO: DTB do kernel novo sem par no original")

NEW = [870, 840, 798, 636, 507, 404]
OLD = [710, 675, 596, 520, 414, 342]
gpu = 0
for n, (d, o) in enumerate(zip(dtbs, orig)):
    if be(710000000) not in o:
        continue  # chip v1: tabela de GPU fica como esta
    for f in NEW:
        if d.count(be(f * 1000000)) != o.count(be(f * 1000000)) + 1:
            sys.exit("ERRO: DTB %d sem o nivel novo de GPU %d MHz" % (n, f))
    for f in OLD:
        if d.count(be(f * 1000000)) != o.count(be(f * 1000000)) - 1:
            sys.exit("ERRO: DTB %d ainda tem o nivel antigo de GPU %d MHz" % (n, f))
    gpu += 1
if gpu != 9:
    sys.exit("ERRO: esperava 9 device trees com a tabela B, achei %d" % gpu)
rev14 = [n for n, o in enumerate(orig) if b"Samsung STARQLTE CHN PROJECT Rev14" in o]
if len(rev14) != 2 or any(be(710000000) not in orig[n] for n in rev14):
    sys.exit("ERRO: os device trees do Rev14 (v2.0 e v2.1) nao receberam a tabela B")

MEM = seq((762, 1144, 1720, 2086, 2597, 2929, 3879, 4943, 5931, 6881, 7980))
A55 = seq((300000, 1144, 748800, 2086, 1132800, 2929, 1440000, 3879, 1593600, 4943))
A75 = seq((300000, 1144, 499200, 1720, 806400, 2929, 1036800, 3879, 1190400, 4943, 1574400, 6881))
A55_OLD = seq((300000, 762, 748800, 1720, 1132800, 2086, 1440000, 2929, 1593600, 3879))
for n, d in enumerate(dtbs):
    if MEM not in d:
        sys.exit("ERRO: DTB %d sem a tabela de memoria com 7980 MBps" % n)
    if A55 not in d or A75 not in d:
        sys.exit("ERRO: DTB %d sem os mapas memlat do v4" % n)
    if A55_OLD in d:
        sys.exit("ERRO: DTB %d ainda tem o mapa memlat original do A55" % n)
for s in (b"s9_osm_oc: %s", b"s9_osm_result", b"frequencia aceita em %d linhas",
          b"input_boost_freq", b"cpuboost_wq", b"s9_dsm: leitura de %zu bytes"):
    if s not in img:
        sys.exit("ERRO: kernel sem %r" % s)
if b"s9_osm_uv" in img:
    sys.exit("ERRO: codigo de undervolt do v2 ainda presente")
print("OK v4: 9 DTBs (inclui Rev14) com GPU 257-870 MHz; 15 com voto de 7980 MBps e memlat novo; "
      "LUT do OSM, reforco ao tocar e leitura do DSM presentes")
