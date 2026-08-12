/* MARX A7105 V1.0 userspace probe.
 * Bounded diagnostics only. No generic arbitrary-ioctl interface is exposed.
 */
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <net/if.h>
#include <unistd.h>
#include <math.h>

#ifndef SIOCDEVPRIVATE
#define SIOCDEVPRIVATE 0x89F0
#endif
#define WLC_IOCTL_MAGIC 0x14e46c77u
#define NEX_GET_VERSION_STRING 413u
#define PR663_GET_VERSION 0x600u
#define WLC_PHY_SAMPLE_COLLECT 307u
#define MARX_BACKEND_CAPS 0x63Fu
#define SAMPLE_RATE 40000000.0
#define BIT_RATE 500000.0
#define SPS 80

struct nex_ioctl { unsigned int cmd; void *buf; unsigned int len; bool set; unsigned int used; unsigned int needed; unsigned int driver; };
struct result { int ret, err; unsigned int used, needed; };

static struct result run_ioctl(int s,const char *ifn,unsigned cmd,void *buf,unsigned len,bool set){
    struct ifreq ifr; struct nex_ioctl ioc; struct result r;
    memset(&ifr,0,sizeof(ifr)); memset(&ioc,0,sizeof(ioc)); strncpy(ifr.ifr_name,ifn,IFNAMSIZ-1);
    ioc.cmd=cmd;ioc.buf=buf;ioc.len=len;ioc.set=set;ioc.driver=WLC_IOCTL_MAGIC;ifr.ifr_data=(void*)&ioc;
    errno=0;r.ret=ioctl(s,SIOCDEVPRIVATE,&ifr);r.err=errno;r.used=ioc.used;r.needed=ioc.needed;return r;
}
static void pr(const char*n,unsigned cmd,struct result r){printf("%s_CMD=%u ret=%d errno=%d %s used=%u needed=%u\n",n,cmd,r.ret,r.err,strerror(r.err),r.used,r.needed);}

static int do_nexmon(int s,const char*ifn){
    char a[256]={0},b[256]={0}; struct result r413=run_ioctl(s,ifn,NEX_GET_VERSION_STRING,a,sizeof(a),false); struct result r600=run_ioctl(s,ifn,PR663_GET_VERSION,b,sizeof(b),false);
    pr("NEXMON_413",413,r413); if(r413.ret>=0)printf("NEXMON_VERSION=%s\n",a);
    pr("PR663_600",0x600,r600); if(r600.ret>=0)printf("PR663_VERSION=%s\n",b);
    int ok=(r600.ret>=0 && (strstr(b,"nexmon")||strstr(b,"Nexmon"))) || (r413.ret>=0 && (strstr(a,"nexmon")||strstr(a,"Nexmon")));
    printf("NEXMON_TRANSPORT=%s\n",ok?"PASS":"NOT_CONFIRMED"); return ok?0:21;
}

/* Conservative compatibility shape derived from Broadcom's public wlioctl.h.
 * The command is receive/capture oriented. If this exact firmware rejects the
 * structure, that rejection is useful evidence and no TX is initiated. */
struct sample_args_min {
    uint8_t coll_us;
    uint8_t pad0[3];
    int32_t cores;
    uint16_t version;
    uint16_t length;
    int32_t trigger;
    uint32_t timeout;
    uint16_t mode;
    uint16_t gpio_sel;
    uint32_t downsamp;
    uint8_t be_deaf;
    uint8_t agc;
    uint8_t filter;
    uint8_t pad1;
    uint16_t nsamps;
    uint16_t pad2;
};

static int do_sample307(int s,const char*ifn){
    uint8_t buf[4096]; memset(buf,0,sizeof(buf)); struct sample_args_min *a=(struct sample_args_min*)buf;
    a->coll_us=10; a->cores=1; a->version=2; a->length=(uint16_t)sizeof(*a); a->timeout=20; a->nsamps=64;
    printf("WLC_PHY_SAMPLE_COLLECT=307\nREQUEST_VERSION=2\nREQUEST_NSAMPS=64\nTX_REQUESTED=0\n");
    struct result r=run_ioctl(s,ifn,WLC_PHY_SAMPLE_COLLECT,buf,sizeof(buf),false); pr("SAMPLE307",WLC_PHY_SAMPLE_COLLECT,r);
    unsigned n=r.used?r.used:64; if(n>256)n=256;
    printf("RX_CAPTURE_RETURN_PREFIX="); for(unsigned i=0;i<n;i++)printf("%02x%s",buf[i],i+1==n?"\n":" ");
    printf("RX_SAMPLE_PATH=%s\n",r.ret>=0?"RESPONDED":"NOT_CONFIRMED");
    printf("RF_TX_TRIGGERED=0\n"); return r.ret>=0?0:22;
}

static void make_hops(uint32_t id,uint8_t h[16]){uint32_t r=id;int n=0;uint8_t t=(uint8_t)(id>>24);while(n<16){uint8_t band=(uint8_t)((((n<<1)|((n>>1)&1))+t)&3);r=r*0x0019660du+0x3c6ef35fu;uint8_t x=(uint8_t)(band*41+1+((r>>n)%41));int ok=1;for(int j=0;j<n;j++){int d=(int)x-h[j];if(d<0)d=-d;if(d<5){ok=0;break;}}if(ok)h[n++]=x;}}
static void bind1(uint8_t p[38],uint32_t id,const uint8_t h[16]){memset(p,0xff,38);p[0]=0xbb;p[1]=id;p[2]=id>>8;p[3]=id>>16;p[4]=id>>24;p[9]=1;p[10]=0;for(int i=0;i<16;i++)p[11+i]=h[i];p[37]=0;}
static uint64_t fnv64(const void *vp,size_t n){const uint8_t*p=vp;uint64_t h=1469598103934665603ULL;for(size_t i=0;i<n;i++){h^=p[i];h*=1099511628211ULL;}return h;}
static int do_gfskdry(void){
    const uint32_t txid=0x86A39073u; uint8_t h[16],p[38]; make_hops(txid,h); bind1(p,txid,h);
    int bits=38*8; int ns=bits*SPS; int16_t *iq=calloc((size_t)ns*2,sizeof(int16_t)); if(!iq)return 30;
    double phase=0, f=1.0; int o=0;
    for(int bi=0;bi<bits;bi++){int byte=bi>>3,bit=7-(bi&7);double target=((p[byte]>>bit)&1)?1.0:-1.0;for(int k=0;k<SPS;k++){f+=0.10*(target-f);phase+=2.0*M_PI*(250000.0*f)/SAMPLE_RATE;if(phase>M_PI)phase-=2*M_PI;if(phase<-M_PI)phase+=2*M_PI;iq[o++]=(int16_t)lrint(cos(phase)*700.0);iq[o++]=(int16_t)lrint(sin(phase)*700.0);}}
    printf("VIRTUAL_A7105=READY\nAFHDS2A_BIND1_BYTES=38\nBIT_RATE=500000\nSAMPLE_RATE=40000000\nSPS=80\nDEVIATION_HZ=250000\nIQ_SAMPLES=%d\n",ns);
    printf("HOPS=");for(int i=0;i<16;i++)printf("%u%s",h[i],i==15?"\n":",");
    printf("BIND1=");for(int i=0;i<38;i++)printf("%02x%s",p[i],i==37?"\n":" ");
    printf("IQ_FNV64=%016llx\n",(unsigned long long)fnv64(iq,(size_t)ns*2*sizeof(int16_t)));
    printf("GFSK_IQ_DRYRUN=PASS\nRF_TX_TRIGGERED=0\n"); free(iq); return 0;
}

static int do_backend(int s,const char*ifn){
    struct stat st; int dev=(stat("/dev/marxrf",&st)==0);
    char cap[256]={0}; struct result r=run_ioctl(s,ifn,MARX_BACKEND_CAPS,cap,sizeof(cap),false);
    printf("MARXRF_DEVICE=%s\n",dev?"PRESENT":"ABSENT"); pr("MARX_BACKEND_CAPS",MARX_BACKEND_CAPS,r);
    if(r.ret>=0)printf("MARX_BACKEND_REPLY=%s\n",cap);
    int ready=dev || (r.ret>=0 && strstr(cap,"MARX_ARBITRARY_TX_V1"));
    printf("ARBITRARY_TX_BACKEND=%s\n",ready?"READY":"NOT_READY");
    printf("RX_PATH=%s\n",(r.ret>=0 && strstr(cap,"RX_IQ"))?"IQ":"NONE");
    printf("BACKEND_POLICY=NO_GENERIC_IOCTL_NO_CONTINUOUS_TX\n"); return ready?0:23;
}

int main(int argc,char**argv){
    const char*ifn=(argc>1&&argv[1][0])?argv[1]:"wlan0";const char*m=(argc>2&&argv[2][0])?argv[2]:"backend";
    printf("MARX_A7105_PROBE=1\nMODE=%s\n",m);
    if(!strcmp(m,"gfskdry"))return do_gfskdry();
    int s=socket(AF_INET,SOCK_DGRAM,0);if(s<0){printf("SOCKET_FAIL=%d %s\n",errno,strerror(errno));return 20;}
    int rc;if(!strcmp(m,"nexmon"))rc=do_nexmon(s,ifn);else if(!strcmp(m,"sample307"))rc=do_sample307(s,ifn);else if(!strcmp(m,"backend"))rc=do_backend(s,ifn);else{printf("UNSUPPORTED_MODE=1\n");rc=2;}close(s);return rc;
}
