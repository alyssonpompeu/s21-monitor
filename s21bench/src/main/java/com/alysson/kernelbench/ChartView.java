package com.alysson.kernelbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ChartView extends View {
    static final int MODE_TOTAL = 0;
    static final int MODE_GPU = 1;
    static final int MODE_CPU = 2;
    static final int MODE_RAM = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<RunRecord> records = new ArrayList<>();
    private int mode = MODE_GPU;

    ChartView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setData(List<RunRecord> data) {
        records = data == null ? new ArrayList<>() : new ArrayList<>(data);
        invalidate();
    }

    void setMode(int m) {
        mode = m;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.argb(220, 10, 15, 25));
        if (records.isEmpty()) {
            p.setColor(Color.LTGRAY); p.setTextSize(28);
            c.drawText("Sem dados FULL", 24, 48, p);
            return;
        }

        float left = 58, right = w - 18, top = 42, bottom = h - 55;
        p.setStrokeWidth(1f); p.setColor(Color.rgb(55, 66, 84));
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom-top)*i/4f;
            c.drawLine(left, y, right, y, p);
        }

        double max = 1;
        for (RunRecord r : records) {
            if (!r.isFull()) continue;
            if (mode == MODE_TOTAL) max = Math.max(max, r.total);
            else if (mode == MODE_GPU) max = Math.max(max, Math.max(r.gpuBurst, r.gpuSoak));
            else if (mode == MODE_CPU) max = Math.max(max, Math.max(r.multi, r.cpuSoak));
            else max = Math.max(max, r.ramCopy);
        }
        max *= 1.08;

        p.setTextSize(20); p.setColor(Color.rgb(205,215,230));
        String title = mode == MODE_TOTAL ? "TOTAL S21Lab" :
                mode == MODE_GPU ? "GPU burst / soak (draws/s)" :
                mode == MODE_CPU ? "CPU multi / soak (Mops)" :
                "RAM copy (MB/s)";
        c.drawText(title, left, 28, p);

        ArrayList<RunRecord> full = new ArrayList<>();
        for (RunRecord r : records) if (r.isFull()) full.add(r);
        if (full.size() < 1) return;

        float dx = full.size() <= 1 ? 0 : (right-left)/(full.size()-1f);
        if (mode == MODE_GPU) {
            drawSeries(c, full, left, top, bottom, dx, max, 1, Color.rgb(81, 173, 255));
            drawSeries(c, full, left, top, bottom, dx, max, 2, Color.rgb(120, 239, 177));
            drawLegend(c, left, h-18, "burst", Color.rgb(81,173,255), "soak", Color.rgb(120,239,177));
        } else if (mode == MODE_CPU) {
            drawSeries(c, full, left, top, bottom, dx, max, 3, Color.rgb(255, 188, 88));
            drawSeries(c, full, left, top, bottom, dx, max, 4, Color.rgb(243, 117, 155));
            drawLegend(c, left, h-18, "multi", Color.rgb(255,188,88), "soak", Color.rgb(243,117,155));
        } else if (mode == MODE_TOTAL) {
            drawSeries(c, full, left, top, bottom, dx, max, 5, Color.rgb(165, 131, 255));
        } else {
            drawSeries(c, full, left, top, bottom, dx, max, 6, Color.rgb(98, 220, 232));
        }

        p.setColor(Color.rgb(165,176,195)); p.setTextSize(15);
        c.drawText(String.format(Locale.US, "%.0f", max), 5, top+6, p);
        c.drawText("0", 28, bottom+5, p);

        int step = Math.max(1, full.size()/5);
        for (int i = 0; i < full.size(); i += step) {
            float x = left + i*dx;
            String s = shortLabel(full.get(i).label);
            c.save();
            c.rotate(-35, x, bottom+18);
            c.drawText(s, x, bottom+18, p);
            c.restore();
        }
    }

    private void drawSeries(Canvas c, List<RunRecord> data, float left, float top, float bottom,
                            float dx, double max, int metric, int color) {
        p.setColor(color); p.setStrokeWidth(4f); p.setStyle(Paint.Style.STROKE);
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < data.size(); i++) {
            double v = value(data.get(i), metric);
            if (v <= 0) continue;
            float x = left + i*dx;
            float y = bottom - (float)(v/max)*(bottom-top);
            if (!started) { path.moveTo(x,y); started = true; }
            else path.lineTo(x,y);
        }
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < data.size(); i++) {
            double v = value(data.get(i), metric);
            if (v <= 0) continue;
            float x = left + i*dx;
            float y = bottom - (float)(v/max)*(bottom-top);
            c.drawCircle(x,y,5,p);
        }
    }

    private double value(RunRecord r, int m) {
        switch (m) {
            case 1: return r.gpuBurst;
            case 2: return r.gpuSoak;
            case 3: return r.multi;
            case 4: return r.cpuSoak;
            case 5: return r.total;
            case 6: return r.ramCopy;
        }
        return 0;
    }

    private void drawLegend(Canvas c, float x, float y, String a, int ca, String b, int cb) {
        p.setTextSize(16);
        p.setColor(ca); c.drawCircle(x+5,y-5,5,p); c.drawText(a,x+16,y,p);
        p.setColor(cb); c.drawCircle(x+95,y-5,5,p); c.drawText(b,x+106,y,p);
    }

    private String shortLabel(String s) {
        if (s == null) return "";
        if (s.length() <= 12) return s;
        return s.substring(0, 12);
    }
}
