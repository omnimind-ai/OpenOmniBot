import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

class ExecutionRecordListItem extends StatelessWidget {
  final ExecutionRecordListItemData recordModel;
  final void Function(BuildContext context, Offset position)? onMorePressed;
  final void Function(int recordId, bool targetStatus)? onRecommendPressed;
  final VoidCallback? onDelete;
  final VoidCallback? onLongPress;
  // 选择模式相关
  final bool isSelectionMode;
  final bool isSelected;
  // 定时任务相关
  final VoidCallback? onSchedulePressed;
  final bool hasScheduledTask;

  const ExecutionRecordListItem({
    Key? key,
    required this.recordModel,
    this.onMorePressed,
    this.onRecommendPressed,
    this.onDelete,
    this.onLongPress,
    this.isSelectionMode = false,
    this.isSelected = false,
    this.onSchedulePressed,
    this.hasScheduledTask = false,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onLongPressStart: (details) {
        onLongPress?.call();
      },
      child: _buildCardContent(context),
    );
  }

  Widget _buildCardContent(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Stack(
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: isDark ? palette.surfacePrimary : Colors.white,
            borderRadius: BorderRadius.circular(8),
            border: isDark ? Border.all(color: palette.borderSubtle) : null,
            boxShadow: isDark ? null : [AppColors.boxShadow],
          ),
          child: Row(
            children: [
              // 原有内容
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        // 显示多个图标
                        ...recordModel.icons.asMap().entries.map((entry) {
                          final index = entry.key;
                          final icon = entry.value;
                          return Padding(
                            padding: EdgeInsets.only(
                              right: index < recordModel.icons.length - 1
                                  ? 4
                                  : 0,
                            ),
                            child: icon,
                          );
                        }),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      recordModel.title,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.57,
                        letterSpacing: 0,
                        color: isDark ? palette.textPrimary : AppColors.text,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Text(
                          '最近执行：${recordModel.lastExecutionTimeLabel}',
                          style: TextStyle(
                            fontSize: 10,
                            color: isDark
                                ? palette.textSecondary
                                : AppColors.text70,
                            height: 1.60,
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 10),
                          child: Container(
                            width: 0.5,
                            height: 7,
                            decoration: BoxDecoration(
                              color: isDark
                                  ? palette.textSecondary
                                  : AppColors.text70,
                            ),
                          ),
                        ),
                        Text(
                          '共执行 ${recordModel.times} 次',
                          style: TextStyle(
                            fontSize: 10,
                            color: isDark
                                ? palette.textSecondary
                                : AppColors.text70,
                            height: 1.60,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),

              // 选择模式下显示复选框
              if (isSelectionMode) ...[
                _buildCheckbox(),
                const SizedBox(width: 12),
              ],
            ],
          ),
        ),
        // 可执行时显示执行按钮（选择模式下不显示）
        if (recordModel.isExecutable && !isSelectionMode) ...[
          Positioned(top: 6, right: 4, child: _buildExecuteButton()),
        ],
        if (recordModel.isSchedulable && !isSelectionMode) ...[
          Positioned(bottom: 6, right: 4, child: _buildScheduleButton()),
        ],
      ],
    );
  }

  Widget _buildScheduleButton() {
    return Builder(
      builder: (context) {
        final palette = context.omniPalette;
        final isDark = context.isDarkTheme;
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () {
            onSchedulePressed?.call();
          },
          child: Container(
            width: 32,
            height: 32,
            alignment: Alignment.center,
            child: SvgPicture.asset(
              'assets/common/schedule_icon.svg',
              width: 16,
              height: 16,
              colorFilter: ColorFilter.mode(
                hasScheduledTask
                    ? (isDark ? palette.accentPrimary : AppColors.primaryBlue)
                    : (isDark ? palette.textSecondary : AppColors.text70),
                BlendMode.srcIn,
              ),
            ),
          ),
        );
      },
    );
  }

  /// 构建执行按钮
  Widget _buildExecuteButton() {
    return Builder(
      builder: (context) {
        final palette = context.omniPalette;
        final isDark = context.isDarkTheme;
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () {
            recordModel.onExecute?.call();
          },
          child: Container(
            width: 32,
            height: 32,
            alignment: Alignment.center,
            child: Icon(
              Icons.play_arrow_rounded,
              size: 18,
              color: isDark ? palette.accentPrimary : AppColors.primaryBlue,
            ),
          ),
        );
      },
    );
  }

  Widget _buildCheckbox() {
    return SizedBox(
      width: 20,
      height: 20,
      child: SvgPicture.asset(
        isSelected
            ? 'assets/common/card_selected.svg'
            : 'assets/common/card_unselected.svg',
        width: 20,
        height: 20,
      ),
    );
  }
}

class ExecutionRecordListItemData {
  final int id;
  final String title;
  final String packageName;
  final String nodeId; // 标识 suggestion
  final String suggestionId; // 标识 suggestion
  final int times;
  final String lastExecutionTimeLabel;
  final List<Widget> icons;
  final bool isRecommended;
  final String? section;

  // 可执行相关属性
  final bool isExecutable; // 是否可执行
  final bool isSchedulable; // 是否可设置定时
  final Map<String, dynamic>? suggestionData; // Suggestion 完整数据
  final VoidCallback? onExecute; // 执行回调

  ExecutionRecordListItemData({
    required this.id,
    required this.title,
    required this.packageName,
    required this.nodeId,
    required this.suggestionId,
    required this.times,
    required this.lastExecutionTimeLabel,
    required this.icons,
    this.isRecommended = false,
    this.section,
    this.isExecutable = false,
    this.isSchedulable = false,
    this.suggestionData,
    this.onExecute,
  });

  ExecutionRecordListItemData copyWith({
    int? id,
    String? title,
    String? packageName,
    String? nodeId,
    String? suggestionId,
    int? times,
    String? lastExecutionTimeLabel,
    List<Widget>? icons,
    bool? isRecommended,
    String? section,
    bool? isExecutable,
    bool? isSchedulable,
    Map<String, dynamic>? suggestionData,
    VoidCallback? onExecute,
  }) {
    return ExecutionRecordListItemData(
      id: id ?? this.id,
      title: title ?? this.title,
      packageName: packageName ?? this.packageName,
      nodeId: nodeId ?? this.nodeId,
      suggestionId: suggestionId ?? this.suggestionId,
      times: times ?? this.times,
      lastExecutionTimeLabel:
          lastExecutionTimeLabel ?? this.lastExecutionTimeLabel,
      icons: icons ?? this.icons,
      isRecommended: isRecommended ?? this.isRecommended,
      section: section ?? this.section,
      isExecutable: isExecutable ?? this.isExecutable,
      isSchedulable: isSchedulable ?? this.isSchedulable,
      suggestionData: suggestionData ?? this.suggestionData,
      onExecute: onExecute ?? this.onExecute,
    );
  }
}
