import 'package:ui/main_omniinfer.dart' as omniinfer;

Future<void> main(List<String> args) => omniinfer.main(args);

@pragma('vm:entry-point')
void subEngineMain(List<String> args) => omniinfer.subEngineMain(args);
