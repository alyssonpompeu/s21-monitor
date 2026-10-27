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
  Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(shape(0xff254768));return b;}
  void enable(boolean enabled){running=!enabled;for(int i=0;i<all.length;i++)all[i].setEnabled(enabled && !(i>=2 && i<=5));}
  @Override public void onCreate(Bundle b){super.onCreate(b);build();scan();}
  @Override public void onDestroy(){queue.shutdownNow();super.onDestroy();}
  void build(){
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff0b1320);
    LinearLayout body=new LinearLayout(this);body.setPadding(dp(17),dp(26),dp(17),dp(28));body.setOrientation(1);scroll.addView(body);
    TextView title=text("APPLE FINAL",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);add(body,title,2);
    add(body,text("GPU CLOCK & BENCH LAB  •  SM-G991B",12,0xff73b9ff),16);
    LinearLayout a=panel();add(a,text("DIAGNÓSTICO DO KERNEL",13,0xff73b9ff),12);
    diagnostic=text("Verificando root, módulos e temperatura...",14,Color.WHITE);add(a,diagnostic,2);add(body,a,13);
    LinearLayout z=panel();add(z,text("BENCHMARK REAL • OPENGL ES 3.0",13,0xff73b9ff),8);
    add(z,text("Shader em GPU, resolução 512×512. Resultado em desenhos/segundo; não é FPS de jogo.",12,0xffb4c7d7),11);
    Button b0=button("Referência original • 1,6 s");add(z,b0,9);b0.setOnClickListener(v->test(0,false));
    Button b1=button("Benchmark original • 5 s");add(z,b1,0);b1.setOnClickListener(v->test(0,true));add(body,z,13);
    LinearLayout o=panel();add(o,text("GPU OC DESATIVADO • REINÍCIOS DETECTADOS",13,0xffffc680),6);
    add(o,text("Os quatro testes acima de 858 MHz causaram congelamento e reinício. O acesso a estes botões está desabilitado até corrigirmos a transição PLL/ACPM.",12,0xffb4c7d7),10);
    Button[] btn=new Button[FREQ.length];
    for(int i=0;i<FREQ.length;i++){final int freq=FREQ[i];
      btn[i]=button(String.format(Locale.US,"%d MHz  •  +%.2f%%",freq/1000,(freq*100.0/STOCK)-100));
      add(o,btn[i],7);btn[i].setOnClickListener(v->test(freq,false));
    }
    add(o,text("Sem sensor de temperatura GPU, com ≥65°C, ou sem identificação dos drivers GPUOC: OC BLOQUEADO. Não altera tensões nem aplica ajustes no boot.",12,0xffffc680),1);add(body,o,13);
    LinearLayout r=panel();add(r,text("MEDIÇÕES / RELATÓRIO",13,0xff73b9ff),12);
    results=text("Execute uma referência original antes de comparar.",13,Color.WHITE);results.setTypeface(Typeface.MONOSPACE);add(r,results,12);
    Button refresh=button("Verificar novamente");add(r,refresh,8);refresh.setOnClickListener(v->scan());
    Button verify=button("Verificar módulos no vendor_boot (somente leitura)");add(r,verify,8);
    verify.setOnClickListener(v->verifyVendor());
    Button crash=button("COLETAR REGISTROS DO REINÍCIO (somente leitura)");add(r,crash,8);crash.setOnClickListener(v->collectCrash());
    Button share=button("Compartilhar resultados");add(r,share,0);
    share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,report);startActivity(Intent.createChooser(i,"Relatório GPU"));});
    add(body,r,13);
    add(body,text("RISCO: clock acima de 858 MHz sem tensão validada pode congelar ou reiniciar o aparelho. Leitura de frequência reportada pelo driver não comprova estabilidade física. Teste curto não demonstra desempenho sustentado.",12,0xffadc0d0),0);
    all=new Button[]{b0,b1,btn[0],btn[1],btn[2],btn[3],refresh,verify,crash,share};
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
  void scan(){
    if(running)return;enable(false);diagnostic.setText("Lendo root, kernel e checksums...");
    queue.execute(()->{
      Cmd id=shell("id");String model=get("getprop ro.product.model");String k=get("uname -r");
      String gpu=findClock();String m=moduleHash("mali_kbase.ko"),c=moduleHash("cmupmucal.ko");
      int t=gpuTemp();boolean modulesFound=MALI.equals(m)&&CMU.equals(c);
      boolean ok=id.code==0&&id.out.contains("uid=0")&&
        "SM-G991B".equals(model)&&k.contains("G991BXXSJHZA6")&&
        (modulesFound||vendorVerified)&&!gpu.isEmpty();
      installed=ok;node=gpu;
      String cur=gpu.isEmpty()?"—":MHz(get("cat "+q(gpu)));
      String out="Root: "+(id.out.contains("uid=0")?"OK":"não autorizado")+"\n"+
        "Aparelho: "+model+"\nKernel: "+k+"\nMali GPUOC: "+(MALI.equals(m)?"OK":"não visível no Android")+
        "\nCMUPMUCAL: "+(CMU.equals(c)?"OK":"não visível no Android")+
        "\nvendor_boot: "+(vendorVerified?"hashes de módulos confirmados":"não inspecionado")+
        "\nSysfs: "+(gpu.isEmpty()?"não encontrado":gpu)+
        "\nClock atual: "+cur+"\nTemperatura GPU: "+cel(t)+
        "\nPulso OC: "+(ok?"LIBERADO (EXPERIMENTAL)":"BLOQUEADO");
      ui.post(()->{diagnostic.setText(out);enable(true);});
    });
  }
  // Checks hashes INSIDE vendor_boot on the actual installed flash, never writes to it.
  // Magiskboot unpack is performed in /data/local/tmp and removed after verification.
  void verifyVendor(){
    if(running)return;
    enable(false);
    results.setText("Lendo vendor_boot sem alterar nenhuma partição. Verificando os dois .ko...");
    queue.execute(()->{
      String script="mb=/data/adb/magisk/magiskboot; "+
          "[ -x \"$mb\" ] || mb=$(command -v magiskboot 2>/dev/null); "+
          "[ -n \"$mb\" ] && [ -x \"$mb\" ] || { echo 'ERRO=magiskboot indisponível'; exit 12; }; "+
          "b=/dev/block/by-name/vendor_boot; "+
          "[ -b \"$b\" ] || b=/dev/block/bootdevice/by-name/vendor_boot; "+
          "[ -b \"$b\" ] || { echo 'ERRO=partição vendor_boot não encontrada'; exit 13; }; "+
          "w=/data/local/tmp/apple_gpu_verify_$; mkdir -p \"$w\" || exit 14; "+
          "trap 'rm -rf \"$w\"' EXIT; cd \"$w\" || exit 15; "+
          "dd if=\"$b\" of=\"$w/vendor_boot.img\" bs=1048576 2>/dev/null || exit 16; "+
          "\"$mb\" unpack -h \"$w/vendor_boot.img\" >/dev/null 2>&1; "+
          "r=''; for f in ramdisk.cpio vendor_ramdisk/ramdisk.cpio vendor_ramdisk/init_boot.cpio; do "+
          "[ -f \"$f\" ] && { r=\"$w/$f\"; break; }; done; "+
          "[ -n \"$r\" ] || { echo 'ERRO=ramdisk não localizado'; exit 17; }; "+
          "mkdir -p \"$w/modules\"; cd \"$w/modules\" || exit 18; "+
          "\"$mb\" cpio \"$r\" extract >/dev/null 2>&1 || { echo 'ERRO=extração falhou'; exit 19; }; "+
          "a=$(find . -name mali_kbase.ko -type f | head -n 1); "+
          "c=$(find . -name cmupmucal.ko -type f | head -n 1); "+
          "[ -n \"$a\" ] && [ -n \"$c\" ] || { echo 'ERRO=módulos não encontrados'; exit 20; }; "+
          "echo MALI=$(sha256sum \"$a\" | cut -d' ' -f1); "+
          "echo CMU=$(sha256sum \"$c\" | cut -d' ' -f1)";
      Cmd x=shell(script,75);
      boolean matches=x.code==0&&MALI.equals(field(x.out,"MALI"))&&CMU.equals(field(x.out,"CMU"));
      vendorVerified=matches;
      String msg="Inspeção somente leitura de vendor_boot:\n"+
          "Processo: "+(x.code==0?"completo":"erro "+x.code)+"\n"+
          "Mali: "+(MALI.equals(field(x.out,"MALI"))?"SHA exato":"não confirmado")+"\n"+
          "CMUPMUCAL: "+(CMU.equals(field(x.out,"CMU"))?"SHA exato":"não confirmado")+"\n"+
          "OC: "+(matches?"módulos confirmados; confira sysfs e temperatura":"bloqueado")+"\n"+
          (x.code!=0?"Detalhes: "+x.out:"");
      ui.post(()->{
        results.setText(msg);report=msg;enable(true);scan();
      });
    });
  }

  void collectCrash(){
    if(running)return;
    enable(false);results.setText("Coletando registros persistentes do kernel... Não modifica arquivos.");
    queue.execute(()->{
      String script="echo '=== CAUSA DE REINICIO ==='; "+
          "echo sys.boot.reason=$(getprop sys.boot.reason); "+
          "echo ro.boot.bootreason=$(getprop ro.boot.bootreason); "+
          "echo kernel=$(uname -r); "+
          "echo '=== PERSISTENT CRASH LOGS ==='; count=0; "+
          "for f in /sys/fs/pstore/* /proc/last_kmsg; do "+
          "[ -f \"$f\" ] || continue; "+
          "echo FILE=$f; count=$((count+1)); "+
          "tail -c 524288 \"$f\" 2>/dev/null | "+
          "grep -iE 'mali|g3d|pll|acpm|dvfs|smmu|fault|watchdog|panic|serror|timeout|gpu|reset' "+
          "| tail -n 18; done; "+
          "[ \"$count\" -gt 0 ] || echo 'Nao ha pstore/last_kmsg disponivel'; "+
          "echo '=== KERNEL LOG ATUAL (apenas diagnostico) ==='; "+
          "dmesg 2>/dev/null | grep -iE 'mali|g3d|pll|acpm|dvfs|smmu|fault|watchdog|panic' | tail -n 15";
      Cmd c=shell(script,30);
      String out="GPUOC: relatorio somente leitura\n"
          +"Resultado da coleta: "+(c.code==0?"OK":"codigo "+c.code)+"\n\n"
          +c.out+"\n\nAVISO: ausencia de pstore nao descarta panic ou watchdog.";
      ui.post(()->{results.setText(out);report=out;enable(true);});
    });
  }
  static String field(String out,String name){
    for(String l:out.split("\n"))if(l.startsWith(name+"="))return l.substring(name.length()+1);
    return "não confirmado";
  }
  void test(int requested,boolean longRun){
    if(running)return;
    if(requested>0){Toast.makeText(this,"GPU OC DESABILITADO: o aparelho reiniciou com todos os clocks experimentais.",Toast.LENGTH_LONG).show();return;}
    if(requested>0&&!installed){Toast.makeText(this,"OC bloqueado: drivers GPUOC ou root não identificados.",Toast.LENGTH_LONG).show();return;}
    if(requested>0){int t=gpuTemp();if(t<0||t>=65000){
      Toast.makeText(this,"OC bloqueado: temperatura GPU ≥65°C ou sensor ausente.",Toast.LENGTH_LONG).show();return;
    }}
    enable(false);results.setText("Executando shader e medição...");
    queue.execute(()->{
      int startT=gpuTemp();long ms=longRun?5000:1600;
      CountDownLatch ready=new CountDownLatch(1);AtomicBoolean stop=new AtomicBoolean(false);
      final GpuBench.Result[] out=new GpuBench.Result[1];
      Thread render=new Thread(()->{try{out[0]=GpuBench.run(ms,ready,stop);}
        catch(Throwable e){out[0]=new GpuBench.Result(0,e.getMessage());ready.countDown();}},"ES3-Renderer");
      render.start();
      String raw="";Cmd pulse=new Cmd(0,"");
      try{
        if(!ready.await(8,TimeUnit.SECONDS))throw new Exception("OpenGL ES não iniciou");
        if(requested==0 && !node.isEmpty()){
          String sample="sleep 0.2; echo CLK1=$(cat "+q(node)+" 2>/dev/null); "+
            "sleep 0.35; echo CLK2=$(cat "+q(node)+" 2>/dev/null); "+
            "sleep 0.35; echo CLK3=$(cat "+q(node)+" 2>/dev/null)";
          raw=shell(sample).out;
        }
        if(requested>0){
          String x="( printf '%s\\n' "+requested+" > "+q(node)+" ) & p=$!; "+
            "sleep 0.20; echo PICO=$(cat "+q(node)+" 2>/dev/null); "+
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
        "\nBenchmark: "+(longRun?"5,0":"1,6")+" s / GPU OpenGL ES 3\n"+
        String.format(Locale.US,"Desenhos/s: %,.1f",d)+"\nShader: "+(score==null?"não finalizado":score.status)+
        "\nTemperatura: "+cel(startT)+" → "+cel(endT);
      if(requested==0 && !raw.isEmpty()){
        s+="\nClock sob carga (3 leituras): "+
          MHz(field(raw,"CLK1"))+", "+MHz(field(raw,"CLK2"))+", "+MHz(field(raw,"CLK3"));
      }
      if(requested>0){
        s+="\nRetorno de comando: "+(pulse.code==0?"OK":"erro "+pulse.code)+
          "\nLeitura durante pulso: "+MHz(field(raw,"PICO"))+
          "\nLeitura após pulso: "+MHz(field(raw,"APOS"));
        if(reference>0&&d>0)s+=String.format(Locale.US,"\nDiferença vs original curto: %+.2f%%",100*(d/reference-1));
        s+="\nAviso: pulso ~1 s em benchmark 1,6 s. Resultado inclui tempo em clock original.";
      }
      if(endT>=75000)s+="\nALERTA: GPU >=75°C; não repetir OC.";
      String cur=node.isEmpty()?"—":MHz(get("cat "+q(node)));
      s+="\nClock final: "+cur+"\nExperimento, não é teste de estabilidade.";
      String finalS=s;ui.post(()->{results.setText(finalS);report=finalS;enable(true);
        diagnostic.append("\nÚltimo teste: "+(requested==0?"stock":requested/1000+" MHz")+" | GPU "+cel(endT));});
    });
  }
}
