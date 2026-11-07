package com.onesay.assistant.v2.imgsave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.ui.RegionSelectOverlay;
import com.onesay.assistant.v2.vision.screenref.UiDumpParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [imgsave] 语音存图的 Android 侧流程（只在 {@link ImageSaveHooks} 里被调用）。
 *
 * <pre>
 * 「保存图片」→ 面板先让位（s1HideSheetNow）→ 350 ms 后一次 root 往返：前台包名 + raw screencap 与<b>非压缩</b> uiautomator dump 并行
 *   → 截图全黑？→ FLAG_SECURE：只试缓存层，并告诉用户
 *   → 图片节点（ImageNodeSelector）→ 每个节点裁掉纯色边
 *   → 一个节点都没有 → 纹理检测（PixelHeuristics）→ 置信度低 → 手动框选（RegionSelectOverlay，圈一下同款）
 *   → 1 张 / 点了第几张 / 说了全部 → 存；多张 → 图上编号徽标 + 面板编号芯片，等「第N张 / 全部 / 点芯片」（60 s）
 *   → 存：缓存层（imgsave.cache）宽高比对上 → 原样拷出「原图」；否则截图裁剪无损 PNG
 *   → Pictures/OneSay/&lt;包名&gt;/ + 媒体扫描 → 面板「已保存 N 张（…）」
 * </pre>
 * 截图与 dump 只放在本 App 的外部缓存目录，读进内存后立即删除（含屏幕隐私）。识屏既有的压缩 dump 采集路径（ScreenContextCapture）不动。
 */
final class ImageSaveController {

    static final String TAG = "ImageSave";

    private final FloatingSheetService mSvc;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private volatile boolean mBusy;
    /** 当前这一轮的截图与目标（多图待选 / 手动框选期间保留）。 */
    private Capture mCap;
    private List<ImageNodeSelector.Target> mPendingTargets;
    private long mPendingAt;
    private final List<View> mBadges = new ArrayList<View>();
    private RegionSelectOverlay mOverlay;
    /** 最近一次结果（调试驱动读）。 */
    volatile String mLastResult = "";

    private final Runnable mPendingTimeout = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "[IMGSAVE] 待选超时（" + (ImageSaveSettings.PENDING_TTL_MS / 1000) + " s），清掉编号");
            clearPending();
        }
    };

    ImageSaveController(FloatingSheetService svc) {
        mSvc = svc;
        mWm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
    }

    static final class Capture {
        Bitmap screen;
        String pkg;
        long captureSec;
        List<ImageNodeSelector.Target> targets = new ArrayList<ImageNodeSelector.Target>();
        boolean black;
        String note = "";
    }

    boolean hasPending() {
        return mPendingTargets != null && SystemClock.elapsedRealtime() - mPendingAt < ImageSaveSettings.PENDING_TTL_MS;
    }

    int pendingCount() {
        return mPendingTargets != null ? mPendingTargets.size() : 0;
    }

    // ------------------------------------------------------------------ 入口

    /** 主线程。 */
    void start(final ImageSaveIntent.Hit hit, final String command) {
        if (mBusy) {
            Log.i(TAG, "[IMGSAVE] 上一次存图还在处理，忽略: " + com.onesay.assistant.v2.util.LogText.u(command));
            return;
        }
        clearPending();
        mBusy = true;
        Log.i(TAG, "[IMGSAVE] 开始 hit=" + hit + " 指令=" + com.onesay.assistant.v2.util.LogText.u(command) + " | " + ImageSaveSettings.dump(mSvc));
        mSvc.s1HideSheetNow();   // 面板先让位：不能截进图里，dump 也要拿到前台 App 的窗口
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.util.Threads.start("ImageSave", new Runnable() {
                    @Override public void run() {
                        try {
                            runCapture(hit, command);
                        } catch (Throwable t) {
                            Log.w(TAG, "[IMGSAVE] 异常: " + t);
                            finish("存图失败：" + t.getMessage(), false);
                        }
                    }
                });
            }
        }, 350L);
    }

    /** 选择口令 / 芯片 / 徽标。主线程。 */
    void onSelection(ImageSaveIntent.Selection sel) {
        if (!hasPending() || sel == null) return;
        final List<ImageNodeSelector.Target> targets = mPendingTargets;
        Log.i(TAG, "[IMGSAVE] 选择 " + sel + "（共 " + targets.size() + " 张）");
        if (sel.cancel) {
            clearPending();
            reply("保存图片", "好的，不存了。");
            return;
        }
        if (sel.outOfRange) {
            reply("保存图片", "屏幕上只有 " + targets.size() + " 张，说「第 1 张」到「第 " + targets.size() + " 张」，或「全部」。");
            return;
        }
        final List<Integer> picks = new ArrayList<Integer>();
        if (sel.all) {
            for (int i = 1; i <= targets.size(); i++) picks.add(i);
        } else {
            picks.addAll(sel.picks);
        }
        final Capture cap = mCap;
        removeBadges();
        mPendingTargets = null;
        mMain.removeCallbacks(mPendingTimeout);
        mBusy = true;
        com.onesay.assistant.v2.util.Threads.start("ImageSaveSel", new Runnable() {
            @Override public void run() {
                try {
                    save(cap, targets, picks);
                } catch (Throwable t) {
                    Log.w(TAG, "[IMGSAVE] 保存异常: " + t);   // 审计 B-8
                    finish("存图失败：" + t.getMessage(), false);
                }
            }
        });
    }

    /** 待选期间用户说了别的：清掉编号与芯片待选状态（主线程）。 */
    void abandonPending() {
        clearPending();
        if (mCap != null && mCap.screen != null && !mBusy) {
            mCap.screen.recycle();
            mCap = null;
        }
    }

    void destroy() {
        mMain.removeCallbacksAndMessages(null);
        clearPending();
        removeOverlay();
    }

    // ------------------------------------------------------------------ 采集（后台线程）

    private void runCapture(ImageSaveIntent.Hit hit, String command) {
        long t0 = SystemClock.elapsedRealtime();
        Capture cap = capture();
        if (cap == null) return;   // capture 已 finish
        long cost = SystemClock.elapsedRealtime() - t0;
        Log.i(TAG, "[IMGSAVE] 采集 " + cost + "ms app=" + cap.pkg + " 屏幕=" + cap.screen.getWidth() + "x" + cap.screen.getHeight()
                + " black=" + cap.black + " 图片节点=" + cap.targets.size() + " " + cap.targets + cap.note);
        mCap = cap;
        if (cap.black) {
            // FLAG_SECURE：截图没法用。节点还在 → 只试缓存层
            if (!cap.targets.isEmpty() && ImageSaveSettings.cacheEnabled(mSvc)) {
                List<Integer> all = new ArrayList<Integer>();
                for (int i = 1; i <= cap.targets.size(); i++) all.add(i);
                save(cap, cap.targets, all);
                return;
            }
            finish("这个页面禁止截图（FLAG_SECURE），截出来是全黑的，没法从屏幕上裁图。"
                    + (ImageSaveSettings.cacheEnabled(mSvc) ? "也没找到能对上的图片节点，缓存取原图也用不上。" : "缓存取原图已关闭。"), false);
            return;
        }
        List<ImageNodeSelector.Target> targets = cap.targets;
        if (targets.isEmpty()) {
            ImageNodeSelector.Target px = pixelFallback(cap);
            if (px != null) targets.add(px);
        }
        ImageSavePlan.Step step = ImageSavePlan.decide(hit, targets.size());
        Log.i(TAG, "[IMGSAVE] 决定 " + step + "（目标 " + targets.size() + " 个）");
        switch (step.kind) {
            case SAVE:
                save(cap, targets, step.picks);
                break;
            case OUT_OF_RANGE:
                finish("屏幕上只识别到 " + targets.size() + " 张图，没有第 " + (hit != null ? hit.ordinal : 0) + " 张。", false);
                break;
            case CHOOSE:
                showChooser(cap, targets, command);
                break;
            default:
                openManual(cap);
        }
    }

    /** root 一次往返：包名 + raw 截图与非压缩 dump 并行。失败返回 null（已 finish）。 */
    private Capture capture() {
        File dir = workDir();
        File raw = new File(dir, "scr.raw");
        File xml = new File(dir, "ui.xml");
        // 2026-10-25 exec 守护 v4（fix-root.md §3「存图截屏」）：不再发复合脚本（v4 拒 ; & $() wait），拆成单条命令依次执行：
        //   ① 前台 Activity（受控管道）② screencap raw ③ uiautomator dump。两个产物文件由 App 先建好（替代 rm -f），root 原地写，
        //   属主 / 标签仍是 App 的；读不了才退回 scratchFix（复合脚本，v4 下需 allow_raw）。
        //   与原脚本的差别：截图与 dump 由并行改为先后执行（多 1~3 s）；__NOW 改用本机时间（同一台设备的时钟）。
        dir.mkdirs();
        com.onesay.assistant.v2.util.PrivateScratch.prepare(raw);
        com.onesay.assistant.v2.util.PrivateScratch.prepare(xml);
        long nowSec = System.currentTimeMillis() / 1000L;
        // ★ r2/fast-screen：先走常驻 root 读屏助手（截图 + 非压缩树，约 0.2 s）；不可用才走原来的三条 root 命令（约 4–10 s）
        com.onesay.assistant.v2.fastscreen.FsCapture.RawAndXml fast = com.onesay.assistant.v2.fastscreen.FsCapture.rawAndXml(mSvc);
        String actOut;
        String dump;
        if (fast != null && com.onesay.assistant.v2.fastscreen.FsCapture.writeFile(raw, fast.raw)) {
            actOut = fast.activityLine;
            dump = fast.xml;
            Log.i(TAG, "[IMGSAVE] 快速读屏 " + fast.ms + "ms");
        } else {
            ShellExecutor rootSh = new ShellExecutor(mSvc);
            ShellExecutor.ShellResponse r = rootSh.execute(captureSteps(raw, xml)[0]);
            if (r.isDown) {
                finish("存图需要 root 守护，守护现在不可用。", false);
                return null;
            }
            rootSh.execute(captureSteps(raw, xml)[1]);
            rootSh.execute(captureSteps(raw, xml)[2]);
            if (com.onesay.assistant.v2.util.PrivateScratch.needsFixup(raw) || com.onesay.assistant.v2.util.PrivateScratch.needsFixup(xml)) {
                Log.w(TAG, "[IMGSAVE] root 产物 App 读不了（没有原地写），退回属主修正");
                rootSh.execute(scratchFix(raw, xml, dir) + " echo __DONE");
            }
            actOut = r.output;
            dump = readText(xml);
        }
        Capture cap = new Capture();
        String[] act = UiDumpParser.parseResumedActivity(actOut);
        cap.pkg = act != null ? act[0] : null;
        cap.captureSec = nowSec;
        cap.screen = readRaw(raw);
        deleteQuietly(raw);
        deleteQuietly(xml);
        if (cap.screen == null) {
            finish("截图失败（screencap 没有产出）。", false);
            return null;
        }
        UiDumpParser.Result pr = UiDumpParser.parse(dump, mSvc.getPackageName());
        if (cap.pkg == null) cap.pkg = pr.rootPackage;
        int w = cap.screen.getWidth(), h = cap.screen.getHeight();
        int sw = 180, sh = Math.max(1, Math.round(h * 180f / w));
        Bitmap small = Bitmap.createScaledBitmap(cap.screen, sw, sh, true);
        int[] spx = new int[sw * sh];
        small.getPixels(spx, 0, sw, 0, 0, sw, sh);
        small.recycle();
        cap.black = PixelHeuristics.isAllBlack(spx, sw, sh);
        if (dump == null) cap.note = "（dump 失败/超时）";
        List<ImageNodeSelector.Target> nodes = ImageNodeSelector.select(pr.nodes, w, h);
        for (ImageNodeSelector.Target t : nodes) cap.targets.add(cap.black ? t : trim(cap.screen, t));
        return cap;
    }

    private ImageNodeSelector.Target trim(Bitmap bmp, ImageNodeSelector.Target t) {
        int tw = t.width(), th = t.height();
        if (tw < 16 || th < 16) return t;
        int[] px = new int[tw * th];
        bmp.getPixels(px, 0, tw, t.left, t.top, tw, th);
        int[] r = PixelHeuristics.trimUniformBorders(px, tw, th, 0, 0, tw, th, 6);
        if (r[0] == 0 && r[1] == 0 && r[2] == tw && r[3] == th) return t;
        ImageNodeSelector.Target out = t.withRect(t.left + r[0], t.top + r[1], t.left + r[2], t.top + r[3]);
        Log.i(TAG, "[IMGSAVE] 裁纯色边 " + t + " → " + out);
        return out;
    }

    private ImageNodeSelector.Target pixelFallback(Capture cap) {
        Bitmap bmp = cap.screen;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int sw = 180, sh = Math.max(1, Math.round(h * 180f / w));
        Bitmap small = Bitmap.createScaledBitmap(bmp, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();
        PixelHeuristics.Region rg = PixelHeuristics.findImageRegion(px, sw, sh, 6);
        Log.i(TAG, "[IMGSAVE] 没有图片节点 → 纹理检测: " + rg);
        if (rg == null || rg.confidence < PixelHeuristics.MIN_CONFIDENCE) return null;
        int[] r = PixelHeuristics.scaleRect(rg, (double) w / sw, (double) h / sh, w, h);
        return trim(bmp, new ImageNodeSelector.Target(r[0], r[1], r[2], r[3], "", "", "PIXEL"));
    }

    // ------------------------------------------------------------------ 存（后台线程）

    private void save(Capture cap, List<ImageNodeSelector.Target> targets, List<Integer> picks) {
        if (cap == null || cap.screen == null) {
            finish("截图已失效，请再说一次「保存图片」。", false);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ImageSavePlan.relativeDir(cap.pkg));
        List<CacheMatcher.Candidate> cands = ImageSaveSettings.cacheEnabled(mSvc) ? cacheCandidates(cap) : null;
        if (cands == null) Log.i(TAG, "[IMGSAVE] 缓存层: 关闭或不可用（imgsave.cache=" + ImageSaveSettings.cacheEnabled(mSvc) + "）");
        Set<String> used = new HashSet<String>();
        List<ImageSavePlan.Tier> tiers = new ArrayList<ImageSavePlan.Tier>();
        List<String> paths = new ArrayList<String>();
        int skippedBlack = 0;
        for (int k = 0; k < picks.size(); k++) {
            int idx = picks.get(k);
            if (idx < 1 || idx > targets.size()) continue;
            ImageNodeSelector.Target t = targets.get(idx - 1);
            File written = null;
            ImageSavePlan.Tier tier = ImageSavePlan.Tier.CROP;
            if (cands != null) {
                CacheMatcher.Decision dec = CacheMatcher.pick(cands, t.width(), t.height(), used);
                Log.i(TAG, "[IMGSAVE] 缓存层 #" + idx + " 目标 " + t.width() + "x" + t.height() + " → " + dec);
                if (dec.chosen != null) {
                    File src = new File(localCopyOf(dec.chosen));
                    written = writeFile(outDir, ImageSavePlan.fileName(stamp, idx, ImageSavePlan.Tier.ORIGINAL, CacheMatcher.extOf(dec.chosen.mime)), src, null);
                    if (written != null) {
                        tier = ImageSavePlan.Tier.ORIGINAL;
                        used.add(dec.chosen.path);
                    }
                }
            }
            if (written == null) {
                if (cap.black) {
                    skippedBlack++;
                    continue;
                }
                Bitmap crop = Bitmap.createBitmap(cap.screen, t.left, t.top, t.width(), t.height());
                written = writeFile(outDir, ImageSavePlan.fileName(stamp, idx, ImageSavePlan.Tier.CROP, "png"), null, crop);
                crop.recycle();
            }
            if (written != null) {
                tiers.add(tier);
                paths.add(written.getAbsolutePath());
                Log.i(TAG, "[IMGSAVE] 已保存 #" + idx + " " + tier + " " + t.source + " → " + written.getAbsolutePath());
            } else {
                Log.w(TAG, "[IMGSAVE] 写入失败 #" + idx);
            }
        }
        cleanupCopies();
        if (!paths.isEmpty()) {
            try {
                MediaScannerConnection.scanFile(mSvc, paths.toArray(new String[0]), null, null);
            } catch (Throwable t) {
                Log.w(TAG, "[IMGSAVE] 媒体扫描异常: " + t.getMessage());
            }
        }
        String msg = ImageSavePlan.summary(tiers);
        if (!paths.isEmpty()) msg += "\n位置：Pictures/" + ImageSavePlan.relativeDir(cap.pkg);
        if (cap.black) msg += "\n这个页面禁止截图（FLAG_SECURE），截图是全黑的" + (skippedBlack > 0 ? "，有 " + skippedBlack + " 张没能从缓存取到原图。" : "。");
        Log.i(TAG, "[IMGSAVE] 结果: " + msg.replace('\n', ' ') + " tiers=" + tiers);
        finish(msg, !paths.isEmpty());
    }

    /** 缓存层：列目录 → 拷出最近的候选 → 读图片头。不可用返回 null。 */
    private List<CacheMatcher.Candidate> cacheCandidates(Capture cap) {
        String list = CacheMatcher.listCommand(cap.pkg);
        if (list == null) return null;
        ShellExecutor sh = new ShellExecutor(mSvc);
        ShellExecutor.ShellResponse r = sh.execute(list);
        // 缓存层要读别的 App 的私有目录（find + stat），exec 守护 v4 的路径白名单不允许，是有意拦下的能力：
        //   被拒就当缓存层不可用（INTEGRATION.md「无法拆」）。
        if (r.isDown || r.isTimeout || r.isDenied) return null;
        List<CacheMatcher.Candidate> cands = CacheMatcher.parseListing(r.output, cap.captureSec, ImageSaveSettings.CACHE_WINDOW_S,
                8 * 1024L, 40L * 1024 * 1024);
        if (cands.isEmpty()) {
            Log.i(TAG, "[IMGSAVE] 缓存层: " + cap.pkg + " 抓图前 " + ImageSaveSettings.CACHE_WINDOW_S + " s 内没有新文件");
            return cands;
        }
        File dir = workDir();
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            String dst = new File(dir, "c_" + i).getAbsolutePath();
            cp.append("cp -f '").append(cands.get(i).path).append("' '").append(dst).append("' 2>/dev/null && ")
              .append(com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(dst, dir.getAbsolutePath())).append("; ");   // ★ IPC-5：外部兜底也 600
        }
        cp.append("true");
        sh.execute(cp.toString());
        for (int i = 0; i < cands.size(); i++) {
            CacheMatcher.Candidate c = cands.get(i);
            byte[] head = readHead(new File(dir, "c_" + i), 256 * 1024);
            String[] mime = new String[1];
            int[] wh = head != null ? CacheMatcher.dimensions(head, head.length, mime) : null;
            if (wh != null) c.dims(wh[0], wh[1], mime[0]);
            mLocalCopies.put(c.path, new File(dir, "c_" + i).getAbsolutePath());
        }
        Log.i(TAG, "[IMGSAVE] 缓存层候选 " + cands.size() + " 个: " + cands);
        return cands;
    }

    private final java.util.HashMap<String, String> mLocalCopies = new java.util.HashMap<String, String>();

    private String localCopyOf(CacheMatcher.Candidate c) {
        String p = mLocalCopies.get(c.path);
        return p != null ? p : "";
    }

    private void cleanupCopies() {
        for (String p : mLocalCopies.values()) deleteQuietly(new File(p));
        mLocalCopies.clear();
    }

    /**
     * 写到 Pictures/OneSay/&lt;包名&gt;/：先直接写（有存储权限时）；不行就写到本 App 外部缓存再用 root 拷过去。
     * src 非空 = 原样拷字节（原图）；否则把 bmp 压成无损 PNG。
     */
    private File writeFile(File outDir, String name, File src, Bitmap bmp) {
        File dst = new File(outDir, name);
        try {
            if (outDir.isDirectory() || outDir.mkdirs()) {
                if (writeTo(dst, src, bmp)) return dst;
            }
        } catch (Throwable t) {
            Log.i(TAG, "[IMGSAVE] 直接写入失败（" + t.getMessage() + "），改用 root 拷贝");
        }
        File tmp = new File(workDir(), "out_" + name);
        try {
            if (!writeTo(tmp, src, bmp)) return null;
            // 2026-10-25 exec 守护 v4：拆成单条命令（mkdir -p → cp -f，任一步失败即失败）；chmod 664 去掉
            //   （sdcardfs 上权限位不生效，且 664 不在 v4 的 chmod 模式白名单里）。注意 v4 目前不允许写 Pictures/，
            //   这条兜底在守护补白名单前会被拒（INTEGRATION.md）；有存储权限时走上面的直接写，不经过 root。
            ShellExecutor.ShellResponse r = com.onesay.assistant.v2.executor.ShellScript.exec(new ShellExecutor(mSvc), rootCopySteps(tmp, dst));
            return r.isSuccess() ? dst : null;
        } catch (Throwable t) {
            Log.w(TAG, "[IMGSAVE] root 拷贝失败: " + t.getMessage());
            return null;
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static boolean writeTo(File dst, File src, Bitmap bmp) throws Exception {
        FileOutputStream out = new FileOutputStream(dst);
        try {
            if (src != null) {
                FileInputStream in = new FileInputStream(src);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    in.close();
                }
                return true;
            }
            return bmp != null && bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------ 多图选择 / 手动框选（主线程）

    private void showChooser(final Capture cap, final List<ImageNodeSelector.Target> targets, final String command) {
        mMain.post(new Runnable() {
            @Override public void run() {
                mBusy = false;
                mPendingTargets = targets;
                mPendingAt = SystemClock.elapsedRealtime();
                mMain.removeCallbacks(mPendingTimeout);
                mMain.postDelayed(mPendingTimeout, ImageSaveSettings.PENDING_TTL_MS);
                mSvc.s1AttachSheet();
                FloatingSheetView sheet = mSvc.getSheetView();
                if (sheet != null) {
                    sheet.showAskReply(command, ImageSavePlan.chooseQuestion(targets.size()));
                    sheet.updateState("选择要存的图", Color.parseColor("#0A84FF"), targets.size() + " 张图");
                    sheet.setDismissLabel("✕ 关闭");
                    showChips(sheet, targets.size());
                }
                showBadges(targets);
                mLastResult = "CHOOSE " + targets.size();
                Log.i(TAG, "[IMGSAVE] 多图待选：编号 " + targets.size() + " 个（芯片 " + ImageNodeSelector.chipLabels(targets.size()) + "）");
            }
        });
    }

    private void showChips(FloatingSheetView sheet, final int n) {
        ActionChipsView v;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) {
            v = (ActionChipsView) existing;
        } else {
            v = new ActionChipsView(mSvc);
            int idx = Math.min(2, sheet.getChildCount());
            sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final List<String> labels = ImageNodeSelector.chipLabels(n);
        v.setListener(new ActionChipsView.Listener() {
            @Override public void onChipClicked(int index) {
                if (index < 0 || index >= labels.size()) return;
                Log.i(TAG, "[IMGSAVE] 芯片点击: " + labels.get(index));
                onSelection(ImageSaveIntent.parseSelection(labels.get(index), n));
            }
        });
        v.showChips(labels);
    }

    private void showBadges(List<ImageNodeSelector.Target> targets) {
        removeBadges();
        for (int i = 0; i < targets.size(); i++) {
            final int no = i + 1;
            ImageNodeSelector.Target t = targets.get(i);
            TextView b = new TextView(mSvc);
            b.setText(String.valueOf(no));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            int size = dp(34);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#E60A84FF"));
            bg.setStroke(dp(2), Color.WHITE);
            b.setBackground(bg);
            b.setContentDescription("第" + no + "张");
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Log.i(TAG, "[IMGSAVE] 徽标点击: 第" + no + "张");
                    onSelection(ImageSaveIntent.parseSelection("第" + no + "张", pendingCount()));
                }
            });
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size, windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = t.left + dp(10);
            p.y = t.top + dp(10);
            try {
                mWm.addView(b, p);
                mBadges.add(b);
            } catch (Throwable e) {
                Log.w(TAG, "[IMGSAVE] 编号徽标挂载失败（悬浮窗权限？）: " + e.getMessage());
            }
        }
    }

    private void removeBadges() {
        for (View v : mBadges) {
            try {
                if (v.getParent() != null) mWm.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
        mBadges.clear();
    }

    private void clearPending() {
        mMain.removeCallbacks(mPendingTimeout);
        removeBadges();
        mPendingTargets = null;
    }

    private void openManual(final Capture cap) {
        mMain.post(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "[IMGSAVE] 识别不到图片 → 手动框选（圈一下）");
                if (mOverlay == null) mOverlay = new RegionSelectOverlay(mSvc);
                mOverlay.setListener(new RegionSelectOverlay.Listener() {
                    @Override public void onSelected(float x0, float y0, float x1, float y1) {
                        int w = cap.screen.getWidth(), h = cap.screen.getHeight();
                        int l = clamp(Math.round(Math.min(x0, x1)), 0, w), t = clamp(Math.round(Math.min(y0, y1)), 0, h);
                        int r = clamp(Math.round(Math.max(x0, x1)), 0, w), b = clamp(Math.round(Math.max(y0, y1)), 0, h);
                        if (r - l < dp(40) || b - t < dp(40)) {
                            mOverlay.setHint("框太小了，再拖一次（或点取消）");
                            return;
                        }
                        removeOverlay();
                        final List<ImageNodeSelector.Target> one = new ArrayList<ImageNodeSelector.Target>();
                        one.add(new ImageNodeSelector.Target(l, t, r, b, "", "", "MANUAL"));
                        final List<Integer> picks = new ArrayList<Integer>();
                        picks.add(1);
                        Log.i(TAG, "[IMGSAVE] 手动框选 " + one.get(0));
                        com.onesay.assistant.v2.util.Threads.start("ImageSaveManual", new Runnable() {
                            @Override public void run() {
                                save(cap, one, picks);
                            }
                        });
                    }

                    @Override public void onCancel() {
                        Log.i(TAG, "[IMGSAVE] 用户取消手动框选");
                        removeOverlay();
                        finish("没有找到能识别的图片，已取消。", false);
                    }
                });
                mOverlay.setHint("没认出图片 · 拖一个框圈住要存的图，松手保存");
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, windowType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.START;
                try {
                    if (mOverlay.getParent() == null) mWm.addView(mOverlay, p);
                    mMain.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (mOverlay != null && mOverlay.getParent() != null) {
                                Log.i(TAG, "[IMGSAVE] 手动框选 30 s 无操作，取消");
                                removeOverlay();
                                finish("没有框选，已取消。", false);
                            }
                        }
                    }, 30000L);
                } catch (Throwable t) {
                    Log.w(TAG, "[IMGSAVE] 框选遮罩挂载异常: " + t);   // 审计 B-8
                    finish("识别不到图片，也无法显示框选遮罩（悬浮窗权限？）。", false);
                }
            }
        });
    }

    private void removeOverlay() {
        if (mOverlay != null && mOverlay.getParent() != null) {
            try {
                mWm.removeViewImmediate(mOverlay);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 收尾

    /** 任意线程：回到主线程，挂回面板显示结果。 */
    private void finish(final String msg, final boolean ok) {
        mLastResult = (ok ? "OK " : "FAIL ") + msg.replace('\n', ' ');
        mMain.post(new Runnable() {
            @Override public void run() {
                mBusy = false;
                if (!hasPending() && mCap != null && mCap.screen != null && (mOverlay == null || mOverlay.getParent() == null)) {
                    mCap.screen.recycle();
                    mCap = null;
                }
                reply("保存图片", msg);
                if (ok) {
                    try {
                        Toast.makeText(mSvc, msg.split("\n")[0], Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private void reply(String q, String msg) {
        mSvc.s1AttachSheet();
        FloatingSheetView sheet = mSvc.getSheetView();
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView && !hasPending()) ((ActionChipsView) existing).hideChips();
        sheet.showAskReply(q, msg);
        sheet.updateState(msg.startsWith("已保存") ? "已保存" : "存图", Color.parseColor(msg.startsWith("已保存") ? "#30D158" : "#FF9F0A"), "保存图片");
        sheet.setDismissLabel("✕ 关闭");
    }

    // ------------------------------------------------------------------ 工具

    private File workDir() {
        // 截图 raw / 非压缩 dump 先放私有目录（root 写完 chown + restorecon，见 capture()）
        File priv = com.onesay.assistant.v2.util.PrivateScratch.dir(mSvc, "imgsave");
        if (priv != null) return priv;
        File base = null;
        try {
            base = mSvc.getExternalCacheDir();
        } catch (Throwable ignored) {
        }
        if (base == null) base = mSvc.getCacheDir();
        File d = new File(base, "imgsave");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * 私有目录 → chown + chmod 600 + restorecon。
     * 外部缓存目录兜底也一样 chown + chmod 600 + restorecon（以前是 chmod 666，
     *   Android 9 上任何持存储权限的 App 能读写），与 ScreenVisionEngine 一致，任何目录都不放开成 666。
     */
    static String scratchFix(File raw, File xml, File dir) {
        return com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(raw.getAbsolutePath(), dir.getAbsolutePath()) + "; "
                + com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(xml.getAbsolutePath(), dir.getAbsolutePath()) + ";";
    }

    /** 存图截屏的三条单命令（纯函数，CI 用 v4 判定模式核对）：前台 Activity、raw 截图、界面树。 */
    static String[] captureSteps(File raw, File xml) {
        return new String[] {
                "dumpsys activity activities | grep -m2 -E 'mResumedActivity|mFocusedActivity'",
                "screencap " + com.onesay.assistant.v2.util.PrivateScratch.quote(raw.getAbsolutePath()),
                "uiautomator dump " + com.onesay.assistant.v2.util.PrivateScratch.quote(xml.getAbsolutePath())};
    }

    /** 无存储权限时经 root 拷到 Pictures 的单命令序列（&& 语义）。 */
    static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> rootCopySteps(File tmp, File dst) {
        return java.util.Arrays.asList(
                com.onesay.assistant.v2.executor.ShellScript.must("mkdir -p " + com.onesay.assistant.v2.util.PrivateScratch.quote(dst.getParentFile().getAbsolutePath())),
                com.onesay.assistant.v2.executor.ShellScript.must("cp -f " + com.onesay.assistant.v2.util.PrivateScratch.quote(tmp.getAbsolutePath())
                        + " " + com.onesay.assistant.v2.util.PrivateScratch.quote(dst.getAbsolutePath())));
    }

    /** raw screencap → Bitmap；头对不上时按 PNG 再试一次。 */
    private static Bitmap readRaw(File f) {
        if (f == null || !f.isFile() || f.length() < 16) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] head = new byte[16];
            raf.readFully(head);
            int w = PixelHeuristics.le32(head, 0), h = PixelHeuristics.le32(head, 4), fmt = PixelHeuristics.le32(head, 8);
            int hl = PixelHeuristics.rawHeaderLength(f.length(), w, h);
            if (hl < 0 || fmt != 1) {   // 1 = RGBA_8888
                raf.close();
                raf = null;
                return BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            FileChannel ch = raf.getChannel();
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, hl, (long) w * h * 4);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "[IMGSAVE] 读截图失败: " + t.getMessage());
            return null;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readText(File f) {
        byte[] b = readHead(f, 4 * 1024 * 1024);
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readHead(File f, int max) {
        if (f == null || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int len = (int) Math.min(max, f.length());
            byte[] b = new byte[len];
            int off = 0, n;
            while (off < len && (n = in.read(b, off, len - off)) > 0) off += n;
            if (off < len) {
                byte[] c = new byte[off];
                System.arraycopy(b, 0, c, 0, off);
                return c;
            }
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @SuppressWarnings("deprecation")
    private static int windowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int v) {
        return (int) (v * mSvc.getResources().getDisplayMetrics().density + 0.5f);
    }
}
