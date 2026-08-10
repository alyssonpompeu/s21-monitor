package com.alysson.wifispectrum;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 71;
    private static final long AUTO_SCAN_MS = 30_000L;

    private WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean running = true;
    private long lastFrameMs;

    private SpectrogramView spectrogram;
    private TextView status;
    private TextView stats;
    private TextView networks;
    private Button autoButton;
    private Spinner bandSpinner;
    private List<WifiSample> latest = new ArrayList<>();

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (updated) {
                captureResults(true, "novo scan");
            } else {
                status.setText("Scan concluído sem dados novos. Mantendo a última amostra.");
            }
        }
    };

    private final Runnable autoScan = new Runnable() {
        @Override public void run() {
            if (running && canReadScans() && isLocationEnabled() && wifi != null && wifi.isWifiEnabled()) {
                requestScan(false);
            }
            handler.postDelayed(this, AUTO_SCAN_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        setContentView(buildUi());
        requestPermissionsIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(scanReceiver, filter);
        receiverRegistered = true;
        handler.post(autoScan);
        if (canReadScans()) {
            refreshReadiness();
            captureResults(false, "cache do Android");
            handler.postDelayed(() -> requestScan(false), 650);
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(autoScan);
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(4, 7, 11));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(root);

        TextView title = text("Wi‑Fi Spectrogram", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());

        TextView sub = text("Espectro instantâneo + waterfall temporal de redes próximas", 12, Color.rgb(101, 206, 255), false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub, match());

        status = card("Preparando Wi‑Fi e permissões…");
        root.addView(status, margin(0, 0, 0, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = button("SCAN AGORA");
        scan.setOnClickListener(v -> requestScan(true));
        row.addView(scan, weight());
        autoButton = button("AUTO: LIGADO");
        autoButton.setOnClickListener(v -> {
            running = !running;
            autoButton.setText(running ? "AUTO: LIGADO" : "AUTO: PAUSADO");
            status.setText(running ? "Captura automática habilitada a cada 30 s." : "Captura automática pausada.");
        });
        row.addView(autoButton, weight());
        root.addView(row, matchMargin(0, 0, 0, 8));

        Button permissions = button("PERMISSÕES / WI‑FI / LOCALIZAÇÃO");
        permissions.setOnClickListener(v -> openNeededSetting());
        root.addView(permissions, margin(0, 0, 0, 9));

        TextView bandLabel = text("BANDA ANALISADA", 11, Color.rgb(110, 205, 255), true);
        root.addView(bandLabel, match());
        bandSpinner = new Spinner(this);
        String[] bands = {"2,4 GHz", "5 GHz", "6 GHz"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bands);
        bandSpinner.setAdapter(adapter);
        bandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                spectrogram.setBand(position);
                updateStats();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(bandSpinner, margin(0, 2, 0, 8));

        spectrogram = new SpectrogramView(this);
        root.addView(spectrogram, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(560)));

        stats = card("Sem amostras ainda.");
        root.addView(stats, margin(0, 8, 0, 8));

        Button clear = button("LIMPAR HISTÓRICO DO WATERFALL");
        clear.setOnClickListener(v -> {
            spectrogram.clearHistory();
            updateStats();
        });
        root.addView(clear, margin(0, 0, 0, 10));

        TextView listTitle = text("PONTOS DE ACESSO VISÍVEIS", 11, Color.rgb(110, 205, 255), true);
        root.addView(listTitle, match());
        networks = text("Nenhuma rede lida ainda.", 12, Color.rgb(190, 205, 214), false);
        networks.setTypeface(Typeface.MONOSPACE);
        networks.setPadding(dp(5), dp(7), dp(5), dp(7));
        root.addView(networks, match());

        TextView note = text("A imagem é construída a partir de frequência, largura de canal e RSSI informados pelo Android. Não são amostras RF/IQ brutas. Quanto mais próximo de -35 dBm, mais forte o sinal; perto de -100 dBm, mais fraco.", 12, Color.rgb(255, 216, 130), false);
        note.setPadding(dp(10), dp(11), dp(10), dp(11));
        note.setBackgroundColor(Color.rgb(42, 34, 18));
        root.addView(note, margin(0, 7, 0, 0));
        return scroll;
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> req = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), REQ_PERMS);
        else refreshReadiness();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            refreshReadiness();
            if (canReadScans()) {
                captureResults(false, "cache do Android");
                handler.postDelayed(() -> requestScan(false), 500);
            }
        }
    }

    private boolean canReadScans() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean nearbyGranted() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        }
        try {
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    private void refreshReadiness() {
        if (wifi == null) {
            status.setText("Wi‑Fi não está disponível neste aparelho.");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            status.setText("Wi‑Fi desligado. Ligue o Wi‑Fi para detectar redes próximas.");
            return;
        }
        if (!canReadScans()) {
            status.setText("Conceda Localização precisa. O Android exige essa permissão para startScan/getScanResults.");
            return;
        }
        if (!isLocationEnabled()) {
            status.setText("Ative o serviço Localização do aparelho para liberar os resultados de scan Wi‑Fi.");
            return;
        }
        status.setText("Pronto • Localização OK • Nearby " + (nearbyGranted() ? "OK" : "não concedido") + " • auto 30 s");
    }

    private void openNeededSetting() {
        if (!canReadScans()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (!isLocationEnabled()) {
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            catch (Exception e) { toast("Abra Configurações > Localização e ative o serviço."); }
            return;
        }
        if (wifi != null && !wifi.isWifiEnabled()) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                else startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            return;
        }
        if (!nearbyGranted() && Build.VERSION.SDK_INT >= 33) requestPermissionsIfNeeded();
        else refreshReadiness();
    }

    @SuppressLint("MissingPermission")
    private void requestScan(boolean userInitiated) {
        if (wifi == null || !wifi.isWifiEnabled()) {
            status.setText("Wi‑Fi desligado.");
            return;
        }
        if (!canReadScans()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (!isLocationEnabled()) {
            status.setText("Localização está desligada. Ative para fazer a varredura.");
            return;
        }

        boolean accepted;
        try { accepted = wifi.startScan(); }
        catch (SecurityException e) {
            accepted = false;
            status.setText("Android bloqueou startScan: " + e.getClass().getSimpleName());
        }
        if (accepted) {
            status.setText(userInitiated ? "Scan solicitado… aguardando dados novos." : "Auto scan solicitado…");
        } else {
            status.setText("SCAN LIMITADO/RECUSADO pelo Android. O waterfall não adicionará uma linha falsa; aguardando próximo scan do sistema.");
            if (spectrogram.getFrameCount() == 0) captureResults(false, "resultado em cache");
        }
    }

    @SuppressLint("MissingPermission")
    private void captureResults(boolean addFrame, String source) {
        if (!canReadScans() || wifi == null) return;
        List<ScanResult> results;
        try { results = wifi.getScanResults(); }
        catch (SecurityException e) {
            status.setText("Android bloqueou getScanResults. Verifique Localização precisa e serviço de Localização.");
            return;
        }
        if (results == null) results = new ArrayList<>();
        results = new ArrayList<>(results);
        Collections.sort(results, Comparator.comparingInt((ScanResult s) -> s.level).reversed());

        ArrayList<WifiSample> samples = new ArrayList<>();
        for (ScanResult s : results) {
            if (s.BSSID == null) continue;
            String ssid = s.SSID == null || s.SSID.trim().isEmpty() ? "<oculta>" : s.SSID;
            samples.add(new WifiSample(ssid, s.BSSID, s.frequency, s.level, widthMhz(s.channelWidth)));
        }
        latest = samples;

        long now = System.currentTimeMillis();
        if (addFrame || spectrogram.getFrameCount() == 0) {
            spectrogram.addFrame(samples, now);
            lastFrameMs = now;
        }
        renderNetworkList();
        updateStats();
        status.setText(source + ": " + samples.size() + " APs visíveis" + (addFrame ? " • nova linha adicionada ao waterfall" : " • leitura inicial/cache"));
    }

    private void updateStats() {
        if (stats == null || spectrogram == null) return;
        int band = spectrogram.getBand();
        int count = 0;
        int strongest = -200;
        String strongestName = "—";
        for (WifiSample s : latest) {
            if (!inBand(s.frequencyMhz, band)) continue;
            count++;
            if (s.rssiDbm > strongest) {
                strongest = s.rssiDbm;
                strongestName = s.ssid;
            }
        }
        String last = lastFrameMs == 0 ? "—" : new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastFrameMs));
        stats.setText("Banda: " + bandName(band) + "\nAPs nesta banda: " + count + " • frames no waterfall: " + spectrogram.getFrameCount() + "\nMais forte: " + strongestName + (strongest > -200 ? " • " + strongest + " dBm" : "") + "\nÚltima linha nova: " + last);
    }

    private void renderNetworkList() {
        if (networks == null) return;
        if (latest.isEmpty()) {
            networks.setText("Nenhum ponto de acesso retornado pelo Android.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(20, latest.size());
        for (int i = 0; i < n; i++) {
            WifiSample s = latest.get(i);
            sb.append(String.format(Locale.US, "%2d  %4d dBm  %4d MHz  %3d MHz  %s\n", i + 1, s.rssiDbm, s.frequencyMhz, s.widthMhz, trim(s.ssid, 22)));
        }
        networks.setText(sb.toString());
    }

    private static int widthMhz(int channelWidth) {
        switch (channelWidth) {
            case ScanResult.CHANNEL_WIDTH_40MHZ: return 40;
            case ScanResult.CHANNEL_WIDTH_80MHZ: return 80;
            case ScanResult.CHANNEL_WIDTH_160MHZ: return 160;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: return 160;
            case ScanResult.CHANNEL_WIDTH_320MHZ: return 320;
            case ScanResult.CHANNEL_WIDTH_20MHZ:
            default: return 20;
        }
    }

    private static boolean inBand(int f, int band) {
        if (band == SpectrogramView.BAND_24) return f >= 2400 && f <= 2500;
        if (band == SpectrogramView.BAND_5) return f >= 5000 && f < 5925;
        return f >= 5925 && f <= 7125;
    }

    private static String bandName(int band) {
        if (band == SpectrogramView.BAND_24) return "2,4 GHz";
        if (band == SpectrogramView.BAND_5) return "5 GHz";
        return "6 GHz";
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView card(String value) {
        TextView v = text(value, 13, Color.rgb(224, 232, 238), false);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundColor(Color.rgb(25, 34, 41));
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = match();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) {
        return margin(l, t, r, b);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
