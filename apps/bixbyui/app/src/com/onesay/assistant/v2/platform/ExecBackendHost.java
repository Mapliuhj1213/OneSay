package com.onesay.assistant.v2.platform;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecBackends;
import com.onesay.plugin.api.exec.ExecResult;
import com.onesay.plugin.exec.root.RootExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

/**
 * 宿主侧：注册执行后端插件（libs/plugins/exec-*），读写用户在「设备能力 › 执行后端」里的选择。
 * 默认不选任何后端 —— 开源构建的特权动作因此默认置灰；选中的后端报告可用后才点亮。
 *
 * <p>选 {@code root}（执行守护）时命令照旧走 {@link ShellExecutor} 的守护通道；选 {@code root_su} / {@code shizuku} 时，
 * {@link ShellExecutor#execute} 改交给该后端（见 {@link #delegate()}）。
 */
public final class ExecBackendHost {
    private ExecBackendHost() {}

    public static final String PREFS = "onesay_exec";
    public static final String KEY_BACKEND = "backend";
    /** ★ priv-llm：「Shizuku 特权」插件开关（默认关）。 */
    public static final String KEY_SHIZUKU = "shizuku_enabled";

    private static volatile ShizukuExecBackend sShizuku;
    private static volatile boolean sShizukuOn;

    private static volatile boolean sInit;
    private static volatile Context sApp;

    public static void ensureInit(Context ctx) {
        if (sInit || ctx == null) return;
        synchronized (ExecBackendHost.class) {
            if (sInit) return;
            try {
            final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            sApp = app;
            ExecBackends.register(RootExecBackend.daemon(new RootExecBackend.Channel() {
                @Override public ExecResult run(String command, long timeoutMs) {
                    ShellExecutor.ShellResponse r = new ShellExecutor(app).execute(command);
                    return new ExecResult(r.rc, r.output, r.isTimeout, r.isDown);
                }

                @Override public ExecBackend.Tri online() {
                    DeviceProfile p = DeviceCapabilityProbe.cached(app);
                    Tri t = p == null ? Tri.UNKNOWN : p.get(DeviceCap.EXEC_DAEMON);
                    return t == Tri.YES ? ExecBackend.Tri.YES : (t == Tri.NO ? ExecBackend.Tri.NO : ExecBackend.Tri.UNKNOWN);
                }
            }));
            ExecBackends.register(RootExecBackend.su(null));
            ShizukuExecBackend shz;
            try {
                ShizukuWhitelist wl = new ShizukuWhitelist(app.getPackageName());
                wl.setSelfInfo(new ShizukuWhitelist.SelfInfo() {   // ★ a11y-1118：读屏助手 argv 的期望值从 PackageManager 现取
                    @Override public String baseApk() {
                        try {
                            return app.getPackageManager().getApplicationInfo(app.getPackageName(), 0).sourceDir;
                        } catch (Throwable t) {
                            return null;
                        }
                    }

                    @Override public int uid() {
                        try {
                            return app.getPackageManager().getApplicationInfo(app.getPackageName(), 0).uid;
                        } catch (Throwable t) {
                            return -1;
                        }
                    }

                    @Override public String helperMain() {
                        return com.onesay.assistant.v2.fastscreen.FsProtocol.MAIN_CLASS;
                    }
                });
                shz = new ShizukuExecBackend(new ShizukuBridge(), wl);
            } catch (Throwable t) {
                shz = new ShizukuExecBackend();   // 库缺失 / 包名异常：不可用，但照样登记（设置页能看到原因）
            }
            sShizuku = shz;
            ExecBackends.register(shz);
            ExecBackends.select(sp(app).getString(KEY_BACKEND, ExecBackends.NONE));
            sShizukuOn = sp(app).getBoolean(KEY_SHIZUKU, false);
            sInit = true;
            } catch (Throwable t) {
                // 宿主环境不完整（例如 JVM 单测里的假 Context）：不注册任何后端 = 特权动作保持置灰，守护通道照旧。
            }
        }
    }

    /** 设置页：选后端（"" = 不选）。su 模式选中后后台探测一次（会触发 root 管理器授权弹窗）。 */
    public static void select(Context ctx, final String id) {
        ensureInit(ctx);
        sp(ctx).edit().putString(KEY_BACKEND, id == null ? ExecBackends.NONE : id).apply();
        ExecBackends.select(id);
        final ExecBackend b = ExecBackends.get(id);
        if (b instanceof RootExecBackend && "root_su".equals(b.id())) {
            com.onesay.assistant.v2.util.Threads.start("exec-su-probe", new Runnable() {
                @Override public void run() {
                    ((RootExecBackend) b).probe();
                }
            });
        }
    }

    /** ★ priv-llm：「Shizuku 特权」开关（默认关）。 */
    public static boolean shizukuEnabled(Context ctx) {
        ensureInit(ctx);
        return sShizukuOn;
    }

    /** 打开时：Shizuku 在跑但没授权 → 弹 Shizuku 的授权框。关掉 = 之后的命令不再经 Shizuku。 */
    public static void setShizukuEnabled(Context ctx, boolean on) {
        ensureInit(ctx);
        sp(ctx).edit().putBoolean(KEY_SHIZUKU, on).apply();
        sShizukuOn = on;
        if (on) ShizukuBridge.requestPermissionIfNeeded();
    }

    /** 控制台用：插件关 → null（不显示）；开 → Shizuku 此刻可不可用。 */
    public static Boolean shizukuReady(Context ctx) {
        ensureInit(ctx);
        ShizukuExecBackend s = sShizuku;
        if (!sShizukuOn && !ShizukuExecBackend.ID.equals(ExecBackends.selected())) return null;
        return s != null && s.available() == ExecBackend.Tri.YES;
    }

    public static ShizukuExecBackend shizuku() {
        return sShizuku;
    }

    /** ★ priv-llm：按执行档位（su / root 守护 / Shizuku 白名单 / 原守护）决定这条命令走哪里，见 {@link ExecTierRouter}。 */
    public static ExecTierRouter.Route route(String command) {
        if (!sInit) return ExecTierRouter.DAEMON;
        return ExecTierRouter.route(command, ExecBackends.ready(), sShizuku, sShizukuOn);
    }

    /**
     * {@link ShellExecutor} 用：选中的是非守护后端且此刻可用 → 返回它；否则 null（照旧走守护）。
     */
    public static ExecBackend delegate() {
        if (!sInit) return null;
        ExecBackend b = ExecBackends.ready();
        return b != null && !"root".equals(b.id()) ? b : null;
    }

    /**
     * ★ a11y-1118：这条命令此刻是不是落在 Shizuku 档（没有可用的 root / su，Shizuku 开着且可用）。
     * 读屏助手据此改发精确 argv（{@link #spawnFastScreenHelper}），识屏截图据此先试外部缓存目录。
     */
    public static boolean shizukuTier(Context ctx) {
        ensureInit(ctx);
        if (!sInit) return false;
        return ExecTierRouter.isShizukuTier(ExecBackends.ready(), sShizuku, sShizukuOn);
    }

    /** ★ a11y-1118：经 Shizuku 起读屏助手（精确 argv，白名单逐项核对）。返回 null = 此刻不走 Shizuku 档。 */
    public static ExecResult spawnFastScreenHelper(Context ctx, long settleMs) {
        if (!shizukuTier(ctx)) return null;
        ShizukuExecBackend s = sShizuku;
        if (s == null) return null;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        String apk;
        int uid;
        try {
            android.content.pm.ApplicationInfo ai = app.getPackageManager().getApplicationInfo(app.getPackageName(), 0);
            apk = ai.sourceDir;
            uid = ai.uid;
        } catch (Throwable t) {
            return ExecResult.down("读不到本包安装路径");
        }
        return s.spawnHelper(ShizukuWhitelist.helperArgv(app.getPackageName(), apk, uid, com.onesay.assistant.v2.fastscreen.FsProtocol.MAIN_CLASS), settleMs);
    }

    /** ★ fix/1123-bug1 B5：刚经 Shizuku 起的读屏助手的标准输入 / 输出（{@link com.onesay.assistant.v2.fastscreen.FsStdio}）；没有返回 null。 */
    public static com.onesay.assistant.v2.fastscreen.FsStdio.Pipes takeFastScreenPipes() {
        try {
            return ShizukuBridge.takeHelperPipes();
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
