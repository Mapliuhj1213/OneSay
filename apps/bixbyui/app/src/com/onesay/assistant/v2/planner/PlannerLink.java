package com.onesay.assistant.v2.planner;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * [planner] 计划 App 适配层的总闸 + 可用性判断（实现 {@link PlannerGate}）。
 * <ul>
 *   <li>开关：prefs bixby_planner / enabled（默认开）+ kill-file planner.off；关 = 一切回到 OneSay 本地存储；</li>
 *   <li>可用：装了 com.onesay.plan 且 ping 通（结构版本 ≥ {@link PlannerContract#SCHEMA_VERSION}）。成功缓存 60 s；失败冷却 30 s 后再试；</li>
 *   <li>没装 / 被拒 / 版本太旧 → 回落本地（功能不丢），日志 [PLANNER]。</li>
 * </ul>
 */
public final class PlannerLink implements PlannerGate {

    static final String TAG = "BixbyPlanner";
    public static final FeatureSwitch SWITCH = new FeatureSwitch("bixby_planner", "enabled", true, "planner.off");
    static final long OK_TTL_MS = 60000L, FAIL_COOL_MS = 30000L;

    private static volatile PlannerLink sInst;

    public static PlannerLink get(Context c) {
        if (sInst == null) {
            synchronized (PlannerLink.class) {
                if (sInst == null) sInst = new PlannerLink(c.getApplicationContext());
            }
        }
        return sInst;
    }

    final Context app;
    final AndroidPlannerBackend backend;
    private volatile long okUntil, coolUntil;
    private volatile String lastWhy = "还没检查";
    private volatile boolean lastOn;
    /** 最近一次 ping 到的结构版本（快捷指令要 ≥ {@link PlannerContract#SCHEMA_SHORTCUTS}）。 */
    private volatile int lastSchema;

    private PlannerLink(Context app) {
        this.app = app;
        this.backend = new AndroidPlannerBackend(app);
    }

    public PlannerBackend backend() {
        return backend;
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PlannerContract.PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override public boolean on() {
        long now = System.currentTimeMillis();
        if (!SWITCH.on(app)) return flip(false, "开关关着（数据留在 OneSay 本地）");
        if (now < okUntil) return true;
        if (now < coolUntil) return false;
        if (!installed(app)) {
            coolUntil = now + FAIL_COOL_MS;
            return flip(false, "计划 App 没装");
        }
        try {
            Bundle b = backend.ping();
            int schema = b == null ? 0 : b.getInt("schema", 0);
            lastSchema = schema;
            if (schema < PlannerContract.SCHEMA_VERSION) {
                coolUntil = now + FAIL_COOL_MS;
                return flip(false, "计划 App 版本太旧（结构 " + schema + "）");
            }
            okUntil = now + OK_TTL_MS;
            return flip(true, "已连上（" + b.getString("who", "?") + "）");
        } catch (RuntimeException e) {
            coolUntil = now + FAIL_COOL_MS;
            return flip(false, "连不上：" + e.getMessage());
        }
    }

    boolean flip(boolean on, String why) {
        if (on != lastOn || !why.equals(lastWhy)) Log.i(TAG, "[PLANNER] " + (on ? "用计划 App" : "用本地存储") + " · " + why);
        lastOn = on;
        lastWhy = why;
        if (on) PlannerMigration.maybeRun(app);
        return on;
    }

    @Override public void failed(Throwable t) {
        okUntil = 0;
        coolUntil = System.currentTimeMillis() + FAIL_COOL_MS;
        flip(false, "读写出错：" + (t == null ? "" : t.getMessage()));
        PlannerMigration.markLocalDirty(app);
    }

    /** ★ feat/shortcuts：连着计划 App 且它有快捷指令表。 */
    public boolean shortcutsOn() {
        return on() && lastSchema >= PlannerContract.SCHEMA_SHORTCUTS;
    }

    /** 状态一句话（控制台 / 调试）。 */
    public String status() {
        return (lastOn ? "用计划 App" : "用本地存储") + " · " + lastWhy;
    }

    /** 控制台里日程 / 待办 / 定时任务的「管理」：开关开着且计划 App 能打开 → 跳过去（返回 true）；否则 false，调用方照旧开本地管理页。 */
    public static boolean manageInPlanner(Context c, String tab) {
        return SWITCH.on(c) && open(c, tab);
    }

    /** 控制台「打开计划」：装了就跳过去（可带页签 today/events/todos/tickets/pickups/automations），没装返回 false。 */
    public static boolean open(Context c, String tab) {
        try {
            Intent i = new Intent(PlannerContract.ACTION_OPEN).setPackage(PlannerContract.PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tab != null) i.putExtra("tab", tab);
            if (c.getPackageManager().resolveActivity(i, 0) == null) return false;
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
