package com.onesay.desktop;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 连中转的 WebSocket 客户端：自动重连（指数退避）、25 秒文本心跳、拼接分片帧、串行发送。 */
public final class RelayClient implements Link {
    /** 兼容旧名。 */
    public interface Listener extends Link.Listener {}

    private final Config cfg;
    private final Link.Listener listener;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onesay-relay");
        t.setDaemon(true);
        return t;
    });
    private volatile WebSocket ws;
    private volatile boolean stopped;
    private volatile boolean connected;
    private long backoffMs = 1000;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public RelayClient(Config cfg, Link.Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    @Override public String name() { return "中转"; }

    @Override public boolean isConnected() { return connected; }

    public void start() {
        sched.execute(this::connect);
        sched.scheduleAtFixedRate(() -> {
            WebSocket w = ws;
            if (w != null && connected) send("ping");
        }, 25, 25, TimeUnit.SECONDS);
    }

    public void stop() {
        stopped = true;
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        sched.shutdownNow();
    }

    private void connect() {
        if (stopped) return;
        try {
            ws = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + cfg.relayToken)
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(cfg.wsUri(), new L())
                    .get(20, TimeUnit.SECONDS);
            connected = true;
            backoffMs = 1000;
            listener.onState(true, "connected");
        } catch (Exception e) {
            connected = false;
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String why = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
            listener.onState(false, "connect failed: " + why);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopped) return;
        long d = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 60_000);
        try { sched.schedule(this::connect, d, TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
    }

    /** 串行发送（Java WebSocket 不允许并发 sendText）。 */
    public synchronized CompletableFuture<?> send(String text) {
        WebSocket w = ws;
        if (w == null) return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
        sendChain = sendChain.handle((a, b) -> null).thenCompose(x -> w.sendText(text, true));
        return sendChain;
    }

    private final class L implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String s = buf.toString();
                buf.setLength(0);
                if (!s.equals("pong")) {
                    try { listener.onFrame(Json.obj(s)); } catch (Exception e) { listener.onState(true, "bad frame: " + e.getMessage()); }
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            connected = false;
            listener.onState(false, "closed " + code + " " + reason);
            if (code != 4000) scheduleReconnect(); // 4000 = 同 id 在别处登录，被顶掉，不抢回
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            connected = false;
            listener.onState(false, "error: " + error.getMessage());
            scheduleReconnect();
        }
    }

    public static Consumer<String> log = s -> System.err.println("[onesay] " + s);
}
