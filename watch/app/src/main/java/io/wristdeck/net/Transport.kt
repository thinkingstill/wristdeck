package io.wristdeck.net

/**
 * 传输层抽象。
 *
 * 存在的理由：让 [BridgeClient] 只管"协议 + 状态机 + 重连策略"，不关心字节是怎么出去的。
 * 目前只有一个实现 [WsTransport]（Wi-Fi / WebSocket）；BLE（手表当 GATT Server）接进来时
 * 只需要再加一个实现，BridgeClient 的退避表、世代号护栏、`playing` 真相源一行都不用改。
 *
 * 约定（实现方必须遵守）：
 *  - [start] 要废弃上一次连接：旧连接的回调**不得**再上抛给 [Sink]（内部自增世代号即可）；
 *  - 回调**允许发生在任意线程**（OkHttp 在线程池、BLE 在 binder 线程），上层自行切主线程 ——
 *    这一点与改造前的行为一致，不要擅自在这里 post；
 *  - [send] 只在通道就绪时返回 true，未连接一律 false，不要抛异常；
 *  - 传输层**不做重连**，重连策略全部由上层负责。
 */
interface Transport {

    /** 与传输无关的回调出口，语义沿用改造前 WebSocketListener 的那套。 */
    interface Sink {

        /**
         * 底层通道就绪。
         *
         * ⚠️ 此刻**还不能**当作"会话可用"：服务端可能紧接着回 denied / replaced。
         * 上层在这里发 hello，真正的就绪以收到 welcome 为准。
         */
        fun onOpen()

        /** 收到一条**完整**文本消息（WebSocket 一帧 / BLE 一次 notify）。 */
        fun onMessage(text: String)

        /**
         * 连接关闭或失败。
         *
         * [rawReason] 是底层原始信息（异常 message / close reason），可能为 null；
         * 翻译成中文交给上层做（现在由 [NetError] 负责），因为上层还要优先采用
         * 服务端下发的 `denied.reason`。
         */
        fun onClosed(rawReason: String?)
    }

    /** 设置连接目标。Wi-Fi 用得上；BLE 实现可以忽略（广播/连接由它自己管）。 */
    fun updateTarget(host: String, port: Int)

    /** 建立连接。多次调用必须只保留最后一次。 */
    fun start()

    /** 主动关闭当前连接。不负责重连。 */
    fun stop()

    /** 尝试发送一条文本；返回是否已交给底层。 */
    fun send(text: String): Boolean

    /** 底层通道是否就绪（已 Open 且尚未关闭）。 */
    val ready: Boolean
}
