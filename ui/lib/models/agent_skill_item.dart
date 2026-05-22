class AgentSkillItem {
  final String id;
  final String name;
  final String description;
  final String? compatibility;
  final Map<String, dynamic> metadata;
  final String rootPath;
  final String shellRootPath;
  final String skillFilePath;
  final String shellSkillFilePath;
  final bool hasScripts;
  final bool hasReferences;
  final bool hasAssets;
  final bool hasEvals;
  final bool enabled;
  final String source;
  final bool installed;

  const AgentSkillItem({
    required this.id,
    required this.name,
    required this.description,
    this.compatibility,
    required this.metadata,
    required this.rootPath,
    required this.shellRootPath,
    required this.skillFilePath,
    required this.shellSkillFilePath,
    required this.hasScripts,
    required this.hasReferences,
    required this.hasAssets,
    required this.hasEvals,
    required this.enabled,
    required this.source,
    required this.installed,
  });

  factory AgentSkillItem.fromMap(Map<String, dynamic> raw) {
    return AgentSkillItem(
      id: (raw['id'] ?? '').toString(),
      name: (raw['name'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      compatibility: raw['compatibility']?.toString(),
      metadata: Map<String, dynamic>.from(
        (raw['metadata'] as Map?) ?? const <String, dynamic>{},
      ),
      rootPath: (raw['rootPath'] ?? '').toString(),
      shellRootPath: (raw['shellRootPath'] ?? raw['rootPath'] ?? '').toString(),
      skillFilePath: (raw['skillFilePath'] ?? '').toString(),
      shellSkillFilePath:
          (raw['shellSkillFilePath'] ?? raw['skillFilePath'] ?? '').toString(),
      hasScripts: raw['hasScripts'] == true,
      hasReferences: raw['hasReferences'] == true,
      hasAssets: raw['hasAssets'] == true,
      hasEvals: raw['hasEvals'] == true,
      enabled: raw['enabled'] != false,
      source: (raw['source'] ?? 'user').toString(),
      installed: raw['installed'] != false,
    );
  }

  List<String> get capabilities {
    final values = <String>[];
    if (hasScripts) values.add('scripts');
    if (hasReferences) values.add('references');
    if (hasAssets) values.add('assets');
    if (hasEvals) values.add('evals');
    return values;
  }

  bool get isBuiltin => source == 'builtin';

  bool get isOfficial => source == 'official';

  AgentSkillItem copyWith({
    String? id,
    String? name,
    String? description,
    String? compatibility,
    Map<String, dynamic>? metadata,
    String? rootPath,
    String? shellRootPath,
    String? skillFilePath,
    String? shellSkillFilePath,
    bool? hasScripts,
    bool? hasReferences,
    bool? hasAssets,
    bool? hasEvals,
    bool? enabled,
    String? source,
    bool? installed,
  }) {
    return AgentSkillItem(
      id: id ?? this.id,
      name: name ?? this.name,
      description: description ?? this.description,
      compatibility: compatibility ?? this.compatibility,
      metadata: metadata ?? this.metadata,
      rootPath: rootPath ?? this.rootPath,
      shellRootPath: shellRootPath ?? this.shellRootPath,
      skillFilePath: skillFilePath ?? this.skillFilePath,
      shellSkillFilePath: shellSkillFilePath ?? this.shellSkillFilePath,
      hasScripts: hasScripts ?? this.hasScripts,
      hasReferences: hasReferences ?? this.hasReferences,
      hasAssets: hasAssets ?? this.hasAssets,
      hasEvals: hasEvals ?? this.hasEvals,
      enabled: enabled ?? this.enabled,
      source: source ?? this.source,
      installed: installed ?? this.installed,
    );
  }
}
