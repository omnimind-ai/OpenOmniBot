import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/assists_core_service.dart';

class AgentOfficialSkillsSyncResult {
  final String action;
  final String repositoryUrl;
  final String rootPath;
  final String shellRootPath;
  final int skillCount;
  final List<AgentSkillItem> skills;

  const AgentOfficialSkillsSyncResult({
    required this.action,
    required this.repositoryUrl,
    required this.rootPath,
    required this.shellRootPath,
    required this.skillCount,
    required this.skills,
  });

  factory AgentOfficialSkillsSyncResult.fromMap(Map<String, dynamic> raw) {
    final rawSkills = raw['skills'];
    final skills = rawSkills is List
        ? rawSkills
              .whereType<Map>()
              .map(
                (item) => AgentSkillItem.fromMap(
                  item.map((key, value) => MapEntry(key.toString(), value)),
                ),
              )
              .toList()
        : <AgentSkillItem>[];
    _sortSkills(skills);
    return AgentOfficialSkillsSyncResult(
      action: (raw['action'] ?? '').toString(),
      repositoryUrl: (raw['repositoryUrl'] ?? '').toString(),
      rootPath: (raw['rootPath'] ?? '').toString(),
      shellRootPath: (raw['shellRootPath'] ?? '').toString(),
      skillCount: _parseInt(raw['skillCount']),
      skills: skills,
    );
  }
}

class AgentSkillStoreService {
  static Future<List<AgentSkillItem>> listSkills() async {
    final items = await AssistsMessageService.listAgentSkills();
    final skills = items.map(AgentSkillItem.fromMap).toList();
    _sortSkills(skills);
    return skills;
  }

  static Future<AgentSkillItem?> setEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    final result = await AssistsMessageService.setAgentSkillEnabled(
      skillId: skillId,
      enabled: enabled,
    );
    if (result == null) return null;
    return AgentSkillItem.fromMap(result);
  }

  static Future<bool> deleteSkill({required String skillId}) {
    return AssistsMessageService.deleteAgentSkill(skillId: skillId);
  }

  static Future<AgentSkillItem?> installBuiltinSkill({
    required String skillId,
  }) async {
    final result = await AssistsMessageService.installBuiltinAgentSkill(
      skillId: skillId,
    );
    if (result == null) return null;
    return AgentSkillItem.fromMap(result);
  }

  static Future<AgentOfficialSkillsSyncResult?> syncOfficialSkills() async {
    final result = await AssistsMessageService.syncOfficialAgentSkills();
    if (result == null) return null;
    return AgentOfficialSkillsSyncResult.fromMap(result);
  }
}

int _parseInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value) ?? 0;
  return 0;
}

int _sourceRank(AgentSkillItem item) {
  if (item.isBuiltin) return 0;
  if (item.isOfficial) return 1;
  return 2;
}

void _sortSkills(List<AgentSkillItem> skills) {
  skills.sort((a, b) {
    if (a.installed != b.installed) {
      return a.installed ? -1 : 1;
    }
    final sourceOrder = _sourceRank(a).compareTo(_sourceRank(b));
    if (sourceOrder != 0) {
      return sourceOrder;
    }
    return a.name.toLowerCase().compareTo(b.name.toLowerCase());
  });
}
