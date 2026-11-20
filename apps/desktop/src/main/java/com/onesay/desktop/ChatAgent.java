package com.onesay.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [chat-agent-1118] 电脑端「对话」页的任务执行循环。
 *
 * <p>以前「对话」只把话交给大模型聊天（没有任何工具，系统提示还写着「你不能直接操作电脑」），
 * 所以「帮我下载 X 并装好」这类目标只会得到一份手动步骤。现在：模型带着工具（执行 PowerShell 命令、打开网址、写文件）一轮轮做，
 * 每一步都走和手机远程指令同一条安全链（{@link Agent#runLocal}：安全插件 → 两轴矩阵 / 白名单 → 电脑上的确认框 → 一次性凭证），
 * 只读查询直接做，改动类照旧弹确认框（只认本机鼠标 / 键盘点击）。
 *
 * <p>[approvals-1118] 任务是最小单位，没有步数上限：做到模型给出答复为止；用户在确认框里拒绝 / 没人确认（停下，不绕路重试）；
 * 同一步原样重复 {@link #MAX_REPEAT} 次、或连续 {@link #MAX_FAILS} 步失败（没有进展）；用户点了停止 / 说「停」；
 * 兜底的总时长上限（设置里可改，默认 {@link #DEFAULT_TIMEOUT_MIN} 分钟）。每一步带一句给人看的说明（工具参数 description），写进任务卡。
 * 任务进行中用户发来的话进 {@link #inbox}，下一步之前交给模型（不会丢）。
 */
public final class ChatAgent {
    /** 确认框 / 日志里显示的发起方。 */
    public static final String FROM = "本机对话";
    public static final int MAX_REPEAT = 3;
    /** 连续这么多步失败（不含被拒）就当没有进展，停下。 */
    public static final int MAX_FAILS = 5;
    public static final int DEFAULT_TIMEOUT_MIN = 120;
    /**
     * [preview-1120 真机综合测试 2] 同类步骤空转防护（Chat 打开；直接 new 的 ChatAgent 默认关，保持旧行为）：
     * 真机上模型换着查询参数调同一个接口 18 次、每次结果一模一样，原样重复检测（{@link #MAX_REPEAT}）抓不到，只能等批准超时。
     * 现在按「同一类步骤」（同一个工具 + 同一个网址主机路径 / 同一个命令开头）计：同类步骤第 {@link #STALE_HINT} 次拿到完全相同的结果时，
     * 在结果里提醒模型别再换参数；到 {@link #STALE_STOP} 次停下。同类步骤总数到 {@link #FAMILY_HINT} 提醒、{@link #FAMILY_STOP} 停下。
     */
    public volatile boolean progressGuard = false;
    public static final int STALE_HINT = 3, STALE_STOP = 5, FAMILY_HINT = 8, FAMILY_STOP = 16;
    /** [preview-1120 真机复验] 联网读网页不分站点的总数：每次换一个站 / 搜索词，同类计数永远到不了上限（查演唱会 16 步、查马拉松 20 步都没收尾）。
     *  到 {@link #WEB_HINT} 提醒用已有结果作答，到 {@link #WEB_STOP} 停下并收尾（说查到了什么、缺什么）。 */
    public static final int WEB_HINT = 8, WEB_STOP = 12;
    /**
     * [preview-1120 真机综合测试 2] 被拒绝 / 空转 / 连续失败停下时，再让模型（不带工具）收个尾：说清已经查到什么、还缺什么、用户自己怎么做。
     * 以前停在一句固定话上，前面查到的东西和手动步骤都没给用户（真机：Steam 诊断到一半被拒，什么结论都没有）。Chat 打开；默认关。
     */
    public volatile boolean wrapUpOnStop = false;
    /** 总时长上限（毫秒）；Chat 按设置填。 */
    public volatile long timeoutMs = DEFAULT_TIMEOUT_MIN * 60_000L;
    /** 任务进行中用户发来的话（下一步之前交给模型）。 */
    public final java.util.concurrent.ConcurrentLinkedQueue<String> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 交给模型后回调（界面把「已收到，下一步处理」改成已处理）。 */
    public volatile java.util.function.Consumer<String> delivered = x -> { };
    /** 当前权限预设（变了就在下一步之前告诉模型）。 */
    public volatile java.util.function.Supplier<ChatPolicy.Preset> preset = () -> ChatPolicy.Preset.DEFAULT;
    /** 上次告诉模型的预设（系统提示里写的那个）。 */
    volatile ChatPolicy.Preset toldPreset;
    /** 时钟（测试换）。 */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** 给模型的输出上限（字符）：和本机执行收的上限一样，界面上折叠但模型看全（[approvals-1118] 6000 → 16000）。 */
    public static final int OUTPUT_TO_MODEL = 16000;

    /** 模型：给消息和工具，回一轮。 */
    public interface Brain {
        Llm.Turn next(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws Exception;
    }

    /** 执行一个电脑能力（App 接到 {@link Agent#runLocal}）。 */
    public interface Exec {
        Actions.Result run(String cmd, Map<String, Object> args) throws Exception;
    }

    /** 把一步进展写进对话。 */
    public interface Steps {
        void add(String text);

        /**
         * [approvals-1118] 结构化的一步（任务卡用）：phase = start / done / note；desc 给人看的一句；tool、detail（命令 / 网址 / 路径）；
         * done 时 ok、summary（一行结论）、output（原始输出，界面折叠在「详情」里）。默认退回成一行文字。
         */
        default void step(Map<String, Object> ev) {
            if ("done".equals(ev.get("phase"))) add(String.valueOf(ev.getOrDefault("text", "")));
            else if ("note".equals(ev.get("phase"))) add(String.valueOf(ev.getOrDefault("desc", "")));
        }
    }

    /** 停止的说法（任务进行中发来就停）。 */
    static boolean stopWord(String s) {
        return s != null && s.trim().matches("(?s)[\\s,，。.!！]*(停|停止|停下|别做了|不要做了|取消|stop)[\\s,，。.!！]*");
    }

    public static final class Outcome {
        public final String text;
        /** done / denied / timeout / loop / max_steps / stopped / error。 */
        public final String status;
        public final int steps;

        Outcome(String text, String status, int steps) {
            this.text = text;
            this.status = status;
            this.steps = steps;
        }
    }

    public final AtomicBoolean cancel = new AtomicBoolean();
    /** [fix-1120 晚] 第二次点停止：立刻结束，不收尾（收尾那一轮模型调用也放弃）。 */
    public final AtomicBoolean hardStop = new AtomicBoolean();

    /** 点一次「停止」：先中断、再收尾；已经在停了再点一次 = 立刻结束。 */
    public void requestStop() {
        if (!cancel.compareAndSet(false, true)) hardStop.set(true);
    }
    /** 模型暂时失败（{@link Llm.Transient}：超时 / 429 / 5xx / 空回复）时最多重试几次；每次等 backoffMs × 次数。 */
    static final int RETRIES = 2;
    volatile long backoffMs = 1500;

    // ------------------------------------------------------------------ 工具定义

    static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new TreeMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    static Map<String, Object> fn(String name, String desc, Map<String, Object> props, List<String> required) {
        Map<String, Object> params = new TreeMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", new ArrayList<Object>(required));
        Map<String, Object> f = new TreeMap<>();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", params);
        Map<String, Object> t = new TreeMap<>();
        t.put("type", "function");
        t.put("function", f);
        return t;
    }

    static final String DESC = "这一步在做什么：给用户看的一句简短中文（10–20 字，如「下载 Mindustry 安装包」「查 MuMu 装在哪」）";

    /** [preview-1120] 额外的工具（MCP 连接器）；App 接上 {@link McpHub#toolDefs}。 */
    public static volatile java.util.function.Supplier<List<Map<String, Object>>> extraTools = List::of;
    /** [preview-1120] MCP 工具给人看的名字（任务卡 / 确认框）。 */
    public static volatile java.util.function.Function<String, String> mcpName = fn -> "MCP 工具（" + fn + "）";

    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<String, Object> rc = new TreeMap<>();
        rc.put("description", prop("string", DESC));
        rc.put("command", prop("string", "一条 Windows PowerShell 5.1 命令（非交互，不能弹窗等输入）。可以用分号串多步。"));
        rc.put("timeout_sec", prop("integer", "最长等多少秒，默认 60，最多 900（下载大文件时调大）。"));
        l.add(fn("run_command", "在这台 Windows 电脑上执行 PowerShell 命令并返回输出（以当前用户权限，不提权）。只读查询、在桌面 / 下载 / 文档 / 工作区里新建文件直接执行；其余按权限预设可能要用户批准一次。下载文件不要用它（Invoke-WebRequest / curl / wget），用 download。", rc, List.of("description", "command")));
        Map<String, Object> ou = new TreeMap<>();
        ou.put("description", prop("string", DESC));
        ou.put("url", prop("string", "要打开的 http(s) 网址"));
        l.add(fn("open_url", "用默认浏览器打开网址（给用户看页面时用；下载文件请用 run_command）。", ou, List.of("description", "url")));
        Map<String, Object> oa = new TreeMap<>();
        oa.put("description", prop("string", DESC));
        oa.put("name", prop("string", "要打开的东西：程序 / 网站的名字（如「哔哩哔哩」「微信」「MuMu模拟器」），或安装目录里 exe 的完整路径"));
        oa.put("url", prop("string", "可选：它的网页地址（http/https），没有本地应用或用户要网页时打开"));
        oa.put("prefer", prop("string", "可选：web = 用户明确说了要网页 / 网站；app = 明确说了要客户端；不确定就不填（OneSay 会按用户习惯或问用户）"));
        l.add(fn("open_target", "打开一个程序或网站：先找这台电脑上装没装本地应用；本地应用和网页都有时按用户习惯，没有习惯就在任务卡上让用户选。打开本身不用批准。", oa, List.of("description", "name")));
        Map<String, Object> wf = new TreeMap<>();
        wf.put("description", prop("string", DESC));
        wf.put("path", prop("string", "文件路径：相对路径写在 OneSay 工作区里；工作区以外要给完整路径（会先请用户确认）。"));
        wf.put("text", prop("string", "文件内容（UTF-8 文本，最多 256 KB）"));
        l.add(fn("write_file", "写一个文本文件。", wf, List.of("description", "path", "text")));
        l.add(ChatReminders.toolDef()); // [fix-1120 晚] 电脑本机的提醒 / 日程
        // [phone-tools-1118] 手机：走已配对的手机联动通道（不是 adb）
        Map<String, Object> ps = new TreeMap<>();
        ps.put("description", prop("string", DESC));
        l.add(fn("phone_status", "查已配对手机的状态：在不在线、电量、屏幕、铃声模式，以及手机上开了哪些「电脑可以让手机做的事」。", ps, List.of("description")));
        Map<String, Object> pa = new TreeMap<>();
        pa.put("description", prop("string", DESC));
        pa.put("name", prop("string", "手机上 App 的名字（桌面上显示的名字，如「微信」「设置」）"));
        pa.put("package", prop("string", "可选：包名（如 com.android.settings），知道就填"));
        l.add(fn("phone_open_app", "在已配对的手机上打开一个 App（支付 / 银行类手机上会先问用户）。", pa, List.of("description", "name")));
        Map<String, Object> po = new TreeMap<>();
        po.put("description", prop("string", DESC));
        po.put("goal", prop("string", "要手机做的事，一句完整的中文目标（如「打开设置查一下系统版本」「在微信里打开文件传输助手」），最多 500 字"));
        l.add(fn("phone_operate", "把一个目标交给手机上的 OneSay，由手机自己看屏幕、规划、点按去做（和用户在手机上说这句话一样）。打电话、发短信、付款、给别人发消息手机上会弹卡让用户确认。只负责交过去，结果要再用 phone_status 或截图确认。", po, List.of("description", "goal")));
        // [overnight-1119] 下载文件（任务卡上有进度条、可取消）
        Map<String, Object> dl = new TreeMap<>();
        dl.put("description", prop("string", DESC));
        dl.put("url", prop("string", "http(s) 下载地址"));
        dl.put("path", prop("string", "存到哪：完整路径（如 C:\\Users\\me\\Downloads\\x.apk），或工作区里的相对路径"));
        dl.put("timeout_sec", prop("integer", "最长等多少秒，默认 1800"));
        l.add(fn("download", "下载一个文件（安装包、压缩包等）。任务卡上实时显示百分比、已下 / 总大小、速度、剩余时间，用户能取消；下完给出大小和 SHA-256。下载文件用它，不要用 Invoke-WebRequest。", dl, List.of("description", "url", "path")));
        // [overnight-1119] 文件发到手机、手机上的快捷指令
        Map<String, Object> pf = new TreeMap<>();
        pf.put("description", prop("string", DESC));
        pf.put("path", prop("string", "电脑上文件的路径（工作区里的相对路径或完整路径），最大 1 GB"));
        l.add(fn("phone_send_file", "把电脑上的一个文件发到已配对的手机（手机存在「下载 / OneSay」里），分块传、带校验，任务卡上有进度。", pf, List.of("description", "path")));
        Map<String, Object> pl = new TreeMap<>();
        pl.put("description", prop("string", DESC));
        l.add(fn("phone_shortcuts", "列出手机上 OneSay 的快捷指令（名字、几步）。", pl, List.of("description")));
        Map<String, Object> pr = new TreeMap<>();
        pr.put("description", prop("string", DESC));
        pr.put("name", prop("string", "快捷指令的名字（先用 phone_shortcuts 查）"));
        l.add(fn("phone_run_shortcut", "在手机上运行一条 OneSay 快捷指令（手机上照常走确认卡）。", pr, List.of("description", "name")));
        // [devtools-1118] 看屏幕 / 给用户看文件
        Map<String, Object> sc = new TreeMap<>();
        sc.put("description", prop("string", DESC));
        sc.put("target", prop("string", "截哪块屏幕：pc = 这台电脑（默认）；android = 用 adb 连着的安卓设备（只有一台时）；或 adb 序列号，如 MuMu 模拟器 127.0.0.1:16384"));
        sc.put("question", prop("string", "想从屏幕上确认什么（如「有没有弹出 root 授权框」「命令输出里有没有 uid=0」）。要拿截图当证据时必填：截完会核对画面是不是这个目标，不是就明说"));
        l.add(fn("screenshot", "截屏并看图：图存进工作区 shots/，在任务卡里直接显示给用户；配了看图模型就交给它描述画面，没配就用 Windows 自带 OCR 读出文字（结果会标明是哪种）。", sc, List.of("description")));
        Map<String, Object> au = new TreeMap<>();
        au.put("description", prop("string", DESC));
        au.put("action", prop("string", "dump = 列出界面上的文字 / 按钮 / 复选框和位置；tap_text = 点有这段文字的按钮；check_text = 勾上这段文字旁边的复选框（已勾上就不动）"));
        au.put("text", prop("string", "tap_text / check_text 要找的文字（如「允许」「永久记住选择」），先完全相同再包含"));
        au.put("then_tap", prop("string", "可选，只用于 check_text：勾好后紧接着点的按钮文字（如先勾「永久记住选择」再点「允许」，倒计时弹窗来得及）"));
        au.put("device", prop("string", "可选：adb 序列号（MuMu 是 127.0.0.1:16384）；只连着一台时不用填"));
        l.add(fn("android_ui", "用 adb 读安卓设备（模拟器 / 开了调试的手机）当前界面的结构（uiautomator，按 UTF-8 读，中文不乱码），按文字点按钮、勾复选框（点在控件 bounds 的中心，不用猜坐标）。付款 / 转账 / 删除 / 卸载这类按钮不替用户点。", au, List.of("description", "action")));
        Map<String, Object> sf = new TreeMap<>();
        sf.put("description", prop("string", DESC));
        sf.put("path", prop("string", "文件路径（工作区里的相对路径，或完整路径）：图片会显示在任务卡里，文本文件可以在卡里点开看"));
        // [overnight-1119] 目标有歧义 / 搜索结果只是「最可能」时，有副作用的动作之前先让用户选
        Map<String, Object> ac = new TreeMap<>();
        ac.put("description", prop("string", DESC));
        ac.put("question", prop("string", "问用户的一句（如「你说的 Mika 是哪个？」）"));
        Map<String, Object> opt = prop("array", "2–3 个候选，每个写清楚：title（如仓库名 owner/name）、detail（星数、一句简介、最新版本号和日期、安装包大小，查到什么写什么）");
        Map<String, Object> item = new TreeMap<>();
        item.put("type", "object");
        Map<String, Object> ip = new TreeMap<>();
        ip.put("title", prop("string", "候选名"));
        ip.put("detail", prop("string", "星数 / 简介 / 最新版本 / 大小"));
        item.put("properties", ip);
        item.put("required", new ArrayList<Object>(List.of("title")));
        opt.put("items", item);
        ac.put("options", opt);
        l.add(fn("ask_choice", "在任务卡上弹选择卡让用户挑一个（自动附「都不是，我来补充」）。目标有歧义（同名的仓库 / 软件不止一个）、或者你只能判断「最可能是」时，下载、安装、付款这类有副作用的步骤之前必须先用它；只读查询（搜索、查版本）可以先做。", ac, List.of("description", "question", "options")));
        l.add(fn("show_file", "把你生成或找到的图片 / 文本文件显示在任务卡里给用户看（不要用 open_url 打开 file: 地址）。", sf, List.of("description", "path")));
        // [preview-1120] MCP 连接器：用户在「设置 › MCP 连接器」里加的服务器的工具（名字 mcp__服务器__工具）
        try { List<Map<String, Object>> ex = extraTools.get(); if (ex != null) l.addAll(ex); } catch (RuntimeException ignored) { }
        return l;
    }

    /** 已注册的工具名。 */
    static Set<String> toolNames() {
        Set<String> n = new java.util.LinkedHashSet<>();
        for (Map<String, Object> t : tools()) n.add(String.valueOf(((Map<?, ?>) t.get("function")).get("name")));
        n.add("open_app");
        return n;
    }

    /** [overnight-1119 第 7 条] 命令里用 adb input tap / swipe 点固定坐标（没有先读界面就是猜）。 */
    static final java.util.regex.Pattern COORD = java.util.regex.Pattern.compile("\\binput\\s+(?:tap|swipe|touchscreen\\s+tap)\\s+\\d{1,5}\\s+\\d{1,5}");

    static boolean guessesCoords(String cmd, Map<String, Object> args) {
        if (!"sys_exec".equals(cmd) || args == null) return false;
        return COORD.matcher(String.valueOf(args.getOrDefault("command", ""))).find();
    }

    /** 模型工具名 → 电脑能力名 + 参数。认不出返回 null。 */
    static Object[] map(Llm.ToolCall c) {
        Map<String, Object> a;
        try {
            a = Json.obj(c.arguments);
        } catch (RuntimeException e) {
            a = null;
        }
        if (a == null) return null;
        Map<String, Object> out = new TreeMap<>();
        switch (c.name) {
            case "run_command": {
                Object cmd = a.get("command");
                if (cmd == null || String.valueOf(cmd).isBlank()) return null;
                // [preview-1120] 整条命令就是下载一个文件 → 改走下载工具（任务卡有进度条、能取消）；判定照旧按等价的 Invoke-WebRequest
                DownloadRewrite.Dl dl = DownloadRewrite.parse(String.valueOf(cmd));
                if (dl != null) return new Object[] {"net_download", DownloadRewrite.args(dl)};
                out.put("command", String.valueOf(cmd));
                Object t = a.get("timeout_sec");
                if (t instanceof Number) out.put("timeout_sec", Math.max(5L, Math.min(900L, ((Number) t).longValue())));
                return new Object[] {"sys_exec", out};
            }
            case "open_url": {
                Object u = a.get("url");
                if (u == null || String.valueOf(u).isBlank()) return null;
                out.put("url", String.valueOf(u));
                return new Object[] {"open_url", out};
            }
            case "open_app":
            case "open_target": {
                Object n = a.get("name");
                if (n == null || String.valueOf(n).isBlank()) return null;
                String v = String.valueOf(n).trim();
                if (v.matches("(?i)[a-z]:\\\\.*")) {
                    out.put("path", v);
                    return new Object[] {"open_app", out};
                }
                out.put("name", v);
                if (a.get("url") != null && !String.valueOf(a.get("url")).isBlank()) out.put("url", String.valueOf(a.get("url")).trim());
                if (a.get("prefer") != null) out.put("prefer", String.valueOf(a.get("prefer")).trim().toLowerCase(java.util.Locale.ROOT));
                return new Object[] {"open_target", out};
            }
            case "write_file": {
                Object p = a.get("path");
                if (p == null || String.valueOf(p).isBlank()) return null;
                out.put("path", String.valueOf(p));
                out.put("text", a.get("text") == null ? "" : String.valueOf(a.get("text")));
                return new Object[] {"file_write", out};
            }
            case "phone_status":
                return new Object[] {"phone_status", out};
            case "reminder": { // [fix-1120 晚]
                Map<String, Object> ra = ChatReminders.mapArgs(a);
                return ra == null ? null : new Object[] {"reminder", ra};
            }
            case "screenshot": {
                String tg = a.get("target") == null ? "" : String.valueOf(a.get("target")).trim();
                out.put("target", tg.isEmpty() ? "pc" : tg);
                if (a.get("question") != null && !String.valueOf(a.get("question")).isBlank()) out.put("question", clip(String.valueOf(a.get("question")).trim(), 300));
                else if (a.get("description") != null && !String.valueOf(a.get("description")).isBlank()) out.put("goal", clip(String.valueOf(a.get("description")).trim(), 120)); // [overnight-1119] 没写要看什么：按这一步的说明核对
                return new Object[] {"screen_look", out};
            }
            case "android_ui": {
                String act = a.get("action") == null ? "" : String.valueOf(a.get("action")).trim().toLowerCase(java.util.Locale.ROOT);
                if (!act.matches("dump|tap_text|check_text")) return null;
                out.put("action", act);
                for (String k : new String[] {"text", "then_tap", "device"}) if (a.get(k) != null && !String.valueOf(a.get(k)).isBlank()) out.put(k, clip(String.valueOf(a.get(k)).trim(), 100));
                if (!act.equals("dump") && !out.containsKey("text")) return null;
                return new Object[] {"android_ui", out};
            }
            case "ask_choice": {
                Object q = a.get("question");
                Object os = a.get("options");
                if (q == null || String.valueOf(q).isBlank() || !(os instanceof List)) return null;
                List<Object> opts = new ArrayList<>();
                for (Object o : (List<?>) os) {
                    if (opts.size() >= 3) break;
                    Map<String, Object> x = new TreeMap<>();
                    if (o instanceof Map) {
                        Object t = ((Map<?, ?>) o).get("title");
                        Object d = ((Map<?, ?>) o).get("detail");
                        if (t == null || String.valueOf(t).isBlank()) continue;
                        x.put("title", clip(String.valueOf(t).trim(), 80));
                        x.put("detail", d == null ? "" : clip(String.valueOf(d).trim(), 240));
                    } else if (o != null && !String.valueOf(o).isBlank()) {
                        x.put("title", clip(String.valueOf(o).trim(), 80));
                        x.put("detail", "");
                    } else continue;
                    opts.add(x);
                }
                if (opts.isEmpty()) return null;
                out.put("question", clip(String.valueOf(q).trim(), 200));
                out.put("options", opts);
                return new Object[] {"ask_choice", out};
            }
            case "show_file": {
                Object p = a.get("path");
                if (p == null || String.valueOf(p).isBlank()) return null;
                out.put("path", String.valueOf(p).trim());
                return new Object[] {"show_file", out};
            }
            case "phone_open_app": {
                String n = a.get("name") == null ? "" : String.valueOf(a.get("name")).trim();
                String pk = a.get("package") == null ? "" : String.valueOf(a.get("package")).trim();
                if (n.isEmpty() && pk.isEmpty()) return null;
                if (!n.isEmpty()) out.put("name", n);
                if (!pk.isEmpty()) out.put("package", pk);
                return new Object[] {"phone_open_app", out};
            }
            case "download": {
                Object u = a.get("url"), p = a.get("path");
                if (u == null || p == null || String.valueOf(u).isBlank() || String.valueOf(p).isBlank()) return null;
                out.put("url", String.valueOf(u).trim());
                out.put("path", String.valueOf(p).trim());
                Object t = a.get("timeout_sec");
                if (t instanceof Number) out.put("timeout_sec", Math.max(30L, Math.min(4 * 3600L, ((Number) t).longValue())));
                return new Object[] {"net_download", out};
            }
            case "phone_send_file": {
                Object p = a.get("path");
                if (p == null || String.valueOf(p).isBlank()) return null;
                out.put("path", String.valueOf(p).trim());
                return new Object[] {"phone_send_file", out};
            }
            case "phone_shortcuts":
                return new Object[] {"phone_shortcuts", out};
            case "phone_run_shortcut": {
                Object n = a.get("name");
                if (n == null || String.valueOf(n).isBlank()) return null;
                out.put("name", clip(String.valueOf(n).trim(), 80));
                return new Object[] {"phone_run_shortcut", out};
            }
            case "phone_operate": {
                String g = a.get("goal") == null ? "" : String.valueOf(a.get("goal")).trim();
                if (g.isEmpty()) return null;
                out.put("goal", g);
                return new Object[] {"phone_operate", out};
            }
            default:
                if (c.name != null && c.name.startsWith("mcp__")) { // [preview-1120] MCP 连接器的工具：原样转给服务器（审批照旧）
                    out.put("fn", c.name);
                    a.remove("description");
                    out.put("arguments", a);
                    return new Object[] {"mcp_call", out};
                }
                return null;
        }
    }

    // ------------------------------------------------------------------ 提示词

    /** 模型把活推回给用户（「我无法直接操作电脑」「需要你手动」）：第一轮这样说就确定性地打回一次。 */
    static final Pattern PUNT = Pattern.compile("(无法|不能|没法|没有办法)(直接)?(操作|控制|访问|在)(你的|您的|这台)?(电脑|计算机|系统)|需要你(自己)?手动|请你手动|你可以按(下面|以下|这个)(的)?(顺序|步骤)");

    /** 一条回复里列了几个要用户回答的问题（编号行 / 项目符号行里带问号或「多少 / 哪 / 几」；
     *  不在列表里、但带问号的一段话也算一个——真机上模型常先用一段话问一个，再列两个，以前只数出 2 个）。 */
    static int questionCount(String s) {
        if (s == null) return 0;
        int n = 0;
        for (String line : s.split("\\R")) {
            String l = line.trim();
            if (!l.matches("^(\\d+[.、)）]|[-•·*]|[（(]\\d+[)）]).*")) {
                if (l.matches(".*[？?].*")) n++;
                continue;
            }
            if (l.matches(".*([？?]|多少|哪(个|里|儿|天|类|些)?|几(天|个|号|点|人)|什么时候|是否|要不要|还是).*")) n++;
        }
        return n;
    }

    /** 第一轮就只问不做：没调用任何工具，回复里列了 3 个及以上的问题。 */
    static boolean overAsks(String s) { return questionCount(s) >= 3; }

    static final String ASK_NUDGE = "（OneSay 内部提示）你一步还没查，就一次问了用户好几个问题。先做能做的："
            + "能公开查到的（活动 / 展会 / 考试的日期地点、签证政策、开放时间、交通方式）先用工具联网查官方来源；"
            + "用户没说的偏好按最常见的情况假设，并写明假设，先给出一版可用的答案；最后最多问 1–2 个真正会改变结果的问题。"
            + "用户给的日期已经过去、活动查不到或地名对不上时，先指出来。";

    static boolean punts(String s) {
        return s != null && PUNT.matcher(s).find();
    }

    /** 当前权限 / 审批策略写给模型的一句（从电脑档位来）。 */
    public static String policyLine(LinkPolicy.Mode mode) {
        LinkPolicy.Mode m = mode == null ? LinkPolicy.Mode.DEFAULT : mode;
        String base = "当前权限档：" + m.sandbox.zh + "，审批：" + m.approval.zh + "。";
        if (m.sandbox == LinkPolicy.Sandbox.READ_ONLY) return base + "这一档只能查询，改动类操作会被拒绝；需要改动时如实告诉用户可以在 OneSay 设置里调整权限档。";
        if (m.approval == LinkPolicy.Approval.NEVER) return base + "需要批准的操作会被直接拒绝、不会弹确认，不要反复申请；被拒时如实告诉用户。";
        return base + "要用户点头的步骤，OneSay 会在电脑上弹确认框交给用户决定，所以直接发起工具调用就行，不必先在回复里请示。"
                + "权限档只决定哪些步骤要用户点头，不是不做的理由：该做的步骤照常调用；真被挡下，看返回的原因再决定换个做法还是向用户说明。";
    }

    /** 工具清单（由注册的工具生成，提示词和实际能力不会对不上）。 */
    static String toolLines() {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> t : tools()) {
            Object f = t.get("function");
            if (!(f instanceof Map)) continue;
            b.append("- ").append(((Map<?, ?>) f).get("name")).append("：").append(((Map<?, ?>) f).get("description")).append('\n');
        }
        return b.toString();
    }

    public static String systemPrompt(String now, String workspace, String home, String memory) {
        return systemPrompt(now, workspace, home, memory, LinkPolicy.Mode.DEFAULT);
    }

    public static String systemPrompt(String now, String workspace, String home, String memory, LinkPolicy.Mode mode) {
        return systemPrompt(now, workspace, home, memory, policyLine(mode));
    }

    /** [approvals-1118] 本机对话按权限预设写提示。 */
    public static String systemPrompt(String now, String workspace, String home, String memory, ChatPolicy.Preset preset) {
        return systemPrompt(now, workspace, home, memory, ChatPolicy.policyLine(preset));
    }

    static String systemPrompt(String now, String workspace, String home, String memory, String policy) {
        return "你是 OneSay，运行在用户这台 Windows 电脑上的助手（和用户手机上的 OneSay 共用记忆），能真正操作这台电脑。用简体中文回答，先给结论，简短直接。现在是 " + now + "。\n"
                + "可用工具（run_command 能做下载文件、安装 / 启动程序、查文件和设置、调用 adb 等命令行工具）：\n" + toolLines()
                + policy + "\n"
                + "每次调用工具都要填 description：用一句简短中文说这一步在做什么（给用户看的进度）。\n"
                + "用户给你一个目标时：自己规划，一步步调用工具把它做完，每步看结果再决定下一步；能由你完成的步骤不要推给用户手动做，也不要只给教程。"
                + "缺信息先自己用命令查（比如程序装在哪、版本号、文件在不在）；只有真正需要用户拍板的事（花钱、多个选择差别很大、要账号密码）才问。\n"
                // [preview-1120 真机综合测试 3] 出行 / 活动 / 政策类：先查后问、时效信息核验、日期对照今天
                + "问到会变的公开信息（活动 / 展会 / 比赛 / 考试的日期和地点、签证与入境政策、开放时间、票价、最新版本）时，先联网查官方或权威来源再答，答案里写明来源和查到的日期；查不到就明说「没查到 / 未核实」，不要凭印象给具体日期。"
                + "拿用户说的日期和现在的日期对照：已经过去、星期对不上、活动查不到或地名对不上，先指出来再往下做。"
                + "规划类请求缺偏好时，按常见情况假设并写明，先给一版能用的方案，最后最多问 1–2 个关键问题，不要一上来列一串问题。"
                + "你自己要看网页内容时用 run_command 的 Invoke-WebRequest（-UseBasicParsing）读出文字；open_url 只是在用户的浏览器里打开页面，你看不到，不要靠打开后截图来读网页。\n"
                // [fix-1120 晚] 推测的日期不能当事实写进计划
                + "日期没查到官方来源时：计划里写「日期待确认」，不要写一个具体的推测日期当安排的依据（可以说「往年一般在几月上 / 中旬」，并注明是往年情况）；"
                + "按它排的安排写成相对的（如「比赛日（待确认）前一天领物」），并给出核实入口（官网 / 官方公众号 / 官方 App 的名字或网址）。\n"
                // [fix-1120 晚] 电脑本机的提醒
                + "提醒 / 日程用 reminder 工具：提醒建在这台电脑上，到点由 OneSay 在电脑上弹通知，不需要手机，手机没连不是不建提醒的理由；不要用 schtasks / Register-ScheduledTask 建 Windows 计划任务来提醒。"
                + "用户说「提醒我」就直接建（时间按今天的日期算准，写进 at）；规划有明确时间的事（考试、出差、比赛、预约、体检、车次、航班）收尾时，主动建关键时间点的提醒（如前一晚、出发前），或在答复里一句话提议要不要建。"
                + "日期是推测的（没查到官方）时，date_verified 填 false：提醒会标「待确认」；也可以建成「去核实 XX 日期」的提醒。"
                + "把东西发到手机是另一件事（phone_* 工具）：手机没连就如实说「没发到手机」，但电脑上的提醒照样建，答复里两件事分开说。\n"
                + "安全：要批准的步骤 OneSay 会在任务卡上请用户点（用户可以一次批准整个任务）；"
                + "被拒绝或没人确认就停下说明，不要换个写法绕过。不要提权（runas / UAC），不要关闭或绕过杀毒软件、防火墙，不要改 OneSay 自己的设置，不要输出或保存密码和 Key。"
                + "只从官方或可信来源下载软件。\n"
                + "下载文件用 download 工具（任务卡上有进度条，用户能取消），不要用 Invoke-WebRequest / curl 下载大文件。"
                + "只读查询写成简单命令（Get-* / Test-Path，可以 | 接 Select-Object、Where-Object、Sort-Object，不要变量赋值）；写文件、下载用字面的完整路径（不要用变量拼路径），放在桌面 / 下载 / 文档 / 工作区里，这样能直接执行、少打扰用户。"
                + "用户要「打开某个程序 / 网站」（如打开 B 站、微信）用 open_target：先用本地应用、没有再用网页，用户说了网页 / 网站就填 prefer=web；打开某个具体网址（搜索结果、下载页）用 open_url。\n"
                + "环境：Windows PowerShell 5.1，非交互；单条命令默认最长 60 秒（run_command 的 timeout_sec 最多 900）；命令的工作目录是 OneSay 工作区 " + workspace
                + "；用户目录 " + home + "，桌面通常是 " + home + "\\Desktop。\n"
                + "手机：用户的手机通过 OneSay 手机联动和这台电脑配对，phone_status / phone_open_app / phone_operate 就是通过这条链路操作手机的。"
                + "不要说你没法控制手机：先看下面的「运行环境」里有没有连着的手机，没有就照那里的说法告诉用户怎么连上；phone_operate 被手机拒（没打开这一项）时告诉用户在手机上打开。"
                + "模拟器或开了 USB / 无线调试的手机也可以用 adb（run_command）。\n"
                + "看屏幕（这台电脑，或 adb 连着的模拟器 / 手机）用 screenshot：结果写明是看图模型的描述还是 OCR 读出的文字；OCR 只有文字，看不出图标、颜色和布局，拿不准就如实说。"
                + "安卓界面上的按钮 / 复选框（如 root 授权弹窗）用 android_ui：先 dump 看有哪些，再按文字 tap_text / check_text，不要硬编码 input tap 坐标；"
                + "要「记住选择」的弹窗先 check_text 勾上再点允许（then_tap）。点完再 dump 或截图确认。"
                + "不要用截图文件的大小、修改时间去猜屏幕上是什么。截图和你生成的文件会显示在任务卡里；要给用户看某个文件用 show_file，不要用 open_url 打开 file: 地址。\n"
                + "截图结果里写着「截到的不是目标画面」或「没法确认」时，不能说「这张就是…」，要先把目标画面调出来重截，或如实说没截到。\n"
                + "目标有歧义（同名的仓库 / 软件不止一个）或你只能判断「最可能是」时：可以先做只读查询（搜索、看版本），但下载、安装、付款之前必须用 ask_choice 列 2–3 个候选让用户选，用户选了再做。\n"
                + "给用户看的话里不要写工具名或能力名（如 phone_operate、caps、find_phone、screen_on），用中文说法；不要用 markdown 符号（** # 之类）。\n"
                + "任务结束时答复的第一行只写结果标记，按目标有没有真正达成来标：【完成】目标达成；【有警告】达成了但中间有报错 / 截图没核对上 / 有没确认的地方；【没做成】目标没达成；【需要你操作】被挡下（开关没开、要用户点、要用户补充信息）。第二行起用一两句话说结果和下一步。\n"
                + "用户说「继续」「接着做」时，接着最近一个没做完的任务：看「当前任务」的程序记录（做过哪些步骤、最近的输出）从最后一步往下做，不要把做过的步骤原样再做一遍。更早的任务只有摘要。做完后用一两句话汇报结果（做了什么、现在是什么状态）。"
                + (memory == null || memory.isBlank() ? "" : "\n\n关于用户的长期记忆（可能过时，以用户最新说法为准）：\n" + memory);
    }

    // ------------------------------------------------------------------ 循环

    public Outcome run(List<Map<String, Object>> context, Brain brain, Exec exec, Steps steps) {
        List<Map<String, Object>> msgs = new ArrayList<>(context);
        List<Map<String, Object>> tools = tools();
        int done = 0, fails = 0;
        boolean nudged = false, fakeNudged = false, askNudged = false;
        int toolTextNudges = 0;
        boolean hedged = false, choiceDone = false;
        int hedgeBlocks = 0;
        boolean uiRead = false;   // [overnight-1119 第 7 条] 这次任务读过安卓界面没有（android_ui）
        int coordBlocks = 0;
        int dlHints = 0; // [preview-1120] 命令里下载文件 → 提示改用下载工具的次数
        boolean schedHinted = false; // [fix-1120 晚] 建 Windows 计划任务 → 提示改用提醒工具（一次）
        String lastKey = "";
        int repeat = 0;
        Map<String, Integer> famCount = new HashMap<>(), sameOut = new HashMap<>();
        cancel.set(false);
        hardStop.set(false);
        long t0 = clock.getAsLong();
        boolean dateNudged = false; // [fix-1120 晚] 推测日期当事实写进计划：打回一次
        while (true) {
            // 任务进行中用户发来的话：下一步之前交给模型（「停」就停）
            for (String in; (in = inbox.poll()) != null; ) {
                try { delivered.accept(in); } catch (RuntimeException ignored) { }
                if (stopWord(in)) { cancel.set(true); continue; }
                msgs.add(Llm.msg("user", "（任务进行中用户发来）" + in + "\n（OneSay 内部提示：先看这句话：是补充 / 纠正就照着调整，是问题就简短回答后接着做，叫你停就停下汇报；是和当前任务无关的新任务，就先把当前任务收个尾并说明，然后再做新的。）"));
            }
            ChatPolicy.Preset now = preset.get();
            if (toldPreset != null && now != null && now != toldPreset) {
                msgs.add(Llm.msg("user", ChatPolicy.changedNote(toldPreset, now)));
                steps.step(Map.of("phase", "note", "desc", "权限预设改成了「" + now.zh + "」"));
            }
            if (now != null) toldPreset = now;
            if (cancel.get()) return stopped(msgs, brain, done);
            if (clock.getAsLong() - t0 > timeoutMs)
                return new Outcome("这个任务已经做了 " + ((clock.getAsLong() - t0) / 60000) + " 分钟，到了设置里的时长上限，先停下。说「继续」我接着做。", "time_limit", done);
            compactToolResults(msgs, KEEP_FULL_RESULTS, OLD_RESULT_CHARS);
            Llm.Turn t;
            try {
                t = wrapUpOnStop ? nextAbortable(brain, msgs, tools) : nextWithRetry(brain, msgs, tools);
                if (t == null) continue; // [fix-1120 晚] 等模型时点了停止：不等这一轮，回到循环开头收尾
            } catch (Exception e) {
                if (cancel.get()) continue;
                String m = e.getMessage() == null ? "大模型出错" : e.getMessage();
                return new Outcome(done > 0 ? m + "。已经做了 " + done + " 步，说「继续」接着做。" : m, "error", done);
            }
            if (t.calls.isEmpty()) {
                // [approvals-1118] DeepSeek 偶尔把工具调用写成正文里的 DSML 标记（</｜｜DSML｜｜ parameter> …）：能还原就当真的工具调用
                List<Llm.ToolCall> rec = recoverCalls(t.content);
                if (!rec.isEmpty()) t = asCalls(t.content, rec);
            }
            if (t.calls.isEmpty()) {
                String raw = t.content == null ? "" : t.content.trim();
                if (looksLikeToolText(raw) && toolTextNudges < 2) {
                    // 像工具调用的文字但还原不出来：不当答复收工，带一句更正再要一次
                    toolTextNudges++;
                    msgs.add(Llm.msg("assistant", stripToolText(raw).isEmpty() ? "（略）" : stripToolText(raw)));
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你上一条回复里有工具调用的标记 / 步骤文字，但没有形成真正的工具调用，所以什么都没执行。"
                            + "请用工具调用（function calling）重新发起要做的那一步；任务做完了就直接用中文说结果，不要写任何调用标记。"));
                    continue;
                }
                String text = stripFakeSteps(raw);
                if (!text.equals(raw) && !fakeNudged && done == 0) {
                    // [approvals-1118] 模型把「▶ 执行命令 … ✓ 完成」当文字写出来，其实一步没做：打回一次，叫它真的调工具（这段文字不进对话）
                    fakeNudged = true;
                    msgs.add(Llm.msg("assistant", text.isEmpty() ? "（略）" : text));
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你刚才在回复里写了执行步骤 / 结果的文字，但没有真正调用工具，那些步骤并没有执行。"
                            + "要做的步骤请直接调用工具；已经真实执行过的结果以工具返回为准。"));
                    continue;
                }
                if (progressGuard && !askNudged && done == 0 && overAsks(text)) {
                    // [preview-1120 真机综合测试 3] 一步没查就甩回一串问题（出行 / 活动类实测 9 条里 7 条这样）：打回一次，
                    // 先查能公开查到的（时效信息查官方来源）、按常见假设给一版，最后最多问 1–2 个真正影响结果的问题
                    askNudged = true;
                    msgs.add(t.message);
                    msgs.add(Llm.msg("user", ASK_NUDGE));
                    continue;
                }
                if (!nudged && done == 0 && punts(text)) {
                    // 第一轮就把活推给用户：打回一次（内部提示，不进对话）
                    nudged = true;
                    msgs.add(t.message);
                    msgs.add(Llm.msg("user", "（OneSay 内部提示）你能用工具直接在这台电脑上操作，不需要用户手动。请直接调用工具开始做；只有真正需要用户决定的事才问。"));
                    continue;
                }
                if (progressGuard && !dateNudged && guessedDateAsFact(text)) {
                    // [fix-1120 晚] 答复里承认日期没查到 / 是推测的，却把具体日期当安排依据、没标「待确认」：打回一次
                    dateNudged = true;
                    msgs.add(t.message);
                    msgs.add(Llm.msg("user", DATE_NUDGE));
                    continue;
                }
                if (!inbox.isEmpty()) { msgs.add(t.message); continue; } // 收工前用户又说了话：再给模型看一眼
                return new Outcome(text.isEmpty() ? "做完了。" : text, "done", done);
            }
            msgs.add(t.message);
            if (hedges(t.content)) hedged = true;
            for (Llm.ToolCall hc : t.calls) if (hedges(hc.arguments)) hedged = true;
            if (t.content != null && !t.content.isBlank()) steps.step(Map.of("phase", "note", "desc", clip(Humanize.text(Outcomes.stripTag(t.content.trim())), 300), "text", t.content.trim())); // [preview-1120 真机] 旁白不露【需要你操作】这类结果标记 // [overnight-1119] 旁白不露内部名
            for (int ci = 0; ci < t.calls.size(); ci++) {
                Llm.ToolCall c = t.calls.get(ci);
                if (cancel.get()) {
                    msgs.add(toolMsg(c.id, "{\"ok\":false,\"text\":\"用户点了停止\"}"));
                    continue;
                }
                Object[] m = map(c);
                if (m == null) {
                    msgs.add(toolMsg(c.id, badArgs(c)));
                    continue;
                }
                String cmd = (String) m[0];
                @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) m[1];
                String desc = stepDesc(c, cmd, args);
                String key = cmd + "|" + Json.stringify(args);
                repeat = key.equals(lastKey) ? repeat + 1 : 1;
                lastKey = key;
                if (repeat >= MAX_REPEAT) {
                    String what = describe(cmd, args);
                    msgs.add(toolMsg(c.id, "{\"ok\":false,\"text\":\"同一步重复太多次，没执行\"}"));
                    steps.step(stepEv("done", done + 1, desc, cmd, args, false, "同一步原样重复了 " + repeat + " 次，停下", "", "▶ " + what + "\n✗ 同一步已经原样重复 " + repeat + " 次，停下"));
                    skipRest(msgs, t.calls, ci);
                    return wrapUp(msgs, brain, new Outcome("同一步（" + desc + "）原样重复了 " + repeat + " 次还没进展，我先停下。你看一下情况，或者换个说法告诉我下一步怎么做。", "loop", done));
                }
                if (!choiceDone && hedged && sideEffect(cmd, args) && hedgeBlocks < 2) {
                    // [overnight-1119] 目标还不确定（说了「最可能 / 应该是」），下载 / 安装 / 付款前先让用户选：这一步不执行
                    hedgeBlocks++;
                    msgs.add(toolMsg(c.id, Json.stringify(Map.of("ok", false, "reason", "need_choice", "text",
                            "没执行：目标还不确定（你说了「最可能 / 应该是」这类话），下载 / 安装 / 付款这类有副作用的步骤之前先用 ask_choice 列出 2–3 个候选（名字、星数、简介、最新版本）让用户选。只读查询可以先做。"))));
                    steps.step(Map.of("phase", "note", "desc", "目标还不确定，先列出候选请你选，选好了再下载 / 安装"));
                    continue;
                }
                if (hedgeBlocks >= 2 && !choiceDone && hedged && sideEffect(cmd, args))
                    return new Outcome("要做的目标还不确定，我两次想直接下载 / 安装都被拦下了。请告诉我具体是哪个（比如仓库地址 owner/name），我再接着做。", Outcomes.NEEDS_USER, done);
                if (!uiRead && guessesCoords(cmd, args) && coordBlocks < 2) {
                    // [overnight-1119 第 7 条] 硬编码坐标防护：没读过界面就 adb input tap / swipe 某个坐标 = 猜，换了分辨率 / 弹窗位置就点错。先读界面、按文字点
                    coordBlocks++;
                    msgs.add(toolMsg(c.id, Json.stringify(Map.of("ok", false, "reason", "guess_coords", "text",
                            "没执行：这一步用 input tap / swipe 点固定坐标，但这次还没读过安卓界面，坐标是猜的。先用 android_ui 的 dump 看界面，再用 tap_text / check_text 按文字点；确实要坐标时用 dump 里控件 bounds 的中心。"))));
                    steps.step(Map.of("phase", "note", "desc", "没按猜的坐标点：先读安卓界面，再按文字点"));
                    continue;
                }
                if ("run_command".equals(c.name) && "net_download".equals(cmd))
                    steps.step(Map.of("phase", "note", "desc", "这条命令是在下载文件：改用下载工具，任务卡上显示进度，可以取消"));
                if ("sys_exec".equals(cmd) && !schedHinted && CREATES_SCHED_TASK.matcher(String.valueOf(args.get("command"))).find()) {
                    // [fix-1120 晚] 用 Windows 计划任务做提醒（真机上模型建过 5 个，清理时还得一个个删）：先提示改用提醒工具；确实要计划任务（开机运行程序等）再发一次照常走审批
                    schedHinted = true;
                    msgs.add(toolMsg(c.id, Json.stringify(Map.of("ok", false, "reason", "use_reminder", "text", SCHED_HINT))));
                    steps.step(Map.of("phase", "note", "desc", "提醒改用 OneSay 的提醒（不建 Windows 计划任务），这条命令先不执行"));
                    continue;
                }
                if ("sys_exec".equals(cmd) && dlHints < 2 && DownloadRewrite.mentions(String.valueOf(args.get("command")))) {
                    // [preview-1120] 命令里混着下载、认不全：先不执行，提示模型改用下载工具（两次后照原命令执行，免得卡死）
                    dlHints++;
                    msgs.add(toolMsg(c.id, Json.stringify(Map.of("ok", false, "reason", "use_download", "text", DownloadRewrite.hint()))));
                    // [preview-1120 真机] 卡上带出被拦下的原命令（以前看不到是哪条，没法判断该不该认）
                    steps.step(Map.of("phase", "note", "desc", "下载文件改用下载工具（任务卡上有进度条），这条命令先不执行：" + clip(LinkPolicy.oneLine(String.valueOf(args.get("command"))), 160)));
                    continue;
                }
                if ("android_ui".equals(cmd)) uiRead = true;
                steps.step(stepEv("start", done + 1, desc, cmd, args, true, "", "", ""));
                Actions.Result r;
                try {
                    r = exec.run(cmd, args);
                } catch (Exception e) {
                    r = Actions.Result.fail(cmd + " 出错：" + e.getClass().getSimpleName());
                }
                if (r == null) r = Actions.Result.fail("没有结果");
                // [preview-1120 真机] 命令错误流是 CLIXML：解成文字再给任务卡「详情」和模型（以前模型看到一堆 XML，还要它自己解）
                if (r.data != null && r.data.get("output") instanceof String && ((String) r.data.get("output")).contains("#< CLIXML"))
                    try { r.data.put("output", clixml((String) r.data.get("output"))); } catch (UnsupportedOperationException ignored) { }
                done++;
                String reason = r.data == null ? "" : String.valueOf(r.data.getOrDefault("reason", ""));
                Object out = r.data == null ? null : r.data.get("output");
                Map<String, Object> ev = stepEv("done", done, desc, cmd, args, r.ok, summary(r), out == null ? "" : String.valueOf(out), stepText(cmd, args, r));
                if (r.data != null) for (String k : new String[] {"image", "file", "verified"}) if (r.data.get(k) != null) ev.put(k, String.valueOf(r.data.get(k))); // [devtools-1118] 任务卡里显示
                if (r.ok && r.data != null && r.data.get("warn") != null && !String.valueOf(r.data.get("warn")).isBlank()) ev.put("warn", String.valueOf(r.data.get("warn"))); // [overnight-1119] 黄色「有警告」
                steps.step(ev);
                String rj = resultJson(r);
                String staleStop = null;
                if (progressGuard && !LinkPolicy.R_CONFIRM_DENIED.equals(reason) && !LinkPolicy.R_CONFIRM_TIMEOUT.equals(reason)) {
                    String fam = family(cmd, args);
                    int fn = famCount.merge(fam, 1, Integer::sum);
                    int sn = sameOut.merge(fam + "#" + (r.ok ? "ok" : "fail") + "#" + (out == null ? "" : String.valueOf(out).trim()).hashCode(), 1, Integer::sum);
                    int wn = fam.contains("|url:") ? famCount.merge("#web", 1, Integer::sum) : 0;
                    if (sn >= STALE_STOP || fn >= FAMILY_STOP)
                        staleStop = sn >= STALE_STOP ? "同一类步骤（" + desc + "）已经 " + sn + " 次拿到完全相同的结果" : "同一类步骤（" + desc + "）已经做了 " + fn + " 次";
                    else if (wn >= WEB_STOP)
                        staleStop = "已经联网查了 " + wn + " 次网页";
                    else if (wn == WEB_HINT && sn < STALE_HINT && fn != FAMILY_HINT)
                        rj = withNote(rj, "OneSay 提示：已经联网查了 " + wn + " 次网页。别再换站点 / 搜索词了：用已经查到的内容给用户答复，查不到的如实说缺什么、去哪个官方渠道看。");
                    else if (sn >= STALE_HINT || fn == FAMILY_HINT)
                        rj = withNote(rj, sn >= STALE_HINT
                                ? "OneSay 提示：这一类步骤已经 " + sn + " 次返回完全相同的结果，再换参数也不会有新信息。不要再试同类变体：换一个完全不同的来源（比如官方网页、搜索），或者用已有结果收尾；拿不到就如实告诉用户缺什么。"
                                : "OneSay 提示：同一类步骤已经做了 " + fn + " 次。先想清楚还缺什么；没有新思路就用已有结果收尾，如实告诉用户。");
                }
                msgs.add(toolMsg(c.id, rj));
                if (cancel.get()) continue; // [fix-1120 晚] 这一步执行中点了停止：后面的调用都回「用户点了停止」，回到循环开头收尾
                if (staleStop != null) {
                    skipRest(msgs, t.calls, ci);
                    steps.step(Map.of("phase", "note", "desc", staleStop + "，没有新进展，先停下"));
                    return wrapUp(msgs, brain, new Outcome(staleStop + "，没有新进展，我先停下。", "loop", done));
                }
                if ("ask_choice".equals(cmd)) {
                    if (r.ok) { choiceDone = true; hedged = false; }
                    else if ("choice_other".equals(reason))
                        return new Outcome("你说候选里都不是。告诉我具体是哪个（名字、作者或网址都行），我接着做。", Outcomes.NEEDS_USER, done);
                    else if ("choice_timeout".equals(reason))
                        return new Outcome("选择卡没人选（10 分钟到了），我先停在这里。选好了说「继续」，或者直接告诉我是哪个。", Outcomes.NEEDS_USER, done);
                }
                if (LinkPolicy.R_CONFIRM_DENIED.equals(reason)) {
                    skipRest(msgs, t.calls, ci);
                    return wrapUp(msgs, brain, new Outcome("你在确认框里拒绝了这一步（" + desc + "），我停在这里，没有换别的办法去做。要换个做法或继续，直接告诉我。", "denied", done));
                }
                if (LinkPolicy.R_CONFIRM_TIMEOUT.equals(reason))
                    return new Outcome("批准没人点（60 秒到了，或窗口被切走），这一步没执行：" + desc + "。说「继续」我会重新请求，到时在任务卡上点「允许」。", "timeout", done);
                fails = r.ok ? 0 : fails + 1;
                if (fails >= MAX_FAILS) {
                    skipRest(msgs, t.calls, ci);
                    return wrapUp(msgs, brain, new Outcome("连续 " + fails + " 步都失败了（最后一步：" + desc + "：" + summary(r) + "），看起来没有进展，我先停下。你看一下情况，或者告诉我换个思路。", "no_progress", done));
                }
            }
        }
    }

    // ------------------------------------------------------------------ [preview-1120 真机综合测试 2] 空转防护 / 停下时收尾

    /** 同一类步骤：同一个工具 + 第一个网址的主机和路径（不看查询参数）；没有网址就看命令的第一个词。 */
    static String family(String cmd, Map<String, Object> a) {
        String body = a == null ? "" : String.valueOf(a.getOrDefault("command", a.getOrDefault("url", Json.stringify(a))));
        String low = body.toLowerCase(java.util.Locale.ROOT);
        Matcher u = FAMILY_URL.matcher(low);
        if (u.find()) return cmd + "|url:" + u.group(1);
        String t = low.replaceFirst("^\\s*(\\$progresspreference\\s*=\\s*'[^']*'\\s*;\\s*)?(\\[[^\\]]+\\]::[^;]*;\\s*)?", "").trim();
        int sp = 0;
        while (sp < t.length() && !Character.isWhitespace(t.charAt(sp)) && t.charAt(sp) != ';' && t.charAt(sp) != '|') sp++;
        return cmd + "|" + t.substring(0, sp);
    }

    static final Pattern FAMILY_URL = Pattern.compile("https?://([^\\s'\"?#;|)]+)");

    static String withNote(String json, String note) {
        String j = json == null ? "{}" : json.trim();
        if (!j.endsWith("}")) return j;
        return j.substring(0, j.length() - 1) + (j.length() > 2 ? "," : "") + "\"onesay_note\":" + Json.stringify(note) + "}";
    }

    /** 同一轮里还没轮到的工具调用补上结果（接口要求每个调用都有回应），标明没执行。 */
    static void skipRest(List<Map<String, Object>> msgs, List<Llm.ToolCall> calls, int i) {
        for (int k = i + 1; k < calls.size(); k++) msgs.add(toolMsg(calls.get(k).id, "{\"ok\":false,\"text\":\"没执行：任务已经停下\"}"));
    }

    // ------------------------------------------------------------------ [fix-1120 晚] 用户点「停止」后收尾

    /** 等模型这一轮：每 50 毫秒看一眼停止开关，点了停止就不等了（返回 null；那一轮的结果丢掉）。 */
    Llm.Turn nextAbortable(Brain brain, List<Map<String, Object>> msgs, List<Map<String, Object>> tools) throws Exception {
        List<Map<String, Object>> snap = new ArrayList<>(msgs);
        java.util.concurrent.FutureTask<Llm.Turn> ft = new java.util.concurrent.FutureTask<>(() -> nextWithRetry(brain, snap, tools));
        Thread th = new Thread(ft, "onesay-chat-llm");
        th.setDaemon(true);
        th.start();
        return await(ft, cancel);
    }

    static <T> T await(java.util.concurrent.FutureTask<T> ft, AtomicBoolean flag) throws Exception {
        while (true) {
            try {
                return ft.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (flag.get()) { ft.cancel(true); return null; }
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception) throw (Exception) c;
                throw new RuntimeException(c);
            }
        }
    }

    static final String STOP_PROMPT = "（OneSay 内部提示）用户点了「停止」，任务到这里停下，正在跑的那一步已经中断。不要再调用任何工具。用中文简短收尾（几行就够）："
            + "①已经确认了什么（只说上面工具结果里真实出现的，带上来源 / 数字；结果互相矛盾就直说）；②还缺什么、没弄清什么；③用户怎么继续（说「继续」我接着做，或者他自己可以怎么做）。"
            + "不要说做了没做的事，不要再问一堆问题，不要写结果标记。";

    /**
     * 用户点了停止：没开收尾（直接 new 的 ChatAgent）或第二次点停止 → 立刻结束；否则不带工具让模型说一轮
     * （已确认什么、还缺什么、怎么继续），查到的东西不丢。收尾时再点停止 = 不等了，直接结束。
     */
    Outcome stopped(List<Map<String, Object>> msgs, Brain brain, int done) {
        Outcome base = new Outcome("已停止。" + (done > 0 ? "做了 " + done + " 步，说「继续」可以接着做。" : ""), "stopped", done);
        if (!wrapUpOnStop || hardStop.get() || done == 0) return base;
        try {
            List<Map<String, Object>> m = new ArrayList<>(msgs);
            m.add(Llm.msg("user", STOP_PROMPT));
            java.util.concurrent.FutureTask<Llm.Turn> ft = new java.util.concurrent.FutureTask<>(() -> brain.next(m, List.of()));
            Thread th = new Thread(ft, "onesay-chat-stop-wrap");
            th.setDaemon(true);
            th.start();
            Llm.Turn t = await(ft, hardStop);
            String x = t == null || t.content == null ? "" : Outcomes.stripTag(stripFakeSteps(t.content.trim()));
            if (hardStop.get() || x.isEmpty() || looksLikeToolText(x)) return base;
            return new Outcome(base.text + "\n\n" + x, "stopped", done);
        } catch (Exception e) {
            return base;
        }
    }

    // ------------------------------------------------------------------ [fix-1120 晚] 推测的日期 / 计划任务

    /** 承认没查到 / 推测的说法。 */
    static final Pattern GUESS_ADMIT = Pattern.compile("没查到|未查到|查不到|没能查到|没能确认|未能确认|没核实|未核实|未经核实|没找到官方|未公布|还没公布|尚未公布|推测|估计|往年|按惯例|大概率|预计");
    /** 具体的月日（11月29日 / 11-29 / 11/29 / 11.29）。 */
    static final Pattern MONTH_DAY = Pattern.compile("(?<!\\d)(1[0-2]|0?[1-9])\\s*(月\\s*(3[01]|[12]\\d|0?[1-9])\\s*[日号]|[-/.](3[01]|[12]\\d|0[1-9])(?![\\d.]))");
    static final Pattern PENDING_WORD = Pattern.compile("待确认|待核实|待定|日期未定");

    /** 答复承认日期没查到 / 是推测的，又写了具体月日，却一处「待确认」都没标：推测的日期被当成了安排依据。 */
    static boolean guessedDateAsFact(String s) {
        if (s == null || s.isEmpty()) return false;
        return GUESS_ADMIT.matcher(s).find() && MONTH_DAY.matcher(s).find() && !PENDING_WORD.matcher(s).find();
    }

    static final String DATE_NUDGE = "（OneSay 内部提示）你的答复里有没查到官方来源 / 推测的日期，却写成了具体日期、按它排了安排。改一下再答："
            + "没核实的日期写「待确认」（可以说往年一般在几月上 / 中旬，注明是往年情况），按它排的安排改成相对写法（如「比赛日（待确认）前一天」），"
            + "并给出核实入口（官网 / 官方公众号 / 官方 App 的名字或网址）；用户自己给的、或查到官方来源的日期照常写，注明来源。要建提醒的话，推测日期的提醒 date_verified 填 false。";

    /** 命令在建 Windows 计划任务。 */
    static final Pattern CREATES_SCHED_TASK = Pattern.compile("(?i)schtasks(\\.exe)?\\s+/create|register-scheduledtask|new-scheduledtask");

    static final String SCHED_HINT = "没执行：要提醒用户的话用 reminder 工具（action=add），提醒存在 OneSay 里、到点在电脑上弹通知，不会在系统里留计划任务，用户在设置里也能看到和删除。"
            + "如果确实需要 Windows 计划任务（比如开机自动运行某个程序，不是提醒），再发一次这条命令，会照常请用户批准。";

    /** 停下时让模型不带工具再说一轮：查到了什么、还缺什么、用户自己怎么做。失败就照原来的话。 */
    Outcome wrapUp(List<Map<String, Object>> msgs, Brain brain, Outcome o) {
        if (!wrapUpOnStop || cancel.get()) return o;
        try {
            List<Map<String, Object>> m = new ArrayList<>(msgs);
            m.add(Llm.msg("user", "（OneSay 内部提示）任务到这里停下了：" + o.text + "\n不要再调用任何工具。用中文简短告诉用户："
                    + "①已经确认了什么（只说上面工具结果里真实出现的，不要推测成事实；结果互相矛盾就直说）；②还没弄清的；③用户自己可以怎么做（具体步骤）。"
                    + "不要说做了没做的事，不要再问一堆问题。"));
            Llm.Turn t = brain.next(m, List.of());
            String x = t == null || t.content == null ? "" : Outcomes.stripTag(stripFakeSteps(t.content.trim())); // 收尾里不露【需要你操作】这类结果标记（真机复验时出现）
            if (x.isEmpty() || looksLikeToolText(x)) return o;
            return new Outcome(o.text + "\n\n" + x, o.status, o.steps);
        } catch (Exception e) {
            return o;
        }
    }

    // ------------------------------------------------------------------ [overnight-1119] 目标不确定时先问

    static final Pattern HEDGE = Pattern.compile("最可能|最有可能|可能是|应该是|大概率|大概是|猜(测)?是|推测|估计是|似乎是|看起来是|多半是|不确定是|probably|most likely|likely the");

    static boolean hedges(String s) { return s != null && HEDGE.matcher(s).find(); }

    /** 有副作用、目标认错了代价大的步骤：下载、安装（含 adb install）、付款类。 */
    static boolean sideEffect(String cmd, Map<String, Object> a) {
        if ("sys_exec".equals(cmd)) {
            String c = a == null || a.get("command") == null ? "" : String.valueOf(a.get("command"));
            return PermStats.DOWNLOAD.matcher(c).find() || PermStats.INSTALL.matcher(c).find();
        }
        if ("phone_operate".equals(cmd)) {
            String g = a == null || a.get("goal") == null ? "" : String.valueOf(a.get("goal"));
            return g.matches("(?s).*(付款|支付|转账|下单|购买|充值|安装|下载).*");
        }
        return "media_get".equals(cmd) || "net_download".equals(cmd);
    }

    // ------------------------------------------------------------------ [approvals-1118] 正文里漏出来的工具调用

    static final Pattern DSML_INVOKE = Pattern.compile("<\\s*[｜|]+\\s*DSML\\s*[｜|]+\\s*invoke\\s+name\\s*=\\s*\"([A-Za-z_]+)\"\\s*>(.*?)<\\s*/\\s*[｜|]+\\s*DSML\\s*[｜|]+\\s*invoke\\s*>", Pattern.DOTALL);
    static final Pattern DSML_PARAM = Pattern.compile("<\\s*[｜|]+\\s*DSML\\s*[｜|]+\\s*parameter\\s+name\\s*=\\s*\"([A-Za-z_]+)\"[^>]*>(.*?)<\\s*/\\s*[｜|]+\\s*DSML\\s*[｜|]+\\s*parameter\\s*>", Pattern.DOTALL);
    static final Pattern DSML_ANY = Pattern.compile("[｜|]{2}\\s*DSML\\s*[｜|]{2}|<\\s*/?\\s*(tool_call|function_calls|invoke)\\b|\\{\\s*\"name\"\\s*:\\s*\"(run_command|open_url|open_target|write_file|phone_[a-z_]+|screenshot|android_ui|show_file)\"");
    /** 只剩收尾标记的那种：「▶ 执行命令：<命令></｜｜DSML｜｜ parameter></｜｜DSML｜｜ invoke>…」（A 轮 / 用户手测里实际出现的样子）。 */
    static final Pattern DSML_TAIL = Pattern.compile("(?s)^\\s*(?:▶\\s*)?执行命令[：:]\\s*(.+?)<\\s*/\\s*[｜|]+\\s*DSML\\s*[｜|]+\\s*parameter\\s*>");

    /** 从正文里还原工具调用（只认已有的工具名、参数要能组成合法 JSON）；还原不了返回空。 */
    static List<Llm.ToolCall> recoverCalls(String content) {
        List<Llm.ToolCall> out = new ArrayList<>();
        if (content == null || !DSML_ANY.matcher(content).find()) return out;
        java.util.regex.Matcher m = DSML_INVOKE.matcher(content);
        int n = 0;
        while (m.find()) {
            String name = m.group(1);
            if (!toolNames().contains(name)) continue;
            Map<String, Object> a = new TreeMap<>();
            java.util.regex.Matcher p = DSML_PARAM.matcher(m.group(2));
            while (p.find()) {
                String v = p.group(2).trim();
                if (p.group(1).equals("timeout_sec") && v.matches("\\d{1,4}")) a.put("timeout_sec", Long.parseLong(v));
                else a.put(p.group(1), v);
            }
            if (!a.isEmpty()) out.add(new Llm.ToolCall("dsml_" + (n++) + "_" + Long.toString(System.nanoTime(), 36), name, Json.stringify(a)));
        }
        if (out.isEmpty()) {
            java.util.regex.Matcher tail = DSML_TAIL.matcher(content);
            if (tail.find() && !tail.group(1).isBlank()) {
                Map<String, Object> a = new TreeMap<>();
                a.put("command", tail.group(1).trim());
                a.put("description", "（从模型正文里还原的命令）");
                out.add(new Llm.ToolCall("dsml_tail_" + Long.toString(System.nanoTime(), 36), "run_command", Json.stringify(a)));
            }
        }
        return out;
    }

    /** 把还原出来的调用做成一轮正常的「助手发起工具调用」（tool_calls 原样带回，后面的 tool 结果能配上对）。 */
    static Llm.Turn asCalls(String content, List<Llm.ToolCall> calls) {
        List<Object> tc = new ArrayList<>();
        for (Llm.ToolCall c : calls) {
            Map<String, Object> f = new TreeMap<>();
            f.put("name", c.name);
            f.put("arguments", c.arguments);
            Map<String, Object> x = new TreeMap<>();
            x.put("id", c.id);
            x.put("type", "function");
            x.put("function", f);
            tc.add(x);
        }
        Map<String, Object> msg = new TreeMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", tc);
        return new Llm.Turn(null, calls, msg);
    }

    static boolean looksLikeToolText(String s) {
        return s != null && (DSML_ANY.matcher(s).find() || FAKE_STEP.matcher(s).find() && s.matches("(?s).*(执行命令|run_command|Invoke-|Get-|adb ).*"));
    }

    static String stripToolText(String s) {
        String x = s == null ? "" : s;
        x = x.replaceAll("(?s)<\\s*/?\\s*[｜|]+\\s*DSML\\s*[｜|]+[^>]*>", "");
        return stripFakeSteps(x).trim();
    }

    /** [taskctx-1118] 一个任务里最近这么多个工具结果原样给模型；更早的输出压到 OLD_RESULT_CHARS 字（完整的在任务卡「详情」里）。 */
    static final int KEEP_FULL_RESULTS = 6, OLD_RESULT_CHARS = 1200;

    static void compactToolResults(List<Map<String, Object>> msgs, int keep, int clipTo) {
        int seen = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Map<String, Object> m = msgs.get(i);
            if (!"tool".equals(m.get("role"))) continue;
            if (++seen <= keep) continue;
            String c = String.valueOf(m.get("content"));
            if (c.length() <= clipTo + 200 || c.contains("较早的输出已压缩")) continue;
            String nc;
            try {
                Map<String, Object> o = new TreeMap<>(Json.obj(c));
                Object out = o.get("output");
                if (out == null) continue;
                String os = String.valueOf(out);
                o.put("output", os.substring(0, Math.min(os.length(), clipTo)) + "…（较早的输出已压缩，" + os.length() + " 字，完整的在任务卡「详情」里；需要就重新查）");
                nc = Json.stringify(o);
            } catch (RuntimeException e) {
                nc = c.substring(0, clipTo) + "…（较早的输出已压缩）";
            }
            Map<String, Object> x = new TreeMap<>(m);
            x.put("content", nc);
            msgs.set(i, x);
        }
    }

    /** 像 OneSay 步骤记录的行（▶ 执行命令… / ✓ … / ✗ …）：模型自己写的这种行一律不当真。 */
    static final Pattern FAKE_STEP = Pattern.compile("(?m)^\\s*(▶|✓|✗|☑|✔|✘)\\s*.*$|^\\s*(执行命令|打开网址|写文件|打开程序)：.*$");

    /** 去掉回复里模型伪造的步骤行；没有就原样返回。 */
    static String stripFakeSteps(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        if (!FAKE_STEP.matcher(s).find()) return s;
        return FAKE_STEP.matcher(s).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** 这一步给人看的一句：模型给的 description（截短），没给就用动作本身。 */
    static String stepDesc(Llm.ToolCall c, String cmd, Map<String, Object> args) {
        try {
            Map<String, Object> a = Json.obj(c.arguments);
            Object d = a == null ? null : a.get("description");
            if (d != null && !String.valueOf(d).isBlank()) return clip(LinkPolicy.oneLine(String.valueOf(d).trim()), 60);
        } catch (RuntimeException ignored) { }
        return clip(describe(cmd, args), 60);
    }

    /** 一行结论：成功用结果文字，失败把原因压成一行。 */
    static String summary(Actions.Result r) {
        String t = r.text == null ? "" : LinkPolicy.oneLine(r.text);
        if (!r.ok && r.data != null && r.data.get("reason_zh") != null) {
            // [preview-1120 真机] 原因已经写在结论里（「这件事要管理员…」），或是「对方点了拒绝」而结论已说拒绝：不再括号里重复一遍
            String rz = String.valueOf(r.data.get("reason_zh"));
            boolean dup = t.contains(rz) || (LinkPolicy.R_CONFIRM_DENIED.equals(String.valueOf(r.data.get("reason"))) && t.contains("拒绝"));
            if (!dup) t = t + "（" + rz + "）";
        }
        if (!r.ok && r.data != null && r.data.get("output") != null) {
            String o = clixml(String.valueOf(r.data.get("output")).trim());
            String first = "";
            for (String line : o.split("\\R")) { if (!line.isBlank()) { first = line.trim(); break; } }
            if (!first.isEmpty()) t = t + "：" + first;
        }
        return clip(Humanize.text(t), 160);
    }

    /**
     * [preview-1120 真机] Windows PowerShell 的错误流经管道出来是 CLIXML（「#< CLIXML」+ XML），任务卡上原样显示成「命令失败：#< CLIXML」。
     * 认出来就取里面的错误文字（S="Error" 段，解开 _x000D__x000A_ 这类转义），别的输出原样返回。
     */
    static String clixml(String o) {
        if (o == null) return "";
        int k = o.indexOf("#< CLIXML");
        if (k < 0) return o;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>", java.util.regex.Pattern.DOTALL).matcher(o.substring(k));
        StringBuilder b = new StringBuilder();
        while (m.find()) b.append(m.group(1));
        String before = o.substring(0, k).trim();
        if (b.length() == 0) return before;
        String err = b.toString();
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("_x([0-9A-Fa-f]{4})_").matcher(err);
        StringBuffer sb = new StringBuffer();
        while (x.find()) x.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(x.group(1), 16))));
        x.appendTail(sb);
        err = sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&").trim();
        return before.isEmpty() ? err : before + "\n" + err;
    }

    static Map<String, Object> stepEv(String phase, int n, String desc, String cmd, Map<String, Object> args, boolean ok, String summary, String output, String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("phase", phase);
        m.put("n", (long) n);
        m.put("desc", desc);
        m.put("tool", cmd);
        m.put("detail", describe(cmd, args));
        m.put("ok", ok);
        m.put("summary", summary);
        m.put("output", clip(output, 16000));
        m.put("text", text);
        return m;
    }

    /** 只重试暂时性的失败（超时 / 连不上 / 429 / 5xx / 空回复），Key 错、余额不足、400 这类直接报。 */
    Llm.Turn nextWithRetry(Brain brain, List<Map<String, Object>> msgs, List<Map<String, Object>> tools) throws Exception {
        for (int i = 0; ; i++) {
            try {
                return brain.next(msgs, tools);
            } catch (Llm.Transient e) {
                if (i >= RETRIES || cancel.get()) throw e;
                try { Thread.sleep(backoffMs * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /** 参数有问题时回给模型的说明（让它改了再调，不中断任务）。 */
    static String badArgs(Llm.ToolCall c) {
        String raw = clip(c.arguments, 300);
        boolean known = toolNames().contains(c.name);
        if (!known) {
            Set<String> names = new java.util.LinkedHashSet<>(toolNames());
            names.remove("open_app");
            return Json.stringify(Map.of("ok", false, "error", "INVALID_TOOL", "text", "不认识的工具：" + c.name + "。可用的工具：" + String.join("、", names)));
        }
        Map<String, Object> a;
        try {
            a = Json.obj(c.arguments);
        } catch (RuntimeException e) {
            a = null;
        }
        if (a == null) return Json.stringify(Map.of("ok", false, "error", "INVALID_ARGS", "text", "参数不是合法 JSON，请改正后重试：" + raw));
        String need = c.name.equals("run_command") ? "command" : c.name.equals("open_url") ? "url" : (c.name.equals("open_app") || c.name.equals("open_target") || c.name.equals("phone_open_app")) ? "name"
                : c.name.equals("phone_operate") ? "goal" : NEED.getOrDefault(c.name, "path");
        return Json.stringify(Map.of("ok", false, "error", "INVALID_ARGS", "text", "参数无效：缺少或为空的字段 " + need + "，请改正后重试：" + raw));
    }

    /** 后加的工具缺哪个字段（badArgs 用）。 */
    static final Map<String, String> NEED = new TreeMap<>(Map.of("android_ui", "action", "show_file", "path", "screenshot", "description", "ask_choice", "question / options", "phone_send_file", "path", "phone_run_shortcut", "name", "download", "url / path", "reminder", "action（add 还要 title、at；update / delete 要 id）"));

    static Map<String, Object> toolMsg(String id, String content) {
        Map<String, Object> m = new TreeMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", id == null ? "" : id);
        m.put("content", content);
        return m;
    }

    static String describe(String cmd, Map<String, Object> a) {
        switch (cmd) {
            case "sys_exec": return "执行命令：" + clip(LinkPolicy.oneLine(String.valueOf(a.get("command"))), 200);
            case "mcp_call": return "调用" + mcpName.apply(String.valueOf(a.get("fn"))) + "：" + clip(LinkPolicy.oneLine(Json.stringify(a.get("arguments") == null ? new TreeMap<String, Object>() : a.get("arguments"))), 160);
            case "open_url": return "打开网址：" + clip(String.valueOf(a.get("url")), 200);
            case "file_write": return "写文件：" + clip(String.valueOf(a.get("path")), 200);
            case "open_app": return "打开程序：" + clip(String.valueOf(a.containsKey("path") ? a.get("path") : a.get("name")), 200);
            case "open_target": return "打开：" + clip(String.valueOf(a.get("name")), 200);
            case "phone_status": return "查手机状态";
            case "reminder": {
                String act = String.valueOf(a.get("action"));
                if ("list".equals(act)) return "看这台电脑上的提醒";
                String t = a.get("title") == null ? String.valueOf(a.getOrDefault("id", "")) : String.valueOf(a.get("title"));
                return ("add".equals(act) ? "建提醒" : "update".equals(act) ? "改提醒" : "删除提醒") + "「" + clip(t, 60) + "」" + (a.get("at") != null ? "：" + a.get("at") : "");
            }
            case "screen_look": return "截屏：" + ("pc".equals(a.get("target")) ? "这台电脑" : clip(String.valueOf(a.get("target")), 60)) + (a.get("question") != null ? "（看：" + clip(String.valueOf(a.get("question")), 80) + "）" : "");
            case "show_file": return "显示文件：" + clip(String.valueOf(a.get("path")), 200);
            case "ask_choice": return "请你选：" + clip(String.valueOf(a.get("question")), 120);
            case "android_ui": {
                String act = String.valueOf(a.get("action"));
                String dev = a.get("device") == null ? "" : "（" + a.get("device") + "）";
                if (act.equals("dump")) return "读安卓界面" + dev;
                return (act.equals("check_text") ? "勾选「" : "点「") + clip(String.valueOf(a.get("text")), 40) + "」" + (a.get("then_tap") != null ? "，再点「" + clip(String.valueOf(a.get("then_tap")), 40) + "」" : "") + dev;
            }
            case "phone_open_app": return "手机上打开：" + clip(String.valueOf(a.containsKey("name") ? a.get("name") : a.get("package")), 200);
            case "phone_operate": return "交给手机去做：" + clip(String.valueOf(a.get("goal")), 200);
            case "net_download": return "下载：" + clip(String.valueOf(a.get("url")), 160) + " → " + clip(String.valueOf(a.get("path")), 120);
            case "phone_send_file": return "发文件到手机：" + clip(String.valueOf(a.get("path")), 200);
            case "phone_shortcuts": return "看手机上的快捷指令";
            case "phone_run_shortcut": return "在手机上运行快捷指令「" + clip(String.valueOf(a.get("name")), 60) + "」";
            default: return cmd;
        }
    }

    static String stepText(String cmd, Map<String, Object> a, Actions.Result r) {
        StringBuilder b = new StringBuilder("▶ ").append(describe(cmd, a)).append('\n');
        b.append(r.ok ? "✓ " : "✗ ").append(clip(r.text == null ? "" : r.text, 200));
        Object out = r.data == null ? null : r.data.get("output");
        if (out != null && !String.valueOf(out).isBlank()) b.append("\n").append(clip(String.valueOf(out).trim(), 400));
        return b.toString();
    }

    static String resultJson(Actions.Result r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", r.ok);
        m.put("text", r.text == null ? "" : r.text);
        if (r.data != null) {
            for (String k : new String[] {"output", "exit", "reason", "reason_zh", "path", "image", "file", "how", "warn", "note", "verified"}) {
                Object v = r.data.get(k);
                if (v == null) continue;
                m.put(k, "output".equals(k) ? clip(String.valueOf(v), OUTPUT_TO_MODEL) : v);
            }
        }
        return Json.stringify(m);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…（截断）";
    }
}
