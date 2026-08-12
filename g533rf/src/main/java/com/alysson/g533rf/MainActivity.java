package com.alysson.g533rf;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int[] CENTERS={2412,2417,2422,2427,2432,2437,2442,2447,2452,2457,2462,2467,2472};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService bg=Executors.newSingleThreadExecutor();
    private TextView status,log;
    private WifiManager wifi;
    private Snapshot off,on;

    @Override public void onCreate(Bundle b){super.onCreate(b);wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);buildUi();perms();msg("G533: A-00072 ↔ A-00073 | faixa FCC 2403,35–2477,35 MHz");}
    @Override protected void onDestroy(){bg.shutdownNow();super.onDestroy();}

    private void buildUi(){
        ScrollView s=new ScrollView(this); LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(14),dp(14),dp(14),dp(14));s.addView(r);
        r.addView(txt("G533 RF Finder — S21",25));
        r.addView(txt("Detector experimental PASSIVO. Tenta correlacionar energia/CCA na banda 2,4 GHz; não transmite nem finge decodificar o protocolo Avnera.",14));
        status=txt("Pronto",15);r.addView(status);
        r.addView(txt("ALVO CONFIRMADO\nHeadset A-00072\nDongle A-00073\n2403,35–2477,35 MHz\nMapa Avnera candidato: 38 frequências em passos de 2 MHz (hipótese, não confirmação específica do G533).",13));
        r.addView(btn("Diagnosticar ROOT / rádio",v->go(this::diagnose)));
        r.addView(btn("Capturar BASELINE — fone OFF",v->go(()->capture(false))));
        r.addView(btn("Capturar FONE ON",v->go(()->capture(true))));
        r.addView(btn("Comparar OFF x ON",v->compare()));
        r.addView(btn("Sweep monitor 2,4 GHz (ROOT)",v->go(this::sweep)));
        r.addView(btn("Scan Wi‑Fi do ambiente",v->go(this::wifiScan)));
        r.addView(btn("Mapa 38 canais candidatos",v->map()));
        r.addView(btn("Limpar",v->log.setText("")));
        r.addView(txt("Se o driver contar energia não‑802.11 em 'survey', o delta OFF→ON pode localizar a região usada. Para frequência/hopping exatos, o telefone precisa expor métricas espectrais/IQ; caso contrário, só SDR externo consegue confirmar.",12));
        log=txt("",12);log.setTextIsSelectable(true);r.addView(log);setContentView(s);
    }

    private void perms(){List<String> p=new ArrayList<>();p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);List<String> m=new ArrayList<>();for(String x:p)if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED)m.add(x);if(!m.isEmpty())requestPermissions(m.toArray(new String[0]),5333);}
    private void go(Runnable r){bg.execute(()->{try{r.run();}catch(Throwable t){msg("ERRO: "+t);}});}

    private void diagnose(){set("Diagnosticando...");Cmd c=sh("id; command -v iw; command -v nexutil; command -v wl; echo ---; iw dev 2>&1; cat /proc/net/wireless 2>&1");msg("=== DIAGNÓSTICO ===\n"+c.o);String i=iface();if(!i.isEmpty()){msg("Interface: "+i);String raw=sh("iw dev "+safe(i)+" survey dump 2>&1").o;msg(raw);msg(parse(raw).isEmpty()?"survey/CCA não parseável.":"survey/CCA disponível.");}set("Diagnóstico concluído");}

    private void capture(boolean isOn){set(isOn?"Capturando ON...":"Capturando OFF...");Snapshot s=snap();if(isOn)on=s;else off=s;msg("=== "+(isOn?"FONE ON":"BASELINE OFF")+" ===\n"+s.info());set("Snapshot salvo");}
    private Snapshot snap(){Snapshot s=new Snapshot();s.iface=iface();if(!s.iface.isEmpty()){String raw=sh("iw dev "+safe(s.iface)+" survey dump 2>&1").o;s.survey=parse(raw);}s.aps=aps();return s;}

    private void compare(){
        if(off==null||on==null){msg("Capture OFF e ON primeiro.");return;}
        msg("=== DELTA OFF → ON ===");List<D> ds=new ArrayList<>();
        for(Map.Entry<Integer,Sv> e:on.survey.entrySet()){Sv a=off.survey.get(e.getKey()),b=e.getValue();if(a==null)continue;double p0=ratio(a.busy,a.active),p1=ratio(b.busy,b.active);double nd=(a.noise!=null&&b.noise!=null)?b.noise-a.noise:0;ds.add(new D(e.getKey(),p0,p1,nd));}
        ds.sort((a,b)->Double.compare(b.score(),a.score()));
        if(ds.isEmpty()){msg("Sem survey comparável. O driver não expôs CCA suficiente; Wi‑Fi/Bluetooth normais não conseguem detectar o G533 proprietário.");return;}
        for(D d:ds)msg(String.format(Locale.US,"%d MHz | busy %.2f%%→%.2f%% Δ%.2f pp | ruído Δ%.1f dB | candidatos: %s",d.f,d.a*100,d.b*100,(d.b-d.a)*100,d.n,near(d.f,10)));
        msg("Maior variação: região ~"+ds.get(0).f+" MHz. É correlação, não demodulação.");
    }

    private void sweep(){
        set("Sweep...");if(!sh("id").o.contains("uid=0")){msg("Sem root.");set("Sem root");return;}String phy=first(sh("iw phy 2>/dev/null | awk '/Wiphy/{print $2;exit}'").o);if(phy.isEmpty()){msg("Sem Wiphy/iw.");return;}
        sh("iw dev g533mon del 2>/dev/null || true");sh("iw phy "+safe(phy)+" interface add g533mon type monitor 2>&1; ip link set g533mon up 2>&1");
        if(!sh("iw dev g533mon info 2>&1").o.contains("Interface g533mon")){msg("Driver recusou monitor separado; Wi‑Fi principal não foi alterado.");set("Monitor não suportado");return;}
        msg("=== SWEEP CCA ===");
        for(int f:CENTERS){sh("iw dev g533mon set freq "+f+" 2>/dev/null");sleep(300);Map<Integer,Sv>a=parse(sh("iw dev g533mon survey dump 2>/dev/null").o);sleep(700);Map<Integer,Sv>b=parse(sh("iw dev g533mon survey dump 2>/dev/null").o);Sv x=a.get(f),y=b.get(f);if(x==null||y==null){msg(f+" MHz | survey n/a");continue;}long act=diff(y.active,x.active),busy=diff(y.busy,x.busy);msg(String.format(Locale.US,"%d MHz | busy %.2f%% | noise %s | G533 %s",f,ratio(busy,act)*100,y.noise==null?"n/a":String.format(Locale.US,"%.1f dBm",y.noise),near(f,10)));}
        sh("ip link set g533mon down 2>/dev/null; iw dev g533mon del 2>/dev/null");set("Sweep concluído");
    }

    private void wifiScan(){msg("=== WI‑FI AMBIENTE ===");for(Ap a:aps())if(a.f>=2400&&a.f<2500)msg(a.f+" MHz  "+a.r+" dBm  "+a.s);msg("Lista acima é 802.11; G533 pode estar transmitindo e não aparecer nela.");}
    private List<Ap> aps(){List<Ap>x=new ArrayList<>();if(wifi==null)return x;try{wifi.startScan();sleep(1200);for(ScanResult r:wifi.getScanResults())x.add(new Ap(r.frequency,r.level,r.SSID));}catch(Exception e){msg("Wi‑Fi scan: "+e.getMessage());}return x;}

    private void map(){msg("=== 38 FREQUÊNCIAS CANDIDATAS ===");for(int i=0;i<38;i++)msg(String.format(Locale.US,"CH%02d  %.2f MHz",i+1,2403.35+i*2.0));msg("Faixa confirmada; passo de 2 MHz é inferência Avnera.");}
    private String near(double c,double d){List<String>x=new ArrayList<>();for(int i=0;i<38;i++){double f=2403.35+i*2;if(Math.abs(f-c)<=d)x.add(String.format(Locale.US,"%.2f",f));}return x.isEmpty()?"nenhum":String.join(",",x)+" MHz";}

    private String iface(){return first(sh("iw dev 2>/dev/null | awk '$1==\"Interface\"{print $2;exit}'").o);}
    private Map<Integer,Sv> parse(String raw){Map<Integer,Sv>m=new HashMap<>();Sv c=null;Pattern pf=Pattern.compile("frequency:\\s*(\\d+)\\s*MHz",2),pa=Pattern.compile("channel active time:\\s*(\\d+)\\s*ms",2),pb=Pattern.compile("channel busy time:\\s*(\\d+)\\s*ms",2),pn=Pattern.compile("noise:\\s*(-?\\d+(?:\\.\\d+)?)\\s*dBm",2);for(String l:raw.split("\\r?\\n")){Matcher q=pf.matcher(l);if(q.find()){int f=Integer.parseInt(q.group(1));c=m.computeIfAbsent(f,Sv::new);continue;}if(c==null)continue;q=pa.matcher(l);if(q.find()){c.active=Long.parseLong(q.group(1));continue;}q=pb.matcher(l);if(q.find()){c.busy=Long.parseLong(q.group(1));continue;}q=pn.matcher(l);if(q.find())c.noise=Double.parseDouble(q.group(1));}return m;}

    private Cmd sh(String q){StringBuilder o=new StringBuilder();int code=-1;try{Process p=new ProcessBuilder("su","-c",q).redirectErrorStream(true).start();BufferedReader b=new BufferedReader(new InputStreamReader(p.getInputStream()));String l;while((l=b.readLine())!=null)o.append(l).append('\n');code=p.waitFor();}catch(Exception e){o.append(e);}return new Cmd(code,o.toString().trim());}
    private String first(String s){for(String x:s.split("\\r?\\n"))if(!x.trim().isEmpty())return x.trim();return "";}private String safe(String s){return s.replaceAll("[^A-Za-z0-9_.:-]","");}private long diff(long b,long a){return b>=a?b-a:0;}private double ratio(long b,long a){return a>0?Math.max(0,Math.min(1,(double)b/a)):0;}private void sleep(long n){try{Thread.sleep(n);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private void set(String s){ui.post(()->status.setText(s));}private void msg(String s){String t=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());ui.post(()->log.append("["+t+"] "+s+"\n"));}
    private TextView txt(String s,float z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);return v;}private Button btn(String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);return b;}private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    static class Cmd{int c;String o;Cmd(int c,String o){this.c=c;this.o=o;}}static class Sv{int f;long active,busy;Double noise;Sv(int f){this.f=f;}}static class Ap{int f,r;String s;Ap(int f,int r,String s){this.f=f;this.r=r;this.s=s==null||s.isEmpty()?"(oculto)":s;}}static class Snapshot{String iface="";Map<Integer,Sv>survey=new HashMap<>();List<Ap>aps=new ArrayList<>();String info(){return "iface="+iface+" | survey="+survey.size()+" | APs="+aps.size();}}static class D{int f;double a,b,n;D(int f,double a,double b,double n){this.f=f;this.a=a;this.b=b;this.n=n;}double score(){return(b-a)+Math.max(0,n)/100.0;}}
}
