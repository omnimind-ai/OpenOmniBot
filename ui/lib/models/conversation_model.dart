import 'package:ui/l10n/app_text_localizer.dart';

enum ConversationMode {
  normal('normal'),
  chatOnly('chat_only'),
  openclaw('openclaw'),
  subagent('subagent'),
  codex('codex');

  const ConversationMode(this.storageValue);

  final String storageValue;

  static ConversationMode fromStorageValue(String? value) {
    final normalized = value?.trim().toLowerCase() ?? '';
    for (final mode in ConversationMode.values) {
      if (mode.storageValue == normalized) {
        return mode;
      }
    }
    return ConversationMode.normal;
  }

  String get displayLabel => switch (this) {
    ConversationMode.normal => AppTextLocalizer.text('普通'),
    ConversationMode.chatOnly => AppTextLocalizer.text('纯聊天'),
    ConversationMode.openclaw => 'OpenClaw',
    ConversationMode.subagent => 'SubAgent',
    ConversationMode.codex => 'Codex',
  };
}

class ConversationModel {
  final int id;
  final ConversationMode mode;
  final bool isArchived;
  final String title;
  final String? summary;
  final String? contextSummary;
  final int? contextSummaryCutoffEntryDbId;
  final int contextSummaryUpdatedAt;
  final int status; // 0: 进行中, 1: 已完成
  final String? lastMessage;
  final int messageCount;
  final int latestPromptTokens;
  final int promptTokenThreshold;
  final int latestPromptTokensUpdatedAt;
  final int createdAt;
  final int updatedAt;

  ConversationModel({
    required this.id,
    this.mode = ConversationMode.normal,
    this.isArchived = false,
    required this.title,
    this.summary,
    this.contextSummary,
    this.contextSummaryCutoffEntryDbId,
    this.contextSummaryUpdatedAt = 0,
    required this.status,
    this.lastMessage,
    required this.messageCount,
    this.latestPromptTokens = 0,
    this.promptTokenThreshold = 128000,
    this.latestPromptTokensUpdatedAt = 0,
    required this.createdAt,
    required this.updatedAt,
  });

  factory ConversationModel.fromJson(Map<String, dynamic> json) {
    return ConversationModel(
      id: (json['id'] as num?)?.toInt() ?? 0,
      mode: ConversationMode.fromStorageValue(json['mode'] as String?),
      isArchived: json['isArchived'] as bool? ?? false,
      title: (json['title'] ?? '').toString(),
      summary: json['summary'] as String?,
      contextSummary: json['contextSummary'] as String?,
      contextSummaryCutoffEntryDbId:
          (json['contextSummaryCutoffEntryDbId'] as num?)?.toInt(),
      contextSummaryUpdatedAt:
          (json['contextSummaryUpdatedAt'] as num?)?.toInt() ?? 0,
      status: (json['status'] as num?)?.toInt() ?? 0,
      lastMessage: json['lastMessage'] as String?,
      messageCount: (json['messageCount'] as num?)?.toInt() ?? 0,
      latestPromptTokens: (json['latestPromptTokens'] as num?)?.toInt() ?? 0,
      promptTokenThreshold:
          (json['promptTokenThreshold'] as num?)?.toInt() ?? 128000,
      latestPromptTokensUpdatedAt:
          (json['latestPromptTokensUpdatedAt'] as num?)?.toInt() ?? 0,
      createdAt: (json['createdAt'] as num?)?.toInt() ?? 0,
      updatedAt: (json['updatedAt'] as num?)?.toInt() ?? 0,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'mode': mode.storageValue,
      'isArchived': isArchived,
      'title': title,
      'summary': summary,
      'contextSummary': contextSummary,
      'contextSummaryCutoffEntryDbId': contextSummaryCutoffEntryDbId,
      'contextSummaryUpdatedAt': contextSummaryUpdatedAt,
      'status': status,
      'lastMessage': lastMessage,
      'messageCount': messageCount,
      'latestPromptTokens': latestPromptTokens,
      'promptTokenThreshold': promptTokenThreshold,
      'latestPromptTokensUpdatedAt': latestPromptTokensUpdatedAt,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
    };
  }

  ConversationModel copyWith({
    int? id,
    ConversationMode? mode,
    bool? isArchived,
    String? title,
    String? summary,
    String? contextSummary,
    int? contextSummaryCutoffEntryDbId,
    int? contextSummaryUpdatedAt,
    int? status,
    String? lastMessage,
    int? messageCount,
    int? latestPromptTokens,
    int? promptTokenThreshold,
    int? latestPromptTokensUpdatedAt,
    int? createdAt,
    int? updatedAt,
  }) {
    return ConversationModel(
      id: id ?? this.id,
      mode: mode ?? this.mode,
      isArchived: isArchived ?? this.isArchived,
      title: title ?? this.title,
      summary: summary ?? this.summary,
      contextSummary: contextSummary ?? this.contextSummary,
      contextSummaryCutoffEntryDbId:
          contextSummaryCutoffEntryDbId ?? this.contextSummaryCutoffEntryDbId,
      contextSummaryUpdatedAt:
          contextSummaryUpdatedAt ?? this.contextSummaryUpdatedAt,
      status: status ?? this.status,
      lastMessage: lastMessage ?? this.lastMessage,
      messageCount: messageCount ?? this.messageCount,
      latestPromptTokens: latestPromptTokens ?? this.latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold ?? this.promptTokenThreshold,
      latestPromptTokensUpdatedAt:
          latestPromptTokensUpdatedAt ?? this.latestPromptTokensUpdatedAt,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  // 获取格式化的时间显示（今天/昨天/日期）
  String get timeDisplay {
    final now = DateTime.now();
    updatedDate;
    final today = DateTime(now.year, now.month, now.day);
    final updatedDay = DateTime(
      updatedDate.year,
      updatedDate.month,
      updatedDate.day,
    );

    final difference = today.difference(updatedDay).inDays;

    if (difference == 0) {
      return AppTextLocalizer.text('今天');
    } else if (difference == 1) {
      return AppTextLocalizer.text('昨天');
    } else if (difference < 7) {
      // 显示星期几
      final weekdays = AppTextLocalizer.chooseList(
        en: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
        zh: ['一', '二', '三', '四', '五', '六', '日'],
      );
      return AppTextLocalizer.choose(
        en: weekdays[updatedDate.weekday - 1],
        zh: '周${weekdays[updatedDate.weekday - 1]}',
      );
    } else {
      // 显示月-日
      return '${updatedDate.month}-${updatedDate.day}';
    }
  }

  DateTime get updatedDate => DateTime.fromMillisecondsSinceEpoch(updatedAt);

  bool get isActive => status == 0;

  double? get contextUsageRatio {
    if (promptTokenThreshold <= 0) return null;
    if (latestPromptTokensUpdatedAt <= 0 && latestPromptTokens <= 0) {
      return null;
    }
    return latestPromptTokens / promptTokenThreshold;
  }

  String get threadKey => '${mode.storageValue}:$id';
}
