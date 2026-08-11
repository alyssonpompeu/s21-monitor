/* Userspace probe for the RX42 BCM4375B1 SDR experiment.
 * Modes:
 *   version -> PR663 0x600 only
 *   regs    -> 0x600 + read-only register probe 0x630
 *   tplram  -> 0x600 + bounded template-RAM write/read/restore 0x631
 *   all     -> 0x600 + 0x630 + 0x631
 * No mode starts RF sample playback.
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
#define PR663_GET_VERSION 0x600u
#define RX42_SDR_PROBE 0x630u
#define RX42_TPLRAM_PROBE 0x631u

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

static bool mode_is(const char *mode, const char *name) {
    return strcmp(mode, name) == 0;
}

int main(int argc, char **argv) {
    const char *ifname = (argc > 1 && argv[1][0]) ? argv[1] : "wlan0";
    const char *mode = (argc > 2 && argv[2][0]) ? argv[2] : "all";
    bool do_regs = mode_is(mode, "regs") || mode_is(mode, "all");
    bool do_tplram = mode_is(mode, "tplram") || mode_is(mode, "all");
    bool valid_mode = mode_is(mode, "version") || do_regs || do_tplram;

    printf("RX42_SCENARIO=%s\n", mode);
    printf("TX_TRIGGERED=0\n");
    if (!valid_mode) {
        printf("RX42_SCENARIO_RESULT=INVALID_MODE\n");
        return 22;
    }

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) {
        printf("RX42_SDR_USER_PROBE=SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        printf("RX42_SCENARIO_RESULT=NOT_CONFIRMED\n");
        return 20;
    }

    char ver[256];
    char ro[2048];
    char rw[4096];
    memset(ver, 0, sizeof(ver));
    memset(ro, 0, sizeof(ro));
    memset(rw, 0, sizeof(rw));

    int rv = call_ioctl(s, ifname, PR663_GET_VERSION, ver, sizeof(ver));
    ver[sizeof(ver)-1] = 0;
    if (rv >= 0) printf("PR663_VERSION=%s\n", ver);

    int rp = -1;
    int rt = -1;
    if (do_regs) {
        rp = call_ioctl(s, ifname, RX42_SDR_PROBE, ro, sizeof(ro));
        ro[sizeof(ro)-1] = 0;
        if (rp >= 0) printf("%s", ro);
    }

    if (do_tplram) {
        rt = call_ioctl(s, ifname, RX42_TPLRAM_PROBE, rw, sizeof(rw));
        rw[sizeof(rw)-1] = 0;
        if (rt >= 0) printf("%s", rw);
    }
    close(s);

    bool version_ok = rv >= 0 && strstr(ver, "nexmon.org");
    bool reg_ok = !do_regs || (rp >= 0 && strstr(ro, "RX42_SDR_PROBE=1") && strstr(ro, "TX_ENABLED_BY_THIS_PROBE=0"));
    bool tpl_ok = !do_tplram || (rt >= 0 && strstr(rw, "TPLRAM_RESULT=PASS") &&
                  strstr(rw, "WRITE_READBACK_OK=1") && strstr(rw, "RESTORE_OK=1") &&
                  strstr(rw, "PLAYBACK_STAYED_OFF=1") && strstr(rw, "TX_TRIGGERED=0"));

    bool ok = version_ok && reg_ok && tpl_ok;
    if (do_tplram) {
        if (ok) printf("RX42_TPLRAM_USER_PROBE=PASS_NO_TX\n");
        else printf("RX42_TPLRAM_USER_PROBE=NOT_CONFIRMED\n");
    }
    printf("RX42_SCENARIO_RESULT=%s\n", ok ? "PASS" : "NOT_CONFIRMED");
    return ok ? 0 : 21;
}
