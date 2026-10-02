package io.wristdeck.net

/* 把底层网络异常压缩成一句短中文。
 *
 * 为什么需要它：连接失败的 detail 会被拼进状态条（"未连接 (...)"），
 * 而 OkHttp/Java 抛出来的 message 是英文堆栈片段，例如
 *   CLEARTEXT communication to 192.168.1.5 not permitted by network security policy
 * 手表屏幕小、用户也没法据此排查。这里统一翻译成一句话。
 *
 * 认不出的异常返回 null —— 宁可只显示"未连接"，也不要把英文原文糊到脸上；
 * 原始 message 由调用方写进 logcat，排查时看日志。
 */
object NetError {

    fun describe(raw: String?): String? {
        val m = raw?.lowercase() ?: return null
        return when {
            m.contains("cleartext") || m.contains("network security policy") ->
                "明文连接被系统拦截"

            m.contains("econnrefused") || m.contains("connection refused") ->
                "PC 未监听该端口"

            m.contains("ehostunreach") || m.contains("enetunreach") ||
                m.contains("no route to host") -> "网络不可达，检查是否同网段"

            m.contains("etimedout") || m.contains("timeout") ||
                m.contains("failed to connect") -> "连接超时"

            m.contains("unknownhost") || m.contains("unable to resolve host") ->
                "IP 地址无法解析"

            m.contains("socket closed") || m.contains("connection reset") ||
                m.contains("broken pipe") -> "连接被中断"

            m.contains("no_host") -> "未填写 IP"

            /* ---- BLE（BleTransport 抛出的 token）----
             * BLE 的"失败"多数不是网络错，而是链路状态变了，所以措辞按"怎么办"来写，
             * 而不是照抄异常名 —— 手表屏幕小，用户需要的是下一步动作。 */
            m.contains("ble_disconnected") -> "蓝牙已断开，正在重连"
            m.contains("ble_stopped") -> "蓝牙已停止"
            m.contains("ble_bt_off") -> "手表蓝牙没开"
            m.contains("ble_no_adapter") -> "取不到蓝牙适配器"
            m.contains("ble_no_advertiser") -> "本机不支持蓝牙广播"
            m.contains("ble_unsupported") -> "本机不支持低功耗蓝牙"
            m.contains("ble_gatt_failed") -> "蓝牙服务建立失败"
            m.contains("ble_adv_failed") -> "蓝牙广播启动失败"

            else -> null
        }
    }
}
