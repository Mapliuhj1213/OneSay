package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.List;

/**
 * [console R12] 控制台页面栈（纯 Java，JVM 可测）：首页永远在栈底，不能被弹出；返回键先问栈顶页自己要不要处理。
 * 视图和动画在 {@link ConsoleNav} 里。
 */
public final class PageStack<T> {

    private final List<T> mPages = new ArrayList<T>();

    public void push(T p) {
        if (p != null) mPages.add(p);
    }

    /** 弹出栈顶（只剩首页时不弹，返回 null）。 */
    public T pop() {
        if (mPages.size() <= 1) return null;
        return mPages.remove(mPages.size() - 1);
    }

    /** 把栈顶换成 p（同一个外来页面换内容，例如记一记列表 → 详情）。 */
    public T replaceTop(T p) {
        if (mPages.isEmpty()) {
            push(p);
            return null;
        }
        return mPages.set(mPages.size() - 1, p);
    }

    public T top() {
        return mPages.isEmpty() ? null : mPages.get(mPages.size() - 1);
    }

    public T below() {
        return mPages.size() < 2 ? null : mPages.get(mPages.size() - 2);
    }

    public int size() {
        return mPages.size();
    }

    public boolean canPop() {
        return mPages.size() > 1;
    }

    /** 回到首页：弹出除栈底外的全部，返回被弹出的（栈顶在前）。 */
    public List<T> popToRoot() {
        List<T> out = new ArrayList<T>();
        while (mPages.size() > 1) out.add(mPages.remove(mPages.size() - 1));
        return out;
    }

    public List<T> all() {
        return new ArrayList<T>(mPages);
    }
}
