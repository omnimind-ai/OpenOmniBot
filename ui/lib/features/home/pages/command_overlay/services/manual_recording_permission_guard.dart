import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/features/home/widgets/permission_bottom_sheet.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';

class ManualRecordingPermissionCheck {
  const ManualRecordingPermissionCheck({
    required this.deviceBrand,
    required this.permissions,
    required this.missingPermissionIds,
  });

  final String deviceBrand;
  final List<PermissionData> permissions;
  final Set<String> missingPermissionIds;

  bool get isAuthorized => missingPermissionIds.isEmpty;

  String missingPermissionText({Locale? locale}) {
    final labels = <String>[
      if (missingPermissionIds.contains(kAccessibilityPermissionId))
        AppTextLocalizer.choose(
          zh: '无障碍辅助权限',
          en: 'Accessibility',
          locale: locale,
        ),
      if (missingPermissionIds.contains(kOverlayPermissionId))
        AppTextLocalizer.choose(
          zh: '悬浮窗权限',
          en: 'Overlay permission',
          locale: locale,
        ),
    ];
    return labels.join(
      AppTextLocalizer.choose(zh: '、', en: ', ', locale: locale),
    );
  }
}

class ManualRecordingPermissionGuard {
  ManualRecordingPermissionGuard._();

  static const Set<String> requiredPermissionIds = <String>{
    kAccessibilityPermissionId,
    kOverlayPermissionId,
  };

  static Future<ManualRecordingPermissionCheck> check(
    BuildContext context,
  ) async {
    final deviceInfo = await DeviceService.getDeviceInfo();
    final brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';
    final specs = PermissionRegistry.getPermissions(brand: brand)
        .where((spec) => requiredPermissionIds.contains(spec.id))
        .toList(growable: false);
    final permissions = PermissionService.specsToPermissionData(
      specs,
      context: context,
    );
    await PermissionService.checkPermissions(permissions);
    final checkedIds = permissions.map((item) => item.id).toSet();
    final missingIds = <String>{
      ...requiredPermissionIds.where((id) => !checkedIds.contains(id)),
      ...permissions
          .where((item) => requiredPermissionIds.contains(item.id))
          .where((item) => !item.notifier.value)
          .map((item) => item.id),
    };
    return ManualRecordingPermissionCheck(
      deviceBrand: brand,
      permissions: permissions,
      missingPermissionIds: missingIds,
    );
  }

  static Future<bool> ensureAuthorized(
    BuildContext context, {
    String? buttonText,
  }) async {
    final permissionCheck = await check(context);
    if (permissionCheck.isAuthorized) {
      return true;
    }
    if (!context.mounted) {
      return false;
    }

    var authorized = false;
    await PermissionBottomSheet.show(
      context,
      initialPermissions: permissionCheck.permissions,
      deviceBrand: permissionCheck.deviceBrand,
      requiredPermissionIds: requiredPermissionIds,
      buttonText:
          buttonText ??
          AppTextLocalizer.choose(en: 'Continue recording', zh: '继续录制'),
      onAllAuthorized: () {
        authorized = true;
      },
    );
    return authorized;
  }
}
