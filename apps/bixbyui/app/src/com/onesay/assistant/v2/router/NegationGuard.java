package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * ★ rel-1118 B04：「叫我别做 / 说完又反悔 / 只是在问」的句子永不在本地执行。
 *
 * <p>t20k 发现：否定判定（{@link TriageDecider#isNegatedCommand}）排在决策表里，而快路径（{@link FastIntents}）、
 * 多意图拆分、S3 定时任务、记一记这些本地接管都在它前面就把句子接走了 —— 「不许7点叫我」建了闹钟，
 * 「呼叫10086，算了不用了」拨号卡里的号码是「10086算不用」。这里集中判一次，三处入口（快路径分诊、决策表、
 * 插件本地接管）都先问它；命中就落 ASK（只回一句话、永不调工具）。
 *
 * <p>三类（纯函数、无状态，开源版可直接挑走）：
 * <ol>
 *   <li><b>否定开头</b>：别 / 不要 / 不用 / 不许 / 先别 / 我没说要 / 谁让你… + 动作动词（以「了」收尾的「不要提醒我了」是取消，照旧放行）；</li>
 *   <li><b>反悔收尾</b>：「，算了」「算了不用了」「个屁」「我只是问问」「当我没说」「开玩笑的」；</li>
 *   <li><b>只是在问</b>：「…要多久」「…会不会费电」「…对手机有害吗」「…的按钮在哪」「教我怎么…」「为什么会自动…」，
 *       （「为什么…」本身交给决策表：可能是设置诊断），以及高危动作的裸问句「关机吗」「卸载微信吗」。</li>
 * </ol>
 * 误杀的代价是多问一句，漏判的代价是真执行，所以拿不准就算命中；但「能帮我锁屏吗」这类礼貌请求不算（不是以动作开头）。
 */
public final class NegationGuard {

    private NegationGuard() {}

    public static final String NEG = "0n-negated", RETRACT = "0n-retract", QUESTION = "0n-question";

    /** 动作动词（与原 NEGATED_CMD 同表，补了息屏 / 记 / 建 / 呼叫 / 拨打 / 叫我）。 */
    private static final String VERBS = "(?:打开|开启|关闭|关掉|删除|删掉|静音|发送|发|打给|打电话|拨打|拨|呼叫|调大|调小|调高|调低|调到|调成|调|设置|设|定|提醒|叫我|叫|播放|放|拍|截|锁|重启|关机|卸载|安装|下载|买|付|转账|启动|退出|息屏|记|建|新建|开|关|删)";

    /** 否定词开头 + 至多 10 个字 + 动作动词。 */
    private static final Pattern NEG_HEAD = Pattern.compile(
            "^(?:先|千万|可|你|请|暂时|手机|我|现在)?(?:别|不要|不用|甭|不许|不准|不必|禁止|不需要|不想|没让你|没叫你|没说要|没说|不是让你|不是叫你|谁让你|谁叫你)(?!忘|停)"
            + "(?:再|帮我|给我|去|要|把)?.{0,10}?" + VERBS);
    /** 单字否定（不 / 莫 / 勿）只认紧跟动词，免得「不开心」「勿扰模式」被拦。 */
    private static final Pattern NEG_HEAD1 = Pattern.compile("^(?:不|莫|勿)(?!开心|扰|关心|记得)[0-9零一二两三四五六七八九十点分半刻秒钟后]{0,8}" + VERBS);

    /** 反悔收尾（在原句上判，要求前面有分隔符的那几种避免误伤「关机算了」这类口头禅）。 */
    private static final Pattern RETRACT_TAIL = Pattern.compile(
            "(?:[，,。.？?！!、~～\\s]+(?:不[，,。\\s]*)?算了(?:吧|啦)?(?:[，,\\s]*不用了?)?"
            + "|算了[，,\\s]*不用了?|[，,。\\s]不用了"
            + "|个屁|(?:我)?只是(?:问问|随便问问|问一下)(?:而已)?|随便问问|当我没说|开玩笑(?:的|呢|而已)?|逗你(?:的|呢)?)$");

    /** 只是在问（在归一化后的句子上判）。 */
    private static final Pattern Q_TAIL = Pattern.compile(
            "(?:要多久|需要多久|多长时间|会不会[^，]{0,6}|对[^，]{0,6}有害吗|有害吗|有危害吗|的按钮在哪(?:里|儿)?|在哪(?:里|儿)?设置|的快捷键是什么|会怎样|会怎么样|这几个字怎么写|是什么意思|有什么用)$");
    private static final Pattern Q_HEAD = Pattern.compile(
            "^(?:教我怎么|教我如何|怎么才能|为什么会?自动[0-9零一二两三四五六七八九十点分半刻]+|我刚才|刚才是不是|昨天忘了|谁让|谁)");
    /** 高危动作的裸问句：「关机吗」「卸载微信吗」（礼貌请求「能帮我关机吗」不以动词开头；「…好吗 / 可以吗」是商量口气的请求，都不在此列）。 */
    private static final Pattern DANGER_Q = Pattern.compile(
            "^(?:关机|重启|恢复出厂|格式化|卸载|删除|删掉|清空|拨打|打电话|呼叫|发短信|转账|付款|支付)[^，]{0,16}(?<!好|可以|行|成|能)(?:吗|么)$");

    /** @return 命中的规则名（{@link #NEG} / {@link #RETRACT} / {@link #QUESTION}），没命中 = null */
    public static String reason(String utterance) {
        if (utterance == null) return null;
        String raw = utterance.replaceAll("[\\u200b-\\u200f\\u2060\\ufeff]", "").replace('\u00a0', ' ').replace('\u3000', ' ').trim();
        raw = raw.replaceAll("[。！!？?…\\s~～🙏]+$", "");
        if (raw.isEmpty()) return null;
        if (RETRACT_TAIL.matcher(raw).find()) return RETRACT;
        String q = TriageDecider.normalize(raw);
        if (q.isEmpty()) return null;
        if (isNegHead(q)) return NEG;
        if (Q_TAIL.matcher(q).find() || Q_HEAD.matcher(q).find() || DANGER_Q.matcher(q).find()) return QUESTION;
        return null;
    }

    public static boolean blocks(String utterance) {
        return reason(utterance) != null;
    }

    static boolean isNegHead(String q) {
        boolean head = NEG_HEAD.matcher(q).find() || NEG_HEAD1.matcher(q).find();
        if (!head) return false;
        boolean who = q.startsWith("谁让你") || q.startsWith("谁叫你");
        if (!who && (q.endsWith("了") || q.endsWith("了吧"))) return false;   // 「不要提醒我了」= 取消，照旧
        if (com.onesay.assistant.v2.s3.S3Parser.isStopRemind(q)) return false;           // AL-07：「别提醒我」= 明确取消
        return true;
    }
}
