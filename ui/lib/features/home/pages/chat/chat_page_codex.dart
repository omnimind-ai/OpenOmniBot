part of 'chat_page.dart';

const String _kCodexModelPreferenceKey = 'model';
const String _kCodexCollaborationModePreferenceKey = 'collaboration_mode';
const String _kCodexPreferenceStoragePrefix = 'chat_codex_command_preference';
const String _kCodexInitPrompt = '''
Please analyze this repository and create or update an AGENTS.md file that acts as a contributor guide for future coding agents.

Include concise, repository-specific guidance for:
- project structure and where important code lives
- build, test, lint, and development commands
- coding conventions and architectural patterns visible in the repo
- testing expectations and any important setup notes

Keep the file practical and avoid generic advice. If AGENTS.md already exists, preserve useful existing guidance and update it with what you learn from the current repository.
''';

mixin _ChatPageCodexMixin on _ChatPageStateBase {
  @override
  Future<void> _refreshCodexStatus() async {
    if (!mounted || _isCodexStatusLoading) return;
    setState(() {
      _isCodexStatusLoading = true;
    });
    try {
      final status = await CodexAppServerService.status();
      if (!mounted) return;
      setState(() {
        _codexStatus = status;
        _isCodexStatusLoading = false;
      });
      if (_activeMode == ChatPageMode.codex) {
        unawaited(_loadCodexModelOptionsWhenReady());
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _codexStatus = CodexStatus.disconnected;
        _isCodexStatusLoading = false;
      });
    }
  }

  @override
  Future<void> _handleCodexTap() async {
    if (_isCodexStatusLoading) return;
    if (_activeMode == ChatPageMode.codex) {
      await _leaveCodexMode();
      return;
    }
    if (_isLocalModelPureChatLocked) {
      _showLocalModelPureChatLockToast();
      return;
    }
    setState(() {
      _isCodexStatusLoading = true;
    });
    CodexStatus status;
    try {
      status = await CodexAppServerService.status();
      if (status.ready && !status.connected) {
        status = await CodexAppServerService.connect();
        unawaited(CodexAppServerService.listThreads());
      }
    } catch (error) {
      status = CodexStatus(
        connected: false,
        ready: false,
        error: error.toString(),
      );
    }
    if (!mounted) return;
    setState(() {
      _codexStatus = status;
      _isCodexStatusLoading = false;
    });
    if (!status.ready) {
      GoRouterManager.push('/home/termux_setting?focus=codex');
      return;
    }

    await _showCodexAccountStatus();

    final target = _newCodexThreadTarget();
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  Future<void> _leaveCodexMode() async {
    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (!mounted) return;

    final target = _resolveCodexExitTarget();
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  ConversationThreadTarget _resolveCodexExitTarget() {
    return _newThreadTargetForConversationMode(ConversationMode.normal);
  }

  @override
  Future<void> _refreshCodexCommandPreferences() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    final model = _readCodexPreference(
      _kCodexModelPreferenceKey,
      conversationId: conversationId,
    );
    final collaborationMode = _readCodexPreference(
      _kCodexCollaborationModePreferenceKey,
      conversationId: conversationId,
    );
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = model;
      _activeCodexCollaborationMode = collaborationMode;
    });
    if (model == null) {
      unawaited(_loadCodexModelOptionsWhenReady());
    }
  }

  Future<void> _loadCodexModelOptionsWhenReady() async {
    if ((_activeCodexModelId ?? '').trim().isNotEmpty ||
        _isCodexModelListLoading) {
      return;
    }
    var status = _codexStatus;
    try {
      if (!status.ready) {
        status = await CodexAppServerService.status();
      }
      if (!status.ready) {
        return;
      }
      if (!status.connected) {
        status = await CodexAppServerService.connect();
        unawaited(CodexAppServerService.listThreads());
      }
    } catch (error) {
      debugPrint('Prepare Codex model options failed: $error');
      return;
    }
    if (!mounted || !status.connected) {
      return;
    }
    setState(() {
      _codexStatus = status;
    });
    await _loadCodexModelOptions(force: true);
  }

  @override
  Future<void> _loadCodexModelOptions({bool force = false}) async {
    if (_isCodexModelListLoading) {
      return;
    }
    if (!force &&
        _codexModelOptions.isNotEmpty &&
        (_activeCodexModelId ?? '').trim().isNotEmpty) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _isCodexModelListLoading = true;
      _codexModelListError = null;
    });
    try {
      final configModel = await _readCodexModelIdFromServerConfig();
      final response = await CodexAppServerService.listModels();
      final models = _extractCodexOptionIds(response, const <String>[
        'models',
        'items',
        'data',
      ]);
      final preferredModel =
          configModel ??
          _extractCodexPreferredOptionId(response) ??
          (models.isNotEmpty ? models.first : null);
      final modelOptions =
          preferredModel != null && !models.contains(preferredModel)
          ? <String>[preferredModel, ...models]
          : models;
      if (!mounted) return;
      setState(() {
        _codexModelOptions = modelOptions;
        if ((_activeCodexModelId ?? '').trim().isEmpty &&
            preferredModel != null) {
          _activeCodexModelId = preferredModel;
        }
        _isCodexModelListLoading = false;
        _codexModelListError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isCodexModelListLoading = false;
        _codexModelListError = error.toString();
      });
    }
  }

  Future<String?> _readCodexModelIdFromServerConfig() async {
    try {
      final response = await CodexAppServerService.readConfig();
      return _extractCodexConfigModelId(response);
    } catch (error) {
      debugPrint('Read Codex config model failed: $error');
      return null;
    }
  }

  @override
  Future<void> _loadCodexCollaborationModes({bool force = false}) async {
    if (_isCodexCollaborationModeListLoading) {
      return;
    }
    if (!force && _codexCollaborationModes.isNotEmpty) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _isCodexCollaborationModeListLoading = true;
      _codexCollaborationModeListError = null;
    });
    try {
      final response = await CodexAppServerService.listCollaborationModes();
      final modes = _extractCodexOptionIds(response, const <String>[
        'collaborationModes',
        'modes',
        'items',
        'data',
      ]);
      if (!mounted) return;
      setState(() {
        _codexCollaborationModes = modes;
        _isCodexCollaborationModeListLoading = false;
        _codexCollaborationModeListError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isCodexCollaborationModeListLoading = false;
        _codexCollaborationModeListError = error.toString();
      });
    }
  }

  @override
  Future<void> _selectCodexModel(String modelId) async {
    final normalized = modelId.trim();
    if (normalized.isEmpty || normalized.startsWith('/')) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = normalized;
    });
    await _writeCodexPreference(_kCodexModelPreferenceKey, normalized);
    _messageController.clear();
    _hideSlashCommandPanel();
  }

  @override
  Future<void> _activateCodexPlanMode({bool persistOnly = false}) async {
    await _loadCodexCollaborationModes();
    final planMode = _resolveCodexPlanMode(_codexCollaborationModes);
    if (!mounted) return;
    setState(() {
      _activeCodexCollaborationMode = planMode;
    });
    await _writeCodexPreference(
      _kCodexCollaborationModePreferenceKey,
      planMode,
    );
    if (!persistOnly) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<void> _handleCodexSlashCommandCardSelected(
    Map<String, dynamic> cardData,
  ) async {
    final command = (cardData['toolTitle'] ?? cardData['displayName'] ?? '')
        .toString()
        .trim();
    if (command.isEmpty) {
      return;
    }
    if (command == '/model') {
      _messageController.value = const TextEditingValue(
        text: '/model ',
        selection: TextSelection.collapsed(offset: 7),
      );
      _inputFocusNode.requestFocus();
      _handleSlashCommandInput();
      await _loadCodexModelOptions();
      return;
    }
    if (command == '/review') {
      await _startCodexReviewCommand();
      return;
    }
    if (command == '/init') {
      await _executeCodexInitCommand();
      return;
    }
    if (command == '/plan') {
      await _activateCodexPlanMode();
      return;
    }
    if (_resolveSlashCommandPanelRoute(_messageController.text) ==
        _SlashCommandPanelRoute.codexModel) {
      await _selectCodexModel(command);
    }
  }

  @override
  Future<bool> _tryHandleCodexSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    final intent = resolveCodexSlashSubmitIntent(trimmed);
    switch (intent.kind) {
      case CodexSlashSubmitKind.none:
        return false;
      case CodexSlashSubmitKind.openModelPicker:
        _triggerSlashCommandPanel();
        await _loadCodexModelOptions();
        return true;
      case CodexSlashSubmitKind.selectModel:
        await _selectCodexModel(intent.value ?? '');
        return true;
      case CodexSlashSubmitKind.startReview:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _startCodexReviewCommand();
        return true;
      case CodexSlashSubmitKind.startInit:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _executeCodexInitCommand();
        return true;
      case CodexSlashSubmitKind.activatePlan:
        await _activateCodexPlanMode();
        return true;
      case CodexSlashSubmitKind.startPlan:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _activateCodexPlanMode(persistOnly: true);
        await _startCodexTurnCommand(
          displayText: trimmed,
          actualText: intent.value ?? '',
          collaborationModeOverride:
              _activeCodexCollaborationMode ?? _resolveCodexPlanMode(const []),
        );
        return true;
      case CodexSlashSubmitKind.unsupported:
        _messageController.clear();
        _hideSlashCommandPanel();
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Unsupported Codex command'
              : '不支持的 Codex 命令',
        );
        return true;
    }
  }

  @override
  Future<void> _executeCodexInitCommand() async {
    await _startCodexTurnCommand(
      displayText: '/init',
      actualText: _kCodexInitPrompt,
    );
  }

  @override
  Future<void> _startCodexReviewCommand() async {
    if (_isAiResponding) {
      return;
    }
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    final messageIds = addUserMessage('/review');
    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        _currentDispatchTaskId = messageIds.aiMessageId;
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      if (mounted) {
        _currentDispatchTaskId = messageIds.aiMessageId;
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }

    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = messageIds.aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: messageIds.aiMessageId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
    await ConversationHistoryService.saveConversationMessages(
      conversationId,
      List<ChatMessageModel>.from(_messages),
      mode: ConversationMode.codex,
    );

    try {
      CodexStatus status = _codexStatus;
      if (!status.connected) {
        status = await CodexAppServerService.connect();
        if (mounted) {
          setState(() {
            _codexStatus = status;
          });
        }
      }
      final response = await CodexAppServerService.startReview(
        conversationId: conversationId,
        threadId: _activeCodexThreadId,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: _activeCodexModelId,
        collaborationMode: _activeCodexCollaborationMode,
      );
      _activeCodexThreadId =
          _asCodexString(response['threadId']) ?? _activeCodexThreadId;
      _activeCodexTurnId =
          _asCodexString(response['turnId']) ?? _activeCodexTurnId;
      await _writeCodexCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('Codex review 启动失败: $error');
    }
  }

  Future<void> _startCodexTurnCommand({
    required String displayText,
    required String actualText,
    String? collaborationModeOverride,
  }) async {
    if (_isAiResponding) {
      return;
    }
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    final messageIds = addUserMessage(displayText);
    await _sendCodexMessage(
      messageIds.aiMessageId,
      actualText,
      collaborationModeOverride: collaborationModeOverride,
    );
  }

  String? _readCodexPreference(String kind, {int? conversationId}) {
    try {
      if (conversationId != null) {
        final scoped = StorageService.getString(
          _codexPreferenceKey(kind, conversationId: conversationId),
          defaultValue: '',
        );
        final normalizedScoped = scoped?.trim() ?? '';
        if (normalizedScoped.isNotEmpty) {
          return normalizedScoped;
        }
      }
      final global = StorageService.getString(
        _codexPreferenceKey(kind),
        defaultValue: '',
      );
      final normalizedGlobal = global?.trim() ?? '';
      return normalizedGlobal.isEmpty ? null : normalizedGlobal;
    } catch (error) {
      debugPrint('Read Codex command preference failed: $error');
      return null;
    }
  }

  Future<void> _writeCodexPreference(String kind, String value) async {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return;
    }
    await StorageService.setString(_codexPreferenceKey(kind), normalized);
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId != null) {
      await StorageService.setString(
        _codexPreferenceKey(kind, conversationId: conversationId),
        normalized,
      );
    }
  }

  Future<void> _writeCodexCommandPreferencesForCurrentConversation() async {
    final modelId = _activeCodexModelId?.trim();
    if (modelId != null && modelId.isNotEmpty) {
      await _writeCodexPreference(_kCodexModelPreferenceKey, modelId);
    }
    final collaborationMode = _activeCodexCollaborationMode?.trim();
    if (collaborationMode != null && collaborationMode.isNotEmpty) {
      await _writeCodexPreference(
        _kCodexCollaborationModePreferenceKey,
        collaborationMode,
      );
    }
  }

  String _codexPreferenceKey(String kind, {int? conversationId}) {
    if (conversationId == null) {
      return '$_kCodexPreferenceStoragePrefix.$kind.global';
    }
    return '$_kCodexPreferenceStoragePrefix.$kind.conversation.$conversationId';
  }

  @override
  void _handleCodexAppServerEvent(Map<String, dynamic> event) {
    final conversationId =
        _asCodexInt(event['conversationId']) ??
        _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId == null) {
      return;
    }
    final result = _runtimeCoordinator.applyCodexEvent(
      conversationId: conversationId,
      event: event,
      conversation: _currentConversationByMode[ChatPageMode.codex],
    );
    final threadId = _asCodexString(event['threadId']) ?? result.threadId;
    final turnId = _asCodexString(event['turnId']) ?? result.turnId;
    if (threadId != null || turnId != null) {
      _activeCodexThreadId = threadId ?? _activeCodexThreadId;
      _activeCodexTurnId = turnId ?? _activeCodexTurnId;
    }
    if (result.method == 'turn/completed') {
      _activeCodexTurnId = null;
    }
    if (!result.handled &&
        result.method != 'codex/stderr' &&
        result.method != 'codex/parseError') {
      debugPrint('[Codex] unhandled app-server event: ${jsonEncode(event)}');
    }
    if (_activeMode == ChatPageMode.codex && mounted) {
      setState(() {});
    }
  }

  @override
  Future<void> _sendCodexMessage(
    String aiMessageId,
    String messageText, {
    String? modelOverride,
    String? collaborationModeOverride,
  }) async {
    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        _currentDispatchTaskId = aiMessageId;
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      if (mounted) {
        _currentDispatchTaskId = aiMessageId;
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }

    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: aiMessageId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
    await ConversationHistoryService.saveConversationMessages(
      conversationId,
      List<ChatMessageModel>.from(_messages),
      mode: ConversationMode.codex,
    );

    try {
      CodexStatus status = _codexStatus;
      if (!status.connected) {
        status = await CodexAppServerService.connect();
        if (mounted) {
          setState(() {
            _codexStatus = status;
          });
        }
      }
      final response = await CodexAppServerService.startTurn(
        conversationId: conversationId,
        threadId: _activeCodexThreadId,
        text: messageText,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: modelOverride ?? _activeCodexModelId,
        collaborationMode:
            collaborationModeOverride ?? _activeCodexCollaborationMode,
      );
      _activeCodexThreadId =
          _asCodexString(response['threadId']) ?? _activeCodexThreadId;
      _activeCodexTurnId =
          _asCodexString(response['turnId']) ?? _activeCodexTurnId;
      final localConversationId = _asCodexInt(response['conversationId']);
      if (localConversationId != null &&
          localConversationId !=
              _currentConversationIdByMode[ChatPageMode.codex]) {
        if (_currentConversationIdByMode[ChatPageMode.codex] == null) {
          _currentConversationIdByMode[ChatPageMode.codex] =
              localConversationId;
          await _prepareConversationModeState(
            ChatPageMode.codex,
            ConversationThreadTarget.existing(
              conversationId: localConversationId,
              mode: ConversationMode.codex,
            ),
          );
        } else {
          debugPrint(
            '[Codex] keeping active conversation ${_currentConversationIdByMode[ChatPageMode.codex]} '
            'instead of mismatched native conversation $localConversationId',
          );
        }
      }
      await _writeCodexCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('Codex 启动失败: $error');
    }
  }

  @override
  Future<void> _interruptCodexTurn() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId == null && _activeCodexThreadId == null) {
      return;
    }
    try {
      await CodexAppServerService.interruptTurn(
        conversationId: conversationId,
        threadId: _activeCodexThreadId,
        turnId: _activeCodexTurnId,
      );
    } catch (error) {
      debugPrint('Codex interrupt failed: $error');
    }
  }

  Future<void> _showCodexAccountStatus() async {
    try {
      final account = await CodexAppServerService.readAccount();
      final accountMap = account['account'];
      final requiresOpenaiAuth = account['requiresOpenaiAuth'] == true;
      final isLoggedIn =
          accountMap is Map &&
          ((accountMap['email']?.toString().trim().isNotEmpty ?? false) ||
              (accountMap['type']?.toString().trim().isNotEmpty ?? false));
      if (isLoggedIn && !requiresOpenaiAuth) {
        return;
      }
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(
          content: Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'Codex login required'
                : '需要登录 Codex',
          ),
          action: SnackBarAction(
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Login'
                : '登录',
            onPressed: () {
              unawaited(_startCodexLogin());
            },
          ),
        ),
      );
    } catch (error) {
      debugPrint('Read Codex account failed: $error');
    }
  }

  Future<void> _startCodexLogin() async {
    try {
      final response = await CodexAppServerService.startLogin();
      final authUrl = _asCodexString(response['authUrl']);
      if (authUrl == null) return;
      await launchUrlString(authUrl, mode: LaunchMode.externalApplication);
    } catch (error) {
      debugPrint('Start Codex login failed: $error');
    }
  }
}

int? _asCodexInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String? _asCodexString(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

List<String> _extractCodexOptionIds(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final rawItems = <dynamic>[];
  for (final key in listKeys) {
    final value = response[key];
    if (value is List) {
      rawItems.addAll(value);
    }
  }
  if (rawItems.isEmpty) {
    for (final value in response.values) {
      if (value is List) {
        rawItems.addAll(value);
      }
    }
  }
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final id = _codexOptionId(item);
    if (id == null || !seen.add(id)) {
      continue;
    }
    result.add(id);
  }
  return result;
}

String? _extractCodexPreferredOptionId(Map<String, dynamic> response) {
  for (final key in const <String>[
    'currentModel',
    'currentModelId',
    'selectedModel',
    'selectedModelId',
    'activeModel',
    'activeModelId',
    'defaultModel',
    'defaultModelId',
    'model',
    'modelId',
  ]) {
    final id = _codexOptionId(response[key]);
    if (id != null) {
      return id;
    }
  }
  for (final key in const <String>[
    'current',
    'selected',
    'active',
    'default',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _codexOptionId(value);
      if (id != null) {
        return id;
      }
    }
  }
  return null;
}

String? _extractCodexConfigModelId(Map<String, dynamic> response) {
  final direct = _codexOptionId(response['model'] ?? response['modelId']);
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _codexOptionId(value['model'] ?? value['modelId']);
      if (id != null) {
        return id;
      }
      final nested = _extractCodexConfigModelId(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _codexOptionId(dynamic item) {
  if (item is String) {
    final text = item.trim();
    return text.isEmpty ? null : text;
  }
  if (item is Map) {
    for (final key in const <String>[
      'id',
      'model',
      'modelId',
      'slug',
      'name',
      'value',
      'mode',
    ]) {
      final text = item[key]?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
  }
  final text = item?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String _resolveCodexPlanMode(List<String> modes) {
  for (final mode in modes) {
    if (mode.toLowerCase() == 'plan') {
      return mode;
    }
  }
  for (final mode in modes) {
    if (_isCodexPlanMode(mode)) {
      return mode;
    }
  }
  return 'plan';
}

bool _isCodexPlanMode(String? mode) {
  final normalized = mode?.trim().toLowerCase() ?? '';
  return normalized == 'plan' || normalized.contains('plan');
}

extension _CodexPermissionModePayload on CodexPermissionMode {
  String get approvalPolicy {
    return switch (this) {
      CodexPermissionMode.fullAccess => 'never',
      CodexPermissionMode.defaultMode ||
      CodexPermissionMode.autoReview => 'on-request',
    };
  }

  String get approvalsReviewer {
    return switch (this) {
      CodexPermissionMode.autoReview => 'guardian_subagent',
      CodexPermissionMode.defaultMode ||
      CodexPermissionMode.fullAccess => 'user',
    };
  }

  Map<String, dynamic>? get sandboxPolicy {
    return switch (this) {
      CodexPermissionMode.fullAccess => const <String, dynamic>{
        'type': 'dangerFullAccess',
      },
      CodexPermissionMode.defaultMode || CodexPermissionMode.autoReview => null,
    };
  }
}
