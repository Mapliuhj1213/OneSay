package com.onesay.assistant.v2.router.longctx;

/**
 * ★ 2026-11-23 用户定：「清空聊天存档」—— 存档（每轮原话 + 完整答复，应用私有目录）是单纯的聊天记录，和长期记忆分开。
 *
 * <p>两个入口，都先确认、确认了才删：
 * <ul>
 *   <li>设置 › 基础设置 › 隐私与审批 › 「清空聊天存档」：弹确认框（{@link #request}）；</li>
 *   <li>语音「清空聊天存档」：快路径命中 {@link #SIGN}（DESTRUCTIVE / risk 2 + confirm → 红色确认卡，签名带 clear → 没有「始终允许」），
 *       点「允许」后执行层调 {@link #run}。</li>
 * </ul>
 * 只删 {@link TurnArchive}；长期记忆（记忆库、用户画像）和本段会话的账本 / 事实 / 逐字历史都不碰。
 */
public final class ArchiveClear {

    private ArchiveClear() {}

    /** 语音入口的能力签名（FastCaps 注册，SysActionBackend 执行）。 */
    public static final String SIGN = "conv.archive.clear";

    public static final String ROW_TITLE = "清空聊天存档";
    public static final String ROW_SUB = "删掉手机上存的聊天记录；长期记忆不动";
    public static final String ROW_ACTION = "清空";
    public static final String CONFIRM_TITLE = "清空聊天存档？";
    public static final String CONFIRM_BODY = "会删掉手机上存的全部聊天记录（你说过的话和我的完整回答）。长期记忆不动，我记住的事还在。删了就找不回来了。";
    public static final String CONFIRM_OK = "清空";

    /** 弹确认框的人（设置页里是 ConsoleKit.confirm）：用户点了确认才调 onYes；取消什么都不调。 */
    public interface Asker {
        void ask(String title, String body, String ok, Runnable onYes);
    }

    /** 删完回话（设置页里弹 toast）。 */
    public interface Done {
        void done(String text);
    }

    /** 设置按钮：先问，确认了才删。 */
    public static void request(Asker asker, final TurnArchive archive, final Done done) {
        if (asker == null || archive == null) return;
        asker.ask(CONFIRM_TITLE, CONFIRM_BODY, CONFIRM_OK, new Runnable() {
            @Override public void run() {
                String t = ArchiveClear.run(archive);
                if (done != null) done.done(t);
            }
        });
    }

    /** 真删（确认之后才调）。@return 给用户的一句话 */
    public static String run(TurnArchive archive) {
        if (archive == null) return "聊天存档没清成，再试一次";
        int n = archive.wipe();
        return n == 0 ? "聊天存档本来就是空的，长期记忆没动" : "聊天存档清空了，长期记忆没动";
    }
}
