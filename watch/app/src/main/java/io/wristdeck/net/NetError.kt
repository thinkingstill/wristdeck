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

            else -> null
        }
    }
}
