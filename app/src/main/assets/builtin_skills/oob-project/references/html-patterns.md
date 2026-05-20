# OOB HTML Frontend Patterns

Target: Android WebView, phone portrait, 360–430dp. Static files in `frontend/html/`.

Project mode injects the current App theme into HTML:

- `html[data-oob-color-scheme="light"]` / `html[data-oob-color-scheme="dark"]`
- `window.oob.colorScheme()`
- `project.colorScheme` from `window.oob.getProject()`

Use those values for light/dark-specific UI. The injected value follows the current App theme and should take precedence over raw `prefers-color-scheme`.

---

## Complete Skeleton

```html
<!doctype html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <title><!-- project name --></title>
  <link rel="stylesheet" href="base.css">
  <style>/* project-specific overrides only */</style>
</head>
<body>
<div class="container">
  <div class="header" data-oob-id="header">
    <h1 class="title">项目名</h1>
    <div class="summary" id="summary" data-oob-id="summary"></div>
  </div>
  <div id="status"></div>
  <button class="btn-primary" id="btn-add" data-oob-id="btn-add" onclick="openForm()">+ 添加</button>
  <div id="form-panel" class="card" style="display:none" data-oob-id="form-panel">
    <input id="inp-title" class="input" type="text" placeholder="名称">
    <div id="form-error" class="error-msg" style="display:none"></div>
    <div style="display:flex;gap:8px;margin-top:12px">
      <button class="btn-secondary" onclick="closeForm()" style="flex:1">取消</button>
      <button class="btn-primary" onclick="submitForm()" style="flex:2;margin:0">保存</button>
    </div>
  </div>
  <div id="list" data-oob-id="list"></div>
</div>
<script>
// ── Bootstrap ────────────────────────────────────────────────────
window.addEventListener('load', async () => {
  showStatus('加载中…');
  const project = await window.oob.getProject();
  render(activeItems(project));
  showStatus('');
});
window.oob.onProjectUpdated(function(project) {
  hideLoading();
  if (project._taskError) { showError(project.errorMessage || '操作失败'); return; }
  render(activeItems(project));
});

// ── View model ───────────────────────────────────────────────────
function toViewItem(item) {
  const f = item.fields || {};
  return {
    id:     item.id,
    title:  item.title || f.title || '',
    status: item.status || 'active',
    // Add domain fields: amount: Number(f.amount||0), note: f.note||'', etc.
  };
}
function activeItems(project) {
  return (project.items || [])
    .filter(i => (i.status || 'active') === 'active')
    .map(toViewItem);
}

// ── Render ───────────────────────────────────────────────────────
function render(items) {
  const el = document.getElementById('list');
  if (!items.length) { el.innerHTML = '<div class="empty">暂无数据</div>'; return; }
  el.innerHTML = items.map(item => `
    <div class="card" data-oob-id="card-${item.id}">
      <div class="card-row">
        <span>${esc(item.title)}</span>
        <button class="btn-archive" onclick="archive('${item.id}')">归档</button>
      </div>
    </div>`).join('');
}

// ── Actions ──────────────────────────────────────────────────────
function openForm()  { document.getElementById('form-panel').style.display='block'; }
function closeForm() { document.getElementById('form-panel').style.display='none'; clearFormError(); }

async function submitForm() {
  const title = document.getElementById('inp-title').value.trim();
  if (!title) { showFormError('inp-title', '不能为空'); return; }
  await apiAction('item.create', { title }, document.querySelector('#form-panel .btn-primary'));
  closeForm();
  document.getElementById('inp-title').value = '';
}
async function archive(id) { await apiAction('item.archive', { item_id: id }); }

// ── API helper (sync CRUD) ───────────────────────────────────────
async function apiAction(apiId, inputs, btn) {
  if (btn) btn.disabled = true;
  showStatus('处理中…');
  try {
    const r = await window.oob.callApi(apiId, inputs);
    if (!r.success) throw new Error(r.errorMessage || '操作失败');
    showStatus('');
    if (r.project) render(activeItems(r.project));
  } catch(e) { showError(e.message); }
  finally { if (btn) btn.disabled = false; }
}

// ── Helpers ──────────────────────────────────────────────────────
function showStatus(m) { document.getElementById('status').innerHTML = m ? `<span class="status-msg">${m}</span>` : ''; }
function showError(m)  { document.getElementById('status').innerHTML = `<div class="error-msg">${esc(m)}</div>`; }
function showFormError(id, m) {
  const el=document.getElementById('form-error'); el.textContent=m; el.style.display='block';
  if(id) document.getElementById(id).focus();
}
function clearFormError() { const el=document.getElementById('form-error'); if(el){el.textContent='';el.style.display='none';} }
function hideLoading() { showStatus(''); document.querySelectorAll('button[disabled]').forEach(b=>b.disabled=false); }
function esc(s) { return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
</script>
</body>
</html>
```

---

## Agent Task Pattern (async, run: {use: "agent"})

```js
// Trigger — callApi returns {status:"pending"} immediately
async function triggerAgentTask() {
  showStatus('分析中，请稍候…');
  document.getElementById('btn-analyze').disabled = true;
  await window.oob.callApi('meal.analyze', { date: today() });
  // Do NOT read result.project here — agent hasn't run yet
}

// Result arrives here (registered once on load)
window.oob.onProjectUpdated(function(project) {
  document.getElementById('btn-analyze').disabled = false;
  showStatus('');
  if (project._taskError) { showError(project.errorMessage || '失败，请重试'); return; }
  render(activeItems(project));
});
```

---

## Script Result Pattern (run: {use: "script"})

```js
async function loadStats() {
  const r = await window.oob.callApi('entry.stats', { month: currentMonth() });
  if (!r.success) { showError(r.errorMessage); return; }
  const { total, by_category } = r.outputs;  // script outputs live in r.outputs
  renderStats(total, by_category);
}
```

---

## Chart (Chart.js via CDN)

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"></script>
<canvas id="chart-main" data-oob-id="chart-main" style="width:100%;height:200px"></canvas>
```

```js
let _chart = null;
function renderChart(labels, data) {
  const ctx = document.getElementById('chart-main').getContext('2d');
  if (_chart) _chart.destroy();  // always destroy before re-create
  _chart = new Chart(ctx, {
    type: 'bar',
    data: { labels, datasets: [{ data, backgroundColor: '#007AFF', borderRadius: 6 }] },
    options: { responsive: true, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } }
  });
}
```

---

## Multi-Page (relative links)

```html
<!-- index.html -->
<a href="detail.html?id=${item.id}">查看</a>
```

```js
// detail.html
const id = new URLSearchParams(location.search).get('id');
window.addEventListener('load', async () => {
  const project = await window.oob.getProject();
  const item = toViewItem(project.items.find(i => i.id === id));
  renderDetail(item);
});
```

No browser back stack. OOB treats sub-pages as Project-local page replacement.

---

## Mobile UX Checklist

- Touch targets ≥ 44px tall
- Single column, `max-width: 430px`
- No horizontal scroll, no modals, no popovers
- Inline expand/collapse only
- Body 15px, meta 13px, nothing below 12px
- Primary action always visible without scrolling
- `#status` div always present (loading / error / success messages)
- `.empty` state when list is empty
