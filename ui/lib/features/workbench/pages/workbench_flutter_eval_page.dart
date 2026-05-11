import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_eval/flutter_eval.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchFlutterEvalPage extends StatefulWidget {
  const WorkbenchFlutterEvalPage({
    super.key,
    WorkbenchProjectBackend? backend,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool embedded = false,
  }) : _backend = backend,
       _projectId = projectId,
       _returnTo = returnTo,
       _embedded = embedded;

  final WorkbenchProjectBackend? _backend;
  final String? _projectId;
  final String? _returnTo;
  final bool _embedded;

  @override
  State<WorkbenchFlutterEvalPage> createState() =>
      _WorkbenchFlutterEvalPageState();
}

class _WorkbenchFlutterEvalPageState extends State<WorkbenchFlutterEvalPage> {
  late final _ProjectLoader _loader;
  StreamSubscription<Map<String, dynamic>>? _updateSub;

  @override
  void initState() {
    super.initState();
    _loader = _ProjectLoader(
      backend: widget._backend ?? NativeWorkbenchProjectBackend(),
      projectId: widget._projectId,
    );
    unawaited(_loader.initialize());
    _updateSub = AssistsMessageService.workbenchProjectUpdatedStream.listen(
      _onProjectUpdated,
    );
  }

  void _onProjectUpdated(Map<String, dynamic> event) {
    final updatedId = event['projectId'] as String?;
    final currentId = _loader.project?.projectId ?? widget._projectId;
    if (updatedId == null || updatedId != currentId) return;
    unawaited(_loader.refresh());
  }

  @override
  void dispose() {
    _updateSub?.cancel();
    _loader.dispose();
    super.dispose();
  }

  void _handleBackNavigation() {
    final returnTo = widget._returnTo?.trim();
    if (returnTo != null && returnTo.isNotEmpty) {
      context.go(returnTo);
      return;
    }
    if (GoRouterManager.canPop()) {
      GoRouterManager.pop();
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final body = AnimatedBuilder(
      animation: _loader,
      builder: (context, _) {
        final content = Column(
          children: [
            if (_loader.loading) const LinearProgressIndicator(minHeight: 2),
            Expanded(child: _buildEvalBody(_loader.project)),
          ],
        );
        return Material(color: palette.pageBackground, child: content);
      },
    );
    if (widget._embedded) {
      return body;
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: context.l10n.workbenchFlutterEvalTitle,
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(child: body),
      ),
    );
  }

  Widget _buildEvalBody(WorkbenchProject? project) {
    if (project == null) {
      return _buildStatus(
        icon: Icons.hourglass_empty_rounded,
        label: context.l10n.workbenchFlutterEvalNoSource,
      );
    }
    final sources = _dartSources(project);
    if (sources.isEmpty) {
      return _buildStatus(
        icon: Icons.code_off_rounded,
        label: context.l10n.workbenchFlutterEvalNoSource,
      );
    }
    final entryFile =
        project.frontendFlutter['entryFile']?.toString().trim().isNotEmpty ==
            true
        ? project.frontendFlutter['entryFile']!.toString().trim()
        : 'lib/main.dart';
    final entryClass =
        project.frontendFlutter['entryClass']?.toString().trim().isNotEmpty ==
            true
        ? project.frontendFlutter['entryClass']!.toString().trim()
        : 'OobProjectWidget';
    if (!sources.containsKey(entryFile)) {
      return _buildStatus(
        icon: Icons.code_off_rounded,
        label: context.l10n.workbenchFlutterEvalNoSource,
      );
    }
    final sourcesKey = sources.entries
        .map((e) => '${e.key}:${e.value.hashCode}')
        .join('|');
    return CompilerWidget(
      key: ValueKey(sourcesKey),
      packages: {'oob_project': sources},
      library: 'package:oob_project/$entryFile',
      function: '$entryClass.',
      args: const [],
      onError: (context, error, _) => _buildStatus(
        icon: Icons.error_outline_rounded,
        label: context.l10n.workbenchFlutterEvalCompileFailed,
      ),
    );
  }

  Map<String, String> _dartSources(WorkbenchProject project) {
    final rawSources = project.frontendFlutter['sources'];
    if (rawSources is! Map) return const {};
    final sources = <String, String>{};
    rawSources.forEach((key, value) {
      final path = key.toString();
      if (!path.endsWith('.dart')) return;
      sources[path] = value?.toString() ?? '';
    });
    return sources;
  }

  Widget _buildStatus({required IconData icon, required String label}) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: palette.textTertiary, size: 28),
            const SizedBox(height: 10),
            Text(
              label,
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.textSecondary, fontSize: 13),
            ),
          ],
        ),
      ),
    );
  }
}

/// Minimal project loader for flutter_eval display.
/// Loads any project by ID via [WorkbenchProjectBackend.getProject]
/// without any template-specific logic.
class _ProjectLoader extends ChangeNotifier {
  _ProjectLoader({
    required WorkbenchProjectBackend backend,
    String? projectId,
  }) : _backend = backend,
       _projectId = projectId?.trim();

  final WorkbenchProjectBackend _backend;
  final String? _projectId;
  WorkbenchProject? _project;
  bool _loading = false;

  WorkbenchProject? get project => _project;
  bool get loading => _loading;

  Future<void> initialize() async {
    final id = _projectId;
    if (id == null || id.isEmpty) return;
    _loading = true;
    notifyListeners();
    try {
      _project = await _backend.getProject(id);
    } catch (_) {
      // leave project null; UI shows no-source state
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> refresh() async {
    final id = _project?.projectId ?? _projectId;
    if (id == null || id.isEmpty) return;
    try {
      _project = await _backend.getProject(id);
      notifyListeners();
    } catch (_) {}
  }
}
