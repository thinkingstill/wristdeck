import { WebSocket } from 'ws';

// 用法:
//   node scripts/sim.js --as exec
//   node scripts/sim.js --as watch --pin 123456 --cmd down --times 50 --interval 300
//   node scripts/sim.js --as watch --pin 123456 --cmd mixed

const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : def;
};

const role = arg('as', 'watch');
const host = arg('host', '127.0.0.1');
const port = Number(arg('port', 8787));
const pin = arg('pin', '000000');
const cmd = arg('cmd', 'down');
const times = Number(arg('times', 20));
const interval = Number(arg('interval', 300));

const url = `ws://${host}:${port}/ws?role=${role}`;
const ws = new WebSocket(url);

const results = [];
let playing = true;
let sent = 0;

const send = (obj) => ws.send(JSON.stringify({ v: 1, ...obj }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIXED = ['toggle', 'down', 'down', 'up', 'left', 'right', 'toggle'];

ws.on('open', () => {
  console.log(`[${role}] 已连接 ${url}`);
  send({ t: 'hello', role, dev: `sim-${role}`, pin });
});

ws.on('message', (raw) => {
  const msg = JSON.parse(raw.toString());

  if (msg.t === 'welcome') {
    console.log(`[${role}] 鉴权通过 sid=${msg.sid} execOnline=${msg.cfg.execOnline}`);
    if (role === 'watch') runWatch();
    return;
  }

  if (msg.t === 'denied') {
    console.error(`[${role}] 被拒绝: ${msg.reason}`);
    process.exit(1);
  }

  if (role === 'exec' && msg.t === 'fwd') {
    const cost = 5 + Math.floor(Math.random() * 25);
    setTimeout(() => {
      if (msg.a === 'toggle' || msg.a === 'play' || msg.a === 'pause') playing = !playing;
      send({ t: 'res', id: msg.id, ok: true, cost, state: { playing } });
    }, cost);
    return;
  }

  if (role === 'watch' && msg.t === 'ack') {
    results.push(msg);
    const tail = msg.ok
      ? `ok e2e=${msg.lat?.e2e}ms exec=${msg.lat?.exec}ms`
      : `FAIL ${msg.reason}`;
    console.log(`  #${results.length} ${msg.id} ${tail}`);
    return;
  }

  if (msg.t === 'evt') {
    console.log(`[${role}] 事件: ${msg.e}`);
  }
});

ws.on('close', (code) => {
  console.log(`[${role}] 连接关闭 code=${code}`);
  if (role === 'watch' && results.length) report();
});

ws.on('error', (e) => {
  console.error(`[${role}] 错误: ${e.message}`);
  process.exit(1);
});

async function runWatch() {
  for (let i = 0; i < times; i += 1) {
    const action = cmd === 'mixed' ? MIXED[i % MIXED.length] : cmd;
    sent += 1;
    send({ t: 'cmd', id: `sim-${i}`, a: action, ts: Date.now() });
    await sleep(interval);
  }
  await sleep(800);
  report();
  ws.close();
}

function report() {
  const okList = results.filter((r) => r.ok);
  const e2e = okList.map((r) => r.lat?.e2e ?? 0).sort((a, b) => a - b);
  const pct = (p) => (e2e.length ? e2e[Math.min(e2e.length - 1, Math.ceil((p / 100) * e2e.length) - 1)] : null);
  console.log('-'.repeat(46));
  console.log(`发送 ${sent}  收到 ack ${results.length}  成功 ${okList.length}  失败 ${results.length - okList.length}`);
  console.log(`端到端 P50=${pct(50)}ms  P95=${pct(95)}ms  max=${e2e[e2e.length - 1] ?? '-'}ms`);
  const reasons = {};
  for (const r of results) if (!r.ok) reasons[r.reason] = (reasons[r.reason] || 0) + 1;
  if (Object.keys(reasons).length) console.log('失败原因:', reasons);
  console.log('-'.repeat(46));
  process.exit(0);
}

if (role === 'watch') {
  setTimeout(() => {
    console.error('超时退出：请确认 Bridge 已启动 (npm start)');
    process.exit(1);
  }, 60_000).unref();
}
