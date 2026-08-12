#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_pr663_link.py <ioctl.c>")

p = Path(sys.argv[1])
s = p.read_text()
marker = "        case 0x621:\n"
if marker not in s:
    raise SystemExit("PR663 ioctl insertion point changed")
if "case 0x643" in s:
    raise SystemExit("MARX LINK cases already present")

block = r'''        /*
         * MARX LINK V1.0
         *
         * The earlier MARX probe treated the legacy 0x130/0x134 portal as
         * ordinary RAM.  On corerev 82 that produced a repeated last-word
         * readback.  This patch follows the register layout used by the
         * Nexmon SDR implementation for AC cores instead: SamplePlayStartPtr,
         * SamplePlayStopPtr, XmtTemplateDataLo/Hi/Ptr and
         * SampleCollectPlayCtrl in the d11ac register block.
         *
         * 0x643 = capability/register map (read only)
         * 0x644 = bounded XmtTemplate portal characterization (write/read/
         *         restore portal registers; no playback)
         * 0x645 = write caller supplied IQ words into XmtTemplate portal
         * 0x646 = bounded one-shot SampleCollectPlayCtrl experiment
         *
         * 0x646 never runs endlessly.  It requires a magic cookie, clamps the
         * window to <= 4096 samples and <= 1500 us, then restores all touched
         * control registers.  It is deliberately experimental because the
         * BCM4375B1 equivalent of wlc_phy_runsamples_acphy is not yet mapped.
         */

        case 0x643: // corrected AC-core sample-playback capability map
        {
            volatile struct d11regs *regs = wlc->regs;
            argprintf("MARX_LINK_CAPS=1\n");
            argprintf("COREREV=%u\n", wlc->hw ? wlc->hw->corerev : 0xffffffff);
            argprintf("CHANSPEC=0x%04x\n", wlc->chanspec);
            if (!regs) {
                argprintf("D11REGS=NULL\nSDR_TX_READY=0\n");
                ret = IOCTL_SUCCESS;
                break;
            }
            argprintf("AC_SAMPLE_COLLECT_START=0x%04x\n", regs->u.d11acregs.SampleCollectStartPtr);
            argprintf("AC_SAMPLE_COLLECT_STOP=0x%04x\n", regs->u.d11acregs.SampleCollectStopPtr);
            argprintf("AC_SAMPLE_COLLECT_CUR=0x%04x\n", regs->u.d11acregs.SampleCollectCurPtr);
            argprintf("AC_SAMPLE_PLAY_START=0x%04x\n", regs->u.d11acregs.SamplePlayStartPtr);
            argprintf("AC_SAMPLE_PLAY_STOP=0x%04x\n", regs->u.d11acregs.SamplePlayStopPtr);
            argprintf("AC_XMT_TEMPLATE_LO=0x%04x\n", regs->u.d11acregs.XmtTemplateDataLo);
            argprintf("AC_XMT_TEMPLATE_HI=0x%04x\n", regs->u.d11acregs.XmtTemplateDataHi);
            argprintf("AC_XMT_TEMPLATE_PTR=0x%04x\n", regs->u.d11acregs.XmtTemplatePtr);
            argprintf("AC_SAMPLE_PLAY_CTRL=0x%04x\n", regs->u.d11acregs.SampleCollectPlayCtrl);
            argprintf("REGISTER_LAYOUT=D11AC_COREREV_GE50\n");
            argprintf("LEGACY_0130_PORTAL_NOT_USED_FOR_LINK=1\n");
            argprintf("SDR_METHOD=NEXMON_SDR_AC_STYLE\n");
            argprintf("SDR_TX_READY=0\n");
            ret = IOCTL_SUCCESS;
        }
        break;

        case 0x644: // characterize modern XmtTemplate portal without playback
        {
            volatile struct d11regs *regs = wlc->regs;
            const uint16 ptrs[4] = { 0x3fc0, 0x3fc1, 0x3fc2, 0x3fc3 };
            const uint32 pattern[4] = { 0x52483432, 0xa55a3cc3, 0x13579bdf, 0x2468ace0 };
            uint32 echoed[4] = {0,0,0,0};
            uint16 ptr_before, lo_before, hi_before;
            int i;

            argprintf("MARX_LINK_PORTAL=1\n");
            argprintf("PORTAL=XmtTemplateDataLo_Hi_Ptr\n");
            argprintf("PLAYBACK_CALL_MADE=0\nTX_TRIGGERED=0\n");
            if (!regs) {
                argprintf("PORTAL_RESULT=REGS_NULL\n");
                ret = IOCTL_SUCCESS;
                break;
            }

            ptr_before = regs->u.d11acregs.XmtTemplatePtr;
            lo_before = regs->u.d11acregs.XmtTemplateDataLo;
            hi_before = regs->u.d11acregs.XmtTemplateDataHi;
            argprintf("PTR_BEFORE=0x%04x\nLO_BEFORE=0x%04x\nHI_BEFORE=0x%04x\n", ptr_before, lo_before, hi_before);
            argprintf("PLAY_CTRL_BEFORE=0x%04x\n", regs->u.d11acregs.SampleCollectPlayCtrl);

            for (i = 0; i < 4; i++) {
                regs->u.d11acregs.XmtTemplatePtr = ptrs[i];
                regs->u.d11acregs.XmtTemplateDataLo = (uint16)(pattern[i] & 0xffff);
                regs->u.d11acregs.XmtTemplateDataHi = (uint16)((pattern[i] >> 16) & 0xffff);
                echoed[i] = ((uint32)regs->u.d11acregs.XmtTemplateDataHi << 16) |
                            (uint32)regs->u.d11acregs.XmtTemplateDataLo;
            }

            /* Restore only the portal registers.  Firmware/Wi-Fi is reloaded by
             * the Android harness after each experiment, so no persistent state
             * survives even if the portal maps to transient template storage. */
            regs->u.d11acregs.XmtTemplatePtr = ptr_before;
            regs->u.d11acregs.XmtTemplateDataLo = lo_before;
            regs->u.d11acregs.XmtTemplateDataHi = hi_before;

            argprintf("PTRS=%04x,%04x,%04x,%04x\n", ptrs[0],ptrs[1],ptrs[2],ptrs[3]);
            argprintf("PATTERN=%08x,%08x,%08x,%08x\n", pattern[0],pattern[1],pattern[2],pattern[3]);
            argprintf("PORT_ECHO=%08x,%08x,%08x,%08x\n", echoed[0],echoed[1],echoed[2],echoed[3]);
            argprintf("PLAY_CTRL_AFTER=0x%04x\n", regs->u.d11acregs.SampleCollectPlayCtrl);
            argprintf("PLAYBACK_CALL_MADE=0\nTX_TRIGGERED=0\n");
            argprintf("PORTAL_RESULT=EXECUTED\n");
            ret = IOCTL_SUCCESS;
        }
        break;

        case 0x645: // write IQ words using AC XmtTemplate portal; no playback
        {
            struct marx_iq_write {
                uint32 magic;
                uint16 start;
                uint16 count;
                uint16 ptr_scale;
                uint16 reserved;
                uint32 words[];
            };
            struct marx_iq_write *q = (struct marx_iq_write *)arg;
            volatile struct d11regs *regs = wlc->regs;
            uint16 oldptr;
            int i;

            if (!regs || len < 12 || q->magic != 0x4d415258 || q->count == 0 || q->count > 1024 ||
                len < 12 + ((int)q->count * 4)) {
                argprintf("MARX_IQ_WRITE=REJECTED\nTX_TRIGGERED=0\n");
                ret = IOCTL_SUCCESS;
                break;
            }
            oldptr = regs->u.d11acregs.XmtTemplatePtr;
            for (i = 0; i < q->count; i++) {
                uint16 pidx = q->start + (uint16)(i * (q->ptr_scale ? q->ptr_scale : 1));
                uint32 v = q->words[i];
                regs->u.d11acregs.XmtTemplatePtr = pidx;
                regs->u.d11acregs.XmtTemplateDataLo = (uint16)(v & 0xffff);
                regs->u.d11acregs.XmtTemplateDataHi = (uint16)((v >> 16) & 0xffff);
            }
            argprintf("MARX_IQ_WRITE=OK\nIQ_START=0x%04x\nIQ_COUNT=%u\nPTR_SCALE=%u\n", q->start, q->count, q->ptr_scale ? q->ptr_scale : 1);
            argprintf("PTR_AFTER_WRITE=0x%04x\n", regs->u.d11acregs.XmtTemplatePtr);
            regs->u.d11acregs.XmtTemplatePtr = oldptr;
            argprintf("PLAYBACK_CALL_MADE=0\nTX_TRIGGERED=0\n");
            ret = IOCTL_SUCCESS;
        }
        break;

        case 0x646: // strictly bounded direct AC playback experiment
        {
            struct marx_play {
                uint32 magic;
                uint16 start;
                uint16 count;
                uint16 wifi_channel;
                uint16 control_mode;
                uint16 duration_us;
                uint16 reserved;
            };
            struct marx_play *q = (struct marx_play *)arg;
            volatile struct d11regs *regs = wlc->regs;
            uint16 old_start, old_stop, old_ctrl, cur_before, cur_after;
            uint16 newctrl;

            if (!regs || len < 16 || q->magic != 0x4d415258 || q->count == 0 || q->count > 4096 ||
                q->duration_us == 0 || q->duration_us > 1500 || q->control_mode < 1 || q->control_mode > 3 ||
                q->wifi_channel < 1 || q->wifi_channel > 13) {
                argprintf("MARX_PLAY=REJECTED\nTX_TRIGGERED=0\n");
                ret = IOCTL_SUCCESS;
                break;
            }

            set_scansuppress(wlc, 1);
            set_mpc(wlc, 0);
            set_chanspec(wlc, CH20MHZ_CHSPEC(q->wifi_channel));

            old_start = regs->u.d11acregs.SamplePlayStartPtr;
            old_stop = regs->u.d11acregs.SamplePlayStopPtr;
            old_ctrl = regs->u.d11acregs.SampleCollectPlayCtrl;
            cur_before = regs->u.d11acregs.SampleCollectCurPtr;

            regs->u.d11acregs.SamplePlayStartPtr = q->start;
            regs->u.d11acregs.SamplePlayStopPtr = q->start + q->count;

            if (q->control_mode == 1)
                newctrl = (uint16)(old_ctrl | (1u << 9));
            else if (q->control_mode == 2)
                newctrl = (uint16)(1u << 9);
            else
                newctrl = (uint16)(old_ctrl | (1u << 9) | (1u << 1));

            argprintf("MARX_PLAY=ARMING_BOUNDED\nWIFI_CHANNEL=%u\nPLAY_START=0x%04x\nPLAY_STOP=0x%04x\n", q->wifi_channel, q->start, q->start + q->count);
            argprintf("CTRL_BEFORE=0x%04x\nCTRL_REQUEST=0x%04x\nCUR_BEFORE=0x%04x\n", old_ctrl, newctrl, cur_before);
            argprintf("KNOWN_LIMITATION=WLC_PHY_RUNSAMPLES_NOT_MAPPED_FOR_BCM4375B1\n");

            regs->u.d11acregs.SampleCollectPlayCtrl = newctrl;
            udelay(q->duration_us);
            cur_after = regs->u.d11acregs.SampleCollectCurPtr;
            regs->u.d11acregs.SampleCollectPlayCtrl = old_ctrl;
            regs->u.d11acregs.SamplePlayStartPtr = old_start;
            regs->u.d11acregs.SamplePlayStopPtr = old_stop;

            argprintf("CUR_AFTER=0x%04x\nCTRL_RESTORED=0x%04x\n", cur_after, regs->u.d11acregs.SampleCollectPlayCtrl);
            argprintf("CUR_MOVED=%d\n", cur_after != cur_before);
            argprintf("TX_EXPERIMENT_ATTEMPTED=1\nTX_WINDOW_US=%u\n", q->duration_us);
            argprintf("TX_TRIGGERED=%d\n", cur_after != cur_before);
            argprintf("MARX_PLAY_RESULT=%s\n", cur_after != cur_before ? "ACTIVITY_OBSERVED" : "NO_ACTIVITY");
            ret = IOCTL_SUCCESS;
        }
        break;

'''

p.write_text(s.replace(marker, block + marker, 1))
print("MARX LINK patch applied to", p)
