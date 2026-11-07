package com.onesay.assistant.v2.wake;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * ★唤醒调参中心（阈值外置）。
 *
 * <h3>为什么需要</h3>
 * 对标魅族 Aicy / 高通方案的结论：成熟方案把唤醒灵敏度做成了
 * <b>运行时热调参数</b>（{@code persist.wakeup.1st_stage_keyword_threshold} 等 6 个系统属性），
 * 改灵敏度不用重编译、不用重启。
 *
 * <p>而本工程此前的调参是<b>编译期常量</b>（{@code KWS_AGC_TARGET_PEAK}、
 * {@code WAKE_VP_THRESHOLD_PCT_DEFAULT} 等散落在 {{@link WakeService}} 各处）——
 * 调一次灵敏度要「改代码 → javac → d8 → apk → adb install」，一轮 10 分钟。
 * 做唤醒拟合需要反复试参数，这个成本是致命的。
 *
 * <h3>用法</h3>
 * <pre>
 *   读取（代码内）：WakeTuning.get(context).vpThresholdPct()
 *   修改（真机探针，仅 debug 包）：am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --es wakecfg "vp_pct=45"
 *   查看（真机探针，仅 debug 包）：am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --es wakecfg dump
 *   复位：           am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --ez wakecfg_reset true
 * </pre>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>默认值 = 现有实测最优值</b>：外置不改行为，所有默认值等于当前代码里的常量。</li>
 *   <li><b>读取失败一律回落默认值</b>：任何异常都不影响唤醒主链路。</li>
 *   <li><b>只外置"需要反复调"的参数</b>：结构性常量（采样率、帧长、隐私偏移）不外置。</li>
 * </ul>
 */
public final class WakeTuning {

    private static final String TAG = "WakeTuning";
    private static final String PREF = "wake_tuning";

    private static WakeTuning sInst;
    private final SharedPreferences mPrefs;

    private WakeTuning(Context c) {
        mPrefs = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized WakeTuning get(Context c) {
        if (sInst == null) sInst = new WakeTuning(c);
        return sInst;
    }

    // ==================== 参数定义（键名 → 默认值） ====================

    /**
     * KWS 解码加分（keywords_score）—— **全局回落值**。
     *
     * <p>9.0 → <b>7.5</b>。
     *
     * <p><b>为什么是 7.5（而不是 sherpa-onnx 官方默认的 1.0）</b>：
     * 本工程的 {@code assets/kws_keywords.txt} 现在每条关键词都带行内
     * {@code :<boost> #<threshold>}（主路径 9.0/0.05、弱读 8.0/0.08、
     * 元音变体 7.5/0.12、中文兜底 7.0/0.25）。sherpa-onnx 会优先使用行内值，
     * <b>本全局值只对"未标注行内参数的条目"生效</b>。
     *
     * <p>★★ 关键实测（2026-10-05 离线复算）：<b>行内 boost 是替代全局值，不是叠加</b>，
     * 且 boost 的有效区间极窄 —— 1.0/3.0/5.0 命中 0/8，7.5 与 9.0 命中 1/8，
     * 12.0/15.0 反而回落 0/8（过强会挤掉正确解码路径）。
     * 因此全局回落值<b>必须与行内值同量纲</b>：取有效下界 7.5，
     * 保证任何漏写行内参数的条目仍能工作（而不是拿到官方默认 1.0 那种无效值）。
     *
     * <p>仍然外置可调：{@code --es wakecfg "kws_score=9.0"} —— 用于应急整体放宽。
     */
    public static final String K_KWS_SCORE = "kws_score";
    public static final float DEF_KWS_SCORE = 7.5f;

    /**
     * KWS 分数下限（keywords_threshold）—— 全局回落值，默认 0.05。
     *
     * <p>与 {@link #K_KWS_SCORE} 同理：取历史实测有效值 0.05（官方默认 0.25 过严，
     * 与本模型"需要高 boost 才能在 beam search 存活"的特性不匹配）。
     */
    public static final String K_KWS_THRESHOLD = "kws_threshold";
    public static final float DEF_KWS_THRESHOLD = 0.05f;

    /** 声纹阈值百分比。默认 40（远场包第 2 档）。 */
    public static final String K_VP_PCT = "vp_pct";
    public static final int DEF_VP_PCT = 40;

    /** 声纹"压线通过"下界（cos）。低于它视为身份不可信，不发云端重判。默认 0.55。 */
    public static final String K_VP_WEAK_COS = "vp_weak_cos";
    public static final float DEF_VP_WEAK_COS = 0.55f;

    /** 断句前最少人声帧数（100ms/帧）。默认 4（400ms）。 */
    public static final String K_MIN_SPEECH_FRAMES = "min_speech_frames";
    public static final int DEF_MIN_SPEECH_FRAMES = 4;

    /** 送云音频时长门槛（毫秒）。默认 1000。低于它不送云、不烧配额。 */
    public static final String K_CLOUD_MIN_MS = "cloud_min_ms";
    public static final int DEF_CLOUD_MIN_MS = 1000;

    /** KWS AGC 目标峰值。默认 0.35。 */
    public static final String K_AGC_TARGET = "agc_target";
    public static final float DEF_AGC_TARGET = 0.35f;

    /** 语音端点静音阈值（毫秒）。默认 2000（r2/input：1600 → 2000；也是自适应断句的上限）。 */
    public static final String K_SILENCE_MS = "silence_ms";
    public static final int DEF_SILENCE_MS = 2000;

    // ================= 环境自适应灵敏度 =================

    /**
     * 环境自适应灵敏度总开关。1=开（默认）/ 0=关。
     *
     * <p>关掉后行为**完全等价于此前的固定阈值** —— 零回退保障。
     * 探针：{@code --es wakecfg "env_adapt=0"}
     */
    public static final String K_ENV_ADAPT = "env_adapt";
    public static final int DEF_ENV_ADAPT = 1;

    /**
     * 环境自适应对一阶 KWS 触发阈值（keywords_threshold）的**调整强度**（百分比/每级）。
     *
     * <p>默认 12：噪声每升一级，行内/全局阈值**相对放宽** 12%；
     * 噪声越低则相应收紧。取值理由：分级共 4 档（0~3），相对中档 1 计算，
     * 安静档（0）收紧 ~12%、很吵档（3）放宽 ~24%。
     * <ul>
     *   <li>收紧太多（如 30%+）：安静环境下真人轻声唤醒也可能被挡（得不偿失）。</li>
     *   <li>放宽太多（如 30%+）：嘈杂环境下误唤醒会明显抬头，虽然二阶声纹能兜，
     *       但会显著增加声纹验证线程的负担与"验证中忽略本次"的概率。</li>
     * </ul>
     * 12 是"在噪声里保住召回、又不把二阶压垮"的折中起点，可按实测微调。
     *
     * <p>探针：{@code --es wakecfg "env_adapt_pct=20"}
     */
    public static final String K_ENV_ADAPT_PCT = "env_adapt_pct";
    public static final int DEF_ENV_ADAPT_PCT = 12;

    // ==================== P1-9/P1-10 体验实验开关（默认安全值，需真机验证） ====================

    /** 短提示音（~160ms 合成 earcon，播完 +80ms 即解除抑制；隐私起点改为唤醒+450ms）。默认 0=关（原提示音 start_tone + 应答语音）。 */
    public static final String K_SHORT_CHIME = "short_chime";
    public static final int DEF_SHORT_CHIME = 1;   // ★ r2/sysux（用户 10-29 拍板）：默认「按下即听」—— 约 0.15 s 短提示音 + 边缘光效，唤醒那一刻就收音（R14.1 曾为 0）

    // ==================== ★ r2/sysux（10-29）系统级交互第一阶段 ====================
    /** 「我在」长应答：只在 息屏 + 语音唤醒 时说，边说边听（默认 0=关，设置里可开）。 */
    public static final String K_WAKE_ACK_VOICE = "wake_ack_voice";
    public static final int DEF_WAKE_ACK_VOICE = 0;
    /** 播报结束自动续听（连续对话 v1）。默认 1。 */
    public static final String K_FOLLOWUP = "followup";
    public static final int DEF_FOLLOWUP = 1;
    /** 续听窗长（ms），夹在 4000–6000（ConversationTurnPolicy）。 */
    public static final String K_FOLLOWUP_MS = "followup_ms";
    /** 播报期间压低媒体（1，用户拍板）；0 = 播报时也保持暂停。 */
    public static final String K_FOCUS_DUCK_SPEAK = "focus_duck_speak";
    public static final int DEF_FOCUS_DUCK_SPEAK = 1;
    /** 说话打断播报（保守版能量判据，BargeInDetector）。默认 1。 */
    public static final String K_BARGE_IN = "barge_in";
    public static final int DEF_BARGE_IN = 1;
    /** 打断判据：绝对下限 RMS / 回声倍数（%）/ 连续块数（100 ms/块）。 */
    public static final String K_BARGE_ABS_MIN = "barge_abs_min";
    public static final String K_BARGE_ECHO_PCT = "barge_echo_pct";
    public static final String K_BARGE_FRAMES = "barge_frames";
    static final String K_SYSUX_CHIME_MIGRATED = "sysux_chime_migrated";
    /** ★ r2/sysux P2-2：本 App 是默认助理时，按键 / 唤醒词唤醒顺手向系统要 AssistStructure + 截图（默认 1；0 = 只用 uiautomator）。 */
    public static final String K_ASSIST_CTX = "assist_ctx";
    public static final int DEF_ASSIST_CTX = 1;

    public boolean assistContextOnWake() { return getInt(K_ASSIST_CTX, DEF_ASSIST_CTX) != 0; }

    public boolean wakeAckVoice() { return getInt(K_WAKE_ACK_VOICE, DEF_WAKE_ACK_VOICE) != 0; }
    public boolean followUp() { return getInt(K_FOLLOWUP, DEF_FOLLOWUP) != 0; }
    public int followUpMs() { return ConversationTurnPolicy.followUpMs(getInt(K_FOLLOWUP_MS, ConversationTurnPolicy.DEF_FOLLOWUP_MS)); }
    public boolean focusDuckWhileSpeaking() { return getInt(K_FOCUS_DUCK_SPEAK, DEF_FOCUS_DUCK_SPEAK) != 0; }
    public boolean bargeIn() { return getInt(K_BARGE_IN, DEF_BARGE_IN) != 0; }

    public BargeInDetector bargeInDetector() {
        return new BargeInDetector(getInt(K_BARGE_ABS_MIN, (int) BargeInDetector.DEF_ABS_MIN), BargeInDetector.DEF_FLOOR_RATIO,
                getInt(K_BARGE_ECHO_PCT, Math.round(BargeInDetector.DEF_ECHO_RATIO * 100)) / 100f,
                getInt(K_BARGE_FRAMES, BargeInDetector.DEF_MIN_FRAMES), BargeInDetector.DEF_CALIBRATE_MS);
    }

    /**
     * ★ r2/sysux 一次性迁移：R14.1 迁移在真机 prefs 里写下了显式 short_chime=0，光改默认值不生效 —— 用户 10-29 拍板改为按下即听，
     * 这里撤掉一次（写 1）。之后用户在控制台再关就尊重用户。只迁一次。
     */
    public void migrateSysux() {
        if (getInt(K_SYSUX_CHIME_MIGRATED, 0) != 0) return;
        mPrefs.edit().putInt(K_SHORT_CHIME, 1).putInt(K_SYSUX_CHIME_MIGRATED, 1).apply();
        Log.i(TAG, "[TUNING] r2/sysux 迁移：short_chime=1（按下即听，只迁一次）");
    }
    /** 自适应断句（短句 0.7s / 长句 0.9s 尾静音 + 最短语句保护）。R14 起默认 1=开；0 = 固定 silence_ms。 */
    public static final String K_ADAPTIVE_EP = "adaptive_ep";
    public static final int DEF_ADAPTIVE_EP = 1;   // ★ R14：默认开（非流式兜底路径 700/900 ms 尾静音）
    public static final String K_EP_SHORT_MS = "ep_short_ms";
    public static final int DEF_EP_SHORT_MS = 1200;   // ★ r2/input：700 → 1200（用户：「必须连续说话不能换气」）
    public static final String K_EP_LONG_MS = "ep_long_ms";
    public static final int DEF_EP_LONG_MS = 1800;    // ★ r2/input：900 → 1800（长句里的换气停顿 1–1.5 s 不再切断）
    /** r2/input 之前的默认值：迁移时只撤掉「等于旧默认」的显式值（用户自己调过的尊重用户）。 */
    static final int OLD_DEF_SILENCE_MS = 1600, OLD_DEF_EP_SHORT_MS = 700, OLD_DEF_EP_LONG_MS = 900;

    // ==================== ★ r2/input（2026-10-24）按文字完整度断句（流式主路，UtteranceEndpointer） ====================
    /** 识别文字看起来已说完（问句 / 吗呢吧 / 动词+宾语短指令）时的尾静音。 */
    public static final String K_EP_COMPLETE_MS = "ep_complete_ms";
    /** 看不出来时的尾静音。 */
    public static final String K_EP_NEUTRAL_MS = "ep_neutral_ms";
    /** 以「然后 / 和 / 给 / 把 …」或逗号结尾（没说完）时的尾静音。 */
    public static final String K_EP_INCOMPLETE_MS = "ep_incomplete_ms";
    /** 总开关：1 = 流式分段攒着、按完整度判断说完（默认）；0 = R14 行为（服务端第一段就执行）。 */
    public static final String K_EP_SEMANTIC = "ep_semantic";
    public static final int DEF_EP_SEMANTIC = 1;
    static final String K_R2_EP_MIGRATED = "r2_ep_migrated";

    public boolean semanticEndpoint() { return getInt(K_EP_SEMANTIC, DEF_EP_SEMANTIC) != 0; }

    public UtteranceEndpointer utteranceEndpointer() {
        return new UtteranceEndpointer(getInt(K_EP_COMPLETE_MS, UtteranceEndpointer.DEF_COMPLETE_MS),
                getInt(K_EP_NEUTRAL_MS, UtteranceEndpointer.DEF_NEUTRAL_MS),
                getInt(K_EP_INCOMPLETE_MS, UtteranceEndpointer.DEF_INCOMPLETE_MS));
    }

    /**
     * ★ r2/input 一次性迁移：控制台以前可能把旧默认值（1600 / 700 / 900）写成了显式值，光改默认值不生效 ——
     * 等于旧默认的撤掉（回到新默认），别的值尊重用户。只迁一次。
     */
    public void migrateR2Endpoint() {
        if (getInt(K_R2_EP_MIGRATED, 0) != 0) return;
        SharedPreferences.Editor ed = mPrefs.edit();
        StringBuilder sb = new StringBuilder();
        if (mPrefs.contains(K_SILENCE_MS) && getInt(K_SILENCE_MS, -1) == OLD_DEF_SILENCE_MS) { ed.remove(K_SILENCE_MS); sb.append(" silence_ms"); }
        if (mPrefs.contains(K_EP_SHORT_MS) && getInt(K_EP_SHORT_MS, -1) == OLD_DEF_EP_SHORT_MS) { ed.remove(K_EP_SHORT_MS); sb.append(" ep_short_ms"); }
        if (mPrefs.contains(K_EP_LONG_MS) && getInt(K_EP_LONG_MS, -1) == OLD_DEF_EP_LONG_MS) { ed.remove(K_EP_LONG_MS); sb.append(" ep_long_ms"); }
        ed.putInt(K_R2_EP_MIGRATED, 1).apply();
        Log.i(TAG, "[TUNING] r2 断句迁移：撤掉等于旧默认的显式值:" + (sb.length() == 0 ? " 无" : sb.toString()) + "（只迁一次）");
    }
    public static final String K_EP_SHORT_SPAN_MS = "ep_short_span_ms";
    public static final int DEF_EP_SHORT_SPAN_MS = 1500;
    public static final String K_EP_MIN_UTT_MS = "ep_min_utt_ms";
    public static final int DEF_EP_MIN_UTT_MS = 300;
    /** 面板显示"正在听你说… / 识别中（已说 x.x 秒）"状态。纯文案，默认 1=开。 */
    public static final String K_LIVE_STATE = "live_state";
    public static final int DEF_LIVE_STATE = 1;
    /** 短提示音模式下的隐私起点（唤醒时刻之后多少 ms 才开始缓存上传音频；必须 ≥ WAKE_LOCAL_GUARD_MS=300）。 */
    public static final long SHORT_CHIME_CAPTURE_FROM_MS = 450;

    // ==================== ★ D1（lane-d）按键唤醒「提示音期间预录」 ====================
    //
    // 只在按键唤醒时、从唤醒那一刻（按键之后，绝不往前挪）起预录约 300 ms，剔除提示音回声后接到捕获缓冲最前面。
    // 唤醒词唤醒不受影响。key_preroll=0 → 与 R14 完全一致。详见 KeyPreRoll / ChimeEchoGate /-lane-d.md。

    /** 总开关。默认 1=开；0 = R14 行为（按键唤醒从 +300 ms 起收音）。 */
    public static final String K_KEY_PREROLL = "key_preroll";
    public static final int DEF_KEY_PREROLL = 1;
    /** 预录长度（ms）。只能让预录更短：起点 = max(唤醒时刻, R14 起点 − preroll_ms)。0 = 不预录。 */
    public static final String K_PREROLL_MS = "preroll_ms";
    public static final int DEF_PREROLL_MS = (int) KeyPreRoll.DEFAULT_PRE_ROLL_MS;
    /** 泄漏比（%）：掩码外残差要超过掩码内能量的这个比例才算人声。调大 = 更不容易把提示音当人声，也更容易漏掉轻声。 */
    public static final String K_PREROLL_LEAK_PCT = "preroll_leak_pct";
    public static final int DEF_PREROLL_LEAK_PCT = Math.round(ChimeEchoGate.DEF_LEAK * 100);
    /** 最长连续人声帧（16 ms/帧）不足这个数就整段丢弃。下限 2。 */
    public static final String K_PREROLL_MIN_FRAMES = "preroll_min_frames";
    public static final int DEF_PREROLL_MIN_FRAMES = ChimeEchoGate.DEF_MIN_FRAMES;
    /** 人声帧前后各保留几帧（保辅音起头 / 字尾）。 */
    public static final String K_PREROLL_HANG = "preroll_hang";
    public static final int DEF_PREROLL_HANG = ChimeEchoGate.DEF_HANG;
    /** 残差绝对下限（RMS）。 */
    public static final String K_PREROLL_ABS_MIN = "preroll_abs_min";
    public static final int DEF_PREROLL_ABS_MIN = Math.round(ChimeEchoGate.DEF_ABS_MIN);
    /** 残差相对底噪的倍数（%）。 */
    public static final String K_PREROLL_FLOOR_PCT = "preroll_floor_pct";
    public static final int DEF_PREROLL_FLOOR_PCT = Math.round(ChimeEchoGate.DEF_FLOOR_RATIO * 100);
    /** 保留帧里提示音频带的残留比例（%）。0 = 整段挖掉。 */
    public static final String K_PREROLL_ATTEN_PCT = "preroll_atten_pct";
    public static final int DEF_PREROLL_ATTEN_PCT = Math.round(ChimeEchoGate.DEF_ATTEN * 100);
    /**
     * ★ LB-1（10-23）：窗内够帧放行时，第一块 R14 帧（右侧上下文）里还要有这么多人声续段帧（贴窗末短段的溢出不算）。
     * 提示音起音瞬态到了窗外是 0 帧，真人抢话会接着说。0 = 关掉这条（回到 D-1 的判定），范围 0–8。
     */
    public static final String K_PREROLL_CTX_FRAMES = "preroll_ctx_frames";
    public static final int DEF_PREROLL_CTX_FRAMES = ChimeEchoGate.DEF_CTX_MIN_FRAMES;

    // ★ r2/screenoff-wake：息屏低功耗听 / 命中先亮屏（判定在 ScreenOffPolicy；默认值见 ScreenOffPolicy.Config.DEF_*）
    /** 息屏 KWS 能量前置门总开关（0 = 息屏也逐帧跑 KWS，回到改动前）。 */
    public static final String K_SCREENOFF_GATE = "screenoff_gate";
    /** 息屏人声判据：RMS 绝对下限（16-bit PCM）。 */
    public static final String K_SCREENOFF_ABS_MIN = "screenoff_abs_min";
    /** 息屏人声判据：RMS ≥ 底噪 × pct/100。 */
    public static final String K_SCREENOFF_FLOOR_PCT = "screenoff_floor_pct";
    /** 最后一帧人声之后门再开多久（ms）。 */
    public static final String K_SCREENOFF_HANG_MS = "screenoff_hang_ms";
    /** 开门时补喂 KWS 的预滚长度（ms，≤ 2500）。 */
    public static final String K_SCREENOFF_PREROLL_MS = "screenoff_preroll_ms";
    /** 命中先亮屏：亮屏锁超时（ms）。 */
    public static final String K_SCREEN_WAKE_MS = "screen_wake_ms";
    /** 锁屏时起托底 Activity 让面板浮在锁屏上方（0 = 不起，面板被锁屏挡住、只有语音）。 */
    public static final String K_KEYGUARD_HOST = "keyguard_host";
    /** 托底 Activity 最长停留（ms）。 */
    public static final String K_KEYGUARD_HOST_MAX_MS = "keyguard_host_max_ms";

    public ScreenOffPolicy.Config screenOffConfig() {
        return new ScreenOffPolicy.Config(
                getInt(K_SCREENOFF_GATE, ScreenOffPolicy.Config.DEF_ENABLED) != 0,
                getInt(K_SCREENOFF_ABS_MIN, ScreenOffPolicy.Config.DEF_ABS_MIN),
                getInt(K_SCREENOFF_FLOOR_PCT, ScreenOffPolicy.Config.DEF_FLOOR_PCT),
                getInt(K_SCREENOFF_HANG_MS, ScreenOffPolicy.Config.DEF_HANG_MS),
                getInt(K_SCREENOFF_PREROLL_MS, ScreenOffPolicy.Config.DEF_PREROLL_MS),
                getInt(K_SCREEN_WAKE_MS, ScreenOffPolicy.Config.DEF_SCREEN_WAKE_MS),
                getInt(K_KEYGUARD_HOST, ScreenOffPolicy.Config.DEF_KEYGUARD_HOST) != 0,
                getInt(K_KEYGUARD_HOST_MAX_MS, ScreenOffPolicy.Config.DEF_HOST_MAX_MS));
    }

    public boolean keyPreRoll() { return getInt(K_KEY_PREROLL, DEF_KEY_PREROLL) != 0; }

    public int preRollMs() {
        int v = getInt(K_PREROLL_MS, DEF_PREROLL_MS);
        return v < 0 ? 0 : Math.min(v, DEF_PREROLL_MS);   // 只能更短：上限就是 R14 的按键隐私起点
    }

    /** 按当前提示音模式和 preroll_* 调参构造回声门。 */
    public ChimeEchoGate chimeEchoGate() {
        return ChimeEchoGate.fromParams(shortChime(),
                getInt(K_PREROLL_LEAK_PCT, DEF_PREROLL_LEAK_PCT), getInt(K_PREROLL_MIN_FRAMES, DEF_PREROLL_MIN_FRAMES),
                getInt(K_PREROLL_HANG, DEF_PREROLL_HANG), getInt(K_PREROLL_ABS_MIN, DEF_PREROLL_ABS_MIN),
                getInt(K_PREROLL_FLOOR_PCT, DEF_PREROLL_FLOOR_PCT), getInt(K_PREROLL_ATTEN_PCT, DEF_PREROLL_ATTEN_PCT),
                getInt(K_PREROLL_CTX_FRAMES, DEF_PREROLL_CTX_FRAMES));
    }

    public boolean shortChime() { return getInt(K_SHORT_CHIME, DEF_SHORT_CHIME) != 0; }

    public boolean adaptiveEndpoint() { return getInt(K_ADAPTIVE_EP, DEF_ADAPTIVE_EP) != 0; }

    public boolean liveState() { return getInt(K_LIVE_STATE, DEF_LIVE_STATE) != 0; }

    /** 按当前开关构造断句器（关闭时等价于旧的固定 silence_ms）。 */
    public EndpointDetector endpointDetector() {
        if (!adaptiveEndpoint()) return EndpointDetector.fixed(silenceMs());
        return new EndpointDetector(true, silenceMs(),
                getInt(K_EP_SHORT_MS, DEF_EP_SHORT_MS), getInt(K_EP_LONG_MS, DEF_EP_LONG_MS),
                getInt(K_EP_SHORT_SPAN_MS, DEF_EP_SHORT_SPAN_MS), getInt(K_EP_MIN_UTT_MS, DEF_EP_MIN_UTT_MS));
    }

    /** ★ R14 一次性迁移的标记键。 */
    static final String K_R14_MIGRATED = "r14_migrated";
    /** ★ R14.1 一次性迁移的标记键（提示音改回原版）。 */
    static final String K_R141_CHIME_RESET = "r141_chime_reset";

    /**
     * ★ R14 一次性迁移：自适应断句（真机 wake_tuning.xml 里是控制台写下的显式 0，光改默认值不生效）。
     * R14.1：<b>不再</b>迁移 short_chime（用户 10-15 14:40 觉得短提示音难听，要原来的）。只迁一次。
     */
    public void migrateR14() {
        if (getInt(K_R14_MIGRATED, 0) == 0) {
            mPrefs.edit().putInt(K_ADAPTIVE_EP, 1).putInt(K_R14_MIGRATED, 1).apply();
            Log.i(TAG, "[TUNING] R14 迁移：adaptive_ep=1（只迁一次）");
        }
        if (getInt(K_R141_CHIME_RESET, 0) == 0) {
            // R14 迁移写下的 short_chime=1 撤回成原提示音；之后用户在控制台再打开就尊重用户
            mPrefs.edit().putInt(K_SHORT_CHIME, 0).putInt(K_R141_CHIME_RESET, 1).apply();
            Log.i(TAG, "[TUNING] R14.1 迁移：short_chime=0（回到原提示音，只迁一次）");
        }
    }

    /** 设置页用：写一个 0/1 开关。 */
    public void setFlag(String key, boolean on) {
        mPrefs.edit().putInt(key, on ? 1 : 0).apply();
        Log.i(TAG, "[TUNING] 体验开关 " + key + " = " + (on ? 1 : 0));
    }

    public float kwsScore() {
        return getFloat(K_KWS_SCORE, DEF_KWS_SCORE);
    }

    public float kwsThreshold() {
        return getFloat(K_KWS_THRESHOLD, DEF_KWS_THRESHOLD);
    }

    public int vpThresholdPct() {
        int v = getInt(K_VP_PCT, DEF_VP_PCT);
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public float vpWeakCos() {
        return getFloat(K_VP_WEAK_COS, DEF_VP_WEAK_COS);
    }

    public int minSpeechFrames() {
        int v = getInt(K_MIN_SPEECH_FRAMES, DEF_MIN_SPEECH_FRAMES);
        return v < 1 ? 1 : v;
    }

    public int cloudMinMs() {
        int v = getInt(K_CLOUD_MIN_MS, DEF_CLOUD_MIN_MS);
        return v < 0 ? 0 : v;
    }

    public float agcTargetPeak() {
        return getFloat(K_AGC_TARGET, DEF_AGC_TARGET);
    }

    public int silenceMs() {
        int v = getInt(K_SILENCE_MS, DEF_SILENCE_MS);
        return v < 1 ? 1 : v;
    }

    /** 环境自适应是否开启。 */
    public boolean envAdaptEnabled() {
        return getInt(K_ENV_ADAPT, DEF_ENV_ADAPT) != 0;
    }

    /** 环境自适应调整强度（百分比/每级）。 */
    public int envAdaptPct() {
        int v = getInt(K_ENV_ADAPT_PCT, DEF_ENV_ADAPT_PCT);
        if (v < 0) v = 0;
        if (v > 40) v = 40;   // 上界保护：超过 40% 的调整在任何场景都不合理
        return v;
    }

    // ==================== 写入 / 批量设置 ====================

    /**
     * 批量设置。格式：{@code "k=v,k=v"}，如 {@code "vp_pct=45,kws_score=9.0"}。
     *
     * @return 实际写入的参数条数；-1 表示格式全错。
     */
    public int applyCsv(String csv) {
        if (csv == null || csv.trim().length() == 0) return -1;
        int n = 0;
        String[] parts = csv.split(",");
        SharedPreferences.Editor ed = mPrefs.edit();
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            if (k.length() == 0 || v.length() == 0) continue;
            try {
                if (isFloatKey(k)) {
                    ed.putFloat(k, Float.parseFloat(v));
                } else {
                    ed.putInt(k, Integer.parseInt(v));
                }
                n++;
            } catch (NumberFormatException e) {
                Log.w(TAG, "参数值不是数字，跳过: " + p);
            }
        }
        ed.apply();
        Log.i(TAG, "[TUNING] 已写入 " + n + " 个参数: " + csv);
        return n;
    }

    /** 复位全部调参到默认值。 */
    public void reset() {
        mPrefs.edit().clear().apply();
        Log.i(TAG, "[TUNING] 已复位为默认值");
    }

    /** 导出当前全部生效值（供探针打印 / 日志核对）。 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 唤醒调参当前生效值 =====");
        sb.append("\n  ").append(K_KWS_SCORE).append(" = ").append(kwsScore())
                .append("  (默认 ").append(DEF_KWS_SCORE).append(")");
        sb.append("\n  ").append(K_KWS_THRESHOLD).append(" = ").append(kwsThreshold())
                .append("  (默认 ").append(DEF_KWS_THRESHOLD).append(")");
        sb.append("\n  ").append(K_VP_PCT).append(" = ").append(vpThresholdPct())
                .append("  (默认 ").append(DEF_VP_PCT).append(")");
        sb.append("\n  ").append(K_VP_WEAK_COS).append(" = ").append(vpWeakCos())
                .append("  (默认 ").append(DEF_VP_WEAK_COS).append(")");
        sb.append("\n  ").append(K_MIN_SPEECH_FRAMES).append(" = ").append(minSpeechFrames())
                .append("  (默认 ").append(DEF_MIN_SPEECH_FRAMES).append(")");
        sb.append("\n  ").append(K_CLOUD_MIN_MS).append(" = ").append(cloudMinMs())
                .append("  (默认 ").append(DEF_CLOUD_MIN_MS).append(")");
        sb.append("\n  ").append(K_AGC_TARGET).append(" = ").append(agcTargetPeak())
                .append("  (默认 ").append(DEF_AGC_TARGET).append(")");
        sb.append("\n  ").append(K_SILENCE_MS).append(" = ").append(silenceMs())
                .append("  (默认 ").append(DEF_SILENCE_MS).append(")");
        sb.append("\n  ").append(K_ENV_ADAPT).append(" = ").append(envAdaptEnabled() ? 1 : 0)
                .append("  (默认 ").append(DEF_ENV_ADAPT).append(", 环境自适应开关)");
        sb.append("\n  ").append(K_ENV_ADAPT_PCT).append(" = ").append(envAdaptPct())
                .append("  (默认 ").append(DEF_ENV_ADAPT_PCT).append("%, 每级调整强度)");
        sb.append("\n  ").append(K_SHORT_CHIME).append(" = ").append(shortChime() ? 1 : 0)
                .append("  (默认 ").append(DEF_SHORT_CHIME).append(", 按下即听：短提示音 + 唤醒即收音)");
        sb.append("\n  ").append(K_WAKE_ACK_VOICE).append(" = ").append(wakeAckVoice() ? 1 : 0)
                .append("  (默认 ").append(DEF_WAKE_ACK_VOICE).append(", 息屏语音唤醒时说「我在」，边说边听)");
        sb.append("\n  ").append(K_FOLLOWUP).append("/").append(K_FOLLOWUP_MS).append(" = ").append(followUp() ? 1 : 0)
                .append("/").append(followUpMs()).append("  (默认 ").append(DEF_FOLLOWUP).append("/").append(ConversationTurnPolicy.DEF_FOLLOWUP_MS)
                .append(", 播报后自动续听)");
        sb.append("\n  ").append(K_FOCUS_DUCK_SPEAK).append(" = ").append(focusDuckWhileSpeaking() ? 1 : 0)
                .append("  (默认 ").append(DEF_FOCUS_DUCK_SPEAK).append(", 收音暂停媒体、播报压低)");
        sb.append("\n  ").append(K_BARGE_IN).append(" = ").append(bargeIn() ? 1 : 0)
                .append("  (默认 ").append(DEF_BARGE_IN).append(", 说话打断播报 abs/echo%/frames=")
                .append(getInt(K_BARGE_ABS_MIN, (int) BargeInDetector.DEF_ABS_MIN)).append("/")
                .append(getInt(K_BARGE_ECHO_PCT, Math.round(BargeInDetector.DEF_ECHO_RATIO * 100))).append("/")
                .append(getInt(K_BARGE_FRAMES, BargeInDetector.DEF_MIN_FRAMES)).append(")");
        sb.append("\n  ").append(K_ADAPTIVE_EP).append(" = ").append(adaptiveEndpoint() ? 1 : 0)
                .append("  (默认 ").append(DEF_ADAPTIVE_EP).append(", 自适应断句 ")
                .append(getInt(K_EP_SHORT_MS, DEF_EP_SHORT_MS)).append("/")
                .append(getInt(K_EP_LONG_MS, DEF_EP_LONG_MS)).append("ms)");
        sb.append("\n  ").append(K_LIVE_STATE).append(" = ").append(liveState() ? 1 : 0)
                .append("  (默认 ").append(DEF_LIVE_STATE).append(", 聆听/识别状态文案)");
        sb.append("\n  ").append(K_KEY_PREROLL).append(" = ").append(keyPreRoll() ? 1 : 0)
                .append("  (默认 ").append(DEF_KEY_PREROLL).append(", 按键唤醒提示音期间预录 ").append(preRollMs()).append("ms；0=R14)");
        sb.append("\n  preroll_leak_pct/min_frames/hang/abs_min/floor_pct/atten_pct/ctx_frames = ")
                .append(getInt(K_PREROLL_LEAK_PCT, DEF_PREROLL_LEAK_PCT)).append("/")
                .append(getInt(K_PREROLL_MIN_FRAMES, DEF_PREROLL_MIN_FRAMES)).append("/")
                .append(getInt(K_PREROLL_HANG, DEF_PREROLL_HANG)).append("/")
                .append(getInt(K_PREROLL_ABS_MIN, DEF_PREROLL_ABS_MIN)).append("/")
                .append(getInt(K_PREROLL_FLOOR_PCT, DEF_PREROLL_FLOOR_PCT)).append("/")
                .append(getInt(K_PREROLL_ATTEN_PCT, DEF_PREROLL_ATTEN_PCT)).append("/")
                .append(getInt(K_PREROLL_CTX_FRAMES, DEF_PREROLL_CTX_FRAMES))
                .append("  (默认 ").append(DEF_PREROLL_LEAK_PCT).append("/").append(DEF_PREROLL_MIN_FRAMES).append("/")
                .append(DEF_PREROLL_HANG).append("/").append(DEF_PREROLL_ABS_MIN).append("/")
                .append(DEF_PREROLL_FLOOR_PCT).append("/").append(DEF_PREROLL_ATTEN_PCT).append("/").append(DEF_PREROLL_CTX_FRAMES).append(")");
        sb.append("\n  screenoff_* = ").append(screenOffConfig())
                .append("  (默认 gate=1 abs=").append(ScreenOffPolicy.Config.DEF_ABS_MIN)
                .append(" floor_pct=").append(ScreenOffPolicy.Config.DEF_FLOOR_PCT)
                .append(" hang=").append(ScreenOffPolicy.Config.DEF_HANG_MS)
                .append(" preroll=").append(ScreenOffPolicy.Config.DEF_PREROLL_MS)
                .append(" screen_wake_ms=").append(ScreenOffPolicy.Config.DEF_SCREEN_WAKE_MS)
                .append(" keyguard_host=1/").append(ScreenOffPolicy.Config.DEF_HOST_MAX_MS).append(")");
        sb.append("\n==============================");
        return sb.toString();
    }

    // ==================== 内部 ====================

    private boolean isFloatKey(String k) {
        return K_KWS_SCORE.equals(k) || K_KWS_THRESHOLD.equals(k)
                || K_VP_WEAK_COS.equals(k) || K_AGC_TARGET.equals(k);
    }

    private int getInt(String k, int def) {
        try {
            return mPrefs.getInt(k, def);
        } catch (Throwable t) {
            Log.w(TAG, "读取 " + k + " 失败，回落默认 " + def + ": " + t.getMessage());
            return def;
        }
    }

    private float getFloat(String k, float def) {
        try {
            // 兼容"先被写成 int、后又按 float 读"的情况（applyCsv 的类型由键名决定，正常不会发生）
            return mPrefs.getFloat(k, def);
        } catch (ClassCastException e) {
            try {
                int iv = mPrefs.getInt(k, (int) def);
                Log.w(TAG, "键 " + k + " 类型不符（int），按 int 读取: " + iv);
                return (float) iv;
            } catch (Throwable t2) {
                Log.w(TAG, "读取 " + k + " 失败，回落默认 " + def);
                return def;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 " + k + " 失败，回落默认 " + def + ": " + t.getMessage());
            return def;
        }
    }
}
