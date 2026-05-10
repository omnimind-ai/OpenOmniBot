import 'package:ui/l10n/legacy_text_localizer.dart';

enum FavoriteRecordType {
  imageRecognition,  // 识图
  unknown,           // 未知类型
}

extension FavoriteRecordTypeX on FavoriteRecordType {
  String get value {
    switch (this) {
      case FavoriteRecordType.imageRecognition:
        return 'imageRecognition';
      case FavoriteRecordType.unknown:
        return 'unknown';
    }
  }

  String get label {
    switch (this) {
      case FavoriteRecordType.imageRecognition:
        return LegacyTextLocalizer.localize('识图');
      case FavoriteRecordType.unknown:
        return LegacyTextLocalizer.localize('未知类型');
    }
  }

  String get iconPath {
    switch (this) {
      case FavoriteRecordType.imageRecognition:
        return 'assets/memory/memory_context_icon.svg';
      case FavoriteRecordType.unknown:
        return 'assets/memory/memory_context_icon.svg';
    }
  }

  String get iconPathInBar {
    switch (this) {
      case FavoriteRecordType.imageRecognition:
        return 'assets/memory/memory_context_icon_dark.svg';
      case FavoriteRecordType.unknown:
        return 'assets/memory/memory_context_icon_dark.svg';
    }
  }

  static FavoriteRecordType fromValue(String? v) {
    if (v == null) return FavoriteRecordType.imageRecognition;
    return FavoriteRecordType.values.firstWhere(
      (e) => e.value == v,
      orElse: () => FavoriteRecordType.unknown,
    );
  }
}

class FavoriteRecord {
  final int id;
  final String title;
  final String desc;
  final FavoriteRecordType type; 
  final String imagePath;
  final String packageName; // 来源应用的包名
  final int createdAt;
  final int updatedAt;

  FavoriteRecord({
    required this.id,
    required this.title,
    required this.desc,
    this.type = FavoriteRecordType.imageRecognition,
    required this.imagePath,
    this.packageName = '',
    required this.createdAt,
    required this.updatedAt,
  });

  factory FavoriteRecord.fromMap(Map<dynamic, dynamic> map) {
    return FavoriteRecord(
      id: map['id'] as int,
      title: map['title'] as String,
      desc: map['desc'] as String,
      type: FavoriteRecordTypeX.fromValue(map['type'] as String?),
      imagePath: map['imagePath'] as String,
      packageName: map['packageName'] as String? ?? '',
      createdAt: map['createdAt'] as int,
      updatedAt: map['updatedAt'] as int,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'title': title,
      'desc': desc,
      'type': type.value,
      'imagePath': imagePath,
      'packageName': packageName,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
    };
  }

  @override
  String toString() {
    return 'FavoriteRecord(id: $id, title: $title, desc: $desc, type: $type, imagePath: $imagePath, packageName: $packageName, createdAt: $createdAt, updatedAt: $updatedAt)';
  }
}
