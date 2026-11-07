package com.onesay.assistant.v2.callback;

import com.onesay.assistant.v2.jobs.JobStore;

/** 回调历史页的文字（纯函数，JVM 可测）。 */
public final class CallbackHistory {

    private CallbackHistory() {}

    /** {状态文字, 色调 ok/warn/muted}。 */
    public static String[] status(JobStore.Outbox o) {
        switch (o.status) {
            case JobStore.Outbox.DELIVERED: return new String[]{"已送达", "ok"};
            case JobStore.Outbox.EXPIRED: return new String[]{"过期未播", "warn"};
            case JobStore.Outbox.SUPPRESSED: return new String[]{"没打扰", "muted"};
            default: return new String[]{"待送达", "muted"};
        }
    }

    /** 副标题：渠道 + 一行内容。 */
    public static String line(JobStore.Outbox o) {
        String via = o.deliveredVia.length() == 0 ? "" : "[" + o.deliveredVia.replace("island", "岛").replace("tts", "播报").replace("notify", "通知")
                .replace("merged", "合并") + "] ";
        String t = o.text == null ? "" : o.text.replace('\n', ' ');
        return via + (t.length() > 60 ? t.substring(0, 59) + "…" : t);
    }
}
