package io.wristdeck.model

/**
 * 连接方式。
 *
 * 两条链路的**上层逻辑完全共用**（[io.wristdeck.net.Protocol] 的 JSON、`BridgeClient` 的状态机 /
 * 退避重连 / `playing` 真相源），差别只在字节怎么出去，所以用一个枚举把它表示成"配置项"，
 * 而不是在代码里到处 `if (是蓝牙)`。
 *
 *  - [BLE]  —— 蓝牙 GATT：手表当外设广播，Mac 上的网关 App 当中心。
 *             免填 IP、免同网段、免路由器依赖，是**默认**方式。
 *  - [WIFI] —— 局域网 WebSocket 直连 PC。免配 Mac 侧网关，作为 BLE 不可用时的兜底。
 *
 * ⚠️ **两者互斥，不能同时开。** Bridge 的 `state.watch` 是单实例，新 watch 连接会
 * `replaced` 掉旧连接（`server.js` code 4006）。BLE 模式下手表不再直连 Bridge，
 * 只连本机网关；网关自己以 `role=watch` 连回环 —— 同时开两条就是自己顶掉自己。
 */
enum class Link { BLE, WIFI }
