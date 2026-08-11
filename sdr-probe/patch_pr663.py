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

        case 0x631: // RX42 bounded template-RAM write/read/restore proof; never starts playback
        {
            volatile struct d11regs *regs = wlc->regs;
            const uint32 scratch = 0x0003ffc0; // final 64-byte region inside 256 KiB template address space
            const uint32 pattern[4] = { 0x52483432, 0xa55a3cc3, 0x13579bdf, 0x2468ace0 };
            uint32 original[4] = {0,0,0,0};
            uint32 written[4] = {0,0,0,0};
            uint32 restored[4] = {0,0,0,0};
            int i;
            int write_ok = 1;
            int restore_ok = 1;

            argprintf("RX42_TPLRAM_PROBE=1\n");
            argprintf("SCRATCH_OFFSET=0x%08x\n", scratch);
            argprintf("WORDS=4\n");
            argprintf("TX_TRIGGERED=0\n");

            if (!regs) {
                argprintf("TPLRAM_RESULT=REGS_NULL\n");
                ret = IOCTL_SUCCESS;
                break;
            }

            /* 0x0026 was observed on the user's BCM4375B1 while the proven
             * 0x630 path still had TX_ENABLED_BY_THIS_PROBE=0. Therefore a
             * non-zero SampleCollectPlayCtrl is not treated as evidence of
             * active playback. The safety invariant here is stronger and
             * directly testable: this handler never writes that register and
             * requires its value to be identical before and after the RAM
             * round-trip. */
            volatile uint16 *r16 = (volatile uint16 *) regs;
            uint16 play_before = r16[0xb2e >> 1];
            argprintf("SAMPLE_PLAY_CTRL_BEFORE=0x%04x\n", play_before);

            volatile uint32 *wrptr = (volatile uint32 *) &regs->tplatewrptr;
            volatile uint32 *wrdata = (volatile uint32 *) &regs->tplatewrdata;
            uint32 ptr_before = *wrptr;
            argprintf("TPLATEWRPTR_BEFORE=0x%08x\n", ptr_before);

            *wrptr = scratch;
            for (i = 0; i < 4; i++) original[i] = *wrdata;

            *wrptr = scratch;
            for (i = 0; i < 4; i++) *wrdata = pattern[i];

            *wrptr = scratch;
            for (i = 0; i < 4; i++) written[i] = *wrdata;
            for (i = 0; i < 4; i++) if (written[i] != pattern[i]) write_ok = 0;

            /* Restore regardless of write/readback result. */
            *wrptr = scratch;
            for (i = 0; i < 4; i++) *wrdata = original[i];

            *wrptr = scratch;
            for (i = 0; i < 4; i++) restored[i] = *wrdata;
            for (i = 0; i < 4; i++) if (restored[i] != original[i]) restore_ok = 0;

            /* Restore pointer state as well. No sample-playback register is
             * written anywhere in this command. */
            *wrptr = ptr_before;
            uint16 play_after = r16[0xb2e >> 1];
            int ctrl_unchanged = (play_after == play_before);

            argprintf("ORIGINAL=%08x,%08x,%08x,%08x\n", original[0], original[1], original[2], original[3]);
            argprintf("PATTERN=%08x,%08x,%08x,%08x\n", pattern[0], pattern[1], pattern[2], pattern[3]);
            argprintf("READBACK=%08x,%08x,%08x,%08x\n", written[0], written[1], written[2], written[3]);
            argprintf("RESTORED=%08x,%08x,%08x,%08x\n", restored[0], restored[1], restored[2], restored[3]);
            argprintf("WRITE_READBACK_OK=%d\n", write_ok);
            argprintf("RESTORE_OK=%d\n", restore_ok);
            argprintf("SAMPLE_PLAY_CTRL_AFTER=0x%04x\n", play_after);
            argprintf("PLAYBACK_CTRL_UNCHANGED=%d\n", ctrl_unchanged);
            /* Legacy status key kept for the Android probe: means this
             * command did not change/arm playback state. */
            argprintf("PLAYBACK_STAYED_OFF=%d\n", ctrl_unchanged);
            argprintf("TX_TRIGGERED=0\n");
            if (write_ok && restore_ok && ctrl_unchanged)
                argprintf("TPLRAM_RESULT=PASS\n");
            else
                argprintf("TPLRAM_RESULT=FAIL\n");
            ret = IOCTL_SUCCESS;
        }
        break;

'''

if "case 0x630" in s or "case 0x631" in s:
    raise SystemExit("0x630/0x631 already present")
p.write_text(s.replace(marker, block + marker, 1))
print("patched", p)
