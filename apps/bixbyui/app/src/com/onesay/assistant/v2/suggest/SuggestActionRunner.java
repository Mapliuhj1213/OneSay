package com.onesay.assistant.v2.suggest;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.ActionRunner;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.Suggestion;
import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.HeadsetScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkScene;
import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.TorchScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;
import com.onesay.assistant.v2.suggest.shot.ScreenshotAnalyzer;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * ★ feat/suggest：用户点了 RUN 按钮后的执行（主线程）。
 * <ul>
 *   <li>截图「分析」→ ScreenshotAnalyzer（这时才上传）；</li>
 *   <li>剪贴板芯片 → S1 原链路（ClipboardWatcher.execute → ExecutionGate → 确认卡 → 执行器），行为与旧芯片一致；</li>
 *   <li>链接「在 X 打开」→ ACTION_VIEW + setPackage；解析不到就直接拉起 App（口令类本来就是拉起 App 让它读剪贴板）——双路径，日志记走了哪条；</li>
 *   <li>耳机 / 新应用「打开」→ 启动器入口；手电筒「关闭」→ CameraManager.setTorchMode(false)。</li>
 * </ul>
 * 打开 App / 关手电筒没有外发副作用，不经确认卡；有副作用的（拨号、建日程、发消息）一律走 ExecutionGate。
 */
final class SuggestActionRunner implements ActionRunner {
    static final String TAG = "Suggest";
    private final FloatingSheetService mSvc;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    SuggestActionRunner(FloatingSheetService svc) {
        mSvc = svc;
    }

    @Override public void run(final Suggestion s, final SAction a, final Done done) {
        mMain.post(new Runnable() {
            @Override public void run() {
                try {
                    runMain(s, a, done);
                } catch (Throwable t) {
                    Log.w(TAG, "[SUGGEST] 执行异常: " + t.getClass().getSimpleName());
                    if (done != null) done.finished(false, "error");
                }
            }
        });
    }

    private void runMain(Suggestion s, SAction a, final Done done) {
        String scene = s.sceneId;
        if (ScreenshotScene.ID.equals(scene) && ScreenshotScene.ACT_ANALYZE.equals(a.id)) {
            ScreenshotAnalyzer.analyze(mSvc, s.get("path"), new ScreenshotAnalyzer.Done() {
                @Override public void finished(boolean ok, String reason) {
                    if (done != null) done.finished(ok, reason);
                }
            });
            return;
        }
        if (ClipScene.ID.equals(scene)) {
            boolean ok = com.onesay.assistant.v2.s1.S1Hooks.runClipChip(s.get("token"), ClipScene.chipIndex(a.id));
            finish(done, ok, ok ? null : "clip_gone");
            return;
        }
        if (LinkScene.ID.equals(scene)) {
            finish(done, openLink(s.get("pkg"), s.get("url")), "open_failed");
            return;
        }
        if (HeadsetScene.ID.equals(scene)) {
            finish(done, launch(HeadsetScene.ACT_OPEN2.equals(a.id) ? s.get("pkg2") : s.get("pkg")), "launch_failed");
            return;
        }
        if (PackageScene.ID.equals(scene)) {
            finish(done, launch(s.get("pkg")), "launch_failed");
            return;
        }
        if (TorchScene.ID.equals(scene)) {
            finish(done, torchOff(s.get("camera_id")), "torch_failed");
            return;
        }
        finish(done, false, "unknown_action");
    }

    private static void finish(Done d, boolean ok, String why) {
        if (d != null) d.finished(ok, ok ? null : why);
    }

    boolean openLink(String pkg, String url) {
        if (pkg == null) return false;
        if (url != null) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(mSvc.getPackageManager()) != null) {
                    mSvc.startActivity(i);
                    Log.i(TAG, "[SUGGEST] 链接 → App（ACTION_VIEW）");
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "[SUGGEST] ACTION_VIEW 失败: " + t.getClass().getSimpleName());
            }
        }
        boolean ok = launch(pkg);
        Log.i(TAG, "[SUGGEST] 链接 → App（" + (url == null ? "口令：" : "VIEW 解析不到，") + "直接拉起）" + (ok ? "ok" : "失败"));
        return ok;
    }

    boolean launch(String pkg) {
        if (pkg == null) return false;
        try {
            Intent li = mSvc.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) return false;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mSvc.startActivity(li);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean torchOff(String camId) {
        try {
            CameraManager cm = (CameraManager) mSvc.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return false;
            if (camId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) {
                        camId = id;
                        break;
                    }
                }
            }
            if (camId == null) return false;
            cm.setTorchMode(camId, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 关手电筒失败: " + t.getClass().getSimpleName());
            return false;
        }
    }
}
