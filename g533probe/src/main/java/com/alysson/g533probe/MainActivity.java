package com.alysson.g533probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 533;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seenBt = new HashSet<>();
    private final Set<String> seenBle = new HashSet<>();

    private TextView status;
    private TextView logView;
    private Spinner rateSpinner;
    private Spinner bitsSpinner;
    private Spinner channelsSpinner;
    private Spinner outputSpinner;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private AudioManager audioManager;
    private UsbManager usbManager;
    private final List<AudioDeviceInfo> outputDevices = new ArrayList<>();

    private boolean receiversRegistered = false;
    private AudioTrack toneTrack;
    private Thread toneThread;
    private volatile boolean toneRunning = false;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) name = safeBtName(d);
            String key = d.getAddress() + "|" + name;
            if (seenBle.add(key)) {
                int rssi = result.getRssi();
                log("BLE  " + markG533(name) + safe(name) + "  " + d.getAddress() + "  RSSI=" + rssi + " dBm");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("BLE: falha de scan, código=" + errorCode);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d;
                if (Build.VERSION.SDK_INT >= 33) {
                    d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (d != null) {
                    String name = safeBtName(d);
                    String key = d.getAddress() + "|" + name;
                    if (seenBt.add(key)) {
                        short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                        log("BT   " + markG533(name) + safe(name) + "  " + d.getAddress() + "  RSSI=" + rssi + " dBm");
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                log("Bluetooth Classic: varredura concluída.");
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestWifiPeers();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bm != null ? bm.getAdapter() : null;
        audioManager = getSystemService(AudioManager.class);
        usbManager = getSystemService(UsbManager.class);
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            wifiP2pChannel = wifiP2pManager.initialize(this, getMainLooper(), null);
        }
        buildUi();
        requestPermissionsIfNeeded();
        refreshAudioDevices();
        scanUsb();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerAppReceivers();
    }

    @Override
    protected void onStop() {
        stopScans();
        stopTone();
        unregisterAppReceivers();
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = text("G533 Probe — S21", 24f);
        root.addView(title);
        TextView subtitle = text("Scanner Bluetooth/BLE/Wi‑Fi Direct + diagnóstico de áudio. Procura Logitech G533 e mostra capacidades reais expostas pelo Android.", 14f);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        status = text("Pronto.", 15f);
        root.addView(status);

        Button perms = button("Conceder permissões", v -> requestPermissionsIfNeeded());
        Button scanBt = button("Varrer Bluetooth + BLE", v -> scanBluetooth());
        Button scanWifi = button("Varrer Wi‑Fi Direct", v -> scanWifiDirect());
        Button scanUsb = button("Verificar USB / IDs G533", v -> scanUsb());
        Button audio = button("Atualizar dispositivos de áudio", v -> refreshAudioDevices());
        root.addView(perms);
        root.addView(scanBt);
        root.addView(scanWifi);
        root.addView(scanUsb);
        root.addView(audio);

        root.addView(section("Teste de áudio PCM"));
        root.addView(text("Taxa solicitada ao Android. O sistema pode reamostrar se o dispositivo não aceitar a taxa diretamente.", 12f));

        rateSpinner = spinner(new String[]{"44100 Hz", "48000 Hz", "96000 Hz", "192000 Hz"});
        bitsSpinner = spinner(new String[]{"16-bit PCM", "32-bit float"});
        channelsSpinner = spinner(new String[]{"Stereo", "Mono"});
        outputSpinner = spinner(new String[]{"Padrão do Android"});
        root.addView(label("Sample rate"));
        root.addView(rateSpinner);
        root.addView(label("Profundidade"));
        root.addView(bitsSpinner);
        root.addView(label("Canais"));
        root.addView(channelsSpinner);
        root.addView(label("Saída preferida"));
        root.addView(outputSpinner);

        Button play = button("Tocar tom 1 kHz", v -> startTone());
        Button stop = button("Parar tom", v -> stopTone());
        root.addView(play);
        root.addView(stop);

        root.addView(section("Resultados"));
        logView = text("", 12f);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        Button clear = button("Limpar log", v -> logView.setText(""));
        root.addView(clear);

        setContentView(scroll);
    }

    private void registerAppReceivers() {
        if (receiversRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        receiversRegistered = true;
    }

    private void unregisterAppReceivers() {
        if (!receiversRegistered) return;
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        receiversRegistered = false;
    }

    private void requestPermissionsIfNeeded() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> missing = new ArrayList<>();
        for (String x : p) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) missing.add(x);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        else status.setText("Permissões principais concedidas.");
    }

    private boolean btScanAllowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean btConnectAllowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanBluetooth() {
        if (bluetoothAdapter == null) {
            log("Bluetooth: adaptador não disponível.");
            return;
        }
        if (!btScanAllowed() || !btConnectAllowed()) {
            log("Bluetooth: conceda as permissões de Dispositivos próximos.");
            requestPermissionsIfNeeded();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            log("Bluetooth está desligado. Ligue-o nas configurações e tente novamente.");
            return;
        }
        seenBt.clear();
        seenBle.clear();
        log("=== Bluetooth / BLE ===");
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                String n = safeBtName(d);
                log("PAIR " + markG533(n) + safe(n) + "  " + d.getAddress());
            }
        } catch (SecurityException e) {
            log("Pareados: permissão negada: " + e.getMessage());
        }
        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
            boolean started = bluetoothAdapter.startDiscovery();
            log("Bluetooth Classic startDiscovery=" + started);
        } catch (SecurityException e) {
            log("Classic: " + e.getMessage());
        }
        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner != null) {
                ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                bleScanner.startScan(null, settings, scanCallback);
                log("BLE: scan LOW_LATENCY iniciado por 12 s.");
                handler.postDelayed(this::stopBleScan, 12000);
            } else log("BLE scanner indisponível.");
        } catch (SecurityException e) {
            log("BLE: " + e.getMessage());
        }
    }

    private void stopBleScan() {
        if (bleScanner != null && btScanAllowed()) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        bleScanner = null;
    }

    private void stopScans() {
        stopBleScan();
        if (bluetoothAdapter != null && btScanAllowed()) {
            try { if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery(); } catch (Exception ignored) {}
        }
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            try { wifiP2pManager.stopPeerDiscovery(wifiP2pChannel, null); } catch (Exception ignored) {}
        }
    }

    private String safeBtName(BluetoothDevice d) {
        if (d == null || !btConnectAllowed()) return null;
        try { return d.getName(); } catch (Exception e) { return null; }
    }

    private void scanWifiDirect() {
        log("=== Wi‑Fi Direct ===");
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            log("Wi‑Fi Direct não disponível neste dispositivo.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            log("Wi‑Fi Direct: falta permissão NEARBY_WIFI_DEVICES.");
            requestPermissionsIfNeeded();
            return;
        }
        try {
            wifiP2pManager.discoverPeers(wifiP2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { log("Wi‑Fi Direct: descoberta iniciada."); }
                @Override public void onFailure(int reason) { log("Wi‑Fi Direct: falha reason=" + reason); }
            });
        } catch (SecurityException e) {
            log("Wi‑Fi Direct: " + e.getMessage());
        }
    }

    private void requestWifiPeers() {
        if (wifiP2pManager == null || wifiP2pChannel == null) return;
        try {
            wifiP2pManager.requestPeers(wifiP2pChannel, peers -> {
                log("Wi‑Fi Direct: " + peers.getDeviceList().size() + " peer(s).");
                for (WifiP2pDevice d : peers.getDeviceList()) {
                    log("P2P  " + markG533(d.deviceName) + safe(d.deviceName) + "  " + d.deviceAddress + " status=" + d.status);
                }
            });
        } catch (SecurityException e) {
            log("requestPeers: " + e.getMessage());
        }
    }

    private void scanUsb() {
        log("=== USB ===");
        if (usbManager == null) {
            log("USB Manager indisponível.");
            return;
        }
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) log("Nenhum dispositivo USB conectado.");
        for (UsbDevice d : devices.values()) {
            int vid = d.getVendorId();
            int pid = d.getProductId();
            boolean g533 = vid == 0x046D && (pid == 0x0A66 || pid == 0x0A67);
            String tag = g533 ? "<<< G533 >>> " : "";
            log(String.format(Locale.US, "USB  %sVID=%04X PID=%04X  %s", tag, vid, pid, safe(d.getProductName())));
        }
        log("Alvos conhecidos: receptor 046D:0A66 | headset/charger 046D:0A67.");
    }

    private void refreshAudioDevices() {
        log("=== Áudio Android ===");
        outputDevices.clear();
        List<String> labels = new ArrayList<>();
        labels.add("Padrão do Android");
        if (audioManager != null) {
            AudioDeviceInfo[] all = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo d : all) {
                String dir = d.isSource() && d.isSink() ? "IN/OUT" : d.isSink() ? "OUT" : "IN";
                String name = String.valueOf(d.getProductName());
                log("AUDIO " + dir + "  " + audioType(d.getType()) + "  " + markG533(name) + name
                        + " rates=" + arr(d.getSampleRates())
                        + " channels=" + arr(d.getChannelCounts())
                        + " enc=" + arr(d.getEncodings()));
                if (d.isSink()) {
                    outputDevices.add(d);
                    labels.add(audioType(d.getType()) + " — " + name);
                }
            }
            String sr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String fpb = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            log("Saída primária Android: sampleRate=" + sr + " Hz, framesPerBuffer=" + fpb);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outputSpinner.setAdapter(adapter);
    }

    private void startTone() {
        stopTone();
        int rate = Integer.parseInt(rateSpinner.getSelectedItem().toString().split(" ")[0]);
        boolean floatPcm = bitsSpinner.getSelectedItemPosition() == 1;
        boolean stereo = channelsSpinner.getSelectedItemPosition() == 0;
        int bits = floatPcm ? 32 : 16;
        int channels = stereo ? 2 : 1;
        int encoding = floatPcm ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
        int mask = stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        long pcmBitrate = (long) rate * bits * channels;
        log("Teste: " + rate + " Hz, " + bits + " bit, " + channels + " canal(is), PCM=" + pcmBitrate + " bit/s.");
        try {
            int min = AudioTrack.getMinBufferSize(rate, mask, encoding);
            if (min <= 0) throw new IllegalStateException("Configuração não suportada por AudioTrack; minBuffer=" + min);
            int buffer = Math.max(min, rate * channels * (bits / 8) / 10);
            AudioFormat fmt = new AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(mask).build();
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            toneTrack = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
            int selected = outputSpinner.getSelectedItemPosition();
            if (selected > 0 && selected - 1 < outputDevices.size()) {
                AudioDeviceInfo dev = outputDevices.get(selected - 1);
                boolean ok = toneTrack.setPreferredDevice(dev);
                log("Saída preferida: " + dev.getProductName() + " setPreferredDevice=" + ok);
            }
            toneTrack.play();
            toneRunning = true;
            toneThread = new Thread(() -> generateTone(rate, stereo, floatPcm), "G533Tone");
            toneThread.start();
            status.setText("Tom 1 kHz ativo em " + rate + " Hz.");
        } catch (Exception e) {
            log("Falha ao iniciar áudio: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopTone();
        }
    }

    private void generateTone(int rate, boolean stereo, boolean floatPcm) {
        int channels = stereo ? 2 : 1;
        int frames = 1024;
        double phase = 0;
        double step = 2.0 * Math.PI * 1000.0 / rate;
        try {
            if (floatPcm) {
                float[] b = new float[frames * channels];
                while (toneRunning && toneTrack != null) {
                    for (int f = 0; f < frames; f++) {
                        float s = (float) (Math.sin(phase) * 0.12);
                        phase += step;
                        for (int c = 0; c < channels; c++) b[f * channels + c] = s;
                    }
                    toneTrack.write(b, 0, b.length, AudioTrack.WRITE_BLOCKING);
                }
            } else {
                short[] b = new short[frames * channels];
                while (toneRunning && toneTrack != null) {
                    for (int f = 0; f < frames; f++) {
                        short s = (short) (Math.sin(phase) * 3800);
                        phase += step;
                        for (int c = 0; c < channels; c++) b[f * channels + c] = s;
                    }
                    toneTrack.write(b, 0, b.length, AudioTrack.WRITE_BLOCKING);
                }
            }
        } catch (Exception e) {
            handler.post(() -> log("Tone thread: " + e.getMessage()));
        }
    }

    private void stopTone() {
        toneRunning = false;
        AudioTrack t = toneTrack;
        toneTrack = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
        if (toneThread != null) {
            try { toneThread.interrupt(); } catch (Exception ignored) {}
            toneThread = null;
        }
        if (status != null) status.setText("Pronto.");
    }

    private String audioType(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB Audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired Headphones";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in Mic";
            default: return "type=" + t;
        }
    }

    private String markG533(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT);
        return (n.contains("g533") || n.contains("logitech")) ? "<<< POSSÍVEL G533 >>> " : "";
    }

    private String arr(int[] a) {
        return a == null || a.length == 0 ? "[não informado]" : Arrays.toString(a);
    }

    private String safe(String s) { return s == null || s.isEmpty() ? "(sem nome)" : s; }

    private void log(String s) {
        String tm = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        handler.post(() -> logView.append("[" + tm + "] " + s + "\n"));
    }

    private TextView text(String s, float sp) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        return v;
    }

    private TextView label(String s) {
        TextView v = text(s, 13f);
        v.setPadding(0, dp(8), 0, dp(2));
        return v;
    }

    private TextView section(String s) {
        TextView v = text(s, 18f);
        v.setPadding(0, dp(16), 0, dp(6));
        return v;
    }

    private Spinner spinner(String[] items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
}
