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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v4.2 isolates SELinux as the cause of firmware_class.path EACCES.
 * It loads only Samsung STOCK firmware from /data/vendor/wifi while SELinux
 * is temporarily Permissive, then restores Enforcing and the original path.
 */
public class SelinuxStockProofV42Activity extends Activity {
    private static final int SAVE_ZIP = 4420;
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/bcm4375_selinux_v42";
    private static final String STOCK = "/vendor/firmware/bcmdhd_sta.bin_b1";
    private static final String CLM = "/vendor/firmware/bcmdhd_clm.blob";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, state, output;
    private Button verify, run, save;
    private volatile boolean busy, attempted;
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

        root.addView(text("BCM4375 SELinux Proof", 28, Color.WHITE, true));
        root.addView(text("v4.2 • STOCK + firmware_class.path • Permissive temporário", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Primeiro execute o preflight.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        verify = button("1. PREFLIGHT STOCK + SELINUX");
        verify.setOnClickListener(v -> verifyState());
        root.addView(verify);

        run = button("2. TESTAR STOCK COM PERMISSIVE TEMPORÁRIO");
        run.setEnabled(false);
        run.setOnClickListener(v -> confirmRun());
        root.addView(run);

        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "O teste anterior retornou EACCES (-13) ao kernel ler bcmdhd_sta.bin_b1 e bcmdhd_clm.blob em /data/vendor/wifi mesmo com owner wifi:wifi e modo 0644. " +
                "Esta versão NÃO carrega Nexmon. Ela usa somente uma cópia do firmware Samsung stock, entra em B1 Monitor com SELinux Enforcing, aponta firmware_class.path para /data/vendor/wifi, " +
                "muda SELinux temporariamente para Permissive apenas durante a volta para B1 Network e restaura Enforcing no bloco finally. O caminho original também é sempre restaurado.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private String probePath() { return getApplicationInfo().nativeLibraryDir + "/libnexprobe.so"; }
    private String fwclassRead() { return RootReader.run("cat " + FWCLASS + " 2>&1", 4).output.trim(); }
    private String enforcing() { return RootReader.run("getenforce 2>&1", 3).output.trim(); }

    private boolean isStockRuntime() {
        String sha = NexmonOneShotController.currentFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        return NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(sha)
                && wv.contains("18.41.117") && wv.contains("B1 Network/rsdb");
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 5);
        boolean root = id.code == 0 && !id.timedOut && id.output.contains("uid=0");
        boolean stock = isStockRuntime();
        boolean fwclass = RootReader.run("test -e " + FWCLASS, 3).code == 0;
        String oldPath = fwclass ? fwclassRead() : "<missing>";
        RootReader.Result tri = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean base = tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean nexmon = tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        String selinux = enforcing();
        boolean enforcing = "Enforcing".equalsIgnoreCase(selinux);
        RootReader.Result path = RootReader.run("ls -ldZ /data/vendor /data/vendor/wifi 2>&1", 4);
        boolean wifiPath = path.output.contains("/data/vendor/wifi") && !path.output.contains("No such file");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        boolean safeModule = "DISABLED".equals(ms) && "ACTIVE".equals(ml);
        RootReader.Result se = RootReader.run("command -v setenforce 2>/dev/null || test -x /system/bin/setenforce", 3);
        boolean setenforce = se.code == 0;
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && stock && fwclass && base && !nexmon && enforcing && wifiPath && safeModule && setenforce;
        return new Snapshot(root, stock, fwclass, oldPath, base, nexmon, enforcing, wifiPath,
                safeModule, setenforce, ms, ml, ready, selinux, tri.output, path.output);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 SELinux Proof v4.2\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("stock_runtime=").append(s.stock).append('\n');
        r.append("current_sha=").append(NexmonOneShotController.currentFirmwareSha()).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("safe_module_state=").append(s.safeModule).append('\n');
        r.append("fwclass_exists=").append(s.fwclass).append('\n');
        r.append("fwclass_original=").append(s.oldPath).append('\n');
        r.append("base_ioctl=").append(s.base).append('\n');
        r.append("nexmon_present=").append(s.nexmon).append('\n');
        r.append("selinux=").append(s.selinux).append('\n');
        r.append("selinux_enforcing=").append(s.enforcing).append('\n');
        r.append("setenforce_available=").append(s.setenforce).append('\n');
        r.append("wifi_path_exists=").append(s.wifiPath).append('\n');
        r.append("ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(NexmonOneShotController.wifiver());
        r.append("\n=== IOCTL ===\n").append(s.triage);
        r.append("\n=== PATH ===\n").append(s.pathInfo);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando stock, SELinux, transporte e staging…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-SELinux-Proof-v4.2-preflight.zip", "v42-preflight.txt", r);
                ui.post(() -> {
                    busy = false;
                    verify.setEnabled(true);
                    run.setEnabled(s.ready && !attempted);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(s.ready ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(s.ready ? "PRONTO • teste usa somente Samsung stock" : "BLOQUEADO • preflight falhou");
                    state.setText("stock=" + s.stock + " module=" + s.moduleState + " @ " + s.moduleLocation
                            + "\nSELinux=" + s.selinux + " setenforce=" + s.setenforce
                            + "\nfwclass=" + s.fwclass + " base_ioctl=" + s.base
                            + "\nready=" + s.ready);
                    output.setText(r);
                });
            } catch (Exception e) { fail("PREFLIGHT FALHOU", e); }
        });
    }

    private void confirmRun() {
        new AlertDialog.Builder(this)
                .setTitle("Provar bloqueio SELinux")
                .setMessage("Será usado apenas firmware Samsung stock. SELinux ficará Permissive somente durante uma recarga B1 Monitor → B1 Network e será restaurado para Enforcing mesmo se ocorrer falha. Execute uma única vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runProof())
                .show();
    }

    private void runProof() {
        if (busy || attempted) return;
        attempted = true;
        setBusy("Revalidando preflight…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            String originalPath = "";
            boolean monitorConfirmed = false;
            boolean permissiveConfirmed = false;
            boolean networkConfirmed = false;
            boolean eacces = false;
            boolean fallback = false;
            boolean requestSuccess = false;
            boolean selinuxCauseConfirmed = false;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-proof", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; teste bloqueado.");
                originalPath = before.oldPath;

                postStatus("1/8 • Preparando Samsung stock em /data/vendor/wifi…");
                String prep = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) + "; "
                        + "cp " + q(STOCK) + " " + q(STAGE + "/bcmdhd_sta.bin_b1") + "; "
                        + "cp " + q(CLM) + " " + q(STAGE + "/bcmdhd_clm.blob") + "; "
                        + "chown -R wifi:wifi " + q(STAGE) + "; chmod 0755 " + q(STAGE) + "; chmod 0644 " + q(STAGE) + "/*; "
                        + "restorecon -RF " + q(STAGE) + " 2>&1; "
                        + "ls -ldZ " + q(STAGE) + "; ls -lZ " + q(STAGE) + "; sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1") + ";";
                RootReader.Result prepR = RootReader.run(prep, 10);
                tr.append("\n=== STAGE STOCK ===\ncode=").append(prepR.code).append(" timeout=").append(prepR.timedOut).append('\n').append(prepR.output);
                if (prepR.code != 0 || prepR.timedOut || !prepR.output.toLowerCase().contains(NexmonOneShotController.STOCK_SHA.toLowerCase()))
                    throw new Exception("Falha preparando staging stock.");

                postStatus("2/8 • Desligando Wi-Fi…");
                tr.append("\n=== WIFI OFF ===\n").append(MonitorController.wifi(false).output);
                Thread.sleep(1200);

                postStatus("3/8 • Carregando Samsung B1 Monitor com Enforcing…");
                tr.append("\n=== MODE MONITOR ===\n").append(MonitorController.setMode("monitor").output);
                tr.append("\n=== START MONITOR ===\n").append(MonitorController.startSamsungLoader().output);
                monitorConfirmed = MonitorController.waitForFirmware("B1 Monitor", 12);
                tr.append("\n").append(MonitorController.snapshot("MONITOR"));
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não confirmado.");

                postStatus("4/8 • firmware_class.path → staging stock…");
                RootReader.Result set = RootReader.run("printf %s " + q(STAGE) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                boolean customPathSet = set.code == 0 && !set.timedOut && STAGE.equals(set.output.trim());
                tr.append("\n=== SET FWCLASS ===\ncode=").append(set.code).append(" timeout=").append(set.timedOut).append('\n').append(set.output);
                tr.append("CUSTOM_PATH_SET=").append(customPathSet).append('\n');
                if (!customPathSet) throw new Exception("firmware_class.path não aceitou staging.");

                int baseLines = parseInt(RootReader.run("dmesg | wc -l", 5).output.trim(), 0);
                tr.append("DMESG_BASE_LINES=").append(baseLines).append('\n');

                postStatus("5/8 • SELinux → Permissive TEMPORÁRIO…");
                RootReader.Result sp = RootReader.run("setenforce 0; getenforce", 5);
                permissiveConfirmed = sp.code == 0 && !sp.timedOut && sp.output.contains("Permissive");
                tr.append("\n=== SETENFORCE 0 ===\ncode=").append(sp.code).append(" timeout=").append(sp.timedOut).append('\n').append(sp.output);
                tr.append("PERMISSIVE_CONFIRMED=").append(permissiveConfirmed).append('\n');
                if (!permissiveConfirmed) throw new Exception("SELinux não entrou em Permissive; recarga abortada.");

                postStatus("6/8 • Voltando para Samsung B1 Network via staging…");
                tr.append("\n=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output);
                tr.append("\n=== START NORMAL ===\n").append(MonitorController.startSamsungLoader().output);
                Thread.sleep(900);
                tr.append("\n=== WIFI ON ===\n").append(MonitorController.wifi(true).output);
                networkConfirmed = MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                Thread.sleep(1000);

                String deltaCmd = baseLines > 0 ? "dmesg | tail -n +" + (baseLines + 1) : "dmesg | tail -500";
                String delta = RootReader.run(deltaCmd, 8).output;
                tr.append("\n=== DMESG DELTA PERMISSIVE ===\n").append(delta);
                String low = delta.toLowerCase();
                eacces = low.contains("error -13") || low.contains("permission denied");
                fallback = low.contains("falling back to sysfs fallback");
                requestSuccess = low.contains("request firmware api") && low.contains("success");
                selinuxCauseConfirmed = monitorConfirmed && permissiveConfirmed && networkConfirmed && !eacces && !fallback;
                tr.append("NETWORK_CONFIRMED=").append(networkConfirmed).append('\n');
                tr.append("EACCES_SEEN=").append(eacces).append('\n');
                tr.append("SYSFS_FALLBACK_SEEN=").append(fallback).append('\n');
                tr.append("REQUEST_FIRMWARE_SUCCESS_SEEN=").append(requestSuccess).append('\n');
                tr.append("SELINUX_CAUSE_CONFIRMED=").append(selinuxCauseConfirmed).append('\n');
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                postStatus("7/8 • Restaurando firmware_class.path + Enforcing…");
                String restore = originalPath == null ? "" : originalPath;
                RootReader.Result rr = RootReader.run("printf %s " + q(restore) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                tr.append("\n=== RESTORE FWCLASS ===\ncode=").append(rr.code).append(" timeout=").append(rr.timedOut).append('\n').append(rr.output);

                RootReader.Result se = RootReader.run("setenforce 1; getenforce", 5);
                tr.append("\n=== RESTORE SELINUX ===\ncode=").append(se.code).append(" timeout=").append(se.timedOut).append('\n').append(se.output);

                postStatus("8/8 • Garantindo Samsung stock…");
                if (!isStockRuntime()) {
                    tr.append("\n=== EMERGENCY STOCK RESTORE ===\n");
                    tr.append(MonitorController.setMode("normal").output);
                    tr.append(MonitorController.startSamsungLoader().output);
                    tr.append(MonitorController.wifi(true).output);
                    MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                } else {
                    MonitorController.wifi(true);
                }

                tr.append("\n=== FINAL ===\n").append(MonitorController.snapshot("FINAL"));
                tr.append("FINAL_FWCLASS=").append(fwclassRead()).append('\n');
                tr.append("FINAL_SELINUX=").append(enforcing()).append('\n');
                tr.append("FINAL_STOCK_RUNTIME=").append(isStockRuntime()).append('\n');
                tr.append("MODULE_STATE_FINAL=").append(NexmonOneShotController.moduleState()).append('\n');
                RootReader.run("rm -rf " + q(STAGE), 5);
            }

            try {
                createZip("BCM4375-SELinux-Proof-v4.2-result.zip", "v42-selinux-proof.txt", tr.toString());
            } catch (Exception e) {
                tr.append("ZIP_ERROR=").append(e.getMessage()).append('\n');
            }

            boolean ok = selinuxCauseConfirmed;
            boolean finalStock = isStockRuntime();
            String finalSelinux = enforcing();
            ui.post(() -> {
                busy = false;
                verify.setEnabled(true);
                run.setEnabled(false);
                save.setEnabled(zipFile != null && zipFile.isFile());
                status.setTextColor(ok ? 0xFF81C784 : 0xFFFFD180);
                status.setText(ok ? "SELINUX CONFIRMADO COMO CAUSA • salve o ZIP" : "TESTE CONCLUÍDO • salve o ZIP; não repita");
                state.setText("selinux_cause_confirmed=" + ok
                        + "\nfinal_stock=" + finalStock
                        + "\nfinal_SELinux=" + finalSelinux
                        + "\nfwclass=" + fwclassRead());
                output.setText(tr.toString());
            });
        });
    }

    private void setBusy(String s) {
        busy = true;
        verify.setEnabled(false);
        run.setEnabled(false);
        save.setEnabled(false);
        status.setTextColor(0xFFFFD180);
        status.setText(s);
    }

    private void postStatus(String s) { ui.post(() -> status.setText(s)); }

    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false;
            verify.setEnabled(true);
            run.setEnabled(false);
            save.setEnabled(zipFile != null && zipFile.isFile());
            status.setTextColor(0xFFEF9A9A);
            status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
        });
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File out = new File(getCacheDir(), zipName);
        if (out.exists()) out.delete();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.putNextEntry(new ZipEntry(reportName));
            zos.write(body.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        zipFile = out;
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(i, SAVE_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("OutputStream nulo");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private static class Snapshot {
        final boolean root, stock, fwclass, base, nexmon, enforcing, wifiPath, safeModule, setenforce, ready;
        final String oldPath, moduleState, moduleLocation, selinux, triage, pathInfo;

        Snapshot(boolean root, boolean stock, boolean fwclass, String oldPath, boolean base, boolean nexmon,
                 boolean enforcing, boolean wifiPath, boolean safeModule, boolean setenforce,
                 String moduleState, String moduleLocation, boolean ready, String selinux,
                 String triage, String pathInfo) {
            this.root = root; this.stock = stock; this.fwclass = fwclass; this.oldPath = oldPath;
            this.base = base; this.nexmon = nexmon; this.enforcing = enforcing; this.wifiPath = wifiPath;
            this.safeModule = safeModule; this.setenforce = setenforce; this.moduleState = moduleState;
            this.moduleLocation = moduleLocation; this.ready = ready; this.selinux = selinux;
            this.triage = triage; this.pathInfo = pathInfo;
        }
    }
}
