package com.onesay.assistant.v2.conn.port;

import java.util.List;

/**
 * lane-a ↔ B 线（连接器）的邮件只读接口（2026-10-23 lane-a 定义，B 线实现）。
 *
 * <p>lane-a 的「每日播报」「我的事导出」只需要<b>未读重要邮件的标题</b>。B 线的 {@code conn.mail.MailConnector#unreadImportant}
 * 由 {@link LaneBMailReadAdapter} 实现、{@link LaneBMailPorts#ensure} 启动时注册；没注册时用 {@link Ports#STUB_READ}（返回「未接入」）。
 * 返回的标题是外部数据：调用方只拿来展示 / 朗读，不当指令。
 */
public interface MailReadPort {

    /** 是否已接入并配置好账户。 */
    boolean available();

    /** 未读重要邮件的标题（最多 max 条，新的在前）。失败抛异常。 */
    List<String> unreadImportantTitles(int max) throws Exception;

    /** 往来邮件里出现过的人（{名字, 地址}，最多 max 个）——对外发邮件核实收件人用。失败抛异常。 */
    List<String[]> correspondents(int max) throws Exception;
}
