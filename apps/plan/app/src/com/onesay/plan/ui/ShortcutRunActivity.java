package com.onesay.plan.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.Shortcuts;
import com.onesay.plan.data.Store;

/**
 * 桌面快捷方式的落点（无界面）：发起一次运行（shortcut_runs 插 status=requested，trigger=launcher），由 OneSay 认领执行。
 * 不导出：固定快捷方式由桌面以本 App 身份启动（未上机验证，见 docs/SHORTCUTS.md「未验证」）。
 */
public final class ShortcutRunActivity extends Activity {

    public static final String ACTION = "com.onesay.plan.action.RUN_SHORTCUT";
    public static final String EXTRA_ID = "shortcut_id";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        long id = getIntent() == null ? 0 : getIntent().getLongExtra(EXTRA_ID, 0);
        Row r = id > 0 ? Store.get(this, Contract.T_SHORTCUTS, id) : null;
        if (r == null) {
            // 删掉的快捷指令留下的旧图标——说清楚怎么办，顺手把图标禁用（老版本删的时候没禁用）
            if (id > 0) {
                java.util.List<Long> gone = new java.util.ArrayList<Long>();
                gone.add(id);
                com.onesay.plan.platform.PinnedShortcuts.disable(this, gone);
            }
            Toast.makeText(this, Shortcuts.PIN_GONE, Toast.LENGTH_LONG).show();
        } else if (r.l("enabled", 1) == 0 || !Shortcuts.trigger(r.s("triggers_json"), Contract.TRIG_LAUNCHER)) {
            Toast.makeText(this, "「" + r.s("name") + "」没开桌面触发", Toast.LENGTH_SHORT).show();
        } else {
            try {
                ShortcutUi.request(this, id, Contract.TRIG_LAUNCHER);
                Toast.makeText(this, "已交给 OneSay 执行「" + r.s("name") + "」", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "没发出去：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
