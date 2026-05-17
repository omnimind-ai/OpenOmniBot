import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/task/run_log/run_log_reusable_function_converter.dart';
import 'package:ui/features/task/pages/scheduled_tasks/widgets/schedule_task_sheet.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

Future<void> showRunLogTimelineSheet(
  BuildContext context, {
  required String runId,
  String title = 'RunLog',
  String? baseUrl,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) =>
        _RunLogTimelineSheetFrame(runId: runId, title: title, baseUrl: baseUrl),
  );
}

/// 通过 runId + cardId 直接跳到单步 detail sheet。
/// 找不到匹配的 card 时 fallback 到完整 timeline。
Future<void> showRunLogStepDetailSheet(
  BuildContext context, {
  required String runId,
  required String cardId,
  String title = 'RunLog',
  String? baseUrl,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) => _StepDetailLoader(
      runId: runId,
      cardId: cardId,
      title: title,
      baseUrl: baseUrl,
    ),
  );
}

/// 内部 widget：先拉 payload，再定位到目标 card 并展示单步 detail。
class _StepDetailLoader extends StatefulWidget {
  const _StepDetailLoader({
    required this.runId,
    required this.cardId,
    required this.title,
    this.baseUrl,
  });

  final String runId;
  final String cardId;
  final String title;
  final String? baseUrl;

  @override
  State<_StepDetailLoader> createState() => _StepDetailLoaderState();
}

class _StepDetailLoaderState extends State<_StepDetailLoader> {
  bool _loading = true;
  Map<String, dynamic>? _card;
  int _cardIndex = 0;
  Map<String, dynamic> _payload = const {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final payload = await AssistsMessageService.getInternalRunLogTimeline(
        runId: widget.runId,
      );
      if (!mounted) return;
      final cards = _extractTimelineCards(payload);
      // Match through the same identity policy as live tool cards so runlog
      // detail links survive adapter differences such as callId vs toolCallId.
      final targetId = widget.cardId.trim().toLowerCase();
      Map<String, dynamic>? matched;
      int matchedIndex = 0;
      for (int i = 0; i < cards.length; i++) {
        final c = cards[i];
        if (AgentToolCardPolicy.cardMatchesId(c, targetId)) {
          matched = c;
          matchedIndex = i;
          break;
        }
      }
      setState(() {
        _payload = payload;
        _card = matched ?? (cards.isNotEmpty ? cards.first : null);
        _cardIndex = matched != null ? matchedIndex : 0;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      final palette = context.omniPalette;
      return GestureDetector(
        onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
        behavior: HitTestBehavior.opaque,
        child: SafeArea(
          top: false,
          child: Align(
            alignment: Alignment.bottomCenter,
            child: GestureDetector(
              onTap: () {},
              child: Container(
                height: 180,
                width: double.infinity,
                decoration: BoxDecoration(
                  color: palette.pageBackground,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                ),
                child: Center(
                  child: CircularProgressIndicator(
                    color: palette.textSecondary,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    }
    final card = _card;
    if (card == null) {
      // 没找到任何 card，fallback 到完整 timeline
      return _RunLogTimelineSheetFrame(
        runId: widget.runId,
        title: widget.title,
        baseUrl: widget.baseUrl,
      );
    }
    return _StepDetailSheet(
      card: card,
      fallbackIndex: _cardIndex,
      runId: widget.runId,
      title: widget.title,
      payload: _payload,
      baseUrl: widget.baseUrl,
    );
  }
}

class RunLogTimelinePage extends StatefulWidget {
  const RunLogTimelinePage({
    super.key,
    required this.runId,
    required this.title,
    this.baseUrl,
    this.embedded = false,
  });

  final String runId;
  final String title;
  final String? baseUrl;
  final bool embedded;

  @override
  State<RunLogTimelinePage> createState() => _RunLogTimelinePageState();
}

class _RunLogTimelinePageState extends State<RunLogTimelinePage> {
  Map<String, dynamic> _payload = const {};
  List<Map<String, dynamic>> _cards = [];
  bool _isLoading = true;
  bool _isConvertingFunction = false;
  bool _isReplayingRunLog = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final payload = await AssistsMessageService.getInternalRunLogTimeline(
        runId: widget.runId,
      );
      if (!mounted) return;
      setState(() {
        _payload = payload;
        _cards = _extractTimelineCards(payload);
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final stepCount = _cards.length;
    final subtitle = stepCount > 0
        ? l10n.runLogTimelineStepCount(stepCount)
        : null;
    final title = subtitle != null
        ? '${l10n.runLogTimelineTitle}  $subtitle'
        : l10n.runLogTimelineTitle;
    final List<Widget> actions = <Widget>[
      Tooltip(
        message: _text(context, '执行 RunLog', 'Run RunLog'),
        child: IconButton(
          icon: _isReplayingRunLog
              ? SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: palette.textPrimary,
                  ),
                )
              : const Icon(Icons.play_arrow_rounded),
          color: palette.textPrimary,
          onPressed:
              _cards.isEmpty || _isConvertingFunction || _isReplayingRunLog
              ? null
              : _executeCurrentRunLog,
        ),
      ),
      Tooltip(
        message: _text(context, 'AI 转功能', 'Convert to function'),
        child: IconButton(
          icon: _isConvertingFunction
              ? SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: palette.textPrimary,
                  ),
                )
              : const Icon(Icons.auto_awesome_rounded),
          color: palette.textPrimary,
          onPressed:
              _cards.isEmpty || _isConvertingFunction || _isReplayingRunLog
              ? null
              : _convertToReusableFunction,
        ),
      ),
      Tooltip(
        message: _text(context, '复制全部文本', 'Copy all text'),
        child: IconButton(
          icon: const Icon(Icons.copy_all_rounded),
          color: palette.textPrimary,
          onPressed: _cards.isEmpty ? null : _copyAllText,
        ),
      ),
    ];

    if (widget.embedded) {
      return ColoredBox(
        color: palette.pageBackground,
        child: Column(
          children: [
            _RunLogTimelineSheetHeader(title: title, actions: actions),
            Expanded(child: _buildBody(context)),
          ],
        ),
      );
    }

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: title, actions: actions),
      body: _buildBody(context),
    );
  }

  Widget _buildBody(BuildContext context) {
    final l10n = context.l10n;
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Text(
          '${l10n.runLogTimelineLoadFailed}\n$_error',
          textAlign: TextAlign.center,
          style: TextStyle(color: context.omniPalette.textSecondary),
        ),
      );
    }
    if (_cards.isEmpty) {
      return Center(
        child: Text(
          l10n.runLogTimelineEmpty,
          style: TextStyle(color: context.omniPalette.textSecondary),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
      itemCount: _cards.length,
      itemBuilder: (context, index) => _StepCard(
        card: _cards[index],
        fallbackIndex: index,
        isLast: index == _cards.length - 1,
        onTap: () => _showStepDetail(_cards[index], index),
      ),
    );
  }

  Future<void> _copyAllText() async {
    final text = _buildRunLogTranscript();
    if (text.trim().isEmpty) {
      showToast(
        _text(context, '暂无可复制内容', 'Nothing to copy'),
        type: ToastType.warning,
      );
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    showToast(
      _text(context, '已复制全部执行文本', 'Copied full execution text'),
      type: ToastType.success,
    );
  }

  Future<void> _convertToReusableFunction() async {
    if (_cards.isEmpty || _isConvertingFunction) {
      return;
    }
    setState(() {
      _isConvertingFunction = true;
    });
    showToast(
      _text(context, '正在转换为可复用功能...', 'Converting to reusable function...'),
      type: ToastType.info,
    );
    try {
      final spec = await RunLogReusableFunctionConverter.convert(
        runId: widget.runId,
        title: widget.title,
        payload: _payload,
        cards: _cards,
        useAi: true,
        useEnglish: _localeValue(context, zh: false, en: true),
      );
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
      });
      if (spec.warning != null && spec.warning!.trim().isNotEmpty) {
        showToast(spec.warning!, type: ToastType.warning);
      } else {
        showToast(
          _text(context, '功能结构已生成', 'Function spec generated'),
          type: ToastType.success,
        );
      }
      await _showReusableFunctionSheet(spec);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
      });
      final message = _text(context, '转换失败', 'Conversion failed');
      showToast('$message: $e', type: ToastType.error);
    }
  }

  Future<void> _executeCurrentRunLog() async {
    if (_cards.isEmpty || _isConvertingFunction || _isReplayingRunLog) {
      return;
    }
    setState(() {
      _isReplayingRunLog = true;
    });
    showToast(
      _text(context, '正在准备并执行 RunLog...', 'Preparing and running RunLog...'),
      type: ToastType.info,
    );
    final executionFailedText = _text(
      context,
      'RunLog 执行失败',
      'RunLog execution failed',
    );
    final conversionFailedText = _text(
      context,
      'RunLog 转换失败',
      'RunLog conversion failed',
    );

    try {
      final convertResult =
          await AssistsMessageService.convertInternalRunLogToOobFunction(
            runId: widget.runId,
            register: true,
          );
      final functionId = _firstNonBlank([
        convertResult['created_function_id'],
        convertResult['function_id'],
      ]);
      if (convertResult['success'] != true || functionId.isEmpty) {
        final message = convertResult['error_message']?.toString().trim();
        throw Exception(
          message?.isNotEmpty == true ? message : conversionFailedText,
        );
      }
      final spec = convertResult['function_spec'] is Map
          ? Map<String, dynamic>.from(
              (convertResult['function_spec'] as Map).map(
                (key, value) => MapEntry(key.toString(), value),
              ),
            )
          : const <String, dynamic>{};

      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: functionId,
        arguments: _defaultArgumentsForFunctionSpec(spec),
      );
      if (!mounted) return;
      setState(() {
        _isReplayingRunLog = false;
      });
      showToast(
        result.success
            ? _runLogReplaySuccessMessage(context, result)
            : _runLogReplayFailureMessage(context, result),
        type: result.success ? ToastType.success : ToastType.error,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isReplayingRunLog = false;
      });
      showToast('$executionFailedText: $e', type: ToastType.error);
    }
  }

  String _buildRunLogTranscript() {
    final lines = <String>[
      '# ${widget.title.trim().isEmpty ? 'RunLog' : widget.title.trim()}',
      '',
      'Run ID: ${widget.runId}',
      'Steps: ${_cards.length}',
    ];

    final goal = _firstNonBlank([
      _payload['goal'],
      _payload['task_goal'],
      _payload['operation_description'],
      _payload['operationDescription'],
    ]);
    if (goal.isNotEmpty) {
      lines.add('Goal: $goal');
    }

    lines.add('');
    lines.add('## Tool Call History');
    for (var index = 0; index < _cards.length; index++) {
      if (index > 0) {
        lines.add('');
      }
      lines.add(
        _RunLogStepSnapshot.fromCard(
          _cards[index],
          fallbackIndex: index,
        ).toTranscript(),
      );
    }

    if (_payload.isNotEmpty) {
      lines.add('');
      lines.add('## Raw Timeline Payload');
      lines.add(_prettyJson(_payload));
    }

    return lines.join('\n').trimRight();
  }

  Future<void> _showStepDetail(Map<String, dynamic> card, int index) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (sheetContext) => _StepDetailSheet(
        card: card,
        fallbackIndex: index,
        runId: widget.runId,
        title: widget.title,
        payload: _payload,
        baseUrl: widget.baseUrl,
      ),
    );
  }

  Future<void> _showReusableFunctionSheet(RunLogReusableFunctionSpec spec) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (sheetContext) => _ReusableFunctionSpecSheet(
        spec: spec,
        runId: widget.runId,
        baseUrl: widget.baseUrl,
      ),
    );
  }
}

class _RunLogTimelineSheetFrame extends StatefulWidget {
  const _RunLogTimelineSheetFrame({
    required this.runId,
    required this.title,
    this.baseUrl,
  });

  final String runId;
  final String title;
  final String? baseUrl;

  @override
  State<_RunLogTimelineSheetFrame> createState() =>
      _RunLogTimelineSheetFrameState();
}

class _RunLogTimelineSheetFrameState extends State<_RunLogTimelineSheetFrame> {
  static const double _minHeightFactor = 0.36;
  static const double _maxHeightFactor = 0.94;

  double? _heightFactor;

  double _initialHeightFactor(double viewportHeight) {
    return viewportHeight < 720 ? 0.72 : 0.62;
  }

  void _handleDragUpdate(DragUpdateDetails details, double availableHeight) {
    if (availableHeight <= 0) {
      return;
    }
    final delta = details.primaryDelta ?? details.delta.dy;
    setState(() {
      final current =
          _heightFactor ??
          _initialHeightFactor(MediaQuery.sizeOf(context).height);
      _heightFactor = (current - delta / availableHeight).clamp(
        _minHeightFactor,
        _maxHeightFactor,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final palette = context.omniPalette;
    final availableHeight = math.max(
      320.0,
      mediaQuery.size.height -
          mediaQuery.padding.top -
          mediaQuery.viewInsets.bottom,
    );
    final heightFactor =
        _heightFactor ?? _initialHeightFactor(mediaQuery.size.height);
    const borderRadius = BorderRadius.vertical(top: Radius.circular(24));

    return SafeArea(
      top: false,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.only(bottom: mediaQuery.viewInsets.bottom),
        child: SizedBox(
          height: availableHeight * heightFactor,
          width: double.infinity,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: palette.pageBackground,
              borderRadius: borderRadius,
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.18),
                  blurRadius: 32,
                  offset: const Offset(0, -8),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: borderRadius,
              child: Material(
                color: palette.pageBackground,
                child: Column(
                  children: [
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onVerticalDragUpdate: (details) =>
                          _handleDragUpdate(details, availableHeight),
                      child: SizedBox(
                        height: 22,
                        width: double.infinity,
                        child: Center(
                          child: Container(
                            width: 42,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.textPrimary.withValues(
                                alpha: 0.18,
                              ),
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: RunLogTimelinePage(
                        runId: widget.runId,
                        title: widget.title,
                        baseUrl: widget.baseUrl,
                        embedded: true,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RunLogTimelineSheetHeader extends StatelessWidget {
  const _RunLogTimelineSheetHeader({
    required this.title,
    required this.actions,
  });

  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      height: 48,
      padding: const EdgeInsets.only(left: 18, right: 4),
      decoration: BoxDecoration(
        color: palette.pageBackground,
        border: Border(
          bottom: BorderSide(color: palette.borderSubtle, width: 0.5),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
          ),
          ...actions,
        ],
      ),
    );
  }
}

// ─── Step card with left-side timeline connector ──────────────────────────────

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.card,
    required this.fallbackIndex,
    required this.isLast,
    required this.onTap,
  });

  final Map<String, dynamic> card;
  final int fallbackIndex;
  final bool isLast;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final l10n = context.l10n;

    final snapshot = _RunLogStepSnapshot.fromCard(
      card,
      fallbackIndex: fallbackIndex,
    );
    final success = snapshot.success ?? true;
    final compileKind = snapshot.compileKind;
    final modelFree = _isModelFreeReplayStep(snapshot);

    final isHit = compileKind == 'hit';
    final dotColor = success
        ? (modelFree
              ? _modelFreeColor(context)
              : (isHit ? _successColor(context) : _routeColor(context)))
        : _errorColor(context);
    final lineColor = isDark ? palette.borderSubtle : Colors.grey.shade200;
    final baseCardColor = isDark ? palette.surfaceSecondary : Colors.white;
    final cardColor = modelFree
        ? Color.alphaBlend(
            _modelFreeColor(context).withValues(alpha: isDark ? 0.16 : 0.08),
            baseCardColor,
          )
        : baseCardColor;
    final borderColor = modelFree
        ? _modelFreeColor(context).withValues(alpha: isDark ? 0.38 : 0.26)
        : (isDark ? palette.borderSubtle : Colors.grey.shade100);
    final preview = snapshot.previewText;

    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Timeline spine: dot + vertical line
          SizedBox(
            width: 32,
            child: Column(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  margin: const EdgeInsets.only(top: 14),
                  decoration: BoxDecoration(
                    color: dotColor,
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(
                        color: dotColor.withValues(alpha: 0.35),
                        blurRadius: 6,
                      ),
                    ],
                  ),
                ),
                if (!isLast)
                  Expanded(
                    child: Center(
                      child: Container(width: 1.5, color: lineColor),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          // Card content
          Expanded(
            child: Container(
              margin: EdgeInsets.only(bottom: isLast ? 0 : 10),
              decoration: BoxDecoration(
                color: cardColor,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: borderColor),
              ),
              child: Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(12),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: onTap,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Header row: step number + badge + duration + status
                        Row(
                          children: [
                            Text(
                              'Step ${snapshot.stepNumber}',
                              style: TextStyle(
                                fontSize: 11,
                                color: palette.textSecondary,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                            const SizedBox(width: 6),
                            _RouteBadge(compileKind: compileKind, l10n: l10n),
                            if (modelFree) ...[
                              const SizedBox(width: 6),
                              const _ModelFreeBadge(),
                            ],
                            const Spacer(),
                            if (snapshot.durationMs != null)
                              Text(
                                _formatMs(snapshot.durationMs!),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                ),
                              ),
                            const SizedBox(width: 6),
                            Icon(
                              success
                                  ? Icons.check_circle_outline
                                  : Icons.cancel_outlined,
                              size: 14,
                              color: success
                                  ? _successColor(context)
                                  : _errorColor(context),
                            ),
                            const SizedBox(width: 4),
                            Icon(
                              Icons.chevron_right_rounded,
                              size: 16,
                              color: palette.textTertiary,
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        // Title
                        Text(
                          snapshot.title.isEmpty
                              ? l10n.runLogTimelineUnknown
                              : snapshot.title,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: palette.textPrimary,
                          ),
                        ),
                        if (snapshot.toolName.isNotEmpty &&
                            snapshot.toolName != snapshot.title) ...[
                          const SizedBox(height: 4),
                          Text(
                            snapshot.toolName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                              fontFamily: 'monospace',
                            ),
                          ),
                        ],
                        if (preview.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            preview,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                              height: 1.25,
                            ),
                          ),
                        ],
                        // Package name (if present)
                        if (snapshot.packageName.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            snapshot.packageName,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StepDetailSheet extends StatefulWidget {
  const _StepDetailSheet({
    required this.card,
    required this.fallbackIndex,
    required this.runId,
    required this.title,
    required this.payload,
    this.baseUrl,
  });

  final Map<String, dynamic> card;
  final int fallbackIndex;
  final String runId;
  final String title;
  final Map<String, dynamic> payload;
  final String? baseUrl;

  @override
  State<_StepDetailSheet> createState() => _StepDetailSheetState();
}

class _StepDetailSheetState extends State<_StepDetailSheet> {
  bool _isConvertingStep = false;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final snapshot = _RunLogStepSnapshot.fromCard(
      widget.card,
      fallbackIndex: widget.fallbackIndex,
    );
    final success = snapshot.success ?? true;
    final statusColor = success ? _successColor(context) : _errorColor(context);
    final sheetHeight = MediaQuery.of(context).size.height * 0.55;

    return GestureDetector(
      onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
      behavior: HitTestBehavior.opaque,
      child: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {},
            child: SizedBox(
              height: sheetHeight,
              width: double.infinity,
              child: Container(
                decoration: BoxDecoration(
                  color: isDark ? palette.surfacePrimary : Colors.white,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(
                        alpha: isDark ? 0.35 : 0.14,
                      ),
                      blurRadius: 26,
                      offset: const Offset(0, -10),
                    ),
                  ],
                ),
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderSubtle,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 14, 10, 10),
                      child: Row(
                        children: [
                          Container(
                            width: 28,
                            height: 28,
                            decoration: BoxDecoration(
                              color: statusColor.withValues(alpha: 0.12),
                              shape: BoxShape.circle,
                            ),
                            child: Icon(
                              success
                                  ? Icons.check_circle_outline_rounded
                                  : Icons.error_outline_rounded,
                              size: 16,
                              color: statusColor,
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '${_text(context, '工具调用历史', 'Tool call history')} · Step ${snapshot.stepNumber}',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  snapshot.title.isEmpty
                                      ? _text(context, '未知步骤', 'Unknown step')
                                      : snapshot.title,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 16,
                                    color: palette.textPrimary,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Tooltip(
                            message: _text(
                              context,
                              'AI 转此步',
                              'Convert this step',
                            ),
                            child: IconButton(
                              icon: _isConvertingStep
                                  ? SizedBox(
                                      width: 18,
                                      height: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: palette.textSecondary,
                                      ),
                                    )
                                  : const Icon(Icons.auto_awesome_rounded),
                              color: palette.textSecondary,
                              onPressed: _isConvertingStep
                                  ? null
                                  : () => _convertThisStep(snapshot),
                            ),
                          ),
                          Tooltip(
                            message: _text(context, '复制本步文本', 'Copy this step'),
                            child: IconButton(
                              icon: const Icon(Icons.content_copy_rounded),
                              color: palette.textSecondary,
                              onPressed: () => _copyText(
                                context,
                                snapshot.toTranscript(),
                                _text(context, '已复制本步文本', 'Copied this step'),
                              ),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close_rounded),
                            color: palette.textSecondary,
                            onPressed: () => Navigator.of(context).maybePop(),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, color: palette.borderSubtle),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Tool name chip + call ID inline (no card)
                            if (snapshot.toolName.isNotEmpty ||
                                snapshot.toolCallId.isNotEmpty)
                              Row(
                                children: [
                                  if (snapshot.toolName.isNotEmpty)
                                    Container(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 8,
                                        vertical: 4,
                                      ),
                                      decoration: BoxDecoration(
                                        color: _routeColor(context).withValues(
                                          alpha: isDark ? 0.15 : 0.09,
                                        ),
                                        borderRadius: BorderRadius.circular(6),
                                        border: Border.all(
                                          color: _routeColor(context)
                                              .withValues(
                                                alpha: isDark ? 0.30 : 0.18,
                                              ),
                                        ),
                                      ),
                                      child: Text(
                                        snapshot.toolName,
                                        style: TextStyle(
                                          fontSize: 12,
                                          fontFamily: 'monospace',
                                          fontWeight: FontWeight.w600,
                                          color: _routeColor(context),
                                        ),
                                      ),
                                    ),
                                  if (snapshot.toolCallId.isNotEmpty) ...[
                                    const SizedBox(width: 8),
                                    Expanded(
                                      child: Text(
                                        snapshot.toolCallId,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          fontSize: 11,
                                          fontFamily: 'monospace',
                                          color: palette.textTertiary,
                                        ),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            const SizedBox(height: 10),
                            // Status / route / duration pills
                            _SummaryGrid(snapshot: snapshot),
                            if (snapshot.prompt.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              _PromptHighlightBox(
                                text: snapshot.prompt,
                                source: snapshot.promptSource,
                              ),
                            ],
                            // Key param highlight row
                            if (snapshot.previewText.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              Container(
                                width: double.infinity,
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  color: statusColor.withValues(
                                    alpha: isDark ? 0.09 : 0.06,
                                  ),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: statusColor.withValues(
                                      alpha: isDark ? 0.22 : 0.15,
                                    ),
                                  ),
                                ),
                                child: Text(
                                  snapshot.previewText,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    height: 1.3,
                                  ),
                                ),
                              ),
                            ],
                            // Arguments — expanded by default
                            if (!_isEmptyJsonValue(snapshot.params)) ...[
                              const SizedBox(height: 12),
                              _CollapsibleSection(
                                title: _text(context, '参数', 'Arguments'),
                                copyValue: _prettyJson(snapshot.params),
                                initiallyExpanded: true,
                                child: _JsonBlock(value: snapshot.params),
                              ),
                            ],
                            // Result — expanded by default
                            if (!_isEmptyJsonValue(snapshot.result)) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '结果', 'Result'),
                                copyValue: _prettyJson(snapshot.result),
                                initiallyExpanded: true,
                                child: _JsonBlock(value: snapshot.result),
                              ),
                            ],
                            // Route result — collapsed by default
                            if (!_isEmptyJsonValue(snapshot.compileResult)) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(
                                  context,
                                  '路由/编译结果',
                                  'Route result',
                                ),
                                copyValue: _prettyJson(snapshot.compileResult),
                                initiallyExpanded: false,
                                child: _JsonBlock(
                                  value: snapshot.compileResult,
                                ),
                              ),
                            ],
                            // Before / after — collapsed by default
                            if (snapshot.before.isNotEmpty ||
                                snapshot.after.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '前后状态', 'Before / after'),
                                copyValue: _prettyJson({
                                  if (snapshot.before.isNotEmpty)
                                    'before': snapshot.before,
                                  if (snapshot.after.isNotEmpty)
                                    'after': snapshot.after,
                                }),
                                initiallyExpanded: false,
                                child: _JsonBlock(
                                  value: {
                                    if (snapshot.before.isNotEmpty)
                                      'before': snapshot.before,
                                    if (snapshot.after.isNotEmpty)
                                      'after': snapshot.after,
                                  },
                                ),
                              ),
                            ],
                            // Raw JSON — collapsed by default
                            const SizedBox(height: 8),
                            _CollapsibleSection(
                              title: _text(context, '原始 JSON', 'Raw JSON'),
                              copyValue: _prettyJson(widget.card),
                              initiallyExpanded: false,
                              child: _JsonBlock(value: widget.card),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _convertThisStep(_RunLogStepSnapshot snapshot) async {
    if (_isConvertingStep) return;
    setState(() {
      _isConvertingStep = true;
    });
    showToast(
      _text(context, '正在转换此步...', 'Converting this step...'),
      type: ToastType.info,
    );
    final stepRunId = '${widget.runId}-step-${snapshot.stepNumber}';
    final stepTitle = snapshot.title.isNotEmpty
        ? snapshot.title
        : (snapshot.toolName.isNotEmpty
              ? snapshot.toolName
              : 'Step ${snapshot.stepNumber}');
    try {
      final spec = await RunLogReusableFunctionConverter.convert(
        runId: stepRunId,
        title: stepTitle,
        payload: {
          ...widget.payload,
          'goal': stepTitle,
          'operation_description': stepTitle,
          'source_run_id': widget.runId,
          'source_step_number': snapshot.stepNumber,
        },
        cards: [widget.card],
        useAi: true,
        useEnglish: _localeValue(context, zh: false, en: true),
      );
      if (!mounted) return;
      setState(() {
        _isConvertingStep = false;
      });
      if (spec.warning != null && spec.warning!.trim().isNotEmpty) {
        showToast(spec.warning!, type: ToastType.warning);
      } else {
        showToast(
          _text(context, '此步功能结构已生成', 'Step function spec generated'),
          type: ToastType.success,
        );
      }
      await showModalBottomSheet<void>(
        context: context,
        useRootNavigator: true,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        barrierColor: Colors.black.withValues(alpha: 0.28),
        builder: (_) => _ReusableFunctionSpecSheet(
          spec: spec,
          runId: stepRunId,
          baseUrl: widget.baseUrl,
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isConvertingStep = false;
      });
      showToast(
        '${_text(context, '转换失败', 'Conversion failed')}: $e',
        type: ToastType.error,
      );
    }
  }

  void _copyText(BuildContext context, String text, String successMessage) {
    Clipboard.setData(ClipboardData(text: text));
    showToast(successMessage, type: ToastType.success);
  }
}

class _ReusableFunctionSpecSheet extends StatefulWidget {
  const _ReusableFunctionSpecSheet({
    required this.spec,
    required this.runId,
    this.baseUrl,
  });

  final RunLogReusableFunctionSpec spec;
  final String runId;
  final String? baseUrl;

  @override
  State<_ReusableFunctionSpecSheet> createState() =>
      _ReusableFunctionSpecSheetState();
}

class _ReusableFunctionSpecSheetState
    extends State<_ReusableFunctionSpecSheet> {
  UtgRunLogImportResult? _importResult;
  UtgManualRunResult? _runResult;
  bool _isImporting = false;
  bool _isExecuting = false;
  bool _isScheduling = false;
  String? _apiError;

  RunLogReusableFunctionSpec get spec => widget.spec;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final sheetHeight = MediaQuery.of(context).size.height * 0.55;

    return GestureDetector(
      onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
      behavior: HitTestBehavior.opaque,
      child: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {},
            child: SizedBox(
              height: sheetHeight,
              width: double.infinity,
              child: Container(
                decoration: BoxDecoration(
                  color: isDark ? palette.surfacePrimary : Colors.white,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(
                        alpha: isDark ? 0.35 : 0.14,
                      ),
                      blurRadius: 26,
                      offset: const Offset(0, -10),
                    ),
                  ],
                ),
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderSubtle,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 14, 10, 10),
                      child: Row(
                        children: [
                          Container(
                            width: 30,
                            height: 30,
                            decoration: BoxDecoration(
                              color: _routeColor(context).withValues(
                                alpha: context.isDarkTheme ? 0.18 : 0.12,
                              ),
                              shape: BoxShape.circle,
                            ),
                            child: Icon(
                              Icons.auto_awesome_rounded,
                              size: 16,
                              color: _routeColor(context),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  spec.aiEnhanced
                                      ? _text(
                                          context,
                                          'AI 转换结果',
                                          'AI conversion',
                                        )
                                      : _text(
                                          context,
                                          '本地转换结果',
                                          'Local conversion',
                                        ),
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  spec.name.isEmpty
                                      ? spec.functionId
                                      : spec.name,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 16,
                                    color: palette.textPrimary,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Tooltip(
                            message: _text(
                              context,
                              '复制 Function JSON',
                              'Copy function JSON',
                            ),
                            child: IconButton(
                              icon: const Icon(Icons.data_object_rounded),
                              color: palette.textSecondary,
                              onPressed: () => _copyText(
                                context,
                                spec.prettyJson,
                                _text(
                                  context,
                                  '已复制 Function JSON',
                                  'Function JSON copied',
                                ),
                              ),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close_rounded),
                            color: palette.textSecondary,
                            onPressed: () => Navigator.of(context).maybePop(),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, color: palette.borderSubtle),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: [
                                _SummaryPill(
                                  label: 'ID',
                                  value: spec.functionId.isEmpty
                                      ? _text(context, '未命名', 'Unnamed')
                                      : spec.functionId,
                                ),
                                _SummaryPill(
                                  label: _text(context, '步骤', 'Steps'),
                                  value: spec.stepCount.toString(),
                                ),
                                _SummaryPill(
                                  label: _text(context, '参数', 'Params'),
                                  value: spec.parameterCount.toString(),
                                ),
                                _SummaryPill(
                                  label: _text(context, '转换', 'Mode'),
                                  value: spec.aiEnhanced ? 'AI' : 'Local',
                                ),
                                _SummaryPill(
                                  label: 'API',
                                  value: _registeredFunctionId.isEmpty
                                      ? _text(context, '未注册', 'Draft')
                                      : _text(context, '可执行', 'Executable'),
                                ),
                              ],
                            ),
                            if (spec.warning != null &&
                                spec.warning!.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _WarningBox(text: spec.warning!),
                            ],
                            if (_apiError != null &&
                                _apiError!.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _WarningBox(text: _apiError!),
                            ],
                            const SizedBox(height: 14),
                            Row(
                              children: [
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: Icons.cloud_upload_outlined,
                                    label: _isImporting
                                        ? _text(context, '注册中', 'Registering')
                                        : _registeredFunctionId.isEmpty
                                        ? _text(
                                            context,
                                            '注册为 OOB API',
                                            'Register OOB API',
                                          )
                                        : _text(context, '重新注册', 'Re-register'),
                                    onTap: _isImporting
                                        ? null
                                        : _registerFunction,
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: Icons.play_arrow_rounded,
                                    label: _isExecuting
                                        ? _text(context, '执行中', 'Running')
                                        : _text(
                                            context,
                                            '执行 OOB API',
                                            'Run OOB API',
                                          ),
                                    onTap: _isImporting || _isExecuting
                                        ? null
                                        : _executeRegisteredFunction,
                                  ),
                                ),
                              ],
                            ),
                            if (_registeredFunctionId.isNotEmpty ||
                                _runResult != null) ...[
                              const SizedBox(height: 12),
                              _FunctionApiStatusBox(
                                functionId: _registeredFunctionId,
                                importResult: _importResult,
                                runResult: _runResult,
                                apiCallJson: _apiCallJson,
                              ),
                            ],
                            const SizedBox(height: 10),
                            _SpecActionButton(
                              icon: Icons.event_available_rounded,
                              label: _isScheduling
                                  ? _text(
                                      context,
                                      '打开定时设置中',
                                      'Opening schedule',
                                    )
                                  : _text(
                                      context,
                                      '转为定时任务',
                                      'Schedule this function',
                                    ),
                              onTap:
                                  _isImporting || _isExecuting || _isScheduling
                                  ? null
                                  : _scheduleRegisteredFunction,
                            ),
                            const SizedBox(height: 10),
                            Row(
                              children: [
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: Icons.content_copy_rounded,
                                    label: _text(
                                      context,
                                      '复制 JSON',
                                      'Copy JSON',
                                    ),
                                    onTap: () => _copyText(
                                      context,
                                      spec.prettyJson,
                                      _text(
                                        context,
                                        '已复制 Function JSON',
                                        'Function JSON copied',
                                      ),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: Icons.smart_toy_outlined,
                                    label: _text(
                                      context,
                                      '复制 Agent 提示',
                                      'Copy agent prompt',
                                    ),
                                    onTap: () => _copyText(
                                      context,
                                      spec.agentPrompt,
                                      _text(
                                        context,
                                        '已复制 Agent 提示',
                                        'Agent prompt copied',
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 14),
                            _DetailSection(
                              title: _text(
                                context,
                                'Function JSON',
                                'Function JSON',
                              ),
                              copyValue: spec.prettyJson,
                              child: _JsonText(text: spec.prettyJson),
                            ),
                            const SizedBox(height: 12),
                            _DetailSection(
                              title: _text(
                                context,
                                'Agent 复用提示',
                                'Agent reuse prompt',
                              ),
                              copyValue: spec.agentPrompt,
                              child: _JsonText(text: spec.agentPrompt),
                            ),
                            if (_apiCallJson.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _DetailSection(
                                title: _text(
                                  context,
                                  '真实 API 调用',
                                  'Executable API call',
                                ),
                                copyValue: _apiCallJson,
                                child: _JsonText(text: _apiCallJson),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _registerFunction() async {
    if (_isImporting) return;
    setState(() {
      _isImporting = true;
      _apiError = null;
    });
    try {
      final result = await AssistsMessageService.registerOobReusableFunction(
        functionSpec: spec.json,
      );
      if (!mounted) return;
      final registeredId = _firstNonBlank([
        result.createdFunctionId,
        result.functionId,
        spec.functionId,
      ]);
      setState(() {
        _importResult = UtgRunLogImportResult.fromMap({
          'success': result.success,
          'run_id': widget.runId,
          'function_id': registeredId,
          'created_function_id': registeredId,
          'functions_created': result.alreadyExists ? 0 : 1,
          'asset_kind': result.assetKind,
          'asset_state': result.assetState,
          'oob_function_as_tool_enabled':
              result.rawJson['oob_function_as_tool_enabled'] == true,
        });
        _isImporting = false;
      });
      if (result.success) {
        showToast(
          _text(context, '已注册为 OOB API', 'Registered OOB API'),
          type: ToastType.success,
        );
      } else {
        final message = result.errorMessage?.trim();
        setState(() {
          _apiError = message?.isNotEmpty == true
              ? message
              : _text(context, '注册失败', 'Registration failed');
        });
        showToast(_apiError!, type: ToastType.error);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isImporting = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
    }
  }

  Future<void> _executeRegisteredFunction() async {
    if (_isExecuting || _isImporting) return;
    var functionId = _registeredFunctionId;
    if (functionId.isEmpty) {
      await _registerFunction();
      if (!mounted) return;
      functionId = _registeredFunctionId;
    }
    if (functionId.isEmpty) {
      showToast(
        _text(context, '没有可执行 function_id', 'Missing executable function ID'),
        type: ToastType.warning,
      );
      return;
    }

    setState(() {
      _isExecuting = true;
      _apiError = null;
    });
    try {
      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: functionId,
        arguments: _defaultArguments,
      );
      if (!mounted) return;
      setState(() {
        _runResult = result;
        _isExecuting = false;
        _apiError = result.success ? null : result.errorMessage;
      });
      showToast(
        result.success
            ? _runSuccessMessage(context, result)
            : _runFailureMessage(context, result),
        type: result.success ? ToastType.success : ToastType.error,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isExecuting = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
    }
  }

  Future<void> _scheduleRegisteredFunction() async {
    if (_isScheduling || _isImporting || _isExecuting) return;
    setState(() {
      _isScheduling = true;
      _apiError = null;
    });
    try {
      var functionId = _registeredFunctionId;
      if (functionId.isEmpty) {
        await _registerFunction();
        if (!mounted) return;
        functionId = _registeredFunctionId;
      }
      if (functionId.isEmpty) {
        setState(() {
          _isScheduling = false;
        });
        showToast(
          _text(
            context,
            'Function 注册失败，无法转定时任务',
            'Function registration failed',
          ),
          type: ToastType.error,
        );
        return;
      }

      final nodeId = 'runlog_function';
      final existingTask =
          await ScheduledTaskStorageService.getScheduledTaskBySuggestionId(
            nodeId,
            functionId,
          );
      if (!mounted) return;
      final result = await ScheduleTaskSheet.show(
        context: context,
        taskTitle: spec.name.isEmpty ? functionId : spec.name,
        packageName: _packageNameForSchedule,
        nodeId: nodeId,
        suggestionId: functionId,
        suggestionData: _scheduleSuggestionData(functionId),
        existingTask: existingTask,
      );
      if (!mounted) return;
      setState(() {
        _isScheduling = false;
      });
      if (result == null) {
        return;
      }
      final saved = await ScheduledTaskStorageService.addScheduledTask(result);
      if (!mounted) return;
      if (!saved) {
        showToast(
          _text(context, '定时任务保存失败', 'Failed to save scheduled task'),
          type: ToastType.error,
        );
        return;
      }
      ScheduledTaskSchedulerService.scheduleTask(result);
      showToast(
        _text(context, '已转为定时任务', 'Scheduled task created'),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isScheduling = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
    }
  }

  String get _registeredFunctionId {
    return _firstNonBlank([
      _importResult?.createdFunctionId,
      _firstHitFunctionId,
      _runResult?.functionId,
      spec.functionId,
    ]);
  }

  String get _firstHitFunctionId {
    final ids = _importResult?.hitFunctionIds ?? const <String>[];
    for (final id in ids) {
      final value = id.trim();
      if (value.isNotEmpty) {
        return value;
      }
    }
    return '';
  }

  Map<String, dynamic> get _defaultArguments {
    return _defaultArgumentsForFunctionSpec(spec.json);
  }

  Map<String, dynamic> _scheduleSuggestionData(String functionId) {
    return {
      'targetKind': 'subagent',
      'subagentPrompt': _scheduledFunctionPrompt(functionId),
      'notificationEnabled': true,
      'source': 'run_log_reusable_function',
      'sourceRunId': widget.runId,
      'oobFunctionId': functionId,
      'oobFunctionArguments': _defaultArguments,
    };
  }

  String _scheduledFunctionPrompt(String functionId) {
    final argumentsJson = const JsonEncoder.withIndent(
      '  ',
    ).convert(_defaultArguments);
    if (_localeValue(context, zh: false, en: true)) {
      return [
        'Execute this already registered OOB function now. Do not create, update, or discuss the schedule.',
        '',
        'Function ID: $functionId',
        'Arguments JSON:',
        argumentsJson,
        '',
        'Execution rule: call the OOB function API with the arguments above. The runtime executes executor=omniflow/model_free steps locally without a model call; executor=tool uses step.callable_tool; executor=agent or validation mismatch may re-plan with step.agent_call/fallback prompt.',
        '',
        'Function JSON:',
        spec.prettyJson,
      ].join('\n');
    }
    return [
      '现在执行这个已经注册的 OOB function。不要创建、修改或讨论定时任务。',
      '',
      'Function ID: $functionId',
      'Arguments JSON:',
      argumentsJson,
      '',
      '执行规则：用上面的参数调用 OOB function API。运行时会把 executor=omniflow/model_free 的步骤本地执行，不调用模型；executor=tool 调用 step.callable_tool；executor=agent 或 validation 不匹配时可使用 step.agent_call/fallback prompt 重规划。',
      '',
      'Function JSON:',
      spec.prettyJson,
    ].join('\n');
  }

  String get _packageNameForSchedule {
    final constraints = _asStringKeyMap(spec.json['constraints']);
    final execution = _asStringKeyMap(spec.json['execution']);
    final steps = execution['steps'];
    final stepPackages = <dynamic>[];
    if (steps is List) {
      for (final step in steps) {
        stepPackages.add(_asStringKeyMap(step)['package_name']);
      }
    }
    return _firstNonBlank([constraints['package_name'], ...stepPackages]);
  }

  String get _apiCallJson {
    final functionId = _registeredFunctionId;
    if (functionId.isEmpty) {
      return '';
    }
    return const JsonEncoder.withIndent('  ').convert({
      'api': 'AssistsMessageService.runOobReusableFunction',
      'body': {
        'function_id': functionId,
        'arguments': _defaultArguments,
        'context': {
          'source': 'oob_reusable_function',
          'source_run_id': widget.runId,
        },
      },
    });
  }

  String _runSuccessMessage(BuildContext context, UtgManualRunResult result) {
    final status = result.terminalState['status']?.toString().trim() ?? '';
    final taskId = result.terminalState['taskId']?.toString().trim() ?? '';
    if (taskId.isNotEmpty) {
      return _localeValue(
        context,
        zh: 'API 已开始执行：$taskId',
        en: 'API execution started: $taskId',
      );
    }
    if (status == 'completed') {
      return _text(context, 'API 已本地执行完成', 'API completed locally');
    }
    return _text(context, 'API 已开始执行', 'API execution started');
  }

  String _runFailureMessage(BuildContext context, UtgManualRunResult result) {
    final error = result.errorMessage?.trim();
    if (error != null && error.isNotEmpty) {
      return error;
    }
    return _text(context, 'API 执行失败', 'API execution failed');
  }

  void _copyText(BuildContext context, String text, String successMessage) {
    Clipboard.setData(ClipboardData(text: text));
    showToast(successMessage, type: ToastType.success);
  }
}

class _FunctionApiStatusBox extends StatelessWidget {
  const _FunctionApiStatusBox({
    required this.functionId,
    required this.importResult,
    required this.runResult,
    required this.apiCallJson,
  });

  final String functionId;
  final UtgRunLogImportResult? importResult;
  final UtgManualRunResult? runResult;
  final String apiCallJson;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final lines = <String>[
      if (functionId.isNotEmpty) 'function_id: $functionId',
      if (importResult != null)
        'import: ${importResult!.success ? 'success' : 'failed'}'
            ' · functions=${importResult!.functionsCreated}'
            ' · hits=${importResult!.hitFunctionIds.length}',
      if (runResult != null)
        'run: ${runResult!.success ? 'started' : 'failed'}'
            '${_runStatusSuffix(runResult!)}',
    ];
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: _successColor(
          context,
        ).withValues(alpha: context.isDarkTheme ? 0.14 : 0.09),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: _successColor(context).withValues(alpha: 0.28),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.api_rounded, size: 18, color: _successColor(context)),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              lines.join('\n'),
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                height: 1.35,
                fontFamily: 'monospace',
              ),
            ),
          ),
          if (apiCallJson.trim().isNotEmpty)
            Tooltip(
              message: _text(context, '复制 API 调用', 'Copy API call'),
              child: IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.content_copy_rounded, size: 16),
                color: palette.textSecondary,
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: apiCallJson));
                  showToast(
                    _text(context, '已复制 API 调用', 'API call copied'),
                    type: ToastType.success,
                  );
                },
              ),
            ),
        ],
      ),
    );
  }

  String _runStatusSuffix(UtgManualRunResult result) {
    final parts = <String>[];
    final status = result.terminalState['status']?.toString().trim() ?? '';
    final runner = result.terminalState['runner']?.toString().trim() ?? '';
    final taskId = result.terminalState['taskId']?.toString().trim() ?? '';
    final error = result.errorMessage?.trim() ?? '';
    if (status.isNotEmpty) parts.add('status=$status');
    if (runner.isNotEmpty) parts.add('runner=$runner');
    if (taskId.isNotEmpty) parts.add('task=$taskId');
    if (result.runFilePath.trim().isNotEmpty) parts.add(result.runFilePath);
    if (!result.success && error.isNotEmpty) parts.add(error);
    return parts.isEmpty ? '' : ' · ${parts.join(' · ')}';
  }
}

class _SpecActionButton extends StatelessWidget {
  const _SpecActionButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final enabled = onTap != null;
    return Material(
      color: context.isDarkTheme
          ? palette.surfaceSecondary
          : Colors.grey.shade100,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 16,
                color: enabled ? palette.textPrimary : palette.textTertiary,
              ),
              const SizedBox(width: 7),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: enabled ? palette.textPrimary : palette.textTertiary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _WarningBox extends StatelessWidget {
  const _WarningBox({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final warningColor = _warningColor(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: warningColor.withValues(
          alpha: context.isDarkTheme ? 0.18 : 0.13,
        ),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          color: palette.textSecondary,
          height: 1.35,
        ),
      ),
    );
  }
}

class _PromptHighlightBox extends StatelessWidget {
  const _PromptHighlightBox({required this.text, required this.source});

  final String text;
  final String source;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = _routeColor(context);
    final title = source.trim().isEmpty
        ? _text(context, 'Prompt', 'Prompt')
        : '${_text(context, 'Prompt', 'Prompt')} · $source';
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.13 : 0.07),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.20)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.notes_rounded, size: 15, color: color),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: color,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Tooltip(
                message: _text(context, '复制 Prompt', 'Copy prompt'),
                child: IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.content_copy_rounded, size: 16),
                  color: palette.textSecondary,
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: text));
                    showToast(
                      _text(context, '已复制 Prompt', 'Prompt copied'),
                      type: ToastType.success,
                    );
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          SelectableText(
            text,
            style: TextStyle(
              fontSize: 12,
              color: palette.textPrimary,
              height: 1.35,
            ),
          ),
        ],
      ),
    );
  }
}

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.snapshot});

  final _RunLogStepSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final items = <MapEntry<String, String>>[
      MapEntry(_text(context, '状态', 'Status'), snapshot.statusLabel(context)),
      MapEntry(
        _text(context, '执行方式', 'Execution'),
        _isModelFreeReplayStep(snapshot)
            ? _text(context, '本地重放 / 无模型调用', 'Local replay / no model')
            : _text(context, '需要模型', 'Needs model'),
      ),
      if (snapshot.compileKind.isNotEmpty)
        MapEntry(_text(context, '路由', 'Route'), snapshot.routeLabel(context)),
      if (snapshot.durationMs != null)
        MapEntry(
          _text(context, '耗时', 'Duration'),
          _formatMs(snapshot.durationMs!),
        ),
      if (snapshot.packageName.isNotEmpty)
        MapEntry(_text(context, '应用包名', 'Package'), snapshot.packageName),
    ];
    if (items.isEmpty) {
      return const SizedBox.shrink();
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: items
          .map((entry) => _SummaryPill(label: entry.key, value: entry.value))
          .toList(growable: false),
    );
  }
}

class _SummaryPill extends StatelessWidget {
  const _SummaryPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(10),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(fontSize: 11, color: palette.textSecondary),
          children: [
            TextSpan(text: '$label  '),
            TextSpan(
              text: value,
              style: TextStyle(
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DetailSection extends StatelessWidget {
  const _DetailSection({
    required this.title,
    required this.child,
    this.copyValue,
  });

  final String title;
  final Widget child;
  final String? copyValue;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : Colors.grey.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(
                      fontSize: 13,
                      color: palette.textPrimary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (copyValue != null && copyValue!.trim().isNotEmpty)
                  Tooltip(
                    message: _text(context, '复制', 'Copy'),
                    child: IconButton(
                      visualDensity: VisualDensity.compact,
                      icon: const Icon(Icons.content_copy_rounded, size: 16),
                      color: palette.textSecondary,
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: copyValue!));
                        showToast(
                          _text(context, '已复制', 'Copied'),
                          type: ToastType.success,
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: child,
          ),
        ],
      ),
    );
  }
}

class _CollapsibleSection extends StatefulWidget {
  const _CollapsibleSection({
    required this.title,
    required this.child,
    this.copyValue,
    this.initiallyExpanded = true,
  });

  final String title;
  final Widget child;
  final String? copyValue;
  final bool initiallyExpanded;

  @override
  State<_CollapsibleSection> createState() => _CollapsibleSectionState();
}

class _CollapsibleSectionState extends State<_CollapsibleSection> {
  late bool _expanded;

  @override
  void initState() {
    super.initState();
    _expanded = widget.initiallyExpanded;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: isDark ? palette.surfaceSecondary : Colors.grey.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Material(
            color: Colors.transparent,
            borderRadius: _expanded
                ? const BorderRadius.vertical(top: Radius.circular(12))
                : BorderRadius.circular(12),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 8, 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        widget.title,
                        style: TextStyle(
                          fontSize: 13,
                          color: palette.textPrimary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    if (_expanded &&
                        widget.copyValue != null &&
                        widget.copyValue!.trim().isNotEmpty)
                      Tooltip(
                        message: _text(context, '复制', 'Copy'),
                        child: IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(
                            Icons.content_copy_rounded,
                            size: 16,
                          ),
                          color: palette.textSecondary,
                          onPressed: () {
                            Clipboard.setData(
                              ClipboardData(text: widget.copyValue!),
                            );
                            showToast(
                              _text(context, '已复制', 'Copied'),
                              type: ToastType.success,
                            );
                          },
                        ),
                      ),
                    Icon(
                      _expanded
                          ? Icons.keyboard_arrow_up_rounded
                          : Icons.keyboard_arrow_down_rounded,
                      size: 20,
                      color: palette.textSecondary,
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (_expanded) ...[
            Divider(height: 1, color: palette.borderSubtle),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              child: widget.child,
            ),
          ],
        ],
      ),
    );
  }
}

class _JsonBlock extends StatelessWidget {
  const _JsonBlock({required this.value});

  final dynamic value;

  @override
  Widget build(BuildContext context) {
    return _JsonText(text: _prettyJson(value));
  }
}

class _JsonText extends StatelessWidget {
  const _JsonText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? Colors.black.withValues(alpha: 0.22)
            : Colors.white,
        borderRadius: BorderRadius.circular(8),
      ),
      child: SelectableText(
        text.trim().isEmpty ? '{}' : text,
        style: TextStyle(
          fontSize: 11,
          height: 1.35,
          color: palette.textPrimary,
          fontFamily: 'monospace',
        ),
      ),
    );
  }
}

class _RouteBadge extends StatelessWidget {
  const _RouteBadge({required this.compileKind, required this.l10n});

  final String compileKind;
  final dynamic l10n;

  @override
  Widget build(BuildContext context) {
    final isHit = compileKind == 'hit';
    final isMiss = compileKind == 'miss';
    if (!isHit && !isMiss) return const SizedBox.shrink();

    final label = isHit
        ? l10n.executionRouteMemorized
        : l10n.executionRouteAiPlanning;
    final color = isHit ? _successColor(context) : _routeColor(context);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
          height: 1,
        ),
      ),
    );
  }
}

class _ModelFreeBadge extends StatelessWidget {
  const _ModelFreeBadge();

  @override
  Widget build(BuildContext context) {
    final color = _modelFreeColor(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.18 : 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.26)),
      ),
      child: Text(
        _text(context, '脚本', 'Script'),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: color,
          height: 1,
        ),
      ),
    );
  }
}

class _RunLogStepSnapshot {
  const _RunLogStepSnapshot({
    required this.card,
    required this.header,
    required this.toolCall,
    required this.params,
    required this.result,
    required this.compileResult,
    required this.before,
    required this.after,
    required this.stepNumber,
    required this.title,
    required this.toolName,
    required this.toolCallId,
    required this.compileKind,
    required this.success,
    required this.durationMs,
    required this.packageName,
    required this.prompt,
    required this.promptSource,
  });

  final Map<String, dynamic> card;
  final Map<String, dynamic> header;
  final Map<String, dynamic> toolCall;
  final dynamic params;
  final dynamic result;
  final dynamic compileResult;
  final Map<String, dynamic> before;
  final Map<String, dynamic> after;
  final int stepNumber;
  final String title;
  final String toolName;
  final String toolCallId;
  final String compileKind;
  final bool? success;
  final int? durationMs;
  final String packageName;
  final String prompt;
  final String promptSource;

  factory _RunLogStepSnapshot.fromCard(
    Map<String, dynamic> card, {
    required int fallbackIndex,
  }) {
    final header = _asStringKeyMap(card['header']);
    final before = _asStringKeyMap(card['before']);
    final after = _asStringKeyMap(card['after']);
    final toolCall = _extractToolCall(card);
    final function = _asStringKeyMap(toolCall['function']);
    final params = _extractParams(card, toolCall, function);
    final compileResult = _firstPresentValue(card, const [
      'compile_result',
      'compileResult',
      'route_result',
      'routeResult',
    ]);
    final result = _extractResult(card);
    final promptHit = _extractPromptHit([
      _PromptSearchRoot('params', params),
      _PromptSearchRoot('tool_call', toolCall),
      _PromptSearchRoot('function', function),
      _PromptSearchRoot('card', card),
      _PromptSearchRoot('result', result),
    ]);
    final headerStepIndex = _asInt(
      _firstPresentValue(header, const ['step_index', 'stepIndex', 'index']),
    );
    final cardStepIndex = _asInt(
      _firstPresentValue(card, const ['step_index', 'stepIndex', 'index']),
    );
    final stepNumber =
        ((headerStepIndex ?? cardStepIndex) ?? fallbackIndex) + 1;
    final toolName = _firstNonBlank([
      toolCall['name'],
      toolCall['tool_name'],
      toolCall['toolName'],
      function['name'],
      card['tool_name'],
      card['toolName'],
      card['action_type'],
      card['actionType'],
      header['tool_name'],
      header['toolName'],
    ]);
    final title = _firstNonBlank([
      header['title'],
      card['title'],
      card['summary'],
      card['operation_description'],
      card['operationDescription'],
      toolName,
    ]);
    final compileKind = _firstNonBlank([
      header['compile_kind'],
      header['compileKind'],
      card['compile_kind'],
      card['compileKind'],
      card['selection_source'] == 'vlm' ? 'miss' : null,
    ]);
    final success =
        _asBool(_firstPresentValue(header, const ['success'])) ??
        _asBool(_firstPresentValue(card, const ['success']));
    final durationMs = _asInt(
      _firstPresentValue(header, const ['duration_ms', 'durationMs']) ??
          _firstPresentValue(card, const ['duration_ms', 'durationMs']),
    );
    final paramsMap = _asStringKeyMap(params);
    final packageName = _firstNonBlank([
      before['package_name'],
      before['packageName'],
      after['package_name'],
      after['packageName'],
      paramsMap['package_name'],
      paramsMap['packageName'],
      card['package_name'],
      card['packageName'],
    ]);
    final toolCallId = _firstNonBlank([
      toolCall['id'],
      toolCall['tool_call_id'],
      toolCall['toolCallId'],
      card['tool_call_id'],
      card['toolCallId'],
    ]);

    return _RunLogStepSnapshot(
      card: card,
      header: header,
      toolCall: toolCall,
      params: params,
      result: result,
      compileResult: compileResult,
      before: before,
      after: after,
      stepNumber: stepNumber,
      title: title,
      toolName: toolName,
      toolCallId: toolCallId,
      compileKind: compileKind,
      success: success,
      durationMs: durationMs,
      packageName: packageName,
      prompt: promptHit.text,
      promptSource: promptHit.source,
    );
  }

  String get previewText {
    final parts = <String>[];
    final paramsMap = _asStringKeyMap(params);
    final resultMap = _asStringKeyMap(result);
    if (prompt.isNotEmpty) {
      parts.add('Prompt: ${_compactPreview(prompt, maxLength: 180)}');
    }
    final target = _firstNonBlank([
      paramsMap['target_description'],
      paramsMap['targetDescription'],
      paramsMap['label'],
      paramsMap['text'],
      paramsMap['content'],
      paramsMap['query'],
      paramsMap['url'],
      resultMap['summary'],
      resultMap['message'],
      resultMap['error_message'],
      resultMap['errorMessage'],
    ]);
    if (target.isNotEmpty) {
      parts.add(target);
    }
    final x = paramsMap['x'];
    final y = paramsMap['y'];
    if (x != null && y != null) {
      parts.add('($x, $y)');
    }
    final direction = _firstNonBlank([paramsMap['direction']]);
    if (direction.isNotEmpty) {
      parts.add(direction);
    }
    return parts.join(' · ');
  }

  String get toolCallForCopy {
    if (toolCall.isNotEmpty) {
      return _prettyJson(toolCall);
    }
    return toolName;
  }

  String statusLabel(BuildContext context) {
    if (success == null) {
      return _text(context, '未知', 'Unknown');
    }
    return success!
        ? _text(context, '成功', 'Success')
        : _text(context, '失败', 'Failed');
  }

  String routeLabel(BuildContext context) {
    if (compileKind == 'hit') {
      return context.l10n.executionRouteMemorized;
    }
    if (compileKind == 'miss') {
      return context.l10n.executionRouteAiPlanning;
    }
    return compileKind;
  }

  String toTranscript() {
    final lines = <String>[
      '### Step $stepNumber',
      if (title.isNotEmpty) 'Title: $title',
      if (toolName.isNotEmpty) 'Tool: $toolName',
      if (toolCallId.isNotEmpty) 'Tool Call ID: $toolCallId',
      if (compileKind.isNotEmpty) 'Route: $compileKind',
      if (success != null) 'Success: $success',
      if (durationMs != null) 'Duration: ${_formatMs(durationMs!)}',
      if (packageName.isNotEmpty) 'Package: $packageName',
      if (prompt.isNotEmpty) 'Prompt Source: $promptSource',
    ];

    if (prompt.isNotEmpty) {
      _appendTranscriptSection(lines, 'Prompt', prompt);
    }
    _appendTranscriptSection(lines, 'Tool Call', toolCall);
    _appendTranscriptSection(lines, 'Arguments', params);
    _appendTranscriptSection(lines, 'Result', result);
    _appendTranscriptSection(lines, 'Route Result', compileResult);
    if (before.isNotEmpty || after.isNotEmpty) {
      _appendTranscriptSection(lines, 'Before / After', {
        if (before.isNotEmpty) 'before': before,
        if (after.isNotEmpty) 'after': after,
      });
    }
    _appendTranscriptSection(lines, 'Raw JSON', card);
    return lines.join('\n').trimRight();
  }
}

Map<String, dynamic> _extractToolCall(Map<String, dynamic> card) {
  final explicit = _firstMap(card, const [
    'tool_call',
    'toolCall',
    'action',
    'call',
  ]);
  if (explicit.isNotEmpty) {
    return explicit;
  }
  final toolName = _firstNonBlank([
    card['tool_name'],
    card['toolName'],
    card['action_type'],
    card['actionType'],
  ]);
  if (toolName.isEmpty) {
    return const {};
  }
  return <String, dynamic>{
    'name': toolName,
    if (card.containsKey('params')) 'params': card['params'],
    if (card.containsKey('arguments')) 'arguments': card['arguments'],
  };
}

dynamic _extractParams(
  Map<String, dynamic> card,
  Map<String, dynamic> toolCall,
  Map<String, dynamic> function,
) {
  final value = _firstPresent([
    toolCall['params'],
    toolCall['arguments'],
    toolCall['args'],
    function['arguments'],
    card['params'],
    card['arguments'],
    card['args'],
  ]);
  return _decodeJsonIfNeeded(value);
}

dynamic _extractResult(Map<String, dynamic> card) {
  final value = _firstPresent([
    card['result'],
    card['tool_result'],
    card['toolResult'],
    card['execution_result'],
    card['executionResult'],
    card['output'],
    card['error'],
    card['error_message'],
    card['errorMessage'],
  ]);
  return _decodeJsonIfNeeded(value);
}

class _PromptHit {
  const _PromptHit(this.text, this.source);

  final String text;
  final String source;
}

class _PromptSearchRoot {
  const _PromptSearchRoot(this.name, this.value);

  final String name;
  final dynamic value;
}

const Set<String> _promptKeyNames = {
  'prompt',
  'subagentprompt',
  'agentprompt',
  'augmentprompt',
  'augumentprompt',
  'systemprompt',
  'userprompt',
  'instruction',
  'instructions',
  'query',
  'question',
  'message',
  'usermessage',
  'input',
  'task',
  'goal',
  'request',
};

_PromptHit _extractPromptHit(List<_PromptSearchRoot> roots) {
  for (final root in roots) {
    final hit = _findPromptInValue(
      root.value,
      path: root.name,
      visited: <Object>{},
    );
    if (hit.text.trim().isNotEmpty) {
      return hit;
    }
  }
  return const _PromptHit('', '');
}

_PromptHit _findPromptInValue(
  dynamic raw, {
  required String path,
  required Set<Object> visited,
}) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is Map) {
    if (!visited.add(value)) {
      return const _PromptHit('', '');
    }
    final map = value.map((key, item) => MapEntry(key.toString(), item));
    for (final entry in map.entries) {
      final key = entry.key.trim();
      final normalizedKey = _normalizePromptKey(key);
      if (_promptKeyNames.contains(normalizedKey)) {
        final text = _promptTextFromValue(entry.value);
        if (text.isNotEmpty) {
          return _PromptHit(text, '$path.$key');
        }
      }
    }
    for (final entry in map.entries) {
      final hit = _findPromptInValue(
        entry.value,
        path: '$path.${entry.key}',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
    }
  } else if (value is Iterable) {
    var index = 0;
    for (final item in value) {
      final hit = _findPromptInValue(
        item,
        path: '$path[$index]',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
      index++;
    }
  }
  return const _PromptHit('', '');
}

String _promptTextFromValue(dynamic raw) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is String) {
    return value.trim();
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  if (value is Map) {
    return _firstNonBlank([
      value['text'],
      value['content'],
      value['message'],
      value['prompt'],
      value['value'],
    ]);
  }
  return '';
}

String _normalizePromptKey(String key) {
  return key.replaceAll(RegExp(r'[^A-Za-z0-9]+'), '').toLowerCase().trim();
}

void _appendTranscriptSection(List<String> lines, String title, dynamic value) {
  if (_isEmptyJsonValue(value)) {
    return;
  }
  lines
    ..add('')
    ..add('$title:')
    ..add(_prettyJson(value));
}

String _formatMs(int ms) {
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(1)}s';
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}

T _localeValue<T>(BuildContext context, {required T zh, required T en}) {
  return AppTextLocalizer.chooseValue(
    zh: zh,
    en: en,
    locale: Localizations.maybeLocaleOf(context),
  );
}

Map<String, dynamic> _defaultArgumentsForFunctionSpec(
  Map<String, dynamic> functionSpec,
) {
  final rawParameters = functionSpec['parameters'];
  if (rawParameters is! List) {
    return const {};
  }
  final arguments = <String, dynamic>{};
  for (final item in rawParameters) {
    if (item is! Map) continue;
    final name = (item['name'] ?? '').toString().trim();
    if (name.isEmpty) continue;
    final defaultValue = item['default'];
    if (defaultValue == null) continue;
    arguments[name] = defaultValue;
  }
  return arguments;
}

String _runLogReplaySuccessMessage(
  BuildContext context,
  UtgManualRunResult result,
) {
  final status = result.terminalState['status']?.toString().trim() ?? '';
  final taskId = result.terminalState['taskId']?.toString().trim() ?? '';
  if (taskId.isNotEmpty) {
    return _localeValue(
      context,
      zh: 'RunLog 已交给 Agent 继续执行：$taskId',
      en: 'RunLog handed off to Agent: $taskId',
    );
  }
  if (status == 'completed') {
    return _text(context, 'RunLog 已本地执行完成', 'RunLog completed locally');
  }
  return _text(context, 'RunLog 已开始执行', 'RunLog execution started');
}

String _runLogReplayFailureMessage(
  BuildContext context,
  UtgManualRunResult result,
) {
  final error = result.errorMessage?.trim();
  if (error != null && error.isNotEmpty) {
    return error;
  }
  return _text(context, 'RunLog 执行失败', 'RunLog execution failed');
}

Color _successColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF63D98A)
      : const Color(0xFF2F8F4E);
}

Color _errorColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF7A7A)
      : const Color(0xFFDC2626);
}

Color _routeColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF7AB7FF)
      : const Color(0xFF3B82F6);
}

Color _modelFreeColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF4DD6C9)
      : const Color(0xFF0F9F8F);
}

Color _warningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFFFC04D);
}

bool _isModelFreeReplayStep(_RunLogStepSnapshot snapshot) {
  final toolName = snapshot.toolName.trim().toLowerCase();
  if (toolName.isEmpty || toolName == 'unknown_tool') {
    return false;
  }
  if (toolName.contains('agent') ||
      toolName.contains('llm') ||
      toolName.contains('vlm')) {
    return false;
  }
  const modelFreeTools = {
    'click',
    'long_press',
    'scroll',
    'type',
    'open_app',
    'press_home',
    'press_back',
    'hot_key',
    'wait',
  };
  return modelFreeTools.contains(toolName);
}

List<Map<String, dynamic>> _extractTimelineCards(Map<String, dynamic> payload) {
  return _findTimelineCards(payload, <Object>{});
}

List<Map<String, dynamic>> _findTimelineCards(
  dynamic value,
  Set<Object> visited,
) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is List) {
    return _cardsFromList(decoded);
  }
  if (decoded is! Map) {
    return const <Map<String, dynamic>>[];
  }
  if (!visited.add(decoded)) {
    return const <Map<String, dynamic>>[];
  }

  final map = decoded.map((key, item) => MapEntry(key.toString(), item));
  for (final key in const <String>[
    'cards',
    'steps',
    'timeline_cards',
    'timelineCards',
    'timeline_steps',
    'timelineSteps',
    'run_steps',
    'runSteps',
  ]) {
    final cards = _cardsFromValue(map[key]);
    if (cards.isNotEmpty) {
      return cards;
    }
  }

  for (final key in const <String>[
    'timeline',
    'timeline_payload',
    'timelinePayload',
    'run_log',
    'runLog',
    'run',
    'detail',
    'data',
    'payload',
    'result',
  ]) {
    final cards = _findTimelineCards(map[key], visited);
    if (cards.isNotEmpty) {
      return cards;
    }
  }

  return const <Map<String, dynamic>>[];
}

List<Map<String, dynamic>> _cardsFromValue(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is! List) {
    return const <Map<String, dynamic>>[];
  }
  return _cardsFromList(decoded);
}

List<Map<String, dynamic>> _cardsFromList(List<dynamic> items) {
  final cards = <Map<String, dynamic>>[];
  for (var index = 0; index < items.length; index++) {
    final card = _asStringKeyMap(items[index]);
    if (card.isEmpty) {
      continue;
    }
    cards.add(_normalizeTimelineCard(card, index));
  }
  return cards;
}

Map<String, dynamic> _normalizeTimelineCard(
  Map<String, dynamic> card,
  int fallbackIndex,
) {
  final normalized = Map<String, dynamic>.from(card);
  normalized.putIfAbsent('step_index', () => fallbackIndex);

  final toolCall = _extractToolCall(normalized);
  final function = _asStringKeyMap(toolCall['function']);
  final toolName = _firstNonBlank([
    toolCall['name'],
    toolCall['tool_name'],
    toolCall['toolName'],
    function['name'],
    normalized['tool_name'],
    normalized['toolName'],
    normalized['action_type'],
    normalized['actionType'],
  ]);
  final title = _firstNonBlank([
    normalized['title'],
    normalized['summary'],
    normalized['operation_description'],
    normalized['operationDescription'],
    toolName,
  ]);

  if (_asStringKeyMap(normalized['header']).isEmpty) {
    normalized['header'] = <String, dynamic>{
      'step_index': fallbackIndex,
      if (title.isNotEmpty) 'title': title,
      if (toolName.isNotEmpty) 'tool_name': toolName,
      if (normalized.containsKey('status')) 'status': normalized['status'],
      if (normalized.containsKey('success')) 'success': normalized['success'],
      if (normalized.containsKey('duration_ms'))
        'duration_ms': normalized['duration_ms'],
      if (normalized.containsKey('durationMs'))
        'duration_ms': normalized['durationMs'],
    };
  }

  if (toolCall.isEmpty && toolName.isNotEmpty) {
    final args = _firstPresent([
      normalized['arguments'],
      normalized['params'],
      normalized['args'],
    ]);
    normalized['tool_call'] = <String, dynamic>{
      'id': _firstNonBlank([
        normalized['tool_call_id'],
        normalized['toolCallId'],
        normalized['card_id'],
        normalized['cardId'],
      ]),
      'name': toolName,
      if (args != null) 'arguments': args,
    };
  }

  return normalized;
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is! Map) {
    return const <String, dynamic>{};
  }
  return decoded.map((key, item) => MapEntry(key.toString(), item));
}

Map<String, dynamic> _firstMap(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final map = _asStringKeyMap(source[key]);
    if (map.isNotEmpty) {
      return map;
    }
  }
  return const <String, dynamic>{};
}

dynamic _firstPresentValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    if (source.containsKey(key) && source[key] != null) {
      return source[key];
    }
  }
  return null;
}

dynamic _firstPresent(List<dynamic> values) {
  for (final value in values) {
    if (value == null) {
      continue;
    }
    if (value is String && value.trim().isEmpty) {
      continue;
    }
    return value;
  }
  return null;
}

String _firstNonBlank(List<dynamic> values) {
  return AgentToolCardPolicy.firstNonBlank(values);
}

String _compactPreview(String value, {int maxLength = 160}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  if (maxLength <= 1) {
    return normalized.substring(0, maxLength);
  }
  return '${normalized.substring(0, maxLength - 1).trimRight()}…';
}

bool? _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') {
    return true;
  }
  if (text == 'false') {
    return false;
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString().trim() ?? '');
}

dynamic _decodeJsonIfNeeded(dynamic value) {
  if (value is! String) {
    return value;
  }
  final trimmed = value.trim();
  if (trimmed.isEmpty) {
    return value;
  }
  final startsLikeJson = trimmed.startsWith('{') || trimmed.startsWith('[');
  if (!startsLikeJson) {
    return value;
  }
  try {
    return jsonDecode(trimmed);
  } catch (_) {
    return value;
  }
}

bool _isEmptyJsonValue(dynamic value) {
  if (value == null) {
    return true;
  }
  if (value is String) {
    return value.trim().isEmpty;
  }
  if (value is Map || value is Iterable) {
    return value.isEmpty;
  }
  return false;
}

String _prettyJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(_jsonSafe(value));
  } catch (_) {
    return value?.toString() ?? '';
  }
}

dynamic _jsonSafe(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded == null ||
      decoded is String ||
      decoded is num ||
      decoded is bool) {
    return decoded;
  }
  if (decoded is Map) {
    return decoded.map(
      (key, item) => MapEntry(key.toString(), _jsonSafe(item)),
    );
  }
  if (decoded is Iterable) {
    return decoded.map(_jsonSafe).toList(growable: false);
  }
  return decoded.toString();
}
