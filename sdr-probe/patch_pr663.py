#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_pr663.py <ioctl.c>")

p = Path(sys.argv[1])
s = p.read_text()
marker = "        case 0x621:\n"
if marker not in s:
    raise SystemExit("PR663 ioctl insertion point changed")

block = r'''        case 0x630: // RX42/BCM4375 read-only SDR register probe
        {
            volatile struct d11regs *regs = wlc->regs;
            argprintf("RX42_SDR_PROBE=1\n");
            argprintf("COREREV=%u\n", wlc->hw ? wlc->hw->corerev : 0xffffffff);
            argprintf("CHANSPEC=0x%04x\n", wlc->chanspec);
            argprintf("MONITOR=0x%08x\n", wlc->monitor);
            if (regs) {
                volatile uint16 *r16 = (volatile uint16 *) regs;
                argprintf("XMT_TEMPLATE_PTR=0x%04x\n", r16[0x550 >> 1]);
                argprintf("SMPL_CLCT_STRPTR=0x%04x\n", r16[0x552 >> 1]);
                argprintf("SMPL_CLCT_STPPTR=0x%04x\n", r16[0x554 >> 1]);
                argprintf("SMPL_CLCT_CURPTR=0x%04x\n", r16[0x556 >> 1]);
                argprintf("SAMPLE_PLAY_CTRL=0x%04x\n", r16[0xb2e >> 1]);
                argprintf("SDR_REGISTER_BLOCK=ACCESSIBLE\n");
            } else {
                argprintf("SDR_REGISTER_BLOCK=NULL\n");
            }
            argprintf("TX_ENABLED_BY_THIS_PROBE=0\n");
            ret = IOCTL_SUCCESS;
        }
        break;

'''

if "case 0x630" in s:
    raise SystemExit("0x630 already present")
p.write_text(s.replace(marker, block + marker, 1))
print("patched", p)
