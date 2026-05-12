import 'dart:async';
import 'dart:convert';
import 'dart:collection';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/common_app_bar.dart';

// Bridge script injected at AT_DOCUMENT_START — window.oob is available
// before any page script runs, eliminating race conditions.
// Viewport injection runs at AT_DOCUMENT_END when document.head is guaranteed present.
const String _kViewportScript = '''
(function () {
  if (!document.querySelector('meta[name=viewport]')) {
    var vp = document.createElement('meta');
    vp.name = 'viewport';
    vp.content = 'width=device-width, initial-scale=1';
    (document.head || document.documentElement).appendChild(vp);
  }
  // Eliminate 300ms double-tap delay while preserving pinch-zoom.
  var s = document.createElement('style');
  s.textContent = 'html,body{touch-action:manipulation;}';
  (document.head || document.documentElement).appendChild(s);
})();
''';

const String _kBridgeScript = '''
(function () {
  if (window.oob && window.oob.__installed) return;
  var _updateCallbacks = [];
  function _callNative(handlerName, args) {
    return new Promise(function(resolve, reject) {
      function invoke() {
        if (window.flutter_inappwebview &&
            typeof window.flutter_inappwebview.callHandler === 'function') {
          Promise.resolve(
            window.flutter_inappwebview.callHandler.apply(
              window.flutter_inappwebview,
              [handlerName].concat(args || [])
            )
          ).then(resolve, reject);
          return true;
        }
        return false;
      }
      if (invoke()) return;
      var timeout = setTimeout(function() {
        reject(new Error('OOB WebView bridge is not ready.'));
      }, 5000);
      window.addEventListener('flutterInAppWebViewPlatformReady', function() {
        clearTimeout(timeout);
        if (!invoke()) reject(new Error('OOB WebView bridge is not available.'));
      }, { once: true });
    });
  }
  var _pendingTasks = {};
  window.oob = {
    __installed: true,
    callApi: function(apiId, inputs) {
      return _callNative('oobCallApi', [apiId, inputs || {}]).then(function(result) {
        if (result && result.status === 'pending' && result.taskId) {
          var taskId = result.taskId;
          var timer = setTimeout(function() {
            delete _pendingTasks[taskId];
            _updateCallbacks.forEach(function(cb) {
              try {
                cb({ _taskError: true, taskId: taskId, apiId: apiId,
                     errorMessage: 'Task timed out after 120s.' });
              } catch(e) {}
            });
          }, 120000);
          _pendingTasks[taskId] = timer;
        }
        return result;
      });
    },
    getProject: function() {
      return _callNative('oobGetProject', []);
    },
    selectElement: function(payload) {
      return _callNative('oobSelectElement', [payload || {}]);
    },
    onProjectUpdated: function(callback) {
      if (typeof callback === 'function') _updateCallbacks.push(callback);
    },
    __dispatchUpdate: function(project) {
      // Clear pending task timers when an update arrives.
      Object.keys(_pendingTasks).forEach(function(id) {
        clearTimeout(_pendingTasks[id]);
        delete _pendingTasks[id];
      });
      _updateCallbacks.forEach(function(cb) {
        try { cb(project); } catch(e) {}
      });
    }
  };
})();
''';

const String _kBridgeBootstrapTag =
    '''
<script>
$_kBridgeScript
</script>
<script>
$_kViewportScript
</script>
<script>
$_kInspectScript
</script>
''';

const String _kInspectScript = '''
(function () {
  if (window.__oobInspectInstalled) return;
  window.__oobInspectInstalled = true;
  var _selected = null;
  function _clear() {
    if (_selected) {
      _selected.style.outline = _selected.__oobOldOutline || '';
      _selected.style.outlineOffset = _selected.__oobOldOffset || '';
    }
  }
  document.addEventListener('click', function(e) {
    if (!window.__oobInspectEnabled) return;
    e.preventDefault(); e.stopPropagation();
    _clear();
    var el = e.target;
    _selected = el;
    el.__oobOldOutline = el.style.outline || '';
    el.__oobOldOffset = el.style.outlineOffset || '';
    el.style.outline = '2px solid #2563eb';
    el.style.outlineOffset = '2px';
    var r = el.getBoundingClientRect();
    window.oob.selectElement({
      tagName: el.tagName,
      id: el.id || '',
      className: el.className ? String(el.className) : '',
      oobId: el.getAttribute('data-oob-id') || '',
      text: (el.innerText || el.textContent || '').trim().slice(0, 500),
      html: (el.outerHTML || '').slice(0, 3000),
      rect: { x: r.x, y: r.y, width: r.width, height: r.height }
    }).catch(function(){});
  }, true);
})();
''';

class WorkbenchHtmlDisplayPage extends StatefulWidget {
  const WorkbenchHtmlDisplayPage({
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
  State<WorkbenchHtmlDisplayPage> createState() =>
      _WorkbenchHtmlDisplayPageState();
}

class _WorkbenchHtmlDisplayPageState extends State<WorkbenchHtmlDisplayPage> {
  late final _HtmlProjectLoader _loader;
  InAppWebViewController? _webController;
  StreamSubscription<Map<String, dynamic>>? _updateSub;
  Timer? _refreshDebounce;

  bool _webViewLoading = false;
  String? _errorMessage;
  double? _pendingScrollRestore;

  @override
  void initState() {
    super.initState();
    _loader = _HtmlProjectLoader(
      backend: widget._backend ?? NativeWorkbenchProjectBackend(),
      projectId: widget._projectId,
    );
    unawaited(_initialize());
    _updateSub = AssistsMessageService.workbenchProjectUpdatedStream.listen(
      _onProjectUpdated,
    );
  }

  Future<void> _initialize() async {
    await _loader.initialize();
    if (mounted) setState(() {});
    unawaited(_loadHtml());
  }

  void _onProjectUpdated(Map<String, dynamic> event) {
    final id = event['projectId'] as String?;
    if (id == null || id != (_loader.project?.projectId ?? widget._projectId)) {
      return;
    }

    final paths = (event['updatedPaths'] as List?)?.cast<String>() ?? [];
    final isHtmlChange = paths.any(
      (p) => p.endsWith('.html') || p.endsWith('.css') || p.endsWith('.js'),
    );

    if (isHtmlChange) {
      _refreshDebounce?.cancel();
      _refreshDebounce = Timer(const Duration(milliseconds: 100), () {
        unawaited(_reloadHtmlPreservingScroll());
      });
      return;
    }

    // Data-only update — use items from event if available, otherwise refresh.
    final rawItems = event['items'];
    if (rawItems is List) {
      _refreshDebounce?.cancel();
      _refreshDebounce = Timer(const Duration(milliseconds: 100), () {
        if (!mounted) return;
        _loader.applyItems(rawItems.cast<Map>());
        final payload = _bridgeUpdatePayload(_loader.project);
        final js =
            'window.oob && window.oob.__dispatchUpdate && '
            'window.oob.__dispatchUpdate(${jsonEncode(payload)});';
        unawaited(_webController?.evaluateJavascript(source: js));
      });
      return;
    }

    // Fallback: full refresh (for events without inline items).
    _refreshDebounce?.cancel();
    _refreshDebounce = Timer(const Duration(milliseconds: 100), () {
      unawaited(
        _loader.refresh().then((_) {
          if (!mounted) return;
          final payload = _bridgeUpdatePayload(_loader.project);
          final js =
              'window.oob && window.oob.__dispatchUpdate && '
              'window.oob.__dispatchUpdate(${jsonEncode(payload)});';
          unawaited(_webController?.evaluateJavascript(source: js));
        }),
      );
    });
  }

  @override
  void dispose() {
    _refreshDebounce?.cancel();
    _updateSub?.cancel();
    _loader.dispose();
    super.dispose();
  }

  // ── WebView setup ──────────────────────────────────────────────────────────

  void _onWebViewCreated(InAppWebViewController controller) {
    _webController = controller;

    controller.addJavaScriptHandler(
      handlerName: 'oobCallApi',
      callback: (args) async {
        final apiId = args.isNotEmpty ? args[0]?.toString() ?? '' : '';
        final rawInputs = args.length > 1 ? args[1] : null;
        final inputs = rawInputs is Map
            ? rawInputs.map((k, v) => MapEntry(k.toString(), v))
            : <String, Object?>{};
        return await _handleCallApi(apiId, inputs);
      },
    );

    controller.addJavaScriptHandler(
      handlerName: 'oobGetProject',
      callback: (_) => _bridgeProjectPayload(_loader.project),
    );

    controller.addJavaScriptHandler(
      handlerName: 'oobSelectElement',
      callback: (args) async {
        final raw = args.isNotEmpty ? args[0] : null;
        final selection = raw is Map
            ? raw.map((k, v) => MapEntry(k.toString(), v))
            : <String, Object?>{};
        await _publishFrontendContext(selection);
        return {'success': true};
      },
    );

    unawaited(_loadHtml());
  }

  Future<void> _reloadHtmlPreservingScroll() async {
    if (!mounted) return;
    final scrollY = await _webController?.evaluateJavascript(
      source: 'window.scrollY || 0',
    );
    _pendingScrollRestore = scrollY != null
        ? double.tryParse(scrollY.toString())
        : null;
    await _loader.refresh();
    if (mounted) unawaited(_loadHtml());
  }

  Future<void> _loadHtml() async {
    final controller = _webController;
    final project = _loader.project;
    if (controller == null || project == null) return;

    final html = project.frontendHtml;
    final entryPath = html['entryPath']?.toString().trim() ?? '';
    final entryFile = html['entryFile']?.toString().trim() ?? 'index.html';
    final sources = html['sources'];

    if (mounted) {
      setState(() {
        _webViewLoading = true;
        _errorMessage = null;
      });
    }

    try {
      final entry = File(entryPath);
      if (entryPath.isNotEmpty && entry.existsSync()) {
        final htmlText = await entry.readAsString();
        await controller.loadData(
          data: _htmlWithBridgeBootstrap(htmlText),
          mimeType: 'text/html',
          encoding: 'utf-8',
          baseUrl: WebUri(entry.parent.uri.toString()),
        );
        return;
      }
      if (sources is Map && sources[entryFile] != null) {
        await controller.loadData(
          data: _htmlWithBridgeBootstrap(sources[entryFile].toString()),
          mimeType: 'text/html',
          encoding: 'utf-8',
          baseUrl: WebUri('file:///android_asset/'),
        );
        return;
      }
      if (mounted) {
        setState(() {
          _webViewLoading = false;
          _errorMessage = '没有可展示的 HTML 产物';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _webViewLoading = false;
          _errorMessage = '$e';
        });
      }
    }
  }

  String _htmlWithBridgeBootstrap(String html) {
    final headMatch = RegExp(
      r'<head(\s[^>]*)?>',
      caseSensitive: false,
    ).firstMatch(html);
    if (headMatch != null) {
      return html.replaceRange(
        headMatch.end,
        headMatch.end,
        '\n$_kBridgeBootstrapTag',
      );
    }
    final htmlMatch = RegExp(
      r'<html(\s[^>]*)?>',
      caseSensitive: false,
    ).firstMatch(html);
    if (htmlMatch != null) {
      return html.replaceRange(
        htmlMatch.end,
        htmlMatch.end,
        '\n<head>\n$_kBridgeBootstrapTag</head>\n',
      );
    }
    return '<!doctype html><html><head>\n$_kBridgeBootstrapTag</head><body>$html</body></html>';
  }

  // ── bridge handlers ────────────────────────────────────────────────────────

  Future<Map<String, Object?>> _handleCallApi(
    String apiId,
    Map<String, Object?> inputs,
  ) async {
    final project = _loader.project;
    if (project == null || apiId.isEmpty) {
      return {'success': false, 'error': 'Project and apiId are required.'};
    }
    try {
      final result = await _loader.backend.callApi(
        projectId: project.projectId,
        apiId: apiId,
        inputs: inputs,
      );
      if (result.project != null) {
        _loader.setProject(result.project!);
      } else if (result.success) {
        await _loader.refresh();
      }
      return {
        'success': result.success,
        'apiId': result.toolId,
        'outputs': result.outputs,
        if (result.errorCode != null) 'errorCode': result.errorCode,
        if (result.errorMessage != null) 'errorMessage': result.errorMessage,
        'project': _bridgeProjectPayload(_loader.project),
      };
    } catch (e) {
      return {'success': false, 'error': '$e'};
    }
  }

  Future<void> _publishFrontendContext(Map<String, Object?> selection) async {
    final backend = _loader.backend;
    if (backend is! NativeWorkbenchProjectBackend) return;
    final project = _loader.project;
    if (project == null) return;
    await backend.setActiveFrontendContext({
      'source': 'workbench_html_display_page',
      'projectId': project.projectId,
      'displayId': widget._displayId ?? project.primaryDisplay.id,
      'route': '/workbench/html?projectId=${project.projectId}',
      'renderer': 'html_webview',
      'selectedElement': selection,
      'mode': 'html_webview',
    });
  }

  Map<String, Object?> _bridgeProjectPayload(WorkbenchProject? project) {
    if (project == null) return const {};
    return {
      'projectId': project.projectId,
      'name': project.name,
      'route': project.route,
      'displayId': widget._displayId ?? project.primaryDisplay.id,
      'pageSpec': project.pageSpec,
      'frontendHtml': project.frontendHtml,
      'tools': _bridgeTools(project),
      'items': _bridgeItems(project),
    };
  }

  // Lightweight payload for data-only updates — omits frontendHtml which is
  // already loaded in the WebView and never changes during item mutations.
  Map<String, Object?> _bridgeUpdatePayload(WorkbenchProject? project) {
    if (project == null) return const {};
    return {
      'projectId': project.projectId,
      'name': project.name,
      'tools': _bridgeTools(project),
      'items': _bridgeItems(project),
    };
  }

  List<Map<String, Object?>> _bridgeTools(WorkbenchProject project) => project
      .tools
      .map(
        (t) => <String, Object?>{
          'id': t.id,
          'toolId': t.id,
          'displayName': t.displayName,
          'description': t.description,
          'inputKeys': t.inputKeys,
          'outputKeys': t.outputKeys,
        },
      )
      .toList(growable: false);

  List<Map<String, Object?>> _bridgeItems(WorkbenchProject project) => project
      .items
      .map(
        (item) => <String, Object?>{
          'id': item.id,
          'title': item.title,
          'status': item.status,
          'fields': item.fields,
        },
      )
      .toList(growable: false);

  // ── navigation ─────────────────────────────────────────────────────────────

  void _handleBack() {
    final ret = widget._returnTo?.trim();
    if (ret != null && ret.isNotEmpty) {
      context.go(ret);
      return;
    }
    if (GoRouterManager.canPop()) {
      GoRouterManager.pop();
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  // ── build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final body = AnimatedBuilder(
      animation: _loader,
      builder: (context, _) {
        final project = _loader.project;
        final hasHtml = project?.frontendHtml.isNotEmpty == true;
        return Material(
          color: palette.pageBackground,
          child: Column(
            children: [
              if (_loader.loading || _webViewLoading)
                const LinearProgressIndicator(minHeight: 2),
              Expanded(
                child: !hasHtml || _errorMessage != null
                    ? _buildStatus(
                        icon: _errorMessage == null
                            ? Icons.language_outlined
                            : Icons.error_outline_rounded,
                        label: _errorMessage ?? '没有可展示的 HTML 产物',
                      )
                    : ChatDrawerGestureGuard(child: _buildWebView()),
              ),
            ],
          ),
        );
      },
    );
    if (widget._embedded) return body;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBack();
      },
      child: AnimatedBuilder(
        animation: _loader,
        builder: (context, _) => Scaffold(
          backgroundColor: palette.pageBackground,
          appBar: CommonAppBar(
            title: _loader.project?.name.trim().isNotEmpty == true
                ? _loader.project!.name
                : 'HTML 展示',
            primary: true,
            onBackPressed: _handleBack,
          ),
          body: SafeArea(child: body),
        ),
      ),
    );
  }

  Widget _buildWebView() {
    return InAppWebView(
      initialSettings: InAppWebViewSettings(
        javaScriptEnabled: true,
        allowFileAccess: true,
        allowContentAccess: true,
        useHybridComposition: false,
        supportZoom: true,
        useWideViewPort: true,
        loadWithOverviewMode: true,
        userAgent:
            'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 '
            '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 OOBWorkbench/1.0',
        cacheMode: CacheMode.LOAD_NO_CACHE,
      ),
      initialUserScripts: UnmodifiableListView([
        UserScript(
          source: _kBridgeScript,
          injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
        ),
        UserScript(
          source: _kViewportScript,
          injectionTime: UserScriptInjectionTime.AT_DOCUMENT_END,
        ),
        UserScript(
          source: _kInspectScript,
          injectionTime: UserScriptInjectionTime.AT_DOCUMENT_END,
        ),
      ]),
      gestureRecognizers: {
        Factory<EagerGestureRecognizer>(() => EagerGestureRecognizer()),
      },
      onWebViewCreated: _onWebViewCreated,
      onLoadStart: (_, __) {
        if (mounted) {
          setState(() {
            _webViewLoading = true;
            _errorMessage = null;
          });
        }
      },
      onLoadStop: (controller, __) async {
        await controller.evaluateJavascript(source: _kBridgeScript);
        await controller.evaluateJavascript(source: _kViewportScript);
        await controller.evaluateJavascript(source: _kInspectScript);
        final restore = _pendingScrollRestore;
        if (restore != null && restore > 0) {
          _pendingScrollRestore = null;
          await controller.evaluateJavascript(
            source: 'window.scrollTo(0, $restore)',
          );
        }
        if (mounted) setState(() => _webViewLoading = false);
      },
      onReceivedError: (_, __, error) {
        if (mounted) {
          setState(() {
            _webViewLoading = false;
            _errorMessage = error.description;
          });
        }
      },
      shouldOverrideUrlLoading: (_, action) async {
        final uri = action.request.url;
        if (uri == null) return NavigationActionPolicy.CANCEL;
        final scheme = uri.scheme;
        if (scheme == 'file' || scheme == 'about' || scheme == 'data') {
          return NavigationActionPolicy.ALLOW;
        }
        return NavigationActionPolicy.CANCEL;
      },
    );
  }

  Widget _buildStatus({required IconData icon, required String label}) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: palette.textTertiary, size: 30),
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

// ── project loader ─────────────────────────────────────────────────────────

class _HtmlProjectLoader extends ChangeNotifier {
  _HtmlProjectLoader({required this.backend, String? projectId})
    : _projectId = projectId?.trim();

  final WorkbenchProjectBackend backend;
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
      _project = await backend.getProject(id);
    } catch (_) {
      _project = null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> refresh() async {
    final id = _project?.projectId ?? _projectId;
    if (id == null || id.isEmpty) return;
    try {
      _project = await backend.getProject(id);
      notifyListeners();
    } catch (_) {}
  }

  void setProject(WorkbenchProject project) {
    _project = project;
    notifyListeners();
  }

  // Apply an inline items list from a dispatch event without a disk round-trip.
  void applyItems(List<Map> rawItems) {
    final current = _project;
    if (current == null) return;
    final parsed = rawItems.map(WorkbenchProjectItem.fromMap).toList();
    _project = current.copyWith(items: parsed);
    // No notifyListeners: the caller dispatches __dispatchUpdate directly to JS.
  }
}
