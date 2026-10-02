package io.wristdeck.model

data class Config(
    val host: String,
    val port: Int,
    val pin: String,
    val keepAlive: Boolean,
    /**
     * 连接方式，默认 [Link.BLE]。
     *
     * 放进来而不是另开一个开关，是因为它和"连哪台 PC"是同一类东西（连接参数），
     * 设置页保存时本来就整体覆盖 [Config]；分开存反而会出现"换了 IP 但链路没换"的错位。
     *
     * ⚠️ 与手势开关的区别：手势开关是**独立**的一把键，刻意不在这里
     * （见 `Prefs.gestureOn` 的注释）—— 那是"现改现生效"的行为开关，不是连接参数。
     */
    val link: Link = Link.BLE,
) {
    companion object {
        const val DEFAULT_PORT = 8787
    }
}
