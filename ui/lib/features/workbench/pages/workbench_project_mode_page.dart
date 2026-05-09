import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchProjectModePage extends StatefulWidget {
  const WorkbenchProjectModePage({
    super.key,
    WorkbenchProjectModeService? service,
  }) : _service = service;

  final WorkbenchProjectModeService? _service;

  @override
  State<WorkbenchProjectModePage> createState() =>
      _WorkbenchProjectModePageState();
}

class _WorkbenchProjectModePageState extends State<WorkbenchProjectModePage> {
  late final WorkbenchProjectModeService _service;
  String? _selectedProjectId;
  WorkbenchProjectExportResult? _lastExportResult;

  @override
  void initState() {
    super.initState();
    _service = widget._service ?? WorkbenchProjectModeService.native();
    _refreshProjects();
  }

  Future<void> _refreshProjects({String? selectProjectId}) async {
    await _service.refresh();
    if (!mounted) return;
    _selectAvailableProject(preferredProjectId: selectProjectId);
  }

  void _selectAvailableProject({String? preferredProjectId}) {
    final projects = _service.projects;
    if (projects.isEmpty) {
      if (_selectedProjectId != null) {
        setState(() => _selectedProjectId = null);
      }
      return;
    }
    WorkbenchProject? selected;
    final preferred = preferredProjectId?.trim();
    if (preferred != null && preferred.isNotEmpty) {
      selected = _findProject(preferred);
    }
    if (selected == null && _selectedProjectId != null) {
      selected = _findProject(_selectedProjectId!);
    }
    selected ??= _service.activeProject == null
        ? null
        : _findProject(_service.activeProject!.projectId);
    selected ??= _findProject(workbenchTodoDefaultProjectId);
    selected ??= projects.first;
    if (_selectedProjectId != selected.projectId) {
      setState(() => _selectedProjectId = selected!.projectId);
    }
  }

  WorkbenchProject? get _currentProject {
    if (_selectedProjectId != null) {
      final selected = _findProject(_selectedProjectId!);
      if (selected != null) return selected;
    }
    return _service.activeProject == null
        ? (_service.projects.isEmpty ? null : _service.projects.first)
        : _findProject(_service.activeProject!.projectId) ??
              (_service.projects.isEmpty ? null : _service.projects.first);
  }

  WorkbenchProject? _findProject(String projectId) {
    for (final project in _service.projects) {
      if (project.projectId == projectId) return project;
    }
    return null;
  }

  bool _isActiveProject(WorkbenchProject project) {
    return _service.activeProject?.projectId == project.projectId;
  }

  String _projectDisplayName(WorkbenchProject project) {
    final name = project.name.trim();
    return name.isEmpty ? project.projectId : name;
  }

  Future<void> _activateProject(
    WorkbenchProject project, {
    bool returnHome = false,
  }) async {
    final active = await _service.activateProject(project);
    if (!mounted) return;
    if (active == null) {
      showToast(
        context.l10n.workbenchProjectActivateFailed,
        type: ToastType.error,
      );
      return;
    }
    setState(() => _selectedProjectId = active.projectId);
    showToast(
      context.l10n.workbenchProjectActivated(_projectDisplayName(active)),
      type: ToastType.success,
    );
    if (returnHome) {
      context.go(GoRouterManager.homeRoute);
    }
  }

  void _openDisplay(
    WorkbenchProject project,
    WorkbenchDisplaySpec display, {
    bool debugMode = false,
  }) {
    final route = _displayRoute(project, display, debugMode: debugMode);
    if (debugMode) {
      context.push(route);
      return;
    }
    context.go(route);
  }

  String _displayRoute(
    WorkbenchProject project,
    WorkbenchDisplaySpec display, {
    required bool debugMode,
  }) {
    final rawRoute = display.route.trim().isEmpty
        ? project.route.trim()
        : display.route.trim();
    final resolvedRoute = rawRoute.isEmpty
        ? '/workbench/todo_log?projectId=${Uri.encodeQueryComponent(project.projectId)}'
        : rawRoute;
    final uri = Uri.parse(resolvedRoute);
    final params = <String, String>{
      ...uri.queryParameters,
      'projectId': project.projectId,
      'displayId': display.id,
    };
    if (debugMode) {
      params['debug'] = '1';
      params['returnTo'] = '/workbench/projects';
    } else {
      params.remove('debug');
      params.remove('returnTo');
    }
    return uri.replace(queryParameters: params).toString();
  }

  Future<void> _openWorkspace(WorkbenchProject project) async {
    final paths = await OmnibotResourceService.ensureWorkspacePathsLoaded();
    if (!mounted) return;
    context.push(
      '/home/omnibot_workspace',
      extra: {
        'workspaceId': project.projectId,
        'workspacePath': _androidWorkspacePath(paths, project),
        'workspaceShellPath': project.spacePath,
      },
    );
  }

  Future<void> _exportProject(WorkbenchProject project) async {
    final result = await _service.exportProject(project);
    if (!mounted) return;
    if (result == null || !result.success) {
      showToast(
        context.l10n.workbenchProjectExportFailed,
        type: ToastType.error,
      );
      return;
    }
    setState(() => _lastExportResult = result);
    showToast(
      context.l10n.workbenchProjectExported(result.packageName),
      type: ToastType.success,
    );
  }

  Future<void> _confirmDeleteProject(WorkbenchProject project) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(context.l10n.workbenchDeleteProjectTitle),
          content: Text(
            context.l10n.workbenchDeleteProjectMessage(project.projectId),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(context.l10n.workbenchDeleteProjectCancel),
            ),
            FilledButton.tonal(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(context.l10n.workbenchDeleteProjectConfirm),
            ),
          ],
        );
      },
    );
    if (confirmed != true) return;
    final result = await _service.deleteProject(project);
    if (!mounted) return;
    if (result == null || !result.success) {
      showToast(
        context.l10n.workbenchDeleteProjectFailed,
        type: ToastType.error,
      );
      return;
    }
    setState(() {
      _selectedProjectId = _service.projects.isEmpty
          ? null
          : _service.projects.first.projectId;
      _lastExportResult = null;
    });
    showToast(context.l10n.workbenchProjectDeleted, type: ToastType.success);
  }

  void _showHelpDialog() {
    final palette = context.omniPalette;
    showDialog<void>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(context.l10n.workbenchProjectHelpTitle),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildHelpRow(
                Icons.chat_bubble_outline_rounded,
                context.l10n.workbenchProjectHelpHomeInput,
              ),
              const SizedBox(height: 10),
              _buildHelpRow(
                Icons.check_circle_outline_rounded,
                context.l10n.workbenchProjectHelpSelect,
              ),
              const SizedBox(height: 10),
              _buildHelpRow(
                Icons.phone_android_rounded,
                context.l10n.workbenchProjectHelpDisplays,
              ),
              const SizedBox(height: 10),
              _buildHelpRow(
                Icons.api_rounded,
                context.l10n.workbenchProjectHelpApis,
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(MaterialLocalizations.of(context).okButtonLabel),
            ),
          ],
          backgroundColor: palette.surfacePrimary,
        );
      },
    );
  }

  void _handleBackNavigation() {
    context.go(GoRouterManager.homeRoute);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: context.l10n.workbenchProjectModeTitle,
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(
          child: AnimatedBuilder(
            animation: _service,
            builder: (context, _) {
              final project = _currentProject;
              return RefreshIndicator(
                onRefresh: () =>
                    _refreshProjects(selectProjectId: _selectedProjectId),
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                  children: [
                    if (_service.loading) ...[
                      const LinearProgressIndicator(minHeight: 2),
                      const SizedBox(height: 12),
                    ],
                    _buildIntroPanel(),
                    if (_service.projects.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      _buildProjectSwitcher(project),
                    ],
                    const SizedBox(height: 12),
                    if (project == null)
                      _buildEmptyProjectPanel()
                    else
                      _buildProjectPanel(project),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildIntroPanel() {
    final palette = context.omniPalette;
    return _buildSurface(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: Icon(
              Icons.dashboard_customize_outlined,
              color: palette.accentPrimary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  context.l10n.workbenchProjectModeTitle,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  context.l10n.workbenchProjectModeSubtitle,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 13,
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: context.l10n.workbenchProjectHelpTooltip,
            onPressed: _showHelpDialog,
            icon: const Icon(Icons.info_outline_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectSwitcher(WorkbenchProject? selectedProject) {
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchProjectSwitcher,
            icon: Icons.view_week_rounded,
            trailing: _buildCountBadge(_service.projects.length.toString()),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _service.projects
                .map((project) {
                  final selected =
                      project.projectId == selectedProject?.projectId;
                  final active = _isActiveProject(project);
                  final label = active
                      ? '${_projectDisplayName(project)} · ${context.l10n.workbenchActiveProject}'
                      : _projectDisplayName(project);
                  return ChoiceChip(
                    selected: selected,
                    label: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 230),
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    onSelected: (_) {
                      setState(() => _selectedProjectId = project.projectId);
                      _activateProject(project);
                    },
                  );
                })
                .toList(growable: false),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectPanel(WorkbenchProject project) {
    final palette = context.omniPalette;
    final active = _isActiveProject(project);
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: _buildProjectIdentity(project)),
              const SizedBox(width: 8),
              _buildCountBadge(
                active
                    ? context.l10n.workbenchActiveProject
                    : context.l10n.workbenchInactiveProject,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildTinyCode(project.projectId),
              _buildCountBadge(
                context.l10n.workbenchDisplayCount(project.displays.length),
              ),
              _buildCountBadge(
                context.l10n.workbenchApiCount(project.tools.length),
              ),
              _buildCountBadge(
                context.l10n.workbenchTodoCount(
                  project.openTodos.length,
                  project.finishedTodos.length,
                ),
              ),
              _buildCountBadge(
                context.l10n.workbenchAndroidAssetCount(
                  project.androidAssets.length,
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _service.loading
                  ? null
                  : () => _activateProject(project, returnHome: true),
              icon: const Icon(Icons.keyboard_return_rounded),
              label: Text(context.l10n.workbenchContinueInHome),
            ),
          ),
          const SizedBox(height: 14),
          _buildDisplayList(project),
          const SizedBox(height: 14),
          Divider(color: palette.borderSubtle, height: 1),
          const SizedBox(height: 14),
          _buildSectionHeader(
            title: context.l10n.workbenchProjectApiForProject(
              _projectDisplayName(project),
            ),
            icon: Icons.api_rounded,
            trailing: _buildCountBadge(
              context.l10n.workbenchApiCount(project.tools.length),
            ),
          ),
          const SizedBox(height: 12),
          _buildApiList(project),
          const SizedBox(height: 14),
          _buildAndroidAssets(project),
          const SizedBox(height: 14),
          _buildProjectActions(project),
          if (_lastExportResult != null) ...[
            const SizedBox(height: 10),
            _buildInlineStatus(
              icon: Icons.archive_outlined,
              label: context.l10n.workbenchProjectExportPath(
                _lastExportResult!.displayPath,
              ),
              color: palette.textSecondary,
            ),
          ],
          if (_service.errorMessage != null) ...[
            const SizedBox(height: 10),
            _buildInlineStatus(
              icon: Icons.error_outline_rounded,
              label: context.l10n.workbenchProjectModeLoadFailed,
              color: const Color(0xFFDC2626),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildDisplayList(WorkbenchProject project) {
    final displays = project.displays.isEmpty
        ? [project.primaryDisplay]
        : project.displays;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildSectionHeader(
          title: context.l10n.workbenchDisplaysTitle,
          icon: Icons.phone_android_rounded,
          trailing: _buildCountBadge(
            context.l10n.workbenchDisplayCount(displays.length),
          ),
        ),
        const SizedBox(height: 10),
        Column(
          children: displays
              .map(
                (display) => Padding(
                  padding: EdgeInsets.only(
                    bottom: display == displays.last ? 0 : 10,
                  ),
                  child: _buildDisplayRow(project, display),
                ),
              )
              .toList(growable: false),
        ),
      ],
    );
  }

  Widget _buildDisplayRow(
    WorkbenchProject project,
    WorkbenchDisplaySpec display,
  ) {
    final palette = context.omniPalette;
    final description = display.description.trim();
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          Icon(Icons.phone_android_rounded, color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  display.label.trim().isEmpty
                      ? context.l10n.workbenchUnnamedDisplay
                      : display.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (description.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      height: 1.3,
                    ),
                  ),
                ],
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    _buildTinyCode(display.id),
                    _buildTinyCode(display.route),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          IconButton.filled(
            tooltip: context.l10n.workbenchOpenDisplay,
            onPressed: () => _openDisplay(project, display),
            icon: const Icon(Icons.open_in_new_rounded),
          ),
          const SizedBox(width: 6),
          IconButton.filledTonal(
            tooltip: context.l10n.workbenchDebugDisplay,
            onPressed: () => _openDisplay(project, display, debugMode: true),
            icon: const Icon(Icons.bug_report_outlined),
          ),
        ],
      ),
    );
  }

  Widget _buildApiList(WorkbenchProject project) {
    final apis = project.tools;
    if (apis.isEmpty) {
      return _buildInlineStatus(
        icon: Icons.info_outline_rounded,
        label: context.l10n.workbenchApiEmpty,
        color: context.omniPalette.textSecondary,
      );
    }
    return Column(
      children: apis
          .map(
            (tool) => Padding(
              padding: EdgeInsets.only(bottom: tool == apis.last ? 0 : 10),
              child: _buildToolRow(tool),
            ),
          )
          .toList(growable: false),
    );
  }

  Widget _buildToolRow(WorkbenchToolSpec tool) {
    final palette = context.omniPalette;
    final description = tool.description?.trim();
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          Icon(_toolIcon(tool.id), color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _toolTitle(tool),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                if (description != null && description.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      height: 1.3,
                    ),
                  ),
                ],
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    _buildTinyCode(tool.id),
                    _buildCountBadge(
                      context.l10n.workbenchToolExecutionCount(
                        tool.executionCount,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAndroidAssets(WorkbenchProject project) {
    if (project.androidAssets.isEmpty) {
      return _buildInlineStatus(
        icon: Icons.android_rounded,
        label: context.l10n.workbenchAndroidAssetsEmpty,
        color: context.omniPalette.textSecondary,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildSectionHeader(
          title: context.l10n.workbenchAndroidAssetsTitle,
          icon: Icons.android_rounded,
          trailing: _buildCountBadge(project.androidAssets.length.toString()),
        ),
        const SizedBox(height: 10),
        Column(
          children: project.androidAssets
              .map(
                (asset) => Padding(
                  padding: EdgeInsets.only(
                    bottom: asset == project.androidAssets.last ? 0 : 8,
                  ),
                  child: _buildAndroidAssetRow(asset),
                ),
              )
              .toList(growable: false),
        ),
      ],
    );
  }

  Widget _buildAndroidAssetRow(WorkbenchAndroidAsset asset) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            asset.isApk ? Icons.android_rounded : Icons.folder_copy_outlined,
            color: palette.accentPrimary,
            size: 20,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  asset.displayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 5),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    _buildTinyCode(asset.sourceKind),
                    if (asset.packageName?.trim().isNotEmpty == true)
                      _buildTinyCode(asset.packageName!),
                    _buildTinyCode(asset.displayPath),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectActions(WorkbenchProject project) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        OutlinedButton.icon(
          onPressed: () => _openWorkspace(project),
          icon: const Icon(Icons.folder_open_rounded),
          label: Text(context.l10n.workbenchOpenWorkspace),
        ),
        OutlinedButton.icon(
          onPressed: _service.loading ? null : () => _exportProject(project),
          icon: const Icon(Icons.inventory_2_outlined),
          label: Text(context.l10n.workbenchExportProjectPackage),
        ),
        TextButton.icon(
          onPressed: _service.loading
              ? null
              : () => _confirmDeleteProject(project),
          icon: const Icon(Icons.delete_outline_rounded),
          label: Text(context.l10n.workbenchDeleteProject),
          style: TextButton.styleFrom(foregroundColor: const Color(0xFFDC2626)),
        ),
      ],
    );
  }

  Widget _buildEmptyProjectPanel() {
    return _buildSurface(
      child: _buildInlineStatus(
        icon: Icons.info_outline_rounded,
        label: context.l10n.workbenchProjectModeEmpty,
        color: context.omniPalette.textSecondary,
      ),
    );
  }

  Widget _buildProjectIdentity(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _projectDisplayName(project),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 18,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          project.spacePath,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _buildHelpRow(IconData icon, String label) {
    final palette = context.omniPalette;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: palette.accentPrimary),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            label,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 13,
              height: 1.35,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSurface({required Widget child}) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: child,
    );
  }

  Widget _buildSectionHeader({
    required String title,
    required IconData icon,
    Widget? trailing,
  }) {
    final palette = context.omniPalette;
    return Row(
      children: [
        Icon(icon, color: palette.accentPrimary),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            title,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        if (trailing != null) trailing,
      ],
    );
  }

  Widget _buildTinyCode(String value) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        value,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }

  Widget _buildCountBadge(String value) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        value,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 12,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }

  Widget _buildInlineStatus({
    required IconData icon,
    required String label,
    required Color color,
  }) {
    return Row(
      children: [
        Icon(icon, color: color, size: 16),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
  }

  IconData _toolIcon(String toolId) {
    return toolId == WorkbenchTodoToolIds.addTodo
        ? Icons.add_task_rounded
        : Icons.task_alt_rounded;
  }

  String _toolTitle(WorkbenchToolSpec tool) {
    final displayName = tool.displayName?.trim();
    if (displayName != null && displayName.isNotEmpty) return displayName;
    return tool.id == WorkbenchTodoToolIds.addTodo
        ? context.l10n.workbenchToolAddTodoTitle
        : tool.id == WorkbenchTodoToolIds.finishTodo
        ? context.l10n.workbenchToolFinishTodoTitle
        : tool.id;
  }

  String _androidWorkspacePath(
    OmnibotWorkspacePaths paths,
    WorkbenchProject project,
  ) {
    final shellPath = project.spacePath.trim();
    if (shellPath.startsWith(paths.shellRootPath)) {
      final suffix = shellPath.substring(paths.shellRootPath.length);
      return '${paths.rootPath}$suffix';
    }
    return paths.rootPath;
  }
}
