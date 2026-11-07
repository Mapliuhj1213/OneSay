package com.onesay.assistant.v2.diag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [diag] 症状句判定（纯函数）。由 {@code TriageDecider.diagnoseMatch} 调用（分诊表与路由挂钩用同一个结论）。
 *
 * <h3>判定顺序</h3>
 * <ol>
 *   <li>知识问句（「什么是勿扰模式」「wifi是什么」「…有什么用」）→ 不是诊断（留给 ASK）；</li>
 *   <li>「记一下…」开头 → 不是诊断（留给 S2 记忆库）；</li>
 *   <li>有显式动作动词（「把屏幕超时改成5分钟」「天太黑了屏幕刺眼帮我调低点亮度」）且没有强症状语气 → 不是诊断（原路由）；
 *       <b>R5</b>：「抱怨式否定」（「不提醒」「没声音」「听不到」「收不到」…，且句中没有祈使标记「帮我/把/以后/别/不要…」）
 *       与强症状语气同等对待——这时句中的动作词只是在描述症状（「微信来消息<u>不提醒</u>」「别人<u>打电话</u>我听不到」），不是要执行；</li>
 *   <li>方法问句（「自动旋转怎么关」「怎么关掉自动旋转」）且没有强症状语气 → 不是诊断（ASK）；</li>
 *   <li>命中知识表某个症状的触发短语 → 该症状；</li>
 *   <li>强症状语气（为什么/老是/总是/一直/怎么回事…）+ 可诊断领域词（屏幕/声音/通知/耗电/wifi…；<b>不含蓝牙</b>）→ 未知症状
 *       （读表里该领域的键，可选 LLM 从白名单里挑键）；</li>
 *   <li>其余 → 不是诊断。</li>
 * </ol>
 */
public final class DiagMatcher {

    private DiagMatcher() {}

    /** 判定结论。symptom 为 null 且 unknown=true = 未知症状。 */
    public static final class Match {
        public final DiagKnowledge.Symptom symptom;
        public final boolean unknown;

        Match(DiagKnowledge.Symptom symptom, boolean unknown) {
            this.symptom = symptom;
            this.unknown = unknown;
        }

        public String id() {
            return symptom != null ? symptom.id : "unknown";
        }

        @Override public String toString() {
            return "diag:" + id();
        }
    }

    static final Pattern KNOWLEDGE = Pattern.compile(
            "是什么|什么是|什么意思|啥意思|是啥|有什么用|有啥用|什么作用|原理|区别|什么叫|英文怎么说|怎么读");
    static final Pattern HOWTO = Pattern.compile(
            "怎么(设置|打开|开启|关闭|关掉|关|开|调|改|弄|用|设)|如何|在哪(里|儿)?(设置|打开|开启|关|开|找|调)|在哪里|教我");
    static final Pattern STRONG = Pattern.compile(
            "为什么|为啥|怎么回事|咋回事|怎么搞的|怎么老是|怎么总是|老是|总是|一直|动不动|经常|莫名其妙|是不是|自己"
            // ★ R13 真机：「屏幕怎么这么暗」被 3-howto-why 判成 ASK（答了一段 Windows 调亮度）—— 「怎么这么 / 咋这么 / 怎么突然 / 怎么变」是抱怨症状
            + "|怎么这么|咋这么|怎么那么|怎么突然|怎么变得?|怎么越来越"
            // ★ int/1102 审计：「热点开了电脑连不上」—— 「连不上 / 连不了 / 搜不到」是症状（审计里被当成动作进了 GUI）
            + "|连不上|连不了|搜不到|连接不上");
    /**
     * R5 抱怨式否定：感知/送达类谓词被否定 = 在描述症状。只看谓词形状，不看具体 App / 领域（领域仍由触发短语或 DOMAINS 决定）。
     */
    static final Pattern COMPLAINT = Pattern.compile(
            "(不|没有?|没)(提醒|提示|响|响铃|弹出?|震|振|显示|声音|声了|动静|反应)"
            + "|(听|看)不(到|见|清)|(收|接)不到|没(收到|听到|看到)");
    /** 祈使 / 指令标记：出现任一个就不是抱怨（「以后不要提醒我」「别响了」「把通知设成不弹出」「帮我关掉提醒」）。 */
    static final Pattern IMPERATIVE = Pattern.compile(
            "帮我|请|麻烦|把|让|叫|以后|今后|别(?!人|的)|不要|不用|不需要|无需|设置|设成|设为|改成|改为|调成|调到|关掉|关闭|打开|开启|取消|停止|提醒我");
    static final Pattern MEMORY = Pattern.compile("^(记一下|记住|记下来|记下|记个|记着|备忘)");
    /** 未知症状可诊断的领域词（刻意不含蓝牙：「为什么蓝牙连不上」留给 ASK，表里没有蓝牙的可读项）。 */
    static final String[] DOMAINS = {
            "屏幕", "亮度", "熄屏", "息屏", "黑屏", "亮屏", "声音", "铃声", "来电", "音量", "通知", "振动", "震动",
            "耗电", "电池", "掉电", "电量", "发烫", "发热", "wifi", "wlan", "无线网", "旋转", "横屏", "字体", "字号",
            "勿扰", "免打扰", "定位", "gps",
    };

    /** 撤销口令（只有存在 10 分钟内的撤销记录时才生效，见 DiagHooks）。 */
    static final Pattern UNDO = Pattern.compile(
            "^(把)?(刚才|刚刚|上一步|上次)?(的|那个|那项)?(设置|改动|修改)?(撤销|撤回|改回去|改回来|还原|恢复原样|恢复原来的?(设置)?|恢复)(一下)?(刚才|刚刚)?(的|那个)?(设置|改动|修改)?(吧|了|啊|呗)?$"
            + "|^(改回|恢复成|还原成)(原来|之前)的?(设置|样子)?(吧|了)?$|^不要了改回去$");

    /**
     * @param normalized  {@code TriageDecider.normalize} 之后的句子
     * @param core        剥掉礼貌框架后的核心短语
     * @param explicitAct 句中是否有显式动作动词（由 TriageDecider 的动作词表判）
     */
    public static Match match(String normalized, String core, boolean explicitAct) {
        String q = normalized == null ? "" : normalized;
        if (q.length() < 2) return null;
        if (KNOWLEDGE.matcher(q).find()) return null;
        if (MEMORY.matcher(q).find() || (core != null && MEMORY.matcher(core).find())) return null;
        boolean strong = STRONG.matcher(q).find() || isComplaint(q);
        if (explicitAct && !strong) return null;
        if (HOWTO.matcher(q).find() && !strong) return null;
        for (DiagKnowledge.Symptom s : DiagKnowledge.SYMPTOMS) {
            for (Pattern p : s.triggers) {
                if (p.matcher(q).find()) return new Match(s, false);
            }
        }
        if (strong && hasDomain(q)) return new Match(null, true);
        return null;
    }

    /** R5：抱怨式否定且没有祈使标记（「微信来消息不提醒」「别人打电话我听不到」）。 */
    public static boolean isComplaint(String q) {
        return q != null && COMPLAINT.matcher(q).find() && !IMPERATIVE.matcher(q).find();
    }

    public static boolean isUndo(String normalized) {
        return normalized != null && UNDO.matcher(normalized).find();
    }

    static boolean hasDomain(String q) {
        for (String d : DOMAINS) if (q.contains(d)) return true;
        return false;
    }

    /** 未知症状：按句中领域词取相关症状的键（没有任何领域命中 → 显示类兜底）。 */
    public static List<String> keysForUnknown(String normalized) {
        Set<String> out = new LinkedHashSet<String>();
        String q = normalized == null ? "" : normalized;
        for (DiagKnowledge.Symptom s : DiagKnowledge.SYMPTOMS) {
            for (String d : s.domains) {
                if (q.contains(d)) {
                    out.addAll(s.keyIds);
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            if (q.contains("屏幕") || q.contains("亮")) {
                out.addAll(DiagKnowledge.symptom("screen_off_fast").keyIds);
                out.addAll(DiagKnowledge.symptom("night_glare").keyIds);
            } else if (q.contains("电")) {
                out.addAll(DiagKnowledge.symptom("battery_drain").keyIds);
            } else {
                out.add("system/screen_off_timeout");
                out.add("global/zen_mode");
                out.add("global/low_power");
            }
        }
        return new ArrayList<String>(out);
    }
}
