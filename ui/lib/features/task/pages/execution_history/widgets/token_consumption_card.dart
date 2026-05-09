import 'package:flutter/material.dart';
import 'package:ui/services/token_usage_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';

/// Weekly aggregated token data for the stacked bar chart.
class _WeeklyTokenData {
  final DateTime weekStart;
  int localTokens = 0;
  int cloudTokens = 0;
  int cachedTokens = 0;
  int get totalTokens => localTokens + cloudTokens;

  _WeeklyTokenData({required this.weekStart});
}

/// Token consumption card showing local vs cloud usage over 16 weeks.
class TokenConsumptionCard extends StatefulWidget {
  final int weeksToShow;

  const TokenConsumptionCard({super.key, this.weeksToShow = 16});

  @override
  State<TokenConsumptionCard> createState() => _TokenConsumptionCardState();
}

class _TokenConsumptionCardState extends State<TokenConsumptionCard>
    with SingleTickerProviderStateMixin {
  List<_WeeklyTokenData> _weeklyData = [];
  bool _isLoading = true;
  int _totalLocal = 0;
  int _totalCloud = 0;
  int _totalCached = 0;

  late AnimationController _fadeController;
  late Animation<double> _fadeAnimation;

  @override
  void initState() {
    super.initState();
    _fadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _fadeAnimation = CurvedAnimation(
      parent: _fadeController,
      curve: Curves.easeOut,
    );
    _loadData();
  }

  @override
  void dispose() {
    _fadeController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    try {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      final startDate = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );
      // Align to Monday
      var alignedStart = startDate;
      while (alignedStart.weekday != DateTime.monday) {
        alignedStart = alignedStart.subtract(const Duration(days: 1));
      }

      final sinceMs = alignedStart.millisecondsSinceEpoch;
      debugPrint('[TokenConsumptionCard] querying records since=$sinceMs (${alignedStart.toIso8601String()})');
      final records = await TokenUsageService.getRecordsSince(sinceMs);
      debugPrint('[TokenConsumptionCard] fetched ${records.length} records from DB');
      for (final r in records) {
        debugPrint('[TokenConsumptionCard]   record id=${r.id}, model=${r.model}, isLocal=${r.isLocal}, '
            'prompt=${r.promptTokens}, completion=${r.completionTokens}, '
            'reasoning=${r.reasoningTokens}, text=${r.textTokens}, cached=${r.cachedTokens}, '
            'totalTokens=${r.totalTokens}, '
            'createdAt=${DateTime.fromMillisecondsSinceEpoch(r.createdAt).toIso8601String()}');
      }

      // Build weekly buckets — calculate actual week count from aligned start to today
      final totalDays = today.difference(alignedStart).inDays + 1;
      final totalWeeks = (totalDays / 7).ceil();
      final weeklyData = List.generate(
        totalWeeks,
        (i) => _WeeklyTokenData(
          weekStart: alignedStart.add(Duration(days: i * 7)),
        ),
      );

      int totalLocal = 0;
      int totalCloud = 0;
      int totalCached = 0;

      for (final record in records) {
        final recordDate = DateTime.fromMillisecondsSinceEpoch(record.createdAt);
        final daysSinceStart = recordDate.difference(alignedStart).inDays;
        if (daysSinceStart < 0) continue;
        final weekIndex = daysSinceStart ~/ 7;
        if (weekIndex >= totalWeeks) continue;

        final tokens = record.totalTokens;

        weeklyData[weekIndex].cachedTokens += record.cachedTokens;
        totalCached += record.cachedTokens;

        if (record.isLocal) {
          weeklyData[weekIndex].localTokens += tokens;
          totalLocal += tokens;
        } else {
          weeklyData[weekIndex].cloudTokens += tokens;
          totalCloud += tokens;
        }
      }

      debugPrint('[TokenConsumptionCard] aggregated: totalLocal=$totalLocal, totalCloud=$totalCloud, '
          'totalCached=$totalCached, total=${totalLocal + totalCloud + totalCached}, '
          'weeks with data=${weeklyData.where((w) => w.totalTokens > 0).length}');

      if (mounted) {
        setState(() {
          _weeklyData = weeklyData;
          _totalLocal = totalLocal;
          _totalCloud = totalCloud;
          _totalCached = totalCached;
          _isLoading = false;
        });
        _fadeController.forward();
      }
    } catch (e) {
      debugPrint('[TokenConsumptionCard] Failed to load data: $e');
      if (mounted) {
        setState(() => _isLoading = false);
        _fadeController.forward();
      }
    }
  }

  int get _total => _totalLocal + _totalCloud;

  String _formatTokenCount(int count) {
    if (count >= 1000000) {
      return '${(count / 1000000).toStringAsFixed(1)}M';
    } else if (count >= 1000) {
      return '${(count / 1000).toStringAsFixed(1)}K';
    }
    return '$count';
  }

  int _percentOf(int part, int total) {
    if (total == 0) return 0;
    return (part * 100 / total).round();
  }

  // Colors
  Color _localColor(bool isDark) =>
      isDark ? const Color(0xFF5E8A5A) : const Color(0xFF7FA878);

  Color _cloudColor(bool isDark) =>
      isDark ? const Color(0xFF2178BD) : const Color(0xFF3B8FD4);

  Color _localPillBg(bool isDark) =>
      isDark ? const Color(0xFF2A3B28) : const Color(0xFFE8F2E6);

  Color _localPillText(bool isDark) =>
      isDark ? const Color(0xFF98D492) : const Color(0xFF3D7A35);

  Color _cloudPillBg(bool isDark) =>
      isDark ? const Color(0xFF1A3A5C) : const Color(0xFFE8F2FC);

  Color _cloudPillText(bool isDark) =>
      isDark ? const Color(0xFF3B9FE8) : const Color(0xFF2C7FEB);

  Color _cachedColor(bool isDark) =>
      isDark ? const Color(0xFFB8860B) : const Color(0xFFD4A017);

  Color _cachedPillBg(bool isDark) =>
      isDark ? const Color(0xFF3A3018) : const Color(0xFFFFF3DC);

  Color _cachedPillText(bool isDark) =>
      isDark ? const Color(0xFFE8C547) : const Color(0xFFB8860B);

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(14, 14, 14, 12),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor.withValues(
                alpha: isDark ? 0.30 : 0.08,
              ),
              blurRadius: 16,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: _isLoading
            ? _buildSkeleton(palette, isDark)
            : FadeTransition(
                opacity: _fadeAnimation,
                child: _total == 0
                    ? _buildEmptyState(palette)
                    : Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _buildHeader(palette, isDark),
                          const SizedBox(height: 14),
                          _buildStackedBars(isDark, palette),
                          const SizedBox(height: 10),
                          _buildLegend(isDark, palette),
                        ],
                      ),
              ),
      ),
    );
  }

  Widget _buildSkeleton(OmniThemePalette palette, bool isDark) {
    final baseColor =
        isDark ? palette.surfaceSecondary : const Color(0xFFE8EFF8);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 140,
          height: 16,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
        const SizedBox(height: 14),
        Container(
          width: double.infinity,
          height: 6,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(3),
          ),
        ),
        const SizedBox(height: 14),
        Container(
          width: double.infinity,
          height: 60,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(6),
          ),
        ),
        const SizedBox(height: 10),
        Align(
          alignment: Alignment.centerRight,
          child: Container(
            width: 100,
            height: 12,
            decoration: BoxDecoration(
              color: baseColor,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildEmptyState(OmniThemePalette palette) {
    return SizedBox(
      height: 60,
      child: Center(
        child: Text(
          '暂无 Token 消耗数据',
          style: TextStyle(fontSize: 11, color: palette.textTertiary),
        ),
      ),
    );
  }

  Widget _buildHeader(OmniThemePalette palette, bool isDark) {
    return Row(
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Total token stat
            Icon(
              Icons.bolt_rounded,
              size: 14,
              color: isDark
                  ? const Color(0xFF7BBCE6)
                  : const Color(0xFF2C7FEB),
            ),
            const SizedBox(width: 3),
            Text(
              _formatTokenCount(_total),
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w700,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(width: 3),
            Text(
              'tokens',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w400,
                color: palette.textTertiary,
              ),
            ),
            if (_totalCached > 0) ...[
              const SizedBox(width: 6),
              _buildStatPill(
                Icons.cached_rounded,
                _formatTokenCount(_totalCached),
                '缓存',
                _cachedColor(isDark),
                palette,
              ),
            ],
          ],
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Wrap(
            alignment: WrapAlignment.end,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 6,
            runSpacing: 4,
            children: [
              // Local proportion pill
              if (_totalLocal > 0)
                _buildPropPill(
                  label: '本地 ${_percentOf(_totalLocal, _total)}%',
                  bgColor: _localPillBg(isDark),
                  textColor: _localPillText(isDark),
                  dotColor: _localColor(isDark),
                ),
              // Cloud proportion pill
              if (_totalCloud > 0)
                _buildPropPill(
                  label: '云端 ${_percentOf(_totalCloud, _total)}%',
                  bgColor: _cloudPillBg(isDark),
                  textColor: _cloudPillText(isDark),
                  dotColor: _cloudColor(isDark),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildStatPill(
    IconData icon,
    String value,
    String label,
    Color iconColor,
    OmniThemePalette palette,
  ) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: iconColor),
        const SizedBox(width: 3),
        Text(
          value,
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(width: 3),
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w400,
            color: palette.textTertiary,
          ),
        ),
      ],
    );
  }

  Widget _buildPropPill({
    required String label,
    required Color bgColor,
    required Color textColor,
    required Color dotColor,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: dotColor,
              borderRadius: BorderRadius.circular(1.5),
            ),
          ),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w500,
              color: textColor,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStackedBars(bool isDark, OmniThemePalette palette) {
    final maxWeekTotal = _weeklyData.fold<int>(
      0,
      (prev, w) => w.totalTokens > prev ? w.totalTokens : prev,
    );

    const double barAreaHeight = 60.0;
    const double barGap = 3.0;

    return LayoutBuilder(
      builder: (context, constraints) {
        final totalBars = _weeklyData.length;
        final barWidth = totalBars > 0
            ? (constraints.maxWidth - (totalBars - 1) * barGap) / totalBars
            : 8.0;
        final clampedWidth = barWidth.clamp(4.0, 14.0);

        return SizedBox(
          height: barAreaHeight,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: List.generate(_weeklyData.length, (index) {
              final week = _weeklyData[index];
              final totalHeight = maxWeekTotal > 0
                  ? (week.totalTokens / maxWeekTotal) * barAreaHeight
                  : 0.0;
              final localHeight = week.totalTokens > 0
                  ? totalHeight * (week.localTokens / week.totalTokens)
                  : 0.0;
              final cloudHeight = totalHeight - localHeight;

              return Padding(
                padding: EdgeInsets.only(
                  right: index < _weeklyData.length - 1 ? barGap : 0,
                ),
                child: Tooltip(
                  message: week.totalTokens > 0
                      ? '本地 ${_formatTokenCount(week.localTokens)} · 云端 ${_formatTokenCount(week.cloudTokens)} · 缓存 ${_formatTokenCount(week.cachedTokens)}'
                      : '无消耗',
                  preferBelow: false,
                  verticalOffset: 12,
                  decoration: BoxDecoration(
                    color: isDark
                        ? const Color(0xFF2D3032)
                        : const Color(0xFF353E53),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  textStyle: const TextStyle(
                    fontSize: 11,
                    color: Colors.white,
                  ),
                  child: SizedBox(
                    width: clampedWidth,
                    height: barAreaHeight,
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        if (cloudHeight > 0)
                          Container(
                            width: clampedWidth,
                            height: cloudHeight.clamp(0, barAreaHeight),
                            decoration: BoxDecoration(
                              color: _cloudColor(isDark),
                              borderRadius: BorderRadius.only(
                                topLeft: const Radius.circular(2),
                                topRight: const Radius.circular(2),
                                bottomLeft: localHeight > 0
                                    ? Radius.zero
                                    : const Radius.circular(0),
                                bottomRight: localHeight > 0
                                    ? Radius.zero
                                    : const Radius.circular(0),
                              ),
                            ),
                          ),
                        if (localHeight > 0)
                          Container(
                            width: clampedWidth,
                            height: localHeight.clamp(0, barAreaHeight),
                            decoration: BoxDecoration(
                              color: _localColor(isDark),
                              borderRadius: BorderRadius.only(
                                topLeft: cloudHeight > 0
                                    ? Radius.zero
                                    : const Radius.circular(2),
                                topRight: cloudHeight > 0
                                    ? Radius.zero
                                    : const Radius.circular(2),
                              ),
                            ),
                          ),
                        if (week.totalTokens == 0)
                          Container(
                            width: clampedWidth,
                            height: 2,
                            decoration: BoxDecoration(
                              color: palette.surfaceElevated,
                              borderRadius: BorderRadius.circular(1),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              );
            }),
          ),
        );
      },
    );
  }

  Widget _buildLegend(bool isDark, OmniThemePalette palette) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(
            color: _localColor(isDark),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(width: 4),
        Text(
          '本地',
          style: TextStyle(fontSize: 9, color: palette.textTertiary),
        ),
        const SizedBox(width: 12),
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(
            color: _cloudColor(isDark),
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(width: 4),
        Text(
          '云端',
          style: TextStyle(fontSize: 9, color: palette.textTertiary),
        ),
      ],
    );
  }
}
