package com.alysson.g533probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
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
import android.hardware.usb.UsbConstants;
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
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 533;

    // Perfil extraído do firmware Logitech G533 v1.13.
    private static final int LOGITECH_VID = 0x046D;
    private static final int G533_RECEIVER_PID = 0x0A66;
    private static final int G533_HEADSET_USB_PID = 0x0A67;
    private static final int AVNERA_BOOT_VID = 0x170D;
    private static final int AVNERA_DONGLE_BOOT_PID = 0x0100;
    private static final int AVNERA_HEADSET_BOOT_PID = 0x0101;
    private static final String FW_CLIENT_SHA256 =
            "c3624e58de9f93414ed94c0ab3690ccef35a82dbb525cf600b00ac5b68e67ba3";
    private static final String FW_HOST_SHA256 =
            "94f705f835e31f902dfbec77ef872c0fab5cf5339ccf756baa49173d85d5e7a2";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seenBt = new HashSet<>();
    private final Set<String> seenBle = new HashSet<>();
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    private TextView status;
    private TextView logView;
    private TextView firmwareView;
    private TextView selectedTargetView;
    private LinearLayout candidateContainer;
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
    private Candidate selectedTarget;

    private static final class Candidate {
        String key;
        String transport;
        String name;
        String address;
        int score;
        String evidence;
        boolean exactG533;
        AudioDeviceInfo audioDevice;

        Candidate(String key, String transport, String name, String address,
                  int score, String evidence, boolean exactG533, AudioDeviceInfo audioDevice) {
            this.key = key;
            this.transport = transport;
            this.name = name;
            this.address = address;
            this.score = score;
            this.evidence = evidence;
            this.exactG533 = exactG533;
            this.audioDevice = audioDevice;
        }
    }

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
                boolean audioClass = isBluetoothAudioClass(d);
                int score = scoreCandidate("BLE", name, audioClass, false, false, rssi);
                String evidence = buildEvidence(name, audioClass, false, false, rssi,
                        "BLE detectado pelo Android");
                addCandidate("BLE:" + d.getAddress(), "Bluetooth LE", name, d.getAddress(),
                        score, evidence, false, null);
                log("BLE  " + markG533(name) + safe(name) + "  " + d.getAddress()
                        + "  RSSI=" + rssi + " dBm  similaridade=" + score + "%");
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
                        boolean audioClass = isBluetoothAudioClass(d);
                        int score = scoreCandidate("BT", name, audioClass, false, false, rssi);
                        String evidence = buildEvidence(name, audioClass, false, false, rssi,
                                "Bluetooth Classic detectado pelo Android");
                        addCandidate("BT:" + d.getAddress(), "Bluetooth Classic", name, d.getAddress(),
                                score, evidence, false, null);
                        log("BT   " + markG533(name) + safe(name) + "  " + d.getAddress()
                                + "  RSSI=" + rssi + " dBm  similaridade=" + score + "%");
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
        loadFirmwareProfile();
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

        TextView title = text("G533 Target Selector — S21", 24f);
        root.addView(title);

        TextView subtitle = text(
                "Usa o perfil extraído dos firmwares G533 V1.13 para ranquear dispositivos "
                        + "que o Android consegue enxergar. Toque em um candidato para selecioná-lo.",
                14f);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        status = text("Pronto.", 15f);
        root.addView(status);

        root.addView(section("Perfil do alvo"));
        firmwareView = text("Carregando perfil do firmware...", 12f);
        firmwareView.setTextIsSelectable(true);
        root.addView(firmwareView);

        Button perms = button("Conceder permissões", v -> requestPermissionsIfNeeded());
        Button scanAll = button("VARREDURA COMPLETA G533", v -> scanAll());
        Button scanBt = button("Varrer Bluetooth + BLE", v -> scanBluetooth());
        Button scanWifi = button("Varrer Wi-Fi Direct", v -> scanWifiDirect());
        Button scanUsb = button("Verificar USB / IDs do firmware", v -> scanUsb());
        Button audio = button("Atualizar dispositivos de áudio", v -> refreshAudioDevices());
        root.addView(perms);
        root.addView(scanAll);
        root.addView(scanBt);
        root.addView(scanWifi);
        root.addView(scanUsb);
        root.addView(audio);

        root.addView(section("Dispositivos candidatos"));
        root.addView(text(
                "Pontuação = semelhança lógica com o perfil G533. 100% significa assinatura exata "
                        + "por nome/USB; pontuações menores indicam apenas semelhança de classe de áudio/transporte.",
                12f));
        candidateContainer = new LinearLayout(this);
        candidateContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(candidateContainer);
        renderCandidates();

        root.addView(section("Alvo selecionado"));
        selectedTargetView = text("Nenhum dispositivo selecionado.", 13f);
        selectedTargetView.setTextIsSelectable(true);
        root.addView(selectedTargetView);

        Button bluetoothSettings = button("Abrir Bluetooth do Android", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                log("Não foi possível abrir Bluetooth: " + e.getMessage());
            }
        });
        Button evaluate = button("Avaliar modo 'celular como dongle'", v -> evaluateSelectedTarget());
        root.addView(bluetoothSettings);
        root.addView(evaluate);

        root.addView(section("Teste de áudio PCM"));
        root.addView(text(
                "Se o alvo selecionado já aparecer ao Android como saída de áudio, o teste tenta "
                        + "rotear o AudioTrack diretamente para ele. Caso contrário usa a saída escolhida abaixo.",
                12f));

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

        Button play = button("Tocar tom 1 kHz no alvo/saída", v -> startTone());
        Button stop = button("Parar tom", v -> stopTone());
        root.addView(play);
        root.addView(stop);

        root.addView(section("Log técnico"));
        logView = text("", 12f);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        Button clear = button("Limpar log", v -> logView.setText(""));
        root.addView(clear);

        setContentView(scroll);
    }

    private void loadFirmwareProfile() {
        String assetText = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("g533_firmware_profile.json")))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            assetText = sb.toString().trim();
        } catch (Exception e) {
            log("Perfil asset não pôde ser lido: " + e.getMessage());
        }

        String summary =
                "Headset A-00072 | client V1.13 | USB 046D:0A67 | boot 170D:0101\n"
                        + "Receiver A-00073 | host V1.13 | USB 046D:0A66 | boot 170D:0100\n"
                        + "client SHA-256: " + FW_CLIENT_SHA256 + "\n"
                        + "host   SHA-256: " + FW_HOST_SHA256 + "\n"
                        + "RF alvo: Logitech/Avnera 2.4 GHz proprietário";
        if (assetText != null && assetText.contains(FW_CLIENT_SHA256)
                && assetText.contains(FW_HOST_SHA256)) {
            summary += "\nPerfil de firmware interno: VERIFICADO.";
        } else {
            summary += "\nPerfil de firmware interno: usando constantes compiladas.";
        }
        firmwareView.setText(summary);
    }

    private void scanAll() {
        stopScans();
        candidates.clear();
        selectedTarget = null;
        selectedTargetView.setText("Nenhum dispositivo selecionado.");
        renderCandidates();
        log("=== NOVA VARREDURA COMPLETA G533 ===");
        refreshAudioDevices();
        scanUsb();
        scanBluetooth();
        scanWifiDirect();
        status.setText("Varredura G533 em andamento...");
        handler.postDelayed(() -> {
            renderCandidates();
            status.setText("Varredura concluída. Selecione um candidato.");
        }, 12500);
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
        for (String x : p) {
            if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) missing.add(x);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        else status.setText("Permissões principais concedidas.");
    }

    private boolean btScanAllowed() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean btConnectAllowed() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
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
                boolean audioClass = isBluetoothAudioClass(d);
                int score = scoreCandidate("BT", n, audioClass, false, false, 0);
                addCandidate("PAIR:" + d.getAddress(), "Bluetooth pareado", n, d.getAddress(),
                        Math.min(100, score + 8),
                        buildEvidence(n, audioClass, false, false, 0, "Dispositivo já pareado"),
                        false, null);
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
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                bleScanner.startScan(null, settings, scanCallback);
                log("BLE: scan LOW_LATENCY iniciado por 12 s.");
                handler.postDelayed(this::stopBleScan, 12000);
            } else {
                log("BLE scanner indisponível.");
            }
        } catch (SecurityException e) {
            log("BLE: " + e.getMessage());
        }
    }

    private boolean isBluetoothAudioClass(BluetoothDevice d) {
        if (d == null || !btConnectAllowed()) return false;
        try {
            BluetoothClass bc = d.getBluetoothClass();
            return bc != null
                    && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (Exception e) {
            return false;
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
            try {
                if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
            } catch (Exception ignored) {}
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
        log("=== Wi-Fi Direct ===");
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            log("Wi-Fi Direct não disponível neste dispositivo.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            log("Wi-Fi Direct: falta permissão NEARBY_WIFI_DEVICES.");
            requestPermissionsIfNeeded();
            return;
        }
        try {
            wifiP2pManager.discoverPeers(wifiP2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    log("Wi-Fi Direct: descoberta iniciada.");
                }

                @Override public void onFailure(int reason) {
                    log("Wi-Fi Direct: falha reason=" + reason);
                }
            });
        } catch (SecurityException e) {
            log("Wi-Fi Direct: " + e.getMessage());
        }
    }

    private void requestWifiPeers() {
        if (wifiP2pManager == null || wifiP2pChannel == null) return;
        try {
            wifiP2pManager.requestPeers(wifiP2pChannel, peers -> {
                log("Wi-Fi Direct: " + peers.getDeviceList().size() + " peer(s).");
                for (WifiP2pDevice d : peers.getDeviceList()) {
                    int score = scoreCandidate("P2P", d.deviceName, false, false, false, 0);
                    addCandidate("P2P:" + d.deviceAddress, "Wi-Fi Direct",
                            d.deviceName, d.deviceAddress, score,
                            buildEvidence(d.deviceName, false, false, false, 0,
                                    "Peer Wi-Fi Direct; não prova compatibilidade com G533"),
                            false, null);
                    log("P2P  " + markG533(d.deviceName) + safe(d.deviceName)
                            + "  " + d.deviceAddress + " status=" + d.status
                            + " similaridade=" + score + "%");
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
            boolean exactNormal = vid == LOGITECH_VID
                    && (pid == G533_RECEIVER_PID || pid == G533_HEADSET_USB_PID);
            boolean exactBoot = vid == AVNERA_BOOT_VID
                    && (pid == AVNERA_DONGLE_BOOT_PID || pid == AVNERA_HEADSET_BOOT_PID);
            boolean exact = exactNormal || exactBoot;
            boolean usbAudio = d.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO
                    || hasUsbAudioInterface(d);

            String name = d.getProductName();
            int score = scoreCandidate("USB", name, usbAudio, exact, vid == LOGITECH_VID, 0);
            String addr = String.format(Locale.US, "%04X:%04X", vid, pid);
            String evidence = buildEvidence(name, usbAudio, exact, vid == LOGITECH_VID, 0,
                    exact ? "VID/PID exato do perfil do firmware G533"
                            : "Dispositivo USB observado pelo Android");
            addCandidate("USB:" + d.getDeviceId(), "USB", name, addr,
                    score, evidence, exact, null);

            String tag = exact ? "<<< G533/AVNERA EXATO >>> " : "";
            log(String.format(Locale.US,
                    "USB  %sVID=%04X PID=%04X  %s  similaridade=%d%%",
                    tag, vid, pid, safe(name), score));
        }

        log("Perfil: receiver 046D:0A66 | headset 046D:0A67 | boot 170D:0100/0101.");
    }

    private boolean hasUsbAudioInterface(UsbDevice d) {
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void refreshAudioDevices() {
        log("=== Áudio Android ===");
        outputDevices.clear();
        List<String> labels = new ArrayList<>();
        labels.add("Padrão do Android");

        if (audioManager != null) {
            AudioDeviceInfo[] all = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo d : all) {
                String dir = d.isSource() && d.isSink()
                        ? "IN/OUT" : d.isSink() ? "OUT" : "IN";
                String name = String.valueOf(d.getProductName());
                boolean audioLike = d.isSink() || d.isSource();
                int score = scoreCandidate("AUDIO", name, audioLike, false, false, 0);

                String address = "audio-id=" + d.getId() + " type=" + d.getType();
                addCandidate("AUDIO:" + d.getId(), "Áudio Android", name, address,
                        score,
                        buildEvidence(name, audioLike, false, false, 0,
                                "Rota de áudio exposta pelo Android: " + audioType(d.getType())),
                        false,
                        d.isSink() ? d : null);

                log("AUDIO " + dir + "  " + audioType(d.getType()) + "  "
                        + markG533(name) + name
                        + " rates=" + arr(d.getSampleRates())
                        + " channels=" + arr(d.getChannelCounts())
                        + " enc=" + arr(d.getEncodings())
                        + " similaridade=" + score + "%");

                if (d.isSink()) {
                    outputDevices.add(d);
                    labels.add(audioType(d.getType()) + " — " + name);
                }
            }

            String sr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            String fpb = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            log("Saída primária Android: sampleRate=" + sr
                    + " Hz, framesPerBuffer=" + fpb);
        }

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outputSpinner.setAdapter(adapter);
    }

    private int scoreCandidate(String transport, String name, boolean audioLike,
                               boolean exactId, boolean logitechVid, int rssi) {
        int score = 0;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);

        if (exactId) score = 100;
        else {
            if (n.contains("g533")) score += 72;
            if (n.contains("logitech")) score += 28;
            if (n.contains("gaming headset")) score += 20;
            else if (n.contains("headset") || n.contains("headphone")
                    || n.contains("fone") || n.contains("audio")) score += 14;

            if (logitechVid) score += 35;
            if (audioLike) score += 22;

            if ("BT".equals(transport)) score += 8;
            else if ("BLE".equals(transport)) score += 5;
            else if ("USB".equals(transport)) score += 8;
            else if ("AUDIO".equals(transport)) score += 12;
            else if ("P2P".equals(transport)) score += 2;

            if (rssi != 0 && rssi > -60) score += 4;
        }
        return Math.max(0, Math.min(100, score));
    }

    private String buildEvidence(String name, boolean audioLike, boolean exactId,
                                 boolean logitechVid, int rssi, String extra) {
        List<String> parts = new ArrayList<>();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("g533")) parts.add("nome contém G533");
        if (n.contains("logitech")) parts.add("nome contém Logitech");
        if (audioLike) parts.add("classe/rota de áudio");
        if (exactId) parts.add("assinatura exata do firmware");
        if (logitechVid) parts.add("VID Logitech 046D");
        if (rssi != 0) parts.add("RSSI=" + rssi + " dBm");
        if (extra != null && !extra.isEmpty()) parts.add(extra);
        return String.join("; ", parts);
    }

    private void addCandidate(String key, String transport, String name, String address,
                              int score, String evidence, boolean exactG533,
                              AudioDeviceInfo audioDevice) {
        handler.post(() -> {
            Candidate old = candidates.get(key);
            if (old == null) {
                candidates.put(key,
                        new Candidate(key, transport, safe(name), safe(address),
                                score, evidence, exactG533, audioDevice));
            } else {
                if (score > old.score) old.score = score;
                if (name != null && !name.isEmpty()) old.name = name;
                if (evidence != null && !evidence.isEmpty()) old.evidence = evidence;
                old.exactG533 = old.exactG533 || exactG533;
                if (audioDevice != null) old.audioDevice = audioDevice;
            }
            renderCandidates();
        });
    }

    private void renderCandidates() {
        if (candidateContainer == null) return;
        candidateContainer.removeAllViews();

        List<Candidate> list = new ArrayList<>(candidates.values());
        list.sort(Comparator
                .comparingInt((Candidate c) -> c.score).reversed()
                .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER));

        if (list.isEmpty()) {
            TextView empty = text("Nenhum candidato encontrado ainda. Execute a varredura completa.", 13f);
            empty.setPadding(0, dp(8), 0, dp(8));
            candidateContainer.addView(empty);
            return;
        }

        for (Candidate c : list) {
            String badge;
            if (c.score >= 90) badge = "ALVO FORTE";
            else if (c.score >= 60) badge = "PARECIDO";
            else if (c.score >= 30) badge = "ÁUDIO COMPATÍVEL";
            else badge = "BAIXA SEMELHANÇA";

            Button b = button(
                    c.score + "% · " + badge + "\n"
                            + c.transport + " · " + c.name + "\n"
                            + c.address,
                    v -> selectCandidate(c));
            b.setAllCaps(false);
            candidateContainer.addView(b);
        }
    }

    private void selectCandidate(Candidate c) {
        selectedTarget = c;
        String route = c.audioDevice != null
                ? "SIM — o Android expõe uma rota de áudio para este item."
                : "NÃO — este item ainda não é uma saída de áudio do Android.";

        selectedTargetView.setText(
                "SELECIONADO\n"
                        + c.score + "% de similaridade\n"
                        + c.transport + " · " + c.name + "\n"
                        + c.address + "\n"
                        + "Evidências: " + c.evidence + "\n"
                        + "Rota de áudio: " + route);

        status.setText("Alvo selecionado: " + c.name);
        log("ALVO SELECIONADO: " + c.transport + " / " + c.name
                + " / " + c.address + " / score=" + c.score + "%");
    }

    private void evaluateSelectedTarget() {
        if (selectedTarget == null) {
            selectedTargetView.setText("Selecione primeiro um dispositivo da lista.");
            return;
        }

        String result;
        if (selectedTarget.exactG533
                && selectedTarget.transport.startsWith("USB")) {
            result = "Assinatura G533/Avnera exata detectada por USB. "
                    + "Neste caso o S21 pode atuar como HOST USB e o app pode conversar com "
                    + "a interface USB real, mas isso ainda não transforma o rádio interno do S21 "
                    + "no transceptor 2.4 GHz Avnera.";
        } else if (selectedTarget.audioDevice != null) {
            result = "Este alvo já é uma saída reconhecida pelo Android. "
                    + "O app consegue direcionar o AudioTrack para ele e testar sample rate/PCM.";
        } else if (selectedTarget.transport.contains("Bluetooth")) {
            result = "O dispositivo é visível por Bluetooth, mas não há rota de áudio ativa. "
                    + "Selecione-o nas configurações Bluetooth e pareie/conecte. "
                    + "Se virar AudioDeviceInfo, o app poderá roteá-lo.";
        } else {
            result = "O Android consegue enxergar este dispositivo, mas não expõe um canal de áudio "
                    + "ou um PHY compatível para tratá-lo como receptor G533. A seleção permanece útil "
                    + "para comparar sinais e identificar o alvo.";
        }

        selectedTargetView.append("\n\nMODO DONGLE:\n" + result);
        log("Avaliação do alvo: " + result);
    }

    private void startTone() {
        stopTone();

        int rate = Integer.parseInt(rateSpinner.getSelectedItem().toString().split(" ")[0]);
        boolean floatPcm = bitsSpinner.getSelectedItemPosition() == 1;
        boolean stereo = channelsSpinner.getSelectedItemPosition() == 0;
        int bits = floatPcm ? 32 : 16;
        int channels = stereo ? 2 : 1;
        int encoding = floatPcm
                ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
        int mask = stereo
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        long pcmBitrate = (long) rate * bits * channels;

        log("Teste: " + rate + " Hz, " + bits + " bit, "
                + channels + " canal(is), PCM=" + pcmBitrate + " bit/s.");

        try {
            int min = AudioTrack.getMinBufferSize(rate, mask, encoding);
            if (min <= 0) {
                throw new IllegalStateException(
                        "Configuração não suportada por AudioTrack; minBuffer=" + min);
            }

            int buffer = Math.max(min, rate * channels * (bits / 8) / 10);
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(encoding)
                    .setChannelMask(mask)
                    .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            toneTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            AudioDeviceInfo preferred = null;
            if (selectedTarget != null && selectedTarget.audioDevice != null) {
                preferred = selectedTarget.audioDevice;
                log("Usando rota do ALVO SELECIONADO: " + selectedTarget.name);
            } else {
                int selected = outputSpinner.getSelectedItemPosition();
                if (selected > 0 && selected - 1 < outputDevices.size()) {
                    preferred = outputDevices.get(selected - 1);
                }
            }

            if (preferred != null) {
                boolean ok = toneTrack.setPreferredDevice(preferred);
                log("Saída preferida: " + preferred.getProductName()
                        + " setPreferredDevice=" + ok);
            }

            toneTrack.play();
            toneRunning = true;
            toneThread = new Thread(
                    () -> generateTone(rate, stereo, floatPcm), "G533Tone");
            toneThread.start();
            status.setText("Tom 1 kHz ativo em " + rate + " Hz.");
        } catch (Exception e) {
            log("Falha ao iniciar áudio: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
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
                        for (int c = 0; c < channels; c++) {
                            b[f * channels + c] = s;
                        }
                    }
                    toneTrack.write(b, 0, b.length, AudioTrack.WRITE_BLOCKING);
                }
            } else {
                short[] b = new short[frames * channels];
                while (toneRunning && toneTrack != null) {
                    for (int f = 0; f < frames; f++) {
                        short s = (short) (Math.sin(phase) * 3800);
                        phase += step;
                        for (int c = 0; c < channels; c++) {
                            b[f * channels + c] = s;
                        }
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
        return (n.contains("g533") || n.contains("logitech"))
                ? "<<< POSSÍVEL G533 >>> " : "";
    }

    private String arr(int[] a) {
        return a == null || a.length == 0
                ? "[não informado]" : Arrays.toString(a);
    }

    private String safe(String s) {
        return s == null || s.isEmpty() ? "(sem nome)" : s;
    }

    private void log(String s) {
        String tm = new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date());
        handler.post(() -> {
            if (logView != null) logView.append("[" + tm + "] " + s + "\n");
        });
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
        ArrayAdapter<String> ad = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
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

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
}
