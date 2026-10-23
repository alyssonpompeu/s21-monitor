package com.alysson.g533bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder report = new StringBuilder();
    private TextView status, output;
    private volatile boolean collecting = false;

    private static final String SUMMARY =
        "G533 Bridge Preflight 1.0 — SOMENTE LEITURA\n" +
        "Alvo: Logitech G533 headset A-00072 e dongle A-00073.\n" +
        "RF documentado pela FCC: 2403,35 a 2477,35 MHz.\n" +
        "Firmware Logitech V1.13, cliente e host, extraído do updater.\n" +
        "BCM4375B1/18.41.117: pesquisar patch Nexmon PR #663.\n" +
        "Este app NÃO conecta o G533 e NÃO ativa patch/monitor/SDR.\n" +
        "Monitor 802.11 não equivale a demodular o protocolo Avnera.\n";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setOrientation(LinearLayout.VERTICAL);
        sc.addView(root);
        root.addView(txt("G533 Bridge Preflight", 24));
        root.addView(txt("Diagnóstico real do controlador do S21 • nenhuma gravação", 14));
        status = txt("Pronto", 16);
        root.addView(status);
        root.addView(button("1. Diagnóstico sem root", v -> start(false)));
        root.addView(button("2. Diagnóstico ROOT — somente leitura", v -> start(true)));
        root.addView(button("3. Ver MAC-config do G533", v -> showMac()));
        root.addView(button("4. Compartilhar relatório", v -> share()));
        output = txt("", 12);
        output.setTextIsSelectable(true);
        root.addView(output);
        setContentView(sc);
        log(SUMMARY);
        log("Identidade Android: " + Build.MANUFACTURER + " " + Build.MODEL +
            " / Android " + Build.VERSION.RELEASE);
    }

    private void showMac() {
        log("=== CONFIGURACOES EXTRAIDAS DO FIRMWARE ===");
        log("Client/headset, offset 0x4F17, 26 bytes:");
        log("86 16 00 63 06 D2 CB 01 0D 03 06 11 B1 00 00 00 00 01 01 04 0C B1 00 00 00 00");
        log("Host/dongle, offset 0x10000, 18 bytes:");
        log("86 0E 00 6B 06 D2 D8 01 0C 03 05 D3 C0 00 00 00 00 01");
        log("O tipo de recurso 0x86 e comum. Campos de RF/pareamento ainda nao decodificados.");
        log("Client SHA256: c3624e58de9f93414ed94c0ab3690ccef35a82dbb525cf600b00ac5b68e67ba3");
        log("Host SHA256: 94f705f835e31f902dfbec77ef872c0fab5cf5339ccf756baa49173d85d5e7a2");
    }

    private void start(boolean root) {
        if (collecting) { log("Coleta em andamento."); return; }
        collecting = true;
        set(root ? "Lendo diagnosticos ROOT..." : "Lendo diagnosticos Android...");
        worker.execute(() -> {
            try {
                log("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())
                    + " / " + (root ? "root solicitado" : "sem root") + " ===");
                String[] names = {
                    "Identidade e firmware carregado",
                    "Modelo, kernel e permissoes",
                    "Interfaces de rede e modulos",
                    "Possiveis arquivos de firmware Broadcom",
                    "Ferramentas instaladas"
                };
                String[] commands = {
                    "cat /sys/wifi/wifiver 2>&1; getprop ro.build.display.id; getprop ro.product.model",
                    "id; uname -r; getenforce 2>/dev/null; getprop ro.board.platform",
                    "cat /proc/modules 2>/dev/null | grep -Ei 'bcmdhd|dhd|bluetooth' | head -n 10; ip -o link show wlan0 2>&1",
                    "for d in /vendor/firmware /vendor/etc/firmware /odm/firmware /system/vendor/firmware; do if [ -d \"$d\" ]; then echo DIR:$d; ls \"$d\" 2>/dev/null | grep -Ei '4375|bcmdhd|hcd|bluetooth|patchram' | head -n 40; fi; done",
                    "for a in iw nexutil wl su; do command -v \"$a\" 2>&1; done; cat /proc/net/wireless 2>/dev/null | head -n 8"
                };
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < commands.length; i++) {
                    log("--- " + names[i] + " ---");
                    String result = shell(root, commands[i]);
                    log(result);
                    all.append(result).append('\n');
                }
                boolean correct = all.indexOf("18.41.117") >= 0;
                log(correct
                    ? "FW 18.41.117 DETECTADO. PR #663 e somente um candidato experimental."
                    : "FW 18.41.117 NAO CONFIRMADO. Verifique se /sys/wifi/wifiver e legivel.");
                if (root && all.indexOf("uid=0") < 0)
                    log("ROOT NAO CONFIRMADO. Dados de algumas particoes podem estar ocultos.");
                log("Proximo passo: comparar FWID real com a pesquisa Nexmon e revisar rollback. NENHUM FLASH EXECUTADO.");
                set("Coleta concluida. Compartilhe o relatorio.");
            } catch (Exception e) {
                log("ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                set("Falha de diagnostico.");
            } finally { collecting = false; }
        });
    }

    private String shell(boolean root, String fixedCommand) {
        Process p = null;
        StringBuilder b = new StringBuilder();
        try {
            p = root
                ? new ProcessBuilder("su", "-c", fixedCommand).redirectErrorStream(true).start()
                : new ProcessBuilder("sh", "-c", fixedCommand).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; int n = 0;
                while ((line = r.readLine()) != null && n++ < 90) {
                    b.append(line).append('\n');
                }
            }
            b.append("[exit=").append(p.waitFor()).append(']');
        } catch (Exception e) {
            b.append("[sem acesso: ").append(e.getMessage()).append(']');
        } finally {
            if (p != null) p.destroy();
        }
        return b.toString();
    }

    private void share() {
        String data;
        synchronized (report) { data = report.toString(); }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "G533 Bridge BCM4375 Preflight");
        i.putExtra(Intent.EXTRA_TEXT, data);
        startActivity(Intent.createChooser(i, "Compartilhar diagnostico G533"));
    }

    private void log(String s) {
        ui.post(() -> {
            synchronized (report) { report.append(s).append('\n'); }
            output.append(s + "\n");
        });
    }

    private void set(String s) { ui.post(() -> status.setText(s)); }

    private TextView txt(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow(); super.onDestroy();
    }
}
