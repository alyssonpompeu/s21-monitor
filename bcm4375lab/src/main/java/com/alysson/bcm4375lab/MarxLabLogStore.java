package com.alysson.bcm4375lab;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent local transcript shared by every MARX LAB screen. */
final class MarxLabLogStore {
    private static final String FILE_NAME = "marx_lab_v11_session.log";
    private static final Object LOCK = new Object();

    static void append(Context context, String section, String text) {
        synchronized (LOCK) {
            try {
                File f = file(context);
                boolean exists = f.isFile() && f.length() > 0;
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
                    StringBuilder b = new StringBuilder();
                    if (!exists) {
                        b.append("MARX LAB V1.1 — LOG ACUMULADO\n");
                        b.append("Cada bloco abaixo corresponde a um teste executado no aparelho.\n");
                    }
                    b.append("\n\n============================================================\n");
                    b.append("SECTION=").append(section).append('\n');
                    b.append("TIMESTAMP=").append(stamp).append('\n');
                    b.append("============================================================\n");
                    b.append(text == null ? "<null>" : text);
                    if (text == null || !text.endsWith("\n")) b.append('\n');
                    out.write(b.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        }
    }

    static String readAll(Context context) {
        synchronized (LOCK) {
            File f = file(context);
            if (!f.isFile()) return "Nenhum teste salvo ainda.";
            try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return "LOG_READ_ERROR=" + e + "\n";
            }
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File f = file(context);
            if (f.exists()) f.delete();
        }
    }

    static long size(Context context) {
        synchronized (LOCK) {
            File f = file(context);
            return f.isFile() ? f.length() : 0L;
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private MarxLabLogStore() {}
}
