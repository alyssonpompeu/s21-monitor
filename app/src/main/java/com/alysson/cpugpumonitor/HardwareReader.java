package com.alysson.cpugpumonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HardwareReader {
    private static long previousTotal = -1;
    private static long previousIdle = -1;
    private static long previousAppCpuMs = -1;
    private static long previousWallMs = -1;
    private static File gpuCurFile;
    private static File gpuMaxFile;
    private static File gpuLoadFile;
    private static boolean gpuDiscoveryDone;

    private HardwareReader() {}

    static synchronized StatsSnapshot sample(Context context) {
        StatsSnapshot out = new StatsSnapshot();
        out.timestamp = System.currentTimeMillis();

        long[] freq = readCpuFrequencies();
        out.cpuCurrentMhz = freq[0] / 1000L;
        out.cpuMaxMhz = freq[1] / 1000L;

        float procCpu = readProcStatPercent();
        if (procCpu >= 0f) {
            out.cpuPercent = procCpu;
            out.cpuEstimated = false;
        } else if (freq[1] > 0) {
            out.cpuPercent = clamp(freq[2] / 100f);
            out.cpuEstimated = true;
        } else {
            out.cpuPercent = 0f;
            out.cpuEstimated = true;
        }

        long nowWall = SystemClock.elapsedRealtime();
        long nowApp = Process.getElapsedCpuTime();
        if (previousWallMs > 0 && nowWall > previousWallMs) {
            out.appCpuPercent = clamp((nowApp - previousAppCpuMs) * 100f / (nowWall - previousWallMs));
        }
        previousWallMs = nowWall;
        previousAppCpuMs = nowApp;

        readGpu(out);
        readMemory(context, out);
        out.batteryTempC = readBatteryTemperature(context);
        return out;
    }

    private static long[] readCpuFrequencies() {
        int cores = Runtime.getRuntime().availableProcessors();
        long currentSum = 0;
        long maxSum = 0;
        int count = 0;
        float normalizedSum = 0f;
        for (int i = 0; i < cores; i++) {
            long cur = readLong(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"));
            long max = readLong(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"));
            if (max <= 0) max = readLong(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq"));
            if (cur > 0 && max > 0) {
                currentSum += cur;
                maxSum += max;
                normalizedSum += Math.min(100f, cur * 100f / max);
                count++;
            }
        }
        if (count == 0) return new long[]{0, 0, 0};
        return new long[]{currentSum / count, maxSum / count, Math.round(normalizedSum / count * 100f)};
    }

    private static float readProcStatPercent() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return -1f;
            String[] p = line.trim().split("\\s+");
            long user = parseLong(p, 1);
            long nice = parseLong(p, 2);
            long system = parseLong(p, 3);
            long idle = parseLong(p, 4);
            long ioWait = parseLong(p, 5);
            long irq = parseLong(p, 6);
            long softIrq = parseLong(p, 7);
            long steal = parseLong(p, 8);
            long total = user + nice + system + idle + ioWait + irq + softIrq + steal;
            long idleAll = idle + ioWait;
            if (previousTotal < 0 || total <= previousTotal) {
                previousTotal = total;
                previousIdle = idleAll;
                return -1f;
            }
            long totalDelta = total - previousTotal;
            long idleDelta = idleAll - previousIdle;
            previousTotal = total;
            previousIdle = idleAll;
            if (totalDelta <= 0) return -1f;
            return clamp((totalDelta - idleDelta) * 100f / totalDelta);
        } catch (Exception ignored) {
            return -1f;
        }
    }

    private static void discoverGpuFiles() {
        if (gpuDiscoveryDone) return;
        gpuDiscoveryDone = true;

        List<File> roots = new ArrayList<>();
        File devfreq = new File("/sys/class/devfreq");
        File[] devfreqChildren = devfreq.listFiles();
        if (devfreqChildren != null) {
            for (File child : devfreqChildren) {
                String n = child.getName().toLowerCase(Locale.US);
                if (n.contains("mali") || n.contains("gpu") || n.contains("18500000")) roots.add(child);
            }
        }
        String[] direct = {
                "/sys/devices/platform/18500000.mali/devfreq/18500000.mali",
                "/sys/class/misc/mali0/device/devfreq/18500000.mali",
                "/sys/class/misc/mali0/device",
                "/sys/kernel/gpu",
                "/sys/devices/platform/gpusysfs"
        };
        for (String path : direct) roots.add(new File(path));

        for (File root : roots) {
            if (gpuCurFile == null) gpuCurFile = firstReadable(root, "cur_freq", "gpu_clock", "clock");
            if (gpuMaxFile == null) gpuMaxFile = firstReadable(root, "max_freq", "gpu_max_clock", "max_clock");
            if (gpuLoadFile == null) gpuLoadFile = firstReadable(root, "utilization", "load", "gpu_busy", "busy");
        }
    }

    private static File firstReadable(File root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            File f = new File(root, name);
            if (f.isFile() && f.canRead()) return f;
            File device = new File(root, "device/" + name);
            if (device.isFile() && device.canRead()) return device;
        }
        return null;
    }

    private static void readGpu(StatsSnapshot out) {
        discoverGpuFiles();
        long cur = readLong(gpuCurFile);
        long max = readLong(gpuMaxFile);
        float load = readUtilization(gpuLoadFile);

        if (cur > 10_000_000L) cur /= 1_000_000L;
        else if (cur > 10_000L) cur /= 1000L;
        if (max > 10_000_000L) max /= 1_000_000L;
        else if (max > 10_000L) max /= 1000L;

        out.gpuCurrentMhz = cur;
        out.gpuMaxMhz = max;
        if (load >= 0f) {
            out.gpuAvailable = true;
            out.gpuPercent = clamp(load);
            out.gpuEstimated = false;
            out.gpuSource = gpuLoadFile == null ? "contador" : gpuLoadFile.getAbsolutePath();
        } else if (cur > 0 && max > 0) {
            out.gpuAvailable = true;
            out.gpuPercent = clamp(cur * 100f / max);
            out.gpuEstimated = true;
            out.gpuSource = "frequência relativa";
        } else {
            out.gpuAvailable = false;
            out.gpuPercent = 0f;
            out.gpuEstimated = true;
            out.gpuSource = "firmware bloqueou o contador";
        }
    }

    private static float readUtilization(File file) {
        String raw = readText(file);
        if (raw == null || raw.isEmpty()) return -1f;
        try {
            String cleaned = raw.trim().replace('%', ' ').replace('@', ' ');
            String[] parts = cleaned.split("[^0-9.]+");
            List<Double> numbers = new ArrayList<>();
            for (String p : parts) if (!p.isEmpty()) numbers.add(Double.parseDouble(p));
            if (numbers.isEmpty()) return -1f;
            if (numbers.size() >= 2 && numbers.get(1) > 100d && numbers.get(0) <= numbers.get(1)) {
                return (float) (numbers.get(0) * 100d / numbers.get(1));
            }
            double first = numbers.get(0);
            if (first > 100d && first <= 1000d) first /= 10d;
            return first <= 100d ? (float) first : -1f;
        } catch (Exception ignored) {
            return -1f;
        }
    }

    private static void readMemory(Context context, StatsSnapshot out) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        out.ramTotalMb = mi.totalMem / 1024L / 1024L;
        out.ramUsedMb = used / 1024L / 1024L;
        out.ramPercent = mi.totalMem > 0 ? clamp(used * 100f / mi.totalMem) : 0f;
    }

    private static float readBatteryTemperature(Context context) {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return Float.NaN;
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
        } catch (Exception ignored) {
            return Float.NaN;
        }
    }

    static String deviceLabel() {
        return Build.MANUFACTURER + " " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE;
    }

    private static long readLong(File file) {
        String s = readText(file);
        if (s == null) return -1;
        try {
            String digits = s.trim().split("[^0-9]+")[0];
            return digits.isEmpty() ? -1 : Long.parseLong(digits);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile() || !file.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long parseLong(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try { return Long.parseLong(parts[index]); } catch (Exception ignored) { return 0; }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(100f, value));
    }
}
