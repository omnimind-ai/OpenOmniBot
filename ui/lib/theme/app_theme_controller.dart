import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_mode.dart';

final appThemeModeProvider =
    StateNotifierProvider<AppThemeController, AppThemeMode>(
      (ref) => AppThemeController(),
    );

class AppThemeController extends StateNotifier<AppThemeMode> {
  AppThemeController() : super(StorageService.getThemeMode());

  Future<void> setThemeMode(AppThemeMode mode) async {
    if (state == mode) {
      return;
    }
    state = mode;
    await StorageService.setThemeMode(mode);
    await AppStateService.applyThemeMode(mode);
  }
}
