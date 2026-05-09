import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/router/go_router_config.dart';
import 'package:ui/features/home/local_model_router_config.dart';
import 'package:ui/features/welcome/local_model_router_config.dart';

void main() {
  tearDown(() {
    AppRouterConfig.configure();
  });

  test('base router config excludes local model routes', () {
    AppRouterConfig.configure();

    final routeNames = AppRouterConfig.getAllRoutes()
        .map((route) => route.name)
        .whereType<String>()
        .toSet();

    expect(routeNames.contains('home/local_models'), isFalse);
    expect(routeNames.contains('welcome/local_intro'), isFalse);
  });

  test('omniinfer router config can add local model routes', () {
    AppRouterConfig.configure(
      extraHomeRoutes: homeLocalModelRoutes,
      extraWelcomeRoutes: welcomeLocalModelRoutes,
    );

    final routeNames = AppRouterConfig.getAllRoutes()
        .map((route) => route.name)
        .whereType<String>()
        .toSet();

    expect(routeNames.contains('home/local_models'), isTrue);
    expect(routeNames.contains('welcome/local_intro'), isTrue);
  });
}
