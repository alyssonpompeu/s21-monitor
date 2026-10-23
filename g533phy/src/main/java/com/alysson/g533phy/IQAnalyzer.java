package com.alysson.g533phy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Offline baseband laboratory: no RF capture, no pairing and no device transmission.
 * Follows the approach of g533_phy_lab/g533_iq_lab.py (Welch, OFF/ON PSD and pi/4 phase hypothesis).
 */
public final class IQAnalyzer {
    public static final int FFT = 2048;
    public static final int MIN_SAMPLES = 4096;
    public static final int MAX_SAMPLES = 524288; // bounded memory on mobile
    private static final double EPS = 1e-25;
    private static final double[] PHASES = {Math.PI / 4, 3 * Math.PI / 4, -3 * Math.PI / 4, -Math.PI / 4};

    private IQAnalyzer() {}

    public enum Format {
        CF32LE(8), CS16LE(4), CU8(2);
        public final int stride;
        Format(int stride) { this.stride = stride; }
    }

    public static final class Samples {
        public final float[] re;
        public final float[] im;
        public final boolean limitReached;
        public Samples(float[] re, float[] im, boolean limitReached) {
            if (re.length != im.length) throw new IllegalArgumentException("I/Q desigual");
            this.re = re;
            this.im = im;
            this.limitReached = limitReached;
        }
        public int size() { return re.length; }
    }

    public static final class Demo {
        public final Samples off;
        public final Samples on;
        public final double sampleRate = 4_000_000;
        public final double symbolRate = 1_000_000;
        Demo(Samples off, Samples on) { this.off = off; this.on = on; }
    }

    public static final class Result {
        public final double[] deltaSpectrumDb;
        public final double centerMHz;
        public final double sampleRate;
        public final double powerChangeDb;
        public final double maxDeltaDb;
        public final double[] peakMHz;
        public final double[] peakDb;
        public final int offSamples;
        public final int onSamples;
        public final boolean truncated;
        public final String phase;
        Result(double[] d, double center, double rate, double p, double max,
               double[] pf, double[] pv, Samples off, Samples on, String phase) {
            this.deltaSpectrumDb = d;
            this.centerMHz = center;
            this.sampleRate = rate;
            this.powerChangeDb = p;
            this.maxDeltaDb = max;
            this.peakMHz = pf;
            this.peakDb = pv;
            this.offSamples = off.size();
            this.onSamples = on.size();
            this.truncated = off.limitReached || on.limitReached;
            this.phase = phase;
        }
        public String describe(boolean demo) {
            StringBuilder b = new StringBuilder();
            b.append(demo ? "DEMONSTRAÇÃO SINTÉTICA — NÃO É O G533\n"
                          : "CAPTURAS I/Q IMPORTADAS — ORIGEM NÃO VERIFICADA\n");
            b.append(String.format(Locale.US, "Centro: %.5f MHz | Fs: %.0f S/s\n", centerMHz, sampleRate));
            b.append(String.format(Locale.US, "OFF: %d amostras | ON: %d amostras\n", offSamples, onSamples));
            if (truncated) b.append("Amostras limitadas às primeiras 524288 por arquivo.\n");
            b.append(String.format(Locale.US, "Potência média ON/OFF: %+.2f dB\n", powerChangeDb));
            b.append(String.format(Locale.US, "Maior diferença espectral: %+.2f dB\n", maxDeltaDb));
            b.append("Picos de diferença (podem ser interferências):\n");
            for (int i = 0; i < peakMHz.length; i++) {
                b.append(String.format(Locale.US, "  %.5f MHz  %+.1f dB\n", peakMHz[i], peakDb[i]));
            }
            if (!phase.isEmpty()) b.append(phase);
            b.append("\nVERIFICAÇÃO: nenhum pacote G533 identificado, nenhum pareamento confirmado.\n");
            b.append("Wi-Fi/Bluetooth/ruído também podem elevar a energia. Usar mesmo ganho, ");
            b.append("centro, taxa, antena e controle ambiental entre OFF e ON.\n");
            return b.toString();
        }
    }

    /** Read complex interleaved I/Q from a SAF document using stream-only Android access. */
    public static Samples load(InputStream in, Format type) throws IOException {
        float[] re = new float[MAX_SAMPLES];
        float[] im = new float[MAX_SAMPLES];
        final int stride = type.stride;
        byte[] bytes = new byte[65536 + 8];
        int carry = 0;
        int count = 0;
        while (count < MAX_SAMPLES) {
            int got = in.read(bytes, carry, 65536);
            if (got < 0) break;
            if (got == 0) continue;
            int total = carry + got;
            int usable = total - (total % stride);
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int at = 0; at < usable && count < MAX_SAMPLES; at += stride) {
                float r, i;
                if (type == Format.CF32LE) {
                    r = data.getFloat(at);
                    i = data.getFloat(at + 4);
                } else if (type == Format.CS16LE) {
                    r = data.getShort(at) / 32768f;
                    i = data.getShort(at + 2) / 32768f;
                } else {
                    r = (((int) bytes[at] & 255) - 127.5f) / 127.5f;
                    i = (((int) bytes[at + 1] & 255) - 127.5f) / 127.5f;
                }
                if (!Float.isFinite(r) || !Float.isFinite(i))
                    throw new IOException("I/Q contém NaN ou infinito");
                re[count] = r;
                im[count] = i;
                count++;
            }
            carry = total - usable;
            if (carry != 0) System.arraycopy(bytes, usable, bytes, 0, carry);
        }
        if (count < MIN_SAMPLES)
            throw new IOException("Captura curta: mínimo 4096 amostras I/Q; lidas " + count);
        return new Samples(Arrays.copyOf(re, count), Arrays.copyOf(im, count), count >= MAX_SAMPLES);
    }

    /** Synthetic pi/4-DQPSK demonstration. This is not an Avnera/G533 signal. */
    public static Demo demo() {
        Random rng = new Random(42);
        final int sps = 4;
        final int symbols = 8192;
        final int n = sps * symbols;
        float[] offR = new float[n], offI = new float[n];
        float[] onR = new float[n], onI = new float[n];
        double phi = 0;
        for (int sym = 0; sym < symbols; sym++) {
            phi += PHASES[rng.nextInt(4)];
            double r = Math.cos(phi) * 0.45;
            double i = Math.sin(phi) * 0.45;
            for (int k = 0; k < sps; k++) {
                int pos = sym * sps + k;
                offR[pos] = (float) (rng.nextGaussian() * 0.13);
                offI[pos] = (float) (rng.nextGaussian() * 0.13);
                onR[pos] = (float) (r + rng.nextGaussian() * 0.13);
                onI[pos] = (float) (i + rng.nextGaussian() * 0.13);
            }
        }
        return new Demo(new Samples(offR, offI, false), new Samples(onR, onI, false));
    }

    public static Result compare(Samples off, Samples on, double sampleRate,
                                 double centerMHz, Double symbolRate) {
        if (!Double.isFinite(sampleRate) || sampleRate < 10000 || sampleRate > 100_000_000)
            throw new IllegalArgumentException("Taxa de amostragem inválida (Hz)");
        if (!Double.isFinite(centerMHz) || centerMHz < 2350 || centerMHz > 2550)
            throw new IllegalArgumentException("Centro em MHz fora da região 2,4 GHz");
        if (off.size() < MIN_SAMPLES || on.size() < MIN_SAMPLES)
            throw new IllegalArgumentException("OFF/ON precisam de 4096+ amostras");
        double[] p0 = welch(off, sampleRate);
        double[] p1 = welch(on, sampleRate);
        double[] delta = new double[FFT];
        final int middle = FFT / 2;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < FFT; i++) {
            delta[i] = 10 * Math.log10(Math.max(p1[i], EPS) / Math.max(p0[i], EPS));
            if (Math.abs(i - middle) >= 2) max = Math.max(max, delta[i]);
        }
        int[] indices = new int[6];
        Arrays.fill(indices, -1);
        double[] peaks = new double[6];
        Arrays.fill(peaks, -Double.MAX_VALUE);
        for (int i = 0; i < FFT; i++) {
            if (Math.abs(i - middle) < 2) continue;
            int slot = -1;
            for (int j = 0; j < indices.length; j++) {
                if (delta[i] > peaks[j]) { slot = j; break; }
            }
            if (slot < 0) continue;
            // Reject neighboring FFT bins of an already selected peak.
            boolean near = false;
            for (int existing : indices)
                if (existing >= 0 && Math.abs(existing - i) < 4) { near = true; break; }
            if (near) continue;
            for (int j = indices.length - 1; j > slot; j--) {
                indices[j] = indices[j - 1];
                peaks[j] = peaks[j - 1];
            }
            indices[slot] = i;
            peaks[slot] = delta[i];
        }
        int count = 0;
        for (int idx : indices) if (idx >= 0) count++;
        double[] peakMHz = new double[count], peakDb = new double[count];
        for (int j = 0; j < count; j++) {
            peakMHz[j] = centerMHz + (indices[j] - middle) * sampleRate / FFT / 1e6;
            peakDb[j] = peaks[j];
        }
        String phase = "";
        if (symbolRate != null) {
            if (!Double.isFinite(symbolRate) || symbolRate <= 0)
                throw new IllegalArgumentException("Taxa de símbolos inválida");
            double sps = sampleRate / symbolRate;
            if (sps < 2 || sps > 2048 || Math.abs(sps - Math.rint(sps)) > 0.001) {
                phase = "Hipótese de fase: sps não inteiro em 2..2048; reamostragem necessária.\n";
            } else {
                phase = phaseHypothesis(on, (int) Math.rint(sps));
            }
        }
        double ratio = 10 * Math.log10((meanPower(on) + EPS) / (meanPower(off) + EPS));
        return new Result(delta, centerMHz, sampleRate, ratio, max,
                          peakMHz, peakDb, off, on, phase);
    }

    private static double meanPower(Samples s) {
        double total = 0;
        for (int n = 0; n < s.size(); n++)
            total += s.re[n] * (double) s.re[n] + s.im[n] * (double) s.im[n];
        return total / s.size();
    }

    private static double[] welch(Samples s, double sampleRate) {
        double[] psd = new double[FFT];
        double[] window = new double[FFT];
        double norm = 0;
        for (int k = 0; k < FFT; k++) {
            window[k] = 0.5 - 0.5 * Math.cos(2 * Math.PI * k / (FFT - 1));
            norm += window[k] * window[k];
        }
        int frames = 0;
        double[] r = new double[FFT], i = new double[FFT];
        for (int start = 0; start + FFT <= s.size(); start += FFT / 2) {
            double avgR = 0, avgI = 0;
            for (int k = 0; k < FFT; k++) {
                avgR += s.re[start + k];
                avgI += s.im[start + k];
            }
            avgR /= FFT;
            avgI /= FFT;
            for (int k = 0; k < FFT; k++) {
                r[k] = (s.re[start + k] - avgR) * window[k];
                i[k] = (s.im[start + k] - avgI) * window[k];
            }
            fft(r, i);
            for (int k = 0; k < FFT; k++) {
                int shifted = (k + FFT / 2) % FFT;
                psd[shifted] += r[k] * r[k] + i[k] * i[k];
            }
            frames++;
        }
        for (int k = 0; k < FFT; k++)
            psd[k] /= frames * norm * sampleRate;
        return psd;
    }

    /** In-place radix-2 FFT, no platform-specific native libs. */
    private static void fft(double[] re, double[] im) {
        final int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr0 = Math.cos(angle), wi0 = Math.sin(angle);
            for (int pos = 0; pos < n; pos += len) {
                double wr = 1, wi = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = pos + k, b = a + len / 2;
                    double vr = re[b] * wr - im[b] * wi;
                    double vi = re[b] * wi + im[b] * wr;
                    double ur = re[a], ui = im[a];
                    re[a] = ur + vr; im[a] = ui + vi;
                    re[b] = ur - vr; im[b] = ui - vi;
                    double next = wr * wr0 - wi * wi0;
                    wi = wr * wi0 + wi * wr0;
                    wr = next;
                }
            }
        }
    }

    private static String phaseHypothesis(Samples s, int sps) {
        double bestMse = Double.POSITIVE_INFINITY;
        int bestOffset = 0, bestCount = 0;
        int[] bestHist = new int[4];
        for (int offset = 0; offset < sps; offset++) {
            double squared = 0;
            int valid = 0;
            int[] hist = new int[4];
            for (int at = offset + sps / 2 + sps; at < s.size(); at += sps) {
                int prev = at - sps;
                double ampA = Math.hypot(s.re[at], s.im[at]);
                double ampB = Math.hypot(s.re[prev], s.im[prev]);
                if (ampA < 1e-4 || ampB < 1e-4) continue;
                double phase = Math.atan2(s.im[at] * s.re[prev] - s.re[at] * s.im[prev],
                                          s.re[at] * s.re[prev] + s.im[at] * s.im[prev]);
                double nearest = Double.POSITIVE_INFINITY;
                int bin = 0;
                for (int j = 0; j < PHASES.length; j++) {
                    double error = Math.atan2(Math.sin(phase - PHASES[j]), Math.cos(phase - PHASES[j]));
                    double e2 = error * error;
                    if (e2 < nearest) { nearest = e2; bin = j; }
                }
                squared += nearest;
                hist[bin]++;
                valid++;
            }
            if (valid >= 40 && squared / valid < bestMse) {
                bestMse = squared / valid;
                bestCount = valid;
                bestOffset = offset;
                bestHist = hist;
            }
        }
        if (bestCount == 0) return "Hipótese de fase: símbolos insuficientes.\n";
        return String.format(Locale.US,
                "Hipótese π/4-DQPSK (NÃO confirma Avnera):\n" +
                "  erro médio fase²: %.5f rad² | sps: %d | offset: %d\n" +
                "  eventos: %d | histograma: [%d,%d,%d,%d]\n",
                bestMse, sps, bestOffset, bestCount,
                bestHist[0], bestHist[1], bestHist[2], bestHist[3]);
    }
}
