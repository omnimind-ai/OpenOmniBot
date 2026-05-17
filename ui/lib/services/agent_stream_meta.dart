import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_tool_card_policy.dart';

class AgentStreamUiCard {
  const AgentStreamUiCard({required this.id, required this.cardData});

  final String id;
  final Map<String, dynamic> cardData;
}

String resolveAgentToolCardId(
  AgentStreamEvent event, {
  Map<dynamic, dynamic>? raw,
}) {
  return AgentToolCardPolicy.cardIdForEvent(event, raw: raw);
}

List<AgentStreamUiCard> extractAgentStreamUiCards(AgentStreamEvent event) {
  final candidates = <Map<String, dynamic>>[];
  final rawCards = event.raw['cards'] ?? event.raw['uiCards'];
  if (rawCards is Iterable) {
    for (final item in rawCards) {
      final card = _asStringMap(item);
      if (card != null) {
        candidates.add(card);
      }
    }
  }

  final embeddedCardData =
      _asStringMap(event.raw['cardData']) ?? _asStringMap(event.raw['card']);
  if (embeddedCardData != null) {
    candidates.add(embeddedCardData);
  }

  final envelopeCard = _cardDataFromEventEnvelope(event.raw);
  if (envelopeCard != null) {
    candidates.add(envelopeCard);
  }

  final cards = <AgentStreamUiCard>[];
  for (var index = 0; index < candidates.length; index++) {
    final cardData = Map<String, dynamic>.from(candidates[index]);
    final type = (cardData['type'] ?? '').toString().trim();
    if (type.isEmpty) {
      continue;
    }
    final explicitId = _firstNonEmpty([
      cardData['cardId'],
      cardData['card_id'],
      cardData['id'],
    ]);
    final fallbackId = candidates.length == 1
        ? (event.entryId ?? '')
        : '${event.entryId ?? event.taskId}-card-${index + 1}';
    final cardId = _firstNonEmpty([explicitId, fallbackId]);
    if (cardId.isEmpty) {
      continue;
    }
    cardData['cardId'] = _firstNonEmpty([cardData['cardId'], cardId]);
    cardData.putIfAbsent('taskId', () => event.taskId);
    cards.add(AgentStreamUiCard(id: cardId, cardData: cardData));
  }
  return cards;
}

Map<String, dynamic>? _cardDataFromEventEnvelope(Map<String, dynamic> raw) {
  final type = (raw['type'] ?? '').toString().trim();
  if (type.isEmpty) {
    return null;
  }
  final cardData = Map<String, dynamic>.from(raw);
  for (final key in const <String>{
    'conversationId',
    'conversationMode',
    'schema_version',
    'trace_id',
    'run_id',
    'span_id',
    'parent_span_id',
    'channel',
    'event',
    'timestamp_ms',
    'status',
    'seq',
    'kind',
    'createdAt',
    'entryId',
    'roundIndex',
    'isFinal',
    'streamMeta',
    'text',
    'message',
    'thinking',
    'stage',
    'prefillTokensPerSecond',
    'decodeTokensPerSecond',
    'outputKind',
    'hasUserVisibleOutput',
    'latestPromptTokens',
    'promptTokenThreshold',
    'error',
    'question',
    'missingFields',
    'missing',
    'dialog',
  }) {
    cardData.remove(key);
  }
  return cardData;
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is Map<String, dynamic>) {
    return value;
  }
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return null;
}

Map<String, dynamic> buildAgentStreamMetaFromEvent(AgentStreamEvent event) {
  final rawStreamMeta = event.raw['streamMeta'];
  final existing = rawStreamMeta is Map
      ? rawStreamMeta.map((key, value) => MapEntry(key.toString(), value))
      : null;
  return ensureAgentStreamMessageMeta(
        existing,
        seq: existing?.containsKey('seq') == true
            ? null
            : (_asInt(event.raw['seq']) ?? event.seq),
        roundIndex: existing?.containsKey('roundIndex') == true
            ? null
            : (_asInt(event.raw['roundIndex']) ?? event.roundIndex),
        kind: existing?.containsKey('kind') == true ? null : event.kind.value,
        parentTaskId: existing?.containsKey('parentTaskId') == true
            ? null
            : event.taskId,
        entryId: existing?.containsKey('entryId') == true
            ? null
            : event.entryId,
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

String _firstNonEmpty(Iterable<dynamic> values) {
  for (final value in values) {
    final normalized = value?.toString().trim() ?? '';
    if (normalized.isNotEmpty) {
      return normalized;
    }
  }
  return '';
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
