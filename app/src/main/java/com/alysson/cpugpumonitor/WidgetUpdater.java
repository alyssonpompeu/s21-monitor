package com.alysson.cpugpumonitor;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WidgetUpdater {
    private WidgetUpdater() {}

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, StatsWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) update(context, manager, id);
    }

    static void update(Context context, AppWidgetManager manager, int widgetId) {
        StatsSnapshot s = StatsStore.load(context);
        boolean monitoring = StatsStore.isMonitoring(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_stats);

        String cpuMark = s.cpuEstimated ? "~" : "";
        views.setTextViewText(R.id.widgetCpu, String.format(Locale.US,
                "CPU %s%.0f%%  •  %d/%d MHz", cpuMark, s.cpuPercent, s.cpuCurrentMhz, s.cpuMaxMhz));
        views.setProgressBar(R.id.widgetCpuBar, 100, Math.round(s.cpuPercent), false);

        if (s.gpuAvailable) {
            String gpuMark = s.gpuEstimated ? "~" : "";
            String freq = s.gpuCurrentMhz > 0 ? String.format(Locale.US, "  •  %d/%d MHz", s.gpuCurrentMhz, s.gpuMaxMhz) : "";
            views.setTextViewText(R.id.widgetGpu, String.format(Locale.US, "GPU %s%.0f%%%s", gpuMark, s.gpuPercent, freq));
            views.setProgressBar(R.id.widgetGpuBar, 100, Math.round(s.gpuPercent), false);
        } else {
            views.setTextViewText(R.id.widgetGpu, "GPU indisponível sem root");
            views.setProgressBar(R.id.widgetGpuBar, 100, 0, false);
        }

        String temp = Float.isNaN(s.batteryTempC) ? "--°C" : String.format(Locale.US, "%.1f°C", s.batteryTempC);
        views.setTextViewText(R.id.widgetExtra, String.format(Locale.US,
                "RAM %.0f%% (%d/%d MB)  •  %s", s.ramPercent, s.ramUsedMb, s.ramTotalMb, temp));
        views.setTextViewText(R.id.widgetTime, s.timestamp == 0 ? "--:--:--" : new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(s.timestamp)));
        views.setTextViewText(R.id.widgetToggle, monitoring ? "PARAR" : "INICIAR");

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetTitle, openPi);

        Intent toggle = new Intent(context, StatsWidgetProvider.class).setAction(StatsWidgetProvider.ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getBroadcast(context, 2, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetToggle, togglePi);

        Intent refresh = new Intent(context, StatsWidgetProvider.class).setAction(StatsWidgetProvider.ACTION_REFRESH);
        PendingIntent refreshPi = PendingIntent.getBroadcast(context, 3, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPi);

        manager.updateAppWidget(widgetId, views);
    }
}
