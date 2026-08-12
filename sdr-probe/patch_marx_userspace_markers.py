#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_marx_userspace_markers.py <marxlinkprobe.c>")
p = Path(sys.argv[1])
s = p.read_text()
old = 'printf("PLAY_ACTIVITY_HINT=%d\\n",q.status==0x3003);'
new = ('printf("PLAY_ACTIVITY_HINT=%d\\nTX_TRIGGERED=%d\\nMARX_PLAY_RESULT=%s\\n",'
       'q.status==0x3003,q.status==0x3003,q.status==0x3003?"ACTIVITY_OBSERVED":"NO_ACTIVITY");')
if old not in s:
    raise SystemExit("play marker insertion point changed")
s = s.replace(old, new, 1)
p.write_text(s)
print("Activity-compatible playback markers enabled")
