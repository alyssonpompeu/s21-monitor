package com.alysson.rx42diag;

import android.app.Activity;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView outputView;
    private Button runButton;
    private Button copyButton;
    private Button shareButton;
    private volatile boolean running;
    private String lastReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        main.postDelayed(this::runDiagnostic, 500);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text("S21 RF Diagnostic", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());

        TextView subtitle = text("ROOT • Wi‑Fi/Bluetooth • kernel • firmware • RX42/A7105", 12, 0xFF80CBC4, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, match());

        TextView device = text(
                "Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
                        "\nDevice: " + Build.DEVICE + " • Hardware: " + Build.HARDWARE +
                        "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                12, 0xFFCFD8DC, false);
        device.setPadding(dp(12), dp(10), dp(12), dp(10));
        device.setBackgroundColor(0xFF172027);
        root.addView(device, matchMargin(0, 0, 0, 10));

        statusView = text("Preparando diagnóstico…", 14, 0xFFFFD180, true);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusView.setBackgroundColor(0xFF332A16);
        root.addView(statusView, matchMargin(0, 0, 0, 10));

        runButton = button("EXECUTAR DIAGNÓSTICO ROOT");
        runButton.setOnClickListener(v -> runDiagnostic());
        root.addView(runButton, matchMargin(0, 0, 0, 7));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = button("COPIAR");
        shareButton = button("COMPARTILHAR");
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        shareButton.setOnClickListener(v -> shareReport());
        actions.addView(copyButton, weight());
        LinearLayout.LayoutParams shareParams = weight();
        shareParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(shareButton, shareParams);
        root.addView(actions, matchMargin(0, 0, 0, 12));

        TextView hint = text(
                "Na primeira execução, aceite a solicitação do Magisk/KernelSU. Depois tire um print do RESUMO abaixo ou use COMPARTILHAR para enviar o relatório completo.",
                12, 0xFFB0BEC5, false);
        hint.setPadding(dp(10), dp(10), dp(10), dp(10));
        hint.setBackgroundColor(0xFF11181D);
        root.addView(hint, matchMargin(0, 0, 0, 10));

        outputView = text("Aguardando execução…", 11, 0xFFE0E0E0, false);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(dp(10), dp(10), dp(10), dp(18));
        outputView.setBackgroundColor(0xFF0D1216);
        root.addView(outputView, match());

        return scroll;
    }

    private void runDiagnostic() {
        if (running) return;
        running = true;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);
        statusView.setTextColor(0xFFFFD180);
        statusView.setText("ROOT: solicitando acesso…\nAceite a janela do seu gerenciador root.");
        outputView.setText("Executando: su -c id …");

        executor.execute(() -> {
            ShellResult root = runSu("id; echo ROOT_MANAGER_START; magisk -v 2>/dev/null; magisk -V 2>/dev/null; ksud -V 2>/dev/null; echo ROOT_MANAGER_END");
            if (root.code != 0 || !root.output.contains("uid=0")) {
                String report = "=== ROOT FALHOU ===\nExit code: " + root.code + "\n\n" + root.output;
                main.post(() -> {
                    lastReport = report;
                    statusView.setTextColor(0xFFEF9A9A);
                    statusView.setText("ROOT: NEGADO / INDISPONÍVEL\nConceda root ao S21 RF Diagnostic e toque em EXECUTAR novamente.");
                    outputView.setText(report);
                    copyButton.setEnabled(true);
                    shareButton.setEnabled(true);
                    runButton.setEnabled(true);
                    running = false;
                });
                return;
            }

            main.post(() -> {
                statusView.setTextColor(0xFF81C784);
                statusView.setText("ROOT: OK • uid=0\nColetando driver, firmware e logs do rádio…");
                outputView.setText("Root concedido. Coletando dados…");
            });

            ShellResult scan = runSu(buildDiagnosticScript());
            String report = "S21 RF Diagnostic v1.0\n" +
                    "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                    "Device: " + Build.DEVICE + "\n" +
                    "Hardware: " + Build.HARDWARE + "\n" +
                    "Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n\n" +
                    "=== ROOT CHECK ===\n" + root.output + "\n" +
                    scan.output + "\n\nExit code: " + scan.code;
            String summary = buildSummary(report);

            main.post(() -> {
                lastReport = report;
                statusView.setTextColor(0xFF81C784);
                statusView.setText(summary);
                outputView.setText("=== RESUMO PARA PRINT ===\n" + summary + "\n\n=== RELATÓRIO COMPLETO ===\n" + report);
                copyButton.setEnabled(true);
                shareButton.setEnabled(true);
                runButton.setEnabled(true);
                running = false;
                Toast.makeText(this, "Diagnóstico concluído.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String buildDiagnosticScript() {
        return "" +
                "echo '=== IDENTIDADE / SISTEMA ===';\n" +
                "date 2>&1;\n" +
                "id 2>&1;\n" +
                "uname -a 2>&1;\n" +
                "cat /proc/version 2>&1;\n" +
                "getenforce 2>&1;\n" +
                "echo 'manufacturer='$(getprop ro.product.manufacturer);\n" +
                "echo 'model='$(getprop ro.product.model);\n" +
                "echo 'device='$(getprop ro.product.device);\n" +
                "echo 'hardware='$(getprop ro.hardware);\n" +
                "echo 'soc.manufacturer='$(getprop ro.soc.manufacturer);\n" +
                "echo 'soc.model='$(getprop ro.soc.model);\n" +
                "echo; echo '=== ROOT ===';\n" +
                "command -v su 2>&1;\n" +
                "command -v magisk 2>&1;\n" +
                "magisk -v 2>/dev/null;\n" +
                "magisk -V 2>/dev/null;\n" +
                "ksud -V 2>/dev/null;\n" +
                "echo; echo '=== PROPRIEDADES WIFI / BLUETOOTH ===';\n" +
                "getprop 2>&1 | grep -iE 'wifi|wlan|bluetooth|bcm|brcm|scsc|slsi|mxman|shannon' | head -n 300;\n" +
                "echo; echo '=== INTERFACES DE REDE ===';\n" +
                "ip link 2>&1;\n" +
                "iw dev 2>&1;\n" +
                "echo; echo '=== WLAN0 SYSFS ===';\n" +
                "ls -la /sys/class/net/wlan0 2>&1;\n" +
                "ls -la /sys/class/net/wlan0/device 2>&1;\n" +
                "readlink -f /sys/class/net/wlan0/device 2>&1;\n" +
                "readlink -f /sys/class/net/wlan0/device/driver 2>&1;\n" +
                "cat /sys/class/net/wlan0/device/uevent 2>&1;\n" +
                "echo; echo '=== BLUETOOTH SYSFS ===';\n" +
                "ls -la /sys/class/bluetooth 2>&1;\n" +
                "ls -la /sys/class/bluetooth/hci0/device 2>&1;\n" +
                "readlink -f /sys/class/bluetooth/hci0/device/driver 2>&1;\n" +
                "cat /sys/class/bluetooth/hci0/device/uevent 2>&1;\n" +
                "echo; echo '=== MODULOS DO KERNEL ===';\n" +
                "cat /proc/modules 2>&1 | grep -iE 'wlan|wifi|bcm|brcm|scsc|slsi|mxman|mif|bluetooth|(^|_)bt' | head -n 250;\n" +
                "echo; echo '=== MODULOS VENDOR ===';\n" +
                "find /vendor/lib/modules -maxdepth 2 -type f 2>/dev/null | grep -iE 'wifi|wlan|bcm|brcm|scsc|slsi|mxman|bluetooth|bt' | head -n 250;\n" +
                "echo; echo '=== DRIVERS PLATFORM ===';\n" +
                "ls /sys/bus/platform/drivers 2>/dev/null | grep -iE 'wifi|wlan|bcm|brcm|scsc|slsi|mxman|bluetooth|bt' | head -n 200;\n" +
                "echo; echo '=== FIRMWARE / VENDOR CONFIG ===';\n" +
                "find /vendor/firmware /vendor/etc -maxdepth 5 -type f 2>/dev/null | grep -iE 'wifi|wlan|bcm|brcm|scsc|slsi|mxman|bluetooth|bt' | head -n 350;\n" +
                "echo; echo '=== DMESG RADIO (ULTIMAS LINHAS) ===';\n" +
                "dmesg 2>&1 | grep -iE 'wifi|wlan|bluetooth|bcm|brcm|scsc|slsi|mxman|shannon' | tail -n 350;\n" +
                "echo; echo '=== FIM ===';\n";
    }

    private String buildSummary(String report) {
        String lower = report.toLowerCase(Locale.ROOT);
        String radio;
        if (lower.contains("bcm4389")) {
            radio = "Broadcom BCM4389 detectado";
        } else if (lower.contains("bcm4375")) {
            radio = "Broadcom BCM4375 detectado";
        } else if (lower.contains("bcm") || lower.contains("brcm")) {
            radio = "Família Broadcom/Cypress detectada";
        } else if (lower.contains("scsc") || lower.contains("slsi") || lower.contains("mxman")) {
            radio = "Stack Samsung SCSC/SLSI detectada";
        } else {
            radio = "Chip/driver de rádio ainda não identificado";
        }

        String rootManager;
        if (lower.contains("magisk")) {
            rootManager = "Magisk provável";
        } else if (lower.contains("kernelsu") || lower.contains("ksud")) {
            rootManager = "KernelSU provável";
        } else {
            rootManager = "gerenciador root não identificado";
        }

        return "ROOT: OK • uid=0\n" +
                "Rádio/driver: " + radio + "\n" +
                "Root manager: " + rootManager + "\n" +
                "Modelo: " + Build.MODEL + " • " + Build.HARDWARE + "\n" +
                "Envie um print deste resumo + o começo do relatório abaixo.";
    }

    private ShellResult runSu(String script) {
        Process process = null;
        StringBuilder out = new StringBuilder();
        int code = -1;
        try {
            process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > 600_000) {
                        out.append("\n[SAÍDA LIMITADA PELO APP]\n");
                        process.destroy();
                        break;
                    }
                }
            }
            code = process.waitFor();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            if (process != null) process.destroy();
        }
        return new ShellResult(code, out.toString());
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("S21 RF Diagnostic", lastReport));
        Toast.makeText(this, "Relatório copiado.", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        if (lastReport.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "S21 RF Diagnostic - " + Build.MODEL);
        send.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(send, "Compartilhar diagnóstico"));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = match();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ShellResult {
        final int code;
        final String output;

        ShellResult(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }
}
