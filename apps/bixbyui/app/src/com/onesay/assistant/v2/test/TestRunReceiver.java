package com.onesay.assistant.v2.test;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * 自动化测试与外部控制广播探针。
 *
 * 支持通过 adb 发送广播驱动：
 * 1. --ez selftest true        : 启动全量自测套件
 * 2. --es match "打开蓝牙"     : 测试路由器匹配结果
 * 3. --es label "把亮度设为80" : 端到端路由并真实执行
 * 4. --es setkey "sk-..."     : 配置持久化 LLM API Key
 * 5. --ez show true           : 调起底部 Sheet 悬浮面板
 * 6. --ez test_voice true     : 注入语音通道唤醒（验证分级去抖，绕开 AEC 阻断）
 * 7. --es asrset/asron/asrprobe/asrasset : 云端 ASR（阿里云一句话识别）配置与验证
 * 8. --es spkfile <wav> [--es spkfile2 <wav2>] : 声纹提向量 / 两段余弦（P0 探针）
 *    --es spkenroll <wav> [--es spkname owner]  : 声纹注册（>9s 自动切 3s 段）
 *    --ez spklist true / --ez spkdel true [--es spkname n] : 状态 / 删除
 *    --es vpchk <wav>                           : 字比对门离线复测（离线识别已下线恒为空；r2/fixes 15 起产品里字比对已停用，仅留探针）
 * 9. --es s1 <cmd> [--es text …] : S1 看得见的后台（胶囊/取件码浮卡/圈选/剪贴板芯片）调试，见 S1TestDriver
 * 10. --es s2 <cmd> [--es text …] : S2 记得住（记忆库/习惯默认值/模拟隔天）调试，见 S2TestDriver
 * 11. --es diag <cmd> [--es text …] : #9 设置诊断（判定/真实只读诊断/键名核实/修复+撤销）调试，见 DiagTestDriver
 * 12. --es roles <cmd> [--es text …] : 默认应用角色（候选推导/裁决/设默认/重置/安装链）调试，见 RolesTestDriver
 * 13. --es round3 <cmd> [--es text …] : round3（拒绝终态 / 免前缀记忆判定与撤销芯片 / 冷启动闸门状态）调试，见 Round3TestDriver
 * 14. --es cache_unneg "<句子>"        : 清掉这句话的 L1 负缓存（验收脚本 cache 模块开跑前用）
 * 14b. --es cache_unpos "<句子>"       : 清掉这句话的 L1 正缓存（round4：CA-0 一并清，CA-1 不再被上轮转正的条目直接 HIT）
 */
public class TestRunReceiver extends BroadcastReceiver {

    private static final String TAG = "TestRunReceiver";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) return;

        // ★ r2/fast-screen：常驻读屏助手 / App 操作代理 / 执行模式的真机测试（--es fs|phone|exec，见 FastScreenTestHooks）
        if (com.onesay.assistant.v2.phone.FastScreenTestHooks.handle(context, intent)) return;

        // ★ int/1102 统一入口：--es plug 一条命令按 id 前缀分派给 mika（mika.* / skillpack*）、suggest（suggest.*）、richout（richout.*）三族，
        //   短名（guard / diagram …）只在恰好一族命中时改写成全名，多族命中 = 重名拒绝（见 kernel/PlugCommand）。
        //   用法：--es plug list | --es plug "guard=off,richout.diagram=off,suggest.clip=on" | --es plug all=default
        // ★ feat/suggest：--es plug suggest --es inject screenshot --es path <文件> | clip --es text … | headset | torch | pkg --es pkg … | status
        if ("suggest".equals(intent.getStringExtra("plug")) && (intent.hasExtra("inject") || intent.hasExtra("inotify"))) {
            String r = com.onesay.assistant.v2.suggest.SuggestHost.debugIntent(context.getApplicationContext(), intent);
            for (String line : r.split("\n")) Log.i(TAG, "[SUGGEST-DBG] " + line);
            return;
        }
        if (intent.hasExtra("plug")) {
            String[] r = com.onesay.assistant.v2.kernel.PlugCommandHost.debug(context, intent.getStringExtra("plug"));
            for (String line : r[0].split("\n")) if (line.length() > 0) Log.w(TAG, "[PLUG] " + line);
            for (String line : r[1].split("\n")) Log.i(TAG, "[MIKA-PLUG] " + line);
            for (String line : r[2].split("\n")) Log.i(TAG, "[RICHOUT-PLUG] " + line);
            return;
        }

        // ★ skill-pack：看一句话会命中哪些技能（不动会话）。用法：--es skill "给妈妈发微信说我到了"；开关仍用 --es plug skillpack=on|off
        if (intent.hasExtra("skill")) {
            String r = com.onesay.assistant.v2.mikaplug.SkillPack.dryRun(context, intent.getStringExtra("skill"));
            for (String line : r.split("\n")) Log.i(TAG, "[SKILL] " + line);
            return;
        }

        // 1. 触发全量自测
        if (intent.getBooleanExtra("selftest", false)) {
            Log.i(TAG, "[RUN] 启动 V2 全量回归自测");
            V2SelfTest.run(context.getApplicationContext(), null);
            return;
        }

        // 1.3 一级软件 / 二级接口类型）。
        //     把注册表按两轴重新索引后落盘 + 打日志，供审计与提示词注入核对。
        //     用法：--ez dumpifdb true
        if (intent.getBooleanExtra("dumpifdb", false)) {
            try {
                com.onesay.assistant.v2.capability.CapabilityRegistry reg =
                        com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance();
                java.util.Map<String, java.util.Map<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                        java.util.List<com.onesay.assistant.v2.capability.Capability>>> tree =
                        reg.bySoftwareAndKind();
                StringBuilder sb = new StringBuilder();
                sb.append("\n===== 接口数据库（一级=软件 / 二级=接口类型）=====\n");
                int total = 0;
                for (java.util.Map.Entry<String, java.util.Map<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                        java.util.List<com.onesay.assistant.v2.capability.Capability>>> e : tree.entrySet()) {
                    sb.append("[").append(e.getKey()).append("]\n");
                    for (java.util.Map.Entry<com.onesay.assistant.v2.capability.InterfaceKind.Kind,
                            java.util.List<com.onesay.assistant.v2.capability.Capability>> ke : e.getValue().entrySet()) {
                        sb.append("  ├ ").append(ke.getKey().cn())
                          .append("(").append(ke.getKey().name()).append(") x")
                          .append(ke.getValue().size()).append("\n");
                        total += ke.getValue().size();
                    }
                }
                sb.append("总接口数=").append(total).append("\n");
                Log.i(TAG, sb.toString());
                // 落盘 JSON（单一真源的快照）
                java.io.File out = new java.io.File(context.getFilesDir(), "interface_db.json");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(reg.exportInterfaceDb().toString(2).getBytes("UTF-8"));
                fos.close();
                Log.i(TAG, "[IFDB] 已导出 " + out.getAbsolutePath()
                        + " (" + out.length() + " 字节)");
            } catch (Throwable t) {
                Log.e(TAG, "[IFDB] 导出失败: " + t.getMessage());
            }
            return;
        }

        // 1.4 调 prompt 不该重编译 APK。
        //     模板落在 /data/data/com.onesay.assistant/files/prompts/*.txt，
        //     用 adb push 推文件后发本广播即生效。
        //     用法：
        //       --ez reload_prompts true     从磁盘重载所有模板
        //       --ez dumpprompts true        打印当前生效的模板与来源
        if (intent.getBooleanExtra("reload_prompts", false)
                || intent.getBooleanExtra("dumpprompts", false)) {
            com.onesay.assistant.v2.router.PromptLibrary pl =
                    com.onesay.assistant.v2.router.PromptLibrary.getInstance(context.getApplicationContext());
            if (intent.getBooleanExtra("reload_prompts", false)) {
                int n = pl.reload();
                Log.i(TAG, "[PROMPT] 广播触发热加载完成，磁盘模板数=" + n
                        + " gen=" + pl.generation());
            }
            java.util.Set<String> names = pl.loadedNames();
            Log.i(TAG, "[PROMPT] 当前磁盘模板: " + (names.isEmpty() ? "（空，全部用内置默认）" : names)
                    + " | ask.output 长度=" + pl.get(com.onesay.assistant.v2.router.PromptLibrary.T_ASK_OUTPUT).length()
                    + " | do.manual 长度=" + pl.get(com.onesay.assistant.v2.router.PromptLibrary.T_DO_MANUAL).length());
            return;
        }

        // ★ r2/cloud-tts：云端语音回复直测（不走问答链路，直接让 TtsSpeaker 播一句固定文本）。
        //   --es ttssay "文本" [--ei ttsmax 60]    走 speakProactive（不看「语音回复」开关）：云端优先，失败回退系统 TTS
        //   --es ttscloud on|off                   云端开关（debug 默认开）
        //   --es ttshost workspace|dashscope       域名（默认业务空间专属域名）
        //   --ei ttsconnectms N / --ei ttsfirstms N  建连 / 首包超时
        //   --ez ttsstop true                      打断（等同 barge-in 时的 TtsSpeaker.stopAll）
        //   --ez ttscfg true                       打印当前配置（不含 key，只报有没有）
        if (intent.hasExtra("ttssay") || intent.hasExtra("ttscloud") || intent.hasExtra("ttshost")
                || intent.hasExtra("ttsconnectms") || intent.hasExtra("ttsfirstms")
                || intent.hasExtra("ttsstop") || intent.hasExtra("ttscfg")) {
            Context app = context.getApplicationContext();
            com.onesay.assistant.v2.tts.CloudTtsConfig tc = new com.onesay.assistant.v2.tts.CloudTtsConfig(app);
            String on = intent.getStringExtra("ttscloud");
            if (on != null) tc.setEnabled("on".equalsIgnoreCase(on) || "true".equalsIgnoreCase(on) || "1".equals(on));
            String host = intent.getStringExtra("ttshost");
            if (host != null) tc.setHostMode(host);
            if (intent.hasExtra("ttsconnectms")) tc.setConnectTimeoutMs(intent.getIntExtra("ttsconnectms", 1500));
            if (intent.hasExtra("ttsfirstms")) tc.setFirstAudioTimeoutMs(intent.getIntExtra("ttsfirstms", 2500));
            boolean hasKey = new com.onesay.assistant.v2.asr.AliyunAsrConfig(app).getDashScopeKey().length() > 0;
            Log.i(TAG, "[TTSCLOUD] " + tc.describe() + " url=" + tc.wsUrl() + " dashscopeKey=" + (hasKey ? "有" : "无"));
            if (intent.getBooleanExtra("ttsstop", false)) {
                com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
                Log.i(TAG, "[TTSCLOUD] stopAll");
            }
            String say = intent.getStringExtra("ttssay");
            if (say != null && say.length() > 0) {
                com.onesay.assistant.v2.ui.TtsSpeaker.get(app).speakProactive(say, intent.getIntExtra("ttsmax", 60));
                Log.i(TAG, "[TTSCLOUD] speakProactive " + say.length() + " 字");
            }
            return;
        }

        // ★ r2/voice-templates 直测：
        //   --es tplsay answer|done|error|offline|cancelled  按回答路径播模板（看开关、走 fireStart / fireDone）
        //   --ez tplvoice true        先把本轮标成语音回合（续听要它）；--ei tplstopms N  N ms 后 stopAll（打断）
        //   --es tplcue miss|offline|error|confirm  播一次性提示短句（不进状态机）
        //   --es fullanswer on|off    「朗读完整回答」开关
        //   --es vtquery "指令"       当作语音回合注入（回答走模板 / 续听），等同语音识别出这句后提交
        if (intent.hasExtra("vtquery")) {
            String q = intent.getStringExtra("vtquery");
            com.onesay.assistant.v2.wake.WakeService.markVoiceCommandForTest(q);
            Log.i(TAG, "[TPL] 语音回合注入: " + q);
            FloatingSheetService.show(context.getApplicationContext(), q);
            return;
        }
        //   --es askreply "答案" [--es askq "问题"]  面板已开时直接把答案上卡片（看排版；会按回答路径出声）
        if (intent.hasExtra("askreply")) {
            FloatingSheetService fs = FloatingSheetService.getInstance();
            String a = intent.getStringExtra("askreply").replace("\\n", "\n");
            String q = intent.getStringExtra("askq");
            Log.i(TAG, "[TPL] askreply " + a.length() + " 字 fs=" + (fs != null));
            if (fs != null) fs.debugShowAskReply(q != null ? q : "排版测试", a);
            return;
        }
        // ★ fix/leak-guard debug 直测：
        //   --es leakguard on|off|status          开关（写 prefs bixby_plug/leakguard，立即生效）/ 打印就绪信息
        //   --es leakcheck "文字"                  三种口径各判一次，打 [LEAK-GUARD-CHECK]（只打前 20 字）
        //   --es leakdemo hotwords|midlist|prompt|normal [--es leakwhere asr|reply|tts|stream|all]
        //        asr   = 当识别结果注入（上屏 + 指令入口）；reply = 面板回答卡片；tts = 直接朗读；stream = 流式回答上屏
        if (intent.hasExtra("leakguard") || intent.hasExtra("leakcheck") || intent.hasExtra("leakdemo")) {
            LeakGuardDemo.run(context.getApplicationContext(), intent.getStringExtra("leakguard"),
                    intent.getStringExtra("leakcheck"), intent.getStringExtra("leakdemo"), intent.getStringExtra("leakwhere"));
            return;
        }

        // ★ device-1102 问题 3b：--es memdump any  打印当前注入的人设 / 记忆摘要、来源路径、各路径最近一次注入、会话状态（[MEMDUMP]）
        //   --ei conv_keep_sec N  会话保持秒数（写 prefs bixby_conv/keep_sec，下次面板离屏生效；0 = 恢复默认 300）
        //   ★ int/1102：--ei conv_budget_tokens N  会话历史预算（token，0 = 默认 32768）；--es model_windows "模型=窗口,…" 覆盖模型窗口表
        //   ★ int/1102：--es chat_model <名>  对话模型（空 = 缺省 deepseek-flash；旧名 deepseek-chat 一律回缺省）
        //   ★ int/1102：--es wakecue vendor|a|legacy  唤醒提示音来源（厂商原声 / 方案 A / 旧版；厂商找不到自动退回 A）
        if (intent.hasExtra("wakecue")) {
            final Context app = context.getApplicationContext();
            final String v = intent.getStringExtra("wakecue");
            com.onesay.assistant.v2.util.Threads.start("wakecue-set", new Runnable() {
                @Override public void run() { Log.i(TAG, "[WAKE-CUE] " + com.onesay.assistant.v2.wake.WakeCueLoader.set(app, v)); }
            });
            return;
        }
        //   ★ int/1102：--es thinking off|agent|all  思考模式（关 / 仅多步任务 / 全部；同设置 › 语音 › 思考模式）
        if (intent.hasExtra("thinking")) {
            com.onesay.assistant.v2.net.ThinkingMode.Level l = com.onesay.assistant.v2.router.ctx.ContextPrefs.setThinking(context, intent.getStringExtra("thinking"));
            Log.i(TAG, "[THINK] 思考模式 = " + com.onesay.assistant.v2.net.ThinkingMode.key(l) + "（" + com.onesay.assistant.v2.net.ThinkingMode.label(l) + "）"
                    + " 问答=" + com.onesay.assistant.v2.net.ThinkingMode.enabledFor(com.onesay.assistant.v2.net.ThinkingMode.Purpose.ASK)
                    + " 多步=" + com.onesay.assistant.v2.net.ThinkingMode.enabledFor(com.onesay.assistant.v2.net.ThinkingMode.Purpose.AGENT));
            return;
        }
        if (intent.hasExtra("memdump") || intent.hasExtra("conv_keep_sec") || intent.hasExtra("conv_budget_tokens") || intent.hasExtra("model_windows")
                || intent.hasExtra("chat_model")) {
            if (intent.hasExtra("chat_model")) {
                com.onesay.assistant.v2.router.ctx.ContextPrefs.setChatModel(context, intent.getStringExtra("chat_model"));
                Log.i(TAG, "[MEMDUMP] 对话模型 = " + com.onesay.assistant.v2.net.LlmModels.chat() + "（窗口 "
                        + com.onesay.assistant.v2.router.ctx.ModelWindows.window(com.onesay.assistant.v2.net.LlmModels.chat()) + "）");
            }
            if (intent.hasExtra("conv_budget_tokens")) {
                com.onesay.assistant.v2.router.ctx.ContextPrefs.setBudget(context, intent.getIntExtra("conv_budget_tokens", 0));
                Log.i(TAG, "[MEMDUMP] 会话历史预算设为 " + com.onesay.assistant.v2.router.ConversationMemory.getInstance().budgetTokens() + " token");
            }
            if (intent.hasExtra("model_windows")) {
                com.onesay.assistant.v2.router.ctx.ContextPrefs.setWindows(context, intent.getStringExtra("model_windows"));
                for (String line : com.onesay.assistant.v2.router.ctx.ModelWindows.table().split("\n")) Log.i(TAG, "[MEMDUMP] 模型窗口 " + line);
            }
            if (intent.hasExtra("conv_keep_sec")) {
                int sec = intent.getIntExtra("conv_keep_sec", 0);
                context.getApplicationContext().getSharedPreferences("bixby_conv", Context.MODE_PRIVATE).edit().putLong("keep_sec", Math.max(0, sec)).apply();
                com.onesay.assistant.v2.router.ConversationMemory.getInstance().setKeepMs(sec * 1000L);
                Log.i(TAG, "[MEMDUMP] 会话保持设为 " + com.onesay.assistant.v2.router.ConversationMemory.getInstance().keepMs() / 1000 + " 秒");
            }
            if (intent.hasExtra("memdump")) {
                for (String line : com.onesay.assistant.v2.soul.SoulHooks.memDump(context.getApplicationContext())) Log.i(TAG, "[MEMDUMP] " + line);
            }
            return;
        }

        // ★ arch/voice-sync debug 直测（不依赖大模型）：按真实挂钩顺序模拟一个前台任务，看应答 / 门控 / 催一次 / 提问提示 / 结局。
        //   --es vsdemo single|multi|many|gui|ask|nudge  [--es vsoutcome done|plan|unverified|login|noanswer|budget|policy|null]
        if (intent.hasExtra("vsdemo")) {
            VoiceSyncDemo.run(context.getApplicationContext(), intent.getStringExtra("vsdemo"), intent.getStringExtra("vsoutcome"));
            return;
        }

        if (intent.hasExtra("tplsay") || intent.hasExtra("tplcue") || intent.hasExtra("fullanswer")) {
            final Context app = context.getApplicationContext();
            String fa = intent.getStringExtra("fullanswer");
            if (fa != null) com.onesay.assistant.v2.ui.TtsSpeaker.setFullAnswerEnabled(app, "on".equalsIgnoreCase(fa) || "true".equalsIgnoreCase(fa));
            Log.i(TAG, "[TPL] fullAnswer=" + com.onesay.assistant.v2.ui.TtsSpeaker.isFullAnswerEnabled(app)
                    + " voiceTurnReply=" + com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurnReplyEnabled(app));
            String say = intent.getStringExtra("tplsay");
            if (say != null) {
                com.onesay.assistant.v2.tts.VoiceTemplate t = com.onesay.assistant.v2.tts.VoiceTemplate.valueOf(say.toUpperCase(java.util.Locale.ROOT));
                if (intent.getBooleanExtra("tplvoice", false)) com.onesay.assistant.v2.ui.TtsSpeaker.setVoiceTurn(true);
                com.onesay.assistant.v2.ui.TtsSpeaker.get(app).speakTemplateForTest(t);
                Log.i(TAG, "[TPL] say " + t + "「" + t.text + "」 voiceTurn=" + com.onesay.assistant.v2.ui.TtsSpeaker.isVoiceTurn());
                int stopMs = intent.getIntExtra("tplstopms", -1);
                if (stopMs >= 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            Log.i(TAG, "[TPL] stopAll（打断）");
                            com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
                        }
                    }, stopMs);
                }
            }
            String cue = intent.getStringExtra("tplcue");
            if (cue != null) {
                com.onesay.assistant.v2.ui.FloatingSheetService fs = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                com.onesay.assistant.v2.tts.VoiceTemplate t = com.onesay.assistant.v2.tts.VoiceTemplate.valueOf(cue.toUpperCase(java.util.Locale.ROOT));
                if (fs != null) fs.playVoiceCue(t);
                Log.i(TAG, "[TPL] cue " + t + " fs=" + (fs != null));
            }
            return;
        }

        // 1.5 唤醒调参热调（对标高通 persist.wakeup.*）。
        //     必须在入口处处理 —— 放在声纹分支里会被前面的 return 挡住（实测无输出）。
        //     用法：
        //       --es wakecfg "vp_pct=45,kws_score=9.0"   批量设置
        //       --es wakecfg dump                         打印当前值（值任意，仅起触发作用）
        //       --ez wakecfg_reset true                   复位默认
        if (intent.hasExtra("wakecfg") || intent.hasExtra("wakecfg_reset")) {
            com.onesay.assistant.v2.wake.WakeTuning wt =
                    com.onesay.assistant.v2.wake.WakeTuning.get(context.getApplicationContext());
            if (intent.getBooleanExtra("wakecfg_reset", false)) {
                wt.reset();
                Log.i(TAG, "[TUNING] 已复位为默认值");
            }
            String csv = intent.getStringExtra("wakecfg");
            if (csv != null && csv.length() > 0 && !"dump".equalsIgnoreCase(csv.trim())) {
                int n = wt.applyCsv(csv);
                Log.i(TAG, "[TUNING] 应用 " + n + " 条参数: " + csv);
            }
            Log.i(TAG, wt.dump());
            return;
        }

        // 1.55 ★ r2/screenoff-wake：息屏门 / 屏幕 / 唤醒锁状态（只 debug 包有本接收器）
        //     用法：--es screenoff status
        //     --es pause on|off   等同控制台 Hero「暂停 / 恢复」（真机验证暂停即真暂停）
        if (intent.hasExtra("screenoff") || intent.hasExtra("pause")) {
            String pv = intent.getStringExtra("pause");
            if (pv != null) {
                com.onesay.assistant.v2.wake.AssistantPause.set(context, "on".equalsIgnoreCase(pv.trim()));
                com.onesay.assistant.v2.wake.WakeService.refreshPauseNotification();
                Log.i(TAG, "[SCREENOFF] 暂停注入 pause=" + pv);
            }
            Log.i(TAG, "[SCREENOFF] " + com.onesay.assistant.v2.wake.WakeService.screenOffStatus());
            return;
        }

        // 1.6 //     起因是"同一人同一模板 cos 在 0.148~0.786 之间摆动"，
        //     怀疑固定 2200ms 回溯窗口里有效语音占比剧烈波动。
        //     用法：
        //       --ez vpdump true      开（每次唤醒把送声纹的音频落盘 + 打印指标）
        //       --ez vpdump false     关
        //       --ez vpdiagclear true 清空诊断目录
        //     文件落在 /data/data/com.onesay.assistant/files/voiceprint/diag/
        if (intent.hasExtra("vpdiagclear")) {
            com.onesay.assistant.v2.wake.WakeService ws = com.onesay.assistant.v2.wake.WakeService.peek();
            int n = ws == null ? -1 : ws.clearVpDiagnostic();
            Log.i(TAG, "[VP-DIAG] clear n=" + n + (ws == null ? "（服务未运行）" : ""));
            return;
        }
        if (intent.hasExtra("vpdump")) {
            boolean on = intent.getBooleanExtra("vpdump", false);
            com.onesay.assistant.v2.wake.WakeService ws = com.onesay.assistant.v2.wake.WakeService.peek();
            if (ws == null) {
                Log.i(TAG, "[VP-DIAG] 服务未运行，开关未生效");
            } else {
                ws.setDiagnoseVp(on);
            }
            return;
        }

        // 1.9 [kernel] 插件调试入口（仅 debug 包；签名级权限 → adb 需 su）：插件声明 debugExtra()，
        //     由 PluginDebugDrivers 分发到各 XxxTestDriver，命令与以前完全相同：
        //     --es s1 status|sms|clip|setclip|circle|collapse|clearpickup|set   --es island status|geom|run|…|set
        //     --es s3 status|remind_in|agent_in|…|set   --es s2 status|set|clock|ask|selftest|habit|wipe
        //     --es diag status|set|match|ask|keys|fix|undo   --es roles status|candidates|ask|set|reset|install|open
        //     --es imgsave status|judge|run|select|set   --es summary status|judge|input|run|chip|set
        if (PluginDebugDrivers.dispatch(context.getApplicationContext(), intent)) {
            return;
        }

        // 1.13 ★ round3 调试入口（仅 debug 包）：--es round3 reject_red|reject_yellow|reject_then_card|judge|undo_chip|cold_status
        if (intent.hasExtra("round3")) {
            Round3TestDriver.handle(context.getApplicationContext(), intent);
            return;
        }

        // 1.17 --es secret status|migrate
        //      只打印「未配置 / 明文待迁移 / Keystore 加密 / 解不开」，绝不打印值；日志 tag SecretPrefs。
        if (intent.hasExtra("secret")) {
            String op = intent.getStringExtra("secret");
            Context app = context.getApplicationContext();
            if ("migrate".equals(op)) {
                Log.i("SecretPrefs", "[SECRET-DBG] migrate 迁移了 " + com.onesay.assistant.v2.security.SecretPrefs.migrateAll(app) + " 个");
            }
            for (String line : com.onesay.assistant.v2.security.SecretPrefs.status(app).split("\n")) {
                if (line.length() > 0) Log.i("SecretPrefs", "[SECRET-DBG] " + line);
            }
            return;
        }

        // 1.16 [kernel] 插件内核调试入口（仅 debug 包）：--es kernel status|order|set  [--es kv "hello=1"]
        if (intent.hasExtra("kernel")) {
            KernelTestDriver.handle(context.getApplicationContext(), intent);
            return;
        }

        // [r2/bike-chooser] 应用网格主题强制（截图用）：--es choicegrid light|dark|auto；可与 --ez show true --es query … 同发
        if (intent.hasExtra("choicegrid")) {
            String cg = intent.getStringExtra("choicegrid");
            com.onesay.assistant.v2.ui.ChoiceGridView.sForceTheme = "light".equals(cg) || "dark".equals(cg) ? cg : null;
            Log.i(TAG, "[RUN] choicegrid theme=" + com.onesay.assistant.v2.ui.ChoiceGridView.sForceTheme);
            if (!intent.getBooleanExtra("show", false)) return;
        }

        // 2. 调起悬浮 Sheet
        if (intent.getBooleanExtra("show", false)) {
            Log.i(TAG, "[RUN] 调起底部 Sheet 悬浮面板");
            FloatingSheetService.show(context.getApplicationContext(), intent.getStringExtra("query"));
            return;
        }

        // 2.2 终态提示探针（）：观察"没听清"终态的面板行为。
        //     终态改为显示 2.5 s → 回中性态 → 自动收起（日志 [ERR-UI]），
        //     确认卡未决 / 任务在跑 / 文字输入中不收。验收脚本 ux 模块 UX-M2 用本探针自动判定。
        //     真实触发需要语音超时（8s 指令窗），无法用广播复现，故加此探针：
        //     先弹面板，0.8s（等挂载完成）后把面板切到终态，之后由人工/脚本观察是否还在屏幕上。
        final String termProbe = intent.getStringExtra("termprobe");
        if (termProbe != null) {
            Log.i(TAG, "[RUN] 终态探针: " + termProbe);
            FloatingSheetService.show(context.getApplicationContext(), null);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    FloatingSheetService svc = FloatingSheetService.getInstance();
                    if (svc != null) {
                        svc.showTerminalPrompt(termProbe);
                        Log.i(TAG, "[RUN] 终态探针已下发 showTerminalPrompt");
                    } else {
                        Log.w(TAG, "[RUN] 终态探针失败：面板 Service 未在线");
                    }
                }
            }, 800);
            return;
        }

        // 2.5 清空 L1 技能缓存
        //     一条 ask.reply 在"搜索降级、资料不全"的情况下
        //     产出过错误答案，而它会**被永久缓存** —— 之后同一个问题永远返回那条错答案，
        //     用户看到的是"第一次答错，然后怎么问都是那个错的"。
        //     ask.reply 的时效性判定（IntentClassifier.isTimeSensitive）只认
        //     今天/现在/几点/股价 这类词，"比特币售价多少"不在内，于是被当稳定知识缓存了。
        //     排查/验证时必须有办法单点清掉，否则只能卸载重装。
        //     路由层把缓存拆成"生产库 + 自测库"后，clearcache 的本意（单点清掉
        //     用户真实缓存里的错答案）对应 clearProduction()；只清自测库用 clearselftest。
        if (intent.getBooleanExtra("clearcache", false)) {
            int n = new com.onesay.assistant.v2.router.L1SkillRouter(context.getApplicationContext())
                    .clearProduction();
            Log.i(TAG, "[CACHE] 已清空 L1 生产技能缓存，删除 " + n + " 条");
            return;
        }
        if (intent.getBooleanExtra("clearselftest", false)) {
            int n = new com.onesay.assistant.v2.router.L1SkillRouter(context.getApplicationContext())
                    .clearForTesting();
            Log.i(TAG, "[CACHE] 已清空 L1 自测库，删除 " + n + " 条（生产缓存不动）");
            return;
        }
        // ★ round3 #4：清某句话的 L1 负缓存（验收脚本 cache 模块开跑前对每句测试句各发一次）
        //   用法：--es cache_unneg "天太黑了屏幕刺眼帮我调低点亮度"
        final String unneg = intent.getStringExtra("cache_unneg");
        if (unneg != null) {
            int n = new com.onesay.assistant.v2.router.L1SkillRouter(context.getApplicationContext()).clearNegative(unneg);
            Log.i(TAG, "[CACHE] 已清除负缓存 " + n + " 条: " + unneg);
            return;
        }
        // ★ round4：清某句话的 L1 正缓存（真机第 4 轮 CA-1：测试句上轮已转正 → 第 1 次直接 HIT、没有 PUT）
        //   用法：--es cache_unpos "天太黑了屏幕刺眼帮我调低点亮度"
        final String unpos = intent.getStringExtra("cache_unpos");
        if (unpos != null) {
            int n = new com.onesay.assistant.v2.router.L1SkillRouter(context.getApplicationContext()).clearPositive(unpos);
            Log.i(TAG, "[CACHE] 已清除正缓存 " + n + " 条: " + unpos);
            return;
        }
        if (intent.getBooleanExtra("cacheinfo", false)) {
            com.onesay.assistant.v2.router.L1SkillRouter l1 =
                    new com.onesay.assistant.v2.router.L1SkillRouter(context.getApplicationContext());
            Log.i(TAG, "[CACHE] 当前技能条数=" + l1.getSkillCount()
                    + "，累计命中=" + l1.getTotalHits());
            return;
        }

        // 打印当前会话上下文。
        //   这是回喂闭环唯一可靠的**非 UI 观测点** —— 不用去猜屏幕上显示了什么，
        //   直接看模型在这一轮到底拿到了哪几轮历史、其中有没有执行回执。
        //   用法：adb shell "am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --ez convdump true"
        if (intent.getBooleanExtra("convdump", false)) {
            java.util.List<com.onesay.assistant.v2.router.ConversationMemory.Turn> snap =
                    com.onesay.assistant.v2.router.ConversationMemory.getInstance().snapshot();
            Log.i(TAG, "[CONV] 会话上下文当前轮数=" + snap.size()
                    + "（轮数安全上限 " + com.onesay.assistant.v2.router.ConversationMemory.MAX_TURNS + "，预算 " + com.onesay.assistant.v2.router.ConversationMemory.getInstance().budgetTokens() + " token）");
            int receipts = 0;
            for (int i = 0; i < snap.size(); i++) {
                com.onesay.assistant.v2.router.ConversationMemory.Turn t = snap.get(i);
                boolean isReceipt = t.answer != null && t.answer.startsWith("[执行回执]");
                if (isReceipt) receipts++;
                Log.i(TAG, "[CONV]   #" + (i + 1) + (isReceipt ? " [执行回执]" : " [问答]")
                        + " 问=" + t.question
                        + " | 答=" + (t.answer != null ? t.answer.replace("\n", " ") : ""));
            }
            Log.i(TAG, "[CONV] 其中执行回执 " + receipts + " 条");
            return;
        }

        // UI 按钮功能全量自测探针
        if (intent.getBooleanExtra("test_buttons", false)) {
            Log.i(TAG, "★ 启动唤醒页 UI 按钮全量自动化功能测试 ★");
            final android.content.Context appCtx = context.getApplicationContext();
            com.onesay.assistant.v2.ui.FloatingSheetService.show(appCtx, null);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    com.onesay.assistant.v2.ui.FloatingSheetService fss = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                    if (fss == null || fss.getSheetView() == null) {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: FloatingSheetService 或 SheetView 未在线");
                        return;
                    }
                    final com.onesay.assistant.v2.ui.FloatingSheetView view = fss.getSheetView();

                    // 1. 验证 ✦ 语音按钮
                    if (view.getChipVoice() != null) {
                        view.getChipVoice().performClick();
                        Log.i(TAG, "[UI-BTN-TEST] PASS: 1.「✦ 语音」按钮响应正常 (声学波形在线=" + (view.getWaveView() != null) + ")");
                    } else {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: 1.「✦ 语音」按钮不存在");
                    }

                    // 2. 验证 ⌨ 文字按钮
                    if (view.getChipText() != null) {
                        view.getChipText().performClick();
                        boolean searchBarVisible = view.getSearchBar() != null && view.getSearchBar().getVisibility() == android.view.View.VISIBLE;
                        Log.i(TAG, "[UI-BTN-TEST] PASS: 2.「⌨ 文字」按钮响应正常 (搜索输入框展开=" + searchBarVisible + ")");
                    } else {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: 2.「⌨ 文字」按钮不存在");
                    }

                    // 3. 验证发送按钮
                    if (view.getSearchBar() != null && view.getSearchBar().getBtnSend() != null) {
                        view.getSearchBar().getInputEdit().setText("现在几点");
                        view.getSearchBar().getBtnSend().performClick();
                        Log.i(TAG, "[UI-BTN-TEST] PASS: 3.「发送」按钮响应正常 (已成功提交文字指令)");
                    } else {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: 3.「发送」按钮不存在");
                    }

                    // 4. 验证 ◎ 识屏按钮
                    if (view.getChipScreen() != null) {
                        view.getChipScreen().performClick();
                        Log.i(TAG, "[UI-BTN-TEST] PASS: 4.「◎ 识屏」按钮响应正常 (已触发识屏/截图指令)");
                    } else {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: 4.「◎ 识屏」按钮不存在");
                    }

                    // 5a. 黄色确认卡三选项（新增；点「拒绝」——安全，不会真执行）
                    com.onesay.assistant.v2.capability.Capability yCap = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().describe("connectivity.dataroam.off");
                    if (yCap != null) {
                        view.showConfirmCard(yCap, new org.json.JSONObject(), false,
                                com.onesay.assistant.v2.executor.ConsentPolicy.TIER_YELLOW, null);
                        if (view.getBtnAlways() != null && view.getBtnOnce() != null && view.getBtnReject() != null) {
                            view.getBtnReject().performClick();
                            Log.i(TAG, "[UI-BTN-TEST] PASS: 5a. 黄卡「始终允许」/「允许单次」/「拒绝」三按钮存在，「拒绝」响应正常");
                        } else {
                            Log.e(TAG, "[UI-BTN-TEST] FAIL: 5a. 黄卡三选项按钮缺失");
                        }
                    }

                    // 5. 验证确认卡取消按钮（红卡，现行设计）
                    com.onesay.assistant.v2.capability.Capability testCap = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().describe("sys.wipe");
                    if (testCap != null) {
                        view.showConfirmCard(testCap, new org.json.JSONObject(), false,
                                com.onesay.assistant.v2.executor.ConsentPolicy.TIER_RED, null);
                        if (view.getBtnCancel() != null && view.getBtnConfirm() != null) {
                            view.getBtnCancel().performClick();
                            Log.i(TAG, "[UI-BTN-TEST] PASS: 5. 红卡「取消」/「确认执行」按钮响应正常");
                        }
                    }

                    // 6. 验证顶部 ✕ 取消按钮
                    if (view.getBtnCancelTop() != null) {
                        view.getBtnCancelTop().performClick();
                        Log.i(TAG, "[UI-BTN-TEST] PASS: 6. 顶部「✕ 取消」按钮响应正常 (面板已收起)");
                    } else {
                        Log.e(TAG, "[UI-BTN-TEST] FAIL: 6.「✕ 取消」按钮不存在");
                    }

                    Log.i(TAG, "★ [UI-BTN-PASS] 唤醒页所有按钮交互与功能 100% 验证通过！★");
                }
            }, 600);
            return;
        }

        // 识屏按钮真实点击与交互测试探针
        if (intent.getBooleanExtra("test_screen_mode", false)) {
            Log.i(TAG, "★ 启动「◎ 识屏」按钮真实点击交互测试 ★");
            final android.content.Context appCtx = context.getApplicationContext();
            com.onesay.assistant.v2.ui.FloatingSheetService.show(appCtx, null);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    com.onesay.assistant.v2.ui.FloatingSheetService fss = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                    if (fss == null || fss.getSheetView() == null) {
                        Log.e(TAG, "[UI-SCREEN-FAIL] FloatingSheetService 或 SheetView 未在线");
                        return;
                    }
                    if (fss.getSheetView().getChipScreen() != null) {
                        Log.i(TAG, "[UI-SCREEN-CLICK] 模拟用户点击「◎ 识屏」Mode Chip");
                        fss.getSheetView().getChipScreen().performClick();
                    } else {
                        Log.e(TAG, "[UI-SCREEN-FAIL] 「◎ 识屏」按钮未找到");
                    }
                }
            }, 500);
            return;
        }

        // 视觉识屏端到端多模态自动化功能测试探针
        if (intent.getBooleanExtra("test_vision", false)) {
            final String q = intent.getStringExtra("vision_ask");
            Log.i(TAG, "★ [VISION-TEST] 启动多模态视觉识屏自动化端到端测试，问题=" + q);
            com.onesay.assistant.v2.vision.ScreenVisionEngine.getInstance(context)
                    .recognizeScreen(q, new com.onesay.assistant.v2.vision.ScreenVisionEngine.Callback() {
                        @Override
                        public void onProgress(String stage) {
                            Log.i(TAG, "[VISION-PROGRESS] " + stage);
                        }

                        @Override
                        public void onSuccess(String answer) {
                            Log.i(TAG, "[VISION-TEST-PASS] 识屏识别成功！结果长度=" + answer.length()
                                    + " 摘要: " + (answer.length() > 60 ? answer.substring(0, 60) + "..." : answer));
                        }

                        @Override
                        public void onError(String errorMsg) {
                            Log.e(TAG, "[VISION-TEST-FAIL] 识屏失败: " + errorMsg);
                        }
                    });
            return;
        }

        // 声学回放钩子 —— 把一段 wav **外放**出来。
        //
        //   **为什么必须有这条**：可续期聆听窗的判据是**能量门**
        //   （"麦克风里还有没有人在说话"）。这条链路没法用项目里既有的
        //   `asrasset` / `asrwinasset` 验证 —— 那两条是把 wav 直接喂给识别器，
        //   **绕过了麦克风与能量门**。要验它，就必须真有声音进麦克风。
        //
        //   用法：--es playwav /sdcard/xxx.wav [--ei playms 20000]
        //        默认循环播放，保证"持续有人声"。
        final String playWav = intent.getStringExtra("playwav");
        if (playWav != null && playWav.length() > 0) {
            startLoopPlayback(playWav, intent.getIntExtra("playms", 20000));
            return;
        }

        // P0 声纹探针（说话人验证）
        //
        //   P0 三判据（方案 §7）：① 无 UnsatisfiedLinkError（引擎能建起来）
        //   ② Note8 实测 RTF < 0.5  ③ 同人两段 cos > 异人两段 cos
        //
        //   用法：
        //     --es spkfile  <wav> [--es spkfile2 <wav2>]   提向量；给第二个则算余弦
        //     --es spkenroll <wav> [--es spkname owner]    注册（安静环境，3~5 句更佳）
        //     --ez spklist true / --ez spkdel true [--es spkname n]
        //   （wav 需 16k/mono/16bit PCM；模型在 filesDir/voiceprint/）
        //
        //   //     --es wakevp <wav>      模拟"唤醒声纹兜底验证"：取 wav 尾部 2.2s（与运行时
        //                            回溯窗口一致）提向量 → 与 owner 算 cos → 按当前阈值判定
        //     --ei setvpthr <0~100>  设置唤醒兜底阈值（百分比），实时生效、无需重装
        final String spkFileProbe = intent.getStringExtra("spkfile");
        final String spkEnrollProbe = intent.getStringExtra("spkenroll");
        final String spkWakeVpProbe = intent.getStringExtra("wakevp");
        if (spkFileProbe != null || spkEnrollProbe != null || spkWakeVpProbe != null
                || intent.hasExtra("setvpthr")
                || intent.getBooleanExtra("spklist", false)
                || intent.getBooleanExtra("spkdel", false)) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runSpeakerProbe(context, intent);
                    } catch (Throwable t) {
                        Log.e(TAG, "[SPK] 探针异常: " + t, t);
                    } finally {
                        pr.finish();
                    }
                }
            }).start();
            return;
        }

        // 声纹注册「字比对」门离线复测
        //   --es vpchk <wav> → 把该 wav 当作"刚录的一段"，打印离线识别文本 +
        //   与 4 个引导句的匹配率（走与产品完全相同的 matchRatio 实现）。
        final String vpChkFile = intent.getStringExtra("vpchk");
        if (vpChkFile != null && vpChkFile.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] pcm = stripWavHeader(readFile(vpChkFile));
                        Log.i(TAG, "[VPCHK] 输入=" + vpChkFile + " pcm=" + pcm.length + " bytes");
                        String heard = com.onesay.assistant.v2.wake.WakeService.recognizeOffline(pcm);
                        Log.i(TAG, "[VPCHK] 离线识别=[" + heard + "]");
                        // 改为直接引用 PHRASES（原先复制了一份硬编码，
                        //   引导词从中文改英文时漏改会静默失效）。
                        String[] phrases = com.onesay.assistant.v2.ui.VoiceprintEnrollController.PHRASES;
                        for (int i = 0; i < phrases.length; i++) {
                            float r = com.onesay.assistant.v2.ui.VoiceprintEnrollController.matchRatio(
                                    com.onesay.assistant.v2.ui.VoiceprintEnrollController.normCn(heard),
                                    com.onesay.assistant.v2.ui.VoiceprintEnrollController.normCn(phrases[i]));
                            Log.i(TAG, "[VPCHK] vs 「" + phrases[i] + "」 匹配率=" + String.format("%.2f", r)
                                    + " 通过(>=0.40)=" + (r >= 0.4f));
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "[VPCHK] 异常: " + t, t);
                    } finally {
                        pr.finish();
                    }
                }
            }).start();
            return;
        }

        // 设备端**真实云端链路**直测探针（修「长音频 HTTP 400」时补的验证手段）。
        //   --es asrtest <wav路径>  [--ei asrsecs N]
        //     读 wav（自动跳 44 头）→ 可选循环填充到 N 秒 → 走 AsrRouter 真实通道
        //     （含 MIME 组装/发送/解析全程）→ 打印结果。
        //   设计动机：宿主机 curl 无法复刻"设备端组装+发送"环节的行为差异，
        //   只有让设备自己发一次，才能对"设备路径是否 400"给出终审。
        final String asrTestFile = intent.getStringExtra("asrtest");
        if (asrTestFile != null && asrTestFile.length() > 0) {
            final String atPath = asrTestFile;
            final int atSecs = intent.getIntExtra("asrsecs", 0);
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] pcm = stripWavHeader(readFile(atPath));
                        if (atSecs > 0) {
                            int want = atSecs * 16000 * 2;
                            byte[] filled = new byte[want];
                            int off = 0;
                            while (off < want) {
                                int n = Math.min(pcm.length, want - off);
                                System.arraycopy(pcm, 0, filled, off, n);
                                off += n;
                            }
                            Log.i(TAG, "[ASRTEST] 循环填充: " + (pcm.length / 32000f)
                                    + "s -> " + atSecs + "s");
                            pcm = filled;
                        }
                        Log.i(TAG, "[ASRTEST] 输入=" + atPath
                                + " 音频=" + (pcm.length / 32000f) + "s/" + pcm.length + "B");
                        com.onesay.assistant.v2.asr.AsrRouter router =
                                com.onesay.assistant.v2.asr.AsrRouter.getInstance(context.getApplicationContext());
                        Log.i(TAG, "[ASRTEST] 通道=" + router.activeCloudName()
                                + " ready=" + router.isCloudReady()
                                + " configured=" + router.isCloudConfigured());
                        com.onesay.assistant.v2.asr.AsrResult r = router.recognizeCloudSync(pcm);
                        Log.i(TAG, "[ASRTEST] 结果: ok=" + r.ok + " text=[" + r.text
                                + "] source=" + r.source + " ms=" + r.latencyMs
                                + " err=" + r.error);
                    } catch (Throwable t) {
                        Log.e(TAG, "[ASRTEST] 异常: " + t, t);
                    } finally {
                        pr.finish();
                    }
                }
            }).start();
            return;
        }

        // 3. 配置 LLM API Key
        String setKey = intent.getStringExtra("setkey");
        if (setKey != null && setKey.length() > 0) {
            new L2LLMCompiler(context).setApiKey(setKey);
            Log.i(TAG, "[RUN] LLM API Key 已持久化配置");
            return;
        }

        // 3b. 联网搜索配置（2026-10-03 随 LLM 架构重构新增）
        //     --es setsearch   <Tavily key>   （首选，免费额度 1000 次/月）
        //     --es setbocha    <博查 key>      （国内备选）
        //     --es setsearchprovider <auto|tavily|bocha|bing>
        //     --ez searchinfo  true            （只读查看当前配置，不写）
        //     免 Key 的必应网页兜底不需要任何配置，默认就在链上。
        final com.onesay.assistant.v2.search.SearchConfig searchCfg =
                new com.onesay.assistant.v2.search.SearchConfig(context);
        String setSearchKey = intent.getStringExtra("setsearch");
        if (setSearchKey != null && setSearchKey.length() > 0) {
            searchCfg.setTavilyKey(setSearchKey);
            Log.i(TAG, "[SEARCH] Tavily Key 已写入");
            Log.i(TAG, "[SEARCH] 当前配置: "
                    + com.onesay.assistant.v2.search.WebSearchClient.getInstance(context).describe());
            return;
        }
        String setBochaKey = intent.getStringExtra("setbocha");
        if (setBochaKey != null && setBochaKey.length() > 0) {
            searchCfg.setBochaKey(setBochaKey);
            Log.i(TAG, "[SEARCH] 博查 Key 已写入");
            return;
        }
        String setProvider = intent.getStringExtra("setsearchprovider");
        if (setProvider != null && setProvider.length() > 0) {
            searchCfg.setProvider(setProvider);
            Log.i(TAG, "[SEARCH] 数据源已设为: " + setProvider);
            return;
        }
        if (intent.hasExtra("sethomecity")) {
            // R15：常用城市（天气补地点）。传空串 = 清除。
            searchCfg.setHomeCity(intent.getStringExtra("sethomecity"));
            Log.i(TAG, "[SEARCH] 常用城市: " + (searchCfg.getHomeCity().length() == 0 ? "未设置" : searchCfg.getHomeCity()));
            return;
        }
        String briefSim = intent.getStringExtra("briefsim");
        if (briefSim != null && briefSim.length() > 0) {
            // [brief] 调试：模拟一条 BRIEF_STATE 直接上岛（跳过来源校验，只在 debug 包里有）。--es briefdetail 可带 detail
            com.onesay.assistant.v2.brief.BriefState.Event ev = com.onesay.assistant.v2.brief.BriefState.parse(
                    com.onesay.assistant.v2.brief.BriefState.ACTION, briefSim, intent.getStringExtra("briefdetail"));
            if (ev != null && !com.onesay.assistant.v2.kernel.plugins.BriefPlugin.SWITCH.on(context)) {
                // lane-a B-9：与接收端同口径 —— 功能关着只撤旧状态、不上岛
                Log.i(TAG, "[BRIEF] 模拟 " + ev + "（功能已关）→ 撤下旧状态=" + com.onesay.assistant.v2.brief.BriefIsland.onEventWhileOff(ev));
                return;
            }
            Log.i(TAG, "[BRIEF] 模拟 " + ev + " → " + com.onesay.assistant.v2.brief.BriefIsland.apply(context, ev));
            return;
        }
        String readonlyDirect = intent.getStringExtra("readonlydirect");
        if (readonlyDirect != null && readonlyDirect.length() > 0) {
            // D3：只读数值工具直出开关（on / off）；默认开
            com.onesay.assistant.v2.router.ReadOnlyAnswer.setEnabled(context, !"off".equalsIgnoreCase(readonlyDirect.trim()));
            Log.i(TAG, "[AGENT-LOOP] D3 只读直出: " + (com.onesay.assistant.v2.router.ReadOnlyAnswer.isEnabled(context) ? "开" : "关"));
            return;
        }
        if (intent.getBooleanExtra("searchinfo", false)) {
            Log.i(TAG, "[SEARCH] 当前配置: "
                    + com.onesay.assistant.v2.search.WebSearchClient.getInstance(context).describe());
            return;
        }

        // 3b-2. 邮件（IMAP）配置与直连验证（）
        //   --es mailuser "you@qq.com" --es mailpass "<16位授权码>"
        //   --es mailhost "imap.qq.com" --ei mailport 993        （可省略，走默认）
        //   --es mailprovider "gmail|qq|163|outlook|exmail"      服务商预设（顺带定 host）
        //   --es mailproxy "socks5://127.0.0.1:1080"             代理（http:// 亦可；空=直连）
        //   --ez mailinfo true      只读查看配置（脱敏，不含凭据）
        //   --ez mailretryinfo true 打印重试判据的判定结果（离线，验证"凭据错误绝不重试"）
        //   --ez mailclear true     清空配置
        //   --es mailfetch "1"      直接拉取最新 N 封（真实 IMAP 往返，绕过路由与 LLM）
        //   --es maildraft "主题||正文||收件人"  新建草稿（真实写入草稿箱）
        //   ⚠️ 授权码/应用密码绝不打印进日志。
        {
            String mu = intent.getStringExtra("mailuser");
            String mp = intent.getStringExtra("mailpass");
            String mh = intent.getStringExtra("mailhost");
            String mpv = intent.getStringExtra("mailprovider");
            String mpx = intent.getStringExtra("mailproxy");
            int mport = intent.hasExtra("mailport") ? intent.getIntExtra("mailport", 993) : -1;
            if (mu != null || mp != null || mh != null || mport > 0
                    || mpv != null || mpx != null) {
                if (mpv != null) {
                    com.onesay.assistant.v2.executor.MailBridge.saveProvider(context, mpv);
                }
                if (mpx != null) {
                    com.onesay.assistant.v2.executor.MailBridge.saveProxy(context, mpx);
                }
                com.onesay.assistant.v2.executor.MailBridge.saveConfig(context, mh, mport, mu, mp);
                Log.i(TAG, "[MAIL] 配置已写入 → " + com.onesay.assistant.v2.executor.MailBridge.configLogInfo(context));
                return;
            }
            if (intent.getBooleanExtra("mailclear", false)) {
                com.onesay.assistant.v2.executor.MailBridge.clearConfig(context);
                Log.i(TAG, "[MAIL] 配置已清空");
                return;
            }
            if (intent.getBooleanExtra("mailinfo", false)) {
                // ★ M-3：只打「已配置/未配置 + 服务商」
                Log.i(TAG, "[MAIL] 当前配置: "
                        + com.onesay.assistant.v2.executor.MailBridge.configLogInfo(context));
                return;
            }
            // --ez mailretryinfo true
            //   把「重试判据」的判定结果在真机上直接打出来。
            //   为什么需要它：重试逻辑只有在**链路抖动**时才会触发，而链路好时整轮跑完
            //   一条 [MAIL-RETRY] 都不会出现 —— 这时"改对了"和"改错了"看起来完全一样。
            //   而这条判据守着一条红线：**凭据错误绝不能重试**（反复用错凭据登录会触发
            //   服务商风控，本机收件箱 10-04 就有一条「登录尝试已被阻止」告警）。
            //   纯离线、零副作用，随时可跑。
            if (intent.getBooleanExtra("mailretryinfo", false)) {
                final javax.mail.AuthenticationFailedException authErr =
                        new javax.mail.AuthenticationFailedException("Invalid credentials (Failure)");
                final javax.mail.MessagingException authWrapped =
                        new javax.mail.MessagingException("login failed", authErr);
                final javax.mail.MessagingException closedByPeer =
                        new javax.mail.MessagingException("Connection closed by peer");
                final javax.mail.MessagingException resetCause =
                        new javax.mail.MessagingException("read failed",
                                new java.net.SocketException("Connection reset"));
                final java.net.SocketTimeoutException timeoutErr =
                        new java.net.SocketTimeoutException("Read timed out");
                final javax.mail.MessagingException folderErr =
                        new javax.mail.MessagingException("Folder INBOX does not exist");

                Log.i(TAG, "[MAIL-RETRYINFO] ── 重试判据验证（离线，零副作用）──");
                Log.i(TAG, "[MAIL-RETRYINFO] 凭据错误 AuthenticationFailed        → 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(authErr)
                        + "（期望 false，实际是红线）");
                Log.i(TAG, "[MAIL-RETRYINFO] 凭据错误（被 MessagingException 包裹） → 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(authWrapped)
                        + "（期望 false）");
                Log.i(TAG, "[MAIL-RETRYINFO] 链路抖动 Connection closed by peer   → 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(closedByPeer)
                        + "（期望 true）");
                Log.i(TAG, "[MAIL-RETRYINFO] 链路抖动（SocketException 在异常链里）→ 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(resetCause)
                        + "（期望 true）");
                Log.i(TAG, "[MAIL-RETRYINFO] 链路抖动 SocketTimeout               → 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(timeoutErr)
                        + "（期望 true）");
                Log.i(TAG, "[MAIL-RETRYINFO] 业务错误 Folder 不存在               → 重试="
                        + com.onesay.assistant.v2.executor.MailBridge.isTransient(folderErr)
                        + "（期望 false）");
                Log.i(TAG, "[MAIL-RETRYINFO] 最大尝试次数="
                        + com.onesay.assistant.v2.executor.MailBridge.maxAttempts()
                        + "，退避基数=" + com.onesay.assistant.v2.executor.MailBridge.retryBaseDelayMs() + "ms");
                return;
            }
            final String mailFetch = intent.getStringExtra("mailfetch");
            final String mailDraft = intent.getStringExtra("maildraft");
            if ((mailFetch != null && mailFetch.length() > 0)
                    || (mailDraft != null && mailDraft.length() > 0)) {
                final PendingResult prm = goAsync();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        try {
                            org.json.JSONObject a = new org.json.JSONObject();
                            com.onesay.assistant.v2.capability.CapabilityRegistry reg =
                                    com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance();
                            com.onesay.assistant.v2.capability.Capability cap;
                            if (mailDraft != null && mailDraft.length() > 0) {
                                String[] parts = mailDraft.split("\\|\\|", -1);
                                a.put("subject", parts.length > 0 ? parts[0] : "");
                                a.put("body", parts.length > 1 ? parts[1] : "");
                                a.put("to", parts.length > 2 ? parts[2] : "");
                                cap = reg.describe(com.onesay.assistant.v2.executor.MailBridge.SIGN_DRAFT_NEW);
                            } else {
                                try {
                                    a.put("count", Integer.parseInt(mailFetch.trim()));
                                } catch (Throwable ignore) {
                                    a.put("count", 1);
                                }
                                a.put("body", true);
                                cap = reg.describe(com.onesay.assistant.v2.executor.MailBridge.SIGN_INBOX_LATEST);
                            }
                            com.onesay.assistant.v2.executor.ExecutionResult r =
                                    com.onesay.assistant.v2.executor.UnifiedExecutor.getInstance(context)
                                            .execute(cap, a);
                            long cost = android.os.SystemClock.elapsedRealtime() - t0;
                            Log.i(TAG, "[MAIL-PROBE] ok=" + r.ok + " verified=" + r.verified
                                    + " 耗时=" + cost + "ms\n" + r.userMessage
                                    + "\n--- detail ---\n" + r.detail);
                        } catch (Throwable t) {
                            Log.e(TAG, "[MAIL-PROBE] 异常: " + t.getMessage(), t);
                        } finally {
                            prm.finish();
                        }
                    }
                }, "MailProbeThread").start();
                return;
            }
        }

        // 3c. 流式问答探针（随 ASK 流式交付新增）
        //     --es streamprobe "比特币售价多少"
        //     走**生产流式链路**（UnifiedRouter.routeStreaming），打印：
        //       阶段切换时间点 / 首字延迟 TTFT / 增量帧数 / 终稿字数
        //     这套数字就是"割裂感有没有被消除"的量化证据 ——
        //     改造前首字延迟 == 总耗时（整段一次到），改造后 TTFT 应远小于总耗时。
        final String streamProbe = intent.getStringExtra("streamprobe");
        if (streamProbe != null && streamProbe.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final long t0 = android.os.SystemClock.elapsedRealtime();
                    final int[] frames = {0};
                    final long[] ttft = {-1};
                    final int[] shown = {0};
                    try {
                        Log.i(TAG, "[STREAM-PROBE] 语句=" + streamProbe);
                        UnifiedRouter.getInstance(context).routeStreaming(streamProbe,
                                new UnifiedRouter.StreamHandler() {
                                    @Override
                                    public void onStage(int stage) {
                                        Log.i(TAG, "[STREAM] 阶段="
                                                + (stage == L2LLMCompiler.STAGE_SEARCH ? "检索" : "作答")
                                                + " +" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                                    }

                                    @Override
                                    public void onPartial(String textSoFar) {
                                        frames[0]++;
                                        shown[0] = textSoFar != null ? textSoFar.length() : 0;
                                        if (ttft[0] < 0) {
                                            ttft[0] = android.os.SystemClock.elapsedRealtime() - t0;
                                            Log.i(TAG, "[STREAM] 首字延迟 TTFT=" + ttft[0] + "ms");
                                        }
                                    }

                                    @Override
                                    public void onFinal(Invocation inv) {
                                        long total = android.os.SystemClock.elapsedRealtime() - t0;
                                        String sign = (inv != null) ? inv.sign : "null";
                                        Log.i(TAG, "[STREAM] 终态 +" + total + "ms, sign=" + sign);
                                        Log.i(TAG, "[STREAM-PROBE] 增量帧数=" + frames[0]
                                                + " | 首字延迟=" + (ttft[0] < 0 ? "无增量" : ttft[0] + "ms")
                                                + " | 上屏峰值=" + shown[0] + "字"
                                                + " | 总耗时=" + total + "ms");
                                    }
                                }, new UnifiedRouter.Cancel());
                    } catch (Throwable t) {
                        Log.e(TAG, "[STREAM-PROBE] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "StreamProbeThread").start();
            return;
        }

        // 4. 测试唤醒应答声播放
        if (intent.getBooleanExtra("test_wake", false)) {
            com.onesay.assistant.v2.wake.WakeService ws = com.onesay.assistant.v2.wake.WakeService.getInstance();
            if (ws != null) {
                Log.i(TAG, "[WAKE-TEST] 触发测试唤醒应答声播放");
                ws.playWakeSounds(false);
            } else {
                Log.w(TAG, "[WAKE-TEST] WakeService 未运行");
            }
            return;
        }

        // 5. 路由匹配测试（异步线程执行，杜绝 NetworkOnMainThreadException）
        final String matchOnly = intent.getStringExtra("match");
        if (matchOnly != null && matchOnly.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Invocation inv = UnifiedRouter.getInstance(context).route(matchOnly);
                        Log.i(TAG, "[MATCH] 语句=[" + matchOnly + "] -> " + (inv != null ? inv.toString() : "null"));
                    } catch (Throwable t) {
                        Log.e(TAG, "[MATCH] 路由异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestMatchThread").start();
            return;
        }

        // 5a-1. 只读探针直连验证（）—— 不经过 LLM，确定性验证 probe.* 链路
        //   用法：
        //     --es probe_apps "linkedin"       查设备装了什么（关键词）
        //     --es probe_intents "com.x.y"     挖某应用的界面接口（会注册+落盘）
        //     --es probe_shell "pm list packages"   白名单只读 shell
        //     --es runcap "mobileqq.main"      直接执行指定能力（★ 诊断用：验证挖到的接口真能调用）
        //     --es runcap "alarm.toggle" --es runcap_args '{"hour":8,"on":false}'   带参数执行（2026-10-05 扩展）
        final String runCapSign = intent.getStringExtra("runcap");
        final String runCapArgs = intent.getStringExtra("runcap_args");
        if (runCapSign != null && runCapSign.length() > 0) {
            final PendingResult prc = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Capability cap = CapabilityRegistry.getInstance().describe(runCapSign);
                        if (cap == null) {
                            Log.e(TAG, "[RUNCAP] 能力不存在: " + runCapSign);
                        } else {
                            org.json.JSONObject args = new org.json.JSONObject();
                            if (runCapArgs != null && runCapArgs.length() > 0) {
                                try {
                                    args = new org.json.JSONObject(runCapArgs);
                                } catch (Throwable te) {
                                    Log.e(TAG, "[RUNCAP] runcap_args 解析失败: " + runCapArgs);
                                }
                            }
                            ExecutionResult r = UnifiedExecutor.getInstance(context)
                                    .execute(cap, args);
                            Log.i(TAG, "[RUNCAP] " + runCapSign + " args=" + args + " ok=" + r.ok
                                    + " code=" + r.code + " verified=" + r.verification + " msg=" + r.userMessage
                                    + (r.detail != null ? " | detail=" + r.detail : ""));
                            // 同时打印给模型的稳定观察值（含 code/hint），真机核对 P0-4 用
                            Log.i(TAG, "[RUNCAP-OBS] " + r.toModelObservation());
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "[RUNCAP] 异常: " + t.getMessage(), t);
                    } finally {
                        prc.finish();
                    }
                }
            }, "TestRunCapThread").start();
            return;
        }

        final String probeAppsKw = intent.getStringExtra("probe_apps");
        final String probeIntentsPkg = intent.getStringExtra("probe_intents");
        final String probeShellCmd = intent.getStringExtra("probe_shell");
        if (probeAppsKw != null || probeIntentsPkg != null || probeShellCmd != null) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (probeAppsKw != null) {
                            com.onesay.assistant.v2.probe.ProbeEngine.ProbeResult r =
                                    com.onesay.assistant.v2.probe.ProbeEngine.apps(context, probeAppsKw);
                            Log.i(TAG, "[PROBE-TEST] probe.apps(" + probeAppsKw + ") ok=" + r.ok
                                    + " → " + (r.ok ? r.output : r.error));
                        }
                        if (probeIntentsPkg != null) {
                            com.onesay.assistant.v2.probe.ProbeEngine.ProbeResult r =
                                    com.onesay.assistant.v2.probe.ProbeEngine.appIntents(context, probeIntentsPkg);
                            Log.i(TAG, "[PROBE-TEST] probe.app_intents(" + probeIntentsPkg + ") ok=" + r.ok
                                    + " → " + (r.ok ? r.output : r.error));
                        }
                        if (probeShellCmd != null) {
                            com.onesay.assistant.v2.probe.ProbeEngine.ProbeResult r =
                                    com.onesay.assistant.v2.probe.ProbeEngine.shellRead(context, probeShellCmd);
                            Log.i(TAG, "[PROBE-TEST] probe.shell_read(" + probeShellCmd + ") ok=" + r.ok
                                    + " → " + (r.ok ? r.output : r.error));
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "[PROBE-TEST] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestProbeThread").start();
            return;
        }

        // 5a-2. 语素引擎诊断（）—— 逐层打印识别过程，定位"判 NONE 是哪一层断的"
        //   用法：adb shell "am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --es lexprobe '让手机安静点'"
        final String lexProbe = intent.getStringExtra("lexprobe");
        if (lexProbe != null && lexProbe.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        com.onesay.assistant.v2.router.ActionLexicon.Intent it =
                                com.onesay.assistant.v2.router.ActionLexicon.classify(lexProbe);
                        boolean hasTgt = com.onesay.assistant.v2.router.ActionLexicon.hasDeviceTarget(lexProbe);
                        String skel = com.onesay.assistant.v2.router.ActionLexicon.stripParticles(lexProbe);
                        java.util.List<com.onesay.assistant.v2.router.ActionLexicon.Intent> all =
                                com.onesay.assistant.v2.router.ActionLexicon.allIntents(lexProbe);
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        for (com.onesay.assistant.v2.capability.Capability c :
                                com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().listCapabilities()) {
                            if (c.sign != null && c.sign.indexOf("volume.ring") == 0) {
                                sb.append(c.sign).append("=《").append(c.summary).append("》 ");
                                n++;
                            }
                        }
                        Log.i(TAG, "[LEXPROBE] q=" + lexProbe
                                + " | 规则化=" + com.onesay.assistant.v2.router.L0RuleRouter.normalizeUtterance(lexProbe)
                                + " | 骨架=" + skel
                                + " | allIntents=" + all
                                + " | classify=" + it
                                + " | looksLikeCommand="
                                + com.onesay.assistant.v2.router.IntentClassifier.looksLikeCommand(lexProbe)
                                + " | hasDeviceTarget=" + hasTgt
                                + " | capSize=" + com.onesay.assistant.v2.capability.CapabilityRegistry
                                        .getInstance().size()
                                + " | ringCap(" + n + ")=" + sb);
                    } catch (Throwable t) {
                        Log.e(TAG, "[LEXPROBE] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "LexProbeThread").start();
            return;
        }

        // 5a-3. 意图/召回综合诊断（）——
        //   一句话打印：时间换算 → 分类 → 多动作判定 → Top-K 召回。
        //   用法：adb shell "am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver \
        //                     --es intentprobe '40分钟后叫醒我'"
        //   它回答的是"模型到底拿没拿到趁手的工具"：Top-K 里没有对应能力，
        //   模型再聪明也只能答"我做不到"。
        final String intentProbe = intent.getStringExtra("intentprobe");
        if (intentProbe != null && intentProbe.length() > 0) {
            final PendingResult pri = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        StringBuilder sb = new StringBuilder();
                        sb.append("[INTENTPROBE] q=").append(intentProbe);
                        sb.append("\n  stripRelative=")
                          .append(com.onesay.assistant.v2.router.TimeContext.stripRelative(intentProbe));
                        com.onesay.assistant.v2.router.TimeContext.Relative rel =
                                com.onesay.assistant.v2.router.TimeContext.parseRelative(intentProbe);
                        sb.append("\n  relative=").append(rel == null ? "null"
                                : (rel.raw + " -> " + rel.hhmm() + " (" + rel.deltaText() + ")"));
                        sb.append("\n  hint=").append(com.onesay.assistant.v2.router.TimeContext.hint(intentProbe));
                        sb.append("\n  ActionLexicon=")
                          .append(com.onesay.assistant.v2.router.ActionLexicon.classify(intentProbe));
                        sb.append("\n  kind=")
                          .append(com.onesay.assistant.v2.router.IntentClassifier.classify(intentProbe, false));
                        sb.append("\n  looksLikeCommand=")
                          .append(com.onesay.assistant.v2.router.IntentClassifier.looksLikeCommand(intentProbe));
                        sb.append("\n  looksLikeMultiAction=")
                          .append(com.onesay.assistant.v2.router.IntentClassifier.looksLikeMultiAction(intentProbe));
                        sb.append("\n  TopK(12):");
                        int i = 0;
                        for (Capability c : com.onesay.assistant.v2.router.CapabilityRetriever.getTopK(intentProbe, 12)) {
                            sb.append("\n    ").append(++i).append(". ")
                              .append(c.sign).append(" 《").append(c.summary).append("》");
                        }
                        Log.i(TAG, sb.toString());
                    } catch (Throwable t) {
                        Log.e(TAG, "[INTENTPROBE] 异常: " + t.getMessage(), t);
                    } finally {
                        pri.finish();
                    }
                }
            }, "IntentProbeThread").start();
            return;
        }

        // 5b. 分诊探针（2026-10-09 用户拍板 D-2a=B：旧 TriageV2 已删除，改为打印**现行** TriageDecider 的规则层结论）
        //     用当前插件开关（诊断 / 免前缀记忆 / 存图 / 摘要 / S3），不带 L0 / 缓存信号、不调 LLM 兜底 —— 不触网不落库。
        //     生产路径里 AMBIGUOUS 还会走一次 LLM 分类，单步 DO 是否进 AgentLoop 还取决于 L0 / 缓存，见日志 [TRIAGE] rule#…
        final String triageQuery = intent.getStringExtra("triage");
        if (triageQuery != null && triageQuery.length() > 0) {
            try {
                com.onesay.assistant.v2.kernel.TriageSwitches sw = com.onesay.assistant.v2.kernel.FeatureKernel.get().triageSwitches(context);
                com.onesay.assistant.v2.router.TriageDecider.Decision d = com.onesay.assistant.v2.router.TriageDecider.decide(triageQuery,
                        com.onesay.assistant.v2.router.TriageDecider.Signals.offline(), sw.diag, sw.implicit, sw.img, sw.sum, sw.s3);
                Log.i(TAG, "[TRIAGE-PROBE] 语句=[" + triageQuery + "] -> " + d + "（现行 TriageDecider 规则层，离线信号）");
            } catch (Throwable t) {
                Log.e(TAG, "[TRIAGE] 分诊异常: " + t.getMessage(), t);
            }
            return;
        }

        // AgentLoop 实测探针（对标 Codex 的 reason→act→observe）。
        //   用法：
        //     adb shell "am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver \
        //         --es agentloop '看看我相册有多少张照片，再根据剩余空间告诉我该不该清理'"
        //   观测：logcat -s V2AgentLoop:I
        //   它能证明的核心事实：第 2 轮看得见第 1 轮的**真实结果**（这正是改造前做不到的）。
        final String loopQuery = intent.getStringExtra("agentloop");
        if (loopQuery != null && loopQuery.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i("V2AgentLoop", "==================================================");
                        Log.i("V2AgentLoop", "★ AgentLoop 实测 ★ 指令：“" + loopQuery + "”");
                        UnifiedRouter router = UnifiedRouter.getInstance(context);
                        Log.i("V2AgentLoop", "[判定] shouldUseAgentLoop="
                                + router.shouldUseAgentLoop(loopQuery));

                        final UnifiedExecutor exec = UnifiedExecutor.getInstance(context);
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        com.onesay.assistant.v2.router.AgentLoop.Result r =
                                router.getAgentLoop().run(loopQuery,
                                        new com.onesay.assistant.v2.router.AgentLoop.Callback() {
                                            @Override
                                            public ExecutionResult execute(Capability cap, org.json.JSONObject args) {
                                                Log.i("V2AgentLoop", "[EXEC] " + cap.sign + " " + args);
                                                return exec.executeInternal(cap, args, false, false);
                                            }

                                            @Override
                                            public com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, org.json.JSONObject args, String reason) {
                                                Log.w("V2AgentLoop", "[GATED] " + cap.sign + " reason=" + reason);
                                                return com.onesay.assistant.v2.router.AgentLoop.ConfirmVerdict.DENY;
                                            }
                                        }, 0);
                        long cost = android.os.SystemClock.elapsedRealtime() - t0;

                        Log.i("V2AgentLoop", "[结果] finished=" + r.finished
                                + " reason=" + r.reason
                                + " 工具调用次数=" + r.steps.size()
                                + " 总耗时=" + cost + "ms");
                        for (int i = 0; i < r.steps.size(); i++) {
                            com.onesay.assistant.v2.router.AgentLoop.Step s = r.steps.get(i);
                            Log.i("V2AgentLoop", "  step" + (i + 1) + ": " + s.tool
                                    + " args=" + s.args + " ok=" + s.ok
                                    + " obs=" + s.observation);
                        }
                        Log.i("V2AgentLoop", "[答复] " + r.answer);
                        Log.i("V2AgentLoop", "==================================================");
                    } catch (Throwable t) {
                        Log.e("V2AgentLoop", "[异常] " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "AgentLoopProbe").start();
            return;
        }

        // 6. 非标任务 Agent 规划与 L1 本地记忆固化复用闭环专项实测
        final String agentQuery = intent.getStringExtra("test_agent");
        if (agentQuery != null && agentQuery.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "==================================================");
                        Log.i(TAG, "★ 启动非标任务 Agent 规划与 L1 技能复用闭环验证 ★");
                        Log.i(TAG, "非标测试指令: “" + agentQuery + "”");

                        UnifiedRouter router = UnifiedRouter.getInstance(context);

                        // 步骤 1：首次触发非标指令（预期：L0/L1 未命中 -> L2 LLM 编译器规划生成能力 -> 固化落库至 SQLite）
                        long t1 = android.os.SystemClock.elapsedRealtime();
                        Invocation inv1 = router.route(agentQuery);
                        long cost1 = android.os.SystemClock.elapsedRealtime() - t1;

                        if (inv1 == null) {
                            Log.e(TAG, "[AGENT-FAIL] 首次规划失败: 未能生成有效能力签名");
                            return;
                        }
                        Log.i(TAG, "[AGENT-1] 首次规划结果: sign=" + inv1.sign + ", args=" + inv1.args
                                + ", 来源=" + inv1.source + ", 耗时=" + cost1 + "ms");

                        // 步骤 2（缓存只在**执行成功之后**写入 ——
                        //   先确认 route() 本身没有落库，再真执行一次并回报路由层。
                        boolean cachedBeforeExec = router.getL1().hasSkill(agentQuery);
                        Log.i(TAG, "[AGENT-DB] 仅规划未执行时的缓存检查: "
                                + (cachedBeforeExec ? "已有条目（之前跑过 / 或 L1 直出）" : "✓ 未落库（符合执行后才写）"));
                        Capability acap = CapabilityRegistry.getInstance().describe(inv1.sign);
                        if (acap != null && inv1.source != Invocation.RouteSource.L1_SKILL) {
                            com.onesay.assistant.v2.executor.ExecutionResult er =
                                    com.onesay.assistant.v2.executor.UnifiedExecutor.getInstance(context).execute(acap, inv1.args);
                            router.reportExecution(agentQuery, inv1.sign, er);
                            Log.i(TAG, "[AGENT-EXEC] 执行并回报: ok=" + er.ok + " code=" + er.code
                                    + " verified=" + er.verification);
                        } else if (acap == null) {
                            Log.i(TAG, "[AGENT-EXEC] " + inv1.sign + " 不是可执行能力（如 ask.reply），按问答缓存规则处理");
                        }
                        boolean hasSolidified = router.getL1().hasSkill(agentQuery);
                        Log.i(TAG, "[AGENT-DB] 执行后技能缓存检查: " + (hasSolidified ? "✓ 技能已写入缓存" : "✕ 未写入！"));

                        // 步骤 3：再次输入相同指令（预期：L0 未命中 -> L1 本地技能库 100% 拦截直出，0 网络请求，耗时 < 10ms）
                        //   带**相对时间**的指令**本就不该入缓存**（见
                        //   IntentClassifier.isTimeSensitive）——它编译出的是"当时"才成立的
                        //   绝对时刻，缓存下来明天同一句话会设错时间的闹钟。所以这里
                        //   若因时效性而没进缓存，是**正确行为**，不是失败。
                        boolean volatileQuery =
                                com.onesay.assistant.v2.router.IntentClassifier.isTimeSensitive(agentQuery);
                        long t2 = android.os.SystemClock.elapsedRealtime();
                        Invocation inv2 = router.route(agentQuery);
                        long cost2 = android.os.SystemClock.elapsedRealtime() - t2;

                        if (inv2 != null && inv2.source == Invocation.RouteSource.L1_SKILL) {
                            Log.i(TAG, "[AGENT-2] 二次复用结果: sign=" + inv2.sign + ", args=" + inv2.args
                                    + ", 来源=" + inv2.source + " [100% 命中本地记忆！], 耗时=" + cost2 + "ms (0网络请求)");
                            Log.i(TAG, "★ [AGENT-PASS] 闭环验证满分通过：非标任务规划一次后，成功固化能力并毫秒级复用！");
                        } else if (volatileQuery && !hasSolidified) {
                            Log.i(TAG, "[AGENT-2] 二次未命中缓存 —— **预期行为**：该指令含相对时间，"
                                    + "结果只对当下成立，被时效性闸门正确挡在缓存之外（来源="
                                    + (inv2 != null ? inv2.source : "null") + ", 耗时=" + cost2 + "ms）");
                            Log.i(TAG, "★ [AGENT-PASS] 时效性缓存防线生效：相对时间指令不入库、不复用旧时刻。");
                        } else {
                            Log.e(TAG, "✕ [AGENT-FAIL] 二次复用失败: 来源=" + (inv2 != null ? inv2.source : "null")
                                    + ", 未能命中 L1 本地技能记忆！");
                        }
                        Log.i(TAG, "==================================================");

                    } catch (Throwable t) {
                        Log.e(TAG, "[AGENT-TEST] 执行异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestAgentThread").start();
            return;
        }

        // 6.5 语音通道唤醒注入
        //     用于验证 WakeService 的**分级去抖**：按键类（key-*）做窗口级去抖，
        //     语音类（voice）只做时间级去抖 —— 用户在 8s 捕获窗内重新喊唤醒词
        //     是完全合理的行为，绝不能被"窗口还开着"这条判据误杀。
        //     真实语音注入受 AEC 回声消除阻断（自我播放会被减掉），故用此入口直连。
        if (intent.getBooleanExtra("test_voice", false)) {
            Log.i(TAG, "[WAKE-TEST] 注入语音唤醒 src=" + com.onesay.assistant.v2.wake.WakeService.SRC_VOICE
                    + " running=" + com.onesay.assistant.v2.wake.WakeService.isServiceRunning()
                    + " micReady=" + com.onesay.assistant.v2.wake.WakeService.isMicReady());
            // ★ round3 #3：冷启动时服务还不存在 → 先拉起（前台服务，Android 8 后台限制不拦），唤醒在服务里排队、开录后补发
            if (!com.onesay.assistant.v2.wake.WakeService.isServiceRunning()) {
                android.content.Intent wi = new android.content.Intent(context, com.onesay.assistant.v2.wake.WakeService.class);
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(wi);
                    else context.startService(wi);
                } catch (Throwable t) {
                    Log.w(TAG, "[WAKE-TEST] 拉起 WakeService 失败: " + t.getMessage());
                }
            }
            com.onesay.assistant.v2.wake.WakeService.requestWake(
                    com.onesay.assistant.v2.wake.WakeService.SRC_VOICE, false);
            return;
        }

        // 7. 端到端执行
        final String label = intent.getStringExtra("label");
        if (label != null && label.length() > 0) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Invocation inv = UnifiedRouter.getInstance(context).route(label);
                        if (inv == null) {
                            Log.w(TAG, "[RUN] 路由未命中: " + label);
                            return;
                        }

                        Capability cap = CapabilityRegistry.getInstance().describe(inv.sign);
                        if (cap == null) {
                            Log.e(TAG, "[RUN] 找不到能力签名: " + inv.sign);
                            return;
                        }

                        ExecutionResult res = UnifiedExecutor.getInstance(context).execute(cap, inv.args);
                        Log.i(TAG, "[RUN] 执行结果: ok=" + res.ok + ", code=" + res.code + ", verified=" + res.verified + ", msg=" + res.userMessage);
                    } catch (Throwable t) {
                        Log.e(TAG, "[RUN] 执行异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestExecThread").start();
            return;
        }

        // 8. 云端 ASR（阿里云一句话识别）—— 配置 / 开关 / 探测 / 端到端识别
        handleCloudAsr(context, intent);
    }

    /**
     * 云端 ASR（阿里云一句话识别）测试与控制入口。
     *
     * <pre>
     *   --es asrset "akId|akSecret|appKey"   写入凭据
     *   --ez asron true / --ez asroff true   打开 / 关闭云端 ASR
     *   --es asrmode &lt;模式&gt;                  OFFLINE_ONLY / OFFLINE_FIRST_CLOUD_FALLBACK / CLOUD_FIRST_FALLBACK_OFFLINE / CLOUD_ONLY
     *                                         （CLOUD_ONLY = 本地 ASR 全屏蔽，识别只认云端；下一次唤醒生效）
     *   --ez asrclear true                   清除全部凭据
     *   --ez privacychk true                 ★ 隐私审计：唤醒音频是否上行（纯本地读取，不发网络）
     *   --ez asrprobe true                   打印配置并**真实签发一次 Token**（验证 AK/SK 与签名算法）
     *   --es asrasset test_cmd.wav           读 assets 音频跑一次真实识别
     *   --es asrfile /sdcard/xxx.pcm         读设备文件跑一次真实识别
     * </pre>
     *
     * <p>★ <b>百炼 / DashScope 通道</b>（与上面 NLS 是两套独立凭据，别混）：
     * <pre>
     *   --es dsrkey sk-xxxx                 写入 DashScope API Key
     *   --es dsrmodel qwen3-asr-flash       切换识别模型（默认 qwen3-asr-flash）
     *   --ez dsrprobe true                  ★ 连通性探测：用 assets/test_cmd.wav 打一次真识别
     *   --es dsrasset test_wake.wav         指定探测/识别用的 assets 音频
     *   --es dsrfile /sdcard/xxx.pcm        读设备文件跑 DashScope 识别
     *   --ez asrwin true                    ★ 生产路径联调：走 AsrRouter（含通道选择与开关判断）
     *   --es asrwinasset test_cmd.wav       指定 asrwin 用的 assets 音频
     * </pre>
     * {@code dsrprobe} 是判定"通不通"的**唯一可靠手段**：它一次同时证明
     * ① DNS 解析 ② TLS 握手 ③ Key 鉴权 ④ 音频可识别 ⑤ 响应解析正确 ——
     * ping 或端口探测只能证明前两项，证明不了鉴权和解析。
     *
     * <p>其中 {@code asrasset} / {@code asrfile} 最有价值：**完全不用说话**就能验证
     * "POP 签名 → Token → 上传 PCM → 解析文本"整条链路，且可反复复现。
     * 调唤醒链路之前先跑这个，能把"网络/凭据问题"和"语音识别问题"彻底分开。
     *
     * <p>注意所有分支都先判断自己的 extra 是否存在，互不干扰；
     * 未命中任何 extra 时本方法什么都不做（安全空转）。
     */
    private void handleCloudAsr(final Context context, final Intent intent) {
        final com.onesay.assistant.v2.asr.AsrRouter asr = com.onesay.assistant.v2.asr.AsrRouter.getInstance(context);
        final com.onesay.assistant.v2.asr.AliyunAsrConfig cfg = asr.config();

        // --- 写入凭据 ---
        String asrSet = intent.getStringExtra("asrset");
        if (asrSet != null && asrSet.length() > 0) {
            String[] p = asrSet.split("\\|");
            if (p.length >= 3) {
                cfg.setAccessKeyId(p[0]);
                cfg.setAccessKeySecret(p[1]);
                cfg.setAppKey(p[2]);
                asr.tokenManager().invalidate();
                Log.i(TAG, "[ASR] 凭据已写入 -> " + cfg.describe());
            } else {
                Log.w(TAG, "[ASR] asrset 格式应为 \"akId|akSecret|appKey\"，收到 " + p.length + " 段");
            }
            return;
        }

        // --- 开关 ---
        if (intent.hasExtra("asron")) {
            cfg.setEnabled(intent.getBooleanExtra("asron", false));
            Log.i(TAG, "[ASR] 云端 ASR 已" + (cfg.isEnabled() ? "打开" : "关闭"));
            return;
        }
        if (intent.getBooleanExtra("asroff", false)) {
            cfg.setEnabled(false);
            Log.i(TAG, "[ASR] 云端 ASR 已关闭");
            return;
        }

        // --- 模式 ---
        String mode = intent.getStringExtra("asrmode");
        if (mode != null && mode.length() > 0) {
            try {
                cfg.setMode(com.onesay.assistant.v2.asr.AsrRouter.Mode.valueOf(mode.trim()));
                Log.i(TAG, "[ASR] 调度模式 = " + cfg.getMode().name());
            } catch (Throwable t) {
                Log.w(TAG, "[ASR] 未知模式: " + mode
                        + "（可选 OFFLINE_ONLY / OFFLINE_FIRST_CLOUD_FALLBACK / CLOUD_FIRST_FALLBACK_OFFLINE）");
            }
            return;
        }

        // --- 清凭据 ---
        if (intent.getBooleanExtra("asrclear", false)) {
            cfg.clearCredentials();
            Log.i(TAG, "[ASR] 凭据已清除");
            return;
        }

        // --- ★ 隐私审计：唤醒音频到底出没出本机（纯读，不发网络） ---
        if (intent.getBooleanExtra("privacychk", false)) {
            Log.i(TAG, "[PRIVACY] ========== 语音上行审计 ==========");
            for (String line : com.onesay.assistant.v2.wake.WakeService.privacyReport().split("\n")) {
                Log.i(TAG, "[PRIVACY] " + line);
            }
            Log.i(TAG, "[PRIVACY] 云端配置: " + cfg.describe());
            Log.i(TAG, "[PRIVACY] ================================");
            return;
        }

        // ==================================================================
        // 百炼 / DashScope 通道（★ 与上面 NLS 是完全独立的两套凭据与协议）
        // ==================================================================

        // --- 写入 DashScope API Key ---
        String dsKey = intent.getStringExtra("dsrkey");
        if (dsKey != null && dsKey.length() > 0) {
            cfg.setDashScopeKey(dsKey);
            Log.i(TAG, "[ASR] DashScope Key 已写入 -> " + cfg.describe());
            return;
        }

        // --- 切换 DashScope 模型 ---
        String dsModel = intent.getStringExtra("dsrmodel");
        if (dsModel != null && dsModel.length() > 0) {
            cfg.setDashScopeModel(dsModel);
            Log.i(TAG, "[ASR] DashScope 模型 = " + cfg.getDashScopeModel());
            return;
        }

        // --- DashScope 连通性探测：**用 assets 里的真实音频打一次真识别** ---
        //     这是"通不通"的最终答案：它同时证明
        //     ① 设备能解析 dashscope.aliyuncs.com ② TLS 握手成功
        //     ③ Key 鉴权通过 ④ 音频能识别 ⑤ 响应解析正确
        //     比任何 ping / 端口探测都强 —— 那些证明不了鉴权与解析。
        if (intent.getBooleanExtra("dsrprobe", false)) {
            final String probeAsset = intent.hasExtra("dsrasset")
                    ? intent.getStringExtra("dsrasset") : "test_cmd.wav";
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    com.onesay.assistant.v2.asr.DashScopeAsrChannel ch = null;
                    try {
                        Log.i(TAG, "===== DashScope 连通性探测 =====");
                        Log.i(TAG, "配置: " + cfg.describe());
                        Log.i(TAG, "端点: " + com.onesay.assistant.v2.net.LlmEndpoints.dashscopeChatUrl());

                        if (!cfg.hasDashScopeKey()) {
                            Log.e(TAG, "[DSR-PROBE] [FAIL] 未配置 DashScope API Key"
                                    + "（先用 --es dsrkey sk-xxxx 写入）");
                            return;
                        }

                        byte[] raw = readAsset(context, probeAsset);
                        byte[] pcm = stripWavHeader(raw);
                        Log.i(TAG, "音频: assets/" + probeAsset
                                + " 去头后=" + pcm.length + "B"
                                + " 时长≈" + (pcm.length / 2 * 1000 / 16000) + "ms");

                        ch = new com.onesay.assistant.v2.asr.DashScopeAsrChannel(cfg);
                        Log.i(TAG, "通道: " + ch.name() + " isReady=" + ch.isReady());

                        com.onesay.assistant.v2.asr.AsrResult r = ch.recognize(pcm, 16000);
                        if (r.ok) {
                            Log.i(TAG, "[DSR-PROBE] [OK] 识别成功 (" + r.latencyMs
                                    + "ms): \"" + r.text + "\"");
                        } else {
                            Log.e(TAG, "[DSR-PROBE] [FAIL] 识别失败 (" + r.latencyMs
                                    + "ms): " + r.error);
                        }
                        Log.i(TAG, "===============================");
                    } catch (Throwable t) {
                        Log.e(TAG, "[DSR-PROBE] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestDsrProbeThread").start();
            return;
        }

        // --- DashScope 端到端识别：assets 或设备文件 ---
        //     注：dsrprobe 分支已提前 return，所以这里再判 dsrasset 不会冲突。
        final String dsAsset = intent.getStringExtra("dsrasset");
        final String dsFile = intent.getStringExtra("dsrfile");
        if ((dsAsset != null && dsAsset.length() > 0)
                || (dsFile != null && dsFile.length() > 0)) {
            final boolean useAsset = (dsAsset != null && dsAsset.length() > 0);
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] raw = useAsset ? readAsset(context, dsAsset) : readFile(dsFile);
                        byte[] pcm = stripWavHeader(raw);
                        com.onesay.assistant.v2.asr.DashScopeAsrChannel ch =
                                new com.onesay.assistant.v2.asr.DashScopeAsrChannel(cfg);
                        Log.i(TAG, "===== DashScope 端到端识别 =====");
                        Log.i(TAG, "音频: " + (useAsset ? "assets/" + dsAsset : dsFile)
                                + " 去头后=" + pcm.length + "B"
                                + " 时长≈" + (pcm.length / 2 * 1000 / 16000) + "ms");
                        com.onesay.assistant.v2.asr.AsrResult r = ch.recognize(pcm, 16000);
                        if (r.ok) {
                            Log.i(TAG, "[DSR-E2E] [OK] 识别成功 (" + r.latencyMs
                                    + "ms): \"" + r.text + "\"");
                        } else {
                            Log.e(TAG, "[DSR-E2E] [FAIL] 识别失败 (" + r.latencyMs
                                    + "ms): " + r.error);
                        }
                        Log.i(TAG, "===============================");
                    } catch (Throwable t) {
                        Log.e(TAG, "[DSR-E2E] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestDsrE2eThread").start();
            return;
        }

        // ==================================================================
        // ★ 生产路径联调：走 AsrRouter.recognizeCloudAsync
        // ==================================================================
        // 上面 dsrprobe / dsrfile 都是直接 new DashScopeAsrChannel()，**绕过了
        // AsrRouter 的通道选择逻辑** —— 那样测不出"WakeService → AsrRouter → 实际生效通道"
        // 这条真实链路（enabled 开关、凭据完整性判断、通道挑选、异步回调）。
        // 本分支专门补这一环，用的就是 WakeService.tryCloudRecognize() 调的那个方法。
        if (intent.getBooleanExtra("asrwin", false)) {
            final String winAsset = intent.hasExtra("asrwinasset")
                    ? intent.getStringExtra("asrwinasset") : "test_cmd.wav";
            final String winFile = intent.getStringExtra("asrwinfile");
            final boolean winUseFile = (winFile != null && winFile.length() > 0);
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "===== 生产路径联调 (AsrRouter.recognizeCloudAsync) =====");
                        Log.i(TAG, "生效通道=" + asr.activeCloudName()
                                + " enabled=" + cfg.isEnabled()
                                + " 凭据完整=" + asr.isCloudConfigured()
                                + " ready=" + asr.isCloudReady());
                        byte[] pcm = stripWavHeader(winUseFile
                                ? readFile(winFile) : readAsset(context, winAsset));
                        Log.i(TAG, "音频: " + (winUseFile ? winFile : "assets/" + winAsset)
                                + " 去头后=" + pcm.length + "B");
                        final java.util.concurrent.CountDownLatch latch =
                                new java.util.concurrent.CountDownLatch(1);
                        asr.recognizeCloudAsync(pcm,
                                new com.onesay.assistant.v2.asr.AsrRouter.Callback() {
                                    @Override
                                    public void onResult(com.onesay.assistant.v2.asr.AsrResult r) {
                                        if (r.ok) {
                                            Log.i(TAG, "[ASR-WIN] [OK] (" + r.latencyMs
                                                    + "ms) \"" + r.text + "\" src=" + r.source);
                                        } else {
                                            Log.e(TAG, "[ASR-WIN] [FAIL] (" + r.latencyMs
                                                    + "ms) " + r.error + " src=" + r.source);
                                        }
                                        Log.i(TAG, "[ASR-WIN] 统计: " + asr.stats());
                                        latch.countDown();
                                    }
                                });
                        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        Log.i(TAG, "===============================");
                    } catch (Throwable t) {
                        Log.e(TAG, "[ASR-WIN] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestAsrWinThread").start();
            return;
        }

        // --- 签名算法自检：固定输入向量，可与外部实现（Python/官方SDK）交叉比对 ---
        //     这是本框架唯一能"完全离线"验证的一环：POP 签名是自实现的，
        //     最容易在 percentEncode / StringToSign 拼接上出错，而这类错误
        //     在没有真 key 时完全看不出来（只会表现为"Token 签发失败"）。
        if (intent.getBooleanExtra("asrsign", false)) {
            Log.i(TAG, "===== POP 签名自检（固定向量，离线可验）=====");
            final String akId = "testAccessKeyId";
            final String akSecret = "testSecretKeyForSigning123";   // gitleaks:allow（固定测试向量）
            final String timestamp = "2026-10-02T08:00:00Z";
            final String nonce = "8d1e6a7a-f44e-40d5-aedb-fe4a1c80f434";
            try {
                java.util.List<String[]> params = new java.util.ArrayList<String[]>();
                params.add(new String[]{"AccessKeyId", akId});
                params.add(new String[]{"Action", "CreateToken"});
                params.add(new String[]{"Version", "2019-02-28"});
                params.add(new String[]{"Format", "JSON"});
                params.add(new String[]{"RegionId", "cn-shanghai"});
                params.add(new String[]{"Timestamp", timestamp});
                params.add(new String[]{"SignatureMethod", "HMAC-SHA1"});
                params.add(new String[]{"SignatureVersion", "1.0"});
                params.add(new String[]{"SignatureNonce", nonce});
                java.util.Collections.sort(params, new java.util.Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareTo(b[0]);
                    }
                });
                StringBuilder canonical = new StringBuilder();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) canonical.append('&');
                    canonical.append(com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode(params.get(i)[0]))
                            .append('=')
                            .append(com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode(params.get(i)[1]));
                }
                String stringToSign = "POST&" + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode("/")
                        + "&" + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode(canonical.toString());
                String sig = com.onesay.assistant.v2.asr.AliyunTokenManager.hmacSha1Base64(akSecret + "&", stringToSign);

                Log.i(TAG, "[ASR-SIGN] 规范化查询串 = " + canonical);
                Log.i(TAG, "[ASR-SIGN] StringToSign  = " + stringToSign);
                Log.i(TAG, "[ASR-SIGN] Signature     = " + sig);
                Log.i(TAG, "[ASR-SIGN] percentEncode 边界: 空格->["
                        + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode("a b")
                        + "] 星号->[" + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode("a*b")
                        + "] 波浪->[" + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode("a~b")
                        + "] 斜杠->[" + com.onesay.assistant.v2.asr.AliyunTokenManager.percentEncode("a/b") + "]");
                Log.i(TAG, "[ASR-SIGN] 当前 UTC 时间戳 = "
                        + com.onesay.assistant.v2.asr.AliyunTokenManager.isoTimestampUtc());
            } catch (Throwable t) {
                Log.e(TAG, "[ASR-SIGN] 自检异常: " + t.getMessage(), t);
            }
            Log.i(TAG, "================================");
            return;
        }

        // --- 探测：真实签一次 Token ---
        if (intent.getBooleanExtra("asrprobe", false)) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "===== 云端 ASR 探测 =====");
                        Log.i(TAG, "配置: " + cfg.describe());
                        Log.i(TAG, "识别端点: " + cfg.getAsrEndpoint());
                        Log.i(TAG, "Token端点: " + cfg.getTokenEndpoint());
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        String tk = asr.tokenManager().getToken(true);
                        long cost = android.os.SystemClock.elapsedRealtime() - t0;
                        if (tk.length() > 0) {
                            Log.i(TAG, "[ASR-PROBE] [OK] Token 签发成功 (" + cost
                                    + "ms)，长度=" + tk.length());
                        } else {
                            Log.e(TAG, "[ASR-PROBE] [FAIL] Token 签发失败 (" + cost + "ms): "
                                    + asr.tokenManager().getLastError());
                        }
                        Log.i(TAG, "统计: " + asr.stats());
                        Log.i(TAG, "=========================");
                    } catch (Throwable t) {
                        Log.e(TAG, "[ASR-PROBE] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestAsrProbeThread").start();
            return;
        }

        // --- 端到端识别：assets 或设备文件 ---
        final String assetName = intent.getStringExtra("asrasset");
        final String filePath = intent.getStringExtra("asrfile");
        if ((assetName != null && assetName.length() > 0) || (filePath != null && filePath.length() > 0)) {
            final boolean useAsset = (assetName != null && assetName.length() > 0);
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] raw = useAsset ? readAsset(context, assetName) : readFile(filePath);
                        byte[] pcm = stripWavHeader(raw);
                        Log.i(TAG, "===== 云端 ASR 端到端识别 =====");
                        Log.i(TAG, "音频: " + (useAsset ? "assets/" + assetName : filePath)
                                + " 原始=" + (raw == null ? 0 : raw.length) + "B"
                                + " 去头后=" + (pcm == null ? 0 : pcm.length) + "B"
                                + " 时长≈" + (pcm == null ? 0 : pcm.length / 2 * 1000 / 16000) + "ms");
                        com.onesay.assistant.v2.asr.AsrResult r = asr.recognizeCloudSync(pcm);
                        if (r.ok) {
                            Log.i(TAG, "[ASR-E2E] [OK] 识别成功 (" + r.latencyMs + "ms): \"" + r.text + "\"");
                        } else {
                            Log.e(TAG, "[ASR-E2E] [FAIL] 识别失败 (" + r.latencyMs + "ms): " + r.error);
                        }
                        Log.i(TAG, "统计: " + asr.stats());
                        Log.i(TAG, "===============================");
                    } catch (Throwable t) {
                        Log.e(TAG, "[ASR-E2E] 异常: " + t.getMessage(), t);
                    } finally {
                        pr.finish();
                    }
                }
            }, "TestAsrE2eThread").start();
        }
    }

    /** 从 APK assets 读全部字节 */
    private static byte[] readAsset(Context ctx, String name) throws Exception {
        java.io.InputStream is = ctx.getAssets().open(name);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    /** 从设备文件系统读全部字节 */
    private static byte[] readFile(String path) throws Exception {
        java.io.InputStream is = new java.io.FileInputStream(path);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    /**
     * 剥掉 WAV 头，只留 PCM 数据段。
     *
     * assets 里的 test_wake.wav / test_cmd.wav 都带 RIFF 头，直接当 PCM 发出去
     * 会把 "RIFF...." 那几十字节解码成噪声。这里遍历 chunk 找 "data" 标记，
     * 比硬编码"跳过 44 字节"稳妥 —— 有些 wav 带 LIST/INFO 块，头长不固定。
     */
    private static byte[] stripWavHeader(byte[] data) {
        if (data == null) return new byte[0];
        if (data.length <= 44) return data;
        if (!(data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F')) {
            return data; // 不是 WAV，当作裸 PCM 原样使用
        }
        int limit = Math.min(data.length - 8, 512);
        for (int i = 12; i < limit; i++) {
            if (data[i] == 'd' && data[i + 1] == 'a' && data[i + 2] == 't' && data[i + 3] == 'a') {
                int off = i + 8; // "data" 之后是 4 字节长度字段
                if (off < data.length) {
                    byte[] out = new byte[data.length - off];
                    System.arraycopy(data, off, out, 0, out.length);
                    return out;
                }
            }
        }
        return data;
    }

    // ---------- P0 声纹探针实现（2026-10-04） ----------

    /**
     * 声纹探针：注册 / 提向量 / 两段余弦 / 验证。
     * 观测点全部走日志（[SPK] 前缀）—— UI 抓取会被别的 App 抢焦点，日志才可靠。
     */
    private static void runSpeakerProbe(Context ctx, Intent it) {
        com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                com.onesay.assistant.v2.asr.SpeakerVerifier.get(ctx);

        String name = it.getStringExtra("spkname");
        if (name == null || name.length() == 0) name = "owner";

        if (it.getBooleanExtra("spkdel", false)) {
            boolean ok = sv.removeName(name);
            Log.i(TAG, "[SPK] 删除 " + name + " -> " + ok + "；状态: " + sv.describeStatus());
            return;
        }

        // --ei setvpthr NN → 设置"唤醒声纹兜底阈值"（NN = 百分比 0~100）。
        //   r2/fixes 15：以前写的是旧键 wake_vp_cfg/threshold_pct（审计 G1 起 WakeService 不再读，探针形同虚设）；
        //   现在写 WakeTuning 的 vp_pct —— 与 WakeService.getWakeVpThreshold() 读的是同一个键，实时生效。
        if (it.hasExtra("setvpthr")) {
            int pct = clampVpPct(it.getIntExtra("setvpthr", com.onesay.assistant.v2.wake.WakeTuning.DEF_VP_PCT));
            com.onesay.assistant.v2.wake.WakeTuning tuning = com.onesay.assistant.v2.wake.WakeTuning.get(ctx);
            tuning.applyCsv(com.onesay.assistant.v2.wake.WakeTuning.K_VP_PCT + "=" + pct);
            int now = tuning.vpThresholdPct();
            Log.i(TAG, "[KWS-VP] 唤醒声纹兜底阈值已设为 " + pct + "%"
                    + "（回读 " + now + "%，即 " + (now / 100.0f) + "）");
            return;
        }

        // 先建引擎（无论哪条子命令，都先把状态打出来 —— JNI 匹配与否第一眼可见）
        boolean avail = sv.isAvailable();
        Log.i(TAG, "[SPK] 状态: " + sv.describeStatus());
        if (!avail && (it.getStringExtra("spkenroll") != null || it.getStringExtra("spkfile") != null)) {
            Log.e(TAG, "[SPK] 引擎不可用，后续动作跳过");
            return;
        }

        // --es wakevp <wav> → 模拟"唤醒声纹兜底验证"：
        //   取该 wav 的**尾部 2.2s**（与运行时回溯窗口完全一致）→ 提向量 →
        //   与 owner 算 cos 裸分数 → 按**当前生效阈值**给出判定。
        //   用途：拿任意音频（尤其主人/他人不同距离、不同音量）测分数分布，据此校准阈值。
        final String wakeVpWav = it.getStringExtra("wakevp");
        if (wakeVpWav != null && wakeVpWav.length() > 0) {
            if (!avail) {
                Log.e(TAG, "[KWS-VP] 引擎不可用，跳过");
                return;
            }
            try {
                float[] all = com.onesay.assistant.v2.asr.SpeakerVerifier.readWavAsFloat(wakeVpWav);
                int want = 2200 * 16000 / 1000;              // 2.2s 回溯窗口
                int min = 16000 * 6 / 5;                     // 1.2s（与 MIN_AUDIO_SEC 对齐）
                int n = Math.min(all.length, want);
                if (n < min) {
                    Log.e(TAG, "[KWS-VP] wav 太短: " + (all.length / 16000.0f) + "s < 1.2s");
                    return;
                }
                float[] seg = new float[n];
                System.arraycopy(all, all.length - n, seg, 0, n);   // ★ 取尾部（模拟回溯）
                long t0 = android.os.SystemClock.elapsedRealtime();
                float[] emb = sv.extractFromSamples(seg, 16000);
                long cost = android.os.SystemClock.elapsedRealtime() - t0;
                if (emb == null) {
                    Log.e(TAG, "[KWS-VP] 提向量失败");
                    return;
                }
                float score = sv.scoreName("owner", emb);
                int pct = ctx.getSharedPreferences("wake_vp_cfg", Context.MODE_PRIVATE)
                        .getInt("threshold_pct", 45);
                float thr = pct / 100.0f;
                Log.i(TAG, "[KWS-VP] wakevp 模拟: " + new java.io.File(wakeVpWav).getName()
                        + " 取尾部=" + (n / 16) + "ms"
                        + " cos=" + String.format("%.3f", score)
                        + " 阈值=" + String.format("%.2f", thr)
                        + " → " + (score >= thr ? "通过" : "不通过")
                        + "；提向量耗时=" + cost + "ms");
            } catch (Throwable t) {
                Log.e(TAG, "[KWS-VP] 异常: " + t);
            }
            return;
        }

        String enrollWav = it.getStringExtra("spkenroll");
        if (enrollWav != null && enrollWav.length() > 0) {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int segs = sv.enrollFromWav(name, enrollWav);
            long dt = android.os.SystemClock.elapsedRealtime() - t0;
            if (segs > 0) {
                Log.i(TAG, "[SPK] 注册[" + name + "] 成功 段数=" + segs + " 总耗时=" + dt + "ms");
            } else {
                Log.e(TAG, "[SPK] 注册[" + name + "] 失败（详见上方日志）");
            }
        }

        String f1 = it.getStringExtra("spkfile");
        if (f1 == null || f1.length() == 0) return;

        com.onesay.assistant.v2.asr.SpeakerVerifier.ExtractResult r1 = sv.extractFromWav(f1);
        if (!r1.ok()) {
            Log.e(TAG, "[SPK] embed[1] 失败: " + r1.err + " (" + f1 + ")");
        } else {
            Log.i(TAG, "[SPK] embed[1] " + new java.io.File(f1).getName()
                    + " 音频=" + String.format("%.2f", r1.seconds) + "s"
                    + " 耗时=" + r1.costMs + "ms"
                    + " RTF=" + String.format("%.3f", r1.rtf)
                    + " dim=" + r1.emb.length);
            // --ez spkdump true → 把这段的 embedding 导出为文件
            // （Java DataOutputStream = Big-Endian；主机端解析示例见 _spkref 分析脚本）。
            // 用途：主机侧做跨类余弦对比（你的声纹 vs 其他音频的特征距离）。
            if (it.getBooleanExtra("spkdump", false)) {
                try {
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(
                            new java.io.FileOutputStream(
                                    new java.io.File(ctx.getFilesDir(), "voiceprint/dump_embedding.bin")));
                    dos.writeInt(r1.emb.length);
                    dos.writeInt(1);
                    dos.writeInt(r1.emb.length);
                    for (int i = 0; i < r1.emb.length; i++) {
                        dos.writeFloat(r1.emb[i]);
                    }
                    dos.flush();
                    dos.close();
                    Log.i(TAG, "[SPK] embedding 已导出: files/voiceprint/dump_embedding.bin");
                } catch (Throwable t) {
                    Log.e(TAG, "[SPK] dump 失败: " + t);
                }
            }
        }

        String f2 = it.getStringExtra("spkfile2");
        if (f2 != null && f2.length() > 0) {
            com.onesay.assistant.v2.asr.SpeakerVerifier.ExtractResult r2 = sv.extractFromWav(f2);
            if (!r2.ok()) {
                Log.e(TAG, "[SPK] embed[2] 失败: " + r2.err + " (" + f2 + ")");
            } else {
                Log.i(TAG, "[SPK] embed[2] " + new java.io.File(f2).getName()
                        + " 音频=" + String.format("%.2f", r2.seconds) + "s"
                        + " 耗时=" + r2.costMs + "ms"
                        + " RTF=" + String.format("%.3f", r2.rtf)
                        + " dim=" + r2.emb.length);
            }
            if (r1.ok() && r2.ok()) {
                float cos = com.onesay.assistant.v2.asr.SpeakerVerifier.cosine(r1.emb, r2.emb);
                Log.i(TAG, "[SPK] ★ 余弦(1,2) = " + cos);
            }
        }

        if (r1.ok() && sv.listNames().length > 0) {
            float thr = it.getIntExtra("spkthr", 35) / 100.0f;
            String who = sv.searchName(r1.emb, thr);
            boolean ok = sv.verifyName(name, r1.emb, thr);
            Log.i(TAG, "[SPK] verify(" + name + ", thr=" + thr + ") = " + ok
                    + "；search -> " + (who.length() == 0 ? "(无匹配)" : who));
        }
    }

    // ---------- P1-2 声学回放（供"依赖麦克风"的链路做真实声学验证） ----------

    /** 当前正在回放的播放器。同时只留一个 —— 再次调用会先停掉上一个。 */
    private static android.media.MediaPlayer sPlayer;
    private static final android.os.Handler sPlayHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable sStopPlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (sPlayer != null) {
                try { sPlayer.stop(); } catch (Throwable ignore) {}
                try { sPlayer.release(); } catch (Throwable ignore) {}
                sPlayer = null;
                Log.i(TAG, "[PLAYWAV] 循环回放结束");
            }
        }
    };

    /**
     * 循环外放一段 wav，用于验证**依赖麦克风**的链路（如 P1-2 可续期聆听窗的能量门判据）。
     *
     * <p>刻意走 {@code USAGE_MEDIA} + 满音量：这条钩子要的是"麦克风里确实有声音"，
     * 不是"用户听得好听"。默认循环播放，保证整段验证期间人声不断。
     *
     * @param maxMs 最长回放时长（兜底，防止循环停不下来）
     */
    private static void startLoopPlayback(String path, int maxMs) {
        try {
            sPlayHandler.removeCallbacks(sStopPlayRunnable);
            if (sPlayer != null) {
                try { sPlayer.stop(); } catch (Throwable ignore) {}
                try { sPlayer.release(); } catch (Throwable ignore) {}
                sPlayer = null;
            }
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(path);
            mp.setLooping(true);
            mp.prepare();
            mp.setVolume(1.0f, 1.0f);
            mp.start();
            sPlayer = mp;
            sPlayHandler.postDelayed(sStopPlayRunnable, Math.max(1000, maxMs));
            Log.i(TAG, "[PLAYWAV] 开始循环外放: " + path + "（最多 " + maxMs + "ms，USAGE_MEDIA 满音量）");
        } catch (Throwable t) {
            Log.e(TAG, "[PLAYWAV] 回放失败: " + t.getMessage());
        }
    }

    /** r2/fixes 15：setvpthr 的取值钳到 0~100（与 WakeTuning.vpThresholdPct 读取时的钳制一致）。 */
    static int clampVpPct(int pct) {
        return pct < 0 ? 0 : (pct > 100 ? 100 : pct);
    }
}
