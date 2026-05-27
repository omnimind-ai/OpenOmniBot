import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'bridge_protocol.dart';

class _PendingCall {
  final Completer<Object?> completer = Completer<Object?>();
  final String channel;
  final String method;
  _PendingCall(this.channel, this.method);
}

class _Subscription {
  final String channel;
  final dynamic args;
  final StreamController<Object?> controller;
  _Subscription(this.channel, this.args, this.controller);
}

/// Resilient single WebSocket connection between Flutter and the Rust backend.
///
/// Responsibilities:
/// - Issue `method_call` frames and resolve their futures on response
/// - Multiplex EventChannel-style subscriptions over one socket
/// - Receive backend-initiated `method_invoke` frames (e.g. `onAgentStreamEvent`) and dispatch to
///   listeners registered via [registerNativeMethodHandler]
/// - Auto-reconnect with exponential backoff; replay active subscriptions after reconnect
class BridgeWebsocketClient {
  BridgeWebsocketClient({required this.port});

  final int port;
  WebSocket? _socket;
  bool _disposed = false;
  bool _connecting = false;
  int _backoffMs = 250;

  final Map<String, _PendingCall> _pendingCalls = {};
  final Map<String, _Subscription> _subscriptions = {};
  final Map<String, Future<dynamic> Function(String method, dynamic args)>
      _nativeHandlers = {};

  Future<void> ensureConnected() async {
    if (_disposed) return;
    if (_socket != null && _socket!.readyState == WebSocket.open) return;
    if (_connecting) {
      // Wait until current attempt resolves.
      while (_connecting) {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      return;
    }
    _connecting = true;
    try {
      final socket = await WebSocket.connect('ws://127.0.0.1:$port/channel')
          .timeout(const Duration(seconds: 8));
      _socket = socket;
      _backoffMs = 250;
      socket.listen(_onMessage,
          onError: (Object e) => _onClosed(e),
          onDone: () => _onClosed(null),
          cancelOnError: true);
      // Replay subscriptions.
      for (final entry in _subscriptions.entries) {
        _sendRaw({
          'type': BridgeFrameType.eventListen,
          'subscriptionId': entry.key,
          'channel': entry.value.channel,
          'arguments': entry.value.args,
        });
      }
    } catch (e) {
      _connecting = false;
      _scheduleReconnect();
      rethrow;
    }
    _connecting = false;
  }

  void registerNativeMethodHandler(
      String channel, Future<dynamic> Function(String method, dynamic args) handler) {
    _nativeHandlers[channel] = handler;
  }

  Future<Object?> invokeMethod(
      String channel, String method, dynamic arguments) async {
    await ensureConnected();
    final pending = _PendingCall(channel, method);
    final requestId = _newId('m');
    _pendingCalls[requestId] = pending;
    _sendRaw({
      'type': BridgeFrameType.methodCall,
      'requestId': requestId,
      'channel': channel,
      'method': method,
      'arguments': arguments ?? const <String, Object?>{},
    });
    return pending.completer.future;
  }

  Stream<Object?> subscribeEvent(String channel, dynamic arguments) {
    final id = _newId('s');
    late final StreamController<Object?> controller;
    controller = StreamController<Object?>(
      onCancel: () {
        _subscriptions.remove(id);
        _sendRaw({'type': BridgeFrameType.eventCancel, 'subscriptionId': id});
      },
    );
    _subscriptions[id] = _Subscription(channel, arguments, controller);
    ensureConnected().then((_) {
      _sendRaw({
        'type': BridgeFrameType.eventListen,
        'subscriptionId': id,
        'channel': channel,
        'arguments': arguments ?? const <String, Object?>{},
      });
    }).catchError((Object e) {
      controller.addError(e);
    });
    return controller.stream;
  }

  Future<void> dispose() async {
    _disposed = true;
    final sock = _socket;
    _socket = null;
    await sock?.close();
    for (final sub in _subscriptions.values) {
      await sub.controller.close();
    }
    _subscriptions.clear();
    for (final c in _pendingCalls.values) {
      if (!c.completer.isCompleted) {
        c.completer.completeError(StateError('bridge disposed'));
      }
    }
    _pendingCalls.clear();
  }

  void _onMessage(dynamic raw) {
    if (raw is! String) return;
    try {
      final map = jsonDecode(raw) as Map<String, dynamic>;
      switch (map['type']) {
        case BridgeFrameType.methodResponse:
          final id = map['requestId'] as String;
          final pending = _pendingCalls.remove(id);
          if (pending == null) return;
          if (map['ok'] == true) {
            pending.completer.complete(map['value']);
          } else {
            final err = (map['error'] as Map?)?.cast<String, dynamic>() ?? const {};
            pending.completer.completeError(
                _BridgeError(err['code'] as String? ?? 'ERROR',
                    err['message'] as String? ?? 'unknown'));
          }
          break;
        case BridgeFrameType.methodInvoke:
          final channel = map['channel'] as String;
          final method = map['method'] as String;
          final args = map['arguments'];
          final handler = _nativeHandlers[channel];
          if (handler != null) {
            // Fire and forget; handlers can complete asynchronously.
            handler(method, args).catchError((Object e, StackTrace st) {
              debugPrint('native handler $channel.$method error: $e');
            });
          }
          break;
        case BridgeFrameType.eventData:
          final id = map['subscriptionId'] as String;
          _subscriptions[id]?.controller.add(map['data']);
          break;
        case BridgeFrameType.eventError:
          final id = map['subscriptionId'] as String;
          final code = map['code'] as String? ?? 'ERROR';
          final message = map['message'] as String? ?? '';
          _subscriptions[id]?.controller.addError(_BridgeError(code, message));
          break;
        case BridgeFrameType.eventEnd:
          final id = map['subscriptionId'] as String;
          final sub = _subscriptions.remove(id);
          sub?.controller.close();
          break;
        case BridgeFrameType.pong:
          break;
        default:
          break;
      }
    } catch (e, st) {
      debugPrint('bridge parse error: $e\n$st');
    }
  }

  void _onClosed(Object? err) {
    final sock = _socket;
    _socket = null;
    if (sock != null) {
      sock.close();
    }
    if (_disposed) return;
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_disposed) return;
    final delay = _backoffMs;
    _backoffMs = (_backoffMs * 2).clamp(250, 5000).toInt();
    Future<void>.delayed(Duration(milliseconds: delay), () {
      if (_disposed) return;
      ensureConnected().catchError((_) {});
    });
  }

  void _sendRaw(Map<String, Object?> frame) {
    final sock = _socket;
    if (sock == null) return;
    try {
      sock.add(jsonEncode(frame));
    } catch (e) {
      debugPrint('bridge send error: $e');
    }
  }

  String _newId(String prefix) =>
      '$prefix-${DateTime.now().microsecondsSinceEpoch}-${_subscriptions.length + _pendingCalls.length}';
}

class _BridgeError implements Exception {
  final String code;
  final String message;
  _BridgeError(this.code, this.message);
  @override
  String toString() => 'BridgeError($code): $message';
}
