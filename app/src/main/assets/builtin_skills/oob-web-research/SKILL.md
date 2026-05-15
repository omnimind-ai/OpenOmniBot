---
name: oob-web-research
description: 调研、搜索整理、收集资料、分析竞品、研究市场、小红书调研、GitHub分析、爬取内容、批量收集、信息汇总、web research。Use when the task involves gathering, extracting, or aggregating information from the web — search results, platform pages, repositories, or social content — and storing findings in a Project.
---

# OOB Web Research

Research tasks combine three tools: `web_search` (broad discovery), `browser_use` (deep extraction), and a Workbench Project (persistent findings store).

## Tool Selection

```
"找一下 X 的资料"         → web_search first, browser_use for top results
"打开这个页面提取 Y"       → browser_use directly
"调研小红书上关于 Z 的内容" → browser_use navigate + get_readable + screenshot
"分析这个 GitHub 仓库"     → browser_use get_readable (README is markdown)
"批量收集多条"             → scroll_and_collect or loop navigate
```

## Core Extraction Pattern

```
navigate(url)
  ↓ check riskChallengeDetected — if true, stop and ask user to handle manually
get_readable           → structured text (titles, body, tags) as markdown
screenshot(read_image=true)  → visual: images, layout, charts — LLM sees it directly
```

Use both together for image-heavy platforms (小红书, 微博). Use only `get_readable` for text-heavy platforms (GitHub, news, docs).

## Browser Session and Login

OOB's browser is offscreen — it does not occupy the real screen. The browser session persists globally across runs.

If a platform requires login:
1. On first access, `riskChallengeDetected` or a login wall will appear.
2. Tell the user: "需要在浏览器里登录一次，之后会自动保持会话。" Show the browser overlay.
3. After the user logs in, the session is kept alive — no re-login needed in future runs.

Never automate login forms (password fields). Wait for the user to log in manually.

## Storing Findings

Write each finding as a Project item via `workbench_api_call`:

```json
{
  "toolId": "finding.save",
  "run": {"use": "native.collection.create"},
  "inputSchema": {
    "title": "string",
    "url": "string?",
    "summary": "string",
    "source": "string",
    "tags": "string?"
  }
}
```

After collecting multiple pages, do one `terminal_execute` Python pass to deduplicate and rank by relevance before writing.

## Risk Handling

- `riskChallengeDetected=true` → stop, show browser, ask user to handle CAPTCHA or login
- HTTP 403/429 → wait, do not retry in a tight loop
- Blank `get_readable` → try `get_text` or `screenshot(read_image=true)` instead
- JS-heavy SPA not rendered → use `wait_for_selector` before extracting

## References

- `references/xiaohongshu.md` — URL patterns, content structure, anti-bot notes, login flow
- `references/github.md` — repo/issue/PR extraction, API fallback via terminal curl
