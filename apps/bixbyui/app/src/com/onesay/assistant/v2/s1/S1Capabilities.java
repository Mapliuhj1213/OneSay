package com.onesay.assistant.v2.s1;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.vision.screenref.ActionMapper;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver;
import com.onesay.assistant.v2.vision.screenref.ScreenAction;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;

import org.json.JSONObject;

/**
 * [s1] 剪贴板芯片的动作映射 + S1 自带的一条能力（纯 Java，JVM 可测）。
 *
 * <p>与识屏能力一样<b>不注册进 CapabilityRegistry</b>（避免改道老指令、BuiltinCapabilities 快照不变）；
 * 芯片点击后由 {@code S1Hooks} 拿 {@link Capability} 对象交给 {@code FloatingSheetService.screenRefExecute}，
 * 走 ExecutionGate → 确认卡 → UnifiedExecutor 的同一条链路。
 *
 * <h3>{@link #SIGN_TAXI}（打车）</h3>
 * 先试高德的打车深链 {@code amapuri://drive/takeTaxi?...&dname=…}，设备上没有高德（am start 报 Error）时退回 geo: 地图搜索。
 * <b>深链参数未经真机核实</b>：高德文档里打车页通常还要终点坐标，只给 dname 时可能只打开打车首页、终点需用户自己点选 ——
 * 列为真机验收项 S1-CL-5。地址处于 URL 查询值位置，由 ShellCommand 做百分号编码（{@code &}、引号、{@code $()} 都逃不出去）。
 * 副作用 DEVICE_CONTROL、risk 0：与 screen.nav.to 同类（拉起界面），未污染时直接放行。
 */
public final class S1Capabilities {

    private S1Capabilities() {}

    public static final String SIGN_TAXI = "s1.taxi.to";

    private static volatile Capability sTaxi;

    public static Capability taxi() {
        Capability c = sTaxi;
        if (c != null) return c;
        synchronized (S1Capabilities.class) {
            if (sTaxi == null) {
                sTaxi = new Capability.Builder(SIGN_TAXI, "打车去复制的地址（优先高德打车，无高德时用系统地图搜索该地址）")
                        .domain("s1").category("map")
                        .verbs("打车").entities("address")
                        .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                        .executor("shell")
                        .schema(new ParamsSchema().addString("address", true, null))
                        .cmd("if am start -a android.intent.action.VIEW -d 'amapuri://drive/takeTaxi?sourceApplication=bixbyui&dname={address}&dev=0' 2>&1 | grep -q Error; then "
                                + "am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}' >/dev/null 2>&1; fi; true")
                        .reply("正在打开打车：{address}")
                        .build();
            }
            return sTaxi;
        }
    }

    /** 芯片 → 要执行的能力签名与参数。打车返回 {@link #SIGN_TAXI}，其余复用识屏的 ActionMapper（同一套参数校验/URL 白名单）。 */
    public static ScreenAction actionFor(ClipChipPlanner.Chip chip) {
        if (chip == null || chip.entity == null) return null;
        switch (chip.kind) {
            case NAV:
                return ActionMapper.map(DeixisResolver.Action.NAVIGATE, chip.entity);
            case CALL:
                return ActionMapper.map(DeixisResolver.Action.CALL, chip.entity);
            case OPEN_URL:
                return ActionMapper.map(DeixisResolver.Action.OPEN_URL, chip.entity);
            case TRACK:
                return ActionMapper.map(DeixisResolver.Action.TRACK, chip.entity);
            case TAXI: {
                JSONObject a = new JSONObject();
                try {
                    a.put("address", chip.entity.value);
                } catch (Exception e) {
                    return null;
                }
                return new ScreenAction(DeixisResolver.Action.NAVIGATE, SIGN_TAXI, a, "打车去 " + chip.entity.shortDisplay(12), chip.entity);
            }
            default:
                return null;
        }
    }

    /** 签名 → 能力对象（S1 自带 / 识屏目录）；其余（phone.call、browser.search）由调用方查注册表。 */
    public static Capability localCapability(String sign) {
        if (SIGN_TAXI.equals(sign)) return taxi();
        return ScreenRefCapabilities.get(sign);
    }
}
