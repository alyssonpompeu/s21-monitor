package com.alysson.bcm4375lab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ExportUtil {
    static long copyRootFile(String source, File dest) throws Exception {
        Process p = new ProcessBuilder("su", "-c", "cat '" + source.replace("'", "'\\''") + "' 2>/dev/null").start();
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroy();
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            dest.delete();
            return -1;
        }
        if (p.exitValue() != 0) {
            dest.delete();
            return -1;
        }

        long total = 0;
        try (InputStream in = p.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        return total;
    }

    static void zip(List<File> files, File dest) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[65536];
            for (File f : files) {
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : md.digest()) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private ExportUtil() {}
}
