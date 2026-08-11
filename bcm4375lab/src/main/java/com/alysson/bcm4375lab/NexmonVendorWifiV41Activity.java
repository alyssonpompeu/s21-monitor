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
 * v4.1 stages the verified Nexmon image under /data/vendor/wifi, then uses
 * firmware_class.path for one controlled Monitor -> STA reload. Success is
 * defined only by Nexmon IOCTL 413, not by wifiver strings.
 */
public class NexmonVendorWifiV41Activity extends Activity {
    private static final int SAVE_ZIP = 4410;
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/bcm4375_nexmon_v41";
    private static final String SRC = NexmonOneShotController.ACTIVE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1";
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

        root.addView(text("BCM4375 NexStage", 28, Color.WHITE, true));
        root.addView(text("v4.1 • Nexmon via /data/vendor/wifi • validação IOCTL 413", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Primeiro execute o preflight.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        verify = button("1. PREFLIGHT STOCK + NEXMON + /DATA/VENDOR/WIFI");
        verify.setOnClickListener(v -> verifyState());
        root.addView(verify);

        run = button("2. MONITOR → NEXMON VIA /DATA/VENDOR/WIFI");
        run.setEnabled(false);
        run.setOnClickListener(v -> confirmRun());
        root.addView(run);

        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "A v4.0 confirmou Monitor → Network sem EACCES e sem fallback enquanto firmware_class.path apontava para /data/vendor/wifi. " +
                "Esta versão copia o Nexmon ec77 verificado para um subdiretório temporário wifi:wifi, faz Monitor → STA uma única vez e considera sucesso SOMENTE se o IOCTL 413 responder. " +
                "firmware_class.path é sempre restaurado. Se 413 não aparecer, o app garante retorno ao stock quando necessário. O módulo permanece DISABLED para o próximo boot ser stock.",
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

    private boolean isStockRuntime() {
        String sha = NexmonOneShotController.currentFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        return NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(sha)
                && wv.contains("18.41.117") && wv.contains("B1 Network/rsdb");
    }

    private RootReader.Result triage() {
        return RootReader.run(q(probePath()) + " wlan0", 6);
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 5);
        boolean root = id.code == 0 && !id.timedOut && id.output.contains("uid=0");
        boolean stock = isStockRuntime();
        boolean fwclass = RootReader.run("test -e " + FWCLASS, 3).code == 0;
        String old = fwclass ? fwclassRead() : "<missing>";
        RootReader.Result tri = triage();
        boolean base = tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean present = tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        RootReader.Result src = RootReader.run("test -f " + q(SRC) + " && sha256sum " + q(SRC), 6);
        boolean nexFile = src.code == 0 && src.output.toLowerCase().contains(NexmonOneShotController.NEXMON_SHA.toLowerCase());
        RootReader.Result path = RootReader.run("ls -ldZ /data/vendor /data/vendor/wifi 2>&1", 4);
        boolean wifiPath = path.output.contains("/data/vendor/wifi") && !path.output.contains("No such file");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        boolean safeModule = "DISABLED".equals(ms) && "ACTIVE".equals(ml);
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && stock && fwclass && base && !present && nexFile && wifiPath && safeModule;
        return new Snapshot(root, stock, fwclass, old, base, present, nexFile, wifiPath,
                safeModule, ms, ml, ready, tri.output, path.output, src.output);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 NexStage v4.1\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("stock_runtime=").append(s.stock).append('\n');
        r.append("current_sha=").append(NexmonOneShotController.currentFirmwareSha()).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("safe_module_state=").append(s.safeModule).append('\n');
        r.append("nexmon_source_ok=").append(s.nexFile).append('\n');
        r.append("fwclass_exists=").append(s.fwclass).append('\n');
        r.append("fwclass_original=").append(s.oldPath).append('\n');
        r.append("base_ioctl=").append(s.base).append('\n');
        r.append("nexmon_present_before=").append(s.present).append('\n');
        r.append("wifi_path_exists=").append(s.wifiPath).append('\n');
        r.append("ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(NexmonOneShotController.wifiver());
        r.append("\n=== IOCTL ===\n").append(s.triage);
        r.append("\n=== NEXMON SOURCE ===\n").append(s.srcInfo);
        r.append("\n=== PATH ===\n").append(s.pathInfo);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando stock, Nexmon ec77, transporte e staging…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-NexStage-v4.1-preflight.zip", "v41-preflight.txt", r);
                ui.post(() -> {
                    busy = false;
                    verify.setEnabled(true);
                    run.setEnabled(s.ready && !attempted);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(s.ready ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(s.ready ? "PRONTO • Nexmon ec77 + staging wifi validados" : "BLOQUEADO • preflight falhou");
                    state.setText("stock=" + s.stock + " module=" + s.moduleState + " @ " + s.moduleLocation
                            + "\nnexmon_source=" + s.nexFile + " base_ioctl=" + s.base
                            + "\n/data/vendor/wifi=" + s.wifiPath + " 413_before=" + s.present
                            + "\nready=" + s.ready);
                    output.setText(r);
                });
            } catch (Exception e) { fail("PREFLIGHT FALHOU", e); }
        });
    }

    private void confirmRun() {
        new AlertDialog.Builder(this)
                .setTitle("Carregar Nexmon via /data/vendor/wifi")
                .setMessage("O rádio fará Samsung Network → Monitor → STA/Nexmon. A imagem ec77 será copiada temporariamente para /data/vendor/wifi. O sucesso só será aceito se IOCTL 413 responder. Execute uma única vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runLoad())
                .show();
    }

    private void runLoad() {
        if (busy || attempted) return;
        attempted = true;
        setBusy("Revalidando preflight…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            String originalPath = "";
            boolean monitorConfirmed = false;
            boolean customPathSet = false;
            boolean nexmonDetected = false;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-load", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; teste bloqueado.");
                originalPath = before.oldPath;

                postStatus("1/7 • Preparando Nexmon ec77 em /data/vendor/wifi…");
                String prep = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) + "; "
                        + "cp " + q(SRC) + " " + q(STAGE + "/bcmdhd_sta.bin_b1") + "; "
                        + "cp " + q(CLM) + " " + q(STAGE + "/bcmdhd_clm.blob") + " 2>/dev/null || true; "
                        + "chown -R wifi:wifi " + q(STAGE) + "; chmod 0755 " + q(STAGE) + "; chmod 0644 " + q(STAGE) + "/*; "
                        + "restorecon -RF " + q(STAGE) + " 2>&1; "
                        + "ls -ldZ " + q(STAGE) + "; ls -lZ " + q(STAGE) + "; sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1");";
                RootReader.Result prepR = RootReader.run(prep, 10);
                tr.append("\n=== STAGE NEXMON ===\ncode=").append(prepR.code).append(" timeout=").append(prepR.timedOut).append('\n').append(prepR.output);
                if (prepR.code != 0 || prepR.timedOut || !prepR.output.toLowerCase().contains(NexmonOneShotController.NEXMON_SHA.toLowerCase()))
                    throw new Exception("Falha preparando Nexmon staging.");

                postStatus("2/7 • Desligando Wi-Fi…");
                tr.append("\n=== WIFI OFF ===\n").append(MonitorController.wifi(false).output);
                Thread.sleep(1200);

                postStatus("3/7 • Carregando Samsung B1 Monitor…");
                tr.append("\n=== MODE MONITOR ===\n").append(MonitorController.setMode("monitor").output);
                tr.append("\n=== START MONITOR ===\n").append(MonitorController.startSamsungLoader().output);
                monitorConfirmed = MonitorController.waitForFirmware("B1 Monitor", 12);
                tr.append("\n").append(MonitorController.snapshot("MONITOR"));
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não confirmado.");

                postStatus("4/7 • firmware_class.path → Nexmon staging…");
                RootReader.Result set = RootReader.run("printf %s " + q(STAGE) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                customPathSet = set.code == 0 && !set.timedOut && STAGE.equals(set.output.trim());
                tr.append("\n=== SET FWCLASS ===\ncode=").append(set.code).append(" timeout=").append(set.timedOut).append('\n').append(set.output);
                tr.append("CUSTOM_PATH_SET=").append(customPathSet).append('\n');
                if (!customPathSet) throw new Exception("firmware_class.path não aceitou staging.");

                postStatus("5/7 • Voltando para STA/Nexmon…");
                tr.append("\n=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output);
                tr.append("\n=== START NORMAL ===\n").append(MonitorController.startSamsungLoader().output);
                Thread.sleep(700);
                tr.append("\n=== FIRMWARE LOG IMEDIATO ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'bcmdhd_sta.bin_b1|Request Firmware API|Falling back|error -13|firmware load' | tail -160", 8).output);
                tr.append("\n=== WIFI ON ===\n").append(MonitorController.wifi(true).output);

                postStatus("6/7 • Aguardando IOCTL 413…");
                String last = "";
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(1000);
                    RootReader.Result p = triage();
                    last = p.output;
                    if (last.contains("TRIAGE_RESULT=NEXMON_PRESENT")) {
                        nexmonDetected = true;
                        break;
                    }
                    if (last.contains("TRIAGE_BASE_IOCTL=SUPPORTED") && i >= 10) break;
                }
                tr.append("\n=== POST LOAD TRIAGE ===\n").append(last);
                tr.append("NEXMON_DETECTED=").append(nexmonDetected).append('\n');
                tr.append("\n=== POST LOAD WIFIVER ===\n").append(NexmonOneShotController.wifiver());
                tr.append("\n=== FIRMWARE LOG FINAL ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'bcmdhd_sta.bin_b1|Request Firmware API|Falling back|error -13|firmware load' | tail -220", 8).output);
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                postStatus("7/7 • Restaurando firmware_class.path…");
                String restore = originalPath == null ? "" : originalPath;
                RootReader.Result rr = RootReader.run("printf %s " + q(restore) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                tr.append("\n=== RESTORE FWCLASS ===\ncode=").append(rr.code).append(" timeout=").append(rr.timedOut).append('\n').append(rr.output);

                if (!nexmonDetected && !isStockRuntime()) {
                    tr.append("\n=== EMERGENCY STOCK RESTORE ===\n");
                    tr.append(MonitorController.setMode("normal").output);
                    tr.append(MonitorController.startSamsungLoader().output);
                    tr.append(MonitorController.wifi(true).output);
                    MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                } else {
                    MonitorController.wifi(true);
                }

                RootReader.Result finalTri = triage();
                boolean finalNex = finalTri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
                tr.append("\n=== FINAL TRIAGE ===\n").append(finalTri.output);
                tr.append("FINAL_NEXMON_413=").append(finalNex).append('\n');
                tr.append("FINAL_FWCLASS=").append(fwclassRead()).append('\n');
                tr.append("FINAL_SELINUX=").append(RootReader.run("getenforce 2>&1", 3).output.trim()).append('\n');
                tr.append("FINAL_STOCK_RUNTIME=").append(isStockRuntime()).append('\n');
                tr.append("MODULE_STATE_FINAL=").append(NexmonOneShotController.moduleState()).append('\n');
                RootReader.run("rm -rf " + q(STAGE), 5);
                nexmonDetected = finalNex;
            }

            try {
                createZip("BCM4375-NexStage-v4.1-result.zip", "v41-nexmon-load.txt", tr.toString());
            } catch (Exception e) {
                tr.append("ZIP_ERROR=").append(e.getMessage()).append('\n');
            }

            boolean ok = nexmonDetected;
            ui.post(() -> {
                busy = false;
                verify.setEnabled(true);
                run.setEnabled(false);
                save.setEnabled(zipFile != null && zipFile.isFile());
                status.setTextColor(ok ? 0xFF81C784 : 0xFFFFD180);
                status.setText(ok ? "NEXMON CONFIRMADO PELO IOCTL 413 • salve o ZIP" : "NEXMON NÃO CONFIRMADO • não repita; salve o ZIP");
                state.setText("nexmon_413=" + ok + "\nfwclass=" + fwclassRead()
                        + "\nSELinux=" + RootReader.run("getenforce 2>&1", 3).output.trim()
                        + "\nmodule=" + NexmonOneShotController.moduleState());
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
        final boolean root, stock, fwclass, base, present, nexFile, wifiPath, safeModule, ready;
        final String oldPath, moduleState, moduleLocation, triage, pathInfo, srcInfo;
        Snapshot(boolean root, boolean stock, boolean fwclass, String oldPath, boolean base, boolean present,
                 boolean nexFile, boolean wifiPath, boolean safeModule, String moduleState, String moduleLocation,
                 boolean ready, String triage, String pathInfo, String srcInfo) {
            this.root = root; this.stock = stock; this.fwclass = fwclass; this.oldPath = oldPath;
            this.base = base; this.present = present; this.nexFile = nexFile; this.wifiPath = wifiPath;
            this.safeModule = safeModule; this.moduleState = moduleState; this.moduleLocation = moduleLocation;
            this.ready = ready; this.triage = triage; this.pathInfo = pathInfo; this.srcInfo = srcInfo;
        }
    }
}
