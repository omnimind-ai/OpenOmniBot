import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class TaskData {
  final String id;
  final String title;
  final DateTime date;
  final TimeOfDay time;
  final RepeatOption repeatOption;
  bool isEnabled; // 移除 final，使其可修改
  final DateTime createdAt;
  final DateTime updatedAt;

  TaskData({
    required this.id,
    required this.title,
    required this.date,
    required this.time,
    required this.repeatOption,
    this.isEnabled = true,
    DateTime? createdAt,
    DateTime? updatedAt,
  }) : createdAt = createdAt ?? DateTime.now(),
       updatedAt = updatedAt ?? DateTime.now();

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'date': date.millisecondsSinceEpoch,
      'timeHour': time.hour,
      'timeMinute': time.minute,
      'repeatOption': repeatOption.value,
      'isEnabled': isEnabled,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'updatedAt': updatedAt.millisecondsSinceEpoch,
    };
  }

  static TaskData fromJson(Map<String, dynamic> json) {
    return TaskData(
      id: json['id'] as String,
      title: json['title'] as String,
      date: DateTime.fromMillisecondsSinceEpoch(json['date'] as int),
      time: TimeOfDay(
        hour: json['timeHour'] as int,
        minute: json['timeMinute'] as int,
      ),
      repeatOption: RepeatOption.fromValue(json['repeatOption'] as String),
      isEnabled: json['isEnabled'] as bool? ?? true,
      createdAt: DateTime.fromMillisecondsSinceEpoch(json['createdAt'] as int),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(json['updatedAt'] as int),
    );
  }

  TaskData copyWith({
    String? title,
    DateTime? date,
    TimeOfDay? time,
    RepeatOption? repeatOption,
    bool? isEnabled,
  }) {
    return TaskData(
      id: id,
      title: title ?? this.title,
      date: date ?? this.date,
      time: time ?? this.time,
      repeatOption: repeatOption ?? this.repeatOption,
      isEnabled: isEnabled ?? this.isEnabled,
      createdAt: createdAt,
      updatedAt: DateTime.now(),
    );
  }
}

enum RepeatOption {
  never(0, '永不', 'never'),
  daily(1, '每日', 'daily'),
  weekly(2, '每周', 'weekly'),
  monthly(3, '每月', 'monthly'),
  yearly(4, '每年', 'yearly'),
  unknown(-1, '', 'unknown');

  const RepeatOption(this.num, this.label, this.value);

  final int num;
  final String label;
  final String value;

  String get displayLabel => LegacyTextLocalizer.localize(label);

  static RepeatOption fromNum(int num) {
    return RepeatOption.values.firstWhere(
      (option) => option.num == num,
      orElse: () => RepeatOption.unknown,
    );
  }

  static RepeatOption fromLabel(String label) {
    return RepeatOption.values.firstWhere(
      (option) => option.label == label,
      orElse: () => RepeatOption.unknown,
    );
  }

  static RepeatOption fromValue(String value) {
    return RepeatOption.values.firstWhere(
      (option) => option.value == value,
      orElse: () => RepeatOption.unknown,
    );
  }

  @override
  String toString() => label;
}

// 以下是历史记录相关的数据模型
class TaskHistorySection {
  final DateTime dateLabel;
  final RepeatOption repeatOption;
  final List<TaskExecutionRecord> records;
  const TaskHistorySection({
    required this.dateLabel,
    required this.repeatOption,
    required this.records,
  });
}

class TaskExecutionRecord {
  final TimeOfDay startTime;
  final TimeOfDay endTime;
  final int durationSeconds;
  final List<ExecutionActionStatus> actions;
  const TaskExecutionRecord({
    required this.startTime,
    required this.endTime,
    required this.durationSeconds,
    required this.actions,
  });
}

class ExecutionActionStatus {
  final String label;
  final bool success;
  final IconData? icon;
  const ExecutionActionStatus({
    required this.label,
    required this.success,
    this.icon,
  });
}