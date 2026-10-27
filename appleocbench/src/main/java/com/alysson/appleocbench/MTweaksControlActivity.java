package com.alysson.appleocbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MTweaksControlActivity extends Activity {
    private static final int BG=0xff0d131d, CARD=0xff182431, TEXT=0xffeef5ff,
        MUTED=0xff9dafc3, TEAL=0xff68ded1, BLUE=0xff91bfff, AMBER=0xffffbc65,
        GOOD=0xff99f2c0, BAD=0xffff9a9a;

    private static final String[][] DEFINITIONS={
        {"GPU","Floppy GPU Unlock","/sys/kernel/gpu/gpu_unlock","switch","1 habilita o limite desbloqueado; 0 retorna ao limite normal"},
        {"GPU","Frequência máxima · kHz","/sys/kernel/gpu/gpu_max_clock","number","Limite máximo da tabela que o kernel disponibiliza"},
        {"GPU","Frequência mínima · kHz","/sys/kernel/gpu/gpu_min_clock","number","Limite mínimo da tabela que o kernel disponibiliza"},
        {"GPU","GPU clamp · kHz","/sys/kernel/gpu/gpu_clamp","number","0 remove o clamp; outra frequência deve constar na tabela"},
        {"TENSÃO","Margem GPU G3D · %","/sys/kernel/percent_margin/g3d_margin_percent","number","Margem percentual encaminhada ao ACPM; não é medição de tensão"},
        {"TENSÃO","Margem CPU A55 · %","/sys/kernel/percent_margin/cpucl0_margin_percent","number","Margem percentual do cluster de eficiência"},
        {"TENSÃO","Margem CPU A78 · %","/sys/kernel/percent_margin/cpucl1_margin_percent","number","Margem percentual do cluster intermediário"},
        {"TENSÃO","Margem CPU X1 · %","/sys/kernel/percent_margin/cpucl2_margin_percent","number","Margem percentual do núcleo de desempenho"},
        {"CPU","A55 · frequência máxima · kHz","/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq","number","CPU0-3"},
        {"CPU","A55 · frequência mínima · kHz","/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq","number","CPU0-3"},
        {"CPU","A55 · governador","/sys/devices/system/cpu/cpufreq/policy0/scaling_governor","text","Valores em scaling_available_governors"},
        {"CPU","A78 · frequência máxima · kHz","/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq","number","CPU4-6"},
        {"CPU","A78 · frequência mínima · kHz","/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq","number","CPU4-6"},
        {"CPU","A78 · governador","/sys/devices/system/cpu/cpufreq/policy4/scaling_governor","text","Valores em scaling_available_governors"},
        {"CPU","X1 · frequência máxima · kHz","/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq","number","CPU7"},
        {"CPU","X1 · frequência mínima · kHz","/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq","number","CPU7"},
        {"CPU","X1 · governador","/sys/devices/system/cpu/cpufreq/policy7/scaling_governor","text","Valores em scaling_available_governors"},
        {"GPU","GPEX · limite máximo · kHz","/sys/devices/platform/18500000.mali/dvfs_max_lock","number","0 remove o limite; caminho encontrado no driver Mali"},
        {"GPU","GPEX · limite mínimo · kHz","/sys/devices/platform/18500000.mali/dvfs_min_lock","number","0 remove o limite; controle Mali independente de GPU Unlock"},
        {"GPU","GPEX · governador","/sys/devices/platform/18500000.mali/dvfs_governor","text","Somente governadores oferecidos pelo driver"},
        {"BARRAMENTO","MIF · frequência mínima","/sys/class/devfreq/17000010.devfreq_mif/min_freq","number","Visível apenas se a versão do kernel expuser o nó"},
        {"BARRAMENTO","MIF · frequência máxima","/sys/class/devfreq/17000010.devfreq_mif/max_freq","number","Unidade indicada pelo próprio nó"},
        {"BARRAMENTO","INT · frequência mínima","/sys/class/devfreq/17000020.devfreq_int/min_freq","number","Visível apenas se a versão do kernel expuser o nó"},
        {"BARRAMENTO","INT · frequência máxima","/sys/class/devfreq/17000020.devfreq_int/max_freq","number","Unidade indicada pelo próprio nó"}
    };
    private static final String[] TABS={"TODOS","GPU","TENSÃO","CPU","BARRAMENTO"};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,String> originals=new LinkedHashMap<>();
    private final Map<String,String> current=new LinkedHashMap<>();
    private final Map<String,Boolean> available=new LinkedHashMap<>();
    private final Map<String,Boolean> writable=new LinkedHashMap<>();
    private final Map<String,TextView> values=new LinkedHashMap<>();
    private final Map<String,TextView> availabilityLabels=new LinkedHashMap<>();
    private final Map<String,Button> restoreButtons=new LinkedHashMap<>();
    private LinearLayout cards, tabs;
    private TextView banner, log, count;
    private String tab="TODOS";
    private volatile boolean busy=false;
    private volatile boolean rootOk=false;
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable bg(int color,int corner){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(corner));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(null,Typeface.BOLD);return v;
    }
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private void add(LinearLayout dst,View child,int bottom){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(bottom);dst.addView(child,lp);
    }
    private Button button(String caption,boolean primary){
        Button b=new Button(this);b.setText(caption);b.setAllCaps(false);
        b.setTextColor(primary?BG:TEXT);
        b.setTextSize(12);
        b.setBackground(bg(primary?TEAL:0xff294157,10));
        b.setMinHeight(dp(44));b.setPadding(dp(10),0,dp(10),0);
        b.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(65).start();break;
                case android.view.MotionEvent.ACTION_UP:
                    v.animate().scaleX(1).scaleY(1).setDuration(130).start();
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1).scaleY(1).setDuration(110).start();break;
            }
            return false;
        });
        return b;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
        scan();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout body=column();body.setPadding(dp(16),dp(24),dp(16),dp(35));
        scroll.addView(body);

        TextView subtitle=text("KERNEL CONTROL · EXYNOS 2100",12,TEAL,true);add(body,subtitle,5);
        add(body,text("Apple Final × MTweaks",26,TEXT,true),5);
        add(body,text("FloppyKernel · 24 controles reais · root",13,MUTED,false),15);

        LinearLayout hero=column();hero.setBackground(bg(CARD,15));hero.setPadding(dp(15),dp(14),dp(15),dp(14));
        banner=text("Detectando kernel e permissões...",13,TEXT,false);add(hero,banner,11);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh=button("ATUALIZAR",true);
        Button oldLab=button("GPU LAB",false);
        row.addView(refresh,new LinearLayout.LayoutParams(0,dp(48),1f));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(48),1f);rp.leftMargin=dp(8);
        row.addView(oldLab,rp);
        refresh.setOnClickListener(v->scan());
        oldLab.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
        add(hero,row,9);
        Button mtImport=button("INSTALAR CONTROLES NO MTWEAKS ORIGINAL",true);
        add(hero,mtImport,0);
        mtImport.setOnClickListener(v->confirmMtweaksImport());
        add(body,hero,17);

        count=text("CARREGANDO PARÂMETROS...",13,BLUE,true);add(body,count,10);
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);
        tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(tabs);add(body,strip,13);
        cards=column();add(body,cards,15);

        LinearLayout info=column();info.setBackground(bg(CARD,13));info.setPadding(dp(13),dp(12),dp(13),dp(13));
        add(info,text("RETORNO DA ÚLTIMA OPERAÇÃO",11,AMBER,true),7);
        log=text("Os comandos ainda não foram executados.",12,MUTED,false);
        log.setTypeface(Typeface.MONOSPACE);
        add(info,log,0);add(body,info,14);

        add(body,text("O retorno confirma escrita e registro do driver. A margem ACPM não representa tensão física medida. Frequências só mudam quando o kernel contém o ponto de operação correspondente. Nenhum ajuste é reaplicado automaticamente na inicialização.",12,MUTED,false),0);
        setContentView(scroll);
        createTabs();
        render();
    }
    private void createTabs(){
        tabs.removeAllViews();
        for(String title:TABS){
            Button b=button(title.equals(tab)?title+" ●":title,title.equals(tab));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(43));p.rightMargin=dp(7);
            tabs.addView(b,p);
            b.setOnClickListener(v->{tab=title;createTabs();render();});
        }
    }
    private void render(){
        cards.removeAllViews();
        int usable=0;
        for(String[] def:DEFINITIONS){
            if(!tab.equals("TODOS")&&!tab.equals(def[0]))continue;
            String path=def[2];
            boolean exists=Boolean.TRUE.equals(available.get(path));
            boolean can=Boolean.TRUE.equals(writable.get(path));
            if(exists)usable++;
            LinearLayout card=column();card.setPadding(dp(14),dp(12),dp(14),dp(12));
            card.setBackground(bg(CARD,13));
            add(card,text(def[1],15,TEXT,true),6);
            TextView label=text(exists?(can?"● disponível para ajuste":"● disponível somente para leitura"):"○ controle não detectado",
                11,exists?(can?GOOD:AMBER):MUTED,false);
            add(card,label,3);availabilityLabels.put(path,label);
            TextView v=text("Atual: "+(current.containsKey(path)?current.get(path):"—"),17,TEAL,true);
            add(card,v,7);values.put(path,v);
            add(card,text(def[4],12,MUTED,false),10);
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
            Button edit=button(def[3].equals("switch")?"ALTERNAR":"AJUSTAR",true);
            Button undo=button("RESTAURAR",false);
            actions.addView(edit,new LinearLayout.LayoutParams(0,dp(43),1.15f));
            LinearLayout.LayoutParams u=new LinearLayout.LayoutParams(0,dp(43),.95f);u.leftMargin=dp(8);actions.addView(undo,u);
            edit.setEnabled(rootOk&&can&&!busy);
            undo.setEnabled(rootOk&&can&&originals.containsKey(path)&&!busy);
            restoreButtons.put(path,undo);
            edit.setOnClickListener(view->change(def));
            undo.setOnClickListener(view->{
                String original=originals.get(path);
                if(original!=null)confirm(def,original,true);
            });
            add(card,actions,0);
            add(cards,card,11);
        }
        count.setText("PARÂMETROS: "+usable+" DISPONÍVEIS DE "+DEFINITIONS.length+" · "+tab);
    }
    static String quote(String input){
        return "'"+input.replace("'","'\\''")+"'";
    }
    static final class Cmd {
        final int rc;final String out;
        Cmd(int rc,String out){this.rc=rc;this.out=out;}
    }
    static Cmd root(String command){
        Process proc=null;
        try{
            proc=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            if(!proc.waitFor(20,TimeUnit.SECONDS)){
                proc.destroyForcibly();return new Cmd(-1,"Comando excedeu 20 segundos");
            }
            StringBuilder sb=new StringBuilder();String line;
            BufferedReader reader=new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while((line=reader.readLine())!=null&&sb.length()<262144)sb.append(line).append("\n");
            return new Cmd(proc.exitValue(),sb.toString().trim());
        }catch(Exception e){return new Cmd(-1,e.toString());}
        finally{if(proc!=null)proc.destroy();}
    }
    private void scan(){
        if(busy)return;
        busy=true;
        banner.setText("Lendo sysfs e verificando root...");
        worker.execute(()->{
            Cmd id=root("id -u; uname -r; getprop ro.product.model");
            String[] lines=id.out.split("\n");
            boolean su=id.rc==0&&lines.length>0&&lines[0].trim().equals("0");
            final String model=lines.length>2?lines[2].trim():"indisponível";
            final String linux=lines.length>1?lines[1].trim():"indisponível";
            StringBuilder cmd=new StringBuilder();
            for(int i=0;i<DEFINITIONS.length;i++){
                String path=DEFINITIONS[i][2];
                cmd.append("p=").append(quote(path))
                   .append("; if [ -e \"$p\" ]; then printf 'ROW").append(i).append("|1|'; ")
                   .append("if [ -w \"$p\" ]; then printf '1|'; else printf '0|'; fi; ")
                   .append("head -n 1 \"$p\" 2>/dev/null | tr '\\n' ' ' | tr '|' '/'; printf '\\n'; ")
                   .append("else echo 'ROW").append(i).append("|0|0|'; fi; ");
            }
            Cmd values=su?root(cmd.toString()):new Cmd(-1,"");
            Map<String,String> seen=new LinkedHashMap<>();
            Map<String,Boolean> found=new LinkedHashMap<>();
            Map<String,Boolean> write=new LinkedHashMap<>();
            for(String l:values.out.split("\n")){
                if(!l.startsWith("ROW"))continue;
                try {
                    String[] fields=l.split("\\|",4);
                    int ix=Integer.parseInt(fields[0].substring(3));
                    if(ix<0||ix>=DEFINITIONS.length)continue;
                    String path=DEFINITIONS[ix][2];
                    found.put(path,fields.length>1&&"1".equals(fields[1]));
                    write.put(path,fields.length>2&&"1".equals(fields[2]));
                    seen.put(path,fields.length>3?fields[3].trim():"");
                }catch(Exception ignored){}
            }
            ui.post(()->{
                rootOk=su;
                available.putAll(found);writable.putAll(write);current.putAll(seen);
                for(String[] def:DEFINITIONS){
                    String path=def[2];
                    if(Boolean.TRUE.equals(found.get(path))&&!originals.containsKey(path)&&
                        seen.get(path)!=null&&!seen.get(path).isEmpty())
                        originals.put(path,seen.get(path));
                }
                busy=false;
                banner.setText("Root: "+(rootOk?"ATIVO":"INATIVO")+
                   "   |   Aparelho: "+model+"\nKernel: "+linux+
                   "\nFonte: nós sysfs realmente expostos. Ajustes indisponíveis ficam identificados.");
                render();
            });
        });
    }
    private void change(String[] def){
        String path=def[2];
        if(def[3].equals("switch")){
            String currentValue=current.get(path);
            confirm(def,"1".equals(currentValue)?"0":"1",false);return;
        }
        EditText edit=new EditText(this);edit.setSingleLine(true);
        edit.setTextColor(TEXT);edit.setTextSize(19);
        edit.setPadding(dp(16),dp(18),dp(16),dp(15));
        edit.setText(current.get(path));
        edit.setSelectAllOnFocus(true);
        edit.setInputType(def[3].equals("number")?
            InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED:
            InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(def[1]).setMessage(def[4]+"\n"+path)
            .setView(edit).setNegativeButton("CANCELAR",null)
            .setNeutralButton("VALORES", (d,w)->readChoices(def))
            .setPositiveButton("APLICAR",(d,w)->confirm(def,edit.getText().toString().trim(),false)).create();
        dialog.show();
    }
    private void readChoices(String[] def){
        String path=def[2],choices="";
        if(path.startsWith("/sys/kernel/gpu/"))choices="/sys/kernel/gpu/gpu_freq_table";
        else if(path.contains("scaling_governor"))choices=path.replace("scaling_governor","scaling_available_governors");
        else if(path.contains("scaling_")&&path.contains("_freq"))choices=path.replace(path.substring(path.lastIndexOf('/')+1),"scaling_available_frequencies");
        else if(path.contains("dvfs_")&&path.endsWith("lock"))choices="/sys/kernel/gpu/gpu_freq_table";
        final String node=choices;
        if(node.isEmpty()){
            Toast.makeText(this,"Este controle não fornece uma lista padrão de opções.",Toast.LENGTH_LONG).show();return;
        }
        worker.execute(()->{
            Cmd c=root("cat "+quote(node)+" 2>/dev/null");
            String[] options=c.out.trim().split("\\s+");
            if(options.length>70) options=java.util.Arrays.copyOf(options,70);
            String[] choicesArr=options;
            ui.post(()->{
                if(c.rc!=0||choicesArr.length==0||choicesArr[0].isEmpty()){
                    Toast.makeText(this,"O kernel não forneceu a lista de opções.",Toast.LENGTH_LONG).show();
                }else{
                    new AlertDialog.Builder(this).setTitle("Disponíveis • "+def[1]).setItems(choicesArr,
                        (dialog,which)->confirm(def,choicesArr[which],false)).show();
                }
            });
        });
    }
    private void confirm(String[] def,String value,boolean restore){
        String path=def[2];
        if(value.isEmpty()||!value.matches("[A-Za-z0-9_.+-]{1,64}")){
            Toast.makeText(this,"Valor inválido ou não informado",Toast.LENGTH_LONG).show();return;
        }
        if(!rootOk||!Boolean.TRUE.equals(writable.get(path))){
            Toast.makeText(this,"Kernel não expõe escrita para este parâmetro",Toast.LENGTH_LONG).show();return;
        }
        String header=restore?"Restaurar valor inicial":"Aplicar no kernel";
        new AlertDialog.Builder(this)
            .setTitle(header).setMessage(def[1]+"\n\n"+current.get(path)+"  →  "+value+
                "\n\nSerá enviado ao sysfs e lido novamente. Sem aplicação automática no boot.")
            .setNegativeButton("CANCELAR",null)
            .setPositiveButton(restore?"RESTAURAR":"APLICAR",(d,w)->apply(def,value))
            .show();
    }

    private void confirmMtweaksImport(){
        new AlertDialog.Builder(this)
          .setTitle("Instalar no MTweaks V2.17.0")
          .setMessage("Importa 24 controles Apple Final para o banco privado do MTweaks original com root."+
              "\n\nMantém os controles preexistentes e cria cópia em controls.json.applefinal.bak."+
              "\n\nFeche o MTweaks antes de instalar. Caso ele esteja aberto, reinicie o app.")
          .setNegativeButton("CANCELAR",null)
          .setPositiveButton("IMPORTAR",(d,w)->importMtweaks())
          .show();
    }
    private void importMtweaks(){
        if(busy)return;
        busy=true;
        log.setText("Lendo banco de controles do MTweaks original...");
        worker.execute(()->{
            String target="/data/user/0/com.moro.mtweaks/files/controls.json";
            String dir="/data/user/0/com.moro.mtweaks/files";
            String readCmd="if [ -d "+quote(dir)+" ]; then "+
                "if [ -f "+quote(target)+" ]; then cat "+quote(target)+"; "+
                "else printf '%s' '{\"version\":1,\"database\":[]}'; fi; "+
                "else echo 'MTWEAKS_NOT_INSTALLED'; exit 13; fi";
            Cmd existing=root(readCmd);
            String status;boolean success=false;
            if(existing.rc!=0||existing.out.contains("MTWEAKS_NOT_INSTALLED")){
                status="MTweaks não localizado no armazenamento privado. Instale o MTweaks original e abra uma vez.";
            } else {
                try{
                    JSONObject data=new JSONObject(existing.out);
                    if(data.optInt("version",0)!=1)
                      throw new Exception("Versão de controles não reconhecida: "+data.optInt("version"));
                    JSONArray entries=data.optJSONArray("database");
                    if(entries==null)entries=new JSONArray();
                    int additions=0,skipped=0;
                    java.util.HashSet<String> used=new java.util.HashSet<>();
                    for(int i=0;i<entries.length();i++){
                        JSONObject item=entries.optJSONObject(i);
                        if(item!=null) used.add(item.optString("title",""));
                    }
                    for(int i=0;i<DEFINITIONS.length;i++){
                        String[] def=DEFINITIONS[i];String title="Apple Final • "+def[1];
                        if(used.contains(title)){skipped++;continue;}
                        String node=def[2];
                        JSONObject item=new JSONObject();
                        boolean toggle=def[3].equals("switch");
                        item.put("id",toggle?"switch":"generic");
                        item.put("title",title);
                        item.put("description",def[4]);
                        item.put("uniqueId",80001+i);
                        item.put("onboot",false);
                        item.put("arguments","");
                        String valueReader="#!/system/bin/sh\ncat "+node+
                            " 2>/dev/null | head -n 1 || echo 0\n";
                        if(toggle)item.put("enable",valueReader);
                        else item.put("value",valueReader);
                        String script="#!/system/bin/sh\np="+node+"\nv=\"$1\"\n"+
                            "[ -w \"$p\" ] || { echo 'Nó indisponível' >&2; exit 12; }\n"+
                            "case \"$v\" in *[!a-zA-Z0-9_.+-]*|'') exit 15;; esac\n"+
                            "printf '%s\\n' \"$v\" > \"$p\" || exit 18\n"+
                            "cat \"$p\" 2>/dev/null\n";
                        item.put("apply",script);
                        entries.put(item);additions++;
                    }
                    data.put("database",entries);
                    File tmp=new File(getFilesDir(),"mtweaks_applefinal_merged.json");
                    try(OutputStreamWriter writer=new OutputStreamWriter(new FileOutputStream(tmp),"UTF-8")){
                        writer.write(data.toString());writer.flush();
                    }
                    String cmd="target="+quote(target)+"; "+
                        "source="+quote(tmp.getAbsolutePath())+"; "+
                        "if [ ! -d "+quote(dir)+" ]; then exit 11; fi; "+
                        "if [ -f \"$target\" ]; then "+
                        "cp -p \"$target\" \"$target.applefinal.bak\" || exit 12; "+
                        "owner=$(stat -c '%u:%g' \"$target\" 2>/dev/null); "+
                        "else owner=$(stat -c '%u:%g' "+quote(dir)+"); fi; "+
                        "cat \"$source\" > \"$target\" || exit 18; "+
                        "[ -z \"$owner\" ] || chown \"$owner\" \"$target\"; "+
                        "chmod 600 \"$target\"; "+
                        "echo 'WRITE_DONE'; wc -c < \"$target\"";
                    Cmd res=root(cmd);
                    if(res.rc==0&&res.out.contains("WRITE_DONE")){
                        Cmd verify=root("cat "+quote(target));
                        JSONObject roundtrip=new JSONObject(verify.out);
                        if(roundtrip.getJSONArray("database").length()==entries.length()){
                            success=true;
                            status="MTweaks atualizado: "+additions+" controles novos, "+skipped+
                                " já existentes. Total no banco: "+entries.length()+
                                "\nBackup: "+target+".applefinal.bak"+
                                "\nFeche e abra novamente o MTweaks para atualizar a lista.";
                        } else status="Escrita não confirmou o total esperado. Restaure o backup.";
                    }else status="Erro ao gravar banco original (root "+res.rc+"): "+res.out;
                }catch(Exception ex){status="Importação interrompida. "+ex.toString();}
            }
            final boolean ok=success;final String info=status;
            ui.post(()->{
                busy=false;log.setText((ok?"IMPORTAÇÃO CONFIRMADA\n":"IMPORTAÇÃO NÃO CONFIRMADA\n")+info);
                log.setTextColor(ok?GOOD:BAD);
                getWindow().getDecorView().performHapticFeedback(
                    ok?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.REJECT);
                Toast.makeText(this,ok?"Controles adicionados ao MTweaks":"Veja o diagnóstico da importação",Toast.LENGTH_LONG).show();
                render();
            });
        });
    }

    private void apply(String[] def,String value){
        if(busy)return;
        busy=true;
        log.setText("ENVIANDO...\n"+def[1]+"\n"+value);
        String path=def[2];
        worker.execute(()->{
            String cmd="p="+quote(path)+"; v="+quote(value)+"; "+
                "if [ ! -w \"$p\" ]; then echo 'WRITE_RC=12'; exit 12; fi; "+
                "printf '%s\\n' \"$v\" > \"$p\"; c=$?; echo \"WRITE_RC=$c\"; "+
                "echo \"READBACK_BEGIN\"; head -n 1 \"$p\" 2>/dev/null; exit \"$c\"";
            Cmd result=root(cmd);
            String after="";
            int begin=result.out.indexOf("READBACK_BEGIN");
            if(begin>=0){String tail=result.out.substring(begin+"READBACK_BEGIN".length()).trim();
                if(!tail.isEmpty())after=tail.split("\n")[0].trim();}
            final String observed=after;
            final boolean ok=result.rc==0 &&
                (value.equals(observed) ||
                  (def[3].equals("text")&&result.out.contains(value)) ||
                  (def[1].contains("GPEX")&&!observed.isEmpty()));
            final String diagnostic=(ok?"REGISTRO ATUALIZADO":"SEM CONFIRMAÇÃO")+
                "\n"+def[1]+"\nSolicitado: "+value+" | Readback: "+
                (observed.isEmpty()?"indisponível":observed)+
                "\nCódigo shell: "+result.rc+
                "\n"+path+
                (!ok?"\nDetalhes: "+result.out:"")+
                (path.contains("percent_margin")?"\nACPM: registro do driver, não medição física":"");
            ui.post(()->{
                busy=false;
                if(!observed.isEmpty())current.put(path,observed);
                log.setText(diagnostic);log.setTextColor(ok?GOOD:BAD);
                getWindow().getDecorView().performHapticFeedback(
                    ok?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.REJECT);
                Toast.makeText(this,ok?"Valor registrado no kernel":"Kernel não confirmou o valor",Toast.LENGTH_SHORT).show();
                render();
            });
        });
    }
}
