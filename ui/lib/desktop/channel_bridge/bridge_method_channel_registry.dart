import 'package:flutter/services.dart';

import 'bridge_websocket_client.dart';

typedef BridgeMethodHandler =
    Future<dynamic> Function(String method, dynamic arguments);

final Map<String, BridgeMethodHandler> _registeredHandlers =
    <String, BridgeMethodHandler>{};

void registerBridgeMethodHandler(
  String channel,
  BridgeMethodHandler handler,
  BridgeWebsocketClient? ws,
) {
  _registeredHandlers[channel] = handler;
  ws?.registerNativeMethodHandler(channel, handler);
}

void unregisterBridgeMethodHandler(String channel, BridgeWebsocketClient? ws) {
  _registeredHandlers.remove(channel);
  ws?.unregisterNativeMethodHandler(channel);
}

void installRegisteredBridgeMethodHandlers(BridgeWebsocketClient ws) {
  for (final entry in _registeredHandlers.entries) {
    ws.registerNativeMethodHandler(entry.key, entry.value);
  }
}

BridgeMethodHandler wrapMethodCallHandler(
  Future<dynamic> Function(MethodCall call) handler,
) {
  return (String method, dynamic arguments) {
    return handler(MethodCall(method, arguments));
  };
}
