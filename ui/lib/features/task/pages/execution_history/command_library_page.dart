import 'package:flutter/material.dart';
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
  List<_CommandSummary> _commands = const [];
  bool _isLoading = true;
  String? _error;
  final Set<String> _deletingIds = {};

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
                .map((item) => _CommandSummary.fromMap(
                      Map<String, dynamic>.from(item.map(
                        (k, v) => MapEntry(k.toString(), v),
                      )),
                    ))
                .toList(growable: false)
          : const <_CommandSummary>[];
      setState(() {
        _commands = list;
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

  Future<void> _delete(_CommandSummary cmd) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(_text(context, '删除指令', 'Delete Command')),
        content: Text(
          _text(
            context,
            '确定删除「${cmd.name}」？此操作不可撤销。',
            'Delete "${cmd.name}"? This cannot be undone.',
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

    setState(() => _deletingIds.add(cmd.functionId));
    try {
      final result = await AssistsMessageService.deleteOobReusableFunction(
        cmd.functionId,
      );
      if (!mounted) return;
      if (result['success'] == true || result['deleted'] == true) {
        setState(() {
          _commands = _commands
              .where((c) => c.functionId != cmd.functionId)
              .toList(growable: false);
        });
        showToast(
          _text(context, '已删除指令', 'Command deleted'),
          type: ToastType.success,
        );
      } else {
        showToast(
          _text(context, '删除失败', 'Delete failed'),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _deletingIds.remove(cmd.functionId));
    }
  }

  Future<void> _run(_CommandSummary cmd) async {
    try {
      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: cmd.functionId,
      );
      if (!mounted) return;
      showToast(
        result.success
            ? _text(context, '指令已开始执行', 'Command execution started')
            : (result.errorMessage ?? _text(context, '执行失败', 'Failed')),
        type: result.success ? ToastType.success : ToastType.error,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text(context, '指令库', 'Command Library'),
        primary: true,
        actions: [
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
    if (_commands.isEmpty) {
      return _EmptyState(
        icon: Icons.bolt_outlined,
        title: _text(context, '暂无指令', 'No Commands Yet'),
        subtitle: _text(
          context,
          '成功的 RunLog 会在系统空闲时自动固化；也可以在 RunLog 详情页直接重放。',
          'Successful RunLogs are saved automatically when OOB is idle. You can also replay from RunLog details.',
        ),
        actionLabel: _text(context, '刷新', 'Refresh'),
        onAction: _load,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _commands.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _CommandCard(
          cmd: _commands[index],
          isDeleting: _deletingIds.contains(_commands[index].functionId),
          onRun: () => _run(_commands[index]),
          onDelete: () => _delete(_commands[index]),
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
  List<_CommandSummary> _commands = const [];
  bool _isLoading = true;
  String? _error;
  final Set<String> _deletingIds = {};

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
                .map((item) => _CommandSummary.fromMap(
                      Map<String, dynamic>.from(item.map(
                        (k, v) => MapEntry(k.toString(), v),
                      )),
                    ))
                .toList(growable: false)
          : const <_CommandSummary>[];
      setState(() {
        _commands = list;
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

  Future<void> _delete(_CommandSummary cmd) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(_text(context, '删除指令', 'Delete Command')),
        content: Text(
          _text(
            context,
            '确定删除「${cmd.name}」？此操作不可撤销。',
            'Delete "${cmd.name}"? This cannot be undone.',
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

    setState(() => _deletingIds.add(cmd.functionId));
    try {
      final result = await AssistsMessageService.deleteOobReusableFunction(
        cmd.functionId,
      );
      if (!mounted) return;
      if (result['success'] == true || result['deleted'] == true) {
        setState(() {
          _commands = _commands
              .where((c) => c.functionId != cmd.functionId)
              .toList(growable: false);
        });
        showToast(
          _text(context, '已删除指令', 'Command deleted'),
          type: ToastType.success,
        );
      } else {
        showToast(
          _text(context, '删除失败', 'Delete failed'),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _deletingIds.remove(cmd.functionId));
    }
  }

  Future<void> _run(_CommandSummary cmd) async {
    try {
      final result = await AssistsMessageService.runOobReusableFunction(
        functionId: cmd.functionId,
      );
      if (!mounted) return;
      showToast(
        result.success
            ? _text(context, '指令已开始执行', 'Command execution started')
            : (result.errorMessage ?? _text(context, '执行失败', 'Failed')),
        type: result.success ? ToastType.success : ToastType.error,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    }
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
    if (_commands.isEmpty) {
      return _EmptyState(
        icon: Icons.bolt_outlined,
        title: _text(context, '暂无指令', 'No Commands Yet'),
        subtitle: _text(
          context,
          '成功的 RunLog 会在系统空闲时自动固化；也可以在 RunLog 详情页直接重放。',
          'Successful RunLogs are saved automatically when OOB is idle. You can also replay from RunLog details.',
        ),
        actionLabel: _text(context, '刷新', 'Refresh'),
        onAction: _load,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _commands.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _CommandCard(
          cmd: _commands[index],
          isDeleting: _deletingIds.contains(_commands[index].functionId),
          onRun: () => _run(_commands[index]),
          onDelete: () => _delete(_commands[index]),
        ),
      ),
    );
  }
}

class _CommandCard extends StatelessWidget {
  const _CommandCard({
    required this.cmd,
    required this.isDeleting,
    required this.onRun,
    required this.onDelete,
  });

  final _CommandSummary cmd;
  final bool isDeleting;
  final VoidCallback onRun;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
        borderRadius: BorderRadius.circular(14),
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
        padding: const EdgeInsets.fromLTRB(16, 14, 14, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: palette.accentPrimary.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  alignment: Alignment.center,
                  child: Icon(
                    Icons.bolt_rounded,
                    size: 20,
                    color: palette.accentPrimary,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        cmd.name.isNotEmpty
                            ? cmd.name
                            : cmd.functionId,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: palette.textPrimary,
                          height: 1.35,
                        ),
                      ),
                      if (cmd.description.isNotEmpty &&
                          cmd.description != cmd.name) ...[
                        const SizedBox(height: 3),
                        Text(
                          cmd.description,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 13,
                            color: palette.textSecondary,
                            height: 1.4,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                _Pill(
                  label: _text(
                    context,
                    '${cmd.stepCount} 步',
                    '${cmd.stepCount} steps',
                  ),
                ),
                if (cmd.parameterNames.isNotEmpty) ...[
                  const SizedBox(width: 6),
                  _Pill(
                    label: _text(
                      context,
                      '${cmd.parameterNames.length} 参数',
                      '${cmd.parameterNames.length} params',
                    ),
                  ),
                ],
                const Spacer(),
                if (isDeleting)
                  const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else ...[
                  _IconBtn(
                    icon: Icons.delete_outline_rounded,
                    color: AppColors.alertRed,
                    tooltip: _text(context, '删除', 'Delete'),
                    onTap: onDelete,
                  ),
                  const SizedBox(width: 4),
                  _RunBtn(label: _text(context, '执行', 'Run'), onTap: onRun),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label});

  final String label;

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
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: palette.accentPrimary,
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
  });

  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 36,
          height: 36,
          alignment: Alignment.center,
          child: Icon(icon, size: 20, color: color),
        ),
      ),
    );
  }
}

class _RunBtn extends StatelessWidget {
  const _RunBtn({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: palette.accentPrimary,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: Colors.white,
          ),
        ),
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

class _CommandSummary {
  const _CommandSummary({
    required this.functionId,
    required this.name,
    required this.description,
    required this.stepCount,
    required this.parameterNames,
    required this.registeredAt,
  });

  factory _CommandSummary.fromMap(Map<String, dynamic> map) {
    final params = map['parameter_names'];
    return _CommandSummary(
      functionId: (map['function_id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      stepCount: _asInt(map['step_count']),
      parameterNames: params is List
          ? params.map((e) => e.toString()).toList(growable: false)
          : const [],
      registeredAt: (map['registered_at'] ?? '').toString(),
    );
  }

  final String functionId;
  final String name;
  final String description;
  final int stepCount;
  final List<String> parameterNames;
  final String registeredAt;

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}
