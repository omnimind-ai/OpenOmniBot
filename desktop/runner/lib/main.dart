import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:ui/app_bootstrap.dart';
import 'package:ui/desktop/channel_bridge/bridge_init.dart';
import 'package:ui/desktop/channel_bridge/desktop_widgets_binding.dart';
import 'package:ui/features/local_model/local_model_feature_standard.dart';

void _dlog(String msg) {
  try {
    final home = Platform.environment['HOME'];
    if (home == null) return;
    final f = File('$home/Library/Logs/OmnibotApp/supervisor.log');
    f.parent.createSync(recursive: true);
    f.writeAsStringSync('[dart] $msg\n', mode: FileMode.append);
  } catch (_) {}
}

Future<void> main(List<String> args) async {
  _dlog('main: args=$args');
  final ws = await installChannelBridge(args: args);
  _dlog('main: bridge installed, ws=${ws != null}');
  DesktopWidgetsBinding.ensureInitialized();
  _dlog('main: binding=${WidgetsBinding.instance.runtimeType}');
  configureStandardLocalModelFeature();
  await bootstrapMain(args);
  _dlog('main: bootstrapMain returned');
}
