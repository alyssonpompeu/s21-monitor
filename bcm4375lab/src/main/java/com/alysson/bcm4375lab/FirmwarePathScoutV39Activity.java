package com.alysson.bcm4375lab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only path/SELinux scout after v3.8 proved firmware_class.path but hit EACCES -13 in /data/adb. */
public class FirmwarePathScoutV39Activity extends Activity {
    private static final int SAVE_ZIP = 4384;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, output;
    private Button scan, save;
    private File zipFile;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(7,10,13));
        scroll.addView(root);

        root.addView(text("BCM4375 Scout v3.9", 27, Color.WHITE, true));
        root.addView(text("READ-ONLY • firmware path / SELinux scout", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE, 12, 0xFFCFD8DC, false));

        status = text("O 117.zip mostrou EACCES (-13) em /data/adb. Esta versão não recarrega firmware.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        scan = button("1. MAPEAR CAMINHOS + SELINUX + MFGLOADER");
        scan.setOnClickListener(v -> runScan());
        root.addView(scan);

        Button blocked = button("2. RECARGA BLOQUEADA NESTA VERSÃO");
        blocked.setEnabled(false);
        root.addView(blocked);

        save = button("SALVAR ZIP DE MAPEAMENTO");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        output = text("Toque no passo 1. Nenhum arquivo de firmware será alterado.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(12), 0, 0);
        root.addView(output);
        return scroll;
    }

    private void runScan() {
        if (busy) return;
        busy = true;
        scan.setEnabled(false);
        save.setEnabled(false);
        status.setText("Coletando estado somente-leitura…");
        worker.execute(() -> {
            try {
                String report = collect();
                createZip(report);
                ui.post(() -> {
                    busy = false;
                    scan.setEnabled(true);
                    save.setEnabled(true);
                    status.setTextColor(0xFF81C784);
                    status.setText("MAPEAMENTO CONCLUÍDO • salve o ZIP e envie");
                    output.setText(report);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    scan.setEnabled(true);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(0xFFEF9A9A);
                    status.setText("MAPEAMENTO FALHOU");
                    output.setText(e.toString());
                });
            }
        });
    }

    private String collect() {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Scout v3.9\nREAD_ONLY=true\n");
        r.append("model=").append(Build.MODEL).append(" hardware=").append(Build.HARDWARE).append(" android=").append(Build.VERSION.RELEASE).append("\n");
        r.append("module_state=").append(NexmonOneShotController.moduleState()).append('\n');
        r.append("module_location=").append(NexmonOneShotController.moduleLocation()).append('\n');
        r.append("current_vendor_sha=").append(NexmonOneShotController.currentFirmwareSha()).append('\n');
        r.append("module_sha=").append(NexmonOneShotController.moduleFirmwareSha()).append('\n');
        r.append("firmware_class_path=").append(run("cat /sys/module/firmware_class/parameters/path 2>&1",4)).append('\n');
        r.append("selinux=").append(run("getenforce 2>&1",3)).append('\n');
        r.append("\n=== WIFIVER ===\n").append(NexmonOneShotController.wifiver());

        section(r, "ROOT / BASIC", "id; umask; uname -a; getenforce", 6);
        section(r, "PATH DAC + LABELS",
                "for p in /data /data/adb /data/adb/modules /data/adb/modules/bcm4375_nexmon_oneshot /data/adb/modules/bcm4375_nexmon_oneshot/system/vendor/firmware /data/vendor /data/vendor/wifi /data/vendor/firmware /data/misc /data/misc/wifi /data/local /data/local/tmp /vendor /vendor/firmware /mnt/vendor /mnt/vendor/persist; do echo; echo ##$p; ls -ldZ $p 2>&1; stat -c 'mode=%a uid=%u gid=%g type=%F inode=%i path=%n' $p 2>&1; done",
                12);
        section(r, "NEXMON FILE",
                "F=/data/adb/modules/bcm4375_nexmon_oneshot/system/vendor/firmware/bcmdhd_sta.bin_b1; ls -lZ $F 2>&1; stat -c 'mode=%a uid=%u gid=%g size=%s inode=%i path=%n' $F 2>&1; sha256sum $F 2>&1",
                8);
        section(r, "MFGLOADER",
                "ls -lZ /vendor/bin/hw/mfgloader 2>&1; ps -AZ 2>&1 | grep -i '[m]fgloader'; echo mode=$(getprop vendor.wlandriver.mode); echo status=$(getprop vendor.wlandriver.status); echo wifi_on=$(settings get global wifi_on)",
                7);
        section(r, "MFGLOADER INIT",
                "for d in /vendor/etc/init /odm/etc/init /system/etc/init /system_ext/etc/init; do [ -d $d ] || continue; grep -R -n -B3 -A18 'mfgloader' $d 2>/dev/null; done | head -280",
                9);
        section(r, "FILE CONTEXTS",
                "for f in /vendor/etc/selinux/vendor_file_contexts /odm/etc/selinux/odm_file_contexts /system/etc/selinux/plat_file_contexts /system_ext/etc/selinux/system_ext_file_contexts /product/etc/selinux/product_file_contexts; do [ -f $f ] || continue; echo ---$f; grep -nEi 'mfgloader|vendor/firmware|data/vendor/(wifi|firmware)|firmware(_file)?|wifi.*data|data/adb' $f 2>/dev/null | head -180; done",
                10);
        section(r, "MOUNT NAMESPACES",
                "echo self=$(readlink /proc/self/ns/mnt 2>&1); echo pid1=$(readlink /proc/1/ns/mnt 2>&1); echo '-- self --'; cat /proc/self/mountinfo 2>/dev/null | grep -E '(/vendor|/data/adb|/data/vendor)' | tail -140; echo '-- pid1 --'; cat /proc/1/mountinfo 2>/dev/null | grep -E '(/vendor|/data/adb|/data/vendor)' | tail -140",
                10);
        section(r, "PID1 VIEW",
                "ls -lZ /proc/1/root/vendor/firmware/bcmdhd_sta.bin_b1 2>&1; sha256sum /proc/1/root/vendor/firmware/bcmdhd_sta.bin_b1 2>&1; ls -ldZ /proc/1/root/data/adb /proc/1/root/data/vendor /proc/1/root/data/vendor/wifi 2>&1",
                8);
        section(r, "TOOLS",
                "for x in /system/bin/nsenter /system/bin/runcon /system/bin/setpriv /system/bin/toybox /system/bin/mount /vendor/bin/hw/mfgloader; do [ -e $x ] && ls -lZ $x; done",
                6);
        section(r, "EACCES / FIRMWARE FALLBACK",
                "dmesg | grep -iE 'bcmdhd_sta.bin_b1|bcmdhd_clm.blob|firmware load|Request Firmware API|error -13|Falling back|ueventd.*firmware' | tail -260",
                9);
        section(r, "AVC",
                "dmesg | grep -iE 'avc:.*denied|selinux.*denied' | tail -260",
                8);

        r.append("\n=== SAFETY ===\nfirmware_reload_performed=false\nfirmware_class_path_modified=false\nvendor_file_modified=false\nselinux_modified=false\n");
        return r.toString();
    }

    private static String run(String cmd, int timeout) {
        return RootReader.run(cmd, timeout).output.trim();
    }

    private static void section(StringBuilder r, String title, String cmd, int timeout) {
        RootReader.Result x = RootReader.run(cmd, timeout);
        r.append("\n=== ").append(title).append(" ===\nexit=").append(x.code).append(" timeout=").append(x.timedOut).append('\n').append(x.output);
        if (!x.output.endsWith("\n")) r.append('\n');
    }

    private void createZip(String body) throws Exception {
        File out = new File(getCacheDir(), "BCM4375-Scout-v39.zip");
        if (out.exists()) out.delete();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.putNextEntry(new ZipEntry("v39-path-scout.txt"));
            zos.write(body.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        zipFile = out;
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(i, SAVE_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("OutputStream nulo");
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private TextView text(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
