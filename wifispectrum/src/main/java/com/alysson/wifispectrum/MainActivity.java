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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
    private static final long LIVE_TICK_MS = 250L;
    private static final long AUTO_SCAN_MS = 30_000L;

    private WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered = false;
    private boolean liveEnabled = true;
    private boolean autoScanEnabled = true;
    private long lastAutoScanMs = 0L;
    private long lastScanFrameMs = 0L;
    private long lastUiMs = 0L;
    private int liveFrames = 0;
    private int scanFrames = 0;

    private SpectrogramView spectrogram;
    private TextView status;
    private TextView stats;
    private TextView networks;
    private Button liveButton;
    private Button autoButton;
    private Spinner bandSpinner;
    private List<WifiSample> latestScan = new ArrayList<>();
    private WifiSample latestConnected = null;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (updated) captureScanResults(true, "scan novo");
            else status.setText("Scan concluído sem dados novos • LIVE conectado continua ativo.");
        }
    };

    private final Runnable fastLoop = new Runnable() {
        @Override public void run() {
            if (liveEnabled) captureConnectedLive();
            long now = System.currentTimeMillis();
            if (autoScanEnabled && now - lastAutoScanMs >= AUTO_SCAN_MS) {
                requestScan(false);
                lastAutoScanMs = now;
            }
            if (now - lastUiMs >= 1000L) {
                updateStats();
                lastUiMs = now;
            }
            handler.postDelayed(this, LIVE_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        if (canReadScans()) {
            refreshReadiness();
            captureScanResults(false, "cache inicial");
            handler.postDelayed(() -> requestScan(false), 700L);
        }
        handler.post(fastLoop);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(fastLoop);
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(3, 5, 10));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(26));
        scroll.addView(root);

        TextView title = text("Wi‑Fi Spectrogram Pro", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());

        TextView sub = text("Waterfall tempo × frequência • LIVE conectado ~250 ms", 12, Color.rgb(103, 211, 255), false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(3), 0, dp(8));
        root.addView(sub, match());

        status = card("Preparando Wi‑Fi…");
        root.addView(status, margin(0, 0, 0, 7));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = button("SCAN REDES");
        scan.setOnClickListener(v -> requestScan(true));
        row1.addView(scan, weight());
        liveButton = button("LIVE 250 ms: ON");
        liveButton.setOnClickListener(v -> {
            liveEnabled = !liveEnabled;
            liveButton.setText(liveEnabled ? "LIVE 250 ms: ON" : "LIVE: PAUSADO");
            status.setText(liveEnabled ? "LIVE rápido retomado para a rede conectada." : "LIVE rápido pausado. Scans gerais continuam disponíveis.");
        });
        row1.addView(liveButton, weight());
        root.addView(row1, matchMargin(0, 0, 0, 6));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        autoButton = button("AUTO SCAN 30s: ON");
        autoButton.setOnClickListener(v -> {
            autoScanEnabled = !autoScanEnabled;
            autoButton.setText(autoScanEnabled ? "AUTO SCAN 30s: ON" : "AUTO SCAN: OFF");
            if (autoScanEnabled) lastAutoScanMs = 0L;
        });
        row2.addView(autoButton, weight());
        Button clear = button("LIMPAR");
        clear.setOnClickListener(v -> {
            spectrogram.clearHistory();
            liveFrames = 0;
            scanFrames = 0;
            updateStats();
        });
        row2.addView(clear, weight());
        root.addView(row2, matchMargin(0, 0, 0, 6));

        Button permissions = button("PERMISSÕES / WI‑FI / LOCALIZAÇÃO");
        permissions.setOnClickListener(v -> openNeededSetting());
        root.addView(permissions, margin(0, 0, 0, 8));

        TextView bandLabel = text("BANDA ANALISADA", 11, Color.rgb(111, 211, 255), true);
        root.addView(bandLabel, match());
        bandSpinner = new Spinner(this);
        String[] bands = {"2,4 GHz", "5 GHz", "6 GHz"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bands);
        bandSpinner.setAdapter(adapter);
        bandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                spectrogram.setBand(position);
                liveFrames = 0;
                scanFrames = 0;
                addImmediateHybridFrame();
                updateStats();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(bandSpinner, margin(0, 0, 0, 4));

        spectrogram = new SpectrogramView(this);
        root.addView(spectrogram, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(660)));

        stats = card("Aguardando leitura.");
        root.addView(stats, margin(0, 7, 0, 8));

        TextView listTitle = text("REDES DO ÚLTIMO SCAN REAL", 11, Color.rgb(111, 211, 255), true);
        root.addView(listTitle, match());
        networks = text("Nenhuma rede lida ainda.", 11, Color.rgb(190, 205, 214), false);
        networks.setTypeface(Typeface.MONOSPACE);
        networks.setPadding(dp(4), dp(6), dp(4), dp(6));
        root.addView(networks, match());

        TextView note = text("Leitura híbrida: a rede Wi‑Fi conectada usa RSSI real consultado rapidamente (~250 ms). As demais redes usam o último ScanResult real do Android e só mudam quando chega um novo scan. O preenchimento visual entre canais é interpolação gráfica, não RF/IQ bruto.", 11, Color.rgb(255, 218, 132), false);
        note.setPadding(dp(9), dp(10), dp(9), dp(10));
        note.setBackgroundColor(Color.rgb(43, 34, 18));
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
                captureScanResults(false, "cache inicial");
                handler.postDelayed(() -> requestScan(false), 500L);
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
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private void refreshReadiness() {
        if (wifi == null) {
            status.setText("Wi‑Fi não disponível neste aparelho.");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            status.setText("Wi‑Fi desligado.");
            return;
        }
        if (!canReadScans()) {
            status.setText("Conceda Localização precisa para ler redes próximas.");
            return;
        }
        if (!isLocationEnabled()) {
            status.setText("Ative Localização do aparelho para o Android liberar scans Wi‑Fi.");
            return;
        }
        status.setText("Pronto • LIVE 250 ms para rede conectada • scans gerais ~30 s" + (nearbyGranted() ? "" : " • Nearby pendente"));
    }

    private void openNeededSetting() {
        if (!canReadScans() || !nearbyGranted()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (!isLocationEnabled()) {
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            catch (Exception e) { toast("Ative Localização nas Configurações."); }
            return;
        }
        if (wifi != null && !wifi.isWifiEnabled()) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                else startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            return;
        }
        refreshReadiness();
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
            status.setText("Localização desligada. Ative para fazer scan de redes próximas.");
            return;
        }
        boolean accepted;
        try { accepted = wifi.startScan(); }
        catch (SecurityException e) { accepted = false; }
        if (accepted) status.setText(userInitiated ? "Scan solicitado…" : "Auto scan solicitado…");
        else status.setText("Scan geral limitado pelo Android • LIVE da rede conectada continua em ~250 ms.");
    }

    @SuppressLint("MissingPermission")
    private void captureScanResults(boolean addFrame, String source) {
        if (wifi == null || !canReadScans()) return;
        List<ScanResult> results;
        try { results = wifi.getScanResults(); }
        catch (SecurityException e) {
            status.setText("Android bloqueou getScanResults. Verifique permissões/localização.");
            return;
        }
        if (results == null) results = new ArrayList<>();
        results = new ArrayList<>(results);
        Collections.sort(results, Comparator.comparingInt((ScanResult s) -> s.level).reversed());

        ArrayList<WifiSample> samples = new ArrayList<>();
        for (ScanResult s : results) {
            if (s.BSSID == null) continue;
            String ssid = cleanSsid(s.SSID);
            samples.add(new WifiSample(ssid, s.BSSID, s.frequency, s.level, widthMhz(s.channelWidth)));
        }
        latestScan = samples;
        renderNetworkList();
        if (addFrame) {
            List<WifiSample> hybrid = mergeConnected(samples, readConnectedSample());
            spectrogram.addFrame(hybrid, System.currentTimeMillis());
            scanFrames++;
            lastScanFrameMs = System.currentTimeMillis();
        }
        status.setText(source + ": " + samples.size() + " APs • dados gerais atualizados");
        updateStats();
    }

    @SuppressLint("MissingPermission")
    private void captureConnectedLive() {
        if (wifi == null || !wifi.isWifiEnabled()) return;
        WifiSample connected = readConnectedSample();
        latestConnected = connected;
        if (connected == null) return;
        List<WifiSample> hybrid = mergeConnected(latestScan, connected);
        spectrogram.addFrame(hybrid, System.currentTimeMillis());
        liveFrames++;
    }

    @SuppressLint("MissingPermission")
    private WifiSample readConnectedSample() {
        WifiInfo info;
        try { info = wifi.getConnectionInfo(); }
        catch (Exception e) { return null; }
        if (info == null) return null;
        String bssid = info.getBSSID();
        int freq = info.getFrequency();
        int rssi = info.getRssi();
        if (bssid == null || "02:00:00:00:00:00".equals(bssid) || freq <= 0 || rssi >= 0 || rssi < -127) return null;
        String ssid = cleanSsid(info.getSSID());
        int width = 20;
        for (WifiSample s : latestScan) {
            if (bssid.equalsIgnoreCase(s.bssid)) {
                width = s.widthMhz;
                if (ssid.equals("<oculta>") || ssid.equals("<unknown ssid>")) ssid = s.ssid;
                break;
            }
        }
        return new WifiSample(ssid, bssid, freq, rssi, width);
    }

    private List<WifiSample> mergeConnected(List<WifiSample> base, WifiSample connected) {
        ArrayList<WifiSample> out = new ArrayList<>();
        boolean replaced = false;
        for (WifiSample s : base) {
            if (connected != null && s.bssid != null && s.bssid.equalsIgnoreCase(connected.bssid)) {
                out.add(connected);
                replaced = true;
            } else out.add(s);
        }
        if (connected != null && !replaced) out.add(connected);
        return out;
    }

    private void addImmediateHybridFrame() {
        WifiSample connected = readConnectedSample();
        List<WifiSample> hybrid = mergeConnected(latestScan, connected);
        if (!hybrid.isEmpty()) spectrogram.addFrame(hybrid, System.currentTimeMillis());
    }

    private void updateStats() {
        if (stats == null || spectrogram == null) return;
        int band = spectrogram.getBand();
        int count = 0;
        int strongest = -200;
        String strongestName = "—";
        for (WifiSample s : latestScan) {
            if (!inBand(s.frequencyMhz, band)) continue;
            count++;
            if (s.rssiDbm > strongest) {
                strongest = s.rssiDbm;
                strongestName = s.ssid;
            }
        }
        String connectedText = latestConnected == null ? "não conectado/indisponível" : latestConnected.ssid + " • " + latestConnected.rssiDbm + " dBm • " + latestConnected.frequencyMhz + " MHz";
        String lastScan = lastScanFrameMs == 0L ? "—" : new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastScanFrameMs));
        stats.setText("Banda: " + bandName(band) + " • APs do scan: " + count +
                "\nLIVE: " + connectedText +
                "\nColunas: " + spectrogram.getFrameCount() + " • live=" + liveFrames + " • scans=" + scanFrames +
                "\nMais forte no último scan: " + strongestName + (strongest > -200 ? " • " + strongest + " dBm" : "") +
                "\nÚltimo scan novo: " + lastScan);
    }

    private void renderNetworkList() {
        if (networks == null) return;
        if (latestScan.isEmpty()) {
            networks.setText("Nenhuma rede retornada pelo Android ainda.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(18, latestScan.size());
        for (int i = 0; i < n; i++) {
            WifiSample s = latestScan.get(i);
            sb.append(String.format(Locale.US, "%2d  %4d dBm  %4d MHz  %3d MHz  %s\n", i + 1, s.rssiDbm, s.frequencyMhz, s.widthMhz, trim(s.ssid, 20)));
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

    private static String cleanSsid(String value) {
        if (value == null || value.trim().isEmpty()) return "<oculta>";
        String s = value.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    private TextView card(String value) {
        TextView v = text(value, 12, Color.rgb(225, 234, 240), false);
        v.setPadding(dp(10), dp(9), dp(10), dp(9));
        v.setBackgroundColor(Color.rgb(27, 37, 44));
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = match(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) { return margin(l, t, r, b); }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); p.setMargins(dp(2), 0, dp(2), 0); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
