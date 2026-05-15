# Agent Activity Rendering v1

## Goal

The chat UI should not render every low-level tool call as a user-facing card.
Tool calls are execution events. User-facing cards should represent activities:
browser operation, terminal session, file operation, workbench update, or MCP
operation.

This keeps RunLog and skill distillation precise while making the live chat
timeline compact.

## References

- Claude Code Agent SDK exposes raw stream events for text and tool calls:
  https://code.claude.com/docs/en/agent-sdk/streaming-output
- AG-UI separates run events, text messages, tool events, and activity events:
  https://docs.ag-ui.com/concepts/events
- Vercel AI SDK UI renders message `parts`, including typed tool parts:
  https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage
- LangGraph streaming separates state updates, token streams, custom events,
  task events, and debug output:
  https://docs.langchain.com/oss/python/langgraph/streaming

## Current Problem

Today OOB maps:

```text
tool call -> agent_tool_summary card -> visible timeline row
```

That is too low-level for tools that naturally perform many steps:

```text
browser_use(navigate)
browser_use(find_elements)
browser_use(click)
browser_use(click retry)
browser_use(type)
```

Each call is useful for RunLog and replay. Showing each as a separate chat card
exposes implementation detail and makes retries look like agent confusion.

## Proposed Layers

```text
AgentStreamEvent
  raw ordered events, persisted messages, RunLog correlation

ChatMessageModel
  durable UI data; still one tool call per message/card where needed

AgentActivityCompactor
  converts process messages into message items or activity items

ActivityRendererRegistry
  picks a renderer by activity kind

Activity cards
  BrowserActivityCard, TerminalActivityCard, WorkspaceActivityCard,
  WorkbenchActivityCard, McpActivityCard, GenericToolActivityCard
```

## Activity Model

```text
AgentProcessItem
  message: ChatMessageModel
  activity: AgentToolActivity

AgentToolActivity
  id
  kind: browser | terminal | workspace | workbench | mcp
  title
  status
  taskId
  messages
  steps

AgentToolActivityStep
  cardId
  title
  action
  target
  status
  isRetry
  message
```

The compactor must be display-only. It must not delete or mutate underlying
messages.

## Grouping Rules

Only compact consecutive tool messages that belong to the same task and same
activity key.

Initial policies:

| Kind | Candidate | Activity key |
| --- | --- | --- |
| browser | `toolType=browser` or `toolName=browser_use` | `taskId + browser` |
| terminal | `toolType=terminal` or `toolName=terminal_*` | `taskId + terminalSessionId`, fallback `taskId + terminal` |
| workspace | `toolType=workspace` or `file_*` | `taskId + workspace` |
| workbench | `toolType=workbench` or `workbench_*` | `taskId + workbench` |
| mcp | `toolType=mcp` | `taskId + serverName` |

Single tool messages should stay as normal cards. Two or more consecutive
messages become one activity.

## Retry Rules

A step is a retry when:

- the previous step failed and the new step has the same normalized action key;
- or the previous step and current step have the same normalized action key.

The action key is tool-specific:

- browser: `action + url/selector/text/key/coordinates`
- terminal: `command + terminalSessionId`
- workspace/workbench: `action + path/file/project id`
- mcp: `serverName + toolName + compact target`

Retries are shown as step metadata, not as separate primary cards.

## Renderer Behavior

Collapsed row:

```text
Browser operation · 5 steps · success
```

Expanded details:

```text
navigate example.com
find_elements login
click Login
retry click Login
type password
```

Every step keeps its original `cardId`; tapping a step can open the matching
RunLog detail.

## Non-Goals

- Do not merge tool calls in RunLog.
- Do not change skill distillation input.
- Do not special-case only `browser_use`.
- Do not hide failures; summarize them on the parent activity and preserve step
  details.

## Rollout

1. Add the compactor and unit tests without wiring it into the UI.
2. Resolve timeline merge conflicts.
3. Change `AgentRunGroupMessage` process rendering from `ChatMessageModel[]` to
   `AgentProcessItem[]`.
4. Add activity renderers for browser and terminal first.
5. Reuse the same compactor in the tool activity strip and webchat.
