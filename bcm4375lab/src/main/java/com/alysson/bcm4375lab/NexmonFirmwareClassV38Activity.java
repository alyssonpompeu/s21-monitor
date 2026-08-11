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

public class NexmonFirmwareClassV38Activity extends Activity {
    private static final int SAVE_ZIP = 4383;
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String NEXDIR = NexmonOneShotController.ACTIVE_DIR + "/system/vendor/firmware";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, state, output;
    private Button verify, run, rebootStock, save;
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

        root.addView(text("BCM4375 Lab", 28, Color.WHITE, true));
        root.addView(text("v3.8 • firmware_class.path → Nexmon • BCM4375B1", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Comece em boot stock limpo. Primeiro execute o preflight.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. PREFLIGHT FIRMWARE_CLASS + TRANSPORTE");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        run = button("2. MONITOR → NEXMON VIA FIRMWARE_CLASS.PATH");
        run.setEnabled(false); run.setOnClickListener(v -> confirmRun()); root.addView(run);
        rebootStock = button("3. REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "O 116.zip confirmou que o kernel fez request_firmware(bcmdhd_sta.bin_b1), porém carregou o Samsung stock mesmo quando /vendor mostrava o SHA Nexmon. " +
                "Esta versão não depende do overlay /vendor. Ela aponta temporariamente firmware_class.path diretamente para o arquivo real do módulo em /data/adb, " +
                "carrega primeiro B1 Monitor e só então tenta STA/Nexmon. O parâmetro firmware_class.path é restaurado em finally, inclusive em falha. " +
                "Nenhum arquivo físico de /vendor é alterado e SELinux permanece Enforcing.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12)); root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); root.addView(output);
        return scroll;
    }

    private String probePath() { return getApplicationInfo().nativeLibraryDir + "/libnexprobe.so"; }

    private String fwclassRead() {
        return RootReader.run("cat " + FWCLASS + " 2>&1", 4).output.trim();
    }

    private boolean fwclassWriteSame() {
        String old = fwclassRead();
        RootReader.Result r = RootReader.run("printf %s " + q(old) + " > " + FWCLASS + " && cat " + FWCLASS + " 2>&1", 5);
        return !r.timedOut && r.code == 0 && r.output.trim().equals(old);
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String currentSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        RootReader.Result tri = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean base = tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean present = tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        boolean unsupported = tri.output.contains("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED");
        boolean stock = wv.contains("18.41.117") && wv.contains("B1 Network/rsdb")
                && NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(currentSha);
        boolean moduleValid = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean fwclassExists = RootReader.run("test -e " + FWCLASS, 3).code == 0;
        boolean nexFile = RootReader.run("test -f " + q(NEXDIR + "/bcmdhd_sta.bin_b1") + " && test \"$(sha256sum " + q(NEXDIR + "/bcmdhd_sta.bin_b1") + " | cut -d' ' -f1)\" = " + q(NexmonOneShotController.NEXMON_SHA), 6).code == 0;
        String oldPath = fwclassExists ? fwclassRead() : "<missing>";
        boolean writeSame = fwclassExists && fwclassWriteSame();
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && stock && "DISABLED".equals(ms) && "ACTIVE".equals(ml)
                && moduleValid && nexFile && base && !present && unsupported
                && fwclassExists && writeSame;
        return new Snapshot(root, ms, ml, currentSha, moduleSha, wv, tri.output, base, present,
                unsupported, stock, moduleValid, nexFile, fwclassExists, writeSame, oldPath, ready);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.8 firmware_class.path loader\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("current_vendor_sha=").append(s.currentSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("stock_runtime=").append(s.stockRuntime).append('\n');
        r.append("module_valid=").append(s.moduleValid).append('\n');
        r.append("module_nexmon_file_ok=").append(s.nexFile).append('\n');
        r.append("base_ioctl_supported=").append(s.base).append('\n');
        r.append("nexmon_413_present=").append(s.present).append('\n');
        r.append("nexmon_413_unsupported=").append(s.unsupported).append('\n');
        r.append("fwclass_exists=").append(s.fwclassExists).append('\n');
        r.append("fwclass_write_same_ok=").append(s.writeSame).append('\n');
        r.append("fwclass_original=").append(s.oldPath).append('\n');
        r.append("ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== IOCTL TRIAGE ===\n").append(s.triage);
        r.append("\n=== MODULE FILE ===\n").append(RootReader.run("ls -laZ " + q(NEXDIR + "/bcmdhd_sta.bin_b1") + "; sha256sum " + q(NEXDIR + "/bcmdhd_sta.bin_b1"), 6).output);
        r.append("\n=== FWCLASS ===\n").append(RootReader.run("ls -lZ " + FWCLASS + " 2>&1; cat " + FWCLASS + " 2>&1", 5).output);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando stock, módulo, firmware_class.path e IOCTL…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-Lab-S21-v38-preflight.zip", "v38-preflight.txt", r);
                ui.post(() -> applySnapshot(s, r));
            } catch (Exception e) { fail("PREFLIGHT FALHOU", e); }
        });
    }

    private void applySnapshot(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        run.setEnabled(s.ready && !attempted);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        if (s.present) {
            status.setTextColor(0xFF81C784); status.setText("NEXMON JÁ RESPONDE AO 413 • não execute novamente");
        } else if (s.ready && !attempted) {
            status.setTextColor(0xFF81C784); status.setText("PRONTO • firmware_class.path gravável + stock limpo + módulo ec77 OK");
        } else if (attempted) {
            status.setTextColor(0xFFFFD180); status.setText("TESTE JÁ EXECUTADO • não repita; salve o ZIP");
        } else {
            status.setTextColor(0xFFFFD180); status.setText("BLOQUEADO • alguma pré-condição falhou");
        }
        state.setText("stock=" + s.stockRuntime + " module=" + s.moduleState + " @ " + s.moduleLocation +
                "\ncurrent_sha=" + s.currentSha +
                "\nmodule_sha=" + s.moduleSha +
                "\nfwclass_exists=" + s.fwclassExists + " write_same=" + s.writeSame +
                "\nbase_ioctl=" + s.base + " 413_present=" + s.present +
                "\nready=" + s.ready);
        output.setText(r);
    }

    private void confirmRun() {
        new AlertDialog.Builder(this)
                .setTitle("Carregar Nexmon via firmware_class.path")
                .setMessage("O Wi-Fi será reinicializado. Primeiro será confirmado Samsung B1 Monitor. Depois firmware_class.path apontará temporariamente para o arquivo Nexmon real em /data/adb e o DHD voltará para STA. O parâmetro será restaurado mesmo em falha. Execute uma única vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runTest())
                .show();
    }

    private void runTest() {
        if (busy || attempted) return;
        attempted = true;
        setBusy("Revalidando pré-condições…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            String originalPath = "";
            boolean monitorConfirmed = false;
            boolean nexmonDetected = false;
            boolean customPathSet = false;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-test", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; teste bloqueado.");
                originalPath = before.oldPath;

                postStatus("1/7 • Desligando Wi-Fi…");
                tr.append("\n=== WIFI OFF ===\n").append(MonitorController.wifi(false).output);
                Thread.sleep(1200);

                postStatus("2/7 • Carregando Samsung B1 Monitor…");
                RootReader.Result mm = MonitorController.setMode("monitor");
                tr.append("\n=== MODE MONITOR ===\ncode=").append(mm.code).append(" timeout=").append(mm.timedOut).append('\n').append(mm.output);
                RootReader.Result ms = MonitorController.startSamsungLoader();
                tr.append("\n=== CTL.START MONITOR ===\ncode=").append(ms.code).append(" timeout=").append(ms.timedOut).append('\n').append(ms.output);
                monitorConfirmed = MonitorController.waitForFirmware("B1 Monitor", 12);
                tr.append("\n").append(MonitorController.snapshot("MONITOR ATTEMPT"));
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não foi confirmado.");

                postStatus("3/7 • Apontando firmware_class.path para Nexmon…");
                RootReader.Result setPath = RootReader.run("printf %s " + q(NEXDIR) + " > " + FWCLASS + " && cat " + FWCLASS + " 2>&1", 6);
                tr.append("\n=== SET FWCLASS PATH ===\ncode=").append(setPath.code).append(" timeout=").append(setPath.timedOut).append('\n').append(setPath.output);
                customPathSet = !setPath.timedOut && setPath.code == 0 && NEXDIR.equals(setPath.output.trim());
                tr.append("CUSTOM_PATH_SET=").append(customPathSet).append('\n');
                if (!customPathSet) throw new Exception("firmware_class.path não aceitou o diretório Nexmon.");

                postStatus("4/7 • Voltando para normal/STA…");
                RootReader.Result mn = MonitorController.setMode("normal");
                tr.append("\n=== MODE NORMAL ===\ncode=").append(mn.code).append(" timeout=").append(mn.timedOut).append('\n').append(mn.output);
                RootReader.Result ns = MonitorController.startSamsungLoader();
                tr.append("\n=== CTL.START NORMAL ===\ncode=").append(ns.code).append(" timeout=").append(ns.timedOut).append('\n').append(ns.output);
                Thread.sleep(800);
                tr.append("\n=== WIFI ON ===\n").append(MonitorController.wifi(true).output);

                postStatus("5/7 • Aguardando DHD + IOCTL 0/1/413…");
                String last = "";
                boolean baseReturned = false;
                for (int i = 0; i < 28; i++) {
                    Thread.sleep(1000);
                    RootReader.Result p = RootReader.run(q(probePath()) + " wlan0", 5);
                    last = p.output;
                    if (last.contains("TRIAGE_BASE_IOCTL=SUPPORTED")) baseReturned = true;
                    if (last.contains("TRIAGE_RESULT=NEXMON_PRESENT")) { nexmonDetected = true; break; }
                    if (baseReturned && i >= 9) break;
                }
                tr.append("\n=== POST LOAD TRIAGE ===\n").append(last);
                tr.append("NEXMON_DETECTED=").append(nexmonDetected).append('\n');
                tr.append("\n=== POST WIFIVER ===\n").append(NexmonOneShotController.wifiver());

                postStatus("6/7 • Restaurando firmware_class.path…");
                RootReader.Result restore = RootReader.run("printf %s " + q(originalPath) + " > " + FWCLASS + " && cat " + FWCLASS + " 2>&1", 6);
                customPathSet = false;
                tr.append("\n=== RESTORE FWCLASS ===\ncode=").append(restore.code).append(" timeout=").append(restore.timedOut).append('\n').append(restore.output);
                tr.append("FWCLASS_RESTORED=").append(restore.code == 0 && restore.output.trim().equals(originalPath)).append('\n');

                postStatus("7/7 • Coletando evidências…");
                tr.append("\n=== FINAL IOCTL ===\n").append(RootReader.run(q(probePath()) + " wlan0", 6).output);
                tr.append("\n=== DHD/FIRMWARE LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd_bus_download_firmware|download firmware|Request Firmware API|Firmware version|firmware_class|bcmdhd|nexmon|4375|monitor' | tail -1800", 16).output);
                tr.append("\n=== AVC ===\n").append(RootReader.run("dmesg | grep -iE 'avc:.*denied|firmware_class' | tail -500", 8).output);
                tr.append("\n=== FINAL FWCLASS ===\n").append(RootReader.run("cat " + FWCLASS + " 2>&1; getenforce 2>&1", 4).output);

                createZip("BCM4375-Lab-S21-v38-firmware-class-result.zip", "v38-firmware-class-result.txt", tr.toString());
                final boolean found = nexmonDetected;
                ui.post(() -> {
                    busy = false; verify.setEnabled(true); run.setEnabled(false); rebootStock.setEnabled(true); save.setEnabled(true);
                    status.setTextColor(found ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(found ? "NEXMON CONFIRMADO PELO 413 • salve o ZIP" : "TESTE CONCLUÍDO • Nexmon não confirmado; salve o ZIP");
                    output.setText(tr.toString());
                });
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                if (customPathSet || !originalPath.isEmpty()) {
                    RootReader.run("printf %s " + q(originalPath) + " > " + FWCLASS, 5);
                } else {
                    RootReader.run(": > " + FWCLASS, 5);
                }
                tr.append("\n=== RECOVERY: RESTORE FWCLASS ===\n").append(RootReader.run("cat " + FWCLASS + " 2>&1", 4).output);
                RootReader.run("setprop vendor.wlandriver.mode normal; /system/bin/setprop ctl.start mfgloader; sleep 1; svc wifi enable", 12);
                tr.append("\n=== RECOVERY WIFIVER ===\n").append(RootReader.run("sleep 4; cat /sys/wifi/wifiver 2>&1", 8).output);
                tr.append("\n=== FAIL LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd_bus_download_firmware|download firmware|Request Firmware API|Firmware version|firmware_class|bcmdhd|nexmon|4375|monitor|avc:.*denied' | tail -1800", 16).output);
                try { createZip("BCM4375-Lab-S21-v38-firmware-class-failure.zip", "v38-failure.txt", tr.toString()); } catch (Exception ignored) {}
                final String body = tr.toString();
                ui.post(() -> {
                    busy = false; verify.setEnabled(true); run.setEnabled(false); rebootStock.setEnabled(true); save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(0xFFEF9A9A); status.setText("TESTE INTERROMPIDO • não repita; salve o ZIP"); output.setText(body);
                });
            } finally {
                if (customPathSet) {
                    RootReader.run("printf %s " + q(originalPath) + " > " + FWCLASS, 5);
                }
            }
        });
    }

    private void confirmStockReboot() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para stock")
                .setMessage("Restaura firmware_class.path para vazio, mantém o módulo Nexmon desativado e reinicia. O próximo boot deverá usar Samsung stock.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Reiniciar stock", (d,w) -> worker.execute(() -> {
                    RootReader.run(": > " + FWCLASS, 5);
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void setBusy(String s) {
        busy = true; ui.post(() -> { status.setTextColor(0xFFFFD180); status.setText(s); verify.setEnabled(false); run.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false); });
    }
    private void postStatus(String s) { ui.post(() -> status.setText(s)); }
    private void fail(String title, Exception e) { ui.post(() -> { busy=false; status.setTextColor(0xFFEF9A9A); status.setText(title); output.setText(e.getClass().getSimpleName()+": "+e.getMessage()); verify.setEnabled(true); rebootStock.setEnabled(true); }); }

    private void createZip(String zipName, String entry, String body) throws Exception {
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(z))) {
            zos.putNextEntry(new ZipEntry(entry)); zos.write(body.getBytes(StandardCharsets.UTF_8)); zos.closeEntry();
        }
        zipFile = z;
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/zip"); i.putExtra(Intent.EXTRA_TITLE, zipFile.getName()); startActivityForResult(i, SAVE_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null) return;
        Uri u = data.getData(); if (u == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(u)) {
                if (out == null) throw new Exception("output stream nulo"); byte[] b = new byte[65536]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Falha salvando: "+e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private TextView text(String s, int sp, int c, boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private static String q(String s) { return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'"; }

    private static final class Snapshot {
        final boolean root, base, present, unsupported, stockRuntime, moduleValid, nexFile, fwclassExists, writeSame, ready;
        final String moduleState, moduleLocation, currentSha, moduleSha, wifiver, triage, oldPath;
        Snapshot(boolean root, String moduleState, String moduleLocation, String currentSha, String moduleSha,
                 String wifiver, String triage, boolean base, boolean present, boolean unsupported, boolean stockRuntime,
                 boolean moduleValid, boolean nexFile, boolean fwclassExists, boolean writeSame, String oldPath, boolean ready) {
            this.root=root; this.moduleState=moduleState; this.moduleLocation=moduleLocation; this.currentSha=currentSha; this.moduleSha=moduleSha;
            this.wifiver=wifiver; this.triage=triage; this.base=base; this.present=present; this.unsupported=unsupported; this.stockRuntime=stockRuntime;
            this.moduleValid=moduleValid; this.nexFile=nexFile; this.fwclassExists=fwclassExists; this.writeSame=writeSame; this.oldPath=oldPath; this.ready=ready;
        }
    }
}
