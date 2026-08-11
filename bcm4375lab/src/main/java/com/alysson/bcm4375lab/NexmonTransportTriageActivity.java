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

public class NexmonTransportTriageActivity extends Activity {
    private static final int SAVE_ZIP = 4379;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button verify, triage, rebootStock, save;
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
        root.addView(text("v3.3 • Broadcom transport triage • 0 / 1 / 413", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("Primeiro confirme o estado protegido do overlay.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE); state.setPadding(0, 0, 0, dp(12)); root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + RUNTIME");
        verify.setOnClickListener(v -> verifyState()); root.addView(verify);
        triage = button("2. TESTAR IOCTL 0 → 1 → 413");
        triage.setEnabled(false); triage.setOnClickListener(v -> runTriage()); root.addView(triage);
        rebootStock = button("3. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot()); root.addView(rebootStock);
        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false); save.setOnClickListener(v -> saveZip()); root.addView(save);

        TextView note = text(
                "Este teste não recarrega firmware e não muda SELinux. O mesmo SIOCDEVPRIVATE consulta WLC_GET_MAGIC (0), " +
                "WLC_GET_VERSION (1) e NEX_GET_VERSION_STRING (413). Se 0/1 também falharem com EOPNOTSUPP, o caminho privado " +
                "do driver Samsung não é utilizável e o próximo passo será nl80211 vendor command. Se 0/1 funcionarem e 413 falhar, " +
                "há evidência forte de que o runtime atual não implementa o patch Nexmon.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12)); root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true); root.addView(output);
        return scroll;
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean rootOk = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String ms = NexmonOneShotController.moduleState();
        String ml = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wv = NexmonOneShotController.wifiver();
        String se = RootReader.run("getenforce 2>&1", 3).output.trim();
        boolean ready = rootOk && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && "DISABLED".equals(ms) && "ACTIVE".equals(ml)
                && NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha)
                && NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha)
                && "Enforcing".equalsIgnoreCase(se);
        return new Snapshot(rootOk, ms, ml, visibleSha, moduleSha, wv, se, ready);
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
                String r = report("preflight", s);
                createZip("BCM4375-Lab-v33-preflight.zip", "v33-preflight.txt", r);
                ui.post(() -> applyState(s, r));
            } catch (Exception e) { fail("VERIFICAÇÃO FALHOU", e); }
        });
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.3 transport triage\nphase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("selinux=").append(s.selinux).append('\n');
        r.append("triage_ready=").append(s.ready).append('\n');
        r.append("probe_path=").append(probePath()).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== FIRMWARE_PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        return r.toString();
    }

    private void applyState(Snapshot s, String r) {
        busy = false;
        verify.setEnabled(true);
        triage.setEnabled(s.ready);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());
        status.setTextColor(s.ready ? 0xFF81C784 : 0xFFFFD180);
        status.setText(s.ready ? "PRONTO • execute o triage 0 → 1 → 413" : "TRIAGE BLOQUEADO • estado protegido não confere");
        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nselinux=" + s.selinux +
                "\ntriage_ready=" + s.ready);
        output.setText(r);
    }

    private void runTriage() {
        if (busy) return;
        setBusy("Executando WLC_GET_MAGIC → WLC_GET_VERSION → NEX 413…");
        worker.execute(() -> {
            StringBuilder r = new StringBuilder();
            try {
                Snapshot before = snapshot();
                r.append(report("before-triage", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; triage bloqueado.");

                RootReader.Result p = RootReader.run(q(probePath()) + " wlan0", 8);
                r.append("\n=== PRIVATE IOCTL TRIAGE ===\ncode=").append(p.code)
                        .append(" timeout=").append(p.timedOut).append('\n').append(p.output);
                r.append("\n=== SELINUX AFTER ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
                r.append("\n=== DHD/IOCTL TAIL ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'dhd|ioctl|EOPNOTSUPP|nexmon|4375' | tail -500", 10).output);

                Snapshot after = snapshot();
                r.append("\n=== AFTER ===\n").append(report("after-triage", after));
                createZip("BCM4375-Lab-S21-v33-transport-triage.zip", "transport-triage.txt", r.toString());

                String result;
                int color;
                if (p.output.contains("TRIAGE_RESULT=PRIVATE_IOCTL_TRANSPORT_UNSUPPORTED")) {
                    result = "TRANSPORTE SIOCDEVPRIVATE NÃO SUPORTADO • salve o ZIP";
                    color = 0xFFFFD180;
                } else if (p.output.contains("TRIAGE_RESULT=BASE_IOCTL_OK_NEXMON_413_UNSUPPORTED")) {
                    result = "IOCTL BASE FUNCIONA, 413 NÃO • salve o ZIP";
                    color = 0xFFFFD180;
                } else if (p.output.contains("TRIAGE_RESULT=NEXMON_PRESENT")) {
                    result = "NEXMON CONFIRMADO PELO 413 • salve o ZIP";
                    color = 0xFF81C784;
                } else {
                    result = "RESULTADO INDETERMINADO • salve o ZIP";
                    color = 0xFFFFD180;
                }
                ui.post(() -> {
                    applyState(after, r.toString());
                    save.setEnabled(true);
                    status.setTextColor(color);
                    status.setText(result);
                });
            } catch (Exception e) {
                r.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                try { createZip("BCM4375-Lab-S21-v33-transport-triage-failed.zip", "transport-triage-failed.txt", r.toString()); } catch (Exception ignored) {}
                fail("TRIAGE FALHOU", e);
            }
        });
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
        File dir = new File(getCacheDir(), "v33-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) { fos.write(body.getBytes(StandardCharsets.UTF_8)); }
        List<File> files = new ArrayList<>(); files.add(report);
        File z = new File(getCacheDir(), zipName); if (z.exists()) z.delete(); ExportUtil.zip(files, z); zipFile = z;
    }

    private void setBusy(String msg) {
        busy = true; status.setTextColor(0xFFFFD180); status.setText(msg);
        verify.setEnabled(false); triage.setEnabled(false); rebootStock.setEnabled(false); save.setEnabled(false);
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
        Snapshot(boolean root, String ms, String ml, String visibleSha, String moduleSha, String wifiver, String selinux, boolean ready) {
            this.root = root; this.moduleState = ms; this.moduleLocation = ml; this.visibleSha = visibleSha;
            this.moduleSha = moduleSha; this.wifiver = wifiver; this.selinux = selinux; this.ready = ready;
        }
    }
}
