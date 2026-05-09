import 'package:ui/app_bootstrap.dart';
import 'package:ui/core/router/go_router_config.dart';
import 'package:ui/features/home/local_model_router_config.dart';
import 'package:ui/features/local_model/local_model_feature_omniinfer.dart';
import 'package:ui/features/welcome/local_model_router_config.dart';

Future<void> main(List<String> args) async {
  _configureOmniInferEdition();
  await bootstrapMain(args);
}

@pragma('vm:entry-point')
void subEngineMain(List<String> args) async {
  _configureOmniInferEdition();
  await bootstrapSubEngine(args);
}

void _configureOmniInferEdition() {
  configureOmniinferLocalModelFeature();
  AppRouterConfig.configure(
    extraHomeRoutes: homeLocalModelRoutes,
    extraWelcomeRoutes: welcomeLocalModelRoutes,
  );
}
