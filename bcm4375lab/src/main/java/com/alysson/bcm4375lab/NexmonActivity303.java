package com.alysson.bcm4375lab;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * v3.0.3 compatibility shim.
 *
 * The v3.0.2 UI correctly classified the previous staged module as LEGACY,
 * but the inherited gate only enabled PREPARE for ABSENT/invalid modules.
 * This wrapper keeps the existing tested flow and only re-enables PREPARE
 * when the verified runtime state is STOCK + LEGACY and no operation is busy.
 */
public class NexmonActivity303 extends NexmonActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable legacyGateFix = new Runnable() {
        @Override
        public void run() {
            try {
                Field busyField = NexmonActivity.class.getDeclaredField("busy");
                Field stateField = NexmonActivity.class.getDeclaredField("state");
                Field prepareField = NexmonActivity.class.getDeclaredField("prepare");
                busyField.setAccessible(true);
                stateField.setAccessible(true);
                prepareField.setAccessible(true);

                boolean busy = busyField.getBoolean(NexmonActivity303.this);
                TextView state = (TextView) stateField.get(NexmonActivity303.this);
                Button prepare = (Button) prepareField.get(NexmonActivity303.this);

                if (!busy && state != null && prepare != null) {
                    String value = String.valueOf(state.getText());
                    if (value.contains("fw=STOCK") && value.contains("module=LEGACY")) {
                        prepare.setEnabled(true);
                    }
                }
            } catch (Throwable ignored) {
                // Fail closed: superclass behavior remains unchanged.
            }
            handler.postDelayed(this, 300L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler.post(legacyGateFix);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(legacyGateFix);
        super.onDestroy();
    }
}
