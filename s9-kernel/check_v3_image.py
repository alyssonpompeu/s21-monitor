#!/usr/bin/env python3
"""Confere que o Image.gz-dtb do v3 carrega as alteracoes esperadas.

- os 9 device trees de chips v2/v2.1 (inclui o Rev14) tem a tabela B da GPU;
- todos tem o voto extra de memoria (7980 MBps);
- o kernel tem as mensagens da regravacao da LUT.
"""
import struct
import sys
import zlib

k = open(sys.argv[1], "rb").read()
o = zlib.decompressobj(31)
img = o.decompress(k)
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
if len(dtbs) != 15:
    sys.exit("ERRO: esperava 15 device trees, achei %d" % len(dtbs))
gpu_new = [798000000, 636000000, 507000000, 322000000, 257000000]
gpu_old = [1000000000, 710000000, 675000000, 596000000, 520000000, 414000000, 342000000]
be = lambda v: struct.pack(">I", v)
# Dos 15 device trees, 9 sao de chips SDM845 v2/v2.1 (inclui o Rev14 do aparelho)
# e usam a tabela de GPU alterada; os outros 6 sao de chips v1 e ficam como estao.
# No original, cada frequencia antiga aparece exatamente 1 vez nesses 9 DTBs.
# 900 MHz ja aparece 2 vezes em todo DTB original (outros clocks): o nivel novo
# de GPU tem de somar exatamente 1 ocorrencia. 798 MHz nao existe no original.
v2 = [d for d in dtbs if be(798000000) in d]
for n, d in enumerate(v2):
    if d.count(be(900000000)) != 3:
        sys.exit("ERRO: DTB v3 %d sem o nivel de GPU de 900 MHz" % n)
if len(v2) != 9:
    sys.exit("ERRO: esperava 9 device trees com a tabela B, achei %d" % len(v2))
for n, d in enumerate(v2):
    for f in gpu_new:
        if be(f) not in d:
            sys.exit("ERRO: DTB v2 %d sem o nivel de GPU %d" % (n, f))
    for f in gpu_old:
        if be(f) in d:
            sys.exit("ERRO: DTB v2 %d ainda tem o nivel antigo %d" % (n, f))
rev14 = [d for d in dtbs if b"Samsung STARQLTE CHN PROJECT Rev14" in d]
if len(rev14) != 2 or any(d not in v2 for d in rev14):
    sys.exit("ERRO: os device trees do Rev14 (v2.0 e v2.1) nao receberam a tabela B")
seq = b"".join(be(v) for v in (762, 1144, 1720, 2086, 2597, 2929, 3879, 4943, 5931, 6881, 7980))
for n, d in enumerate(dtbs):
    if seq not in d:
        sys.exit("ERRO: DTB %d sem a tabela de memoria com 7980 MBps" % n)
for s in (b"s9_osm_oc: %s", b"s9_osm_result", b"frequencia aceita em %d linhas", b"input_boost_freq", b"cpuboost_wq"):
    if s not in img:
        sys.exit("ERRO: kernel sem %r" % s)
if b"s9_osm_uv" in img:
    sys.exit("ERRO: codigo de undervolt do v2 ainda presente")
print("OK v3: 9 DTBs (v2/v2.1, inclui Rev14) com GPU ate 900 MHz; 15 com voto de 7980 MBps; troca de multiplicador da LUT e reforco ao tocar presentes")
