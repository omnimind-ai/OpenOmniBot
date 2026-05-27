import 'dart:io';

import 'package:flutter/services.dart';

import 'bridge_init.dart';
import 'bridge_method_channel_registry.dart';
import 'bridge_protocol.dart';

class BridgeMethodChannel {
  const BridgeMethodChannel(this.name);

  final String name;

  MethodChannel get _platformChannel => MethodChannel(name);

  Future<T?> invokeMethod<T>(String method, [dynamic arguments]) async {
    if (!_isDesktopBridgeCandidate) {
      return _platformChannel.invokeMethod<T>(method, arguments);
    }

    final ws = pendingBridgeClient ?? await installChannelBridge();
    if (ws == null) {
      throw PlatformException(
        code: 'BRIDGE_UNAVAILABLE',
        message:
            'Desktop backend channel bridge is unavailable for $name.$method. '
            'Start the desktop runner or set OMNIBOT_BACKEND_PORT.',
      );
    }

    try {
      final value = await ws.invokeMethod(
        name,
        method,
        _materialize(arguments),
      );
      return value as T?;
    } on PlatformException {
      rethrow;
    } catch (e) {
      throw PlatformException(code: 'BRIDGE_ERROR', message: e.toString());
    }
  }

  void setMethodCallHandler(
    Future<dynamic> Function(MethodCall call)? handler,
  ) {
    _platformChannel.setMethodCallHandler(handler);
    if (!_isDesktopBridgeCandidate) {
      return;
    }

    if (handler == null) {
      unregisterBridgeMethodHandler(name, pendingBridgeClient);
      return;
    }

    registerBridgeMethodHandler(
      name,
      wrapMethodCallHandler(handler),
      pendingBridgeClient,
    );
  }

  bool get _isDesktopBridgeCandidate {
    if (!(Platform.isMacOS || Platform.isWindows || Platform.isLinux)) {
      return false;
    }
    return BridgedChannels.methodChannels.contains(name) ||
        BridgedChannels.eventChannels.contains(name);
  }
}

Object? _materialize(Object? args) {
  if (args == null) {
    return const <String, Object?>{};
  }
  if (args is Map) {
    return args.map((key, value) => MapEntry(key.toString(), value));
  }
  return args;
}
