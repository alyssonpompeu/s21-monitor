package com.alysson.g991baudiolab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HASH_FULL_RAW = "df37c8d7ad52673b2dccb843ff150f5620990e893a150e0275dbc62aa704d70e";
    private static final String HASH_AUDIO32_GOOD = "ea9082bff92d3e08357bbb06d27ae29fd907ed8e1675a24dcb6e4bc66464f340";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView output;
    private volatile String lastReport = "";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        scanAll();
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(30));
        scroll.addView(root);

        TextView title = text("G991B AUDIO RAW DOCTOR", 25, true);
        root.addView(title);
        TextView sub = text("ROOT • CS35L41 • ABOX • PCM REAL • LOG DE FAULTS", 12, false);
        sub.setPadding(0, dp(3), 0, dp(10));
        root.addView(sub);

        status = text("Abrindo root...", 13, true);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status);

        root.addView(section("DIAGNÓSTICO"));
        LinearLayout r1 = row();
        Button scan = button("SCAN COMPLETO");
        Button pcm = button("PCM AO VIVO");
        r1.addView(scan, weight());
        r1.addView(pcm, weight());
        root.addView(r1);

        LinearLayout r2 = row();
        Button logs = button("LOG DE ERROS");
        Button amp = button("AMP / TINYMIX");
        r2.addView(logs, weight());
        r2.addView(amp, weight());
        root.addView(r2);

        root.addView(section("CONTROLES ROOT DE TESTE"));
        root.addView(text("Os botões executam os controles encontrados no aparelho e mostram o retorno real do shell. Nada fica bloqueado artificialmente no APK.", 12, false));

        LinearLayout r3 = row();
        Button asp = button("FORÇAR ASP RAW");
        Button vpbr = button("VPBR OFF");
        r3.addView(asp, weight());
        r3.addView(vpbr, weight());
        root.addView(r3);

        LinearLayout r4 = row();
        Button dre = button("DRE OFF");
        Button save = button("SALVAR RELATÓRIO");
        r4.addView(dre, weight());
        r4.addView(save, weight());
        root.addView(r4);

        root.addView(section("RESULTADO"));
        output = text("Aguardando scan...", 12, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp(8), dp(8), dp(8), dp(16));
        root.addView(output);

        scan.setOnClickListener(v -> scanAll());
        pcm.setOnClickListener(v -> runNamed("PCM REAL", pcmCommand()));
        logs.setOnClickListener(v -> runLogs());
        amp.setOnClickListener(v -> runNamed("AMP / TINYMIX", tinymixCommand()));
        asp.setOnClickListener(v -> runNamed("FORÇAR ASP RAW", forceAspCommand()));
        vpbr.setOnClickListener(v -> runNamed("VPBR OFF", vpbrOffCommand()));
        dre.setOnClickListener(v -> runNamed("DRE OFF", dreOffCommand()));
        save.setOnClickListener(v -> saveReport());

        setContentView(scroll);
    }

    private void scanAll() {
        status.setText("Executando scan root...");
        output.setText("Lendo vendor_boot, ALSA, tinymix e kernel log...");
        exec.submit(() -> {
            boolean root = RootShell.hasRoot();
            if (!root) {
                ui("Root: NEGADO/AUSENTE\nConceda root ao APK no Magisk e toque SCAN COMPLETO novamente.", "ROOT NÃO DISPONÍVEL");
                return;
            }

            String hash = run(vendorBootHashCommand());
            String mode;
            if (hash.contains(HASH_FULL_RAW)) mode = "FULL RAW v1.0 DETECTADO";
            else if (hash.contains(HASH_AUDIO32_GOOD)) mode = "AUDIO32 v0.1.1 (S32 somente)";
            else mode = "VENDOR_BOOT DIFERENTE / NÃO IDENTIFICADO";

            StringBuilder sb = new StringBuilder();
            sb.append("=== G991B AUDIO RAW DOCTOR ===\n");
            sb.append("Modelo Android: ").append(Build.MODEL).append("\n");
            sb.append("Build: ").append(Build.DISPLAY).append("\n");
            sb.append("Root: OK\n");
            sb.append("Modo detectado: ").append(mode).append("\n\n");
            sb.append(sectionOut("VENDOR_BOOT", hash));
            sb.append(sectionOut("KERNEL / MÓDULOS", run("uname -a; echo; cat /proc/modules 2>/dev/null | grep -Ei 'cs35l41|cirrus|abox|unbound' || true")));
            sb.append(sectionOut("ALSA", run("cat /proc/asound/cards 2>/dev/null; echo; cat /proc/asound/pcm 2>/dev/null")));
            sb.append(sectionOut("PCM ATIVO", run(pcmCommand())));
            sb.append(sectionOut("CONTROLES AMP", run(tinymixCommand())));

            String klog = run(kernelLogCommand());
            sb.append(sectionOut("KERNEL AUDIO LOG", klog));
            sb.append(sectionOut("CLASSIFICAÇÃO", classify(klog)));

            lastReport = sb.toString();
            ui(lastReport, mode);
        });
    }

    private void runLogs() {
        status.setText("Lendo faults CS35L41/ABOX...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            String log = run(kernelLogCommand());
            String result = "=== CLASSIFICAÇÃO ===\n" + classify(log) + "\n\n=== LOG ===\n" + log;
            lastReport = result;
            ui(result, "LOG CONCLUÍDO");
        });
    }

    private void runNamed(String name, String cmd) {
        status.setText(name + "...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            RootShell.Result r = RootShell.exec(cmd);
            String s = "=== " + name + " ===\nexit=" + r.code + "\n" + (r.out.isEmpty() ? "(sem saída)" : r.out);
            lastReport = s;
            ui(s, name + (r.ok() ? " OK" : " ERRO " + r.code));
        });
    }

    private void saveReport() {
        status.setText("Gerando e salvando relatório...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            if (lastReport == null || lastReport.length() < 20) lastReport = "Sem relatório. Execute SCAN COMPLETO.";
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String path = "/data/media/0/Download/G991B_AUDIO_RAW_REPORT_" + stamp + ".txt";
            RootShell.Result r = RootShell.writeText(path, lastReport);
            if (r.ok()) ui(lastReport + "\n\nSALVO EM:\n/sdcard/Download/G991B_AUDIO_RAW_REPORT_" + stamp + ".txt", "RELATÓRIO SALVO");
            else ui("Falha ao salvar:\n" + r.out, "ERRO AO SALVAR");
        });
    }

    private String vendorBootHashCommand() {
        return "P=''; for X in /dev/block/by-name/vendor_boot /dev/block/bootdevice/by-name/vendor_boot /dev/block/platform/*/by-name/vendor_boot; do [ -e \"$X\" ] && { P=\"$X\"; break; }; done; " +
                "if [ -n \"$P\" ]; then echo path=$P; sha256sum \"$P\"; else echo 'vendor_boot: NAO ENCONTRADO'; fi";
    }

    private String pcmCommand() {
        return "N=0; for F in /proc/asound/card*/pcm*/sub*/hw_params; do " +
                "[ -f \"$F\" ] || continue; V=$(cat \"$F\" 2>/dev/null); [ \"$V\" = closed ] && continue; " +
                "echo '---' $F; echo \"$V\"; N=$((N+1)); done; " +
                "[ $N -gt 0 ] || echo 'Nenhum PCM aberto. Inicie uma musica e toque PCM AO VIVO novamente.'";
    }

    private String tinymixBase() {
        return "T=$(command -v tinymix 2>/dev/null); [ -x \"$T\" ] || T=/vendor/bin/tinymix; [ -x \"$T\" ] || T=/system/bin/tinymix; ";
    }

    private String tinymixCommand() {
        return tinymixBase() +
                "if [ -x \"$T\" ]; then echo tinymix=$T; \"$T\" | grep -Ei 'PCM Source|VPBR|VBBR|Boost Enable|DSP Booted|DSP1 Preload|AMP Mute|DRE|CSPL|HALO|Speaker|Receiver' || true; " +
                "else echo 'tinymix: AUSENTE'; fi";
    }

    private String forceAspCommand() {
        return tinymixBase() +
                "[ -x \"$T\" ] || { echo 'tinymix ausente'; exit 127; }; " +
                "IDS=$(\"$T\" | grep -i 'PCM Source' | awk '{print $1}'); " +
                "[ -n \"$IDS\" ] || { echo 'PCM Source nao encontrado'; exit 2; }; " +
                "for I in $IDS; do echo control=$I; \"$T\" \"$I\" ASP || exit $?; done; " +
                "echo; \"$T\" | grep -i 'PCM Source' || true";
    }

    private String vpbrOffCommand() {
        return tinymixBase() +
                "[ -x \"$T\" ] || { echo 'tinymix ausente'; exit 127; }; " +
                "IDS=$(\"$T\" | grep -i 'VPBR Enable' | awk '{print $1}'); " +
                "[ -n \"$IDS\" ] || { echo 'VPBR Enable nao exposto'; exit 2; }; " +
                "for I in $IDS; do echo control=$I; \"$T\" \"$I\" Disabled || \"$T\" \"$I\" 0 || exit $?; done; " +
                "echo; \"$T\" | grep -i 'VPBR' || true";
    }

    private String dreOffCommand() {
        return tinymixBase() +
                "[ -x \"$T\" ] || { echo 'tinymix ausente'; exit 127; }; " +
                "IDS=$(\"$T\" | grep -Ei '[[:space:]]DRE([[:space:]]|$)' | awk '{print $1}'); " +
                "[ -n \"$IDS\" ] || { echo 'DRE nao exposto'; exit 2; }; " +
                "for I in $IDS; do echo control=$I; \"$T\" \"$I\" 0 || exit $?; done; " +
                "echo; \"$T\" | grep -Ei '[[:space:]]DRE([[:space:]]|$)' || true";
    }

    private String kernelLogCommand() {
        return "(dmesg 2>/dev/null || logcat -b kernel -d 2>/dev/null) | " +
                "grep -Ei 'cs35l41|cirrus|abox|audio|speaker|amp short|over.?temperature|temp warn|boost|CSPL|HALO|xrun|underrun|uvp|ovp|fault|timeout' | tail -n 400";
    }

    private String classify(String s) {
        if (s == null) s = "";
        String l = s.toLowerCase(Locale.US);
        StringBuilder x = new StringBuilder();
        boolean hit = false;
        if (l.contains("amp short")) { x.append("AMP_SHORT: DETECTADO\n"); hit = true; }
        if (l.contains("over temperature") || l.contains("overtemperature") || l.contains("temp warn")) { x.append("TEMP/DIE: DETECTADO\n"); hit = true; }
        if (l.contains("bst ovp") || l.contains("boost") && l.contains("ovp")) { x.append("BOOST_OVP: DETECTADO\n"); hit = true; }
        if (l.contains("bst dcm uvp") || l.contains("boost") && l.contains("uvp")) { x.append("BOOST_UVP: DETECTADO\n"); hit = true; }
        if (l.contains("bst short")) { x.append("BOOST_SHORT: DETECTADO\n"); hit = true; }
        if (l.contains("otp_boot_done") || l.contains("otp boot")) { x.append("CS35L41_OTP_BOOT: PROBLEMA\n"); hit = true; }
        if ((l.contains("cspl") || l.contains("halo")) && (l.contains("timeout") || l.contains("invalid") || l.contains("error"))) { x.append("CSPL/HALO: ERRO NO LOG\n"); hit = true; }
        if (l.contains("xrun") || l.contains("underrun")) { x.append("PCM_XRUN/UNDERRUN: DETECTADO\n"); hit = true; }
        if (!hit) x.append("Nenhuma assinatura principal de fault encontrada neste recorte do kernel log.\n");
        x.append("\nPCM real deve ser conferido com musica tocando; procure 'format: S32_LE' e a linha 'rate'.");
        return x.toString();
    }

    private String run(String cmd) {
        RootShell.Result r = RootShell.exec(cmd);
        if (!r.ok() && r.out.isEmpty()) return "exit=" + r.code;
        return (r.ok() ? "" : "exit=" + r.code + "\n") + r.out;
    }

    private String sectionOut(String title, String body) {
        return "\n=== " + title + " ===\n" + (body == null || body.isEmpty() ? "(sem saída)" : body) + "\n";
    }

    private void ui(String body, String st) {
        runOnUiThread(() -> {
            status.setText(st);
            output.setText(body);
        });
    }

    private TextView section(String s) {
        TextView t = text(s, 14, true);
        t.setPadding(0, dp(16), 0, dp(5));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
