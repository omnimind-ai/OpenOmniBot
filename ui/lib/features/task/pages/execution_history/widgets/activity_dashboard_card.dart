import 'package:flutter/material.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/token_usage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

// ---------------------------------------------------------------------------
//  Weekly token bucket
// ---------------------------------------------------------------------------

class _WeeklyTokenData {
  final DateTime weekStart;
  int localTokens = 0;
  int cloudTokens = 0;
  int cachedTokens = 0;
  int get totalTokens => localTokens + cloudTokens;
  _WeeklyTokenData({required this.weekStart});
}

// ---------------------------------------------------------------------------
//  Month label helper (heatmap)
// ---------------------------------------------------------------------------

class _MonthLabel {
  final int weekIndex;
  final String label;
  const _MonthLabel({required this.weekIndex, required this.label});
}

// ---------------------------------------------------------------------------
//  Activity Dashboard Card
// ---------------------------------------------------------------------------

class ActivityDashboardCard extends StatefulWidget {
  final int weeksToShow;
  const ActivityDashboardCard({super.key, this.weeksToShow = 16});

  @override
  State<ActivityDashboardCard> createState() => _ActivityDashboardCardState();
}

class _ActivityDashboardCardState extends State<ActivityDashboardCard>
    with SingleTickerProviderStateMixin {
  // -- tab --
  static const int _convTab = 0;
  static const int _tokenTab = 1;
  int _currentTab = 0; // 0=对话  1=Token
  double _tabSwitcherDragDelta = 0;

  // -- conversation data --
  Map<String, int> _activityMap = {};
  bool _isConvLoading = true;
  int _totalConversations = 0;
  int _currentStreak = 0;

  // -- token data --
  List<_WeeklyTokenData> _weeklyData = [];
  bool _isTokenLoading = true;
  int _totalLocal = 0;
  int _totalCloud = 0;
  int _totalCached = 0;

  // -- animation --
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
    _loadConversationData();
    _loadTokenData();
  }

  @override
  void dispose() {
    _fadeController.dispose();
    super.dispose();
  }

  // -----------------------------------------------------------------------
  //  Data loading — conversation
  // -----------------------------------------------------------------------

  Future<void> _loadConversationData() async {
    try {
      final conversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      final startDate = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );

      final Map<String, int> activityMap = {};
      int totalInRange = 0;

      for (final conv in conversations) {
        final date = DateTime.fromMillisecondsSinceEpoch(conv.createdAt);
        final dateOnly = DateTime(date.year, date.month, date.day);
        if (dateOnly.isBefore(startDate) || dateOnly.isAfter(today)) continue;
        final key = _dateKey(dateOnly);
        activityMap[key] = (activityMap[key] ?? 0) + 1;
        totalInRange++;
      }

      int streak = 0;
      var checkDate = today;
      while (true) {
        if ((activityMap[_dateKey(checkDate)] ?? 0) > 0) {
          streak++;
          checkDate = checkDate.subtract(const Duration(days: 1));
        } else {
          break;
        }
      }

      if (mounted) {
        setState(() {
          _activityMap = activityMap;
          _totalConversations = totalInRange;
          _currentStreak = streak;
          _isConvLoading = false;
        });
        _tryStartFade();
      }
    } catch (e) {
      debugPrint('[ActivityDashboard] conv load failed: $e');
      if (mounted) {
        setState(() => _isConvLoading = false);
        _tryStartFade();
      }
    }
  }

  // -----------------------------------------------------------------------
  //  Data loading — token
  // -----------------------------------------------------------------------

  Future<void> _loadTokenData() async {
    try {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      var alignedStart = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );
      while (alignedStart.weekday != DateTime.monday) {
        alignedStart = alignedStart.subtract(const Duration(days: 1));
      }

      final records = await TokenUsageService.getRecordsSince(
        alignedStart.millisecondsSinceEpoch,
      );

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
        final daysSinceStart = DateTime.fromMillisecondsSinceEpoch(
          record.createdAt,
        ).difference(alignedStart).inDays;
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

      if (mounted) {
        setState(() {
          _weeklyData = weeklyData;
          _totalLocal = totalLocal;
          _totalCloud = totalCloud;
          _totalCached = totalCached;
          _isTokenLoading = false;
        });
        _tryStartFade();
      }
    } catch (e) {
      debugPrint('[ActivityDashboard] token load failed: $e');
      if (mounted) {
        setState(() => _isTokenLoading = false);
        _tryStartFade();
      }
    }
  }

  void _tryStartFade() {
    if (!_isConvLoading && !_isTokenLoading && !_fadeController.isAnimating) {
      _fadeController.forward();
    }
  }

  // -----------------------------------------------------------------------
  //  Helpers
  // -----------------------------------------------------------------------

  int get _totalTokens => _totalLocal + _totalCloud;

  String _dateKey(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  String _formatTokenCount(int count) {
    if (count >= 1000000) return '${(count / 1000000).toStringAsFixed(1)}M';
    if (count >= 1000) return '${(count / 1000).toStringAsFixed(1)}K';
    return '$count';
  }

  int _percentOf(int part, int total) =>
      total == 0 ? 0 : (part * 100 / total).round();

  // -- heatmap colors --
  int _intensityLevel(int count) {
    if (count == 0) return 0;
    if (count == 1) return 1;
    if (count <= 3) return 2;
    if (count <= 6) return 3;
    return 4;
  }

  Color _cellColor(int level, bool isDark) {
    const light = [0xFFEBF0F5, 0xFFBFDBF7, 0xFF7BBCE6, 0xFF3B8FD4, 0xFF1A56A8];
    const dark = [0xFF242728, 0xFF1A3A5C, 0xFF1B5E94, 0xFF2178BD, 0xFF3B9FE8];
    final c = isDark ? dark : light;
    return Color(c[level.clamp(0, 4)]);
  }

  // -- token colors --
  Color _localColor(bool d) => d ? const Color(0xFF5E8A5A) : const Color(0xFF7FA878);
  Color _cloudColor(bool d) => d ? const Color(0xFF2178BD) : const Color(0xFF3B8FD4);
  Color _localPillBg(bool d) => d ? const Color(0xFF2A3B28) : const Color(0xFFE8F2E6);
  Color _localPillText(bool d) => d ? const Color(0xFF98D492) : const Color(0xFF3D7A35);
  Color _cloudPillBg(bool d) => d ? const Color(0xFF1A3A5C) : const Color(0xFFE8F2FC);
  Color _cloudPillText(bool d) => d ? const Color(0xFF3B9FE8) : const Color(0xFF2C7FEB);
  Color _cachedColor(bool d) => d ? const Color(0xFFB8860B) : const Color(0xFFD4A017);
  Color _cachedPillBg(bool d) => d ? const Color(0xFF3A3018) : const Color(0xFFFFF3DC);
  Color _cachedPillText(bool d) => d ? const Color(0xFFE8C547) : const Color(0xFFB8860B);

  // -----------------------------------------------------------------------
  //  Build
  // -----------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final isLoading = _isConvLoading && _isTokenLoading;

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
              color: palette.shadowColor.withValues(alpha: isDark ? 0.30 : 0.08),
              blurRadius: 16,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: isLoading
            ? _buildSkeleton(palette, isDark)
            : FadeTransition(
                opacity: _fadeAnimation,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildStatsRow(palette, isDark),
                    const SizedBox(height: 12),
                    _buildSegmentedControl(),
                    const SizedBox(height: 12),
                    AnimatedCrossFade(
                      firstChild: _buildConversationView(isDark, palette),
                      secondChild: _buildTokenView(isDark, palette),
                      crossFadeState: _currentTab == _convTab
                          ? CrossFadeState.showFirst
                          : CrossFadeState.showSecond,
                      duration: const Duration(milliseconds: 300),
                      firstCurve: Curves.easeOut,
                      secondCurve: Curves.easeOut,
                      sizeCurve: Curves.easeOut,
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  // -----------------------------------------------------------------------
  //  Skeleton
  // -----------------------------------------------------------------------

  Widget _buildSkeleton(OmniThemePalette palette, bool isDark) {
    final c = isDark ? palette.surfaceSecondary : const Color(0xFFE8EFF8);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(width: 200, height: 16, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(4))),
        const SizedBox(height: 12),
        Container(width: double.infinity, height: 28, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(14))),
        const SizedBox(height: 12),
        Container(width: double.infinity, height: 90, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(6))),
        const SizedBox(height: 10),
        Container(width: 140, height: 12, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(4))),
      ],
    );
  }

  // -----------------------------------------------------------------------
  //  Stats row — unified header
  // -----------------------------------------------------------------------

  Widget _buildStatsRow(OmniThemePalette palette, bool isDark) {
    final accentBlue = isDark ? const Color(0xFF7BBCE6) : const Color(0xFF2C7FEB);
    final streakColor = _currentStreak >= 3
        ? const Color(0xFFF59E0B)
        : accentBlue;

    return Row(
      children: [
        // 对话次数
        _buildStatPill(
          Icons.chat_bubble_outline_rounded,
          '$_totalConversations',
          LegacyTextLocalizer.localize('次对话'),
          accentBlue,
          palette,
        ),
        const SizedBox(width: 8),
        // 连续天数
        _buildStatPill(
          Icons.local_fire_department_rounded,
          '$_currentStreak',
          LegacyTextLocalizer.localize('天连续'),
          streakColor,
          palette,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Align(
            alignment: Alignment.centerRight,
            child: Wrap(
              alignment: WrapAlignment.end,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 6,
              runSpacing: 4,
              children: [
                // Token 总量
                _buildStatPill(
                  Icons.bolt_rounded,
                  _formatTokenCount(_totalTokens),
                  'tokens',
                  accentBlue,
                  palette,
                ),
                if (_totalCached > 0)
                  _buildStatPill(
                    Icons.cached_rounded,
                    _formatTokenCount(_totalCached),
                    LegacyTextLocalizer.localize('缓存'),
                    _cachedColor(isDark),
                    palette,
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildStatPill(IconData icon, String value, String label, Color iconColor, OmniThemePalette palette) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: iconColor),
        const SizedBox(width: 3),
        Text(value, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: palette.textPrimary)),
        const SizedBox(width: 2),
        Text(label, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w400, color: palette.textTertiary)),
      ],
    );
  }

  // -----------------------------------------------------------------------
  //  Tab switcher (matches Scheduled page style, smaller size)
  // -----------------------------------------------------------------------

  void _switchTab(int tabIndex) {
    if (_currentTab == tabIndex) return;
    setState(() => _currentTab = tabIndex);
  }

  void _handleTabSwitcherDragEnd({double velocity = 0}) {
    final shouldSwitchRight = (_tabSwitcherDragDelta + velocity * 0.015) > 0;
    final shouldSwitch =
        _tabSwitcherDragDelta.abs() > 14 || velocity.abs() > 250;
    if (shouldSwitch) {
      _switchTab(shouldSwitchRight ? _tokenTab : _convTab);
    }
    _tabSwitcherDragDelta = 0;
  }

  Widget _buildSegmentedControl() {
    final palette = context.omniPalette;
    return Builder(
      builder: (sliderContext) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onHorizontalDragUpdate: (details) {
          _tabSwitcherDragDelta += details.delta.dx;
        },
        onHorizontalDragEnd: (details) {
          _handleTabSwitcherDragEnd(velocity: details.primaryVelocity ?? 0);
        },
        onTapUp: (details) {
          final box = sliderContext.findRenderObject() as RenderBox?;
          if (box == null || !box.hasSize) return;
          final local = box.globalToLocal(details.globalPosition);
          _switchTab(
            local.dx >= box.size.width / 2 ? _tokenTab : _convTab,
          );
        },
        child: Container(
          height: 28,
          padding: const EdgeInsets.all(2),
          decoration: BoxDecoration(
            color: context.isDarkTheme ? palette.segmentTrack : Colors.white,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(
              color: context.isDarkTheme
                  ? palette.borderSubtle
                  : AppColors.text10,
            ),
          ),
          child: Stack(
            children: [
              AnimatedAlign(
                duration: const Duration(milliseconds: 280),
                curve: Curves.easeOutCubic,
                alignment: _currentTab == _convTab
                    ? Alignment.centerLeft
                    : Alignment.centerRight,
                child: FractionallySizedBox(
                  widthFactor: 0.5,
                  child: Container(
                    margin: const EdgeInsets.symmetric(horizontal: 1),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(999),
                      gradient: context.isDarkTheme
                          ? LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [
                                Color.lerp(
                                  palette.surfaceElevated,
                                  palette.accentPrimary,
                                  0.18,
                                )!,
                                Color.lerp(
                                  palette.surfaceSecondary,
                                  palette.accentPrimary,
                                  0.30,
                                )!,
                              ],
                            )
                          : const LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [Color(0xFF2DA5F0), Color(0xFF1930D9)],
                            ),
                      boxShadow: context.isDarkTheme
                          ? null
                          : const [
                              BoxShadow(
                                color: Color(0x1F1930D9),
                                blurRadius: 10,
                                offset: Offset(0, 4),
                              ),
                            ],
                      border: context.isDarkTheme
                          ? Border.all(color: palette.borderSubtle)
                          : null,
                    ),
                  ),
                ),
              ),
              Row(
                children: [
                  _buildTabButton(
                    label: LegacyTextLocalizer.isEnglish ? 'Chat' : '对话',
                    tabIndex: _convTab,
                  ),
                  _buildTabButton(
                    label: 'Token',
                    tabIndex: _tokenTab,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTabButton({required String label, required int tabIndex}) {
    final selected = _currentTab == tabIndex;
    final palette = context.omniPalette;
    return Expanded(
      child: Center(
        child: AnimatedScale(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          scale: selected ? 1 : 0.97,
          child: AnimatedDefaultTextStyle(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            style: TextStyle(
              color: selected
                  ? (context.isDarkTheme ? palette.textPrimary : Colors.white)
                  : (context.isDarkTheme
                        ? palette.textSecondary
                        : AppColors.text70),
              fontSize: 12,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            ),
            child: Text(label),
          ),
        ),
      ),
    );
  }

  // -----------------------------------------------------------------------
  //  Conversation tab — heatmap
  // -----------------------------------------------------------------------

  Widget _buildConversationView(bool isDark, OmniThemePalette palette) {
    if (_isConvLoading) {
      return const SizedBox(height: 100, child: Center(child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildHeatmapGrid(isDark),
      ],
    );
  }

  Widget _buildHeatmapGrid(bool isDark) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final daysBack = widget.weeksToShow * 7 - 1;
    var startDate = today.subtract(Duration(days: daysBack));
    while (startDate.weekday != DateTime.monday) {
      startDate = startDate.subtract(const Duration(days: 1));
    }

    final totalDays = today.difference(startDate).inDays + 1;
    final totalWeeks = (totalDays / 7).ceil();

    final monthLabels = <_MonthLabel>[];
    String? lastMonth;
    for (int week = 0; week < totalWeeks; week++) {
      final weekStart = startDate.add(Duration(days: week * 7));
      final m = _monthName(weekStart.month);
      if (m != lastMonth) {
        monthLabels.add(_MonthLabel(weekIndex: week, label: m));
        lastMonth = m;
      }
    }

    const double cellGap = 3.0;
    const double dayLabelWidth = 20.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Month labels
        Padding(
          padding: const EdgeInsets.only(left: dayLabelWidth, bottom: 4),
          child: SizedBox(
            height: 14,
            child: LayoutBuilder(builder: (context, constraints) {
              final weekCellWidth = totalWeeks > 0 ? constraints.maxWidth / totalWeeks : 14.0;
              return Stack(
                children: monthLabels.map((ml) => Positioned(
                  left: ml.weekIndex * weekCellWidth,
                  child: Text(ml.label, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: isDark ? const Color(0xFF9A9488) : const Color(0xFF98A5BB))),
                )).toList(),
              );
            }),
          ),
        ),
        // Grid
        LayoutBuilder(builder: (context, constraints) {
          final gridWidth = constraints.maxWidth - dayLabelWidth;
          final cellSize = (totalWeeks > 0 ? (gridWidth - (totalWeeks - 1) * cellGap) / totalWeeks : 11.0).clamp(4.0, 14.0);
          final gridHeight = 7 * cellSize + 6 * cellGap;
          final rowHeight = cellSize + cellGap;

          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: dayLabelWidth,
                child: Column(
                  children: [
                    for (int day = 0; day < 7; day++)
                      SizedBox(
                        height: day < 6 ? rowHeight : cellSize,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: day % 2 == 0
                              ? Text(_dayLabel(day), style: TextStyle(fontSize: 9, fontWeight: FontWeight.w500, color: isDark ? const Color(0xFF9A9488) : const Color(0xFF98A5BB)))
                              : const SizedBox.shrink(),
                        ),
                      ),
                  ],
                ),
              ),
              Expanded(
                child: SizedBox(
                  height: gridHeight,
                  child: Wrap(
                    direction: Axis.vertical,
                    spacing: cellGap,
                    runSpacing: cellGap,
                    children: List.generate(totalWeeks * 7, (index) {
                      final week = index ~/ 7;
                      final dayOfWeek = index % 7;
                      final cellDate = startDate.add(Duration(days: week * 7 + dayOfWeek));
                      if (cellDate.isAfter(today)) return SizedBox(width: cellSize, height: cellSize);
                      final count = _activityMap[_dateKey(cellDate)] ?? 0;
                      return Tooltip(
                        message: count > 0
                            ? LegacyTextLocalizer.localize(
                                '$count 次对话 · ${cellDate.month}/${cellDate.day}',
                              )
                            : LegacyTextLocalizer.localize(
                                '无对话 · ${cellDate.month}/${cellDate.day}',
                              ),
                        preferBelow: false,
                        verticalOffset: 12,
                        decoration: BoxDecoration(color: isDark ? const Color(0xFF2D3032) : const Color(0xFF353E53), borderRadius: BorderRadius.circular(6)),
                        textStyle: const TextStyle(fontSize: 11, color: Colors.white),
                        child: Container(width: cellSize, height: cellSize, decoration: BoxDecoration(color: _cellColor(_intensityLevel(count), isDark), borderRadius: BorderRadius.circular(2.5))),
                      );
                    }),
                  ),
                ),
              ),
            ],
          );
        }),
      ],
    );
  }

  String _monthName(int m) {
    if (LegacyTextLocalizer.isEnglish) {
      const names = [
        '',
        'Jan',
        'Feb',
        'Mar',
        'Apr',
        'May',
        'Jun',
        'Jul',
        'Aug',
        'Sep',
        'Oct',
        'Nov',
        'Dec',
      ];
      return names[m];
    }
    const names = [
      '',
      '1月',
      '2月',
      '3月',
      '4月',
      '5月',
      '6月',
      '7月',
      '8月',
      '9月',
      '10月',
      '11月',
      '12月',
    ];
    return names[m];
  }

  String _dayLabel(int i) {
    if (LegacyTextLocalizer.isEnglish) {
      const labels = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun'];
      return labels[i];
    }
    const labels = ['一', '', '三', '', '五', '', '日'];
    return labels[i];
  }

  // -----------------------------------------------------------------------
  //  Token tab — stacked bars
  // -----------------------------------------------------------------------

  Widget _buildTokenView(bool isDark, OmniThemePalette palette) {
    if (_isTokenLoading) {
      return const SizedBox(height: 100, child: Center(child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))));
    }
    if (_totalTokens == 0) {
      return SizedBox(
        height: 70,
        child: Center(
          child: Text(
            LegacyTextLocalizer.localize('暂无 Token 消耗数据'),
            style: TextStyle(fontSize: 11, color: palette.textTertiary),
          ),
        ),
      );
    }
    return Column(
      children: [
        // Local/Cloud proportion pills
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            if (_totalLocal > 0)
              _buildPropPill(
                LegacyTextLocalizer.localize(
                  '本地 ${_percentOf(_totalLocal, _totalTokens)}%',
                ),
                _localPillBg(isDark),
                _localPillText(isDark),
                _localColor(isDark),
              ),
            if (_totalLocal > 0 && _totalCloud > 0) const SizedBox(width: 6),
            if (_totalCloud > 0)
              _buildPropPill(
                LegacyTextLocalizer.localize(
                  '云端 ${_percentOf(_totalCloud, _totalTokens)}%',
                ),
                _cloudPillBg(isDark),
                _cloudPillText(isDark),
                _cloudColor(isDark),
              ),
          ],
        ),
        const SizedBox(height: 10),
        _buildStackedBars(isDark, palette),
      ],
    );
  }

  Widget _buildPropPill(String label, Color bg, Color text, Color dot) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(20)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 6, height: 6, decoration: BoxDecoration(color: dot, borderRadius: BorderRadius.circular(1.5))),
        const SizedBox(width: 4),
        Text(label, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w500, color: text)),
      ]),
    );
  }

  Widget _buildStackedBars(bool isDark, OmniThemePalette palette) {
    final maxWeekTotal = _weeklyData.fold<int>(0, (p, w) => w.totalTokens > p ? w.totalTokens : p);
    const double barGap = 3.0;
    const double heatmapCellGap = 3.0;
    const double dayLabelWidth = 20.0;

    return LayoutBuilder(builder: (context, constraints) {
      final totalBars = _weeklyData.length;
      // Match heatmap grid height
      final gridWidth = constraints.maxWidth - dayLabelWidth;
      final heatmapCellSize = (totalBars > 0 ? (gridWidth - (totalBars - 1) * heatmapCellGap) / totalBars : 11.0).clamp(4.0, 14.0);
      final barAreaHeight = 7 * heatmapCellSize + 6 * heatmapCellGap;
      final barWidth = (totalBars > 0 ? (constraints.maxWidth - (totalBars - 1) * barGap) / totalBars : 8.0).clamp(4.0, 14.0);

      return SizedBox(
        height: barAreaHeight,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: List.generate(_weeklyData.length, (index) {
            final week = _weeklyData[index];
            final totalH = maxWeekTotal > 0 ? (week.totalTokens / maxWeekTotal) * barAreaHeight : 0.0;
            final localH = week.totalTokens > 0 ? totalH * (week.localTokens / week.totalTokens) : 0.0;
            final cloudH = totalH - localH;
            return Padding(
              padding: EdgeInsets.only(right: index < _weeklyData.length - 1 ? barGap : 0),
              child: Tooltip(
                message: week.totalTokens > 0
                    ? LegacyTextLocalizer.localize(
                        '本地 ${_formatTokenCount(week.localTokens)} · 云端 ${_formatTokenCount(week.cloudTokens)} · 缓存 ${_formatTokenCount(week.cachedTokens)}',
                      )
                    : LegacyTextLocalizer.localize('无消耗'),
                preferBelow: false, verticalOffset: 12,
                decoration: BoxDecoration(color: isDark ? const Color(0xFF2D3032) : const Color(0xFF353E53), borderRadius: BorderRadius.circular(6)),
                textStyle: const TextStyle(fontSize: 11, color: Colors.white),
                child: SizedBox(
                  width: barWidth,
                  height: barAreaHeight,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      if (cloudH > 0) Container(width: barWidth, height: cloudH.clamp(0, barAreaHeight), decoration: BoxDecoration(color: _cloudColor(isDark), borderRadius: BorderRadius.only(topLeft: const Radius.circular(2), topRight: const Radius.circular(2)))),
                      if (localH > 0) Container(width: barWidth, height: localH.clamp(0, barAreaHeight), decoration: BoxDecoration(color: _localColor(isDark), borderRadius: cloudH > 0 ? BorderRadius.zero : const BorderRadius.only(topLeft: Radius.circular(2), topRight: Radius.circular(2)))),
                      if (week.totalTokens == 0) Container(width: barWidth, height: 2, decoration: BoxDecoration(color: palette.surfaceElevated, borderRadius: BorderRadius.circular(1))),
                    ],
                  ),
                ),
              ),
            );
          }),
        ),
      );
    });
  }

}
