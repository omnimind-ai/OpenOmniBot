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
          _asCodexString((response['thread'] as Map?)?['id']) ??
          threadId;
      final messages = _codexMessagesFromThreadResponse(response);
      final conversation = _remoteCodexConversationFromResponse(
        runtimeId: runtimeId,
        response: response,
      );
      setState(() {
        _codexStatus = status;
        _activeCodexThreadId = resolvedThreadId;
        _currentConversationByMode[ChatPageMode.codex] = conversation;
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
        chatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
      );
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
          AppTextLocalizer.choose(
            en: 'Unsupported Codex command',
            zh: '不支持的 Codex 命令',
          ),
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
        collaborationMode:
            collaborationModeOverride ?? _activeCodexCollaborationMode,
      );
      _activeCodexThreadId =
          _asCodexString(response['threadId']) ?? _activeCodexThreadId;
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
      final locale = Localizations.localeOf(context);
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(
          content: Text(
            AppTextLocalizer.choose(
              zh: '需要登录 Codex',
              en: 'Codex login required',
              locale: locale,
            ),
          ),
          action: SnackBarAction(
            label: AppTextLocalizer.choose(
              zh: '登录',
              en: 'Login',
              locale: locale,
            ),
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

List<ChatMessageModel> _codexMessagesFromThreadResponse(
  Map<String, dynamic> response,
) {
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
              isFinal: true,
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
        chronological.add(
          ChatMessageModel.cardMessage(
            {
              'type': 'deep_thinking',
              'isLoading': false,
              'thinkingContent': text,
              'stage': ThinkingStage.complete.value,
              'taskID': turnId,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
              'endTime': createdAt.millisecondsSinceEpoch,
              'isCollapsible': true,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'thinking_snapshot',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: true,
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
    return 'Codex file change';
  }
  if (itemType == 'plan') {
    return 'Codex plan';
  }
  return _asCodexString(item['toolName'] ?? item['name']) ?? 'Codex tool';
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
