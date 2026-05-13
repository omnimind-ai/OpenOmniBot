---
name: oob-native-html-frontend
description: 改HTML前端、改界面样式、改布局、加图表、前端热更新、修前端、写index.html、修改Project显示、改前端代码、优化移动端界面。Write, hot-update, or repair the HTML/CSS/vanilla JS display layer of an OOB Workbench Project. Use when the task is specifically about the frontend files — not Project design or API definition.
---

# OOB Native HTML Frontend

Target runtime: Android WebView serving static files from `frontend/html/`. No build step, no npm, no React.

## Delivery Rules

Default: HTML + CSS + vanilla JS only. Never generate `package.json`, JSX, or TypeScript source. CDN only for Chart.js or Alpine.js when actually needed; core layout must be local via `base.css`.

## Bridge (`window.oob`) — Only Channel to Native

```js
const project = await window.oob.getProject();          // read on load
const result  = await window.oob.callApi(apiId, inputs); // write via Project Tool
window.oob.onProjectUpdated(fn);                        // async agent task results
```

## Data Contract

Items use the Workbench envelope: top-level `id/title/status`, domain fields under `item.fields.*`. Always normalize through `toViewItem()` before rendering — never read `item.amount`, `item.date`, etc. directly.

## State Machine (every callApi button)

`idle → loading (disabled) → success: re-render → error: show message, re-enable`
Agent tasks: `callApi → spinner → onProjectUpdated → re-render or show error`.

## Hot Update

Prefer `htmlPatches` (surgical) over full `htmlFiles` rewrite. Target `data-oob-id` anchors — add them to: primary button, form panel, list container, item cards, summary section.

## References

- `references/html-patterns.md` — full skeleton, bridge code, toViewItem adapter, multi-page hash routing, chart pattern
- `references/hot-update-guide.md` — htmlPatches strategy, oob-id targeting, when to rewrite vs patch
- `assets/base.css` — copy into `frontend/html/base.css` for every new project
