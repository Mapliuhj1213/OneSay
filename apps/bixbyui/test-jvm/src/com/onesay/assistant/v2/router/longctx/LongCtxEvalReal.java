package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Kind;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Q;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Scenario;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/1123-longctx-phone：<b>可选</b>的真模型评测（不是单测，名字不以 Test 结尾，run_unit_tests.sh 不会跑；本分支从没跑过）。
 *
 * <p>同一份评测集，改前做法（总开关关）和改后做法（开）各跑一遍，请求发给真实的 OpenAI 兼容端点。
 * Key 只从环境变量读，代码和日志里都不出现；不设就直接退出。判分用宽松规则（真模型答复不是固定句子）：
 * 偏题 = 答复和本题题面的内容二字组覆盖率低于和某个旧题的；复述 = ReplyCheck 判 ECHO；遗忘 = 没说出标准值；人设 = 出现啥 / 咋 / 呗；技能 = 出行题末尾有【出行清单】。
 *
 * <pre>
 *   # 先 BIXBY_TEST_COMPILE_ONLY=1 BIXBY_TEST_BUILD=/tmp/x bash run_unit_tests.sh 编好，再：
 *   ONESAY_EVAL_URL=https://…/chat/completions ONESAY_EVAL_KEY=… ONESAY_EVAL_MODEL=deepseek-chat \
 *   java -cp "/tmp/x/test:/tmp/x/prod:/tmp/x/shims:$LIBS/android-json-…jar:$LIBS/icu4j-72.1.jar:$JUNIT:$PLATFORM" \
 *        com.onesay.assistant.v2.router.longctx.LongCtxEvalReal [场景数上限]
 * </pre>
 * 会真的花 token（6 个场景约 400 次请求）。
 */
public final class LongCtxEvalReal {

    public static void main(String[] args) throws Exception {
        final String url = System.getenv("ONESAY_EVAL_URL"), key = System.getenv("ONESAY_EVAL_KEY"), model = System.getenv("ONESAY_EVAL_MODEL");
        if (url == null || key == null || model == null || key.length() == 0) {
            System.err.println("需要环境变量 ONESAY_EVAL_URL / ONESAY_EVAL_KEY / ONESAY_EVAL_MODEL（本脚本不带任何 Key）");
            System.exit(2);
        }
        int max = args.length > 0 ? Integer.parseInt(args[0]) : 99;
        for (boolean on : new boolean[]{false, true}) {
            LongCtxEvalNewDriver d = new LongCtxEvalNewDriver(on);
            int judged = 0, off = 0, echo = 0, recall = 0, forgot = 0, persona = 0, trip = 0, tripOk = 0;
            int n = 0;
            for (Scenario s : LongCtxEvalData.all()) {
                if (n++ >= max) break;
                long[] clock = {1000L};
                d.begin(LongCtxEvalTest.EVAL_BUDGET, clock, LongCtxEval.skills());
                List<Q> prior = new ArrayList<Q>();
                for (Q q : s.turns) {
                    clock[0] += 10_000L;
                    if (q.kind == Kind.HEAVY) {
                        d.heavy(q.text, q.receipt);
                        continue;
                    }
                    FakeLongCtxModel relay = new FakeLongCtxModel() {
                        @Override public String answer(String body) {
                            return post(url, key, model, body);
                        }
                    };
                    String a = d.ask(q.text, LongCtxEval.SYSTEM, relay);
                    if (a == null) a = "";
                    if (ReplyCheck.check(q.text, a, null).verdict == ReplyCheck.Verdict.ECHO) echo++;
                    if (a.contains("啥") || a.contains("咋") || a.contains("呗")) persona++;
                    if (q.kind == Kind.RECALL) {
                        recall++;
                        if (!a.contains(q.value)) forgot++;
                    } else {
                        judged++;
                        double mine = TextSim.containment(TextSim.content(q.text), TextSim.grams(a));
                        for (Q p : prior) {
                            if (p.kind == Kind.RECALL || p.kind == Kind.HEAVY || p == q) continue;
                            if (TextSim.containment(TextSim.content(p.text), TextSim.grams(a)) >= mine + 0.3) {
                                off++;
                                break;
                            }
                        }
                        if (q.kind == Kind.TRIP) {
                            trip++;
                            if (a.contains(FakeLongCtxModel.SKILL_MARK)) tripOk++;
                        }
                    }
                    prior.add(q);
                }
            }
            System.out.printf("%s：偏题 %d/%d 复述 %d/%d 遗忘 %d/%d 人设 %d/%d 技能 %d/%d 自检重答 %d%n", d.name(), off, judged, echo, judged + recall,
                    forgot, recall, persona, judged + recall, tripOk, trip, d.retries());
        }
    }

    static String post(String url, String key, String model, String body) {
        try {
            JSONObject b = new JSONObject(body);
            b.put("model", model);
            b.remove("thinking");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            OutputStream o = c.getOutputStream();
            o.write(b.toString().getBytes("UTF-8"));
            o.close();
            InputStream in = c.getResponseCode() < 300 ? c.getInputStream() : c.getErrorStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            in.close();
            JSONObject res = new JSONObject(bo.toString("UTF-8"));
            return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        } catch (Exception e) {
            System.err.println("请求失败：" + e.getClass().getSimpleName());
            return null;
        }
    }
}
