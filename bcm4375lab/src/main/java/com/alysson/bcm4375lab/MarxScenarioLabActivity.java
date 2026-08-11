package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MARX LAB V1.1 LOGS: one APK, multiple isolated RF-development scenarios.
 * Every test is appended to a persistent local transcript. Scenarios 0-4 never
 * invoke sample playback or RF TX. Experimental scenarios restore firmware_class,
 * SELinux Enforcing and normal Wi-Fi in finally.
 */
public class MarxScenarioLabActivity extends Activity {
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/marx_lab_v11";
    private static final String ASSET = "nexmon/bcmdhd_sta_marx_tplram_v1.bin";
    private static final String EXPECTED_SHA = "MARX_TPLRAM_V1_SHA";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView log;
    private LinearLayout buttons;
    private volatile boolean busy;

    private interface ScenarioJob { ScenarioResult run() throws Exception; }
    private static final class ScenarioResult {
        final boolean ok;
        final String text;
        ScenarioResult(boolean ok, String text) { this.ok = ok; this.text = text; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        refreshCumulativeLog();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCumulativeLog();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        root.setBackgroundColor(0xFF071014);
        scroll.addView(root);

        root.addView(txt("MARX LAB V1.1 LOGS", 30, Color.WHITE, true));
        root.addView(txt("SM-G991B • BCM4375B1 • laboratório multi-cenário + histórico persistente", 13, 0xFF80CBC4, false));
        root.addView(txt("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFB0BEC5, false));
        root.addView(txt("Cada execução é salva separadamente no log acumulado. Você pode fazer 0, 1, 2, 3, 4, laboratório AFHDS2A, teste detalhado 0x631 e restauração stock; depois copie tudo de uma vez.", 13, 0xFFCFD8DC, false));

        status = txt("Pronto. O log persistente não é apagado ao trocar de tela ou fechar o app.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        root.addView(buttons);

        addButton("CENÁRIO 0 — PREFLIGHT / ESTADO ATUAL", () -> runJob("CENARIO_0_PREFLIGHT", "Preflight…", this::preflight));
        addButton("CENÁRIO 1 — BACKEND ATUAL / PR663", () -> runJob("CENARIO_1_BACKEND", "Diagnosticando backend atual…", this::backendCurrent));
        addButton("CENÁRIO 2 — NEXMON + 0x630 SOMENTE LEITURA", () -> confirmExperimental("CENARIO_2_0x630", "0x630 somente leitura", "regs"));
        addButton("CENÁRIO 3 — 0x631 TEMPLATE RAM / RESTORE", () -> confirmExperimental("CENARIO_3_0x631", "0x631 write/read/restore", "tplram"));
        addButton("CENÁRIO 4 — SEQUÊNCIA SEGURA COMPLETA 0x600+0x630+0x631", () -> confirmExperimental("CENARIO_4_ALL", "sequência segura completa", "all"));
        addButton("CENÁRIO 5 — LAB AFHDS2A / PACOTES (SEM TX)", () -> {
            MarxLabLogStore.append(this, "CENARIO_5_AFHDS2A", "OPENED_AFHDS2A_LAB=1\nTX_ENABLED=0\n");
            startActivity(new Intent(this, Rx42ControlActivity.class));
        });
        addButton("TESTE DETALHADO 0x631 — TELA ANTIGA", () -> {
            MarxLabLogStore.append(this, "DETAILED_0x631", "OPENED_DETAILED_0x631=1\nTX_ENABLED=0\n");
            startActivity(new Intent(this, Rx42PhyProbeV1Activity.class));
        });
        addButton("RESTAURAR STOCK + SELINUX ENFORCING", this::confirmRestore);
        addButton("COPIAR LOG COMPLETO DE TODOS OS TESTES", this::copyLog);
        addButton("LIMPAR LOG ACUMULADO", this::confirmClearLog);

        TextView safety = txt("SEGURANÇA: cenários 0-4 e o teste detalhado 0x631 não chamam sample playback e não habilitam TX. O Cenário 5 apenas monta/analisa AFHDS2A; GFSK TX continua bloqueado. Em falha experimental o transcript parcial é preservado antes da restauração.", 12, 0xFFFFAB91, false);
        safety.setPadding(0, dp(18), 0, dp(8));
        root.addView(safety);

        log = mono("Carregando log acumulado…", 11, 0xFFE0E0E0);
        root.addView(log);
        return scroll;
    }

    private void confirmExperimental(String section, String label, String mode) {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Executar " + label + "?")
                .setMessage("O Wi-Fi será reiniciado e o firmware MARX será carregado temporariamente. A entrada em B1 Monitor agora usa poll por até 15 s e salva snapshot mesmo em falha. SELinux volta a Enforcing no finally. Nenhuma rotina de sample playback/TX é chamada.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d, w) -> runJob(section, "Executando " + label + "…", () -> experimental(mode)))
                .show();
    }

    private void confirmRestore() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Restaurar estado stock?")
                .setMessage("Força firmware_class para /vendor/firmware, SELinux Enforcing, modo Wi-Fi normal e reinicia o Wi-Fi. O resultado também será salvo no log acumulado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (d, w) -> runJob("RESTORE_STOCK_SELINUX", "Restaurando stock…", this::restoreStock))
                .show();
    }

    private void confirmClearLog() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Limpar histórico?")
                .setMessage("Apaga somente o arquivo de log local do MARX LAB. Não altera firmware, Wi-Fi ou SELinux.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (d, w) -> {
                    MarxLabLogStore.clear(this);
                    refreshCumulativeLog();
                    status.setTextColor(0xFF81C784);
                    status.setText("Log acumulado limpo.");
                }).show();
    }

    private void runJob(String section, String running, ScenarioJob job) {
        if (busy) return;
        busy = true;
        setButtons(false);
        status.setTextColor(0xFFFFD180);
        status.setText(running);
        worker.execute(() -> {
            ScenarioResult result;
            try {
                result = job.run();
            } catch (Throwable t) {
                result = new ScenarioResult(false, "UNCAUGHT_EXCEPTION=" + t + "\n");
            }
            MarxLabLogStore.append(this, section, result.text);
            ScenarioResult finalResult = result;
            ui.post(() -> {
                busy = false;
                setButtons(true);
                status.setTextColor(finalResult.ok ? 0xFF81C784 : 0xFFEF9A9A);
                status.setText(finalResult.ok ? "Cenário concluído: PASS • log salvo" : "Cenário concluído: NÃO CONFIRMADO / FAIL • log salvo");
                refreshCumulativeLog();
            });
        });
    }

    private ScenarioResult preflight() {
        StringBuilder out = new StringBuilder("=== MARX LAB / CENÁRIO 0 PREFLIGHT ===\n");
        String id = rr("id 2>&1", 4);
        String se = rr("getenforce 2>&1", 4).trim();
        String fw = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
        String wifi = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
        String mode = rr("getprop vendor.wlandriver.mode 2>/dev/null", 3).trim();
        String statusProp = rr("getprop vendor.wlandriver.status 2>/dev/null", 3).trim();
        String fwParam = rr("cat /sys/module/dhd/parameters/firmware_path 2>/dev/null", 3).trim();
        boolean root = id.contains("uid=0");
        boolean enforcing = "Enforcing".equalsIgnoreCase(se);
        out.append("root=").append(root).append('\n');
        out.append("SELinux=").append(se).append('\n');
        out.append("firmware_class.path=").append(fw.isEmpty() ? "<default/empty>" : fw).append('\n');
        out.append("vendor.wlandriver.mode=").append(mode).append('\n');
        out.append("vendor.wlandriver.status=").append(statusProp).append('\n');
        out.append("dhd.firmware_path=").append(fwParam).append('\n');
        out.append("wifiver=\n").append(wifi).append('\n');
        out.append("PREFLIGHT_RESULT=").append(root && enforcing ? "PASS" : "CHECK_REQUIRED").append('\n');
        return new ScenarioResult(root && enforcing, out.toString());
    }

    private ScenarioResult backendCurrent() {
        StringBuilder out = new StringBuilder("=== MARX LAB / CENÁRIO 1 BACKEND ATUAL ===\n");
        String id = rr("id 2>&1", 4);
        String probe = rr(nexProbe() + " wlan0", 8);
        boolean root = id.contains("uid=0");
        boolean nex = probe.contains("NEXPROBE_PR663_600=true") || probe.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        out.append("root=").append(root).append('\n');
        out.append("mode=").append(MonitorController.mode()).append('\n');
        out.append("status=").append(MonitorController.status()).append('\n');
        out.append("wifiver=\n").append(MonitorController.wifiver()).append('\n');
        out.append("probe=\n").append(probe).append('\n');
        out.append("BACKEND_CURRENT_RESULT=").append(root && nex ? "PASS" : "NOT_PRESENT_OR_NOT_CONFIRMED").append('\n');
        return new ScenarioResult(root && nex, out.toString());
    }

    private ScenarioResult experimental(String mode) {
        StringBuilder tr = new StringBuilder();
        String originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
        if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
        boolean success = false;
        boolean restored = false;
        try {
            tr.append("=== MARX LAB EXPERIMENTAL mode=").append(mode).append(" ===\n");
            String id = rr("id 2>&1", 4);
            String se = rr("getenforce 2>&1", 4).trim();
            tr.append("root=").append(id.contains("uid=0")).append("\nSELinux=").append(se).append('\n');
            tr.append("original_fwclass=").append(originalPath).append('\n');
            tr.append("initial_snapshot=\n").append(MonitorController.snapshot("INITIAL"));
            if (!id.contains("uid=0") || !"Enforcing".equalsIgnoreCase(se))
                throw new Exception("preflight exige root e SELinux Enforcing");

            post("Extraindo e validando firmware MARX…");
            File src = new File(getFilesDir(), "bcmdhd_sta_marx_lab_v11.bin");
            copyAsset(ASSET, src);
            String stageCmd = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) +
                    "; cp " + q(src.getAbsolutePath()) + " " + q(STAGE + "/bcmdhd_sta.bin_b1") +
                    "; cp /vendor/firmware/bcmdhd_clm.blob " + q(STAGE + "/bcmdhd_clm.blob") +
                    "; chown -R wifi:wifi " + q(STAGE) +
                    "; chmod 0755 " + q(STAGE) +
                    "; chmod 0644 " + q(STAGE + "/bcmdhd_sta.bin_b1") + " " + q(STAGE + "/bcmdhd_clm.blob") +
                    "; restorecon -RF " + q(STAGE) + " 2>&1 || true";
            tr.append("=== STAGE ===\n").append(rr(stageCmd, 8));
            String stagedSha = rr("sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1") + " | awk '{print $1}'", 4).trim();
            tr.append("staged_sha=").append(stagedSha).append('\n');
            tr.append("expected_sha=").append(EXPECTED_SHA).append('\n');
            if (!EXPECTED_SHA.equalsIgnoreCase(stagedSha)) throw new Exception("SHA do firmware não confere");

            post("Entrando em Samsung B1 Monitor…");
            tr.append("=== WIFI OFF ===\n").append(MonitorController.wifi(false).output).append('\n');
            sleep(1200);
            tr.append("=== MODE MONITOR ===\n").append(MonitorController.setMode("monitor").output).append('\n');
            tr.append("=== START MONITOR ===\n").append(MonitorController.startSamsungLoader().output).append('\n');
            boolean monOk = MonitorController.waitForFirmware("B1 Monitor", 15);
            tr.append(MonitorController.snapshot("MONITOR_GATE"));
            tr.append("MONITOR_CONFIRMED=").append(monOk).append('\n');
            if (!monOk) throw new Exception("B1 Monitor não confirmado após poll de 15 s");

            post("Carregando firmware experimental…");
            String setOut = rr("printf '%s' " + q(STAGE) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
            String activePath = setOut.trim();
            tr.append("=== SET FWCLASS ===\n").append(setOut).append('\n');
            tr.append("FWCLASS_STAGE=").append(activePath).append('\n');
            if (!STAGE.equals(activePath)) throw new Exception("firmware_class.path não aceitou staging");

            String setPerm = rr("setenforce 0; getenforce 2>&1", 4);
            String loadSe = setPerm.trim();
            tr.append("=== SELINUX LOAD ===\n").append(setPerm).append('\n');
            tr.append("SELINUX_LOAD=").append(loadSe).append('\n');
            if (!loadSe.contains("Permissive")) throw new Exception("não entrou em Permissive");

            tr.append("=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output).append('\n');
            tr.append("=== START EXPERIMENTAL NETWORK ===\n").append(MonitorController.startSamsungLoader().output).append('\n');
            sleep(900);
            tr.append("=== WIFI ON ===\n").append(MonitorController.wifi(true).output).append('\n');
            boolean netOk = MonitorController.waitForFirmware("B1 Network/rsdb", 15);
            tr.append(MonitorController.snapshot("EXPERIMENTAL_NETWORK"));
            tr.append("EXPERIMENTAL_NETWORK_CONFIRMED=").append(netOk).append('\n');
            if (!netOk) throw new Exception("B1 Network experimental não confirmado após poll de 15 s");

            post("Executando probe mode=" + mode + "…");
            String probe = rr(sdrProbe() + " wlan0 " + q(mode), 12);
            tr.append("=== PROBE ===\n").append(probe).append('\n');
            success = probe.contains("RX42_SCENARIO_RESULT=PASS");
            if ("tplram".equals(mode) || "all".equals(mode)) {
                success = success && probe.contains("TX_TRIGGERED=0") && probe.contains("PLAYBACK_STAYED_OFF=1");
            }
            tr.append("SCENARIO_APP_RESULT=").append(success ? "PASS" : "NOT_CONFIRMED").append('\n');
        } catch (Throwable e) {
            tr.append("EXCEPTION=").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        } finally {
            post("Restaurando estado stock…");
            tr.append("=== FINALLY / RESTORE ===\n");
            tr.append(rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + "; setenforce 1", 5));
            tr.append("=== RESTORE MODE NORMAL ===\n").append(MonitorController.wifi(false).output).append('\n');
            sleep(1000);
            tr.append(MonitorController.setMode("normal").output).append('\n');
            tr.append(MonitorController.startSamsungLoader().output).append('\n');
            sleep(900);
            tr.append(MonitorController.wifi(true).output).append('\n');
            MonitorController.waitForFirmware("B1 Network/rsdb", 15);
            String finalSe = rr("getenforce 2>&1", 3).trim();
            String finalPath = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
            tr.append(MonitorController.snapshot("FINAL_STOCK"));
            rr("rm -rf " + q(STAGE), 4);
            tr.append("final_SELinux=").append(finalSe).append('\n');
            tr.append("final_fwclass=").append(finalPath).append('\n');
            restored = "Enforcing".equalsIgnoreCase(finalSe) && originalPath.equals(finalPath);
            tr.append("RESTORE_STATE=").append(restored ? "PASS" : "CHECK_REQUIRED").append('\n');
        }
        return new ScenarioResult(success && restored, tr.toString());
    }

    private ScenarioResult restoreStock() {
        StringBuilder out = new StringBuilder("=== MARX LAB / RESTORE STOCK ===\n");
        out.append(rr("printf '%s' '/vendor/firmware' > " + FWCLASS + "; setenforce 1", 5));
        out.append("\n=== WIFI OFF ===\n").append(MonitorController.wifi(false).output);
        sleep(1000);
        out.append("\n=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output);
        out.append("\n=== START NORMAL ===\n").append(MonitorController.startSamsungLoader().output);
        sleep(900);
        out.append("\n=== WIFI ON ===\n").append(MonitorController.wifi(true).output);
        boolean network = MonitorController.waitForFirmware("B1 Network/rsdb", 15);
        rr("rm -rf " + q(STAGE), 4);
        String se = rr("getenforce 2>&1", 3).trim();
        String fw = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
        boolean ok = "Enforcing".equalsIgnoreCase(se) && "/vendor/firmware".equals(fw) && network;
        out.append('\n').append(MonitorController.snapshot("RESTORE_FINAL"));
        out.append("SELinux=").append(se).append('\n');
        out.append("firmware_class.path=").append(fw).append('\n');
        out.append("NETWORK_CONFIRMED=").append(network).append('\n');
        out.append("RESTORE_RESULT=").append(ok ? "PASS" : "CHECK_REQUIRED").append('\n');
        return new ScenarioResult(ok, out.toString());
    }

    private void copyLog() {
        String all = MarxLabLogStore.readAll(this);
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("MARX LAB V1.1 complete log", all));
        status.setTextColor(0xFF81C784);
        status.setText("Log completo copiado • " + MarxLabLogStore.size(this) + " bytes");
        log.setText(all);
    }

    private void refreshCumulativeLog() {
        if (log != null) log.setText(MarxLabLogStore.readAll(this));
    }

    private void addButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> action.run());
        buttons.addView(b);
    }

    private void setButtons(boolean enabled) {
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setEnabled(enabled);
    }

    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private String nexProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libnexprobe.so"); }
    private String sdrProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libsdrrx42probe.so"); }
    private void post(String s) { ui.post(() -> status.setText(s)); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        dst.setReadable(true, false);
    }

    private TextView txt(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono(String s, float sp, int color) {
        TextView t = txt(s, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
