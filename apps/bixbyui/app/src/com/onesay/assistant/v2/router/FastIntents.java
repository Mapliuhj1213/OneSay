package com.onesay.assistant.v2.router;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/triage-fast：零 LLM 快路径模板（分诊第 0 层）。
 *
 * <h3>为什么要有它</h3>
 * <p>L0RuleRouter 只认能力表里逐字写死的 say 模板（加几条口语剥离变体）。高频指令换个说法
 * （「锁定屏幕」「把后台都关了」「回到主屏幕」「下一曲」「音量调到最大」「开一下勿扰」）就全部漏到
 * TriageDecider 的 AMBIGUOUS → LLM 分类 → AgentLoop，甚至被模型拿去开「应用管理」点界面。
 * 用户 2026-11-02 原话：「锁屏这种任务直接模式识别模板化就好了」「明明可以很快但他选择了最慢的方式」。
 *
 * <h3>怎么判</h3>
 * <p>纯函数、零 Android 依赖：归一化（{@link TriageDecider#normalize}）→ 剥礼貌前后缀 → 一组<b>整句锚定</b>的正则，
 * 命中即给出 (能力签名, 参数)。整句锚定保证复合句（「锁屏然后…」）绝不会被吞成单步；复合句交给
 * {@link CompoundSplitter} 按子句逐个回到这里。
 *
 * <p>只收「一说就知道做什么、零参数或参数可从句中确定」的设备动作；需要选人 / 选 App / 填内容的
 * （打电话给 X、导航到 X、发消息）不在这里，仍由 L0 模板 / 角色层 / LLM 处理。
 *
 * <p>Java 7 语法（无 lambda / stream）。
 */
public final class FastIntents {

    private FastIntents() {}

    /** 总开关（默认开；JVM 修前/修后对照与紧急回退用）。 */
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean on) {
        sEnabled = on;
    }

    public static boolean enabled() {
        return sEnabled;
    }

    /** 一条命中。 */
    public static final class Hit {
        public final String sign;
        public final JSONObject args;
        /** 命中的规则名（日志 / 覆盖矩阵）。 */
        public final String rule;

        Hit(String sign, JSONObject args, String rule) {
            this.sign = sign;
            this.args = args;
            this.rule = rule;
        }

        public Invocation toInvocation(String query) {
            return new Invocation(sign, args, 1.0, Invocation.RouteSource.L0_RULE, query);
        }

        @Override public String toString() {
            return sign + args + "#" + rule;
        }
    }

    // ------------------------------------------------------------------ 归一化

    private static final String[] PREFIX = {
            "你能不能帮我把", "你能不能帮我", "能不能帮我把", "能不能帮我", "可不可以帮我把", "可不可以帮我", "可以帮我把", "可以帮我",
            "麻烦你帮我把", "麻烦帮我把", "麻烦你帮我", "麻烦帮我", "请你帮我把", "请帮我把", "请你帮我", "请帮我", "帮我把", "给我把",
            "能不能把", "可不可以把", "可以把", "能不能", "可不可以", "你能", "你可以", "可以", "能", "麻烦你", "麻烦", "请你", "请",
            "帮我", "帮忙", "给我", "替我", "快点把", "快把", "赶紧把", "快点", "赶紧", "马上", "立刻", "现在", "再帮我", "再把", "再", "把", "去",
    };
    private static final String[] SUFFIX = {
            "好不好", "可以吗", "行不行", "好吗", "行吗", "谢谢你", "谢谢", "一下吧", "一下子", "一下", "吧", "呗", "呀", "啊", "哦", "喔", "嘛", "啦",
    };

    /** 剥礼貌框架（前后缀可重复，最多 6 轮）；中置「一下」也去掉（「开一下勿扰」→「开勿扰」）。 */
    static String clean(String utterance) {
        return clean(utterance, true);
    }

    /** @param infix 是否去掉中置「一下」（发短信的正文不能动，传 false） */
    static String clean(String utterance, boolean infix) {
        String q = TriageDecider.normalize(utterance);
        if (q.length() == 0) return q;
        String cur = q;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 6) {
            changed = false;
            for (String p : PREFIX) {
                if (cur.startsWith(p) && cur.length() > p.length() + 1) {
                    cur = cur.substring(p.length());
                    changed = true;
                    break;
                }
            }
            for (String s : SUFFIX) {
                if (cur.endsWith(s) && cur.length() > s.length() + 1) {
                    cur = cur.substring(0, cur.length() - s.length());
                    changed = true;
                    break;
                }
            }
        }
        if (infix && cur.length() > 3) cur = cur.replace("一下", "");
        return cur;
    }

    // ------------------------------------------------------------------ 规则表

    private static final class Rule {
        final String name;
        final Pattern p;
        final String sign;

        Rule(String name, String regex, String sign) {
            this.name = name;
            this.p = Pattern.compile("^(?:" + regex + ")$");
            this.sign = sign;
        }
    }

    private static final String ON = "(?:打开|开启|开|启动|启用|开开|打开下|开下)";
    private static final String OFF = "(?:关闭|关掉|关|关上|关了|停用|禁用|断开|关下)";
    private static final String TAIL_ON = "(?:打开|开启|开|开起来|打开吧|开了)";
    private static final String TAIL_OFF = "(?:关闭|关掉|关|关了|关上|断开|关掉了)";

    /** 可切换对象 → 签名前缀（on/off 拼后缀）。顺序：长词在前。 */
    private static final String[][] TOGGLES = {
            // 对象正则, 开签名, 关签名
            {"(?:勿扰模式|免打扰模式|请勿打扰模式|勿扰|免打扰|请勿打扰|防打扰)", "dnd.on", "dnd.off"},
            {"(?:飞行模式|飞机模式|航班模式)", "connectivity.airplane.on", "connectivity.airplane.off"},
            {"(?:wifi|wlan|wi-fi|无线网络|无线网|无线局域网|无线)", "connectivity.wifi.on", "connectivity.wifi.off"},
            {"(?:蓝牙)", "connectivity.bt.on", "connectivity.bt.off"},
            {"(?:移动数据|蜂窝数据|蜂窝网络|移动网络|数据流量|流量|数据)", "connectivity.data.on", "connectivity.data.off"},
            {"(?:定位服务|位置服务|位置信息|定位|gps|位置)", "connectivity.gps.on", "connectivity.gps.off"},
            {"(?:nfc|近场通信)", "connectivity.nfc.on", "connectivity.nfc.off"},
            {"(?:自动旋转|屏幕旋转|旋转屏幕|自动转屏|转屏)", "settings.rotation.on", "settings.rotation.off"},
            {"(?:省电模式|节电模式|省电|超级省电)", "battery.saver.on", "battery.saver.off"},
            {"(?:自动亮度|自动调节亮度|亮度自动调节)", "settings.autobrightness.on", "settings.autobrightness.off"},
            {"(?:手电筒|手电|闪光灯)", "torch.on", "torch.off"},
            {"(?:移动热点|个人热点|wifi热点|热点)", "settings.page.hotspot", "settings.page.hotspot"},
            {"(?:触摸振动|按键振动|按键震动|触感反馈)", "settings.haptic.on", "settings.haptic.off"},
    };

    private static final List<Rule> RULES = new ArrayList<Rule>();

    static {
        // 锁屏 / 息屏（root input keyevent 26）
        RULES.add(new Rule("lock",
                "锁屏|锁屏幕|锁一下屏|锁下屏|锁个屏|锁定屏幕|锁定手机|锁上屏幕|锁上手机|锁手机|锁住屏幕|锁住手机|屏幕锁上|屏幕锁定|屏幕锁了|屏幕锁住"
                        + "|手机锁上|手机锁了|关屏|关屏幕|关闭屏幕|关掉屏幕|屏幕关掉|屏幕关了|屏幕关闭|熄屏|熄灭屏幕|息屏|黑屏|灭屏",
                "screen.lock"));
        // 清空后台（系统级：守护内建模板 → 读屏助手点「全部关闭」兜底；绝不打开应用管理）
        RULES.add(new Rule("recents.clear",
                "(?:一键)?(?:清空|清理|清除|清掉|清了|关闭|关掉|关了|杀掉|杀死|杀|结束|干掉|清)(?:所有|全部|一下)?(?:的)?"
                        + "(?:后台|后台应用|后台的应用|后台程序|后台任务|后台软件|后台进程|后台运行的应用|最近任务|最近应用|最近的应用|多任务"
                        + "|所有应用|全部应用|所有程序|所有软件|所有app|运行的应用|正在运行的应用|内存)"
                        + "|(?:后台|后台应用|后台程序|最近任务|所有应用)(?:全部|都|全)?(?:清空|清理|清掉|清了|关闭|关掉|关了|杀掉|关)"
                        + "|一键清理|一键加速|一键清空|清理内存|释放内存|清内存|杀后台|清后台|内存清理|后台清理|全部关闭|关闭全部|全部清除",
                "sys.recents.clear"));
        // 回桌面（keyevent 3）
        RULES.add(new Rule("home",
                "(?:返回|回到|回|退回|切回|退到|去)(?:手机)?(?:桌面|主屏幕|主屏|主页|主界面|首页|home)|桌面|主屏幕|按home键|按主页键|home键|主页键",
                "nav.home"));
        // 返回（keyevent 4）
        RULES.add(new Rule("back",
                "返回|后退|退回|回退|返回上一页|返回上一级|返回上一步|返回上个页面|返回上一个页面|返回上一个界面|回到上一页|回上一页|上一页|按返回键|返回键",
                "nav.back"));
        // 最近任务界面（keyevent 187）
        RULES.add(new Rule("recents",
                "(?:打开|看看|显示|查看|看一下|切到)?(?:最近任务|最近应用|最近使用的应用|多任务|多任务界面|后台任务|任务管理)(?:界面|列表)?|看看后台|打开后台|后台列表",
                "nav.recents"));
        // 截屏
        RULES.add(new Rule("screenshot",
                "截屏|截图|截个图|截个屏|截一张图|截一张|截张图|截一张屏|截一下屏|截下屏|截屏幕|屏幕截图|截取屏幕|截下屏幕|截一下图|截下图|截一个图",
                "ui.screenshot"));
        // 媒体键（keyevent 126/127/87/88）
        RULES.add(new Rule("media.pause",
                "暂停|暂停播放|暂停音乐|暂停一下音乐|音乐暂停|歌暂停|停一下歌|别放了|不要放了|停止播放|停止音乐|停止放歌|别唱了|先暂停",
                "media.pause"));
        RULES.add(new Rule("media.next",
                "下一首|下一曲|下首|下一首歌|切歌|换一首|换首歌|换一首歌|换歌|下一个歌|播放下一首|切到下一首|切下一首|放下一首",
                "media.next"));
        RULES.add(new Rule("media.prev",
                "上一首|上一曲|上首|上一首歌|播放上一首|切回上一首|切上一首|放上一首|回到上一首",
                "media.prev"));
        RULES.add(new Rule("media.play",
                "继续播放|恢复播放|继续放|接着放|接着播|继续放歌|继续听|播放|继续播|开始播放|音乐继续"
                        // ★ int/1102 常识（探针：「播放音乐」进了 AgentLoop）：没点歌名 / App 的「放音乐」= 媒体播放键（接着放上次的播放器）
                        + "|播放音乐|播放歌曲|放音乐|放点音乐|放首歌|放一首歌|来点音乐|来首歌|来一首歌|放歌|放个歌|开始放歌",   // 「我想听歌」仍留给分诊（FastIntentsTest 负例：可能要挑歌 / App）
                "media.play"));
        // 拍照（系统相机 intent，零 LLM）
        RULES.add(new Rule("camera",
                "拍照|拍张照|拍张照片|拍个照|拍个照片|拍一张|拍一张照片|拍照片|照相|照张相|自拍|来张自拍|打开相机拍照|打开相机|开相机|启动相机",
                "camera.open"));
        // 重启 / 关机（能力本身 risk=2 + 确认卡，模板只负责快）
        RULES.add(new Rule("reboot", "重启|重启手机|重新启动|重新启动手机|手机重启|重启一下手机|重启设备|重启系统", "sys.reboot"));
        RULES.add(new Rule("poweroff", "关机|关闭手机|手机关机|关掉手机|把手机关了|关手机", "sys.poweroff"));
        // 静音（手机静音 = 来电铃声静音；媒体静音另有 L0 模板）
        RULES.add(new Rule("mute",
                "静音|手机静音|调成静音|切换到静音|开静音|打开静音|静音模式|开启静音模式|打开静音模式|设为静音|设成静音",
                "volume.ring.mute"));
    }

    // 音量 / 亮度（带数值）
    private static final Pattern VOL_UP = Pattern.compile(
            "^(?:(?:媒体)?(?:音量|声音)(?:调大|调高|加大|增大|提高|大一点|大点|大一些|高一点|高点|再大点|再大一点|开大|放大)(?:一点|点|些)?"
                    + "|(?:调大|调高|加大|增大|提高|开大)(?:一点|点)?(?:音量|声音)|大声一点|大声点|大点声|再大声点|再大声一点|大声些|大声一些)$");
    private static final Pattern VOL_DOWN = Pattern.compile(
            "^(?:(?:媒体)?(?:音量|声音)(?:调小|调低|减小|降低|小一点|小点|小一些|低一点|低点|再小点|再小一点|关小|放小)(?:一点|点|些)?"
                    + "|(?:调小|调低|减小|降低|关小)(?:一点|点)?(?:音量|声音)|小声一点|小声点|小点声|再小声点|再小声一点|小声些|小声一些|太吵了|好吵)$");
    private static final Pattern VOL_SET = Pattern.compile(
            "^(?:媒体|音乐)?(?:音量|声音)(?:调到|调成|调为|设为|设成|设置为|设置成|开到|放到|改成|改为)?(?:百分之)?(.+?)(?:%|格)?$");
    private static final Pattern BRI_UP = Pattern.compile(
            "^(?:(?:屏幕)?亮度(?:调高|调大|提高|加大|高一点|高点|大一点|亮一点|再高点|再亮点)(?:一点|点|些)?|(?:调高|调大|提高)(?:一点|点)?(?:屏幕)?亮度"
                    + "|(?:屏幕)?(?:调亮|亮一点|亮点|再亮一点|再亮点|亮一些)(?:一点|点)?|屏幕太暗了|太暗了|好暗|看不清屏幕|屏幕太暗)$");
    private static final Pattern BRI_DOWN = Pattern.compile(
            "^(?:(?:屏幕)?亮度(?:调低|调小|降低|减小|低一点|低点|小一点|暗一点|再低点|再暗点)(?:一点|点|些)?|(?:调低|调小|降低)(?:一点|点)?(?:屏幕)?亮度"
                    + "|(?:屏幕)?(?:调暗|暗一点|暗点|再暗一点|再暗点|暗一些)(?:一点|点)?|屏幕太亮了|太亮了|太刺眼了|好亮|屏幕太亮|太刺眼)$");
    private static final Pattern BRI_SET = Pattern.compile(
            "^(?:屏幕)?亮度(?:调到|调成|调为|设为|设成|设置为|设置成|开到|改成|改为)?(?:百分之)?(.+?)(?:%)?$");
    // 「N分钟后提醒我…」「倒计时N分钟」「定个N分钟的计时器」→ 系统计时器（SET_TIMER，SKIP_UI）
    private static final Pattern TIMER_REL = Pattern.compile(
            "^(.+?)(秒钟|秒|分钟|分|个小时|小时|个钟头|钟头)(?:以后|之后|后)(?:提醒我|叫我|喊我|提醒一下|提醒)(.*)$");
    private static final Pattern TIMER_SET = Pattern.compile(
            "^(?:设|定|设置|开|开始)?(?:个|一个)?(?:倒计时|计时|计时器|定时器)?(.+?)(秒钟|秒|分钟|分|个小时|小时)(?:的)?(?:倒计时|计时器|计时|定时器|定时)$"
                    + "|^(?:倒计时|计时)(.+?)(秒钟|秒|分钟|分|个小时|小时)$");

    // 闹钟：「明天早上6点半叫我起床」「设个下午3点15的闹钟」（一次性；「每天」类重复闹钟不在这里）
    private static final Pattern ALARM = Pattern.compile(
            "^(?:设|定|设置|定个|设个|定一个|设一个|订个|订一个)?(?:明天|今天|今晚|明早|明晚|后天)?(早上|上午|中午|下午|傍晚|晚上|凌晨|早晨|清晨|夜里)?"
                    + "([0-9零一二两三四五六七八九十]+)(?:点|:)(半|一刻|三刻|[0-9零一二两三四五六七八九十]+分?)?(?:钟)?"
                    + "(?:的闹钟|闹钟|叫我起床|叫我|叫醒我|喊我起床|喊我|叫醒)$");
    // ★ int/1102（triage-fast 留下的）：重复闹钟「每天 / 工作日 / 周末 X 点叫醒我」→ clock.alarm.set{hour,minute,days}
    private static final Pattern ALARM_REPEAT = Pattern.compile(
            "^(?:设|定|设置|定个|设个|定一个|设一个|订个|订一个)?(?:个)?(每天|天天|每日|每天都|工作日|每个工作日|工作日都|周一到周五|每周一到周五|周末|每个周末|每周末|周六周日|周末都)"
                    + "(?:的)?(早上|上午|中午|下午|傍晚|晚上|凌晨|早晨|清晨|夜里)?"
                    + "([0-9零一二两三四五六七八九十]+)(?:点|:)(半|一刻|三刻|[0-9零一二两三四五六七八九十]+分?)?(?:钟)?"
                    + "(?:的闹钟|闹钟|叫我起床|叫我|叫醒我|喊我起床|喊我|叫醒|提醒我起床)$");
    // ★ int/1102（triage-fast 留下的）：导航「导航到X / 带我去X / 去X怎么走」→ screen.nav.to{address}
    //   执行走 device-1102 的 ShellPrograms.navigate（定位前置 → geo: → 高德网页）。用 screen.nav.to 而不是 s2.nav.app：
    //   后者 pkg 是必填参数（审计抓到：模板不带包名会触发缺参反问）。级联里角色层（S2 接管「导航…」）排在分诊之前：
    //   有默认地图 App 时仍由它走 s2.nav.app 带上包名（geo: -p）；角色层不接时才落到这条模板。
    private static final Pattern NAV = Pattern.compile(
            "^(?:导航到|导航去|导航至|开导航去|开导航到|导航|带我去|带我到|送我去|送我到)(?!栏|键|条|模式|设置)(.{2,30})$"
                    + "|^(?:去|到)(.{2,30})(?:怎么走|怎么去|的路线|的路)$|^怎么去(.{2,30})$");
    // 打电话 / 发短信（人名交给执行层查通讯录；phone.call / sms.send 本身 risk=2 有确认卡）
    private static final Pattern CALL = Pattern.compile("^(?:给|跟|帮我给|帮我跟)([^说发打]{1,8}?)打(?:个|一个|一通)?电话$");
    private static final Pattern SMS = Pattern.compile(
            "^(?:发短信给|发信息给|发条短信给|给)([^说]{1,10}?)(?:发短信|发信息|发条短信|发个短信)?说(.{1,60})$");
    /**
     * ★ int/1102 常识（探针：「给老王发微信说我到了」被发成短信）：点了微信 / QQ / 钉钉 / 飞书 / 邮件，或只说「发消息」（没说短信）
     * 的都不是短信——交给分诊 → AgentLoop（msg_send 技能：微信无接口 → phone.operate，发送前确认卡）。只看「说」之前的部分（正文里提到微信不算）。
     */
    static final Pattern IM_CHANNEL = Pattern.compile("微信|weixin|wechat|qq|钉钉|飞书|企业微信|邮件|邮箱|私信|消息|whatsapp|telegram");
    /**
     * ★ int/1102 常识（探针：「搜一下周杰伦新歌」进了 AgentLoop）：「搜一下 / 搜索 / 百度一下 X」= 浏览器搜索 X（零 LLM）。
     * 不收：点了 App（「在淘宝搜…」「微信里搜…」→ GUI）、指代屏幕（「搜一下这个」→ 识屏）、附近（地图）、天气（问答）、搜手机里的东西（本机搜索）。
     */
    static final Pattern SEARCH = Pattern.compile(
            "^(?:帮我|给我|你)?(?:搜一下|搜一搜|搜搜|搜索一下|搜索|搜|百度一下|百度搜索|百度搜一下|百度|上网搜一下|上网搜|上网查一下|上网查|网上搜一下|网上搜|网上查一下|网上查)(?:一下)?(?:看看)?[\\s:：，,]*(.{1,40})$");
    static final Pattern SEARCH_NOT = Pattern.compile(
            "^(?:这|那|屏幕|当前|刚才|刚复制)|附近|周边|天气|气温|手机里|本机|相册|文件|设置里|通讯录|联系人|短信里|邮件里|记一记|微信|淘宝|京东|拼多多|抖音|小红书|美团|大众点评|b站|bilibili|知乎|闲鱼|支付宝|app|应用里|应用商店|蓝牙|wifi|wlan|无线网|热点|附近的设备|信号|然后|接着|并且|再把|再发|发给|告诉|分享|总结|翻译");

    static Hit webSearch(String utterance) {
        if (utterance == null) return null;
        if (!SearchRouting.explicitBrowser(utterance)) return null;   // ★ 11-04 ②：没明确要浏览器 → 不拉起网页，交给调研 / 问答（搜索 API）
        String raw = utterance.trim().replaceAll("^(小比|bixby|Bixby)[，,\\s]*", "").replaceAll("[。！？!?，,~～\\s]+$", "");
        raw = raw.replaceFirst("^(?:帮我|给我|你)?(?:打开浏览器|在浏览器里|在浏览器上|在浏览器中|用浏览器|浏览器里|打开网页)(?:帮我|给我)?[\\s，,]*", "");   // ★ 11-04 ②
        Matcher m = SEARCH.matcher(raw);
        if (!m.matches()) return null;
        String kw = m.group(1).trim().replaceAll("^(关于|有关|一下)", "").replaceAll("(吧|呗|啊|呀|看看)$", "").trim();
        if (kw.length() < 2 || SEARCH_NOT.matcher(kw.toLowerCase(java.util.Locale.ROOT)).find()) return null;
        if (com.onesay.assistant.v2.agenttools.GuiFold.thirdPartyAppScope(utterance) != null) return null;
        return new Hit("browser.search", sargs("keyword", kw, null, null), "search");
    }

    /** ★ int/1102 常识（探针：「看看我的邮件」进了 AgentLoop）：看邮件 = 读收件箱最新 3 封的发件人和标题（IMAP，零 LLM，不念正文）。「打开邮箱」仍是开 App。 */
    static final Pattern MAIL_LATEST = Pattern.compile(
            "^(?:看看|看一下|查看|查一下|查查|读一下|念一下|帮我看看|帮我看一下)(?:我的)?(?:最新的?|最近的?)?(?:邮件|邮箱|收件箱|新邮件)(?:有什么|有啥)?$"
                    + "|^(?:我)?(?:有没有|有)新邮件(?:吗|没|没有)?$|^(?:我的)?(?:最新|最近)(?:一封|的)?邮件(?:是什么|说了什么|是啥)?$|^收件箱(?:里)?有什么$");
    /** 「有新邮件吗」：问的是有没有没读的。 */
    static final Pattern MAIL_NEW_Q = Pattern.compile("(?<!最)新邮件");
    // ★ int/1102（用户 2026-11-03 拍板：问邮件先问想看哪类 → 按类型 / 发件人 / 内容关键词读）
    static final String MAIL_VERB = "(?:看看|看一下|看下|查看|查一下|查查|读一下|念一下|念念|帮我看看|帮我看一下|帮我读一下|有没有|有哪些)?";
    static final Pattern MAIL_UNREAD = Pattern.compile("^" + MAIL_VERB + "(?:我的)?(?:未读|没读|没看|还没看|还没读)(?:过)?的?(?:邮件|信)(?:有哪些|有什么|有吗)?$");
    static final Pattern MAIL_FLAGGED = Pattern.compile("^" + MAIL_VERB + "(?:我的)?(?:重要|星标|标星|加星|带星|红旗)的?(?:邮件|信)(?:有哪些|有什么)?$");
    static final Pattern MAIL_FROM = Pattern.compile("^" + MAIL_VERB + "(?:一下)?(.{1,24}?)(?:发来|发给我|寄来|发)的(?:邮件|信|来信)$");
    static final Pattern MAIL_KW = Pattern.compile(
            "^(?:在|帮我在|帮我)?(?:邮件|邮箱|收件箱)(?:里|中)?(?:搜|找|查)(?:一下|一搜|一找)?(?:关于|有关|带)?(.{1,30}?)(?:的邮件|的信)?$"
                    + "|^(?:搜|找|查)(?:一下)?(?:邮件|邮箱|收件箱)(?:里|中)(?:的|关于|有关)?(.{1,30}?)(?:的邮件)?$"
                    + "|^" + MAIL_VERB + "(?:找找|找一下)?(?:关于|有关|提到|写了)(.{1,30}?)的(?:邮件|信)$");

    /** ★ 11-04 F：问数量。 */
    static final Pattern MAIL_COUNT = Pattern.compile(
            "^(?:目前|现在|今天)?(?:我|我的)?(?:目前|现在|今天)?(?:一共|总共)?(?:有|收到|还有)?(?:多少|几)封?(?:未读|没读|新)?的?(?:邮件|信|电子邮件)(?:了|呢|啊|吗)?$"
                    + "|^(?:我的)?(?:邮件|邮箱|收件箱)(?:里)?(?:一共|总共|现在)?有(?:多少|几)封?(?:邮件|信)?(?:了|呢|啊)?$|^(?:未读|新)邮件(?:有)?(?:多少|几)封?$");
    /** ★ 11-04 G：问「有价值 / 重要 / 值得看」的邮件。 */
    static final Pattern MAIL_VALUE = Pattern.compile(
            "^(?:我)?(?:最近|近期|这几天|今天|目前)?(?:有|收到了?)?(?:哪些|什么|啥)(?:比较|特别|真正)?(?:有价值|有用|值得看|值得一看|重要|要紧|需要处理|需要回)的?(?:邮件|信)(?:吗|呢)?$"
                    + "|^(?:最近|近期)?(?:有价值|有用|值得看|重要|要紧)的?(?:邮件|信)(?:有哪些|有什么|有吗)?$|^(?:帮我)?(?:挑|筛|找)(?:一下|几封)?(?:有价值|重要|值得看)的(?:邮件|信)$");

    static Hit mailDigest(String c) {
        if (c == null || c.length() < 3) return null;
        try {
            int n = com.onesay.assistant.v2.executor.MailDigest.expandIndex(c);
            if (n > 0) {
                String subj = com.onesay.assistant.v2.executor.MailDigest.subjectAt(n, System.currentTimeMillis());
                if (subj == null) return null;   // 没有刚列过的邮件：交给后面（不瞎猜是哪封）
                JSONObject a = new JSONObject();
                a.put("keyword", subj.length() > 30 ? subj.substring(0, 30) : subj);
                a.put("count", 1);
                a.put("body", true);
                long uid = com.onesay.assistant.v2.executor.MailDigest.uidAt(n, System.currentTimeMillis());
                if (uid > 0) {   // ★ 11-04 午：按 UID 直读那一封（标题仍带着，取到的信标题对不上就退回按标题过滤）
                    a.put("uid", String.valueOf(uid));
                    a.put("index", n);
                }
                return new Hit("mail.inbox.latest", a, "mail.expand");
            }
            boolean count = MAIL_COUNT.matcher(c).matches();
            boolean value = !count && MAIL_VALUE.matcher(c).matches();
            if (!count && !value) return null;
            JSONObject a = new JSONObject();
            a.put("count", 5);
            a.put("body", false);
            a.put("digest", true);
            a.put("value", value);
            return new Hit("mail.inbox.latest", a, value ? "mail.value" : "mail.count");
        } catch (Exception e) {
            return null;
        }
    }

    /** ★ int/1102：带过滤的读邮件（未读 / 重要 / 某人发的 / 内容关键词）→ mail.inbox.latest{filter|from|keyword}。纯函数（JVM 单测）。 */
    static Hit mailFiltered(String c) {
        if (c == null || c.length() < 3) return null;
        JSONObject a = new JSONObject();
        try {
            a.put("count", 3);
            a.put("body", false);
            if (MAIL_UNREAD.matcher(c).matches()) {
                a.put("filter", "unread");
                return new Hit("mail.inbox.latest", a, "mail.unread");
            }
            if (MAIL_FLAGGED.matcher(c).matches()) {
                a.put("filter", "flagged");
                return new Hit("mail.inbox.latest", a, "mail.flagged");
            }
            Matcher m = MAIL_FROM.matcher(c);
            if (m.matches()) {
                String who = m.group(1).replaceAll("^(?:我的|是|由|一下)", "").trim();
                if (who.length() > 0 && !who.matches("(?:我|我的|最新|最近|新|未读|重要|所有|全部|今天|昨天)")) {
                    a.put("from", who);
                    return new Hit("mail.inbox.latest", a, "mail.from");
                }
            }
            m = MAIL_KW.matcher(c);
            if (m.matches()) {
                String k = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                k = k == null ? "" : k.replaceAll("^(?:的|关于|有关)", "").trim();
                if (k.length() > 0) {
                    a.put("keyword", k);
                    return new Hit("mail.inbox.latest", a, "mail.keyword");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // 只读：存储 / 媒体音量
    private static final Pattern READ_STORAGE = Pattern.compile(
            "^(?:手机)?(?:存储|存储空间|空间|内存空间|储存空间)(?:还)?(?:剩|剩下|还剩|有)多少(?:空间)?|还剩多少(?:存储|空间|存储空间)|存储空间够不够$");
    private static final Pattern READ_VOLUME = Pattern.compile(
            "^(?:现在|当前)?(?:媒体)?(?:音量|声音)(?:是)?(?:多少|多大|几格)$");

    /**
     * 匹配一句话。
     * @return null = 不是快路径句子
     */
    public static Hit match(String utterance) {
        if (!sEnabled || utterance == null) return null;
        String c = clean(utterance);
        if (c.length() == 0 || c.length() > 48) return null;
        for (Rule r : RULES) {
            if (r.p.matcher(c).matches()) return new Hit(r.sign, new JSONObject(), r.name);
        }
        Hit bx = batchDevice(c);   // ★ int/1102 审计：清除所有通知 / 全部静音 / 打开 NFC 刷公交卡
        if (bx != null) return bx;
        Hit t = toggle(c);
        if (t != null) return t;
        if (VOL_UP.matcher(c).matches()) return new Hit("volume.media.up", new JSONObject(), "vol.up");
        if (VOL_DOWN.matcher(c).matches()) return new Hit("volume.media.down", new JSONObject(), "vol.down");
        if (BRI_UP.matcher(c).matches()) return new Hit("settings.brightness.up", new JSONObject(), "bri.up");
        if (BRI_DOWN.matcher(c).matches()) return new Hit("settings.brightness.down", new JSONObject(), "bri.down");
        Matcher m = VOL_SET.matcher(c);
        if (m.matches()) {
            Integer lv = level(m.group(1), 15, c.contains("百分之") || pct(utterance));
            if (lv != null) return new Hit(lv == 0 ? "volume.media.mute" : "volume.media.set", args("level", lv), "vol.set");
        }
        m = BRI_SET.matcher(c);
        if (m.matches()) {
            // ★ int/1102 常识（CommonSenseTest：「亮度调到50%」被当成 50/255）：normalize 会吃掉 %，要看原句；亮度没有用户认识的 0–255 刻度，
            //   说的数一律按百分比（「亮度调到50」= 一半）
            Integer lv = level(m.group(1), 255, true);
            if (lv != null) return new Hit("settings.brightness.set", args("level", Math.max(1, lv)), "bri.set");
        }
        Hit arel = alarmRel(c);   // ★ 11-04 E：「三十分钟后叫醒我」= 闹钟（用户 11-03 定：xx 分钟后叫醒我一律走闹钟），零 LLM、不确认
        if (arel != null) return arel;
        Long sec = timerSeconds(c);
        if (sec != null) return new Hit("clock.timer.set", args("seconds", sec.intValue()), "timer");
        Hit a = alarm(c);
        if (a != null) return a;
        Hit ar = alarmRepeat(c);
        if (ar != null) return ar;
        Hit ab = alarmBatch(c);
        if (ab != null) return ab;
        Hit ad = alarmDelete(c);   // ★ audit-J 3：「删掉6点40的闹钟」→ alarm.cancel{hour,minute}（时分完全匹配，不进 AgentLoop）
        if (ad == null && (utterance.indexOf(':') >= 0 || utterance.indexOf('：') >= 0)) {   // normalize 会吃掉「6:40」的冒号
            ad = alarmDelete(clean(utterance.replaceAll("([0-9]{1,2})[:：]([0-9]{2})", "$1点$2")));
        }
        if (ad != null) return ad;
        Hit nv = nav(c);
        if (nv == null) nv = nav(TriageDecider.normalize(utterance));   // 「去公司怎么走」：clean 会把句首「去」当礼貌前缀剥掉
        if (nv != null) return nv;
        Hit md = mailDigest(c);   // ★ 11-04 F/G：「有多少邮件」「最近有哪些有价值的邮件」「展开第一封」→ IMAP 只读，零 LLM（不再让模型凭空列标题）
        if (md != null) return md;
        Hit mf = mailFiltered(c);   // ★ int/1102：按类型 / 发件人 / 内容读邮件（排在网页搜索前：「搜一下邮件里的发票」不是网页搜索）
        if (mf != null) return mf;
        Hit sr = webSearch(utterance);
        if (sr != null) return sr;
        if (MAIL_LATEST.matcher(c).matches()) {
            JSONObject ma = new JSONObject();
            try {
                ma.put("count", 3);
                ma.put("body", false);
                if (MAIL_NEW_Q.matcher(c).find()) ma.put("filter", "unread");   // 「有新邮件吗」= 看未读
            } catch (Exception ignored) {
            }
            return new Hit("mail.inbox.latest", ma, "mail.latest");
        }
        m = CALL.matcher(c);
        if (m.matches()) return new Hit("phone.call", sargs("number", m.group(1), null, null), "call");
        m = SMS.matcher(clean(utterance, false));
        if (m.matches() && !IM_CHANNEL.matcher(m.group(1)).find() && !IM_CHANNEL.matcher(TriageDecider.normalize(utterance).replaceAll("说.*$", "")).find()) {
            return new Hit("sms.send", sargs("number", m.group(1), "text", m.group(2)), "sms");
        }
        if (READ_STORAGE.matcher(c).matches()) return new Hit("storage.free", new JSONObject(), "read.storage");
        if (READ_VOLUME.matcher(c).matches()) return new Hit("volume.media.get", new JSONObject(), "read.volume");
        return null;
    }

    // ★ int/1102 审计：批量设备动作（目标 = 全部；不追问、不进 AgentLoop）+ NFC 刷卡（前置开关 + 钱包）
    static final Pattern NOTIF_CLEAR_ALL = Pattern.compile(
            "^(?:清除|清空|清理|清掉|删除|删掉|移除)(?:所有|全部|所有的|全部的)?通知(?:栏)?(?:消息|里的消息)?$"
                    + "|^(?:所有|全部)?(?:的)?通知(?:栏)?(?:消息)?(?:全|都|全部|统统)?(?:清了|清掉|清除|清空|删了|删掉|清一下|清理掉)$");
    static final Pattern MUTE_ALL = Pattern.compile(
            "^(?:全部静音|全都静音|一键静音|全部都静音|统统静音)$"
                    + "|^(?:所有|全部)(?:的)?声音(?:都|全|全部)?(?:关了|关掉|关闭|静音|关上)$"
                    + "|^(?:关掉|关闭|关了)(?:所有|全部)(?:的)?声音$|^声音(?:全|全部|都)(?:关了|关掉|关闭)$");
    static final Pattern NFC_TRANSIT = Pattern.compile(
            "^(?:打开|开启|开|用)nfc(?:然后|再|来|去|我要|准备)?(?:刷|坐)(?:公交卡|地铁卡|交通卡|公交|地铁|卡|门禁卡)$"
                    + "|^(?:我要|准备)?刷(?:公交卡|地铁卡|交通卡)$");

    static Hit batchDevice(String c) {
        if (NOTIF_CLEAR_ALL.matcher(c).matches()) return new Hit(com.onesay.assistant.v2.capability.caps.FastCaps.SIGN_NOTIF_CLEAR_ALL, new JSONObject(), "notif.clear.all");
        if (MUTE_ALL.matcher(c).matches()) return new Hit(com.onesay.assistant.v2.capability.caps.FastCaps.SIGN_MUTE_ALL, new JSONObject(), "mute.all");
        if (NFC_TRANSIT.matcher(c).matches()) return new Hit(com.onesay.assistant.v2.capability.caps.FastCaps.SIGN_NFC_TRANSIT, new JSONObject(), "nfc.transit");
        return null;
    }

    // ★ int/1102 审计：批量闹钟（目标 = 全部，不追问几点）
    private static final Pattern ALARM_ALL_DEL = Pattern.compile(
            "^(?:删除|删掉|删了|取消|清空|清除)(?:所有|全部|全部的|所有的)?闹钟$|^(?:把)?(?:所有|全部)?闹钟(?:全|都|全部)(?:删了|删掉|删除|取消|清空|清掉)$|^(?:所有|全部)闹钟(?:都)?(?:删了|删掉|删除|取消)$");
    private static final Pattern ALARM_ALL_OFF = Pattern.compile(
            "^(?:关掉|关闭|关了|停掉)(?:所有|全部|所有的|全部的)闹钟$|^(?:把)?(?:所有|全部)?闹钟(?:全|都|全部)(?:关了|关掉|关闭|停了)$");
    private static final Pattern ALARM_ALL_ON = Pattern.compile(
            "^(?:打开|开启|开)(?:所有|全部|所有的|全部的)闹钟$|^(?:把)?(?:所有|全部)?闹钟(?:全|都|全部)(?:打开|开启|开了)$");

    static Hit alarmBatch(String c) {
        if (ALARM_ALL_DEL.matcher(c).matches() && (c.contains("所有") || c.contains("全") || c.startsWith("清空") || c.startsWith("清除"))) {
            return new Hit("alarm.cancel.all", new JSONObject(), "alarm.all.del");
        }
        if (ALARM_ALL_OFF.matcher(c).matches()) return new Hit("alarm.toggle.all", args("on", false), "alarm.all.off");
        if (ALARM_ALL_ON.matcher(c).matches()) return new Hit("alarm.toggle.all", args("on", true), "alarm.all.on");
        return null;
    }

    // ★ audit-J 3（Gemini 真机 DEL-01：「删掉6点40的闹钟」AgentLoop 0 次调用、纯文本没删）：删 / 关某个钟点的闹钟。
    //   时、分都给到执行器做完全匹配（只说「8点」= 8:00）；没说上午下午 → alt_hour = 另一个钟点，按现有闹钟里实际有的那个来。
    private static final String DEL_HM = "(?:明天|今天|今晚|明早|明晚|后天)?(早上|上午|中午|下午|傍晚|晚上|凌晨|早晨|清晨|夜里)?"
            + "([0-9]{1,2}|[零一二两三四五六七八九十]{1,3})(?:点|:|：)(整|半|一刻|三刻|[0-9]{1,2}分?|[零一二两三四五六七八九十]{1,3}分?)?(?:钟)?(?:的|那个的|那个)?";
    private static final Pattern ALARM_DEL_ONE = Pattern.compile(
            "^(删除|删掉|删了|删去|取消掉|取消|去掉|关掉|关闭|关了)(?:一下)?" + DEL_HM + "闹钟$"
                    + "|^(?:把|将)?" + DEL_HM + "闹钟(?:给我)?(删了|删掉|删除|取消掉|取消|去掉|不要了|关掉|关了|关闭)(?:吧)?$");

    static Hit alarmDelete(String c) {
        Matcher m = ALARM_DEL_ONE.matcher(c);
        if (!m.matches()) return null;
        boolean first = m.group(1) != null;
        String verb = first ? m.group(1) : m.group(8);
        int g = first ? 2 : 5;
        long[] hm = delHourMinute(m.group(g), m.group(g + 1), m.group(g + 2));
        if (hm == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("hour", hm[0]);
            o.put("minute", hm[1]);
            if (hm[2] >= 0) o.put("alt_hour", hm[2]);
            if (verb.startsWith("关")) {
                o.put("on", false);
                return new Hit("alarm.toggle", o, "alarm.off.one");
            }
        } catch (Exception ignored) {
        }
        return new Hit("alarm.cancel", o, "alarm.del.one");
    }

    /** 时段 + 钟点 + 分 → {hour, minute, altHour}；分没说 = 0；没时段且 1–12 点 → altHour = 另一半天（否则 -1）。解析不了 = null。 */
    static long[] delHourMinute(String period, String hs, String ms) {
        if (hs == null) return null;
        Long h = NumberParser.parse(hs);
        if (h == null || h < 0 || h > 24) return null;
        long hour = h, alt = -1;
        if (period != null) {
            if ((period.equals("下午") || period.equals("傍晚") || period.equals("晚上") || period.equals("夜里")) && hour < 12) hour += 12;
            else if (period.equals("中午") && hour < 6) hour += 12;
            else if ((period.equals("凌晨") || period.equals("早上") || period.equals("上午") || period.equals("早晨") || period.equals("清晨")) && hour == 12) hour = 0;
        } else if (hour >= 1 && hour <= 11) {
            alt = hour + 12;
        } else if (hour == 12) {
            alt = 0;
        }
        if (hour == 24) hour = 0;
        long minute = 0;
        if (ms != null && !ms.equals("整")) {
            if (ms.equals("半")) minute = 30;
            else if (ms.equals("一刻")) minute = 15;
            else if (ms.equals("三刻")) minute = 45;
            else {
                String d = ms.endsWith("分") ? ms.substring(0, ms.length() - 1) : ms;
                Long v = NumberParser.parse(d);
                if (v == null || v < 0 || v > 59) return null;
                minute = v;
            }
        }
        if (hour > 23) return null;
        return new long[]{hour, minute, alt};
    }

    /** 重复闹钟：重复词 → days 位掩码（bit0=周日 … bit6=周六），钟点同 {@link #alarm}。 */
    static Hit alarmRepeat(String c) {
        Matcher m = ALARM_REPEAT.matcher(c);
        if (!m.matches()) return null;
        String rep = m.group(1);
        int days = rep.startsWith("周末") || rep.contains("周末") || rep.equals("周六周日") ? 0x41
                : (rep.contains("工作日") || rep.contains("周一到周五")) ? 0x3E : 0x7F;
        // 复用一次性闹钟的钟点解析：拼一句「<时段><钟点>闹钟」交给 alarm()
        String one = (m.group(2) == null ? "" : m.group(2)) + m.group(3) + "点" + (m.group(4) == null ? "" : m.group(4)) + "闹钟";
        Hit base = alarm(one);
        if (base == null) return null;
        try {
            base.args.put("days", days);
        } catch (Exception ignored) {
        }
        return new Hit("clock.alarm.set", base.args, "alarm.repeat");
    }

    /** 导航：取目的地（去掉句尾语气词）；目的地太短 / 是代词（这里、那儿）不接，交给后面的层（识屏指代等）。 */
    static Hit nav(String c) {
        Matcher m = NAV.matcher(c);
        if (!m.matches()) return null;
        String d = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        if (d == null) return null;
        d = d.replaceAll("(吧|了|呗|啊|呀|一下)+$", "").trim();
        if (d.length() < 2 || d.matches("(这里|那里|这儿|那儿|这个地方|那个地方|这个地址|那个地址|刚才那个地方|屏幕上的地址|刚复制的地址|复制的地址)")) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("address", d);
        } catch (Exception ignored) {
        }
        return new Hit(com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_NAV, o, "nav");
    }

    /** ★ 11-04 E：相对闹钟「N 分钟 / N 小时 / 半小时 / 一个半小时后叫醒我」。
     *  ★ 11-04 F（白盒审计）：整句以「叫我 / 喊我」结尾也是闹钟（用户定：xx 分钟后叫我一律走闹钟；后面带事「叫我吃饭」仍走计时器）；
     *  前缀「帮我定一个 40 分钟后的闹钟」吃掉「定一个」（「一个」后面必须是数字，免得把「一个半小时」吃坏）。 */
    static final Pattern ALARM_REL = Pattern.compile(
            "^(?:帮我|给我|请)?(?:设置|设定|设|定|订|来)?(?:一个(?=[0-9零两三四五六七八九十百])|个)?(.+?)(分钟|分|个小时|小时|个钟头|钟头)(?:以后|之后|后)(?:叫醒我|叫醒|叫我起床|喊我起床|喊醒我|把我叫醒|叫我|喊我|闹我|响铃|的闹钟)$");
    /** 测试注入的「现在」（毫秒）；0 = 真实时钟。 */
    static volatile long sNowForTest = 0L;

    /** 「现在」（测试可注入）。 */
    static long nowMs() {
        return sNowForTest > 0 ? sNowForTest : System.currentTimeMillis();
    }

    /**
     * ★ audit-J 4：相对时长 →分钟（闹钟「40分钟后叫我」与问答「40分钟后是几点」共用）。
     * num = 「40 / 两 / 一个半 / 半」，unit = 「分钟 / 分 / 个小时 / 小时 / 个钟头 / 钟头」；不合法或超出 1 分钟–24 小时 = -1。
     */
    static long relMinutes(String rawNum, String unit) {
        if (rawNum == null || unit == null) return -1;
        String num = rawNum.replace("个", "").trim();
        boolean hourUnit = unit.contains("时") || unit.contains("钟头");
        long minutes;
        if (num.equals("半")) {
            if (!hourUnit) return -1;
            minutes = 30;
        } else {
            boolean half = num.endsWith("半");
            String n0 = half ? num.substring(0, num.length() - 1) : num;
            if (!n0.matches("[0-9零一二两三四五六七八九十百]+")) return -1;
            Long v = NumberParser.parse(n0);
            if (v == null || v <= 0) return -1;
            minutes = hourUnit ? v * 60 + (half ? 30 : 0) : v;
            if (half && !hourUnit) return -1;
        }
        if (minutes < 1 || minutes > 24 * 60) return -1;
        return minutes;
    }

    static Hit alarmRel(String c) {
        Matcher m = ALARM_REL.matcher(c);
        if (!m.matches()) return null;
        long minutes = relMinutes(m.group(1), m.group(2));
        if (minutes < 0) return null;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(nowMs() + minutes * 60000L);
        JSONObject o = new JSONObject();
        try {
            o.put("hour", cal.get(java.util.Calendar.HOUR_OF_DAY));
            o.put("minute", cal.get(java.util.Calendar.MINUTE));
        } catch (Exception ignored) {
        }
        return new Hit("clock.alarm.set", o, "alarm.rel");
    }

    static Hit alarm(String c) {
        Matcher m = ALARM.matcher(c);
        if (!m.matches()) return null;
        String period = m.group(1);
        String hs = m.group(2);
        if (!hs.matches("[0-9]+") && !hs.matches("[零一二两三四五六七八九十]+")) return null;
        Long h = NumberParser.parse(hs);
        if (h == null || h < 0 || h > 24) return null;
        long hour = h;
        if (period != null) {
            if ((period.equals("下午") || period.equals("傍晚") || period.equals("晚上") || period.equals("夜里")) && hour < 12) hour += 12;
            else if (period.equals("中午") && hour < 6) hour += 12;
            else if (period.equals("凌晨") && hour == 12) hour = 0;
        }
        if (hour == 24) hour = 0;
        long minute = 0;
        String ms = m.group(3);
        if (ms != null) {
            if (ms.equals("半")) minute = 30;
            else if (ms.equals("一刻")) minute = 15;
            else if (ms.equals("三刻")) minute = 45;
            else {
                String d = ms.endsWith("分") ? ms.substring(0, ms.length() - 1) : ms;
                Long v = NumberParser.parse(d);
                if (v == null || v < 0 || v > 59) return null;
                minute = v;
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("hour", hour);
            o.put("minute", minute);
        } catch (Exception ignored) {
        }
        return new Hit("clock.alarm.set", o, "alarm");
    }

    private static JSONObject sargs(String k1, String v1, String k2, String v2) {
        JSONObject o = new JSONObject();
        try {
            o.put(k1, v1);
            if (k2 != null) o.put(k2, v2);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 方便调用方：直接给 Invocation（null = 未命中）。 */
    public static Invocation route(String utterance) {
        Hit h = match(utterance);
        return h == null ? null : h.toInvocation(utterance);
    }

    private static Hit toggle(String c) {
        for (String[] t : TOGGLES) {
            String obj = t[0];
            if (Pattern.matches(ON + obj + "|" + obj + TAIL_ON + "|把?" + obj + "打开", c)) {
                return new Hit(t[1], new JSONObject(), "toggle.on");
            }
            if (Pattern.matches(OFF + obj + "|" + obj + TAIL_OFF + "|把?" + obj + "关(?:了|掉|闭)", c)) {
                return new Hit(t[2], new JSONObject(), "toggle.off");
            }
        }
        return null;
    }

    /** 「最大 / 一半 / 50 / 百分之三十 / 十」→ 档位（max = 满档）；解析不了返回 null。 */
    /** 原句里有百分号（normalize 会把 % 当标点去掉）。 */
    static boolean pct(String utterance) {
        return utterance != null && (utterance.contains("%") || utterance.contains("％") || utterance.contains("百分之"));
    }

    static Integer level(String raw, int max, boolean percent) {
        if (raw == null) return null;
        String s = raw.replace("百分之", "").replace("%", "").replace("的", "").trim();
        if (s.equals("最大") || s.equals("最高") || s.equals("最响") || s.equals("最亮") || s.equals("满") || s.equals("满格")) return max;
        if (s.equals("最小") || s.equals("最低") || s.equals("最暗")) return max == 15 ? 1 : 1;
        if (s.equals("一半") || s.equals("中间") || s.equals("中等") || s.equals("适中")) return (max + 1) / 2;
        if (s.equals("零") || s.equals("0")) return 0;
        Long v = NumberParser.parse(s);
        if (v == null) return null;
        // 只接受纯数字（避免「音量调到跟刚才一样」被 NumberParser 逐字累加）
        if (!s.matches("[0-9零一二两三四五六七八九十百]+")) return null;
        long n = v.longValue();
        if (percent || n > max) {
            if (n > 100) return null;
            return (int) Math.round(n * max / 100.0);
        }
        return (int) n;
    }

    static Long timerSeconds(String c) {
        Matcher m = TIMER_REL.matcher(c);
        String num = null, unit = null;
        if (m.matches()) {
            num = m.group(1);
            unit = m.group(2);
        } else {
            m = TIMER_SET.matcher(c);
            if (m.matches()) {
                num = m.group(1) != null ? m.group(1) : m.group(3);
                unit = m.group(2) != null ? m.group(2) : m.group(4);
            }
        }
        if (num == null || unit == null) return null;
        num = num.replace("个", "").trim();
        long n;
        if (num.equals("半")) {
            n = -1;
        } else {
            if (!num.matches("[0-9零一二两三四五六七八九十百]+")) return null;
            Long v = NumberParser.parse(num);
            if (v == null || v <= 0) return null;
            n = v;
        }
        long sec;
        if (unit.startsWith("秒")) sec = n < 0 ? 30 : n;
        else if (unit.startsWith("分")) sec = n < 0 ? 30 : n * 60;
        else sec = n < 0 ? 1800 : n * 3600;
        if (sec <= 0 || sec > 86400) return null;
        return sec;
    }

    private static JSONObject args(String k, boolean v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject args(String k, int v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }
}
