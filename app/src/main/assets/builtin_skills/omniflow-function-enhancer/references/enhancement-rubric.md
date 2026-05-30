# OmniFlow Function Enhancement Rubric

## Status Decision

Use `enhanced` when at least one section changed the Function in a way that a
future user can actually benefit from, and all required sections returned valid
patches.

Use `unchanged` when patches are valid but normalize to the same Function, or
when every proposed change is too vague to improve recall or parameter filling.

Use `partial` when a useful patch was applied but one or more sections failed,
returned invalid JSON, referenced missing step indexes, or attempted unsafe
bindings.

Use `failed` when no safe patch remains after validation, the model returns no
parseable JSON, or the save operation cannot preserve the existing Function.

## Good Parameter Candidates

- Contact or recipient names: `contact_name`, `recipient_name`.
- Phone numbers: `phone_number`.
- Search queries: `search_query`.
- Message bodies: `message_text`.
- Dates or times typed by the user: `target_date`, `target_time`.
- URLs or domains: `target_url`, `website`.
- Object names visible in a task: `target_item`, `playlist_name`,
  `document_name`.

Reject:

- Coordinates: `x`, `y`, `start_x`, `end_y`.
- Bounds and dimensions: `bounds`, `left`, `top`, `width`, `height`.
- Screenshot, XML, node id, or page vector values.
- Paths that do not exist in the current Function.

## Examples

Search flow:

```json
{
  "name": "搜索关键词",
  "description": "在当前搜索页定位搜索输入框，输入运行时搜索词并打开搜索结果。当前页面展示同类搜索框时可复用；搜索结果页出现与关键词相关的结果即为成功。",
  "parameters": [
    {
      "name": "search_query",
      "type": "string",
      "description": "运行时搜索词",
      "default": "天气",
      "bindings": ["$.execution.steps[1].args.text"]
    }
  ],
  "agent_reuse": {
    "reuse_when": ["当前页面展示同一个搜索输入框。"],
    "avoid_when": ["页面不是搜索页，或输入框用途不是搜索。"],
    "success_signal": "搜索结果页展示与关键词相关的结果。"
  }
}
```

Message flow:

```json
{
  "name": "发送消息给联系人",
  "description": "在目标联系人聊天页定位消息输入框，写入运行时消息文本并点击发送。当前已进入同一聊天输入页时可复用；消息列表出现刚发送的文本即为成功。",
  "parameters": [
    {
      "name": "message_text",
      "type": "string",
      "description": "要发送的消息正文",
      "default": "我马上到",
      "bindings": ["$.execution.steps[2].args.text"]
    }
  ],
  "agent_reuse": {
    "reuse_when": ["当前已进入同一聊天输入页。"],
    "avoid_when": ["当前聊天对象不确定，或发送动作需要用户确认。"],
    "success_signal": "消息气泡出现在聊天记录底部。"
  }
}
```

Settings flow with no runtime slot:

```json
{
  "name": "打开系统设置",
  "description": "从当前界面打开 Android 系统设置首页。设备允许启动设置应用时可复用；设置首页或对应设置入口可见即为成功。",
  "parameters": [],
  "agent_reuse": {
    "reuse_when": ["设备可打开 Android 设置应用。"],
    "avoid_when": ["目标不是 Android 系统设置，或需要进入具体设置项。"],
    "success_signal": "屏幕显示系统设置首页。"
  }
}
```

If this produces only equivalent labels on an already clear Function, mark the
attempt `unchanged` rather than pretending enhancement happened.
