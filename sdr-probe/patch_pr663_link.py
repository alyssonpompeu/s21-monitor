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

# Keep the firmware shim intentionally tiny.  Human-readable diagnostics,
# AFHDS2A framing and IQ synthesis live in the Android/native userspace tool.
# This matters because PR663 has only a 0x3000-byte patch region.
block = r'''        case 0x643:
        {
            struct ml_caps {
                uint32 magic;
                uint16 status;
                uint16 corerev;
                uint16 chanspec;
                uint16 collect_start;
                uint16 collect_stop;
                uint16 collect_cur;
                uint16 play_start;
                uint16 play_stop;
                uint16 xmt_lo;
                uint16 xmt_hi;
                uint16 xmt_ptr;
                uint16 play_ctrl;
            };
            struct ml_caps *q = (struct ml_caps *)arg;
            volatile struct d11regs *r = wlc->regs;
            if (len >= sizeof(struct ml_caps)) {
                q->magic = 0x4d4c4331;
                q->status = r ? 1 : 0;
                q->corerev = wlc->hw ? wlc->hw->corerev : 0xffff;
                q->chanspec = wlc->chanspec;
                if (r) {
                    q->collect_start = r->u.d11acregs.SampleCollectStartPtr;
                    q->collect_stop = r->u.d11acregs.SampleCollectStopPtr;
                    q->collect_cur = r->u.d11acregs.SampleCollectCurPtr;
                    q->play_start = r->u.d11acregs.SamplePlayStartPtr;
                    q->play_stop = r->u.d11acregs.SamplePlayStopPtr;
                    q->xmt_lo = r->u.d11acregs.XmtTemplateDataLo;
                    q->xmt_hi = r->u.d11acregs.XmtTemplateDataHi;
                    q->xmt_ptr = r->u.d11acregs.XmtTemplatePtr;
                    q->play_ctrl = r->u.d11acregs.SampleCollectPlayCtrl;
                }
                ret = IOCTL_SUCCESS;
            }
        }
        break;

        case 0x645:
        {
            struct ml_iq {
                uint32 magic;
                uint16 start;
                uint16 count;
                uint16 ptr_scale;
                uint16 status;
                uint32 words[];
            };
            struct ml_iq *q = (struct ml_iq *)arg;
            volatile struct d11regs *r = wlc->regs;
            int i;
            if (!r || len < 12 || q->magic != 0x4d415258 || q->count == 0 ||
                q->count > 1024 || len < 12 + ((int)q->count * 4)) {
                if (len >= 12) q->status = 0;
                ret = IOCTL_SUCCESS;
                break;
            }
            q->status = 0;
            for (i = 0; i < q->count; i++) {
                uint16 ps = q->ptr_scale ? q->ptr_scale : 1;
                uint16 pi = q->start + (uint16)(i * ps);
                uint32 v = q->words[i];
                r->u.d11acregs.XmtTemplatePtr = pi;
                r->u.d11acregs.XmtTemplateDataLo = (uint16)v;
                r->u.d11acregs.XmtTemplateDataHi = (uint16)(v >> 16);
            }
            q->status = 1;
            ret = IOCTL_SUCCESS;
        }
        break;

        case 0x646:
        {
            struct ml_play {
                uint32 magic;
                uint16 start;
                uint16 count;
                uint16 wifi_channel;
                uint16 control_mode;
                uint16 duration_us;
                uint16 status;
                uint16 old_start;
                uint16 old_stop;
                uint16 old_ctrl;
                uint16 cur_before;
                uint16 cur_after;
                uint16 new_ctrl;
            };
            struct ml_play *q = (struct ml_play *)arg;
            volatile struct d11regs *r = wlc->regs;
            if (!r || len < sizeof(struct ml_play) || q->magic != 0x4d415258 ||
                q->count == 0 || q->count > 60000 || q->duration_us == 0 ||
                q->duration_us > 1500 || q->control_mode < 1 || q->control_mode > 3 ||
                q->wifi_channel < 1 || q->wifi_channel > 13) {
                if (len >= sizeof(struct ml_play)) q->status = 0;
                ret = IOCTL_SUCCESS;
                break;
            }

            set_scansuppress(wlc, 1);
            set_mpc(wlc, 0);
            set_chanspec(wlc, CH20MHZ_CHSPEC(q->wifi_channel));

            q->old_start = r->u.d11acregs.SamplePlayStartPtr;
            q->old_stop = r->u.d11acregs.SamplePlayStopPtr;
            q->old_ctrl = r->u.d11acregs.SampleCollectPlayCtrl;
            q->cur_before = r->u.d11acregs.SampleCollectCurPtr;
            r->u.d11acregs.SamplePlayStartPtr = q->start;
            r->u.d11acregs.SamplePlayStopPtr = q->start + q->count;
            if (q->control_mode == 1)
                q->new_ctrl = q->old_ctrl | (1u << 9);
            else if (q->control_mode == 2)
                q->new_ctrl = (1u << 9);
            else
                q->new_ctrl = q->old_ctrl | (1u << 9) | (1u << 1);
            r->u.d11acregs.SampleCollectPlayCtrl = q->new_ctrl;
            udelay(q->duration_us);
            q->cur_after = r->u.d11acregs.SampleCollectCurPtr;
            r->u.d11acregs.SampleCollectPlayCtrl = q->old_ctrl;
            r->u.d11acregs.SamplePlayStartPtr = q->old_start;
            r->u.d11acregs.SamplePlayStopPtr = q->old_stop;
            q->status = 1 | ((q->cur_after != q->cur_before) ? 2 : 0);
            ret = IOCTL_SUCCESS;
        }
        break;

'''

p.write_text(s.replace(marker, block + marker, 1))
print("MARX LINK compact patch applied to", p)
