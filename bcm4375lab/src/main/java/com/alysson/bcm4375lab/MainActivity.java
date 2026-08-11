package com.alysson.bcm4375lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int SAVE_ZIP = 4375;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView stateView;
    private TextView output;
    private Button preflightButton;
    private Button prepareButton;
    private Button armButton;
    private Button rebootButton;
    private Button disarmButton;
    private Button collectButton;
    private Button saveButton;
    private File zipFile;
    private volatile boolean busy;

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

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(7, 10, 13));
        scroll.addView(root);

        root.addView(text("BCM4375 Lab", 28, Color.WHITE, true));
        TextView subtitle = text("v3.0 • Nexmon BCM4375B1 18.41.117 • Magisk one-shot", 12, 0xFF80CBC4, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        TextView device = text("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
                "\nDevice: " + Build.DEVICE + " • Hardware: " + Build.HARDWARE +
                "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT, 12, 0xFFCFD8DC, false);
        device.setPadding(dp(10), dp(10), dp(10), dp(10));
        device.setBackgroundColor(0xFF172027);
        root.addView(device);

        status = text("Comece pelo preflight. Nenhum arquivo de /vendor será gravado diretamente.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        stateView = text("Estado ainda não verificado.", 12, 0xFFB0BEC5, false);
        stateView.setTypeface(Typeface.MONOSPACE);
        stateView.setPadding(0, 0, 0, dp(12));
        root.addView(stateView);

        preflightButton = button("1. NEXMON PREFLIGHT");
        preflightButton.setOnClickListener(v -> runPreflight());
        root.addView(preflightButton);

        prepareButton = button("2. BAIXAR + PREPARAR MÓDULO DESATIVADO");
        prepareButton.setEnabled(false);
        prepareButton.setOnClickListener(v -> confirmPrepare());
        root.addView(prepareButton);

        armButton = button("3. ARMAR NEXMON PARA O PRÓXIMO BOOT");
        armButton.setEnabled(false);
        armButton.setOnClickListener(v -> confirmArm());
        root.addView(armButton);

        rebootButton = button("4. REINICIAR PARA TESTAR NEXMON");
        rebootButton.setEnabled(false);
        rebootButton.setOnClickListener(v -> confirmReboot());
        root.addView(rebootButton);

        disarmButton = button("DESARMAR / GARANTIR STOCK NO PRÓXIMO BOOT");
        disarmButton.setEnabled(false);
        disarmButton.setOnClickListener(v -> disarm());
        root.addView(disarmButton);

        collectButton = button("5. COLETAR RESULTADO NEXMON");
        collectButton.setEnabled(false);
        collectButton.setOnClickListener(v -> collectEvidence());
        root.addView(collectButton);

        saveButton = button("SALVAR ÚLTIMO PACOTE ZIP");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveZip());
        root.addView(saveButton);

        TextView note = text(
                "Proteção one-shot: o módulo substitui somente /vendor/firmware/bcmdhd_sta.bin_b1 via overlay Magisk. " +
                "No primeiro boot ativo, post-fs-data.sh cria o marcador disable, portanto o boot seguinte volta ao firmware Samsung. " +
                "A v3.0 não altera SELinux e ainda não executa nexutil/injection.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        output = text("Toque em 1. NEXMON PREFLIGHT.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private void runPreflight() {
        if (busy) return;
        setBusy("Executando Nexmon preflight…");
        worker.execute(() -> {
            try {
                RootReader.Result root = RootReader.run("id", 30);
                if (root.timedOut || root.code != 0 || !root.output.contains("uid=0")) {
                    showFailure("ROOT INDISPONÍVEL", root.output);
                    return;
                }

                State s = readState();
                String report = buildPreflightReport(s);
                createReportZip("BCM4375-Lab-S21-nexmon-preflight.zip", "bcm4375-nexmon-preflight.txt", report);
                ui.post(() -> applyState(s, report));
            } catch (Exception e) {
                showFailure("Falha no preflight", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private State readState() {
        String wifiver = NexmonOneShotController.wifiver();
        String currentSha = NexmonOneShotController.currentFirmwareSha();
        String moduleState = NexmonOneShotController.moduleState();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        boolean root = RootReader.run("id", 5).output.contains("uid=0");
        boolean model = "SM-G991B".equalsIgnoreCase(Build.MODEL);
        boolean hw = "exynos2100".equalsIgnoreCase(Build.HARDWARE);
        boolean magisk = NexmonOneShotController.magiskReady();
        boolean nexmonActive = NexmonOneShotController.isNexmonActive();
        boolean stockNetwork = wifiver.contains("18.41.117") && wifiver.contains("B1 Network") && NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(currentSha);
        boolean moduleValid = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        return new State(root, model, hw, magisk, nexmonActive, stockNetwork, currentSha, moduleState, moduleSha, wifiver, moduleValid);
    }

    private String buildPreflightReport(State s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.0.0 - Nexmon preflight\n\n");
        r.append(check("root", s.root));
        r.append(check("SM-G991B", s.model));
        r.append(check("Exynos 2100", s.hw));
        r.append(check("Magisk modules writable", s.magisk));
        r.append(check("Stock B1 Network + stock SHA", s.stockNetwork));
        r.append("[INFO] Nexmon active = ").append(s.nexmonActive).append('\n');
        r.append("[INFO] Current firmware SHA = ").append(s.currentSha).append('\n');
        r.append("[INFO] Module state = ").append(s.moduleState).append('\n');
        r.append("[INFO] Module firmware SHA = ").append(s.moduleSha).append('\n');
        r.append("[INFO] Module firmware valid = ").append(s.moduleValid).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== MAGISK ===\n").append(NexmonOneShotController.magiskInfo());
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        r.append("\nEXPECTED_STOCK_SHA=").append(NexmonOneShotController.STOCK_SHA).append('\n');
        r.append("EXPECTED_NEXMON_SHA=").append(NexmonOneShotController.NEXMON_SHA).append('\n');
        return r.toString();
    }

    private void applyState(State s, String report) {
        busy = false;
        boolean baseOk = s.root && s.model && s.hw && s.magisk;
        boolean canPrepare = baseOk && s.stockNetwork && !s.nexmonActive;
        boolean preparedDisabled = s.moduleValid && "DISABLED".equals(s.moduleState);
        boolean armed = s.moduleValid && "ARMED".equals(s.moduleState) && !s.nexmonActive;
        boolean activeSafe = s.nexmonActive && ("DISABLED".equals(s.moduleState) || "REMOVE_PENDING".equals(s.moduleState));

        preflightButton.setEnabled(true);
        prepareButton.setEnabled(canPrepare);
        armButton.setEnabled(preparedDisabled && s.stockNetwork);
        disarmButton.setEnabled(s.moduleValid || s.nexmonActive || "ARMED".equals(s.moduleState));
        collectButton.setEnabled(s.nexmonActive || new File("/data/adb/bcm4375_nexmon_oneshot_result.txt").exists());
        saveButton.setEnabled(zipFile != null && zipFile.isFile());

        if (armed) {
            rebootButton.setText("4. REINICIAR PARA TESTAR NEXMON");
            rebootButton.setEnabled(true);
            status.setTextColor(0xFFFFD180);
            status.setText("NEXMON ARMADO • próximo boot usará o patch one-shot");
        } else if (s.nexmonActive) {
            rebootButton.setText("6. REINICIAR PARA VOLTAR AO STOCK");
            rebootButton.setEnabled(activeSafe);
            status.setTextColor(activeSafe ? 0xFF81C784 : 0xFFEF9A9A);
            status.setText(activeSafe ? "NEXMON ATIVO • one-shot já desarmado para o próximo boot" : "NEXMON ATIVO • DESARME antes de reiniciar");
        } else if (preparedDisabled) {
            rebootButton.setEnabled(false);
            status.setTextColor(0xFF81C784);
            status.setText("MÓDULO NEXMON PREPARADO E DESATIVADO • seguro");
        } else if (canPrepare) {
            rebootButton.setEnabled(false);
            status.setTextColor(0xFF81C784);
            status.setText("PREFLIGHT OK • pronto para baixar/preparar o módulo");
        } else {
            rebootButton.setEnabled(false);
            status.setTextColor(0xFFFFD180);
            status.setText("PREFLIGHT CONCLUÍDO • veja os itens abaixo antes de prosseguir");
        }

        stateView.setText(
                "firmware=" + (s.nexmonActive ? "NEXMON" : (s.stockNetwork ? "STOCK" : "OUTRO")) +
                "\nmodule=" + s.moduleState +
                "\ncurrent_sha=" + s.currentSha +
                "\nmodule_sha=" + s.moduleSha);
        output.setText(report);
    }

    private void confirmPrepare() {
        new AlertDialog.Builder(this)
                .setTitle("Preparar Nexmon one-shot")
                .setMessage("O app baixará o firmware da release verificada, exigirá o SHA-256 exato e instalará um módulo Magisk DESATIVADO. O Wi-Fi e o boot atual não serão alterados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Preparar", (d, w) -> prepareModule())
                .show();
    }

    private void prepareModule() {
        if (busy) return;
        setBusy("Baixando firmware Nexmon…");
        worker.execute(() -> {
            try {
                State before = readState();
                if (!(before.root && before.model && before.hw && before.magisk && before.stockNetwork && !before.nexmonActive)) {
                    throw new Exception("Estado mudou desde o preflight. Execute o preflight novamente.");
                }
                File firmware = NexmonOneShotController.downloadVerifiedFirmware(this, this::postStatus);
                postStatus("Montando módulo Magisk one-shot…");
                File moduleZip = NexmonOneShotController.buildModuleZip(this, firmware);
                postStatus("Instalando módulo em estado DESATIVADO…");
                RootReader.Result install = NexmonOneShotController.installDisabledModule(moduleZip);
                if (install.timedOut || install.code != 0) throw new Exception("Falha no magisk --install-module: " + install.output);
                State after = readState();
                if (!after.moduleValid || !"DISABLED".equals(after.moduleState)) {
                    NexmonOneShotController.disarmNextBoot();
                    throw new Exception("Módulo não ficou validado/desativado após a instalação.");
                }
                String report = buildPreflightReport(after) + "\nMODULE_PREPARED=YES\nMODULE_ZIP_SHA256=" + NexmonOneShotController.sha256(moduleZip) + "\n";
                createReportZip("BCM4375-Lab-S21-nexmon-module-prepared.zip", "bcm4375-nexmon-module-prepared.txt", report);
                ui.post(() -> applyState(after, report));
            } catch (Exception e) {
                NexmonOneShotController.disarmNextBoot();
                showFailure("Falha preparando módulo", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private void confirmArm() {
        new AlertDialog.Builder(this)
                .setTitle("Armar próximo boot")
                .setMessage("Depois de armar, o PRÓXIMO reboot usará o firmware Nexmon. O módulo se auto-desativa durante esse boot para que o reboot seguinte volte ao Samsung stock.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Armar", (d, w) -> arm())
                .show();
    }

    private void arm() {
        if (busy) return;
        setBusy("Armando Nexmon one-shot…");
        worker.execute(() -> {
            try {
                State before = readState();
                if (!before.stockNetwork || !before.moduleValid || !"DISABLED".equals(before.moduleState)) {
                    throw new Exception("Estado inseguro para armar. Execute o preflight novamente.");
                }
                RootReader.Result r = NexmonOneShotController.armNextBoot();
                if (r.timedOut || r.code != 0) throw new Exception("Não foi possível remover o marcador disable: " + r.output);
                State after = readState();
                if (!"ARMED".equals(after.moduleState)) throw new Exception("O módulo não ficou ARMED.");
                ui.post(() -> applyState(after, buildPreflightReport(after) + "\nONE_SHOT_ARMED=YES\n"));
            } catch (Exception e) {
                NexmonOneShotController.disarmNextBoot();
                showFailure("Falha ao armar", e.getMessage());
            }
        });
    }

    private void confirmReboot() {
        if (busy) return;
        State s = readState();
        if (s.nexmonActive) {
            new AlertDialog.Builder(this)
                    .setTitle("Voltar ao Samsung stock")
                    .setMessage("O módulo será mantido desativado e o telefone reiniciará. O próximo boot deverá usar bcmdhd_sta.bin_b1 original.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Reiniciar stock", (d, w) -> rebootStock())
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Iniciar teste Nexmon")
                    .setMessage("O telefone reiniciará. Este próximo boot usa Nexmon uma vez. Depois que iniciar, abra novamente o BCM4375 Lab e execute COLETAR RESULTADO NEXMON antes de reiniciar para stock.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Reiniciar para Nexmon", (d, w) -> rebootArmed())
                    .show();
        }
    }

    private void rebootArmed() {
        worker.execute(() -> {
            State s = readState();
            if (!s.moduleValid || !"ARMED".equals(s.moduleState) || !s.stockNetwork) {
                showFailure("Reboot bloqueado", "O módulo não está armado em um estado stock validado.");
                return;
            }
            NexmonOneShotController.reboot();
        });
    }

    private void rebootStock() {
        worker.execute(() -> {
            NexmonOneShotController.disarmNextBoot();
            NexmonOneShotController.reboot();
        });
    }

    private void disarm() {
        if (busy) return;
        setBusy("Desarmando módulo Nexmon…");
        worker.execute(() -> {
            RootReader.Result r = NexmonOneShotController.disarmNextBoot();
            if (r.code != 0 && !"ABSENT".equals(NexmonOneShotController.moduleState())) {
                showFailure("Falha ao desarmar", r.output);
                return;
            }
            State s = readState();
            ui.post(() -> applyState(s, buildPreflightReport(s) + "\nDISARM_REQUEST=YES\n"));
        });
    }

    private void collectEvidence() {
        if (busy) return;
        setBusy("Coletando evidências Nexmon…");
        worker.execute(() -> {
            try {
                String report = NexmonOneShotController.collectEvidence();
                createReportZip("BCM4375-Lab-S21-nexmon-one-shot-result.zip", "bcm4375-nexmon-one-shot-result.txt", report);
                State s = readState();
                ui.post(() -> {
                    applyState(s, report + "\n\nZIP pronto. Salve e envie aqui.");
                    saveButton.setEnabled(true);
                });
            } catch (Exception e) {
                showFailure("Falha coletando resultado", e.getMessage());
            }
        });
    }

    private void createReportZip(String zipName, String reportName, String body) throws Exception {
        File work = new File(getCacheDir(), "bcm4375-v3-report");
        ExportUtil.deleteRecursive(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Falha criando diretório de relatório");
        File reportFile = new File(work, reportName);
        try (FileOutputStream out = new FileOutputStream(reportFile)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        List<File> files = new ArrayList<>();
        files.add(reportFile);
        File result = new File(getCacheDir(), zipName);
        if (result.exists()) result.delete();
        ExportUtil.zip(files, result);
        zipFile = result;
    }

    private void setBusy(String message) {
        busy = true;
        status.setTextColor(0xFFFFD180);
        status.setText(message);
        preflightButton.setEnabled(false);
        prepareButton.setEnabled(false);
        armButton.setEnabled(false);
        rebootButton.setEnabled(false);
        disarmButton.setEnabled(false);
        collectButton.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void postStatus(String value) {
        ui.post(() -> status.setText(value));
    }

    private void showFailure(String title, String details) {
        ui.post(() -> {
            busy = false;
            status.setTextColor(0xFFEF9A9A);
            status.setText(title);
            output.setText(details == null ? "" : details);
            preflightButton.setEnabled(true);
            disarmButton.setEnabled(true);
        });
    }

    private String check(String label, boolean ok) {
        return (ok ? "[OK]   " : "[FAIL] ") + label + "\n";
    }

    private void saveZip() {
        if (zipFile == null || !zipFile.isFile()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
        startActivityForResult(intent, SAVE_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_ZIP || resultCode != RESULT_OK || data == null || zipFile == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Destino indisponível");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                ui.post(() -> Toast.makeText(this, "ZIP salvo.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Falha ao salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class State {
        final boolean root;
        final boolean model;
        final boolean hw;
        final boolean magisk;
        final boolean nexmonActive;
        final boolean stockNetwork;
        final String currentSha;
        final String moduleState;
        final String moduleSha;
        final String wifiver;
        final boolean moduleValid;

        State(boolean root, boolean model, boolean hw, boolean magisk, boolean nexmonActive,
              boolean stockNetwork, String currentSha, String moduleState, String moduleSha,
              String wifiver, boolean moduleValid) {
            this.root = root;
            this.model = model;
            this.hw = hw;
            this.magisk = magisk;
            this.nexmonActive = nexmonActive;
            this.stockNetwork = stockNetwork;
            this.currentSha = currentSha;
            this.moduleState = moduleState;
            this.moduleSha = moduleSha;
            this.wifiver = wifiver;
            this.moduleValid = moduleValid;
        }
    }
}
