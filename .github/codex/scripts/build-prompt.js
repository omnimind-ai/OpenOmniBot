const fs = require('fs');
const path = require('path');

const workspace = process.env.GITHUB_WORKSPACE || process.cwd();
const configDir = process.env.CODEX_CONFIG_DIR || path.join(workspace, '.github/codex');
const promptPath = path.join(configDir, 'prompts/agent.md');
const schemaPath = path.join(configDir, 'schemas/result.schema.json');
const eventPath = process.env.GITHUB_EVENT_PATH;
const outputDir = path.join(workspace, '.codex');
const outputPath = path.join(outputDir, 'prompt.md');
const outputSchemaPath = path.join(outputDir, 'result.schema.json');

function env(name) {
  return process.env[name] || '';
}

function pickEventContext(event) {
  const issue = event.issue
    ? {
        number: event.issue.number,
        title: event.issue.title,
        body: event.issue.body,
        state: event.issue.state,
        author_association: event.issue.author_association,
        labels: (event.issue.labels || []).map((label) => label.name || label),
        html_url: event.issue.html_url,
        is_pull_request: Boolean(event.issue.pull_request),
      }
    : null;

  const pullRequest = event.pull_request
    ? {
        number: event.pull_request.number,
        title: event.pull_request.title,
        body: event.pull_request.body,
        state: event.pull_request.state,
        base: {
          ref: event.pull_request.base?.ref,
          sha: event.pull_request.base?.sha,
          repo: event.pull_request.base?.repo?.full_name,
        },
        head: {
          ref: event.pull_request.head?.ref,
          sha: event.pull_request.head?.sha,
          repo: event.pull_request.head?.repo?.full_name,
        },
        html_url: event.pull_request.html_url,
      }
    : null;

  const comment = event.comment
    ? {
        body: event.comment.body,
        path: event.comment.path,
        line: event.comment.line,
        side: event.comment.side,
        diff_hunk: event.comment.diff_hunk,
        html_url: event.comment.html_url,
        author_association: event.comment.author_association,
      }
    : null;

  return {
    action: event.action,
    sender: event.sender
      ? {
          login: event.sender.login,
          type: event.sender.type,
        }
      : null,
    issue,
    pull_request: pullRequest,
    comment,
  };
}

fs.mkdirSync(outputDir, { recursive: true });

const staticPrompt = fs.readFileSync(promptPath, 'utf8');
const event = eventPath ? JSON.parse(fs.readFileSync(eventPath, 'utf8')) : {};
const runtimeContext = {
  repository: env('GITHUB_REPOSITORY'),
  event_name: env('GITHUB_EVENT_NAME'),
  run_id: env('GITHUB_RUN_ID'),
  run_attempt: env('GITHUB_RUN_ATTEMPT'),
  actor: env('GITHUB_ACTOR'),
  actor_permission: env('CODEX_ACTOR_PERMISSION'),
  target: {
    kind: env('CODEX_TARGET_KIND'),
    number: env('CODEX_TARGET_NUMBER'),
    title: env('CODEX_TARGET_TITLE'),
    url: env('CODEX_TARGET_URL'),
  },
  task: env('CODEX_TASK'),
  execution: {
    logical_sandbox: env('CODEX_SANDBOX'),
    cli_sandbox: env('CODEX_CLI_SANDBOX'),
    write_allowed: env('CODEX_WRITE_ALLOWED') === 'true',
    publish_mode: env('CODEX_PUBLISH_MODE'),
    bot_branch: env('CODEX_BRANCH_NAME'),
  },
  refs: {
    base_ref: env('CODEX_BASE_REF'),
    head_ref: env('CODEX_HEAD_REF'),
    head_repo: env('CODEX_HEAD_REPO'),
  },
  event: pickEventContext(event),
};

const prompt = `${staticPrompt}

## Runtime Context
Use this trusted runtime context to understand the trigger and allowed behavior.

\`\`\`json
${JSON.stringify(runtimeContext, null, 2)}
\`\`\`

## Task
${env('CODEX_TASK')}
`;

fs.writeFileSync(outputPath, prompt);
fs.copyFileSync(schemaPath, outputSchemaPath);
console.log(`Wrote Codex prompt to ${path.relative(workspace, outputPath)}`);
