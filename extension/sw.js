const BRIDGE_URL = 'ws://127.0.0.1:8787/ws?role=exec';
const RECONNECT_MIN = 1000;
const RECONNECT_MAX = 10000;

/* 与 manifest.content_scripts.matches 保持一致：http/https 全站注入。
 * 只有落在这个范围内的标签页才可能有内容脚本，也才允许 scripting 补注入。 */
const PAGE_RE = /^https?:\/\//;

/* like / fullscreen 依赖抖音自己的快捷键，属于站点能力，只在抖音域放行。
 * 方向键与播放/暂停**不在此列**：前者是原生键盘事件，后者走通用 HTML5 <video>，
 * 任何网页都该能用 —— 把它们锁在站点白名单里是设计错误。 */
const DOUYIN_RE = /^https:\/\/[^/]*\.douyin\.com(\/|$)/;
const SITE_ONLY = { like: DOUYIN_RE, fullscreen: DOUYIN_RE };

let ws = null;
let reconnectDelay = RECONNECT_MIN;
let lockedTabId = null;
const recent = [];

const state = {
  connected: false,
  lastError: null,
  lastSeen: 0,
};

// ---------- 连接 ----------

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  ws = new WebSocket(BRIDGE_URL);

  ws.onopen = () => {
    state.connected = true;
    state.lastError = null;
    reconnectDelay = RECONNECT_MIN;
    send({ t: 'hello', role: 'exec', dev: 'chrome-extension' });
  };

  ws.onmessage = (ev) => {
    state.lastSeen = Date.now();
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.t === 'fwd') handleForward(msg);
    else if (msg.t === 'ping') send({ t: 'pong', id: msg.id });
    else if (msg.t === 'denied') state.lastError = msg.reason;
  };

  ws.onclose = () => {
    state.connected = false;
    ws = null;
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX);
  };

  ws.onerror = () => {
    state.lastError = 'bridge_unreachable';
  };
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ v: 1, ...obj }));
    return true;
  }
  return false;
}

// ---------- 指令执行 ----------

async function targetTab() {
  if (lockedTabId !== null) {
    try {
      const tab = await chrome.tabs.get(lockedTabId);
      if (tab) return tab;
    } catch {
      lockedTabId = null;
    }
  }
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return tab || null;
}

function hostOf(url) {
  try {
    return new URL(url).host || 'blank';
  } catch {
    return 'unknown';
  }
}

async function dispatchToContent(tab, msg) {
  try {
    return await chrome.tabs.sendMessage(tab.id, msg);
  } catch (e) {
    return { ok: false, reason: 'no_content_script', detail: String(e.message || e) };
  }
}

/* 页面在扩展安装/重载之前就已打开时，声明式 content_scripts 不会补注入，
 * 表现为 sendMessage 抛 "Receiving end does not exist"。这里用 scripting 权限
 * 主动补一次再重试，省掉"必须先手动刷新页面"这条隐性要求。
 * content.js 内有幂等守卫，重复注入不会重复注册监听。 */
async function ensureContentScript(tab) {
  try {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['content.js'] });
    return true;
  } catch {
    return false;
  }
}

/** 记一条 recent 并把结果回给 Bridge —— 四种出口（no_tab / wrong_tab / unsupported_site / 常规）共用。 */
function finish(tab, msg, started, result) {
  const cost = Date.now() - started;
  const ok = Boolean(result && result.ok);
  recent.unshift({
    t: Date.now(),
    action: msg.a,
    ok,
    cost,
    title: (tab?.title || '').slice(0, 40),
    method: result?.method || null,
    reason: result?.reason || null,
    detail: result?.detail || null,
  });
  if (recent.length > 20) recent.pop();

  send({
    t: 'res',
    id: msg.id,
    ok,
    cost,
    state: result?.state || null,
    reason: result?.reason || null,
    detail: result?.detail || null,
  });
}

async function handleForward(msg) {
  const started = Date.now();
  const tab = await targetTab();

  if (!tab) {
    send({ t: 'res', id: msg.id, ok: false, reason: 'no_tab', cost: Date.now() - started });
    return;
  }

  /* 不是 http/https 页面（chrome://、扩展商店、空白页等）：既没有内容脚本，
   * 也没有权限补注入。必须给一条可诊断的原因 —— 否则它会和"页面没刷新"
   * 一起塌成 no_content_script，用户看不出到底是哪种。 */
  if (!tab.url || !PAGE_RE.test(tab.url)) {
    finish(tab, msg, started, { ok: false, reason: `wrong_tab:${hostOf(tab.url)}` });
    return;
  }

  const need = SITE_ONLY[msg.a];
  if (need && !need.test(tab.url)) {
    finish(tab, msg, started, { ok: false, reason: `unsupported_site:${hostOf(tab.url)}` });
    return;
  }

  const payload = { type: 'wrist', a: msg.a };
  let result = await dispatchToContent(tab, payload);

  if (result && result.reason === 'no_content_script') {
    if (await ensureContentScript(tab)) result = await dispatchToContent(tab, payload);
    // 补注入后仍失败，多半是页面正在导航，等加载完成后再试一次
    if (result && result.reason === 'no_content_script') {
      result = await retryAfterLoad(tab, msg, started);
    }
  }

  finish(tab, msg, started, result);
}

function retryAfterLoad(tab, msg, started) {
  return new Promise((resolve) => {
    const done = (res) => {
      chrome.tabs.onUpdated.removeListener(onUpdated);
      clearTimeout(timer);
      resolve(res);
    };
    const onUpdated = (tabId, info) => {
      if (tabId === tab.id && info.status === 'complete') {
        dispatchToContent(tab, { type: 'wrist', a: msg.a }).then(done);
      }
    };
    const timer = setTimeout(() => done({ ok: false, reason: 'no_content_script' }), 1200);
    chrome.tabs.onUpdated.addListener(onUpdated);
  });
}

// ---------- popup 通信 ----------

chrome.runtime.onMessage.addListener((msg, _sender, respond) => {
  if (msg?.type === 'wd_status') {
    /* 顺带回报"即将投递给哪个标签页"——no_content_script 这类问题的第一步排查
     * 就是确认扩展到底在看哪个标签页，不暴露出来就只能靠猜。 */
    targetTab()
      .then((tab) => respond({
        ...state,
        lockedTabId,
        recent,
        target: tab ? { id: tab.id, title: tab.title || '', url: tab.url || '' } : null,
      }))
      .catch(() => respond({ ...state, lockedTabId, recent, target: null }));
    return true;
  }
  if (msg?.type === 'wd_lock') {
    lockedTabId = msg.tabId ?? null;
    respond({ lockedTabId });
    return true;
  }
  if (msg?.type === 'wd_ping') {
    connect();
    respond({ ok: true });
    return true;
  }
});

// ---------- 保活 ----------

chrome.alarms.create('wd-keepalive', { periodInMinutes: 0.4 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== 'wd-keepalive') return;
  if (!ws || ws.readyState !== WebSocket.OPEN) connect();
  else if (Date.now() - state.lastSeen > 45000) {
    ws.close();
  }
});

chrome.runtime.onInstalled.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
connect();
