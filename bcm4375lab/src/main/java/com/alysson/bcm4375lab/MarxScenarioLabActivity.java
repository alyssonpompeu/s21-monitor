package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-scenario lab so we can advance several safe validation steps inside one APK.
 * Scenarios 0-3 never transmit RF. Scenario 4 delegates to the existing bounded
 * 0x630/0x631 firmware loader/probe, which also keeps TX disabled.
 */
public class MarxScenarioLabActivity extends Activity {
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, log;
    private Button runSafe, preflight, nexmon, afhds, tplram, recover;
    private volatile boolean busy;
    private Afhds2aEngine engine;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int stableId = getSharedPreferences("marx_lab", MODE_PRIVATE).getInt("txid", 0);
        if (stableId == 0) {
            stableId = new SecureRandom().nextInt();
            if (stableId == 0) stableId = 0x42A7105;
            getSharedPreferences("marx_lab", MODE_PRIVATE).edit().putInt("txid", stableId).apply();
        }
        engine = new Afhds2aEngine(stableId);
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
        r.setPadding(dp(18), dp(18), dp(18), dp(34));
        r.setBackgroundColor(0xFF071014);
        s.addView(r);

        r.addView(text("MARX V1.0 • MULTI-LAB", 28, Color.WHITE, true));
        r.addView(text("Vários cenários no mesmo APK • BCM4375B1 • AFHDS2A • TX bloqueado", 13, 0xFF80CBC4, false));
        r.addView(text("A ideia deste painel é evitar instalar um APK para cada microteste. Os cenários abaixo podem ser repetidos e o log fica reunido nesta tela. O teste de Template RAM continua usando o firmware MARX já incorporado.", 13, 0xFFCFD8DC, false));

        status = text("Pronto. Comece por EXECUTAR CENÁRIOS SEGUROS.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(16), 0, dp(8));
        r.addView(status);

        runSafe = button("EXECUTAR CENÁRIOS 0 → 3 (SEM RELOAD / SEM TX)");
        runSafe.setOnClickListener(v -> runSafeSuite());
        r.addView(runSafe);

        preflight = button("CENÁRIO 0 • PREFLIGHT / ROOT / SELINUX / WIFI");
        preflight.setOnClickListener(v -> runOne("preflight"));
        r.addView(preflight);

        nexmon = button("CENÁRIO 1 • NEXMON / PR663 / IOCTL 0x600");
        nexmon.setOnClickListener(v -> runOne("nexmon"));
        r.addView(nexmon);

        afhds = button("CENÁRIO 2 • AFHDS2A SELF-TEST / BIND DRY-RUN");
        afhds.setOnClickListener(v -> runOne("afhds"));
        r.addView(afhds);

        Button snapshot = button("CENÁRIO 3 • SNAPSHOT TÉCNICO DO SISTEMA");
        snapshot.setOnClickListener(v -> runOne("snapshot"));
        r.addView(snapshot);

        tplram = button("CENÁRIO 4 • CARREGAR MARX + TESTAR 0x630/0x631");
        tplram.setOnClickListener(v -> confirmTemplateRam());
        r.addView(tplram);

        recover = button("RECUPERAÇÃO • SELINUX ENFORCING + WIFI NORMAL");
        recover.setOnClickListener(v -> confirmRecovery());
        r.addView(recover);

        log = text("Nenhum cenário executado.", 11, 0xFFE0E0E0, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(16), 0, 0);
        r.addView(log);

        TextView note = text("Cenários 0–3 são somente leitura/cálculo local. O cenário 4 reinicia o Wi‑Fi para carregar o firmware MARX e executa o round-trip da Template RAM, mantendo sample playback/TX desligado.", 12, 0xFFFFAB91, false);
        note.setPadding(0, dp(18), 0, 0);
        r.addView(note);
        return s;
    }

    private void runSafeSuite() {
        if (busy) return;
        setBusy(true, "Executando cenários seguros 0 → 3…");
        worker.execute(() -> {
            StringBuilder out = new StringBuilder();
            out.append(runPreflight());
            out.append(runNexmon());
            out.append(runAfhds());
            out.append(runSnapshot());
            finish("Suite 0 → 3 concluída. Nenhum TX executado.", out.toString(), true);
        });
    }

    private void runOne(String which) {
        if (busy) return;
        setBusy(true, "Executando " + which + "…");
        worker.execute(() -> {
            String out;
            switch (which) {
                case "preflight": out = runPreflight(); break;
                case "nexmon": out = runNexmon(); break;
                case "afhds": out = runAfhds(); break;
                case "snapshot": out = runSnapshot(); break;
                default: out = "cenário desconhecido\n"; break;
            }
            finish("Cenário concluído. Nenhum TX executado.", out, true);
        });
    }

    private String runPreflight() {
        StringBuilder b = new StringBuilder("=== CENÁRIO 0 • PREFLIGHT ===\n");
        String id = rr("id", 4);
        String se = rr("getenforce 2>&1", 4).trim();
        String fp = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
        String wv = rr("cat /sys/wifi/wifiver 2>/dev/null", 4).trim();
        b.append("model=").append(Build.MODEL).append('\n');
        b.append("hardware=").append(Build.HARDWARE).append('\n');
        b.append("android=").append(Build.VERSION.RELEASE).append('\n');
        b.append("root=").append(id.contains("uid=0")).append('\n');
        b.append("selinux=").append(se).append('\n');
        b.append("firmware_class.path=").append(fp).append('\n');
        b.append("wifiver=").append(wv).append("\n\n");
        return b.toString();
    }

    private String runNexmon() {
        String out = rr(nativeNexProbe() + " wlan0", 8);
        boolean present = out.contains("NEXPROBE_PR663_600=true") || out.contains("TRIAGE_RESULT=NEXMON_PRESENT");
        return "=== CENÁRIO 1 • NEXMON ===\nNEXMON_PRESENT=" + present + "\n" + out + "\n";
    }

    private String runAfhds() {
        int[] channels = {1000, 1500, 1500, 1500};
        byte[] b1 = engine.buildBindPacket(1);
        byte[] b2 = engine.buildBindPacket(2);
        byte[] data = engine.buildSticksPacket(channels);
        boolean sizes = b1.length == Afhds2aEngine.TX_PACKET_SIZE &&
                b2.length == Afhds2aEngine.TX_PACKET_SIZE &&
                data.length == Afhds2aEngine.TX_PACKET_SIZE;
        boolean types = (b1[0] & 0xff) == 0xbb && (b2[0] & 0xff) == 0xbc && (data[0] & 0xff) == 0x58;
        StringBuilder b = new StringBuilder("=== CENÁRIO 2 • AFHDS2A SELF-TEST ===\n");
        b.append("period_us=").append(Afhds2aEngine.PERIOD_US).append('\n');
        b.append("packet_sizes_ok=").append(sizes).append('\n');
        b.append("packet_types_ok=").append(types).append('\n');
        b.append("hops=\n").append(engine.describeHops()).append('\n');
        b.append("bind1=").append(Afhds2aEngine.hex(b1)).append('\n');
        b.append("bind2=").append(Afhds2aEngine.hex(b2)).append('\n');
        b.append("data58=").append(Afhds2aEngine.hex(data)).append('\n');
        b.append("RF_TX=0\n\n");
        return b.toString();
    }

    private String runSnapshot() {
        String cmd = "echo '=== CENÁRIO 3 • SNAPSHOT ==='; date; uname -a; " +
                "echo '--- id'; id; echo '--- selinux'; getenforce 2>&1; " +
                "echo '--- wifiver'; cat /sys/wifi/wifiver 2>/dev/null; " +
                "echo '--- fwclass'; cat " + FWCLASS + " 2>/dev/null; " +
                "echo '--- dhd params'; for f in /sys/module/dhd/parameters/firmware_path /sys/module/dhd/parameters/nvram_path; do [ -e \"$f\" ] && echo \"$f=$(cat $f 2>/dev/null)\"; done; " +
                "echo '--- wlan0'; ip link show wlan0 2>/dev/null || true";
        return rr(cmd, 8) + "\n";
    }

    private void confirmTemplateRam() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Cenário 4 — carregar firmware MARX?")
                .setMessage("Este cenário reinicia o Wi‑Fi e executa os IOCTLs 0x630/0x631. O teste salva/escreve/lê/restaura uma pequena área de Template RAM. Sample playback e TX permanecem desligados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> {
                    Intent i = new Intent(this, Rx42PhyProbeV1Activity.class);
                    i.putExtra("auto_run", true);
                    startActivity(i);
                }).show();
    }

    private void confirmRecovery() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Restaurar estado seguro?")
                .setMessage("Força SELinux Enforcing, firmware_class.path=/vendor/firmware e reinicia o Wi‑Fi em modo normal.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (d,w) -> recover()).show();
    }

    private void recover() {
        setBusy(true, "Restaurando estado seguro…");
        worker.execute(() -> {
            StringBuilder b = new StringBuilder("=== RECOVERY ===\n");
            b.append(rr("printf '%s' '/vendor/firmware' > " + FWCLASS + " 2>&1 || true; setenforce 1 2>&1 || true; svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 13));
            b.append("\nSELinux=").append(rr("getenforce 2>&1", 4).trim());
            b.append("\nfwclass=").append(rr("cat " + FWCLASS + " 2>/dev/null", 4).trim());
            b.append("\nwifiver=\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null", 4));
            finish("Recuperação executada.", b.toString(), true);
        });
    }

    private void setBusy(boolean v, String msg) {
        busy = v;
        runSafe.setEnabled(!v); preflight.setEnabled(!v); nexmon.setEnabled(!v); afhds.setEnabled(!v); tplram.setEnabled(!v); recover.setEnabled(!v);
        status.setTextColor(0xFFFFD180);
        status.setText(msg);
    }

    private void finish(String msg, String out, boolean ok) {
        ui.post(() -> {
            setBusy(false, msg);
            status.setTextColor(ok ? 0xFF81C784 : 0xFFEF9A9A);
            log.setText(out);
        });
    }

    private String nativeNexProbe() { return q(getApplicationInfo().nativeLibraryDir + "/libnexprobe.so"); }
    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(9); b.setLayoutParams(p); return b;
    }
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
