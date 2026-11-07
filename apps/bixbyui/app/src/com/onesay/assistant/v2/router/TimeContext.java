package com.onesay.assistant.v2.router;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间上下文 —— 把「手机现在几点」变成模型能看到的东西。
 *
 * <h3>它解决什么（2026-10-06 实测事故）</h3>
 * <pre>
 *   用户：“40分钟后叫醒我”
 *   实际：模型答“我这边读不到本机当前时间，所以没法自动算出具体是几点几分响铃，
 *              你直接告诉我现在的时间，或者干脆说闹钟定在几点”
 *   期望：拿到当前时间 → 算出 00:52 → 设好闹钟
 * </pre>
 * 这不是模型笨，是**我们把它的眼睛蒙上了**：
 * <ol>
 *   <li>LLM 提示词（ASK / DO / PLAN 三条通道）里**从来没有当前时间** ——
 *       DeepSeek 是云端模型，它不知道这台手机此刻几点；</li>
 *   <li>{@code clock.alarm.set} 的参数是**绝对**的 hour/minute，
 *       而用户给的是**相对**的“40 分钟后” —— 中间缺一个换算器。</li>
 * </ol>
 * 模型的选择（不猜、反问）在"手里没有时钟"的前提下其实是**正确的**，
 * 所以修法不是调话术逼它猜，而是**把时钟给它**，并把相对时间**预先算好**。
 *
 * <h3>两条腿</h3>
 * <ul>
 *   <li>{@link #block()} —— 注入**系统提示词**：告诉模型"现在是几点"。
 *       这是通用能力，覆盖“明天早上八点”“下周一”这类模型自己能推的换算。</li>
 *   <li>{@link #hint(String)} —— 注入**用户消息**：把“40 分钟后”这种
 *       **需要精确算术**的相对时间，由本机算成绝对时刻再交给模型。
 *       为什么要代算：大模型做日期算术**不可靠**（跨零点、跨天尤其容易错），
 *       而这类换算对本机来说是确定性的 —— 能算的绝不让模型猜。</li>
 * </ul>
 *
 * <h3>顺带修掉的一个机制缺陷</h3>
 * <p>{@link #stripRelative(String)}：{@code IntentClassifier.looksLikeMultiAction}
 * 用 {@code "A后B"} 结构判"一句话里挂了两件事"。但「40分钟**后**叫醒我」的前后
 * 正好各≥2 字，会被这条结构判成**复合句**（其实只有一件事）。
 * 时序连接词的"后"和相对时间的"后"是两回事，故在判定前先把相对时间短语抹掉。
 *
 * <p>纯计算，不持 Context，无副作用，可离线自测。
 */
public final class TimeContext {

    private TimeContext() {}

    private static final String[] WEEK_CN = { "日", "一", "二", "三", "四", "五", "六" };

    /**
     * 相对时间表达式：`<数量><个?><单位><后|之后|以后|过后>`。
     *
     * <p>数量支持阿拉伯数字、中文数字（通过 {@link NumberParser} 解析）与"半"。
     * 单位覆盖 秒 / 分钟 / 小时（钟头）—— 这三档是"叫醒/提醒/倒计时"的全部口语形态。
     *
     * <p>用**结构**识别而不是罗列例句：只要"数量 + 时间单位 + 后"成立就算相对时间，
     * 所以"两小时后""四十分钟后""半个小时之后""90秒以后"同一条规则全覆盖。
     *
     * <p>★ 数量那一支为什么要写成"…半 | 纯数字"两组：
     * 「一个半小时后」若只按"数量 + 单位"匹配，正则会在"半小时后"处抢先命中 →
     * 算出 **30 分钟**（应为 90 分钟），用户设的闹钟直接早了一小时。
     * 所以把"N个半"作为一个**整体数量**（N + 0.5）放在前面优先匹配。
     */
    private static final Pattern RELATIVE = Pattern.compile(
            "((?:\\d+|[零〇一二两三四五六七八九十百]+)?\\s*个?\\s*半"
                    + "|\\d+|[零〇一二两三四五六七八九十百]+)"
                    + "\\s*(?:个)?\\s*(小时|钟头|分钟|分|秒)\\s*(?:后|之后|以后|过后)");

    /** 现在（设备时钟）。 */
    public static Calendar now() {
        return Calendar.getInstance();
    }

    /** 把时刻渲染成中文可读串：{@code 2026年10月6日 星期二 00:12}。 */
    public static String describe(Calendar c) {
        if (c == null) c = now();
        StringBuilder sb = new StringBuilder();
        sb.append(c.get(Calendar.YEAR)).append("年")
          .append(c.get(Calendar.MONTH) + 1).append("月")
          .append(c.get(Calendar.DAY_OF_MONTH)).append("日 ")
          .append("星期").append(WEEK_CN[c.get(Calendar.DAY_OF_WEEK) - 1]).append(' ')
          .append(pad2(c.get(Calendar.HOUR_OF_DAY))).append(':')
          .append(pad2(c.get(Calendar.MINUTE)));
        return sb.toString();
    }

    /** 24 小时制 {@code HH:mm}。 */
    public static String hhmm(Calendar c) {
        if (c == null) return "";
        return pad2(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(Calendar.MINUTE));
    }

    private static String pad2(int v) {
        return v < 10 ? ("0" + v) : String.valueOf(v);
    }

    /**
     * 注入**系统提示词**的时间块。
     *
     * <p>内容刻意写三件事：① 现在几点；② 换算口径（24 小时制）；③ 用法纪律
     * （先换算再填参，拿不准就问，不要沉默地放弃）。
     *
     * <p>★ 刻意压到两行：它会被注入**每一次** LLM 调用（含与时间无关的
     * 「调低点亮度」）。实测过长的时间块会挤占注意力、扰动能力选择
     * （同一句「天太黑了屏幕刺眼帮我调低点亮度」在长块下降级成先读一次亮度）。
     * 只在**确实需要换算**的句子上才附加长提示，由 {@link #hint(String)} 负责。
     */
    public static String block() {
        Calendar c = now();
        StringBuilder sb = new StringBuilder();
        sb.append("【当前时间】").append(describe(c)).append("（24 小时制，本机实时读取）。\n");
        sb.append("与“现在/今天/明天/周几/几小时后”有关的说法一律以此为基准；"
                + "换算出的绝对时刻直接填参数，不要回答“我读不到当前时间”。\n");
        return sb.toString();
    }

    /** 相对时间解析结果。 */
    public static final class Relative {
        /** 命中的原文，如 "40分钟后"。 */
        public final String raw;
        /** 偏移总量（秒）。 */
        public final long deltaSeconds;
        /** 目标绝对时刻。 */
        public final Calendar target;

        Relative(String raw, long deltaSeconds, Calendar target) {
            this.raw = raw;
            this.deltaSeconds = deltaSeconds;
            this.target = target;
        }

        /** 目标时刻的 24 小时制 {@code HH:mm}。 */
        public String hhmm() {
            return TimeContext.hhmm(target);
        }

        /** 目标时刻的中文可读串。 */
        public String describe() {
            return TimeContext.describe(target);
        }

        /** 口语化的偏移描述："40 分钟" / "2 小时" / "90 秒"。 */
        public String deltaText() {
            long s = deltaSeconds;
            if (s % 3600 == 0) return (s / 3600) + " 小时";
            if (s % 60 == 0) return (s / 60) + " 分钟";
            return s + " 秒";
        }
    }

    /**
     * 在句子里找**第一个**相对时间表达式并算出绝对时刻。
     *
     * @return 解析结果；句中没有相对时间时返回 {@code null}
     */
    public static Relative parseRelative(String query) {
        if (query == null || query.length() == 0) return null;
        Matcher m = RELATIVE.matcher(query);
        if (!m.find()) return null;

        String numText = m.group(1).replace("个", "").trim();
        String unit = m.group(2);

        Long seconds = amountToSeconds(numText, unit);
        if (seconds == null || seconds.longValue() <= 0) return null;
        // 上限：30 天。再长就不像"叫醒/提醒"了，也挡住脏输入。
        if (seconds.longValue() > 30L * 24L * 3600L) return null;

        Calendar target = now();
        target.add(Calendar.SECOND, seconds.intValue());   // <=30天，int 秒安全
        return new Relative(m.group(), seconds.longValue(), target);
    }

    /**
     * 把"数量词 + 单位"折算成秒。
     *
     * @param numText 已去掉"个"的数量词：{@code ""}（纯"半"）、{@code "半"}、
     *                {@code "一个半"} 已归一为 {@code "一半"}、{@code "40"}、{@code "四十"}
     * @return 秒数；无法解析返回 null
     */
    private static Long amountToSeconds(String numText, String unit) {
        long unitSec;
        if ("小时".equals(unit) || "钟头".equals(unit)) unitSec = 3600L;
        else if ("秒".equals(unit)) unitSec = 1L;
        else unitSec = 60L;                                  // 分钟 / 分

        boolean half = numText != null && numText.endsWith("半");
        String baseText = half ? numText.substring(0, numText.length() - 1) : numText;

        long base = 0L;
        if (baseText != null && baseText.length() > 0) {
            Long n = NumberParser.parse(baseText);
            if (n == null) return null;
            base = n.longValue();
        }
        if (half) {
            // 纯"半" → 半个单位；"一个半" → 1.5 个单位
            return Long.valueOf(base * unitSec + unitSec / 2L);
        }
        if (base <= 0L) return null;
        return Long.valueOf(base * unitSec);
    }

    /**
     * 抹掉句中的相对时间短语。
     *
     * <p>用途：避免"N分钟后"被 {@code IntentClassifier} 的"A后B"复合句结构误判
     * （相对时间的"后"是状语，不是时序连接词）。
     */
    public static String stripRelative(String q) {
        if (q == null || q.length() == 0) return q;
        return RELATIVE.matcher(q).replaceAll("");
    }

    /**
     * 注入**用户消息**的相对时间换算提示。
     *
     * <p>模型拿到的是"已经算好的绝对时刻"，它只需要照填参数。这样做的理由：
     * 大模型的日期算术不可靠，而本机算这个是确定性的 —— **能算的绝不让模型猜**。
     *
     * @return 提示文本；句中没有相对时间时返回 {@code null}
     */
    public static String hint(String query) {
        Relative r = parseRelative(query);
        if (r == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("【时间换算（本机时钟已替你算好，务必直接采用）】\n");
        sb.append("本机当前时间：").append(describe(now())).append("。\n");
        sb.append("用户说的“").append(r.raw).append("”＝ ")
          .append(describe(r.target)).append("，即 ").append(r.hhmm())
          .append("（24 小时制）。\n");
        sb.append("凡是需要填时间参数（hour/minute/seconds 等）的地方，**必须**用上面算好的数值，");
        sb.append("不要自己心算，也**不要反问用户“现在几点”**；时间已经在手上了，直接执行。\n");
        return sb.toString();
    }

    /**
     * 供 LLM 使用的完整用户消息体：把时间换算提示拼在原始问题**前面**。
     *
     * <p>放前面而不是后面，是因为模型对靠近结尾的指令服从度更高、
     * 而问题文本本身较短 —— 把它夹在"提示 + 问题"之间最稳。
     */
    public static String withHint(String query) {
        String h = hint(query);
        if (h == null) return query;
        return h + "————\n【用户原话】" + query;
    }
}
