#!/usr/bin/env python3
"""Teste de regressao do build local com clang 18 + ld.lld (kernel v5).

Uso: check_vmlinux_lld.py <vmlinux> <Image> <boot_original.img>

Cada verificacao corresponde a um defeito que apareceu ao trocar GCC 4.9 +
binutils por clang 18 + ld.lld:
 1. "-pie -shared" recusado pelo lld -> vmlinux tem de ser ELF DYN com .rela;
 2. stpcpy indefinido (clang gera chamadas a ele) -> nenhum simbolo indefinido;
 3. o kernel 4.9 so aplica relocacoes R_AARCH64_RELATIVE no boot -> qualquer
    outro tipo so e aceito se nao tiver simbolo (vem de ponteiro fraco nulo,
    hoje so no driver gator, que nunca roda);
 4. memcpy/memset/memmove "weak" -> os tres existem e apontam para o mesmo
    endereco das versoes __mem*;
 5. cabecalho do Image igual ao original (text_offset, flags) e tamanho a
    menos de 2% do original.
"""
import re
import struct
import subprocess
import sys
import zlib

vmlinux, image, boot = sys.argv[1:4]
fails = []


def run(*a):
    return subprocess.run(a, capture_output=True, text=True, check=True).stdout


hdr = run("llvm-readelf", "-h", vmlinux)
if "DYN" not in hdr:
    fails.append("vmlinux nao e ELF DYN (relocavel)")
if not re.search(r"\]\s+\.rela\s+RELA", run("llvm-readelf", "-S", vmlinux)):
    fails.append("secao .rela ausente")

# 6. __section("x") com aspas: o clang grava as aspas no nome e o lld joga a
#    secao fora do lugar (secondary_holding_pen_release caiu no meio do rodata)
quoted = [l for l in run("llvm-readelf", "-S", "-W", vmlinux).splitlines() if '"' in l]
if quoted:
    fails.append("secao com aspas no nome: %s" % quoted)

undef = [l for l in run("llvm-nm", "-u", vmlinux).splitlines() if l.strip()]
weak_ok = [l for l in undef if l.split()[0] in ("w", "v")]
strong = [l for l in undef if l not in weak_ok]
if strong:
    fails.append("simbolos indefinidos: %s" % strong[:10])

types = {}
bad = []
for l in run("llvm-readelf", "-r", vmlinux).splitlines():
    m = re.match(r"\s*([0-9a-f]+)\s+([0-9a-f]+)\s+(R_AARCH64_\w+)\s*(\S*)", l)
    if not m:
        continue
    t = m.group(3)
    types[t] = types.get(t, 0) + 1
    if t != "R_AARCH64_RELATIVE" and int(m.group(2), 16) >> 32 != 0:
        bad.append(l.strip())
if types.get("R_AARCH64_RELATIVE", 0) < 100000:
    fails.append("poucas relocacoes RELATIVE: %s" % types)
if bad:
    fails.append("relocacoes com simbolo que o kernel nao aplica: %s" % bad[:5])
if sum(v for k, v in types.items() if k != "R_AARCH64_RELATIVE") > 16:
    fails.append("relocacoes nao-RELATIVE demais: %s" % types)

syms = {}
for l in run("llvm-nm", vmlinux).splitlines():
    p = l.split()
    if len(p) == 3:
        syms[p[2]] = (p[0], p[1])
for f in ("memcpy", "memset", "memmove"):
    if f not in syms or "__" + f not in syms:
        fails.append("%s ausente" % f)
    elif syms[f][0] != syms["__" + f][0]:
        fails.append("%s em endereco diferente de __%s" % (f, f))

new = open(image, "rb").read()
b = open(boot, "rb").read()
ks, = struct.unpack("<I", b[8:12])
page, = struct.unpack("<I", b[36:40])
old = zlib.decompressobj(31).decompress(b[page:page + ks])
hn = struct.unpack("<IIQQQ", new[:32])
ho = struct.unpack("<IIQQQ", old[:32])
if hn[2] != ho[2] or hn[4] != ho[4]:
    fails.append("cabecalho do Image difere: text_offset/flags %s vs %s" % (hn[2:5:2], ho[2:5:2]))
if new[56:60] != b"ARM\x64":
    fails.append("Image sem a assinatura ARM64")
if abs(len(new) - len(old)) > len(old) * 0.02:
    fails.append("tamanho do Image fora de 2%%: %d vs %d" % (len(new), len(old)))

if fails:
    print("FALHOU:\n  " + "\n  ".join(fails))
    sys.exit(1)
print("OK lld: ELF DYN com %d relocacoes RELATIVE; %d outras sem simbolo; sem indefinidos; "
      "mem* corretos; cabecalho igual ao original" %
      (types["R_AARCH64_RELATIVE"], sum(v for k, v in types.items() if k != "R_AARCH64_RELATIVE")))
