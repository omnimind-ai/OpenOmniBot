import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';

const String workbenchTodoDefaultProjectId = 'oob-workbench-todo-log';
const String workbenchTodoTemplateId = 'todo_log_demo';

abstract class WorkbenchProjectBackend {
  Future<WorkbenchProject> createProject({
    required String projectId,
    required String templateId,
    String? name,
    String? prompt,
    List<String>? initialTodos,
  });

  Future<WorkbenchProject> getProject(String projectId);

  Future<List<WorkbenchProject>> listProjects();

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
  /// must not register this control action as a Project business API.
  Future<WorkbenchProjectHotUpdateResult> hotUpdateProject({
    required String projectId,
    required String prompt,
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
    required String templateId,
    String? name,
    String? prompt,
    List<String>? initialTodos,
  }) async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('workbenchProjectCreate', {
          'projectId': projectId,
          'templateId': templateId,
          if (name != null && name.trim().isNotEmpty) 'name': name,
          if (prompt != null && prompt.trim().isNotEmpty) 'prompt': prompt,
          if (initialTodos != null && initialTodos.isNotEmpty)
            'initialTodos': initialTodos,
        });
    return WorkbenchProject.fromMap(result ?? const {});
  }

  @override
  Future<WorkbenchProject> getProject(String projectId) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectGet',
      {'projectId': projectId},
    );
    return WorkbenchProject.fromMap(result ?? const {});
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
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'workbenchProjectHotUpdate',
      {'projectId': projectId, 'prompt': prompt, 'caller': 'ui'},
    );
    return WorkbenchProjectHotUpdateResult.fromMap(result ?? const {});
  }
}

class WorkbenchTodoLogService extends ChangeNotifier {
  WorkbenchTodoLogService({
    required WorkbenchProjectBackend backend,
    String projectId = workbenchTodoDefaultProjectId,
    WorkbenchProject? initialProject,
  }) : _backend = backend,
       _projectId = projectId,
       _project = initialProject ?? WorkbenchTodoProjectFactory.create();

  factory WorkbenchTodoLogService.native({String? projectId}) {
    return WorkbenchTodoLogService(
      backend: NativeWorkbenchProjectBackend(),
      projectId: projectId ?? workbenchTodoDefaultProjectId,
    );
  }

  final WorkbenchProjectBackend _backend;
  final String _projectId;
  WorkbenchProject _project;
  bool _loading = false;
  String? _errorMessage;

  WorkbenchProject get project => _project;
  bool get loading => _loading;
  String? get errorMessage => _errorMessage;

  Future<void> initialize() async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final project = await _loadOrCreateProject();
      final apis = await _backend.listApis(project.projectId);
      _project = project.copyWith(tools: apis);
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
    }
    return _backend.createProject(
      projectId: _projectId,
      templateId: workbenchTodoTemplateId,
    );
  }

  Future<void> refresh() async {
    try {
      final latest = await _backend.getProject(_project.projectId);
      final apis = await _backend.listApis(latest.projectId);
      _project = latest.copyWith(tools: apis);
      _errorMessage = null;
      notifyListeners();
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
    }
  }

  Future<WorkbenchToolRunResult> runTool(
    String apiId,
    Map<String, Object?> inputs,
  ) async {
    final result = await _backend.callApi(
      projectId: _project.projectId,
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
  Future<WorkbenchProjectHotUpdateResult> applyHotUpdate(String prompt) async {
    final result = await _backend.hotUpdateProject(
      projectId: _project.projectId,
      prompt: prompt,
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
}

class WorkbenchProjectModeService extends ChangeNotifier {
  WorkbenchProjectModeService({required WorkbenchProjectBackend backend})
    : _backend = backend;

  factory WorkbenchProjectModeService.native() {
    return WorkbenchProjectModeService(
      backend: NativeWorkbenchProjectBackend(),
    );
  }

  final WorkbenchProjectBackend _backend;
  List<WorkbenchProject> _projects = const [];
  bool _loading = false;
  String? _errorMessage;

  List<WorkbenchProject> get projects => _projects;
  bool get loading => _loading;
  String? get errorMessage => _errorMessage;

  Future<void> refresh() async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      _projects = await _backend.listProjects();
    } catch (error) {
      _errorMessage = error.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> createTodoLogProject(String projectId) async {
    final normalizedProjectId = projectId.trim();
    if (normalizedProjectId.isEmpty) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final project = await _backend.createProject(
        projectId: normalizedProjectId,
        templateId: workbenchTodoTemplateId,
      );
      _projects = await _backend.listProjects();
      return project;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<WorkbenchProject?> createTodoLogProjectFromPrompt(
    String prompt, {
    required String name,
    required List<String> initialTodos,
  }) async {
    final normalizedPrompt = prompt.trim();
    if (normalizedPrompt.isEmpty) {
      return null;
    }
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      _projects = await _backend.listProjects();
      final projectId = _nextPromptProjectId();
      final project = await _backend.createProject(
        projectId: projectId,
        templateId: workbenchTodoTemplateId,
        name: name,
        prompt: normalizedPrompt,
      );
      var latestProject = project;
      final addedTodoIds = <String>[];
      for (final title in initialTodos) {
        final trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty) {
          continue;
        }
        final result = await _backend.callApi(
          projectId: project.projectId,
          apiId: WorkbenchTodoToolIds.addTodo,
          inputs: {'title': trimmedTitle},
        );
        if (result.project != null) {
          latestProject = result.project!;
        }
        final todo = result.outputs['todo'];
        if (todo is WorkbenchTodoItem) {
          addedTodoIds.add(todo.id);
        } else if (todo is Map) {
          final todoId = todo['id']?.toString().trim();
          if (todoId != null && todoId.isNotEmpty) {
            addedTodoIds.add(todoId);
          }
        }
      }
      if (addedTodoIds.isNotEmpty) {
        final result = await _backend.callApi(
          projectId: project.projectId,
          apiId: WorkbenchTodoToolIds.finishTodo,
          inputs: {'todo_id': addedTodoIds.first},
        );
        if (result.project != null) {
          latestProject = result.project!;
        }
      }
      _projects = await _backend.listProjects();
      return latestProject;
    } catch (error) {
      _errorMessage = error.toString();
      return null;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  String _nextPromptProjectId() {
    const base = 'oob-workbench-todolist';
    final existing = _projects.map((project) => project.projectId).toSet();
    if (!existing.contains(base)) {
      return base;
    }
    var index = 2;
    while (existing.contains('$base-$index')) {
      index += 1;
    }
    return '$base-$index';
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
      _projects = _projects
          .map(
            (item) => item.projectId == result.project!.projectId
                ? result.project!
                : item,
          )
          .toList(growable: false);
    } else if (result.success) {
      _projects = await _backend.listProjects();
    }
    _errorMessage = result.success ? null : result.errorMessage;
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
        _projects = await _backend.listProjects();
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
    String prompt,
  ) async {
    _loading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      final result = await _backend.hotUpdateProject(
        projectId: project.projectId,
        prompt: prompt,
      );
      if (result.project != null) {
        _projects = _projects
            .map(
              (item) => item.projectId == result.project!.projectId
                  ? result.project!
                  : item,
            )
            .toList(growable: false);
      } else if (result.success) {
        _projects = await _backend.listProjects();
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
}
