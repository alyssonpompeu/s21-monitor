package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RX42 controller using AFHDS2A plus constants recovered from FS-i6 firmware 2.0.17. */
public class Rx42ControlActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status,rfState,packetView,valuesView,hopsView,profileView;
    private SeekBar throttle,steering,ch3,ch4;
    private Button diagnose,phyProbe,bind,neutral;
    private Afhds2aEngine engine;
    private int stableId;
    private volatile boolean nexmonPresent;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stableId=getPreferences(MODE_PRIVATE).getInt("afhds2a_tx_id",0);
        if(stableId==0){stableId=new SecureRandom().nextInt();if(stableId==0)stableId=0x42A7105;getPreferences(MODE_PRIVATE).edit().putInt("afhds2a_tx_id",stableId).apply();}
        engine=new Afhds2aEngine(stableId);
        setContentView(buildUi());
        updatePacket();
    }

    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private ScrollView buildUi(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(36));root.setBackgroundColor(0xFF080B0E);scroll.addView(root);

        root.addView(txt("RX42 Control FS-i6 RE v3",28,Color.WHITE,true));
        root.addView(txt("MA-RX42-A/W • AFHDS2A • perfil RF FS-i6 2.0.17 • Template RAM probe",13,0xFF80CBC4,false));
        root.addView(txt("Samsung "+Build.MODEL+" • "+Build.HARDWARE+" • Android "+Build.VERSION.RELEASE,12,0xFFB0BEC5,false));

        status=txt("Perfil FS-i6 carregado. Verifique primeiro o Nexmon.",15,0xFFFFD180,true);status.setPadding(0,dp(16),0,dp(8));root.addView(status);
        rfState=mono("LINK: NÃO CONECTADO\nPHY GFSK TX: NÃO VALIDADO\n"+engine.describeIds(),12,0xFFE0E0E0);root.addView(rfState);

        diagnose=button("1. VERIFICAR NEXMON / BACKEND RF");diagnose.setOnClickListener(v->diagnoseRf());root.addView(diagnose);
        phyProbe=button("2. TESTAR TEMPLATE RAM 0x631 (SEM TX)");phyProbe.setOnClickListener(v->startActivity(new Intent(this,Rx42PhyProbeV1Activity.class)));root.addView(phyProbe);
        bind=button("3. PREPARAR / BIND RX42");bind.setOnClickListener(v->attemptBind());root.addView(bind);

        root.addView(section("PERFIL ORIGINAL FS-i6 2.0.17"));
        profileView=mono(FsI6ReverseProfile.summary()+"\n\nREGISTROS A7105 EXTRAÍDOS:\n"+FsI6ReverseProfile.compactRegisterDump(),10,0xFFB2DFDB);profileView.setTextIsSelectable(true);root.addView(profileView);

        root.addView(section("CONTROLES"));
        throttle=slider(root,"Motor / CH1",0);
        steering=slider(root,"Direção / CH2",500);
        ch3=slider(root,"CH3",500);
        ch4=slider(root,"CH4",500);
        neutral=button("MOTOR 0 + DIREÇÃO CENTRO");neutral.setOnClickListener(v->{throttle.setProgress(0);steering.setProgress(500);ch3.setProgress(500);ch4.setProgress(500);updatePacket();});root.addView(neutral);
        valuesView=mono("",12,0xFFCFD8DC);root.addView(valuesView);

        root.addView(section("HOPPING AFHDS2A (16 canais A7105)"));
        hopsView=mono(engine.describeHops(),12,0xFF80CBC4);root.addView(hopsView);
        root.addView(section("PACOTE 0x58 — 38 BYTES"));
        packetView=mono("",11,0xFFE0E0E0);packetView.setTextIsSelectable(true);root.addView(packetView);

        TextView note=txt("Segurança: motor inicia em 1000 µs. Remova hélice/motor durante testes. A etapa 0x631 só valida escrita/leitura/restauração da Template RAM com sample playback desligado; ela NÃO transmite RF. O bind permanece bloqueado até o backend GFSK ser validado.",12,0xFFFFAB91,false);note.setPadding(0,dp(18),0,0);root.addView(note);
        return scroll;
    }

    private void diagnoseRf(){
        diagnose.setEnabled(false);bind.setEnabled(false);status.setTextColor(0xFFFFD180);status.setText("Verificando BCM4375/Nexmon…");
        worker.execute(()->{
            String out=RootReader.run(nativeProbe()+" wlan0",7).output;
            boolean root=RootReader.run("id",3).output.contains("uid=0");
            boolean nex=out.contains("NEXPROBE_PR663_600=true")||out.contains("TRIAGE_RESULT=NEXMON_PRESENT");
            nexmonPresent=nex;
            String se=RootReader.run("getenforce 2>&1",3).output.trim();
            ui.post(()->{
                diagnose.setEnabled(true);bind.setEnabled(true);
                if(root&&nex){
                    status.setTextColor(0xFF81C784);status.setText("Nexmon confirmado. AFHDS2A + perfil FS-i6 prontos.");
                    rfState.setText("NEXMON: PRESENTE (PR663 0x600)\nSELinux: "+se+"\nFS-i6 RF profile: CARREGADO NO APP\nPHY GFSK TX: AINDA NÃO VALIDADO\nLINK: NÃO CONECTADO\n"+engine.describeIds());
                }else{
                    status.setTextColor(0xFFEF9A9A);status.setText("Backend Nexmon não confirmado.");
                    rfState.setText("root="+root+" nexmon="+nex+" SELinux="+se+"\n\n"+out);
                }
            });
        });
    }

    private void attemptBind(){
        if(!nexmonPresent){new AlertDialog.Builder(this).setTitle("Verifique o RF primeiro").setMessage("Toque em 'VERIFICAR NEXMON / BACKEND RF' antes.").setPositiveButton("OK",null).show();return;}
        byte[] b1=engine.buildBindPacket(1),b2=engine.buildBindPacket(2),b3=engine.buildBindPacket(3),b4=engine.buildBindPacket(4);
        String preview="FS-i6 A7105 ID: 0x"+Long.toHexString(FsI6ReverseProfile.A7105_ID).toUpperCase(Locale.US)+
                "\nBind RF alterna canais: 0x8C / 0x0D\nPeríodo de referência: "+Afhds2aEngine.PERIOD_US+" µs\n\nBIND1:\n"+Afhds2aEngine.hex(b1)+
                "\n\nBIND2:\n"+Afhds2aEngine.hex(b2)+"\n\nBIND3:\n"+Afhds2aEngine.hex(b3)+"\n\nBIND4 (RX ID ainda não aprendido):\n"+Afhds2aEngine.hex(b4)+
                "\n\nDATA 0x58:\n"+Afhds2aEngine.hex(engine.buildSticksPacket(currentChannels()));
        new AlertDialog.Builder(this).setTitle("Bind AFHDS2A reconstruído").setMessage("A engenharia reversa do FS-i6 está incorporada, mas esta v3 ainda NÃO aciona sample playback nem transmite GFSK. Primeiro precisamos confirmar o round-trip 0x631 da Template RAM.\n\n"+preview).setPositiveButton("OK",null).show();
        status.setTextColor(0xFFFFD180);status.setText("Bind pronto; aguardando validação da Template RAM / PHY GFSK.");
    }

    private SeekBar slider(LinearLayout root,String label,int initial){
        TextView t=txt(label,14,0xFFECEFF1,true);t.setPadding(0,dp(12),0,0);root.addView(t);
        SeekBar s=new SeekBar(this);s.setMax(1000);s.setProgress(initial);root.addView(s);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean f){updatePacket();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});return s;
    }
    private int[] currentChannels(){return new int[]{1000+throttle.getProgress(),1000+steering.getProgress(),1000+ch3.getProgress(),1000+ch4.getProgress()};}
    private void updatePacket(){if(throttle==null)return;int[] c=currentChannels();if(valuesView!=null)valuesView.setText("CH1 motor="+c[0]+"  CH2 direção="+c[1]+"\nCH3="+c[2]+"  CH4="+c[3]+"\n"+engine.describeIds());if(packetView!=null)packetView.setText(Afhds2aEngine.hex(engine.buildSticksPacket(c)));}
    private String nativeProbe(){return q(getApplicationInfo().nativeLibraryDir+"/libnexprobe.so");}
    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    private TextView section(String s){TextView t=txt(s,13,0xFF80CBC4,true);t.setPadding(0,dp(18),0,dp(4));return t;}
    private TextView txt(String s,float sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private TextView mono(String s,float sp,int color){TextView t=txt(s,sp,color,false);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(9);b.setLayoutParams(p);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
