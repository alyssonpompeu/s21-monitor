package com.alysson.hdrboost;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class HdrTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Autorize HDR Boost a modificar configurações do sistema.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 20, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
            return;
        }

        boolean enabled = HdrController.toggle(this);
        Toast.makeText(this, enabled ? "HDR Boost ativado" : "HDR Boost desativado", Toast.LENGTH_SHORT).show();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = HdrController.isEnabled(this);
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(enabled ? "HDR Boost ON" : "HDR Boost");
        tile.updateTile();
    }

    static void requestTileRefresh(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, HdrTileService.class));
        } catch (Exception ignored) {
        }
    }
}
