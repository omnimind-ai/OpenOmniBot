import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/widgets/normal_options_card.dart';
import '../../models/block_models.dart';
import '../buttons_group_two.dart';
import '../bot_status.dart';

class TaskOptionsCard extends StatefulWidget {
  final TaskOptionsBlock block;

  const TaskOptionsCard({
    Key? key,
    required this.block,
  }) : super(key: key);

  @override
  State<TaskOptionsCard> createState() => _TaskOptionsCardState();
}

class _TaskOptionsCardState extends State<TaskOptionsCard>
    with TickerProviderStateMixin {
  late List<TaskOption> _selected = [];

  @override
  void initState() {
    super.initState();
  }

  @override
  void dispose() {
    super.dispose();
  }

  void onTaskConfirm() {
    print('Confirmed tasks: ${_selected.map((t) => t.name).toList()}');
  }

  void onTaskCancel() {
    print('Task selection cancelled');
  }

  void onButtonPressed(ButtonModel button) {
    if(button.action == 'confirm') {
      onTaskConfirm();
    } else if(button.action == 'cancel') {
      setState(() {
        _selected.clear();
      });
      onTaskCancel();
    }
  }
  
  void onTaskSelectionChanged() {
    print('Task selection changed');
  }

  @override
  Widget build(BuildContext context) {
    final cancelButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'cancel',
      orElse: () => ButtonModel(action: 'cancel', text: ''),
    );
    final confirmButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'confirm',
      orElse: () => ButtonModel(action: 'confirm', text: ''),
    );

    final b = widget.block;
    return Column(
      children: [
        BotStatus(status: BotStatusType.hint, hintText: LegacyTextLocalizer.localize('请选择一个任务')),
        const SizedBox(height: 8),
        Container(
          margin: const EdgeInsets.symmetric(vertical: 8.0),
          padding: const EdgeInsets.all(16.0),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12.0),
            border: Border.all(color: Colors.grey.shade200),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              NormalOptionsCard(
                title: b.title ?? LegacyTextLocalizer.localize('任务选项'),
                subtitle: b.taskDesc ?? LegacyTextLocalizer.localize('请选择你想执行的任务'),
                options: b.options.map((t) => t.toOptionItem()).toList(),
                multiSelect: b.multiSelect,
                onSelectionChanged: (sel) {
                  _selected = sel
                      .map((c) => b.options.firstWhere((a) => a.name == c.title))
                      .toList();
                  onTaskSelectionChanged();
                  print(
                    'Selected tasks: ${_selected.map((t) => t.name).toList()}'
                  );
                },
              ),
              const SizedBox(height: 12),
              if (!widget.block.isConsumed)
                ButtonsGroupTwo(
                  leftButton: cancelButton,
                  rightButton: confirmButton,
                  onButtonPressed: onButtonPressed,
                ),
            ],
          ),
        )
      ]
    );
  }
}
