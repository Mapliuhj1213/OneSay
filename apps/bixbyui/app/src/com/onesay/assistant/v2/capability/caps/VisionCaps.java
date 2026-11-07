package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 识屏读屏。
 *
 * <p>从 {@code BuiltinCapabilities.registerVision} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class VisionCaps {

    private VisionCaps() {}

    public static void register(CapabilityRegistry reg) {
        // 修订摘要（原为"屏幕视觉理解与多模态识别"）。
        //   原名太抽象：它既没告诉模型**什么时候该用它**，也让 Top-K 召不回它
        //   （用户说"读出邮件内容/截图里是什么"时，字面与原名零重合）。
        //   新摘要把用途写清楚：**读屏幕上的文字内容**，并点出三类典型场景。
        //   这样"读邮件/看聊天/看页面/截图里是什么"都能靠摘要二元组蹭到分进候选。
        reg.register(new Capability.Builder("vision.screen.understand",
                "识屏读屏：读出当前屏幕上的文字内容（邮件、聊天记录、页面文字、截图内容）")
                .domain("vision").category("screen")
                .verbs("识别", "看", "分析", "总结", "读")
                .entities("screen")
                .keywords("屏幕内容", "识屏", "读屏", "截图内容", "界面文字", "屏幕上的字")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema().addString("content", false, ""))
                .say("识屏", "识别屏幕", "看下屏幕", "看看屏幕", "屏幕上有什么",
                        "屏幕上显示了什么", "总结屏幕", "分析屏幕", "屏幕识别",
                        "帮我看看屏幕", "看屏幕", "屏幕分析", "识别当前屏幕",
                        "识别屏幕{content}", "看屏幕{content}", "帮我看看屏幕{content}", "看看屏幕{content}")
                .reply("正在识别屏幕内容...")
                .build());
    }
}
