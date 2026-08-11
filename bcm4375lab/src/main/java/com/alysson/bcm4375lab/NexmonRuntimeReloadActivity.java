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

public class NexmonRuntimeReloadActivity extends Activity {
    private static final int SAVE_ZIP = 4377;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button verify, reload, collect, rebootStock, save;
    private volatile boolean busy;
    private volatile boolean reloadAttempted;
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
        root.addView(text("v3.1 • runtime DHD reload • Nexmon 18.41.117", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Verifique o estado antes do reload.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + DHD ATUAL");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        reload = button("2. RECARREGAR DHD COM OVERLAY NEXMON");
        reload.setEnabled(false); reload.setOnClickListener(v -> confirmReload()); root.addView(reload);
        collect = button("3. COLETAR RESULTADO");
        collect.setEnabled(false); collect.setOnClickListener(v -> collectEvidence("manual")); root.addView(collect);
        rebootStock = button("4. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "O reload só é liberado quando: módulo guard2 = DISABLED @ ACTIVE, /vendor/firmware/bcmdhd_sta.bin_b1 = SHA Nexmon ec77..., " +
                "e o firmware rodando ainda é Samsung B1 Network. O teste usa svc wifi + vendor.wlandriver.mode=normal + serviço mfgloader Samsung. " +
                "Se algo falhar, o módulo continua DISABLED e o botão STOCK volta ao firmware original no próximo boot.", 12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12)); root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); root.addView(output);
        return scroll;
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        boolean stock = wv.contains("18.41.117") && wv.contains("B1 Network/rsdb");
        boolean nexmon = NexmonOneShotController.isNexmonActive();
        boolean visNex = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean modNex = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml) && visNex && modNex && stock && !nexmon;
        return new Snapshot(root, ms, ml, visibleSha, moduleSha, wv, stock, nexmon, visNex, modNex, ready);
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando overlay e firmware ativo…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-Lab-v31-runtime-preflight.zip", "runtime-preflight.txt", r);
                ui.post(() -> applySnapshot(s, r));
            } catch (Exception e) { fail("VERIFICAÇÃO FALHOU", e); }
        });
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.1 runtime reload\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("stock_runtime=").append(s.stockRuntime).append('\n');
        r.append("nexmon_runtime=").append(s.nexmonRuntime).append('\n');
        r.append("runtime_reload_ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== FIRMWARE_PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        r.append("\n=== WLAN PROPERTIES ===\n").append(RootReader.run("getprop | grep -iE 'vendor\\.wlandriver|wifi' | head -180", 6).output);
        r.append("\n=== MOUNTINFO ===\n").append(RootReader.run("cat /proc/self/mountinfo | grep -E '/vendor($|/firmware)|bcm4375_nexmon' | tail -140", 6).output);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void applySnapshot(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        reload.setEnabled(s.ready && !reloadAttempted);
        collect.setEnabled(true);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        if (s.nexmonRuntime) {
            status.setTextColor(0xFF81C784);
            status.setText("NEXMON DETECTADO NO RUNTIME • colete/salve antes de reboot");
        } else if (s.ready && !reloadAttempted) {
            status.setTextColor(0xFF81C784);
            status.setText("PRONTO • overlay Nexmon ativo, DHD ainda stock, módulo DISABLED");
        } else if (reloadAttempted) {
            status.setTextColor(0xFFFFD180);
            status.setText("RELOAD JÁ EXECUTADO • não repita; colete o ZIP");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("RELOAD BLOQUEADO • requisitos não conferem");
        }
        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nstock_runtime=" + s.stockRuntime +
                "\nnexmon_runtime=" + s.nexmonRuntime +
                "\nready=" + s.ready);
        output.setText(r);
    }

    private void confirmReload() {
        new AlertDialog.Builder(this)
                .setTitle("Reload DHD com overlay Nexmon")
                .setMessage("O Wi-Fi cairá temporariamente. O app força o ciclo Samsung normal/STA e depois espera até 36 s pelo BCM4375. O teste só pode ser feito uma vez nesta sessão.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d, w) -> runReload())
                .show();
    }

    private void runReload() {
        if (busy || reloadAttempted) return;
        reloadAttempted = true;
        setBusy("Validando pré-condições…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            try {
                Snapshot before = snapshot();
                tr.append(report("before-reload", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; reload bloqueado.");

                postStatus("1/5 • Desligando Wi-Fi…");
                RootReader.Result off = RootReader.run("svc wifi disable; sleep 3", 10);
                tr.append("\n=== WIFI OFF ===\ncode=").append(off.code).append(" timeout=").append(off.timedOut).append('\n').append(off.output);

                postStatus("2/5 • Selecionando Samsung normal/STA…");
                tr.append("\n=== MODE NORMAL ===\n").append(RootReader.run("setprop vendor.wlandriver.mode normal; getprop vendor.wlandriver.mode", 5).output);

                postStatus("3/5 • Acionando mfgloader…");
                RootReader.Result loader = RootReader.run("start mfgloader; sleep 4; getprop vendor.wlandriver.status", 12);
                tr.append("\n=== MFGLOADER ===\ncode=").append(loader.code).append(" timeout=").append(loader.timedOut).append('\n').append(loader.output);

                postStatus("4/5 • Religando Wi-Fi…");
                RootReader.Result on = RootReader.run("svc wifi enable", 6);
                tr.append("\n=== WIFI ON ===\ncode=").append(on.code).append(" timeout=").append(on.timedOut).append('\n').append(on.output);

                postStatus("5/5 • Aguardando BCM4375…");
                String last = "";
                boolean detected = false;
                for (int i = 0; i < 18; i++) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    last = NexmonOneShotController.wifiver();
                    if (last.toLowerCase().contains("nexmon")) { detected = true; break; }
                }

                Snapshot after = snapshot();
                tr.append("\n=== LAST WIFIVER POLL ===\n").append(last);
                tr.append("\n=== AFTER RELOAD ===\n").append(report("after-reload", after));
                tr.append("\nSAW_NEXMON_STRING=").append(detected).append('\n');
                tr.append("\n=== DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -850", 12).output);
                tr.append("\n=== INTERFACES ===\n").append(RootReader.run("cat /proc/net/wireless 2>&1; echo ---; cat /sys/class/net/wlan0/type 2>&1; echo ---; cat /sys/class/net/wlan0/flags 2>&1", 6).output);

                createZip("BCM4375-Lab-S21-v31-runtime-reload-result.zip", "runtime-reload-result.txt", tr.toString());
                final boolean detectedFinal = detected;
                ui.post(() -> {
                    applySnapshot(after, tr.toString());
                    reload.setEnabled(false);
                    save.setEnabled(true);
                    status.setTextColor((after.nexmonRuntime || detectedFinal) ? 0xFF81C784 : 0xFFFFD180);
                    status.setText((after.nexmonRuntime || detectedFinal)
                            ? "RELOAD CONCLUÍDO • Nexmon detectado; salve o ZIP"
                            : "RELOAD CONCLUÍDO • Nexmon não confirmado; salve o ZIP");
                });
            } catch (Exception e) {
                RootReader.run("svc wifi enable", 5);
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                tr.append("\n=== FAIL DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -600", 10).output);
                try { createZip("BCM4375-Lab-S21-v31-runtime-reload-failed.zip", "runtime-reload-failed.txt", tr.toString()); } catch (Exception ignored) {}
                fail("RELOAD FALHOU • próximo boot continua stock-protegido", e);
            }
        });
    }

    private void collectEvidence(String phase) {
        if (busy) return;
        setBusy("Coletando logs…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                StringBuilder r = new StringBuilder(report(phase, s));
                r.append("\n=== ONE-SHOT EVIDENCE ===\n").append(NexmonOneShotController.collectEvidence());
                r.append("\n=== RELEVANT DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -1000", 12).output);
                createZip("BCM4375-Lab-S21-v31-current-result.zip", "v31-current-result.txt", r.toString());
                ui.post(() -> { applySnapshot(s, r.toString()); save.setEnabled(true); status.setText("COLETA PRONTA • salve o ZIP"); });
            } catch (Exception e) { fail("COLETA FALHOU", e); }
        });
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O app reafirmará disable no módulo e reiniciará. O próximo boot não montará o overlay Nexmon.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v31-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) { fos.write(body.getBytes(StandardCharsets.UTF_8)); }
        List<File> files = new ArrayList<>(); files.add(report);
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete(); ExportUtil.zip(files, z); zipFile = z;
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); reload.setEnabled(false); collect.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
    }
    private void postStatus(String msg) { ui.post(() -> status.setText(msg)); }
    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false; status.setTextColor(0xFFEF9A9A); status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            verify.setEnabled(true); collect.setEnabled(true); rebootStock.setEnabled(true); save.setEnabled(zipFile != null && zipFile.isFile());
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

    private Button button(String label) { Button b = new Button(this); b.setText(label); return b; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class Snapshot {
        final boolean root, stockRuntime, nexmonRuntime, visibleNexmon, moduleNexmon, ready;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver;
        Snapshot(boolean root, String ms, String ml, String vs, String modSha, String wv, boolean stock, boolean nexmon, boolean visNex, boolean modNex, boolean ready) {
            this.root=root; this.moduleState=ms; this.moduleLocation=ml; this.visibleSha=vs; this.moduleSha=modSha; this.wifiver=wv;
            this.stockRuntime=stock; this.nexmonRuntime=nexmon; this.visibleNexmon=visNex; this.moduleNexmon=modNex; this.ready=ready;
        }
    }
}
