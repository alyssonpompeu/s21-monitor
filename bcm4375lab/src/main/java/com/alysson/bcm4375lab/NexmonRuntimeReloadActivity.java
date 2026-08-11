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

    private TextView status;
    private TextView state;
    private TextView output;
    private Button verify;
    private Button reload;
    private Button collect;
    private Button rebootStock;
    private Button save;
    private volatile boolean busy;
    private File zipFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
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
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Verifique o estado antes do reload. Nenhum reboot é necessário para o teste.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        verify = button("1. VERIFICAR OVERLAY + DHD ATUAL");
        verify.setOnClickListener(v -> verifyState());
        root.addView(verify);

        reload = button("2. RECARREGAR DHD COM OVERLAY NEXMON");
        reload.setEnabled(false);
        reload.setOnClickListener(v -> confirmReload());
        root.addView(reload);

        collect = button("3. COLETAR RESULTADO DO RELOAD");
        collect.setEnabled(false);
        collect.setOnClickListener(v -> collectEvidence("manual-collect"));
        root.addView(collect);

        rebootStock = button("4. DESARMAR + REINICIAR PARA STOCK");
        rebootStock.setOnClickListener(v -> confirmStockReboot());
        root.addView(rebootStock);

        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "Pré-condição rígida: módulo guard2 deve estar DISABLED @ ACTIVE, o arquivo visível em /vendor/firmware deve ter SHA Nexmon ec77..., " +
                "e o DHD deve ainda reportar o firmware Samsung B1 Network. O teste desliga o Wi-Fi do framework, seleciona vendor.wlandriver.mode=normal, " +
                "aciona o mfgloader Samsung, religa o Wi-Fi e coleta wifiver/dmesg. Se o Wi-Fi não voltar, use o botão STOCK: o módulo já permanece desativado para o próximo boot.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private void verifyState() {
        if (busy) return;
        setBusy("Verificando overlay e firmware ativo…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String report = report("preflight", s);
                createZip("BCM4375-Lab-v31-runtime-preflight.zip", "runtime-preflight.txt", report);
                ui.post(() -> applySnapshot(s, report));
            } catch (Exception e) {
                fail("VERIFICAÇÃO FALHOU", e);
            }
        });
    }

    private Snapshot snapshot() {
        RootReader.Result id = RootReader.run("id", 8);
        boolean root = !id.timedOut && id.code == 0 && id.output.contains("uid=0");
        String moduleState = NexmonOneShotController.moduleState();
        String moduleLocation = NexmonOneShotController.moduleLocation();
        String visibleSha = NexmonOneShotController.currentFirmwareSha();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        String wifiver = NexmonOneShotController.wifiver();
        boolean stockRuntime = wifiver.contains("18.41.117") && wifiver.contains("B1 Network/rsdb");
        boolean nexmonRuntime = NexmonOneShotController.isNexmonActive();
        boolean guard2 = "DISABLED".equals(moduleState) || "ARMED".equals(moduleState) || "REMOVE_PENDING".equals(moduleState);
        boolean visibleNexmon = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(visibleSha);
        boolean moduleNexmon = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        boolean ready = root && "SM-G991B".equalsIgnoreCase(Build.MODEL) && "exynos2100".equalsIgnoreCase(Build.HARDWARE)
                && guard2 && "DISABLED".equals(moduleState) && "ACTIVE".equals(moduleLocation)
                && visibleNexmon && moduleNexmon && stockRuntime && !nexmonRuntime;
        return new Snapshot(root, moduleState, moduleLocation, visibleSha, moduleSha, wifiver,
                stockRuntime, nexmonRuntime, visibleNexmon, moduleNexmon, ready);
    }

    private String report(String phase, Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.1 runtime reload\n");
        r.append("phase=").append(phase).append('\n');
        r.append("root=").append(s.root).append('\n');
        r.append("model=").append(Build.MODEL).append('\n');
        r.append("hardware=").append(Build.HARDWARE).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("visible_vendor_sha=").append(s.visibleSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("visible_nexmon_sha=").append(s.visibleNexmon).append('\n');
        r.append("module_nexmon_sha=").append(s.moduleNexmon).append('\n');
        r.append("stock_runtime=").append(s.stockRuntime).append('\n');
        r.append("nexmon_runtime=").append(s.nexmonRuntime).append('\n');
        r.append("runtime_reload_ready=").append(s.ready).append('\n');
        r.append("expected_stock_sha=").append(NexmonOneShotController.STOCK_SHA).append('\n');
        r.append("expected_nexmon_sha=").append(NexmonOneShotController.NEXMON_SHA).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== FW PATH ===\n").append(RootReader.run("cat /sys/module/dhd/parameters/firmware_path 2>&1", 4).output);
        r.append("\n=== WLAN PROPS ===\n").append(RootReader.run("getprop | grep -iE 'vendor\\.wlandriver|wifi' | head -160", 6).output);
        r.append("\n=== MOUNTINFO VENDOR FIRMWARE ===\n").append(RootReader.run("cat /proc/self/mountinfo | grep -E '/vendor($|/firmware)|bcm4375_nexmon' | tail -120", 6).output);
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void applySnapshot(Snapshot s, String report) {
        busy = false;
        verify.setEnabled(true);
        reload.setEnabled(s.ready);
        collect.setEnabled(true);
        rebootStock.setEnabled(true);
        save.setEnabled(zipFile != null && zipFile.isFile());

        if (s.ready) {
            status.setTextColor(0xFF81C784);
            status.setText("PRONTO • overlay Nexmon ativo, DHD ainda stock, módulo já DISABLED");
        } else if (s.nexmonRuntime) {
            status.setTextColor(0xFF81C784);
            status.setText("NEXMON JÁ ESTÁ RODANDO • colete o resultado; não faça outro reload");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("NÃO PRONTO PARA RELOAD • veja o relatório; o botão 2 permanece bloqueado");
        }

        state.setText("module=" + s.moduleState + " @ " + s.moduleLocation +
                "\nvisible_sha=" + s.visibleSha +
                "\nstock_runtime=" + s.stockRuntime +
                "\nnexmon_runtime=" + s.nexmonRuntime +
                "\nready=" + s.ready);
        output.setText(report);
    }

    private void confirmReload() {
        new AlertDialog.Builder(this)
                .setTitle("Reload DHD com overlay Nexmon")
                .setMessage("O Wi-Fi será desligado temporariamente. O app usará o mfgloader Samsung em modo normal e religará o framework para forçar uma nova carga de bcmdhd_sta.bin_b1. O módulo já está DISABLED para o próximo boot. Não feche o app durante o teste.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar reload", (d, w) -> runReload())
                .show();
    }

    private void runReload() {
        if (busy) return;
        setBusy("Validando pré-condições imediatamente antes do reload…");
        worker.execute(() -> {
            StringBuilder trace = new StringBuilder();
            try {
                Snapshot before = snapshot();
                trace.append(report("before-reload", before));
                if (!before.ready) throw new Exception("Pré-condições mudaram; reload bloqueado.");

                postStatus("1/5 • Desligando Wi-Fi do framework…");
                RootReader.Result wifiOff = RootReader.run("svc wifi disable; sleep 3", 10);
                trace.append("\n=== WIFI OFF ===\ncode=").append(wifiOff.code).append(" timeout=").append(wifiOff.timedOut).append('\n').append(wifiOff.output);

                postStatus("2/5 • Selecionando modo Samsung normal/STA…");
                RootReader.Result mode = RootReader.run("setprop vendor.wlandriver.mode normal; getprop vendor.wlandriver.mode", 5);
                trace.append("\n=== SET MODE NORMAL ===\n").append(mode.output);

                postStatus("3/5 • Acionando mfgloader Samsung…");
                RootReader.Result loader = RootReader.run("start mfgloader; sleep 4; getprop vendor.wlandriver.status", 12);
                trace.append("\n=== START MFGLOADER ===\ncode=").append(loader.code).append(" timeout=").append(loader.timedOut).append('\n').append(loader.output);

                postStatus("4/5 • Religando Wi-Fi para forçar nova carga…");
                RootReader.Result wifiOn = RootReader.run("svc wifi enable", 6);
                trace.append("\n=== WIFI ON ===\ncode=").append(wifiOn.code).append(" timeout=").append(wifiOn.timedOut).append('\n').append(wifiOn.output);

                postStatus("5/5 • Aguardando BCM4375 inicializar…");
                String last = "";
                boolean sawNexmon = false;
                for (int i = 0; i < 18; i++) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    last = NexmonOneShotController.wifiver();
                    if (last.toLowerCase().contains("nexmon")) {
                        sawNexmon = true;
                        break;
                    }
                }

                Snapshot after = snapshot();
                trace.append("\n=== POLL LAST WIFIVER ===\n").append(last);
                trace.append("\n=== AFTER RELOAD ===\n").append(report("after-reload", after));
                trace.append("\nSAW_NEXMON_VERSION=").append(sawNexmon).append('\n');
                trace.append("\n=== DHD LOG SINCE BOOT ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -700", 10).output);
                trace.append("\n=== INTERFACES ===\n")
                        .append(RootReader.run("cat /proc/net/wireless 2>&1; echo ---; cat /sys/class/net/wlan0/type 2>&1; echo ---; cat /sys/class/net/wlan0/flags 2>&1", 6).output);

                createZip("BCM4375-Lab-S21-v31-runtime-reload-result.zip", "runtime-reload-result.txt", trace.toString());
                ui.post(() -> {
                    applySnapshot(after, trace.toString());
                    save.setEnabled(true);
                    if (after.nexmonRuntime || sawNexmon) {
                        status.setTextColor(0xFF81C784);
                        status.setText("RELOAD EXECUTADO • Nexmon detectado; salve o ZIP antes de qualquer reboot");
                    } else {
                        status.setTextColor(0xFFFFD180);
                        status.setText("RELOAD EXECUTADO • Nexmon não confirmado; salve o ZIP para análise");
                    }
                });
            } catch (Exception e) {
                RootReader.run("svc wifi enable", 5);
                trace.append("\nEXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
                trace.append("\n=== FAIL DHD LOG ===\n").append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -500", 10).output);
                try {
                    createZip("BCM4375-Lab-S21-v31-runtime-reload-failed.zip", "runtime-reload-failed.txt", trace.toString());
                } catch (Exception ignored) {}
                fail("RELOAD FALHOU • próximo boot continua protegido para stock", e);
            }
        });
    }

    private void collectEvidence(String phase) {
        if (busy) return;
        setBusy("Coletando estado atual e logs…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                StringBuilder r = new StringBuilder(report(phase, s));
                r.append("\n=== ONE SHOT EVIDENCE ===\n").append(NexmonOneShotController.collectEvidence());
                r.append("\n=== FULL RELEVANT DHD LOG ===\n")
                        .append(RootReader.run("dmesg | grep -iE 'dhd|bcmdhd|nexmon|firmware|4375|monitor|radiotap|mfgloader' | tail -900", 12).output);
                createZip("BCM4375-Lab-S21-v31-current-result.zip", "v31-current-result.txt", r.toString());
                ui.post(() -> {
                    applySnapshot(s, r.toString());
                    save.setEnabled(true);
                    status.setText("COLETA PRONTA • salve o ZIP");
                });
            } catch (Exception e) {
                fail("COLETA FALHOU", e);
            }
        });
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Samsung stock")
                .setMessage("O app reafirmará o marcador disable no módulo e reiniciará. No próximo boot, o overlay Nexmon não será montado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                }))
                .show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v31-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) {
            fos.write(body.getBytes(StandardCharsets.UTF_8));
        }
        List<File> files = new ArrayList<>();
        files.add(report);
        File zip = new File(getCacheDir(), zipName);
        if (zip.exists()) zip.delete();
        ExportUtil.zip(files, zip);
        zipFile = zip;
    }

    private void setBusy(String message) {
        busy = true;
        status.setTextColor(0xFFFFD180);
        status.setText(message);
        verify.setEnabled(false);
        reload.setEnabled(false);
        collect.setEnabled(false);
        rebootStock.setEnabled(false);
        save.setEnabled(false);
    }

    private void postStatus(String message) {
        ui.post(() -> status.setText(message));
    }

    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false;
            status.setTextColor(0xFFEF9A9A);
            status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            verify.setEnabled(true);
            collect.setEnabled(true);
            rebootStock.setEnabled(true);
            save.setEnabled(zipFile != null && zipFile.isFile());
        });
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(i, SAVE_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Destino indisponível");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Snapshot {
        final boolean root;
        final String moduleState, moduleLocation, visibleSha, moduleSha, wifiver;
        final boolean stockRuntime, nexmonRuntime, visibleNexmon, moduleNexmon, ready;

        Snapshot(boolean root, String moduleState, String moduleLocation, String visibleSha, String moduleSha,
                 String wifiver, boolean stockRuntime, boolean nexmonRuntime, boolean visibleNexmon,
                 boolean moduleNexmon, boolean ready) {
            this.root = root;
            this.moduleState = moduleState;
            this.moduleLocation = moduleLocation;
            this.visibleSha = visibleSha;
            this.moduleSha = moduleSha;
            this.wifiver = wifiver;
            this.stockRuntime = stockRuntime;
            this.nexmonRuntime = nexmonRuntime;
            this.visibleNexmon = visibleNexmon;
            this.moduleNexmon = moduleNexmon;
            this.ready = ready;
        }
    }
}
