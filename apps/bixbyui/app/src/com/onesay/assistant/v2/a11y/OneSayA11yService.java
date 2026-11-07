/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0. Modified by OneSay: same empty-shell pattern as
 * app/src/main/java/com/wangzan101/mika/a11y/MikaAccessibilityService.kt. Upstream: ebb0e44.
 */
package com.onesay.assistant.v2.a11y;

/**
 * ★ mika-plug（mika.a11y）：空壳。系统设置里存的是这个组件名，实现放在 {@link A11yBackend}，重构实现不会让已打开的服务失效
 * （照 Mika 的做法）。只在 debug 包的 Manifest 里声明。
 */
public final class OneSayA11yService extends A11yBackend {
}
