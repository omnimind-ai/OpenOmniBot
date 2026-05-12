import 'package:flutter/foundation.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/storage_service.dart';

class FloatingOverlayService {
  static const String kFloatingOverlayEnabledKey = 'floating_overlay_enabled';

  static final ValueNotifier<bool> enabledListenable = ValueNotifier<bool>(
    StorageService.getBool(kFloatingOverlayEnabledKey, defaultValue: true) ??
        true,
  );

  static bool get isEnabled => enabledListenable.value;

  static Future<bool> loadEnabled() async {
    final localEnabled =
        StorageService.getBool(
          kFloatingOverlayEnabledKey,
          defaultValue: true,
        ) ??
        true;
    final nativeEnabled = await AppStateService.getFloatingOverlayEnabled();
    final resolved = nativeEnabled ?? localEnabled;
    if (enabledListenable.value != resolved) {
      enabledListenable.value = resolved;
    }
    if (nativeEnabled != null && nativeEnabled != localEnabled) {
      await StorageService.setBool(kFloatingOverlayEnabledKey, nativeEnabled);
    }
    return resolved;
  }

  static Future<bool> setEnabled(bool enabled) async {
    final previous = enabledListenable.value;
    enabledListenable.value = enabled;
    final saved = await StorageService.setBool(
      kFloatingOverlayEnabledKey,
      enabled,
    );
    final nativeSynced = await AppStateService.setFloatingOverlayEnabled(
      enabled,
    );
    if (!enabled && nativeSynced) {
      await AppStateService.dismissFloatingOverlay();
    }
    if (!saved || !nativeSynced) {
      enabledListenable.value = previous;
      await StorageService.setBool(kFloatingOverlayEnabledKey, previous);
      await AppStateService.setFloatingOverlayEnabled(previous);
      return false;
    }
    return true;
  }
}
