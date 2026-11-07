package com.onesay.assistant.v2.asr;

/**
 * r2/p0 P0-2（modaudit M2-1 + M2-2）：语音识别失败分型 + 面板文案（纯 Java，JVM 可测）。
 *
 * <p>以前除了鉴权失败，云端识别的所有失败（没配 Key、超时、5xx、429、DNS / 建连失败）一律显示「没听清」——
 * 用户明明说清楚了，却被告诉没听清；debug 包默认没有 ASR Key，每次冒烟都会撞上。现在按原因分开：
 * <ul>
 *   <li>{@link Kind#NOT_CONFIGURED} 没配语音识别 Key / 云端识别没开 → 「语音识别未配置」+「去配置」，不自动收起；</li>
 *   <li>{@link Kind#AUTH} 鉴权失败（401 / 403 / Token 拿不到）→ 「语音识别 Key 失效」+「去配置」，不自动收起；</li>
 *   <li>{@link Kind#TIMEOUT} / {@link Kind#RATE_LIMIT} / {@link Kind#SERVER} / {@link Kind#NETWORK} → 各自的说法；
 *       录音缓冲还在时带「重试」（用同一段录音再识别一次，最多一次）；</li>
 *   <li>{@link Kind#NO_SPEECH} 只有真的没识别出字（空结果 / 幻觉丢弃 / 音频太短）才说「没听清」。</li>
 * </ul>
 *
 * <p>隐私：只看错误串（通道给出的异常类名 / HTTP 状态），从不接触转写原文；{@link #logTag} 只给分类名。
 */
public final class AsrFailure {

    private AsrFailure() {}

    public enum Kind { NOT_CONFIGURED, AUTH, TIMEOUT, RATE_LIMIT, SERVER, NETWORK, NO_SPEECH }

    /** 面板芯片文案。 */
    public static final String CHIP_SETUP = "去配置";
    public static final String CHIP_RETRY = "重试";

    /** 控制台深链：语音识别 Key 设置（MainActivity page=console:asr.key → Console.openPage）。 */
    public static final String CONSOLE_PAGE = "asr.key";

    /**
     * 按通道给出的错误串分型（DashScopeAsrChannel / AliyunAsrChannel / HttpClient.errorText 的口径）。
     * 顺序：配置 → 鉴权 → 限流 → 服务端 → 超时 → 网络 → 空结果；认不出的非空错误按 {@link Kind#SERVER}（服务出错，不甩给用户的说法）。
     */
    public static Kind classify(String error) {
        if (error == null) return Kind.NO_SPEECH;
        String e = error.trim();
        if (e.length() == 0) return Kind.NO_SPEECH;
        String l = e.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("配置不完整") || e.contains("未启用") || e.contains("未配置")) return Kind.NOT_CONFIGURED;
        // 空结果 / 幻觉 / 音频问题：服务是好的，只是这段没有可用文字
        if (e.contains("返回为空") || e.contains("文本为空") || e.contains("幻觉") || e.contains("音频为空")
                || e.contains("音频过短") || e.contains("音频超长")) return Kind.NO_SPEECH;
        if (e.contains("鉴权失败") || l.contains("http 401") || l.contains("http 403") || l.contains("(http 401)")
                || l.contains("(http 403)") || e.startsWith("AUTH") || l.contains("invalidapikey") || l.contains("invalid api key")
                || l.contains("unauthorized")) return Kind.AUTH;
        if (l.contains("http 429") || l.contains("throttl") || l.contains("rate limit") || l.contains("ratelimit")
                || l.contains("too many requests")) return Kind.RATE_LIMIT;
        if (l.matches("(?s).*http 5\\d\\d.*")) return Kind.SERVER;
        if (l.contains("sockettimeout") || l.contains("timeout") || l.contains("timed out") || e.contains("超时")) return Kind.TIMEOUT;
        if (l.contains("unknownhost") || l.contains("unable to resolve host") || l.contains("connectexception")
                || l.contains("noroutetohost") || l.contains("socketexception") || l.contains("sslexception")
                || l.contains("sslhandshake") || l.contains("eofexception") || l.contains("ioexception")
                || l.contains("network is unreachable") || e.contains("网络异常")) return Kind.NETWORK;
        // Token 拿不到且不是网络原因 → 多半是 AK / AppKey 不对
        if (e.contains("无法获取 Token")) return Kind.AUTH;
        return Kind.SERVER;
    }

    /** 要用户去控制台改配置的两类：卡片不自动收起。 */
    public static boolean needsSetup(Kind k) {
        return k == Kind.NOT_CONFIGURED || k == Kind.AUTH;
    }

    /** 同一段录音再识别一次有意义的几类（服务 / 网络的临时问题）。 */
    public static boolean retryable(Kind k) {
        return k == Kind.TIMEOUT || k == Kind.RATE_LIMIT || k == Kind.SERVER || k == Kind.NETWORK;
    }

    /** 卡片标题（徽标）。 */
    public static String badge(Kind k) {
        switch (k) {
            case NOT_CONFIGURED: return "语音识别未配置";
            case AUTH: return "语音识别 Key 失效";
            case TIMEOUT: return "识别超时";
            case RATE_LIMIT: return "识别服务太忙";
            case SERVER: return "识别服务出错";
            case NETWORK: return "连不上识别服务";
            default: return "没听清";
        }
    }

    /**
     * 卡片正文。
     *
     * @param canRetry 录音还在、可以点「重试」
     */
    public static String line(Kind k, boolean canRetry) {
        String retry = canRetry ? "可以点「重试」用刚才的录音再识别一次，" : "";
        switch (k) {
            case NOT_CONFIGURED:
                return "还没配置语音识别 Key，听不懂说的话。点「去配置」填好就能用；也可以点「⌨ 文字」输入。";
            case AUTH:
                return "语音识别 Key 失效（鉴权失败），点「去配置」换一个新的 Key。";
            case TIMEOUT:
                return "网络有点慢，这句没识别出来。" + retry + (canRetry ? "或者再说一次。" : "再说一次试试。");
            case RATE_LIMIT:
                return "识别服务这会儿太忙，这句没识别出来。" + retry + (canRetry ? "或者稍等再说。" : "稍等再说一次。");
            case SERVER:
                return "识别服务出错，这句没识别出来。" + retry + (canRetry ? "或者再说一次。" : "再说一次试试。");
            case NETWORK:
                return "连不上语音识别服务（网络或域名解析失败）。" + retry + (canRetry ? "或者检查网络后再说。" : "检查网络后再说一次。");
            default:
                return "没听清，可以再说一次，或点「⌨ 文字」输入。";
        }
    }

    /** 卡片上的芯片。 */
    public static java.util.List<String> chips(Kind k, boolean canRetry) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (needsSetup(k)) out.add(CHIP_SETUP);
        else if (retryable(k) && canRetry) out.add(CHIP_RETRY);
        return out;
    }

    /** 日志用：只有分类名，不带原文。 */
    public static String logTag(Kind k) {
        return "[ASR-FAIL] " + k.name();
    }

    /**
     * 重试闸门（纯函数）：只在同一个会话、本窗还没执行过指令、录音还在、本窗还没重试过时允许。
     */
    public static boolean mayRetry(boolean hasPcm, int retryGen, int currentGen, boolean gotCommand, boolean alreadyRetried) {
        return hasPcm && retryGen >= 0 && retryGen == currentGen && !gotCommand && !alreadyRetried;
    }

    /**
     * 凭据指纹（只存哈希前 12 位，不存 Key 本身）：记下「哪一个 Key 被判过失效」，换了 Key 指纹不同就不再报失效。
     */
    public static String fingerprint(String credential) {
        if (credential == null || credential.trim().length() == 0) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(credential.trim().getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(credential.trim().hashCode());
        }
    }

    /** 记录的失效指纹与当前凭据指纹一致 = 当前 Key 仍是失效的那一个。 */
    public static boolean keyStillInvalid(String recordedFp, String currentFp) {
        return recordedFp != null && recordedFp.length() > 0 && recordedFp.equals(currentFp);
    }
}
