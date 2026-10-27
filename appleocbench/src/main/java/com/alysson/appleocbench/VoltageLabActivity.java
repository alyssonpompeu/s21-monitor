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
  private TextView metrics, status, summary, value, exptxt, editTitle;
  private SeekBar mvSeek, exponentSeek;
  private Spinner domainSpinner, freqSpinner;
  private RadioGroup modes;
  private Curve curveView;
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
  private Button button(String name){Button b=new Button(this);b.setText(name);b.setAllCaps(false);
    b.setTextColor(WHITE);b.setBackground(rect(0xff24506a,11));return b;
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);
    for(int i=0;i<4;i++){clocks[i]=new ArrayList<>();edits[i]=new HashMap<>();baseUv[i]=new HashMap<>();}
    createUI();refresh();
  }
  @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
  private void createUI(){
    ScrollView sc=new ScrollView(this);sc.setBackgroundColor(BG);sc.setFillViewport(true);
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

    LinearLayout chart=panel();
    item(chart,text("CURVA DE FREQUÊNCIA × TENSÃO",12,CYAN,true),8);
    curveView=new Curve(this);item(chart,curveView,8);
    summary=text("Escolha um domínio para visualizar.",13,SUB,false);item(chart,summary,2);
    item(body,chart,12);

    LinearLayout edit=panel();
    item(edit,text("EDITOR DE PERFIL",12,CYAN,true),8);
    modes=new RadioGroup(this);modes.setOrientation(RadioGroup.HORIZONTAL);
    RadioButton per=new RadioButton(this);per.setText("Por clock");per.setTextColor(WHITE);per.setId(777);
    RadioButton exp=new RadioButton(this);exp.setText("Exponencial");exp.setTextColor(WHITE);exp.setId(778);
    modes.addView(per);modes.addView(exp);modes.check(777);
    item(edit,modes,12);
    modes.setOnCheckedChangeListener((g,id)->{mode=id==777?0:1;updateEditControls();});
    singleControls=new LinearLayout(this);singleControls.setOrientation(LinearLayout.VERTICAL);
    editTitle=text("Ponto de operação",12,SUB,false);item(singleControls,editTitle,6);
    freqSpinner=new Spinner(this);item(singleControls,freqSpinner,8);
    freqSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int idx,long id){selectedIdx=idx;updateEditControls();}
      @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
    });
    item(edit,singleControls,4);

    curveControls=new LinearLayout(this);curveControls.setOrientation(LinearLayout.VERTICAL);
    item(curveControls,text("Expoente da curva (1,0 a 3,0)",12,SUB,false),4);
    exponentSeek=new SeekBar(this);exponentSeek.setMax(20);exponentSeek.setProgress(10);item(curveControls,exponentSeek,2);
    exptxt=text("Expoente 2,0 • variação cresce nos clocks altos",12,SUB,false);item(curveControls,exptxt,4);
    exponentSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      @Override public void onProgressChanged(SeekBar s,int p,boolean user){expo10=10+p;exptxt.setText(String.format(Locale.US,"Expoente %.1f • variação concentrada nos clocks altos",expo10/10f));curveView.invalidate();updateSummary();}
      @Override public void onStartTrackingTouch(SeekBar s){}
      @Override public void onStopTrackingTouch(SeekBar s){}
    });
    item(edit,curveControls,4);
    value=text("Offset -15 mV • apenas simulação",15,GREEN,true);item(edit,value,6);
    mvSeek=new SeekBar(this);mvSeek.setMax(20);mvSeek.setProgress(7);item(edit,mvSeek,12);
    mvSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      @Override public void onProgressChanged(SeekBar sb,int progress,boolean user){
        int mv=(progress-10)*5;
        if(mode==0 && !clocks[domain].isEmpty()){
          int idx=Math.min(selectedIdx,clocks[domain].size()-1);
          edits[domain].put(clocks[domain].get(idx),mv);
        }else if(mode==1){curveEndMv=mv;}
        updateSummary();curveView.invalidate();
      }
      @Override public void onStartTrackingTouch(SeekBar sb){}
      @Override public void onStopTrackingTouch(SeekBar sb){}
    });
    item(edit,text("Por clock e curva exponencial são modelos de edição. A interface Samsung disponível não permite escrever valores individuais por OPP. Os offsets acima NÃO são aplicados ao chip.",12,WARN,false),2);
    item(body,edit,12);

    LinearLayout real=panel();
    item(real,text("CONTROLE REAL • MARGEM ACPM",12,CYAN,true),7);
    status=text("Verificando margem real...",13,WHITE,false);item(real,status,9);
    item(real,text("A Samsung expõe uma margem percentual por domínio. É diferente de escolher milivolts em um clock. O valor lido não é uma medição independente do regulador.",12,SUB,false),12);
    applyButton=button("Testar margem real +1% por 3 segundos");
    applyButton.setEnabled(false);item(real,applyButton,7);
    applyButton.setOnClickListener(v->confirmMargin());
    Button reload=button("Atualizar leitura real");item(real,reload,0);reload.setOnClickListener(v->refresh());
    item(body,real,12);
    item(body,text("Limites de engenharia: sem aplicação automática no boot; nenhuma alteração acima de 1% nesta fase; sem tensão arbitrária; sem alteração de proteção térmica. Recuperação automática solicitada após 3 segundos, mas não é garantida se o kernel travar.",12,WARN,false),0);
    setContentView(sc);
    updateEditControls();
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
    if(metrics==null)return;
    ArrayList<Integer> list=clocks[domain];
    metrics.setText("Frequências detectadas: "+list.size()+
        (domain==0?"\nTabela ASV GPU: "+baseUv[0].size()+" pontos de tensão nominal":"\nTensão por clock de CPU: não exposta nesta interface")+
        "\nMargem registrada: "+(marginFound[domain]?domainMargins[domain]+"%":"não disponível"));
    String[] labels=new String[Math.max(list.size(),1)];
    if(list.isEmpty())labels[0]="Nenhuma frequência disponível";
    for(int i=0;i<list.size();i++){
      int f=list.get(i);Integer uv=baseUv[domain].get(f);
      labels[i]=String.format(Locale.US,"%.0f MHz%s",f/1000f,uv==null?"":String.format(Locale.US," • %.1f mV",uv/1000f));
    }
    freqSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
    selectedIdx=Math.min(selectedIdx,labels.length-1);freqSpinner.setSelection(selectedIdx);
    updateEditControls();updateSummary();
    String path="/sys/kernel/percent_margin/"+MARGINS[domain];
    status.setText((marginFound[domain]?"Margem atualmente registrada: "+domainMargins[domain]+"%":"Controle de margem não encontrado")+
      "\n"+path+"\n"+
      (trustedKernel?"Driver V2 ACPM identificado":"Driver V2 não confirmado. Teste real desabilitado."));
    applyButton.setEnabled(marginFound[domain]&&trustedKernel&&!busy&&domainMargins[domain]==0);
    curveView.invalidate();
  }
  private void updateEditControls(){
    if(curveControls==null)return;
    singleControls.setVisibility(mode==0?View.VISIBLE:View.GONE);
    curveControls.setVisibility(mode==1?View.VISIBLE:View.GONE);
    int current=curveEndMv;
    if(mode==0&&!clocks[domain].isEmpty()){
      int f=clocks[domain].get(Math.min(selectedIdx,clocks[domain].size()-1));
      current=edits[domain].containsKey(f)?edits[domain].get(f):0;
    }
    int progress=(current/5)+10;
    if(mvSeek!=null)mvSeek.setProgress(Math.max(0,Math.min(20,progress)));
    updateSummary();
  }
  private int offset(int i,int freq){
    if(mode==0)return edits[i].containsKey(freq)?edits[i].get(freq):0;
    if(clocks[i].isEmpty())return 0;
    int max=clocks[i].get(clocks[i].size()-1);
    double ratio=(double)freq/Math.max(1,max);
    return (int)Math.round(curveEndMv*Math.pow(ratio,expo10/10.0));
  }
  private void updateSummary(){
    if(summary==null)return;
    int idx=domain;
    int n=clocks[idx].size(); if(n==0){summary.setText("Sem dados suficientes para desenhar uma curva real.");return;}
    int min=clocks[idx].get(0),max=clocks[idx].get(n-1);
    summary.setText(String.format(Locale.US,"%d pontos • %.0f–%.0f MHz\n",n,min/1000.0,max/1000.0)+
      (mode==0?"Offsets manuais por frequência":"Modelo exponencial: potência "+String.format(Locale.US,"%.1f",expo10/10.0))+
      " • alterações apenas na prévia");
    int f=clocks[idx].get(Math.min(selectedIdx,n-1));
    int o=mode==0?offset(idx,f):curveEndMv;
    if(value!=null)value.setText((o>0?"+":"")+o+" mV • SIMULAÇÃO, NÃO APLICADO");
  }
  private void confirmMargin(){
    if(!trustedKernel||!marginFound[domain]||domainMargins[domain]!=0||busy)return;
    final int id=domain;
    new AlertDialog.Builder(this)
      .setTitle("Teste REAL de margem ACPM")
      .setMessage("Solicitar +1% na tensão do domínio "+TITLES[id]+" durante 3 segundos e depois solicitar 0%. "+
          "Isto é uma solicitação ao firmware, NÃO uma leitura do regulador. Pode reiniciar o aparelho. "+
          "Sem alteração ao iniciar. Deseja executar?")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Testar 3 s",(dialog,which)->applyMargin(id))
      .show();
  }
  private void applyMargin(int id){
    if(busy)return;busy=true;applyButton.setEnabled(false);
    status.setText("Solicitando +1% no ACPM; restauração programada após 3 segundos...");
    executor.execute(()->{
      String node="/sys/kernel/percent_margin/"+MARGINS[id];
      String shell="f="+q(node)+"; before=$(cat \"$f\" 2>/dev/null) || exit 12; "+
        "[ \"$before\" = 0 ] || exit 13; "+
        "printf '1\\n' > \"$f\" || exit 14; "+
        "echo DURANTE=$(cat \"$f\" 2>/dev/null); "+
        "sleep 3; printf '0\\n' > \"$f\"; ret=$?; "+
        "echo RESTORE_RC=$ret; echo APOS=$(cat \"$f\" 2>/dev/null); exit $ret";
      Run x=run(shell,9);
      int recorded=parseInt(extract(x.out,"APOS"),999);
      main.post(()->{
        busy=false;
        status.setText("Teste finalizado. Comando: "+(x.code==0?"retornou":"falhou")+
          "\nDurante: "+extract(x.out,"DURANTE")+"% (registro no driver)"+
          "\nDepois: "+extract(x.out,"APOS")+"%"+
          "\nATENÇÃO: isso não mede tensão física."+
          (recorded!=0?"\nNÃO FOI POSSÍVEL CONFIRMAR RESTAURAÇÃO. Reinicie sem novos testes.":""));
        refresh();
      });
    });
  }
  private static String extract(String s,String key){for(String l:s.split("\n"))if(l.startsWith(key+"="))return l.substring(key.length()+1);return "não confirmado";}
  private final class Curve extends View {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    Curve(Activity c){super(c);setMinimumHeight(dp(190));}
    @Override protected void onMeasure(int w,int h){
      super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(dp(190),View.MeasureSpec.EXACTLY));
    }
    @Override protected void onDraw(Canvas c){
      super.onDraw(c);float w=getWidth(),h=getHeight(),left=dp(27),right=w-dp(12),top=dp(16),bottom=h-dp(25);
      p.setStrokeWidth(dp(1));p.setColor(0xff314155);
      for(int i=0;i<5;i++){float y=top+(bottom-top)*i/4f;c.drawLine(left,y,right,y,p);}
      p.setColor(SUB);p.setTextSize(dp(10));p.setStyle(Paint.Style.FILL);
      c.drawText("mV",dp(4),top+dp(6),p);
      c.drawText("MHz",right-dp(21),h-dp(7),p);
      ArrayList<Integer> list=clocks[domain];
      if(list.size()<2)return;
      float[] xs=new float[list.size()],ys=new float[list.size()];
      double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
      double[] vals=new double[list.size()];
      boolean uv=baseUv[domain].size()==list.size();
      for(int i=0;i<list.size();i++){
        int f=list.get(i);double base=uv?baseUv[domain].get(f)/1000.0:0;
        vals[i]=uv?base+offset(domain,f):offset(domain,f);
        min=Math.min(min,vals[i]);max=Math.max(max,vals[i]);
      }
      if(max-min<20){max+=10;min-=10;}
      int lo=list.get(0),hi=list.get(list.size()-1);
      for(int i=0;i<list.size();i++){
        xs[i]=left+(list.get(i)-lo)/(float)(hi-lo)*(right-left);
        ys[i]=bottom-(float)((vals[i]-min)/(max-min))*(bottom-top);
      }
      Path path=new Path();path.moveTo(xs[0],ys[0]);
      for(int i=1;i<list.size();i++)path.lineTo(xs[i],ys[i]);
      p.setColor(CYAN);p.setStrokeWidth(dp(2.5f));p.setStyle(Paint.Style.STROKE);c.drawPath(path,p);
      p.setStyle(Paint.Style.FILL);
      int jump=Math.max(1,list.size()/14);
      for(int i=0;i<list.size();i+=jump)c.drawCircle(xs[i],ys[i],dp(2.6f),p);
      p.setColor(SUB);p.setTextSize(dp(10));
      c.drawText(String.format(Locale.US,"%.0f",min),dp(2),bottom,p);
      c.drawText(String.format(Locale.US,"%.0f",max),dp(2),top+dp(12),p);
    }
  }
}
