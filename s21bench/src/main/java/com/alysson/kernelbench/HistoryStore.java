package com.alysson.kernelbench;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "kernelbench_history";
    private static final String KEY = "records";
    private static final int MAX = 100;

    private HistoryStore() {}

    public static final class Record {
        public long timestamp;
        public String type = "";
        public int gpuScore;
        public double gpuRaw;
        public int cpuScore;
        public double cpuRaw;
        public int ramScore;
        public double ramRaw;
        public int overall;
        public double gpuDelta;
        public double cpuDelta;
        public double ramDelta;
        public double sceneFps;
        public String kernel = "";
        public String build = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("timestamp", timestamp);
                o.put("type", type);
                o.put("gpuScore", gpuScore);
                o.put("gpuRaw", gpuRaw);
                o.put("cpuScore", cpuScore);
                o.put("cpuRaw", cpuRaw);
                o.put("ramScore", ramScore);
                o.put("ramRaw", ramRaw);
                o.put("overall", overall);
                o.put("gpuDelta", gpuDelta);
                o.put("cpuDelta", cpuDelta);
                o.put("ramDelta", ramDelta);
                o.put("sceneFps", sceneFps);
                o.put("kernel", kernel);
                o.put("build", build);
            } catch (Exception ignored) {}
            return o;
        }

        static Record fromJson(JSONObject o) {
            Record r = new Record();
            r.timestamp = o.optLong("timestamp", 0L);
            r.type = o.optString("type", "");
            r.gpuScore = o.optInt("gpuScore", 0);
            r.gpuRaw = o.optDouble("gpuRaw", 0);
            r.cpuScore = o.optInt("cpuScore", 0);
            r.cpuRaw = o.optDouble("cpuRaw", 0);
            r.ramScore = o.optInt("ramScore", 0);
            r.ramRaw = o.optDouble("ramRaw", 0);
            r.overall = o.optInt("overall", 0);
            r.gpuDelta = o.optDouble("gpuDelta", 0);
            r.cpuDelta = o.optDouble("cpuDelta", 0);
            r.ramDelta = o.optDouble("ramDelta", 0);
            r.sceneFps = o.optDouble("sceneFps", 0);
            r.kernel = o.optString("kernel", "");
            r.build = o.optString("build", "");
            return r;
        }
    }

    public static synchronized List<Record> load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY, "[]");
        List<Record> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Record.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void add(Context c, Record r) {
        List<Record> list = load(c);
        list.add(0, r);
        while (list.size() > MAX) list.remove(list.size() - 1);

        JSONArray a = new JSONArray();
        for (Record item : list) a.put(item.toJson());
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, a.toString()).apply();
    }

    public static synchronized void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
