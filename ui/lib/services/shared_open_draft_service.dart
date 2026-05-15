import 'package:ui/services/app_state_service.dart';

class SharedOpenDraftAttachmentPayload {
  const SharedOpenDraftAttachmentPayload({
    required this.id,
    required this.name,
    required this.path,
    required this.size,
    required this.mimeType,
    required this.isImage,
    required this.promptPath,
    required this.sendToModel,
  });

  final String id;
  final String name;
  final String path;
  final int? size;
  final String? mimeType;
  final bool isImage;
  final String? promptPath;
  final bool sendToModel;

  factory SharedOpenDraftAttachmentPayload.fromMap(Map<dynamic, dynamic> map) {
    final rawSize = map['size'];
    final size = rawSize is int
        ? rawSize
        : rawSize is num
        ? rawSize.toInt()
        : int.tryParse(rawSize?.toString() ?? '');
    return SharedOpenDraftAttachmentPayload(
      id: (map['id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      path: (map['path'] ?? '').toString(),
      size: size,
      mimeType: (map['mimeType'] as String?)?.trim(),
      isImage: map['isImage'] == true,
      promptPath: (map['promptPath'] as String?)?.trim(),
      sendToModel: map['sendToModel'] != false,
    );
  }
}

class SharedOpenDraftPayload {
  const SharedOpenDraftPayload({
    required this.requestKey,
    required this.text,
    required this.attachments,
  });

  final String requestKey;
  final String? text;
  final List<SharedOpenDraftAttachmentPayload> attachments;

  bool get hasContent =>
      (text?.trim().isNotEmpty ?? false) || attachments.isNotEmpty;

  factory SharedOpenDraftPayload.fromMap(Map<dynamic, dynamic> map) {
    final rawAttachments = map['attachments'] as List<dynamic>? ?? const [];
    final normalizedText = (map['text'] as String?)?.trim();
    return SharedOpenDraftPayload(
      requestKey: (map['requestKey'] ?? '').toString(),
      text: (normalizedText == null || normalizedText.isEmpty)
          ? null
          : normalizedText,
      attachments: rawAttachments
          .whereType<Map<dynamic, dynamic>>()
          .map(SharedOpenDraftAttachmentPayload.fromMap)
          .where((item) => item.path.trim().isNotEmpty)
          .toList(),
    );
  }
}

class SharedOpenDraftService {
  static Future<SharedOpenDraftPayload?> getPendingDraft() async {
    final map = await AppStateService.getPendingShareDraft();
    if (map == null || map.isEmpty) {
      return null;
    }
    final payload = SharedOpenDraftPayload.fromMap(map);
    return payload.hasContent ? payload : null;
  }

  static Future<void> clearPendingDraft() async {
    await AppStateService.clearPendingShareDraft();
  }
}
