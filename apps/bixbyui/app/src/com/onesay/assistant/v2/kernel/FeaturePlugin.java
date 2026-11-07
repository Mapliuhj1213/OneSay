package com.onesay.assistant.v2.kernel;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [kernel] 功能插件的统一接口（Java 7：抽象类 + 空实现，插件只覆盖自己用到的挂钩）。
 *
 * <h3>一个插件声明什么</h3>
 * <table>
 *   <tr><th>方面</th><th>方法</th></tr>
 *   <tr><td>身份</td><td>{@link #id()}、{@link #title()}、{@link #logTag()}（诊断日志 tag）、{@link #acceptanceModule()}（验收脚本模块名）</td></tr>
 *   <tr><td>开关</td><td>{@link #prefsName()} + {@link #killFiles()} + {@link #enabled(Context)}（总开关）；新插件可直接用 {@link FeatureSwitch}</td></tr>
 *   <tr><td>生命周期</td><td>{@link #onInit}（装配时一次）/ {@link #onEnable} / {@link #onDisable}（开关翻转时，由内核在服务启动、每次唤醒时比对）</td></tr>
 *   <tr><td>分诊</td><td>{@link #triageSignal()} + {@link #triageOn(Context)}（分诊开关信号）、{@link #triageRules()}（声明自己的规则 id，文档 / 状态用）、{@link #l0}（L0 直达）</td></tr>
 *   <tr><td>执行</td><td>{@link #route}（路由层本地接管）、{@link #claims}（不进 AgentLoop）、{@link #dispatch}（按签名接管派发）、{@link #describe}（自带能力）</td></tr>
 *   <tr><td>面板</td><td>{@link #interceptCommand}、{@link #bypassScreenRef}、{@link #onReplyShown}、{@link #onExecuted}、{@link #onExecFailed}、{@link #onServiceCreate}、{@link #onServiceDestroy}</td></tr>
 *   <tr><td>灵动岛</td><td>{@link #islandActivities()}：这个插件会往岛上放哪些活动</td></tr>
 *   <tr><td>控制台</td><td>{@link #consoleCard()}：控制台卡片说明；{@link #debugStatus(Context)}：一行状态</td></tr>
 *   <tr><td>依赖 / 调试</td><td>{@link #dependsOn()}：依赖的其他插件 id（被关时内核在状态里提示，不改行为）；
 *       {@link #debugExtra()}：debug 包 TestRunReceiver 的 {@code --es <名>} 调试入口</td></tr>
 * </table>
 *
 * <h3>内核保证</h3>
 * <ul>
 *   <li>每个挂钩调用都包在 try/catch 里：一个插件抛异常只影响它自己（记 {@code [KERNEL]} 警告，按「没接管」处理），不影响其他插件和主流程；</li>
 *   <li>{@link #kernelGated()} 为 true 的插件在 {@link #enabled(Context)} 为 false 时内核直接跳过它的所有挂钩；
 *       迁移进来的老功能保持 false —— 它们内部按更细的子开关现查（行为与迁移前一致）；</li>
 *   <li>挂钩顺序 = {@link #priority(FeatureHook)}，小的先调。</li>
 * </ul>
 */
public abstract class FeaturePlugin {

    // ------------------------------------------------------------------ 身份

    /** 唯一 id（小写，和 prefs / kill-switch / 验收模块同名为佳）。 */
    public abstract String id();

    /** 给人看的名字（控制台 / 文档）。 */
    public abstract String title();

    /** 诊断日志 tag（logcat 里 grep 用）。 */
    public String logTag() {
        return "[" + id().toUpperCase() + "]";
    }

    /** 验收脚本 device_acceptance.sh 里的模块名（没有就返回 null）。 */
    public String acceptanceModule() {
        return id();
    }

    // ------------------------------------------------------------------ 开关

    /** SharedPreferences 名（bixby_xxx）。 */
    public String prefsName() {
        return null;
    }

    /** kill-switch 文件名（xxx.off；外部 files 目录或 /data/local/tmp/bixby_xxx.off）。 */
    public String[] killFiles() {
        return new String[0];
    }

    /** 出厂默认是否打开。 */
    public boolean defaultOn() {
        return true;
    }

    /** 总开关（现查，不缓存）。 */
    public abstract boolean enabled(Context ctx);

    /** true = 总开关关闭时内核跳过本插件所有挂钩。迁移的老功能为 false（内部自己按子开关判）。 */
    public boolean kernelGated() {
        return true;
    }

    // ------------------------------------------------------------------ 生命周期

    public void onInit(Context ctx) {
    }

    public void onEnable(Context ctx) {
    }

    public void onDisable(Context ctx) {
    }

    /** 本挂钩上的优先级（小的先调）。 */
    public int priority(FeatureHook hook) {
        return 1000;
    }

    // ------------------------------------------------------------------ 分诊

    /** 本插件控制的分诊信号（{@link TriageSwitches} 常量），没有返回 null。 */
    public String triageSignal() {
        return null;
    }

    /** 分诊信号是否打开（默认跟总开关）。 */
    public boolean triageOn(Context ctx) {
        return enabled(ctx);
    }

    /** 本插件在 TriageDecider 里的规则 id（声明用：文档、状态、测试）。 */
    public String[] triageRules() {
        return new String[0];
    }

    /** L0 直达：命中返回调用，否则 null。 */
    public Invocation l0(Context ctx, String raw) {
        return null;
    }

    /** L0 命中时的日志行（不含 TAG）；null = 不打。 */
    public String l0Log(Invocation inv) {
        return null;
    }

    // ------------------------------------------------------------------ 执行

    /** 路由层本地接管：返回非 null 即交付这个调用（不查 / 不写缓存、不进 AgentLoop）。 */
    public Invocation route(Context ctx, String query, L2LLMCompiler l2) {
        return null;
    }

    /** 接管日志前缀（如「[ROUTE-SUM] 长内容摘要接管: 」），后面接签名和模式。 */
    public String routeLog() {
        return "[ROUTE-" + id().toUpperCase() + "] 插件接管: ";
    }

    /** 接管后是否先跑一次分诊（只为 [TRIAGE] 日志口径一致；规则判，不触网，5 秒内复用）。 */
    public boolean triageBeforeRouteLog(Invocation inv) {
        return false;
    }

    /** 这句话由本插件本地处理，永不进 AgentLoop。 */
    public boolean claims(Context ctx, String query) {
        return false;
    }

    /** 按签名接管派发；返回 true 表示已处理。 */
    public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        return false;
    }

    /** 自带（不进注册表）的能力描述。 */
    public Capability describe(String sign) {
        return null;
    }

    // ------------------------------------------------------------------ 面板

    /** 面板输入拦截；返回 true 表示这句话被本插件吃掉。 */
    public boolean interceptCommand(FloatingSheetService svc, String command) {
        return false;
    }

    /** 这句话里的「这个 / 这张 / 那条」不是屏幕指代，跳过识屏。 */
    public boolean bypassScreenRef(Context ctx, String command) {
        return false;
    }

    public void onReplyShown(FloatingSheetService svc, Invocation inv) {
    }

    public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
    }

    public void onExecFailed(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
    }

    public void onServiceCreate(FloatingSheetService svc) {
    }

    public void onServiceDestroy(FloatingSheetService svc) {
    }

    /** 新一次唤醒、识屏采集之前。 */
    public void onWakeEarly(FloatingSheetService svc) {
    }

    /** 新一次唤醒、识屏采集开始之后。 */
    public void onWake(FloatingSheetService svc) {
    }

    /**
     * 物理键手势（目前只有 {@code "long"}：按住满 800 ms，守护 v7 写 trigger「long seq dur」）。
     * 返回 true = 本插件处理了（第一个处理的胜出）。在后台线程调用。
     */
    public boolean onKeyGesture(Context ctx, String gesture) {
        return false;
    }

    /** 本插件此刻是否要接这个手势（决定 App 是否创建 bixby.gesture_v2 标记、让守护改写 long）。 */
    public boolean wantsKeyGesture(Context ctx, String gesture) {
        return false;
    }

    /** 任一能力执行完（成功失败都调；query 是用户原话或循环里的命令）。 */
    public void afterExec(Context ctx, String query, Capability cap, JSONObject args, ExecutionResult r) {
    }

    // ------------------------------------------------------------------ AgentLoop / 执行后端

    /** 往 AgentLoop 本轮候选工具里追加自己的工具（JSON schema）。 */
    public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) {
    }

    /** AgentLoop 里自己的工具描述。 */
    public Capability describeForAgent(Context ctx, String name) {
        return null;
    }

    /** AgentLoop 里自己的工具在 App 内执行；不是自己的返回 null。 */
    public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        return null;
    }

    /** 本插件的执行后端（按 executor 名认领能力）；没有返回 null。 */
    public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return null;
    }

    // ------------------------------------------------------------------ 灵动岛 / 控制台 / 调试

    /** 本插件往灵动岛上放的活动（给人看的说明，如「后台任务胶囊」「取件码」）。 */
    public String[] islandActivities() {
        return new String[0];
    }

    /**
     * 第 4 阶段（D-3a）：本插件通过 {@link FeatureKernel#island(String)} 往岛上推的活动种类（{@link IslandHost} 的 KIND_* 常量）。
     * 推了没声明的种类，内核照样转交（行为不变）但记一条 {@code [KERNEL]} 警告；{@code kernel status} 列出来。
     */
    public String[] islandActivityKinds() {
        return new String[0];
    }

    // ------------------------------------------------------------------ 第 4 阶段（D-3a）：识屏类挂钩

    /** AgentLoop 用户消息改写（返回改写后的内容；不改就原样返回）。 */
    public String agentPrompt(Context ctx, String query, String content) {
        return content;
    }

    // ---- ★ device-1102：「不懂先搜索」（诊断类）经内核挂钩接入，宿主（UnifiedRouter / AgentLoop）不直调功能类 ----

    /** 这句话本插件要求进 AgentLoop（例：诊断症状没命中已知案例 → 先搜索再动手）。按 CLAIMS 顺序问，任一 true 即进。 */
    public boolean wantsAgentLoop(Context ctx, String query, com.onesay.assistant.v2.router.L2LLMCompiler l2) {
        return false;
    }

    /** AgentLoop 开局附在用户消息后的一句系统提示（webOffered = 本轮挂了搜索工具）；null = 不加。按 AGENT_PROMPT 顺序拼接。 */
    public String agentStartHint(Context ctx, String query, boolean webOffered) {
        return null;
    }

    /** AgentLoop 模型想收工时的提醒（calledSigns = 本任务调过的工具签名）；null = 不提醒。宿主保证每任务最多提醒一次。 */
    public String agentFinishNudge(Context ctx, String query, boolean webOffered, java.util.List<String> calledSigns) {
        return null;
    }

    /** 本插件这次改写是否把<b>不可信内容</b>（屏幕文字等）注进了提示词（是 → AgentLoop 本任务从第一步起 tainted）。 */
    public boolean agentPromptTainted(String before, String after) {
        return false;
    }

    /** AgentLoop 工具调用过闸前的参数兜底；返回非 null = 本步不执行，返回值作为观测。 */
    public String agentPrecheck(Context ctx, String name, JSONObject args) {
        return null;
    }

    /** 面板输入的屏幕指代接管；返回 true 表示吃掉这句话。 */
    public boolean screenReference(FloatingSheetService svc, String command) {
        return false;
    }

    /** 用户切到文字输入。 */
    public void onTextMode(FloatingSheetService svc) {
    }

    /** 圈选区域出动作芯片（region 是 vision.screenref.ScreenContext）；返回出了几个芯片。 */
    public int regionChips(FloatingSheetService svc, Object region) {
        return 0;
    }

    /** 控制台卡片说明（没有返回 null）。 */
    public String consoleCard() {
        return null;
    }

    /** 往控制台追加「小节标题 + 卡片」（v1，兼容；R12 起画在「功能与权限 › 其他」里。迁移到 v2 的插件不再实现它）。 */
    public void addConsoleCard(ConsoleHost host, android.widget.LinearLayout root) {
    }

    /** [R12] true = 本插件用 v2 页面接口（{@link #addConsoleEntries}），控制台不再调 {@link #addConsoleCard}。 */
    public boolean consoleV2() {
        return false;
    }

    /**
     * [R12] 控制台 v2：往页面 page（{@link ConsoleHost#PAGE_FEATURES} 等，或本插件 openPage 打开的细项页 id）里加分组 / 行。
     * 不认识的 page 什么都不做。
     */
    public void addConsoleEntries(ConsoleHost host, String page, android.widget.LinearLayout root) {
    }

    /** [R12] 首页入口行 / 大卡用的一句状态（例如灵动岛「已开启 · 全屏时隐藏」）；null = 不提供。后台线程调用。 */
    public String consoleSummary(Context ctx) {
        return null;
    }

    /** [R12] 首页大卡：{数字, 副标题}；null = 不提供。后台线程调用。 */
    public String[] consoleTile(Context ctx) {
        return null;
    }

    /** 一行状态（调试 / 验收）。 */
    public String debugStatus(Context ctx) {
        return "enabled=" + enabled(ctx);
    }

    // ------------------------------------------------------------------ 依赖 / 调试入口（第 3 阶段）

    /**
     * 本插件依赖的其他插件 id（例如 S3 借 S1 / 灵动岛显示进度）。<b>只做声明</b>：依赖被关时内核在
     * {@code --es kernel status} 里给出 {@code deps-off=[…]} 提示，<b>不会</b>因此关掉本插件（不改行为）。
     */
    public String[] dependsOn() {
        return new String[0];
    }

    /**
     * debug 包调试入口：TestRunReceiver 收到 {@code --es <debugExtra> <cmd>} 时交给本插件的 XxxTestDriver
     * （驱动类在 test 包、只进 debug 包；映射表见 test/PluginDebugDrivers）。没有返回 null。
     */
    public String debugExtra() {
        return null;
    }
}
