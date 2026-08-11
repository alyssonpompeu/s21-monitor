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

public class NexmonProbeActivity extends Activity {
    private static final int SAVE_ZIP = 4378;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button verify, probe, permissiveProbe, rebootStock, save;
    private volatile boolean busy;
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
        root.addView(text("v3.2 • Nexmon IOCTL 413 probe • BCM4375B1", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Primeiro confirme que o overlay ec77... continua ativo neste boot.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + RUNTIME");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        probe = button("2. PROBE NEXMON IOCTL 413 (ENFORCING)");
        probe.setEnabled(false); probe.setOnClickListener(v -> runProbe(false)); root.addView(probe);
        permissiveProbe = button("3. PROBE 413 • PERMISSIVE TEMPORÁRIO");
        permissiveProbe.setEnabled(false); permissiveProbe.setOnClickListener(v -> confirmPermissiveProbe()); root.addView(permissiveProbe);
        rebootStock = button("4. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "O Nexmon oficial define NEX_GET_VERSION_STRING como IOCTL 413. Este app executa um probe nativo mínimo contra wlan0. " +
                "O botão 3 só existe porque BCM4375B1/Nexutil pode ser bloqueado por SELinux: ele cria um watchdog que restaura Enforcing, " +
                "coloca SELinux em Permissive apenas durante o probe e força Enforcing novamente em seguida. Nenhum firmware é recarregado nesta versão.",
                12, 0xFFB0BEC5, false);
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
        String se = RootReader.run("getenforce 2>&1", 3).output.trim();
        boolean visibleNexmon = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean moduleNexmon = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml) && visibleNexmon && moduleNexmon;
        return new Snapshot(root, ms, ml, visibleSha, moduleSha, wv, se, ready);
    }

    private String probePath() {
        return getApplicationInfo().nativeLibraryDir + "/libnexprobe.so";
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando estado atual…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                StringBuilder r = new StringBuilder(report("preflight", s));
                r.append("\n=== PROBE BINARY ===\n")
                        .append(RootReader.run("ls -lZ " + q(probePath()) + " 2>&1; file " + q(probePath()) + " 2>&1", 6).output);
                createZip("BCM4375-Lab-v32-preflight.zip", "v32-preflight.txt", r.toString());
                ui.post(() -> applySnapshot(s, r.toString()));
            } catch (Exception e) { fail("VERIFICAÇÃO FALHOU", e); }
        });
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.2 Nexmon IOCTL probe\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("probe_ready=").append(s.ready).append('\n');
        r.append("selinux=").append(s.selinux).append('\n');
        r.append("probe_path=").append(probePath()).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== FIRMWARE_PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        return r.toString();
    }

    private void applySnapshot(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        probe.setEnabled(s.ready);
        permissiveProbe.setEnabled(s.ready);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        if (s.ready) {
            status.setTextColor(0xFF81C784);
            status.setText("PRONTO • overlay Nexmon ec77... ativo e módulo DISABLED");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("PROBE BLOQUEADO • o estado atual não bate com o one-shot protegido");
        }
        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nselinux=" + s.selinux +
                "\nprobe_ready=" + s.ready);
        output.setText(r);
    }

    private void runProbe(boolean temporaryPermissive) {
        if (busy) return;
        setBusy(temporaryPermissive ? "Executando probe 413 com Permissive temporário…" : "Executando probe 413 com SELinux atual…");
        worker.execute(() -> {
            StringBuilder r = new StringBuilder();
            try {
                Snapshot before = snapshot();
                r.append(report(temporaryPermissive ? "before-permissive-probe" : "before-enforcing-probe", before));
                if (!before.ready) throw new Exception("Pré-condições do probe não conferem.");

                if (temporaryPermissive) {
                    RootReader.Result armWatchdog = RootReader.run("(sleep 8; setenforce 1) >/dev/null 2>&1 & setenforce 0; getenforce", 4);
                    r.append("\n=== TEMP PERMISSIVE ENTER ===\n").append(armWatchdog.output);
                    if (!armWatchdog.output.toLowerCase().contains("permissive"))
                        throw new Exception("Não foi possível confirmar SELinux Permissive.");
                }

                RootReader.Result p;
                try {
                    p = RootReader.run(q(probePath()) + " wlan0", 5);
                } finally {
                    if (temporaryPermissive) RootReader.run("setenforce 1; getenforce", 4);
                }

                r.append("\n=== NEXPROBE 413 ===\ncode=").append(p.code).append(" timeout=").append(p.timedOut).append('\n').append(p.output);
                r.append("\n=== SELINUX AFTER ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
                r.append("\n=== AVC/DHD TAIL ===\n").append(RootReader.run("dmesg | grep -iE 'avc:|denied|dhd|nexmon|ioctl|4375' | tail -420", 10).output);
                Snapshot after = snapshot();
                r.append("\n=== AFTER ===\n").append(report("after-probe", after));

                String zipName = temporaryPermissive ? "BCM4375-Lab-S21-v32-probe413-permissive.zip" : "BCM4375-Lab-S21-v32-probe413-enforcing.zip";
                createZip(zipName, "probe413.txt", r.toString());
                boolean present = p.output.contains("NEXPROBE_RESULT=NEXMON_PRESENT");
                ui.post(() -> {
                    applySnapshot(after, r.toString());
                    save.setEnabled(true);
                    status.setTextColor(present ? 0xFF81C784 : 0xFFFFD180);
                    status.setText(present ? "NEXMON CONFIRMADO PELO IOCTL 413 • salve o ZIP" : "IOCTL 413 NÃO CONFIRMOU NEXMON • salve o ZIP");
                });
            } catch (Exception e) {
                if (temporaryPermissive) RootReader.run("setenforce 1", 3);
                r.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                r.append("SELINUX_FINAL=").append(RootReader.run("getenforce 2>&1", 3).output);
                try { createZip("BCM4375-Lab-S21-v32-probe413-failed.zip", "probe413-failed.txt", r.toString()); } catch (Exception ignored) {}
                fail("PROBE FALHOU", e);
            }
        });
    }

    private void confirmPermissiveProbe() {
        new AlertDialog.Builder(this)
                .setTitle("Permissive temporário")
                .setMessage("SELinux ficará Permissive apenas durante o probe 413. Um watchdog tenta restaurar Enforcing em 8 segundos e o app também restaura imediatamente após o comando. Nenhum firmware será alterado ou recarregado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar probe", (d, w) -> runProbe(true))
                .show();
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O app reafirmará disable e reiniciará. O próximo boot não montará o overlay Nexmon.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    RootReader.run("setenforce 1", 3);
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                })).show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v32-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) { fos.write(body.getBytes(StandardCharsets.UTF_8)); }
        List<File> files = new ArrayList<>(); files.add(report);
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete(); ExportUtil.zip(files, z); zipFile = z;
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); probe.setEnabled(false); permissiveProbe.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
    }
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
        final boolean root, ready;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver, selinux;
        Snapshot(boolean root, String ms, String ml, String vs, String modSha, String wv, String se, boolean ready) {
            this.root=root; this.moduleState=ms; this.moduleLocation=ml; this.visibleSha=vs; this.moduleSha=modSha; this.wifiver=wv; this.selinux=se; this.ready=ready;
        }
    }
}
