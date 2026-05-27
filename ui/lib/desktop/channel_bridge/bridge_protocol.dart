/// WebSocket frame schema shared with the Rust backend (`omnibot-backend/api/envelope.rs`).
///
/// Each WS message is a JSON object with a `type` discriminator. Two directions:
///
/// Dart → backend:
///   method_call    { requestId, channel, method, arguments }
///   event_listen   { subscriptionId, channel, arguments }
///   event_cancel   { subscriptionId }
///   ping           {}
///
/// backend → Dart:
///   method_response{ requestId, ok, value | error }
///   method_invoke  { channel, method, arguments }
///   event_data     { subscriptionId, data }
///   event_error    { subscriptionId, code, message }
///   event_end      { subscriptionId }
///   system_event   { kind, data }
///   pong           {}
library;

class BridgeFrameType {
  static const String methodCall = 'method_call';
  static const String methodResponse = 'method_response';
  static const String methodInvoke = 'method_invoke';
  static const String eventListen = 'event_listen';
  static const String eventCancel = 'event_cancel';
  static const String eventData = 'event_data';
  static const String eventError = 'event_error';
  static const String eventEnd = 'event_end';
  static const String systemEvent = 'system_event';
  static const String ping = 'ping';
  static const String pong = 'pong';
}

class BridgedChannels {
  /// MethodChannels whose calls are forwarded to the Rust backend over WS.
  static const Set<String> methodChannels = {
    'cn.com.omnimind.bot/AssistCoreEvent',
    'cn.com.omnimind.bot/CacheDataEvent',
    'cn.com.omnimind.bot/McpServer',
    'cn.com.omnimind.bot/network',
    'cn.com.omnimind.bot/CodexAppServer',
    'cn.com.omnimind.bot/app_state',
    'cn.com.omnimind.bot/app_update',
    'cn.com.omnimind.bot/file_save',
    'cn.com.omnimind.bot/RemoteMcpConfig',
    'device_info',
  };

  /// EventChannels whose listen/cancel are routed to backend subscriptions.
  /// On desktop most of these are stubbed to never emit, except codex (M0 stub).
  static const Set<String> eventChannels = {
    'cn.com.omnimind.bot/CodexAppServerEvents',
    'cn.com.omnimind.bot/CodexAppServer',
    'cn.com.omnimind.bot/AssistCoreEvent',
  };

  /// Channels handled entirely on the Dart side as no-ops (Android-only features).
  static const Set<String> stubChannels = {
    'cn.com.omnimind.bot/overlay',
    'cn.com.omnimind.bot/SpecialPermissionEvent',
    'cn.com.omnimind.bot/SpecialPermissionEvents',
    'hide_from_recents',
    'cn.com.omnimind.bot/mnn_local_models',
    'cn.com.omnimind.bot/MnnLocalModels',
    'cn.com.omnimind.bot/MnnLocalModelsEvents',
    'cn.com.omnimind.bot/VoicePlayback',
    'cn.com.omnimind.bot/VoicePlaybackEvents',
    'cn.com.omnimind.bot/StorageUsage',
    'cn.com.omnimind.bot/pdf_preview',
    'cn.com.omnimind.bot/screen_dialog',
    'cn.com.omnimind.bot/ScreenDialogEvent',
    'cn.com.omnimind.bot/AgentBrowserSession',
  };
}
