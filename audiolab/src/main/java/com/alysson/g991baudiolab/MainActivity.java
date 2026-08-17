package com.alysson.g991baudiolab;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String MODULE_ID = "g991b_audio_lab";
    private static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    private static final String ACTIVE_PARAM = MODULE_DIR + "/system/vendor/etc/SoundBoosterParam.txt";
    private static final String AUDIO32_MARKER = "/data/adb/modules/g991b_audio32/module.prop";
    private static final String STOCK_ASSET = "SoundBoosterParam.stock.txt";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView status;
    private RadioButton bit24, bit32;
    private RadioGroup rateGroup;
    private final EditText[][] top = new EditText[8][3];
    private final EditText[][] bottom = new EditText[8][3];

    private static final int[][] TOP_DEFAULT = {
            {250,220,-18},{450,320,-14},{750,500,-8},{1200,700,-3},
            {2200,1200,0},{4000,2200,1},{7000,3500,1},{12500,5000,0}
    };
    private static final int[][] BOTTOM_DEFAULT = {
            {120,100,4},{165,120,5},{230,180,4},{340,260,2},
            {700,500,1},{1500,1000,0},{3500,2200,-4},{8500,5000,-10}
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("audio_lab", MODE_PRIVATE);
        buildUi();
        loadPrefsOrDefaults();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(30));
        scroll.addView(root);

        TextView title = text("G991B AUDIO LAB", 26, true);
        root.addView(title);
        TextView sub = text("SM-G991B / HZA6 • TOP + BOTTOM independentes\nControle tonal próprio sobre o SoundBooster; proteção Cirrus permanece stock.", 14, false);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        status = text("Verificando root/backend...", 13, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(status);

        root.addView(section("MODO PRINCIPAL"));
        LinearLayout mainButtons = row();
        Button stock = button("PADRÃO");
        Button lab = button("APK / LAB");
        mainButtons.addView(stock, weight());
        mainButtons.addView(lab, weight());
        root.addView(mainButtons);
        stock.setOnClickListener(v -> confirmAndApplyStock());
        lab.setOnClickListener(v -> applyLab(false));

        root.addView(text("Backend necessário: instale o ZIP G991B HZA6 AudioLab Backend no app Magisk e reinicie uma vez.", 12, true));

        root.addView(section("FORMATO DE SAÍDA"));
        TextView fmtNote = text("24-bit é o formato de playback exposto pelo driver stock. 32-bit fica bloqueado até detectar o patch de kernel Audio32. A taxa abaixo é o alvo do projeto; esta v1 não finge forçar ABOX quando o kernel não expõe o controle.", 12, false);
        root.addView(fmtNote);

        RadioGroup bits = new RadioGroup(this);
        bits.setOrientation(RadioGroup.HORIZONTAL);
        bit24 = new RadioButton(this); bit24.setText("24-bit stock"); bit24.setId(View.generateViewId());
        bit32 = new RadioButton(this); bit32.setText("32-bit EXP"); bit32.setId(View.generateViewId());
        bits.addView(bit24, weight()); bits.addView(bit32, weight());
        root.addView(bits);
        bit24.setChecked(true);
        bits.setOnCheckedChangeListener((g,id) -> saveFormatPrefs());

        rateGroup = new RadioGroup(this);
        rateGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int rate : new int[]{48000, 96000, 192000}) {
            RadioButton rb = new RadioButton(this);
            rb.setText((rate/1000) + " kHz");
            rb.setTag(rate);
            rb.setId(View.generateViewId());
            rateGroup.addView(rb, weight());
            if (rate == 48000) rb.setChecked(true);
        }
        root.addView(rateGroup);
        rateGroup.setOnCheckedChangeListener((g,id) -> saveFormatPrefs());

        root.addView(section("PRESETS"));
        LinearLayout p1 = row();
        Button flat = button("REFERENCE FLAT");
        Button body = button("iPHONE BODY");
        p1.addView(flat, weight()); p1.addView(body, weight()); root.addView(p1);
        LinearLayout p2 = row();
        Button subwoofer = button("SUBWOOFER HEAVY");
        Button defaults = button("LAB DEFAULT");
        p2.addView(subwoofer, weight()); p2.addView(defaults, weight()); root.addView(p2);
        flat.setOnClickListener(v -> presetFlat());
        body.setOnClickListener(v -> presetIphoneBody());
        subwoofer.setOnClickListener(v -> presetSubwoofer());
        defaults.setOnClickListener(v -> presetDefault());

        root.addView(section("TOP / EARPIECE — MÉDIOS + AGUDOS"));
        root.addView(text("Cada banda: frequência (Hz) • largura/Q experimental • ganho (dB). Ganho limitado a -24…+6 dB.", 12, false));
        addBandEditor(root, top, "TOP");

        root.addView(section("BOTTOM — WOOFER / MID"));
        root.addView(text("Use as bandas 1–4 para peso/corpo e 5–6 para médios. As bandas 7–8 controlam quanto agudo fica no speaker inferior.", 12, false));
        addBandEditor(root, bottom, "BOT");

        root.addView(section("PROTEÇÃO DO TRANSDUTOR"));
        TextView safety = text("CSPL: ON (bloqueado)\nTérmica: ON (bloqueado)\nExcursão: ON (bloqueado)\nCorrente/tensão: ON (bloqueado)\nCalibração BOT/RCV: STOCK", 14, true);
        safety.setTextColor(Color.rgb(30,120,60));
        root.addView(safety);
        root.addView(text("Grave forte pode elevar excursão e temperatura mesmo em volume moderado. O Audio Lab não altera *.wmfw, spk-prot.bin nem calib.bin.", 12, false));

        root.addView(section("APLICAR"));
        Button apply = button("APLICAR APK / LAB");
        Button applyReboot = button("APLICAR + REINICIAR");
        Button reboot = button("REINICIAR AGORA");
        root.addView(apply); root.addView(applyReboot); root.addView(reboot);
        apply.setOnClickListener(v -> applyLab(false));
        applyReboot.setOnClickListener(v -> applyLab(true));
        reboot.setOnClickListener(v -> confirmReboot());

        setContentView(scroll);
    }

    private void addBandEditor(LinearLayout parent, EditText[][] dst, String prefix) {
        for (int i=0;i<8;i++) {
            LinearLayout r = row();
            TextView n = text(prefix + " " + (i+1), 12, true);
            n.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(n, new LinearLayout.LayoutParams(dp(55), dp(48)));
            for (int j=0;j<3;j++) {
                EditText e = new EditText(this);
                e.setSingleLine(true);
                e.setTextSize(13);
                e.setGravity(Gravity.CENTER);
                e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                        (j==2 ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
                e.setHint(j==0 ? "Hz" : j==1 ? "Width" : "dB");
                dst[i][j] = e;
                r.addView(e, weight());
            }
            parent.addView(r);
        }
    }

    private void loadPrefsOrDefaults() {
        for (int i=0;i<8;i++) for (int j=0;j<3;j++) {
            top[i][j].setText(String.valueOf(prefs.getInt("t_"+i+"_"+j, TOP_DEFAULT[i][j])));
            bottom[i][j].setText(String.valueOf(prefs.getInt("b_"+i+"_"+j, BOTTOM_DEFAULT[i][j])));
        }
        int bits = prefs.getInt("bits",24);
        if (bits==32) bit32.setChecked(true); else bit24.setChecked(true);
        int rate = prefs.getInt("rate",48000);
        for (int i=0;i<rateGroup.getChildCount();i++) {
            RadioButton rb=(RadioButton)rateGroup.getChildAt(i);
            if (((Integer)rb.getTag())==rate) rb.setChecked(true);
        }
    }

    private void saveFormatPrefs() {
        int rate=48000;
        int id=rateGroup.getCheckedRadioButtonId();
        if (id!=-1) {
            RadioButton rb=findViewById(id);
            if (rb!=null && rb.getTag() instanceof Integer) rate=(Integer)rb.getTag();
        }
        prefs.edit().putInt("bits", bit32.isChecked()?32:24).putInt("rate",rate).apply();
    }

    private void saveBandPrefs() {
        SharedPreferences.Editor ed=prefs.edit();
        for (int i=0;i<8;i++) for (int j=0;j<3;j++) {
            ed.putInt("t_"+i+"_"+j, readInt(top[i][j], TOP_DEFAULT[i][j]));
            ed.putInt("b_"+i+"_"+j, readInt(bottom[i][j], BOTTOM_DEFAULT[i][j]));
        }
        ed.apply();
    }

    private void refreshStatus() {
        exec.submit(() -> {
            boolean root = RootShell.hasRoot();
            boolean mod = root && RootShell.exists(MODULE_DIR + "/module.prop");
            boolean a32 = root && RootShell.exists(AUDIO32_MARKER);
            String build = Build.DISPLAY == null ? "?" : Build.DISPLAY;
            String txt = "Modelo: " + Build.MODEL + "\nBuild: " + build +
                    "\nRoot: " + (root?"OK":"NÃO") +
                    "\nBackend: " + (mod?"OK":"NÃO INSTALADO") +
                    "\nAudio32 kernel: " + (a32?"DETECTADO":"AUSENTE") +
                    "\nPerfil ativo: " + prefs.getString("active_mode","desconhecido");
            runOnUiThread(() -> {
                status.setText(txt);
                bit32.setEnabled(a32);
                if (!a32 && bit32.isChecked()) bit24.setChecked(true);
            });
        });
    }

    private void confirmAndApplyStock() {
        new AlertDialog.Builder(this).setTitle("Restaurar Padrão Samsung?")
                .setMessage("O arquivo tonal HZA6 original será colocado no overlay do módulo. As proteções Cirrus não são alteradas. Reinício necessário.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Restaurar",(d,w)->applyStock()).show();
    }

    private void applyStock() {
        if (!deviceLooksRight()) return;
        setStatus("Gravando perfil Padrão Samsung...");
        exec.submit(() -> {
            if (!ensureBackend()) return;
            try {
                String stock=readAssetText(STOCK_ASSET);
                RootShell.Result r=RootShell.writeText(ACTIVE_PARAM,stock);
                if (!r.ok()) { uiError("Falha ao gravar perfil stock:\n"+r.out); return; }
                prefs.edit().putString("active_mode","PADRÃO SAMSUNG").apply();
                runOnUiThread(() -> status.setText("PADRÃO gravado. Reinicie para aplicar."));
            } catch(Throwable t){ uiError(t.toString()); }
        });
    }

    private void applyLab(boolean rebootAfter) {
        if (!deviceLooksRight()) return;
        int[][] t, b;
        try { t=readBands(top); b=readBands(bottom); }
        catch(IllegalArgumentException e){ Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); return; }
        saveBandPrefs(); saveFormatPrefs();
        setStatus("Gerando perfil APK / LAB...");
        exec.submit(() -> {
            if (!ensureBackend()) return;
            try {
                String stock=readAssetText(STOCK_ASSET);
                String custom=patchBanks(stock,t,b);
                RootShell.Result r=RootShell.writeText(ACTIVE_PARAM,custom);
                if (!r.ok()) { uiError("Falha ao gravar perfil LAB:\n"+r.out); return; }
                prefs.edit().putString("active_mode","APK / LAB").apply();
                if (rebootAfter) {
                    runOnUiThread(() -> status.setText("LAB gravado. Reiniciando..."));
                    Thread.sleep(500);
                    RootShell.exec("reboot");
                } else runOnUiThread(() -> status.setText("APK / LAB gravado. Reinicie para aplicar.\nProteção CSPL/térmica/excursão permanece ON."));
            } catch(Throwable x){ uiError(x.toString()); }
        });
    }

    private boolean ensureBackend() {
        if (!RootShell.hasRoot()) { uiError("Root Magisk não concedido."); return false; }
        if (!RootShell.exists(MODULE_DIR + "/module.prop")) {
            uiError("Backend G991B Audio Lab não está instalado. Instale o ZIP companion no Magisk e reinicie.");
            return false;
        }
        return true;
    }

    private String patchBanks(String stock, int[][] t, int[][] b) {
        Map<String,String> repl=new HashMap<>();
        for(int i=0;i<8;i++) {
            char c=(char)('A'+i);
            repl.put("AA"+c, line("AA"+c,t[i]));
            repl.put("BA"+c, line("BA"+c,t[i]));
            repl.put("AC"+c, line("AC"+c,b[i]));
            repl.put("BC"+c, line("BC"+c,b[i]));
        }
        StringBuilder out=new StringBuilder(stock.length()+128);
        for(String ln:stock.split("\\r?\\n",-1)) {
            if(ln.length()>=3 && repl.containsKey(ln.substring(0,3))) out.append(repl.get(ln.substring(0,3)));
            else out.append(ln);
            out.append('\n');
        }
        return out.toString();
    }

    private String line(String key,int[] v){ return key+","+v[0]+","+v[1]+","+v[2]; }

    private int[][] readBands(EditText[][] src) {
        int[][] out=new int[8][3];
        for(int i=0;i<8;i++) {
            out[i][0]=readInt(src[i][0],0);
            out[i][1]=readInt(src[i][1],0);
            out[i][2]=readInt(src[i][2],0);
            if(out[i][0]<40 || out[i][0]>20000) throw new IllegalArgumentException("Frequência da banda "+(i+1)+" deve ficar entre 40 e 20000 Hz.");
            if(out[i][1]<10 || out[i][1]>12000) throw new IllegalArgumentException("Largura da banda "+(i+1)+" deve ficar entre 10 e 12000.");
            if(out[i][2]<-24 || out[i][2]>6) throw new IllegalArgumentException("Ganho da banda "+(i+1)+" deve ficar entre -24 e +6 dB.");
        }
        return out;
    }

    private void presetDefault(){ setBands(top,TOP_DEFAULT); setBands(bottom,BOTTOM_DEFAULT); toast("LAB DEFAULT carregado. Toque APK / LAB para gravar."); }
    private void presetFlat(){
        int[][] t=copy(TOP_DEFAULT), b=copy(BOTTOM_DEFAULT);
        for(int i=0;i<8;i++){t[i][2]=0;b[i][2]=0;}
        setBands(top,t);setBands(bottom,b);toast("REFERENCE FLAT carregado.");
    }
    private void presetIphoneBody(){
        int[][] t=copy(TOP_DEFAULT), b=copy(BOTTOM_DEFAULT);
        int[] tg={-16,-12,-7,-2,0,1,1,0}; int[] bg={3,5,4,2,1,0,-3,-8};
        for(int i=0;i<8;i++){t[i][2]=tg[i];b[i][2]=bg[i];}
        setBands(top,t);setBands(bottom,b);toast("iPHONE BODY carregado.");
    }
    private void presetSubwoofer(){
        int[][] t=copy(TOP_DEFAULT), b=copy(BOTTOM_DEFAULT);
        int[] tg={-18,-15,-9,-3,0,1,1,0}; int[] bg={5,6,5,3,1,0,-5,-12};
        for(int i=0;i<8;i++){t[i][2]=tg[i];b[i][2]=bg[i];}
        setBands(top,t);setBands(bottom,b);toast("SUBWOOFER HEAVY carregado. Comece em volume moderado.");
    }
    private int[][] copy(int[][] a){int[][]x=new int[a.length][3];for(int i=0;i<a.length;i++)x[i]=a[i].clone();return x;}
    private void setBands(EditText[][] dst,int[][] vals){for(int i=0;i<8;i++)for(int j=0;j<3;j++)dst[i][j].setText(String.valueOf(vals[i][j]));saveBandPrefs();}

    private boolean deviceLooksRight() {
        if (!"SM-G991B".equalsIgnoreCase(Build.MODEL)) {
            new AlertDialog.Builder(this).setTitle("Dispositivo diferente")
                    .setMessage("Este build foi feito para SM-G991B HZA6. Modelo atual: "+Build.MODEL+". Aplicação bloqueada para evitar escrever parâmetros no aparelho errado.")
                    .setPositiveButton("OK",null).show();
            return false;
        }
        return true;
    }

    private void confirmReboot(){
        new AlertDialog.Builder(this).setTitle("Reiniciar agora?")
                .setMessage("O perfil SoundBooster selecionado será carregado no próximo boot.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Reiniciar",(d,w)->exec.submit(()->RootShell.exec("reboot"))).show();
    }

    private String readAssetText(String name) throws IOException {
        try(InputStream in=getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private int readInt(EditText e,int fallback){try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception x){return fallback;}}
    private void setStatus(String s){status.setText(s);}
    private void uiError(String s){runOnUiThread(()->{status.setText("ERRO\n"+s);Toast.makeText(this,s,Toast.LENGTH_LONG).show();});}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private TextView section(String s){TextView v=text(s,16,true);v.setPadding(0,dp(18),0,dp(6));return v;}
    private TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
