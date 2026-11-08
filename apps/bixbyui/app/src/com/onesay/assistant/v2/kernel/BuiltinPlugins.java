package com.onesay.assistant.v2.kernel;

import com.onesay.assistant.v2.kernel.plugins.DiagPlugin;
import com.onesay.assistant.v2.kernel.plugins.HelloPlugin;
import com.onesay.assistant.v2.kernel.plugins.ImageSavePlugin;
import com.onesay.assistant.v2.kernel.plugins.IslandPlugin;
import com.onesay.assistant.v2.kernel.plugins.MemoryPlugin;
import com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin;
import com.onesay.assistant.v2.kernel.plugins.RolesPlugin;
import com.onesay.assistant.v2.kernel.plugins.S1Plugin;
import com.onesay.assistant.v2.kernel.plugins.S3Plugin;
import com.onesay.assistant.v2.kernel.plugins.ScreenRefPlugin;
import com.onesay.assistant.v2.kernel.plugins.SummaryPlugin;

/**
 * [kernel] 内置插件清单 —— <b>加一个新插件 = 在这里加一行</b>。
 * 注册顺序只决定同优先级时的先后；真正的调用顺序看各插件的 priority。
 */
public final class BuiltinPlugins {

    private BuiltinPlugins() {}

    public static void registerAll(FeatureKernel k) {
        k.register(new S1Plugin());
        k.register(new IslandPlugin());
        k.register(new MemoryPlugin());
        k.register(new RolesPlugin());
        k.register(new DiagPlugin());
        k.register(new SummaryPlugin());
        k.register(new ImageSavePlugin());
        k.register(new S3Plugin());
        k.register(new ScreenRefPlugin());   // 第 3 阶段：只登记身份 / 开关 / 状态，挂钩仍宿主直调
        k.register(new QuickNotePlugin());   // 2026-10-11 记一记（长按 Bixby 键）
        k.register(new com.onesay.assistant.v2.kernel.plugins.BriefPlugin());   // 2026-10-22 晨间播报 v2 简报状态上灵动岛，默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.ConnectorPlugin());   // 2026-10-22 B 线：连接器（MCP / Google），默认开、未配置时不干预
        k.register(new com.onesay.assistant.v2.kernel.plugins.ErrandPlugin());   // ★ lane C3 浏览器跑腿，默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.CallbackPlugin());   // 2026-10-23 lane-a：定时任务与主动播报（回调中枢），默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.NoticePlugin());   // ★ 灵动岛消息提醒（回调结果 / 日程 / 定时邮件），默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.SchedulePlugin());   // 2026-10-24 本地日程，默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.PlannerPlugin());   // 计划 App 适配层（feat/planner-link），默认开；没装计划 App = 无副作用
        k.register(new com.onesay.assistant.v2.kernel.plugins.RainPlugin());   // ★ feat/rain-minutely 分钟级降水（和风天气）：Key / Host 不齐 = 无副作用
        k.register(new com.onesay.assistant.v2.kernel.plugins.PhonePlugin());   // ★ r2/fast-screen：读屏助手预热 + phone.operate（执行模式），默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.PhongentPlugin());   // 2026-11-02 phongent 兼容执行端（插件本体 com.onesay.assistant.plug.phongent.core），默认关
        k.register(new com.onesay.assistant.v2.kernel.plugins.SuggestPlugin());   // ★ feat/suggest 主动建议（Aicy 建议对标）：debug 默认开、release 默认关
        k.register(new com.onesay.assistant.v2.kernel.plugins.AskOncePlugin());   // ★ int/1102 问一次记住（发消息渠道 / 购物方式 / 邮件先问哪类 / 播放音乐无播放器），默认开
        k.register(new com.onesay.assistant.v2.kernel.plugins.PcLinkPlugin());   // ★ feat/pclink 电脑联动（扫码配对电脑端 OneSay）：默认开，没配对 = 不联网、无副作用
        k.register(new com.onesay.assistant.v2.kernel.plugins.VyrsePlugin());   // ★ compat-vyrse：VYRSE 电脑兼容（当它的手机），默认关；关着 = 不联网、无服务
        k.register(new HelloPlugin());   // 示例，默认关
    }
}
