// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

/**
 * 声纹（说话人验证）特征提取器 —— 对齐 sherpa-onnx v1.12.15 JNI。
 *
 * native 签名逐参对照官方 jni/speaker-embedding-extractor.cc：
 *   newFromFile(JNIEnv, jobject, jobject config)   → 只收 config 对象（勿用旧版 4 参形态）
 *   newFromAsset(JNIEnv, jobject, jobject mgr, jobject config)
 *   createStream(ptr)                              → 返回 OnlineStream*，可直接复用 OnlineStream
 *   isReady(ptr, streamPtr) / compute(ptr, streamPtr) / dim(ptr) / delete(ptr)
 */
public class SpeakerEmbeddingExtractor {
    private long ptr = 0;

    public SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig config) {
        LibraryLoader.maybeLoad();
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalArgumentException(
                    "Invalid SpeakerEmbeddingExtractorConfig: failed to create native SpeakerEmbeddingExtractor");
        }
    }

    public SpeakerEmbeddingExtractor(AssetManager assetManager, SpeakerEmbeddingExtractorConfig config) {
        LibraryLoader.maybeLoad();
        ptr = newFromAsset(assetManager, config);
        if (ptr == 0) {
            throw new IllegalArgumentException(
                    "Invalid SpeakerEmbeddingExtractorConfig: failed to create native SpeakerEmbeddingExtractor from asset");
        }
    }

    /** 新建一条音频输入流（返回的 OnlineStream 由调用方负责 release）。 */
    public OnlineStream createStream() {
        long p = createStream(ptr);
        return new OnlineStream(p);
    }

    /** 是否已累积到足够帧可以出向量（音频太短会返回 false）。 */
    public boolean isReady(OnlineStream stream) {
        return isReady(ptr, stream.getPtr());
    }

    /** 计算 embedding（192 维，依模型而定）。 */
    public float[] compute(OnlineStream stream) {
        return compute(ptr, stream.getPtr());
    }

    /** embedding 维度。 */
    public int dim() {
        return dim(ptr);
    }

    protected void finalize() throws Throwable {
        release();
    }

    // 用完请手动调用
    public void release() {
        if (this.ptr == 0) {
            return;
        }
        delete(this.ptr);
        this.ptr = 0;
    }

    private native long newFromFile(SpeakerEmbeddingExtractorConfig config);

    private native long newFromAsset(AssetManager assetManager, SpeakerEmbeddingExtractorConfig config);

    private native void delete(long ptr);

    private native long createStream(long ptr);

    private native boolean isReady(long ptr, long streamPtr);

    private native float[] compute(long ptr, long streamPtr);

    private native int dim(long ptr);
}
