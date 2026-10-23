package com.alysson.g533connect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(8, 14, 24);
    private static final int CARD = Color.rgb(18, 27, 42);
    private static final int CARD2 = Color.rgb(24, 35, 53);
    private static final int TEAL = Color.rgb(58, 228, 190);
    private static final int LAVENDER = Color.rgb(174, 169, 250);
    private static final int WHITE = Color.rgb(237, 243, 248);
    private static final int DIM = Color.rgb(153, 174, 194);
    private static final int GOLD = Color.rgb(250, 192, 108);
    private static final int REQUEST_FILE = 533;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout main, nav;
    private TextView hint;
    private volatile boolean busy;
    private int tab = 0;
    private RadioAudit.Report lastAudit;
    private String firmwareReport = "";
    private String log = "G533 Connect v1.0\nNenhuma transmissão RF ou pareamento executado.\n";

    private GradientDrawable background(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radius));
        return gd;
    }

    private GradientDrawable border(int color, int stroke, int radius) {
        GradientDrawable gd = background(color, radius);
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        setup();
        render();
    }

    private void setup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(NAVY);
        shell.setPadding(dp(18), dp(23), dp(18), dp(30));
        scroll.addView(shell);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        SignalMark mark = new SignalMark();
        brand.addView(mark, new LinearLayout.LayoutParams(dp(49), dp(49)));
        LinearLayout group = vertical();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.leftMargin = dp(10);
        group.addView(text("G533 CONNECT", 21, WHITE, true));
        group.addView(text("AVNERA  ×  BCM4375 LAB", 10, TEAL, true));
        brand.addView(group, gp);
        shell.addView(brand);

        hint = text("S21 • laboratório experimental", 12, DIM, false);
        hint.setPadding(0, dp(8), 0, dp(13));
        shell.addView(hint);
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        shell.addView(nav);
        main = vertical();
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(12);
        shell.addView(main, mp);
        setContentView(scroll);
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setLineSpacing(dp(2), 1.0f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        else t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    private TextView title(String name) {
        TextView t = text(name, 18, WHITE, true);
        t.setPadding(0, dp(15), 0, dp(7));
        return t;
    }

    private void nav() {
        nav.removeAllViews();
        String[] labels = {"Painel", "Conectar", "Firmware", "Logs"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = text(labels[i], 12, tab == i ? NAVY : DIM, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(4), dp(11), dp(4), dp(11));
            b.setBackground(background(tab == i ? TEAL : CARD, 11));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) p.leftMargin = dp(5);
            nav.addView(b, p);
            b.setOnClickListener(v -> { tab = index; render(); });
        }
    }

    private LinearLayout card(int color) {
        LinearLayout box = vertical();
        box.setBackground(background(color, 19));
        box.setPadding(dp(18), dp(16), dp(18), dp(18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(11);
        main.addView(box, p);
        return box;
    }

    private void line(LinearLayout parent, String label, String detail, int color) {
        parent.addView(text(label, 12, DIM, false));
        TextView body = text(detail, 15, color, true);
        body.setPadding(0, dp(3), 0, dp(11));
        parent.addView(body);
    }

    private void action(LinearLayout parent, String name, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(name);
        b.setTextSize(14);
        b.setTextColor(primary ? NAVY : WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(border(primary ? TEAL : CARD2,
                               primary ? TEAL : Color.rgb(54, 72, 94), 13));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(49));
        p.bottomMargin = dp(9);
        parent.addView(b, p);
    }

    private void note(LinearLayout parent, String message, int tint) {
        TextView t = text(message, 12, tint, false);
        t.setPadding(0, dp(5), 0, dp(6));
        parent.addView(t);
    }

    private void render() {
        nav();
        main.removeAllViews();
        if (tab == 0) panel();
        else if (tab == 1) pairing();
        else if (tab == 2) firmware();
        else logs();
    }

    private void panel() {
        LinearLayout hero = card(CARD);
        hero.addView(text("VISÃO GERAL", 11, TEAL, true));
        TextView heading = text("Seu G533.\nUm objetivo.", 28, WHITE, true);
        heading.setPadding(0, dp(7), 0, dp(8));
        hero.addView(heading);
        note(hero, "Controlar o Logitech G533 sem o receptor USB original.", DIM);
        LinearLayout pill = vertical();
        pill.setPadding(dp(11), dp(10), dp(11), dp(10));
        pill.setBackground(border(Color.rgb(48, 37, 24), GOLD, 12));
        pill.addView(text("●  NÃO CONECTADO", 13, GOLD, true));
        pill.addView(text("Ainda não existe um backend Avnera compatível com o rádio deste S21.", 12, DIM, false));
        hero.addView(pill);
        LinearLayout c = card(CARD);
        c.addView(text("SEU DISPOSITIVO", 12, LAVENDER, true));
        line(c, "Telefone", Build.MODEL + "  •  Android " + Build.VERSION.RELEASE, WHITE);
        line(c, "Firmware do Broadcom",
                lastAudit == null ? "18.41.117 / FWID 01-1b4772fd (registro anterior)"
                : lastAudit.wifiIdentity ? "18.41.117 / FWID verificado agora" : "Não confirmado nesta leitura",
                lastAudit != null && lastAudit.wifiIdentity ? TEAL : WHITE);
        line(c, "Correspondência stock Nexmon",
                lastAudit == null ? "Já verificada anteriormente; clique para revalidar"
                : lastAudit.shaMatch ? "SHA-256 confirmado na leitura atual" : "Não confirmada nesta leitura",
                lastAudit != null && lastAudit.shaMatch ? TEAL : DIM);
        line(c, "Rádio Avnera",
                "PHY não implementado; recepção e TX ausentes", GOLD);
        action(c, busy ? "Executando auditoria..." : "Verificar rádio e firmware  →",
                true, v -> audit(0));
        LinearLayout milestones = card(CARD);
        milestones.addView(text("PROGRESSO REAL", 12, LAVENDER, true));
        note(milestones, "✓  Firmware G533 client/host identificado", TEAL);
        note(milestones, "✓  Firmware Wi-Fi do S21 corresponde ao Nexmon #662", TEAL);
        note(milestones, "○  Recepção de amostras RF Avnera: não comprovada", GOLD);
        note(milestones, "○  Pacotes de pareamento: não decodificados", DIM);
        note(milestones, "○  Áudio sem dongle: não implementado", DIM);
    }

    private void pairing() {
        LinearLayout c = card(CARD);
        c.addView(text("PAREAMENTO G533", 12, TEAL, true));
        c.addView(text("Verificar antes\nde conectar.", 25, WHITE, true));
        note(c, "A conexão direta requer um transceptor capaz de transmitir e receber o protocolo proprietário Avnera/Carmen.", DIM);
        line(c, "Status da conexão", "Não conectado", GOLD);
        line(c, "Backend Avnera no S21", "Indisponível", GOLD);
        action(c, "Verificar pré-requisitos de conexão", true, v -> audit(1));
        LinearLayout guide = card(CARD);
        guide.addView(text("COMO COLOCAR O FONE EM PAIRING", 12, LAVENDER, true));
        note(guide, "1. Ligue o headset G533.", WHITE);
        note(guide, "2. Mantenha pressionado o botão G por aproximadamente 15 segundos, até o LED piscar rapidamente.", WHITE);
        note(guide, "3. Para completar, o outro lado precisa responder com pacotes Avnera. O S21 ainda não dispõe desse transmissor.", DIM);
        action(guide, "O LED está piscando — verificar conexão", false, v -> checkPairing());
        LinearLayout usb = card(CARD);
        usb.addView(text("RECEPTOR USB / CARGA", 12, LAVENDER, true));
        note(usb, lastAudit == null ? "Nenhum inventário USB executado." :
                "Receptor G533 A-00073: " + lastAudit.dongles +
                " encontrado(s).\nHeadset conectado via cabo USB: " + lastAudit.headsets + ".",
                WHITE);
        action(usb, "Atualizar dispositivos USB", false, v -> audit(1));
        note(usb, "O cabo USB do G533 não substitui o enlace de áudio proprietário.", DIM);
    }

    private void firmware() {
        LinearLayout c = card(CARD);
        c.addView(text("ENGENHARIA REVERSA", 12, TEAL, true));
        c.addView(text("Firmware G533\nV1.13", 25, WHITE, true));
        note(c, "Confirma os bytes originais do headset e dongle por SHA-256 e extrai seu macconfig, sem gravar nada.", DIM);
        line(c, "Host / dongle", "65.554 bytes  •  macconfig 0x10000", WHITE);
        line(c, "Client / headset", "20.294 bytes  •  macconfig 0x4F17", WHITE);
        action(c, "Importar arquivo BIN para analisar", true, v -> pickFirmware());
        LinearLayout research = card(CARD);
        research.addView(text("TRABALHOS EXISTENTES", 12, LAVENDER, true));
        note(research, "AV7300: ferramentas de desassemblagem Avnera em hardware Corsair. Compatibilidade com a ISA G533 ainda não verificada.", WHITE);
        note(research, "RadioSploit: modifica Bluetooth BCM4375 para Zigbee, Mosart e ESB; não implementa Avnera/DQPSK.", WHITE);
        note(research, "Nexmon #663: patch Wi-Fi para 18.41.117; modo monitor 802.11 não é um decodificador G533.", WHITE);
        if (!firmwareReport.isEmpty()) {
            LinearLayout result = card(CARD2);
            result.addView(text("ÚLTIMA INSPEÇÃO", 12, TEAL, true));
            TextView t = text(firmwareReport, 12, WHITE, false);
            t.setTextIsSelectable(true);
            result.addView(t);
        }
    }

    private void logs() {
        LinearLayout c = card(CARD);
        c.addView(text("DIAGNÓSTICOS", 12, TEAL, true));
        action(c, "Executar nova auditoria", true, v -> audit(3));
        action(c, "Compartilhar relatório", false, v -> share());
        TextView t = text(log, 11, WHITE, false);
        t.setTextIsSelectable(true);
        c.addView(t);
    }

    private void checkPairing() {
        append("PAIRING: LED informado pelo usuário; handshake RF não iniciado. Sem Avnera PHY não há como escutar/parear.");
        tab = 1;
        render();
        android.app.AlertDialog.Builder dlg = new android.app.AlertDialog.Builder(this);
        dlg.setTitle("Pareamento não disponível");
        dlg.setMessage("O LED piscando confirma somente a tentativa de pareamento do headset. " +
                "O aplicativo não recebeu pacotes Avnera e não transmitiu resposta. " +
                "A conexão permanece NÃO CONECTADA.");
        dlg.setPositiveButton("Entendi", null);
        dlg.show();
    }

    private void audit(int returnTab) {
        if (busy) return;
        busy = true;
        hint.setText("Aguarde • auditoria somente leitura");
        worker.execute(() -> {
            RadioAudit.Report result = RadioAudit.run(getApplicationContext());
            ui.post(() -> {
                busy = false;
                lastAudit = result;
                append(result.content);
                hint.setText(result.root ? "Magisk ROOT • leitura concluída" : "Sem ROOT • leitura parcial");
                tab = returnTab;
                render();
                if (returnTab == 1) new android.app.AlertDialog.Builder(this)
                        .setTitle("Verificação concluída")
                        .setMessage("Firmware compatível para pesquisa: " +
                                (result.wifiIdentity && result.shaMatch ? "SIM" : "não confirmado") +
                                "\nReceptor USB: " + result.dongles +
                                "\n\nPareamento direto Avnera: NÃO SUPORTADO. " +
                                "Não foram transmitidos pacotes nem criado um enlace.")
                        .setPositiveButton("Fechar", null).show();
            });
        });
    }

    private void pickFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQUEST_FILE || code != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        hint.setText("Analisando BIN local...");
        worker.execute(() -> {
            String answer;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Documento não pôde ser aberto.");
                answer = FirmwareInspector.inspect(input);
            } catch (Exception e) {
                answer = "Não foi possível analisar: " + e.getMessage();
            }
            final String captured = answer;
            ui.post(() -> {
                firmwareReport = captured;
                append(captured);
                hint.setText("Firmware inspecionado • nenhuma alteração executada");
                tab = 2;
                render();
            });
        });
    }

    private void append(String message) {
        String when = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        log = log + "\n[" + when + "] " + message;
        if (log.length() > 40000) log = log.substring(log.length() - 40000);
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "G533 Connect — relatório de engenharia");
        i.putExtra(Intent.EXTRA_TEXT, log);
        startActivity(Intent.createChooser(i, "Compartilhar diagnóstico"));
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class SignalMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SignalMark() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setColor(CARD2); paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), dp(13), dp(13), paint);
            paint.setColor(TEAL);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            for (int n = 0; n < 3; n++) {
                float r = dp(7 + n * 6);
                c.drawArc(new RectF(x-r, y-r, x+r, y+r), 210, 120, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y + dp(4), dp(3.5f), paint);
        }
    }
}
