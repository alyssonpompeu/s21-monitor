/*
 * BCM4375 Remote Lab Broadcom/Nexmon private-ioctl probe.
 *
 * Read-only probe covers the stock Broadcom transport plus both the generic
 * Nexmon ABI and the BCM4375B1 18.41.117 PR #663 custom version ioctl 0x600.
 * Optional monitor_on/monitor_off modes are fixed, allow-listed controls for
 * later Remote Lab recipes; no arbitrary ioctl number is accepted from input.
 *
 * GPL-3.0-or-later for compatibility with Nexmon-derived ABI usage.
 */

#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
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
#define WLC_GET_MONITOR 107u
#define WLC_SET_MONITOR 108u
#define NEX_GET_CAPABILITIES 400u
#define NEX_GET_VERSION_STRING 413u
#define PR663_GET_VERSION 0x600u

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
                               void *buf, unsigned int len, bool set) {
    struct ifreq ifr;
    struct nex_ioctl ioc;
    struct result r;

    memset(&ifr, 0, sizeof(ifr));
    memset(&ioc, 0, sizeof(ioc));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);

    ioc.cmd = cmd;
    ioc.buf = buf;
    ioc.len = len;
    ioc.set = set;
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

static int do_monitor_set(int s, const char *ifname, uint32_t value) {
    struct result r = run_ioctl(s, ifname, WLC_SET_MONITOR, &value, sizeof(value), true);
    print_result(value ? "WLC_SET_MONITOR_ON" : "WLC_SET_MONITOR_OFF", WLC_SET_MONITOR, r);
    printf("WLC_SET_MONITOR_VALUE=%u\n", value);
    if (r.ret < 0) {
        printf("CONTROL_RESULT=MONITOR_SET_FAILED\n");
        return 31;
    }
    printf("CONTROL_RESULT=MONITOR_SET_OK\n");
    return 0;
}

int main(int argc, char **argv) {
    const char *ifname = (argc > 1 && argv[1][0]) ? argv[1] : "wlan0";
    const char *mode = (argc > 2 && argv[2][0]) ? argv[2] : "probe";

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) {
        printf("TRIAGE_SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        return 20;
    }

    if (!strcmp(mode, "monitor_on")) {
        int rc = do_monitor_set(s, ifname, 2u);
        close(s);
        return rc;
    }
    if (!strcmp(mode, "monitor_off")) {
        int rc = do_monitor_set(s, ifname, 0u);
        close(s);
        return rc;
    }
    if (strcmp(mode, "probe") != 0) {
        printf("CONTROL_RESULT=UNSUPPORTED_MODE\n");
        close(s);
        return 32;
    }

    uint32_t magic = 0;
    uint32_t version = 0;
    uint32_t monitor = 0;
    uint32_t capabilities = 0;
    char nexver[256];
    char prver[256];
    memset(nexver, 0, sizeof(nexver));
    memset(prver, 0, sizeof(prver));

    struct result r0 = run_ioctl(s, ifname, WLC_GET_MAGIC, &magic, sizeof(magic), false);
    struct result r1 = run_ioctl(s, ifname, WLC_GET_VERSION, &version, sizeof(version), false);
    struct result r107 = run_ioctl(s, ifname, WLC_GET_MONITOR, &monitor, sizeof(monitor), false);
    struct result r400 = run_ioctl(s, ifname, NEX_GET_CAPABILITIES, &capabilities, sizeof(capabilities), false);
    struct result r413 = run_ioctl(s, ifname, NEX_GET_VERSION_STRING, nexver, sizeof(nexver), false);
    struct result r600 = run_ioctl(s, ifname, PR663_GET_VERSION, prver, sizeof(prver), false);
    close(s);

    print_result("WLC_GET_MAGIC", WLC_GET_MAGIC, r0);
    printf("WLC_MAGIC_VALUE=0x%08x\n", magic);
    print_result("WLC_GET_VERSION", WLC_GET_VERSION, r1);
    printf("WLC_VERSION_VALUE=%u\n", version);
    print_result("WLC_GET_MONITOR", WLC_GET_MONITOR, r107);
    printf("WLC_MONITOR_VALUE=%u\n", monitor);
    print_result("NEX_GET_CAPABILITIES", NEX_GET_CAPABILITIES, r400);
    printf("NEX_CAPABILITIES_VALUE=0x%08x\n", capabilities);
    print_result("NEX_GET_VERSION_STRING", NEX_GET_VERSION_STRING, r413);
    print_result("PR663_GET_VERSION", PR663_GET_VERSION, r600);

    nexver[sizeof(nexver) - 1] = '\0';
    prver[sizeof(prver) - 1] = '\0';
    if (r413.ret >= 0) printf("NEX_VERSION_STRING=%s\n", nexver);
    if (r600.ret >= 0) printf("PR663_VERSION_STRING=%s\n", prver);

    if (r0.ret < 0 && r1.ret < 0) {
        printf("TRIAGE_RESULT=PRIVATE_IOCTL_TRANSPORT_UNSUPPORTED\n");
        return 21;
    }

    printf("TRIAGE_BASE_IOCTL=SUPPORTED\n");

    bool generic_present = r413.ret >= 0 &&
        (strstr(nexver, "nexmon") || strstr(nexver, "Nexmon") || strstr(nexver, "nexmon.org"));
    bool pr663_present = r600.ret >= 0 &&
        (strstr(prver, "nexmon") || strstr(prver, "Nexmon") || strstr(prver, "nexmon.org"));

    if (generic_present || pr663_present) {
        printf("NEXPROBE_GENERIC_413=%s\n", generic_present ? "true" : "false");
        printf("NEXPROBE_PR663_600=%s\n", pr663_present ? "true" : "false");
        printf("NEXPROBE_RESULT=NEXMON_PRESENT\n");
        printf("TRIAGE_RESULT=NEXMON_PRESENT\n");
        return 0;
    }

    printf("NEXPROBE_GENERIC_413=false\n");
    printf("NEXPROBE_PR663_600=false\n");
    if (r413.ret < 0 && r600.ret < 0) {
        printf("NEXPROBE_RESULT=VERSION_IOCTLS_UNSUPPORTED\n");
        printf("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_VERSION_UNSUPPORTED\n");
        return 22;
    }

    printf("NEXPROBE_RESULT=NO_NEXMON_STRING\n");
    printf("TRIAGE_RESULT=VERSION_RESPONDED_WITHOUT_NEXMON_STRING\n");
    return 23;
}
