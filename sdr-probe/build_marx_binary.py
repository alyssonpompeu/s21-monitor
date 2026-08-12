#!/usr/bin/env python3
from pathlib import Path
import hashlib
import sys

BASE_SHA = "15e3b2f6e29bf01c88b8670f2fcf4b40e02d947a2091af5243ddbf9b0dd69c44"
HELPER_OFF = 0x138000
HELPER_LIMIT = 0x138A54
TRAMP_OFF = 0x13923C
TRAMP_PREIMAGE = bytes.fromhex("49 f2 88 60 c0 f2 2a 00")


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_marx_binary.py <base-fw> <helper-bin> <tramp-bin> <output-fw>")
    base_p, helper_p, tramp_p, out_p = map(Path, sys.argv[1:])
    base = bytearray(base_p.read_bytes())
    helper = helper_p.read_bytes()
    tramp = tramp_p.read_bytes()

    got = sha256(base)
    if got != BASE_SHA:
        raise SystemExit(f"unexpected base firmware SHA256: {got}")
    if not helper or len(helper) > (HELPER_LIMIT - HELPER_OFF):
        raise SystemExit(f"helper size invalid: {len(helper)}")
    if not tramp or len(tramp) > 64:
        raise SystemExit(f"trampoline size invalid: {len(tramp)}")
    if any(base[HELPER_OFF:HELPER_OFF + len(helper)]):
        raise SystemExit("helper target region is not zero-filled")
    if bytes(base[TRAMP_OFF:TRAMP_OFF + len(TRAMP_PREIMAGE)]) != TRAMP_PREIMAGE:
        raise SystemExit("0x630 trampoline preimage changed")

    base[HELPER_OFF:HELPER_OFF + len(helper)] = helper
    base[TRAMP_OFF:TRAMP_OFF + len(tramp)] = tramp
    out_p.parent.mkdir(parents=True, exist_ok=True)
    out_p.write_bytes(base)

    print(f"BASE_SHA256={got}")
    print(f"HELPER_OFFSET=0x{HELPER_OFF:x} HELPER_SIZE={len(helper)}")
    print(f"TRAMP_OFFSET=0x{TRAMP_OFF:x} TRAMP_SIZE={len(tramp)}")
    print(f"OUTPUT_SHA256={sha256(base)}")


if __name__ == "__main__":
    main()
