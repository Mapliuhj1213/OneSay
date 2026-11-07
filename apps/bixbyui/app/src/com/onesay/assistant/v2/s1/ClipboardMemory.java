package com.onesay.assistant.v2.s1;

import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [s1] 剪贴板的进程内短期记忆（纯 Java，JVM 可测；全部静态 + 同步）。
 *
 * <ul>
 *   <li><b>自家写入登记</b>：识屏「复制这个」、取件码浮卡「复制」等本 App 写剪贴板前调用 {@link #markOwnWrite}，
 *       剪贴板监听据此忽略这次变化 —— 防止「复制 → 弹芯片 → 再复制」的回环；</li>
 *   <li><b>最近一次外部复制的实体</b>：剪贴板监听在内容<b>通过敏感过滤后</b>调用 {@link #remember}；
 *       指代消解（「导航到刚复制的地址」/ 屏幕上没地址时的「导航去这里」）用 {@link #recentEntities} 取值，
 *       有效期 {@link #TTL_MS}（5 分钟）。只存抽出来的实体，不存原文。</li>
 * </ul>
 */
public final class ClipboardMemory {

    private ClipboardMemory() {}

    /** 剪贴板实体用于指代消解的有效期。 */
    public static final long TTL_MS = 5L * 60L * 1000L;
    /** 自家写入登记的有效期（监听回调一般在几十毫秒内到达，留足余量）。 */
    public static final long OWN_WRITE_WINDOW_MS = 5000L;

    private static String sOwnText;
    private static long sOwnAt;
    private static List<ScreenEntity> sEntities = Collections.emptyList();
    private static long sEntitiesAt;

    public static synchronized void markOwnWrite(String text, long now) {
        sOwnText = text;
        sOwnAt = now;
    }

    /** 这次剪贴板变化是不是本 App 刚写的。命中一次即作废（同样的文字之后由用户自己再复制，仍会被处理）。 */
    public static synchronized boolean consumeOwnWrite(String text, long now) {
        if (sOwnText == null || text == null) return false;
        boolean hit = sOwnText.equals(text) && now - sOwnAt >= 0 && now - sOwnAt <= OWN_WRITE_WINDOW_MS;
        if (hit) {
            sOwnText = null;
            sOwnAt = 0;
        }
        return hit;
    }

    public static synchronized void remember(List<ScreenEntity> entities, long now) {
        sEntities = entities == null ? Collections.<ScreenEntity>emptyList() : new ArrayList<ScreenEntity>(entities);
        sEntitiesAt = now;
    }

    /** 5 分钟内最近一次外部复制抽到的实体；过期或没有返回空列表。 */
    public static synchronized List<ScreenEntity> recentEntities(long now) {
        if (sEntities.isEmpty() || now - sEntitiesAt < 0 || now - sEntitiesAt > TTL_MS) return Collections.emptyList();
        return new ArrayList<ScreenEntity>(sEntities);
    }

    public static synchronized void clear() {
        sOwnText = null;
        sOwnAt = 0;
        sEntities = Collections.emptyList();
        sEntitiesAt = 0;
    }
}
