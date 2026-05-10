import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/omniflow/omniflow_simple_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/execution/execution_card.dart';
import 'package:ui/widgets/execution/execution_detail_view.dart';
import 'package:ui/widgets/execution/execution_models.dart';

class OmniFlowSimpleDashboardPage extends StatefulWidget {
  const OmniFlowSimpleDashboardPage({super.key});

  @override
  State<OmniFlowSimpleDashboardPage> createState() =>
      _OmniFlowSimpleDashboardPageState();
}

class _OmniFlowSimpleDashboardPageState
    extends State<OmniFlowSimpleDashboardPage> {
  final OmniFlowSimpleService _service = OmniFlowSimpleService();
  OmniFlowSimpleSnapshot? _snapshot;
  ExecutionDetail? _selected;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final snapshot = await _service.load();
      if (!mounted) return;
      setState(() {
        _snapshot = snapshot;
        _selected ??= _firstDetail(snapshot);
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast('OmniFlow 加载失败: $error', type: ToastType.error);
    }
  }

  ExecutionDetail? _firstDetail(OmniFlowSimpleSnapshot snapshot) {
    if (snapshot.functions.isNotEmpty) {
      return ExecutionDetail.fromFunction(snapshot.functions.first);
    }
    if (snapshot.runLogs.isNotEmpty) {
      return ExecutionDetail.fromRunLog(snapshot.runLogs.first);
    }
    return null;
  }

  Future<void> _execute(String functionId) async {
    try {
      final result = await _service.executeFunction(functionId);
      final route = (result['route'] ?? '').toString();
      final success = result['success'] == true;
      showToast(
        success ? '执行已开始: $route' : '执行失败',
        type: success ? ToastType.success : ToastType.error,
      );
      await _load();
    } catch (error) {
      showToast('执行失败: $error', type: ToastType.error);
    }
  }

  Future<void> _delete(String functionId) async {
    final deleted = await _service.deleteFunction(functionId);
    if (!mounted) return;
    if (deleted) {
      setState(() => _selected = null);
      await _load();
    }
    showToast(deleted ? '已删除 Function' : '未找到 Function');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final snapshot = _snapshot;
    final functionDetails = (snapshot?.functions ?? const [])
        .map(ExecutionDetail.fromFunction)
        .toList();
    final runLogDetails = (snapshot?.runLogs ?? const [])
        .map(ExecutionDetail.fromRunLog)
        .toList();

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: 'OmniFlow Simple UTG',
        primary: true,
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: () => unawaited(_load()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: _loading && snapshot == null
          ? const Center(child: CircularProgressIndicator())
          : _buildBody(context, functionDetails, runLogDetails),
    );
  }

  Widget _buildBody(
    BuildContext context,
    List<ExecutionDetail> functions,
    List<ExecutionDetail> runLogs,
  ) {
    final palette = context.omniPalette;
    if (functions.isEmpty && runLogs.isEmpty) {
      return Center(
        child: Text(
          '暂无 Simple UTG runlog。跑一次 VLM GUI 任务后会自动出现。',
          style: TextStyle(color: palette.textSecondary),
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 860;
        final list = _buildList(context, functions, runLogs);
        final detail = _selected == null
            ? Center(
                child: Text(
                  '选择一个 Function 或 Run Log',
                  style: TextStyle(color: palette.textSecondary),
                ),
              )
            : ExecutionDetailView(
                detail: _selected!,
                headerActions: _selected!.type == ExecutionDetailType.function
                    ? FilledButton.icon(
                        onPressed: () => unawaited(_execute(_selected!.id)),
                        icon: const Icon(Icons.play_arrow_rounded, size: 18),
                        label: const Text('执行'),
                      )
                    : null,
                onRefresh: _load,
              );
        if (!wide) {
          return Column(
            children: [
              Expanded(child: list),
              Divider(height: 1, color: palette.borderSubtle),
              Expanded(child: detail),
            ],
          );
        }
        return Row(
          children: [
            SizedBox(width: 360, child: list),
            VerticalDivider(width: 1, color: palette.borderSubtle),
            Expanded(child: detail),
          ],
        );
      },
    );
  }

  Widget _buildList(
    BuildContext context,
    List<ExecutionDetail> functions,
    List<ExecutionDetail> runLogs,
  ) {
    final palette = context.omniPalette;
    final snapshot = _snapshot;
    final providerAvailable = snapshot?.status['provider_available'] == true;
    final storePath = (snapshot?.status['store_path'] ?? '').toString();

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(14, 14, 14, 20),
        children: [
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: palette.surfacePrimary,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: Row(
              children: [
                _StatusMetric(label: 'Functions', value: '${functions.length}'),
                const SizedBox(width: 14),
                _StatusMetric(label: 'Run Logs', value: '${runLogs.length}'),
                const SizedBox(width: 14),
                Expanded(
                  child: _StatusMetric(
                    label: 'Provider',
                    value: providerAvailable ? 'online' : 'fallback',
                    alignEnd: true,
                  ),
                ),
              ],
            ),
          ),
          if (storePath.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              storePath,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: palette.textTertiary, fontSize: 11),
            ),
          ],
          const SizedBox(height: 18),
          _SectionHeader(
            title: 'Functions',
            count: functions.length,
            emptyText: 'VLM GUI 任务成功后会生成可 replay 的 simple function。',
          ),
          if (functions.isEmpty)
            _EmptyHint(text: '暂无 Function', palette: palette)
          else
            for (final detail in functions) ...[
              ExecutionCard(
                detail: detail,
                highlighted: _selected?.id == detail.id,
                onTap: () => setState(() => _selected = detail),
                onViewDetail: () => setState(() => _selected = detail),
                onExecute: () => unawaited(_execute(detail.id)),
                onDelete: () => unawaited(_delete(detail.id)),
              ),
              const SizedBox(height: 12),
            ],
          const SizedBox(height: 8),
          _SectionHeader(
            title: 'Run Logs',
            count: runLogs.length,
            emptyText: 'Runlog 由 VLM GUI task terminal result 自动写入。',
          ),
          if (runLogs.isEmpty)
            _EmptyHint(text: '暂无 Run Log', palette: palette)
          else
            for (final detail in runLogs) ...[
              ExecutionCard(
                detail: detail,
                highlighted: _selected?.id == detail.id,
                onTap: () => setState(() => _selected = detail),
                onViewDetail: () => setState(() => _selected = detail),
              ),
              const SizedBox(height: 12),
            ],
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.title,
    required this.count,
    required this.emptyText,
  });

  final String title;
  final int count;
  final String emptyText;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          Text(
            title,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              '$count',
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 11,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          if (count == 0) ...[
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                emptyText,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: palette.textTertiary, fontSize: 11),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _StatusMetric extends StatelessWidget {
  const _StatusMetric({
    required this.label,
    required this.value,
    this.alignEnd = false,
  });

  final String label;
  final String value;
  final bool alignEnd;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: alignEnd
          ? CrossAxisAlignment.end
          : CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
            color: palette.textTertiary,
            fontSize: 11,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint({required this.text, required this.palette});

  final String text;
  final dynamic palette;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        text,
        style: TextStyle(color: palette.textSecondary, fontSize: 13),
      ),
    );
  }
}
