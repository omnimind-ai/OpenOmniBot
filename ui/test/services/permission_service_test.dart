import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  test('rebuilds special permission labels from current locale', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(zhPermission.name, '内置 workspace');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(enPermission.name, 'Built-in workspace');
  });

  test('builds shizuku display permission from current locale', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(zhPermission.name, 'Shizuku 权限');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(enPermission.name, 'Shizuku Permission');
  });

  test('builds vlm automation display permission from current locale', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kVlmAutomationPermissionId,
    ]).single;
    expect(zhPermission.name, 'VLM 操作权限');
    expect(zhPermission.description, contains('Shizuku'));
    expect(zhPermission.description, contains('无障碍'));

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kVlmAutomationPermissionId,
    ]).single;
    expect(enPermission.name, 'VLM Automation');
    expect(enPermission.description, contains('Shizuku'));
    expect(enPermission.description, contains('Accessibility'));
  });

  test('task execution permissions require overlay and vlm automation', () {
    expect(kTaskExecutionRequiredPermissionIds, const <String>[
      kOverlayPermissionId,
      kVlmAutomationPermissionId,
    ]);
    expect(
      kTaskExecutionRequiredPermissionIds.contains(kAccessibilityPermissionId),
      isFalse,
    );
  });

  test(
    'companion automation level accepts vlm automation instead of accessibility',
    () {
      final specs = PermissionRegistry.getPermissionsByLevel(
        brand: 'other',
        level: PermissionLevel.companionAutomation,
      );
      final ids = specs.map((item) => item.id).toSet();

      expect(ids, contains(kOverlayPermissionId));
      expect(ids, contains(kVlmAutomationPermissionId));
      expect(ids, isNot(contains(kAccessibilityPermissionId)));
    },
  );

  test(
    'full execution level accepts vlm automation instead of accessibility',
    () {
      final specs = PermissionRegistry.getPermissionsByLevel(
        brand: 'other',
        level: PermissionLevel.fullExecution,
      );
      final ids = specs.map((item) => item.id).toSet();

      expect(ids, contains(kOverlayPermissionId));
      expect(ids, contains(kInstalledAppsPermissionId));
      expect(ids, contains(kVlmAutomationPermissionId));
      expect(ids, isNot(contains(kAccessibilityPermissionId)));
    },
  );

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

  test('vlm automation is only injected for execution levels', () {
    final defaultSpecs = PermissionRegistry.getPermissions(brand: 'other');
    expect(
      defaultSpecs.any((item) => item.id == kVlmAutomationPermissionId),
      isFalse,
    );

    final companionSpecs = PermissionRegistry.getPermissionsByLevel(
      brand: 'other',
      level: PermissionLevel.companionAutomation,
    );
    expect(
      companionSpecs.any((item) => item.id == kVlmAutomationPermissionId),
      isTrue,
    );
  });
}
