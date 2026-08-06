package com.alysson.cpugpumonitor;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class StatsWidgetProvider extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "com.alysson.cpugpumonitor.TOGGLE";
    static final String ACTION_REFRESH = "com.alysson.cpugpumonitor.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        StatsSnapshot s = HardwareReader.sample(context);
        StatsStore.save(context, s);
        for (int id : ids) WidgetUpdater.update(context, manager, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            StatsSnapshot s = HardwareReader.sample(context);
            StatsStore.save(context, s);
            WidgetUpdater.updateAll(context);
        } else if (ACTION_TOGGLE.equals(action)) {
            Intent service = new Intent(context, MonitorService.class);
            if (StatsStore.isMonitoring(context)) {
                service.setAction(MonitorService.ACTION_STOP);
                context.startService(service);
            } else {
                service.setAction(MonitorService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
                else context.startService(service);
            }
        }
    }
}
