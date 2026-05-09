part of 'chat_page.dart';

mixin _ChatPageLifecycleMixin on _ChatPageStateBase {
  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);
    _loadHdPadPanePreferences();
    _checkCompanionTaskState();
    AssistsMessageService.setOnTaskFinishCallback(() {
      if (!mounted || _isCompanionToggleLoading) return;
      setState(() {
        _isCompanionModeEnabled = false;
      });
      _resetCompanionCountdown();
    });

    SchedulerBinding.instance.addPostFrameCallback((_) {
      checkConversationExists();
      Future.delayed(const Duration(milliseconds: 700), () {
        if (!mounted) return;
        unawaited(_initializeHalfScreenEngineIfNeeded());
      });
    });

    _runtimeCoordinator.ensureInitialized();
    _runtimeCoordinator.addListener(_handleRuntimeCoordinatorChanged);
    _workbenchActiveProjectService.addListener(
      _handleWorkbenchActiveProjectChanged,
    );
    unawaited(_workbenchActiveProjectService.refresh());
    AppUpdateService.statusNotifier.addListener(_handleAppUpdateStatusChanged);
    _appUpdateStatus = AppUpdateService.statusNotifier.value;
    unawaited(AppUpdateService.initialize());
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_handleExternalConversationListChanged());
        });
    _conversationMessagesChangedSubscription = AssistsMessageService
        .conversationMessagesChangedStream
        .listen((event) {
          unawaited(_handleExternalConversationMessagesChanged(event));
        });

    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    unawaited(_bootstrapConversationThread());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final mediaQuery = MediaQuery.maybeOf(context);
    if (mediaQuery != null &&
        _isHdPadLandscapeForMediaQuery(mediaQuery) &&
        _activeSurfaceMode == ChatSurfaceMode.workspace) {
      _activeSurfaceMode = ChatSurfaceMode.normal;
      _setChatIslandDisplayLayerForMode(
        ChatPageMode.normal,
        ChatIslandDisplayLayer.model,
      );
    }
    final route = ModalRoute.of(context);
    if (route is PageRoute && route != _subscribedRoute) {
      if (_subscribedRoute != null) {
        GoRouterManager.routeObserver.unsubscribe(this);
      }
      _subscribedRoute = route;
      GoRouterManager.routeObserver.subscribe(this, route);
    }
  }

  @override
  void didUpdateWidget(covariant ChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_threadTargetChanged(oldWidget.threadTarget, widget.threadTarget)) {
      debugPrint(
        '[ChatPage] thread target changed: '
        '${oldWidget.threadTarget} -> ${widget.threadTarget}',
      );
      unawaited(_reloadConversationForCurrentTarget());
    }
  }

  @override
  bool _threadTargetChanged(
    ConversationThreadTarget? oldTarget,
    ConversationThreadTarget? newTarget,
  ) {
    return oldTarget != newTarget;
  }

  @override
  Future<ConversationThreadTarget> _resolveConversationThreadTarget(
    ConversationThreadTarget? incomingTarget, {
    ConversationMode? preferredMode,
  }) async {
    final normalizedPreferredMode = preferredMode == ConversationMode.openclaw
        ? ConversationMode.normal
        : preferredMode;
    final normalizedIncomingTarget = _normalizeVisibleThreadTarget(
      incomingTarget,
    );
    if (normalizedIncomingTarget != null) {
      return normalizedIncomingTarget;
    }

    if (normalizedPreferredMode == null) {
      final lastVisible =
          await ConversationHistoryService.getLastVisibleThreadTarget();
      final normalizedLastVisible = _normalizeVisibleThreadTarget(lastVisible);
      if (normalizedLastVisible != null) {
        return normalizedLastVisible;
      }
    }

    final resolvedMode = normalizedPreferredMode ?? ConversationMode.normal;
    final savedTarget =
        await ConversationHistoryService.getCurrentConversationTarget(
          mode: resolvedMode,
        );
    final normalizedSavedTarget = _normalizeVisibleThreadTarget(savedTarget);
    if (normalizedSavedTarget != null) {
      return normalizedSavedTarget;
    }

    final latestTarget = await ConversationService.getLatestConversationTarget(
      mode: resolvedMode,
    );
    final normalizedLatestTarget = _normalizeVisibleThreadTarget(latestTarget);
    if (normalizedLatestTarget != null) {
      return normalizedLatestTarget;
    }

    return ConversationThreadTarget.newConversation(mode: resolvedMode);
  }

  ConversationThreadTarget? _normalizeVisibleThreadTarget(
    ConversationThreadTarget? target,
  ) {
    if (target == null) {
      return null;
    }
    if (target.mode == ConversationMode.openclaw) {
      return null;
    }
    return target;
  }

  @override
  Future<void> _bootstrapConversationThread() async {
    await _loadOpenClawConfig();
    await _loadTerminalEnvironmentVariables();
    final target = await _resolveConversationThreadTarget(widget.threadTarget);
    if (!mounted) return;
    await _applyConversationThreadTarget(target, syncPage: false);
    if (!mounted) return;
    unawaited(_loadNormalChatModelContext());
    unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    _notifySummarySheetReadyIfNeeded();
  }

  @override
  Future<void> _reloadConversationForCurrentTarget() async {
    final target = await _resolveConversationThreadTarget(widget.threadTarget);
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
    if (!mounted) return;
    _notifySummarySheetReadyIfNeeded();
  }

  @override
  Future<void> _applyConversationThreadTarget(
    ConversationThreadTarget target, {
    bool syncPage = true,
  }) async {
    final effectiveTarget = await _overrideTargetWithSharedDraftIfNeeded(
      target,
    );
    final targetMode = _pageModeForConversationMode(effectiveTarget.mode);
    _storeDraftForActiveConversationMode();
    _cancelNormalSurfaceModelReveal();
    if (!mounted) return;
    setState(() {
      _resolvedThreadTarget = effectiveTarget;
      _activeConversationMode = targetMode;
      _activeSurfaceMode = _surfaceForConversationMode(effectiveTarget.mode);
      _showSlashCommandPanel = false;
      _showModelMentionPanel = false;
      _activeModelMentionToken = null;
      _openClawPanelExpanded = false;
      _isBrowserOverlayVisible = false;
      _isSurfacePageScrolling = false;
    });
    _resetLocalConversationState(targetMode);
    _vlmAnswerController.clear();
    _applyDraftForConversationMode(targetMode);
    await initializeConversation();
    await _applyStagedSharedDraftIfNeeded(effectiveTarget);
    await _persistVisibleThreadTargetIfNeeded();
    if (syncPage) {
      _jumpToCurrentModePage(animate: false);
    }
  }

  @override
  Future<void> _ensureConversationModeReady(ChatPageMode mode) async {
    if (_hasPreparedConversationState(mode)) {
      return;
    }
    final target = await _resolveConversationThreadTarget(
      null,
      preferredMode: _conversationModeForPageMode(mode),
    );
    if (!mounted) return;
    await _prepareConversationModeState(mode, target);
  }

  bool _hasPreparedConversationState(ChatPageMode mode) {
    final runtime = _runtimeForMode(mode);
    final draft = _draftMessageByMode[mode] ?? '';
    return _currentConversationIdByMode[mode] != null ||
        _currentConversationByMode[mode] != null ||
        _messagesByMode[mode]!.isNotEmpty ||
        (runtime?.messages.isNotEmpty ?? false) ||
        draft.isNotEmpty ||
        _pendingAttachmentsByMode[mode]!.isNotEmpty;
  }

  Future<void> _prepareConversationModeState(
    ChatPageMode mode,
    ConversationThreadTarget target,
  ) async {
    if (target.isNewConversation) {
      return;
    }

    final conversationId = target.conversationId;
    if (conversationId == null) {
      return;
    }

    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
    final inMemoryConversation = runtime?.conversation;
    final inMemoryMessages = runtime == null || runtime.messages.isEmpty
        ? null
        : List<ChatMessageModel>.from(runtime.messages);
    final conversations = await ConversationService.getAllConversations(
      includeArchived: true,
    );
    if (!mounted) return;

    ConversationModel? conversation;
    try {
      conversation = conversations.firstWhere(
        (item) =>
            item.id == conversationId &&
            item.mode == _conversationModeForPageMode(mode),
      );
    } catch (_) {
      conversation = null;
    }

    final resolvedConversation = inMemoryConversation ?? conversation;
    final resolvedMessages =
        inMemoryMessages ??
        await ConversationHistoryService.getConversationMessages(
          conversationId,
          mode: _conversationModeForPageMode(mode),
        );
    if (!mounted) return;

    _currentConversationIdByMode[mode] = conversationId;
    _currentConversationByMode[mode] = resolvedConversation;
    _messagesByMode[mode]!
      ..clear()
      ..addAll(resolvedMessages);

    if (runtime == null) {
      _runtimeCoordinator.ensureRuntime(
        conversationId: conversationId,
        mode: _modeKey(mode),
        initialMessages: resolvedMessages,
        conversation: resolvedConversation,
        initialChatIslandDisplayLayer: _chatIslandDisplayLayerForMode(mode),
      );
    } else if (resolvedConversation != null) {
      runtime.conversation = resolvedConversation;
    }
    _syncRuntimeSnapshotForMode(
      mode,
      conversation: resolvedConversation,
      messages: resolvedMessages,
    );
  }

  @override
  Future<void> _persistVisibleThreadTargetIfNeeded() async {
    final visibleTarget = _visibleThreadTarget;
    if (visibleTarget == null) {
      return;
    }
    _resolvedThreadTarget = visibleTarget;
    await ConversationHistoryService.saveLastVisibleThreadTarget(visibleTarget);
    await ConversationHistoryService.saveCurrentConversationTarget(
      visibleTarget,
      mode: visibleTarget.mode,
    );
    await ConversationService.setCurrentConversationTarget(visibleTarget);
  }

  @override
  void _notifySummarySheetReadyIfNeeded() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      AssistsMessageService.notifySummarySheetReady();
    });
  }

  @override
  Future<void> _initializeHalfScreenEngineIfNeeded() async {
    if (_hasInitializedHalfScreen) return;
    _hasInitializedHalfScreen = true;
    await AppStateService.initHalfScreenEngine();
  }

  @override
  Future<void> _checkCompanionTaskState() async {
    try {
      final isRunning = await AssistsMessageService.isCompanionTaskRunning();
      if (!mounted) return;
      setState(() {
        _isCompanionModeEnabled = isRunning;
      });
      if (!isRunning) {
        _resetCompanionCountdown();
      }
    } catch (e) {
      debugPrint('检查陪伴状态失败: $e');
      if (!mounted) return;
      setState(() {
        _isCompanionModeEnabled = false;
      });
      _resetCompanionCountdown();
    }
  }

  @override
  Future<void> _toggleCompanionMode() async {
    if (_isCompanionToggleLoading) return;
    if (_isCompanionModeEnabled) {
      await _cancelCompanionMode();
      return;
    }
    await _startCompanionMode();
  }

  @override
  Future<void> _startCompanionMode() async {
    setState(() {
      _isCompanionToggleLoading = true;
    });

    try {
      await _initializeHalfScreenEngineIfNeeded();
      final deviceInfo = await DeviceService.getDeviceInfo();
      if (!mounted) return;
      final brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';
      final checkedSpecs = PermissionRegistry.getPermissionsByLevel(
        brand: brand,
        level: PermissionLevel.fullExecution,
      );
      final permissionDataList = PermissionService.specsToPermissionData(
        checkedSpecs,
        context: context,
      );
      await PermissionService.checkPermissions(permissionDataList);
      final allAuthorized = PermissionService.checkAllAuthorized(
        permissionDataList,
      );

      if (!allAuthorized) {
        if (!mounted) return;
        setState(() {
          _isCompanionToggleLoading = false;
        });
        await PermissionBottomSheet.show(
          context,
          initialPermissions: permissionDataList,
          deviceBrand: brand,
          onAllAuthorized: () {
            unawaited(_executeCompanionStart());
          },
        );
        return;
      }

      await _executeCompanionStart();
    } catch (e) {
      debugPrint('开启陪伴前置检查失败: $e');
      if (!mounted) return;
      setState(() {
        _isCompanionToggleLoading = false;
      });
    }
  }

  @override
  Future<void> _executeCompanionStart() async {
    if (!_isCompanionToggleLoading && mounted) {
      setState(() {
        _isCompanionToggleLoading = true;
      });
    }

    try {
      final result = await AssistsMessageService.createCompanionTask();
      if (result != true) {
        throw StateError('createCompanionTask returned false');
      }
      if (!mounted) return;
      setState(() {
        _isCompanionModeEnabled = true;
        _isCompanionToggleLoading = false;
        _companionCountdown = _ChatPageStateBase.kCompanionCountdownDuration;
        _showCompanionCountdown = true;
      });
      _startCompanionCountdown();
    } catch (e) {
      debugPrint('开启陪伴失败: $e');
      showToast('开启陪伴失败', type: ToastType.error);
      if (!mounted) return;
      setState(() {
        _isCompanionToggleLoading = false;
      });
      await _checkCompanionTaskState();
    }
  }

  @override
  Future<void> _cancelCompanionMode() async {
    setState(() {
      _isCompanionToggleLoading = true;
    });

    try {
      final result = await AssistsMessageService.cancelTask();
      if (result != true) {
        throw StateError('cancelTask returned false');
      }
      if (!mounted) return;
      setState(() {
        _isCompanionModeEnabled = false;
        _isCompanionToggleLoading = false;
      });
      _resetCompanionCountdown();
    } catch (e) {
      debugPrint('结束陪伴失败: $e');
      showToast('结束陪伴失败', type: ToastType.error);
      if (!mounted) return;
      setState(() {
        _isCompanionToggleLoading = false;
      });
      await _checkCompanionTaskState();
    }
  }

  @override
  void _startCompanionCountdown() {
    _companionCountdownTimer?.cancel();
    _companionCountdownTimer = Timer.periodic(const Duration(seconds: 1), (
      timer,
    ) {
      if (!mounted) {
        timer.cancel();
        return;
      }

      var shouldPressHome = false;
      setState(() {
        _companionCountdown -= 1;
        if (_companionCountdown <= 0) {
          _showCompanionCountdown = false;
          shouldPressHome = true;
          timer.cancel();
        }
      });

      if (shouldPressHome) {
        unawaited(_pressHomeAfterCompanionCountdown());
      }
    });
  }

  @override
  void _resetCompanionCountdown() {
    _companionCountdownTimer?.cancel();
    _companionCountdownTimer = null;
    if (!mounted) {
      _companionCountdown = _ChatPageStateBase.kCompanionCountdownDuration;
      _showCompanionCountdown = false;
      return;
    }
    setState(() {
      _companionCountdown = _ChatPageStateBase.kCompanionCountdownDuration;
      _showCompanionCountdown = false;
    });
  }

  @override
  void _interruptCompanionAutoHomeIfNeeded() {
    if (!_isCompanionModeEnabled || !_showCompanionCountdown) {
      return;
    }
    _resetCompanionCountdown();
    unawaited(AssistsMessageService.cancelCompanionGoHome());
  }

  @override
  Future<void> _pressHomeAfterCompanionCountdown() async {
    if (!_isCompanionModeEnabled) return;
    final success = await AssistsMessageService.pressHome();
    if (!success && mounted) {
      showToast('Auto return home failed', type: ToastType.error);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_runtimeCoordinator.flushAllPendingPersistence());
    unawaited(_persistVisibleThreadTargetIfNeeded());
    _cancelNormalSurfaceModelReveal();
    _conversationListChangedSubscription?.cancel();
    _conversationMessagesChangedSubscription?.cancel();
    if (_subscribedRoute != null) {
      GoRouterManager.routeObserver.unsubscribe(this);
      _subscribedRoute = null;
    }
    _runtimeCoordinator.removeListener(_handleRuntimeCoordinatorChanged);
    _workbenchActiveProjectService.removeListener(
      _handleWorkbenchActiveProjectChanged,
    );
    _workbenchActiveProjectService.dispose();
    AppUpdateService.statusNotifier.removeListener(
      _handleAppUpdateStatusChanged,
    );
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _normalMessageScrollController.dispose();
    _openClawMessageScrollController.dispose();
    _modePageController.dispose();
    _inputFocusNode.dispose();
    _vlmAnswerController.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    _companionCountdownTimer?.cancel();
    super.dispose();
  }

  @override
  void didPopNext() {
    unawaited(_handleDidPopNext());
  }

  @override
  void didPush() {}

  @override
  void didPop() {}

  @override
  void didPushNext() {
    _dismissChatInputFocus();
  }

  Future<void> _handleDidPopNext() async {
    await checkConversationExists();
    if (!mounted) return;
    await _loadNormalChatModelContext();
    if (!mounted) return;
    await _refreshLiveBrowserSessionSnapshot(syncRuntime: true);
    if (!mounted) return;
    await _workbenchActiveProjectService.refresh();
  }

  void _handleWorkbenchActiveProjectChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _handleExternalConversationListChanged() async {
    final conversationId = _currentConversationId;
    await checkConversationExists();
    if (!mounted || conversationId == null) {
      return;
    }
    final runtime = _runtimeForMode(_activeMode);
    await loadConversation(
      conversationId,
      preferInMemory: runtime?.hasInFlightTask == true,
    );
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _handleExternalConversationMessagesChanged(
    Map<String, dynamic> event,
  ) async {
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      return;
    }
    final changedConversationId = (event['conversationId'] as num?)?.toInt();
    final changedMode = ConversationMode.fromStorageValue(
      event['mode'] as String?,
    );
    if (changedConversationId != conversationId ||
        changedMode != activeConversationModeValue) {
      return;
    }
    final runtime = _runtimeForMode(_activeMode);
    await loadConversation(
      conversationId,
      preferInMemory: runtime?.hasInFlightTask == true,
    );
    if (!mounted) return;
    setState(() {});
  }

  @override
  void _onFocusChange() {}

  @override
  void _handleAppUpdateStatusChanged() {
    if (!mounted) return;
    setState(() {
      _appUpdateStatus = AppUpdateService.statusNotifier.value;
    });
  }

  @override
  double _popupMenuBottomOffset() {
    final renderObject = _inputAreaKey.currentContext?.findRenderObject();
    if (renderObject is! RenderBox || !renderObject.hasSize) {
      return 72;
    }
    final offset = renderObject.size.height - 8;
    return offset < 72 ? 72 : offset;
  }

  @override
  Future<void> _handleAppUpdateBannerTap() async {
    final status = _appUpdateStatus;
    if (status == null || !status.hasUpdate || !mounted) return;
    await showAppUpdateDialog(context, status);
  }

  @override
  int _pageIndexForSurface(ChatSurfaceMode mode) => switch (mode) {
    ChatSurfaceMode.normal => 0,
    ChatSurfaceMode.workspace => 1,
    ChatSurfaceMode.openclaw => 0,
  };

  @override
  ChatSurfaceMode _surfaceForPageIndex(int pageIndex) => switch (pageIndex) {
    1 => ChatSurfaceMode.workspace,
    _ => ChatSurfaceMode.normal,
  };

  @override
  ScrollController _scrollControllerForMode(ChatPageMode mode) {
    return mode == ChatPageMode.openclaw
        ? _openClawMessageScrollController
        : _normalMessageScrollController;
  }

  @override
  void _jumpToCurrentModePage({bool animate = true}) {
    final targetPage = _pageIndexForSurface(_activeSurfaceMode);
    if (!_modePageController.hasClients) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _jumpToCurrentModePage(animate: animate);
      });
      return;
    }
    final currentPage = _modePageController.page?.round();
    if (currentPage == targetPage) return;
    if (animate) {
      _modePageController.animateToPage(
        targetPage,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    } else {
      _modePageController.jumpToPage(targetPage);
    }
  }

  @override
  Future<void> _switchChatMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  }) async {
    final resolvedTargetMode = targetMode == ChatSurfaceMode.openclaw
        ? ChatSurfaceMode.normal
        : targetMode;
    final requestId = ++_surfaceSwitchRequestId;
    bool isStaleRequest() => !mounted || requestId != _surfaceSwitchRequestId;
    if (!mounted) return;
    if (_activeSurfaceMode == resolvedTargetMode) {
      if (syncPage) _jumpToCurrentModePage();
      if (resolvedTargetMode == ChatSurfaceMode.normal &&
          !_isSurfacePageScrolling &&
          (!syncPage || !_modePageController.hasClients)) {
        _scheduleNormalSurfaceModelReveal();
      }
      return;
    }

    _cancelNormalSurfaceModelReveal();
    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (isStaleRequest()) return;

    if (resolvedTargetMode == ChatSurfaceMode.workspace) {
      _inputFocusNode.unfocus();
      final workspacePathsFuture =
          _workspacePathsLoadFuture ??
          OmnibotResourceService.ensureWorkspacePathsLoaded();
      setState(() {
        _activeSurfaceMode = ChatSurfaceMode.workspace;
        _workspacePathsLoadFuture = workspacePathsFuture;
        _messageController.clear();
        _setChatIslandDisplayLayerForMode(
          ChatPageMode.normal,
          ChatIslandDisplayLayer.mode,
        );
        _isBrowserOverlayVisible = false;
      });
      _hideSlashCommandPanel();
      if (syncPage) _jumpToCurrentModePage();
      return;
    }

    await _ensureConversationModeReady(ChatPageMode.normal);
    if (isStaleRequest()) return;
    setState(() {
      _activeSurfaceMode = ChatSurfaceMode.normal;
      _activeConversationMode = ChatPageMode.normal;
      _resetNormalSurfaceModelRevealInterruption();
      _setChatIslandDisplayLayerForMode(
        ChatPageMode.normal,
        ChatIslandDisplayLayer.mode,
      );
    });
    _applyDraftForConversationMode(ChatPageMode.normal);
    await _persistVisibleThreadTargetIfNeeded();
    if (isStaleRequest()) return;
    _hideSlashCommandPanel();
    unawaited(_loadNormalChatModelContext());
    if (syncPage) _jumpToCurrentModePage();
    if (!_isSurfacePageScrolling &&
        (!syncPage || !_modePageController.hasClients)) {
      _scheduleNormalSurfaceModelReveal();
    }
  }

  @override
  void _handleModePageChanged(int pageIndex) {
    final targetMode = _surfaceForPageIndex(pageIndex);
    unawaited(_switchChatMode(targetMode, syncPage: false));
  }

  @override
  void _storeDraftForActiveConversationMode() {
    _draftMessageByMode[_activeConversationMode] = _messageController.text;
  }

  @override
  void _applyDraftForConversationMode(ChatPageMode mode) {
    final draft = _draftMessageByMode[mode] ?? '';
    _messageController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
  }

  Future<ConversationThreadTarget> _overrideTargetWithSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final staged = _activeStagedSharedOpenDraft();
    if (staged != null && staged.hasContent) {
      return ConversationThreadTarget.newConversation(
        mode: ConversationMode.normal,
        fromNativeRoute: true,
        requestKey: staged.requestKey,
      );
    }

    final payload = await SharedOpenDraftService.getPendingDraft();
    if (payload == null || !payload.hasContent) {
      return target;
    }
    _stagedSharedOpenDraft = payload;
    _stagedSharedOpenDraftExpiresAt =
        DateTime.now().millisecondsSinceEpoch + 5000;
    return ConversationThreadTarget.newConversation(
      mode: ConversationMode.normal,
      fromNativeRoute: true,
      requestKey: payload.requestKey,
    );
  }

  Future<void> _applyStagedSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final payload = _activeStagedSharedOpenDraft();
    if (payload == null ||
        !payload.hasContent ||
        !target.isNewConversation ||
        target.mode != ConversationMode.normal) {
      return;
    }

    final attachments = payload.attachments
        .map(
          (item) => ChatInputAttachment(
            id: item.id.isNotEmpty ? item.id : item.path,
            name: item.name.isNotEmpty ? item.name : item.path.split('/').last,
            path: item.path,
            size: item.size,
            mimeType: item.mimeType,
            isImage: item.isImage,
          ),
        )
        .toList();

    if (!mounted) {
      return;
    }
    setState(() {
      _draftMessageByMode[ChatPageMode.normal] = payload.text ?? '';
      _pendingAttachmentsByMode[ChatPageMode.normal]!
        ..clear()
        ..addAll(attachments);
    });
    if (_activeConversationMode == ChatPageMode.normal) {
      _applyDraftForConversationMode(ChatPageMode.normal);
    }
    await SharedOpenDraftService.clearPendingDraft();
  }

  SharedOpenDraftPayload? _activeStagedSharedOpenDraft() {
    final payload = _stagedSharedOpenDraft;
    final expiresAt = _stagedSharedOpenDraftExpiresAt;
    if (payload == null) {
      return null;
    }
    if (expiresAt != null &&
        DateTime.now().millisecondsSinceEpoch > expiresAt) {
      _stagedSharedOpenDraft = null;
      _stagedSharedOpenDraftExpiresAt = null;
      return null;
    }
    return payload;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if ((state == AppLifecycleState.resumed ||
            state == AppLifecycleState.inactive) &&
        _currentConversationId != null) {
      Future.delayed(const Duration(milliseconds: 100), () async {
        if (!mounted) return;
        await checkConversationExists();
      });
    }
    if (state == AppLifecycleState.resumed) {
      _notifySummarySheetReadyIfNeeded();
      unawaited(_checkCompanionTaskState());
      unawaited(AppUpdateService.refreshIfNeeded());
      unawaited(_loadNormalChatModelContext());
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    }
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_runtimeCoordinator.flushAllPendingPersistence());
      unawaited(_persistVisibleThreadTargetIfNeeded());
      _resetCompanionCountdown();
      unawaited(AssistsMessageService.cancelCompanionGoHome());
    }
  }
}
