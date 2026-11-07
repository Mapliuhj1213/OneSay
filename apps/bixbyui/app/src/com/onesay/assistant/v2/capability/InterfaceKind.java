package com.onesay.assistant.v2.capability;

/**
 * 接口类型分类器 —— 给每个能力打上「二级分类」。
 *
 * <h3>用户定的分类体系（2026-10-04）</h3>
 * <pre>
 *   一级：按软件（settings / connectivity / browser / store / notes …）
 *          → 直接沿用 {@link Capability#category}（能力签名第一段）
 *   二级：按接口类型 —— **增删改查 与 调用UI 必须分开**
 *          → 本类负责推导
 * </pre>
 *
 * <h3>为什么要二级分类（不是为了好看）</h3>
 * <p>它决定**执行语义与安全策略**，是三件事的开关：
 * <ol>
 *   <li><b>读-改分离</b>：用户明确要求"shell 先读再执行"。QUERY 类接口
 *       天然就是"读"，可以直接执行；而 MUTATE 类接口执行前应先跑一次
 *       QUERY 观测当前值 —— 没有二级分类，执行器不知道该先读什么。</li>
 *   <li><b>LLM 选接口的约束</b>：DO 手册要告诉模型"你手里有哪几类接口"。
 *       增删改（会改系统状态）与查询（只读）混在一起时，模型容易用查询冒充执行。</li>
 *   <li><b>确认门槛</b>：增/删（不可逆）与改（可逆）与查（无副作用）的风险本质不同，
 *       这个差别就是确认卡策略的依据。</li>
 * </ol>
 *
 * <h3>推导规则（从能力自身字段推，不靠人手工标注）</h3>
 * <p>能力的语义已经写在它的字段里，二级分类是**读出来**的，不是"再编一套"：
 * <ul>
 *   <li>{@code executor=intent} + cmd 是 {@code am start} → {@link Kind#UI}（调用界面）</li>
 *   <li>有 {@code check} 且 cmd 是纯读取（get/state/--get/list）→ {@link Kind#QUERY}</li>
 *   <li>cmd 含 {@code --delete/-d }/{@code uninstall}/{@code pm clear} → {@link Kind#DELETE}</li>
 *   <li>sign 以 {@code .set/.on/.off/.up/.down} 结尾，或 cmd 写状态 → {@link Kind#UPDATE}</li>
 *   <li>新增类（新建笔记、建闹钟）→ {@link Kind#CREATE}</li>
 * </ul>
 * 推导不出来时回落 {@link Kind#UPDATE}（最保守：当"要改状态"处理，走确认门槛）。
 */
public final class InterfaceKind {

    private InterfaceKind() {}

    /** 二级接口类型 —— 增删改查 + 调用UI。 */
    public enum Kind {
        /** 查：只读，不改任何状态（brightness.get / wifi.state / time.now）。 */
        QUERY,
        /** 增：在本机产出一个新实体（新建笔记 / 建闹钟 / 新建日程）。 */
        CREATE,
        /** 改：修改已有状态，可逆（亮度调高 / wifi on / 音量调低）。 */
        UPDATE,
        /** 删：删除/清理，通常不可逆（卸载 / 清除缓存 / 删除闹钟）。 */
        DELETE,
        /** 调用UI：拉起某个界面/意图，让用户在自己界面里操作（am start / 打开商店）。 */
        UI,
        /** 无法判定 —— 按 UPDATE 处理（保守）。 */
        UNKNOWN;

        /** 是否只读（可无确认直接执行）。 */
        public boolean isReadOnly() {
            return this == QUERY;
        }

        /** 是否有副作用（执行前应做读-改分离的"先读"）。 */
        public boolean hasSideEffect() {
            return this == CREATE || this == UPDATE || this == DELETE;
        }

        /** 是否不可逆（需要更高门槛的确认）。 */
        public boolean isDestructive() {
            return this == DELETE;
        }

        /** 中文名（供 UI / 提示词展示）。 */
        public String cn() {
            switch (this) {
                case QUERY:  return "查询";
                case CREATE: return "新增";
                case UPDATE: return "修改";
                case DELETE: return "删除";
                case UI:     return "调用界面";
                default:     return "未分类";
            }
        }
    }

    /**
     * 从能力定义推导二级类型。
     *
     * @param cap 能力（真源）
     * @return 二级接口类型；永不为 null（兜底 UNKNOWN）
     */
    public static Kind of(Capability cap) {
        if (cap == null) return Kind.UNKNOWN;

        String sign = cap.sign != null ? cap.sign : "";
        String cmd = cap.cmdTemplate != null ? cap.cmdTemplate : "";
        String verdictKey = sign.toLowerCase() + " " + cmd.toLowerCase();
        String cmdL = cmd.toLowerCase();

        // ---------- ① 删除：不可逆动作**最高优先**（★ 修正：提前到 UI 判定之前）----------
        //   起因：`app.uninstall` 走 intent 通道（拉起系统卸载界面），
        //   初版先判 UI → 被归成"调用界面"，丢掉了"这是不可逆操作"这个关键属性。
        //   但确认门槛、读-改分离都是按**语义**（删了回不来）而非按**通道**（am start）定的，
        //   所以不可逆动作必须先标记，通道只影响"怎么执行"不影响"要不要拦"。
        if (containsAny(cap.sign != null ? cap.sign.toLowerCase() : "", DELETE_MARKERS)
                || containsAny(cmdL, DELETE_MARKERS)) {
            return Kind.DELETE;
        }

        // ---------- ② 调用 UI：走 intent 通道拉界面 ----------
        //   判据：executor=intent（执行体是 am start）。
        //   这类接口的"产出"是一个界面，不是状态变更 —— 必须单列一类，
        //   否则 LLM 会把"打开了搜索页"当成"已完成搜索"（坑2的变体）。
        if ("intent".equals(cap.executorType) || cmd.contains("am start")) {
            return Kind.UI;
        }

        // ---------- ③ 查询：**必须无写动作**（提前到 CREATE 之前、并加写排除）----------
        //
        //  【这一条为什么改】实测发现 `settings.brightness.up` 被误判成 QUERY。
        //  它的 cmd 是**读-改复合命令**：
        //      b=$(settings get system screen_brightness); n=$((b+40)); ...; settings put system screen_brightness $n
        //  初版 `isQueryCmd` 只看见里面的 `settings get` 就判 QUERY，
        //  **完全忽略了后面那句 settings put** —— 写操作被读特征掩盖了。
        //
        //  危害：把"改"当"读" → 不加确认门槛、不走读-改分离 → 用户说"亮度调高"被当作无害只读直接执行。
        //
        //  判据修正为**写优先**：只要 cmd 里出现真正改状态的动作，就绝不允许归 QUERY。
        //  注意 `echo` **不算**写 —— 它只打印，不动系统状态（nfc.state / store.status.check
        //  就是靠 echo 输出读到的结果，把它们误杀成写会反过来把"读"弄丢）。
        if (!hasMutatingCmd(cmdL) && (isQuerySign(sign) || isQueryCmd(cmd))) {
            return Kind.QUERY;
        }

        // ---------- ④ 新增：产出新实体 ----------
        if (containsAny(verdictKey, CREATE_MARKERS)) {
            return Kind.CREATE;
        }

        // ---------- ⑤ 修改：有 cmd 且不是上述 → 改状态 ----------
        if (cmd.length() > 0) {
            return Kind.UPDATE;
        }

        // ---------- ⑥ 兜底：能力的 risk/sideEffect 说明它有副作用 → UPDATE，否则 QUERY ----------
        if (cap.sideEffect == SideEffect.DEVICE_CONTROL) return Kind.UPDATE;
        return Kind.UNKNOWN;
    }

    /**
     * 一级分类：按**软件**（用户定的分类体系第一级）。
     *
     * <p>判据优先级（从"按 category"改为"按 sign 首段"）：
     * <ol>
     *   <li><b>sign 首段</b> —— 这是最细的"软件"标识：
     *       {@code browser.*}→浏览器、{@code store.*}→应用商店、{@code notes.*}→笔记、
     *       {@code settings.*}→系统设置、{@code connectivity.*}→连接、{@code volume.*}→音量。</li>
     *   <li>sign 无点时回落 {@code category}。</li>
     * </ol>
     *
     * <p>为什么改：初版用 {@code category}（settings/net/media/app/clock/sys），
     * 结果 {@code app} 一组里塞了 19 个接口，浏览器、商店、笔记全混在一起 ——
     * 那不是"按软件"，是"按大类"。用户要的一级分类是**能一眼看出是哪个软件**，
     * 所以取 sign 首段（软件名就写在签名里，这本就是它的第一段语义）。
     */
    public static String softwareOf(Capability cap) {
        if (cap == null) return "unknown";
        if (cap.sign != null && cap.sign.indexOf('.') > 0) {
            return cap.sign.substring(0, cap.sign.indexOf('.'));
        }
        if (cap.category != null && cap.category.length() > 0) return cap.category;
        return "unknown";
    }

    // ---------- 内部判据 ----------

    /** 命名上明示只读的后缀。 */
    private static final String[] QUERY_SIGNS = {
            ".get", ".state", ".check", ".list", ".query", ".info", ".status"
    };

    /** cmd 里的只读命令特征。 */
    private static final String[] QUERY_CMDS = {
            "settings get", "dumpsys", "--get", "cmd wifi status", "getprop",
            "cat /sys", " -e list", "pm list", "get-state"
    };

    /**
     * 删除特征。
     *
     * <p>去掉 {@code "rm -"} —— 它会被**参数/路径**误撞：
     * 实测 {@code clock.alarm.set} 的 cmd 里 {@code android.intent.extra.alarm.HOUR {hour} --ei ...}
     * 中间凑出了 {@code "rm -"} 子串，把"设定闹钟"误判成删除类。
     * 结论：DELETE 判定只用**语义明确的整词**，不用这种两字符碎片。
     */
    private static final String[] DELETE_MARKERS = {
            "uninstall", "pm clear", "delete", "remove", "clear",
            "卸", "清除", "删除", "清理"
    };

    /** cmd/sign 里的新增特征。 */
    private static final String[] CREATE_MARKERS = {
            "insert", "new", "create", "add", "新建", "创建", "记录", "写"
    };

    private static boolean isQuerySign(String sign) {
        String s = sign.toLowerCase();
        for (String suf : QUERY_SIGNS) {
            if (s.endsWith(suf)) return true;
        }
        return false;
    }

    private static boolean isQueryCmd(String cmd) {
        if (cmd == null || cmd.length() == 0) return false;
        String c = cmd.toLowerCase();
        for (String q : QUERY_CMDS) {
            if (c.contains(q)) return true;
        }
        return false;
    }

    /**
     * 真正**改状态**的 cmd 特征（新增）。
     *
     * <p>判据原则：看它**动没动系统状态**，而不是看它"用没用 shell 命令"。
     * <ul>
     *   <li>{@code settings put} / {@code settings delete} —— 写系统设置</li>
     *   <li>{@code svc } —— 开关 wifi/数据/电源</li>
     *   <li>{@code am start} / {@code am broadcast} —— 拉起界面或发广播</li>
     *   <li>{@code pm enable/disable/clear} —— 改包状态</li>
     *   <li>{@code cmd }（如 {@code cmd wifi set-wifi-enabled}）—— 改连接状态</li>
     *   <li>{@code input } —— 注入按键/触摸</li>
     *   <li>{@code media volume --stream N --set} —— 改音量</li>
     *   <li>{@code >} 重定向写入 / {@code chmod}/{@code rm} —— 落盘改动</li>
     * </ul>
     *
     * <p><b>刻意不含 {@code echo}</b>：echo 只把结果打到 stdout，不改任何状态。
     * 实测 {@code connectivity.nfc.state} 与 {@code store.status.check} 都是
     * "dumpsys 读 → echo 输出"，若把 echo 当写会把这俩纯读接口误杀成非 QUERY。
     */
    private static final String[] MUTATING_CMDS = {
            "settings put", "settings delete",
            "svc ", "am start", "am broadcast",
            "pm enable", "pm disable", "pm clear",
            "cmd wifi set", "cmd connectivity",
            "input ", "media volume",
            "chmod", "chown", " > ", ">>",
            "setprop"
    };

    private static boolean hasMutatingCmd(String cmdL) {
        if (cmdL == null || cmdL.length() == 0) return false;
        for (String m : MUTATING_CMDS) {
            if (cmdL.contains(m)) return true;
        }
        return false;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        if (haystack == null) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
