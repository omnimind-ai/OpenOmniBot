import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/l10n/app_text_localizer.dart';
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
      builder: (sheetContext) =>
          _FunctionDetailSheet(group: group, specFuture: future),
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
      builder: (sheetContext) =>
          _FunctionDetailSheet(group: group, specFuture: future),
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
    final summaryText = group.displayDescription.trim();
    final previewText = _buildUserStepPreview(context, function.stepSummaries);
    return Material(
      color: Colors.transparent,
      child: Container(
        decoration: BoxDecoration(
          color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: context.isDarkTheme
              ? const []
              : [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.04),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ],
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 13, 14, 13),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _RunActionButton(isRunning: isRunning, onTap: onRun),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          group.displayName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                            height: 1.35,
                            letterSpacing: 0,
                          ),
                        ),
                        if (summaryText.isNotEmpty) ...[
                          const SizedBox(height: 3),
                          Text(
                            summaryText,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              color: palette.textSecondary,
                              height: 1.35,
                              letterSpacing: 0,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  if (isDeleting)
                    const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  else ...[
                    _IconBtn(
                      icon: Icons.info_outline_rounded,
                      color: palette.textSecondary,
                      backgroundColor: palette.surfaceSecondary,
                      tooltip: _text(context, '详情', 'Details'),
                      onTap: onOpenDetails,
                    ),
                    const SizedBox(width: 4),
                    _IconBtn(
                      icon: Icons.delete_outline_rounded,
                      color: AppColors.alertRed,
                      backgroundColor: AppColors.alertRed.withValues(
                        alpha: 0.08,
                      ),
                      tooltip: _text(context, '删除', 'Delete'),
                      onTap: onDelete,
                    ),
                  ],
                ],
              ),
              if (previewText.isNotEmpty) ...[
                const SizedBox(height: 9),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.subject_rounded,
                      size: 14,
                      color: palette.textTertiary,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        previewText,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textTertiary,
                          height: 1.35,
                          letterSpacing: 0,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 8),
              Wrap(
                spacing: 7,
                runSpacing: 7,
                children: [
                  _MetricPill(
                    label: _text(context, '步骤', 'Steps'),
                    value: function.stepCount.toString(),
                  ),
                  _MetricPill(
                    label: _text(context, '参数', 'Params'),
                    value: function.parameterNames.length.toString(),
                  ),
                  if (group.sourceRunIds.isNotEmpty)
                    _MetricPill(
                      label: 'RunLogs',
                      value: group.sourceRunIds.length.toString(),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RunActionButton extends StatelessWidget {
  const _RunActionButton({required this.isRunning, required this.onTap});

  final bool isRunning;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final label = isRunning
        ? _text(context, '执行中', 'Running')
        : _text(context, '执行', 'Run');
    return Align(
      alignment: Alignment.centerLeft,
      child: FilledButton.icon(
        onPressed: isRunning ? null : onTap,
        icon: isRunning
            ? SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              )
            : const Icon(Icons.play_arrow_rounded, size: 17),
        label: Text(label),
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 32),
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          textStyle: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            letterSpacing: 0,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.accentPrimary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(
        '$label $value',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: palette.textSecondary,
          letterSpacing: 0,
        ),
      ),
    );
  }
}

class _IconBtn extends StatelessWidget {
  const _IconBtn({
    required this.icon,
    required this.color,
    required this.tooltip,
    required this.onTap,
    this.backgroundColor,
  });

  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback onTap;
  final Color? backgroundColor;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: backgroundColor ?? Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          alignment: Alignment.center,
          child: Icon(icon, size: 20, color: color),
        ),
      ),
    );
  }
}

class _FunctionDetailSheet extends StatelessWidget {
  const _FunctionDetailSheet({required this.group, required this.specFuture});

  final _FunctionGroup group;
  final Future<Map<String, dynamic>?> specFuture;

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
              future: specFuture,
              builder: (context, snapshot) {
                final detail = _FunctionDetailSnapshot.from(
                  group: group,
                  spec: snapshot.data,
                );
                if (snapshot.connectionState != ConnectionState.done &&
                    snapshot.data == null) {
                  return const Center(child: CircularProgressIndicator());
                }
                return SingleChildScrollView(
                  controller: controller,
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Center(
                        child: Container(
                          width: 40,
                          height: 4,
                          decoration: BoxDecoration(
                            color: palette.borderSubtle,
                            borderRadius: BorderRadius.circular(99),
                          ),
                        ),
                      ),
                      const SizedBox(height: 18),
                      Text(
                        _text(context, '复用指令详情', 'Reusable Command Details'),
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                          color: palette.textTertiary,
                          letterSpacing: 0.2,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                            width: 42,
                            height: 42,
                            decoration: BoxDecoration(
                              color: palette.accentPrimary.withValues(
                                alpha: 0.12,
                              ),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            alignment: Alignment.center,
                            child: Icon(
                              Icons.functions_rounded,
                              size: 22,
                              color: palette.accentPrimary,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  detail.summary.displayName,
                                  style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.w700,
                                    color: palette.textPrimary,
                                    height: 1.25,
                                  ),
                                ),
                                if (detail
                                    .summary
                                    .displayDescription
                                    .isNotEmpty) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    detail.summary.displayDescription,
                                    style: TextStyle(
                                      fontSize: 13,
                                      color: palette.textSecondary,
                                      height: 1.4,
                                    ),
                                  ),
                                ],
                                const SizedBox(height: 6),
                                Text(
                                  detail.summary.functionId,
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontFamily: 'monospace',
                                    color: palette.textTertiary,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      Wrap(
                        spacing: 7,
                        runSpacing: 7,
                        children: [
                          _MetricPill(
                            label: _text(context, '状态', 'State'),
                            value: _text(context, '已注册', 'Registered'),
                          ),
                          _MetricPill(
                            label: _text(context, '步骤', 'Steps'),
                            value:
                                (detail.steps.isNotEmpty
                                        ? detail.steps.length
                                        : detail.summary.stepCount)
                                    .toString(),
                          ),
                          _MetricPill(
                            label: _text(context, '参数', 'Params'),
                            value: detail.parameters.length.toString(),
                          ),
                          if (detail.group.variantCount > 1)
                            _MetricPill(
                              label: _text(context, '变体', 'Variants'),
                              value: detail.group.variantCount.toString(),
                            ),
                          if (detail.sourceRunIds.isNotEmpty)
                            _MetricPill(
                              label: 'RunLog',
                              value: detail.sourceRunIds.length.toString(),
                            ),
                        ],
                      ),
                      if (detail.sourceRunIds.isNotEmpty) ...[
                        const SizedBox(height: 12),
                        _DetailSectionTitle(zh: '离线来源', en: 'Offline Source'),
                        const SizedBox(height: 10),
                        _FunctionSourcePanel(sourceRunIds: detail.sourceRunIds),
                      ],
                      const SizedBox(height: 18),
                      _DetailSectionTitle(zh: '动作预览', en: 'Action Preview'),
                      const SizedBox(height: 10),
                      _FunctionPreviewPanel(
                        steps: detail.steps,
                        parameters: detail.parameters,
                      ),
                      const SizedBox(height: 18),
                      _DetailSectionTitle(zh: '步骤', en: 'Steps'),
                      const SizedBox(height: 10),
                      if (detail.steps.isEmpty)
                        _DetailEmptyText(
                          text: _text(context, '暂无步骤', 'No steps'),
                        )
                      else
                        Column(
                          children: detail.steps
                              .map(
                                (step) => Padding(
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: _StepDetailTile(step: step),
                                ),
                              )
                              .toList(growable: false),
                        ),
                      const SizedBox(height: 12),
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
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: _ParameterDetailTile(parameter: param),
                                ),
                              )
                              .toList(growable: false),
                        ),
                    ],
                  ),
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

class _FunctionPreviewPanel extends StatelessWidget {
  const _FunctionPreviewPanel({required this.steps, required this.parameters});

  final List<_StepSummary> steps;
  final List<_ParameterSummary> parameters;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final stepPreview = _buildStepPreview(context, steps);
    final parameterPreview = _previewNames(
      parameters.map((parameter) => parameter.name).toList(growable: false),
    );
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.accentPrimary.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (stepPreview.isEmpty)
            Text(
              _text(context, '暂无动作预览', 'No action preview'),
              style: TextStyle(
                fontSize: 12,
                color: palette.textTertiary,
                height: 1.4,
                letterSpacing: 0,
              ),
            )
          else
            Text(
              stepPreview,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
                height: 1.4,
                letterSpacing: 0,
              ),
            ),
          if (parameterPreview.isNotEmpty) ...[
            const SizedBox(height: 7),
            Text(
              '${_text(context, '参数', 'Params')}: $parameterPreview',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 11,
                color: palette.textSecondary,
                height: 1.35,
                letterSpacing: 0,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _FunctionSourcePanel extends StatelessWidget {
  const _FunctionSourcePanel({required this.sourceRunIds});

  final List<String> sourceRunIds;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final preview = sourceRunIds.take(3).join(' · ');
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.accentPrimary.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            Icons.history_toggle_off_rounded,
            size: 17,
            color: palette.accentPrimary,
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _text(
                    context,
                    '由 RunLog 注册，可通过复用指令库本地执行。',
                    'Registered from RunLog and executable from Reusable Commands.',
                  ),
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.35,
                    letterSpacing: 0,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  preview,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: palette.textTertiary,
                    fontFamily: 'monospace',
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StepDetailTile extends StatelessWidget {
  const _StepDetailTile({required this.step});

  final _StepSummary step;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final actionLabel = _localizedToolAction(context, step.displayTool).trim();
    final subtitle = actionLabel.isNotEmpty ? actionLabel : step.displayTool;
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceSecondary : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 26,
            height: 26,
            decoration: BoxDecoration(
              color: palette.accentPrimary.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(7),
            ),
            alignment: Alignment.center,
            child: Text(
              '${step.index + 1}',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: palette.accentPrimary,
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  step.displayTitle,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  subtitle,
                  style: TextStyle(fontSize: 11, color: palette.textSecondary),
                ),
              ],
            ),
          ),
        ],
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
  });

  factory _StepSummary.fromMap(Map<String, dynamic> map) {
    return _StepSummary(
      index: _FunctionSummary._asInt(map['index']),
      id: (map['id'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      kind: (map['kind'] ?? '').toString(),
      executor: (map['executor'] ?? '').toString(),
      tool: (map['tool'] ?? '').toString(),
    );
  }

  final int index;
  final String id;
  final String title;
  final String kind;
  final String executor;
  final String tool;

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

class _FunctionDetailSnapshot {
  const _FunctionDetailSnapshot({
    required this.summary,
    required this.group,
    required this.parameters,
    required this.steps,
    required this.sourceRunIds,
  });

  final _FunctionSummary summary;
  final _FunctionGroup group;
  final List<_ParameterSummary> parameters;
  final List<_StepSummary> steps;
  final List<String> sourceRunIds;

  factory _FunctionDetailSnapshot.from({
    required _FunctionGroup group,
    Map<String, dynamic>? spec,
  }) {
    final raw = spec ?? const <String, dynamic>{};
    final execution = _FunctionSummary._asMap(raw['execution']);
    final parametersRaw = raw['parameters'];
    final stepsRaw = execution['steps'];
    final parameters = parametersRaw is List
        ? parametersRaw
              .whereType<Map>()
              .map(
                (item) => _ParameterSummary.fromMap(
                  Map<String, dynamic>.from(
                    item.map((k, v) => MapEntry(k.toString(), v)),
                  ),
                ),
              )
              .toList(growable: false)
        : group.primary.parameterNames
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
    late final List<_StepSummary> steps;
    if (stepsRaw is List) {
      steps = stepsRaw
          .whereType<Map>()
          .map(
            (item) => _StepSummary.fromMap(
              Map<String, dynamic>.from(
                item.map((k, v) => MapEntry(k.toString(), v)),
              ),
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
    );
  }
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
      final conversionSuccess = result['conversion_success'] == true ||
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

String _buildStepPreview(BuildContext context, List<_StepSummary> steps) {
  final parts = <String>[];
  for (final step in steps.take(3)) {
    final title = step.displayTitle.trim();
    final tool = _localizedToolAction(context, step.displayTool).trim();
    final body = title.isNotEmpty && tool.isNotEmpty && title != tool
        ? '$title · $tool'
        : (title.isNotEmpty ? title : tool);
    if (body.isEmpty) continue;
    parts.add('${step.index + 1}. $body');
  }
  if (parts.isEmpty) return '';
  final preview = parts.join('  ');
  return steps.length > 3 ? '$preview …' : preview;
}

String _buildUserStepPreview(BuildContext context, List<_StepSummary> steps) {
  final parts = <String>[];
  for (final step in steps.take(3)) {
    final title = step.title.trim();
    final tool = step.displayTool.trim();
    final text = title.isNotEmpty
        ? title
        : _localizedToolAction(context, tool).trim();
    if (text.isEmpty) continue;
    parts.add('${step.index + 1}. $text');
  }
  if (parts.isEmpty) return '';
  final preview = parts.join('  ');
  return steps.length > 3 ? '$preview …' : preview;
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

String _localizedToolAction(BuildContext context, String tool) {
  switch (tool.trim().toLowerCase()) {
    case 'open_app':
      return _text(context, '打开应用', 'Open app');
    case 'click':
      return _text(context, '点击', 'Tap');
    case 'long_press':
      return _text(context, '长按', 'Long press');
    case 'input_text':
    case 'type':
      return _text(context, '输入文本', 'Enter text');
    case 'swipe':
      return _text(context, '滑动', 'Swipe');
    case 'press_back':
    case 'back':
      return _text(context, '返回', 'Go back');
    case 'press_home':
    case 'home':
      return _text(context, '回到桌面', 'Go home');
    case 'wait':
      return _text(context, '等待', 'Wait');
  }
  return tool.trim();
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
