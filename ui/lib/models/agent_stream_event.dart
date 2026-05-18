import 'package:ui/features/home/pages/chat/chat_page_models.dart';

enum AgentStreamEventKind {
  thinkingStarted('thinking_started'),
  thinkingSnapshot('thinking_snapshot'),
  textSnapshot('text_snapshot'),
  toolStarted('tool_started'),
  toolProgress('tool_progress'),
  toolCompleted('tool_completed'),
  workbenchProjectCard('workbench_project_card'),
  completed('completed'),
  error('error'),
  permissionRequired('permission_required'),
  clarifyRequired('clarify_required');

  const AgentStreamEventKind(this.value);

  final String value;

  static AgentStreamEventKind? fromValue(String raw) {
    final normalized = raw.trim().toLowerCase();
    for (final kind in AgentStreamEventKind.values) {
      if (kind.value == normalized) {
        return kind;
      }
    }
    return null;
  }
}

enum AgentStreamPhase {
  idle,
  thinking,
  tool,
  output,
  completed,
  error,
  clarify,
  permissionRequired,
}

class UserDialogChoice {
  const UserDialogChoice({required this.label, required this.value, this.hint});

  final String label;
  final String value;
  final String? hint;

  static UserDialogChoice? tryParse(dynamic raw) {
    if (raw is! Map) return null;
    final label = raw['label']?.toString() ?? '';
    final value = raw['value']?.toString() ?? '';
    if (label.isEmpty || value.isEmpty) return null;
    return UserDialogChoice(
      label: label,
      value: value,
      hint: raw['hint']?.toString(),
    );
  }
}

class UserDialog {
  const UserDialog({
    required this.type,
    required this.message,
    this.title,
    this.confirmLabel,
    this.cancelLabel,
    this.danger = false,
    this.choices = const [],
    this.placeholder,
    this.inputType,
  });

  final String type; // confirm | choices | input
  final String message;
  final String? title;
  final String? confirmLabel;
  final String? cancelLabel;
  final bool danger;
  final List<UserDialogChoice> choices;
  final String? placeholder;
  final String? inputType;

  static UserDialog? tryParse(dynamic raw) {
    if (raw is! Map) return null;
    final type = raw['type']?.toString() ?? '';
    final message = raw['message']?.toString() ?? '';
    if (type.isEmpty || message.isEmpty) return null;
    final choicesRaw = raw['choices'];
    final choices = choicesRaw is List
        ? choicesRaw
              .map(UserDialogChoice.tryParse)
              .whereType<UserDialogChoice>()
              .toList()
        : <UserDialogChoice>[];
    return UserDialog(
      type: type,
      message: message,
      title: raw['title']?.toString(),
      confirmLabel: raw['confirmLabel']?.toString(),
      cancelLabel: raw['cancelLabel']?.toString(),
      danger: raw['danger'] == true,
      choices: choices,
      placeholder: raw['placeholder']?.toString(),
      inputType: raw['inputType']?.toString(),
    );
  }
}

class AgentStreamEvent {
  const AgentStreamEvent({
    required this.taskId,
    required this.seq,
    required this.kind,
    required this.createdAtMs,
    this.schemaVersion = '',
    this.traceId = '',
    this.runId = '',
    this.spanId = '',
    this.parentSpanId = '',
    this.channel = '',
    this.eventName = '',
    this.status = '',
    this.entryId,
    this.roundIndex = 0,
    this.isFinal = false,
    this.text = '',
    this.thinking = '',
    this.stage = 1,
    this.prefillTokensPerSecond,
    this.decodeTokensPerSecond,
    this.success = true,
    this.outputKind = 'none',
    this.hasUserVisibleOutput = false,
    this.latestPromptTokens,
    this.promptTokenThreshold,
    this.errorMessage = '',
    this.question = '',
    this.missingFields = const <String>[],
    this.missingPermissions = const <String>[],
    this.dialog,
    this.browserSnapshot,
    this.raw = const <String, dynamic>{},
  });

  final String taskId;
  final int seq;
  final AgentStreamEventKind kind;
  final int createdAtMs;
  final String schemaVersion;
  final String traceId;
  final String runId;
  final String spanId;
  final String parentSpanId;
  final String channel;
  final String eventName;
  final String status;
  final String? entryId;
  final int roundIndex;
  final bool isFinal;
  final String text;
  final String thinking;
  final int stage;
  final double? prefillTokensPerSecond;
  final double? decodeTokensPerSecond;
  final bool success;
  final String outputKind;
  final bool hasUserVisibleOutput;
  final int? latestPromptTokens;
  final int? promptTokenThreshold;
  final String errorMessage;
  final String question;
  final List<String> missingFields;
  final List<String> missingPermissions;
  final UserDialog? dialog;
  final ChatBrowserSessionSnapshot? browserSnapshot;
  final Map<String, dynamic> raw;

  factory AgentStreamEvent.fromMap(Map<dynamic, dynamic>? map) {
    final raw = Map<String, dynamic>.from(
      (map ?? const <String, dynamic>{}).map(
        (key, value) => MapEntry(key.toString(), value),
      ),
    );
    final eventName = (raw['event'] ?? raw['kind'] ?? '').toString();
    final kind = AgentStreamEventKind.fromValue(eventName);
    if (kind == null) {
      throw ArgumentError('Unknown agent stream event kind: $eventName');
    }
    final taskId = (raw['taskId'] ?? '').toString();
    if (taskId.trim().isEmpty) {
      throw ArgumentError('Agent stream event missing taskId');
    }
    final workspaceId = (raw['workspaceId'] ?? '').toString().trim();
    final browserSnapshot =
        kind == AgentStreamEventKind.toolCompleted &&
            (raw['toolType'] ?? '').toString().trim() == 'browser' &&
            workspaceId.isNotEmpty
        ? (ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
                rawJson: (raw['rawResultJson'] ?? '').toString(),
                workspaceId: workspaceId,
              ) ??
              ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
                rawJson: (raw['resultPreviewJson'] ?? '').toString(),
                workspaceId: workspaceId,
              ))
        : null;
    return AgentStreamEvent(
      taskId: taskId,
      seq: _asInt(raw['seq']) ?? 0,
      kind: kind,
      createdAtMs:
          _asInt(raw['timestamp_ms']) ??
          _asInt(raw['createdAt']) ??
          DateTime.now().millisecondsSinceEpoch,
      schemaVersion: (raw['schema_version'] ?? '').toString(),
      traceId: (raw['trace_id'] ?? '').toString(),
      runId: (raw['run_id'] ?? '').toString(),
      spanId: (raw['span_id'] ?? '').toString(),
      parentSpanId: (raw['parent_span_id'] ?? '').toString(),
      channel: (raw['channel'] ?? '').toString(),
      eventName: eventName,
      status: (raw['status'] ?? '').toString(),
      entryId: raw['entryId']?.toString(),
      roundIndex: _asInt(raw['roundIndex']) ?? 0,
      isFinal: raw['isFinal'] == true,
      text: (raw['text'] ?? raw['message'] ?? '').toString(),
      thinking:
          (raw['thinking'] ?? raw['reasoning_content'] ?? '').toString(),
      stage: _asInt(raw['stage']) ?? 1,
      prefillTokensPerSecond: _asDouble(raw['prefillTokensPerSecond']),
      decodeTokensPerSecond: _asDouble(raw['decodeTokensPerSecond']),
      success: raw['success'] != false,
      outputKind: (raw['outputKind'] ?? 'none').toString(),
      hasUserVisibleOutput: raw['hasUserVisibleOutput'] == true,
      latestPromptTokens: _asInt(raw['latestPromptTokens']),
      promptTokenThreshold: _asInt(raw['promptTokenThreshold']),
      errorMessage: (raw['error'] ?? '').toString(),
      question: (raw['question'] ?? '').toString(),
      missingFields:
          (raw['missingFields'] as List<dynamic>?)
              ?.map((item) => item.toString())
              .toList(growable: false) ??
          const <String>[],
      missingPermissions:
          (raw['missing'] as List<dynamic>?)
              ?.map((item) => item.toString())
              .toList(growable: false) ??
          const <String>[],
      dialog: UserDialog.tryParse(raw['dialog']),
      browserSnapshot: browserSnapshot,
      raw: raw,
    );
  }

  static int? _asInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw.trim());
    return null;
  }

  static double? _asDouble(dynamic raw) {
    if (raw is double) return raw;
    if (raw is num) return raw.toDouble();
    if (raw is String) return double.tryParse(raw.trim());
    return null;
  }
}
