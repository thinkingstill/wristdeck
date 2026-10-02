package io.wristdeck.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi / WebSocket 传输实现 —— 从原 [BridgeClient] 原样搬出来，行为不变。
 *
 * 唯一的改动是：世代号（`generation`）和 socket 生命周期从 BridgeClient 下沉到这里。
 * 原来它俩是防止"被顶替的旧 socket 仍回调 onFailure 导致重复排程"的护栏，
 * 本质上属于**传输层**的职责，所以跟着搬过来更合适；上层另有 `pendingReconnect`
 * 保证同一轮只排一次重连，两层护栏叠加，行为与改造前一致。
 */
class WsTransport(private val sink: Transport.Sink) : Transport {

    private val main = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 每次 [start] 自增，回调里先比对世代号。
     *
     * 为什么必须有它：被顶替或被 cancel 的旧 socket 仍会回调 onFailure（典型是
     * "sent ping but didn't receive pong"），若不加甄别就又排一次重连，
     * 新旧连接互相顶替会让连接数指数级发散 —— 实测 Bridge 侧一晚上收到 6719 个会话。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var socket: WebSocket? = null

    private var host: String = ""
    private var port: Int = 0

    override val ready: Boolean
        get() = socket != null

    override fun updateTarget(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    override fun start() {
        // 同一时刻只允许一个在途连接：废弃上一个 socket。
        // 用 cancel() 而不是 close()：close() 会触发 onClosed 再排一次重连（旧的双重排程 bug）。
        val prev = socket
        socket = null
        prev?.cancel()
        generation += 1
        val gen = generation
        val url = "ws://$host:$port/ws?role=watch"
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(gen))
    }

    override fun stop() {
        // 刻意不动 generation：这里要的正是让在途回调照常上抛，由上层按
        // stopped / denyReason 决定后续，与改造前的行为保持一致。
        socket?.close(1000, "bye")
        socket = null
    }

    override fun send(text: String): Boolean {
        val s = socket ?: return false
        return s.send(text)
    }

    private fun postClosed(raw: String?) = main.post { sink.onClosed(raw) }

    /**
     * 每个连接一份，携带发起时的世代号；过期连接的回调直接丢弃。
     *
     * 注意这里**不**把回调 post 到主线程 —— 改造前 `handle()` 就是在 OkHttp 线程上跑的，
     * 保持原样，免得引入意料之外的时序变化（上层自己按需要 post）。
     */
    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun stale() = gen != generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale()) return
            sink.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale()) return
            sink.onMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stale()) return
            // 原始 message 只进日志（含 cleartext 拦截、ECONNREFUSED 等英文片段），
            // 给 UI 的 detail 由上层翻译成人话。
            Log.w(TAG, "连接失败: ${t.message}", t)
            socket = null
            postClosed(t.message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stale()) return
            Log.i(TAG, "连接关闭 code=$code reason=$reason")
            socket = null
            postClosed(reason)
        }
    }

    private companion object {
        const val TAG = "WristDeck"
    }
}
