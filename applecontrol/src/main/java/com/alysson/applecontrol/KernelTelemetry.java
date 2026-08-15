package com.alysson.applecontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight root-backed kernel/thermal telemetry for the G991B lab. */
final class KernelTelemetry {
    private KernelTelemetry() {}

    static final String[] UV_KEYS = {"uv0", "uv1", "uv2", "uvg", "uvmif", "uvdsu", "uvint"};

    static final class ExecResult {
        final int exitCode;
        final String output;
        ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    /** One persistent Magisk su shell: no new su process for each sample. */
    static final class RootShell implements Closeable {
        private static final String EOF = "__APPLE_ROOT_EOF__";
        private final java.lang.Process process;
        private final BufferedWriter writer;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final AtomicLong seq = new AtomicLong();
        private final Thread readerThread;
        private volatile boolean closed;

        private RootShell(java.lang.Process process) throws IOException {
            this.process = process;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) lines.offer(line);
                } catch (IOException ignored) {
                } finally {
                    lines.offer(EOF);
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }, "AppleRootReader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        static RootShell open() throws IOException {
            java.lang.Process p = new java.lang.ProcessBuilder("su")
                    .redirectErrorStream(true).start();
            RootShell shell = new RootShell(p);
            ExecResult id = shell.exec("id", 12000);
            if (id.exitCode != 0 || !id.output.contains("uid=0")) {
                shell.close();
                throw new IOException("root não concedido: " + id.output.trim());
            }
            return shell;
        }

        synchronized ExecResult exec(String command, long timeoutMs) {
            if (closed || !process.isAlive()) return new ExecResult(127, "ROOT_SHELL_CLOSED");
            long n = seq.incrementAndGet();
            String marker = "__APPLE_END_" + n + "__";
            StringBuilder out = new StringBuilder();
            try {
                // Newlines close the shell group safely even when command itself ends in ';'.
                writer.write("{\n" + command + "\n}\n__apple_rc=$?\nprintf '\\n" + marker + ":%s\\n' \"$__apple_rc\"\n");
                writer.flush();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return new ExecResult(124, "TIMEOUT");
                    String line = lines.poll(remaining, TimeUnit.NANOSECONDS);
                    if (line == null) return new ExecResult(124, "TIMEOUT");
                    if (EOF.equals(line)) return new ExecResult(127, out.toString().trim());
                    if (line.startsWith(marker + ":")) {
                        int rc = 0;
                        try { rc = Integer.parseInt(line.substring((marker + ":").length()).trim()); }
                        catch (Exception ignored) {}
                        return new ExecResult(rc, out.toString().trim());
                    }
                    out.append(line).append('\n');
                }
            } catch (Exception e) {
                return new ExecResult(127, "ROOT_ERR:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }

        boolean isAlive() { return !closed && process.isAlive(); }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { writer.write("exit\n"); writer.flush(); } catch (Exception ignored) {}
            try { process.destroy(); } catch (Exception ignored) {}
        }
    }

    static String snapshotCommand() {
        return
            "readn(){ if [ -r \"$2\" ]; then v=$(cat \"$2\" 2>/dev/null | tr -d '\\r\\n'); [ -n \"$v\" ] && printf '%s=%s\\n' \"$1\" \"$v\" || printf '%s=NA\\n' \"$1\"; else printf '%s=NA\\n' \"$1\"; fi; }; " +
            "tzexact(){ key=\"$1\"; want=\"$2\"; for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; t=$(cat \"$z/type\" 2>/dev/null | tr -d '\\r\\n'); if [ \"$t\" = \"$want\" ]; then readn \"$key\" \"$z/temp\"; return; fi; done; printf '%s=NA\\n' \"$key\"; }; " +
            "mifzone=''; miftype=''; for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; t=$(cat \"$z/type\" 2>/dev/null | tr -d '\\r\\n'); case \"$t\" in *MIF*|*mif*|*DDR*|*ddr*|*DRAM*|*dram*|*MEMORY*|*memory*) mifzone=\"$z\"; miftype=\"$t\"; break;; esac; done; " +
            "mif=''; for d in /sys/class/devfreq/*mif*; do [ -e \"$d\" ] && { mif=\"$d\"; break; }; done; " +
            "readn p0_cur /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq; " +
            "readn p0_max /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq; " +
            "readn p4_cur /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq; " +
            "readn p4_max /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq; " +
            "readn p7_cur /sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq; " +
            "readn p7_max /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq; " +
            "readn gpu_clock /sys/devices/platform/18500000.mali/clock; " +
            "readn gpu_util /sys/devices/platform/18500000.mali/utilization; " +
            "readn gpu_max_lock /sys/devices/platform/18500000.mali/dvfs_max_lock; " +
            "readn gpu_min_lock /sys/devices/platform/18500000.mali/dvfs_min_lock; " +
            "readn uv0 /sys/kernel/percent_margin/cpucl0_margin_percent; " +
            "readn uv1 /sys/kernel/percent_margin/cpucl1_margin_percent; " +
            "readn uv2 /sys/kernel/percent_margin/cpucl2_margin_percent; " +
            "readn uvg /sys/kernel/percent_margin/g3d_margin_percent; " +
            "readn uvmif /sys/kernel/percent_margin/mif_margin_percent; " +
            "readn uvdsu /sys/kernel/percent_margin/dsu_margin_percent; " +
            "readn uvint /sys/kernel/percent_margin/int_margin_percent; " +
            "tzexact little_temp LITTLE; tzexact mid_temp MID; tzexact big_temp BIG; tzexact g3d_temp G3D; " +
            "if [ -n \"$mifzone\" ]; then readn mif_temp \"$mifzone/temp\"; printf 'mif_temp_source=%s\\n' \"$miftype\"; else printf 'mif_temp=NA\\nmif_temp_source=NA\\n'; fi; " +
            "if [ -n \"$mif\" ]; then readn mif_cur \"$mif/cur_freq\"; readn mif_min \"$mif/min_freq\"; readn mif_max \"$mif/max_freq\"; else printf 'mif_cur=NA\\nmif_min=NA\\nmif_max=NA\\n'; fi; " +
            "readn batt_temp /sys/class/power_supply/battery/temp; " +
            "printf 'load='; cut -d' ' -f1 /proc/loadavg 2>/dev/null || printf NA; printf '\\n'";
    }

    static String applyMarginsCommand(String[] nodes, int[] values) {
        StringBuilder s = new StringBuilder("apple_rc=0; ");
        for (int i = 0; i < nodes.length; i++) {
            s.append("if [ \"$apple_rc\" -eq 0 ]; then ")
             .append("if [ -e ").append(nodes[i]).append(" ] && [ -w ").append(nodes[i]).append(" ]; then ")
             .append("printf '%s\\n' '").append(values[i]).append("' > ").append(nodes[i]).append(" 2>/dev/null || apple_rc=$?; ")
             .append("else apple_rc=13; fi; fi; ");
        }
        s.append("[ \"$apple_rc\" -eq 0 ]");
        return s.toString();
    }

    static final class Snapshot {
        final Map<String,String> values;
        Snapshot(Map<String,String> values) { this.values = values; }

        static Snapshot parse(String text) {
            Map<String,String> map = new LinkedHashMap<>();
            if (text != null) {
                for (String line : text.split("\\n")) {
                    int p = line.indexOf('=');
                    if (p > 0) map.put(line.substring(0, p).trim(), line.substring(p + 1).trim());
                }
            }
            return new Snapshot(map);
        }

        String get(String key) { return values.getOrDefault(key, "NA"); }
        Integer intValue(String key) { try { return Integer.parseInt(get(key)); } catch (Exception e) { return null; } }
        Long longValue(String key) { try { return Long.parseLong(get(key)); } catch (Exception e) { return null; } }

        Double tempC(String key) {
            try {
                double v = Double.parseDouble(get(key));
                if (Math.abs(v) > 1000.0) v /= 1000.0;
                else if ("batt_temp".equals(key) && Math.abs(v) > 100.0) v /= 10.0;
                return v;
            } catch (Exception e) { return null; }
        }

        int[] margins() {
            int[] out = new int[UV_KEYS.length];
            for (int i = 0; i < UV_KEYS.length; i++) {
                Integer v = intValue(UV_KEYS[i]);
                if (v == null || v < -15 || v > 15) return null;
                out[i] = v;
            }
            return out;
        }

        String marginsText() {
            int[] m = margins();
            if (m == null) return "N/D";
            return String.format(Locale.US, "A55=%d%% A78=%d%% X1=%d%% GPU=%d%% MIF=%d%% DSU=%d%% INT=%d%%",
                    m[0], m[1], m[2], m[3], m[4], m[5], m[6]);
        }
    }

    static final class Accumulator {
        private static final String[] TEMP_KEYS = {"little_temp", "mid_temp", "big_temp", "g3d_temp", "mif_temp"};
        private static final String[] TEMP_NAMES = {"A55_LITTLE", "A78_MID", "X1_BIG", "GPU_G3D", "MIF_DDR5"};
        private static final String[] FREQ_KEYS = {"p0_cur", "p4_cur", "p7_cur", "gpu_clock", "mif_cur"};
        private static final String[] FREQ_NAMES = {"A55", "A78", "X1", "GPU", "MIF"};

        private final long intervalMs;
        private Snapshot first;
        private Snapshot last;
        private int samples;
        private final Map<String,Double> maxTemp = new LinkedHashMap<>();
        private final Map<String,Long> peakFreq = new LinkedHashMap<>();
        private String error = "";

        Accumulator(long intervalMs) { this.intervalMs = intervalMs; }

        synchronized void add(Snapshot s) {
            if (s == null) return;
            if (first == null) first = s;
            last = s;
            samples++;
            for (String k : TEMP_KEYS) {
                Double v = s.tempC(k);
                if (v != null) maxTemp.put(k, Math.max(maxTemp.getOrDefault(k, -999.0), v));
            }
            for (String k : FREQ_KEYS) {
                Long v = s.longValue(k);
                if (v != null) peakFreq.put(k, Math.max(peakFreq.getOrDefault(k, 0L), v));
            }
        }

        synchronized void setError(String e) {
            if (error.isEmpty() && e != null) error = e;
        }

        private static String fTemp(Double v) { return v == null ? "NA" : String.format(Locale.US, "%.1f", v); }
        private static String fLong(Long v) { return v == null ? "NA" : Long.toString(v); }

        synchronized String reportBlock() {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("=== KERNEL / THERMAL TELEMETRY ===\n");
            if (first == null || last == null) {
                sb.append("telemetry_status=UNAVAILABLE\n");
                if (!error.isEmpty()) sb.append("telemetry_error=").append(error.replace('\n', ' ')).append('\n');
                return sb.toString();
            }
            sb.append("telemetry_status=OK\n");
            sb.append("telemetry_samples=").append(samples).append('\n');
            sb.append("telemetry_interval_ms=").append(intervalMs).append('\n');
            sb.append("uv_start=").append(first.marginsText()).append('\n');
            sb.append("uv_end=").append(last.marginsText()).append('\n');
            sb.append("mif_temp_source=").append(last.get("mif_temp_source")).append('\n');
            sb.append("note_mif_temp=MIF/DDR5 temperature is reported only when firmware exposes a matching thermal zone; it is not assumed to be DRAM-die temperature.\n");

            for (int i = 0; i < TEMP_KEYS.length; i++) {
                String k = TEMP_KEYS[i];
                sb.append(TEMP_NAMES[i]).append("_temp_start_C=").append(fTemp(first.tempC(k))).append('\n');
                sb.append(TEMP_NAMES[i]).append("_temp_max_C=").append(fTemp(maxTemp.get(k))).append('\n');
                sb.append(TEMP_NAMES[i]).append("_temp_end_C=").append(fTemp(last.tempC(k))).append('\n');
            }
            for (int i = 0; i < FREQ_KEYS.length; i++) {
                String k = FREQ_KEYS[i];
                sb.append(FREQ_NAMES[i]).append("_freq_start_kHz=").append(fLong(first.longValue(k))).append('\n');
                sb.append(FREQ_NAMES[i]).append("_freq_peak_kHz=").append(fLong(peakFreq.get(k))).append('\n');
                sb.append(FREQ_NAMES[i]).append("_freq_end_kHz=").append(fLong(last.longValue(k))).append('\n');
            }
            sb.append("A55_max_limit_kHz=").append(first.get("p0_max")).append('\n');
            sb.append("A78_max_limit_kHz=").append(first.get("p4_max")).append('\n');
            sb.append("X1_max_limit_kHz=").append(first.get("p7_max")).append('\n');
            sb.append("MIF_min_limit_kHz=").append(first.get("mif_min")).append('\n');
            sb.append("MIF_max_limit_kHz=").append(first.get("mif_max")).append('\n');
            sb.append("GPU_max_lock_start=").append(first.get("gpu_max_lock")).append('\n');
            sb.append("GPU_min_lock_start=").append(first.get("gpu_min_lock")).append('\n');
            sb.append("battery_temp_start_C=").append(fTemp(first.tempC("batt_temp"))).append('\n');
            sb.append("battery_temp_end_C=").append(fTemp(last.tempC("batt_temp"))).append('\n');
            if (!error.isEmpty()) sb.append("telemetry_warning=").append(error.replace('\n', ' ')).append('\n');
            return sb.toString();
        }
    }
}
