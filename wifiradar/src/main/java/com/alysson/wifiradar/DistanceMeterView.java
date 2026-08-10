package com.alysson.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

public final class DistanceMeterView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String ssid = "Aguardando rede";
    private double distanceMeters = Double.NaN;
    private int rssiDbm = -127;
    private int frequencyMhz = 0;
    private boolean live = false;

    public DistanceMeterView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(8, 18, 22));
    }

    public void setMeasurement(String ssid, double distanceMeters, int rssiDbm, int frequencyMhz, boolean live) {
        this.ssid = ssid == null || ssid.isEmpty() ? "<rede sem nome>" : ssid;
        this.distanceMeters = distanceMeters;
        this.rssiDbm = rssiDbm;
        this.frequencyMhz = frequencyMhz;
        this.live = live;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(9, 25, 29));
        c.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(10), dp(10), p);

        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.rgb(102, 215, 255));
        p.setTextSize(dp(11));
        c.drawText("DISTÂNCIA APROXIMADA DO WI‑FI", dp(12), dp(20), p);

        p.setColor(Color.WHITE);
        p.setTextSize(dp(17));
        c.drawText(trim(ssid, 28), dp(12), dp(44), p);

        p.setColor(Color.rgb(120, 238, 165));
        p.setTextSize(dp(25));
        c.drawText("≈ " + DistanceEstimator.formatMeters(distanceMeters), dp(12), dp(75), p);

        p.setColor(Color.rgb(190, 205, 214));
        p.setTextSize(dp(11));
        String source = live ? "ao vivo" : "último scan";
        String freq = frequencyMhz > 0 ? frequencyMhz + " MHz" : "freq. ?";
        c.drawText(rssiDbm + " dBm • " + freq + " • " + source, dp(12), dp(94), p);

        float left = dp(18);
        float right = w - dp(18);
        float y = dp(119);

        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(6));
        p.setColor(Color.rgb(35, 63, 69));
        c.drawLine(left, y, right, y, p);
        p.setStrokeCap(Paint.Cap.BUTT);

        double[] ticks = {0.5, 1, 2, 5, 10, 20, 30};
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(9));
        for (double tick : ticks) {
            float x = position(tick, left, right);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.rgb(88, 125, 132));
            c.drawLine(x, y - dp(7), x, y + dp(7), p);
            p.setColor(Color.rgb(155, 178, 184));
            String label = tick < 1 ? "0,5" : String.valueOf((int)tick);
            c.drawText(label, x, y + dp(20), p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(8));
        p.setColor(Color.rgb(125, 150, 156));
        c.drawText("metros", right - dp(26), y + dp(20), p);

        if (!Double.isNaN(distanceMeters)) {
            float x = position(distanceMeters, left, right);
            p.setColor(Color.rgb(80, 202, 255));
            p.setStyle(Paint.Style.FILL);
            Path marker = new Path();
            marker.moveTo(x, y - dp(13));
            marker.lineTo(x - dp(7), y - dp(23));
            marker.lineTo(x + dp(7), y - dp(23));
            marker.close();
            c.drawPath(marker, p);
            c.drawCircle(x, y, dp(6), p);
        }

        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(9));
        p.setColor(Color.rgb(244, 205, 115));
        if (!Double.isNaN(distanceMeters)) {
            String range = DistanceEstimator.formatMeters(DistanceEstimator.lowerLikelyMeters(distanceMeters)) +
                    " a " + DistanceEstimator.formatMeters(DistanceEstimator.upperLikelyMeters(distanceMeters));
            c.drawText("Faixa provável: " + range + " • estimativa por RSSI", dp(12), h - dp(8), p);
        } else {
            c.drawText("Aguardando RSSI válido para estimar a distância.", dp(12), h - dp(8), p);
        }
    }

    private float position(double meters, float left, float right) {
        double clamped = Math.max(0.3, Math.min(30.0, meters));
        double min = Math.log10(1.3);
        double max = Math.log10(31.0);
        double value = Math.log10(1.0 + clamped);
        float t = (float)((value - min) / (max - min));
        t = Math.max(0f, Math.min(1f, t));
        return left + (right - left) * t;
    }

    private static String trim(String s, int n) {
        if (s == null) return "<sem nome>";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
