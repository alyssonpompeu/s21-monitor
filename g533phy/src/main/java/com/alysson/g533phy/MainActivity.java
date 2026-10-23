package com.alysson.g533phy;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logitech G533 experimental offline PHY lab.
 * This app cannot sample the BCM4375 radio directly, cannot pair G533,
 * and does not pretend synthetic I/Q is real RF.
 */
public final class MainActivity extends Activity {
    private static final int PICK_OFF = 201;
    private static final int PICK_ON = 202;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private Uri offUri, onUri;
    private TextView status, fileOff, fileOn, output;
    private SpectrumView spectrum;
    private Spinner format;
    private EditText centerMHz, sampleRate, symbolRate;
    private String lastReport = "Nenhuma análise executada.";
    private int bg = Color.rgb(9, 16, 25);
    private final int white = Color.rgb(231, 240, 248);
    private final int muted = Color.rgb(163, 185, 203);

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setup();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private Button button(String caption, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(caption); b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(action);
        return b;
    }

    private EditText numeric(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(white);
        e.setHintTextColor(muted);
        e.setTextSize(16);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        return e;
    }

    private void setup() {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(35));
        root.setBackgroundColor(bg);
        scroll.addView(root);
        root.addView(label("G533  •  PHY LAB", 26, white));
        root.addView(label("Android v1.0  |  " + Build.MODEL + "  |  somente análise offline", 12, muted));
        root.addView(label("Este APK NÃO capta RF do S21 nem conecta o headset. " +
                "Analisa arquivos I/Q externos ou sinais gerados artificialmente.", 14, Color.rgb(255, 203, 134)));

        root.addView(label("1  TESTE IMEDIATO", 17, white));
        root.addView(label("Gera um sinal pi/4-DQPSK sintético e ruído com sementes fixas. " +
                "Demonstra a diferença OFF/ON e a hipótese de fase — sem sinal do G533.", 12, muted));
        root.addView(button("Executar demonstração sintética", v -> demonstrate()));

        spectrum = new SpectrumView(this);
        root.addView(spectrum, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));

        root.addView(label("2  IMPORTAR I/Q DO PC / SDR", 17, white));
        root.addView(label("Selecione duas gravações da MESMA frequência e taxa, com o headset OFF e ON. " +
                "Copie os arquivos para o telefone ou abra pelo seletor de documentos.", 12, muted));
        root.addView(button("Selecionar captura OFF", v -> pick(PICK_OFF)));
        fileOff = label("OFF: não selecionado", 12, muted);
        root.addView(fileOff);
        root.addView(button("Selecionar captura ON", v -> pick(PICK_ON)));
        fileOn = label("ON: não selecionado", 12, muted);
        root.addView(fileOn);

        root.addView(label("Formato I/Q entrelaçado", 13, white));
        format = new Spinner(this);
        String[] formats = {"cf32le — Float32 I/Q", "cs16le — Int16 I/Q", "cu8 — Unsigned8 I/Q"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, formats);
        format.setAdapter(adapter);
        root.addView(format);

        root.addView(label("Taxa de amostragem (Hz)", 13, white));
        sampleRate = numeric("4000000");
        root.addView(sampleRate);
        root.addView(label("Frequência central (MHz)", 13, white));
        centerMHz = numeric("2439.35");
        root.addView(centerMHz);
        root.addView(label("Taxa de símbolos (Hz) — opcional, deixe vazio se desconhecida", 13, white));
        symbolRate = numeric("");
        symbolRate.setHint("Desconhecida");
        root.addView(symbolRate);

        root.addView(button("Analisar OFF × ON", v -> analyzeFiles()));
        root.addView(button("Mostrar 38 canais candidatos", v -> channels()));
        root.addView(button("Compartilhar relatório", v -> share()));

        status = label("Pronto para executar o teste sintético.", 15, Color.rgb(115, 225, 198));
        root.addView(status);
        output = label("Os resultados aparecerão aqui.\n", 12, white);
        output.setTextIsSelectable(true);
        root.addView(output);
        root.addView(label("FCC G533 A-00073: 2403,35–2477,35 MHz / DQPSK / 38 canais. " +
                "A grade de 2 MHz é candidata; NÃO detecta canal ativo nem protocolo/pacotes. " +
                "FFTs de 2048 pontos. No máximo as primeiras 524288 amostras por arquivo.", 12, muted));
        setContentView(scroll);
    }

    private void pick(int which) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, which);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Current session's document grant is still enough.
        }
        if (req == PICK_OFF) { offUri = uri; fileOff.setText("OFF: " + fileName(uri)); }
        if (req == PICK_ON) { onUri = uri; fileOn.setText("ON: " + fileName(uri)); }
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "documento I/Q selecionado";
    }

    private void demonstrate() {
        if (busy) return;
        busy = true;
        setStatus("Processando demonstração DQPSK sintética...");
        worker.execute(() -> {
            try {
                IQAnalyzer.Demo demo = IQAnalyzer.demo();
                IQAnalyzer.Result r = IQAnalyzer.compare(
                        demo.off, demo.on, demo.sampleRate, 2439.35, demo.symbolRate);
                show(r, true);
            } catch (Exception e) { fail(e); }
            finally { busy = false; }
        });
    }

    private void analyzeFiles() {
        if (busy) return;
        if (offUri == null || onUri == null) {
            setStatus("Selecione primeiro as duas capturas OFF e ON.");
            return;
        }
        final double fs, fc;
        final Double baud;
        final IQAnalyzer.Format fmt;
        try {
            fs = Double.parseDouble(sampleRate.getText().toString().trim().replace(',', '.'));
            fc = Double.parseDouble(centerMHz.getText().toString().trim().replace(',', '.'));
            String s = symbolRate.getText().toString().trim().replace(',', '.');
            baud = s.isEmpty() ? null : Double.parseDouble(s);
            fmt = IQAnalyzer.Format.values()[format.getSelectedItemPosition()];
        } catch (Exception e) {
            setStatus("Verifique os valores de taxa, centro e formato.");
            return;
        }
        final Uri off = offUri, on = onUri;
        busy = true;
        setStatus("Lendo arquivos I/Q e calculando espectro...");
        worker.execute(() -> {
            try (InputStream a = getContentResolver().openInputStream(off);
                 InputStream b = getContentResolver().openInputStream(on)) {
                if (a == null || b == null) throw new IllegalStateException("Não foi possível abrir os arquivos");
                IQAnalyzer.Samples offSamples = IQAnalyzer.load(a, fmt);
                IQAnalyzer.Samples onSamples = IQAnalyzer.load(b, fmt);
                IQAnalyzer.Result result = IQAnalyzer.compare(offSamples, onSamples, fs, fc, baud);
                show(result, false);
            } catch (Exception e) { fail(e); }
            finally { busy = false; }
        });
    }

    private void show(IQAnalyzer.Result r, boolean demo) {
        final String result = r.describe(demo);
        ui.post(() -> {
            lastReport = "G533 PHY Lab Android v1.0\n" + result;
            spectrum.setResult(r);
            output.setText(result);
            status.setText(demo
                    ? "Demonstração concluída — sinal artificial, NÃO G533."
                    : "Análise de arquivos concluída — identidade do emissor NÃO verificada.");
        });
    }

    private void fail(Exception e) {
        ui.post(() -> {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status.setText("Falha: " + message);
            output.setText("Não foi possível concluir a análise.\n" + message);
        });
    }

    private void setStatus(String s) { status.setText(s); }

    private void channels() {
        StringBuilder s = new StringBuilder("38 centros RF CANDIDATOS (2 MHz) — NÃO são canais capturados:\n");
        for (int i = 0; i < 38; i++) {
            s.append(String.format(Locale.US, "%2d: %.2f MHz\n", i + 1, 2403.35 + 2 * i));
        }
        s.append("\nA identificação de canais RF não fornece o protocolo de pareamento.\n");
        lastReport = "G533 PHY Lab Android v1.0\n" + s;
        output.setText(s.toString());
        status.setText("Grade candidata exibida; nenhuma varredura de rádio.");
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "G533 PHY Lab — relatório offline");
        i.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(i, "Compartilhar análise"));
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
