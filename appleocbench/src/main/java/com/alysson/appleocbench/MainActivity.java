package com.alysson.appleocbench;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import android.view.View;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
  static final int STOCK=858000;
  static final int[] FREQ={910000,936000,975000,1001000};
  static final String MALI="b3e2815d195e96ce281540a626a4659abaf504932c1b810562a287aa26f24831";
  static final String CMU="a006b9bc9f967b16a6c0f10f1c1434bb4e4c31bc4e8a1ff98c0059f254d88995";
  final Handler ui=new Handler(Looper.getMainLooper());
  final ExecutorService queue=Executors.newSingleThreadExecutor();
  TextView diagnostic, results;
  Button[] all;
  volatile boolean running=false, installed=false;
  volatile boolean vendorVerified=false;
  volatile boolean[] ocAllowed={false,false,false,false};
  Button[] freqButtons;
  Button gpuUnlockButton;
  volatile String capsInfo="";
  volatile String node="";
  double reference=0;
  String report="";
  int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  GradientDrawable shape(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(14));return d;}
  TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
  LinearLayout panel(){
    LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(17),dp(17),dp(17),dp(17));
    l.setBackground(shape(0xff162537));return l;
  }
  void add(LinearLayout outer,android.view.View v,int bottom){
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);outer.addView(v,p);
  }
  Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(shape(0xff254768));
    b.setOnTouchListener((v,e)->{
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.animate().scaleX(.972f).scaleY(.972f).setDuration(90).start();
      else if(e.getActionMasked()==MotionEvent.ACTION_UP){
        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        if(v.isEnabled())v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      }else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)
        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
      return false;
    });return b;}
  void enable(boolean enabled){
    running=!enabled;
    for(Button b:all)b.setEnabled(enabled);
    if(freqButtons!=null)for(Button b:freqButtons)b.setEnabled(enabled);
    if(gpuUnlockButton!=null)gpuUnlockButton.setEnabled(enabled);
  }
  @Override public void onCreate(Bundle b){super.onCreate(b);build();scan();}
  @Override public void onDestroy(){queue.shutdownNow();super.onDestroy();}
  void build(){
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff0b1320);
    scroll.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
      return insets;
    });
    LinearLayout body=new LinearLayout(this);body.setPadding(dp(17),dp(26),dp(17),dp(28));body.setOrientation(1);scroll.addView(body);
    TextView title=text("APPLE FINAL",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);add(body,title,2);
    add(body,text("GPU CLOCK & BENCH LAB  •  SM-G991B",12,0xff73b9ff),16);
    LinearLayout a=panel();add(a,text("DIAGNÓSTICO DO KERNEL",13,0xff73b9ff),12);
    diagnostic=text("Verificando root, módulos e temperatura...",14,Color.WHITE);add(a,diagnostic,2);add(body,a,13);
    LinearLayout z=panel();add(z,text("BENCHMARK REAL • OPENGL ES 3.0",13,0xff73b9ff),8);
    add(z,text("Shader de 1024×1024, com leitura de pixels e amostras do clock durante 2 segundos. Não é FPS de jogo.",12,0xffb4c7d7),11);
    Button b0=button("Benchmark GPU • carga real 2 s");add(z,b0,9);b0.setOnClickListener(v->test(0,false));
    Button b1=button("Repetir benchmark GPU • 2 s");add(z,b1,0);b1.setOnClickListener(v->test(0,true));add(body,z,13);
    LinearLayout o=panel();add(o,text("GPU OC • PULSO OC COORDENADO PELO ACPM",13,0xffffc680),6);
    add(o,text("Só libera clocks presentes simultaneamente na tabela do firmware ACPM e na tabela do driver. Pulso de 100 ms, com retorno ao DVFS e diagnóstico de rejeição.",12,0xffb4c7d7),10);
    Button[] btn=new Button[FREQ.length];
    for(int i=0;i<FREQ.length;i++){final int freq=FREQ[i];
      btn[i]=button(String.format(Locale.US,"%d MHz  •  +%.2f%%",freq/1000,(freq*100.0/STOCK)-100));
      add(o,btn[i],7);btn[i].setOnClickListener(v->test(freq,false));
    }
    add(o,text("Candidatos acima do stock só podem funcionar se o ACPM oferecer um OPP. Ative o modo unlock quando o kernel compatível estiver instalado.",12,0xffffc680),8);
    gpuUnlockButton=button("GPU UNLOCK • consultar / alternar");
    add(o,gpuUnlockButton,0);gpuUnlockButton.setOnClickListener(v->toggleGpuUnlock());
    add(body,o,13);
    LinearLayout r=panel();add(r,text("MEDIÇÕES / RELATÓRIO",13,0xff73b9ff),12);
    results=text("Execute uma referência original antes de comparar.",13,Color.WHITE);results.setTypeface(Typeface.MONOSPACE);add(r,results,12);
    Button volt=button("VOLTAGE LAB • TENSÃO REAL CPU/GPU");add(r,volt,8);volt.setOnClickListener(v->startActivity(new Intent(this,VoltageLabActivity.class)));
    Button refresh=button("Verificar novamente");add(r,refresh,8);refresh.setOnClickListener(v->scan());
    Button share=button("Compartilhar resultados");add(r,share,0);
    share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,report);startActivity(Intent.createChooser(i,"Relatório GPU"));});
    add(body,r,13);
    add(body,text("RISCO: clock acima de 858 MHz sem tensão validada pode congelar ou reiniciar o aparelho. Leitura de frequência reportada pelo driver não comprova estabilidade física. Teste curto não demonstra desempenho sustentado.",12,0xffadc0d0),0);
    freqButtons=btn;
    all=new Button[]{b0,b1,btn[0],btn[1],btn[2],btn[3],refresh,share};
    setContentView(scroll);
  }
  static class Cmd{int code;String out;Cmd(int c,String t){code=c;out=t;}}
  static Cmd shell(String s){return shell(s,13);}
  static Cmd shell(String s,int timeout){
    Process p=null;
    try{
      p=new ProcessBuilder("su","-c",s).redirectErrorStream(true).start();
      if(!p.waitFor(timeout,TimeUnit.SECONDS)){p.destroyForcibly();return new Cmd(-1,"timeout root");}
      BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()));
      StringBuilder b=new StringBuilder();String l;while((l=r.readLine())!=null)if(b.length()<5000)b.append(l).append('\n');
      return new Cmd(p.exitValue(),b.toString().trim());
    }catch(Exception e){return new Cmd(-1,"erro root: "+e.getMessage());}
    finally{if(p!=null)p.destroy();}
  }
  static String get(String s){Cmd x=shell(s);return x.code==0?x.out:"";}
  static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
  static int gpuTemp(){
    String s=get("for f in /sys/class/thermal/thermal_zone*/type; do t=$(cat \"$f\" 2>/dev/null); "+
      "case \"$t\" in *G3D*|*g3d*|*GPU*|*gpu*|*mali*|*MALI*) "+
      "cat \"$(dirname \"$f\")/temp\" 2>/dev/null; exit 0;; esac; done");
    try{int x=Integer.parseInt(s.split("\n")[0].trim());return x<150?x*1000:x;}catch(Exception e){return -1;}
  }
  static String findClock(){
    String s=get("for f in /sys/devices/platform/*mali*/clock /sys/devices/platform/*mali*/*/clock "+
      "/sys/devices/platform/*gpu*/clock /sys/devices/platform/18500000.mali/clock; do "+
      "[ -f \"$f\" ] && [ -w \"$f\" ] && { echo \"$f\"; exit 0; }; done; "+
      "find /sys/devices/platform -maxdepth 6 -name clock 2>/dev/null | while read f; do "+
      "case \"$f\" in *mali*|*gpu*|*g3d*) [ -w \"$f\" ] && { echo \"$f\"; break; };; esac; done");
    if(s.contains("\n"))s=s.split("\n")[0];return s.startsWith("/sys/")&&s.endsWith("/clock")?s:"";
  }
  static String moduleHash(String n){
    return get("for f in /vendor/lib/modules/"+n+" /lib/modules/"+n+" /vendor_dlkm/lib/modules/"+n+"; do "+
      "if [ -f \"$f\" ]; then sha256sum \"$f\" | cut -d' ' -f1; exit 0; fi; done; "+
      "find /vendor /lib/modules /vendor_dlkm -name "+n+" -type f 2>/dev/null | head -n 1 | "+
      "while read f; do sha256sum \"$f\" | cut -d' ' -f1; done");
  }
  static String MHz(String s){try{return String.format(Locale.US,"%.1f MHz",Long.parseLong(s.trim())/1000.0);}catch(Exception e){return s.isEmpty()?"—":s;}}
  static String cel(int x){return x<0?"não detectada":String.format(Locale.US,"%.1f°C",x/1000.0);}
  static String capsPath(){
    String path=get("for f in /sys/devices/platform/*mali*/apple_gpuoc_caps "+
       "/sys/devices/platform/*gpu*/apple_gpuoc_caps "+
       "/sys/devices/platform/18500000.mali/apple_gpuoc_caps; do "+
       "[ -f \"$f\" ] && { echo \"$f\"; exit 0; }; done; "+
       "find /sys/devices/platform -maxdepth 6 -name apple_gpuoc_caps 2>/dev/null | head -n 1");
    return path.startsWith("/sys/")?path:"";
  }
  static String unlockPath(){
    String path=get("for f in /sys/kernel/gpu/gpu_unlock /sys/devices/platform/*mali*/apple_gpu_unlock "+
      "/sys/devices/platform/*gpu*/apple_gpu_unlock "+
      "/sys/devices/platform/18500000.mali/apple_gpu_unlock; do "+
      "[ -f \"$f\" ] && { echo \"$f\"; exit 0; }; done; "+
      "find /sys/devices/platform -maxdepth 6 -name apple_gpu_unlock 2>/dev/null | head -n 1");
    return path.startsWith("/sys/")?path:"";
  }
  void toggleGpuUnlock(){
    if(running)return;
    enable(false);
    results.setText("Lendo o seletor Floppy-style de limite GPU...");
    queue.execute(()->{
      String path=unlockPath();
      if(path.isEmpty()){
        String reason="Nem GPU Unlock Floppy nem Apple GPU Unlock encontrados.\n"+
          "A consulta procura /sys/kernel/gpu/gpu_unlock e a interface antiga Apple.\n"+
          "O app não altera clocks por conta própria.";
        ui.post(()->{results.setText(reason);report=reason;enable(true);});
        return;
      }
      String before=get("cat "+q(path));
      int target="1".equals(before.trim())?0:1;
      Cmd x=shell("p="+q(path)+"; echo BEFORE=$(cat \"$p\"); "+
        "printf '%s\\n' "+target+" > \"$p\"; ret=$?; "+
        "echo WRITE_RC=$ret; echo AFTER=$(cat \"$p\"); exit $ret");
      String after=field(x.out,"AFTER");
      boolean success=x.code==0&&after.equals(Integer.toString(target));
      String text="GPU UNLOCK • "+(success?"registro atualizado":"não confirmado")+
        "\nAnterior: "+before+" | pedido: "+target+" | atual: "+after+
        "\nResposta do driver: "+x.code+
        "\nModo "+(target==1?"limite desbloqueado":"limite stock")+
        "\nO clock efetivo depende do governador e das tabelas CAL/ASV/DT.";
      ui.post(()->{
        results.setText(text);report=text;enable(true);
        getWindow().getDecorView().performHapticFeedback(
          success?android.view.HapticFeedbackConstants.CONFIRM:android.view.HapticFeedbackConstants.REJECT);
        scan();
      });
    });
  }
  void scan(){
    if(running)return;enable(false);diagnostic.setText("Lendo root, firmware ACPM e clocks...");
    queue.execute(()->{
      Cmd id=shell("id");String model=get("getprop ro.product.model"),k=get("uname -r");
      String gpu=findClock(),path=capsPath(),caps=path.isEmpty()?"":get("cat "+q(path));
      String unlock=unlockPath();String unlockVal=unlock.isEmpty()?"não disponível":get("cat "+q(unlock));
      boolean floppy=unlock.equals("/sys/kernel/gpu/gpu_unlock");
      String freqTable=floppy?get("cat /sys/kernel/gpu/gpu_freq_table"):"";
      boolean rootOk=id.code==0&&id.out.contains("uid=0");
      boolean v2=caps.contains("mode=ACPM_ONLY")&&caps.contains("direct_pll_writes=DISABLED");
      boolean modelOk="SM-G991B".equals(model)&&(floppy||k.contains("G991BXXSJHZA6"));
      String[] flags={"fw_valid_910","fw_valid_936","fw_valid_975","fw_valid_1001"};
      boolean[] accepted={false,false,false,false};int count=0;
      for(int i=0;i<flags.length;i++){
        boolean nativeOpp=false;
        if(floppy)for(String f:freqTable.trim().split("\\s+"))if(f.equals(Integer.toString(FREQ[i])))nativeOpp=true;
        accepted[i]=rootOk&&modelOk&&!gpu.isEmpty()&&
          (floppy?nativeOpp:(v2&&caps.contains(flags[i]+"=1")));
        if(accepted[i])count++;
      }
      ocAllowed=accepted;installed=count>0;node=gpu;
      capsInfo=floppy?"FloppyKernel (tabela real):\n"+freqTable:caps;
      int t=gpuTemp();
      String diag="Root: "+(rootOk?"OK":"não autorizado")+
        "\nAparelho: "+model+"\nKernel: "+k+
        "\nModo firmware: "+(floppy?"FloppyKernel nativo":v2?"Apple ACPM V2":"não reconhecido")+
        "\nClock atual: "+(gpu.isEmpty()?"—":MHz(get("cat "+q(gpu))))+
        "\nGPU: "+cel(t)+
        "\nFirmware/DT aceitam OC: "+count+" de 4"+
        "\nGPU unlock: "+unlockVal+" | interface: "+(unlock.isEmpty()?"ausente":unlock)+"\n"+
        (floppy?"Tabela GPU Floppy:\n"+freqTable:
          caps.isEmpty()?"Sem interface apple_gpuoc_caps.":caps);
      ui.post(()->{diagnostic.setText(diag);enable(true);});
    });
  }
  static String field(String out,String name){
    for(String l:out.split("\n"))if(l.startsWith(name+"="))return l.substring(name.length()+1);
    return "não confirmado";
  }
  void test(int requested,boolean longRun){
    if(running)return;
    if(requested>0){int idx=-1;for(int i=0;i<FREQ.length;i++)if(FREQ[i]==requested)idx=i;
      if(idx<0||!installed||!ocAllowed[idx]){
        String reason="SOLICITAÇÃO: "+requested/1000+" MHz"+
          "\nResultado: frequência não registrada no firmware/DT atual"+
          "\nDriver: "+(capsInfo.contains("mode=ACPM_ONLY")?"ACPM V2":"interface V2 não detectada")+
          "\n"+(capsInfo.isEmpty()?"Interface de capacidades ausente":capsInfo)+
          "\nO firmware não oferece essa tensão/frequência.";
        results.setText(reason);report=reason;
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.REJECT);
        Toast.makeText(this,"Firmware não possui OPP para "+requested/1000+" MHz",Toast.LENGTH_LONG).show();return;
      }
    }
    if(requested>0){int t=gpuTemp();if(t<0||t>=65000){
      Toast.makeText(this,"OC bloqueado: temperatura GPU ≥65°C ou sensor ausente.",Toast.LENGTH_LONG).show();return;
    }}
    enable(false);results.setText("Executando shader e medição...");
    queue.execute(()->{
      int startT=gpuTemp();long ms=2000;
      CountDownLatch ready=new CountDownLatch(1);AtomicBoolean stop=new AtomicBoolean(false);
      final GpuBench.Result[] out=new GpuBench.Result[1];
      Thread render=new Thread(()->{try{out[0]=GpuBench.run(ms,ready,stop);}
        catch(Throwable e){out[0]=new GpuBench.Result(0,e.getMessage());ready.countDown();}},"ES3-Renderer");
      render.start();
      String raw="";Cmd pulse=new Cmd(0,"");
      try{
        if(!ready.await(8,TimeUnit.SECONDS))throw new Exception("OpenGL ES não iniciou");
        if(requested==0 && !node.isEmpty()){
          String sample="f="+q(node)+"; i=0; printf 'CLOCKS='; "+
            "while [ $i -lt 15 ]; do "+
            "printf '%s,' \"$(cat \"$f\" 2>/dev/null)\"; "+
            "i=$((i+1)); sleep 0.12; done; echo"; 
          raw=shell(sample,8).out;
        }
        if(requested>0){
          String x="( printf '%s\\n' "+requested+" > "+q(node)+" ) & p=$!; "+
            "sleep 0.03; echo PICO=$(cat "+q(node)+" 2>/dev/null); "+
            "wait $p; echo APOS=$(cat "+q(node)+" 2>/dev/null); "+
            "printf '0\\n' > "+q(node)+" 2>/dev/null || true";
          pulse=shell(x);raw=pulse.out;
        }
        render.join(9000);
        if(render.isAlive()){stop.set(true);render.join(1000);}
      }catch(Exception e){stop.set(true);raw+="\nErro: "+e.getMessage();}
      if(requested>0&&!node.isEmpty())shell("printf '0\\n' > "+q(node)+" 2>/dev/null || true");
      int endT=gpuTemp();
      GpuBench.Result score=out[0];double d=score==null?0:score.drawsPerSecond;
      if(requested==0&&!longRun&&d>0)reference=d;
      String s="Modo: "+(requested==0?"ORIGINAL":requested/1000+" MHz OC")+
        "\nBenchmark: 2,0 s / GPU OpenGL ES 3\n"+
        String.format(Locale.US,"Desenhos/s: %,.1f",d)+"\nShader: "+(score==null?"não finalizado":score.status)+
        "\nTemperatura: "+cel(startT)+" → "+cel(endT);
      if(requested==0 && !raw.isEmpty()){
        String v=field(raw,"CLOCKS");
        int samples=0;long sum=0,max=0,min=Long.MAX_VALUE;
        for(String item:v.split(",")){
          try{long hz=Long.parseLong(item.trim());if(hz>0){
            samples++;sum+=hz;max=Math.max(max,hz);min=Math.min(min,hz);
          }}catch(Exception ignored){}
        }
        s+="\nGPU sob carga: "+samples+" amostras / 2 s"+
           "\nClock médio: "+(samples>0?MHz(Long.toString(sum/samples)):"indisponível")+
           " | máximo: "+(samples>0?MHz(Long.toString(max)):"indisponível")+
           "\nClock mínimo ativo: "+(samples>0?MHz(Long.toString(min)):"indisponível")+
           "\nLeituras: "+v;

      }
      if(requested>0){
        s+="\nRetorno de comando: "+(pulse.code==0?"OK":"erro "+pulse.code)+
          "\nLeitura durante pulso: "+MHz(field(raw,"PICO"))+
          "\nLeitura após pulso: "+MHz(field(raw,"APOS"));
        if(reference>0&&d>0)s+=String.format(Locale.US,"\nDiferença vs original curto: %+.2f%%",100*(d/reference-1));
        s+="\nAviso: pulso curto em benchmark 2 s; não representa estabilidade sustentada.";
      }
      if(endT>=75000)s+="\nALERTA: GPU >=75°C; não repetir OC.";
      String cur=node.isEmpty()?"—":MHz(get("cat "+q(node)));
      s+="\nClock final: "+cur+"\nExperimento, não é teste de estabilidade.";
      String finalS=s;ui.post(()->{results.setText(finalS);report=finalS;enable(true);
        diagnostic.append("\nÚltimo teste: "+(requested==0?"stock":requested/1000+" MHz")+" | GPU "+cel(endT));});
    });
  }
}
