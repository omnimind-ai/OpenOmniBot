import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/pages/workbench_schema_project_page.dart';
import 'package:ui/features/workbench/pages/workbench_todo_log_page.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/common_app_bar.dart';

enum _OmnibotWorkspaceMode { work, project }

const String _workspaceCachedModeKey = 'omnibot_workspace_cached_mode_v1';
const String _workspaceCachedDirectoryKey =
    'omnibot_workspace_cached_directory_v1';

class OmnibotWorkspacePage extends StatefulWidget {
  final String workspacePath;
  final String? workspaceId;
  final String? workspaceShellPath;
  final bool startInProjectMode;

  const OmnibotWorkspacePage({
    super.key,
    required this.workspacePath,
    this.workspaceId,
    this.workspaceShellPath,
    this.startInProjectMode = false,
  });

  @override
  State<OmnibotWorkspacePage> createState() => _OmnibotWorkspacePageState();
}

class _OmnibotWorkspacePageState extends State<OmnibotWorkspacePage> {
  final GlobalKey<OmnibotWorkspaceBrowserState> _browserKey =
      GlobalKey<OmnibotWorkspaceBrowserState>();
  bool _browserCanGoUp = false;
  late _OmnibotWorkspaceMode _mode;

  @override
  void initState() {
    super.initState();
    _mode = widget.startInProjectMode
        ? _OmnibotWorkspaceMode.project
        : _cachedWorkspaceMode();
    _persistWorkspaceMode(_mode);
  }

  _OmnibotWorkspaceMode _cachedWorkspaceMode() {
    final cached = StorageService.getString(_workspaceCachedModeKey);
    return cached == _OmnibotWorkspaceMode.project.name
        ? _OmnibotWorkspaceMode.project
        : _OmnibotWorkspaceMode.work;
  }

  void _persistWorkspaceMode(_OmnibotWorkspaceMode mode) {
    unawaited(StorageService.setString(_workspaceCachedModeKey, mode.name));
  }

  String? _cachedWorkspaceDirectory(String rootPath) {
    final cached = StorageService.getString(
      _workspaceCachedDirectoryKey,
    )?.trim();
    if (cached == null || cached.isEmpty) return null;
    final normalizedRoot = _normalizeWorkspacePath(rootPath);
    final normalizedCached = _normalizeWorkspacePath(cached);
    final insideRoot =
        normalizedCached == normalizedRoot ||
        normalizedCached.startsWith('$normalizedRoot/');
    if (!insideRoot) return null;
    return Directory(normalizedCached).existsSync() ? normalizedCached : null;
  }

  void _persistWorkspaceDirectory(String path) {
    final normalized = _normalizeWorkspacePath(path);
    if (normalized.isEmpty) return;
    unawaited(
      StorageService.setString(_workspaceCachedDirectoryKey, normalized),
    );
  }

  String _normalizeWorkspacePath(String path) {
    final trimmed = path.trim();
    if (trimmed.length > 1 && trimmed.endsWith('/')) {
      return trimmed.substring(0, trimmed.length - 1);
    }
    return trimmed;
  }

  void _handleBackPressed() {
    final browserState = _browserKey.currentState;
    if (_mode == _OmnibotWorkspaceMode.work &&
        browserState != null &&
        browserState.canGoUp) {
      browserState.openParentDirectory();
    } else {
      GoRouterManager.pop();
    }
  }

  void _showWorkbenchGuide(bool backgroundActive) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return OmnibotWorkbenchGuideSheet(translucent: backgroundActive);
      },
    );
  }

  void _openWorkbenchConsole() {
    GoRouterManager.push('/workbench/projects');
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<AppBackgroundConfig>(
      valueListenable: AppBackgroundService.notifier,
      builder: (context, backgroundConfig, _) {
        final palette = context.omniPalette;
        final backgroundActive = backgroundConfig.isActive;
        return PopScope(
          canPop: !_browserCanGoUp,
          onPopInvokedWithResult: (didPop, _) {
            if (didPop) return;
            _handleBackPressed();
          },
          child: Scaffold(
            backgroundColor: Colors.transparent,
            body: Stack(
              fit: StackFit.expand,
              children: [
                Positioned.fill(
                  child: AppBackgroundLayer(
                    config: backgroundConfig,
                    fallbackColor: palette.previewFallback,
                    layerKey: const ValueKey('workspace-page-background'),
                  ),
                ),
                SafeArea(
                  child: Column(
                    children: [
                      CommonAppBar(
                        titleWidget: Text(
                          _mode == _OmnibotWorkspaceMode.project
                              ? context.l10n.workbenchWorkspaceProjectMode
                              : context.l10n.workbenchWorkspaceTitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                            fontFamily: 'SF Pro',
                          ),
                        ),
                        primary: false,
                        backgroundColor: backgroundSurfaceColor(
                          translucent: backgroundActive,
                          baseColor: palette.surfacePrimary,
                          opacity: 0.68,
                        ),
                        onBackPressed: _handleBackPressed,
                        actions: [
                          IconButton(
                            tooltip: context
                                .l10n
                                .workbenchWorkspaceOpenProjectConsole,
                            onPressed: _openWorkbenchConsole,
                            icon: Icon(
                              Icons.tune_rounded,
                              color: palette.textSecondary,
                            ),
                          ),
                          IconButton(
                            tooltip:
                                context.l10n.workbenchWorkspaceGuideTooltip,
                            onPressed: () =>
                                _showWorkbenchGuide(backgroundActive),
                            icon: Icon(
                              Icons.info_outline_rounded,
                              color: palette.textSecondary,
                            ),
                          ),
                        ],
                      ),
                      Expanded(
                        child: AnimatedSwitcher(
                          duration: const Duration(milliseconds: 180),
                          switchInCurve: Curves.easeOutCubic,
                          switchOutCurve: Curves.easeOutCubic,
                          child: _mode == _OmnibotWorkspaceMode.project
                              ? OmnibotWorkspaceProjectFrontends(
                                  key: const ValueKey('workspace-project-mode'),
                                  translucentSurfaces: backgroundActive,
                                )
                              : OmnibotWorkspaceBrowser(
                                  key: _browserKey,
                                  workspacePath: widget.workspacePath,
                                  workspaceShellPath: widget.workspaceShellPath,
                                  initialDirectoryPath:
                                      _cachedWorkspaceDirectory(
                                        widget.workspacePath,
                                      ),
                                  onCurrentDirectoryChanged:
                                      _persistWorkspaceDirectory,
                                  enableSystemBackHandler: false,
                                  translucentSurfaces: backgroundActive,
                                  showBreadcrumbHeader: true,
                                  showHeaderTitle: false,
                                  onCanGoUpChanged: (canGoUp) {
                                    if (_browserCanGoUp == canGoUp ||
                                        !mounted) {
                                      return;
                                    }
                                    setState(() {
                                      _browserCanGoUp = canGoUp;
                                    });
                                  },
                                ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class OmnibotWorkbenchGuideSheet extends StatelessWidget {
  const OmnibotWorkbenchGuideSheet({super.key, required this.translucent});

  final bool translucent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final maxHeight = MediaQuery.of(context).size.height * 0.84;
    return SafeArea(
      top: false,
      child: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
        ),
        child: Align(
          alignment: Alignment.bottomCenter,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: maxHeight),
            child: Container(
              decoration: BoxDecoration(
                color: backgroundSurfaceColor(
                  translucent: translucent,
                  baseColor: palette.surfacePrimary,
                  opacity: 0.96,
                ),
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(12),
                ),
                border: Border.all(color: palette.borderSubtle),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const SizedBox(height: 8),
                  Container(
                    width: 38,
                    height: 4,
                    decoration: BoxDecoration(
                      color: palette.borderStrong,
                      borderRadius: BorderRadius.circular(999),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(18, 14, 10, 8),
                    child: Row(
                      children: [
                        Icon(
                          Icons.dashboard_customize_outlined,
                          color: palette.accentPrimary,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            context.l10n.workbenchWorkspaceGuideTitle,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 17,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: context.l10n.workbenchWorkspaceGuideClose,
                          onPressed: () => Navigator.of(context).pop(),
                          icon: Icon(
                            Icons.close_rounded,
                            color: palette.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Flexible(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.fromLTRB(18, 0, 18, 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            context.l10n.workbenchWorkspaceGuideIntro,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 13,
                              height: 1.35,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 14),
                          _WorkbenchGuideFlow(translucent: translucent),
                          const SizedBox(height: 10),
                          _WorkbenchGuideSection(
                            icon: Icons.widgets_outlined,
                            title: context
                                .l10n
                                .workbenchWorkspaceGuideProjectTitle,
                            body:
                                context.l10n.workbenchWorkspaceGuideProjectBody,
                            translucent: translucent,
                          ),
                          _WorkbenchGuideSection(
                            icon: Icons.phone_android_rounded,
                            title: context
                                .l10n
                                .workbenchWorkspaceGuideFrontendTitle,
                            body: context
                                .l10n
                                .workbenchWorkspaceGuideFrontendBody,
                            translucent: translucent,
                          ),
                          _WorkbenchGuideSection(
                            icon: Icons.api_rounded,
                            title: context
                                .l10n
                                .workbenchWorkspaceGuideBackendTitle,
                            body:
                                context.l10n.workbenchWorkspaceGuideBackendBody,
                            translucent: translucent,
                          ),
                          _WorkbenchGuideSection(
                            icon: Icons.storage_rounded,
                            title:
                                context.l10n.workbenchWorkspaceGuideDataTitle,
                            body: context.l10n.workbenchWorkspaceGuideDataBody,
                            translucent: translucent,
                          ),
                          _WorkbenchGuideSection(
                            icon: Icons.auto_fix_high_rounded,
                            title:
                                context.l10n.workbenchWorkspaceGuideVibeTitle,
                            body: context.l10n.workbenchWorkspaceGuideVibeBody,
                            translucent: translucent,
                          ),
                          _WorkbenchGuideSection(
                            icon: Icons.extension_rounded,
                            title:
                                context.l10n.workbenchWorkspaceGuideExtendTitle,
                            body:
                                context.l10n.workbenchWorkspaceGuideExtendBody,
                            translucent: translucent,
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _WorkbenchGuideFlow extends StatelessWidget {
  const _WorkbenchGuideFlow({required this.translucent});

  final bool translucent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final steps = [
      context.l10n.workbenchWorkspaceGuideFlowPrompt,
      context.l10n.workbenchWorkspaceGuideFlowProject,
      context.l10n.workbenchWorkspaceGuideFlowApi,
      context.l10n.workbenchWorkspaceGuideFlowDisplay,
      context.l10n.workbenchWorkspaceGuideFlowPersist,
    ];
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: translucent,
          baseColor: palette.surfaceSecondary,
          opacity: 0.72,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.workbenchWorkspaceGuideFlowTitle,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 10),
          for (var index = 0; index < steps.length; index++)
            Padding(
              padding: EdgeInsets.only(
                bottom: index == steps.length - 1 ? 0 : 8,
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 24,
                    height: 24,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: palette.accentPrimary.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: palette.accentPrimary.withValues(alpha: 0.28),
                      ),
                    ),
                    child: Text(
                      '${index + 1}',
                      style: TextStyle(
                        color: palette.accentPrimary,
                        fontSize: 12,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      steps[index],
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 13,
                        height: 1.3,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _WorkbenchGuideSection extends StatelessWidget {
  const _WorkbenchGuideSection({
    required this.icon,
    required this.title,
    required this.body,
    required this.translucent,
  });

  final IconData icon;
  final String title;
  final String body;
  final bool translucent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: translucent,
          baseColor: palette.surfaceSecondary,
          opacity: 0.66,
        ),
        borderRadius: BorderRadius.circular(8),
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
                Text(
                  title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  body,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 13,
                    height: 1.35,
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
}

class OmnibotWorkspaceModeHeader extends StatelessWidget {
  const OmnibotWorkspaceModeHeader({
    super.key,
    required this.projectModeEnabled,
    required this.translucent,
    required this.onChanged,
    this.onOpenWorkbenchConsole,
    this.onShowGuide,
  });

  final bool projectModeEnabled;
  final bool translucent;
  final ValueChanged<bool> onChanged;
  final VoidCallback? onOpenWorkbenchConsole;
  final VoidCallback? onShowGuide;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = backgroundSurfaceColor(
      translucent: translucent,
      baseColor: palette.surfacePrimary,
      opacity: 0.74,
    );
    return Container(
      height: 46,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: background,
        border: Border(bottom: BorderSide(color: palette.borderSubtle)),
      ),
      child: Row(
        children: [
          Text(
            context.l10n.workbenchWorkspaceTitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 15,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(width: 8),
          OmnibotWorkspaceModeButton(
            projectModeEnabled: projectModeEnabled,
            onTap: () => onChanged(!projectModeEnabled),
          ),
          const Spacer(),
          IconButton(
            tooltip: context.l10n.workbenchWorkspaceOpenProjectConsole,
            onPressed: onOpenWorkbenchConsole,
            icon: Icon(Icons.tune_rounded, color: palette.textSecondary),
          ),
          IconButton(
            tooltip: context.l10n.workbenchWorkspaceGuideTooltip,
            onPressed: onShowGuide,
            icon: Icon(
              Icons.info_outline_rounded,
              color: palette.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class OmnibotWorkspaceModeButton extends StatelessWidget {
  const OmnibotWorkspaceModeButton({
    super.key,
    required this.projectModeEnabled,
    required this.onTap,
  });

  final bool projectModeEnabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final label = projectModeEnabled
        ? context.l10n.workbenchWorkspaceProjectMode
        : context.l10n.workbenchWorkspaceWorkMode;
    final icon = projectModeEnabled
        ? Icons.phone_android_rounded
        : Icons.folder_open_rounded;
    return Material(
      color: palette.accentPrimary.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Container(
          height: 28,
          constraints: const BoxConstraints(minWidth: 76),
          padding: const EdgeInsets.symmetric(horizontal: 10),
          alignment: Alignment.center,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 14, color: palette.accentPrimary),
              const SizedBox(width: 5),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.accentPrimary,
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class OmnibotWorkspaceProjectFrontends extends StatefulWidget {
  const OmnibotWorkspaceProjectFrontends({
    super.key,
    this.translucentSurfaces = false,
    WorkbenchProjectModeService? service,
  }) : _service = service;

  final bool translucentSurfaces;
  final WorkbenchProjectModeService? _service;

  @override
  State<OmnibotWorkspaceProjectFrontends> createState() =>
      _OmnibotWorkspaceProjectFrontendsState();
}

class _OmnibotWorkspaceProjectFrontendsState
    extends State<OmnibotWorkspaceProjectFrontends> {
  late final WorkbenchProjectModeService _service =
      widget._service ?? WorkbenchProjectModeService.native();
  late final bool _ownsService = widget._service == null;

  @override
  void initState() {
    super.initState();
    _service.addListener(_handleServiceChanged);
    unawaited(_refreshAndEnsureActive());
  }

  @override
  void dispose() {
    _service.removeListener(_handleServiceChanged);
    if (_ownsService) {
      _service.dispose();
    }
    super.dispose();
  }

  void _handleServiceChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _refreshAndEnsureActive() async {
    await _service.refresh();
    if (!mounted ||
        _service.activeProject != null ||
        _service.projects.isEmpty) {
      return;
    }
    await _service.activateProject(_service.projects.first);
  }

  Future<void> _activateProject(WorkbenchProject project) async {
    final active = await _service.activateProject(project);
    if (!mounted) return;
    if (active == null) {
      showToast(
        context.l10n.workbenchWorkspaceProjectOpenFailed,
        type: ToastType.error,
      );
      return;
    }
  }

  void _showWorkbenchGuide() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return OmnibotWorkbenchGuideSheet(
          translucent: widget.translucentSurfaces,
        );
      },
    );
  }

  String _displayRoute(WorkbenchProject project, WorkbenchDisplaySpec display) {
    final rawRoute = display.route.trim().isEmpty
        ? project.route.trim()
        : display.route.trim();
    final resolvedRoute = rawRoute.isEmpty
        ? '/workbench/todo_log?projectId=${Uri.encodeQueryComponent(project.projectId)}'
        : rawRoute;
    final uri = Uri.parse(resolvedRoute);
    return uri
        .replace(
          queryParameters: {
            ...uri.queryParameters,
            'projectId': project.projectId,
            'displayId': display.id,
          },
        )
        .toString();
  }

  void _openDisplayRoute(
    WorkbenchProject project,
    WorkbenchDisplaySpec display,
  ) {
    GoRouterManager.push(_displayRoute(project, display));
  }

  WorkbenchProject? _currentProject(List<WorkbenchProject> projects) {
    final activeProjectId = _service.activeProject?.projectId;
    if (activeProjectId != null) {
      for (final project in projects) {
        if (project.projectId == activeProjectId) {
          return project;
        }
      }
    }
    return projects.isEmpty ? null : projects.first;
  }

  String _projectDisplayName(WorkbenchProject project) {
    final name = project.name.trim();
    return name.isEmpty ? project.projectId : name;
  }

  @override
  Widget build(BuildContext context) {
    final projects = _service.projects;
    final project = _currentProject(projects);
    final display = project?.primaryDisplay;
    return Column(
      children: [
        if (_service.loading) const LinearProgressIndicator(minHeight: 2),
        _WorkspaceProjectMiniBar(
          project: project,
          display: display,
          projects: projects,
          projectName: project == null ? null : _projectDisplayName(project),
          translucent: widget.translucentSurfaces,
          onRefresh: _refreshAndEnsureActive,
          onOpenDisplay: project == null || display == null
              ? null
              : () => _openDisplayRoute(project, display),
          onShowGuide: _showWorkbenchGuide,
          onProjectSelected: (selectedProject) =>
              unawaited(_activateProject(selectedProject)),
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  border: Border.all(color: context.omniPalette.borderSubtle),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: project == null || display == null
                    ? _WorkspaceProjectStatusCard(
                        icon: Icons.phone_android_outlined,
                        label: context
                            .l10n
                            .workbenchWorkspaceProjectFrontendsEmpty,
                        translucent: widget.translucentSurfaces,
                      )
                    : _WorkspaceProjectDisplayHost(
                        key: ValueKey(
                          'workspace-project-host-${project.projectId}-${display.id}',
                        ),
                        project: project,
                        display: display,
                      ),
              ),
            ),
          ),
        ),
        if (_service.errorMessage != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: _WorkspaceProjectStatusCard(
              icon: Icons.error_outline_rounded,
              label: context.l10n.workbenchProjectModeLoadFailed,
              color: const Color(0xFFDC2626),
              translucent: widget.translucentSurfaces,
            ),
          ),
      ],
    );
  }
}

class _WorkspaceProjectMiniBar extends StatelessWidget {
  const _WorkspaceProjectMiniBar({
    required this.project,
    required this.display,
    required this.projects,
    required this.projectName,
    required this.translucent,
    required this.onRefresh,
    required this.onOpenDisplay,
    required this.onShowGuide,
    required this.onProjectSelected,
  });

  final WorkbenchProject? project;
  final WorkbenchDisplaySpec? display;
  final List<WorkbenchProject> projects;
  final String? projectName;
  final bool translucent;
  final Future<void> Function() onRefresh;
  final VoidCallback? onOpenDisplay;
  final VoidCallback onShowGuide;
  final ValueChanged<WorkbenchProject> onProjectSelected;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final activeProject = project;
    final activeDisplay = display;
    final displayLabel = activeDisplay == null
        ? context.l10n.workbenchWorkspaceProjectFrontendsTitle
        : activeDisplay.label.trim().isEmpty
        ? context.l10n.workbenchUnnamedDisplay
        : activeDisplay.label;
    final background = backgroundSurfaceColor(
      translucent: translucent,
      baseColor: palette.surfacePrimary,
      opacity: 0.74,
    );
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(
        color: background,
        border: Border(bottom: BorderSide(color: palette.borderSubtle)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  displayLabel,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (projectName != null)
                  Text(
                    projectName!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
              ],
            ),
          ),
          PopupMenuButton<String>(
            tooltip: context.l10n.workbenchProjectSwitcher,
            enabled: projects.isNotEmpty,
            icon: Icon(
              Icons.unfold_more_rounded,
              color: projects.isEmpty
                  ? palette.textTertiary
                  : palette.textSecondary,
            ),
            onSelected: (projectId) {
              for (final item in projects) {
                if (item.projectId == projectId) {
                  onProjectSelected(item);
                  return;
                }
              }
            },
            itemBuilder: (context) {
              return projects
                  .map((item) {
                    final name = item.name.trim().isEmpty
                        ? item.projectId
                        : item.name.trim();
                    return PopupMenuItem<String>(
                      value: item.projectId,
                      child: Row(
                        children: [
                          Icon(
                            item.projectId == activeProject?.projectId
                                ? Icons.check_rounded
                                : Icons.widgets_outlined,
                            size: 18,
                            color: item.projectId == activeProject?.projectId
                                ? palette.accentPrimary
                                : palette.textTertiary,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    );
                  })
                  .toList(growable: false);
            },
          ),
          IconButton(
            tooltip: context.l10n.workbenchWorkspaceGuideTooltip,
            onPressed: onShowGuide,
            icon: Icon(
              Icons.info_outline_rounded,
              color: palette.textSecondary,
            ),
          ),
          IconButton(
            tooltip: context.l10n.workbenchOpenDisplay,
            onPressed: onOpenDisplay,
            icon: Icon(Icons.open_in_new_rounded, color: palette.textSecondary),
          ),
          IconButton(
            tooltip: context.l10n.omniflowRefresh,
            onPressed: () => unawaited(onRefresh()),
            icon: Icon(Icons.refresh_rounded, color: palette.textSecondary),
          ),
        ],
      ),
    );
  }
}

class _WorkspaceProjectDisplayHost extends StatelessWidget {
  const _WorkspaceProjectDisplayHost({
    super.key,
    required this.project,
    required this.display,
  });

  final WorkbenchProject project;
  final WorkbenchDisplaySpec display;

  @override
  Widget build(BuildContext context) {
    final route = display.route.trim().isEmpty
        ? project.route.trim()
        : display.route.trim();
    final hostsTodoLog =
        project.templateId == workbenchTodoTemplateId ||
        route.startsWith('/workbench/todo_log');
    if (hostsTodoLog) {
      return WorkbenchTodoLogPage(
        projectId: project.projectId,
        displayId: display.id,
        embedded: true,
      );
    }
    final hostsSchemaDisplay =
        project.templateId == 'schema_app' ||
        route.startsWith('/workbench/schema_app');
    if (hostsSchemaDisplay) {
      return WorkbenchSchemaProjectPage(
        projectId: project.projectId,
        displayId: display.id,
        embedded: true,
      );
    }
    return _WorkspaceProjectUnsupportedDisplay(display: display);
  }
}

class _WorkspaceProjectUnsupportedDisplay extends StatelessWidget {
  const _WorkspaceProjectUnsupportedDisplay({required this.display});

  final WorkbenchDisplaySpec display;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final label = display.label.trim().isEmpty
        ? context.l10n.workbenchUnnamedDisplay
        : display.label;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.widgets_outlined, color: palette.textTertiary, size: 34),
            const SizedBox(height: 10),
            Text(
              label,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 15,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              context.l10n.workbenchWorkspaceProjectUnsupportedDisplay,
              textAlign: TextAlign.center,
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
    );
  }
}

class _WorkspaceProjectStatusCard extends StatelessWidget {
  const _WorkspaceProjectStatusCard({
    required this.icon,
    required this.label,
    required this.translucent,
    this.color,
  });

  final IconData icon;
  final String label;
  final bool translucent;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final foreground = color ?? palette.textSecondary;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: translucent,
          baseColor: palette.surfacePrimary,
          opacity: 0.76,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        children: [
          Icon(icon, color: foreground, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                color: foreground,
                fontSize: 13,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
