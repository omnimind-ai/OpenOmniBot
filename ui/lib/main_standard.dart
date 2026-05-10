import 'package:ui/app_bootstrap.dart';
import 'package:ui/features/local_model/local_model_feature_standard.dart';

Future<void> main(List<String> args) async {
  configureStandardLocalModelFeature();
  await bootstrapMain(args);
}

@pragma('vm:entry-point')
void subEngineMain(List<String> args) async {
  configureStandardLocalModelFeature();
  await bootstrapSubEngine(args);
}
