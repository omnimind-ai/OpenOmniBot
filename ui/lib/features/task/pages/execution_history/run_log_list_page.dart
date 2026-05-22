import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class RunLogListPage extends StatefulWidget {
  const RunLogListPage({super.key, this.baseUrl});

  final String? baseUrl;

  @override
  State<RunLogListPage> createState() => _RunLogListPageState();
}

class _RunLogListPageState extends State<RunLogListPage> {
  static const int _limit = 50;

  UtgRunLogsSnapshot? _snapshot;
  bool _isLoading = true;
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
      final snapshot = await AssistsMessageService.getInternalRunLogs(
        limit: _limit,
      );
      if (!mounted) return;
      setState(() {
        _snapshot = snapshot;
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
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: l10n.executionHistoryTitle,
        primary: true,
        actions: [
          Tooltip(
            message: l10n.localModelsRefresh,
            child: IconButton(
              icon: const Icon(Icons.refresh_rounded),
              color: palette.textPrimary,
              onPressed: _isLoading ? null : _load,
            ),
          ),
        ],
      ),
      body: SafeArea(top: false, child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    final l10n = context.l10n;
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _RunLogEmptyState(
        icon: Icons.error_outline_rounded,
        title: AppTextLocalizer.text(
          '加载运行日志失败',
          locale: Localizations.localeOf(context),
        ),
        subtitle: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }

    final runs = _snapshot?.runs ?? const <UtgRunLogSummary>[];
    if (runs.isEmpty) {
      return _RunLogEmptyState(
        icon: Icons.route_outlined,
        title: l10n.executionHistoryEmpty,
        subtitle: _text(
          context,
          '执行一次 Agent 或 VLM 任务后会出现在这里。',
          'Execution records will appear here after an Agent or VLM task.',
        ),
        actionLabel: l10n.localModelsRefresh,
        onAction: _load,
      );
    }

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 24),
        itemCount: runs.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _RunLogListItem(
          run: runs[index],
          onTap: () => _openRunLog(runs[index]),
        ),
      ),
    );
  }

  void _openRunLog(UtgRunLogSummary run) {
    if (run.runId.trim().isEmpty) {
      showToast(
        _text(context, 'Run ID 为空，无法打开', 'Missing run ID'),
        type: ToastType.warning,
      );
      return;
    }
    GoRouterManager.push(
      '/task/run_log_timeline',
      extra: {
        'runId': run.runId,
        'title': _titleForRun(context, run),
        if (widget.baseUrl != null && widget.baseUrl!.trim().isNotEmpty)
          'baseUrl': widget.baseUrl!.trim(),
      },
    );
  }
}

class _RunLogListItem extends StatelessWidget {
  const _RunLogListItem({required this.run, required this.onTap});

  final UtgRunLogSummary run;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final title = _titleForRun(context, run);
    final statusColor = run.success
        ? _successColor(context)
        : _errorColor(context);
    final meta = [
      if (run.stepCount > 0) l10n.runLogTimelineStepCount(run.stepCount),
      if (run.toolName.trim().isNotEmpty) run.toolName.trim(),
      if (run.compileStatus.trim().isNotEmpty) run.compileStatus.trim(),
      if (_formatDuration(run.durationMs).isNotEmpty)
        _formatDuration(run.durationMs),
      if (run.tokenUsageTotal != null) _formatTokenUsage(run.tokenUsageTotal!),
    ].join(' · ');

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Ink(
          decoration: BoxDecoration(
            color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      margin: const EdgeInsets.only(top: 7),
                      decoration: BoxDecoration(
                        color: statusColor,
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Icon(
                      Icons.chevron_right_rounded,
                      color: palette.textTertiary,
                      size: 22,
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (meta.isNotEmpty)
                  Text(
                    meta,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                if (run.runId.trim().isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    run.runId.trim(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 11,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
                if (_timeLabel(run).isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    _timeLabel(run),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: palette.textTertiary, fontSize: 11),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RunLogEmptyState extends StatelessWidget {
  const _RunLogEmptyState({
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
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 34, color: palette.textTertiary),
            const SizedBox(height: 12),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 16),
            TextButton.icon(
              onPressed: onAction,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: Text(actionLabel),
            ),
          ],
        ),
      ),
    );
  }
}

String _titleForRun(BuildContext context, UtgRunLogSummary run) {
  final title = _firstNonBlank([
    run.goal,
    run.operationDescription,
    run.compileSummary,
    run.selectorLabel,
    run.runId,
  ]);
  return title.isEmpty ? context.l10n.runLogTimelineUnknown : title;
}

String _timeLabel(UtgRunLogSummary run) {
  final started = _parseDate(run.startedAt);
  final finished = _parseDate(run.finishedAt);
  if (started == null && finished == null) {
    return '';
  }
  final formatter = DateFormat('yyyy/MM/dd HH:mm:ss');
  if (started != null && finished != null) {
    return '${formatter.format(started)} - ${formatter.format(finished)}';
  }
  return formatter.format(started ?? finished!);
}

DateTime? _parseDate(String raw) {
  final value = raw.trim();
  if (value.isEmpty) return null;
  return DateTime.tryParse(value)?.toLocal();
}

String _formatDuration(num? durationMs) {
  if (durationMs == null || durationMs <= 0) return '';
  if (durationMs < 1000) {
    return '${durationMs.round()}ms';
  }
  final seconds = durationMs / 1000;
  if (seconds < 60) {
    return '${seconds.toStringAsFixed(seconds >= 10 ? 0 : 1)}s';
  }
  final minutes = seconds / 60;
  return '${minutes.toStringAsFixed(minutes >= 10 ? 0 : 1)}min';
}

String _formatTokenUsage(int totalTokens) {
  if (totalTokens <= 0) return '';
  if (totalTokens >= 1000) {
    return '${(totalTokens / 1000).toStringAsFixed(totalTokens >= 10000 ? 1 : 2)}k tokens';
  }
  return '$totalTokens tokens';
}

String _firstNonBlank(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
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
      : const Color(0xFF19A974);
}

Color _errorColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF7A7A)
      : const Color(0xFFE14C4C);
}
