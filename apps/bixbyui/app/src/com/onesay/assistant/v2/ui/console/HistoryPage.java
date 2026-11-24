package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ feat/console-ui（设计稿 ① ☰）：历史对话。
 *
 * <p>App 里<b>没有</b>把以前的对话存下来的地方（会话只在内存里：关掉对话就清，没关的话一会儿没说话也会过期），
 * 所以这里只做「当前这次对话」：列出这次说过的几句，给一个「接着聊」回到对话窗。没有新建任何存储。
 */
final class HistoryPage extends Console.BuiltPage {

    HistoryPage(Console c) {
        super(c, "历史对话");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        List<String[]> l;
        try {
            // ★ fix/chat-vision-1124 第 4 条：打开历史页 = 用户还在用，先续上保持期再读（读快照本身会清掉到期的会话）
            com.onesay.assistant.v2.router.ConversationMemory m = com.onesay.assistant.v2.router.ConversationMemory.getInstance();
            m.touch();
            l = ConsoleHomeModel.historyRows(m.snapshot());
        } catch (Throwable t) {
            l = new ArrayList<String[]>();
        }
        if (l.isEmpty()) {
            k.footer(col, "现在没有进行中的对话。回首页说一句或打字，就开始新的一次。");
        } else {
            LinearLayout g = k.group(col, "这次对话");
            for (String[] r : l) {
                k.valueRow(g, r[0], r[1].length() > 0 ? r[1] : null, "", com.onesay.assistant.v2.kernel.ConsoleHost.TONE_MUTED);
            }
            LinearLayout g2 = k.group(col, null);
            k.actionRow(g2, "接着聊", "回到这次对话接着打字", "打开", new Runnable() {
                @Override public void run() {
                    com.onesay.assistant.v2.ui.FloatingSheetService.openChat(c.act, false);
                }
            });
        }
        k.footer(col, "以前的对话不会存在手机上：关掉对话，或者一会儿没说话，这次对话就清掉了。");
    }
}
