enum WorkbenchTodoStatus {
  open,
  finished;

  bool get isFinished => this == WorkbenchTodoStatus.finished;

  static WorkbenchTodoStatus fromWire(Object? value) {
    return value?.toString() == 'finished'
        ? WorkbenchTodoStatus.finished
        : WorkbenchTodoStatus.open;
  }

  String get wireName => switch (this) {
    WorkbenchTodoStatus.open => 'open',
    WorkbenchTodoStatus.finished => 'finished',
  };
}

class WorkbenchTodoItem {
  const WorkbenchTodoItem({
    required this.id,
    required this.title,
    required this.status,
    required this.createdAt,
    this.finishedAt,
  });

  final String id;
  final String title;
  final WorkbenchTodoStatus status;
  final DateTime createdAt;
  final DateTime? finishedAt;

  bool get isFinished => status.isFinished;

  factory WorkbenchTodoItem.fromMap(Map<dynamic, dynamic> map) {
    return WorkbenchTodoItem(
      id: (map['id'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      status: WorkbenchTodoStatus.fromWire(map['status']),
      createdAt:
          DateTime.tryParse((map['createdAt'] ?? '').toString()) ??
          DateTime.fromMillisecondsSinceEpoch(0),
      finishedAt: map['finishedAt'] == null
          ? null
          : DateTime.tryParse(map['finishedAt'].toString()),
    );
  }

  Map<String, Object?> toMap() {
    return {
      'id': id,
      'title': title,
      'status': status.wireName,
      'createdAt': createdAt.toIso8601String(),
      'finishedAt': finishedAt?.toIso8601String(),
    };
  }

  WorkbenchTodoItem copyWith({
    String? id,
    String? title,
    WorkbenchTodoStatus? status,
    DateTime? createdAt,
    DateTime? finishedAt,
  }) {
    return WorkbenchTodoItem(
      id: id ?? this.id,
      title: title ?? this.title,
      status: status ?? this.status,
      createdAt: createdAt ?? this.createdAt,
      finishedAt: finishedAt ?? this.finishedAt,
    );
  }
}

class WorkbenchToolSpec {
  const WorkbenchToolSpec({
    required this.id,
    required this.kind,
    required this.inputKeys,
    required this.outputKeys,
    this.executionCount = 0,
    this.displayName,
    this.description,
  });

  final String id;
  final String kind;
  final List<String> inputKeys;
  final List<String> outputKeys;
  final int executionCount;
  final String? displayName;
  final String? description;

  factory WorkbenchToolSpec.fromMap(Map<dynamic, dynamic> map) {
    final inputKeys = map['inputKeys'];
    final outputKeys = map['outputKeys'];
    return WorkbenchToolSpec(
      id: (map['toolId'] ?? map['apiId'] ?? map['id'] ?? '').toString(),
      kind: (map['kind'] ?? map['executorKind'] ?? '').toString(),
      inputKeys: inputKeys is List
          ? inputKeys.map((item) => item.toString()).toList(growable: false)
          : const [],
      outputKeys: outputKeys is List
          ? outputKeys.map((item) => item.toString()).toList(growable: false)
          : const [],
      executionCount:
          int.tryParse((map['executionCount'] ?? '0').toString()) ?? 0,
      displayName: map['displayName']?.toString(),
      description: map['description']?.toString(),
    );
  }

  WorkbenchToolSpec copyWith({
    String? id,
    String? kind,
    List<String>? inputKeys,
    List<String>? outputKeys,
    int? executionCount,
    String? displayName,
    String? description,
  }) {
    return WorkbenchToolSpec(
      id: id ?? this.id,
      kind: kind ?? this.kind,
      inputKeys: inputKeys ?? this.inputKeys,
      outputKeys: outputKeys ?? this.outputKeys,
      executionCount: executionCount ?? this.executionCount,
      displayName: displayName ?? this.displayName,
      description: description ?? this.description,
    );
  }
}

class WorkbenchFlowSpec {
  const WorkbenchFlowSpec({
    required this.id,
    required this.pageId,
    required this.triggerId,
    required this.toolId,
    required this.inputBinding,
    required this.outputBinding,
  });

  final String id;
  final String pageId;
  final String triggerId;
  final String toolId;
  final Map<String, String> inputBinding;
  final Map<String, String> outputBinding;

  factory WorkbenchFlowSpec.fromMap(Map<dynamic, dynamic> map) {
    return WorkbenchFlowSpec(
      id: (map['id'] ?? '').toString(),
      pageId: (map['pageId'] ?? '').toString(),
      triggerId: (map['triggerId'] ?? '').toString(),
      toolId: (map['toolId'] ?? '').toString(),
      inputBinding: map['inputBinding'] is Map
          ? Map<String, String>.from(map['inputBinding'] as Map)
          : const {},
      outputBinding: map['outputBinding'] is Map
          ? Map<String, String>.from(map['outputBinding'] as Map)
          : const {},
    );
  }
}

class WorkbenchAndroidAsset {
  const WorkbenchAndroidAsset({
    required this.assetId,
    required this.sourceKind,
    required this.displayName,
    required this.originalPath,
    required this.projectPath,
    required this.shellPath,
    required this.entryPath,
    required this.importedAt,
    this.packageName,
    this.versionName,
    this.versionCode,
    this.sizeBytes = 0,
    this.fileCount = 0,
  });

  final String assetId;
  final String sourceKind;
  final String displayName;
  final String originalPath;
  final String projectPath;
  final String shellPath;
  final String entryPath;
  final String? packageName;
  final String? versionName;
  final int? versionCode;
  final int sizeBytes;
  final int fileCount;
  final DateTime importedAt;

  bool get isApk => sourceKind == 'apk';

  String get displayPath => entryPath.trim().isEmpty ? shellPath : entryPath;

  factory WorkbenchAndroidAsset.fromMap(Map<dynamic, dynamic> map) {
    return WorkbenchAndroidAsset(
      assetId: (map['assetId'] ?? '').toString(),
      sourceKind: (map['sourceKind'] ?? '').toString(),
      displayName: (map['displayName'] ?? map['assetId'] ?? '').toString(),
      originalPath: (map['originalPath'] ?? '').toString(),
      projectPath: (map['projectPath'] ?? '').toString(),
      shellPath: (map['shellPath'] ?? '').toString(),
      entryPath: (map['entryPath'] ?? '').toString(),
      packageName: map['packageName']?.toString(),
      versionName: map['versionName']?.toString(),
      versionCode: int.tryParse((map['versionCode'] ?? '').toString()),
      sizeBytes: int.tryParse((map['sizeBytes'] ?? '0').toString()) ?? 0,
      fileCount: int.tryParse((map['fileCount'] ?? '0').toString()) ?? 0,
      importedAt:
          DateTime.tryParse((map['importedAt'] ?? '').toString()) ??
          DateTime.fromMillisecondsSinceEpoch(0),
    );
  }
}

class WorkbenchProject {
  const WorkbenchProject({
    required this.projectId,
    required this.name,
    required this.templateId,
    required this.route,
    required this.spacePath,
    required this.pageIds,
    required this.tools,
    required this.flows,
    required this.androidAssets,
    required this.todos,
  });

  final String projectId;
  final String name;
  final String templateId;
  final String route;
  final String spacePath;
  final List<String> pageIds;
  final List<WorkbenchToolSpec> tools;
  final List<WorkbenchFlowSpec> flows;
  final List<WorkbenchAndroidAsset> androidAssets;
  final List<WorkbenchTodoItem> todos;

  List<WorkbenchTodoItem> get openTodos =>
      todos.where((todo) => !todo.isFinished).toList(growable: false);

  List<WorkbenchTodoItem> get finishedTodos =>
      todos.where((todo) => todo.isFinished).toList(growable: false);

  factory WorkbenchProject.fromMap(Map<dynamic, dynamic> map) {
    final pageIds = map['pageIds'];
    final tools = map['tools'] ?? map['apis'];
    final flows = map['flows'];
    final androidAssets = map['androidAssets'];
    final todos = map['todos'];
    return WorkbenchProject(
      projectId: (map['projectId'] ?? '').toString(),
      name: (map['name'] ?? map['displayName'] ?? '').toString(),
      templateId: (map['templateId'] ?? '').toString(),
      route: (map['route'] ?? '').toString(),
      spacePath: (map['spacePath'] ?? '').toString(),
      pageIds: pageIds is List
          ? pageIds.map((item) => item.toString()).toList(growable: false)
          : const [],
      tools: tools is List
          ? tools
                .whereType<Map<dynamic, dynamic>>()
                .map(WorkbenchToolSpec.fromMap)
                .toList(growable: false)
          : const [],
      flows: flows is List
          ? flows
                .whereType<Map<dynamic, dynamic>>()
                .map(WorkbenchFlowSpec.fromMap)
                .toList(growable: false)
          : const [],
      androidAssets: androidAssets is List
          ? androidAssets
                .whereType<Map<dynamic, dynamic>>()
                .map(WorkbenchAndroidAsset.fromMap)
                .toList(growable: false)
          : const [],
      todos: todos is List
          ? todos
                .whereType<Map<dynamic, dynamic>>()
                .map(WorkbenchTodoItem.fromMap)
                .toList(growable: false)
          : const [],
    );
  }

  WorkbenchProject copyWith({
    String? projectId,
    String? name,
    String? templateId,
    String? route,
    String? spacePath,
    List<String>? pageIds,
    List<WorkbenchToolSpec>? tools,
    List<WorkbenchFlowSpec>? flows,
    List<WorkbenchAndroidAsset>? androidAssets,
    List<WorkbenchTodoItem>? todos,
  }) {
    return WorkbenchProject(
      projectId: projectId ?? this.projectId,
      name: name ?? this.name,
      templateId: templateId ?? this.templateId,
      route: route ?? this.route,
      spacePath: spacePath ?? this.spacePath,
      pageIds: pageIds ?? this.pageIds,
      tools: tools ?? this.tools,
      flows: flows ?? this.flows,
      androidAssets: androidAssets ?? this.androidAssets,
      todos: todos ?? this.todos,
    );
  }
}

class WorkbenchToolRunResult {
  const WorkbenchToolRunResult({
    required this.toolId,
    required this.success,
    required this.outputs,
    this.project,
    this.errorCode,
    this.errorMessage,
  });

  final String toolId;
  final bool success;
  final Map<String, Object?> outputs;
  final WorkbenchProject? project;
  final String? errorCode;
  final String? errorMessage;

  factory WorkbenchToolRunResult.fromMap(Map<dynamic, dynamic> map) {
    final outputs = map['outputs'];
    final project = map['project'];
    return WorkbenchToolRunResult(
      toolId: (map['toolId'] ?? map['apiId'] ?? '').toString(),
      success: map['success'] == true,
      outputs: outputs is Map ? Map<String, Object?>.from(outputs) : const {},
      project: project is Map ? WorkbenchProject.fromMap(project) : null,
      errorCode: map['errorCode']?.toString(),
      errorMessage: map['errorMessage']?.toString(),
    );
  }
}

class WorkbenchProjectExportResult {
  const WorkbenchProjectExportResult({
    required this.success,
    required this.projectId,
    required this.packageName,
    required this.exportPath,
    required this.exportShellPath,
    required this.includedFiles,
  });

  final bool success;
  final String projectId;
  final String packageName;
  final String exportPath;
  final String exportShellPath;
  final List<String> includedFiles;

  String get displayPath =>
      exportShellPath.trim().isEmpty ? exportPath : exportShellPath;

  factory WorkbenchProjectExportResult.fromMap(Map<dynamic, dynamic> map) {
    final includedFiles = map['includedFiles'];
    return WorkbenchProjectExportResult(
      success: map['success'] == true,
      projectId: (map['projectId'] ?? '').toString(),
      packageName: (map['packageName'] ?? '').toString(),
      exportPath: (map['exportPath'] ?? '').toString(),
      exportShellPath: (map['exportShellPath'] ?? '').toString(),
      includedFiles: includedFiles is List
          ? includedFiles.map((item) => item.toString()).toList(growable: false)
          : const [],
    );
  }
}

class WorkbenchProjectDeleteResult {
  const WorkbenchProjectDeleteResult({
    required this.success,
    required this.projectId,
    required this.remainingProjectCount,
    this.projectPath = '',
    this.spacePath = '',
  });

  final bool success;
  final String projectId;
  final String projectPath;
  final String spacePath;
  final int remainingProjectCount;

  factory WorkbenchProjectDeleteResult.fromMap(Map<dynamic, dynamic> map) {
    return WorkbenchProjectDeleteResult(
      success: map['success'] == true,
      projectId: (map['projectId'] ?? '').toString(),
      projectPath: (map['projectPath'] ?? '').toString(),
      spacePath: (map['spacePath'] ?? '').toString(),
      remainingProjectCount:
          int.tryParse((map['remainingProjectCount'] ?? '0').toString()) ?? 0,
    );
  }
}

class WorkbenchProjectHotUpdateResult {
  const WorkbenchProjectHotUpdateResult({
    required this.success,
    required this.projectId,
    required this.prompt,
    required this.appliedActionCount,
    this.project,
    this.hotUpdateLogPath = '',
  });

  final bool success;
  final String projectId;
  final String prompt;
  final int appliedActionCount;
  final WorkbenchProject? project;
  final String hotUpdateLogPath;

  factory WorkbenchProjectHotUpdateResult.fromMap(Map<dynamic, dynamic> map) {
    final project = map['project'];
    final appliedActions = map['appliedActions'];
    return WorkbenchProjectHotUpdateResult(
      success: map['success'] == true,
      projectId: (map['projectId'] ?? '').toString(),
      prompt: (map['prompt'] ?? '').toString(),
      appliedActionCount: appliedActions is List ? appliedActions.length : 0,
      project: project is Map ? WorkbenchProject.fromMap(project) : null,
      hotUpdateLogPath: (map['hotUpdateLogPath'] ?? '').toString(),
    );
  }
}

class WorkbenchAndroidIngestResult {
  const WorkbenchAndroidIngestResult({
    required this.success,
    required this.projectId,
    required this.asset,
    this.project,
    this.androidManifestPath = '',
    this.androidIngestLogPath = '',
  });

  final bool success;
  final String projectId;
  final WorkbenchAndroidAsset? asset;
  final WorkbenchProject? project;
  final String androidManifestPath;
  final String androidIngestLogPath;

  factory WorkbenchAndroidIngestResult.fromMap(Map<dynamic, dynamic> map) {
    final asset = map['asset'];
    final project = map['project'];
    return WorkbenchAndroidIngestResult(
      success: map['success'] == true,
      projectId: (map['projectId'] ?? '').toString(),
      asset: asset is Map ? WorkbenchAndroidAsset.fromMap(asset) : null,
      project: project is Map ? WorkbenchProject.fromMap(project) : null,
      androidManifestPath: (map['androidManifestPath'] ?? '').toString(),
      androidIngestLogPath: (map['androidIngestLogPath'] ?? '').toString(),
    );
  }
}

class WorkbenchTodoToolIds {
  const WorkbenchTodoToolIds._();

  static const addTodo = 'todo.add';
  static const finishTodo = 'todo.finish';
}

class WorkbenchTodoProjectFactory {
  const WorkbenchTodoProjectFactory._();

  static WorkbenchProject create() {
    return WorkbenchProject(
      projectId: 'oob-workbench-todo-log',
      name: 'Todo Log Workbench',
      templateId: 'todo_log_demo',
      route: '/workbench/todo_log?projectId=oob-workbench-todo-log',
      spacePath: '/workspace/projects/oob-workbench-todo-log',
      pageIds: const ['todo-log-page'],
      tools: const [
        WorkbenchToolSpec(
          id: WorkbenchTodoToolIds.addTodo,
          kind: 'native_template',
          inputKeys: ['title'],
          outputKeys: ['todo'],
        ),
        WorkbenchToolSpec(
          id: WorkbenchTodoToolIds.finishTodo,
          kind: 'native_template',
          inputKeys: ['todo_id'],
          outputKeys: ['todo'],
        ),
      ],
      flows: const [
        WorkbenchFlowSpec(
          id: 'todo-log-page.add',
          pageId: 'todo-log-page',
          triggerId: 'add-button',
          toolId: WorkbenchTodoToolIds.addTodo,
          inputBinding: {'title': 'page.todo_input'},
          outputBinding: {'todo': 'project.todos'},
        ),
        WorkbenchFlowSpec(
          id: 'todo-log-page.finish',
          pageId: 'todo-log-page',
          triggerId: 'finish-button',
          toolId: WorkbenchTodoToolIds.finishTodo,
          inputBinding: {'todo_id': 'todo.id'},
          outputBinding: {'todo': 'project.todos'},
        ),
      ],
      androidAssets: const [],
      todos: const [],
    );
  }
}
