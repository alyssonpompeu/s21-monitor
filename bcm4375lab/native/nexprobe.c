/*
 * BCM4375 Lab Broadcom/Nexmon private-ioctl transport triage.
 *
 * Uses the same private ioctl ABI as Nexmon libnexio. It tests:
 *   WLC_GET_MAGIC          = 0
 *   WLC_GET_VERSION        = 1
 *   NEX_GET_VERSION_STRING = 413
 *
 * GPL-3.0-or-later for compatibility with Nexmon-derived ABI usage.
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
#define WLC_GET_MAGIC 0u
#define WLC_GET_VERSION 1u
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

struct result {
    int ret;
    int err;
    unsigned int used;
    unsigned int needed;
};

static struct result run_ioctl(int s, const char *ifname, unsigned int cmd,
                               void *buf, unsigned int len) {
    struct ifreq ifr;
    struct nex_ioctl ioc;
    struct result r;

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
    r.ret = ioctl(s, SIOCDEVPRIVATE, &ifr);
    r.err = errno;
    r.used = ioc.used;
    r.needed = ioc.needed;
    return r;
}

static void print_result(const char *name, unsigned int cmd, struct result r) {
    printf("%s_CMD=%u ret=%d errno=%d %s used=%u needed=%u\n",
           name, cmd, r.ret, r.err, strerror(r.err), r.used, r.needed);
}

int main(int argc, char **argv) {
    const char *ifname = (argc > 1 && argv[1][0]) ? argv[1] : "wlan0";
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) {
        printf("TRIAGE_SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        return 20;
    }

    uint32_t magic = 0;
    uint32_t version = 0;
    char nexver[256];
    memset(nexver, 0, sizeof(nexver));

    struct result r0 = run_ioctl(s, ifname, WLC_GET_MAGIC, &magic, sizeof(magic));
    struct result r1 = run_ioctl(s, ifname, WLC_GET_VERSION, &version, sizeof(version));
    struct result r413 = run_ioctl(s, ifname, NEX_GET_VERSION_STRING, nexver, sizeof(nexver));
    close(s);

    print_result("WLC_GET_MAGIC", WLC_GET_MAGIC, r0);
    printf("WLC_MAGIC_VALUE=0x%08x\n", magic);
    print_result("WLC_GET_VERSION", WLC_GET_VERSION, r1);
    printf("WLC_VERSION_VALUE=%u\n", version);
    print_result("NEX_GET_VERSION_STRING", NEX_GET_VERSION_STRING, r413);

    nexver[sizeof(nexver) - 1] = '\0';
    if (r413.ret >= 0) printf("NEX_VERSION_STRING=%s\n", nexver);

    if (r0.ret < 0 && r1.ret < 0) {
        printf("TRIAGE_RESULT=PRIVATE_IOCTL_TRANSPORT_UNSUPPORTED\n");
        return 21;
    }

    if (r0.ret >= 0 || r1.ret >= 0) {
        printf("TRIAGE_BASE_IOCTL=SUPPORTED\n");
        if (r413.ret >= 0 &&
            (strstr(nexver, "nexmon") || strstr(nexver, "Nexmon") || strstr(nexver, "nexmon.org"))) {
            printf("NEXPROBE_RESULT=NEXMON_PRESENT\n");
            printf("TRIAGE_RESULT=NEXMON_PRESENT\n");
            return 0;
        }
        if (r413.ret < 0) {
            printf("NEXPROBE_RESULT=IOCTL_FAIL\n");
            printf("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED\n");
            return 22;
        }
        printf("NEXPROBE_RESULT=NO_NEXMON_STRING\n");
        printf("TRIAGE_RESULT=413_RESPONDED_WITHOUT_NEXMON_STRING\n");
        return 23;
    }

    printf("TRIAGE_RESULT=INDETERMINATE\n");
    return 24;
}
