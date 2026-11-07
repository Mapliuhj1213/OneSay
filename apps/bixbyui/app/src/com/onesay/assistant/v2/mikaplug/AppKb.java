package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import com.onesay.assistant.plug.appkb.core.KnowledgeIndex;


/**
 * ★ mika-plug（mika.appkb）：App 知识包的宿主适配。知识文件在 assets 根目录 {@code kb_app_*.md} / {@code kb_site_*.md}
 * （assets 打包是平铺的，不用子目录），格式照 Mika APP.md（front matter + Markdown）。进程内只加载一次。
 * JVM 单测没有 Context 时从 {@code -Dbixby.root}/app/assets 读。
 */
public final class AppKb {
    private static volatile KnowledgeIndex sIndex;
    public static final int PER_PACK_CHARS = 1500;

    private final KnowledgeIndex mIndex;

    private AppKb(KnowledgeIndex i) {
        mIndex = i;
    }

    public static AppKb get(Context ctx) {
        KnowledgeIndex i = sIndex;
        if (i == null) {
            synchronized (AppKb.class) {
                if (sIndex == null) sIndex = load(ctx);
                i = sIndex;
            }
        }
        return new AppKb(i);
    }

    public KnowledgeIndex index() {
        return mIndex;
    }

    public KnowledgeIndex.Injected inject(String task, int max) {
        return mIndex.inject(task, max, PER_PACK_CHARS);
    }

    static KnowledgeIndex load(Context ctx) {
        // ★ skill-pack：与技能包共用 AssetMd 加载器（平铺 assets，按前缀区分）
        KnowledgeIndex idx = new KnowledgeIndex();
        for (String md : AssetMd.load(ctx, "kb_").values()) idx.add(md);
        return idx;
    }

    /** 单测用：换一个索引（null = 下次重新加载）。 */
    public static void reset(KnowledgeIndex i) {
        sIndex = i;
    }
}
