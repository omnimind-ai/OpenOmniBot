import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/features/task/run_log/run_log_reusable_function_converter.dart';
import 'package:ui/features/task/run_log/run_log_replay_policy.dart';
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
  String title = '',
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
  String title = '',
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
        final error = _runLogPayloadError(context, payload);
        _error = error;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = context.l10n.omniflowAssetRunLogNotReady;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final stepCount = _cards.length;
    final subtitleParts = <String>[
      if (_payload.isNotEmpty) _runLogStatusInfo(context, _payload).label,
      if (stepCount > 0) l10n.runLogTimelineStepCount(stepCount),
    ].where((item) => item.trim().isNotEmpty).toList(growable: false);
    final subtitle = subtitleParts.isNotEmpty
        ? subtitleParts.join(' · ')
        : null;
    final title = l10n.runLogTimelineTitle;
    final convertEligibility = _runLogConvertEligibility(
      context,
      _payload,
      _cards,
    );
    final List<Widget> actions = <Widget>[
      Tooltip(
        message: l10n.omniflowAssetReplay,
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
        message: convertEligibility.canConvert
            ? _text(context, '保存为复用指令', 'Save reusable command')
            : convertEligibility.message,
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
              : const Icon(Icons.cloud_upload_outlined),
          color: palette.textPrimary,
          onPressed:
              !convertEligibility.canConvert ||
                  _isConvertingFunction ||
                  _isReplayingRunLog
              ? null
              : _registerCurrentRunLog,
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
            _RunLogTimelineSheetHeader(
              title: title,
              subtitle: subtitle,
              actions: actions,
            ),
            Expanded(child: _buildBody(context)),
          ],
        ),
      );
    }

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        titleWidget: _RunLogTimelineHeaderTitle(
          title: title,
          subtitle: subtitle,
        ),
        height: 52,
        primary: true,
        actions: actions,
      ),
      body: _buildBody(context),
    );
  }

  Widget _buildBody(BuildContext context) {
    final l10n = context.l10n;
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _RunLogTimelineEmptyNotice(
        icon: Icons.route_rounded,
        title: l10n.runLogTimelineLoadFailed,
        message: _error!,
      );
    }
    if (_cards.isEmpty) {
      return ListView(
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
        children: [
          _RunLogOverviewCard(payload: _payload, stepCount: 0),
          const SizedBox(height: 14),
          _RunLogTimelineEmptyNotice(
            icon: Icons.check_circle_outline_rounded,
            title: l10n.runLogTimelineEmpty,
            message: _runLogEmptyMessage(context, _payload),
          ),
        ],
      );
    }
    final tokenSummary = _RunLogTokenUsageAggregate.fromPayload(_payload);
    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
      children: [
        _RunLogOverviewCard(payload: _payload, stepCount: _cards.length),
        const SizedBox(height: 14),
        _RunLogOfflineFlowCard(
          canRegister: _runLogConvertEligibility(
            context,
            _payload,
            _cards,
          ).canConvert,
          isRegistering: _isConvertingFunction,
          isReplaying: _isReplayingRunLog,
          onReplay:
              _cards.isEmpty || _isConvertingFunction || _isReplayingRunLog
              ? null
              : _executeCurrentRunLog,
          onRegister:
              _runLogConvertEligibility(context, _payload, _cards).canConvert &&
                  !_isConvertingFunction &&
                  !_isReplayingRunLog
              ? _registerCurrentRunLog
              : null,
        ),
        const SizedBox(height: 14),
        if (tokenSummary.hasUsage) ...[
          _RunLogTokenSummaryCard(summary: tokenSummary),
          const SizedBox(height: 14),
        ],
        for (var index = 0; index < _cards.length; index++)
          _StepCard(
            card: _cards[index],
            fallbackIndex: index,
            isLast: index == _cards.length - 1,
            onTap: () => _showStepDetail(_cards[index], index),
          ),
      ],
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

  Future<void> _registerCurrentRunLog() async {
    if (_cards.isEmpty || _isConvertingFunction) {
      return;
    }
    final convertEligibility = _runLogConvertEligibility(
      context,
      _payload,
      _cards,
    );
    if (!convertEligibility.canConvert) {
      showToast(convertEligibility.message, type: ToastType.warning);
      return;
    }
    setState(() {
      _isConvertingFunction = true;
    });
    showToast(
      _text(context, '正在注册 RunLog...', 'Registering RunLog...'),
      type: ToastType.info,
    );
    final registrationFailedText = _text(
      context,
      '注册失败',
      'Registration failed',
    );
    final useEnglish = _localeValue(context, zh: false, en: true);
    try {
      final result =
          await AssistsMessageService.convertInternalRunLogToOobFunction(
            runId: widget.runId,
            register: true,
          );
      final functionId = _firstNonBlank([
        result['created_function_id'],
        result['function_id'],
      ]);
      if (result['success'] != true || functionId.isEmpty) {
        final error = result['error_message']?.toString().trim();
        throw Exception(
          error?.isNotEmpty == true ? error : registrationFailedText,
        );
      }
      final functionSpec = _asStringKeyMap(result['function_spec']);
      final specJson = functionSpec.isNotEmpty
          ? functionSpec
          : <String, dynamic>{
              'schema_version': 'oob.reusable_function.v1',
              'function_id': functionId,
              'name': functionId,
              'description': _firstNonBlank([
                _payload['goal'],
                _payload['operation_description'],
                widget.title,
              ]),
              'parameters': const <dynamic>[],
              'execution': const <String, dynamic>{
                'kind': 'tool_sequence',
                'steps': <dynamic>[],
              },
            };
      final agentPrompt =
          await RunLogReusableFunctionConverter.buildAgentPromptAsync(
            specJson,
            useEnglish: useEnglish,
          );
      final spec = RunLogReusableFunctionSpec(
        json: specJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
      );
      final importResult = UtgRunLogImportResult.fromMap(result);
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
      });
      showToast(
        _text(context, 'RunLog 已保存为复用指令', 'RunLog saved as reusable command'),
        type: ToastType.success,
      );
      await _showReusableFunctionSheet(spec, initialImportResult: importResult);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
      });
      final message = _text(context, '注册失败', 'Registration failed');
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
    showToast(context.l10n.omniflowAssetReplayProgress, type: ToastType.info);
    final executionFailedText = context.l10n.omniflowAssetReplayFailed;
    final conversionFailedText = _text(
      context,
      '执行记录生成失败',
      'Execution record generation failed',
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
      await showFunctionRunResultSheet(
        context,
        result: result,
        title: _text(context, 'RunLog 重放结果', 'RunLog replay result'),
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
    final l10n = context.l10n;
    final transcriptTitle = widget.title.trim().isEmpty
        ? l10n.runLogTimelineTitle
        : widget.title.trim();
    final lines = <String>[
      '# $transcriptTitle',
      '',
      'Run ID: ${widget.runId}',
      l10n.runLogTimelineStepCount(_cards.length),
    ];

    final goal = _firstNonBlank([
      _payload['goal'],
      _payload['task_goal'],
      _payload['operation_description'],
      _payload['operationDescription'],
    ]);
    if (goal.isNotEmpty) {
      lines.add('${l10n.omniflowAssetGoal}: $goal');
    }

    lines.add('');
    lines.add('## ${_text(context, '工具调用历史', 'Tool call history')}');
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
      lines.add('## ${_text(context, '原始时间线数据', 'Raw timeline payload')}');
      lines.add(_prettyUserJson(_payload));
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

  Future<void> _showReusableFunctionSheet(
    RunLogReusableFunctionSpec spec, {
    UtgRunLogImportResult? initialImportResult,
  }) {
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
        initialImportResult: initialImportResult,
      ),
    );
  }
}

class _RunLogTimelineEmptyNotice extends StatelessWidget {
  const _RunLogTimelineEmptyNotice({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 28, color: palette.textTertiary),
            const SizedBox(height: 12),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 15,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RunLogOverviewCard extends StatelessWidget {
  const _RunLogOverviewCard({required this.payload, required this.stepCount});

  final Map<String, dynamic> payload;
  final int stepCount;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final status = _runLogStatusInfo(context, payload);
    final goal = _firstNonBlank([
      payload['goal'],
      payload['task_goal'],
      payload['operation_description'],
      payload['operationDescription'],
    ]);
    final error = _firstNonBlank([
      payload['error_message'],
      payload['errorMessage'],
      status.kind == _RunLogStatusKind.failed ? payload['done_reason'] : null,
      status.kind == _RunLogStatusKind.failed ? payload['doneReason'] : null,
    ]);
    final tokenSummary = _RunLogTokenUsageAggregate.fromPayload(payload);
    final durationMs = _asInt(payload['duration_ms'] ?? payload['durationMs']);
    final started = _runLogTimeText(context, payload, start: true);
    final finished = _runLogTimeText(context, payload, start: false);
    final chips = <MapEntry<String, String>>[
      MapEntry(_text(context, '步骤', 'Steps'), stepCount.toString()),
      if (durationMs != null)
        MapEntry(_text(context, '耗时', 'Duration'), _formatMs(durationMs)),
      if (tokenSummary.totalTokens != null)
        MapEntry(
          _text(context, 'VLM Token', 'VLM tokens'),
          _formatTokens(tokenSummary.totalTokens!),
        ),
      if (started.isNotEmpty)
        MapEntry(_text(context, '开始', 'Started'), started),
      if (finished.isNotEmpty)
        MapEntry(_text(context, '结束', 'Finished'), finished),
    ];

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          status.color.withValues(alpha: isDark ? 0.15 : 0.07),
          isDark ? palette.surfaceSecondary : Colors.white,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: status.color.withValues(alpha: isDark ? 0.34 : 0.20),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: status.color.withValues(alpha: isDark ? 0.20 : 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(status.icon, color: status.color, size: 17),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      status.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0,
                      ),
                    ),
                    if (goal.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text(
                        goal,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.32,
                          letterSpacing: 0,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          if (chips.isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: chips
                  .map(
                    (entry) =>
                        _SummaryPill(label: entry.key, value: entry.value),
                  )
                  .toList(growable: false),
            ),
          ],
          if (error.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              error,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: status.kind == _RunLogStatusKind.failed
                    ? _errorColor(context)
                    : palette.textSecondary,
                fontSize: 12,
                height: 1.32,
                fontWeight: status.kind == _RunLogStatusKind.failed
                    ? FontWeight.w600
                    : FontWeight.w400,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _RunLogTokenSummaryCard extends StatelessWidget {
  const _RunLogTokenSummaryCard({required this.summary});

  final _RunLogTokenUsageAggregate summary;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final accent = const Color(0xFF2563EB);
    final items = <MapEntry<String, String>>[
      if (summary.totalTokens != null)
        MapEntry(
          _text(context, '总计', 'Total'),
          _formatTokens(summary.totalTokens!),
        ),
      if (summary.callCount != null)
        MapEntry(
          _text(context, 'VLM 调用', 'VLM calls'),
          summary.callCount.toString(),
        ),
      if (summary.stepCount != null && summary.stepCount != summary.callCount)
        MapEntry(_text(context, '步骤', 'Steps'), summary.stepCount.toString()),
      if (summary.promptTokens != null)
        MapEntry(
          _text(context, '输入', 'Prompt'),
          _formatTokens(summary.promptTokens!),
        ),
      if (summary.completionTokens != null)
        MapEntry(
          _text(context, '输出', 'Completion'),
          _formatTokens(summary.completionTokens!),
        ),
      if (summary.cachedTokens != null && summary.cachedTokens! > 0)
        MapEntry(
          _text(context, '缓存', 'Cached'),
          _formatTokens(summary.cachedTokens!),
        ),
    ];
    if (items.isEmpty) {
      return const SizedBox.shrink();
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          accent.withValues(alpha: isDark ? 0.16 : 0.08),
          isDark ? palette.surfaceSecondary : Colors.white,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: accent.withValues(alpha: isDark ? 0.32 : 0.18),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: accent.withValues(alpha: isDark ? 0.22 : 0.12),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(Icons.data_usage_rounded, color: accent, size: 16),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _text(context, '在线 VLM 成本', 'Online VLM cost'),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0,
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: items
                      .map(
                        (entry) =>
                            _SummaryPill(label: entry.key, value: entry.value),
                      )
                      .toList(growable: false),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _RunLogOfflineFlowCard extends StatelessWidget {
  const _RunLogOfflineFlowCard({
    required this.canRegister,
    required this.isRegistering,
    required this.isReplaying,
    required this.onReplay,
    required this.onRegister,
  });

  final bool canRegister;
  final bool isRegistering;
  final bool isReplaying;
  final VoidCallback? onReplay;
  final VoidCallback? onRegister;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final accent = _modelFreeColor(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          accent.withValues(alpha: isDark ? 0.16 : 0.07),
          isDark ? palette.surfaceSecondary : Colors.white,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: accent.withValues(alpha: isDark ? 0.36 : 0.18),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: accent.withValues(alpha: isDark ? 0.22 : 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(Icons.route_rounded, color: accent, size: 16),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _text(context, '离线复用流程', 'Offline reuse flow'),
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _text(
                        context,
                        '这条 RunLog 可以直接重放，也可以保存成复用指令后继续使用。',
                        'Replay this RunLog directly, or save it as a reusable command.',
                      ),
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                        height: 1.35,
                        letterSpacing: 0,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _FlowStepPill(
                icon: Icons.history_toggle_off_rounded,
                label: _text(context, 'RunLog 已收集', 'RunLog collected'),
                active: true,
              ),
              _FlowStepPill(
                icon: Icons.inventory_2_outlined,
                label: canRegister
                    ? _text(context, '可保存为复用指令', 'Reusable command ready')
                    : _text(context, '等待成功结果', 'Awaiting success'),
                active: canRegister,
              ),
              _FlowStepPill(
                icon: Icons.play_arrow_rounded,
                label: _text(context, '本地执行', 'Local execution'),
                active: onReplay != null,
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _InlineActionButton(
                  icon: Icons.play_arrow_rounded,
                  label: isReplaying
                      ? _text(context, '重放中', 'Replaying')
                      : _text(context, '重放 RunLog', 'Replay RunLog'),
                  onTap: onReplay,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _InlineActionButton(
                  icon: Icons.cloud_upload_outlined,
                  label: isRegistering
                      ? _text(context, '注册中', 'Registering')
                      : _text(context, '保存复用指令', 'Save reusable command'),
                  onTap: onRegister,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _FlowStepPill extends StatelessWidget {
  const _FlowStepPill({
    required this.icon,
    required this.label,
    required this.active,
  });

  final IconData icon;
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = active ? _modelFreeColor(context) : palette.textTertiary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.16 : 0.09),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 5),
          Text(
            label,
            style: TextStyle(
              color: active ? palette.textPrimary : palette.textSecondary,
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 0,
            ),
          ),
        ],
      ),
    );
  }
}

class _InlineActionButton extends StatelessWidget {
  const _InlineActionButton({
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
      color: enabled
          ? _modelFreeColor(
              context,
            ).withValues(alpha: context.isDarkTheme ? 0.18 : 0.10)
          : palette.surfaceSecondary,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 16,
                color: enabled ? palette.textPrimary : palette.textTertiary,
              ),
              const SizedBox(width: 6),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: enabled ? palette.textPrimary : palette.textTertiary,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0,
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
    final resolvedTitle = widget.title.trim().isEmpty
        ? context.l10n.runLogTimelineTitle
        : widget.title.trim();
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
                        title: resolvedTitle,
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
    required this.subtitle,
    required this.actions,
  });

  final String title;
  final String? subtitle;
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
            child: _RunLogTimelineHeaderTitle(
              title: title,
              subtitle: subtitle,
              alignment: CrossAxisAlignment.start,
            ),
          ),
          ...actions,
        ],
      ),
    );
  }
}

class _RunLogTimelineHeaderTitle extends StatelessWidget {
  const _RunLogTimelineHeaderTitle({
    required this.title,
    required this.subtitle,
    this.alignment = CrossAxisAlignment.center,
  });

  final String title;
  final String? subtitle;
  final CrossAxisAlignment alignment;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final subtitleText = subtitle?.trim() ?? '';
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: alignment,
      children: [
        Text(
          title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: palette.textPrimary,
            letterSpacing: 0,
            height: 1.08,
          ),
        ),
        if (subtitleText.isNotEmpty) ...[
          const SizedBox(height: 2),
          Text(
            subtitleText,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: palette.textTertiary,
              letterSpacing: 0,
              height: 1.05,
            ),
          ),
        ],
      ],
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
    final source = _runLogStepSource(snapshot);
    final sourceColor = _runLogStepSourceColor(context, source);
    final hasSourceBadge = _hasRunLogSourceBadge(source);
    final displayTitle = _runLogStepDisplayTitle(context, snapshot);

    final isHit = compileKind == 'hit';
    final dotColor = success
        ? (hasSourceBadge
              ? sourceColor
              : (isHit ? _successColor(context) : _routeColor(context)))
        : _errorColor(context);
    final lineColor = isDark ? palette.borderSubtle : Colors.grey.shade200;
    final baseCardColor = isDark ? palette.surfaceSecondary : Colors.white;
    final cardColor = hasSourceBadge
        ? Color.alphaBlend(
            sourceColor.withValues(alpha: isDark ? 0.17 : 0.075),
            baseCardColor,
          )
        : baseCardColor;
    final borderColor = hasSourceBadge
        ? sourceColor.withValues(alpha: isDark ? 0.40 : 0.24)
        : (isDark ? palette.borderSubtle : Colors.grey.shade100);
    final preview = snapshot.previewText(context);

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
                              _stepLabel(context, snapshot.stepNumber),
                              style: TextStyle(
                                fontSize: 11,
                                color: palette.textSecondary,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                            const SizedBox(width: 6),
                            if (hasSourceBadge)
                              _RunLogStepSourceBadge(source: source)
                            else
                              _RouteBadge(compileKind: compileKind, l10n: l10n),
                            const Spacer(),
                            if (snapshot.durationMs != null)
                              Text(
                                _formatMs(snapshot.durationMs!),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                ),
                              ),
                            if (snapshot.tokenUsageIsVlmCost) ...[
                              const SizedBox(width: 6),
                              Text(
                                _formatTokens(snapshot.totalTokens!),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                ),
                              ),
                            ],
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
                          displayTitle.isEmpty
                              ? l10n.runLogTimelineUnknown
                              : displayTitle,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: palette.textPrimary,
                          ),
                        ),
                        if (snapshot.toolName.isNotEmpty &&
                            snapshot.toolName != displayTitle) ...[
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
    final source = _runLogStepSource(snapshot);
    final displayTitle = _runLogStepDisplayTitle(context, snapshot);

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
                                  '${_runLogStepDetailTitle(context, source)} · ${_stepLabel(context, snapshot.stepNumber)}',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  displayTitle.isEmpty
                                      ? _text(context, '未知步骤', 'Unknown step')
                                      : displayTitle,
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
                            if (snapshot.tokenUsageIsVlmCost) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(
                                  context,
                                  '在线 VLM 成本',
                                  'Online VLM cost',
                                ),
                                copyValue: _prettyJson({
                                  'token_usage': snapshot.tokenUsage,
                                  if (snapshot.tokenUsageAttempts.isNotEmpty)
                                    'token_usage_attempts':
                                        snapshot.tokenUsageAttempts,
                                }),
                                initiallyExpanded: false,
                                child: _JsonBlock(
                                  value: {
                                    'token_usage': snapshot.tokenUsage,
                                    if (snapshot.tokenUsageAttempts.isNotEmpty)
                                      'token_usage_attempts':
                                          snapshot.tokenUsageAttempts,
                                  },
                                ),
                              ),
                            ],
                            if (_shouldShowVisualActionPanel(snapshot)) ...[
                              const SizedBox(height: 10),
                              _VlmStepActionPanel(
                                snapshot: snapshot,
                                source: source,
                              ),
                            ],
                            if (snapshot.prompt.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              _PromptHighlightBox(
                                text: snapshot.prompt,
                                source: snapshot.promptSource,
                              ),
                            ],
                            // Key param highlight row
                            if (snapshot.previewText(context).isNotEmpty) ...[
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
                                  snapshot.previewText(context),
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
                                copyValue: _prettyUserJson(snapshot.params),
                                initiallyExpanded: true,
                                child: _JsonBlock(
                                  value: _userVisibleJson(snapshot.params),
                                ),
                              ),
                            ],
                            // Result — expanded by default
                            if (!_isEmptyJsonValue(snapshot.result)) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '结果', 'Result'),
                                copyValue: _prettyUserJson(snapshot.result),
                                initiallyExpanded: true,
                                child: _JsonBlock(
                                  value: _userVisibleJson(snapshot.result),
                                ),
                              ),
                            ],
                            // Execution metadata — collapsed by default
                            if (!_isEmptyJsonValue(snapshot.compileResult)) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '执行信息', 'Execution info'),
                                copyValue: _prettyUserJson(
                                  snapshot.compileResult,
                                ),
                                initiallyExpanded: false,
                                child: _JsonBlock(
                                  value: _userVisibleJson(
                                    snapshot.compileResult,
                                  ),
                                ),
                              ),
                            ],
                            // Before / after — collapsed by default
                            if (snapshot.before.isNotEmpty ||
                                snapshot.after.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '前后状态', 'Before / after'),
                                copyValue: _prettyUserJson({
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
                              copyValue: _prettyUserJson(widget.card),
                              initiallyExpanded: false,
                              child: _JsonBlock(
                                value: _userVisibleJson(widget.card),
                              ),
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
      _text(context, '正在生成此步复用指令...', 'Generating this step command...'),
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
          _text(context, '此步复用指令已生成', 'Step reusable command generated'),
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
        '${_text(context, '生成失败', 'Generation failed')}: $e',
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
    this.initialImportResult,
  });

  final RunLogReusableFunctionSpec spec;
  final String runId;
  final String? baseUrl;
  final UtgRunLogImportResult? initialImportResult;

  @override
  State<_ReusableFunctionSpecSheet> createState() =>
      _ReusableFunctionSpecSheetState();
}

class _ReusableFunctionSpecSheetState
    extends State<_ReusableFunctionSpecSheet> {
  late UtgRunLogImportResult? _importResult;
  UtgManualRunResult? _runResult;
  bool _isImporting = false;
  bool _isExecuting = false;
  bool _isScheduling = false;
  String? _apiError;

  RunLogReusableFunctionSpec get spec => widget.spec;

  @override
  void initState() {
    super.initState();
    _importResult = widget.initialImportResult;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final sheetHeight = MediaQuery.of(context).size.height * 0.55;
    final hasRegisteredFunction = _registeredFunctionId.isNotEmpty;
    final functionJsonForUser = _functionJsonForUser;
    final agentPromptForUser = _agentPromptForUser;

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
                                          'AI 整理结果',
                                          'AI prepared command',
                                        )
                                      : hasRegisteredFunction
                                      ? _text(
                                          context,
                                          'RunLog 保存结果',
                                          'Saved RunLog command',
                                        )
                                      : _text(
                                          context,
                                          '本地生成结果',
                                          'Locally prepared command',
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
                              '复制复用指令 JSON',
                              'Copy reusable command JSON',
                            ),
                            child: IconButton(
                              icon: const Icon(Icons.data_object_rounded),
                              color: palette.textSecondary,
                              onPressed: () => _copyText(
                                context,
                                functionJsonForUser,
                                _text(
                                  context,
                                  '已复制复用指令 JSON',
                                  'Reusable command JSON copied',
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
                                  label: _text(context, '来源', 'Source'),
                                  value: spec.aiEnhanced
                                      ? _text(context, 'AI 整理', 'AI prepared')
                                      : hasRegisteredFunction
                                      ? _text(context, '已保存', 'Saved')
                                      : _text(context, '本地生成', 'Local'),
                                ),
                                _SummaryPill(
                                  label: _text(context, '状态', 'Status'),
                                  value: hasRegisteredFunction
                                      ? _text(context, '可执行', 'Executable')
                                      : _text(context, '待保存', 'Draft'),
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
                                            '保存复用指令',
                                            'Save reusable command',
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
                                            '执行复用指令',
                                            'Run reusable command',
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
                                      'Schedule this command',
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
                                      functionJsonForUser,
                                      _text(
                                        context,
                                        '已复制复用指令 JSON',
                                        'Reusable command JSON copied',
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
                                      agentPromptForUser,
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
                                '复用指令 JSON',
                                'Reusable command JSON',
                              ),
                              copyValue: functionJsonForUser,
                              child: _JsonText(text: functionJsonForUser),
                            ),
                            const SizedBox(height: 12),
                            _DetailSection(
                              title: _text(
                                context,
                                'Agent 复用提示',
                                'Agent reuse prompt',
                              ),
                              copyValue: agentPromptForUser,
                              child: _JsonText(text: agentPromptForUser),
                            ),
                            if (_apiCallJson.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _DetailSection(
                                title: _text(context, '执行调用', 'Run call'),
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
      ]);
      if (result.success && registeredId.isEmpty) {
        final message = _text(
          context,
          '注册返回缺少复用指令 ID',
          'Registration returned no reusable command ID',
        );
        setState(() {
          _isImporting = false;
          _apiError = message;
        });
        showToast(message, type: ToastType.error);
        return;
      }
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
          _text(context, '已保存为复用指令', 'Reusable command saved'),
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
        _text(context, '没有可执行的复用指令', 'Missing runnable command'),
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
      await showFunctionRunResultSheet(
        context,
        result: result,
        title: _text(context, '复用指令执行结果', 'Reusable command result'),
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
            '复用指令保存失败，无法转定时任务',
            'Reusable command registration failed',
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
    final importResult = _importResult;
    return _firstNonBlank([
      if (importResult?.success == true) importResult?.createdFunctionId,
      if (importResult?.success == true) _firstHitFunctionId,
      if (_runResult?.success == true) _runResult?.functionId,
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

  String get _functionJsonForUser => _prettyUserJson(spec.json);

  String get _agentPromptForUser => _userVisibleString(spec.agentPrompt);

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
    final argumentsJson = _prettyUserJson(_defaultArguments);
    if (_localeValue(context, zh: false, en: true)) {
      return [
        'Execute this already registered OOB reusable command now. Do not create, update, or discuss the schedule.',
        '',
        'Reusable command ID: $functionId',
        'Arguments JSON:',
        argumentsJson,
        '',
        'Execution rule: run the OOB reusable command with the arguments above. The runtime executes executor=omniflow/model_free steps locally without a model call; executor=tool uses step.callable_tool; executor=agent or validation mismatch may re-plan with step.agent_call/fallback prompt.',
        '',
        'Reusable command JSON:',
        _functionJsonForUser,
      ].join('\n');
    }
    return [
      '现在执行这个已经注册的 OOB 复用指令。不要创建、修改或讨论定时任务。',
      '',
      '复用指令 ID: $functionId',
      'Arguments JSON:',
      argumentsJson,
      '',
      '执行规则：用上面的参数运行 OOB 复用指令。运行时会把 executor=omniflow/model_free 的步骤本地执行，不调用模型；executor=tool 调用 step.callable_tool；executor=agent 或 validation 不匹配时可使用 step.agent_call/fallback prompt 重规划。',
      '',
      '复用指令 JSON:',
      _functionJsonForUser,
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
    return _prettyUserJson({
      'action': 'run_reusable_command',
      'function_id': functionId,
      'arguments': _defaultArguments,
      'context': {
        'source': 'oob_reusable_function',
        'source_run_id': widget.runId,
      },
    });
  }

  String _runSuccessMessage(BuildContext context, UtgManualRunResult result) {
    if (result.startedAgentFallback) {
      final taskId = result.taskId;
      return _localeValue(
        context,
        zh: taskId.isEmpty ? '已交给 VLM 继续执行' : '已交给 VLM 继续执行：$taskId',
        en: taskId.isEmpty ? 'Handed off to VLM' : 'Handed off to VLM: $taskId',
      );
    }
    if (result.completedLocal) {
      return _text(
        context,
        '复用指令已本地执行完成',
        'Reusable command completed locally',
      );
    }
    return _text(context, '复用指令已开始执行', 'Reusable command started');
  }

  String _runFailureMessage(BuildContext context, UtgManualRunResult result) {
    final error = result.errorMessage?.trim();
    if (error != null && error.isNotEmpty) {
      return error;
    }
    return _text(context, '复用指令执行失败', 'Reusable command failed');
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
    final runResult = this.runResult;
    final statusColor = runResult == null || runResult.success
        ? _successColor(context)
        : _errorColor(context);
    final lines = <String>[
      if (functionId.isNotEmpty)
        _text(context, '复用指令：$functionId', 'Reusable command: $functionId'),
      if (importResult != null) _importStatusText(context, importResult!),
      if (runResult != null) _runStatusText(context, runResult),
    ];
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: statusColor.withValues(alpha: context.isDarkTheme ? 0.14 : 0.09),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: statusColor.withValues(alpha: 0.28)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                Icons.play_circle_outline_rounded,
                size: 18,
                color: statusColor,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  lines.join('\n'),
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textSecondary,
                    height: 1.35,
                  ),
                ),
              ),
              if (apiCallJson.trim().isNotEmpty)
                Tooltip(
                  message: _text(context, '复制执行调用', 'Copy run call'),
                  child: IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.content_copy_rounded, size: 16),
                    color: palette.textSecondary,
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: apiCallJson));
                      showToast(
                        _text(context, '已复制执行调用', 'Run call copied'),
                        type: ToastType.success,
                      );
                    },
                  ),
                ),
            ],
          ),
          if (runResult != null && runResult.stepResults.isNotEmpty) ...[
            const SizedBox(height: 10),
            FunctionRunResultInlinePanel(result: runResult),
          ],
        ],
      ),
    );
  }

  String _importStatusText(
    BuildContext context,
    UtgRunLogImportResult importResult,
  ) {
    if (!importResult.success) {
      return _text(context, '保存：失败', 'Save: failed');
    }
    final count = importResult.functionsCreated;
    return _text(
      context,
      count > 0 ? '保存：已保存 $count 条复用指令' : '保存：已保存',
      count > 0 ? 'Save: $count reusable commands saved' : 'Save: saved',
    );
  }

  String _runStatusText(BuildContext context, UtgManualRunResult result) {
    final stepCount = result.stepCount;
    final stepText = stepCount > 0
        ? ' · ${result.successStepCount}/$stepCount'
        : '';
    if (result.startedAgentFallback) {
      return _text(
        context,
        '执行：已交给 VLM 继续执行$stepText',
        'Run: handed off to VLM$stepText',
      );
    }
    if (result.completedLocal) {
      return _text(
        context,
        '执行：本地执行完成$stepText',
        'Run: completed locally$stepText',
      );
    }
    if (!result.success) {
      return _text(context, '执行：失败$stepText', 'Run: failed$stepText');
    }
    return _text(context, '执行：已开始$stepText', 'Run: started$stepText');
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

class _VlmStepActionPanel extends StatelessWidget {
  const _VlmStepActionPanel({required this.snapshot, required this.source});

  final _RunLogStepSnapshot snapshot;
  final _RunLogStepSource source;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final color = _runLogStepSourceColor(context, source);
    final params = _asStringKeyMap(snapshot.params);
    final result = _asStringKeyMap(snapshot.result);
    final action = _vlmActionLabel(context, snapshot.toolName).trim();
    final target = _runLogStepTarget(snapshot).trim();
    final coordinates = _vlmCoordinateText(params);
    final resultText = _firstNonBlank([
      result['summary'],
      result['message'],
      result['error_message'],
      result['errorMessage'],
    ]).trim();
    final meta = <MapEntry<String, String>>[
      if (snapshot.packageName.isNotEmpty)
        MapEntry(_text(context, '应用', 'Package'), snapshot.packageName),
      if (coordinates.isNotEmpty)
        MapEntry(_text(context, '坐标', 'Coordinates'), coordinates),
      if (snapshot.durationMs != null)
        MapEntry(
          _text(context, '耗时', 'Duration'),
          _formatMs(snapshot.durationMs!),
        ),
    ];

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(11, 10, 11, 11),
      decoration: BoxDecoration(
        color: color.withValues(alpha: isDark ? 0.15 : 0.075),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: color.withValues(alpha: isDark ? 0.34 : 0.22),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.touch_app_rounded, size: 16, color: color),
              const SizedBox(width: 7),
              Expanded(
                child: Text(
                  _runLogStepActionPanelTitle(context, source),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: color,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0,
                    height: 1.1,
                  ),
                ),
              ),
              if (action.isNotEmpty)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: isDark ? 0.20 : 0.12),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    action,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: color,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0,
                      height: 1,
                    ),
                  ),
                ),
            ],
          ),
          if (target.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              target,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 13,
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
                letterSpacing: 0,
                height: 1.25,
              ),
            ),
          ],
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: meta
                  .map(
                    (entry) => _VlmActionMetaPill(
                      label: entry.key,
                      value: entry.value,
                    ),
                  )
                  .toList(growable: false),
            ),
          ],
          if (resultText.isNotEmpty && resultText != target) ...[
            const SizedBox(height: 8),
            Text(
              resultText,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                fontWeight: FontWeight.w500,
                letterSpacing: 0,
                height: 1.3,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _VlmActionMetaPill extends StatelessWidget {
  const _VlmActionMetaPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary.withValues(alpha: 0.78)
            : Colors.white.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle.withValues(alpha: 0.72)),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            fontSize: 12,
            color: palette.textSecondary,
            letterSpacing: 0,
            height: 1.05,
          ),
          children: [
            TextSpan(text: '$label  '),
            TextSpan(
              text: value,
              style: TextStyle(
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
                fontFamily: value.contains(',') ? 'monospace' : null,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.snapshot});

  final _RunLogStepSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final source = _runLogStepSource(snapshot);
    final items = <MapEntry<String, String>>[
      MapEntry(_text(context, '状态', 'Status'), snapshot.statusLabel(context)),
      MapEntry(
        _text(context, '执行方式', 'Execution'),
        _hasRunLogSourceBadge(source)
            ? _runLogStepSourceLabel(context, source)
            : _text(context, '需要模型', 'Needs model'),
      ),
      if (!_hasRunLogSourceBadge(source) && snapshot.compileKind.isNotEmpty)
        MapEntry(
          _text(context, '处理方式', 'Handling'),
          snapshot.routeLabel(context),
        ),
      if (snapshot.durationMs != null)
        MapEntry(
          _text(context, '耗时', 'Duration'),
          _formatMs(snapshot.durationMs!),
        ),
      if (snapshot.tokenUsageIsVlmCost)
        MapEntry(
          _text(context, 'VLM Token', 'VLM tokens'),
          snapshot.tokenUsageLabel(context),
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

class _RunLogStepSourceBadge extends StatelessWidget {
  const _RunLogStepSourceBadge({required this.source});

  final _RunLogStepSource source;

  @override
  Widget build(BuildContext context) {
    final color = _runLogStepSourceColor(context, source);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.18 : 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.26)),
      ),
      child: Text(
        _runLogStepSourceLabel(context, source),
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
    required this.tokenUsage,
    required this.tokenUsageAttempts,
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
  final Map<String, dynamic> tokenUsage;
  final List<Map<String, dynamic>> tokenUsageAttempts;

  int? get totalTokens =>
      _asInt(tokenUsage['total_tokens'] ?? tokenUsage['totalTokens']);

  bool get isVlmStep {
    final toolType = _firstNonBlank([
      card['tool_type'],
      card['toolType'],
      header['tool_type'],
      header['toolType'],
    ]).toLowerCase();
    final normalizedToolName = toolName.trim().toLowerCase();
    final source = _firstNonBlank([
      card['source'],
      card['run_source'],
      card['runSource'],
      card['selection_source'],
      card['selectionSource'],
    ]).toLowerCase();
    final normalizedExecutionKind = compileKind.trim().toLowerCase();
    return normalizedExecutionKind == 'vlm_step' ||
        normalizedExecutionKind == 'vlm' ||
        toolType == 'vlm' ||
        normalizedToolName == 'vlm_task' ||
        source == 'vlm';
  }

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
    var tokenUsage = _firstMap(card, const ['token_usage', 'tokenUsage']);
    if (tokenUsage.isEmpty) {
      tokenUsage = _firstMap(header, const ['token_usage', 'tokenUsage']);
    }
    final tokenUsageAttempts = _asStringKeyMapList(
      _firstPresentValue(card, const [
        'token_usage_attempts',
        'tokenUsageAttempts',
      ]),
    );

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
      tokenUsage: tokenUsage,
      tokenUsageAttempts: tokenUsageAttempts,
    );
  }

  String previewText(BuildContext context) {
    final parts = <String>[];
    final paramsMap = _asStringKeyMap(params);
    final resultMap = _asStringKeyMap(result);
    if (prompt.isNotEmpty) {
      parts.add(
        '${_text(context, '提示', 'Prompt')}: ${_compactPreview(prompt, maxLength: 180)}',
      );
    }
    final target = _firstNonBlank([
      paramsMap['target_description'],
      paramsMap['targetDescription'],
      paramsMap['label'],
      paramsMap['text'],
      paramsMap['content'],
      paramsMap['goal'],
      paramsMap['task_goal'],
      paramsMap['taskGoal'],
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

  bool get tokenUsageIsVlmCost => isVlmStep && totalTokens != null;

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
    if (compileKind == 'vlm_step' || compileKind == 'vlm') {
      return _text(context, 'VLM 执行', 'VLM execution');
    }
    return compileKind;
  }

  String tokenUsageLabel(BuildContext context) {
    final total = totalTokens;
    final promptTokens = _asInt(
      tokenUsage['prompt_tokens'] ?? tokenUsage['promptTokens'],
    );
    final completionTokens = _asInt(
      tokenUsage['completion_tokens'] ?? tokenUsage['completionTokens'],
    );
    final cachedTokens = _asInt(
      tokenUsage['cached_tokens'] ?? tokenUsage['cachedTokens'],
    );
    final parts = <String>[];
    if (total != null) {
      parts.add(_formatTokens(total));
    }
    if (promptTokens != null || completionTokens != null) {
      parts.add('P${promptTokens ?? 0}/C${completionTokens ?? 0}');
    }
    if (cachedTokens != null && cachedTokens > 0) {
      parts.add(
        _localeValue(
          context,
          zh: '缓存 $cachedTokens',
          en: 'cached $cachedTokens',
        ),
      );
    }
    return parts.isEmpty ? _text(context, '未知', 'Unknown') : parts.join(' · ');
  }

  String toTranscript() {
    final lines = <String>[
      '### Step $stepNumber',
      if (title.isNotEmpty) 'Title: $title',
      if (toolName.isNotEmpty) 'Tool: $toolName',
      if (toolCallId.isNotEmpty) 'Tool Call ID: $toolCallId',
      if (compileKind.isNotEmpty)
        'Execution: ${_userVisibleString(compileKind)}',
      if (success != null) 'Success: $success',
      if (durationMs != null) 'Duration: ${_formatMs(durationMs!)}',
      if (tokenUsageIsVlmCost) 'VLM Token Usage: ${tokenUsageLabelTextOnly()}',
      if (packageName.isNotEmpty) 'Package: $packageName',
      if (prompt.isNotEmpty) 'Prompt Source: $promptSource',
    ];

    if (prompt.isNotEmpty) {
      _appendTranscriptSection(lines, 'Prompt', prompt);
    }
    _appendTranscriptSection(lines, 'Tool Call', toolCall);
    _appendTranscriptSection(lines, 'Arguments', params);
    _appendTranscriptSection(lines, 'Result', result);
    _appendTranscriptSection(lines, 'Token Usage', {
      if (tokenUsage.isNotEmpty) 'token_usage': tokenUsage,
      if (tokenUsageAttempts.isNotEmpty)
        'token_usage_attempts': tokenUsageAttempts,
    });
    _appendTranscriptSection(lines, 'Execution Info', compileResult);
    if (before.isNotEmpty || after.isNotEmpty) {
      _appendTranscriptSection(lines, 'Before / After', {
        if (before.isNotEmpty) 'before': before,
        if (after.isNotEmpty) 'after': after,
      });
    }
    _appendTranscriptSection(lines, 'Raw JSON', card);
    return lines.join('\n').trimRight();
  }

  String tokenUsageLabelTextOnly() {
    final total = totalTokens;
    final promptTokens = _asInt(
      tokenUsage['prompt_tokens'] ?? tokenUsage['promptTokens'],
    );
    final completionTokens = _asInt(
      tokenUsage['completion_tokens'] ?? tokenUsage['completionTokens'],
    );
    final cachedTokens = _asInt(
      tokenUsage['cached_tokens'] ?? tokenUsage['cachedTokens'],
    );
    final parts = <String>[];
    if (total != null) parts.add(_formatTokens(total));
    if (promptTokens != null || completionTokens != null) {
      parts.add('prompt=${promptTokens ?? 0}');
      parts.add('completion=${completionTokens ?? 0}');
    }
    if (cachedTokens != null && cachedTokens > 0) {
      parts.add('cached=$cachedTokens');
    }
    return parts.join(', ');
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
    ..add(_prettyUserJson(value));
}

String _formatMs(int ms) {
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(1)}s';
}

String _formatTokens(int tokens) {
  if (tokens >= 1000) {
    return '${(tokens / 1000).toStringAsFixed(tokens >= 10000 ? 1 : 2)}k';
  }
  return '$tokens';
}

String _stepLabel(BuildContext context, int stepNumber) {
  return _localeValue(context, zh: '第 $stepNumber 步', en: 'Step $stepNumber');
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
  if (result.startedAgentFallback) {
    final taskId = result.taskId;
    return _localeValue(
      context,
      zh: taskId.isEmpty ? '执行记录已交给 VLM 继续执行' : '执行记录已交给 VLM 继续执行：$taskId',
      en: taskId.isEmpty
          ? 'Execution handed off to VLM'
          : 'Execution handed off to VLM: $taskId',
    );
  }
  if (result.completedLocal) {
    return context.l10n.omniflowAssetReplaySuccess;
  }
  return context.l10n.omniflowAssetReplaySuccess;
}

String _runLogReplayFailureMessage(
  BuildContext context,
  UtgManualRunResult result,
) {
  final error = result.errorMessage?.trim();
  if (error != null && error.isNotEmpty) {
    return error;
  }
  return context.l10n.omniflowAssetReplayFailed;
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

Color _vlmColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF6BA9)
      : const Color(0xFFDB2777);
}

Color _humanColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFB86B)
      : const Color(0xFFD97706);
}

Color _warningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFFFC04D);
}

Color _runningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFE6A700);
}

enum _RunLogStatusKind { running, success, failed, unknown }

class _RunLogStatusInfo {
  const _RunLogStatusInfo({
    required this.kind,
    required this.label,
    required this.title,
    required this.color,
    required this.icon,
  });

  final _RunLogStatusKind kind;
  final String label;
  final String title;
  final Color color;
  final IconData icon;
}

_RunLogStatusInfo _runLogStatusInfo(
  BuildContext context,
  Map<String, dynamic> payload,
) {
  final rawStatus = _firstNonBlank([
    payload['run_status'],
    payload['runStatus'],
    payload['status'],
  ]).toLowerCase();
  final finished = _isRunLogFinished(payload);
  if (!finished || rawStatus == 'running' || rawStatus == 'in_progress') {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.running,
      label: _text(context, '运行中', 'Running'),
      title: _text(context, '执行还在进行中', 'Execution is still running'),
      color: _runningColor(context),
      icon: Icons.timelapse_rounded,
    );
  }
  final success = _runLogSuccess(payload);
  if (success == true) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.success,
      label: _text(context, '已完成', 'Done'),
      title: _text(context, '执行已完成', 'Execution completed'),
      color: _successColor(context),
      icon: Icons.check_circle_outline_rounded,
    );
  }
  if (success == false ||
      _firstNonBlank([
        payload['error_message'],
        payload['errorMessage'],
      ]).isNotEmpty) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.failed,
      label: _text(context, '失败', 'Failed'),
      title: _text(context, '执行失败', 'Execution failed'),
      color: _errorColor(context),
      icon: Icons.error_outline_rounded,
    );
  }
  return _RunLogStatusInfo(
    kind: _RunLogStatusKind.unknown,
    label: _text(context, '未知', 'Unknown'),
    title: _text(context, '执行状态未知', 'Execution status unknown'),
    color: context.omniPalette.textTertiary,
    icon: Icons.help_outline_rounded,
  );
}

String _runLogTimeText(
  BuildContext context,
  Map<String, dynamic> payload, {
  required bool start,
}) {
  final rawText = _firstNonBlank(
    start
        ? [payload['started_at'], payload['startedAt']]
        : [payload['finished_at'], payload['finishedAt']],
  );
  final rawMs = _asInt(
    start
        ? payload['started_at_ms'] ?? payload['startedAtMs']
        : payload['finished_at_ms'] ?? payload['finishedAtMs'],
  );
  DateTime? value;
  if (rawMs != null && rawMs > 0) {
    value = DateTime.fromMillisecondsSinceEpoch(rawMs).toLocal();
  } else if (rawText.isNotEmpty) {
    value = DateTime.tryParse(rawText)?.toLocal();
  }
  if (value == null) return '';
  final now = DateTime.now();
  final sameDay =
      now.year == value.year &&
      now.month == value.month &&
      now.day == value.day;
  final hour = value.hour.toString().padLeft(2, '0');
  final minute = value.minute.toString().padLeft(2, '0');
  final second = value.second.toString().padLeft(2, '0');
  if (sameDay) {
    return '$hour:$minute:$second';
  }
  final month = value.month.toString().padLeft(2, '0');
  final day = value.day.toString().padLeft(2, '0');
  return '${value.year}/$month/$day $hour:$minute';
}

enum _RunLogStepSource { agentVlm, human, omniflowReplay, route }

bool _isVlmRunLogStep(_RunLogStepSnapshot snapshot) => snapshot.isVlmStep;

_RunLogStepSource _runLogStepSource(_RunLogStepSnapshot snapshot) {
  final evidence = _runLogSourceEvidence(snapshot);
  if (_containsAny(evidence, const [
    'human_takeover',
    'manual_recording',
    'human',
  ])) {
    return _RunLogStepSource.human;
  }
  if (_containsAny(evidence, const [
    'omniflow_replay',
    'oob_omniflow_replay',
    'oob_function_runner',
    'completed_local',
  ])) {
    return _RunLogStepSource.omniflowReplay;
  }
  if (_isVlmRunLogStep(snapshot)) {
    return _RunLogStepSource.agentVlm;
  }
  return _RunLogStepSource.route;
}

Set<String> _runLogSourceEvidence(_RunLogStepSnapshot snapshot) {
  final values = <String>{};
  void collect(dynamic value, {int depth = 0}) {
    if (value == null || depth > 4) {
      return;
    }
    if (value is Map) {
      for (final entry in value.entries) {
        final key = entry.key.toString().trim().toLowerCase();
        final shouldCollect = const {
          'source',
          'run_source',
          'runsource',
          'selection_source',
          'selectionsource',
          'recording_source',
          'recordingsource',
          'compile_kind',
          'compilekind',
          'tool_type',
          'tooltype',
          'runner',
          'executor',
          'execution_status',
          'executionstatus',
          'replay_engine',
          'replayengine',
        }.contains(key);
        if (shouldCollect) {
          collect(entry.value, depth: depth + 1);
        } else if (entry.value is Map || entry.value is List) {
          collect(entry.value, depth: depth + 1);
        }
      }
      return;
    }
    if (value is List) {
      for (final item in value) {
        collect(item, depth: depth + 1);
      }
      return;
    }
    final text = value.toString().trim().toLowerCase();
    if (text.isNotEmpty) {
      values.add(text);
    }
  }

  collect(snapshot.card);
  collect(snapshot.header);
  collect(snapshot.result);
  collect(snapshot.params);
  collect(snapshot.compileResult);
  values.add(snapshot.compileKind.trim().toLowerCase());
  values.add(snapshot.toolName.trim().toLowerCase());
  return values.where((value) => value.isNotEmpty).toSet();
}

bool _containsAny(Set<String> values, List<String> needles) {
  for (final value in values) {
    for (final needle in needles) {
      if (value == needle || value.contains(needle)) {
        return true;
      }
    }
  }
  return false;
}

bool _hasRunLogSourceBadge(_RunLogStepSource source) {
  return source == _RunLogStepSource.agentVlm ||
      source == _RunLogStepSource.human ||
      source == _RunLogStepSource.omniflowReplay;
}

Color _runLogStepSourceColor(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _vlmColor(context);
    case _RunLogStepSource.human:
      return _humanColor(context);
    case _RunLogStepSource.omniflowReplay:
      return _modelFreeColor(context);
    case _RunLogStepSource.route:
      return _routeColor(context);
  }
}

String _runLogStepSourceLabel(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return 'Agent/VLM';
    case _RunLogStepSource.human:
      return _text(context, '人类', 'Human');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '离线重放', 'OmniFlow Replay');
    case _RunLogStepSource.route:
      return _text(context, '工具', 'Tool');
  }
}

String _runLogStepDetailTitle(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _text(context, 'Agent/VLM 执行记录', 'Agent/VLM run');
    case _RunLogStepSource.human:
      return _text(context, '人类接管记录', 'Human takeover');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '离线重放记录', 'Offline replay');
    case _RunLogStepSource.route:
      return _text(context, '工具调用历史', 'Tool call history');
  }
}

String _runLogStepActionPanelTitle(
  BuildContext context,
  _RunLogStepSource source,
) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _text(context, 'Agent/VLM 动作', 'Agent/VLM action');
    case _RunLogStepSource.human:
      return _text(context, '人类操作', 'Human action');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '离线重放动作', 'Offline replay action');
    case _RunLogStepSource.route:
      return _text(context, '工具动作', 'Tool action');
  }
}

bool _shouldShowVisualActionPanel(_RunLogStepSnapshot snapshot) {
  final source = _runLogStepSource(snapshot);
  return source == _RunLogStepSource.agentVlm ||
      source == _RunLogStepSource.human ||
      source == _RunLogStepSource.omniflowReplay;
}

String _runLogStepDisplayTitle(
  BuildContext context,
  _RunLogStepSnapshot snapshot,
) {
  if (!_isVlmRunLogStep(snapshot)) {
    return snapshot.title;
  }
  final action = _vlmActionLabel(context, snapshot.toolName);
  final target = _runLogStepTarget(snapshot);
  if (action.isEmpty) {
    return target.isNotEmpty ? target : snapshot.title;
  }
  if (target.isEmpty || target == action) {
    return action;
  }
  return '$action $target';
}

String _runLogStepTarget(_RunLogStepSnapshot snapshot) {
  final params = _asStringKeyMap(snapshot.params);
  final result = _asStringKeyMap(snapshot.result);
  final replayAction =
      RunLogReplayPolicy.omniflowActionForToolName(snapshot.toolName) ??
      snapshot.toolName.trim().toLowerCase();
  if (replayAction == 'open_app' && snapshot.packageName.isNotEmpty) {
    return snapshot.packageName;
  }
  return _firstNonBlank([
    params['target_description'],
    params['targetDescription'],
    params['content'],
    params['goal'],
    params['task_goal'],
    params['taskGoal'],
    params['text'],
    params['prompt'],
    params['value'],
    params['package_name'],
    params['packageName'],
    params['key'],
    params['duration_ms'],
    params['durationMs'],
    params['duration'],
    result['summary'],
    result['message'],
  ]);
}

String _vlmCoordinateText(Map<String, dynamic> params) {
  final x = _firstNonBlank([params['x'], params['clientX']]);
  final y = _firstNonBlank([params['y'], params['clientY']]);
  if (x.isNotEmpty && y.isNotEmpty) {
    return '$x,$y';
  }
  final x1 = _firstNonBlank([params['x1'], params['startX']]);
  final y1 = _firstNonBlank([params['y1'], params['startY']]);
  final x2 = _firstNonBlank([params['x2'], params['endX']]);
  final y2 = _firstNonBlank([params['y2'], params['endY']]);
  if (x1.isNotEmpty && y1.isNotEmpty && x2.isNotEmpty && y2.isNotEmpty) {
    return '$x1,$y1 -> $x2,$y2';
  }
  return '';
}

String _vlmActionLabel(BuildContext context, String raw) {
  switch (raw.trim()) {
    case 'click':
      return _text(context, '点击', 'Tap');
    case 'type':
      return _text(context, '输入', 'Type');
    case 'scroll':
      return _text(context, '滚动', 'Scroll');
    case 'long_press':
    case 'longPress':
      return _text(context, '长按', 'Long press');
    case 'open_app':
    case 'openApp':
      return _text(context, '打开应用', 'Open app');
    case 'press_home':
    case 'pressHome':
      return _text(context, '返回桌面', 'Home');
    case 'press_back':
    case 'pressBack':
      return _text(context, '返回', 'Back');
    case 'wait':
      return _text(context, '等待', 'Wait');
    case 'record':
      return _text(context, '记录', 'Record');
    case 'finished':
      return _text(context, '完成任务', 'Finish');
    case 'require_user_choice':
    case 'requireUserChoice':
      return _text(context, '请求选择', 'Need choice');
    case 'require_user_confirmation':
    case 'requireUserConfirmation':
      return _text(context, '请求确认', 'Need confirmation');
    case 'info':
      return _text(context, '请求协助', 'Need input');
    case 'feedback':
      return _text(context, '反馈', 'Feedback');
    case 'abort':
      return _text(context, '中止', 'Abort');
    case 'hot_key':
    case 'hotKey':
      return _text(context, '快捷键', 'Shortcut');
    case 'vlm_task':
    case 'mobile':
      return _text(context, '视觉执行', 'Visual task');
  }
  return raw;
}

List<Map<String, dynamic>> _extractTimelineCards(Map<String, dynamic> payload) {
  return _findTimelineCards(payload, <Object>{});
}

class _RunLogTokenUsageAggregate {
  const _RunLogTokenUsageAggregate({
    required this.totalTokens,
    required this.promptTokens,
    required this.completionTokens,
    required this.cachedTokens,
    required this.callCount,
    required this.stepCount,
  });

  final int? totalTokens;
  final int? promptTokens;
  final int? completionTokens;
  final int? cachedTokens;
  final int? callCount;
  final int? stepCount;

  bool get hasUsage =>
      totalTokens != null ||
      promptTokens != null ||
      completionTokens != null ||
      cachedTokens != null ||
      callCount != null ||
      stepCount != null;

  factory _RunLogTokenUsageAggregate.fromPayload(Map<String, dynamic> payload) {
    final usage = _firstMap(payload, const ['token_usage', 'tokenUsage']);
    final byStep = _asStringKeyMapList(
      _firstPresentValue(payload, const [
        'token_usage_by_step',
        'tokenUsageByStep',
      ]),
    );
    final byCall = _asStringKeyMapList(
      _firstPresentValue(payload, const [
        'token_usage_by_call',
        'tokenUsageByCall',
      ]),
    );
    return _RunLogTokenUsageAggregate(
      totalTokens: _asInt(
        payload['token_usage_total'] ??
            payload['tokenUsageTotal'] ??
            usage['total_tokens'] ??
            usage['totalTokens'],
      ),
      promptTokens: _asInt(usage['prompt_tokens'] ?? usage['promptTokens']),
      completionTokens: _asInt(
        usage['completion_tokens'] ?? usage['completionTokens'],
      ),
      cachedTokens: _asInt(usage['cached_tokens'] ?? usage['cachedTokens']),
      callCount:
          _asInt(
            payload['token_usage_call_count'] ??
                payload['tokenUsageCallCount'] ??
                usage['call_count'] ??
                usage['callCount'] ??
                usage['attempt_count'] ??
                usage['attemptCount'],
          ) ??
          (byCall.isNotEmpty ? byCall.length : null),
      stepCount:
          _asInt(usage['step_count'] ?? usage['stepCount']) ??
          (byStep.isNotEmpty ? byStep.length : null),
    );
  }
}

String? _runLogPayloadError(
  BuildContext context,
  Map<String, dynamic> payload,
) {
  final success = _asBool(payload['success']);
  if (success != false) {
    return null;
  }
  final code = (payload['error_code'] ?? payload['errorCode'])
      ?.toString()
      .trim()
      .toUpperCase();
  if (code == 'NOT_FOUND' || code == 'RUN_LOG_ID_EMPTY') {
    return context.l10n.omniflowAssetRunLogNotReady;
  }
  final message = (payload['error_message'] ?? payload['errorMessage'])
      ?.toString()
      .trim();
  if (message != null && message.isNotEmpty) {
    return message;
  }
  return context.l10n.runLogTimelineLoadFailed;
}

class _RunLogConvertEligibility {
  const _RunLogConvertEligibility({
    required this.canConvert,
    required this.message,
  });

  final bool canConvert;
  final String message;
}

_RunLogConvertEligibility _runLogConvertEligibility(
  BuildContext context,
  Map<String, dynamic> payload,
  List<Map<String, dynamic>> cards,
) {
  if (cards.isEmpty) {
    return _RunLogConvertEligibility(
      canConvert: false,
      message: _text(context, '暂无可注册步骤', 'No steps to register'),
    );
  }
  if (!_isRunLogFinished(payload)) {
    return _RunLogConvertEligibility(
      canConvert: false,
      message: _text(
        context,
        '执行还在进行中，完成后才能注册',
        'This run is still executing. Register after it finishes.',
      ),
    );
  }
  if (_runLogSuccess(payload) == false) {
    final error = _firstNonBlank([
      payload['error_message'],
      payload['errorMessage'],
      payload['done_reason'],
      payload['doneReason'],
    ]);
    return _RunLogConvertEligibility(
      canConvert: false,
      message: error.isNotEmpty
          ? _text(context, '执行未成功，不能注册：$error', 'Run failed: $error')
          : _text(
              context,
              '执行未成功，不能注册',
              'Only successful runs can be registered.',
            ),
    );
  }
  return _RunLogConvertEligibility(
    canConvert: true,
    message: _text(context, '注册 RunLog', 'Register RunLog'),
  );
}

bool _isRunLogFinished(Map<String, dynamic> payload) {
  final explicit = _asBool(
    payload['run_finished'] ?? payload['runFinished'] ?? payload['is_finished'],
  );
  if (explicit != null) {
    return explicit;
  }
  return _firstNonBlank([
    payload['finished_at'],
    payload['finishedAt'],
    payload['finished_at_ms'],
    payload['finishedAtMs'],
  ]).isNotEmpty;
}

bool? _runLogSuccess(Map<String, dynamic> payload) {
  return _asBool(
    payload['run_success'] ??
        payload['runSuccess'] ??
        payload['record_success'] ??
        payload['recordSuccess'],
  );
}

String _runLogEmptyMessage(BuildContext context, Map<String, dynamic> payload) {
  final success = _asBool(payload['success']);
  final doneReason = (payload['done_reason'] ?? payload['doneReason'])
      ?.toString()
      .trim();
  if (success == true) {
    return _text(
      context,
      '这次回复没有工具调用，只有最终文本，因此没有可展开的执行步骤。',
      'This reply did not call tools, so there are no execution steps to expand.',
    );
  }
  if (doneReason != null && doneReason.isNotEmpty) {
    return context.l10n.omniflowAssetNoSteps;
  }
  return context.l10n.runLogTimelineEmpty;
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

List<Map<String, dynamic>> _asStringKeyMapList(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is! List) {
    return const <Map<String, dynamic>>[];
  }
  return decoded
      .map(_asStringKeyMap)
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
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

String _prettyUserJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(_userVisibleJson(value));
  } catch (_) {
    return _userVisibleString(value?.toString() ?? '');
  }
}

dynamic _userVisibleJson(dynamic value) {
  final safe = _jsonSafe(value);
  if (safe is String) {
    return _userVisibleString(safe);
  }
  if (safe == null || safe is num || safe is bool) {
    return safe;
  }
  if (safe is Map) {
    return safe.map(
      (key, item) =>
          MapEntry(_userVisibleJsonKey(key.toString()), _userVisibleJson(item)),
    );
  }
  if (safe is Iterable) {
    return safe.map(_userVisibleJson).toList(growable: false);
  }
  return _userVisibleString(safe.toString());
}

String _userVisibleJsonKey(String key) => _userVisibleString(key);

String _userVisibleString(String value) {
  return value
      .replaceAll(RegExp('compile', caseSensitive: false), 'execution')
      .replaceAll('编译', '执行');
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
