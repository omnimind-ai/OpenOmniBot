import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';

class ConversationSlideAction {
  const ConversationSlideAction({
    required this.onPressed,
    required this.backgroundColor,
    required this.child,
    this.borderRadius = BorderRadius.zero,
  });

  final VoidCallback onPressed;
  final Color backgroundColor;
  final Widget child;
  final BorderRadius borderRadius;
}

class ConversationSlidable extends ConsumerWidget {
  const ConversationSlidable({
    super.key,
    required this.itemKey,
    required this.groupTag,
    required this.actions,
    required this.onDismissed,
    required this.child,
    this.isBusy = false,
    this.margin = EdgeInsets.zero,
    this.actionExtentRatioPerAction = 0.24,
    this.dismissThreshold,
    this.onFullSwipe,
  }) : assert(actions.length > 0, 'actions must not be empty');

  final String itemKey;
  final Object groupTag;
  final List<ConversationSlideAction> actions;
  final VoidCallback onDismissed;
  final Widget child;
  final bool isBusy;
  final EdgeInsetsGeometry margin;
  final double actionExtentRatioPerAction;
  final double? dismissThreshold;
  final VoidCallback? onFullSwipe;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isLeftHanded = ref.watch(habitualHandProvider).isLeft;
    final totalExtentRatio = (actionExtentRatioPerAction * actions.length)
        .clamp(0.0, 1.0)
        .toDouble();
    final resolvedDismissThreshold =
        dismissThreshold ??
        (actions.length > 1
            ? (totalExtentRatio + 0.16).clamp(0.0, 0.95).toDouble()
            : 0.4);
    final effectiveActions = isLeftHanded
        ? actions.reversed.toList(growable: false)
        : actions;
    final actionPane = ActionPane(
      motion: const BehindMotion(),
      extentRatio: totalExtentRatio,
      dismissible: DismissiblePane(
        dismissThreshold: resolvedDismissThreshold,
        closeOnCancel: true,
        motion: const InversedDrawerMotion(),
        onDismissed: onFullSwipe ?? onDismissed,
      ),
      children: effectiveActions
          .map(
            (action) => CustomSlidableAction(
              onPressed: (_) => action.onPressed(),
              backgroundColor: action.backgroundColor,
              borderRadius: isLeftHanded
                  ? _mirrorBorderRadius(action.borderRadius)
                  : action.borderRadius,
              padding: EdgeInsets.zero,
              child: action.child,
            ),
          )
          .toList(),
    );

    return Container(
      margin: margin,
      child: IgnorePointer(
        ignoring: isBusy,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: isBusy ? 0.72 : 1,
          child: Slidable(
            key: ValueKey<String>(itemKey),
            groupTag: groupTag,
            closeOnScroll: true,
            startActionPane: isLeftHanded ? actionPane : null,
            endActionPane: isLeftHanded ? null : actionPane,
            child: child,
          ),
        ),
      ),
    );
  }

  BorderRadius _mirrorBorderRadius(BorderRadius radius) {
    return BorderRadius.only(
      topLeft: radius.topRight,
      topRight: radius.topLeft,
      bottomLeft: radius.bottomRight,
      bottomRight: radius.bottomLeft,
    );
  }
}
