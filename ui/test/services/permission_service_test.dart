import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';

void main() {
  tearDown(AppTextLocalizer.clearResolvedLocale);

  test('rebuilds special permission labels from current locale', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(zhPermission.name, '内置 workspace');

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(enPermission.name, 'Built-in workspace');
  });

  test('builds shizuku display permission from current locale', () {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(zhPermission.name, 'Shizuku 权限');

    AppTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(enPermission.name, 'Shizuku Permission');
  });

  test('shizuku stays optional unless explicitly included', () {
    final defaultSpecs = PermissionRegistry.getPermissions(brand: 'other');
    expect(
      defaultSpecs.any((item) => item.id == kShizukuPermissionId),
      isFalse,
    );

    final optionalSpecs = PermissionRegistry.getPermissions(
      brand: 'other',
      includeOptionalAdvanced: true,
    );
    expect(
      optionalSpecs.any((item) => item.id == kShizukuPermissionId),
      isTrue,
    );
  });

  test('companion automation only requires overlay permission', () {
    final specs = PermissionRegistry.getPermissionsByLevel(
      brand: 'other',
      level: PermissionLevel.companionAutomation,
    );

    expect(specs.map((item) => item.id), [kOverlayPermissionId]);
  });
}
