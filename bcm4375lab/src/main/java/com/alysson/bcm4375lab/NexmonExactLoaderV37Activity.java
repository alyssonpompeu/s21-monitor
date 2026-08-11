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

public class NexmonExactLoaderV37Activity extends Activity {
    private static final int SAVE_ZIP = 4382;
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
        root.addView(text("v3.7 • exact Samsung ctl.start loader • BCM4375B1", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Primeiro verifique o estado. Não reinicie antes do teste.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + TRANSPORTE");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        run = button("2. EXECUTAR SEQUÊNCIA EXATA V2.1 → NEXMON");
        run.setEnabled(false); run.setOnClickListener(v -> confirmRun()); root.addView(run);
        rebootStock = button("3. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "O 101.zip provou que 'start mfgloader' não disparou a troca neste contexto. " +
                "A v2.1 que funcionou usava exatamente /system/bin/setprop ctl.start mfgloader, após svc wifi disable. " +
                "A v3.7 reproduz essa sequência para carregar primeiro Samsung B1 Monitor. Só depois de confirmar Monitor, " +
                "seleciona normal, dispara ctl.start novamente, religa o framework Wi-Fi e testa IOCTL 0/1/413. " +
                "O módulo permanece DISABLED para o próximo boot.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12)); root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); root.addView(output);
        return scroll;
    }

    private String probePath() { return getApplicationInfo().nativeLibraryDir + "/libnexprobe.so"; }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        RootReader.Result tri = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean base = tri.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean present = tri.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        boolean unsupported = tri.output.contains("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED");
        boolean vis = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean mod = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean stock = wv.contains("18.41.117") && wv.contains("B1 Network/rsdb");
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL)
                && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml)
                && vis && mod && stock && base && !present && unsupported;
        return new Snapshot(root, ms, ml, visibleSha, moduleSha, wv, tri.output, base, present, unsupported, stock, ready);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.7 exact ctl.start loader\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("stock_runtime=").append(s.stockRuntime).append('\n');
        r.append("base_ioctl_supported=").append(s.base).append('\n');
        r.append("nexmon_413_present=").append(s.present).append('\n');
        r.append("nexmon_413_unsupported=").append(s.unsupported).append('\n');
        r.append("ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== IOCTL TRIAGE ===\n").append(s.triage);
        r.append("\n=== FWPATH / PROPS ===\n").append(RootReader.run(
                "printf 'firmware_path='; cat /sys/module/dhd/parameters/firmware_path 2>&1; " +
                "echo; echo mode=$(getprop vendor.wlandriver.mode); echo status=$(getprop vendor.wlandriver.status); " +
                "echo wifi_on=$(settings get global wifi_on); ip link show wlan0 2>&1 | head -6", 7).output);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando estado atual…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-Lab-S21-v37-preflight.zip", "v37-preflight.txt", r);
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
            status.setTextColor(0xFF81C784); status.setText("NEXMON JÁ RESPONDE AO 413 • não execute a troca");
        } else if (s.ready && !attempted) {
            status.setTextColor(0xFF81C784); status.setText("PRONTO • sequência exata da v2.1 liberada");
        } else if (attempted) {
            status.setTextColor(0xFFFFD180); status.setText("TESTE JÁ EXECUTADO • não repita; salve o ZIP");
        } else {
            status.setTextColor(0xFFFFD180); status.setText("TESTE BLOQUEADO • pré-condições não conferem");
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
                .setTitle("Executar loader Samsung exato")
                .setMessage("O Wi-Fi será desligado. O app usará exatamente setprop ctl.start mfgloader, como na v2.1 que carregou B1 Monitor. Só após confirmar Monitor será tentado normal/STA com o overlay Nexmon. Execute uma única vez.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runExact())
                .show();
    }

    private void runExact() {
        if (busy || attempted) return;
        attempted = true;
        setBusy("Validando pré-condições…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            boolean monitorConfirmed = false;
            boolean normalAttempted = false;
            try {
                Snapshot before = snapshot();
                tr.append(report("before-exact-sequence", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; teste bloqueado.");

                String wifiState = RootReader.run("settings get global wifi_on", 4).output.trim();
                boolean wifiWasEnabled = "1".equals(wifiState) || "2".equals(wifiState);
                tr.append("wifi_on_before=").append(wifiState).append('\n');

                postStatus("1/7 • Desligando Wi-Fi como na v2.1…");
                RootReader.Result off = MonitorController.wifi(false);
                tr.append("\n=== WIFI OFF ===\ncode=").append(off.code).append(" timeout=").append(off.timedOut).append('\n').append(off.output);
                Thread.sleep(1200);

                postStatus("2/7 • mode=monitor…");
                RootReader.Result modeMon = MonitorController.setMode("monitor");
                tr.append("\n=== MODE MONITOR ===\ncode=").append(modeMon.code).append(" timeout=").append(modeMon.timedOut).append('\n').append(modeMon.output);
                if (modeMon.code != 0 || modeMon.timedOut) throw new Exception("Falha definindo monitor.");

                postStatus("3/7 • ctl.start mfgloader → B1 Monitor…");
                RootReader.Result monStart = MonitorController.startSamsungLoader();
                tr.append("\n=== CTL.START MONITOR ===\ncode=").append(monStart.code).append(" timeout=").append(monStart.timedOut).append('\n').append(monStart.output);
                if (monStart.code != 0 || monStart.timedOut) throw new Exception("ctl.start mfgloader falhou no Monitor.");

                monitorConfirmed = MonitorController.waitForFirmware("B1 Monitor", 12);
                tr.append("\n").append(MonitorController.snapshot("MONITOR ATTEMPT"));
                tr.append("MONITOR_CONFIRMED=").append(monitorConfirmed).append('\n');
                if (!monitorConfirmed) throw new Exception("B1 Monitor não apareceu; normal/Nexmon NÃO foi tentado.");

                postStatus("4/7 • mode=normal + ctl.start mfgloader…");
                RootReader.Result modeNormal = MonitorController.setMode("normal");
                tr.append("\n=== MODE NORMAL ===\ncode=").append(modeNormal.code).append(" timeout=").append(modeNormal.timedOut).append('\n').append(modeNormal.output);
                if (modeNormal.code != 0 || modeNormal.timedOut) throw new Exception("Falha definindo normal.");
                RootReader.Result normalStart = MonitorController.startSamsungLoader();
                normalAttempted = true;
                tr.append("\n=== CTL.START NORMAL ===\ncode=").append(normalStart.code).append(" timeout=").append(normalStart.timedOut).append('\n').append(normalStart.output);
                if (normalStart.code != 0 || normalStart.timedOut) throw new Exception("ctl.start mfgloader falhou no normal.");

                postStatus("5/7 • Religando framework Wi-Fi…");
                if (wifiWasEnabled) {
                    Thread.sleep(800);
                    RootReader.Result on = MonitorController.wifi(true);
                    tr.append("\n=== WIFI ON ===\ncode=").append(on.code).append(" timeout=").append(on.timedOut).append('\n').append(on.output);
                }

                postStatus("6/7 • Aguardando IOCTL 0/1/413…");
                String last = "";
                boolean baseReturned = false;
                boolean nexmon = false;
                for (int i = 0; i < 24; i++) {
                    Thread.sleep(1000);
                    RootReader.Result p = RootReader.run(q(probePath()) + " wlan0", 5);
                    last = p.output;
                    if (last.contains("TRIAGE_BASE_IOCTL=SUPPORTED")) baseReturned = true;
                    if (last.contains("TRIAGE_RESULT=NEXMON_PRESENT")) { nexmon = true; break; }
                    if (baseReturned && i >= 7) break;
                }
                tr.append("\n=== POST-NORMAL TRIAGE ===\n").append(last);
                tr.append("BASE_RETURNED=").append(baseReturned).append('\n');
                tr.append("NEXMON_DETECTED=").append(nexmon).append('\n');

                postStatus("7/7 • Coletando evidências…");
                tr.append("\n=== POST WIFIVER ===\n").append(NexmonOneShotController.wifiver());
                tr.append("\n=== POST FWPATH/STATUS ===\n").append(RootReader.run(
                        "printf 'firmware_path='; cat /sys/module/dhd/parameters/firmware_path 2>&1; echo; " +
                        "echo mode=$(getprop vendor.wlandriver.mode); echo status=$(getprop vendor.wlandriver.status); " +
                        "echo wifi_on=$(settings get global wifi_on); ip link show wlan0 2>&1 | head -8", 7).output);
                tr.append("\n=== DHD/INIT LOG ===\n").append(RootReader.run(
                        "dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor mode|mfgloader|request_firmware|pcie' | tail -1800", 16).output);
                tr.append("\nFINAL_RESULT=").append(nexmon ? "NEXMON_PRESENT" : (baseReturned ? "BASE_OK_413_UNSUPPORTED" : "NO_BASE_IOCTL_AFTER_NORMAL")).append('\n');

                persistEvidence(tr.toString());
                createZip("BCM4375-Lab-S21-v37-exact-loader-result.zip", "v37-exact-loader-result.txt", tr.toString());
                final boolean found = nexmon;
                ui.post(() -> {
                    busy = false; verify.setEnabled(true); run.setEnabled(false); rebootStock.setEnabled(true); save.setEnabled(true);
                    status.setTextColor(found ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(found ? "NEXMON CONFIRMADO PELO IOCTL 413 • salve o ZIP" : "SEQUÊNCIA CONCLUÍDA • salve o ZIP; não repita");
                    output.setText(tr.toString());
                });
            } catch (Exception e) {
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                tr.append("MONITOR_CONFIRMED_AT_FAILURE=").append(monitorConfirmed).append('\n');
                tr.append("NORMAL_ATTEMPTED_AT_FAILURE=").append(normalAttempted).append('\n');
                tr.append("\n=== FAILURE STATE ===\n").append(RootReader.run(
                        "echo mode=$(getprop vendor.wlandriver.mode); echo status=$(getprop vendor.wlandriver.status); " +
                        "printf 'firmware_path='; cat /sys/module/dhd/parameters/firmware_path 2>&1; echo; cat /sys/wifi/wifiver 2>&1", 7).output);
                tr.append("\n=== FAILURE DHD/INIT LOG ===\n").append(RootReader.run(
                        "dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor mode|mfgloader|request_firmware|pcie' | tail -1800", 16).output);
                try { persistEvidence(tr.toString()); createZip("BCM4375-Lab-S21-v37-exact-loader-failed.zip", "v37-exact-loader-failed.txt", tr.toString()); } catch (Exception ignored) {}
                ui.post(() -> {
                    busy = false; verify.setEnabled(true); run.setEnabled(false); rebootStock.setEnabled(true); save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(0xFFEF9A9A);
                    status.setText("TESTE INTERROMPIDO • NÃO REPITA • salve ZIP ou reinicie stock");
                    output.setText(tr.toString());
                });
            }
        });
    }

    private void persistEvidence(String body) {
        File tmp = new File(getCacheDir(), "v37-persist.txt");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { return; }
        RootReader.run("cp " + q(tmp.getAbsolutePath()) + " /data/adb/bcm4375_v37_last.txt 2>/dev/null; chmod 600 /data/adb/bcm4375_v37_last.txt 2>/dev/null", 6);
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O módulo já está DISABLED. O app reafirmará o desarme e reiniciará; o próximo boot não montará o overlay Nexmon.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Reiniciar stock", (d, w) -> worker.execute(() -> {
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void createZip(String name, String entryName, String body) throws Exception {
        File z = new File(getCacheDir(), name);
        if (z.exists()) z.delete();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(z))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(body.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        zipFile = z;
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip"); i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
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
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); run.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
    }

    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false; status.setTextColor(0xFFEF9A9A); status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            verify.setEnabled(true); rebootStock.setEnabled(true); save.setEnabled(zipFile != null && zipFile.isFile());
        });
    }

    private void postStatus(String s) { ui.post(() -> status.setText(s)); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); return b; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class Snapshot {
        final boolean root, base, present, unsupported, stockRuntime, ready;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver, triage;
        Snapshot(boolean root, String ms, String ml, String vs, String modSha, String wv, String triage,
                 boolean base, boolean present, boolean unsupported, boolean stockRuntime, boolean ready) {
            this.root=root; this.moduleState=ms; this.moduleLocation=ml; this.visibleSha=vs; this.moduleSha=modSha;
            this.wifiver=wv; this.triage=triage; this.base=base; this.present=present; this.unsupported=unsupported;
            this.stockRuntime=stockRuntime; this.ready=ready;
        }
    }
}
