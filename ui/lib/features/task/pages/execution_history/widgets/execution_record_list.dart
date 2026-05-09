import 'package:flutter/material.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/features/task/pages/execution_history/widgets/execution_record_list_item.dart';

class ExecutionRecordList extends StatelessWidget {
  final List<ExecutionRecordListItemData> records;
  final Function(int) onDelete;
  final Function(ExecutionRecordListItemData, BuildContext, Offset) onMore;
  final Function(ExecutionRecordListItemData)? onLongPress;
  final Function(ExecutionRecordListItemData)? onTap;
  // 选择模式相关
  final bool isSelectionMode;
  final Set<String> selectedKeys;
  final Function(ExecutionRecordListItemData)? onToggleSelection;
  final String Function(ExecutionRecordListItemData)? getRecordKey;
  final Function(ExecutionRecordListItemData)? onSchedulePressed;
  final Set<String> scheduledTaskKeys;

  const ExecutionRecordList({
    required this.records,
    required this.onDelete,
    required this.onMore,
    this.onLongPress,
    this.onTap,
    this.isSelectionMode = false,
    this.selectedKeys = const {},
    this.onToggleSelection,
    this.getRecordKey,
    this.onSchedulePressed,
    this.scheduledTaskKeys = const {},
  });

  @override
  Widget build(BuildContext context) {
    Map<String, List<ExecutionRecordListItemData>> grouped = {};
    for (var record in records) {
      final section = record.section ?? '未分类';
      grouped.putIfAbsent(section, () => []).add(record);
    }

    return SlidableAutoCloseBehavior(
      closeWhenTapped: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Column(
          children: [
            ...grouped.entries.map((entry) {
              final section = entry.key;
              final sectionCards = entry.value;

              return Column(
                children: [
                  // 分组标题
                  // _buildSectionHeader(section),

                  // 分组内容
                  ...List.generate(sectionCards.length, (index) {
                    final record = sectionCards[index];
                    final showSeparator = index < sectionCards.length - 1;
                    final recordKey =
                        getRecordKey?.call(record) ??
                        '${record.nodeId}|${record.suggestionId}';
                    final isSelected = selectedKeys.contains(recordKey);
                    final hasScheduledTask = scheduledTaskKeys.contains(
                      recordKey,
                    );

                    return Column(
                      children: [
                        GestureDetector(
                          onTap: isSelectionMode
                              ? () => onToggleSelection?.call(record)
                              : () => onTap?.call(record),
                          child: ExecutionRecordListItem(
                            recordModel: record,
                            isSelectionMode: isSelectionMode,
                            isSelected: isSelected,
                            hasScheduledTask: hasScheduledTask,
                            onSchedulePressed: isSelectionMode
                                ? null
                                : () => onSchedulePressed?.call(record),
                            onMorePressed: isSelectionMode
                                ? null
                                : (context, position) {
                                    onMore(record, context, position);
                                  },
                            onDelete: isSelectionMode
                                ? null
                                : () {
                                    onDelete(record.id);
                                  },
                            onLongPress: isSelectionMode
                                ? null
                                : () {
                                    onLongPress?.call(record);
                                  },
                          ),
                        ),
                        // if (showSeparator)
                        const SizedBox(height: 8),
                      ],
                    );
                  }),

                  // const SizedBox(height: 16),
                ],
              );
            }).toList(),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String section) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 16),
      alignment: Alignment.centerLeft,
      height: 20,
      child: Text(
        section,
        style: TextStyle(
          fontSize: AppTextStyles.fontSizeSmall,
          color: AppColors.text50,
          fontWeight: AppTextStyles.fontWeightRegular,
          height: AppTextStyles.lineHeightH2,
          letterSpacing: AppTextStyles.letterSpacingNormal,
        ),
      ),
    );
  }
}
