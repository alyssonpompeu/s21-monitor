package com.alysson.hdrboost;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this,
                    "Autorize 'Modificar configurações do sistema'. Depois toque no HDR Boost novamente.",
                    Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            finish();
            return;
        }

        boolean enabled = HdrController.toggle(this);
        HdrTileService.requestTileRefresh(this);
        Toast.makeText(this,
                enabled ? "HDR Boost ATIVADO — brilho máximo + Vivid" : "HDR Boost DESATIVADO — ajustes restaurados",
                Toast.LENGTH_LONG).show();
        finish();
    }
}
