package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.plug.suggest.core.Presenter;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.Suggestion;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.IslandHost;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ feat/suggest：Presenter → 灵动岛 suggest 卡（IslandHost.suggestShow，渲染复用 NOTE 结果卡）。
 * 非岛模式 / 岛忙 / 面板在屏幕上 → show 返回 false（引擎记 SUPPRESSED，剪贴板场景由 SuggestHost 退回旧卡片）。
 */
final class IslandSuggestPresenter implements Presenter {
    static final String OWNER = SuggestPlugs.MASTER;

    private volatile Listener mListener;
    private final Map<String, Suggestion> mShown = new LinkedHashMap<String, Suggestion>();

    private final IslandHost.SuggestListener mIsland = new IslandHost.SuggestListener() {
        @Override public void onSuggestAction(String id, int index) {
            Suggestion s;
            synchronized (mShown) {
                s = mShown.get(id);
            }
            Listener l = mListener;
            SAction a = s == null ? null : s.actionAt(index);
            if (l != null && a != null) l.onAction(id, a.id);
        }

        @Override public void onSuggestTimeout(String id) {
            Listener l = mListener;
            if (l != null) l.onTimeout(id);
        }

        @Override public void onSuggestSwipedAway(String id) {
            Listener l = mListener;
            if (l != null) l.onSwipedAway(id);
        }
    };

    private IslandHost.Channel ch() {
        return FeatureKernel.get().island(OWNER);
    }

    @Override public boolean show(Suggestion s) {
        IslandHost.Channel c = ch();
        if (!c.islandMode()) return false;
        c.setSuggestListener(mIsland);   // 每次重登：岛控制器可能是后挂上的
        synchronized (mShown) {
            mShown.put(s.id, s);
            Iterator<String> it = mShown.keySet().iterator();
            while (mShown.size() > 8) {
                it.next();
                it.remove();
            }
        }
        return c.suggestShow(s.id, s.title, s.subtitle, s.thumbRef, s.labels(), s.ttlMs);
    }

    @Override public void update(String id, String subtitle) {
        ch().suggestUpdate(id, subtitle);
    }

    @Override public void withdraw(String id) {
        ch().suggestWithdraw(id);
    }

    @Override public void setListener(Listener l) {
        mListener = l;
    }
}
