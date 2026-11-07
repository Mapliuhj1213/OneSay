package com.onesay.assistant.v2.trip;

import java.util.regex.Pattern;

/**
 * ★ trip-decompose（2026-11-04，PLAN-trip-decompose）：「安排 / 规划 / 帮我搞定 / 准备一下」+ 出行 / 日期 / 地点 = 复合任务。
 * 分诊、日程本地层、快路径共用这一个判定（纯函数）。命中后走 AgentLoop 规划模式，规划模式内禁止 UNKNOWN_CAPABILITY。
 */
public final class TripIntent {
    private TripIntent() {}

    static final Pattern PLAN_VERB = Pattern.compile(
            "安排一?下|安排好|帮我安排|给我安排|规划|计划一?下|帮我搞定|搞定一?下|准备一?下|帮我准备|准备好|打点一?下|张罗一?下|筹备|攻略");
    static final Pattern TRAVEL = Pattern.compile(
            "出行|出差|旅行|旅游|度假|出游|自驾|去.{1,8}(玩|逛|旅|出差|开会|看|过年|度假)|行程|机票|酒店|飞(去|到)|回老家|探亲|签证");
    static final Pattern GO_PLACE = Pattern.compile("(去|到|飞|回)[\\u4e00-\\u9fa5A-Za-z]{2,8}");
    static final Pattern DATE = Pattern.compile(
            "下个?月|下周|下星期|这周末|周末|月底|月初|月中|国庆|五一|十一|春节|元旦|清明|端午|中秋|暑假|寒假|明天|后天|大后天|[0-9一二三四五六七八九十]+(号|日|月)|星期.|周[一二三四五六日天]");
    /** 明确不是出行（「安排一下会议」「准备一下发言稿」）。 */
    static final Pattern NOT_TRIP = Pattern.compile("会议|开会时间|发言稿|PPT|ppt|作业|考试|面试题|晚饭|午饭|早饭|菜");

    public static boolean isTrigger(String utterance) {
        if (utterance == null) return false;
        String q = utterance.replaceAll("\\s+", "");
        if (q.length() < 5) return false;
        if (!PLAN_VERB.matcher(q).find()) return false;
        boolean travel = TRAVEL.matcher(q).find();
        if (!travel && NOT_TRIP.matcher(q).find()) return false;
        boolean place = GO_PLACE.matcher(q).find();
        boolean date = DATE.matcher(q).find();
        return travel || (place && date);
    }

    /** 规划模式的固定子目标（技能文件 skill_trip.md 同口径；岛上逐项显示进度）。 */
    public static final String[] SUBGOALS = {
            "解析日期（回程/人数/出发城市只问一次，可选「你决定」）",
            "日历建出行事件 + 前晚收拾行李提醒 + 当天闹钟",
            "调研机票价格区间与直飞（标来源，只推荐不下单）",
            "调研酒店区域与价格区间（标来源，只推荐不下单）",
            "天气 / 入境要求 / 汇率 / 插头 / eSIM",
            "去机场路线与耗时",
            "行李清单"
    };

    /** 空洞拒绝：没说卡在哪个子目标、缺什么，就说「做不了 / 没接口」。 */
    static final Pattern HOLLOW = Pattern.compile("做不了|干不了|没接口|没有接口|无法完成|不支持|无能为力|办不到|帮不了");
    static final Pattern SPECIFIC = Pattern.compile("子目标|卡在|缺少|缺|需要你|已完成|已经|✓|√|第.项");

    public static boolean isHollowRefusal(String reply) {
        if (reply == null) return false;
        String r = reply.trim();
        if (!HOLLOW.matcher(r).find()) return false;
        return !SPECIFIC.matcher(r).find() || r.length() < 30;
    }

    /** 收工校验打回用的内部指令（只打回一次）。 */
    public static final String REDO_HINT = "[PLAN-CHECK] 这是复合任务，不能直接说做不了。先用 task.todo 拆成子目标（日期/日程闹钟/机票/酒店/天气入境汇率/路线/清单），"
            + "逐项用现有工具尝试（日程、闹钟、web.search、research.spawn）；只有逐项试过仍失败，才说明具体卡在哪个子目标、缺什么。花钱的只推荐不下单。";

    public static final String PLAN_MODE_HINT = "[PLAN-MODE] 用户要你安排一次出行。按 trip 技能拆子目标并逐项推进；本模式不允许回复「做不了 / 没接口」，"
            + "某项失败只在该项写明缺什么，其他项照做。回程/人数/出发城市只问一次，给「你决定」选项。";
}
