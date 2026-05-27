import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'bridge_binary_messenger.dart';
import 'bridge_init.dart';

/// Subclass of [WidgetsFlutterBinding] that wraps the default binary messenger
/// with [BridgingBinaryMessenger] so Dart [MethodChannel] / [EventChannel] calls
/// are routed to the Rust backend over WebSocket.
///
/// This MUST be the first call that creates the global binding. If you already
/// invoked `WidgetsFlutterBinding.ensureInitialized()` elsewhere, the override
/// will not take effect.
class DesktopWidgetsBinding extends WidgetsFlutterBinding {
  static bool _initialized = false;

  static WidgetsBinding ensureInitialized() {
    if (!_initialized) {
      _initialized = true;
      DesktopWidgetsBinding._();
    }
    return WidgetsBinding.instance;
  }

  DesktopWidgetsBinding._();

  @override
  BinaryMessenger createBinaryMessenger() {
    final ws = pendingBridgeClient;
    final original = super.createBinaryMessenger();
    if (ws == null) {
      debugPrint('DesktopWidgetsBinding: no bridge client; using default messenger.');
      return original;
    }
    final bridged = BridgingBinaryMessenger(original, ws);
    // Kick off the WebSocket connection asynchronously — the messenger itself
    // will `await ws.ensureConnected()` again before any first send.
    unawaited(ws.ensureConnected().catchError((Object e) {
      debugPrint('DesktopWidgetsBinding: initial WS connect failed: $e');
    }));
    return bridged;
  }
}
