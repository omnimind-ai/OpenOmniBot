import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/execution/execution_models.dart';
import 'package:ui/widgets/execution/execution_stats_panel.dart';
import 'package:ui/widgets/execution/execution_step_tile.dart';

/// 执行详情的通用视图组件
/// 可用于显示 Function 或 RunLog 的详情
class ExecutionDetailView extends StatefulWidget {
  final ExecutionDetail detail;
  final Widget? headerActions;
  final Widget? footerActions;
  final bool showStats;
  final bool showTimeline;
  final bool showAssetRefs;
  final VoidCallback? onRefresh;

  const ExecutionDetailView({
    super.key,
    required this.detail,
    this.headerActions,
    this.footerActions,
    this.showStats = true,
    this.showTimeline = true,
    this.showAssetRefs = true,
    this.onRefresh,
  });

  @override
  State<ExecutionDetailView> createState() => _ExecutionDetailViewState();
}

class _ExecutionDetailViewState extends State<ExecutionDetailView> {
  final Set<int> _expandedSteps = {};

  @override
  Widget build(BuildContext context) {
    final detail = widget.detail;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 头部信息卡片
          _buildHeaderCard(context),

          // 统计面板
          if (widget.showStats && detail.stats != null) ...[
            const SizedBox(height: 12),
            ExecutionStatsPanel(stats: detail.stats!),
          ],

          // 资产引用
          if (widget.showAssetRefs &&
              detail.assetRefs != null &&
              detail.assetRefs!.hasAssets) ...[
            const SizedBox(height: 12),
            _buildAssetRefsPanel(context),
          ],

          // 时间线
          if (widget.showTimeline && detail.steps.isNotEmpty) ...[
            const SizedBox(height: 16),
            _buildTimelineSection(context),
          ],

          // 底部操作
          if (widget.footerActions != null) ...[
            const SizedBox(height: 16),
            widget.footerActions!,
          ],
        ],
      ),
    );
  }

  Widget _buildHeaderCard(BuildContext context) {
    final palette = context.omniPalette;
    final detail = widget.detail;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 16,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题行
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // ID
                    Text(
                      detail.id,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    // 类型标签
                    Row(
                      children: [
                        _buildPill(
                          context,
                          detail.type == ExecutionDetailType.function
                              ? AppTextLocalizer.choose(
                                  zh: '复用指令',
                                  en: 'Reusable command',
                                  locale: Localizations.maybeLocaleOf(context),
                                )
                              : 'RunLog',
                          backgroundColor:
                              detail.type == ExecutionDetailType.function
                              ? const Color(0xFFE8F0FF)
                              : const Color(0xFFF0E8FF),
                          textColor: detail.type == ExecutionDetailType.function
                              ? const Color(0xFF1E40AF)
                              : const Color(0xFF6B21A8),
                        ),
                        const SizedBox(width: 8),
                        _buildPill(context, '${detail.stepCount} steps'),
                        if (detail.success != null) ...[
                          const SizedBox(width: 8),
                          _buildPill(
                            context,
                            detail.success! ? '成功' : '失败',
                            backgroundColor: detail.success!
                                ? const Color(0xFFE8F7EE)
                                : const Color(0xFFFDECEC),
                            textColor: detail.success!
                                ? const Color(0xFF117A37)
                                : const Color(0xFFB42318),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
              if (widget.headerActions != null) widget.headerActions!,
            ],
          ),

          // 目标/描述
          if (detail.goal != null && detail.goal!.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(
              detail.goal!,
              style: TextStyle(
                fontSize: 14,
                color: palette.textSecondary,
                height: 1.5,
              ),
            ),
          ],

          // 应用信息
          if ((detail.appName != null && detail.appName!.isNotEmpty) ||
              (detail.packageName != null &&
                  detail.packageName!.isNotEmpty)) ...[
            const SizedBox(height: 10),
            Row(
              children: [
                Icon(Icons.android, size: 14, color: palette.textTertiary),
                const SizedBox(width: 6),
                Text(
                  [
                    detail.appName,
                    detail.packageName,
                  ].where((e) => e != null && e.isNotEmpty).join(' · '),
                  style: TextStyle(fontSize: 12, color: palette.textTertiary),
                ),
              ],
            ),
          ],

          // 时间信息
          if (detail.durationMs != null || detail.startedAt != null) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                if (detail.durationMs != null) ...[
                  Icon(
                    Icons.timer_outlined,
                    size: 14,
                    color: palette.textTertiary,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    detail.durationText,
                    style: TextStyle(fontSize: 12, color: palette.textTertiary),
                  ),
                  const SizedBox(width: 12),
                ],
                if (detail.startedAt != null) ...[
                  Icon(Icons.schedule, size: 14, color: palette.textTertiary),
                  const SizedBox(width: 4),
                  Text(
                    _formatDateTime(detail.startedAt!),
                    style: TextStyle(fontSize: 12, color: palette.textTertiary),
                  ),
                ],
              ],
            ),
          ],

          if (detail.tokenUsage?.compactText.isNotEmpty == true) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _buildMetricChip(
                  context,
                  icon: Icons.data_usage_rounded,
                  label: 'Token',
                  value: detail.tokenUsage!.compactText,
                ),
                if (detail.tokenUsage!.stepCount != null)
                  _buildMetricChip(
                    context,
                    icon: Icons.timeline_rounded,
                    label: '统计步数',
                    value: '${detail.tokenUsage!.stepCount}',
                  ),
              ],
            ),
          ],

          // 关联的 run_ids
          if (detail.sourceRunIds.isNotEmpty) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.link, size: 14, color: palette.textTertiary),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    '关联执行: ${detail.sourceRunIds.length} 条',
                    style: TextStyle(fontSize: 12, color: palette.textTertiary),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildMetricChip(
    BuildContext context, {
    required IconData icon,
    required String label,
    required String value,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: palette.textTertiary),
          const SizedBox(width: 5),
          Text(
            '$label ',
            style: TextStyle(fontSize: 12, color: palette.textTertiary),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: palette.textSecondary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAssetRefsPanel(BuildContext context) {
    final palette = context.omniPalette;
    final assetRefs = widget.detail.assetRefs!;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '资产文件',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (assetRefs.xmlRefs.isNotEmpty)
                _buildPill(
                  context,
                  '${assetRefs.xmlRefs.length} XML',
                  backgroundColor: const Color(0xFFE8F0FF),
                  textColor: const Color(0xFF1E40AF),
                ),
              if (assetRefs.screenshotRefs.isNotEmpty)
                _buildPill(
                  context,
                  '${assetRefs.screenshotRefs.length} 截图',
                  backgroundColor: const Color(0xFFF0E8FF),
                  textColor: const Color(0xFF6B21A8),
                ),
            ],
          ),
          if (assetRefs.functionDir != null) ...[
            const SizedBox(height: 8),
            Text(
              assetRefs.functionDir!,
              style: TextStyle(
                fontSize: 11,
                color: palette.textTertiary,
                fontFamily: 'monospace',
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildTimelineSection(BuildContext context) {
    final palette = context.omniPalette;
    final steps = widget.detail.steps;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              '执行步骤',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(width: 8),
            _buildPill(context, '${steps.length}'),
            const Spacer(),
            TextButton(
              onPressed: () {
                setState(() {
                  if (_expandedSteps.length == steps.length) {
                    _expandedSteps.clear();
                  } else {
                    _expandedSteps.addAll(
                      List.generate(steps.length, (i) => i),
                    );
                  }
                });
              },
              child: Text(
                _expandedSteps.length == steps.length ? '全部收起' : '全部展开',
                style: const TextStyle(fontSize: 13),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        ...steps.map(
          (step) => ExecutionStepTile(
            step: step,
            expanded: _expandedSteps.contains(step.index),
            onTap: () {
              setState(() {
                if (_expandedSteps.contains(step.index)) {
                  _expandedSteps.remove(step.index);
                } else {
                  _expandedSteps.add(step.index);
                }
              });
            },
            onCopyJson: () => _copyStepJson(step),
          ),
        ),
      ],
    );
  }

  Future<void> _copyStepJson(ExecutionStep step) async {
    final json = const JsonEncoder.withIndent('  ').convert(step.toUserJson());
    await Clipboard.setData(ClipboardData(text: json));
    if (mounted) {
      showToast('已复制步骤 JSON', type: ToastType.success);
    }
  }

  Widget _buildPill(
    BuildContext context,
    String text, {
    Color? backgroundColor,
    Color? textColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: textColor ?? palette.textSecondary,
        ),
      ),
    );
  }

  String _formatDateTime(String isoTime) {
    try {
      final dt = DateTime.parse(isoTime);
      return '${dt.month}/${dt.day} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    } catch (_) {
      return isoTime;
    }
  }
}
