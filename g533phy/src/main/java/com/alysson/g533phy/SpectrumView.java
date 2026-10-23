package com.alysson.g533phy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Locale;

/** Spectrum of relative ON/OFF power; not an RF receiver or a device identifier. */
public final class SpectrumView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] spectrum;
    private double centerMHz;
    private double sampleRate;

    public SpectrumView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setResult(IQAnalyzer.Result r) {
        spectrum = r.deltaSpectrumDb;
        centerMHz = r.centerMHz;
        sampleRate = r.sampleRate;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(18, 25, 36));
        float left = dp(49), right = w - dp(12), top = dp(28), bottom = h - dp(38);
        if (right <= left || bottom <= top) return;
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(215, 227, 236));
        paint.setStyle(Paint.Style.FILL);
        c.drawText("Δ potência espectral ON / OFF (dB)", left, dp(16), paint);
        float ymin = -35, ymax = 45;
        for (int value = -20; value <= 40; value += 20) {
            float y = bottom - (value - ymin) / (ymax - ymin) * (bottom - top);
            paint.setColor(value == 0 ? Color.rgb(140, 159, 178) : Color.rgb(52, 70, 86));
            paint.setStrokeWidth(value == 0 ? dp(1.4f) : dp(0.8f));
            c.drawLine(left, y, right, y, paint);
            paint.setColor(Color.rgb(177, 199, 214));
            c.drawText(String.format(Locale.US, "%+d", value), dp(8), y + dp(4), paint);
        }
        float mid = (left + right) * 0.5f;
        paint.setColor(Color.rgb(65, 80, 100));
        c.drawLine(mid, top, mid, bottom, paint);
        paint.setColor(Color.rgb(177, 199, 214));
        String center = String.format(Locale.US, "%.3f", centerMHz);
        String lo = String.format(Locale.US, "%.3f", centerMHz - sampleRate / 2e6);
        String hi = String.format(Locale.US, "%.3f MHz", centerMHz + sampleRate / 2e6);
        c.drawText(lo, left, h - dp(15), paint);
        c.drawText(center, mid - dp(18), h - dp(15), paint);
        c.drawText(hi, right - paint.measureText(hi), h - dp(15), paint);
        if (spectrum == null || spectrum.length < 2) {
            paint.setColor(Color.rgb(132, 161, 181));
            c.drawText("Execute a demonstração ou importe duas capturas.", left, h / 2f, paint);
            return;
        }
        Path path = new Path();
        for (int j = 0; j < spectrum.length; j++) {
            float x = left + (right - left) * j / (spectrum.length - 1f);
            double v = Math.max(ymin, Math.min(ymax, spectrum[j]));
            float y = (float) (bottom - (v - ymin) / (ymax - ymin) * (bottom - top));
            if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.rgb(67, 218, 205));
        paint.setStrokeWidth(dp(1.4f));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
