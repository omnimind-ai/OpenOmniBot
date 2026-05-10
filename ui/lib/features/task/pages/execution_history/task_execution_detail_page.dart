import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:intl/intl.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/execution_record.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/image_util.dart';
import 'package:ui/widgets/image/cached_image.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/theme/theme_context.dart';

/// 执行记录详情页参数
class TaskExecutionDetailParams {
  final String title;
  final String packageName;
  final String appName;
  final String nodeId;
  final String suggestionId;
  final int totalCount;
  final int lastExecutionTime;
  final ExecutionRecordType type;
  final String? iconUrl;
  final String? content; // 总结任务的 Markdown 内容

  TaskExecutionDetailParams({
    required this.title,
    required this.packageName,
    required this.appName,
    required this.nodeId,
    required this.suggestionId,
    required this.totalCount,
    required this.lastExecutionTime,
    this.type = ExecutionRecordType.unknown,
    this.iconUrl,
    this.content,
  });

  Map<String, dynamic> toMap() {
    return {
      'title': title,
      'packageName': packageName,
      'appName': appName,
      'nodeId': nodeId,
      'suggestionId': suggestionId,
      'totalCount': totalCount,
      'lastExecutionTime': lastExecutionTime,
      'type': type.value,
      'iconUrl': iconUrl,
      'content': content,
    };
  }

  factory TaskExecutionDetailParams.fromMap(Map<String, dynamic>? map) {
    if (map == null) {
      throw ArgumentError('map cannot be null');
    }
    return TaskExecutionDetailParams(
      title: map['title'] as String? ?? '',
      packageName: map['packageName'] as String? ?? '',
      appName: map['appName'] as String? ?? '',
      nodeId: map['nodeId'] as String? ?? '',
      suggestionId: map['suggestionId'] as String? ?? '',
      totalCount: map['totalCount'] as int? ?? 0,
      lastExecutionTime: map['lastExecutionTime'] as int? ?? 0,
      type: ExecutionRecordTypeX.fromValue(map['type'] as String?),
      iconUrl: map['iconUrl'] as String?,
      content: map['content'] as String?,
    );
  }
}

/// 执行记录详情页 - 显示单个任务的所有执行历史
class TaskExecutionDetailPage extends StatefulWidget {
  final TaskExecutionDetailParams params;

  const TaskExecutionDetailPage({super.key, required this.params});

  @override
  State<TaskExecutionDetailPage> createState() =>
      _TaskExecutionDetailPageState();
}

class _TaskExecutionDetailPageState extends State<TaskExecutionDetailPage> {
  bool _isLoading = true;
  List<ExecutionRecord> _executionRecords = [];
  ImageProvider? _appIconProvider;
  bool get _isSummaryType =>
      widget.params.type == ExecutionRecordType.summary &&
      widget.params.content?.isNotEmpty == true;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
    });

    try {
      // 加载执行记录列表
      final records = await CacheUtil.getExecutionRecordsByNodeAndSuggestionId(
        widget.params.nodeId,
        widget.params.suggestionId,
      );

      // 加载应用图标
      if (widget.params.packageName.isNotEmpty && mounted) {
        final iconMap = await ImageUtil.batchLoadAppIcons({
          widget.params.packageName,
        }, context);
        _appIconProvider = iconMap[widget.params.packageName];
      }

      if (mounted) {
        setState(() {
          _executionRecords = records;
          _isLoading = false;
        });
      }
    } catch (e) {
      print('加载执行记录详情失败: $e');
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: context.isDarkTheme
          ? palette.pageBackground
          : const Color(0xFFF5F5F5),
      appBar: const CommonAppBar(primary: true),
      body: SafeArea(
        top: false,
        child: _isLoading
            ? _buildLoadingIndicator()
            : SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SizedBox(height: 16),
                    _buildTaskHeader(),
                    const SizedBox(height: 12),
                    _buildDivider(),
                    const SizedBox(height: 16),
                    if (!_isSummaryType) _buildStatsRow(),
                    const SizedBox(height: 8),
                    // 根据类型组件化渲染不同内容
                    _buildContentByType(),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
      ),
    );
  }

  /// 构建任务标题区
  Widget _buildTaskHeader() {
    final palette = context.omniPalette;
    // 限制每行最多12个字
    String title = widget.params.title;
    if (title.length > 12) {
      final List<String> segments = [];
      for (int i = 0; i < title.length; i += 12) {
        segments.add(
          title.substring(i, i + 12 > title.length ? title.length : i + 12),
        );
      }
      title = segments.join('\n');
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Text(
            title,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w500,
              color: palette.textPrimary,
              height: 1.5,
              letterSpacing: 0.5,
            ),
          ),
        ),
        // 应用图标
        Padding(
          padding: const EdgeInsets.fromLTRB(2, 5, 2, 2),
          child: _buildAppIcon(),
        ),
        const SizedBox(width: 4),
        // 类型图标
        Padding(
          padding: const EdgeInsets.fromLTRB(2, 5, 2, 2),
          child: _buildTypeIcon(),
        ),
      ],
    );
  }

  /// 构建应用图标
  Widget _buildAppIcon() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(2),
      child: _appIconProvider != null
          ? Image(
              image: _appIconProvider!,
              width: 20,
              height: 20,
              fit: BoxFit.cover,
              errorBuilder: (context, error, stackTrace) =>
                  _buildDefaultAppIcon(),
            )
          : _buildDefaultAppIcon(),
    );
  }

  Widget _buildDefaultAppIcon() {
    final palette = context.omniPalette;
    return Container(
      width: 20,
      height: 20,
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceElevated : Colors.grey[300],
        borderRadius: BorderRadius.circular(2),
        border: context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
      ),
      child: Icon(
        Icons.apps,
        size: 16,
        color: context.isDarkTheme ? palette.textSecondary : Colors.grey[600],
      ),
    );
  }

  /// 构建类型图标
  Widget _buildTypeIcon() {
    const double iconSize = 20.0;
    final type = widget.params.type;
    final iconUrl = widget.params.iconUrl;

    return iconUrl != null && iconUrl.isNotEmpty
        ? CachedImage(
            imageUrl: iconUrl,
            width: iconSize,
            height: iconSize,
            fit: BoxFit.cover,
            errorWidget: _buildDefaultTypeIcon(type, iconSize),
          )
        : type.defaultIconPath.isNotEmpty
        ? _buildDefaultTypeIcon(type, iconSize)
        : SizedBox.shrink();
    // : Container(
    //     width: iconSize,
    //     height: iconSize,
    //     decoration: BoxDecoration(
    //       color: const Color(0xFF1676FE),
    //       borderRadius: BorderRadius.circular(4),
    //     ),
    //     padding: const EdgeInsets.all(2),
    //     child:const Icon(
    //       Icons.auto_awesome,
    //       size: iconSize - 4,
    //       color: Colors.white,
    //     )
    //   );
  }

  /// 构建默认类型图标（SVG）
  Widget _buildDefaultTypeIcon(ExecutionRecordType type, double size) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: Color(type.defaultIconColor),
        borderRadius: BorderRadius.circular(2),
      ),
      padding: const EdgeInsets.all(2),
      child: type.defaultIconPath.isEmpty
          ? SizedBox.shrink()
          : SvgPicture.asset(
              type.defaultIconPath,
              width: size - 4,
              height: size - 4,
            ),
    );
  }

  /// 构建分割线
  Widget _buildDivider() {
    final palette = context.omniPalette;
    return Container(
      height: 0.5,
      width: double.infinity,
      color: context.isDarkTheme
          ? palette.borderSubtle
          : const Color(0x99E9E9E9),
    );
  }

  /// 构建统计行
  Widget _buildStatsRow() {
    final palette = context.omniPalette;
    final lastTimeLabel = _formatLastExecutionTime(
      widget.params.lastExecutionTime,
    );

    return Row(
      children: [
        Text(
          '${context.trLegacy('最近执行')} $lastTimeLabel',
          style: TextStyle(
            fontSize: 10,
            color: context.isDarkTheme
                ? palette.textSecondary
                : AppColors.text70,
            height: 1.6,
          ),
        ),
        Container(
          margin: const EdgeInsets.symmetric(horizontal: 10),
          width: 0.5,
          height: 7,
          color: context.isDarkTheme ? palette.textSecondary : AppColors.text70,
        ),
        Text(
          '${_executionRecords.isNotEmpty ? _executionRecords.length : widget.params.totalCount}x',
          style: TextStyle(
            fontSize: 10,
            color: context.isDarkTheme
                ? palette.textSecondary
                : AppColors.text70,
            height: 1.6,
          ),
        ),
      ],
    );
  }

  /// 根据类型组件化渲染不同内容
  /// - summary 类型：展示 Markdown 内容
  /// - 其他类型：展示执行记录列表
  Widget _buildContentByType() {
    if (_isSummaryType) {
      return _buildSummaryContent();
    } else {
      return _buildExecutionList();
    }
  }

  /// 构建总结内容（Markdown）
  Widget _buildSummaryContent() {
    final palette = context.omniPalette;
    final content = widget.params.content;

    if (content == null || content.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(32),
        child: Center(
          child: Text(
            context.trLegacy('暂无总结内容'),
            style: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text50,
            ),
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          context.l10n.brandNameEnglish,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 16,
            fontWeight: FontWeight.w500,
            height: 1.38,
            letterSpacing: 0.33,
          ),
        ),
        const SizedBox(height: 8),
        MarkdownBody(
          data: content,
          styleSheet: MarkdownStyleSheet(
            p: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightRegular,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            h1: TextStyle(
              fontSize: AppTextStyles.fontSizeH1,
              fontWeight: AppTextStyles.fontWeightMedium,
              height: AppTextStyles.lineHeightH1,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            h2: TextStyle(
              fontSize: AppTextStyles.fontSizeH2,
              fontWeight: AppTextStyles.fontWeightMedium,
              height: AppTextStyles.lineHeightH1,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            h3: TextStyle(
              fontSize: AppTextStyles.fontSizeH3,
              fontWeight: AppTextStyles.fontWeightMedium,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            strong: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightMedium,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            code: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightRegular,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingNormal,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
              fontFamily: 'monospace',
              backgroundColor: context.isDarkTheme
                  ? palette.surfaceSecondary
                  : AppColors.text10,
            ),
            codeblockDecoration: BoxDecoration(
              color: context.isDarkTheme
                  ? palette.surfaceSecondary
                  : AppColors.text10,
              borderRadius: BorderRadius.circular(4),
            ),
            listBullet: TextStyle(
              fontSize: AppTextStyles.fontSizeSmall,
              fontWeight: AppTextStyles.fontWeightRegular,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingWide,
              color: palette.textPrimary,
            ),
            listBulletPadding: const EdgeInsets.only(right: 8),
          ),
        ),
      ],
    );
  }

  /// 构建执行记录列表
  Widget _buildExecutionList() {
    final palette = context.omniPalette;
    if (_executionRecords.isEmpty) {
      return _buildEmptyState();
    }

    return Container(
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
        borderRadius: BorderRadius.circular(4),
        border: context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: _executionRecords.asMap().entries.map((entry) {
          final index = entry.key;
          final record = entry.value;
          final isLast = index == _executionRecords.length - 1;
          return _buildExecutionItem(record, isLast);
        }).toList(),
      ),
    );
  }

  /// 构建单条执行记录
  Widget _buildExecutionItem(ExecutionRecord record, bool isLast) {
    final palette = context.omniPalette;
    final status = record.status.displayName;
    final timeLabel = _formatRecordTime(record.createdAt);

    return Container(
      padding: EdgeInsets.only(top: 16, bottom: isLast ? 16 : 8),
      decoration: BoxDecoration(
        border: isLast
            ? null
            : Border(
                bottom: BorderSide(
                  color: context.isDarkTheme
                      ? palette.borderSubtle
                      : const Color(0xFFEEEEEE),
                  width: 0.5,
                ),
              ),
      ),
      child: Row(
        children: [
          // 左侧内容
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  status,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  timeLabel,
                  style: TextStyle(
                    fontSize: 10,
                    color: context.isDarkTheme
                        ? palette.textSecondary
                        : const Color(0xFF999999),
                    height: 1.6,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 构建空状态
  Widget _buildEmptyState() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(32),
      child: Center(
        child: Text(
          context.l10n.trajectoryNoRecords,
          style: TextStyle(
            fontSize: AppTextStyles.fontSizeMain,
            color: context.isDarkTheme
                ? palette.textSecondary
                : AppColors.text50,
          ),
        ),
      ),
    );
  }

  /// 构建加载指示器
  Widget _buildLoadingIndicator() {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(24.0),
        child: SizedBox(
          width: 32,
          height: 32,
          child: CircularProgressIndicator(strokeWidth: 2.5),
        ),
      ),
    );
  }

  /// 格式化最后执行时间
  String _formatLastExecutionTime(int timestamp) {
    if (timestamp == 0) return context.trLegacy('未知');

    final now = DateTime.now();
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);

    if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day) {
      return '${context.trLegacy('今天')} ${DateFormat('HH:mm').format(date)}';
    } else if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day - 1) {
      return '${context.trLegacy('昨天')} ${DateFormat('HH:mm').format(date)}';
    } else {
      return DateFormat('yyyy/MM/dd HH:mm').format(date);
    }
  }

  /// 格式化记录时间
  String _formatRecordTime(int timestamp) {
    if (timestamp == 0) return context.trLegacy('未知');

    final now = DateTime.now();
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);

    if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day) {
      return '${context.trLegacy('今天')} ${DateFormat('HH:mm').format(date)}';
    } else if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day - 1) {
      return '${context.trLegacy('昨天')} ${DateFormat('HH:mm').format(date)}';
    } else {
      return DateFormat('yyyy/MM/dd HH:mm').format(date);
    }
  }
}
