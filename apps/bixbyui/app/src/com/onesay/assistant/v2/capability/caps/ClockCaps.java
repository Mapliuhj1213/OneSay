package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 时钟与时间（闹钟/计时器/时间）。
 *
 * <p>从 {@code BuiltinCapabilities.registerClockAndTime} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class ClockCaps {

    private ClockCaps() {}

    public static void register(CapabilityRegistry reg) {
        // 当前时间与日期查询（只读零风险，全离线）
        reg.register(new Capability.Builder("time.now", "查询当前系统时间")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("date +%H:%M")
                .say("现在几点", "几点了", "当前时间", "报时", "现在时间")
                .reply("现在是北京时间 {out}")
                .build());

        reg.register(new Capability.Builder("date.today", "查询当前日期与星期")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("date +%m月%d日")
                .say("今天几号", "今天周几", "今天是几月几日", "查看今天日期")
                .reply("今天是 {out}")
                .build());

        // 设定闹钟与定时器
        // //   verbs 里原本有"打开"，「打开闹钟」这句会命中 clock.alarm.set，
        //   且 schema 的 hour 默认值（8 / 后来被 LLM 填 7）会让它**擅自设一个 7 点的闹钟**。
        //   「打开闹钟」的语义是"打开闹钟界面"，属于 app.launch（走 CATEGORY_ALIASES 的 时钟|闹钟 别名），
        //   不是"设定一个闹钟"。所以这里 verbs 只保留「设置/定/叫」，去掉「打开」。
        //   同时把 hour 默认值删掉 —— 设定闹钟**必须显式给时间**，绝不允许用默认值兜底凑一个。
        //
        // 联动：executor 走 "alarmbridge"（AlarmBridge）——
        //   装了 OneSay 时钟：闹钟写进时钟（signature 权限保护的 AlarmApiReceiver，由它自己落盘/排程），
        //   执行后面板弹出闹钟卡片（大时间+倒计时+开关+铃声行）；没装：交给系统 AlarmClock.ACTION_SET_ALARM。
        //   cmd 字段保留仅作两件事：① InterfaceKind 分类的判定输入（仍归 UI 类）；
        //   ② 归档说明"这套操作落点是什么"。它不再被杀。
        reg.register(new Capability.Builder("clock.alarm.set", "设定指定时间闹钟")
                .domain("settings")
                .verbs("设置", "定", "叫")
                .entities("setting")
                .examples("定七点的闹钟", "早上七点叫我")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema()
                        .addInteger("hour", true, 0, 23, null)
                        .addInteger("minute", false, 0, 59, "0")
                        .addInteger("days", false, 0, 127, "0"))   // ★ int/1102 重复闹钟：位掩码 bit0=周日 … bit6=周六（127 每天 / 62 工作日 / 65 周末）；0 = 只响一次
                .risk(0, false)
                .executor("alarmbridge")
                .cmd("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR {hour} --ei android.intent.extra.alarm.MINUTES {minute} --ez android.intent.extra.alarm.SKIP_UI true")
                .say("定{n}点闹钟", "定{n}点{m}分闹钟", "{n}点叫我", "{n}点{m}分叫我", "设{n}点闹钟", "设{n}点{m}分闹钟")
                .reply("已为您设定 {hour} 点 {minute} 分的闹钟")
                .build());

        // 闹钟三件套（查 / 开关 / 删）。
        //   与 clock.alarm.set 同走 alarmbridge（需要 OneSay 时钟；没装时如实说明），面板以闹钟卡片呈现。
        reg.register(new Capability.Builder("alarm.query", "查看闹钟")
                .domain("settings")
                .verbs("查看", "看看", "查")
                .entities("setting")
                .examples("我的闹钟", "看看有哪些闹钟")
                .category("clock")
                .sideEffect(SideEffect.NONE)
                .schema(new ParamsSchema())
                .risk(0, false)
                .executor("alarmbridge")
                .say("我的闹钟", "看看闹钟", "有哪些闹钟", "闹钟列表", "我有哪些闹钟")
                .reply("已列出闹钟")
                .build());

        reg.register(new Capability.Builder("alarm.toggle", "开启或关闭闹钟")
                .domain("settings")
                .verbs("关闭", "关掉", "开启")
                .entities("setting")
                .examples("关掉闹钟", "把八点的闹钟关了")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema()
                        .addInteger("hour", false, 0, 23, null)
                        .addInteger("minute", false, 0, 59, null)   // ★ audit-J 3：带分钟 = 精确匹配
                        .addInteger("alt_hour", false, 0, 23, null)
                        .addBoolean("on", false, "false"))
                .risk(0, false)
                .executor("alarmbridge")
                .say("关掉闹钟", "关闭闹钟", "把闹钟关了", "关一下闹钟",
                        "关掉{n}点的闹钟", "关闭{n}点的闹钟")
                .reply("闹钟开关已处理")
                .build());

        reg.register(new Capability.Builder("alarm.cancel", "删除闹钟")
                .domain("settings")
                .verbs("删除", "删掉", "取消")
                .entities("setting")
                .examples("删除闹钟", "把闹钟删了")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                // ★ audit-J 3：带 minute = 时、分完全匹配才删；alt_hour = 没说上午下午时的另一个钟点（按现有闹钟里实际有的那个）
                .schema(new ParamsSchema().addInteger("hour", false, 0, 23, null).addInteger("minute", false, 0, 59, null).addInteger("alt_hour", false, 0, 23, null))
                .risk(0, false)
                .executor("alarmbridge")
                .say("删除闹钟", "删掉闹钟", "把闹钟删掉", "删除{n}点的闹钟")
                .reply("闹钟已处理")
                .build());

        // ★ device-1102：「删除所有闹钟」不再反问「几点 / 哪一个」——目标就是全部；删除全部不可撤销 → 确认卡一次。
        reg.register(new Capability.Builder("alarm.cancel.all", "删除全部闹钟（不需要时间参数；执行前确认一次）")
                .domain("settings")
                .verbs("删除", "删掉", "清空")
                .entities("setting")
                .examples("删除所有闹钟", "把闹钟全删了", "清空闹钟")
                .category("clock")
                .sideEffect(SideEffect.DESTRUCTIVE)
                .schema(new ParamsSchema())
                .risk(2, true)
                .executor("alarmbridge")
                .say("删除所有闹钟", "删除全部闹钟", "删掉所有闹钟", "删掉全部闹钟", "把闹钟全删了", "把闹钟全部删掉",
                        "把所有闹钟删掉", "把所有闹钟都删了", "清空闹钟", "清空所有闹钟", "闹钟全部删除", "取消所有闹钟", "取消全部闹钟")
                .reply("闹钟已全部删除")
                .build());

        reg.register(new Capability.Builder("alarm.toggle.all", "开启或关闭全部闹钟（不需要时间参数）")
                .domain("settings")
                .verbs("关闭", "关掉", "开启")
                .entities("setting")
                .examples("关掉所有闹钟", "把闹钟全关了")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addBoolean("on", false, "false"))
                .risk(0, false)
                .executor("alarmbridge")
                .say("关掉所有闹钟", "关掉全部闹钟", "关闭所有闹钟", "关闭全部闹钟", "把闹钟全关了", "把所有闹钟关掉",
                        "把所有闹钟都关了", "闹钟全部关掉", "闹钟全关了")
                .reply("闹钟已全部处理")
                .build());

        reg.register(new Capability.Builder("clock.timer.set", "开启倒计时计时器")
                .domain("settings")
                .verbs("设置", "定", "计时")
                .entities("setting")
                .examples("倒计时六十秒", "计时五分钟")
                .category("clock")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("seconds", true, 1, 86400, null))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH {seconds} --ez android.intent.extra.alarm.SKIP_UI true")
                .say("倒计时{n}秒", "定时{n}秒", "计时{n}秒")
                .reply("已启动 {seconds} 秒倒计时")
                .build());
    }
}
