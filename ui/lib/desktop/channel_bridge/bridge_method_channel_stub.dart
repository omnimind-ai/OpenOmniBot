import 'package:flutter/services.dart';

class BridgeMethodChannel {
  const BridgeMethodChannel(this.name);

  final String name;

  MethodChannel get _platformChannel => MethodChannel(name);

  Future<T?> invokeMethod<T>(String method, [dynamic arguments]) {
    return _platformChannel.invokeMethod<T>(method, arguments);
  }

  void setMethodCallHandler(
    Future<dynamic> Function(MethodCall call)? handler,
  ) {
    _platformChannel.setMethodCallHandler(handler);
  }
}
