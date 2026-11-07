package com.onesay.assistant.v2.soul;

/**
 * [soul] 每轮注入预算（按字符计，中文 1 字 = 1 字符）。
 *
 * <p>默认值取自 SOUL-MEMORY-RESEARCH §6.2（[推断] 起点，需真机 + 计费观察再调）：
 * 人设 400、画像 300、核心记忆 600、检索命中 3 条 × 120、合计 ≤ 1500。
 * token 估算按 DeepSeek 官方「1 个中文字符 ≈ 0.6 token，1 个英文字符 ≈ 0.3 token」
 * （https://api-docs.deepseek.com/quick_start/token_usage ）。
 */
public final class SoulBudget {

    public int soulMax = 400;
    public int profileMax = 300;
    public int coreMax = 600;
    public int hitMax = 120;
    public int hits = 3;
    public int totalMax = 1500;
    /** 单条画像 / 核心记忆注入时的长度上限：超过截到这个长度（尾部「…」）注入，全文仍可检索（★ device-1102：旧版是整条不注入）。 */
    public int itemMax = 80;
    /** 多少天没被确认 / 命中就停止注入（研究 §6.3：90 天）。 */
    public int retireDays = 90;
    /** 日志检索半衰期（研究 §2.1 §6.4：30 天）。 */
    public int logHalfLifeDays = 30;

    public static SoulBudget defaults() { return new SoulBudget(); }

    /** 「soul=400,profile=300,core=600,hit=120,hits=3,total=1500」这类覆盖串（控制台 / debug 调参）；坏值忽略。 */
    public static SoulBudget parse(String spec) {
        SoulBudget b = new SoulBudget();
        if (spec == null) return b;
        for (String kv : spec.split("[,;\\s]+")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = kv.substring(0, i).trim();
            int v;
            try {
                v = Integer.parseInt(kv.substring(i + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (v < 0 || v > 20000) continue;
            if ("soul".equals(k)) b.soulMax = v;
            else if ("profile".equals(k)) b.profileMax = v;
            else if ("core".equals(k)) b.coreMax = v;
            else if ("hit".equals(k)) b.hitMax = v;
            else if ("hits".equals(k)) b.hits = Math.min(v, 10);
            else if ("total".equals(k)) b.totalMax = v;
            else if ("item".equals(k)) b.itemMax = v;
            else if ("retire".equals(k)) b.retireDays = v;
            else if ("halflife".equals(k)) b.logHalfLifeDays = Math.max(1, v);
        }
        return b;
    }

    public String spec() {
        return "soul=" + soulMax + ",profile=" + profileMax + ",core=" + coreMax + ",hit=" + hitMax + ",hits=" + hits
                + ",total=" + totalMax + ",item=" + itemMax + ",retire=" + retireDays + ",halflife=" + logHalfLifeDays;
    }

    /** DeepSeek 口径的 token 估算：CJK 0.6、其它 0.3（向上取整）。 */
    public static int estimateTokens(String s) {
        if (s == null || s.length() == 0) return 0;
        double t = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            t += (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF) ? 0.6 : 0.3;
        }
        return (int) Math.ceil(t);
    }
}
