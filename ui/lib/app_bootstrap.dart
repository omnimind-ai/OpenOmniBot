import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/embedded_terminal_init_overlay.dart';

import 'core/router/go_router_manager.dart';
import 'services/event_bus.dart';

Future<void> bootstrapMain(List<String> args) async {
  String? initialRoute;

  // 可以在这里处理从原生传递过来的参数
  if (args.isNotEmpty) {
    // 处理参数的逻辑
    debugPrint('Received args from native: $args');

    // 检查是否有路由参数
    for (var arg in args) {
      if (arg.startsWith('--route=')) {
        initialRoute = arg.substring(8); // 提取路由路径
      }
    }
  } else {
    debugPrint('No args received from native');
  }

  // 设置初始路由
  if (initialRoute != null) {
    GoRouterManager.setInitialRoute(initialRoute);
  }
  WidgetsFlutterBinding.ensureInitialized();
  WidgetsBinding.instance.deferFirstFrame();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  final container = ProviderContainer();
  await StorageService.init();
  await AppBackgroundService.load();
  await ScheduledTaskSchedulerService.initialize();
  await OmnibotResourceService.ensureWorkspacePathsLoaded();
  SystemChrome.setSystemUIOverlayStyle(
    AppTheme.overlayStyleForBrightness(
      _resolveStartupBrightness(StorageService.getThemeMode()),
    ),
  );

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: MyApp(args: args),
    ),
  );
  WidgetsBinding.instance.allowFirstFrame();
}

@pragma('vm:entry-point')
Future<void> bootstrapSubEngine(List<String> args) async {
  GoRouterManager.setSubEngine(true);
  String? initialRoute;
  if (args.isNotEmpty) {
    for (var arg in args) {
      if (arg.startsWith('--route=')) {
        initialRoute = arg.substring(8);
      }
    }
  }
  if (initialRoute != null) {
    GoRouterManager.setInitialRoute(initialRoute);
  }
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  final container = ProviderContainer();
  await StorageService.init();
  await AppBackgroundService.load();
  await ScheduledTaskSchedulerService.initialize();
  await OmnibotResourceService.ensureWorkspacePathsLoaded();
  SystemChrome.setSystemUIOverlayStyle(
    AppTheme.overlayStyleForBrightness(
      _resolveStartupBrightness(StorageService.getThemeMode()),
    ),
  );

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: MyApp(args: args),
    ),
  );
}

Brightness _resolveStartupBrightness(AppThemeMode mode) {
  return switch (mode) {
    AppThemeMode.light => Brightness.light,
    AppThemeMode.dark => Brightness.dark,
    AppThemeMode.system =>
      WidgetsBinding.instance.platformDispatcher.platformBrightness,
  };
}

class MyApp extends ConsumerStatefulWidget {
  final List<String> args;
  const MyApp({super.key, this.args = const []});

  @override
  ConsumerState<MyApp> createState() => _MyAppState();
}

class _MyAppState extends ConsumerState<MyApp> {
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();

    final initStart = DateTime.now();
    debugPrint('🎨 [FlutterStartup] MyApp initState start');
    _router = GoRouterManager.createRouter(ref);
    _initializeApp();
    debugPrint(
      "⏱️  [FlutterStartup] MyApp initState cost: ${DateTime.now().difference(initStart).inMilliseconds}ms",
    );
  }

  Future<void> _initializeApp() async {
    final appInitStart = DateTime.now();
    try {
      ref.read(eventListenerProvider);
      debugPrint(
        "⏱️  [FlutterStartup] eventListenerProvider init cost: ${DateTime.now().difference(appInitStart).inMilliseconds}ms",
      );
    } catch (e) {
      debugPrint('⚠️  [FlutterStartup] initializeApp error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final buildStart = DateTime.now();
    debugPrint('🎨 [FlutterStartup] MyApp build start');

    final widgetBuildStart = DateTime.now();
    final themeMode = ref.watch(appThemeModeProvider).materialThemeMode;
    final resolvedLocale = ref.watch(appResolvedLocaleProvider);
    LegacyTextLocalizer.setResolvedLocale(resolvedLocale.locale);
    final widget = MaterialApp.router(
      onGenerateTitle: (context) =>
          AppLocalizations.of(context)?.appName ?? 'Omnibot',
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: themeMode,
      themeAnimationCurve: Curves.easeInOutCubic,
      themeAnimationDuration: const Duration(milliseconds: 220),
      routerConfig: _router,
      locale: resolvedLocale.locale,
      builder: (context, child) {
        final brightness = Theme.of(context).brightness;
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: AppTheme.overlayStyleForBrightness(brightness),
          child: Stack(
            fit: StackFit.expand,
            children: [
              child ?? const SizedBox.shrink(),
              const EmbeddedTerminalInitToastListener(),
            ],
          ),
        );
      },
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    );

    debugPrint(
      "⏱️  [FlutterStartup] Widget tree build cost: ${DateTime.now().difference(widgetBuildStart).inMilliseconds}ms",
    );
    debugPrint(
      "✅ [FlutterStartup] MyApp build total cost: ${DateTime.now().difference(buildStart).inMilliseconds}ms",
    );

    return widget;
  }
}
