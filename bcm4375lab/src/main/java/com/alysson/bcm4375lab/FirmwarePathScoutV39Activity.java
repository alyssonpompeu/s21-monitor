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

/**
 * v3.9 is intentionally read-only.
 *
 * v3.8 proved firmware_class.path itself works, but request_firmware() returned
 * EACCES (-13) when the custom directory lived under /data/adb. This screen
 * maps DAC permissions, SELinux labels, init service configuration and mount
 * namespaces before choosing another firmware staging directory.
 */
public class FirmwarePathScoutV39Activity extends Activity {
    private static final int SAVE_ZIP = 4384;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, state, output;
    private Button scan, save;
    private volatile boolean busy;
    private File zipFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(7, 10, 13));
        scroll.addView(root);

        root.addView(text("BCM4375 Lab", 28, Color.WHITE, true));
        root.addView(text("v3.9 • firmware path permission scout • READ-ONLY", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("v3.8 encontrou EACCES (-13). Esta versão só mapeia permissões e contexts.",
                14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Ainda não analisado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        scan = button("1. MAPEAR CAMINHOS + MFGLOADER + SELINUX");
        scan.setOnClickListener(v -> runScan());
        root.addView(scan);

        Button active = button("2. RECARGA DE FIRMWARE BLOQUEADA NESTA VERSÃO");
        active.setEnabled(false);
        root.addView(active);

        save = button("SALVAR ZIP DE MAPEAMENTO");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "Nenhum arquivo é criado em /data/vendor, nenhum parâmetro sysfs é alterado e nenhum serviço Wi-Fi é reiniciado. " +
                "O objetivo é descobrir um diretório que o contexto real do loader Samsung possa atravessar e ler, " +
                "sem depender de /data/adb.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        output = text("Toque no passo 1 e depois salve o ZIP.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);

        return scroll;
    }

    private void runScan() {
        if (busy) return;
        busy = true;
        scan.setEnabled(false);
        save.setEnabled(false);
        status.setTextColor(0xFFFFD180);
        status.setText("Coletando permissões, contexts e configuração do loader…");

        worker.execute(() -> {
            try {
                String report = collect();
                createZip("BCM4375-Lab-S21-v39-path-scout.zip", "v39-path-scout.txt", report);
                ui.post(() -> {
                    busy = false;
                    scan.setEnabled(true);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(0xFF81C784);
                    status.setText("MAPEAMENTO CONCLUÍDO • salve o ZIP; nenhuma recarga foi feita");
                    state.setText(
                            "module=" + NexmonOneShotController.moduleState() + " @ " + NexmonOneShotController.moduleLocation() +
                            "\ncurrent_sha=" + NexmonOneShotController.currentFirmwareSha() +
                            "\nmodule_sha=" + NexmonOneShotController.moduleFirmwareSha() +
                            "\nfwclass_path=" + RootReader.run("cat /sys/module/firmware_class/parameters/path 2>&1", 4).output.trim() +
                            "\nSELinux=" + RootReader.run("getenforce 2>&1", 3).output.trim());
                    output.setText(report);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    scan.setEnabled(true);
                    save.setEnabled(zipFile != null && zipFile.isFile());
                    status.setTextColor(0xFFEF9A9A);
                    status.setText("MAPEAMENTO FALHOU");
                    output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
                });
            }
        });
    }

    private String collect() {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.9 firmware path permission scout\n");
        r.append("READ_ONLY=true\n");
        r.append("Model=").append(Build.MODEL).append('\n');
        r.append("Device=").append(Build.DEVICE).append('\n');
        r.append("Hardware=").append(Build.HARDWARE).append('\n');
        r.append("Android=").append(Build.VERSION.RELEASE).append(" API=").append(Build.VERSION.SDK_INT).append("\n\n");

        append(r, "ROOT / SELINUX", "id; getenforce; umask; echo kernel=$(uname -a)", 6);

        r.append("\n=== NEXMON / RUNTIME STATE ===\n");
        r.append("module_state=").append(NexmonOneShotController.moduleState()).append('\n');
        r.append("module_location=").append(NexmonOneShotController.moduleLocation()).append('\n');
        r.append("current_vendor_sha=").append(NexmonOneShotController.currentFirmwareSha()).append('\n');
        r.append("module_sha=").append(NexmonOneShotController.moduleFirmwareSha()).append('\n');
        r.append("wifiver=\n").append(NexmonOneShotController.wifiver());
        r.append("firmware_class_path=")
                .append(RootReader.run("cat /sys/module/firmware_class/parameters/path 2>&1", 4).output.trim()).append('\n');

        String moduleFile = NexmonOneShotController.ACTIVE_DIR + "/system/vendor/firmware/bcmdhd_sta.bin_b1";
        String paths =
                "/data /data/adb /data/adb/modules " + NexmonOneShotController.ACTIVE_DIR + " " +
                NexmonOneShotController.ACTIVE_DIR + "/system " +
                NexmonOneShotController.ACTIVE_DIR + "/system/vendor " +
                NexmonOneShotController.ACTIVE_DIR + "/system/vendor/firmware " +
                "/data/vendor /data/vendor/wifi /data/vendor/wifi/firmware /data/vendor/firmware " +
                "/data/misc /data/misc/wifi /data/local /data/local/tmp " +
                "/vendor /vendor/firmware /mnt/vendor /mnt/vendor/persist";

        append(r, "PATH DAC + SELINUX LABELS",
                "for p in " + paths + "; do " +
                        "echo; echo '##' $p; " +
                        "ls -ldZ $p 2>&1; " +
                        "stat -c 'mode=%a uid=%u gid=%g type=%F inode=%i path=%n' $p 2>&1; " +
                        "done",
                12);

        append(r, "NEXMON MODULE FILE",
                "ls -lZ '" + moduleFile + "' 2>&1; " +
                        "stat -c 'mode=%a uid=%u gid=%g size=%s inode=%i path=%n' '" + moduleFile + "' 2>&1; " +
                        "sha256sum '" + moduleFile + "' 2>&1",
                8);

        append(r, "MFGLOADER EXECUTABLE / PROCESS",
                "ls -lZ /vendor/bin/hw/mfgloader 2>&1; " +
                        "ps -AZ 2>&1 | grep -i '[m]fgloader'; " +
                        "echo mode=$(getprop vendor.wlandriver.mode); " +
                        "echo status=$(getprop vendor.wlandriver.status); " +
                        "echo wifi_on=$(settings get global wifi_on)",
                6);

        append(r, "MFGLOADER INIT SERVICE",
                "for d in /vendor/etc/init /odm/etc/init /system/etc/init /system_ext/etc/init; do " +
                        "[ -d $d ] || continue; " +
                        "grep -R -n -B3 -A18 'service[[:space:]]\+mfgloader' $d 2>/dev/null; " +
                        "done | head -260",
                8);

        append(r, "FILE_CONTEXTS RELEVANT",
                "for f in /vendor/etc/selinux/vendor_file_contexts /odm/etc/selinux/odm_file_contexts " +
                        "/system/etc/selinux/plat_file_contexts /system_ext/etc/selinux/system_ext_file_contexts " +
                        "/product/etc/selinux/product_file_contexts; do " +
                        "[ -f $f ] || continue; echo '---' $f; " +
                        "grep -nEi 'mfgloader|vendor/firmware|data/vendor/(wifi|firmware)|firmware(_file)?|wifi.*data|data/adb' $f 2>/dev/null | head -180; " +
                        "done",
                10);

        append(r, "MOUNT NAMESPACES",
                "echo self=$(readlink /proc/self/ns/mnt 2>&1); " +
                        "echo pid1=$(readlink /proc/1/ns/mnt 2>&1); " +
                        "echo '-- self mountinfo relevant --'; " +
                        "cat /proc/self/mountinfo 2>/dev/null | grep -E '(/vendor|/data/adb|/data/vendor)' | tail -120; " +
                        "echo '-- pid1 mountinfo relevant --'; " +
                        "cat /proc/1/mountinfo 2>/dev/null | grep -E '(/vendor|/data/adb|/data/vendor)' | tail -120",
                10);

        append(r, "PID1 VIEW OF FIRMWARE",
                "ls -lZ /proc/1/root/vendor/firmware/bcmdhd_sta.bin_b1 2>&1; " +
                        "sha256sum /proc/1/root/vendor/firmware/bcmdhd_sta.bin_b1 2>&1; " +
                        "ls -ldZ /proc/1/root/data/adb /proc/1/root/data/vendor /proc/1/root/data/vendor/wifi 2>&1",
                8);

        append(r, "TOOLS",
                "for x in /system/bin/nsenter /system/bin/runcon /system/bin/setpriv /system/bin/toybox /system/bin/mount /vendor/bin/hw/mfgloader; do " +
                        "[ -e $x ] && ls -lZ $x; done; " +
                        "echo 'toybox applets:'; toybox 2>&1 | tr ' ' '\\n' | grep -E '^(nsenter|runcon|setpriv|mount|stat|chcon)$'",
                6);

        append(r, "RECENT FIRMWARE EACCES / FALLBACK",
                "dmesg | grep -iE 'bcmdhd_sta.bin_b1|bcmdhd_clm.blob|firmware load|Request Firmware API|error -13|Falling back|ueventd.*firmware' | tail -220",
                8);

        append(r, "RECENT AVC",
                "dmesg | grep -iE 'avc:.*denied|selinux.*denied' | tail -220",
                7);

        r.append("\n=== SAFETY ASSERTION ===\n");
        r.append("firmware_reload_performed=false\n");
        r.append("firmware_class_path_modified=false\n");
        r.append("vendor_file_modified=false\n");
        r.append("selinux_modified=false\n");
        return r.toString();
    }

    private static void append(StringBuilder r, String title, String cmd, int timeout) {
        RootReader.Result x = RootReader.run(cmd, timeout);
        r.append("\n=== ").append(title).append(" ===\n");
        r.append("exit=").append(x.code).append(" timeout=").append(x.timedOut).append('\n');
        r.append(x.output);
        if (!x.output.endsWith("\n")) r.append('\n');
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File work = new File(getCacheDir(), "bcm4375-v39");
        deleteRecursive(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Falha criando cache");

        File report = new File(work, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) {
            fos.write(body.getBytes(StandardCharsets.UTF_8));
        }

        File out = new File(getCacheDir(), zipName);
        if (out.exists()) out.delete();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
             FileInputStream in = new FileInputStream(report)) {
            zos.putNextEntry(new ZipEntry(reportName));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("OutputStream nulo");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo. Envie aqui.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
