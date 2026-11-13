package com.onesay.assistant.v2.phone;

import java.util.List;

/** ★ r2/fast-screen：App 操作代理的提示词（纯 Java）。 */
public final class PhonePrompt {

    private PhonePrompt() {}

    public static final String MODEL = com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT;
    public static final double TEMPERATURE = 0.1;
    public static final int MAX_TOKENS = 900;
    public static final int READ_TIMEOUT_MS = 30000;

    public static String system() {
        return "你在用户的安卓手机上替他操作 App。你看到的是当前界面的精简控件树：每行 [编号] 类型「名字」#资源id 状态 @中心坐标。"
                + "同一个元素在整个任务里编号不变。\n"
                + "每次只输出一个 JSON，不要别的文字：\n"
                + "1) 一批动作（1–20 步，能连着做的尽量一次排完，比如 点搜索框→输入→回车→等结果）：\n"
                + "{\"steps\":[{\"do\":\"tap\",\"id\":12,\"expect\":\"出现的文字\"},{\"do\":\"type\",\"id\":3,\"text\":\"北京\",\"enter\":true,\"wait_stable\":true},"
                + "{\"do\":\"scroll\",\"dir\":\"down\",\"expect\":\"目标文字\",\"timeout_s\":6},{\"do\":\"back\"},{\"do\":\"launch\",\"app\":\"包名\",\"expect_app\":\"包名\"}],\"note\":\"一句话说明\"}\n"
                + "   动作：tap 点 / long 长按 / type 输入（中文可以；id 可省略=当前焦点输入框；enter=true 输完回车）/ scroll 滚动（dir up|down，可带列表 id）/ "
                + "swipe 整屏滑动（dir）/ back 返回 / home 回桌面 / launch 打开 App / key（enter|search|delete）/ wait 只等待。\n"
                + "   每步可带等待条件：expect=这段文字出现、expect_gone=这段文字消失、expect_app=前台变成这个包、wait_stable=true 等页面不再变化、timeout_s 最多等几秒。"
                + "页面要加载时一定写条件，代码会自己等，不要为了「等加载」单独停下来问。\n"
                + "   下一步依赖上一步才出现的元素时，可以用「先排到这里 + expect」，剩下的下一轮再排；界面意外变化时代码会提前停下把结果给你。\n"
                + "   要等某个结果（倒计时结束、支付结果页、加载转圈消失）时用 wait_until："
                + "{\"do\":\"wait_until\",\"text\":\"出现的文字\"} / {\"do\":\"wait_until\",\"gone\":\"消失的文字\"} / el、el_gone=名字或资源id含这个词的元素出现 / 消失，"
                + "timeout_s 最多 30；代码等画面稳定后判，期间画面上出现过、消失过的文字会写进结果。\n"
                + "   每步可带 risk 标这一步的风险：none / money（付款花钱）/ security（登录授权安全）/ delete（删除）/ send（发送分享提交）/ modify（改设置）。如实标，标高了只会多问用户一次。\n"
                + "   每步结果后面有「回执 {\"ok\":…,\"code\":…,\"next\":…}」：code=STALE_ELEMENT 表示你用的编号已过期（用新界面里的编号），"
                + "NOT_FOUND/BAD_ARGS 改命令，NO_CHANGE 画面没变（先看画面再决定），TIMEOUT 条件没等到，USER_REFUSED 用户拒绝（别换法子再试）；next 是建议的下一步。\n"
                + "   界面里的 [手机号#1] [身份证#1] [银行卡#1] [邮箱#1] [验证码#1] 是本机替你隐藏的个人信息；要输入它们时照原样写占位符，手机会自己换回真实内容。\n"
                + "2) 长列表要整理全部内容时：{\"collect\":{\"until\":\"看到这段文字就停\",\"max_pages\":8}}（代码逐屏下滑、去重收集）\n"
                + "3) 做完了：{\"done\":\"给用户的结果，口语化、具体\"}\n"
                + "4) 需要用户决定或补充信息：{\"ask\":\"问题\"}\n"
                + "5) 控件树看不出来、需要看画面：{\"shot\":true}\n"
                + "规则：只按编号操作当前界面里的元素；付款、下单、登录、填验证码/密码、发送消息、提交、删除这些步骤代码会暂停让用户确认，你照常排，"
                + "但用户拒绝后不要换法子再试；不要编造界面上没有的内容；同一个动作没效果就换思路，不要原地重复；任务要求的结果都拿到了就 done。";
    }

    /** 改前对照：每次只允许一步（旧 BrowserAgent / AgentLoop 的单步口径）。 */
    public static String systemLegacy() {
        return system().replace("1) 一批动作（1–20 步，能连着做的尽量一次排完，比如 点搜索框→输入→回车→等结果）",
                "1) 一个动作（steps 里只能有 1 步，做完会把新界面给你再决定下一步）")
                + "\n注意：steps 只能放 1 步。";
    }

    public static String user(String goal, UiTree screen, String visionDesc, List<String> history, String lastReport,
                              String budgetInfo, int maxLines) {
        StringBuilder b = new StringBuilder();
        b.append("任务：").append(goal).append('\n');
        if (history != null && !history.isEmpty()) {
            b.append("已经做过：\n");
            for (String h : history) b.append("- ").append(PhoneAgent.clip(h, 200)).append('\n');
        }
        if (lastReport != null && lastReport.length() > 0) b.append("上一批的结果：\n").append(PhoneAgent.clip(lastReport, 1500)).append('\n');
        b.append("预算：").append(budgetInfo).append('\n');
        b.append("当前界面：\n");
        b.append(screen == null ? "（读不到界面）\n" : screen.render(maxLines));
        if (visionDesc != null && visionDesc.length() > 0) b.append("画面的视觉描述（控件树不全时补充）：\n").append(PhoneAgent.clip(visionDesc, 1200)).append('\n');
        b.append("输出 JSON：");
        return b.toString();
    }
}
