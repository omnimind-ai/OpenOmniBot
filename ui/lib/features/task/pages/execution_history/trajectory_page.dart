import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/core/mixins/page_lifecycle_mixin.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_section.dart';
import 'package:ui/features/task/pages/execution_history/task_execution_detail_page.dart';
import 'package:ui/features/task/pages/scheduled_tasks/widgets/schedule_task_sheet.dart';
import 'package:ui/models/task_execution_info.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/image_util.dart';
import 'package:ui/features/task/pages/execution_history/widgets/execution_record_list_item.dart';
import 'package:intl/intl.dart';

import 'package:ui/models/execution_record.dart';
import 'package:ui/widgets/context_menu.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/batch_delete_confirm_sheet.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/features/task/pages/execution_history/widgets/execution_record_list.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/image/cached_image.dart';
import 'package:ui/widgets/selection_bottom_bar.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/features/task/pages/execution_history/widgets/activity_dashboard_card.dart';

class TrajectoryPage extends StatefulWidget {
  const TrajectoryPage({super.key});

  @override
  State<TrajectoryPage> createState() =>
      _TrajectoryPageState();
}

class _TrajectoryPageState
    extends State<TrajectoryPage>
    with
        WidgetsBindingObserver,
        PageLifecycleMixin<TrajectoryPage> {
  List<AppTag> executionTags = [];

  List<TaskExecutionInfo> taskExecutionInfos = [];

  List<ExecutionRecordListItemData> executionRecordViewModels = [];

  bool _isLoading = true;
  bool _hasLoadedOnce = false;
  Set<String> selectedTagIds = {'all'};

  // 选择模式状态
  bool _isSelectionMode = false;
  Set<String> _selectedRecordKeys = {}; // 使用 title+packageName 作为唯一标识

  // Suggestion 缓存，key 为 nodeId|suggestionId（用于执行任务）
  Map<String, Map<String, dynamic>> _suggestionMap = {};
  Set<String> _scheduledTaskKeys = {};

  @override
  void initState() {
    super.initState();
    _loadData();
    _loadScheduledTaskKeys();
  }

  // ✅ 应用从后台回到前台时刷新
  @override
  void onPageResumed() {
    if (_hasLoadedOnce) {
      print('✅ ExecutionRecordPage resumed - reloading data silently');
      _loadData(silent: true);
      _loadScheduledTaskKeys();
    }
  }

  void _onTagSelectionChanged(Set<String> next, String triggerId) {
    setState(() {
      if (next.contains('all')) {
        if (triggerId != 'all' && next.length > 1) {
          next.remove('all');
        } else if (triggerId == 'all') {
          next = {'all'};
        }
      } else if (next.isEmpty) {
        next = {'all'};
      }
      selectedTagIds = next;
    });
  }

  // 从数据库加载数据
  /// [silent] 是否静默刷新（不显示loading）
  Future<void> _loadData({bool silent = false}) async {
    if (!silent) {
      setState(() {
        _isLoading = true;
      });
    }

    try {
      await Future.wait([_loadSuggestionMap(), _loadTaskExecutionInfos()]);
      await _loadExecutionTags();

      setState(() {
        if (!silent) {
          _isLoading = false;
        }
      });
      _hasLoadedOnce = true;
    } catch (e) {
      print('Error loading data: $e');
      if (!silent) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  /// 加载 Suggestion 数据并缓存到 _suggestionMap
  Future<void> _loadSuggestionMap() async {
    // 开源版仅保留 VLM 回放能力。
    _suggestionMap = {};
  }

  Future<void> _loadScheduledTaskKeys() async {
    try {
      final tasks = await ScheduledTaskStorageService.loadScheduledTasks();
      if (!mounted) return;
      setState(() {
        _scheduledTaskKeys = tasks
            .map((task) => '${task.nodeId}|${task.suggestionId}')
            .toSet();
      });
    } catch (e) {
      print('Error loading scheduled task keys: $e');
    }
  }

  /// 生成 Suggestion key（与执行记录匹配）
  String _getSuggestionKey(String nodeId, String suggestionId) {
    return '$nodeId|$suggestionId';
  }

  // 加载执行记录信息
  Future<void> _loadTaskExecutionInfos() async {
    try {
      final records = await CacheUtil.getTaskExecutionInfos();

      setState(() {
        taskExecutionInfos = records;
      });
    } catch (e) {
      print('Error loading execution records: $e');
    }
  }

  // 加载执行记录标签数据 TODO 优化抽取函数
  Future<void> _loadExecutionTags() async {
    try {
      // 1) 先构建 tagList 数据源
      final tagList = <AppTag>[];
      final totalCount = taskExecutionInfos.length;
      tagList.add(
        AppTag(
          id: 'all',
          label: context.l10n.trajectoryAll,
          count: totalCount,
          svgPath: 'assets/common/all_icon.svg',
          iconBgColor: Colors.black,
          iconColor: Colors.white,
        ),
      );

      // 收集需要获取图标的 packageName
      final packageNames = <String>{};
      //将taskexecutioninfos按packagename分组，统计数量，并加入到packagenames中
      for (final info in taskExecutionInfos) {
        final appName = info.appName;
        final packageName = info.packageName;

        if (packageName.isNotEmpty && !packageNames.contains(packageName)) {
          packageNames.add(packageName);
          tagList.add(
            AppTag(
              id: packageName,
              label: appName,
              count: taskExecutionInfos
                  .where((e) => e.packageName == packageName)
                  .length,
              icon: Icons.apps,
              iconBgColor: const Color(0xFFE6F0FE),
              appIconProvider: null,
            ),
          );
        }
      }

      // 2) 批量获取图标
      Map<String, ImageProvider?> iconProv = {};
      if (mounted) {
        iconProv = await ImageUtil.batchLoadAppIcons(packageNames, context);
      } else {
        iconProv = {};
      }

      // 3) 将图标数据填回 tagList 的 appIconProvider（避免再次 setState）
      final tagListWithIcons = tagList.map((t) {
        if (t.appIconProvider == null && iconProv.containsKey(t.id)) {
          return AppTag(
            id: t.id,
            label: t.label,
            count: t.count,
            icon: t.icon,
            iconBgColor: t.iconBgColor,
            iconColor: t.iconColor,
            appIconProvider: iconProv[t.id],
          );
        }
        return t;
      }).toList();

      // 4) 将图标数据填回 executionRecordViewModels 的 icons（避免再次 setState）
      final defaultIcon = Builder(
        builder: (context) {
          final palette = context.omniPalette;
          return Container(
            width: 20,
            height: 20,
            decoration: BoxDecoration(
              color: context.isDarkTheme
                  ? palette.surfaceElevated
                  : Colors.grey[300],
              borderRadius: BorderRadius.circular(2),
              border: context.isDarkTheme
                  ? Border.all(color: palette.borderSubtle)
                  : null,
            ),
            child: Icon(
              Icons.apps,
              size: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : Colors.grey[600],
            ),
          );
        },
      );

      final modelsWithIcons = taskExecutionInfos.map((info) {
        final section = _getSection(info.lastExecutionTime);
        final iconsList = <Widget>[];

        // 1. 添加 App 图标
        if (iconProv.containsKey(info.packageName)) {
          iconsList.add(
            ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: Image(
                image: iconProv[info.packageName]!,
                width: 20,
                height: 20,
                fit: BoxFit.cover,
                errorBuilder: (context, error, stackTrace) {
                  final palette = context.omniPalette;
                  return Container(
                    width: 20,
                    height: 20,
                    decoration: BoxDecoration(
                      color: context.isDarkTheme
                          ? palette.surfaceElevated
                          : Colors.grey[300],
                      borderRadius: BorderRadius.circular(2),
                      border: context.isDarkTheme
                          ? Border.all(color: palette.borderSubtle)
                          : null,
                    ),
                    child: Icon(
                      Icons.apps,
                      size: 16,
                      color: context.isDarkTheme
                          ? palette.textSecondary
                          : Colors.grey[600],
                    ),
                  );
                },
              ),
            ),
          );
        } else {
          iconsList.add(defaultIcon);
        }

        // 2. 添加技能类型图标（suggestion iconUrl 或默认类型图标）
        print(
          'Adding type icon for ${info.title} with iconUrl: ${info.iconUrl}',
        );
        print('ExecutionRecordType: ${info.type}');
        iconsList.add(_buildTypeIcon(info.type, info.iconUrl));

        // 3. 判断是否可执行（参照 SkillGridItem 的判断逻辑）
        final suggestionKey = _getSuggestionKey(info.nodeId, info.suggestionId);
        final suggestionData =
            _suggestionMap[suggestionKey] ?? _buildVlmSuggestionData(info);
        final isExecutable = suggestionData != null
            ? _isExecutable(suggestionData, info.type)
            : false;
        final isSchedulable = suggestionData != null
            ? _isSchedulable(suggestionData, info.type, isExecutable)
            : false;

        return ExecutionRecordListItemData(
          id: info.id,
          title: info.title,
          packageName: info.packageName,
          nodeId: info.nodeId,
          suggestionId: info.suggestionId,
          times: info.count,
          section: section,
          lastExecutionTimeLabel: _getTimeLabel(
            section,
            info.lastExecutionTime,
          ),
          icons: iconsList,
          isExecutable: isExecutable,
          isSchedulable: isSchedulable,
          suggestionData: suggestionData,
          onExecute: isExecutable
              ? () => _executeTask(info, suggestionData)
              : null,
        );
      }).toList();

      setState(() {
        executionTags = tagListWithIcons;
        executionRecordViewModels = modelsWithIcons;
      });
    } catch (e) {
      print('Error loading execution tags: $e');
    }
  }

  bool _isExecutable(
    Map<String, dynamic> suggestionData,
    ExecutionRecordType type,
  ) {
    final isAppInstalled = suggestionData['isInstalled'] as bool? ?? false;
    final isHomeTask = suggestionData['isHomeTask'] as bool? ?? false;
    final requireChatbotTrigger =
        suggestionData['triggerType'] == 'require_chatbot_trigger';

    // 检测 tasks 中的 slots 是否为空，判断是否需要额外信息
    bool needsExtraInfo = false;
    final tasks = suggestionData['tasks'];
    if (tasks != null && tasks is List && tasks.isNotEmpty) {
      for (final task in tasks) {
        final slots = task['slots'];
        if (slots != null && slots is List && slots.isNotEmpty) {
          needsExtraInfo = true;
          break;
        }
      }
    }

    // 任务可执行的条件（与 SkillGridItem 一致）：
    // 1. 不是学习任务
    // 2. 应用已安装
    // 3. 是首页任务
    // 4. 不需要 chatbot 触发
    // 5. 不需要额外信息
    return isAppInstalled &&
        isHomeTask &&
        !requireChatbotTrigger &&
        !needsExtraInfo;
  }

  Map<String, dynamic>? _buildVlmSuggestionData(TaskExecutionInfo info) {
    if (info.type != ExecutionRecordType.vlm) {
      return null;
    }
    final goal = info.suggestionId.trim().isNotEmpty
        ? info.suggestionId.trim()
        : info.title.trim();
    if (goal.isEmpty) {
      return null;
    }
    return {
      'goal': goal,
      'packageName': info.packageName,
      'nodeId': info.nodeId,
      'suggestionId': info.suggestionId,
    };
  }

  bool _isSchedulable(
    Map<String, dynamic> suggestionData,
    ExecutionRecordType type,
    bool isExecutable,
  ) {
    if (type == ExecutionRecordType.vlm) {
      final goal = (suggestionData['goal'] as String?)?.trim() ?? '';
      return goal.isNotEmpty;
    }
    return isExecutable;
  }

  /// 执行任务
  Future<void> _executeTask(
    TaskExecutionInfo info,
    Map<String, dynamic> suggestionData,
  ) async {
    final goal = (suggestionData['goal'] as String?)?.trim() ?? '';
    if (goal.isEmpty) {
      showToast('Current record does not support execution', type: ToastType.error);
      return;
    }

    final success = await AssistsMessageService.createVLMOperationTask(
      goal,
      packageName: info.packageName,
    );
    if (!mounted) return;
    if (success) {
      showToast('Task started', type: ToastType.success);
    } else {
      showToast('Task execution failed', type: ToastType.error);
    }
  }

  Future<void> _onSchedulePressed(ExecutionRecordListItemData record) async {
    if (record.suggestionData == null) {
      showToast('Current record does not support scheduling', type: ToastType.error);
      return;
    }

    final existingTask =
        await ScheduledTaskStorageService.getScheduledTaskBySuggestionId(
          record.nodeId,
          record.suggestionId,
        );
    if (!mounted) {
      return;
    }

    final result = await ScheduleTaskSheet.show(
      context: context,
      taskTitle: record.title,
      packageName: record.packageName,
      nodeId: record.nodeId,
      suggestionId: record.suggestionId,
      suggestionData: record.suggestionData,
      existingTask: existingTask,
    );

    if (result == null) {
      return;
    }

    await ScheduledTaskStorageService.addScheduledTask(result);
    ScheduledTaskSchedulerService.scheduleTask(result);
    await _loadScheduledTaskKeys();
    if (mounted) {
      showToast('Scheduled task set', type: ToastType.success);
    }
  }

  /// 构建类型图标（优先使用 icon URL，否则使用类型默认图标）
  Widget _buildTypeIcon(ExecutionRecordType type, String? iconUrl) {
    const double iconSize = 20.0;

    // 如果有 icon URL，优先显示网络图标
    if (iconUrl != null && iconUrl.isNotEmpty) {
      return CachedImage(
        imageUrl: iconUrl,
        width: iconSize,
        height: iconSize,
        fit: BoxFit.cover,
        errorWidget: _buildDefaultTypeIcon(type, iconSize),
      );
    }

    // 没有 icon URL 时使用默认类型图标
    return _buildDefaultTypeIcon(type, iconSize);
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

  void _showContextMenu(
    ExecutionRecordListItemData vm,
    BuildContext context,
    Offset position,
  ) async {
    final action = await showRecordContextMenu(
      context: context,
      position: position,
    );
    switch (action) {
      // case RecordMenuAction.edit:
      //   _editRecord(vm.title, vm.id);
      //   break;
      case RecordMenuAction.delete:
        _deleteExecutionRecord(vm.id);
        break;
      default:
        break;
    }
  }

  /// 点击执行记录，跳转到详情页
  void _navigateToDetail(ExecutionRecordListItemData vm) {
    // 查找对应的 TaskExecutionInfo
    final info = taskExecutionInfos.firstWhere(
      (e) => e.nodeId == vm.nodeId && e.suggestionId == vm.suggestionId,
      orElse: () => TaskExecutionInfo(
        id: vm.id,
        appName: '',
        packageName: vm.packageName,
        title: vm.title,
        nodeId: vm.nodeId,
        suggestionId: vm.suggestionId,
        count: vm.times,
        lastExecutionTime: 0,
      ),
    );

    final params = TaskExecutionDetailParams(
      title: vm.title,
      packageName: vm.packageName,
      appName: info.appName,
      nodeId: vm.nodeId,
      suggestionId: vm.suggestionId,
      totalCount: vm.times,
      lastExecutionTime: info.lastExecutionTime,
      type: info.type,
      iconUrl: info.iconUrl,
      content: info.content,
    );

    GoRouterManager.push('/task/execution_detail', extra: params.toMap());
  }

  /// 长按进入选择模式
  void _enterSelectionMode(ExecutionRecordListItemData vm) {
    final key = _getRecordKey(vm);
    setState(() {
      _isSelectionMode = true;
      _selectedRecordKeys.add(key);
    });
  }

  /// 生成记录唯一标识（使用 nodeId+suggestionId）
  String _getRecordKey(ExecutionRecordListItemData vm) {
    return '${vm.nodeId}|${vm.suggestionId}';
  }

  /// 切换记录选中状态
  void _toggleRecordSelection(ExecutionRecordListItemData vm) {
    final key = _getRecordKey(vm);
    setState(() {
      if (_selectedRecordKeys.contains(key)) {
        _selectedRecordKeys.remove(key);
      } else {
        _selectedRecordKeys.add(key);
      }
    });
  }

  /// 退出选择模式
  void _exitSelectionMode() {
    setState(() {
      _isSelectionMode = false;
      _selectedRecordKeys.clear();
    });
  }

  /// 全选/全不选
  void _toggleSelectAll(List<ExecutionRecordListItemData> records) {
    setState(() {
      if (_selectedRecordKeys.length == records.length) {
        // 已全选，取消全选
        _selectedRecordKeys.clear();
      } else {
        // 未全选，全选
        _selectedRecordKeys = records.map((r) => _getRecordKey(r)).toSet();
      }
    });
  }

  /// 批量删除选中的记录
  Future<void> _batchDeleteSelectedRecords() async {
    final count = _selectedRecordKeys.length;
    if (count == 0) return;

    // 显示底部确认弹窗
    final result = await showModalBottomSheet<bool>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) =>
          BatchDeleteConfirmSheet(count: count, unit: ' ${context.l10n.trajectoryTaskRecords}'),
    );

    if (result == true) {
      // 执行批量删除
      int successCount = 0;
      for (final key in _selectedRecordKeys.toList()) {
        final parts = key.split('|');
        if (parts.length == 2) {
          final nodeId = parts[0];
          final suggestionId = parts[1];
          final success = await _performBatchDeleteByNodeAndSuggestionId(
            nodeId,
            suggestionId,
          );
          if (success) {
            successCount++;
          }
        }
      }

      // 退出选择模式
      _exitSelectionMode();

      // 重新加载标签统计
      await _loadExecutionTags();


      // 显示删除结果
      if (successCount > 0) {
        showToast(context.l10n.skillDeleted, type: ToastType.success);
      }
    }
  }

  /// 执行单条批量删除（不显示弹窗，使用 nodeId+suggestionId）
  Future<bool> _performBatchDeleteByNodeAndSuggestionId(
    String nodeId,
    String suggestionId,
  ) async {
    try {
      bool success = await CacheUtil.deleteExecutionRecordByNodeAndSuggestionId(
        nodeId,
        suggestionId,
      );
      if (success) {
        // 从本地列表中删除匹配的记录
        setState(() {
          taskExecutionInfos.removeWhere(
            (record) =>
                record.nodeId == nodeId && record.suggestionId == suggestionId,
          );
        });
      }
      return success;
    } catch (e) {
      print('Error deleting task records: $e');
      return false;
    }
  }

  // 删除执行记录（单条）
  void _deleteExecutionRecord(int recordId) {
    AppDialog.confirm(
      context,
      title: context.l10n.memoryDeleteConfirmTitle,
      content: context.l10n.memoryDeleteWarning,
      cancelText: context.trLegacy('取消'),
      confirmText: context.l10n.skillDelete,
      confirmButtonColor: AppColors.alertRed,
    ).then((result) async {
      if (result == true) {
        await _performExecutionDelete(recordId);
      }
    });
  }

  // 执行删除操作（单条）
  Future<void> _performExecutionDelete(int recordId) async {
    try {
      bool success = await CacheUtil.deleteExecutionRecordById(recordId);
      if (!success) {
        showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
        return;
      }

      // 从本地列表中删除
      setState(() {
        taskExecutionInfos.removeWhere((record) => record.id == recordId);
      });

      showToast(context.l10n.skillDeleted, type: ToastType.success);

      // 重新加载标签统计
      await _loadExecutionTags();

    } catch (e) {
      print('Error deleting card: $e');
      showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final allRecords = executionRecordViewModels;

    final filterRecords =
        (selectedTagIds.contains('all')) || selectedTagIds.isEmpty
        ? allRecords
        : allRecords.where((record) {
            return selectedTagIds.contains(record.packageName);
          }).toList();

    return Scaffold(
      backgroundColor: context.isDarkTheme
          ? palette.pageBackground
          : AppColors.background,
      appBar: _isSelectionMode
          ? _buildSelectionAppBar(filterRecords)
          : CommonAppBar(title: context.l10n.trajectoryTitle, showAiBadge: false, primary: true),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            Expanded(
              child: _isLoading
                  ? _buildLoadingIndicator()
                  : SingleChildScrollView(
                      child: Column(
                        children: [
                          // 选择模式下模糊顶部区域
                          ImageFiltered(
                            imageFilter: _isSelectionMode
                                ? ImageFilter.blur(sigmaX: 10, sigmaY: 10)
                                : ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Column(
                              children: [
                                SizedBox(height: 14),
                                const ActivityDashboardCard(),
                                SizedBox(height: 12),
                              ],
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            child: Align(
                              alignment: Alignment.centerLeft,
                              child: Text(
                                context.l10n.trajectoryTaskRecords,
                                style: TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w600,
                                  color: context.omniPalette.textPrimary,
                                ),
                              ),
                            ),
                          ),
                          SizedBox(height: 10),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            child: TagSection(
                              items: executionTags,
                              selectedIds: selectedTagIds,
                              onSelectionChanged: _onTagSelectionChanged,
                              maxCollapsedRows: 1,
                            ),
                          ),
                          if (allRecords.isNotEmpty) ...[
                            SizedBox(height: 8),
                            ExecutionRecordList(
                              records: filterRecords,
                              onDelete: _deleteExecutionRecord,
                              onMore: _showContextMenu,
                              onLongPress: (vm) => _enterSelectionMode(vm),
                              onTap: (vm) => _navigateToDetail(vm),
                              isSelectionMode: _isSelectionMode,
                              selectedKeys: _selectedRecordKeys,
                              onToggleSelection: _toggleRecordSelection,
                              getRecordKey: _getRecordKey,
                              onSchedulePressed: _onSchedulePressed,
                              scheduledTaskKeys: _scheduledTaskKeys,
                            ),
                          ] else
                            _buildEmptyRecordsHint(),
                        ],
                      ),
                    ),
            ),
            // 选择模式下的底部删除按钮栏
            SelectionBottomBar(
              isActive: _isSelectionMode,
              onDeletePressed: _selectedRecordKeys.isNotEmpty
                  ? _batchDeleteSelectedRecords
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  // 选择模式下的 AppBar
  PreferredSizeWidget _buildSelectionAppBar(
    List<ExecutionRecordListItemData> filterRecords,
  ) {
    final palette = context.omniPalette;
    final isAllSelected =
        _selectedRecordKeys.length == filterRecords.length &&
        filterRecords.isNotEmpty;
    return CommonAppBar(
      primary: true,
      title: context.l10n.trajectorySelectedCount(_selectedRecordKeys.length),
      titleStyle: TextStyle(
        fontSize: 17,
        fontWeight: FontWeight.w600,
        color: palette.textPrimary,
        fontFamily: 'SF Pro',
      ),
      leadingWidth: 64,
      leading: TextButton(
        onPressed: _exitSelectionMode,
        child: Text(
          context.trLegacy('取消'),
          style: TextStyle(
            color: palette.accentPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w400,
          ),
        ),
      ),
      actions: [
        SizedBox(
          width: 72,
          child: TextButton(
            onPressed: () => _toggleSelectAll(filterRecords),
            child: Text(
              isAllSelected ? context.l10n.memoryDeselectAll : context.trLegacy('全选'),
              style: TextStyle(
                color: palette.accentPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w400,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildEmptyRecordsHint() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 48),
      child: Column(
        children: [
          SvgPicture.asset(
            'assets/common/empty_record.svg',
            fit: BoxFit.contain,
            errorBuilder: (ctx, err, stack) => Icon(
              Icons.favorite_border,
              size: 72,
              color: context.isDarkTheme
                  ? palette.borderStrong
                  : Colors.grey.shade400,
            ),
          ),
          const SizedBox(height: 12),
          
          Text(
            context.l10n.trajectoryNoRecordsDesc,
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightRegular,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text20,
              height: AppTextStyles.lineHeightH2,
              letterSpacing: AppTextStyles.letterSpacingWide,
            ),
          ),
        ],
      ),
    );
  }

  String _getSection(int timestamp) {
    String section = context.l10n.trajectoryUnknownDate;
    final today = DateTime.now();
    final recordDate = DateTime.fromMillisecondsSinceEpoch(timestamp);
    if (recordDate.year == today.year &&
        recordDate.month == today.month &&
        recordDate.day == today.day) {
      section = context.trLegacy('今天');
    } else if (recordDate.year == today.year &&
        recordDate.month == today.month &&
        recordDate.day == today.day - 1) {
      section = context.trLegacy('昨天');
    } else {
      section = context.l10n.trajectoryThreeDaysAgo;
    }
    return section;
  }

  String _getTimeLabel(String section, int timestamp) {
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
    final today = DateTime.now();

    if (date.year == today.year &&
        date.month == today.month &&
        date.day == today.day) {
      return '${context.trLegacy('今天')} ' + DateFormat('HH:mm').format(date);
    } else if (date.year == today.year &&
        date.month == today.month &&
        date.day == today.day - 1) {
      return '${context.trLegacy('昨天')} ' + DateFormat('HH:mm').format(date);
    } else {
      return DateFormat('yyyy/MM/dd HH:mm').format(date);
    }
  }

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
}
