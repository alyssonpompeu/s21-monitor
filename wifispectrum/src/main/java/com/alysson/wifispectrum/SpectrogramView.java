package com.alysson.wifispectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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

    private static final int BINS = 220;
    private static final int MAX_FRAMES = 110;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Deque<Frame> frames = new ArrayDeque<>();
    private int band = BAND_24;

    public SpectrogramView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(3, 6, 10));
    }

    public void setBand(int band) {
        this.band = band;
        invalidate();
    }

    public int getBand() {
        return band;
    }

    public void clearHistory() {
        frames.clear();
        invalidate();
    }

    public int getFrameCount() {
        return frames.size();
    }

    public void addFrame(List<WifiSample> samples, long timestampMs) {
        frames.addLast(new Frame(new ArrayList<>(samples), timestampMs));
        while (frames.size() > MAX_FRAMES) frames.removeFirst();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float left = dp(42);
        float right = w - dp(10);
        float top = dp(24);
        float spectrumBottom = h * 0.36f;
        float waterfallTop = spectrumBottom + dp(28);
        float waterfallBottom = h - dp(38);

        drawPanel(canvas, left, top, right, spectrumBottom);
        drawPanel(canvas, left, waterfallTop, right, waterfallBottom);
        drawAxes(canvas, left, top, right, spectrumBottom, waterfallTop, waterfallBottom);

        Frame latest = frames.peekLast();
        if (latest != null) drawInstantSpectrum(canvas, latest.samples, left, top, right, spectrumBottom);
        drawWaterfall(canvas, left, waterfallTop, right, waterfallBottom);
        drawLegend(canvas, left, right, h);
    }

    private void drawPanel(Canvas c, float l, float t, float r, float b) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(6, 13, 20));
        c.drawRect(l, t, r, b, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(35, 58, 72));
        c.drawRect(l, t, r, b, paint);
    }

    private void drawAxes(Canvas c, float l, float top, float r, float spectrumBottom, float waterfallTop, float waterfallBottom) {
        paint.setTextSize(dp(10));
        paint.setColor(Color.rgb(165, 190, 204));
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("-35", l - dp(5), top + dp(5), paint);
        c.drawText("-65", l - dp(5), (top + spectrumBottom) / 2f + dp(4), paint);
        c.drawText("-100", l - dp(5), spectrumBottom, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.rgb(101, 206, 255));
        c.drawText("ESPECTRO ATUAL (dBm)", l, top - dp(7), paint);
        c.drawText("WATERFALL • TEMPO ↓", l, waterfallTop - dp(7), paint);

        int[] range = rangeForBand();
        int ticks = band == BAND_24 ? 5 : 6;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(150, 175, 188));
        for (int i = 0; i < ticks; i++) {
            float x = l + (r - l) * i / (ticks - 1f);
            int freq = Math.round(range[0] + (range[1] - range[0]) * i / (ticks - 1f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.rgb(25, 43, 54));
            c.drawLine(x, top, x, spectrumBottom, paint);
            c.drawLine(x, waterfallTop, x, waterfallBottom, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(150, 175, 188));
            c.drawText(freq + "", x, waterfallBottom + dp(15), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("MHz", l, waterfallBottom + dp(31), paint);
    }

    private void drawInstantSpectrum(Canvas c, List<WifiSample> samples, float l, float t, float r, float b) {
        float[] bins = buildBins(samples);
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < BINS; i++) {
            float x = l + (r - l) * i / (BINS - 1f);
            float y = mapDbmToY(bins[i], t, b);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.rgb(84, 220, 255));
        c.drawPath(path, paint);

        for (WifiSample s : samples) {
            if (!inBand(s.frequencyMhz)) continue;
            float x = freqToX(s.frequencyMhz, l, r);
            float y = mapDbmToY(s.rssiDbm, t, b);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorForDbm(s.rssiDbm));
            c.drawCircle(x, y, dp(3.5f), paint);
        }
    }

    private void drawWaterfall(Canvas c, float l, float t, float r, float b) {
        if (frames.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(12));
            paint.setColor(Color.rgb(112, 139, 154));
            c.drawText("Aguardando amostras Wi‑Fi…", (l + r) / 2f, (t + b) / 2f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        List<Frame> frameList = new ArrayList<>(frames);
        float rowH = (b - t) / MAX_FRAMES;
        float cellW = (r - l) / BINS;
        int firstVisible = Math.max(0, frameList.size() - MAX_FRAMES);
        int visible = frameList.size() - firstVisible;
        float y = b - visible * rowH;

        for (int fi = firstVisible; fi < frameList.size(); fi++) {
            float[] bins = buildBins(frameList.get(fi).samples);
            for (int i = 0; i < BINS; i++) {
                float dbm = bins[i];
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colorForDbm(dbm));
                float x0 = l + i * cellW;
                c.drawRect(x0, y, x0 + cellW + 1f, y + rowH + 1f, paint);
            }
            y += rowH;
        }
    }

    private float[] buildBins(List<WifiSample> samples) {
        float[] bins = new float[BINS];
        for (int i = 0; i < BINS; i++) bins[i] = -110f;
        int[] range = rangeForBand();
        float span = range[1] - range[0];

        for (WifiSample s : samples) {
            if (!inBand(s.frequencyMhz)) continue;
            float half = Math.max(10f, s.widthMhz / 2f);
            float start = s.frequencyMhz - half;
            float end = s.frequencyMhz + half;
            int i0 = clamp(Math.round((start - range[0]) / span * (BINS - 1)), 0, BINS - 1);
            int i1 = clamp(Math.round((end - range[0]) / span * (BINS - 1)), 0, BINS - 1);
            for (int i = i0; i <= i1; i++) {
                float f = range[0] + span * i / (BINS - 1f);
                float rel = Math.abs(f - s.frequencyMhz) / half;
                float shaped = s.rssiDbm - 9f * rel * rel;
                if (shaped > bins[i]) bins[i] = shaped;
            }
        }
        return bins;
    }

    private float freqToX(int freq, float l, float r) {
        int[] range = rangeForBand();
        float norm = (freq - range[0]) / (float)(range[1] - range[0]);
        norm = Math.max(0f, Math.min(1f, norm));
        return l + norm * (r - l);
    }

    private float mapDbmToY(float dbm, float top, float bottom) {
        float v = (dbm + 100f) / 65f;
        v = Math.max(0f, Math.min(1f, v));
        return bottom - v * (bottom - top);
    }

    private int colorForDbm(float dbm) {
        if (dbm <= -105f) return Color.rgb(4, 8, 13);
        float v = (dbm + 100f) / 65f;
        v = Math.max(0f, Math.min(1f, v));
        if (v < 0.25f) {
            float q = v / 0.25f;
            return Color.rgb(8, Math.round(35 + 70 * q), Math.round(80 + 150 * q));
        } else if (v < 0.50f) {
            float q = (v - 0.25f) / 0.25f;
            return Color.rgb(8, Math.round(105 + 130 * q), Math.round(230 - 150 * q));
        } else if (v < 0.75f) {
            float q = (v - 0.50f) / 0.25f;
            return Color.rgb(Math.round(20 + 235 * q), 235, Math.round(80 - 60 * q));
        } else {
            float q = (v - 0.75f) / 0.25f;
            return Color.rgb(255, Math.round(235 - 190 * q), 20);
        }
    }

    private void drawLegend(Canvas c, float l, float r, float h) {
        float y = h - dp(14);
        float barW = Math.min(dp(180), (r - l) * 0.55f);
        float x0 = r - barW;
        int steps = 60;
        for (int i = 0; i < steps; i++) {
            float f = i / (steps - 1f);
            float dbm = -100f + 65f * f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorForDbm(dbm));
            float x = x0 + barW * i / steps;
            c.drawRect(x, y - dp(8), x + barW / steps + 1f, y, paint);
        }
        paint.setTextSize(dp(9));
        paint.setColor(Color.rgb(170, 190, 200));
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("-100 dBm", x0, y + dp(10), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("-35 dBm", r, y + dp(10), paint);
        paint.setTextAlign(Paint.Align.LEFT);
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class Frame {
        final List<WifiSample> samples;
        final long timestamp;
        Frame(List<WifiSample> samples, long timestamp) {
            this.samples = samples;
            this.timestamp = timestamp;
        }
    }
}
