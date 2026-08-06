package com.alysson.cpugpumonitor;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView cpuText;
    private TextView gpuText;
    private TextView systemText;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            executor.execute(() -> {
                StatsSnapshot s = HardwareReader.sample(getApplicationContext());
                StatsStore.save(getApplicationContext(), s);
                WidgetUpdater.updateAll(getApplicationContext());
                runOnUiThread(() -> render(s));
            });
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ((TextView) findViewById(R.id.deviceText)).setText(HardwareReader.deviceLabel());
        cpuText = findViewById(R.id.cpuText);
        gpuText = findViewById(R.id.gpuText);
        systemText = findViewById(R.id.systemText);

        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);
        Button addWidget = findViewById(R.id.addWidgetButton);

        start.setOnClickListener(v -> {
            requestNotificationPermission();
            Intent service = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
        });
        stop.setOnClickListener(v -> startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP)));
        addWidget.setOnClickListener(v -> requestPinWidget());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void render(StatsSnapshot s) {
        String cpuMode = s.cpuEstimated ? "estimativa pela frequência" : "contador global";
        cpuText.setText(String.format(Locale.US,
                "CPU: %s%.0f%%\nFrequência média: %d / %d MHz\nProcesso do app: %.1f%%\nModo: %s",
                s.cpuEstimated ? "~" : "", s.cpuPercent, s.cpuCurrentMhz, s.cpuMaxMhz, s.appCpuPercent, cpuMode));

        if (s.gpuAvailable) {
            gpuText.setText(String.format(Locale.US,
                    "GPU: %s%.0f%%\nFrequência: %d / %d MHz\nModo: %s",
                    s.gpuEstimated ? "~" : "", s.gpuPercent, s.gpuCurrentMhz, s.gpuMaxMhz,
                    s.gpuEstimated ? "estimativa pela frequência" : "contador do firmware"));
        } else {
            gpuText.setText("GPU: indisponível sem root\nO firmware/SELinux não expôs um contador legível para este app.\nFonte: " + s.gpuSource);
        }

        String temp = Float.isNaN(s.batteryTempC) ? "indisponível" : String.format(Locale.US, "%.1f °C", s.batteryTempC);
        systemText.setText(String.format(Locale.US,
                "RAM: %.0f%% — %d / %d MB\nTemperatura da bateria: %s\nMonitor contínuo: %s",
                s.ramPercent, s.ramUsedMb, s.ramTotalMb, temp,
                StatsStore.isMonitoring(this) ? "ATIVO" : "PARADO"));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void requestPinWidget() {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        ComponentName provider = new ComponentName(this, StatsWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            Intent callback = new Intent(this, MainActivity.class);
            PendingIntent success = PendingIntent.getActivity(this, 200, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.requestPinAppWidget(provider, null, success);
        } else {
            Toast.makeText(this, "Abra Widgets na tela inicial e procure CPU GPU Monitor S21.", Toast.LENGTH_LONG).show();
        }
    }
}
