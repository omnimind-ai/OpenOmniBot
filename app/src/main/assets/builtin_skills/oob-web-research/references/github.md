# GitHub 调研指南

## URL 规律

```
仓库主页:   https://github.com/<owner>/<repo>
README:     https://github.com/<owner>/<repo>#readme
Issues:     https://github.com/<owner>/<repo>/issues?q=<query>
PR 列表:    https://github.com/<repo>/pulls
发布页:     https://github.com/<owner>/<repo>/releases
代码搜索:   https://github.com/search?q=<query>&type=repositories
Raw 文件:   https://raw.githubusercontent.com/<owner>/<repo>/main/<path>
```

## 提取策略

### 仓库概览

```
navigate("https://github.com/<owner>/<repo>")
→ get_readable   // README 内容以 markdown 格式返回，质量很高
→ get_text(selector=".f3")          // 仓库描述
→ get_text(selector="#repo-stars")  // star 数
→ get_text(selector=".BorderGrid")  // About 区域: stars/forks/watchers/language
```

`get_readable` 对 GitHub 效果极好——直接返回格式化的 README markdown，通常不需要 screenshot。

### Issues 分析

```
navigate("https://github.com/<repo>/issues?q=is:open+label:bug")
→ get_readable    // issue 列表标题和标签
→ scroll_and_collect(selector=".js-navigation-item", count=30)

// 单个 issue 详情:
navigate(issue_url)
→ get_readable    // 问题描述 + 评论线程
```

### Releases / Changelog

```
navigate("https://github.com/<repo>/releases")
→ get_readable   // 版本号 + 变更说明
```

### 搜索仓库

```
navigate("https://github.com/search?q=calorie+tracker+android&type=repositories&s=stars")
→ get_readable   // 仓库列表: 名称、stars、描述、语言
```

## API 方式（无反爬，推荐大量提取时用）

GitHub 有公开 REST API，不需要 Token 即可访问公开数据（每小时 60 次限额）：

```bash
# terminal_execute:
curl -s "https://api.github.com/repos/<owner>/<repo>" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'Stars: {d[\"stargazers_count\"]}')
print(f'Forks: {d[\"forks_count\"]}')
print(f'Issues: {d[\"open_issues_count\"]}')
print(f'Language: {d[\"language\"]}')
print(f'Description: {d[\"description\"]}')
"
```

```bash
# 获取最新 releases:
curl -s "https://api.github.com/repos/<owner>/<repo>/releases?per_page=5"

# 搜索仓库:
curl -s "https://api.github.com/search/repositories?q=<query>&sort=stars&per_page=10"

# 获取 Issues:
curl -s "https://api.github.com/repos/<owner>/<repo>/issues?state=open&per_page=20"
```

如果有 GitHub Token（`GITHUB_TOKEN` 环境变量），每小时限额提升到 5000 次：
```bash
curl -s -H "Authorization: Bearer $GITHUB_TOKEN" "https://api.github.com/repos/..."
```

## 典型调研任务示例

### 分析某个开源项目

```
1. navigate 仓库主页 → get_readable (README)
2. terminal_execute: curl API 获取 stars/forks/issues/language
3. navigate releases 页 → get_readable (近期更新)
4. navigate issues 页 → get_readable (主要问题/feature requests)
5. 汇总写入 Project
```

### 竞品 GitHub 仓库对比

```
1. web_search("site:github.com <category> android") 找候选
2. 对每个仓库: curl API 获取基本指标
3. 按 stars 排序，取 Top 5
4. 对每个 Top 5: navigate + get_readable 分析 README
5. terminal_execute Python: 生成对比表
6. 写入 Project findings
```

### 监控 Issue 动态

```
navigate("https://github.com/<repo>/issues?q=is:open+sort:updated")
→ get_readable    // 最新活跃 issues
→ 提取: issue 标题、标签、最后更新时间、评论数
→ 与上次结果对比（from project.items），找出新增和关闭的 issues
```

## 注意

- GitHub 基本无反爬，`get_readable` 效果好，优先用
- 大量数据提取用 curl API，比 browser_use 快 10 倍且稳定
- Raw 文件（`raw.githubusercontent.com`）可以直接 fetch 不需要 browser
- 私有仓库需要 Token，不要在 agent 里硬编码 Token
