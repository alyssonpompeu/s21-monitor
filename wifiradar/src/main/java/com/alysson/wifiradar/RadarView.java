package com.alysson.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class RadarView extends View {
    private static final int BINS = 72;
    private final float[] avgDbm = new float[BINS];
    private final int[] samples = new int[BINS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Contact> contacts = new ArrayList<>();
    private float headingDeg = 0f;
    private String selectedBssid;
    private String selectedSsid;
    private int selectedRssi = -127;

    public RadarView(Context context) {
        super(context);
        Arrays.fill(avgDbm, Float.NaN);
        setBackgroundColor(Color.rgb(4, 12, 9));
    }

    public void setHeading(float heading) {
        headingDeg = normalize(heading);
        invalidate();
    }

    public void setContacts(List<Contact> list, String selectedBssid) {
        contacts.clear();
        if (list != null) contacts.addAll(list);
        this.selectedBssid = selectedBssid;
        invalidate();
    }

    public void setSelectedSignal(String ssid, String bssid, int rssi) {
        selectedSsid = ssid;
        selectedBssid = bssid;
        selectedRssi = rssi;
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
        float cx = w / 2f;
        float radarTop = dp(112);
        float cy = radarTop + Math.min(w, h - radarTop) * 0.46f;
        float r = Math.min(w * 0.43f, (h - radarTop) * 0.41f);

        drawContactsPanel(c, w);

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
        }
        drawArrow(c, cx, cy, r * 0.62f, headingDeg, Color.rgb(60, 190, 255), dp(2));

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(dp(14));
        String center = selectedSsid == null ? (contacts.isEmpty() ? "0 REDES" : "SELECIONE UM ALVO") : trim(selectedSsid, 24);
        c.drawText(center, cx, cy - dp(8), p);

        p.setTextSize(dp(13));
        if (selectedRssi < 0 && selectedRssi >= -120) {
            p.setColor(signalColor(selectedRssi));
            c.drawText(selectedRssi + " dBm", cx, cy + dp(13), p);
        }

        p.setTextSize(dp(11));
        p.setColor(Color.rgb(103, 201, 255));
        c.drawText(String.format(Locale.US, "S21 %.0f°", headingDeg), cx, cy + r + dp(34), p);

        if (!Float.isNaN(best)) {
            p.setTextSize(dp(12));
            p.setColor(Color.rgb(255, 226, 105));
            c.drawText(String.format(Locale.US, "MELHOR %.0f°", best), cx, cy + r + dp(52), p);
        } else {
            p.setTextSize(dp(11));
            p.setColor(Color.rgb(145, 175, 155));
            c.drawText("direção: aguardando varredura 360°", cx, cy + r + dp(52), p);
        }
    }

    private void drawContactsPanel(Canvas c, float width) {
        float left = dp(8);
        float top = dp(8);
        float right = width - dp(8);
        int shown = Math.min(4, contacts.size());
        float height = dp(30 + shown * 18);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(190, 8, 19, 16));
        c.drawRoundRect(new RectF(left, top, right, top + height), dp(8), dp(8), p);

        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(11));
        p.setColor(Color.rgb(114, 220, 160));
        c.drawText("CONTATOS Wi‑Fi: " + contacts.size() + "  • direção somente após varredura", left + dp(8), top + dp(17), p);

        for (int i = 0; i < shown; i++) {
            Contact x = contacts.get(i);
            boolean selected = selectedBssid != null && selectedBssid.equalsIgnoreCase(x.bssid);
            p.setColor(selected ? Color.rgb(255, 221, 90) : Color.rgb(205, 232, 215));
            p.setTextSize(dp(11));
            String marker = selected ? "▶ " : "● ";
            String connected = x.connected ? " ★" : "";
            String line = marker + trim(x.ssid, 19) + connected + "   " + x.rssi + " dBm";
            c.drawText(line, left + dp(8), top + dp(36 + i * 18), p);
        }
        if (contacts.size() > shown) {
            p.setColor(Color.rgb(140, 165, 150));
            p.setTextSize(dp(10));
            c.drawText("+ " + (contacts.size() - shown) + " outras redes", right - dp(90), top + height - dp(5), p);
        }
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

    private static int signalColor(int rssi) {
        if (rssi >= -55) return Color.rgb(110, 235, 135);
        if (rssi >= -70) return Color.rgb(255, 216, 90);
        return Color.rgb(255, 125, 100);
    }

    private static String trim(String s, int n) {
        if (s == null) return "<sem nome>";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static float normalize(float d) { d %= 360f; return d < 0 ? d + 360f : d; }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    public static final class Contact {
        public final String ssid;
        public final String bssid;
        public final int rssi;
        public final int frequency;
        public final boolean connected;

        public Contact(String ssid, String bssid, int rssi, int frequency, boolean connected) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.rssi = rssi;
            this.frequency = frequency;
            this.connected = connected;
        }
    }
}
