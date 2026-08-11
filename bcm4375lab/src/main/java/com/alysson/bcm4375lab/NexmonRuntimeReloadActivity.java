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
    private static final int SAVE_ZIP = 4380;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button verify, reload, save, rebootStock;
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
        root.addView(text("v3.4 • runtime DHD reload + IOCTL 0→1→413", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Verifique o estado antes do reload.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + TRANSPORTE");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        reload = button("2. RECARREGAR DHD COM OVERLAY NEXMON");
        reload.setEnabled(false); reload.setOnClickListener(v -> confirmReload()); root.addView(reload);
        rebootStock = button("3. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "O reload só é liberado se o módulo one-shot estiver DISABLED @ ACTIVE, o arquivo visível em /vendor tiver o SHA Nexmon ec77..., " +
                "WLC_GET_MAGIC/WLC_GET_VERSION funcionarem e o comando Nexmon 413 ainda estiver ausente. O app usa o próprio mfgloader Samsung para reinicializar o DHD. " +
                "Depois do reload repete 0→1→413. Não altera SELinux e não escreve fisicamente em /vendor.",
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
        RootReader.Result id = RootReader.run("id", 6);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        String se = RootReader.run("getenforce 2>&1", 3).output.trim();
        RootReader.Result triage = RootReader.run(q(probePath()) + " wlan0", 6);
        boolean baseOk = triage.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED");
        boolean nexPresent = triage.output.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        boolean nexUnsupported = triage.output.contains("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED");
        boolean visibleNex = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean moduleNex = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml) && visibleNex && moduleNex && baseOk && nexUnsupported;
        return new Snapshot(root, ms, ml, visibleSha, moduleSha, wv, se, triage.output, baseOk, nexPresent, nexUnsupported, ready);
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando overlay, hashes e IOCTLs…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String r = report("preflight", s);
                createZip("BCM4375-Lab-S21-v34-preflight.zip", "v34-preflight.txt", r);
                ui.post(() -> applySnapshot(s, r));
            } catch (Exception e) { fail("VERIFICAÇÃO FALHOU", e); }
        });
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.4 runtime reload\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("selinux=").append(s.selinux).append('\n');
        r.append("base_ioctl_supported=").append(s.baseOk).append('\n');
        r.append("nexmon_413_present=").append(s.nexPresent).append('\n');
        r.append("nexmon_413_unsupported=").append(s.nexUnsupported).append('\n');
        r.append("reload_ready=").append(s.ready).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== IOCTL TRIAGE ===\n").append(s.triage);
        r.append("\n=== FIRMWARE_PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        r.append("\n=== WLAN PROPERTIES ===\n").append(RootReader.run("getprop | grep -iE 'vendor\\.wlandriver|wifi' | head -180", 6).output);
        return r.toString();
    }

    private void applySnapshot(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        reload.setEnabled(s.ready && !reloadAttempted);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        if (s.nexPresent) {
            status.setTextColor(0xFF81C784);
            status.setText("NEXMON JÁ CONFIRMADO PELO IOCTL 413 • não faça reload");
        } else if (s.ready && !reloadAttempted) {
            status.setTextColor(0xFF81C784);
            status.setText("PRONTO • transporte OK, 413 ausente, overlay Nexmon montado");
        } else if (reloadAttempted) {
            status.setTextColor(0xFFFFD180);
            status.setText("RELOAD JÁ EXECUTADO • não repita; salve o ZIP");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("RELOAD BLOQUEADO • pré-condições não conferem");
        }
        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nbase_ioctl=" + s.baseOk +
                "\n413_present=" + s.nexPresent +
                "\n413_unsupported=" + s.nexUnsupported +
                "\nready=" + s.ready);
        output.setText(r);
    }

    private void confirmReload() {
        new AlertDialog.Builder(this)
                .setTitle("Reload runtime do BCM4375")
                .setMessage("O Wi-Fi cairá temporariamente. O app usa o serviço mfgloader Samsung em modo normal/STA, religa o Wi-Fi e então testa IOCTL 0→1→413. Faça apenas uma tentativa.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar uma vez", (d, w) -> runReload())
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

                postStatus("3/5 • Acionando mfgloader Samsung…");
                RootReader.Result loader = RootReader.run("start mfgloader; sleep 4; getprop vendor.wlandriver.status", 12);
                tr.append("\n=== MFGLOADER ===\ncode=").append(loader.code).append(" timeout=").append(loader.timedOut).append('\n').append(loader.output);

                postStatus("4/5 • Religando Wi-Fi…");
                RootReader.Result on = RootReader.run("svc wifi enable", 6);
                tr.append("\n=== WIFI ON ===\ncode=").append(on.code).append(" timeout=").append(on.timedOut).append('\n').append(on.output);

                postStatus("5/5 • Aguardando DHD e testando 0→1→413…");
                String lastProbe = "";
                boolean baseReturned = false;
                boolean nexmonDetected = false;
                for (int i = 0; i < 18; i++) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    RootReader.Result p = RootReader.run(q(probePath()) + " wlan0", 6);
                    lastProbe = p.output;
                    if (p.output.contains("TRIAGE_BASE_IOCTL=SUPPORTED")) baseReturned = true;
                    if (p.output.contains("TRIAGE_RESULT=NEXMON_PRESENT")) { nexmonDetected = true; break; }
                    if (baseReturned && i >= 4 && p.output.contains("BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED")) break;
                }

                Snapshot after = snapshot();
                tr.append("\n=== LAST POST-RELOAD TRIAGE ===\n").append(lastProbe);
                tr.append("\nBASE_RETURNED=").append(baseReturned).append('\n');
                tr.append("NEXMON_DETECTED=").append(nexmonDetected).append('\n');
                tr.append("\n=== AFTER RELOAD ===\n").append(report("after-reload", after));
                tr.append("\n=== DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -1000", 12).output);
                tr.append("\n=== INTERFACES ===\n").append(RootReader.run("cat /proc/net/wireless 2>&1; echo ---; cat /sys/class/net/wlan0/type 2>&1; echo ---; cat /sys/class/net/wlan0/flags 2>&1", 6).output);

                createZip("BCM4375-Lab-S21-v34-runtime-reload-result.zip", "v34-runtime-reload-result.txt", tr.toString());
                final boolean detectedFinal = nexmonDetected || after.nexPresent;
                ui.post(() -> {
                    applySnapshot(after, tr.toString());
                    reload.setEnabled(false);
                    save.setEnabled(true);
                    status.setTextColor(detectedFinal ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(detectedFinal
                            ? "NEXMON CONFIRMADO APÓS RELOAD • salve o ZIP"
                            : "RELOAD CONCLUÍDO • 413 ainda não confirmou Nexmon; salve o ZIP");
                });
            } catch (Exception e) {
                RootReader.run("svc wifi enable", 5);
                tr.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                tr.append("\n=== FAIL DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -800", 10).output);
                try { createZip("BCM4375-Lab-S21-v34-runtime-reload-failed.zip", "v34-runtime-reload-failed.txt", tr.toString()); } catch (Exception ignored) {}
                fail("RELOAD FALHOU • não repita; salve o ZIP", e);
            }
        });
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O app reafirmará disable no módulo e reiniciará. O próximo boot não deve montar o overlay Nexmon.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    RootReader.run("setenforce 1", 3);
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v34-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) { fos.write(body.getBytes(StandardCharsets.UTF_8)); }
        List<File> files = new ArrayList<>(); files.add(report);
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete(); ExportUtil.zip(files, z); zipFile = z;
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); reload.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
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
        final boolean root, baseOk, nexPresent, nexUnsupported, ready;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver, selinux, triage;
        Snapshot(boolean root, String ms, String ml, String vs, String modSha, String wv, String se,
                 String triage, boolean baseOk, boolean nexPresent, boolean nexUnsupported, boolean ready) {
            this.root = root; this.moduleState = ms; this.moduleLocation = ml; this.visibleSha = vs; this.moduleSha = modSha;
            this.wifiver = wv; this.selinux = se; this.triage = triage; this.baseOk = baseOk; this.nexPresent = nexPresent;
            this.nexUnsupported = nexUnsupported; this.ready = ready;
        }
    }
}
