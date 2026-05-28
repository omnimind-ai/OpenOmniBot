import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

Future<void> showFunctionRunResultSheet(
  BuildContext context, {
  required UtgManualRunResult result,
  String? title,
  Map<String, dynamic> arguments = const {},
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) => _FunctionRunResultSheet(
      initialResult: result,
      title: title,
      arguments: arguments,
    ),
  );
}

class FunctionRunResultInlinePanel extends StatelessWidget {
  const FunctionRunResultInlinePanel({
    super.key,
    required this.result,
    this.showRawJson = false,
    this.showArtifacts = false,
    this.stepsInitiallyExpanded = false,
  });

  final UtgManualRunResult result;
  final bool showRawJson;
  final bool showArtifacts;
  final bool stepsInitiallyExpanded;

  @override
  Widget build(BuildContext context) {
    final steps = result.stepResults;
    final errorText = _resultErrorText(result);
    final artifacts = _runArtifactEntries(context, result);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _RunResultMetrics(result: result),
        if (errorText.isNotEmpty) ...[
          const SizedBox(height: 10),
          _ResultMessageBox(
            icon: Icons.error_outline_rounded,
            color: _errorColor(context),
            text: errorText,
            copyValue: errorText,
          ),
        ],
        if (showArtifacts && artifacts.isNotEmpty) ...[
          const SizedBox(height: 10),
          _ExpandableResultSection(
            title: _text(context, '运行产物', 'Run artifacts'),
            copyValue: artifacts
                .map((entry) => '${entry.key}: ${entry.value}')
                .join('\n'),
            initiallyExpanded: false,
            child: _RunArtifactList(entries: artifacts),
          ),
        ],
        if (steps.isNotEmpty) ...[
          const SizedBox(height: 10),
          FunctionRunStepList(
            steps: steps,
            initiallyExpanded: stepsInitiallyExpanded,
          ),
        ],
        if (showRawJson) ...[
          const SizedBox(height: 10),
          _ExpandableResultSection(
            title: _text(context, '原始结果', 'Raw result'),
            copyValue: _prettyJson(_stripInternalTiming(result.rawJson)),
            initiallyExpanded: false,
            child: _JsonText(
              text: _prettyJson(_stripInternalTiming(result.rawJson)),
            ),
          ),
        ],
      ],
    );
  }
}

class _FunctionRunResultSheet extends StatefulWidget {
  const _FunctionRunResultSheet({
    required this.initialResult,
    this.title,
    this.arguments = const {},
  });

  final UtgManualRunResult initialResult;
  final String? title;
  final Map<String, dynamic> arguments;

  @override
  State<_FunctionRunResultSheet> createState() =>
      _FunctionRunResultSheetState();
}

class _FunctionRunResultSheetState extends State<_FunctionRunResultSheet> {
  late UtgManualRunResult _result;
  bool _continuing = false;

  @override
  void initState() {
    super.initState();
    _result = widget.initialResult;
  }

  Future<void> _continueWithVlm() async {
    if (_continuing || !_result.canContinueWithVlm) return;
    final functionId = _result.functionId.trim();
    if (functionId.isEmpty) return;
    setState(() => _continuing = true);
    try {
      final next = await AssistsMessageService.runOobReusableFunction(
        functionId: functionId,
        arguments: widget.arguments,
        allowVlmFallback: true,
        localReplayResult: _result.rawJson,
      );
      if (!mounted) return;
      setState(() {
        _result = next;
        _continuing = false;
      });
      showToast(
        next.success
            ? _text(context, '已交给 VLM 继续执行', 'Continuing with VLM')
            : (_resultErrorText(next).isNotEmpty
                  ? _resultErrorText(next)
                  : _text(context, 'VLM 继续执行失败', 'VLM continuation failed')),
        type: next.success ? ToastType.success : ToastType.error,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _continuing = false);
      showToast(error.toString(), type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final result = _result;
    final accent = result.success
        ? _successColor(context)
        : _errorColor(context);
    return DraggableScrollableSheet(
      initialChildSize: 0.70,
      minChildSize: 0.46,
      maxChildSize: 0.94,
      expand: false,
      builder: (context, controller) {
        return Material(
          color: Colors.transparent,
          child: Container(
            decoration: BoxDecoration(
              color: palette.pageBackground,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(18),
              ),
              border: Border(top: BorderSide(color: palette.borderSubtle)),
            ),
            child: Column(
              children: [
                const SizedBox(height: 10),
                Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderSubtle,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 14, 8, 10),
                  child: Row(
                    children: [
                      Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: accent.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Icon(
                          result.success
                              ? Icons.check_circle_outline_rounded
                              : Icons.error_outline_rounded,
                          size: 18,
                          color: accent,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              widget.title?.trim().isNotEmpty == true
                                  ? widget.title!.trim()
                                  : _text(
                                      context,
                                      '复用指令执行结果',
                                      'Reusable command result',
                                    ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w700,
                                color: palette.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              _runSubtitle(context, result),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 12,
                                color: palette.textSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Tooltip(
                        message: _text(
                          context,
                          '复制结果 JSON',
                          'Copy result JSON',
                        ),
                        child: IconButton(
                          icon: const Icon(Icons.content_copy_rounded),
                          color: palette.textSecondary,
                          onPressed: () {
                            Clipboard.setData(
                              ClipboardData(
                                text: _prettyJson(
                                  _stripInternalTiming(result.rawJson),
                                ),
                              ),
                            );
                            showToast(
                              _text(
                                context,
                                '已复制结果 JSON',
                                'Result JSON copied',
                              ),
                              type: ToastType.success,
                            );
                          },
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
                    controller: controller,
                    padding: EdgeInsets.fromLTRB(
                      16,
                      14,
                      16,
                      result.canContinueWithVlm ? 96 : 24,
                    ),
                    child: FunctionRunResultInlinePanel(result: result),
                  ),
                ),
                if (result.canContinueWithVlm)
                  SafeArea(
                    top: false,
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
                      decoration: BoxDecoration(
                        color: palette.pageBackground,
                        border: Border(
                          top: BorderSide(color: palette.borderSubtle),
                        ),
                      ),
                      child: FilledButton.icon(
                        onPressed: _continuing ? null : _continueWithVlm,
                        icon: _continuing
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.smart_toy_outlined, size: 18),
                        label: Text(
                          _continuing
                              ? _text(context, '正在继续执行', 'Continuing')
                              : _text(context, '用 VLM 继续', 'Continue with VLM'),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _RunResultMetrics extends StatelessWidget {
  const _RunResultMetrics({required this.result});

  final UtgManualRunResult result;

  @override
  Widget build(BuildContext context) {
    final status = result.terminalState['status']?.toString().trim() ?? '';
    final steps = result.stepResults;
    final stepCount = result.stepCount > 0 ? result.stepCount : steps.length;
    final successCount = result.successStepCount > 0
        ? result.successStepCount
        : steps.where((step) => step['success'] != false).length;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        _MetricPill(
          label: _text(context, '状态', 'Status'),
          value: _runStateText(context, result, rawStatus: status),
        ),
        if (stepCount > 0)
          _MetricPill(
            label: _text(context, '步骤', 'Steps'),
            value: '$successCount/$stepCount',
          ),
      ],
    );
  }
}

typedef FunctionRunStepActionBuilder =
    Widget? Function(
      BuildContext context,
      int index,
      Map<String, dynamic> step,
    );

class FunctionRunStepList extends StatelessWidget {
  const FunctionRunStepList({
    super.key,
    required this.steps,
    this.title,
    this.initiallyExpanded = false,
    this.showStatusIcon = true,
    this.copyValue,
    this.actionBuilder,
  });

  final List<Map<String, dynamic>> steps;
  final String? title;
  final bool initiallyExpanded;
  final bool showStatusIcon;
  final String? copyValue;
  final FunctionRunStepActionBuilder? actionBuilder;

  @override
  Widget build(BuildContext context) {
    return _ExpandableResultSection(
      title:
          title ??
          '${_text(context, '执行步骤', 'Step results')} · ${steps.length}',
      copyValue: copyValue ?? _prettyJson(_stripInternalTiming(steps)),
      initiallyExpanded: initiallyExpanded,
      child: Column(
        children: steps
            .asMap()
            .entries
            .map(
              (entry) => Padding(
                padding: EdgeInsets.only(
                  bottom: entry.key == steps.length - 1 ? 0 : 8,
                ),
                child: _StepResultTile(
                  index: entry.key,
                  step: entry.value,
                  showStatusIcon: showStatusIcon,
                  actionBuilder: actionBuilder,
                ),
              ),
            )
            .toList(growable: false),
      ),
    );
  }
}

class _StepResultTile extends StatelessWidget {
  const _StepResultTile({
    required this.index,
    required this.step,
    required this.showStatusIcon,
    this.actionBuilder,
  });

  final int index;
  final Map<String, dynamic> step;
  final bool showStatusIcon;
  final FunctionRunStepActionBuilder? actionBuilder;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final success = step['success'] != false;
    final color = success ? _successColor(context) : _errorColor(context);
    final title = _firstNonBlank([
      step['summary'],
      step['title'],
      step['tool'],
      step['step_id'],
    ]);
    final postcondition = _asMap(step['postcondition']);
    final postconditionText = _postconditionText(context, postcondition);
    final errorText = _firstNonBlank([
      step['error_message'],
      step['errorMessage'],
      step['error'],
      step['reason'],
      step['fallback_reason'],
    ]);
    final meta = [
      _firstNonBlank([step['executor']]),
      _firstNonBlank([step['tool']]),
      if (step['needs_agent'] == true)
        _text(context, '需要 Agent', 'Needs agent'),
      if (step['blocked_executor'] != null)
        'blocked=${step['blocked_executor']}',
    ].where((value) => value.trim().isNotEmpty).join(' · ');
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(10, 9, 10, 9),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceSecondary : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(7),
            ),
            alignment: Alignment.center,
            child: Text(
              '${index + 1}',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: color,
              ),
            ),
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title.isEmpty
                      ? _text(context, '未命名步骤', 'Untitled step')
                      : title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.25,
                  ),
                ),
                if (meta.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    meta,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textSecondary,
                    ),
                  ),
                ],
                if (!success && errorText.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    errorText,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: _errorColor(context),
                      height: 1.3,
                    ),
                  ),
                ],
                if (postconditionText.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  Text(
                    postconditionText,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textTertiary,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          if (actionBuilder != null)
            actionBuilder!(context, index, step) ?? const SizedBox.shrink()
          else if (showStatusIcon)
            Icon(
              success
                  ? Icons.check_circle_outline_rounded
                  : Icons.error_outline_rounded,
              size: 16,
              color: color,
            ),
        ],
      ),
    );
  }
}

class _MetricPill extends StatelessWidget {
  const _MetricPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxWidth: MediaQuery.sizeOf(context).width - 32,
      ),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: context.isDarkTheme
              ? palette.surfaceSecondary
              : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(10),
        ),
        child: RichText(
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
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
      ),
    );
  }
}

class _ResultSection extends StatelessWidget {
  const _ResultSection({
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
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 9, 8, 7),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: palette.textPrimary,
                    ),
                  ),
                ),
                if (copyValue?.trim().isNotEmpty == true)
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

class _ExpandableResultSection extends StatefulWidget {
  const _ExpandableResultSection({
    required this.title,
    required this.child,
    this.copyValue,
    this.initiallyExpanded = false,
  });

  final String title;
  final Widget child;
  final String? copyValue;
  final bool initiallyExpanded;

  @override
  State<_ExpandableResultSection> createState() =>
      _ExpandableResultSectionState();
}

class _ExpandableResultSectionState extends State<_ExpandableResultSection> {
  late bool _expanded;

  @override
  void initState() {
    super.initState();
    _expanded = widget.initiallyExpanded;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : Colors.grey.shade50,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(10)),
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 7, 8, 7),
              child: Row(
                children: [
                  Icon(
                    _expanded
                        ? Icons.keyboard_arrow_down_rounded
                        : Icons.keyboard_arrow_right_rounded,
                    size: 18,
                    color: palette.textSecondary,
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      widget.title,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  if (widget.copyValue?.trim().isNotEmpty == true)
                    Tooltip(
                      message: _text(context, '复制', 'Copy'),
                      child: IconButton(
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.content_copy_rounded, size: 16),
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
                ],
              ),
            ),
          ),
          if (_expanded)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              child: widget.child,
            ),
        ],
      ),
    );
  }
}

class _ResultMessageBox extends StatelessWidget {
  const _ResultMessageBox({
    required this.icon,
    required this.color,
    required this.text,
    this.copyValue,
  });

  final IconData icon;
  final Color color;
  final String text;
  final String? copyValue;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(10, 9, 6, 9),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.14 : 0.08),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.22)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 17, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: SelectableText(
              text,
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                height: 1.35,
              ),
            ),
          ),
          if (copyValue?.trim().isNotEmpty == true)
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
    );
  }
}

class _RunArtifactList extends StatelessWidget {
  const _RunArtifactList({required this.entries});

  final List<MapEntry<String, String>> entries;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: entries
          .map(
            (entry) => Padding(
              padding: const EdgeInsets.only(bottom: 5),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 86,
                    child: Text(
                      entry.key,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 11,
                        color: palette.textTertiary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SelectableText(
                      entry.value,
                      style: TextStyle(
                        fontSize: 11,
                        color: palette.textSecondary,
                        fontFamily: 'monospace',
                        height: 1.3,
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

class _JsonText extends StatelessWidget {
  const _JsonText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return SelectableText(
      text,
      style: TextStyle(
        fontSize: 11,
        height: 1.35,
        fontFamily: 'monospace',
        color: palette.textSecondary,
      ),
    );
  }
}

String _runSubtitle(BuildContext context, UtgManualRunResult result) {
  final stepCount = result.stepCount > 0
      ? result.stepCount
      : result.stepResults.length;
  final successCount = result.successStepCount > 0
      ? result.successStepCount
      : result.stepResults.where((step) => step['success'] != false).length;
  final parts = <String>[
    _runStateText(context, result),
    if (stepCount > 0) '$successCount/$stepCount',
  ].where((value) => value.trim().isNotEmpty).toList(growable: false);
  if (parts.isEmpty) {
    return result.success
        ? _text(context, '执行成功', 'Run succeeded')
        : _text(context, '执行失败', 'Run failed');
  }
  return parts.join(' · ');
}

String _runStateText(
  BuildContext context,
  UtgManualRunResult result, {
  String? rawStatus,
}) {
  if (result.completedVlmFallback) {
    return _text(context, 'VLM 执行完成', 'Completed by VLM');
  }
  if (result.startedAgentFallback) {
    return _text(context, '已交给 VLM 继续执行', 'Handed off to VLM');
  }
  if (result.completedLocal) {
    return _text(context, '本地执行完成', 'Completed locally');
  }
  if (result.failed) {
    return _text(context, '执行失败', 'Run failed');
  }
  final status = rawStatus ?? result.executionStatus;
  if (status.trim().isNotEmpty && status != 'success') {
    return status;
  }
  return result.success
      ? _text(context, '执行成功', 'Run succeeded')
      : _text(context, '执行失败', 'Run failed');
}

String _postconditionText(
  BuildContext context,
  Map<String, dynamic> postcondition,
) {
  if (postcondition.isEmpty) return '';
  final kind = _firstNonBlank([postcondition['kind']]);
  final success = _firstNonBlank([postcondition['success']]);
  final score = _firstNonBlank([postcondition['score']]);
  final minScore = _firstNonBlank([postcondition['min_score']]);
  final matched = _firstNonBlank([postcondition['package_matched']]);
  final fallback = _firstNonBlank([postcondition['fallback']]);
  final error = _firstNonBlank([
    postcondition['error_message'],
    postcondition['error'],
    postcondition['message'],
  ]);
  return [
    kind.isEmpty ? _text(context, '后置校验', 'postcondition') : kind,
    if (success.isNotEmpty) 'ok=$success',
    if (score.isNotEmpty) 'score=$score',
    if (minScore.isNotEmpty) 'min=$minScore',
    if (matched.isNotEmpty) 'pkg=$matched',
    if (fallback.isNotEmpty) 'fallback=$fallback',
    if (error.isNotEmpty) error,
  ].join(' · ');
}

String _resultErrorText(UtgManualRunResult result) {
  if (result.success) return '';
  return _firstNonBlank([
    result.errorMessage,
    result.errorCode,
    result.terminalState['error_message'],
    result.terminalState['errorMessage'],
    result.terminalState['error'],
    result.rawJson['error_message'],
    result.rawJson['errorMessage'],
    result.rawJson['error'],
  ]);
}

List<MapEntry<String, String>> _runArtifactEntries(
  BuildContext context,
  UtgManualRunResult result,
) {
  final entries = <MapEntry<String, String>>[];
  void add(String label, String value) {
    final text = value.trim();
    if (text.isNotEmpty) entries.add(MapEntry(label, text));
  }

  add('run_id', _firstNonBlank([result.rawJson['run_id']]));
  add('run_file', result.runFilePath);
  add('run_index', result.runIndexPath);
  add('run_store', result.runStorageDir);
  add(
    _text(context, '目标', 'goal'),
    _firstNonBlank([result.goal, result.rawJson['goal']]),
  );
  return entries;
}

Map<String, dynamic> _asMap(dynamic value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return const <String, dynamic>{};
}

String _firstNonBlank(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return '';
}

dynamic _stripInternalTiming(dynamic value) {
  const blockedKeys = <String>{
    'duration_ms',
    'durationMs',
    'started_at_ms',
    'startedAtMs',
    'finished_at_ms',
    'finishedAtMs',
    'phase_ms',
    'phaseMs',
    'timing',
    'runner_duration_ms',
    'runnerDurationMs',
  };
  if (value is Map<String, dynamic>) {
    return value.map((key, item) {
      if (blockedKeys.contains(key)) {
        return MapEntry<String, dynamic>(key, null);
      }
      return MapEntry<String, dynamic>(key, _stripInternalTiming(item));
    })..removeWhere((_, item) => item == null);
  }
  if (value is Map) {
    return Map<String, dynamic>.fromEntries(
      value.entries
          .where((entry) => !blockedKeys.contains(entry.key.toString()))
          .map(
            (entry) => MapEntry(
              entry.key.toString(),
              _stripInternalTiming(entry.value),
            ),
          ),
    );
  }
  if (value is List) {
    return value.map(_stripInternalTiming).toList(growable: false);
  }
  return value;
}

String _prettyJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(_userVisibleJson(value));
  } catch (_) {
    return _userVisibleString(value.toString());
  }
}

dynamic _userVisibleJson(dynamic value) {
  if (value is String) {
    return _userVisibleString(value);
  }
  if (value == null || value is num || value is bool) {
    return value;
  }
  if (value is Map) {
    return value.map(
      (key, item) =>
          MapEntry(_userVisibleString(key.toString()), _userVisibleJson(item)),
    );
  }
  if (value is Iterable) {
    return value.map(_userVisibleJson).toList(growable: false);
  }
  return _userVisibleString(value.toString());
}

String _userVisibleString(String value) {
  return value
      .replaceAll(RegExp('compile', caseSensitive: false), 'execution')
      .replaceAll('编译', '执行');
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
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
