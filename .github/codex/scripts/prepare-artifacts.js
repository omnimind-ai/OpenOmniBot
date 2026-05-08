const fs = require('fs');
const path = require('path');
const { execFileSync, spawnSync } = require('child_process');

const workspace = process.env.GITHUB_WORKSPACE || process.cwd();
const codexDir = path.join(workspace, '.codex');
const outputDir = path.join(codexDir, 'out');
const finalPath = path.join(codexDir, 'final.json');
const resultPath = path.join(outputDir, 'result.json');
const patchPath = path.join(outputDir, 'codex.patch');

function runGit(args, options = {}) {
  const safeArgs = [
    '-c',
    'core.fsmonitor=false',
    '-c',
    'core.hooksPath=/dev/null',
    '-c',
    'diff.external=',
    ...args,
  ];
  return execFileSync('git', safeArgs, {
    cwd: workspace,
    encoding: options.encoding || 'utf8',
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
  });
}

function runGitDiffAllowDifference(args) {
  const safeArgs = [
    '-c',
    'core.fsmonitor=false',
    '-c',
    'core.hooksPath=/dev/null',
    '-c',
    'diff.external=',
    ...args,
  ];
  const result = spawnSync('git', safeArgs, {
    cwd: workspace,
    encoding: 'utf8',
  });
  if (result.status !== 0 && result.status !== 1) {
    throw new Error(result.stderr || `git ${args.join(' ')} failed with status ${result.status}`);
  }
  return result.stdout || '';
}

function writeOutput(name, value) {
  const outputFile = process.env.GITHUB_OUTPUT;
  if (!outputFile) {
    return;
  }
  fs.appendFileSync(outputFile, `${name}=${value}\n`);
}

function parseFinalMessage() {
  const candidates = [];
  if (fs.existsSync(finalPath)) {
    candidates.push(fs.readFileSync(finalPath, 'utf8'));
  }
  if (process.env.CODEX_FINAL_MESSAGE) {
    candidates.push(process.env.CODEX_FINAL_MESSAGE);
  }

  for (const candidate of candidates) {
    const trimmed = candidate.trim();
    if (!trimmed) {
      continue;
    }
    try {
      return JSON.parse(trimmed);
    } catch {
      const match = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
      if (match) {
        try {
          return JSON.parse(match[1].trim());
        } catch {
          // Try the next candidate.
        }
      }
    }
  }

  return {
    status: 'comment_only',
    summary: 'Codex did not return structured output.',
    comment: (process.env.CODEX_FINAL_MESSAGE || '').trim() || 'Codex did not return a final message.',
    pr_title: '',
    pr_body: '',
    verification: 'No structured verification was returned by Codex.',
    changed_files: [],
  };
}

function normalizeResult(result) {
  const allowedStatuses = new Set(['comment_only', 'needs_info', 'code_change', 'no_op']);
  const normalized = {
    status: allowedStatuses.has(result.status) ? result.status : 'comment_only',
    summary: String(result.summary || '').trim(),
    comment: String(result.comment || '').trim(),
    pr_title: String(result.pr_title || '').trim(),
    pr_body: String(result.pr_body || '').trim(),
    verification: String(result.verification || '').trim(),
    changed_files: Array.isArray(result.changed_files)
      ? result.changed_files.map((file) => String(file)).filter(Boolean)
      : [],
  };

  if (!normalized.summary) {
    normalized.summary = normalized.status === 'code_change'
      ? 'Codex prepared repository changes.'
      : 'Codex completed the requested inspection.';
  }
  if (!normalized.comment) {
    normalized.comment = normalized.summary;
  }
  return normalized;
}

fs.mkdirSync(outputDir, { recursive: true });

const stagedChangedFiles = runGit(['diff', '--cached', '--name-only', '--']).split('\n').filter(Boolean);
const unstagedChangedFiles = runGit(['diff', '--name-only', '--']).split('\n').filter(Boolean);
const untracked = runGit(['ls-files', '--others', '--exclude-standard', '-z'], { encoding: 'buffer' });
const untrackedFiles = untracked
  .toString('utf8')
  .split('\0')
  .filter((file) => file && !file.startsWith('.codex/'));
const stagedChangedFileSet = new Set(stagedChangedFiles);
// If a path has staged changes, treat the index as Codex's intended result.
// This prevents index-only gitlink fixes from being canceled by submodule worktree state.
const unstagedOnlyFiles = unstagedChangedFiles.filter((file) => !stagedChangedFileSet.has(file));
const changedFiles = Array.from(new Set([...stagedChangedFiles, ...unstagedOnlyFiles, ...untrackedFiles]));
const patchParts = [
  runGit(['diff', '--cached', '--binary', '--no-ext-diff', '--']),
];
if (unstagedOnlyFiles.length > 0) {
  patchParts.push(runGit(['diff', '--binary', '--no-ext-diff', '--', ...unstagedOnlyFiles]));
}
for (const file of untrackedFiles) {
  patchParts.push(runGitDiffAllowDifference(['diff', '--no-index', '--binary', '--no-ext-diff', '--', '/dev/null', file]));
}
const patch = patchParts
  .map((part) => part.trimEnd())
  .filter(Boolean)
  .join('\n');
fs.writeFileSync(patchPath, patch ? `${patch}\n` : '');
fs.writeFileSync(path.join(outputDir, 'changed-files.txt'), `${changedFiles.join('\n')}${changedFiles.length ? '\n' : ''}`);

const result = normalizeResult(parseFinalMessage());
if (changedFiles.length > 0 && result.status !== 'code_change') {
  result.status = 'code_change';
}
if (changedFiles.length > 0) {
  result.changed_files = Array.from(new Set([...result.changed_files, ...changedFiles]));
}

fs.writeFileSync(resultPath, `${JSON.stringify(result, null, 2)}\n`);
writeOutput('result_status', result.status);
writeOutput('has_patch', patch.trim() ? 'true' : 'false');

console.log(`Prepared Codex result: ${result.status}`);
console.log(`Changed files: ${changedFiles.length ? changedFiles.join(', ') : '(none)'}`);
