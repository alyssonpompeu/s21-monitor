package com.alysson.cpugpumonitor;

import android.content.Context;
import android.content.SharedPreferences;

final class StatsStore {
    private static final String PREFS = "stats";
    private StatsStore() {}

    static void save(Context context, StatsSnapshot s) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("timestamp", s.timestamp)
                .putFloat("cpuPercent", s.cpuPercent)
                .putBoolean("cpuEstimated", s.cpuEstimated)
                .putLong("cpuCurrentMhz", s.cpuCurrentMhz)
                .putLong("cpuMaxMhz", s.cpuMaxMhz)
                .putFloat("appCpuPercent", s.appCpuPercent)
                .putFloat("gpuPercent", s.gpuPercent)
                .putBoolean("gpuEstimated", s.gpuEstimated)
                .putLong("gpuCurrentMhz", s.gpuCurrentMhz)
                .putLong("gpuMaxMhz", s.gpuMaxMhz)
                .putBoolean("gpuAvailable", s.gpuAvailable)
                .putFloat("ramPercent", s.ramPercent)
                .putLong("ramUsedMb", s.ramUsedMb)
                .putLong("ramTotalMb", s.ramTotalMb)
                .putFloat("batteryTempC", s.batteryTempC)
                .putString("gpuSource", s.gpuSource)
                .apply();
    }

    static StatsSnapshot load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        StatsSnapshot s = new StatsSnapshot();
        s.timestamp = p.getLong("timestamp", 0);
        s.cpuPercent = p.getFloat("cpuPercent", 0);
        s.cpuEstimated = p.getBoolean("cpuEstimated", true);
        s.cpuCurrentMhz = p.getLong("cpuCurrentMhz", 0);
        s.cpuMaxMhz = p.getLong("cpuMaxMhz", 0);
        s.appCpuPercent = p.getFloat("appCpuPercent", 0);
        s.gpuPercent = p.getFloat("gpuPercent", 0);
        s.gpuEstimated = p.getBoolean("gpuEstimated", true);
        s.gpuCurrentMhz = p.getLong("gpuCurrentMhz", 0);
        s.gpuMaxMhz = p.getLong("gpuMaxMhz", 0);
        s.gpuAvailable = p.getBoolean("gpuAvailable", false);
        s.ramPercent = p.getFloat("ramPercent", 0);
        s.ramUsedMb = p.getLong("ramUsedMb", 0);
        s.ramTotalMb = p.getLong("ramTotalMb", 0);
        s.batteryTempC = p.getFloat("batteryTempC", Float.NaN);
        s.gpuSource = p.getString("gpuSource", "não disponível");
        return s;
    }

    static void setMonitoring(Context context, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("monitoring", active).apply();
    }

    static boolean isMonitoring(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("monitoring", false);
    }
}
