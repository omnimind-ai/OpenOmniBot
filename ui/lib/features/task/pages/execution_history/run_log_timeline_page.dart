import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
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
  List<Map<String, dynamic>> _cards = [];
  bool _isLoading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final payload = await AssistsMessageService.getRunLogTimeline(
        runId: widget.runId,
        baseUrl: widget.baseUrl,
      );
      if (!mounted) return;
      setState(() {
        final raw = payload['cards'];
        _cards = raw is List
            ? raw.whereType<Map<String, dynamic>>().toList()
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
    final subtitle = stepCount > 0 ? l10n.runLogTimelineStepCount(stepCount) : null;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: subtitle != null
            ? '${l10n.runLogTimelineTitle}  $subtitle'
            : l10n.runLogTimelineTitle,
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
        isLast: index == _cards.length - 1,
      ),
    );
  }
}

// ─── Step card with left-side timeline connector ──────────────────────────────

class _StepCard extends StatelessWidget {
  const _StepCard({required this.card, required this.isLast});

  final Map<String, dynamic> card;
  final bool isLast;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final l10n = context.l10n;

    final header = card['header'] as Map<String, dynamic>? ?? {};
    final stepIndex = (header['step_index'] as int? ?? 0) + 1;
    final title = (header['title'] as String?) ?? l10n.runLogTimelineUnknown;
    final success = header['success'] as bool? ?? true;
    final compileKind = (header['compile_kind'] as String?) ?? 'unknown';
    final durationMs = (header['duration_ms'] as num?)?.toInt();
    final before = card['before'] as Map<String, dynamic>? ?? {};
    final packageName = before['package_name'] as String?;

    final isHit = compileKind == 'hit';
    final dotColor = success
        ? (isHit ? const Color(0xFF2F8F4E) : const Color(0xFF3B82F6))
        : const Color(0xFFDC2626);
    final lineColor = isDark
        ? palette.borderSubtle
        : Colors.grey.shade200;

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
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: isDark ? palette.surfaceSecondary : Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isDark ? palette.borderSubtle : Colors.grey.shade100,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Header row: step number + badge + duration + status
                  Row(
                    children: [
                      Text(
                        'Step $stepIndex',
                        style: TextStyle(
                          fontSize: 11,
                          color: palette.textSecondary,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(width: 6),
                      _RouteBadge(compileKind: compileKind, l10n: l10n),
                      const Spacer(),
                      if (durationMs != null)
                        Text(
                          _formatMs(durationMs),
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textSecondary,
                          ),
                        ),
                      const SizedBox(width: 6),
                      Icon(
                        success ? Icons.check_circle_outline : Icons.cancel_outlined,
                        size: 14,
                        color: success
                            ? const Color(0xFF2F8F4E)
                            : const Color(0xFFDC2626),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  // Title
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: palette.textPrimary,
                    ),
                  ),
                  // Package name (if present)
                  if (packageName != null && packageName.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      packageName,
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
        ],
      ),
    );
  }

  String _formatMs(int ms) {
    if (ms < 1000) return '${ms}ms';
    return '${(ms / 1000).toStringAsFixed(1)}s';
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

    final label = isHit ? l10n.executionRouteMemorized : l10n.executionRouteAiPlanning;
    final color = isHit ? const Color(0xFF2F8F4E) : const Color(0xFF3B82F6);

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
