package com.onesay.assistant.v2.entry;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 快捷开关：点一下 → 收起通知栏并开始聆听（锁屏时先解锁）。 */
public class SummonTileService extends TileService {

    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Entries.canFire(this, BuiltinEntries.QS_TILE) ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.updateTile();
    }

    @Override public void onClick() {
        final Intent i = new Intent(this, EntryLaunchActivity.class)
                .putExtra(EntryLaunchActivity.EXTRA_ENTRY, BuiltinEntries.QS_TILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (isLocked()) {
            unlockAndRun(new Runnable() { @Override public void run() { startActivityAndCollapse(i); } });
        } else {
            startActivityAndCollapse(i);
        }
    }
}
