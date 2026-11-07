// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

/**
 * 声纹（说话人验证）注册管理器 —— 对齐 sherpa-onnx v1.12.15 JNI。
 *
 * 支持多说话人：每人可 add 一段或多段（addList）向量。
 *
 * ⚠️ C++ 侧对 embedding 维度做了硬校验，维度不符会 **exit(-1) 杀掉整个进程** ——
 *    调用前务必确认 float[] 长度 == dim（用 SpeakerEmbeddingExtractor.dim() 取得的值）。
 */
public class SpeakerEmbeddingManager {
    private long ptr = 0;
    private final int dim;

    public SpeakerEmbeddingManager(int dim) {
        LibraryLoader.maybeLoad();
        this.dim = dim;
        ptr = create(dim);
        if (ptr == 0) {
            throw new IllegalArgumentException("Failed to create native SpeakerEmbeddingManager");
        }
    }

    public int getDim() {
        return dim;
    }

    /** 注册单人单段向量。 */
    public boolean add(String name, float[] embedding) {
        return add(ptr, name, embedding);
    }

    /** 注册单人多段向量（内部做平均/取质心）。 */
    public boolean addList(String name, float[][] embeddings) {
        return addList(ptr, name, embeddings);
    }

    /** 删除某个说话人。 */
    public boolean remove(String name) {
        return remove(ptr, name);
    }

    /** 在已注册列表里找最相似且超过阈值的人；无命中返回空串。 */
    public String search(float[] embedding, float threshold) {
        return search(ptr, embedding, threshold);
    }

    /** 验证 embedding 是否属于 name 此人。 */
    public boolean verify(String name, float[] embedding, float threshold) {
        return verify(ptr, name, embedding, threshold);
    }

    public boolean contains(String name) {
        return contains(ptr, name);
    }

    public int numSpeakers() {
        return numSpeakers(ptr);
    }

    public String[] allSpeakerNames() {
        return allSpeakerNames(ptr);
    }

    protected void finalize() throws Throwable {
        release();
    }

    public void release() {
        if (this.ptr == 0) {
            return;
        }
        delete(this.ptr);
        this.ptr = 0;
    }

    private native long create(int dim);

    private native void delete(long ptr);

    private native boolean add(long ptr, String name, float[] embedding);

    private native boolean addList(long ptr, String name, float[][] embeddings);

    private native boolean remove(long ptr, String name);

    private native String search(long ptr, float[] embedding, float threshold);

    private native boolean verify(long ptr, String name, float[] embedding, float threshold);

    private native boolean contains(long ptr, String name);

    private native int numSpeakers(long ptr);

    private native String[] allSpeakerNames(long ptr);
}
