#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_pr663_v2.py <ioctl.c>")
p = Path(sys.argv[1]); s = p.read_text(); marker = "        case 0x621:\n"
if marker not in s: raise SystemExit("PR663 ioctl insertion point changed")
if "case 0x63F" in s or "case 0x640" in s: raise SystemExit("V2 commands already present")
block = r'''        case 0x63F: // MARX RX42 V2 capability gate; read-only, never transmits
        {
            argprintf("MARX_RX42_BCM4375_V2=1\n");
            argprintf("API=MARX_AFHDS2A_BOUNDED_V2\n");
            argprintf("COREREV=%u\n", wlc->hw ? wlc->hw->corerev : 0xffffffff);
            argprintf("D11_REGS=%s\n", wlc->regs ? "PRESENT" : "NULL");
            argprintf("NATIVE_TPLRAM_WRITER=0\nNATIVE_SAMPLE_PLAY=0\nBOUNDED_TX=0\nRX_IQ=0\n");
            argprintf("TX_GATE=CLOSED_NATIVE_MAPPING_REQUIRED\n");
            argprintf("POLICY=AFHDS2A_ONLY_NO_GENERIC_TX_NO_CONTINUOUS_TX\nTX_TRIGGERED=0\n");
            ret = IOCTL_SUCCESS;
        }
        break;

        case 0x640: // MARX RX42 V2 D11 snapshot; read-only
        {
            volatile struct d11regs *regs = wlc->regs;
            argprintf("MARX_REG_SNAPSHOT_V2=1\n");
            argprintf("COREREV=%u\n", wlc->hw ? wlc->hw->corerev : 0xffffffff);
            argprintf("CHANSPEC=0x%04x\n", wlc->chanspec);
            if (!regs) { argprintf("D11_REGS=NULL\nTX_TRIGGERED=0\n"); ret = IOCTL_SUCCESS; break; }
            volatile uint16 *r16 = (volatile uint16 *) regs;
            argprintf("D11_REGS=PRESENT\n");
            argprintf("LEGACY_TPLATEWRPTR=0x%08x\n", regs->tplatewrptr);
            argprintf("LEGACY_TPLATEWRDATA=0x%08x\n", regs->tplatewrdata);
            argprintf("XMT_TEMPLATE_PTR_0550=0x%04x\n", r16[0x550 >> 1]);
            argprintf("SMPL_CLCT_START_0552=0x%04x\n", r16[0x552 >> 1]);
            argprintf("SMPL_CLCT_STOP_0554=0x%04x\n", r16[0x554 >> 1]);
            argprintf("SMPL_CLCT_CUR_0556=0x%04x\n", r16[0x556 >> 1]);
            argprintf("SMPL_PLAY_START_055A=0x%04x\n", r16[0x55a >> 1]);
            argprintf("SMPL_PLAY_STOP_055C=0x%04x\n", r16[0x55c >> 1]);
            argprintf("XMT_TEMPLATE_LO_0560=0x%04x\n", r16[0x560 >> 1]);
            argprintf("XMT_TEMPLATE_HI_0562=0x%04x\n", r16[0x562 >> 1]);
            argprintf("XMT_TEMPLATE_PTR_0564=0x%04x\n", r16[0x564 >> 1]);
            argprintf("SAMPLE_PLAY_CTRL_0B2E=0x%04x\n", r16[0xb2e >> 1]);
            argprintf("SNAPSHOT_WRITES=0\nTX_TRIGGERED=0\n");
            ret = IOCTL_SUCCESS;
        }
        break;

'''
p.write_text(s.replace(marker, block + marker, 1)); print("patched", p)
