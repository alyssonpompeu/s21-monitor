package com.alysson.g991baudiolab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HASH_FULL_RAW = "df37c8d7ad52673b2dccb843ff150f5620990e893a150e0275dbc62aa704d70e";
    private static final String MODULE_ID = "g991b_audio_kernel_eq";
    private static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    private static final String ACTIVE_PARAM = MODULE_DIR + "/system/vendor/etc/SoundBoosterParam.txt";
    private static final String LEGACY_MODULE = "/data/adb/modules/g991b_audio_lab";
    private static final String LEGACY_PARAM = LEGACY_MODULE + "/system/vendor/etc/SoundBoosterParam.txt";
    private static final String STOCK_ASSET = "SoundBoosterParam.stock.txt";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView output;
    private TextView topGainLabel;
    private TextView bottomGainLabel;
    private SeekBar topGain;
    private SeekBar bottomGain;
    private CheckBox keepAsp;
    private CheckBox vpbrOff;
    private CheckBox dreOff;
    private final EditText[][] top = new EditText[8][3];
    private final EditText[][] bottom = new EditText[8][3];
    private volatile String lastReport = "";

    // TOP = receiver/earpiece (_0 / left). BOTTOM = loudspeaker (_1 / right).
    private static final int[][] TOP_DEFAULT = {
            {250,220,-16},{500,350,-10},{900,500,-5},{1600,900,-1},
            {2800,1400,1},{4500,2200,2},{7500,3000,1},{12000,4500,0}
    };
    private static final int[][] BOTTOM_IPHONE = {
            {85,90,3},{120,100,6},{165,120,8},{230,160,7},
            {320,220,5},{480,300,3},{800,500,1},{1600,900,0}
    };
    private static final int[][] BOTTOM_HEAVY = {
            {70,80,5},{95,90,8},{130,100,10},{175,120,10},
            {240,170,8},{340,240,6},{600,400,3},{1200,800,1}
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        setBands(top, TOP_DEFAULT);
        setBands(bottom, BOTTOM_IPHONE);
        refreshStatus();
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(36));
        scroll.addView(root);

        root.addView(text("G991B AUDIO KERNEL EQ", 25, true));
        TextView sub = text("ROOT • CS35L41 • ABOX • EQ TOP/BOTTOM • AMP GAIN • FULL RAW", 12, false);
        sub.setPadding(0, dp(3), 0, dp(8));
        root.addView(sub);

        status = text("Verificando root e driver...", 13, true);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(status);

        root.addView(section("PRESETS"));
        LinearLayout presets1 = row();
        Button iphone = button("iPHONE BASS");
        Button heavy = button("SUB + MID HEAVY");
        presets1.addView(iphone, weight());
        presets1.addView(heavy, weight());
        root.addView(presets1);
        LinearLayout presets2 = row();
        Button flat = button("FLAT");
        Button stock = button("SAMSUNG STOCK");
        presets2.addView(flat, weight());
        presets2.addView(stock, weight());
        root.addView(presets2);
        iphone.setOnClickListener(v -> presetIphone());
        heavy.setOnClickListener(v -> presetHeavy());
        flat.setOnClickListener(v -> presetFlat());
        stock.setOnClickListener(v -> applyStock(false));

        root.addView(section("TOP / EARPIECE — RECEIVER"));
        root.addView(text("8 bandas independentes. Colunas: frequência Hz • largura • ganho dB. Os valores manuais não são limitados artificialmente pelo APK.", 12, false));
        addBandEditor(root, top, "TOP");

        root.addView(section("BOTTOM — SPEAKER PRINCIPAL"));
        root.addView(text("Preset iPhone Bass concentra corpo em ~120–350 Hz e mantém médios no speaker inferior em vez de transformar tudo em grave seco.", 12, false));
        addBandEditor(root, bottom, "BOT");

        root.addView(section("GANHO DIRETO DO CS35L41"));
        root.addView(text("AMP PCM Gain é um controle real do driver. O próprio CS35L41 expõe 0…20; não é um limite inventado pelo APK.", 12, false));

        topGainLabel = text("TOP AMP GAIN: 10", 13, true);
        root.addView(topGainLabel);
        topGain = new SeekBar(this);
        topGain.setMin(0); topGain.setMax(20); topGain.setProgress(10);
        root.addView(topGain);

        bottomGainLabel = text("BOTTOM AMP GAIN: 10", 13, true);
        root.addView(bottomGainLabel);
        bottomGain = new SeekBar(this);
        bottomGain.setMin(0); bottomGain.setMax(20); bottomGain.setProgress(10);
        root.addView(bottomGain);

        topGain.setOnSeekBarChangeListener(gainListener(topGainLabel, "TOP AMP GAIN: "));
        bottomGain.setOnSeekBarChangeListener(gainListener(bottomGainLabel, "BOTTOM AMP GAIN: "));

        Button applyGain = button("APLICAR GANHOS AGORA");
        root.addView(applyGain);
        applyGain.setOnClickListener(v -> applyGains());

        root.addView(section("DRIVER / RAW"));
        keepAsp = new CheckBox(this);
        keepAsp.setText("Forçar PCM Source = ASP RAW");
        keepAsp.setChecked(true);
        root.addView(keepAsp);

        vpbrOff = new CheckBox(this);
        vpbrOff.setText("VPBR OFF");
        vpbrOff.setChecked(true);
        root.addView(vpbrOff);

        dreOff = new CheckBox(this);
        dreOff.setText("DRE OFF");
        dreOff.setChecked(true);
        root.addView(dreOff);

        Button applyDriver = button("APLICAR DRIVER AGORA");
        root.addView(applyDriver);
        applyDriver.setOnClickListener(v -> applyDriverNow());

        root.addView(section("APLICAR EQ"));
        root.addView(text("O EQ por canal é gravado como overlay Magisk de SoundBoosterParam. Ganho/ASP/VPBR/DRE são enviados diretamente aos kcontrols do CS35L41 via tinymix.", 12, false));
        LinearLayout applyRow = row();
        Button saveEq = button("GRAVAR EQ");
        Button saveReboot = button("EQ + REINICIAR");
        applyRow.addView(saveEq, weight());
        applyRow.addView(saveReboot, weight());
        root.addView(applyRow);
        saveEq.setOnClickListener(v -> applyEq(false));
        saveReboot.setOnClickListener(v -> applyEq(true));

        root.addView(section("DIAGNÓSTICO"));
        LinearLayout diag = row();
        Button scan = button("LER DRIVER");
        Button pcm = button("PCM AO VIVO");
        diag.addView(scan, weight());
        diag.addView(pcm, weight());
        root.addView(diag);
        Button saveReport = button("SALVAR RELATÓRIO");
        root.addView(saveReport);
        scan.setOnClickListener(v -> scanDriver());
        pcm.setOnClickListener(v -> runNamed("PCM AO VIVO", pcmCommand()));
        saveReport.setOnClickListener(v -> saveReport());

        root.addView(section("RESULTADO"));
        output = text("Aguardando...", 11, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp(6), dp(6), dp(6), dp(18));
        root.addView(output);

        setContentView(scroll);
    }

    private SeekBar.OnSeekBarChangeListener gainListener(TextView target, String prefix) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { target.setText(prefix + p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private void addBandEditor(LinearLayout parent, EditText[][] dst, String prefix) {
        for (int i = 0; i < 8; i++) {
            LinearLayout r = row();
            TextView n = text(prefix + " " + (i + 1), 11, true);
            n.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(n, new LinearLayout.LayoutParams(dp(48), dp(48)));
            for (int j = 0; j < 3; j++) {
                EditText e = new EditText(this);
                e.setSingleLine(true);
                e.setTextSize(12);
                e.setGravity(Gravity.CENTER);
                e.setInputType(InputType.TYPE_CLASS_NUMBER | (j == 2 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
                e.setHint(j == 0 ? "Hz" : j == 1 ? "Width" : "dB");
                dst[i][j] = e;
                r.addView(e, weight());
            }
            parent.addView(r);
        }
    }

    private void presetIphone() {
        setBands(top, TOP_DEFAULT);
        setBands(bottom, BOTTOM_IPHONE);
        toast("Preset iPhone Bass carregado. Toque EQ + REINICIAR para aplicar o perfil tonal.");
    }

    private void presetHeavy() {
        setBands(top, TOP_DEFAULT);
        setBands(bottom, BOTTOM_HEAVY);
        toast("Preset SUB + MID HEAVY carregado.");
    }

    private void presetFlat() {
        int[][] t = {
                {250,220,0},{500,350,0},{900,500,0},{1600,900,0},
                {2800,1400,0},{4500,2200,0},{7500,3000,0},{12000,4500,0}
        };
        int[][] b = {
                {85,90,0},{120,100,0},{165,120,0},{230,160,0},
                {320,220,0},{480,300,0},{800,500,0},{1600,900,0}
        };
        setBands(top, t);
        setBands(bottom, b);
        toast("FLAT carregado.");
    }

    private void setBands(EditText[][] dst, int[][] values) {
        for (int i = 0; i < 8; i++) for (int j = 0; j < 3; j++) dst[i][j].setText(String.valueOf(values[i][j]));
    }

    private int[][] readBands(EditText[][] src) {
        int[][] out = new int[8][3];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 3; j++) {
                String s = src[i][j].getText().toString().trim();
                if (s.isEmpty()) throw new IllegalArgumentException("Campo vazio na banda " + (i + 1));
                out[i][j] = Integer.parseInt(s);
            }
            if (out[i][0] <= 0 || out[i][1] <= 0) throw new IllegalArgumentException("Frequência e largura devem ser maiores que zero na banda " + (i + 1));
        }
        return out;
    }

    private void refreshStatus() {
        exec.submit(() -> {
            boolean root = RootShell.hasRoot();
            String hash = root ? run(vendorBootHashCommand()) : "root ausente";
            String mode = hash.contains(HASH_FULL_RAW) ? "FULL RAW v1.0" : "vendor_boot diferente";
            String s = "Modelo: " + Build.MODEL + "\nBuild: " + Build.DISPLAY + "\nRoot: " + (root ? "OK" : "NÃO") + "\nKernel áudio: " + mode;
            runOnUiThread(() -> status.setText(s));
        });
    }

    private void applyEq(boolean rebootAfter) {
        final int[][] t;
        final int[][] b;
        try {
            t = readBands(top);
            b = readBands(bottom);
        } catch (Throwable x) {
            toast("EQ inválido: " + x.getMessage());
            return;
        }
        final boolean asp = keepAsp.isChecked();
        final boolean vp = vpbrOff.isChecked();
        final boolean dre = dreOff.isChecked();
        final int tg = topGain.getProgress();
        final int bg = bottomGain.getProgress();

        status.setText("Gravando EQ/Magisk...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            try {
                String stock = readAssetText(STOCK_ASSET);
                String custom = patchBanks(stock, t, b);
                String moduleProp = "id=" + MODULE_ID + "\nname=G991B Audio Kernel EQ\nversion=3.0\nversionCode=30\nauthor=Alysson + ChatGPT\ndescription=Per-channel SoundBooster EQ + CS35L41 live controls for SM-G991B HZA6\n";
                String service = buildServiceScript(asp, vp, dre, tg, bg);

                RootShell.Result a = RootShell.writeText(MODULE_DIR + "/module.prop", moduleProp);
                RootShell.Result p = RootShell.writeText(ACTIVE_PARAM, custom);
                RootShell.Result s = RootShell.writeText(MODULE_DIR + "/service.sh", service);
                RootShell.exec("chmod 0755 '" + MODULE_DIR + "/service.sh'; rm -f '" + MODULE_DIR + "/disable' '" + MODULE_DIR + "/remove'");

                if (RootShell.exists(LEGACY_MODULE + "/module.prop")) RootShell.writeText(LEGACY_PARAM, custom);

                String live = run(driverApplyCommand(asp, vp, dre, tg, bg));
                String result = "EQ gravado: " + (p.ok() ? "OK" : "ERRO") + "\nmodule.prop=" + a.code + " service=" + s.code + "\n\nDRIVER AO VIVO:\n" + live + "\n\nO SoundBoosterParam entra de forma garantida no próximo boot.";
                lastReport = result;
                ui(result, rebootAfter ? "EQ GRAVADO — REINICIANDO" : "EQ GRAVADO");
                if (rebootAfter) {
                    Thread.sleep(700);
                    RootShell.exec("reboot");
                }
            } catch (Throwable x) {
                ui(x.toString(), "ERRO EQ");
            }
        });
    }

    private void applyStock(boolean rebootAfter) {
        final boolean asp = keepAsp.isChecked();
        final boolean vp = vpbrOff.isChecked();
        final boolean dre = dreOff.isChecked();
        final int tg = topGain.getProgress();
        final int bg = bottomGain.getProgress();
        status.setText("Gravando SoundBooster stock...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            try {
                String stock = readAssetText(STOCK_ASSET);
                RootShell.writeText(ACTIVE_PARAM, stock);
                if (RootShell.exists(LEGACY_MODULE + "/module.prop")) RootShell.writeText(LEGACY_PARAM, stock);
                RootShell.writeText(MODULE_DIR + "/module.prop", "id=" + MODULE_ID + "\nname=G991B Audio Kernel EQ\nversion=3.0\nversionCode=30\nauthor=Alysson + ChatGPT\ndescription=Stock SoundBooster profile with CS35L41 controls\n");
                RootShell.writeText(MODULE_DIR + "/service.sh", buildServiceScript(asp, vp, dre, tg, bg));
                RootShell.exec("chmod 0755 '" + MODULE_DIR + "/service.sh'; rm -f '" + MODULE_DIR + "/disable' '" + MODULE_DIR + "/remove'");
                ui("SoundBoosterParam HZA6 stock gravado. Reinicie para o perfil tonal stock entrar.", "STOCK GRAVADO");
                if (rebootAfter) RootShell.exec("reboot");
            } catch (Throwable x) { ui(x.toString(), "ERRO STOCK"); }
        });
    }

    private void applyGains() {
        int tg = topGain.getProgress();
        int bg = bottomGain.getProgress();
        runNamed("AMP GAIN TOP/BOTTOM", ampGainCommand(tg, bg));
    }

    private void applyDriverNow() {
        runNamed("DRIVER RAW", driverApplyCommand(keepAsp.isChecked(), vpbrOff.isChecked(), dreOff.isChecked(), topGain.getProgress(), bottomGain.getProgress()));
    }

    private void scanDriver() {
        status.setText("Lendo CS35L41/ABOX...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("=== VENDOR_BOOT ===\n").append(run(vendorBootHashCommand())).append("\n\n");
            sb.append("=== CS35L41 / TINYMIX ===\n").append(run(tinymixScanCommand())).append("\n\n");
            sb.append("=== PCM ===\n").append(run(pcmCommand())).append("\n\n");
            sb.append("=== MÓDULOS ===\n").append(run("cat /proc/modules | grep -Ei 'cs35l41|cirrus|abox|unbound' || true"));
            lastReport = sb.toString();
            ui(lastReport, "DRIVER LIDO");
        });
    }

    private String patchBanks(String stock, int[][] t, int[][] b) {
        Map<String,String> repl = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            char c = (char)('A' + i);
            repl.put("AA" + c, line("AA" + c, t[i]));
            repl.put("BA" + c, line("BA" + c, t[i]));
            repl.put("AC" + c, line("AC" + c, b[i]));
            repl.put("BC" + c, line("BC" + c, b[i]));
        }
        StringBuilder out = new StringBuilder(stock.length() + 256);
        for (String ln : stock.split("\\r?\\n", -1)) {
            if (ln.length() >= 3 && repl.containsKey(ln.substring(0,3))) out.append(repl.get(ln.substring(0,3)));
            else out.append(ln);
            out.append('\n');
        }
        return out.toString();
    }

    private String line(String key, int[] v) { return key + "," + v[0] + "," + v[1] + "," + v[2]; }

    private String buildServiceScript(boolean asp, boolean vp, boolean dre, int tg, int bg) {
        return "#!/system/bin/sh\n" +
                "sleep 12\n" +
                "T=$(command -v tinymix 2>/dev/null); [ -x \"$T\" ] || T=/vendor/bin/tinymix\n" +
                "[ -x \"$T\" ] || exit 0\n" +
                (asp ? "for I in $(\"$T\" | grep -i 'PCM Source' | awk '{print $1}'); do \"$T\" \"$I\" ASP >/dev/null 2>&1; done\n" : "") +
                (vp ? "for I in $(\"$T\" | grep -i 'VPBR Enable' | awk '{print $1}'); do \"$T\" \"$I\" Disabled >/dev/null 2>&1 || \"$T\" \"$I\" 0 >/dev/null 2>&1; done\n" : "") +
                (dre ? "for I in $(\"$T\" | grep -Ei '[[:space:]]DRE([[:space:]]|$)' | awk '{print $1}'); do \"$T\" \"$I\" 0 >/dev/null 2>&1; done\n" : "") +
                ampGainScript(tg, bg) + "\n";
    }

    private String tinymixBase() {
        return "T=$(command -v tinymix 2>/dev/null); [ -x \"$T\" ] || T=/vendor/bin/tinymix; [ -x \"$T\" ] || T=/system/bin/tinymix; ";
    }

    private String ampGainScript(int topVal, int botVal) {
        return "IDS=$(\"$T\" | grep -i 'AMP PCM Gain' | awk '{print $1}'); " +
                "TOP=$(echo \"$IDS\" | sed -n '1p'); BOT=$(echo \"$IDS\" | sed -n '2p'); " +
                "[ -n \"$TOP\" ] && \"$T\" \"$TOP\" " + topVal + " >/dev/null 2>&1; " +
                "[ -n \"$BOT\" ] && \"$T\" \"$BOT\" " + botVal + " >/dev/null 2>&1; ";
    }

    private String ampGainCommand(int topVal, int botVal) {
        return tinymixBase() +
                "[ -x \"$T\" ] || { echo 'tinymix ausente'; exit 127; }; " +
                "echo 'Controles antes:'; \"$T\" | grep -i 'AMP PCM Gain' || true; " +
                "IDS=$(\"$T\" | grep -i 'AMP PCM Gain' | awk '{print $1}'); TOP=$(echo \"$IDS\" | sed -n '1p'); BOT=$(echo \"$IDS\" | sed -n '2p'); " +
                "echo TOP_ID=$TOP BOTTOM_ID=$BOT; " +
                "[ -n \"$TOP\" ] || { echo 'TOP AMP PCM Gain não encontrado'; exit 2; }; " +
                "[ -n \"$BOT\" ] || { echo 'BOTTOM AMP PCM Gain não encontrado'; exit 3; }; " +
                "\"$T\" \"$TOP\" " + topVal + "; \"$T\" \"$BOT\" " + botVal + "; echo; echo 'Depois:'; \"$T\" | grep -i 'AMP PCM Gain' || true";
    }

    private String driverApplyCommand(boolean asp, boolean vp, boolean dre, int tg, int bg) {
        StringBuilder c = new StringBuilder(tinymixBase());
        c.append("[ -x \"$T\" ] || { echo 'tinymix ausente'; exit 127; }; ");
        if (asp) c.append("for I in $(\"$T\" | grep -i 'PCM Source' | awk '{print $1}'); do echo PCM_SOURCE_ID=$I; \"$T\" \"$I\" ASP; done; ");
        if (vp) c.append("for I in $(\"$T\" | grep -i 'VPBR Enable' | awk '{print $1}'); do echo VPBR_ID=$I; \"$T\" \"$I\" Disabled || \"$T\" \"$I\" 0; done; ");
        if (dre) c.append("for I in $(\"$T\" | grep -Ei '[[:space:]]DRE([[:space:]]|$)' | awk '{print $1}'); do echo DRE_ID=$I; \"$T\" \"$I\" 0; done; ");
        c.append(ampGainCommandBody(tg, bg));
        c.append("echo; \"$T\" | grep -Ei 'PCM Source|VPBR Enable|[[:space:]]DRE([[:space:]]|$)|AMP PCM Gain' || true");
        return c.toString();
    }

    private String ampGainCommandBody(int topVal, int botVal) {
        return "IDS=$(\"$T\" | grep -i 'AMP PCM Gain' | awk '{print $1}'); TOP=$(echo \"$IDS\" | sed -n '1p'); BOT=$(echo \"$IDS\" | sed -n '2p'); " +
                "[ -n \"$TOP\" ] && { echo TOP_GAIN_ID=$TOP; \"$T\" \"$TOP\" " + topVal + "; }; " +
                "[ -n \"$BOT\" ] && { echo BOTTOM_GAIN_ID=$BOT; \"$T\" \"$BOT\" " + botVal + "; }; ";
    }

    private String tinymixScanCommand() {
        return tinymixBase() + "if [ -x \"$T\" ]; then echo tinymix=$T; \"$T\" | grep -Ei 'PCM Source|Digital PCM Volume|AMP PCM Gain|VPBR|VBBR|DRE|Boost Enable|AMP Mute|DSP Booted|CSPL|HALO|Speaker|Receiver' || true; else echo 'tinymix ausente'; fi";
    }

    private String pcmCommand() {
        return "N=0; for F in /proc/asound/card*/pcm*/sub*/hw_params; do [ -f \"$F\" ] || continue; V=$(cat \"$F\" 2>/dev/null); [ \"$V\" = closed ] && continue; echo '---' $F; echo \"$V\"; N=$((N+1)); done; [ $N -gt 0 ] || echo 'Nenhum PCM aberto. Toque música e rode novamente.'";
    }

    private String vendorBootHashCommand() {
        return "P=''; for X in /dev/block/by-name/vendor_boot /dev/block/bootdevice/by-name/vendor_boot /dev/block/platform/*/by-name/vendor_boot; do [ -e \"$X\" ] && { P=\"$X\"; break; }; done; if [ -n \"$P\" ]; then echo path=$P; sha256sum \"$P\"; else echo 'vendor_boot não encontrado'; fi";
    }

    private void runNamed(String name, String cmd) {
        status.setText(name + "...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            RootShell.Result r = RootShell.exec(cmd);
            String s = "=== " + name + " ===\nexit=" + r.code + "\n" + (r.out.isEmpty() ? "(sem saída)" : r.out);
            lastReport = s;
            ui(s, name + (r.ok() ? " OK" : " ERRO"));
        });
    }

    private void saveReport() {
        status.setText("Salvando relatório...");
        exec.submit(() -> {
            if (!RootShell.hasRoot()) { ui("Root não disponível.", "ROOT NÃO"); return; }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String path = "/data/media/0/Download/G991B_AUDIO_KERNEL_EQ_" + stamp + ".txt";
            if (lastReport == null || lastReport.length() < 10) lastReport = "Sem relatório. Use LER DRIVER primeiro.";
            RootShell.Result r = RootShell.writeText(path, lastReport);
            ui(r.ok() ? "Salvo em /sdcard/Download/G991B_AUDIO_KERNEL_EQ_" + stamp + ".txt" : r.out, r.ok() ? "RELATÓRIO SALVO" : "ERRO");
        });
    }

    private String readAssetText(String asset) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    private String run(String cmd) {
        RootShell.Result r = RootShell.exec(cmd);
        return (r.ok() ? "" : "exit=" + r.code + "\n") + r.out;
    }

    private void ui(String body, String st) {
        runOnUiThread(() -> { status.setText(st); output.setText(body); });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

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
        b.setMinHeight(dp(50));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private int dp(int x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }
}
