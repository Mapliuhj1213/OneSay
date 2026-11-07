package com.onesay.assistant.v2.kernel;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * [kernel] 控制台（MainActivity）给插件画界面用的宿主接口：统一外观，插件只管内容。
 *
 * <p><b>v2（R12 控制台重设计）</b>：控制台从「一页堆卡片」改成页面栈（首页 → 功能与权限 / 记忆 / 灵动岛 / … → 细项页）。
 * 插件实现 {@link FeaturePlugin#consoleV2()} = true，并在 {@link FeaturePlugin#addConsoleEntries(ConsoleHost, String, LinearLayout)}
 * 里按页面 id（见 {@link #PAGE_FEATURES} 等）往页面里加「分组 + 行」；细项页用 {@link #openPage(String, String)} 打开，
 * 内核再按同一个 id 回调插件画内容。
 *
 * <p><b>v1（兼容）</b>：没迁移的插件仍然实现 {@link FeaturePlugin#addConsoleCard(ConsoleHost, LinearLayout)}，
 * 用 {@link #sectionTitle}/{@link #card}/{@link #uxSwitch}/{@link #button} 画「标题 + 卡片」，
 * 控制台把它们画在「功能与权限 › 其他」里（新配色），不会丢。
 */
public interface ConsoleHost {

    // ------------------------------------------------------------------ 页面 id（插件按 id 往对应位置加内容）
    /** 功能与权限 › 功能（每个功能一行：右边开关、左边点进细项页）。 */
    String PAGE_FEATURES = "skills.features";
    /** 功能与权限 › 屏幕与剪贴板（R12 第一次给 S1 / 识屏 / 存图加 UI，D6）。 */
    String PAGE_SCREEN = "skills.screen";
    /** 记忆页。 */
    String PAGE_MEMORY = "memory";
    /** 灵动岛页。 */
    String PAGE_ISLAND = "island";
    /** 记一记设置（记一记列表右上角「设置」）。 */
    String PAGE_QUICKNOTE = "quicknote";

    /** kill-switch 文件存在时，行的副标题统一显示这句（文件名只在开发者页出现）。 */
    String KILLED = "已被停用（开发者设置）";

    int TONE_NORMAL = 0, TONE_OK = 1, TONE_WARN = 2, TONE_BAD = 3, TONE_MUTED = 4;

    interface Toggle {
        void onToggle(boolean on);
    }

    /** 一行设置（可以随后改副标题 / 开关 / 可用状态）。 */
    interface Row {
        View view();

        void setSub(String sub);

        void setSubTone(int tone);

        void setValue(String value, int tone);

        void setOn(boolean on);

        boolean isOn();

        /** 不可用：整行变淡、开关点不动（父开关关着 / 被 kill 文件停用）。 */
        void setEnabled(boolean enabled);
    }

    Activity activity();

    int dp(int v);

    // ------------------------------------------------------------------ v2

    /** 往 root 里加一个分组（title 为 null = 不要组标题），返回分组容器；行用下面的方法往里加，分隔线自动加。 */
    LinearLayout group(LinearLayout root, String title);

    /** 标题 + 副标题 + 右侧开关（整行点 = 切换）。 */
    Row switchRow(LinearLayout group, String title, String sub, boolean on, Toggle t);

    /** 左边点进细项页、右边开关（AOSP「列表项 + 开关」）。 */
    Row switchLinkRow(LinearLayout group, String title, String sub, boolean on, Toggle t, Runnable open);

    /** 点进下一页的行（右侧 ›）。 */
    Row linkRow(LinearLayout group, String title, String sub, Runnable open);

    /** 只读状态行（右侧状态文字，tone 决定颜色）。 */
    Row valueRow(LinearLayout group, String title, String sub, String value, int tone);

    /** 右侧一个胶囊按钮（「加入」「重置」「去授权」）。 */
    Row actionRow(LinearLayout group, String title, String sub, String action, Runnable onAction);

    /** 主开关条（苔绿容器色，页面顶部）。 */
    Row mainSwitch(LinearLayout root, String title, boolean on, Toggle t);

    /** 页脚说明（小字、t3 色）。 */
    TextView footer(LinearLayout root, String text);

    /** 打开一个由插件画内容的细项页（内核按 pageId 回调 addConsoleEntries）。 */
    void openPage(String pageId, String title);

    void toast(String msg);

    /** 二次确认对话框（主题色）。 */
    void confirm(String title, String msg, String ok, Runnable yes);

    /** 主线程刷新（后台线程读完数据后用）。 */
    void ui(Runnable r);

    boolean devMode();

    // ------------------------------------------------------------------ v1（兼容未迁移的插件）

    /** 小节标题。 */
    TextView sectionTitle(String text);

    /** 圆角卡片（竖直 LinearLayout）。 */
    LinearLayout card();

    /** 统一样式的开关行。 */
    Switch uxSwitch(String label, boolean on, CompoundButton.OnCheckedChangeListener l);

    /** 统一样式的整宽按钮（color 被忽略，统一成主题样式）。 */
    Button button(String text, int color);
}
