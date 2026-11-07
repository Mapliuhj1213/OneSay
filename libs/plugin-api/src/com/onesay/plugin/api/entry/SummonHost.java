package com.onesay.plugin.api.entry;

/** 宿主实现：真正去唤起助手（开麦 / 打开面板）。只由 {@link EntryRegistry#fire} 调用。 */
public interface SummonHost {
    /**
     * @param source 入口 id（写进日志、给唤醒链路区分来源）
     * @param mode   语音或文字
     */
    void summon(String source, EntryTrigger.Mode mode);
}
