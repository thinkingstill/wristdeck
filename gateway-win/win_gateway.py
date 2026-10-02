#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WristDeckLink for Windows —— WristDeck 的 Windows 侧 BLE 网关（纯透传）

职责只有三件事（与 macOS 侧 `gateway/WristDeckLink.swift` 逐条对齐）：
  1. 常驻扫描手表的 service UUID（**只靠 UUID，不靠设备名** —— 实测 local_name 常拿不到，
     而且手表 LE 地址还会随机轮换，所以每一轮都必须重新扫描，**绝不能缓存地址**），
     发现即连接 → 发现服务/特征 → 订阅通知
  2. 连本机 Bridge 的 WebSocket。回环地址命中服务端 `isLocalIp()` 的回环信任域，**免 PIN**
  3. 双向透传：BLE 通知 → WS 文本帧；WS 文本帧 → BLE write

它**故意不解释协议** —— 不认识 hello/cmd/ack，只搬字节。所以 Bridge 与浏览器扩展零改动，
协议将来要演进也只需要动手表侧一处（Protocol.kt）。

────────────────────────────────────────────────────────────────────────────
与 Mac 版的差异**只有技术栈**，语义完全一致：

| Mac（CoreBluetooth）                       | Windows（bleak / WinRT）              |
|--------------------------------------------|---------------------------------------|
| `CBCentralManager.scanForPeripherals`      | `BleakScanner.discover`               |
| `discoverServices` → `discoverCharacteristics` | `client.services`（连接后自动发现） |
| `setNotifyValue(true)`                     | `client.start_notify()`               |
| `writeValue(.withResponse)`                | `write_gatt_char(..., response=False)` |
| `URLSessionWebSocketTask`                  | `websockets`                          |

⚠️ 写用 **without-response**：本机实测手表侧日志是 `need_rsp=0` 且 `pong` 正常返回
   （见 `win_ble_e2e_result.json`），比 withResponse 少一个往返。
⚠️ Windows 蓝牙**不需要** macOS 那套 `Info.plist` / TCC 授权，所以没有 .app 打包问题，
   直接 `python win_gateway.py` 就能跑。

⚠️ Wi-Fi 与 BLE 互斥：Bridge 的 `state.watch` 是单实例，谁后连谁把前一个顶掉（code 4006）。
   所以网关在跑的时候，手表必须切到"用蓝牙连接"；反过来手表用 Wi-Fi 直连时就不要开网关。

单独运行（调试用，不带托盘）：
    python win_gateway.py
日志：%USERPROFILE%\\.wristbridge\\logs\\gateway.log
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from collections import deque
from typing import Any, Callable, Optional

# ──────────────────────────── 配置常量 ────────────────────────────

#: 手表 GATT Server 暴露的 service / characteristic（与 Protocol / 手表侧 BleTransport 一致）
SERVICE_UUID = "7a1b0001-6f3c-4b2e-9a55-1d0e2f3a4b5c"
CHAR_UUID = "7a1b0002-6f3c-4b2e-9a55-1d0e2f3a4b5c"

#: 以 `role=watch` 连本机 Bridge。回环地址 ⇒ 服务端跳过 PIN 校验，`pin` 留空即可。
#:
#: ⚠️ 这个 hello 不是"多此一举"，它是**会话重新对齐的关键**：
#: WS 断开重连后 Bridge 那边是一个全新的、没有 role 的会话；网关补发 hello 换来一个 `welcome`，
#: 再透传给手表 —— 手表那边就算没重发自己的 hello 也能回到 READY。
#: 没有它，WS 抖一次手表就会永久停在"连接中"。
HELLO_JSON = '{"v":1,"t":"hello","role":"watch","dev":"ble-gateway","pin":""}'

#: 实测 MTU 527 ⇒ ATT 单包上限 524B。留点余量，超了在日志里看得见（正常不会命中）。
MAX_PAYLOAD_BYTES = 500

#: 上行（BLE→WS）在 WS 未就绪时的暂存上限与保鲜期。
#: 手表一订阅就会立刻发 hello，丢了它就要等下一轮握手；但太老的消息补发出去只会造成
#: "用户 20 秒前按的手势现在才执行"，所以宁可丢。
UP_QUEUE_LIMIT = 32
UP_QUEUE_TTL = 5.0

#: 每个阶段各给 8 秒。实测正常一轮「连接 + 发现服务 + 发现特征 + 订阅」合计约 3 秒，8s 足够宽裕。
#: 为什么必须有超时：连上了但对方不再给回调时，链路上**所有**现成的自救入口都要求"对方先给消息"，
#: 回调不来时一个都走不到 ⇒ 进程活着却既不连也不扫，永久僵死。
LINK_TIMEOUT = 8.0
SCAN_TIMEOUT = 8.0
CONNECT_TIMEOUT = 20.0

#: WS 重连退避（与 Mac 版一致）
WS_BACKOFF = (1, 2, 4, 8, 15, 30)

#: 连错这么多次就放弃机型偏好、接受眼前这块。1/2^8 ≈ 0.4%：小到忽略，又保证"人不会被卡住"。
MAX_MISMATCH_RETRIES = 8

HOME = os.path.expanduser("~")
WRISTBRIDGE_DIR = os.path.join(HOME, ".wristbridge")
LOG_DIR = os.path.join(WRISTBRIDGE_DIR, "logs")
LOG_PATH = os.path.join(LOG_DIR, "gateway.log")
LOG_ROTATE_BYTES = 2 * 1024 * 1024

#: 机型偏好与 Mac 版**共用同一个文件**（换机器不用重配）。
#:   { "device": "OPWW234", "lastDevice": "OPWW234" }
#:   - `device`     ：**手工指定**，设了就永远优先
#:   - `lastDevice` ：网关自己维护，每次握手成功都会更新
STATE_PATH = os.path.join(WRISTBRIDGE_DIR, "wristdecklink.json")

#: Windows 专属配置（端口 / node 路径 / 保活等）。与上面那个分开，避免污染跨平台状态。
CONFIG_PATH = os.path.join(WRISTBRIDGE_DIR, "gateway-win.json")

DEFAULT_CONFIG: dict[str, Any] = {
    "bridgePort": 8787,
    "bridgeHost": "127.0.0.1",
    "nodePath": None,          # 空 = 从 PATH 里找 node
    "bridgeScript": None,      # 空 = <仓库>/bridge/server.js
    "bleKeepaliveSec": 0,      # 0 = 关（与 Mac 版一致：不发额外心跳）
    "device": None,            # 手工指定机型，写这里会同步进 wristdecklink.json
}


# ──────────────────────────── 日志 ────────────────────────────


class Logger:
    """同时写文件（2MB 轮转）与内存环形缓冲（供托盘显示），可选同步打屏。

    守护进程的日志不能无限长；托盘需要最近若干行做"查看日志"，所以留一个 deque。
    """

    def __init__(self, path: str = LOG_PATH, echo: bool = True) -> None:
        self.path = path
        self.echo = echo
        self.tail: deque[str] = deque(maxlen=400)
        self._sinks: list[Callable[[str], None]] = []
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self._rotate_if_needed()

    def _rotate_if_needed(self) -> None:
        try:
            if os.path.exists(self.path) and os.path.getsize(self.path) > LOG_ROTATE_BYTES:
                backup = self.path + ".1"
                if os.path.exists(backup):
                    os.remove(backup)
                os.replace(self.path, backup)
        except OSError:
            pass

    def add_sink(self, fn: Callable[[str], None]) -> None:
        """额外接收每一行（托盘用来刷新 UI）。"""
        self._sinks.append(fn)

    def __call__(self, msg: str) -> None:
        line = f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {msg}"
        self.tail.append(line)
        if self.echo:
            try:
                print(line, flush=True)
            except Exception:
                pass
        try:
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            pass
        for fn in list(self._sinks):
            try:
                fn(line)
            except Exception:
                pass

    def text(self, n: int = 200) -> str:
        return "\n".join(list(self.tail)[-n:])


LOG = Logger()


# ──────────────────────────── 状态文件 ────────────────────────────


def load_state() -> dict[str, Any]:
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def save_state(state: dict[str, Any]) -> None:
    try:
        os.makedirs(WRISTBRIDGE_DIR, exist_ok=True)
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
    except OSError:
        pass


def load_config() -> dict[str, Any]:
    """读 `gateway-win.json`；缺文件 / 坏文件都当默认值，绝不影响主链路。"""
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            user = json.load(f)
        if isinstance(user, dict):
            cfg.update(user)
    except Exception:
        pass
    return cfg


def save_config(cfg: dict[str, Any]) -> None:
    try:
        os.makedirs(WRISTBRIDGE_DIR, exist_ok=True)
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except OSError:
        pass


def ensure_config_file() -> str:
    """首次运行落一份默认配置，用户想改端口 / 指定 node 路径时有地方下手。"""
    if not os.path.exists(CONFIG_PATH):
        save_config(dict(DEFAULT_CONFIG))
    return CONFIG_PATH


# ──────────────────────────── WebSocket 兼容层 ────────────────────────────

try:  # websockets >= 14 的新式 asyncio API
    from websockets.asyncio.client import connect as ws_connect
except ImportError:  # pragma: no cover - 老版本兜底
    from websockets import connect as ws_connect  # type: ignore


# ──────────────────────────── 网关 ────────────────────────────


class Gateway:
    """全程跑在**同一个 asyncio 事件循环**上（托盘把它放在独立线程里），
    于是所有状态都被串行化，不需要复杂的锁 —— 这是刻意选的，避免出一堆难查的竞态。

    跨线程只暴露 `status()` / `request_ble_restart()` / `request_ws_restart()`，
    它们读写的都是标量或只做 set/clear，足够安全。
    """

    def __init__(self, config: Optional[dict[str, Any]] = None, logger: Logger = LOG) -> None:
        self.cfg = config or load_config()
        self.log = logger

        # ---- BLE 侧 ----
        self.client: Any = None
        self.ble_ready = False
        self._ble_ready_evt = asyncio.Event()
        self._abort_ble = asyncio.Event()
        self._disconnected = asyncio.Event()
        self._ble_lock = asyncio.Lock()
        self._last_ble_tx = 0.0
        self._had_link = False

        # ---- WS 侧 ----
        self.ws: Any = None
        self._ws_open = False
        self._ws_open_evt = asyncio.Event()
        self._ws_lock = asyncio.Lock()

        # ---- 两个方向的缓冲 ----
        self.up_q: asyncio.Queue[tuple[float, str]] = asyncio.Queue()
        self.down_q: asyncio.Queue[bytes] = asyncio.Queue()

        # ---- 设备偏好（两块表同时在播时的仲裁） ----
        self.state = load_state()
        if self.cfg.get("device"):
            self.state["device"] = self.cfg["device"]
        self._mismatch = 0
        self._pref_exhausted = False

        # ---- 供托盘/外部读取的运行状态 ----
        self.stat: dict[str, Any] = {
            "started_at": time.time(),
            "ble": "idle",
            "ble_detail": "",
            "watch_addr": None,
            "watch_name": None,
            "mtu": None,
            "ws": "idle",
            "ws_url": self._ws_url(),
            "ws_sent": 0,
            "ws_recv": 0,
            "relay_up": 0,
            "relay_down": 0,
            "dropped_up": 0,
            "dropped_down": 0,
            "cycles": 0,
            "device": None,
            "want_device": self.want_device,
            "last_err": None,
        }

        self._tasks: list[asyncio.Task] = []

    # ---------------- 小工具 ----------------

    def _ws_url(self) -> str:
        host = self.cfg.get("bridgeHost") or "127.0.0.1"
        port = int(self.cfg.get("bridgePort") or 8787)
        return f"ws://{host}:{port}/ws?role=watch"

    @property
    def want_device(self) -> Optional[str]:
        """本次会话要优先找的机型：手工 `device` 优先，否则用记忆的 `lastDevice`。"""
        return self.state.get("device") or self.state.get("lastDevice")

    def _set_ble(self, phase: str, detail: str = "") -> None:
        self.stat["ble"] = phase
        self.stat["ble_detail"] = detail
        self.stat["want_device"] = self.want_device

    def _set_ws(self, phase: str) -> None:
        self.stat["ws"] = phase

    def status(self) -> dict[str, Any]:
        s = dict(self.stat)
        s["uptime"] = round(time.time() - s["started_at"], 1)
        s["want_device"] = self.want_device
        return s

    def request_ble_restart(self) -> None:
        """托盘"重启链路"按钮：打断当前 BLE 周期，回到扫描。跨线程只做置位。"""
        self._abort_ble.set()

    # ---------------- 生命周期 ----------------

    async def run(self) -> None:
        self._banner()
        self._tasks = [
            asyncio.create_task(self._ble_worker(), name="ble"),
            asyncio.create_task(self._ws_worker(), name="ws"),
            asyncio.create_task(self._up_pump(), name="up"),
            asyncio.create_task(self._down_pump(), name="down"),
        ]
        try:
            await asyncio.gather(*self._tasks)
        except asyncio.CancelledError:
            raise
        finally:
            for t in self._tasks:
                t.cancel()

    def _banner(self) -> None:
        self.log("=== WristDeckLink for Windows 启动 ===")
        self.log(f"日志文件 {LOG_PATH}")
        self.log(f"Bridge   {self._ws_url()}")
        self.log(f"偏好机型 {self.want_device or '未指定（任何一块都行）'}"
                 f"（{'手工指定' if self.state.get('device') else '自动记忆'}）")
        ka = float(self.cfg.get("bleKeepaliveSec") or 0)
        self.log(f"BLE 保活 {'关闭（与 Mac 版一致）' if ka <= 0 else f'{ka:g}s'}")
        self.log(f"扫描目标 service={SERVICE_UUID}")

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        await self._ble_teardown()

    # ---------------- BLE 主循环 ----------------

    async def _ble_worker(self) -> None:
        while True:
            try:
                await self._ble_cycle()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # 蓝牙关闭 / 适配器被禁用等
                self.stat["last_err"] = f"{type(e).__name__}: {e}"
                self.log(f"BLE 周期异常：{type(e).__name__}: {e}")
            had = self._had_link
            await self._ble_teardown()
            self.stat["cycles"] += 1
            if had:
                # 只有"建立过链路"才算一轮重连，避免把纯扫描等待也算进去
                self.log("1s 后重新扫描")
            await asyncio.sleep(1.0)

    async def _ble_cycle(self) -> None:
        self._abort_ble.clear()
        self._disconnected.clear()
        self._had_link = False

        # ---------- 1. 扫描（每轮重扫，地址必刷新） ----------
        self._set_ble("scanning", "扫描 service UUID…")
        self.log(f"开始扫描 service={SERVICE_UUID}")
        try:
            dev, adv = await asyncio.wait_for(
                self._scan_for_watch(), timeout=SCAN_TIMEOUT + 25
            )
        except asyncio.TimeoutError:
            self.log("扫描超时；等下一轮")
            self._set_ble("idle", "扫描超时")
            return

        if dev is None:
            self._set_ble("idle", "未扫到手表广播")
            self.log("未扫到手表（广播可能已停：灭屏 / Doze / 不在范围 / 被其它主机占用）")
            return

        self.stat["watch_addr"] = dev.address
        self.stat["watch_name"] = adv.local_name
        self.log(f"发现手表 rssi={adv.rssi} addr={dev.address} name={adv.local_name!r}")

        if self._abort_ble.is_set():
            return

        # ---------- 2. 连接 ----------
        self._set_ble("connecting", f"连接 {dev.address}")
        self.log("发起连接…")
        client = BleakClientFactory(dev, timeout=CONNECT_TIMEOUT,
                                    disconnected_callback=self._on_disconnected)
        try:
            await asyncio.wait_for(client.connect(), timeout=CONNECT_TIMEOUT + 5)
        except Exception as e:
            self.log(f"连接失败：{type(e).__name__}: {e}；1s 后重新扫描")
            try:
                await client.disconnect()
            except Exception:
                pass
            return
        self.client = client
        self._had_link = True
        self.log(f"已连接 {dev.address}")

        # ---------- 3. 发现服务 / 特征 ----------
        self._set_ble("subscribing", "发现服务/特征")
        svc = next((s for s in client.services if s.uuid.lower() == SERVICE_UUID), None)
        if svc is None:
            self.log("没找到目标服务；1s 后重来")
            return
        ch = next((c for c in svc.characteristics if c.uuid.lower() == CHAR_UUID), None)
        if ch is None:
            self.log("没找到目标特征；1s 后重来")
            return
        self.log(f"特征已就绪 properties={list(ch.properties)}，订阅通知…")

        # ---------- 4. 订阅（"链路可用"的唯一判据） ----------
        # ⚠️ hello 走的是手表→网关的 notify 方向，**没订阅就发不出去**；
        #    所以"就绪"必须挂在这里，不能挂在连接建立。
        try:
            await asyncio.wait_for(
                client.start_notify(CHAR_UUID, self._on_notify), timeout=LINK_TIMEOUT
            )
        except Exception as e:
            self.log(f"订阅失败：{type(e).__name__}: {e}")
            return

        mtu = getattr(client, "mtu_size", None)
        self.stat["mtu"] = mtu
        self.ble_ready = True
        self._ble_ready_evt.set()
        self._set_ble("ready", f"MTU {mtu}")
        self.log(f"BLE 链路就绪（已订阅通知）MTU={mtu} 单包上限={mtu - 3 if mtu else '?'}B")

        # ---------- 5. 守着这条链路 ----------
        await self._wait_ble_end()

    async def _scan_for_watch(self):
        """返回 (BLEDevice, AdvertisementData) 或 (None, None)。

        广告包里只放 service UUID（只有 legacy 广播，31 字节预算，设备名放 scan response），
        所以识别**必须**靠 UUID。实测 local_name 常常拿不到。
        """
        found = await BleakScannerFactory.discover(timeout=SCAN_TIMEOUT, return_adv=True)
        cands = []
        for _addr, (d, adv) in (found or {}).items():
            uuids = [u.lower() for u in (adv.service_uuids or [])]
            if SERVICE_UUID in uuids:
                cands.append((d, adv))
        if not cands:
            if found:
                self.log(f"扫描到 {len(found)} 个设备，无一命中目标 service")
            return None, None
        if len(cands) > 1:
            self.log(f"⚠️ 同时扫到 {len(cands)} 块表（同 service UUID），先连第一块，"
                     f"真正的仲裁在收到 hello 之后")
        # 与 Mac 版一致：谁先被扫到就连谁。广播里区分不了机型，随机挑没有意义，
        # 而且真挑错了也会在 hello 阶段被仲裁掉。
        return cands[0]

    async def _wait_ble_end(self) -> None:
        """守着当前链路，直到：断开回调 / 手动重启 / 被仲裁拒绝 / is_connected 变假。"""
        ka = float(self.cfg.get("bleKeepaliveSec") or 0)
        self._last_ble_tx = time.monotonic()
        while True:
            if self._abort_ble.is_set():
                self.log("收到手动重连请求 ⇒ 丢弃链路重扫")
                return
            if self._disconnected.is_set():
                self.log("已断开（收到断开回调）；1s 后重新扫描")
                return
            c = self.client
            if c is None or not c.is_connected:
                self.log("检测到链路失效（is_connected=False）；1s 后重新扫描")
                return
            if ka > 0 and time.monotonic() - self._last_ble_tx >= ka:
                # 可选：补一条协议自带的心跳，用来尽早发现"半死"的链路。
                # 默认关闭，保持与 Mac 版一致的报文特征。
                try:
                    await self._ble_write(b'{"v":1,"t":"ping","id":"gwka"}')
                except Exception:
                    return
            await asyncio.sleep(0.5)

    async def _ble_teardown(self) -> None:
        if self.ble_ready:
            self.log("BLE 链路失效")
        self.ble_ready = False
        self._ble_ready_evt.clear()
        self.stat["mtu"] = None
        c, self.client = self.client, None
        if c is not None:
            try:
                if c.is_connected:
                    try:
                        await c.stop_notify(CHAR_UUID)
                    except Exception:
                        pass
                    await c.disconnect()
            except Exception as e:
                self.log(f"断开清理异常：{type(e).__name__}: {e}")
        self._set_ble("idle", "链路已清理")

    def _on_disconnected(self, _client: Any) -> None:
        """bleak 的断开回调（在事件循环线程里被调用，只做置位）。"""
        self._disconnected.set()

    def _on_notify(self, _sender: Any, data: bytearray) -> None:
        """上行：手表 notify 出来的一条完整消息。

        ⚠️ 这个回调必须是**同步**的：它由 bleak 在事件循环中直接调用，
        里面不能 await，所以只做「解析机型 → 入队」，真正的发送交给 `_up_pump`。
        """
        try:
            text = bytes(data).decode("utf-8")
        except UnicodeDecodeError:
            self.log(f"收到非 UTF-8 通知 {len(data)}B，丢弃")
            return

        # 两块表同时在播时的仲裁：只有拿到 hello 才知道对面是哪一块
        dev = hello_device(text)
        if dev is not None and self._reject(dev):
            return

        self.log(f"↑ {text}")
        self._enqueue_up(text)

    def _enqueue_up(self, text: str) -> None:
        q = self.up_q
        if q.qsize() >= UP_QUEUE_LIMIT:
            try:
                q.get_nowait()
                self.stat["dropped_up"] += 1
            except asyncio.QueueEmpty:
                pass
        q.put_nowait((time.monotonic(), text))

    # ---------------- 设备偏好仲裁 ----------------

    def _reject(self, dev: str) -> bool:
        """返回 True = 这条 hello **作废**（已安排断开重扫）。

        ⚠️ 被拒的 hello 必须**整个丢掉、绝不能透传给 Bridge**：Bridge 对同一个 WS 会话
        只认一次 `hello`，同一会话再发一次会被当成异常消息，握手直接坏掉。
        而网关到 Bridge 的 WS 是**全程复用的那一条** —— 所以"先决定、再转发"是唯一安全的顺序。
        """
        want = self.want_device
        if want is None or want == dev or self._pref_exhausted:
            # `preferenceExhausted` 时必须 `persist=False`：这个标志一旦置真就整个会话不复位，
            # 而"本次会话不再挑"之后每一次重连都会走到这一支 —— 若沿用默认的 True，
            # 只要之后重连成功一次，`lastDevice` 就被覆盖，上面"不动记忆"的承诺当场失效。
            self._remember(dev, persist=not self._pref_exhausted)
            return False

        self._mismatch += 1
        if self._mismatch > MAX_MISMATCH_RETRIES:
            self._pref_exhausted = True
            self.log(f"⚠️ 连试 {MAX_MISMATCH_RETRIES} 次都没等到 {want} ⇒ "
                     f"本次会话改用 {dev}，不再挑")
            # 期望的那块一直没出现，多半是它没开机 / 不在附近。这时**不动记忆**：
            # 下次启动仍优先它，等它回来就自动切回去。
            self._remember(dev, persist=False)
            return False

        self.log(f"⚠️ 扫到的是 {dev}，要的是 {want}"
                 f"（第 {self._mismatch}/{MAX_MISMATCH_RETRIES} 次）⇒ 断开重扫")
        self._abort_ble.set()
        return True

    def _remember(self, dev: str, persist: bool = True) -> None:
        """握手成功 ⇒ 记下来，下次启动优先找它。手工指定（`state.device`）时不动记忆。"""
        self._mismatch = 0
        self.stat["device"] = dev
        if not persist:
            return
        if self.state.get("device") is None and self.state.get("lastDevice") != dev:
            self.state["lastDevice"] = dev
            save_state(self.state)
            self.log(f"记住手表机型 {dev}")

    # ---------------- 上行泵（BLE → WS） ----------------

    async def _up_pump(self) -> None:
        while True:
            ts, text = await self.up_q.get()
            if not await self._wait_ws_ready(ts):
                self.stat["dropped_up"] += 1
                self.log(f"丢弃 1 条过期上行消息（WS 超过 {UP_QUEUE_TTL:g}s 未就绪）")
                continue
            try:
                await self._ws_send(text)
                self.stat["relay_up"] += 1
            except Exception as e:
                self.log(f"WS 发送失败：{type(e).__name__}: {e}")

    async def _wait_ws_ready(self, ts: float) -> bool:
        while not self._ws_open:
            remain = UP_QUEUE_TTL - (time.monotonic() - ts)
            if remain <= 0:
                return False
            try:
                await asyncio.wait_for(self._ws_open_evt.wait(), timeout=remain)
            except asyncio.TimeoutError:
                return False
        return True

    async def _ws_send(self, text: str) -> None:
        async with self._ws_lock:
            ws = self.ws
            if ws is None or not self._ws_open:
                raise RuntimeError("ws 未连接")
            await ws.send(text)
        self.stat["ws_sent"] += 1

    # ---------------- WS 主循环 ----------------

    async def _ws_worker(self) -> None:
        idx = 0
        url = self._ws_url()
        while True:
            self._set_ws("connecting")
            self.log("连接 Bridge…")
            try:
                async with ws_connect(url, ping_interval=None, open_timeout=5,
                                      close_timeout=3, max_size=None) as ws:
                    self.ws = ws
                    idx = 0
                    self.log("WS 已连接")
                    # ⚠️ 顺序不能换：Bridge 对"还没有 role 的会话"会把非 hello 消息
                    #    一律打回 need_hello（4005）。所以网关自己的 hello 必须最先发，
                    #    发完才放行暂存的上行消息。
                    await ws.send(HELLO_JSON)
                    self.stat["ws_sent"] += 1
                    self._ws_open = True
                    self._ws_open_evt.set()
                    self._set_ws("open")
                    async for raw in ws:
                        if isinstance(raw, (bytes, bytearray)):
                            raw = bytes(raw).decode("utf-8", "replace")
                        self.stat["ws_recv"] += 1
                        self._on_ws_text(raw)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                self.stat["last_err"] = f"ws: {type(e).__name__}: {e}"
                self.log(f"WS 接收中断：{type(e).__name__}: {e}")
            finally:
                self._ws_open = False
                self._ws_open_evt.clear()
                self.ws = None
                self._set_ws("idle")

            delay = WS_BACKOFF[min(idx, len(WS_BACKOFF) - 1)]
            idx += 1
            self.log(f"WS {delay}s 后重连")
            await asyncio.sleep(delay)

    # ---------------- 下行泵（WS → BLE） ----------------

    def _on_ws_text(self, text: str) -> None:
        """下行：Bridge 发来的一条完整消息。"""
        self.log(f"↓ {text}")
        if not self.ble_ready:
            # 与上行刻意不对称：补发过期的 ack 只会造成状态错乱，所以直接丢。
            self.stat["dropped_down"] += 1
            self.log("BLE 未就绪，丢弃这条下行消息")
            return
        data = text.encode("utf-8")
        if len(data) > MAX_PAYLOAD_BYTES:
            self.log(f"⚠️ 下行 {len(data)}B 超过 ATT 单包上限，可能发不出去")
        if self.down_q.qsize() > 64:
            self.stat["dropped_down"] += 1
            self.log("下行队列积压，丢弃这条消息")
            return
        self.down_q.put_nowait(data)

    async def _down_pump(self) -> None:
        while True:
            data = await self.down_q.get()
            if not await self._wait_ble_ready(3.0):
                self.stat["dropped_down"] += 1
                self.log("BLE 未就绪，丢弃这条下行消息")
                continue
            try:
                await self._ble_write(data)
                self.stat["relay_down"] += 1
            except Exception as e:
                self.stat["dropped_down"] += 1
                self.log(f"下发写入失败：{type(e).__name__}: {e}")

    async def _wait_ble_ready(self, timeout: float) -> bool:
        if self.ble_ready:
            return True
        try:
            await asyncio.wait_for(self._ble_ready_evt.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            return self.ble_ready
        return True

    async def _ble_write(self, data: bytes) -> None:
        """`.withResponse` 在 Mac 上同时只允许一个写在空中，所以串行化排队；这里同理。"""
        async with self._ble_lock:
            c = self.client
            if c is None or not self.ble_ready or not c.is_connected:
                raise RuntimeError("BLE 链路不可用")
            await asyncio.wait_for(
                c.write_gatt_char(CHAR_UUID, data, response=False), timeout=6
            )
            self._last_ble_tx = time.monotonic()


# ──────────────────────────── 延迟导入（便于纯语法检查） ────────────────────────────
#
# bleak 只在真正要连蓝牙时才需要；把 import 放在这里，可以让 `python -c "import
# win_gateway"` 在没有蓝牙依赖的环境下也能过语法，托盘启动时再一次性导入。


def _bleak():
    from bleak import BleakClient, BleakScanner

    return BleakClient, BleakScanner


try:
    BleakClientFactory, BleakScannerFactory = _bleak()
except Exception:  # pragma: no cover
    BleakClientFactory = None  # type: ignore
    BleakScannerFactory = None  # type: ignore


def hello_device(text: str) -> Optional[str]:
    """从手表的 hello 里取机型。只认握手消息，别把别的 JSON 当设备信息。"""
    if '"hello"' not in text or '"t"' not in text:
        return None
    try:
        obj = json.loads(text)
    except Exception:
        return None
    if not isinstance(obj, dict) or obj.get("t") != "hello":
        return None
    dev = obj.get("dev")
    return dev if isinstance(dev, str) and dev else None


# ──────────────────────────── CLI ────────────────────────────


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="WristDeck Windows BLE 网关（纯透传）")
    ap.add_argument("--quiet", action="store_true", help="不打屏，只写日志文件")
    ap.add_argument("--port", type=int, default=None, help="覆盖 Bridge 端口（默认 8787）")
    ap.add_argument("--host", default=None, help="覆盖 Bridge 主机（默认 127.0.0.1）")
    ap.add_argument("--device", default=None, help="手工指定手表机型（如 OPWW234），写入偏好在文件")
    ap.add_argument("--status", action="store_true", help="打印一次配置与偏好后退出")
    args = ap.parse_args(argv)

    cfg = load_config()
    if args.port:
        cfg["bridgePort"] = args.port
    if args.host:
        cfg["bridgeHost"] = args.host
    if args.device:
        cfg["device"] = args.device
        st = load_state()
        st["device"] = args.device
        save_state(st)
        save_config(cfg)

    if args.status:
        print(json.dumps({
            "config": cfg,
            "state": load_state(),
            "log": LOG_PATH,
            "service": SERVICE_UUID,
        }, ensure_ascii=False, indent=2))
        return 0

    if BleakClientFactory is None:
        print("缺少依赖：bleak。请先运行 install.ps1，或 pip install bleak websockets",
              file=sys.stderr)
        return 2

    LOG.echo = not args.quiet
    gw = Gateway(cfg, LOG)

    async def runner() -> None:
        try:
            await gw.run()
        except asyncio.CancelledError:
            pass

    try:
        asyncio.run(runner())
    except KeyboardInterrupt:
        LOG("收到 Ctrl-C，退出")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
