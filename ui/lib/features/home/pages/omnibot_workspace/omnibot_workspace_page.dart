import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/common_app_bar.dart';

enum _OmnibotWorkspaceMode { work, project }

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
        : _OmnibotWorkspaceMode.work;
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

  void _setProjectModeEnabled(bool enabled) {
    setState(() {
      _mode = enabled
          ? _OmnibotWorkspaceMode.project
          : _OmnibotWorkspaceMode.work;
      if (enabled) {
        _browserCanGoUp = false;
      }
    });
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
                        title: context.l10n.workbenchWorkspaceTitle,
                        primary: false,
                        backgroundColor: backgroundSurfaceColor(
                          translucent: backgroundActive,
                          baseColor: palette.surfacePrimary,
                          opacity: 0.68,
                        ),
                        onBackPressed: _handleBackPressed,
                      ),
                      OmnibotWorkspaceModeToggle(
                        projectModeEnabled:
                            _mode == _OmnibotWorkspaceMode.project,
                        translucentSurfaces: backgroundActive,
                        onChanged: _setProjectModeEnabled,
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

class OmnibotWorkspaceModeToggle extends StatelessWidget {
  const OmnibotWorkspaceModeToggle({
    super.key,
    required this.projectModeEnabled,
    required this.onChanged,
    this.translucentSurfaces = false,
  });

  final bool projectModeEnabled;
  final ValueChanged<bool> onChanged;
  final bool translucentSurfaces;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = backgroundSurfaceColor(
      translucent: translucentSurfaces,
      baseColor: palette.surfacePrimary,
      opacity: 0.68,
    );
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
      color: background,
      child: Row(
        children: [
          Expanded(
            child: _OmnibotWorkspaceModeButton(
              selected: !projectModeEnabled,
              label: context.l10n.workbenchWorkspaceWorkMode,
              icon: Icons.folder_open_rounded,
              onTap: () => onChanged(false),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: _OmnibotWorkspaceModeButton(
              selected: projectModeEnabled,
              label: context.l10n.workbenchWorkspaceProjectMode,
              icon: Icons.phone_android_rounded,
              onTap: () => onChanged(true),
            ),
          ),
        ],
      ),
    );
  }
}

class _OmnibotWorkspaceModeButton extends StatelessWidget {
  const _OmnibotWorkspaceModeButton({
    required this.selected,
    required this.label,
    required this.icon,
    required this.onTap,
  });

  final bool selected;
  final String label;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = selected
        ? palette.accentPrimary
        : palette.surfaceSecondary;
    final foreground = selected
        ? Theme.of(context).colorScheme.onPrimary
        : palette.textSecondary;
    return Material(
      color: background,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: selected ? null : onTap,
        child: Container(
          height: 40,
          alignment: Alignment.center,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 18, color: foreground),
              const SizedBox(width: 7),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: foreground,
                    fontSize: 13,
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
    unawaited(_service.refresh());
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

  Future<void> _openDisplay(
    WorkbenchProject project,
    WorkbenchDisplaySpec display,
  ) async {
    final active = await _service.activateProject(project);
    if (!mounted) return;
    if (active == null) {
      showToast(
        context.l10n.workbenchWorkspaceProjectOpenFailed,
        type: ToastType.error,
      );
      return;
    }
    GoRouterManager.push(_displayRoute(project, display));
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

  String _projectDisplayName(WorkbenchProject project) {
    final name = project.name.trim();
    return name.isEmpty ? project.projectId : name;
  }

  @override
  Widget build(BuildContext context) {
    final projects = _service.projects;
    return RefreshIndicator(
      onRefresh: _service.refresh,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          if (_service.loading) ...[
            const LinearProgressIndicator(minHeight: 2),
            const SizedBox(height: 12),
          ],
          _ProjectModeHeader(translucent: widget.translucentSurfaces),
          const SizedBox(height: 12),
          if (projects.isEmpty)
            _WorkspaceProjectStatusCard(
              icon: Icons.phone_android_outlined,
              label: context.l10n.workbenchWorkspaceProjectFrontendsEmpty,
              translucent: widget.translucentSurfaces,
            )
          else
            ...projects.map(
              (project) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _WorkspaceProjectCard(
                  project: project,
                  active:
                      _service.activeProject?.projectId == project.projectId,
                  projectName: _projectDisplayName(project),
                  translucent: widget.translucentSurfaces,
                  onOpenDisplay: (display) => _openDisplay(project, display),
                ),
              ),
            ),
          if (_service.errorMessage != null) ...[
            const SizedBox(height: 2),
            _WorkspaceProjectStatusCard(
              icon: Icons.error_outline_rounded,
              label: context.l10n.workbenchProjectModeLoadFailed,
              color: const Color(0xFFDC2626),
              translucent: widget.translucentSurfaces,
            ),
          ],
          SizedBox(height: MediaQuery.of(context).padding.bottom),
        ],
      ),
    );
  }
}

class _ProjectModeHeader extends StatelessWidget {
  const _ProjectModeHeader({required this.translucent});

  final bool translucent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
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
          Icon(Icons.phone_android_rounded, color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  context.l10n.workbenchWorkspaceProjectFrontendsTitle,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  context.l10n.workbenchWorkspaceProjectFrontendsSubtitle,
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
}

class _WorkspaceProjectCard extends StatelessWidget {
  const _WorkspaceProjectCard({
    required this.project,
    required this.active,
    required this.projectName,
    required this.translucent,
    required this.onOpenDisplay,
  });

  final WorkbenchProject project;
  final bool active;
  final String projectName;
  final bool translucent;
  final ValueChanged<WorkbenchDisplaySpec> onOpenDisplay;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final displays = project.displays.isEmpty
        ? [project.primaryDisplay]
        : project.displays;
    final executionCount = project.tools.fold<int>(
      0,
      (sum, tool) => sum + tool.executionCount,
    );
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: translucent,
          baseColor: palette.surfacePrimary,
          opacity: 0.76,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: active ? palette.accentPrimary : palette.borderSubtle,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  projectName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              if (active)
                _WorkspaceProjectBadge(
                  label: context.l10n.workbenchActiveProject,
                  color: palette.accentPrimary,
                ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            context.l10n.workbenchWorkspaceProjectApiStats(
              project.tools.length,
              executionCount,
            ),
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 12),
          Column(
            children: displays
                .map(
                  (display) => Padding(
                    padding: EdgeInsets.only(
                      bottom: display == displays.last ? 0 : 8,
                    ),
                    child: _WorkspaceProjectDisplayRow(
                      display: display,
                      onTap: () => onOpenDisplay(display),
                    ),
                  ),
                )
                .toList(growable: false),
          ),
        ],
      ),
    );
  }
}

class _WorkspaceProjectDisplayRow extends StatelessWidget {
  const _WorkspaceProjectDisplayRow({
    required this.display,
    required this.onTap,
  });

  final WorkbenchDisplaySpec display;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final label = display.label.trim().isEmpty
        ? context.l10n.workbenchUnnamedDisplay
        : display.label;
    return Material(
      color: palette.surfaceSecondary,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minHeight: 52),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              Icon(Icons.open_in_new_rounded, color: palette.accentPrimary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Icon(Icons.chevron_right_rounded, color: palette.textTertiary),
            ],
          ),
        ),
      ),
    );
  }
}

class _WorkspaceProjectBadge extends StatelessWidget {
  const _WorkspaceProjectBadge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.26)),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w800,
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
