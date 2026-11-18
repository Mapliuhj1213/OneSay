package com.onesay.assistant.v2.entry;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.onesay.assistant.v2.wake.MicListenSettings;

/**
 * 快捷开关「唤醒词」：点一下临时关掉唤醒词监听（麦克风立即停录，指示灯熄灭），再点恢复。
 * 不改设置里的监听模式；关着时仍能用「OneSay」快捷开关 / 按键 / 手势叫出来。
 */
public class MicListenTileService extends TileService {

    @Override public void onStartListening() {
        refresh();
    }

    @Override public void onClick() {
        boolean off = MicListenSettings.quickOff(this);
        MicListenSettings.setQuickOff(this, !off);   // WakeService 监听 prefs，立即重判
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean off = MicListenSettings.quickOff(this);
        t.setState(off ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.setLabel("唤醒词");
        try {
            // compileSdk 28 没有 setSubtitle（API 29），反射调用
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                Tile.class.getMethod("setSubtitle", CharSequence.class).invoke(t, off ? "已暂停" : "按设置监听");
            }
        } catch (Throwable ignore) {}
        t.updateTile();
    }
}
