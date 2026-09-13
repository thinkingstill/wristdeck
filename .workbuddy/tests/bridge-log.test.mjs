/* 验证 `node server.js` 会把手表操作日志实时打到终端。
 *
 * 做法：用独立的 HOME（`os.homedir()` 优先读 HOME）伪造一份 config.json，把
 * 测试实例开在 8791 端口，**完全不碰用户正在跑的 8787**；然后脚本自己当
 * watch + exec 两个客户端走一遍真实握手与转发，最后把子进程的 stdout 打出来。
 *
 * 跑法：node .workbuddy/tests/bridge-log.test.mjs
 */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PROJECT = path.resolve(here, '..', '..');
const BRIDGE = path.join(PROJECT, 'bridge');

// ws 是 CJS，且只装在 bridge/node_modules 下 —— 用 createRequire 以 bridge 为基准解析
const WebSocket = createRequire(path.join(BRIDGE, 'package.json'))('ws');

const NODE = process.execPath;
const PORT = 8791;
const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'wd-logtest-'));
fs.mkdirSync(path.join(tmpHome, '.wristbridge'), { recursive: true });
fs.writeFileSync(
  path.join(tmpHome, '.wristbridge', 'config.json'),
  JSON.stringify({ port: PORT, pin: '424242', bindAll: false }),
);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const child = spawn(NODE, [path.join(BRIDGE, 'server.js')], {
  env: { ...process.env, HOME: tmpHome },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let out = '';
child.stdout.on('data', (d) => { out += d.toString(); });
child.stderr.on('data', (d) => { out += d.toString(); });

// 等启动横幅（说明已经开始监听）
for (let i = 0; i < 60 && !out.includes('等待手表'); i++) await sleep(100);
if (!out.includes('等待手表')) {
  console.log('服务没起来，输出如下：\n' + out);
  child.kill();
  process.exit(1);
}

const open = (role) =>
  new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws?role=${role}`);
    ws.on('open', () => {
      ws.send(JSON.stringify({ v: 1, t: 'hello', role, pin: '424242', dev: `test-${role}` }));
      resolve(ws);
    });
    ws.on('message', (buf) => {
      const msg = JSON.parse(buf.toString());
      // 执行器收到转发就把"扩展回包"打回去，带 detail 现场
      if (msg.t === 'fwd' && role === 'exec') {
        ws.send(JSON.stringify({
          v: 1, t: 'res', id: msg.id, ok: false, cost: 267,
          reason: 'no_change',
          detail: 'video=0 scroll=0 blocked=0 host=doc',
        }));
      }
    });
    ws.on('error', reject);
  });

const watch = await open('watch');
const exec = await open('exec');
await sleep(150);

// 1) 正常转发一次（失败 + detail 现场）
watch.send(JSON.stringify({ v: 1, t: 'cmd', a: 'right', id: 'r1' }));
await sleep(200);

// 2) 执行器离线
exec.close();
await sleep(200);
watch.send(JSON.stringify({ v: 1, t: 'cmd', a: 'down', id: 'd1' }));
await sleep(200);

// 3) 执行器回来
const exec2 = await open('exec');
await sleep(150);
watch.send(JSON.stringify({ v: 1, t: 'cmd', a: 'up', id: 'u1' }));
await sleep(250);

watch.close();
exec2.close();
await sleep(250);
child.kill('SIGTERM');
await sleep(300);

console.log('=========== 子进程 stdout ===========');
console.log(out);
console.log('=====================================');

const lines = out.split('\n');
let failed = 0;
const check = (label, cond) => {
  console.log(`${cond ? '[PASS]' : '[FAIL]'} ${label}`);
  if (!cond) failed++;
};

const has = (re) => lines.some((l) => re.test(l));
const idx = (re) => lines.findIndex((l) => re.test(l));
check('手表按下时打出"手表 right … sent"', has(/手表\s+right\s+\d+ms\s+√\s+sent/));
check('回包打出"扩展 right … × no_change"', has(/扩展\s+right\s+\d+ms\s+×\s+no_change/));
check('失败现场 detail 一起打出来', has(/host=doc/));
check('sent 行在结果行之前', idx(/手表\s+right/) < idx(/扩展\s+right/));
check('打出了 exec_offline', has(/手表\s+down\s+0ms\s+×\s+exec_offline/));
check('exec_offline 时不再多打一条 sent', lines.filter((l) => /手表\s+down/.test(l)).length === 1);
check('打出了两个客户端握手', has(/手表\s+hello/) && has(/扩展\s+hello/));
check('打出了 exec 断连', has(/扩展\s+-\s+0ms\s+√\s+disconnected/));
check('成功项用 √、失败项用 ×', has(/√/) && has(/×/));
check('重定向到管道时不着色（无 ANSI 转义）', !/\x1b\[/.test(out));

fs.rmSync(tmpHome, { recursive: true, force: true });
console.log(failed === 0 ? '\n全部通过' : `\n${failed} 项失败`);
process.exit(failed === 0 ? 0 : 1);
