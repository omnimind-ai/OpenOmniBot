String? omniflowToolProfileForMessage(String message) {
  final normalized = message.trim().toLowerCase();
  if (normalized.isEmpty) return null;
  final mentionsRunLog =
      normalized.contains('runlog') ||
      normalized.contains('run log') ||
      normalized.contains('轨迹') ||
      normalized.contains('运行日志');
  final mentionsFunction =
      normalized.contains('function') ||
      normalized.contains('复用指令') ||
      normalized.contains('指令库') ||
      normalized.contains('复用命令');
  final managementIntent =
      normalized.contains('注册') ||
      normalized.contains('保存') ||
      normalized.contains('转换') ||
      normalized.contains('转成') ||
      normalized.contains('增强') ||
      normalized.contains('更新') ||
      normalized.contains('执行') ||
      normalized.contains('运行') ||
      normalized.contains('使用') ||
      normalized.contains('复用') ||
      normalized.contains('调用') ||
      normalized.contains('用刚才') ||
      normalized.contains('用上一条') ||
      normalized.contains('用上次') ||
      normalized.contains('删除') ||
      normalized.contains('查看') ||
      normalized.contains('list') ||
      normalized.contains('convert') ||
      normalized.contains('register') ||
      normalized.contains('enhance') ||
      normalized.contains('update') ||
      normalized.contains('run') ||
      normalized.contains('use') ||
      normalized.contains('reuse') ||
      normalized.contains('call') ||
      normalized.contains('delete');
  return (managementIntent && (mentionsRunLog || mentionsFunction))
      ? 'function_management'
      : null;
}
