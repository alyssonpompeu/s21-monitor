#!/usr/bin/env python3
"""Troca somente o kernel dentro de um boot.img Android (cabecalho v0).

Mantem ramdisk, cmdline, enderecos e qualquer dado apos as secoes (assinatura
Samsung). Uso: repack_bootimg.py <boot_original.img> <novo_kernel> <saida.img>
"""
import struct
import sys


def pad(n, page):
    return (n + page - 1) // page * page


def main():
    src, kern, out = sys.argv[1:4]
    d = open(src, "rb").read()
    if d[:8] != b"ANDROID!":
        sys.exit("ERRO: nao e um boot.img Android")
    ks, ka, rs, ra, ss, sa, ta, page, hv, osv = struct.unpack("<10I", d[8:48])
    if hv != 0:
        sys.exit("ERRO: cabecalho v%d nao suportado" % hv)
    k_off = page
    r_off = k_off + pad(ks, page)
    s_off = r_off + pad(rs, page)
    end = s_off + pad(ss, page)
    ramdisk = d[r_off:r_off + rs]
    second = d[s_off:s_off + ss]
    tail = d[end:]
    nk = open(kern, "rb").read()
    hdr = bytearray(d[:page])
    struct.pack_into("<I", hdr, 8, len(nk))
    body = bytes(hdr)
    for blob in (nk, ramdisk, second):
        body += blob + b"\0" * (pad(len(blob), page) - len(blob))
    body += tail
    open(out, "wb").write(body)
    print("OK: kernel %d -> %d bytes; ramdisk %d; cauda %d; total %d" % (ks, len(nk), rs, len(tail), len(body)))


if __name__ == "__main__":
    main()
