package com.onesay.assistant.v2.jobs;

import com.onesay.assistant.v2.capability.SideEffect;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限静态分析（lane-a 第 3 项「权限卡」）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li><b>创建时</b> {@link #analyze}：按任务的指令文字（AGENT）/ 模板 id / 固定能力序列，列出它会用到的工具和副作用。
 *       规则偏「宁多勿少」：多列一项只是多一个勾选框，少列一项会让任务在运行时停在「等确认」。</li>
 *   <li><b>运行时</b> {@link #forTool}：一次工具调用（能力签名 + 副作用分级）需要哪一项权限，交给 {@link PermissionGuard} 比对授权。</li>
 * </ul>
 */
public final class PermissionAnalyzer {

    private PermissionAnalyzer() {}

    static final Pattern P_CAL_READ = Pattern.compile("日程|日历|会议|行程|安排");
    static final Pattern P_CAL_WRITE = Pattern.compile("(加|添加|建|新建|创建|写|记|放)(到|进|个|一个)?(日历|日程)|(日历|日程)(里|上)?(加|添加|建|新建)|约(个|一个)?会");
    static final Pattern P_MAIL_READ = Pattern.compile("邮件|邮箱|收件|未读");
    static final Pattern P_MAIL_DRAFT = Pattern.compile("草稿|起草|写(封|一封)?邮件|拟(一封)?邮件");
    static final Pattern P_MAIL_SEND = Pattern.compile("发(个|封|一封)?邮件|回(复)?邮件|邮件(给|发给|告诉)|发给.{0,12}(邮箱|@)|寄(给|出)");
    static final Pattern P_SEARCH = Pattern.compile("搜|查|新闻|天气|气温|下雨|股价|汇率|热搜|行情|比分|资讯|最新|联网|网上");
    static final Pattern P_TODO = Pattern.compile("待办|任务清单|todo|要做的事", Pattern.CASE_INSENSITIVE);
    static final Pattern P_TODO_WRITE = Pattern.compile("(加|添加|新建|建|记|创建|完成|勾掉|划掉)(一条|个|一个)?(到)?(待办|todo)|(待办|todo)(里)?(加|添加|新建)", Pattern.CASE_INSENSITIVE);
    static final Pattern P_SETTINGS = Pattern.compile("亮度|音量|静音|勿扰|蓝牙|wifi|wi-fi|无线|飞行模式|热点|定位|gps|nfc|息屏|省电|深色|振动|铃声|闹钟|设置", Pattern.CASE_INSENSITIVE);
    static final Pattern P_DEVICE = Pattern.compile("电量|电池|存储|内存|网络|信号|流量");
    static final Pattern P_OPEN = Pattern.compile("打开|启动|进入|跳到");
    static final Pattern P_SHELL = Pattern.compile("shell|命令行|root|adb|执行命令|脚本", Pattern.CASE_INSENSITIVE);
    static final Pattern P_PAY = Pattern.compile("支付|付款|付钱|转账|买单|下单|购买|充值|缴费|买");
    static final Pattern P_RED = Pattern.compile("发(个|条|一条)?短信|短信(给|告诉)|发微信|发消息给|转发|删除|删掉|清空|清除|卸载|格式化|恢复出厂|打电话|拨打|呼叫");

    // 英文（在 fold 后的小写文本上匹配）
    static final Pattern P_MAIL_SEND_EN = Pattern.compile("\\b(send|sends|sending|reply|forward|fwd)\\b.{0,30}\\b(e-?mails?|mails?|message|letter|report|it|them|him|her)\\b|\\be-?mail (him|her|them|my|the|to)\\b|\\bsend (to|it)\\b");
    static final Pattern P_PAY_EN = Pattern.compile("\\b(pay|pays|paying|payment|purchase|buy|checkout|transfer|wire|remit|top ?up)\\b");
    static final Pattern P_RED_EN = Pattern.compile("\\b(text|sms|delete|remove|erase|wipe|uninstall|factory reset|call|dial)\\b|发送短信|寄出");

    /** 模板任务的权限（模板固定，不靠文字猜）。 */
    public static Set<Perm> forTemplate(String templateId) {
        Set<Perm> s = EnumSet.noneOf(Perm.class);
        if (DailyDigest.TEMPLATE_ID.equals(templateId)) {
            s.add(Perm.READ_TODO);
            s.add(Perm.READ_MAIL);
            s.add(Perm.WEB_SEARCH);
        }
        if (templateId != null && templateId.startsWith("ev.")) {   // 第 7 项事件任务：ev.<源>#<条件摘要>
            s.add(templateId.startsWith("ev.NOTIFICATION") ? Perm.READ_NOTIFICATION : Perm.READ_DEVICE);
        }
        if (templateId != null && templateId.startsWith(WatchBridge.TEMPLATE_PREFIX)) s.add(Perm.WEB_SEARCH);   // B 的 P3 监控：只读联网搜索
        if (MyThingsExport.TEMPLATE_ID.equals(templateId)) {   // 第 6 项：只读待办 + 邮件标题，发给晨间播报 v2
            s.add(Perm.READ_TODO);
            s.add(Perm.READ_MAIL);
        }
        return s;
    }

    /**
     * 创建时的静态分析。
     *
     * @param command     AGENT 任务的指令文字（REMINDER 传 null / 空）
     * @param templateId  模板 id（没有传 null）
     * @param toolSigns   固定能力序列（没有传 null）
     */
    public static Set<Perm> analyze(String command, String templateId, Collection<String> toolSigns) {
        Set<Perm> s = EnumSet.noneOf(Perm.class);
        if (templateId != null && templateId.length() > 0) s.addAll(forTemplate(templateId));
        if (toolSigns != null) for (String sign : toolSigns) {
            Perm p = forTool(sign, null);
            if (p != null) s.add(p);
        }
        String c = command == null ? "" : command;
        if (c.trim().length() == 0) return s;
        // 权限卡与 S3 红词同一套归一化（繁→简 / NFKC / 去零宽 / 小写）+ 英文，免得「发出邮件 / 付款」不出现在卡上
        String f = com.onesay.assistant.v2.security.SafetyText.fold(c);
        if (P_MAIL_SEND_EN.matcher(f).find()) {
            s.add(Perm.MAIL_DRAFT);
            s.add(Perm.MAIL_SEND);
        }
        if (P_PAY_EN.matcher(f).find()) s.add(Perm.PAYMENT);
        if (P_RED_EN.matcher(f).find()) s.add(Perm.RED_ACTION);
        c = c + "\n" + f;
        boolean send = P_MAIL_SEND.matcher(c).find();
        if (send) {
            // 发邮件 = 先起草（可预授权）+ 发出（每次确认）；起草之前通常要读往来邮件核实收件人
            s.add(Perm.MAIL_DRAFT);
            s.add(Perm.MAIL_SEND);
        }
        if (P_MAIL_DRAFT.matcher(c).find()) s.add(Perm.MAIL_DRAFT);
        if (P_MAIL_READ.matcher(c).find()) s.add(Perm.READ_MAIL);   // 含发邮件：核实收件人要查往来邮件
        if (P_CAL_WRITE.matcher(c).find()) s.add(Perm.WRITE_CALENDAR);
        else if (P_CAL_READ.matcher(c).find()) s.add(Perm.READ_CALENDAR);
        if (P_SEARCH.matcher(c).find()) s.add(Perm.WEB_SEARCH);
        if (P_TODO.matcher(c).find()) s.add(P_TODO_WRITE.matcher(c).find() ? Perm.WRITE_TODO : Perm.READ_TODO);
        if (P_SETTINGS.matcher(c).find()) s.add(Perm.SYSTEM_SETTINGS);
        if (P_DEVICE.matcher(c).find()) s.add(Perm.READ_DEVICE);
        if (P_OPEN.matcher(c).find()) s.add(Perm.OPEN_APP);
        if (P_SHELL.matcher(c).find()) s.add(Perm.SHELL);
        if (P_PAY.matcher(c).find()) s.add(Perm.PAYMENT);
        if (P_RED.matcher(c).find()) s.add(Perm.RED_ACTION);
        return s;
    }

    /** 内置连接器 id（与 ConsentPolicy.BUILTIN_CONNECTORS 同一张表；远程 MCP 服务器不能用这些 id）。 */
    static final java.util.Set<String> BUILTIN_CONN = new java.util.HashSet<String>(java.util.Arrays.asList("google", "mail", "tavily"));

    /** conn.&lt;id&gt;.&lt;tool&gt; 且 id 不是内置连接器 → 远程 MCP 工具。 */
    public static boolean isRemoteConnSign(String sign) {
        if (sign == null || !sign.startsWith("conn.")) return false;
        String id = sign.substring(5);
        int dot = id.indexOf('.');
        if (dot <= 0) return false;
        return !BUILTIN_CONN.contains(id.substring(0, dot));
    }

    /**
     * 运行时：一次工具调用需要的权限。null = 不需要权限（纯回答 / 本地无副作用）。
     * 能力签名优先（精确），签名认不出时按副作用分级兜底；ROOT / DESTRUCTIVE / PUBLIC_COMMS 至少是红卡。
     */
    public static Perm forTool(String sign, SideEffect effect) {
        // 先强归一化（NFKC / 去零宽 / 同形字→拉丁 / 小写），全大写 SEND_EMAIL、ѕend 不再漏
        String s = com.onesay.assistant.v2.security.SafetyText.fold(sign == null ? "" : sign).toLowerCase(java.util.Locale.ROOT);
        if (s.equals("ask.reply") || s.equals("chat.reply") || s.equals("clarify")) return null;   // 纯回答（先判：下面的「对外发送」判据认 reply）
        // ---- 永远不可预授权的（先判，防被下面的宽规则吞掉）
        if (s.contains("senddraft") || s.equals("mail.send") || s.startsWith("mail.send") || s.equals("mail.reply")
                || s.contains(".send_draft") || s.endsWith(".send")) return Perm.MAIL_SEND;
        // 2026-10-23 A+B 合并：B 的 conn.mail.delete_draft 只删本 App 建的草稿 = 草稿权限（不是红卡）；先判，防被 delete 规则吞掉
        if (s.equals("conn.mail.delete_draft") || s.contains("deletedraft")) return Perm.MAIL_DRAFT;
        if (s.contains("pay") || s.contains("purchase") || s.contains("checkout") || s.contains("transfer")) return Perm.PAYMENT;
        if (s.contains("sms") || s.startsWith("call.") || s.contains(".call") || s.contains("delete") || s.contains("uninstall")
                || s.contains("wipe") || s.contains("factory")) return Perm.RED_ACTION;
        // ★ LOGIC-5：远程（非内置）连接器的其余工具一律不可预授权（无人值守每次岛上确认）。付款 / 删除 / 短信类在上面已归 PAYMENT / RED_ACTION
        //   （同样不可预授权）；不新转 MAIL_SEND（那会被 CallbackPlugin 改道成内置邮件起草）。
        if (isRemoteConnSign(s)) return Perm.REMOTE_TOOL;
        // ★ LOGIC-5：统一「对外发送」判据（dispatch / deliver / emit / relay / submit / enqueue / push …）→ 不可预授权
        if (com.onesay.assistant.v2.security.OutboundJudge.signLooksOutbound(sign) && !s.startsWith("mail.draft") && !s.contains("draft")) return Perm.RED_ACTION;
        // ---- 可预授权的
        if (s.startsWith("mail.draft") || s.contains("createdraft") || s.contains("updatedraft") || s.contains("create_draft")
                || s.contains("update_draft")) return Perm.MAIL_DRAFT;
        // 2026-10-23 A+B 合并：B 的 Google Tasks（conn.google.tasks_list / tasks_create / tasks_update）= 待办权限
        if (s.contains("tasks_")) return s.contains("tasks_list") || s.contains("tasks_get") ? Perm.READ_TODO : Perm.WRITE_TODO;
        if (s.startsWith("mail.") || s.contains("gmail") || s.contains(".mail.") || s.contains("inbox")) return Perm.READ_MAIL;
        if (s.contains("calendar") && (s.contains("add") || s.contains("insert") || s.contains("create") || s.contains("update")
                || s.contains("write"))) return Perm.WRITE_CALENDAR;
        if (s.contains("calendar") || s.contains("agenda")) return Perm.READ_CALENDAR;
        if (s.startsWith("s3.todo") && !s.equals("s3.todo")) return Perm.READ_TODO;
        if (s.equals("s3.todo") || s.equals("s3.tasks")) return Perm.READ_TODO;
        if (s.contains("search") || s.contains("web.") || s.contains("tavily") || s.contains("weather") || s.contains("news")) return Perm.WEB_SEARCH;
        if (s.startsWith("app.launch") || s.startsWith("settings.page") || s.startsWith("open.")) return Perm.OPEN_APP;
        if (s.startsWith("shell") || s.contains(".shell") || effect == SideEffect.ROOT) return Perm.SHELL;
        if (s.startsWith("settings.") || s.startsWith("volume") || s.startsWith("clock.") || s.startsWith("alarm.")
                || s.startsWith("wifi") || s.startsWith("bluetooth") || s.startsWith("connectivity")) {
            return s.endsWith(".get") || s.endsWith(".state") || s.endsWith(".query") ? Perm.READ_DEVICE : Perm.SYSTEM_SETTINGS;
        }
        if (s.startsWith("battery") || s.startsWith("device.") || s.startsWith("readonly.")) return Perm.READ_DEVICE;
        if (s.equals("ask.reply") || s.equals("chat.reply") || s.equals("clarify") || s.length() == 0 && effect == null) return null;
        if (effect == null) return null;
        switch (effect) {
            case NONE: return null;
            case PRIVATE_DATA_READ: return Perm.READ_DEVICE;
            case DEVICE_CONTROL: return Perm.SYSTEM_SETTINGS;
            case PERSISTENT_WRITE: return Perm.SYSTEM_SETTINGS;
            case PUBLIC_COMMS:
            case DESTRUCTIVE: return Perm.RED_ACTION;
            default: return Perm.SHELL;   // ROOT
        }
    }
}
