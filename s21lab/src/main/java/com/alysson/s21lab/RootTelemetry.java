package com.alysson.s21lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootTelemetry {
    private RootTelemetry() {}

    static String exec(String command, int timeoutSec) {
        Process proc = null;
        try {
            final Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            proc = process;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                } catch (IOException ignored) {}
            });
            reader.start();
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "TIMEOUT";
            }
            reader.join(1000);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ERR:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    static boolean hasRoot() {
        return exec("id", 5).contains("uid=0");
    }

    static String moduleInfo() {
        return exec("cat /data/adb/modules/g991b_performance_unlock/module.prop 2>/dev/null || echo module=NA", 5).trim();
    }

    static String snapshot() {
        String script =
            "readn(){ if [ -r \"$1\" ]; then tr -d '\\n' < \"$1\"; else printf NA; fi; }; " +
            "tzn(){ for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; [ \"$(cat \"$z/type\" 2>/dev/null)\" = \"$1\" ] && { readn \"$z/temp\"; return; }; done; printf NA; }; " +
            "cdn(){ for c in /sys/class/thermal/cooling_device*; do [ -d \"$c\" ] || continue; [ \"$(cat \"$c/type\" 2>/dev/null)\" = \"$1\" ] && { readn \"$c/cur_state\"; return; }; done; printf NA; }; " +
            "mif=''; for d in /sys/class/devfreq/*mif*; do [ -e \"$d\" ] && { mif=\"$d\"; break; }; done; " +
            "printf 'p0_cur='; readn /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq; " +
            "printf '|p0_max='; readn /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq; " +
            "printf '|p4_cur='; readn /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq; " +
            "printf '|p4_max='; readn /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq; " +
            "printf '|p7_cur='; readn /sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq; " +
            "printf '|p7_max='; readn /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq; " +
            "printf '|gpu='; readn /sys/devices/platform/18500000.mali/clock; " +
            "printf '|gpu_max_lock='; readn /sys/devices/platform/18500000.mali/dvfs_max_lock; " +
            "printf '|uv0='; readn /sys/kernel/percent_margin/cpucl0_margin_percent; " +
            "printf '|uv1='; readn /sys/kernel/percent_margin/cpucl1_margin_percent; " +
            "printf '|uv2='; readn /sys/kernel/percent_margin/cpucl2_margin_percent; " +
            "printf '|uvg='; readn /sys/kernel/percent_margin/g3d_margin_percent; " +
            "printf '|uvmif='; readn /sys/kernel/percent_margin/mif_margin_percent; " +
            "printf '|uvdsu='; readn /sys/kernel/percent_margin/dsu_margin_percent; " +
            "printf '|uvint='; readn /sys/kernel/percent_margin/int_margin_percent; " +
            "printf '|BIG='; tzn BIG; printf '|MID='; tzn MID; printf '|LITTLE='; tzn LITTLE; printf '|G3D='; tzn G3D; " +
            "printf '|cool0='; cdn thermal-cpufreq-0; printf '|cool1='; cdn thermal-cpufreq-1; printf '|cool2='; cdn thermal-cpufreq-2; printf '|coolg='; cdn thermal-gpufreq-0; " +
            "printf '|mif_cur='; [ -n \"$mif\" ] && readn \"$mif/cur_freq\" || printf NA; " +
            "printf '|mif_min='; [ -n \"$mif\" ] && readn \"$mif/min_freq\" || printf NA; " +
            "printf '|mif_max='; [ -n \"$mif\" ] && readn \"$mif/max_freq\" || printf NA; " +
            "printf '|batt_temp='; readn /sys/class/power_supply/battery/temp; " +
            "printf '|batt_current='; readn /sys/class/power_supply/battery/current_now; " +
            "printf '|batt_voltage='; readn /sys/class/power_supply/battery/voltage_now; " +
            "printf '|load='; cut -d' ' -f1 /proc/loadavg 2>/dev/null; echo";
        return exec(script, 5).trim();
    }

    static String staticHardware() {
        String cmd =
            "echo '--- CPU available ---'; " +
            "for p in 0 4 7; do echo policy$p; cat /sys/devices/system/cpu/cpufreq/policy$p/scaling_available_frequencies 2>/dev/null; done; " +
            "echo '--- MALI DVFS ---'; cat /sys/devices/platform/18500000.mali/dvfs_table 2>/dev/null; " +
            "echo '--- MALI ASV ---'; cat /sys/devices/platform/18500000.mali/asv_table 2>/dev/null; " +
            "echo '--- MIF ---'; for d in /sys/class/devfreq/*mif*; do [ -e \"$d\" ] || continue; echo $d; cat $d/available_frequencies 2>/dev/null; done";
        return exec(cmd, 8).trim();
    }
}
