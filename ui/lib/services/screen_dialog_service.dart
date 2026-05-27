import 'package:flutter/services.dart';

typedef TitleCallback = void Function(String title);
typedef ContentCallback = void Function(String content);
typedef BeforeCloseChatBotCallback = void Function();

class ScreenDialogService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/ScreenDialogEvent',
  );

  static TitleCallback? _onTitleCallback;
  static ContentCallback? _onContentCallback;
  static BeforeCloseChatBotCallback? _onBeforeCloseChatBotDialog;

  static void initialize() {
    _channel.setMethodCallHandler(_handleMethod);
  }

  static Future<void> _handleMethod(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onTitle':
          final String title = call.arguments ?? '';
          print(title);
          _onTitleCallback?.call(title);
          break;
        case 'onContent':
          final String content = call.arguments ?? '';
          print(content);
          _onContentCallback?.call(content);
          break;
        default:
          print('Unhandled method: ${call.method}');
      }
    } catch (e) {
      print('Failed to handle method call: $e');
    }
  }

  static void setOnTitleCallback(TitleCallback callback) {
    _onTitleCallback = callback;
  }

  static void setOnContentCallback(ContentCallback callback) {
    _onContentCallback = callback;
  }

  static void setOnBeforeCloseChatBotDialog(
    BeforeCloseChatBotCallback? callback,
  ) {
    _onBeforeCloseChatBotDialog = callback;
  }

  static void clearCallbacks() {
    print('ScreenDialogService.clearCallbacks()');
    _onTitleCallback = null;
    _onContentCallback = null;
    _onBeforeCloseChatBotDialog = null;
  }

  static Future<bool> closeDialog() async {
    try {
      _channel.setMethodCallHandler(null);
      var result = await _channel.invokeMethod('closeDialog');
      return result == "Success";
    } on PlatformException catch (e) {
      print('Failed to close dialog: ${e.message}');
      return false;
    }
  }

  static Future<bool> closeChatBotDialog() async {
    try {
      if (_onBeforeCloseChatBotDialog != null) {
        _onBeforeCloseChatBotDialog!.call();
      }
      _channel.setMethodCallHandler(null);
      var result = await _channel.invokeMethod('closeChatBotDialog');
      return result == "Success";
    } on PlatformException catch (e) {
      print('Failed to close chat bot dialog: ${e.message}');
      return false;
    }
  }

  static Future<bool> hideForExternalActivity() async {
    try {
      final result = await _channel.invokeMethod('hideForExternalActivity');
      return result == true;
    } on PlatformException catch (e) {
      print('Failed to hide dialog for external activity: ${e.message}');
      return false;
    }
  }

  static Future<bool> restoreAfterExternalActivity() async {
    try {
      final result = await _channel.invokeMethod(
        'restoreAfterExternalActivity',
      );
      return result == true;
    } on PlatformException catch (e) {
      print('Failed to restore dialog after external activity: ${e.message}');
      return false;
    }
  }

  static Future<bool> hideForManualRecording() async {
    try {
      final result = await _channel.invokeMethod('hideForManualRecording');
      return result == true;
    } on PlatformException catch (e) {
      print('Failed to hide dialog for manual recording: ${e.message}');
      return false;
    }
  }

  static Future<bool> restoreAfterManualRecording() async {
    try {
      final result = await _channel.invokeMethod(
        'restoreAfterManualRecording',
      );
      return result == true;
    } on PlatformException catch (e) {
      print('Failed to restore dialog after manual recording: ${e.message}');
      return false;
    }
  }
}
