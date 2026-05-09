import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'project exposes registered APIs without exposing project create',
    () async {
      final service = _todoLogService();
      await service.initialize();
      final project = service.project;

      expect(project.projectId, 'oob-workbench-todo-log');
      expect(project.spacePath, '/workspace/projects/oob-workbench-todo-log');
      expect(project.pageIds, contains('todo-log-page'));
      expect(project.primaryDisplay.title, 'Todo 日志');
      expect(project.primaryDisplay.shortName, 'TODO');
      expect(
        project.tools.map((tool) => tool.id),
        containsAll([
          WorkbenchTodoToolIds.addTodo,
          WorkbenchTodoToolIds.finishTodo,
        ]),
      );
      expect(
        project.tools.map((tool) => tool.id),
        isNot(contains('workbench_project_create')),
      );
    },
  );

  test(
    'todo APIs add and finish todo items through the service boundary',
    () async {
      final service = _todoLogService();
      await service.initialize();

      final addResult = await service.runTool(WorkbenchTodoToolIds.addTodo, {
        'title': 'Create PageSpec renderer',
      });

      expect(addResult.success, isTrue);
      expect(service.project.openTodos.first.title, 'Create PageSpec renderer');
      expect(service.project.openTodos.first.status, WorkbenchTodoStatus.open);

      final todo = addResult.outputs['todo']! as WorkbenchTodoItem;
      final finishResult = await service.runTool(
        WorkbenchTodoToolIds.finishTodo,
        {'todo_id': todo.id},
      );

      expect(finishResult.success, isTrue);
      expect(
        service.project.finishedTodos.map((item) => item.id),
        contains(todo.id),
      );
      expect(
        service.project.tools
            .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.addTodo)
            .executionCount,
        1,
      );
      expect(
        service.project.tools
            .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.finishTodo)
            .executionCount,
        1,
      );
    },
  );

  test(
    'hot update applies through Workbench control API and refreshes project',
    () async {
      final service = _todoLogService();
      await service.initialize();

      final result = await service.applyHotUpdate('增加 todo：热更新后的任务');

      expect(result.success, isTrue);
      expect(result.appliedActionCount, 1);
      expect(service.project.openTodos.first.title, '增加 todo：热更新后的任务');
      expect(
        service.project.tools
            .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.addTodo)
            .executionCount,
        1,
      );
    },
  );

  test(
    'todo.add rejects empty titles and unknown APIs fail explicitly',
    () async {
      final service = _todoLogService();
      await service.initialize();

      final emptyResult = await service.runTool(WorkbenchTodoToolIds.addTodo, {
        'title': '   ',
      });
      final unknownResult = await service.runTool('todo.archive', {
        'todo_id': 'todo-0',
      });

      expect(emptyResult.success, isFalse);
      expect(emptyResult.errorCode, 'EMPTY_TODO_TITLE');
      expect(unknownResult.success, isFalse);
      expect(unknownResult.errorCode, 'UNKNOWN_TOOL');
    },
  );

  test('native service reads existing project before calling APIs', () async {
    const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          switch (call.method) {
            case 'workbenchProjectGet':
              return _projectPayload();
            case 'workbenchApiList':
              return _apiPayload();
            case 'workbenchApiCall':
              return {
                'success': true,
                'toolId': WorkbenchTodoToolIds.addTodo,
                'outputs': {
                  'todo': {
                    'id': 'todo-native',
                    'title': 'Native call',
                    'status': 'open',
                    'createdAt': DateTime.now().toIso8601String(),
                  },
                },
                'project': _projectPayload(
                  todos: [
                    {
                      'id': 'todo-native',
                      'title': 'Native call',
                      'status': 'open',
                      'createdAt': DateTime.now().toIso8601String(),
                    },
                  ],
                ),
              };
            default:
              fail('Unexpected method ${call.method}');
          }
        });

    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    final service = WorkbenchTodoLogService.native();
    await service.initialize();
    final result = await service.runTool(WorkbenchTodoToolIds.addTodo, {
      'title': 'Native call',
    });

    expect(result.success, isTrue);
    expect(calls, containsAll(['workbenchProjectGet', 'workbenchApiList']));
    expect(calls, isNot(contains('workbenchProjectCreate')));
    expect(calls.last, 'workbenchApiCall');
    expect(service.project.openTodos.first.title, 'Native call');
  });

  test(
    'native service creates project only when get reports missing',
    () async {
      const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            switch (call.method) {
              case 'workbenchProjectGet':
                throw PlatformException(
                  code: 'WORKBENCH_PROJECT_GET_ERROR',
                  message:
                      'Workbench project not found: oob-workbench-todo-log',
                );
              case 'workbenchProjectCreate':
                return _projectPayload();
              case 'workbenchApiList':
                return _apiPayload();
              default:
                fail('Unexpected method ${call.method}');
            }
          });

      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
      });

      final service = WorkbenchTodoLogService.native();
      await service.initialize();

      expect(calls, [
        'workbenchProjectGet',
        'workbenchProjectCreate',
        'workbenchApiList',
      ]);
    },
  );

  test(
    'project mode lists projects and creates isolated project ids',
    () async {
      final service = _projectModeService();

      await service.refresh();
      expect(service.projects, hasLength(1));

      final created = await service.createTodoLogProject(
        'oob-workbench-todo-log-dev',
      );
      final addResult = await service.runTool(
        service.projects.single,
        WorkbenchTodoToolIds.addTodo,
        {'title': 'Track executions'},
      );

      expect(created?.projectId, 'oob-workbench-todo-log-dev');
      expect(service.projects.single.projectId, 'oob-workbench-todo-log-dev');
      expect(addResult.success, isTrue);
      expect(
        service.projects.single.tools
            .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.addTodo)
            .executionCount,
        1,
      );

      final export = await service.exportProject(service.projects.single);
      expect(export?.success, isTrue);
      expect(export?.exportShellPath, contains('/workspace/projects/exports/'));
      expect(export?.includedFiles, contains('manifest.json'));
      expect(export?.includedFiles, contains('project/backend/api_spec.json'));

      final deleted = await service.deleteProject(service.projects.single);
      expect(deleted?.success, isTrue);
      expect(deleted?.remainingProjectCount, 0);
      expect(service.projects, isEmpty);
    },
  );

  test('project mode creates prompt project with initial todo split', () async {
    final service = _projectModeService();

    final created = await service.createTodoLogProjectFromPrompt(
      '我想创建一个简单的todolist管理系统，要求可以增加todo，归档todo',
      name: 'Todo List Workbench',
      initialTodos: const ['验证可以增加 todo', '验证可以归档 todo'],
    );

    expect(created?.projectId, 'oob-workbench-todolist');
    expect(created?.name, 'Todo List Workbench');
    expect(
      created?.openTodos.map((todo) => todo.title),
      contains('验证可以归档 todo'),
    );
    expect(
      created?.finishedTodos.map((todo) => todo.title),
      contains('验证可以增加 todo'),
    );
    expect(
      created?.tools
          .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.addTodo)
          .executionCount,
      2,
    );
    expect(
      created?.tools
          .firstWhere((tool) => tool.id == WorkbenchTodoToolIds.finishTodo)
          .executionCount,
      1,
    );

    final second = await service.createTodoLogProjectFromPrompt(
      '再创建一个可以增加 todo 和归档 todo 的系统',
      name: 'Todo List Workbench',
      initialTodos: const ['第二个项目的 todo'],
    );

    expect(second?.projectId, 'oob-workbench-todolist-2');
  });

  test(
    'native service exports and deletes project through MethodChannel',
    () async {
      const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            switch (call.method) {
              case 'workbenchProjectList':
                return [_projectPayload()];
              case 'workbenchProjectExport':
                return {
                  'success': true,
                  'projectId': workbenchTodoDefaultProjectId,
                  'packageName': 'oob-workbench-todo-log-export.zip',
                  'exportPath': '/data/workspace/projects/exports/export.zip',
                  'exportShellPath': '/workspace/projects/exports/export.zip',
                  'includedFiles': [
                    'manifest.json',
                    'project/README.md',
                    'project/frontend/page_spec.json',
                    'project/backend/api_spec.json',
                  ],
                };
              case 'workbenchProjectDelete':
                return {
                  'success': true,
                  'projectId': workbenchTodoDefaultProjectId,
                  'projectPath':
                      '/data/workspace/projects/$workbenchTodoDefaultProjectId',
                  'spacePath':
                      '/workspace/projects/$workbenchTodoDefaultProjectId',
                  'remainingProjectCount': 0,
                };
              default:
                fail('Unexpected method ${call.method}');
            }
          });

      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
      });

      final service = WorkbenchProjectModeService.native();
      await service.refresh();
      final export = await service.exportProject(service.projects.single);
      final deleted = await service.deleteProject(service.projects.single);

      expect(calls, [
        'workbenchProjectList',
        'workbenchProjectExport',
        'workbenchProjectDelete',
        'workbenchProjectList',
      ]);
      expect(export?.success, isTrue);
      expect(export?.displayPath, '/workspace/projects/exports/export.zip');
      expect(
        export?.includedFiles,
        contains('project/frontend/page_spec.json'),
      );
      expect(deleted?.success, isTrue);
      expect(deleted?.remainingProjectCount, 0);
    },
  );

  test(
    'native service hot updates project through MethodChannel control API',
    () async {
      const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            switch (call.method) {
              case 'workbenchProjectGet':
                return _projectPayload();
              case 'workbenchApiList':
                return _apiPayload();
              case 'workbenchProjectHotUpdate':
                return {
                  'success': true,
                  'projectId': workbenchTodoDefaultProjectId,
                  'prompt': call.arguments['prompt'],
                  'appliedActions': [
                    {'apiId': WorkbenchTodoToolIds.addTodo, 'success': true},
                  ],
                  'hotUpdateLogPath':
                      '/workspace/projects/$workbenchTodoDefaultProjectId/logs/hot_updates.jsonl',
                  'project': _projectPayload(
                    todos: [
                      {
                        'id': 'todo-hot-update',
                        'title': 'Hot updated',
                        'status': 'open',
                        'createdAt': DateTime.now().toIso8601String(),
                      },
                    ],
                  ),
                };
              default:
                fail('Unexpected method ${call.method}');
            }
          });

      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
      });

      final service = WorkbenchTodoLogService.native();
      await service.initialize();
      final result = await service.applyHotUpdate('Hot updated');

      expect(calls, [
        'workbenchProjectGet',
        'workbenchApiList',
        'workbenchProjectHotUpdate',
      ]);
      expect(result.success, isTrue);
      expect(result.hotUpdateLogPath, contains('hot_updates.jsonl'));
      expect(service.project.openTodos.first.title, 'Hot updated');
    },
  );

  test('project mode ingests Android asset through control API', () async {
    final service = _projectModeService();
    await service.refresh();

    final result = await service.ingestAndroidAsset(
      service.projects.single,
      '/workspace/apps/demo.apk',
    );

    expect(result?.success, isTrue);
    expect(result?.asset?.sourceKind, 'apk');
    expect(result?.asset?.displayPath, contains('/workspace/projects/'));
    expect(service.projects.single.androidAssets, hasLength(1));
    expect(
      service.projects.single.androidAssets.single.displayName,
      'demo.apk',
    );
    expect(
      service.projects.single.tools.map((tool) => tool.id),
      isNot(contains('workbench_project_ingest_android')),
    );
  });

  test(
    'native service ingests Android asset through MethodChannel control API',
    () async {
      const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            switch (call.method) {
              case 'workbenchProjectList':
                return [_projectPayload()];
              case 'workbenchProjectIngestAndroid':
                expect(
                  call.arguments['sourcePath'],
                  '/workspace/apps/demo.apk',
                );
                return {
                  'success': true,
                  'projectId': workbenchTodoDefaultProjectId,
                  'asset': _androidAssetPayload(),
                  'androidManifestPath':
                      '/workspace/projects/$workbenchTodoDefaultProjectId/android/manifest.json',
                  'androidIngestLogPath':
                      '/workspace/projects/$workbenchTodoDefaultProjectId/logs/android_ingest.jsonl',
                  'project': _projectPayload(
                    androidAssets: [_androidAssetPayload()],
                  ),
                };
              default:
                fail('Unexpected method ${call.method}');
            }
          });

      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
      });

      final service = WorkbenchProjectModeService.native();
      await service.refresh();
      final result = await service.ingestAndroidAsset(
        service.projects.single,
        '/workspace/apps/demo.apk',
      );

      expect(calls, ['workbenchProjectList', 'workbenchProjectIngestAndroid']);
      expect(result?.success, isTrue);
      expect(result?.androidManifestPath, contains('android/manifest.json'));
      expect(service.projects.single.androidAssets.single.sourceKind, 'apk');
    },
  );
}

WorkbenchTodoLogService _todoLogService() {
  final project = WorkbenchTodoProjectFactory.create();
  return WorkbenchTodoLogService(
    backend: _TestWorkbenchProjectBackend(project: project),
    initialProject: project,
  );
}

WorkbenchProjectModeService _projectModeService() {
  return WorkbenchProjectModeService(backend: _TestWorkbenchProjectBackend());
}

class _TestWorkbenchProjectBackend implements WorkbenchProjectBackend {
  _TestWorkbenchProjectBackend({WorkbenchProject? project})
    : _project = project ?? WorkbenchTodoProjectFactory.create(),
      _todoSequence = project?.todos.length ?? 0;

  WorkbenchProject _project;
  int _todoSequence;
  final Map<String, int> _apiCallCounts = {};
  bool _hasProject = true;

  @override
  Future<WorkbenchProject> createProject({
    required String projectId,
    required String templateId,
    String? name,
    String? prompt,
    List<String>? initialTodos,
  }) async {
    _hasProject = true;
    final todos = initialTodos == null || initialTodos.isEmpty
        ? _project.todos
        : initialTodos
              .map(
                (title) => WorkbenchTodoItem(
                  id: 'todo-initial-${_todoSequence++}',
                  title: title,
                  status: WorkbenchTodoStatus.open,
                  createdAt: DateTime.now(),
                ),
              )
              .toList(growable: false);
    _project = _project.copyWith(
      projectId: projectId,
      templateId: templateId,
      name: name?.trim().isNotEmpty == true ? name!.trim() : _project.name,
      todos: todos,
    );
    return _project;
  }

  @override
  Future<WorkbenchProject> getProject(String projectId) async => _project;

  @override
  Future<List<WorkbenchProject>> listProjects() async =>
      _hasProject ? [_project] : const [];

  @override
  Future<List<WorkbenchToolSpec>> listApis(String projectId) async {
    return _project.tools;
  }

  @override
  Future<WorkbenchToolRunResult> callApi({
    required String projectId,
    required String apiId,
    required Map<String, Object?> inputs,
  }) async {
    if (_project.tools.any((tool) => tool.id == apiId)) {
      _apiCallCounts[apiId] = (_apiCallCounts[apiId] ?? 0) + 1;
      _project = _project.copyWith(tools: _toolsWithExecutionCounts());
    }
    switch (apiId) {
      case WorkbenchTodoToolIds.addTodo:
        final title = (inputs['title'] ?? '').toString().trim();
        if (title.isEmpty) {
          return WorkbenchToolRunResult(
            toolId: WorkbenchTodoToolIds.addTodo,
            success: false,
            outputs: const {},
            project: _project,
            errorCode: 'EMPTY_TODO_TITLE',
            errorMessage: 'Todo title is required.',
          );
        }
        final todo = WorkbenchTodoItem(
          id: 'todo-${_todoSequence++}',
          title: title,
          status: WorkbenchTodoStatus.open,
          createdAt: DateTime.now(),
        );
        _project = _project.copyWith(todos: [todo, ..._project.todos]);
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: true,
          outputs: {'todo': todo},
          project: _project,
        );
      case WorkbenchTodoToolIds.finishTodo:
        final todoId = (inputs['todo_id'] ?? inputs['todoId'] ?? '')
            .toString()
            .trim();
        final index = _project.todos.indexWhere((todo) => todo.id == todoId);
        if (index < 0) {
          return WorkbenchToolRunResult(
            toolId: apiId,
            success: false,
            outputs: const {},
            project: _project,
            errorCode: 'TODO_NOT_FOUND',
            errorMessage: 'Todo not found: $todoId',
          );
        }
        final current = _project.todos[index];
        final finished = current.isFinished
            ? current
            : current.copyWith(
                status: WorkbenchTodoStatus.finished,
                finishedAt: DateTime.now(),
              );
        final todos = [..._project.todos];
        todos[index] = finished;
        _project = _project.copyWith(todos: todos);
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: true,
          outputs: {'todo': finished},
          project: _project,
        );
      default:
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: false,
          outputs: const {},
          errorCode: 'UNKNOWN_TOOL',
          errorMessage: 'Unknown workbench API: $apiId',
        );
    }
  }

  @override
  Future<WorkbenchProjectExportResult> exportProject(String projectId) async {
    return WorkbenchProjectExportResult(
      success: true,
      projectId: projectId,
      packageName: '$projectId-test.zip',
      exportPath: '/tmp/$projectId-test.zip',
      exportShellPath: '/workspace/projects/exports/$projectId-test.zip',
      includedFiles: const [
        'manifest.json',
        'registry/project_record.json',
        'registry/api_records.json',
        'project/README.md',
        'project/project.json',
        'project/frontend/page_spec.json',
        'project/backend/api_spec.json',
        'project/data/todos.json',
      ],
    );
  }

  @override
  Future<WorkbenchProjectDeleteResult> deleteProject(String projectId) async {
    final deletedProjectId = _project.projectId;
    _project = WorkbenchTodoProjectFactory.create();
    _apiCallCounts.clear();
    _hasProject = false;
    return WorkbenchProjectDeleteResult(
      success: true,
      projectId: deletedProjectId,
      projectPath: '/tmp/$deletedProjectId',
      spacePath: '/workspace/projects/$deletedProjectId',
      remainingProjectCount: 0,
    );
  }

  @override
  Future<WorkbenchProjectHotUpdateResult> hotUpdateProject({
    required String projectId,
    required String prompt,
  }) async {
    final addResult = await callApi(
      projectId: projectId,
      apiId: WorkbenchTodoToolIds.addTodo,
      inputs: {'title': prompt},
    );
    return WorkbenchProjectHotUpdateResult(
      success: addResult.success,
      projectId: projectId,
      prompt: prompt,
      appliedActionCount: addResult.success ? 1 : 0,
      project: _project,
      hotUpdateLogPath: '/workspace/projects/$projectId/logs/hot_updates.jsonl',
    );
  }

  @override
  Future<WorkbenchAndroidIngestResult> ingestAndroidAsset({
    required String projectId,
    required String sourcePath,
    String? sourceKind,
    String? displayName,
  }) async {
    final asset = WorkbenchAndroidAsset(
      assetId: 'demo-apk',
      sourceKind: sourceKind?.trim().isNotEmpty == true
          ? sourceKind!.trim()
          : 'apk',
      displayName: displayName?.trim().isNotEmpty == true
          ? displayName!.trim()
          : sourcePath.split('/').last,
      originalPath: sourcePath,
      projectPath: '/tmp/$projectId/android/apps/demo-apk',
      shellPath: '/workspace/projects/$projectId/android/apps/demo-apk',
      entryPath:
          '/workspace/projects/$projectId/android/apps/demo-apk/source.apk',
      importedAt: DateTime.now(),
      sizeBytes: 12,
      fileCount: 1,
    );
    _project = _project.copyWith(
      androidAssets: [asset, ..._project.androidAssets],
    );
    return WorkbenchAndroidIngestResult(
      success: true,
      projectId: projectId,
      asset: asset,
      project: _project,
      androidManifestPath:
          '/workspace/projects/$projectId/android/manifest.json',
      androidIngestLogPath:
          '/workspace/projects/$projectId/logs/android_ingest.jsonl',
    );
  }

  List<WorkbenchToolSpec> _toolsWithExecutionCounts() {
    return _project.tools
        .map(
          (tool) => tool.copyWith(
            executionCount: _apiCallCounts[tool.id] ?? tool.executionCount,
          ),
        )
        .toList(growable: false);
  }
}

Map<String, Object?> _projectPayload({
  List<Map<String, Object?>> todos = const [],
  List<Map<String, Object?>> androidAssets = const [],
}) {
  return {
    'projectId': workbenchTodoDefaultProjectId,
    'name': 'Todo Log Workbench',
    'templateId': workbenchTodoTemplateId,
    'route': '/workbench/todo_log?projectId=$workbenchTodoDefaultProjectId',
    'spacePath': '/workspace/projects/$workbenchTodoDefaultProjectId',
    'pageIds': ['todo-log-page'],
    'displays': [
      {
        'id': 'todo-log-display',
        'title': 'Todo 日志',
        'shortName': 'TODO',
        'route': '/workbench/todo_log?projectId=$workbenchTodoDefaultProjectId',
        'kind': 'oob_flutter',
        'isDefault': true,
      },
    ],
    'tools': _apiPayload(),
    'flows': [],
    'androidAssets': androidAssets,
    'todos': todos,
  };
}

Map<String, Object?> _androidAssetPayload() {
  return {
    'assetId': 'demo-apk',
    'projectId': workbenchTodoDefaultProjectId,
    'sourceKind': 'apk',
    'displayName': 'demo.apk',
    'originalPath': '/workspace/apps/demo.apk',
    'projectPath':
        '/data/workspace/projects/$workbenchTodoDefaultProjectId/android/apps/demo-apk',
    'shellPath':
        '/workspace/projects/$workbenchTodoDefaultProjectId/android/apps/demo-apk',
    'entryPath':
        '/workspace/projects/$workbenchTodoDefaultProjectId/android/apps/demo-apk/source.apk',
    'sizeBytes': 12,
    'fileCount': 1,
    'importedAt': DateTime.now().toIso8601String(),
  };
}

List<Map<String, Object?>> _apiPayload() {
  return [
    {
      'apiId': WorkbenchTodoToolIds.addTodo,
      'toolId': WorkbenchTodoToolIds.addTodo,
      'kind': 'native_template',
      'inputKeys': ['title'],
      'outputKeys': ['todo'],
    },
    {
      'apiId': WorkbenchTodoToolIds.finishTodo,
      'toolId': WorkbenchTodoToolIds.finishTodo,
      'kind': 'native_template',
      'inputKeys': ['todo_id'],
      'outputKeys': ['todo'],
    },
  ];
}
