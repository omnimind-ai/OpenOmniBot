import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import '../../models/block_models.dart';
import '../normal_choices_group.dart';
import '../buttons_group_two.dart';
import '../bot_status.dart';

class AppOptionsCard extends StatefulWidget {
  final AppOptionsBlock block;

  const AppOptionsCard({
    Key? key,
    required this.block,
  }) : super(key: key);

  @override
  State<AppOptionsCard> createState() => _AppOptionsCardState();
}

class _AppOptionsCardState extends State<AppOptionsCard>
    with TickerProviderStateMixin {
  late List<AppOption> _selected = [];
  late AnimationController _ctrl;
  late Animation<int> _countdown;
  bool _executing = false;

  void onAppConfirm() {
    setState(() {
      _executing = false;
    });
    _ctrl.reset();
    print('Confirmed apps: ${_selected.map((a) => a.name).toList()}');
  }

  void onAppCancel() {
    setState(() {
      _selected.clear();
      _executing = false;
    });
    _ctrl.reset();
    print('App selection cancelled');
  }

  void onButtonPressed(ButtonModel button) {
    if(button.action == 'confirm') {
      onAppConfirm();
    } else if(button.action == 'cancel') {
      onAppCancel();
    }
  }

  void onAppSelectionChanged(List<AppOption> selected) {
    print('App selection changed');
  }
  
  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(seconds: 6));
    _countdown = IntTween(begin: 6, end: 0).animate(_ctrl);

    _ctrl.addStatusListener((status) {
      if (mounted && status == AnimationStatus.completed && _selected.isNotEmpty) {
        setState(() {
          _executing = false;
        });
        onAppConfirm();
      }
    });
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final b = widget.block;
    return Column(
      children: [
        BotStatus(status: BotStatusType.hint, hintText: LegacyTextLocalizer.localize('请选择一个应用程序')),
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
              NormalChoicesGroup(
                options: b.applications.map((a) => a.toChoiceOption()).toList(),
                multiSelect: b.multiSelect,
                onSelectionChanged: (sel) {
                  _selected = sel
                      .map((c) => b.applications.firstWhere((a) => a.packageName == c.value))
                      .toList();
                  onAppSelectionChanged(_selected);
                  if (_selected.isEmpty) {
                    _ctrl.reset();
                    setState(() => _executing = false);
                  }else{
                    _ctrl.forward(from: 0);
                    setState(() => _executing = true);
                  }
                },
              ),
              const SizedBox(height: 12),
              if (!widget.block.isConsumed)
                ButtonsGroupTwo(
                  leftButton: ButtonModel(
                    text: LegacyTextLocalizer.localize('取消'),
                    action: 'cancel',
                  ),
                  rightButton: ButtonModel(
                    text: LegacyTextLocalizer.localize('确认'),
                    action: 'confirm',
                  ),
                  countdownAnimation: _countdown,
                  isExecuting: _executing,
                  onButtonPressed: onButtonPressed,
                ),
            ],
          ),
        )
      ]
    );
    
  }
}