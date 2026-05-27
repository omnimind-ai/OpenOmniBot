import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'bridge_websocket_client.dart';

/// Resolves the backend port and prepares a [BridgeWebsocketClient].
///
/// The port is delivered via Flutter `dartEntrypointArguments`
/// (`--backend-port=<n>`) set by the native runner. We fall back to an
/// environment variable so tests / `flutter run --dart-entrypoint-args=...`
/// flows still work.
///
/// Does NOT touch any [WidgetsBinding] — call before
/// `DesktopWidgetsBinding.ensureInitialized()`.
Future<BridgeWebsocketClient?> installChannelBridge({List<String>? args}) async {
  if (kIsWeb) return null;
  if (!(Platform.isMacOS || Platform.isWindows || Platform.isLinux)) return null;

  int? port;
  if (args != null) {
    for (final a in args) {
      if (a.startsWith('--backend-port=')) {
        port = int.tryParse(a.substring('--backend-port='.length));
        break;
      }
    }
  }
  port ??= int.tryParse(Platform.environment['OMNIBOT_BACKEND_PORT'] ?? '');
  if (port == null || port <= 0) {
    debugPrint('OmniBot bridge: backend port missing (args=$args); channel bridge not installed.');
    return null;
  }
  _pendingWs = BridgeWebsocketClient(port: port);
  debugPrint('OmniBot bridge: target backend port=$port');
  return _pendingWs;
}

BridgeWebsocketClient? _pendingWs;

/// Accessor used by `DesktopWidgetsBinding.createBinaryMessenger`.
BridgeWebsocketClient? get pendingBridgeClient => _pendingWs;
