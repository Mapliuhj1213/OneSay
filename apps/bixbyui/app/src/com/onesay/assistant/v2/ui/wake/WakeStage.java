package com.onesay.assistant.v2.ui.wake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [R13 唤醒界面 v2] 唤醒界面的显式状态机（纯 Java，JVM 可测）—— 替代 FloatingSheetView.updateState 里的
 * {@code badge.contains("聆听"/"解析"/…)} 字符串分支（代码里记录过两次「副标题与徽标自相矛盾」的真机 bug）。
 *
 * <p>输入是事件，输出是 {@link Transition}（从哪到哪 + 要执行的 {@link Effect} 列表），宿主（FloatingSheetView /
 * FloatingSheetService / WakeGlowView）只负责执行效果，不再自己猜状态。旧的 updateState(badge…) 保留为适配层：
 * 它先经 {@link #classifyBadge} 把旧徽标映射成事件，再走同一张转移表 —— 映射只在这一处，有单测。
 *
 * <p>状态与转移见方案 §4.1（v2 圆润化修订后的视觉见 v2.2–v2.6）。
 */
public final class WakeStage {

    public enum Stage {
        /** 没有唤醒界面。 */
        IDLE,
        /** 光在听 + 底部小卡（转写 + 光点）。 */
        LISTENING,
        /** 说完了 / 正在解析 / 检索 / 生成：光吸进卡片。 */
        THINKING,
        /** 流式作答中（卡片随内容长高）。 */
        ANSWERING,
        /** 结果已上屏（芯片弹入）。 */
        RESULT,
        /** 复合任务在跑（卡片上，稍后收进岛）。 */
        TASK,
        /** 红 / 黄确认卡。 */
        CONFIRM,
        /** 没听清（2.5 s 后收起，M-2 规则不变）。 */
        ERROR,
        /** 打字输入。 */
        TEXT,
        /** 卡片正在形变飞进岛（舞台在画）。 */
        HANDOFF,
        /** 任务住在岛上（卡片窗口已摘下）。 */
        ISLAND,
        /** 卡片沉下退场中。 */
        DISMISSING
    }

    public enum Event {
        WAKE, LISTEN_UPDATE, THINK, ANSWER_STREAM, RESULT, TASK, CONFIRM, ERROR, TEXT_MODE,
        COLLAPSE_TO_ISLAND, LANDED_IN_ISLAND, RETURN_FROM_ISLAND, DISMISS, DISMISSED, RESET,
        /**
         * ★ LC-5：前台来了一条新请求（文字注入 / 面板注入 / ⌨ 输入 / 语音指令，都汇到 onSubmitCommand）。
         * 卡片在屏上时与 THINK 完全相同；卡片在岛上（ISLAND / HANDOFF）时新建一张卡片接住这条请求，
         * 岛上原来的任务不受影响（岛自己画）。以前这里只有 THINK，被 ISLAND 分支当成「岛上任务的步骤」吞掉 → 卡在 ISLAND。
         */
        NEW_REQUEST
    }

    public enum Effect {
        GLOW_IGNITE, GLOW_INHALE, GLOW_OFF, GLOW_DIM_ERROR,
        CARD_IN, CARD_RESIZE, CONTENT_IN, CHIPS_IN, CARD_DISMISS, CARD_PRESS,
        MORPH_TO_ISLAND, UNMORPH_FROM_ISLAND, RELEASE_STAGE,
        SHOW_ORB, HIDE_ORB, SHOW_KEYBOARD,
        HAPTIC_IGNITE, HAPTIC_RESULT, HAPTIC_LAND, HAPTIC_ERROR
    }

    public static final class Transition {
        public final Stage from;
        public final Stage to;
        public final Event event;
        public final List<Effect> effects;

        Transition(Stage from, Stage to, Event event, List<Effect> effects) {
            this.from = from;
            this.to = to;
            this.event = event;
            this.effects = effects;
        }

        public boolean changed() {
            return from != to;
        }

        public boolean has(Effect e) {
            return effects.contains(e);
        }

        @Override
        public String toString() {
            return from + " -" + event + "-> " + to + " " + effects;
        }
    }

    private Stage mStage = Stage.IDLE;

    public synchronized Stage stage() {
        return mStage;
    }

    /** 卡片窗口此刻应在屏幕上（形变 / 岛 / 空闲时不在）。 */
    public static boolean cardOnScreen(Stage s) {
        return s != Stage.IDLE && s != Stage.HANDOFF && s != Stage.ISLAND;
    }

    /**
     * ★ LC-5：新请求到达时卡片是否不在屏上、需要宿主先把卡片窗挂回来并清掉旧内容（岛上任务留在岛上）。
     * IDLE 不算：IDLE 下 THINK 本来就会 CARD_IN（面板注入的老路径，内容由 showSheet 新挂载）。
     */
    public static boolean newRequestLeavesIsland(Stage s) {
        return s == Stage.ISLAND || s == Stage.HANDOFF;
    }

    public synchronized Transition on(Event e) {
        Stage from = mStage;
        List<Effect> fx = new ArrayList<Effect>();
        Stage to = next(from, e, fx);
        mStage = to;
        return new Transition(from, to, e, Collections.unmodifiableList(fx));
    }

    /** 转移表（纯函数）。fx 收集效果。 */
    static Stage next(Stage s, Event e, List<Effect> fx) {
        // 新唤醒、硬复位在任何状态都生效
        if (e == Event.WAKE) {
            if (s == Stage.LISTENING) return s;
            fx.add(Effect.GLOW_IGNITE);
            fx.add(Effect.SHOW_ORB);
            fx.add(Effect.HAPTIC_IGNITE);
            if (s == Stage.IDLE || s == Stage.DISMISSING || s == Stage.ISLAND || s == Stage.HANDOFF) {
                if (s == Stage.HANDOFF) fx.add(Effect.RELEASE_STAGE);
                fx.add(Effect.CARD_IN);
            } else {
                fx.add(Effect.CONTENT_IN);   // 卡片已在屏上（「再说一句」）：只换内容
            }
            return Stage.LISTENING;
        }
        if (e == Event.NEW_REQUEST) {
            if (s == Stage.HANDOFF) fx.add(Effect.RELEASE_STAGE);
            if (!cardOnScreen(s) || s == Stage.DISMISSING) fx.add(Effect.CARD_IN);
            return contentStage(cardOnScreen(s) && s != Stage.DISMISSING ? s : Stage.IDLE, Event.THINK, fx);
        }
        if (e == Event.RESET) {
            if (s == Stage.HANDOFF) fx.add(Effect.RELEASE_STAGE);
            if (s != Stage.IDLE) fx.add(Effect.GLOW_OFF);
            return Stage.IDLE;
        }
        switch (s) {
            case IDLE:
                // 没有唤醒就有内容（面板注入 / 剪贴板芯片 / 圈选结果 / 冷启动补发）：卡片直接以对应形态出现
                if (e == Event.DISMISS || e == Event.DISMISSED || e == Event.LANDED_IN_ISLAND) return s;
                if (e == Event.RETURN_FROM_ISLAND) {
                    fx.add(Effect.CARD_IN);
                    return Stage.RESULT;
                }
                fx.add(Effect.CARD_IN);
                return contentStage(Stage.IDLE, e, fx);
            case HANDOFF:
                if (e == Event.LANDED_IN_ISLAND) {
                    fx.add(Effect.HAPTIC_LAND);
                    return Stage.ISLAND;
                }
                if (e == Event.RETURN_FROM_ISLAND) {
                    fx.add(Effect.RELEASE_STAGE);
                    fx.add(Effect.CARD_IN);
                    return Stage.RESULT;
                }
                if (e == Event.DISMISS || e == Event.DISMISSED) {
                    fx.add(Effect.RELEASE_STAGE);
                    return Stage.IDLE;
                }
                return s;   // 形变途中的内容更新（步骤文字）只记不画：卡片已不在屏上
            case ISLAND:
                if (e == Event.RETURN_FROM_ISLAND) {
                    fx.add(Effect.UNMORPH_FROM_ISLAND);
                    return Stage.RESULT;
                }
                if (e == Event.DISMISS || e == Event.DISMISSED) return Stage.IDLE;
                return s;   // 岛上任务的步骤 / 结果：岛自己画，卡片不动
            case DISMISSING:
                if (e == Event.DISMISSED) return Stage.IDLE;
                if (e == Event.DISMISS) return s;
                if (e == Event.RETURN_FROM_ISLAND) {
                    fx.add(Effect.CARD_IN);
                    return Stage.RESULT;
                }
                // 退场途中来了新内容（迟到的结果已被服务层丢弃；这里只可能是新一轮输入）：反向接回
                fx.add(Effect.CARD_IN);
                return contentStage(Stage.IDLE, e, fx);
            default:
                break;
        }
        // 卡片在屏上的状态
        if (e == Event.DISMISS) {
            fx.add(Effect.CARD_DISMISS);
            fx.add(Effect.GLOW_OFF);
            fx.add(Effect.HIDE_ORB);
            return Stage.DISMISSING;
        }
        if (e == Event.DISMISSED) {
            fx.add(Effect.GLOW_OFF);
            return Stage.IDLE;
        }
        if (e == Event.COLLAPSE_TO_ISLAND) {
            fx.add(Effect.GLOW_OFF);
            fx.add(Effect.HIDE_ORB);
            fx.add(Effect.MORPH_TO_ISLAND);
            return Stage.HANDOFF;
        }
        if (e == Event.LANDED_IN_ISLAND || e == Event.RETURN_FROM_ISLAND) return s;
        return contentStage(s, e, fx);
    }

    /** 内容类事件 → 卡片形态。 */
    private static Stage contentStage(Stage s, Event e, List<Effect> fx) {
        switch (e) {
            case LISTEN_UPDATE:
                if (s != Stage.LISTENING) {
                    fx.add(Effect.GLOW_IGNITE);
                    fx.add(Effect.SHOW_ORB);
                }
                fx.add(Effect.CONTENT_IN);
                return Stage.LISTENING;
            case THINK:
                if (s == Stage.LISTENING) fx.add(Effect.GLOW_INHALE);
                if (s == Stage.LISTENING) fx.add(Effect.HIDE_ORB);
                if (s != Stage.THINKING) fx.add(Effect.CONTENT_IN);
                return Stage.THINKING;
            case ANSWER_STREAM:
                if (s == Stage.LISTENING) {
                    fx.add(Effect.GLOW_INHALE);
                    fx.add(Effect.HIDE_ORB);
                }
                fx.add(Effect.CARD_RESIZE);
                if (s != Stage.ANSWERING) fx.add(Effect.CONTENT_IN);
                return Stage.ANSWERING;
            case RESULT:
                if (s == Stage.LISTENING) {
                    fx.add(Effect.GLOW_INHALE);
                    fx.add(Effect.HIDE_ORB);
                }
                fx.add(Effect.CARD_RESIZE);
                fx.add(Effect.CONTENT_IN);
                fx.add(Effect.CHIPS_IN);
                if (s != Stage.RESULT) fx.add(Effect.HAPTIC_RESULT);
                return Stage.RESULT;
            case TASK:
                if (s == Stage.LISTENING) {
                    fx.add(Effect.GLOW_INHALE);
                    fx.add(Effect.HIDE_ORB);
                }
                if (s != Stage.TASK) fx.add(Effect.CONTENT_IN);
                fx.add(Effect.CARD_RESIZE);
                return Stage.TASK;
            case CONFIRM:
                if (s == Stage.LISTENING) {
                    fx.add(Effect.GLOW_INHALE);
                    fx.add(Effect.HIDE_ORB);
                }
                fx.add(Effect.CARD_RESIZE);
                fx.add(Effect.CONTENT_IN);
                fx.add(Effect.CARD_PRESS);
                return Stage.CONFIRM;
            case ERROR:
                fx.add(Effect.GLOW_DIM_ERROR);
                fx.add(Effect.HIDE_ORB);
                fx.add(Effect.CONTENT_IN);
                if (s != Stage.ERROR) fx.add(Effect.HAPTIC_ERROR);
                return Stage.ERROR;
            case TEXT_MODE:
                fx.add(Effect.GLOW_OFF);
                fx.add(Effect.HIDE_ORB);
                fx.add(Effect.SHOW_KEYBOARD);
                fx.add(Effect.CARD_RESIZE);
                return Stage.TEXT;
            default:
                return s;
        }
    }

    // =====================================================================
    // 旧徽标适配层（唯一一处字符串 → 事件映射）
    // =====================================================================

    /**
     * 把旧 {@code updateState(badge, …)} 的徽标文字映射成事件。null = 不是状态切换（只改正文）。
     * 顺序有讲究：先认终态（「执行完毕」含「执行」，旧实现就是在这里误吞的），再认进行态。
     */
    /**
     * ★ R14：卡片右下角 ⌨ / ◎ 图标行的可见性（返回 View.VISIBLE / INVISIBLE / GONE）。
     * 聆听 / 结果 / 出错 = 可点；思考 / 作答 = INVISIBLE（占位、不可点 —— 聆听→思考→结果 卡片高度不变，少两次 WRAP_CONTENT relayout）；
     * 任务 / 确认 / 打字 / 形变 / 岛 / 空闲 = GONE。
     */
    public static int iconRowVisibility(Stage st) {
        if (st == null) return android.view.View.GONE;
        switch (st) {
            case LISTENING:
            case RESULT:
            case ERROR:
                return android.view.View.VISIBLE;
            case THINKING:
            case ANSWERING:
                return android.view.View.INVISIBLE;
            default:
                return android.view.View.GONE;
        }
    }

    public static Event classifyBadge(String badge) {
        if (badge == null) return null;
        String b = badge.trim();
        if (b.length() == 0) return null;
        if (has(b, "没听清", "启动超时", "没有网络", "等待超时")) return Event.ERROR;
        if (WakeCopy.asrKind(b) != null) return Event.ERROR;   // r2/p0 P0-2：识别失败分型（「语音识别未配置」等，含「识别」二字，必须排在 THINK 前）
        if (has(b, "聆听", "在听", "\uD83C\uDFA4")) return Event.LISTEN_UPDATE;
        if (has(b, "键盘")) return Event.TEXT_MODE;
        if (has(b, "安全拦截", "操作待确认")) return Event.CONFIRM;
        if (has(b, "完毕", "执行成功", "执行异常", "执行未达成", "部分完成", "已启动应用", "未能完成", "已中止",
                "智能问答", "识屏完成", "识屏失败", "已拒绝", "已允许", "已被拦截", "未能识别", "异常", "确认超时",
                "确认已过期", "闹钟已", "未开启", "开关未生效")) return Event.RESULT;
        if (has(b, "正在处理")) return Event.TASK;
        if (has(b, "解析", "识别", "识屏", "作答", "检索", "生成", "正在执行", "思考")) return Event.THINK;
        return Event.RESULT;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    // =====================================================================
    // 每个状态的静态视觉参数（纯函数）
    // =====================================================================

    /** 底部局部压暗（D3′：聆听 30%，作答 50%；顶部 0）。 */
    public static float dimLevel(Stage s) {
        switch (s) {
            case LISTENING: return 0.30f;
            case THINKING:
            case ANSWERING:
            case RESULT:
            case TASK:
            case CONFIRM:
            case ERROR:
            case TEXT: return 0.50f;
            default: return 0f;
        }
    }

    /** 压暗渐变从屏高的哪个比例开始渐入（聆听 45%、作答 25%）。 */
    public static float dimStartFrac(Stage s) {
        return s == Stage.LISTENING ? 0.45f : 0.25f;
    }

    /** 边缘 bloom 是否该亮（只代表「在听」；吸进卡片后熄灭）。 */
    public static boolean glowOn(Stage s) {
        return s == Stage.LISTENING;
    }

    /** 卡片上的柔光小球（聆听时）。 */
    public static boolean orbOn(Stage s) {
        return s == Stage.LISTENING;
    }

    /** 关闭键（聆听时不放：点外部 / 返回键即可收起）。 */
    public static boolean closeOn(Stage s) {
        return s != Stage.LISTENING && cardOnScreen(s) && s != Stage.DISMISSING;
    }

    /** 卡片内发光强度（v2.4：聆听 22%、作答 30%，2 s 后回落 12%；错误态更暗）。 */
    public static float innerGlow(Stage s) {
        switch (s) {
            case LISTENING: return 0.22f;
            case THINKING:
            case ANSWERING: return 0.30f;
            case RESULT:
            case TASK:
            case CONFIRM:
            case TEXT: return 0.12f;
            case ERROR: return 0.06f;
            default: return 0f;
        }
    }
}
