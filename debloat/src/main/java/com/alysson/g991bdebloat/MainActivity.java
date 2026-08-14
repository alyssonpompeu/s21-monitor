package com.alysson.g991bdebloat;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "debloat_state";
    private static final String PREF_DISABLED = "disabled_by_app";
    private static final String PREF_UNINSTALLED = "uninstalled_by_app";

    // Deliberately conservative: obvious optional packages only. Missing packages are skipped.
    private static final String[] SAFE_PACKAGES = {
            "com.samsung.android.app.spage",                 // Samsung Free
            "com.samsung.android.app.tips",                  // Tips
            "com.samsung.android.kidsinstaller",             // Kids installer
            "com.sec.android.app.kidshome",                  // Samsung Kids
            "com.samsung.android.arzone",                    // AR Zone
            "com.samsung.android.aremoji",                   // AR Emoji
            "com.samsung.android.aremojieditor",             // AR Emoji editor
            "com.samsung.android.ardrawing",                 // AR Doodle
            "com.sec.android.autodoodle.service",            // Auto Doodle
            "com.samsung.android.livestickers",              // Live stickers
            "com.sec.android.mimage.avatarstickers",         // Avatar stickers
            "com.samsung.android.bixby.agent",               // Bixby
            "com.samsung.android.bixby.wakeup",              // Bixby wakeup
            "com.samsung.android.app.settings.bixby",        // Bixby settings
            "com.samsung.systemui.bixby2",                   // Bixby SystemUI bridge
            "com.samsung.android.bixbyvision.framework",     // Bixby Vision
            "com.samsung.android.visionintelligence",        // Vision Intelligence
            "com.samsung.android.game.gamehome",             // Gaming Hub / Game Launcher
            "com.samsung.android.game.gametools",            // Game tools overlay
            "com.samsung.android.app.watchmanagerstub",      // Galaxy wearable stub
            "com.samsung.android.oneconnect",                // SmartThings
            "com.samsung.android.service.peoplestripe",      // People Edge
            "com.facebook.appmanager",                       // Meta stub
            "com.facebook.services",                         // Meta services
            "com.facebook.system",                           // Meta system stub
            "com.microsoft.skydrive",                        // OneDrive preload
            "com.netflix.partner.activation"                 // Netflix activation preload
    };

    // User-space Knox components. This cannot and does not change the Knox hardware Warranty Bit.
    // networkfilter is intentionally NOT present because community reports associate its removal
    // with connectivity regressions on some One UI releases.
    private static final String[] KNOX_PACKAGES = {
            "com.samsung.android.knox.analytics.uploader",
            "com.samsung.klmsagent",
            "com.sec.enterprise.knox.cloudmdm.smdms",
            "com.samsung.android.knox.pushmanager",
            "com.samsung.knox.securefolder",
            "com.samsung.android.knox.containercore",
            "com.samsung.android.knox.containeragent",
            "com.samsung.knox.keychain",
            "com.knox.vpn.proxyhandler",
            "com.sec.enterprise.knox.attestation",
            "com.samsung.android.knox.attestation",
            "com.samsung.android.bbc.bbcagent"
    };

    // Hard blocklist: the app refuses to operate on these even if they are accidentally added later.
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "com.android.vending",
            "com.google.android.gms",
            "com.samsung.android.app.notes",
            "com.sec.android.app.camera",
            "com.sec.android.gallery3d",
            "com.android.systemui",
            "com.android.settings",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
            "com.android.phone",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.bluetooth",
            "com.sec.imsservice",
            "com.samsung.android.provider.filterprovider",
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.knox.app.networkfilter"
    ));

    private TextView status;
    private TextView rootBadge;
    private volatile boolean rootOk = false;
    private final ArrayList<Button> actionButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        append("Inicializando em " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + ".");
        if (!"SM-G991B".equalsIgnoreCase(Build.MODEL)) {
            append("ATENÇÃO: este APK foi projetado especificamente para SM-G991B. Operações destrutivas serão bloqueadas neste aparelho.");
        }
        checkRoot();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("G991B Debloat Root");
        title.setTextSize(26f);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Debloat reversível para Galaxy S21 Exynos. Preserva WhatsApp, Play Store/Play Services, Samsung Notes, Câmera, Galeria, telefone, launcher, IMS, Bluetooth e componentes críticos.");
        sub.setTextSize(14f);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        rootBadge = new TextView(this);
        rootBadge.setText("ROOT: verificando…");
        rootBadge.setTextSize(16f);
        rootBadge.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(rootBadge);

        addAction(root, "1. Analisar RAM e pacotes", v -> analyze());
        addAction(root, "2. Congelar bloat seguro (recomendado)", v -> confirmSafe(false));
        addAction(root, "3. Remover bloat seguro do usuário 0", v -> confirmSafe(true));
        addAction(root, "4. Desativar Knox user-space", v -> confirmKnox(false));
        addAction(root, "5. Remover Knox user-space do usuário 0", v -> confirmKnox(true));
        addAction(root, "6. Restaurar alterações feitas por este APK", v -> confirmRestore());

        TextView note = new TextView(this);
        note.setText("Knox: o APK só atua em pacotes de software. Ele NÃO apaga nem restaura o Knox Warranty Bit/e-fuse. O pacote Knox Network Filter é protegido e não é removido por este perfil.");
        note.setTextSize(13f);
        note.setPadding(0, dp(12), 0, dp(8));
        root.addView(note);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setMovementMethod(new ScrollingMovementMethod());
        status.setTextIsSelectable(true);
        status.setPadding(dp(10), dp(10), dp(10), dp(24));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        setButtonsEnabled(false);
    }

    private void addAction(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        actionButtons.add(b);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Button b : actionButtons) b.setEnabled(enabled);
    }

    private void checkRoot() {
        new Thread(() -> {
            CommandResult r = runRoot("id");
            rootOk = r.exitCode == 0 && r.output.contains("uid=0");
            runOnUiThread(() -> {
                rootBadge.setText(rootOk ? "ROOT: concedido via su" : "ROOT: não concedido — autorize este APK no Magisk");
                setButtonsEnabled(rootOk);
                append(rootOk ? "Acesso root confirmado." : "Falha ao obter uid=0. Abra o Magisk, autorize o pedido de superusuário e reabra o APK.\nDetalhe: " + r.output);
            });
        }).start();
    }

    private void analyze() {
        if (!guard()) return;
        setBusy(true);
        append("\n=== ANÁLISE ===");
        new Thread(() -> {
            CommandResult mem = runRoot("cat /proc/meminfo | grep -E '^(MemTotal|MemAvailable|Cached|SwapTotal|SwapFree):'");
            CommandResult packs = runRoot("pm list packages --user 0");
            Set<String> installed = parsePackageList(packs.output);
            int safeCount = countPresent(installed, SAFE_PACKAGES);
            int knoxCount = countPresent(installed, KNOX_PACKAGES);
            int disabledByApp = getSavedSet(PREF_DISABLED).size();
            int removedByApp = getSavedSet(PREF_UNINSTALLED).size();
            runOnUiThread(() -> {
                append(mem.output.trim());
                append(String.format(Locale.US,
                        "Candidatos presentes: %d/%d bloat seguro; %d/%d Knox. Alterações registradas: %d congelados, %d removidos.",
                        safeCount, SAFE_PACKAGES.length, knoxCount, KNOX_PACKAGES.length, disabledByApp, removedByApp));
                setBusy(false);
            });
        }).start();
    }

    private void confirmSafe(boolean uninstall) {
        if (!guard()) return;
        String action = uninstall ? "remover para o usuário 0" : "congelar";
        new AlertDialog.Builder(this)
                .setTitle(uninstall ? "Debloat forte" : "Debloat conservador")
                .setMessage("O perfil vai " + action + " apenas pacotes opcionais presentes no aparelho. Câmera, Galeria, Notes, WhatsApp, Play Store, serviços de telefonia/IMS e pacotes protegidos ficam intactos.\n\nTudo que este APK alterar fica registrado para restauração.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (d, w) -> runProfile("BLOAT SEGURO", SAFE_PACKAGES, uninstall))
                .show();
    }

    private void confirmKnox(boolean uninstall) {
        if (!guard()) return;
        String action = uninstall ? "remover do usuário 0" : "desativar";
        new AlertDialog.Builder(this)
                .setTitle("Knox user-space — avançado")
                .setMessage("Este perfil tenta " + action + " componentes Knox de software. Isso desativa recursos como Secure Folder, gerenciamento corporativo/MDM e atestação Knox.\n\nIMPORTANTE: o Knox Warranty Bit é um e-fuse de hardware e NÃO pode ser apagado/restaurado por este APK. O Network Filter é deliberadamente preservado.\n\nContinuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (d, w) -> runProfile("KNOX USER-SPACE", KNOX_PACKAGES, uninstall))
                .show();
    }

    private void confirmRestore() {
        if (!guard()) return;
        new AlertDialog.Builder(this)
                .setTitle("Restaurar")
                .setMessage("Reinstalar para o usuário 0 e/ou reativar somente os pacotes que este APK registrou como alterados?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (d, w) -> restoreAll())
                .show();
    }

    private void runProfile(String label, String[] packages, boolean uninstall) {
        if (!guardModel()) return;
        setBusy(true);
        append("\n=== " + label + " / " + (uninstall ? "REMOVER USER 0" : "CONGELAR") + " ===");
        new Thread(() -> {
            Set<String> installed = parsePackageList(runRoot("pm list packages --user 0").output);
            Set<String> disabledNow = parsePackageList(runRoot("pm list packages -d --user 0").output);
            Set<String> disabledByApp = getSavedSet(PREF_DISABLED);
            Set<String> uninstalledByApp = getSavedSet(PREF_UNINSTALLED);
            int changed = 0;
            int skipped = 0;
            int failed = 0;

            for (String pkg : packages) {
                if (PROTECTED.contains(pkg)) {
                    skipped++;
                    post("PROTEGIDO  " + pkg);
                    continue;
                }
                if (!installed.contains(pkg)) {
                    skipped++;
                    post("AUSENTE     " + pkg);
                    continue;
                }

                if (uninstall) {
                    CommandResult r = runRoot("pm uninstall --user 0 " + pkg);
                    if (r.exitCode == 0 && r.output.contains("Success")) {
                        uninstalledByApp.add(pkg);
                        disabledByApp.remove(pkg);
                        changed++;
                        post("REMOVIDO    " + pkg);
                    } else {
                        failed++;
                        post("FALHOU      " + pkg + " -> " + compact(r.output));
                    }
                } else {
                    if (disabledNow.contains(pkg)) {
                        skipped++;
                        post("JÁ INATIVO  " + pkg);
                        continue;
                    }
                    CommandResult r = runRoot("pm disable-user --user 0 " + pkg);
                    if (r.exitCode == 0 && !r.output.toLowerCase(Locale.ROOT).contains("failure")) {
                        disabledByApp.add(pkg);
                        changed++;
                        post("CONGELADO   " + pkg);
                    } else {
                        failed++;
                        post("FALHOU      " + pkg + " -> " + compact(r.output));
                    }
                }
            }

            saveSet(PREF_DISABLED, disabledByApp);
            saveSet(PREF_UNINSTALLED, uninstalledByApp);
            final int fChanged = changed, fSkipped = skipped, fFailed = failed;
            runOnUiThread(() -> {
                append("Resultado: " + fChanged + " alterados, " + fSkipped + " ignorados, " + fFailed + " falhas. Reinicie o aparelho e compare RAM/bateria após alguns minutos de uso normal.");
                setBusy(false);
            });
        }).start();
    }

    private void restoreAll() {
        if (!guardModel()) return;
        setBusy(true);
        append("\n=== RESTAURAÇÃO ===");
        new Thread(() -> {
            Set<String> disabled = getSavedSet(PREF_DISABLED);
            Set<String> uninstalled = getSavedSet(PREF_UNINSTALLED);
            Set<String> stillDisabled = new HashSet<>();
            Set<String> stillUninstalled = new HashSet<>();
            int restored = 0;
            int failed = 0;

            for (String pkg : uninstalled) {
                CommandResult install = runRoot("cmd package install-existing --user 0 " + pkg);
                CommandResult enable = runRoot("pm enable --user 0 " + pkg);
                if (install.exitCode == 0 && !install.output.toLowerCase(Locale.ROOT).contains("failure")) {
                    restored++;
                    post("REINSTALADO  " + pkg);
                } else {
                    failed++;
                    stillUninstalled.add(pkg);
                    post("FALHOU      " + pkg + " -> " + compact(install.output + " " + enable.output));
                }
            }

            for (String pkg : disabled) {
                CommandResult enable = runRoot("pm enable --user 0 " + pkg);
                if (enable.exitCode == 0 && !enable.output.toLowerCase(Locale.ROOT).contains("failure")) {
                    restored++;
                    post("REATIVADO    " + pkg);
                } else {
                    failed++;
                    stillDisabled.add(pkg);
                    post("FALHOU      " + pkg + " -> " + compact(enable.output));
                }
            }

            saveSet(PREF_DISABLED, stillDisabled);
            saveSet(PREF_UNINSTALLED, stillUninstalled);
            final int fRestored = restored, fFailed = failed;
            runOnUiThread(() -> {
                append("Restauração: " + fRestored + " concluídos, " + fFailed + " falhas pendentes.");
                setBusy(false);
            });
        }).start();
    }

    private boolean guard() {
        if (!rootOk) {
            Toast.makeText(this, "Root não autorizado. Autorize no Magisk.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private boolean guardModel() {
        if (!guard()) return false;
        if (!"SM-G991B".equalsIgnoreCase(Build.MODEL)) {
            new AlertDialog.Builder(this)
                    .setTitle("Modelo não suportado")
                    .setMessage("Operação bloqueada: este build é específico para SM-G991B. Modelo detectado: " + Build.MODEL)
                    .setPositiveButton("OK", null)
                    .show();
            return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            setButtonsEnabled(!busy && rootOk);
            if (busy) rootBadge.setText("ROOT: ativo — executando…");
            else rootBadge.setText(rootOk ? "ROOT: concedido via su" : "ROOT: não concedido");
        });
    }

    private void post(String line) {
        runOnUiThread(() -> append(line));
    }

    private void append(String line) {
        if (status == null) return;
        String old = status.getText().toString();
        if (old.length() > 24000) old = old.substring(old.length() - 16000);
        status.setText(old + (old.isEmpty() ? "" : "\n") + line);
    }

    private Set<String> parsePackageList(String output) {
        Set<String> set = new HashSet<>();
        if (output == null) return set;
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("package:")) set.add(line.substring(8));
        }
        return set;
    }

    private int countPresent(Set<String> installed, String[] list) {
        int n = 0;
        for (String pkg : list) if (installed.contains(pkg)) n++;
        return n;
    }

    private Set<String> getSavedSet(String key) {
        Set<String> original = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(key, new HashSet<>());
        return new HashSet<>(original == null ? new HashSet<>() : original);
    }

    private void saveSet(String key, Set<String> value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(key, new HashSet<>(value)).apply();
    }

    private String compact(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private CommandResult runRoot(String command) {
        StringBuilder out = new StringBuilder();
        int code = -1;
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            code = p.waitFor();
        } catch (Exception e) {
            out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return new CommandResult(code, out.toString());
    }

    private static class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
