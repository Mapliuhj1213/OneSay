package com.onesay.desktop;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * [adv-1114] 找茬式对抗测试的门禁：全部对抗用例，含 {@link AdvFixedBugsTest}（原 AdvKnownBugsTest 的 71 条，2026-11-14 修好后挪回）和 {@link AdvFix1114Test}（这次修复的新用例）。
 * 运行：{@code TEST_LIBS=… sh src/test/adv.sh}（gate）/ {@code … adv.sh bugs} / {@code … adv.sh all}（原有 159 条 + 门禁）。
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({AdvElevationTest.class, AdvModeMatrixTest.class, AdvNetTest.class, AdvUrlTest.class, AdvConfirmGateTest.class, AdvConfirmFlowTest.class,
        AdvSelfAuthTest.class, AdvPathTest.class, AdvShortcutTest.class, AdvMediaTest.class, AdvProtocolTest.class, AdvScriptGenTest.class, AdvConfigTest.class,
        AdvFixedBugsTest.class, AdvFix1114Test.class})
public class AdvSuite { }
