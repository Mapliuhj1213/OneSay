package com.onesay.assistant.v2.ui.console;

/**
 * ★ console-align 2026-11-12：设置区按「控制台条目清单」（docs/CONSOLE.md）重排，与电脑端设置页同名同序：
 * <b>连接 · 权限与审批 · 模型与密钥 · 语音 · 替你操作 · 功能 · 下载 · 插件</b>，然后「关于」，最下面「开发者」（只手机有，默认折叠）。
 * 合并：原「语音唤醒」「朗读」→「语音」一页；原「权限保活」「隐私」→「权限与审批」一页（再加「以后这类不用问」清单）；
 * 原「灵动岛」「主动建议」两行 → 「功能」页最上面两行；「功能插件」从开发者搬到「插件」；「把链接下到电脑」从电脑联动细项页搬到「下载」。
 * 插件行（{@code PAGE_FEATURES}）按 id 分组：{@link #CONNECT_PLUGINS} 进连接、{@link #ACT_PLUGINS} 进替你操作、{@link #DEV_PLUGINS} 进开发者、
 * {@link #SUGGEST_PLUGINS} 进主动建议，其余（含以后新加的）都落在「功能」，不会丢。不删功能、设置键不改名。
 *
 * <p>[控制台整理第二步 2026-11-05] 首页「设置」区的分组表（纯 Java，JVM 可测）。旧入口 → 新位置见 {@link #MOVES}。
 */
public final class ConsoleGroups {

    private ConsoleGroups() {}

    public static final String CONNECT = "connect", PERM = "perm", MODEL = "model", VOICE = "voice", ACT = "act", FEATURES = "features",
            DOWNLOAD = "download", PLUGINS = "plugins", ABOUT = "about", DEV = "dev";
    /** 2026-11-05 版的分组 id（页面仍在：语音唤醒 / 朗读 = 「语音」一页的两段，灵动岛 / 主动建议 = 「功能」页里的两行，隐私 = 「权限与审批」里的「屏幕与剪贴板」）。 */
    public static final String WAKE = "wake", READ = "read", ISLAND = "island", SUGGEST = "suggest", PRIVACY = "privacy";

    /** 设置区分组（顺序即首页顺序，也是电脑端设置页的顺序）：{id, 标题, 默认副标题}。 */
    public static final String[][] GROUPS = {
            {CONNECT, "连接", "电脑联动 · 多设备执行端 · 连接器"},
            {PERM, "权限与审批", "系统权限 · 屏幕与剪贴板 · 以后这类不用问"},
            {MODEL, "模型与密钥", "主模型 · 备用模型 · 语音识别 Key · 思考模式 · 邮箱"},
            {VOICE, "语音", "声纹 · 唤醒手感 · 提示音 · 朗读"},
            {ACT, "替你操作", "替你操作 App · 接口优先 · 浏览器跑腿 · 控屏颜色"},
            {FEATURES, "功能", "灵动岛 · 主动建议 · 日程 · 待办 · 定时任务 · 计划 App"},
            {DOWNLOAD, "下载", "把视频 / 音频链接下到电脑"},
            {PLUGINS, "插件", "每个插件的状态（只读）"},
    };

    /** 开发者分组（放最下，默认折叠）。 */
    public static final String DEV_TITLE = "开发者";
    public static final String DEV_SUB_COLLAPSED = "诊断 · 摘要 · 调试 · 点开";
    public static final String DEV_SUB_EXPANDED = "诊断 · 摘要 · 调试 · 点这里收起";
    /** 开发者分组默认折叠（每次进控制台都是折叠的，不记忆）。 */
    public static final boolean DEV_EXPANDED_BY_DEFAULT = false;

    /** 原 PAGE_FEATURES 里的插件，分到开发者的：设置诊断（diag）、长内容摘要（summary）。 */
    public static final String[] DEV_PLUGINS = {"diag", "summary"};
    /** 分到主动建议的：suggest（{@code SuggestPlug.ID}）。 */
    public static final String[] SUGGEST_PLUGINS = {"suggest"};
    /** ★ console-align：分到「连接」的：电脑联动、多设备执行端、连接器（MCP / Google）。 */
    public static final String[] CONNECT_PLUGINS = {"pclink", "phongent", "conn"};
    /** ★ console-align：分到「替你操作」的：替你操作 App、接口优先、浏览器跑腿。 */
    public static final String[] ACT_PLUGINS = {"phone", "apifirst", "errand"};
    /** 「功能」页不画的（= 开发者 + 主动建议 + 连接 + 替你操作）；其余插件都画在「功能」。 */
    public static final String[] FEATURES_EXCLUDE = {"diag", "summary", "suggest", "pclink", "phongent", "conn", "phone", "apifirst", "errand"};

    /** 设置区里某个插件 id 的 PAGE_FEATURES 行落在哪个分组。 */
    public static String groupOfFeaturePlugin(String pluginId) {
        for (String s : DEV_PLUGINS) if (s.equals(pluginId)) return DEV;
        for (String s : SUGGEST_PLUGINS) if (s.equals(pluginId)) return SUGGEST;
        for (String s : CONNECT_PLUGINS) if (s.equals(pluginId)) return CONNECT;
        for (String s : ACT_PLUGINS) if (s.equals(pluginId)) return ACT;
        return FEATURES;
    }

    /** 「功能」页里「灵动岛」一行的副标题。 */
    public static String defaultIslandSub() {
        return "显示 · 消息提醒 · 智能上岛";
    }

    public static String title(String id) {
        if (DEV.equals(id)) return DEV_TITLE;
        if (ABOUT.equals(id)) return "关于";
        for (String[] g : GROUPS) if (g[0].equals(id)) return g[1];
        return null;
    }

    public static String defaultSub(String id) {
        if (DEV.equals(id)) return DEV_SUB_COLLAPSED;
        for (String[] g : GROUPS) if (g[0].equals(id)) return g[2];
        return null;
    }

    /**
     * 旧入口 → 新位置对照表：{旧位置, 新位置, 新位置所在源文件（相对 app/src/com/bixbyui/v2/）, 新文件里必须出现的标识}。
     * JVM 测试逐条检查「标识」确实在新文件里，保证所有原入口都找得到。★ console-align 起「新位置」写的是 2026-11-12 的位置。
     */
    public static final String[][] MOVES = {
            // ---- 首页
            {"首页 · 状态卡 / 提醒条", "首页 · 状态卡 / 提醒条（不变）", "ui/console/HomePage.java", "col.addView(hero());"},
            {"首页 · 记一记 / 记忆两张卡", "首页 · 两张卡（不变）", "ui/console/HomePage.java", "\"记一记\""},
            {"首页 · 计划区", "首页 · 计划区（不变）", "ui/console/HomePage.java", "\"打开计划\""},
            {"首页 · 模型 · 模型与密钥", "设置 · 模型与密钥", "ui/console/HomePage.java", "new AdvancedPage(c)"},
            {"首页 · 设置 · 灵动岛", "设置 · 功能 · 灵动岛（最上面一行）", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_ISLAND, \"灵动岛\""},
            {"首页 · 设置 · 关于", "设置区下方 · 关于", "ui/console/HomePage.java", "new AboutPage(c)"},
            // ---- 功能与权限 › 功能（PAGE_FEATURES 插件行）
            {"功能与权限 · 功能 · 计划 App", "设置 · 功能 · 计划 App", "ui/console/FeaturesPage.java", "ConsoleGroups.FEATURES_EXCLUDE"},
            {"功能与权限 · 功能 · 日程", "设置 · 功能 · 日程", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 定时任务 / 待办", "设置 · 功能 · 定时任务 / 待办", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 定时任务与主动播报", "设置 · 功能 · 定时任务与主动播报", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 晨间简报上灵动岛", "设置 · 功能 · 晨间简报上灵动岛", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 连接器（MCP / Google）", "设置 · 连接 · 连接器", "ui/console/GroupPages.java", "ConsoleGroups.CONNECT_PLUGINS"},
            {"功能与权限 · 功能 · 浏览器跑腿", "设置 · 替你操作 · 浏览器跑腿", "ui/console/GroupPages.java", "ConsoleGroups.ACT_PLUGINS"},
            {"功能与权限 · 功能 · 多设备执行端", "设置 · 连接 · 多设备执行端", "ui/console/GroupPages.java", "ConsoleGroups.CONNECT_PLUGINS"},
            {"功能与权限 · 功能 · 主动建议", "设置 · 功能 · 主动建议（第二行）", "ui/console/FeaturesPage.java", "new GroupPages.SuggestPage(c)"},
            {"功能与权限 · 功能 · 设置诊断", "开发者 · 诊断与摘要 · 设置诊断", "ui/console/GroupPages.java", "ConsoleGroups.DEV_PLUGINS"},
            {"功能与权限 · 功能 · 长内容摘要", "开发者 · 诊断与摘要 · 长内容摘要", "ui/console/GroupPages.java", "ConsoleGroups.DEV_PLUGINS"},
            // ---- 功能与权限 › 屏幕与剪贴板 / 权限与运行 / 其他
            {"功能与权限 · 屏幕与剪贴板（识屏 / 圈一下 / 取件码浮卡 / 剪贴板地址芯片 / 保存图片）", "设置 · 权限与审批 · 屏幕与剪贴板", "ui/console/GroupPages.java", "ConsoleHost.PAGE_SCREEN"},
            {"功能与权限 · 权限与运行 · 悬浮窗", "设置 · 权限与审批 · 系统权限 · 悬浮窗", "ui/console/GroupPages.java", "\"悬浮窗\""},
            {"功能与权限 · 权限与运行 · 电池优化白名单", "设置 · 权限与审批 · 系统权限 · 电池优化白名单", "ui/console/GroupPages.java", "\"电池优化白名单\""},
            {"功能与权限 · 权限与运行 · Root 通道", "设置 · 权限与审批 · 系统权限 · Root 通道", "ui/console/GroupPages.java", "\"Root 通道\""},
            {"功能与权限 · 权限与运行 · 麦克风", "设置 · 权限与审批 · 系统权限 · 麦克风", "ui/console/GroupPages.java", "\"麦克风\""},
            {"功能与权限 · 其他（没迁移的旧插件卡片）", "开发者 · 诊断与摘要 · 其他", "ui/console/GroupPages.java", "addConsoleCards(c, legacy)"},
            // ---- 语音
            {"语音 · 声纹（录制 / 取消录制）", "设置 · 语音 · 声纹", "ui/console/VoicePage.java", "\"取消录制\""},
            {"语音 · 唤醒手感（按下即听 / 息屏说「我在」/ 抢话不丢字 / 自适应断句）", "设置 · 语音 · 唤醒手感", "ui/console/VoicePage.java", "\"唤醒手感\""},
            {"语音 · 唤醒提示音", "设置 · 语音 · 唤醒提示音", "ui/console/VoicePage.java", "\"唤醒提示音\""},
            {"语音 · 回应 · 语音问就语音答", "设置 · 语音 · 朗读 · 语音问就语音答", "ui/console/VoicePage.java", "\"语音问就语音答\""},
            {"语音 · 回应 · 朗读完整回答", "设置 · 语音 · 朗读 · 朗读完整回答", "ui/console/VoicePage.java", "\"朗读完整回答\""},
            {"语音 · 回应 · 打字问也读出来", "设置 · 语音 · 朗读 · 打字问也读出来", "ui/console/VoicePage.java", "\"打字问也读出来\""},
            {"语音 · 回应 · 回答后接着听", "设置 · 语音 · 朗读 · 回答后接着听", "ui/console/VoicePage.java", "\"回答后接着听\""},
            {"语音 · 回应 · 显示「正在听 / 识别中」", "设置 · 语音 · 朗读 · 显示「正在听 / 识别中」", "ui/console/VoicePage.java", "\"显示「正在听 / 识别中」\""},
            {"语音 · 思考模式", "设置 · 模型与密钥 · 思考模式", "ui/console/AdvancedPage.java", "\"思考模式\""},
            // ---- 模型与密钥
            {"模型与密钥 · API 密钥（面板入口）", "设置 · 模型与密钥 · 主模型 · API Key / 其他模型与密钥 · 语音识别 Key（都进 API 密钥面板）", "ui/console/AdvancedPage.java", "k.linkRow(g, \"API Key\""},
            {"模型与密钥 · 视觉模型 / 语音识别 Key", "设置 · 模型与密钥 · 其他模型与密钥", "ui/console/AdvancedPage.java", "\"语音识别 Key\""},
            {"模型与密钥 · 读数直答 / 发给云端前隐藏个人信息", "设置 · 模型与密钥 · 发送与回答", "ui/console/AdvancedPage.java", "\"读数类问题直接回答\""},
            {"模型与密钥 · 备用模型", "设置 · 模型与密钥 · 备用模型", "ui/console/AdvancedPage.java", "editBackup();"},
            {"模型与密钥 · 账户 · 邮箱（IMAP）", "设置 · 模型与密钥 · 账户（不变）", "ui/console/AdvancedPage.java", "MailSettingsActivity.class"},
            {"模型与密钥 · 外观 · 浅色外观", "设置 · 关于 · 外观", "ui/console/AboutPage.java", "\"浅色外观\""},
            {"模型与密钥 · 插件 · 功能插件（2026-11-05 起在开发者）", "设置 · 插件", "ui/console/HomePage.java", "new AdvancedPage.PluginsPage(c)"},
            {"模型与密钥 · 开发者（开发者模式）", "开发者 · 调试工具（开发者模式）", "ui/console/HomePage.java", "new DeveloperPage(c)"},
            // ---- ★ console-align 2026-11-12
            {"设置 · 语音唤醒 / 朗读（两行）", "设置 · 语音（一页：唤醒 + 朗读）", "ui/console/HomePage.java", "VoicePage.all(c)"},
            {"设置 · 权限保活 / 隐私（两行）", "设置 · 权限与审批（一页）", "ui/console/HomePage.java", "new GroupPages.PermPage(c)"},
            {"功能 · 电脑联动", "设置 · 连接 · 电脑联动", "ui/console/GroupPages.java", "ConsoleGroups.CONNECT_PLUGINS, null"},
            {"功能 · 替你操作 App / 接口优先", "设置 · 替你操作", "ui/console/GroupPages.java", "ConsoleGroups.ACT_PLUGINS, null"},
            {"电脑联动 · 让电脑做事 · 把链接下到电脑", "设置 · 下载", "pclink/PcLinkUi.java", "public static void addDownloadRows("},
            {"确认卡「始终允许」（以前没有撤销入口）", "设置 · 权限与审批 · 以后这类不用问", "ui/console/GroupPages.java", "ConsentPolicy.revoke(sign)"},
            // ---- 深链
            {"唤醒卡片「去配置」console:asr.key", "设置 · 模型与密钥 + 语音识别 Key 弹窗（不变）", "ui/console/Console.java", "nav.push(new AdvancedPage(this));"},
    };
}
