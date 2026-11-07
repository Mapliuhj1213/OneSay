package com.onesay.assistant.v2.soul;

import java.util.List;

/**
 * [soul] 会话结束那一次 LLM 调用的提示词（无工具、只产候选 JSON，结果只进 daily_log）。
 * 依据研究 §6.3-2（OpenClaw 压缩前静默 flush + Muse quiet-moment pass）与 §6.5（敏感只记存在、不建他人档案）。
 * 只把<b>用户本人说的话</b>交给模型（不给助手答复、不给识屏 / 搜索资料），候选的 quote 还要回到原话里核对。
 */
public final class SoulPrompts {

    private SoulPrompts() {}

    /** 本会话用户至少说了几句才值得整理。 */
    public static final int FLUSH_MIN_TURNS = 3;
    public static final int FLUSH_MAX_TOKENS = 400;

    public static final String FLUSH_SYSTEM =
            "你是记忆整理员。下面是用户这次对语音助手说的话（只有用户的原话）。\n"
          + "找出值得长期记住的、关于用户本人的稳定信息：事实（fact）、偏好（pref）、约定或待办（commit）、对助手的长期要求（directive）。\n"
          + "规则：\n"
          + "1. 只记用户亲口说的、以后还用得上的；一次性的查询、闲聊、天气新闻、操作指令（打开某 App）都不记。\n"
          + "2. 密码、验证码、卡号、证件号、密钥、token 只写「有这么一项」，绝不写值。\n"
          + "3. 不给别人建档：提到别人只记和用户相关的事实（如「妈妈生日 3 月 5 日」），不记评价、矛盾、隐私。\n"
          + "4. quote 必须从用户原话里逐字摘一段（≥2 字），摘不出就不要这条。\n"
          + "5. 最多 5 条；没有值得记的就输出 []。\n"
          + "只输出 JSON 数组，不要别的文字：[{\"kind\":\"pref\",\"text\":\"不吃香菜\",\"quote\":\"我不吃香菜\",\"confidence\":0.8}]";

    public static String flushUser(List<String> utterances) {
        StringBuilder sb = new StringBuilder("【用户原话】\n");
        int i = 1;
        for (String u : utterances) {
            if (u == null || u.trim().length() == 0) continue;
            sb.append(i++).append(". ").append(MemoryText.clip(u.trim(), 200)).append("\n");
        }
        return sb.toString();
    }
}
