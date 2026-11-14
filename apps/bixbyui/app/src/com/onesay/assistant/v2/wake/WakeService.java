package com.onesay.assistant.v2.wake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.ui.FloatingSheetService;


import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * OneSay 核心常驻唤醒与语音识别调度服务（审计 G1 10-23 按现状重写类注释；旧版写的 Vosk 多候选 / pickBestAlternative 早已下线）。
 *
 * <ul>
 *   <li><b>唤醒</b>：本地 sherpa-onnx KWS（+ 可选声纹二阶确认），零网络；物理 Bixby 键经 FileObserver 监听
 *       {@code files/bixby.trigger}（{@link KeyTrigger}：down / press / long / up）。所有唤醒汇到 {@code requestWakeInternal}：
 *       第一行简报闸门 {@link WakeAdmission}，然后暂停、冷启动排队、声纹注册互斥、去抖。长按 / 按住走 {@code dispatchKeyGesture}。</li>
 *   <li><b>捕获窗</b>：{@code armCaptureWindow} 开窗，隐私起点 {@code mCaptureFrom} 恒晚于唤醒时刻（{@code WAKE_LOCAL_GUARD_MS} 断言兜底）；
 *       按键唤醒另有 D 线提示音期间预录（{@link KeyPreRoll} + {@link ChimeEchoGate}，起点不早于唤醒时刻）。
 *       能量门断句（{@link EndpointDetector}），人还在说话时续期，整段上限 {@link #CAPTURE_MAX_MS}。</li>
 *   <li><b>识别</b>：全部走云端——R14 流式（{@code RealtimeAsrSession}，按下即预建）优先，失败回退非流式（{@code AsrRouter}）；
 *       代际号 {@code mCaptureGen} 丢弃跨窗 / HIDE 后的迟到结果。</li>
 *   <li><b>提示音</b>：异步 MediaPlayer，播放期 Acoustic Shield 不喂 KWS / 能量门。</li>
 * </ul>
 */
public class WakeService extends Service {

    private static final String TAG = "WakeService";
    private static final String CHANNEL_ID = "bixby_wake_channel";
    private static final int NOTIF_ID = 2026;

    private static final String ASSET_KWS = "kws_model.zip";
    private static final String KWS_VER = "kws-v2-2026-10-01";
    /**
     * 覆盖 zip 内的 keywords.txt —— 改词表不必重打包模型。
     * <p>起因：实测确认「嗨比西比」这类中文谐音在本 KWS 模型上命中率极低
     * （判过命中的 5 段音频离线复跑只中 1/5），而英文音素是模型原生支持、
     * 真机实测"很准"。词表要从中文为主改为英文为主，且以后还可能再调，
     * 所以把它从 zip 里提出来单独放。
     */
    private static final String ASSET_KWS_KEYWORDS = "kws_keywords.txt";
    /**
     * 关键词表版本戳。
     * <p>递增此值 → 下次启动会把 {@link #ASSET_KWS_KEYWORDS} 重新落盘到
     * {@code files/kws/keywords.txt}，覆盖 zip 内的旧版。
     * <p>改动历史：
     * <ul>
     *   <li>1 — 原始 4 条（HEY_BIXBY/HI_BIXBY + 嗨比西比/比西比）</li>
     *   <li>2 — 2026-10-04 英文为主：+4 条英文变体（弱读 / 元音），中文降为兜底</li>
     *   <li>3 — 修正：v2 的词表带了 `#` 注释，sherpa-onnx 解析器不跳过注释行会崩
     *       （实测 `invalid stof argument`）；v3 起落盘前过滤注释/空行</li>
     * </ul>
     */
    private static final String KWS_KEYWORDS_VER = "6-onesay";   // 开源版：用户自定义唤醒词，实际戳 = 本值 + 词表哈希（WakeWords.stamp）

    public static final long CAPTURE_MS = 8000;        // 8 秒自然交互指令窗（语音唤醒起步值）
    /**
     * 按键 / 长按唤醒的起步窗更长。
     *
     * <p>对齐的是"长按之后麦克风**一直亮着**等你说话"这个已被验证的交互语义
     * （某系统级助手的实测差异：热词链路停顿约 1 秒就关麦，长按链路则一直等）。
     * 用户长按是有明确意图的，不该和"随口喊一声"共用同一个 8 秒。
     */
    public static final long CAPTURE_MS_KEY = 12000;
    /** 判定"还在说话"后，每次把结束边界往后挪这么多。 */
    public static final long CAPTURE_EXTEND_STEP_MS = 4000;
    /**
     * 整段窗（首窗 + 全部续期）的硬上限。
     * 到顶就正常收窗 —— 保证"续期"绝不会退化成"麦克风一直开着"。
     */
    public static final long CAPTURE_MAX_MS = 30000;   // ★ r2/input：20 → 30 s（「总时长上限适当放宽」；PcmWindowBuffer 同步）
    /** 多久之内听见人声，才算"人还在说话"（据此决定要不要续期）。 */
    public static final long RENEW_IF_SPEECH_WITHIN_MS = 2500;
    /** ★ 上传窗起点相对唤醒时刻的固定偏移（**隐私边界，不可改**）。 */
    public static final long CAPTURE_FROM_OFFSET_MS = 1100;

    /**
     * 必须累计听到这么多人声帧（100ms/帧）才允许断句。
     *
     * <p>为什么需要：断句判据原本只看"距上次人声 &gt; 1.6s"。但 Acoustic Shield 解除
     * 抑制的瞬间、或窗刚开的瞬间，能量门里没有任何"听过人声"的证据 —— 此时若起点
     * 对齐稍有偏差，就会在**根本没听到用户说话**的情况下判"说完了"，把近空音频送云端。
     * 云端对近空音频会产生幻觉（实测回吐热词表原文 440 字）。
     *
     * <p>取 4 帧（400ms）：正常语速下"打开浏览器"这类最短指令也远超 400ms；
     * 而环境噪声/键盘声达不到连续 4 帧超硬门限，天然被挡在外面。
     */
    private static final int MIN_SPEECH_FRAMES = 4;

    private static final int CHUNK_SAMPLES = 1600;     // 100ms @ 16kHz
    /**
     * 采集采样率。全景一致：AudioRecord 采集 / vosk / sherpa-onnx KWS /
     * 云端一句话识别全部用 16kHz 单声道 PCM16 —— 所以上云时**不需要任何重采样**。
     */
    private static final int SAMPLE_RATE = 16000;

    /**
     * ★ 缺陷修复（2026-10-02）：一次按键只允许唤醒一次。
     *
     * 背景（设备实测日志为证）：同一次 Bixby 按键会经**两条互不知情的通道**抵达本服务：
     *   ① hijack_daemon.sh 写 bixby.trigger 文件 → FileObserver（毫秒级快路径）
     *   ② 同一脚本 0.65s 后做兜底 launch WebViewUiActivity → requestWake(SRC_KEY_ACTIVITY)（慢路径）
     * 两条路径此前各自独立地「开窗 + 播应答音 + 呼出面板」，用户体感就是
     * 「按一次响两声、面板闪两下」。现在两条路径全部汇入唯一入口 requestWake()，
     * 落在去抖窗内的第二次请求直接丢弃。
     *
     * 去抖窗取值依据：daemon 的兜底检查是 CHECK_AFTER=0.7s、长按判定是 LONG_SLEEP=0.65s，
     * 即同一次按键的两条通道间隔不会超过约 1s，1500ms 足够覆盖且不会误伤用户连按。
     */
    private static final long WAKE_DEDUP_MS = 1500;

    // ================= 远场包 =================
    //
    // 用户实测"远距离唤醒还是不太理想"。远场的物理事实：声压随距离衰减，
    // 3~5 米的说话在麦克风上可能只剩满幅的 2%~5% —— 而 KWS 神经网的训练分布
    // 是"正常近场电平"，输入低 20dB 级别时判别力断崖下降（"喊了没反应"的根因之一）。
    //
    // 对策（"远场包"三旋钮，彼此呼应）：
    //   ① KWS 解码加分 keywords_score 6.0 → 7.5（含糊/小声更容易被判成关键词）；
    //   ② 喂给 KWS 的自适应输入增益（本常量区，把低电平帧拉到模型工作区）；
    //   ③ 声纹兜底阈值 45% → 40%（远场 cos 天然偏低，给真主人留余量）。
    // 误报由既有的两道下游防线拦截：能量门（原始信号判定）与声纹兜底（是不是主人）。

    /**
     * KWS 自适应输入增益的目标峰值（≈ -9dBFS）。
     * 低于它的帧会被放大到这个电平；已达标（近场大声）的帧不动，天然防削波。
     */
    private static final float KWS_AGC_TARGET_PEAK = 0.35f;

    /** 增益上限（+21dB）。远场极端小声时封顶，避免把本底噪声抬成"伪语音"。 */
    private static final float KWS_AGC_MAX_GAIN = 12.0f;

    /**
     * 绝对静音线：帧峰值低于它（≈ -44dBFS，安静房间本底）不做任何增益 ——
     * 纯噪声放大没有意义，只会给 KWS 制造幻觉输入；保持原始电平即可。
     */
    private static final float KWS_AGC_SILENCE_PEAK = 0.006f;

    /**
     * 增益平滑系数（0~1，越大越"快攻"）。
     * 0.5 ≈ 300ms 内跟上目标值：说话起始的过渡段不至于漏配增益，
     * 块边界又不产生突变台阶（KWS 按 100ms 帧吃数据）。
     */
    private static final float KWS_AGC_SMOOTH = 0.5f;

    /** 平滑后的当前增益（仅 micLoop 采集线程读写，无并发问题）。 */
    private float mKwsAgcGain = 1.0f;

    /**
     * 唤醒调参中心（对标高通 {@code persist.wakeup.*} 热调体系）。
     * <p>所有"需要反复试"的灵敏度参数都从这里读，不再散落成编译期常量 ——
     * 调参一轮从「改代码+编译+装机 10 分钟」降到「一条广播 1 秒」。
     */
    private com.onesay.assistant.v2.wake.WakeTuning mTuning;
    /** KWS-AGC 日志采样计数器（每 {@link #KWS_AGC_LOG_EVERY} 帧打一条）。
     *  5 秒一条的心跳把 logcat 环形
     *  缓冲区冲得只剩约 20 分钟历史，真机排查"输入奇怪"时取证窗口被自己吃掉了。
     *  60s 一条足以观察远端增益趋势，又能把历史留存提升一个数量级。 */
    private long mKwsAgcLogCnt = 0L;
    /** AGC 日志采样间隔（帧）。600 帧 × 100ms ≈ 60s。 */
    private static final long KWS_AGC_LOG_EVERY = 600L;

    // ================= 环境自适应灵敏度 =================

    /**
     * 最近一次噪声分级（0=安静 / 1=普通 / 2=嘈杂 / 3=很吵）。
     *
     * <p>由采集线程每 {@link #ENV_LOG_EVERY_FRAMES} 帧从 {@link EnergyGate#getNoiseLevel()}
     * 刷新一次。用途见 {@link #envAcceptBonusSoft()}。</p>
     */
    private volatile int mEnvNoiseLevel = 1;

    /** 噪声分级刷新/日志间隔（帧）。100 帧 × 100ms ≈ 10s。 */
    private static final long ENV_LOG_EVERY_FRAMES = 100L;
    private long mEnvLogCnt = 0L;
    /** 上一次打过的噪声分级，仅用于"变化时才打日志"，避免刷屏。 */
    private int mEnvLastLoggedLevel = -1;

    // ================= 唤醒专项拟合（声纹兜底） =================

    /**
     * 唤醒声纹回溯窗口（毫秒）。
     *
     * <p>取值依据：「嗨比西比」念完约 0.7~1s；KWS 报命中自带几个 blank 帧的收尾延迟
     * （数百 ms），再加上话头停顿 —— 2.2s 能稳稳把"这几个字"圈进来，又不会把更早的
     * 无关人声卷进来。验证耗时 ≈ 2.2s × RTF 0.35 ≈ 0.7s（Note8 实测）。
     *
     * <p>P0-3（对标高通 pre_roll_duration）：
     * 该窗口同时充当"预滚缓冲"的读取长度 —— 成熟方案（魅族/高通）在唤醒命中后
     * 会**回捞唤醒词本身所在的那段音频**，用于二阶声纹确认。我们的 RollingAudioBuffer
     * 就是这套预滚能力的实现，本常量决定回捞多长。
     */
    private static final int WAKE_VP_WINDOW_MS = 2200;

    /**
     * 预滚缓冲容量：比读取窗口留余量，防快照时刚好绕环。
     *
     * <p>2.5s → 3.5s。
     * 对标高通 {@code persist.wakeup.history_buffer_duration}。加长的理由是
     * 两级判定（P0-1）需要更宽的回溯范围：一阶可能比唤醒词晚报几百毫秒，
     * 加上唤醒词本身 1s，2.5s 太紧。3.5s @16kHz×2B = 112KB，内存可忽略。
     */
    private static final int WAKE_VP_BUF_CAPACITY = SAMPLE_RATE * 35 / 10;   // 3.5s

    /*
     * 唤醒声纹兜底阈值 —— 现由 {@link WakeTuning#vpThresholdPct()}（键 {@code vp_pct}，默认 {@link WakeTuning#DEF_VP_PCT}）管理，下面是取值来历。
     *
     * <p>默认 0.45 的来历：声纹注册实测（真人 4 段）本人段间余弦 0.70~0.85、
     * 你 vs TTS（他人）≈ -0.02 —— 0.45 两侧都有大裕量。唤醒场景音频更短（2.2s），
     * 分数会略低于注册段，但仍在安全区内；**实际分布以本参数的运行日志为准**——
     * 每次兜底验证都会打 cos 分数，据此校准（这就是"专项拟合"的数据来源）。
     *
     * <p>（第 2 档远场包）：45 → 40。
     * 远场/轻声音频的 cos 天然低于近场注册段（信噪比差），45 会误杀真主人；
     * 40 依然远高于"他人"的实测区间（|cos| < 0.2），安全裕量充足。
     */

    /** 单次声纹验证最少需要的音频（不足则放弃 —— 与 SpeakerVerifier.MIN_AUDIO_SEC=1.2s 对齐）。 */
    private static final int WAKE_VP_MIN_SAMPLES = SAMPLE_RATE * 6 / 5;   // 1.2s

    /**
     * ★★ 隐私硬闸门（2026-10-02）：唤醒音频**永不出本机**。
     *
     * <p>这条规则不是"目前恰好成立"，而是**代码强制**的三段式保证：
     *
     * <ol>
     *   <li><b>唤醒判定全程本地</b>：唤醒词由本地 sherpa-onnx KWS（{@code kws/encoder.onnx} 那套）
     *       判定，vosk 文本命中也是本地 —— 唤醒路径上不存在任何 HTTP 调用。
     *       唤醒只做一件事：{@code requestWakeInternal()} → 播应答音 + 开捕获窗。</li>
     *   <li><b>缓冲起点恒晚于唤醒时刻</b>：{@code mCaptureFrom = 唤醒时刻 + 1100ms}
     *       （{@link #armCaptureWindow}）。麦克风线程只在 {@code now > mCaptureFrom} 时才
     *       {@code mPcmBuffer.append()}，所以唤醒词本身、以及唤醒前的常听流，
     *       <b>根本没有进入过上传缓冲</b>。</li>
     *   <li><b>发送前再验一次</b>：{@link #tryCloudRecognize} 在真正发请求前断言
     *       {@code mCaptureFrom - mLastWakeAt >= WAKE_LOCAL_GUARD_MS}，
     *       不满足就拒发并打 ERROR。</li>
     * </ol>
     *
     * <p>第 3 条是**防回归**用的：将来任何人想把窗口前移以"顺便把唤醒词也识别一下"
     * （{@link PcmWindowBuffer} 早年的注释里就写过这种念头），都会在这里被直接拦下，
     * 而不是悄悄把用户卧室里的声音传到公网。
     *
     * <p>取值 300ms 是保守余量：正常配置下实际间隔是 1100ms，远大于它；
     * 定这么小是为了只在"起点真的被前移到唤醒词附近"时才触发，不误伤合理的微调。
     */
    private static final long WAKE_LOCAL_GUARD_MS = 300;

    /** 唤醒来源标记（仅用于日志定位是哪条通道进来的） */
    public static final String SRC_KEY_FILE = "key-file";
    public static final String SRC_KEY_ACTIVITY = "key-activity";
    public static final String SRC_KEY_SIM = "key-sim";
    public static final String SRC_VOICE = "voice";
    /** ★ BUG-A1：按键文件通道最近一次事件的守护 seq（0 = 旧守护 / 没有）。 */
    private volatile int mKeySeq;

    private static volatile WakeService sInstance;
    private static volatile boolean sRunning = false;

    // ---------- 冷启动唤醒排队 ----------
    //   旧行为：① requestWake 在服务实例还没建好时直接 return（无日志）；② 语音引擎启动期（KWS 解压/加载、
    //   AudioRecord 还没 startRecording）唤醒照常开捕获窗，但麦克风没在录 → 窗口到点只能「没听清」。
    //   冷启动后第一次按键（WebViewUiActivity 先 startForegroundService 再 requestWake，服务是异步建的）必丢，
    //   紧接着的一次落在引擎加载期也丢 —— 与真机「头两条丢失、重发正常」一致。
    //   现在：麦克风真正开录之前到达的唤醒先排队（只留最新一条 = 同一次按键的两条通道天然去重），
    //   开录后立即补发；超过 COLD_WAKE_TIMEOUT_MS 仍未就绪则丢弃并在面板上说明。排队逻辑见 util.ReadyQueue（JVM 单测）。
    /** 麦克风已进入录制状态（micLoop 就要开始）。 */
    private static volatile boolean sMicReady = false;
    /** 冷启动唤醒最长等待：超过就丢弃（不在用户早就放弃之后突然开麦）。 */
    static final long COLD_WAKE_TIMEOUT_MS = 10000L;
    private static final com.onesay.assistant.v2.util.ReadyQueue<String[]> sColdWakes =
            new com.onesay.assistant.v2.util.ReadyQueue<String[]>(COLD_WAKE_TIMEOUT_MS, 1);
    private static final Handler sColdHandler = new Handler(Looper.getMainLooper());

    /** 麦克风是否已开录（调试驱动 / 冷启动探针用）。 */
    public static boolean isMicReady() {
        return sMicReady;
    }
    private static volatile String sStatus = "未运行";

    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private AudioManager mAudioManager;
    private PowerManager.WakeLock mWakeLock;
    private final Object mWakeLockSync = new Object();
    // ★ r2/screenoff-wake：屏幕 / 锁屏状态与息屏 KWS 能量前置门（判定全在纯 Java 的 ScreenOffPolicy）
    private ScreenOffPolicy mScreenPolicy;
    private android.content.BroadcastReceiver mScreenReceiver;
    /** 命中先亮屏：SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP，带超时自动释放。 */
    private PowerManager.WakeLock mScreenWakeLock;
    /** 暂停开关的进程内缓存（SharedPreferences 监听 + 控制台回调刷新；读循环每帧读它，不碰 prefs）。 */
    private volatile boolean mPausedCached;
    /** ★ r2/sysux P1-1：本窗的提示音 / 起点 / 预录决定（armCaptureWindow 里定）。 */
    private volatile WakePromptPolicy.Plan mPromptPlan;
    /** ★ r2/sysux P1-1：唤醒那一刻屏幕是否亮着（「我在」只在息屏语音唤醒时说）。 */
    private volatile boolean mScreenOnAtWake = true;
    /** ★ r2/sysux P1-2：整轮对话的音频焦点状态机。 */
    private AssistAudioFocus mFocus = new AssistAudioFocus(true);
    private AudioManager.OnAudioFocusChangeListener mFocusListener;
    private android.media.AudioFocusRequest mFocusReq;
    /** ★ r2/sysux P1-3：当前捕获窗是播报后的续听窗（没人说 → 安静收起，不报「没听清」）。 */
    private volatile boolean mFollowUpActive;
    /** ★ r2/sysux P1-3：说话打断播报。 */
    private volatile BargeInDetector mBargeIn = new BargeInDetector();
    /** ★ r2/sysux P1-3：语音指令 → FloatingSheetService.onSubmitCommand 认领（认领到 = 语音回合）。 */
    private static volatile String sPendingVoiceCommand;
    /** ★ r2/sysux R1：麦克风是否真的在录（PARTIAL 唤醒锁的前提）。 */
    private final MicRecordingState mMicState = new MicRecordingState();
    private final Object mPauseLock = new Object();
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener mPauseListener;
    private long mScreenGateLogAt;
    private FileObserver mTriggerObserver;
    private AudioRecord mAudioRecord;

    private com.k2fsa.sherpa.onnx.KeywordSpotter mKws;
    private com.k2fsa.sherpa.onnx.OnlineStream mKwsStream;

    private EnergyGate mEnergyGate;

    /**
     * 云端 ASR 调度（阿里云一句话识别）。
     * ★ 框架默认**关闭**（{@code AliyunAsrConfig.isEnabled()==false}），
     * 未配置 key 时整条链路的行为与改造前**完全一致** —— 云端一次都不会被访问。
     * 打开方式见 {@code AliyunAsrConfig} 类注释与《阿里云ASR接入指南》。
     */
    private com.onesay.assistant.v2.asr.AsrRouter mAsrRouter;
    /** 捕获窗内攒下的 PCM，供云端整段识别（与 vosk 流式识别并行，互不干扰） */
    private com.onesay.assistant.v2.asr.PcmWindowBuffer mPcmBuffer;
    /**
     * App 本体「录制声纹」按钮触发，**无视捕获窗**持续攒 PCM。
     * 用户自己控制节奏（自己按、自己说、自己停）；音频只进本机声纹计算，不经任何网络。
     */
    private volatile boolean mVpRecording = false;
    /** 声纹录制专用缓冲（独立于 mPcmBuffer，避免与云端 ASR 的捕获窗语义互相干扰）。 */
    private com.onesay.assistant.v2.asr.PcmWindowBuffer mVpRecBuffer;

    /**
     * 声纹**注册会话**压制开关（录音功能迁移到 App 本体后的配套）。
     *
     * <p>置 true 的时机 = 用户在 App 里进入引导流程那一刻（含尚未开始录制的 READY 态）；
     * 置 false = 会话结束（完成/取消/离开页面）。
     *
     * <p>为什么必须盖住"整段会话"而不只是"录制中"：引导句本身就是唤醒词
     * （"嗨比西比，现在几点"…）。用户在点「● 开始录制」之前跟着界面先念一遍，
     * 或录制间隙的复述，都会命中 KWS 并把唤醒面板弹出来 —— 面板一弹，
     * 用户视角就是"我录个声纹，它却一直跳出来捣乱"。
     * 会话期间整体静默，比只静默"录制中"更符合用户预期。
     */
    private volatile boolean mEnrollSession = false;

    /**
     * 最近 ~2.5s 的滚动音频。
     *
     * <p>KWS 报"命中唤醒词"时唤醒词已经说完（收尾延迟），要验证"是不是主人在喊"
     * 就必须能回溯这段音频 —— 本缓冲就是这份回溯能力。micLoop 每帧 append（一次
     * arraycopy，开销微不足道）；验证线程 snapshotLast 取拷贝，两者无锁竞争问题。
     */
    private com.onesay.assistant.v2.wake.RollingAudioBuffer mWakeRollingBuf;

    /**
     * 唤醒声纹兜底验证的并发门：同一时刻只跑一个验证任务。
     * （防短时间多次 KWS 命中把验证线程堆叠起来；算完自动放开。）
     */
    private final java.util.concurrent.atomic.AtomicBoolean mWakeVpBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 本次捕获是否已发起过云端重判（防 VAD 断句与窗超时两条路径重复触发） */
    private volatile boolean mCloudFiredForThisCapture = false;
    /**
     * ★ R13 卡死修复：云端请求<b>真的还在路上</b>（发出置真、本窗回调到达即置假）。旧实现用
     * {@code mCloudFiredForThisCapture && !mGotCommand} 判在途，云端失败后依然为真 → 窗到点又把面板弹成「正在想」且永不收（见 CaptureSessionPolicy）。
     */
    private volatile boolean mCloudInFlight = false;
    /** ★ R13：本窗已收尾（云端失败 / 空结果 / 没网），窗到点的收尾逻辑不再跑。 */
    private volatile boolean mCaptureClosed = false;
    /**
     * 捕获窗"代际号"。每次开窗（{@link #armCaptureWindow}）递增；
     * 云端请求发送时快照、回调时比对 —— 不相等 = 跨窗迟到，直接丢弃
     * （根治"上一窗的迟到结果被新窗执行"的错乱，用户实测 bug：按键后浏览器自动打开）。
     */
    private volatile int mCaptureGen = 0;
    private volatile boolean mAbort = false;
    private volatile boolean mIsPlayingAckSound = false;
    private volatile long mAckSoundEndAt = 0L;
    /**
     * 能量门断句计时起点（= 应答音播完、抑制解除的那一刻，见 playWakeSounds）。
     * 仅供断句日志核对"距计时起点多久"用 —— 正常应 ≥ 1600ms 才可能断句；
     * 若日志里出现"距 arm 仅 N ms 即断句"（N 远小于 1600），说明假断句又回来了。
     */
    private volatile long mArmAt = 0L;

    // ================= ★ R14 流式识别（qwen3-asr-flash-realtime）=================
    /** 当前流式会话（按下键时预建，开窗时绑定到本窗代际）；null = 没有。 */
    private volatile com.onesay.assistant.v2.asr.RealtimeAsrSession mRt;
    /** mRt 绑定的捕获窗代际；-1 = 预建了还没绑定（等松手唤醒）。 */
    private volatile int mRtGen = -1;
    /** 服务端 VAD 说「正在说」（speech_started 置真、speech_stopped 置假）。 */
    private volatile boolean mRtSpeaking;
    /** 本地能量门已断句、在等流式最终结果的时刻（0 = 没在等）。 */
    private volatile long mRtLocalEpAt;

    // ★ r2/input（2026-10-24）流式分段攒着、按文字完整度判断说完（UtteranceEndpointer）
    /** 本窗流式已出的段（主线程写；读循环只读 {@link #mRtFinalsJoined}）。 */
    private final java.util.ArrayList<String> mRtSegments = new java.util.ArrayList<String>();
    private volatile String mRtFinalsJoined = "";
    /** 在途半段（流式中间结果，已剥应答语）。 */
    private volatile String mRtPartialText = "";
    /** 流式最后一次有动静（开始说话 / 中间结果 / 段结束）。 */
    private volatile long mRtActivityAt;
    /** 本窗用不用「分段攒着 + 完整度断句」（开窗时按 ep_semantic 定）。 */
    private volatile boolean mSemanticEp = true;
    private volatile UtteranceEndpointer mUttEp = UtteranceEndpointer.defaults();
    /** 最近一帧裁决用的尾静音（续期判断要比它多等一点，不在用户停顿思考时把窗关了）。 */
    private volatile int mEpRequiredMs = UtteranceEndpointer.DEF_NEUTRAL_MS;
    /** 读循环已经为本窗 post 过提交（防重复 post）。 */
    private volatile int mEpCommitPostedGen = -1;
    private String mEpLogged;
    /** 「再按一次键结束」受理的时刻：同一次按键从另一条通道迟到的唤醒请求要吞掉。 */
    private volatile long mKeyFinishAt;
    /** 本窗流式失败过（回退非流式后不再尝试）。 */
    private volatile int mRtFailedGen = -1;
    private final com.onesay.assistant.v2.asr.RealtimeAsrPolicy.Breaker mRtBreaker = new com.onesay.assistant.v2.asr.RealtimeAsrPolicy.Breaker();
    /** 时延分解（elapsedRealtime）：按下、建连就绪、首个中间结果、本地/服务端断句、出字。 */
    private volatile long mTKeyDown, mTRtReady, mTFirstPartial, mTSpeechEnd, mTCaptureSummaryGen = -1;
    private long mLastTriggerAt = 0L;
    /** ★ R10 D6：长按后继续按住到这个时长再松手 → 随口记。 */
    public static final long HOLD_SUIKOUJI_MS = 2000L;
    /** ★ R10 守护 v8：最近一次「按下」的序号与时刻（elapsedRealtime）。 */
    private volatile int mDownSeq = 0;
    private volatile long mDownAt = 0L;
    private static volatile boolean sV8Seen, sLastLongSilent;
    /** ★ R10 方案 C：长按时掐断正在播的唤醒音（守护 v7 兜底）。 */
    private volatile boolean mAckAbort;
    private volatile MediaPlayer mAckPlayer;
    private long mLastWakeAt = 0L;
    /** 上一次唤醒请求的时间、模式与来源，用于统一入口的幂等去抖 */
    private long mLastWakeReqAt = 0L;
    private boolean mLastWakeReqConversation = false;
    private String mLastWakeSource = "";
    private long mLastNotifAt = 0L;
    /**
     * 本捕获窗是否为「✦ 语音」按钮发起的"继续聆听"会话。
     * 决定收窗文案：继续会话只给轻提示（不动会话正文），新唤醒走完整"没听清"终态。
     */
    private boolean mLastContinueSession = false;
    /**
     * 指令捕获窗的起止时刻。
     * ★ volatile：云端重判的回调在**主线程**写这两个值（闭窗），
     * 而麦克风线程每 100ms 读它们判断是否还在窗内 —— 跨线程可见性必须显式保证，
     * 否则麦克风线程可能继续把音频喂给已关闭的窗。
     */
    private volatile long mCaptureUntil = 0L;
    /** ★ R14.1：KWS 暂停到此刻（按下 / 捕获窗 + 尾巴）。 */
    private volatile long mKwsPauseUntil = 0L;
    private boolean mKwsPausedLogged;
    private volatile long mCaptureFrom = 0L;
    private long mSuppressAsrUntil = 0L;
    private long mSpeechSeenAt = 0L;
    /** ★ volatile：云端重判的回调在主线程置 true，麦克风线程与窗超时 Runnable 都要读 */
    private volatile boolean mGotCommand = false;
    private String mLastPushedAsr = "";
    /** 上一次打印过的 vosk 原始产出（仅用于抑制重复日志） */
    private long mKwsBlockedCount = 0L;

    /**
     * 本次唤醒若走过声纹兜底，记下它当时的 cos 得分与时刻。
     *
     * <p>用途：真机实测"环境语音被当成指令"的根因是 —— 唤醒压线通过（能量门没过、
     * 声纹兜底刚够阈值）时，指令窗吃进了一整段**环境旁白**（实测是一句视频里的
     * "瞬间，河面骤然炸开去了。"），本地离线候选为空，超时后整体送云端重判，
     * 云端老老实实把旁白转出来 → 面板当提问作答。
     *
     * <p>所以"超时闭合 + 空候选"这条重判路径必须带一个身份前提：
     * 只有本次唤醒的声纹得分**明确高于阈值**（≥ {@link #WAKE_VP_WEAK_COS}）才放行；
     * 压线通过的（0.40~0.55）一律不发，直接"没听清"。用户真正对着手机说话时，
     * 声纹得分通常 0.6+，不受影响；环境声/旁人声则被拦在这条线外。
     */
    private volatile float mLastWakeVpCos = -1f;    private volatile long mLastWakeVpAt = 0L;

    /**
     * 把每次"送进声纹"的那段音频落盘 + 打印质量指标。
     *
     * <p>起因（本轮核心疑点）：同一人、同一模板，运行时声纹 cos 在 0.148~0.786 之间剧烈
     * 摆动，而注册质量本身正常（4 段内一致性 0.64）。若阈值/模板有问题，分数应该整体
     * 偏低而不是"忽高忽低"。忽高忽低只有一个解释：**送进声纹的那段音频，每次包含的
     * 有效语音不一样多**。
     *
     * <p>机制推断：KWS 命中的是可变长的词（实测出现过 比自毙/比西比/HI_BIXBY/嗨比自比/
     * 黑必自毙），而回溯窗口是固定的 2200ms。命中晚 → 窗口里大半是静音/噪声 → 有效
     * 语音占比低 → 向量被背景带偏 → cos 掉到 0.1~0.2。命中早 → 窗口正好卡在人声上
     * → cos 0.6~0.8。
     *
     * <p>这个假设必须用真实音频验证，不能靠日志猜。本开关打开后，每段素材写到
     * {@code files/voiceprint/diag/diag_<时间戳>_<kw>_<cos>.wav}（16k/mono/16bit），
     * 同时用 {@code [VP-DIAG]} 标签打印 时长/峰值/RMS/有效帧占比/首末非静音位置。
     * 用 {@code --ez vpdump true} 打开，默认关闭（生产不落盘用户音频）。
     */
    private volatile boolean mDiagnoseVp = false;

    /** 诊断落盘目录（voiceprint/diag），懒创建。 */
    private java.io.File diagDir() {
        java.io.File d = new java.io.File(getFilesDir(), "voiceprint/diag");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /**
     * 把一段即将送进声纹的音频落盘为 wav，并打印能量/有效语音占比指标。
     *
     * <p>写 wav 是给"离线复算"用的：拿到文件后可以在 PC 上跑同一份 onnx 模型，
     * 复现 cos，从而区分"是音频本身差异"还是"模型/代码路径差异"。
     *
     * @param pcm 16k/mono/16bit，即将送声纹的完整片段
     * @param kw  KWS 命中词（用于文件名与日志，可能为 null）
     */
    private void dumpVpDiagnostic(short[] pcm, String kw) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 13：用户音频落盘只在 debug 包
        if (pcm == null || pcm.length == 0) {
            Log.w(TAG, "[VP-DIAG] pcm 为空，跳过");
            return;
        }
        try {
            // 指标：峰值 / RMS / 有效帧（|x| > 300 记一帧，按 160 样本=10ms 一帧）
            int peak = 0;
            long sq = 0;
            int frameLen = 160;
            int frames = pcm.length / frameLen;
            int voiced = 0;
            int firstVoiced = -1;
            int lastVoiced = -1;
            for (int i = 0; i < pcm.length; i++) {
                int v = pcm[i];
                int a = v < 0 ? -v : v;
                if (a > peak) peak = a;
                sq += (long) v * v;
            }
            for (int f = 0; f < frames; f++) {
                boolean isV = false;
                for (int j = 0; j < frameLen; j++) {
                    int a = pcm[f * frameLen + j];
                    if (a < 0) a = -a;
                    if (a > 300) { isV = true; break; }
                }
                if (isV) {
                    voiced++;
                    if (firstVoiced < 0) firstVoiced = f;
                    lastVoiced = f;
                }
            }
            double rms = Math.sqrt((double) sq / pcm.length);
            double voicedPct = frames > 0 ? (voiced * 100.0 / frames) : 0.0;

            String safeKw = kw == null ? "null" : kw.replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5]", "");
            if (safeKw.length() > 12) safeKw = safeKw.substring(0, 12);
            String name = "diag_" + System.currentTimeMillis() + "_" + safeKw + ".wav";
            java.io.File out = new java.io.File(diagDir(), name);
            writeWav16kMono(out, pcm);

            Log.i(TAG, "[VP-DIAG] kw=" + kw
                    + " samples=" + pcm.length
                    + " ms=" + (pcm.length * 1000 / 16000)
                    + " peak=" + peak
                    + " rms=" + String.format("%.0f", rms)
                    + " voicedPct=" + String.format("%.1f", voicedPct)
                    + " firstVoicedMs=" + (firstVoiced < 0 ? -1 : firstVoiced * 10)
                    + " lastVoicedMs=" + (lastVoiced < 0 ? -1 : lastVoiced * 10)
                    + " file=" + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "[VP-DIAG] 落盘失败: " + t);
        }
    }

    /** 写 16k/mono/16bit PCM wav（44 字节标准头）。 */
    private static void writeWav16kMono(java.io.File f, short[] pcm) throws java.io.IOException {
        java.io.DataOutputStream os = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)));
        try {
            int dataLen = pcm.length * 2;
            byte[] hdr = new byte[44];
            writeAscii(hdr, 0, "RIFF");
            writeLe32(hdr, 4, 36 + dataLen);
            writeAscii(hdr, 8, "WAVE");
            writeAscii(hdr, 12, "fmt ");
            writeLe32(hdr, 16, 16);
            writeLe16(hdr, 20, 1);      // PCM
            writeLe16(hdr, 22, 1);      // mono
            writeLe32(hdr, 24, 16000);
            writeLe32(hdr, 28, 32000);  // byte rate
            writeLe16(hdr, 32, 2);      // block align
            writeLe16(hdr, 34, 16);     // bits
            writeAscii(hdr, 36, "data");
            writeLe32(hdr, 40, dataLen);
            os.write(hdr);
            for (int i = 0; i < pcm.length; i++) {
                os.write(pcm[i] & 0xFF);
                os.write((pcm[i] >> 8) & 0xFF);
            }
        } finally {
            os.close();
        }
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /** 供测试探针开启/关闭诊断落盘。 */
    public void setDiagnoseVp(boolean on) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 13：release 打不开声纹音频落盘
        mDiagnoseVp = on;
        Log.i(TAG, "[VP-DIAG] mDiagnoseVp=" + on);
    }

    /** 清空诊断目录，返回删除文件数（供探针调用）。 */
    public int clearVpDiagnostic() {
        int n = 0;
        java.io.File[] fs = diagDir().listFiles();
        if (fs != null) {
            for (java.io.File f : fs) {
                if (f.delete()) n++;
            }
        }
        Log.i(TAG, "[VP-DIAG] 清理 " + n + " 个诊断文件");
        return n;
    }

    /** 声纹"明确通过"分界：低于它视为压线通过，超时空候选重判不发云端。 */
    private static final float WAKE_VP_WEAK_COS = 0.55f;
    /**
     * 是否已就"云端凭据失效"向用户报过警。
     * Key 被撤销/删除后，云端调用会**每一次**都 401 失败，
     *   若只写日志，用户完全不知道——只会觉得"开着云端识别还是不准"。
     *   置为 true 后把前台通知改成显式警告；下一次云端真正命中时再改回正常文案，
     *   避免同一条警告反复刷。仅在主线程读写。
     */
    private boolean mCloudKeyWarned = false;

    // ---------------------------------------------------------------- r2/p0 P0-2：识别失败「重试」
    // 超时 / 限流 / 服务端 / 网络失败时，卡片上点「重试」用同一段录音再识别一次：录音就是本窗的 mPcmBuffer
    // （stop 后内容保留，只有下一次 armCaptureWindow 的 start() 才清空，而那一步会把代际号 +1 → 重试自然作废）。
    // 不另存副本、不落盘、不打日志。只在同一会话、本窗还没执行过指令、本会话还没重试过时有效。
    /** 可重试的会话号（-1 = 没有可重试的）。 */
    private volatile int mRetryGen = -1;
    private volatile int mRetriedGen = -1;
    /** tryCloudRecognize 这一次是「重试」（只在 retryLastCloud 里置位，主线程）。 */
    private boolean mRetryPending;

    /** 面板「重试」芯片入口（主线程）。返回 false = 会话已作废 / 录音不在 / 已重试过 / 没发出去。 */
    public static boolean retryCloudFromUi() {
        WakeService s = sInstance;
        return s != null && s.retryLastCloud();
    }

    private boolean retryLastCloud() {
        if (!com.onesay.assistant.v2.asr.AsrFailure.mayRetry(mPcmBuffer.size() > 0 && !mPcmBuffer.isActive(), mRetryGen, mCaptureGen, mGotCommand, mRetriedGen == mCaptureGen)) {
            Log.i(TAG, "[ASR-FAIL] 重试作废（会话已换 / 本窗已执行 / 录音不在 / 已重试过）");
            clearRetry();
            return false;
        }
        mRetriedGen = mCaptureGen;
        mRetryPending = true;
        mCloudFiredForThisCapture = false;   // 本窗唯一那次送云已用掉且失败了 → 用户点「重试」再给一次（同一段录音，最多一次）
        boolean fired;
        try {
            fired = tryCloudRecognize(null, "用户点重试");
        } finally {
            mRetryPending = false;
        }
        if (!fired) {
            mCloudFiredForThisCapture = true;
            clearRetry();
        }
        Log.i(TAG, "[ASR-FAIL] 用户点重试 → " + (fired ? "已用同一段录音再发一次" : "没发出去"));
        return fired;
    }

    private void clearRetry() {
        mRetryGen = -1;
    }

    /** 语音识别没法用：云端开关没开或凭据不全（本地识别已下线，两者任一 = 说什么都识别不了）。 */
    private boolean asrNotConfigured() {
        com.onesay.assistant.v2.asr.AsrRouter r = mAsrRouter;
        return r != null && (!r.config().isEnabled() || !r.isCloudConfigured());
    }

    /** 识别失败分型卡（会话已作废 → 不上屏，同 notifyCaptureMiss）。 */
    private void showAsrFailure(FloatingSheetService fs, com.onesay.assistant.v2.asr.AsrFailure.Kind kind, int gen, boolean canRetry) {
        if (fs == null) return;
        if (com.onesay.assistant.v2.wake.CaptureSessionPolicy.isStale(gen, mCaptureGen)) {
            Log.i(TAG, "[CAPTURE] 丢弃迟到的识别失败卡（会话 #" + gen + " ≠ 当前 #" + mCaptureGen + "）");
            return;
        }
        fs.showAsrFailure(kind, mLastContinueSession, canRetry);
    }

    public static WakeService getInstance() {
        return sInstance;
    }

    // ---------------------------------------------------------------- 声纹录制（2026-10-04）
    //
    // 面板「录制声纹」按钮的两态入口。用户自己控制节奏（自己按、自己说、自己停），
    // 音频只在本机算 embedding —— 与"唤醒音频永不上行"的隐私红线同源，此处不碰任何网络。
    // 单人语义：面板固定以 "owner" 注册，每次录制覆盖。

    /** 开始录制。返回 false = 服务未就绪（麦克风不可用）。 */
    public static boolean startVoiceprintRecording() {
        WakeService s = sInstance;
        return s != null && s.beginVpRecording();
    }

    /**
     * 停止录制并取走 PCM。
     *
     * @return 16k/mono/16bit LE 字节流；null = 服务未就绪。
     */
    public static byte[] stopVoiceprintRecording() {
        WakeService s = sInstance;
        return (s == null) ? null : s.endVpRecording();
    }

    /** 本次录制已攒的音频时长（毫秒）—— 供面板实时计时显示。未录制时返回 0。 */
    public static long getVoiceprintRecordedMs() {
        WakeService s = sInstance;
        if (s == null || s.mVpRecBuffer == null) {
            return 0L;
        }
        return s.mVpRecBuffer.durationMs();
    }

    /** 停止录制并丢弃数据（面板收起时的清理路径 —— 防麦克风数据继续累积）。 */
    public static void abortVoiceprintRecording() {
        WakeService s = sInstance;
        if (s != null) {
            s.endVpRecording();
        }
    }

    /**
     * 声纹注册「会话」压制开关的静态入口（App 本体引导流程专用）。
     * 进入引导 = true（KWS 命中也一律不开窗）；会话结束/中止 = false。
     */
    public static void setEnrollSession(boolean on) {
        WakeService s = sInstance;
        if (s != null) {
            s.mEnrollSession = on;
            s.wakePauseWaiter();   // ★ r2/screenoff-wake：暂停中开始声纹注册 → 读循环立刻恢复录音
            Log.i(TAG, "[VP] 注册会话压制开关 = " + on);
        }
    }

    // ---------------------------------------------------------------- ★ 11-04 相机对话：麦克风旁路
    /**
     * 相机对话（{@code ui.live.CameraLiveActivity}）期间借用本服务已开着的麦克风（同一个 AudioRecord，带平台 AEC / NS）：
     * Android 8 只允许一个 App 录音，另开一个会抢麦或读到静音。旁路期间读循环把每块 PCM 只交给旁路，
     * <b>KWS / 捕获窗 / 能量门 / 声纹缓冲 / 打断检测一律不跑</b>，唤醒请求一律忽略；助手「暂停」时也照常收音（用户主动开的相机对话）。
     * PCM 只在内存里转交，不落盘、不进日志。
     */
    public interface LiveMicTap {
        /** 16 kHz / 16-bit / 单声道；读线程回调，不可阻塞。 */
        void onPcm(short[] buf, int n);
        /** ★ 11-04 G：旁路主人是否还活着（相机对话已挂断 = false）。主人死了旁路自动卸下，免得唤醒 / 录音被永久吞掉。 */
        boolean liveTapAlive();
    }

    private static volatile LiveMicTap sLiveTap;
    /** ★ 11-04 H：服务端 VAD 报过「开始说话」的那个捕获窗代号（-1 = 无）。 */
    private volatile int mRtSawSpeechGen = -1;

    /**
     * ★ ui-K2：旁路主人要<b>自己开麦</b>（相机对话改用 VOICE_COMMUNICATION + AEC / NS，与 TTS 同一条通话路由）——
     * 本服务这一路在旁路期间停录（AudioRecord.stop，参数 / 音源不动），旁路卸下后按「暂停 → 恢复」原路重新开录。
     */
    private static volatile boolean sLiveOwnMic;
    /** ★ ui-K2：ownMic 旁路期间本服务的录音确实已经停了（相机对话据此再开自己的 AudioRecord）。 */
    private static volatile boolean sLiveMicReleased;

    /** ★ ui-K2：本服务是否已为相机对话停录（没在跑也算已让出）。 */
    public static boolean liveMicReleased() {
        WakeService s = sInstance;
        return s == null || s.mAudioRecord == null || sLiveMicReleased;
    }

    /** 旁路装着且主人要自己开麦。 */
    static boolean liveOwnMicActive() {
        return sLiveOwnMic && liveTap() != null;
    }

    /** 装上 / 卸下旁路（本服务供音频，旧行为）。 */
    public static boolean setLiveMicTap(LiveMicTap tap) {
        return setLiveMicTap(tap, false);
    }

    /**
     * 装上 / 卸下旁路。返回 true = 服务在跑且麦克风可用；false = 服务不在（调用方自己开麦即可）。
     * ownMic=true（★ ui-K2）：本服务停录让出麦克风，PCM 不再转交，调用方自己开 VOICE_COMMUNICATION；唤醒请求照旧被吞。
     */
    public static boolean setLiveMicTap(LiveMicTap tap, boolean ownMic) {
        WakeService s = sInstance;
        if (tap == null) {
            sLiveTap = null;
            sLiveOwnMic = false;
            if (s != null) s.wakePauseWaiter();
            Log.i(TAG, "[LIVE-MIC] 相机对话旁路卸下");
            return true;
        }
        if (s == null || s.mAudioRecord == null || s.mAbort) return false;
        sLiveOwnMic = ownMic;
        sLiveTap = tap;
        s.wakePauseWaiter();   // 暂停中也要恢复录音（ownMic：读循环下一块就进暂停、停录）
        Log.i(TAG, "[LIVE-MIC] 相机对话旁路装上" + (ownMic ? "（ownMic：本服务停录让出麦克风）" : ""));
        return true;
    }

    public static boolean liveTapActive() {
        return liveTap() != null;
    }

    /** 只卸下自己装的旁路（旧页面晚到的 stop 不会卸掉新页面的）。 */
    public static void clearLiveMicTap(LiveMicTap owner) {
        if (owner != null && sLiveTap == owner) setLiveMicTap(null);
    }

    /**
     * ★ 11-04 G（用户 17:51：录音又录不进去）：旁路装着但主人已挂断 / 被杀（没走到 stop）时，唤醒请求、续听、播报打断会被一直吞掉，
     * 麦克风读到的音频也全交给死掉的旁路 → 表现为「按了没反应、录不进去」。所有判断都走这里，主人不活就当场卸下。
     */
    static LiveMicTap liveTap() {
        LiveMicTap t = sLiveTap;
        if (t == null) return null;
        boolean alive;
        try {
            alive = t.liveTapAlive();
        } catch (Throwable e) {
            alive = false;
        }
        if (alive) return t;
        if (sLiveTap == t) {
            sLiveTap = null;
            Log.w(TAG, "[LIVE-MIC] 旁路主人已不在，自动卸下（恢复唤醒 / 录音）");
        }
        return null;
    }

    private boolean beginVpRecording() {
        if (mAudioRecord == null || mAbort) {
            return false;
        }
        if (mVpRecBuffer == null) {
            mVpRecBuffer = new com.onesay.assistant.v2.asr.PcmWindowBuffer(SAMPLE_RATE);
        }
        mVpRecBuffer.start();
        mVpRecording = true;
        wakePauseWaiter();   // ★ r2/screenoff-wake
        Log.i(TAG, "[VP] 声纹录制开始（上限 " + com.onesay.assistant.v2.asr.PcmWindowBuffer.MAX_SECONDS + "s）");
        return true;
    }

    private byte[] endVpRecording() {
        boolean was = mVpRecording;
        mVpRecording = false;
        if (mVpRecBuffer == null) {
            return null;
        }
        mVpRecBuffer.stop();
        byte[] pcm = mVpRecBuffer.snapshot();
        Log.i(TAG, "[VP] 声纹录制结束: " + ((pcm == null) ? 0 : pcm.length / 2)
                + " 样本 / " + mVpRecBuffer.durationMs() + "ms"
                + (mVpRecBuffer.overflowed() ? "（已达上限被截断）" : "")
                + (was ? "" : "（本次未在录制态，仅清理）"));
        return pcm;
    }

    /**
     * 本地 vosk 识别链路已整体下线（识别全走云端）。
     *
     * <p>本方法保留为**兼容壳**：只剩 debug 探针 vpchk 调用；声纹注册的「字比对」r2/fixes 15 起已明确停用
     * （见 VoiceprintEnrollController.TEXT_CHECK_ENABLED：换云端会把注册录音传出本机，不做）。恒返回空串。
     *
     * @param pcm 16k/mono/16bit LE 字节流（当前实现不消费）
     * @return 恒为空串
     */
    public static String recognizeOffline(byte[] pcm) {
        return "";
    }


    /** ★ arch/voice-sync：收音窗开着（用户可能正在说话 / 录音）→ 应答 / 催一次不出声。 */
    public static boolean isCaptureOpen() {
        WakeService s = sInstance;
        try {
            return s != null && s.mFocus.state() == AssistAudioFocus.State.LISTENING;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isServiceRunning() {
        return sRunning;
    }

    public static String getStatusText() {
        return sStatus;
    }

    /** ★ R12：控制台切换「暂停」后刷新前台通知文案。 */
    public static void refreshPauseNotification() {
        WakeService s = sInstance;
        if (s == null) return;
        boolean paused = AssistantPause.isPaused(s);
        s.updateNotification(paused ? AssistantPause.notificationText(true, true) : sStatus);
        s.onPauseChanged("console");   // ★ r2/screenoff-wake：暂停即真暂停（停录音 + 放唤醒锁）
    }

    /**
     * ★ 隐私审计（给探针与真人自查用）：一行说清"唤醒音频出了本机没有"。
     *
     * <p>恒返回"唤醒本地"是设计事实，不是运行期状态 —— 唤醒路径压根不存在网络调用点：
     * {@link #requestWakeInternal} 只做三件事（去抖判定、播应答音、{@link #armCaptureWindow}），
     * 没有任何 {@code mAsrRouter} 调用。所以这里不需要查网络、不需要看配置。
     */
    public static String privacyReport() {
        WakeService s = sInstance;
        long gap = (s == null || s.mLastWakeAt == 0) ? -1 : (s.mCaptureFrom - s.mLastWakeAt);
        StringBuilder sb = new StringBuilder();
        sb.append("唤醒判定通道 = 本地 sherpa-onnx KWS（零网络）\n");
        sb.append("唤醒音频是否上行 = 否（唤醒只播应答音+开窗，无任何 HTTP 调用点）\n");
        // r2/fixes 12：按实际生效的规则描述（来源 / 短提示音 / 按键预录），不再写死「+1100ms」
        WakeTuning tu = s != null ? s.mTuning : null;
        boolean shortChime = tu != null ? tu.shortChime() : WakeTuning.DEF_SHORT_CHIME != 0;
        boolean preRoll = tu != null ? tu.keyPreRoll() : WakeTuning.DEF_KEY_PREROLL != 0;
        long preMs = tu != null ? tu.preRollMs() : WakeTuning.DEF_PREROLL_MS;
        sb.append(CaptureSessionPolicy.captureRuleText(shortChime, preRoll, preMs,
                WAKE_LOCAL_GUARD_MS, WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS, CAPTURE_FROM_OFFSET_MS));
        if (shortChime) sb.append("（r2/sysux 按下即听：所有唤醒来源起点 +").append(WAKE_LOCAL_GUARD_MS)
                .append("ms，[唤醒, +").append(WAKE_LOCAL_GUARD_MS).append("ms) 由预录补齐——起点 = 唤醒时刻，剔除提示音回声，只有提示音则整段丢弃；续听窗起点 = 开窗 +")
                .append(WAKE_LOCAL_GUARD_MS).append("ms）\n");
        sb.append("（最近一窗");
        if (gap < 0) {
            sb.append("：无有效窗");
        } else {
            sb.append(isKeySource(s.mLastWakeSource) ? "按键唤醒" : "唤醒词唤醒").append("，实测起点 +").append(gap).append("ms");
            long pre = s.mPreRollEmittedStartAt;
            if (pre > 0 && pre >= s.mLastWakeAt) sb.append("，预录接上起点 +").append(pre - s.mLastWakeAt).append("ms");
        }
        sb.append("；硬门限 ").append(WAKE_LOCAL_GUARD_MS).append("ms）\n");
        if (s == null) {
            sb.append("服务实例 = 未运行（无活动捕获窗）\n");
        } else {
            sb.append("云端开关 = ").append(s.mAsrRouter != null && s.mAsrRouter.config().isEnabled())
                    .append("，模式 = ").append(s.mAsrRouter == null ? "无" : s.mAsrRouter.getMode().name()).append("\n");
            sb.append("◆ 说明：云端只作用于**唤醒之后**捕获窗内的指令音频；唤醒词本身永不上行。\n");
        }
        return sb.toString();
    }

    /**
     * ★ 唯一唤醒入口。任何来源都必须经过这里，由它统一做幂等去抖：
     * 按键文件、按键兜底 Activity、语音 KWS、语音 ASR 全部收敛到这一个闸门。
     *
     * @param source       唤醒来源标记（仅日志用）
     * @param conversation 是否为长按进入对话模式
     */
    public static void requestWake(String source, boolean conversation) {
        WakeService s = sInstance;
        // ★ BUG-A1：简报闸门不在这里——挪到所有唤醒的汇合点 requestWakeInternal 第一行（WakeAdmission）。
        //   服务没建好时先排队，补发时同样经过 requestWakeInternal，闸门照样生效。
        // ★ R12 控制台「暂停」同样只在 requestWakeInternal 判一次（pausedBlocks，排在 WakeAdmission 之后、
        //   冷启动排队之前，所以暂停时也不会排队）。审计 G1：这里原来重复判一次暂停（服务在时两处各打一条日志），已删。
        if (s == null) {
            // ★ round3 #3：服务实例还没建好（冷启动：调用方刚 startForegroundService，服务是异步创建的）→ 排队，不再静默丢弃
            queueColdWake(source, conversation, "服务未创建");
            return;
        }
        s.requestWakeInternal(source, conversation);
    }

    /**
     * ★UI「✦ 语音」按钮专用入口 —— 在<b>当前会话里继续聆听</b>。
     *
     * <p><b>与 {@link #requestWake} 的语义差别（用户实测反馈驱动）</b>：
     * <ul>
     *   <li>{@code requestWake} = "新一次唤醒"：会经 {@code mShowListeningRunnable}
     *       → {@code setListeningPrompt} 把面板上的会话展示整屏重置（转录清空、结果卡隐藏）。
     *       这是给"唤醒词/按键"设计的语义 —— 上一轮对话到此为止。</li>
     *   <li>{@code requestContinueListening} = "面板已开，用户点按钮接着用嘴说"：
     *       只开捕获窗 + 播提示音，<b>不重置面板展示、不打断会话上下文</b>。</li>
     * </ul>
     *
     * <p>旧实现里「✦ 语音」按钮走的正是 {@code requestWake} —— 用户点完看到的是
     * "像新开了一个对话，上一轮问答全没了"。本入口是那条链路的专属替代。
     */
    public static void requestContinueListening() {
        WakeService s = sInstance;
        if (s != null) {
            s.requestContinueInternal();
        }
    }

    /** 「✦ 语音」继续聆听的受理实现（音频链路与唤醒完全一致，仅去掉 UI 重置）。 */
    private synchronized void requestContinueInternal() {
        if (mVpRecording || mEnrollSession) {
            Log.i(TAG, "[VP] 声纹注册中，忽略继续聆听请求");
            return;
        }
        if (liveTap() != null) {
            Log.i(TAG, "[LIVE-MIC] 相机对话中，忽略继续聆听请求");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // ★ device-1102 问题 2：按钮重入防抖（连点 / 抬手抖动在 400 ms 内只受理一次）
        if (com.onesay.assistant.v2.wake.MicReentry.debounced(mLastWakeReqAt, now)) {
            Log.i(TAG, "[WAKE] 「✦ 语音」400ms 内重复点击，忽略");
            return;
        }
        // ★ device-1102 问题 2：开麦先停上一轮回答的朗读（barge-in，同 requestWakeInternal）——
        //   否则麦克风把还在读的回答录进去，识别出「一大段」上屏 / 当成下一条指令
        com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
        mLastContinueSession = true;

        if (now < mCaptureUntil) {
            // 捕获窗还开着（用户连点 / 刚被唤醒）：只续期，不重播提示音
            // —— 与"短按升级长按"同策略，避免叠加提示音把用户说话盖掉。
            extendCaptureWindow(now);
            Log.i(TAG, "[WAKE] 「✦ 语音」继续聆听：捕获窗仍在，仅续期（不重播提示音）");
            return;
        }

        mLastWakeReqAt = now;
        mLastWakeSource = SRC_VOICE + "-ui";
        mFollowUpActive = false;
        mLastWakeReqConversation = false;
        boolean alt = (now - mLastNotifAt) < 90000;
        mLastNotifAt = now;
        armCaptureWindow(now, CAPTURE_MS);
        playWakeSounds(alt);
        Log.i(TAG, "[WAKE] 受理「✦ 语音」继续聆听（不清会话展示、不重置上下文）");
        // ★ 这里刻意 **不** post mShowListeningRunnable：
        //   它是"新一次唤醒"的 UI 重置（清转录 + 藏结果卡），与本入口的"接着聊"语义冲突。
        //   聆听态外观由 FloatingSheetView.switchToVoiceMode() 在点击瞬间直接切换。
    }

    /** 按键类来源（同一次按键会经多条通道抵达，必须做窗口级去抖） */
    private static boolean isKeySource(String source) {
        return source != null && source.startsWith("key-");
    }

    /** ★ R12 控制台「暂停」（D1-A）：唤醒词 / 按键 / 冷启动补发都汇到 requestWakeInternal，一处挡住；服务、岛、定时任务照常。 */
    private boolean pausedBlocks(String source) {
        if (!AssistantPause.blocks(AssistantPause.isPaused(this), source)) return false;
        Log.i(TAG, "[PAUSE] 助手已暂停，忽略唤醒请求（src=" + source + "）");
        return true;
    }

    /**
     * 简报播放中按 Bixby 键 = 只停简报、这一次不唤醒。只在 requestWakeInternal 第一行调用——
     * 按键文件短按 / 随口记收音 / 按键兜底 Activity（经 requestWake）/ 模拟按键 / 唤醒词 / 冷启动补发都经过那里；
     * 非 key-* 来源（唤醒词）一律放行。吞掉时什么都不碰（不开窗、不播应答音、不打断 TTS、不动去抖状态），D 线预录也就不会开始。
     */
    private boolean briefSwallowsKey(String source) {
        return WakeAdmission.briefSwallows(this, source, WakeAdmission.seqFor(source, mKeySeq));
    }

    private synchronized void requestWakeInternal(String source, boolean conversation) {
        if (briefSwallowsKey(source)) return;   // ★ BUG-A1 简报闸门（WakeAdmission）
        if (pausedBlocks(source)) return;   // ★ R12 暂停
        syncGestureFlag(this);   // 记一记开关可能刚被改
        // ★ round3 #3：麦克风还没开录（引擎启动期）→ 排队，开录后补发（所有唤醒来源都汇到这里，一处守卫覆盖全部）
        if (!sMicReady) {
            queueColdWake(source, conversation, "语音引擎未就绪");
            return;
        }
        // ★录制声纹期间**拒绝一切唤醒**（录制与唤醒互斥，用户明确要求）；
        //   录音功能迁移到 App 本体后，压制范围扩大到"注册会话全程"（含 READY 态，
        //   防用户提前跟读引导句触发唤醒面板）—— 见 mEnrollSession 注释。
        //
        //   为什么必须关：录制引导句本身就是唤醒词（"嗨比西比，现在几点"）——
        //   不关掉的话，用户每念一句都会触发一次唤醒流程：应答音抢播（把录制音频
        //   从 Acoustic Shield 里静音掉）、面板被改写、唤醒窗里的命令子句甚至会被
        //   当成真实指令去执行（"打开相机"就真的打开相机）。
        //
        //   wakeOnce()（KWS/vosk 语音）与 requestWake()（实体键等）**全部汇入本方法**，
        //   所以这一个守卫就覆盖所有唤醒路径。
        if (mVpRecording || mEnrollSession) {
            Log.i(TAG, "[VP] 声纹注册" + (mVpRecording ? "录制" : "会话")
                    + "中，忽略唤醒请求（src=" + source + "）");
            return;
        }
        if (liveTap() != null) {
            Log.i(TAG, "[LIVE-MIC] 相机对话中，忽略唤醒请求（src=" + source + "）");
            return;
        }
        // ★ r2/input：收音中再按一次键 = 说完了（距上次受理 ≥ 去抖窗、已听到人声或已有流式文字）。在简报闸门 / 暂停之后判，
        //   BUG-A1 不受影响；同一次按键从另一条通道迟到的请求（去抖窗内）一并吞掉，不会变成「新唤醒」。
        if (isKeySource(source)) {
            long nk = SystemClock.elapsedRealtime();
            if (CaptureSessionPolicy.swallowAfterKeyFinish(nk, mKeyFinishAt, WAKE_DEDUP_MS)) {
                Log.i(TAG, "[EP] 吞掉同一次「结束」按键的迟到通道 src=" + source + "（距受理 " + (nk - mKeyFinishAt) + "ms）");
                return;
            }
            int needK = (mTuning != null) ? mTuning.minSpeechFrames() : MIN_SPEECH_FRAMES;
            if (CaptureSessionPolicy.keyPressFinishes(nk < mCaptureUntil && nk >= mCaptureFrom, mGotCommand, mCaptureClosed,
                    mCloudFiredForThisCapture, nk - mLastWakeReqAt, WAKE_DEDUP_MS,
                    mEnergyGate.getSpeechFrameCount(), needK, rtText(true).length() > 0)) {
                mKeyFinishAt = nk;
                Log.i(TAG, "[EP] 收音中再按一次键（src=" + source + "，距上次受理 " + (nk - mLastWakeReqAt) + "ms）→ 结束收音、马上识别");
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        finishCaptureNow("再按一次键");
                    }
                });
                return;
            }
        }
        // 新一次唤醒立即打断正在进行的语音回复（barge-in）
        com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
        long now = SystemClock.elapsedRealtime();
        long dt = now - mLastWakeReqAt;
        boolean windowStillOpen = (now < mCaptureUntil);

        // ★ 去抖分两级，不能一刀切：
        //   - 按键类（key-file / key-activity）：同一次按键的两条通道**必然落在同一个捕获窗内**，
        //     所以做"窗口级"去抖 —— 窗口还开着就是重复，直接丢。
        //   - 语音类（voice，KWS 与 ASR 都可能报）：只做"时间级"去抖。
        //     因为用户主动再喊一次唤醒词（第一次没说清）是完全合理的行为，
        //     此时窗口往往还开着，用窗口级去抖会把他锁在门外。
        boolean windowLevelDedup = isKeySource(source) && isKeySource(mLastWakeSource);
        boolean dedupHit = dt < WAKE_DEDUP_MS
                && conversation == mLastWakeReqConversation
                && (!windowLevelDedup || windowStillOpen);

        if (dedupHit) {
            Log.i(TAG, "[WAKE-DEDUP] 丢弃重复唤醒请求 src=" + source
                    + "（距上次 " + dt + "ms，捕获窗" + (windowStillOpen ? "仍开启" : "已闭合") + "）");
            return;
        }

        // 短按已开窗，长按随后升级为对话模式 → 只延长捕获窗，绝不重播应答音
        if (dt < WAKE_DEDUP_MS && conversation && !mLastWakeReqConversation && windowStillOpen) {
            Log.i(TAG, "[WAKE-UPGRADE] 短按升级为对话模式，不重播应答音 src=" + source);
            mLastWakeReqAt = now;
            mLastWakeReqConversation = true;
            mLastWakeSource = source;
            extendCaptureWindow(now);
            mMainHandler.post(mShowListeningRunnable);
            return;
        }

        mLastWakeReqAt = now;
        mLastWakeReqConversation = conversation;
        mLastWakeSource = source;
        mLastContinueSession = false;   // ★ 真实唤醒 = 新会话语义（收窗文案走完整"没听清"）
        Log.i(TAG, "[WAKE] 受理唤醒 src=" + source + " 对话模式=" + conversation
                + "（距上次 " + dt + "ms）");
        mScreenOnAtWake = screenInteractiveNow();   // ★ r2/sysux：亮屏之前记下（「我在」只在息屏语音唤醒时说）
        mFollowUpActive = false;
        wakeScreenForWake(source);   // ★ r2/screenoff-wake：息屏 → 先亮屏；锁屏 → 托底 Activity

        // 90 秒内反复唤醒时换一个应答音，长按时也用应答音 2（与三星原生手感对齐）
        boolean alt = conversation || (now - mLastNotifAt) < 90000;
        mLastNotifAt = now;

        // 起步窗按来源分流 ——
        //   按键/长按（或显式对话模式）= 长窗，对应用户"我按住就是要说事"的意图；
        //   语音唤醒词 = 标准窗。两者都能"仍在说话时续期"。
        long wantWindow = (conversation || isKeySource(source)) ? CAPTURE_MS_KEY : CAPTURE_MS;
        armCaptureWindow(now, wantWindow);
        playWakeSounds(alt);
        requestAssistContext(source);   // ★ r2/sysux P2-2：是默认助理 → 向系统要前台 App 的 AssistStructure（uiautomator 作回退）
        mMainHandler.post(mShowListeningRunnable);
    }

    private void requestAssistContext(String source) {
        try {
            boolean opt = mTuning == null || mTuning.assistContextOnWake();
            if (!com.onesay.assistant.v2.assist.AssistEntryPolicy.wantContextOnWake(opt,
                    com.onesay.assistant.v2.assist.OneSayVoiceInteractionService.isActive(this), keyguardLockedNow(), source)) return;
            com.onesay.assistant.v2.assist.OneSayVoiceInteractionService.requestContext(this, source);
        } catch (Throwable t) {
            Log.w(TAG, "[ASSIST] 要屏幕上下文失败（回退 uiautomator）: " + t);
        }
    }

    /** 只延长捕获窗，不重播提示音、不重启音频链路（长按升级场景） */
    private void extendCaptureWindow(long now) {
        // ★ P1-2：短按升级为对话模式，起步窗直接给到长窗；但仍受整段窗硬上限约束。
        long win = clampCaptureWindow(now, CAPTURE_MS_KEY);
        mCaptureUntil = now + win;
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        mMainHandler.postDelayed(mWindowExpiredRunnable, win);
        Log.i(TAG, "[CAPTURE] 捕获窗已延长至 +" + win + "ms（长按对话模式）");
    }

    /**
     * 把想要的窗口时长裁到"整段窗硬上限"之内。
     *
     * <p>整段窗从<b>唤醒时刻</b>起算，所以传入的是 {@code mLastWakeAt} 而不是 now ——
     * 否则反复续期时基准会不断后移，硬上限就形同虚设。
     */
    private long clampCaptureWindow(long now, long wantMs) {
        return clampCaptureWindow(now, mLastWakeAt, wantMs);
    }

    /** 纯函数版（可单测）：把想要的窗长裁进"从唤醒时刻起算的整段硬上限"。 */
    public static long clampCaptureWindow(long now, long lastWakeAt, long wantMs) {
        long used = (lastWakeAt > 0) ? (now - lastWakeAt) : 0L;
        long remain = CAPTURE_MAX_MS - used;
        if (remain <= 0) return 0L;
        return Math.min(wantMs, remain);
    }

    /**
     * 窗口到点前的"续期判定"。
     *
     * <p><b>要解决的缺陷</b>：捕获窗原本是<b>从唤醒时刻起算的固定时长</b>。
     * 于是"唤醒后想一下再说""语速慢""句子偏长"这三种情况，
     * 都会在用户话说到一半时把窗关掉 —— 用户感受到的是"我明明说完了，它却说没听清"。
     * （同类缺陷在外部参考里也被点名：热词链路"停顿约 1 秒就关麦"。）
     *
     * <p><b>续期条件只有一个</b>：能量门在最近 {@link #RENEW_IF_SPEECH_WITHIN_MS}
     * 毫秒内看见过<b>人声</b>。也就是说 —— <b>只有"人还在说话"才续期</b>。
     * 一直没人出声时照常按点收窗（该提示"没听清"就提示），
     * 刻意不做"既然开了就多等一会儿"，避免麦克风无意义地开着。
     *
     * <p><b>两道额外闸门</b>：
     * <ul>
     *   <li>{@code now < mCaptureFrom} 时不续期 —— 这一段是应答音播放期，
     *       续期等于是被自己的应答音续命；</li>
     *   <li>整段窗（首窗 + 全部续期）不超过 {@link #CAPTURE_MAX_MS}，到顶正常收窗。</li>
     * </ul>
     *
     * <p><b>🚨 隐私铁律（改这个函数前必读）</b>：续期<b>只推后结束边界
     * {@code mCaptureUntil}，永远不碰起点 {@code mCaptureFrom}</b>。
     * 起点决定了"哪一段音频会被缓存并可能上行"；把它前移 = 把唤醒前的声音送上网。
     * {@link #tryCloudRecognize} 里的 {@code WAKE_LOCAL_GUARD_MS} 断言会把这种改动拦下，
     * 但别指望断言兜底 —— 压根不要写错。
     *
     * @return {@code true} = 已成功续期（本次不收窗）；{@code false} = 正常到期，继续走收窗流程
     */
    /**
     * 续期判定的**纯函数**版本（无副作用，供自测直接断言）。
     *
     * <p>把它单独拎出来是为了让"什么时候该续期"这件事**可被确定性验证** ——
     * 否则只能靠对着手机说话去碰运气，测不出边界（触顶 / 应答音期 / 窗已关）。
     *
     * @param now          当前时刻（elapsedRealtime）
     * @param lastWakeAt   本次唤醒时刻
     * @param captureFrom  上传窗起点（唤醒 +1100ms）
     * @param captureUntil 捕获窗结束边界，{@code <= 0} 表示窗已关
     * @param lastSpeechAt 能量门最后一次判定为人声的时刻（0 = 从未）
     * @return true = 应当续期
     */
    public static boolean shouldRenewCapture(long now, long lastWakeAt, long captureFrom,
                                             long captureUntil, long lastSpeechAt) {
        return shouldRenewCapture(now, lastWakeAt, captureFrom, captureUntil, lastSpeechAt, RENEW_IF_SPEECH_WITHIN_MS);
    }

    /** ★ r2/input：续期判据窗口 = max(2.5 s, 当前尾静音 + 0.5 s)（纯函数在 CaptureSessionPolicy，JVM 可测）。 */
    public static long renewWithinMs(int requiredSilenceMs) {
        return CaptureSessionPolicy.renewWithinMs(RENEW_IF_SPEECH_WITHIN_MS, requiredSilenceMs);
    }

    /** 同上，判据窗口可调（r2/input）。 */
    public static boolean shouldRenewCapture(long now, long lastWakeAt, long captureFrom,
                                             long captureUntil, long lastSpeechAt, long withinMs) {
        return CaptureSessionPolicy.shouldRenew(now, lastWakeAt, captureFrom, captureUntil, lastSpeechAt, withinMs, CAPTURE_MAX_MS);
    }

    private boolean renewCaptureIfStillSpeaking(long now) {
        // ★ 能量门只能看见"有声"，分不清那是谁 —— 所以这里必须自己排掉我方声音：
        //   应答音还在响 / 刚响完的那一段，一律不续期，否则就成了"自己的提示音给自己续命"。
        //   （正常路径下应答音在唤醒 +1250ms 就结束了，离窗到期还有 6 秒多，这条不会误伤；
        //     它真正防的是"窗内又唤醒一次"时新播的那声提示音。）
        if (mIsPlayingAckSound || now < mAckSoundEndAt) return false;

        // ★ r2/input：「人还在说」= 能量门或流式最近有动静；判据窗口 = max(2.5 s, 当前尾静音 + 0.5 s) —— 说到「然后」停下来想时不关窗
        final long lastSpeech = Math.max(mEnergyGate.getLastSpeechTimestamp(), rtOwns() ? mRtActivityAt : 0L);
        if (!shouldRenewCapture(now, mLastWakeAt, mCaptureFrom, mCaptureUntil, lastSpeech,
                renewWithinMs(mEpRequiredMs))) {
            return false;
        }
        // ★ R14（diag P0-2）：续期还要「本窗人声帧够数」或服务端 VAD 说正在说 —— 一帧零星人声不再把窗拖到 16 s
        final int needRenew = (mTuning != null) ? mTuning.minSpeechFrames() : MIN_SPEECH_FRAMES;
        final int framesNow = mEnergyGate.getSpeechFrameCount();
        if (!com.onesay.assistant.v2.asr.RealtimeAsrPolicy.renewAllowed(framesNow, needRenew, mRtSpeaking && rtOwns())) {
            Log.i(TAG, "[CAPTURE] 距上次人声 " + (now - lastSpeech) + "ms，但本窗人声帧 " + framesNow + "/" + needRenew
                    + " 不够、服务端也没在听到说话 → 不续期，按点收窗");
            return false;
        }

        long used = (mLastWakeAt > 0) ? (now - mLastWakeAt) : CAPTURE_MAX_MS;
        long step = clampCaptureWindow(now, CAPTURE_EXTEND_STEP_MS);
        if (step <= 0) return false;

        long prevUntil = mCaptureUntil;
        mCaptureUntil = now + step;
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        mMainHandler.postDelayed(mWindowExpiredRunnable, step);

        // ★ 打点三件事，方便事后核对这次续期是否合理：
        //   ① 距上次人声多久（判据）；② 结束边界往后挪了多少；③ 起点有没有被误动。
        Log.i(TAG, "[CAPTURE] 仍在说话（距上次人声 " + (now - lastSpeech) + "ms ≤ "
                + RENEW_IF_SPEECH_WITHIN_MS + "ms）→ 捕获窗续期 +" + step + "ms"
                + "（结束边界 " + (prevUntil - now) + "→" + step + "ms；"
                + "整段 " + used + "/" + CAPTURE_MAX_MS + "ms；"
                + "隐私起点 mCaptureFrom 未动，仍为唤醒+" + (mCaptureFrom - mLastWakeAt) + "ms）");
        return true;
    }

    private final Runnable mShowListeningRunnable = new Runnable() {
        @Override
        public void run() {
            FloatingSheetService.showListening(WakeService.this, "我在听，请讲...");
        }
    };

    /** UI 层面主动点击“取消”或收起浮层时，立即重置捕获窗与麦克风通道 (绝不在主线程调用本地 C++ 指针，防 SIGSEGV) */
    public static void cancelCaptureFromUi() {
        cancelCaptureFromUiInternal(null);
    }

    /**
     * （HIDE 迟到「没听清」）：ACTION_HIDE 收起浮层 = 这一窗作废。只在窗还开着 / 云端还在路上、且没拿到指令时才动
     * （{@link com.onesay.assistant.v2.wake.CaptureSessionPolicy#cancelOnHide}）—— 已拿到指令（随口记、长按）或本窗已收尾的 HIDE 不受影响。
     * 代际号 +1 之后：窗到点回调撤掉；在途的非流式 / 流式回调、以及已排队的「没听清」都按会话号判为迟到丢弃。
     */
    public static void cancelCaptureFromUi(String why) {
        WakeService s = sInstance;
        if (s == null) return;
        boolean open = s.mCaptureUntil > 0;
        if (!com.onesay.assistant.v2.wake.CaptureSessionPolicy.cancelOnHide(s.mGotCommand, s.mCaptureClosed, open, s.mCloudInFlight)) return;
        Log.i(TAG, "[CAPTURE] " + why + " 收起浮层 → 作废本窗（会话 #" + s.mCaptureGen + " → #" + (s.mCaptureGen + 1)
                + (open ? "，窗还开着" : "") + (s.mCloudInFlight ? "，云端在途" : "") + "）");
        cancelCaptureFromUiInternal(why);
    }

    private static void cancelCaptureFromUiInternal(String why) {
        WakeService s = sInstance;
        if (s != null) {
            s.mCaptureUntil = 0;
            s.mFollowUpActive = false;
            s.focusTurnEnd("界面取消" + (why != null ? " · " + why : ""));   // ★ r2/sysux P1-2：用户取消 = 整轮结束，归还焦点
            // ★ R14：「⌨ 文字」/ 点外部 / 关闭 = 这一窗作废，干净地停：不再收音、流式会话关掉、窗到点回调撤掉、迟到的识别结果丢弃
            //   （代际号 +1 → 在途的非流式回调与流式回调都会被当成跨窗迟到丢弃，不会在打字时突然执行一句语音指令）。
            if (!s.mGotCommand && !s.mCaptureClosed) {
                s.mCaptureClosed = true;
                s.mCaptureGen++;
                s.mMainHandler.removeCallbacks(s.mWindowExpiredRunnable);
                try { s.mPcmBuffer.stop(); } catch (Throwable ignore) {}
                s.mCloudInFlight = false;   // 在途请求属于作废的会话，回调到达也会按会话号丢弃
                s.onCaptureClosed(why != null ? "界面取消 · " + why : "界面取消");
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 取当前存活的 WakeService 实例，服务未运行时返回 null。
     * <p>仅供测试探针使用（诊断开关 / 清理诊断文件），不给业务链路调用。
     */
    public static WakeService peek() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // 唤醒修复（11-08 真机 ANR「startForegroundService() did not then call startForeground()」）：
        //   前台声明是本服务的<b>第一件事</b>，排在任何初始化（调参中心 / 云端 ASR / 屏幕状态 / 唤醒锁）之前 ——
        //   重装后第一次冷启动（dex 还没优化、主线程同时在建控制台）onCreate 本身就可能吃掉好几秒，
        //   旧实现要等 onCreate 全部跑完、onStartCommand 才声明，超过系统时限就 ANR。之后任何路径（含将来的提前返回）都已在前台。
        ensureForeground("onCreate");
        // 凭据明文 → Keystore 密文的一次性迁移（后台线程；失败保持明文，不影响启动）
        final Context appCtx = getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("secret-migrate", new Runnable() {
            @Override public void run() {
                int n = com.onesay.assistant.v2.security.SecretPrefs.migrateAll(appCtx);
                if (n > 0) Log.i(TAG, "[SECRET] 启动迁移：" + n + " 个凭据已改存 Keystore 密文");
            }
        });
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mEnergyGate = new EnergyGate();
        // 调参中心（须在任何 KWS/声纹读取参数之前初始化）
        mTuning = com.onesay.assistant.v2.wake.WakeTuning.get(this);
        Log.i(TAG, "[TUNING] 唤醒调参中心就绪" + mTuning.dump());
        // ★ int/1102：唤醒提示音来源（默认读手机上的三星原声 Bixby_BOS；找不到退回方案 A），后台解码一次并缓存
        com.onesay.assistant.v2.util.Threads.start("wake-cue", new Runnable() {
            @Override public void run() { WakeCueLoader.init(appCtx); }
        });
        // 云端 ASR（默认关闭；未配置 key 时 isCloudReady() 恒为 false，链路行为不变）
        mAsrRouter = com.onesay.assistant.v2.asr.AsrRouter.getInstance(this);
        // ★ R14：捕获窗缓冲上限与整段窗上限对齐（20 s；旧 12 s 时续期出来的那段白听）
        mPcmBuffer = new com.onesay.assistant.v2.asr.PcmWindowBuffer(SAMPLE_RATE, com.onesay.assistant.v2.asr.PcmWindowBuffer.CAPTURE_MAX_SECONDS);
        // ★ R14 一次性迁移：短提示音 + 自适应断句默认开（真机 prefs 里是控制台写下的显式 0）
        mTuning.migrateR14();
        mTuning.migrateR2Endpoint();   // ★ r2/input：断句旧默认的显式值撤掉
        mTuning.migrateSysux();        // ★ r2/sysux：按下即听（short_chime=1，用户 10-29 拍板，只迁一次）
        mFocus = new AssistAudioFocus(mTuning.focusDuckWhileSpeaking());
        com.onesay.assistant.v2.ui.TtsSpeaker.setSpeechListener(new com.onesay.assistant.v2.ui.TtsSpeaker.SpeechListener() {
            @Override public void onSpeechStart() { onReplySpeechStart(); }
            @Override public void onSpeechDone(boolean interrupted) { onReplySpeechDone(interrupted); }
            @Override public void onSpeechSkipped(String why) { onReplyNotSpoken(why); }
        });
        try {
            if (mAsrRouter != null && mAsrRouter.config().migrateR2RealtimeSilence())
                Log.i(TAG, "[TUNING] r2 迁移：realtime_silence_ms 显式 450 已撤掉 → 默认 " + mAsrRouter.config().getRealtimeSilenceMs() + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "[TUNING] r2 迁移异常: " + t);
        }
        // ★ R14（diag C7/C8）：ICU Han-Latin 与热词表在后台预热 —— 不再压在第一句话的主线程上（10-15：880 ms + 331 ms）
        com.onesay.assistant.v2.util.Threads.start("asr-prewarm", new Runnable() {
            @Override public void run() {
                try { com.onesay.assistant.v2.router.PinyinUtil.prewarm(); } catch (Throwable t) { Log.w(TAG, "[PREWARM] ICU 预热失败: " + t); }
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    com.onesay.assistant.v2.asr.AsrHotwords.prewarm(appCtx);
                    Log.i(TAG, "[PREWARM] 热词表就绪 " + (SystemClock.elapsedRealtime() - t0) + "ms");
                } catch (Throwable t) { Log.w(TAG, "[PREWARM] 热词预热失败: " + t); }
            }
        });
        Log.i(TAG, "[ASR-CLOUD] 云端识别通道: " + mAsrRouter.config().describe());
        Log.i(TAG, "[ASR-CLOUD] 生效通道=" + mAsrRouter.activeCloudName()
                + " 就绪=" + mAsrRouter.isCloudReady()
                + " 凭据完整=" + mAsrRouter.isCloudConfigured());
        initScreenState();          // ★ r2/screenoff-wake：屏幕 / 锁屏 / 暂停状态
        updateWakeLock("onCreate"); // ★ r2/screenoff-wake：只在「没暂停且息屏」时持有 PARTIAL（旧：onCreate 拿了永不放）
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sInstance = this;
        // 每次 startForegroundService 都对应一次 onStartCommand —— 第一行再声明一次（幂等），任何 return / stopSelf 都在它之后。
        ensureForeground("onStartCommand");

        if (!sRunning) {
            sRunning = true;
            mAbort = false;
            startKeyTriggerWatcher();
            // 预先拉起 FloatingSheetService，确保其随进程常驻（getInstance 不返回 null）
            try {
                Intent fsIntent = new Intent(this, FloatingSheetService.class);
                startService(fsIntent);
            } catch (Throwable t) {
                Log.w(TAG, "预启动 FloatingSheetService 失败: " + t.getMessage());
            }
            mExec.execute(new Runnable() {
                @Override
                public void run() {
                    bootEngine();
                }
            });
        }

        if (intent != null && intent.hasExtra("play_test")) {
            final String testName = intent.getStringExtra("play_test");
            playAssetAsync(testName);
            return START_STICKY;
        }

        if (intent != null && intent.getBooleanExtra("trigger_key", false)) {
            simulateKeyTrigger();
            return START_STICKY;
        }

        return START_STICKY;
    }

    /**
     * ★ round3 #3：冷启动排队一次唤醒（只留最新一条）。到点（{@link #COLD_WAKE_TIMEOUT_MS}）仍未就绪 → 丢弃并在面板说明。
     */
    private static void queueColdWake(String source, boolean conversation, String why) {
        long now = SystemClock.elapsedRealtime();
        // 服务曾就绪又被销毁/重建：队列回到未就绪，重新排队
        if (sColdWakes.isReady()) sColdWakes.reset();
        sColdWakes.offer(new String[]{ source, conversation ? "1" : "0" }, now);
        com.onesay.assistant.v2.util.ReadyQueue.Item<String[]> dropped = sColdWakes.takeDropped();
        Log.i(TAG, "[COLD] 唤醒请求排队（" + why + "）src=" + source + " conv=" + conversation
                + (dropped != null ? "（同一时段更早的一次唤醒合并掉：" + dropped.value[0] + "）" : "")
                + "，最长等待 " + COLD_WAKE_TIMEOUT_MS + "ms");
        sColdHandler.removeCallbacks(sColdWakeTimeout);
        sColdHandler.postDelayed(sColdWakeTimeout, COLD_WAKE_TIMEOUT_MS + 50);
    }

    private static final Runnable sColdWakeTimeout = new Runnable() {
        @Override
        public void run() {
            if (sMicReady) return;
            com.onesay.assistant.v2.util.ReadyQueue.Drain<String[]> d =
                    sColdWakes.expire(SystemClock.elapsedRealtime(), false);
            if (d.expired.isEmpty()) return;
            Log.w(TAG, "[COLD] 语音引擎 " + COLD_WAKE_TIMEOUT_MS + "ms 内未就绪，丢弃排队唤醒 " + d.expired.size()
                    + " 次（状态：" + sStatus + "）");
            FloatingSheetService fs = FloatingSheetService.getInstance();
            if (fs != null) fs.showTerminalPrompt("语音引擎还在启动，这次没能开始收音，请稍后再按一次");
        }
    };

    /** ★ round3 #3：麦克风开录（引擎线程）→ 主线程补发排队的唤醒。 */
    private void onMicReady() {
        if (mAbort) return;
        sMicReady = true;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                com.onesay.assistant.v2.util.ReadyQueue.Drain<String[]> d = sColdWakes.markReady(now);
                sColdHandler.removeCallbacks(sColdWakeTimeout);
                for (com.onesay.assistant.v2.util.ReadyQueue.Item<String[]> it : d.expired) {
                    Log.w(TAG, "[COLD] 排队唤醒已超时丢弃（等待 " + it.waitedMs(now) + "ms）src=" + it.value[0]);
                }
                Log.i(TAG, "[COLD] 语音引擎就绪，补发排队唤醒 " + d.ready.size() + " 次");
                for (com.onesay.assistant.v2.util.ReadyQueue.Item<String[]> it : d.ready) {
                    Log.i(TAG, "[COLD] 补发唤醒（等待 " + it.waitedMs(now) + "ms）src=" + it.value[0]);
                    requestWakeInternal(it.value[0], "1".equals(it.value[1]));
                }
            }
        });
    }

    /**
     * 按键唤醒的可选精确路径（触发方式 = 原始按键事件）：root / Shizuku 跑 tools/kdaemon/key_daemon.sh，读 getevent，
     * 每次按下目标键写 files/key.trigger（{@link KeyTrigger}）。这里只在入口打开、触发方式选了原始按键事件时监听，
     * 两次按下落在时间窗内 = 双击 → 按设置的动作唤起。
     */
    private void startKeyTriggerWatcher() {
        if (!com.onesay.assistant.v2.entry.Entries.canFire(this, com.onesay.assistant.v2.entry.BuiltinEntries.KEY_WAKE)
                || com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.trigger(this) != com.onesay.assistant.v2.entry.keywake.KeyWake.Trigger.RAW_INPUT) {
            return;
        }
        final File dir = getFilesDir();
        final File triggerFile = new File(dir, "key.trigger");
        final com.onesay.assistant.v2.entry.keywake.DoubleTap tap =
                new com.onesay.assistant.v2.entry.keywake.DoubleTap(com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.windowMs(this));
        mTriggerObserver = new FileObserver(dir.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (!"key.trigger".equals(path)) return;
                if (!com.onesay.plugin.api.entry.EntryRegistry.canFire(com.onesay.assistant.v2.entry.BuiltinEntries.KEY_WAKE)) return;
                KeyTrigger kt = KeyTrigger.parse(readSmall(triggerFile));
                if (kt.kind != KeyTrigger.Kind.PRESS) return;
                long t = kt.ms > 0 ? kt.ms : SystemClock.elapsedRealtime();
                if (tap.onPress(t)) {
                    com.onesay.assistant.v2.entry.keywake.KeyWake.Action a = com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.action(WakeService.this);
                    Log.i(TAG, "[KEY] 原始按键双击 → " + a.label);
                    com.onesay.assistant.v2.entry.Entries.fire(WakeService.this, com.onesay.assistant.v2.entry.BuiltinEntries.KEY_WAKE,
                            com.onesay.assistant.v2.entry.keywake.KeyWake.modeFor(a));
                }
            }
        };
        mTriggerObserver.startWatching();
        Log.i(TAG, "按键唤醒（原始按键事件）监听已启动");
    }

    /**
     * 有插件要接长按（记一记开着）→ 建 files/bixby.gesture_v2，守护 v7 才把长按写成「long …」；
     * 没有 → 删掉标记，守护保持旧行为。启动时与每次唤醒时同步（插件开关可能在调试入口里被改）。
     */
    public static void syncGestureFlag(Context ctx) {
        try {
            File flag = new File(ctx.getFilesDir(), KeyTrigger.GESTURE_FLAG);
            File v3 = new File(ctx.getFilesDir(), KeyTrigger.GESTURE_FLAG_V3);
            boolean want = com.onesay.assistant.v2.kernel.FeatureKernel.get().wantsKeyGesture(ctx, "long");
            if (want && !flag.exists()) {
                flag.createNewFile();
                Log.i(TAG, "[KEY] 已建手势标记（长按 → 记一记）");
            } else if (!want && flag.exists()) {
                flag.delete();
                Log.i(TAG, "[KEY] 已删手势标记（长按保持旧行为）");
            }
            // ★ R10：v3 标记（守护 v8：按下只预热、松手才算短按 → 长按完全静音）跟 v2 一起建、一起删
            if (want && !v3.exists()) {
                v3.createNewFile();
                Log.i(TAG, "[KEY] 已建 v3 手势标记（守护 v8：松手才唤醒，长按静音）");
            } else if (!want && v3.exists()) {
                v3.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "[KEY] 手势标记同步失败: " + t.getMessage());
        }
    }

    /** ★ R10：最近一次长按是否走的守护 v8（按下没出声、没弹面板）—— 记一记据此省掉等面板退场的 450 ms。 */
    public static boolean lastLongWasSilent() {
        return sLastLongSilent;
    }

    /** ★ R10：收到过守护 v8 的「down」（调试 / 状态用）。 */
    public static boolean daemonV8Seen() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return false;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sV8Seen;
    }

    /** 长按的触感反馈：一下短促的 tick（权限 VIBRATE 已在清单里）。 */
    private void vibrateTick() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(28, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable t) {
            Log.w(TAG, "[KEY] 震动失败: " + t.getMessage());
        }
    }

    /** 长按：撤销按下时开的唤醒窗与面板，再交给插件（后台线程）。silent = 守护 v8（按下时什么都没出）。 */
    private void onLongGesture(boolean silent, int seq) {
        vibrateTick();
        if (!silent) abortAckSound();   // 方案 C：守护 v7 时唤醒音已经在放 → 掐断
        cancelCaptureFromUi();
        mMainHandler.removeCallbacks(mShowListeningRunnable);
        // 只把 mCaptureUntil 清 0 不够 —— 按下时挂的到期回调 12 s 后照跑「指令窗超时闭合」，
        //   走 notifyCaptureMiss 可能把「没听清」面板又弹出来。长按 = 这一窗作废：撤掉到期回调，按「已收到指令」收口。
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        mGotCommand = true;
        mCaptureUntil = 0;
        onCaptureClosed("长按");
        FloatingSheetService.hide(this);
        dispatchKeyGesture(getApplicationContext(), "long", seq);
    }

    /** 冷启动（守护 -e gesture long 拉起 Activity）用：没有守护 seq。 */
    public static void dispatchKeyGesture(final Context app, final String gesture) {
        dispatchKeyGesture(app, gesture, 0);
    }

    /** 按键手势（long / hold）的唯一汇合点：冷启动 Activity 与按键文件热路径共用。seq = 守护按键序号（不知道传 0）。 */
    static void dispatchKeyGesture(final Context app, final String gesture, final int seq) {
        com.onesay.assistant.v2.util.Threads.start("key-gesture", new Runnable() {
            @Override public void run() {
                // 长按也遵守「简报播放中按键 = 只停简报」——不交插件（记一记 / 随口记都不启动）
                if (WakeAdmission.briefSwallows(app, WakeAdmission.gestureSource(gesture), seq)) return;
                if (AssistantPause.blocks(AssistantPause.isPaused(app), "gesture-" + gesture)) {
                    Log.i(TAG, "[PAUSE] 助手已暂停，忽略按键手势 " + gesture);
                    return;
                }
                boolean handled = com.onesay.assistant.v2.kernel.FeatureKernel.get().onKeyGesture(app, gesture);
                Log.i(TAG, "[KEY] 手势 " + gesture + (handled ? " 已由插件处理" : " 没有插件处理（忽略）"));
            }
        });
    }

    private static String readSmall(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** ★ R10 随口记（按住 ≥ 2 s）：开一个正常的收音窗（有应答音 = 「现在说」），下一句由记一记插件在面板入口截走。 */
    public static void requestSuikoujiListen() {
        WakeService s = sInstance;
        if (s == null) return;
        s.mMainHandler.post(new Runnable() {
            @Override public void run() {
                WakeService w = sInstance;
                if (w != null) w.requestWakeInternal(SRC_KEY_FILE + "-hold", false);
            }
        });
    }

    public void simulateKeyTrigger() {
        mLastTriggerAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "[SIMULATE] 模拟 Bixby 按键唤醒触发");
        requestWakeInternal(SRC_KEY_SIM, false);
    }

    /** 2. 初始化引擎并进入麦克风主循环 */
    private void bootEngine() {
        try {
            sStatus = "正在初始化语音引擎...";
            updateNotification(sStatus);

            initKws();

            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 32000);

            // 优先采用语音识别专用通道 VOICE_RECOGNITION（启用系统声学回声消除与降噪算法）
            try {
                mAudioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                );
            } catch (Throwable t) {
                mAudioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                );
            }

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (mMicState.onInitFailed("STATE_UNINITIALIZED")) updateWakeLock("mic-init-failed");
                throw new IllegalStateException("AudioRecord 初始化失败，请检查录音权限");
            }

            // 激活底层硬件 DSP AcousticEchoCanceler (AEC) 与 NoiseSuppressor (NS)
            try {
                int sessionId = mAudioRecord.getAudioSessionId();
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId);
                    if (aec != null) {
                        aec.setEnabled(true);
                        Log.i(TAG, "硬件级 AcousticEchoCanceler (AEC) 已激活");
                    }
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(sessionId);
                    if (ns != null) {
                        ns.setEnabled(true);
                        Log.i(TAG, "硬件级 NoiseSuppressor (NS) 已激活");
                    }
                }
            } catch (Throwable ignore) {}

            mAudioRecord.startRecording();
            // ★ r2/sysux R1：只有确认进入录制态才算「在录」，唤醒锁随之评估（别的 App 占麦时这里为 false，不持锁）
            boolean recOk = mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
            mMicState.onStartResult(recOk, "boot");
            updateWakeLock("mic-started");
            sStatus = "语音与按键监听就绪";
            updateNotification(sStatus);
            Log.i(TAG, "AudioRecord 已进入录制状态，开始流式监听循环");
            onMicReady();   // ★ round3 #3：补发冷启动期间排队的唤醒

            micLoop();
            if (mMicState.onStopped("读循环退出")) updateWakeLock("mic-loop-exit");

        } catch (Throwable t) {
            if (mMicState.onStopped("启动异常")) updateWakeLock("mic-boot-failed");
            Log.e(TAG, "WakeService 启动异常: " + t.getMessage(), t);
            sStatus = "启动故障: " + t.getMessage();
            updateNotification(sStatus);
        }
    }

    private void initKws() {
        // OneSay 开源版：本地唤醒词是可选功能，默认关；开了也要用户自己填了唤醒词才初始化（不内置任何唤醒词）。
        android.content.SharedPreferences wwSp = getSharedPreferences(WakeWords.PREFS, MODE_PRIVATE);
        final String userWords = wwSp.getString(WakeWords.KEY_LINES, "");
        if (!wwSp.getBoolean(WakeWords.KEY_ENABLED, WakeWords.ENABLED_DEFAULT) || WakeWords.validate(userWords, null) != null) {
            Log.i(TAG, "本地唤醒词未开启或未填写（默认关）：只用按钮 / 默认助理唤醒");
            mKws = null;
            mKwsStream = null;
            return;
        }
        try {
            File kwsDir = new File(getFilesDir(), "kws");
            File verFile = new File(kwsDir, ".ver");
            if (!kwsDir.exists() || !verFile.exists()) {
                deleteTree(kwsDir);
                unpackZip(ASSET_KWS, "kws");
                FileOutputStream fo = new FileOutputStream(verFile);
                fo.write(KWS_VER.getBytes("UTF-8"));
                fo.close();
                Log.i(TAG, "sherpa-onnx KWS 模型解压完成");
            }

            // 外置关键词表落盘（覆盖 zip 内的 keywords.txt）。
            //   单独版本戳，与模型包版本解耦 —— 以后只调词表就递增 KWS_KEYWORDS_VER 即可。
            String tokErr = WakeWords.validate(userWords, readTokens(new File(kwsDir, "tokens.txt")));
            if (tokErr != null) throw new IllegalArgumentException("唤醒词无效：" + tokErr);
            applyExternalKeywords(kwsDir, userWords);

            String enc = new File(kwsDir, "encoder.onnx").getAbsolutePath();
            String dec = new File(kwsDir, "decoder.onnx").getAbsolutePath();
            String joi = new File(kwsDir, "joiner.onnx").getAbsolutePath();
            String tok = new File(kwsDir, "tokens.txt").getAbsolutePath();
            String kw = new File(kwsDir, "keywords.txt").getAbsolutePath();

            com.k2fsa.sherpa.onnx.KeywordSpotterConfig cfg =
                    com.k2fsa.sherpa.onnx.KeywordSpotterConfig.builder()
                            .setFeatureConfig(com.k2fsa.sherpa.onnx.FeatureConfig.builder()
                                    .setSampleRate(16000).setFeatureDim(80).build())
                            .setOnlineModelConfig(com.k2fsa.sherpa.onnx.OnlineModelConfig.builder()
                                    .setTransducer(com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig.builder()
                                            .setEncoder(enc).setDecoder(dec).setJoiner(joi).build())
                                    .setTokens(tok)
                                    .setNumThreads(1)
                                    .setDebug(false)
                                    .build())
                            .setKeywordsFile(kw)
                            // **全局值必须与行内值同量纲**。
                            //
                            //   keywords.txt 每条都带行内 {:boost #threshold}
                            //   （主路径 9.0/0.05 → 中文兜底 7.0/0.25，见该文件头部实测）。
                            //   sherpa-onnx 会**优先使用行内值**，全局值只作为回落。
                            //
                            //   ★★ 实测教训（离线复算）：行内 boost 是**替代**全局值而非叠加，
                            //   且有效区间极窄（1.0~5.0 命中 0/8，7.5~9.0 命中 1/8，
                            //   12.0+ 反而回落 0/8）。所以全局回落值取有效下界 7.5 ——
                            //   若按 sherpa 官方默认 1.0 设置，任何漏写行内值的条目
                            //   都会掉进"低 boost 无效区"，表现为彻底唤不醒。
                            .setKeywordsScore(mTuning.kwsScore())
                            .setKeywordsThreshold(mTuning.kwsThreshold())
                            .setMaxActivePaths(20)
                            .setNumTrailingBlanks(2)
                            .build();

            mKws = new com.k2fsa.sherpa.onnx.KeywordSpotter(cfg);
            mKwsStream = mKws.createStream();
            Log.i(TAG, "sherpa-onnx KWS 唤醒引擎初始化就绪");

        } catch (Throwable t) {
            Log.e(TAG, "KWS 初始化异常，唤醒词不可用（按键唤醒照常）: " + t.getMessage(), t);
            mKws = null;
            mKwsStream = null;
        }
    }


    /**
     * ★ r2/screenoff-wake：一块 PCM 喂 KWS（原读循环里的整段，原样搬出来：AGC → acceptWaveform → decode → 命中裁决）。
     * @return 本块是否命中唤醒词
     */
    private boolean feedKwsChunk(short[] buf, int read, long now) {
        boolean hit = false;
        try {
            // 每 ~10s 刷新一次噪声分级（仅在变化时打日志）。
            if ((++mEnvLogCnt % ENV_LOG_EVERY_FRAMES) == 1) {
                refreshEnvNoiseLevel();
            }
            // 喂 KWS 前先做自适应增益（详见 KWS_AGC_* 常量）。
            //   注意边界：这里只变换 **KWS 的输入副本** —— 上方滚动缓冲、下方
            //   声纹录制缓冲、云端 ASR 缓冲与能量门用的全部是原始 buf。
            //   也就是说"放大的只是 KWS 这只耳朵"，所有裁决链仍看真实电平。
            float peak = 0f;
            for (int i = 0; i < read; i++) {
                int v = buf[i];
                if (v < 0) v = -v;
                if (v > peak) peak = v;
            }
            float pk = peak / 32768.0f;
            // ★ P0-4：目标峰值外置（探针 --es wakecfg "agc_target=0.5" 可调）
            final float agcTarget = (mTuning != null) ? mTuning.agcTargetPeak() : KWS_AGC_TARGET_PEAK;
            float want;
            if (pk < KWS_AGC_SILENCE_PEAK) {
                want = 1.0f;   // 绝对静音：不抬本底
            } else if (pk >= agcTarget) {
                want = 1.0f;   // 已达标（近场）：不动，防削波
            } else {
                want = agcTarget / pk;
                if (want > KWS_AGC_MAX_GAIN) want = KWS_AGC_MAX_GAIN;
            }
            mKwsAgcGain += (want - mKwsAgcGain) * KWS_AGC_SMOOTH;

            // ★ R14（diag C6）：复用 KWS 输入缓冲（读循环每 100 ms 一帧，长度基本恒定），不再每帧 new float[]
            float[] fBuf = mKwsF;
            if (fBuf == null || fBuf.length != read) mKwsF = fBuf = new float[read];
            float g = mKwsAgcGain;
            for (int i = 0; i < read; i++) {
                float s = buf[i] / 32768.0f * g;
                if (s > 1f) s = 1f;
                if (s < -1f) s = -1f;
                fBuf[i] = s;
            }
            if ((++mKwsAgcLogCnt % KWS_AGC_LOG_EVERY) == 1) {   // 每 ≈60s 一条，不刷屏
                Log.i(TAG, "[KWS-AGC] gain=" + String.format("%.2f", g)
                        + " 帧峰值=" + String.format("%.3f", pk));
            }

            mKwsStream.acceptWaveform(fBuf, 16000);
            while (mKws.isReady(mKwsStream)) {
                mKws.decode(mKwsStream);
            }

            com.k2fsa.sherpa.onnx.KeywordSpotterResult kr = mKws.getResult(mKwsStream);
            String kw = (kr != null) ? kr.getKeyword() : "";
            if (kw != null && kw.length() > 0) {
                hit = true;
                mKws.reset(mKwsStream);
                // ★ 缺陷修复（2026-10-02）：原先此处硬性要求 isSpeech（即 rms > 350 硬门限），
                //   轻声细语/远场唤醒时神经网已 100% 命中，却因瞬时能量不足被自己的门吞掉。
                // ★ R14：捕获窗内 processFrame 的返回值改按捕获窗判据，KWS 硬门改看 hardRecent（= 旧语义）
                boolean hardOk = mEnergyGate.hardRecent(now);
                boolean softOk = mEnergyGate.isSoftSpeech();

                if (now - mLastWakeAt <= 2500) {
                    Log.i(TAG, "[KWS] 命中唤醒词但处于 2500ms 防重入期，忽略: " + kw);
                } else if (speakerGateActive()) {
                    // ★P0-2（对标高通 SecondStageUV.threshold）：
                    //   **声纹升格为并列第二级** —— 不再只当"能量门拦下后的兜底"。
                    //
                    //   成熟方案的做法（魅族 21 Pro 实测举证）：
                    //     一阶关键词判定**故意放宽**（宁可误报），
                    //     二阶用 TIUV 声纹确认"是不是主人"（SecondStageUV.threshold）。
                    //     两级分离，各自专注：一阶管召回，二阶管精度。
                    //
                    //   我们的旧做法是"单级 + 兜底"：能量门放行就直接唤醒（跳过声纹），
                    //   只有能量门拦下才用声纹。问题是能量门只能回答"是不是人声"，
                    //   答不了"是不是主人"——远场轻声的真主人照样被它拦掉（这就是
                    //   "喊了没反应"），而近场的他人/电视声音反而直接放行（误唤醒）。
                    //
                    //   现在：**只要声纹可用且已注册，判定权就交给声纹**（不论能量门结果）。
                    //   能量门从"门槛"降级为"参考"（仍打进日志供分析）。
                    //   未注册声纹 / 引擎不可用 → 走下面的旧逻辑，零回退。
                    mKwsBlockedCount++;   // 复用计数器：统计"经声纹终审"的次数
                    Log.i(TAG, "[KWS] 命中唤醒词: " + kw + " (硬门=" + hardOk
                            + ", 软门=" + softOk + ") → 交声纹终审（P0-2 第二级）");
                    trySpeakerVerifiedWake(kw);
                } else if (envAccept(hardOk, softOk)) {
                    Log.i(TAG, "[KWS] 命中唤醒词: " + kw + " (硬门=" + hardOk
                            + ", 软门=" + softOk
                            + ", 噪声=" + noiseLevelName(mEnvNoiseLevel)
                            + ", 软接受加成=" + String.format("%.2f", envAcceptBonusSoft())
                            + ")");
                    wakeOnce();
                } else {
                    // 能量门拦下 → **声纹兜底**（旧路径）。
                    //   仅在"未注册声纹 / 引擎不可用"时才会走到这里（见 speakerGateActive）。
                    //   实测（02:42~02:46 日志）：主人 5 次真实唤醒因远场/轻声
                    //   被能量门吞掉 —— 这就是"喊了没反应"的直接来源。
                    //   能量门只是粗糙的"是不是人声"判据；声纹能精确回答"是不是主人"，
                    //   所以这一档交给声纹接管：通过 → 补唤醒（召回↑）；不通过 → 真忽略。
                    mKwsBlockedCount++;
                    Log.i(TAG, "[KWS] 命中但能量门未过 kw=" + kw
                            + " → 转入声纹兜底验证（累计拦下=" + mKwsBlockedCount + "）");
                    trySpeakerVerifiedWake(kw);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "KWS 处理异常: " + t.getMessage());
        }
        return hit;
    }

    /** ★ r2/screenoff-wake：息屏能量门刚打开 —— reset 流，把最近 prerollMs（含本帧）按 100 ms 一块补喂；命中即停。 */
    private void replayPrerollIntoKws(short[] buf, int read, long now) {
        try { mKws.reset(mKwsStream); } catch (Throwable ignore) {}
        int want = (mScreenPolicy != null) ? mScreenPolicy.prerollSamples(SAMPLE_RATE, WAKE_VP_BUF_CAPACITY) : 0;
        short[] pre = (mWakeRollingBuf != null && want > 0) ? mWakeRollingBuf.snapshotLast(want) : null;
        if (pre == null || pre.length == 0) {
            feedKwsChunk(buf, read, now);
            return;
        }
        if (mScreenPolicy != null && !mScreenPolicy.screenOn()) {
            Log.i(TAG, "[SCREEN-KWS] 息屏听到人声 → 开门，补喂预滚 " + (pre.length * 1000 / SAMPLE_RATE) + "ms");
        }
        short[] chunk = new short[CHUNK_SAMPLES];
        for (int off = 0; off < pre.length; off += CHUNK_SAMPLES) {
            int n = Math.min(CHUNK_SAMPLES, pre.length - off);
            System.arraycopy(pre, off, chunk, 0, n);
            if (feedKwsChunk(chunk, n, now)) break;
        }
    }

    /** 息屏门统计：息屏时每 5 分钟一条。 */
    private void maybeLogScreenGate(long now) {
        if (mScreenPolicy == null || mScreenPolicy.screenOn()) return;
        if (now - mScreenGateLogAt < 300000L) return;
        mScreenGateLogAt = now;
        Log.i(TAG, "[SCREEN-KWS] " + mScreenPolicy.stats());
    }

    /** 3. 麦克风流式采集主循环 */
    private void micLoop() {
        short[] buf = new short[CHUNK_SAMPLES];

        // 滚动缓冲（KWS 命中时回溯唤醒词音频，做声纹验证）。
        //   纯本机内存、纯本机计算 —— 与"唤醒音频永不上行"的隐私红线完全同向（不上传）。
        if (mWakeRollingBuf == null) {
            mWakeRollingBuf = new com.onesay.assistant.v2.wake.RollingAudioBuffer(WAKE_VP_BUF_CAPACITY);
        }

        while (!mAbort && mAudioRecord != null) {
            // ★ r2/screenoff-wake：暂停即真暂停（声纹注册 / 暂停前已开的捕获窗例外，收完再停）
            if (ScreenOffPolicy.micShouldPause(mPausedCached, mVpRecording || liveTap() != null, mEnrollSession,
                    SystemClock.elapsedRealtime() < mCaptureUntil, liveOwnMicActive())) {
                if (!pauseMicUntilResumed()) break;
                continue;
            }
            int read = 0;
            try {
                if (mAudioRecord != null && mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    read = mAudioRecord.read(buf, 0, buf.length);
                }
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord 读取异常: " + t.getMessage());
                read = -1;
            }

            // ★ r2/sysux R1：读结果驱动「是否真的在录」→ 唤醒锁（连续读失败 / 底层停录即放锁，恢复后再拿）
            boolean stRec = true;
            if (read <= 0 || !mMicState.recording()) {
                try { stRec = mAudioRecord != null && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING; }
                catch (Throwable ignore) { stRec = false; }
            }
            if (mMicState.onRead(read, stRec)) {
                Log.i(TAG, "[MIC] 在录=" + mMicState.recording() + "（" + mMicState.reason() + "）");
                updateWakeLock("mic-read");
            }

            if (read <= 0) {
                if (mAbort) break;
                SystemClock.sleep(20);
                continue;
            }


            // ★ 11-04 相机对话旁路：整块只交给旁路，其余（KWS / 捕获窗 / 打断检测）全不跑
            LiveMicTap liveTap = liveTap();
            if (liveTap != null) {
                try {
                    liveTap.onPcm(buf, read);
                } catch (Throwable t) {
                    Log.w(TAG, "[LIVE-MIC] 旁路回调异常: " + t.getClass().getSimpleName());
                }
                continue;
            }

            long now = SystemClock.elapsedRealtime();

            feedBargeIn(buf, read, now);   // ★ r2/sysux P1-3：播报中说话打断（只在播报中、窗没开时生效）

            // ★ D1：按键预录 —— 块尾落在 (唤醒, R14 起点] 的帧先进预录缓存（起点之前的样本在 offer 里就丢掉）。
            //   不 continue：非抑制期照旧喂能量门 / KWS（与 R14 一致）；抑制期照旧什么都不喂。
            if (mPreRoll.armed()) {
                if (mVpRecording || now >= mCaptureUntil) {
                    mPreRoll.disarm();   // 窗关了 / 声纹录制：预录作废（只在内存里，直接清掉）
                } else if (mPreRoll.wants(now)) {
                    mPreRoll.offer(buf, read, now);
                }
            }

            // ★ 核心防线 1：扬声器应答声播放期及余音衰减期（Acoustic Shield）
            // 只要扬声器正在发声，严禁将采集数据喂给 Vosk、KWS 及能量门，彻底切断声学自激链路！
            boolean isSuppressed = mIsPlayingAckSound || (now < mAckSoundEndAt);
            if (isSuppressed) {
                // ★ R14（diag P1-2）：提示音期间麦克风数据<b>不再整段丢弃</b> —— 隐私起点之后的帧照常进识别缓冲 / 流式会话
                //   （用户开口早了，开头几个字不再被吞；AEC 开着）。但仍不喂 KWS、能量门、声纹滚动缓冲：
                //   自家提示音不能被当成人声去断句 / 续期 / 唤醒。
                if (!mVpRecording && now > mCaptureFrom && now < mCaptureUntil) {
                    appendCaptureChunk(buf, read);   // ★ D1：第一块会先接上（剔过回声的）按键预录
                }
                continue;
            }

            // 滚动保留最近 ~2.5s 真实麦克风音频
            //   （只进内存、只用于本机声纹验证，不上传、不落盘）。
            //   位置在 Acoustic Shield 之后 —— 我方应答音不会被卷进来。
            if (mWakeRollingBuf != null) {
                mWakeRollingBuf.append(buf, read);
            }

            // ★录制期间**只攒声纹缓冲，其余一切全部跳过** ——
            //   捕获窗 PCM（防引导句被送云端识别/提交）、KWS、vosk、能量门、VAD 全不跑。
            //   独立缓冲，不影响云端 ASR 的 mPcmBuffer 语义；位置在 Acoustic Shield 之后，
            //   扬声器自激期（应答音）的音频不会被录进声纹。
            if (mVpRecording) {
                if (mVpRecBuffer != null) {
                    mVpRecBuffer.append(buf, read);
                }
                continue;
            }

            // 云端 ASR 音频旁路：只攒"捕获窗内"的 PCM，供离线搞不定时整段上云。
            //   与下方的 vosk 流式识别完全并行，不改动既有链路；
            //   云端未启用时这里只是往内存里扔字节，不产生任何网络与 IO 开销。
            if (now > mCaptureFrom && now < mCaptureUntil) {
                appendCaptureChunk(buf, read);   // ★ R14 同一帧推缓冲 + 流式；★ D1 第一块先接预录
            }

            // 1. 自适应能量门与人声检测
            boolean isSpeech = false;
            try {
                isSpeech = mEnergyGate.processFrame(buf, read);
            } catch (Throwable ignore) {}

            // 2. sherpa-onnx KWS 唤醒主路
            // ★ R14.1 卡顿：KWS 常驻占一个核的 60–80 %（10-15 实测，load≈8）。唤醒会话期间（按下 → 捕获窗 → 关窗后 2 s）
            //   不再跑 KWS —— 此时本来就不接受再次唤醒（2.5 s 防重入 + 捕获窗内命中也只会被忽略/打断），CPU 让给动画 / RenderThread。
            //   恢复时 reset 流，避免把暂停前后两段拼成一个假关键词。
            if (mCaptureUntil > now) mKwsPauseUntil = Math.max(mKwsPauseUntil, now + CaptureSessionPolicy.KWS_RESUME_TAIL_MS);
            boolean kwsPaused = CaptureSessionPolicy.kwsPaused(now, mCaptureUntil, mKwsPauseUntil);
            if (kwsPaused != mKwsPausedLogged) {
                mKwsPausedLogged = kwsPaused;
                if (!kwsPaused && mKws != null && mKwsStream != null) {
                    try { mKws.reset(mKwsStream); } catch (Throwable ignore) {}
                }
                Log.i(TAG, kwsPaused ? "[KWS] 唤醒会话中：暂停 KWS（让出 CPU 给界面动画）" : "[KWS] 会话结束：恢复 KWS（流已 reset）");
            }
            if (!kwsPaused && mKws != null && mKwsStream != null) {
                // ★ r2/screenoff-wake：息屏能量前置门 —— 亮屏恒 FEED（与改动前逐帧一致）；息屏安静 SKIP（不跑 KWS）；
                //   息屏由安静转为有人声 ACTIVATE：reset 流 + 补喂滚动缓冲里最近 ~1.5 s（已含本帧），唤醒词开头不被吃掉。
                int gate = (mScreenPolicy != null) ? mScreenPolicy.onFrame(now, buf, read) : ScreenOffPolicy.FEED;
                if (gate == ScreenOffPolicy.ACTIVATE) {
                    replayPrerollIntoKws(buf, read, now);
                } else if (gate == ScreenOffPolicy.FEED) {
                    feedKwsChunk(buf, read, now);
                }
                maybeLogScreenGate(now);
            }

            // 2.5 ★★ 能量门断句 —— 本地 vosk 识别链路已整体下线，本块是**唯一的断句/提交路径**。
            //
            //   "说过话 + 静音 1.6s" = 送云端裁决。不依赖任何本地识别文本；
            //   远场说话 / 纯音 / 噪声等场景同样适用（实测：比等 8s 窗超时快约 4 秒）。
            boolean inWinOnly = (now > mCaptureFrom && now < mCaptureUntil);
            if (inWinOnly) {
                // ★ R14：第一帧真人声的时刻取能量门自己记的（旧写法拿 processFrame 返回值，arm 后 2.5 s 回溯期内恒为真，
                //   于是「第一次人声」总是记在开窗第一帧，自适应断句的「已说时长」被算长）
                if (mSpeechSeenAt == 0) mSpeechSeenAt = mEnergyGate.getFirstSpeechTimestamp();
                final long lastSp = mEnergyGate.getLastSpeechTimestamp();
                // 断句阈值交给 EndpointDetector（开关 adaptive_ep 关闭时 = 固定 silence_ms，旧行为）
                final EndpointDetector ep = (mTuning != null) ? mTuning.endpointDetector()
                        : EndpointDetector.fixed(1600);
                final int silenceMs = ep.trailingSilenceMs(mSpeechSeenAt, lastSp);
                // 断句新增**前置条件** —— 必须确实听到过一段人声。
                //
                //   原判据只有"mSpeechSeenAt > 0 且 距上次人声 > 1.6s"。这两个条件在
                //   "窗口刚开/抑制刚解除、压根没听到用户说话"时也可能同时成立：
                //     · mSpeechSeenAt 由 isSpeech 置位，而 arm 之后第一帧若被环境音误判
                //       为人声，它就会被置上；
                //     · "距上次人声 > 1.6s" 在长时间无语音时恒成立。
                //   于是判"用户说完了" → 送 100ms 近空音频 → 云端幻觉（回吐热词表原文）
                //   → 该窗唯一的云端配额被烧掉，用户真实语音全丢（10-04 实测十次一次）。
                //
                //   现在加一条硬门槛：自 arm（抑制解除）以来累计人声帧 < MIN_SPEECH_FRAMES
                //   时，无论静音多久都不许断句 —— 宁可等窗自然到期，也不能把空音频送出去。
                final int speechFrames = mEnergyGate.getSpeechFrameCount();
                final int needFrames = (mTuning != null) ? mTuning.minSpeechFrames() : MIN_SPEECH_FRAMES;
                if (speechFrames > 0 && mLiveShownGen != mCaptureGen && mTuning != null && mTuning.liveState() && !rtOwns()) {
                    // ASR 通道不支持流式分句 → 给明确的"正在听"状态
                    mLiveShownGen = mCaptureGen;
                    FloatingSheetService fsl = FloatingSheetService.getInstance();
                    if (fsl != null) fsl.showListeningStateQuiet("正在听", "正在听你说…说完稍停即可");
                }
                if (mSemanticEp && rtOwns() && !mCloudFiredForThisCapture && !mGotCommand) {
                    // ★ r2/input：流式在管 → 按「已识别文字的完整度」决定要等多久尾静音（UtteranceEndpointer），服务端说「正在说」一律不断
                    semanticEndpointTick(now, lastSp, speechFrames, needFrames,
                            ep.utteranceLongEnough(mSpeechSeenAt, lastSp, speechFrames, needFrames));
                } else if (mSpeechSeenAt > 0 && lastSp > 0 && speechFrames >= needFrames && !mCloudFiredForThisCapture) {
                    // 非流式兜底也给倒计时环（尾静音 = EndpointDetector 的值）
                    mEpRequiredMs = silenceMs;
                    com.onesay.assistant.v2.ui.wake.ListenProgress.publish(
                            UtteranceEndpointer.ringProgress(Math.max(0L, now - lastSp), silenceMs), now);
                }
                if (!(mSemanticEp && rtOwns())
                        && mSpeechSeenAt > 0 && lastSp > 0 && (now - lastSp > silenceMs)
                        && speechFrames >= needFrames
                        && ep.utteranceLongEnough(mSpeechSeenAt, lastSp, speechFrames, needFrames)
                        && !mCloudFiredForThisCapture
                        && !localEndpointDeferredToRealtime(now)) {
                    mTSpeechEnd = lastSp;
                    Log.i(TAG, "[VAD] 能量门断句" + (ep.isAdaptive() ? "（自适应）" : "")
                            + " → 送云端裁决（静音 " + silenceMs + "ms；累计人声 "
                            + speechFrames + " 帧；距计时起点 " + (now - mArmAt) + "ms）");
                    if (!com.onesay.assistant.v2.wake.CaptureSessionPolicy.mayFireCloud(com.onesay.assistant.v2.net.NetState.isOnline(this))) {
                        // ★ R13 离线兜底：没网 → 不发云端（只会白等 + 报没听清），立刻「没有网络」收尾
                        endCaptureOffline("能量门断句");
                    } else if (tryCloudRecognize(null, "能量门断句")) {
                        FloatingSheetService fsc = FloatingSheetService.getInstance();
                        if (fsc != null) {
                            if (mTuning != null && mTuning.liveState()) {
                                fsc.setListeningPrompt("识别中…（已说 "
                                        + String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, lastSp - mSpeechSeenAt) / 1000f)
                                        + " 秒）");
                            } else {
                                fsc.setListeningPrompt("云端识别中…");
                            }
                        }
                    }
                } else if (mSpeechSeenAt > 0 && lastSp > 0 && (now - lastSp > silenceMs)
                        && speechFrames < needFrames) {
                    // 静音够久但人声证据不足 —— 打点，方便事后核对"是不是又出现了假断句"。
                    Log.i(TAG, "[VAD] 静音已达 " + silenceMs + "ms 但人声帧不足（" + speechFrames + "/"
                            + needFrames + "），不断句，等窗口自然到期");
                }
            }

        }
    }

    /**
     * 语音唤醒（KWS 神经网络命中 / ASR 文本命中）。
     * 与按键唤醒共用同一个幂等入口——避免"KWS 已唤醒、ASR 又判一次"造成重复开窗。
     */
    private void wakeOnce() {
        requestWakeInternal(SRC_VOICE, false);
    }

    // ================= 声纹兜底 =================

    /**
     * 能量门拦下的 KWS 命中 → **声纹兜底判定**（"拿特征把唤醒敏感度拉高"的落点）。
     *
     * <p>为什么：能量门只能回答"是不是人声"，回答不了"是不是主人"。实测
     * （02:42~02:46 日志）主人 5 次真实唤醒被它吞掉（远场/轻声时瞬时能量不足）——
     * 这就是"喊了没反应"的直接来源。声纹能精确回答"是不是主人"，于是把这一档
     * 交还给它做最终裁决：<b>声纹通过 → 补唤醒（召回↑）；不通过 → 真忽略（防旁人/电视）</b>。
     *
     * <p>安全边界（缺一不动原逻辑）：
     * <ul>
     *   <li>声纹引擎不可用 → 忽略（保持原行为）</li>
     *   <li>未注册 owner → 忽略（保持原行为，新用户零影响）</li>
     *   <li>回溯音频 < 1.2s（缓冲刚起步）→ 忽略</li>
     * </ul>
     *
     * <p>验证在独立线程执行（2.2s 音频约 0.6~0.8s），绝不阻塞采集线程；
     * {@link #mWakeVpBusy} 保证同一时刻只有一条验证在跑。
     * 每次验证都把 **cos 裸分数** 打进日志 —— 这就是阈值"专项拟合"的数据来源。
     */
    private void trySpeakerVerifiedWake(final String kw) {
        final com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                com.onesay.assistant.v2.asr.SpeakerVerifier.get(getApplicationContext());
        if (!sv.isAvailable()) {
            Log.i(TAG, "[KWS-VP] 声纹引擎不可用，维持原逻辑（忽略）");
            return;
        }
        if (!sv.hasName("owner")) {
            Log.i(TAG, "[KWS-VP] 未注册声纹，维持原逻辑（忽略）");
            return;
        }
        if (mWakeRollingBuf == null) return;

        final short[] pcm = mWakeRollingBuf.snapshotLast(WAKE_VP_WINDOW_MS * SAMPLE_RATE / 1000);
        if (pcm == null || pcm.length < WAKE_VP_MIN_SAMPLES) {
            Log.i(TAG, "[KWS-VP] 回溯音频不足（"
                    + (pcm == null ? 0 : pcm.length / 16) + "ms < 1.2s），放弃声纹兜底");
            return;
        }
        if (!mWakeVpBusy.compareAndSet(false, true)) {
            Log.i(TAG, "[KWS-VP] 上一条验证仍在进行，忽略本次（kw=" + kw + "）");
            return;
        }
        final float threshold = getWakeVpThreshold();
        com.onesay.assistant.v2.util.Threads.start("wake-vp-verify", new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    // 把送进声纹的这段音频落盘 + 打印质量指标。
                    //   起因：运行时 cos 波动极大（0.148 ~ 0.786，同一人同一模板），
                    //   怀疑"KWS 命中时刻不定 → 2200ms 窗口里有效语音占比剧烈波动"。
                    //   必须用真实数据验证，不能靠猜。开关：--ez vpdump true
                    if (com.onesay.assistant.v2.BuildVariant.IS_DEBUG && mDiagnoseVp) {   // r2/fixes 13
                        dumpVpDiagnostic(pcm, kw);
                    }
                    float[] samples = new float[pcm.length];
                    for (int i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 32768.0f;
                    final float[] emb = sv.extractFromSamples(samples, SAMPLE_RATE);
                    if (emb == null) {
                        Log.i(TAG, "[KWS-VP] 回溯=" + (pcm.length / 16) + "ms 提向量失败，忽略");
                        return;
                    }
                    float score = sv.scoreName("owner", emb);
                    boolean ok = score >= threshold;
                    long cost = SystemClock.elapsedRealtime() - t0;
                    Log.i(TAG, "[KWS-VP] 声纹兜底 kw=" + kw
                            + " 回溯=" + (pcm.length / 16) + "ms"
                            + " cos=" + String.format("%.3f", score)
                            + " 阈值=" + String.format("%.2f", threshold)
                            + " 耗时=" + cost + "ms"
                            + " → " + (ok ? "通过，补唤醒" : "不通过，忽略"));
                    if (ok && !mAbort) {
                        // 把本次判定的得分记下来 —— 后续"指令窗超时、无有效
                        //   指令"时的云端重判会拿它当身份前提（见 WAKE_VP_WEAK_COS）。
                        mLastWakeVpCos = score;
                        mLastWakeVpAt = SystemClock.elapsedRealtime();
                        wakeOnce();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[KWS-VP] 验证异常: " + t);
                } finally {
                    mWakeVpBusy.set(false);
                }
            }
        });
    }

    /**
     * 声纹终审是否可用（决定 KWS 命中后走"声纹终审"还是"能量门直放"）。
     *
     * <p>三个前提缺一即返回 false，此时退回旧逻辑（能量门判定 + 声纹兜底）——
     * 保证"未注册声纹的用户"与"引擎不可用"两种情况下行为完全不变。
     *
     * @return true = 声纹可承担第二级终审
     */
    private boolean speakerGateActive() {
        try {
            com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                    com.onesay.assistant.v2.asr.SpeakerVerifier.get(getApplicationContext());
            return sv != null && sv.isAvailable() && sv.hasName("owner");
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 环境自适应灵敏度 =================

    /**
     * 刷新环境噪声分级（由采集线程按间隔调用）。
     *
     * <p>对标：小米小爱"嘈杂场景临时调高档"、Open-XiaoAI"按环境噪声自动切换配置"。
     * 分级只在**变化时**打一条日志，避免刷屏。
     */
    private void refreshEnvNoiseLevel() {
        if (mEnergyGate == null) return;
        int lvl = mEnergyGate.getNoiseLevel();
        mEnvNoiseLevel = lvl;
        if (lvl != mEnvLastLoggedLevel) {
            mEnvLastLoggedLevel = lvl;
            Log.i(TAG, "[ENV] 环境噪声分级 → " + noiseLevelName(lvl)
                    + "（噪声底=" + String.format("%.0f", mEnergyGate.getNoiseFloor())
                    + "，自适应=" + (envAdaptOn() ? "开" : "关")
                    + "，本档软接受加成=" + String.format("%.2f", envAcceptBonusSoft()) + "）");
        }
    }

    private static String noiseLevelName(int lvl) {
        switch (lvl) {
            case 0: return "安静";
            case 1: return "普通";
            case 2: return "嘈杂";
            case 3: return "很吵";
            default: return "未知(" + lvl + ")";
        }
    }

    private boolean envAdaptOn() {
        return mTuning != null && mTuning.envAdaptEnabled();
    }

    /**
     * 环境自适应对"软接受判定"的加成系数（乘法）。
     *
     * <p><b>为什么加在"接受判定"而不是 KWS 内部阈值</b>：sherpa-onnx 的关键词阈值
     * 在引擎构造时固化（行内参数 + 全局值），**无运行时热改接口**，改它必须重建
     * KeywordSpotter（几十 MB 模型重载，代价不可接受）。因此本工程把环境自适应当作
     * **命中后的软补偿**：KWS 报命中后，是否放行由本系数参与决定 ——
     * 效果等价（嘈杂时更容易接受、安静时更严格），但没有引擎重建成本。
     *
     * <p>取值：以"普通"档（1）为基准 1.0，每偏离一级按 {@code env_adapt_pct} 调整。
     * <ul>
     *   <li>安静（0）→ &lt; 1.0：收紧，抑制安静环境下的误唤醒；</li>
     *   <li>普通（1）→ 1.0：中性；</li>
     *   <li>嘈杂（2）/ 很吵（3）→ &gt; 1.0：放宽，保住噪声中的召回。</li>
     * </ul>
     * 典型（pct=12）：安静 0.88、普通 1.00、嘈杂 1.12、很吵 1.24。
     */
    private float envAcceptBonusSoft() {
        if (!envAdaptOn()) return 1.0f;
        int pct = mTuning.envAdaptPct();
        int delta = mEnvNoiseLevel - 1;          // 相对"普通"档的偏移
        return 1.0f + (pct / 100.0f) * delta;
    }

    /**
     * 一阶（KWS）命中后是否**接受本次唤醒**（未启用声纹终审时的判定）。
     *
     * <p>这是"能量门 + 环境自适应"的合成判据，替代原先简单的 {@code hardOk || softOk}：
     * <ul>
     *   <li><b>硬门过（hardOk）</b>：确实听到人声 → 任何环境都接受（这是最可信的一档）。</li>
     *   <li><b>仅软门过（!hardOk && softOk）</b>：低声/远场，门限以下有语音迹象 →
     *       是否接受**取决于环境噪声**：
     *       <ul>
     *         <li>安静环境（分级 0）：<b>拒绝</b> —— 安静时人声信噪比高却仍过不了硬门，
     *             更可能是远处旁人说话/电视声漏进来，收紧可显著抑制安静环境误唤醒。</li>
     *         <li>普通及以上（分级 ≥1）：<b>接受</b> —— 噪声本身就会压低瞬时能量，
     *             此时软门过已属不易，应当放行（召回优先）。</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p>注意：本方法**只在声纹终审不可用时**才被调用（见上方调用链）。
     * 声纹可用时，判定权整体交给二阶声纹，与本方法无关 —— 两级架构不变。
     *
     * <p>关闭环境自适应（{@code env_adapt=0}）时退化为 {@code hardOk || softOk}，
     * 与改造前**逐字等价**（零回退）。
     */
    private boolean envAccept(boolean hardOk, boolean softOk) {
        if (hardOk) return true;
        if (!softOk) return false;
        if (!envAdaptOn()) return true;                     // 关闭自适应 → 旧行为
        return mEnvNoiseLevel >= 1;                          // 安静档（0）拒绝仅软门的命中
    }

    /**
     * 唤醒声纹兜底阈值（★ P0-4：改由 WakeTuning 统一管理，可运行时热调）。
     */
    private float getWakeVpThreshold() {
        // mTuning 在 onCreate 里就建好（早于 micLoop）；null 只是防御，回落默认值。
        // 审计 G1：删掉旧 wake_vp_cfg 兜底——它只在 mTuning == null 时读，实际从不生效。r2/fixes 15：调试探针 setvpthr 改写 WakeTuning 的 vp_pct
        //   （与这里读的是同一个键），实时生效。
        int pct = (mTuning != null) ? mTuning.vpThresholdPct() : WakeTuning.DEF_VP_PCT;
        return pct / 100.0f;
    }

    /**
     * 开启一次指令捕获窗。
     *
     * @param wantWindowMs 期望的窗长（语音唤醒 {@link #CAPTURE_MS} / 按键长按 {@link #CAPTURE_MS_KEY}）；
     *                     会被 {@link #clampCaptureWindow} 裁到整段窗硬上限之内。
     *                     ★ P1-2：续期（{@link #renewCaptureIfStillSpeaking}）只改 {@code mCaptureUntil}，
     *                     本方法里的 {@code mCaptureFrom}（+1100ms 隐私起点）**任何情况下都不动**。
     */
    private void armCaptureWindow(long now, long wantWindowMs) {
        mLastWakeAt = now;
        long windowMs = clampCaptureWindow(now, wantWindowMs);
        if (windowMs <= 0) windowMs = CAPTURE_MS;   // 兜底：不应发生
        mCaptureUntil = now + windowMs;
        mPromptPlan = planFor(mLastWakeSource);   // ★ r2/sysux P1-1：提示音 / 起点 / 预录
        mCaptureFrom = now + mPromptPlan.captureFromMs; // 隐私起点（按下即听：所有来源 +300 ms，[唤醒, +300) 由预录补上）
        focusListen();   // ★ r2/sysux P1-2：收音 = GAIN_TRANSIENT（暂停媒体）
        mSuppressAsrUntil = now + 1250;
        mSpeechSeenAt = 0L;
        mGotCommand = false;
        mLastPushedAsr = "";

        // ★能量门断句计时**不在这里**对齐。
        //
        //   v1 曾在此调用 mEnergyGate.arm()，但那是**唤醒时刻**；
        //   而麦克风真正解除抑制（Acoustic Shield 放开）是在应答音播完之后 ——
        //   实测唤醒 T0 → 解除抑制 T0+1601ms。静音阈值恰好 1.6s，于是：
        //     解除抑制那一帧，now - lastSpeech(arm 时刻) ≈ 1601ms > 1600ms
        //     → 断句判定**第一帧**即成立（实测开窗 +14ms 报"能量门断句"），
        //       送出去的是 100ms 近空音频 → 云端幻觉 → 后续真实语音全丢。
        //
        //   v2 把 arm() 挪到 playWakeSounds() 的播放完成回调里（抑制解除的那一瞬间），
        //   即断句计时起点 = 采集恢复起点，两者物理对齐。此处不再重复调用。
        //   （保留本注释是为了防止后人"觉得这里缺一句 arm"又把它加回来 —— 加回来就是回归。）

        // ★代际号递增（防跨窗迟到执行）。
        //   凡在本窗开启**之前**发出的云端请求，其结果到达时 gen 已不匹配 → 一律丢弃。
        //   背景（用户实测 bug）：窗1 说话但云端慢 → 超时提示"没听清" → 用户重按键开窗2
        //   → 窗1 的迟到结果在窗2 里被执行（凭空"打开浏览器"）。代际号根治这类错乱。
        mCaptureGen++;

        // ★ 云端 ASR：清空并开启 PCM 缓冲 —— 窗内音频攒下来整段送云端识别（识别全走云端）。
        mCloudFiredForThisCapture = false;
        mCloudInFlight = false;
        mCaptureClosed = false;
        mPcmBuffer.start();
        mRtLocalEpAt = 0L;
        mRtSpeaking = false;
        resetSegments();
        mSemanticEp = mTuning == null || mTuning.semanticEndpoint();
        mUttEp = mTuning != null ? mTuning.utteranceEndpointer() : UtteranceEndpointer.defaults();
        mEpRequiredMs = mUttEp.neutralMs();
        mEpLogged = null;
        com.onesay.assistant.v2.ui.wake.ListenProgress.clear();
        mMainHandler.removeCallbacks(mRtGraceRunnable);
        bindRealtime(mCaptureGen);   // ★ R14：把按下时预建的流式会话绑到本窗（没有就现在建）
        armKeyPreRoll();             // ★ D1：按键唤醒 → 提示音期间预录（起点 = 唤醒时刻，按键之后）

        // ★ 隐私声明：起点恒为"唤醒时刻 + 1100ms"，即唤醒词与应答音都被排除在上传之外。
        //   这行日志是给真人测试时自查用的 —— 看到它就说明本次唤醒的音频边界是对的。
        Log.i(TAG, "[PRIVACY] 唤醒判定=本地(sherpa-onnx KWS，零网络)"
                + " | 上传窗起点=唤醒+" + (mCaptureFrom - mLastWakeAt) + "ms"
                + " | 唤醒词音频在窗外(不上传) | 硬门限=" + WAKE_LOCAL_GUARD_MS + "ms"
                + (mPreRoll.armed() ? " | 按键预录起点=唤醒+" + (mPreRoll.startAt() - mLastWakeAt) + "ms（剔回声后有人声才接）" : ""));

        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        mWindowGen = mCaptureGen;   // ★ 窗到点回调属于哪个会话（迟到的到点按会话号丢弃）
        mMainHandler.postDelayed(mWindowExpiredRunnable, windowMs);

        Log.i(TAG, "[CAPTURE] 开启指令捕获窗 (+1100ms 起至 +" + windowMs + "ms"
                + "；仍在说话可续期，整段上限 " + CAPTURE_MAX_MS + "ms)");
    }

    /** 当前挂着的窗到点回调属于的会话号（armCaptureWindow 时记下）。 */
    private volatile int mWindowGen = -1;

    private final Runnable mWindowExpiredRunnable = new Runnable() {
        @Override
        public void run() {
            if (mGotCommand) return;
            if (com.onesay.assistant.v2.wake.CaptureSessionPolicy.isStale(mWindowGen, mCaptureGen)) {
                Log.i(TAG, "[CAPTURE] 丢弃迟到的窗到点（会话 #" + mWindowGen + " ≠ 当前 #" + mCaptureGen + "）");
                return;
            }
            // 录制声纹期间窗超时**不提交**（防引导句被当成指令送出去）。
            //   窗会自然过期；录制结束后用户重新唤醒即可，届时 armCaptureWindow 会覆盖状态。
            if (mVpRecording) {
                Log.i(TAG, "[VP] 录制声纹中，捕获窗超时但不提交");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < mCaptureUntil) {
                // //   续期会把本 Runnable 按新步长重挂一次，于是存在"提前跑到"的可能
                //   （消息调度/时钟抖动）。**必须把剩余时间补挂回去** ——
                //   直接 return 会让窗口**永远不再到期**，表现为麦克风一直开着。
                //   这是隐私相关的失效，宁可多补挂一次也不能漏。
                long remain = mCaptureUntil - now;
                mMainHandler.postDelayed(this, remain);
                Log.w(TAG, "[CAPTURE] 到期检查提前触发，已补挂 " + remain + "ms"
                        + "（不补挂会导致窗口永不到期）");
                return;
            }

            // 到点了，但先别急着收 —— 用户可能只是**话还没说完**。
            //   只有"能量门最近确实看见人声"才续期；没人出声时照常走下面的收窗流程。
            if (renewCaptureIfStillSpeaking(now)) return;

            // ★ r2/input：窗到点（整段上限 / 续期到头）时流式已经攒了文字 → 就用它（以前会整段重送非流式）
            if (mSemanticEp && rtOwns() && rtText(true).length() > 0 && !mGotCommand && !mCloudFiredForThisCapture && !mCaptureClosed) {
                commitRealtimeText(true, "窗到点（已说 " + (now - mLastWakeAt) + "ms）");
                return;
            }
            Log.i(TAG, "[CAPTURE] 指令窗超时闭合，未接收到有效指令");
            mCaptureUntil = 0;
            // ★ r2/sysux P1-2：这里不归还焦点 —— 下面可能还要整段送非流式出指令；没指令的出口（closeCaptureAfterCloud /
            //   没听清 / 续听安静收起）各自结束整轮，焦点看门狗兜底。

            // ★ R14 流式：服务端 VAD 与本地能量门都没听到人声 → 静音超时，不发任何识别请求，直接「没听清」收尾（面板 2.5 s 后自己收）；
            //   听到过人声却没出最终结果 → 关流式，下面照旧整段送非流式。
            com.onesay.assistant.v2.asr.RealtimeAsrPolicy.OnExpiry rx = com.onesay.assistant.v2.asr.RealtimeAsrPolicy.onWindowExpired(
                    rtOwns(), mRt != null && mRt.sawSpeech(), mEnergyGate.getSpeechFrameCount());
            if (rx == com.onesay.assistant.v2.asr.RealtimeAsrPolicy.OnExpiry.MISS_NO_SPEECH && !mGotCommand && !mCloudInFlight && !mCaptureClosed) {
                Log.i(TAG, "[ASR-RT] 窗到点：本地能量门人声帧 " + mEnergyGate.getSpeechFrameCount() + " < "
                        + com.onesay.assistant.v2.asr.RealtimeAsrPolicy.EXPIRY_MIN_SPEECH_FRAMES
                        + "（服务端 VAD " + (mRt != null && mRt.sawSpeech() ? "报过说话，按提示音尾巴处理" : "也没听到") + "）→ 静音超时，不发识别请求");
                mCloudFiredForThisCapture = true;
                closeCaptureAfterCloud("静音超时");
                notifyCaptureMiss(FloatingSheetService.getInstance());
                return;
            }
            if (rx == com.onesay.assistant.v2.asr.RealtimeAsrPolicy.OnExpiry.FALLBACK_NONSTREAM) {
                Log.i(TAG, "[ASR-RT] 窗到点：听到过人声但流式没给最终结果 → 关流式，整段送非流式");
                cancelRealtime("窗到点回退");
            }
            onCaptureClosed("窗到点");   // ★ R14：解冻底噪 + 本窗汇总（下面的非流式兜底只用已攒的缓冲）

            // ★超时重判的**身份前提**。
            //   本窗从头到尾没有本地可执行文本（离线候选恒为空 —— 本地识别链路已下线），
            //   此时送上云的整段音频里装的到底是不是"人对手机说的话"，没有任何证据。
            //   真机实测：一次压线唤醒（声纹刚够 0.40）把环境旁白整段吃了进来，
            //   超时重判把旁白识别成指令并执行（"瞬间，河面骤然炸开去了。"）。
            //   判据：本窗若产出了声纹得分且它 < WAKE_VP_WEAK_COS，说明这次唤醒
            //   只是"像主人"而不是"就是主人"——不发重判，直接按没听清收尾。
            //   未注册声纹 / 按键唤醒（mLastWakeVpAt 为本窗之前的旧值）不受影响。
            // ★ P0-4：压线下界外置（探针 --es wakecfg "vp_weak_cos=0.6" 可调）
            final float weakCos = (mTuning != null) ? mTuning.vpWeakCos() : WAKE_VP_WEAK_COS;
            boolean weakIdentity = (mLastWakeVpCos >= 0f
                    && (now - mLastWakeVpAt) <= CAPTURE_MAX_MS
                    && mLastWakeVpCos < weakCos);
            if (weakIdentity) {
                Log.i(TAG, "[CAPTURE] 声纹压线通过（cos=" + String.format("%.3f", mLastWakeVpCos)
                        + " < " + weakCos + "）→ 超时空候选不发云端重判，按没听清收尾");
                notifyCaptureMiss(FloatingSheetService.getInstance());
                return;
            }

            // ★ 云端兜底：离线整窗都没给出可执行文本，最后把整段音频送云端试一次。
            //   云端若命中，回调里会走 handleVoiceCommand，并覆盖下面这句提示。
            // ★ 在途判定：本窗早已发过云端请求、且还没执行过 → 请求还在路上。
            //   必须在 tryCloudRecognize 之前算（它可能会把"已发过"置 true）。
            // ★ R13：在途只认 mCloudInFlight（云端失败后不再误判在途、不再把面板弹成「正在想」）；没网直接「没有网络」收尾
            com.onesay.assistant.v2.wake.CaptureSessionPolicy.Expiry ex = com.onesay.assistant.v2.wake.CaptureSessionPolicy.onWindowExpired(
                    mGotCommand, mCaptureClosed, mCloudInFlight, com.onesay.assistant.v2.net.NetState.isOnline(WakeService.this));
            if (ex == com.onesay.assistant.v2.wake.CaptureSessionPolicy.Expiry.IGNORE) {
                Log.i(TAG, "[CAPTURE] 本窗已收尾（云端已回 / 没网），窗到点不再提示");
                return;
            }
            if (ex == com.onesay.assistant.v2.wake.CaptureSessionPolicy.Expiry.OFFLINE) {
                endCaptureOffline("指令窗超时");
                return;
            }
            boolean cloudInFlight = ex == com.onesay.assistant.v2.wake.CaptureSessionPolicy.Expiry.CLOUD_PENDING;
            boolean cloudTried = !cloudInFlight && tryCloudRecognize(null, "指令窗超时");

            FloatingSheetService fs = FloatingSheetService.getInstance();
            if (fs != null) {
                if (cloudTried || cloudInFlight) {
                    // 云端还在判（本次刚发出 或 早前已发出、仍在途）：切文案即可。
                    // "在途"时**不能**说"没听清"—— 误导用户重按按键，
                    //   会把上一窗的迟到结果错当新窗输入（实测 bug："按键后浏览器打开"）。
                    // 继续聆听会话用"轻提示"（只改状态行）——
                    //   完整版 setListeningPrompt 会清转录 + 藏结果卡，把上一轮问答整屏擦掉；
                    //   而识别音路完全一致（重判照发），只是面板不乱动。
                    if (mLastContinueSession) {
                        fs.showCloudBusyQuiet();
                    } else {
                        fs.setListeningPrompt("云端识别中…");
                    }
                } else {
                    // 云端这轮根本没发出去（通道关闭 / 凭据缺失 / 音频过短）→ 本次唤醒到此为止。
                    // 给一个诚实的终态文案；**不再自动收起**，面板常驻。
                    // 继续聆听会话改走轻提示（只动状态行，不抹会话正文）。
                    notifyCaptureMiss(fs);
                }
            }
        }
    };

    /**
     * 捕获窗以"没听清"收尾时的 UI 提示分流（）。
     *
     * <p>「✦ 语音」继续聆听的会话：只更新状态行（不碰转录/结果卡）——
     * 会话没结束，只是这一轮没听到；真实唤醒才走完整的"请重新唤醒"终态。
     */
    /** ★ R13：云端回调没产出命令 → 本窗立刻关闭（窗到点的收尾不再跑，麦克风焦点归还）。任何线程可调。 */
    private void closeCaptureAfterCloud(String why) {
        mCaptureClosed = true;
        mCaptureUntil = 0;
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        try { mPcmBuffer.stop(); } catch (Throwable ignore) {}
        focusTurnEnd("本窗收尾 · " + why);   // ★ r2/sysux：没出指令 = 整轮结束
        onCaptureClosed(why);
        Log.i(TAG, "[CAPTURE] 本窗收尾（" + why + "）");
    }

    /** ★ R13 离线兜底：没网 → 不发云端、关窗、立刻「没有网络」。 */
    private void endCaptureOffline(String why) {
        mCloudFiredForThisCapture = true;   // 本窗不再尝试送云
        closeCaptureAfterCloud("没有网络 · " + why);
        Log.w(TAG, "[OFFLINE] 没有网络：跳过云端识别（" + why + "），面板出「没有网络」");
        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) fs.showOfflinePrompt(mLastContinueSession);
    }

    private void notifyCaptureMiss(FloatingSheetService fs) {
        notifyCaptureMiss(fs, mCaptureGen);
    }

    /**
     * 「没听清」带会话号。会话已作废（HIDE / 点外部 / ⌨ / 新开窗把代际号 +1）→ 不再上屏，免得落到下一张卡上。
     * 云端回调在后台线程：判过一次代际号之后到这里之间也可能被作废，所以这里再判一次。
     */
    private void notifyCaptureMiss(FloatingSheetService fs, int gen) {
        if (fs == null) return;
        if (com.onesay.assistant.v2.wake.CaptureSessionPolicy.isStale(gen, mCaptureGen)) {
            Log.i(TAG, "[CAPTURE] 丢弃迟到的「没听清」（会话 #" + gen + " ≠ 当前 #" + mCaptureGen + "）");
            return;
        }
        // ★ r2/sysux P1-3：续听窗里没人说话 → 安静收起续听态（不报「没听清」），整轮结束
        if (mFollowUpActive) {
            mFollowUpActive = false;
            focusTurnEnd("续听窗没人说话");
            Log.i(TAG, "[FOLLOWUP] 续听窗内没听到说话 → 安静收起");
            fs.endFollowUpListening();
            return;
        }
        focusTurnEnd("没听清");
        // ★ r2/p0 P0-2：语音识别没配置时，任何「没听到 / 没识别出」的收尾都不说「没听清」，说「语音识别未配置」+「去配置」
        if (asrNotConfigured()) {
            Log.i(TAG, com.onesay.assistant.v2.asr.AsrFailure.logTag(com.onesay.assistant.v2.asr.AsrFailure.Kind.NOT_CONFIGURED) + " 收尾：语音识别未配置 → 不说「没听清」");
            fs.showAsrFailure(com.onesay.assistant.v2.asr.AsrFailure.Kind.NOT_CONFIGURED, mLastContinueSession, false);
            return;
        }
        if (mLastContinueSession) {
            fs.showTerminalPromptQuiet("没听清，可再说一次或点「⌨ 文字」输入");
        } else {
            fs.showTerminalPrompt("没听清指令，请重新唤醒...");
            fs.playVoiceCue(com.onesay.assistant.v2.s1.VoiceSync.nextMiss());   // ★ r2/voice-templates：新唤醒没听清说一句（续听窗没人说仍安静收起）；★ arch/voice-sync：连续第 1/2/≥3 次换 v3 ASR_MISS_1/2/MAX
        }
    }

    /** ★ 核心防线 3：Partial 实时转写向 UI 抛送安全门禁 */
    private void pushPartialToUi(String partial) {
        long now = SystemClock.elapsedRealtime();
        if (mIsPlayingAckSound || now < mAckSoundEndAt || now < mSuppressAsrUntil) {
            return; // 严禁在应答播放期及回声期向 UI 抛送任何 partial 转写！
        }
        if (WakeWordStripper.isAckSoundUtterance(partial)) {
            return;
        }
        // ★ device-1102：识别到的其实是刚才读出来的回答（TTS 回采）→ 不上屏
        if (com.onesay.assistant.v2.asr.AnswerEchoGuard.isEcho(partial, now)) {
            Log.i(TAG, "[ASR-ECHO] 中间结果是上一轮朗读的回采，不上屏 len=" + partial.length());
            clearLeakedTranscript();
            return;
        }
        // ★ fix/leak-guard：热词表 / 提示词回吐不上屏（以前这里只过滤应答音，流式中间结果里的热词表会原样上面板）
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockPartial(this, "pushPartialToUi", partial)) {
            clearLeakedTranscript();
            return;
        }

        String clean = WakeWordStripper.stripAckEcho(partial);
        if (clean.equals(mLastPushedAsr)) return;
        mLastPushedAsr = clean;

        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) {
            fs.updateTranscript(clean);
        }
    }

    /** ★ fix/leak-guard：之前已上屏的半截回吐（阈值之下的几个热词）换成省略号。 */
    private void clearLeakedTranscript() {
        if (mLastPushedAsr.length() == 0) return;
        mLastPushedAsr = "";
        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) fs.updateTranscript("…");
    }

    private void handleVoiceCommand(final String command) {
        // ★ fix/leak-guard：所有出口汇到这里 —— 最后一道：提示词 / 热词表回吐不执行、不显示，按「没听清」收尾
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockInput(this, "handleVoiceCommand", command)) {
            clearLeakedTranscript();
            closeCaptureAfterCloud("提示词回吐拦截");
            notifyCaptureMiss(FloatingSheetService.getInstance());
            return;
        }
        // ★ device-1102：整句就是上一轮朗读的回采 → 不执行，按「没听清」收尾
        if (com.onesay.assistant.v2.asr.AnswerEchoGuard.isEcho(command, SystemClock.elapsedRealtime())) {
            Log.i(TAG, "[ASR-ECHO] 识别结果是上一轮朗读的回采，不执行 len=" + (command == null ? 0 : command.length()));
            clearLeakedTranscript();
            closeCaptureAfterCloud("朗读回采拦截");
            notifyCaptureMiss(FloatingSheetService.getInstance());
            return;
        }
        mCaptureUntil = 0;
        final boolean followUp = mFollowUpActive;
        mFollowUpActive = false;
        // ★ r2/sysux P1-3：续听窗里说「好了 / 谢谢 / 没事了」= 结束这一轮（不当指令执行）
        if (followUp && ConversationTurnPolicy.isDismissPhrase(command)) {
            mPcmBuffer.stop();
            onCaptureClosed("结束语");
            focusTurnEnd("结束语");
            Log.i(TAG, "[FOLLOWUP] 续听窗里说了结束语「" + com.onesay.assistant.v2.util.LogText.u(command) + "」→ 结束本轮、收起面板");
            mMainHandler.post(new Runnable() {
                @Override public void run() {
                    FloatingSheetService fs = FloatingSheetService.getInstance();
                    if (fs != null) fs.playVoiceCue(com.onesay.assistant.v2.tts.VoiceTemplate.EXIT);   // ★ arch/voice-sync：结束语 → v3 EXIT_GOODBYE「好的」
                    if (fs != null) fs.dismissFromAssistant("结束语");
                }
            });
            return;
        }
        // ★ r2/sysux P1-2：拿到指令不再立刻归还焦点（旧：abandonAudioFocus），保持到整轮结束
        focusCommand();
        sPendingVoiceCommand = command;   // ★ r2/sysux P1-3：onSubmitCommand 认领 → 语音回合默认语音回答
        // 所有出口（一句式 / VAD 断句 / 窗内 final / 云端回调）最终都汇到这里，
        // 所以在这里统一停掉 PCM 缓冲 —— 单一收口点，不会有漏网的路径。
        mPcmBuffer.stop();
        onCaptureClosed("拿到指令");

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                FloatingSheetService fs = FloatingSheetService.getInstance();
                if (fs != null) {
                    fs.onSubmitCommand(command);
                } else {
                    FloatingSheetService.show(WakeService.this, command);
                }
            }
        });
    }

    /**
     * 云端识别 —— **唯一的识别通道**（本地 vosk 识别链路已整体下线）。
     *
     * <p>触发点：
     * <ol>
     *   <li>能量门断句（"说过话 + 静音 1.6s"）；</li>
     *   <li>指令窗超时，整段音频最后试一次。</li>
     * </ol>
     * 两条路径共用 {@link #mCloudFiredForThisCapture} 防重，
     * <b>一次捕获最多上一次云</b>（既省额度，也避免同一句话被执行两次）。
     *
     * <p>回调侧有一道 {@link #mCaptureGen} 代际校验：跨窗迟到的结果一律丢弃。
     *
     * <p>本方法在**麦克风线程**调用（断句分支）或**主线程**调用（窗超时），
     * 两者都只做一次内存快照 + 投递到线程池，不做网络 IO。
     *
     * @param offlineCandidate 仅用于日志对照（本地识别已下线，正常恒为 null）
     * @param reason           触发原因（日志用）
     * @return 是否真的发起了云端请求
     */
    private boolean tryCloudRecognize(String offlineCandidate, String reason) {
        // ★★ 隐私硬闸门（第 3 道，见 WAKE_LOCAL_GUARD_MS 注释）：发送前再验一次，
        //    确保缓冲起点确实晚于唤醒时刻 —— 唤醒音频绝不上行。
        //    正常配置下 mCaptureFrom - mLastWakeAt = 1100ms，远大于 300ms 的门限；
        //    一旦有人把窗口前移去"顺便识别唤醒词"，这里会直接拒发并报 ERROR。
        long gap = mCaptureFrom - mLastWakeAt;
        if (mLastWakeAt > 0 && gap < WAKE_LOCAL_GUARD_MS) {
            Log.e(TAG, "[PRIVACY-REJECT] 拒发云端请求：音频起点距唤醒仅 " + gap + "ms"
                    + "（< 硬门限 " + WAKE_LOCAL_GUARD_MS + "ms），可能含唤醒词音频。reason=" + reason);
            return false;
        }
        // ★ D1 隐私硬闸门：本窗若接上了按键预录，其起点必须不早于唤醒时刻（预录只在按键之后开始，不能往前挪）
        if (mPreRollEmittedGen == mCaptureGen && mPreRollEmittedStartAt < mLastWakeAt) {
            Log.e(TAG, "[PRIVACY-REJECT] 拒发云端请求：按键预录起点早于唤醒时刻 " + (mLastWakeAt - mPreRollEmittedStartAt)
                    + "ms。reason=" + reason);
            return false;
        }
        if (mCloudFiredForThisCapture) {
            Log.i(TAG, "[ASR-CLOUD] 本次捕获已发起过云端请求，跳过（reason=" + reason + "）");
            return false;
        }
        if (mAsrRouter == null) {
            return false;
        }
        if (asrNotConfigured()) {
            // 开关没开 / 凭据没配全 —— 直接明说，别让它变成一次"0ms 的网络失败"，
            // 那种日志最坑人：看起来像网络问题，其实是根本没发出去。
            // ★ r2/p0 P0-2：以前到这里只写日志，用户最后看到「没听清」。现在：窗还开着（说完话断句时）就立刻收窗 +
            //   「语音识别未配置」卡（带「去配置」、不自动收起）；窗到点时由调用方的 notifyCaptureMiss 出同一张卡。
            Log.i(TAG, "[ASR-CLOUD] 跳过：语音识别未配置（云端开关=" + mAsrRouter.config().isEnabled()
                    + "，凭据完整=" + mAsrRouter.isCloudConfigured() + "，通道=" + mAsrRouter.activeCloudName()
                    + "），reason=" + reason);
            if (mCaptureUntil > 0 && !mCaptureClosed && !mGotCommand) {
                mCloudFiredForThisCapture = true;   // 本窗不再尝试送云（能量门不会每帧再进来）
                closeCaptureAfterCloud("语音识别未配置");
                notifyCaptureMiss(FloatingSheetService.getInstance());
            }
            return false;
        }

        final boolean isRetry = mRetryPending;   // r2/p0：「重试」—— 缓冲没动过（同一会话），下面取到的就是失败那次的同一段录音
        final byte[] pcm = mPcmBuffer.snapshot();
        int durMs = mPcmBuffer.durationMs();
        // ★音频时长硬门槛 100ms → 1000ms。
        //
        //   原门槛 3200B（100ms）形同虚设：假断句送出来的近空音频**正好**是
        //   100ms/3200B —— 恰好卡在门槛内侧被放行，云端对近空音频产生幻觉
        //   （实测回吐热词表原文 440 字），然后被幻觉门丢弃。
        //   但此时 mCloudFiredForThisCapture 已置 true（配额烧掉）、mPcmBuffer 已 stop，
        //   用户随后真正说出的指令**全部丢失** —— 8 秒窗到期时日志只会写
        //   "本次捕获已发起过云端请求，跳过"。这就是"唤醒十次只出来一次"。
        //
        //   1 秒依据：最短的真实指令（"打开浏览器"）实测 ≥ 1.2s；
        //   而假断句产物实测 ≤ 240ms。1 秒把两者完全分离开，
        //   且**不烧配额**（return 在 mCloudFiredForThisCapture = true 之前），
        //   所以假断句即使发生，本窗仍保有唯一那次真正的送云机会。
        // ★ P0-4：门槛外置（探针 --es wakecfg "cloud_min_ms=800" 可调）。
        final int cloudMinMs = (mTuning != null) ? mTuning.cloudMinMs() : 1000;
        if (pcm.length < cloudMinMs * 32) { // 16kHz × 16bit = 32 字节/ms
            Log.i(TAG, "[ASR-CLOUD] 跳过：音频仅 " + durMs + "ms（< " + cloudMinMs + "ms 门槛，"
                    + "疑似假断句产物，保留本窗配额）reason=" + reason);
            return false;
        }

        mCloudFiredForThisCapture = true;
        mCloudInFlight = true;
        mPcmBuffer.stop();
        cancelRealtime("改走非流式");   // ★ R14：非流式接手 → 流式会话关掉（一次唤醒只认一路结果）
        final long tFire = SystemClock.elapsedRealtime();

        Log.i(TAG, "[ASR-CLOUD] 发起云端重判 reason=" + reason
                + " 离线候选=\"" + (offlineCandidate == null ? "" : offlineCandidate) + "\""
                + " 音频=" + durMs + "ms/" + pcm.length + "B");

        final int myGen = mCaptureGen;   // ★ 代际快照：回调时用它识别"跨窗迟到"
        mAsrRouter.recognizeCloudAsync(pcm, new com.onesay.assistant.v2.asr.AsrRouter.Callback() {
            @Override
            public void onResult(com.onesay.assistant.v2.asr.AsrResult r) {
                // ★按按键后浏览器自动打开）。
                //   场景：窗1 说话 → 送云（慢）→ 窗超时提示"没听清" → 用户再按键开窗2
                //   → 窗1 的云端结果此刻到达 —— 它属于上一轮，**绝不能在窗2 里执行**。
                if (myGen != mCaptureGen) {
                    Log.i(TAG, "[ASR-CLOUD] 丢弃跨窗迟到的云端结果（gen " + myGen
                            + " ≠ " + mCaptureGen + "）: ok=" + r.ok);
                    return;
                }
                mCloudInFlight = com.onesay.assistant.v2.wake.CaptureSessionPolicy.cloudInFlightAfterCallback();
                if (mGotCommand) {
                    Log.i(TAG, "[ASR-CLOUD] 丢弃重复的云端结果（本窗已执行过）: ok=" + r.ok);
                    return;
                }
                if (!r.ok) {
                    closeCaptureAfterCloud("云端失败");
                    // 云端失败必须**分型**，不能再一律静默回退。
                    //   这是"明明开着云端、识别还是不准"的真正来源 —— 凭据失效
                    //   （Key 被删除/撤销）或网络不可达时，云端会**每一次**都失败，
                    //   上层却悄悄退回 vosk，用户完全无从察觉。
                    //   实测：Key 被撤销后 5 次调用 5 次 HTTP 401，全部静默回退。
                    // ★ r2/p0 P0-2：按 AsrFailure 分型（未配置 / 鉴权 / 超时 / 限流 / 服务端 / 网络 / 空结果），只有空结果才说「没听清」
                    final com.onesay.assistant.v2.asr.AsrFailure.Kind kind = com.onesay.assistant.v2.asr.AsrFailure.classify(r.error);
                    final boolean authFail = kind == com.onesay.assistant.v2.asr.AsrFailure.Kind.AUTH;
                    if (authFail) {
                        com.onesay.assistant.v2.asr.AsrHealth.markAuthFailed(WakeService.this, mAsrRouter.config());   // 控制台提醒条读它（只存指纹）
                        Log.e(TAG, "[ASR-CLOUD] ★★★ 云端凭据已失效：后续云端调用将**全部**失败，"
                                + "语音识别不可用。请更新 DashScope API Key。"
                                + " err=" + r.error);
                        mCloudKeyWarned = true;
                        updateNotification("⚠ 云端 API Key 失效，语音识别不可用");
                    } else {
                        Log.i(TAG, "[ASR-CLOUD] 云端未命中（" + r.latencyMs + "ms）: " + r.error + " " + com.onesay.assistant.v2.asr.AsrFailure.logTag(kind));
                    }
                    // 录音还在 + 临时性失败 + 不是重试那一次 → 留着这段录音给「重试」
                    final boolean canRetry = !isRetry && com.onesay.assistant.v2.asr.AsrFailure.retryable(kind) && pcm.length > 0;
                    if (canRetry) {
                        mRetryGen = myGen;
                    } else {
                        clearRetry();
                    }
                    // 本地识别已整体下线 —— 云端没给出结果 = 本次唤醒彻底结束
                    //   （没有"离线候选"可回退）。
                    Log.i(TAG, "[ASR-CLOUD] 本次唤醒无任何可用结果，面板转入常驻终态（不再自动收起）");
                    FloatingSheetService fs = FloatingSheetService.getInstance();
                    if (fs != null) {
                        // 凭据失效 / 超时 / 限流 / 服务端 / 网络时别再骗用户"没听清" —— 问题不在他说了什么。
                        if (kind != com.onesay.assistant.v2.asr.AsrFailure.Kind.NO_SPEECH) {
                            showAsrFailure(fs, kind, myGen, canRetry);
                        } else {
                            // 继续聆听会话走轻提示（会话没结束，只这轮没听到）——
                            //   旧实现不论会话类型都用完整终态，会把上一轮问答整屏擦掉。
                            notifyCaptureMiss(fs, myGen);
                        }
                    }
                    return;
                }
                String text = WakeWordStripper.strip(r.text);
                if (text == null || text.trim().length() == 0) text = r.text;
                text = text.trim();
                // ★ R14.1：按键唤醒从 +300 ms 起收音，原版应答语音（约 1.55 s，「在的」/「我在」）整段进了缓冲 →
                //   非流式也要和流式一样：剥掉开头的应答语，整句只是应答语 / 热词表回吐 = 空结果（以前「在的。」被当指令执行）
                text = WakeWordStripper.stripAckEcho(text).trim();
                if (text.length() > 0 && (WakeWordStripper.isAckSoundUtterance(text)
                        || com.onesay.assistant.v2.leakguard.LeakGuardHost.blockInput(WakeService.this, "asr.cloud.final", text)   // ★ fix/leak-guard
                        || com.onesay.assistant.v2.asr.RealtimeAsrSession.isCorpusEcho(text, com.onesay.assistant.v2.asr.RealtimeAsrSession.corpusFromHotwords(com.onesay.assistant.v2.asr.AsrHotwords.get(WakeService.this))))) {
                    Log.i(TAG, "[ASR-CLOUD] 非流式结果只是应答音 / 热词回吐，当空结果: " + com.onesay.assistant.v2.util.LogText.u(text));
                    text = "";
                }
                if (text.length() == 0) {
                    // ★ R13：空结果以前静默 return —— 面板停在「正在想」没人收。现在按没听清收尾 + 关窗
                    closeCaptureAfterCloud("云端空结果");
                    notifyCaptureMiss(FloatingSheetService.getInstance(), myGen);
                    return;
                }

                // 转写合理性门。
                //   通道层（DashScopeAsrChannel）已挡掉近空音频幻觉与提示词回吐；
                //   这里再过一次，是因为**其它 ASR 通道**（备用实现）不会走那段逻辑 ——
                //   而"热词表原文变成用户指令"的后果是直接把提示词送进路由，
                //   代价远大于多加一次字符串判断。置为丢弃后按"没听清"收尾。
                if (text.startsWith(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX)) {
                    Log.w(TAG, "[HALLUCINATION] 丢弃提示词回吐（唤醒侧二次校验）: "
                            + text.length() + " 字");
                    closeCaptureAfterCloud("幻觉丢弃");
                    // 继续聆听会话走轻提示（新唤醒才用完整"请重新唤醒"终态）。
                    notifyCaptureMiss(FloatingSheetService.getInstance(), myGen);
                    return;
                }

                Log.i(TAG, "[ASR-CLOUD] 云端命中（" + r.latencyMs + "ms）: \"" + com.onesay.assistant.v2.util.LogText.u(text) + "\"");
                logLatency("非流式", tFire);
                if (mCloudKeyWarned) {   // Key 换好后云端恢复 → 把前台通知文案改回正常
                    mCloudKeyWarned = false;
                    updateNotification(sStatus);
                }
                mGotCommand = true;
                clearRetry();   // r2/p0：识别成功 → 不再可重试
                com.onesay.assistant.v2.asr.AsrHealth.markOk(WakeService.this);
                mCaptureUntil = 0;
                pushPartialToUi(text);
                handleVoiceCommand(text);
            }
        });
        return true;
    }

    // ==================== ★ R14 流式识别（qwen3-asr-flash-realtime）====================
    //
    // 主路：按下键 → prewarmRecognition 建 WebSocket（握手藏在按键 + 提示音里）→ 松手唤醒、开窗时 bindRealtime 绑到本窗代际 →
    //   micLoop 把捕获窗内的每帧同时推给 PCM 缓冲和流式会话 → 服务端 VAD（450 ms 尾静音）断句 → completed → handleRealtimeFinal。
    // 兜底（自动）：建连失败 / 服务端报错 / 中途断开 → onError 标记失败，本地能量门断句时照旧发非流式；本地已断句而流式 1.2 s 内没出结果
    //   → mRtGraceRunnable 回退非流式；窗到点听到过人声却没出结果 → 回退非流式；都没听到人声 → 静音超时、不发请求。
    // 开关：bixby_asr_cfg/realtime（默认 true）。连续 3 次失败熔断 5 分钟。

    private final Object mRtLock = new Object();
    /** KWS 输入复用缓冲（只在读循环线程用）。 */
    private float[] mKwsF;

    // ==================== ★ D1（lane-d）按键唤醒「提示音期间预录」====================
    //
    // ★ 隐私：预录只在按键之后开始，绝不往前挪 —— 起点 = 唤醒时刻（按键事件之后；守护 v8 是松手那一刻），
    //   跨过起点的那块麦克风帧，起点之前的样本直接丢；不读常听滚动缓冲 mWakeRollingBuf（那里有按键前的音频）。
    //   只给按键唤醒用；唤醒词唤醒、「✦ 语音」继续聆听都不预录，起点照旧（450 / 1100 ms）。开关 key_preroll=0 → 与 R14 完全一致。
    // 流程：armCaptureWindow 武装 → micLoop 把块尾落在 (唤醒, R14 起点] 的帧收进 mPreRoll（不喂能量门 / KWS / 声纹缓冲）→
    //   第一块 R14 捕获帧到来时 appendCaptureChunk 先 flush：ChimeEchoGate 剔除提示音回声，有人声才接到 PCM 缓冲 / 流式会话最前面，
    //   只有提示音就整段丢（上传音频与 R14 逐字节一致）。R14 的「本地能量门至少 3 帧人声」规则不变（预录帧不计入）。
    private final KeyPreRoll mPreRoll = new KeyPreRoll(SAMPLE_RATE);
    /** 本窗接上了预录的代际号（-1 = 没接）。 */
    private volatile int mPreRollEmittedGen = -1;
    /** 本窗接上的预录起点（elapsedRealtime），隐私硬闸门复核用。 */
    private volatile long mPreRollEmittedStartAt = 0L;

    /** 开窗时：按键唤醒且开关开 → 武装预录；否则解除（行为 = R14）。 */
    private void armKeyPreRoll() {
        mPreRollEmittedGen = -1;
        mPreRollEmittedStartAt = 0L;
        // ★ r2/sysux P1-1：按下即听 —— 预录从「按键专用」推广到所有唤醒来源（起点仍 = 唤醒时刻，不读常听缓冲），
        //   由 WakePromptPolicy 决定（续听窗 / 「我在」窗 / 旧版语音唤醒不预录）。
        WakePromptPolicy.Plan plan = mPromptPlan;
        if (plan == null || !plan.preRoll || mTuning == null) {
            mPreRoll.disarm();
            return;
        }
        float floor = 0f;
        try { floor = mEnergyGate.getNoiseFloor(); } catch (Throwable ignore) {}
        mPreRoll.arm(mLastWakeAt, mCaptureFrom, mTuning.preRollMs(), mTuning.chimeEchoGate(), floor);
        long downAgo = (mDownAt > 0) ? (mLastWakeAt - mDownAt) : -1;
        Log.i(TAG, "[PREROLL] " + WakePromptPolicy.kindOf(mLastWakeSource) + " 唤醒：提示音期间预录 " + (mCaptureFrom - mPreRoll.startAt()) + "ms"
                + " | 起点=唤醒+" + (mPreRoll.startAt() - mLastWakeAt) + "ms（按键之后，不早于唤醒时刻"
                + (downAgo >= 0 && downAgo < 10000 ? "；距按下 " + (mPreRoll.startAt() - mDownAt) + "ms" : "") + "）"
                + " | 提示音=" + (mTuning.shortChime() ? "短" : "原版") + " 底噪=" + (int) floor);
    }

    /**
     * 往本窗捕获缓冲 + 流式会话追加一块 R14 帧。第一块到来时先把预录 flush 掉（剔回声、决定接不接），
     * 这块帧顺便当回声分析的右侧上下文。
     */
    private void appendCaptureChunk(short[] buf, int read) {
        if (mPreRoll.armed()) emitKeyPreRoll(buf, read);
        mPcmBuffer.append(buf, read);
        feedRealtime(buf, read);   // ★ R14：同一帧同时推给流式会话（同一条隐私边界）
    }

    private void emitKeyPreRoll(short[] ctx, int ctxLen) {
        KeyPreRoll.Flushed f = mPreRoll.flush(ctx, ctxLen);
        if (f.gate == null) {
            if (f.bufferedMs > 0 || f.startAt < f.wakeAt) Log.w(TAG, "[PREROLL] 预录未处理（起点自检失败或无回声门），丢弃 " + f.bufferedMs + "ms");
            return;
        }
        // ★ 隐私复核：起点不早于本窗唤醒时刻、而且本窗计划允许预录（r2/sysux：不再只限按键）
        WakePromptPolicy.Plan plan = mPromptPlan;
        boolean privacyOk = WakePromptPolicy.preRollPrivacyOk(f.startAt, mLastWakeAt, f.wakeAt, plan != null && plan.preRoll);
        if (f.emitted() && privacyOk) {
            for (int off = 0; off < f.pcm.length; off += CHUNK_SAMPLES) {   // 按 100 ms 一块推，和正常帧同节奏
                int n = Math.min(CHUNK_SAMPLES, f.pcm.length - off);
                short[] part = new short[n];
                System.arraycopy(f.pcm, off, part, 0, n);
                mPcmBuffer.append(part, n);
                feedRealtime(part, n);
            }
            mPreRollEmittedGen = mCaptureGen;
            mPreRollEmittedStartAt = f.startAt;
            Log.i(TAG, "[PREROLL] 接上预录 " + (f.pcm.length * 1000 / SAMPLE_RATE) + "ms（起点=唤醒+" + (f.startAt - f.wakeAt)
                    + "ms）：" + f.gate + " | 逐帧 " + f.gate.trace());   // ★ LB-1：接上时也打逐帧（含窗外帧），上机看余量
        } else {
            Log.i(TAG, "[PREROLL] 预录丢弃（" + (privacyOk ? "只有提示音 / 人声不足" : "隐私复核不通过") + "，本窗音频与 R14 相同）："
                    + f.gate + " | 逐帧 " + f.gate.trace());
        }
    }

    private boolean realtimeWanted() {
        if (mAsrRouter == null) return false;
        com.onesay.assistant.v2.asr.AliyunAsrConfig c = mAsrRouter.config();
        return com.onesay.assistant.v2.asr.RealtimeAsrPolicy.shouldUseRealtime(
                c.isEnabled() && c.isRealtimeEnabled(), c.hasDashScopeKey(),
                com.onesay.assistant.v2.net.NetState.isOnline(this), mRtBreaker.isOpen(SystemClock.elapsedRealtime()));
    }

    /** 按下键（守护 v8 的 down 事件）：预建流式连接 + 预热非流式 TLS（兜底也快）。后台线程。 */
    private void prewarmRecognition(final String why) {
        com.onesay.assistant.v2.util.Threads.start("asr-prewarm-key", new Runnable() {
            @Override public void run() {
                try {
                    if (realtimeWanted()) openRealtime(-1, why);
                    else Log.i(TAG, "[ASR-RT] " + why + "：不开流式（开关关 / 没网 / 熔断中 fails=" + mRtBreaker.fails() + "）");
                    if (mAsrRouter != null) mAsrRouter.prewarmHttpAsync();
                } catch (Throwable t) {
                    Log.w(TAG, "[ASR-RT] 预热异常（不影响唤醒）: " + t);
                }
            }
        });
    }

    /** 开一个流式会话。gen = -1：按下时预建（等开窗绑定）；≥0：直接绑到这一窗。 */
    private void openRealtime(int gen, String why) {
        synchronized (mRtLock) {
            com.onesay.assistant.v2.asr.RealtimeAsrSession cur = mRt;
            if (cur != null && cur.isHealthy()) {
                if (gen < 0) return;                 // 预建：已经有一条能用的（可能开窗那边抢先建好了）
                if (mRtGen == gen) return;           // 已绑在本窗
                if (mRtGen == -1) {                  // 按下时预建的 → 绑到本窗
                    mRtGen = gen;
                    cur.markMicOpen();               // ★ device-1102：开麦闸门从「绑到本窗」算起，不从按下预建算
                    Log.i(TAG, "[ASR-RT] 绑定已预建的流式连接 gen=" + gen + " state=" + cur.state()
                            + " 建连已用 " + (System.nanoTime() / 1000000L - cur.tStart) + "ms");
                    return;
                }
            }
            if (cur != null) cur.cancel();
            com.onesay.assistant.v2.asr.AliyunAsrConfig c = mAsrRouter.config();
            String key = c.getDashScopeKey();
            if (key == null || key.length() == 0) return;
            String hw = com.onesay.assistant.v2.asr.AsrHotwords.get(this);
            com.onesay.assistant.v2.asr.RealtimeAsrSession s = new com.onesay.assistant.v2.asr.RealtimeAsrSession(
                    c.getRealtimeUrl(), key, c.getRealtimeSilenceMs(), hw,
                    com.onesay.assistant.v2.asr.RealtimeAsrPolicy.CONNECT_TIMEOUT_MS, gen, mRtListener);
            s.multiSegment(mTuning == null || mTuning.semanticEndpoint());   // ★ r2/input：段攒着，不一段就执行
            mRt = s;
            mRtGen = gen;
            if (gen >= 0) s.markMicOpen();   // ★ device-1102：直接绑窗的会话，开麦闸门从现在算
            mRtSpeaking = false;
            mTRtReady = 0L;
            mTFirstPartial = 0L;
            s.start();
            Log.i(TAG, "[ASR-RT] 建流式连接（" + why + "）model=" + c.getRealtimeModel() + " vad=" + c.getRealtimeSilenceMs()
                    + "ms gen=" + gen + " 热词=" + (hw == null ? 0 : hw.length()) + "字");
            mMainHandler.removeCallbacks(mRtIdleClose);
            if (gen < 0) mMainHandler.postDelayed(mRtIdleClose, com.onesay.assistant.v2.asr.RealtimeAsrPolicy.IDLE_CLOSE_MS);
        }
    }

    /** 开窗（armCaptureWindow）时调用：把预建的会话绑到本窗；没有就现在建（语音唤醒 / 冷启动 / 没收到按下事件）。 */
    private void bindRealtime(int gen) {
        mMainHandler.removeCallbacks(mRtIdleClose);
        try {
            com.onesay.assistant.v2.asr.RealtimeAsrSession cur = mRt;
            if (cur != null && cur.isHealthy() && mRtGen == -1) {
                openRealtime(gen, "开窗");   // 只做绑定
                return;
            }
            if (!realtimeWanted()) {
                if (cur != null) cancelRealtime("本窗不用流式");
                return;
            }
            openRealtime(gen, "开窗（没有预建）");
        } catch (Throwable t) {
            Log.w(TAG, "[ASR-RT] 绑定 / 建连异常 → 本窗走非流式: " + t);
        }
    }

    private void feedRealtime(short[] buf, int read) {
        com.onesay.assistant.v2.asr.RealtimeAsrSession rt = mRt;
        if (rt != null && mRtGen == mCaptureGen && mRtFailedGen != mCaptureGen) rt.appendShorts(buf, read);
    }

    /** 本窗的识别是否归流式管（健康、绑在本窗、本窗没失败过）。 */
    private boolean rtOwns() {
        com.onesay.assistant.v2.asr.RealtimeAsrSession rt = mRt;
        return rt != null && mRtGen == mCaptureGen && mRtFailedGen != mCaptureGen && rt.isHealthy();
    }

    private void cancelRealtime(String why) {
        synchronized (mRtLock) {
            com.onesay.assistant.v2.asr.RealtimeAsrSession rt = mRt;
            mRt = null;
            mRtGen = -1;
            mRtSpeaking = false;
            mMainHandler.removeCallbacks(mRtIdleClose);
            if (rt != null) {
                rt.cancel();
                Log.i(TAG, "[ASR-RT] 关闭流式会话（" + why + "）已发 " + rt.sentChunks() + " 帧，丢弃应答音 turn " + rt.fillerTurns() + " 条"
                        + (rt.droppedChunks() > 0 ? "，丢 " + rt.droppedChunks() + " 帧" : ""));
            }
        }
    }

    /** 本地能量门断句时：流式还在管 → 不发非流式，先等它 LOCAL_EP_GRACE_MS（只安排一次）。返回 true = 已交给流式。 */
    private boolean localEndpointDeferredToRealtime(long now) {
        if (com.onesay.assistant.v2.asr.RealtimeAsrPolicy.onLocalEndpoint(rtOwns())
                == com.onesay.assistant.v2.asr.RealtimeAsrPolicy.OnLocalEndpoint.FIRE_NONSTREAM) {
            return false;
        }
        if (mRtLocalEpAt == 0L) {
            mRtLocalEpAt = now;
            Log.i(TAG, "[ASR-RT] 本地能量门已断句（人声 " + mEnergyGate.getSpeechFrameCount() + " 帧），流式还没出最终结果 → 再等 "
                    + com.onesay.assistant.v2.asr.RealtimeAsrPolicy.LOCAL_EP_GRACE_MS + "ms");
            mMainHandler.removeCallbacks(mRtGraceRunnable);
            mMainHandler.postDelayed(mRtGraceRunnable, com.onesay.assistant.v2.asr.RealtimeAsrPolicy.LOCAL_EP_GRACE_MS);
        }
        return true;
    }

    /** 回退非流式（主线程）。 */
    private void fallbackToNonStream(String why) {
        if (mGotCommand || mCaptureClosed || mCloudFiredForThisCapture) return;
        mRtFailedGen = mCaptureGen;
        cancelRealtime(why);
        if (!com.onesay.assistant.v2.wake.CaptureSessionPolicy.mayFireCloud(com.onesay.assistant.v2.net.NetState.isOnline(this))) {
            endCaptureOffline(why);
            return;
        }
        if (tryCloudRecognize(null, why)) {
            FloatingSheetService fs = FloatingSheetService.getInstance();
            if (fs != null) fs.setListeningPrompt("云端识别中…");
        }
    }

    private final Runnable mRtGraceRunnable = new Runnable() {
        @Override
        public void run() {
            if (mGotCommand || mCaptureClosed || mCloudFiredForThisCapture || mRtLocalEpAt == 0L) return;
            // ★ r2/input：多段模式下已经有文字（已出段 / 在途半段）→ 直接用，不再白等 1.6–2 s 的非流式整段识别
            if (mSemanticEp && rtText(true).length() > 0) {
                Log.w(TAG, "[EP] 本地判说完后 " + com.onesay.assistant.v2.asr.RealtimeAsrPolicy.LOCAL_EP_GRACE_MS
                        + "ms 最后一段仍没出最终结果 → 用已有文字（含在途半段）提交");
                commitRealtimeText(true, "最后一段超时");
                return;
            }
            Log.w(TAG, "[ASR-RT] 本地断句后 " + com.onesay.assistant.v2.asr.RealtimeAsrPolicy.LOCAL_EP_GRACE_MS
                    + "ms 流式仍没出最终结果 → 回退非流式");
            fallbackToNonStream("流式超时回退");
        }
    };

    private final Runnable mRtIdleClose = new Runnable() {
        @Override
        public void run() {
            if (mRt != null && mRtGen == -1) cancelRealtime("按下后 " + com.onesay.assistant.v2.asr.RealtimeAsrPolicy.IDLE_CLOSE_MS + "ms 没等到唤醒");
        }
    };

    private final com.onesay.assistant.v2.asr.RealtimeAsrSession.Listener mRtListener = new com.onesay.assistant.v2.asr.RealtimeAsrSession.Listener() {
        @Override
        public void onReady(com.onesay.assistant.v2.asr.RealtimeAsrSession s) {
            if (s != mRt) return;
            mTRtReady = SystemClock.elapsedRealtime();
            Log.i(TAG, "[ASR-RT] 流式就绪：建连 + session.update " + (s.tReady - s.tStart) + "ms");
        }

        @Override
        public void onSpeechStarted(com.onesay.assistant.v2.asr.RealtimeAsrSession s) {
            if (s != mRt) return;
            mRtSpeaking = true;
            mRtSawSpeechGen = mCaptureGen;   // ★ 11-04 H：本窗服务端 VAD 报过说话
            mRtActivityAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "[ASR-RT] 服务端 VAD：开始说话");
        }

        @Override
        public void onSpeechStopped(com.onesay.assistant.v2.asr.RealtimeAsrSession s) {
            if (s != mRt) return;
            mRtSpeaking = false;   // 不刷新 mRtActivityAt：speech_stopped 是静音 700 ms 之后才到的，尾静音要从人声结束算
            Log.i(TAG, "[ASR-RT] 服务端 VAD：这一段说完了");
        }

        @Override
        public void onPartial(com.onesay.assistant.v2.asr.RealtimeAsrSession s, String text) {
            if (s != mRt || mRtGen != mCaptureGen || mGotCommand) return;
            if (WakeWordStripper.isAckSoundUtterance(text)) return;   // ★ 10-15：提示音尾巴的「嗯」不上屏、不算首个中间结果
            if (mTFirstPartial == 0L) {
                mTFirstPartial = SystemClock.elapsedRealtime();
                Log.i(TAG, "[ASR-RT] 首个中间结果（距可说话 " + (mTFirstPartial - mArmAt) + "ms）: " + com.onesay.assistant.v2.util.LogText.u(text));
            }
            String t = WakeWordStripper.stripAckEcho(text);
            // ★ 10-15：本地还没听到人声（<3 帧）时的中间结果多半是热词表幻觉，不上屏
            if (mEnergyGate.getSpeechFrameCount() < com.onesay.assistant.v2.asr.RealtimeAsrPolicy.EXPIRY_MIN_SPEECH_FRAMES) return;
            // ★ fix/leak-guard：在途半段是回吐 → 不记成在途文字（否则窗到点 / 手动结束会把它连同已出段一起提交）
            if (t != null && com.onesay.assistant.v2.leakguard.LeakGuardHost.blockInput(WakeService.this, "asr.rt.partial", t)) {
                mRtPartialText = "";
                clearLeakedTranscript();
                return;
            }
            if (t != null && t.trim().length() > 0) {
                mRtPartialText = t.trim();
                mRtActivityAt = SystemClock.elapsedRealtime();
                String fin = mRtFinalsJoined;
                // ★ r2/input：多段时屏上显示「已出段 + 在途半段」，不会一停顿就只剩后半句
                pushPartialToUi(fin.length() == 0 ? t.trim() : UtteranceEndpointer.joinSegments(java.util.Arrays.asList(fin, t.trim())));
            }
        }

        @Override
        public void onFinal(final com.onesay.assistant.v2.asr.RealtimeAsrSession s, final String text) {
            mMainHandler.post(new Runnable() {
                @Override public void run() {
                    handleRealtimeFinal(s, text);
                }
            });
        }

        @Override
        public void onError(final com.onesay.assistant.v2.asr.RealtimeAsrSession s, final String reason) {
            mRtBreaker.onFailure(SystemClock.elapsedRealtime());
            mMainHandler.post(new Runnable() {
                @Override public void run() {
                    if (s != mRt) {
                        Log.i(TAG, "[ASR-RT] 旧会话失败（已不用）: " + reason);
                        return;
                    }
                    boolean bound = mRtGen == mCaptureGen;
                    Log.w(TAG, "[ASR-RT] 流式失败（" + reason + "）→ 自动回退非流式；熔断计数=" + mRtBreaker.fails());
                    if (bound) mRtFailedGen = mCaptureGen;
                    cancelRealtime("流式失败");
                    // 本地已经断过句、在等流式 → 立刻发非流式；否则本地断句 / 窗到点时会自然走非流式
                    if (bound && mRtLocalEpAt > 0L) {
                        mMainHandler.removeCallbacks(mRtGraceRunnable);
                        fallbackToNonStream("流式失败回退");
                    }
                }
            });
        }
    };

    /** 流式最终文本（主线程）。和非流式成功回调做同样的校验，再交给 handleVoiceCommand。 */
    private void handleRealtimeFinal(com.onesay.assistant.v2.asr.RealtimeAsrSession s, String raw) {
        if (s != mRt || mRtGen != mCaptureGen) {
            Log.i(TAG, "[ASR-RT] 丢弃跨窗 / 已关闭会话的最终结果: " + com.onesay.assistant.v2.util.LogText.u(raw));
            return;
        }
        mRtBreaker.onSuccess();
        mMainHandler.removeCallbacks(mRtGraceRunnable);
        if (mGotCommand || mCloudFiredForThisCapture || mCaptureClosed) {
            Log.i(TAG, "[ASR-RT] 丢弃重复的最终结果（本窗已有结果 / 已改走非流式）: " + com.onesay.assistant.v2.util.LogText.u(raw));
            return;
        }
        String text = WakeWordStripper.strip(raw);
        if (text == null || text.trim().length() == 0) text = raw;
        text = WakeWordStripper.stripAckEcho(text.trim()).trim();
        if (text.length() == 0 || WakeWordStripper.isAckSoundUtterance(text)
                || text.startsWith(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX)
                || com.onesay.assistant.v2.leakguard.LeakGuardHost.blockInput(WakeService.this, "asr.rt.final", text)) {   // ★ fix/leak-guard：中段词表 / 无前缀回吐也丢（含分段入账前）
            // ★ R14 上机修复（10-15）：提示音尾巴漏进麦克风，服务端 VAD 先切出一段「嗯」。
            //   旧逻辑在这里关掉流式会话，于是本窗每次都退回非流式（流式形同虚设）。
            //   现在只丢弃这条结果、保持会话：服务端 VAD 支持一个会话多轮，用户真正的话会再出一条最终结果；
            //   本地断句后流式仍无结果 → mRtGraceRunnable 照旧回退非流式。
            Log.w(TAG, "[ASR-RT] 最终结果是应答音 / 空 / 提示词回吐（\"" + com.onesay.assistant.v2.util.LogText.u(raw) + "\"）→ 丢弃这条，流式会话保持，等下一段");
            mTFirstPartial = 0L;
            return;
        }
        // ★ R14 上机（10-15）：本地能量门几乎没听到人声（提示音尾巴 0–2 帧）却出了最终结果 → 多半是模型幻觉，不执行；
        //   关流式，交给本地断句 / 窗到点（窗到点时人声帧 <3 → 静音超时，不发请求）。
        int localFrames = mEnergyGate.getSpeechFrameCount();
        boolean srvSaw = mRtSawSpeechGen == mCaptureGen;
        if (!com.onesay.assistant.v2.asr.RealtimeAsrPolicy.finalCorroborated(localFrames, srvSaw, text.length())) {   // ★ 11-04 H：嘈杂环境放宽
            Log.w(TAG, "[ASR-RT] 流式最终结果没有本地人声佐证（人声帧 " + localFrames + "）→ 当幻觉丢弃: " + com.onesay.assistant.v2.util.LogText.u(text));
            mRtFailedGen = mCaptureGen;
            cancelRealtime("无本地人声佐证");
            return;
        }
        if (localFrames < com.onesay.assistant.v2.asr.RealtimeAsrPolicy.EXPIRY_MIN_SPEECH_FRAMES) {
            Log.i(TAG, "[ASR-RT] 本地人声帧 " + localFrames + " 偏少，但服务端 VAD 确认说话、结果 " + text.length() + " 字 → 放行（嘈杂环境底噪偏高）");
        }
        if (s.isMultiSegment()) {
            // ★ r2/input：这一段先攒着，说没说完交给读循环里的 UtteranceEndpointer（按文字完整度 + 尾静音）
            int n = addSegment(text);
            mRtPartialText = "";   // 不刷新 mRtActivityAt：段结果是静音之后才到的
            pushPartialToUi(mRtFinalsJoined);
            Log.i(TAG, "[EP] 流式第 " + n + " 段: \"" + com.onesay.assistant.v2.util.LogText.u(text) + "\" → 先攒着（完整度 " + UtteranceEndpointer.classify(mRtFinalsJoined)
                    + "，要等尾静音 " + mUttEp.requiredSilenceMs(mRtFinalsJoined) + "ms）");
            if (mRtLocalEpAt > 0L) commitRealtimeText(false, "本地已判说完、最后一段到齐");
            return;
        }
        Log.i(TAG, "[ASR-RT] 流式命中: \"" + com.onesay.assistant.v2.util.LogText.u(text) + "\"（服务端 speech_stopped→最终 "
                + (s.tSpeechStopped > 0 ? (s.tFinal - s.tSpeechStopped) + "ms" : "?") + "，已发 " + s.sentChunks() + " 帧）");
        if (mCloudKeyWarned) {
            mCloudKeyWarned = false;
            updateNotification(sStatus);
        }
        logLatency("流式", 0L);
        mCloudFiredForThisCapture = true;
        mCloudInFlight = false;
        mGotCommand = true;
        com.onesay.assistant.v2.asr.AsrHealth.markOk(WakeService.this);   // integrate：流式成功也清「Key 失效」记录（r2/p0 交接 §5.6）
        mCaptureUntil = 0;
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        pushPartialToUi(text);
        handleVoiceCommand(text);
    }

    // ================= ★ r2/input（2026-10-24）分段攒着 + 按完整度断句 + 手动结束 =================

    private void resetSegments() {
        synchronized (mRtSegments) {
            mRtSegments.clear();
            mRtFinalsJoined = "";
        }
        mRtPartialText = "";
        mRtActivityAt = 0L;
        mEpCommitPostedGen = -1;
    }

    private int addSegment(String seg) {
        synchronized (mRtSegments) {
            mRtSegments.add(seg);
            mRtFinalsJoined = UtteranceEndpointer.joinSegments(mRtSegments);
            return mRtSegments.size();
        }
    }

    private int segmentCount() {
        synchronized (mRtSegments) {
            return mRtSegments.size();
        }
    }

    /** 流式攒下的文字（includePartial = 带上在途半段）。 */
    private String rtText(boolean includePartial) {
        String fin = mRtFinalsJoined;
        String p = includePartial ? mRtPartialText : "";
        if (p == null || p.trim().length() == 0) return fin == null ? "" : fin;
        if (fin == null || fin.length() == 0) return p.trim();
        return UtteranceEndpointer.joinSegments(java.util.Arrays.asList(fin, p.trim()));
    }

    /** 读循环（采集线程）每帧：流式在管时的断句裁决。 */
    private void semanticEndpointTick(long now, long lastSp, int speechFrames, int needFrames, boolean longEnough) {
        UtteranceEndpointer.Input in = new UtteranceEndpointer.Input();
        in.now = now;
        in.firstSpeechAt = mSpeechSeenAt;
        in.lastSpeechAt = lastSp;
        in.speechFrames = speechFrames;
        in.minFrames = needFrames;
        in.longEnough = longEnough;
        in.rtOwns = true;
        in.rtSpeaking = mRtSpeaking;
        in.finals = mRtFinalsJoined;
        in.partial = mRtPartialText;
        in.rtActivityAt = mRtActivityAt;
        UtteranceEndpointer.Verdict v = mUttEp.decide(in);
        mEpRequiredMs = v.requiredMs;
        com.onesay.assistant.v2.ui.wake.ListenProgress.publish(v.ring, now);
        String tag = v.action + "/" + v.completeness + "/" + v.requiredMs;
        if (!tag.equals(mEpLogged) && (v.action != UtteranceEndpointer.Action.WAIT || v.silentMs >= UtteranceEndpointer.RING_DELAY_MS)) {
            mEpLogged = tag;
            Log.i(TAG, "[EP] " + v + "（已出段 " + segmentCount() + "，在途半段「" + com.onesay.assistant.v2.util.LogText.u(mRtPartialText) + "」，服务端"
                    + (mRtSpeaking ? "在说" : "静") + "）");
        }
        switch (v.action) {
            case COMMIT_TEXT: {
                if (mEpCommitPostedGen == mCaptureGen) return;
                mEpCommitPostedGen = mCaptureGen;
                final int gen = mCaptureGen;
                final String why = "说完（尾静音 " + v.silentMs + "/" + v.requiredMs + "ms，" + v.completeness + "）";
                mMainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (gen != mCaptureGen) return;
                        commitRealtimeText(false, why);
                    }
                });
                return;
            }
            case WAIT_FOR_FINAL:
                localEndpointDeferredToRealtime(now);
                return;
            default:
                // 又开口了（尾静音没到）：之前「等最后一段」的 grace 作废，免得 1.2 s 后半路提交
                if (mRtLocalEpAt > 0L && v.silentMs < v.requiredMs) {
                    mRtLocalEpAt = 0L;
                    mMainHandler.removeCallbacks(mRtGraceRunnable);
                    Log.i(TAG, "[EP] 用户接着说了 → 撤销「等最后一段」");
                }
        }
    }

    /** 提交流式攒下的文字（主线程）。返回 true = 已交给 handleVoiceCommand。 */
    private boolean commitRealtimeText(boolean includePartial, String why) {
        if (mGotCommand || mCloudFiredForThisCapture || mCaptureClosed) return false;
        String text = rtText(includePartial).trim();
        if (text.length() == 0) return false;
        // ★ fix/leak-guard：攒下的几段拼起来是回吐（每段单看没到阈值）→ 不提交，按「没听清」收尾
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockInput(this, "commitRealtimeText", text)) {
            resetSegments();
            mCloudFiredForThisCapture = true;
            mCloudInFlight = false;
            mMainHandler.removeCallbacks(mRtGraceRunnable);
            cancelRealtime("提示词回吐拦截");
            clearLeakedTranscript();
            closeCaptureAfterCloud("提示词回吐拦截");
            notifyCaptureMiss(FloatingSheetService.getInstance());
            return true;
        }
        com.onesay.assistant.v2.asr.RealtimeAsrSession s = mRt;
        mRtBreaker.onSuccess();
        mMainHandler.removeCallbacks(mRtGraceRunnable);
        Log.i(TAG, "[EP] 提交（" + why + "）: \"" + com.onesay.assistant.v2.util.LogText.u(text) + "\"（" + segmentCount() + " 段"
                + (includePartial && mRtPartialText.length() > 0 ? " + 在途半段" : "")
                + (s != null ? "，已发 " + s.sentChunks() + " 帧" : "") + "）");
        if (mCloudKeyWarned) {
            mCloudKeyWarned = false;
            updateNotification(sStatus);
        }
        logLatency("流式", 0L);
        com.onesay.assistant.v2.ui.wake.ListenProgress.clear();
        mCloudFiredForThisCapture = true;
        mCloudInFlight = false;
        mGotCommand = true;
        com.onesay.assistant.v2.asr.AsrHealth.markOk(WakeService.this);   // integrate：流式（分段攒着）提交成功也清「Key 失效」记录
        mCaptureUntil = 0;
        mMainHandler.removeCallbacks(mWindowExpiredRunnable);
        pushPartialToUi(text);
        handleVoiceCommand(text);
        return true;
    }

    /**
     * ★ r2/input：手动结束（点卡片 / 再按一次键）。主线程。还没说话 → 不结束（返回 false）。
     * 流式有文字 → 立刻用（含在途半段）；没文字但本地听到过人声 → 流式回退 / 非流式整段识别。
     */
    private boolean finishCaptureNow(String why) {
        if (mGotCommand || mCaptureClosed || mCloudFiredForThisCapture) return false;
        long now = SystemClock.elapsedRealtime();
        if (mCaptureUntil <= 0 || now < mCaptureFrom) return false;
        int frames = mEnergyGate.getSpeechFrameCount();
        int need = (mTuning != null) ? mTuning.minSpeechFrames() : MIN_SPEECH_FRAMES;
        if (rtOwns() && rtText(true).length() > 0) {
            Log.i(TAG, "[EP] 手动结束（" + why + "）→ 提交流式文字");
            return commitRealtimeText(true, "手动结束：" + why);
        }
        if (frames < need) {
            Log.i(TAG, "[EP] 手动结束（" + why + "）但还没听到说话（人声 " + frames + "/" + need + " 帧）→ 不结束，继续听");
            return false;
        }
        Log.i(TAG, "[EP] 手动结束（" + why + "）→ 整段送非流式（人声 " + frames + " 帧）");
        com.onesay.assistant.v2.ui.wake.ListenProgress.clear();
        if (rtOwns()) {
            fallbackToNonStream("手动结束：" + why);
            return true;
        }
        mTSpeechEnd = mEnergyGate.getLastSpeechTimestamp();
        if (!com.onesay.assistant.v2.wake.CaptureSessionPolicy.mayFireCloud(com.onesay.assistant.v2.net.NetState.isOnline(this))) {
            endCaptureOffline("手动结束");
            return true;
        }
        if (tryCloudRecognize(null, "手动结束：" + why)) {
            FloatingSheetService fs = FloatingSheetService.getInstance();
            if (fs != null) fs.setListeningPrompt("识别中…");
        }
        return true;
    }

    /** UI 入口（卡片点按「说完了」）。任意线程。 */
    public static void finishCaptureFromUi(final String why) {
        final WakeService s = sInstance;
        if (s == null) return;
        s.mMainHandler.post(new Runnable() {
            @Override public void run() {
                s.finishCaptureNow(why);
            }
        });
    }

    /** 给 UI 判断「点卡片 = 结束」是否有意义：正在收音、已听到人声或已有流式文字。 */
    /** 捕获窗此刻开着（唤醒 / 续说已受理、还没收窗）。对话窗再次唤醒复核用。 */
    public static boolean captureOpen() {
        WakeService s = sInstance;
        if (s == null) return false;
        long now = SystemClock.elapsedRealtime();
        return now < s.mCaptureUntil && !s.mCaptureClosed;
    }

    public static boolean canFinishCapture() {
        WakeService s = sInstance;
        if (s == null) return false;
        long now = SystemClock.elapsedRealtime();
        int need = (s.mTuning != null) ? s.mTuning.minSpeechFrames() : MIN_SPEECH_FRAMES;
        return CaptureSessionPolicy.finishAllowed(now < s.mCaptureUntil && now >= s.mCaptureFrom, s.mGotCommand,
                s.mCaptureClosed, s.mCloudFiredForThisCapture, s.mEnergyGate.getSpeechFrameCount(), need,
                s.rtText(true).length() > 0);
    }

    /** 本窗收尾（任何出口都走这里，幂等）：解冻底噪、打一行捕获窗汇总、关流式会话。 */
    private void onCaptureClosed(String why) {
        try {
            com.onesay.assistant.v2.ui.wake.ListenProgress.clear();   // ★ r2/input：倒计时环收掉
            mMainHandler.removeCallbacks(mRtGraceRunnable);
            if (mEnergyGate != null) {
                mEnergyGate.endCapture();
                if (mTCaptureSummaryGen != mCaptureGen) {
                    mTCaptureSummaryGen = mCaptureGen;
                    Log.i(TAG, "[CAPTURE-SUM] " + why + " | " + mEnergyGate.captureSummary());
                }
            }
            if (mRt != null && (mRtGen == mCaptureGen || mRtGen == -1 || mRtGen < mCaptureGen)) cancelRealtime(why);
        } catch (Throwable t) {
            Log.w(TAG, "[CAPTURE] 收尾异常: " + t);
        }
    }

    /**
     * 一行时延分解（elapsedRealtime，同一时钟）：按下→唤醒→可说话（提示音结束、能量门开窗）→说完（本地最后一帧人声）→出字。
     * tFire > 0 = 非流式请求发出时刻（另记请求耗时）。
     */
    private void logLatency(String path, long tFire) {
        try {
            long now = SystemClock.elapsedRealtime();
            long wake = mLastWakeAt;
            boolean keyed = mTKeyDown > 0 && wake - mTKeyDown >= 0 && wake - mTKeyDown < 5000;
            long lastSp = mEnergyGate.getLastSpeechTimestamp();
            StringBuilder sb = new StringBuilder("[ASR-T] 路径=").append(path);
            if (keyed) sb.append(" 按下→唤醒=").append(wake - mTKeyDown).append("ms");
            sb.append(" 唤醒→可说话=").append(mArmAt > 0 ? (mArmAt - wake) + "ms" : "?");
            if (keyed && mArmAt > 0) sb.append(" 按下→可说话=").append(mArmAt - mTKeyDown).append("ms");
            if (mTRtReady > 0) sb.append(" 流式就绪(距唤醒)=").append(mTRtReady - wake).append("ms");
            if (mTFirstPartial > 0 && mArmAt > 0) sb.append(" 首个中间结果(距可说话)=").append(mTFirstPartial - mArmAt).append("ms");
            if (lastSp > mArmAt && mArmAt > 0) sb.append(" 说完(本地最后人声)→出字=").append(now - lastSp).append("ms");
            if (tFire > 0) sb.append(" 非流式请求=").append(now - tFire).append("ms");
            sb.append(" 唤醒→出字=").append(now - wake).append("ms");
            Log.i(TAG, sb.toString());
        } catch (Throwable ignore) {
        }
    }

    /** 提取 Vosk N-best 所有候选 */


    // ==================== ★ r2/sysux P1-2：音频焦点（整轮对话一把，AssistAudioFocus 状态机） ====================

    /** 收音开始：GAIN_TRANSIENT（暂停媒体）。任何线程。 */
    private void focusListen() {
        applyFocus(mFocus.onListen(), "收音");
        mMainHandler.removeCallbacks(mFocusWatchdog);
    }

    /** 拿到指令：保持。 */
    private void focusCommand() {
        applyFocus(mFocus.onCommand(), "拿到指令");
        armFocusWatchdog();
    }

    /** 整轮结束：归还（幂等）。 */
    void focusTurnEnd(String why) {
        mMainHandler.removeCallbacks(mFocusWatchdog);
        mBargeIn.stop();
        applyFocus(mFocus.onTurnEnd(), "整轮结束 · " + why);
    }

    /** ★ r2/voice-templates：打断后等这么久再看有没有人接管（新唤醒 / barge-in 都在同一帧或下一帧开窗）。 */
    static final long INTERRUPT_RELEASE_DELAY_MS = 300L;

    /** 焦点看门狗：思考 / 播报状态 30 s 没有任何进展（没播报、没续听、没收起）→ 归还，免得音乐一直停着。 */
    private static final long FOCUS_WATCHDOG_MS = 30000L;
    private final Runnable mFocusWatchdog = new Runnable() {
        @Override public void run() {
            if (mFocus.state() != AssistAudioFocus.State.LISTENING && mFocus.held()) focusTurnEnd("看门狗 " + FOCUS_WATCHDOG_MS + "ms");
        }
    };

    private void armFocusWatchdog() {
        mMainHandler.removeCallbacks(mFocusWatchdog);
        mMainHandler.postDelayed(mFocusWatchdog, FOCUS_WATCHDOG_MS);
    }

    @SuppressWarnings("deprecation")
    private synchronized void applyFocus(AssistAudioFocus.Action a, String why) {
        if (a == AssistAudioFocus.Action.NONE || mAudioManager == null) return;
        try {
            if (mFocusListener == null) {
                mFocusListener = new AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int change) {
                        if (change == AudioManager.AUDIOFOCUS_LOSS) {
                            mFocus.onLoss();
                            Log.i(TAG, "[FOCUS] 焦点被别的 App 永久拿走（不再自己恢复）");
                        } else {
                            Log.i(TAG, "[FOCUS] 焦点变化 " + change);
                        }
                    }
                };
            }
            if (a == AssistAudioFocus.Action.ABANDON) {
                if (mFocusReq != null && Build.VERSION.SDK_INT >= 26) mAudioManager.abandonAudioFocusRequest(mFocusReq);
                else mAudioManager.abandonAudioFocus(mFocusListener);
                mFocusReq = null;
                Log.i(TAG, "[FOCUS] 归还音频焦点（" + why + "）");
                return;
            }
            int gain = a == AssistAudioFocus.Action.REQUEST_DUCK ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
            int r;
            if (Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(gain)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(mFocusListener, mMainHandler)
                        .setWillPauseWhenDucked(false)
                        .build();
                r = mAudioManager.requestAudioFocus(req);
                mFocusReq = req;
            } else {
                r = mAudioManager.requestAudioFocus(mFocusListener, AudioManager.STREAM_MUSIC, gain);
            }
            Log.i(TAG, "[FOCUS] " + (gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ? "GAIN_TRANSIENT（暂停媒体）" : "GAIN_TRANSIENT_MAY_DUCK（压低媒体）")
                    + " → " + (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "granted" : "r=" + r) + "（" + why + "）");
        } catch (Throwable t) {
            Log.w(TAG, "[FOCUS] 焦点操作异常（" + why + "）: " + t);
        }
    }

    /** 兼容旧调用点：归还 = 整轮结束。 */
    public void abandonAudioFocus() {
        focusTurnEnd("abandonAudioFocus");
    }

    // ==================== ★ r2/sysux P1-3：语音回答 / 续听 / 打断 ====================

    /**
     * ★ fix/leak-guard debug 直测（release 不生效）：把一段文字当成识别结果走「中间结果上屏 → 指令入口」两道接入点，
     * 看 [LEAK-GUARD] 日志和面板是否按「没听清」收尾。不发云端、不开麦。
     */
    public static boolean debugLeakInject(final String text) {
        final WakeService w = sInstance;
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG || w == null || text == null) return false;
        w.mMainHandler.post(new Runnable() {
            @Override public void run() {
                w.pushPartialToUi(text);
                w.handleVoiceCommand(text);
            }
        });
        return true;
    }

    /** ★ r2/voice-templates debug 直测：把下一条注入的指令当成刚识别出来的语音指令（release 不生效）。 */
    public static void markVoiceCommandForTest(String command) {
        if (com.onesay.assistant.v2.BuildVariant.IS_DEBUG) sPendingVoiceCommand = command;
    }

    /** FloatingSheetService.onSubmitCommand 认领：这条指令是不是刚才语音识别出来的（是 → 语音回合）。 */
    public static boolean claimVoiceTurn(String command) {
        String p = sPendingVoiceCommand;
        sPendingVoiceCommand = null;
        return p != null && command != null && p.trim().equals(command.trim());
    }

    /** TTS 开始出声（TtsSpeaker 回调）。 */
    public static void onReplySpeechStart() {
        WakeService s = sInstance;
        if (s == null) return;
        if (liveTap() != null) return;   // ★ 11-04 相机对话自己管打断 / 续听
        s.applyFocus(s.mFocus.onSpeakStart(), "开始播报");
        s.armFocusWatchdog();
        if (s.mTuning != null && s.mTuning.bargeIn() && com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurn()) {
            float floor = 0f;
            try { floor = s.mEnergyGate.getNoiseFloor(); } catch (Throwable ignore) {}
            s.mBargeIn = s.mTuning.bargeInDetector();
            s.mBargeIn.start(SystemClock.elapsedRealtime(), floor);
            Log.i(TAG, "[BARGE] 播报中可开口打断（标定 " + BargeInDetector.DEF_CALIBRATE_MS + "ms 回声基线，底噪 " + (int) floor + "）");
        }
    }

    /**
     * TTS 播完 / 被打断（TtsSpeaker 回调）。interrupted = stopAll（新唤醒 / 说话打断 / 关开关）—— 打断者接管这一轮，这里什么都不做。
     */
    public static void onReplySpeechDone(final boolean interrupted) {
        final WakeService s = sInstance;
        if (s == null) return;
        if (liveTap() != null) return;   // ★ 11-04 相机对话：播完不开面板续听窗
        s.mBargeIn.stop();
        if (interrupted) {
            // ★ r2/voice-templates：打断者接管时它会马上开收音窗（LISTENING）；没人接管（ttsstop / 关开关 / 文字回合顶掉）
            //   时状态还停在 SPEAKING —— 原来要等 30 s 看门狗才归还 duck 焦点，期间媒体一直被压低。稍等一拍再判，给接管者让路。
            s.mMainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    boolean capturing = s.mCaptureUntil > SystemClock.elapsedRealtime();
                    if (ConversationTurnPolicy.releaseFocusAfterInterrupt(
                            s.mFocus.state() == AssistAudioFocus.State.SPEAKING && !com.onesay.assistant.v2.ui.TtsSpeaker.isSpeaking(),
                            s.mFocus.held(), capturing)) {
                        s.focusTurnEnd("播报被打断、没有新一轮接管");
                    }
                }
            }, INTERRUPT_RELEASE_DELAY_MS);
            return;
        }
        s.mMainHandler.post(new Runnable() {
            @Override public void run() {
                FloatingSheetService fs = FloatingSheetService.getInstance();
                boolean visible = fs != null && fs.isCardShowing();
                boolean island = fs != null && fs.isCardOnIsland();
                boolean chat = fs != null && fs.chatWindowOpen();
                boolean ok = ConversationTurnPolicy.shouldFollowUp(s.mTuning != null && s.mTuning.followUp(),
                        com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurn(), false, visible, island, s.mPausedCached)
                        && com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.autoFollowUp(chat);   // 对话窗开着不自动开录音
                if (ok) {
                    s.requestFollowUpInternal("followup-tts");
                } else {
                    s.focusTurnEnd("播报结束（不续听：voiceTurn=" + com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurn()
                            + " 面板=" + visible + " 岛=" + island + "）");
                }
            }
        });
    }

    /** 结果出来了但不播报（文字回合 / 语音回复关 / 引擎不可用）：整轮结束。 */
    public static void onReplyNotSpoken(String why) {
        WakeService s = sInstance;
        if (s == null) return;
        if (s.mCaptureUntil > SystemClock.elapsedRealtime()) return;   // 已经在下一轮收音了（迟到的结果），别把焦点还掉
        s.focusTurnEnd("结果不播报 · " + why);
    }

    /** 面板收起 / 进岛 / 退场：整轮结束（FloatingSheetService 调）。 */
    public static void onPanelGone(String why) {
        WakeService s = sInstance;
        if (s == null) return;
        if (s.mCaptureUntil > SystemClock.elapsedRealtime() && !s.mGotCommand) return;   // 还在收音（新唤醒挂窗前的收起动画）
        if (com.onesay.assistant.v2.ui.TtsSpeaker.isSpeaking()) return;   // 回执还在播（面板 220 ms 让位）：播完的回调再结束整轮
        s.focusTurnEnd("面板 " + why);
    }

    /** 续听窗：不响提示音、不预录、只有光效；起点 = 开窗 + 300 ms 隐私硬门限。没人说 → notifyCaptureMiss 安静收起。 */
    private synchronized void requestFollowUpInternal(String source) {
        if (mVpRecording || mEnrollSession || mPausedCached) {
            focusTurnEnd("续听被拒（声纹 / 暂停）");
            return;
        }
        if (liveTap() != null) return;   // ★ 11-04 相机对话中不开面板续听
        long now = SystemClock.elapsedRealtime();
        if (now < mCaptureUntil) return;   // 已经有窗开着（刚又唤醒了）
        mLastWakeReqAt = now;
        mLastWakeSource = source;
        mLastWakeReqConversation = false;
        mLastContinueSession = true;
        mFollowUpActive = true;
        int ms = mTuning != null ? mTuning.followUpMs() : ConversationTurnPolicy.DEF_FOLLOWUP_MS;
        armCaptureWindow(now, ms);
        playWakeSounds(false);   // plan.chime == NONE：只对齐断句计时，不出声
        Log.i(TAG, "[FOLLOWUP] 开续听窗 " + ms + "ms（src=" + source + "，不响提示音，没人说就安静收起）");
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                FloatingSheetService fs = FloatingSheetService.getInstance();
                if (fs != null) fs.beginFollowUpListening();
            }
        });
    }

    /** 读循环：播报中、窗没开时喂打断检测。 */
    private void feedBargeIn(short[] buf, int read, long now) {
        BargeInDetector d = mBargeIn;
        if (!d.active() || now < mCaptureUntil) return;
        if (!d.offer(now, EnergyGate.rmsOf(buf, read))) return;
        Log.i(TAG, "[BARGE] 播报中听到说话（阈值 " + (int) d.threshold() + "）→ 打断播报、开续听窗");
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
                requestFollowUpInternal("followup-bargein");
            }
        });
    }

    private boolean screenInteractiveNow() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★ r2/sysux P1-1：本窗计划。 */
    private WakePromptPolicy.Plan planFor(String source) {
        boolean instant = mTuning == null ? WakeTuning.DEF_SHORT_CHIME != 0 : mTuning.shortChime();
        boolean ack = mTuning != null && mTuning.wakeAckVoice();
        boolean pre = mTuning == null ? WakeTuning.DEF_KEY_PREROLL != 0 : mTuning.keyPreRoll();
        WakePromptPolicy.Plan p = WakePromptPolicy.plan(WakePromptPolicy.kindOf(source), instant, ack, pre, mScreenOnAtWake,
                WAKE_LOCAL_GUARD_MS, CAPTURE_FROM_OFFSET_MS);
        Log.i(TAG, "[PROMPT] src=" + source + " 息屏唤醒=" + !mScreenOnAtWake + " → " + p);
        return p;
    }

    /** 异步播放唤醒提示音与应答语音（独立线程执行，绝不阻塞麦克风循环，防自听自激） */
    public void playWakeSounds(final boolean alt) {
        WakePromptPolicy.Plan plan = mPromptPlan;
        if (plan != null && plan.chime == WakePromptPolicy.Chime.NONE) {
            // ★ r2/sysux 续听窗：不出声，断句计时从此刻起
            mIsPlayingAckSound = false;
            mArmAt = SystemClock.elapsedRealtime();
            mEnergyGate.armCapture(mArmAt);
            mAckSoundEndAt = mArmAt;
            mSuppressAsrUntil = mArmAt;
            return;
        }
        if (plan != null ? plan.chime == WakePromptPolicy.Chime.SHORT : (mTuning != null && mTuning.shortChime())) {
            playShortChime(plan != null && plan.ackVoice, alt);
            return;
        }
        mIsPlayingAckSound = true;
        long now = SystemClock.elapsedRealtime();
        // 乐观初值 1250 → 1700。
        //   实测 wake_ack1/2.wav 实际播放时长 1.6~1.7s（唤醒 T0 → 解除抑制 T0+1601ms），
        //   而 1250 的初值**短于**真实时长 —— 若播放完成回调因任何原因迟到，
        //   抑制会以 1250 为准提前解除，把应答音尾音当成"用户说话"喂进能量门。
        //   1700 覆盖实测上限；正常路径下这段仍会被下面的 finally 按真实时刻精确改写。
        mAckSoundEndAt = now + 1700;
        mSuppressAsrUntil = now + 1750;

        com.onesay.assistant.v2.util.Threads.start("wakeservice", new Runnable() {
            @Override
            public void run() {
                try {
                    mAckAbort = false;
                    // ★ r2/sysux：焦点已在 armCaptureWindow → focusListen() 申请（GAIN_TRANSIENT）
                    playAssetSync("start_tone.ogg");
                    if (!mAckAbort) playAssetSync(alt ? "wake_ack2.wav" : "wake_ack1.wav");
                    if (!mAckAbort) SystemClock.sleep(120); // 留存余音物理衰减期
                } finally {
                    // ★//   抑制解除的这一瞬间，才是指令音频真正开始被采集的起点。
                    //   断句用的"最后人声时刻"必须对齐到这里 —— 否则：
                    //     arm 记在唤醒时刻 T0，解除抑制已是 T0+1600ms，
                    //     而静音阈值恰好 1.6s → 解除抑制后第一帧就满足"静音超时"，
                    //     触发假断句（实测间隔 14ms），送出 100ms 近空音频。
                    //   放在这里而非 armCaptureWindow()，是因为只有本线程知道
                    //   MediaPlayer 真正播放完的时刻（应答音时长 1.6~1.7s，与 1250 的
                    //   乐观估计相差数百毫秒，这数百毫秒正是假断句的温床）。
                    mArmAt = SystemClock.elapsedRealtime();   // ★ 供断句日志核对时差
                    mEnergyGate.armCapture(mArmAt);   // ★ R14：同时冻结底噪（捕获窗内不再被人声抬高）
                    mAckSoundEndAt = SystemClock.elapsedRealtime();
                    mIsPlayingAckSound = false;
                    Log.i(TAG, "[ACK-AUDIO] 唤醒应答声播放完成，麦克风正式解除抑制并开窗"
                            + "（能量门断句计时已对齐到此刻，armAt=" + mArmAt + "）");
                }
            }
        });
    }

    /** ★ R10 方案 C：掐断正在播的唤醒应答音（守护 v7 长按兜底；v8 下根本没响）。 */
    private void abortAckSound() {
        mAckAbort = true;
        MediaPlayer mp = mAckPlayer;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignore) {}
        }
        Log.i(TAG, "[ACK-AUDIO] 长按：掐断唤醒应答音（守护 v7 兜底）");
    }

    // ==================== 短提示音模式（开关 short_chime，默认关） ====================

    /** 本窗"正在听"状态已显示过的代际号（每窗只提示一次）。 */
    private volatile int mLiveShownGen = -1;

    /** 隐私起点：短提示音模式 450ms（仍 ≥ WAKE_LOCAL_GUARD_MS），否则沿用 1100ms。 */

    /**
     * ★ R14 隐私起点（纯函数，可单测）：按键唤醒的音频里没有唤醒词（松手才唤醒，按下前的常听流不进缓冲），
     * 起点放到硬门限 {@link #WAKE_LOCAL_GUARD_MS}（300 ms）—— 提示音期间开口的字也能收进来；
     * 语音唤醒照旧（短提示音 450 ms / 长应答音 1100 ms），唤醒词尾巴不会进缓冲。
     */
    public static long captureFromOffsetMs(boolean keySource, boolean shortChime) {
        return CaptureSessionPolicy.captureFromOffsetMs(keySource, shortChime,
                WAKE_LOCAL_GUARD_MS, WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS, CAPTURE_FROM_OFFSET_MS);
    }

    /**
     * ~160ms 合成 earcon（{@link Earcon}），AudioTrack 静态模式播放（无解码/prepare），
     * 播完 +80ms 尾音余量即解除抑制并对齐断句计时 —— 与旧路径的 finally 语义一致。
     */
    private void playShortChime(final boolean ackVoice, final boolean alt) {
        mIsPlayingAckSound = true;
        long now = SystemClock.elapsedRealtime();
        mAckSoundEndAt = now + (ackVoice ? 1900 : 450);      // 乐观上限；finally 里按真实时刻改写
        mSuppressAsrUntil = now + (ackVoice ? 1900 : 450);
        com.onesay.assistant.v2.util.Threads.start("ShortChime", new Runnable() {
            @Override
            public void run() {
                android.media.AudioTrack track = null;
                try {
                    mAckAbort = false;
                    short[] pcm = Earcon.wakeChime();   // ★ int/1102：WakeCue 来源（厂商原声 / 方案 A / 旧版）
                    Log.i(TAG, "[WAKE-CUE] 播放 " + WakeCue.describe());
                    track = new android.media.AudioTrack.Builder()
                            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new android.media.AudioFormat.Builder()
                                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(Earcon.SAMPLE_RATE)
                                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                    track.write(pcm, 0, pcm.length);
                    track.play();
                    SystemClock.sleep(Earcon.durationMs(pcm, Earcon.SAMPLE_RATE) + 80);
                    // ★ r2/sysux：「我在」可选项（息屏 + 语音唤醒）——边说边听：捕获窗起点不等它（+300 ms 起照收，AEC 开着，
                    //   识别文本里的回读由 WakeWordStripper.stripAckEcho 剥掉）；只是能量门 / 断句 / 续期在它播完前不认人声。
                    if (ackVoice && !mAckAbort) playAssetSync(alt ? "wake_ack2.wav" : "wake_ack1.wav");
                } catch (Throwable t) {
                    Log.w(TAG, "[ACK-AUDIO] 短提示音播放异常（不影响开窗）: " + t.getMessage());
                } finally {
                    if (track != null) {
                        try { track.stop(); } catch (Throwable ignore) {}
                        try { track.release(); } catch (Throwable ignore) {}
                    }
                    mArmAt = SystemClock.elapsedRealtime();
                    mEnergyGate.armCapture(mArmAt);   // ★ R14：冻结底噪
                    mAckSoundEndAt = mArmAt;
                    mIsPlayingAckSound = false;
                    Log.i(TAG, "[ACK-AUDIO] 短提示音" + (ackVoice ? "+「我在」" : "") + "完成，麦克风解除抑制（唤醒后 "
                            + (mArmAt - mLastWakeAt) + "ms，隐私起点=唤醒+" + (mCaptureFrom - mLastWakeAt) + "ms）");
                }
            }
        });
    }

    private void playAssetAsync(final String assetName) {
        com.onesay.assistant.v2.util.Threads.start("wakeservice", new Runnable() {
            @Override
            public void run() {
                playAssetSync(assetName);
            }
        });
    }

    private void playAssetSync(String assetName) {
        MediaPlayer mp = null;
        AssetFileDescriptor afd = null;
        try {
            mp = new MediaPlayer();
            // ★ 应答音走助手/系统音通道（USAGE_ASSISTANCE_SONIFICATION），
            //   不跟随媒体音量：否则用户把音乐静音后，助手的"我在/完成"提示也会一起消失。
            //   （2026-10-02 实测：旧实现走 STREAM_MUSIC，media 音量=0 时唤醒/完成应答全静默。）
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            afd = getAssets().openFd(assetName);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close(); // 尽早关闭句柄
            afd = null;
            mp.prepare();
            if (mAckAbort) return;
            mAckPlayer = mp;
            mp.start();
            // 在子线程等待播放完成释放，不阻塞主采集线程（★ R10：分段睡，长按掐断时提前结束）
            int dur = mp.getDuration();
            long until = SystemClock.elapsedRealtime() + Math.min(dur > 0 ? dur + 40 : 900, 3000);
            while (!mAckAbort && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
        } catch (Throwable t) {
            Log.w(TAG, "播放音频异常 [" + assetName + "]: " + t.getMessage());
        } finally {
            if (mAckPlayer == mp) mAckPlayer = null;
            if (mp != null) {
                try { mp.release(); } catch (Throwable ignore) {}
            }
            if (afd != null) {
                try { afd.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * ★ r2/screenoff-wake：PARTIAL_WAKE_LOCK 精细化 —— 只在「没暂停 且 息屏」时持有（{@link ScreenOffPolicy#wantPartialWakeLock}）。
     * 亮屏时 CPU 本来就醒着；暂停时读循环已 stop() 录音，没东西要听。旧实现 onCreate 拿了永不释放（THERMAL-TRIAGE #2：24 h LONG）。
     * ★ r2/sysux R1：再加「麦克风真的在录」（{@link MicRecordingState}）——初始化失败 / 没在录 / 暂停都不持有，恢复成功后再获取。
     * 非引用计数：多次 acquire / release 不会叠加。
     */
    private void updateWakeLock(String why) {
        boolean screenOn = mScreenPolicy != null && mScreenPolicy.screenOn();
        boolean want = ScreenOffPolicy.wantPartialWakeLock(mPausedCached, screenOn, mMicState.recording());
        synchronized (mWakeLockSync) {
            try {
                if (mWakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return;
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bixbyui:wakelock");
                    mWakeLock.setReferenceCounted(false);
                }
                boolean held = mWakeLock.isHeld();
                if (want && !held) {
                    mWakeLock.acquire();
                    Log.i(TAG, "[WAKELOCK] 获取 PARTIAL（" + why + "：息屏常听）");
                } else if (!want && held) {
                    mWakeLock.release();
                    Log.i(TAG, "[WAKELOCK] 释放 PARTIAL（" + why + "：" + (mPausedCached ? "已暂停"
                            : !mMicState.recording() ? "麦克风未在录：" + mMicState.reason() : "亮屏") + "）");
                }
            } catch (Throwable t) {
                Log.w(TAG, "WakeLock 更新异常(" + why + "): " + t.getMessage());
            }
        }
    }

    /** WakeTuning screenoff_* → 策略配置（息屏时重读，调参不用重启服务）。 */
    private ScreenOffPolicy.Config screenOffConfig() {
        try {
            if (mTuning != null) return mTuning.screenOffConfig();
        } catch (Throwable t) {
            Log.w(TAG, "[SCREEN] 读取 screenoff_* 调参失败，用默认: " + t);
        }
        return ScreenOffPolicy.Config.defaults();
    }

    /** ★ r2/screenoff-wake：动态注册 SCREEN_OFF / SCREEN_ON / USER_PRESENT，初始化屏幕、锁屏、暂停状态与锁屏探针。 */
    private void initScreenState() {
        mScreenPolicy = new ScreenOffPolicy(screenOffConfig());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mScreenPolicy.setScreenOn(pm == null || pm.isInteractive());
        } catch (Throwable ignore) {}
        mScreenPolicy.setKeyguardLocked(keyguardLockedNow());
        final Context app = getApplicationContext();
        ScreenOffPolicy.setKeyguardProbe(new ScreenOffPolicy.KeyguardProbe() {
            @Override public boolean locked() {
                android.app.KeyguardManager km = (android.app.KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
                return km != null && km.isKeyguardLocked();
            }
        });
        mPausedCached = AssistantPause.isPaused(this);
        try {
            mPauseListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
                    if (AssistantPause.KEY.equals(key)) onPauseChanged("prefs");
                }
            };
            getSharedPreferences(AssistantPause.PREF, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(mPauseListener);
        } catch (Throwable t) {
            Log.w(TAG, "[PAUSE] 注册暂停监听失败: " + t);
        }
        try {
            mScreenReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    onScreenIntent(intent != null ? intent.getAction() : null);
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(mScreenReceiver, f);
            com.onesay.assistant.v2.jobs.InstallWaitHooks.attach(this);   // ★ install-wait：恢复等待任务 + 动态 PACKAGE_ADDED
        } catch (Throwable t) {
            Log.w(TAG, "[SCREEN] 注册屏幕广播失败（息屏门不生效，等同亮屏行为）: " + t);
            mScreenReceiver = null;
            mScreenPolicy.setScreenOn(true);
        }
        Log.i(TAG, "[SCREEN] 初始 screenOn=" + mScreenPolicy.screenOn() + " locked=" + mScreenPolicy.keyguardLocked()
                + " paused=" + mPausedCached + " | " + mScreenPolicy.config());
    }

    private boolean keyguardLockedNow() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private void onScreenIntent(String action) {
        if (action == null || mScreenPolicy == null) return;
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            mScreenPolicy.setConfig(screenOffConfig());
            mScreenPolicy.setScreenOn(false);
            mScreenPolicy.setKeyguardLocked(keyguardLockedNow());
            com.onesay.assistant.v2.ui.KeyguardHostActivity.finishIfShowing("screen-off");
            Log.i(TAG, "[SCREEN] 息屏 → KWS 走能量前置门 | " + mScreenPolicy.stats());
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            mScreenPolicy.setScreenOn(true);
            mScreenPolicy.setKeyguardLocked(keyguardLockedNow());
            Log.i(TAG, "[SCREEN] 亮屏 → KWS 恢复逐帧 | " + mScreenPolicy.stats());
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            mScreenPolicy.setKeyguardLocked(false);
            Log.i(TAG, "[SCREEN] 已解锁");
        }
        updateWakeLock("screen:" + action.substring(action.lastIndexOf('.') + 1));
    }

    /** 暂停开关变了（prefs 监听 / 控制台回调）：刷新缓存、叫醒读循环、调整唤醒锁。 */
    void onPauseChanged(String why) {
        boolean p = AssistantPause.isPaused(this);
        boolean changed = p != mPausedCached;
        mPausedCached = p;
        wakePauseWaiter();
        if (changed) Log.i(TAG, "[PAUSE] 暂停=" + p + "（" + why + "）");
        updateWakeLock("pause:" + why);
    }

    private void wakePauseWaiter() {
        synchronized (mPauseLock) {
            mPauseLock.notifyAll();
        }
    }

    /**
     * ★ r2/screenoff-wake：暂停即真暂停。读循环里调用：stop() AudioRecord（audioserver 的 AudioIn 锁随之释放）、放 PARTIAL 锁，
     * 等到恢复 / 声纹注册开始 / 服务退出；恢复时重新 startRecording（Android 8 只允许一个 App 录音，失败重试）并重新评估唤醒锁。
     *
     * @return false = 服务正在退出，读循环应结束
     */
    private boolean pauseMicUntilResumed() {
        AudioRecord ar = mAudioRecord;
        try {
            if (ar != null && ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
        } catch (Throwable t) {
            Log.w(TAG, "[PAUSE] 停止录音异常: " + t.getMessage());
        }
        boolean forLive = liveOwnMicActive();
        sLiveMicReleased = forLive;
        Log.i(TAG, forLive ? "[LIVE-MIC] 相机对话自己开麦 → 本服务已停止录音、不跑 KWS" : "[PAUSE] 助手已暂停 → 已停止录音、不跑 KWS");
        mMicState.onStopped("暂停");   // 相机对话让出麦克风也记作暂停（同一把锁逻辑）
        updateWakeLock("mic-paused");
        while (!mAbort && ScreenOffPolicy.micShouldPause(mPausedCached, mVpRecording || liveTap() != null, mEnrollSession, false, liveOwnMicActive())) {
            synchronized (mPauseLock) {
                try {
                    mPauseLock.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            mPausedCached = AssistantPause.isPaused(this);   // 兜底：监听漏掉时也能自愈
        }
        sLiveMicReleased = false;
        if (mAbort) return false;
        ar = mAudioRecord;
        boolean ok = false;
        for (int i = 0; i < 10 && !mAbort && ar != null; i++) {
            try {
                ar.startRecording();
                ok = ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
            } catch (Throwable t) {
                Log.w(TAG, "[PAUSE] 恢复录音失败（第 " + (i + 1) + " 次）: " + t.getMessage());
            }
            if (ok) break;
            SystemClock.sleep(500);
        }
        try { if (mKws != null && mKwsStream != null) mKws.reset(mKwsStream); } catch (Throwable ignore) {}
        if (mWakeRollingBuf != null) mWakeRollingBuf.clear();   // 暂停前的旧音频不能进预滚 / 声纹回溯
        Log.i(TAG, "[PAUSE] 已恢复 → 录音" + (ok ? "已重新开始" : "恢复失败（10 次）") + "，KWS 流已 reset");
        mMicState.onStartResult(ok, "resume");   // ★ r2/sysux R1：恢复成功才重新持锁
        updateWakeLock("mic-resumed");
        return true;
    }

    /**
     * ★ r2/screenoff-wake：命中先亮屏。受理唤醒（唤醒词 / 实体键 / 模拟键都汇到 requestWakeInternal）时：
     * 屏幕灭着 → SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP|ON_AFTER_RELEASE 短时锁（超时自动释放，纯 App 侧，不碰 exec 守护）；
     * 锁着 → 托底 Activity（不透明黑底、show-when-locked）让锁屏处于「被遮挡」态，面板（带 FLAG_SHOW_WHEN_LOCKED）才浮得上来。
     */
    @SuppressWarnings("deprecation")
    private void wakeScreenForWake(String source) {
        ScreenOffPolicy.Config cfg = (mScreenPolicy != null) ? mScreenPolicy.config() : ScreenOffPolicy.Config.defaults();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean interactive = pm == null || pm.isInteractive();
            if (ScreenOffPolicy.needScreenWake(interactive) && pm != null) {
                if (mScreenWakeLock == null) {
                    mScreenWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "bixbyui:screenwake");
                    mScreenWakeLock.setReferenceCounted(false);
                }
                mScreenWakeLock.acquire(cfg.screenWakeMs);
                Log.i(TAG, "[SCREEN] 息屏受理唤醒 src=" + source + " → 先亮屏（亮屏锁 " + cfg.screenWakeMs + "ms 超时自动释放）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SCREEN] 亮屏失败: " + t.getMessage());
        }
        try {
            boolean locked = keyguardLockedNow();
            if (mScreenPolicy != null) mScreenPolicy.setKeyguardLocked(locked);
            if (ScreenOffPolicy.needKeyguardHost(locked, cfg)) {
                com.onesay.assistant.v2.ui.KeyguardHostActivity.show(this, cfg.hostMaxMs, source);
                Log.i(TAG, "[SCREEN] 锁屏中受理唤醒 → 托底 Activity（面板浮在锁屏上方；隐私 / 写操作要求先解锁）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SCREEN] 锁屏托底失败: " + t.getMessage());
        }
    }

    /** 调试：息屏门 / 屏幕 / 唤醒锁状态一行（debug 探针用）。 */
    public static String screenOffStatus() {
        WakeService s = sInstance;
        if (s == null || s.mScreenPolicy == null) return "WakeService 未运行";
        boolean held = false;
        try { held = s.mWakeLock != null && s.mWakeLock.isHeld(); } catch (Throwable ignore) {}
        return s.mScreenPolicy.stats() + " paused=" + s.mPausedCached + " micRecording=" + s.mMicState.recording()
                + "(" + s.mMicState.reason() + ") partialHeld=" + held
                + " | " + s.mScreenPolicy.config();
    }

    /**
     * 唤醒修复：前台声明（startForegroundService 的约定）。onCreate / onStartCommand 的第一句。
     * <p>任何不该继续运行的情况（没有录音权限、Root 未就绪、助手暂停 / 关闭、模型缺失）都必须<b>先调本方法再 stopSelf</b>，
     * 不许在它之前 return —— 否则系统时限一到就是 ANR，服务被拆掉时还会补一个 RemoteServiceException 崩溃。
     * 通知构造失败时退回最简通知再试一次；两次都失败只记日志（不能让声明本身把服务弄崩）。
     */
    private void ensureForeground(String why) {
        try {
            String text;
            try {
                text = AssistantPause.notificationText(AssistantPause.isPaused(this), sRunning);
            } catch (Throwable t) {
                text = "启动中";
            }
            startForeground(NOTIF_ID, buildNotification(text));
            return;
        } catch (Throwable t) {
            Log.w(TAG, "[FG] startForeground 失败（" + why + "），改用最简通知重试: " + t);
        }
        try {
            startForeground(NOTIF_ID, buildNotification("启动中"));
        } catch (Throwable t2) {
            Log.e(TAG, "[FG] startForeground 两次都失败（" + why + "）: " + t2);
        }
    }

    private Notification buildNotification(String content) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "OneSay 语音唤醒与按键服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            chan.setDescription("维持麦克风监听与 Bixby 实体键响应");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2 + " 语音与按键服务")
                .setContentText(content)
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true);

        return b.build();
    }

    private void updateNotification(String content) {
        if (AssistantPause.isPaused(this)) content = AssistantPause.notificationText(true, true);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(content));
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 把外置关键词表 {@link #ASSET_KWS_KEYWORDS} 落盘，覆盖 zip 内的 keywords.txt。
     *
     * <p>为什么外置：关键词表是最需要反复调的部件（本轮就从"中文为主"改成"英文为主"），
     * 而它原本埋在 kws_model.zip 里 —— 每次调词表都要重打包 4MB 的模型，且版本戳与模型耦合。
     * 提到独立 asset + 独立版本戳后，调词表只需改一个 txt。
     *
     * <p>★ 关键坑（2026-10-04 实测踩到）：sherpa-onnx 的 keywords 解析器
     * **不跳过 `#` 注释行** —— 会把注释文本当关键词解析，遇非数字 token 直接抛
     * {@code invalid stof argument}，**整个 KWS 初始化失败**。
     * 所以这里落盘前**过滤掉空行与 `#` 开头的注释行**，
     * 让词表文件本身可以带注释而不炸（说明文档另见 assets/kws_keywords.README.txt）。
     *
     * <p>失败不致命：落盘失败时保留 zip 内的原词表，KWS 照常能初始化（只是用旧词表）。
     */
    /** 模型 tokens.txt 的全部 token（每行第一列）；读不到返回 null（只做格式检查）。 */
    private static java.util.Set<String> readTokens(File f) {
        if (!f.isFile()) return null;
        java.util.Set<String> s = new java.util.HashSet<String>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.length() == 0) continue;
                    int sp = t.indexOf(' ');
                    s.add(sp < 0 ? t : t.substring(0, sp));
                }
            } finally { br.close(); }
        } catch (Exception e) {
            return null;
        }
        return s;
    }

    private void applyExternalKeywords(File kwsDir, String userWords) {
        File stamp = new File(kwsDir, ".kw_ver");
        final String KWS_KEYWORDS_VER = WakeWords.stamp(WakeService.KWS_KEYWORDS_VER, userWords);
        try {
            String old = null;
            if (stamp.exists()) {
                byte[] b = new byte[(int) stamp.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(stamp);
                try { fis.read(b); } finally { fis.close(); }
                old = new String(b, "UTF-8").trim();
            }
            if (KWS_KEYWORDS_VER.equals(old)) {
                Log.i(TAG, "[KWS-KW] 关键词表已是 v" + old + "，跳过");
                return;
            }
            // 读 asset 全文 → 逐行过滤注释/空行 → 只写有效关键词
            // OneSay 开源版：词表来自用户填写的唤醒词（WakeWords），不再读内置 asset。
            InputStream in = new java.io.ByteArrayInputStream(WakeWords.keywordsFile(userWords).getBytes("UTF-8"));
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) raw.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.ByteArrayInputStream(raw.toByteArray()), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int kept = 0;
            int dropped = 0;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.length() == 0 || t.charAt(0) == '#') {
                    dropped++;
                    continue;                 // ★ 注释/空行必须剔除，否则 KWS 初始化会炸
                }
                sb.append(t).append('\n');
                kept++;
            }
            br.close();
            if (kept == 0) {
                Log.w(TAG, "[KWS-KW] 过滤后无有效关键词行，放弃覆盖（沿用 zip 内原表）");
                return;
            }
            File out = new File(kwsDir, "keywords.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            java.io.FileOutputStream fs = new java.io.FileOutputStream(stamp);
            try { fs.write(KWS_KEYWORDS_VER.getBytes("UTF-8")); } finally { fs.close(); }
            Log.i(TAG, "[KWS-KW] 关键词表已更新到 v" + KWS_KEYWORDS_VER
                    + "（保留 " + kept + " 条 / 剔除注释空行 " + dropped + " 行）→ "
                    + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "[KWS-KW] 外置关键词表落盘失败，沿用 zip 内原表: " + t);
        }
    }

    private void unpackZip(String assetName, String outDirName) throws Exception {        File outDir = new File(getFilesDir(), outDirName);
        outDir.mkdirs();
        InputStream in = new BufferedInputStream(getAssets().open(assetName));
        ZipInputStream z = new ZipInputStream(in);
        byte[] buf = new byte[65536];
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            File f = new File(outDir, e.getName());
            if (e.isDirectory()) {
                f.mkdirs();
                continue;
            }
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) {
                throw new java.io.IOException("无法创建目录: " + p);
            }
            FileOutputStream fo = new FileOutputStream(f);
            int n;
            while ((n = z.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            z.closeEntry();
        }
        z.close();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteTree(c);
            }
        }
        f.delete();
    }

    @Override
    public void onDestroy() {
        sMicReady = false;   // ★ round3 #3：麦克风随服务释放，之后的唤醒重新排队等下一次开录
        sColdWakes.reset();
        mAbort = true;
        mVpRecording = false;   // 服务销毁时结束声纹录制态
        sRunning = false;
        sStatus = "已停止";

        if (mTriggerObserver != null) {
            try { mTriggerObserver.stopWatching(); } catch (Throwable ignore) {}
            mTriggerObserver = null;
        }

        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
                mAudioRecord.release();
            } catch (Throwable ignore) {}
            mAudioRecord = null;
        }

        if (mWakeLock != null && mWakeLock.isHeld()) {
            try { mWakeLock.release(); } catch (Throwable ignore) {}
            mWakeLock = null;
        }
        // ★ r2/screenoff-wake：屏幕广播 / 暂停监听 / 亮屏锁 / 托底 Activity / 锁屏探针一并收掉
        wakePauseWaiter();
        if (mScreenReceiver != null) {
            try { unregisterReceiver(mScreenReceiver); } catch (Throwable ignore) {}
            mScreenReceiver = null;
        }
        if (mPauseListener != null) {
            try { getSharedPreferences(AssistantPause.PREF, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(mPauseListener); } catch (Throwable ignore) {}
            mPauseListener = null;
        }
        if (mScreenWakeLock != null) {
            try { if (mScreenWakeLock.isHeld()) mScreenWakeLock.release(); } catch (Throwable ignore) {}
            mScreenWakeLock = null;
        }
        com.onesay.assistant.v2.ui.KeyguardHostActivity.finishIfShowing("service-destroy");
        ScreenOffPolicy.setKeyguardProbe(null);

        focusTurnEnd("服务退出");
        com.onesay.assistant.v2.ui.TtsSpeaker.setSpeechListener(null);
        if (sInstance == this) sInstance = null;
        super.onDestroy();
        Log.i(TAG, "WakeService 已销毁退出");
    }
}
