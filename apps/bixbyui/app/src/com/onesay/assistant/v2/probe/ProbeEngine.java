package com.onesay.assistant.v2.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.MinedCapabilities;
import com.onesay.assistant.v2.executor.ShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读设备探针（ProbeEngine）新增。
 *
 * <h3>它解决什么问题</h3>
 * <p>用户实测痛点：「假设一个新的软件它没有接口数据那怎么办？」
 * 现有接口库（手写 100 条 + 离线挖掘资产）覆盖不了"新装的第三方应用"——
 * LLM 在 AgentLoop 里面对候选工具表只能"选或不选"，没有任何"查一查"的手段，
 * 于是一句"做不到"就成了它的默认结局。
 *
 * <p>本引擎给循环装上"眼睛"——<b>三个纯只读探针</b>：
 * <ul>
 *   <li>{@link #SIGN_APPS}：查设备装了什么（PackageManager 实时索引，零 shell）</li>
 *   <li>{@link #SIGN_APP_INTENTS}：挖某个应用暴露的可调用界面接口
 *       （{@code pm dump} 的 Activity Resolver Table，离线挖掘脚本的<b>运行时版</b>），
 *       挖到即注册为新能力 + 落盘 {@code files/mined_runtime.json} 复用</li>
 *   <li>{@link #SIGN_SHELL_READ}：白名单只读 shell 兜底（{@link #checkReadOnlyCommand}）</li>
 * </ul>
 *
 * <h3>安全模型（三道闸门，缺一不可）</h3>
 * <ol>
 *   <li><b>前缀白名单</b>：命令必须以只读前缀开头（pm list / pm dump / dumpsys package 等）；</li>
 *   <li><b>特殊字符禁止</b>：{@code ; | & > < ` $ ( ) { } " ' \} 与换行一律拒绝 —— 禁止拼接/重定向；</li>
 *   <li><b>危险词禁止</b>：am / reboot / install / setprop 等二次拦截（纵深防御）。</li>
 * </ol>
 * 探针能力本身 sideEffect=NONE、risk=0，且不进普通召回（见 CapabilityRegistry#registerProbe）——
 * 只在 AgentLoop 探索族里被显式注入。
 *
 * <h3>为什么"结构化"而不是"给 LLM 一把自由 shell"</h3>
 * <p>自由 shell 的白名单组合会爆炸（分号/管道一绕就穿）、LLM 拼 dumpsys 语法不稳定、
 * 几百 KB 原始 dump 全量进 prompt 又脏又贵。结构化探针 = 命令由我们生成、
 * LLM 只给参数、输出由我们精修成 JSON。
 */
public final class ProbeEngine {

    private static final String TAG = "ProbeEngine";

    public static final String SIGN_APPS = "probe.apps";
    public static final String SIGN_APP_INTENTS = "probe.app_intents";
    public static final String SIGN_SHELL_READ = "probe.shell_read";

    /** 运行时挖掘资产的落盘文件（与 assets/mined_intents.json 同构，下次启动由 MinedCapabilities 加载）。 */
    public static final String RUNTIME_FILE = "mined_runtime.json";

    private static final int MAX_APPS = 20;
    private static final int MAX_INTENTS = 12;
    private static final int MAX_SHELL_OUTPUT = 2500;

    private static final Pattern PKG_RE = Pattern.compile("^[A-Za-z0-9_.]+$");

    private ProbeEngine() {}

    // =====================================================================
    // 结果封装
    // =====================================================================

    public static final class ProbeResult {
        public final boolean ok;
        /** ok 时的结构化输出（直接作为 AgentLoop 的 observation 回灌给 LLM） */
        public final String output;
        public final String error;

        private ProbeResult(boolean ok, String output, String error) {
            this.ok = ok;
            this.output = output;
            this.error = error;
        }

        public static ProbeResult ok(String output) { return new ProbeResult(true, output, null); }
        public static ProbeResult fail(String error) { return new ProbeResult(false, null, error); }
    }

    /** 统一入口（UnifiedExecutor 的 "probe" executorType 分发到这里）。 */
    public static ProbeResult execute(String sign, JSONObject args, Context ctx) {
        if (args == null) args = new JSONObject();
        if (SIGN_APPS.equals(sign)) {
            return apps(ctx, args.optString("keyword", ""));
        }
        if (SIGN_APP_INTENTS.equals(sign)) {
            return appIntents(ctx, args.optString("package", ""), args.optString("goal", ""));
        }
        if (SIGN_SHELL_READ.equals(sign)) {
            return shellRead(ctx, args.optString("command", ""));
        }
        return ProbeResult.fail("未知探针: " + sign);
    }

    // =====================================================================
    // 探针 ①：probe.apps —— 查设备装了什么
    // =====================================================================

    public static ProbeResult apps(Context ctx, String keyword) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);

            String kw = normalize(keyword);
            JSONArray arr = new JSONArray();
            int count = 0;
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String label = String.valueOf(ri.loadLabel(pm));
                if (kw.length() > 0) {
                    if (!normalize(label).contains(kw) && !normalize(pkg).contains(kw)) continue;
                }
                JSONObject o = new JSONObject();
                o.put("label", label);
                o.put("package", pkg);
                o.put("launchable", true);
                arr.put(o);
                if (++count >= MAX_APPS) break;
            }

            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("count", arr.length());
            root.put("apps", arr);
            if (arr.length() == 0) {
                root.put("note", kw.length() > 0
                        ? "没有找到匹配「" + keyword + "」的已安装应用；它可能没装（可去应用商店搜索），或换个关键词再查"
                        : "设备上没有可启动的应用");
            } else if (arr.length() >= MAX_APPS) {
                root.put("note", "结果已截断（最多 " + MAX_APPS + " 条），可用更具体的关键词缩小范围");
            }
            return ProbeResult.ok(root.toString());
        } catch (Throwable t) {
            Log.w(TAG, "probe.apps 失败: " + t.getMessage());
            return ProbeResult.fail("查询应用列表失败: " + t.getMessage());
        }
    }

    // =====================================================================
    // 探针 ②：probe.app_intents —— 挖接口（离线挖掘脚本的运行时版）
    // =====================================================================

    public static ProbeResult appIntents(Context ctx, String pkgRaw) {
        return appIntents(ctx, pkgRaw, "");
    }

    /** 深挖接口最多注册多少个（深链/私有 action/带标签界面/provider，按目标排序后取前 N）。 */
    private static final int MAX_DEEP = 10;

    /**
     * @param goal 用户目标原话（用于给深挖到的接口排序，可为空）
     */
    public static ProbeResult appIntents(Context ctx, String pkgRaw, String goal) {
        String pkg = pkgRaw == null ? "" : pkgRaw.trim();
        if (!PKG_RE.matcher(pkg).matches()) {
            return ProbeResult.fail("包名格式不合法（只允许字母/数字/点/下划线）: " + pkgRaw
                    + "；可先用 probe.apps 查准确包名");
        }

        // ---- 1) 只读命令拿 dump 文本 ----
        ShellExecutor shell = new ShellExecutor(ctx);
        ShellExecutor.ShellResponse resp = shell.execute("pm dump " + pkg);
        if (resp.isTimeout) return ProbeResult.fail("系统命令通道超时");
        if (resp.isDown) return ProbeResult.fail("系统命令通道不可用（root 守护未启动）");
        String text = resp.output == null ? "" : resp.output;
        if (text.contains("Unable to find package") || !text.contains("Package [" + pkg + "]")) {
            return ProbeResult.fail("设备上没有找到包「" + pkg + "」——先用 probe.apps 确认是否安装、拿准包名");
        }

        try {
            // ---- 2) 解析 Activity Resolver Table ----
            //  ★ 三步精修（QQ 实测驱动，缺一步名单就废）：
            //   ① 组件去重：同一组件按不同 MIME 分组重复出现（QQ 实测 1 组件重复 12 次）；
            //   ② 优先级排序：表里 MIME 组在前、Non-Data 组（MAIN/LAUNCHER 主入口）在后 ——
            //      不排序的话名额被分享类组件吃光，主入口根本进不了名单；
            //   ③ 按动作去重：同一动作只留第一个组件 —— 工具名 = <软件>.<动作>，
            //      同动作多组件会撞名（LLM 也区分不了），去重后每个工具名唯一、语义清晰。
            List<IntentEntry> all = parseDump(text);
            List<IntentEntry> deduped = new ArrayList<IntentEntry>();
            Set<String> seen = new HashSet<String>();
            for (IntentEntry e : all) {
                if (!e.isValuable()) continue;
                if (!seen.add(e.comp + "#" + e.primaryActionKey())) continue;
                deduped.add(e);
            }
            java.util.Collections.sort(deduped, new java.util.Comparator<IntentEntry>() {
                @Override
                public int compare(IntentEntry a, IntentEntry b) {
                    return a.priority() - b.priority();
                }
            });
            List<IntentEntry> kept = new ArrayList<IntentEntry>();
            Set<String> actionSeen = new HashSet<String>();
            for (IntentEntry e : deduped) {
                if (!actionSeen.add(e.primaryActionKey())) continue;
                kept.add(e);
                if (kept.size() >= MAX_INTENTS) break;
            }

            // ---- 3) 构造能力：挖到即注册 + 落盘 ----
            CapabilityRegistry reg = CapabilityRegistry.getInstance();
            String software = shortSoftware(pkg);
            JSONArray interfaces = new JSONArray();
            List<String> registeredNow = new ArrayList<String>();
            List<JSONObject> toPersist = new ArrayList<JSONObject>();

            for (IntentEntry e : kept) {
                String action = e.primaryActionKey();
                String sign = software + "." + action;
                String comp = compToAm(e.comp);

                boolean already = reg.describe(sign) != null;
                if (!already) {
                    JSONObject it = new JSONObject();
                    it.put("sign", sign);
                    it.put("component", comp);
                    it.put("software", software);
                    it.put("summary", action);
                    JSONArray flags = new JSONArray();
                    if (e.hasCat("android.intent.category.LAUNCHER")) flags.put("LAUNCHER");
                    if (e.hasCat("android.intent.category.VOICE")) flags.put("VOICE");
                    it.put("flags", flags);
                    Capability cap = MinedCapabilities.buildFromIntent(it);
                    if (cap != null) {
                        reg.register(cap);
                        registeredNow.add(sign);
                        toPersist.add(it);
                    }
                }

                JSONObject io = new JSONObject();
                io.put("component", comp);
                io.put("action", action);
                io.put("tool", sign);
                io.put("registered", !already);
                interfaces.put(io);
            }
            // ---- 3b) 深链 / 私有 action / 带中文标签的导出界面 / provider ----
            //   旧逻辑只认标准 action，QQ 邮箱这类应用真正的入口（深链、私有 action）全被丢掉。
            //   真机证实 qqmail:// 只声明 scheme（qqmail://inbox 与 /compose 落同一主界面），
            //   这类 host-less 链接只记作「拉起主界面」；写信走 mailto:（LaunchComposeMail），读信走 IMAP 或 app.launch+读屏。
            JSONArray providers = new JSONArray();
            int deepFound = 0;
            try {
                java.util.Map<String, String> labels = new java.util.HashMap<String, String>();
                Set<String> exportedActs = new HashSet<String>();
                Set<String> exportedProvs = new HashSet<String>();
                loadRuntimeInfo(ctx, pkg, labels, exportedActs, exportedProvs);
                List<AppInterfaceMiner.Iface> mined =
                        AppInterfaceMiner.mine(text, pkg, labels, exportedActs, exportedProvs);
                deepFound = mined.size();
                List<AppInterfaceMiner.Iface> ranked = AppInterfaceMiner.rank(mined, goal);
                Set<String> taken = new HashSet<String>();
                for (int i = 0; i < interfaces.length(); i++) {
                    taken.add(interfaces.getJSONObject(i).optString("tool"));
                }
                for (AppInterfaceMiner.Iface e : ranked) {
                    if (e.kind == AppInterfaceMiner.Kind.PROVIDER) {
                        JSONObject po = new JSONObject();
                        po.put("authority", e.authority);
                        po.put("exported", e.exported);
                        providers.put(po);
                    }
                }
                for (AppInterfaceMiner.Iface e : AppInterfaceMiner.pickForTools(ranked, MAX_DEEP)) {
                    String sign = AppInterfaceMiner.signFor(software, e, taken);
                    boolean already = reg.describe(sign) != null;
                    if (!already) {
                        JSONObject it = e.toIntentJson(sign, software);
                        Capability cap = it != null ? MinedCapabilities.buildFromIntent(it) : null;
                        if (cap == null) continue;
                        reg.register(cap);
                        registeredNow.add(sign);
                        toPersist.add(it);
                    }
                    JSONObject io = new JSONObject();
                    io.put("kind", e.kind.name());
                    io.put("action", e.describe());
                    if (e.label != null) io.put("label", e.label);
                    if (e.uri != null) io.put("uri", e.uri);
                    io.put("score", e.score);
                    io.put("tool", sign);
                    io.put("registered", !already);
                    if (e.kind == AppInterfaceMiner.Kind.PROVIDER) io.put("needs_confirm", true);
                    interfaces.put(io);
                }
            } catch (Throwable t) {
                Log.w(TAG, "probe.app_intents 深挖失败（不影响标准接口）: " + t.getMessage());
            }
            persist(toPersist, ctx);

            // ---- 4) 输出（给 LLM 的观察） ----
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("package", pkg);
            root.put("found", interfaces.length());
            root.put("interfaces", interfaces);
            if (deepFound > 0) root.put("deep_found", deepFound);
            if (providers.length() > 0) root.put("providers", providers);
            if (goal != null && goal.length() > 0) root.put("ranked_for", goal);
            if (interfaces.length() > 0) {
                root.put("note", "已挖到 " + interfaces.length() + " 个可调用界面" + (registeredNow.size() > 0
                        ? "，其中新注册 " + registeredNow.size() + " 个（可直接以工具名调用）"
                        : "（均已在库中）"));
            } else {
                // 连 LAUNCHER 都没有？给主入口兜底信息
                String launch = null;
                try {
                    Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (li != null && li.getComponent() != null) {
                        launch = li.getComponent().flattenToString();
                    }
                } catch (Throwable ignore) {}
                root.put("note", "该应用没有暴露可被外部调用的标准界面接口"
                        + (launch != null ? "；它的主入口是 " + launch + "（可用 app.launch 打开）" : ""));
            }
            return ProbeResult.ok(root.toString());
        } catch (Throwable t) {
            Log.w(TAG, "probe.app_intents 失败: " + t.getMessage());
            return ProbeResult.fail("解析失败: " + t.getMessage());
        }
    }

    // =====================================================================
    // 探针 ③：probe.shell_read —— 白名单只读 shell 兜底
    // =====================================================================

    public static ProbeResult shellRead(Context ctx, String commandRaw) {
        String cmd = commandRaw == null ? "" : commandRaw.trim();
        String reject = checkReadOnlyCommand(cmd);
        if (reject != null) {
            return ProbeResult.fail("只读白名单拒绝（" + reject + "）。只允许 pm/dumpsys/getprop 类只读查询");
        }
        ShellExecutor shell = new ShellExecutor(ctx);
        ShellExecutor.ShellResponse resp = shell.execute(cmd);
        if (resp.isTimeout) return ProbeResult.fail("命令执行超时");
        if (resp.isDown) return ProbeResult.fail("系统命令通道不可用（root 守护未启动）");
        String out = resp.output == null ? "" : resp.output;
        if (out.length() > MAX_SHELL_OUTPUT) {
            out = out.substring(0, MAX_SHELL_OUTPUT) + "\n…(输出已截断)";
        }
        try {
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("rc", resp.rc);
            root.put("output", out);
            return ProbeResult.ok(root.toString());
        } catch (Throwable t) {
            return ProbeResult.fail("结果封装失败: " + t.getMessage());
        }
    }

    // =====================================================================
    // 只读白名单闸门（纯函数，自测直接断言）
    // =====================================================================

    /** 允许的只读命令前缀（全部经人工审计：不可改变设备状态）。 */
    private static final String[] READ_ONLY_PREFIXES = {
            "pm list packages", "pm path ", "pm dump ",
            "cmd package resolve-activity ", "cmd package query-activities ", "cmd package list ",
            "dumpsys package ", "dumpsys window ",
            "dumpsys activity activities", "dumpsys activity top",
            "dumpsys display", "dumpsys meminfo", "dumpsys cpuinfo",
            "getprop",
    };

    /** 禁止的 shell 特殊字符（拼接/重定向/命令替换——全部会破坏"只读"语义）。 */
    private static final Pattern BANNED_CHARS = Pattern.compile("[;&|><`$(){}\"'\\\\\\r\\n]");

    /**
     * 危险词二次拦截（纵深防御）。注意只收"绝不可能出现在合法只读命令里"的词——
     * 像 settings/update/clear 这种会误伤包名（com.android.settings），不收。
     */
    private static final String[] BANNED_WORDS = {
            " am ", " reboot", " install", " uninstall", " setprop",
            " input ", " force-stop", " kill", " wipe", " svc ", " sh ", " su ",
    };

    /**
     * 校验一条命令是否属于"只读白名单"。
     *
     * @return {@code null} = 放行；否则返回拒绝原因（含具体触发的规则）
     */
    public static String checkReadOnlyCommand(String command) {
        if (command == null || command.trim().length() == 0) return "空命令";
        String cmd = command.trim();
        if (cmd.length() > 200) return "命令过长（>200 字符）";
        if (BANNED_CHARS.matcher(cmd).find()) return "含 shell 特殊字符（禁止拼接/重定向/命令替换）";

        boolean allowed = false;
        for (String p : READ_ONLY_PREFIXES) {
            if (cmd.startsWith(p)) { allowed = true; break; }
        }
        if (!allowed) return "不在只读白名单内";

        String lower = (" " + cmd.toLowerCase() + " ");
        for (String w : BANNED_WORDS) {
            if (lower.contains(w)) return "含禁止词「" + w.trim() + "」";
        }
        return null;
    }

    /**
     * 从 PackageManager 补 dump 里没有的信息 —— Activity 的中文标签、
     * 是否导出（且不要求权限）、provider 是否导出。失败静默（深挖只是增强）。
     */
    private static void loadRuntimeInfo(Context ctx, String pkg, java.util.Map<String, String> labels,
                                        Set<String> exportedActs, Set<String> exportedProvs) {
        try {
            PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    String comp = pkg + "/" + ai.name;
                    // 只认 Activity 自己的标签（没有自己的标签时 loadLabel 会回落成应用名，没有区分度）
                    if (ai.labelRes != 0 || ai.nonLocalizedLabel != null) {
                        CharSequence l = ai.loadLabel(pm);
                        if (l != null) labels.put(comp, l.toString());
                    }
                    if (ai.exported && ai.permission == null && ai.enabled) exportedActs.add(comp);
                }
            }
            if (pi.providers != null) {
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    if (!p.exported || p.authority == null) continue;
                    if (p.readPermission != null) continue;   // 要权限的不当可调接口
                    for (String a : p.authority.split(";")) exportedProvs.add(a.trim());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取应用组件信息失败（深挖降级为纯 dump）: " + t.getMessage());
        }
    }

    // =====================================================================
    // dump 解析（离线脚本 mine_intents.py 的 Java 移植，纯函数）
    // =====================================================================

    /** 从 Activity Resolver Table 抽到的一条 intent 条目。 */
    public static final class IntentEntry {
        public final String comp;
        public final List<String> actions;
        public final List<String> cats;

        IntentEntry(String comp, List<String> actions, List<String> cats) {
            this.comp = comp;
            this.actions = actions;
            this.cats = cats;
        }

        public boolean hasCat(String cat) {
            return cats != null && cats.contains(cat);
        }

        /** 是否值得登记为接口（只认：标准 action 去噪音 / LAUNCHER / VOICE / CAR_MODE）。 */
        public boolean isValuable() {
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.startsWith("android.intent.action.")
                            && !NOISE_ACTION.matcher(a).find()) {
                        return true;
                    }
                }
            }
            return hasCat("android.intent.category.LAUNCHER")
                    || hasCat("android.intent.category.VOICE")
                    || hasCat("android.intent.category.CAR_MODE");
        }

        /** 主语义键：LAUNCHER→main / VOICE→voice / 否则第一个标准 action 的短键。 */
        public String primaryActionKey() {
            if (hasCat("android.intent.category.LAUNCHER")) return "main";
            if (hasCat("android.intent.category.VOICE")) return "voice";
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.startsWith("android.intent.action.")) {
                        return sanitize(a.substring("android.intent.action.".length()));
                    }
                }
                if (!actions.isEmpty() && actions.get(0) != null) {
                    String a = actions.get(0);
                    int dot = a.lastIndexOf('.');
                    return sanitize(dot >= 0 ? a.substring(dot + 1) : a);
                }
            }
            return "ui";
        }

        /** 名单优先级：主入口(0) > 语音口(1) > MAIN(2) > 其他标准动作(3)。 */
        public int priority() {
            if (hasCat("android.intent.category.LAUNCHER")) return 0;
            if (hasCat("android.intent.category.VOICE")) return 1;
            if (actions != null) {
                for (String a : actions) {
                    if ("android.intent.action.MAIN".equals(a)) return 2;
                }
            }
            return 3;
        }
    }

    /** 内部噪音 action（与离线脚本保持同一份定义）。 */
    private static final Pattern NOISE_ACTION = Pattern.compile(
            "(WIDGET|NOTIFICATION|_INTERNAL|ALARM_NOTIFICATION|"
                    + "\\.provider\\.|\\.SERVICE|BOOT_COMPLETED|PACKAGE_|"
                    + "MY_PACKAGE|LOCALE_CHANGED|CONFIGURATION|"
                    + "TIME_SET|TIMEZONE_CHANGED|SCREEN_ON|SCREEN_OFF|USER_PRESENT|"
                    + "ACCOUNT_|SYNC_|CONNECTIVITY|BATTERY|POWER_|DEVICE_|"
                    + "MEDIA_|SEARCH_|INSTALL_|DELETE_|APP_|"
                    + "LOCK_|UNLOCK|SIM_|PHONE_STATE|NETWORK|"
                    + "\\.weather\\.|\\.update\\.|\\.sync\\.|\\.push\\.|\\.analytics)");

    private static final Pattern P_COMP = Pattern.compile("^\\s+[0-9a-f]+\\s+(\\S+)\\s+filter\\s+[0-9a-f]+");
    private static final Pattern P_ACTION = Pattern.compile("^\\s+Action:\\s+\"([^\"]+)\"");
    private static final Pattern P_CAT = Pattern.compile("^\\s+Category:\\s+\"([^\"]+)\"");
    private static final Pattern P_END = Pattern.compile("^\\s*(Service|Provider|Receiver) Resolver Table");

    /** 解析 dump 文本里的 Activity Resolver Table，返回全部条目（未过滤）。 */
    public static List<IntentEntry> parseDump(String text) {
        List<IntentEntry> out = new ArrayList<IntentEntry>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("Activity Resolver Table")) { start = i; break; }
        }
        if (start < 0) return out;

        String curComp = null;
        List<String> curA = null;
        List<String> curC = null;
        for (int i = start + 1; i < lines.length; i++) {
            String ln = lines[i];
            if (P_END.matcher(ln).find()) break;

            Matcher mc = P_COMP.matcher(ln);
            if (mc.find()) {
                if (curComp != null) out.add(new IntentEntry(curComp, curA, curC));
                curComp = mc.group(1);
                curA = new ArrayList<String>();
                curC = new ArrayList<String>();
                continue;
            }
            Matcher ma = P_ACTION.matcher(ln);
            if (ma.find() && curComp != null) { curA.add(ma.group(1)); continue; }
            Matcher mk = P_CAT.matcher(ln);
            if (mk.find() && curComp != null) { curC.add(mk.group(1)); }
        }
        if (curComp != null) out.add(new IntentEntry(curComp, curA, curC));
        return out;
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    /** `pkg/.Cls` / `pkg/Cls` → `pkg/full.Cls`（am start -n 可用形式）。 */
    public static String compToAm(String comp) {
        if (comp == null || comp.indexOf('/') < 0) return comp;
        int slash = comp.indexOf('/');
        String pkg = comp.substring(0, slash);
        String cls = comp.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }

    /** 包名 → 能力 sign 的软件段（取末段，如 com.tencent.mobileqq → mobileqq）。 */
    static String shortSoftware(String pkg) {
        if (pkg == null) return "app";
        int dot = pkg.lastIndexOf('.');
        String s = dot >= 0 ? pkg.substring(dot + 1) : pkg;
        s = sanitize(s);
        return s.length() == 0 ? "app" : s;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 20; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。？！、]", "").toLowerCase();
    }

    // =====================================================================
    // 落盘（files/mined_runtime.json，与 assets 资产同构 → 下次启动复用）
    // =====================================================================

    private static synchronized void persist(List<JSONObject> newEntries, Context ctx) {
        if (newEntries == null || newEntries.isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), RUNTIME_FILE);
            JSONArray arr = new JSONArray();
            // ★ 去重键必须是 sign（接口名）而不是 component ——
            //   同一组件可有多个动作、是多个接口（QQ 实测：JumpActivity 贡献 view/send_multiple/sendto 三个），
            //   按 component 去重会把后注册的动作整条丢掉。
            Set<String> seenSigns = new HashSet<String>();
            String old = readFile(f);
            if (old != null && old.length() > 0) {
                try {
                    JSONObject root = new JSONObject(old);
                    JSONArray oa = root.optJSONArray("intents");
                    if (oa != null) {
                        for (int i = 0; i < oa.length(); i++) {
                            JSONObject it = oa.optJSONObject(i);
                            if (it == null) continue;
                            seenSigns.add(it.optString("sign"));
                            arr.put(it);
                        }
                    }
                } catch (Throwable ignore) {}
            }
            int added = 0;
            for (JSONObject n : newEntries) {
                String sign = n.optString("sign");
                if (sign.length() == 0 || seenSigns.contains(sign)) continue;
                seenSigns.add(sign);
                arr.put(n);
                added++;
            }
            if (added == 0) return;
            JSONObject root = new JSONObject();
            root.put("intents", arr);
            writeFile(f, root.toString());
            Log.i(TAG, "运行时挖掘接口已落盘: 新增 " + added + " 条 → " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "运行时挖掘落盘失败（不影响本次会话）: " + t.getMessage());
        }
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    private static void writeFile(File f, String content) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "写文件失败: " + t.getMessage());
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
        }
    }
}
