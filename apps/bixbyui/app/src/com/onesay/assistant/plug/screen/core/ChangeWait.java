/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (app/cap/phone/a11y/ChangeWait.kt) to Java 7; the frame (picture)
 * branch is dropped — this is the tree-only version (MIKA-EXEC §6.5): quiet ≥ 400 ms and tree changed → changed;
 * at the 2.5 s cap decide by the tree fingerprint. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import com.onesay.assistant.plug.api.Clock;

/** 动作之后等界面稳定再判断有没有变化（只看控件树 + 事件安静）。时钟、安静时长、树变没变都由调用方注入。 */
public final class ChangeWait {
    private ChangeWait() {
    }

    public static final long POLL_MS = 150L, QUIET_MS = 400L, CAP_MS = 2500L;
    static final long MIN_SLEEP_MS = 10L;

    /** 事件源：距最近一次界面事件过了多久（毫秒）；不知道时返回 Long.MAX_VALUE（视同安静）。 */
    public interface Quiet {
        long quietForMs();
    }

    /** 控件树指纹是否与动作前不同（实现里读一次树）。 */
    public interface TreeChanged {
        boolean changed();
    }

    /** @return true = 变了（含到上限时树已不同）；false = 到上限树仍没变。capMs ≤ 0 用默认 2.5 s。 */
    public static boolean awaitStableChange(Clock clock, Quiet quiet, TreeChanged tree, long capMs) {
        long cap = capMs > 0 ? capMs : CAP_MS;
        long deadline = clock.elapsed() + cap;
        long wait = POLL_MS;
        while (true) {
            clock.sleep(Math.min(wait, Math.max(0, deadline - clock.elapsed())));
            long q = quiet.quietForMs();
            boolean isQuiet = q >= QUIET_MS;
            if (isQuiet && tree.changed()) return true;
            if (clock.elapsed() >= deadline) return tree.changed();
            wait = isQuiet ? POLL_MS : Math.max(MIN_SLEEP_MS, Math.min(POLL_MS, QUIET_MS - q));
        }
    }
}
