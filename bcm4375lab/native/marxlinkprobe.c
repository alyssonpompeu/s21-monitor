/* MARX LINK COREREV82 userspace engine.
 * One private command (0x630) transports structured requests to a tiny
 * firmware helper. The helper uses the D11AC SamplePlay/XmtTemplate portal
 * identified from Nexmon's bcm4375 structs; no legacy tplatewrptr path.
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
#define CMD_TRANSPORT 0x630u
#define OP_CAPS 1u
#define OP_WRITE 2u
#define OP_PLAY 3u
#define SAMPLE_RATE 40000000.0
#define SPS 80
#define MAX_SAMPLES 62000
#define CHUNK_WORDS 900

struct nex_ioctl { unsigned int cmd; void *buf; unsigned int len; bool set; unsigned int used; unsigned int needed; unsigned int driver; };

struct req {
    uint32_t magic;
    uint16_t op, status;
    uint16_t start, count, ptr_scale, wifi_channel, control_mode, duration_us;
    uint16_t corerev, chanspec;
    uint16_t collect_start, collect_stop, collect_cur;
    uint16_t play_start, play_stop, xmt_lo, xmt_hi, xmt_ptr, play_ctrl;
    uint16_t old_start, old_stop, old_ctrl, cur_before, cur_after, new_ctrl;
    uint16_t readback_ok, reserved;
    uint32_t words[];
};

static int call_transport(int s,const char *ifname,void *buf,unsigned len) {
    struct ifreq ifr; struct nex_ioctl ioc;
    memset(&ifr,0,sizeof(ifr)); memset(&ioc,0,sizeof(ioc));
    strncpy(ifr.ifr_name,ifname,IFNAMSIZ-1);
    ioc.cmd=CMD_TRANSPORT; ioc.buf=buf; ioc.len=len; ioc.set=false; ioc.driver=WLC_IOCTL_MAGIC;
    ifr.ifr_data=(void*)&ioc; errno=0;
    int ret=ioctl(s,SIOCDEVPRIVATE,&ifr);
    printf("CMD=0x630 ret=%d errno=%d %s used=%u needed=%u\n",ret,errno,strerror(errno),ioc.used,ioc.needed);
    return ret;
}

static void print_caps(const struct req *q) {
    printf("MARX_LINK_TRANSPORT=0x630\nFIRMWARE_HELPER_STATUS=0x%04x\n",q->status);
    printf("COREREV=%u\nCHANSPEC=0x%04x\n",q->corerev,q->chanspec);
    printf("D11AC_SAMPLE_COLLECT_START=0x%04x\nD11AC_SAMPLE_COLLECT_STOP=0x%04x\nD11AC_SAMPLE_COLLECT_CUR=0x%04x\n",q->collect_start,q->collect_stop,q->collect_cur);
    printf("D11AC_SAMPLE_PLAY_START=0x%04x\nD11AC_SAMPLE_PLAY_STOP=0x%04x\n",q->play_start,q->play_stop);
    printf("D11AC_XMT_DATA_LO=0x%04x\nD11AC_XMT_DATA_HI=0x%04x\nD11AC_XMT_PTR=0x%04x\n",q->xmt_lo,q->xmt_hi,q->xmt_ptr);
    printf("D11AC_SAMPLE_PLAY_CTRL=0x%04x\n",q->play_ctrl);
    printf("PORTAL_OFFSETS=55A,55C,560,562,564,B2E\nLEGACY_TPLATE_0130_0134=DISABLED\n");
}

static int caps(int s,const char *ifname) {
    struct req q; memset(&q,0,sizeof(q)); q.magic=MARX_MAGIC; q.op=OP_CAPS;
    int r=call_transport(s,ifname,&q,sizeof(q)); print_caps(&q);
    int ok=(r>=0 && q.status==0x1001 && q.corerev==82);
    printf("COREREV82_PATH=%s\n",ok?"READY":"NOT_READY");
    return ok?0:-1;
}

static uint32_t pack_iq(double iv,double qv,double amp) {
    long i=lround(iv*amp),q=lround(qv*amp);
    if(i>32767)i=32767;if(i<-32768)i=-32768;if(q>32767)q=32767;if(q<-32768)q=-32768;
    return ((uint32_t)(uint16_t)(int16_t)i<<16)|(uint16_t)(int16_t)q;
}

static int write_iq(int s,const char *ifname,uint16_t start,uint16_t scale,const uint32_t *iq,int count) {
    int pos=0; int all_rb=1;
    while(pos<count) {
        int c=count-pos; if(c>CHUNK_WORDS)c=CHUNK_WORDS;
        size_t bytes=sizeof(struct req)+(size_t)c*4;
        struct req *q=calloc(1,bytes); if(!q)return -1;
        q->magic=MARX_MAGIC;q->op=OP_WRITE;q->start=(uint16_t)(start+pos*scale);q->count=(uint16_t)c;q->ptr_scale=scale;
        memcpy(q->words,iq+pos,(size_t)c*4);
        int r=call_transport(s,ifname,q,(unsigned)bytes);
        int ok=(r>=0 && (q->status==0x2001||q->status==0x2002));
        printf("IQ_CHUNK start=0x%04x count=%d status=0x%04x readback=%u\n",q->start,c,q->status,q->readback_ok);
        if(!q->readback_ok)all_rb=0; free(q); if(!ok)return -1; pos+=c;
    }
    printf("IQ_WRITE_TRANSPORT=OK\nIQ_READBACK_ALL=%d\n",all_rb);
    return 0;
}

static int play_once(int s,const char *ifname,uint16_t start,uint16_t count,int ctrl,int us) {
    struct req q; memset(&q,0,sizeof(q));q.magic=MARX_MAGIC;q.op=OP_PLAY;q.start=start;q.count=count;q.control_mode=(uint16_t)ctrl;q.duration_us=(uint16_t)us;
    int r=call_transport(s,ifname,&q,sizeof(q));
    printf("PLAY_STATUS=0x%04x\nCTRL_BEFORE=0x%04x\nCTRL_DURING=0x%04x\nCUR_BEFORE=0x%04x\nCUR_AFTER=0x%04x\n",q.status,q.old_ctrl,q.new_ctrl,q.cur_before,q.cur_after);
    printf("PLAY_WINDOW_US=%u\nPLAY_REGS_RESTORED=%d\n",q.duration_us,q.play_ctrl==q.old_ctrl);
    printf("PLAY_ACTIVITY_HINT=%d\n",q.status==0x3003);
    return (r>=0 && (q.status==0x3001||q.status==0x3003))?0:-1;
}

static int pulse(int s,const char *ifname,int ctrl,int scale) {
    const int ns=1024; uint32_t *iq=malloc((size_t)ns*4); if(!iq)return -1; double ph=0;
    for(int i=0;i<ns;i++){ph+=2*M_PI*250000.0/SAMPLE_RATE;iq[i]=pack_iq(cos(ph),sin(ph),500.0);}
    printf("MARX_PULSE samples=%d amp=500 ctrl=%d scale=%d\n",ns,ctrl,scale);
    int r=write_iq(s,ifname,0x0800,(uint16_t)scale,iq,ns); free(iq); if(r<0)return r;
    return play_once(s,ifname,0x0800,(uint16_t)ns,ctrl,100);
}

struct bits { uint8_t *v; int n,cap; };
static void bp(struct bits*b,int v){if(b->n<b->cap)b->v[b->n++]=(uint8_t)(v?1:0);} static void bm(struct bits*b,uint8_t x){for(int i=7;i>=0;i--)bp(b,(x>>i)&1);}
static uint8_t h74(uint8_t n){uint8_t d0=n&1,d1=(n>>1)&1,d2=(n>>2)&1,d3=(n>>3)&1;return(uint8_t)(((d3^d2^d0)<<6)|((d3^d1^d0)<<5)|(d3<<4)|((d2^d1^d0)<<3)|(d2<<2)|(d1<<1)|d0);}
static void hm(struct bits*b,uint8_t n){uint8_t h=h74(n);for(int i=6;i>=0;i--)bp(b,(h>>i)&1);}
static uint16_t crc16(const uint8_t*p,size_t n){uint16_t c=0xffff;for(size_t i=0;i<n;i++){c^=(uint16_t)p[i]<<8;for(int b=0;b<8;b++)c=(c&0x8000)?(uint16_t)((c<<1)^0x1021):(uint16_t)(c<<1);}return c;}
static void make_bind1(uint8_t p[38],uint32_t id,const uint8_t h[16]){memset(p,0xff,38);p[0]=0xbb;p[1]=id;p[2]=id>>8;p[3]=id>>16;p[4]=id>>24;p[9]=1;p[10]=0;for(int i=0;i<16;i++)p[11+i]=h[i];p[37]=0;}
static void hops(uint32_t id,uint8_t h[16]){uint32_t r=id;int n=0;uint8_t t=(uint8_t)(id>>24);while(n<16){uint8_t band=(uint8_t)((((n<<1)|((n>>1)&1))+t)&3);r=r*0x0019660du+0x3c6ef35fu;uint8_t x=(uint8_t)(band*41+1+((r>>n)%41));int ok=1;for(int j=0;j<n;j++){int d=(int)x-h[j];if(d<0)d=-d;if(d<5){ok=0;break;}}if(ok)h[n++]=x;}}
static int air(const uint8_t p[38],int profile,struct bits*b){static const uint8_t sync[4]={0x54,0x75,0xc5,0x2a};uint8_t body[40];memcpy(body,p,38);uint16_t c=crc16(p,38);body[38]=c>>8;body[39]=c;for(int i=0;i<4;i++)bm(b,0xaa);for(int i=0;i<4;i++)bm(b,sync[i]);for(int i=0;i<40;i++){if(profile==1)bm(b,body[i]);else if(profile==0){hm(b,body[i]>>4);hm(b,body[i]&15);}else{hm(b,body[i]&15);hm(b,body[i]>>4);}}return b->n;}
static int gfsk(const struct bits*b,double center,double dev,uint32_t*out,int max,double amp){double phase=0,f=b->n?(b->v[0]?1:-1):0;int n=0;for(int bi=0;bi<b->n;bi++){double target=b->v[bi]?1:-1;for(int k=0;k<SPS;k++){f+=0.11*(target-f);phase+=2*M_PI*(center+dev*f)/SAMPLE_RATE;if(phase>M_PI)phase-=2*M_PI;if(phase<-M_PI)phase+=2*M_PI;if(n<max)out[n++]=pack_iq(cos(phase),sin(phase),700.0);}}return n;}

static int bind_candidate(int s,const char *ifname,uint32_t txid,int profile,int ctrl,int scale,int rounds){uint8_t h[16],p[38];hops(txid,h);make_bind1(p,txid,h);printf("AFHDS2A_BIND1_CANDIDATE=1\nTXID=%08x\n",txid);printf("HOPS=");for(int i=0;i<16;i++)printf("%u%s",h[i],i==15?"\n":",");printf("PACKET=");for(int i=0;i<38;i++)printf("%02x%s",p[i],i==37?"\n":" ");uint8_t bb[2048];struct bits bits={bb,0,(int)sizeof(bb)};air(p,profile,&bits);uint32_t *iq=malloc((size_t)MAX_SAMPLES*4);if(!iq)return -1;int rc=0;for(int round=0;round<rounds;round++){int ach=(round&1)?0x0d:0x8c;int wch=(ach==0x0d)?1:13;double rf=2400.0+0.5*ach,wf=(wch==1)?2412.0:2472.0,off=(rf-wf)*1e6;int ns=gfsk(&bits,off,250000.0,iq,MAX_SAMPLES,700.0);uint16_t st=0x0200;printf("ROUND=%d A7105_CH=0x%02x RF_MHZ=%.3f WIFI_CH=%d OFFSET_HZ=%.0f SAMPLES=%d PROFILE=%d\n",round,ach,rf,wch,off,ns,profile);if(ns<=0||ns>60000||write_iq(s,ifname,st,(uint16_t)scale,iq,ns)<0||play_once(s,ifname,st,(uint16_t)ns,ctrl,180)<0){rc=-1;break;}usleep(3850);}free(iq);printf("BIND1_SEQUENCE_SENT_CANDIDATE=%d\n",rc==0);printf("RX_ID_LEARNING=NOT_IMPLEMENTED_YET\nFULL_BIND_CLAIMED=0\n");return rc;}

static void usage(const char*p){fprintf(stderr,"usage: %s <ifname> caps|portal|pulse [ctrl] [scale]|bind <txidhex> [profile] [ctrl] [scale] [rounds]\n",p);}
int main(int argc,char**argv){if(argc<3){usage(argv[0]);return 2;}const char*ifn=argv[1],*m=argv[2];int s=socket(AF_INET,SOCK_DGRAM,0);if(s<0){perror("socket");return 20;}printf("MARX_LINK_USERSPACE=2\nMODE=%s\nSAMPLE_RATE=40000000\n",m);int rc=0;if(!strcmp(m,"caps")||!strcmp(m,"portal"))rc=caps(s,ifn);else if(!strcmp(m,"pulse")){int c=argc>3?atoi(argv[3]):1,sc=argc>4?atoi(argv[4]):1;rc=pulse(s,ifn,c,sc);}else if(!strcmp(m,"bind")){if(argc<4){usage(argv[0]);close(s);return 2;}uint32_t id=(uint32_t)strtoul(argv[3],0,16);int pr=argc>4?atoi(argv[4]):0,c=argc>5?atoi(argv[5]):1,sc=argc>6?atoi(argv[6]):1,rd=argc>7?atoi(argv[7]):6;if(pr<0||pr>2||c<1||c>4||sc<1||sc>4||rd<1||rd>20){usage(argv[0]);close(s);return 2;}rc=bind_candidate(s,ifn,id,pr,c,sc,rd);}else{usage(argv[0]);rc=-1;}close(s);return rc<0?21:0;}
