import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';

abstract class WorkbenchProjectBackend {
  Future<WorkbenchProject> createProject({
    required String projectId,
    String? name,
    String? prompt,
    String? entityName,
    String? description,
    List<Object?>? initialItems,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
  });

  Future<WorkbenchProject> getProject(
    String projectId, {
    bool includeSources = true,
    bool includeRuntimeState = true,
    bool includeFrontendPayloads = true,
  });

  Future<WorkbenchProject> updateProjectMetadata({
    required String projectId,
    String? name,
    String? shortName,
    String? description,
    List<Map<String, Object?>>? displays,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
    String? prompt,
  });

  Future<List<WorkbenchProject>> listProjects();

  Future<WorkbenchProject?> activateProject(String projectId);

  Future<WorkbenchProject?> getActiveProject();

  Future<void> deactivateProject();

  Future<List<WorkbenchToolSpec>> listApis(String projectId);

  Future<WorkbenchToolRunResult> callApi({
    required String projectId,
    required String apiId,
    required Map<String, Object?> inputs,
  });

  Future<WorkbenchProjectExportResult> exportProject(String projectId);

  Future<WorkbenchProjectDeleteResult> deleteProject(String projectId);

  /// Applies a Workbench control-plane hot update for an existing Project.
  ///
  /// `projectId` identifies the persisted Project package, and `prompt` is the
  /// user request captured from the Xiaowan floating assistant. Implementations
  /// must not register this control action as a Project Tool.
  Future<WorkbenchProjectHotUpdateResult> hotUpdateProject({
    required String projectId,
    required String prompt,
    Map<String, Object?>? frontendContext,
  });

  /// Imports an APK file or Android project directory into a Workbench Project.
  ///
  /// This is a Workbench control-plane operation. It stores the Android asset
  /// under the Project workspace and must not register a Project Tool.
  Future<WorkbenchAndroidIngestResult> ingestAndroidAsset({
    required String projectId,
    required String sourcePath,
    String? sourceKind,
    String? displayName,
  });
}

class NativeWorkbenchProjectBackend implements WorkbenchProjectBackend {
  NativeWorkbenchProjectBackend({
    MethodChannel channel = const MethodChannel(
      'cn.com.omnimind.bot/AssistCoreEvent',
    ),
  }) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<WorkbenchProject> createProject({
    required String projectId,
    String? name,
    String? prompt,
    String? entityName,
    String? description,
    List<Object?>? initialItems,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
  }) async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('workbenchProjectCreate', {
          'projectId': projectId,
          if (name != null && name.trim().isNotEmpty) 'name': name,
          if (prompt != null && prompt.trim().isNotEmpty) 'prompt': prompt,
          if (entityName != null && entityName.trim().isNotEmpty)
            'entityName': entityName,
          if (description != null && description.trim().isNotEmpty)
            'description': description,
          if (initialItems != null && initialItems.isNotEmpty)
            'initialItems': initialItems,
          if (apis != null && apis.isNotEmpty) 'apis': apis,
          if (htmlFiles != null && htmlFiles.isNotEmpty) 'htmlFiles': htmlFiles,
          if (flutterFiles != null && flutterFiles.isNotEmpty)
            'flutterFiles': flutterFiles,
          if (markdownFiles != null && markdownFiles.isNotEmpty)
            'markdownFiles': markdownFiles,
        });
    return WorkbenchProject.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProject> getProject(
    String projectId, {
    bool includeSources = true,
    bool includeRuntimeState = true,
    bool includeFrontendPayloads = true,
  }) async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('workbenchProjectGet', {
          'projectId': projectId,
          'includeSources': includeSources,
          'includeRuntimeState': includeRuntimeState,
          'includeFrontendPayloads': includeFrontendPayloads,
        });
    return WorkbenchProject.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProject> updateProjectMetadata({
    required String projectId,
    String? name,
    String? shortName,
    String? description,
    List<Map<String, Object?>>? displays,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
    String? prompt,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectUpdate',
      {
        'projectId': projectId,
        if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
        if (shortName != null && shortName.trim().isNotEmpty)
          'shortName': shortName.trim(),
        if (description != null && description.trim().isNotEmpty)
          'description': description.trim(),
        if (displays != null && displays.isNotEmpty) 'displays': displays,
        if (apis != null && apis.isNotEmpty) 'apis': apis,
        if (htmlFiles != null && htmlFiles.isNotEmpty) 'htmlFiles': htmlFiles,
        if (flutterFiles != null && flutterFiles.isNotEmpty)
          'flutterFiles': flutterFiles,
        if (markdownFiles != null && markdownFiles.isNotEmpty)
          'markdownFiles': markdownFiles,
        if (prompt != null && prompt.trim().isNotEmpty) 'prompt': prompt.trim(),
      },
    );
    final project = result?['project'];
    return project is Map<dynamic, dynamic>
        ? WorkbenchProject.fromMap(project)
        : WorkbenchProject.fromMap(result ?? const {});
  }

  @override
  Future<List<WorkbenchProject>> listProjects() async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'workbenchProjectList',
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(WorkbenchProject.fromMap)
        .toList(growable: false);
  }

  @override
  Future<WorkbenchProject?> activateProject(String projectId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectActivate',
      {'projectId': projectId},
    );
    final project = result?['project'];
    return project is Map<dynamic, dynamic>
        ? WorkbenchProject.fromMap(project)
        : null;
  }

  @override
  Future<WorkbenchProject?> getActiveProject() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectActiveGet',
      {'compact': true},
    );
    final project = result?['project'];
    return project is Map<dynamic, dynamic>
        ? WorkbenchProject.fromMap(project)
        : null;
  }

  Future<void> setActiveFrontendContext(
    Map<String, Object?> frontendContext,
  ) async {
    try {
      await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'workbenchFrontendContextSet',
        {'context': frontendContext},
      );
    } on PlatformException catch (error) {
      debugPrint('保存工作台前端上下文失败: ${error.message}');
    }
  }

  Future<Map<String, dynamic>?> getActiveFrontendContext() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'workbenchFrontendContextGet',
      );
      if (result == null || result.isEmpty) return null;
      return result.map((key, value) => MapEntry(key.toString(), value));
    } on PlatformException catch (error) {
      debugPrint('读取工作台前端上下文失败: ${error.message}');
      return null;
    }
  }

  @override
  Future<void> deactivateProject() async {
    await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectDeactivate',
    );
  }

  @override
  Future<List<WorkbenchToolSpec>> listApis(String projectId) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'workbenchApiList',
      {'projectId': projectId},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(WorkbenchToolSpec.fromMap)
        .toList(growable: false);
  }

  @override
  Future<WorkbenchToolRunResult> callApi({
    required String projectId,
    required String apiId,
    required Map<String, Object?> inputs,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchApiCall',
      {
        'projectId': projectId,
        'apiId': apiId,
        'inputs': inputs,
        'caller': 'ui',
      },
    );
    return WorkbenchToolRunResult.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProjectExportResult> exportProject(String projectId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectExport',
      {'projectId': projectId},
    );
    return WorkbenchProjectExportResult.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProjectDeleteResult> deleteProject(String projectId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectDelete',
      {'projectId': projectId},
    );
    return WorkbenchProjectDeleteResult.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProjectHotUpdateResult> hotUpdateProject({
    required String projectId,
    required String prompt,
    Map<String, Object?>? frontendContext,
  }) async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('workbenchProjectHotUpdate', {
          'projectId': projectId,
          'prompt': prompt,
          'caller': 'ui',
          if (frontendContext != null && frontendContext.isNotEmpty)
            'frontendContext': frontendContext,
        });
    return WorkbenchProjectHotUpdateResult.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchAndroidIngestResult> ingestAndroidAsset({
    required String projectId,
    required String sourcePath,
    String? sourceKind,
    String? displayName,
  }) async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('workbenchProjectIngestAndroid', {
          'projectId': projectId,
          'sourcePath': sourcePath,
          if (sourceKind != null && sourceKind.trim().isNotEmpty)
            'sourceKind': sourceKind,
          if (displayName != null && displayName.trim().isNotEmpty)
            'displayName': displayName,
          'caller': 'ui',
        });
    return WorkbenchAndroidIngestResult.fromMap(result ?? const {});
  }
}

class WorkbenchProjectService extends ChangeNotifier {
  WorkbenchProjectService({
    required WorkbenchProjectBackend backend,
    required String projectId,
    WorkbenchProject? initialProject,
    bool autoCreateIfMissing = false,
  }) : _backend = backend,
       _projectId = projectId,
       _project = initialProject,
       _autoCreateIfMissing = autoCreateIfMissing;

  factory WorkbenchProjectService.native({
    required String projectId,
    bool autoCreateIfMissing = false,
  }) {
    return WorkbenchProjectService(
      backend: NativeWorkbenchProjectBackend(),
      projectId: projectId,
      autoCreateIfMissing: autoCreateIfMissing,
    );
  }

  final WorkbenchProjectBackend _backend;
  final String _projectId;
  final bool _autoCreateIfMissing;
  WorkbenchProject? _project;
  bool _loading = false;
  String? _errorMessage;

  WorkbenchProject? get project => _project;
  bool get loading => _loading;
  String? get errorMessage => _errorMessage;

  Future<void> initialize() async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final project = await _loadOrCreateProject();
      _project = await _projectWithLegacyToolFallback(project);
    } catch (error) {
      _errorMessage = error.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject> _loadOrCreateProject() async {
    try {
      return await _backend.getProject(_projectId);
    } on PlatformException catch (error) {
      final message = error.message ?? '';
      if (!message.contains('not found')) {
        rethrow;
      }
      if (!_autoCreateIfMissing) {
        rethrow;
      }
    }
    return _backend.createProject(projectId: _projectId);
  }

  Future<void> refresh() async {
    final current = _project;
    if (current == null) {
      await initialize();
      return;
    }
    try {
      final latest = await _backend.getProject(current.projectId);
      _project = await _projectWithLegacyToolFallback(latest);
      _errorMessage = null;
      notifyListeners();
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> updateProjectMetadata(
    WorkbenchProject project, {
    String? name,
    String? shortName,
    String? description,
    List<Map<String, Object?>>? displays,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
    String? prompt,
  }) async {
    final normalizedName = name?.trim();
    final normalizedShortName = shortName?.trim();
    final normalizedDescription = description?.trim();
    final normalizedPrompt = prompt?.trim();
    if ((normalizedName == null || normalizedName.isEmpty) &&
        (normalizedShortName == null || normalizedShortName.isEmpty) &&
        (normalizedDescription == null || normalizedDescription.isEmpty) &&
        (normalizedPrompt == null || normalizedPrompt.isEmpty) &&
        (displays == null || displays.isEmpty) &&
        (apis == null || apis.isEmpty) &&
        (htmlFiles == null || htmlFiles.isEmpty) &&
        (flutterFiles == null || flutterFiles.isEmpty) &&
        (markdownFiles == null || markdownFiles.isEmpty)) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final updated = await _backend.updateProjectMetadata(
        projectId: project.projectId,
        name: normalizedName,
        shortName: normalizedShortName,
        description: normalizedDescription,
        displays: displays,
        apis: apis,
        htmlFiles: htmlFiles,
        flutterFiles: flutterFiles,
        markdownFiles: markdownFiles,
        prompt: normalizedPrompt,
      );
      _project = await _projectWithLegacyToolFallback(updated);
      _errorMessage = null;
      return _project;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchToolRunResult> runTool(
    String apiId,
    Map<String, Object?> inputs,
  ) async {
    final current = _project;
    if (current == null) {
      await initialize();
    }
    final project = _project;
    if (project == null) {
      return const WorkbenchToolRunResult(
        toolId: '',
        success: false,
        outputs: {},
        errorCode: 'PROJECT_NOT_LOADED',
        errorMessage: 'Workbench Project is not loaded.',
      );
    }
    final result = await _backend.callApi(
      projectId: project.projectId,
      apiId: apiId,
      inputs: inputs,
    );
    if (result.project != null) {
      _project = result.project!;
    } else if (result.success) {
      await refresh();
      return result;
    }
    _errorMessage = result.success ? null : result.errorMessage;
    notifyListeners();
    return result;
  }

  /// Sends the generated frontend assistant prompt to the native Workbench.
  ///
  /// The prompt updates the current Project through the control API and returns
  /// a refreshed Project payload when native execution succeeds.
  Future<WorkbenchProjectHotUpdateResult> applyHotUpdate(
    String prompt, {
    Map<String, Object?>? frontendContext,
  }) async {
    final result = await _backend.hotUpdateProject(
      projectId: _project?.projectId ?? _projectId,
      prompt: prompt,
      frontendContext: frontendContext,
    );
    if (result.project != null) {
      _project = result.project!;
    } else if (result.success) {
      await refresh();
      return result;
    }
    _errorMessage = null;
    notifyListeners();
    return result;
  }

  Future<WorkbenchProject> _projectWithLegacyToolFallback(
    WorkbenchProject project,
  ) async {
    if (project.tools.isNotEmpty || project.projectId.trim().isEmpty) {
      return project;
    }
    final apis = await _backend.listApis(project.projectId);
    return apis.isEmpty ? project : project.copyWith(tools: apis);
  }
}

class WorkbenchProjectModeService extends ChangeNotifier {
  WorkbenchProjectModeService({required WorkbenchProjectBackend backend})
    : _backend = backend,
      _projects = _cachedProjects,
      _activeProject = _cachedActiveProject;

  factory WorkbenchProjectModeService.native() {
    return WorkbenchProjectModeService(
      backend: NativeWorkbenchProjectBackend(),
    );
  }

  final WorkbenchProjectBackend _backend;
  static List<WorkbenchProject> _cachedProjects = const [];
  static WorkbenchProject? _cachedActiveProject;

  List<WorkbenchProject> _projects;
  WorkbenchProject? _activeProject;
  bool _loading = false;
  String? _errorMessage;

  List<WorkbenchProject> get projects => _projects;
  WorkbenchProject? get activeProject => _activeProject;
  bool get loading => _loading;
  String? get errorMessage => _errorMessage;

  Future<void> refresh() async {
    final showLoading = _projects.isEmpty && _activeProject == null;
    _loading = showLoading;
    _errorMessage = null;
    if (showLoading) {
      notifyListeners();
    }
    try {
      final results = await Future.wait<Object?>([
        _backend.listProjects(),
        _backend.getActiveProject(),
      ]);
      _projects = _mergeProjectIndexes(results[0] as List<WorkbenchProject>);
      _activeProject = _mergeProjectIndex(results[1] as WorkbenchProject?);
      _rememberSnapshot();
    } catch (error) {
      // Only surface the error when there is no existing data to show.
      // Transient native failures (binder timeout, partial init) must not flash
      // a red card over an otherwise-working workspace.
      if (_projects.isEmpty && _activeProject == null) {
        _errorMessage = error.toString();
      }
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> loadProjectDetails(String projectId) async {
    final normalizedProjectId = projectId.trim();
    if (normalizedProjectId.isEmpty) return null;
    _errorMessage = null;
    try {
      final project = await _backend.getProject(
        normalizedProjectId,
        includeSources: false,
        includeRuntimeState: false,
        includeFrontendPayloads: false,
      );
      _upsertProject(project);
      if (_activeProject?.projectId == project.projectId) {
        _activeProject = project;
      }
      _rememberSnapshot();
      return project;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      notifyListeners();
    }
  }

  // Lightweight refresh: only updates the active project, skips full list reload.
  // Used for data-only events (api_call) to avoid constant list rebuilds.
  Future<void> refreshActiveProject() async {
    try {
      final updated = await _backend.getActiveProject();
      if (updated?.projectId == _activeProject?.projectId) {
        _activeProject = _mergeProjectIndex(updated);
        _rememberSnapshot();
        notifyListeners();
      }
    } catch (_) {}
  }

  Future<WorkbenchProject?> activateProject(WorkbenchProject project) async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final active = await _backend.activateProject(project.projectId);
      _activeProject = _mergeProjectIndex(active ?? project);
      _projects = _projects
          .map(
            (item) => item.projectId == _activeProject!.projectId
                ? _activeProject!
                : item,
          )
          .toList(growable: false);
      _rememberSnapshot();
      return _activeProject;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> updateProjectMetadata(
    WorkbenchProject project, {
    String? name,
    String? shortName,
    String? description,
    List<Map<String, Object?>>? displays,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? htmlFiles,
    List<Map<String, Object?>>? flutterFiles,
    List<Map<String, Object?>>? markdownFiles,
    String? prompt,
  }) async {
    final normalizedName = name?.trim();
    final normalizedShortName = shortName?.trim();
    final normalizedDescription = description?.trim();
    final normalizedPrompt = prompt?.trim();
    if ((normalizedName == null || normalizedName.isEmpty) &&
        (normalizedShortName == null || normalizedShortName.isEmpty) &&
        (normalizedDescription == null || normalizedDescription.isEmpty) &&
        (normalizedPrompt == null || normalizedPrompt.isEmpty) &&
        (displays == null || displays.isEmpty) &&
        (apis == null || apis.isEmpty) &&
        (htmlFiles == null || htmlFiles.isEmpty) &&
        (flutterFiles == null || flutterFiles.isEmpty) &&
        (markdownFiles == null || markdownFiles.isEmpty)) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final updated = await _backend.updateProjectMetadata(
        projectId: project.projectId,
        name: normalizedName,
        shortName: normalizedShortName,
        description: normalizedDescription,
        displays: displays,
        apis: apis,
        htmlFiles: htmlFiles,
        flutterFiles: flutterFiles,
        markdownFiles: markdownFiles,
        prompt: normalizedPrompt,
      );
      _projects = _projects
          .map((item) => item.projectId == updated.projectId ? updated : item)
          .toList(growable: false);
      if (_activeProject?.projectId == updated.projectId) {
        _activeProject = updated;
      }
      _rememberSnapshot();
      return updated;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> getActiveProject() async {
    try {
      _activeProject = _mergeProjectIndex(await _backend.getActiveProject());
      _rememberSnapshot();
      notifyListeners();
      return _activeProject;
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
      return null;
    }
  }

  Future<bool> deactivateProject() async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      await _backend.deactivateProject();
      _activeProject = null;
      _rememberSnapshot();
      return true;
    } catch (error) {
      _errorMessage = error.toString();
      return false;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Creates a generic Project through the Workbench control API.
  ///
  /// `prompt` is the user's original requirement for future iteration,
  /// `entityName` optionally names the business object the display manages,
  /// and `initialItems` seeds persisted Project state without writing files
  /// directly from Flutter.
  Future<WorkbenchProject?> createProjectFromPrompt(
    String prompt, {
    required String projectId,
    required String name,
    String? entityName,
    String? description,
    List<Object?> initialItems = const [],
    List<Map<String, Object?>> apis = const [],
    List<Map<String, Object?>> htmlFiles = const [],
    List<Map<String, Object?>> flutterFiles = const [],
    List<Map<String, Object?>> markdownFiles = const [],
  }) async {
    final normalizedPrompt = prompt.trim();
    final normalizedProjectId = projectId.trim();
    final normalizedEntityName = entityName?.trim();
    if (normalizedPrompt.isEmpty || normalizedProjectId.isEmpty) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final project = await _backend.createProject(
        projectId: normalizedProjectId,
        name: name,
        prompt: normalizedPrompt,
        entityName: normalizedEntityName,
        description: description,
        initialItems: initialItems,
        apis: apis,
        htmlFiles: htmlFiles,
        flutterFiles: flutterFiles,
        markdownFiles: markdownFiles,
      );
      _projects = _mergeProjectIndexes(await _backend.listProjects());
      _upsertProject(project);
      _rememberSnapshot();
      return project;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchToolRunResult> runTool(
    WorkbenchProject project,
    String apiId,
    Map<String, Object?> inputs,
  ) async {
    final result = await _backend.callApi(
      projectId: project.projectId,
      apiId: apiId,
      inputs: inputs,
    );
    if (result.project != null) {
      _upsertProject(result.project!);
    } else if (result.success) {
      _projects = _mergeProjectIndexes(await _backend.listProjects());
    }
    _errorMessage = result.success ? null : result.errorMessage;
    _rememberSnapshot();
    notifyListeners();
    return result;
  }

  Future<WorkbenchProjectExportResult?> exportProject(
    WorkbenchProject project,
  ) async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      return await _backend.exportProject(project.projectId);
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProjectDeleteResult?> deleteProject(
    WorkbenchProject project,
  ) async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final result = await _backend.deleteProject(project.projectId);
      if (result.success) {
        _projects = _mergeProjectIndexes(await _backend.listProjects());
        _activeProject = _mergeProjectIndex(await _backend.getActiveProject());
        _rememberSnapshot();
      }
      return result;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Sends a Project-mode assistant prompt through the Workbench control API.
  ///
  /// `project` is the selected Project in the manager page, and `prompt` is the
  /// requested live edit. The local project list is refreshed from the returned
  /// Project payload or from native storage when needed.
  Future<WorkbenchProjectHotUpdateResult?> applyHotUpdate(
    WorkbenchProject project,
    String prompt, {
    Map<String, Object?>? frontendContext,
  }) async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final result = await _backend.hotUpdateProject(
        projectId: project.projectId,
        prompt: prompt,
        frontendContext: frontendContext,
      );
      if (result.project != null) {
        _upsertProject(result.project!);
      } else if (result.success) {
        _projects = _mergeProjectIndexes(await _backend.listProjects());
      }
      _rememberSnapshot();
      return result;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Imports an Android app/project into the selected Workbench Project.
  ///
  /// `sourcePath` can be an Android absolute path or `/workspace/...` shell
  /// path. The Project list is refreshed from the native payload after import.
  Future<WorkbenchAndroidIngestResult?> ingestAndroidAsset(
    WorkbenchProject project,
    String sourcePath, {
    String? sourceKind,
    String? displayName,
  }) async {
    final normalizedSourcePath = sourcePath.trim();
    if (normalizedSourcePath.isEmpty) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final result = await _backend.ingestAndroidAsset(
        projectId: project.projectId,
        sourcePath: normalizedSourcePath,
        sourceKind: sourceKind,
        displayName: displayName,
      );
      if (result.project != null) {
        _upsertProject(result.project!);
      } else if (result.success) {
        _projects = _mergeProjectIndexes(await _backend.listProjects());
      }
      _rememberSnapshot();
      return result;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  void _upsertProject(WorkbenchProject project) {
    var replaced = false;
    _projects = _projects
        .map((item) {
          if (item.projectId != project.projectId) return item;
          replaced = true;
          return project;
        })
        .toList(growable: false);
    if (!replaced) {
      _projects = [project, ..._projects];
    }
    if (_activeProject?.projectId == project.projectId) {
      _activeProject = project;
    }
  }

  List<WorkbenchProject> _mergeProjectIndexes(List<WorkbenchProject> indexes) {
    return indexes
        .map(_mergeProjectIndex)
        .whereType<WorkbenchProject>()
        .toList(growable: false);
  }

  WorkbenchProject? _mergeProjectIndex(WorkbenchProject? index) {
    if (index == null) return null;
    final known = _knownProject(index.projectId);
    if (known == null) return index;
    return known.copyWith(
      name: index.name,
      route: index.route,
      spacePath: index.spacePath,
      pageIds: index.pageIds.isNotEmpty ? index.pageIds : known.pageIds,
      displays: index.displays.isNotEmpty ? index.displays : known.displays,
      tools: index.tools.isNotEmpty ? index.tools : known.tools,
      pageSpec: index.pageSpec.isNotEmpty ? index.pageSpec : known.pageSpec,
    );
  }

  WorkbenchProject? _knownProject(String projectId) {
    for (final project in _projects) {
      if (project.projectId == projectId) return project;
    }
    for (final project in _cachedProjects) {
      if (project.projectId == projectId) return project;
    }
    if (_activeProject?.projectId == projectId) return _activeProject;
    if (_cachedActiveProject?.projectId == projectId) {
      return _cachedActiveProject;
    }
    return null;
  }

  void _rememberSnapshot() {
    _cachedProjects = _projects;
    _cachedActiveProject = _activeProject;
  }
}

class WorkbenchActiveProjectService extends ChangeNotifier {
  WorkbenchActiveProjectService({required WorkbenchProjectBackend backend})
    : _backend = backend;

  factory WorkbenchActiveProjectService.native() {
    return WorkbenchActiveProjectService(
      backend: NativeWorkbenchProjectBackend(),
    );
  }

  final WorkbenchProjectBackend _backend;
  WorkbenchProject? _activeProject;
  bool _loading = false;

  WorkbenchProject? get activeProject => _activeProject;
  bool get loading => _loading;

  Future<void> refresh() async {
    _loading = true;
    notifyListeners();
    try {
      _activeProject = await _backend.getActiveProject();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> deactivate() async {
    await _backend.deactivateProject();
    _activeProject = null;
    notifyListeners();
  }
}
