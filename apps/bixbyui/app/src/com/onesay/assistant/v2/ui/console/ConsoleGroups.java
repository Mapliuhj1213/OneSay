package com.onesay.assistant.v2.ui.console;

/**
 * [控制台整理第二步 2026-11-05] 首页「设置」区的分组表（纯 Java，JVM 可测）。
 *
 * <p>只搬家与改名，不改功能与默认值：
 * <ul>
 *   <li>设置区：模型 · 功能 · 语音唤醒 · 朗读 · 灵动岛 · 主动建议 · 权限保活 · 隐私，然后「关于」；</li>
 *   <li>调试遗留（诊断、摘要、插件列表、旧插件卡片、调试工具）统一收进最下面的「开发者」，默认折叠；
 *       「调试工具」（原「开发者」页）仍只在开发者模式开着时出现（与之前一样）。</li>
 * </ul>
 * 插件行（{@code PAGE_FEATURES}）按插件 id 分到不同分组：{@link #DEV_PLUGINS} 进开发者，{@link #SUGGEST_PLUGINS} 进主动建议，
 * 其余（含以后新加的）都落在「功能」，不会丢。旧入口 → 新位置见 {@link #MOVES}（也写在 bixby-plan/docs/控制台整理.md 第二步）。
 */
public final class ConsoleGroups {

    private ConsoleGroups() {}

    public static final String MODEL = "model", FEATURES = "features", WAKE = "wake", READ = "read", ISLAND = "island",
            SUGGEST = "suggest", PERM = "perm", PRIVACY = "privacy", ABOUT = "about", DEV = "dev";

    /** 设置区分组（顺序即首页顺序）：{id, 标题, 默认副标题}。 */
    public static final String[][] GROUPS = {
            {MODEL, "模型", "模型与密钥 · 思考模式 · 邮箱 · 外观"},
            {FEATURES, "功能", "日程 · 待办 · 定时任务 · 计划 App · 跑腿 · 连接器"},
            {WAKE, "语音唤醒", "声纹 · 唤醒手感 · 提示音"},
            {READ, "朗读", "语音回复 · 朗读完整回答 · 接着听"},
            {ISLAND, "灵动岛", "显示 · 消息提醒 · 智能上岛"},
            {SUGGEST, "主动建议", "总开关 · 场景 · 截图权限"},
            {PERM, "权限保活", "悬浮窗 · 电池白名单 · Root · 麦克风"},
            {PRIVACY, "隐私", "识屏 · 圈一下 · 剪贴板 · 存图"},
    };

    /** 开发者分组（放最下，默认折叠）。 */
    public static final String DEV_TITLE = "开发者";
    public static final String DEV_SUB_COLLAPSED = "诊断 · 摘要 · 插件 · 调试 · 点开";
    public static final String DEV_SUB_EXPANDED = "诊断 · 摘要 · 插件 · 调试 · 点这里收起";
    /** 开发者分组默认折叠（每次进控制台都是折叠的，不记忆）。 */
    public static final boolean DEV_EXPANDED_BY_DEFAULT = false;

    /** 「功能与权限」原 PAGE_FEATURES 里的插件，分到开发者的：设置诊断（diag）、长内容摘要（summary）。 */
    public static final String[] DEV_PLUGINS = {"diag", "summary"};
    /** 分到主动建议的：suggest（{@code SuggestPlug.ID}）。 */
    public static final String[] SUGGEST_PLUGINS = {"suggest"};
    /** 「功能」页不画的（= 开发者 + 主动建议）；其余插件都画在「功能」。 */
    public static final String[] FEATURES_EXCLUDE = {"diag", "summary", "suggest"};

    /** 设置区里某个插件 id 的 PAGE_FEATURES 行落在哪个分组。 */
    public static String groupOfFeaturePlugin(String pluginId) {
        for (String s : DEV_PLUGINS) if (s.equals(pluginId)) return DEV;
        for (String s : SUGGEST_PLUGINS) if (s.equals(pluginId)) return SUGGEST;
        return FEATURES;
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
     * 旧入口 → 新位置对照表：{旧位置, 新位置, 新位置所在源文件（相对 app/src/com/onesay/assistant/v2/）, 新文件里必须出现的标识}。
     * JVM 测试逐条检查「标识」确实在新文件里，保证所有原入口都找得到。
     */
    public static final String[][] MOVES = {
            // ---- 首页
            {"首页 · 状态卡 / 提醒条", "首页 · 状态卡 / 提醒条（不变）", "ui/console/HomePage.java", "col.addView(hero());"},
            {"首页 · 记一记 / 记忆两张卡", "首页 · 两张卡（不变）", "ui/console/HomePage.java", "\"记一记\""},
            {"首页 · 计划区", "首页 · 计划区（不变）", "ui/console/HomePage.java", "\"打开计划\""},
            {"首页 · 模型 · 模型与密钥", "设置 · 模型", "ui/console/HomePage.java", "new AdvancedPage(c)"},
            {"首页 · 设置 · 灵动岛", "设置 · 灵动岛", "ui/console/HomePage.java", "ConsoleHost.PAGE_ISLAND, \"灵动岛\""},
            {"首页 · 设置 · 关于", "设置区下方 · 关于", "ui/console/HomePage.java", "new AboutPage(c)"},
            // ---- 功能与权限 › 功能（PAGE_FEATURES 插件行）
            {"功能与权限 · 功能 · 计划 App", "设置 · 功能 · 计划 App", "ui/console/FeaturesPage.java", "ConsoleGroups.FEATURES_EXCLUDE"},
            {"功能与权限 · 功能 · 日程", "设置 · 功能 · 日程", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 定时任务 / 待办", "设置 · 功能 · 定时任务 / 待办", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 定时任务与主动播报", "设置 · 功能 · 定时任务与主动播报", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 晨间简报上灵动岛", "设置 · 功能 · 晨间简报上灵动岛", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 连接器（MCP / Google）", "设置 · 功能 · 连接器", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 浏览器跑腿", "设置 · 功能 · 浏览器跑腿", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 多设备执行端", "设置 · 功能 · 多设备执行端", "ui/console/FeaturesPage.java", "ConsoleHost.PAGE_FEATURES"},
            {"功能与权限 · 功能 · 主动建议", "设置 · 主动建议", "ui/console/GroupPages.java", "ConsoleGroups.SUGGEST_PLUGINS"},
            {"功能与权限 · 功能 · 设置诊断", "开发者 · 诊断与摘要 · 设置诊断", "ui/console/GroupPages.java", "ConsoleGroups.DEV_PLUGINS"},
            {"功能与权限 · 功能 · 长内容摘要", "开发者 · 诊断与摘要 · 长内容摘要", "ui/console/GroupPages.java", "ConsoleGroups.DEV_PLUGINS"},
            // ---- 功能与权限 › 屏幕与剪贴板 / 权限与运行 / 其他
            {"功能与权限 · 屏幕与剪贴板（识屏 / 圈一下 / 取件码浮卡 / 剪贴板地址芯片 / 保存图片）", "设置 · 隐私 · 屏幕与剪贴板", "ui/console/GroupPages.java", "ConsoleHost.PAGE_SCREEN"},
            {"功能与权限 · 权限与运行 · 悬浮窗", "设置 · 权限保活 · 悬浮窗", "ui/console/GroupPages.java", "\"悬浮窗\""},
            {"功能与权限 · 权限与运行 · 电池优化白名单", "设置 · 权限保活 · 电池优化白名单", "ui/console/GroupPages.java", "\"电池优化白名单\""},
            {"功能与权限 · 权限与运行 · Root 通道", "设置 · 权限保活 · Root 通道", "ui/console/GroupPages.java", "\"Root 通道\""},
            {"功能与权限 · 权限与运行 · 麦克风", "设置 · 权限保活 · 麦克风", "ui/console/GroupPages.java", "\"麦克风\""},
            {"功能与权限 · 其他（没迁移的旧插件卡片）", "开发者 · 诊断与摘要 · 其他", "ui/console/GroupPages.java", "addConsoleCards(c, legacy)"},
            // ---- 语音
            {"语音 · 声纹（录制 / 取消录制）", "设置 · 语音唤醒 · 声纹", "ui/console/VoicePage.java", "\"取消录制\""},
            {"语音 · 唤醒手感（按下即听 / 息屏说「我在」/ 抢话不丢字 / 自适应断句）", "设置 · 语音唤醒 · 唤醒手感", "ui/console/VoicePage.java", "\"唤醒手感\""},
            {"语音 · 唤醒提示音", "设置 · 语音唤醒 · 唤醒提示音", "ui/console/VoicePage.java", "\"唤醒提示音\""},
            {"语音 · 回应 · 语音问就语音答", "设置 · 朗读 · 语音问就语音答", "ui/console/VoicePage.java", "\"语音问就语音答\""},
            {"语音 · 回应 · 朗读完整回答", "设置 · 朗读 · 朗读完整回答", "ui/console/VoicePage.java", "\"朗读完整回答\""},
            {"语音 · 回应 · 打字问也读出来", "设置 · 朗读 · 打字问也读出来", "ui/console/VoicePage.java", "\"打字问也读出来\""},
            {"语音 · 回应 · 回答后接着听", "设置 · 朗读 · 回答后接着听", "ui/console/VoicePage.java", "\"回答后接着听\""},
            {"语音 · 回应 · 显示「正在听 / 识别中」", "设置 · 朗读 · 显示「正在听 / 识别中」", "ui/console/VoicePage.java", "\"显示「正在听 / 识别中」\""},
            {"语音 · 思考模式", "设置 · 模型 · 思考模式", "ui/console/AdvancedPage.java", "\"思考模式\""},
            // ---- 模型与密钥
            {"模型与密钥 · API 密钥 / 视觉模型 / 读数直答", "设置 · 模型", "ui/console/AdvancedPage.java", "\"API 密钥\""},
            {"模型与密钥 · 账户 · 邮箱（IMAP）", "设置 · 模型 · 账户（不变）", "ui/console/AdvancedPage.java", "MailSettingsActivity.class"},
            {"模型与密钥 · 外观 · 浅色外观", "设置 · 模型 · 外观（不变）", "ui/console/AdvancedPage.java", "\"浅色外观\""},
            {"模型与密钥 · 插件 · 功能插件", "开发者 · 功能插件", "ui/console/HomePage.java", "new AdvancedPage.PluginsPage(c)"},
            {"模型与密钥 · 开发者（开发者模式）", "开发者 · 调试工具（开发者模式）", "ui/console/HomePage.java", "new DeveloperPage(c)"},
            // ---- 深链
            {"唤醒卡片「去配置」console:asr.key", "设置 · 模型 + 语音识别 Key 弹窗（不变）", "ui/console/Console.java", "nav.push(new AdvancedPage(this));"},
    };
}
