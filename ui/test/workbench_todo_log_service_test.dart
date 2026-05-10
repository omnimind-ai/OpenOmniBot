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
    'native schema display does not create todo fallback when project is missing',
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
                  message: 'Workbench project not found: customer-tracker',
                );
              default:
                fail('Unexpected method ${call.method}');
            }
          });

      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
      });

      final service = WorkbenchTodoLogService.native(
        projectId: 'customer-tracker',
        autoCreateTodoIfMissing: false,
      );
      await service.initialize();

      expect(calls, ['workbenchProjectGet']);
      expect(service.errorMessage, contains('customer-tracker'));
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
    'project mode can create a generic schema project from prompt',
    () async {
      final service = _projectModeService();

      final created = await service.createSchemaProjectFromPrompt(
        '创建一个客户跟进系统，可以新增客户并归档客户',
        projectId: 'customer-tracker',
        name: 'Customer Tracker',
        entityName: 'Customer',
        description: 'Track customer follow-up records.',
        initialItems: const ['Alice'],
      );

      expect(created?.projectId, 'customer-tracker');
      expect(created?.templateId, 'schema_app');
      expect(
        created?.route,
        '/workbench/schema_app?projectId=customer-tracker',
      );
      expect(created?.schema['entityName'], 'Customer');
      expect(created?.items.single.title, 'Alice');
      expect(
        created?.tools.map((tool) => tool.id),
        containsAll(['customer.create', 'customer.archive']),
      );
      expect(
        created?.tools.map((tool) => tool.id),
        isNot(contains(WorkbenchTodoToolIds.addTodo)),
      );
    },
  );

  test(
    'quick capture project ingests links and archives through project APIs',
    () async {
      final backend = _TestWorkbenchProjectBackend();
      final created = await backend.createProject(
        projectId: workbenchQuickCaptureProjectId,
        templateId: workbenchQuickCaptureTemplateId,
        name: '随手记 Inbox',
      );
      final service = WorkbenchTodoLogService(
        backend: backend,
        projectId: workbenchQuickCaptureProjectId,
        initialProject: created,
        autoCreateTodoIfMissing: false,
      );

      await service.initialize();
      final ingest = await service
          .runTool(WorkbenchQuickCaptureToolIds.ingest, {
            'text': 'https://xhslink.com/demo 装修案例',
            'url': 'https://xhslink.com/demo',
            'sourceApp': '小红书',
          });
      final item = service.project.activeCaptureItems.first;
      final archive = await service.runTool(
        WorkbenchQuickCaptureToolIds.archive,
        {'item_id': item.id},
      );

      expect(service.project.templateId, workbenchQuickCaptureTemplateId);
      expect(service.project.primaryDisplay.shortName, 'NOTE');
      expect(ingest.success, isTrue);
      expect(item.type, 'link');
      expect(item.url, 'https://xhslink.com/demo');
      expect(archive.success, isTrue);
      expect(service.project.archivedCaptureItems.single.id, item.id);
      expect(
        service.project.tools
            .firstWhere(
              (tool) => tool.id == WorkbenchQuickCaptureToolIds.ingest,
            )
            .executionCount,
        1,
      );
      expect(
        service.project.tools
            .firstWhere(
              (tool) => tool.id == WorkbenchQuickCaptureToolIds.archive,
            )
            .executionCount,
        1,
      );
    },
  );

  test('native create forwards generic schema project config', () async {
    const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'workbenchProjectCreate':
              final args = call.arguments as Map<dynamic, dynamic>;
              expect(args['templateId'], 'schema_app');
              expect(args['entityName'], 'Habit');
              expect(args['description'], 'Track daily routines.');
              expect(args['initialItems'], ['Drink water']);
              return _schemaProjectPayload();
            default:
              fail('Unexpected method ${call.method}');
          }
        });

    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    final backend = NativeWorkbenchProjectBackend();
    final project = await backend.createProject(
      projectId: 'habit-tracker',
      templateId: 'schema_app',
      name: 'Habit Tracker',
      prompt: 'Create a habit tracker',
      entityName: 'Habit',
      description: 'Track daily routines.',
      initialItems: const ['Drink water'],
    );

    expect(calls.single.method, 'workbenchProjectCreate');
    expect(project.projectId, 'habit-tracker');
    expect(project.schema['entityName'], 'Habit');
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
              case 'workbenchProjectActiveGet':
                return {'success': true, 'project': null};
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
        'workbenchProjectActiveGet',
        'workbenchProjectExport',
        'workbenchProjectDelete',
        'workbenchProjectList',
        'workbenchProjectActiveGet',
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

  test('project mode activates and clears active project context', () async {
    final service = _projectModeService();

    await service.refresh();
    final active = await service.activateProject(service.projects.single);

    expect(active?.projectId, workbenchTodoDefaultProjectId);
    expect(service.activeProject?.projectId, workbenchTodoDefaultProjectId);

    await service.deactivateProject();

    expect(service.activeProject, isNull);
  });

  test('native service activates project through MethodChannel', () async {
    const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          switch (call.method) {
            case 'workbenchProjectList':
              return [_projectPayload()];
            case 'workbenchProjectActiveGet':
              return {'success': true, 'project': null};
            case 'workbenchProjectActivate':
              return {
                'success': true,
                'activeProject': {
                  'projectId': workbenchTodoDefaultProjectId,
                  'skillId': 'oob-native-workbench',
                },
                'project': _projectPayload(),
              };
            case 'workbenchProjectDeactivate':
              return {
                'success': true,
                'previousProjectId': workbenchTodoDefaultProjectId,
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
    final active = await service.activateProject(service.projects.single);
    await service.deactivateProject();

    expect(calls, [
      'workbenchProjectList',
      'workbenchProjectActiveGet',
      'workbenchProjectActivate',
      'workbenchProjectDeactivate',
    ]);
    expect(active?.projectId, workbenchTodoDefaultProjectId);
    expect(service.activeProject, isNull);
  });

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
                final args = call.arguments as Map<dynamic, dynamic>;
                expect(args['frontendContext'], {
                  'displayId': 'todo-log-display',
                  'route': '/workbench/todo_log',
                });
                return {
                  'success': true,
                  'projectId': workbenchTodoDefaultProjectId,
                  'prompt': args['prompt'],
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
      final result = await service.applyHotUpdate(
        'Hot updated',
        frontendContext: const {
          'displayId': 'todo-log-display',
          'route': '/workbench/todo_log',
        },
      );

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
              case 'workbenchProjectActiveGet':
                return {'success': true, 'project': null};
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

      expect(calls, [
        'workbenchProjectList',
        'workbenchProjectActiveGet',
        'workbenchProjectIngestAndroid',
      ]);
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
  WorkbenchProject? _activeProject;

  @override
  Future<WorkbenchProject> createProject({
    required String projectId,
    required String templateId,
    String? name,
    String? prompt,
    List<String>? initialTodos,
    String? entityName,
    String? description,
    List<Object?>? initialItems,
    List<Map<String, Object?>>? apis,
  }) async {
    _hasProject = true;
    if (templateId == workbenchQuickCaptureTemplateId) {
      final now = DateTime.now();
      final captures = (initialItems ?? const [])
          .map(
            (item) => WorkbenchQuickCaptureItem(
              id: 'capture-initial-${_todoSequence++}',
              type: 'todo',
              title: item is Map
                  ? (item['title'] ?? item['text'] ?? 'Capture').toString()
                  : item.toString(),
              summary: item.toString(),
              status: 'active',
              createdAt: now,
              updatedAt: now,
            ),
          )
          .toList(growable: false);
      _project = WorkbenchProject(
        projectId: projectId,
        name: name?.trim().isNotEmpty == true ? name!.trim() : '随手记 Inbox',
        templateId: templateId,
        route: '/workbench/quick_capture?projectId=$projectId',
        spacePath: '/workspace/projects/$projectId',
        pageIds: const ['quick-capture-page'],
        displays: [
          WorkbenchDisplaySpec.quickCapture(
            projectId: projectId,
            route: '/workbench/quick_capture?projectId=$projectId',
          ),
        ],
        tools: const [
          WorkbenchToolSpec(
            id: WorkbenchQuickCaptureToolIds.ingest,
            kind: 'workspace_python_script',
            inputKeys: [
              'text',
              'url',
              'sourceApp',
              'shareText',
              'screenshotPath',
            ],
            outputKeys: ['item', 'items'],
          ),
          WorkbenchToolSpec(
            id: WorkbenchQuickCaptureToolIds.archive,
            kind: 'workspace_python_script',
            inputKeys: ['item_id'],
            outputKeys: ['item'],
          ),
          WorkbenchToolSpec(
            id: WorkbenchQuickCaptureToolIds.promoteToTodo,
            kind: 'workspace_python_script',
            inputKeys: ['item_id', 'todo_title'],
            outputKeys: ['item'],
          ),
          WorkbenchToolSpec(
            id: WorkbenchQuickCaptureToolIds.summarize,
            kind: 'workspace_python_script',
            inputKeys: ['item_id'],
            outputKeys: ['item'],
          ),
        ],
        flows: const [],
        androidAssets: const [],
        todos: const [],
        items: const [],
        captureItems: captures,
      );
      return _project;
    }
    if (templateId == 'schema_app') {
      final entity = entityName?.trim().isNotEmpty == true
          ? entityName!.trim()
          : 'Item';
      final namespace = entity.toLowerCase();
      final items = (initialItems ?? const [])
          .map(
            (item) => WorkbenchSchemaItem(
              id: '$namespace-${_todoSequence++}',
              title: item is Map
                  ? (item['title'] ?? item['name'] ?? entity).toString()
                  : item.toString(),
              status: 'active',
              fields: item is Map ? Map<String, Object?>.from(item) : const {},
              createdAt: DateTime.now(),
            ),
          )
          .toList(growable: false);
      _project = WorkbenchProject(
        projectId: projectId,
        name: name?.trim().isNotEmpty == true ? name!.trim() : entity,
        templateId: templateId,
        route: '/workbench/schema_app?projectId=$projectId',
        spacePath: '/workspace/projects/$projectId',
        pageIds: const ['schema-main-page'],
        displays: [
          WorkbenchDisplaySpec(
            id: 'schema-main-display',
            title: name?.trim().isNotEmpty == true ? name!.trim() : entity,
            shortName: entity.toUpperCase(),
            route: '/workbench/schema_app?projectId=$projectId',
            description: description ?? '',
            kind: 'oob_schema_collection',
            isDefault: true,
          ),
        ],
        tools: [
          WorkbenchToolSpec(
            id: '$namespace.create',
            kind: 'native_schema_collection',
            inputKeys: const ['title'],
            outputKeys: const ['item'],
          ),
          WorkbenchToolSpec(
            id: '$namespace.archive',
            kind: 'native_schema_collection',
            inputKeys: const ['item_id'],
            outputKeys: const ['item'],
          ),
        ],
        flows: const [],
        androidAssets: const [],
        todos: const [],
        items: items,
        captureItems: const [],
        schema: {
          'entityName': entity,
          if (description != null) 'description': description,
        },
      );
      return _project;
    }
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
  Future<WorkbenchProject> updateProjectMetadata({
    required String projectId,
    String? name,
    String? shortName,
    String? description,
    List<Map<String, Object?>>? displays,
    List<Map<String, Object?>>? apis,
    List<Map<String, Object?>>? flutterFiles,
    String? prompt,
  }) async {
    final normalizedName = name?.trim();
    final normalizedShortName = shortName?.trim();
    final displays = _project.displays
        .map((display) {
          if (!display.isDefault && display.id != _project.primaryDisplay.id) {
            return display;
          }
          return WorkbenchDisplaySpec(
            id: display.id,
            title: normalizedName?.isNotEmpty == true
                ? normalizedName!
                : display.title,
            shortName: normalizedShortName?.isNotEmpty == true
                ? normalizedShortName!
                : display.shortName,
            route: display.route,
            description: display.description,
            kind: display.kind,
            isDefault: display.isDefault,
          );
        })
        .toList(growable: false);
    _project = _project.copyWith(
      name: normalizedName?.isNotEmpty == true ? normalizedName : _project.name,
      displays: displays,
    );
    return _project;
  }

  @override
  Future<List<WorkbenchProject>> listProjects() async =>
      _hasProject ? [_project] : const [];

  @override
  Future<WorkbenchProject?> activateProject(String projectId) async {
    _activeProject = _project;
    return _activeProject;
  }

  @override
  Future<WorkbenchProject?> getActiveProject() async => _activeProject;

  @override
  Future<void> deactivateProject() async {
    _activeProject = null;
  }

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
      case WorkbenchQuickCaptureToolIds.ingest:
        final text =
            (inputs['text'] ?? inputs['shareText'] ?? inputs['url'] ?? '')
                .toString()
                .trim();
        if (text.isEmpty) {
          return WorkbenchToolRunResult(
            toolId: apiId,
            success: false,
            outputs: const {},
            project: _project,
            errorCode: 'EMPTY_CAPTURE_INPUT',
          );
        }
        final url = (inputs['url'] ?? '').toString().trim();
        final item = WorkbenchQuickCaptureItem(
          id: 'capture-${_todoSequence++}',
          type: url.isNotEmpty ? 'link' : 'todo',
          title: text,
          summary: url.isNotEmpty ? 'Link saved' : text,
          status: 'active',
          url: url.isEmpty ? null : url,
          createdAt: DateTime.now(),
          updatedAt: DateTime.now(),
        );
        _project = _project.copyWith(
          captureItems: [item, ..._project.captureItems],
        );
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: true,
          outputs: {'item': item},
          project: _project,
        );
      case WorkbenchQuickCaptureToolIds.archive:
        final itemId = (inputs['item_id'] ?? inputs['itemId'] ?? '')
            .toString()
            .trim();
        final index = _project.captureItems.indexWhere(
          (item) => item.id == itemId,
        );
        if (index < 0) {
          return WorkbenchToolRunResult(
            toolId: apiId,
            success: false,
            outputs: const {},
            project: _project,
            errorCode: 'CAPTURE_ITEM_NOT_FOUND',
          );
        }
        final current = _project.captureItems[index];
        final archived = WorkbenchQuickCaptureItem(
          id: current.id,
          type: current.type,
          title: current.title,
          summary: current.summary,
          status: 'archived',
          createdAt: current.createdAt,
          updatedAt: DateTime.now(),
          url: current.url,
          sourceApp: current.sourceApp,
          rawText: current.rawText,
          shareText: current.shareText,
          screenshotPath: current.screenshotPath,
          dueHint: current.dueHint,
          priority: current.priority,
          archivedAt: DateTime.now(),
        );
        final captures = [..._project.captureItems];
        captures[index] = archived;
        _project = _project.copyWith(captureItems: captures);
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: true,
          outputs: {'item': archived},
          project: _project,
        );
      case WorkbenchQuickCaptureToolIds.promoteToTodo:
      case WorkbenchQuickCaptureToolIds.summarize:
        final itemId = (inputs['item_id'] ?? inputs['itemId'] ?? '')
            .toString()
            .trim();
        final index = _project.captureItems.indexWhere(
          (item) => item.id == itemId,
        );
        if (index < 0) {
          return WorkbenchToolRunResult(
            toolId: apiId,
            success: false,
            outputs: const {},
            project: _project,
            errorCode: 'CAPTURE_ITEM_NOT_FOUND',
          );
        }
        final current = _project.captureItems[index];
        final updated = WorkbenchQuickCaptureItem(
          id: current.id,
          type: apiId == WorkbenchQuickCaptureToolIds.promoteToTodo
              ? 'todo'
              : 'summary',
          title: current.title,
          summary: current.summary,
          status: current.status,
          createdAt: current.createdAt,
          updatedAt: DateTime.now(),
          url: current.url,
          sourceApp: current.sourceApp,
          rawText: current.rawText,
          shareText: current.shareText,
          screenshotPath: current.screenshotPath,
          dueHint: current.dueHint,
          priority: current.priority,
          archivedAt: current.archivedAt,
        );
        final captures = [..._project.captureItems];
        captures[index] = updated;
        _project = _project.copyWith(captureItems: captures);
        return WorkbenchToolRunResult(
          toolId: apiId,
          success: true,
          outputs: {'item': updated},
          project: _project,
        );
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
    if (_activeProject?.projectId == deletedProjectId) {
      _activeProject = null;
    }
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
    Map<String, Object?>? frontendContext,
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

Map<String, Object?> _schemaProjectPayload() {
  return {
    'projectId': 'habit-tracker',
    'name': 'Habit Tracker',
    'templateId': 'schema_app',
    'route': '/workbench/schema_app?projectId=habit-tracker',
    'spacePath': '/workspace/projects/habit-tracker',
    'pageIds': ['schema-main-page'],
    'schema': {'entityName': 'Habit', 'description': 'Track daily routines.'},
    'displays': [
      {
        'id': 'schema-main-display',
        'title': 'Habit Tracker',
        'shortName': 'HABIT',
        'route': '/workbench/schema_app?projectId=habit-tracker',
        'kind': 'oob_schema_collection',
        'isDefault': true,
      },
    ],
    'tools': [
      {
        'apiId': 'habit.create',
        'toolId': 'habit.create',
        'kind': 'native_schema_collection',
        'inputKeys': ['title'],
        'outputKeys': ['item'],
      },
      {
        'apiId': 'habit.archive',
        'toolId': 'habit.archive',
        'kind': 'native_schema_collection',
        'inputKeys': ['item_id'],
        'outputKeys': ['item'],
      },
    ],
    'flows': [],
    'items': [
      {
        'id': 'habit-1',
        'title': 'Drink water',
        'status': 'active',
        'fields': {},
        'createdAt': DateTime.now().toIso8601String(),
      },
    ],
  };
}
