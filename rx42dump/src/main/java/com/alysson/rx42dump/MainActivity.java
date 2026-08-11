package com.alysson.rx42dump;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int SAVE_ZIP = 42;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView outputView;
    private Button dumpButton;
    private Button saveButton;
    private File zipFile;
    private volatile boolean running;

    private static final String[] SOURCES = new String[] {
            "/vendor/firmware/bcmdhd_sta.bin_b1",
            "/vendor/firmware/bcmdhd_mon.bin_b1",
            "/vendor/firmware/bcmdhd_mfg.bin_b1",
            "/vendor/firmware/bcmdhd_clm.blob",
            "/vendor/etc/init/wifi_brcm.rc",
            "/vendor/etc/wlan_vendor_rc",
            "/vendor/etc/wlan_common_rc",
            "/vendor/etc/init/android.hardware.wifi@1.0-service.rc",
            "/vendor/etc/init/vendor.samsung.hardware.wifi@2.0-service.rc"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text("RX42 Firmware Dump", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());

        TextView subtitle = text("BCM4375B1 • firmware 18.41.117 • somente leitura", 12, 0xFF80CBC4, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, match());

        TextView device = text(
                "Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
                        "\nDevice: " + Build.DEVICE + " • Hardware: " + Build.HARDWARE +
                        "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                12, 0xFFCFD8DC, false);
        device.setPadding(dp(12), dp(10), dp(12), dp(10));
        device.setBackgroundColor(0xFF172027);
        root.addView(device, matchMargin(0, 0, 0, 10));

        statusView = text("Pronto para extrair uma cópia dos arquivos Broadcom.", 14, 0xFFFFD180, true);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusView.setBackgroundColor(0xFF332A16);
        root.addView(statusView, matchMargin(0, 0, 0, 10));

        dumpButton = button("GERAR PACOTE DE FIRMWARE");
        dumpButton.setOnClickListener(v -> generateDump());
        root.addView(dumpButton, matchMargin(0, 0, 0, 7));

        saveButton = button("SALVAR ZIP");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveZip());
        root.addView(saveButton, matchMargin(0, 0, 0, 12));

        TextView note = text(
                "O app não grava em /vendor, não troca firmware e não reinicia Wi‑Fi/Bluetooth. Ele apenas lê os arquivos via root, calcula SHA‑256 e cria um ZIP local para análise.",
                12, 0xFFB0BEC5, false);
        note.setPadding(dp(10), dp(10), dp(10), dp(10));
        note.setBackgroundColor(0xFF11181D);
        root.addView(note, matchMargin(0, 0, 0, 10));

        outputView = text("Nenhum pacote gerado ainda.", 11, 0xFFE0E0E0, false);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(dp(10), dp(10), dp(10), dp(18));
        outputView.setBackgroundColor(0xFF0D1216);
        root.addView(outputView, match());
        return scroll;
    }

    private void generateDump() {
        if (running) return;
        running = true;
        dumpButton.setEnabled(false);
        saveButton.setEnabled(false);
        statusView.setTextColor(0xFFFFD180);
        statusView.setText("Solicitando root e lendo os arquivos…");
        outputView.setText("Iniciando extração somente leitura…");

        executor.execute(() -> {
            TextResult root = runSuText("id; getprop vendor.wlan.firmware.version; getprop vendor.bluetooth_fw_ver");
            if (root.code != 0 || !root.output.contains("uid=0")) {
                main.post(() -> {
                    statusView.setTextColor(0xFFEF9A9A);
                    statusView.setText("ROOT NEGADO / INDISPONÍVEL");
                    outputView.setText(root.output);
                    dumpButton.setEnabled(true);
                    running = false;
                });
                return;
            }

            try {
                File work = new File(getCacheDir(), "rx42-fw-dump");
                deleteRecursive(work);
                if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Não foi possível criar diretório temporário");

                List<File> copied = new ArrayList<>();
                StringBuilder report = new StringBuilder();
                report.append("RX42 Firmware Dump v1.0\n");
                report.append("Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                report.append("Device: ").append(Build.DEVICE).append('\n');
                report.append("Hardware: ").append(Build.HARDWARE).append('\n');
                report.append("Android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
                report.append("Root: ").append(firstLine(root.output)).append("\n\n");

                TextResult props = runSuText(
                        "echo vendor.wlan.firmware.version=$(getprop vendor.wlan.firmware.version); " +
                        "echo vendor.wlan.driver.version=$(getprop vendor.wlan.driver.version); " +
                        "echo vendor.bluetooth_fw_ver=\"$(getprop vendor.bluetooth_fw_ver)\"; " +
                        "echo ro.build.fingerprint=\"$(getprop ro.build.fingerprint)\"; " +
                        "uname -a"
                );
                report.append("=== PROPRIEDADES ===\n").append(props.output).append('\n');
                report.append("=== ARQUIVOS ===\n");

                for (String source : SOURCES) {
                    if (!rootFileExists(source)) {
                        report.append("MISSING  ").append(source).append('\n');
                        continue;
                    }
                    File dest = new File(work, new File(source).getName());
                    long bytes = copyRootBinary(source, dest);
                    if (bytes < 0) {
                        report.append("ERROR    ").append(source).append('\n');
                        continue;
                    }
                    copied.add(dest);
                    report.append(String.format(Locale.US, "%-8d %s  %s\n", bytes, sha256(dest), source));
                }

                report.append("\n=== REFERÊNCIAS EM CONFIGURAÇÃO ===\n");
                for (File f : copied) {
                    if (!f.getName().endsWith(".rc") && !f.getName().contains("wlan_")) continue;
                    report.append("-- ").append(f.getName()).append(" --\n");
                    appendInterestingLines(f, report);
                }

                File metadata = new File(work, "metadata.txt");
                try (FileOutputStream fos = new FileOutputStream(metadata)) {
                    fos.write(report.toString().getBytes(StandardCharsets.UTF_8));
                }
                copied.add(metadata);

                File outZip = new File(getCacheDir(), "RX42-S21-BCM4375B1-firmware-dump.zip");
                if (outZip.exists()) outZip.delete();
                createZip(copied, outZip);
                zipFile = outZip;

                String finalReport = report + "\nZIP: " + outZip.getName() +
                        "\nZIP bytes: " + outZip.length() +
                        "\nZIP SHA-256: " + sha256(outZip) +
                        "\n\nPacote pronto. Toque em SALVAR ZIP e depois envie esse arquivo aqui.";

                main.post(() -> {
                    statusView.setTextColor(0xFF81C784);
                    statusView.setText("PACOTE PRONTO • somente leitura\n" + outZip.getName());
                    outputView.setText(finalReport);
                    saveButton.setEnabled(true);
                    dumpButton.setEnabled(true);
                    running = false;
                    Toast.makeText(this, "Pacote de firmware criado.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    statusView.setTextColor(0xFFEF9A9A);
                    statusView.setText("Falha ao gerar pacote.");
                    outputView.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
                    dumpButton.setEnabled(true);
                    running = false;
                });
            }
        });
    }

    private boolean rootFileExists(String path) {
        TextResult r = runSuText("test -f " + sh(path) + " && echo YES || echo NO");
        return r.output.contains("YES");
    }

    private long copyRootBinary(String source, File dest) throws Exception {
        Process p = new ProcessBuilder("su", "-c", "cat " + sh(source) + " 2>/dev/null").start();
        long total = 0;
        try (InputStream in = p.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            dest.delete();
            return -1;
        }
        return total;
    }

    private void appendInterestingLines(File file, StringBuilder report) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 200) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("bcmdhd") || l.contains("firmware") || l.contains("wlan") || l.contains("wifi") || l.contains("monitor") || l.contains("mfg")) {
                    report.append(line).append('\n');
                    count++;
                }
            }
        } catch (Exception e) {
            report.append("[erro lendo texto: ").append(e.getMessage()).append("]\n");
        }
    }

    private void createZip(List<File> files, File dest) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[64 * 1024];
            for (File f : files) {
                ZipEntry entry = new ZipEntry(f.getName());
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    private String sha256(File file) throws Exception {
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

    private TextResult runSuText(String script) {
        Process p = null;
        StringBuilder out = new StringBuilder();
        int code = -1;
        try {
            p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            code = p.waitFor();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            if (p != null) p.destroy();
        }
        return new TextResult(code, out.toString());
    }

    private String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String firstLine(String s) {
        int n = s.indexOf('\n');
        return n >= 0 ? s.substring(0, n) : s;
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(i, SAVE_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        executor.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Destino indisponível");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                main.post(() -> Toast.makeText(this, "ZIP salvo. Agora envie o arquivo aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = match();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private static class TextResult {
        final int code;
        final String output;
        TextResult(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }
}
