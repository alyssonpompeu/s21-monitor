package com.alysson.bcm4375lab;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class NexmonManager {
    static final String STOCK_SHA = "1676f46ce56b96f58dc70de08beaab4ab3362ee6dd751465a8d6a0023c3c54ad";
    static final String NEXMON_SHA = "ec77f799a989e8104322d3c51901685426389c435a968e30d89f134f47c03d0c";
    static final String MODULE_ID = "bcm4375_nexmon_s21";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    private static final String ASSET = "nexmon/bcmdhd_sta_nexmon_18_41_117.bin";

    static File materializeAsset(Context context) throws Exception {
        File out = new File(context.getCacheDir(), "bcmdhd_sta_nexmon_18_41_117.bin");
        try (InputStream in = context.getAssets().open(ASSET); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        String sha = sha256(out);
        if (!NEXMON_SHA.equals(sha)) throw new Exception("Nexmon asset SHA mismatch: " + sha);
        return out;
    }

    static String stockSha() {
        return RootReader.run("sha256sum /vendor/firmware/bcmdhd_sta.bin_b1 2>/dev/null | cut -d' ' -f1", 8).output.trim();
    }

    static String magiskVersion() {
        return RootReader.run("magisk -v 2>/dev/null; magisk -V 2>/dev/null", 5).output.trim();
    }

    static boolean moduleExists() {
        return RootReader.run("test -d " + MODULE_DIR, 3).code == 0;
    }

    static boolean moduleDisabled() {
        return RootReader.run("test -f " + MODULE_DIR + "/disable", 3).code == 0;
    }

    static String moduleFileSha() {
        return RootReader.run("sha256sum " + MODULE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1 2>/dev/null | cut -d' ' -f1", 8).output.trim();
    }

    static RootReader.Result prepareDisabledModule(File firmware) {
        String src = q(firmware.getAbsolutePath());
        String script =
                "set -e; " +
                "rm -rf " + MODULE_DIR + "; " +
                "mkdir -p " + MODULE_DIR + "/system/vendor/firmware; " +
                "cat " + src + " > " + MODULE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1; " +
                "printf '%s\\n' " +
                "'id=" + MODULE_ID + "' " +
                "'name=BCM4375B1 Nexmon 18.41.117 S21' " +
                "'version=3.0.0' " +
                "'versionCode=300' " +
                "'author=BCM4375 Lab' " +
                "'description=Systemless Nexmon firmware overlay for SM-G991B BCM4375B1; disabled by default.' " +
                "> " + MODULE_DIR + "/module.prop; " +
                "touch " + MODULE_DIR + "/disable; " +
                "chmod 0644 " + MODULE_DIR + "/module.prop " + MODULE_DIR + "/disable " + MODULE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1; " +
                "chown -R 0:0 " + MODULE_DIR + "; " +
                "sha256sum " + MODULE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1";
        return RootReader.run(script, 15);
    }

    static RootReader.Result armForNextBoot() {
        return RootReader.run("test -d " + MODULE_DIR + " && rm -f " + MODULE_DIR + "/disable && sync", 5);
    }

    static RootReader.Result disableForNextBoot() {
        return RootReader.run("test -d " + MODULE_DIR + " && touch " + MODULE_DIR + "/disable && chmod 0644 " + MODULE_DIR + "/disable && sync", 5);
    }

    static RootReader.Result removeModuleNextBoot() {
        return RootReader.run("test -d " + MODULE_DIR + " && touch " + MODULE_DIR + "/remove && touch " + MODULE_DIR + "/disable && chmod 0644 " + MODULE_DIR + "/remove " + MODULE_DIR + "/disable && sync", 5);
    }

    static String stateReport() {
        return "module_exists=" + moduleExists() + "\n" +
                "module_disabled=" + moduleDisabled() + "\n" +
                "module_fw_sha=" + moduleFileSha() + "\n" +
                RootReader.run("ls -lZ " + MODULE_DIR + " " + MODULE_DIR + "/system/vendor/firmware 2>&1", 5).output;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : md.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private NexmonManager() {}
}
