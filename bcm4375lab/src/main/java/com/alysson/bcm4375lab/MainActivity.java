package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView checks;
    private TextView output;
    private Button analyze;
    private Button save;
    private Button monitorTest;
    private File zipFile;
    private volatile boolean busy;

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

        root.addView(text("BCM4375 Lab", 28, Color.WHITE, true));
        TextView subtitle = text("v2.1 • BCM4375B1 • Samsung MON + capability probe + rollback", 12, 0xFF80CBC4, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        TextView device = text("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
                "\nDevice: " + Build.DEVICE + " • Hardware: " + Build.HARDWARE +
                "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT, 12, 0xFFCFD8DC, false);
        device.setPadding(dp(10), dp(10), dp(10), dp(10));
        device.setBackgroundColor(0xFF172027);
        root.addView(device);

        status = text("Primeiro execute a análise. O teste ativo só libera se toda a base bater.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(12));
        root.addView(status);

        analyze = new Button(this);
        analyze.setText("1. ANALISAR BCM4375");
        analyze.setOnClickListener(this::runAnalysis);
        root.addView(analyze);

        monitorTest = new Button(this);
        monitorTest.setText("2. TESTAR SAMSUNG MONITOR + CAPACIDADES");
        monitorTest.setEnabled(false);
        monitorTest.setOnClickListener(v -> confirmMonitorTest());
        root.addView(monitorTest);

        save = new Button(this);
        save.setText("SALVAR ÚLTIMO PACOTE ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        checks = text("Pré-verificações ainda não executadas.", 12, 0xFFB0BEC5, false);
        checks.setTypeface(Typeface.MONOSPACE);
        checks.setPadding(0, dp(14), 0, dp(12));
        root.addView(checks);

        TextView note = text(
                "O teste usa o mfgloader original da Samsung. Não substitui arquivos em /vendor. " +
                "Enquanto B1 Monitor estiver ativo, a v2.1 coleta apenas capacidades/estado; não injeta quadros e não usa Nexmon. " +
                "No rollback, o Wi-Fi é religado antes da confirmação B1 Network, conforme observado no seu S21.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, 0, 0, dp(12));
        root.addView(note);

        output = text("Toque em 1. ANALISAR BCM4375.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private void runAnalysis(View ignored) {
        if (busy) return;
        busy = true;
        setButtons(false, false, false);
        status.setTextColor(0xFFFFD180);
        status.setText("Etapa 0 • aguardando autorização root…");
        checks.setText("Executando verificações…");
        output.setText("Se o Magisk solicitar permissão, autorize BCM4375 Lab.");

        worker.execute(() -> {
            try {
                RootReader.Result root = RootReader.run("id", 30);
                if (root.timedOut || root.code != 0 || !root.output.contains("uid=0")) {
                    showFailure("ROOT INDISPONÍVEL", root.output);
                    return;
                }

                StringBuilder report = new StringBuilder();
                report.append("BCM4375 Lab v2.1.0 - preflight\n")
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

                Preflight pf = preflight();
                String summary = pf.summary();
                report.append("=== ACTIVE PREFLIGHT ===\n").append(summary);
                report.append("ACTIVE_READY=").append(pf.ok ? "YES" : "NO").append('\n');
                createReportZip("BCM4375-Lab-S21-preflight.zip", "bcm4375-v21-preflight.txt", report.toString());

                ui.post(() -> {
                    busy = false;
                    checks.setText(summary);
                    status.setTextColor(pf.ok ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(pf.ok ? "BASE VALIDADA • Samsung MON liberado" : "BASE NÃO VALIDADA • teste ativo bloqueado");
                    output.setText(report + "\nPacote preflight pronto.");
                    setButtons(true, pf.ok, true);
                });
            } catch (Exception e) {
                showFailure("Falha na análise", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private void confirmMonitorTest() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Teste Samsung Monitor")
                .setMessage("O Wi-Fi será desligado temporariamente. O app carregará B1 Monitor, coletará uma sondagem de capacidades e depois restaurará B1 Network/STA. Não feche o app durante o teste.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar teste", (d, w) -> runMonitorTest())
                .show();
    }

    private void runMonitorTest() {
        if (busy) return;
        busy = true;
        setButtons(false, false, false);
        status.setTextColor(0xFFFFD180);
        status.setText("Validando novamente antes da troca…");
        output.setText("Nenhum arquivo de /vendor será alterado.");

        worker.execute(() -> {
            StringBuilder report = new StringBuilder();
            boolean wifiWasEnabled = false;
            boolean monitorLoaded = false;
            boolean rollbackOk = false;
            try {
                Preflight pf = preflight();
                report.append("BCM4375 Lab v2.1.0 - Samsung MON capability test\n\n").append(pf.summary()).append('\n');
                if (!pf.ok) throw new Exception("Preflight ativo não passou. Nenhuma troca foi feita.");

                String wifiState = RootReader.run("settings get global wifi_on", 4).output.trim();
                wifiWasEnabled = "1".equals(wifiState) || "2".equals(wifiState);
                report.append("wifi_on_before=").append(wifiState).append('\n');
                report.append(MonitorController.snapshot("BEFORE"));

                postStatus("Desligando Wi-Fi do Android…");
                MonitorController.wifi(false);
                Thread.sleep(1200);

                postStatus("Selecionando vendor.wlandriver.mode=monitor…");
                RootReader.Result modeResult = MonitorController.setMode("monitor");
                if (modeResult.code != 0 || modeResult.timedOut) throw new Exception("Falha definindo modo monitor: " + modeResult.output);

                postStatus("Iniciando mfgloader Samsung…");
                RootReader.Result startResult = MonitorController.startSamsungLoader();
                report.append("start_monitor_exit=").append(startResult.code).append(" timeout=").append(startResult.timedOut).append('\n');
                if (startResult.code != 0 || startResult.timedOut) throw new Exception("init não aceitou iniciar mfgloader: " + startResult.output);

                monitorLoaded = MonitorController.waitForFirmware("B1 Monitor", 12);
                report.append(MonitorController.snapshot("MONITOR ATTEMPT"));
                if (!monitorLoaded) throw new Exception("B1 Monitor não apareceu dentro de 12 s.");

                postStatus("B1 MONITOR • coletando capacidades…");
                report.append("\n=== MONITOR CAPABILITY PROBE ===\n");
                report.append(RootReader.run(
                        "echo net_type=$(cat /sys/class/net/wlan0/type 2>/dev/null); " +
                        "echo net_flags=$(cat /sys/class/net/wlan0/flags 2>/dev/null); " +
                        "echo '-- ip details --'; ip -details link show wlan0 2>&1; " +
                        "echo '-- proc wireless --'; cat /proc/net/wireless 2>&1; " +
                        "echo '-- tools --'; " +
                        "for x in /system/bin/iw /vendor/bin/iw /system_ext/bin/iw /system/bin/wl /vendor/bin/wl /vendor/bin/hw/wl /system/bin/nexutil /vendor/bin/nexutil; do [ -e $x ] && ls -l $x; done; " +
                        "echo '-- vendor driver props --'; getprop | grep -i 'vendor.wlandriver'; " +
                        "echo '-- monitor log --'; dmesg | grep -iE 'monitor mode|radiotap|monitor|promisc' | tail -100",
                        8).output);

                for (int left = 5; left >= 1; left--) {
                    final int sec = left;
                    ui.post(() -> status.setText("B1 MONITOR ATIVO • rollback em " + sec + " s"));
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                report.append("\nTEST_EXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                try {
                    postStatus("ROLLBACK • apontando para Samsung STA…");
                    MonitorController.setMode("normal");
                    RootReader.Result restoreStart = MonitorController.startSamsungLoader();
                    report.append("rollback_start_exit=").append(restoreStart.code).append(" timeout=").append(restoreStart.timedOut).append('\n');

                    // No S21 testado, mfgloader coloca wlan0 DOWN e firmware_path=STA.
                    // O STA é efetivamente carregado quando o framework Wi-Fi abre wlan0 novamente.
                    if (wifiWasEnabled) {
                        postStatus("ROLLBACK • religando Wi-Fi para carregar STA…");
                        Thread.sleep(800);
                        MonitorController.wifi(true);
                    }

                    postStatus("ROLLBACK • aguardando B1 Network…");
                    rollbackOk = MonitorController.waitForFirmware("B1 Network", 20);
                    report.append(MonitorController.snapshot("AFTER ROLLBACK"));
                    report.append("wifi_on_after=").append(RootReader.run("settings get global wifi_on", 4).output.trim()).append('\n');
                } catch (Exception rollbackError) {
                    report.append("ROLLBACK_EXCEPTION=").append(rollbackError.getMessage()).append('\n');
                }

                report.append("\nmonitor_loaded=").append(monitorLoaded).append('\n');
                report.append("rollback_network_ok=").append(rollbackOk).append('\n');
                report.append("wifi_was_enabled=").append(wifiWasEnabled).append('\n');
                report.append("\n=== DHD LOG AFTER TEST ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|firmware|4375|mfgloader|wlandriver|monitor mode' | tail -280", 7).output);

                try {
                    createReportZip("BCM4375-Lab-S21-monitor-capability-test.zip", "bcm4375-monitor-capability-test.txt", report.toString());
                } catch (Exception zipError) {
                    report.append("ZIP_ERROR=").append(zipError.getMessage()).append('\n');
                }

                final boolean mon = monitorLoaded;
                final boolean rb = rollbackOk;
                final String finalReport = report.toString();
                ui.post(() -> {
                    busy = false;
                    output.setText(finalReport + "\n\nSalve o ZIP e envie aqui.");
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    analyze.setEnabled(true);
                    monitorTest.setEnabled(rb);
                    if (mon && rb) {
                        status.setTextColor(0xFF81C784);
                        status.setText("SUCESSO • B1 Monitor + capability probe + STA restaurado");
                    } else if (rb) {
                        status.setTextColor(0xFFFFD180);
                        status.setText("MON não confirmado • STA restaurado com sucesso");
                    } else {
                        status.setTextColor(0xFFEF9A9A);
                        status.setText("ROLLBACK NÃO CONFIRMADO • reinicie o telefone antes de novos testes");
                        monitorTest.setEnabled(false);
                    }
                });
            }
        });
    }

    private Preflight preflight() {
        String wifiver = MonitorController.wifiver();
        String hashes = RootReader.run("sha256sum /vendor/firmware/bcmdhd_sta.bin_b1 /vendor/firmware/bcmdhd_mon.bin_b1 /vendor/firmware/bcmdhd_mfg.bin_b1 2>/dev/null", 8).output;
        String loaderStatus = MonitorController.status();

        boolean rootOk = RootReader.run("id", 5).output.contains("uid=0");
        boolean modelOk = "SM-G991B".equalsIgnoreCase(Build.MODEL);
        boolean hwOk = "exynos2100".equalsIgnoreCase(Build.HARDWARE);
        boolean networkOk = wifiver.contains("18.41.117") && wifiver.contains("B1 Network");
        boolean staHash = hashes.contains(MonitorController.STA_SHA);
        boolean monHash = hashes.contains(MonitorController.MON_SHA);
        boolean mfgHash = hashes.contains(MonitorController.MFG_SHA);
        boolean pathOk = RootReader.run("test -w /sys/module/dhd/parameters/firmware_path", 3).code == 0;
        boolean loaderOk = RootReader.run("test -x /vendor/bin/hw/mfgloader", 3).code == 0;
        boolean serviceOk = RootReader.run("grep -q 'service mfgloader /vendor/bin/hw/mfgloader' /vendor/etc/wlan_common_rc /vendor/etc/init/wifi.rc 2>/dev/null", 4).code == 0;
        boolean stateOk = !"ok".equalsIgnoreCase(loaderStatus);

        return new Preflight(rootOk, modelOk, hwOk, networkOk, staHash, monHash, mfgHash, pathOk, loaderOk, serviceOk, stateOk, loaderStatus);
    }

    private void createReportZip(String zipName, String reportName, String body) throws Exception {
        File work = new File(getCacheDir(), "bcm4375-v21");
        ExportUtil.deleteRecursive(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Falha criando diretório de relatório");
        File reportFile = new File(work, reportName);
        try (FileOutputStream out = new FileOutputStream(reportFile)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        List<File> files = new ArrayList<>();
        files.add(reportFile);
        File result = new File(getCacheDir(), zipName);
        if (result.exists()) result.delete();
        ExportUtil.zip(files, result);
        zipFile = result;
    }

    private void postProgress(int current, int total, String label) {
        ui.post(() -> status.setText("Etapa " + current + "/" + total + " • " + label));
    }

    private void postStatus(String value) {
        ui.post(() -> status.setText(value));
    }

    private void setButtons(boolean analysis, boolean active, boolean canSave) {
        analyze.setEnabled(analysis);
        monitorTest.setEnabled(active);
        save.setEnabled(canSave && zipFile != null && zipFile.isFile());
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
            busy = false;
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

    private static final class Preflight {
        final boolean ok;
        final boolean rootOk, modelOk, hwOk, networkOk, staHash, monHash, mfgHash, pathOk, loaderOk, serviceOk, stateOk;
        final String loaderStatus;

        Preflight(boolean rootOk, boolean modelOk, boolean hwOk, boolean networkOk,
                  boolean staHash, boolean monHash, boolean mfgHash, boolean pathOk,
                  boolean loaderOk, boolean serviceOk, boolean stateOk, String loaderStatus) {
            this.rootOk = rootOk;
            this.modelOk = modelOk;
            this.hwOk = hwOk;
            this.networkOk = networkOk;
            this.staHash = staHash;
            this.monHash = monHash;
            this.mfgHash = mfgHash;
            this.pathOk = pathOk;
            this.loaderOk = loaderOk;
            this.serviceOk = serviceOk;
            this.stateOk = stateOk;
            this.loaderStatus = loaderStatus;
            this.ok = rootOk && modelOk && hwOk && networkOk && staHash && monHash && mfgHash && pathOk && loaderOk && serviceOk && stateOk;
        }

        String summary() {
            return checkLine("Root", rootOk) +
                    checkLine("Modelo SM-G991B", modelOk) +
                    checkLine("Hardware Exynos 2100", hwOk) +
                    checkLine("Ativo: 18.41.117 B1 Network", networkOk) +
                    checkLine("SHA STA exato", staHash) +
                    checkLine("SHA MON exato", monHash) +
                    checkLine("SHA MFG exato", mfgHash) +
                    checkLine("firmware_path gravável", pathOk) +
                    checkLine("mfgloader executável", loaderOk) +
                    checkLine("serviço mfgloader Samsung presente", serviceOk) +
                    checkLine("mfgloader não está em estado ok", stateOk) +
                    "loader_status_before=" + loaderStatus + "\n";
        }

        private static String checkLine(String label, boolean ok) {
            return (ok ? "[OK]   " : "[FAIL] ") + label + "\n";
        }
    }
}
