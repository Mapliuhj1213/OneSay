package com.onesay.assistant.v2.jobs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 「我的事」导出（lane-a 第 6 项；PLAN-20261019 ④ 最后一行「共用待办 / 邮件摘要」）。纯 Java，JVM 可测。
 *
 * <p>OneSay 定时把「今日待办 + 未读重要邮件标题」写成广播发给晨间播报 v2，<b>v2 自己落盘</b>
 * （遵守「外部不直写对方数据」：OneSay 不碰 v2 的文件）。这里只做 OneSay 一侧。
 *
 * <h3>广播格式（v1）</h3>
 * v2 的 {@code V2ControlReceiver} 是 exported=false，和第 2 项的 4 条语音控制一样只能 root 发：
 * <pre>am broadcast -n com.onesay.clock/.ControlReceiver -a com.onesay.clock.MY_THINGS --ei v 1 --es payload &lt;Base64(UTF-8 JSON)&gt;</pre>
 * （开源版：目标是 OneSay 时钟；时钟侧目前还没有 MY_THINGS 的处理，会忽略这条广播。）
 * payload 解码后是：
 * <pre>{
 *   "v": 1, "kind": "my_things", "source": "com.onesay.assistant",
 *   "date": "2026-10-23",            // 生成时本机时区的日期（v2 只在同一天用，隔天作废）
 *   "generatedAt": 1761170400000,    // 毫秒
 *   "todos": ["交周报", ...],         // 今日待办（含没设截止的），≤ 5 条，每条 ≤ 30 字
 *   "todoNote": "",                  // 取不到时的原因：没授权读取 / 暂时读不到；取到了为空串
 *   "mails": ["季度预算审批", ...],    // 未读重要邮件标题，≤ 3 条，每条 ≤ 30 字
 *   "mailNote": ""                   // 没授权读取 / 还没接入 / 暂时读不到
 * }</pre>
 * 邮件标题是外部数据：去控制字符 / 双向覆盖字符并截断；整段 JSON 走 Base64，所以 shell 命令里只有
 * {@code [A-Za-z0-9+/=]}，任何标题都拼不出额外的命令或参数（{@link #amCommand} 再校验一次）。
 * v2 侧应把它们当展示文本读，不当指令。
 *
 * <h3>定时</h3>
 * 内置模板任务 {@link #TEMPLATE_ID}，默认每天 6:30（提前 5 分钟取数），权限卡只勾「读待办 / 读邮件」。
 * 结果不打扰用户：outbox 记为「没打扰」，只在回调历史里留一条。
 */
public final class MyThingsExport {

    private MyThingsExport() {}

    public static final String TEMPLATE_ID = "my_things_export";
    public static final String TITLE = "「我的事」导出";
    public static final int VERSION = 1;
    public static final String PKG = com.onesay.assistant.v2.brief.BriefState.SENDER_PKG;
    public static final String RECEIVER = PKG + "/.ControlReceiver";
    public static final String ACTION = PKG + ".MY_THINGS";
    static final int MAX_TODO = 5;
    static final int MAX_MAIL = 3;
    static final int MAX_ITEM = 30;

    static final Pattern B64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");

    /** 新建模板任务（默认每天 6:30，不出声）。 */
    public static JobStore.Job newJob(int hour, int minute) {
        JobStore.Job j = new JobStore.Job();
        j.title = TITLE;
        j.templateId = TEMPLATE_ID;
        j.repeat = com.onesay.assistant.v2.s3.S3Schedule.DAILY;
        j.hour = hour;
        j.minute = minute;
        j.leadMs = JobsEngine.DEFAULT_LEAD_MS;
        j.speak = false;
        return j;
    }

    /** 拼 payload JSON（不带日程，旧口径）。 */
    public static String payload(DailyDigest.Part todos, DailyDigest.Part mails, long now, TimeZone tz) {
        return payload(todos, mails, null, now, tz);
    }

    static final int MAX_EVENT = 5;

    /**
     * [sched] 2026-10-24 本地日程：多两个字段 {@code "events": ["上午9点 晨会", …]}（≤ 5 条，每条 ≤ 30 字）、{@code "eventNote"}。
     * 版本号不变（v=1）：只加字段，v2 侧不认识的键照旧忽略；v2 要念日程得在 V2ControlReceiver 里读 events（交给晨间播报线程）。
     * events = null（本地日程关着）时这两个键都不写，和旧 payload 逐字一致。
     */
    public static String payload(DailyDigest.Part todos, DailyDigest.Part mails, DailyDigest.Part events, long now, TimeZone tz) {
        JSONObject o = new JSONObject();
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
            o.put("v", VERSION);
            o.put("kind", "my_things");
            o.put("source", "com.onesay.assistant");
            o.put("date", f.format(new Date(now)));
            o.put("generatedAt", now);
            o.put("todos", items(todos, MAX_TODO));
            o.put("todoNote", note(todos));
            o.put("mails", items(mails, MAX_MAIL));
            o.put("mailNote", note(mails));
            if (events != null) {
                o.put("events", items(events, MAX_EVENT));
                o.put("eventNote", note(events));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return o.toString();
    }

    static JSONArray items(DailyDigest.Part p, int max) {
        JSONArray a = new JSONArray();
        if (p == null || p.items == null) return a;
        for (String s : p.items) {
            if (a.length() >= max) break;
            String c = DailyDigest.clean(s, MAX_ITEM).trim();
            if (c.length() > 0) a.put(c);
        }
        return a;
    }

    static String note(DailyDigest.Part p) {
        if (p == null) return "暂时读不到";
        if (p.items != null) return "";
        return p.note == null || p.note.length() == 0 ? "暂时读不到" : DailyDigest.clean(p.note, 20);
    }

    /** root 广播命令；payload 走 Base64，命令里没有任何外部文本。 */
    public static String amCommand(String payloadJson) {
        String b = base64(utf8(payloadJson == null || payloadJson.length() == 0 ? "{}" : payloadJson));
        if (!B64.matcher(b).matches()) throw new IllegalStateException("payload 编码异常");
        return "am broadcast -n " + RECEIVER + " -a " + ACTION + " --ei v " + VERSION + " --es payload " + b;
    }

    /** 给历史 / 日志的一句话（不含标题原文）。 */
    public static String summary(DailyDigest.Part todos, DailyDigest.Part mails) {
        return "已发给晨间播报 v2：待办 " + count(todos, MAX_TODO) + "，邮件标题 " + count(mails, MAX_MAIL);
    }

    static String count(DailyDigest.Part p, int max) {
        if (p == null || p.items == null) return note(p);
        return Math.min(p.items.size(), max) + " 条";
    }

    static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final char[] ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 标准 Base64（带 = 填充）。不用 android.util.Base64（JVM 单测里是桩），也不依赖 java.util.Base64。 */
    static String base64(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(ALPHA[b0 >> 2]).append(ALPHA[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < in.length ? ALPHA[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < in.length ? ALPHA[b2 & 63] : '=');
        }
        return sb.toString();
    }

    /** 测试 / 日志用：今天待办 + 邮件条目原样列表（已清洗）。 */
    static List<String> list(JSONArray a) {
        List<String> l = new java.util.ArrayList<String>();
        for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }
}
