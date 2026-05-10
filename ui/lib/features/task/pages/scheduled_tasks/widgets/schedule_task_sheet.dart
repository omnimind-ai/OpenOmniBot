import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:uuid/uuid.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

/// 定时任务配置底部弹窗
class ScheduleTaskSheet extends StatefulWidget {
  /// 任务标题
  final String taskTitle;

  /// 包名
  final String packageName;

  /// nodeId
  final String nodeId;

  /// suggestionId
  final String suggestionId;

  /// suggestion数据
  final Map<String, dynamic>? suggestionData;

  /// 应用图标URL
  final String? appIconUrl;

  /// 类型图标URL
  final String? typeIconUrl;

  /// 现有的定时任务（用于编辑）
  final ScheduledTask? existingTask;

  const ScheduleTaskSheet({
    Key? key,
    required this.taskTitle,
    required this.packageName,
    required this.nodeId,
    required this.suggestionId,
    this.suggestionData,
    this.appIconUrl,
    this.typeIconUrl,
    this.existingTask,
  }) : super(key: key);

  @override
  State<ScheduleTaskSheet> createState() => _ScheduleTaskSheetState();

  /// 显示定时任务配置弹窗
  static Future<ScheduledTask?> show({
    required BuildContext context,
    required String taskTitle,
    required String packageName,
    required String nodeId,
    required String suggestionId,
    Map<String, dynamic>? suggestionData,
    String? appIconUrl,
    String? typeIconUrl,
    ScheduledTask? existingTask,
  }) {
    return showModalBottomSheet<ScheduledTask>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => ScheduleTaskSheet(
        taskTitle: taskTitle,
        packageName: packageName,
        nodeId: nodeId,
        suggestionId: suggestionId,
        suggestionData: suggestionData,
        appIconUrl: appIconUrl,
        typeIconUrl: typeIconUrl,
        existingTask: existingTask,
      ),
    );
  }
}

class _ScheduleTaskSheetState extends State<ScheduleTaskSheet> {
  /// 当前选择的标签页索引 (0: 固定时间, 1: 倒计时)
  int _selectedTabIndex = 0;

  /// 是否每日重复
  bool _repeatDaily = false;

  /// 固定时间
  TimeOfDay _selectedTime = TimeOfDay.now();

  /// 倒计时分钟数
  int _countdownMinutes = 30;

  /// PageController for tab switching
  late PageController _pageController;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(initialPage: _selectedTabIndex);

    // 如果有现有任务，初始化数据
    if (widget.existingTask != null) {
      final task = widget.existingTask!;
      _selectedTabIndex = task.type == ScheduledTaskType.fixedTime ? 0 : 1;
      _repeatDaily = task.repeatDaily;

      if (task.type == ScheduledTaskType.fixedTime && task.fixedTime != null) {
        final parts = task.fixedTime!.split(':');
        if (parts.length == 2) {
          _selectedTime = TimeOfDay(
            hour: int.tryParse(parts[0]) ?? 0,
            minute: int.tryParse(parts[1]) ?? 0,
          );
        }
      } else if (task.type == ScheduledTaskType.countdown) {
        _countdownMinutes = task.countdownMinutes ?? 30;
      }

      _pageController = PageController(initialPage: _selectedTabIndex);
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 拖动指示器
            Container(
              margin: const EdgeInsets.only(top: 12),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey[300],
                borderRadius: BorderRadius.circular(2),
              ),
            ),

            // 标题
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      LegacyTextLocalizer.isEnglish ? 'Set scheduled task' : '设置定时任务',
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: AppColors.text,
                      ),
                    ),
                  ),
                  GestureDetector(
                    onTap: () => Navigator.pop(context),
                    child: const Icon(
                      Icons.close,
                      size: 24,
                      color: AppColors.text70,
                    ),
                  ),
                ],
              ),
            ),

            // 任务标题显示
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 16),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  const Icon(
                    Icons.task_alt,
                    size: 20,
                    color: AppColors.primaryBlue,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      widget.taskTitle,
                      style: const TextStyle(
                        fontSize: 14,
                        color: AppColors.text,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 16),

            // 标签页切换
            _buildTabSelector(),

            const SizedBox(height: 16),

            // 内容区域
            SizedBox(
              height: 200,
              child: PageView(
                controller: _pageController,
                onPageChanged: (index) {
                  setState(() {
                    _selectedTabIndex = index;
                  });
                },
                children: [_buildFixedTimeTab(), _buildCountdownTab()],
              ),
            ),

            // 每日重复开关
            if (_selectedTabIndex == 0)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Row(
                  children: [
                    Text(
                      LegacyTextLocalizer.isEnglish ? 'Repeat daily' : '每日重复执行',
                      style: TextStyle(fontSize: 14, color: AppColors.text),
                    ),
                    const Spacer(),
                    CupertinoSwitch(
                      value: _repeatDaily,
                      activeColor: AppColors.primaryBlue,
                      onChanged: (value) {
                        setState(() {
                          _repeatDaily = value;
                        });
                      },
                    ),
                  ],
                ),
              ),

            const SizedBox(height: 24),

            // 确认按钮
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  onPressed: _onConfirm,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primaryBlue,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(24),
                    ),
                    elevation: 0,
                  ),
                  child: Text(
                    LegacyTextLocalizer.localize('确认'),
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  /// 构建标签页选择器
  Widget _buildTabSelector() {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.background,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Expanded(
            child: _buildTabButton(
              title: '固定时间',
              isSelected: _selectedTabIndex == 0,
              onTap: () {
                setState(() {
                  _selectedTabIndex = 0;
                });
                _pageController.animateToPage(
                  0,
                  duration: const Duration(milliseconds: 300),
                  curve: Curves.easeInOut,
                );
              },
            ),
          ),
          Expanded(
            child: _buildTabButton(
              title: '倒计时',
              isSelected: _selectedTabIndex == 1,
              onTap: () {
                setState(() {
                  _selectedTabIndex = 1;
                });
                _pageController.animateToPage(
                  1,
                  duration: const Duration(milliseconds: 300),
                  curve: Curves.easeInOut,
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  /// 构建单个标签按钮
  Widget _buildTabButton({
    required String title,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: isSelected ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(6),
          boxShadow: isSelected
              ? [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.05),
                    blurRadius: 4,
                    offset: const Offset(0, 2),
                  ),
                ]
              : null,
        ),
        child: Center(
          child: Text(
            title,
            style: TextStyle(
              fontSize: 14,
              fontWeight: isSelected ? FontWeight.w500 : FontWeight.normal,
              color: isSelected ? AppColors.primaryBlue : AppColors.text70,
            ),
          ),
        ),
      ),
    );
  }

  /// 构建固定时间选择
  Widget _buildFixedTimeTab() {
    return SizedBox(
      height: 200,
      child: CupertinoTheme(
        data: CupertinoThemeData(
          textTheme: CupertinoTextThemeData(
            dateTimePickerTextStyle: TextStyle(
              fontSize: 26, // 增大字体
              color: AppColors.text,
            ),
          ),
        ),
        child: CupertinoDatePicker(
          mode: CupertinoDatePickerMode.time,
          initialDateTime: DateTime(
            DateTime.now().year,
            DateTime.now().month,
            DateTime.now().day,
            _selectedTime.hour,
            _selectedTime.minute,
          ),
          onDateTimeChanged: (DateTime newDateTime) {
            setState(() {
              _selectedTime = TimeOfDay.fromDateTime(newDateTime);
            });
          },
          use24hFormat: true,
          itemExtent: 40, // 增加行高
        ),
      ),
    );
  }

  /// 构建倒计时选择
  Widget _buildCountdownTab() {
    return Center(
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _buildCountdownButton(
            icon: Icons.remove,
            onTap: () {
              if (_countdownMinutes > 5) {
                setState(() {
                  _countdownMinutes -= 5;
                });
              }
            },
          ),
          const SizedBox(width: 24),
          GestureDetector(
            onTap: _showCountdownInputDialog,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              decoration: BoxDecoration(
                border: Border.all(
                  color: AppColors.primaryBlue.withOpacity(0.3),
                ),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _formatCountdown(_countdownMinutes),
                    style: const TextStyle(
                      fontSize: 36,
                      fontWeight: FontWeight.w300,
                      color: AppColors.text,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '后执行',
                    style: TextStyle(fontSize: 14, color: AppColors.text70),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 24),
          _buildCountdownButton(
            icon: Icons.add,
            onTap: () {
              if (_countdownMinutes < 1440) {
                // 最多24小时
                setState(() {
                  _countdownMinutes += 5;
                });
              }
            },
          ),
        ],
      ),
    );
  }

  /// 显示倒计时输入对话框
  Future<void> _showCountdownInputDialog() async {
    final minutes = await showDialog<int>(
      context: context,
      useRootNavigator: false,
      builder: (_) => _CountdownInputDialog(initialMinutes: _countdownMinutes),
    );

    if (!mounted || minutes == null) return;
    setState(() {
      _countdownMinutes = minutes;
    });
  }

  /// 格式化倒计时显示
  String _formatCountdown(int minutes) {
    if (minutes >= 60) {
      final hours = minutes ~/ 60;
      final mins = minutes % 60;
      if (mins > 0) {
        return '${hours}h ${mins}m';
      }
      return '${hours}小时';
    }
    return '${minutes}分钟';
  }

  /// 构建加减按钮
  Widget _buildCountdownButton({
    required IconData icon,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: AppColors.primaryBlue.withOpacity(0.1),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, size: 24, color: AppColors.primaryBlue),
      ),
    );
  }

  /// 选择时间
  Future<void> _selectTime() async {
    final TimeOfDay? picked = await showTimePicker(
      context: context,
      initialTime: _selectedTime,
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: ColorScheme.light(primary: AppColors.primaryBlue),
          ),
          child: child!,
        );
      },
    );

    if (picked != null && picked != _selectedTime) {
      setState(() {
        _selectedTime = picked;
      });
    }
  }

  /// 确认创建定时任务
  void _onConfirm() {
    final task = ScheduledTask(
      id: widget.existingTask?.id ?? const Uuid().v4(),
      title: widget.taskTitle,
      packageName: widget.packageName,
      nodeId: widget.nodeId,
      suggestionId: widget.suggestionId,
      targetKind: widget.existingTask?.targetKind ?? 'vlm',
      subagentConversationId: widget.existingTask?.subagentConversationId,
      subagentPrompt: widget.existingTask?.subagentPrompt,
      notificationEnabled: widget.existingTask?.notificationEnabled ?? true,
      type: _selectedTabIndex == 0
          ? ScheduledTaskType.fixedTime
          : ScheduledTaskType.countdown,
      fixedTime: _selectedTabIndex == 0
          ? '${_selectedTime.hour.toString().padLeft(2, '0')}:${_selectedTime.minute.toString().padLeft(2, '0')}'
          : null,
      countdownMinutes: _selectedTabIndex == 1 ? _countdownMinutes : null,
      repeatDaily: _repeatDaily,
      isEnabled: true,
      createdAt:
          widget.existingTask?.createdAt ??
          DateTime.now().millisecondsSinceEpoch,
      suggestionData: widget.suggestionData,
      appIconUrl: widget.appIconUrl,
      typeIconUrl: widget.typeIconUrl,
    );

    // 计算下次执行时间
    final taskWithNextTime = task.copyWith(
      nextExecutionTime: task.calculateNextExecutionTime(),
    );

    Navigator.pop(context, taskWithNextTime);
  }
}

class _CountdownInputDialog extends StatefulWidget {
  const _CountdownInputDialog({required this.initialMinutes});

  final int initialMinutes;

  @override
  State<_CountdownInputDialog> createState() => _CountdownInputDialogState();
}

class _CountdownInputDialogState extends State<_CountdownInputDialog> {
  late final TextEditingController _controller;
  final FocusNode _focusNode = FocusNode();
  String? _errorText;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialMinutes.toString());
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _focusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _close([int? value]) {
    _focusNode.unfocus();
    Navigator.of(context).pop(value);
  }

  void _submit() {
    final minutes = int.tryParse(_controller.text.trim());
    if (minutes == null || minutes <= 0 || minutes > 1440) {
      setState(() {
        _errorText = '请输入 1-1440 之间的分钟数';
      });
      return;
    }
    _close(minutes);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
        title: const Text('设置倒计时'),
        content: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Expanded(
              child: TextField(
                controller: _controller,
                focusNode: _focusNode,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  suffixText: '分钟',
                  border: const OutlineInputBorder(),
                  errorText: _errorText,
                ),
                onChanged: (_) {
                  if (_errorText != null) {
                    setState(() {
                      _errorText = null;
                    });
                  }
                },
                onSubmitted: (_) => _submit(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => _close(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: _submit,
            child: const Text('确定'),
          ),
        ],
      ),
    );
  }
}
