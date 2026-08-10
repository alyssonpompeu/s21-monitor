package com.alysson.wifispectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class SpectrogramView extends View {
    public static final int BAND_24 = 0;
    public static final int BAND_5 = 1;
    public static final int BAND_6 = 2;

    private static final int FREQ_BINS = 220;
    private static final int MAX_COLUMNS = 240;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Deque<Column> columns = new ArrayDeque<>();
    private int band = BAND_24;
    private long firstTimestamp = 0L;
    private long lastTimestamp = 0L;

    public SpectrogramView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(2, 5, 12));
    }

    public void setBand(int band) {
        if (this.band == band) return;
        this.band = band;
        clearHistory();
    }

    public int getBand() { return band; }

    public int getFrameCount() { return columns.size(); }

    public long getHistoryDurationMs() {
        return firstTimestamp == 0L || lastTimestamp == 0L ? 0L : Math.max(0L, lastTimestamp - firstTimestamp);
    }

    public void clearHistory() {
        columns.clear();
        firstTimestamp = 0L;
        lastTimestamp = 0L;
        invalidate();
    }

    public void addFrame(List<WifiSample> samples, long timestampMs) {
        float[] bins = buildBins(samples);
        columns.addLast(new Column(bins, timestampMs));
        while (columns.size() > MAX_COLUMNS) columns.removeFirst();
        Column first = columns.peekFirst();
        Column last = columns.peekLast();
        firstTimestamp = first == null ? 0L : first.timestampMs;
        lastTimestamp = last == null ? 0L : last.timestampMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w < 20 || h < 20) return;

        float left = dp(56);
        float top = dp(30);
        float legendW = dp(34);
        float right = w - legendW - dp(18);
        float bottom = h - dp(44);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(3, 8, 30));
        c.drawRect(left, top, right, bottom, p);

        drawGrid(c, left, top, right, bottom);
        drawWaterfall(c, left, top, right, bottom);
        drawAxes(c, left, top, right, bottom);
        drawColorLegend(c, right + dp(10), top, right + dp(24), bottom);

        if (columns.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(13));
            p.setColor(Color.rgb(165, 188, 206));
            c.drawText("Aguardando sinal Wi-Fi…", (left + right) / 2f, (top + bottom) / 2f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawWaterfall(Canvas c, float l, float t, float r, float b) {
        if (columns.isEmpty()) return;
        List<Column> list = new ArrayList<>(columns);
        int count = list.size();
        float colW = (r - l) / MAX_COLUMNS;
        float startX = r - count * colW;
        float rowH = (b - t) / FREQ_BINS;

        for (int ci = 0; ci < count; ci++) {
            Column column = list.get(ci);
            float x0 = startX + ci * colW;
            float x1 = x0 + colW + 1f;
            for (int bi = 0; bi < FREQ_BINS; bi++) {
                float dbm = column.bins[bi];
                p.setStyle(Paint.Style.FILL);
                p.setColor(colorForDbm(dbm));
                float y1 = b - bi * rowH;
                float y0 = y1 - rowH - 1f;
                c.drawRect(x0, y0, x1, y1, p);
            }
        }

        // Latest-data cursor.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Color.argb(150, 255, 255, 255));
        c.drawLine(r, t, r, b, p);
    }

    private void drawGrid(Canvas c, float l, float t, float r, float b) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Color.argb(70, 105, 155, 190));
        int horizontal = band == BAND_24 ? 5 : 7;
        for (int i = 0; i < horizontal; i++) {
            float y = t + (b - t) * i / (horizontal - 1f);
            c.drawLine(l, y, r, y, p);
        }
        for (int i = 0; i <= 6; i++) {
            float x = l + (r - l) * i / 6f;
            c.drawLine(x, t, x, b, p);
        }
    }

    private void drawAxes(Canvas c, float l, float t, float r, float b) {
        int[] range = rangeForBand();
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(9.5f));
        p.setColor(Color.rgb(205, 216, 225));
        p.setTextAlign(Paint.Align.RIGHT);

        int ticks = band == BAND_24 ? 5 : 7;
        for (int i = 0; i < ticks; i++) {
            float frac = i / (ticks - 1f);
            float y = b - (b - t) * frac;
            int freq = Math.round(range[0] + (range[1] - range[0]) * frac);
            c.drawText(freq + "", l - dp(5), y + dp(3), p);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.rgb(107, 210, 255));
        p.setTextSize(dp(11));
        c.drawText("FREQUÊNCIA (MHz)", l, t - dp(9), p);

        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.rgb(205, 216, 225));
        p.setTextSize(dp(9));
        long durationMs = getHistoryDurationMs();
        double seconds = durationMs / 1000.0;
        for (int i = 0; i <= 6; i++) {
            float x = l + (r - l) * i / 6f;
            double age = seconds * (1.0 - i / 6.0);
            String label = age < 0.8 ? "agora" : String.format(Locale.US, "-%.0fs", age);
            c.drawText(label, x, b + dp(15), p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.rgb(107, 210, 255));
        p.setTextSize(dp(10));
        c.drawText("TEMPO →", l, b + dp(32), p);
    }

    private void drawColorLegend(Canvas c, float l, float t, float r, float b) {
        int steps = 100;
        float stepH = (b - t) / steps;
        for (int i = 0; i < steps; i++) {
            float frac = i / (steps - 1f);
            float dbm = -35f - frac * 70f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(colorForDbm(dbm));
            float y0 = t + i * stepH;
            c.drawRect(l, y0, r, y0 + stepH + 1f, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Color.rgb(220, 225, 230));
        c.drawRect(l, t, r, b, p);

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(8));
        p.setColor(Color.rgb(220, 225, 230));
        c.drawText("-35", r + dp(3), t + dp(4), p);
        c.drawText("-60", r + dp(3), t + (b - t) * 0.36f, p);
        c.drawText("-80", r + dp(3), t + (b - t) * 0.65f, p);
        c.drawText("-105", r + dp(3), b, p);
        c.drawText("dBm", l - dp(2), b + dp(13), p);
    }

    private float[] buildBins(List<WifiSample> samples) {
        float[] bins = new float[FREQ_BINS];
        for (int i = 0; i < FREQ_BINS; i++) bins[i] = -110f;
        int[] range = rangeForBand();
        float span = range[1] - range[0];

        for (WifiSample s : samples) {
            if (!inBand(s.frequencyMhz)) continue;
            float half = Math.max(10f, s.widthMhz / 2f);
            float start = s.frequencyMhz - half;
            float end = s.frequencyMhz + half;
            int i0 = clamp(Math.round((start - range[0]) / span * (FREQ_BINS - 1)), 0, FREQ_BINS - 1);
            int i1 = clamp(Math.round((end - range[0]) / span * (FREQ_BINS - 1)), 0, FREQ_BINS - 1);
            for (int i = i0; i <= i1; i++) {
                float freq = range[0] + span * i / (FREQ_BINS - 1f);
                float rel = Math.abs(freq - s.frequencyMhz) / half;
                float shaped = s.rssiDbm - 11f * rel * rel;
                if (shaped > bins[i]) bins[i] = shaped;
            }
        }

        // Slight vertical blur to avoid blocky channel edges and resemble a real waterfall.
        float[] smooth = bins.clone();
        for (int i = 1; i < FREQ_BINS - 1; i++) {
            smooth[i] = Math.max(bins[i], (bins[i - 1] + bins[i] * 2f + bins[i + 1]) / 4f - 1.5f);
        }
        return smooth;
    }

    private int colorForDbm(float dbm) {
        if (dbm <= -108f) return Color.rgb(0, 2, 22);
        float v = (dbm + 105f) / 70f;
        v = Math.max(0f, Math.min(1f, v));

        if (v < 0.18f) {
            float q = v / 0.18f;
            return Color.rgb(0, Math.round(6 + 18 * q), Math.round(45 + 115 * q));
        } else if (v < 0.38f) {
            float q = (v - 0.18f) / 0.20f;
            return Color.rgb(0, Math.round(24 + 180 * q), Math.round(160 + 85 * q));
        } else if (v < 0.58f) {
            float q = (v - 0.38f) / 0.20f;
            return Color.rgb(Math.round(0 + 70 * q), Math.round(204 + 45 * q), Math.round(245 - 155 * q));
        } else if (v < 0.78f) {
            float q = (v - 0.58f) / 0.20f;
            return Color.rgb(Math.round(70 + 185 * q), Math.round(249 - 5 * q), Math.round(90 - 70 * q));
        } else {
            float q = (v - 0.78f) / 0.22f;
            return Color.rgb(255, Math.round(244 - 210 * q), Math.round(20 - 15 * q));
        }
    }

    private boolean inBand(int f) {
        if (band == BAND_24) return f >= 2400 && f <= 2500;
        if (band == BAND_5) return f >= 5000 && f < 5925;
        return f >= 5925 && f <= 7125;
    }

    private int[] rangeForBand() {
        if (band == BAND_24) return new int[]{2400, 2500};
        if (band == BAND_5) return new int[]{5150, 5900};
        return new int[]{5925, 7125};
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static final class Column {
        final float[] bins;
        final long timestampMs;
        Column(float[] bins, long timestampMs) {
            this.bins = bins;
            this.timestampMs = timestampMs;
        }
    }
}
