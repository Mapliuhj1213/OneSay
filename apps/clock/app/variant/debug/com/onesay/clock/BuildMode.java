package com.onesay.clock;

/** 构建变体开关：build_linux.sh 编 app/variant/debug，build_release.sh 编 app/variant/release（两份只差这一个常量）。 */
public final class BuildMode {
    public static final boolean RELEASE = false;
    private BuildMode() { }
}
