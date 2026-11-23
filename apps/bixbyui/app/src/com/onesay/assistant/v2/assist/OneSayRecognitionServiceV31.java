package com.onesay.assistant.v2.assist;

/**
 * ★ feat/assist-role：Android 12+ 用的识别服务（行为和 {@link OneSayRecognitionService} 一样，只多了 Manifest 里的
 * {@code BIND_RECOGNITION_SERVICE} 保护）。
 *
 * <p>为什么分两个：Android 12 起别的 App 用系统语音识别时由系统代为绑定（系统有这个权限），加保护不影响谁；
 * Android 11 及以下是别的 App <b>直接</b>绑定，加了这个权限它们会绑不上（多数会直接崩），所以低版本仍用不加权限的老组件
 * （照旧回「忙」）。两个组件用 {@code values-v31/onesay_assist.xml} 的开关二选一，{@code xml-v31} 里的助理声明指向本类。</p>
 */
public class OneSayRecognitionServiceV31 extends OneSayRecognitionService {
}
