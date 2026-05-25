import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_eval/flutter_eval.dart';
import 'package:flutter_eval/security.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/pages/workbench_project_display_page.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
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
       _displayId = displayId,
       _returnTo = returnTo,
       _embedded = embedded;

  final WorkbenchProjectBackend? _backend;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _embedded;

  @override
  State<WorkbenchFlutterEvalPage> createState() =>
      _WorkbenchFlutterEvalPageState();
}

class _WorkbenchFlutterEvalPageState extends State<WorkbenchFlutterEvalPage> {
  static const String _oobMethodChannelName =
      'cn.com.omnimind.bot/AssistCoreEvent';
  static const String _legacyWorkbenchChannelName = 'workbench';

  late final _ProjectLoader _loader;
  StreamSubscription<Map<String, dynamic>>? _updateSub;
  final Set<String> _evalFallbackSourceKeys = <String>{};

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
    var sources = _dartSources(project);
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
    var entryClass =
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
    final normalizedEntry = _normalizeEntrySource(sources[entryFile]!);
    sources = {...sources, entryFile: normalizedEntry.source};
    if (!_declaresClass(normalizedEntry.source, entryClass) &&
        normalizedEntry.runAppWidgetClass != null &&
        _declaresClass(
          normalizedEntry.source,
          normalizedEntry.runAppWidgetClass!,
        )) {
      entryClass = normalizedEntry.runAppWidgetClass!;
    }
    final sourcesKey = sources.entries
        .map((e) => '${e.key}:${e.value.hashCode}')
        .join('|');
    if (_evalFallbackSourceKeys.contains(sourcesKey) ||
        _shouldSkipFlutterEval(sources)) {
      return _buildProjectDisplayFallback();
    }
    return _buildRuntimeWidget(
      key: ValueKey(sourcesKey),
      sourcesKey: sourcesKey,
      sources: sources,
      entryFile: entryFile,
      entryClass: entryClass,
    );
  }

  Widget _buildRuntimeWidget({
    required Key key,
    required String sourcesKey,
    required Map<String, String> sources,
    required String entryFile,
    required String entryClass,
  }) {
    final packages = {'oob_project': sources};
    final library = 'package:oob_project/$entryFile';
    final function = '$entryClass.';
    const permissions = [
      MethodChannelPermission(_oobMethodChannelName),
      MethodChannelPermission(_legacyWorkbenchChannelName),
    ];
    return CompilerWidget(
      key: key,
      packages: packages,
      library: library,
      function: function,
      args: const [null],
      permissions: permissions,
      onError: (context, firstError, firstTrace) {
        debugPrint(
          '[flutter_eval] runtime build error (with-arg): $firstError',
        );
        return CompilerWidget(
          key: ValueKey('$key-no-arg-fallback'),
          packages: packages,
          library: library,
          function: function,
          args: const [],
          permissions: permissions,
          onError: (context, secondError, secondTrace) {
            debugPrint(
              '[flutter_eval] runtime build error (no-arg): $secondError',
            );
            _scheduleEvalFallback(sourcesKey);
            return _buildStatus(
              icon: Icons.sync_problem_rounded,
              label: context.l10n.workbenchFlutterEvalRuntimeFailed,
            );
          },
        );
      },
    );
  }

  void _scheduleEvalFallback(String sourcesKey) {
    if (_evalFallbackSourceKeys.contains(sourcesKey)) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _evalFallbackSourceKeys.contains(sourcesKey)) return;
      setState(() {
        _evalFallbackSourceKeys.add(sourcesKey);
      });
    });
  }

  bool _shouldSkipFlutterEval(Map<String, String> sources) {
    final source = sources.values.join('\n');
    return RegExp(
      r'\b(GlobalKey|Form|FormState|FormField)\b|'
      r'\bshowDialog\s*\(|'
      r'\bNavigator\s*\.\s*of\s*\([^)]*\)\s*\.\s*push\s*\(',
    ).hasMatch(source);
  }

  Widget _buildProjectDisplayFallback() {
    final projectId = _loader.project?.projectId ?? widget._projectId;
    if (projectId == null || projectId.trim().isEmpty) {
      return _buildStatus(
        icon: Icons.error_outline_rounded,
        label: context.l10n.workbenchFlutterEvalRuntimeFailed,
      );
    }
    return WorkbenchProjectDisplayPage(
      projectId: projectId,
      displayId: widget._displayId,
      embedded: true,
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

  _NormalizedDartEntry _normalizeEntrySource(String source) {
    final runAppWidgetClass = RegExp(
      r'runApp\s*\(\s*(?:const\s+)?([A-Za-z_$][\w$]*)\s*\(',
    ).firstMatch(source)?.group(1);
    var normalized = source.replaceFirst(
      RegExp(
        r'\n?\s*void\s+main\s*\(\s*\)\s*(?:async\s*)?\{[\s\S]*?runApp\s*\([^;]*;\s*\n?\}',
        multiLine: true,
      ),
      '\n',
    );
    normalized = normalized
        .replaceAll(
          "MethodChannel('$_legacyWorkbenchChannelName')",
          "MethodChannel('$_oobMethodChannelName')",
        )
        .replaceAll(
          'MethodChannel("$_legacyWorkbenchChannelName")',
          'MethodChannel("$_oobMethodChannelName")',
        );
    return _NormalizedDartEntry(
      source: normalized,
      runAppWidgetClass: runAppWidgetClass,
    );
  }

  bool _declaresClass(String source, String className) {
    if (className.trim().isEmpty) return false;
    return RegExp(
      '\\bclass\\s+${RegExp.escape(className)}\\b',
    ).hasMatch(source);
  }

  Widget _buildStatus({
    required IconData icon,
    required String label,
    String? detail,
  }) {
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
            if (detail != null && detail.trim().isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                constraints: const BoxConstraints(maxWidth: 460),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: SelectableText(
                  detail,
                  textAlign: TextAlign.left,
                  style: TextStyle(
                    color: palette.textTertiary,
                    fontSize: 11,
                    height: 1.35,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _NormalizedDartEntry {
  const _NormalizedDartEntry({
    required this.source,
    required this.runAppWidgetClass,
  });

  final String source;
  final String? runAppWidgetClass;
}

/// Minimal project loader for flutter_eval display.
/// Loads any project by ID via [WorkbenchProjectBackend.getProject]
/// without any template-specific logic.
class _ProjectLoader extends ChangeNotifier {
  _ProjectLoader({required WorkbenchProjectBackend backend, String? projectId})
    : _backend = backend,
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
