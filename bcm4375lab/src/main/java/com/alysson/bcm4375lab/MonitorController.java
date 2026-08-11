package com.alysson.bcm4375lab;

final class MonitorController {
    static final String STA_SHA = "1676f46ce56b96f58dc70de08beaab4ab3362ee6dd751465a8d6a0023c3c54ad";
    static final String MON_SHA = "ed3c29f07f01d715e962a32d066a0fc03a1274cd40c62e38c4e6e4c6e2c7cbd1";
    static final String MFG_SHA = "4b858dffe2b7c8b7cbc12fac861ebbc224e2ce59f8045ebd3e331088d9625962";

    static RootReader.Result setMode(String mode) {
        if (!"normal".equals(mode) && !"monitor".equals(mode)) {
            return new RootReader.Result(-1, "invalid mode\n", false);
        }
        return RootReader.run("/system/bin/setprop vendor.wlandriver.mode " + mode, 3);
    }

    static RootReader.Result startSamsungLoader() {
        return RootReader.run("/system/bin/setprop ctl.start mfgloader", 5);
    }

    static RootReader.Result wifi(boolean enabled) {
        return RootReader.run(enabled ? "/system/bin/svc wifi enable" : "/system/bin/svc wifi disable", 8);
    }

    static String wifiver() {
        return RootReader.run("cat /sys/wifi/wifiver 2>/dev/null", 4).output;
    }

    static String status() {
        return RootReader.run("getprop vendor.wlandriver.status", 3).output.trim();
    }

    static String mode() {
        return RootReader.run("getprop vendor.wlandriver.mode", 3).output.trim();
    }

    static boolean waitForFirmware(String token, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (wifiver().contains(token)) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static String snapshot(String label) {
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(label).append(" ===\n");
        out.append("mode=").append(mode()).append('\n');
        out.append("status=").append(status()).append('\n');
        out.append("wifiver=\n").append(wifiver());
        out.append(RootReader.run("printf 'firmware_path='; cat /sys/module/dhd/parameters/firmware_path 2>/dev/null; echo; ip link show wlan0 2>&1 | head -6", 5).output);
        return out.toString();
    }

    private MonitorController() {}
}
