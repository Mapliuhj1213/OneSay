package com.onesay.assistant.v2.ui.wake;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ LC-5：岛上任务的结论该不该写进卡片（纯 Java，JVM 可测）。
 *
 * <p>前台每来一条新请求（{@code FloatingSheetService.onSubmitCommand}），请求号 +1。岛上任务（跑腿）派发时记下当时的请求号：
 * <ul>
 *   <li>收工时请求号没变 → 卡片还属于它，结论照旧写进卡片（「查看」= 流回卡片看全文）。</li>
 *   <li>请求号变了 → 卡片已被新请求接管（正在显示新回答），结论<b>只放岛上</b>，不覆盖新回答；
 *       用户点岛上「查看」时再把结论画回卡片。</li>
 * </ul>
 */
public final class TaskResultRouting {

    private TaskResultRouting() {}

    /** 收工时：卡片仍属于这个任务（派发后没有新请求）。 */
    public static boolean writeToCard(int dispatchSeq, int currentSeq) {
        return dispatchSeq == currentSeq;
    }

    /** 点「查看」时：派发后来过新请求（卡片内容已不是这个任务的结论）→ 先重画结论。 */
    public static boolean restoreOnView(int dispatchSeq, int currentSeq) {
        return dispatchSeq != currentSeq;
    }

    /** 一条岛上任务的结论。 */
    public static final class Result {
        public final String question;
        public final String answer;
        public final int seq;

        public Result(String question, String answer, int seq) {
            this.question = question;
            this.answer = answer;
            this.seq = seq;
        }
    }

    /** 最近几条岛上任务结论（按 task-result- id，最多 {@link #CAP} 条，最旧的先丢）。线程安全。 */
    public static final class Store {
        public static final int CAP = 8;
        private final LinkedHashMap<String, Result> mMap = new LinkedHashMap<String, Result>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
                return size() > CAP;
            }
        };

        public synchronized void put(String id, Result e) {
            if (id == null || e == null) return;
            mMap.remove(id);
            mMap.put(id, e);
        }

        public synchronized Result get(String id) {
            return id == null ? null : mMap.get(id);
        }

        public synchronized int size() {
            return mMap.size();
        }
    }
}
