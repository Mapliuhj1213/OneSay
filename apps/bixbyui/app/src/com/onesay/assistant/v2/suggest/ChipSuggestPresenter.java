package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.plug.suggest.core.Presenter;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.Suggestion;
import com.onesay.assistant.v2.s1.S1Hooks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ fix/shot-1104：非岛模式下的展示端口 → S1 旧芯片卡（{@link S1Hooks#showSuggestChip}）。标题 =「标题 · 副标题」，按钮 = 卡上的动作。
 * 由 {@link com.onesay.assistant.plug.suggest.core.RoutedPresenter} 在 {@code islandMode()==false} 时选用。
 */
final class ChipSuggestPresenter implements Presenter {
    private volatile Listener mListener;
    private final Map<String, Suggestion> mShown = new LinkedHashMap<String, Suggestion>();

    static String icon(String iconKey) {
        if ("screenshot".equals(iconKey)) return "🖼";
        if ("torch".equals(iconKey)) return "🔦";
        if ("headset".equals(iconKey)) return "🎧";
        return "💡";
    }

    static String line(Suggestion s) {
        String t = s.title == null ? "" : s.title;
        return s.subtitle == null || s.subtitle.length() == 0 ? t : t + " · " + s.subtitle;
    }

    private final S1Hooks.SuggestChipListener mChip = new S1Hooks.SuggestChipListener() {
        @Override public void onChip(String id, int index) {
            Suggestion s;
            synchronized (mShown) {
                s = mShown.get(id);
            }
            SAction a = s == null ? null : s.actionAt(index);
            Listener l = mListener;
            if (l != null && a != null) l.onAction(id, a.id);
        }

        @Override public void onClosed(String id, boolean timeout) {
            Listener l = mListener;
            if (l == null) return;
            if (timeout) l.onTimeout(id);
            else l.onSwipedAway(id);
        }
    };

    @Override public boolean show(Suggestion s) {
        com.onesay.assistant.v2.ui.FloatingSheetService svc = SuggestHost.service();
        if (svc == null) return false;
        synchronized (mShown) {
            mShown.put(s.id, s);
            Iterator<String> it = mShown.keySet().iterator();
            while (mShown.size() > 8) {
                it.next();
                it.remove();
            }
        }
        return S1Hooks.showSuggestChip(svc, s.id, icon(s.iconKey), line(s), s.labels(), s.ttlMs, mChip);
    }

    @Override public void update(String id, String subtitle) {
        Suggestion s;
        synchronized (mShown) {
            s = mShown.get(id);
            if (s != null) {
                s = s.withSubtitle(subtitle);
                mShown.put(id, s);
            }
        }
        if (s != null) S1Hooks.updateSuggestChip(id, icon(s.iconKey), line(s), s.labels());
    }

    @Override public void withdraw(String id) {
        S1Hooks.withdrawSuggestChip(id);
    }

    @Override public void setListener(Listener l) {
        mListener = l;
    }
}
