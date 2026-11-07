package com.onesay.assistant.v2.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.onesay.assistant.v2.asr.AliyunAsrConfig;
import com.onesay.assistant.v2.executor.ShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * 屏幕视觉多模态理解引擎（ScreenVisionEngine）
 *
 * 核心链路（听到、看到、做到之「看到」通道）：
 * 1. 【屏幕截取】：借由 root 守护执行 `screencap -p /sdcard/Pictures/bixbyui_shot.png`，
 *    物理截取当前显示屏完整画面（~200ms）；
 * 2. 【智能图损压缩】：自适应降采样（长边限制 1080px）+ JPEG 75% 压缩，
 *    将 1.3MB 裸图大幅压缩至 ~80KB，并在内存中完成高效 Base64 编码，兼顾极速上传与低功耗；
 * 3. 【多模态端点调用】：直连阿里云百炼 DashScope 视觉多模态大模型（qwen-vl-plus / qwen-vl-max），
 *    OpenAI 兼容协议传输图像与提示词；
 * 4. 【异步流转与异常兜底】：全生命周期进度回调（截图 -> 编码 -> 云端分析 -> 结构化答复）。
 */
public class ScreenVisionEngine {

    /**
     * 从云端错误体里摘出 {@code code / message}（OpenAI 兼容格式 {@code {"error":{"code","message"}}}
     * 或 DashScope 原生 {@code {"code","message"}}），≤ 120 字；任何形如 sk-… 的串打码。解析不了返回空串。
     */
    static String cloudErrorSummary(String body) {
        if (body == null || body.trim().length() == 0) return "";
        String code = "", msg = "";
        try {
            JSONObject j = new JSONObject(body.trim());
            JSONObject e = j.optJSONObject("error");
            if (e != null) {
                code = e.optString("code", e.optString("type", ""));
                msg = e.optString("message", "");
            } else {
                code = j.optString("code", "");
                msg = j.optString("message", "");
            }
        } catch (Exception ex) {
            msg = body.trim();
        }
        String out = (code.length() > 0 ? code : "") + (code.length() > 0 && msg.length() > 0 ? " " : "") + msg;
        out = out.replaceAll("sk-[A-Za-z0-9._\\-]{4,}", "sk-***").replace('\n', ' ').trim();
        return out.length() > 120 ? out.substring(0, 120) + "…" : out;
    }

    /** 视觉请求（纯函数，JVM 测试断言 URL / 头 / 超时与旧实现一致：连 12 s、读 25 s、POST、Bearer）。 */
    static com.onesay.assistant.v2.net.HttpClient.Request visionRequest(String url, String apiKey, byte[] body) {
        return new com.onesay.assistant.v2.net.HttpClient.Request(url)
                .method("POST")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .timeouts(12000, 25000);
    }


    private static final String TAG = "ScreenVisionEngine";
    private static volatile ScreenVisionEngine sInstance;

    /**
     * ★这里**曾经硬编码过一个真实的 DashScope Key** 作为兜底，已移除
     * （2026-10-23 审计 g3：注释里残留的 key 前缀也删掉了；git 历史里的旧 key 须在百炼控制台作废）。
     *
     * <p>现在的口径：**只认用户在设置里配好的 DashScope Key**（与 ASR 共用同一份配置，
     * {@code bixby_asr_cfg}）。没配就直接报错，不再静默使用任何内置凭据。
     */
    private static String visionApiUrl() { return com.onesay.assistant.v2.net.LlmEndpoints.dashscopeChatUrl(); }
    /**
     * qwen-vl-plus 自 2026-10-10 起云端返回 404 model_not_found（百炼「部分历史主线模型下线」公告，
     * 生效 2026-10-10 00:00；真机 03:52 还是 200、22:11 起 404）→ 默认改 qwen3-vl-plus（百炼视觉理解文档当前列出的 Qwen3-VL 主线）。
     * 可在 bixby_asr_cfg 里用 vision_model 覆盖（例如改回旧名或换 qwen3-vl-flash）。
     */
    public static final String DEFAULT_MODEL = "qwen3-vl-plus";
    static final String PREF_VISION_MODEL = "vision_model";

    /** 纯函数：配置了就用配置（去空白），否则默认。 */
    static String pickModel(String configured) {
        if (configured == null) return DEFAULT_MODEL;
        String c = configured.trim();
        return c.length() == 0 ? DEFAULT_MODEL : c;
    }

    /** ★ R12 控制台「模型与密钥 › 视觉模型」只读显示：当前生效的视觉模型名。 */
    public static String effectiveModel(Context ctx) {
        try {
            return pickModel(ctx.getSharedPreferences("bixby_asr_cfg", Context.MODE_PRIVATE).getString(PREF_VISION_MODEL, ""));
        } catch (Throwable t) {
            return DEFAULT_MODEL;
        }
    }

    private String model() {
        try {
            return pickModel(mContext.getSharedPreferences("bixby_asr_cfg", Context.MODE_PRIVATE).getString(PREF_VISION_MODEL, ""));
        } catch (Throwable t) {
            return DEFAULT_MODEL;
        }
    }

    private final Context mContext;
    private final ShellExecutor mShellExecutor;
    private final AliyunAsrConfig mAsrConfig;

    public interface Callback {
        void onProgress(String stage);
        void onSuccess(String answer);
        void onError(String errorMsg);
    }

    /**
     * 可取消的回调。worker 在「截图后、编码前」和「POST 前」两个检查点问一次，
     * 取消了就<b>不上传</b>（原来取消只保证不显示，截图照样发到云端）。
     */
    public interface CancellableCallback extends Callback {
        boolean isCancelled();
    }

    /** LOGIC-8：取消后回给 onError 的固定文本（图没有上传）。 */
    public static final String ERR_CANCELLED = "识屏已取消（画面没有上传）";

    /** LOGIC-8：识屏会把整屏发到云端（产品提示文案）。 */
    public static final String UPLOAD_NOTICE = "识屏会把当前整屏画面发送到云端视觉模型分析";

    /** LOGIC-8：在途识屏的代次；{@link #cancelInFlight} 自增 → 已启动的 worker 在下一个检查点放弃（不上传）。 */
    private final java.util.concurrent.atomic.AtomicInteger mGen = new java.util.concurrent.atomic.AtomicInteger();

    /** LOGIC-8：作废所有在途识屏（面板收起 / 用户点「✕ 取消」时调用）。 */
    public void cancelInFlight() {
        int g = mGen.incrementAndGet();
        Log.i(TAG, "[VISION] 在途识屏已作废（代次 " + g + "），未上传的不再上传");
    }

    /** LOGIC-8：检查点判定（纯函数，JVM 可测）：代次变了，或回调说已取消 → 放弃。 */
    static boolean shouldAbort(int startGen, int nowGen, Callback cb) {
        if (startGen != nowGen) return true;
        try {
            return cb instanceof CancellableCallback && ((CancellableCallback) cb).isCancelled();
        } catch (Throwable t) {
            return false;
        }
    }

    private ScreenVisionEngine(Context context) {
        this.mContext = context.getApplicationContext();
        this.mShellExecutor = new ShellExecutor(mContext);
        this.mAsrConfig = new AliyunAsrConfig(mContext);
    }

    public static ScreenVisionEngine getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ScreenVisionEngine.class) {
                if (sInstance == null) {
                    sInstance = new ScreenVisionEngine(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 「屏幕上这是什么」回了 637 字带 Markdown 的长文 → 问答同口径：先一句结论、纯文本、
     * 未要求详细时 1~3 句（{@link com.onesay.assistant.v2.router.AskStyle}，翻译只给译文）。
     */
    public static String questionPrompt(String userQuestion) {
        String q = userQuestion.trim();
        String style = com.onesay.assistant.v2.router.AskStyle.rule(q);
        return "当前手机屏幕截图如上。请结合当前屏幕内容精准回答用户问题（若底部有悬浮助手面板，请忽略助手面板本身，重点结合屏幕实际内容）。"
                + "第一句就是结论；用纯文本，不用 Markdown 标题、加粗、项目符号；不逐项罗列状态栏图标。"
                + (style.length() > 0 ? style : "\n") + "用户问题：" + q;
    }

    /**
     * 捕获并分析当前手机屏幕。
     *
     * @param userQuestion 用户自定义提问，若为 null 则执行通用屏幕内容总结
     * @param callback 异步回调接口
     */
    public void recognizeScreen(final String userQuestion, final Callback callback) {
        // 普通识屏入口也开 rejectBlack（FLAG_SECURE 全黑截图不上传，回 ERR_SECURE_BLACK）
        recognizeInternal(userQuestion, null, 0, 0, null, null, true, callback);
    }

    /**
     * ★ feat/suggest：分析<b>已有图片文件</b>（不截屏，例如用户刚截的图）。复用同一套压缩（长边 1080、JPEG 75）/ 上传 / 错误摘要 / 取消逻辑；
     * 原图不删除（是用户的文件）。只在用户点了「分析」后调用。
     */
    public void recognizeImage(String path, String prompt, Callback callback) {
        if (path == null || !new File(path).isFile()) {
            if (callback != null) callback.onError("图片不存在（可能已被删除）");
            return;
        }
        recognizeInternal(null, null, 0, 0, prompt, null, false, path, callback);
    }

    /**
     * [s1] 圈选即问：截图后只把用户框住的区域（视图坐标 {l,t,r,b}，视图尺寸 viewW×viewH，按比例换算到截图像素）发给 VL。
     *
     * @param prompt     完整提示词（由 RegionSelection.vlPrompt 生成）
     * @param onCaptured 截图完成（无论成败）后在工作线程回调一次 —— 调用方此时才可以把面板挂回屏幕，避免面板被截进图里
     */
    public void recognizeRegion(String prompt, int[] rect, int viewW, int viewH, Runnable onCaptured, Callback callback) {
        recognizeInternal(null, rect, viewW, viewH, prompt, onCaptured, false, callback);
    }

    /** [summary] 全黑截图（FLAG_SECURE）时 {@link Callback#onError} 收到的固定文本：调用方据此给出明确提示，图不上传。 */
    public static final String ERR_SECURE_BLACK = "__SCREEN_SECURE_BLACK__";

    /**
     * [summary] 整屏 + 自定提示词，截图全黑（FLAG_SECURE）时<b>不上传</b>，直接回 {@link #ERR_SECURE_BLACK}。
     *
     * @param onCaptured 截图完成后在工作线程回调一次（调用方此时把面板挂回来，面板不会被截进图里）
     */
    public void recognizeScreenGuarded(String prompt, Runnable onCaptured, Callback callback) {
        recognizeInternal(null, null, 0, 0, prompt, onCaptured, true, callback);
    }

    private void recognizeInternal(final String userQuestion, final int[] region, final int viewW, final int viewH,
                                   final String promptOverride, final Runnable onCaptured, final boolean rejectBlack,
                                   final Callback callback) {
        recognizeInternal(userQuestion, region, viewW, viewH, promptOverride, onCaptured, rejectBlack, null, callback);
    }

    /** @param existingImage 非 null = 不截屏，直接用这个文件（feat/suggest 截图分析）；它不会被删除。 */
    private void recognizeInternal(final String userQuestion, final int[] region, final int viewW, final int viewH,
                                   final String promptOverride, final Runnable onCaptured, final boolean rejectBlack,
                                   final String existingImage, final Callback callback) {
        final int startGen = mGen.get();   // LOGIC-8
        com.onesay.assistant.v2.util.Threads.start("ScreenVisionWorker", new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                // 中间产物路径（在 finally 里删掉，正常路径磁盘不留文件）
                String shotPath = null;
                try {
                    boolean captured;
                    File shotFile;
                    if (existingImage != null) {   // ★ feat/suggest：已有图片，跳过截屏；shotPath 保持 null → finally 不删用户的文件
                        captured = true;
                        shotFile = new File(existingImage);
                        Log.i(TAG, "[VISION] 分析已有图片（不截屏）size=" + shotFile.length());
                    } else {
                    if (callback != null) {
                        callback.onProgress("正在捕获当前屏幕画面...");
                    }

                    // 1. 物理截取当前屏幕
                    // 修复（用户反馈"识别屏幕每次都保存到相册，每次都要手动删除"）：
                    //   原路径写死 /sdcard/Pictures/bixbyui_shot.png —— Pictures 是**媒体库扫描目录**，
                    //   于是每一次识屏都在相册里留一张垃圾图，只能手动删；
                    //   而且它和 ui.screenshot 能力**共用同一个文件名**，两边会互相覆盖。
                    //   现在优先写应用外部缓存目录 /sdcard/Android/data/<pkg>/cache/：
                    //     ① 媒体库**不扫描** Android/data（不进相册）；
                    //     ② root 守护进程**可直接写**（不必再 chown 到 app uid）；
                    //     ③ App 自己名下的目录，自己可删；卸载后系统自动清理。
                    //   文件在 finally 里**用完即删**（见方法尾部）。
                    //   若缓存目录不可写（极端情况）→ 退回老路径保功能不退化，同样会删。
                    // 先写 App 私有目录（别的 App 读不到），失败退回外部缓存目录；
                    //   不再退回 /sdcard/Pictures（会进相册、任何 App 可读）。
                    // 先私有 scratch，再内部 cache 根目录（都只有本 App 能读）；
                    //   两处都拿不到才退回外部缓存目录，而且不再放开成所有人可读写（统一 chown 给 App + chmod 600）。
                    shotPath = shotPathPrivate();
                    captured = shotPath != null && captureScreen(shotPath);
                    if (!captured) {
                        if (shotPath != null) {
                            Log.w(TAG, "[VISION] 私有 scratch 截屏失败，改用内部 cache 目录");
                            deleteQuietly(shotPath);
                        }
                        shotPath = shotPathInternalCache();
                        captured = shotPath != null && captureScreen(shotPath);
                    }
                    if (!captured) {
                        if (shotPath != null) deleteQuietly(shotPath);
                        shotPath = shotPathExternalCache();
                        if (shotPath != null) {
                            Log.w(TAG, "[VISION] 内部目录都不可用，退回外部缓存目录（600，用完即删）");
                            captured = captureScreen(shotPath);
                        }
                    }
                    if (shotPath == null) {
                        if (callback != null) callback.onError("无法截取屏幕内容：没有可写的缓存目录");
                        return;
                    }
                    long tCap = SystemClock.elapsedRealtime() - t0;
                    Log.i(TAG, "[VISION] 截屏完成，耗时=" + tCap + "ms, 路径=" + shotPath);
                    if (onCaptured != null) {   // [s1] 截图已落盘：调用方可以把面板挂回来了
                        try { onCaptured.run(); } catch (Throwable ignored) {}
                    }

                    shotFile = new File(shotPath);
                    }
                    if (!captured || !shotFile.exists() || shotFile.length() < 1024) {
                        if (callback != null) {
                            callback.onError("无法截取屏幕内容：画面文件为空或权限受阻");
                        }
                        return;
                    }

                    // ★ LOGIC-8 检查点①：截图后、编码前 —— 已取消就不编码、不上传
                    if (shouldAbort(startGen, mGen.get(), callback)) {
                        Log.i(TAG, "[VISION] 截图后检测到取消，不上传");
                        if (callback != null) callback.onError(ERR_CANCELLED);
                        return;
                    }
                    if (callback != null) {
                        callback.onProgress("正在压缩并上传画面...（" + UPLOAD_NOTICE + "）");
                    }

                    // 2. 图像自适应下采样与 JPEG 压缩
                    Bitmap bmp = BitmapFactory.decodeFile(shotFile.getAbsolutePath());
                    if (bmp == null) {
                        if (callback != null) {
                            callback.onError("画面解码失败，请重试");
                        }
                        return;
                    }

                    if (rejectBlack && isAllBlack(bmp)) {   // [summary] FLAG_SECURE：全黑截图不上传
                        bmp.recycle();
                        Log.i(TAG, "[VISION] 截图全黑（疑似 FLAG_SECURE），不上传");
                        if (callback != null) callback.onError(ERR_SECURE_BLACK);
                        return;
                    }

                    if (region != null) {   // [s1] 圈选：裁到用户框住的区域
                        int[] crop = com.onesay.assistant.v2.s1.RegionSelection.mapToBitmap(region,
                                viewW > 0 ? viewW : bmp.getWidth(), viewH > 0 ? viewH : bmp.getHeight(),
                                bmp.getWidth(), bmp.getHeight());
                        if (crop != null) {
                            Bitmap cropped = Bitmap.createBitmap(bmp, crop[0], crop[1], crop[2], crop[3]);
                            if (cropped != bmp) bmp.recycle();
                            bmp = cropped;
                            Log.i(TAG, "[VISION] 圈选裁剪 x=" + crop[0] + " y=" + crop[1] + " " + crop[2] + "x" + crop[3]);
                        }
                    }

                    int origW = bmp.getWidth();
                    int origH = bmp.getHeight();
                    float maxDim = Math.max(origW, origH);
                    Bitmap scaled = bmp;
                    if (maxDim > 1080f) {
                        float ratio = 1080f / maxDim;
                        scaled = Bitmap.createScaledBitmap(bmp, (int) (origW * ratio), (int) (origH * ratio), true);
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                    byte[] jpegBytes = baos.toByteArray();
                    String base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

                    if (scaled != bmp) {
                        scaled.recycle();
                    }
                    bmp.recycle();
                    long tEncode = SystemClock.elapsedRealtime() - t0;
                    Log.i(TAG, "[VISION] 图像编码完成，体积=" + (jpegBytes.length / 1024) + "KB, 累计耗时=" + tEncode + "ms");

                    if (callback != null) {
                        callback.onProgress("视觉多模态大模型分析中...");
                    }

                    // 3. 构造 OpenAI 兼容协议的多模态分析请求
                    String promptText = promptOverride != null ? promptOverride
                            : (userQuestion != null && userQuestion.trim().length() > 0)
                            ? questionPrompt(userQuestion)
                            : "你是一个手机系统级智能视觉助手。请观察当前手机屏幕截图，清晰简明地总结：1. 当前处于什么应用或界面；2. 屏幕核心信息与内容概要；3. 关键可操作项。若底部有语音助手或悬浮面板，请忽略助手面板本身，重点关注手机实际界面。字数控制在 200 字左右，条理清晰。";

                    JSONObject payload = new JSONObject();
                    payload.put("model", model());

                    JSONArray messages = new JSONArray();
                    JSONObject userMsg = new JSONObject();
                    userMsg.put("role", "user");

                    JSONArray contentArr = new JSONArray();

                    JSONObject imgObj = new JSONObject();
                    imgObj.put("type", "image_url");
                    JSONObject urlObj = new JSONObject();
                    urlObj.put("url", "data:image/jpeg;base64," + base64Image);
                    imgObj.put("image_url", urlObj);
                    contentArr.put(imgObj);

                    JSONObject textObj = new JSONObject();
                    textObj.put("type", "text");
                    textObj.put("text", promptText);
                    contentArr.put(textObj);

                    userMsg.put("content", contentArr);
                    messages.put(userMsg);
                    payload.put("messages", messages);

                    // 4. 发起 HTTP POST 请求
                    String apiKey = mAsrConfig.getDashScopeKey();
                    // 内置兜底 Key 已被移除（2026-10-23 恒空的兜底常量一并删掉），所以这里必须
                    //   **明确报错**，而不是带着空的 Bearer 去打云端 —— 那样只会拿到一个
                    //   语焉不详的 401，用户根本看不出是"没配 Key"。
                    if (apiKey == null || apiKey.trim().length() == 0) {
                        Log.w(TAG, "[VISION] 未配置 DashScope Key，识屏无法进行");
                        if (callback != null) {
                            callback.onError("还没有配置阿里云百炼（DashScope）的 API Key，"
                                    + "识屏需要它 —— 填好与语音识别共用的那个 Key 即可。");
                        }
                        return;
                    }

                    // 改走共享 HttpClient（与 LLM / 搜索同一套超时、关流、错误分类），不再自己开连接。
                    //   网络异常照旧落到下面的「识屏过程出现异常」（以前是 IOException 抛到外层 catch）。
                    byte[] bodyBytes = payload.toString().getBytes("UTF-8");
                    // ★ LOGIC-8 检查点②：POST 前 —— 已取消就不发
                    if (shouldAbort(startGen, mGen.get(), callback)) {
                        Log.i(TAG, "[VISION] POST 前检测到取消，不上传");
                        if (callback != null) callback.onError(ERR_CANCELLED);
                        return;
                    }
                    com.onesay.assistant.v2.net.HttpClient.Response resp = com.onesay.assistant.v2.net.HttpClient.execute(
                            visionRequest(visionApiUrl(), apiKey.trim(), bodyBytes));
                    if (resp.error != null) {
                        throw resp.error;
                    }
                    int code = resp.code;
                    String respStr = resp.body;
                    long tTotal = SystemClock.elapsedRealtime() - t0;
                    Log.i(TAG, "[VISION] 云端返回 HTTP " + code + ", 总耗时=" + tTotal + "ms");

                    if (code != 200) {
                        // 把云端错误码 / 信息打进日志（10-10 22:11 起真机连续 HTTP 404，旧日志只有状态码、查不出是模型下线还是账号无权限）
                        String why = cloudErrorSummary(respStr);
                        Log.w(TAG, "[VISION] 云端错误 HTTP " + code + (why.length() > 0 ? "：" + why : "（无错误体）") + " model=" + model());
                        if (callback != null) {
                            callback.onError("视觉分析请求失败(HTTP " + code + ")" + (why.length() > 0 ? "：" + why : ""));
                        }
                        return;
                    }

                    // 5. 解析回答正文
                    JSONObject respJson = new JSONObject(respStr);
                    JSONArray choices = respJson.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        if (callback != null) {
                            callback.onError("视觉模型未返回有效内容");
                        }
                        return;
                    }

                    JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                    String answer = (msg != null) ? msg.optString("content", "") : "";
                    if (answer == null || answer.trim().length() == 0) {
                        if (callback != null) {
                            callback.onError("视觉模型返回内容为空");
                        }
                        return;
                    }

                    Log.i(TAG, "[VISION] 视觉分析成功，正文字数=" + answer.length());
                    if (callback != null) {
                        callback.onSuccess(answer.trim());
                    }

                } catch (Throwable t) {
                    Log.e(TAG, "[VISION] 视觉处理异常: " + t.getMessage(), t);
                    if (callback != null) {
                        callback.onError("识屏过程出现异常: " + t.getMessage());
                    }
                } finally {
                    // 中间产物**用完即删**（无论成功/失败/提前 return 都走到这里）。
                    //   用户诉求原话："识别屏幕每次都是保存到相册，每次都要手动删除很麻烦。"
                    //   即使某一轮删除失败，文件也只落在 cache 目录里（媒体库不扫描，不进相册）。
                    deleteQuietly(shotPath);
                }
            }
        });
    }

    /** [summary] 缩到 180 宽后按语音存图的同一口径（PixelHeuristics.isAllBlack）判全黑。 */
    private static boolean isAllBlack(Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= 0 || h <= 0) return false;
            int sw = 180, sh = Math.max(1, Math.round(h * 180f / w));
            Bitmap small = Bitmap.createScaledBitmap(bmp, sw, sh, true);
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            if (small != bmp) small.recycle();
            return com.onesay.assistant.v2.imgsave.PixelHeuristics.isAllBlack(px, sw, sh);
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * 供自测断言：识屏中间产物的**正常落盘路径**。
     *
     * <p>断言内容应当是"它**不在**媒体库扫描目录里"（用户诉求：识屏别再往相册写）。
     */
    public String debugShotPath() {
        String p = shotPathPrivate();
        if (p == null) p = shotPathInternalCache();
        return p != null ? p : shotPathExternalCache();
    }

    /** r2/fixes 5：内部 cache 根目录 /data/data/<pkg>/cache/vision_shot.png（拿不到返回 null）。 */
    private String shotPathInternalCache() {
        try {
            File dir = mContext.getCacheDir();
            return dir == null ? null : new File(dir, "vision_shot.png").getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 私有目录 /data/data/com.onesay.assistant/cache/scratch/vision/（拿不到返回 null）。 */
    private String shotPathPrivate() {
        java.io.File d = com.onesay.assistant.v2.util.PrivateScratch.dir(mContext, "vision");
        return d == null ? null : new File(d, "vision_shot.png").getAbsolutePath();
    }

    /**
     * 识屏中间产物的落盘路径 —— **应用外部缓存目录**。
     *
     * <p>为什么不是 {@code /sdcard/Pictures/}：那是**媒体库扫描目录**，
     * 每识屏一次就在相册里留一张垃圾图（用户实测痛点）。
     * {@code /sdcard/Android/data/<pkg>/cache/} 有三个刚好都要的特性：
     * ① 媒体库**不扫描** Android/data ⇒ 不进相册；
     * ② root 守护进程**可直接写**（不必 chown 到 app uid）；
     * ③ App 自己名下的目录，可自删；卸载后系统自动清理。
     */
    private String shotPathExternalCache() {
        try {
            File dir = mContext.getExternalCacheDir();
            return dir == null ? null : new File(dir, "vision_shot.png").getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 截屏命令（纯函数，JVM 测试守住）：root 写完一律 chown 给目录属主（= App uid）+ chmod 600 + restorecon，
     * <b>任何目录都不放开成所有人可读写</b>（2026-10-24 r2/fixes 5）。
     */
    static String captureCmd(String path) {
        // 2026-10-25 exec 守护 v4：单条白名单命令。文件由 App 先建好（PrivateScratch.prepare），root 原地写，
        //   属主 / 标签仍是 App 的，不再需要 chown + restorecon（v4 不执行复合脚本，fix-root.md §3）。
        //   原来的 timeout 6 去掉：守护自己有 10 s 单条上限（timeout 不在 v4 白名单里）。
        return "screencap -p " + com.onesay.assistant.v2.util.PrivateScratch.quote(path);
    }

    /** 兜底：root 没有原地写（App 读不了）时的属主修正（复合脚本，v4 下需 allow_raw，见 PrivateScratch.needsFixup）。 */
    static String fixupFallbackCmd(String path) {
        String parent = new File(path).getParent();
        return com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(path, parent == null ? path : parent);
    }

    /** 执行一次截屏；返回"文件看起来有效"（存在且 >1KB）。 */
    private boolean captureScreen(String path) {
        try {
            // ★ r2/fast-screen：先走常驻读屏助手（JPEG q90）；不可用才走 root screencap -p
            if (com.onesay.assistant.v2.fastscreen.FsCapture.jpegTo(mContext, new File(path), 90, 0) && new File(path).length() > 1024) return true;
            com.onesay.assistant.v2.util.PrivateScratch.prepare(new File(path));
            mShellExecutor.execute(captureCmd(path));
            if (com.onesay.assistant.v2.util.PrivateScratch.needsFixup(new File(path))) {
                Log.w(TAG, "[VISION] 截图不是 App 可读的文件（root 没有原地写），退回属主修正");
                mShellExecutor.execute(fixupFallbackCmd(path));
            }
            File f = new File(path);
            return f.exists() && f.length() > 1024;
        } catch (Throwable t) {
            Log.w(TAG, "[VISION] 截屏执行异常: " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 识屏附图（对话窗底栏左侧按钮）

    /** 一种截屏方式（纯接口，JVM 测试守住顺序）。 */
    public interface Capturer {
        String name();

        boolean capture(String path);
    }

    /**
     * 按顺序试截屏方式，第一个落出有效文件（存在且 &gt;1KB）的胜出；返回它的 name，都不行返回 null（失败的残留文件删掉）。
     * 识屏附图的顺序：root screencap → 常驻读屏助手（UiAutomation 截图）。本机没有 MediaProjection / 无障碍截图能力（Android 8，无授权页），
     * 两条都不行由调用方给明确提示。
     */
    public static String captureOrdered(String path, Capturer... cs) {
        if (path == null || cs == null) return null;
        for (Capturer c : cs) {
            if (c == null) continue;
            boolean ok;
            try {
                ok = c.capture(path);
            } catch (Throwable t) {
                ok = false;
            }
            File f = new File(path);
            if (ok && f.isFile() && f.length() > 1024) return c.name();
            if (f.exists()) f.delete();
        }
        return null;
    }

    /** 识屏附图的结果：path 非 null = 成功（缩略图 thumb，方式 via）；否则 error 是给用户看的原因。 */
    public static final class AttachShot {
        public final String path, via, error;
        public final Bitmap thumb;

        AttachShot(String path, String via, Bitmap thumb, String error) {
            this.path = path;
            this.via = via;
            this.thumb = thumb;
            this.error = error;
        }
    }

    /**
     * 只截屏、不上传（工作线程调）。调用方先把自己的浮层藏好。截图落在 App 私有 scratch（不进相册），
     * 发送问完由调用方删。全黑（FLAG_SECURE）不附，直接给提示。
     */
    public AttachShot captureForAttach() {
        String path = null;
        java.io.File d = com.onesay.assistant.v2.util.PrivateScratch.dir(mContext, "vision");
        if (d == null) d = mContext.getCacheDir();
        if (d == null) return new AttachShot(null, null, null, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_CAPTURE_FAILED);
        path = new File(d, "screen_attach_" + System.currentTimeMillis() + ".png").getAbsolutePath();
        final ShellExecutor sh = mShellExecutor;
        long t0 = SystemClock.elapsedRealtime();
        String via = captureOrdered(path, new Capturer() {
            @Override public String name() { return "root"; }
            @Override public boolean capture(String p) {
                com.onesay.assistant.v2.util.PrivateScratch.prepare(new File(p));
                sh.execute(captureCmd(p));
                if (com.onesay.assistant.v2.util.PrivateScratch.needsFixup(new File(p))) sh.execute(fixupFallbackCmd(p));
                return true;
            }
        }, new Capturer() {
            @Override public String name() { return "fast"; }
            @Override public boolean capture(String p) {
                return com.onesay.assistant.v2.fastscreen.FsCapture.jpegTo(mContext, new File(p), 90, 0);
            }
        });
        Log.i(TAG, "[VISION] 识屏附图截屏 via=" + via + " 耗时=" + (SystemClock.elapsedRealtime() - t0) + "ms");
        if (via == null) return new AttachShot(null, null, null, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_CAPTURE_FAILED);
        Bitmap thumb = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int edge = Math.max(o.outWidth, o.outHeight), ss = 1;
            while (edge / (ss * 2) >= 360) ss *= 2;
            BitmapFactory.Options d2 = new BitmapFactory.Options();
            d2.inSampleSize = ss;
            thumb = BitmapFactory.decodeFile(path, d2);
        } catch (Throwable ignored) {
        }
        if (thumb == null) {
            deleteQuietly(path);
            return new AttachShot(null, null, null, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_CAPTURE_FAILED);
        }
        if (isAllBlack(thumb)) {
            deleteQuietly(path);
            return new AttachShot(null, null, null, com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.SCREEN_CAPTURE_BLACK);
        }
        return new AttachShot(path, via, thumb, null);
    }

    /** 静默删除（失败不影响主流程）。 */
    private static void deleteQuietly(String path) {
        if (path == null) return;
        try {
            File f = new File(path);
            if (f.exists() && f.delete()) {
                Log.i(TAG, "[VISION] 中间产物已清理: " + path);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 识屏结果（同步接口用）。 */
    public static final class VisionResult {
        public final boolean ok;
        public final String text;

        VisionResult(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    /** 识屏同步等待的默认上限。实测单轮 3~6s（截屏 1s + 编码 0.2s + 云端 2~4s），30s 足够宽裕。 */
    public static final long SYNC_TIMEOUT_MS = 30000L;

    /**
     * **同步版**识屏 —— 供**执行器**调用（新增）。
     *
     * <h3>为什么必须有这一版</h3>
     * <p>识屏此前只有异步回调版，且只在**单步路径**被
     * {@code FloatingSheetService.triggerScreenVision} 直接调用。
     * 一旦它作为**一个工具**出现在 AgentLoop 里（"打开相机→截图→说出内容"这种
     * 多步任务必须这样走），执行器拿到的只有 {@code executorType == "custom"} 的
     * 占位分支 —— 返回字符串 {@code "Custom delegated: vision.screen.understand"}，
     * **识屏根本没跑**。模型收到这个假观察，只能编或放弃。
     *
     * <p>所以补一个阻塞版：执行器在后台线程上调用它，拿到真实文本作为 observation。
     *
     * @param userQuestion 针对性提问；null/空 = 通用屏幕内容总结
     * @return 结果对象；{@code ok=false} 时 {@code text} 是失败原因
     */
    public VisionResult recognizeScreenSync(String userQuestion, long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final VisionResult[] box = new VisionResult[1];
        recognizeScreen(userQuestion, new Callback() {
            @Override
            public void onProgress(String stage) {
                // 同步版不逐级上报进度（调用方在后台线程上阻塞，没有 UI 可刷）
            }

            @Override
            public void onSuccess(String answer) {
                box[0] = new VisionResult(true, answer);
                latch.countDown();
            }

            @Override
            public void onError(String errorMsg) {
                box[0] = new VisionResult(false, ERR_SECURE_BLACK.equals(errorMsg)
                        ? "当前页面禁止截屏（画面全黑），没有上传，无法识别" : errorMsg);   // LOGIC-8
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return new VisionResult(false, "识屏超时（超过 " + (timeoutMs / 1000) + " 秒）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new VisionResult(false, "识屏被中断");
        }
        return box[0] != null ? box[0] : new VisionResult(false, "识屏无返回");
    }
}
