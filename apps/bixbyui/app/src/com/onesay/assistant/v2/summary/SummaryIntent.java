package com.onesay.assistant.v2.summary;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [summary] #18「总结这页」意图（L0 规则 / 分诊 / 面板挂钩共用这一个判定）。纯 Java，JVM 可测。
 *
 * <h3>触发（保守：要么是光秃秃的「总结一下」，要么宾语是<b>屏幕上的东西</b>）</h3>
 * <ul>
 *   <li>光动词：总结一下 / 总结 / 帮我概括一下 / 归纳一下 / 提炼一下；</li>
 *   <li>动词 + 屏幕宾语：总结这页 / 总结一下这篇文章 / 概括一下聊天记录 / 总结一下屏幕上的内容 / 把这篇总结一下 / 提炼一下要点；</li>
 *   <li>问句：这篇讲了什么 / 这个页面说了啥 / 这篇文章主要讲的是什么 / 这个群在聊什么 / 这页的重点是什么；</li>
 *   <li>太长不看：太长不看 / 太长了不想看 / 太长了帮我总结 / tldr / 给我个摘要。</li>
 * </ul>
 * <h3>不触发（各有归属，原样走原路由）</h3>
 * 「总结我的记忆」（S2）、「总结今天的邮件」（邮件 / 定时任务）、日程 / 新闻 / 天气 / 账单；
 * 知识问句与写作（「总结一下二战的原因」「概括一下红楼梦」「年终总结怎么写」「帮我写个总结」）——
 * 宾语不是屏幕指代就不命中，另有排除词兜底。
 */
public final class SummaryIntent {

    private SummaryIntent() {}

    private static final String HEAD = "^(?:请|麻烦你?|帮忙|帮我|给我|你|能不能|可以|可不可以|快)*(?:帮我|给我|替我)?";
    private static final String VERB = "(?:总结|概括|归纳|提炼|概述|梳理|总结归纳|归纳总结|总结总结|概括概括)";
    private static final String SOFT = "(?:一下|下|一下子)?";
    private static final String END = "(?:吧|呗|啊|呀|哈|好吗|可以吗|行吗|嘛)?$";
    /** 屏幕宾语：这页 / 这篇（文章）/ 这个页面 / 当前页面 / 屏幕上的内容 / 聊天记录 / 这个群 / 上面的内容 / 全文…… */
    private static final String OBJ = "(?:"
            + "这(?:一)?(?:页|篇|段|些|条|章)(?:文章|帖子|内容|文字|话|东西|网页|页面|新闻稿)?"
            + "|这个(?:页面|网页|界面|帖子|文章|内容|聊天|群|群聊|对话|链接|公众号文章|推文|长文|帖)"
            + "|(?:当前|现在|目前)(?:的)?(?:页面|页|界面|内容|文章|网页|屏幕)"
            + "|屏幕(?:上)?(?:的)?(?:内容|文字|东西|这些)?"
            + "|(?:这些|上面|上边|前面)(?:的)?(?:内容|聊天|消息|文字|聊天记录|讨论)?"
            + "|(?:群里|群聊里?)(?:的)?(?:消息|聊天|讨论)?"
            + "|(?:聊天记录|对话记录|群聊记录|全文|本页|本文|此页|这文章)"
            + ")";
    /** 「…的要点 / 重点 / 大意」 */
    private static final String GIST = "(?:要点|重点|大意|主要内容|核心内容|核心|梗概|主旨|中心思想|关键信息)";

    /** 光动词：「总结一下」「帮我概括一下」「总结」。 */
    private static final Pattern P_BARE = Pattern.compile(HEAD + VERB + SOFT + END);
    /** 动词 + 宾语（宾语在后）：「总结这页」「总结一下这篇文章的要点」「提炼一下要点」。 */
    private static final Pattern P_VERB_OBJ = Pattern.compile(
            HEAD + VERB + SOFT + "(?:" + OBJ + "(?:的)?(?:" + GIST + ")?|" + GIST + ")(?:给我听)?" + END);
    /** 宾语在前：「把这篇总结一下」「这页概括一下」「聊天记录总结一下」。 */
    private static final Pattern P_OBJ_VERB = Pattern.compile(
            HEAD + "(?:把|将)?" + OBJ + "(?:的)?(?:" + GIST + ")?(?:给我|帮我)?" + VERB + SOFT + "(?:给我听)?" + END);
    /** 问句：「这篇讲了什么」「这个页面说了啥」「这个群在聊什么」「这页的重点是什么」。 */
    private static final Pattern P_QUESTION = Pattern.compile(
            "^" + OBJ + "(?:主要|大概|到底|都|究竟)?(?:在)?(?:讲|说|写|聊|谈|讨论)(?:了|的|的是|着)?(?:些)?(?:什么|啥|啥子|哪些)(?:内容|东西|事|事情|意思)?(?:呀|啊|呢|哦)?$"
            + "|^" + OBJ + "(?:的)?" + GIST + "(?:是)?(?:什么|啥|有哪些)(?:呀|啊|呢)?$"
            + "|^" + OBJ + "(?:是)?(?:什么意思|啥意思)(?:呀|啊|呢)?$");
    /** 太长不看：「太长不看」「太长了不想看」「太长了帮我总结」「tldr」「给我个摘要」。 */
    private static final Pattern P_TLDR = Pattern.compile(
            "^(?:太长不看|太长了?(?:我)?(?:不想看|懒得看|看不完|看不下去)(?:了)?(?:帮我)?(?:总结|概括)?(?:一下)?|太长了?(?:帮我|你)?(?:总结|概括)(?:一下)?|tldr|tl;dr)" + END
            + "|" + HEAD + "(?:来|出|做|生成|要|整)?(?:个|一个|一份|份)(?:摘要|概要|总结|要点)" + END);

    /** 排除：别的功能的宾语 / 写作 / 方法问句（兜底，主判定本来就要求屏幕宾语）。 */
    private static final Pattern EXCLUDE = Pattern.compile(
            "记忆|我记|记的|记过|备忘|笔记|邮件|邮箱|日程|日历|行程|账单|记账|花销|消费|新闻联播|天气|股票|年终|周报|日报|月报|工作总结|学习总结|心得"
            + "|写(?:个|一个|一份|一篇|篇|份)?(?:总结|摘要)|怎么(?:写|做)|如何|什么是|啥是|什么叫|视频|电影|这首歌|歌词");

    /** 归一：小写、去空白与标点（问号不保留，问句由句式判）。 */
    public static String normalize(String s) {
        if (s == null) return "";
        String q = s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’《》（）【】…·～~]", "");
        return q;
    }

    /** 是否是「总结这页」指令。 */
    public static boolean isTrigger(String utterance) {
        String q = normalize(utterance);
        if (q.length() < 2 || q.length() > 30) return false;
        // 「tl;dr」去标点后是 tldr
        if (EXCLUDE.matcher(q).find()) return false;
        return P_BARE.matcher(q).find() || P_VERB_OBJ.matcher(q).find() || P_OBJ_VERB.matcher(q).find()
                || P_QUESTION.matcher(q).find() || P_TLDR.matcher(q).find();
    }
}
