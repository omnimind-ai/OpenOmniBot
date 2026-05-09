import 'package:flutter/widgets.dart';

class ChatDrawerGestureGate {
  ChatDrawerGestureGate._();

  static final Set<int> _activePointerIds = <int>{};

  static bool containsPointer(int pointer) =>
      _activePointerIds.contains(pointer);

  static void holdPointer(int pointer) {
    _activePointerIds.add(pointer);
  }

  static void releasePointer(int pointer) {
    _activePointerIds.remove(pointer);
  }
}

class ChatDrawerGestureGuard extends StatelessWidget {
  const ChatDrawerGestureGuard({
    super.key,
    required this.child,
    this.behavior = HitTestBehavior.translucent,
  });

  final Widget child;
  final HitTestBehavior behavior;

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: behavior,
      onPointerDown: (event) {
        ChatDrawerGestureGate.holdPointer(event.pointer);
      },
      onPointerUp: (event) {
        ChatDrawerGestureGate.releasePointer(event.pointer);
      },
      onPointerCancel: (event) {
        ChatDrawerGestureGate.releasePointer(event.pointer);
      },
      child: child,
    );
  }
}
