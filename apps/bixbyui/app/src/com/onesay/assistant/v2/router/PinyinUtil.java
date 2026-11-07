package com.onesay.assistant.v2.router;

/**
 * 拼音工具（只保留转写能力）。
 *
 * <h3>2026-10-03 的删减 —— 为什么这里只剩一个方法</h3>
 *
 * 本类原先还承担一个职责：**文本层同音纠错**。它维护了一张几百条的
 * {@code HOMOPHONE_FIXES} 枚举表（"发开"→"打开"、"烂牙"→"蓝牙"、"歪发"→"wifi"…），
 * 外加一层按读音找最近邻的 {@code fixByPhonetic}，在字幕与执行链路上把错读替换成规范词。
 *
 * <p>用户定档：**全部删掉**。理由站得住脚 ——
 * <ol>
 *   <li><b>治标不治本</b>：vosk 对同一个词的错法近乎无穷（wifi 一个词就有
 *       "歪歪/外翻/歪发/外快/万科/白费…"），枚举表永远追不上新错法；</li>
 *   <li><b>污染溯源</b>：替换之后，日志与字幕里看到的都不是 ASR 的真实输出，
 *       排查时无法区分"没听见"和"听错了"；</li>
 *   <li><b>有更好的位置</b>：纠错应该在**识别环节**做 —— 把先验作为上下文交给声学模型
 *       （见 {@code com.onesay.assistant.v2.asr.AsrHotwords}），让模型自己选对同音词，
 *       而不是在模型输出之后去猜它想说什么。</li>
 * </ol>
 *
 * <p>因此本类现在只提供拼音转写，供唤醒词剥离（{@code WakeWordStripper}）等场景使用。
 */
public class PinyinUtil {

    private static Object sTranslit;
    private static java.lang.reflect.Method sTranslitMethod;
    private static boolean sTranslitTried = false;

    /**
     * ★ R14（diag C7）：ICU {@code Transliterator.getInstance("Han-Latin")} 首次初始化约 880 ms。10-15 真机它卡在<b>主线程</b>
     * （云端结果回调 → WakeWordStripper.strip → 这里），Choreographer 报 Skipped 53 frames。WakeService.onCreate 在后台线程调一次
     * {@link #prewarm()}，第一句话就不用再挨这一下。初始化加锁：预热还没完时主线程来调会等它完成（而不是像旧代码那样
     * 先把 tried 置真、拿到 null 直接返回空串 —— 那样唤醒词剥离会悄悄失效）。
     */
    public static void prewarm() {
        long t0 = System.nanoTime();
        String p = toPinyin("你好");
        android.util.Log.i("PinyinUtil", "[PREWARM] ICU Han-Latin 就绪 " + (System.nanoTime() - t0) / 1000000L + "ms → " + p);
    }

    private static synchronized boolean ensureInit() {
        if (!sTranslitTried) {
            sTranslitTried = true;
            try {
                Class<?> cls = Class.forName("android.icu.text.Transliterator");
                sTranslit = cls.getMethod("getInstance", String.class).invoke(null, "Han-Latin");
                sTranslitMethod = cls.getMethod("transliterate", String.class);
            } catch (Throwable ignore) {}
        }
        return sTranslit != null && sTranslitMethod != null;
    }

    /** 将汉字转换为纯小写无音调拼音（依赖 Android API 24+ ICU） */
    public static String toPinyin(String s) {
        if (s == null || s.length() == 0) return "";
        try {
            if (!ensureInit()) return "";
            String p;
            synchronized (PinyinUtil.class) {   // ICU Transliterator 实例不保证线程安全
                p = (String) sTranslitMethod.invoke(sTranslit, s);
            }
            p = java.text.Normalizer.normalize(p, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return p.toLowerCase().replaceAll("[^a-z0-9]", "");
        } catch (Throwable e) {
            return "";
        }
    }
}
