#!/usr/bin/env python3
"""Rank BCM4375B1 RAM candidates for legacy Nexmon SDR functions.

Static analysis only: candidate addresses are never patched or called. The
reference functions/addresses come from Nexmon's bcm43455c0 7.45.154 wrapper
map. AUTO_ENABLE deliberately remains zero.
"""
from __future__ import annotations
import collections
import math
import re
import subprocess
import sys
import tempfile
from pathlib import Path

OLD_RAMSTART = 0x198000
NEW_RAMSTART = 0x170000
REFERENCES = {
    "wlc_phy_stopplayback_acphy": 0x1D2964,
    "wlc_phy_runsamples_acphy": 0x1D2C8C,
}

# Raw instruction bytes are suppressed in llvm-objdump, making this parser
# independent of whether a runner prints Thumb bytes as bytes or halfwords.
LINE_RE = re.compile(r"^\s*([0-9a-fA-F]+):\s+([a-zA-Z][a-zA-Z0-9_.]*)\s*(.*)$")


def disassemble(binary: Path, ramstart: int) -> list[tuple[int,str,str]]:
    with tempfile.TemporaryDirectory() as td:
        obj = Path(td) / "fw.o"
        subprocess.run(["llvm-objcopy", "-I", "binary", "-O", "elf32-littlearm", "-B", "arm", str(binary), str(obj)], check=True)
        subprocess.run(["llvm-objcopy", "--set-section-flags", ".data=alloc,code,load,readonly", str(obj)], check=True)
        out = subprocess.check_output([
            "llvm-objdump", "-d", "-j", ".data", "--triple=thumbv7r-none-eabi",
            "--no-show-raw-insn", f"--adjust-vma={hex(ramstart)}", str(obj)
        ], text=True, errors="replace")
    ins=[]
    for line in out.splitlines():
        m=LINE_RE.match(line)
        if m:
            ins.append((int(m.group(1),16), m.group(2).lower(), m.group(3).strip().lower()))
    return ins


def norm(mnem: str, ops: str) -> str:
    x = re.sub(r"r(?:1[0-5]|[0-9])|sp|lr|pc", "r", ops)
    x = re.sub(r"0x[0-9a-f]+", "#", x)
    x = re.sub(r"#[+-]?[0-9]+", "#", x)
    x = re.sub(r"\s+", "", x)
    mem = "m" if "[" in x else ""
    imm = "i" if "#" in x else ""
    return f"{mnem}:{mem}{imm}"


def slice_from(ins, addr, maxn=140):
    idx=min(range(len(ins)), key=lambda i: abs(ins[i][0]-addr))
    seq=[]
    for a,m,o in ins[idx:idx+maxn]:
        seq.append((a,m,o))
        if len(seq)>=32:
            if m=="bx" and "lr" in o: break
            if m.startswith("pop") and "pc" in o: break
            if m.startswith("ldmia") and "pc" in o: break
    return seq


def grams(seq, n=3):
    toks=[norm(m,o) for _,m,o in seq]
    return collections.Counter(tuple(toks[i:i+n]) for i in range(max(0,len(toks)-n+1)))


def cosine(a,b):
    if not a or not b: return 0.0
    keys=set(a)|set(b)
    dot=sum(a[k]*b[k] for k in keys)
    na=math.sqrt(sum(v*v for v in a.values())); nb=math.sqrt(sum(v*v for v in b.values()))
    return dot/(na*nb) if na and nb else 0.0


def candidates(ins):
    out=[]
    for i,(a,m,o) in enumerate(ins):
        if m.startswith("push") or (m.startswith("stmdb") and "sp" in o):
            out.append(i)
    return out


def rank(refseq, newins, limit=16):
    rg=grams(refseq,3); rh=collections.Counter(norm(m,o) for _,m,o in refseq)
    nref=len(refseq)
    scored=[]
    for idx in candidates(newins):
        seq=newins[idx:idx+nref]
        if len(seq)<max(24,nref//2): continue
        s3=cosine(rg, grams(seq,3))
        sh=cosine(rh, collections.Counter(norm(m,o) for _,m,o in seq))
        score=0.75*s3+0.25*sh
        if score>=0.12:
            scored.append((score,newins[idx][0],len(seq)))
    scored.sort(reverse=True)
    return scored[:limit]


def main():
    if len(sys.argv)!=4:
        raise SystemExit("usage: find_bcm4375_sdr.py <bcm43455-7.45.154.bin> <bcm4375-18.41.117.bin> <report.txt>")
    old=Path(sys.argv[1]); new=Path(sys.argv[2]); report=Path(sys.argv[3])
    oi=disassemble(old,OLD_RAMSTART); ni=disassemble(new,NEW_RAMSTART)
    if not oi or not ni:
        raise SystemExit(f"disassembly produced no instructions: old={len(oi)} new={len(ni)}")
    lines=[]
    lines += ["BCM4375B1_SDR_STATIC_LOCATOR=1", "AUTO_ENABLE=0", "POLICY=MANUAL_REVIEW_BEFORE_ANY_CALL", f"OLD_INSTRUCTIONS={len(oi)}", f"NEW_INSTRUCTIONS={len(ni)}"]
    for name,addr in REFERENCES.items():
        ref=slice_from(oi,addr)
        lines += ["", f"REFERENCE={name}", f"REFERENCE_ADDR=0x{addr:08X}", f"REFERENCE_INSNS={len(ref)}"]
        ranked=rank(ref,ni)
        lines.append(f"CANDIDATE_COUNT={len(ranked)}")
        for pos,(score,caddr,n) in enumerate(ranked,1):
            lines.append(f"CANDIDATE_{pos:02d}=0x{caddr:08X} SCORE={score:.5f} WINDOW={n}")
    lines += ["", "NOTE=Candidates are similarity hints only; no address is patched or called by this workflow."]
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines)+"\n")
    print(report.read_text())

if __name__ == "__main__":
    main()
