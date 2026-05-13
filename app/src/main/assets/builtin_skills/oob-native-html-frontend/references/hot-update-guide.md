# OOB HTML Hot Update Guide

When a user asks to change the frontend ("改大一点", "换个颜色", "加个搜索框"), use the right strategy.

---

## Patch vs Full Rewrite

| Change type | Use |
|---|---|
| CSS value, color, font size, spacing | `htmlPatches` |
| Add or remove a single element | `htmlPatches` |
| Text content, label, placeholder | `htmlPatches` |
| Restructure layout (>30% of file changed) | full `htmlFiles` rewrite |
| New page file (`detail.html`) | new file addition |
| Refactor JS logic | full `htmlFiles` rewrite |

Full rewrite costs ~50× more tokens than a patch. Always prefer patches for incremental changes.

---

## htmlPatches Format

```json
{
  "htmlPatches": [
    { "path": "index.html", "oldText": "font-size: 14px", "newText": "font-size: 18px" },
    { "path": "index.html", "oldText": "color: #999",     "newText": "color: #333" }
  ]
}
```

- `oldText` must be unique in the file — include enough surrounding context if needed
- Multiple patches in one call are fine
- `path` is relative to `frontend/html/`

---

## Targeting Strategy

**Step 1: oobId exists** (`frontendContext.selectedElement.oobId`)

Search directly for `data-oob-id="<id>"` in the source — do not read the full file.

```json
{ "oldText": "data-oob-id=\"btn-add\" onclick=\"openForm()\"",
  "newText": "data-oob-id=\"btn-add\" onclick=\"openForm()\" style=\"background:#34C759\"" }
```

**Step 2: No oobId, annotation description available**

Use `frontendContext.annotationDescription` ("User drew a circle around the BUTTON labeled '打卡'") to identify the target element by its visible text or nearby text, then `file_read(lineStart, lineCount)` for the relevant section only.

**Step 3: No context at all**

Read only the relevant section with `file_read(lineStart, lineCount)`. Do not read the full file if the change is small.

---

## data-oob-id Anchor Convention

Add these anchors to every generated frontend. They are stable targets — never rename after creation without updating `PROJECT_CONTEXT.md`.

| `data-oob-id` | Element |
|---|---|
| `header` | Page header container |
| `summary` | Summary stat / progress display |
| `btn-add` | Primary add action button |
| `form-panel` | Inline add/edit form |
| `list` | Item list container |
| `card-{item.id}` | Individual item card |
| `chart-{name}` | Chart canvas |
| `btn-{action}` | Other action buttons |

---

## Common Patch Examples

**Change a color:**
```json
{ "oldText": "background: #007AFF", "newText": "background: #34C759" }
```

**Resize a button:**
```json
{ "oldText": "padding: 14px", "newText": "padding: 18px" }
```

**Change a label:**
```json
{ "oldText": ">+ 添加记录<", "newText": ">+ 新增<" }
```

**Add an element after an existing one:**
```json
{
  "oldText": "</button>\n  <div id=\"list\"",
  "newText": "</button>\n  <input id=\"search\" class=\"input\" placeholder=\"搜索…\" oninput=\"filterList(this.value)\">\n  <div id=\"list\""
}
```

**Remove an element:**
```json
{ "oldText": "  <div class=\"old-section\">...</div>\n", "newText": "" }
```

---

## After Patching

Always update `PROJECT_CONTEXT.md` if:
- A new `data-oob-id` was added
- A visible label changed (so future patches can find it)
- The primary action button was renamed
