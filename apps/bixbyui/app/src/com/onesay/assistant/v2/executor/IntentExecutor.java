package com.onesay.assistant.v2.executor;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android Intent 特权执行器。
 *
 * 核心设计（严格汲取 v1 BUG-002 经验）：
 * 1. 启动应用必须且只能使用显式组件（-n pkg/activity），坚决不用 am start -p；
 * 2. 具备品类词（浏览器、相机、相册、计算器等）动态映射表；
 * 3. 精准捕获未安装异常，上报结构化 NOT_FOUND_APP 错误码；
 * 4. ★ 缺陷修复（2026-10-02）：已安装应用索引从「实例级一次构建永不刷新」
 *    升级为「进程级共享 + 安装/卸载/替换广播主动作废 + 60s TTL 兜底」。
 */
public class IntentExecutor implements AppResolver {

    private static final String TAG = "IntentExecutor";

    /** 索引缓存兜底存活时间（广播注册失败时靠它保证最终一致） */
    private static final long INDEX_TTL_MS = 60_000L;

    /** 进程级共享的已安装应用索引快照（不可变对象，保证读写原子一致） */
    private static final class AppIndex {
        final Map<String, String> labelToPkg;
        final Map<String, String> pkgToComponent;
        final long builtAt;

        AppIndex(Map<String, String> labelToPkg, Map<String, String> pkgToComponent, long builtAt) {
            this.labelToPkg = labelToPkg;
            this.pkgToComponent = pkgToComponent;
            this.builtAt = builtAt;
        }
    }

    private static volatile AppIndex sIndex;
    private static volatile boolean sWatcherRegistered = false;

    private final Context mContext;
    private final PackageManager mPackageManager;

    private static final String[][] CATEGORY_ALIASES = {
        // 通用 / AOSP / Google 包在前，厂商包（三星等）在后；第一个装了的胜出。
        { "浏览器|网页浏览器|上网|网页", "com.android.chrome,com.android.browser,com.sec.android.app.sbrowser" },
        { "相机|照相机|拍照",         "com.android.camera2,com.android.camera,com.sec.android.app.camera" },
        { "相册|图库|照片",           "com.google.android.apps.photos,com.android.gallery3d,com.sec.android.gallery3d" },
        { "音乐|播放器|听歌",         "com.spotify.music,com.android.music,com.sec.android.app.music" },
        // 候选按「先真实存在的包」排序：OneSay Clock 在前，AOSP / Google / 厂商时钟兜底。
        { "时钟|闹钟",               "com.onesay.clock,com.android.deskclock,com.google.android.deskclock,com.sec.android.app.clockpackage" },
        { "计算器|算数",             "com.android.calculator2,com.google.android.calculator,com.sec.android.app.popupcalculator" },
        { "日历|日程",               "com.android.calendar,com.google.android.calendar,com.samsung.android.calendar" },
        { "联系人|通讯录|电话本",     "com.android.contacts,com.google.android.contacts,com.samsung.android.app.contacts" },
        { "信息|短信|短消息",         "com.android.mms,com.google.android.apps.messaging,com.samsung.android.messaging" },
        { "应用商店|应用市场|商店",   "com.android.vending,com.huawei.appmarket,com.xiaomi.market,com.heytap.market,com.oppo.market,com.bbk.appstore,com.sec.android.app.samsungapps" },
        { "文件|我的文件|文件管理",   "com.android.documentsui,com.google.android.apps.nbu.files,com.sec.android.app.myfiles" },
        { "笔记|三星笔记|便签|记事",   "com.samsung.android.app.notes" },
    };

    public IntentExecutor(Context context) {
        this.mContext = context.getApplicationContext();
        this.mPackageManager = mContext.getPackageManager();
        registerPackageWatcher(mContext);
    }

    /**
     * ★ 缺陷修复（2026-10-02）：注册应用增删广播，索引在应用变动时立即作废。
     *
     * 此前索引是实例级缓存且只构建一次，服务常驻数天后用户新装/卸载应用，
     * 助手会一直回“未找到该应用”，必须强杀进程才恢复。
     */
    private static void registerPackageWatcher(final Context appCtx) {
        if (sWatcherRegistered) return;
        synchronized (IntentExecutor.class) {
            if (sWatcherRegistered) return;
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                filter.addDataScheme("package");
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.i(TAG, "检测到应用增删/替换，作废应用索引缓存: "
                                + (intent != null ? intent.getDataString() : "?"));
                        sIndex = null;
                    }
                }, filter);
                sWatcherRegistered = true;
                Log.i(TAG, "已注册 PACKAGE_ADDED/REMOVED/REPLACED 监听，应用索引可动态刷新");
            } catch (Throwable t) {
                Log.w(TAG, "注册应用增删广播失败，退回 60s TTL 兜底刷新: " + t.getMessage());
            }
        }
    }

    private void ensureAppIndex() {
        AppIndex cur = sIndex;
        long now = SystemClock.elapsedRealtime();
        if (cur != null && (now - cur.builtAt) < INDEX_TTL_MS) return;

        synchronized (IntentExecutor.class) {
            cur = sIndex;
            now = SystemClock.elapsedRealtime();
            if (cur != null && (now - cur.builtAt) < INDEX_TTL_MS) return;

            Map<String, String> labelToPkg = new HashMap<String, String>();
            Map<String, String> pkgToComponent = new HashMap<String, String>();

            try {
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = mPackageManager.queryIntentActivities(main, 0);

                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    String label = normalize(String.valueOf(ri.loadLabel(mPackageManager)));
                    String comp = pkg + "/" + ri.activityInfo.name;

                    if (label.length() > 0 && !labelToPkg.containsKey(label)) {
                        labelToPkg.put(label, pkg);
                    }

                    // 优先以规范启动 Intent 作为标准入口
                    Intent launch = mPackageManager.getLaunchIntentForPackage(pkg);
                    if (launch != null && launch.getComponent() != null) {
                        pkgToComponent.put(pkg, launch.getComponent().flattenToString());
                    } else if (!pkgToComponent.containsKey(pkg)) {
                        pkgToComponent.put(pkg, comp);
                    }
                }
                Log.i(TAG, "已加载应用索引：" + labelToPkg.size() + " 个标签，"
                        + pkgToComponent.size() + " 个启动组件");
            } catch (Throwable t) {
                Log.w(TAG, "构建应用索引失败: " + t.getMessage());
            }

            // 原子发布（AppIndex 不可变，读侧永远拿到成对一致的索引）
            sIndex = new AppIndex(labelToPkg, pkgToComponent, SystemClock.elapsedRealtime());
        }
    }

    /**
     * 根据应用名称或品类词解析唯一启动组件（pkg/activity）。
     */
    @Override
    public String resolveComponent(String appName) {
        if (appName == null || appName.trim().length() == 0) return null;
        ensureAppIndex();
        AppIndex idx = sIndex;
        if (idx == null) return null;
        return resolveIn(appName, idx.labelToPkg, idx.pkgToComponent, mContext.getPackageName());
    }

    /**
     * 纯函数版（JVM 可测）。顺序：包名直达 → 精确标签 → ★R11 自家别名 → 单向包含 → 品类别名 → 包名 → ★R11 去掉「设置」后缀再精确匹配。
     */
    public static String resolveIn(String appName, Map<String, String> labelToPkg, Map<String, String> pkgToComponent, String selfPkg) {
        if (appName == null || appName.trim().length() == 0) return null;
        String key = normalize(appName);

        // 0. [roles] 包名直达（角色层按包名启动）：形如包名且已索引 → 直接用，不走标签模糊匹配
        if (appName.indexOf('.') > 0 && pkgToComponent.containsKey(appName.trim())) {
            return pkgToComponent.get(appName.trim());
        }

        // 1. 精确匹配应用标签
        String exactPkg = labelToPkg.get(key);
        if (exactPkg != null) {
            return pkgToComponent.get(exactPkg);
        }

        // 1b. ★ R11：自家别名（「Bixby」「OneSay」「控制台」…）→ 本 App 控制台。
        //     以前「bixby」走单向包含，会在「Bixby 视觉」和「OneSay 控制台」之间随 HashMap 顺序乱选。
        if (selfPkg != null && AppNameRules.isSelfAlias(key) && pkgToComponent.containsKey(selfPkg)) {
            return pkgToComponent.get(selfPkg);
        }

        // 2. 单向包含模糊匹配
        for (Map.Entry<String, String> entry : labelToPkg.entrySet()) {
            if (entry.getKey().contains(key)) {
                return pkgToComponent.get(entry.getValue());
            }
        }

        // 3. 品类别名匹配
        String cat = categoryComponent(key, pkgToComponent, false);
        if (cat != null) return cat;

        // 4. 包名直接匹配
        if (pkgToComponent.containsKey(appName)) {
            return pkgToComponent.get(appName);
        }

        // 5. ★ R11（真机 23:22「打开Bixby设置」→ L0 解析不到 → 分诊 DO_ACT 交 AgentLoop，面板显示「复合任务」，
        //    4 轮探针后才打开 com.onesay.assistant）：去掉「设置 / 的设置 / settings」后缀，剩下的部分<b>精确</b>是某个应用
        //    （标签 / 自家别名 / 品类词）→ 打开它（App 的设置在 App 里）。只认精确，避免「wifi设置」被包含匹配到别的 App。
        String rest = AppNameRules.stripSettingsSuffix(key);
        if (rest != null) {
            String p = labelToPkg.get(rest);
            if (p != null && pkgToComponent.containsKey(p)) return pkgToComponent.get(p);
            if (selfPkg != null && AppNameRules.isSelfAlias(rest) && pkgToComponent.containsKey(selfPkg)) return pkgToComponent.get(selfPkg);
            String c = categoryComponent(rest, pkgToComponent, true);
            if (c != null) return c;
        }
        return null;
    }

    private static String categoryComponent(String key, Map<String, String> pkgToComponent, boolean exactOnly) {
        for (String[] mapping : CATEGORY_ALIASES) {
            String[] triggers = mapping[0].split("\\|");
            for (String trigger : triggers) {
                if (trigger.equals(key) || (!exactOnly && key.contains(trigger))) {
                    String[] candidates = mapping[1].split(",");
                    for (String cand : candidates) {
                        String c = pkgToComponent.get(cand.trim());
                        if (c != null) return c;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 启动指定组件应用。
     */
    @Override
    public boolean launchComponent(String componentStr) {
        if (componentStr == null || componentStr.indexOf('/') < 0) return false;
        try {
            String[] parts = componentStr.split("/");
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(parts[0], parts[1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mContext.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "启动应用组件失败: " + componentStr + ", error=" + t.getMessage());
            return false;
        }
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。？！、]", "").toLowerCase();
    }
}
