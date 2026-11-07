package com.onesay.plan.platform;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.util.Log;

import com.onesay.plan.core.Shortcuts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 快捷指令的桌面固定图标（删快捷指令不撤桌面图标）。按 API 能力探测：
 * <ul>
 *   <li>Android 7.1（API 25）起才有 ShortcutManager；更老的系统什么都不做；</li>
 *   <li>Android 不允许 App 直接把用户固定的图标从桌面拿掉，能做的是 {@link ShortcutManager#disableShortcuts(List, CharSequence)}：
 *       桌面把它置灰（One UI / 原生桌面），点了显示 {@link Shortcuts#PIN_DISABLED}；用户长按即可移除；</li>
 *   <li>只处理确实固定在桌面上的那几个 id（getPinnedShortcuts 里有的），异常一律吞掉、只记日志，不影响删除本身。</li>
 * </ul>
 */
public final class PinnedShortcuts {
    private PinnedShortcuts() {}

    static final String TAG = "BixbyPlan";

    static ShortcutManager sm(Context c) {
        if (Build.VERSION.SDK_INT < 25) return null;
        try {
            return (ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删掉的快捷指令 → 禁用它们在桌面上的固定图标。返回禁用了几个。 */
    public static int disable(Context c, List<Long> ids) {
        ShortcutManager m = sm(c);
        if (m == null || ids == null || ids.isEmpty()) return 0;
        try {
            Set<String> pinned = new HashSet<String>();
            for (ShortcutInfo i : m.getPinnedShortcuts()) pinned.add(i.getId());
            List<String> hit = new ArrayList<String>();
            for (long id : ids) if (pinned.contains(Shortcuts.pinId(id))) hit.add(Shortcuts.pinId(id));
            if (hit.isEmpty()) return 0;
            m.disableShortcuts(hit, Shortcuts.PIN_DISABLED);
            Log.i(TAG, "[SHORTCUT] 已禁用桌面图标 " + hit);
            return hit.size();
        } catch (Throwable t) {
            Log.w(TAG, "[SHORTCUT] 禁用桌面图标失败: " + t);
            return 0;
        }
    }

    /** 再次固定前：同 id 以前被禁用过（快捷指令删了又新建、id 被复用）→ 先启用，否则 requestPinShortcut 会拒绝。 */
    public static void enableIfDisabled(Context c, long id) {
        ShortcutManager m = sm(c);
        if (m == null) return;
        try {
            List<String> one = new ArrayList<String>();
            one.add(Shortcuts.pinId(id));
            m.enableShortcuts(one);
        } catch (Throwable ignored) {
        }
    }
}
