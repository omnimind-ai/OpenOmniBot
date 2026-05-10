import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import '../bot_status.dart';
import '../buttons_group_two.dart';
import '../normal_choices_group.dart';
import '../../models/block_models.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class ActionCard extends StatefulWidget {
  final ActionStepsBlock block;
  final bool shouldAnimate;
  final VoidCallback? onAnimationCompleted;
  final Function(ButtonModel)? onButtonConsumed;

  const ActionCard({
    Key? key,
    required this.block,
    this.shouldAnimate = true,
    this.onAnimationCompleted,
    this.onButtonConsumed,
  }) : super(key: key);

  @override
  State<ActionCard> createState() => _ActionCardState();
}

class _ActionCardState extends State<ActionCard>
    with TickerProviderStateMixin {
  late AnimationController _countdownController;
  late Animation<int> _countdownAnimation;
  final List<ActionStep> _rendered = [];
  bool _hasInserted = false;
  bool _isInserting = false;
  bool _executing = true;
  final GlobalKey<AnimatedListState> _stepListKey = GlobalKey<AnimatedListState>();

  @override
  void initState() {
    super.initState();
    _countdownController = AnimationController(
      duration: const Duration(seconds: 6),
      vsync: this,
    );
    _countdownAnimation = IntTween(
      begin: 6,
      end: 0,
    ).animate(_countdownController);

    // 倒计时结束时自动执行
    _countdownController.addStatusListener((status) {
      if (status == AnimationStatus.completed) {
        onExecute();
        widget.onButtonConsumed?.call(
          widget.block.buttonList.firstWhere(
            (button) => button.action == 'execute',
            orElse: () => ButtonModel(action: 'errorConsume', text: ''),
          )
        );
      }
    });

    if (widget.shouldAnimate) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && !_hasInserted && !_isInserting) {
          Future.delayed(const Duration(milliseconds: 150), _insertStepsAnimated);
        }
      });
    } else {
      _insertStepsImmediately();
    }
  }

  void _startCountdown() {
    if (!mounted) return;
    _countdownController.forward();
  }

  void onExecute() {
    setState(() {
      _executing = false;
    });
    print('Execute action steps');
  }

  void onCancel() {
    setState(() {
      _executing = false;
    });
    print('Cancel action steps');
  }

  void onButtonPressed(ButtonModel button) {
    if(button.action == 'execute') {
      onExecute();
    } else if(button.action == 'cancel') {
      onCancel();
    }
    widget.onButtonConsumed?.call(button);
  }

  @override
  void dispose() {
    _countdownController.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant ActionCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.shouldAnimate && !_hasInserted && !_isInserting) {
      Future.delayed(const Duration(milliseconds: 50), _insertStepsAnimated);
    }
  }

  // 立即插入所有步骤（无动画）
  void _insertStepsImmediately() {
    if (_hasInserted) return;

    for (int taskIdx = 0; taskIdx < widget.block.steps.length; taskIdx++) {
      // 多任务时插入任务头部标识
      if (widget.block.steps.length > 1) {
        _rendered.add(ActionStep(description: LegacyTextLocalizer.isEnglish ? 'Task ${taskIdx + 1}' : '任务${taskIdx + 1}', isHeader: true));
      }
      // 插入任务步骤
      for (int i = 0; i < widget.block.steps[taskIdx].length; i++) {
        _rendered.add(widget.block.steps[taskIdx][i]);
      }
    }
    
    setState(() {
      _hasInserted = true;
    });
    
    widget.onAnimationCompleted?.call();
    _startCountdown();
  }

  void _insertStepsAnimated() async {
    if (_hasInserted || _isInserting || !mounted) return;
    
    _isInserting = true; // 加锁

    for (int taskIdx = 0; taskIdx < widget.block.steps.length; taskIdx++) {
      if (!mounted) {
        _isInserting = false;
        return;
      }
      // 多任务时插入任务头部标识
      if (widget.block.steps.length > 1) {
        _rendered.add(ActionStep(description: LegacyTextLocalizer.isEnglish ? 'Task ${taskIdx + 1}' : '任务${taskIdx + 1}', isHeader: true));
        _stepListKey.currentState?.insertItem(_rendered.length - 1, duration: const Duration(milliseconds: 250));
        await Future.delayed(const Duration(milliseconds: 180));
      }
      // 插入任务步骤
      for (int i = 0; i < widget.block.steps[taskIdx].length; i++) {
        _rendered.add(widget.block.steps[taskIdx][i]);
        _stepListKey.currentState?.insertItem(_rendered.length - 1, duration: const Duration(milliseconds: 300));
        await Future.delayed(const Duration(milliseconds: 360));
      }
    }
    
    if (mounted) {
      widget.onAnimationCompleted?.call();
      _startCountdown();
      setState(() {
        _hasInserted = true;
        _isInserting = false; // 解锁
      });
    } else {
      _isInserting = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final cancelButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'cancel',
      orElse: () => ButtonModel(action: 'cancel', text: ''),
    );
    final confirmButton = widget.block.buttonList.firstWhere(
      (button) => button.action == 'execute',
      orElse: () => ButtonModel(action: 'execute', text: ''),
    );

    return Column(
      children: [
        BotStatus(status: BotStatusType.hint, hintText: LegacyTextLocalizer.localize('好，我来帮你完成')),
        SizedBox(height: 8),
        Container(
          margin: const EdgeInsets.only(left: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey.shade50,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Column(
                  children: [
                    BotStatus(status: BotStatusType.completed, costTime: widget.block.costTime),
                    AnimatedList(
                      key: _stepListKey,
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      initialItemCount: _rendered.length,
                      itemBuilder: (context, index, animation) {
                        final step = _rendered[index];
                        // 任务头部
                        if (step.isHeader == true) {
                          return SizeTransition(
                            sizeFactor: CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
                            child: FadeTransition(
                              opacity: animation,
                              child: Padding(
                                padding: const EdgeInsets.only(top: 16.0, bottom: 4.0),
                                child: Text(
                                  step.description,
                                  style: const TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                              ),
                            ),
                          );
                        }
                        // 普通步骤
                        bool first = index == 0 || _rendered[index - 1].isHeader;
                        bool last = index + 1 == _rendered.length || _rendered[index + 1].isHeader;
                        return SizeTransition(
                          sizeFactor: CurvedAnimation(parent: animation, curve: Curves.easeOutCubic),
                          child: FadeTransition(
                            opacity: animation,
                            child: Padding(
                              padding: const EdgeInsets.only(top: 8.0),
                              child: _StepChip(
                                step: step,
                                first: first,
                                last: last,
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ],
                )
              ),
              SizedBox(height: 12),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 350),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeInCubic,
                child: _hasInserted && !widget.block.isConsumed
                    ? ButtonsGroupTwo(
                        key: const ValueKey('buttons_group_two'),
                        leftButton: cancelButton,
                        rightButton: confirmButton,
                        countdownAnimation: _countdownAnimation,
                        isExecuting: _executing,
                        onButtonPressed: onButtonPressed,
                      )
                    : const SizedBox.shrink(key: ValueKey('buttons_group_empty')),
              ),
            ],
          ),
        ),
      ]
    );
  }
}

class _StepChip extends StatelessWidget {
  final ActionStep step;
  final bool first;
  final bool last;
  const _StepChip({required this.step, required this.first, required this.last});

  @override
  Widget build(BuildContext context) {
    if (step.isHeader == true) {
      return const SizedBox.shrink();
    }

    // 生成步骤前置图标：优先加载中，其次完成，否则箭头／任务头
    Widget leading;
    // if (step.status == 'in_progress') {
    //   leading = SizedBox(
    //     width: 16,
    //     height: 16,
    //     child: CircularProgressIndicator(strokeWidth: 2),
    //   );
    // } else {
    //   IconData icon;
    //   Color iconColor;
    //   if (first) {
    //     icon = Icons.flag;
    //     iconColor = Colors.blue;
    //   } else if (step.status == 'completed') {
    //     icon = Icons.check_circle;
    //     iconColor = Colors.green;
    //   } else {
    //     icon = Icons.arrow_downward;
    //     iconColor = Colors.grey;
    //   }
    //   leading = Icon(icon, size: 16, color: iconColor);
    // }

    // 生成步骤前置图标：第一个步骤为旗帜，最后一步为对号，其他为箭头
    if (first) {
      leading = const Icon(Icons.flag, size: 16, color: Colors.blue);
    } else if (last) {
      leading = const Icon(Icons.check_circle, size: 16, color: Colors.green);
    } else {
      leading = const Icon(Icons.arrow_downward, size: 16, color: Colors.grey);
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          margin: const EdgeInsets.only(top: 2),
          child: leading,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(
                step.description,
                style: TextStyle(
                  fontSize: 14,
                  // 使用 status 替换原 isCompleted 决定文字颜色
                  color: step.status == 'completed'
                      ? Colors.black
                      : Colors.grey.shade700,
                ),
              ),
              if (step.isUserAction) ...[
                const SizedBox(width: 6),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.orange.shade100,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    LegacyTextLocalizer.localize('用户操作'),
                    style: TextStyle(
                      fontSize: 10,
                      color: Colors.orange,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

// 使用示例
class ActionCardExample extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ActionCard(
      block: ActionStepsBlock(
        id: 'example_block',
        taskId: 'example_task',
        steps: const [
        [
          ActionStep(
            description: '打开"支付宝"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"我的"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"设置"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"支付设置"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"自动续费/免密支付"',
            status: 'pending',
          ),
          ActionStep(
            description: '点击"自动续费"',
            status: 'pending',
          ),
          ActionStep(
            description: '选择需关闭项',
            status: 'pending',
            isUserAction: true,
          ),
          ActionStep(
            description: '关闭付费项目',
            status: 'pending',
          ),
        ],
        [
          ActionStep(
            description: '打开"支付宝"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"我的"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"设置"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"支付设置"',
            status: 'pending',
          ),
          ActionStep(
            description: '进入"自动续费/免密支付"',
            status: 'pending',
          ),
          ActionStep(
            description: '点击"自动续费"',
            status: 'pending',
          ),
          ActionStep(
            description: '选择需关闭项',
            status: 'pending',
            isUserAction: true,
          ),
          ActionStep(
            description: '关闭付费项目',
            status: 'pending',
          ),
        ],
      ],
      buttonList: [
        ButtonModel(text: '取消', action: 'cancel'),
        ButtonModel(text: '去执行', action: 'execute'),
      ],
      )
    );
  }
}
