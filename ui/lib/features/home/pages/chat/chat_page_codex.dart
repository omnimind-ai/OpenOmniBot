part of 'chat_page.dart';

const String _kCodexModelPreferenceKey = 'model';
const String _kCodexReasoningEffortPreferenceKey = 'reasoning_effort';
const String _kCodexCollaborationModePreferenceKey = 'collaboration_mode';
const String _kCodexPreferenceStoragePrefix = 'chat_codex_command_preference';
const String _kDefaultCodexReasoningEffort = 'xhigh';
const List<String> _kCodexModelListResponseKeys = <String>[
  'models',
  'items',
  'data',
  'modelOptions',
  'model_options',
  'availableModels',
  'available_models',
  'modelIds',
  'model_ids',
  'options',
];
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
      if (status.remoteEnabled) {
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Remote Codex Bridge is unavailable'
              : '远程 Codex Bridge 不可用',
        );
        GoRouterManager.push('/home/codex_setting');
        return;
      }
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
  String? _codexRemoteWorkspaceNameForGreeting() {
    if (!_codexStatus.remoteEnabled) {
      return null;
    }
    return _codexLastPathSegment(
      _codexStatus.remoteCwd ?? _codexStatus.cwd ?? '',
    );
  }

  @override
  Future<void> _openCodexRemoteWorkspacePicker() async {
    if (!_codexStatus.remoteEnabled) {
      return;
    }
    CodexLocalConfig config;
    try {
      config = await CodexAppServerService.readLocalConfig();
    } catch (error) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to read Codex config: $error'
            : '读取 Codex 配置失败：$error',
        type: ToastType.error,
      );
      return;
    }
    if (!mounted) return;
    if (!config.remoteEnabled || config.remoteBridgeUrl.trim().isEmpty) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Remote Codex Bridge is not configured'
            : '远程 Codex Bridge 尚未配置',
        type: ToastType.warning,
      );
      return;
    }
    final selected = await showCodexRemoteDirectoryPicker(
      context: context,
      remoteBridgeUrl: config.remoteBridgeUrl,
      remoteBridgeToken: config.remoteBridgeToken,
      initialPath: config.remoteCwd,
    );
    if (!mounted || selected == null || selected.trim().isEmpty) {
      return;
    }
    final nextCwd = selected.trim();
    if (nextCwd == config.remoteCwd.trim()) {
      return;
    }
    try {
      await CodexAppServerService.writeLocalConfig(
        baseUrl: config.baseUrl,
        model: config.model,
        apiKey: config.apiKey,
        remoteEnabled: true,
        remoteBridgeUrl: config.remoteBridgeUrl,
        remoteBridgeToken: config.remoteBridgeToken,
        remoteCwd: nextCwd,
      );
      final status = await CodexAppServerService.status();
      if (!mounted) return;
      setState(() {
        _codexStatus = status;
        _activeCodexThreadId = null;
        _activeCodexTurnId = null;
      });
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Switched Codex workspace to ${_codexLastPathSegment(nextCwd) ?? nextCwd}'
            : '已切换到 ${_codexLastPathSegment(nextCwd) ?? nextCwd}',
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to switch workspace: $error'
            : '切换工作目录失败：$error',
        type: ToastType.error,
      );
    }
  }

  @override
  Future<void> _prepareRemoteCodexSessionTarget(
    ConversationThreadTarget target,
  ) async {
    final threadId = target.codexThreadId?.trim() ?? '';
    if (threadId.isEmpty) {
      return;
    }
    final runtimeId = _remoteCodexRuntimeId(threadId);
    _activeCodexRemoteRuntimeId = runtimeId;
    _activeCodexThreadId = threadId;
    _activeCodexTurnId = null;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;

    try {
      CodexStatus status = _codexStatus;
      if (!status.connected) {
        status = await CodexAppServerService.connect();
      }
      final response = await CodexAppServerService.resumeThread(
        threadId: threadId,
      );
      if (!mounted) return;
      final resolvedThreadId =
          _asCodexString(response['threadId']) ??
          _asCodexString(_asCodexMap(response['thread'])?['id']) ??
          threadId;
      final conversation = _remoteCodexConversationFromResponse(
        runtimeId: runtimeId,
        response: response,
      );
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: resolvedThreadId,
        fallbackRuntimeId: runtimeId,
        fallbackConversation: conversation,
        status: status,
        assumeActive: target.codexThreadActive == true,
      );
      _startRemoteCodexSessionSync(resolvedThreadId);
      _rememberRuntimeUiSnapshot(ChatPageMode.codex);
    } catch (error) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to load Codex session: $error'
            : '加载 Codex session 失败：$error',
        type: ToastType.error,
      );
    }
  }

  @override
  Future<void> _refreshCodexCommandPreferences() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    final model = _readCodexPreference(
      _kCodexModelPreferenceKey,
      conversationId: conversationId,
    );
    final effort = _readCodexPreference(
      _kCodexReasoningEffortPreferenceKey,
      conversationId: conversationId,
    );
    final collaborationMode = _readCodexPreference(
      _kCodexCollaborationModePreferenceKey,
      conversationId: conversationId,
    );
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = model;
      _activeCodexReasoningEffort = _normalizeCodexReasoningEffort(effort);
      _activeCodexCollaborationMode = collaborationMode;
    });
    if (model == null || effort == null || _codexModelOptions.isEmpty) {
      unawaited(_loadCodexModelOptionsWhenReady());
    }
  }

  Future<void> _loadCodexModelOptionsWhenReady() async {
    if ((_codexModelOptions.isNotEmpty &&
            (_activeCodexModelId ?? '').trim().isNotEmpty &&
            (_activeCodexReasoningEffort ?? '').trim().isNotEmpty) ||
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
      final configSettings = await _readCodexRunSettingsFromServerConfig();
      final response = await CodexAppServerService.listModels();
      final models = _extractCodexOptionIds(
        response,
        _kCodexModelListResponseKeys,
      );
      if (models.isEmpty) {
        debugPrint(
          '[Codex] model/list returned no parseable models: ${jsonEncode(response)}',
        );
      }
      final preferredModel =
          configSettings.modelId ??
          _extractCodexPreferredOptionId(response) ??
          _extractCodexDefaultModelId(response) ??
          (models.isNotEmpty ? models.first : null);
      final activeModel = (_activeCodexModelId ?? '').trim();
      final modelOptions = _mergeCodexOptionIds(
        current: activeModel.isEmpty ? preferredModel : activeModel,
        preferred: preferredModel,
        options: models,
      );
      final effectiveModel = activeModel.isNotEmpty
          ? activeModel
          : preferredModel;
      final modelDefaultEffort = _extractCodexModelDefaultReasoningEffort(
        response,
        effectiveModel,
      );
      final effortOptions = _mergeCodexReasoningEffortOptions(
        current: configSettings.reasoningEffort ?? modelDefaultEffort,
        options: _extractCodexReasoningEffortOptions(response),
      );
      if (!mounted) return;
      setState(() {
        _codexModelOptions = modelOptions;
        if ((_activeCodexModelId ?? '').trim().isEmpty &&
            preferredModel != null) {
          _activeCodexModelId = preferredModel;
        }
        if ((_activeCodexReasoningEffort ?? '').trim().isEmpty) {
          _activeCodexReasoningEffort =
              configSettings.reasoningEffort ??
              modelDefaultEffort ??
              (effortOptions.isNotEmpty
                  ? effortOptions.last
                  : _kDefaultCodexReasoningEffort);
        }
        _codexReasoningEffortOptions = effortOptions;
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

  Future<_CodexRunSettingsSnapshot>
  _readCodexRunSettingsFromServerConfig() async {
    try {
      final response = await CodexAppServerService.readConfig();
      return _CodexRunSettingsSnapshot(
        modelId: _extractCodexConfigModelId(response),
        reasoningEffort: _extractCodexConfigReasoningEffort(response),
      );
    } catch (error) {
      debugPrint('Read Codex config run settings failed: $error');
      return const _CodexRunSettingsSnapshot();
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
  Future<void> _selectCodexModel(
    String modelId, {
    bool clearComposer = true,
  }) async {
    final normalized = modelId.trim();
    if (normalized.isEmpty || normalized.startsWith('/')) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = normalized;
    });
    await _writeCodexPreference(_kCodexModelPreferenceKey, normalized);
    if (clearComposer) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<void> _selectCodexReasoningEffort(String effort) async {
    final normalized = _normalizeCodexReasoningEffort(effort);
    if (normalized == null) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeCodexReasoningEffort = normalized;
      _codexReasoningEffortOptions = _mergeCodexReasoningEffortOptions(
        current: normalized,
        options: _codexReasoningEffortOptions,
      );
    });
    await _writeCodexPreference(
      _kCodexReasoningEffortPreferenceKey,
      normalized,
    );
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
    final remoteCodex = _isRemoteCodexConfigured();
    int? conversationId;
    if (remoteCodex) {
      conversationId = _ensureRemoteCodexRuntimeForCurrentMessages();
    } else {
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (_) {
        if (mounted) {
          _currentDispatchTaskId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTaskId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = messageIds.aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: messageIds.aiMessageId,
      conversationId: resolvedConversationId,
      mode: _modeKey(_activeMode),
    );
    if (!remoteCodex) {
      await ConversationHistoryService.saveConversationMessages(
        resolvedConversationId,
        List<ChatMessageModel>.from(_messages),
        mode: ConversationMode.codex,
      );
    }

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
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeCodexThreadId,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: _activeCodexModelId,
        effort: _activeCodexReasoningEffort,
        collaborationMode: _activeCodexCollaborationMode,
      );
      final resolvedThreadId = _asCodexString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeCodexThreadId = resolvedThreadId ?? _activeCodexThreadId;
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
    final effort = _activeCodexReasoningEffort?.trim();
    if (effort != null && effort.isNotEmpty) {
      await _writeCodexPreference(_kCodexReasoningEffortPreferenceKey, effort);
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
    final remoteCodex = _isRemoteCodexConfigured();
    final eventThreadId = _codexEventThreadId(event);
    final explicitConversationId = _asCodexInt(event['conversationId']);
    final mappedRemoteConversationId = remoteCodex && eventThreadId != null
        ? _remoteCodexRuntimeId(eventThreadId)
        : null;
    final shouldPromoteRemoteEvent =
        remoteCodex &&
        eventThreadId != null &&
        _shouldPromoteRemoteCodexEventToVisibleThread(
          threadId: eventThreadId,
          runtimeId: mappedRemoteConversationId!,
        );
    final conversationId =
        explicitConversationId ??
        (shouldPromoteRemoteEvent
            ? _activateRemoteCodexRuntimeForThread(eventThreadId)
            : mappedRemoteConversationId) ??
        _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId == null) {
      return;
    }
    if (remoteCodex && eventThreadId != null && !shouldPromoteRemoteEvent) {
      _ensureRemoteCodexRuntimeForThread(eventThreadId);
    }
    final isVisibleConversation =
        conversationId == _currentConversationIdByMode[ChatPageMode.codex];
    final result = _runtimeCoordinator.applyCodexEvent(
      conversationId: conversationId,
      event: event,
      conversation: isVisibleConversation
          ? _currentConversationByMode[ChatPageMode.codex]
          : null,
    );
    final threadId = _asCodexString(event['threadId']) ?? result.threadId;
    final turnId = _asCodexString(event['turnId']) ?? result.turnId;
    if (isVisibleConversation && (threadId != null || turnId != null)) {
      _activeCodexThreadId = threadId ?? _activeCodexThreadId;
      _activeCodexTurnId = turnId ?? _activeCodexTurnId;
    }
    if (isVisibleConversation && result.method == 'turn/completed') {
      _activeCodexTurnId = null;
    }
    if (isVisibleConversation) {
      final runtime = _runtimeCoordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeCodex,
      );
      if (runtime != null) {
        _syncCodexModeStateFromRuntime(runtime);
      }
    }
    if (!result.handled &&
        result.method != 'codex/stderr' &&
        result.method != 'codex/parseError') {
      debugPrint('[Codex] unhandled app-server event: ${jsonEncode(event)}');
    }
    if (_activeMode == ChatPageMode.codex && mounted && isVisibleConversation) {
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
    final remoteCodex = _isRemoteCodexConfigured();
    int? conversationId;
    if (remoteCodex) {
      conversationId = _ensureRemoteCodexRuntimeForCurrentMessages();
    } else {
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (_) {
        if (mounted) {
          _currentDispatchTaskId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTaskId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: aiMessageId,
      conversationId: resolvedConversationId,
      mode: _modeKey(_activeMode),
    );
    if (!remoteCodex) {
      await ConversationHistoryService.saveConversationMessages(
        resolvedConversationId,
        List<ChatMessageModel>.from(_messages),
        mode: ConversationMode.codex,
      );
    }

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
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeCodexThreadId,
        text: messageText,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: modelOverride ?? _activeCodexModelId,
        effort: _activeCodexReasoningEffort,
        collaborationMode:
            collaborationModeOverride ?? _activeCodexCollaborationMode,
      );
      final resolvedThreadId = _asCodexString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeCodexThreadId = resolvedThreadId ?? _activeCodexThreadId;
      _activeCodexTurnId =
          _asCodexString(response['turnId']) ?? _activeCodexTurnId;
      final localConversationId = _asCodexInt(response['conversationId']);
      if (!remoteCodex &&
          localConversationId != null &&
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
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        threadId: _activeCodexThreadId,
        turnId: _activeCodexTurnId,
      );
    } catch (error) {
      debugPrint('Codex interrupt failed: $error');
    }
  }

  void _startRemoteCodexSessionSync(String threadId) {
    final normalizedThreadId = threadId.trim();
    if (normalizedThreadId.isEmpty) {
      return;
    }
    if (_remoteCodexSessionSyncThreadId == normalizedThreadId &&
        _remoteCodexSessionSyncTimer != null) {
      return;
    }
    _remoteCodexSessionSyncThreadId = normalizedThreadId;
    _remoteCodexSessionSyncSignature = '';
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => unawaited(_syncRemoteCodexSessionSnapshot()),
    );
    unawaited(_syncRemoteCodexSessionSnapshot());
  }

  @override
  void _stopRemoteCodexSessionSync() {
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = null;
    _remoteCodexSessionSyncInFlight = false;
    _remoteCodexSessionSyncThreadId = null;
    _remoteCodexSessionSyncSignature = '';
  }

  Future<void> _syncRemoteCodexSessionSnapshot() async {
    if (_remoteCodexSessionSyncInFlight) {
      return;
    }
    final threadId = _remoteCodexSessionSyncThreadId?.trim() ?? '';
    if (threadId.isEmpty ||
        !mounted ||
        _activeConversationMode != ChatPageMode.codex ||
        !_isRemoteCodexConfigured() ||
        _activeCodexThreadId?.trim() != threadId) {
      return;
    }
    _remoteCodexSessionSyncInFlight = true;
    try {
      final response = await _readRemoteCodexThreadSnapshot(threadId);
      if (!mounted ||
          _remoteCodexSessionSyncThreadId != threadId ||
          _activeCodexThreadId?.trim() != threadId) {
        return;
      }
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: threadId,
        fromPoll: true,
      );
    } catch (error) {
      debugPrint('Remote Codex session sync failed: $error');
    } finally {
      if (_remoteCodexSessionSyncThreadId == threadId) {
        _remoteCodexSessionSyncInFlight = false;
      }
    }
  }

  Future<Map<String, dynamic>> _readRemoteCodexThreadSnapshot(
    String threadId,
  ) async {
    try {
      return await CodexAppServerService.readThread(threadId: threadId);
    } catch (error) {
      debugPrint('Codex thread/read failed, falling back to resume: $error');
      return CodexAppServerService.resumeThread(threadId: threadId);
    }
  }

  void _applyRemoteCodexThreadSnapshot({
    required Map<String, dynamic> response,
    required String fallbackThreadId,
    int? fallbackRuntimeId,
    List<ChatMessageModel>? fallbackMessages,
    ConversationModel? fallbackConversation,
    CodexStatus? status,
    bool fromPoll = false,
    bool assumeActive = false,
  }) {
    final resolvedThreadId =
        _asCodexString(response['threadId']) ??
        _asCodexString(_asCodexMap(response['thread'])?['id']) ??
        fallbackThreadId;
    if (resolvedThreadId.isEmpty) {
      return;
    }
    final runtimeId =
        fallbackRuntimeId ?? _remoteCodexRuntimeId(resolvedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
    );
    final activity = _codexThreadActivityFromResponse(response);
    final previousActive = runtime?.isAiResponding ?? false;
    final directActiveTurnId = _codexActiveTurnIdFromThreadResponse(response);
    final isAiResponding =
        directActiveTurnId != null ||
        (activity.known ? activity.active : (assumeActive || previousActive));
    final activeTurnId = isAiResponding
        ? (directActiveTurnId ??
              _codexLatestTurnIdFromThreadResponse(response) ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              _activeCodexTurnId)
        : null;
    final activeTaskId = isAiResponding
        ? (activeTurnId ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              'remote-codex-$resolvedThreadId')
        : null;
    final hasTurns = _codexThreadResponseHasTurns(response);
    final messages = hasTurns
        ? _codexMessagesFromThreadResponse(
            response,
            active: isAiResponding,
            activeTurnId: activeTurnId,
          )
        : (fallbackMessages ??
              List<ChatMessageModel>.from(
                runtime?.messages ??
                    _messagesByMode[ChatPageMode.codex] ??
                    const <ChatMessageModel>[],
              ));
    final conversation =
        (fallbackConversation ??
                _remoteCodexConversationFromResponse(
                  runtimeId: runtimeId,
                  response: response,
                ))
            .copyWith(messageCount: messages.length);
    final signature = _remoteCodexSnapshotSignature(
      threadId: resolvedThreadId,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      activeTaskId: activeTaskId,
    );
    if (fromPoll && signature == _remoteCodexSessionSyncSignature) {
      return;
    }
    _remoteCodexSessionSyncSignature = signature;

    if (!mounted) {
      return;
    }
    setState(() {
      _activeCodexRemoteRuntimeId = runtimeId;
      _activeCodexThreadId = resolvedThreadId;
      _activeCodexTurnId = activeTurnId;
      if (status != null) {
        _codexStatus = status;
      }
      _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
      _currentConversationByMode[ChatPageMode.codex] = conversation;
      _isAiRespondingByMode[ChatPageMode.codex] = isAiResponding;
      _isExecutingTaskByMode[ChatPageMode.codex] = isAiResponding;
      _isDeepThinkingByMode[ChatPageMode.codex] = isAiResponding;
      _currentThinkingStageByMode[ChatPageMode.codex] = isAiResponding
          ? ThinkingStage.thinking.value
          : ThinkingStage.complete.value;
      _currentDispatchTaskIdByMode[ChatPageMode.codex] = activeTaskId;
      _messagesByMode[ChatPageMode.codex]!
        ..clear()
        ..addAll(messages);
      _hasMoreMessagesByMode[ChatPageMode.codex] = false;
      _messageOffsetByMode[ChatPageMode.codex] = messages.length;
    });
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      initialMessages: messages,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    _runtimeCoordinator.replaceConversationSnapshot(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      isExecutingTask: isAiResponding,
      isDeepThinking: isAiResponding,
      deepThinkingContent: runtime?.deepThinkingContent ?? '',
      currentDispatchTaskId: activeTaskId,
      currentThinkingStage: isAiResponding
          ? ThinkingStage.thinking.value
          : ThinkingStage.complete.value,
      lastAgentTaskId: activeTaskId,
      chatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    if (activeTaskId != null) {
      _runtimeCoordinator.registerTask(
        taskId: activeTaskId,
        conversationId: runtimeId,
        mode: kChatRuntimeModeCodex,
      );
    }
    final updatedRuntime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
    );
    if (updatedRuntime != null) {
      _syncCodexModeStateFromRuntime(updatedRuntime);
    }
  }

  void _syncCodexModeStateFromRuntime(ChatConversationRuntimeState runtime) {
    _isAiRespondingByMode[ChatPageMode.codex] = runtime.isAiResponding;
    _isContextCompressingByMode[ChatPageMode.codex] =
        runtime.isContextCompressing;
    _isCheckingExecutableTaskByMode[ChatPageMode.codex] =
        runtime.isCheckingExecutableTask;
    _isSubmittingVlmReplyByMode[ChatPageMode.codex] =
        runtime.isSubmittingVlmReply;
    _vlmInfoQuestionByMode[ChatPageMode.codex] = runtime.vlmInfoQuestion;
    _currentAiMessagesByMode[ChatPageMode.codex]!
      ..clear()
      ..addAll(runtime.currentAiMessages);
    _deepThinkingContentByMode[ChatPageMode.codex] =
        runtime.deepThinkingContent;
    _isDeepThinkingByMode[ChatPageMode.codex] = runtime.isDeepThinking;
    _currentDispatchTaskIdByMode[ChatPageMode.codex] =
        runtime.currentDispatchTaskId;
    _currentThinkingStageByMode[ChatPageMode.codex] =
        runtime.currentThinkingStage;
    _isInputAreaVisibleByMode[ChatPageMode.codex] = runtime.isInputAreaVisible;
    _isExecutingTaskByMode[ChatPageMode.codex] = runtime.isExecutingTask;
    _currentConversationByMode[ChatPageMode.codex] = runtime.conversation;
    _chatIslandDisplayLayerByMode[ChatPageMode.codex] =
        runtime.chatIslandDisplayLayer;
    _lastAgentToolTypeByMode[ChatPageMode.codex] = runtime.lastAgentToolType;
    _browserSessionSnapshotByMode[ChatPageMode.codex] =
        runtime.browserSessionSnapshot;
  }

  bool _isRemoteCodexConfigured() {
    final runtime = _codexStatus.runtime?.trim();
    return runtime == 'remote' || _codexStatus.remoteEnabled;
  }

  int _ensureRemoteCodexRuntimeForCurrentMessages() {
    final currentId = _currentConversationIdByMode[ChatPageMode.codex];
    if (currentId != null &&
        _runtimeCoordinator.isEphemeralRuntime(
          conversationId: currentId,
          mode: kChatRuntimeModeCodex,
        )) {
      return currentId;
    }
    final runtimeId = _activeCodexThreadId?.trim().isNotEmpty == true
        ? _remoteCodexRuntimeId(_activeCodexThreadId!)
        : (_activeCodexRemoteRuntimeId ??
              _remoteCodexRuntimeId(
                'pending-${DateTime.now().microsecondsSinceEpoch}',
              ));
    _activeCodexRemoteRuntimeId = runtimeId;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
    _currentConversationByMode[ChatPageMode.codex] ??= ConversationModel(
      id: runtimeId,
      mode: ConversationMode.codex,
      title: 'Codex',
      status: 0,
      lastMessage: _messagesByMode[ChatPageMode.codex]!.isNotEmpty
          ? _messagesByMode[ChatPageMode.codex]!.first.text
          : null,
      messageCount: _messagesByMode[ChatPageMode.codex]!.length,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      initialMessages: List<ChatMessageModel>.from(
        _messagesByMode[ChatPageMode.codex]!,
      ),
      conversation: _currentConversationByMode[ChatPageMode.codex],
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    return runtimeId;
  }

  int _ensureRemoteCodexRuntimeForThread(String threadId) {
    final normalizedThreadId = threadId.trim();
    final runtimeId = _remoteCodexRuntimeId(normalizedThreadId);
    final now = DateTime.now().millisecondsSinceEpoch;
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      conversation:
          _runtimeCoordinator
              .runtimeFor(
                conversationId: runtimeId,
                mode: kChatRuntimeModeCodex,
              )
              ?.conversation ??
          ConversationModel(
            id: runtimeId,
            mode: ConversationMode.codex,
            title:
                'Codex ${normalizedThreadId.length > 6 ? normalizedThreadId.substring(normalizedThreadId.length - 6) : normalizedThreadId}',
            status: 0,
            messageCount: 0,
            createdAt: now,
            updatedAt: now,
          ),
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    return runtimeId;
  }

  int _activateRemoteCodexRuntimeForThread(String threadId) {
    final normalizedThreadId = threadId.trim();
    final runtimeId = _ensureRemoteCodexRuntimeForThread(normalizedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
    );
    if (runtime != null) {
      final visibleMessages = _messagesByMode[ChatPageMode.codex]!;
      if (visibleMessages.isNotEmpty) {
        final existingIds = runtime.messages
            .map((message) => message.id)
            .toSet();
        for (final message in visibleMessages.reversed) {
          if (existingIds.add(message.id)) {
            runtime.messages.add(message);
          }
        }
      }
      final currentConversation =
          _currentConversationByMode[ChatPageMode.codex];
      if (currentConversation != null) {
        runtime.conversation = currentConversation.copyWith(id: runtimeId);
      }
      _currentConversationByMode[ChatPageMode.codex] = runtime.conversation;
    }
    _activeCodexRemoteRuntimeId = runtimeId;
    _activeCodexThreadId = normalizedThreadId;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
    return runtimeId;
  }

  bool _shouldPromoteRemoteCodexEventToVisibleThread({
    required String threadId,
    required int runtimeId,
  }) {
    final activeThreadId = _activeCodexThreadId?.trim();
    if (activeThreadId == threadId) {
      return true;
    }
    final currentConversationId =
        _currentConversationIdByMode[ChatPageMode.codex];
    if (currentConversationId == runtimeId) {
      return true;
    }
    if (activeThreadId != null && activeThreadId.isNotEmpty) {
      return false;
    }
    if (currentConversationId == null ||
        currentConversationId != _activeCodexRemoteRuntimeId) {
      return false;
    }
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: currentConversationId,
      mode: kChatRuntimeModeCodex,
    );
    return (_messagesByMode[ChatPageMode.codex]?.isNotEmpty ?? false) ||
        (runtime?.hasInFlightTask ?? false) ||
        (_currentDispatchTaskIdByMode[ChatPageMode.codex]?.isNotEmpty ?? false);
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

String? _codexEventThreadId(Map<String, dynamic> event) {
  final direct = _asCodexString(event['threadId'] ?? event['thread_id']);
  if (direct != null) {
    return direct;
  }
  final params = _asCodexMap(event['params']);
  final fromParams = _asCodexString(
    params?['threadId'] ?? params?['thread_id'],
  );
  if (fromParams != null) {
    return fromParams;
  }
  final thread = _asCodexMap(params?['thread']);
  final fromThread = _asCodexString(thread?['id']);
  if (fromThread != null) {
    return fromThread;
  }
  final message = _asCodexMap(event['message']);
  final messageParams = _asCodexMap(message?['params']);
  final fromMessageParams = _asCodexString(
    messageParams?['threadId'] ?? messageParams?['thread_id'],
  );
  if (fromMessageParams != null) {
    return fromMessageParams;
  }
  final messageThread = _asCodexMap(messageParams?['thread']);
  return _asCodexString(messageThread?['id']);
}

int _remoteCodexRuntimeId(String seed) {
  var hash = 0x45d9f3b;
  for (final codeUnit in seed.codeUnits) {
    hash = 0x1fffffff & (hash * 31 + codeUnit);
  }
  return -((hash & 0x3fffffff) + 1);
}

ConversationModel _remoteCodexConversationFromResponse({
  required int runtimeId,
  required Map<String, dynamic> response,
}) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final now = DateTime.now().millisecondsSinceEpoch;
  final createdAt =
      _codexTimeValueMs(thread['createdAt'] ?? thread['created_at']) ?? now;
  final updatedAt =
      _codexTimeValueMs(
        thread['updatedAt'] ??
            thread['updated_at'] ??
            thread['lastActivityAt'] ??
            thread['last_activity_at'],
      ) ??
      createdAt;
  final title =
      _asCodexString(
        thread['name'] ??
            thread['title'] ??
            thread['preview'] ??
            response['name'] ??
            response['title'] ??
            response['preview'],
      ) ??
      'Codex';
  return ConversationModel(
    id: runtimeId,
    mode: ConversationMode.codex,
    title: _truncateCodexText(title, 40),
    status: 0,
    lastMessage: _asCodexString(thread['preview']),
    messageCount: _codexMessagesFromThreadResponse(response).length,
    createdAt: createdAt,
    updatedAt: updatedAt,
  );
}

class _CodexThreadActivityState {
  const _CodexThreadActivityState({required this.known, required this.active});

  final bool known;
  final bool active;

  static const unknown = _CodexThreadActivityState(known: false, active: false);
  static const activeState = _CodexThreadActivityState(
    known: true,
    active: true,
  );
  static const inactiveState = _CodexThreadActivityState(
    known: true,
    active: false,
  );
}

_CodexThreadActivityState _codexThreadActivityFromResponse(
  Map<String, dynamic> response,
) {
  final thread = _asCodexMap(response['thread']) ?? response;
  _CodexThreadActivityState? inactiveCandidate;
  for (final value in <dynamic>[
    response['active'],
    response['isActive'],
    response['is_active'],
    response['status'],
    response['state'],
    response['turnStatus'],
    response['turn_status'],
    thread['active'],
    thread['isActive'],
    thread['is_active'],
    thread['status'],
    thread['state'],
    thread['turnStatus'],
    thread['turn_status'],
  ]) {
    final parsed = _codexActivityFromValue(value);
    if (parsed == null) {
      continue;
    }
    if (parsed.active) {
      return parsed;
    }
    inactiveCandidate ??= parsed;
  }
  final latestTurnActivity = _codexLatestTurnActivityFromResponse(response);
  if (latestTurnActivity != null) {
    return latestTurnActivity;
  }
  if (inactiveCandidate != null) {
    return inactiveCandidate;
  }
  return _CodexThreadActivityState.unknown;
}

_CodexThreadActivityState? _codexLatestTurnActivityFromResponse(
  Map<String, dynamic> response,
) {
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asCodexMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _codexActivityFromValue(turn['status'] ?? turn['state']);
    if (parsed != null) {
      return parsed;
    }
  }
  return null;
}

_CodexThreadActivityState? _codexActivityFromValue(dynamic value) {
  if (value is bool) {
    return value
        ? _CodexThreadActivityState.activeState
        : _CodexThreadActivityState.inactiveState;
  }
  final status = _codexStatusText(value);
  if (status == null) {
    return null;
  }
  final normalized = _normalizeCodexStatus(status);
  if (_codexStatusIsActive(normalized)) {
    return _CodexThreadActivityState.activeState;
  }
  if (_codexStatusIsInactive(normalized)) {
    return _CodexThreadActivityState.inactiveState;
  }
  return null;
}

String? _codexStatusText(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String || value is num || value is bool) {
    return _asCodexString(value);
  }
  final map = _asCodexMap(value);
  if (map != null) {
    for (final key in const <String>[
      'type',
      'status',
      'state',
      'value',
      'name',
    ]) {
      final text = _codexStatusText(map[key]);
      if (text != null) {
        return text;
      }
    }
  }
  return null;
}

String _normalizeCodexStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _codexStatusIsActive(String status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _codexStatusIsInactive(String status) {
  return status == 'idle' ||
      status == 'closed' ||
      status == 'completed' ||
      status == 'complete' ||
      status == 'notloaded' ||
      status == 'systemerror' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

String? _codexActiveTurnIdFromThreadResponse(Map<String, dynamic> response) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final status =
      _asCodexMap(response['status']) ?? _asCodexMap(thread['status']);
  final direct = _asCodexString(
    response['turnId'] ??
        response['turn_id'] ??
        response['activeTurnId'] ??
        response['active_turn_id'] ??
        response['currentTurnId'] ??
        response['current_turn_id'] ??
        thread['turnId'] ??
        thread['turn_id'] ??
        thread['activeTurnId'] ??
        thread['active_turn_id'] ??
        thread['currentTurnId'] ??
        thread['current_turn_id'] ??
        status?['turnId'] ??
        status?['turn_id'] ??
        status?['activeTurnId'] ??
        status?['active_turn_id'],
  );
  if (direct != null) {
    return direct;
  }
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asCodexMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _codexActivityFromValue(turn['status'] ?? turn['state']);
    if (parsed?.active == true) {
      return _asCodexString(turn['id']);
    }
  }
  return null;
}

String? _codexLatestTurnIdFromThreadResponse(Map<String, dynamic> response) {
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turnId = _asCodexString(_asCodexMap(turns[index])?['id']);
    if (turnId != null) {
      return turnId;
    }
  }
  return null;
}

bool _codexThreadResponseHasTurns(Map<String, dynamic> response) {
  return _codexTurnsFromThreadResponse(response) != null;
}

List<dynamic>? _codexTurnsFromThreadResponse(Map<String, dynamic> response) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  return rawTurns is List ? rawTurns : null;
}

String _remoteCodexSnapshotSignature({
  required String threadId,
  required List<ChatMessageModel> messages,
  required ConversationModel conversation,
  required bool isAiResponding,
  required String? activeTaskId,
}) {
  final buffer = StringBuffer()
    ..write(threadId)
    ..write('|')
    ..write(conversation.updatedAt)
    ..write('|')
    ..write(isAiResponding ? '1' : '0')
    ..write('|')
    ..write(activeTaskId ?? '')
    ..write('|')
    ..write(messages.length);
  for (final message in messages) {
    buffer
      ..write('|')
      ..write(message.id)
      ..write(':')
      ..write(message.text?.hashCode ?? message.cardData?.hashCode ?? 0);
  }
  return buffer.toString();
}

List<ChatMessageModel> _codexMessagesFromThreadResponse(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  if (rawTurns is! List) {
    return const <ChatMessageModel>[];
  }
  final chronological = <ChatMessageModel>[];
  var seq = 0;
  for (var turnIndex = 0; turnIndex < rawTurns.length; turnIndex += 1) {
    final turn = _asCodexMap(rawTurns[turnIndex]);
    if (turn == null) {
      continue;
    }
    final turnId = _asCodexString(turn['id']) ?? 'turn-$turnIndex';
    final isActiveTurn =
        active && activeTurnId != null && turnId == activeTurnId;
    final turnStartedAt =
        _codexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ??
        DateTime.now().millisecondsSinceEpoch;
    final rawItems = turn['items'];
    if (rawItems is! List) {
      continue;
    }
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = _asCodexMap(rawItems[itemIndex]);
      if (item == null) {
        continue;
      }
      final itemType = _asCodexString(item['type']) ?? '';
      final itemId = _asCodexString(item['id']) ?? '$turnId-item-$itemIndex';
      final createdAt = DateTime.fromMillisecondsSinceEpoch(
        (_codexTimeValueMs(
                  item['createdAt'] ??
                      item['created_at'] ??
                      item['startedAt'] ??
                      item['started_at'],
                ) ??
                turnStartedAt) +
            itemIndex,
      );
      if (itemType == 'userMessage') {
        final text = _codexExtractText(item['content'] ?? item['text']);
        if (text.trim().isEmpty) {
          continue;
        }
        chronological.add(
          ChatMessageModel(
            id: '$itemId-codex-user',
            type: 1,
            user: 1,
            content: {'text': text, 'id': '$itemId-codex-user'},
            createAt: createdAt,
          ),
        );
        continue;
      }
      if (itemType == 'agentMessage') {
        final text = _codexExtractText(
          item['text'] ?? item['message'] ?? item['content'],
        );
        if (text.trim().isEmpty) {
          continue;
        }
        seq += 1;
        final messageId = '$itemId-codex-agent';
        final isFinal = !isActiveTurn;
        chronological.add(
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: {'text': text, 'id': messageId},
            createAt: createdAt,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'text_snapshot',
              parentTaskId: turnId,
              entryId: messageId,
              isFinal: isFinal,
            ),
          ),
        );
        continue;
      }
      if (itemType == 'reasoning') {
        final text = _codexExtractText(
          item['summary'] ?? item['text'] ?? item['content'],
        );
        if (text.trim().isEmpty) {
          continue;
        }
        seq += 1;
        final cardId = '$itemId-codex-thinking';
        final itemActivity = _codexActivityFromValue(
          item['status'] ?? item['state'],
        );
        final isLoading = isActiveTurn && itemActivity?.active != false;
        final stage = isLoading
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        chronological.add(
          ChatMessageModel.cardMessage(
            {
              'type': 'deep_thinking',
              'isLoading': isLoading,
              'thinkingContent': text,
              'stage': stage,
              'taskID': turnId,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
              'endTime': isLoading ? null : createdAt.millisecondsSinceEpoch,
              'isCollapsible': !isLoading,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'thinking_snapshot',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: !isLoading,
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_codexHistoricalToolItemTypes.contains(itemType)) {
        seq += 1;
        final cardId = '$itemId-codex-${_codexToolKind(itemType)}';
        final summary = _codexExtractText(
          item['summary'] ??
              item['status'] ??
              item['output'] ??
              item['text'] ??
              item['content'],
        );
        chronological.add(
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'taskId': turnId,
              'toolName': 'codex.${_codexToolKind(itemType)}',
              'displayName': _codexToolTitle(itemType, item),
              'toolTitle': _codexToolTitle(itemType, item),
              'cardId': cardId,
              'toolType': _codexToolKind(itemType),
              'status': 'success',
              'summary': summary,
              'progress': summary,
              'argsJson': _safeCodexJson(item),
              'resultPreviewJson': '',
              'rawResultJson': _safeCodexJson(item),
              'terminalOutput': _codexExtractText(item['output']),
              'terminalOutputDelta': '',
              'showTerminalOutput': itemType == 'commandExecution',
              'showRawResult': true,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'tool_completed',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: true,
            ),
          ).copyWith(createAt: createdAt),
        );
      }
    }
  }
  return chronological.reversed.toList(growable: false);
}

Map<String, dynamic>? _asCodexMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), nestedValue);
  });
}

String _codexExtractText(dynamic value) {
  if (value == null) return '';
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  if (value is List) {
    return value.map(_codexExtractText).where((text) => text.isNotEmpty).join();
  }
  final map = _asCodexMap(value);
  if (map != null) {
    for (final key in const <String>[
      'text',
      'content',
      'message',
      'value',
      'delta',
      'summary',
    ]) {
      final text = _codexExtractText(map[key]);
      if (text.isNotEmpty) {
        return text;
      }
    }
  }
  return value.toString();
}

int? _codexTimeValueMs(dynamic value) {
  if (value == null) return null;
  if (value is num) {
    final raw = value.toInt();
    return raw < 100000000000 ? raw * 1000 : raw;
  }
  final text = value.toString().trim();
  if (text.isEmpty) return null;
  final rawInt = int.tryParse(text);
  if (rawInt != null) {
    return rawInt < 100000000000 ? rawInt * 1000 : rawInt;
  }
  return DateTime.tryParse(text)?.millisecondsSinceEpoch;
}

String _codexToolKind(String itemType) {
  return switch (itemType) {
    'commandExecution' => 'terminal',
    'fileChange' => 'file',
    'plan' => 'plan',
    _ => 'tool',
  };
}

String _codexToolTitle(String itemType, Map<String, dynamic> item) {
  if (itemType == 'commandExecution') {
    final command = _codexExtractText(item['command'] ?? item['cmd']).trim();
    if (command.isNotEmpty) {
      return _truncateCodexText(command, 48);
    }
    return 'Codex command';
  }
  if (itemType == 'fileChange') {
    return _codexFileChangeTitle(item);
  }
  if (itemType == 'plan') {
    return 'Codex plan';
  }
  return _codexGenericToolTitle(item);
}

String _codexFileChangeTitle(Map<String, dynamic> item) {
  final path =
      _asCodexString(
        item['path'] ??
            item['filePath'] ??
            item['file_path'] ??
            item['filename'] ??
            item['fileName'],
      ) ??
      _codexFirstPathFromList(item['files']) ??
      _codexFirstPathFromList(item['changes']);
  if (path == null) {
    return 'Codex file change';
  }
  final name = _codexLastPathSegment(path) ?? path;
  return _truncateCodexText('Edit $name', 42);
}

String _codexGenericToolTitle(Map<String, dynamic> item) {
  final args = _codexToolArgs(item);
  final explicit = _asCodexString(
    item['toolTitle'] ??
        item['tool_title'] ??
        item['displayName'] ??
        item['display_name'] ??
        args['toolTitle'] ??
        args['tool_title'],
  );
  if (explicit != null) {
    return _truncateCodexText(explicit, 48);
  }
  final detail = _asCodexString(
    args['command'] ??
        args['cmd'] ??
        args['query'] ??
        args['q'] ??
        args['url'] ??
        args['path'] ??
        args['filePath'] ??
        args['file_path'],
  );
  final toolName = _asCodexString(
    item['toolName'] ?? item['tool_name'] ?? item['name'],
  );
  if (detail != null) {
    final normalizedDetail = detail.contains('/') || detail.contains('\\')
        ? (_codexLastPathSegment(detail) ?? detail)
        : detail;
    final shortName = toolName == null ? null : _codexShortToolName(toolName);
    return _truncateCodexText(
      shortName == null ? normalizedDetail : '$shortName: $normalizedDetail',
      48,
    );
  }
  if (toolName != null) {
    return _truncateCodexText(_codexShortToolName(toolName), 48);
  }
  return 'Codex tool';
}

Map<String, dynamic> _codexToolArgs(Map<String, dynamic> item) {
  for (final key in const <String>['arguments', 'args', 'input']) {
    final map = _asCodexMap(item[key]);
    if (map != null) {
      return map;
    }
    final raw = _asCodexString(item[key]);
    if (raw == null) {
      continue;
    }
    try {
      final decoded = jsonDecode(raw);
      final decodedMap = _asCodexMap(decoded);
      if (decodedMap != null) {
        return decodedMap;
      }
    } catch (_) {
      continue;
    }
  }
  return const <String, dynamic>{};
}

String? _codexFirstPathFromList(dynamic value) {
  if (value is! List) {
    return null;
  }
  for (final item in value) {
    if (item is String && item.trim().isNotEmpty) {
      return item.trim();
    }
    final map = _asCodexMap(item);
    final path = _asCodexString(
      map?['path'] ??
          map?['filePath'] ??
          map?['file_path'] ??
          map?['filename'] ??
          map?['fileName'],
    );
    if (path != null) {
      return path;
    }
  }
  return null;
}

String _codexShortToolName(String toolName) {
  final normalized = toolName.trim();
  if (normalized.isEmpty) {
    return normalized;
  }
  final withoutNamespace = normalized.split(RegExp(r'[./:]')).last;
  final parts = withoutNamespace
      .split('__')
      .where((part) => part.isNotEmpty)
      .toList(growable: false);
  return parts.isEmpty ? withoutNamespace : parts.last;
}

String _truncateCodexText(String text, int maxLength) {
  final normalized = text.trim().replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return '${normalized.substring(0, maxLength)}...';
}

String _safeCodexJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}

const Set<String> _codexHistoricalToolItemTypes = <String>{
  'commandExecution',
  'fileChange',
  'tool',
  'mcpToolCall',
  'plan',
};

String? _codexLastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'/+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized.split('/').where((part) => part.isNotEmpty).toList();
  if (parts.isEmpty) {
    return normalized == '/' ? '/' : null;
  }
  return parts.last;
}

List<String> _extractCodexOptionIds(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final rawItems = _collectCodexListItems(response, listKeys);
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

List<String> _mergeCodexOptionIds({
  String? current,
  String? preferred,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final text = value?.trim() ?? '';
    if (text.isEmpty || !seen.add(text)) {
      return;
    }
    result.add(text);
  }

  add(current);
  add(preferred);
  for (final option in options) {
    add(option);
  }
  return result;
}

List<dynamic> _collectCodexListItems(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final normalizedKeys = listKeys.map(_normalizeCodexResponseKey).toSet();
  final rawItems = <dynamic>[];

  void visitMap(Map<dynamic, dynamic> map) {
    for (final entry in map.entries) {
      final key = _normalizeCodexResponseKey(entry.key.toString());
      final value = entry.value;
      if (value is List) {
        if (normalizedKeys.contains(key)) {
          rawItems.addAll(value);
        }
        for (final item in value) {
          final nested = _asCodexMap(item);
          if (nested != null) {
            visitMap(nested);
          }
        }
      } else {
        final nested = _asCodexMap(value);
        if (nested != null) {
          visitMap(nested);
        }
      }
    }
  }

  visitMap(response);
  if (rawItems.isEmpty) {
    for (final value in response.values) {
      if (value is List) {
        rawItems.addAll(value);
      }
    }
  }
  return rawItems;
}

String _normalizeCodexResponseKey(String key) {
  return key.toLowerCase().replaceAll(RegExp(r'[_-]'), '');
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

String? _extractCodexDefaultModelId(Map<String, dynamic> response) {
  for (final item in _collectCodexListItems(
    response,
    _kCodexModelListResponseKeys,
  )) {
    final map = _asCodexMap(item);
    if (map == null) {
      continue;
    }
    final isDefault = map['isDefault'] == true || map['default'] == true;
    if (!isDefault) {
      continue;
    }
    final id = _codexOptionId(map);
    if (id != null) {
      return id;
    }
  }
  return null;
}

String? _extractCodexModelDefaultReasoningEffort(
  Map<String, dynamic> response,
  String? modelId,
) {
  final normalizedModelId = modelId?.trim();
  for (final item in _collectCodexListItems(
    response,
    _kCodexModelListResponseKeys,
  )) {
    final map = _asCodexMap(item);
    if (map == null) {
      continue;
    }
    if (normalizedModelId != null &&
        normalizedModelId.isNotEmpty &&
        !_codexModelItemMatches(map, normalizedModelId)) {
      continue;
    }
    final effort = _normalizeCodexReasoningEffort(
      map['defaultReasoningEffort'] ??
          map['default_reasoning_effort'] ??
          map['defaultReasoningLevel'] ??
          map['default_reasoning_level'] ??
          map['reasoningEffort'] ??
          map['reasoning_effort'],
    );
    if (effort != null) {
      return effort;
    }
  }
  return null;
}

bool _codexModelItemMatches(
  Map<String, dynamic> item,
  String normalizedModelId,
) {
  for (final key in const <String>[
    'id',
    'model',
    'modelId',
    'model_id',
    'slug',
    'value',
    'name',
  ]) {
    final text = item[key]?.toString().trim();
    if (text == normalizedModelId) {
      return true;
    }
  }
  return false;
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

String? _extractCodexConfigReasoningEffort(Map<String, dynamic> response) {
  final direct = _normalizeCodexReasoningEffort(
    response['model_reasoning_effort'] ??
        response['reasoning_effort'] ??
        response['reasoningEffort'] ??
        response['effort'],
  );
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'modelSettings',
    'model_settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final nested = _extractCodexConfigReasoningEffort(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

List<String> _extractCodexReasoningEffortOptions(
  Map<String, dynamic> response,
) {
  final rawItems = <dynamic>[];
  for (final key in const <String>[
    'reasoningEfforts',
    'reasoning_efforts',
    'efforts',
    'modelReasoningEfforts',
    'model_reasoning_efforts',
  ]) {
    final value = response[key];
    if (value is List) {
      rawItems.addAll(value);
    }
  }
  for (final value in response.values) {
    if (value is Map) {
      rawItems.addAll(
        _extractCodexReasoningEffortOptions(
          value.map(
            (key, nestedValue) => MapEntry(key.toString(), nestedValue),
          ),
        ),
      );
    } else if (value is List) {
      for (final item in value) {
        if (item is! Map) {
          continue;
        }
        for (final key in const <String>[
          'reasoningEfforts',
          'reasoning_efforts',
          'supportedReasoningEfforts',
          'supported_reasoning_efforts',
          'efforts',
        ]) {
          final nested = item[key];
          if (nested is List) {
            rawItems.addAll(nested);
          }
        }
      }
    }
  }
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final normalized = _normalizeCodexReasoningEffort(
      item is Map
          ? (item['id'] ??
                item['value'] ??
                item['name'] ??
                item['effort'] ??
                item['reasoningEffort'] ??
                item['reasoning_effort'])
          : item,
    );
    if (normalized == null || !seen.add(normalized)) {
      continue;
    }
    result.add(normalized);
  }
  return result;
}

List<String> _mergeCodexReasoningEffortOptions({
  String? current,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final normalized = _normalizeCodexReasoningEffort(value);
    if (normalized == null || !seen.add(normalized)) {
      return;
    }
    result.add(normalized);
  }

  add(current);
  for (final option in options) {
    add(option);
  }
  for (final option in const <String>[
    'low',
    'medium',
    'high',
    _kDefaultCodexReasoningEffort,
  ]) {
    add(option);
  }
  return result;
}

String? _normalizeCodexReasoningEffort(dynamic value) {
  final text = value?.toString().trim().toLowerCase() ?? '';
  if (text.isEmpty) {
    return null;
  }
  return switch (text) {
    'no' || 'none' || 'off' => 'none',
    'min' || 'minimal' || 'minimum' => 'minimal',
    'med' || 'medium' => 'medium',
    'extra_high' ||
    'extra-high' ||
    'very_high' ||
    'very-high' ||
    'x-high' ||
    'x high' ||
    'xhigh' => 'xhigh',
    'low' || 'high' => text,
    _ => text,
  };
}

String? _codexOptionId(dynamic item) {
  if (item is String) {
    final text = item.trim();
    return text.isEmpty ? null : text;
  }
  if (item is Map) {
    for (final key in const <String>[
      'id',
      'modelId',
      'model_id',
      'slug',
      'value',
      'model',
      'name',
      'displayName',
      'display_name',
      'mode',
    ]) {
      final text = item[key]?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
    return null;
  }
  if (item is Iterable) {
    return null;
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

class _CodexRunSettingsSnapshot {
  const _CodexRunSettingsSnapshot({this.modelId, this.reasoningEffort});

  final String? modelId;
  final String? reasoningEffort;
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
