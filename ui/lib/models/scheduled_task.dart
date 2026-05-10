import 'package:ui/l10n/legacy_text_localizer.dart';

/// 定时任务类型
enum ScheduledTaskType {
  /// 固定时间（如每天8:00）
  fixedTime,

  /// 倒计时（如30分钟后）
  countdown,
}

/// 定时任务模型
class ScheduledTask {
  /// 唯一标识符
  final String id;

  /// 任务标题
  final String title;

  /// 关联的包名
  final String packageName;

  /// 关联的nodeId
  final String nodeId;

  /// 关联的suggestionId
  final String suggestionId;

  /// 目标类型：vlm
  final String targetKind;

  /// subagent 固定线程 conversationId
  final String? subagentConversationId;

  /// subagent 任务提示词
  final String? subagentPrompt;

  /// 执行完成是否通知
  final bool notificationEnabled;

  /// 定时任务类型
  final ScheduledTaskType type;

  /// 固定时间（仅当type为fixedTime时有效）
  /// 格式: "HH:mm"
  final String? fixedTime;

  /// 倒计时分钟数（仅当type为countdown时有效）
  final int? countdownMinutes;

  /// 是否每日重复执行
  final bool repeatDaily;

  /// 是否启用
  final bool isEnabled;

  /// 创建时间
  final int createdAt;

  /// 下次执行时间（毫秒时间戳）
  final int? nextExecutionTime;

  /// 完整的suggestion数据，用于执行任务
  final Map<String, dynamic>? suggestionData;

  /// 应用图标URL
  final String? appIconUrl;

  /// 任务类型图标URL
  final String? typeIconUrl;

  ScheduledTask({
    required this.id,
    required this.title,
    required this.packageName,
    required this.nodeId,
    required this.suggestionId,
    this.targetKind = 'vlm',
    this.subagentConversationId,
    this.subagentPrompt,
    this.notificationEnabled = true,
    required this.type,
    this.fixedTime,
    this.countdownMinutes,
    this.repeatDaily = false,
    this.isEnabled = true,
    required this.createdAt,
    this.nextExecutionTime,
    this.suggestionData,
    this.appIconUrl,
    this.typeIconUrl,
  });

  /// 从JSON创建
  factory ScheduledTask.fromJson(Map<String, dynamic> json) {
    final targetKindFromJson =
        json['targetKind'] as String? ?? 'vlm';
    final rawSuggestionData = json['suggestionData'] != null
        ? Map<String, dynamic>.from(json['suggestionData'] as Map)
        : <String, dynamic>{};

    return ScheduledTask(
      id: json['id'] as String,
      title: json['title'] as String,
      packageName: json['packageName'] as String,
      nodeId: json['nodeId'] as String? ?? '',
      suggestionId: json['suggestionId'] as String? ?? '',
      targetKind: targetKindFromJson,
      subagentConversationId: json['subagentConversationId'] as String?,
      subagentPrompt: json['subagentPrompt'] as String?,
      notificationEnabled: json['notificationEnabled'] as bool? ?? true,
      type: ScheduledTaskType.values.firstWhere(
        (e) => e.name == json['type'],
        orElse: () => ScheduledTaskType.fixedTime,
      ),
      fixedTime: json['fixedTime'] as String?,
      countdownMinutes: json['countdownMinutes'] as int?,
      repeatDaily: json['repeatDaily'] as bool? ?? false,
      isEnabled: json['isEnabled'] as bool? ?? true,
      createdAt: json['createdAt'] as int,
      nextExecutionTime: json['nextExecutionTime'] as int?,
      suggestionData: rawSuggestionData,
      appIconUrl: json['appIconUrl'] as String?,
      typeIconUrl: json['typeIconUrl'] as String?,
    );
  }

  /// 转换为JSON
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'packageName': packageName,
      'nodeId': nodeId,
      'suggestionId': suggestionId,
      'targetKind': targetKind,
      'subagentConversationId': subagentConversationId,
      'subagentPrompt': subagentPrompt,
      'notificationEnabled': notificationEnabled,
      'type': type.name,
      'fixedTime': fixedTime,
      'countdownMinutes': countdownMinutes,
      'repeatDaily': repeatDaily,
      'isEnabled': isEnabled,
      'createdAt': createdAt,
      'nextExecutionTime': nextExecutionTime,
      'suggestionData': suggestionData,
      'appIconUrl': appIconUrl,
      'typeIconUrl': typeIconUrl,
    };
  }

  /// 复制并修改
  ScheduledTask copyWith({
    String? id,
    String? title,
    String? packageName,
    String? nodeId,
    String? suggestionId,
    String? targetKind,
    String? subagentConversationId,
    String? subagentPrompt,
    bool? notificationEnabled,
    ScheduledTaskType? type,
    String? fixedTime,
    int? countdownMinutes,
    bool? repeatDaily,
    bool? isEnabled,
    int? createdAt,
    int? nextExecutionTime,
    Map<String, dynamic>? suggestionData,
    String? appIconUrl,
    String? typeIconUrl,
  }) {
    return ScheduledTask(
      id: id ?? this.id,
      title: title ?? this.title,
      packageName: packageName ?? this.packageName,
      nodeId: nodeId ?? this.nodeId,
      suggestionId: suggestionId ?? this.suggestionId,
      targetKind: targetKind ?? this.targetKind,
      subagentConversationId:
          subagentConversationId ?? this.subagentConversationId,
      subagentPrompt: subagentPrompt ?? this.subagentPrompt,
      notificationEnabled: notificationEnabled ?? this.notificationEnabled,
      type: type ?? this.type,
      fixedTime: fixedTime ?? this.fixedTime,
      countdownMinutes: countdownMinutes ?? this.countdownMinutes,
      repeatDaily: repeatDaily ?? this.repeatDaily,
      isEnabled: isEnabled ?? this.isEnabled,
      createdAt: createdAt ?? this.createdAt,
      nextExecutionTime: nextExecutionTime ?? this.nextExecutionTime,
      suggestionData: suggestionData ?? this.suggestionData,
      appIconUrl: appIconUrl ?? this.appIconUrl,
      typeIconUrl: typeIconUrl ?? this.typeIconUrl,
    );
  }

  /// 计算下次执行时间
  int calculateNextExecutionTime() {
    final now = DateTime.now();

    if (type == ScheduledTaskType.countdown) {
      // 倒计时类型：当前时间 + 倒计时分钟数
      return now
          .add(Duration(minutes: countdownMinutes ?? 0))
          .millisecondsSinceEpoch;
    } else {
      // 固定时间类型
      if (fixedTime == null) return now.millisecondsSinceEpoch;

      final parts = fixedTime!.split(':');
      if (parts.length != 2) return now.millisecondsSinceEpoch;

      final hour = int.tryParse(parts[0]) ?? 0;
      final minute = int.tryParse(parts[1]) ?? 0;

      var scheduledDateTime = DateTime(
        now.year,
        now.month,
        now.day,
        hour,
        minute,
      );

      // 如果今天的时间已经过了，则设置为明天
      if (scheduledDateTime.isBefore(now)) {
        scheduledDateTime = scheduledDateTime.add(const Duration(days: 1));
      }

      return scheduledDateTime.millisecondsSinceEpoch;
    }
  }

  /// 获取显示的时间文本
  String getDisplayTimeText() {
    if (type == ScheduledTaskType.countdown) {
      final minutes = countdownMinutes ?? 0;
      if (minutes >= 60) {
        final hours = minutes ~/ 60;
        final mins = minutes % 60;
        if (mins > 0) {
          return '$hours小时$mins分钟后';
        }
        return '$hours小时后';
      }
      return '$minutes分钟后';
    } else {
      return fixedTime ?? '--:--';
    }
  }

  /// 获取下次执行时间的显示文本
  String getNextExecutionTimeText() {
    final en = LegacyTextLocalizer.isEnglish;
    if (nextExecutionTime == null) return en ? 'Not set' : '未设置';

    final nextTime = DateTime.fromMillisecondsSinceEpoch(nextExecutionTime!);
    final now = DateTime.now();
    final diff = nextTime.difference(now);

    if (diff.isNegative) return en ? 'Expired' : '已过期';

    if (diff.inDays > 0) {
      return en ? '${diff.inDays}d later' : '${diff.inDays}天后';
    } else if (diff.inHours > 0) {
      return en ? '${diff.inHours}h later' : '${diff.inHours}小时后';
    } else if (diff.inMinutes > 0) {
      return en ? '${diff.inMinutes}m later' : '${diff.inMinutes}分钟后';
    } else {
      return en ? 'Starting soon' : '即将执行';
    }
  }

  @override
  String toString() {
    return 'ScheduledTask(id: $id, title: $title, targetKind: $targetKind, type: $type, fixedTime: $fixedTime, countdownMinutes: $countdownMinutes, repeatDaily: $repeatDaily)';
  }
}
