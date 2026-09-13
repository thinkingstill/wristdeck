/* 用最小 DOM 桩跑真实的 extension/content.js，验证方向键在几类页面上的行为。
 * jsdom 没有布局引擎（scrollHeight/clientHeight 恒为 0），这里干脆自己桩一个
 * 可控的元素模型：谁有溢出、谁的盒子多大，都由测试指定，断言才有意义。
 *
 * 跑法：node .workbuddy/tests/scroll-host.test.mjs
 */
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(here, '..', '..', 'extension', 'content.js');
const code = fs.readFileSync(SRC, 'utf8');

// ---------- 最小元素模型 ----------

function el(name, { oy = 'visible', ox = 'visible', w = 0, h = 0, sh = 0, sw = 0 } = {}) {
  return {
    name,
    parentElement: null,
    _root: null,
    scrollTop: 0,
    scrollLeft: 0,
    scrollHeight: sh,
    clientHeight: h,
    scrollWidth: sw,
    clientWidth: w,
    _oy: oy,
    _ox: ox,
    getBoundingClientRect() {
      return { width: w, height: h, top: 0, left: 0, right: w, bottom: h };
    },
    scrollBy(o = {}) {
      this.scrollTop += o.top || 0;
      this.scrollLeft += o.left || 0;
    },
    getRootNode() {
      return this._root;
    },
    dispatchEvent() {
      return true;
    },
    addEventListener() {},
  };
}

function chain(list) {
  for (let i = 0; i < list.length - 1; i++) list[i].parentElement = list[i + 1];
  return list;
}

// ---------- 每个用例搭一套 document ----------

function makeEnv({ center, allDivs, scrollingElement, viewport = 800 }) {
  const doc = {
    scrollingElement,
    documentElement: scrollingElement,
    body: scrollingElement,
    activeElement: null,
    elementFromPoint: () => center,
    querySelectorAll: (sel) => (sel === 'video' ? [] : allDivs),
  };
  return {
    doc,
    document: doc,
    innerWidth: 1200,
    innerHeight: viewport,
    window: { innerWidth: 1200, innerHeight: viewport },
    getComputedStyle: (e) => ({ overflowY: e._oy, overflowX: e._ox }),
    performance: { now: () => Date.now() },
    setTimeout,
    clearTimeout,
    console,
  };
}

class FakeKeyboardEvent {
  constructor(type, init = {}) {
    this.type = type;
    this.key = init.key;
    this.code = init.code;
    this.keyCode = init.keyCode ?? 0;
    this.which = init.which ?? 0;
    this.bubbles = !!init.bubbles;
    this.cancelable = !!init.cancelable;
    this.composed = !!init.composed;
    this.defaultPrevented = false;
  }
  preventDefault() {
    this.defaultPrevented = true;
  }
  stopPropagation() {}
}

/* 把 content.js 装进一个隔离上下文，返回消息入口。 */
function load(env) {
  const listeners = [];
  const ctx = vm.createContext({
    ...env,
    KeyboardEvent: FakeKeyboardEvent,
    ShadowRoot: class ShadowRoot {},
    requestAnimationFrame: (fn) => setTimeout(fn, 0),
    globalThis: null,
    chrome: {
      runtime: {
        onMessage: {
          addListener: (fn) => listeners.push(fn),
        },
      },
    },
  });
  ctx.globalThis = ctx;
  vm.runInContext(code, ctx, { filename: 'content.js' });
  if (!listeners.length) throw new Error('content.js 没有注册 onMessage 监听');
  return (a) =>
    new Promise((resolve) => {
      listeners[0]({ type: 'wrist', a }, {}, resolve);
    });
}

// ---------- 断言 ----------

let failed = 0;
function check(label, cond, extra = '') {
  const tag = cond ? 'PASS' : 'FAIL';
  if (!cond) failed++;
  console.log(`  [${tag}] ${label}${extra ? ' — ' + extra : ''}`);
}

// ================= 用例 1：普通文档流页面 =================
console.log('\n用例 1  普通页面（<html> 就是滚动容器）');
{
  const html = el('html', { oy: 'visible', w: 1200, h: 800, sh: 3000, sw: 1200 });
  const p = el('p', { w: 1200, h: 3000, sh: 3000 });
  chain([p, html]);
  const env = makeEnv({ center: p, allDivs: [], scrollingElement: html });
  const run = load(env);

  const r = await run('down');
  check('down 成功', r.ok === true, JSON.stringify(r));
  check('走的是原生滚动兜底', r.method === 'scroll', 'method=' + r.method);
  check('页面确实滚了', html.scrollTop > 0, 'scrollTop=' + html.scrollTop);
}

// ================= 用例 2：SPA — html/body 不可滚，真容器 overflow:hidden ======
console.log('\n用例 2  SPA（html,body 高度 100% 不可滚 + 内层 overflow:hidden 滚动容器）');
{
  const html = el('html', { oy: 'hidden', w: 1200, h: 800, sh: 800, sw: 1200 });
  const body = el('body', { oy: 'hidden', w: 1200, h: 800, sh: 800, sw: 1200 });
  const feed = el('feed', { oy: 'hidden', w: 1200, h: 800, sh: 6000, sw: 1200 });
  const clip = el('clip', { oy: 'hidden', w: 1200, h: 600, sh: 600, sw: 1200 });
  const deep = el('deep', { w: 1200, h: 600, sh: 600 });
  chain([deep, clip, feed, body, html]);

  const env = makeEnv({ center: deep, allDivs: [clip, feed], scrollingElement: html });
  const run = load(env);

  const r = await run('down');
  check('down 成功（不再 no_change）', r.ok === true, JSON.stringify(r));
  check('走的是原生滚动兜底', r.method === 'scroll', 'method=' + r.method);
  check('滚的是真正的容器 feed，不是裁剪框 clip', feed.scrollTop > 0 && clip.scrollTop === 0,
    `feed=${feed.scrollTop} clip=${clip.scrollTop}`);

  const before = feed.scrollTop;
  const r2 = await run('up');
  check('up 能往回滚', r2.ok === true && feed.scrollTop < before,
    `before=${before} after=${feed.scrollTop}`);
}

// ================= 用例 3：左右键 + 只横向溢出的容器 =================
console.log('\n用例 3  横向滚动（宽表格页面，只有 overflow-x）');
{
  const html = el('html', { oy: 'hidden', w: 1200, h: 800, sh: 800, sw: 1200 });
  const body = el('body', { oy: 'hidden', w: 1200, h: 800, sh: 800, sw: 1200 });
  const wide = el('wide', { ox: 'auto', oy: 'hidden', w: 1200, h: 700, sh: 700, sw: 4000 });
  const deep = el('deep', { w: 4000, h: 700, sh: 700, sw: 4000 });
  chain([deep, wide, body, html]);

  const env = makeEnv({ center: deep, allDivs: [wide], scrollingElement: html });
  const run = load(env);

  const r = await run('right');
  check('right 成功', r.ok === true, JSON.stringify(r));
  check('横向滚动了', wide.scrollLeft > 0, 'scrollLeft=' + wide.scrollLeft);
}

// ================= 用例 4：真的没得滚 → 老实的 no_change =================
console.log('\n用例 4  页面确实无处可滚（短页面 + ←）');
{
  const html = el('html', { oy: 'visible', w: 1200, h: 800, sh: 700, sw: 1200 });
  const p = el('p', { w: 1200, h: 700, sh: 700 });
  chain([p, html]);
  const env = makeEnv({ center: p, allDivs: [], scrollingElement: html });
  const run = load(env);

  const r = await run('left');
  check('如实报失败', r.ok === false && r.reason === 'no_change', JSON.stringify(r));
  check('detail 带现场', typeof r.detail === 'string' && r.detail.includes('scroll=0'), r.detail);
}

// ================= 用例 5：页面自己吃掉了按键 =================
console.log('\n用例 5  站点自己 preventDefault 接管方向键');
{
  const html = el('html', { oy: 'visible', w: 1200, h: 800, sh: 3000, sw: 1200 });
  const host = el('host', { w: 1200, h: 800, sh: 3000 });
  chain([host, html]);
  const env = makeEnv({ center: host, allDivs: [], scrollingElement: html });
  // 让焦点落在页内元素上（keyTarget 会派发给它），并让 dispatchEvent 触发
  // preventDefault，模拟站点自己接管方向键。
  env.doc.activeElement = host;
  host.dispatchEvent = (ev) => {
    ev.preventDefault();
    return false;
  };
  const run = load(env);

  const r = await run('down');
  check('不抢站点的控制权（不补滚）', r.ok === false && html.scrollTop === 0, JSON.stringify(r));
  check('detail 标出 blocked=1', r.detail.includes('blocked=1'), r.detail);
}

console.log(`\n${failed === 0 ? '全部通过' : failed + ' 项失败'}\n`);
process.exit(failed === 0 ? 0 : 1);
