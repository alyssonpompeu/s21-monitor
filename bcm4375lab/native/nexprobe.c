/*
 * BCM4375 Lab minimal Nexmon version probe.
 *
 * Uses the same Broadcom private ioctl ABI as Nexmon's libnexio:
 * https://github.com/seemoo-lab/nexmon
 * NEX_GET_VERSION_STRING = 413, WLC_IOCTL_MAGIC = 0x14e46c77.
 *
 * This source is distributed under GPL-3.0-or-later to remain compatible
 * with the Nexmon project from which the ioctl ABI usage was derived.
 */

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
#define NEX_GET_VERSION_STRING 413u

struct nex_ioctl {
    unsigned int cmd;
    void *buf;
    unsigned int len;
    bool set;
    unsigned int used;
    unsigned int needed;
    unsigned int driver;
};

int main(int argc, char **argv) {
    const char *ifname = (argc > 1 && argv[1][0]) ? argv[1] : "wlan0";
    char buf[256];
    struct ifreq ifr;
    struct nex_ioctl ioc;
    int s;
    int ret;
    int saved_errno;

    memset(buf, 0, sizeof(buf));
    memset(&ifr, 0, sizeof(ifr));
    memset(&ioc, 0, sizeof(ioc));

    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    ioc.cmd = NEX_GET_VERSION_STRING;
    ioc.buf = buf;
    ioc.len = sizeof(buf);
    ioc.set = false;
    ioc.driver = WLC_IOCTL_MAGIC;
    ifr.ifr_data = (void *) &ioc;

    s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) {
        printf("NEXPROBE_SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        return 10;
    }

    errno = 0;
    ret = ioctl(s, SIOCDEVPRIVATE, &ifr);
    saved_errno = errno;
    close(s);

    printf("NEXPROBE_IOCTL ret=%d errno=%d %s\n", ret, saved_errno, strerror(saved_errno));
    printf("NEXPROBE_USED=%u NEEDED=%u\n", ioc.used, ioc.needed);

    if (ret < 0) {
        printf("NEXPROBE_RESULT=IOCTL_FAIL\n");
        return 11;
    }

    buf[sizeof(buf) - 1] = '\0';
    printf("NEXPROBE_VERSION=%s\n", buf);
    if (strstr(buf, "nexmon") || strstr(buf, "Nexmon") || strstr(buf, "nexmon.org")) {
        printf("NEXPROBE_RESULT=NEXMON_PRESENT\n");
        return 0;
    }

    printf("NEXPROBE_RESULT=NO_NEXMON_STRING\n");
    return 12;
}
