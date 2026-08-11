package com.alysson.bcm4375lab;

final class ProbeCatalog {
    static final Probe[] ALL = new Probe[] {
            new Probe("ROOT", "id; id -Z 2>/dev/null || true"),
            new Probe("WLAN VERSIONS", "echo driver=$(getprop vendor.wlan.driver.version); echo firmware=$(getprop vendor.wlan.firmware.version); echo bt=\"$(getprop vendor.bluetooth_fw_ver)\""),
            new Probe("WIFIVER SYS", "cat /sys/wifi/wifiver 2>&1"),
            new Probe("WIFIVER DATA", "cat /data/vendor/conn/.wifiver.info 2>&1"),
            new Probe("FIRMWARE PATH", "ls -lZ /sys/module/dhd/parameters/firmware_path 2>&1; printf 'value=<'; cat /sys/module/dhd/parameters/firmware_path 2>/dev/null; echo '>'; test -w /sys/module/dhd/parameters/firmware_path && echo writable=YES || echo writable=NO"),
            new Probe("FIRMWARE FILES", "ls -lZ /vendor/firmware/bcmdhd_sta.bin_b1 /vendor/firmware/bcmdhd_mon.bin_b1 /vendor/firmware/bcmdhd_mfg.bin_b1 2>&1"),
            new Probe("FIRMWARE HASHES", "sha256sum /vendor/firmware/bcmdhd_sta.bin_b1 /vendor/firmware/bcmdhd_mon.bin_b1 /vendor/firmware/bcmdhd_mfg.bin_b1 2>&1"),
            new Probe("LOADERS", "ls -lZ /vendor/bin/hw/macloader /vendor/bin/hw/mfgloader 2>&1"),
            new Probe("INIT REFERENCES", "grep -RniE 'macloader|mfgloader|firmware_path|bcmdhd_(sta|mon|mfg)' /vendor/etc/init /vendor/etc /odm/etc/init /system/etc/init 2>/dev/null | head -180"),
            new Probe("WIFI PROPERTIES", "getprop | grep -iE 'mfg|wlan|wifi|bcmdhd|4375' | head -180"),
            new Probe("MACLOADER STRINGS", "/system/bin/strings /vendor/bin/hw/macloader 2>/dev/null | grep -iE 'firmware|firmware_path|bcmdhd|monitor|mfg|sta|fw_path|nvram|load.*driver|unload.*driver|usage|option' | head -240"),
            new Probe("MFGLOADER STRINGS", "/system/bin/strings /vendor/bin/hw/mfgloader 2>/dev/null | grep -iE 'firmware|firmware_path|bcmdhd|monitor|mfg|sta|fw_path|nvram|load.*driver|unload.*driver|usage|option' | head -240"),
            new Probe("DHD LOG", "dmesg | grep -iE 'bcmdhd|dhd|firmware|fw_path|4375|18\\.41\\.117|macloader|mfgloader' | tail -180")
    };

    static final class Probe {
        final String label;
        final String command;
        Probe(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    private ProbeCatalog() {}
}
