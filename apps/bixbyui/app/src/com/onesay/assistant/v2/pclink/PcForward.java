package com.onesay.assistant.v2.pclink;

import com.onesay.assistant.v2.notice.IslandNotice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * [pclink] 提醒中枢 → 电脑通知的转发口径（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>日程（CALENDAR，含本地日程）→ {@code reminder}；</li>
 *   <li>定时任务 / 自动任务（CALLBACK）：标题或正文带「提醒」→ {@code reminder}（定时任务提醒），否则 {@code auto}（自动任务结果）；</li>
 *   <li>分钟级降水（WEATHER）→ {@code rain}；</li>
 *   <li>其余来源（通话、验证码、系统状态、跑腿、邮件……）v1 不转发。</li>
 * </ul>
 * 只转「刚出现在提醒队列里」的那一次；同一个去重键在本进程里只发一次（{@link Dedup}）。电脑离线就丢，不排队。
 */
public final class PcForward {

    public static final String KIND_REMINDER = "reminder", KIND_AUTO = "auto", KIND_RAIN = "rain";

    private PcForward() {}

    /** 这条提醒转发成哪一类；null = 不转。 */
    public static String kindOf(IslandNotice n) {
        if (n == null || n.source == null) return null;
        switch (n.source) {
            case CALENDAR:
                return KIND_REMINDER;
            case CALLBACK: {
                String t = (n.title == null ? "" : n.title) + " " + (n.summary == null ? "" : n.summary);
                return t.contains("提醒") ? KIND_REMINDER : KIND_AUTO;
            }
            case WEATHER:
                return KIND_RAIN;
            default:
                return null;
        }
    }

    /** notify 指令参数（电脑端 local「notify」：kind / title / text）。 */
    public static Map<String, Object> args(String kind, IslandNotice n) {
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("kind", kind);
        a.put("title", clip(n.title, 80));
        a.put("text", clip(n.summary, 500));
        return a;
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 去重：同一个去重键只发一次（最近 200 个）。 */
    public static final class Dedup {
        private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > 200;
            }
        };

        public synchronized boolean first(String key) {
            if (key == null || key.length() == 0) return true;
            if (seen.containsKey(key)) return false;
            seen.put(key, Boolean.TRUE);
            return true;
        }
    }
}
