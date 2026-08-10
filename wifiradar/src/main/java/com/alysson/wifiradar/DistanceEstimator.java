package com.alysson.wifiradar;

import java.util.Locale;

final class DistanceEstimator {
    private static final double MIN_METERS = 0.30;
    private static final double MAX_METERS = 50.0;
    private static final double INDOOR_PATH_LOSS_EXPONENT = 2.70;

    private DistanceEstimator() {}

    static double estimateMeters(int rssiDbm, int frequencyMhz) {
        if (rssiDbm >= 0 || rssiDbm < -120) return Double.NaN;

        // RSSI de referência aproximado a 1 m. A frequência mais alta normalmente
        // sofre maior perda no mesmo ambiente. Não é uma medição ToF/RTT.
        double referenceAt1m;
        if (frequencyMhz >= 5925) referenceAt1m = -47.0;
        else if (frequencyMhz >= 4900) referenceAt1m = -45.0;
        else referenceAt1m = -42.0;

        double meters = Math.pow(10.0, (referenceAt1m - rssiDbm) / (10.0 * INDOOR_PATH_LOSS_EXPONENT));
        if (Double.isNaN(meters) || Double.isInfinite(meters)) return Double.NaN;
        return Math.max(MIN_METERS, Math.min(MAX_METERS, meters));
    }

    static String formatMeters(double meters) {
        if (Double.isNaN(meters)) return "—";
        if (meters >= 50.0) return "50+ m";
        if (meters >= 10.0) return String.format(Locale.getDefault(), "%.0f m", meters);
        return String.format(Locale.getDefault(), "%.1f m", meters);
    }

    static double lowerLikelyMeters(double meters) {
        if (Double.isNaN(meters)) return Double.NaN;
        return Math.max(MIN_METERS, meters * 0.55);
    }

    static double upperLikelyMeters(double meters) {
        if (Double.isNaN(meters)) return Double.NaN;
        return Math.min(MAX_METERS, meters * 1.80);
    }
}
