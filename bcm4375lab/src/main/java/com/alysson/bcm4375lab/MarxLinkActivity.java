package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MARX LINK V1.0
 *
 * A single APK for the BCM4375B1 -> AFHDS2A experiment.  It ports the
 * architecture of Nexmon SDR to the modern d11ac register block instead of
 * repeatedly guessing the legacy tplate portal.  Every action stages the
 * experimental firmware, executes a bounded scenario, and restores the stock
 * Wi-Fi firmware + SELinux Enforcing in finally.
 */
public class MarxLinkActivity extends Activity {
    private static final String FWCLASS = "/sys/module/firmware_class/parameters/path";
    private static final String STAGE = "/data/vendor/wifi/marx_link_v1";
    private static final String ASSET = "nexmon/bcmdhd_sta_marx_link_v1.bin";
    private static final String EXPECTED_SHA = "MARX_LINK_V1_SHA";
    private static final int DEFAULT_TXID = 0x86A39073; // preserves the ID used in the previous MARX/RX42 lab

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, state, log;
    private Button autoMap, pulse, bind, recover;
    private volatile boolean busy;
    private int txId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        txId = getSharedPreferences("marx_link", MODE_PRIVATE).getInt("txid", DEFAULT_TXID);
        getSharedPreferences("marx_link", MODE_PRIVATE).edit().putInt("txid", txId).apply();
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
        r.setPadding(dp(18), dp(18), dp(18), dp(36));
        r.setBackgroundColor(0xFF071014);
        s.addView(r);

        r.addView(text("MARX LINK V1.0", 29, Color.WHITE, true));
        r.addView(text("Galaxy S21 • BCM4375B1 • Nexmon PR663 • AFHDS2A/A7105", 13, 0xFF80CBC4, false));
        r.addView(text("Novo método: usa o bloco D11AC moderno (SamplePlayStart/Stop, XmtTemplateDataLo/Hi/Ptr e SampleCollectPlayCtrl), seguindo a arquitetura do Nexmon SDR. O portal legado 0x130/0x134 que repetiu a última word não é usado para o caminho LINK.", 13, 0xFFCFD8DC, false));

        status = text("Pronto. Execute 1 → 2. O passo 3 só é liberado logicamente se houver atividade no backend de sample-play.", 15, 0xFFFFD180, true);
        status.setPadding(0, dp(16), 0, dp(8));
        r.addView(status);

        state = mono("TX ID AFHDS2A: " + String.format(Locale.US, "%08X", txId) +
                "\nRF: NÃO VALIDADO\nBIND: NÃO CONFIRMADO\nTX contínuo: DESABILITADO", 12, 0xFFE0E0E0);
        r.addView(state);

        autoMap = button("1. AUTO MAPEAR SDR / D11AC (SEM TX)");
        autoMap.setOnClickListener(v -> runExperiment("caps_portal"));
        r.addView(autoMap);

        pulse = button("2. TESTAR PULSO RF CURTO / 3 MODOS");
        pulse.setOnClickListener(v -> confirmPulse());
        r.addView(pulse);

        bind = button("3. TENTAR INICIAR BIND AFHDS2A (EXPERIMENTAL)");
        bind.setOnClickListener(v -> confirmBind());
        r.addView(bind);

        recover = button("4. RECUPERAR WIFI + SELINUX ENFORCING");
        recover.setOnClickListener(v -> confirmRecovery());
        r.addView(recover);

        r.addView(section("COMO O PASSO 3 FUNCIONA"));
        r.addView(text("O app monta o BIND1 de 38 bytes, ID A7105 54 75 C5 2A, alterna os canais de bind 0x8C/0x0D e gera três candidatos de codificação física a 500 kbit/s. O sinal é sintetizado como IQ/GFSK e entregue ao backend BCM4375. Como ainda não temos RX GFSK arbitrário no telefone, este build não inventa RX ID: ele tenta provocar a primeira reação do receptor e registra FULL_BIND_CONFIRMED=0 até existir o caminho de recepção.", 12, 0xFFB0BEC5, false));

        r.addView(section("SEGURANÇA"));
        r.addView(text("Remova hélice e desconecte o motor/ESC durante todos os testes. O pulso é curto e a amplitude IQ foi mantida baixa, mas é RF experimental em 2,4 GHz. Não execute perto de modelos em uso. O app sempre tenta restaurar /vendor/firmware, Wi-Fi normal e SELinux Enforcing.", 12, 0xFFFFAB91, false));

        log = mono("Nenhum cenário executado.", 10, 0xFFE0E0E0);
        log.setPadding(0, dp(16), 0, 0);
        r.addView(log);
        return s;
    }

    private void confirmPulse() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Pulso RF experimental")
                .setMessage("Confirme que hélice/motor estão desconectados. O app carregará o firmware MARX LINK e testará até três modos de SampleCollectPlayCtrl com janelas de aproximadamente 100 µs. Não envia AFHDS2A neste passo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d,w) -> runExperiment("pulse_matrix"))
                .show();
    }

    private void confirmBind() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Tentar bind AFHDS2A?")
                .setMessage("Mantenha o MA-RX42 em modo BIND e deixe motor/hélice desconectados. O app primeiro procura atividade no backend SDR. Se encontrar, transmite uma sequência curta de BIND1 usando três candidatos de PHY. Ainda não há demodulador RX A7105 no S21, portanto o app não afirmará bind completo sem RX ID.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Tentar", (d,w) -> runExperiment("bind_auto"))
                .show();
    }

    private void confirmRecovery() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Restaurar estado seguro?")
                .setMessage("Força firmware_class.path=/vendor/firmware, SELinux Enforcing e reinicia o Wi-Fi em modo normal.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (d,w) -> runRecovery())
                .show();
    }

    private void runExperiment(String mode) {
        if (busy) return;
        setBusy(true, "Preparando " + mode + "…");
        worker.execute(() -> {
            StringBuilder tr = new StringBuilder();
            String originalPath = "/vendor/firmware";
            boolean backendActivity = false;
            boolean staged = false;
            try {
                RootReader.Result id = RootReader.run("id", 4);
                String se = rr("getenforce 2>&1", 4).trim();
                originalPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                if (originalPath.isEmpty()) originalPath = "/vendor/firmware";
                tr.append("=== MARX LINK EXPERIMENT mode=").append(mode).append(" ===\n")
                  .append("root=").append(id.output.contains("uid=0")).append("\nSELinux=").append(se)
                  .append("\noriginal_fwclass=").append(originalPath).append('\n');
                if (!id.output.contains("uid=0") || !"Enforcing".equalsIgnoreCase(se))
                    throw new Exception("preflight exige root + SELinux Enforcing");

                post("Extraindo e validando firmware MARX LINK…");
                File src = new File(getFilesDir(), "bcmdhd_sta_marx_link_v1.bin");
                copyAsset(ASSET, src);
                String stageCmd = "rm -rf " + q(STAGE) + "; mkdir -p " + q(STAGE) +
                        "; cp " + q(src.getAbsolutePath()) + " " + q(STAGE + "/bcmdhd_sta.bin_b1") +
                        "; cp /vendor/firmware/bcmdhd_clm.blob " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; chown -R wifi:wifi " + q(STAGE) + "; chmod 0755 " + q(STAGE) +
                        "; chmod 0644 " + q(STAGE + "/bcmdhd_sta.bin_b1") + " " + q(STAGE + "/bcmdhd_clm.blob") +
                        "; restorecon -RF " + q(STAGE) + " 2>&1 || true";
                tr.append("=== STAGE ===\n").append(rr(stageCmd, 9));
                String sha = rr("sha256sum " + q(STAGE + "/bcmdhd_sta.bin_b1") + " | awk '{print $1}'", 4).trim();
                tr.append("staged_sha=").append(sha).append("\nexpected_sha=").append(EXPECTED_SHA).append('\n');
                if (!EXPECTED_SHA.equalsIgnoreCase(sha)) throw new Exception("SHA do firmware não confere");
                staged = true;

                post("Entrando em B1 Monitor e recarregando firmware…");
                rr("svc wifi disable; sleep 2; setprop vendor.wlandriver.mode monitor; setprop ctl.start mfgloader; sleep 3", 10);
                String mon = rr("cat /sys/wifi/wifiver 2>/dev/null", 4);
                tr.append("=== MONITOR ===\n").append(mon);
                if (!mon.contains("B1 Monitor")) throw new Exception("B1 Monitor não confirmado");

                rr("printf '%s' " + q(STAGE) + " > " + FWCLASS, 4);
                String setPath = rr("cat " + FWCLASS + " 2>/dev/null", 4).trim();
                tr.append("FWCLASS_STAGE=").append(setPath).append('\n');
                if (!STAGE.equals(setPath)) throw new Exception("firmware_class.path recusou staging");

                rr("setenforce 0", 3);
                tr.append("SELINUX_LOAD=").append(rr("getenforce 2>&1", 3).trim()).append('\n');
                rr("setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 12);
                tr.append("=== EXPERIMENTAL NETWORK ===\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null",4)).append('\n');

                if ("caps_portal".equals(mode)) {
                    post("Mapeando D11AC e portal XmtTemplate…");
                    tr.append("=== CAPS 0x643 ===\n").append(rr(nativeLink()+" wlan0 caps", 10));
                    tr.append("\n=== PORTAL 0x644 ===\n").append(rr(nativeLink()+" wlan0 portal", 10));
                } else if ("pulse_matrix".equals(mode)) {
                    for (int ctrl=1; ctrl<=3; ctrl++) {
                        post("Pulso curto: modo " + ctrl + "/3…");
                        String o = rr(nativeLink()+" wlan0 pulse "+ctrl+" 1", 12);
                        tr.append("\n=== PULSE CTRL ").append(ctrl).append(" ===\n").append(o);
                        if (o.contains("MARX_PLAY_RESULT=ACTIVITY_OBSERVED") || o.contains("TX_TRIGGERED=1")) {
                            backendActivity = true;
                            tr.append("BACKEND_ACTIVITY_SELECTED_CTRL=").append(ctrl).append('\n');
                            break;
                        }
                    }
                    tr.append("BACKEND_ACTIVITY=").append(backendActivity).append('\n');
                } else if ("bind_auto".equals(mode)) {
                    int selected = 0;
                    for (int ctrl=1; ctrl<=3; ctrl++) {
                        post("Validando backend antes do bind: modo " + ctrl + "…");
                        String o = rr(nativeLink()+" wlan0 pulse "+ctrl+" 1", 12);
                        tr.append("\n=== PRE-BIND PULSE CTRL ").append(ctrl).append(" ===\n").append(o);
                        if (o.contains("MARX_PLAY_RESULT=ACTIVITY_OBSERVED") || o.contains("TX_TRIGGERED=1")) { selected=ctrl; backendActivity=true; break; }
                    }
                    if (!backendActivity) {
                        tr.append("BIND_ABORTED=BACKEND_NO_ACTIVITY\nRF_AFHDS2A_SENT=0\n");
                    } else {
                        for (int profile=0; profile<=2; profile++) {
                            post("AFHDS2A bind candidato " + (profile+1) + "/3… observe o LED do RX42");
                            String cmd = nativeLink()+" wlan0 bind "+String.format(Locale.US,"%08X",txId)+" "+profile+" "+selected+" 1 6";
                            String o = rr(cmd, 25);
                            tr.append("\n=== AFHDS2A PHY PROFILE ").append(profile).append(" ===\n").append(o);
                        }
                        tr.append("RF_AFHDS2A_CANDIDATES_SENT=1\n")
                          .append("RX_DEMODULATOR_AVAILABLE=0\nRX_ID_LEARNED=0\nFULL_BIND_CONFIRMED=0\n")
                          .append("OBSERVE_RECEIVER_LED=1\n");
                    }
                }
            } catch (Exception e) {
                tr.append("EXCEPTION=").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            } finally {
                post("Restaurando firmware normal e SELinux…");
                try {
                    rr("printf '%s' " + q(originalPath) + " > " + FWCLASS + " 2>&1 || true; setenforce 1 2>&1 || true; svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4", 14);
                    tr.append("=== FINALLY / RESTORE ===\nfinal_SELinux=").append(rr("getenforce 2>&1",4).trim())
                      .append("\nfinal_fwclass=").append(rr("cat "+FWCLASS+" 2>/dev/null",4).trim())
                      .append("\nfinal_wifiver=\n").append(rr("cat /sys/wifi/wifiver 2>/dev/null",4));
                    if (staged) rr("rm -rf " + q(STAGE), 4);
                    boolean restoreOk = "Enforcing".equalsIgnoreCase(rr("getenforce 2>&1",3).trim()) &&
                            "/vendor/firmware".equals(rr("cat "+FWCLASS+" 2>/dev/null",3).trim());
                    tr.append("\nRESTORE_STATE=").append(restoreOk?"PASS":"CHECK_MANUALLY").append('\n');
                } catch (Exception ignored) {}
            }

            boolean activity = backendActivity;
            String shown = tr.toString();
            ui.post(() -> {
                setBusy(false, "Concluído. Consulte o log abaixo.");
                log.setText(shown);
                status.setTextColor(0xFF81C784);
                if (activity) state.setText("TX ID AFHDS2A: "+String.format(Locale.US,"%08X",txId)+"\nBACKEND SAMPLE-PLAY: ATIVIDADE OBSERVADA\nBIND COMPLETO: AINDA NÃO CONFIRMADO\nTX contínuo: DESABILITADO");
            });
        });
    }

    private void runRecovery() {
        if (busy) return;
        setBusy(true, "Restaurando…");
        worker.execute(() -> {
            String o = rr("printf '%s' '/vendor/firmware' > " + FWCLASS + " 2>&1 || true; setenforce 1 2>&1 || true; svc wifi disable; sleep 2; setprop vendor.wlandriver.mode normal; setprop ctl.start mfgloader; sleep 3; svc wifi enable; sleep 4; echo SELINUX=$(getenforce); echo FWCLASS=$(cat "+FWCLASS+" 2>/dev/null); cat /sys/wifi/wifiver 2>/dev/null", 14);
            ui.post(() -> { setBusy(false,"Recuperação concluída."); log.setText("=== RECOVERY ===\n"+o); });
        });
    }

    private String nativeLink() { return q(getApplicationInfo().nativeLibraryDir + "/libmarxlinkprobe.so"); }
    private String rr(String cmd, long timeout) { return RootReader.run(cmd, timeout).output; }
    private void post(String s) { ui.post(() -> status.setText(s)); }

    private void copyAsset(String asset, File dst) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n; while ((n=in.read(b))>0) out.write(b,0,n);
        }
        dst.setReadable(true,false);
    }

    private void setBusy(boolean b, String msg) {
        busy=b;
        if (autoMap!=null) autoMap.setEnabled(!b);
        if (pulse!=null) pulse.setEnabled(!b);
        if (bind!=null) bind.setEnabled(!b);
        if (recover!=null) recover.setEnabled(!b);
        status.setText(msg); status.setTextColor(0xFFFFD180);
    }
    private TextView section(String s){TextView t=text(s,13,0xFF80CBC4,true);t.setPadding(0,dp(18),0,dp(5));return t;}
    private TextView text(String s,float sp,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private TextView mono(String s,float sp,int c){TextView t=text(s,sp,c,false);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(9);b.setLayoutParams(p);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
}
