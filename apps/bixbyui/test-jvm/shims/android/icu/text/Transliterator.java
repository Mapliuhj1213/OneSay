package android.icu.text;

/**
 * JVM 单测用的 android.icu.text.Transliterator 替身：转发给 ICU4J（com.ibm.icu）。
 * Android 的 android.icu.* 本身就是 ICU4J 改包名而来，所以 "Han-Latin" 转写结果与真机基本一致
 * （ICU 版本不同：Android 9 内置 ICU 60，这里用 ICU4J 72.1，个别生僻字读音可能有差异）。
 *
 * 只实现 PinyinUtil 通过反射用到的两个方法：getInstance(String) 与 transliterate(String)。
 */
public class Transliterator {
    private final com.ibm.icu.text.Transliterator impl;

    private Transliterator(com.ibm.icu.text.Transliterator impl) { this.impl = impl; }

    public static Transliterator getInstance(String id) {
        return new Transliterator(com.ibm.icu.text.Transliterator.getInstance(id));
    }

    public String transliterate(String source) {
        return impl.transliterate(source);
    }
}
