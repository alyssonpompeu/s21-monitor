#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_packet.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void print_hex(const unsigned char *p, int n) {
    int lim = n < 32 ? n : 32;
    for (int i = 0; i < lim; i++) printf("%02x", p[i]);
    printf("\n");
}

int main(int argc, char **argv) {
    const char *ifname = argc > 1 ? argv[1] : "wlan0";
    int seconds = argc > 2 ? atoi(argv[2]) : 6;
    if (seconds < 1) seconds = 1;
    if (seconds > 15) seconds = 15;

    unsigned int ifindex = if_nametoindex(ifname);
    if (!ifindex) {
        printf("SNIFF_RESULT=IF_NOT_FOUND errno=%d %s\n", errno, strerror(errno));
        return 2;
    }

    int s = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (s < 0) {
        printf("SNIFF_RESULT=SOCKET_FAIL errno=%d %s\n", errno, strerror(errno));
        return 3;
    }

    struct sockaddr_ll sa;
    memset(&sa, 0, sizeof(sa));
    sa.sll_family = AF_PACKET;
    sa.sll_protocol = htons(ETH_P_ALL);
    sa.sll_ifindex = (int)ifindex;
    if (bind(s, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        printf("SNIFF_RESULT=BIND_FAIL errno=%d %s\n", errno, strerror(errno));
        close(s);
        return 4;
    }

    long long end = now_ms() + (long long)seconds * 1000LL;
    unsigned long packets = 0, bytes = 0, radiotap = 0;
    int first_rt = 0;
    unsigned char buf[8192];

    while (now_ms() < end) {
        int remaining = (int)(end - now_ms());
        if (remaining > 500) remaining = 500;
        if (remaining < 1) remaining = 1;
        struct pollfd pfd = { .fd = s, .events = POLLIN };
        int pr = poll(&pfd, 1, remaining);
        if (pr < 0) {
            if (errno == EINTR) continue;
            printf("SNIFF_POLL_ERROR=%d %s\n", errno, strerror(errno));
            break;
        }
        if (pr == 0 || !(pfd.revents & POLLIN)) continue;
        ssize_t n = recv(s, buf, sizeof(buf), 0);
        if (n <= 0) continue;
        packets++;
        bytes += (unsigned long)n;
        if (n >= 8 && buf[0] == 0 && buf[1] == 0) {
            unsigned int rtlen = (unsigned int)buf[2] | ((unsigned int)buf[3] << 8);
            if (rtlen >= 8 && rtlen <= (unsigned int)n) {
                radiotap++;
                if (!first_rt) {
                    printf("FIRST_RADIOTAP_LEN=%u\nFIRST_RADIOTAP_HEX=", rtlen);
                    print_hex(buf, (int)n);
                    first_rt = 1;
                }
            }
        }
    }

    close(s);
    printf("SNIFF_IF=%s\nSNIFF_SECONDS=%d\nSNIFF_PACKETS=%lu\nSNIFF_BYTES=%lu\nSNIFF_RADIOTAP_LIKE=%lu\n",
           ifname, seconds, packets, bytes, radiotap);
    if (radiotap > 0) {
        printf("SNIFF_RESULT=RADIOTAP_RX_PRESENT\n");
        return 0;
    }
    if (packets > 0) {
        printf("SNIFF_RESULT=PACKETS_WITHOUT_RADIOTAP\n");
        return 5;
    }
    printf("SNIFF_RESULT=NO_PACKETS\n");
    return 6;
}
