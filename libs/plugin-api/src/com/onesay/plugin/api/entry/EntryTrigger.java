package com.onesay.plugin.api.entry;

/**
 * 唤起入口插件接口（纯 Java，不依赖 android.*）。
 *
 * <p>一个入口只负责一件事：「用户通过某种方式要求唤起助手」时调用 {@link SummonHost#summon}。
 * 录音、面板、权限、对话都由宿主负责，入口不碰。
 *
 * <h3>两类入口</h3>
 * <ul>
 *   <li>{@link Kind#SYSTEM}：由系统或桌面投递（默认数字助理、快捷开关、桌面小部件、桌面快捷方式、公开 Intent）。
 *       组件声明在宿主 Manifest 里；{@link #start}/{@link #stop} 通常什么都不做，宿主可以借 stop 停用组件。</li>
 *   <li>{@link Kind#LISTENER}：需要宿主常驻服务主动监听（本地唤醒词、厂商按键）。{@link #start} 开始监听，{@link #stop} 停止。</li>
 * </ul>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@link #id()} 稳定、全小写，内置入口以 {@code entry.} 开头，厂商入口以 {@code vendor.<厂商>.} 开头；</li>
 *   <li>入口只经 {@link EntryRegistry#fire} 唤起宿主 —— 注册表会先检查用户是否打开了这个入口，关着的入口触发一律丢弃；</li>
 *   <li>{@link #isAvailable} 必须便宜、不抛异常、不弹授权框。</li>
 * </ul>
 */
public interface EntryTrigger {

    enum Kind { SYSTEM, LISTENER }

    enum Tri { YES, NO, UNKNOWN }

    /** 唤起方式：语音（开麦聆听）或文字（只打开面板）。 */
    enum Mode { VOICE, TEXT }

    /** 稳定 id，如 {@code entry.qs_tile}。 */
    String id();

    /** 设置页显示的名字。 */
    String label();

    /** 一句话说明（怎么用、需要什么）。 */
    String description();

    Kind kind();

    /** 用户没动过开关时是否开启。会开麦或可被别的 App 触发的入口必须默认关。 */
    boolean defaultEnabled();

    /** 这台设备上能不能用。 */
    Tri isAvailable(EntryEnv env);

    /** 不可用 / 未确认时给用户看的原因；可用时返回 null。 */
    String unavailableReason(EntryEnv env);

    /**
     * 开始工作（入口被打开且可用时由宿主调用）。LISTENER 入口在这里开始监听，收到触发后调
     * {@code EntryRegistry.fire(id(), mode)}。返回 false 表示没能启动（不抛异常）。
     */
    boolean start(EntryEnv env);

    /** 停止（可重复调用）。 */
    void stop(EntryEnv env);
}
