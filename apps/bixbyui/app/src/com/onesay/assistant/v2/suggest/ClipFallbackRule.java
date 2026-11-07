package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.plug.suggest.core.Outcome;
import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkScene;

/**
 * ★ feat/suggest：剪贴板 / 链接建议被岛拒绝时，要不要退回 S1 旧剪贴板卡片（纯函数，JVM 可测；SuggestHost 调）。
 * 只有「岛放不下」（host:… 忙 / 面板开着 / 非岛）和「克制档 / 静默」（quiet:…）才退回；频控、去重、开关关、超时、用户忽略都不退回，
 * 免得同一次复制弹两次。
 */
final class ClipFallbackRule {
    private ClipFallbackRule() {
    }

    static boolean wants(String sceneId, Outcome o, String reason) {
        if (o != Outcome.SUPPRESSED || reason == null) return false;
        if (!ClipScene.ID.equals(sceneId) && !LinkScene.ID.equals(sceneId)) return false;
        return reason.startsWith("host:") || reason.startsWith("quiet:");
    }

    /** 剪贴板卡带着 ClipboardWatcher 的芯片键；链接卡（payload 里的 token 表示「是否口令」）退回时用最近一次的芯片（null）。 */
    static String token(String sceneId, String payloadToken) {
        return ClipScene.ID.equals(sceneId) ? payloadToken : null;
    }
}
