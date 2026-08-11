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

public class NexmonActivity extends Activity {
    private static final int SAVE_ZIP = 4376;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView state;
    private TextView output;
    private Button preflight;
    private Button prepare;
    private Button arm;
    private Button reboot;
    private Button collect;
    private Button stock;
    private Button save;
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
        root.addView(text("v3.0 • Nexmon one-shot • BCM4375B1 18.41.117", 12, 0xFF80CBC4, false));
        root.addView(text("Samsung " + Build.MODEL + " • " + Build.HARDWARE + " • Android " + Build.VERSION.RELEASE,
                12, 0xFFCFD8DC, false));

        status = text("Execute o preflight. A v3 não grava fisicamente em /vendor.", 14, 0xFFFFD180, true);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        state = text("Estado não verificado.", 12, 0xFFB0BEC5, false);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, 0, 0, dp(12));
        root.addView(state);

        preflight = button("1. NEXMON PREFLIGHT");
        preflight.setOnClickListener(v -> runPreflight());
        root.addView(preflight);

        prepare = button("2. BAIXAR + PREPARAR MÓDULO DESATIVADO");
        prepare.setEnabled(false);
        prepare.setOnClickListener(v -> confirmPrepare());
        root.addView(prepare);

        arm = button("3. ARMAR NEXMON PARA O PRÓXIMO BOOT");
        arm.setEnabled(false);
        arm.setOnClickListener(v -> confirmArm());
        root.addView(arm);

        reboot = button("4. REINICIAR PARA TESTAR NEXMON");
        reboot.setEnabled(false);
        reboot.setOnClickListener(v -> confirmNexmonReboot());
        root.addView(reboot);

        collect = button("5. COLETAR RESULTADO DO BOOT");
        collect.setEnabled(false);
        collect.setOnClickListener(v -> collectEvidence());
        root.addView(collect);

        stock = button("6. DESARMAR + REINICIAR PARA STOCK");
        stock.setEnabled(false);
        stock.setOnClickListener(v -> confirmStockReboot());
        root.addView(stock);

        save = button("SALVAR ÚLTIMO ZIP");
        save.setEnabled(false);
        save.setOnClickListener(v -> saveZip());
        root.addView(save);

        TextView note = text(
                "O firmware patchado é aceito somente se o SHA-256 for exatamente " + NexmonOneShotController.NEXMON_SHA + ". " +
                "O módulo é instalado primeiro como DESATIVADO. Ao armar, apenas o próximo boot recebe o overlay. " +
                "No boot Nexmon, o service.sh cria disable para que o boot seguinte volte ao firmware Samsung. " +
                "SELinux permanece Enforcing e esta versão não executa nexutil nem injection.",
                12, 0xFFB0BEC5, false);
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        output = text("Nenhuma operação executada.", 11, 0xFFE0E0E0, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        root.addView(output);
        return scroll;
    }

    private void runPreflight() {
        if (busy) return;
        setBusy("Solicitando root e verificando estado…");
        worker.execute(() -> {
            try {
                Snapshot s = snapshot();
                String report = report(s);
                createZip("BCM4375-Lab-v3-preflight.zip", "preflight.txt", report);
                ui.post(() -> applySnapshot(s, report));
            } catch (Exception e) {
                fail("PREFLIGHT FALHOU", e);
            }
        });
    }

    private Snapshot snapshot() throws Exception {
        RootReader.Result rootResult = RootReader.run("id", 20);
        boolean root = !rootResult.timedOut && rootResult.code == 0 && rootResult.output.contains("uid=0");
        String wifiver = NexmonOneShotController.wifiver();
        String currentSha = NexmonOneShotController.currentFirmwareSha();
        String moduleState = NexmonOneShotController.moduleState();
        String location = NexmonOneShotController.moduleLocation();
        String moduleSha = NexmonOneShotController.moduleFirmwareSha();
        boolean nexmon = NexmonOneShotController.isNexmonActive();
        boolean result = NexmonOneShotController.resultExists();
        boolean stockFw = wifiver.contains("18.41.117") && wifiver.contains("B1 Network") &&
                NexmonOneShotController.STOCK_SHA.equalsIgnoreCase(currentSha);
        boolean moduleValid = NexmonOneShotController.NEXMON_SHA.equalsIgnoreCase(moduleSha);
        return new Snapshot(root,
                "SM-G991B".equalsIgnoreCase(Build.MODEL),
                "exynos2100".equalsIgnoreCase(Build.HARDWARE),
                NexmonOneShotController.magiskReady(), stockFw, nexmon, result,
                currentSha, moduleState, location, moduleSha, moduleValid, wifiver);
    }

    private String report(Snapshot s) {
        StringBuilder r = new StringBuilder();
        r.append("BCM4375 Lab v3.0.0\n\n");
        r.append(check("root", s.root));
        r.append(check("SM-G991B", s.model));
        r.append(check("Exynos 2100", s.hardware));
        r.append(check("Magisk module backend", s.magisk));
        r.append("stock_firmware=").append(s.stockFirmware).append('\n');
        r.append("nexmon_active=").append(s.nexmonActive).append('\n');
        r.append("result_exists=").append(s.resultExists).append('\n');
        r.append("module_state=").append(s.moduleState).append('\n');
        r.append("module_location=").append(s.moduleLocation).append('\n');
        r.append("module_valid=").append(s.moduleValid).append('\n');
        r.append("current_sha=").append(s.currentSha).append('\n');
        r.append("module_sha=").append(s.moduleSha).append('\n');
        r.append("expected_stock_sha=").append(NexmonOneShotController.STOCK_SHA).append('\n');
        r.append("expected_nexmon_sha=").append(NexmonOneShotController.NEXMON_SHA).append('\n');
        r.append("\n=== WIFIVER ===\n").append(s.wifiver);
        r.append("\n=== MAGISK ===\n").append(NexmonOneShotController.magiskInfo());
        r.append("\n=== SELINUX ===\n").append(RootReader.run("getenforce 2>&1", 3).output);
        return r.toString();
    }

    private void applySnapshot(Snapshot s, String report) {
        busy = false;
        boolean base = s.root && s.model && s.hardware && s.magisk;
        boolean canPrepare = base && s.stockFirmware && !s.nexmonActive;
        boolean disabled = s.moduleValid && "DISABLED".equals(s.moduleState);
        boolean armedNow = s.moduleValid && "ARMED".equals(s.moduleState) && s.stockFirmware && !s.nexmonActive;

        preflight.setEnabled(true);
        prepare.setEnabled(canPrepare && (!s.moduleValid || "ABSENT".equals(s.moduleState)));
        arm.setEnabled(base && s.stockFirmware && disabled && !s.nexmonActive);
        reboot.setEnabled(armedNow);
        collect.setEnabled(base && (s.nexmonActive || s.resultExists));
        stock.setEnabled(base && (s.nexmonActive || s.moduleValid || "ARMED".equals(s.moduleState)));
        save.setEnabled(zipFile != null && zipFile.isFile());

        if (s.nexmonActive) {
            status.setTextColor(disabled ? 0xFF81C784 : 0xFFEF9A9A);
            status.setText(disabled ? "NEXMON ATIVO • próximo boot já está DESATIVADO para stock" : "NEXMON ATIVO • use DESARMAR antes de reiniciar");
        } else if (armedNow) {
            status.setTextColor(0xFFFFD180);
            status.setText("ARMADO • o próximo boot tentará Nexmon uma vez");
        } else if (disabled) {
            status.setTextColor(0xFF81C784);
            status.setText("MÓDULO PREPARADO E DESATIVADO • seguro para permanecer assim");
        } else if (canPrepare) {
            status.setTextColor(0xFF81C784);
            status.setText("PREFLIGHT OK • pronto para preparar o módulo");
        } else {
            status.setTextColor(0xFFFFD180);
            status.setText("ESTADO VERIFICADO • não prossiga enquanto houver requisito inválido");
        }

        state.setText("fw=" + (s.nexmonActive ? "NEXMON" : (s.stockFirmware ? "STOCK" : "OUTRO")) +
                "\nmodule=" + s.moduleState + " @ " + s.moduleLocation +
                "\ncurrent_sha=" + s.currentSha +
                "\nmodule_sha=" + s.moduleSha);
        output.setText(report);
    }

    private void confirmPrepare() {
        new AlertDialog.Builder(this)
                .setTitle("Preparar módulo Nexmon")
                .setMessage("O app baixará o firmware da release verificada, validará o SHA-256 e instalará o módulo Magisk em estado DESATIVADO. Nenhuma mudança ocorrerá no boot atual.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Preparar", (d, w) -> prepareModule())
                .show();
    }

    private void prepareModule() {
        if (busy) return;
        setBusy("Validando estado antes do download…");
        worker.execute(() -> {
            try {
                Snapshot before = snapshot();
                if (!(before.root && before.model && before.hardware && before.magisk && before.stockFirmware && !before.nexmonActive))
                    throw new Exception("O estado não é mais stock/seguro. Execute o preflight novamente.");

                File fw = NexmonOneShotController.downloadVerifiedFirmware(this, this::postStatus);
                postStatus("Criando módulo Magisk…");
                File module = NexmonOneShotController.buildModuleZip(this, fw);
                postStatus("Instalando em modules_update e marcando DISABLED…");
                RootReader.Result install = NexmonOneShotController.installDisabledModule(module);
                if (install.timedOut || install.code != 0)
                    throw new Exception("magisk --install-module falhou: " + install.output);

                Snapshot after = snapshot();
                if (!after.moduleValid || !"DISABLED".equals(after.moduleState))
                    throw new Exception("O módulo não terminou em estado DISABLED validado.");
                String r = report(after) + "\nmodule_zip_sha256=" + NexmonOneShotController.sha256(module) + "\nPREPARED=YES\n";
                createZip("BCM4375-Lab-v3-module-prepared.zip", "module-prepared.txt", r);
                ui.post(() -> applySnapshot(after, r));
            } catch (Exception e) {
                NexmonOneShotController.disarmNextBoot();
                fail("PREPARAÇÃO FALHOU", e);
            }
        });
    }

    private void confirmArm() {
        new AlertDialog.Builder(this)
                .setTitle("Armar Nexmon one-shot")
                .setMessage("Isto remove o marcador disable. O próximo reboot aplicará o firmware Nexmon. No boot Nexmon, o serviço do módulo recriará disable para que o reboot seguinte volte ao stock.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Armar", (d, w) -> armModule())
                .show();
    }

    private void armModule() {
        if (busy) return;
        setBusy("Armando próximo boot…");
        worker.execute(() -> {
            try {
                Snapshot before = snapshot();
                if (!before.stockFirmware || !before.moduleValid || !"DISABLED".equals(before.moduleState))
                    throw new Exception("Módulo não está DISABLED sobre um boot stock validado.");
                RootReader.Result r = NexmonOneShotController.armNextBoot();
                if (r.timedOut || r.code != 0) throw new Exception("Não foi possível armar: " + r.output);
                Snapshot after = snapshot();
                if (!"ARMED".equals(after.moduleState)) throw new Exception("Estado ARMED não confirmado.");
                ui.post(() -> applySnapshot(after, report(after) + "\nARMED=YES\n"));
            } catch (Exception e) {
                NexmonOneShotController.disarmNextBoot();
                fail("ARMAR FALHOU", e);
            }
        });
    }

    private void confirmNexmonReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar para Nexmon")
                .setMessage("O telefone reiniciará agora. Quando o Android voltar, abra BCM4375 Lab e toque em COLETAR RESULTADO. Não arme novamente antes de analisar o primeiro boot.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Reiniciar", (d, w) -> worker.execute(() -> {
                    try {
                        Snapshot s = snapshot();
                        if (!s.stockFirmware || !s.moduleValid || !"ARMED".equals(s.moduleState)) {
                            NexmonOneShotController.disarmNextBoot();
                            throw new Exception("Reboot bloqueado: estado ARMED/stock não confirmado.");
                        }
                        NexmonOneShotController.reboot();
                    } catch (Exception e) {
                        fail("REBOOT BLOQUEADO", e);
                    }
                }))
                .show();
    }

    private void collectEvidence() {
        if (busy) return;
        setBusy("Coletando evidências do boot…");
        worker.execute(() -> {
            try {
                String r = NexmonOneShotController.collectEvidence();
                createZip("BCM4375-Lab-S21-nexmon-one-shot-result.zip", "nexmon-one-shot-result.txt", r);
                Snapshot s = snapshot();
                ui.post(() -> {
                    applySnapshot(s, r + "\n\nZIP pronto. Salve e envie aqui.");
                    save.setEnabled(true);
                });
            } catch (Exception e) {
                fail("COLETA FALHOU", e);
            }
        });
    }

    private void confirmStockReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Garantir próximo boot stock")
                .setMessage("O app criará disable em qualquer cópia ativa/staged do módulo e reiniciará. O firmware original em /vendor não é alterado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desarmar e reiniciar", (d, w) -> worker.execute(() -> {
                    NexmonOneShotController.disarmNextBoot();
                    NexmonOneShotController.reboot();
                }))
                .show();
    }

    private void createZip(String zipName, String reportName, String body) throws Exception {
        File dir = new File(getCacheDir(), "v3-report");
        ExportUtil.deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Falha criando cache");
        File report = new File(dir, reportName);
        try (FileOutputStream fos = new FileOutputStream(report)) {
            fos.write(body.getBytes(StandardCharsets.UTF_8));
        }
        List<File> files = new ArrayList<>();
        files.add(report);
        File zip = new File(getCacheDir(), zipName);
        if (zip.exists()) zip.delete();
        ExportUtil.zip(files, zip);
        zipFile = zip;
    }

    private void setBusy(String message) {
        busy = true;
        status.setTextColor(0xFFFFD180);
        status.setText(message);
        preflight.setEnabled(false);
        prepare.setEnabled(false);
        arm.setEnabled(false);
        reboot.setEnabled(false);
        collect.setEnabled(false);
        stock.setEnabled(false);
        save.setEnabled(false);
    }

    private void postStatus(String message) {
        ui.post(() -> status.setText(message));
    }

    private void fail(String title, Exception e) {
        ui.post(() -> {
            busy = false;
            status.setTextColor(0xFFEF9A9A);
            status.setText(title);
            output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            preflight.setEnabled(true);
            stock.setEnabled(true);
        });
    }

    private String check(String label, boolean ok) {
        return (ok ? "[OK]   " : "[FAIL] ") + label + "\n";
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

    private static final class Snapshot {
        final boolean root, model, hardware, magisk, stockFirmware, nexmonActive, resultExists;
        final String currentSha, moduleState, moduleLocation, moduleSha, wifiver;
        final boolean moduleValid;

        Snapshot(boolean root, boolean model, boolean hardware, boolean magisk, boolean stockFirmware,
                 boolean nexmonActive, boolean resultExists, String currentSha, String moduleState,
                 String moduleLocation, String moduleSha, boolean moduleValid, String wifiver) {
            this.root = root;
            this.model = model;
            this.hardware = hardware;
            this.magisk = magisk;
            this.stockFirmware = stockFirmware;
            this.nexmonActive = nexmonActive;
            this.resultExists = resultExists;
            this.currentSha = currentSha;
            this.moduleState = moduleState;
            this.moduleLocation = moduleLocation;
            this.moduleSha = moduleSha;
            this.moduleValid = moduleValid;
            this.wifiver = wifiver;
        }
    }
}
