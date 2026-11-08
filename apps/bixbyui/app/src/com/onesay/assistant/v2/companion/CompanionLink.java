package com.onesay.assistant.v2.companion;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [companion] 伴侣发现（Android 层）：PackageManager 查安装 + 签名 + provider 归属 → {@code link.hello} 握手 → {@link CompanionHandshake}。
 * 结果缓存：READY 60 s，其余 30 s。总开关 prefs bixby_companion / enabled（默认开）+ kill-file companion.off；关 = 当作没装（全部回落原流程）。
 * 不用 shell / root。日志 TAG OneSayCompanion，口径 [COMPANION]。
 */
public final class CompanionLink {

    static final String TAG = "OneSayCompanion";
    public static final FeatureSwitch SWITCH = new FeatureSwitch("bixby_companion", "enabled", true, "companion.off");
    static final long OK_TTL_MS = 60000L, FAIL_TTL_MS = 30000L;

    public enum Peer {
        PLAN(CompanionContract.PLAN_PKG, CompanionContract.PLAN_AUTHORITY, CompanionContract.PLAN_REQUIRED),
        CLOCK(CompanionContract.CLOCK_PKG, CompanionContract.CLOCK_AUTHORITY, CompanionContract.CLOCK_REQUIRED);

        public final String pkg, authority;
        final String[] required;

        Peer(String pkg, String authority, String[] required) {
            this.pkg = pkg;
            this.authority = authority;
            this.required = required;
        }
    }

    private static volatile CompanionLink sInst;

    public static CompanionLink get(Context c) {
        if (sInst == null) {
            synchronized (CompanionLink.class) {
                if (sInst == null) sInst = new CompanionLink(c.getApplicationContext() != null ? c.getApplicationContext() : c);
            }
        }
        return sInst;
    }

    final Context app;
    private final Map<Peer, CompanionHandshake.Result> last = new LinkedHashMap<Peer, CompanionHandshake.Result>();
    private final Map<Peer, Long> until = new LinkedHashMap<Peer, Long>();

    private CompanionLink(Context app) {
        this.app = app;
    }

    /** 当前状态（可能触发一次 hello，后台线程调用）。 */
    public CompanionHandshake.Result result(Peer p) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            Long u = until.get(p);
            CompanionHandshake.Result r = last.get(p);
            if (r != null && u != null && now < u) return r;
        }
        CompanionHandshake.Result r = probe(p);
        synchronized (this) {
            CompanionHandshake.Result old = last.get(p);
            if (old == null || old.state != r.state) Log.i(TAG, "[COMPANION] " + p + " " + p.pkg + " → " + r);
            last.put(p, r);
            until.put(p, now + (r.state.ready() ? OK_TTL_MS : FAIL_TTL_MS));
        }
        return r;
    }

    public CompanionHandshake.State state(Peer p) {
        return result(p).state;
    }

    /** 主线程可用：只看缓存，不发起 hello（没有缓存 = 只查安装 / 签名）。 */
    public CompanionHandshake.State cachedState(Peer p) {
        synchronized (this) {
            CompanionHandshake.Result r = last.get(p);
            if (r != null) return r.state;
        }
        return CompanionHandshake.evaluate(installed(p.pkg), sameSigner(p), null, p.required).state;
    }

    public void invalidate() {
        synchronized (this) {
            until.clear();
        }
    }

    CompanionHandshake.Result probe(Peer p) {
        if (!SWITCH.on(app)) return CompanionHandshake.evaluate(false, false, null, p.required);
        boolean inst = installed(p.pkg);
        boolean same = inst && sameSigner(p);
        Map<String, Object> hello = null;
        if (same) {
            try {
                Bundle x = new Bundle();
                x.putInt(CompanionContract.K_CLIENT_VERSION, CompanionContract.VERSION);
                x.putString(CompanionContract.K_CLIENT_PKG, app.getPackageName());
                hello = toMap(call(p, CompanionContract.M_HELLO, null, x));
            } catch (RuntimeException e) {
                Log.i(TAG, "[COMPANION] " + p + " hello 失败: " + e.getMessage());
            }
        }
        CompanionHandshake.Result r = CompanionHandshake.evaluate(inst, same, hello, p.required);
        // 计划 App：用户把「计划 App」开关关了（数据留本地）→ 不经连接改数据，但仍算自家 App 在场（AgentLoop 不探测第三方）
        if (p == Peer.PLAN && r.state.ready() && !com.onesay.assistant.v2.planner.PlannerLink.SWITCH.on(app)) {
            r = new CompanionHandshake.Result(CompanionHandshake.State.NO_LINK, r.peerVersion, r.caps, "计划 App 开关关着（数据留在 OneSay 本地）");
        }
        return r;
    }

    boolean installed(String pkg) {
        try {
            app.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同证书 + 这个 authority 的 provider 确实归这个包（防同名 authority 冒充）。 */
    boolean sameSigner(Peer p) {
        try {
            PackageManager pm = app.getPackageManager();
            if (pm.checkSignatures(app.getPackageName(), p.pkg) != PackageManager.SIGNATURE_MATCH) return false;
            ProviderInfo pi = pm.resolveContentProvider(p.authority, 0);
            return pi == null || p.pkg.equals(pi.packageName);   // 太旧没有 provider：仍算同证书（NO_LINK）
        } catch (Throwable t) {
            return false;
        }
    }

    /** 伴侣 uid（简报来源对账用）；没装 -1。 */
    public int uid(Peer p) {
        try {
            return app.getPackageManager().getApplicationInfo(p.pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 直接 call（调用方已确认 READY 或在握手）。出错抛 RuntimeException。 */
    public Bundle call(Peer p, String method, String arg, Bundle extras) {
        ContentProviderClient c = null;
        try {
            ProviderInfo pi = app.getPackageManager().resolveContentProvider(p.authority, 0);
            if (pi == null || !p.pkg.equals(pi.packageName)) throw new IllegalStateException(p.pkg + " 没有伴侣接口");
            c = app.getContentResolver().acquireUnstableContentProviderClient(p.authority);
            if (c == null) throw new IllegalStateException(p.pkg + " 连不上");
            Bundle b = c.call(method, arg, extras);
            if (b == null) throw new IllegalStateException(method + " 没有返回");
            return b;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(method + " 失败: " + e.getMessage(), e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static Map<String, Object> toMap(Bundle b) {
        if (b == null) return null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (String k : b.keySet()) m.put(k, b.get(k));
        return m;
    }

    /** 状态一句话（控制台 / 调试 / getAssistantStatus）。 */
    public String status() {
        StringBuilder sb = new StringBuilder();
        for (Peer p : Peer.values()) {
            CompanionHandshake.Result r;
            synchronized (this) {
                r = last.get(p);
            }
            if (sb.length() > 0) sb.append("；");
            sb.append(p == Peer.PLAN ? "计划 " : "时钟 ").append(r == null ? "还没检查" : r.toString());
        }
        return sb.toString();
    }
}
