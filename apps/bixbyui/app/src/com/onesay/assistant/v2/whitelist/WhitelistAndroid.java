package com.onesay.assistant.v2.whitelist;

import android.app.Notification;
import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.pclink.PcLanClient;
import com.onesay.assistant.v2.pclink.PcLink;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [app-whitelist-1116] 手机端白名单的 Android 接线：状态放 App 私有目录；本机加删后经电脑联动推给电脑（whitelist_sync），
 * 电脑回的整份合并进来；对方带来的新放行条目弹一条通知。纯规则在 {@link WhitelistState} / {@link AppWhitelist}。
 */
public final class WhitelistAndroid {

    private static final String TAG = "AppWhitelist";

    private WhitelistAndroid() {}

    public static WhitelistState state(Context c) {
        final Context app = c.getApplicationContext();
        WhitelistState s = WhitelistState.init(app.getFilesDir(), PcLink.me(app));
        if (s.listener == null) {
            s.listener = new WhitelistState.Listener() {
                @Override public void changed() {
                    push(app);
                }
            };
        }
        return s;
    }

    /** 推给配对的电脑（没配对 / 电脑联动关着就不推，下次电脑常连上来时它会推过来、回本机整份）。 */
    public static void push(final Context c) {
        final Context app = c.getApplicationContext();
        if (!PcLink.paired(app) || !PcLink.SWITCH.on(app)) return;
        final WhitelistState s = state(app);
        if (s.broken()) return;
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("doc", s.json());
        PcLink.run(app, "whitelist_sync", a, 15000, new PcLink.Done() {
            @Override public void done(PcLanClient.Result r) {
                if (r == null || !r.ok || r.data.get("doc") == null) {
                    Log.i(TAG, "[WL] 没同步到电脑：" + (r == null ? "null" : r.fail));
                    return;
                }
                try {
                    tellAdded(app, s.merge(String.valueOf(r.data.get("doc")), "pc"));
                    Log.i(TAG, "[WL] 已和电脑同步");
                } catch (RuntimeException e) {
                    Log.w(TAG, "[WL] 电脑回的白名单合并失败：" + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** 对方带来的新放行条目：通知一下（只写名字 / 包名，不写别的）。 */
    public static void tellAdded(Context c, List<AppWhitelist.Entry> added) {
        if (added == null || added.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        for (AppWhitelist.Entry e : added) b.append(b.length() == 0 ? "" : "、").append(e.name.length() == 0 ? e.id : e.name);
        String t = "电脑加了 " + added.size() + " 个白名单应用：" + b + "。要在手机上确认一次才生效（控制台 › 权限与审批 › 应用白名单 › 确认生效，也能删）";
        Log.i(TAG, "[WL] 同步来新条目 " + added.size() + " 个");
        try {
            Notification n = com.onesay.assistant.v2.pclink.PcRxAndroid.notice(c, "应用白名单", t);
            if (n != null) ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(9416, n);
        } catch (Throwable ignored) {
        }
    }
}
