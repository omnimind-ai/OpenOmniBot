import 'package:flutter_test/flutter_test.dart';
import 'package:ui/desktop/channel_bridge/bridge_init.dart';

void main() {
  test('channel bridge stays disabled without a backend port', () async {
    final ws = await installChannelBridge(args: const ['--backend-port=0']);
    expect(ws, isNull);
  });
}
