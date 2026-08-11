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

public class NexmonMonitorBounceActivity extends Activity {
    private static final int SAVE_ZIP = 4380;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button verify, run, rebootStock, save;
    private volatile boolean busy;
    private volatile boolean attempted;
    private File zipFile;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(7, 10, 13));
        scroll.addView(root);

        root.addView(text("BCM4375 Lab", 28, Color.WHITE, true));
        root.addView(text("v3.5 • monitor bounce → Nexmon reload • BCM4375B1", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Verifique o estado antes do teste.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + TRANSPORTE");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        run = button("2. MONITOR → NORMAL COM OVERLAY NEXMON");
        run.setEnabled(false); run.setOnClickListener(v -> confirmRun()); root.addView(run);
        rebootStock = button("3. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "A v3.4 provou que normal→normal não recarrega firmware: houve somente suspend/resume PCIe. " +
                "Esta versão força primeiro o firmware Samsung B1 Monitor, que já foi validado neste aparelho. " +
                "Só se B1 Monitor for confirmado, volta para normal/STA enquanto o overlay Nexmon ec77... está ativo e então testa IOCTL 0/1/413. " +
                "O módulo permanece DISABLED; o próximo boot continua protegido para Samsung stock.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12)); root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); root.addView(output);
        return scroll;
    }

    private String probePath() {
        return getApplicationInfo().nativeLibraryDir + "/libnexprobe.so";
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        RootReader.Result tr = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean base = tr.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean present = tr.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        boolean unsupported = tr.output.contains("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED");
        boolean vis = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean mod = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean stock = wv.contains("18.41.117") && wv.contains("B1 Network/rsdb");
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml)
                && vis && mod && stock && base && !present && unsupported;
        return new Snapshot(root, ms, ml, visibleSha, moduleSha, wv, tr.output, base, present, unsupported, stock, ready);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.5 monitor bounce\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("stock_runtime=").append(s.stockRuntime).append('\n');
        r.append("base_ioctl_supported=").append(s.base).append('\n');
        r.append("nexmon_413_present=").append(s.present).append('\n');
        r.append("nexmon_413_unsupported=").append(s.unsupported).append('\n');
        r.append("bounce_ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== IOCTL TRIAGE ===\n").append(s.triage);
        r.append("\n=== FIRMWARE_PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        r.append("\n=== WLAN PROPERTIES ===\n").append(RootReader.run("getprop | grep -iE 'vendor\\.wlandriver|wifi' | head -180", 6).output);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando overlay, stock runtime e IOCTL…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-Lab-S21-v35-preflight.zip", "v35-preflight.txt", r);
                ui.post(() -> applySnapshot(s, r));
            } catch (Exception e) { fail("VERIFICAÇÃO FALHOU", e); }
        });
    }

    private void applySnapshot(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        run.setEnabled(s.ready && !attempted);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        if (s.present) {
            status.setTextColor(0xFF81C784);
            status.setText("NEXMON JÁ RESPONDE AO 413 • não execute bounce");
        } else if (s.ready && !attempted) {
            status.setTextColor(0xFF81C784);
            status.setText("PRONTO • stock ativo + overlay Nexmon + transporte OK");
        } else if (attempted) {
            status.setTextColor(0xFFFFD180);
            status.setText("TESTE JÁ EXECUTADO • não repita; salve o ZIP");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("TESTE BLOQUEADO • pré-condições não conferem");
        }
        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nbase_ioctl=" + s.base +
                "\n413_present=" + s.present +
                "\n413_unsupported=" + s.unsupported +
                "\nready=" + s.ready);
        output.setText(r);
    }

    private void confirmRun() {
        new AlertDialog.Builder(this)
                .setTitle("Forçar reload via B1 Monitor")
                .setMessage("O Wi-Fi cairá. O app carregará primeiro o Samsung B1 Monitor. Somente após confirmar B1 Monitor ele volta para normal/STA com o overlay Nexmon ativo. Faça apenas uma vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runBounce())
                .show();
    }

    private void runBounce() {
        if (busy || attempted) return;
        attempted = true;
        setBusy("Validando pré-condições…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            boolean monitorConfirmed = false;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-bounce", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; bounce bloqueado.");

                postStatus("1/7 • Desligando Wi-Fi…");
                tr.append("\n=== WIFI OFF ===\n").append(RootReader.run("svc wifi disable; sleep 2", 9).output);

                postStatus("2/7 • Selecionando B1 Monitor…");
                tr.append("\n=== MODE MONITOR ===\n").append(RootReader.run("setprop vendor.wlandriver.mode monitor; getprop vendor.wlandriver.mode", 5).output);

                postStatus("3/7 • Acionando mfgloader para Monitor…");
                RootReader.Result monLoad = RootReader.run("start mfgloader; sleep 3; getprop vendor.wlandriver.status", 12);
                tr.append("\n=== MFGLOADER MONITOR ===\ncode=").append(monLoad.code).append(" timeout=").append(monLoad.timedOut).append('\n').append(monLoad.output);

                String monWv = "";
                for (int i = 0; i < 12; i++) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    monWv = NexmonOneShotController.wifiver();
                    if (monWv.contains("B1 Monitor")) { monitorConfirmed = true; break; }
                }
                tr.append("\n=== MONITOR WIFIVER ===\n").append(monWv);
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não foi confirmado; não vou tentar normal/Nexmon.");

                postStatus("4/7 • Voltando para normal/STA com overlay Nexmon…");
                tr.append("\n=== MODE NORMAL ===\n").append(RootReader.run("setprop vendor.wlandriver.mode normal; getprop vendor.wlandriver.mode", 5).output);
                RootReader.Result normLoad = RootReader.run("start mfgloader; sleep 3; getprop vendor.wlandriver.status", 12);
                tr.append("\n=== MFGLOADER NORMAL ===\ncode=").append(normLoad.code).append(" timeout=").append(normLoad.timedOut).append('\n').append(normLoad.output);

                postStatus("5/7 • Religando Wi-Fi…");
                tr.append("\n=== WIFI ON ===\n").append(RootReader.run("svc wifi enable", 6).output);

                postStatus("6/7 • Aguardando transporte Broadcom…");
                String lastTriage = "";
                boolean baseReturned = false;
                boolean nexmonDetected = false;
                for (int i = 0; i < 24; i++) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    RootReader.Result p = RootReader.run(q(probePath()) + " wlan0", 5);
                    lastTriage = p.output;
                    if (lastTriage.contains("TRIAGE_BASE_IOCTL=SUPPORTED")) baseReturned = true;
                    if (lastTriage.contains("TRIAGE_RESULT=NEXMON_PRESENT")) { nexmonDetected = true; break; }
                    if (baseReturned && i >= 5) break;
                }
                tr.append("\n=== POST-NORMAL TRIAGE ===\n").append(lastTriage);
                tr.append("BASE_RETURNED=").append(baseReturned).append('\n');
                tr.append("NEXMON_DETECTED=").append(nexmonDetected).append('\n');

                postStatus("7/7 • Coletando evidências…");
                Snapshot after = snapshot();
                tr.append("\n=== AFTER BOUNCE ===\n").append(report("after-bounce", after));
                tr.append("\n=== DHD/INIT LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader|request_firmware|pcie' | tail -1400", 14).output);
                tr.append("\n=== INTERFACES ===\n").append(RootReader.run("cat /proc/net/wireless 2>&1; echo ---; cat /sys/class/net/wlan0/type 2>&1; echo ---; cat /sys/class/net/wlan0/flags 2>&1", 6).output);

                createZip("BCM4375-Lab-S21-v35-monitor-bounce-result.zip", "v35-monitor-bounce-result.txt", tr.toString());
                final boolean found = nexmonDetected || after.present;
                ui.post(() -> {
                    applySnapshot(after, tr.toString());
                    run.setEnabled(false);
                    save.setEnabled(true);
                    status.setTextColor(found ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(found
                            ? "NEXMON CONFIRMADO APÓS BOUNCE • salve o ZIP"
                            : "BOUNCE CONCLUÍDO • 413 ainda não confirmou Nexmon; salve o ZIP");
                });
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                tr.append("MONITOR_CONFIRMED_AT_FAILURE=").append(monitorConfirmed).append('\n');
                RootReader.run("setprop vendor.wlandriver.mode normal; start mfgloader; sleep 2; svc wifi enable", 12);
                tr.append("\n=== RECOVERY ATTEMPT ===\n").append(RootReader.run("getprop vendor.wlandriver.status; cat /sys/wifi/wifiver 2>&1", 6).output);
                tr.append("\n=== FAIL DHD/INIT LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader|request_firmware|pcie' | tail -1400", 14).output);
                try { createZip("BCM4375-Lab-S21-v35-monitor-bounce-failed.zip", "v35-monitor-bounce-failed.txt", tr.toString()); } catch (Exception ignored) {}
                fail("BOUNCE FALHOU • não repita; salve o ZIP ou reinicie stock", e);
            }
        });
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O app reafirmará disable no módulo, restaurará mode=normal e reiniciará. O próximo boot não montará o overlay Nexmon.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    RootReader.run("setenforce 1; setprop vendor.wlandriver.mode normal", 4);
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v35-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) { fos.write(body.getBytes(StandardCharsets.UTF_8)); }
        List<File> files = new ArrayList<>(); files.add(report);
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete(); ExportUtil.zip(files, z); zipFile = z;
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); run.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
    }

    private void postStatus(String msg) { ui.post(() -> status.setText(msg)); }

    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false; status.setTextColor(0xFFEF9A9A); status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            verify.setEnabled(true); rebootStock.setEnabled(true); save.setEnabled(zipFile != null && zipFile.isFile());
        });
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip"); i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(i, SAVE_ZIP);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != SAVE_ZIP || res != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Destino indisponível");
                byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo.", Toast.LENGTH_LONG).show());
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); return b; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class Snapshot {
        final boolean root, base, present, unsupported, stockRuntime, ready;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver, triage;
        Snapshot(boolean root, String ms, String ml, String vs, String modSha, String wv, String triage,
                 boolean base, boolean present, boolean unsupported, boolean stockRuntime, boolean ready) {
            this.root = root; this.moduleState = ms; this.moduleLocation = ml; this.visibleSha = vs; this.moduleSha = modSha;
            this.wifiver = wv; this.triage = triage; this.base = base; this.present = present; this.unsupported = unsupported;
            this.stockRuntime = stockRuntime; this.ready = ready;
        }
    }
}
