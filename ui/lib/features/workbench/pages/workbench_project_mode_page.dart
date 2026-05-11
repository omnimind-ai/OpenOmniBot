import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchProjectModePage extends StatefulWidget {
  const WorkbenchProjectModePage({
    super.key,
    WorkbenchProjectModeService? service,
    String? initialProjectId,
  }) : _service = service,
       _initialProjectId = initialProjectId;

  final WorkbenchProjectModeService? _service;
  final String? _initialProjectId;

  @override
  State<WorkbenchProjectModePage> createState() =>
      _WorkbenchProjectModePageState();
}

enum _ProjectMenuAction { workspace, export, delete }

class _WorkbenchProjectModePageState extends State<WorkbenchProjectModePage> {
  late final WorkbenchProjectModeService _service;
  String? _selectedProjectId;
  String? _detailProjectId;
  WorkbenchProjectExportResult? _lastExportResult;

  @override
  void initState() {
    super.initState();
    _service = widget._service ?? WorkbenchProjectModeService.native();
    _selectedProjectId = widget._initialProjectId?.trim();
    _refreshProjects(selectProjectId: _selectedProjectId);
  }

  @override
  void didUpdateWidget(covariant WorkbenchProjectModePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget._initialProjectId != widget._initialProjectId) {
      _refreshProjects(selectProjectId: widget._initialProjectId?.trim());
    }
  }

  Future<void> _refreshProjects({String? selectProjectId}) async {
    await _service.refresh();
    if (!mounted) return;
    _selectAvailableProject(preferredProjectId: selectProjectId);
    if (_detailProjectId != null && _findProject(_detailProjectId!) == null) {
      setState(() => _detailProjectId = null);
    }
  }

  void _returnHomeForProjectPrompt() {
    showToast(context.l10n.workbenchProjectCreateFromHome);
    context.go(GoRouterManager.homeRoute);
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
    selected ??= _findProject(workbenchQuickCaptureProjectId);
    selected ??= _firstNonTodoProject(projects);
    selected ??= projects.first;
    if (_selectedProjectId != selected.projectId) {
      setState(() => _selectedProjectId = selected!.projectId);
    }
  }

  WorkbenchProject? get _detailProject {
    if (_detailProjectId == null) return null;
    return _findProject(_detailProjectId!);
  }

  WorkbenchProject? _findProject(String projectId) {
    for (final project in _service.projects) {
      if (project.projectId == projectId) return project;
    }
    return null;
  }

  WorkbenchProject? _firstNonTodoProject(List<WorkbenchProject> projects) {
    for (final project in projects) {
      if (project.templateId != workbenchTodoTemplateId) return project;
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

  Future<void> _activateProject(WorkbenchProject project) async {
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
  }

  Future<void> _deactivateProject() async {
    final success = await _service.deactivateProject();
    if (!mounted) return;
    if (!success) {
      showToast(
        context.l10n.workbenchProjectDeactivateFailed,
        type: ToastType.error,
      );
      return;
    }
    showToast(
      context.l10n.workbenchProjectDeactivated,
      type: ToastType.success,
    );
  }

  Future<void> _toggleProjectActivation(WorkbenchProject project) {
    return _isActiveProject(project)
        ? _deactivateProject()
        : _activateProject(project);
  }

  Future<void> _editProjectLabels(WorkbenchProject project) async {
    final l10n = context.l10n;
    final nameController = TextEditingController(
      text: _projectDisplayName(project),
    );
    final shortNameController = TextEditingController(
      text: project.primaryDisplay.shortName,
    );
    final values = await showDialog<Map<String, String>>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: Text(l10n.workbenchEditProjectLabels),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nameController,
                autofocus: true,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: l10n.workbenchProjectNameLabel,
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: shortNameController,
                textInputAction: TextInputAction.done,
                textCapitalization: TextCapitalization.characters,
                maxLength: 8,
                decoration: InputDecoration(
                  labelText: l10n.workbenchProjectShortNameLabel,
                  counterText: '',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(l10n.workbenchDeleteProjectCancel),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop({
                'name': nameController.text.trim(),
                'shortName': shortNameController.text.trim(),
              }),
              child: Text(l10n.workbenchSaveProjectLabels),
            ),
          ],
        );
      },
    );
    nameController.dispose();
    shortNameController.dispose();
    if (!mounted) return;
    if (values == null) return;
    final name = values['name']?.trim() ?? '';
    final shortName = values['shortName']?.trim() ?? '';
    if (name.isEmpty) {
      showToast(l10n.workbenchProjectNameRequired, type: ToastType.error);
      return;
    }
    final updated = await _service.updateProjectMetadata(
      project,
      name: name,
      shortName: shortName,
    );
    if (!mounted) return;
    if (updated == null) {
      showToast(l10n.workbenchProjectLabelsUpdateFailed, type: ToastType.error);
      return;
    }
    setState(() {
      _selectedProjectId = updated.projectId;
      if (_detailProjectId == project.projectId) {
        _detailProjectId = updated.projectId;
      }
    });
    showToast(l10n.workbenchProjectLabelsUpdated, type: ToastType.success);
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
    final fallbackRoute = _fallbackDisplayRoute(project);
    final resolvedRoute =
        rawRoute.isEmpty ||
            (rawRoute.startsWith('/workbench/todo_log') &&
                project.templateId != workbenchTodoTemplateId)
        ? fallbackRoute
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

  String _fallbackDisplayRoute(WorkbenchProject project) {
    final encodedProjectId = Uri.encodeQueryComponent(project.projectId);
    if (project.templateId == workbenchQuickCaptureTemplateId ||
        project.tools.any(
          (tool) => tool.id.startsWith(WorkbenchQuickCaptureToolIds.ingest),
        )) {
      return '/workbench/quick_capture?projectId=$encodedProjectId';
    }
    if (project.templateId == 'schema_app') {
      return '/workbench/schema_app?projectId=$encodedProjectId';
    }
    return '/workbench/quick_capture?projectId=$encodedProjectId';
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
      _detailProjectId = null;
      _lastExportResult = null;
    });
    showToast(context.l10n.workbenchProjectDeleted, type: ToastType.success);
  }

  void _handleBackNavigation() {
    if (_detailProjectId != null) {
      setState(() => _detailProjectId = null);
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final detailProject = _detailProject;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: detailProject == null
              ? context.l10n.workbenchProjectModeTitle
              : context.l10n.workbenchProjectDetailTitle,
          primary: true,
          onBackPressed: _handleBackNavigation,
          actions: [
            if (detailProject == null)
              IconButton(
                tooltip: context.l10n.workbenchProjectModeCreateButton,
                onPressed: _returnHomeForProjectPrompt,
                icon: const Icon(Icons.chat_bubble_outline_rounded),
              ),
          ],
        ),
        body: SafeArea(
          child: AnimatedBuilder(
            animation: _service,
            builder: (context, _) {
              final detailProject = _detailProject;
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
                    if (detailProject == null) ...[
                      _buildGreetingBadge(),
                      const SizedBox(height: 12),
                      _buildActiveProjectSummary(),
                      const SizedBox(height: 12),
                      if (_service.projects.isEmpty)
                        _buildEmptyProjectPanel()
                      else
                        _buildProjectList(),
                      if (_service.errorMessage != null) ...[
                        const SizedBox(height: 12),
                        _buildInlineStatus(
                          icon: Icons.error_outline_rounded,
                          label: context.l10n.workbenchProjectModeLoadFailed,
                          color: const Color(0xFFDC2626),
                        ),
                      ],
                    ] else
                      _buildProjectPanel(detailProject),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildGreetingBadge() {
    final palette = context.omniPalette;
    return InkWell(
      borderRadius: BorderRadius.circular(24),
      onTap: () => _showPhilosophySheet(),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
        decoration: BoxDecoration(
          color: palette.accentPrimary.withAlpha(18),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: palette.accentPrimary.withAlpha(50)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.auto_awesome_rounded, size: 15, color: palette.accentPrimary),
            const SizedBox(width: 6),
            Text(
              context.l10n.workbenchPhilosophyBadge,
              style: TextStyle(
                color: palette.accentPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(width: 4),
            Icon(Icons.chevron_right_rounded, size: 15, color: palette.accentPrimary),
          ],
        ),
      ),
    );
  }

  void _showPhilosophySheet() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _WorkbenchPhilosophySheet(palette: context.omniPalette),
    );
  }

  Widget _buildActiveProjectSummary() {
    final palette = context.omniPalette;
    final activeProject = _service.activeProject == null
        ? null
        : _findProject(_service.activeProject!.projectId) ??
              _service.activeProject;
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchProjectActiveTitle,
            icon: Icons.check_circle_outline_rounded,
            trailing: activeProject == null
                ? null
                : IconButton(
                    tooltip: context.l10n.workbenchDeactivateProject,
                    onPressed: _service.loading ? null : _deactivateProject,
                    icon: const Icon(Icons.link_off_rounded),
                  ),
          ),
          const SizedBox(height: 10),
          if (activeProject == null)
            _buildInlineStatus(
              icon: Icons.info_outline_rounded,
              label: context.l10n.workbenchProjectActiveEmpty,
              color: palette.textSecondary,
            )
          else ...[
            Text(
              _projectDisplayName(activeProject),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 18,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _projectOneLineSummary(activeProject),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildProjectList() {
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchProjectListTitle,
            icon: Icons.dashboard_customize_outlined,
            trailing: _buildCountBadge(_service.projects.length.toString()),
          ),
          const SizedBox(height: 12),
          Column(
            children: _service.projects
                .map(
                  (project) => Padding(
                    padding: EdgeInsets.only(
                      bottom: project == _service.projects.last ? 0 : 10,
                    ),
                    child: _buildProjectListRow(project),
                  ),
                )
                .toList(growable: false),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectListRow(WorkbenchProject project) {
    final palette = context.omniPalette;
    final active = _isActiveProject(project);
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () {
        setState(() {
          _selectedProjectId = project.projectId;
          _detailProjectId = project.projectId;
          _lastExportResult = null;
        });
      },
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: active ? palette.surfaceSecondary : palette.surfacePrimary,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: active ? palette.accentPrimary : palette.borderSubtle,
          ),
        ),
        child: Row(
          children: [
            Icon(
              active
                  ? Icons.check_circle_outline_rounded
                  : Icons.dashboard_customize_outlined,
              color: active ? palette.accentPrimary : palette.textSecondary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _projectDisplayName(project),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 7),
                  Text(
                    _projectOneLineSummary(project),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      height: 1.35,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            IconButton(
              tooltip: active
                  ? context.l10n.workbenchDeactivateProject
                  : context.l10n.workbenchActivateProject,
              onPressed: _service.loading
                  ? null
                  : () => _toggleProjectActivation(project),
              icon: Icon(
                active
                    ? Icons.check_circle_rounded
                    : Icons.radio_button_unchecked_rounded,
              ),
              color: active ? palette.accentPrimary : palette.textTertiary,
            ),
            const SizedBox(width: 2),
            Icon(Icons.chevron_right_rounded, color: palette.textTertiary),
          ],
        ),
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
              IconButton(
                tooltip: context.l10n.workbenchEditProjectLabels,
                onPressed: _service.loading
                    ? null
                    : () => _editProjectLabels(project),
                icon: const Icon(Icons.edit_outlined),
              ),
              const SizedBox(width: 4),
              IconButton.filledTonal(
                tooltip: active
                    ? context.l10n.workbenchDeactivateProject
                    : context.l10n.workbenchActivateProject,
                onPressed: _service.loading
                    ? null
                    : () => _toggleProjectActivation(project),
                icon: Icon(
                  active
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                ),
              ),
              const SizedBox(width: 4),
              _buildProjectMenu(project),
            ],
          ),
          const SizedBox(height: 14),
          _buildDisplayList(project),
          const SizedBox(height: 14),
          Divider(color: palette.borderSubtle, height: 1),
          const SizedBox(height: 14),
          _buildSectionHeader(
            title: context.l10n.workbenchProjectApiForProject,
            icon: Icons.api_rounded,
            trailing: _buildCountBadge(
              context.l10n.workbenchApiCount(project.tools.length),
            ),
          ),
          const SizedBox(height: 12),
          _buildApiList(project),
          const SizedBox(height: 14),
          _buildAndroidAssets(project),
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
    final visibleDescription =
        description.isNotEmpty && !_looksLikeControlCopy(description);
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
                if (visibleDescription) ...[
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
    final visibleDescription =
        description != null &&
        description.isNotEmpty &&
        !_looksLikeControlCopy(description);
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
                if (visibleDescription) ...[
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
                if (tool.executionCount > 0) ...[
                  const SizedBox(height: 6),
                  _buildCountBadge(
                    context.l10n.workbenchToolExecutionCount(
                      tool.executionCount,
                    ),
                  ),
                ],
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
                if (asset.packageName?.trim().isNotEmpty == true) ...[
                  const SizedBox(height: 5),
                  Text(
                    asset.packageName!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectMenu(WorkbenchProject project) {
    final palette = context.omniPalette;
    return PopupMenuButton<_ProjectMenuAction>(
      tooltip: context.l10n.workbenchProjectMoreActions,
      enabled: !_service.loading,
      icon: const Icon(Icons.more_vert_rounded),
      onSelected: (action) {
        switch (action) {
          case _ProjectMenuAction.workspace:
            _openWorkspace(project);
          case _ProjectMenuAction.export:
            _exportProject(project);
          case _ProjectMenuAction.delete:
            _confirmDeleteProject(project);
        }
      },
      itemBuilder: (context) => [
        PopupMenuItem(
          value: _ProjectMenuAction.workspace,
          child: _buildProjectMenuItem(
            Icons.folder_open_rounded,
            context.l10n.workbenchOpenWorkspace,
            palette.textPrimary,
          ),
        ),
        PopupMenuItem(
          value: _ProjectMenuAction.export,
          child: _buildProjectMenuItem(
            Icons.inventory_2_outlined,
            context.l10n.workbenchExportProjectPackage,
            palette.textPrimary,
          ),
        ),
        PopupMenuItem(
          value: _ProjectMenuAction.delete,
          child: _buildProjectMenuItem(
            Icons.delete_outline_rounded,
            context.l10n.workbenchDeleteProject,
            const Color(0xFFDC2626),
          ),
        ),
      ],
    );
  }

  Widget _buildProjectMenuItem(IconData icon, String label, Color color) {
    return Row(
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(width: 10),
        Text(label, style: TextStyle(color: color)),
      ],
    );
  }

  Widget _buildEmptyProjectPanel() {
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildInlineStatus(
            icon: Icons.info_outline_rounded,
            label: context.l10n.workbenchProjectModeEmpty,
            color: context.omniPalette.textSecondary,
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _returnHomeForProjectPrompt,
            icon: const Icon(Icons.chat_bubble_outline_rounded),
            label: Text(context.l10n.workbenchProjectModeCreateButton),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectIdentity(WorkbenchProject project) {
    final palette = context.omniPalette;
    final shortName = project.primaryDisplay.shortName.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                _projectDisplayName(project),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            if (shortName.isNotEmpty) ...[
              const SizedBox(width: 8),
              _buildCountBadge(shortName),
            ],
          ],
        ),
        const SizedBox(height: 6),
        Text(
          _projectOneLineSummary(project),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 12,
            height: 1.35,
            fontWeight: FontWeight.w600,
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
    final id = toolId.toLowerCase();
    if (id == WorkbenchTodoToolIds.addTodo ||
        id.endsWith('.create') ||
        id.endsWith('.add')) {
      return Icons.add_box_outlined;
    }
    if (id == WorkbenchTodoToolIds.finishTodo ||
        id.endsWith('.archive') ||
        id.endsWith('.finish') ||
        id.endsWith('.complete')) {
      return Icons.archive_outlined;
    }
    return Icons.api_rounded;
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

  String _projectOneLineSummary(WorkbenchProject project) {
    final description = project.schema['description']?.toString().trim();
    if (description != null &&
        description.isNotEmpty &&
        !_looksLikeControlCopy(description)) {
      return description;
    }
    if (project.templateId == workbenchQuickCaptureTemplateId) {
      return context.l10n.workbenchProjectSummaryQuickCapture;
    }
    if (project.templateId == workbenchTodoTemplateId) {
      return context.l10n.workbenchProjectSummaryTodo;
    }
    final entityName =
        project.schema['entityName']?.toString().trim().isNotEmpty == true
        ? project.schema['entityName'].toString().trim()
        : context.l10n.workbenchSchemaDefaultEntity;
    return context.l10n.workbenchProjectSummarySchema(entityName);
  }

  bool _looksLikeControlCopy(String value) {
    final lower = value.toLowerCase();
    return lower.contains('project') ||
        lower.contains('api') ||
        lower.contains('toolbox') ||
        lower.contains('workspace') ||
        lower.contains('backend') ||
        lower.contains('frontend') ||
        lower.contains('executor') ||
        lower.contains('generated') ||
        lower.contains('oob native');
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

class _WorkbenchPhilosophySheet extends StatelessWidget {
  const _WorkbenchPhilosophySheet({required this.palette});

  final OmniThemePalette palette;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return DraggableScrollableSheet(
      initialChildSize: 0.88,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      builder: (_, controller) => Container(
        decoration: BoxDecoration(
          color: palette.pageBackground,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: palette.borderSubtle,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Expanded(
              child: ListView(
                controller: controller,
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
                children: [
                  _buildHeader(context, l10n),
                  const SizedBox(height: 20),
                  _buildPillars(context, l10n),
                  const SizedBox(height: 16),
                  _buildStrengths(context, l10n),
                  const SizedBox(height: 16),
                  _buildHowTo(context, l10n),
                  const SizedBox(height: 16),
                  _buildActivateHint(context, l10n),
                  const SizedBox(height: 8),
                  Center(
                    child: TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: Text(l10n.workbenchPhilosophyClose),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(BuildContext context, AppLocalizations l10n) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(Icons.auto_awesome_rounded, color: palette.accentPrimary, size: 22),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                l10n.workbenchPhilosophyTitle,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 22,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          l10n.workbenchPhilosophyTagline,
          style: TextStyle(
            color: palette.accentPrimary,
            fontSize: 16,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 10),
        Text(
          l10n.workbenchPhilosophySubtitle,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 13,
            height: 1.55,
          ),
        ),
      ],
    );
  }

  Widget _buildPillars(BuildContext context, AppLocalizations l10n) {
    return _buildSection(
      context,
      title: l10n.workbenchPhilosophyPillarsTitle,
      icon: Icons.layers_rounded,
      child: Column(
        children: [
          _buildPillarRow(
            context,
            icon: Icons.hub_rounded,
            label: l10n.workbenchPhilosophyComposable,
            desc: l10n.workbenchPhilosophyComposableDesc,
          ),
          const SizedBox(height: 10),
          _buildPillarRow(
            context,
            icon: Icons.psychology_rounded,
            label: l10n.workbenchPhilosophyAIDriven,
            desc: l10n.workbenchPhilosophyAIDrivenDesc,
          ),
          const SizedBox(height: 10),
          _buildPillarRow(
            context,
            icon: Icons.phone_android_rounded,
            label: l10n.workbenchPhilosophyMobileNative,
            desc: l10n.workbenchPhilosophyMobileNativeDesc,
          ),
        ],
      ),
    );
  }

  Widget _buildPillarRow(
    BuildContext context, {
    required IconData icon,
    required String label,
    required String desc,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: palette.accentPrimary.withAlpha(20),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Icon(icon, size: 18, color: palette.accentPrimary),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                desc,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 12,
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildStrengths(BuildContext context, AppLocalizations l10n) {
    final items = [
      (
        icon: Icons.memory_rounded,
        title: l10n.workbenchPhilosophyBackendTitle,
        desc: l10n.workbenchPhilosophyBackendDesc,
        badge: 'OOB + Omniflow',
      ),
      (
        icon: Icons.phone_android_rounded,
        title: l10n.workbenchPhilosophyFrontendTitle,
        desc: l10n.workbenchPhilosophyFrontendDesc,
        badge: 'Flutter · HTML',
      ),
      (
        icon: Icons.inventory_2_outlined,
        title: l10n.workbenchPhilosophyRuntimeTitle,
        desc: l10n.workbenchPhilosophyRuntimeDesc,
        badge: 'Project',
      ),
    ];
    return _buildSection(
      context,
      title: l10n.workbenchPhilosophyStrengthsTitle,
      icon: Icons.bolt_rounded,
      child: Column(
        children: items.asMap().entries.map((entry) {
          final item = entry.value;
          final isLast = entry.key == items.length - 1;
          return Column(
            children: [
              _buildStrengthRow(
                context,
                icon: item.icon,
                title: item.title,
                desc: item.desc,
                badge: item.badge,
              ),
              if (!isLast) const SizedBox(height: 10),
            ],
          );
        }).toList(),
      ),
    );
  }

  Widget _buildStrengthRow(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String desc,
    required String badge,
  }) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
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
                      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                      decoration: BoxDecoration(
                        color: palette.surfaceSecondary,
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: palette.borderSubtle),
                      ),
                      child: Text(
                        badge,
                        style: TextStyle(
                          color: palette.textTertiary,
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  desc,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHowTo(BuildContext context, AppLocalizations l10n) {
    final steps = [
      (
        label: l10n.workbenchPhilosophyStep1Label,
        desc: l10n.workbenchPhilosophyStep1Desc,
        icon: Icons.mic_none_rounded,
      ),
      (
        label: l10n.workbenchPhilosophyStep2Label,
        desc: l10n.workbenchPhilosophyStep2Desc,
        icon: Icons.visibility_outlined,
      ),
      (
        label: l10n.workbenchPhilosophyStep3Label,
        desc: l10n.workbenchPhilosophyStep3Desc,
        icon: Icons.edit_note_rounded,
      ),
    ];
    return _buildSection(
      context,
      title: l10n.workbenchPhilosophyHowToTitle,
      icon: Icons.play_circle_outline_rounded,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: steps.asMap().entries.map((entry) {
          final step = entry.value;
          final isLast = entry.key == steps.length - 1;
          return Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: _buildStepCard(context, label: step.label, desc: step.desc, icon: step.icon)),
                if (!isLast) ...[
                  const SizedBox(width: 4),
                  Padding(
                    padding: const EdgeInsets.only(top: 22),
                    child: Icon(Icons.arrow_forward_rounded, size: 14, color: palette.textTertiary),
                  ),
                  const SizedBox(width: 4),
                ],
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildStepCard(
    BuildContext context, {
    required String label,
    required String desc,
    required IconData icon,
  }) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        children: [
          Icon(icon, size: 20, color: palette.accentPrimary),
          const SizedBox(height: 4),
          Text(
            label,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 15,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            desc,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 11,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActivateHint(BuildContext context, AppLocalizations l10n) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withAlpha(14),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.accentPrimary.withAlpha(40)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.lightbulb_outline_rounded, size: 16, color: palette.accentPrimary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              l10n.workbenchPhilosophyActivateHint,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12,
                height: 1.5,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSection(
    BuildContext context, {
    required String title,
    required IconData icon,
    required Widget child,
  }) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 16, color: palette.accentPrimary),
              const SizedBox(width: 6),
              Text(
                title,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}
