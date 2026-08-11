package com.alysson.bcm4375lab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int SAVE_ZIP = 4375;
    private static final String EXPECTED_STA_SHA = "1676f46ce56b96f58dc70de08beaab4ab3362ee6dd751465a8d6a0023c3c54ad";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView checks;
    private TextView output;
    private Button analyze;
    private Button save;
    private File zipFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(7, 10, 13));
        scroll.addView(root);

        TextView title = text("BCM4375 Lab", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("v1.1 • S21 • BCM4375B1 • root somente leitura", 12, 0xFF80CBC4, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        TextView device = text("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
                "\nDevice: " + Build.DEVICE + " • Hardware: " + Build.HARDWARE +
                "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT, 12, 0xFFCFD8DC, false);
        device.setPadding(dp(10), dp(10), dp(10), dp(10));
        device.setBackgroundColor(0xFF172027);
        root.addView(device);

        status = text("Pronto. Esta versão não altera o Wi-Fi.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(12));
        root.addView(status);

        analyze = new Button(this);
        analyze.setText("ANALISAR BCM4375 E GERAR ZIP");
        analyze.setOnClickListener(this::runAnalysis);
        root.addView(analyze);

        save = new Button(this);
        save.setText("SALVAR PACOTE ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        Button active = new Button(this);
        active.setText("MODO ATIVO BLOQUEADO NA V1.1");
        active.setEnabled(false);
        root.addView(active);

        checks = text("Pré-verificações ainda não executadas.", 12, 0xFFB0BEC5, false);
        checks.setTypeface(Typeface.MONOSPACE);
        checks.setPadding(0, dp(14), 0, dp(12));
        root.addView(checks);

        TextView note = text("Cada consulta tem timeout. Se uma etapa falhar ou travar, o app registra TIMEOUT e continua. Nenhuma escrita em firmware_path, nenhum restart de wlan0 e nenhuma troca de firmware.", 12, 0xFFB0BEC5, false);
        note.setPadding(0, 0, 0, dp(12));
        root.addView(note);

        output = text("Toque em ANALISAR BCM4375 E GERAR ZIP.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private void runAnalysis(View ignored) {
        analyze.setEnabled(false);
        save.setEnabled(false);
        status.setTextColor(0xFFFFD180);
        status.setText("Etapa 0 • aguardando autorização root…");
        checks.setText("Executando verificações…");
        output.setText("Se o Magisk solicitar permissão, autorize BCM4375 Lab.\nNenhuma escrita será feita.");

        worker.execute(() -> {
            try {
                RootReader.Result root = RootReader.run("id", 30);
                if (root.timedOut) {
                    showFailure("ROOT: TIMEOUT", "O Magisk não respondeu em 30 s. Abra o Magisk, confirme a permissão do BCM4375 Lab e tente novamente.\n\n" + root.output);
                    return;
                }
                if (root.code != 0 || !root.output.contains("uid=0")) {
                    showFailure("ROOT NEGADO / INDISPONÍVEL", root.output);
                    return;
                }

                StringBuilder report = new StringBuilder();
                report.append("BCM4375 Lab v1.1.0\n")
                        .append("Model: ").append(Build.MODEL).append('\n')
                        .append("Device: ").append(Build.DEVICE).append('\n')
                        .append("Hardware: ").append(Build.HARDWARE).append("\n\n");

                int total = ProbeCatalog.ALL.length;
                for (int i = 0; i < total; i++) {
                    ProbeCatalog.Probe probe = ProbeCatalog.ALL[i];
                    postProgress(i + 1, total, probe.label);
                    RootReader.Result r = RootReader.run(probe.command, 8);
                    report.append("=== ").append(probe.label).append(" ===\n")
                            .append(r.output)
                            .append(r.timedOut ? "[timeout=YES]\n" : "")
                            .append("[exit=").append(r.code).append("]\n\n");
                }

                postStatus("Validando firmware e loaders…");
                String wifiver = RootReader.run("cat /sys/wifi/wifiver 2>/dev/null", 5).output;
                String hashes = RootReader.run("sha256sum /vendor/firmware/bcmdhd_sta.bin_b1 /vendor/firmware/bcmdhd_mon.bin_b1 /vendor/firmware/bcmdhd_mfg.bin_b1 2>/dev/null", 8).output;
                String mfgStrings = RootReader.run("/system/bin/strings /vendor/bin/hw/mfgloader 2>/dev/null | grep -iE 'firmware_path|bcmdhd_mon.bin|bcmdhd_mfg.bin'", 5).output;

                boolean modelOk = "SM-G991B".equalsIgnoreCase(Build.MODEL);
                boolean hwOk = "exynos2100".equalsIgnoreCase(Build.HARDWARE);
                boolean fwOk = wifiver.contains("18.41.117") && wifiver.contains("B1 Network");
                boolean staOk = hashes.contains(EXPECTED_STA_SHA);
                boolean monOk = RootReader.run("test -f /vendor/firmware/bcmdhd_mon.bin_b1", 3).code == 0;
                boolean mfgOk = RootReader.run("test -f /vendor/firmware/bcmdhd_mfg.bin_b1", 3).code == 0;
                boolean pathOk = RootReader.run("test -w /sys/module/dhd/parameters/firmware_path", 3).code == 0;
                boolean loadersOk = RootReader.run("test -x /vendor/bin/hw/macloader && test -x /vendor/bin/hw/mfgloader", 3).code == 0;
                boolean evidenceOk = mfgStrings.contains("firmware_path") && mfgStrings.contains("bcmdhd_mon.bin") && mfgStrings.contains("bcmdhd_mfg.bin");

                String summary = check("Modelo SM-G991B", modelOk) +
                        check("Hardware Exynos 2100", hwOk) +
                        check("Firmware ativo 18.41.117 B1 Network", fwOk) +
                        check("SHA-256 do STA conhecido", staOk) +
                        check("Firmware MON presente", monOk) +
                        check("Firmware MFG presente", mfgOk) +
                        check("firmware_path gravável", pathOk) +
                        check("macloader/mfgloader executáveis", loadersOk) +
                        check("mfgloader referencia MON/MFG", evidenceOk);
                report.append("=== PREFLIGHT ===\n").append(summary);
                report.append("ACTIVE_MODE=LOCKED_READ_ONLY_V1_1\n");

                postStatus("Criando pacote de análise…");
                File work = new File(getCacheDir(), "bcm4375lab");
                ExportUtil.deleteRecursive(work);
                if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Falha criando cache de trabalho");
                List<File> files = new ArrayList<>();

                File reportFile = new File(work, "bcm4375-lab-report.txt");
                try (FileOutputStream out = new FileOutputStream(reportFile)) {
                    out.write(report.toString().getBytes(StandardCharsets.UTF_8));
                }
                files.add(reportFile);

                exportRootFile(files, work, "/vendor/bin/hw/macloader", "macloader");
                exportRootFile(files, work, "/vendor/bin/hw/mfgloader", "mfgloader");
                exportRootFile(files, work, "/vendor/etc/init/wifi_brcm.rc", "wifi_brcm.rc");
                exportRootFile(files, work, "/vendor/etc/wlan_vendor_rc", "wlan_vendor_rc");
                exportRootFile(files, work, "/vendor/etc/wlan_common_rc", "wlan_common_rc");

                File readme = new File(work, "README-FIRST.txt");
                try (FileOutputStream out = new FileOutputStream(readme)) {
                    out.write(("BCM4375 Lab v1.1.0\nSomente leitura. Nenhuma troca de firmware foi executada.\nEnvie este ZIP para análise dos loaders antes de habilitar modo ativo.\n").getBytes(StandardCharsets.UTF_8));
                }
                files.add(readme);

                File resultZip = new File(getCacheDir(), "BCM4375-Lab-S21-analysis.zip");
                if (resultZip.exists()) resultZip.delete();
                ExportUtil.zip(files, resultZip);
                zipFile = resultZip;

                boolean allBaseOk = modelOk && hwOk && fwOk && staOk && monOk && mfgOk && pathOk && loadersOk && evidenceOk;
                String finalText = report + "\nZIP=" + resultZip.getName() +
                        "\nZIP_SHA256=" + ExportUtil.sha256(resultZip) +
                        "\n\nSalve o ZIP e envie o arquivo aqui.";
                ui.post(() -> {
                    checks.setText(summary);
                    status.setTextColor(allBaseOk ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(allBaseOk ? "BASE VALIDADA • ZIP PRONTO • modo ativo ainda bloqueado" : "ZIP PRONTO • há verificações que não bateram");
                    output.setText(finalText);
                    analyze.setEnabled(true);
                    save.setEnabled(true);
                    Toast.makeText(this, "Análise concluída.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                showFailure("Falha na análise", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private void postProgress(int current, int total, String label) {
        ui.post(() -> {
            status.setText("Etapa " + current + "/" + total + " • " + label);
            output.setText("Executando: " + label + "\nTimeout máximo desta consulta: 8 s.\nNenhuma escrita será feita.");
        });
    }

    private void postStatus(String value) {
        ui.post(() -> status.setText(value));
    }

    private void exportRootFile(List<File> files, File work, String source, String name) throws Exception {
        if (RootReader.run("test -f '" + source + "'", 3).code != 0) return;
        File dest = new File(work, name);
        if (ExportUtil.copyRootFile(source, dest) >= 0) files.add(dest);
    }

    private String check(String label, boolean ok) {
        return (ok ? "[OK]   " : "[FAIL] ") + label + "\n";
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(intent, SAVE_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Destino indisponível");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie esse arquivo aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showFailure(String title, String details) {
        ui.post(() -> {
            status.setTextColor(0xFFEF9A9A);
            status.setText(title);
            output.setText(details);
            analyze.setEnabled(true);
        });
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
