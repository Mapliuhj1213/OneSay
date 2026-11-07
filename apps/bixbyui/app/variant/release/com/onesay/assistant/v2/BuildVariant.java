package com.onesay.assistant.v2;

/**
 * 构建变体常量（release）。build_linux.sh 按 DEBUG 选 app/variant/release 或 app/variant/debug 里的同名类编译。
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
    public static final String VARIANT = "release";
    /** 是否 debug 变体（com.onesay.assistant.debug）。 */
    public static final boolean IS_DEBUG = false;
    /** applicationId（安装包名）。Java 包名永远是 com.onesay.assistant，不随变体变。 */
    public static final String APPLICATION_ID = "com.onesay.assistant";
    /** adb shell 可写的 kill-switch 根目录：release 沿用 /data/local/tmp（文件名 bixby_&lt;名&gt;）。 */
    public static final String KILL_TMP_ROOT = "/data/local/tmp";
    /** 写到公共存储（相册 / Pictures）的目录名。 */
    public static final String PUBLIC_DIR = "OneSay";
    /** 写到公共存储的固定文件名里插入的标记（release 为空：bixbyui_shot.png）。 */
    public static final String PUBLIC_FILE_TAG = "";
    /** 界面上的应用名（控制台大标题 / 前台服务通知）。 */
    public static final String APP_TITLE = "OneSay";
    /** 带 v2 的旧称（通知标题沿用）。 */
    public static final String APP_TITLE_V2 = "OneSay";
}
