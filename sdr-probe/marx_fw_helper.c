typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;

#define MARX_MAGIC 0x4d415258u
#define OP_CAPS 1u
#define OP_WRITE 2u
#define OP_PLAY 3u
#define OP_RESTORE 4u

struct req {
    u32 magic;
    u16 op;
    u16 status;
    u16 start;
    u16 count;
    u16 ptr_scale;
    u16 wifi_channel;
    u16 control_mode;
    u16 duration_us;
    u16 corerev;
    u16 chanspec;
    u16 collect_start;
    u16 collect_stop;
    u16 collect_cur;
    u16 play_start;
    u16 play_stop;
    u16 xmt_lo;
    u16 xmt_hi;
    u16 xmt_ptr;
    u16 play_ctrl;
    u16 old_start;
    u16 old_stop;
    u16 old_ctrl;
    u16 cur_before;
    u16 cur_after;
    u16 new_ctrl;
    u16 readback_ok;
    u16 reserved;
    u32 words[];
};

static inline u16 rd16(volatile u16 *r, u32 off) { return r[off >> 1]; }
static inline void wr16(volatile u16 *r, u32 off, u16 v) { r[off >> 1] = v; }

__attribute__((section(".text.marx_helper"), used))
int marx_helper(void *wlc, void *arg, unsigned int len)
{
    struct req *q = (struct req *)arg;
    volatile u16 *r;
    u8 *w = (u8 *)wlc;
    u32 i;
    if (!wlc || !arg || len < sizeof(struct req)) return 0;
    if (q->magic != MARX_MAGIC) { q->status = 0xe001; return 0; }
    r = *(volatile u16 **)(w + 0x0c);
    if (!r) { q->status = 0xe002; return 0; }

    q->corerev = 0xffff;
    {
        u8 *hw = *(u8 **)(w + 0x18);
        if (hw) q->corerev = *(u32 *)(hw + 0x44);
    }
    q->chanspec = *(u16 *)(w + 0x28e);
    q->collect_start = rd16(r, 0x552);
    q->collect_stop  = rd16(r, 0x554);
    q->collect_cur   = rd16(r, 0x556);
    q->play_start    = rd16(r, 0x55a);
    q->play_stop     = rd16(r, 0x55c);
    q->xmt_lo        = rd16(r, 0x560);
    q->xmt_hi        = rd16(r, 0x562);
    q->xmt_ptr       = rd16(r, 0x564);
    q->play_ctrl     = rd16(r, 0xb2e);

    if (q->op == OP_CAPS) {
        q->status = (q->corerev == 82) ? 0x1001 : 0x1002;
        return 0;
    }

    if (q->op == OP_WRITE) {
        u32 need = (u32)sizeof(struct req) + ((u32)q->count * 4u);
        u16 scale = q->ptr_scale ? q->ptr_scale : 1;
        if (!q->count || q->count > 900 || len < need) { q->status=0xe010; return 0; }
        q->readback_ok = 1;
        for (i=0;i<q->count;i++) {
            u16 p = (u16)(q->start + (u16)(i * scale));
            u32 v = q->words[i];
            wr16(r,0x564,p);
            wr16(r,0x560,(u16)v);
            wr16(r,0x562,(u16)(v>>16));
        }
        wr16(r,0x564,q->start);
        {
            u32 got = (u32)rd16(r,0x560) | ((u32)rd16(r,0x562)<<16);
            if (got != q->words[0]) q->readback_ok = 0;
        }
        if (q->count > 1) {
            u16 p = (u16)(q->start + (u16)((q->count-1) * scale));
            wr16(r,0x564,p);
            {
                u32 got = (u32)rd16(r,0x560) | ((u32)rd16(r,0x562)<<16);
                if (got != q->words[q->count-1]) q->readback_ok = 0;
            }
        }
        q->xmt_ptr = rd16(r,0x564);
        q->xmt_lo = rd16(r,0x560);
        q->xmt_hi = rd16(r,0x562);
        q->status = q->readback_ok ? 0x2001 : 0x2002;
        return 0;
    }

    if (q->op == OP_PLAY) {
        typedef unsigned int (*udelay_t)(int);
        udelay_t udelay_fw = (udelay_t)0x00142241u;
        if (!q->count || q->count > 60000 || !q->duration_us || q->duration_us > 750 ||
            q->control_mode < 1 || q->control_mode > 4) { q->status=0xe020; return 0; }
        q->old_start = rd16(r,0x55a);
        q->old_stop  = rd16(r,0x55c);
        q->old_ctrl  = rd16(r,0xb2e);
        q->cur_before= rd16(r,0x556);
        wr16(r,0x55a,q->start);
        wr16(r,0x55c,(u16)(q->start + q->count));
        if (q->control_mode == 1) q->new_ctrl = (u16)(q->old_ctrl | 0x0200);
        else if (q->control_mode == 2) q->new_ctrl = 0x0200;
        else if (q->control_mode == 3) q->new_ctrl = (u16)(q->old_ctrl | 0x0202);
        else q->new_ctrl = (u16)(q->old_ctrl | 0x0201);
        wr16(r,0xb2e,q->new_ctrl);
        udelay_fw((int)q->duration_us);
        q->cur_after = rd16(r,0x556);
        wr16(r,0xb2e,q->old_ctrl);
        wr16(r,0x55a,q->old_start);
        wr16(r,0x55c,q->old_stop);
        q->play_ctrl = rd16(r,0xb2e);
        q->status = (q->cur_after != q->cur_before) ? 0x3003 : 0x3001;
        return 0;
    }

    if (q->op == OP_RESTORE) {
        wr16(r,0xb2e,q->old_ctrl);
        wr16(r,0x55a,q->old_start);
        wr16(r,0x55c,q->old_stop);
        q->status=0x4001;
        return 0;
    }

    q->status = 0xefff;
    return 0;
}
