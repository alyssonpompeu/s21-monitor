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

        HdrController.ToggleResult result = HdrController.toggle(this);
        HdrTileService.requestTileRefresh(this);

        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();

        // Root is the preferred path. WRITE_SETTINGS remains only as a non-root fallback.
        if (!result.enabled && !Settings.System.canWrite(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        finish();
    }
}
