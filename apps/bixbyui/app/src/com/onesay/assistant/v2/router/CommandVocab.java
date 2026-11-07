package com.onesay.assistant.v2.router;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 指令词先验词库 —— 用于 ASR N-best 多候选重排打分。
 *
 * 原理：
 * Vosk 离线小模型在同音字上经常在 top-1 选错（如把“无线”识别成“无限”，“蓝牙”识别成“烂牙”）。
 * 两者走规则表都能命中（因为容错词库把“无限”也视作候选），单看能否匹配分不出高下。
 * 本词表包含所有标准、正规写法的指令词，不包含错别字。
 * 当多候选出现时，命中本表标准词越多的候选，打分越高，从而将正确的候选提升为最佳结果。
 */
public final class CommandVocab {

    /** 核心指令领域高频词（标准规范字） */
    private static final String[] DOMAIN_WORDS = {
            "打开", "关闭", "关掉", "开一下", "调高", "调低", "调大", "调小", "调到", "设置",
            "查一下", "看一下", "多少", "还剩", "状态", "开着吗", "开没开",
            "无线", "wifi", "wlan", "无线网", "无线网络", "网络", "蓝牙", "数据", "流量", "飞行模式", "定位", "位置",
            "热点", "便携热点", "连网", "断网", "开启", "关上", "连接",
            "亮度", "音量", "铃声", "闹钟", "通知", "静音", "勿扰", "震动",
            "截图", "截屏", "息屏", "亮屏", "手电筒", "闪光灯", "省电", "自动亮度", "自动旋转",
            "横屏", "竖屏", "自动对时", "时区", "输入法", "录屏",
            "相机", "相册", "笔记", "浏览器", "时钟", "计算器", "音乐", "地图",
            "搜索", "搜一下", "上网", "天气", "热搜", "新闻",
            "播放", "暂停", "停止", "上一首", "下一首", "换歌",
            "应用", "卸载", "安装", "分享", "信息", "短信", "电话",
            "拨号", "挂断", "接听", "发消息", "打电话",
            "记一下", "做个笔记", "提醒", "计时器", "倒计时", "几点", "时间",
            "可以", "好的", "取消", "确认", "执行", "重来", "返回", "主页", "桌面",
            "存储", "内存", "电池", "电量", "省电模式", "开发者"
    };

    private static final Set<String> SCORE_WORDS;

    static {
        Set<String> s = new LinkedHashSet<String>();
        for (String w : DOMAIN_WORDS) {
            s.add(w.toLowerCase());
        }
        SCORE_WORDS = s;
    }

    /**
     * 计算输入文本中命中的标准指令词数量。
     *
     * @param text 待评估的候选文本
     * @return 命中标准词的频次
     */
    public static int canonicalHits(String text) {
        if (text == null || text.length() == 0) return 0;
        String t = text.replace(" ", "").toLowerCase();
        int count = 0;
        for (String w : SCORE_WORDS) {
            if (w.length() >= 2 && t.contains(w)) {
                count++;
            }
        }
        return count;
    }

    private CommandVocab() {}
}
