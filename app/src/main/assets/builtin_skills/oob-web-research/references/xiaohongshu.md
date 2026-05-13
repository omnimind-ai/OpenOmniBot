# 小红书调研指南

## URL 规律

```
搜索结果页:   https://www.xiaohongshu.com/search_result?keyword=<关键词>&source=web_search_result
笔记详情页:   https://www.xiaohongshu.com/explore/<note_id>
用户主页:     https://www.xiaohongshu.com/user/profile/<user_id>
话题页:       https://www.xiaohongshu.com/tag/<tag_name>
```

推荐用 PC 版 URL (`www.xiaohongshu.com`)，内容更完整，反爬相对宽松。

## 提取策略

### 搜索结果列表

```
navigate("https://www.xiaohongshu.com/search_result?keyword=防晒&source=web_search_result")
→ riskChallengeDetected? 若是，停止让用户处理
→ scroll_and_collect(selector=".note-item", count=20)  // 批量提取卡片
→ 每张卡片: title、点赞数、评论数、作者名、note_id
```

### 单篇笔记详情

```
navigate("https://www.xiaohongshu.com/explore/<note_id>")
→ get_readable           // 标题、正文、标签
→ screenshot(read_image=true)   // 封面图、图片内容（小红书重图，必须看图）
→ 提取: 标题、正文、话题标签、点赞、收藏、评论数
```

### 评论区

```
scroll_and_collect(selector=".comment-item", count=30)
→ 提取: 评论文本、点赞数、回复数
```

## 内容结构（get_readable 返回的字段）

```
标题:   h1 或 .note-title
正文:   .note-content / .desc
标签:   .tag / #话题标签
互动:   .like-count, .collect-count, .comment-count
作者:   .author-name
发布时间: .date
```

## 反爬注意事项

1. **登录墙**: 未登录只能看部分内容，图片和评论通常需要登录
2. **滑块验证**: 频繁访问触发 `riskChallengeDetected`，停下来让用户手动通过
3. **频率控制**: 连续请求之间加 `wait_for_selector` 等待内容加载，不要快速循环
4. **User Agent**: 使用 `set_user_agent(desktop_safari)` 模拟 Safari，减少触发

## 登录流程（首次）

```
1. navigate("https://www.xiaohongshu.com")
2. 检测是否有登录按钮 → find_elements(selector=".login-btn")
3. 如果需要登录: 告知用户"请在悬浮窗里扫码登录小红书，登录后会自动继续"
4. 等待用户操作，session 自动持久化
5. 后续访问直接 navigate，不需要重新登录
```

## 典型调研任务示例

### 提取某关键词下 Top 20 笔记

```
1. navigate 搜索页
2. scroll_and_collect 获取笔记列表（note_id + 基本指标）
3. 按点赞排序取 Top 20
4. 依次 navigate 每个详情页: get_readable + screenshot(read_image=true)
5. 整理后 workbench_api_call 写入 Project
```

### 分析某品牌/话题的用户声音

```
1. 搜索品牌词，收集评论区高赞评论
2. terminal_execute Python: 词频统计、情感分类、高频问题提取
3. 结果写入 Project findings
```

## 注意

- 不要抓取用户私信或非公开内容
- 不要绕过登录验证，只做已登录状态下的正常访问
- 图片分析用 screenshot(read_image=true)，不要尝试下载图片 CDN URL（通常有防盗链）
