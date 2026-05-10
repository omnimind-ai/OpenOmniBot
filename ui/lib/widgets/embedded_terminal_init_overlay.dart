import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/utils/ui.dart';

class EmbeddedTerminalInitToastListener extends StatefulWidget {
  const EmbeddedTerminalInitToastListener({super.key});

  @override
  State<EmbeddedTerminalInitToastListener> createState() =>
      _EmbeddedTerminalInitToastListenerState();
}

class _EmbeddedTerminalInitToastListenerState
    extends State<EmbeddedTerminalInitToastListener> {
  StreamSubscription<EmbeddedTerminalInitProgress>? _progressSubscription;
  int? _lastHandledStartedAtMillis;
  int? _lastHandledCompletionMillis;

  @override
  void initState() {
    super.initState();
    _progressSubscription = embeddedTerminalInitProgressStream.listen((_) {
      unawaited(_reloadSnapshot());
    });
    unawaited(_reloadSnapshot());
  }

  @override
  void dispose() {
    _progressSubscription?.cancel();
    super.dispose();
  }

  Future<void> _reloadSnapshot() async {
    try {
      final snapshot = await getEmbeddedTerminalInitSnapshot();
      if (!mounted) {
        return;
      }
      _applySnapshot(snapshot);
    } catch (_) {}
  }

  void _applySnapshot(EmbeddedTerminalInitSnapshot snapshot) {
    final startedAtMillis = snapshot.startedAt?.millisecondsSinceEpoch;
    final completionMillis = snapshot.completedAt?.millisecondsSinceEpoch;

    if (snapshot.running) {
      if (startedAtMillis != null &&
          startedAtMillis != _lastHandledStartedAtMillis) {
        _lastHandledStartedAtMillis = startedAtMillis;
        _showStartToast();
      }
    }

    if (snapshot.completed) {
      if (completionMillis != null &&
          completionMillis != _lastHandledCompletionMillis) {
        _lastHandledCompletionMillis = completionMillis;
        _showCompletionToast(snapshot);
      }
      if (startedAtMillis != null) {
        _lastHandledStartedAtMillis = startedAtMillis;
      }
    }
  }

  void _showStartToast() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      showToast(
        LegacyTextLocalizer.isEnglish ? 'Preparing Alpine environment' : '开始准备 Alpine 环境',
        type: ToastType.info,
        duration: const Duration(seconds: 2),
      );
    });
  }

  void _showCompletionToast(EmbeddedTerminalInitSnapshot snapshot) {
    final success = snapshot.success == true;
    final message = snapshot.stage.isNotEmpty
        ? snapshot.stage
        : success
        ? (LegacyTextLocalizer.isEnglish ? 'Alpine environment ready' : 'Alpine 环境已准备完成')
        : (LegacyTextLocalizer.isEnglish ? 'Alpine environment preparation failed' : 'Alpine 环境准备失败');
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      showToast(
        message,
        type: success ? ToastType.success : ToastType.error,
        duration: const Duration(seconds: 3),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    return const SizedBox.shrink();
  }
}
