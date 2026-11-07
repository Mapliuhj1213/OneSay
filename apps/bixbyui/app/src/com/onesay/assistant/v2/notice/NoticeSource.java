package com.onesay.assistant.v2.notice;

import java.util.List;

/**
 * 扩展接口：以后接入别的提醒来源（快递、航班、某个 App 的事件……）只要实现这个接口并 {@code NoticeHooks.register}。
 * 中心统一负责：去重、过期、优先级排队、免打扰 / 通话中、每日上限、持久化、上岛。
 *
 * <p>两种用法：
 * <ul>
 *   <li><b>拉</b>：中心到点调 {@link #poll}；返回 null = 这次拿不到（离线 / 没配置），中心不动已有提醒；
 *       返回列表 = 这个来源当前应有的全部提醒（中心会撤掉列表里没有的、还没处理的旧提醒——日程被删、任务被改）。</li>
 *   <li><b>推</b>：自己调 {@code NoticeHooks.post(ctx, notice)}（{@link #nextPollAt} 返回 -1、{@link #poll} 返回 null 即可）。</li>
 * </ul>
 */
public interface NoticeSource {

    /** 来源 id（写进 {@link IslandNotice#sourceTag}，动作回调按它找回来）。 */
    String id();

    IslandNotice.Source kind();

    /** 这个来源的开关键（控制台「各来源开关」用）；null = 只受总开关。 */
    String switchKey();

    /** 下一次需要 poll 的时刻；-1 = 不需要定时 poll。 */
    long nextPollAt(long now);

    List<IslandNotice> poll(long now) throws Exception;

    /** {@link NoticeAction.Kind#CUSTOM} 动作；返回是否处理了。 */
    boolean onAction(IslandNotice n, NoticeAction a);

    /**
     * 标记接口（2026-10-23 BUG-I1，同日收窄）：实现它的拉型来源在 {@link #poll} 抛 {@link Unavailable}
     * （<b>连接器缺失 / 未登录 / 授权失效</b>）时，中心把<b>这个来源</b>（kind + id）所有没处理的提醒撤下
     * （还没展示的、已经在岛上的都撤；别的来源不动），记 WITHDRAWN。来源恢复后下一次 poll 拿到同一个去重键，没过期的会重新进队。
     * 普通的网络失败 / 超时 / 远端错误（poll 返回 null 或抛别的异常）<b>不撤</b>：已有提醒保留，来源自己安排重试。
     * 没实现它的来源保持原约定：null / 异常 = 什么都不动。
     */
    interface WithdrawWhenUnavailable {
    }

    /** 来源「不可用」（连接器缺失 / 未登录 / 授权失效）：和一次网络失败区分开，只有这种才撤提醒（BUG-I1 收窄）。 */
    final class Unavailable extends Exception {
        public Unavailable(String why) {
            super(why);
        }
    }
}
