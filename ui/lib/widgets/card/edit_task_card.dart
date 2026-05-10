import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:flutter/cupertino.dart';
import 'package:intl/intl.dart';
import '../buttons_group_two.dart';
import '../../models/block_models.dart';
import '../../services/task_storage_service.dart';
import '../../models/task_models.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';

class EditTaskCard extends StatefulWidget {
  final DateTime selectedDate;
  final TimeOfDay selectedTime;
  final RepeatOption repeatOption;
  final Function(DateTime)? onDateChanged;
  final Function(TimeOfDay)? onTimeChanged;
  final Function(RepeatOption)? onRepeatChanged;
  final VoidCallback? onSave;
  final VoidCallback? onCancel;

  const EditTaskCard({
    Key? key,
    required this.selectedDate,
    required this.selectedTime,
    this.repeatOption = RepeatOption.never,
    this.onDateChanged,
    this.onTimeChanged,
    this.onRepeatChanged,
    this.onSave,
    this.onCancel,
  }) : super(key: key);

  @override
  State<EditTaskCard> createState() => _EditTaskCardState();
}

class _EditTaskCardState extends State<EditTaskCard> {
  late DateTime _selectedDate;
  late TimeOfDay _selectedTime;
  late RepeatOption _repeatOption;

  @override
  void initState() {
    super.initState();
    _selectedDate = widget.selectedDate;
    _selectedTime = widget.selectedTime;
    _repeatOption = widget.repeatOption;
  }

  void onButtonPressed(ButtonModel button) {
    if (button.action == 'cancel') {
      widget.onCancel?.call();
    } else if (button.action == 'confirm') {
      // 触发保存回调，传递当前选中的值
      widget.onDateChanged?.call(_selectedDate);
      widget.onTimeChanged?.call(_selectedTime);
      widget.onRepeatChanged?.call(_repeatOption);
      widget.onSave?.call();
    }
  }

  void _selectTime() async {
    // 使用滚轮风格的时间选择器
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (context) {
        TimeOfDay tempTime = _selectedTime;
        final now = DateTime.now();
        final initial = DateTime(
          now.year,
          now.month,
          now.day,
          _selectedTime.hour,
          _selectedTime.minute,
        );
        return Container(
          height: 300,
          child: Column(
            children: [
              // 顶部标题栏
              Container(
                padding: const EdgeInsets.all(16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: Text(LegacyTextLocalizer.localize('取消')),
                    ),
                    Text(
                      LegacyTextLocalizer.localize('时间'),
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    TextButton(
                      onPressed: () {
                        setState(() {
                          _selectedTime = tempTime;
                        });
                        widget.onTimeChanged?.call(tempTime);
                        Navigator.pop(context);
                      },
                      child: Text(LegacyTextLocalizer.localize('确定')),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: Localizations.override(
                  context: context,
                  locale: const Locale('zh', 'CN'),
                  child: CupertinoDatePicker(
                    mode: CupertinoDatePickerMode.time,
                    use24hFormat: true,
                    initialDateTime: initial,
                    onDateTimeChanged: (DateTime dateTime) {
                      tempTime = TimeOfDay(
                        hour: dateTime.hour,
                        minute: dateTime.minute,
                      );
                    },
                    backgroundColor: Colors.white,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _selectDate() async {
    // 使用滚轮风格的日期选择器
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (context) {
        DateTime tempDate = _selectedDate;
        final now = DateTime.now();
        final minDate = DateTime(now.year, now.month, now.day).subtract(const Duration(days: 365 * 1));
        final maxDate = minDate.add(const Duration(days: 365 * 2));
        return Container(
          height: 300,
          child: Column(
            children: [
              // 顶部标题栏
              Container(
                padding: const EdgeInsets.all(16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: Text(LegacyTextLocalizer.localize('取消')),
                    ),
                    Text(
                      LegacyTextLocalizer.localize('日期'),
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    TextButton(
                      onPressed: () {
                        setState(() {
                          _selectedDate = tempDate;
                        });
                        widget.onDateChanged?.call(tempDate);
                        Navigator.pop(context);
                      },
                      child: Text(LegacyTextLocalizer.localize('确定')),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              // 滚轮日期选择器
              Expanded(
                child: Localizations.override(
                  context: context,
                  locale: const Locale('zh', 'CN'),
                  child: CupertinoDatePicker(
                    mode: CupertinoDatePickerMode.date,
                    initialDateTime: tempDate,
                    minimumDate: minDate,
                    maximumDate: maxDate,
                    onDateTimeChanged: (DateTime dateTime) {
                      tempDate = dateTime;
                    },
                    backgroundColor: Colors.white,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _showRepeatOptions(Offset position) {
    final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
    if (overlay == null) {
      return;
    }
    showMenu(
      context: context,
      position: PopupMenuAnchorPosition.fromOverlayOffset(
        overlayOffset: position,
        overlaySize: overlay.size,
        estimatedMenuHeight: 200,
      ),
      color: Colors.white,
      items: [
        PopupMenuItem(
          value: 'never',
          child: Row(
            children: [ Text(LegacyTextLocalizer.localize('永不')), ],
          ),
        ),
        PopupMenuItem(
          value: 'daily',
          child: Row(
            children: [ Text(LegacyTextLocalizer.localize('每日')), ],
          ),
        ),
        PopupMenuItem(
          value: 'weekly',
          child: Row(
            children: [ Text(LegacyTextLocalizer.localize('每周')), ],
          ),
        ),
        PopupMenuItem(
          value: 'monthly',
          child: Row(
            children: [ Text(LegacyTextLocalizer.localize('每月')), ],
          ),
        ),
      ],
    ).then((value) {
      if (value != null) {
        setState(() {
          switch (value) {
            case 'never':
              _repeatOption = RepeatOption.never;
              break;
            case 'daily':
              _repeatOption = RepeatOption.daily;
              break;
            case 'weekly':
              _repeatOption = RepeatOption.weekly;
              break;
            case 'monthly':
              _repeatOption = RepeatOption.monthly;
              break;
            default:
              _repeatOption = RepeatOption.never;
          }
        });
        widget.onRepeatChanged?.call(_repeatOption);
      }
    });
  }

  String _formatDate(DateTime date) {
    final en = LegacyTextLocalizer.isEnglish;
    final weekdays = en
        ? ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
        : ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    final weekday = weekdays[date.weekday - 1];
    return en
        ? '${date.month}/${date.day} $weekday'
        : '${date.year}年${date.month}月${date.day}日 $weekday';
  }

  String _formatTime(TimeOfDay time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 设置项列表
        Container(
          margin: const EdgeInsets.only(bottom: 16),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            children: [
              // 时间设置
              _buildSettingItem(
                title: LegacyTextLocalizer.localize('时间'),
                value: _formatTime(_selectedTime),
                onTap: (position) => _selectTime(),
              ),

              const Divider(height: 1, indent: 16, endIndent: 16),

              // 日期设置
              _buildSettingItem(
                title: LegacyTextLocalizer.localize('日期'),
                value: _formatDate(_selectedDate),
                onTap: (position) => _selectDate(),
              ),
            ],
          ),
        ),

        Container(
          margin: const EdgeInsets.only(bottom: 16),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            children: [
              // 重复设置
              _buildSettingItem(
                title: LegacyTextLocalizer.localize('重复'),
                value: _repeatOption.displayLabel,
                onTap: (position) => _showRepeatOptions(position),
                showArrow: false,
              ),
            ],
          ),
        ),
        
        // 添加保存和取消按钮
        Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: ButtonsGroupTwo(
            leftButton: ButtonModel(
              text: LegacyTextLocalizer.localize('取消'),
              action: 'cancel',
            ),
            rightButton: ButtonModel(
              text: LegacyTextLocalizer.localize('保存'),
              action: 'confirm',
            ),
            onButtonPressed: onButtonPressed,
          ),
        ),
      ],
    );
  }

  Widget _buildSettingItem({
    required String title,
    required String value,
    required Function(Offset)? onTap,
    bool showArrow = true,
  }) {
    return GestureDetector(
      onTapDown: (TapDownDetails details) {
        final RenderBox overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
        final Offset position = overlay.globalToLocal(details.globalPosition);
        onTap?.call(position);
      },
      child: ListTile(
        title: Text(
          title,
          style: const TextStyle(
            fontSize: 16,
            color: Colors.black87,
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              value,
              style: TextStyle(
                fontSize: 16,
                color: Colors.grey.shade600,
              ),
            ),
            if (showArrow) ...[
              const SizedBox(width: 8),
              Icon(
                Icons.chevron_right,
                color: Colors.grey.shade400,
              ),
            ] else ...[
              const SizedBox(width: 8),
              Icon(
                Icons.unfold_more,
                color: Colors.grey.shade400,
              ),
            ],
          ],
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      ),
    );
  }
}

// 使用示例
class EditTaskCardExample extends StatefulWidget {
  @override
  State<EditTaskCardExample> createState() => _EditTaskCardExampleState();
}

class _EditTaskCardExampleState extends State<EditTaskCardExample> {
  DateTime _selectedDate = DateTime(2025, 9, 4);
  TimeOfDay _selectedTime = const TimeOfDay(hour: 15, minute: 0);
  RepeatOption _repeatOption = RepeatOption.never;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey.shade100,
      body: Center(
        child: EditTaskCard(
          selectedDate: _selectedDate,
          selectedTime: _selectedTime,
          repeatOption: _repeatOption,
          onDateChanged: (date) {
            setState(() {
              _selectedDate = date;
            });
          },
          onTimeChanged: (time) {
            setState(() {
              _selectedTime = time;
            });
          },
          onRepeatChanged: (option) {
            setState(() {
              _repeatOption = option;
            });
          },
          onSave: () {
            print('保存任务');
          },
          onCancel: () {
            print('取消编辑');
          },
        ),
      ),
    );
  }
}
