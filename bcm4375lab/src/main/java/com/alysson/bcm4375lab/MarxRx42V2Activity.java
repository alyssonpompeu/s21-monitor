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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MARX RX42 BCM4375 V2.
 *
 * Engineering build for Galaxy S21 / BCM4375B1 / COREREV82. The firmware
 * exposes read-only V2 capability and register-snapshot IOCTLs. No RF burst is
 * started unless a future firmware explicitly reports both NATIVE_SAMPLE_PLAY=1
 * and BOUNDED_TX=1.
 */
public class MarxRx42V2Activity extends Activity {
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/marx_rx42_bcm4375_v2";
    private static final String ASSET = "nexmon/bcmdhd_sta_marx_rx42_v2.bin";
    private static final String EXPECTED_SHA = "MARX_RX42_V2_SHA";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, log;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView s = new ScrollView(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(18), dp(20), dp(18), dp(36));
        r.setBackgroundColor(0xFF071014);
        s.addView(r);

        r.addView(text("MARX RX42 • BCM4375 V2", 27, Color.WHITE, true));
        r.addView(text("Galaxy S21 G991B • BCM4375B1 • 18.41.117 • COREREV82", 13, 0xFF80CBC4, false));
        r.addView(text("Objetivo desta build: provar o backend nativo de Template RAM / Sample Playback sem declarar RF por software. O gate de TX permanece fechado enquanto o mapeamento nativo não estiver validado.", 13, 0xFFCFD8DC, false));

        status = text("V2 pronta para carregar o firmware de diagnóstico.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(16), 0, dp(10));
        r.addView(status);

        r.addView(button("1. CARREGAR FIRMWARE V2 + CAPS + SNAPSHOT", v -> confirmLoad()));
        r.addView(button("2. NEXMON / PR663 TRANSPORT", v -> runNative("nexmon")));
        r.addView(button("3. AFHDS2A GFSK/IQ DRY-RUN (SEM RF)", v -> runNative("gfskdry")));
        r.addView(button("4. D11 / SAMPLE PLAY REGISTER SNAPSHOT", v -> runNative("regsnap")));
        r.addView(button("5. AVALIAR GATE DE TX", v -> runNative("backend")));
        r.addView(button("6. WLC_PHY_SAMPLE_COLLECT 307 (RX, SEM TX)", v -> runNative("sample307")));
        r.addView(button("7. RESTAURAR WIFI NORMAL + ENFORCING", v -> recover()));

        r.addView(text("Critério para abrir o próximo estágio: o firmware precisa reportar NATIVE_SAMPLE_PLAY=1 e BOUNDED_TX=1 após validação do endereço/função nativa. Esta V2 publica os candidatos da análise estática no release, mas não chama candidatos automaticamente.", 12, 0xFFFFAB91, false));

        log = mono("Nenhum teste executado.");
        log.setPadding(0, dp(16), 0, 0);
        r.addView(log);
        return s;
    }

    private void confirmLoad() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Carregar firmware MARX V2?")
                .setMessage("O Wi-Fi será reiniciado. SELinux fica Permissive apenas durante a troca do firmware e volta a Enforcing. Os novos IOCTLs 0x63F e 0x640 são somente leitura e não disparam RF.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> loadAndProbe())
                .show();
    }

    private void loadAndProbe() {
        if (!enterBusy("Preparando firmware V2…")) return;
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            String originalPath = "/vendor/firmware";
            boolean v2Loaded = false;
            try {
                RootReader.Result id = RootReader.run("id", 4);
                String se = rr("getenforce 2>&1", 4).trim();
                originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
                tr.append("=== PREFLIGHT ===\nroot=").append(id.output.contains("uid=0"))
                  .append("\nSELinux=").append(se)
                  .append("\nfwclass=").append(originalPath)
                  .append("\nwifiver=\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null", 4)).append('\n');
                if (!id.output.contains("uid=0")) throw new Exception("root uid=0 não confirmado");

                File src = new File(getFilesDir(), "bcmdhd_sta_marx_rx42_v2.bin");
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
                tr.append("firmware_sha=").append(stagedSha).append('\n');
                if (!EXPECTED_SHA.equalsIgnoreCase(stagedSha)) throw new Exception("SHA V2 não confere");

                post("Entrando em B1 Monitor…");
                rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode monitor; setprop ctl.start mfgloader; sleep 3", 9);
                String mon = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                tr.append("=== MONITOR ===\n").append(mon).append('\n');
                if (!mon.contains("B1 Monitor")) throw new Exception("B1 Monitor não confirmado");

                rr("printf '%s' " + q(STAGE) + " > " + FWCLASS, 4);
                if (!STAGE.equals(rr("cat " + FWCLASS + " 2>/dev/null", 3).trim()))
                    throw new Exception("firmware_class.path não aceitou staging V2");

                rr("setenforce 0", 3);
                tr.append("SELINUX_LOAD=").append(rr("getenforce 2>&1", 3).trim()).append('\n');
                rr("setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 12);
                rr("setenforce 1", 3);
                String net = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                tr.append("=== EXPERIMENTAL NETWORK ===\n").append(net).append('\n');

                String caps = nativeOut("capsv2", 10);
                String snap = nativeOut("regsnap", 10);
                String transport = nativeOut("nexmon", 10);
                tr.append("=== CAPS 0x63F ===\n").append(caps)
                  .append("\n=== SNAPSHOT 0x640 ===\n").append(snap)
                  .append("\n=== NEXMON ===\n").append(transport).append('\n');
                v2Loaded = caps.contains("MARX_RX42_BCM4375_V2=1") &&
                           caps.contains("COREREV=82") &&
                           caps.contains("TX_TRIGGERED=0") &&
                           snap.contains("MARX_REG_SNAPSHOT_V2=1") &&
                           snap.contains("SNAPSHOT_WRITES=0");
                tr.append("V2_DIAGNOSTIC_GATE=").append(v2Loaded ? "PASS" : "FAIL").append('\n');
                tr.append("RF_TX_TRIGGERED=0\n");
            } catch (Exception e) {
                tr.append("EXCEPTION=").append(e).append('\n');
            } finally {
                try {
                    rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + "; setenforce 1", 5);
                    if (!v2Loaded) {
                        rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 3", 11);
                    }
                    tr.append("FINAL_SELINUX=").append(rr("getenforce 2>&1", 3).trim()).append('\n');
                    tr.append("FINAL_FWCLASS=").append(rr("cat " + FWCLASS + " 2>/dev/null", 3).trim()).append('\n');
                } catch (Exception ignored) {}
            }
            boolean ok = v2Loaded;
            String shown = tr.toString();
            ui.post(() -> {
                busy = false;
                status.setTextColor(ok ? 0xFF81C784 : 0xFFEF9A9A);
                status.setText(ok ? "V2 carregada • COREREV82 confirmado • TX gate ainda fechado" : "V2 não confirmada • estado seguro restaurado");
                log.setText(shown);
            });
        });
    }

    private void runNative(String mode) {
        if (!enterBusy("Executando " + mode + "…")) return;
        worker.execute(() -> {
            String out = nativeOut(mode, mode.equals("gfskdry") ? 16 : 10);
            String message;
            if (mode.equals("backend")) {
                boolean open = out.contains("NATIVE_SAMPLE_PLAY_READY=1") && out.contains("BOUNDED_TX_READY=1");
                message = open ? "Gate nativo pronto para o próximo teste de burst." : "Gate de TX fechado corretamente: falta Sample Playback nativo validado.";
            } else message = "Cenário " + mode + " concluído.";
            finish(out, message);
        });
    }

    private String nativeOut(String mode, long timeout) {
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libmarxa7105probe.so");
        String cmd = "chmod 0755 " + q(exe.getAbsolutePath()) + " 2>/dev/null || true; " + q(exe.getAbsolutePath()) + " wlan0 " + mode;
        RootReader.Result r = RootReader.run(cmd, timeout);
        return r.output + "\nNATIVE_EXIT=" + r.code + "\n";
    }

    private void recover() {
        if (!enterBusy("Restaurando Wi-Fi…")) return;
        worker.execute(() -> {
            String cmd = "printf '%s' /vendor/firmware > " + FWCLASS + " 2>/dev/null || true; setenforce 1 2>/dev/null || true; " +
                    "svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4; " +
                    "echo SELINUX=$(getenforce); echo FWCLASS=$(cat " + FWCLASS + " 2>/dev/null); cat /sys/wifi/wifiver 2>/dev/null; rm -rf " + q(STAGE);
            RootReader.Result r = RootReader.run(cmd, 15);
            finish(r.output + "\nRECOVERY_EXIT=" + r.code + "\n", "Wi-Fi normal / SELinux Enforcing restaurados.");
        });
    }

    private boolean enterBusy(String msg) {
        if (busy) return false;
        busy = true;
        ui.post(() -> { status.setText(msg); log.setText("Executando…"); });
        return true;
    }

    private void finish(String output, String msg) {
        ui.post(() -> { busy = false; status.setText(msg); log.setText(output); });
    }

    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private void post(String s) { ui.post(() -> status.setText(s)); }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        dst.setReadable(true, false);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }
    private TextView mono(String s) { TextView t=text(s,10,0xFFE0E0E0,false); t.setTypeface(android.graphics.Typeface.MONOSPACE); t.setTextIsSelectable(true); return t; }
    private TextView text(String s, float sp, int color, boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
