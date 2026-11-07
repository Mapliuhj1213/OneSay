package com.onesay.assistant.v2.capability;

/**
 * 副作用分类（风险元数据）。
 * 用于定义操作的安全边界，由 ToolGate 在执行前进行确定性拦截和校验。
 */
public enum SideEffect {
    /** 无副作用：如查询时间、电量、读取状态等只读且不侵犯隐私的操作 */
    NONE,
    
    /** 隐私数据读取：如读取通讯录、读取通知、读取相册等 */
    PRIVATE_DATA_READ,
    
    /** 设备控制：如开关 WiFi、调亮度、打开应用等设备级非破坏性修改 */
    DEVICE_CONTROL,
    
    /** 持久化写入：如新建备忘录、设置闹钟。此类操作可被持久化投毒，如来源不可信需强制确认 */
    PERSISTENT_WRITE,
    
    /** 公共通信：如发短信、打电话、发送网络请求等 */
    PUBLIC_COMMS,
    
    /** 破坏性操作：如卸载应用、删除文件、清空缓存等 */
    DESTRUCTIVE,
    
    /** ROOT 级最高权限：不可降级，任何自动路径都不得绕过确认卡，污染来源直接拒绝 */
    ROOT
}
