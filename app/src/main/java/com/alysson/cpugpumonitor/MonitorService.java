package com.alysson.cpugpumonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    static final String ACTION_START = "com.alysson.cpugpumonitor.START";
    static final String ACTION_STOP = "com.alysson.cpugpumonitor.STOP";
    private static final String CHANNEL = "hardware_monitor";
    private static final int NOTIFICATION_ID = 2100;
    private ScheduledExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }

        Notification initial = buildNotification("Iniciando leitura…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, initial);
        }
        startMonitoring();
        return START_STICKY;
    }

    private void startMonitoring() {
        if (executor != null && !executor.isShutdown()) return;
        StatsStore.setMonitoring(this, true);
        WidgetUpdater.updateAll(this);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                StatsSnapshot s = HardwareReader.sample(getApplicationContext());
                StatsStore.save(getApplicationContext(), s);
                WidgetUpdater.updateAll(getApplicationContext());
                String gpu = s.gpuAvailable ? String.format(Locale.US, "GPU %.0f%%", s.gpuPercent) : "GPU bloqueada";
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFICATION_ID, buildNotification(String.format(Locale.US, "CPU %.0f%% • %s • RAM %.0f%%", s.cpuPercent, gpu, s.ramPercent)));
            } catch (Throwable ignored) {
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void stopMonitoring() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        StatsStore.setMonitoring(this, false);
        WidgetUpdater.updateAll(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_monitor)
                .setContentTitle("Monitor de hardware ativo")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Parar", stopPi).build())
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Monitor de CPU e GPU", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantém o widget de hardware atualizado em tempo quase real.");
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        StatsStore.setMonitoring(this, false);
        WidgetUpdater.updateAll(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
