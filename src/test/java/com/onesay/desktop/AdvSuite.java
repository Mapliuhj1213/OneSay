package com.onesay.desktop;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * [adv-1114] 找茬式对抗测试的门禁：全部对抗用例，<b>不含</b> {@link AdvKnownBugsTest}（已确认的真 bug，预期失败，单独跑）。
 * 运行：{@code TEST_LIBS=… sh src/test/adv.sh}（gate）/ {@code … adv.sh bugs} / {@code … adv.sh all}（原有 159 条 + 门禁）。
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({AdvElevationTest.class, AdvModeMatrixTest.class, AdvNetTest.class, AdvUrlTest.class, AdvConfirmGateTest.class, AdvConfirmFlowTest.class,
        AdvSelfAuthTest.class, AdvPathTest.class, AdvShortcutTest.class, AdvMediaTest.class, AdvProtocolTest.class, AdvScriptGenTest.class, AdvConfigTest.class})
public class AdvSuite { }
