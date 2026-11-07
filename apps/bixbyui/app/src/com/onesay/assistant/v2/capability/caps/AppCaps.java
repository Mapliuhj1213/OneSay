package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 应用（打开/商店/浏览器…）。
 *
 * <p>从 {@code BuiltinCapabilities.registerApps} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class AppCaps {

    private AppCaps() {}

    public static void register(CapabilityRegistry reg) {
        // 核心应用启动器（严格使用规范组件 -n pkg/cls，由 IntentExecutor/PackageManager 统一解析）
        reg.register(new Capability.Builder("app.launch", "启动手机已安装应用程序")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("app", true, ""))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -n {component}")
                .say("打开{app}", "启动{app}", "运行{app}", "拉起{app}")
                .reply("已为您打开 {app}")
                .build());

        // 网页与搜索
        reg.register(new Capability.Builder("browser.open", "使用默认浏览器打开指定网址")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("url", true, "https://www.baidu.com"))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d {url}")
                .say("打开网页{url}", "浏览网站{url}")
                .reply("正在为您打开网页")
                .build());

        // ★ 缺陷修复（2026-10-03）：原先只有一个 browser.search 且引擎**写死百度**，
        //   于是"用必应搜一下 XXX"这类指令根本没有落点 —— 无论用户点没点名引擎，
        //   最终都打开百度。现在按引擎拆成独立能力：
        //   引擎名写在 say 模板里 → L0 能靠字面量长度自然分辨（"必应搜索{n}" 比 "搜索{n}" 长，优先命中）；
        //   LLM 也能从能力摘要里直接选对。URL 直接写死在模板，不需要任何条件渲染。
        reg.register(new Capability.Builder("browser.search", "使用百度搜索互联网内容（未点名引擎时的默认）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("keyword", true, "今日新闻"))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d https://www.baidu.com/s?wd={keyword}")
                .say("搜一下{n}", "百度搜索{n}", "搜索{n}", "查一下{n}", "百度一下{n}")
                .reply("已发起百度搜索：{keyword}")
                .build());

        reg.register(new Capability.Builder("browser.search.bing", "使用必应 Bing 搜索互联网内容")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("keyword", true, ""))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d https://cn.bing.com/search?q={keyword}")
                .say("必应搜索{n}", "用必应搜索{n}", "必应搜{n}", "必应上搜索{n}", "bing搜索{n}", "用bing搜{n}")
                .reply("已在必应搜索：{keyword}")
                .build());

        reg.register(new Capability.Builder("browser.search.google", "使用谷歌 Google 搜索互联网内容")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("keyword", true, ""))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d https://www.google.com/search?q={keyword}")
                .say("谷歌搜索{n}", "用谷歌搜索{n}", "谷歌搜{n}", "google搜索{n}", "用google搜{n}")
                .reply("已在谷歌搜索：{keyword}")
                .build());

        reg.register(new Capability.Builder("browser.search.sogou", "使用搜狗搜索互联网内容")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("keyword", true, ""))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d https://www.sogou.com/web?query={keyword}")
                .say("搜狗搜索{n}", "用搜狗搜索{n}", "搜狗搜{n}")
                .reply("已在搜狗搜索：{keyword}")
                .build());

        // 只开首页不搜索（"打开必应"这类没有关键词的请求）
        reg.register(new Capability.Builder("browser.open.bing", "打开必应首页")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d https://cn.bing.com")
                .say("打开必应", "打开bing", "打开必应首页")
                .reply("已为您打开必应")
                .build());

        // 「打开浏览器」的正解 —— 读默认浏览器 + 用它的接口打开界面。
        //
        //   旧行为（错的）：`打开浏览器` 被 app.launch 用「品类别名」拦走，
        //   别名表里写死的是 `com.android.chrome`（本机根本没装），
        //   真正在用的三星浏览器只是靠"候选里恰好有 sbrowser"兜着 —— 一旦别名表过时，
        //   就会回"没找到应用"。而且它走的是 ACTION_MAIN/LAUNCHER（**等于用户点图标**），
        //   不是"浏览器打开某页"的正规接口。
        //
        //   正解分两步，与电脑 agent「先探明环境、再选接口」的做法一致：
        //     ① 运行时读系统：`cmd package resolve-activity --brief
        //        -a android.intent.action.VIEW -d http://` → 输出形如
        //        `com.sec.android.app.sbrowser/.SBrowserLauncherActivity`（默认浏览器组件）
        //     ② 用拿到的组件 `am start -n <component>` 打开界面
        //   本机实测：默认浏览器 = 三星浏览器 com.sec.android.app.sbrowser ✓
        //
        //   ★ 为什么用 `-n <component>` 而不是 `-a VIEW -d http://`：
        //     `-a VIEW -d http://example.com` 会**顺带打开 example.com 这个页面**，
        //     用户只想"打开浏览器"却看到一个示例网站，属结果形态错误（坑2 同源）。
        //     用解析出来的 LauncherActivity + `-n` 才是"只开界面、不带页面"。
        reg.register(new Capability.Builder("browser.open.default", "打开系统默认浏览器（运行时探测，不依赖预置别名表）")
                .domain("settings")
                .verbs("打开")
                .entities("setting")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                // 探测与启动串成一条：resolve 拿组件，失败则回落 VIEW 协议（保证总有浏览器打开）
                .cmd("C=$(cmd package resolve-activity --brief -a android.intent.action.VIEW -d http://example.com 2>/dev/null | tail -1); "
                        + "if [ -n \"$C\" ]; then am start -n \"$C\"; else am start -a android.intent.action.VIEW -d about:blank; fi")
                .say("打开浏览器", "启动浏览器", "打开网页浏览器", "打开默认浏览器")
                .reply("已为您打开默认浏览器")
                .build());

        // 「打开浏览器，把引擎换成 X」的一次到位实现。
        //
        //   排查过程（记录在此，避免后来人重复踩）：
        //   ① 先挖到三星浏览器的私有接口 `com.samsung.intent.action.
        //      CSC_BROWSER_SET_SEARCH_ENGINE`（接收者 .settings.SearchEngineSettingReceiver），
        //      且爆破出 extra key = `searchEngine`，值形如 "bing"/"google"。
        //   ② root 下广播**能被接收**（日志有 `onReceive ... value is received :google`、
        //      `onBrowserPreferenceChanged key: current_search_engine_name`），
        //      但 prefs **写盘失败/被回滚**（实测切完仍是 bing）。
        //      结论：该接口是**开机时 CSC 运营商配置一次性写入**用的，不是运行时切换入口。
        //      —— 这是一条"挖到接口但不可用"的负面结论，已确认，勿再试。
        //   ③ 正解改用 **URL 直达**：`am start -a VIEW -d <引擎首页>`。
        //      语义上完全满足用户意图（"让浏览器用必应"——打开必应就是），
        //      且不依赖任何浏览器私有接口，跨浏览器通用。
        //      实测：打开后前台 App = com.sec.android.app.sbrowser/.SBrowserMainActivity ✓
        //
        //   注意与既有能力的边界（避免重叠）：
        //     browser.open.bing / .google …  → 只开某引擎首页（不带"换引擎"语义）
        //     browser.engine.open.<eng>     → **「打开浏览器把引擎换成X」这种复合句的正解**
        //
        //   ★ 为什么按引擎拆成 4 条（而不是一条带 {engine} 槽位）：
        //     L0 无法把中文"必应"解析成 URL —— 槽位只能回填数字或已登记实体。
        //     所以把 URL 直接写死在每条 say 模板里，与既有 browser.search.* 的做法一致。
        //     say 模板里含引擎名 → L0 靠字面量长度自然分辨，命中即正确。
        for (String[] e : new String[][]{
                { "bing", "https://cn.bing.com" },
                { "baidu", "https://www.baidu.com" },
                { "google", "https://www.google.com" },
                { "sogou", "https://www.sogou.com" } }) {
            String eng = e[0];
            String url = e[1];
            reg.register(new Capability.Builder("browser.engine.open." + eng,
                    "打开浏览器并切换到" + eng + "引擎")
                    .domain("settings")
                    .verbs("打开", "切换")
                    .entities("setting")
                    .category("app")
                    .sideEffect(SideEffect.DEVICE_CONTROL)
                    .risk(0, false)
                    .executor("shell")
                    .cmd("am start -a android.intent.action.VIEW -d " + url)
                    .say("打开浏览器把引擎换成" + eng, "浏览器引擎换成" + eng,
                            "把浏览器引擎换成" + eng, "浏览器换成" + eng)
                    .reply("已打开浏览器并切换到 " + eng + " 引擎")
                    .build());
        }

        reg.register(new Capability.Builder("browser.default.check", "查询系统默认浏览器")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                // 原命令 `query-activities | grep -m1 Activity` 输出一大坨
                //   dump（"Activity #0: priority=... match=..."），直接回给用户很丑。
                //   改用 --brief，一行输出即 `pkg/.Activity`，正是用户想知道的。
                .cmd("cmd package resolve-activity --brief -a android.intent.action.VIEW -d http://example.com 2>/dev/null | tail -1")
                .say("默认浏览器是什么", "当前浏览器是哪个", "默认浏览器是哪个")
                .reply("当前默认浏览器：{out}")
                .build());

        reg.register(new Capability.Builder("browser.default.set", "前往设置默认浏览器")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                .say("设置默认浏览器", "修改默认网页浏览器")
                .reply("已打开默认应用设置界面")
                .build());

        // 笔记记录
        //   //     原来用的是 `am start -a SEND -t text/plain --es TEXT '...'` **不带 -n**，
        //     实测会弹出系统**分享面板**（"完成动作的方式：三星浏览器/添加至新笔记/信息/QQ…"），
        //     要用户自己再点一次 —— 任务没完成，且选择项里还混着浏览器、QQ 等无关目标。
        //
        //     正解 = **显式指定 Samsung Notes 的新建编辑器组件**：
        //       com.samsung.android.app.notes/.nativecomposer.NativeComposerActionNewActivity
        //     实测：`su -c am start -a SEND -t text/plain -n <该组件> --es android.intent.extra.TEXT '...'`
        //     直达 `ComposerActivity`（笔记编辑器），正文已预填（截图确认）。
        //     ★ 该 Activity **未导出**（不带 -n 时普通 uid 起不来；带 -n 需 root），
        //       正好本工程的 intent 执行器走 root 守护，天然可用。
        //
        //     另注：三星笔记自带 `android.intent.action.CREATE_NOTE` 是**不注册**的
        //       （`cmd package resolve-activity -a CREATE_NOTE` → No activity found），
        //       别去试标准 API，本机只有上面这条 SEND+组件 的路通。
        reg.register(new Capability.Builder("notes.new", "新建一条三星笔记")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                // ★ 可选参数 content：LLM 常把「新建笔记记录XXX」归成 notes.new 并把 XXX 塞进
                //   args["text"]/args["content"]。若 notes.new 不声明该参数，LLM 抽出的正文会被丢弃
                //   （实测：`新建笔记记录明天下午三点开会` → notes.new，只落一条空白笔记）。
                //   声明为非必填 + 默认"新建笔记"，则"带内容"和"不带内容"两种说法都能正确落地。
                .schema(new ParamsSchema().addString("content", false, "新建笔记"))
                .risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.SEND -t text/plain "
                        + "-n com.samsung.android.app.notes/.nativecomposer.NativeComposerActionNewActivity "
                        + "--es android.intent.extra.TEXT '{content}'")
                .say("新建笔记", "写个便签", "新建便签", "记事", "打开笔记")
                .reply("已打开三星笔记新建窗口：{content}")
                .build());

        reg.register(new Capability.Builder("notes.new.text", "随手记下指定内容到三星笔记")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("content", true, ""))
                .risk(0, false)
                .executor("intent")
                // 同 notes.new：必须带 -n 指定编辑器组件，否则弹分享面板。
                .cmd("am start -a android.intent.action.SEND -t text/plain "
                        + "-n com.samsung.android.app.notes/.nativecomposer.NativeComposerActionNewActivity "
                        + "--es android.intent.extra.TEXT '{content}'")
                .say("记一下{n}", "随手记{n}", "记录{n}", "记笔记{n}",
                        "新建笔记记录{n}", "新建笔记写{n}", "新建笔记输入{n}")
                .reply("已为您记录到三星笔记：{content}")
                .build());

        // 卸载应用（高危操作，必带确认卡）
        reg.register(new Capability.Builder("app.uninstall", "卸载指定应用程序（高危销毁）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("app")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addString("app", true, ""))
                .risk(2, true)
                .executor("intent")
                .cmd("am start -a android.intent.action.DELETE -d package:{pkg}")
                .say("卸载{app}", "删除软件{app}")
                .reply("正在卸载应用 {app}")
                .build());
    }
}
