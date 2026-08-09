package com.alysson.flashlightshortcut;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class MainActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CameraManager cameraManager;
    private String torchCameraId;
    private Boolean torchEnabled;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) {
                torchEnabled = enabled;
            }
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (cameraId.equals(torchCameraId)) {
                torchEnabled = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        torchCameraId = findBestTorchCamera();

        if (torchCameraId == null) {
            Toast.makeText(this, "Este aparelho não possui uma lanterna compatível.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        cameraManager.registerTorchCallback(torchCallback, mainHandler);
        scheduleToggle(180);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        scheduleToggle(80);
    }

    private void scheduleToggle(long delayMs) {
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(this::toggleTorch, delayMs);
    }

    private void toggleTorch() {
        if (torchCameraId == null) {
            return;
        }

        boolean enable = !Boolean.TRUE.equals(torchEnabled);

        try {
            cameraManager.setTorchMode(torchCameraId, enable);
            torchEnabled = enable;
            Toast.makeText(this, enable ? "Lanterna ligada" : "Lanterna desligada", Toast.LENGTH_SHORT).show();

            // Mantém a Activity/processo vivos em segundo plano. O Android desliga
            // automaticamente a lanterna se este processo for encerrado.
            mainHandler.postDelayed(() -> moveTaskToBack(true), 100);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Toast.makeText(this, "Não foi possível alterar a lanterna.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
    }

    private String findBestTorchCamera() {
        String fallback = null;

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                if (!Boolean.TRUE.equals(flashAvailable)) {
                    continue;
                }

                if (fallback == null) {
                    fallback = cameraId;
                }

                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException | SecurityException ignored) {
            return null;
        }

        return fallback;
    }

    @Override
    protected void onDestroy() {
        if (cameraManager != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }
        super.onDestroy();
    }
}
