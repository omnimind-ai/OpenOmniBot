import 'package:flutter/widgets.dart';

Future<void> ensureDesktopChannelBridge(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();
}
