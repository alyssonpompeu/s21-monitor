package com.alysson.bcm4375lab;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class NexmonOneShotController {
    static final String MODULE_ID = "bcm4375_nexmon_oneshot";
    static final String ACTIVE_DIR = "/data/adb/modules/" + MODULE_ID;
    static final String STAGED_DIR = "/data/adb/modules_update/" + MODULE_ID;
    static final String STOCK_SHA = "1676f46ce56b96f58dc70de08beaab4ab3362ee6dd751465a8d6a0023c3c54ad";
    static final String NEXMON_SHA = "ec77f799a989e8104322d3c51901685426389c435a968e30d89f134f47c03d0c";
    static final String STOCK_PATH = "/vendor/firmware/bcmdhd_sta.bin_b1";
    static final String RESULT_PATH = "/data/adb/bcm4375_nexmon_oneshot_result.txt";
    static final String STATE_PATH = "/data/adb/bcm4375_nexmon_oneshot_state.txt";
    private static final String ASSET = "nexmon/bcmdhd_sta_nexmon_18_41_117.bin";

    interface Progress { void onProgress(String text); }

    static String wifiver() {
        return RootReader.run("cat /sys/wifi/wifiver 2>/dev/null", 5).output;
    }

    static boolean isNexmonActive() {
        String s = wifiver().toLowerCase(Locale.ROOT);
        return s.contains("nexmon.org") || s.contains("nexmon");
    }

    static String currentFirmwareSha() {
        return RootReader.run("sha256sum " + STOCK_PATH + " 2>/dev/null | cut -d' ' -f1", 8).output.trim();
    }

    static String magiskInfo() {
        return RootReader.run("echo magisk_version=$(magisk -v 2>/dev/null); echo magisk_code=$(magisk -V 2>/dev/null); echo magisk_path=$(magisk --path 2>/dev/null)", 5).output;
    }

    static boolean magiskReady() {
        RootReader.Result r = RootReader.run(
                "command -v magisk >/dev/null 2>&1 && test -d /data/adb/modules && test -w /data/adb && test -f /data/adb/magisk/util_functions.sh", 5);
        return r.code == 0 && !r.timedOut;
    }

    static String moduleState() {
        String cmd =
                "D=''; [ -d '" + STAGED_DIR + "' ] && D='" + STAGED_DIR + "'; " +
                "[ -z \"$D\" ] && [ -d '" + ACTIVE_DIR + "' ] && D='" + ACTIVE_DIR + "'; " +
                "if [ -z \"$D\" ]; then echo ABSENT; " +
                "elif [ -e \"$D/remove\" ]; then echo REMOVE_PENDING; " +
                "elif [ -e \"$D/disable\" ]; then echo DISABLED; else echo ARMED; fi";
        return RootReader.run(cmd, 4).output.trim();
    }

    static String moduleLocation() {
        return RootReader.run(
                "if [ -d '" + STAGED_DIR + "' ]; then echo STAGED; elif [ -d '" + ACTIVE_DIR + "' ]; then echo ACTIVE; else echo NONE; fi", 4).output.trim();
    }

    static String moduleFirmwareSha() {
        String cmd =
                "D='" + ACTIVE_DIR + "'; [ -d '" + STAGED_DIR + "' ] && D='" + STAGED_DIR + "'; " +
                "sha256sum \"$D/system/vendor/firmware/bcmdhd_sta.bin_b1\" 2>/dev/null | cut -d' ' -f1";
        return RootReader.run(cmd, 8).output.trim();
    }

    static boolean resultExists() {
        return RootReader.run("test -f '" + RESULT_PATH + "'", 3).code == 0;
    }

    static File downloadVerifiedFirmware(Context context, Progress progress) throws Exception {
        if (progress != null) progress.onProgress("Extraindo firmware Nexmon embutido no APK…");
        File out = new File(context.getCacheDir(), "bcmdhd_sta_nexmon_18_41_117.bin");
        if (out.exists()) out.delete();
        try (InputStream in = context.getAssets().open(ASSET); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        String sha = sha256(out);
        if (!NEXMON_SHA.equalsIgnoreCase(sha)) {
            out.delete();
            throw new Exception("SHA-256 do Nexmon embutido não confere: " + sha);
        }
        if (progress != null) progress.onProgress("Firmware Nexmon embutido • SHA-256 OK.");
        return out;
    }

    static File buildModuleZip(Context context, File firmware) throws Exception {
        String sha = sha256(firmware);
        if (!NEXMON_SHA.equalsIgnoreCase(sha)) throw new Exception("Firmware Nexmon não autorizado: " + sha);
        File zip = new File(context.getCacheDir(), "BCM4375-Nexmon-OneShot-Magisk.zip");
        if (zip.exists()) zip.delete();

        String moduleProp =
                "id=" + MODULE_ID + "\n" +
                "name=BCM4375B1 Nexmon One-Shot\n" +
                "version=18.41.117-pr663\n" +
                "versionCode=300\n" +
                "author=BCM4375 Lab\n" +
                "description=One-shot Nexmon BCM4375B1 firmware overlay for SM-G991B.\n";

        String postFs =
                "#!/system/bin/sh\n" +
                "{ echo post_fs_data_reached=YES; date; } > " + STATE_PATH + " 2>&1\n";

        String service =
                "#!/system/bin/sh\n" +
                "MODDIR=${0%/*}\n" +
                "touch \"$MODDIR/disable\"\n" +
                "sync\n" +
                "{ echo service_auto_disabled_next_boot=YES; date; } >> " + STATE_PATH + " 2>&1\n" +
                "sleep 10\n" +
                "{\n" +
                "  echo 'BCM4375 Nexmon one-shot boot result'\n" +
                "  date\n" +
                "  echo '-- module --'\n" +
                "  ls -laZ \"$MODDIR\" 2>&1\n" +
                "  echo '-- wifiver --'\n" +
                "  cat /sys/wifi/wifiver 2>&1\n" +
                "  echo '-- mounted firmware sha --'\n" +
                "  sha256sum /vendor/firmware/bcmdhd_sta.bin_b1 2>&1\n" +
                "  echo '-- firmware_path --'\n" +
                "  cat /sys/module/dhd/parameters/firmware_path 2>&1\n" +
                "  echo '-- wlan0 --'\n" +
                "  ip -details link show wlan0 2>&1\n" +
                "  echo '-- selinux --'\n" +
                "  getenforce 2>&1\n" +
                "  echo '-- dhd log --'\n" +
                "  dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap' | tail -320\n" +
                "} > " + RESULT_PATH + " 2>&1\n";

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            putText(zos, "module.prop", moduleProp);
            putText(zos, "post-fs-data.sh", postFs);
            putText(zos, "service.sh", service);
            putFile(zos, "system/vendor/firmware/bcmdhd_sta.bin_b1", firmware);
        }
        return zip;
    }

    static RootReader.Result installDisabledModule(File moduleZip) {
        String path = shellQuote(moduleZip.getAbsolutePath());
        String cmd =
                "rm -f '" + RESULT_PATH + "' '" + STATE_PATH + "'; " +
                "magisk --install-module " + path + " && " +
                "D=''; [ -d '" + STAGED_DIR + "' ] && D='" + STAGED_DIR + "'; " +
                "[ -z \"$D\" ] && [ -d '" + ACTIVE_DIR + "' ] && D='" + ACTIVE_DIR + "'; " +
                "test -n \"$D\" && " +
                "chmod 0755 \"$D/post-fs-data.sh\" \"$D/service.sh\" && " +
                "chmod 0644 \"$D/system/vendor/firmware/bcmdhd_sta.bin_b1\" && " +
                "test \"$(sha256sum \"$D/system/vendor/firmware/bcmdhd_sta.bin_b1\" | cut -d' ' -f1)\" = '" + NEXMON_SHA + "' && " +
                "rm -f \"$D/remove\" && touch \"$D/disable\" && sync";
        return RootReader.run(cmd, 45);
    }

    static RootReader.Result armNextBoot() {
        String cmd =
                "D=''; [ -d '" + STAGED_DIR + "' ] && D='" + STAGED_DIR + "'; " +
                "[ -z \"$D\" ] && [ -d '" + ACTIVE_DIR + "' ] && D='" + ACTIVE_DIR + "'; " +
                "test -n \"$D\" && " +
                "test \"$(sha256sum \"$D/system/vendor/firmware/bcmdhd_sta.bin_b1\" 2>/dev/null | cut -d' ' -f1)\" = '" + NEXMON_SHA + "' && " +
                "rm -f \"$D/remove\" \"$D/disable\" && sync && test ! -e \"$D/disable\"";
        return RootReader.run(cmd, 10);
    }

    static RootReader.Result disarmNextBoot() {
        String cmd =
                "FOUND=0; for D in '" + STAGED_DIR + "' '" + ACTIVE_DIR + "'; do " +
                "if [ -d \"$D\" ]; then touch \"$D/disable\"; FOUND=1; fi; done; sync; [ $FOUND -eq 1 ]";
        return RootReader.run(cmd, 8);
    }

    static RootReader.Result scheduleRemoval() {
        String cmd =
                "FOUND=0; for D in '" + STAGED_DIR + "' '" + ACTIVE_DIR + "'; do " +
                "if [ -d \"$D\" ]; then touch \"$D/disable\" \"$D/remove\"; FOUND=1; fi; done; sync; [ $FOUND -eq 1 ]";
        return RootReader.run(cmd, 8);
    }

    static RootReader.Result reboot() {
        return RootReader.run("sync; reboot", 3);
    }

    static String collectEvidence() {
        StringBuilder out = new StringBuilder();
        out.append("BCM4375 Lab v3.0.0 - Nexmon one-shot evidence\n\n");
        out.append("module_state=").append(moduleState()).append('\n');
        out.append("module_location=").append(moduleLocation()).append('\n');
        out.append("module_fw_sha=").append(moduleFirmwareSha()).append('\n');
        out.append("current_vendor_fw_sha=").append(currentFirmwareSha()).append('\n');
        out.append("nexmon_active=").append(isNexmonActive()).append('\n');
        out.append("result_exists=").append(resultExists()).append('\n');
        out.append("\n=== WIFIVER ===\n").append(wifiver());
        out.append("\n=== MAGISK ===\n").append(magiskInfo());
        out.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        out.append("\n=== ACTIVE MODULE ===\n").append(RootReader.run("ls -laZ '" + ACTIVE_DIR + "' '" + ACTIVE_DIR + "/system/vendor/firmware' 2>&1", 5).output);
        out.append("\n=== STAGED MODULE ===\n").append(RootReader.run("ls -laZ '" + STAGED_DIR + "' '" + STAGED_DIR + "/system/vendor/firmware' 2>&1", 5).output);
        out.append("\n=== AUTO BOOT STATE ===\n").append(RootReader.run("cat '" + STATE_PATH + "' 2>&1", 4).output);
        out.append("\n=== AUTO BOOT RESULT ===\n").append(RootReader.run("cat '" + RESULT_PATH + "' 2>&1", 6).output);
        out.append("\n=== LIVE DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap' | tail -360", 8).output);
        return out.toString();
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        return hex.toString();
    }

    private static void putText(ZipOutputStream zos, String name, String body) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(body.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String name, File source) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private NexmonOneShotController() {}
}
