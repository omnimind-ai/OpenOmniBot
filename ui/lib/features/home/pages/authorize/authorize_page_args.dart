const String kOverlayPermissionId = 'overlay';
const String kAccessibilityPermissionId = 'accessibility';
const String kInstalledAppsPermissionId = 'installed_apps';
const String kShizukuPermissionId = 'shizuku';
const String kWorkspaceStoragePermissionId = 'workspace_storage';
const String kPublicStoragePermissionId = 'public_storage';

const List<String> kTaskExecutionRequiredPermissionIds = <String>[
  kOverlayPermissionId,
  kAccessibilityPermissionId,
];

List<String> normalizeRequiredPermissionIds(Iterable<dynamic>? rawIds) {
  if (rawIds == null) return const <String>[];
  return rawIds
      .map((item) => item.toString().trim())
      .where((item) => item.isNotEmpty)
      .toSet()
      .toList(growable: false);
}

class AuthorizePageArgs {
  final List<String> requiredPermissionIds;

  const AuthorizePageArgs({
    this.requiredPermissionIds = const <String>[],
  });

  static const AuthorizePageArgs taskExecution = AuthorizePageArgs(
    requiredPermissionIds: kTaskExecutionRequiredPermissionIds,
  );
}
