package com.alysson.kernelbench;

import org.json.JSONObject;

final class RunRecord {
    String id = "";
    String label = "";
    String timestamp = "";
    String source = "";
    String kernel = "";
    String note = "";
    String kind = "FULL";
    boolean historical = false;

    double total;
    double cpu;
    double gpu;
    double mem;
    double storage;

    double single;
    double multi;
    double gpuBurst;
    double ramCopy;
    double ramLatency;
    double storageWrite;
    double storageRead;
    double cpuSoak;
    double gpuSoak;

    String startTelemetry = "";
    String endTelemetry = "";

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("label", label); o.put("timestamp", timestamp);
            o.put("source", source); o.put("kernel", kernel); o.put("note", note);
            o.put("kind", kind); o.put("historical", historical);
            o.put("total", total); o.put("cpu", cpu); o.put("gpu", gpu);
            o.put("mem", mem); o.put("storage", storage);
            o.put("single", single); o.put("multi", multi); o.put("gpuBurst", gpuBurst);
            o.put("ramCopy", ramCopy); o.put("ramLatency", ramLatency);
            o.put("storageWrite", storageWrite); o.put("storageRead", storageRead);
            o.put("cpuSoak", cpuSoak); o.put("gpuSoak", gpuSoak);
            o.put("startTelemetry", startTelemetry); o.put("endTelemetry", endTelemetry);
        } catch (Exception ignored) {}
        return o;
    }

    static RunRecord fromJson(JSONObject o) {
        RunRecord r = new RunRecord();
        r.id = o.optString("id", "");
        r.label = o.optString("label", "");
        r.timestamp = o.optString("timestamp", "");
        r.source = o.optString("source", "");
        r.kernel = o.optString("kernel", "");
        r.note = o.optString("note", "");
        r.kind = o.optString("kind", "FULL");
        r.historical = o.optBoolean("historical", false);
        r.total = o.optDouble("total", 0);
        r.cpu = o.optDouble("cpu", 0);
        r.gpu = o.optDouble("gpu", 0);
        r.mem = o.optDouble("mem", 0);
        r.storage = o.optDouble("storage", 0);
        r.single = o.optDouble("single", 0);
        r.multi = o.optDouble("multi", 0);
        r.gpuBurst = o.optDouble("gpuBurst", 0);
        r.ramCopy = o.optDouble("ramCopy", 0);
        r.ramLatency = o.optDouble("ramLatency", 0);
        r.storageWrite = o.optDouble("storageWrite", 0);
        r.storageRead = o.optDouble("storageRead", 0);
        r.cpuSoak = o.optDouble("cpuSoak", 0);
        r.gpuSoak = o.optDouble("gpuSoak", 0);
        r.startTelemetry = o.optString("startTelemetry", "");
        r.endTelemetry = o.optString("endTelemetry", "");
        return r;
    }

    boolean isFull() {
        return "FULL".equals(kind) && total > 0;
    }
}
