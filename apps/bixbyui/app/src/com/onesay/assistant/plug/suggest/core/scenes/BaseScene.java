/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import com.onesay.assistant.plug.suggest.core.Outcome;
import com.onesay.assistant.plug.suggest.core.Scene;
import com.onesay.assistant.plug.suggest.core.SceneContext;
import com.onesay.assistant.plug.suggest.core.SuggestDefaults;
import com.onesay.assistant.plug.suggest.core.Suggestion;

/** 场景公共样板：id / 标题 / 隐私等级 / 接哪类信号；默认开关按 D5。 */
public abstract class BaseScene implements Scene {
    private final String mId, mTitle;
    private final int mPrivacy;
    private final boolean mSkeleton;
    private final String[] mTypes;

    protected BaseScene(String id, String title, int privacy, boolean skeleton, String... types) {
        mId = id;
        mTitle = title;
        mPrivacy = privacy;
        mSkeleton = skeleton;
        mTypes = types == null ? new String[0] : types;
    }

    @Override public final String id() { return mId; }
    @Override public final String title() { return mTitle; }
    @Override public final int privacyClass() { return mPrivacy; }
    @Override public boolean defaultOn() { return SuggestDefaults.defaultSceneOn(mPrivacy, mSkeleton); }

    public final boolean skeleton() { return mSkeleton; }

    @Override public boolean accepts(String t) {
        for (String x : mTypes) if (x.equals(t)) return true;
        return false;
    }

    @Override public void onOutcome(Suggestion s, Outcome o, SceneContext ctx) {
    }

    /** 文本散列（去重键用，不留原文）。 */
    public static String hash(String t) {
        if (t == null) return "0";
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < t.length(); i++) {
            h ^= t.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }
}
