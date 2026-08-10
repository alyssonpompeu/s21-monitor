package com.alysson.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public class RadarView extends View {
    private static final int BINS = 72;
    private final float[] avgDbm = new float[BINS];
    private final int[] samples = new int[BINS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float headingDeg = 0f;

    public RadarView(Context context) {
        super(context);
        Arrays.fill(avgDbm, Float.NaN);
        setBackgroundColor(Color.rgb(4, 12, 9));
    }

    public void setHeading(float heading) {
        headingDeg = normalize(heading);
        invalidate();
    }

    public void clearSamples() {
        Arrays.fill(avgDbm, Float.NaN);
        Arrays.fill(samples, 0);
        invalidate();
    }

    public void addSample(float heading, int rssi) {
        if (rssi >= 0 || rssi < -120) return;
        int idx = Math.round(normalize(heading) / 5f) % BINS;
        if (Float.isNaN(avgDbm[idx])) avgDbm[idx] = rssi;
        else avgDbm[idx] = avgDbm[idx] * 0.72f + rssi * 0.28f;
        samples[idx]++;
        invalidate();
    }

    public int getTotalSamples() {
        int n = 0;
        for (int x : samples) n += x;
        return n;
    }

    public int getCoveredBins() {
        int n = 0;
        for (int x : samples) if (x > 0) n++;
        return n;
    }

    public float getBestBearing() {
        float best = Float.NaN;
        float bestScore = -999f;
        for (int i = 0; i < BINS; i++) {
            if (samples[i] == 0) continue;
            float total = 0f;
            float weight = 0f;
            for (int d = -2; d <= 2; d++) {
                int j = (i + d + BINS) % BINS;
                if (samples[j] > 0 && !Float.isNaN(avgDbm[j])) {
                    float w = 3f - Math.abs(d);
                    total += avgDbm[j] * w;
                    weight += w;
                }
            }
            if (weight == 0) continue;
            float score = total / weight;
            if (score > bestScore) {
                bestScore = score;
                best = i * 5f;
            }
        }
        return best;
    }

    public float getBestRssi() {
        float bearing = getBestBearing();
        if (Float.isNaN(bearing)) return Float.NaN;
        int idx = Math.round(bearing / 5f) % BINS;
        return avgDbm[idx];
    }

    public int getConfidencePercent() {
        int covered = getCoveredBins();
        int total = getTotalSamples();
        if (covered < 3) return 0;
        float coverage = Math.min(1f, covered / 30f);
        float density = Math.min(1f, total / 70f);
        return Math.round(100f * (0.65f * coverage + 0.35f * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.43f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Color.rgb(32, 88, 58));
        for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, r * i / 4f, p);
        for (int d = 0; d < 360; d += 30) {
            double a = Math.toRadians(d - 90);
            c.drawLine(cx, cy, cx + (float)Math.cos(a) * r, cy + (float)Math.sin(a) * r, p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(12));
        p.setColor(Color.rgb(127, 236, 166));
        c.drawText("N", cx, cy - r - dp(7), p);
        c.drawText("E", cx + r + dp(10), cy + dp(4), p);
        c.drawText("S", cx, cy + r + dp(17), p);
        c.drawText("W", cx - r - dp(10), cy + dp(4), p);

        RectF oval = new RectF(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(dp(5), r * 0.08f));
        for (int i = 0; i < BINS; i++) {
            if (samples[i] == 0 || Float.isNaN(avgDbm[i])) continue;
            float strength = clamp((avgDbm[i] + 100f) / 65f, 0f, 1f);
            int alpha = 55 + Math.round(200 * strength);
            int red = Math.round(255 * Math.max(0f, (strength - 0.55f) / 0.45f));
            int green = 210;
            p.setColor(Color.argb(alpha, red, green, 70));
            c.drawArc(oval, i * 5f - 92.5f, 5.3f, false, p);
        }

        float best = getBestBearing();
        if (!Float.isNaN(best)) {
            drawArrow(c, cx, cy, r * 0.84f, best, Color.rgb(255, 218, 75), dp(5));
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(13));
            p.setColor(Color.rgb(255, 226, 105));
            c.drawText(String.format(Locale.US, "MELHOR %.0f°", best), cx, cy + dp(7), p);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(12));
            p.setColor(Color.rgb(115, 153, 132));
            c.drawText("Gire o aparelho para mapear", cx, cy + dp(5), p);
        }

        drawArrow(c, cx, cy, r * 0.62f, headingDeg, Color.rgb(60, 190, 255), dp(2));
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(11));
        p.setColor(Color.rgb(103, 201, 255));
        c.drawText(String.format(Locale.US, "S21 %.0f°", headingDeg), cx, cy + r + dp(34), p);
    }

    private void drawArrow(Canvas c, float cx, float cy, float len, float deg, int color, float stroke) {
        double a = Math.toRadians(deg - 90);
        float x = cx + (float)Math.cos(a) * len;
        float y = cy + (float)Math.sin(a) * len;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, cy, x, y, p);
        float head = dp(12);
        double a1 = a + Math.toRadians(155);
        double a2 = a - Math.toRadians(155);
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + (float)Math.cos(a1)*head, y + (float)Math.sin(a1)*head);
        path.moveTo(x, y);
        path.lineTo(x + (float)Math.cos(a2)*head, y + (float)Math.sin(a2)*head);
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static float normalize(float d) { d %= 360f; return d < 0 ? d + 360f : d; }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}
