package com.onesay.assistant.v2.kernel;

import android.content.Context;

import com.onesay.assistant.v2.island.IslandModel;

import java.util.List;

/**
 * [kernel] 灵动岛承载面（第 4 阶段 D-3a）：插件往岛上放活动的唯一通道。
 *
 * <p>以前 S3 直接调 {@code S1Hooks.s3Bg*()}（跨功能硬依赖）；现在是
 * <b>插件 → {@link FeatureKernel#island(String)}（{@link Channel}，带 owner）→ 内核 → 已挂载的 IslandHost（IslandController）→ IslandModel → 渲染</b>。
 * 面板服务没起来 / 岛控制器没挂载时，通道是空操作（与以前 {@code if (sIsland != null)} 完全一致）。
 *
 * <p>实现方（IslandController）的方法都带 owner（插件 id），用来在 {@code --es island status} 里标出每个活动是谁放的。
 */
public interface IslandHost {

    String KIND_TASK = "task";       // 前台任务收起到岛上（S1）
    String KIND_PICKUP = "pickup";   // 取件码 / 取餐码（S1）
    String KIND_BG = "bg";           // 后台定时任务进度 / 确认 / 回执（S3）
    String KIND_REMIND = "remind";   // 到点提醒（S3）
    String KIND_TODO = "todo";       // 待办 chip / 列表（S3）
    String KIND_NOTE = "note";       // ★ R10 记一记：「记录中」流光 + 结果卡（带动作）
    String KIND_NOTICE = "notice";   // ★ 消息提醒队列（队首一条 +N，展开列出全部）
    String KIND_SUGGEST = "suggest"; // ★ feat/suggest 主动建议卡（复用 NOTE 结果卡，优先级最低，岛忙就不放）

    /** ★ feat/suggest 建议卡的用户操作（主线程）。 */
    interface SuggestListener {
        /** 第 index 个按钮（0..2）。 */
        void onSuggestAction(String id, int index);

        void onSuggestTimeout(String id);

        /** 上滑 / 横滑 / 点岛外收起 = 忽略。 */
        void onSuggestSwipedAway(String id);
    }

    /** ★ 岛上消息提醒的用户操作（主线程）。 */
    interface NoticeListener {
        void onNoticeDismiss(String id);

        void onNoticeDismissAll();

        void onNoticeAction(String id, int index);
    }

    /** ★ R10 记一记结果卡 / 码胶囊的回调（主线程）。与 S3 的 {@link Listener} 分开登记，互不覆盖。 */
    interface NoteListener {
        /** 结果卡上第 index 个动作被点。 */
        void onNoteAction(String id, int index);

        /** 结果卡正文 / 紧凑岛被点：打开详情。 */
        void onNoteOpen(String id);

        /** 记一记放上岛的码被关掉（noteId &gt; 0）或过期（expired=true）。 */
        void onCodeGone(long noteId, boolean expired);
    }

    /** 岛上活动的用户操作回调（主线程）。 */
    interface Listener {
        void onBgConfirm(String id, boolean allow);

        void onBgStop(String id);

        void onRemindDone(String id);

        void onRemindSnooze(String id);

        void onTodoCheck(String id);
    }

    Context hostContext();

    boolean islandMode();

    void setListener(String owner, Listener l);

    /** 2026-10-22：只接收本插件自己放上岛的活动的回调（目前只用于「停止」），不替换默认回调。 */
    void setScopedListener(String owner, Listener l);

    void bgStarted(String owner, String id, String title);

    void bgStep(String owner, String id, String text);

    void bgConfirm(String owner, String id, String title, String detail);

    void bgConfirmResolved(String owner, String id);

    void bgEnded(String owner, String id, boolean ok, String reason);

    void remindFired(String owner, String id, String title, long atMs, int lateMin);

    void removeBg(String owner, String id);

    void setTodos(String owner, List<IslandModel.TodoRow> rows, int today, boolean chip);

    void setNoteListener(String owner, NoteListener l);

    void noteStarted(String owner, String id, String title);

    void noteResult(String owner, String id, boolean ok, String title, String detail, String meta, List<String> actions);

    /** 码上岛（复用 S1 取件码卡）：常驻到用户关掉或 ttl 兜底过期；pkg = 点「打开」时拉起的来源 App。返回是否新上岛。 */
    boolean addCode(String owner, String kind, String code, String station, String pkg, long ttlMs, long noteId);

    /** 撤销 / 删除条目时把码从岛上拿掉。 */
    void removeCode(String owner, String kind, String code);

    /** ★：整体刷新岛上的提醒队列（rows 队首在前）。alert = 请求自动展开（岛按忙闲最后裁决，不打断回答 / 任务）。 */
    void setNotices(String owner, List<IslandModel.NoticeRow> rows, boolean alert, boolean high);

    void setNoticeListener(String owner, NoticeListener l);

    /** ★ feat/suggest：只接收建议卡回调。 */
    void setSuggestListener(String owner, SuggestListener l);

    /** ★ feat/suggest：放建议卡。返回 false = 当前不能展示（岛忙 / 关闭 / 非岛模式）。thumbPath 只在本机解码缩略图。 */
    boolean suggestShow(String owner, String id, String title, String subtitle, String thumbPath, List<String> actions, long ttlMs);

    /** ★ feat/suggest：就地改副标题（连拍计数）。 */
    void suggestUpdate(String owner, String id, String subtitle);

    /** ★ feat/suggest：撤卡。 */
    void suggestWithdraw(String owner, String id);

    /**
     * 插件拿到的通道（不用自己带 owner）。没挂载岛 = 全部空操作、{@link #ready()} 为 false。
     * 插件 kernelGated 且总开关关闭时，通道也丢弃（新插件的约定；迁移的老插件 kernelGated=false，行为不变）。
     */
    final class Channel {
        private final FeatureKernel mKernel;
        private final String mOwner;

        Channel(FeatureKernel k, String owner) {
            mKernel = k;
            mOwner = owner;
        }

        public String owner() {
            return mOwner;
        }

        /** 岛控制器已挂载（且本插件没被内核关掉）。 */
        public boolean ready() {
            return target(null) != null;
        }

        public boolean islandMode() {
            IslandHost h = target(null);
            return h != null && h.islandMode();
        }

        public void setListener(Listener l) {
            IslandHost h = target(null);
            if (h != null) h.setListener(mOwner, l);
        }

        public void setScopedListener(Listener l) {
            IslandHost h = target(null);
            if (h != null) h.setScopedListener(mOwner, l);
        }

        public void bgStarted(String id, String title) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.bgStarted(mOwner, id, title);
        }

        public void bgStep(String id, String text) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.bgStep(mOwner, id, text);
        }

        public void bgConfirm(String id, String title, String detail) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.bgConfirm(mOwner, id, title, detail);
        }

        public void bgConfirmResolved(String id) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.bgConfirmResolved(mOwner, id);
        }

        public void bgEnded(String id, boolean ok, String reason) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.bgEnded(mOwner, id, ok, reason);
        }

        public void remindFired(String id, String title, long atMs, int lateMin) {
            IslandHost h = target(KIND_REMIND);
            if (h != null) h.remindFired(mOwner, id, title, atMs, lateMin);
        }

        public void removeBg(String id) {
            IslandHost h = target(KIND_BG);
            if (h != null) h.removeBg(mOwner, id);
        }

        /**
         * 2026-10-23（lane-a B-9）：撤下本插件放上岛的后台活动，<b>不看总开关</b>（插件被 kill-file / 开关关掉后也要能把自己留在岛上的东西拿掉）。
         * 只有「撤下」不受开关门控；放新活动仍走 {@link #removeBg} 等正常门控。插件 FAILED 时同样放行（撤下总是安全的）。
         */
        public void cleanupBg(String id) {
            IslandHost h = mKernel.islandCleanupTarget();
            if (h != null) h.removeBg(mOwner, id);
        }

        public void setTodos(List<IslandModel.TodoRow> rows, int today, boolean chip) {
            IslandHost h = target(KIND_TODO);
            if (h != null) h.setTodos(mOwner, rows, today, chip);
        }

        public void setNoteListener(NoteListener l) {
            IslandHost h = target(null);
            if (h != null) h.setNoteListener(mOwner, l);
        }

        public void noteStarted(String id, String title) {
            IslandHost h = target(KIND_NOTE);
            if (h != null) h.noteStarted(mOwner, id, title);
        }

        public void noteResult(String id, boolean ok, String title, String detail, String meta, List<String> actions) {
            IslandHost h = target(KIND_NOTE);
            if (h != null) h.noteResult(mOwner, id, ok, title, detail, meta, actions);
        }

        public boolean addCode(String kind, String code, String station, String pkg, long ttlMs, long noteId) {
            IslandHost h = target(KIND_PICKUP);
            return h != null && h.addCode(mOwner, kind, code, station, pkg, ttlMs, noteId);
        }

        public void removeCode(String kind, String code) {
            IslandHost h = target(KIND_PICKUP);
            if (h != null) h.removeCode(mOwner, kind, code);
        }

        public void setNotices(List<IslandModel.NoticeRow> rows, boolean alert, boolean high) {
            IslandHost h = target(KIND_NOTICE);
            if (h != null) h.setNotices(mOwner, rows, alert, high);
        }

        /** 撤下全部提醒，不看开关（总开关关掉后也要能把留在岛上的拿掉）。 */
        public void cleanupNotices() {
            IslandHost h = mKernel.islandCleanupTarget();
            if (h != null) h.setNotices(mOwner, java.util.Collections.<IslandModel.NoticeRow>emptyList(), false, false);
        }

        public void setNoticeListener(NoticeListener l) {
            IslandHost h = target(null);
            if (h != null) h.setNoticeListener(mOwner, l);
        }

        public void setSuggestListener(SuggestListener l) {
            IslandHost h = target(null);
            if (h != null) h.setSuggestListener(mOwner, l);
        }

        public boolean suggestShow(String id, String title, String subtitle, String thumbPath, List<String> actions, long ttlMs) {
            IslandHost h = target(KIND_SUGGEST);
            return h != null && h.suggestShow(mOwner, id, title, subtitle, thumbPath, actions, ttlMs);
        }

        public void suggestUpdate(String id, String subtitle) {
            IslandHost h = target(KIND_SUGGEST);
            if (h != null) h.suggestUpdate(mOwner, id, subtitle);
        }

        /** 撤卡不看开关（插件被关掉后也要能把自己的卡拿掉）。 */
        public void suggestWithdraw(String id) {
            IslandHost h = mKernel.islandCleanupTarget();
            if (h != null) h.suggestWithdraw(mOwner, id);
        }

        private IslandHost target(String kind) {
            return mKernel.islandTarget(mOwner, kind);
        }
    }
}
