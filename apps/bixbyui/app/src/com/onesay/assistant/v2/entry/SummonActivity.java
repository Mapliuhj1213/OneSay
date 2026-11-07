package com.onesay.assistant.v2.entry;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 公开唤起入口 {@code com.onesay.assistant.action.SUMMON}。三道闸：
 * <ol>
 *   <li>组件默认停用（{@code android:enabled="false"}），用户在设置里打开「外部 App 唤起」才启用；</li>
 *   <li>调用方必须持有危险级权限 {@code com.onesay.assistant.permission.SUMMON}（系统会弹框让用户逐个 App 授权）；</li>
 *   <li>{@link Entries#fire} 再核对一次开关。</li>
 * </ol>
 * 可选 extra {@code com.onesay.assistant.extra.MODE}：{@code voice}（默认）/ {@code text}。
 */
public class SummonActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String mode = getIntent() == null ? null : getIntent().getStringExtra(BuiltinEntries.EXTRA_MODE);
        if (!Entries.fire(this, BuiltinEntries.INTENT, BuiltinEntries.parseMode(mode))) {
            Toast.makeText(this, "OneSay：外部唤起未开启（设置 › 设备能力与功能 › 唤起入口）", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
