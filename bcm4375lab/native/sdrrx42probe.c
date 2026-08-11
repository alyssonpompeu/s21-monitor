/* Fixed, read-only userspace probe for the RX42 BCM4375B1 SDR experiment. */
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <net/if.h>
#include <unistd.h>

#ifndef SIOCDEVPRIVATE
#define SIOCDEVPRIVATE 0x89F0
#endif
#define WLC_IOCTL_MAGIC 0x14e46c77u
#define PR663_GET_VERSION 0x600u
#define RX42_SDR_PROBE 0x630u

struct nex_ioctl {
    unsigned int cmd;
    void *buf;
    unsigned int len;
    bool set;
    unsigned int used;
    unsigned int needed;
    unsigned int driver;
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

int main(int argc, char **argv) {
    const char *ifname = (argc > 1 && argv[1][0]) ? argv[1] : "wlan0";
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) {
        printf("RX42_SDR_USER_PROBE=SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        return 20;
    }

    char ver[256];
    char out[2048];
    memset(ver, 0, sizeof(ver));
    memset(out, 0, sizeof(out));

    int rv = call_ioctl(s, ifname, PR663_GET_VERSION, ver, sizeof(ver));
    ver[sizeof(ver)-1] = 0;
    if (rv >= 0) printf("PR663_VERSION=%s\n", ver);

    int rp = call_ioctl(s, ifname, RX42_SDR_PROBE, out, sizeof(out));
    out[sizeof(out)-1] = 0;
    if (rp >= 0) printf("%s", out);
    close(s);

    if (rv >= 0 && strstr(ver, "nexmon.org") && rp >= 0 &&
        strstr(out, "RX42_SDR_PROBE=1") && strstr(out, "TX_ENABLED_BY_THIS_PROBE=0")) {
        printf("RX42_SDR_USER_PROBE=SUPPORTED_READ_ONLY\n");
        return 0;
    }
    printf("RX42_SDR_USER_PROBE=NOT_CONFIRMED\n");
    return 21;
}
