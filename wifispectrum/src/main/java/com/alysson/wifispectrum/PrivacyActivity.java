package com.alysson.wifispectrum;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class PrivacyActivity extends Activity {
    private static final String PREFS = "privacy_gate";
    private static final String ACCEPTED = "accepted_v1";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoOpenCancelled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean accepted = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ACCEPTED, false);
        setContentView(buildUi(accepted));
        if (accepted) {
            handler.postDelayed(() -> {
                if (!autoOpenCancelled && !isFinishing()) openAnalyzer();
            }, 1400L);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private ScrollView buildUi(boolean accepted) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 8, 14));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root);

        TextView title = text("Wi‑Fi Spectrogram Pro", 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = text("Privacidade e uso das permissões", 15, Color.rgb(105, 211, 255), true);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        TextView disclosure = text(
                "Para mostrar redes Wi‑Fi próximas, o Android exige permissões de Wi‑Fi e, em determinadas versões, Localização precisa.\n\n" +
                "O aplicativo usa essas permissões somente para ler SSID/BSSID, frequência, largura de canal e intensidade do sinal (RSSI) fornecidos pelas APIs Wi‑Fi do Android.\n\n" +
                "O app NÃO lê coordenadas GPS para rastrear você, NÃO envia dados de Wi‑Fi para servidores, NÃO possui anúncios, analytics, conta ou login. O processamento do espectrograma ocorre localmente no aparelho.",
                15, Color.rgb(229, 235, 240), false);
        disclosure.setPadding(dp(14), dp(14), dp(14), dp(14));
        disclosure.setBackgroundColor(Color.rgb(25, 35, 43));
        root.addView(disclosure);

        Button policy = button("POLÍTICA DE PRIVACIDADE COMPLETA");
        policy.setOnClickListener(v -> {
            autoOpenCancelled = true;
            showFullPolicy(root);
        });
        root.addView(policy, params(dp(12)));

        Button open = button(accepted ? "ABRIR ANALISADOR" : "CONCORDO E CONTINUAR");
        open.setOnClickListener(v -> {
            if (!accepted) {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                prefs.edit().putBoolean(ACCEPTED, true).apply();
            }
            openAnalyzer();
        });
        root.addView(open, params(dp(8)));

        if (accepted) {
            TextView info = text("Abrindo automaticamente… toque em Política de Privacidade para permanecer nesta tela.", 12, Color.rgb(155, 170, 180), false);
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            info.setPadding(0, dp(8), 0, 0);
            root.addView(info);
        }
        return scroll;
    }

    private void showFullPolicy(LinearLayout root) {
        if (root.findViewWithTag("full_policy") != null) return;
        TextView policy = text(
                "POLÍTICA DE PRIVACIDADE — Wi‑Fi Spectrogram Pro\n\n" +
                "Última atualização: 10 de agosto de 2026.\n\n" +
                "1. Dados acessados\nO app acessa informações de redes Wi‑Fi disponibilizadas pelo Android: SSID, BSSID, frequência, largura de canal e RSSI. Algumas versões do Android exigem Localização precisa para as APIs de varredura Wi‑Fi.\n\n" +
                "2. Finalidade\nOs dados são usados somente no aparelho para gerar o espectro, waterfall, lista de pontos de acesso e medições exibidas ao usuário.\n\n" +
                "3. Localização\nO app não solicita coordenadas GPS, não cria histórico de localização e não usa Wi‑Fi para rastrear deslocamento.\n\n" +
                "4. Coleta e compartilhamento\nNão há conta, login, anúncios ou analytics. SSIDs, BSSIDs, localização e medições não são transmitidos para servidores do desenvolvedor ou terceiros.\n\n" +
                "5. Retenção\nO histórico visual fica apenas na memória durante o uso e pode ser limpo no aplicativo. Não há banco de dados remoto de usuários.\n\n" +
                "6. Segurança\nComo os dados analisados não são enviados a servidores, eles permanecem no dispositivo e ficam sujeitos às proteções do Android.\n\n" +
                "7. Contato\nCanal público do projeto: github.com/alyssonpompeu/s21-monitor/issues",
                13, Color.rgb(218, 226, 232), false);
        policy.setTag("full_policy");
        policy.setPadding(dp(14), dp(14), dp(14), dp(14));
        policy.setBackgroundColor(Color.rgb(18, 26, 33));
        root.addView(policy, params(dp(10)));
    }

    private void openAnalyzer() {
        autoOpenCancelled = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams params(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, top, 0, 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
