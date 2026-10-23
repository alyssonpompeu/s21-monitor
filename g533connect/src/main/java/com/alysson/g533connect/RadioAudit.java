package com.alysson.g533connect;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class RadioAudit {
    public static final String EXPECTED_FWID = "01-1b4772fd";
    public static final String EXPECTED_SHA = "1676f46ce56b96f58dc70de08beaab4ab3362ee6dd751465a8d6a0023c3c54ad";
    private RadioAudit() {}

    public static final class Report {
        public final String content;
        public final boolean root;
        public final boolean wifiIdentity;
        public final boolean shaMatch;
        public final int dongles;
        public final int headsets;
        public final boolean usbAvailable;
        Report(String content, boolean root, boolean wifiIdentity, boolean shaMatch,
               int dongles, int headsets, boolean usbAvailable) {
            this.content = content;
            this.root = root;
            this.wifiIdentity = wifiIdentity;
            this.shaMatch = shaMatch;
            this.dongles = dongles;
            this.headsets = headsets;
            this.usbAvailable = usbAvailable;
        }
        public String verdict() {
            return wifiIdentity && shaMatch
                ? "Firmware Wi-Fi confirmado; protocolo Avnera NÃO disponível."
                : "Protocolo Avnera NÃO disponível; verifique o diagnóstico.";
        }
    }

    /** Android diagnostics use fixed read-only shell commands. No module, firmware, or RF writes. */
    public static Report run(Context ctx) {
        StringBuilder b = new StringBuilder();
        b.append("G533 CONNECT • AUDITORIA READ-ONLY\n")
         .append("Modelo: ").append(Build.MANUFACTURER).append(" ")
         .append(Build.MODEL).append("\nAndroid: ").append(Build.VERSION.RELEASE)
         .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        String who = command("su", "-c", "id");
        boolean root = who.contains("uid=0");
        b.append("\nROOT\n").append(who).append('\n');
        String fw = command(root ? "su" : "sh", "-c",
                "cat /sys/wifi/wifiver 2>&1; getprop ro.build.display.id");
        boolean identity = fw.contains("BCM4375B1") && fw.contains("18.41.117")
            && fw.contains(EXPECTED_FWID);
        b.append("\nFIRMWARE ATIVO\n").append(fw).append('\n');
        String hash = command(root ? "su" : "sh", "-c",
                "if [ -r /vendor/firmware/bcmdhd_sta.bin_b1 ]; then " +
                "sha256sum /vendor/firmware/bcmdhd_sta.bin_b1; " +
                "stat -c 'bytes=%s' /vendor/firmware/bcmdhd_sta.bin_b1; else echo acesso_negado; fi");
        boolean match = hash.contains(EXPECTED_SHA) && hash.contains("bytes=1297036");
        b.append("\nIMAGEM Wi-Fi STOCK\n").append(hash).append('\n');
        String hcd = command(root ? "su" : "sh", "-c",
                "for f in /vendor/firmware/bcm4375B1_semco.hcd " +
                "/vendor/firmware/bcm4375B1_semco_sem.hcd; do " +
                "if [ -r \"$f\" ]; then sha256sum \"$f\"; fi; done");
        b.append("\nHCDs PRESENTES (não implica HCD carregado)\n").append(hcd).append('\n');
        int dongles = 0, headsets = 0;
        boolean usbSupported = false;
        UsbManager manager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (manager != null) {
            usbSupported = true;
            for (UsbDevice device : manager.getDeviceList().values()) {
                int vid = device.getVendorId(), pid = device.getProductId();
                b.append(String.format(Locale.US,
                     "USB DEVICE VID=%04X PID=%04X\n", vid, pid));
                if (vid == 0x046d && pid == 0x0a66) dongles++;
                if (vid == 0x046d && pid == 0x0a67) headsets++;
            }
        }
        b.append("\nG533 dongle USB A-00073: ").append(dongles).append('\n')
         .append("G533 headset via cabo USB: ").append(headsets).append('\n');
        if (dongles > 0)
            b.append("Dongle detectado por USB. Não foi executado pareamento HID.\n");
        b.append("\nAVNERA RADIO PHY: NÃO IMPLEMENTADO\n")
         .append("Nenhum dado RF G533 capturado; nenhum pacote de pairing enviado.\n")
         .append("Wi-Fi Nexmon e RadioSploit NÃO implementam automaticamente DQPSK Avnera.\n")
         .append("SELinux, firmware e rádio não foram alterados.\n");
        return new Report(b.toString(), root, identity, match, dongles, headsets, usbSupported);
    }

    private static String command(String... args) {
        Process p = null;
        try {
            p = new ProcessBuilder(args).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 45) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out + "[timeout]";
            }
            return out.toString();
        } catch (Exception e) {
            return "[" + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
