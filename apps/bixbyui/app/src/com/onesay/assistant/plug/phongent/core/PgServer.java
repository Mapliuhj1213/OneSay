/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/RemoteBridge.kt 的 serve() 接入循环（Kotlin → Java 7）。
 * 保留：有界线程池（满了直接关连接）、读请求 4 秒超时、坏请求静默关闭不记正文。
 * 改动：接入前先过来源地址闸（{@link PgNet}，只收局域网 / Tailscale）；另起 1 秒一次的状态同步（从 OneSay 任务队列拉状态，
 * 有变化才唤醒事件流）；流式连接设并发上限。
 */
package com.onesay.assistant.plug.phongent.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** [phongent] TCP 接入。纯 Java（java.net），JVM 可在回环上起一个真服务器做端到端测试。 */
public final class PgServer {

    public static final int THREADS = 8;
    public static final int MAX_STREAMS = 4;

    private final PgApi mApi;
    private final PgTasks mTasks;
    private final PgState.Signal mSignal;
    private final PlugConfig mPolicy;
    private final PlugLog mLog;
    private ServerSocket mServer;
    private ThreadPoolExecutor mPool;
    private Thread mAccept, mTicker;
    private final AtomicInteger mStreams = new AtomicInteger();
    public final AtomicInteger rejectedPeers = new AtomicInteger();

    public PgServer(PgApi api, PgTasks tasks, PgState.Signal signal, PlugConfig policy, PlugLog log) {
        mApi = api;
        mTasks = tasks;
        mSignal = signal;
        mPolicy = policy;
        mLog = log;
    }

    /** 绑定并开始接入。port=0 = 系统挑。返回实际端口。 */
    public synchronized int start(InetAddress bind, int port) throws IOException {
        if (mServer != null) return mServer.getLocalPort();
        final ServerSocket server = new ServerSocket(port, 16, bind);
        mServer = server;
        mApi.closed = false;
        mPool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(THREADS));
        mAccept = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop(server);
            }
        }, "pg-accept");
        mAccept.setDaemon(true);
        mAccept.start();
        mTicker = new Thread(new Runnable() {
            @Override public void run() {
                while (!server.isClosed()) {
                    try {
                        if (mTasks.sync()) mSignal.signal();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "pg-ticker");
        mTicker.setDaemon(true);
        mTicker.start();
        return server.getLocalPort();
    }

    public synchronized int port() {
        return mServer == null ? -1 : mServer.getLocalPort();
    }

    public synchronized void stop() {
        mApi.closed = true;
        try {
            if (mServer != null) mServer.close();
        } catch (IOException ignored) {
        }
        mServer = null;
        if (mPool != null) mPool.shutdownNow();
        mPool = null;
        if (mTicker != null) mTicker.interrupt();
        mSignal.signal();
    }

    private void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                break;
            }
            if (!PgNet.allowed(s.getInetAddress(), mPolicy.tailscaleOnly(), mPolicy.allowLoopback())) {
                rejectedPeers.incrementAndGet();
                mLog.i("[PG] 拒绝非局域网 / 非 Tailscale 来源的连接");
                closeQuietly(s);
                continue;
            }
            ThreadPoolExecutor pool = mPool;
            try {
                if (pool == null) throw new RejectedExecutionException();
                pool.execute(new Runnable() {
                    @Override public void run() {
                        serve(s);
                    }
                });
            } catch (RejectedExecutionException e) {
                closeQuietly(s);
            }
        }
    }

    private void serve(Socket s) {
        boolean stream = false;
        try {
            s.setSoTimeout(4000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            PgHttp.Request rq = PgHttp.read(new BufferedInputStream(s.getInputStream()), PgHttp.MAX_BODY);
            stream = "GET".equals(rq.method) && ("/events".equals(rq.path) || "/video".equals(rq.path));
            OutputStream out = s.getOutputStream();
            if (stream) {
                if (mStreams.incrementAndGet() > MAX_STREAMS) {
                    PgHttp.writeJson(out, 429, PgHttp.err("too_many_streams"));
                    return;
                }
                s.setSoTimeout(0);
            }
            mApi.handle(rq, out);
        } catch (Throwable ignored) {
            // 坏请求 / 断开：静默关闭，不记正文
        } finally {
            if (stream) mStreams.decrementAndGet();
            closeQuietly(s);
        }
    }

    static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
