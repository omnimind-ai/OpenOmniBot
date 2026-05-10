enum CodexSlashSubmitKind {
  none,
  openModelPicker,
  selectModel,
  startReview,
  startInit,
  activatePlan,
  startPlan,
  unsupported,
}

class CodexSlashSubmitIntent {
  const CodexSlashSubmitIntent(this.kind, {this.value});

  final CodexSlashSubmitKind kind;
  final String? value;
}

CodexSlashSubmitIntent resolveCodexSlashSubmitIntent(String messageText) {
  final trimmed = messageText.trim();
  if (!trimmed.startsWith('/')) {
    return const CodexSlashSubmitIntent(CodexSlashSubmitKind.none);
  }

  final normalized = trimmed.toLowerCase();
  if (normalized == '/model') {
    return const CodexSlashSubmitIntent(CodexSlashSubmitKind.openModelPicker);
  }
  if (normalized.startsWith('/model ')) {
    final modelId = trimmed.substring('/model'.length).trim();
    if (modelId.isEmpty) {
      return const CodexSlashSubmitIntent(CodexSlashSubmitKind.openModelPicker);
    }
    return CodexSlashSubmitIntent(
      CodexSlashSubmitKind.selectModel,
      value: modelId,
    );
  }

  if (normalized == '/review') {
    return const CodexSlashSubmitIntent(CodexSlashSubmitKind.startReview);
  }
  if (normalized == '/init') {
    return const CodexSlashSubmitIntent(CodexSlashSubmitKind.startInit);
  }
  if (normalized == '/plan') {
    return const CodexSlashSubmitIntent(CodexSlashSubmitKind.activatePlan);
  }
  if (normalized.startsWith('/plan ')) {
    final prompt = trimmed.substring('/plan'.length).trim();
    if (prompt.isEmpty) {
      return const CodexSlashSubmitIntent(CodexSlashSubmitKind.activatePlan);
    }
    return CodexSlashSubmitIntent(
      CodexSlashSubmitKind.startPlan,
      value: prompt,
    );
  }

  return const CodexSlashSubmitIntent(CodexSlashSubmitKind.unsupported);
}
