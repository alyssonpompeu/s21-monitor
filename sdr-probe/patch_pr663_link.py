#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_pr663_link.py <ioctl.c>")

p = Path(sys.argv[1])
s = p.read_text()

if "case 0x643" in s:
    raise SystemExit("MARX LINK cases already present")

# PR663 has a very small patch region on BCM4375B1.  The upstream experimental
# ioctl.c carries canned EAPOL/auth/assoc frames plus nine test-only injection
# commands.  MARX LINK never uses those fixtures: AFHDS2A framing and IQ are
# synthesized in the ARM64 userspace helper.  Remove the fixtures/commands to
# reclaim patch RAM instead of expanding the firmware patch region.

def cut_between(text: str, start: str, end: str, keep_end: bool = True) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"start marker not found: {start!r}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"end marker not found: {end!r}")
    return text[:a] + (text[b:] if keep_end else text[b + len(end):])

# Remove all canned Wi-Fi test payloads.  Keep orig_call and the actual hook.
s = cut_between(s, "uint8_t eapol1[] = {\n", "static int orig_call = 0;\n")

# Remove generic frame-injection ioctl from this dedicated build.  Monitor,
# normal WLC ioctls and Nexmon version reporting remain intact.
s = cut_between(s, "        case NEX_INJECT_FRAME:\n", "        case WLC_GET_MONITOR:\n")

# Remove console dump command; it pulls extra log-buffer code into the patch.
console_start = "        case 0x609: // return console\n"
fixture_start = "        case 0x621:\n"
a = s.find(console_start)
b = s.find(fixture_start, a if a >= 0 else 0)
if a >= 0 and b >= 0:
    s = s[:a] + s[b:]

# Remove the remaining test-only 0x621..0x629 canned transmit cases.
s = cut_between(s, "        case 0x621:\n", "        default:\n")

# Keep the firmware shim intentionally small.  Human-readable diagnostics,
# AFHDS2A framing, GFSK synthesis and experiment orchestration live in the
# Android/native userspace tool.  Firmware only exposes the D11AC portal.
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

marker = "        default:\n"
if marker not in s:
    raise SystemExit("default ioctl insertion point changed")
s = s.replace(marker, block + marker, 1)

p.write_text(s)
print("MARX LINK lean SDR patch applied to", p)
print("removed upstream canned injection fixtures and ioctls 0x621..0x629")
