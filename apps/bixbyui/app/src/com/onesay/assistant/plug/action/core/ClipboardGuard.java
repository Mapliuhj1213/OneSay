/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: the "borrow the clipboard, paste, restore, global lock" rule of
 * app/cap/phone/a11y/AndroidScreen.kt inputText (L1371) rewritten in Java 7 against a ClipboardPort. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.action.core;

/**
 * 剪贴板兜底输入（WebView / SET_TEXT 失败时）：只借一下，粘贴完还原用户原来的剪贴板；全局一把锁，
 * 两个粘贴不会互相踩。还原前看一眼：剪贴板已经不是我们放的（用户这期间复制了别的）就不动。密码框不走这里（调用方拦）。
 */
public final class ClipboardGuard {
    private ClipboardGuard() {
    }

    private static final Object LOCK = new Object();

    public interface ClipboardPort {
        /** 当前剪贴板文字；空 / 非文字为 null。 */
        String get();

        boolean set(String text);
    }

    public interface Paste {
        boolean paste();
    }

    /** 结果：ok = 粘贴动作发出；restored = 已还原（或本来就是空、无需还原时也为 true）。 */
    public static final class Result {
        public final boolean ok, restored;

        Result(boolean ok, boolean restored) {
            this.ok = ok;
            this.restored = restored;
        }
    }

    public static Result pasteAndRestore(ClipboardPort cb, String text, Paste paste) {
        synchronized (LOCK) {
            String old = null;
            try {
                old = cb.get();
            } catch (Throwable ignored) {
            }
            if (!cb.set(text)) return new Result(false, true);
            boolean ok = false;
            try {
                ok = paste.paste();
            } finally {
                String now = null;
                try {
                    now = cb.get();
                } catch (Throwable ignored) {
                }
                boolean ours = now == null ? text.length() == 0 : now.equals(text);
                boolean restored = !ours || cb.set(old == null ? "" : old);
                return new Result(ok, restored);
            }
        }
    }
}
