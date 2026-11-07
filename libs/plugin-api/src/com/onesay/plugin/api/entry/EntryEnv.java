package com.onesay.plugin.api.entry;

/**
 * 宿主交给入口的运行环境（纯 Java）。Android 宿主里 {@link #androidContext()} 是 {@code android.content.Context}，
 * 入口插件需要时自己强转；JVM 单测里可以是 null。
 */
public interface EntryEnv {

    /** Android Context（JVM 单测里为 null）。 */
    Object androidContext();

    /** 系统 API 级别（Build.VERSION.SDK_INT）。 */
    int sdkInt();

    /**
     * 宿主探测到的设备能力，名字由宿主约定（OneSay 用 {@code DeviceCap} 的枚举名，如 {@code ROOT}、{@code BIXBY_KEY}、
     * {@code MIC}、{@code DEFAULT_ASSISTANT}）。宿主不认识的名字返回 {@link EntryTrigger.Tri#UNKNOWN}。
     */
    EntryTrigger.Tri cap(String name);
}
