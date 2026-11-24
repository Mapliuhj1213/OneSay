package com.onesay.assistant.v2.a11y;

/**
 * ★ a11y-1118：本 App 的无障碍读屏服务此刻是什么状态（纯 Java，JVM 可测）。
 *
 * <p>「打开了」看系统设置 {@code enabled_accessibility_services}（冒号分隔的组件名，可能是完整写法
 * {@code com.onesay.assistant.debug/com.onesay.assistant.v2.a11y.OneSayA11yService}，也可能是短写法
 * {@code com.onesay.assistant/.v2.a11y.OneSayA11yService}；debug 包的 applicationId 带 .debug 后缀而 Java 包名不带，
 * 所以只有 release 会出现短写法）。「连上了」看服务实例（{@link A11yBackend#connected()}）。
 * 两者不一致（设置里开着、实例没连上）多半是系统刚把服务解绑、正要重新绑定（Flyme 上大约每 30 s 一次），等一会儿就好。
 */
public final class A11yState {
    private A11yState() {}

    /** 服务实现类（Manifest 里声明的空壳类名，固定不变）。 */
    public static final String SERVICE_CLASS = "com.onesay.assistant.v2.a11y.OneSayA11yService";

    /** settings 串里有没有本包的这个服务（大小写不敏感，兼容完整 / 短写法、前后空白）。 */
    public static boolean enabledIn(String setting, String pkg, String cls) {
        if (setting == null || pkg == null || cls == null || pkg.length() == 0 || cls.length() == 0) return false;
        for (String raw : setting.split(":")) {
            String c = raw.trim();
            int slash = c.indexOf('/');
            if (slash <= 0) continue;
            String p = c.substring(0, slash), k = c.substring(slash + 1);
            if (!p.equalsIgnoreCase(pkg)) continue;
            if (k.startsWith(".")) k = p + k;
            if (k.equalsIgnoreCase(cls)) return true;
        }
        return false;
    }

    /** 助手报错里会带内部模板 / 进程名，给用户和模型看之前去掉内部代号。纯函数。 */
    public static String scrub(String s) {
        if (s == null) return "";
        String t = s.replace("bixby-fastscreen-start", "读屏助手启动模板");
        t = t.replaceAll("(?i)bixbyui", "onesay").replaceAll("(?i)bixby", "onesay");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /** 读屏后端选哪个。 */
    public enum Backend { HELPER, A11Y, NONE }

    /** 选择结果：后端 + 不可用时给用户 / 模型看的原因 + 要不要给「打开无障碍」入口。 */
    public static final class Choice {
        public final Backend backend;
        public final String reason;
        public final boolean offerA11ySettings;
        /** [exec-1124 C] 按动作选后端时的大方向（{@link #route}）。 */
        public final Route route;

        Choice(Backend b, String reason, boolean offer) {
            this(b, reason, offer, b == Backend.HELPER ? Route.HELPER : b == Backend.A11Y ? Route.A11Y_ONLY : Route.NONE);
        }

        Choice(Backend b, String reason, boolean offer, Route r) {
            this.backend = b;
            this.reason = reason;
            this.offerA11ySettings = offer;
            this.route = r;
        }

        @Override public String toString() {
            return backend + "/" + route + (reason != null ? " (" + reason + ")" : "") + (offerA11ySettings ? " +entry" : "");
        }
    }

    /** 原因里的标记：带它 = 要用户去打开无障碍读屏服务（PhoneFailure / 结果卡入口据此判断）。 */
    public static final String NEED_A11Y = "需要打开无障碍读屏服务";

    /**
     * 读屏后端判定（phone.operate / 编号点选开局用）。
     *
     * @param helperOk          常驻读屏助手可用（ping 通）
     * @param a11yConnected     无障碍服务实例连着（等过重新绑定之后）
     * @param a11yInSettings    系统设置里本服务是打开的
     * @param a11yKilled        开发者显式关了无障碍后端（kill 文件 / 插件显式关）
     * @param helperError       助手不可用的原因（FastScreen.lastError）
     */
    public static Choice choose(boolean helperOk, boolean a11yConnected, boolean a11yInSettings, boolean a11yKilled, String helperError) {
        return choose(helperOk, false, a11yConnected, a11yInSettings, a11yKilled, helperError);
    }

    /**
     * [exec-1124 C] 按动作选后端的大方向。不再「整个任务二选一」：
     * <ul>
     *   <li>{@link #HELPER_ROOT}：助手是 root 守护拉起的（Note 8 这类）→ 动作顺序保持原样（助手为主），只有返回 / 主页先用无障碍全局动作；</li>
     *   <li>{@link #A11Y_FIRST}：助手（Shizuku）和无障碍都在 → 以无障碍为主、助手兜底；点按 节点点击 → 无障碍手势 → 助手注入；</li>
     *   <li>{@link #HELPER}：只有助手（无障碍没开）；</li>
     *   <li>{@link #A11Y_ONLY}：只有无障碍（原来的兜底模式）；</li>
     *   <li>{@link #NONE}：都不行。</li>
     * </ul>
     */
    public enum Route { HELPER_ROOT, A11Y_FIRST, HELPER, A11Y_ONLY, NONE }

    /** [exec-1124 C] 纯函数：大方向判定。被开发者显式关掉的无障碍后端不算「在」。 */
    public static Route route(boolean helperOk, boolean helperRoot, boolean a11yConnected, boolean a11yKilled) {
        boolean a = a11yConnected && !a11yKilled;
        if (helperOk && helperRoot) return Route.HELPER_ROOT;
        if (helperOk && a) return Route.A11Y_FIRST;
        if (helperOk) return Route.HELPER;
        if (a) return Route.A11Y_ONLY;
        return Route.NONE;
    }

    /** [exec-1124 C] 同 {@link #choose(boolean, boolean, boolean, boolean, String)}，多一个「助手是不是 root 拉起的」。 */
    public static Choice choose(boolean helperOk, boolean helperRoot, boolean a11yConnected, boolean a11yInSettings, boolean a11yKilled, String helperError) {
        Route r = route(helperOk, helperRoot, a11yConnected, a11yKilled);
        if (helperOk) return new Choice(Backend.HELPER, null, false, r);
        if (a11yConnected && !a11yKilled) return new Choice(Backend.A11Y, null, false);
        String he = helperError == null || helperError.trim().length() == 0 ? "" : "（" + scrub(helperError.trim()) + "）";
        if (a11yKilled) return new Choice(Backend.NONE, "读屏助手不可用" + he + "，无障碍读屏后端被关掉了", false);
        if (a11yInSettings) {
            return new Choice(Backend.NONE, "读屏助手不可用" + he + "；无障碍读屏服务已打开但还没连上（系统可能刚把它停掉），"
                    + "稍等几秒再试；还不行就在无障碍设置里把 OneSay 读屏服务关掉再打开。" + NEED_A11Y, true);
        }
        return new Choice(Backend.NONE, "读屏助手不可用" + he + "；" + NEED_A11Y + "：到「设置 › 无障碍」打开 OneSay 读屏服务后再说一遍", true);
    }
}
