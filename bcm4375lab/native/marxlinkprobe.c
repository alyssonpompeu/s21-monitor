/*
 * MARX LINK V1.0 userspace engine.
 * Firmware IOCTLs are intentionally binary/compact so PR663 stays inside its
 * 12 KiB patch window. Human-readable diagnostics and AFHDS2A/IQ generation
 * live here instead of consuming scarce BCM4375 patch RAM.
 */
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <net/if.h>
#include <unistd.h>

#ifndef SIOCDEVPRIVATE
#define SIOCDEVPRIVATE 0x89F0
#endif
#define WLC_IOCTL_MAGIC 0x14e46c77u
#define MARX_MAGIC 0x4d415258u
#define CAPS_MAGIC 0x4d4c4331u
#define CMD_VERSION 0x600u
#define CMD_CAPS    0x643u
#define CMD_WRITEIQ 0x645u
#define CMD_PLAY    0x646u
#define SAMPLE_RATE 40000000.0
#define BIT_RATE     500000.0
#define SPS 80
#define MAX_SAMPLES 62000
#define CHUNK_SAMPLES 900

struct nex_ioctl {
    unsigned int cmd;
    void *buf;
    unsigned int len;
    bool set;
    unsigned int used;
    unsigned int needed;
    unsigned int driver;
};

struct caps_resp {
    uint32_t magic;
    uint16_t status;
    uint16_t corerev;
    uint16_t chanspec;
    uint16_t collect_start;
    uint16_t collect_stop;
    uint16_t collect_cur;
    uint16_t play_start;
    uint16_t play_stop;
    uint16_t xmt_lo;
    uint16_t xmt_hi;
    uint16_t xmt_ptr;
    uint16_t play_ctrl;
};

struct iq_write_hdr {
    uint32_t magic;
    uint16_t start;
    uint16_t count;
    uint16_t ptr_scale;
    uint16_t status;
    uint32_t words[];
};

struct play_req {
    uint32_t magic;
    uint16_t start;
    uint16_t count;
    uint16_t wifi_channel;
    uint16_t control_mode;
    uint16_t duration_us;
    uint16_t status;
    uint16_t old_start;
    uint16_t old_stop;
    uint16_t old_ctrl;
    uint16_t cur_before;
    uint16_t cur_after;
    uint16_t new_ctrl;
};

static int call_ioctl(int s, const char *ifname, unsigned int cmd, void *buf, unsigned int len) {
    struct ifreq ifr;
    struct nex_ioctl ioc;
    memset(&ifr, 0, sizeof(ifr));
    memset(&ioc, 0, sizeof(ioc));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    ioc.cmd = cmd;
    ioc.buf = buf;
    ioc.len = len;
    ioc.set = false;
    ioc.driver = WLC_IOCTL_MAGIC;
    ifr.ifr_data = (void *)&ioc;
    errno = 0;
    int ret = ioctl(s, SIOCDEVPRIVATE, &ifr);
    printf("CMD=0x%03x ret=%d errno=%d %s used=%u needed=%u\n",
           cmd, ret, errno, strerror(errno), ioc.used, ioc.needed);
    return ret;
}

static int show_caps(int s, const char *ifname, const char *tag) {
    struct caps_resp q;
    memset(&q, 0, sizeof(q));
    int r = call_ioctl(s, ifname, CMD_CAPS, &q, sizeof(q));
    printf("=== %s ===\n", tag);
    printf("MARX_LINK_CAPS_MAGIC=0x%08x\n", q.magic);
    printf("D11REGS_PRESENT=%d\n", q.status == 1);
    printf("COREREV=%u\nCHANSPEC=0x%04x\n", q.corerev, q.chanspec);
    printf("AC_SAMPLE_COLLECT_START=0x%04x\nAC_SAMPLE_COLLECT_STOP=0x%04x\nAC_SAMPLE_COLLECT_CUR=0x%04x\n",
           q.collect_start, q.collect_stop, q.collect_cur);
    printf("AC_SAMPLE_PLAY_START=0x%04x\nAC_SAMPLE_PLAY_STOP=0x%04x\n", q.play_start, q.play_stop);
    printf("AC_XMT_TEMPLATE_LO=0x%04x\nAC_XMT_TEMPLATE_HI=0x%04x\nAC_XMT_TEMPLATE_PTR=0x%04x\n",
           q.xmt_lo, q.xmt_hi, q.xmt_ptr);
    printf("AC_SAMPLE_PLAY_CTRL=0x%04x\n", q.play_ctrl);
    printf("REGISTER_LAYOUT=D11AC_COREREV_GE50\nLEGACY_0130_PORTAL_NOT_USED_FOR_LINK=1\n");
    printf("SDR_METHOD=NEXMON_SDR_AC_STYLE\nSDR_TX_READY=%d\n", r >= 0 && q.magic == CAPS_MAGIC && q.status == 1);
    return (r >= 0 && q.magic == CAPS_MAGIC && q.status == 1) ? 0 : -1;
}

static uint16_t crc16_ccitt(const uint8_t *p, size_t n) {
    uint16_t crc = 0xffff;
    for (size_t i=0;i<n;i++) {
        crc ^= (uint16_t)p[i] << 8;
        for (int b=0;b<8;b++)
            crc = (crc & 0x8000) ? (uint16_t)((crc<<1)^0x1021) : (uint16_t)(crc<<1);
    }
    return crc;
}

static uint8_t hamming74(uint8_t nibble) {
    uint8_t d0=(nibble>>0)&1, d1=(nibble>>1)&1, d2=(nibble>>2)&1, d3=(nibble>>3)&1;
    uint8_t p1=d3^d2^d0, p2=d3^d1^d0, p4=d2^d1^d0;
    return (uint8_t)((p1<<6)|(p2<<5)|(d3<<4)|(p4<<3)|(d2<<2)|(d1<<1)|d0);
}

struct bits { uint8_t *v; int n; int cap; };
static void bit_push(struct bits *b, int v) { if (b->n < b->cap) b->v[b->n++] = (uint8_t)(v?1:0); }
static void byte_msb(struct bits *b, uint8_t x) { for(int i=7;i>=0;i--) bit_push(b,(x>>i)&1); }
static void h74_msb(struct bits *b, uint8_t n) { uint8_t h=hamming74(n); for(int i=6;i>=0;i--) bit_push(b,(h>>i)&1); }

static int build_air_bits(const uint8_t pkt[38], int profile, struct bits *out) {
    static const uint8_t id[4] = {0x54,0x75,0xc5,0x2a};
    uint8_t body[40];
    memcpy(body,pkt,38);
    uint16_t crc=crc16_ccitt(pkt,38);
    body[38]=(uint8_t)(crc>>8); body[39]=(uint8_t)crc;
    for(int i=0;i<4;i++) byte_msb(out,0xaa);
    for(int i=0;i<4;i++) byte_msb(out,id[i]);
    if(profile==1) {
        for(int i=0;i<40;i++) byte_msb(out,body[i]);
    } else if(profile==0) {
        for(int i=0;i<40;i++){h74_msb(out,body[i]>>4);h74_msb(out,body[i]&15);}
    } else {
        for(int i=0;i<40;i++){h74_msb(out,body[i]&15);h74_msb(out,body[i]>>4);}
    }
    return out->n;
}

static uint32_t pack_iq(double iv, double qv, double amp) {
    long i=lround(iv*amp), q=lround(qv*amp);
    if(i>32767)i=32767;if(i<-32768)i=-32768;
    if(q>32767)q=32767;if(q<-32768)q=-32768;
    return ((uint32_t)((uint16_t)(int16_t)i)<<16) | (uint16_t)(int16_t)q;
}

static int mod_gfsk(const struct bits *bits,double center_hz,double dev_hz,uint32_t *out,int max,double amp) {
    double phase=0.0, filt=bits->n?(bits->v[0]?1.0:-1.0):0.0;
    const double alpha=0.11;
    int n=0;
    for(int bi=0;bi<bits->n;bi++) {
        double target=bits->v[bi]?1.0:-1.0;
        for(int k=0;k<SPS;k++) {
            filt += alpha*(target-filt);
            phase += 2.0*M_PI*(center_hz+dev_hz*filt)/SAMPLE_RATE;
            if(phase>M_PI)phase-=2.0*M_PI;
            if(phase<-M_PI)phase+=2.0*M_PI;
            if(n<max)out[n++]=pack_iq(cos(phase),sin(phase),amp);
        }
    }
    return n;
}

static int write_iq(int s,const char *ifname,uint16_t start,uint16_t scale,const uint32_t *iq,int count) {
    int pos=0;
    while(pos<count) {
        int c=count-pos;if(c>CHUNK_SAMPLES)c=CHUNK_SAMPLES;
        size_t bytes=sizeof(struct iq_write_hdr)+(size_t)c*4;
        struct iq_write_hdr *q=calloc(1,bytes);if(!q)return -1;
        q->magic=MARX_MAGIC;q->start=(uint16_t)(start+pos*scale);q->count=(uint16_t)c;q->ptr_scale=scale;
        memcpy(q->words,iq+pos,(size_t)c*4);
        int r=call_ioctl(s,ifname,CMD_WRITEIQ,q,(unsigned)bytes);
        int ok=(r>=0 && q->status==1);
        printf("IQ_CHUNK_START=0x%04x IQ_CHUNK_COUNT=%d IQ_WRITE_OK=%d\n",q->start,c,ok);
        free(q);
        if(!ok)return -1;
        pos+=c;
    }
    printf("MARX_IQ_WRITE=OK\nIQ_TOTAL=%d\nPLAYBACK_CALL_MADE=0\nTX_TRIGGERED=0\n",count);
    return 0;
}

static int play_once(int s,const char *ifname,uint16_t start,uint16_t count,int wifi_ch,int ctrl,int us) {
    struct play_req q;
    memset(&q,0,sizeof(q));
    q.magic=MARX_MAGIC;q.start=start;q.count=count;q.wifi_channel=(uint16_t)wifi_ch;
    q.control_mode=(uint16_t)ctrl;q.duration_us=(uint16_t)us;
    int r=call_ioctl(s,ifname,CMD_PLAY,&q,sizeof(q));
    int accepted=(r>=0 && (q.status&1));
    int moved=accepted && (q.status&2);
    printf("MARX_PLAY_ACCEPTED=%d\nWIFI_CHANNEL=%u\nPLAY_START=0x%04x\nPLAY_COUNT=%u\n",
           accepted,q.wifi_channel,q.start,q.count);
    printf("CTRL_BEFORE=0x%04x\nCTRL_REQUEST=0x%04x\nCUR_BEFORE=0x%04x\nCUR_AFTER=0x%04x\n",
           q.old_ctrl,q.new_ctrl,q.cur_before,q.cur_after);
    printf("KNOWN_LIMITATION=WLC_PHY_RUNSAMPLES_NOT_MAPPED_FOR_BCM4375B1\n");
    printf("TX_EXPERIMENT_ATTEMPTED=%d\nTX_WINDOW_US=%u\nCUR_MOVED=%d\nTX_TRIGGERED=%d\n",
           accepted,q.duration_us,moved,moved);
    printf("MARX_PLAY_RESULT=%s\n",moved?"ACTIVITY_OBSERVED":(accepted?"NO_ACTIVITY":"REJECTED"));
    return accepted?0:-1;
}

static void make_bind1(uint8_t p[38],uint32_t txid,const uint8_t hops[16]) {
    memset(p,0xff,38);p[0]=0xbb;
    p[1]=(uint8_t)txid;p[2]=(uint8_t)(txid>>8);p[3]=(uint8_t)(txid>>16);p[4]=(uint8_t)(txid>>24);
    p[9]=1;p[10]=0;for(int i=0;i<16;i++)p[11+i]=hops[i];p[37]=0;
}

static void calc_hops(uint32_t txid,uint8_t h[16]) {
    uint32_t rnd=txid;int idx=0;uint8_t tx3=(uint8_t)(txid>>24);
    while(idx<16) {
        uint8_t band=(uint8_t)((((idx<<1)|((idx>>1)&1))+tx3)&3);
        rnd=rnd*0x0019660du+0x3c6ef35fu;
        uint8_t next=(uint8_t)(band*41+1+((rnd>>idx)%41));
        int ok=1;for(int j=0;j<idx;j++){int d=(int)next-(int)h[j];if(d<0)d=-d;if(d<5){ok=0;break;}}
        if(ok)h[idx++]=next;
    }
}

static int do_pulse(int s,const char *ifname,int ctrl,int scale) {
    const int ns=1024;uint32_t *iq=malloc(ns*4);if(!iq)return -1;
    double ph=0;for(int i=0;i<ns;i++){ph+=2*M_PI*250000.0/SAMPLE_RATE;iq[i]=pack_iq(cos(ph),sin(ph),500.0);}
    printf("MARX_PULSE=PREPARE samples=%d amp=500 ctrl=%d ptr_scale=%d\n",ns,ctrl,scale);
    int r=write_iq(s,ifname,0x1800,(uint16_t)scale,iq,ns);free(iq);if(r<0)return r;
    return play_once(s,ifname,0x1800,(uint16_t)ns,1,ctrl,100);
}

static int do_bind_candidate(int s,const char *ifname,uint32_t txid,int profile,int ctrl,int scale,int rounds) {
    uint8_t hops[16],pkt[38];calc_hops(txid,hops);make_bind1(pkt,txid,hops);
    printf("MARX_AFHDS2A_BIND_CANDIDATE=1\nTXID=%08x\nPROFILE=%d\nCTRL_MODE=%d\nPTR_SCALE=%d\n",txid,profile,ctrl,scale);
    printf("BIND_PACKET=");for(int i=0;i<38;i++)printf("%02x%s",pkt[i],i==37?"\n":" ");
    printf("HOPS=");for(int i=0;i<16;i++)printf("%u%s",hops[i],i==15?"\n":",");
    uint8_t bitbuf[2048];struct bits bits={bitbuf,0,(int)sizeof(bitbuf)};build_air_bits(pkt,profile,&bits);
    uint32_t *iq=malloc((size_t)MAX_SAMPLES*4);if(!iq)return -1;
    for(int round=0;round<rounds;round++) {
        int a7105=(round&1)?0x0d:0x8c;
        int wifi=(a7105==0x0d)?1:13;
        double rf_mhz=2400.0+0.5*a7105;
        double wifi_mhz=(wifi==1)?2412.0:2472.0;
        double offset=(rf_mhz-wifi_mhz)*1e6;
        int ns=mod_gfsk(&bits,offset,250000.0,iq,MAX_SAMPLES,700.0);
        if(ns<=0||ns>60000){printf("WAVEFORM_SIZE_INVALID=%d\n",ns);free(iq);return -1;}
        uint16_t start=0x0200;
        printf("ROUND=%d A7105_CH=0x%02x RF_MHZ=%.3f WIFI_CH=%d BB_OFFSET_HZ=%.0f IQ_SAMPLES=%d\n",
               round,a7105,rf_mhz,wifi,offset,ns);
        if(write_iq(s,ifname,start,(uint16_t)scale,iq,ns)<0){free(iq);return -1;}
        int us=(int)ceil(ns/(SAMPLE_RATE/1000000.0))+80;if(us>1500)us=1500;
        play_once(s,ifname,start,(uint16_t)ns,wifi,ctrl,us);
        usleep(3850);
    }
    free(iq);
    printf("BIND_TX_PHASE1_ONLY=1\nRX_ID_LEARNED=0\nFULL_BIND_CONFIRMED=0\n");
    return 0;
}

static void usage(const char *p) {
    fprintf(stderr,"usage: %s <ifname> caps|portal|pulse [ctrl] [scale]|bind <txid-hex> [profile] [ctrl] [scale] [rounds]\n",p);
}

int main(int argc,char **argv) {
    if(argc<3){usage(argv[0]);return 2;}
    const char *ifname=argv[1],*mode=argv[2];
    int s=socket(AF_INET,SOCK_DGRAM,0);if(s<0){perror("socket");return 20;}
    printf("MARX_LINK_USERSPACE=1\nMODE=%s\nSAMPLE_RATE=40000000\nBIT_RATE=500000\n",mode);
    char ver[512]={0};call_ioctl(s,ifname,CMD_VERSION,ver,sizeof(ver));printf("PR663_VERSION=%s\n",ver);
    int rc=0;
    if(!strcmp(mode,"caps")) rc=show_caps(s,ifname,"D11AC CAPS 0x643");
    else if(!strcmp(mode,"portal")) {rc=show_caps(s,ifname,"D11AC PORTAL MAP");printf("PORTAL_WRITE_TEST=DEFERRED_TO_IQ_WRITE_0x645\n");}
    else if(!strcmp(mode,"pulse")) {int ctrl=argc>3?atoi(argv[3]):1;int scale=argc>4?atoi(argv[4]):1;rc=do_pulse(s,ifname,ctrl,scale);}
    else if(!strcmp(mode,"bind")) {
        if(argc<4){usage(argv[0]);close(s);return 2;}
        uint32_t txid=(uint32_t)strtoul(argv[3],0,16);int profile=argc>4?atoi(argv[4]):0;
        int ctrl=argc>5?atoi(argv[5]):1,scale=argc>6?atoi(argv[6]):1,rounds=argc>7?atoi(argv[7]):6;
        if(profile<0||profile>2||ctrl<1||ctrl>3||scale<1||scale>4||rounds<1||rounds>20){usage(argv[0]);close(s);return 2;}
        rc=do_bind_candidate(s,ifname,txid,profile,ctrl,scale,rounds);
    } else {usage(argv[0]);rc=2;}
    close(s);return rc<0?21:0;
}
