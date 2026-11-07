/*
 * [phongent 插件 · 入口] OneSay 自写的装配外壳（协议部分改写自 phongent，见各 Pg*.java 的改动声明）。
 * 见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.security.SecureRandom;

/**
 * phongent 兼容执行端插件的唯一入口。
 *
 * <p>生命周期约定：构造 = 零副作用（不开端口、不起线程、不碰通知来源）；{@link #start} 才监听 / attach；
 * {@link #stop} 把 start 做的全部撤掉（可重复调用）。宿主的总开关关着时根本不 start。
 */
public final class PhongentPlug {

    /** 插件描述（与 arch/mika-plug 的 com.onesay.assistant.plug.api.PlugSpec 字段一一对应，合并后可直接 new PlugSpec(ID, VERSION, …)）。 */
    public static final String ID = "phongent";
    public static final int VERSION = 1;
    public static final String TITLE = "多设备执行端（phongent 兼容）";
    public static final boolean DEFAULT_ON = false;
    public static final String[] DEPS = {};
    /** 改写的上游文件（相对 phongent 仓库根，commit 0b57e35）。 */
    public static final String[] PORTED = {
            "app/src/main/java/com/wangzan101/mika/remote/RemoteBridge.kt",
            "app/src/main/java/com/wangzan101/mika/remote/LanAccess.kt",
            "app/src/main/java/com/wangzan101/mika/remote/LanService.kt",
            "app/src/main/java/com/wangzan101/mika/remote/BridgeEvents.kt",
            "app/src/main/java/com/wangzan101/mika/remote/RemoteTaskQueue.kt",
            "app/src/main/java/com/wangzan101/mika/remote/MessageListener.kt",
            "app/src/main/java/com/wangzan101/mika/remote/RemoteScreen.kt",
            "app/src/main/java/com/wangzan101/mika/remote/VirtualVideo.kt",
            "app/src/main/java/com/wangzan101/mika/remote/VideoCapture.kt",
            "core/src/main/kotlin/com/wangzan101/mika/core/run/ControllerQueue.kt"};

    /** mDNS 服务类型 / TXT（宿主用自己的 NSD 注册，iOS 主控只认 transport=tcp-http-v1、id 为 UUID）。 */
    public static final String SERVICE_TYPE = "_mika-agent._tcp.";
    public static final String TRANSPORT = "tcp-http-v1";

    private final PlugConfig mConfig;
    private final Clock mClock;
    private final PlugLog mLog;
    private final HostInfo mHost;
    private final NotificationSource mNotices;
    private final PgAuth mAuth;
    private final PgTasks mTasks;
    private final PgMessages mMessages = new PgMessages();
    private final PgState.Signal mSignal = new PgState.Signal();
    private final PgScreen mScreen;
    private PgServer mServer;

    public PhongentPlug(PlugConfig config, Storage storage, Clock clock, PlugLog log, HostInfo host, Approver approver,
                        TaskExecutor executor, NotificationSource notices, ScreenSource screen, InputSink input) {
        mConfig = config;
        mClock = clock == null ? Clock.SYSTEM : clock;
        mLog = log == null ? PlugLog.NONE : log;
        mHost = host;
        mNotices = notices;
        mAuth = new PgAuth(storage, approver, mClock, new SecureRandom());
        mTasks = new PgTasks(storage, executor);
        mScreen = screen == null ? null : new PgScreen(screen, input, approver, mClock);
        mTasks.setOnChange(new Runnable() {
            @Override public void run() {
                mSignal.signal();
            }
        });
        mMessages.setOnChange(new Runnable() {
            @Override public void run() {
                mSignal.signal();
            }
        });
    }

    /** 开始监听（bind 一般是 0.0.0.0；来源另经 PgNet 过滤）。返回实际端口。 */
    public synchronized int start(InetAddress bind, int port) throws IOException {
        if (mServer != null) return mServer.port();
        PgApi api = new PgApi(mAuth, mTasks, mMessages, mHost, mSignal, mScreen, mConfig);
        PgServer server = new PgServer(api, mTasks, mSignal, mConfig, mLog);
        int p = server.start(bind, port);
        mServer = server;
        if (mNotices != null) {
            mNotices.attach(new NotificationSource.Sink() {
                @Override public void post(NotificationSource.Notice n) {
                    if (mConfig.relayNotifications()) mMessages.post(n, mClock.wall());
                }
            });
        }
        mLog.i("[PG] 执行端已监听端口 " + p);
        return p;
    }

    public synchronized void stop() {
        if (mNotices != null) mNotices.detach();
        if (mServer != null) mServer.stop();
        mServer = null;
        mAuth.clearPending();
        if (mScreen != null) mScreen.stop("执行端已关闭");
        mMessages.clear();
        mLog.i("[PG] 执行端已停止");
    }

    public synchronized boolean running() {
        return mServer != null;
    }

    public synchronized int port() {
        return mServer == null ? -1 : mServer.port();
    }

    public int rejectedPeers() {
        PgServer s = mServer;
        return s == null ? 0 : s.rejectedPeers.get();
    }

    // ------------------------------------------------------------------ 本机确认回调（宿主 UI 调）

    public boolean decidePair(String requestId, boolean allow) {
        return mAuth.decide(requestId, allow);
    }

    public String pendingPairName(String requestId) {
        return mAuth.pendingName(requestId);
    }

    public void decideScreen(boolean allow) {
        if (mScreen != null) mScreen.decide(allow);
    }

    public void stopScreen() {
        if (mScreen != null) mScreen.stop("本机已停止共享");
    }

    // ------------------------------------------------------------------ 已信任主控（宿主设置页）

    public JSONArray peers() {
        return mAuth.peers();
    }

    public boolean revoke(String peerKey) {
        return mAuth.revoke(peerKey);
    }

    public int revokeAll() {
        return mAuth.revokeAll();
    }

    /**
     * 本机手动配对（收不到 mDNS 时，例如走 Tailscale）：本机发一个 token，拼成 iOS 主控能导入的
     * mikaremote://pair?url=…&amp;token=… 链接。链接即凭据，只该在本机屏幕上给用户看。失败返回 null。
     */
    public String manualPairLink(String controllerName, String host, int port) {
        String token = mAuth.issueLocal(controllerName);
        if (token == null) return null;
        try {
            return "mikaremote://pair?url=" + URLEncoder.encode("http://" + host + ":" + port, "UTF-8") + "&token=" + token;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 只给测试 / 诊断

    PgTasks tasks() {
        return mTasks;
    }

    PgMessages messages() {
        return mMessages;
    }
}
