import 'package:ui/models/agent_stream_event.dart';

Map<String, dynamic> buildAgentStreamMetaFromEvent(AgentStreamEvent event) {
  final rawStreamMeta = event.raw['streamMeta'];
  final existing = rawStreamMeta is Map
      ? rawStreamMeta.map((key, value) => MapEntry(key.toString(), value))
      : null;
  return ensureAgentStreamMessageMeta(
        existing,
        seq: _asInt(event.raw['seq']) ?? event.seq,
        roundIndex: _asInt(event.raw['roundIndex']) ?? event.roundIndex,
        kind: event.kind.value,
        parentTaskId: event.taskId,
        entryId: event.entryId,
        isFinal: event.isFinal,
      ) ??
      <String, dynamic>{};
}

Map<String, dynamic>? ensureAgentStreamMessageMeta(
  Map<String, dynamic>? streamMeta, {
  int? seq,
  int? roundIndex,
  String? kind,
  String? parentTaskId,
  String? entryId,
  bool isFinal = false,
}) {
  final normalized = Map<String, dynamic>.from(streamMeta ?? const {});
  final hasInput =
      normalized.isNotEmpty ||
      seq != null ||
      roundIndex != null ||
      (kind?.trim().isNotEmpty ?? false) ||
      (parentTaskId?.trim().isNotEmpty ?? false) ||
      (entryId?.trim().isNotEmpty ?? false) ||
      isFinal;
  if (!hasInput) {
    return null;
  }

  if (seq != null) {
    normalized['seq'] = seq;
  }
  if (roundIndex != null) {
    normalized['roundIndex'] = roundIndex;
  }
  final normalizedKind = kind?.trim() ?? '';
  if (normalizedKind.isNotEmpty) {
    normalized['kind'] = normalizedKind;
  }
  final normalizedTaskId = parentTaskId?.trim() ?? '';
  if (normalizedTaskId.isNotEmpty) {
    normalized['parentTaskId'] = normalizedTaskId;
  }
  final normalizedEntryId = entryId?.trim() ?? '';
  if (normalizedEntryId.isNotEmpty) {
    normalized['entryId'] = normalizedEntryId;
  }

  normalized['isFinal'] = isFinal || normalized['isFinal'] == true;
  return normalized;
}

int? _asInt(dynamic raw) {
  if (raw is int) {
    return raw;
  }
  if (raw is num) {
    final asDouble = raw.toDouble();
    if (asDouble.isFinite && asDouble == asDouble.truncateToDouble()) {
      return raw.toInt();
    }
  }
  if (raw is String) {
    return int.tryParse(raw.trim());
  }
  return null;
}
