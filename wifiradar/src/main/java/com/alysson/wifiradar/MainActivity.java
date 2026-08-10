package com.alysson.wifiradar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_PERMS = 50;
    private WifiManager wifi;
    private SensorManager sensors;
    private Sensor rotation;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView targetInfo;
    private TextView directionInfo;
    private TextView networksInfo;
    private Spinner targetSpinner;
    private RadarView radar;
    private SpectrumView spectrum;
    private Button huntButton;

    private final List<Target> targets = new ArrayList<>();
    private List<ScanResult> lastResults = new ArrayList<>();
    private String selectedBssid;
    private boolean hunting = false;
    private boolean receiverRegistered = false;
    private float heading = 0f;
    private long lastLiveSample = 0L;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            readScanResults(true);
        }
    };

    private final Runnable livePoll = new Runnable() {
        @Override public void run() {
            if (hasWifiPermissions()) pollConnectedSignal();
            updateDirectionText();
            handler.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        setContentView(buildUi());
        requestNeededPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(scanReceiver, f);
        receiverRegistered = true;
        if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        handler.post(livePoll);
        if (hasWifiPermissions()) readScanResults(false);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(livePoll);
        sensors.unregisterListener(this);
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 9, 12));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);

        TextView title = text("Wi‑Fi Hunter Radar", 27, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, match());
        TextView sub = text("Radar por RSSI + bússola • espectro 2,4 / 5 / 6 GHz", 12, Color.rgb(101, 206, 255));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(3), 0, dp(12));
        root.addView(sub, match());

        status = card("Verificando permissões e Wi‑Fi…");
        root.addView(status, matchMargin(0, 0, 0, 8));

        Button permission = button("PERMISSÕES / LOCALIZAÇÃO");
        permission.setOnClickListener(v -> ensureReady());
        root.addView(permission, matchMargin(0, 0, 0, 7));

        Button scan = button("VARREDURA WI‑FI AGORA");
        scan.setOnClickListener(v -> requestScan());
        root.addView(scan, matchMargin(0, 0, 0, 10));

        label(root, "REDE PARA CAÇAR");
        targetSpinner = new Spinner(this);
        targetSpinner.setBackgroundColor(Color.rgb(34, 44, 52));
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < targets.size()) {
                    selectedBssid = targets.get(pos).bssid;
                    radar.clearSamples();
                    spectrum.setData(lastResults, selectedBssid);
                    renderTarget(targets.get(pos), null);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        root.addView(targetSpinner, matchMargin(0, 0, 0, 8));

        targetInfo = text("Selecione uma rede.", 13, Color.rgb(205, 220, 230));
        root.addView(targetInfo, matchMargin(0, 0, 0, 7));

        huntButton = button("INICIAR RADAR 360°");
        huntButton.setOnClickListener(v -> toggleHunt());
        root.addView(huntButton, matchMargin(0, 0, 0, 8));

        Button clear = button("LIMPAR MAPA DIRECIONAL");
        clear.setOnClickListener(v -> { radar.clearSamples(); updateDirectionText(); });
        root.addView(clear, matchMargin(0, 0, 0, 8));

        radar = new RadarView(this);
        root.addView(radar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(390)));

        directionInfo = card("Aponte o topo do S21 para frente e gire lentamente 360°.");
        root.addView(directionInfo, matchMargin(0, 8, 0, 12));

        label(root, "ESPECTRO / OCUPAÇÃO DOS CANAIS");
        spectrum = new SpectrumView(this);
        root.addView(spectrum, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(350)));

        networksInfo = text("Nenhuma varredura ainda.", 12, Color.rgb(185, 199, 210));
        networksInfo.setPadding(dp(6), dp(10), dp(6), dp(10));
        root.addView(networksInfo, match());

        TextView note = text("Como usar o radar: escolha de preferência a rede em que o S21 está conectado, toque em INICIAR RADAR e faça uma volta lenta. A seta amarela marca o azimute com RSSI mais forte. Paredes, reflexões e a posição da sua mão alteram o resultado; faça 2–3 voltas para confirmar.", 12, Color.rgb(255, 210, 118));
        note.setPadding(dp(10), dp(12), dp(10), dp(12));
        note.setBackgroundColor(Color.rgb(43, 34, 18));
        root.addView(note, matchMargin(0, 8, 0, 0));
        return scroll;
    }

    private void toggleHunt() {
        if (!hasWifiPermissions()) { requestNeededPermissions(); return; }
        if (selectedBssid == null) { toast("Selecione uma rede primeiro."); return; }
        hunting = !hunting;
        if (hunting) {
            radar.clearSamples();
            huntButton.setText("PARAR RADAR");
            status.setText("Radar ativo — gire lentamente o S21 360°.");
        } else {
            huntButton.setText("INICIAR RADAR 360°");
            status.setText("Radar parado. O mapa foi mantido.");
        }
    }

    private void ensureReady() {
        if (!hasWifiPermissions()) { requestNeededPermissions(); return; }
        if (!isLocationEnabled()) {
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            catch (Exception e) { toast("Ative Localização nas Configurações."); }
            return;
        }
        requestScan();
    }

    private void requestNeededPermissions() {
        ArrayList<String> req = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), REQ_PERMS);
        else refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            refreshStatus();
            if (hasWifiPermissions()) readScanResults(false);
        }
    }

    private boolean hasWifiPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private void refreshStatus() {
        if (!hasWifiPermissions()) status.setText("Permissão necessária: Wi‑Fi por perto + Localização precisa.");
        else if (!isLocationEnabled()) status.setText("Ative o serviço Localização para o Android liberar resultados de scan Wi‑Fi.");
        else status.setText(rotation == null ? "Wi‑Fi pronto. Sensor de orientação não encontrado; espectro funciona, radar direcional não." : "Pronto. Faça uma varredura e escolha a rede.");
    }

    @SuppressLint("MissingPermission")
    private void requestScan() {
        if (!hasWifiPermissions()) { requestNeededPermissions(); return; }
        if (!isLocationEnabled()) { ensureReady(); return; }
        boolean accepted;
        try { accepted = wifi.startScan(); }
        catch (SecurityException e) { accepted = false; }
        status.setText(accepted ? "Varredura solicitada…" : "O Android recusou um novo scan agora (possível limite de varreduras). Usando resultados recentes.");
        readScanResults(false);
    }

    @SuppressLint("MissingPermission")
    private void readScanResults(boolean fromFreshScan) {
        if (!hasWifiPermissions()) return;
        List<ScanResult> list;
        try { list = wifi.getScanResults(); }
        catch (SecurityException e) { status.setText("Android bloqueou getScanResults: verifique permissões e Localização."); return; }
        if (list == null) list = new ArrayList<>();
        list = new ArrayList<>(list);
        Collections.sort(list, Comparator.comparingInt((ScanResult x) -> x.level).reversed());
        lastResults = list;
        rebuildTargets();
        spectrum.setData(lastResults, selectedBssid);
        renderNetworks();
        if (fromFreshScan && hunting) sampleSelectedFromScan();
        if (fromFreshScan) status.setText("Scan atualizado: " + list.size() + " pontos de acesso visíveis.");
    }

    private void rebuildTargets() {
        String keep = selectedBssid;
        Map<String, Target> unique = new LinkedHashMap<>();
        for (ScanResult s : lastResults) {
            if (s.BSSID == null) continue;
            Target old = unique.get(s.BSSID);
            if (old == null || s.level > old.rssi) unique.put(s.BSSID, new Target(s));
        }
        targets.clear();
        targets.addAll(unique.values());
        List<String> labels = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            labels.add(t.display());
            if (keep != null && keep.equalsIgnoreCase(t.bssid)) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        targetSpinner.setAdapter(adapter);
        if (selected >= 0) targetSpinner.setSelection(selected);
        else if (!targets.isEmpty()) targetSpinner.setSelection(0);
    }

    @SuppressLint("MissingPermission")
    private void pollConnectedSignal() {
        WifiInfo info;
        try { info = wifi.getConnectionInfo(); } catch (Exception e) { return; }
        if (info == null || selectedBssid == null || info.getBSSID() == null) return;
        if (!selectedBssid.equalsIgnoreCase(info.getBSSID())) return;
        int rssi = info.getRssi();
        Target target = findTarget(selectedBssid);
        if (target != null) renderTarget(target, rssi);
        if (hunting && System.currentTimeMillis() - lastLiveSample >= 350) {
            radar.addSample(heading, rssi);
            lastLiveSample = System.currentTimeMillis();
        }
    }

    private void sampleSelectedFromScan() {
        Target t = findTarget(selectedBssid);
        if (t != null) {
            radar.addSample(heading, t.rssi);
            renderTarget(t, t.rssi);
        }
    }

    private Target findTarget(String bssid) {
        if (bssid == null) return null;
        for (Target t : targets) if (bssid.equalsIgnoreCase(t.bssid)) return t;
        return null;
    }

    private void renderTarget(Target t, Integer liveRssi) {
        int rssi = liveRssi == null ? t.rssi : liveRssi;
        int quality = quality(rssi);
        targetInfo.setText(t.ssid + "\nBSSID " + t.bssid + " • Canal " + channel(t.frequency) + " • " + t.frequency + " MHz\nSinal " + rssi + " dBm • " + quality + "%" + (liveRssi != null ? " • AO VIVO" : " • último scan"));
    }

    private void updateDirectionText() {
        if (radar == null) return;
        float best = radar.getBestBearing();
        if (Float.isNaN(best)) {
            directionInfo.setText("Amostras: " + radar.getTotalSamples() + " • cobertura: " + radar.getCoveredBins() + "/72 setores\nGire lentamente e mantenha o topo do telefone apontando para fora.");
            return;
        }
        float delta = normalizeSigned(best - heading);
        String turn;
        if (Math.abs(delta) < 8) turn = "APONTE PARA FRENTE";
        else if (delta > 0) turn = "gire " + Math.round(delta) + "° para a DIREITA";
        else turn = "gire " + Math.round(-delta) + "° para a ESQUERDA";
        directionInfo.setText(String.format(Locale.US, "Direção mais forte: %.0f° magnético • %s\nConfiança de varredura: %d%% • amostras: %d • setores: %d/72", best, turn, radar.getConfidencePercent(), radar.getTotalSamples(), radar.getCoveredBins()));
    }

    private void renderNetworks() {
        StringBuilder sb = new StringBuilder("REDES MAIS FORTES\n");
        int n = Math.min(16, lastResults.size());
        for (int i = 0; i < n; i++) {
            ScanResult s = lastResults.get(i);
            String ssid = s.SSID == null || s.SSID.isEmpty() ? "<rede oculta>" : s.SSID;
            sb.append(String.format(Locale.US, "%2d. %-22s %4d dBm   ch %-3d   %d MHz\n", i + 1, trim(ssid, 22), s.level, channel(s.frequency), s.frequency));
        }
        networksInfo.setText(sb.toString());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] R = new float[9];
        float[] orient = new float[3];
        SensorManager.getRotationMatrixFromVector(R, event.values);
        SensorManager.getOrientation(R, orient);
        heading = (float)Math.toDegrees(orient[0]);
        if (heading < 0) heading += 360f;
        radar.setHeading(heading);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static int quality(int rssi) {
        if (rssi <= -100) return 0;
        if (rssi >= -40) return 100;
        return Math.round((rssi + 100) * 100f / 60f);
    }

    private static int channel(int f) {
        if (f == 2484) return 14;
        if (f >= 2412 && f <= 2472) return (f - 2407) / 5;
        if (f >= 5000 && f < 5925) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;
        return 0;
    }

    private static float normalizeSigned(float d) {
        d = (d + 540f) % 360f - 180f;
        return d;
    }

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    private void label(LinearLayout root, String s) {
        TextView v = text(s, 12, Color.rgb(110, 205, 255));
        v.setPadding(0, dp(10), 0, dp(5));
        root.addView(v, match());
    }

    private TextView card(String s) {
        TextView v = text(s, 13, Color.rgb(224, 232, 238));
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundColor(Color.rgb(28, 37, 43));
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams matchMargin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = match(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static final class Target {
        final String ssid, bssid;
        final int frequency, rssi;
        Target(ScanResult s) {
            ssid = s.SSID == null || s.SSID.isEmpty() ? "<rede oculta>" : s.SSID;
            bssid = s.BSSID;
            frequency = s.frequency;
            rssi = s.level;
        }
        String display() {
            String tail = bssid != null && bssid.length() >= 5 ? bssid.substring(bssid.length() - 5) : bssid;
            return ssid + "  •  " + rssi + " dBm  •  " + frequency + " MHz  •  " + tail;
        }
    }
}
