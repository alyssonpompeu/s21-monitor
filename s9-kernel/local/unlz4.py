#!/usr/bin/env python3
"""Descompacta arquivos .lz4 (formato frame) do firmware Samsung, em Python puro.
Uso: unlz4.py <entrada.lz4> <saida>"""
import struct
import sys


def block(src, out):
    i, n = 0, len(src)
    while i < n:
        tok = src[i]
        i += 1
        lit = tok >> 4
        if lit == 15:
            while True:
                b = src[i]
                i += 1
                lit += b
                if b != 255:
                    break
        out += src[i:i + lit]
        i += lit
        if i >= n:
            break
        off = src[i] | (src[i + 1] << 8)
        i += 2
        if off == 0:
            raise ValueError("offset 0 invalido")
        ml = tok & 15
        if ml == 15:
            while True:
                b = src[i]
                i += 1
                ml += b
                if b != 255:
                    break
        ml += 4
        start = len(out) - off
        if off >= ml:
            out += out[start:start + ml]
        else:
            for k in range(ml):
                out.append(out[start + k])


def frame(data):
    out = bytearray()
    p = 0
    while p < len(data):
        magic, = struct.unpack_from("<I", data, p)
        if 0x184D2A50 <= magic <= 0x184D2A5F:  # frame "skippable"
            size, = struct.unpack_from("<I", data, p + 4)
            p += 8 + size
            continue
        if magic != 0x184D2204:
            raise ValueError("magica lz4 invalida em %d" % p)
        flg, bd = data[p + 4], data[p + 5]
        p += 6
        if flg & 0x08:
            p += 8  # tamanho do conteudo
        if flg & 0x01:
            p += 4  # dict id
        p += 1  # checksum do cabecalho
        bchk = flg & 0x10
        while True:
            sz, = struct.unpack_from("<I", data, p)
            p += 4
            if sz == 0:
                break
            raw = sz & 0x80000000
            sz &= 0x7FFFFFFF
            chunk = data[p:p + sz]
            p += sz
            if raw:
                out += chunk
            else:
                block(chunk, out)
            if bchk:
                p += 4
        if flg & 0x04:
            p += 4  # checksum do conteudo
    return bytes(out)


if __name__ == "__main__":
    d = open(sys.argv[1], "rb").read()
    o = frame(d)
    open(sys.argv[2], "wb").write(o)
    print("OK: %d -> %d bytes" % (len(d), len(o)))
