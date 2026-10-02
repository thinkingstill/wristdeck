package io.wristdeck.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * 蓝牙 BLE 传输实现：**手表当外设（GATT Server + 广播），Mac 当中心**。
 *
 * 方向对调是有硬原因的：手表做 Central 需要 `ACCESS_FINE_LOCATION` + 系统定位开关（targetSdk 30 的死穴），
 * 而做 **Peripheral 完全不要定位权限** —— 实测广播与 GATT Server 都是 `BLUETOOTH`/`BLUETOOTH_ADMIN`
 * 两个 normal 级权限搞定，装即得、无运行时申请、无配对框、无 PIN 交互。
 *
 * ## 数据通路（两条，各管一个方向）
 * | 方向 | GATT 操作 | 谁发起 |
 * |---|---|---|
 * | Mac → 手表（下行：welcome / ack / evt / ping） | Central **write** 到 [CHAR_UUID] | 网关 App |
 * | 手表 → Mac（上行：hello / cmd / pong） | Peripheral **notify** [CHAR_UUID] | 本类 [send] |
 *
 * 一次 write / 一次 notify 就是**一条完整消息**（与 WebSocket 一帧同构），所以
 * `Protocol` 的 JSON 一行不用改。实测 MTU 协商到 **512**，60~200 字节的消息单包直发，**无需分片**。
 *
 * ## 三个踩过的坑（都写死在代码里了，别"优化"掉）
 * 1. **CCCD 必须手动 `addDescriptor`** —— 这条 ROM 不给声明了 `PROPERTY_NOTIFY` 的特征自动补 CCCD。
 *    不加时 macOS 订阅直接报 `CBATTErrorDomain Code=10 "The attribute could not be found"`，
 *    于是"手表→Mac"方向整个不通。加上后 `isNotifying=true`。
 * 2. **`BluetoothManager.openGattServer()` 而不是 `BluetoothAdapter.openGattServer()`** ——
 *    后者是 `@SystemApi`，需要 `BLUETOOTH_PRIVILEGED`，第三方 App 编译期就过不去。
 * 3. **只有 legacy 广播**（`isLeExtendedAdvertisingSupported=false`）⇒ **31 字节预算**：
 *    128 位 service UUID 占 18 字节，所以设备名只能放 scan response（19 字节），不能挤在同一个包。
 *    Mac 侧识别**必须靠 UUID**，不能靠设备名（`local_name` 经常拿不到）。
 *
 * ## 与 [WsTransport] 的语义对齐
 * 世代号（`generation`）护栏照搬：每次 [start] 换一个回调实例、自增世代号，过期连接的回调一律丢弃。
 * 这防的是同一个病 —— 被拆掉的旧连接仍在 binder 线程回调，不加甄别就会重复上抛 `onClosed`，
 * 上层再排一次重连，新旧连接互相顶替直到连接数发散。
 *
 * 线程：BLE 回调来自 binder 线程，**不 post**（`Transport` 的约定：上层自己切主线程）。
 */
class BleTransport(
    context: Context,
    private val sink: Transport.Sink,
) : Transport {

    private val appContext: Context = context.applicationContext

    /**
     * 每次 [start] 自增；回调先比对世代号，过期即丢。
     *
     * 与 WsTransport 里那个是同一个角色。`@Volatile` 必须有：写发生在主线程
     * （[start] / [stop]），读发生在 binder 线程（回调）。
     */
    @Volatile
    private var generation = 0

    /** 中心设备（Mac 网关）。同一时刻只认一台 —— Bridge 的 `state.watch` 本身就是单实例。 */
    @Volatile
    private var central: BluetoothDevice? = null

    /**
     * 中心设备是否已订阅本特征的通知。
     *
     * **`onOpen` 就卡在这个状态上，不是"连上就报"** —— 见 [onDescriptorWriteRequest] 的注释。
     */
    @Volatile
    private var subscribed = false

    @Volatile
    private var stopped = true

    /**
     * 广播是否在播。
     *
     * 这个标志不是可有可无的优化：**BLE 一旦建立连接，控制器会自动停止广播**。
     * 所以"连过一次"之后，断开时必须重新 `startAdvertising`，否则网关再也搜不到手表。
     * 于是：`onStartSuccess` 置真；中心设备一连上就置假（此刻广播已被系统停掉），
     * 断开重连时 [startAdvertising] 才会真的重新开播。
     *
     * 它同时解决另一个问题：启动阶段 [start] 会被连调两次（见 [ensureServer] 注释），
     * 有它在就不会白白 stop+start 折腾一轮。
     */
    @Volatile
    private var advActive = false

    /** 最近一次协商到的 MTU，仅用于日志与长度告警。默认 23（ATT 最小集）。 */
    @Volatile
    private var mtu = DEFAULT_MTU

    private var adapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var receiverRegistered = false

    /**
     * 盯住系统蓝牙开关。
     *
     * ⚠️ 这个接收器**不是锦上添花，是必须的**。实测（OWW212 / ColorOS Watch / Android 11）：
     * 用户在设置里关掉蓝牙后，系统把 GATT Server、广播、连接全部拆掉，但**一个回调都不给** ——
     * `onConnectionStateChange` 根本没触发。于是不主动感知的话：
     *   - `BridgeClient.state` 永远停在 READY，状态条上"已连接"是**假的**（实测截图确认）；
     *   - 蓝牙重新打开后也没有任何东西去重建链路（没有事件、退避也没在跑），
     *     **除非重启 App，链路永久失效**（实测等 60s+ 依然零反应）。
     *
     * `onReceive` 默认跑在主线程 —— 与 BridgeClient 的状态机同一线程，不需要额外同步。
     */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "$B 系统蓝牙已关闭")
                    // 手里的 gattServer / advertiser 这时已经是僵尸对象，先把引用清掉。
                    tearDown()
                    // 上报失败 ⇒ 上层转 DISCONNECTED 并进退避，状态条不再说谎。
                    if (!stopped) sink.onClosed(REASON_BT_OFF)
                }

                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "$B 系统蓝牙已恢复，重建链路")
                    // 不等退避：蓝牙刚回来就立刻重建，否则最长要干等 60s。
                    if (!stopped) start()
                }
            }
        }
    }

    override val ready: Boolean
        get() = central != null && subscribed

    override fun updateTarget(host: String, port: Int) {
        // BLE 没有"目标地址"可填：谁连过来由对方扫描决定。刻意空实现而不是抛异常，
        // 这样 BridgeClient 那行 `transport.updateTarget(...)` 两条链路可以共用。
    }

    // ---------------- 生命周期 ----------------

    override fun start() {
        stopped = false
        ensureAdapterReceiver()
        // 中心设备还在线 = 上一轮会话的残留（典型是用户在设置页改了参数触发重连）。
        // 必须掐掉让它重连 —— 复用同一个连接的话 `onOpen` 不会再触发（订阅状态没变），
        // 会话就永远卡在 CONNECTING。这与 Wi-Fi 下"改配置关掉旧 socket 再连"是同一件事。
        if (central != null) tearDown()

        val err = ensureServer()
        if (err != null) {
            // 蓝牙没开 / 没有 BLE 能力：交给上层走退避重连，而不是卡在 CONNECTING。
            sink.onClosed(err)
            return
        }
        startAdvertising()
    }

    override fun stop() {
        val hadLink = central != null
        tearDown()
        stopped = true
        releaseAdapterReceiver()
        // 刻意**要**上抛 onClosed：`BridgeClient` 处理 denied 时正是靠 `transport.stop()`
        // 触发 onClosed 才把 `denyReason` 送进重连决策（否则会卡在中间态）。
        // 上层 `stop()` 路径里 `stopped=true`，这条 onClosed 会被安全忽略。
        if (hadLink) sink.onClosed(REASON_STOPPED)
    }

    private fun ensureAdapterReceiver() {
        if (receiverRegistered) return
        appContext.registerReceiver(
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
        receiverRegistered = true
    }

    private fun releaseAdapterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(adapterReceiver) }
            .onFailure { Log.w(TAG, "注销蓝牙状态接收器异常", it) }
        receiverRegistered = false
    }

    /** 拆连接 / 关服务 / 停广播，并让所有在途回调失效。不碰 [stopped]。 */
    private fun tearDown() {
        generation += 1
        runCatching { advertiser?.stopAdvertising(advCallback) }
            .onFailure { Log.w(TAG, "停广播异常", it) }
        advertiser = null
        advActive = false
        central = null
        subscribed = false
        mtu = DEFAULT_MTU
        txChar = null
        runCatching { gattServer?.close() }
            .onFailure { Log.w(TAG, "关 GATT Server 异常", it) }
        gattServer = null
    }

    // ---------------- GATT Server ----------------

    /**
     * 复用还活着的 GATT Server，只在没有时新建。
     *
     * 为什么要复用：`BridgeService.onCreate` 与 `onStartCommand` 会各调一次
     * `BridgeClient.start()`（后者看到的状态可能仍是 DISCONNECTED —— `state` 的更新是
     * post 到主线程的，来不及时序）。Wi-Fi 下两次 `newWebSocket` 只是多建一个 socket，
     * 无所谓；BLE 下每次重建都要 `openGattServer` + `addService` + 重开广播，
     * 实测日志里能看到 60ms 内建了两个 GATT Server、广播被 stop 又 start 一轮 —— 纯属扰动。
     *
     * @return null 表示就绪；否则返回可直接交给 [NetError] 翻译的失败 token。
     */
    private fun ensureServer(): String? {
        val ad = adapter
        if (gattServer != null && ad != null && ad.isEnabled) return null
        if (gattServer != null) {
            // 适配器被关过：旧 GATT Server 在系统那边已经注销，我们手里的只是僵尸对象。
            // 必须重建 —— 复用会出现"广播正常、服务不存在"，网关连得上却找不到特征。
            Log.i(TAG, "$B 旧 GATT Server 已失效，重建")
            tearDown()
        }
        return openServer(generation)
    }

    private fun openServer(gen: Int): String? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = manager?.adapter
        if (manager == null || ad == null) {
            Log.w(TAG, "$B$REASON_NO_ADAPTER（取不到 BluetoothManager/Adapter）")
            return REASON_NO_ADAPTER
        }
        adapter = ad
        if (!ad.isEnabled) {
            Log.w(TAG, "$B$REASON_BT_OFF（系统蓝牙未开启）")
            return REASON_BT_OFF
        }
        if (!appContext.packageManager.hasSystemFeature(FEATURE_BLE)) {
            Log.w(TAG, "$B$REASON_UNSUPPORTED（无 bluetooth_le 特性）")
            return REASON_UNSUPPORTED
        }

        val server = manager.openGattServer(appContext, ServerCallback(gen))
        if (server == null) {
            Log.w(TAG, "$B openGattServer 返回 null")
            return REASON_GATT_FAILED
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val chr = BluetoothGattCharacteristic(CHAR_UUID, CHAR_PROPS, CHAR_PERMS)
        // ⚠️ 坑 #1：必须手动加 CCCD，否则 macOS 订阅报 Code=10。
        chr.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, DESCRIPTOR_PERMS))
        service.addCharacteristic(chr)

        if (!server.addService(service)) {
            Log.w(TAG, "$B addService 失败")
            runCatching { server.close() }
            return REASON_GATT_FAILED
        }
        gattServer = server
        txChar = chr
        Log.i(TAG, "$B GATT Server 已就绪 service=$SERVICE_UUID")
        return null
    }

    // ---------------- 广播 ----------------

    /**
     * legacy 广播（坑 #3）。两个包各自都要塞进 31 字节：
     *  - advertising data：**只放 service UUID**（128 位 = 2 + 16 = 18 字节，实测就是这个尺寸）
     *  - scan response：放**设备名**（实测 19 字节）
     *
     * 用 `startAdvertising`（经典重载）而不是 `startAdvertisingSet`：实测 macOS 兼容性更好，
     * 且 `AdvertisingSetParameters` 在这套 SDK 上没有低延迟档位常量。
     */
    private fun startAdvertising() {
        if (advActive) {
            // 已经在播了（典型是启动阶段被连调两次 start()），不必再 stop+start 折腾一轮。
            Log.i(TAG, "$B 已在广播，跳过重复启动")
            return
        }
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w(TAG, "$B$REASON_NO_ADVERTISER（没有 BluetoothLeAdvertiser）")
            sink.onClosed(REASON_NO_ADVERTISER)
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            adv.startAdvertising(settings, data, scanResponse, advCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "$B startAdvertising 抛异常", t)
            sink.onClosed(REASON_ADV_FAILED)
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advActive = true
            Log.i(
                TAG,
                "$B 广播已开启 mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel}"
            )
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                // 说明它其实还在播（我们的 advActive 判早了）。把它纠回来，不当失败。
                advActive = true
                Log.i(TAG, "$B 广播已在运行，忽略")
                return
            }
            advActive = false
            // 注意：DATA_TOO_LARGE / FEATURE_UNSUPPORTED 属于改代码才能好的错，
            // 报上去会变成"每 60s 重试一次"。这是刻意的取舍 —— 让状态条上看得见异常，
            // 好过静悄悄地什么都不做（日志里能看到具体 errorCode）。
            Log.w(TAG, "$B 广播启动失败 errorCode=$errorCode")
            sink.onClosed("$REASON_ADV_FAILED:$errorCode")
        }
    }

    // ---------------- 发送 ----------------

    /**
     * 通过 notify 把一条消息推给中心设备。
     *
     * 只有"已连接且已订阅"才返回 true：[Transport.send] 的约定是未就绪一律 false、不抛异常，
     * 上层据此走失败反馈，而不是把消息悄悄吞掉。
     */
    @Synchronized
    @Suppress("DEPRECATION")
    override fun send(text: String): Boolean {
        val device = central ?: return false
        if (!subscribed) return false
        val server = gattServer ?: return false
        val chr = txChar ?: return false

        val bytes = text.toByteArray(Charsets.UTF_8)
        val budget = mtu - ATT_HEADER // ATT 协商值里含 3 字节头
        if (bytes.size > budget) {
            // 正常不会命中（最大的 ack 也就 200 字节上下，MTU 实测 512）。留一条告警是为了
            // 万一将来协议字段膨胀，能第一时间在日志里看到"是长度问题"而不是"消息丢了"。
            Log.w(TAG, "$B 消息 ${bytes.size}B 超过 ATT 单包上限 ${budget}B（mtu=$mtu）")
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, chr, false, bytes)
            } else {
                // API 33 之前只能先把值塞进特征再通知（33 起该写法和三参重载都已废弃）。
                chr.value = bytes
                server.notifyCharacteristicChanged(device, chr, false)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$B notify 失败", t)
            false
        }
    }

    // ---------------- 回调 ----------------

    /**
     * 每个 [start] 一份、携带发起时的世代号 —— 与 WsTransport 的 `Listener` 同构。
     *
     * 用「新建实例」而不是「复用单例 + 内部判代」是因为：这样每个回调天然绑定它那一次
     * `openGattServer` 的上下文，`gattServer` 被换成新的之后，旧实例的回调直接整段失效，
     * 不需要在每个方法里都写一遍判别。
     */
    private inner class ServerCallback(private val gen: Int) : BluetoothGattServerCallback() {

        private fun stale() = gen != generation

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (stale()) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    central = device
                    subscribed = false
                    // 广播已被系统停掉（可连接广播在建立连接时自动停），把标志跟着纠正，
                    // 这样断开重连时 startAdvertising() 才会真的重新开播。
                    advActive = false
                    Log.i(TAG, "$B 中心设备已连接 ${device.address}")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device.address != central?.address) return
                    Log.i(TAG, "$B 中心设备已断开 ${device.address} status=$status")
                    central = null
                    subscribed = false
                    // 断开不等于"用户关了它"：交给上层按退避重连，重连后重新广播等它回来。
                    if (!stopped) sink.onClosed(REASON_DISCONNECTED)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (stale()) return
            this@BleTransport.mtu = mtu
            Log.i(TAG, "$B MTU 协商为 $mtu（单包上限 ${mtu - ATT_HEADER}B）")
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (stale()) return
            Log.i(TAG, "$B onServiceAdded status=$status uuid=${service.uuid}")
        }

        /**
         * 下行：Mac 写入一条消息。
         *
         * 必须**先 sendResponse 再处理**：不回响应的话中心设备那边的 write 会一直挂着
         * （`writeValue(type:.withResponse)` 等不到回调，网关会以为链路坏了）。
         */
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (stale()) return
            if (characteristic.uuid != CHAR_UUID) return
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (preparedWrite || offset != 0) {
                // MTU 512 下现有消息都是单包，分片走不到这里；真走到了说明前提变了，留痕。
                Log.w(TAG, "$B 收到分片/预备写入（prepared=$preparedWrite offset=$offset），忽略")
                return
            }
            val text = String(value, Charsets.UTF_8)
            Log.i(TAG, "$B ← 收到写入 ${value.size}B")
            sink.onMessage(text)
        }

        /**
         * 订阅事件 —— 这里就是 [Transport.Sink.onOpen] 的触发点。
         *
         * 为什么不在 `STATE_CONNECTED` 时报 onOpen：`BridgeClient.onOpen()` 干的事是**发 hello**，
         * 而 hello 走的是 notify 方向 —— 中心设备还没订阅 CCCD 时那条通知根本发不出去。
         * hello 一丢，`welcome` 就永远不会回来，BridgeClient 会**永久停在 CONNECTING**
         * （WS 那边靠 TCP 失败能兜底，BLE 这边"连上了"是立即成立的，没有失败可等）。
         * 所以判据必须是"下行通道真的能用了"，也就是 CCCD 被写 01 00。
         */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (stale()) return
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid != CCCD_UUID) return

            val enable = value.isNotEmpty() && value[0] == CCCD_ENABLE_BYTE
            Log.i(TAG, "$B 中心设备${if (enable) "订阅" else "取消订阅"}通知")
            if (!enable) {
                subscribed = false
                return
            }
            if (!subscribed) {
                subscribed = true
                if (!stopped) sink.onOpen()
            }
        }
    }

    private companion object {
        const val TAG = "WristDeck"
        const val B = "BLE "

        /** 128 位自定义 UUID。广播包里只放它，Mac 侧靠它识别（设备名不可靠）。 */
        val SERVICE_UUID: UUID = UUID.fromString("7a1b0001-6f3c-4b2e-9a55-1d0e2f3a4b5c")

        /** 唯一特征：下行靠 write，上行靠 notify。 */
        val CHAR_UUID: UUID = UUID.fromString("7a1b0002-6f3c-4b2e-9a55-1d0e2f3a4b5c")

        /** 标准 Client Characteristic Configuration Descriptor。 */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 实测值 0x1c = WRITE | WRITE_NO_RESPONSE | NOTIFY。 */
        const val CHAR_PROPS = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY

        const val CHAR_PERMS = BluetoothGattCharacteristic.PERMISSION_READ or
            BluetoothGattCharacteristic.PERMISSION_WRITE

        const val DESCRIPTOR_PERMS = BluetoothGattDescriptor.PERMISSION_READ or
            BluetoothGattDescriptor.PERMISSION_WRITE

        /** CCCD 写 0x0001 = 打开通知，0x0000 = 关闭。 */
        const val CCCD_ENABLE_BYTE: Byte = 0x01

        const val FEATURE_BLE = "android.hardware.bluetooth_le"

        const val DEFAULT_MTU = 23
        const val ATT_HEADER = 3

        // 失败原因 token：由 NetError 翻成中文再上状态条。
        const val REASON_DISCONNECTED = "ble_disconnected"
        const val REASON_STOPPED = "ble_stopped"
        const val REASON_BT_OFF = "ble_bt_off"
        const val REASON_NO_ADAPTER = "ble_no_adapter"
        const val REASON_NO_ADVERTISER = "ble_no_advertiser"
        const val REASON_UNSUPPORTED = "ble_unsupported"
        const val REASON_ADV_FAILED = "ble_adv_failed"
        const val REASON_GATT_FAILED = "ble_gatt_failed"
    }
}
