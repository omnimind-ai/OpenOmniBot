import 'dart:async';

import 'package:flutter/services.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/app_state_service.dart';

// 卡片推送
typedef CardPushCallback<T> = void Function(Map<String, dynamic> cardData);
//陪伴任务结束
typedef TaskFinishCallback = void Function();
//消息回执
typedef ChatTaskMessageCallBack =
    void Function(String taskID, String content, String? type);
//消息回执结束
typedef ChatTaskMessageEndCallBack = void Function(String taskID);
//VLM任务结束
typedef VLMTaskFinishEndCallBack = void Function(String? taskId);
//普通任务结束
typedef CommonTaskFinishEndCallBack = void Function();
//VLM请求用户输入（INFO动作）
typedef VLMRequestUserInputCallBack =
    void Function(String question, String? taskId);
//Dispatch流式数据回调
typedef DispatchStreamDataCallBack =
    void Function(String taskID, String data, String fullContent);
//Dispatch流式结束回调
typedef DispatchStreamEndCallBack =
    void Function(String taskID, String fullContent);
//Dispatch流式错误回调
typedef DispatchStreamErrorCallBack =
    void Function(
      String taskID,
      String error,
      String fullContent,
      bool isRateLimited,
    );

// Agent相关回调
typedef AgentPromptTokenUsageCallback =
    void Function(
      String taskId,
      int latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentContextCompactionStateCallback =
    void Function(
      String taskId,
      bool isCompacting,
      int? latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentStreamEventCallback = void Function(AgentStreamEvent event);
typedef ScheduledTaskCancelledCallBack = void Function(String taskId);
typedef ScheduledTaskExecuteNowCallBack = void Function(String taskId);

class ModelAvailabilityCheckResult {
  final bool available;
  final int? code;
  final String message;

  const ModelAvailabilityCheckResult({
    required this.available,
    required this.code,
    required this.message,
  });

  factory ModelAvailabilityCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return const ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败：返回为空',
      );
    }

    final codeValue = map['code'];
    int? code;
    if (codeValue is int) {
      code = codeValue;
    } else if (codeValue is String) {
      code = int.tryParse(codeValue);
    }

    return ModelAvailabilityCheckResult(
      available: map['available'] == true,
      code: code,
      message: (map['message'] ?? '').toString(),
    );
  }
}

class AgentToolEventData {
  final String taskId;
  final String cardId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String terminalOutputDelta;
  final String? terminalSessionId;
  final String terminalStreamState;
  final String? workspaceId;
  final String? interruptedBy;
  final String? interruptionReason;
  final List<Map<String, dynamic>> artifacts;
  final List<Map<String, dynamic>> actions;
  final bool success;

  const AgentToolEventData({
    required this.taskId,
    this.cardId = '',
    required this.toolName,
    required this.displayName,
    this.toolTitle = '',
    required this.toolType,
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
    this.resultPreviewJson = '',
    this.rawResultJson = '',
    this.terminalOutput = '',
    this.terminalOutputDelta = '',
    this.terminalSessionId,
    this.terminalStreamState = '',
    this.workspaceId,
    this.interruptedBy,
    this.interruptionReason,
    this.artifacts = const [],
    this.actions = const [],
    this.success = true,
  });

  factory AgentToolEventData.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return AgentToolEventData(
      taskId: (raw['taskId'] ?? '').toString(),
      cardId: (raw['cardId'] ?? '').toString(),
      toolName: (raw['toolName'] ?? '').toString(),
      displayName: (raw['displayName'] ?? raw['toolName'] ?? '').toString(),
      toolTitle: (raw['toolTitle'] ?? '').toString(),
      toolType: (raw['toolType'] ?? 'builtin').toString(),
      serverName: raw['serverName']?.toString(),
      status: (raw['status'] ?? '').toString(),
      argsJson: (raw['argsJson'] ?? raw['args'] ?? '').toString(),
      progress: (raw['progress'] ?? '').toString(),
      summary: (raw['summary'] ?? '').toString(),
      resultPreviewJson: (raw['resultPreviewJson'] ?? '').toString(),
      rawResultJson: (raw['rawResultJson'] ?? '').toString(),
      terminalOutput: (raw['terminalOutput'] ?? '').toString(),
      terminalOutputDelta: (raw['terminalOutputDelta'] ?? '').toString(),
      terminalSessionId: raw['terminalSessionId']?.toString(),
      terminalStreamState: (raw['terminalStreamState'] ?? '').toString(),
      workspaceId: raw['workspaceId']?.toString(),
      interruptedBy: raw['interruptedBy']?.toString(),
      interruptionReason: raw['interruptionReason']?.toString(),
      artifacts: ((raw['artifacts'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      actions: ((raw['actions'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      success: raw['success'] != false,
    );
  }
}

class AgentAiConfigChangedEvent {
  final String source;
  final String path;

  const AgentAiConfigChangedEvent({required this.source, required this.path});

  factory AgentAiConfigChangedEvent.fromMap(Map<dynamic, dynamic>? map) {
    return AgentAiConfigChangedEvent(
      source: (map?['source'] ?? '').toString(),
      path: (map?['path'] ?? '').toString(),
    );
  }
}

class AssistsMessageService {
  static const MethodChannel assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  // 回调函数
  static CardPushCallback? _onCardPushCallback;
  static TaskFinishCallback? _onTaskFinishCallback;
  static ChatTaskMessageCallBack? _onChatTaskMessageCallBack;
  static ChatTaskMessageEndCallBack? _onChatTaskMessageEndCallBack;
  static VLMRequestUserInputCallBack? _onVLMRequestUserInputCallBack;
  static DispatchStreamDataCallBack? _onDispatchStreamDataCallBack;
  static DispatchStreamEndCallBack? _onDispatchStreamEndCallBack;
  static DispatchStreamErrorCallBack? _onDispatchStreamErrorCallBack;

  // Agent回调
  static AgentPromptTokenUsageCallback? _onAgentPromptTokenUsageCallback;
  static AgentContextCompactionStateCallback?
  _onAgentContextCompactionStateCallback;

  static ScheduledTaskCancelledCallBack? _onScheduledTaskCancelledCallBack;
  static ScheduledTaskExecuteNowCallBack? _onScheduledTaskExecuteNowCallBack;
  static final StreamController<AgentAiConfigChangedEvent>
  _agentAiConfigChangedController =
      StreamController<AgentAiConfigChangedEvent>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationListChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationMessagesChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _browserSessionSnapshotChangedController =
      StreamController<Map<String, dynamic>>.broadcast();

  // 改为回调列表，支持多个监听器
  static final List<ChatTaskMessageCallBack> _onChatTaskMessageCallBacks = [];
  static final List<ChatTaskMessageEndCallBack> _onChatTaskMessageEndCallBacks =
      [];
  static final List<AgentStreamEventCallback> _onAgentStreamEventCallbacks = [];
  static final List<VLMTaskFinishEndCallBack> _onVLMTaskFinishCallBacks = [];
  static final List<CommonTaskFinishEndCallBack> _onCommonTaskFinishCallBacks =
      [];

  static Stream<AgentAiConfigChangedEvent> get agentAiConfigChangedStream =>
      _agentAiConfigChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationListChangedStream =>
      _conversationListChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationMessagesChangedStream =>
      _conversationMessagesChangedController.stream;
  static Stream<Map<String, dynamic>> get browserSessionSnapshotChangedStream =>
      _browserSessionSnapshotChangedController.stream;

  static void initialize() {
    assistCore.setMethodCallHandler(_handleMethod);
  }

  static void dispatchAgentAiConfigChanged(AgentAiConfigChangedEvent event) {
    _agentAiConfigChangedController.add(event);
  }

  static Future<dynamic> _handleMethod(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onCardPush':
          final Map<String, dynamic> cardData = Map<String, dynamic>.from(
            call.arguments,
          );
          _onCardPushCallback?.call(cardData['data']);
          break;

        case 'onTaskFinish':
          print('任务完成');
          _onTaskFinishCallback?.call();
          break;
        case 'onAgentAiConfigChanged':
          final data = Map<String, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          // Defer broadcast to the next event-loop turn so listeners can
          // safely invoke the same platform channel without re-entrancy.
          unawaited(
            Future<void>(() {
              dispatchAgentAiConfigChanged(
                AgentAiConfigChangedEvent.fromMap(data),
              );
            }),
          );
          break;
        case 'onConversationListChanged':
          _conversationListChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onConversationMessagesChanged':
          _conversationMessagesChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onBrowserSessionSnapshotUpdated':
          _browserSessionSnapshotChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onChatMessage':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          print(
            'onChatMessage content: ${data['content']}, type: ${data['type']}',
          );
          _onChatTaskMessageCallBack?.call(
            data['taskID'],
            data['content'],
            data['type'],
          );
          for (final callback in _onChatTaskMessageCallBacks) {
            callback(data['taskID'], data['content'], data['type']);
          }
          break;
        case 'onChatMessageEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onChatTaskMessageEndCallBack?.call(data['taskID']);
          for (final callback in _onChatTaskMessageEndCallBacks) {
            callback(data['taskID']);
          }
          break;
        case 'onVLMRequestUserInput':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          print('onVLMRequestUserInput question: ${data['question']}');
          _onVLMRequestUserInputCallBack?.call(
            data['question'],
            data['taskId']?.toString(),
          );
          break;
        case 'onVLMTaskFinish':
          print('任务完成');
          // 通知所有注册的回调
          for (final callback in _onVLMTaskFinishCallBacks) {
            callback((call.arguments as Map?)?['taskId']?.toString());
          }
          break;
        case 'onCommonTaskFinish':
          print('任务完成');
          // 通知所有注册的回调
          for (final callback in _onCommonTaskFinishCallBacks) {
            callback();
          }
          break;
        case 'onDispatchStreamData':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamDataCallBack?.call(
            data['taskID'] ?? '',
            data['data'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamEndCallBack?.call(
            data['taskID'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamError':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamErrorCallBack?.call(
            data['taskID'] ?? '',
            data['error'] ?? '',
            data['fullContent'] ?? '',
            data['isRateLimited'] == true,
          );
          break;
        case 'onAgentPromptTokenUsageChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final latestPromptTokens = _asNullableInt(data['latestPromptTokens']);
          if (latestPromptTokens == null) {
            break;
          }
          _onAgentPromptTokenUsageCallback?.call(
            (data['taskId'] ?? '').toString(),
            latestPromptTokens,
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentContextCompactionStateChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentContextCompactionStateCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['isCompacting'] == true,
            _asNullableInt(data['latestPromptTokens']),
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentStreamEvent':
          final event = AgentStreamEvent.fromMap(call.arguments as Map?);
          for (final callback in _onAgentStreamEventCallbacks) {
            callback(event);
          }
          break;
        case 'onScheduledTaskCancelled':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskCancelledCallBack?.call(data['taskId'] ?? '');
          break;
        case 'onScheduledTaskExecuteNow':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskExecuteNowCallBack?.call(data['taskId'] ?? '');
          break;
        case 'agentScheduleCreate':
          return await AgentScheduleBridgeService.createTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleList':
          return await AgentScheduleBridgeService.listTasks();
        case 'agentScheduleUpdate':
          return await AgentScheduleBridgeService.updateTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleDelete':
          return await AgentScheduleBridgeService.deleteTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );

        default:
          print('未处理的方法: ${call.method}');
      }
    } catch (e) {
      print('处理方法调用时出错: $e');
      rethrow;
    }
  }

  // 设置回调函数
  static void setOnCardPushCallback(CardPushCallback callback) {
    _onCardPushCallback = callback;
  }

  static void setOnTaskFinishCallback(TaskFinishCallback callback) {
    _onTaskFinishCallback = callback;
  }

  static void setOnChatTaskMessageCallBack(ChatTaskMessageCallBack callback) {
    _onChatTaskMessageCallBack = callback;
  }

  static void addOnChatTaskMessageCallBack(ChatTaskMessageCallBack? callback) {
    if (callback != null && !_onChatTaskMessageCallBacks.contains(callback)) {
      _onChatTaskMessageCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageCallBack(
    ChatTaskMessageCallBack? callback,
  ) {
    _onChatTaskMessageCallBacks.remove(callback);
  }

  static void setOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack callback,
  ) {
    _onChatTaskMessageEndCallBack = callback;
  }

  static void addOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    if (callback != null &&
        !_onChatTaskMessageEndCallBacks.contains(callback)) {
      _onChatTaskMessageEndCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    _onChatTaskMessageEndCallBacks.remove(callback);
  }

  static void setOnVLMRequestUserInputCallBack(
    VLMRequestUserInputCallBack callback,
  ) {
    _onVLMRequestUserInputCallBack = callback;
  }

  static void setOnVLMTaskFinishCallBack(VLMTaskFinishEndCallBack? callback) {
    if (callback != null && !_onVLMTaskFinishCallBacks.contains(callback)) {
      _onVLMTaskFinishCallBacks.add(callback);
    }
  }

  static void setOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    if (callback != null && !_onCommonTaskFinishCallBacks.contains(callback)) {
      _onCommonTaskFinishCallBacks.add(callback);
    }
  }

  static void removeOnVLMTaskFinishCallBack(
    VLMTaskFinishEndCallBack? callback,
  ) {
    _onVLMTaskFinishCallBacks.remove(callback);
  }

  static void removeOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    _onCommonTaskFinishCallBacks.remove(callback);
  }

  static void setOnDispatchStreamDataCallBack(
    DispatchStreamDataCallBack? callback,
  ) {
    _onDispatchStreamDataCallBack = callback;
  }

  static void setOnDispatchStreamEndCallBack(
    DispatchStreamEndCallBack? callback,
  ) {
    _onDispatchStreamEndCallBack = callback;
  }

  static void setOnDispatchStreamErrorCallBack(
    DispatchStreamErrorCallBack? callback,
  ) {
    _onDispatchStreamErrorCallBack = callback;
  }

  static void setOnScheduledTaskCancelledCallBack(
    ScheduledTaskCancelledCallBack? callback,
  ) {
    _onScheduledTaskCancelledCallBack = callback;
  }

  static void setOnScheduledTaskExecuteNowCallBack(
    ScheduledTaskExecuteNowCallBack? callback,
  ) {
    _onScheduledTaskExecuteNowCallBack = callback;
  }

  static void setOnAgentPromptTokenUsageCallback(
    AgentPromptTokenUsageCallback? callback,
  ) {
    _onAgentPromptTokenUsageCallback = callback;
  }

  static void setOnAgentContextCompactionStateCallback(
    AgentContextCompactionStateCallback? callback,
  ) {
    _onAgentContextCompactionStateCallback = callback;
  }

  static int? _asNullableInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw);
    return null;
  }

  static void setOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    if (callback != null && !_onAgentStreamEventCallbacks.contains(callback)) {
      _onAgentStreamEventCallbacks.add(callback);
    }
  }

  static void removeOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    _onAgentStreamEventCallbacks.remove(callback);
  }

  // 发送按钮点击事件到Android端
  static Future<bool> clickButton(
    String taskID,
    String btnId,
    String value, //需要保留.因为有多选数据比如选择app列表,具体协议再定义
    bool isNeedPermission, //是否需要检查权限
  ) async {
    try {
      var result = await assistCore.invokeMethod('clickButton', {
        'taskID': taskID,
        'id': btnId,
        'value': value,
        'isNeedPermission': isNeedPermission,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('发送按钮点击事件失败: ${e.message}');
      return false;
    }
  }

  // 创建陪伴任务
  static Future<bool> createCompanionTask() async {
    var result = await assistCore.invokeMethod('createCompanionTask');
    return result == "SUCCESS";
  }

  //取消陪伴任务
  static Future<bool> cancelTask() async {
    var result = await assistCore.invokeMethod('cancelTask');
    return result == "SUCCESS";
  }

  /// 取消正在运行的任务，不影响陪伴模式
  static Future<bool> cancelRunningTask({String? taskId}) async {
    try {
      var result = await assistCore.invokeMethod(
        'cancelRunningTask',
        taskId == null ? null : {'taskId': taskId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消运行中任务失败: ${e.message}');
      return false;
    }
  }

  /// 停止当前 Agent 正在执行的工具调用，但不终止整轮 Agent 响应
  static Future<bool> stopAgentToolCall({
    required String taskId,
    required String cardId,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'stopAgentToolCall',
        <String, String>{'taskId': taskId, 'cardId': cardId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('停止工具调用失败: ${e.message}');
      return false;
    }
  }

  /// 取消陪伴任务的回到桌面操作
  /// 当用户在开启陪伴后离开主页时调用
  static Future<bool> cancelCompanionGoHome() async {
    try {
      var result = await assistCore.invokeMethod('cancelCompanionGoHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消回到桌面失败: ${e.message}');
      return false;
    }
  }

  /// Trigger the system Home action.
  static Future<bool> pressHome() async {
    try {
      var result = await assistCore.invokeMethod('pressHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('pressHome failed: ${e.message}');
      return false;
    }
  }

  // cancel chat task
  static Future<bool> cancelChatTask({String? taskId}) async {
    var result = await assistCore.invokeMethod(
      'cancelChatTask',
      taskId == null ? null : {'taskId': taskId},
    );
    return result == "SUCCESS";
  }

  static Future<bool> copyToClipboard(String text) async {
    try {
      var result = await assistCore.invokeMethod('copyToClipboard', {
        'text': text,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('复制到剪贴板失败: ${e.message}');
      return false;
    }
  }

  static Future<String?> getClipboardText() async {
    try {
      final result = await assistCore.invokeMethod<String>('getClipboardText');
      return result;
    } on PlatformException catch (e) {
      print('读取剪贴板失败: ${e.message}');
      return null;
    }
  }

  //开始聊天任务
  static Future<bool> createChatTask(
    String taskID,
    List<Map<String, dynamic>> content, {
    String? provider,
    Map<String, dynamic>? openClawConfig,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    int? conversationId,
    String? conversationMode,
    String? userMessage,
    List<Map<String, dynamic>> userAttachments = const [],
  }) async {
    try {
      print('createChatTask taskID: $taskID content: $content');
      final args = {'taskID': taskID, 'content': content};
      if (provider != null) {
        args['provider'] = provider;
      }
      if (openClawConfig != null) {
        args['openClawConfig'] = openClawConfig;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessage != null) {
        args['userMessage'] = userMessage;
      }
      if (userAttachments.isNotEmpty) {
        args['userAttachments'] = userAttachments;
      }
      final result = await assistCore.invokeMethod('createChatTask', args);
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('createChatTask failed: ${e.message}');
      return false;
    }
  }

  //开始视觉模型任务
  static Future<bool> createVLMOperationTask(
    String goal, {
    String? taskId,
    String model = "scene.vlm.operation.primary",
    int maxSteps = 25,
    String? packageName,
    bool needSummary = false,
    bool skipGoHome = false, // 是否跳过回到主页，从当前页面开始执行
  }) async {
    print(
      'createVLMOperationTask goal: $goal model: $model  maxSteps: $maxSteps packageName: $packageName needSummary: $needSummary skipGoHome: $skipGoHome',
    );
    var result = await assistCore.invokeMethod('createVLMOperationTask', {
      'goal': goal,
      if (taskId != null) 'taskId': taskId,
      'model': model,
      'maxSteps': maxSteps,
      'packageName': packageName,
      'needSummary': needSummary,
      'skipGoHome': skipGoHome,
    });

    return result == "SUCCESS";
  }

  /// 向运行中的VLM任务提供用户输入（INFO动作）
  static Future<bool> provideUserInputToVLMTask(String userInput) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'provideUserInputToVLMTask',
        {'userInput': userInput},
      );
      return result == true;
    } on PlatformException catch (e) {
      print('提供用户输入失败: ${e.message}');
      return false;
    }
  }

  /// 通知原生层ChatBotSheet已准备好接收总结
  static Future<bool> notifySummarySheetReady() async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'notifySummarySheetReady',
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('通知总结Sheet准备就绪失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> isCompanionTaskRunning() async {
    return await assistCore.invokeMethod('isCompanionTaskRunning', {});
  }

  /// 获取已安装应用（包含中文应用名和包名）
  static Future<List<Map<String, dynamic>>> getInstalledApplications() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplications',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用失败: ${e.message}');
      return [];
    }
  }

  /// 获取已安装应用（附带图标更新）
  static Future<List<Map<String, dynamic>>>
  getInstalledApplicationsWithIconUpdate() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplicationsWithIconUpdate',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用(附带图标更新)失败: ${e.message}');
      return [];
    }
  }

  /// 开源版不提供 suggestions
  static Future<List<Map<String, dynamic>>> getSuggestions() async {
    return [];
  }

  static Future<bool> isPackageAuthorized(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'isPackageAuthorized',
        {'packageName': packageName},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      print('检查包名授权状态失败: ${e.message}');
      return false;
    }
  }

  // 开源版已移除学习模式

  /// 预约VLM操作任务
  static Future<String?> scheduleVLMOperationTask(
    String goal, //目标文本
    int times, { //预约时间
    String model = "scene.vlm.operation.primary", //模型(sceneId)
    int maxSteps = 25, //最大步数
    String? packageName, //执行任务包名
    String title = "", //任务标题
    String? subTitle, //子标题
    String? extraJson, //额外参数,获取info时会返回
  }) async {
    print(
      'scheduleVLMOperationTask goal: $goal, times: $times, model: $model, maxSteps: $maxSteps, packageName: $packageName',
    );
    try {
      final result = await assistCore
          .invokeMethod<String>('scheduleVLMOperationTask', {
            'goal': goal,
            'model': model,
            'maxSteps': maxSteps,
            'packageName': packageName,
            'times': times,
            'title': title,
            'subTitle': subTitle,
            'extraJson': extraJson,
          });
      return result;
    } on PlatformException catch (e) {
      print('预约VLM操作任务失败: ${e.message}');
      return null;
    }
  }

  /// 获取预约任务信息信息
  static Future<Map<String, dynamic>?> getScheduleTaskInfo() async {
    try {
      final result = await assistCore.invokeMethod<Map<Object?, Object?>>(
        'getScheduleInfo',
      );
      if (result != null) {
        return result.cast<String, dynamic>();
      }
      return null;
    } on PlatformException catch (e) {
      print('获取预约任务信息失败: ${e.message}');
      return null;
    }
  }

  /// 清除预约任务
  static Future<bool> clearScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('clearScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('清除预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 立即执行预约任务
  static Future<bool> doScheduleNow() async {
    try {
      final result = await assistCore.invokeMethod('doScheduleNow');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('立即执行预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 取消预约任务
  static Future<bool> cancelScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('cancelScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('取消预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 查询统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<List<Map<String, dynamic>>> listAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'listAgentExactAlarms',
      );
      if (result == null) return [];
      return result.map((item) {
        if (item is Map) {
          return Map<String, dynamic>.from(item);
        }
        return <String, dynamic>{};
      }).toList();
    } on PlatformException catch (e) {
      print('查询应用内闹钟失败: ${e.message}');
      return [];
    }
  }

  /// 删除统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAgentExactAlarm(String alarmId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': alarmId},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      print('删除应用内闹钟失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> getAlarmSettings() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'getAlarmSettings',
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('读取闹钟设置失败: ${e.message}');
      return {};
    }
  }

  static Future<Map<String, dynamic>> saveAlarmSettings({
    required String source,
    String? localPath,
    String? remoteUrl,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'saveAlarmSettings',
        {'source': source, 'localPath': localPath, 'remoteUrl': remoteUrl},
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('保存闹钟设置失败: ${e.message}');
      return {'success': false, 'message': e.message ?? '保存失败'};
    }
  }

  /// 获取当前 nanoTime（毫秒级，System.nanoTime() / 1_000_000）
  static Future<int?> getNanoTime() async {
    try {
      final result = await assistCore.invokeMethod<int>('getNanoTime');
      return result;
    } on PlatformException catch (e) {
      print('获取nanoTime失败: ${e.message}');
      return null;
    }
  }

  /// 执行首次任务
  static Future<bool> startFirstUse(String packageName) async {
    try {
      final result = await assistCore.invokeMethod('startFirstUse', {
        'packageName': packageName,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('执行首次任务失败: ${e.message}');
      return false;
    }
  }

  /// 初始化半屏引擎并启动首次体验
  static Future<void> initializeAndStartFirstUse(String packageName) async {
    print('🎯 [FirstUse] 开始初始化半屏引擎并启动首次体验');

    // 1. 首先初始化半屏引擎
    final initSuccess = await AppStateService.initHalfScreenEngine();
    if (initSuccess) {
      print('✅ [FirstUse] 半屏引擎初始化成功');
    } else {
      print('⚠️ [FirstUse] 半屏引擎初始化失败');
    }

    // 2. 延迟启动首次体验，确保引擎完全就绪
    await Future.delayed(const Duration(milliseconds: 300));

    // 3. 启动首次体验
    final startSuccess = await startFirstUse(packageName);
    if (startSuccess) {
      print('✅ [FirstUse] 首次体验启动成功');
    } else {
      print('⚠️ [FirstUse] 首次体验启动失败');
    }
  }

  /// 调用LLM chat接口（非流式）
  /// 用于修复JSON格式等场景
  static Future<String?> postLLMChat({
    required String text,
    String model = 'scene.dispatch.model',
  }) async {
    try {
      final result = await assistCore.invokeMethod<String>('postLLMChat', {
        'text': text,
        'model': model,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用LLM chat失败: ${e.message}');
      return null;
    }
  }

  /// 生成记忆中心问候语（原生端优先使用标准 tool_calls）
  static Future<String?> generateMemoryGreeting({
    required List<Map<String, String>> records,
    String model = 'scene.compactor.context',
  }) async {
    try {
      final payloadRecords = records
          .map(
            (item) => {
              'title': item['title'] ?? '',
              'description': item['description'] ?? '',
              'appName': item['appName'] ?? '',
            },
          )
          .toList();
      final result = await assistCore.invokeMethod<String>(
        'generateMemoryGreeting',
        {'model': model, 'records': payloadRecords},
      );
      return result;
    } on PlatformException catch (e) {
      print('生成记忆中心问候语失败: ${e.message}');
      return null;
    }
  }

  /// 创建 Agent 任务
  static Future<bool> createAgentTask({
    required String taskId,
    required String userMessage,
    List<Map<String, dynamic>> conversationHistory = const [],
    List<Map<String, dynamic>> attachments = const [],
    int? userMessageCreatedAtMillis,
    int? conversationId,
    String? conversationMode,
    String? scheduledTaskId,
    String? scheduledTaskTitle,
    bool? scheduleNotificationEnabled,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    Map<String, String>? terminalEnvironment,
  }) async {
    try {
      final args = <String, dynamic>{
        'taskId': taskId,
        'userMessage': userMessage,
      };
      if (conversationHistory.isNotEmpty) {
        args['conversationHistory'] = conversationHistory;
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessageCreatedAtMillis != null &&
          userMessageCreatedAtMillis > 0) {
        args['userMessageCreatedAt'] = userMessageCreatedAtMillis;
      }
      if (scheduledTaskId != null && scheduledTaskId.trim().isNotEmpty) {
        args['scheduledTaskId'] = scheduledTaskId.trim();
      }
      if (scheduledTaskTitle != null && scheduledTaskTitle.trim().isNotEmpty) {
        args['scheduledTaskTitle'] = scheduledTaskTitle.trim();
      }
      if (scheduleNotificationEnabled != null) {
        args['scheduleNotificationEnabled'] = scheduleNotificationEnabled;
      }
      if (attachments.isNotEmpty) {
        args['attachments'] = attachments;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (terminalEnvironment != null && terminalEnvironment.isNotEmpty) {
        args['terminalEnvironment'] = terminalEnvironment;
      }
      final result = await assistCore.invokeMethod('createAgentTask', {
        ...args,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('创建 Agent 任务失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> compactConversationContext({
    required int conversationId,
    required String conversationMode,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<Map<dynamic, dynamic>>('compactConversationContext', {
            'conversationId': conversationId,
            'conversationMode': conversationMode,
            if (modelOverride != null) 'modelOverride': modelOverride,
            if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty)
              'reasoningEffort': reasoningEffort.trim(),
          });
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('手动压缩上下文失败: ${e.message}');
      return {
        'compacted': false,
        'reason': 'failed',
        'message': e.message ?? '手动压缩上下文失败',
      };
    }
  }

  static Future<Map<String, dynamic>?> upsertWorkspaceScheduledTask(
    Map<String, dynamic> task,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'upsertWorkspaceScheduledTask',
        {'task': task},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('更新原生定时任务失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteWorkspaceScheduledTask(String taskId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteWorkspaceScheduledTask',
        {'taskId': taskId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除原生定时任务失败: ${e.message}');
      return false;
    }
  }

  static Future<int> syncWorkspaceScheduledTasks(
    List<Map<String, dynamic>> tasks,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'syncWorkspaceScheduledTasks',
        {'tasks': tasks},
      );
      if (result == null) return 0;
      final count = result['count'];
      if (count is int) return count;
      if (count is String) return int.tryParse(count) ?? 0;
      return 0;
    } on PlatformException catch (e) {
      print('同步原生定时任务失败: ${e.message}');
      return 0;
    }
  }

  static Future<List<Map<String, dynamic>>> listAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'agentSkillList',
      );
      return (result ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList();
    } on PlatformException catch (e) {
      print('读取 Agent skills 失败: ${e.message}');
      return const [];
    }
  }

  static Future<Map<String, dynamic>?> installAgentSkill({
    required String sourcePath,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstall',
        {'sourcePath': sourcePath},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> setAgentSkillEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSetEnabled',
        {'skillId': skillId, 'enabled': enabled},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('切换 Agent skill 启用状态失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteAgentSkill({required String skillId}) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillDelete',
        {'skillId': skillId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除 Agent skill 失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>?> installBuiltinAgentSkill({
    required String skillId,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstallBuiltin',
        {'skillId': skillId},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装内置 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> syncOfficialAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSyncOfficialRepository',
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('同步官方 Agent skills 失败: ${e.message}');
      return null;
    }
  }

  /// 检测自定义 VLM 模型可用性（OpenAI-compatible）
  static Future<ModelAvailabilityCheckResult> checkVlmModelAvailability({
    required String model,
    required String apiBase,
    String apiKey = '',
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'checkVlmModelAvailability',
        {'model': model, 'apiBase': apiBase, 'apiKey': apiKey},
      );
      return ModelAvailabilityCheckResult.fromMap(result);
    } on PlatformException catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: e.message ?? '检测失败',
      );
    } catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败: $e',
      );
    }
  }

  /// 打开应用市场
  static Future<String?> openAPPMarket(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<String>('openAPPMarket', {
        'packageName': packageName,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用openAPPMarket失败: ${e.message}');
      return null;
    }
  }

  /// 检查是否在桌面
  static Future<bool> isDesktop() async {
    try {
      final result = await assistCore.invokeMethod<bool>('isDesktop');
      return result ?? false;
    } on PlatformException catch (e) {
      print('检查是否在桌面失败: ${e.message}');
      return false;
    }
  }

  /// 获取桌面包名
  static Future<List<String>?> getDeskTopPackageName() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getDeskTopPackageName',
      );
      if (result != null) {
        return result.map((e) => e.toString()).toList();
      }
      return null;
    } on PlatformException catch (e) {
      print('获取桌面包名失败: ${e.message}');
      return null;
    }
  }

  /// 获取当前应用包名
  /// 用于从当前页面开始执行任务
  static Future<String?> getCurrentPackageName() async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'getCurrentPackageName',
      );
      return result;
    } on PlatformException catch (e) {
      print('获取当前应用包名失败: ${e.message}');
      return null;
    }
  }

  /// 同步“任务完成后自动回聊天”设置到原生层
  static Future<bool> setAutoBackToChatAfterTaskEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setAutoBackToChatAfterTaskEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('同步自动回聊天设置失败: ${e.message}');
      return false;
    }
  }

  /// 跳转到主引擎路由
  static Future<bool> navigateToMainEngineRoute(String route) async {
    try {
      final result = await assistCore.invokeMethod(
        'navigateToMainEngineRoute',
        {'route': route},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('跳转到主引擎路由失败: ${e.message}');
      return false;
    }
  }

  /// 显示定时任务倒计时提醒（原生浮层）
  static Future<bool> showScheduledTaskReminder({
    required String taskId,
    required String taskName,
    int countdownSeconds = 5,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'showScheduledTaskReminder',
        {
          'taskId': taskId,
          'taskName': taskName,
          'countdownSeconds': countdownSeconds,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('显示定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 隐藏定时任务倒计时提醒
  static Future<bool> hideScheduledTaskReminder() async {
    try {
      final result = await assistCore.invokeMethod('hideScheduledTaskReminder');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('隐藏定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 授权完成后重新打开ChatBot
  static Future<bool> reopenChatBotAfterAuth() async {
    try {
      final result = await assistCore.invokeMethod('reopenChatBotAfterAuth');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('重新打开ChatBot失败: ${e.message}');
      return false;
    }
  }
}
