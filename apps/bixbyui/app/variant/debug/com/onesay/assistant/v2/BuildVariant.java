package com.onesay.assistant.v2;

/**
 * 构建变体常量（debug：com.onesay.assistant.debug）。build_linux.sh 按 DEBUG 选 app/variant/release 或 app/variant/debug 里的同名类编译。
 *
 * <p><b>只准放编译期常量</b>（static final 基本类型 / String 字面量）：javac 会把它们内联进引用处，
 * 所以 release 的 classes.dex 与引入本类之前逐字节一致；build_linux.sh 编译后会检查没有任何类
 * 真正引用本类（常量池里出现 com/onesay/assistant/v2/BuildVariant 就失败），然后把 BuildVariant.class 从 dex 输入里删掉。
 * 两个变体的字段名必须一一对应（BuildVariantTest 会比对）。
 *
 * <p>引用时写全限定名 {@code com.onesay.assistant.v2.BuildVariant.X}、不加 import、不改行数 —— 行号表一变，dex 的调试信息就不再一致。
 */
public final class BuildVariant {
    private BuildVariant() { }

    /** 变体名。 */
    public static final String VARIANT = "debug";
    /** 是否 debug 变体（com.onesay.assistant.debug）。 */
    public static final boolean IS_DEBUG = true;
    /** applicationId（安装包名）。Java 包名永远是 com.onesay.assistant，不随变体变。 */
    public static final String APPLICATION_ID = "com.onesay.assistant.debug";
    /** adb shell 可写的 kill-switch 根目录：debug 用自己的子目录 /data/local/tmp/com.onesay.assistant.debug/bixby_&lt;名&gt;，和 release 互不影响。 */
    public static final String KILL_TMP_ROOT = "/data/local/tmp/com.onesay.assistant.debug";
    /** 写到公共存储（相册 / Pictures）的目录名。 */
    public static final String PUBLIC_DIR = "OneSay-Debug";
    /** 写到公共存储的固定文件名里插入的标记（debug：bixbyui_debug_shot.png）。 */
    public static final String PUBLIC_FILE_TAG = "_debug";
    /** 界面上的应用名（控制台大标题 / 前台服务通知）。 */
    public static final String APP_TITLE = "OneSay Debug";
    /** 带 v2 的旧称（通知标题沿用）。 */
    public static final String APP_TITLE_V2 = "OneSay Debug";
}
