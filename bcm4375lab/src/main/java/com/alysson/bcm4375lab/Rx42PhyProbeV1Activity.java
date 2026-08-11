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

/**
 * MARX V1.0 probe for BCM4375B1 18.41.117.
 * IOCTL 0x631 performs a bounded Template RAM write/read/restore with an explicit
 * pointer assignment for each 32-bit word. It never invokes sample playback or TX.
 */
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

        r.addView(text("MARX V1.0", 30, Color.WHITE, true));
        r.addView(text("BCM4375B1 • Template RAM • endereçamento explícito • TX OFF", 13, 0xFF80CBC4, false));
        r.addView(text("Este teste salva 4 words da área de scratch, posiciona o ponteiro antes de cada acesso, escreve quatro padrões diferentes, lê de volta e restaura cada word original. SAMPLE_PLAY_CTRL deve permanecer exatamente igual antes/depois. Nenhuma rotina de sample playback/TX é chamada.", 13, 0xFFCFD8DC, false));
        status = text("Pronto para round-trip MARX V1.0 sem TX.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(18), 0, dp(12));
        r.addView(status);

        run = new Button(this);
        run.setText("CARREGAR FIRMWARE + TESTAR 0x630/0x631");
        run.setAllCaps(false);
        run.setOnClickListener(v -> confirmRun());
        r.addView(run);

        log = text("Nenhum teste executado.", 11, 0xFFE0E0E0, false);
        log.setPadding(0, dp(16), 0, 0);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(log);
        return s;
    }

    private void confirmRun() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("MARX V1.0 — testar Template RAM?")
                .setMessage("O Wi-Fi será reiniciado. SELinux ficará Permissive apenas durante o carregamento do firmware e voltará a Enforcing. O 0x631 salva 4 words, usa um endereço explícito para cada word, escreve, lê, restaura e verifica. O registrador SAMPLE_PLAY_CTRL não é escrito. Nenhuma rotina de sample playback/TX é chamada.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> execute()).show();
    }

    private void execute() {
        busy = true; run.setEnabled(false); status.setText("Executando MARX V1.0…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            JSONObject report = new JSONObject();
            boolean success = false;
            String originalPath = "/vendor/firmware";
            try {
                report.put("test_id", "marx_v1_tplram_explicit_ptr_0631_no_tx");
                report.put("model", android.os.Build.MODEL);
                report.put("hardware", android.os.Build.HARDWARE);
                report.put("android", android.os.Build.VERSION.RELEASE);
                report.put("started_ms", System.currentTimeMillis());

                RootReader.Result id = RootReader.run("id", 4);
                String se = rr("getenforce 2>&1", 4).trim();
                originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
                tr.append("=== MARX V1.0 PREFLIGHT ===\nroot=").append(id.output.contains("uid=0"))
                  .append("\nSELinux=").append(se)
                  .append("\nfwclass=").append(originalPath)
                  .append("\nwifiver=\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null", 4)).append('\n');
                if (!id.output.contains("uid=0") || !"Enforcing".equalsIgnoreCase(se))
                    throw new Exception("preflight: root + SELinux Enforcing necessários");

                post("Extraindo firmware MARX V1.0…");
                File src = new File(getFilesDir(), "bcmdhd_sta_marx_tplram_v1.bin");
                copyAsset(ASSET, src);
                String qsrc = q(src.getAbsolutePath());
                String stageCmd = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) +
                        "; cp " + qsrc + " " + q(STAGE + "/bcmdhd_sta.bin_b1") +
                        "; cp /vendor/firmware/bcmdhd_clm.blob " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; chown -R wifi:wifi " + q(STAGE) +
                        "; chmod 0755 " + q(STAGE) +
                        "; chmod 0644 " + q(STAGE + "/bcmdhd_sta.bin_b1") + " " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; restorecon -RF " + q(STAGE) + " 2>&1 || true";
                tr.append("=== STAGE ===\n").append(rr(stageCmd, 8));
                String stagedSha = rr("sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1") + " | awk '{print $1}'", 4).trim();
                tr.append("staged_sha=").append(stagedSha).append('\n');
                if (!EXPECTED_SHA.equalsIgnoreCase(stagedSha)) throw new Exception("SHA do firmware MARX não confere");

                post("Entrando em Samsung B1 Monitor…");
                rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode monitor; setprop ctl.start mfgloader; sleep 3", 9);
                String mon = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                boolean monitorLoaded = mon.contains("B1 Monitor");
                tr.append("=== MONITOR ===\n").append(mon).append("MONITOR_CONFIRMED=").append(monitorLoaded).append('\n');
                if (!monitorLoaded) throw new Exception("B1 Monitor não confirmado");

                post("Carregando Nexmon MARX V1.0…");
                rr("printf '%s' " + q(STAGE) + " > " + FWCLASS, 4);
                String setPath = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
                tr.append("FWCLASS_STAGE=").append(setPath).append('\n');
                if (!STAGE.equals(setPath)) throw new Exception("firmware_class.path não aceitou staging");

                rr("setenforce 0", 3);
                String permissive = rr("getenforce 2>&1", 3).trim();
                tr.append("SELINUX_LOAD=").append(permissive).append('\n');
                if (!"Permissive".equalsIgnoreCase(permissive)) throw new Exception("não entrou em Permissive");

                rr("setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 12);
                String net = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                tr.append("=== MARX EXPERIMENTAL NETWORK ===\n").append(net).append('\n');

                post("Executando 0x630 + 0x631 com ponteiro explícito…");
                String probe = rr(nativeProbe() + " wlan0", 9);
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
            } catch (Exception e) {
                try { report.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch(Exception ignored) {}
                tr.append("EXCEPTION=").append(e).append('\n');
            } finally {
                try {
                    rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + "; setenforce 1", 5);
                    if (!success) {
                        post("Restaurando firmware normal…");
                        rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 3", 11);
                    }
                    String finalSe = rr("getenforce 2>&1", 3).trim();
                    String finalPath = rr("cat " + FWCLASS + " 2>/dev/null", 3).trim();
                    String finalWifi = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                    String finalProbe = rr(nativeProbe() + " wlan0", 9);
                    tr.append("=== FINAL ===\nSELinux=").append(finalSe)
                      .append("\nfwclass=").append(finalPath)
                      .append("\nwifiver=\n").append(finalWifi)
                      .append("\nprobe=\n").append(finalProbe).append('\n');
                    rr("rm -rf " + q(STAGE), 4);
                    report.put("final_selinux", finalSe);
                    report.put("final_fwclass", finalPath);
                    report.put("final_probe", finalProbe);
                    report.put("trace", tr.toString());
                    report.put("finished_ms", System.currentTimeMillis());
                } catch(Exception ignored) {}
            }

            tr.append("REPORT_MODE=LOCAL_ONLY\n");
            boolean ok = success;
            String shown = tr.toString();
            ui.post(() -> {
                busy = false; run.setEnabled(false);
                status.setTextColor(ok ? 0xFF81C784 : 0xFFEF9A9A);
                status.setText(ok ? "MARX V1.0 • TEMPLATE RAM PASS • SEM TX" : "MARX V1.0 • PROVA NÃO CONFIRMADA • estado seguro restaurado");
                log.setText(shown);
            });
        });
    }

    private String nativeProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libsdrrx42probe.so"); }
    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private void post(String s) { ui.post(() -> status.setText(s)); }

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
