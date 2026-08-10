package com.alysson.wifispectrum;

public final class WifiSample {
    public final String ssid;
    public final String bssid;
    public final int frequencyMhz;
    public final int rssiDbm;
    public final int widthMhz;

    public WifiSample(String ssid, String bssid, int frequencyMhz, int rssiDbm, int widthMhz) {
        this.ssid = ssid;
        this.bssid = bssid;
        this.frequencyMhz = frequencyMhz;
        this.rssiDbm = rssiDbm;
        this.widthMhz = widthMhz;
    }
}
