package com.alysson.kernelbench;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class HistoryStore {
    private static final String PREF = "s21lab_history_v2";
    private static final String KEY = "local_runs";

    private HistoryStore() {}

    static List<RunRecord> local(Context c) {
        ArrayList<RunRecord> out = new ArrayList<>();
        try {
            String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(RunRecord.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    static List<RunRecord> all(Context c) {
        ArrayList<RunRecord> out = new ArrayList<>();
        out.addAll(HistoricalData.all());
        out.addAll(local(c));
        Collections.sort(out, Comparator.comparing(r -> r.timestamp == null ? "" : r.timestamp));
        return out;
    }

    static List<RunRecord> full(Context c) {
        ArrayList<RunRecord> out = new ArrayList<>();
        for (RunRecord r : all(c)) if (r.isFull()) out.add(r);
        return out;
    }

    static void add(Context c, RunRecord r) {
        ArrayList<RunRecord> list = new ArrayList<>(local(c));
        list.add(r);
        while (list.size() > 100) list.remove(0);
        save(c, list);
    }

    static void clearLocal(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private static void save(Context c, List<RunRecord> list) {
        JSONArray arr = new JSONArray();
        for (RunRecord r : list) arr.put(r.toJson());
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putString(KEY, arr.toString()).apply();
    }
}
