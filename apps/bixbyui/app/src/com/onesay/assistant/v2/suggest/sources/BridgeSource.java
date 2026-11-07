package com.onesay.assistant.v2.suggest.sources;

import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;

/** ★ feat/suggest：桥接源 —— 现有链路（剪贴板 ClipboardWatcher、取件码 IslandController、调试注入）把信号递进引擎。没 attach 时丢弃。 */
public final class BridgeSource implements SignalSource {
    private final String mType;
    private volatile Sink mSink;

    public BridgeSource(String type) {
        mType = type;
    }

    @Override public String type() {
        return mType;
    }

    @Override public void attach(Sink sink) {
        mSink = sink;
    }

    @Override public void detach() {
        mSink = null;
    }

    public boolean attached() {
        return mSink != null;
    }

    public boolean emit(Signal s) {
        Sink k = mSink;
        if (k == null || s == null) return false;
        k.emit(s);
        return true;
    }
}
