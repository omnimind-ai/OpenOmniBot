import 'package:ui/l10n/legacy_text_localizer.dart';

enum ExecutionRecordType {
  system,
  vlm,
  summary,
  unknown,
}

extension ExecutionRecordTypeX on ExecutionRecordType {
  String get value {
    switch (this) {
      case ExecutionRecordType.system:
        return 'system';
      case ExecutionRecordType.vlm:
        return 'vlm';
      case ExecutionRecordType.summary:
        return 'summary';
      case ExecutionRecordType.unknown:
        return 'unknown';
    }
  }

  String get label {
    switch (this) {
      case ExecutionRecordType.system:
        return LegacyTextLocalizer.localize('系统');
      case ExecutionRecordType.vlm:
        return 'VLM';
      case ExecutionRecordType.summary:
        return LegacyTextLocalizer.localize('总结');
      case ExecutionRecordType.unknown:
        return LegacyTextLocalizer.localize('未知');
    }
  }

  String get defaultIconPath {
    return '';
  }

  int get defaultIconColor {
    return 0x00000000;
  }

  static ExecutionRecordType fromValue(String? v) {
    switch (v) {
      case 'system':
        return ExecutionRecordType.system;
      case 'vlm':
        return ExecutionRecordType.vlm;
      case 'summary':
        return ExecutionRecordType.summary;
      case 'learning':
        return ExecutionRecordType.unknown;
      default:
        return ExecutionRecordType.unknown;
    }
  }
}

enum ExecutionStatus {
  running,
  success,
  failed,
  cancelled,
  waiting,
  paused,
}

extension ExecutionStatusX on ExecutionStatus {
  String get value {
    switch (this) {
      case ExecutionStatus.running:
        return 'running';
      case ExecutionStatus.success:
        return 'success';
      case ExecutionStatus.failed:
        return 'failed';
      case ExecutionStatus.cancelled:
        return 'cancelled';
      case ExecutionStatus.waiting:
        return 'waiting';
      case ExecutionStatus.paused:
        return 'paused';
    }
  }

  String get displayName {
    switch (this) {
      case ExecutionStatus.running:
        return LegacyTextLocalizer.localize('执行中');
      case ExecutionStatus.success:
        return LegacyTextLocalizer.localize('执行成功');
      case ExecutionStatus.failed:
        return LegacyTextLocalizer.localize('执行失败');
      case ExecutionStatus.cancelled:
        return LegacyTextLocalizer.localize('已取消');
      case ExecutionStatus.waiting:
        return LegacyTextLocalizer.localize('等待执行');
      case ExecutionStatus.paused:
        return LegacyTextLocalizer.localize('已暂停');
    }
  }

  static ExecutionStatus fromValue(String? v) {
    if (v == null) return ExecutionStatus.running;
    return ExecutionStatus.values.firstWhere(
      (e) => e.value == v,
      orElse: () => ExecutionStatus.running,
    );
  }
}

class ExecutionRecord {
  final int id;
  final String title;
  final String appName;
  final String packageName;
  final String nodeId;
  final String suggestionId;
  final String? iconUrl;
  final ExecutionRecordType type;
  final String? content;
  final ExecutionStatus status;
  final int createdAt;
  final int updatedAt;

  ExecutionRecord({
    required this.id,
    required this.title,
    required this.appName,
    required this.packageName,
    required this.nodeId,
    required this.suggestionId,
    this.iconUrl,
    this.type = ExecutionRecordType.unknown,
    this.content,
    this.status = ExecutionStatus.running,
    required this.createdAt,
    required this.updatedAt,
  });

  factory ExecutionRecord.fromMap(Map<dynamic, dynamic> map) {
    return ExecutionRecord(
      id: map['id'] as int,
      title: map['title'] as String,
      appName: map['appName'] as String,
      packageName: map['packageName'] as String,
      nodeId: map['nodeId'] as String? ?? '',
      suggestionId: map['suggestionId'] as String? ?? '',
      iconUrl: map['iconUrl'] as String?,
      type: ExecutionRecordTypeX.fromValue(map['type'] as String?),
      content: map['content'] as String?,
      status: ExecutionStatusX.fromValue(map['status'] as String?),
      createdAt: map['createdAt'] as int,
      updatedAt: map['updatedAt'] as int,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'title': title,
      'appName': appName,
      'packageName': packageName,
      'nodeId': nodeId,
      'suggestionId': suggestionId,
      'iconUrl': iconUrl,
      'type': type.value,
      'content': content,
      'status': status.value,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
    };
  }

  @override
  String toString() {
    return 'ExecutionRecord(id: $id, title: $title, appName: $appName, packageName: $packageName, nodeId: $nodeId, suggestionId: $suggestionId, iconUrl: $iconUrl, type: $type, content: $content, status: $status, createdAt: $createdAt, updatedAt: $updatedAt)';
  }
}
