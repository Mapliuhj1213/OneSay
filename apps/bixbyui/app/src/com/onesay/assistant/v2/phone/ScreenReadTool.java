package com.onesay.assistant.v2.phone;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

/**
 * [fix-1123-dexec B29] 智能体循环里的「读当前屏幕」工具（不截图，读文字和按钮）。
 *
 * <p>真机（魅族 12.5）：打开显示设置后用户问上面写了什么，模型说「看不到屏幕，要截屏」——Shizuku 读屏明明可用，
 * 但循环的候选工具里没有读屏，只有识屏（截图），没配识屏 Key 就只能放弃。现在候选里常驻这个只读工具：
 * 读屏助手 / 无障碍谁能用用谁，读不到才说读不到。</p>
 */
public final class ScreenReadTool {

    private ScreenReadTool() {}

    public static final String SIGN = "screen.read_now";
    private static volatile Capability sCap;

    public static Capability cap() {
        if (sCap != null) return sCap;
        sCap = new Capability.Builder(SIGN,
                "读现在屏幕上的文字和按钮（不截图、不点任何东西）：刚打开某个 App / 设置页、用户问「上面写了什么 / 现在是什么页面 / 开关开没开」时先用它，"
                        + "读不到再考虑看图")
                .domain("phone").category("screen")
                .verbs("看", "读").entities("screen")
                .sideEffect(SideEffect.PRIVATE_DATA_READ).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema().addInteger("max_lines", false, 10, 200, "80"))
                .reply("已读取屏幕")
                .build();
        return sCap;
    }

    public static boolean isMine(String name) {
        return name != null && (name.equals(SIGN) || name.equals("screen__read_now") || name.equals("screen_read_now"));
    }

    /** 给模型的文字（纯函数，JVM 可测）。 */
    public static String format(UiTree u, int maxLines) {
        if (u == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("前台应用：").append(u.pkg == null || u.pkg.length() == 0 ? "（没读到）" : u.pkg);
        if (u.activity != null && u.activity.length() > 0) sb.append("  页面：").append(u.activity);
        sb.append("\n读屏质量：").append(u.quality()).append("\n");
        sb.append(u.render(maxLines <= 0 ? 80 : maxLines));
        return sb.toString();
    }

    public static ExecutionResult run(Context ctx, Capability c, org.json.JSONObject args) {
        int max = args == null ? 80 : Math.max(10, Math.min(200, args.optInt("max_lines", 80)));
        try {
            FastScreenDriver drv = new FastScreenDriver(ctx);
            com.onesay.assistant.v2.a11y.A11yState.Choice be = drv.chooseBackend();
            if (be.backend == com.onesay.assistant.v2.a11y.A11yState.Backend.NONE) {
                return ExecutionResult.failed(ErrorCode.PERMISSION_DENIED, "现在读不了屏幕：" + be.reason, "读屏后端不可用", "如实告诉用户怎么打开读屏，别假装看到了", c, args);
            }
            UiTree u = drv.observe(new UiTree.Ids());
            String text = format(u, max);
            if (text == null || UiTree.Q_UNUSABLE.equals(u.quality())) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "这一页读不出文字", "读屏为空或不可用", "可以试试看图；看图也不行就如实说明", c, args);
            }
            return ExecutionResult.success(c, args, text, ExecutionResult.Verification.VERIFIED, "读屏 " + u.els.size() + " 个元素");
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "读屏出错了", "读屏异常: " + t.getClass().getSimpleName(), c, args);
        }
    }
}
