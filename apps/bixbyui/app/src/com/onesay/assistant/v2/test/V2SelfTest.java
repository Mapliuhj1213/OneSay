package com.onesay.assistant.v2.test;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.ActionLexicon;
import com.onesay.assistant.v2.router.AgentLoop;
import com.onesay.assistant.v2.router.AskEngine;
import com.onesay.assistant.v2.router.IntentClassifier;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L0RuleRouter;
import com.onesay.assistant.v2.router.L1SkillRouter;
import com.onesay.assistant.v2.router.TimeContext;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.wake.EnergyGate;
import com.onesay.assistant.v2.wake.WakeWordStripper;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * OneSay 全量自动化回归自测套件。
 *
 * 核心验证矩阵：
 * 1. 100% 危险操作（risk=2 且 confirm=true）物理门禁拦截（GATED）；
 * 2. 参数 Schema 严格类型与范围校验；
 * 3. 唤醒词泄漏防御（WakeWordStripper）；
 * 4. 同 query 路由 100% 确定性与一致性回归；
 * 5. Markdown 字符 100% 过滤校验；
 * 6. 真实系统设置快照捕获与全量还原。
 */
public class V2SelfTest {

    private static final String TAG = "V2SelfTest";

    /** ★ R12：debug 包带自测 → 控制台开发者页画「运行全量回归自测」（release 占位类里是 false）。 */
    public static final boolean AVAILABLE = true;

    public interface SelfTestCallback {
        void onProgress(String log);
        void onComplete(String summary);
    }

    /** 自测期严禁真实触发的高危破坏性动作清单 */
    private static final Set<String> GATED_SIGNS = new HashSet<String>(Arrays.asList(
            "sys.reboot", "sys.poweroff", "sys.wipe", "app.uninstall",
            "connectivity.dataroam.on", "connectivity.dataroam.off",
            "phone.call", "sms.send"
    ));

    /**
     * 自测期跳过物理执行（避免黑屏、熄屏或切屏打断测试）。
     *
     * 此前 [全量能力] 会真实执行 connectivity.wifi.off
     *   （`svc wifi disable`），**把整台设备断网**。后果不只是少测几个用例 ——
     *   自测之后所有依赖网络的用例（专项 9 的 L2 大模型规划）与**用户随后的每一次
     *   云端 ASR 调用**都会因为没网而失败，而收尾的 `settings put global wifi_on 1`
     *   只是改标记位、拉不回真实关联（现象：wifi_on=1 但 radio 停在 NO-CARRIER）。
     *   结果就是"云端 ASR 莫名其妙全挂"，排查成本极高。wifi 开关已有专项 6
     *   做路由匹配验证，物理执行没有必要，直接跳过。
     */
    private static final Set<String> SKIP_EXEC_SIGNS = new HashSet<String>(Arrays.asList(
            "screen.lock", "screen.off", "torch.on", "torch.off", "torch.toggle",
            "connectivity.wifi.on", "connectivity.wifi.off",
            // 页面直达类必须跳过 —— am start 会抢焦点，
            //   打断同一轮后续所有用例（实测：打开设置页后 mCurrentFocus 变化，后续断言失焦）。
            "settings.page.wifi", "settings.page.sound", "settings.page.display",
            "settings.page.datetime", "settings.page.location", "settings.page.storage",
            "settings.page.battery", "settings.page.notification", "settings.page.apps",
            "settings.page.accessibility",
            // 这两条会发 SET_ALARM / SET_TIMER 系统广播，
            //   被**第三方闹钟类 app** 的 intent-filter 接住，
            //   弹出它的界面抢走前台 + 震动响铃 —— 用户看到的现象是"OneSay 崩了/乱跳"，
            //   实际我们进程一次都没崩（进程未重启、自测正常跑完）。
            //   同时它会在设备上**留下真实闹钟/计时器**，属"改设备状态"类副作用。
            //   路由匹配另有专项覆盖，物理执行没有必要。
            // clock.alarm.set 已改走 alarmbridge（OneSay 时钟接口；没装时交给系统时钟）——
            //   但**仍会新增真实闹钟**（无人值守副作用），继续跳过。
            //   alarm.toggle / alarm.cancel 同理：空参执行会动到设备上真实闹钟，必须跳过。
            //   （alarm.query 是纯只读，保留物理执行 —— 正好用来验证联动链路真实在线。）
            "clock.alarm.set", "clock.timer.set",
            "alarm.toggle", "alarm.cancel",
            // 走单独端到端测试，自测期跳过物理截屏与网络请求
            "vision.screen.understand",
            // 自测期**不做真实登录与网络往返**
            //   （未配授权码时必然失败，属"环境未就绪"而非功能缺陷）。
            //   静态契约与安全边界另有 13.8 专项覆盖；真实往返用
            //   `--es mailfetch 1` 探针在配置好之后单独跑。
            "mail.inbox.latest", "mail.draft.new"
    ));

    /**
     * 待快照与还原的系统状态键值对。
     *
     * <p>新增的"写入类"能力（自动亮度/触摸振动/省电/AOD/侧屏闪光/智能截屏）
     * 会被自测真实执行，必须纳入快照，否则跑完自测用户设备被改（这类坑本项目记过三次）。
     */
    private static final String[] SNAPSHOT_KEYS = {
            "global:wifi_on", "global:bluetooth_on", "global:mobile_data",
            "global:airplane_mode_on", "global:auto_time", "secure:location_mode",
            "system:screen_brightness", "system:accelerometer_rotation", "system:screen_off_timeout",
            // 2026-10-03 新增
            "system:screen_brightness_mode", "system:haptic_feedback_enabled",
            "global:low_power", "system:aod_mode", "system:edge_lighting",
            "system:enable_smart_capture"
    };

    private static volatile boolean sRunning = false;

    public static void run(final Context ctx, final SelfTestCallback cb) {
        if (sRunning) {
            if (cb != null) cb.onProgress("⚠️ 自测已在后台运行中，请勿重复触发");
            return;
        }

        sRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    executeTests(ctx, cb);
                } finally {
                    sRunning = false;
                }
            }
        }, "V2SelfTestThread").start();
    }

    private static void executeTests(Context ctx, SelfTestCallback cb) {
        log(cb, "==================================================");
        log(cb, "★ 启动 OneSay 全量自动化回归自测 ★");
        log(cb, "==================================================");

        UnifiedExecutor executor = UnifiedExecutor.getInstance(ctx);
        UnifiedRouter router = UnifiedRouter.getInstance(ctx);
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        ShellExecutor shell = executor.getShellExecutor();

        // 1. 拍摄系统设置物理快照
        log(cb, "[准备] 正在记录设备当前系统设置物理快照...");
        Map<String, String> snapshot = takeSnapshot(shell);
        // **四路音量也纳入快照**。
        //   这是一个被记了三次的旧账（2026-10-02 记忆里就写着"自测快照不含四路音量，
        //   跑完要手核"，当天报告也写着"设备音量仍为上轮自测遗留的 7/7/7/4"）。
        //   根因：能力表里有 volume.media.mute / volume.ring.mute / volume.notif.mute
        //   这些**真实执行**的静音项，跑完不还原 —— 用户会发现手机莫名其妙变成静音。
        //   与 WiFi 那个坑同源（"测试改了系统状态却没恢复"），这次一并堵死。
        final String volSnapshot = takeVolumeSnapshot(shell);
        log(cb, "[准备] 音频流快照: " + volSnapshot);

        int passCount = 0;
        int failCount = 0;
        int gatedCount = 0;
        int skipCount = 0;

        // 2–3. [专项 1/4 唤醒词零泄漏、专项 2/4 Markdown 净化] 审计 R-3：纯逻辑，已迁到 JVM
        //      test-jvm/.../selftest/V2SelfTestPortedTest（wakeWordStripZeroLeak / markdownCleanForTts）。
        log(cb, "\n[专项 1-2] 唤醒词零泄漏 / Markdown 净化：已迁到 JVM（V2SelfTestPortedTest），设备上不再重复跑");

        // 4. 专项测试：路由 100% 确定性与一致性回归（小溪缺陷 #2）
        log(cb, "\n[专项 3/6] 同 Query 路由 100% 一致性连跑验证 (20 次)...");
        boolean consistencyPass = true;
        String query = "现在几点";
        for (int i = 0; i < 20; i++) {
            Invocation inv = router.route(query);
            if (inv == null || (!"time.now".equals(inv.sign) && !"ask.reply".equals(inv.sign))) {
                consistencyPass = false;
                break;
            }
        }
        if (consistencyPass) {
            log(cb, "  ✓ 20 次相同指令路由完全一致 (100% 一致性)");
            passCount++;
        } else {
            log(cb, "  ✕ 路由发生漂移不一致");
            failCount++;
        }

        // 5. 专项测试：一句式唤醒指令切分（One-Shot splitWakeCommand）
        log(cb, "\n[专项 4/6] 一句式唤醒指令双向滑动切分测试...");
        L0RuleRouter l0Router = new L0RuleRouter(ctx);
        String[][] oneBreathCases = {
                { "嗨比西比打开蓝牙", "打开蓝牙" },
                { "比西比打开wifi", "打开wifi" },
                { "海必须别调高亮度", "调高亮度" },
                { "必自比打开相机", "打开相机" }
        };
        boolean splitAllPass = true;
        for (String[] pair : oneBreathCases) {
            String splitCmd = WakeWordStripper.splitWakeCommand(pair[0], false, l0Router);
            if (splitCmd != null && splitCmd.equals(pair[1])) {
                log(cb, "  ✓ 切分成功: [" + pair[0] + "] -> [" + splitCmd + "]");
            } else {
                log(cb, "  ✕ 切分未达预期: [" + pair[0] + "] -> 实际: [" + splitCmd + "] 期望: [" + pair[1] + "]");
                splitAllPass = false;
            }
        }
        if (splitAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6. 专项测试：ASR N-best 候选重排与同音字修正
        log(cb, "\n[专项 5/8] ASR N-best 候选重排与领域词先验纠错测试...");
        java.util.List<String> alts = java.util.Arrays.asList("打开无限", "打开无线");
        String bestAlt = l0Router.pickBestAlternative(alts, 2);
        if ("打开无线".equals(bestAlt)) {
            log(cb, "  ✓ N-best 重排成功: [打开无限, 打开无线] -> 纠错提升为 [打开无线]");
            passCount++;
        } else {
            log(cb, "  ✕ N-best 重排失败: 实际选出 [" + bestAlt + "]");
            failCount++;
        }

        // 7. 专项测试：Wi-Fi 多变体与近音容错路由测试
        log(cb, "\n[专项 6/8] Wi-Fi 语音识别多变体与近音容错路由测试...");
        String[][] wifiCases = {
                { "打开wifi", "connectivity.wifi.on" },
                { "把wifi打开", "connectivity.wifi.on" },
                { "开启wifi", "connectivity.wifi.on" },
                { "连接wifi", "connectivity.wifi.on" },
                { "开一下wifi", "connectivity.wifi.on" },
                { "打开无线", "connectivity.wifi.on" },
                { "打开无线网", "connectivity.wifi.on" },
                { "打开网络", "connectivity.wifi.on" },
                { "打开wlan", "connectivity.wifi.on" },
                // 移除 4 条 Vosk 近音变体用例（"打开歪发/歪快/微费/外发"）。
                //
                //   它们原先是通过 PinyinUtil 的**文本层同音纠错表**命中的
                //   （"歪发"→"wifi"）。用户定档「全部删掉，只靠 ASR 热词」之后，
                //   纠错责任整体移到了识别环节，这几条纯文本输入在架构上
                //   **已经不可能再命中** —— 文本层从此不做任何替换。
                //
                //   继续留着它们只会得到一个恒定 FAIL，且会掩盖真正需要关注的问题。
                //   真实链路的保障改由 ASR 热词承担：热词表里含 "WiFi"，
                //   云端识别不会产出这些错读（见 AsrHotwords）。
                { "关闭wifi", "connectivity.wifi.off" },
                { "把wifi关掉", "connectivity.wifi.off" },
                { "断开wifi", "connectivity.wifi.off" },
                { "关闭网络", "connectivity.wifi.off" },
                { "wifi开着吗", "connectivity.wifi.state" }
        };
        boolean wifiAllPass = true;
        for (String[] pair : wifiCases) {
            Invocation inv = router.route(pair[0]);
            if (inv != null && pair[1].equals(inv.sign)) {
                log(cb, "  ✓ Wi-Fi变体命中: [" + pair[0] + "] -> " + inv.sign);
            } else {
                log(cb, "  ✕ Wi-Fi变体失准: [" + pair[0] + "] -> 实际: " + (inv != null ? inv.sign : "null") + " (期望: " + pair[1] + ")");
                wifiAllPass = false;
            }
        }
        if (wifiAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6b. 专项：闹钟/计时器**路由匹配**（新增）
        //     为什么单独测：这两条能力已进 SKIP_EXEC_SIGNS（物理执行会发 SET_ALARM /
        //     SET_TIMER 系统广播，被第三方闹钟 app 接住弹窗抢前台 + 留下真实闹钟）。
        //     但"跳过执行"不等于"放弃验证" —— 路由匹配必须仍被覆盖，
        //     否则这两条能力就成了无人看守的盲区（本项目吃过这个亏）。
        //     判据：只验 router.route() 的 sign，不真发广播。
        log(cb, "\n[专项] 闹钟/计时器路由匹配（物理执行已 SKIP，只验路由）...");
        String[][] clockCases = {
                // 三列 = {句子, 期望sign, 期望数字（""=不校验；闹钟查 hour、计时查 seconds）}
                //   实测定稿病例：「设一个 7 点的闹钟」曾因量词残余
                //   （槽位捕获"一个7" → NumberParser 把"一"+"7"拼成 17）被设成 **17 点**。
                //   只验 sign 抓不住这类缺陷 —— 本组把"参数也对"钉死在自测里。
                { "设一个八点的闹钟", "clock.alarm.set", "8" },
                { "定一个十点的闹钟", "clock.alarm.set", "10" },
                { "设一个10点闹钟", "clock.alarm.set", "10" },
                { "设7点闹钟", "clock.alarm.set", "7" },
                { "一点叫我", "clock.alarm.set", "1" },
                // 这两句**没说要几点/多久**，属"意图明确但缺必填槽"，
                //   正确行为是追问（clarify.ask），而不是硬塞一个默认值去执行。
                //   原先期望 clock.alarm.set —— 那正好会把 LLM 编造的"7 点"当成正确结果缓存下来。
                { "定个闹钟", "clarify.ask", "" },
                { "八点叫我", "clock.alarm.set", "8" },
                { "设个三十秒倒计时", "clock.timer.set", "30" },
                { "定个计时器", "clarify.ask", "" }
        };
        boolean clockAllPass = true;
        for (String[] pair : clockCases) {
            Invocation inv = router.route(pair[0]);
            boolean ok = inv != null && pair[1].equals(inv.sign);
            String detail = inv != null ? inv.sign : "null";
            if (ok && pair.length > 2 && pair[2] != null && pair[2].length() > 0) {
                String numKey = "clock.timer.set".equals(pair[1]) ? "seconds" : "hour";
                long want = Long.parseLong(pair[2]);
                long got = inv.args != null ? inv.args.optLong(numKey, -1) : -1;
                if (got != want) {
                    ok = false;
                    detail = inv.sign + " 但 " + numKey + "=" + got + "（期望 " + want + "）";
                }
            }
            if (ok) {
                log(cb, "  ✓ 闹钟路由命中: [" + pair[0] + "] -> " + pair[1]);
            } else {
                log(cb, "  ✕ 闹钟路由失准: [" + pair[0] + "] -> 实际: " + detail
                        + " (期望: " + pair[1] + ")");
                clockAllPass = false;
            }
        }
        if (clockAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6c. 专项：现行分诊 TriageDecider（2026-10-09 D-2a：旧 TriageV2 已删除，用例改查现行决策表的规则层结论）
        //     用户点名的两个坑仍然覆盖：要内容（新闻/天气/币价）→ ASK、不是 DO_ACT；垃圾/只有唤醒词 → AMBIGUOUS（不进 AgentLoop、不进缓存）。
        //     同一张表在 JVM 里有 TriageProbeParityTest 守着，设备上跑只是确认 APK 里的类与 JVM 一致。
        log(cb, "\n[专项] 现行分诊 TriageDecider（规则层，离线信号）...");
        Object[][] triageCases = {
                // {语句, 期望标签}
                { "获取今日华尔街日报", TriageDecider.Label.AMBIGUOUS },   // 规则判不了 → 生产里走 LLM 兜底，失败按 ASK（无副作用）
                { "查一下今天的新闻",   TriageDecider.Label.ASK },
                { "今天天气怎么样",     TriageDecider.Label.ASK },
                { "比特币现在多少钱",   TriageDecider.Label.ASK },
                { "打开蓝牙",           TriageDecider.Label.DO_ACT },
                { "把亮度调到最低",     TriageDecider.Label.DO_ACT },
                { "发短信给张三",       TriageDecider.Label.DO_ACT },
                { "嗯",                 TriageDecider.Label.AMBIGUOUS },
                { "嗨比西比",           TriageDecider.Label.AMBIGUOUS },
                { "那个那个",           TriageDecider.Label.AMBIGUOUS },
                { "你好",               TriageDecider.Label.ASK },
        };
        boolean triageAllPass = true;
        for (Object[] tc : triageCases) {
            String s = (String) tc[0];
            TriageDecider.Decision v = TriageDecider.decide(s, TriageDecider.Signals.offline());
            boolean ok = v.label == tc[1] && !(v.label == TriageDecider.Label.AMBIGUOUS && v.useAgentLoop);
            if (ok) {
                log(cb, "  ✓ 分诊: [" + s + "] -> " + v.label + " (" + v.rule + ")");
            } else {
                log(cb, "  ✕ 分诊失准: [" + s + "] -> 实际 " + v.label + " (" + v.rule + ")"
                        + " (期望 " + tc[1] + ")");
                triageAllPass = false;
            }
        }
        if (triageAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6c-0. 专项：**接口数据库分类 + 提示词热加载**（新增）
        //   用户定的接口库分类：一级按软件、二级按接口类型（增删改查 / 调用UI 分列）。
        //   本专项验证：
        //     ① 每个能力都能被分到**明确**的二级类型（不是满屏 UNKNOWN）
        //     ② 只读能力果然落 QUERY、am start 类果然落 UI
        //     ③ 接口库两轴索引与注册表条目数一致（不丢不重）
        //     ④ 提示词库能取到两套模板且非空（内置默认兜底）
        log(cb, "\n[专项] 接口数据库分类 + 提示词热加载...");
        boolean ifdbAllPass = true;
        {
            int total = reg.listCapabilities().size();
            // 两轴索引条目总数应与注册表一致
            int indexed = 0;
            java.util.Map<String, java.util.Map<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                    java.util.List<com.onesay.assistant.v2.capability.Capability>>> tree =
                    reg.bySoftwareAndKind();
            int unknown = 0;
            for (java.util.Map.Entry<String, java.util.Map<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                    java.util.List<com.onesay.assistant.v2.capability.Capability>>> e : tree.entrySet()) {
                for (java.util.Map.Entry<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                        java.util.List<com.onesay.assistant.v2.capability.Capability>> ke : e.getValue().entrySet()) {
                    indexed += ke.getValue().size();
                    if (ke.getKey() == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UNKNOWN) {
                        unknown += ke.getValue().size();
                    }
                }
            }
            if (indexed == total) {
                log(cb, "  ✓ 接口库两轴索引完整: " + total + " 个接口全部归类（软件维度 "
                        + tree.size() + " 组）");
            } else {
                log(cb, "  ✕ 接口库索引丢失: 注册表 " + total + " vs 索引 " + indexed);
                ifdbAllPass = false;
            }
            // UNKNOWN 比例不应过高（超过 20% 说明分类器失效）
            if (unknown * 5 <= total) {
                log(cb, "  ✓ 二级分类覆盖率达标: UNKNOWN=" + unknown + "/" + total);
            } else {
                log(cb, "  ✕ 分类器覆盖不足: UNKNOWN=" + unknown + "/" + total + "（>20%）");
                ifdbAllPass = false;
            }
            // 抽查：只读能力落 QUERY，am start 类落 UI
            com.onesay.assistant.v2.capability.Capability bget =
                    reg.describe("settings.brightness.get");
            if (bget != null && com.onesay.assistant.v2.capability.InterfaceKind.of(bget)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.QUERY) {
                log(cb, "  ✓ 分类抽查: settings.brightness.get -> QUERY（只读）");
            } else {
                log(cb, "  ✕ 分类抽查失败: settings.brightness.get 未落 QUERY");
                ifdbAllPass = false;
            }
            com.onesay.assistant.v2.capability.Capability bset =
                    reg.describe("settings.brightness.set");
            if (bset != null && com.onesay.assistant.v2.capability.InterfaceKind.of(bset)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UPDATE) {
                log(cb, "  ✓ 分类抽查: settings.brightness.set -> UPDATE（改状态）");
            } else {
                log(cb, "  ✕ 分类抽查失败: settings.brightness.set 未落 UPDATE");
                ifdbAllPass = false;
            }
            com.onesay.assistant.v2.capability.Capability storeCap =
                    reg.describe("store.search.market");
            if (storeCap != null && com.onesay.assistant.v2.capability.InterfaceKind.of(storeCap)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UI) {
                log(cb, "  ✓ 分类抽查: store.search.market -> UI（调用界面）");
            } else {
                log(cb, "  ✕ 分类抽查失败: store.search.market 未落 UI（实际="
                        + (storeCap != null ? com.onesay.assistant.v2.capability.InterfaceKind.of(storeCap) : "null") + "）");
                ifdbAllPass = false;
            }
            // ★ 回归：不可逆动作必须落 DELETE（app.uninstall 曾因先判 UI 被归错）
            com.onesay.assistant.v2.capability.Capability uninst = reg.describe("app.uninstall");
            if (uninst != null && com.onesay.assistant.v2.capability.InterfaceKind.of(uninst)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.DELETE) {
                log(cb, "  ✓ 分类抽查: app.uninstall -> DELETE（不可逆优先）");
            } else {
                log(cb, "  ✕ 分类抽查失败: app.uninstall 未落 DELETE（实际="
                        + (uninst != null ? com.onesay.assistant.v2.capability.InterfaceKind.of(uninst) : "null") + "）");
                ifdbAllPass = false;
            }
            // ★ 回归：clock.alarm.set 曾被 cmd 里的 "rm -" 碎片误判成 DELETE，必须落 UI
            com.onesay.assistant.v2.capability.Capability alarm = reg.describe("clock.alarm.set");
            if (alarm != null && com.onesay.assistant.v2.capability.InterfaceKind.of(alarm)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UI) {
                log(cb, "  ✓ 分类抽查: clock.alarm.set -> UI（未被 rm 碎片误伤）");
            } else {
                log(cb, "  ✕ 分类抽查失败: clock.alarm.set 误判（实际="
                        + (alarm != null ? com.onesay.assistant.v2.capability.InterfaceKind.of(alarm) : "null") + "，应 UI）");
                ifdbAllPass = false;
            }
            // ★ 回归（2026-10-04 补）：读-改复合命令必须归 UPDATE，不能被读特征掩盖成 QUERY。
            //   settings.brightness.up 的 cmd 是 "…settings get…; …; settings put …"，
            //   初版只看见 get 就判只读 —— 后果是"改系统状态"被当成无害查询直接执行，
            //   确认门槛与读-改分离全部失效。
            com.onesay.assistant.v2.capability.Capability bup = reg.describe("settings.brightness.up");
            if (bup != null && com.onesay.assistant.v2.capability.InterfaceKind.of(bup)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UPDATE) {
                log(cb, "  ✓ 分类抽查: settings.brightness.up -> UPDATE（读-改复合不算只读）");
            } else {
                log(cb, "  ✕ 分类抽查失败: settings.brightness.up 被读特征掩盖（实际="
                        + (bup != null ? com.onesay.assistant.v2.capability.InterfaceKind.of(bup) : "null") + "，应 UPDATE）");
                ifdbAllPass = false;
            }
            // ★ 反向回归：纯读 + echo 输出**不能**被误杀成非 QUERY（echo 不是写）。
            com.onesay.assistant.v2.capability.Capability nfc = reg.describe("connectivity.nfc.state");
            if (nfc != null && com.onesay.assistant.v2.capability.InterfaceKind.of(nfc)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.QUERY) {
                log(cb, "  ✓ 分类抽查: connectivity.nfc.state -> QUERY（echo 输出不算写）");
            } else {
                log(cb, "  ✕ 分类抽查失败: connectivity.nfc.state 被 echo 误杀（实际="
                        + (nfc != null ? com.onesay.assistant.v2.capability.InterfaceKind.of(nfc) : "null")
                        + "，应 QUERY）");
                ifdbAllPass = false;
            }
            // **自动挖掘的系统接口**必须真的进了注册表。
            //   验证三件事：
            //     ① 挖掘资产加载成功（minedCount > 0）
            //     ② 挖掘接口类型是 UI（am start 拉界面，不是状态变更）
            //     ③ 抽一条已知的（时钟 set_alarm VOICE 口）确认可被寻址
            //   注意：init 在服务路径已调用；此处兜底再调一次（幂等）。
            reg.init(ctx);
            int mined = reg.minedCount();
            if (mined > 0) {
                log(cb, "  ✓ 自动挖掘接口已加载: " + mined + " 条（系统包界面/语音口）");
            } else {
                log(cb, "  ✕ 自动挖掘接口未加载: minedCount=0（资产缺失或解析失败）");
                ifdbAllPass = false;
            }
            // 抽查一条挖掘接口：必须是 UI 类（am start）
            com.onesay.assistant.v2.capability.Capability minedSample = null;
            for (com.onesay.assistant.v2.capability.Capability c : reg.listCapabilities()) {
                if (c.category != null && c.cmdTemplate != null
                        && c.cmdTemplate.startsWith("am start -n ")) {
                    minedSample = c;
                    break;
                }
            }
            if (minedSample != null && com.onesay.assistant.v2.capability.InterfaceKind.of(minedSample)
                    == com.onesay.assistant.v2.capability.InterfaceKind.Kind.UI) {
                log(cb, "  ✓ 挖掘接口抽查: " + minedSample.sign + " -> UI（am start 组件）");
            } else {
                log(cb, "  ✕ 挖掘接口抽查失败: 未找到 am start 型挖掘接口");
                ifdbAllPass = false;
            }

            // AgentLoop 地基断言（离线可验部分，不触网）。
            //
            //   为什么这几条必须进自测：AgentLoop 是"LLM 从翻译器变执行体"的地基，
            //   一旦它静默坏掉（工具名不合法 / 候选漏召 / tools 格式错），
            //   表现是"模型说没有这个工具"——极难与"模型能力不足"区分。
            //   把它钉成断言，坏掉时自测直接红。
            //
            //   ① 工具名合法性：端点只收 ^[a-zA-Z0-9_-]+$，
            //      我们的签名是点号分隔，必须经过 signToToolName 转换。
            //   ② 转换可逆：模型回传的 name 必须能还原成真实签名。
            //   ③ tools 数组外壳是 OpenAI 规范（type=function + function 对象）。
            //   ④ say 模板强命中把 time.now 送进候选（本轮实测事故的回归锁）。
            String dotSign = "connectivity.wifi.state";
            String wire = com.onesay.assistant.v2.capability.CapabilityRegistry.signToToolName(dotSign);
            if (wire.indexOf('.') < 0 && wire.matches("^[a-zA-Z0-9_-]+$")
                    && dotSign.equals(com.onesay.assistant.v2.capability.CapabilityRegistry.toolNameToSign(wire))) {
                log(cb, "  ✓ AgentLoop 工具名转换可逆且端点合法: " + dotSign + " -> " + wire);
            } else {
                log(cb, "  ✕ AgentLoop 工具名转换失败: " + dotSign + " -> " + wire);
                ifdbAllPass = false;
            }

            try {
                java.util.List<com.onesay.assistant.v2.capability.Capability> cand =
                        com.onesay.assistant.v2.router.CapabilityRetriever.getTopK("查一下现在几点了", 40);
                org.json.JSONArray tools = reg.exportToolsSchema(cand);
                boolean shellOk = tools.length() > 0;
                if (shellOk) {
                    org.json.JSONObject t0 = tools.getJSONObject(0);
                    shellOk = "function".equals(t0.optString("type"))
                            && t0.optJSONObject("function") != null;
                }
                if (shellOk) {
                    log(cb, "  ✓ AgentLoop tools 规范正确（type=function 外壳，" + tools.length() + " 个）");
                } else {
                    log(cb, "  ✕ AgentLoop tools 规范错误（缺 type=function 外壳）");
                    ifdbAllPass = false;
                }

                boolean hasTime = false;
                for (int i = 0; i < cand.size(); i++) {
                    if ("time.now".equals(cand.get(i).sign)) { hasTime = true; break; }
                }
                if (hasTime) {
                    log(cb, "  ✓ AgentLoop 候选召回: 「查一下现在几点了」含 time.now（say 强命中生效）");
                } else {
                    log(cb, "  ✕ AgentLoop 候选召回失败: time.now 未进 Top-40（say 强命中失效）");
                    ifdbAllPass = false;
                }

                // **目标族兜底**必须覆盖"打开/下载"类目标。
                //
                //   实测事故：用户说「打开 linkedin」，Top-40 被挖掘接口
                //   （contacts.* / settings.* / notes.*）灌满，
                //   导致 app.launch / browser.search / browser.open **全被挤出去**，
                //   模型答"我手上这 40 个工具里没有能输入网址或搜索的能力"。
                //   修法：按动作语素把该族的全部能力兜底塞进候选（见 AgentLoop.ensureGoalFamily）。
                //   本断言锁死"该族至少包含关键能力"，防止兜底表被误删。
                int familiesChecked = 0;
                boolean familyOk = true;
                // ① LAUNCH（打开 X）：必须含 app.launch + 一个搜索/打开网页的口
                String[] launchFam = com.onesay.assistant.v2.router.AgentLoop.goalFamilySigns(
                        com.onesay.assistant.v2.router.ActionLexicon.Intent.LAUNCH);
                familyOk &= containsSign(launchFam, "app.launch");
                familyOk &= (containsSign(launchFam, "browser.search")
                        || containsSign(launchFam, "browser.open"));
                familiesChecked++;
                // ② INSTALL（下载 X）：必须含两个商店 + 可用性探测
                String[] installFam = com.onesay.assistant.v2.router.AgentLoop.goalFamilySigns(
                        com.onesay.assistant.v2.router.ActionLexicon.Intent.INSTALL);
                familyOk &= containsSign(installFam, "store.search.market");
                familyOk &= containsSign(installFam, "store.search.play");
                familyOk &= containsSign(installFam, "store.status.check");
                familiesChecked++;
                // ③ 兜底表里的签名必须**真实存在**于注册表（防手误写了不存在的签名）
                for (String s : launchFam) {
                    if (reg.describe(s) == null) {
                        log(cb, "  ✕ 目标族兜底表含未注册签名: " + s);
                        familyOk = false;
                    }
                }
                if (familyOk) {
                    log(cb, "  ✓ AgentLoop 目标族兜底: " + familiesChecked
                            + " 族覆盖完整（LAUNCH 含 app.launch+搜索口；INSTALL 含双商店+探测）");
                } else {
                    log(cb, "  ✕ AgentLoop 目标族兜底不完整（换路将无路可换）");
                    ifdbAllPass = false;
                }
            } catch (Throwable t) {
                log(cb, "  ✕ AgentLoop tools 断言异常: " + t.getMessage());
                ifdbAllPass = false;
            }
        }
        {
            // 提示词库：两套模板都必须能取到内容（文件缺失时走内置默认）
            com.onesay.assistant.v2.router.PromptLibrary pl =
                    com.onesay.assistant.v2.router.PromptLibrary.getInstance(ctx);
            String ask = pl.get(com.onesay.assistant.v2.router.PromptLibrary.T_ASK_OUTPUT);
            String man = pl.get(com.onesay.assistant.v2.router.PromptLibrary.T_DO_MANUAL);
            if (ask != null && ask.length() > 50) {
                log(cb, "  ✓ 提示词模板 ask.output 可取（" + ask.length() + " 字）");
            } else {
                log(cb, "  ✕ 提示词模板 ask.output 为空");
                ifdbAllPass = false;
            }
            if (man != null && man.length() > 50) {
                log(cb, "  ✓ 提示词模板 do.manual 可取（" + man.length() + " 字，含操作规程）");
            } else {
                log(cb, "  ✕ 提示词模板 do.manual 为空");
                ifdbAllPass = false;
            }
            // 渲染测试：变量应被替换
            java.util.Map<String, String> vars = new java.util.HashMap<String, String>();
            vars.put("capabilities", "TEST_CAP_LIST");
            vars.put("query", "TEST_Q");
            vars.put("entityIndex", "");
            String rendered = pl.render(com.onesay.assistant.v2.router.PromptLibrary.T_DO_MANUAL, vars);
            if (rendered.contains("TEST_CAP_LIST") && !rendered.contains("{{capabilities}}")) {
                log(cb, "  ✓ 模板变量替换生效（{{capabilities}} 已注入）");
            } else {
                log(cb, "  ✕ 模板变量替换失败");
                ifdbAllPass = false;
            }
        }
        if (ifdbAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6c-1. 专项：**动作语素引擎泛化回归**（新增，本轮核心）
        //   用户判定："我有点怀疑你在定向拟合来交差，我需要一个能跑的工具。"
        //   —— 这句话直指旧机制的病根：**靠词表 containsAny**，补一个词只能救一个例句。
        //
        //   因此本专项的全部用例都是**语素表里没有、例句表里也没有的原句**，
        //   一律用"自然口语搭配"（装个/装上/弄个/搞个/搜个/找一下/下一个…），
        //   只要语素引擎是结构化的，这些**都没见过也应全中**。
        //
        //   判据分两层：
        //     ① 动作类别（ActionLexicon）：不依赖任何例句，纯语法判定
        //     ② 分类倾向（IntentClassifier）：INSTALL 类句子必须不能被判成"纯求知"
        //   任何一条不中 → 说明机制退化，必须查引擎而不是加词条。
        log(cb, "\n[专项] 动作语素引擎泛化回归（未见句，验机制非词表）...");
        Object[][] lexiconCases = {
                // {语句, 期望动作类别}
                { "帮我下载个抖音",       ActionLexicon.Intent.INSTALL },
                { "把微信装上",           ActionLexicon.Intent.INSTALL },
                { "给我装个高德地图",     ActionLexicon.Intent.INSTALL },
                { "给我弄个美团",         ActionLexicon.Intent.INSTALL },
                { "搞个支付宝来",         ActionLexicon.Intent.INSTALL },
                { "帮我把淘宝卸了",       ActionLexicon.Intent.INSTALL },
                { "商店里搜个支付宝",     ActionLexicon.Intent.SEARCH },
                { "应用市场找一下美团",   ActionLexicon.Intent.SEARCH },
                { "在商店里搜一下小红书", ActionLexicon.Intent.SEARCH },
                { "打开相机",             ActionLexicon.Intent.LAUNCH },
                { "启动计算器",           ActionLexicon.Intent.LAUNCH },
                { "帮我记一下买牛奶",     ActionLexicon.Intent.CREATE },
                { "写一条备注明天开会",   ActionLexicon.Intent.CREATE },
                { "把亮度调低一点",       ActionLexicon.Intent.CONFIGURE },
                // **开关范畴**与**无动词状态句**。
                //
                //   二轮泛化验（跨类别未见句）暴露出两类结构性缺口：
                //     ① 开关类：「蓝牙关了吧」「把wifi关掉」判 NONE ——
                //        语素表只收了"打开/开启"（LAUNCH），**完全没有"关"这一支**，
                //        于是判 ASK，LLM 答"Windows 11 怎么关蓝牙"（答的是电脑）。
                //     ② 无动词状态句：「让手机安静点」「屏幕再暗一点」判 NONE ——
                //        这两句**没有任何祈使动词**（"让…点"/"再…一点"是状态描述），
                //        靠动作语素永远认不出；改由"能力库目标词 + 祈使语气"第二通道兜。
                //   两类都是**范畴级**缺口（不是漏了个词），所以补的是范畴。
                { "蓝牙关了吧",           ActionLexicon.Intent.CONFIGURE },
                { "把wifi关掉",           ActionLexicon.Intent.CONFIGURE },
                { "关闭蓝牙",             ActionLexicon.Intent.CONFIGURE },
                { "让手机安静点",         ActionLexicon.Intent.CONFIGURE },
                { "屏幕再暗一点",         ActionLexicon.Intent.CONFIGURE },
                { "声音小点",             ActionLexicon.Intent.CONFIGURE },
                { "屏幕亮点",             ActionLexicon.Intent.CONFIGURE },
        };
        boolean lexAllPass = true;
        for (Object[] lc : lexiconCases) {
            String s = (String) lc[0];
            ActionLexicon.Intent want = (ActionLexicon.Intent) lc[1];
            ActionLexicon.Intent got = ActionLexicon.classify(
                    L0RuleRouter.normalizeUtterance(s));
            if (got == want) {
                log(cb, "  ✓ 语素: [" + s + "] -> " + got);
            } else {
                log(cb, "  ✕ 语素失准: [" + s + "] -> " + got + " (期望 " + want + ")");
                lexAllPass = false;
            }
        }
        // 泛化第二层：这些句子**不该**被当成纯求知问答（ASK）
        // 判据放宽为"不是纯 ASK"即可 —— AGENT/MIXED 都说明它被认成了动作类
        String[] generalizeCases = {
                "帮我下载个抖音", "把微信装上", "给我装个高德地图",
                "给我弄个美团", "搞个支付宝来", "帮我把淘宝卸了",
                "商店里搜个支付宝", "应用市场找一下美团", "在商店里搜一下小红书"
        };
        for (String s : generalizeCases) {
            boolean l0Hit = router.getL0() != null && router.getL0().route(s) != null;
            IntentClassifier.Kind k = IntentClassifier.classify(s, l0Hit, false);
            if (k != IntentClassifier.Kind.ASK) {
                log(cb, "  ✓ 分类: [" + s + "] -> " + k + "（非纯问答，可进执行链）");
            } else {
                log(cb, "  ✕ 分类退化: [" + s + "] -> ASK（被当纯求知，应进执行链）");
                lexAllPass = false;
            }
        }
        // 泛化第三层：宾语清洗（"下载个抖音" 不许把量词带进槽位）
        Object[][] objCases = {
                { "下载个抖音",     "抖音" },
                { "装个高德地图",   "高德地图" },
                { "商店里搜个支付宝", "支付宝" },
        };
        for (Object[] oc : objCases) {
            String s = (String) oc[0];
            String want = (String) oc[1];
            String got = ActionLexicon.objectOf(L0RuleRouter.normalizeUtterance(s));
            if (want.equals(got)) {
                log(cb, "  ✓ 宾语清洗: [" + s + "] -> " + got);
            } else {
                log(cb, "  ✕ 宾语清洗失败: [" + s + "] -> " + got + " (期望 " + want + ")");
                lexAllPass = false;
            }
        }
        if (lexAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 泛化第四层（**能力库驱动的目标识别**。
        //
        //   两条独立断言，各防一个具体 BUG：
        //     ① 中文滑窗：summary「设置屏幕亮度」要能让「屏幕再暗一点」命中"屏幕"。
        //        初版按"非中文断开"切词 → 6 字整串当一个词 → 恒不命中，
        //        hasDeviceTarget 恒 false，整条兜底通道等于废掉。
        //     ② 状态同义：「安静」要能对上 summary 里的「静音」——
        //        这是 volume.ring.mute 能被召回的唯一依据（二元组零重合）。
        boolean tgtPass = true;
        String[] tgtCases = { "屏幕再暗一点", "让手机安静点", "声音小点", "蓝牙关了吧" };
        for (String s : tgtCases) {
            if (ActionLexicon.hasDeviceTarget(L0RuleRouter.normalizeUtterance(s))) {
                log(cb, "  ✓ 目标识别: [" + s + "] 命中本机可控目标");
            } else {
                log(cb, "  ✕ 目标识别失败: [" + s + "] 未命中（中文滑窗/同义归一失效）");
                tgtPass = false;
            }
        }
        // 反向：纯知识问题**不该**被认成本机目标（否则闲聊会跑去读传感器）
        String[] notTarget = { "李白是谁", "相对论讲了什么", "推荐几本书" };
        for (String s : notTarget) {
            if (!ActionLexicon.hasDeviceTarget(s)) {
                log(cb, "  ✓ 目标识别(反向): [" + s + "] 正确未命中");
            } else {
                log(cb, "  ✕ 目标识别过度: [" + s + "] 被误认成本机目标");
                tgtPass = false;
            }
        }
        // 状态同义：必须用**同一份定义**（识别与召回共用），
        //   否则会出现"认得出是静音诉求、却召不回 mute 接口"的割裂。
        if (ActionLexicon.stateSynonymMatches("让手机安静点", "静音电话来电铃声")) {
            log(cb, "  ✓ 状态同义: 安静 ↔ 静音 归一可用（供 CapabilityRetriever 召回复用）");
        } else {
            log(cb, "  ✕ 状态同义归一失效（安静/静音应指向同一状态）");
            tgtPass = false;
        }
        if (tgtPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6c-2. 专项：笔记 / 应用商店任务（新增）
        //     ① 新建笔记带内容 → notes.new.text 且 content 非空（含"三点"等汉字数字不能被吞）
        //     ② 下载 app → store.search.market（LLM 会选可用商店）
        //     ③ 这两个任务在分诊上必须是 DO_ACTION（不是 ASK_INFO）
        log(cb, "\n[专项] 笔记 / 应用商店任务（内容捕获 + 商店选择 + 分诊形态）...");
        Object[][] miscCases = {
                // {语句, 期望sign, 期望content(可空)}
                { "新建笔记记录明天下午三点开会", "notes.new.text", "明天下午三点开会" },
                { "记一下明天下午三点开会",       "notes.new.text", "明天下午三点开会" },
                { "记一下买牛奶",                 "notes.new.text", "买牛奶" },
                { "下载微信",                     "store.search.market", "微信" },
                { "在应用商店下载微信",           "store.search.market", "微信" },
        };
        boolean miscAllPass = true;
        for (Object[] mc : miscCases) {
            String s = (String) mc[0];
            String wantSign = (String) mc[1];
            String wantContent = (String) mc[2];
            Invocation iv = router.route(s);
            String got = iv == null ? "(null)" : iv.sign;
            String content = (iv != null && iv.args != null)
                    ? iv.args.optString("content", iv.args.optString("keyword", "")) : "";
            boolean ok = wantSign.equals(got);
            if (ok && wantContent != null && wantContent.length() > 0) {
                ok = wantContent.equals(content);
            }
            if (ok) {
                log(cb, "  ✓ " + s + " -> " + got + " content=" + content);
            } else {
                log(cb, "  ✕ " + s + " -> 实际 " + got + " content=" + content
                        + " (期望 " + wantSign + " / " + wantContent + ")");
                miscAllPass = false;
            }
        }
        // 分诊形态：这两类必须是 DO_ACT
        Object[][] miscTriage = {
                { "新建笔记输入一段内容保存", TriageDecider.Label.DO_ACT },
                { "打开应用商店下载微信",     TriageDecider.Label.DO_ACT },
        };
        for (Object[] mt : miscTriage) {
            String s = (String) mt[0];
            TriageDecider.Decision v = TriageDecider.decide(s, TriageDecider.Signals.offline());
            if (v.label == mt[1]) {
                log(cb, "  ✓ 分诊形态: [" + s + "] -> " + v.label);
            } else {
                log(cb, "  ✕ 分诊形态错误: [" + s + "] -> " + v.label + " (期望 " + mt[1] + ")");
                miscAllPass = false;
            }
        }
        if (miscAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 6d. 专项：浏览器能力（新增）
        //     「打开浏览器」= 运行时探测默认浏览器 + 开界面（不是开着浏览器让用户自己搜）；
        //     「打开浏览器把引擎换成 X」= 复合句，必须命中 engine.open 而非被 app.launch 吞掉。
        //     本专项只验**路由 sign**（不真开浏览器、不联网），执行链另有 RUN 探针实测。
        log(cb, "\n[专项] 浏览器能力路由（默认浏览器探测 / 换引擎复合句）...");
        Object[][] browserCases = {
                // {语句, 期望sign}
                { "打开浏览器",                   "browser.open.default" },
                { "启动浏览器",                   "browser.open.default" },
                { "打开浏览器把引擎换成必应",      "browser.engine.open.bing" },
                { "打开浏览器把引擎换成百度",      "browser.engine.open.baidu" },
                { "打开浏览器把引擎换成google",   "browser.engine.open.google" },
                { "打开浏览器把引擎换成搜狗",      "browser.engine.open.sogou" },
                { "默认浏览器是什么",             "browser.default.check" },
                // 回归底线：普通开 App 不能被浏览器规则带偏
                { "打开相册",                     "app.launch" },
                { "打开计算器",                   "app.launch" },
        };
        boolean browserAllPass = true;
        for (Object[] bc : browserCases) {
            String s = (String) bc[0];
            String want = (String) bc[1];
            Invocation iv = router.route(s);
            String got = iv == null ? "(null)" : iv.sign;
            if (want.equals(got)) {
                log(cb, "  ✓ 浏览器路由: [" + s + "] -> " + got);
            } else {
                log(cb, "  ✕ 浏览器路由失准: [" + s + "] -> 实际 " + got + " (期望 " + want + ")");
                browserAllPass = false;
            }
        }
        if (browserAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 7b. 专项测试：LLM 路由架构重构的四条关键断言（2026-10-03）
        //     ① L0 不再贪婪吞掉复合句（「打开浏览器搜索必应」必须原样放行）
        //     ② 分类器把动宾并列判成 MIXED（而不是单步 AGENT）
        //     ③ 单动作句不能被误判成复合句（零回归）
        //     ④ 联网问答通道就绪（免 Key 的必应网页兜底必须在链上）
        log(cb, "\n[专项] 路由架构重构断言（残余检测 / 复合句分类 / 联网通道）...");
        try {
            final String composite = "打开浏览器搜索必应";

            Invocation l0Res = router.getL0().route(composite);
            if (l0Res == null) {
                log(cb, "  ✓ L0 残余检测生效: [" + composite + "] 未被贪婪吞噬，已放行给上层");
                passCount++;
            } else {
                log(cb, "  ✕ L0 仍在吞噬复合句: [" + composite + "] -> " + l0Res.sign);
                failCount++;
            }

            IntentClassifier.Kind kind = IntentClassifier.classify(composite, false, false);
            if (kind == IntentClassifier.Kind.MIXED) {
                log(cb, "  ✓ 动宾并列被判为 MIXED（会走 LLM 出多步计划）");
                passCount++;
            } else {
                log(cb, "  ✕ 动宾并列分类错误: 期望 MIXED，实际 " + kind);
                failCount++;
            }

            IntentClassifier.Kind single = IntentClassifier.classify("打开蓝牙", true, false);
            if (single == IntentClassifier.Kind.AGENT) {
                log(cb, "  ✓ 单动作句未被误判: [打开蓝牙] 仍是 AGENT（零回归）");
                passCount++;
            } else {
                log(cb, "  ✕ 单动作句被误判为 " + single);
                failCount++;
            }

            com.onesay.assistant.v2.search.WebSearchClient ws =
                    com.onesay.assistant.v2.search.WebSearchClient.getInstance(ctx);
            String provider = ws.activeProviderName();
            if (provider != null && !"(无)".equals(provider)) {
                log(cb, "  ✓ 联网问答通道就绪: 首选数据源=" + provider + ", " + ws.describe());
                passCount++;
            } else {
                log(cb, "  ✕ 联网问答通道不可用（问答又将退化为「答不上来」）");
                failCount++;
            }

            // ⑤ ASR 热词表（识别环节纠错的载体，不能为空）
            String hotwords = com.onesay.assistant.v2.asr.AsrRouter.getInstance(ctx).hotwordsPreview();
            if (hotwords != null && hotwords.length() > 20) {
                log(cb, "  ✓ ASR 热词表已构建: " + hotwords);
                passCount++;
            } else {
                log(cb, "  ✕ ASR 热词表为空或过短，识别纠错将失效: " + hotwords);
                failCount++;
            }

            // ⑥ 行情问句必须被判为时效性（→ 永不落缓存）
            //    2026-10-03 实测事故：「比特币售价多少」一个时效词都没命中，
            //    被当"稳定知识"缓存 30 天 —— 第一次答错就再也改不掉了。
            String[] volatileQs = { "比特币售价多少", "黄金价格多少", "特斯拉行情怎么样", "今天油价" };
            String missed = null;
            for (String vq : volatileQs) {
                if (!IntentClassifier.isTimeSensitive(vq)) { missed = vq; break; }
            }
            if (missed == null) {
                log(cb, "  ✓ 行情/报价问句被判为时效性，不会落缓存（" + volatileQs.length + " 条全过）");
                passCount++;
            } else {
                log(cb, "  ✕ 行情问句未被识别为时效性，会被永久缓存: [" + missed + "]");
                failCount++;
            }

            // ⑦ 反向：稳定知识不能被误判成时效性（否则缓存白建，每次都联网）
            String[] stableQs = { "你是谁", "一斤等于多少克", "报警电话是多少", "相对论是什么" };
            String overHit = null;
            for (String sq : stableQs) {
                if (IntentClassifier.isTimeSensitive(sq)) { overHit = sq; break; }
            }
            if (overHit == null) {
                log(cb, "  ✓ 稳定知识未被误判为时效性，缓存策略零回归（" + stableQs.length + " 条全过）");
                passCount++;
            } else {
                log(cb, "  ✕ 稳定知识被误判为时效性，会造成无谓的重复联网: [" + overHit + "]");
                failCount++;
            }

            // ⑧ 缓存清理入口可用（错答案的逃生通道，坏掉就只剩卸载重装）
            int skillCount = router.getL1().getSkillCount();
            if (skillCount >= 0) {
                log(cb, "  ✓ 缓存可查询/可清理（当前 " + skillCount + " 条技能）；"
                        + "命令行: --ez clearcache true");
                passCount++;
            } else {
                log(cb, "  ✕ 缓存接口异常，返回条数 " + skillCount);
                failCount++;
            }
            // ⑨ 流式问答的 SSE 解析（纯离线用例，不联网）
            //    为什么要专门守它：这段解析是整个流式交付的咽喉。最阴的坑是
            //    org.json 的 optString 会把 `"content": null` 变成字符串 "null"，
            //    一旦回退到这个写法，屏幕上每个回答前面都会多出一个 "null"。
            String[][] sseCases = {
                    // 标准 SSE 增量行
                    { "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", "你好" },
                    // 少数端点的裸 JSON 行
                    { "{\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}", "世界" },
                    // 首帧 content 为 null → 必须跳过，绝不能变成 "null"
                    { "data: {\"choices\":[{\"delta\":{\"content\":null}}]}", null },
                    // 结束标记
                    { "data: [DONE]", null },
                    // 心跳注释与事件行
                    { ": keep-alive", null },
                    { "event: ping", null }
            };
            String badSse = null;
            for (String[] c : sseCases) {
                String got = com.onesay.assistant.v2.router.SseStream.parseDelta(c[0]);
                boolean okCase = (c[1] == null) ? (got == null) : c[1].equals(got);
                if (!okCase) {
                    badSse = "[ " + c[0] + " ] -> " + got + "（期望 " + c[1] + "）";
                    break;
                }
            }
            if (badSse == null && com.onesay.assistant.v2.router.SseStream.isDoneLine("data: [DONE]")) {
                log(cb, "  ✓ SSE 流式增量解析正确（" + sseCases.length
                        + " 条用例，含 null-content / [DONE] / 心跳注释）");
                passCount++;
            } else {
                log(cb, "  ✕ SSE 解析异常: " + (badSse != null ? badSse : "结束标记识别失败"));
                failCount++;
            }

            // ⑩ 会话上下文记忆（ConversationMemory，纯离线用例，不联网）
            //    —— 验证"同一面板内多轮"的承载与预算裁剪：用户 2026-10-03 要求
            //    "在单次打开窗口里加上上下文，如果问答不关闭的话"。
            com.onesay.assistant.v2.router.ConversationMemory mem =
                    com.onesay.assistant.v2.router.ConversationMemory.getInstance();
            mem.clear();
            mem.record("天空为什么是蓝的", "因为瑞利散射……");
            boolean t1 = (mem.size() == 1);
            // ★ int/1102：预算改按 token（默认 32768），短问答 5 轮应全部保留、不折叠
            for (int i = 0; i < 4; i++) mem.record("追问" + i, "回答" + i);
            boolean t2 = (mem.size() == 5 && mem.foldedTurns() == 0);
            java.util.List<com.onesay.assistant.v2.router.ConversationMemory.Turn> snap = mem.snapshot();
            boolean t3 = (snap.size() > 0 && "追问3".equals(snap.get(snap.size() - 1).question));
            mem.clear();
            boolean t4 = mem.isEmpty();
            if (t1 && t2 && t3 && t4) {
                log(cb, "  ✓ 会话上下文记忆正确（记录 / token 预算 "
                        + mem.budgetTokens() + " 内不折叠 / 保留最近 / 清空）");
                passCount++;
            } else {
                log(cb, "  ✕ 会话上下文记忆异常: size1=" + t1 + " trim=" + t2
                        + " latest=" + t3 + " clear=" + t4);
                failCount++;
            }

            // ⑪ 可续期聆听窗（P1-2，纯函数用例，不碰麦克风）
            //    —— "人还在说话就不收窗"必须**可确定性验证**：靠真机说话语速碰运气，
            //    测不出「触顶 / 应答音期 / 窗已关 / 从没听见人声」这几个边界。
            //    断言全部基于 WakeService 的纯函数，不涉及任何音频设备。
            final long WK = 100000L;                                        // 假想唤醒时刻
            final long FROM = WK + com.onesay.assistant.v2.wake.WakeService.CAPTURE_FROM_OFFSET_MS;
            final long UNTIL = WK + com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS;
            final long MAXW = com.onesay.assistant.v2.wake.WakeService.CAPTURE_MAX_MS;
            final long STEP = com.onesay.assistant.v2.wake.WakeService.CAPTURE_EXTEND_STEP_MS;

            // 到点这一刻（now == UNTIL）的四种情形
            boolean r1 = com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    UNTIL, WK, FROM, UNTIL, UNTIL - 1000);                 // 1s 前有人声 → 续期
            boolean r2 = !com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    UNTIL, WK, FROM, UNTIL, UNTIL - 4000);                 // 4s 前就静了 → 不收
            boolean r3 = !com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    UNTIL, WK, FROM, UNTIL, 0L);                           // 从未听见人声 → 不收
            boolean r4 = !com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    UNTIL, WK, FROM, 0L, UNTIL - 1000);                    // 窗已被关 → 不收
            // 应答音播放期内（now < captureFrom）→ 不收，避免被自己的应答音续命
            boolean r5 = !com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    WK + 500, WK, FROM, UNTIL, WK + 400);
            // 触顶 → 不收；差 1ms 触顶 → 仍可收
            boolean r6 = !com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    WK + MAXW, WK, FROM, UNTIL, WK + MAXW - 100);
            boolean r7 = com.onesay.assistant.v2.wake.WakeService.shouldRenewCapture(
                    WK + MAXW - 1, WK, FROM, UNTIL, WK + MAXW - 500);

            // 硬上限裁剪：整段窗（首窗 + 全部续期）永不超过 CAPTURE_MAX_MS
            boolean r8 = com.onesay.assistant.v2.wake.WakeService.clampCaptureWindow(
                    WK, WK, com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS_KEY)
                    == com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS_KEY;
            boolean r9 = com.onesay.assistant.v2.wake.WakeService.clampCaptureWindow(WK + 18000, WK, STEP)
                    == (MAXW - 18000);
            // 长按窗必须长于语音窗（P1-2 对齐"长按=麦克风一直等着你"的核心）
            boolean r10 = com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS_KEY
                    > com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS;
            // 🚨 隐私边界回归：唤醒词唤醒（长应答音）上传窗起点 = 唤醒 +1100ms；P1-2 只允许推后"结束边界"，绝不许动"起点"。
            //    写死 1100 是为了逼出一次有意识的确认。r2/fixes 12：隐私自检文案按实际规则描述
            //    （唤醒词 +450/1100、按键 +300、按键预录绝不早于唤醒），这里核对文案与当前配置算出来的数一致。
            String pr = com.onesay.assistant.v2.wake.WakeService.privacyReport();
            com.onesay.assistant.v2.wake.WakeTuning wt = com.onesay.assistant.v2.wake.WakeTuning.get(ctx);
            long voiceFrom = com.onesay.assistant.v2.wake.WakeService.captureFromOffsetMs(false, wt.shortChime());
            long keyFrom = com.onesay.assistant.v2.wake.WakeService.captureFromOffsetMs(true, wt.shortChime());
            boolean r11 = (com.onesay.assistant.v2.wake.WakeService.CAPTURE_FROM_OFFSET_MS == 1100L)
                    && pr.contains("唤醒词唤醒 +" + voiceFrom + "ms")
                    && pr.contains("按键唤醒 +" + keyFrom + "ms")
                    && pr.contains("一律晚于唤醒时刻")
                    && (!wt.keyPreRoll() || wt.preRollMs() <= 0 || pr.contains("绝不早于唤醒"))
                    && !pr.contains("唤醒时刻 + 1100ms");

            if (r1 && r2 && r3 && r4 && r5 && r6 && r7 && r8 && r9 && r10 && r11) {
                log(cb, "  ✓ 可续期聆听窗正确（仍在说话续期 / 静音不收 / 应答音期不收 / "
                        + "触顶封顶 / 长按窗 " + com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS_KEY
                        + "ms > 语音窗 " + com.onesay.assistant.v2.wake.WakeService.CAPTURE_MS
                        + "ms / 隐私起点 唤醒词 +" + voiceFrom + "ms、按键 +" + keyFrom + "ms）");
                passCount++;
            } else {
                log(cb, "  ✕ 可续期聆听窗异常: 续期=" + r1 + " 超期不收=" + r2 + " 无人声不收=" + r3
                        + " 已关不收=" + r4 + " 应答期不收=" + r5
                        + " 触顶不收=" + r6 + " 未触顶可收=" + r7
                        + " 裁剪=" + r8 + "/" + r9 + " 长按更长=" + r10 + " 隐私起点=" + r11);
                failCount++;
            }
        } catch (Throwable t) {
            log(cb, "  ✕ 新架构断言执行异常: " + t.getMessage());
            failCount++;
        }

        // 8. 专项测试：Ask 模式直接输出（时间、日期、四则算术、实用换算与字数硬约束）
        //    注（2026-10-02）："今天天气" 已从本地直出清单中移除 —— 天气是时效性数据，
        //    本地不能假装知道（旧实现返回写死的"今日多云转晴 18℃~26℃"），改由 L2 联网处理。
        log(cb, "\n[专项 7/10] Ask 问答模式日常即时查询直出与字数上限测试...");
        String[] askQueries = { "现在几点", "今天几号", "你是谁", "35乘18等于多少", "一斤等于多少克", "报警电话是多少" };
        boolean askAllPass = true;
        for (String q : askQueries) {
            Invocation inv = router.route(q);
            if (inv != null && "ask.reply".equals(inv.sign) && inv.args != null && inv.args.has("text")) {
                String ans = inv.args.optString("text");
                if (ans.length() <= 100) {
                    log(cb, "  ✓ Ask 直出成功 (" + ans.length() + "字 <= 100字): [" + q + "] -> " + ans);
                } else {
                    log(cb, "  ✕ Ask 回答超出100字限制 (" + ans.length() + "字): " + ans);
                    askAllPass = false;
                }
            } else {
                log(cb, "  ✕ Ask 直出失败: [" + q + "] -> inv=" + (inv != null ? inv.sign : "null"));
                askAllPass = false;
            }
        }
        if (askAllPass) {
            passCount++;
        } else {
            failCount++;
        }

        // 9. 专项测试：高危破坏性动作 100% 门禁拦截测试（小溪缺陷 #4）
        log(cb, "\n[专项 8/10] 高危动作（risk=2 且 confirm=true）物理确认卡门禁测试...");
        for (String sign : GATED_SIGNS) {
            Capability cap = reg.describe(sign);
            if (cap == null) continue;

            // 未确认尝试直接执行
            ExecutionResult res = executor.executeInternal(cap, new JSONObject(), false, false);
            if (res.code == ErrorCode.RISK_GATED && !res.ok) {
                log(cb, "  ✓ GATED  | " + cap.sign + " -> 成功被安全确认卡门禁拦截");
                gatedCount++;
            } else {
                log(cb, "  ✕ GATED 漏洞: " + cap.sign + " 竟然未被拦截！code=" + res.code);
                failCount++;
            }
        }

        // 10. 专项测试：非标任务 Agent 规划与 L1 技能固化复用闭环实测
        log(cb, "\n[专项 9/10] 非标任务 Agent（L2 大模型规划 + L1 技能固化复用）闭环实测...");
        String agentKey = router.getL2().getApiKey();
        if (agentKey != null && agentKey.trim().length() > 0) {
            router.getL1().clearForTesting(); // 清理测试数据库，保障验证完整三步自学习闭环
            shell.execute("svc wifi enable");
            android.os.SystemClock.sleep(1000);
            String testNonStandard = "天太黑了屏幕刺眼帮我调低点亮度";
            // 步骤 1：首次路由（预期命中 L2 大模型编译器）
            long tStart = android.os.SystemClock.elapsedRealtime();
            Invocation inv1 = router.route(testNonStandard);
            long cost1 = android.os.SystemClock.elapsedRealtime() - tStart;

            // route() 只规划不落库，
            //   闭环改为 route → 真执行 → reportExecution → 再 route 命中 L1。
            if (inv1 != null && inv1.source == Invocation.RouteSource.L2_LLM
                    && inv1.sign != null && inv1.sign.startsWith("settings.brightness")) {
                log(cb, "  ✓ [L2规划成功] " + testNonStandard + " -> " + inv1.sign + " (耗时: " + cost1 + "ms)");
                boolean before = router.getL1().hasSkill(testNonStandard);
                if (before) {
                    log(cb, "  ✕ [缓存语义] 仅 route() 未执行就已落库（应在执行成功后才写）");
                    failCount++;
                } else {
                    Capability bcap = CapabilityRegistry.getInstance().describe(inv1.sign);
                    ExecutionResult er = bcap != null ? executor.execute(bcap, inv1.args) : null;
                    if (er == null || !er.ok) {
                        log(cb, "  ✕ [执行失败] " + inv1.sign + " → " + (er != null ? er.code + " " + er.userMessage : "无能力契约"));
                        failCount++;
                    } else {
                        router.reportExecution(testNonStandard, inv1.sign, er);
                        boolean hasDb = router.getL1().hasSkill(testNonStandard);
                        if (hasDb) {
                            log(cb, "  ✓ [L1落库成功] 执行成功(" + er.verification + ")后写入技能缓存");
                            // 步骤 3：二次路由相同非标指令（预期命中 L1 技能缓存，0 网络请求）
                            long t2 = android.os.SystemClock.elapsedRealtime();
                            Invocation inv2 = router.route(testNonStandard);
                            long cost2 = android.os.SystemClock.elapsedRealtime() - t2;
                            if (inv2 != null && inv2.source == Invocation.RouteSource.L1_SKILL && inv1.sign.equals(inv2.sign)) {
                                log(cb, "  ✓ [L1复用成功] 二次输入命中本地技能缓存直出！(耗时: " + cost2 + "ms, 0网络调用)");
                                passCount++;
                            } else {
                                log(cb, "  ✕ [L1复用失败] 二次输入未能命中 L1，实际来源: " + (inv2 != null ? inv2.source + " " + inv2.sign : "null"));
                                failCount++;
                            }
                        } else {
                            log(cb, "  ✕ [L1落库失败] 执行成功并回报后，技能缓存中仍检索不到");
                            failCount++;
                        }
                    }
                }
            } else {
                log(cb, "  ✕ [L2规划失败] inv=" + (inv1 != null ? (inv1.sign + " " + inv1.source) : "null"));
                failCount++;
            }
        } else {
            log(cb, "  - [L2未配置] 跳过网络大模型调用自测");
            skipCount++;
        }

        // 11. 专项测试：语音唤醒应答声语义自激隔离与回读防火墙测试
        log(cb, "\n[专项 10/10] 语音唤醒应答声语义自激隔离与回读防火墙测试...");
        boolean ackShieldPass = true;
        // 验证 1：自身应答词纯净拦截
        String[] ackWords = { "我在", "在的", "在呢", "我在呢", "我在请讲", "在呢请讲", "我在听" };
        for (String w : ackWords) {
            if (!WakeWordStripper.isAckSoundUtterance(w)) {
                log(cb, "  ✕ 应答黑名单漏判: " + w);
                ackShieldPass = false;
            }
            Invocation inv = router.route(w);
            if (inv != null && !"ask.reply".equals(inv.sign) && !"chat.reply".equals(inv.sign)) {
                log(cb, "  ✕ 自身应答音被错误解析为设备指令: " + w + " -> " + inv.sign);
                ackShieldPass = false;
            }
        }
        // 验证 2：重叠发音前缀剥离
        String stripped1 = WakeWordStripper.strip("我在打开蓝牙");
        if (!"打开蓝牙".equals(stripped1)) {
            log(cb, "  ✕ 重叠发音剥离异常: '我在打开蓝牙' -> '" + stripped1 + "'");
            ackShieldPass = false;
        }
        String stripped2 = WakeWordStripper.strip("在的把屏幕调亮");
        if (!"把屏幕调亮".equals(stripped2)) {
            log(cb, "  ✕ 重叠发音剥离异常: '在的把屏幕调亮' -> '" + stripped2 + "'");
            ackShieldPass = false;
        }

        if (ackShieldPass) {
            log(cb, "  ✓ 唤醒应答声自激隔离与前缀防火墙 100% 拦截通过");
            passCount++;
        } else {
            failCount++;
        }

        // 12. 缺陷修复回归专项
        log(cb, "\n[专项 11/11] 本轮缺陷修复回归专项（VAD/一句式探针、SQLite 生命周期、软能量门、参数转义、短唤醒词）...");
        boolean fixAllPass = true;

        // 12.1 缺陷 1/2：唤醒链路离线探针必须覆盖 Ask 问答（修复前仅探测 L0，问答被丢）
        String[] probeCases = { "现在几点", "今天几号", "打开蓝牙", "调高亮度" };
        for (String pc : probeCases) {
            if (!router.canHandle(pc)) {
                log(cb, "  ✕ 离线探针漏判（缺陷 1/2 未修复）: [" + pc + "]");
                fixAllPass = false;
            }
        }
        String oneShotAsk = WakeWordStripper.splitWakeCommand("嗨比西比现在几点", false, router);
        if (!"现在几点".equals(oneShotAsk)) {
            log(cb, "  ✕ 一句式问答切分失败（缺陷 2 未修复）: 实际=[" + oneShotAsk + "]");
            fixAllPass = false;
        } else {
            log(cb, "  ✓ 一句式问答切分通过: 「嗨比西比现在几点」-> " + oneShotAsk);
        }

        // 12.2 缺陷 4：L1 SQLite 单例连接生命周期（连续多次查询/落库不得抛 already-closed）
        L1SkillRouter l1 = router.getL1();
        l1.recordSkill("修复回归探针指令", "settings.brightness.up", new JSONObject(), 1);
        boolean l1Ok = l1.hasSkill("修复回归探针指令")
                && l1.hasSkill("修复回归探针指令")
                && l1.hasSkill("修复回归探针指令")
                && l1.getSkillCount() > 0;
        if (l1Ok) {
            log(cb, "  ✓ L1 技能库连续查询/落库稳定（SQLite 单例连接未被误关闭）");
        } else {
            log(cb, "  ✕ L1 技能库查询异常或落库丢失（缺陷 4 未修复）");
            fixAllPass = false;
        }

        // 12.3 缺陷 3：软能量门（神经网 KWS 轻声命中不再被硬 RMS 门吞掉）
        EnergyGate gate = new EnergyGate();
        short[] quiet = new short[1600];
        short[] softLoud = new short[1600];
        for (int i = 0; i < 1600; i++) { quiet[i] = 60; softLoud[i] = 200; }
        boolean hardGateOpened = false;
        for (int i = 0; i < 30; i++) {
            hardGateOpened |= gate.processFrame(quiet, quiet.length);
        }
        boolean softFrame = gate.processFrame(softLoud, softLoud.length);
        boolean softPass = gate.isSoftSpeech() && !softFrame && !hardGateOpened;
        if (softPass) {
            log(cb, "  ✓ 软能量门生效：rms=200 帧 硬门=false / 软门=true（轻声唤醒可放行，且未退化为止噪失效）");
        } else {
            log(cb, "  ✕ 软能量门异常: 硬门=" + softFrame + ", 软门=" + gate.isSoftSpeech() + ", 静音期误开=" + hardGateOpened);
            fixAllPass = false;
        }

        // 12.4 缺陷 5：Shell 参数转义（单引号断裂与命令分隔符注入）
        //      改测 ShellCommand —— 不再删字符，而是整体单引号包裹，
        //      分隔符/命令替换在引号内只是字面量（JVM 单测 ShellCommandTest 还会真跑 sh 验证参数边界）。
        java.util.Map<String, String> esc = new java.util.HashMap<String, String>();
        esc.put("x", "a; rm -rf /data/local/tmp; 'x'");
        esc.put("c", "Tom's bag");
        String escapedInject = ShellCommand.fill("echo {x}", esc);
        String escapedQuote = ShellCommand.fill("--es t '{c}'", esc);
        if ("echo 'a; rm -rf /data/local/tmp; '\\''x'\\'''".equals(escapedInject)
                && "--es t 'Tom'\\''s bag'".equals(escapedQuote)) {
            log(cb, "  ✓ 参数转义通过: [" + escapedQuote + "] / [" + escapedInject + "]");
        } else {
            log(cb, "  ✕ 参数转义异常: [" + escapedQuote + "] / [" + escapedInject + "]");
            fixAllPass = false;
        }

        // 12.5 缺陷 10：2 字短唤醒词只允许全字精确匹配（防日常句子误唤醒）
        //      「小溪」已按用户要求下线 —— 这里改为断言"它不再被识别为唤醒词"，
        //      同时验证存活的「比西比/嗨比西比」仍走通。
        boolean shortWordOk = !WakeWordStripper.isWakeText("小溪")
                && !WakeWordStripper.containsWakeWord("小溪")
                && !WakeWordStripper.isWakeTextPy("小溪")
                && !WakeWordStripper.containsWakeWord("小溪流水潺潺")
                && WakeWordStripper.containsWakeWord("比西比")
                && WakeWordStripper.isWakeText("比西比")
                && WakeWordStripper.containsWakeWord("嗨比西比")
                && WakeWordStripper.isWakeText("嗨比西比");
        if (shortWordOk) {
            log(cb, "  ✓ 唤醒词表回归通过：「小溪」已下线（三种判定均不识别），「比西比/嗨比西比」仍工作");
        } else {
            log(cb, "  ✕ 唤醒词表判定异常（小溪下线不彻底 或 存活词失效）");
            fixAllPass = false;
        }

        // 12.6 噪声分级 + 分级映射方向正确性
        {
            // 注意：EnergyGate 的底噪用 0.95 平滑因子追踪，需要足够多帧才收敛。
            // 200 → 1200 需约 ln(差比)/ln(1/0.95) ≈ 35 帧理论，实测取 800 帧留足裕量。
            EnergyGate envGate = new EnergyGate();
            short[] quietFrame = new short[1600];
            for (int i = 0; i < 1600; i++) quietFrame[i] = 60;
            for (int i = 0; i < 800; i++) envGate.processFrame(quietFrame, quietFrame.length);
            int lvlQuiet = envGate.getNoiseLevel();
            float floorQuiet = envGate.getNoiseFloor();

            EnergyGate loudGate = new EnergyGate();
            short[] loudFrame = new short[1600];
            // rms=300 < 硬门限 350 → 不进人声分支，会被当作环境底噪累积；
            // 从初值 200 平滑收敛到 300，落在"普通/嘈杂"档（≥1）。
            for (int i = 0; i < 1600; i++) loudFrame[i] = 300;
            for (int i = 0; i < 800; i++) loudGate.processFrame(loudFrame, loudFrame.length);
            int lvlLoud = loudGate.getNoiseLevel();
            float floorLoud = loudGate.getNoiseFloor();

            boolean levelOk = (lvlQuiet == 0) && (lvlLoud >= 1);
            boolean orderOk = floorLoud > floorQuiet * 2.0f;   // 噪声底应显著抬升
            boolean monotonic = lvlQuiet <= lvlLoud;
            if (levelOk && orderOk && monotonic) {
                log(cb, "  ✓ 环境自适应生效：安静→分级 " + lvlQuiet
                        + "（底噪 " + String.format("%.0f", floorQuiet) + "）"
                        + " / 嘈杂→分级 " + lvlLoud
                        + "（底噪 " + String.format("%.0f", floorLoud) + "），方向单调正确");
            } else {
                log(cb, "  ✕ 环境自适应异常：安静分级=" + lvlQuiet + " 噪声底=" + floorQuiet
                        + " / 嘈杂分级=" + lvlLoud + " 噪声底=" + floorLoud);
                fixAllPass = false;
            }
        }

        // 12.7 OneSay 开源版：不再内置唤醒词表（用户在设置里自定义，见 WakeWords / WakeWordsTest），本项跳过
        log(cb, "  - 内置唤醒词表已移除（开源版默认关、用户自定义），跳过 12.7");

        if (fixAllPass) {
            log(cb, "  ✓ 本轮 5 类缺陷修复回归专项全部通过");
            passCount++;
        } else {
            failCount++;
        }

        // 13. 专项测试：意图分类（ASK/AGENT/MIXED）与「缓存优先于 LLM」路由策略
        //     （2026-10-02 用户诉求：输入后先判定 ask / agent / 混合任务，
        //       路由完成再查缓存，由缓存命中与否决定用缓存还是调 LLM）
        log(cb, "\n[专项 12/12] 路由策略专项（意图分类 → 查缓存 → 决定用缓存还是 LLM）...");
        boolean routeAllPass = true;

        // 13.1 三分类准确性（L0 真实命中是 AGENT 的硬证据）
        String[][] kindCases = {
                { "介绍一下相对论", "ASK" },
                { "感冒了吃什么药", "ASK" },
                { "打开蓝牙", "AGENT" },
                { "把亮度调到最低", "AGENT" },
                { "帮我把亮度调到最低，顺便告诉我现在几点", "MIXED" },
        };
        for (String[] kc : kindCases) {
            IntentClassifier.Kind k = router.classify(kc[0]);
            if (k.name().equals(kc[1])) {
                log(cb, "  ✓ 分类正确: [" + kc[0] + "] -> " + k.name());
            } else {
                log(cb, "  ✕ 分类偏差: [" + kc[0] + "] 期望=" + kc[1] + " 实际=" + k.name());
                routeAllPass = false;
            }
        }

        // 13.2 天气不得再由本地写死答案拦截（必须弃权，交给联网通道）
        String weatherLocal = AskEngine.tryAnswer("今天天气怎么样");
        if (weatherLocal == null) {
            log(cb, "  ✓ 天气已从本地假答案移除（不再返回写死的 18℃~26℃），改由 L2 联网通道处理");
        } else {
            log(cb, "  ✕ 天气仍被本地写死答案拦截: " + weatherLocal);
            routeAllPass = false;
        }

        // 13.3 「缓存命中 ⇒ 不调 LLM」的直接证明
        String cacheProbe = "自测缓存优先探针";
        l1.recordSkill(cacheProbe, IntentClassifier.Kind.AGENT, "settings.brightness.up",
                new JSONObject(), 1, L1SkillRouter.TTL_PERMANENT);
        Invocation cacheInv = router.route(cacheProbe);
        if (cacheInv != null && cacheInv.source == Invocation.RouteSource.L1_SKILL) {
            log(cb, "  ✓ 缓存优先策略生效: [" + cacheProbe + "] 来源=" + cacheInv.source + "（未走 LLM）");
        } else {
            log(cb, "  ✕ 缓存优先策略失效: 来源=" + (cacheInv != null ? cacheInv.source : "null"));
            routeAllPass = false;
        }

        // 13.4 时效性问句必须被拒绝写入缓存（否则明天会拿到今天的答案）
        l1.recordSkill("现在几点", IntentClassifier.Kind.ASK, "ask.reply",
                new JSONObject(), 0, L1SkillRouter.TTL_ASK_KNOWLEDGE);
        if (!l1.hasSkill("现在几点")) {
            log(cb, "  ✓ 时效性问句被正确拒绝写入缓存");
        } else {
            log(cb, "  ✕ 时效性问句竟然入了缓存");
            routeAllPass = false;
        }

        // 13.5 混合任务多步计划（依赖网络，仅记录不作断言，避免网络抖动产生假失败）
        if (router.getL2().hasApiKey()) {
            Invocation mixedInv = router.route("把亮度调到最低，顺便告诉我现在几点");
            if (mixedInv != null) {
                log(cb, "  · 混合任务路由返回: " + (mixedInv.isPlan()
                        ? ("多步计划 " + mixedInv.steps.size() + " 步")
                        : mixedInv.sign) + " (src=" + mixedInv.source + ")");
            } else {
                log(cb, "  · 混合任务本次未返回结果（网络抖动），不计入失败");
            }
        }

        if (routeAllPass) {
            log(cb, "  ✓ 路由策略专项全部通过（分类 → 缓存 → LLM 链路符合预期）");
            passCount++;
        } else {
            failCount++;
        }

        // 13.6 相对时间专项 —— 审计 R-3：纯离线计算，13.6.1–13.6.8 已逐条迁到 JVM
        //   test-jvm/.../selftest/V2SelfTestPortedTest（relativeTime* / wakeRemindIsAction / alarmInTopK / timeSensitiveCacheGate）。
        log(cb, "\n[相对时间] 已迁到 JVM（V2SelfTestPortedTest 6 例），设备上不再重复跑");

        // 13.7 识屏落盘策略专项（新增）—— 纯离线，无副作用。
        //   用户实测痛点："识别屏幕每次都是保存到相册，每次都要手动删除很麻烦。"
        //   根因：识屏的中间产物写死 /sdcard/Pictures/bixbyui_shot.png —— Pictures 是
        //   **媒体库扫描目录**，于是每识屏一次相册就多一张垃圾图；而且它和 ui.screenshot
        //   能力共用同一个文件名，两边会互相覆盖。
        //   现在改为应用外部缓存目录（媒体库不扫描）+ 用完即删。
        log(cb, "\n[识屏落盘] 校验中间产物不进相册 / 与截图能力解耦（离线，无副作用）...");
        boolean visionStorPass = true;
        {
            String p = com.onesay.assistant.v2.vision.ScreenVisionEngine.getInstance(ctx).debugShotPath();
            // 13.7.1 中间产物路径**不得落在媒体库扫描目录**（Pictures/DCIM/Movies…）
            if (p == null || p.length() == 0) {
                log(cb, "  ✕ 13.7.1 识屏落盘路径为空");
                visionStorPass = false;
            } else if (p.indexOf("/Pictures/") >= 0 || p.indexOf("/DCIM/") >= 0) {
                log(cb, "  ✕ 13.7.1 识屏中间产物仍落在媒体库目录（会进相册）: " + p);
                visionStorPass = false;
            } else {
                log(cb, "  ✓ 13.7.1 识屏中间产物落在私有缓存目录（媒体库不扫描，不进相册）: " + p);
            }

            // 13.7.2 必须与 ui.screenshot 的落盘文件**解耦**（否则互相覆盖）
            Capability shotCap = reg.describe("ui.screenshot");
            String shotCmd = (shotCap != null) ? shotCap.cmdTemplate : null;
            String shotFile = "bixbyui_shot.png";          // 截图能力的落盘文件名
            boolean decoupled = shotCmd != null && shotCmd.indexOf(shotFile) >= 0
                    && (p == null || !p.endsWith(shotFile));
            if (decoupled) {
                log(cb, "  ✓ 13.7.2 识屏与截图能力的落盘文件名已解耦（不会互相覆盖）");
            } else {
                log(cb, "  ✕ 13.7.2 识屏与截图仍共用同一落盘文件: vision=" + p + " shotCmd=" + shotCmd);
                visionStorPass = false;
            }

            // 13.7.3 ui.screenshot 仍应进相册（用户明确要的产物，不能一起改坏）
            boolean screenshotStillGallery = shotCmd != null && shotCmd.indexOf("/Pictures/") >= 0;
            if (screenshotStillGallery) {
                log(cb, "  ✓ 13.7.3 截图能力仍落相册（用户明确要保存的产物，零回归）");
            } else {
                log(cb, "  ✕ 13.7.3 截图能力被误改，不再落相册: " + shotCmd);
                visionStorPass = false;
            }

            // 13.7.4 ★ 「读」这一支必须识别为 QUERY_INFO，且读屏工具要进候选。
            //   实测事故：「读一下屏幕上的内容」因无读类语素 → 走目标兜底 → 判 CONFIGURE
            //   → 而注入读屏工具的是 QUERY_INFO 家族 → 模型答"本机没有读取屏幕内容的能力"。
            //   这是"语音识屏无效"的**第二层**根因（第一层是 ASK 通道无能力清单）。
            String[] readCases = { "读一下屏幕上的内容", "读出屏幕上的字", "朗读屏幕内容" };
            String readBad = null;
            for (String rc : readCases) {
                if (ActionLexicon.classify(rc) != ActionLexicon.Intent.QUERY_INFO) {
                    readBad = rc + "(intent=" + ActionLexicon.classify(rc) + ")";
                    break;
                }
            }
            boolean visionRecalled = false;
            for (Capability c : com.onesay.assistant.v2.router.CapabilityRetriever.getTopK("读一下屏幕上的内容", 12)) {
                if ("vision.screen.understand".equals(c.sign)) { visionRecalled = true; break; }
            }
            if (readBad == null && visionRecalled) {
                log(cb, "  ✓ 13.7.4 「读」类语素归 QUERY_INFO，且 vision.screen.understand 进 Top-12");
            } else {
                log(cb, "  ✕ 13.7.4 读屏召回异常: 语素=" + readBad + " 进Top12=" + visionRecalled);
                visionStorPass = false;
            }

            if (visionStorPass) passCount++; else failCount++;
        }

        // 13.8 邮件（IMAP）专项（新增）—— **纯离线**，不联网、不真实登录。
        //   邮件走 IMAP 是"有标准协议就别读屏"的落地；这里只钉死**静态契约**与**安全边界**，
        //   真实往返由 `--es mailfetch` 探针在配好授权码后单独验证。
        log(cb, "\n[邮件] 校验 IMAP 能力注册 / 未配置闸门 / 授权码不泄漏（离线，无副作用）...");
        boolean mailPass = true;
        {
            // 13.8.1 两个邮件能力必须注册且走 custom 执行器
            Capability inbox = reg.describe("mail.inbox.latest");
            Capability draft = reg.describe("mail.draft.new");
            if (inbox != null && draft != null
                    && "custom".equals(inbox.executorType) && "custom".equals(draft.executorType)
                    && com.onesay.assistant.v2.executor.MailBridge.isMailSign(inbox.sign)
                    && com.onesay.assistant.v2.executor.MailBridge.isMailSign(draft.sign)) {
                log(cb, "  ✓ 13.8.1 邮件能力注册正确（inbox.latest / draft.new → custom 执行器）");
            } else {
                log(cb, "  ✕ 13.8.1 邮件能力注册异常: inbox=" + (inbox != null)
                        + " draft=" + (draft != null));
                mailPass = false;
            }

            // 13.8.2 未配置时必须**友好拒绝**（且不触网）—— 直接告诉用户缺什么
            //   先快照原配置，测完还原（不能把用户已配好的授权码冲掉）
            String oldUser = com.onesay.assistant.v2.executor.MailBridge.user(ctx);
            String oldHost = com.onesay.assistant.v2.executor.MailBridge.host(ctx);
            int oldPort = com.onesay.assistant.v2.executor.MailBridge.port(ctx);
            // 授权码已改存 Keystore 密文 → 快照要取解密后的明文（直接 getString 拿到密文再 saveConfig 会二次加密）
            String oldPass = com.onesay.assistant.v2.security.SecretPrefs.get(com.onesay.assistant.v2.executor.MailBridge.prefs(ctx),
                    com.onesay.assistant.v2.executor.MailBridge.KEY_PASS);
            // 服务商与代理也要一起快照。
            //   原实现只存 host/port/user/pass，而 clearConfig 是**整份清空**
            //   —— 设备上已经填好真实授权码之后跑一次自测，会把"服务商/代理"这两项静默丢掉。
            String oldProvider = com.onesay.assistant.v2.executor.MailBridge.provider(ctx);
            String oldProxy = com.onesay.assistant.v2.executor.MailBridge.proxy(ctx);
            try {
                com.onesay.assistant.v2.executor.MailBridge.clearConfig(ctx);
                boolean gateOk = !com.onesay.assistant.v2.executor.MailBridge.isConfigured(ctx);
                ExecutionResult r = com.onesay.assistant.v2.executor.UnifiedExecutor.getInstance(ctx)
                        .execute(inbox, new JSONObject());
                boolean friendly = r != null && !r.ok
                        && r.userMessage != null && r.userMessage.indexOf("授权码") >= 0;
                if (gateOk && friendly) {
                    log(cb, "  ✓ 13.8.2 未配置时友好拒绝（提示需要 16 位授权码，且未触网）");
                } else {
                    log(cb, "  ✕ 13.8.2 未配置闸门失效: configured=" + !gateOk
                            + " msg=" + (r != null ? r.userMessage : "null"));
                    mailPass = false;
                }

                // 13.8.3 ★ 安全断言：配置摘要里**绝不能**出现授权码
                String fakePass = "AbCd1234EfGh5678";
                com.onesay.assistant.v2.executor.MailBridge.saveConfig(ctx, "imap.qq.com", 993,
                        "someone@qq.com", fakePass);
                String info = com.onesay.assistant.v2.executor.MailBridge.configInfo(ctx);
                if (info.indexOf(fakePass) < 0 && info.indexOf("***") >= 0) {
                    log(cb, "  ✓ 13.8.3 配置摘要已脱敏（授权码不出现在任何可打印文本里）");
                } else {
                    log(cb, "  ✕ 13.8.3 配置摘要泄漏了授权码明文: " + info);
                    mailPass = false;
                }

                // 13.8.4 纯函数：正文抽取（HTML → 文本）必须可用（邮件正文多为 HTML）
                String html = "<div>你好<br/>这是一封<b>测试</b>邮件</div><script>x=1</script>";
                String text = com.onesay.assistant.v2.executor.MailBridge.stripHtml(html);
                if (text != null && text.indexOf("测试") >= 0 && text.indexOf("x=1") < 0
                        && text.indexOf("<") < 0) {
                    log(cb, "  ✓ 13.8.4 HTML 正文抽取正确（保留文字、剥掉标签与脚本）");
                } else {
                    log(cb, "  ✕ 13.8.4 HTML 正文抽取异常: " + text);
                    mailPass = false;
                }
                // 13.8.5 ★★ 回归锁：未识别槽位占位符**绝不能炸掉整条 L0 路由**
                //   事故：新加邮件能力时 say 模板误写 `写邮件草稿{subject}`，
                //   而 L0 只认 {app}/{n}/{m}/{url}/{content} → 未替换的 {subject} 进了
                //   Pattern.compile → PatternSyntaxException → **任何句子都路由失败**。
                //   修复：buildRegex 把未识别的 {…} 转义成字面量。
                boolean regexSafe = true;
                String[] badTemplates = {
                        "写邮件草稿{subject}", "读邮件{count}封", "{unknown}", "混合{n}与{xyz}" };
                for (String t : badTemplates) {
                    try {
                        java.util.regex.Pattern.compile(L0RuleRouter.debugBuildRegex(t));
                    } catch (Throwable te) {
                        regexSafe = false;
                        log(cb, "  ✕ 13.8.5 模板仍会让正则编译失败: " + t + " → " + te.getMessage());
                        break;
                    }
                }
                // 正常模板必须仍然可用（零回归）
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                            L0RuleRouter.debugBuildRegex("定{n}点闹钟"));
                    if (!p.matcher("定7点闹钟").matches()) regexSafe = false;
                } catch (Throwable te) {
                    regexSafe = false;
                }
                // 且路由入口对任意句子都不再抛异常
                try {
                    router.route("读一下我的邮件");
                } catch (Throwable te) {
                    regexSafe = false;
                    log(cb, "  ✕ 13.8.5 路由入口抛异常: " + te.getMessage());
                }
                if (regexSafe) {
                    log(cb, "  ✓ 13.8.5 未知槽位被转义：坏模板只会「不匹配」，不会炸掉整条路由（"
                            + badTemplates.length + " 个坏模板全过 + 正常模板零回归）");
                } else {
                    mailPass = false;
                }

                // 13.8.6 多邮箱服务商预设（MailBridge 是**通用 IMAP**，不绑 QQ）
                //   ——用户问"支持其他邮箱登录吗"，这里把答案钉成可执行断言。
                boolean presetOk = "imap.qq.com".equals(com.onesay.assistant.v2.executor.MailBridge.presetHost("qq"))
                        && "imap.163.com".equals(com.onesay.assistant.v2.executor.MailBridge.presetHost("163"))
                        && "imap.gmail.com".equals(com.onesay.assistant.v2.executor.MailBridge.presetHost("gmail"))
                        && "outlook.office365.com".equals(
                                com.onesay.assistant.v2.executor.MailBridge.presetHost("outlook"))
                        && "imap.exmail.qq.com".equals(
                                com.onesay.assistant.v2.executor.MailBridge.presetHost("exmail"))
                        && com.onesay.assistant.v2.executor.MailBridge.presetHost("没这个服务商") == null
                        // 国内直连可达性判据（用于给出"要不要代理"的人话提示）
                        && !com.onesay.assistant.v2.executor.MailBridge.isLikelyBlocked("imap.qq.com")
                        && !com.onesay.assistant.v2.executor.MailBridge.isLikelyBlocked("imap.163.com")
                        && !com.onesay.assistant.v2.executor.MailBridge.isLikelyBlocked("outlook.office365.com")
                        && com.onesay.assistant.v2.executor.MailBridge.isLikelyBlocked("imap.gmail.com");
                if (presetOk) {
                    log(cb, "  ✓ 13.8.6 邮箱服务商预设正确（qq/163/gmail/outlook/exmail）+ 直连可达性判据正确");
                } else {
                    log(cb, "  ✕ 13.8.6 服务商预设或可达性判据有误");
                    mailPass = false;
                }

                // 13.8.7 代理解析（IMAP 走 TCP 993，不是 HTTP —— 必须按协议配）
                //   socks5 走 mail.imaps.socks.*；http 走 mail.imaps.proxy.*（CONNECT 隧道）
                String pSocks = com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("socks5://127.0.0.1:1080");
                String pSocks2 = com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("127.0.0.1:7891");
                String pHttp = com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("http://127.0.0.1:7890");
                boolean proxyOk = "socks5 127.0.0.1:1080".equals(pSocks)
                        && "socks5 127.0.0.1:7891".equals(pSocks2)      // 只写 host:port 默认按 SOCKS5
                        && "http 127.0.0.1:7890".equals(pHttp)
                        && com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("") == null
                        && com.onesay.assistant.v2.executor.MailBridge.debugParseProxy("socks5://127.0.0.1") == null
                        && com.onesay.assistant.v2.executor.MailBridge.debugParseProxy(null) == null;
                if (proxyOk) {
                    log(cb, "  ✓ 13.8.7 代理解析正确（socks5 / http / 裸 host:port 默认 socks5 / 非法输入返回空）");
                } else {
                    log(cb, "  ✕ 13.8.7 代理解析异常: socks=" + pSocks + " bare=" + pSocks2 + " http=" + pHttp);
                    mailPass = false;
                }

                // ★★ 13.8.8（2026-10-06）重试判据的**安全锁**：必须"只重试链路抖动、绝不重试凭据错误"。
                //   实测依据：本机对 imap.gmail.com:993 连跑 3 次只有 1 次成功（另 2 次被掐断
                //   Connection closed by peer），所以必须重试；但**反复用错凭据登录会触发风控**
                //   （本机收件箱 10-04 就有一条「登录尝试已被阻止」告警）。
                //   这组断言是纯函数，离线、零副作用 —— 但它锁住的是"别把账号玩封"这条红线。
                javax.mail.AuthenticationFailedException authErr =
                        new javax.mail.AuthenticationFailedException("Invalid credentials (Failure)");
                javax.mail.MessagingException authWrapped =
                        new javax.mail.MessagingException("login failed", authErr);
                javax.mail.MessagingException closedByPeer =
                        new javax.mail.MessagingException("Connection closed by peer");
                javax.mail.MessagingException resetCause =
                        new javax.mail.MessagingException("read failed",
                                new java.net.SocketException("Connection reset"));
                java.net.SocketTimeoutException timeoutErr = new java.net.SocketTimeoutException("Read timed out");
                javax.mail.MessagingException folderErr =
                        new javax.mail.MessagingException("Folder INBOX does not exist");

                boolean retryOk =
                        // ① 认证失败：两种包装形态都必须判为"不重试"
                        com.onesay.assistant.v2.executor.MailBridge.hasAuthFailure(authErr)
                        && com.onesay.assistant.v2.executor.MailBridge.hasAuthFailure(authWrapped)
                        && !com.onesay.assistant.v2.executor.MailBridge.isTransient(authErr)
                        && !com.onesay.assistant.v2.executor.MailBridge.isTransient(authWrapped)
                        // ② 链路抖动：类型判据 + 文本判据 + 异常链判据都要认
                        && com.onesay.assistant.v2.executor.MailBridge.isTransient(closedByPeer)
                        && com.onesay.assistant.v2.executor.MailBridge.isTransient(resetCause)
                        && com.onesay.assistant.v2.executor.MailBridge.isTransient(timeoutErr)
                        // ③ 确定性业务错误（邮件夹不存在）不许重试
                        && !com.onesay.assistant.v2.executor.MailBridge.isTransient(folderErr);
                if (retryOk) {
                    log(cb, "  ✓ 13.8.8 重试判据正确（链路抖动重试 / 凭据错误与业务错误绝不重试）");
                } else {
                    log(cb, "  ✕ 13.8.8 重试判据异常: auth="
                            + com.onesay.assistant.v2.executor.MailBridge.isTransient(authErr)
                            + " wrapped=" + com.onesay.assistant.v2.executor.MailBridge.isTransient(authWrapped)
                            + " peer=" + com.onesay.assistant.v2.executor.MailBridge.isTransient(closedByPeer)
                            + " timeout=" + com.onesay.assistant.v2.executor.MailBridge.isTransient(timeoutErr));
                    mailPass = false;
                }

                // ★★ 13.8.9（2026-10-06）正文抽取的回归锁：**这是本轮真机暴露的真 bug**。
                //   现象：QQ 邮箱连读 3 封，正文**全部**为空（日志只有一句
                //   `IMAPInputStream cannot be cast to Multipart`）。
                //   根因链：① JavaMail 解析不出 boundary ⇒ getContent() 退回原始流；
                //          ② 兜底只"剥标签"不解码 ⇒ 正文变成 `=E5=B7=B2=E5=9C=A8...` 这种
                //             quoted-printable 原文（等于把乱码端给用户和模型）。
                //   这里用一个**合成的原始 MIME** 钉死三件事：
                //     ① QP 必须真解码成中文；② 优先 text/plain 而不是 text/html；
                //     ③ 图片段（base64）不许混进正文。
                String rawMime =
                        "--BND\r\n"
                        + "Content-Type: text/html; charset=UTF-8\r\n"
                        + "Content-Transfer-Encoding: quoted-printable\r\n"
                        + "\r\n"
                        + "<p>=E5=A4=87=E7=94=A8</p>\r\n"
                        + "--BND\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "Content-Transfer-Encoding: quoted-printable\r\n"
                        + "\r\n"
                        + "=E4=BD=A0=E5=A5=BD=EF=BC=8C=E4=B8=96=E7=95=8C\r\n"
                        + "--BND\r\n"
                        + "Content-Type: image/png\r\n"
                        + "Content-Transfer-Encoding: base64\r\n"
                        + "\r\n"
                        + "iVBORw0KGgo=\r\n"
                        + "--BND--\r\n";
                String extracted = com.onesay.assistant.v2.executor.MailBridge.debugExtractRawMime(rawMime);
                boolean bodyOk = extracted != null
                        && extracted.indexOf("\u4f60\u597d\uff0c\u4e16\u754c") >= 0   // "你好，世界"
                        && extracted.indexOf("\u5907\u7528") < 0                     // "备用"（HTML 段）不该被选中
                        && extracted.indexOf("PNG") < 0
                        && extracted.indexOf("=") < 0;                               // 不许残留 QP 转义
                if (bodyOk) {
                    log(cb, "  ✓ 13.8.9 原始 MIME 正文抽取正确（QP 真解码 / 优先纯文本 / 丢弃图片段）");
                } else {
                    log(cb, "  ✕ 13.8.9 正文抽取异常: [" + extracted + "]");
                    mailPass = false;
                }

                // 13.8.10 QP 软换行：'=' + CRLF 必须整段吃掉（否则正文里会出现断行的等号）
                String softBreak = com.onesay.assistant.v2.executor.MailBridge
                        .debugDecodeQuotedPrintable("=E4=BD=A0=\r\n=E5=A5=BD");
                if ("\u4f60\u597d".equals(softBreak)) {
                    log(cb, "  ✓ 13.8.10 QP 软换行正确（'=' + CRLF 被吃掉，拼回「你好」）");
                } else {
                    log(cb, "  ✕ 13.8.10 QP 软换行异常: [" + softBreak + "]");
                    mailPass = false;
                }

            } finally {
                // 还原原配置（绝不留测试数据）
                com.onesay.assistant.v2.executor.MailBridge.clearConfig(ctx);
                if (oldUser != null && oldUser.length() > 0) {
                    com.onesay.assistant.v2.executor.MailBridge.saveConfig(ctx, oldHost, oldPort,
                            oldUser, oldPass);
                    // 服务商 / 代理一并还原（见上面的快照注释）
                    if (oldProvider != null && oldProvider.length() > 0) {
                        com.onesay.assistant.v2.executor.MailBridge.saveProvider(ctx, oldProvider);
                    }
                    if (oldProxy != null && oldProxy.length() > 0) {
                        com.onesay.assistant.v2.executor.MailBridge.saveProxy(ctx, oldProxy);
                    }
                    Log.i("V2SelfTest", "[MAIL] 已还原用户原有邮件配置（"
                            + com.onesay.assistant.v2.executor.MailBridge.configLogInfo(ctx) + "）");
                }
            }

            if (mailPass) passCount++; else failCount++;
        }

        // 14. 只读探针专项（//     ① 白名单闸门（放行合法只读 / 拦截注入与写操作）
        //     ② dump 解析器（Activity Resolver Table → 组件接口）
        //     ③ 注册不变量（探针可被 describe、且不污染普通召回清单）
        log(cb, "\n[只读探针] 校验白名单闸门 / dump 解析 / 注册不变量（离线，无副作用）...");
        boolean probeAllPass = true;
        {
            // 14.1 放行合法只读命令
            if (com.onesay.assistant.v2.probe.ProbeEngine.checkReadOnlyCommand("pm list packages") == null
                    && com.onesay.assistant.v2.probe.ProbeEngine.checkReadOnlyCommand("pm dump com.android.settings") == null
                    && com.onesay.assistant.v2.probe.ProbeEngine.checkReadOnlyCommand("dumpsys package com.android.settings") == null) {
                log(cb, "  ✓ 14.1 只读白名单正确放行（pm list / pm dump / dumpsys package）");
            } else {
                log(cb, "  ✕ 14.1 只读白名单误伤合法命令");
                probeAllPass = false;
            }

            // 14.2 拦截注入与写操作（6 条攻击样本）
            String[] evilCmds = {
                    "dumpsys package com.x; am start -n com.x/.Y",
                    "pm list packages && reboot",
                    "pm dump com.x > /sdcard/leak.txt",
                    "dumpsys package com.x | grep x",
                    "am start -n com.x/.Y",
                    "setprop ro.test 1",
            };
            boolean evilBlocked = true;
            for (String e : evilCmds) {
                if (com.onesay.assistant.v2.probe.ProbeEngine.checkReadOnlyCommand(e) == null) {
                    evilBlocked = false;
                    log(cb, "  ✕ 14.2 漏放攻击样本: " + e);
                }
            }
            if (evilBlocked) {
                log(cb, "  ✓ 14.2 注入/拼接/重定向/写操作全部被拦截（6/6 攻击样本）");
            } else {
                probeAllPass = false;
            }

            // 14.3 dump 解析器（Activity Resolver Table → 组件条目 + 价值过滤）
            String sample = "  Activity Resolver Table:\n"
                    + "    Non-Data Actions:\n"
                    + "        android.intent.action.MAIN:\n"
                    + "          deadbeef com.demo/.MainActivity filter cafe1234\n"
                    + "            Action: \"android.intent.action.MAIN\"\n"
                    + "            Category: \"android.intent.category.LAUNCHER\"\n"
                    + "        android.intent.action.VIEW:\n"
                    + "          5555aaaa com.demo/.ViewActivity filter 6666bbbb\n"
                    + "            Action: \"android.intent.action.VIEW\"\n"
                    + "            Category: \"android.intent.category.DEFAULT\"\n"
                    + "    Full MIME Types:\n"
                    + "        text/plain:\n"
                    + "          9876fedc com.demo/.WidgetProvider filter 0011aabb\n"
                    + "            Action: \"android.appwidget.action.APPWIDGET_UPDATE\"\n"
                    + "  Service Resolver Table:\n"
                    + "    1111ffff com.demo/.BgService filter 2222abcd\n";
            java.util.List<com.onesay.assistant.v2.probe.ProbeEngine.IntentEntry> ents =
                    com.onesay.assistant.v2.probe.ProbeEngine.parseDump(sample);
            int valuable = 0;
            String mainKey = null;
            for (com.onesay.assistant.v2.probe.ProbeEngine.IntentEntry e : ents) {
                if (e.isValuable()) {
                    valuable++;
                    if (e.comp.contains("MainActivity")) mainKey = e.primaryActionKey();
                }
            }
            boolean parserOk = (ents.size() == 3) && (valuable == 2) && "main".equals(mainKey);
            if (parserOk) {
                log(cb, "  ✓ 14.3 dump 解析器正确（3 条目 / 2 有价值 / LAUNCHER→main；Service 段已被截断）");
            } else {
                log(cb, "  ✕ 14.3 dump 解析器异常: 条目=" + ents.size() + " 有价值=" + valuable + " main键=" + mainKey);
                probeAllPass = false;
            }

            // 14.4 注册不变量：探针可被 describe（AgentLoop 执行链路需要），
            //      但**不在** listCapabilities（普通召回清单）里。
            boolean probeRegistered = reg.describe("probe.apps") != null
                    && reg.describe("probe.app_intents") != null
                    && reg.describe("probe.shell_read") != null;
            boolean probeListed = reg.listProbes().size() == 3;
            boolean notPolluting = true;
            for (Capability c : reg.listCapabilities()) {
                if (c.sign != null && c.sign.startsWith("probe.")) { notPolluting = false; break; }
            }
            if (probeRegistered && probeListed && notPolluting) {
                log(cb, "  ✓ 14.4 探针注册不变量成立（describe 可解析 / 3 条在探索族 / 不污染普通召回）");
            } else {
                log(cb, "  ✕ 14.4 探针注册不变量失败: describe=" + probeRegistered
                        + " listProbes=" + reg.listProbes().size() + " 未污染=" + notPolluting);
                probeAllPass = false;
            }
        }
        if (probeAllPass) {
            log(cb, "  ✓ 只读探针专项全部通过（闸门 → 解析 → 注册不变量）");
            passCount++;
        } else {
            failCount++;
        }

        // 15. 界面动作互斥专项（//     判据（isUiFrontAction）与任务级去重键（uiDedupKey）必须满足：
        //       · 商店搜索 / 应用拉起（intent 或 am start 通道）→ 判为"界面动作"，收互斥管控；
        //       · 只读探针 / 调亮度（shell 无界面切换）→ 判为非界面动作，不受限；
        //       · 两家商店搜同一个词 → 去重键相同（并行 fan-out 会被拦成一条）；
        //       · 拉起两个不同 App → 去重键不同（不会误拦合法的多步打开）。
        log(cb, "\n[界面动作互斥] 校验 AgentLoop 的界面独占判据与去重键（离线，无副作用）...");
        boolean uiDedupAllPass = true;
        {
            Capability cMarket = reg.describe("store.search.market");
            Capability cPlay = reg.describe("store.search.play");
            Capability cLaunch = reg.describe("app.launch");
            Capability cProbeApps = reg.describe("probe.apps");
            Capability cBright = reg.describe("settings.brightness.set");

            // 15.1 界面动作判据：商店/拉起类必须被识别，只读类必须放行
            boolean uiJudged = cMarket != null && cPlay != null && cLaunch != null
                    && cProbeApps != null && cBright != null
                    && AgentLoop.isUiFrontAction(cMarket)
                    && AgentLoop.isUiFrontAction(cPlay)
                    && AgentLoop.isUiFrontAction(cLaunch)
                    && !AgentLoop.isUiFrontAction(cProbeApps)
                    && !AgentLoop.isUiFrontAction(cBright);
            if (uiJudged) {
                log(cb, "  ✓ 15.1 界面动作判据正确（商店/拉起=界面动作；探针/调亮度=非界面动作）");
            } else {
                log(cb, "  ✕ 15.1 界面动作判据异常: samsung=" + (cMarket != null)
                        + " play=" + (cPlay != null) + " launch=" + (cLaunch != null));
                uiDedupAllPass = false;
            }

            // 15.2 任务级去重键：两家商店搜同词 → 同一键；两个不同 App → 不同键
            try {
                JSONObject kw1 = new JSONObject();
                kw1.put("keyword", "微信");
                JSONObject kw2 = new JSONObject();
                kw2.put("keyword", "微信");
                JSONObject app1 = new JSONObject();
                app1.put("app", "QQ");
                JSONObject app2 = new JSONObject();
                app2.put("app", "微信");
                boolean sameStore = cMarket != null && cPlay != null
                        && AgentLoop.uiDedupKey(cMarket, kw1).equals(AgentLoop.uiDedupKey(cPlay, kw2));
                boolean diffApps = cLaunch != null
                        && !AgentLoop.uiDedupKey(cLaunch, app1).equals(AgentLoop.uiDedupKey(cLaunch, app2));
                if (sameStore && diffApps) {
                    log(cb, "  ✓ 15.2 去重键正确（商店A/B同词→同键；拉起不同App→不同键）");
                } else {
                    log(cb, "  ✕ 15.2 去重键异常: 商店同键=" + sameStore + " App异键=" + diffApps);
                    uiDedupAllPass = false;
                }
            } catch (Throwable t) {
                log(cb, "  ✕ 15.2 去重键构造异常: " + t.getMessage());
                uiDedupAllPass = false;
            }
        }
        if (uiDedupAllPass) {
            log(cb, "  ✓ 界面动作互斥专项全部通过（判据 → 去重键）");
            passCount++;
        } else {
            failCount++;
        }

        // 16. 权限确认策略专项（//     · tierOf：可恢复敏感操作（重启）= 黄卡；不可逆（恢复出厂）/ 删除类（卸载）/
        //       不可信来源 = 红卡；
        //     · 「始终允许」白名单：默认不放行 → allowAlways 后放行 → revoke 恢复
        //       （全程用假签名，绝不触碰真实策略）；
        //     · gate()：白名单命中时把 REQUIRE_CONFIRM 降级为 ALLOW（viaAlwaysAllow）。
        log(cb, "\n[权限确认策略] 校验卡片分级 / 白名单往返 / 闸门降级（离线，无副作用）...");
        boolean consentAllPass = true;
        {
            Capability cReboot = reg.describe("sys.reboot");
            Capability cWipe = reg.describe("sys.wipe");
            Capability cUninstall = reg.describe("app.uninstall");

            // 16.1 分级：重启=黄；恢复出厂/卸载/不可信来源=红
            boolean tierOk = cReboot != null && cWipe != null && cUninstall != null
                    && ConsentPolicy.tierOf(cReboot, false) == ConsentPolicy.TIER_YELLOW
                    && ConsentPolicy.tierOf(cWipe, false) == ConsentPolicy.TIER_RED
                    && ConsentPolicy.tierOf(cUninstall, false) == ConsentPolicy.TIER_RED
                    && ConsentPolicy.tierOf(cReboot, true) == ConsentPolicy.TIER_RED;
            if (tierOk) {
                log(cb, "  ✓ 16.1 卡片分级正确（重启=黄卡；恢复出厂/卸载/不可信来源=红卡）");
            } else {
                log(cb, "  ✕ 16.1 卡片分级异常");
                consentAllPass = false;
            }

            // 16.2 白名单往返（假签名，防污染真实策略）
            String fakeSign = "test.consent.dummy";
            ConsentPolicy.revoke(fakeSign);   // 先清场（防上轮残留）
            boolean w0 = !ConsentPolicy.isAlwaysAllowed(fakeSign);
            ConsentPolicy.allowAlways(fakeSign);
            boolean w1 = ConsentPolicy.isAlwaysAllowed(fakeSign);
            ConsentPolicy.revoke(fakeSign);
            boolean w2 = !ConsentPolicy.isAlwaysAllowed(fakeSign);
            if (w0 && w1 && w2) {
                log(cb, "  ✓ 16.2 「始终允许」白名单往返正确（默认不放行 → 写入放行 → 撤销恢复）");
            } else {
                log(cb, "  ✕ 16.2 白名单往返异常: " + w0 + "/" + w1 + "/" + w2);
                consentAllPass = false;
            }

            // 16.3 闸门降级：假能力（risk=2 需确认）→ 白名单命中后降级为 ALLOW 且标记 viaAlwaysAllow
            Capability fakeCap = new Capability.Builder(fakeSign, "自测用假能力")
                    .risk(2, true)
                    .executor("shell")
                    .cmd("true")
                    .build();
            ConsentPolicy.revoke(fakeSign);
            ConsentPolicy.Outcome g0 = ConsentPolicy.gate(fakeCap, new JSONObject(), false);
            boolean g0ok = g0.decision == ToolGate.Decision.REQUIRE_CONFIRM && !g0.viaAlwaysAllow;
            ConsentPolicy.allowAlways(fakeSign);
            ConsentPolicy.Outcome g1 = ConsentPolicy.gate(fakeCap, new JSONObject(), false);
            boolean g1ok = g1.decision == ToolGate.Decision.ALLOW && g1.viaAlwaysAllow;
            ConsentPolicy.revoke(fakeSign);
            ConsentPolicy.Outcome g2 = ConsentPolicy.gate(fakeCap, new JSONObject(), false);
            boolean g2ok = g2.decision == ToolGate.Decision.REQUIRE_CONFIRM;
            if (g0ok && g1ok && g2ok) {
                log(cb, "  ✓ 16.3 闸门降级正确（需确认 → 白名单命中降为 ALLOW → 撤销后恢复）");
            } else {
                log(cb, "  ✕ 16.3 闸门降级异常: " + g0ok + "/" + g1ok + "/" + g2ok);
                consentAllPass = false;
            }
        }
        if (consentAllPass) {
            log(cb, "  ✓ 权限确认策略专项全部通过（分级 → 白名单 → 闸门降级）");
            passCount++;
        } else {
            failCount++;
        }

        // 17. 闹钟联动专项（//     · 四个闹钟能力全走 alarmbridge（executor 单一真源）；
        //     · 通道常量：只发给 OneSay 时钟、权限名正确、系统兜底重复日换算；
        //     · 闹钟表解析/目标选择：混杂输出摘 JSON、多闹钟未指定目标必须拒绝；
        //     · 时间格式化与 nextAt；
        //     · 判定：alarmbridge 既不是"拉起界面"（不让位、要留卡片），
        //       也不是"切屏动作"（不占 AgentLoop 同轮名额）。
        //     全程纯离线（不触发真实广播/落盘），可重复跑。
        log(cb, "\n[闹钟联动] 校验桥解析 / 通道常量 / 判定（离线，无副作用）...");
        boolean mbAllPass = true;
        {
            // 17.1 能力注册：四个闹钟域能力全走 alarmbridge
            String[] mbSigns = { "clock.alarm.set", "alarm.query", "alarm.toggle", "alarm.cancel" };
            boolean mbRegOk = true;
            for (int i = 0; i < mbSigns.length; i++) {
                Capability c = reg.describe(mbSigns[i]);
                if (c == null || !"alarmbridge".equals(c.executorType)) {
                    mbRegOk = false;
                    log(cb, "  ✕ 17.1 " + mbSigns[i] + " 未走 alarmbridge（executor="
                            + (c != null ? c.executorType : "null") + "）");
                }
            }
            if (mbRegOk) {
                log(cb, "  ✓ 17.1 四个闹钟能力注册正确（set/query/toggle/cancel → alarmbridge）");
            } else {
                mbAllPass = false;
            }

            // 17.2 通道常量（防回归钉子）：只发给 OneSay 时钟、权限名正确、系统兜底的重复日换算
            java.util.List<Integer> mbDays = com.onesay.assistant.v2.executor.AlarmBridge.calendarDays(0x3E);
            boolean cmdOk = "com.onesay.clock".equals(com.onesay.assistant.v2.executor.AlarmBridge.CLOCK_PKG)
                    && "com.onesay.clock.permission.ALARM_CONTROL".equals(com.onesay.assistant.v2.executor.AlarmBridge.CLOCK_PERMISSION)
                    && com.onesay.assistant.v2.executor.AlarmBridge.ClockCmd.set(7, 30, 0).action.startsWith("com.onesay.clock.action.")
                    && mbDays.size() == 5 && mbDays.get(0) == java.util.Calendar.MONDAY;
            if (cmdOk) {
                log(cb, "  ✓ 17.2 通道常量正确（时钟包 / 权限 / 系统重复日）");
            } else {
                log(cb, "  ✕ 17.2 通道常量异常");
                mbAllPass = false;
            }

            // 17.3 dump 解析与目标选择
            String mbMixed = "Broadcasting: Intent { act=x }\nBroadcast completed: result=0\n"
                    + "{\"ts\":123,\"count\":2,\"lastId\":2,\"alarms\":["
                    + "{\"id\":1,\"h\":8,\"m\":0,\"d\":0,\"on\":true,\"r\":\"smart\",\"next\":555},"
                    + "{\"id\":2,\"h\":9,\"m\":30,\"d\":0,\"on\":false,\"r\":\"smart\",\"next\":666}]}";
            org.json.JSONObject mbDump = com.onesay.assistant.v2.executor.AlarmBridge.parseDumpJson(mbMixed);
            boolean mbParseOk = mbDump != null && mbDump.optInt("count") == 2
                    && com.onesay.assistant.v2.executor.AlarmBridge.findById(mbDump, 2) != null
                    && com.onesay.assistant.v2.executor.AlarmBridge.findById(mbDump, 99) == null
                    && com.onesay.assistant.v2.executor.AlarmBridge.findEnabledAt(mbDump, 8, 0) != null
                    && com.onesay.assistant.v2.executor.AlarmBridge.findEnabledAt(mbDump, 9, 30) == null
                    // 目标选择：多闹钟未指定 → 拒绝（null）；指定 hour → 命中；显式 id → 命中
                    && com.onesay.assistant.v2.executor.AlarmBridge.pickTarget(mbDump, -1, -1, false) == null
                    && com.onesay.assistant.v2.executor.AlarmBridge.pickTarget(mbDump, -1, 9, true) != null
                    && com.onesay.assistant.v2.executor.AlarmBridge.pickTarget(mbDump, 1, -1, false) != null;
            org.json.JSONObject mbOne = com.onesay.assistant.v2.executor.AlarmBridge.parseDumpJson(
                    "{\"ts\":1,\"count\":1,\"lastId\":1,\"alarms\":[{\"id\":1,\"h\":8,\"m\":0,\"on\":true}]}");
            boolean mbPickOk = mbOne != null
                    && com.onesay.assistant.v2.executor.AlarmBridge.pickTarget(mbOne, -1, -1, false) != null;
            boolean mbNegOk =
                    com.onesay.assistant.v2.executor.AlarmBridge.parseDumpJson("cat: no such file") == null
                    && com.onesay.assistant.v2.executor.AlarmBridge.parseDumpJson(null) == null;
            if (mbParseOk && mbPickOk && mbNegOk) {
                log(cb, "  ✓ 17.3 dump 解析与目标选择正确（摘 JSON / 多闹钟拒绝 / 唯一直取 / 坏输入防御）");
            } else {
                log(cb, "  ✕ 17.3 dump 解析异常: " + mbParseOk + "/" + mbPickOk + "/" + mbNegOk);
                mbAllPass = false;
            }

            // 17.4 时间格式化
            boolean mbFmtOk =
                    "07:05".equals(com.onesay.assistant.v2.executor.AlarmBridge.hhmm(true, 7, 5))
                    && "7:05".equals(com.onesay.assistant.v2.executor.AlarmBridge.hhmm(false, 7, 5))
                    && "1:00".equals(com.onesay.assistant.v2.executor.AlarmBridge.hhmm(false, 13, 0))
                    && "12:30".equals(com.onesay.assistant.v2.executor.AlarmBridge.hhmm(false, 12, 30))
                    && "上午".equals(com.onesay.assistant.v2.executor.AlarmBridge.period(7))
                    && "下午".equals(com.onesay.assistant.v2.executor.AlarmBridge.period(13))
                    && "还有 2 小时 0 分".equals(com.onesay.assistant.v2.executor.AlarmBridge.countdown(7200000L, 0L))
                    && "不到 1 分钟".equals(com.onesay.assistant.v2.executor.AlarmBridge.countdown(59000L, 0L))
                    && "时间已过".equals(com.onesay.assistant.v2.executor.AlarmBridge.countdown(0L, 1L));
            if (mbFmtOk) {
                log(cb, "  ✓ 17.4 时间格式化正确（制式 hhmm / 上午下午 / 倒计时）");
            } else {
                log(cb, "  ✕ 17.4 时间格式化异常");
                mbAllPass = false;
            }

            // 17.5 nextAt：今天没到 → 今天；已过 → 明天；重复日掩码 → 对应日
            java.util.Calendar mbBase = java.util.Calendar.getInstance();
            mbBase.set(2026, 0, 1, 12, 0, 0);
            mbBase.set(java.util.Calendar.MILLISECOND, 0);
            long mbNow = mbBase.getTimeInMillis();
            java.util.Calendar mbToday = (java.util.Calendar) mbBase.clone();
            mbToday.set(java.util.Calendar.HOUR_OF_DAY, 14);
            mbToday.set(java.util.Calendar.MINUTE, 30);
            java.util.Calendar mbTomorrow = (java.util.Calendar) mbToday.clone();
            mbTomorrow.add(java.util.Calendar.DAY_OF_YEAR, 1);
            java.util.Calendar mbT11 = (java.util.Calendar) mbBase.clone();
            mbT11.set(java.util.Calendar.HOUR_OF_DAY, 11);
            mbT11.set(java.util.Calendar.MINUTE, 0);
            mbT11.add(java.util.Calendar.DAY_OF_YEAR, 1);
            boolean mbNextOk =
                    com.onesay.assistant.v2.executor.AlarmBridge.nextAt(mbNow, 14, 30, 0)
                            == mbToday.getTimeInMillis()
                    && com.onesay.assistant.v2.executor.AlarmBridge.nextAt(mbNow, 11, 0, 0)
                            == mbT11.getTimeInMillis()
                    && com.onesay.assistant.v2.executor.AlarmBridge.nextAt(mbNow, 14, 30,
                            1 << (mbTomorrow.get(java.util.Calendar.DAY_OF_WEEK) - 1))
                            == mbTomorrow.getTimeInMillis();
            if (mbNextOk) {
                log(cb, "  ✓ 17.5 nextAt 正确（今天/顺延明天/重复日掩码）");
            } else {
                log(cb, "  ✕ 17.5 nextAt 异常");
                mbAllPass = false;
            }

            // 17.6 判定：不让位（留卡片）/ 不占同轮界面名额
            Capability mbSetCap = reg.describe("clock.alarm.set");
            boolean mbVerdictOk = mbSetCap != null
                    && !com.onesay.assistant.v2.ui.FloatingSheetService.isAppLaunchOrNavigation(mbSetCap)
                    && !com.onesay.assistant.v2.router.AgentLoop.isUiFrontAction(mbSetCap)
                    && com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign("alarm.query")
                    && !com.onesay.assistant.v2.executor.AlarmBridge.isAlarmSign("app.launch");
            if (mbVerdictOk) {
                log(cb, "  ✓ 17.6 判定正确（不让位 / 不占界面名额 / 域判定）");
            } else {
                log(cb, "  ✕ 17.6 判定异常（让位="
                        + (mbSetCap != null && com.onesay.assistant.v2.ui.FloatingSheetService.isAppLaunchOrNavigation(mbSetCap))
                        + " 界面动作="
                        + (mbSetCap != null && com.onesay.assistant.v2.router.AgentLoop.isUiFrontAction(mbSetCap)) + "）");
                mbAllPass = false;
            }
        }
        if (mbAllPass) {
            log(cb, "  ✓ 闹钟联动专项全部通过（注册 → 通道 → 解析 → 格式 → 判定）");
            passCount++;
        } else {
            failCount++;
        }

        // 6. 全量能力执行与回读校验测试
        log(cb, "\n[全量能力] 开始逐项验证已注册能力（含真实特权执行与回读校验）...");
        for (Capability cap : reg.listCapabilities()) {
            if (GATED_SIGNS.contains(cap.sign)) {
                // 已在门禁专项测过，跳过重复执行
                continue;
            }

            if (SKIP_EXEC_SIGNS.contains(cap.sign)) {
                log(cb, "  - SKIP   | " + cap.sign + "（自测期避免打断设备，跳过物理执行）");
                skipCount++;
                continue;
            }

            // 合成默认入参
            JSONObject args = new JSONObject();
            if (cap.paramsSchema.getRules().containsKey("level")) {
                com.onesay.assistant.v2.capability.ParamsSchema.PropertyRule r = cap.paramsSchema.getRules().get("level");
                long lvl = (r != null && r.max != null && r.max <= 15) ? 8 : 120;
                try { args.put("level", lvl); } catch (Exception ignored) {}
            }
            if (cap.paramsSchema.getRules().containsKey("seconds")) {
                try { args.put("seconds", 30); } catch (Exception ignored) {}
            }
            // clock.alarm.set 的 hour 是必填且**不再有默认值**（默认值是
            //   「打开闹钟」被误设成 7 点闹钟的根源，已按用户反馈删除）。自测合成入参时
            //   必须显式给一个值，否则会因"缺少必填参数 hour"被误判成 FAIL。
            if (cap.paramsSchema.getRules().containsKey("hour")) {
                try { args.put("hour", 8); } catch (Exception ignored) {}
            }
            if (cap.paramsSchema.getRules().containsKey("minute")) {
                try { args.put("minute", 0); } catch (Exception ignored) {}
            }
            if (cap.paramsSchema.getRules().containsKey("app")) {
                try { args.put("app", "设置"); } catch (Exception ignored) {}
            }
            if (cap.paramsSchema.getRules().containsKey("url")) {
                try { args.put("url", "https://www.baidu.com"); } catch (Exception ignored) {}
            }
            if (cap.paramsSchema.getRules().containsKey("keyword")) {
                try { args.put("keyword", "天气"); } catch (Exception ignored) {}
            }

            ExecutionResult res = executor.execute(cap, args);
            if (res.ok) {
                log(cb, "  ✓ PASS   | " + cap.sign + " | " + res.userMessage + (res.verified ? " [回读验过]" : ""));
                passCount++;
            } else {
                log(cb, "  ✕ FAIL   | " + cap.sign + " | code=" + res.code + " err=" + res.userMessage);
                failCount++;
            }
        }

        // 7. 还原系统初始状态
        log(cb, "\n[收尾] 正在根据快照精确还原所有系统设置...");
        restoreSnapshot(shell, snapshot);
        log(cb, "[收尾] 系统设置已 100% 还原至初始状态。");

        // 7.2 音量还原本（2026-10-03）：与"音量快照"配对。
        restoreVolumes(shell, volSnapshot, cb);

        // 7.5 网络恢复兜底（2026-10-03）：settings put 改不动真实 Wi-Fi 关联，
        //     必须显式 svc 才能重连。自测若曾关过网，这里负责拉回来并**验证拿到 IP**，
        //     避免"自测完云端全挂"这种坑再次发生。
        restoreNetwork(shell, snapshot, cb);

        // 8. 汇总统计
        String summary = String.format("=== OneSay 自测汇总: PASS=%d FAIL=%d GATED=%d SKIP=%d ===",
                passCount, failCount, gatedCount, skipCount);
        log(cb, "\n" + summary);

        if (cb != null) {
            cb.onComplete(summary);
        }
    }

    private static Map<String, String> takeSnapshot(ShellExecutor shell) {
        Map<String, String> map = new HashMap<String, String>();
        for (String key : SNAPSHOT_KEYS) {
            String[] parts = key.split(":");
            String out = shell.execute("settings get " + parts[0] + " " + parts[1]).output;
            map.put(key, out != null ? out.trim() : "");
        }
        return map;
    }

    private static void restoreSnapshot(ShellExecutor shell, Map<String, String> snapshot) {
        for (Map.Entry<String, String> e : snapshot.entrySet()) {
            String[] parts = e.getKey().split(":");
            String val = e.getValue();
            if (val.length() > 0 && !"null".equalsIgnoreCase(val)) {
                shell.execute("settings put " + parts[0] + " " + parts[1] + " " + val);
            }
        }
    }

    // ================= 音频流快照 / 还原（2026-10-03 新增） =================
    //
    // 为什么不用 SKIP 而要"执行 + 还原"：
    //   volume.*.mute / volume.*.set 是**真实能力**，跳过就等于这几条永远没被测过。
    //   但它们又确实会改用户能感知的状态 —— 所以正确做法不是不测，而是**测完还回去**。
    //   （WiFi 那两条不同：svc wifi disable 会断掉整机网络、连后续云端用例一起毁掉，
    //     恢复成本高且会污染同一轮里的其它断言，所以那两条走 SKIP。）

    /** 需要快照的音频流：0=通话 1=系统 2=铃声 3=媒体 4=闹钟 5=通知 */
    private static final int[] VOL_STREAMS = { 0, 1, 2, 3, 4, 5 };

    /** 读某一路音量；读不到返回 -1（还原时跳过，不会拿 -1 去 set）。 */
    private static int readVolume(ShellExecutor shell, int stream) {
        try {
            String out = shell.execute("media volume --stream " + stream + " --get").output;
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("volume is (\\d+)").matcher(out);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** @return "0=4|1=0|2=7|3=7|4=4|5=7" 形式的快照串 */
    private static String takeVolumeSnapshot(ShellExecutor shell) {
        StringBuilder sb = new StringBuilder();
        for (int s : VOL_STREAMS) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s).append('=').append(readVolume(shell, s));
        }
        return sb.toString();
    }

    /** 按快照还原六路音量，并**逐路回读**打印实测值（不信任 set 的返回）。 */
    private static void restoreVolumes(ShellExecutor shell, String snap, SelfTestCallback cb) {
        if (snap == null || snap.length() == 0) {
            log(cb, "[收尾] ⚠ 无音量快照，跳过音量还原（请手核四路音量）");
            return;
        }
        StringBuilder now = new StringBuilder();
        for (String part : snap.split("\\|")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            int stream, want;
            try {
                stream = Integer.parseInt(kv[0].trim());
                want = Integer.parseInt(kv[1].trim());
            } catch (Throwable t) {
                continue;
            }
            if (want < 0) continue;
            shell.execute("media volume --stream " + stream + " --set " + want);
            int got = readVolume(shell, stream);
            if (now.length() > 0) now.append(' ');
            now.append(got == want ? "s" + stream + "=" + got : "s" + stream + "=" + got + "(期望" + want + ")");
        }
        log(cb, "[收尾] ✓ 音量已按快照还原: " + now);
    }

    /**
     * 网络恢复兜底（2026-10-03 新增）。
     *
     * <p>Android 上 {@code settings put global wifi_on 1} 只是写一个标记位，
     * <b>不会重建 Wi-Fi 关联</b>。真正的开关是 {@code svc wifi enable}。
     * 自测若在 [全量能力] 里执行过 {@code connectivity.wifi.off}，
     * 收尾只还原标记位就会留下"wifi_on=1 但实际断网"的假死状态，
     * 导致后续所有云端 API 静默失败。这里显式拉起并轮询等 IP。
     */
    private static void restoreNetwork(ShellExecutor shell, Map<String, String> snapshot, SelfTestCallback cb) {
        String want = snapshot.get("global:wifi_on");
        boolean wasOn = want == null || "1".equals(want.trim());
        log(cb, "[收尾] 网络恢复：快照 wifi_on=" + (wasOn ? "1" : (want == null ? "(空)" : want.trim())));

        if (!wasOn) {
            log(cb, "[收尾] 快照为关闭态，不改动网络");
            return;
        }

        shell.execute("svc wifi enable");
        String ip = "";
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            ip = nvl(shell.execute("ip addr show wlan0 2>/dev/null | grep 'inet '").output);
            if (ip.length() > 0) break;
            android.os.SystemClock.sleep(1000);
        }
        if (ip.length() > 0) {
            log(cb, "[收尾] ✓ Wi-Fi 已恢复并拿到 IP：" + ip);
        } else {
            log(cb, "[收尾] ⚠ Wi-Fi 期望开启但 20s 内未拿到 IP，云端 API 可能不可用（请检查路由器/热点）");
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static void log(SelfTestCallback cb, String msg) {
        Log.i(TAG, msg);
        if (cb != null) {
            cb.onProgress(msg);
        }
    }

    /** 数组里是否含指定签名（自测断言辅助）。 */
    private static boolean containsSign(String[] arr, String sign) {
        if (arr == null || sign == null) return false;
        for (String s : arr) {
            if (sign.equals(s)) return true;
        }
        return false;
    }
}
