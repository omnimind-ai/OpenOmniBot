import 'package:ui/services/storage_service.dart';

class ChatDetailSheetPreferences {
  ChatDetailSheetPreferences._();

  static const String _heightFactorKey = 'chat_detail_sheet_height_factor';

  static double? _cachedHeightFactor;

  static double resolveHeightFactor({
    required double fallback,
    required double min,
    required double max,
  }) {
    final cached =
        _cachedHeightFactor ?? StorageService.getDouble(_heightFactorKey);
    if (cached == null) {
      return fallback.clamp(min, max).toDouble();
    }
    final normalized = cached.clamp(min, max).toDouble();
    _cachedHeightFactor = normalized;
    return normalized;
  }

  static Future<void> saveHeightFactor(
    double heightFactor, {
    required double min,
    required double max,
  }) async {
    final normalized = heightFactor.clamp(min, max).toDouble();
    _cachedHeightFactor = normalized;
    await StorageService.setDouble(_heightFactorKey, normalized);
  }
}
