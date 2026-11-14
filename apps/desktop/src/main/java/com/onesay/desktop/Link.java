package com.onesay.desktop;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 一条通道：局域网直连或中转。收到的帧统一成中转格式 {type, from, id, body}。 */
public interface Link {
    interface Listener {
        void onFrame(Map<String, Object> frame);
        default void onState(boolean connected, String why) {}
    }

    void start();
    void stop();
    boolean isConnected();
    /** 发一条外层信封 {type:"msg", to, id, body}。 */
    CompletableFuture<?> send(String text);
    String name();
}
