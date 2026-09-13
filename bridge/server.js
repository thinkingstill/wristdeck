import http from 'node:http';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, 'public');
const CONFIG_DIR = path.join(os.homedir(), '.wristbridge');
const CONFIG_FILE = path.join(CONFIG_DIR, 'config.json');

const HEARTBEAT_MS = 15_000;
const TIMEOUT_MS = 30_000;
const CMD_TIMEOUT_MS = 1_500;
// 指令超时后仍保留 id 关联的宽限期：执行器可能"慢到但已生效"，
// 用 cmd_late 事件把结果补偿给手表，避免"页面已切换、手表却报失败"。
const UNRESOLVED_TTL_MS = 10_000;
// 同一时刻允许的在途指令上限，防止连点造成回包乱序
const INFLIGHT_MAX = 3;
const LOG_LIMIT = 100;
const LAT_SAMPLE_LIMIT = 200;
const PIN_FAIL_LIMIT = 5;
const PIN_LOCK_MS = 60_000;

const ACTIONS = new Set([
  'toggle', 'play', 'pause',
  'up', 'down', 'left', 'right',
  'volUp', 'volDown', 'like', 'fullscreen', 'seekFwd5', 'seekBack5',
]);

const config = await loadConfig();

const state = {
  startedAt: Date.now(),
  seq: 0,
  watch: null,
  exec: null,
  log: [],
  latencies: [],
  stats: { sent: 0, ok: 0, fail: 0, denied: 0, late: 0 },
};
const pending = new Map();
const unresolved = new Map();
const pinFails = new Map();

// ---------- config ----------

async function loadConfig() {
  const base = { port: 8787, pin: null, bindAll: true };
  try {
    Object.assign(base, JSON.parse(await fsp.readFile(CONFIG_FILE, 'utf8')));
  } catch {
    // 首次运行
  }
  if (!base.pin) {
    base.pin = String(Math.floor(100_000 + Math.random() * 900_000));
    await saveConfig(base);
  }
  return base;
}

async function saveConfig(cfg) {
  await fsp.mkdir(CONFIG_DIR, { recursive: true });
  await fsp.writeFile(CONFIG_FILE, JSON.stringify(cfg, null, 2), 'utf8');
}

// ---------- helpers ----------

function lanAddress() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) return net.address;
    }
  }
  return '127.0.0.1';
}

/* 回环 = 本机信任域。
 * 手表必须走局域网（需 PIN 鉴权）；扩展跑在本机，强制验 PIN 只增加配置负担。
 * 这条边界同时决定状态页能否展示 PIN：只有本机请求才给。
 */
function isLocalIp(ip) {
  const v = String(ip || '').replace('::ffff:', '');
  return v === '127.0.0.1' || v === '::1' || v === 'localhost';
}

function reqIp(req) {
  return String(req.socket?.remoteAddress || '').replace('::ffff:', '');
}

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify({ v: 1, ...obj }));
    return true;
  }
  return false;
}

/* ---------- 日志输出 ----------
 * 操作日志原本**只写进 state.log**（由 /api/log 与状态页消费），从不打到终端，
 * 所以 `node server.js` 跑起来只有一行启动横幅，手表点了什么完全看不见 ——
 * 想看就得另开窗口 curl /api/log。这里补上实时输出。
 *
 * 只在 stdout 是 TTY 时着色：`node server.js > bridge.log 2>&1` 重定向出去时
 * 留纯文本，方便 grep/awk。`--quiet` 或 WD_QUIET=1 可关掉控制台输出
 * （/api/log 与状态页照旧保留）。 */
const LOG_STDOUT = process.env.WD_QUIET !== '1' && !process.argv.includes('--quiet');
const ANSI = Boolean(process.stdout.isTTY) && !process.env.NO_COLOR;
const paint = (code, s) => (ANSI ? `\x1b[${code}m${s}\x1b[0m` : s);
const SRC_LABEL = { watch: '手表', exec: '扩展', bridge: '服务', sys: '系统' };

function printLog(r) {
  const hhmmss = new Date(r.t).toLocaleTimeString('zh-CN', { hour12: false });
  // 三个来源标签都是两个等宽汉字，天然对齐，不必按显示宽度补空格
  const src = SRC_LABEL[r.src] || String(r.src || '系统').slice(0, 2);
  const mark = r.ok ? paint(32, '√') : paint(31, '×');
  const note = r.note || (r.ok ? 'ok' : 'failed');
  const detail = r.detail ? paint(90, ` (${r.detail})`) : '';
  console.log(`${paint(90, hhmmss)}  ${src}  ${r.action}  ${r.ms}ms  ${mark} ${note}${detail}`);
}

function pushLog(entry) {
  const record = { t: Date.now(), ...entry };
  state.log.push(record);
  if (state.log.length > LOG_LIMIT) state.log.splice(0, state.log.length - LOG_LIMIT);
  if (LOG_STDOUT) printLog(record);
}

function recordLatency(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0 || ms > 10_000) return;
  state.latencies.push(ms);
  if (state.latencies.length > LAT_SAMPLE_LIMIT) state.latencies.shift();
}

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return Math.round(sorted[idx]);
}

function summary() {
  const sorted = [...state.latencies].sort((a, b) => a - b);
  return { p50: percentile(sorted, 50), p95: percentile(sorted, 95), n: sorted.length };
}

function clientView(session) {
  if (!session) return null;
  return {
    id: session.id,
    dev: session.dev,
    ip: session.ip,
    uptime: Date.now() - session.connectedAt,
  };
}

// ---------- websocket ----------

const server = http.createServer(handleHttp);
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws, req) => {
  const session = {
    id: ++state.seq,
    ws,
    role: null,
    dev: null,
    ip: reqIp(req),
    local: isLocalIp(reqIp(req)),
    alive: true,
    connectedAt: Date.now(),
  };

  ws.on('pong', () => { session.alive = true; });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return send(ws, { t: 'denied', reason: 'bad_json', code: 4005 });
    }
    handleMessage(session, msg);
  });

  ws.on('close', () => {
    if (session.role === 'watch' && state.watch === session) state.watch = null;
    if (session.role === 'exec' && state.exec === session) state.exec = null;
    if (session.role) {
      pushLog({ src: session.role, action: '-', ms: 0, ok: true, note: 'disconnected' });
      if (session.role === 'exec') broadcastExecState(false);
    }
  });

  ws.on('error', () => { /* close 事件会统一清理 */ });
});

function broadcastExecState(online) {
  if (state.watch) {
    send(state.watch.ws, { t: 'evt', e: online ? 'exec_online' : 'exec_offline' });
  }
}

function handleMessage(session, msg) {
  session.lastSeen = Date.now();
  if (!msg || typeof msg.t !== 'string') return;

  if (msg.t === 'hello') return handleHello(session, msg);
  if (!session.role) return send(session.ws, { t: 'denied', reason: 'need_hello', code: 4005 });

  switch (msg.t) {
    case 'cmd': return handleCmd(session, msg);
    case 'res': return handleRes(session, msg);
    case 'pong': return;
    case 'ping': return send(session.ws, { t: 'pong', id: msg.id });
    default: return send(session.ws, { t: 'denied', reason: 'unknown_type', code: 4005 });
  }
}

function handleHello(session, msg) {
  const role = msg.role;
  if (role !== 'watch' && role !== 'exec') {
    return send(session.ws, { t: 'denied', reason: 'unknown_role', code: 4002 });
  }

  const isLoopback = session.local;
  if (!isLoopback) {
    const fails = pinFails.get(session.ip);
    if (fails && Date.now() < fails.until) {
      return send(session.ws, { t: 'denied', reason: 'pin_locked', code: 4001 });
    }
    if (String(msg.pin || '') !== config.pin) {
      const rec = pinFails.get(session.ip) || { count: 0, until: 0 };
      rec.count += 1;
      if (rec.count >= PIN_FAIL_LIMIT) rec.until = Date.now() + PIN_LOCK_MS;
      pinFails.set(session.ip, rec);
      state.stats.denied += 1;
      pushLog({ src: role, action: 'hello', ms: 0, ok: false, note: `pin_failed(${rec.count})` });
      return send(session.ws, { t: 'denied', reason: 'bad_pin', code: 4001 });
    }
    pinFails.delete(session.ip);
  }

  session.role = role;
  session.dev = typeof msg.dev === 'string' ? msg.dev.slice(0, 40) : role;

  if (role === 'watch') {
    if (state.watch && state.watch !== session) {
      send(state.watch.ws, { t: 'denied', reason: 'replaced', code: 4006 });
      state.watch.ws.close();
      pushLog({ src: 'watch', action: 'hello', ms: 0, ok: true, note: 'replaced_by_new_watch' });
    }
    state.watch = session;
  } else {
    if (state.exec && state.exec !== session) {
      send(state.exec.ws, { t: 'denied', reason: 'replaced', code: 4006 });
      state.exec.ws.close();
      pushLog({ src: 'exec', action: 'hello', ms: 0, ok: true, note: 'replaced_by_new_exec' });
    }
    state.exec = session;
    broadcastExecState(true);
  }

  pushLog({ src: role, action: 'hello', ms: 0, ok: true, note: session.dev });
  send(session.ws, {
    t: 'welcome',
    sid: String(session.id),
    cfg: { hb: HEARTBEAT_MS / 1000, execOnline: Boolean(state.exec) },
  });
}

function handleCmd(session, msg) {
  if (session.role !== 'watch') return;
  if (!ACTIONS.has(msg.a)) {
    return send(session.ws, { t: 'ack', id: msg.id, ok: false, reason: 'bad_action', code: 4005 });
  }

  const recvAt = Date.now();
  const id = typeof msg.id === 'string' && msg.id ? msg.id : `c${state.seq}-${recvAt}`;
  state.stats.sent += 1;

  if (!state.exec) {
    state.stats.fail += 1;
    pushLog({ src: 'watch', action: msg.a, ms: 0, ok: false, note: 'exec_offline' });
    return send(session.ws, {
      t: 'ack', id, ok: false, reason: 'exec_offline', code: 4003,
      lat: { net: 0, exec: 0, e2e: 0 },
    });
  }

  if (pending.size >= INFLIGHT_MAX) {
    state.stats.fail += 1;
    pushLog({ src: 'watch', action: msg.a, ms: 0, ok: false, note: 'busy' });
    return send(session.ws, {
      t: 'ack', id, ok: false, reason: 'busy', code: 4007,
      lat: { net: 0, exec: 0, e2e: 0 },
    });
  }

  const timer = setTimeout(() => {
    const entry = pending.get(id);
    pending.delete(id);
    if (entry) unresolved.set(id, { ...entry, timer: null, timedOutAt: Date.now() });
    state.stats.fail += 1;
    pushLog({ src: 'watch', action: msg.a, ms: CMD_TIMEOUT_MS, ok: false, note: 'exec_timeout' });
    // 明确告知"未确认"：执行器可能稍后才回，届时用 cmd_late 补偿
    send(session.ws, {
      t: 'ack', id, ok: false, reason: 'exec_timeout', code: 4004, unresolved: true,
      lat: { net: 0, exec: CMD_TIMEOUT_MS, e2e: CMD_TIMEOUT_MS },
    });
  }, CMD_TIMEOUT_MS);

  pending.set(id, { session, action: msg.a, recvAt, timer, watchTs: msg.ts });
  send(state.exec.ws, { t: 'fwd', id, a: msg.a, ts: recvAt });

  /* 补一条"指令已收下"。此前**成功转发不记任何日志**，日志里只有回包那一条
   * （`src:'exec'`），于是终端/状态页只能看到"扩展 right ×no_change"，
   * 看不到"手表按了 right"——正是"为什么不显示手表操作日志"的一半原因。
   * 失败分支（exec_offline / busy / exec_timeout）本来就有自己的条目，不会重复。 */
  pushLog({ src: 'watch', action: msg.a, ms: 0, ok: true, note: 'sent' });
}

/* 超时后才回来的执行结果：不再当成失败丢弃（那会让"页面已切换、手表报失败"），
 * 也不补发 ack（会和后续指令的 ack 抢顺序），只发一条 cmd_late 事件让手表静默纠正图标。
 */
function handleLateRes(msg) {
  const late = unresolved.get(msg.id);
  if (!late) return;
  unresolved.delete(msg.id);

  const now = Date.now();
  const e2e = now - late.recvAt;
  const playing = msg.state && typeof msg.state.playing === 'boolean' ? msg.state.playing : null;

  state.stats.late += 1;
  if (msg.ok) state.stats.ok += 1;
  pushLog({
    src: 'exec', action: late.action, ms: e2e, ok: Boolean(msg.ok),
    note: `late:${msg.reason || (msg.ok ? 'ok' : 'failed')}`,
    detail: msg.detail || null,
  });

  const target = state.watch || late.session;
  send(target?.ws, {
    t: 'evt', e: 'cmd_late', id: msg.id, ok: Boolean(msg.ok),
    state: playing === null ? null : { playing },
  });
}

function handleRes(session, msg) {
  if (session.role !== 'exec') return;
  const entry = pending.get(msg.id);
  if (!entry) return handleLateRes(msg);
  clearTimeout(entry.timer);
  pending.delete(msg.id);

  const now = Date.now();
  const e2e = now - entry.recvAt;
  const net = typeof entry.watchTs === 'number'
    ? Math.min(Math.max(entry.recvAt - entry.watchTs, 0), 5000)
    : 0;
  const execCost = typeof msg.cost === 'number' ? Math.max(0, Math.min(msg.cost, 5000)) : e2e;

  recordLatency(e2e);
  if (msg.ok) state.stats.ok += 1; else state.stats.fail += 1;
  pushLog({
    src: 'exec', action: entry.action, ms: e2e, ok: Boolean(msg.ok),
    note: msg.reason || (msg.ok ? 'ok' : 'failed'),
    /* 扩展回包里的失败现场（no_change 时的 video=/scroll=/blocked=/host=）一起透传，
     * 否则终端上只能看到 "× no_change"，还得回头去扩展 popup 里找原因。 */
    detail: msg.detail || null,
  });

  send(entry.session.ws, {
    t: 'ack',
    id: msg.id,
    ok: Boolean(msg.ok),
    state: msg.state || null,
    reason: msg.reason || null,
    lat: { net, exec: execCost, e2e },
  });
}

// ---------- heartbeat ----------

setInterval(() => {
  const now = Date.now();
  for (const session of [state.watch, state.exec]) {
    if (!session) continue;
    if (now - (session.lastSeen || session.connectedAt) > TIMEOUT_MS) {
      session.ws.terminate();
      continue;
    }
    send(session.ws, { t: 'ping', id: String(now) });
    if (session.ws.readyState === 1) session.ws.ping();
  }

  // 超时指令的宽限期一到就丢弃 id 关联，避免 map 无限增长
  for (const [id, entry] of unresolved) {
    if (now - entry.timedOutAt > UNRESOLVED_TTL_MS) unresolved.delete(id);
  }
}, HEARTBEAT_MS).unref?.();

// ---------- http ----------

function handleHttp(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (url.pathname === '/api/status') {
    // 状态页监听在 0.0.0.0：非本机请求不返回 PIN，否则局域网内一次 GET 就能拿到配对码，
    // §10 的"PIN + 失败锁定"会形同虚设。
    const local = isLocalIp(reqIp(req));
    return json(res, {
      pin: local ? config.pin : null,
      pinHidden: !local,
      port: config.port,
      lan: lanAddress(),
      uptime: Date.now() - state.startedAt,
      watch: clientView(state.watch),
      exec: clientView(state.exec),
      stats: state.stats,
      latency: summary(),
    });
  }

  if (url.pathname === '/api/log') {
    return json(res, { log: state.log.slice(-50) });
  }

  if (url.pathname === '/' || url.pathname === '/index.html') {
    return sendFile(res, path.join(PUBLIC_DIR, 'index.html'), 'text/html; charset=utf-8');
  }

  res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ error: 'not_found' }));
}

function json(res, data) {
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(data));
}

async function sendFile(res, file, type) {
  try {
    const body = await fsp.readFile(file);
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(body);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('not found');
  }
}

// ---------- boot ----------

server.listen(config.port, config.bindAll ? '0.0.0.0' : '127.0.0.1', () => {
  const lan = lanAddress();
  const bar = '-'.repeat(46);
  console.log(bar);
  console.log(' WristDeck Bridge 已启动');
  console.log(bar);
  console.log(` 状态页      http://localhost:${config.port}`);
  console.log(` 局域网地址  ws://${lan}:${config.port}/ws`);
  console.log(` 配对 PIN    ${config.pin}`);
  console.log('             （仅在 http://localhost 打开的状态页显示，局域网内其它设备取不到）');
  console.log(` 配置文件    ${CONFIG_FILE}`);
  console.log(` 控制台日志  ${LOG_STDOUT ? '实时输出指令流水（--quiet 或 WD_QUIET=1 可关，关掉后状态页照旧）' : '已关闭（--quiet）'}`);
  console.log(bar);
  console.log(' 等待手表 (role=watch) 与浏览器扩展 (role=exec) 接入...');
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log('\n正在关闭...');
    wss.close();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1000).unref();
  });
}

