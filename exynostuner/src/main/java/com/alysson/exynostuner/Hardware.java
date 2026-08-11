package com.alysson.exynostuner;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Hardware {
    static final int WRITE_TEMP_LIMIT_MC = 85_000;

    static final class CpuPolicy {
        String path;
        String cpus;
        String governor;
        long current;
        long min;
        long max;
        long hwMin;
        long hwMax;
        boolean minWritable;
        boolean maxWritable;
        final List<Long> frequencies = new ArrayList<>();

        String title() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.toUpperCase(Locale.US) + (TextUtils.isEmpty(cpus) ? "" : " • CPUs " + cpus);
        }
    }

    static final class DevDevice {
        String path;
        String name;
        String governor;
        long current;
        long min;
        long max;
        boolean minWritable;
        boolean maxWritable;
        final List<Long> frequencies = new ArrayList<>();

        String label() {
            return TextUtils.isEmpty(name) ? path.substring(path.lastIndexOf('/') + 1) : name;
        }
    }

    static final class Snapshot {
        boolean rooted;
        String rootManager = "—";
        final List<CpuPolicy> cpuPolicies = new ArrayList<>();
        final List<DevDevice> devfreq = new ArrayList<>();
        DevDevice gpu;
        DevDevice mif;
        String devfreqSummary = "";
        String thermalSummary = "";
        int hottestMilliC;
    }

    static final class LiveSnapshot {
        final Map<String, Long> currentByPath = new LinkedHashMap<>();
        int hottestMilliC;
        String hottestType = "—";
        String thermalSummary = "";
    }

    static Snapshot detect() {
        Snapshot s = new Snapshot();
        RootShell.Result root = RootShell.run("id");
        s.rooted = root.code == 0 && root.output.contains("uid=0");
        s.rootManager = s.rooted ? detectRootManager() : detectSu();
        if (!s.rooted) return s;

        String cpuPaths = RootShell.run("for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"$p\" ] && echo \"$p\"; done").output;
        for (String p : lines(cpuPaths)) {
            CpuPolicy cpu = readCpu(p);
            if (cpu != null) s.cpuPolicies.add(cpu);
        }

        String devPaths = RootShell.run("for d in /sys/class/devfreq/* /sys/class/kgsl/kgsl-3d0/devfreq; do [ -d \"$d\" ] && readlink -f \"$d\"; done | awk '!seen[$0]++'").output;
        int bestGpuScore = 0;
        int bestMifScore = 0;
        for (String p : lines(devPaths)) {
            DevDevice d = readDev(p);
            if (d == null) continue;
            s.devfreq.add(d);
            String key = (d.name + " " + d.path).toLowerCase(Locale.US);
            int gpuScore = gpuScore(key);
            int mifScore = mifScore(key);
            if (gpuScore > bestGpuScore) {
                bestGpuScore = gpuScore;
                s.gpu = d;
            }
            if (mifScore > bestMifScore) {
                bestMifScore = mifScore;
                s.mif = d;
            }
        }

        StringBuilder dev = new StringBuilder();
        for (DevDevice d : s.devfreq) {
            if (dev.length() > 0) dev.append('\n');
            dev.append(d.label()).append(" — ").append(d.path);
        }
        s.devfreqSummary = dev.length() == 0 ? "Nenhum devfreq exposto." : dev.toString();

        LiveSnapshot live = readLive(s);
        s.hottestMilliC = live.hottestMilliC;
        s.thermalSummary = live.thermalSummary;
        return s;
    }

    static LiveSnapshot readLive(Snapshot s) {
        LiveSnapshot out = new LiveSnapshot();
        if (s == null || !s.rooted) return out;
        StringBuilder cmd = new StringBuilder();
        for (CpuPolicy c : s.cpuPolicies) {
            cmd.append("printf 'C|").append(shellSafeTag(c.path)).append("|'; cat ")
                    .append(RootShell.q(c.path + "/scaling_cur_freq")).append(" 2>/dev/null || echo 0; ");
        }
        if (s.gpu != null) {
            cmd.append("printf 'D|").append(shellSafeTag(s.gpu.path)).append("|'; ")
                    .append(readCurrentShell(s.gpu.path)).append("; ");
        }
        if (s.mif != null && (s.gpu == null || !s.mif.path.equals(s.gpu.path))) {
            cmd.append("printf 'D|").append(shellSafeTag(s.mif.path)).append("|'; ")
                    .append(readCurrentShell(s.mif.path)).append("; ");
        }
        cmd.append("for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; ")
                .append("ty=$(cat \"$z/type\" 2>/dev/null); te=$(cat \"$z/temp\" 2>/dev/null); ")
                .append("[ -n \"$te\" ] && printf 'T|%s|%s\\n' \"$ty\" \"$te\"; done");

        RootShell.Result r = RootShell.run(cmd.toString());
        StringBuilder thermal = new StringBuilder();
        int relevantMax = 0;
        String relevantType = "—";
        int anyMax = 0;
        String anyType = "—";
        int shown = 0;
        for (String line : lines(r.output)) {
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            if ("C".equals(p[0]) || "D".equals(p[0])) {
                long v = parseLong(p[2]);
                if (v > 0) out.currentByPath.put(p[1], v);
            } else if ("T".equals(p[0])) {
                int mc = normalizeTemp(parseLong(p[2]));
                if (mc <= 0 || mc > 150_000) continue;
                String type = p[1].isEmpty() ? "thermal" : p[1];
                if (mc > anyMax) { anyMax = mc; anyType = type; }
                if (isRelevantThermal(type) && mc > relevantMax) {
                    relevantMax = mc;
                    relevantType = type;
                }
                if (isRelevantThermal(type) && shown < 8) {
                    if (thermal.length() > 0) thermal.append(" • ");
                    thermal.append(type).append(' ').append(formatTemp(mc));
                    shown++;
                }
            }
        }
        out.hottestMilliC = relevantMax > 0 ? relevantMax : anyMax;
        out.hottestType = relevantMax > 0 ? relevantType : anyType;
        out.thermalSummary = thermal.length() == 0
                ? (out.hottestMilliC > 0 ? out.hottestType + " " + formatTemp(out.hottestMilliC) : "Sensores térmicos não expostos.")
                : thermal.toString();
        return out;
    }

    static RootShell.Result applyCpu(CpuPolicy c, long min, long max) {
        if (c == null) return new RootShell.Result(-1, "CPU policy ausente");
        return writeRange(c.path + "/scaling_min_freq", c.path + "/scaling_max_freq", min, max);
    }

    static RootShell.Result applyDev(DevDevice d, long min, long max) {
        if (d == null) return new RootShell.Result(-1, "devfreq ausente");
        String minFile = firstExisting(d.path, "min_freq", "scaling_min_freq");
        String maxFile = firstExisting(d.path, "max_freq", "scaling_max_freq");
        if (minFile.isEmpty() || maxFile.isEmpty()) return new RootShell.Result(-1, "min/max não expostos");
        return writeRange(minFile, maxFile, min, max);
    }

    static RootShell.Result restoreCpu(CpuPolicy c, long min, long max) {
        return applyCpu(c, min, max);
    }

    static RootShell.Result restoreDev(DevDevice d, long min, long max) {
        return applyDev(d, min, max);
    }

    static String formatFreq(long value) {
        if (value <= 0) return "—";
        double mhz;
        if (value >= 10_000_000L) mhz = value / 1_000_000.0;
        else if (value >= 10_000L) mhz = value / 1_000.0;
        else mhz = value;
        if (mhz >= 100) return String.format(Locale.US, "%.0f MHz", mhz);
        return String.format(Locale.US, "%.1f MHz", mhz);
    }

    static String formatTemp(int mc) {
        if (mc <= 0) return "—";
        return String.format(Locale.US, "%.1f °C", mc / 1000.0);
    }

    static int nearestIndex(List<Long> list, long value) {
        if (list == null || list.isEmpty()) return 0;
        int best = 0;
        long dist = Math.abs(list.get(0) - value);
        for (int i = 1; i < list.size(); i++) {
            long d = Math.abs(list.get(i) - value);
            if (d < dist) { dist = d; best = i; }
        }
        return best;
    }

    private static CpuPolicy readCpu(String path) {
        if (TextUtils.isEmpty(path)) return null;
        CpuPolicy c = new CpuPolicy();
        c.path = path;
        c.cpus = read(path + "/related_cpus");
        if (c.cpus.isEmpty()) c.cpus = read(path + "/affected_cpus");
        c.governor = read(path + "/scaling_governor");
        c.current = readLong(path + "/scaling_cur_freq");
        c.min = readLong(path + "/scaling_min_freq");
        c.max = readLong(path + "/scaling_max_freq");
        c.hwMin = readLong(path + "/cpuinfo_min_freq");
        c.hwMax = readLong(path + "/cpuinfo_max_freq");
        c.minWritable = writable(path + "/scaling_min_freq");
        c.maxWritable = writable(path + "/scaling_max_freq");
        Set<Long> f = new LinkedHashSet<>();
        addNumbers(f, read(path + "/scaling_available_frequencies"));
        addTimeInState(f, read(path + "/stats/time_in_state"));
        if (c.hwMin > 0) f.add(c.hwMin);
        if (c.min > 0) f.add(c.min);
        if (c.current > 0) f.add(c.current);
        if (c.max > 0) f.add(c.max);
        if (c.hwMax > 0) f.add(c.hwMax);
        c.frequencies.addAll(f);
        Collections.sort(c.frequencies);
        return c;
    }

    private static DevDevice readDev(String path) {
        if (TextUtils.isEmpty(path)) return null;
        DevDevice d = new DevDevice();
        d.path = path;
        d.name = read(path + "/name");
        if (d.name.isEmpty()) d.name = path.substring(path.lastIndexOf('/') + 1);
        d.governor = read(path + "/governor");
        d.current = readCurrent(path);
        String minFile = firstExisting(path, "min_freq", "scaling_min_freq");
        String maxFile = firstExisting(path, "max_freq", "scaling_max_freq");
        d.min = minFile.isEmpty() ? 0 : readLong(minFile);
        d.max = maxFile.isEmpty() ? 0 : readLong(maxFile);
        d.minWritable = !minFile.isEmpty() && writable(minFile);
        d.maxWritable = !maxFile.isEmpty() && writable(maxFile);
        Set<Long> f = new LinkedHashSet<>();
        addNumbers(f, read(path + "/available_frequencies"));
        addTransitionFrequencies(f, read(path + "/trans_stat"));
        if (d.min > 0) f.add(d.min);
        if (d.current > 0) f.add(d.current);
        if (d.max > 0) f.add(d.max);
        d.frequencies.addAll(f);
        Collections.sort(d.frequencies);
        return d;
    }

    private static RootShell.Result writeRange(String minFile, String maxFile, long min, long max) {
        if (min <= 0 || max <= 0 || min > max) return new RootShell.Result(-1, "faixa inválida");
        long curMin = readLong(minFile);
        long curMax = readLong(maxFile);
        String cmd;
        if (min > curMax) {
            cmd = "echo " + max + " > " + RootShell.q(maxFile) + " && echo " + min + " > " + RootShell.q(minFile);
        } else if (max < curMin) {
            cmd = "echo " + min + " > " + RootShell.q(minFile) + " && echo " + max + " > " + RootShell.q(maxFile);
        } else {
            cmd = "echo " + min + " > " + RootShell.q(minFile) + " && echo " + max + " > " + RootShell.q(maxFile);
        }
        return RootShell.run(cmd);
    }

    private static String firstExisting(String path, String... names) {
        for (String n : names) {
            String p = path + "/" + n;
            RootShell.Result r = RootShell.run("[ -e " + RootShell.q(p) + " ] && echo yes || true");
            if (r.output.trim().equals("yes")) return p;
        }
        return "";
    }

    private static long readCurrent(String path) {
        long v = readLong(path + "/cur_freq");
        if (v <= 0) v = readLong(path + "/target_freq");
        if (v <= 0 && path.toLowerCase(Locale.US).contains("kgsl")) v = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk");
        return v;
    }

    private static String readCurrentShell(String path) {
        String cur = RootShell.q(path + "/cur_freq");
        String target = RootShell.q(path + "/target_freq");
        return "v=$(cat " + cur + " 2>/dev/null); [ -z \"$v\" ] && v=$(cat " + target + " 2>/dev/null); echo ${v:-0}";
    }

    private static String read(String path) {
        RootShell.Result r = RootShell.run("cat " + RootShell.q(path) + " 2>/dev/null");
        return r.code == 0 ? r.output.trim() : "";
    }

    private static long readLong(String path) {
        return parseLong(read(path));
    }

    private static boolean writable(String path) {
        return RootShell.run("[ -w " + RootShell.q(path) + " ] && echo yes || echo no").output.trim().equals("yes");
    }

    private static String detectRootManager() {
        RootShell.Result r = RootShell.run(
                "if command -v magisk >/dev/null 2>&1; then printf 'Magisk '; magisk -V 2>/dev/null || true; " +
                "elif [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then echo KernelSU; " +
                "elif [ -d /data/adb/ap ] || command -v apd >/dev/null 2>&1; then echo APatch; " +
                "else echo 'su/root disponível'; fi");
        return r.output.trim().isEmpty() ? "su/root disponível" : r.output.trim();
    }

    private static String detectSu() {
        RootShell.Result r = RootShell.direct("command -v su 2>/dev/null || which su 2>/dev/null");
        return r.output.trim().isEmpty() ? "su não encontrado" : "su encontrado, acesso negado/não concedido";
    }

    private static int gpuScore(String key) {
        int s = 0;
        if (key.contains("gpu")) s += 8;
        if (key.contains("mali")) s += 10;
        if (key.contains("g3d")) s += 10;
        if (key.contains("adreno")) s += 10;
        if (key.contains("kgsl")) s += 8;
        return s;
    }

    private static int mifScore(String key) {
        if (key.contains("memlat")) return 0;
        int s = 0;
        if (key.contains("devfreq_mif")) s += 20;
        if (key.matches(".*(^|[^a-z])mif([^a-z]|$).*")) s += 15;
        if (key.contains("mif")) s += 12;
        if (key.contains("dram")) s += 10;
        if (key.contains("dmc")) s += 10;
        if (key.contains("memory-controller") || key.contains("memory_controller")) s += 8;
        return s;
    }

    private static boolean isRelevantThermal(String type) {
        String s = type == null ? "" : type.toLowerCase(Locale.US);
        return s.contains("cpu") || s.contains("gpu") || s.contains("g3d") || s.contains("mali") ||
                s.contains("soc") || s.contains("big") || s.contains("mid") || s.contains("little") ||
                s.contains("cluster");
    }

    private static int normalizeTemp(long v) {
        if (v <= 0) return 0;
        if (v < 1000) v *= 1000;
        return (int)Math.min(Integer.MAX_VALUE, v);
    }

    private static long parseLong(String text) {
        try {
            if (text == null) return 0;
            String x = text.trim().split("\\s+")[0].replaceAll("[^0-9]", "");
            return x.isEmpty() ? 0 : Long.parseLong(x);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void addNumbers(Set<Long> out, String text) {
        if (text == null) return;
        for (String token : text.trim().split("\\s+")) {
            long v = parseLong(token);
            if (v > 0) out.add(v);
        }
    }

    private static void addTimeInState(Set<Long> out, String text) {
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length > 0) {
                long v = parseLong(p[0]);
                if (v > 0) out.add(v);
            }
        }
    }

    private static void addTransitionFrequencies(Set<Long> out, String text) {
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String clean = line.trim().replace("*", "");
            if (clean.isEmpty()) continue;
            long v = parseLong(clean.split("\\s+")[0]);
            if (v > 1000) out.add(v);
        }
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String x : text.split("\\n")) {
            x = x.trim();
            if (!x.isEmpty()) out.add(x);
        }
        return out;
    }

    private static String shellSafeTag(String path) {
        return path.replace("|", "_").replace("\n", "_");
    }
}
