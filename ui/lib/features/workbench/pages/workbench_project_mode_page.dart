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

enum _WorkbenchProjectViewMode { use, debug }

class _WorkbenchProjectModePageState extends State<WorkbenchProjectModePage> {
  late final WorkbenchProjectModeService _service;
  final TextEditingController _promptController = TextEditingController();
  final TextEditingController _androidSourceController =
      TextEditingController();
  String? _selectedProjectId;
  WorkbenchProjectExportResult? _lastExportResult;
  bool _promptSeeded = false;
  _WorkbenchProjectViewMode _viewMode = _WorkbenchProjectViewMode.use;

  @override
  void initState() {
    super.initState();
    _service = widget._service ?? WorkbenchProjectModeService.native();
    _refreshProjects();
  }

  @override
  void dispose() {
    _promptController.dispose();
    _androidSourceController.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_promptSeeded) {
      _promptController.text = context.l10n.workbenchProjectDefaultPrompt;
      _promptSeeded = true;
    }
  }

  Future<void> _refreshProjects({String? selectProjectId}) async {
    await _service.refresh();
    if (!mounted) {
      return;
    }
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
    selected ??= _findProject(workbenchTodoDefaultProjectId);
    selected ??= projects.first;
    if (_selectedProjectId != selected.projectId) {
      setState(() => _selectedProjectId = selected!.projectId);
    }
  }

  Future<void> _createDefaultProject() async {
    final project = await _service.createTodoLogProject(
      workbenchTodoDefaultProjectId,
    );
    if (!mounted) {
      return;
    }
    if (project == null) {
      showToast(
        context.l10n.workbenchProjectModeLoadFailed,
        type: ToastType.error,
      );
      return;
    }
    setState(() => _selectedProjectId = project.projectId);
    showToast(context.l10n.workbenchProjectCreated, type: ToastType.success);
  }

  Future<void> _generateProjectFromPrompt() async {
    final project = await _service.createTodoLogProjectFromPrompt(
      _promptController.text,
      name: context.l10n.workbenchGeneratedTodoProjectName,
      initialTodos: [
        context.l10n.workbenchPromptSeedAddTodo,
        context.l10n.workbenchPromptSeedArchiveTodo,
      ],
    );
    if (!mounted) {
      return;
    }
    if (project == null) {
      showToast(
        context.l10n.workbenchProjectPromptRequired,
        type: ToastType.error,
      );
      return;
    }
    setState(() => _selectedProjectId = project.projectId);
    showToast(context.l10n.workbenchProjectGenerated, type: ToastType.success);
    _openDisplay(project, project.primaryDisplay);
  }

  void _openDisplay(
    WorkbenchProject project,
    WorkbenchDisplaySpec display, {
    bool debugReturn = false,
  }) {
    final route = _displayRoute(project, display, debugReturn: debugReturn);
    if (debugReturn) {
      context.push(route);
      return;
    }
    context.go(route);
  }

  Future<void> _runToolFromList(
    WorkbenchProject project,
    WorkbenchToolSpec tool,
  ) async {
    if (tool.id == WorkbenchTodoToolIds.addTodo) {
      final result = await _service.runTool(project, tool.id, {
        'title': context.l10n.workbenchToolListDefaultTodo,
      });
      if (!mounted) {
        return;
      }
      showToast(
        result.success
            ? context.l10n.workbenchTodoAdded
            : context.l10n.workbenchUnknownTool,
        type: result.success ? ToastType.success : ToastType.error,
      );
      return;
    }
    final openTodos = project.openTodos;
    if (openTodos.isEmpty) {
      showToast(context.l10n.workbenchNoOpenTodo, type: ToastType.error);
      return;
    }
    final result = await _service.runTool(project, tool.id, {
      'todo_id': openTodos.first.id,
    });
    if (!mounted) {
      return;
    }
    showToast(
      result.success
          ? context.l10n.workbenchTodoFinishedToast
          : context.l10n.workbenchUnknownTool,
      type: result.success ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _openWorkspace(WorkbenchProject project) async {
    final paths = await OmnibotResourceService.ensureWorkspacePathsLoaded();
    if (!mounted) {
      return;
    }
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
    if (!mounted) {
      return;
    }
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

  Future<void> _ingestAndroidAsset(WorkbenchProject project) async {
    final sourcePath = _androidSourceController.text.trim();
    if (sourcePath.isEmpty) {
      showToast(
        context.l10n.workbenchAndroidSourceRequired,
        type: ToastType.error,
      );
      return;
    }
    final result = await _service.ingestAndroidAsset(project, sourcePath);
    if (!mounted) {
      return;
    }
    if (result == null || !result.success) {
      showToast(
        context.l10n.workbenchAndroidIngestFailed,
        type: ToastType.error,
      );
      return;
    }
    _androidSourceController.clear();
    showToast(
      context.l10n.workbenchAndroidIngested(
        result.asset?.displayName ?? project.projectId,
      ),
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
    if (confirmed != true) {
      return;
    }
    final result = await _service.deleteProject(project);
    if (!mounted) {
      return;
    }
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

  void _handleBackNavigation() {
    context.go(GoRouterManager.homeRoute);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) {
          return;
        }
        _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: context.l10n.workbenchProjectModeTitle,
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        floatingActionButton: AnimatedBuilder(
          animation: _service,
          builder: (context, _) {
            return FloatingActionButton.extended(
              onPressed: () {
                final project = _currentProject;
                if (project == null) {
                  showToast(
                    context.l10n.workbenchAssistantNoProject,
                    type: ToastType.error,
                  );
                  return;
                }
                _openAssistantSheet(project);
              },
              tooltip: context.l10n.workbenchAssistantTooltip,
              icon: const Icon(Icons.auto_awesome_rounded),
              label: Text(context.l10n.workbenchAssistantName),
            );
          },
        ),
        body: SafeArea(
          child: AnimatedBuilder(
            animation: _service,
            builder: (context, _) {
              final project = _currentProject;
              return ListView(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                children: [
                  if (_service.loading) ...[
                    const LinearProgressIndicator(minHeight: 2),
                    const SizedBox(height: 12),
                  ],
                  _buildGenerationPanel(),
                  const SizedBox(height: 12),
                  _buildModeSwitch(),
                  if (_service.projects.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _buildProjectSwitcher(project),
                  ],
                  const SizedBox(height: 12),
                  if (project == null)
                    _buildEmptyProjectPanel()
                  else ...[
                    if (_viewMode == _WorkbenchProjectViewMode.use)
                      _buildUsePanel(project)
                    else
                      _buildDebugPanel(project),
                  ],
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  /// Opens the Xiaowan hot-update sheet for the selected Project.
  ///
  /// `project` is the currently selected Workbench Project. The submitted
  /// prompt is handled by the Workbench control API, then the Project list is
  /// refreshed from the returned persistent state.
  Future<void> _openAssistantSheet(WorkbenchProject project) async {
    final controller = TextEditingController();
    var applying = false;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) {
        return StatefulBuilder(
          builder: (sheetContext, setSheetState) {
            final palette = sheetContext.omniPalette;
            Future<void> submit() async {
              final prompt = controller.text.trim();
              if (prompt.isEmpty) {
                showToast(
                  sheetContext.l10n.workbenchAssistantPromptRequired,
                  type: ToastType.error,
                );
                return;
              }
              setSheetState(() => applying = true);
              try {
                final result = await _service.applyHotUpdate(project, prompt);
                if (!mounted) {
                  return;
                }
                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop();
                }
                showToast(
                  result?.success == true
                      ? context.l10n.workbenchAssistantApplied
                      : context.l10n.workbenchAssistantHotUpdateFailed,
                  type: result?.success == true
                      ? ToastType.success
                      : ToastType.error,
                );
              } catch (_) {
                if (!mounted) {
                  return;
                }
                if (sheetContext.mounted) {
                  setSheetState(() => applying = false);
                }
                showToast(
                  context.l10n.workbenchAssistantHotUpdateFailed,
                  type: ToastType.error,
                );
              }
            }

            return SafeArea(
              top: false,
              child: Padding(
                padding: EdgeInsets.only(
                  bottom: MediaQuery.of(sheetContext).viewInsets.bottom,
                ),
                child: Container(
                  padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
                  decoration: BoxDecoration(
                    color: palette.surfacePrimary,
                    borderRadius: const BorderRadius.vertical(
                      top: Radius.circular(16),
                    ),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.auto_awesome_rounded,
                            color: palette.accentPrimary,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              sheetContext.l10n.workbenchAssistantName,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 18,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                          IconButton(
                            tooltip: MaterialLocalizations.of(
                              sheetContext,
                            ).closeButtonTooltip,
                            onPressed: applying
                                ? null
                                : () => Navigator.of(sheetContext).pop(),
                            icon: const Icon(Icons.close_rounded),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      _buildTinyCode(project.projectId),
                      const SizedBox(height: 12),
                      TextField(
                        controller: controller,
                        autofocus: true,
                        enabled: !applying,
                        minLines: 3,
                        maxLines: 5,
                        textInputAction: TextInputAction.newline,
                        decoration: InputDecoration(
                          hintText:
                              sheetContext.l10n.workbenchAssistantPromptHint,
                          filled: true,
                          fillColor: palette.surfaceSecondary,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(8),
                            borderSide: BorderSide.none,
                          ),
                          contentPadding: const EdgeInsets.all(12),
                        ),
                      ),
                      const SizedBox(height: 12),
                      SizedBox(
                        width: double.infinity,
                        child: FilledButton.icon(
                          onPressed: applying ? null : submit,
                          icon: applying
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.bolt_rounded),
                          label: Text(sheetContext.l10n.workbenchAssistantSend),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        );
      },
    );
    controller.dispose();
  }

  WorkbenchProject? get _currentProject {
    if (_selectedProjectId != null) {
      final selected = _findProject(_selectedProjectId!);
      if (selected != null) {
        return selected;
      }
    }
    return _findProject(workbenchTodoDefaultProjectId) ??
        (_service.projects.isEmpty ? null : _service.projects.first);
  }

  WorkbenchProject? _findProject(String projectId) {
    for (final project in _service.projects) {
      if (project.projectId == projectId) {
        return project;
      }
    }
    return null;
  }

  String _projectDisplayName(WorkbenchProject project) {
    final name = project.name.trim();
    return name.isEmpty ? project.projectId : name;
  }

  Widget _buildGenerationPanel() {
    final palette = context.omniPalette;
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchProjectGenerateTitle,
            icon: Icons.chat_bubble_outline_rounded,
          ),
          const SizedBox(height: 10),
          Text(
            context.l10n.workbenchProjectGenerateSubtitle,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 13,
              height: 1.35,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _promptController,
            minLines: 2,
            maxLines: 4,
            decoration: InputDecoration(
              hintText: context.l10n.workbenchProjectPromptHint,
              filled: true,
              fillColor: palette.surfaceSecondary,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: BorderSide.none,
              ),
              contentPadding: const EdgeInsets.all(12),
            ),
          ),
          const SizedBox(height: 12),
          _buildGenerationBlueprint(),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _service.loading ? null : _generateProjectFromPrompt,
              icon: const Icon(Icons.playlist_add_check_rounded),
              label: Text(context.l10n.workbenchProjectGenerateButton),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildModeSwitch() {
    final palette = context.omniPalette;
    return SegmentedButton<_WorkbenchProjectViewMode>(
      segments: [
        ButtonSegment(
          value: _WorkbenchProjectViewMode.use,
          icon: const Icon(Icons.play_circle_outline_rounded),
          label: Text(context.l10n.workbenchUseMode),
        ),
        ButtonSegment(
          value: _WorkbenchProjectViewMode.debug,
          icon: const Icon(Icons.bug_report_outlined),
          label: Text(context.l10n.workbenchDebugMode),
        ),
      ],
      selected: {_viewMode},
      showSelectedIcon: false,
      style: SegmentedButton.styleFrom(
        visualDensity: VisualDensity.compact,
        side: BorderSide(color: palette.borderSubtle),
      ),
      onSelectionChanged: (selection) {
        setState(() => _viewMode = selection.first);
      },
    );
  }

  String _displayRoute(
    WorkbenchProject project,
    WorkbenchDisplaySpec display, {
    required bool debugReturn,
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
    if (debugReturn) {
      params['returnTo'] = '/workbench/projects';
    } else {
      params.remove('returnTo');
    }
    return uri.replace(queryParameters: params).toString();
  }

  Widget _buildGenerationBlueprint() {
    final steps = [
      (
        icon: Icons.inventory_2_outlined,
        title: context.l10n.workbenchProjectPlanProject,
        value: 'workbench_project_create',
      ),
      (
        icon: Icons.phone_android_rounded,
        title: context.l10n.workbenchProjectPlanFrontend,
        value: '/workbench/todo_log',
      ),
      (
        icon: Icons.api_rounded,
        title: context.l10n.workbenchProjectPlanApi,
        value: 'todo.add / todo.finish',
      ),
      (
        icon: Icons.storage_rounded,
        title: context.l10n.workbenchProjectPlanData,
        value: 'data/todos.json',
      ),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          context.l10n.workbenchProjectPlanTitle,
          style: TextStyle(
            color: context.omniPalette.textPrimary,
            fontSize: 13,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 8),
        Column(
          children: steps
              .map(
                (step) => Padding(
                  padding: EdgeInsets.only(bottom: step == steps.last ? 0 : 8),
                  child: _buildBlueprintRow(
                    icon: step.icon,
                    title: step.title,
                    value: step.value,
                  ),
                ),
              )
              .toList(growable: false),
        ),
      ],
    );
  }

  Widget _buildBlueprintRow({
    required IconData icon,
    required String title,
    required String value,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          Icon(icon, color: palette.accentPrimary, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Flexible(child: _buildTinyCode(value)),
        ],
      ),
    );
  }

  Widget _buildUsePanel(WorkbenchProject project) {
    final palette = context.omniPalette;
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 42,
                height: 42,
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
                      context.l10n.workbenchProjectCurrentTitle,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      context.l10n.workbenchProjectCurrentSubtitle,
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 13,
                        height: 1.35,
                      ),
                    ),
                  ],
                ),
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
            ],
          ),
          const SizedBox(height: 14),
          _buildDisplayList(project, debugReturn: false),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: _service.loading ? null : _createDefaultProject,
                icon: const Icon(Icons.playlist_add_rounded),
                label: Text(context.l10n.workbenchProjectModeCreateButton),
              ),
              OutlinedButton.icon(
                onPressed: () => _openWorkspace(project),
                icon: const Icon(Icons.folder_open_rounded),
                label: Text(context.l10n.workbenchOpenWorkspace),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildDisplayList(
    WorkbenchProject project, {
    required bool debugReturn,
  }) {
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
                  child: _buildDisplayRow(
                    project,
                    display,
                    debugReturn: debugReturn,
                  ),
                ),
              )
              .toList(growable: false),
        ),
      ],
    );
  }

  Widget _buildDisplayRow(
    WorkbenchProject project,
    WorkbenchDisplaySpec display, {
    required bool debugReturn,
  }) {
    final palette = context.omniPalette;
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
            onPressed: () =>
                _openDisplay(project, display, debugReturn: debugReturn),
            icon: const Icon(Icons.open_in_new_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildDebugPanel(WorkbenchProject project) {
    final palette = context.omniPalette;
    return _buildSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchDebugMode,
            icon: Icons.bug_report_outlined,
          ),
          const SizedBox(height: 12),
          _buildProjectIdentity(project),
          const SizedBox(height: 12),
          _buildDisplayList(project, debugReturn: true),
          const SizedBox(height: 14),
          _buildDebugToolPanel(project),
          const SizedBox(height: 14),
          _buildProjectInfo(project),
          const SizedBox(height: 14),
          _buildAndroidAssetPanel(project),
          const SizedBox(height: 14),
          Divider(color: context.omniPalette.borderSubtle, height: 1),
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
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: () => _openWorkspace(project),
                icon: const Icon(Icons.folder_open_rounded),
                label: Text(context.l10n.workbenchOpenWorkspace),
              ),
              OutlinedButton.icon(
                onPressed: _service.loading
                    ? null
                    : () => _exportProject(project),
                icon: const Icon(Icons.inventory_2_outlined),
                label: Text(context.l10n.workbenchExportProjectPackage),
              ),
              TextButton.icon(
                onPressed: _service.loading
                    ? null
                    : () => _confirmDeleteProject(project),
                icon: const Icon(Icons.delete_outline_rounded),
                label: Text(context.l10n.workbenchDeleteProject),
                style: TextButton.styleFrom(
                  foregroundColor: const Color(0xFFDC2626),
                ),
              ),
            ],
          ),
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

  Widget _buildDebugToolPanel(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchDebugToolsTitle,
            icon: Icons.tune_rounded,
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _buildInlineStatus(
                  icon: Icons.auto_awesome_rounded,
                  label: context.l10n.workbenchDebugHotUpdate,
                  color: palette.textSecondary,
                ),
              ),
              TextButton(
                onPressed: () => _openAssistantSheet(project),
                child: Text(context.l10n.workbenchAssistantName),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Expanded(
                child: _buildInlineStatus(
                  icon: Icons.visibility_outlined,
                  label: context.l10n.workbenchDebugVlmTest,
                  color: palette.textSecondary,
                ),
              ),
              OutlinedButton(
                onPressed: null,
                child: Text(context.l10n.workbenchDebugComingSoon),
              ),
            ],
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
                  final name = project.name.trim().isEmpty
                      ? project.projectId
                      : project.name;
                  return ChoiceChip(
                    selected: selected,
                    label: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 220),
                      child: Text(
                        name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    onSelected: (_) {
                      setState(() => _selectedProjectId = project.projectId);
                    },
                  );
                })
                .toList(growable: false),
          ),
        ],
      ),
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
          project.name.trim().isEmpty
              ? context.l10n.workbenchTemplateProjectName
              : project.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          project.projectId,
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

  Widget _buildProjectInfo(WorkbenchProject project) {
    return Column(
      children: [
        _buildInfoRow(
          icon: Icons.display_settings_rounded,
          title: context.l10n.workbenchProjectInfoDisplayTitle,
          value: project.route.trim().isEmpty
              ? '/workbench/todo_log?projectId=${project.projectId}'
              : project.route,
        ),
        const SizedBox(height: 8),
        _buildInfoRow(
          icon: Icons.code_rounded,
          title: context.l10n.workbenchProjectInfoSourceTitle,
          value: context.l10n.workbenchProjectInfoSourceValue,
        ),
        const SizedBox(height: 8),
        _buildInfoRow(
          icon: Icons.storage_rounded,
          title: context.l10n.workbenchProjectInfoRuntimeTitle,
          value: context.l10n.workbenchProjectInfoRuntimeValue,
        ),
      ],
    );
  }

  Widget _buildAndroidAssetPanel(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionHeader(
            title: context.l10n.workbenchAndroidAssetsTitle,
            icon: Icons.android_rounded,
            trailing: _buildCountBadge(project.androidAssets.length.toString()),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _androidSourceController,
            enabled: !_service.loading,
            minLines: 1,
            maxLines: 2,
            decoration: InputDecoration(
              hintText: context.l10n.workbenchAndroidSourceHint,
              filled: true,
              fillColor: palette.surfacePrimary,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: BorderSide.none,
              ),
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 10,
              ),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _service.loading
                  ? null
                  : () => _ingestAndroidAsset(project),
              icon: const Icon(Icons.file_upload_outlined),
              label: Text(context.l10n.workbenchAndroidIngestButton),
            ),
          ),
          if (project.androidAssets.isEmpty) ...[
            const SizedBox(height: 10),
            _buildInlineStatus(
              icon: Icons.info_outline_rounded,
              label: context.l10n.workbenchAndroidAssetsEmpty,
              color: palette.textSecondary,
            ),
          ] else ...[
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
        ],
      ),
    );
  }

  Widget _buildAndroidAssetRow(WorkbenchAndroidAsset asset) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
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

  Widget _buildInfoRow({
    required IconData icon,
    required String title,
    required String value,
  }) {
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
          Icon(icon, color: palette.accentPrimary, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  value,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    height: 1.3,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
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
              child: _buildToolRow(project, tool),
            ),
          )
          .toList(growable: false),
    );
  }

  Widget _buildToolRow(WorkbenchProject project, WorkbenchToolSpec tool) {
    final palette = context.omniPalette;
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
                  _toolTitle(tool.id),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
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
          const SizedBox(width: 8),
          IconButton.filledTonal(
            tooltip: context.l10n.workbenchCallApi,
            onPressed: _service.loading
                ? null
                : () => _runToolFromList(project, tool),
            icon: const Icon(Icons.play_arrow_rounded),
          ),
        ],
      ),
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

  IconData _toolIcon(String toolId) {
    return toolId == WorkbenchTodoToolIds.addTodo
        ? Icons.add_task_rounded
        : Icons.task_alt_rounded;
  }

  String _toolTitle(String toolId) {
    return toolId == WorkbenchTodoToolIds.addTodo
        ? context.l10n.workbenchToolAddTodoTitle
        : context.l10n.workbenchToolFinishTodoTitle;
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
