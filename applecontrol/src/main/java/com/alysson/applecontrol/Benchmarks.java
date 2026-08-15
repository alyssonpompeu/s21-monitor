package com.alysson.applecontrol;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class Benchmarks {
    private Benchmarks() {}

    static double cpuSingleMops(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        long ops = 0;
        long x = 0x1234abcd5678ef01L;
        double f = 1.000001;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 10000; i++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                f = f * 1.000000119 + ((x & 0xffff) * 1e-9);
                if (f > 1000.0) f *= 0.001;
            }
            ops += 10000L * 7L;
        }
        if (f == 42.123) throw new AssertionError();
        return ops / (ms / 1000.0) / 1_000_000.0;
    }

    static double cpuMultiMops(long ms, int threads) throws InterruptedException {
        long deadline = System.nanoTime() + ms * 1_000_000L;
        AtomicLong total = new AtomicLong();
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                long ops = 0;
                long x = 0x9e3779b97f4a7c15L ^ id;
                double f = 1.000001 + id * 1e-6;
                while (System.nanoTime() < deadline) {
                    for (int i = 0; i < 5000; i++) {
                        x ^= x << 13;
                        x ^= x >>> 7;
                        x ^= x << 17;
                        f = f * 1.000000119 + ((x & 0xffff) * 1e-9);
                        if (f > 1000.0) f *= 0.001;
                    }
                    ops += 5000L * 7L;
                }
                if (f == 42.123) throw new AssertionError();
                total.addAndGet(ops);
                done.countDown();
            }, "S21LabCPU-" + t).start();
        }
        done.await();
        return total.get() / (ms / 1000.0) / 1_000_000.0;
    }

    static double memoryBandwidthMBs(long ms) {
        final int size = 64 * 1024 * 1024;
        byte[] src = new byte[size];
        byte[] dst = new byte[size];
        for (int i = 0; i < size; i += 4096) src[i] = (byte)i;
        long end = System.nanoTime() + ms * 1_000_000L;
        long bytes = 0;
        while (System.nanoTime() < end) {
            System.arraycopy(src, 0, dst, 0, size);
            byte[] tmp = src; src = dst; dst = tmp;
            bytes += size;
        }
        return bytes / (ms / 1000.0) / (1024.0 * 1024.0);
    }

    static double memoryLatencyMops(long ms) {
        final int n = 4 * 1024 * 1024;
        int[] a = new int[n];
        int mask = n - 1;
        for (int i = 0; i < n; i++) a[i] = (i * 1103515245 + 12345) & mask;
        long end = System.nanoTime() + ms * 1_000_000L;
        int p = 1;
        long ops = 0;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 10000; i++) p = a[p];
            ops += 10000;
        }
        if (p == -1) throw new AssertionError();
        return ops / (ms / 1000.0) / 1_000_000.0;
    }

    static StorageResult storage(File dir, int megabytes) throws IOException {
        File f = new File(dir, "s21lab_io.tmp");
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < block.length; i += 4096) block[i] = (byte)(i ^ 0x5a);

        long t0 = System.nanoTime();
        try (FileOutputStream out = new FileOutputStream(f)) {
            for (int i = 0; i < megabytes; i++) out.write(block);
            out.getFD().sync();
        }
        long t1 = System.nanoTime();

        long read = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            while (true) {
                int n = in.read(block);
                if (n < 0) break;
                read += n;
            }
        }
        long t2 = System.nanoTime();
        f.delete();

        double write = megabytes / ((t1 - t0) / 1e9);
        double readMB = (read / (1024.0 * 1024.0)) / ((t2 - t1) / 1e9);
        return new StorageResult(write, readMB);
    }

    static final class StorageResult {
        final double writeMBs;
        final double readMBs;
        StorageResult(double w, double r) { writeMBs = w; readMBs = r; }
    }
}
