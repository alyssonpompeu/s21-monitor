package com.alysson.appleocbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class VoltageLabActivity extends Activity {
  private final int BG=0xff090f1b, PANEL=0xff152338, CYAN=0xff64c9e7,
      WHITE=0xffebf3ff, SUB=0xffa3b4c8, WARN=0xffffc785, GREEN=0xff8de3bd;
  private final String[] TITLES={"GPU • Mali G78","CPU • A55 eficiência","CPU • A78 desempenho","CPU • X1 principal"};
  private final String[] MARGINS={"g3d_margin_percent","cpucl0_margin_percent","cpucl1_margin_percent","cpucl2_margin_percent"};
  private final String[] CPU_POLICIES={"","policy0","policy4","policy7"};
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private final ArrayList<Integer>[] clocks=new ArrayList[4];
  private final HashMap<Integer,Integer>[] edits=new HashMap[4];
  private final HashMap<Integer,Integer>[] baseUv=new HashMap[4];
  private final int[] domainMargins={0,0,0,0};
  private final boolean[] marginFound={false,false,false,false};
  private int domain=0, mode=0, curveEndMv=-15, expo10=20, selectedIdx=0;
  private volatile boolean busy=false;
  private boolean trustedKernel=false;
  private final int[] initialMargins={0,0,0,0};
  private final boolean[] initialSet={false,false,false,false};
  private EditText percentInput;
  private Button resetButton;
  private TextView actionLog;
  private String lastAction="Nenhum comando executado";
  private TextView metrics, status, summary, value, exptxt, editTitle, frequencyInfo;
  private SeekBar mvSeek, exponentSeek;
  private Spinner domainSpinner, freqSpinner;
  private RadioGroup modes;

  private Button applyButton;
  private LinearLayout curveControls, singleControls;
  private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  private GradientDrawable rect(int fill,int radius){
    GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;
  }
  private TextView text(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
    if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
  }
  private LinearLayout panel(){
    LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(16),dp(16),dp(16),dp(16));l.setBackground(rect(PANEL,16));return l;
  }
  private void item(LinearLayout l,View v,int bottom){
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);l.addView(v,p);
  }
  private Button button(String name){
    Button b=new Button(this);b.setText(name);b.setAllCaps(false);
    b.setTextColor(WHITE);b.setBackground(rect(0xff24506a,11));
    b.setOnTouchListener((v,e)->{
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
        v.animate().scaleX(0.974f).scaleY(0.974f).setDuration(90).start();
      }else if(e.getActionMasked()==MotionEvent.ACTION_UP){
        v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
        if(v.isEnabled())v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      }else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){
        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
      }
      return false;
    });
    return b;
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);
    for(int i=0;i<4;i++){clocks[i]=new ArrayList<>();edits[i]=new HashMap<>();baseUv[i]=new HashMap<>();}
    createUI();refresh();
  }
  @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
  private void createUI(){
    ScrollView sc=new ScrollView(this);sc.setBackgroundColor(BG);sc.setFillViewport(true);
    sc.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
      return insets;
    });
    LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16),dp(26),dp(16),dp(25));sc.addView(body);
    item(body,text("VOLTAGE LAB",29,WHITE,true),2);
    item(body,text("APPLE FINAL  /  EXYNOS 2100",12,CYAN,true),16);
    LinearLayout top=panel();
    item(top,text("DOMÍNIO DE ENERGIA",12,CYAN,true),9);
    domainSpinner=new Spinner(this);
    ArrayAdapter<String> domainAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,TITLES);
    domainSpinner.setAdapter(domainAdapter);item(top,domainSpinner,14);
    domainSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){domain=pos;selectedIdx=0;updateUI();}
      @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
    });
    metrics=text("Verificando tabela ASV / DVFS...",13,WHITE,false);item(top,metrics,3);
    item(body,top,12);

    LinearLayout table=panel();
    item(table,text("FREQUÊNCIAS REAIS IDENTIFICADAS",12,CYAN,true),9);
    frequencyInfo=text("Carregando tabela ASV / frequências...",13,WHITE,false);
    item(table,frequencyInfo,4);
    item(body,table,12);

    LinearLayout real=panel();
    item(real,text("TENSÃO REAL • MARGEM ACPM",12,CYAN,true),7);
    status=text("Lendo tensão/offset via kernel...",13,WHITE,false);item(real,status,9);
    item(real,text("Controle real de margem por domínio. Não é uma tensão em mV nem permite aplicar a curva separadamente por frequência.",12,SUB,false),8);
    item(real,text("Margem real por domínio (%) • ajuste no ACPM",13,SUB,false),4);
    percentInput=new EditText(this);percentInput.setSingleLine(true);
    percentInput.setTextColor(WHITE);percentInput.setTextSize(20);
    percentInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    percentInput.setText("0");percentInput.setSelectAllOnFocus(true);
    item(real,percentInput,9);
    applyButton=button("APLICAR NO ACPM");item(real,applyButton,6);
    applyButton.setEnabled(false);applyButton.setOnClickListener(v->confirmMargin());
    resetButton=button("RESTAURAR VALOR INICIAL");item(real,resetButton,9);
    resetButton.setOnClickListener(v->applyMargin(domain,initialMargins[domain],true));
    actionLog=text("Nenhum comando executado.",12,GREEN,false);
    item(real,actionLog,10);
    Button reload=button("Atualizar leituras");item(real,reload,0);
    reload.setOnClickListener(v->refresh());
    item(body,real,12);
    item(body,text("Aplicar envia a margem por domínio ao driver Samsung. Restaurar volta ao valor identificado quando o Lab iniciou. Não altera tensões individualmente por frequência. O valor é solicitado ao ACPM, não medido fisicamente.",12,SUB,false),0);
    setContentView(sc);
  }
  private static class Run {final int code;final String out;Run(int x,String s){code=x;out=s;}}
  private static Run run(String script,int limitSec){
    Process p=null;
    try{
      p=new ProcessBuilder("su","-c",script).redirectErrorStream(true).start();
      if(!p.waitFor(limitSec,TimeUnit.SECONDS)){p.destroyForcibly();return new Run(-1,"timeout");}
      BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));StringBuilder sb=new StringBuilder();String x;
      while((x=br.readLine())!=null && sb.length()<10000)sb.append(x).append('\n');
      return new Run(p.exitValue(),sb.toString().trim());
    }catch(Exception e){return new Run(-1,e.toString());}finally{if(p!=null)p.destroy();}
  }
  private static String q(String x){return "'"+x.replace("'","'\\''")+"'";}
  private static String read(String cmd){Run r=run(cmd,12);return r.code==0?r.out:"";}
  private static int parseInt(String s,int def){try{return Integer.parseInt(s.trim());}catch(Exception e){return def;}}
  private static int temp(){
    String v=read("for f in /sys/class/thermal/thermal_zone*/type; do "+
        "t=$(cat \"$f\" 2>/dev/null);case \"$t\" in *G3D*|*g3d*|*GPU*|*gpu*|*mali*|*MALI*) "+
        "cat \"$(dirname \"$f\")/temp\"; exit 0;; esac; done");
    int x=parseInt(v,-1);return x>0&&x<150?x*1000:x;
  }
  private void refresh(){
    if(busy)return;busy=true;metrics.setText("Lendo hardware e tabelas reais...");
    executor.execute(()->{
      String[] marginValues=new String[4];
      for(int i=0;i<4;i++){
        final int idx=i;
        String node="/sys/kernel/percent_margin/"+MARGINS[idx];
        Run r=run("[ -r "+q(node)+" ] && cat "+q(node),10);
        marginValues[i]=r.out;
        marginFound[i]=r.code==0 && parseInt(r.out,999)!=999;
        domainMargins[i]=parseInt(r.out,0);
        if(marginFound[i]&&!initialSet[i]){
          initialMargins[i]=domainMargins[i];initialSet[i]=true;
        }
        clocks[i].clear();baseUv[i].clear();
        if(i==0){
          String output=read("find /sys/devices/platform -maxdepth 6 -type f -name asv_table 2>/dev/null | "+
            "head -n 1 | while read f; do cat \"$f\"; done");
          for(String line:output.split("\n")){
            String[] pair=line.trim().split("[,\\s]+");
            if(pair.length<2)continue;
            int khz=parseInt(pair[0],-1),uv=parseInt(pair[1],-1);
            if(khz>=100000&&khz<=1500000&&uv>=300000&&uv<=1800000){
              if(!clocks[i].contains(khz))clocks[i].add(khz);baseUv[i].put(khz,uv);
            }
          }
        }else{
          String output=read("cat /sys/devices/system/cpu/cpufreq/"+CPU_POLICIES[i]+"/scaling_available_frequencies 2>/dev/null");
          for(String num:output.split("[\\s,]+")){
            int khz=parseInt(num,-1);if(khz>100000&&khz<4000000&&!clocks[i].contains(khz))clocks[i].add(khz);
          }
        }
        Collections.sort(clocks[i]);
      }
      String caps=read("find /sys/devices/platform -maxdepth 6 -type f -name apple_gpuoc_caps 2>/dev/null | "+
        "head -n 1 | while read f; do cat \"$f\"; done");
      String model=read("getprop ro.product.model");
      boolean root=read("id").contains("uid=0");
      trustedKernel=root && "SM-G991B".equals(model) && caps.contains("mode=ACPM_ONLY");
      main.post(()->{busy=false;updateUI();});
    });
  }
  private void updateUI(){
    if(metrics==null || frequencyInfo==null)return;
    ArrayList<Integer> list=clocks[domain];
    metrics.setText("Frequências: "+list.size()+
        (domain==0?" | ASV nominal: "+baseUv[0].size()+" pontos":" | CPU freq: cpufreq")+
        "\nMargem atual do domínio: "+
        (marginFound[domain]?domainMargins[domain]+"%":"sem interface"));
    StringBuilder b=new StringBuilder();
    if(list.isEmpty()){
      b.append("O driver não expôs a lista. Verifique a tabela ASV ou cpufreq.");
    } else{
      // Frequencies shown below were read from the kernel, never fabricated.
      int count=0;
      for(int i=list.size()-1;i>=0;i--){
        int f=list.get(i);Integer uv=baseUv[domain].get(f);
        b.append(String.format(Locale.US,"%.0f MHz",f/1000f));
        if(uv!=null)b.append(String.format(Locale.US,"  |  %.1f mV nominal",uv/1000f));
        b.append("\n"); if(++count>=28){b.append("...");break;}
      }
    }
    frequencyInfo.setText(b.toString().trim());
    String path="/sys/kernel/percent_margin/"+MARGINS[domain];
    status.setText((marginFound[domain]?"Margem registrada: "+domainMargins[domain]+"%":"Controle ACPM indisponível")+
      "\n"+path+"\n"+(trustedKernel?"Driver GPUOC V2 detectado":"Sem identificação GPUOC V2; margem Samsung é independente"));
    if(percentInput!=null&&!percentInput.hasFocus()&&marginFound[domain])
      percentInput.setText(Integer.toString(domainMargins[domain]));
    applyButton.setEnabled(marginFound[domain]&&!busy);
    if(resetButton!=null)resetButton.setEnabled(marginFound[domain]&&initialSet[domain]&&!busy);
    if(actionLog!=null)actionLog.setText(lastAction);
  }
  private void confirmMargin(){
    if(busy)return;
    if(!marginFound[domain]){
      Toast.makeText(this,"Este kernel não oferece controle de margem para este domínio.",Toast.LENGTH_LONG).show();
      return;
    }
    int target;
    try{target=Integer.parseInt(percentInput.getText().toString().trim());}
    catch(Exception e){percentInput.setError("Digite uma porcentagem");return;}
    if(target < -100 || target > 100){
      percentInput.setError("Intervalo do driver Samsung: -100 a +100");
      return;
    }
    final int id=domain;
    new AlertDialog.Builder(this)
      .setTitle("ACPM • Aplicar margem real")
      .setMessage("Domínio: "+TITLES[id]+
        "\nRegistrada: "+domainMargins[id]+"%\nSolicitada: "+target+"%\n\n"+
        "A mudança permanece nesta sessão até ser restaurada ou o firmware a redefinir. "+
        "O readback confirma apenas o registro do kernel, não a tensão física.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("APLICAR",(d,w)->applyMargin(id,target,false))
      .show();
  }
  private void applyMargin(int id,int target,boolean restoring){
    if(busy||!marginFound[id])return;
    busy=true;applyButton.setEnabled(false);resetButton.setEnabled(false);
    String domainTitle=TITLES[id];
    lastAction="Enviando "+target+"% para "+domainTitle+"...";
    actionLog.setText(lastAction);
    executor.execute(()->{
      String node="/sys/kernel/percent_margin/"+MARGINS[id];
      String command="f="+q(node)+"; echo BEFORE=$(cat \"$f\" 2>/dev/null); "+
        "printf '%s\\n' "+target+" > \"$f\"; rc=$?; "+
        "echo WRITE_RC=$rc; echo AFTER=$(cat \"$f\" 2>/dev/null); exit $rc";
      Run ret=run(command,12);
      int after=parseInt(extract(ret.out,"AFTER"),999);
      int writeRc=parseInt(extract(ret.out,"WRITE_RC"),999);
      boolean success=ret.code==0 && writeRc==0 && after==target;
      String text=(success?"✓ REGISTRO ATUALIZADO":"✕ NÃO CONFIRMADO")+" • "+domainTitle+
        "\nSolicitado: "+target+"%"+
        " | retorno: "+ret.code+" / write: "+writeRc+
        "\nRegistro antes: "+extract(ret.out,"BEFORE")+"%"+
        " → depois: "+extract(ret.out,"AFTER")+"%"+
        "\nReadback do driver, não é confirmação elétrica de tensão."+
        (!success?"\nDiagnóstico: "+ret.out:"");
      main.post(()->{
        busy=false;lastAction=text;actionLog.setText(text);
        actionLog.setTextColor(success?GREEN:WARN);
        actionLog.announceForAccessibility(success?"Margem registrada":"Falha ao aplicar margem");
        if(success) {
          getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
          Toast.makeText(this,restoring?"Margem restaurada":"Margem registrada: "+target+"%",Toast.LENGTH_SHORT).show();
        } else {
          getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.REJECT);
          Toast.makeText(this,"Falha ou confirmação indisponível: confira o relatório",Toast.LENGTH_LONG).show();
        }
        percentInput.clearFocus();
        refresh();
      });
    });
  }
  private static String extract(String s,String key){for(String l:s.split("\n"))if(l.startsWith(key+"="))return l.substring(key.length()+1);return "não confirmado";}
}
