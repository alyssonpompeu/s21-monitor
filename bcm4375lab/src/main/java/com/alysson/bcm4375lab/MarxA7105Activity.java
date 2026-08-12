package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MARX A7105 V1.0
 *
 * Single-apk laboratory centered on the actual architecture we need:
 *   FS-i6 RF behaviour -> Virtual A7105 -> AFHDS2A -> GFSK/IQ -> BCM4375 backend.
 *
 * This build deliberately separates "protocol ready" from "RF backend ready".
 * It never claims a bind from a local packet-generation success.
 */
public class MarxA7105Activity extends Activity {
    private static final int TX_ID = 0x86A39073;
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Afhds2aEngine afhds = new Afhds2aEngine(TX_ID);

    private TextView headline, state, log;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        refreshState();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(40));
        root.setBackgroundColor(0xFF071014);
        scroll.addView(root);

        root.addView(text("MARX A7105 V1.0", 28, Color.WHITE, true));
        root.addView(text("FS-i6 RF logic • Virtual A7105 • AFHDS2A • BCM4375B1 • COREREV82", 13, 0xFF80CBC4, false));

        headline = text("Arquitetura integrada. Nenhum resultado local é tratado como BIND sem resposta real do MA-RX42.", 14, 0xFFFFD180, true);
        headline.setPadding(0, dp(12), 0, dp(8));
        root.addView(headline);

        state = mono("", 11, 0xFFE0E0E0);
        root.addView(state);

        root.addView(section("ETAPA A — CONTROLE / A7105 VIRTUAL"));
        root.addView(button("1. FS-i6 + A7105 VIRTUAL (SEM RF)", v -> runVirtualA7105()));
        root.addView(button("2. GERAR BIND + HOPS + DATA 0x58 (SEM RF)", v -> runProtocolVectors()));
        root.addView(button("3. GERAR GFSK/IQ CANDIDATO (SEM RF)", v -> runNative("gfskdry")));

        root.addView(section("ETAPA B — S21 / KERNEL / DHD"));
        root.addView(button("4. MAPEAR ROOT + KERNEL + DHD + FIRMWARE", v -> runSystemMap()));
        root.addView(button("5. PROVAR TRANSPORTE NEXMON / PR663", v -> runNative("nexmon")));
        root.addView(button("6. PROVAR WLC_PHY_SAMPLE_COLLECT (RX IQ)", v -> runNative("sample307")));

        root.addView(section("ETAPA C — FIRMWARE BCM4375"));
        root.addView(button("7. ABRIR PROBE 0x630/0x631 COM FIRMWARE CONHECIDO", v -> {
            startActivity(new Intent(this, Rx42PhyProbeV1Activity.class));
        }));
        root.addView(button("8. INVENTÁRIO DO BACKEND SDR / KERNEL BRIDGE", v -> runNative("backend")));

        root.addView(section("ETAPA D — LINK"));
        root.addView(button("9. AVALIAR PRONTIDÃO PARA TX/BIND", v -> evaluateTxReadiness()));
        root.addView(button("10. TENTAR LINK SOMENTE SE BACKEND ESTIVER COMPROVADO", v -> confirmLink()));

        root.addView(section("RECUPERAÇÃO"));
        root.addView(button("11. RESTAURAR WIFI + SELINUX ENFORCING", v -> recover()));

        root.addView(section("O QUE ESTE APK ESTÁ FAZENDO"));
        root.addView(text(
                "O firmware do FS-i6 não é simplesmente executado pelo Wi-Fi. O app replica a parte RF observável do FS-i6 e apresenta uma interface de A7105 virtual. " +
                "O backend BCM4375 é tratado separadamente. O objetivo é substituir A7105_WriteReg/SetChannel/WriteFIFO/TX/RX por operações equivalentes no firmware/driver do BCM4375. " +
                "A recepção é investigada também pelo comando Broadcom WLC_PHY_SAMPLE_COLLECT (307), pois AFHDS2A precisa aprender o RX ID durante bind.",
                12, 0xFFB0BEC5, false));

        root.addView(section("SEGURANÇA DE BANCADA"));
        root.addView(text("Motor/hélice desconectados. Os botões 1–9 não devem iniciar transmissão AFHDS2A contínua. O botão 10 é bloqueado enquanto o backend não produzir evidência explícita de TX arbitrário controlável e restaurável.", 12, 0xFFFFAB91, false));

        log = mono("Nenhum cenário executado.", 10, 0xFFE0E0E0);
        log.setPadding(0, dp(14), 0, 0);
        root.addView(log);
        return scroll;
    }

    private void refreshState() {
        state.setText("TX ID: " + String.format(Locale.US, "%08X", TX_ID) +
                "\nA7105 ID: 5475C52A" +
                "\nAFHDS2A: 38-byte TX • 16 hops • 3850 us" +
                "\nRX ID: FF FF FF FF (até resposta 0xBC real)" +
                "\nRF backend: NÃO COMPROVADO" +
                "\nFull bind: NÃO CONFIRMADO");
    }

    private void runVirtualA7105() {
        if (!enterBusy("Montando A7105 virtual…")) return;
        worker.execute(() -> {
            StringBuilder b = new StringBuilder();
            b.append("=== VIRTUAL A7105 / FS-i6 ===\n");
            b.append(FsI6ReverseProfile.summary()).append("\n\n");
            b.append("REGISTERS (45 pairs):\n").append(FsI6ReverseProfile.compactRegisterDump()).append("\n");
            b.append("\nA7105 API EMULADA:\n");
            b.append("reset() -> profile 2.0.17\n");
            b.append("writeId(0x5475C52A)\n");
            b.append("setDataRate(reg0E=00 -> 500 kbps profile)\n");
            b.append("setFifoLen(reg03=25 -> 38 bytes)\n");
            b.append("setChannel(AFHDS2A channel)\n");
            b.append("writeFifo(38 bytes)\n");
            b.append("strobeTx() -> exige backend BCM4375\n");
            b.append("strobeRx() -> exige RX IQ/demod\n");
            b.append("\nVIRTUAL_A7105_RESULT=PASS\nRF_SENT=0\n");
            finish(b.toString(), "A7105 virtual pronto. Ainda sem RF.");
        });
    }

    private void runProtocolVectors() {
        if (!enterBusy("Gerando vetores AFHDS2A…")) return;
        worker.execute(() -> {
            StringBuilder b = new StringBuilder();
            b.append("=== AFHDS2A / FS-i6 VECTORS ===\n");
            b.append(afhds.describeIds()).append('\n');
            b.append("HOPS=").append(afhds.describeHops()).append('\n');
            b.append("BIND_CHANNEL_A=0x0D\nBIND_CHANNEL_B=0x8C\nPERIOD_US=3850\n");
            for (int p=1;p<=4;p++) b.append("BIND").append(p).append('=').append(Afhds2aEngine.hex(afhds.buildBindPacket(p))).append('\n');
            int[] ch = new int[14];
            java.util.Arrays.fill(ch, 1500); ch[0] = 1000;
            b.append("DATA_0x58=").append(Afhds2aEngine.hex(afhds.buildSticksPacket(ch))).append('\n');
            b.append("FAILSAFE_0x56=").append(Afhds2aEngine.hex(afhds.buildFailsafePacket(ch, true))).append('\n');
            b.append("SETTINGS_0xAA=").append(Afhds2aEngine.hex(afhds.buildSettingsPacket())).append('\n');
            b.append("PROTOCOL_ENGINE_RESULT=PASS\nRF_SENT=0\n");
            finish(b.toString(), "AFHDS2A gerado. O gargalo continua sendo o backend RF.");
        });
    }

    private void runSystemMap() {
        if (!enterBusy("Mapeando S21…")) return;
        worker.execute(() -> {
            String cmd = "echo '=== ID ==='; id; " +
                    "echo '=== SELINUX ==='; getenforce; " +
                    "echo '=== KERNEL ==='; uname -a; " +
                    "echo '=== DEVICE ==='; getprop ro.product.model; getprop ro.hardware; " +
                    "echo '=== WIFI VER ==='; cat /sys/wifi/wifiver 2>/dev/null; " +
                    "echo '=== FWCLASS ==='; cat " + FWCLASS + " 2>/dev/null; " +
                    "echo '=== DHD ==='; ls -ld /sys/module/dhd 2>/dev/null; cat /sys/module/dhd/version 2>/dev/null; " +
                    "echo '=== DHD PARAMS ==='; ls /sys/module/dhd/parameters 2>/dev/null | head -80; " +
                    "echo '=== MARX KERNEL BRIDGE ==='; ls -l /dev/marxrf 2>/dev/null || echo MARXRF_DEVICE=ABSENT; " +
                    "echo '=== KALLSYMS ==='; test -r /proc/kallsyms && echo KALLSYMS_READABLE=1 || echo KALLSYMS_READABLE=0; " +
                    "echo '=== MODULES ==='; cat /proc/modules 2>/dev/null | grep -E 'dhd|bcmdhd|cfg80211' || true";
            RootReader.Result r = RootReader.run(cmd, 12);
            finish(r.output + "\nSYSTEM_MAP_EXIT=" + r.code + "\n", "Mapa kernel/DHD concluído.");
        });
    }

    private void runNative(String mode) {
        if (!enterBusy("Executando " + mode + "…")) return;
        worker.execute(() -> {
            File exe = new File(getApplicationInfo().nativeLibraryDir, "libmarxa7105probe.so");
            String cmd = "chmod 0755 " + q(exe.getAbsolutePath()) + " 2>/dev/null || true; " + q(exe.getAbsolutePath()) + " wlan0 " + mode;
            RootReader.Result r = RootReader.run(cmd, 18);
            finish(r.output + "\nNATIVE_EXIT=" + r.code + "\n", "Cenário " + mode + " concluído.");
        });
    }

    private void evaluateTxReadiness() {
        if (!enterBusy("Avaliando backend…")) return;
        worker.execute(() -> {
            File exe = new File(getApplicationInfo().nativeLibraryDir, "libmarxa7105probe.so");
            RootReader.Result r = RootReader.run(q(exe.getAbsolutePath()) + " wlan0 backend", 10);
            String o = r.output;
            boolean ready = o.contains("ARBITRARY_TX_BACKEND=READY") && o.contains("RX_PATH=") && !o.contains("RX_PATH=NONE");
            StringBuilder b = new StringBuilder(o);
            b.append("\n=== DECISION ===\n");
            b.append("PROTOCOL_READY=1\nVIRTUAL_A7105_READY=1\n");
            b.append("ARBITRARY_TX_READY=").append(o.contains("ARBITRARY_TX_BACKEND=READY")?1:0).append('\n');
            b.append("RX_REPLY_PATH_READY=").append(o.contains("RX_PATH=NONE")?0:1).append('\n');
            b.append("FULL_BIND_GATE=").append(ready?"OPEN":"CLOSED").append('\n');
            finish(b.toString(), ready ? "Backend parece pronto para um teste limitado." : "Bind continua bloqueado: falta backend TX arbitrário e/ou RX.");
        });
    }

    private void confirmLink() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Teste de link AFHDS2A")
                .setMessage("Motor/hélice precisam estar desconectados. Este botão NÃO força transmissão se o backend não provar ARBITRARY_TX_BACKEND=READY. O app não aceita TX_TRIGGERED sozinho como prova de bind; é necessária resposta 0xBC/RX ID real.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Verificar e tentar", (d,w) -> gatedLink())
                .show();
    }

    private void gatedLink() {
        if (!enterBusy("Verificando gate de link…")) return;
        worker.execute(() -> {
            File exe = new File(getApplicationInfo().nativeLibraryDir, "libmarxa7105probe.so");
            RootReader.Result c = RootReader.run(q(exe.getAbsolutePath()) + " wlan0 backend", 10);
            StringBuilder b = new StringBuilder(c.output);
            if (!c.output.contains("ARBITRARY_TX_BACKEND=READY")) {
                b.append("\nLINK_ABORTED=ARBITRARY_TX_BACKEND_NOT_READY\nRF_AFHDS2A_SENT=0\n");
                finish(b.toString(), "Link bloqueado corretamente: backend SDR ainda não comprovado.");
                return;
            }
            b.append("\nLINK_GATE_TX=OPEN\n");
            b.append("RX_ID_REQUIRED=1\nRX_ID_CURRENT=FFFFFFFF\n");
            b.append("LINK_ABORTED=RX_DEMODULATOR_NOT_CONFIRMED\nRF_AFHDS2A_SENT=0\n");
            finish(b.toString(), "TX poderia ser habilitado, mas o bind completo continua bloqueado até RX IQ/demod estar confirmado.");
        });
    }

    private void recover() {
        if (!enterBusy("Restaurando…")) return;
        worker.execute(() -> {
            String cmd = "printf '%s' /vendor/firmware > " + FWCLASS + " 2>/dev/null || true; " +
                    "setenforce 1 2>/dev/null || true; svc wifi disable; sleep 2; " +
                    "setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4; " +
                    "echo SELINUX=$(getenforce); echo FWCLASS=$(cat " + FWCLASS + " 2>/dev/null); cat /sys/wifi/wifiver 2>/dev/null";
            RootReader.Result r = RootReader.run(cmd, 14);
            finish(r.output + "\nRECOVERY_EXIT=" + r.code + "\n", "Recuperação concluída.");
        });
    }

    private boolean enterBusy(String msg) {
        if (busy) return false;
        busy = true;
        ui.post(() -> { headline.setText(msg); log.setText("Executando…"); });
        return true;
    }

    private void finish(String output, String msg) {
        ui.post(() -> {
            busy = false;
            headline.setText(msg);
            log.setText(output);
            refreshState();
        });
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setGravity(Gravity.CENTER); b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(8); b.setLayoutParams(p);
        return b;
    }
    private TextView section(String s){TextView t=text(s,13,0xFF80CBC4,true);t.setPadding(0,dp(18),0,dp(4));return t;}
    private TextView text(String s,float sp,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private TextView mono(String s,float sp,int c){TextView t=text(s,sp,c,false);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);return t;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
}
