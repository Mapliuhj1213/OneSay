package com.onesay.assistant.v2.platform;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 唤醒入口抽象（refactor/oss 2026-11-06）：按键唤醒、悬浮球、语音唤醒词、默认助理手势……
 *
 * <p>现状：已实现的入口仍由原代码直接接线（无障碍服务里的按键唤醒、KWS 循环、VoiceInteractionService），
 * 这里先给出统一的描述与能力声明，供能力页展示和移植时逐个换成实现类。
 * 后续实现类的约定：入口只负责「发生了一次唤醒」并调用 {@link Sink}，不碰录音和面板。
 */
public interface WakeEntry {

    /** 入口收到一次触发后回调宿主（主线程或后台线程均可，宿主自行切线程）。 */
    interface Sink {
        /** @param source 入口 id；@param kind 短按 / 长按 / 双击等，按入口自定义（如 {@code KeyTrigger.Kind} 的名字） */
        void onWake(String source, String kind);
    }

    /** 稳定 id（与 {@link FeatureRegistry} 里的功能 id 一致，如 {@code wake.key}）。 */
    String id();

    /** 需要的设备能力。 */
    List<DeviceCap> requires();

    /** 开始监听；不可用时返回 false 且不抛异常。 */
    boolean start(Sink sink);

    /** 停止监听（可重复调用）。 */
    void stop();

    /** 内置入口的静态描述（不含实现），按优先级排列。 */
    final class Builtin {
        private Builtin() {}

        public static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList(
                "wake.key", "wake.kws", "wake.assist", "wake.floating"));
    }
}
