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
import 'package:ui/features/workbench/services/workbench_shape_recognizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/common_app_bar.dart';

// Bridge script injected at AT_DOCUMENT_START — window.oob is available
// before any page script runs, eliminating race conditions.
// Viewport injection runs at AT_DOCUMENT_END when document.head is guaranteed present.
const String _kViewportScript = '''
(function () {
  var viewport = document.querySelector('meta[name=viewport]');
  if (!viewport) {
    var vp = document.createElement('meta');
    vp.name = 'viewport';
    vp.content = 'width=device-width, initial-scale=1';
    (document.head || document.documentElement).appendChild(vp);
    viewport = vp;
  }
  var viewportContent = viewport ? String(viewport.content || '') : '';
  var widthMatch = viewportContent.match(/\\bwidth\\s*=\\s*(\\d{3,})/i);
  var fixedCanvas = widthMatch ? parseInt(widthMatch[1], 10) >= 700 : false;
  var styleId = 'oob-workbench-viewport-style';
  var s = document.getElementById(styleId);
  if (!s) {
    s = document.createElement('style');
    s.id = styleId;
    (document.head || document.documentElement).appendChild(s);
  }
  var common = [
    '*,*::before,*::after{box-sizing:border-box;}',
    'img,video,canvas,svg,iframe{max-width:100%;}',
    'input,textarea,select,button{max-width:100%;font:inherit;}',
    'input[type="search"],input[type="text"],input:not([type]),textarea,select{min-width:0;}',
    '[role=search],form,fieldset,.toolbar,.filter-bar,.search-bar{max-width:100%;}',
    'html,body{min-height:100%;}',
    'body{overflow-y:auto!important;-webkit-overflow-scrolling:touch;}',
    '.oob-horizontal-scroll,[data-oob-horizontal-scroll]{max-width:100%;overflow-x:auto;touch-action:pan-x pan-y pinch-zoom;}'
  ].join('');
  var phone = [
    'html,body{max-width:100%;overflow-x:hidden;overscroll-behavior-x:none;touch-action:pan-y pinch-zoom;}',
    'pre,code{white-space:pre-wrap;word-break:break-word;}',
    'table{max-width:100%;}'
  ].join('');
  s.textContent = common + (fixedCanvas ? 'html,body{overscroll-behavior-x:none;}' : phone);
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
        // Agent tasks return {success:true, outputs:{status:'pending', taskId:..., conversationId:...}}
        var out = result && result.outputs;
        if (out && out.status === 'pending' && out.taskId) {
          var taskId = out.taskId;
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
    /// Open the execution step log for an agent task.
    /// conversationId is available in callApi result.outputs.conversationId
    /// for agent tasks (run.use="agent").
    openTaskLog: function(conversationId) {
      return _callNative('oobOpenTaskLog', [conversationId]);
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

const String _kSourceFallbackHost = 'oob-project.local';
const int _kWideCanvasViewportThreshold = 700;

InAppWebViewSettings _htmlWebViewSettings(bool wideCanvas) {
  return InAppWebViewSettings(
    javaScriptEnabled: true,
    allowFileAccess: true,
    allowContentAccess: true,
    useHybridComposition: true,
    transparentBackground: false,
    supportZoom: wideCanvas,
    builtInZoomControls: wideCanvas,
    displayZoomControls: false,
    textZoom: 100,
    useWideViewPort: wideCanvas,
    loadWithOverviewMode: wideCanvas,
    initialScale: wideCanvas ? 0 : 100,
    disableVerticalScroll: false,
    verticalScrollBarEnabled: true,
    disableHorizontalScroll: true,
    horizontalScrollBarEnabled: false,
    userAgent:
        'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 OOBWorkbench/1.0',
    cacheMode: CacheMode.LOAD_NO_CACHE,
  );
}

@visibleForTesting
bool workbenchHtmlUsesFixedCanvasViewport(String html) {
  final metaTags = RegExp(
    r'<meta\b[^>]*>',
    caseSensitive: false,
    dotAll: true,
  ).allMatches(html);
  for (final match in metaTags) {
    final tag = match.group(0) ?? '';
    final name = _htmlAttributeValue(tag, 'name')?.trim().toLowerCase();
    if (name != 'viewport') continue;
    final content = _htmlAttributeValue(tag, 'content') ?? '';
    final widthMatch = RegExp(
      r'\bwidth\s*=\s*(\d{3,})',
      caseSensitive: false,
    ).firstMatch(content);
    final width = int.tryParse(widthMatch?.group(1) ?? '');
    return width != null && width >= _kWideCanvasViewportThreshold;
  }
  return false;
}

String? _htmlAttributeValue(String tag, String attributeName) {
  final escapedAttributeName = RegExp.escape(attributeName);
  final quoted = RegExp(
    "$escapedAttributeName\\s*=\\s*([\"'])(.*?)\\1",
    caseSensitive: false,
    dotAll: true,
  ).firstMatch(tag);
  if (quoted != null) return quoted.group(2);
  final unquoted = RegExp(
    '$escapedAttributeName\\s*=\\s*([^\\s>]+)',
    caseSensitive: false,
  ).firstMatch(tag);
  return unquoted?.group(1);
}

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
    WorkbenchProject? initialProject,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool embedded = false,
  }) : _backend = backend,
       _initialProject = initialProject,
       _projectId = projectId,
       _displayId = displayId,
       _returnTo = returnTo,
       _embedded = embedded;

  final WorkbenchProjectBackend? _backend;
  final WorkbenchProject? _initialProject;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _embedded;

  @override
  State<WorkbenchHtmlDisplayPage> createState() =>
      _WorkbenchHtmlDisplayPageState();
}

class _WorkbenchHtmlDisplayPageState extends State<WorkbenchHtmlDisplayPage>
    with AutomaticKeepAliveClientMixin {
  late final _HtmlProjectLoader _loader;
  InAppWebViewController? _webController;
  StreamSubscription<Map<String, dynamic>>? _updateSub;
  Timer? _refreshDebounce;

  bool _webViewLoading = false;
  String? _errorMessage;
  double? _pendingScrollRestore;
  String? _loadedHtmlSignature;
  String? _currentHtmlFile;
  Uri? _currentHtmlUri;
  bool? _wideCanvasSettingsApplied;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _loader = _HtmlProjectLoader(
      backend: widget._backend ?? NativeWorkbenchProjectBackend(),
      initialProject: widget._initialProject,
      projectId: widget._projectId ?? widget._initialProject?.projectId,
    );
    unawaited(_initialize());
    _updateSub = AssistsMessageService.workbenchProjectUpdatedStream.listen(
      _onProjectUpdated,
    );
    WorkbenchHtmlHitTestBridge.register(_hitTestAtPoint);
  }

  Future<void> _initialize() async {
    await _loader.initialize();
    if (!mounted) return;
    setState(() {});
    unawaited(_loadHtml());
    // If data came from cache, background-refresh and reload only if HTML changed.
    if (_loader.servedFromCache) {
      unawaited(
        _loader.backgroundRefresh(
          onHtmlChanged: () {
            if (mounted) unawaited(_reloadHtmlPreservingScroll());
          },
          onProjectChanged: () {
            if (!mounted) return;
            setState(() {});
            unawaited(_dispatchProjectUpdate());
          },
        ),
      );
    }
  }

  void _onProjectUpdated(Map<String, dynamic> event) {
    final id = event['projectId'] as String?;
    if (id == null || id != (_loader.project?.projectId ?? widget._projectId)) {
      return;
    }

    // Evict cache on structural changes so the next mount fetches fresh data.
    final reason = event['reason']?.toString() ?? '';
    if (reason == 'project_deleted' || reason == 'project_deactivated') {
      _HtmlProjectLoader.invalidateCache(id);
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
        unawaited(_dispatchProjectUpdate(payload: payload));
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
          unawaited(_dispatchProjectUpdate(payload: payload));
        }),
      );
    });
  }

  @override
  void dispose() {
    WorkbenchHtmlHitTestBridge.unregister();
    _refreshDebounce?.cancel();
    _updateSub?.cancel();
    _loader.dispose();
    super.dispose();
  }

  // ── DOM hit-test ───────────────────────────────────────────────────────────

  /// Called by [WorkbenchHtmlHitTestBridge] when the annotation overlay needs
  /// to identify which HTML element is at a given canvas point.
  Future<WorkbenchHitElement?> _hitTestAtPoint(Offset point) async {
    final controller = _webController;
    if (controller == null) return null;
    final x = point.dx.toStringAsFixed(1);
    final y = point.dy.toStringAsFixed(1);
    const js = r"""
(function(x, y) {
  try {
    var el = document.elementFromPoint(x, y);
    if (!el || el === document.body || el === document.documentElement) return null;
    // Walk up to find the nearest ancestor with data-oob-id.
    var target = el;
    while (target && target !== document.body) {
      if (target.getAttribute && target.getAttribute('data-oob-id')) break;
      target = target.parentElement;
    }
    if (!target || target === document.body) target = el;
    var oobId = (target.getAttribute && target.getAttribute('data-oob-id')) || '';
    var text = ((target.innerText || target.textContent || '')).trim().slice(0, 200);
    var cls = typeof target.className === 'string' ? target.className.trim() : '';
    var outer = (target.outerHTML || '').slice(0, 600);
    return JSON.stringify({
      oobId: oobId,
      tagName: target.tagName || '',
      text: text,
      className: cls,
      outerHtml: outer
    });
  } catch(e) { return null; }
})(X_COORD, Y_COORD)
""";
    final script = js.replaceAll('X_COORD', x).replaceAll('Y_COORD', y);
    try {
      final result = await controller.evaluateJavascript(source: script);
      if (result == null) return null;
      final raw = result is String ? jsonDecode(result) : result;
      if (raw is! Map) return null;
      return WorkbenchHitElement(
        oobId: raw['oobId']?.toString() ?? '',
        tagName: raw['tagName']?.toString() ?? '',
        text: raw['text']?.toString() ?? '',
        className: raw['className']?.toString() ?? '',
        outerHtml: raw['outerHtml']?.toString() ?? '',
      );
    } catch (_) {
      return null;
    }
  }

  // ── WebView setup ──────────────────────────────────────────────────────────

  void _onWebViewCreated(InAppWebViewController controller) {
    _webController = controller;
    _loadedHtmlSignature = null;

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

    controller.addJavaScriptHandler(
      handlerName: 'oobOpenTaskLog',
      callback: (args) {
        final conversationId = args.isNotEmpty ? args[0]?.toString() ?? '' : '';
        if (conversationId.isNotEmpty) {
          GoRouterManager.push(
            '/task/run_log_timeline',
            extra: {'runId': conversationId, 'title': 'Task Log'},
          );
        }
        return {'success': conversationId.isNotEmpty};
      },
    );

    unawaited(_loadHtml());
  }

  Future<void> _reloadHtmlPreservingScroll() async {
    if (!mounted) return;
    final scrollY = await _safeEvaluateJavascript('window.scrollY || 0');
    _pendingScrollRestore = scrollY != null
        ? double.tryParse(scrollY.toString())
        : null;
    await _loader.refresh();
    if (mounted) unawaited(_loadHtml(force: true));
  }

  Future<void> _loadHtml({
    bool force = false,
    String? relativeFile,
    Uri? requestedUri,
  }) async {
    final controller = _webController;
    final project = _loader.project;
    if (controller == null || project == null) return;

    final html = project.frontendHtml;
    final entryFile = html['entryFile']?.toString().trim() ?? 'index.html';
    final requestedFile =
        _normalizeProjectHtmlPath(relativeFile) ??
        _currentHtmlFile ??
        _normalizeProjectHtmlPath(entryFile) ??
        'index.html';
    final targetFile = _hasHtmlDocument(html, requestedFile)
        ? requestedFile
        : (_normalizeProjectHtmlPath(entryFile) ?? 'index.html');
    final sources = html['sources'];
    final signature = _htmlLoadSignature(html, targetFile, requestedUri);
    if (!force && _loadedHtmlSignature == signature) return;

    if (mounted) {
      setState(() {
        _webViewLoading = true;
        _errorMessage = null;
      });
    }

    try {
      final target = _projectHtmlFile(html, targetFile);
      if (target != null && target.existsSync()) {
        final root = _projectHtmlRoot(html);
        final historyUri = _historyUriForFile(target, requestedUri);
        final htmlText = await target.readAsString();
        await _applyViewportModeForHtml(controller, htmlText);
        await controller.loadData(
          data: _htmlWithBridgeBootstrap(htmlText),
          mimeType: 'text/html',
          encoding: 'utf-8',
          baseUrl: WebUri(target.parent.uri.toString()),
          historyUrl: WebUri(historyUri.toString()),
          allowingReadAccessTo: root == null
              ? null
              : WebUri(root.uri.toString()),
        );
        _loadedHtmlSignature = signature;
        _currentHtmlFile = targetFile;
        _currentHtmlUri = historyUri;
        return;
      }
      if (sources is Map && sources[targetFile] != null) {
        final htmlText = sources[targetFile].toString();
        final historyUri = _sourceFallbackUri(
          project.projectId,
          targetFile,
          requestedUri,
        );
        await _applyViewportModeForHtml(controller, htmlText);
        await controller.loadData(
          data: _htmlWithBridgeBootstrap(htmlText),
          mimeType: 'text/html',
          encoding: 'utf-8',
          baseUrl: WebUri(
            _sourceFallbackBaseUri(project.projectId, targetFile),
          ),
          historyUrl: WebUri(historyUri.toString()),
        );
        _loadedHtmlSignature = signature;
        _currentHtmlFile = targetFile;
        _currentHtmlUri = historyUri;
        return;
      }
      if (mounted) {
        setState(() {
          _webViewLoading = false;
          _errorMessage = _t('没有可展示的 HTML 产物', 'No HTML output to display');
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

  Future<void> _applyViewportModeForHtml(
    InAppWebViewController controller,
    String htmlText,
  ) async {
    final wideCanvas = workbenchHtmlUsesFixedCanvasViewport(htmlText);
    if (_wideCanvasSettingsApplied == wideCanvas) return;
    _wideCanvasSettingsApplied = wideCanvas;
    await controller.setSettings(settings: _htmlWebViewSettings(wideCanvas));
  }

  String _htmlLoadSignature(
    Map<String, Object?> html,
    String targetFile,
    Uri? requestedUri,
  ) {
    final entryPath = html['entryPath']?.toString().trim() ?? '';
    final entryFile = html['entryFile']?.toString().trim() ?? 'index.html';
    final sources = html['sources'];
    final sourceSignature = sources is Map
        ? sources.entries
              .map((entry) => '${entry.key}:${entry.value}')
              .join('|')
        : '';
    final pathStamp = entryPath.isEmpty
        ? ''
        : File(entryPath).existsSync()
        ? File(entryPath).lastModifiedSync().millisecondsSinceEpoch.toString()
        : '0';
    final query = requestedUri?.query ?? _currentHtmlUri?.query ?? '';
    final fragment = requestedUri?.fragment ?? _currentHtmlUri?.fragment ?? '';
    return '$entryPath#$pathStamp#$entryFile#$targetFile#$query#$fragment#$sourceSignature';
  }

  Future<NavigationActionPolicy> _handleProjectNavigation(
    NavigationAction action,
  ) async {
    final uri = action.request.url;
    if (uri == null) return NavigationActionPolicy.CANCEL;

    final scheme = uri.scheme.toLowerCase();
    if (scheme == 'about' || scheme == 'data') {
      return NavigationActionPolicy.ALLOW;
    }

    if (!action.isForMainFrame) {
      return _isAllowedProjectSubresource(uri)
          ? NavigationActionPolicy.ALLOW
          : NavigationActionPolicy.CANCEL;
    }

    final targetFile = _projectHtmlTargetFor(uri);
    if (targetFile != null) {
      if (_isSameDocumentHashNavigation(targetFile, uri)) {
        return NavigationActionPolicy.ALLOW;
      }
      await _loadHtml(force: true, relativeFile: targetFile, requestedUri: uri);
      return NavigationActionPolicy.CANCEL;
    }

    return NavigationActionPolicy.CANCEL;
  }

  String? _projectHtmlTargetFor(Uri uri) {
    final project = _loader.project;
    if (project == null) return null;
    final html = project.frontendHtml;
    final relativePath = switch (uri.scheme.toLowerCase()) {
      'file' => _relativePathForProjectFileUri(html, uri),
      'https' || 'http' => _relativePathForSourceFallbackUri(project, uri),
      '' => _relativePathForCurrentPage(uri),
      _ => null,
    };
    final normalized = _normalizeProjectHtmlPath(relativePath);
    if (normalized == null || !_isHtmlDocumentPath(normalized)) return null;
    if (!_hasHtmlDocument(html, normalized)) return null;
    return normalized;
  }

  bool _isAllowedProjectSubresource(Uri uri) {
    final project = _loader.project;
    if (project == null) return false;
    final html = project.frontendHtml;
    if (uri.scheme.toLowerCase() == 'file') {
      return _relativePathForProjectFileUri(html, uri) != null;
    }
    if (uri.scheme.toLowerCase() == 'https' ||
        uri.scheme.toLowerCase() == 'http') {
      return _relativePathForSourceFallbackUri(project, uri) != null;
    }
    return uri.scheme == 'about' || uri.scheme == 'data';
  }

  bool _isSameDocumentHashNavigation(String targetFile, Uri uri) {
    final currentFile = _currentHtmlFile;
    if (currentFile == null || targetFile != currentFile) return false;
    if (uri.fragment.isEmpty) return false;
    return uri.query == (_currentHtmlUri?.query ?? '');
  }

  bool _hasHtmlDocument(Map<String, Object?> html, String relativePath) {
    if (!_isHtmlDocumentPath(relativePath)) return false;
    final target = _projectHtmlFile(html, relativePath);
    if (target != null && target.existsSync()) return true;
    final sources = html['sources'];
    return sources is Map && sources[relativePath] != null;
  }

  bool _isHtmlDocumentPath(String relativePath) {
    final lower = relativePath.toLowerCase();
    return lower.endsWith('.html') || lower.endsWith('.htm');
  }

  File? _projectHtmlFile(Map<String, Object?> html, String relativePath) {
    final root = _projectHtmlRoot(html);
    if (root == null) return null;
    final normalized = _normalizeProjectHtmlPath(relativePath);
    if (normalized == null) return null;
    final targetUri = root.uri.resolve(normalized);
    if (!targetUri.path.startsWith(root.uri.path)) return null;
    final target = File.fromUri(targetUri);
    if (!target.existsSync()) return target;
    try {
      final rootPath = root.resolveSymbolicLinksSync();
      final targetPath = target.resolveSymbolicLinksSync();
      final rootPrefix = rootPath.endsWith(Platform.pathSeparator)
          ? rootPath
          : '$rootPath${Platform.pathSeparator}';
      if (targetPath != rootPath && !targetPath.startsWith(rootPrefix)) {
        return null;
      }
    } catch (_) {
      return null;
    }
    return target;
  }

  Directory? _projectHtmlRoot(Map<String, Object?> html) {
    final entryPath = html['entryPath']?.toString().trim() ?? '';
    if (entryPath.isEmpty) return null;
    final entryFile =
        _normalizeProjectHtmlPath(html['entryFile']?.toString()) ??
        'index.html';
    var dir = File(entryPath).absolute.parent;
    final parentDepth = entryFile.split('/').length - 1;
    for (var i = 0; i < parentDepth; i += 1) {
      dir = dir.parent;
    }
    return dir;
  }

  String? _relativePathForProjectFileUri(Map<String, Object?> html, Uri uri) {
    final root = _projectHtmlRoot(html);
    if (root == null || uri.scheme.toLowerCase() != 'file') return null;
    final target = File.fromUri(
      uri.replace(query: null, fragment: null).normalizePath(),
    ).absolute;
    final rootPath = root.absolute.path;
    final rootPrefix = rootPath.endsWith(Platform.pathSeparator)
        ? rootPath
        : '$rootPath${Platform.pathSeparator}';
    if (target.path == rootPath) return null;
    if (target.path != rootPath && !target.path.startsWith(rootPrefix)) {
      return null;
    }
    return target.path
        .substring(rootPrefix.length)
        .replaceAll(Platform.pathSeparator, '/');
  }

  String? _relativePathForSourceFallbackUri(WorkbenchProject project, Uri uri) {
    final scheme = uri.scheme.toLowerCase();
    if ((scheme != 'https' && scheme != 'http') ||
        uri.host != _kSourceFallbackHost) {
      return null;
    }
    final segments = uri.pathSegments;
    if (segments.length < 2 || segments.first != project.projectId) {
      return null;
    }
    return segments.skip(1).join('/');
  }

  String? _relativePathForCurrentPage(Uri uri) {
    final currentFile = _currentHtmlFile;
    if (currentFile == null) return null;
    final currentDir = _parentDirectoryPath(currentFile);
    final base = Uri(path: currentDir.isEmpty ? './' : '$currentDir/');
    final resolved = base.resolveUri(uri);
    return resolved.path;
  }

  String? _normalizeProjectHtmlPath(String? raw) {
    final trimmed = raw?.trim().replaceAll('\\', '/') ?? '';
    if (trimmed.isEmpty) return null;
    final parsed = Uri.tryParse(trimmed);
    var path = parsed?.path ?? trimmed;
    if (path.startsWith('frontend/html/')) {
      path = path.substring('frontend/html/'.length);
    }
    if (path.startsWith('/')) {
      path = path.substring(1);
    }
    path = Uri.decodeComponent(path).replaceAll('\\', '/');
    final parts = path
        .split('/')
        .where((part) => part.isNotEmpty && part != '.')
        .toList(growable: false);
    if (parts.isEmpty || parts.any((part) => part == '..')) return null;
    return parts.join('/');
  }

  Uri _historyUriForFile(File file, Uri? requestedUri) {
    final query = requestedUri?.hasQuery == true ? requestedUri!.query : null;
    final fragment = requestedUri?.fragment.isNotEmpty == true
        ? requestedUri!.fragment
        : null;
    return file.uri.replace(query: query, fragment: fragment);
  }

  Uri _sourceFallbackUri(
    String projectId,
    String relativePath,
    Uri? requestedUri,
  ) {
    final query = requestedUri?.hasQuery == true ? requestedUri!.query : null;
    final fragment = requestedUri?.fragment.isNotEmpty == true
        ? requestedUri!.fragment
        : null;
    return Uri(
      scheme: 'https',
      host: _kSourceFallbackHost,
      pathSegments: [projectId, ...relativePath.split('/')],
      query: query,
      fragment: fragment,
    );
  }

  String _sourceFallbackBaseUri(String projectId, String relativePath) {
    final parent = _parentDirectoryPath(relativePath);
    final parentSegments = parent.isEmpty ? <String>[] : parent.split('/');
    return Uri(
      scheme: 'https',
      host: _kSourceFallbackHost,
      pathSegments: [projectId, ...parentSegments, ''],
    ).toString();
  }

  String _parentDirectoryPath(String relativePath) {
    final slash = relativePath.lastIndexOf('/');
    if (slash <= 0) return '';
    return relativePath.substring(0, slash);
  }

  Future<Object?> _safeEvaluateJavascript(
    String source, {
    InAppWebViewController? controller,
  }) async {
    final target = controller ?? _webController;
    if (target == null) return null;
    try {
      return await target.evaluateJavascript(source: source);
    } catch (error) {
      if (kDebugMode) {
        debugPrint('Workbench HTML WebView evaluateJavascript failed: $error');
      }
      return null;
    }
  }

  Future<void> _dispatchProjectUpdate({Map<String, Object?>? payload}) async {
    final updatePayload = payload ?? _bridgeUpdatePayload(_loader.project);
    if (updatePayload.isEmpty) return;
    final js =
        'window.oob && window.oob.__dispatchUpdate && '
        'window.oob.__dispatchUpdate(${jsonEncode(updatePayload)});';
    await _safeEvaluateJavascript(js);
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
    super.build(context);
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
                        label:
                            _errorMessage ??
                            _t('没有可展示的 HTML 产物', 'No HTML output to display'),
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
                : _t('HTML 展示', 'HTML display'),
            primary: true,
            onBackPressed: _handleBack,
          ),
          body: SafeArea(child: body),
        ),
      ),
    );
  }

  Widget _buildWebView() {
    final palette = context.omniPalette;
    return ColoredBox(
      color: palette.surfacePrimary,
      child: InAppWebView(
        initialSettings: _htmlWebViewSettings(false),
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
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
          Factory<VerticalDragGestureRecognizer>(
            () => VerticalDragGestureRecognizer(),
          ),
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
          await _safeEvaluateJavascript(_kBridgeScript, controller: controller);
          await _safeEvaluateJavascript(
            _kViewportScript,
            controller: controller,
          );
          await _safeEvaluateJavascript(
            _kInspectScript,
            controller: controller,
          );
          final restore = _pendingScrollRestore;
          if (restore != null && restore > 0) {
            _pendingScrollRestore = null;
            await _safeEvaluateJavascript(
              'window.scrollTo(0, $restore)',
              controller: controller,
            );
          }
          if (mounted) setState(() => _webViewLoading = false);
        },
        onReceivedError: (_, request, error) {
          if (request.isForMainFrame == false) {
            if (kDebugMode) {
              debugPrint(
                'Workbench HTML ignored subresource error: '
                '${error.description} (${request.url})',
              );
            }
            return;
          }
          if (mounted) {
            setState(() {
              _webViewLoading = false;
              _errorMessage = error.description;
            });
          }
        },
        shouldOverrideUrlLoading: (_, action) async {
          return _handleProjectNavigation(action);
        },
      ),
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

  String _t(String zh, String en) {
    return Localizations.localeOf(context).languageCode == 'zh' ? zh : en;
  }
}

// ── project loader ─────────────────────────────────────────────────────────

class _HtmlProjectLoader extends ChangeNotifier {
  // Process-level cache: projectId → last-known WorkbenchProject.
  // Survives page dispose/remount so the next load is instant.
  static final Map<String, WorkbenchProject> _cache = {};

  static void invalidateCache(String projectId) {
    _cache.remove(projectId);
  }

  _HtmlProjectLoader({
    required this.backend,
    WorkbenchProject? initialProject,
    String? projectId,
  }) : _project = initialProject,
       _projectId = projectId?.trim() ?? initialProject?.projectId.trim();

  final WorkbenchProjectBackend backend;
  final String? _projectId;
  WorkbenchProject? _project;
  bool _loading = false;
  // True when _project was served from cache (background refresh still pending).
  bool _servedFromCache = false;

  WorkbenchProject? get project => _project;
  bool get loading => _loading;
  bool get servedFromCache => _servedFromCache;

  Future<void> initialize() async {
    final id = _projectId;
    if (id == null || id.isEmpty) return;

    // Cache hit: return immediately, caller can start rendering at once.
    final cached = _cache[id];
    if (cached != null && _project == null) {
      _project = cached;
      _servedFromCache = true;
      return;
    }
    _servedFromCache = false;

    _loading = true;
    notifyListeners();
    try {
      final fresh = await backend.getProject(
        id,
        includeSources: false,
        includeRuntimeState: true,
      );
      _project = _preferProjectWithDisplayData(
        current: _project,
        incoming: fresh,
      );
      if (_project != null) _cache[id] = _project!;
    } catch (error) {
      if (kDebugMode) debugPrint('Workbench HTML project load failed: $error');
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Background-refreshes the project after a cache hit.
  /// Calls [onHtmlChanged] if the HTML content changed, so the caller can reload.
  Future<void> backgroundRefresh({
    required VoidCallback onHtmlChanged,
    required VoidCallback onProjectChanged,
  }) async {
    final id = _project?.projectId ?? _projectId;
    if (id == null || id.isEmpty) return;
    _servedFromCache = false;
    try {
      final fresh = _preferProjectWithDisplayData(
        current: _project,
        incoming: await backend.getProject(
          id,
          includeSources: false,
          includeRuntimeState: true,
        ),
      );
      final htmlChanged =
          _extractHtmlSources(_project) != _extractHtmlSources(fresh);
      _project = fresh;
      _cache[id] = fresh;
      notifyListeners();
      onProjectChanged();
      if (htmlChanged) {
        onHtmlChanged();
      }
    } catch (error) {
      if (kDebugMode) {
        debugPrint('Workbench HTML background refresh failed: $error');
      }
    }
  }

  Future<void> refresh() async {
    final id = _project?.projectId ?? _projectId;
    if (id == null || id.isEmpty) return;
    try {
      _project = _preferProjectWithDisplayData(
        current: _project,
        incoming: await backend.getProject(
          id,
          includeSources: false,
          includeRuntimeState: true,
        ),
      );
      if (_project != null) {
        _cache[id] = _project!;
      }
      notifyListeners();
    } catch (error) {
      if (kDebugMode) {
        debugPrint('Workbench HTML project refresh failed: $error');
      }
    }
  }

  static String _extractHtmlSources(WorkbenchProject? p) {
    if (p == null) return '';
    return p.frontendHtml.entries.map((e) => '${e.key}:${e.value}').join('|');
  }

  void setProject(WorkbenchProject project) {
    _project = _preferProjectWithDisplayData(
      current: _project,
      incoming: project,
    );
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

  WorkbenchProject _preferProjectWithDisplayData({
    required WorkbenchProject? current,
    required WorkbenchProject incoming,
  }) {
    if (current == null) return incoming;
    return incoming.copyWith(
      displays: incoming.displays.isNotEmpty
          ? incoming.displays
          : current.displays,
      pageSpec: incoming.pageSpec.isNotEmpty
          ? incoming.pageSpec
          : current.pageSpec,
      tools: incoming.tools.isNotEmpty ? incoming.tools : current.tools,
      frontendHtml: incoming.frontendHtml.isNotEmpty
          ? incoming.frontendHtml
          : current.frontendHtml,
      frontendFlutter: incoming.frontendFlutter.isNotEmpty
          ? incoming.frontendFlutter
          : current.frontendFlutter,
      frontendMarkdown: incoming.frontendMarkdown.isNotEmpty
          ? incoming.frontendMarkdown
          : current.frontendMarkdown,
    );
  }
}
