# OOB Project Frontend Guide

Complete HTML/CSS/JS patterns for OOB Workbench Projects. Target: phone portrait WebView, 360–430dp wide.

---

## File Structure

```
frontend/
  html/
    base.css          ← standard CSS (copy from skill assets/base.css)
    index.html        ← main display (always required)
    detail.html       ← optional: item detail page (relative link from index)
```

WebView serves from `frontend/html/` root. Relative links between pages work. No external file paths.

---

## HTML Skeleton (complete)

```html
<!doctype html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title><!-- Project name --></title>
  <link rel="stylesheet" href="base.css">
  <style>
    /* Project-specific overrides only */
  </style>
</head>
<body>
<div class="container">

  <!-- ── Header ───────────────────────────────────────────────── -->
  <div class="header" data-oob-id="header">
    <h1 class="title">项目名称</h1>
    <!-- optional: summary stat (today total, progress, count) -->
    <div class="summary" data-oob-id="summary" id="summary">--</div>
  </div>

  <!-- ── Status bar (required, never remove) ──────────────────── -->
  <div id="status"></div>

  <!-- ── Primary action (required, always visible) ────────────── -->
  <button class="btn-primary" data-oob-id="btn-add" id="btn-add" onclick="openForm()">
    + 添加记录
  </button>

  <!-- ── Inline form (hidden by default) ─────────────────────── -->
  <div id="form-panel" class="card" style="display:none" data-oob-id="form-panel">
    <input id="inp-title" class="input" type="text" placeholder="名称">
    <!-- add domain fields here -->
    <div id="form-error" class="error-msg" style="display:none"></div>
    <div style="display:flex;gap:8px;margin-top:12px">
      <button class="btn-secondary" onclick="closeForm()" style="flex:1">取消</button>
      <button class="btn-primary" onclick="submitForm()" style="flex:2;margin:0">保存</button>
    </div>
  </div>

  <!-- ── Item list ─────────────────────────────────────────────── -->
  <div id="main"></div>

</div>
<script>
// ══════════════════════════════════════════════════════════════
// VIEW MODEL — normalize Workbench item envelope
// NEVER read item.amount, item.category etc. directly.
// ALL domain fields live under item.fields.*
// ══════════════════════════════════════════════════════════════
function toViewItem(item) {
  const f = (item && item.fields) ? item.fields : {};
  return {
    id:       item.id,
    title:    item.title || f.title || '',
    status:   item.status || 'active',
    // ── domain fields ──────────────────────────────────────
    // Replace these with your actual field names:
    amount:   Number(f.amount  || 0),
    note:     f.note     || '',
    category: f.category || '',
    date:     f.date     || '',
  };
}

function activeViewItems(project) {
  return (project.items || [])
    .filter(i => (i.status || 'active') === 'active')
    .map(toViewItem);
}

// ══════════════════════════════════════════════════════════════
// INIT — page load reads real data, never hardcoded
// ══════════════════════════════════════════════════════════════
window.addEventListener('load', async () => {
  showStatus('加载中…');
  const project = await window.oob.getProject();
  render(activeViewItems(project));
  showStatus('');
});

// ══════════════════════════════════════════════════════════════
// ASYNC UPDATES — agent task results arrive here
// Required even if no agent tasks in v1
// ══════════════════════════════════════════════════════════════
window.oob.onProjectUpdated(function(project) {
  hideLoading();
  if (project._taskError) {
    showError(project.errorMessage || '操作失败');
    return;
  }
  render(activeViewItems(project));
});

// ══════════════════════════════════════════════════════════════
// RENDER
// ══════════════════════════════════════════════════════════════
function render(items) {
  const el = document.getElementById('main');
  if (!items || items.length === 0) {
    el.innerHTML = '<div class="empty">暂无记录，点击上方按钮开始</div>';
    return;
  }
  el.innerHTML = items.map(item => `
    <div class="card" data-oob-id="item-${item.id}">
      <div class="card-row">
        <span class="card-title">${esc(item.title)}</span>
        <button class="btn-archive" onclick="archiveItem('${item.id}')">归档</button>
      </div>
      <!-- domain field display -->
      <div class="card-meta">${esc(item.note)}</div>
    </div>`).join('');
}

// ══════════════════════════════════════════════════════════════
// ACTIONS
// ══════════════════════════════════════════════════════════════
function openForm()  { document.getElementById('form-panel').style.display = 'block'; }
function closeForm() { document.getElementById('form-panel').style.display = 'none'; clearFormError(); }

async function submitForm() {
  const title = document.getElementById('inp-title').value.trim();
  if (!title) { showFormError('inp-title', '请填写名称'); return; }

  setLoading(true);
  const result = await window.oob.callApi('item.create', { title });
  setLoading(false);

  if (!result.success) { showFormError(null, result.errorMessage || '保存失败'); return; }
  closeForm();
  document.getElementById('inp-title').value = '';
  render(activeViewItems(result.project));
}

async function archiveItem(itemId) {
  setLoading(true);
  const result = await window.oob.callApi('item.archive', { item_id: itemId });
  setLoading(false);
  if (result.project) render(activeViewItems(result.project));
}

// ══════════════════════════════════════════════════════════════
// HELPERS
// ══════════════════════════════════════════════════════════════
function showStatus(msg) {
  const el = document.getElementById('status');
  el.innerHTML = msg ? `<span class="status-msg">${msg}</span>` : '';
}
function showError(msg) {
  document.getElementById('status').innerHTML = `<div class="error-msg">${esc(msg)}</div>`;
}
function showFormError(inputId, msg) {
  const el = document.getElementById('form-error');
  el.textContent = msg; el.style.display = 'block';
  if (inputId) document.getElementById(inputId).focus();
}
function clearFormError() {
  const el = document.getElementById('form-error');
  if (el) { el.textContent = ''; el.style.display = 'none'; }
}
function setLoading(v) {
  const btn = document.getElementById('btn-add');
  if (btn) btn.disabled = v;
  document.getElementById('status').innerHTML = v ? '<span class="status-msg">处理中…</span>' : '';
}
function hideLoading() { setLoading(false); }
function esc(s) {
  return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
</script>
</body>
</html>
```

---

## CSS Reference (base.css)

See `assets/base.css` for the full file. Key tokens and components:

```css
:root {
  --bg:      #F7F8FC;
  --surface: #FFFFFF;
  --border:  rgba(0,0,0,0.08);
  --text:    #0D1117;
  --muted:   rgba(13,17,23,0.50);
  --accent:  #007AFF;
  --danger:  #DC2626;
  --success: #059669;
  --radius:  12px;
  --shadow:  0 1px 4px rgba(0,0,0,0.07);
}
```

**Component classes provided by base.css:**

| Class | Purpose |
|---|---|
| `.container` | Centered column, max 430px |
| `.card` | White rounded card with shadow |
| `.card-row` | Flex row inside card |
| `.btn-primary` | Full-width blue action button |
| `.btn-secondary` | Bordered neutral button |
| `.btn-archive` | Text-only danger button |
| `.input` | Standard text/number input |
| `.tag`, `.tag-*` | Inline colored badge |
| `.empty` | Centered empty-state placeholder |
| `.error-msg` | Red error box |
| `.status-msg` | Muted status line |
| `.spinner` | CSS animation spinner |
| `.header`, `.title`, `.summary` | Page header layout |
| `.loading` | Opacity 0.4, pointer-events none |

---

## Data Binding Rules

### Item envelope (what `project.items` contains)

```js
{
  id:         "uuid-string",          // Workbench metadata — stable identifier
  title:      "User visible title",   // Workbench metadata
  status:     "active",              // Workbench metadata: "active" | "archived"
  createdAt:  "2026-05-13T10:00:00Z",
  archivedAt: null,
  fields: {
    // ALL application domain fields live here
    amount:   150.0,
    note:     "午饭",
    category: "餐饮",
    date:     "2026-05-13",
  }
}
```

**Rule:** Never read `item.amount`, `item.category`, etc. directly. Always use `item.fields.amount`, `item.fields.category`. Normalize via `toViewItem()` at the boundary.

### callApi result envelope

```js
{
  success:      true,
  apiId:        "entry.create",
  outputs:      { /* API-specific outputs, depends on executor */ },
  project:      { items: [ /* full updated item list */ ] },
  errorCode:    undefined,
  errorMessage: undefined
}
```

- For CRUD (`native.collection.*`): re-render from `result.project.items`
- For `list` APIs: use `result.outputs.items || result.project.items`, normalize each item
- For agent tasks: result is `{status: "pending"}` — wait for `onProjectUpdated` callback
- For errors: `result.success === false`, show `result.errorMessage`

---

## Interaction Patterns

### Sync CRUD (native.collection)

```js
async function createItem(title, amount, note) {
  setLoading(true);
  const result = await window.oob.callApi('entry.create', { title, amount, note });
  setLoading(false);
  if (!result.success) { showError(result.errorMessage || '操作失败'); return; }
  render(activeViewItems(result.project));  // immediate re-render
}
```

### Async agent task (run: {use: "agent"})

```js
// HTML trigger — callApi returns immediately with {status:"pending"}
async function analyzePhoto() {
  showStatus('分析中，请稍候…');
  document.getElementById('btn-analyze').disabled = true;
  await window.oob.callApi('meal.analyze', { date: today() });
  // DO NOT read result.project here — agent hasn't run yet
}

// Subscribe once on page load — result arrives here
window.oob.onProjectUpdated(function(project) {
  hideLoading();
  document.getElementById('btn-analyze').disabled = false;
  if (project._taskError) {
    showError(project.errorMessage || '分析失败，请重试');
    return;
  }
  render(activeViewItems(project));
});
```

### Script result display (run: {use: "script"})

```js
async function showMonthlyStats() {
  const result = await window.oob.callApi('entry.monthly_stats', { month: currentMonth() });
  if (!result.success) { showError(result.errorMessage); return; }
  // Script outputs are in result.outputs
  const { total, by_category } = result.outputs;
  renderStats(total, by_category);
}
```

---

## Charts (Chart.js via CDN)

Use only when the user explicitly needs a chart, not for decoration.

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"></script>
```

```js
function renderBarChart(canvasId, labels, data) {
  const ctx = document.getElementById(canvasId).getContext('2d');
  new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [{ data, backgroundColor: '#007AFF', borderRadius: 6 }]
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: { y: { beginAtZero: true } }
    }
  });
}
```

Canvas sizing: `width: 100%; height: 200px` — fits phone portrait without horizontal scroll.

---

## Multi-Page Projects

When a project needs a detail page:

```html
<!-- index.html: navigate to detail -->
<a href="detail.html?id=${item.id}">查看详情</a>

<!-- detail.html: read id from URL, load from project.items -->
<script>
const itemId = new URLSearchParams(location.search).get('id');
window.addEventListener('load', async () => {
  const project = await window.oob.getProject();
  const raw = project.items.find(i => i.id === itemId);
  if (!raw) { document.getElementById('main').textContent = '未找到'; return; }
  const item = toViewItem(raw);
  renderDetail(item);
});
</script>
```

OOB treats `detail.html?id=1` as Project-local page replacement — no browser back stack, no external navigation.

---

## Mobile UX Rules

- Touch targets: min 44px tall for all buttons and tappable elements
- One column layout: never use `display:grid` with more than 2 columns on phone
- No horizontal scroll: all tables become card rows or definition lists
- No modals or popovers: use inline expand/collapse (`display:none` ↔ `display:block`)
- No desktop heroes or large banners: first visible area shows real data
- Sticky primary action: if the list is long, keep the add button pinned or always above the list
- Font size: body 15px, meta 13px, never below 12px

---

## Importing Styles from Another Skill

A skill's `assets/` directory can contain CSS templates. To use them in a Project:

```
# Step 1: Read the skill asset
file_read(path="{otherSkillAssetsDir}/theme.css")

# Step 2: Write into project frontend
file_write(path="{spacePath}/frontend/html/theme.css", content=<content from step 1>)

# Step 3: Link in HTML
<link rel="stylesheet" href="theme.css">
```

The WebView serves only from `frontend/html/`, so files must be copied there first. Skill `assets/` directories are not directly linkable from HTML.

This skill's own `assets/base.css` is the standard mobile baseline. Include it in every Project by writing it to `frontend/html/base.css` during creation.
