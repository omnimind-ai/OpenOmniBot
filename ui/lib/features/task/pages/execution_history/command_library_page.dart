import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/features/task/pages/execution_history/widgets/reusable_command_card.dart';
import 'package:ui/features/task/run_log/run_log_replay_policy.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class CommandLibraryPage extends StatefulWidget {
  const CommandLibraryPage({super.key});

  @override
  State<CommandLibraryPage> createState() => _CommandLibraryPageState();
}

class _CommandLibraryPageState extends State<CommandLibraryPage> {
  List<_FunctionGroup> _functions = const [];
  bool _isLoading = true;
  String? _error;
  final Set<String> _deletingIds = {};
  final Set<String> _runningIds = {};
  bool _isLearning = false;

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
      final result = await AssistsMessageService.listOobReusableFunctions(
        limit: 200,
      );
      if (!mounted) return;
      final raw = result['functions'];
      final list = raw is List
          ? raw
                .whereType<Map>()
                .map(
                  (item) => _FunctionSummary.fromMap(
                    Map<String, dynamic>.from(
                      item.map((k, v) => MapEntry(k.toString(), v)),
                    ),
                  ),
                )
                .toList(growable: false)
          : const <_FunctionSummary>[];
      setState(() {
        _functions = _groupFunctions(list);
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

  Future<void> _delete(_FunctionGroup group) async {
    final function = group.primary;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(_text(context, '删除复用指令', 'Delete Reusable Command')),
        content: Text(
          _text(
            context,
            group.variantCount > 1
                ? '确定删除「${function.displayName}」及其 ${group.variantCount} 个同类来源？此操作不可撤销。'
                : '确定删除「${function.displayName}」？此操作不可撤销。',
            group.variantCount > 1
                ? 'Delete "${function.displayName}" and its ${group.variantCount} variants? This cannot be undone.'
                : 'Delete "${function.displayName}"? This cannot be undone.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(
              _text(context, '删除', 'Delete'),
              style: const TextStyle(color: AppColors.alertRed),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deletingIds.add(group.signature));
    try {
      var deletedCount = 0;
      var allDeleted = true;
      for (final item in group.items) {
        final result = await AssistsMessageService.deleteOobReusableFunction(
          item.functionId,
        );
        final deleted = result['success'] == true || result['deleted'] == true;
        if (deleted) {
          deletedCount += 1;
        } else {
          allDeleted = false;
        }
      }
      if (!mounted) return;
      if (allDeleted && deletedCount == group.items.length) {
        setState(() {
          _functions = _functions
              .where((c) => c.signature != group.signature)
              .toList(growable: false);
        });
        showToast(
          group.variantCount > 1
              ? _text(context, '已删除复用指令组', 'Reusable command group deleted')
              : _text(context, '已删除复用指令', 'Reusable command deleted'),
          type: ToastType.success,
        );
      } else {
        await _load();
        if (!mounted) return;
        showToast(
          _text(context, '部分删除失败', 'Partial delete failed'),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _deletingIds.remove(group.signature));
    }
  }

  Future<void> _startLearning() async {
    await _startHumanTrajectoryLearningFlow(
      context: context,
      isLearning: () => _isLearning,
      setLearning: (value) {
        if (mounted) setState(() => _isLearning = value);
      },
      reload: _load,
    );
  }

  Future<void> _run(_FunctionGroup group) async {
    if (_runningIds.contains(group.signature)) return;
    setState(() => _runningIds.add(group.signature));
    try {
      final spec = await AssistsMessageService.getOobReusableFunction(
        group.primary.functionId,
      );
      if (!mounted) return;
      final arguments = await _resolveRunArguments(context, spec);
      if (!mounted || arguments == null) return;
      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: group.primary.functionId,
        arguments: arguments,
      );
      if (!mounted) return;
      showToast(
        result.success
            ? _runSuccessMessage(context, result)
            : (result.errorMessage ?? _text(context, '执行失败', 'Failed')),
        type: result.success ? ToastType.success : ToastType.error,
        duration: const Duration(seconds: 3),
      );
      if (mounted) setState(() => _runningIds.remove(group.signature));
      if (!result.success && mounted) {
        await showFunctionRunResultSheet(
          context,
          result: result,
          title: _text(context, '复用指令执行结果', 'Reusable command result'),
          arguments: arguments,
        );
      }
      if (result.success && mounted) {
        await _load();
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _runningIds.remove(group.signature));
    }
  }

  Future<void> _openDetails(_FunctionGroup group) async {
    if (!mounted) return;
    final future = AssistsMessageService.getOobReusableFunction(
      group.primary.functionId,
    );
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => _CommandFunctionDetailSheet(
        group: group,
        specFuture: future,
        onSaved: _load,
        onRun: () => _run(group),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text(context, '复用指令库', 'Reusable Commands'),
        primary: true,
        actions: [
          Tooltip(
            message: _text(context, '学习操作', 'Learn Actions'),
            child: IconButton(
              icon: _isLearning
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.gesture_rounded),
              color: palette.textPrimary,
              onPressed: _isLearning ? null : _startLearning,
            ),
          ),
          Tooltip(
            message: _text(context, '刷新', 'Refresh'),
            child: IconButton(
              icon: const Icon(Icons.refresh_rounded),
              color: palette.textPrimary,
              onPressed: _isLoading ? null : _load,
            ),
          ),
        ],
      ),
      body: SafeArea(top: false, child: _buildContent(context)),
    );
  }

  Widget _buildContent(BuildContext context) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _EmptyState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Load Failed'),
        subtitle: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }
    if (_functions.isEmpty) {
      return _EmptyState(
        icon: Icons.bolt_outlined,
        title: _text(context, '暂无复用指令', 'No Reusable Commands Yet'),
        subtitle: _text(
          context,
          '可以直接学习一段完整的人类操作，保存后在这里复用。',
          'Learn a complete human-operated trajectory and reuse it here.',
        ),
        actionLabel: _text(context, '学习操作', 'Learn Actions'),
        onAction: _startLearning,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _functions.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _FunctionCard(
          group: _functions[index],
          isDeleting: _deletingIds.contains(_functions[index].signature),
          isRunning: _runningIds.contains(_functions[index].signature),
          onRun: () => _run(_functions[index]),
          onDelete: () => _delete(_functions[index]),
          onOpenDetails: () => _openDetails(_functions[index]),
        ),
      ),
    );
  }
}

/// Embeddable version — no Scaffold, used inside PageView (e.g. Memory Center).
class CommandLibraryEmbed extends StatefulWidget {
  const CommandLibraryEmbed({super.key});

  @override
  State<CommandLibraryEmbed> createState() => _CommandLibraryEmbedState();
}

class _CommandLibraryEmbedState extends State<CommandLibraryEmbed>
    with AutomaticKeepAliveClientMixin {
  List<_FunctionGroup> _functions = const [];
  bool _isLoading = true;
  String? _error;
  final Set<String> _deletingIds = {};
  final Set<String> _runningIds = {};
  bool _isLearning = false;

  @override
  bool get wantKeepAlive => true;

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
      final result = await AssistsMessageService.listOobReusableFunctions(
        limit: 200,
      );
      if (!mounted) return;
      final raw = result['functions'];
      final list = raw is List
          ? raw
                .whereType<Map>()
                .map(
                  (item) => _FunctionSummary.fromMap(
                    Map<String, dynamic>.from(
                      item.map((k, v) => MapEntry(k.toString(), v)),
                    ),
                  ),
                )
                .toList(growable: false)
          : const <_FunctionSummary>[];
      setState(() {
        _functions = _groupFunctions(list);
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

  Future<void> _delete(_FunctionGroup group) async {
    final function = group.primary;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(_text(context, '删除复用指令', 'Delete Reusable Command')),
        content: Text(
          _text(
            context,
            group.variantCount > 1
                ? '确定删除「${function.displayName}」及其 ${group.variantCount} 个同类来源？此操作不可撤销。'
                : '确定删除「${function.displayName}」？此操作不可撤销。',
            group.variantCount > 1
                ? 'Delete "${function.displayName}" and its ${group.variantCount} variants? This cannot be undone.'
                : 'Delete "${function.displayName}"? This cannot be undone.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(
              _text(context, '删除', 'Delete'),
              style: const TextStyle(color: AppColors.alertRed),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deletingIds.add(group.signature));
    try {
      var deletedCount = 0;
      var allDeleted = true;
      for (final item in group.items) {
        final result = await AssistsMessageService.deleteOobReusableFunction(
          item.functionId,
        );
        final deleted = result['success'] == true || result['deleted'] == true;
        if (deleted) {
          deletedCount += 1;
        } else {
          allDeleted = false;
        }
      }
      if (!mounted) return;
      if (allDeleted && deletedCount == group.items.length) {
        setState(() {
          _functions = _functions
              .where((c) => c.signature != group.signature)
              .toList(growable: false);
        });
        showToast(
          group.variantCount > 1
              ? _text(context, '已删除复用指令组', 'Reusable command group deleted')
              : _text(context, '已删除复用指令', 'Reusable command deleted'),
          type: ToastType.success,
        );
      } else {
        await _load();
        if (!mounted) return;
        showToast(
          _text(context, '部分删除失败', 'Partial delete failed'),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _deletingIds.remove(group.signature));
    }
  }

  Future<void> _run(_FunctionGroup group) async {
    if (_runningIds.contains(group.signature)) return;
    setState(() => _runningIds.add(group.signature));
    try {
      final spec = await AssistsMessageService.getOobReusableFunction(
        group.primary.functionId,
      );
      if (!mounted) return;
      final arguments = await _resolveRunArguments(context, spec);
      if (!mounted || arguments == null) return;
      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: group.primary.functionId,
        arguments: arguments,
      );
      if (!mounted) return;
      showToast(
        result.success
            ? _runSuccessMessage(context, result)
            : (result.errorMessage ?? _text(context, '执行失败', 'Failed')),
        type: result.success ? ToastType.success : ToastType.error,
        duration: const Duration(seconds: 3),
      );
      if (mounted) setState(() => _runningIds.remove(group.signature));
      if (!result.success && mounted) {
        await showFunctionRunResultSheet(
          context,
          result: result,
          title: _text(context, '复用指令执行结果', 'Reusable command result'),
          arguments: arguments,
        );
      }
      if (result.success && mounted) {
        await _load();
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _runningIds.remove(group.signature));
    }
  }

  Future<void> _openDetails(_FunctionGroup group) async {
    if (!mounted) return;
    final future = AssistsMessageService.getOobReusableFunction(
      group.primary.functionId,
    );
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => _CommandFunctionDetailSheet(
        group: group,
        specFuture: future,
        onSaved: _load,
        onRun: () => _run(group),
      ),
    );
  }

  Future<void> _startLearning() async {
    await _startHumanTrajectoryLearningFlow(
      context: context,
      isLearning: () => _isLearning,
      setLearning: (value) {
        if (mounted) setState(() => _isLearning = value);
      },
      reload: _load,
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _EmptyState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Load Failed'),
        subtitle: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }
    if (_functions.isEmpty) {
      return _EmptyState(
        icon: Icons.bolt_outlined,
        title: _text(context, '暂无复用指令', 'No Reusable Commands Yet'),
        subtitle: _text(
          context,
          '可以直接学习一段完整的人类操作，保存后在这里复用。',
          'Learn a complete human-operated trajectory and reuse it here.',
        ),
        actionLabel: _text(context, '学习操作', 'Learn Actions'),
        onAction: _startLearning,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _functions.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _FunctionCard(
          group: _functions[index],
          isDeleting: _deletingIds.contains(_functions[index].signature),
          isRunning: _runningIds.contains(_functions[index].signature),
          onRun: () => _run(_functions[index]),
          onDelete: () => _delete(_functions[index]),
          onOpenDetails: () => _openDetails(_functions[index]),
        ),
      ),
    );
  }
}

class _FunctionCard extends StatelessWidget {
  const _FunctionCard({
    required this.group,
    required this.isDeleting,
    required this.isRunning,
    required this.onRun,
    required this.onDelete,
    required this.onOpenDetails,
  });

  final _FunctionGroup group;
  final bool isDeleting;
  final bool isRunning;
  final VoidCallback onRun;
  final VoidCallback onDelete;
  final VoidCallback onOpenDetails;

  @override
  Widget build(BuildContext context) {
    final function = group.primary;
    final palette = context.omniPalette;
    return ReusableCommandCard(
      title: group.displayName,
      description: group.displayDescription,
      steps: function.stepSummaries
          .map(
            (step) => ReusableCommandStepPreview(
              index: step.index,
              title: step.title,
              tool: step.tool,
              executor: step.executor,
              kind: step.kind,
            ),
          )
          .toList(growable: false),
      stepCount: function.stepCount,
      parameterCount: function.parameterNames.length,
      sourceRunCount: group.sourceRunIds.length,
      isRunning: isRunning,
      onRun: onRun,
      isBusy: isDeleting,
      actions: [
        ReusableCommandCardAction(
          icon: Icons.info_outline_rounded,
          color: palette.textSecondary,
          backgroundColor: palette.surfaceSecondary,
          tooltip: _text(context, '详情', 'Details'),
          onTap: onOpenDetails,
        ),
        ReusableCommandCardAction(
          icon: Icons.delete_outline_rounded,
          color: AppColors.alertRed,
          backgroundColor: AppColors.alertRed.withValues(alpha: 0.08),
          tooltip: _text(context, '删除', 'Delete'),
          onTap: onDelete,
        ),
      ],
    );
  }
}

class _CommandFunctionDetailSheet extends StatefulWidget {
  const _CommandFunctionDetailSheet({
    required this.group,
    required this.specFuture,
    required this.onSaved,
    required this.onRun,
  });

  final _FunctionGroup group;
  final Future<Map<String, dynamic>?> specFuture;
  final Future<void> Function() onSaved;
  final Future<void> Function() onRun;

  @override
  State<_CommandFunctionDetailSheet> createState() =>
      _CommandFunctionDetailSheetState();
}

class _CommandFunctionDetailSheetState
    extends State<_CommandFunctionDetailSheet> {
  Map<String, dynamic>? _savedSpec;
  bool _isSaving = false;

  Future<void> _editStep(
    _FunctionDetailSnapshot detail,
    _StepSummary step,
  ) async {
    if (_isSaving || detail.spec.isEmpty) return;
    final editedStep = await _showFunctionStepEditorDialog(context, step.raw);
    if (editedStep == null || !mounted) return;
    final updatedSpec = _replaceFunctionStep(detail.spec, step, editedStep);
    if (updatedSpec == null) {
      showToast(
        context.l10n.functionLibraryStepEditMissing,
        type: ToastType.error,
      );
      return;
    }
    await _saveSpec(updatedSpec, context.l10n.functionLibraryStepSaved);
  }

  Future<void> _deleteStep(
    _FunctionDetailSnapshot detail,
    _StepSummary step,
  ) async {
    if (_isSaving || detail.steps.length <= 1) {
      showToast(context.l10n.functionLibraryStepKeepOne, type: ToastType.error);
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(dialogContext.l10n.functionLibraryStepDeleteTitle),
        content: Text(
          dialogContext.l10n.functionLibraryStepDeleteConfirm(
            step.displayTitle,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(dialogContext.l10n.omniflowCancel),
          ),
          TextButton.icon(
            icon: const Icon(Icons.delete_outline, size: 18),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            label: Text(dialogContext.l10n.functionLibraryDelete),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final updatedSpec = _removeFunctionStep(detail.spec, step);
    if (updatedSpec == null) {
      showToast(
        context.l10n.functionLibraryStepDeleteMissing,
        type: ToastType.error,
      );
      return;
    }
    await _saveSpec(updatedSpec, context.l10n.functionLibraryStepDeleted);
  }

  Future<void> _addStep(_FunctionDetailSnapshot detail) async {
    if (_isSaving || detail.spec.isEmpty) return;
    final newStep = await _showFunctionStepEditorDialog(
      context,
      _newFunctionStepTemplate(detail.steps.length),
      isNew: true,
    );
    if (newStep == null || !mounted) return;
    final updatedSpec = _appendFunctionStep(detail.spec, newStep);
    if (updatedSpec == null) {
      showToast(
        context.l10n.functionLibraryStepSaveFailed,
        type: ToastType.error,
      );
      return;
    }
    await _saveSpec(updatedSpec, _text(context, '步骤已添加', 'Step added'));
  }

  Future<void> _saveSpec(
    Map<String, dynamic> updatedSpec,
    String successMessage,
  ) async {
    setState(() => _isSaving = true);
    try {
      final result = await AssistsMessageService.registerOobReusableFunction(
        functionSpec: updatedSpec,
      );
      if (!mounted) return;
      if (!result.success) {
        showToast(
          result.errorMessage ?? context.l10n.functionLibraryStepSaveFailed,
          type: ToastType.error,
        );
        return;
      }
      setState(() => _savedSpec = updatedSpec);
      await widget.onSaved();
      if (mounted) {
        showToast(successMessage, type: ToastType.success);
      }
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return DraggableScrollableSheet(
      initialChildSize: 0.86,
      minChildSize: 0.58,
      maxChildSize: 0.96,
      expand: false,
      builder: (context, controller) {
        return Material(
          color: Colors.transparent,
          child: Container(
            decoration: BoxDecoration(
              color: palette.pageBackground,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(16),
              ),
              border: Border(top: BorderSide(color: palette.borderSubtle)),
            ),
            child: FutureBuilder<Map<String, dynamic>?>(
              future: widget.specFuture,
              builder: (context, snapshot) {
                final detail = _FunctionDetailSnapshot.from(
                  group: widget.group,
                  spec: _savedSpec ?? snapshot.data,
                );
                if (snapshot.connectionState != ConnectionState.done &&
                    snapshot.data == null) {
                  return const Center(child: CircularProgressIndicator());
                }
                return Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
                      child: Column(
                        children: [
                          Container(
                            width: 40,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.borderSubtle,
                              borderRadius: BorderRadius.circular(99),
                            ),
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      _text(
                                        context,
                                        '复用指令详情',
                                        'Reusable Command Details',
                                      ),
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                        color: palette.textSecondary,
                                        letterSpacing: 0,
                                      ),
                                    ),
                                    const SizedBox(height: 2),
                                    Text(
                                      detail.summary.displayName,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 16,
                                        fontWeight: FontWeight.w700,
                                        color: palette.textPrimary,
                                        height: 1.25,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              IconButton(
                                icon: const Icon(Icons.close_rounded),
                                color: palette.textSecondary,
                                onPressed: () =>
                                    Navigator.of(context).maybePop(),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, color: palette.borderSubtle),
                    Expanded(
                      child: SingleChildScrollView(
                        controller: controller,
                        padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            ReusableCommandCard(
                              title: detail.summary.displayName,
                              description: detail.summary.displayDescription,
                              steps: detail.steps
                                  .map(
                                    (step) => ReusableCommandStepPreview(
                                      index: step.index,
                                      title: step.displayTitle,
                                      tool: step.displayTool,
                                      executor: step.executor,
                                      kind: step.kind,
                                    ),
                                  )
                                  .toList(growable: false),
                              stepCount: detail.steps.isNotEmpty
                                  ? detail.steps.length
                                  : detail.summary.stepCount,
                              parameterCount: detail.parameters.length,
                              sourceRunCount: detail.sourceRunIds.length,
                              isRunning: _isSaving,
                              onRun: _isSaving ? null : () => widget.onRun(),
                            ),
                            const SizedBox(height: 14),
                            Row(
                              children: [
                                Expanded(
                                  child: _DetailSectionTitle(
                                    zh: '执行步骤',
                                    en: 'Steps',
                                  ),
                                ),
                                Tooltip(
                                  message: _text(context, '添加步骤', 'Add step'),
                                  child: IconButton(
                                    icon: const Icon(
                                      Icons.add_circle_outline_rounded,
                                      size: 20,
                                    ),
                                    visualDensity: VisualDensity.compact,
                                    color: palette.textSecondary,
                                    onPressed:
                                        (!_isSaving && detail.spec.isNotEmpty)
                                        ? () => _addStep(detail)
                                        : null,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            if (detail.steps.isEmpty)
                              _DetailEmptyText(
                                text: _text(context, '暂无步骤', 'No steps'),
                              )
                            else
                              RunLogStyleFunctionStepList(
                                title:
                                    '${_text(context, '执行步骤', 'Step results')} · ${detail.steps.length}',
                                steps: detail.steps
                                    .map((step) => step.raw)
                                    .toList(growable: false),
                                initiallyExpanded: true,
                                actionBuilder: (context, index, rawStep) {
                                  if (index < 0 ||
                                      index >= detail.steps.length) {
                                    return null;
                                  }
                                  final step = detail.steps[index];
                                  final canEdit = !_isSaving;
                                  final canDelete =
                                      canEdit && detail.steps.length > 1;
                                  return Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Tooltip(
                                        message: context
                                            .l10n
                                            .functionLibraryStepEditTitle,
                                        child: IconButton(
                                          icon: const Icon(
                                            Icons.edit_outlined,
                                            size: 18,
                                          ),
                                          visualDensity: VisualDensity.compact,
                                          color: palette.textSecondary,
                                          onPressed: canEdit
                                              ? () => _editStep(detail, step)
                                              : null,
                                        ),
                                      ),
                                      Tooltip(
                                        message: context
                                            .l10n
                                            .functionLibraryStepDeleteTitle,
                                        child: IconButton(
                                          icon: const Icon(
                                            Icons.delete_outline,
                                            size: 18,
                                          ),
                                          visualDensity: VisualDensity.compact,
                                          color: palette.textSecondary,
                                          onPressed: canDelete
                                              ? () => _deleteStep(detail, step)
                                              : null,
                                        ),
                                      ),
                                    ],
                                  );
                                },
                              ),
                            const SizedBox(height: 16),
                            _DetailSectionTitle(zh: '参数', en: 'Parameters'),
                            const SizedBox(height: 10),
                            if (detail.parameters.isEmpty)
                              _DetailEmptyText(
                                text: _text(context, '暂无参数', 'No parameters'),
                              )
                            else
                              Column(
                                children: detail.parameters
                                    .map(
                                      (param) => Padding(
                                        padding: const EdgeInsets.only(
                                          bottom: 8,
                                        ),
                                        child: _ParameterDetailTile(
                                          parameter: param,
                                        ),
                                      ),
                                    )
                                    .toList(growable: false),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        );
      },
    );
  }
}

class _DetailSectionTitle extends StatelessWidget {
  const _DetailSectionTitle({required this.zh, required this.en});

  final String zh;
  final String en;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Text(
      _text(context, zh, en),
      style: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w700,
        color: palette.textTertiary,
        letterSpacing: 0.2,
      ),
    );
  }
}

class _DetailEmptyText extends StatelessWidget {
  const _DetailEmptyText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Text(
        text,
        style: TextStyle(fontSize: 13, color: palette.textTertiary),
      ),
    );
  }
}

class _ParameterDetailTile extends StatelessWidget {
  const _ParameterDetailTile({required this.parameter});

  final _ParameterSummary parameter;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceSecondary : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            parameter.name,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            [
              if (parameter.type.isNotEmpty) parameter.type,
              if (parameter.required) _text(context, '必填', 'required'),
              if (parameter.defaultValue.isNotEmpty)
                '${_text(context, '默认', 'default')}: ${parameter.defaultValue}',
            ].join(' · '),
            style: TextStyle(fontSize: 11, color: palette.textSecondary),
          ),
          if (parameter.description.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              parameter.description,
              style: TextStyle(
                fontSize: 11,
                color: palette.textTertiary,
                height: 1.35,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfaceSecondary
                    : palette.previewFallback,
                shape: BoxShape.circle,
              ),
              alignment: Alignment.center,
              child: Icon(icon, size: 28, color: palette.textSecondary),
            ),
            const SizedBox(height: 16),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13,
                color: palette.textSecondary,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 20),
            GestureDetector(
              onTap: onAction,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(99),
                ),
                child: Text(
                  actionLabel,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.accentPrimary,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FunctionSummary {
  const _FunctionSummary({
    required this.functionId,
    required this.name,
    required this.description,
    required this.cardCount,
    required this.stepCount,
    required this.parameterNames,
    required this.createdAt,
    required this.updatedAt,
    required this.runCount,
    required this.successCount,
    required this.failCount,
    required this.sourceRunIds,
    required this.stepSummaries,
  });

  factory _FunctionSummary.fromMap(Map<String, dynamic> map) {
    final params = map['parameter_names'];
    final runStats = _asMap(map['run_stats']);
    final sourceRunIds = map['source_run_ids'];
    final stepSummaries = map['step_summaries'];
    final stepCount = _asInt(map['step_count']);
    final cardCount = _asInt(map['card_count']);
    return _FunctionSummary(
      functionId: (map['function_id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      cardCount: cardCount > 0 ? cardCount : stepCount,
      stepCount: stepCount,
      parameterNames: params is List
          ? params.map((e) => e.toString()).toList(growable: false)
          : const [],
      createdAt: (map['registered_at'] ?? map['created_at'] ?? '').toString(),
      updatedAt: (map['updated_at'] ?? '').toString(),
      runCount: _asInt(runStats['run_count'] ?? map['run_count']),
      successCount: _asInt(runStats['success_count'] ?? map['success_count']),
      failCount: _asInt(runStats['fail_count'] ?? map['fail_count']),
      sourceRunIds: sourceRunIds is List
          ? sourceRunIds.map((e) => e.toString()).toList(growable: false)
          : const [],
      stepSummaries: stepSummaries is List
          ? stepSummaries
                .whereType<Map>()
                .map(
                  (item) => _StepSummary.fromMap(
                    Map<String, dynamic>.from(
                      item.map((k, v) => MapEntry(k.toString(), v)),
                    ),
                  ),
                )
                .toList(growable: false)
          : const [],
    );
  }

  final String functionId;
  final String name;
  final String description;
  final int cardCount;
  final int stepCount;
  final List<String> parameterNames;
  final String createdAt;
  final String updatedAt;
  final int runCount;
  final int successCount;
  final int failCount;
  final List<String> sourceRunIds;
  final List<_StepSummary> stepSummaries;

  String get displayName {
    final trimmedName = name.trim();
    if (trimmedName.isNotEmpty) return trimmedName;
    return _fallbackNameFromId(functionId);
  }

  String get displayDescription {
    final text = description.trim();
    if (text.isEmpty || text == displayName || text == functionId) return '';
    if (_looksLikeTechnicalId(text)) return '';
    return text;
  }

  String get semanticTitle {
    if (_looksLikeTechnicalSummaryTitle(displayName) &&
        displayDescription.isNotEmpty) {
      return displayDescription;
    }
    return displayName;
  }

  String get semanticDescription {
    if (displayDescription.isEmpty || displayDescription == displayName) {
      return '';
    }
    if (_looksLikeTechnicalSummaryTitle(displayName)) {
      return '';
    }
    return displayDescription;
  }

  String get parameterPreview => _previewNames(parameterNames);

  String get semanticSignature {
    final stepKey = stepSummaries
        .map((step) => step.semanticSignature)
        .join('|');
    final paramKey = parameterNames
        .map(_normalizeSignatureText)
        .where((value) => value.isNotEmpty)
        .join('|');
    return [
      cardCount.toString(),
      stepCount.toString(),
      paramKey,
      stepKey,
    ].join('||');
  }

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  static Map<String, dynamic> _asMap(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return value.map((key, item) => MapEntry(key.toString(), item));
    }
    return const {};
  }

  static bool _looksLikeTechnicalId(String value) {
    final normalized = value.trim();
    return normalized.startsWith('oob_cmd_') ||
        normalized.startsWith('debug_') ||
        RegExp(r'^[0-9a-fA-F]{8}[-_]').hasMatch(normalized);
  }

  static bool _looksLikeTechnicalSummaryTitle(String value) {
    final normalized = value.trim().toLowerCase();
    return normalized.startsWith('debug') ||
        normalized.startsWith('oob_cmd_') ||
        normalized.contains('runlog') ||
        normalized == 'function' ||
        normalized == 'functions';
  }

  static String _fallbackNameFromId(String value) {
    final normalized = value.trim();
    if (normalized.isEmpty) return '复用指令';
    final cleaned = normalized
        .replaceFirst(RegExp(r'^oob_cmd_'), '')
        .replaceFirst(RegExp(r'^debug_'), '')
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .trim();
    if (cleaned.isEmpty) return '复用指令';
    return cleaned
        .split(' ')
        .where((part) => part.isNotEmpty)
        .map((part) => part.length <= 2 ? part.toUpperCase() : part)
        .join(' ');
  }
}

class _StepSummary {
  const _StepSummary({
    required this.index,
    required this.id,
    required this.title,
    required this.kind,
    required this.executor,
    required this.tool,
    required this.raw,
  });

  factory _StepSummary.fromMap(Map<String, dynamic> map, {int? fallbackIndex}) {
    final index = map.containsKey('index')
        ? _FunctionSummary._asInt(map['index'])
        : (fallbackIndex ?? 0);
    final normalized = Map<String, dynamic>.from(map);
    normalized.putIfAbsent('index', () => index);
    normalized.putIfAbsent('step_id', () => map['id'] ?? 'step_${index + 1}');
    normalized.putIfAbsent(
      'summary',
      () =>
          [
                map['summary'],
                map['title'],
                map['tool'],
                map['omniflow_action'],
                map['step_id'],
              ]
              .map((value) => value?.toString().trim() ?? '')
              .firstWhere((value) => value.isNotEmpty, orElse: () => ''),
    );
    return _StepSummary(
      index: index,
      id: (map['id'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      kind: (map['kind'] ?? '').toString(),
      executor: (map['executor'] ?? '').toString(),
      tool: (map['tool'] ?? '').toString(),
      raw: normalized,
    );
  }

  final int index;
  final String id;
  final String title;
  final String kind;
  final String executor;
  final String tool;
  final Map<String, dynamic> raw;

  String get displayTitle {
    final text = title.trim();
    if (text.isNotEmpty) return text;
    final toolText = tool.trim();
    if (toolText.isNotEmpty) return toolText;
    return executor.trim().isNotEmpty ? executor.trim() : 'step';
  }

  String get displayTool {
    final toolText = tool.trim();
    if (toolText.isNotEmpty) return toolText;
    final executorText = executor.trim();
    if (executorText.isNotEmpty) return executorText;
    return kind.trim();
  }

  String get semanticSignature {
    return [
      _normalizeSignatureText(displayTitle),
      _normalizeSignatureText(displayTool),
      _normalizeSignatureText(kind),
      _normalizeSignatureText(executor),
    ].join('|');
  }
}

class _FunctionGroup {
  const _FunctionGroup({required this.signature, required this.items});

  final String signature;
  final List<_FunctionSummary> items;

  _FunctionSummary get primary => items.first;

  int get variantCount => items.length;

  int get runCount => items.fold<int>(0, (sum, item) => sum + item.runCount);

  String get displayName {
    for (final item in items) {
      final name = item.displayName.trim();
      if (name.isNotEmpty &&
          !_FunctionSummary._looksLikeTechnicalSummaryTitle(name)) {
        return name;
      }
    }
    return primary.displayName;
  }

  String get displayDescription {
    for (final item in items) {
      final description = item.semanticDescription.trim();
      if (description.isNotEmpty) return description;
    }
    return primary.semanticDescription;
  }

  String get createdAt {
    final parsed = items
        .map((item) => _parseTimestamp(item.createdAt))
        .whereType<DateTime>()
        .toList(growable: false);
    if (parsed.isEmpty) return primary.createdAt;
    parsed.sort();
    return parsed.first.millisecondsSinceEpoch.toString();
  }

  List<String> get sourceRunIds {
    final ids = <String>{};
    for (final item in items) {
      ids.addAll(item.sourceRunIds);
    }
    return ids.toList(growable: false);
  }
}

class _ParameterSummary {
  const _ParameterSummary({
    required this.name,
    required this.type,
    required this.required,
    required this.description,
    required this.defaultValue,
  });

  factory _ParameterSummary.fromMap(Map<String, dynamic> map) {
    return _ParameterSummary(
      name: (map['name'] ?? '').toString(),
      type: (map['type'] ?? '').toString(),
      required: _asBool(map['required']),
      description: (map['description'] ?? '').toString(),
      defaultValue: (map['default'] ?? '').toString(),
    );
  }

  final String name;
  final String type;
  final bool required;
  final String description;
  final String defaultValue;
}

List<_ParameterSummary> _parameterSummariesFromSpec(
  dynamic rawParameters,
  List<String> fallbackNames,
) {
  if (rawParameters is List) {
    final parameters = rawParameters
        .whereType<Map>()
        .map(
          (item) => _ParameterSummary.fromMap(
            Map<String, dynamic>.from(
              item.map((key, value) => MapEntry(key.toString(), value)),
            ),
          ),
        )
        .toList(growable: false);
    if (parameters.isNotEmpty) return parameters;
  }

  final schema = _FunctionSummary._asMap(rawParameters);
  final properties = _FunctionSummary._asMap(schema['properties']);
  if (properties.isNotEmpty) {
    final requiredNames = schema['required'] is List
        ? (schema['required'] as List).map((item) => item.toString()).toSet()
        : const <String>{};
    return properties.entries
        .map((entry) {
          final property = _FunctionSummary._asMap(entry.value);
          return _ParameterSummary.fromMap({
            ...property,
            'name': entry.key,
            'required': requiredNames.contains(entry.key),
          });
        })
        .toList(growable: false);
  }

  return fallbackNames
      .map(
        (name) => _ParameterSummary(
          name: name,
          type: '',
          required: false,
          description: '',
          defaultValue: '',
        ),
      )
      .toList(growable: false);
}

class _FunctionDetailSnapshot {
  const _FunctionDetailSnapshot({
    required this.summary,
    required this.group,
    required this.parameters,
    required this.steps,
    required this.sourceRunIds,
    required this.spec,
  });

  final _FunctionSummary summary;
  final _FunctionGroup group;
  final List<_ParameterSummary> parameters;
  final List<_StepSummary> steps;
  final List<String> sourceRunIds;
  final Map<String, dynamic> spec;

  factory _FunctionDetailSnapshot.from({
    required _FunctionGroup group,
    Map<String, dynamic>? spec,
  }) {
    final raw = spec ?? const <String, dynamic>{};
    final execution = _FunctionSummary._asMap(raw['execution']);
    final parametersRaw = raw['parameters'];
    final stepsRaw = execution['steps'];
    final parameters = _parameterSummariesFromSpec(
      parametersRaw,
      group.primary.parameterNames,
    );
    late final List<_StepSummary> steps;
    if (stepsRaw is List) {
      steps = stepsRaw
          .asMap()
          .entries
          .where((entry) => entry.value is Map)
          .map(
            (entry) => _StepSummary.fromMap(
              Map<String, dynamic>.from(
                (entry.value as Map).map((k, v) => MapEntry(k.toString(), v)),
              ),
              fallbackIndex: entry.key,
            ),
          )
          .toList(growable: false);
    } else {
      steps = group.primary.stepSummaries;
    }
    return _FunctionDetailSnapshot(
      summary: group.primary,
      group: group,
      parameters: parameters,
      steps: steps,
      sourceRunIds: group.sourceRunIds,
      spec: raw,
    );
  }
}

const _customStepToolValue = '__custom_step_tool__';

enum _FunctionStepArgType { string, integer, number, boolean }

class _FunctionStepArgField {
  const _FunctionStepArgField(
    this.key, {
    this.type = _FunctionStepArgType.string,
    this.hint = '',
  });

  final String key;
  final _FunctionStepArgType type;
  final String hint;
}

class _FunctionStepOperationDefinition {
  const _FunctionStepOperationDefinition({
    required this.value,
    required this.zhLabel,
    required this.enLabel,
    this.argsTemplate = const {},
    this.fields = const [],
  });

  final String value;
  final String zhLabel;
  final String enLabel;
  final Map<String, dynamic> argsTemplate;
  final List<_FunctionStepArgField> fields;

  String label(BuildContext context) => _text(context, zhLabel, enLabel);
}

const _functionStepOperations = <_FunctionStepOperationDefinition>[
  _FunctionStepOperationDefinition(
    value: 'click',
    zhLabel: '点击',
    enLabel: 'Click',
    fields: [
      _FunctionStepArgField(
        'x',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField(
        'y',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField('target_description'),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'long_press',
    zhLabel: '长按',
    enLabel: 'Long press',
    fields: [
      _FunctionStepArgField(
        'x',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField(
        'y',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField('duration_ms', type: _FunctionStepArgType.integer),
      _FunctionStepArgField('target_description'),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'input_text',
    zhLabel: '输入文本',
    enLabel: 'Input text',
    fields: [
      _FunctionStepArgField('text'),
      _FunctionStepArgField(
        'x',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField(
        'y',
        type: _FunctionStepArgType.number,
        hint: '0-1000',
      ),
      _FunctionStepArgField('target_description'),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'swipe',
    zhLabel: '滑动',
    enLabel: 'Swipe',
    fields: [
      _FunctionStepArgField('x1', type: _FunctionStepArgType.number),
      _FunctionStepArgField('y1', type: _FunctionStepArgType.number),
      _FunctionStepArgField('x2', type: _FunctionStepArgType.number),
      _FunctionStepArgField('y2', type: _FunctionStepArgType.number),
      _FunctionStepArgField('duration_ms', type: _FunctionStepArgType.integer),
      _FunctionStepArgField('direction'),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'scroll',
    zhLabel: '滚动',
    enLabel: 'Scroll',
    fields: [
      _FunctionStepArgField('direction'),
      _FunctionStepArgField('x1', type: _FunctionStepArgType.number),
      _FunctionStepArgField('y1', type: _FunctionStepArgType.number),
      _FunctionStepArgField('x2', type: _FunctionStepArgType.number),
      _FunctionStepArgField('y2', type: _FunctionStepArgType.number),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'open_app',
    zhLabel: '打开应用',
    enLabel: 'Open app',
    argsTemplate: {'reset_task': true, 'launch_mode': 'fresh_task'},
    fields: [
      _FunctionStepArgField('package_name'),
      _FunctionStepArgField('reset_task', type: _FunctionStepArgType.boolean),
      _FunctionStepArgField('launch_mode'),
    ],
  ),
  _FunctionStepOperationDefinition(
    value: 'press_back',
    zhLabel: '返回',
    enLabel: 'Back',
  ),
  _FunctionStepOperationDefinition(
    value: 'press_home',
    zhLabel: '回到主页',
    enLabel: 'Home',
  ),
  _FunctionStepOperationDefinition(
    value: 'press_key',
    zhLabel: '按键',
    enLabel: 'Press key',
    fields: [_FunctionStepArgField('key')],
  ),
  _FunctionStepOperationDefinition(
    value: 'hot_key',
    zhLabel: '组合键',
    enLabel: 'Hot key',
    fields: [_FunctionStepArgField('key')],
  ),
  _FunctionStepOperationDefinition(
    value: 'finished',
    zhLabel: '完成',
    enLabel: 'Finished',
    argsTemplate: {'content': 'Done'},
    fields: [_FunctionStepArgField('content')],
  ),
];

Future<Map<String, dynamic>?> _showFunctionStepEditorDialog(
  BuildContext context,
  Map<String, dynamic> rawStep, {
  bool isNew = false,
}) {
  return showDialog<Map<String, dynamic>>(
    context: context,
    builder: (dialogContext) =>
        _FunctionStepEditorDialog(rawStep: rawStep, isNew: isNew),
  );
}

class _FunctionStepEditorDialog extends StatefulWidget {
  const _FunctionStepEditorDialog({required this.rawStep, required this.isNew});

  final Map<String, dynamic> rawStep;
  final bool isNew;

  @override
  State<_FunctionStepEditorDialog> createState() =>
      _FunctionStepEditorDialogState();
}

class _FunctionStepEditorDialogState extends State<_FunctionStepEditorDialog> {
  late final TextEditingController _titleController;
  late final TextEditingController _customToolController;
  late final TextEditingController _argsController;
  final Map<String, TextEditingController> _argControllers = {};
  late String _selectedTool;
  String? _errorText;

  @override
  void initState() {
    super.initState();
    final rawTool =
        (widget.rawStep['tool'] ?? widget.rawStep['omniflow_action'] ?? '')
            .toString()
            .trim();
    final operation = _operationDefinitionForTool(rawTool);
    _selectedTool = operation?.value ?? _customStepToolValue;
    _titleController = TextEditingController(
      text: (widget.rawStep['title'] ?? '').toString(),
    );
    _customToolController = TextEditingController(
      text: operation == null ? rawTool : '',
    );
    _argsController = TextEditingController(
      text: const JsonEncoder.withIndent('  ').convert(
        widget.rawStep['args'] is Map
            ? _stringKeyMap(widget.rawStep['args'])
            : _selectedOperation?.argsTemplate ?? const {},
      ),
    );
    _rebuildArgControllers(_decodedArgsOrEmpty());
  }

  @override
  void dispose() {
    _titleController.dispose();
    _customToolController.dispose();
    _argsController.dispose();
    for (final controller in _argControllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  _FunctionStepOperationDefinition? get _selectedOperation {
    for (final operation in _functionStepOperations) {
      if (operation.value == _selectedTool) return operation;
    }
    return null;
  }

  void _onOperationChanged(String? value) {
    if (value == null || value == _selectedTool) return;
    final previousArgs = _decodedArgsOrEmpty();
    setState(() {
      _selectedTool = value;
      _errorText = null;
      final definition = _selectedOperation;
      if (definition != null) {
        final nextArgs = <String, dynamic>{...definition.argsTemplate};
        for (final field in definition.fields) {
          if (previousArgs.containsKey(field.key)) {
            nextArgs[field.key] = previousArgs[field.key];
          }
        }
        _setArgsJson(nextArgs);
        _rebuildArgControllers(nextArgs);
      } else {
        _rebuildArgControllers(previousArgs);
      }
    });
  }

  void _rebuildArgControllers(Map<String, dynamic> args) {
    for (final controller in _argControllers.values) {
      controller.dispose();
    }
    _argControllers.clear();
    final fields =
        _selectedOperation?.fields ?? const <_FunctionStepArgField>[];
    for (final field in fields) {
      _argControllers[field.key] = TextEditingController(
        text: _argFieldText(args[field.key]),
      );
    }
  }

  void _syncArgsJsonFromFields() {
    final definition = _selectedOperation;
    if (definition == null) return;
    final args = _decodedArgsOrEmpty();
    for (final field in definition.fields) {
      final raw = _argControllers[field.key]?.text.trim() ?? '';
      if (raw.isEmpty) {
        args.remove(field.key);
      } else {
        args[field.key] = _parseArgFieldValue(raw, field.type);
      }
    }
    _setArgsJson(args);
  }

  void _setArgsJson(Map<String, dynamic> args) {
    final pretty = const JsonEncoder.withIndent('  ').convert(args);
    _argsController.value = TextEditingValue(
      text: pretty,
      selection: TextSelection.collapsed(offset: pretty.length),
    );
  }

  Map<String, dynamic> _decodedArgsOrEmpty() {
    try {
      final decoded = jsonDecode(
        _argsController.text.trim().isEmpty ? '{}' : _argsController.text,
      );
      if (decoded is Map) return _stringKeyMap(decoded);
    } catch (_) {
      return const {};
    }
    return const {};
  }

  void _save() {
    if (_selectedOperation != null) {
      _syncArgsJsonFromFields();
    }
    final enteredTool = _selectedTool == _customStepToolValue
        ? _customToolController.text.trim()
        : _selectedTool;
    if (enteredTool.isEmpty) {
      setState(() {
        _errorText = context.l10n.functionLibraryStepToolRequired;
      });
      return;
    }
    final tool =
        RunLogReplayPolicy.omniflowActionForToolName(enteredTool) ??
        enteredTool;
    final dynamic decodedArgs;
    try {
      decodedArgs = jsonDecode(
        _argsController.text.trim().isEmpty ? '{}' : _argsController.text,
      );
    } catch (_) {
      setState(() {
        _errorText = context.l10n.functionLibraryStepArgsInvalid;
      });
      return;
    }
    if (decodedArgs is! Map) {
      setState(() {
        _errorText = context.l10n.functionLibraryStepArgsObjectRequired;
      });
      return;
    }
    Navigator.of(context).pop(
      _buildFunctionStepFromEdit(
        rawStep: widget.rawStep,
        title: _titleController.text.trim(),
        tool: tool,
        args: _stringKeyMap(decodedArgs),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selectedDefinition = _selectedOperation;
    final fields =
        selectedDefinition?.fields ?? const <_FunctionStepArgField>[];
    return AlertDialog(
      title: Text(
        widget.isNew
            ? _text(context, '添加步骤', 'Add step')
            : context.l10n.functionLibraryStepEditTitle,
      ),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _titleController,
                decoration: InputDecoration(
                  labelText: context.l10n.functionLibraryStepTitleLabel,
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
              ),
              const SizedBox(height: 10),
              DropdownButtonFormField<String>(
                value: _selectedTool,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: context.l10n.functionLibraryStepToolLabel,
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
                items: [
                  for (final operation in _functionStepOperations)
                    DropdownMenuItem<String>(
                      value: operation.value,
                      child: Text(
                        '${operation.value} · ${operation.label(context)}',
                      ),
                    ),
                  DropdownMenuItem<String>(
                    value: _customStepToolValue,
                    child: Text(_text(context, '自定义工具', 'Custom tool')),
                  ),
                ],
                onChanged: _onOperationChanged,
              ),
              if (_selectedTool == _customStepToolValue) ...[
                const SizedBox(height: 10),
                TextField(
                  controller: _customToolController,
                  decoration: InputDecoration(
                    labelText: _text(context, '工具名', 'Tool name'),
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ],
              const SizedBox(height: 12),
              Text(
                _text(context, '参数', 'Parameters'),
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: palette.textTertiary,
                  letterSpacing: 0,
                ),
              ),
              const SizedBox(height: 8),
              if (fields.isEmpty)
                Text(
                  _text(context, '此操作无需参数', 'This action has no parameters'),
                  style: TextStyle(fontSize: 12, color: palette.textTertiary),
                )
              else
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    for (final field in fields)
                      SizedBox(
                        width: _fieldWidth(field),
                        child: TextField(
                          controller: _argControllers[field.key],
                          keyboardType: _keyboardTypeForArgField(field.type),
                          decoration: InputDecoration(
                            labelText: field.key,
                            helperText: field.hint.isEmpty ? null : field.hint,
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                          onChanged: (_) => _syncArgsJsonFromFields(),
                        ),
                      ),
                  ],
                ),
              const SizedBox(height: 12),
              TextField(
                controller: _argsController,
                keyboardType: TextInputType.multiline,
                minLines: 5,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace'),
                decoration: InputDecoration(
                  labelText: context.l10n.functionLibraryStepArgsLabel,
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
              ),
              if (_errorText != null) ...[
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    _errorText!,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.alertRed,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(context.l10n.omniflowCancel),
        ),
        FilledButton.icon(
          icon: Icon(
            widget.isNew ? Icons.add_rounded : Icons.save_outlined,
            size: 18,
          ),
          label: Text(
            widget.isNew
                ? _text(context, '添加', 'Add')
                : context.l10n.omniflowSaveConfig,
          ),
          onPressed: _save,
        ),
      ],
    );
  }
}

Map<String, dynamic> _newFunctionStepTemplate(int index) {
  const action = 'click';
  final stepId = 'step_${index + 1}';
  return <String, dynamic>{
    'id': stepId,
    'step_id': stepId,
    'index': index,
    'title': action,
    'summary': action,
    'kind': 'omniflow_action',
    'executor': 'omniflow',
    'omniflow_action': action,
    'local_action': action,
    'model_free': true,
    'scriptable': true,
    'tool': action,
    'callable_tool': action,
    'args': const <String, dynamic>{},
  };
}

_FunctionStepOperationDefinition? _operationDefinitionForTool(String tool) {
  final action =
      RunLogReplayPolicy.omniflowActionForToolName(tool) ??
      RunLogReplayPolicy.normalizeToolName(tool);
  for (final operation in _functionStepOperations) {
    if (operation.value == action) return operation;
  }
  return null;
}

Map<String, dynamic> _stringKeyMap(dynamic value) {
  if (value is! Map) return const {};
  return Map<String, dynamic>.fromEntries(
    value.entries.map(
      (entry) =>
          MapEntry(entry.key.toString(), _jsonCompatibleValue(entry.value)),
    ),
  );
}

dynamic _jsonCompatibleValue(dynamic value) {
  if (value is Map) return _stringKeyMap(value);
  if (value is List) {
    return value.map(_jsonCompatibleValue).toList(growable: false);
  }
  return value;
}

String _argFieldText(dynamic value) {
  if (value == null) return '';
  if (value is String || value is num || value is bool) {
    return value.toString();
  }
  return jsonEncode(value);
}

dynamic _parseArgFieldValue(String raw, _FunctionStepArgType type) {
  switch (type) {
    case _FunctionStepArgType.integer:
      return int.tryParse(raw) ?? raw;
    case _FunctionStepArgType.number:
      return num.tryParse(raw) ?? raw;
    case _FunctionStepArgType.boolean:
      final normalized = raw.trim().toLowerCase();
      if (normalized == 'true' || normalized == '1' || normalized == 'yes') {
        return true;
      }
      if (normalized == 'false' || normalized == '0' || normalized == 'no') {
        return false;
      }
      return raw;
    case _FunctionStepArgType.string:
      return raw;
  }
}

TextInputType _keyboardTypeForArgField(_FunctionStepArgType type) {
  switch (type) {
    case _FunctionStepArgType.integer:
      return TextInputType.number;
    case _FunctionStepArgType.number:
      return const TextInputType.numberWithOptions(decimal: true);
    case _FunctionStepArgType.boolean:
    case _FunctionStepArgType.string:
      return TextInputType.text;
  }
}

double _fieldWidth(_FunctionStepArgField field) {
  if (field.type == _FunctionStepArgType.boolean) return 132;
  if (field.type == _FunctionStepArgType.integer ||
      field.type == _FunctionStepArgType.number) {
    return 136;
  }
  switch (field.key) {
    case 'text':
    case 'content':
    case 'package_name':
    case 'target_description':
      return 214;
    default:
      return 160;
  }
}

Map<String, dynamic> _buildFunctionStepFromEdit({
  required Map<String, dynamic> rawStep,
  required String title,
  required String tool,
  required Map<String, dynamic> args,
}) {
  final updated = _stringKeyMap(rawStep);
  final normalizedTool = RunLogReplayPolicy.normalizeToolName(tool);
  final action = RunLogReplayPolicy.omniflowActionForToolName(tool);
  final effectiveTool = action ?? normalizedTool;
  final effectiveTitle = title.isNotEmpty ? title : effectiveTool;

  updated['title'] = effectiveTitle;
  updated['summary'] = effectiveTitle;
  updated['tool'] = effectiveTool;
  updated['args'] = _stringKeyMap(args);

  if (action != null) {
    updated['kind'] = 'omniflow_action';
    updated['executor'] = 'omniflow';
    updated['omniflow_action'] = action;
    updated['local_action'] = action;
    updated['model_free'] = true;
    updated['scriptable'] = true;
    updated['tool'] = action;
    updated['callable_tool'] = action;
    updated.remove('agent_call');
    updated.remove('fallback_prompt');
    updated.remove('fallbackPrompt');
    updated.remove('source_tool');
    if (RunLogReplayPolicy.isCoordinateAction(action)) {
      updated['coordinate_hook'] = 'omniflow';
    } else {
      updated.remove('coordinate_hook');
    }
  } else {
    updated.remove('omniflow_action');
    updated.remove('local_action');
    updated.remove('coordinate_hook');
    updated['tool'] = effectiveTool;
    updated['callable_tool'] = effectiveTool;
    final existingExecutor = (rawStep['executor'] ?? '').toString().trim();
    if (existingExecutor == 'agent') {
      updated['executor'] = 'agent';
      updated['kind'] = updated['kind'] ?? 'agent_replan';
      updated['model_free'] = false;
      updated['scriptable'] = false;
    } else {
      updated['executor'] = 'tool';
      updated['kind'] = 'tool_call';
      updated['model_free'] = false;
      updated['scriptable'] = true;
    }
  }

  return updated;
}

Map<String, dynamic>? _replaceFunctionStep(
  Map<String, dynamic> spec,
  _StepSummary step,
  Map<String, dynamic> replacement,
) {
  final cloned = jsonDecode(jsonEncode(spec));
  if (cloned is! Map) return null;
  final updatedSpec = Map<String, dynamic>.from(
    cloned.map((key, value) => MapEntry(key.toString(), value)),
  );
  final execution = Map<String, dynamic>.from(
    _FunctionSummary._asMap(updatedSpec['execution']),
  );
  final rawSteps = execution['steps'];
  if (rawSteps is! List) return null;
  final steps = rawSteps
      .whereType<Map>()
      .map(
        (item) => Map<String, dynamic>.from(
          item.map((key, value) => MapEntry(key.toString(), value)),
        ),
      )
      .toList();
  var index = step.id.trim().isEmpty
      ? -1
      : steps.indexWhere((candidate) => candidate['id']?.toString() == step.id);
  if (index < 0 && step.index >= 0 && step.index < steps.length) {
    index = step.index;
  }
  if (index < 0 || index >= steps.length) return null;
  steps[index] = replacement;
  execution['steps'] = steps;
  updatedSpec['execution'] = execution;
  _syncFunctionExecutionCounts(updatedSpec, execution, steps);
  _syncCanonicalActionAfterStepEdit(updatedSpec, index, replacement);
  _updateBoundParameterDefaults(updatedSpec, index, replacement);
  return updatedSpec;
}

Map<String, dynamic>? _appendFunctionStep(
  Map<String, dynamic> spec,
  Map<String, dynamic> step,
) {
  final cloned = jsonDecode(jsonEncode(spec));
  if (cloned is! Map) return null;
  final updatedSpec = Map<String, dynamic>.from(
    cloned.map((key, value) => MapEntry(key.toString(), value)),
  );
  final execution = Map<String, dynamic>.from(
    _FunctionSummary._asMap(updatedSpec['execution']),
  );
  final rawSteps = execution['steps'];
  final steps = rawSteps is List
      ? rawSteps
            .whereType<Map>()
            .map(
              (item) => Map<String, dynamic>.from(
                item.map((key, value) => MapEntry(key.toString(), value)),
              ),
            )
            .toList()
      : <Map<String, dynamic>>[];
  final nextIndex = steps.length;
  final normalizedStep = _functionStepAtIndex(step, nextIndex);
  steps.add(normalizedStep);
  execution['steps'] = steps;
  updatedSpec['execution'] = execution;
  _syncFunctionExecutionCounts(updatedSpec, execution, steps);

  final rawActions = updatedSpec['actions'];
  if (rawActions is List) {
    final actions = List<dynamic>.from(rawActions);
    final action = _canonicalActionFromStep(normalizedStep);
    if (action != null) {
      actions.add(action);
      updatedSpec['actions'] = actions;
    }
  }
  return updatedSpec;
}

Map<String, dynamic> _functionStepAtIndex(
  Map<String, dynamic> rawStep,
  int index,
) {
  final step = _stringKeyMap(rawStep);
  final stepId = 'step_${index + 1}';
  step['id'] = stepId;
  step['step_id'] = stepId;
  step['index'] = index;
  final tool = (step['tool'] ?? step['omniflow_action'] ?? '').toString();
  if ((step['title'] ?? '').toString().trim().isEmpty) {
    step['title'] = tool.trim().isEmpty ? stepId : tool;
  }
  if ((step['summary'] ?? '').toString().trim().isEmpty) {
    step['summary'] = step['title'];
  }
  return step;
}

void _syncFunctionExecutionCounts(
  Map<String, dynamic> spec,
  Map<String, dynamic> execution,
  List<Map<String, dynamic>> steps,
) {
  final omniflowStepCount = steps
      .where((step) => (step['executor'] ?? '').toString() == 'omniflow')
      .length;
  final agentStepCount = steps
      .where((step) => (step['executor'] ?? '').toString() == 'agent')
      .length;
  final scriptableStepCount = steps
      .where((step) => step['scriptable'] == true)
      .length;
  final modelFreeStepCount = steps
      .where((step) => step['model_free'] == true)
      .length;

  execution['step_count'] = steps.length;
  execution['omniflow_step_count'] = omniflowStepCount;
  execution['agent_step_count'] = agentStepCount;
  execution['requires_agent_fallback'] = agentStepCount > 0;

  final metadata = _FunctionSummary._asMap(spec['metadata']);
  if (metadata.isNotEmpty) {
    metadata['step_count'] = steps.length;
    metadata['scriptable_step_count'] = scriptableStepCount;
    metadata['model_free_step_count'] = modelFreeStepCount;
    metadata['omniflow_step_count'] = omniflowStepCount;
    metadata['agent_step_count'] = agentStepCount;
    metadata['requires_agent_fallback'] = agentStepCount > 0;
    spec['metadata'] = metadata;
  }
}

void _syncCanonicalActionAfterStepEdit(
  Map<String, dynamic> spec,
  int index,
  Map<String, dynamic> step,
) {
  final rawActions = spec['actions'];
  if (rawActions is! List || index < 0 || index >= rawActions.length) return;
  final rawAction = rawActions[index];
  final existingAction = rawAction is Map ? _stringKeyMap(rawAction) : null;
  final action = _canonicalActionFromStep(step, existingAction: existingAction);
  if (action == null) return;
  final actions = List<dynamic>.from(rawActions);
  actions[index] = action;
  spec['actions'] = actions;
}

Map<String, dynamic>? _canonicalActionFromStep(
  Map<String, dynamic> step, {
  Map<String, dynamic>? existingAction,
}) {
  final rawTool =
      (step['tool'] ?? step['omniflow_action'] ?? step['callable_tool'] ?? '')
          .toString();
  final action =
      RunLogReplayPolicy.omniflowActionForToolName(rawTool) ??
      RunLogReplayPolicy.normalizeToolName(rawTool);
  final args = _stringKeyMap(step['args']);
  final description = (step['title'] ?? step['summary'] ?? '')
      .toString()
      .trim();
  final output = <String, dynamic>{};
  if (description.isNotEmpty) {
    output['description'] = description;
  }

  switch (action) {
    case 'click':
    case 'long_press':
      output['type'] = action;
      final target = _canonicalPointTarget(args);
      if (target != null) output['target'] = target;
      final prompt = _firstNonBlankArg(args, const [
        'target_description',
        'targetDescription',
        'clickPrompt',
        'label',
      ]);
      if (prompt.isNotEmpty) output['prompt'] = prompt;
      return output;
    case 'input_text':
      output['type'] = 'input_text';
      final existingText = (existingAction?['text'] ?? '').toString();
      if (existingText.contains(r'${')) {
        output['text'] = existingAction!['text'];
      } else {
        final text = _firstPresentArg(args, const ['text', 'content', 'value']);
        if (text != null) output['text'] = text;
      }
      final target = _canonicalPointTarget(args);
      if (target != null && target['kind'] == 'coords') {
        output['target'] = target;
      }
      final prompt = _firstNonBlankArg(args, const [
        'target_description',
        'targetDescription',
        'label',
        'selector',
      ]);
      if (prompt.isNotEmpty) output['prompt'] = prompt;
      return output;
    case 'scroll':
    case 'swipe':
      output['type'] = 'swipe';
      final target = _canonicalSwipeTarget(args);
      if (target != null) output['target'] = target;
      final direction = _firstNonBlankArg(args, const [
        'direction',
        'scroll_direction',
      ]);
      if (direction.isNotEmpty) output['direction'] = direction;
      final distance = _firstPresentArg(args, const [
        'distance',
        'scroll_distance',
      ]);
      if (distance != null) output['distance'] = distance;
      final endX = _firstPresentArg(args, const ['x2', 'end_x', 'endX']);
      final endY = _firstPresentArg(args, const ['y2', 'end_y', 'endY']);
      final duration = _firstPresentArg(args, const [
        'duration_ms',
        'durationMs',
      ]);
      if (endX != null) output['end_x'] = endX;
      if (endY != null) output['end_y'] = endY;
      if (duration != null) output['duration_ms'] = duration;
      return output;
    case 'open_app':
      output['type'] = 'open_app';
      final packageName = _firstNonBlankArg(args, const [
        'package_name',
        'packageName',
      ]);
      if (packageName.isNotEmpty) output['packageName'] = packageName;
      return output;
    case 'press_home':
      output['type'] = 'press_key';
      output['key'] = 'home';
      return output;
    case 'press_back':
      output['type'] = 'press_key';
      output['key'] = 'back';
      return output;
    case 'press_key':
    case 'hot_key':
      output['type'] = 'press_key';
      final key = _firstNonBlankArg(args, const ['key', 'hotkey', 'hot_key']);
      if (key.isNotEmpty) output['key'] = key;
      return output;
    case 'finished':
      output['type'] = 'finished';
      final content = _firstPresentArg(args, const ['content', 'summary']);
      final enableSummary = _firstPresentArg(args, const [
        'enable_summary',
        'enableSummary',
      ]);
      final summaryPrompt = _firstPresentArg(args, const [
        'summary_prompt',
        'summaryPrompt',
      ]);
      if (content != null) output['content'] = content;
      if (enableSummary != null) output['enableSummary'] = enableSummary;
      if (summaryPrompt != null) output['summaryPrompt'] = summaryPrompt;
      return output;
    default:
      output['type'] = 'external_tool';
      output['toolName'] = rawTool.trim().isEmpty ? action : rawTool.trim();
      output['arguments'] = args;
      return output;
  }
}

Map<String, dynamic>? _canonicalPointTarget(Map<String, dynamic> args) {
  final x = _firstPresentArg(args, const ['x', 'center_x', 'centerX']);
  final y = _firstPresentArg(args, const ['y', 'center_y', 'centerY']);
  if (x != null && y != null) {
    return <String, dynamic>{
      'kind': 'coords',
      'x': x,
      'y': y,
      if (_firstPresentArg(args, const ['xml_ref', 'xmlRef']) != null)
        'xmlRef': _firstPresentArg(args, const ['xml_ref', 'xmlRef']),
    };
  }
  final prompt = _firstNonBlankArg(args, const [
    'target_description',
    'targetDescription',
    'clickPrompt',
    'label',
  ]);
  if (prompt.isEmpty) return null;
  return <String, dynamic>{'kind': 'prompt', 'prompt': prompt};
}

Map<String, dynamic>? _canonicalSwipeTarget(Map<String, dynamic> args) {
  final x = _firstPresentArg(args, const ['x1', 'x', 'center_x', 'centerX']);
  final y = _firstPresentArg(args, const ['y1', 'y', 'center_y', 'centerY']);
  if (x == null || y == null) return null;
  return <String, dynamic>{'kind': 'coords', 'x': x, 'y': y};
}

dynamic _firstPresentArg(Map<String, dynamic> args, List<String> keys) {
  for (final key in keys) {
    if (args.containsKey(key) && args[key] != null) return args[key];
  }
  return null;
}

String _firstNonBlankArg(Map<String, dynamic> args, List<String> keys) {
  for (final key in keys) {
    final value = args[key]?.toString().trim() ?? '';
    if (value.isNotEmpty) return value;
  }
  return '';
}

Map<String, dynamic>? _removeFunctionStep(
  Map<String, dynamic> spec,
  _StepSummary step,
) {
  final cloned = jsonDecode(jsonEncode(spec));
  if (cloned is! Map) return null;
  final updatedSpec = Map<String, dynamic>.from(
    cloned.map((key, value) => MapEntry(key.toString(), value)),
  );
  final execution = Map<String, dynamic>.from(
    _FunctionSummary._asMap(updatedSpec['execution']),
  );
  final rawSteps = execution['steps'];
  if (rawSteps is! List || rawSteps.length <= 1) return null;
  final steps = rawSteps
      .whereType<Map>()
      .map(
        (item) => Map<String, dynamic>.from(
          item.map((key, value) => MapEntry(key.toString(), value)),
        ),
      )
      .toList();
  var index = step.id.trim().isEmpty
      ? -1
      : steps.indexWhere((candidate) => candidate['id']?.toString() == step.id);
  if (index < 0 && step.index >= 0 && step.index < steps.length) {
    index = step.index;
  }
  if (index < 0 || index >= steps.length) return null;
  steps.removeAt(index);
  for (var stepIndex = 0; stepIndex < steps.length; stepIndex++) {
    steps[stepIndex]['id'] = 'step_${stepIndex + 1}';
    steps[stepIndex]['index'] = stepIndex;
  }
  execution['steps'] = steps;
  updatedSpec['execution'] = execution;
  _syncFunctionExecutionCounts(updatedSpec, execution, steps);
  final actions = updatedSpec['actions'];
  if (actions is List && index < actions.length) {
    final updatedActions = List<dynamic>.from(actions)..removeAt(index);
    updatedSpec['actions'] = updatedActions;
  }
  _shiftBindingsAfterStepRemoval(updatedSpec, index);
  return updatedSpec;
}

void _updateBoundParameterDefaults(
  Map<String, dynamic> spec,
  int stepIndex,
  Map<String, dynamic> step,
) {
  final args = _FunctionSummary._asMap(step['args']);
  if (args.isEmpty) return;
  final parameters = spec['parameters'];
  if (parameters is List) {
    for (final raw in parameters) {
      if (raw is! Map) continue;
      final parameter = raw.map(
        (key, value) => MapEntry(key.toString(), value),
      );
      final argKey = _boundArgKeyForStep(parameter['bindings'], stepIndex);
      if (argKey != null && args.containsKey(argKey)) {
        raw['default'] = args[argKey];
      }
    }
    return;
  }
  final schema = _FunctionSummary._asMap(parameters);
  final properties = _FunctionSummary._asMap(schema['properties']);
  for (final raw in properties.values) {
    if (raw is! Map) continue;
    final property = raw.map((key, value) => MapEntry(key.toString(), value));
    final argKey = _boundArgKeyForStep(property['x_oob_bindings'], stepIndex);
    if (argKey != null && args.containsKey(argKey)) {
      raw['default'] = args[argKey];
    }
  }
}

String? _boundArgKeyForStep(dynamic rawBindings, int stepIndex) {
  if (rawBindings is! List) return null;
  for (final rawBinding in rawBindings) {
    final match = RegExp(
      r'^\$\.execution\.steps\[(\d+)\]\.args\.([A-Za-z0-9_]+)$',
    ).firstMatch(rawBinding?.toString() ?? '');
    if (match != null && int.tryParse(match.group(1) ?? '') == stepIndex) {
      return match.group(2);
    }
  }
  return null;
}

void _shiftBindingsAfterStepRemoval(
  Map<String, dynamic> spec,
  int removedIndex,
) {
  final parameters = spec['parameters'];
  if (parameters is List) {
    parameters.removeWhere((raw) {
      if (raw is! Map) return false;
      final hadBindings = raw['bindings'] is List;
      final bindings = _shiftBindings(raw['bindings'], removedIndex);
      if (hadBindings) raw['bindings'] = bindings;
      return hadBindings && bindings.isEmpty;
    });
    return;
  }
  final schema = _FunctionSummary._asMap(parameters);
  final properties = _FunctionSummary._asMap(schema['properties']);
  final removedNames = <String>[];
  for (final entry in properties.entries) {
    final raw = entry.value;
    if (raw is! Map) continue;
    final hadBindings = raw['x_oob_bindings'] is List;
    final bindings = _shiftBindings(raw['x_oob_bindings'], removedIndex);
    if (hadBindings) raw['x_oob_bindings'] = bindings;
    if (hadBindings && bindings.isEmpty) removedNames.add(entry.key);
  }
  for (final name in removedNames) {
    properties.remove(name);
  }
  final required = schema['required'];
  if (required is List && removedNames.isNotEmpty) {
    required.removeWhere((name) => removedNames.contains(name?.toString()));
  }
}

List<String> _shiftBindings(dynamic rawBindings, int removedIndex) {
  if (rawBindings is! List) return const [];
  final output = <String>[];
  final bindingPattern = RegExp(
    r'^\$\.(execution\.steps|actions)\[(\d+)\](.*)$',
  );
  for (final value in rawBindings) {
    final binding = value?.toString() ?? '';
    final match = bindingPattern.firstMatch(binding);
    if (match == null) {
      output.add(binding);
      continue;
    }
    final index = int.tryParse(match.group(2) ?? '');
    if (index == null) {
      output.add(binding);
      continue;
    }
    if (index == removedIndex) continue;
    if (index > removedIndex) {
      output.add('\$.${match.group(1)}[${index - 1}]${match.group(3)}');
    } else {
      output.add(binding);
    }
  }
  return output;
}

Future<void> _startHumanTrajectoryLearningFlow({
  required BuildContext context,
  required bool Function() isLearning,
  required ValueChanged<bool> setLearning,
  required Future<void> Function() reload,
}) async {
  if (isLearning()) return;
  if (!context.mounted) return;
  setLearning(true);
  showToast(
    _text(
      context,
      '开始记录操作。请在目标应用中完成点击或滑动，结束后点小万「完成学习」。',
      'Recording started. Perform taps or swipes in the target app, then tap Finish Learning on the floating assistant.',
    ),
    duration: const Duration(seconds: 4),
  );
  try {
    final result = await AssistsMessageService.startHumanTrajectoryLearning();
    if (!context.mounted) return;
    if (result['success'] == true) {
      final functionId = (result['function_id'] ?? '').toString();
      final conversionSuccess =
          result['conversion_success'] == true ||
          result['conversionSuccess'] == true ||
          functionId.isNotEmpty;
      showToast(
        !conversionSuccess
            ? _text(
                context,
                '手动录制完成，RunLog 已生成；复用指令生成失败',
                'Recording completed and RunLog was created; reusable command conversion failed',
              )
            : functionId.isEmpty
            ? _text(context, '已学习为复用指令', 'Learned as reusable command')
            : _text(
                context,
                '已学习为复用指令：$functionId',
                'Learned as reusable command: $functionId',
              ),
        type: ToastType.success,
        duration: const Duration(seconds: 3),
      );
      await reload();
    } else {
      showToast(
        (result['error_message'] ?? _text(context, '学习失败', 'Learning failed'))
            .toString(),
        type: ToastType.error,
      );
    }
  } catch (e) {
    if (!context.mounted) return;
    showToast(e.toString(), type: ToastType.error);
  } finally {
    setLearning(false);
  }
}

Future<Map<String, dynamic>?> _resolveRunArguments(
  BuildContext context,
  Map<String, dynamic>? spec,
) async {
  final arguments = _defaultArgumentsForFunctionSpec(spec);
  final missing = _missingRequiredRunParameters(spec, arguments);
  if (missing.isEmpty) {
    return arguments;
  }
  final manualArguments = await _showRunArgumentsDialog(context, missing);
  if (manualArguments == null) {
    return null;
  }
  return <String, dynamic>{...arguments, ...manualArguments};
}

Map<String, dynamic> _defaultArgumentsForFunctionSpec(
  Map<String, dynamic>? spec,
) {
  final rawParameters = spec?['parameters'];
  if (rawParameters is! List) return const {};
  final arguments = <String, dynamic>{};
  for (final item in rawParameters) {
    if (item is! Map) continue;
    final name = (item['name'] ?? '').toString().trim();
    if (name.isEmpty || !item.containsKey('default')) continue;
    final value = item['default'];
    if (value != null) {
      arguments[name] = value;
    }
  }
  return arguments;
}

List<_ParameterSummary> _missingRequiredRunParameters(
  Map<String, dynamic>? spec,
  Map<String, dynamic> arguments,
) {
  final rawParameters = spec?['parameters'];
  if (rawParameters is! List) return const [];
  final missing = <_ParameterSummary>[];
  for (final item in rawParameters) {
    if (item is! Map) continue;
    final parameter = _ParameterSummary.fromMap(
      Map<String, dynamic>.from(item.map((k, v) => MapEntry(k.toString(), v))),
    );
    if (!parameter.required || parameter.name.trim().isEmpty) continue;
    final current = arguments[parameter.name];
    final hasValue = current != null && current.toString().trim().isNotEmpty;
    if (!hasValue) {
      missing.add(parameter);
    }
  }
  return missing;
}

Future<Map<String, dynamic>?> _showRunArgumentsDialog(
  BuildContext context,
  List<_ParameterSummary> parameters,
) async {
  final controllers = <String, TextEditingController>{
    for (final parameter in parameters)
      parameter.name: TextEditingController(text: parameter.defaultValue),
  };
  try {
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) {
        String? errorText;
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            final palette = ctx.omniPalette;
            return AlertDialog(
              title: Text(_text(ctx, '填写执行参数', 'Run arguments')),
              content: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _text(
                          ctx,
                          '这个复用指令需要补充参数后才能执行。',
                          'This reusable command needs arguments before running.',
                        ),
                        style: TextStyle(
                          fontSize: 13,
                          color: palette.textSecondary,
                          height: 1.35,
                        ),
                      ),
                      const SizedBox(height: 12),
                      for (final parameter in parameters) ...[
                        TextField(
                          controller: controllers[parameter.name],
                          keyboardType: _keyboardTypeForParameter(parameter),
                          decoration: InputDecoration(
                            labelText: parameter.name,
                            helperText: parameter.description.isEmpty
                                ? (parameter.type.isEmpty
                                      ? null
                                      : parameter.type)
                                : parameter.description,
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 10),
                      ],
                      if (errorText != null) ...[
                        Text(
                          errorText!,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.alertRed,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(null),
                  child: Text(_text(ctx, '取消', 'Cancel')),
                ),
                FilledButton(
                  onPressed: () {
                    final values = <String, dynamic>{};
                    final missingNames = <String>[];
                    for (final parameter in parameters) {
                      final raw =
                          controllers[parameter.name]?.text.trim() ?? '';
                      if (raw.isEmpty) {
                        missingNames.add(parameter.name);
                        continue;
                      }
                      values[parameter.name] = _coerceArgumentValue(
                        raw,
                        parameter.type,
                      );
                    }
                    if (missingNames.isNotEmpty) {
                      setDialogState(() {
                        errorText = _text(
                          ctx,
                          '请填写：${missingNames.join(', ')}',
                          'Required: ${missingNames.join(', ')}',
                        );
                      });
                      return;
                    }
                    Navigator.of(ctx).pop(values);
                  },
                  child: Text(_text(ctx, '执行', 'Run')),
                ),
              ],
            );
          },
        );
      },
    );
    return result;
  } finally {
    for (final controller in controllers.values) {
      controller.dispose();
    }
  }
}

TextInputType _keyboardTypeForParameter(_ParameterSummary parameter) {
  final type = parameter.type.toLowerCase();
  if (type.contains('int') ||
      type.contains('number') ||
      type.contains('float') ||
      type.contains('double')) {
    return TextInputType.number;
  }
  return TextInputType.text;
}

dynamic _coerceArgumentValue(String value, String type) {
  final normalizedType = type.trim().toLowerCase();
  if (normalizedType.contains('bool')) {
    final normalizedValue = value.trim().toLowerCase();
    if (normalizedValue == 'true' ||
        normalizedValue == '1' ||
        normalizedValue == 'yes' ||
        normalizedValue == 'y') {
      return true;
    }
    if (normalizedValue == 'false' ||
        normalizedValue == '0' ||
        normalizedValue == 'no' ||
        normalizedValue == 'n') {
      return false;
    }
  }
  if (normalizedType.contains('int')) {
    return int.tryParse(value) ?? value;
  }
  if (normalizedType.contains('number') ||
      normalizedType.contains('float') ||
      normalizedType.contains('double')) {
    return num.tryParse(value) ?? value;
  }
  return value;
}

List<_FunctionGroup> _groupFunctions(List<_FunctionSummary> summaries) {
  final groups = <String, List<_FunctionSummary>>{};
  for (final summary in summaries) {
    groups
        .putIfAbsent(summary.semanticSignature, () => <_FunctionSummary>[])
        .add(summary);
  }
  final result = groups.entries
      .map(
        (entry) => _FunctionGroup(
          signature: entry.key,
          items: entry.value..sort(_compareFunctionSummaries),
        ),
      )
      .toList(growable: false);
  result.sort(_compareFunctionGroups);
  return result;
}

int _compareFunctionSummaries(_FunctionSummary a, _FunctionSummary b) {
  final dateCompare = _compareDateTimeDesc(a.createdAt, b.createdAt);
  if (dateCompare != null) return dateCompare;
  final runCompare = b.runCount.compareTo(a.runCount);
  if (runCompare != 0) return runCompare;
  return a.displayName.compareTo(b.displayName);
}

int _compareFunctionGroups(_FunctionGroup a, _FunctionGroup b) {
  final dateCompare = _compareDateTimeDesc(a.createdAt, b.createdAt);
  if (dateCompare != null) return dateCompare;
  final runCompare = b.runCount.compareTo(a.runCount);
  if (runCompare != 0) return runCompare;
  return a.displayName.compareTo(b.displayName);
}

int? _compareDateTimeDesc(String a, String b) {
  final left = _parseTimestamp(a);
  final right = _parseTimestamp(b);
  if (left == null || right == null) return null;
  return right.compareTo(left);
}

String _normalizeSignatureText(String value) {
  final normalized = value.trim().toLowerCase();
  if (normalized.isEmpty) return '';
  return normalized.replaceAll(RegExp(r'\s+'), ' ');
}

String _previewNames(List<String> values, {int maxItems = 3}) {
  final names = <String>[];
  for (final value in values) {
    final trimmed = value.trim();
    if (trimmed.isEmpty || names.contains(trimmed)) continue;
    names.add(trimmed);
  }
  if (names.isEmpty) return '';
  if (names.length <= maxItems) return names.join(' · ');
  return '${names.take(maxItems).join(' · ')} +${names.length - maxItems}';
}

bool _asBool(dynamic value) {
  if (value is bool) return value;
  if (value is num) return value != 0;
  if (value is String) {
    final normalized = value.trim().toLowerCase();
    return normalized == 'true' || normalized == '1' || normalized == 'yes';
  }
  return false;
}

DateTime? _parseTimestamp(String raw) {
  final text = raw.trim();
  if (text.isEmpty) return null;
  final millis = int.tryParse(text);
  if (millis != null && millis > 0) {
    return DateTime.fromMillisecondsSinceEpoch(millis);
  }
  return DateTime.tryParse(text);
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}

String _runSuccessMessage(BuildContext context, UtgManualRunResult result) {
  if (result.completedVlmFallback) {
    return _text(
      context,
      '复用指令已通过 VLM 执行完成',
      'Reusable command completed by VLM',
    );
  }
  if (result.startedAgentFallback) {
    final taskId = result.taskId;
    return _text(
      context,
      taskId.isEmpty ? '已交给 VLM 继续执行' : '已交给 VLM 继续执行：$taskId',
      taskId.isEmpty ? 'Handed off to VLM' : 'Handed off to VLM: $taskId',
    );
  }
  if (result.completedLocal) {
    return _text(context, '复用指令已本地执行完成', 'Reusable command completed locally');
  }
  return _text(context, '复用指令已开始执行', 'Reusable command started');
}
