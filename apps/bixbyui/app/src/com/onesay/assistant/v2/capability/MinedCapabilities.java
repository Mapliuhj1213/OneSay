package com.onesay.assistant.v2.capability;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 自动挖掘出的系统接口加载器（新增）。
 *
 * <h3>它解决什么问题</h3>
 * <p>{@link BuiltinCapabilities} 是**手写**的能力表（1638 行、100 余条），
 * 覆盖的是"我们想到要支持"的操作。但设备上有 370 个系统应用，
 * 它们各自声明了一堆可被外部调用的界面（intent），手写永远追不上。
 *
 * <p>本轮做的事：把每个系统包的 {@code pm dump} 里的
 * <b>Activity Resolver Table</b> 扒下来，抽出 LAUNCHER / VOICE / 标准 action
 * 三类可调用接口，编译成资产 {@code assets/mined_intents.json}，
 * 由本类在注册表初始化时批量注册 —— 让"打开某界面"这件事**不再靠手写**。
 *
 * <h3>这些能力长什么样</h3>
 * <pre>
 *   sign      = clock.set_alarm        （&lt;软件&gt;.&lt;动作&gt;）
 *   summary   = 打开 set_alarm 界面      （自动生成的中文摘要）
 *   executor  = shell
 *   cmd       = am start -n &lt;组件全名&gt;    （经 root 守护执行）
 * </pre>
 * 归入 {@link InterfaceKind.Kind#UI}（"调用界面"），不是状态变更 ——
 * 执行成功只代表"界面打开了"，不代表"事情做完了"。这一点
 * {@link InterfaceKind} 的类型判定已保证（executor=intent 或 cmd 含 am start → UI）。
 *
 * <h3>为什么不设 risk / confirm</h3>
 * <p>拉起一个界面**不改任何系统状态**，属于最安全的操作类别，
 * 因此 risk=0、confirm=false。真正的风险在用户在界面里做的操作，
 * 那已经不是我们这条链路能碰到的了。
 *
 * <h3>加载失败为什么静默</h3>
 * <p>资产缺失 / 解析出错**绝不能**阻断注册表初始化 ——
 * 手写的 {@link BuiltinCapabilities} 才是基本盘，挖掘产物是增量。
 * 少加载一批界面接口，助手照常工作；初始化抛异常，整个助手就起不来了。
 */
public final class MinedCapabilities {

    private static final String TAG = "MinedCapabilities";
    private static final String ASSET = "mined_intents.json";

    private static final String[] EMPTY = new String[0];

    /**
     * 动作段 → 中文关键词（口语别名表）。
     *
     * <p>只收"用户真会这么说"的短词。给多一个别名就多一次召回机会，
     * 但**错一个别名会误召回** —— 所以宁可少收，不收模糊词。
     */
    private static final java.util.Map<String, String[]> EXACT_CN =
            new java.util.HashMap<String, String[]>();

    static {
        // ---- 系统设置各面板 ----
        put("bluetooth_settings", "蓝牙");
        put("wifi_settings", "wifi", "无线网络", "无线网");
        put("wifi_settings_2", "wifi", "无线网络");
        put("wireless_settings", "无线和网络", "无线网络");
        put("configure_wifi_settings", "wifi", "无线网络");
        put("wifi_p2p_settings", "wifi直连", "直连");
        put("wifi_ap_settings", "热点", "移动热点", "共享网络");
        put("wifi_ap_settings_2", "热点", "移动热点");
        put("wifi_calling_settings", "wifi通话", "无线通话");
        put("location_settings", "定位", "位置", "定位服务");
        put("display_settings", "显示", "屏幕显示");
        put("sound_settings", "声音", "音量设置");
        put("security_settings", "安全");
        put("lock_and_security_settings", "锁屏和安全", "安全");
        put("privacy_settings", "隐私");
        put("privacy_reset_settings", "隐私重置");
        put("accessibility_settings", "无障碍");
        put("device_info_settings", "关于手机", "设备信息");
        put("date_time_settings", "日期和时间", "时间和日期", "时间日期");
        put("language_and_input_settings", "语言和输入法", "语言输入");
        put("locale_picker", "语言", "语言设置");
        put("input_method_and_subtype_enabler", "输入法");
        put("user_dictionary_settings", "用户词典", "词典");
        put("spell_checkers_settings", "拼写检查");
        put("storage_dashboard", "存储", "存储空间");
        put("storage_use", "存储占用");
        put("data_usage", "流量", "数据流量");
        put("network_dashboard", "网络", "网络设置");
        put("connected_device_dashboard", "连接设备", "已连接设备");
        put("tether_settings", "网络共享", "共享网络");
        put("vpn_settings", "vpn");
        put("apn_settings", "apn", "接入点");
        put("apn_editor", "apn", "接入点");
        put("home_settings", "主屏幕", "桌面设置");
        put("backup_settings", "备份");
        put("cloud_account_settings", "云账户", "云");
        put("device_admin_settings", "设备管理器");
        put("device_visibility_settings", "可见性", "设备可见");
        put("dock_settings", "底座");
        put("print_settings", "打印");
        put("usb_settings", "usb");
        put("sim_status", "sim卡状态", "sim卡");
        put("sim_lock_status", "sim卡锁");
        put("imei_information", "imei");
        put("status", "状态");
        put("battery_saver_mode_voice_activity", "省电模式");
        put("lockscreen_menu", "锁屏");
        put("nearby_settings", "附近设备");
        put("running_services", "运行中的服务", "后台服务");
        put("manage_applications", "应用管理", "管理应用");
        put("default_autofill_picker", "自动填充");
        put("dock_settings_2", "底座");
        // ---- 时钟 ----
        put("set_alarm", "闹钟", "设闹钟", "定闹钟");
        put("dismiss_alarm", "关闭闹钟", "关闹钟");
        put("snooze_alarm", "贪睡", "再睡一会");
        put("show_alarms", "闹钟", "闹钟列表", "查看闹钟");
        put("set_timer", "计时器", "倒计时", "秒表计时");
        // ---- 笔记 / 日程 / 短信 / 联系人 ----
        put("memo_list", "笔记", "备忘录");
        put("memo_picker", "笔记");
        put("native_composer_action_new", "新建笔记", "记笔记");
        put("all_in_one", "日历", "日程");
        put("edit_event", "新建日程", "日程");
        put("agenda_search", "搜索日程", "日程搜索");
        put("calendar_search", "搜索日程", "日程搜索");
        put("conversation_composer", "写短信", "发短信", "短信");
        put("compose_message_mms", "写短信", "发短信", "彩信");
        put("compose_message_mms_no_v_item", "写短信", "发短信");
        put("search", "搜索");
        put("dial", "拨号", "打电话");
        put("dialtacts", "拨号", "电话", "通讯录");
        put("people", "联系人", "通讯录");
        put("contact_editor", "新建联系人", "添加联系人");
        put("call", "打电话", "拨号");
        put("call_settings", "通话设置");
        put("accept_call_dialog", "接听");
        // ---- 浏览器 / 商店 / 相册 / 相机 ----
        put("web_search", "搜索", "网页搜索");
        put("search_result", "搜索结果");
        put("asset_browser", "应用商店");
        put("gallery", "相册", "图库");
        put("camera", "相机", "拍照");
        // ---- 其他 ----
        put("voice_command", "语音指令", "语音命令");
        put("launcher", "桌面", "主屏幕");
        put("start", "启动");
        put("add_apps", "添加应用");
    }

    private static void put(String k, String... v) {
        EXACT_CN.put(k, v);
    }

    private MinedCapabilities() {}

    /**
     * 从资产加载并注册全部挖掘出的接口。
     *
     * @param reg     能力注册表
     * @param context 上下文（用于读取 assets）
     * @return 实际注册的条数（0 表示资产缺失或为空）
     */
    public static int registerAll(CapabilityRegistry reg, Context context) {
        if (reg == null || context == null) return 0;
        String json = readAsset(context);
        if (json == null || json.length() == 0) {
            Log.i(TAG, "无挖掘资产（" + ASSET + "），跳过");
            return 0;
        }
        int ok = registerFromJson(reg, json);
        Log.i(TAG, "挖掘接口注册完成: " + ok + " 条");
        return ok;
    }

    /**
     * 加载**运行时挖掘**资产（{@code files/mined_runtime.json}）。
     *
     * <p>与 {@link #registerAll} 同构：同一份 JSON 结构、同一条 {@link #buildFromIntent}
     * 构造链 —— 离线挖掘与运行时挖掘（probe.app_intents）产出的能力**字段与语义完全一致**。
     * 上次会话挖到的界面接口，这次启动直接可用。
     *
     * @return 实际注册条数（0 = 文件缺失/为空）
     */
    public static int registerRuntime(CapabilityRegistry reg, Context context) {
        if (reg == null || context == null) return 0;
        String json = null;
        try {
            java.io.File f = new java.io.File(context.getFilesDir(),
                    com.onesay.assistant.v2.probe.ProbeEngine.RUNTIME_FILE);
            if (f.exists()) json = readFile(f);
        } catch (Throwable t) {
            Log.w(TAG, "运行时挖掘资产读取失败: " + t.getMessage());
            return 0;
        }
        if (json == null || json.length() == 0) return 0;
        int ok = registerFromJson(reg, json);
        Log.i(TAG, "运行时挖掘接口注册完成: " + ok + " 条");
        return ok;
    }

    /** 共用的注册循环：解析 JSON → build → 注册（手写/已注册 sign 均跳过）。 */
    private static int registerFromJson(CapabilityRegistry reg, String json) {
        int ok = 0;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("intents");
            if (arr == null) return 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                Capability cap = build(it);
                if (cap == null) continue;
                // 手写能力优先：同名 sign 已被 BuiltinCapabilities 占用时不覆盖
                if (reg.describe(cap.sign) != null) continue;
                reg.register(cap);
                ok++;
            }
        } catch (Exception e) {
            Log.w(TAG, "解析挖掘资产失败: " + e.getMessage());
        }
        return ok;
    }

    private static String readFile(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 供运行时探针（ProbeEngine）复用同一套能力构造逻辑 ——
     * 保证"离线资产"与"运行时挖掘"产出的能力字段（sign/cmd/keywords/say）完全同源。
     */
    public static Capability buildFromIntent(JSONObject it) {
        return build(it);
    }

    private static Capability build(JSONObject it) {
        // 深挖产出的富接口（深链/私有 action/带标签导出界面/provider）走单独构造；
        //   旧条目没有 kind 字段，仍走下面的原逻辑（行为不变）。
        if (it.optString("kind", "").length() > 0) return buildRich(it);
        String sign = it.optString("sign", "");
        String component = it.optString("component", "");
        if (sign.length() == 0 || component.length() == 0) return null;
        String sw = it.optString("software", "");
        String action = it.optString("summary", "");

        // 中文摘要：把动作名转成"打开 xx 界面"，比裸 action 名可读得多
        String cn = humanize(action);
        JSONArray flags = it.optJSONArray("flags");
        boolean voice = false;
        if (flags != null) {
            for (int i = 0; i < flags.length(); i++) {
                if ("VOICE".equals(flags.optString(i))) { voice = true; break; }
            }
        }

        Capability.Builder b = new Capability.Builder(sign, "打开" + cn + "界面")
                .domain(sw)
                .category(sw)
                .verbs("打开", "进入")
                .entities("app")
                .examples("打开" + cn)
                .keywords(cn, sw)
                .sideEffect(SideEffect.NONE)
                .risk(0, false)
                .executor("shell")   // am start 走 root 守护通道，比 Intent 通道更稳
                .cmd("am start -n " + component)
                .reply("已打开" + cn);

        // 补中文关键词 —— 这是"打开wifi设置"能路由到 wifi_settings 的关键。
        //   actseg 是英文（wifi_settings），而用户说的是中文（"wifi设置"）。
        //   只挂英文关键词时 hasDeviceTarget 会漏判（实测 "打开wifi设置" -> false），
        //   于是这句被当成闲聊而不是"打开界面"。补上中文名 + 口语别名。
        String[] cnKw = chineseKeywords(action);
        if (cnKw.length > 0) {
            b.keywords(cnKw);
        }

        // VOICE 类接口是厂商给语音助手留的口，补几条更口语的触发说法
        if (voice) {
            b.say("打开" + cn, cn);
        } else {
            b.say("打开" + cn);
        }
        return b.build();
    }

    /** 只允许的字面量字符（防御：落盘文件被篡改时也不能把任意字符拼进 shell）。 */
    private static final java.util.regex.Pattern RICH_SAFE_URI =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9+._\\-]{0,30}://[A-Za-z0-9._:/~%\\-]{0,150}$");
    /** ★ M-1：标准不透明 scheme 的裸前缀（mailto: 等），不带任何参数。 */
    private static final java.util.regex.Pattern RICH_SAFE_OPAQUE =
            java.util.regex.Pattern.compile("^(mailto|tel|sms|smsto|geo):$");
    private static final java.util.regex.Pattern RICH_SAFE_COMP =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");
    private static final java.util.regex.Pattern RICH_SAFE_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.\\-]{1,120}$");

    /**
     * 深挖接口 → 能力。字面量一律单引号包裹，且先过白名单字符校验。
     * <ul>
     *   <li>DEEPLINK：{@code am start -a android.intent.action.VIEW -d '<uri>' -p <pkg>}；
     *       ★ M-1 只声明 scheme 的（hostless）改用 {@code -n '<comp>'} 显式组件（真机 mailto 有两个组件声明，
     *       {@code -p} 会弹选择器），launcherOnly 的摘要/回复只说「主界面」</li>
     *   <li>ACTION：{@code am start -a '<action>' -n '<comp>'}</li>
     *   <li>ACTIVITY：{@code am start -n '<comp>'}</li>
     *   <li>PROVIDER：{@code content query --uri content://<auth>/{path}}（读应用数据：PRIVATE_DATA_READ + 需确认）</li>
     * </ul>
     * 前三类会切换前台界面（InterfaceKind=UI），sideEffect=DEVICE_CONTROL：可信来源直接执行，
     * 读过不可信内容（tainted）后需确认。
     */
    private static Capability buildRich(JSONObject it) {
        String sign = it.optString("sign", "");
        String kind = it.optString("kind", "");
        String sw = it.optString("software", "");
        String pkg = it.optString("pkg", "");
        String summary = it.optString("summary", sign);
        String label = it.optString("label", "");
        if (sign.length() == 0 || !RICH_SAFE_NAME.matcher(pkg).matches()) return null;
        String cmd;
        SideEffect se = SideEffect.DEVICE_CONTROL;
        int risk = 0;
        boolean confirm = false;
        ParamsSchema schema = new ParamsSchema();
        String reply;
        if ("DEEPLINK".equals(kind)) {
            String uri = it.optString("uri", "");
            boolean hostless = it.optBoolean("hostless", false);
            String comp = it.optString("component", "");
            if (!RICH_SAFE_URI.matcher(uri).matches()
                    && !(hostless && RICH_SAFE_OPAQUE.matcher(uri).matches())) return null;
            if (hostless && RICH_SAFE_COMP.matcher(comp).matches()) {
                cmd = "am start -a android.intent.action.VIEW -d '" + uri + "' -n '" + comp + "'";
            } else {
                cmd = "am start -a android.intent.action.VIEW -d '" + uri + "' -p " + pkg;
            }
            // ★ M-1 兼容旧版落盘条目（10-07 之前挖的没有 hostless 标记）：裸 "xxx://" 同样只算拉起主界面；
            //   旧的 "mailto://" 是错误 URI（且 -p 会弹选择器），直接丢弃，下次挖接口会按新规则重建
            boolean legacyBare = !hostless && uri.endsWith("://");
            if (legacyBare && uri.startsWith("mailto")) return null;
            if (legacyBare) {
                summary = "拉起「" + (label.length() > 0 ? label : sw) + "」主界面（" + uri + " 只声明了 scheme，不能直达具体页面）";
            }
            if (legacyBare || it.optBoolean("launcherOnly", false)) {
                reply = "已打开" + (label.length() > 0 ? label : sw) + "主界面";
            } else {
                reply = "已打开" + (label.length() > 0 ? label : uri);
            }
        } else if ("ACTION".equals(kind)) {
            String act = it.optString("intentAction", "");
            String comp = it.optString("component", "");
            if (!RICH_SAFE_NAME.matcher(act).matches() || !RICH_SAFE_COMP.matcher(comp).matches()) return null;
            cmd = "am start -a '" + act + "' -n '" + comp + "'";
            reply = "已打开" + (label.length() > 0 ? label : sw);
        } else if ("ACTIVITY".equals(kind)) {
            String comp = it.optString("component", "");
            if (!RICH_SAFE_COMP.matcher(comp).matches()) return null;
            cmd = "am start -n '" + comp + "'";
            reply = "已打开" + (label.length() > 0 ? label : sw);
        } else if ("PROVIDER".equals(kind)) {
            String auth = it.optString("authority", "");
            if (!RICH_SAFE_NAME.matcher(auth).matches()) return null;
            cmd = "content query --uri content://" + auth + "/{path}";
            se = SideEffect.PRIVATE_DATA_READ;
            risk = 2;
            confirm = true;
            schema.addString("path", false, "");
            reply = "已查询应用数据";
        } else {
            return null;
        }
        Capability.Builder b = new Capability.Builder(sign, summary)
                .domain(sw)
                .category(sw)
                .verbs("打开", "进入", "查看")
                .entities("app")
                .keywords(sw)
                .sideEffect(se)
                .risk(risk, confirm)
                .executor("shell")
                .schema(schema)
                .cmd(cmd)
                .reply(reply);
        JSONArray kws = it.optJSONArray("keywords");
        if (kws != null) {
            for (int i = 0; i < kws.length(); i++) {
                String k = kws.optString(i, "");
                if (k.length() > 0) b.keywords(k);
            }
        }
        // 不生成 say（L0 规则）：深挖接口只给 AgentLoop 用，不改变 L0 直达路由（行为不变、防模板污染）
        if (label.length() > 0) b.examples("打开" + label);
        return b.build();
    }

    /**
     * 从动作段推导**中文关键词**（供目标识别与倒排索引）。
     *
     * <p>为什么必须手工映射而不能机翻：用户说的词是口语（"wifi设置"/"蓝牙"/"亮度"），
     * 而组件类名是英文驼峰（WifiSettingsActivity）。这里维护的是一张
     * **口语 → 语义**的窄表，覆盖设置界面里最高频的那几十个面板。
     * 未收录的返回空数组（**不编造**，宁可少一个关键词也不要错误关键词）。
     */
    private static String[] chineseKeywords(String action) {
        if (action == null) return EMPTY;
        String a = action.toLowerCase();
        // 精确匹配优先
        String[] exact = EXACT_CN.get(a);
        if (exact != null) return exact;
        // 前缀匹配（wifi_settings_2 → wifi_settings 的规则）
        int cut = a.lastIndexOf('_');
        while (cut > 0) {
            String head = a.substring(0, cut);
            exact = EXACT_CN.get(head);
            if (exact != null) return exact;
            cut = a.lastIndexOf('_', cut - 1);
        }
        return EMPTY;
    }

    /**
     * 把 action 名转成可读中文片段。
     * 已知的常用动作给精修译文，其余按"去下划线、首字母大写"兜底 ——
     * 重点是不让 sign 的原始英文串直接顶到用户面前。
     */
    private static String humanize(String action) {
        if (action == null) return "";
        String a = action.toLowerCase();
        if ("main".equals(a) || "launcher".equals(a)) return "";
        if (a.startsWith("set_alarm")) return "闹钟设置";
        if (a.startsWith("set_timer")) return "计时器";
        if (a.startsWith("show_alarm")) return "闹钟列表";
        if (a.startsWith("dismiss_alarm")) return "关闭闹钟";
        if (a.startsWith("snooze_alarm")) return "贪睡";
        if ("view".equals(a)) return "";
        if ("web_search".equals(a)) return "网页搜索";
        if ("send".equals(a) || "sendto".equals(a)) return "分享";
        if ("edit".equals(a)) return "编辑";
        // 兜底：set_alarm → 设置闹钟
        String s = a.replace('_', ' ');
        return s;
    }

    private static String readAsset(Context context) {
        InputStream in = null;
        BufferedReader r = null;
        try {
            AssetManager am = context.getAssets();
            in = am.open(ASSET);
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }
}
