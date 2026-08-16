package com.alysson.kernelbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Analytics {
    private Analytics() {}

    static String compare(RunRecord cur, List<RunRecord> all) {
        if (cur == null) return "Execute um FULL para gerar comparação.";
        RunRecord v233 = byId(all, "hist-v233");
        RunRecord v11 = byId(all, "hist-h11");

        StringBuilder sb = new StringBuilder();
        sb.append("CRUZAMENTO DO RUN ATUAL\n");
        sb.append("• ").append(cur.label).append("\n\n");

        if (v233 != null) {
            sb.append("vs v2.3.3 baseline\n");
            appendDelta(sb, "TOTAL", cur.total, v233.total);
            appendDelta(sb, "CPU multi", cur.multi, v233.multi);
            appendDelta(sb, "CPU soak", cur.cpuSoak, v233.cpuSoak);
            appendDelta(sb, "GPU burst", cur.gpuBurst, v233.gpuBurst);
            appendDelta(sb, "GPU soak", cur.gpuSoak, v233.gpuSoak);
            appendDelta(sb, "RAM copy", cur.ramCopy, v233.ramCopy);
            sb.append('\n');
        }

        if (v11 != null) {
            sb.append("vs Hybrid v1.1 sustained\n");
            appendDelta(sb, "TOTAL", cur.total, v11.total);
            appendDelta(sb, "CPU multi", cur.multi, v11.multi);
            appendDelta(sb, "CPU soak", cur.cpuSoak, v11.cpuSoak);
            appendDelta(sb, "GPU burst", cur.gpuBurst, v11.gpuBurst);
            appendDelta(sb, "GPU soak", cur.gpuSoak, v11.gpuSoak);
            appendDelta(sb, "RAM copy", cur.ramCopy, v11.ramCopy);
            sb.append('\n');
        }

        RunRecord bestGpuBurst = best(all, 1);
        RunRecord bestGpuSoak = best(all, 2);
        RunRecord bestCpuMulti = best(all, 3);
        RunRecord bestCpuSoak = best(all, 4);
        RunRecord bestRam = best(all, 5);

        sb.append("melhores resultados registrados\n");
        appendBest(sb, "GPU burst", bestGpuBurst, bestGpuBurst == null ? 0 : bestGpuBurst.gpuBurst, "draws/s");
        appendBest(sb, "GPU soak", bestGpuSoak, bestGpuSoak == null ? 0 : bestGpuSoak.gpuSoak, "draws/s");
        appendBest(sb, "CPU multi", bestCpuMulti, bestCpuMulti == null ? 0 : bestCpuMulti.multi, "Mops");
        appendBest(sb, "CPU soak", bestCpuSoak, bestCpuSoak == null ? 0 : bestCpuSoak.cpuSoak, "Mops");
        appendBest(sb, "RAM copy", bestRam, bestRam == null ? 0 : bestRam.ramCopy, "MB/s");

        sb.append("\nrelações históricas (correlação, não causalidade)\n");
        sb.append(String.format(Locale.US, "• GPU burst ↔ GPU soak: r=%+.3f\n", correlation(all, 1)));
        sb.append(String.format(Locale.US, "• CPU multi ↔ CPU soak: r=%+.3f\n", correlation(all, 2)));
        sb.append(String.format(Locale.US, "• RAM copy ↔ GPU soak: r=%+.3f\n", correlation(all, 3)));

        if (cur.gpuBurst > 0 && cur.gpuSoak > 0) {
            sb.append(String.format(Locale.US, "\nrun atual: GPU soak/burst = %.1f%%\n",
                    cur.gpuSoak / cur.gpuBurst * 100.0));
        }
        if (cur.multi > 0 && cur.cpuSoak > 0) {
            sb.append(String.format(Locale.US, "run atual: CPU soak/multi = %.1f%%\n",
                    cur.cpuSoak / cur.multi * 100.0));
        }
        return sb.toString();
    }

    static String historicalSummary(List<RunRecord> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("Base histórica incorporada: ").append(all.size()).append(" runs.\n");
        sb.append("Os arquivos sem mapeamento seguro de kernel permanecem identificados pelo timestamp/arquivo.\n");
        RunRecord v11 = byId(all, "hist-h11");
        RunRecord v12 = byId(all, "hist-h12");
        if (v11 != null && v12 != null) {
            sb.append(String.format(Locale.US,
                    "Hybrid v1.1 → GPU soak %.3f / CPU soak %.3f / RAM %.2f MB/s\n",
                    v11.gpuSoak, v11.cpuSoak, v11.ramCopy));
            sb.append(String.format(Locale.US,
                    "Hybrid v1.2 → GPU burst %.3f / GPU soak %.3f / CPU soak %.3f\n",
                    v12.gpuBurst, v12.gpuSoak, v12.cpuSoak));
        }
        return sb.toString();
    }

    private static void appendDelta(StringBuilder sb, String name, double current, double base) {
        if (current <= 0 || base <= 0) return;
        double d = (current / base - 1.0) * 100.0;
        sb.append(String.format(Locale.US, "• %-10s %+.2f%%\n", name, d));
    }

    private static void appendBest(StringBuilder sb, String name, RunRecord r, double value, String unit) {
        if (r == null || value <= 0) return;
        sb.append(String.format(Locale.US, "• %s: %.3f %s — %s\n", name, value, unit, r.label));
    }

    private static RunRecord byId(List<RunRecord> all, String id) {
        for (RunRecord r : all) if (id.equals(r.id)) return r;
        return null;
    }

    private static RunRecord best(List<RunRecord> all, int metric) {
        RunRecord best = null;
        double max = -1;
        for (RunRecord r : all) {
            if (!r.isFull()) continue;
            double v;
            switch (metric) {
                case 1: v = r.gpuBurst; break;
                case 2: v = r.gpuSoak; break;
                case 3: v = r.multi; break;
                case 4: v = r.cpuSoak; break;
                case 5: v = r.ramCopy; break;
                default: v = 0;
            }
            if (v > max) { max = v; best = r; }
        }
        return best;
    }

    private static double correlation(List<RunRecord> all, int pair) {
        ArrayList<Double> xs = new ArrayList<>();
        ArrayList<Double> ys = new ArrayList<>();
        for (RunRecord r : all) {
            if (!r.isFull()) continue;
            double x, y;
            if (pair == 1) { x = r.gpuBurst; y = r.gpuSoak; }
            else if (pair == 2) { x = r.multi; y = r.cpuSoak; }
            else { x = r.ramCopy; y = r.gpuSoak; }
            if (x > 0 && y > 0) { xs.add(x); ys.add(y); }
        }
        int n = xs.size();
        if (n < 3) return 0;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += xs.get(i); my += ys.get(i); }
        mx /= n; my /= n;
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double a = xs.get(i) - mx;
            double b = ys.get(i) - my;
            num += a*b; dx += a*a; dy += b*b;
        }
        double den = Math.sqrt(dx*dy);
        return den > 0 ? num/den : 0;
    }
}
