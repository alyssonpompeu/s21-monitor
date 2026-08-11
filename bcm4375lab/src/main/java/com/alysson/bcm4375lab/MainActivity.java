package com.alysson.bcm4375lab;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.rgb(7, 10, 13));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("BCM4375 Lab");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setText("Pronto. Esta versão não altera o Wi-Fi.");
        status.setTextColor(0xFFFFD180);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        Button analyze = new Button(this);
        analyze.setText("ANALISAR BCM4375");
        analyze.setOnClickListener(this::runAnalysis);
        root.addView(analyze);

        output = new TextView(this);
        output.setText("A análise root será executada por uma lista fechada de verificações.");
        output.setTextColor(0xFFE0E0E0);
        output.setTextIsSelectable(true);
        output.setPadding(0, 24, 0, 24);
        root.addView(output);

        setContentView(scroll);
    }

    private void runAnalysis(View ignored) {
        status.setText("Módulo de análise em preparação.");
    }
}
