import 'dart:io';

import 'package:flutter/widgets.dart';

import 'bridge_init.dart';
import 'desktop_widgets_binding.dart';

Future<void> ensureDesktopChannelBridge(List<String> args) async {
  if (Platform.isMacOS || Platform.isWindows || Platform.isLinux) {
    await installChannelBridge(args: args);
    DesktopWidgetsBinding.ensureInitialized();
    return;
  }
  WidgetsFlutterBinding.ensureInitialized();
}
