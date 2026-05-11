class WorkbenchProjectItem {
  const WorkbenchProjectItem({
    required this.id,
    required this.title,
    required this.status,
    required this.createdAt,
    this.fields = const {},
    this.archivedAt,
  });

  final String id;
  final String title;
  final String status;
  final Map<String, Object?> fields;
  final DateTime createdAt;
  final DateTime? archivedAt;

  bool get isArchived => status == 'archived';

  factory WorkbenchProjectItem.fromMap(Map<dynamic, dynamic> map) {
    final fields = map['fields'];
    return WorkbenchProjectItem(
      id: (map['id'] ?? '').toString(),
      title: (map['title'] ?? map['name'] ?? '').toString(),
      status: (map['status'] ?? 'active').toString(),
      fields: fields is Map ? Map<String, Object?>.from(fields) : const {},
      createdAt:
          DateTime.tryParse((map['createdAt'] ?? '').toString()) ??
          DateTime.fromMillisecondsSinceEpoch(0),
      archivedAt: map['archivedAt'] == null
          ? null
          : DateTime.tryParse(map['archivedAt'].toString()),
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

class WorkbenchDisplaySpec {
  const WorkbenchDisplaySpec({
    required this.id,
    required this.title,
    required this.shortName,
    required this.route,
    this.description = '',
    this.kind = 'oob_flutter',
    this.renderer = 'oob_flutter',
    this.isDefault = false,
  });

  final String id;
  final String title;
  final String shortName;
  final String route;
  final String description;
  final String kind;
  final String renderer;
  final bool isDefault;

  String get label {
    final cleanTitle = title.trim();
    final cleanShortName = shortName.trim();
    if (cleanTitle.isEmpty) {
      return cleanShortName;
    }
    if (cleanShortName.isEmpty) {
      return cleanTitle;
    }
    return '$cleanTitle · $cleanShortName';
  }

  factory WorkbenchDisplaySpec.fromMap(Map<dynamic, dynamic> map) {
    return WorkbenchDisplaySpec(
      id: (map['id'] ?? map['displayId'] ?? map['pageId'] ?? '').toString(),
      title: (map['title'] ?? map['displayName'] ?? map['name'] ?? '')
          .toString(),
      shortName: (map['shortName'] ?? map['abbr'] ?? '').toString(),
      route: (map['route'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      kind: (map['kind'] ?? map['renderer'] ?? 'oob_flutter').toString(),
      renderer: (map['renderer'] ?? map['kind'] ?? 'oob_flutter').toString(),
      isDefault: map['isDefault'] == true,
    );
  }

  static WorkbenchDisplaySpec project({
    required String projectId,
    required String route,
  }) {
    final resolvedRoute = route.trim().isEmpty
        ? '/workbench/project?projectId=$projectId'
        : route.trim();
    return WorkbenchDisplaySpec(
      id: 'project-main-display',
      title: 'Project',
      shortName: 'APP',
      route: resolvedRoute,
      description: 'Display bound to Project Tools.',
      isDefault: true,
    );
  }
}

class WorkbenchProject {
  const WorkbenchProject({
    required this.projectId,
    required this.name,
    required this.route,
    required this.spacePath,
    required this.pageIds,
    required this.displays,
    required this.tools,
    required this.flows,
    required this.androidAssets,
    required this.items,
    this.pageSpec = const {},
    this.frontendHtml = const {},
    this.frontendFlutter = const {},
  });

  final String projectId;
  final String name;
  final String route;
  final String spacePath;
  final List<String> pageIds;
  final List<WorkbenchDisplaySpec> displays;
  final List<WorkbenchToolSpec> tools;
  final List<WorkbenchFlowSpec> flows;
  final List<WorkbenchAndroidAsset> androidAssets;
  final List<WorkbenchProjectItem> items;
  final Map<String, Object?> pageSpec;
  final Map<String, Object?> frontendHtml;
  final Map<String, Object?> frontendFlutter;

  List<WorkbenchProjectItem> get activeItems =>
      items.where((item) => !item.isArchived).toList(growable: false);

  List<WorkbenchProjectItem> get archivedItems =>
      items.where((item) => item.isArchived).toList(growable: false);

  WorkbenchDisplaySpec get primaryDisplay {
    for (final display in displays) {
      if (display.isDefault) {
        return display;
      }
    }
    if (displays.isNotEmpty) {
      return displays.first;
    }
    return WorkbenchDisplaySpec.project(projectId: projectId, route: route);
  }

  factory WorkbenchProject.fromMap(Map<dynamic, dynamic> map) {
    final projectId = (map['projectId'] ?? '').toString();
    final route = (map['route'] ?? '').toString();
    final pageIds = map['pageIds'];
    final displays = map['displays'] ?? map['frontends'];
    final tools = map['tools'] ?? map['apis'];
    final flows = map['flows'];
    final androidAssets = map['androidAssets'];
    final items = map['items'];
    final pageSpec = map['pageSpec'];
    final frontendHtml = map['frontendHtml'];
    final frontendFlutter = map['frontendFlutter'];
    final parsedDisplays = displays is List
        ? displays
              .whereType<Map<dynamic, dynamic>>()
              .map(WorkbenchDisplaySpec.fromMap)
              .where((display) => display.route.trim().isNotEmpty)
              .toList(growable: false)
        : const <WorkbenchDisplaySpec>[];
    return WorkbenchProject(
      projectId: projectId,
      name: (map['name'] ?? map['displayName'] ?? '').toString(),
      route: route,
      spacePath: (map['spacePath'] ?? '').toString(),
      pageIds: pageIds is List
          ? pageIds.map((item) => item.toString()).toList(growable: false)
          : const [],
      displays: parsedDisplays.isEmpty
          ? [WorkbenchDisplaySpec.project(projectId: projectId, route: route)]
          : parsedDisplays,
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
      items: items is List
          ? items
                .whereType<Map<dynamic, dynamic>>()
                .map(WorkbenchProjectItem.fromMap)
                .toList(growable: false)
          : const [],
      pageSpec: pageSpec is Map
          ? Map<String, Object?>.from(pageSpec)
          : const {},
      frontendHtml: frontendHtml is Map
          ? Map<String, Object?>.from(frontendHtml)
          : const {},
      frontendFlutter: frontendFlutter is Map
          ? Map<String, Object?>.from(frontendFlutter)
          : const {},
    );
  }

  WorkbenchProject copyWith({
    String? projectId,
    String? name,
    String? route,
    String? spacePath,
    List<String>? pageIds,
    List<WorkbenchDisplaySpec>? displays,
    List<WorkbenchToolSpec>? tools,
    List<WorkbenchFlowSpec>? flows,
    List<WorkbenchAndroidAsset>? androidAssets,
    List<WorkbenchProjectItem>? items,
    Map<String, Object?>? pageSpec,
    Map<String, Object?>? frontendHtml,
    Map<String, Object?>? frontendFlutter,
  }) {
    return WorkbenchProject(
      projectId: projectId ?? this.projectId,
      name: name ?? this.name,
      route: route ?? this.route,
      spacePath: spacePath ?? this.spacePath,
      pageIds: pageIds ?? this.pageIds,
      displays: displays ?? this.displays,
      tools: tools ?? this.tools,
      flows: flows ?? this.flows,
      androidAssets: androidAssets ?? this.androidAssets,
      items: items ?? this.items,
      pageSpec: pageSpec ?? this.pageSpec,
      frontendHtml: frontendHtml ?? this.frontendHtml,
      frontendFlutter: frontendFlutter ?? this.frontendFlutter,
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
    this.requiresAgentRegeneration = false,
    this.recommendedTool = '',
    this.instructions = const <String>[],
  });

  final bool success;
  final String projectId;
  final String prompt;
  final int appliedActionCount;
  final WorkbenchProject? project;
  final String hotUpdateLogPath;
  final bool requiresAgentRegeneration;
  final String recommendedTool;
  final List<String> instructions;

  factory WorkbenchProjectHotUpdateResult.fromMap(Map<dynamic, dynamic> map) {
    final project = map['project'];
    final appliedActions = map['appliedActions'];
    final rawInstructions = map['instructions'];
    return WorkbenchProjectHotUpdateResult(
      success: map['success'] == true,
      projectId: (map['projectId'] ?? '').toString(),
      prompt: (map['prompt'] ?? '').toString(),
      appliedActionCount: appliedActions is List ? appliedActions.length : 0,
      project: project is Map ? WorkbenchProject.fromMap(project) : null,
      hotUpdateLogPath: (map['hotUpdateLogPath'] ?? '').toString(),
      requiresAgentRegeneration: map['requiresAgentRegeneration'] == true,
      recommendedTool: (map['recommendedTool'] ?? '').toString(),
      instructions: rawInstructions is List
          ? rawInstructions
                .map((item) => item.toString())
                .toList(growable: false)
          : const <String>[],
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
