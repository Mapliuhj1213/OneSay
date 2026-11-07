/**
 * [screenref] 识屏指代 + 下一步动作芯片（调研 04 号文档 #1 + #7）。
 *
 * <p>设计、挂钩清单（合并后逐行重放用）、测试与真机验收脚本见
 * {@code deliverable/-识屏指代功能实现.md}；真机半自动验收：{@code _probe/screenref_accept.sh}。
 *
 * <p>纯 Java（JVM 单测覆盖）：ScreenEntity / UiNode / UiDumpParser / DateTimeNormalizer / EntityExtractor /
 * ScreenContext / DeixisResolver / ActionMapper / ScreenAction / ScreenRefCapabilities。
 * <br>Android 侧：ScreenRefSettings（开关 + kill-switch）/ ScreenContextCapture（root 采集 + 缓存 + VL 兜底）/
 * ScreenRefHooks（全部挂钩入口）/ ui.ActionChipsView。
 */
package com.onesay.assistant.v2.vision.screenref;
