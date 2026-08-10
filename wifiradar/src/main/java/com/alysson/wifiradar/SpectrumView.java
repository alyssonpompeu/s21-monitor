package com.alysson.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.wifi.ScanResult;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpectrumView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<ScanResult> results = new ArrayList<>();
    private String targetBssid;

    public SpectrumView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(6, 10, 15));
    }

    public void setData(List<ScanResult> data, String target) {
        results = data == null ? new ArrayList<>() : new ArrayList<>(data);
        targetBssid = target;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float left = dp(44), right = w - dp(10), top = dp(12), bottom = h - dp(12);
        float rowH = (bottom - top) / 3f;
        drawBand(c, left, right, top, rowH, 2400, 2500, "2.4 GHz");
        drawBand(c, left, right, top + rowH, rowH, 4900, 5900, "5 GHz");
        drawBand(c, left, right, top + rowH * 2f, rowH, 5925, 7125, "6 GHz");
    }

    private void drawBand(Canvas c, float left, float right, float y, float rowH, int minF, int maxF, String label) {
        float base = y + rowH - dp(18);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Color.rgb(43, 62, 78));
        c.drawLine(left, base, right, base, p);
        for (int i = 0; i <= 4; i++) {
            float yy = y + dp(8) + (base - y - dp(8)) * i / 4f;
            c.drawLine(left, yy, right, yy, p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(10));
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.rgb(150, 180, 205));
        c.drawText(label, dp(5), y + dp(14), p);
        p.setTextSize(dp(8));
        p.setColor(Color.rgb(98, 121, 141));
        c.drawText("-30", dp(8), y + dp(28), p);
        c.drawText("-90", dp(8), base, p);

        for (ScanResult s : results) {
            int f = s.frequency;
            if (f < minF || f > maxF) continue;
            boolean target = targetBssid != null && targetBssid.equalsIgnoreCase(s.BSSID);
            float strength = clamp((s.level + 100f) / 70f, 0f, 1f);
            float centerX = left + (f - minF) * (right - left) / (maxF - minF);
            int widthMhz = channelWidthMHz(s);
            float halfX = Math.max(dp(8), widthMhz * (right - left) / (maxF - minF) * 0.62f);
            float peakY = base - strength * (rowH - dp(36));

            Path shape = new Path();
            shape.moveTo(centerX - halfX, base);
            shape.quadTo(centerX - halfX * 0.45f, peakY, centerX, peakY);
            shape.quadTo(centerX + halfX * 0.45f, peakY, centerX + halfX, base);

            int color = target ? Color.rgb(255, 220, 72) : colorForFrequency(f);
            p.setColor(Color.argb(target ? 240 : 150, Color.red(color), Color.green(color), Color.blue(color)));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(target ? dp(3) : dp(1.5f));
            c.drawPath(shape, p);

            if (target || s.level > -58) {
                String ssid = s.SSID == null || s.SSID.isEmpty() ? "<oculta>" : s.SSID;
                if (ssid.length() > 12) ssid = ssid.substring(0, 12) + "…";
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(dp(target ? 9 : 8));
                p.setColor(color);
                c.drawText(ssid, centerX, Math.max(y + dp(12), peakY - dp(4)), p);
            }
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(8));
        p.setColor(Color.rgb(91, 112, 130));
        c.drawText(String.format(Locale.US, "%d MHz", minF), left, base + dp(11), p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.US, "%d MHz", maxF), right, base + dp(11), p);
    }

    private int colorForFrequency(int f) {
        if (f < 3000) return Color.rgb(92, 218, 138);
        if (f < 5925) return Color.rgb(91, 174, 255);
        return Color.rgb(194, 123, 255);
    }

    private int channelWidthMHz(ScanResult s) {
        switch (s.channelWidth) {
            case ScanResult.CHANNEL_WIDTH_40MHZ: return 40;
            case ScanResult.CHANNEL_WIDTH_80MHZ: return 80;
            case ScanResult.CHANNEL_WIDTH_160MHZ: return 160;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: return 160;
            default: return 20;
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}
