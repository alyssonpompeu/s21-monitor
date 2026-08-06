package com.alysson.cpugpumonitor;

final class StatsSnapshot {
    long timestamp;
    float cpuPercent;
    boolean cpuEstimated;
    long cpuCurrentMhz;
    long cpuMaxMhz;
    float appCpuPercent;
    float gpuPercent;
    boolean gpuEstimated;
    long gpuCurrentMhz;
    long gpuMaxMhz;
    boolean gpuAvailable;
    float ramPercent;
    long ramUsedMb;
    long ramTotalMb;
    float batteryTempC;
    String gpuSource = "não disponível";
}
