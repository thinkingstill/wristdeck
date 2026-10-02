#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WristDeck 托盘常驻（Windows）—— 一个图标托管整套链路

它做三件事：
  1. 内嵌跑 BLE 网关（`win_gateway.Gateway`），跑在自己的事件循环线程里
  2. 以子进程拉起并**守护** Node Bridge（`bridge/server.js`），崩了自动重启；
     子进程 stdout 原样汇进网关日志，所以终端/状态页/日志文件是同一份流水
  3. 托盘图标实时反映链路状态（颜色 = BLE 是否就绪），右键菜单提供启停与开关

为什么用"统一托管"而不是两个独立进程：
  用户侧只有一个东西要装、要启动、要退出；且**单实例互斥**能防止两个网关同时抢同一块表
  —— 实测两台主机同时以 watch 身份连 Bridge 时，`pong` 会串流到错误的一方。

启动方式：
    pythonw.exe tray.py            # 无控制台（install.ps1 自启项用的就是这个）
    python.exe  tray.py --console  # 带控制台，调试用
    python.exe  tray.py --no-bridge  # 只跑 BLE 网关，Bridge 你自己起

退出：托盘菜单「退出」
"""

from __future__ import annotations

import argparse
import asyncio
import ctypes
import os
import shutil
import subprocess
import sys
import threading
import time
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

try:
    import win_gateway as gw_mod
    from win_gateway import Gateway, Logger
except Exception as e:  # pragma: no cover
    print(f"无法导入 win_gateway：{e}", file=sys.stderr)
    raise

IS_WINDOWS = os.name == "nt"
CREATE_NO_WINDOW = 0x08000000 if IS_WINDOWS else 0

STATE_COLORS = {
    "ready": (63, 185, 80),        # 绿：链路就绪
    "connecting": (210, 153, 34),  # 琥珀：正在连
    "subscribing": (210, 153, 34),
    "scanning": (88, 166, 255),    # 蓝：在扫描
    "idle": (139, 148, 158),       # 灰：没扫到 / 已清理
    "error": (248, 81, 73),        # 红：出错
}

BLE_TEXT = {
    "ready": "已就绪",
    "connecting": "连接中",
    "subscribing": "订阅中",
    "scanning": "扫描中",
    "idle": "空闲",
}
WS_TEXT = {"open": "已连接", "connecting": "连接中", "idle": "断开"}


# ──────────────────────────── 单实例 ────────────────────────────


def acquire_single_instance(name: str = r"Global\WristDeckWindowsTray"):
    """具名 mutex 防多开。返回句柄（需保持引用）或 None=已有实例在跑。"""
    if not IS_WINDOWS:
        return 1
    k32 = ctypes.windll.kernel32
    handle = k32.CreateMutexW(None, False, name)
    if not handle:
        return 1
    if k32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        return None
    return handle


# ──────────────────────────── Bridge 子进程守护 ────────────────────────────


class BridgeSupervisor:
    """拉起 `node bridge/server.js` 并守护它。stdout/stderr 逐行汇进我们的日志。"""

    def __init__(self, log: Logger, node: str | None, script: str, cwd: str) -> None:
        self.log = log
        self.node = node
        self.script = script
        self.cwd = cwd
        self.proc: subprocess.Popen | None = None
        self._stopping = threading.Event()
        self._thread: threading.Thread | None = None
        self.restarts = 0
        self.last_exit: str | None = None

    # -- 对外 --
    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self) -> None:
        if not self.node:
            self.log("⚠️ 找不到 node，Bridge 未启动（请装 Node.js，或安装时把路径写进 gateway-win.json）")
            return
        if not os.path.exists(self.script):
            self.log(f"⚠️ 找不到 {self.script}，Bridge 未启动")
            return
        self._thread = threading.Thread(target=self._loop, name="bridge", daemon=True)
        self._thread.start()

    def restart(self) -> None:
        p = self.proc
        if p and p.poll() is None:
            self.log("按请求重启 Bridge…")
            self.last_exit = "manual"
            try:
                p.terminate()
            except Exception:
                pass

    def stop(self) -> None:
        self._stopping.set()
        p = self.proc
        if p and p.poll() is None:
            try:
                p.terminate()
            except Exception:
                pass
            try:
                p.wait(timeout=5)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass

    # -- 内部 --
    def _loop(self) -> None:
        delay = 2
        while not self._stopping.is_set():
            argv = [self.node, self.script]
            self.log(f"启动 Bridge：{' '.join(argv)}")
            try:
                self.proc = subprocess.Popen(
                    argv,
                    cwd=self.cwd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    stdin=subprocess.DEVNULL,
                    creationflags=CREATE_NO_WINDOW,
                    bufsize=1,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )
            except Exception as e:
                self.log(f"⚠️ Bridge 启动失败：{type(e).__name__}: {e}")
                self.proc = None
                if self._stopping.wait(delay):
                    break
                delay = min(delay * 2, 30)
                continue

            self._pump_output(self.proc)
            code = self.proc.wait()
            self.proc = None
            if self._stopping.is_set():
                break
            if self.last_exit == "manual":
                self.last_exit = None
                delay = 2
                self.log("Bridge 已按请求停止，2s 后重新拉起")
            else:
                self.restarts += 1
                self.last_exit = f"exit={code}"
                self.log(f"⚠️ Bridge 退出（code={code}），{delay}s 后重启（第 {self.restarts} 次）")
                delay = min(delay * 2, 30)

    def _pump_output(self, proc: subprocess.Popen) -> None:
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                line = line.rstrip("\r\n")
                if line:
                    self.log(f"[bridge] {line}")
        except Exception:
            pass


# ──────────────────────────── 托盘图标 ────────────────────────────


def make_icon_image(rgb: tuple[int, int, int]):
    from PIL import Image, ImageDraw

    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    body = (13, 17, 23, 255)
    # 表体
    d.rounded_rectangle((6, 3, size - 7, size - 4), radius=12, fill=body, outline=rgb, width=4)
    # 表带（上下两小段，让它一眼像手表）
    d.rectangle((24, 0, size - 25, 5), fill=rgb)
    d.rectangle((24, size - 6, size - 25, size - 1), fill=rgb)
    # 表盘
    d.ellipse((19, 16, size - 20, size - 17), outline=rgb, width=4)
    # 指针
    d.line((32, 31, 32, 22), fill=rgb, width=4)
    d.line((32, 31, 40, 34), fill=rgb, width=4)
    return img


# ──────────────────────────── 开机自启 ────────────────────────────

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "WristDeck"


def autostart_command() -> str:
    exe = sys.executable
    pythonw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(pythonw):
        exe = pythonw
    return f'"{exe}" "{os.path.join(HERE, "tray.py")}"'


def autostart_enabled() -> bool:
    if not IS_WINDOWS:
        return False
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, RUN_VALUE)
            return True
    except OSError:
        return False


def set_autostart(on: bool) -> bool:
    if not IS_WINDOWS:
        return False
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, autostart_command())
            else:
                try:
                    winreg.DeleteValue(k, RUN_VALUE)
                except OSError:
                    pass
        return True
    except OSError:
        return False


# ──────────────────────────── 托盘应用 ────────────────────────────


class TrayApp:
    def __init__(self, args: argparse.Namespace) -> None:
        gw_mod.ensure_config_file()
        self.cfg = gw_mod.load_config()
        if args.port:
            self.cfg["bridgePort"] = args.port
        self.port = int(self.cfg.get("bridgePort") or 8787)
        self.log = Logger(echo=True)
        self.gateway = Gateway(self.cfg, self.log)

        node = args.node or self.cfg.get("nodePath") or shutil.which("node")
        bridge_script = self.cfg.get("bridgeScript") or os.path.join(REPO, "bridge", "server.js")
        self.bridge = BridgeSupervisor(
            self.log, node, bridge_script, os.path.dirname(bridge_script)
        )
        self.node = node

        self.icon = None
        self._stop = threading.Event()
        self._gw_thread: threading.Thread | None = None
        self._gw_loop: asyncio.AbstractEventLoop | None = None

    # ---------- 状态文案 ----------

    def _ble_text(self) -> str:
        return BLE_TEXT.get(self.gateway.status()["ble"], self.gateway.status()["ble"])

    def _ws_text(self) -> str:
        return WS_TEXT.get(self.gateway.status()["ws"], self.gateway.status()["ws"])

    def _detail_text(self) -> str:
        s = self.gateway.status()
        if s["mtu"]:
            return f"MTU {s['mtu']}"
        return s["ble_detail"] or "—"

    def _status_lines(self) -> list[str]:
        s = self.gateway.status()
        lines = [f"BLE {self._ble_text()}", f"Bridge {self._ws_text()}"]
        lines.append(self._detail_text())
        lines.append("Bridge 进程 " + ("运行中" if self.bridge.running else "未运行"))
        return lines

    def _tooltip(self) -> str:
        return "WristDeck\n" + "\n".join(self._status_lines())

    def _color(self) -> tuple[int, int, int]:
        s = self.gateway.status()
        if s["ble"] == "ready":
            return STATE_COLORS["ready"]
        if s["last_err"] and s["ble"] == "idle" and not self.bridge.running:
            return STATE_COLORS["error"]
        return STATE_COLORS.get(s["ble"], STATE_COLORS["idle"])

    # ---------- 动作 ----------

    def _open_status(self, *_a) -> None:
        webbrowser.open(f"http://127.0.0.1:{self.port}")

    def _open_log(self, *_a) -> None:
        os.makedirs(gw_mod.LOG_DIR, exist_ok=True)
        try:
            os.startfile(gw_mod.LOG_DIR)  # noqa: S606 - Windows 资源管理器
        except Exception:
            pass

    def _restart_ble(self, *_a) -> None:
        self.log("托盘：重启 BLE 链路")
        self.gateway.request_ble_restart()

    def _restart_bridge(self, *_a) -> None:
        self.bridge.restart()

    def _toggle_autostart(self, *_a) -> None:
        want = not autostart_enabled()
        ok = set_autostart(want)
        self.log(f"开机自启 → {'开' if want else '关'}"
                 f"{'' if ok else '（写入注册表失败）'}")
        if self.icon:
            self.icon.update_menu()

    def _quit(self, *_a) -> None:
        self.log("托盘：退出")
        self._stop.set()
        self.bridge.stop()
        if self.icon:
            self.icon.stop()

    # ---------- 网关线程 ----------

    def _gw_main(self) -> None:
        loop = asyncio.new_event_loop()
        self._gw_loop = loop
        asyncio.set_event_loop(loop)

        async def runner() -> None:
            task = asyncio.create_task(self.gateway.run(), name="gateway")
            while not self._stop.is_set():
                await asyncio.sleep(0.3)
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

        try:
            loop.run_until_complete(runner())
        except Exception as e:
            self.log(f"网关线程退出异常：{type(e).__name__}: {e}")
        finally:
            try:
                loop.close()
            except Exception:
                pass

    def _menu(self):
        import pystray

        return pystray.Menu(
            pystray.MenuItem(lambda _i: f"BLE：{self._ble_text()}", None, enabled=False),
            pystray.MenuItem(lambda _i: f"Bridge：{self._ws_text()}", None, enabled=False),
            pystray.MenuItem(lambda _i: self._detail_text(), None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("打开状态页", self._open_status, default=True),
            pystray.MenuItem("打开日志目录", self._open_log),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("重启 BLE 链路", self._restart_ble),
            pystray.MenuItem("重启 Bridge", self._restart_bridge),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("开机自启", self._toggle_autostart,
                             checked=lambda _i: autostart_enabled()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("退出", self._quit),
        )

    def _tick(self) -> None:
        """每 2s 刷新图标颜色与菜单文案。"""
        while not self._stop.is_set():
            try:
                if self.icon:
                    rgb = self._color()
                    if rgb != self._last_color:
                        self.icon.icon = make_icon_image(rgb)
                        self._last_color = rgb
                    self.icon.title = self._tooltip()
                    self.icon.update_menu()
            except Exception:
                pass
            self._stop.wait(2.0)

    def run(self) -> int:
        import pystray

        self.log("=== WristDeck 托盘启动 ===")
        self.log(f"节点 node = {self.node or '未找到'}")
        self.log(f"Bridge 脚本 = {self.bridge.script}")
        self.log(f"状态页 http://127.0.0.1:{self.port}")

        self._gw_thread = threading.Thread(target=self._gw_main, name="gateway", daemon=True)
        self._gw_thread.start()

        if not getattr(self, "_no_bridge", False):
            self.bridge.start()

        self._last_color = self._color()
        self.icon = pystray.Icon(
            "WristDeck",
            icon=make_icon_image(self._last_color),
            title=self._tooltip(),
            menu=self._menu(),
        )
        threading.Thread(target=self._tick, name="tick", daemon=True).start()
        try:
            self.icon.run()
        finally:
            self._stop.set()
            self.bridge.stop()
        return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="WristDeck 托盘（Windows）")
    ap.add_argument("--console", action="store_true", help="强制打屏（调试用，默认也打）")
    ap.add_argument("--no-bridge", action="store_true", help="不托管 Node Bridge")
    ap.add_argument("--node", default=None, help="node.exe 路径（默认从 PATH 找）")
    ap.add_argument("--port", type=int, default=None, help="覆盖 Bridge 端口")
    args = ap.parse_args(argv)

    handle = acquire_single_instance()
    if handle is None:
        # 已经有实例在跑：把已经在托盘里的那个提示出来即可，不重复启动
        try:
            ctypes.windll.user32.MessageBoxW(
                None, "WristDeck 已经在托盘中运行了。", "WristDeck", 0x40
            )
        except Exception:
            pass
        return 0

    app = TrayApp(args)
    app._no_bridge = args.no_bridge
    return app.run()


if __name__ == "__main__":
    raise SystemExit(main())
