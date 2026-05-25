import 'dart:convert';

final RegExp _completeToolCallBlockRegex = RegExp(
  r'<tool_call\b[^>]*>[\s\S]*?</tool_call>',
  caseSensitive: false,
);

String stripInternalToolPayloadText(String text) {
  if (text.isEmpty) {
    return text;
  }
  var result = text;
  result = result.replaceAll(_completeToolCallBlockRegex, '');
  final incompleteToolCallStart = result.toLowerCase().indexOf('<tool_call');
  if (incompleteToolCallStart >= 0) {
    result = result.substring(0, incompleteToolCallStart);
  }
  return result.trim();
}

bool isLikelyInternalToolPayloadText(String text) {
  final normalizedText = text.trim();
  if (normalizedText.isEmpty) {
    return false;
  }
  if (stripInternalToolPayloadText(normalizedText).isEmpty &&
      normalizedText.toLowerCase().contains('<tool_call')) {
    return true;
  }

  try {
    final decoded = jsonDecode(normalizedText);
    if (decoded is Map) {
      final keys = decoded.keys.map((key) => key.toString()).toSet();
      final hasToolResultPayloadKey = keys.any(
        const <String>{
          'previewJson',
          'resultPreviewJson',
          'rawResultJson',
          'terminalOutput',
          'toolName',
          'toolType',
          'artifacts',
          'actions',
        }.contains,
      );
      final hasStatusOrSummaryKey = keys.any(
        const <String>{
          'success',
          'status',
          'summary',
          'progress',
          'completed',
        }.contains,
      );
      return hasToolResultPayloadKey && hasStatusOrSummaryKey;
    }
  } catch (_) {
    // Persisted snapshots can be trimmed or resume mid-payload; fall through to
    // a conservative field-pattern check.
  }

  final normalized = normalizedText.toLowerCase();
  final hasToolResultPayloadField =
      normalized.contains('"previewjson"') ||
      normalized.contains('"resultpreviewjson"') ||
      normalized.contains('"rawresultjson"') ||
      normalized.contains('"terminaloutput"') ||
      normalized.contains('"toolname"') ||
      normalized.contains('"tooltype"');
  final hasStatusOrSummaryField =
      normalized.contains('"summary"') ||
      normalized.contains('"progress"') ||
      normalized.contains('"success"') ||
      normalized.contains('"status"') ||
      normalized.contains('"completed"');
  return hasToolResultPayloadField && hasStatusOrSummaryField;
}
