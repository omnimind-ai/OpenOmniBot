import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/task/pages/execution_history/run_log_reusable_function_converter.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class RunLogTimelinePage extends StatefulWidget {
  const RunLogTimelinePage({
    super.key,
    required this.runId,
    required this.title,
    this.baseUrl,
  });

  final String runId;
  final String title;
  final String? baseUrl;

  @override
  State<RunLogTimelinePage> createState() => _RunLogTimelinePageState();
}

class _RunLogTimelinePageState extends State<RunLogTimelinePage> {
  Map<String, dynamic> _payload = const {};
  List<Map<String, dynamic>> _cards = [];
  bool _isLoading = true;
  bool _isConvertingFunction = false;
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
      final payload =
          await AssistsMessageService.getRunLogTimelinePreferInternal(
            runId: widget.runId,
            baseUrl: widget.baseUrl,
          );
      if (!mounted) return;
      setState(() {
        _payload = payload;
        final raw = payload['cards'];
        _cards = raw is List
            ? raw.map(_asStringKeyMap).where((card) => card.isNotEmpty).toList()
            : [];
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

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: subtitle != null
            ? '${l10n.runLogTimelineTitle}  $subtitle'
            : l10n.runLogTimelineTitle,
        actions: [
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
              onPressed: _cards.isEmpty || _isConvertingFunction
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
        ],
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
      _text(context, '正在用 AI 转换为可复用功能...', 'Converting with AI...'),
      type: ToastType.info,
    );
    try {
      final spec = await RunLogReusableFunctionConverter.convert(
        runId: widget.runId,
        title: widget.title,
        payload: _payload,
        cards: _cards,
        useEnglish: _isEnglish(context),
      );
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
      });
      if (spec.warning != null && spec.warning!.trim().isNotEmpty) {
        showToast(spec.warning!, type: ToastType.warning);
      } else {
        showToast(
          spec.aiEnhanced
              ? _text(context, 'AI 功能结构已生成', 'AI function spec generated')
              : _text(context, '功能结构已生成', 'Function spec generated'),
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
      builder: (sheetContext) =>
          _StepDetailSheet(card: card, fallbackIndex: index),
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

    final isHit = compileKind == 'hit';
    final dotColor = success
        ? (isHit ? _successColor(context) : _routeColor(context))
        : _errorColor(context);
    final lineColor = isDark ? palette.borderSubtle : Colors.grey.shade200;
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
                color: isDark ? palette.surfaceSecondary : Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isDark ? palette.borderSubtle : Colors.grey.shade100,
                ),
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

class _StepDetailSheet extends StatelessWidget {
  const _StepDetailSheet({required this.card, required this.fallbackIndex});

  final Map<String, dynamic> card;
  final int fallbackIndex;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final snapshot = _RunLogStepSnapshot.fromCard(
      card,
      fallbackIndex: fallbackIndex,
    );
    final success = snapshot.success ?? true;
    final statusColor = success ? _successColor(context) : _errorColor(context);
    final maxHeight = MediaQuery.of(context).size.height * 0.9;

    return SafeArea(
      top: false,
      child: Align(
        alignment: Alignment.bottomCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxHeight),
          child: Container(
            decoration: BoxDecoration(
              color: isDark ? palette.surfacePrimary : Colors.white,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(22),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: isDark ? 0.35 : 0.14),
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
                    padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _SummaryGrid(snapshot: snapshot),
                        const SizedBox(height: 14),
                        _DetailSection(
                          title: _text(context, '工具调用', 'Tool call'),
                          copyValue: snapshot.toolCallForCopy,
                          child: _KeyValueBlock(
                            values: {
                              if (snapshot.toolName.isNotEmpty)
                                _text(context, '工具', 'Tool'): snapshot.toolName,
                              if (snapshot.toolCallId.isNotEmpty)
                                _text(context, '调用 ID', 'Call ID'):
                                    snapshot.toolCallId,
                            },
                            fallback: _prettyJson(snapshot.toolCall),
                          ),
                        ),
                        if (!_isEmptyJsonValue(snapshot.params)) ...[
                          const SizedBox(height: 12),
                          _DetailSection(
                            title: _text(context, '参数', 'Arguments'),
                            copyValue: _prettyJson(snapshot.params),
                            child: _JsonBlock(value: snapshot.params),
                          ),
                        ],
                        if (!_isEmptyJsonValue(snapshot.result)) ...[
                          const SizedBox(height: 12),
                          _DetailSection(
                            title: _text(context, '结果', 'Result'),
                            copyValue: _prettyJson(snapshot.result),
                            child: _JsonBlock(value: snapshot.result),
                          ),
                        ],
                        if (!_isEmptyJsonValue(snapshot.compileResult)) ...[
                          const SizedBox(height: 12),
                          _DetailSection(
                            title: _text(context, '路由/编译结果', 'Route result'),
                            copyValue: _prettyJson(snapshot.compileResult),
                            child: _JsonBlock(value: snapshot.compileResult),
                          ),
                        ],
                        if (snapshot.before.isNotEmpty ||
                            snapshot.after.isNotEmpty) ...[
                          const SizedBox(height: 12),
                          _DetailSection(
                            title: _text(context, '前后状态', 'Before / after'),
                            copyValue: _prettyJson({
                              if (snapshot.before.isNotEmpty)
                                'before': snapshot.before,
                              if (snapshot.after.isNotEmpty)
                                'after': snapshot.after,
                            }),
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
                        const SizedBox(height: 12),
                        _DetailSection(
                          title: _text(context, '原始 JSON', 'Raw JSON'),
                          copyValue: _prettyJson(card),
                          child: _JsonBlock(value: card),
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
    );
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
  String? _apiError;

  RunLogReusableFunctionSpec get spec => widget.spec;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final maxHeight = MediaQuery.of(context).size.height * 0.9;
    final scriptCall = _scriptCallJson;

    return SafeArea(
      top: false,
      child: Align(
        alignment: Alignment.bottomCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxHeight),
          child: Container(
            decoration: BoxDecoration(
              color: isDark ? palette.surfacePrimary : Colors.white,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(22),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: isDark ? 0.35 : 0.14),
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
                                  ? _text(context, 'AI 转换结果', 'AI conversion')
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
                              spec.name.isEmpty ? spec.functionId : spec.name,
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
                                    ? _text(context, '注册为 API', 'Register API')
                                    : _text(context, '重新注册', 'Re-register'),
                                onTap: _isImporting ? null : _registerFunction,
                              ),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: _SpecActionButton(
                                icon: Icons.play_arrow_rounded,
                                label: _isExecuting
                                    ? _text(context, '执行中', 'Running')
                                    : _text(context, '执行 API', 'Run API'),
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
                        Row(
                          children: [
                            Expanded(
                              child: _SpecActionButton(
                                icon: Icons.content_copy_rounded,
                                label: _text(context, '复制 JSON', 'Copy JSON'),
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
                        if (scriptCall.trim().isNotEmpty) ...[
                          const SizedBox(height: 10),
                          _SpecActionButton(
                            icon: Icons.terminal_rounded,
                            label: _text(
                              context,
                              '复制脚本调用 JSON',
                              'Copy script call JSON',
                            ),
                            onTap: () => _copyText(
                              context,
                              scriptCall,
                              _text(
                                context,
                                '已复制脚本调用 JSON',
                                'Script call JSON copied',
                              ),
                            ),
                          ),
                        ],
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
                        if (scriptCall.trim().isNotEmpty) ...[
                          const SizedBox(height: 12),
                          _DetailSection(
                            title: _text(
                              context,
                              'Script 调用形态',
                              'Script call shape',
                            ),
                            copyValue: scriptCall,
                            child: _JsonText(text: scriptCall),
                          ),
                        ],
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
    );
  }

  Future<void> _registerFunction() async {
    if (_isImporting) return;
    setState(() {
      _isImporting = true;
      _apiError = null;
    });
    try {
      final result = await AssistsMessageService.importUtgRunLog(
        runId: widget.runId,
        baseUrl: widget.baseUrl,
      );
      if (!mounted) return;
      setState(() {
        _importResult = result;
        _isImporting = false;
      });
      if (result.success) {
        showToast(
          _text(context, '已注册为可执行 API', 'Registered executable API'),
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
      final result = await AssistsMessageService.runUtgFunction(
        functionId: functionId,
        arguments: _defaultArguments,
        baseUrl: widget.baseUrl,
      );
      if (!mounted) return;
      setState(() {
        _runResult = result;
        _isExecuting = false;
      });
      showToast(
        result.success
            ? _text(context, 'API 已开始执行', 'API execution started')
            : _text(context, 'API 执行失败', 'API execution failed'),
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

  String get _scriptCallJson {
    final scriptReuse = spec.json['script_reuse'];
    if (scriptReuse is! Map) {
      return '';
    }
    final callShape = scriptReuse['call_shape'];
    if (callShape == null) {
      return '';
    }
    return const JsonEncoder.withIndent('  ').convert(callShape);
  }

  String get _registeredFunctionId {
    return _firstNonBlank([
      _importResult?.createdFunctionId,
      _firstHitFunctionId,
      _runResult?.functionId,
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

  Map<String, String> get _defaultArguments {
    final rawParameters = spec.json['parameters'];
    if (rawParameters is! List) {
      return const {};
    }
    final arguments = <String, String>{};
    for (final item in rawParameters) {
      if (item is! Map) continue;
      final name = (item['name'] ?? '').toString().trim();
      if (name.isEmpty) continue;
      final defaultValue = item['default'];
      if (defaultValue == null) continue;
      arguments[name] = defaultValue.toString();
    }
    return arguments;
  }

  String get _apiCallJson {
    final functionId = _registeredFunctionId;
    if (functionId.isEmpty) {
      return '';
    }
    return const JsonEncoder.withIndent('  ').convert({
      'method': 'POST',
      'path': '/functions/execute',
      'body': {
        'function_id': functionId,
        'arguments': _defaultArguments,
        'context': {
          'source': 'run_log_reusable_function',
          'source_run_id': widget.runId,
        },
      },
    });
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
            '${runResult!.runFilePath.trim().isNotEmpty ? ' · ${runResult!.runFilePath}' : ''}',
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

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.snapshot});

  final _RunLogStepSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final items = <MapEntry<String, String>>[
      MapEntry(_text(context, '状态', 'Status'), snapshot.statusLabel(context)),
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

class _KeyValueBlock extends StatelessWidget {
  const _KeyValueBlock({required this.values, required this.fallback});

  final Map<String, String> values;
  final String fallback;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    if (values.isEmpty) {
      return _JsonText(text: fallback);
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: values.entries
          .map(
            (entry) => Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 74,
                    child: Text(
                      entry.key,
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textSecondary,
                      ),
                    ),
                  ),
                  Expanded(
                    child: SelectableText(
                      entry.value,
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textPrimary,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          )
          .toList(growable: false),
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
    );
  }

  String get previewText {
    final parts = <String>[];
    final paramsMap = _asStringKeyMap(params);
    final resultMap = _asStringKeyMap(result);
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
    ];

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
  final languageCode = Localizations.maybeLocaleOf(context)?.languageCode;
  return languageCode == 'en' ? en : zh;
}

bool _isEnglish(BuildContext context) {
  return Localizations.maybeLocaleOf(context)?.languageCode == 'en';
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

Color _warningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFFFC04D);
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
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
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
