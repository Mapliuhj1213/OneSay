package com.onesay.assistant.v2.router;

/**
 * 语音热路径「离线可执行性探针」。
 *
 * 存在意义（2026-10-02 缺陷修复）：
 * 唤醒链路（VAD 静音断句、一句式直说切分）需要在**麦克风采集线程**上判断
 * “这句话到底能不能被处理”。此前只调用 L0RuleRouter，导致所有 Ask 问答
 * 与 L1 记忆技能被判定为“非指令”而丢弃。
 *
 * 铁律：本探针**严禁触网**。只允许 Ask（本地问答）+ L0（本地规则）+ L1（本地 SQLite），
 *      绝不允许把 L2 云端大模型编译器挂到实时语音链路上。
 */
public interface IOfflineProbe {

    /** 该话语能否被离线链路（Ask / L0 / L1）直接处理 */
    boolean canHandle(String utterance);

    /**
     * 命中说法的字面量长度。
     * 返回 0 表示无字面量命中（不参与一句式切分的择优）。
     */
    int literalLength(String utterance);
}
