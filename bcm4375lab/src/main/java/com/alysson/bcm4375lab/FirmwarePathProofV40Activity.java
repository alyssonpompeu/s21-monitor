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
 * v4.0 proves that request_firmware() can read from /data/vendor/wifi
 * using an exact copy of Samsung STOCK firmware. No Nexmon firmware is loaded.
 */
public class FirmwarePathProofV40Activity extends Activity {
    private static final int SAVE_ZIP = 4400;
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/bcm4375_pathproof_v40";
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

        root.addView(text("BCM4375 PathProof", 28, Color.WHITE, true));
        root.addView(text("v4.0 • STOCK via /data/vendor/wifi • nenhum Nexmon é carregado", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Primeiro execute o preflight.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        verify = button("1. PREFLIGHT STOCK + /DATA/VENDOR/WIFI");
        verify.setOnClickListener(v -> verifyState());
        root.addView(verify);

        run = button("2. PROVAR STOCK VIA FIRMWARE_CLASS.PATH");
        run.setEnabled(false);
        run.setOnClickListener(v -> confirmRun());
        root.addView(run);

        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "O 221.zip mostrou /data/adb como 0700 root:root e /data/vendor/wifi como 0771 wifi:wifi. " +
                "O mfgloader Samsung roda como usuário wifi. Esta versão copia SOMENTE o firmware Samsung stock para " +
                STAGE + ", aplica owner wifi:wifi + restorecon, carrega B1 Monitor e volta para B1 Network usando esse diretório. " +
                "Se não houver EACCES nem fallback, o caminho fica provado. firmware_class.path é restaurado em finally e o staging é removido.",
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

    private String fwclassRead() {
        return RootReader.run("cat " + FWCLASS + " 2>&1", 4).output.trim();
    }

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
        String old = fwclass ? fwclassRead() : "<missing>";
        RootReader.Result tri = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean base = tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean present = tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        RootReader.Result path = RootReader.run("ls -ldZ /data/vendor /data/vendor/wifi 2>&1", 4);
        boolean wifiPath = path.output.contains("/data/vendor/wifi") && !path.output.contains("No such file");
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && stock && fwclass && base && !present && wifiPath;
        return new Snapshot(root, stock, fwclass, old, base, present, wifiPath, ready, tri.output, path.output);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 PathProof v4.0\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("stock_runtime=").append(s.stock).append('\n');
        r.append("current_sha=").append(NexmonOneShotController.currentFirmwareSha()).append('\n');
        r.append("fwclass_exists=").append(s.fwclass).append('\n');
        r.append("fwclass_original=").append(s.oldPath).append('\n');
        r.append("base_ioctl=").append(s.base).append('\n');
        r.append("nexmon_present=").append(s.present).append('\n');
        r.append("wifi_path_exists=").append(s.wifiPath).append('\n');
        r.append("ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(NexmonOneShotController.wifiver());
        r.append("\n=== IOCTL ===\n").append(s.triage);
        r.append("\n=== PATH ===\n").append(s.pathInfo);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando stock, transporte e /data/vendor/wifi…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-PathProof-v4.0-preflight.zip", "v40-preflight.txt", r);
                ui.post(() -> {
                    busy = false;
                    verify.setEnabled(true);
                    run.setEnabled(s.ready && !attempted);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(s.ready ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(s.ready ? "PRONTO • teste usa apenas firmware Samsung stock" : "BLOQUEADO • preflight falhou");
                    state.setText("stock=" + s.stock + " fwclass=" + s.fwclass + " base_ioctl=" + s.base
                            + "\n/data/vendor/wifi=" + s.wifiPath + " nexmon_present=" + s.present
                            + "\nready=" + s.ready);
                    output.setText(r);
                });
            } catch (Exception e) { fail("PREFLIGHT FALHOU", e); }
        });
    }

    private void confirmRun() {
        new AlertDialog.Builder(this)
                .setTitle("Provar caminho com firmware stock")
                .setMessage("Será criada uma cópia temporária do firmware Samsung stock em /data/vendor/wifi. O rádio fará Network → Monitor → Network. Nenhum firmware Nexmon será carregado. Execute uma única vez.")
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
            boolean networkConfirmed = false;
            boolean customPathSet = false;
            boolean proofSuccess = false;
            int dmesgLines = 0;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-proof", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; teste bloqueado.");
                originalPath = before.oldPath;

                postStatus("1/7 • Preparando cópia stock em /data/vendor/wifi…");
                String prep = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) + "; "
                        + "cp " + q(STOCK) + " " + q(STAGE + "/bcmdhd_sta.bin_b1") + "; "
                        + "cp " + q(CLM) + " " + q(STAGE + "/bcmdhd_clm.blob") + " 2>/dev/null || true; "
                        + "chown -R wifi:wifi " + q(STAGE) + "; chmod 0755 " + q(STAGE) + "; chmod 0644 " + q(STAGE) + "/*; "
                        + "restorecon -RF " + q(STAGE) + " 2>&1; "
                        + "ls -ldZ " + q(STAGE) + "; ls -lZ " + q(STAGE) + "; sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1");";
                RootReader.Result prepR = RootReader.run(prep, 10);
                tr.append("\n=== STAGE STOCK ===\ncode=").append(prepR.code).append(" timeout=").append(prepR.timedOut).append('\n').append(prepR.output);
                if (prepR.code != 0 || prepR.timedOut || !prepR.output.contains(NexmonOneShotController.STOCK_SHA))
                    throw new Exception("Falha preparando cópia stock.");

                dmesgLines = parseInt(RootReader.run("dmesg | wc -l", 5).output.trim(), 0);
                tr.append("DMESG_BASE_LINES=").append(dmesgLines).append('\n');

                postStatus("2/7 • Desligando Wi-Fi…");
                tr.append("\n=== WIFI OFF ===\n").append(MonitorController.wifi(false).output);
                Thread.sleep(1200);

                postStatus("3/7 • Carregando B1 Monitor Samsung…");
                tr.append("\n=== MODE MONITOR ===\n").append(MonitorController.setMode("monitor").output);
                tr.append("\n=== START MONITOR ===\n").append(MonitorController.startSamsungLoader().output);
                monitorConfirmed = MonitorController.waitForFirmware("B1 Monitor", 12);
                tr.append("\n").append(MonitorController.snapshot("MONITOR"));
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não confirmado.");

                postStatus("4/7 • firmware_class.path → staging stock…");
                RootReader.Result set = RootReader.run("printf %s " + q(STAGE) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                customPathSet = set.code == 0 && !set.timedOut && STAGE.equals(set.output.trim());
                tr.append("\n=== SET FWCLASS ===\ncode=").append(set.code).append(" timeout=").append(set.timedOut).append('\n').append(set.output);
                tr.append("CUSTOM_PATH_SET=").append(customPathSet).append('\n');
                if (!customPathSet) throw new Exception("firmware_class.path não aceitou staging.");

                postStatus("5/7 • Voltando para B1 Network via staging…");
                tr.append("\n=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output);
                tr.append("\n=== START NORMAL ===\n").append(MonitorController.startSamsungLoader().output);
                Thread.sleep(900);
                tr.append("\n=== WIFI ON ===\n").append(MonitorController.wifi(true).output);
                networkConfirmed = MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                Thread.sleep(1200);

                String deltaCmd = dmesgLines > 0 ? "dmesg | tail -n +" + (dmesgLines + 1) : "dmesg | tail -400";
                String delta = RootReader.run(deltaCmd, 8).output;
                tr.append("\n=== DMESG DELTA ===\n").append(delta);
                tr.append("\n").append(MonitorController.snapshot("POST STOCK PATH PROOF"));
                tr.append("NETWORK_CONFIRMED=").append(networkConfirmed).append('\n');

                String low = delta.toLowerCase();
                boolean eacces = low.contains("error -13") || low.contains("permission denied");
                boolean fallback = low.contains("falling back to sysfs fallback");
                boolean requestSuccess = delta.contains("Request Firmware API) success");
                proofSuccess = monitorConfirmed && customPathSet && networkConfirmed && !eacces && !fallback && requestSuccess;
                tr.append("EACCES_SEEN=").append(eacces).append('\n');
                tr.append("SYSFS_FALLBACK_SEEN=").append(fallback).append('\n');
                tr.append("REQUEST_FIRMWARE_SUCCESS_SEEN=").append(requestSuccess).append('\n');
                tr.append("PATH_PROOF_SUCCESS=").append(proofSuccess).append('\n');
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                postStatus("6/7 • Restaurando firmware_class.path + stock…");
                String restore = originalPath == null ? "" : originalPath;
                RootReader.Result rr = RootReader.run("printf %s " + q(restore) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                tr.append("\n=== RESTORE FWCLASS ===\ncode=").append(rr.code).append(" timeout=").append(rr.timedOut).append('\n').append(rr.output);

                if (!isStockRuntime()) {
                    tr.append("\n=== EMERGENCY NORMAL STOCK ===\n");
                    tr.append(MonitorController.setMode("normal").output);
                    tr.append(MonitorController.startSamsungLoader().output);
                    tr.append(MonitorController.wifi(true).output);
                    MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                } else {
                    MonitorController.wifi(true);
                }
                tr.append("\n=== FINAL SNAPSHOT ===\n").append(MonitorController.snapshot("FINAL"));
                tr.append("FINAL_FWCLASS=").append(fwclassRead()).append('\n');
                tr.append("FINAL_SELINUX=").append(RootReader.run("getenforce 2>&1", 3).output.trim()).append('\n');
                tr.append("FINAL_STOCK_RUNTIME=").append(isStockRuntime()).append('\n');
                RootReader.run("rm -rf " + q(STAGE), 5);
            }

            try {
                createZip("BCM4375-PathProof-v4.0-result.zip", "v40-path-proof.txt", tr.toString());
            } catch (Exception e) {
                tr.append("ZIP_ERROR=").append(e.getMessage()).append('\n');
            }

            boolean ok = proofSuccess;
            ui.post(() -> {
                busy = false;
                verify.setEnabled(true);
                run.setEnabled(false);
                save.setEnabled(zipFile != null && zipFile.isFile());
                status.setTextColor(ok ? 0xFF81C784 : 0xFFFFD180);
                status.setText(ok ? "CAMINHO PROVADO COM STOCK • salve o ZIP" : "TESTE CONCLUÍDO • caminho ainda não provado; salve o ZIP");
                state.setText("path_proof_success=" + ok + "\nfinal_stock=" + isStockRuntime() + "\nfwclass=" + fwclassRead());
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
        final boolean root, stock, fwclass, base, present, wifiPath, ready;
        final String oldPath, triage, pathInfo;
        Snapshot(boolean root, boolean stock, boolean fwclass, String oldPath, boolean base, boolean present,
                 boolean wifiPath, boolean ready, String triage, String pathInfo) {
            this.root = root; this.stock = stock; this.fwclass = fwclass; this.oldPath = oldPath;
            this.base = base; this.present = present; this.wifiPath = wifiPath; this.ready = ready;
            this.triage = triage; this.pathInfo = pathInfo;
        }
    }
}
