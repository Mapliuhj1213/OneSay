package com.onesay.assistant.v2.kernel;

/**
 * [kernel] 分诊开关信号：插件声明「我打开时分诊才产出我的标签 / 规则」。
 *
 * <p>迁移前 UnifiedRouter.triage 里逐个读 5 个功能的开关；现在由内核按插件声明的 {@link FeaturePlugin#triageSignal()}
 * 汇总。没有注册对应插件时该信号为 false（等同功能关闭）。
 */
public final class TriageSwitches {

    /** 设置诊断：DIAGNOSE 标签。 */
    public static final String DIAG = "diag";
    /** S2 免前缀记忆：陈述句判成「记一下」。 */
    public static final String IMPLICIT_MEMORY = "implicit";
    /** 语音存图：6i-image-save。 */
    public static final String IMAGE_SAVE = "img";
    /** 长内容摘要：5s-screen-summary。 */
    public static final String SUMMARY = "sum";
    /** S3 定时任务 / 待办：6t / 5t。 */
    public static final String S3 = "s3";

    public final boolean diag;
    public final boolean implicit;
    public final boolean img;
    public final boolean sum;
    public final boolean s3;

    public TriageSwitches(boolean diag, boolean implicit, boolean img, boolean sum, boolean s3) {
        this.diag = diag;
        this.implicit = implicit;
        this.img = img;
        this.sum = sum;
        this.s3 = s3;
    }

    @Override
    public String toString() {
        return "diag=" + diag + " implicit=" + implicit + " img=" + img + " sum=" + sum + " s3=" + s3;
    }
}
