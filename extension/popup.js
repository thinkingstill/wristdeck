const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const time = (ts) => new Date(ts).toLocaleTimeString('zh-CN', { hour12: false });

async function refresh() {
  const status = await chrome.runtime.sendMessage({ type: 'wd_status' });

  $('conn').innerHTML = status.connected
    ? '<span class="dot on"></span>已连接'
    : `<span class="dot off"></span>未连接${status.lastError ? ' · ' + esc(status.lastError) : ''}`;

  $('tab').textContent = status.lockedTabId
    ? `已锁定 #${status.lockedTabId}`
    : '未锁定 · 当前激活';

  const items = status.recent || [];
  $('log').innerHTML = items.length
    ? items.map((r) =>
        `<div class="item"><span class="${r.ok ? 'ok' : 'bad'}">${r.ok ? '√' : '×'}</span> ` +
        `${esc(r.action)} · ${r.cost}ms · ${esc(r.method || '-')}` +
        `${r.reason ? ' · ' + esc(r.reason) : ''}<br>` +
        `<span style="color:var(--muted)">${time(r.t)} ${esc(r.title || '')}</span>` +
        /* 失败现场一起显示：video/scroll/blocked/host 四项能直接分辨
         * "页面没视频"、"到顶了没余量"、"站点自己吃掉了按键"、"找错滚动宿主"。 */
        `${r.detail ? '<br><span style="color:var(--muted)">' + esc(r.detail) + '</span>' : ''}` +
        `</div>`
      ).join('')
    : '<div class="empty">暂无执行记录</div>';

  /* 把"指令到底投给哪个标签页"显示出来。no_content_script 的头号原因
   * 是扩展盯着的标签页根本不是普通网页（chrome:// 之类），看不到目标就只能靠猜。 */
  try {
    const tab = status.target;
    const url = tab?.url || '';
    const host = url ? new URL(url).hostname : '-';
    const controllable = /^https?:\/\//.test(url);
    $('rule').textContent = url
      ? `${host} · ${controllable ? '可控制' : '不可控制（非网页）'}`
      : '无可用标签页';
    $('lockBtn').textContent = status.lockedTabId ? '解除锁定' : '锁定当前标签页';
    $('lockBtn').dataset.tabId = tab?.id ?? '';
  } catch {
    $('rule').textContent = '-';
  }
}

$('lockBtn').addEventListener('click', async () => {
  const current = $('lockBtn').dataset.tabId;
  const tabId = $('lockBtn').textContent.includes('解除') ? null : Number(current || 0);
  await chrome.runtime.sendMessage({ type: 'wd_lock', tabId: tabId || null });
  refresh();
});

$('reconnectBtn').addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ type: 'wd_ping' });
  setTimeout(refresh, 600);
});

refresh();
setInterval(refresh, 2000);
