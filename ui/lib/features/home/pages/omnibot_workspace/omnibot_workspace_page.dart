import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/pages/workbench_flutter_eval_page.dart';
import 'package:ui/features/workbench/pages/workbench_html_display_page.dart';
import 'package:ui/features/workbench/pages/workbench_project_display_page.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_context.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/assists_core_service.dart';
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
  final TextEditingController _editPromptController = TextEditingController();
  final FocusNode _editPromptFocusNode = FocusNode();
  final List<WorkbenchAnnotationStroke> _editStrokes = [];
  final List<Offset> _editCurrentPoints = [];
  StreamSubscription<Map<String, dynamic>>? _projectUpdateSub;
  bool _editPromptVisible = false;
  bool _editDrawingEnabled = true;
  bool _submittingEdit = false;
  Size _editCanvasSize = Size.zero;
  int _nextEditStrokeId = 0;

  static const Color _editStrokeColor = Color(0xFFE13D56);
  static const double _editStrokeWidth = 4;

  @override
  void initState() {
    super.initState();
    _service.addListener(_handleServiceChanged);
    _projectUpdateSub = AssistsMessageService.workbenchProjectUpdatedStream
        .listen(_handleWorkbenchProjectUpdated);
    unawaited(_refreshAndEnsureActive());
  }

  @override
  void dispose() {
    _projectUpdateSub?.cancel();
    _service.removeListener(_handleServiceChanged);
    _editPromptController.dispose();
    _editPromptFocusNode.dispose();
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

  void _handleWorkbenchProjectUpdated(Map<String, dynamic> event) {
    if (!mounted) return;
    final reason = event['reason']?.toString() ?? '';
    final isActiveProjectChange =
        reason == 'project_activated' ||
        reason == 'project_deactivated' ||
        reason == 'project_deleted';
    if (isActiveProjectChange) {
      _closeEditPrompt();
    }
    unawaited(_service.refresh());
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

  String _displayRoute(WorkbenchProject project, WorkbenchDisplaySpec display) {
    final rawRoute = display.route.trim().isEmpty
        ? project.route.trim()
        : display.route.trim();
    final resolvedRoute = rawRoute.isEmpty
        ? '/workbench/project?projectId=${Uri.encodeQueryComponent(project.projectId)}'
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

  void _openProjectManager(WorkbenchProject? project) {
    GoRouterManager.push(
      '/workbench/projects',
      queryParams: project == null ? null : {'projectId': project.projectId},
    );
  }

  void _toggleEditPrompt(WorkbenchProject? project) {
    if (project == null) {
      showToast(
        context.l10n.workbenchAssistantNoProject,
        type: ToastType.warning,
      );
      return;
    }
    setState(() {
      _editPromptVisible = !_editPromptVisible;
      if (_editPromptVisible) {
        _editDrawingEnabled = true;
      } else {
        _clearEditDrawing(clearPrompt: false);
      }
    });
    if (_editPromptVisible) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _editPromptFocusNode.requestFocus();
      });
    } else {
      _editPromptFocusNode.unfocus();
    }
  }

  List<String> _sourceFileKeys(Map<String, Object?> frontendPayload) {
    final sources = frontendPayload['sources'];
    if (sources is! Map) return const [];
    return sources.keys.map((key) => key.toString()).toList(growable: false);
  }

  String _routeForDisplay(
    WorkbenchProject project,
    WorkbenchDisplaySpec display,
  ) {
    return workbenchRouteForDisplay(
      project,
      display,
      fallbackRoute: '/workbench/project',
    );
  }

  Map<String, Object?> _buildToolbarEditFrontendContext(
    WorkbenchProject project,
    WorkbenchDisplaySpec display,
    WorkbenchAnnotationPayload? annotationPayload,
  ) {
    final renderer = display.renderer.trim().isEmpty
        ? display.kind.trim()
        : display.renderer.trim();
    final html = project.frontendHtml;
    final flutter = project.frontendFlutter;
    final baseContext = buildWorkbenchVisibleFrontendContext(
      project: project,
      display: display,
      source: 'workspace_project_toolbar_edit',
      extraVisibleState: {
        'renderer': renderer,
        'hasHtml': html.isNotEmpty,
        'hasFlutter': flutter.isNotEmpty,
        'htmlEntryFile': html['entryFile']?.toString(),
        'htmlEntryPath': html['entryPath']?.toString(),
        'htmlSourceFiles': _sourceFileKeys(html),
        'flutterEntryFile': flutter['entryFile']?.toString(),
        'flutterEntryClass': flutter['entryClass']?.toString(),
        'flutterSourceFiles': _sourceFileKeys(flutter),
      },
    );
    if (annotationPayload == null) return baseContext;
    final visibleState = baseContext['visibleState'] is Map
        ? Map<String, Object?>.from(baseContext['visibleState'] as Map)
        : <String, Object?>{};
    final annotationContext = annotationPayload.toFrontendContext(
      projectId: project.projectId,
      displayId: display.id,
      route: _routeForDisplay(project, display),
      source: 'workspace_project_toolbar_drawing_edit',
      visibleState: visibleState,
    );
    return {
      ...baseContext,
      ...annotationContext,
      'toolbarEditContext': baseContext,
      'screenshotSummary':
          'Use the visible Workbench Display plus these red annotation paths to infer the marked target UI. The Flutter client does not classify the shape.',
    };
  }

  String _buildToolbarEditAgentContinuationMessage(
    String prompt,
    Map<String, Object?> frontendContext,
    WorkbenchProjectHotUpdateResult hotUpdateResult,
  ) {
    const encoder = JsonEncoder.withIndent('  ');
    final contextJson = encoder.convert(frontendContext);
    final instructionsJson = encoder.convert(hotUpdateResult.instructions);
    return '''
$prompt

当前 Workbench 右侧显示区已经先调用 workbench_project_hot_update 并记录了这次编辑请求。不要再次调用 workbench_project_hot_update，不要开启新的产品生成流程；请继续读取当前 Project，并调用 workbench_project_update 只改必要的 frontend/html 或 frontend/flutter 文件。不要把 Project ID、工具数量、executor、workspace、data/log 路径等控制面信息写到可见界面。

hot_update 返回信息：

```json
{
  "projectId": "${hotUpdateResult.projectId}",
  "requiresAgentRegeneration": ${hotUpdateResult.requiresAgentRegeneration},
  "recommendedTool": "${hotUpdateResult.recommendedTool}",
  "instructions": $instructionsJson
}
```

当前前端上下文：

```json
$contextJson
```
''';
  }

  Future<void> _submitProjectEdit(
    WorkbenchProject? project,
    WorkbenchDisplaySpec? display,
  ) async {
    if (_submittingEdit) return;
    final rawPrompt = _editPromptController.text.trim();
    final hasDrawing = _editStrokes.isNotEmpty;
    final prompt = rawPrompt.isEmpty && hasDrawing
        ? context.l10n.workbenchAnnotationDefaultPrompt
        : rawPrompt;
    if (prompt.isEmpty) {
      showToast(
        context.l10n.workbenchAssistantPromptRequired,
        type: ToastType.warning,
      );
      return;
    }
    if (project == null || display == null) {
      showToast(
        context.l10n.workbenchAssistantNoProject,
        type: ToastType.warning,
      );
      return;
    }
    setState(() => _submittingEdit = true);
    final annotationPayload = hasDrawing
        ? WorkbenchAnnotationPayload(
            strokes: List<WorkbenchAnnotationStroke>.unmodifiable(_editStrokes),
            canvasSize: _editCanvasSize,
            prompt: prompt,
          )
        : null;
    final frontendContext = _buildToolbarEditFrontendContext(
      project,
      display,
      annotationPayload,
    );
    final backend = NativeWorkbenchProjectBackend();
    await backend.setActiveFrontendContext(frontendContext);
    final hotUpdateResult = await _service.applyHotUpdate(
      project,
      prompt,
      frontendContext: frontendContext,
    );
    final needsAgent = hotUpdateResult?.requiresAgentRegeneration == true;
    var success = hotUpdateResult?.success == true;
    if (success && needsAgent) {
      final taskId =
          'workbench-toolbar-edit-${DateTime.now().millisecondsSinceEpoch}';
      success = await AssistsMessageService.createAgentTask(
        taskId: taskId,
        userMessage: _buildToolbarEditAgentContinuationMessage(
          prompt,
          frontendContext,
          hotUpdateResult!,
        ),
        conversationMode: 'subagent',
      );
    }
    if (!mounted) return;
    setState(() {
      _submittingEdit = false;
      if (success) {
        _editPromptVisible = false;
        _editPromptController.clear();
        _clearEditDrawing(clearPrompt: false);
      }
    });
    if (success) {
      _editPromptFocusNode.unfocus();
      showToast(needsAgent ? '已开始调整当前项目' : '已应用到当前项目', type: ToastType.success);
    } else {
      showToast(
        context.l10n.workbenchAssistantHotUpdateFailed,
        type: ToastType.error,
      );
    }
  }

  void _startEditStroke(DragStartDetails details) {
    if (_submittingEdit || !_editDrawingEnabled) return;
    setState(() {
      _editCurrentPoints
        ..clear()
        ..add(details.localPosition);
    });
  }

  void _appendEditStrokePoint(DragUpdateDetails details) {
    if (_submittingEdit || !_editDrawingEnabled || _editCurrentPoints.isEmpty) {
      return;
    }
    setState(() {
      _editCurrentPoints.add(details.localPosition);
    });
  }

  void _finishEditStroke([DragEndDetails? _]) {
    if (_submittingEdit || _editCurrentPoints.length < 2) {
      setState(_editCurrentPoints.clear);
      return;
    }
    final points = List<Offset>.unmodifiable(_editCurrentPoints);
    setState(() {
      _editStrokes.add(
        WorkbenchAnnotationStroke(
          id: 'toolbar-stroke-${_nextEditStrokeId++}',
          points: points,
          color: _editStrokeColor,
          strokeWidth: _editStrokeWidth,
        ),
      );
      _editCurrentPoints.clear();
    });
  }

  void _undoEditStroke() {
    if (_editStrokes.isEmpty || _submittingEdit) return;
    setState(() {
      _editStrokes.removeLast();
    });
  }

  void _clearEditDrawing({bool clearPrompt = false}) {
    _editStrokes.clear();
    _editCurrentPoints.clear();
    if (clearPrompt) {
      _editPromptController.clear();
    }
  }

  void _clearEditDrawingWithRefresh() {
    if (_submittingEdit ||
        (_editStrokes.isEmpty && _editCurrentPoints.isEmpty)) {
      return;
    }
    setState(() => _clearEditDrawing());
  }

  void _closeEditPrompt() {
    setState(() {
      _editPromptVisible = false;
      _clearEditDrawing(clearPrompt: false);
    });
    _editPromptFocusNode.unfocus();
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

  String? _projectDisplayName(WorkbenchProject? project) {
    if (project == null) return null;
    final name = project.name.trim();
    return name.isEmpty ? project.projectId : name;
  }

  String? _projectDescription(
    WorkbenchProject? project,
    WorkbenchDisplaySpec? display,
  ) {
    if (project == null) return null;
    final candidates = [
      project.pageSpec['description'],
      project.pageSpec['subtitle'],
      display?.description,
    ];
    for (final candidate in candidates) {
      final value = candidate?.toString().trim() ?? '';
      if (value.isEmpty) continue;
      if (value.startsWith('Live HTML Display')) continue;
      if (value.startsWith('Live Flutter Display')) continue;
      if (value.startsWith('Display bound to')) continue;
      return value;
    }
    return null;
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
          projectName: _projectDisplayName(project),
          projectDescription: _projectDescription(project, display),
          translucent: widget.translucentSurfaces,
          onOpenProjectManager: () => _openProjectManager(project),
          onRefresh: _refreshAndEnsureActive,
          onEdit: project == null ? null : () => _toggleEditPrompt(project),
          editing: _editPromptVisible,
          onOpenDisplay: project == null || display == null
              ? null
              : () => _openDisplayRoute(project, display),
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
            child: Stack(
              fit: StackFit.expand,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: context.omniPalette.borderSubtle,
                      ),
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
                if (_editPromptVisible)
                  Positioned.fill(
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        _editCanvasSize = Size(
                          constraints.maxWidth.isFinite
                              ? constraints.maxWidth
                              : 0,
                          constraints.maxHeight.isFinite
                              ? constraints.maxHeight
                              : 0,
                        );
                        return IgnorePointer(
                          ignoring: !_editDrawingEnabled || _submittingEdit,
                          child: GestureDetector(
                            behavior: HitTestBehavior.translucent,
                            onPanStart: _startEditStroke,
                            onPanUpdate: _appendEditStrokePoint,
                            onPanEnd: _finishEditStroke,
                            onPanCancel: () => _finishEditStroke(),
                            child: CustomPaint(
                              painter: WorkbenchAnnotationPainter(
                                strokes: _editStrokes,
                                currentPoints: _editCurrentPoints,
                                currentColor: _editStrokeColor,
                                currentStrokeWidth: _editStrokeWidth,
                                drawingEnabled: _editDrawingEnabled,
                              ),
                              child: const SizedBox.expand(),
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                if (_editPromptVisible && project != null && display != null)
                  Positioned(
                    left: 10,
                    right: 10,
                    bottom: 10,
                    child: _WorkspaceProjectEditBubble(
                      translucent: widget.translucentSurfaces,
                      controller: _editPromptController,
                      focusNode: _editPromptFocusNode,
                      submitting: _submittingEdit,
                      drawingEnabled: _editDrawingEnabled,
                      strokeCount: _editStrokes.length,
                      onToggleDrawing: () => setState(
                        () => _editDrawingEnabled = !_editDrawingEnabled,
                      ),
                      onUndoDrawing: _undoEditStroke,
                      onClearDrawing: _clearEditDrawingWithRefresh,
                      onClose: _closeEditPrompt,
                      onSubmit: () => _submitProjectEdit(project, display),
                    ),
                  ),
              ],
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
    required this.projectName,
    required this.projectDescription,
    required this.translucent,
    required this.onOpenProjectManager,
    required this.onRefresh,
    required this.onEdit,
    required this.editing,
    required this.onOpenDisplay,
  });

  final String? projectName;
  final String? projectDescription;
  final bool translucent;
  final VoidCallback onOpenProjectManager;
  final Future<void> Function() onRefresh;
  final VoidCallback? onEdit;
  final bool editing;
  final VoidCallback? onOpenDisplay;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = backgroundSurfaceColor(
      translucent: translucent,
      baseColor: palette.surfacePrimary,
      opacity: 0.74,
    );
    final title = projectName?.trim() ?? '';
    final subtitle = projectDescription?.trim() ?? '';
    return Container(
      height: 54,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: background,
        border: Border(bottom: BorderSide(color: palette.borderSubtle)),
      ),
      child: Row(
        children: [
          Expanded(
            child: title.isEmpty
                ? const SizedBox.shrink()
                : Column(
                    mainAxisAlignment: subtitle.isEmpty
                        ? MainAxisAlignment.center
                        : MainAxisAlignment.start,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 13,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      if (subtitle.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          subtitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 11,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ],
                  ),
          ),
          const SizedBox(width: 8),
          Tooltip(
            message: context.l10n.workbenchProjectEditAction,
            child: IconButton(
              onPressed: onEdit,
              constraints: const BoxConstraints.tightFor(width: 36, height: 36),
              padding: EdgeInsets.zero,
              icon: Icon(
                Icons.edit_outlined,
                color: editing ? palette.accentPrimary : palette.textSecondary,
              ),
            ),
          ),
          IconButton(
            tooltip: context.l10n.workbenchWorkspaceOpenProjectConsole,
            onPressed: onOpenProjectManager,
            constraints: const BoxConstraints.tightFor(width: 36, height: 36),
            padding: EdgeInsets.zero,
            icon: Icon(
              Icons.dashboard_customize_outlined,
              color: palette.textSecondary,
            ),
          ),
          IconButton(
            tooltip: context.l10n.workbenchOpenDisplay,
            onPressed: onOpenDisplay,
            constraints: const BoxConstraints.tightFor(width: 36, height: 36),
            padding: EdgeInsets.zero,
            icon: Icon(Icons.open_in_new_rounded, color: palette.textSecondary),
          ),
          IconButton(
            tooltip: context.l10n.omniflowRefresh,
            onPressed: () => unawaited(onRefresh()),
            constraints: const BoxConstraints.tightFor(width: 36, height: 36),
            padding: EdgeInsets.zero,
            icon: Icon(Icons.refresh_rounded, color: palette.textSecondary),
          ),
        ],
      ),
    );
  }
}

class _WorkspaceProjectEditBubble extends StatelessWidget {
  const _WorkspaceProjectEditBubble({
    required this.translucent,
    required this.controller,
    required this.focusNode,
    required this.submitting,
    required this.drawingEnabled,
    required this.strokeCount,
    required this.onToggleDrawing,
    required this.onUndoDrawing,
    required this.onClearDrawing,
    required this.onClose,
    required this.onSubmit,
  });

  final bool translucent;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool submitting;
  final bool drawingEnabled;
  final int strokeCount;
  final VoidCallback onToggleDrawing;
  final VoidCallback onUndoDrawing;
  final VoidCallback onClearDrawing;
  final VoidCallback onClose;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = backgroundSurfaceColor(
      translucent: translucent,
      baseColor: palette.surfacePrimary,
      opacity: 0.96,
    );
    return Material(
      color: Colors.transparent,
      elevation: 10,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.fromLTRB(10, 8, 8, 8),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.14),
              blurRadius: 20,
              offset: const Offset(0, 10),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                _WorkspaceEditToolButton(
                  tooltip: drawingEnabled
                      ? context.l10n.workbenchAnnotationBrowseMode
                      : context.l10n.workbenchAnnotationDrawMode,
                  icon: drawingEnabled
                      ? Icons.pan_tool_alt_outlined
                      : Icons.draw_outlined,
                  active: drawingEnabled,
                  onPressed: submitting ? null : onToggleDrawing,
                ),
                _WorkspaceEditToolButton(
                  tooltip: context.l10n.workbenchAnnotationUndo,
                  icon: Icons.undo_rounded,
                  onPressed: submitting || strokeCount == 0
                      ? null
                      : onUndoDrawing,
                ),
                _WorkspaceEditToolButton(
                  tooltip: context.l10n.workbenchAnnotationClear,
                  icon: Icons.delete_sweep_outlined,
                  onPressed: submitting || strokeCount == 0
                      ? null
                      : onClearDrawing,
                ),
                const SizedBox(width: 4),
                Container(
                  height: 26,
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: palette.surfaceSecondary.withValues(alpha: 0.78),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: palette.borderSubtle),
                  ),
                  child: Text(
                    strokeCount == 0
                        ? context.l10n.workbenchAnnotationNoShape
                        : context.l10n.workbenchAnnotationShapeCount(
                            strokeCount,
                          ),
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
                const Spacer(),
                IconButton(
                  tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                  onPressed: submitting ? null : onClose,
                  constraints: const BoxConstraints.tightFor(
                    width: 32,
                    height: 32,
                  ),
                  padding: EdgeInsets.zero,
                  icon: Icon(
                    Icons.close_rounded,
                    size: 18,
                    color: palette.textSecondary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 7),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    focusNode: focusNode,
                    enabled: !submitting,
                    minLines: 1,
                    maxLines: 4,
                    textInputAction: TextInputAction.send,
                    onSubmitted: (_) {
                      if (!submitting) onSubmit();
                    },
                    decoration: InputDecoration(
                      hintText: context.l10n.workbenchAssistantPromptHint,
                      isDense: true,
                      filled: true,
                      fillColor: palette.surfaceSecondary.withValues(
                        alpha: 0.7,
                      ),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide(color: palette.borderSubtle),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide(color: palette.borderSubtle),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide(color: palette.accentPrimary),
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 9,
                      ),
                    ),
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 13,
                      height: 1.25,
                    ),
                  ),
                ),
                const SizedBox(width: 6),
                IconButton(
                  tooltip: context.l10n.workbenchAssistantSend,
                  onPressed: submitting ? null : onSubmit,
                  constraints: const BoxConstraints.tightFor(
                    width: 34,
                    height: 34,
                  ),
                  padding: EdgeInsets.zero,
                  icon: submitting
                      ? SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: palette.accentPrimary,
                          ),
                        )
                      : Icon(
                          Icons.arrow_upward_rounded,
                          size: 19,
                          color: palette.accentPrimary,
                        ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _WorkspaceEditToolButton extends StatelessWidget {
  const _WorkspaceEditToolButton({
    required this.tooltip,
    required this.icon,
    this.active = false,
    this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final bool active;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = active ? palette.accentPrimary : palette.textSecondary;
    return IconButton(
      tooltip: tooltip,
      onPressed: onPressed,
      constraints: const BoxConstraints.tightFor(width: 32, height: 32),
      padding: EdgeInsets.zero,
      icon: Icon(icon, size: 18, color: color),
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
    final renderer = display.renderer.trim().isEmpty
        ? display.kind.trim()
        : display.renderer.trim();
    final hostsHtmlDisplay =
        renderer == 'html_webview' ||
        display.id == 'html-main-display' ||
        route.startsWith('/workbench/html');
    if (hostsHtmlDisplay) {
      return WorkbenchHtmlDisplayPage(
        projectId: project.projectId,
        displayId: display.id,
        embedded: true,
      );
    }
    final hostsFlutterEval =
        renderer == 'flutter_eval' ||
        display.id == 'flutter-main-display' ||
        route.startsWith('/workbench/flutter_eval');
    if (hostsFlutterEval) {
      return WorkbenchFlutterEvalPage(
        projectId: project.projectId,
        displayId: display.id,
        embedded: true,
      );
    }
    return WorkbenchProjectDisplayPage(
      projectId: project.projectId,
      displayId: display.id,
      annotationMode: false,
      embedded: true,
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
