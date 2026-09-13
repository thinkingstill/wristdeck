/* WristDeck content script —— 注入范围为 http/https 全站（不是只抖音）
 * 把 Bridge 转发的动作翻译成页面内操作。
 * 策略：能直接操作 video 元素的就操作元素（不依赖键盘事件可信度）；
 *      方向键走键盘事件 + 页面是否真的动了来校验，普通网页再补原生滚动。
 */

/* 整文件包一层 IIFE + 幂等守卫。
 * 为什么必须这样：扩展安装/重载前就已打开的页面不会被声明式 content_scripts 覆盖，
 * sw.js 会用 chrome.scripting.executeScript 补注入同一份文件；而顶层 const 重复声明
 * 会直接抛 SyntaxError，导致补注入这条路彻底失效。守卫挂在隔离世界的 globalThis 上，
 * 不污染页面。 */
(() => {
if (globalThis.__wristdeckContent) return;
globalThis.__wristdeckContent = true;

/* 方向键是原样转发，不做站点语义翻译：页面收到 ArrowUp 后要翻页还是滚动，
 * 由页面自己决定。这里只负责把事件派发对、把结果校验准。 */
const ARROWS = {
  up: { key: 'ArrowUp', code: 'ArrowUp', keyCode: 38 },
  down: { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40 },
  left: { key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37 },
  right: { key: 'ArrowRight', code: 'ArrowRight', keyCode: 39 },
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 元素定位 ----------

function pickVideo() {
  const vids = Array.from(document.querySelectorAll('video'));
  if (!vids.length) return null;

  const cx = window.innerWidth / 2;
  const cy = window.innerHeight / 2;
  let best = null;
  let bestScore = Infinity;

  for (const v of vids) {
    const r = v.getBoundingClientRect();
    const visible =
      r.width > 40 && r.height > 40 &&
      r.bottom > 0 && r.top < window.innerHeight &&
      r.right > 0 && r.left < window.innerWidth;
    if (!visible) continue;

    const dist = Math.hypot(r.left + r.width / 2 - cx, r.top + r.height / 2 - cy);
    const score = (v.paused ? 1e9 : 0) + dist * 1000 - r.width * r.height * 0.01;
    if (score < bestScore) {
      bestScore = score;
      best = v;
    }
  }
  return best || vids[0];
}

function videoSrc(v) {
  if (!v) return '';
  return v.currentSrc || v.src || v.dataset?.src || '';
}

/* 派发目标优先选当前 video 元素：
 * 它一定在页面的 React 根容器内，事件冒泡会经过根容器、document、window，
 * 覆盖绝大多数前端监听写法（比派发到 body 更可靠）。
 */
function keyTarget() {
  const v = pickVideo();
  if (v) return v;
  const el = document.activeElement;
  if (el && el !== document.body) return el;
  return document.body;
}

function fireKey(key, code, keyCode) {
  const target = keyTarget();
  const ev = new KeyboardEvent('keydown', {
    key,
    code,
    keyCode,
    which: keyCode,
    bubbles: true,
    cancelable: true,
    composed: true,
  });
  if (ev.keyCode !== keyCode) {
    try {
      Object.defineProperty(ev, 'keyCode', { get: () => keyCode });
      Object.defineProperty(ev, 'which', { get: () => keyCode });
    } catch {
      /* 只读属性时忽略，多数站点不依赖 keyCode */
    }
  }
  target.dispatchEvent(ev);
  return ev;
}

// ---------- 动作 ----------

async function doToggle() {
  const v = pickVideo();
  if (v) {
    try {
      if (v.paused) await v.play();
      else v.pause();
      return { ok: true, method: 'video', state: { playing: !v.paused } };
    } catch {
      /* 自动播放策略或元素被替换，降级到键盘事件 */
    }
  }
  fireKey(' ', 'Space', 32);
  await sleep(150);
  const after = pickVideo();
  return { ok: true, method: 'key', state: { playing: after ? !after.paused : null } };
}

async function doPlayPause(forcePlay) {
  const v = pickVideo();
  if (v) {
    try {
      if (forcePlay) await v.play();
      else v.pause();
      return { ok: true, method: 'video', state: { playing: forcePlay } };
    } catch {
      /* 落到下面 */
    }
  }

  // 键盘兜底：Space 是"翻转"而不是"设为"。手表发来的 play/pause 是显式目标状态，
  // 若当前已经是目标状态就什么都不做，否则会把状态按反方向切过去。
  const cur = pickVideo();
  if (!cur) return { ok: false, reason: 'no_video' };
  if (!cur.paused === forcePlay) {
    return { ok: true, method: 'video', state: { playing: forcePlay } };
  }

  fireKey(' ', 'Space', 32);
  await sleep(150);
  const after = pickVideo();
  const playing = after ? !after.paused : !forcePlay;
  return { ok: playing === forcePlay, method: 'key', state: { playing } };
}

/* 找到页面**真正在滚的那个元素** —— 按方向找，不是笼统找一个。
 *
 * 只看 document.scrollingElement 不够：现代 SPA（抖音 / YouTube / X / 各种后台）
 * 普遍把 <html>/<body> 设成高度 100% + 不可滚，真正的滚动发生在某个内层容器上。
 * 那样会得出"页面不可滚"的错误结论，方向键在普通网页上照样失败。
 *
 * 三处容易漏掉、但真实网站天天在用的写法，这里都覆盖：
 *  ① overflow:hidden 的容器。它**仍然是滚动容器**，scrollTop/scrollBy 照样生效
 *     （只是滚轮和键盘不驱动它，得靠 JS）。只认 auto/scroll/overlay 会漏掉一大批
 *     用 overflow:hidden + 脚本滚动搭起来的页面。
 *  ② shadow DOM。元素在 shadow root 里时 parentElement 直接是 null，祖先链断掉，
 *     必须用 getRootNode().host 接着往上爬。
 *  ③ 只横向溢出的页面（宽表格、横向文档）。原来只用"竖向有没有溢出"筛选，
 *     这类页面的滚动容器永远选不出来，左右方向键必然 no_change。
 *
 * 祖先链里同时有多个候选时取**面积最大的那个**：视口里通常还套着若干
 * overflow:hidden 的小裁剪框（播放器外框、圆角遮罩），它们的 clientHeight 只有
 * 几百像素，而真正的滚动宿主基本铺满视口。按面积取最大可天然跳过这些小框。
 */
function scrollHost(dir) {
  const vertical = dir === 'up' || dir === 'down';
  const de = document.scrollingElement || document.documentElement;

  // 这个元素在**该方向上**是否真的有溢出余量
  const room = (el) => (vertical
    ? el.scrollHeight - el.clientHeight > 2
    : el.scrollWidth - el.clientWidth > 2);

  let best = null;
  let bestArea = 0;

  // 1) 从视口中心出发沿祖先链找：中间那一列通常就是滚动容器
  let el = null;
  try {
    el = document.elementFromPoint(Math.round(innerWidth / 2), Math.round(innerHeight / 2));
  } catch {
    el = null;
  }
  let guard = 0;
  while (el && el !== document.documentElement && guard++ < 100) {
    if (room(el) && scrollable(el, vertical)) {
      const r = el.getBoundingClientRect();
      const a = r.width * r.height;
      if (a > bestArea) {
        bestArea = a;
        best = el;
      }
    }
    el = el.parentElement ||
      (el.getRootNode() instanceof ShadowRoot ? el.getRootNode().host : null);
  }
  if (best) return best;

  // 2) 整页滚动的老式页面（<html> 的 overflow 通常是默认的 visible，不能用
  //    scrollable() 卡它，直接看有没有溢出余量）
  if (room(de)) return de;

  // 3) 兜底：扫一遍整页，取面积最大的、这个方向上真有溢出的容器
  for (const c of document.querySelectorAll('div, main, section, article')) {
    if (!room(c) || !scrollable(c, vertical)) continue;
    const r = c.getBoundingClientRect();
    if (r.width < 80 || r.height < 80) continue;
    const a = r.width * r.height;
    if (a > bestArea) {
      bestArea = a;
      best = c;
    }
  }
  return best || de;
}

/* overflow:hidden 也算可滚：它只是不接受滚轮/键盘驱动，脚本仍然滚得动，
 * 而这里本来就走脚本滚动。overflow:clip 才是真的滚不动，必须排除。 */
function scrollable(el, vertical) {
  const s = getComputedStyle(el);
  const v = vertical ? s.overflowY : s.overflowX;
  return v === 'auto' || v === 'scroll' || v === 'overlay' || v === 'hidden';
}

/* 读一份"页面状态快照"，用来判断方向键到底有没有产生效果。
 * 三类迹象都要采：视频换源（抖音/YouTube 式翻页）、播放进度位移（快进退）、
 * 页面滚动位移（普通网页，方向键本来就是滚动）。 */
function snapshot(dir = 'down') {
  const v = pickVideo();
  const host = scrollHost(dir);
  return {
    hasVideo: Boolean(v),
    src: v ? videoSrc(v) : '',
    time: v ? v.currentTime : 0,
    host,
    top: host ? Math.round(host.scrollTop) : 0,
    left: host ? Math.round(host.scrollLeft) : 0,
    playing: v ? !v.paused : null,
  };
}

function pageMoved(a, b) {
  if (a.hasVideo && b.hasVideo && a.src && b.src && a.src !== b.src) return true;
  if (a.hasVideo && b.hasVideo && Math.abs(b.time - a.time) > 0.05) return true;
  if (Math.abs(b.top - a.top) > 2) return true;
  if (Math.abs(b.left - a.left) > 2) return true;
  return false;
}

/* 这个方向上还有没有可滚的余量（是否已到顶/到底/本来就不可滚）。 */
function canScroll(host, dir) {
  if (!host) return false;
  if (dir === 'up' || dir === 'down') {
    const max = host.scrollHeight - host.clientHeight;
    if (max <= 2) return false;
    return dir === 'down' ? host.scrollTop < max - 2 : host.scrollTop > 2;
  }
  const maxX = host.scrollWidth - host.clientWidth;
  if (maxX <= 2) return false;
  return dir === 'right' ? host.scrollLeft < maxX - 2 : host.scrollLeft > 2;
}

/* 合成 KeyboardEvent 的 isTrusted = false，**不会触发浏览器默认动作**，
 * 所以普通网页上"按了 ↓ 但页面没滚"是必然结果，不是页面坏了。
 * 页面自己接管了（preventDefault）就尊重它；否则这里补一次原生滚动，
 * 让方向键在任意网页都符合直觉。
 *
 * 用 scrollBy({behavior:'instant'})，不用赋值 scrollTop —— 页面若设了
 * CSS `scroll-behavior: smooth`，赋值会变成动画，当场读回 scrollTop 拿到的是旧值。 */
function applyScroll(host, dir) {
  if (!host) return;
  const step = Math.round(window.innerHeight * 0.85);
  if (dir === 'up' || dir === 'down') {
    host.scrollBy({ top: dir === 'down' ? step : -step, behavior: 'instant' });
  } else {
    host.scrollBy({ left: dir === 'right' ? step : -step, behavior: 'instant' });
  }
}

/* 方向键：原样转发 Arrow 系列，然后校验页面到底动没动。
 * 校验不过就报 no_change，让手表震失败 —— 比"假装点了"更有用。
 * 这里**不再要求页面必须有 video**：方向键与视频无关，普通网页同样要能用。 */
async function doArrow(dir) {
  const spec = ARROWS[dir];
  if (!spec) return { ok: false, reason: 'unknown_action' };

  const vertical = dir === 'up' || dir === 'down';
  const before = snapshot(dir);

  const ev = fireKey(spec.key, spec.code, spec.keyCode);
  // 翻页要等新视频挂上 src，快进退只需等 currentTime 落位，等待时长分开给
  await sleep(vertical ? 320 : 260);

  const after = snapshot(dir);
  if (pageMoved(before, after)) {
    return { ok: true, method: 'key', state: { playing: after.playing } };
  }

  /* 走到这里说明页面一点动静都没有。此时只要"页面自己没接管"（没 preventDefault）
   * 且这个方向还有滚动余量，就用原生滚动复现"方向键本来就该有的效果" ——
   * 这才对得起"任意网页都能用方向键"这个要求。
   *
   * 门闸为什么不用"页面里有没有 video"：有没有视频只是场景提示，不是控制权的证据。
   * 新闻页挂一个内嵌视频、视频在视口外没露脸、站点自己换了播放器实现……都会让
   * after.hasVideo 为真，却和"方向键该不该滚页面"毫无关系，结果就是键白按。
   * 真正可信的"站点接管了"信号是 preventDefault：网站要自己吃箭头键，
   * 一定会阻止默认行为。没阻止 + 页面没动 = 站点压根没管这个键。 */
  const canScrollNow = canScroll(after.host, dir);
  if (!ev.defaultPrevented && canScrollNow) {
    applyScroll(after.host, dir);
    await sleep(60);
    if (Math.round(after.host.scrollTop) !== after.top ||
        Math.round(after.host.scrollLeft) !== after.left) {
      return { ok: true, method: 'scroll', state: { playing: after.playing } };
    }
  }

  return {
    ok: false,
    method: 'key',
    state: { playing: after.playing },
    reason: 'no_change',
    /* 给 Bridge 日志/扩展 popup 留一份现场：video=页面里有视频、scroll=该方向还有
     * 滚动余量、blocked=页面自己 preventDefault 接管了、host=滚动宿主的类型。
     * 排查"为什么没反应"时这四项基本能一眼定位。 */
    detail: `video=${after.hasVideo ? 1 : 0} scroll=${canScrollNow ? 1 : 0}` +
      ` blocked=${ev.defaultPrevented ? 1 : 0}` +
      ` host=${after.host === (document.scrollingElement || document.documentElement) ? 'doc' : 'inner'}`,
  };
}

async function doVolume(delta) {
  const v = pickVideo();
  if (!v) return { ok: false, reason: 'no_video' };
  v.muted = false;
  v.volume = Math.min(1, Math.max(0, Math.round((v.volume + delta) * 10) / 10));
  return { ok: true, method: 'video', state: { volume: v.volume } };
}

async function doSeek(seconds) {
  const v = pickVideo();
  if (!v) return { ok: false, reason: 'no_video' };
  v.currentTime = Math.min((v.duration || 0), Math.max(0, v.currentTime + seconds));
  return { ok: true, method: 'video', state: { currentTime: Math.round(v.currentTime) } };
}

// 以下动作依赖站点快捷键实现（来源为公开教程，未经官方确认）
async function doShortcut(key, code, keyCode) {
  fireKey(key, code, keyCode);
  await sleep(150);
  return { ok: true, method: 'key' };
}

function execute(action) {
  switch (action) {
    case 'toggle': return doToggle();
    case 'play': return doPlayPause(true);
    case 'pause': return doPlayPause(false);
    case 'up': return doArrow('up');
    case 'down': return doArrow('down');
    case 'left': return doArrow('left');
    case 'right': return doArrow('right');
    case 'volUp': return doVolume(0.1);
    case 'volDown': return doVolume(-0.1);
    case 'seekFwd5': return doSeek(5);
    case 'seekBack5': return doSeek(-5);
    case 'like': return doShortcut('z', 'KeyZ', 90);
    case 'fullscreen': return doShortcut('h', 'KeyH', 72);
    default: return Promise.resolve({ ok: false, reason: 'unknown_action' });
  }
}

// ---------- 消息入口 ----------

chrome.runtime.onMessage.addListener((msg, _sender, respond) => {
  if (!msg || msg.type !== 'wrist') return;

  const t0 = performance.now();
  execute(msg.a)
    .then((result) => respond({ ...result, cost: Math.round(performance.now() - t0) }))
    .catch((e) => respond({
      ok: false,
      reason: String((e && e.message) || e),
      cost: Math.round(performance.now() - t0),
    }));

  return true;
});

})();
