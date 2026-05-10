const int kMaxPersistedThinkingChars = 16 * 1024;
const String _thinkingTruncationNotice = '[Earlier reasoning omitted]\n';

Map<String, dynamic> buildPersistentDeepThinkingCardData(
  Map<String, dynamic> cardData,
) {
  final result = Map<String, dynamic>.from(cardData);
  final thinking = (result['thinkingContent'] ?? '').toString();
  final originalLength = thinking.length;
  if (originalLength <= kMaxPersistedThinkingChars) {
    result['thinkingContentTruncated'] = false;
    result['thinkingOriginalLength'] = originalLength;
    result['thinkingTruncateMode'] = 'none';
    return result;
  }

  final bodyLimit =
      kMaxPersistedThinkingChars - _thinkingTruncationNotice.length;
  final tail = _takeLastRunes(thinking, bodyLimit < 0 ? 0 : bodyLimit);
  result['thinkingContent'] = '$_thinkingTruncationNotice$tail';
  result['thinkingContentTruncated'] = true;
  result['thinkingOriginalLength'] = originalLength;
  result['thinkingTruncateMode'] = 'head_omitted';
  return result;
}

String _takeLastRunes(String value, int maxRunes) {
  if (maxRunes <= 0 || value.isEmpty) return '';
  final runes = value.runes.toList(growable: false);
  if (runes.length <= maxRunes) return value;
  return String.fromCharCodes(runes.skip(runes.length - maxRunes));
}
