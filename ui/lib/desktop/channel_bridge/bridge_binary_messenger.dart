import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

import 'bridge_protocol.dart';
import 'bridge_websocket_client.dart';

/// Wraps the host platform's [BinaryMessenger] to redirect outgoing MethodChannel calls
/// of bridged channels into the local WebSocket bridge.
///
/// Calls to channels not registered in [BridgedChannels.methodChannels] /
/// [BridgedChannels.eventChannels] / [BridgedChannels.stubChannels] are forwarded to the
/// original messenger unchanged (e.g. plugin channels handled natively by Flutter Desktop).
class BridgingBinaryMessenger implements BinaryMessenger {
  BridgingBinaryMessenger(this._original, this._ws);

  final BinaryMessenger _original;
  final BridgeWebsocketClient _ws;
  // Track Dart-side handlers registered by services. Backend `method_invoke` frames
  // route here through `BridgeWebsocketClient.registerNativeMethodHandler`.
  final Map<String, MessageHandler> _dartHandlers = {};

  @override
  Future<ByteData?> send(String channel, ByteData? message) async {
    if (BridgedChannels.stubChannels.contains(channel)) {
      return _stubReply(channel, message);
    }
    if (BridgedChannels.methodChannels.contains(channel) ||
        BridgedChannels.eventChannels.contains(channel)) {
      return _sendBridged(channel, message);
    }
    return _original.send(channel, message);
  }

  @override
  void setMessageHandler(String channel, MessageHandler? handler) {
    if (handler == null) {
      _dartHandlers.remove(channel);
    } else {
      _dartHandlers[channel] = handler;
    }
    _original.setMessageHandler(channel, handler);
    // For bridged channels also subscribe to backend-initiated method_invoke frames.
    if (BridgedChannels.methodChannels.contains(channel) ||
        BridgedChannels.eventChannels.contains(channel)) {
      if (handler == null) {
        _ws.unregisterNativeMethodHandler(channel);
        return;
      }
      _ws.registerNativeMethodHandler(channel, (method, args) async {
        // Encode as a MethodCall via the standard codec and pass to Dart handler.
        const codec = StandardMethodCodec();
        final call = MethodCall(method, args);
        final bytes = codec.encodeMethodCall(call);
        final reply = await handler.call(bytes);
        return reply == null ? null : codec.decodeEnvelope(reply);
      });
    }
  }

  @override
  Future<void> handlePlatformMessage(
    String channel,
    ByteData? data,
    PlatformMessageResponseCallback? callback,
  ) async {
    // Newer API: route through channelBuffers so registered handlers fire.
    ServicesBinding.instance.channelBuffers.push(channel, data, (reply) {
      callback?.call(reply);
    });
  }

  Future<ByteData?> _sendBridged(String channel, ByteData? message) async {
    const codec = StandardMethodCodec();
    if (message == null) return null;
    final call = codec.decodeMethodCall(message);

    if (BridgedChannels.eventChannels.contains(channel) &&
        (call.method == 'listen' || call.method == 'cancel')) {
      return _handleEventChannel(channel, call);
    }

    try {
      final value = await _ws.invokeMethod(
        channel,
        call.method,
        _materialize(call.arguments),
      );
      return codec.encodeSuccessEnvelope(value);
    } catch (e) {
      final code = e is Exception ? 'BRIDGE_ERROR' : 'ERROR';
      return codec.encodeErrorEnvelope(code: code, message: e.toString());
    }
  }

  // EventChannel uses Dart message handler set on a stream channel; the standard codec wraps
  // listen/cancel as MethodCalls. We hold one [_EventBinding] per channel name.
  final Map<String, _EventBinding> _eventBindings = {};

  Future<ByteData?> _handleEventChannel(String channel, MethodCall call) async {
    const codec = StandardMethodCodec();
    final binding = _eventBindings.putIfAbsent(
      channel,
      () => _EventBinding(channel, _ws),
    );
    switch (call.method) {
      case 'listen':
        await binding.listen(
          _materialize(call.arguments),
          (event) {
            final reply = codec.encodeSuccessEnvelope(event);
            ServicesBinding.instance.channelBuffers.push(
              channel,
              reply,
              (_) {},
            );
          },
          (errCode, errMsg) {
            final reply = codec.encodeErrorEnvelope(
              code: errCode,
              message: errMsg,
            );
            ServicesBinding.instance.channelBuffers.push(
              channel,
              reply,
              (_) {},
            );
          },
          () {
            ServicesBinding.instance.channelBuffers.push(channel, null, (_) {});
          },
        );
        return codec.encodeSuccessEnvelope(null);
      case 'cancel':
        await binding.cancel();
        return codec.encodeSuccessEnvelope(null);
      default:
        return codec.encodeErrorEnvelope(
          code: 'UNIMPLEMENTED',
          message: call.method,
        );
    }
  }

  // For stubbed Android-only channels: return a sensible default reply so calls don't throw.
  ByteData? _stubReply(String channel, ByteData? message) {
    const codec = StandardMethodCodec();
    if (message == null) return null;
    final call = codec.decodeMethodCall(message);
    final stubbedValue = _defaultStubValue(channel, call.method);
    return codec.encodeSuccessEnvelope(stubbedValue);
  }

  Object? _defaultStubValue(String channel, String method) {
    if (channel == 'cn.com.omnimind.bot/SpecialPermissionEvent') {
      switch (method) {
        case 'isAccessibilityServiceEnabled':
        case 'isShizukuInstalled':
        case 'isShizukuRunning':
        case 'isTermuxInstalled':
        case 'isTermuxRunCommandPermissionGranted':
        case 'isInstalledAppsPermissionGranted':
        case 'isNotificationPermissionGranted':
        case 'isPublicStorageAccessGranted':
        case 'isUnknownAppInstallAllowed':
        case 'isBackgroundRunAllowed':
          return false;
        case 'isWorkspaceStorageAccessGranted':
          return true;
        case 'getShizukuStatus':
          return {'installed': false, 'granted': false};
        case 'getWorkspacePathSnapshot':
          return _workspacePathSnapshot();
        case 'getEmbeddedTerminalInitSnapshot':
          return {
            'running': false,
            'completed': true,
            'success': true,
            'progress': 1.0,
            'stage': 'desktop',
            'logLines': <Object?>[],
          };
        case 'getEmbeddedTerminalRuntimeStatus':
          return {
            'supported': false,
            'runtimeReady': false,
            'basePackagesReady': false,
            'allReady': false,
            'missingCommands': <Object?>[],
            'message': 'Embedded Android terminal is unavailable on desktop.',
            'nodeReady': false,
            'nodeMinMajor': 22,
            'pnpmReady': false,
            'workspaceAccessGranted': true,
          };
        case 'getEmbeddedTerminalSetupStatus':
        case 'getEmbeddedTerminalSetupInventory':
          return {'packages': <String, Object?>{}};
        case 'getEmbeddedTerminalSetupSessionSnapshot':
          return {
            'sessionId': '',
            'running': false,
            'completed': true,
            'success': true,
            'message': '',
            'selectedPackageIds': <Object?>[],
          };
        case 'installEmbeddedTerminalPackages':
          return {
            'success': false,
            'message': 'Unavailable on desktop',
            'output': '',
          };
        case 'startEmbeddedTerminalSetupSession':
          return {
            'sessionId': '',
            'running': false,
            'completed': true,
            'success': false,
            'message': 'Unavailable on desktop',
            'selectedPackageIds': <Object?>[],
          };
        case 'getEmbeddedTerminalAutoStartTasks':
          return {'tasks': <Object?>[]};
        case 'saveEmbeddedTerminalAutoStartTask':
          return <String, Object?>{};
        case 'runEmbeddedTerminalAutoStartTask':
          return {
            'taskId': '',
            'started': false,
            'alreadyRunning': false,
            'message': 'Unavailable on desktop',
            'sessionId': '',
          };
        case 'requestPermissions':
          return 'Failed';
      }
    }
    if (channel == 'cn.com.omnimind.bot/mnn_local_models' ||
        channel == 'cn.com.omnimind.bot/MnnLocalModels') {
      switch (method) {
        case 'listInstalledModels':
        case 'refreshInstalledModels':
        case 'listMarketModels':
          return <Object?>[];
        case 'getOverview':
          return {
            'config': _mnnConfig(),
            'installedModels': <Object?>[],
            'market': {'models': <Object?>[], 'availableSources': <Object?>[]},
          };
        case 'getConfig':
        case 'saveConfig':
        case 'setActiveModel':
        case 'startApiService':
        case 'stopApiService':
        case 'stopLanProxy':
          return _mnnConfig();
        case 'preloadModel':
          return {
            'success': false,
            'cancelled': false,
            'error': 'Unavailable on desktop',
          };
        case 'getBackend':
          return 'unavailable';
      }
    }
    if (channel == 'cn.com.omnimind.bot/AgentBrowserSession') {
      return {'available': false};
    }
    if (channel == 'hide_from_recents') return null;
    if (channel == 'cn.com.omnimind.bot/overlay') return false;
    if (channel == 'cn.com.omnimind.bot/VoicePlayback') return false;
    if (channel == 'cn.com.omnimind.bot/ScreenDialogEvent') return false;
    return null;
  }

  Map<String, Object?> _workspacePathSnapshot() {
    final dataDir = Platform.environment['OMNIBOT_DATA_DIR'];
    final home = Platform.environment['HOME'] ?? Directory.systemTemp.path;
    final rootPath = dataDir == null || dataDir.trim().isEmpty
        ? '$home/OmnibotApp/workspaces/default'
        : '${dataDir.trim()}/workspaces/default';
    return {
      'rootPath': rootPath,
      'shellRootPath': rootPath,
      'internalRootPath': '$rootPath/.omnibot',
    };
  }

  Map<String, Object?> _mnnConfig() {
    return {
      'backend': 'unavailable',
      'autoStartOnAppOpen': false,
      'apiRunning': false,
      'apiReady': false,
      'apiState': 'stopped',
      'apiHost': '127.0.0.1',
      'apiPort': 9099,
      'baseUrl': '',
      'activeModelId': '',
      'downloadProvider': 'ModelScope',
      'availableSources': <Object?>[],
      'loadedBackend': 'unavailable',
      'loadedModelId': '',
      'lanProxyRunning': false,
      'lanHost': '',
      'lanProxyPort': 9100,
      'lanBaseUrl': '',
      'lanToken': '',
      'lanTargetBaseUrl': '',
      'lanProxyError': '',
    };
  }

  Object? _materialize(Object? args) {
    if (args == null) return const <String, Object?>{};
    if (args is Map) {
      return args.map((k, v) => MapEntry(k.toString(), v));
    }
    return args;
  }
}

class _EventBinding {
  _EventBinding(this.channel, this.ws);
  final String channel;
  final BridgeWebsocketClient ws;
  StreamSubscription<Object?>? _sub;

  Future<void> listen(
    Object? args,
    void Function(Object? event) onEvent,
    void Function(String code, String message) onError,
    void Function() onDone,
  ) async {
    await _sub?.cancel();
    _sub = ws
        .subscribeEvent(channel, args)
        .listen(
          onEvent,
          onError: (Object e) {
            final code = e.toString().contains('BridgeError')
                ? 'BRIDGE_ERROR'
                : 'ERROR';
            onError(code, e.toString());
          },
          onDone: onDone,
        );
  }

  Future<void> cancel() async {
    await _sub?.cancel();
    _sub = null;
  }
}
