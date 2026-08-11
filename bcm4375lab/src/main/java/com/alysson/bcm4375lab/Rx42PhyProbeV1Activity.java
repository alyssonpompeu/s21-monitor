package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Detailed 0x631 Template RAM test. Never invokes sample playback or TX. */
public class Rx42PhyProbeV1Activity extends Activity {
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/marx_tplram_v1";
    private static final String ASSET = "nexmon/bcmdhd_sta_marx_tplram_v1.bin";
    private static final String EXPECTED_SHA = "MARX_TPLRAM_V1_SHA";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, log;
    private Button run;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView s = new ScrollView(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(22), dp(24), dp(22), dp(28));
        r.setBackgroundColor(0xFF071014);
        s.addView(r);

        r.addView(text("MARX V1.0 — DETALHADO 0x631", 28, Color.WHITE, true));
        r.addView(text("BCM4375B1 • Template RAM • endereçamento explícito • TX OFF • log automático", 13, 0xFF80CBC4, false));
        r.addView(text("Este teste salva 4 words da área de scratch, posiciona o ponteiro antes de cada acesso, escreve quatro padrões, lê, restaura e verifica. A entrada em B1 Monitor usa poll de até 15 s. O transcript completo é salvo no log acumulado mesmo quando ocorre exceção.", 13, 0xFFCFD8DC, false));
        status = text("Pronto para round-trip 0x631 sem TX.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(18), 0, dp(12));
        r.addView(status);

        run = new Button(this);
        run.setText("CARREGAR FIRMWARE + TESTAR 0x630/0x631");
        run.setAllCaps(false);
        run.setOnClickListener(v -> confirmRun());
        r.addView(run);

        log = text("Nenhum teste executado nesta tela. O resultado será salvo automaticamente no histórico do MARX LAB.", 11, 0xFFE0E0E0, false);
        log.setPadding(0, dp(16), 0, 0);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(log);
        return s;
    }

    private void confirmRun() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Testar Template RAM 0x631?")
                .setMessage("O Wi-Fi será reiniciado. SELinux ficará Permissive apenas durante o carregamento do firmware e voltará a Enforcing. Nenhuma rotina de sample playback/TX é chamada. O log completo será preservado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> execute()).show();
    }

    private void execute() {
        busy = true;
        run.setEnabled(false);
        status.setText("Executando teste detalhado 0x631…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            JSONObject report = new JSONObject();
            boolean success = false;
            boolean restored = false;
            String originalPath = "/vendor/firmware";
            try {
                report.put("test_id", "marx_detailed_tplram_explicit_ptr_0631_no_tx");
                report.put("model", android.os.Build.MODEL);
                report.put("hardware", android.os.Build.HARDWARE);
                report.put("android", android.os.Build.VERSION.RELEASE);
                report.put("started_ms", System.currentTimeMillis());

                RootReader.Result id = RootReader.run("id", 4);
                String se = rr("getenforce 2>&1", 4).trim();
                originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
                tr.append("=== DETAILED 0x631 PREFLIGHT ===\nroot=").append(id.output.contains("uid=0"))
                        .append("\nSELinux=").append(se)
                        .append("\nfwclass=").append(originalPath)
                        .append("\n").append(MonitorController.snapshot("INITIAL")).append('\n');
                if (!id.output.contains("uid=0") || !"Enforcing".equalsIgnoreCase(se))
                    throw new Exception("preflight: root + SELinux Enforcing necessários");

                post("Extraindo firmware MARX…");
                File src = new File(getFilesDir(), "bcmdhd_sta_marx_tplram_v1.bin");
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
                if (!EXPECTED_SHA.equalsIgnoreCase(stagedSha)) throw new Exception("SHA do firmware MARX não confere");

                post("Entrando em Samsung B1 Monitor…");
                tr.append("=== WIFI OFF ===\n").append(MonitorController.wifi(false).output).append('\n');
                sleep(1200);
                tr.append("=== MODE MONITOR ===\n").append(MonitorController.setMode("monitor").output).append('\n');
                tr.append("=== START MONITOR ===\n").append(MonitorController.startSamsungLoader().output).append('\n');
                boolean monitorLoaded = MonitorController.waitForFirmware("B1 Monitor", 15);
                tr.append(MonitorController.snapshot("MONITOR_GATE"));
                tr.append("MONITOR_CONFIRMED=").append(monitorLoaded).append('\n');
                if (!monitorLoaded) throw new Exception("B1 Monitor não confirmado após poll de 15 s");

                post("Carregando Nexmon MARX…");
                String setPathOut = rr("printf '%s' " + q(STAGE) + " > " + FWCLASS + "; cat " + FWCLASS + " 2>&1", 5);
                String setPath = setPathOut.trim();
                tr.append("=== SET FWCLASS ===\n").append(setPathOut).append('\n');
                tr.append("FWCLASS_STAGE=").append(setPath).append('\n');
                if (!STAGE.equals(setPath)) throw new Exception("firmware_class.path não aceitou staging");

                String permOut = rr("setenforce 0; getenforce 2>&1", 4);
                tr.append("=== SELINUX LOAD ===\n").append(permOut).append('\n');
                if (!permOut.contains("Permissive")) throw new Exception("não entrou em Permissive");

                tr.append("=== MODE NORMAL ===\n").append(MonitorController.setMode("normal").output).append('\n');
                tr.append("=== START EXPERIMENTAL ===\n").append(MonitorController.startSamsungLoader().output).append('\n');
                sleep(900);
                tr.append("=== WIFI ON ===\n").append(MonitorController.wifi(true).output).append('\n');
                boolean networkLoaded = MonitorController.waitForFirmware("B1 Network/rsdb", 15);
                tr.append(MonitorController.snapshot("EXPERIMENTAL_NETWORK"));
                tr.append("EXPERIMENTAL_NETWORK_CONFIRMED=").append(networkLoaded).append('\n');
                if (!networkLoaded) throw new Exception("B1 Network experimental não confirmado após poll de 15 s");

                post("Executando 0x630 + 0x631 com ponteiro explícito…");
                String probe = rr(nativeProbe() + " wlan0", 12);
                tr.append("=== IOCTL 0x630 + 0x631 ===\n").append(probe).append('\n');
                success = probe.contains("RX42_TPLRAM_USER_PROBE=PASS_NO_TX") &&
                        probe.contains("TPLRAM_RESULT=PASS") &&
                        probe.contains("WRITE_READBACK_OK=1") &&
                        probe.contains("RESTORE_OK=1") &&
                        probe.contains("PLAYBACK_STAYED_OFF=1") &&
                        probe.contains("TX_TRIGGERED=0");
                report.put("probe_output", probe);
                report.put("success", success);
                report.put("experimental_sha", EXPECTED_SHA);
            } catch (Throwable e) {
                try { report.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch(Exception ignored) {}
                tr.append("EXCEPTION=").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                try {
                    tr.append("=== FINAL / RESTORE ===\n");
                    tr.append(rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + "; setenforce 1", 5));
                    tr.append(MonitorController.wifi(false).output).append('\n');
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
                    restored = "Enforcing".equalsIgnoreCase(finalSe) && originalPath.equals(finalPath);
                    tr.append("final_selinux=").append(finalSe).append('\n');
                    tr.append("final_fwclass=").append(finalPath).append('\n');
                    tr.append("RESTORE_STATE=").append(restored ? "PASS" : "CHECK_REQUIRED").append('\n');
                    report.put("final_selinux", finalSe);
                    report.put("final_fwclass", finalPath);
                    report.put("trace", tr.toString());
                    report.put("finished_ms", System.currentTimeMillis());
                } catch(Exception e) {
                    tr.append("RESTORE_EXCEPTION=").append(e).append('\n');
                }
            }

            tr.append("REPORT_MODE=LOCAL_ONLY\n");
            boolean ok = success && restored;
            tr.append("DETAILED_0x631_RESULT=").append(ok ? "PASS" : "NOT_CONFIRMED").append('\n');
            String shown = tr.toString();
            MarxLabLogStore.append(this, "DETAILED_0x631_RESULT", shown);
            ui.post(() -> {
                busy = false;
                run.setEnabled(true);
                status.setTextColor(ok ? 0xFF81C784 : 0xFFEF9A9A);
                status.setText(ok ? "0x631 DETALHADO • PASS • SEM TX • LOG SALVO" : "0x631 DETALHADO • NÃO CONFIRMADO • ESTADO RESTAURADO • LOG SALVO");
                log.setText(shown);
            });
        });
    }

    private String nativeProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libsdrrx42probe.so"); }
    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private void post(String s) { ui.post(() -> status.setText(s)); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        dst.setReadable(true, false);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
