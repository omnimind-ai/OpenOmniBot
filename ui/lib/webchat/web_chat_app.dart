// ignore_for_file: avoid_web_libraries_in_flutter, deprecated_member_use

import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:html' as html;

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/webchat/web_backends.dart';

enum _ShellSection { chat, workspace, browser }

const Color _kPageBackground = Color(0xFFF4F7FC);
const Color _kPanelSurface = Color(0xFFF9FCFF);
const Color _kPanelBorder = Color(0xFFD9E6FB);
const Color _kUserBubble = Color(0xCCF1F8FF);
const Color _kPrimaryText = Color(0xFF353E53);
const Color _kSecondaryText = Color(0xFF617390);
const Color _kSubtleText = Color(0xFF9DA9BB);
const Color _kAttachmentSurface = Color(0xFFE4EEFF);
const Color _kAttachmentBorder = Color(0xFFD0DEFA);
const Color _kAttachmentIcon = Color(0xFF375EAF);
const Color _kAttachmentText = Color(0xFF35517A);
const Color _kAccentBlue = Color(0xFF4F83FF);
const Color _kAccentGreen = Color(0xFF52C41A);
const double _kDesktopDividerHitWidth = 18;
const double _kDesktopLeftPaneMinWidth = 256;
const double _kDesktopLeftPaneMaxWidth = 420;
const double _kDesktopCenterPaneMinWidth = 520;
const double _kDesktopRightPaneMinWidth = 320;
const double _kDesktopRightPaneMaxWidth = 520;

class _DesktopPaneLayout {
  const _DesktopPaneLayout({
    required this.leftWidth,
    required this.centerWidth,
    required this.rightWidth,
  });

  final double leftWidth;
  final double centerWidth;
  final double rightWidth;
}

class _WorkspaceBreadcrumbSegment {
  const _WorkspaceBreadcrumbSegment({
    required this.label,
    required this.path,
    required this.isCurrent,
  });

  final String label;
  final String path;
  final bool isCurrent;
}

class WebChatApp extends StatelessWidget {
  const WebChatApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Omnibot Web Chat',
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      localeResolutionCallback: (locale, supportedLocales) {
        final languageCode = locale?.languageCode.toLowerCase();
        final resolvedLocale = languageCode == 'en'
            ? const Locale('en')
            : const Locale('zh');
        LegacyTextLocalizer.setResolvedLocale(resolvedLocale);
        return resolvedLocale;
      },
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF2E6AE6),
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: _kPageBackground,
        fontFamily: 'PingFang SC',
      ),
      home: const _WebChatHome(),
    );
  }
}

class _PendingAttachment {
  const _PendingAttachment({
    required this.name,
    required this.mimeType,
    required this.size,
    required this.dataUrl,
    required this.isImage,
  });

  final String name;
  final String mimeType;
  final int size;
  final String dataUrl;
  final bool isImage;

  Map<String, dynamic> toPayload() {
    return <String, dynamic>{
      'fileName': name,
      'mimeType': mimeType,
      'size': size,
      'dataUrl': dataUrl,
      'isImage': isImage,
    };
  }
}

class _WebChatHome extends StatefulWidget {
  const _WebChatHome();

  @override
  State<_WebChatHome> createState() => _WebChatHomeState();
}

class _WebChatHomeState extends State<_WebChatHome> {
  final WebChatHttpClient _client = WebChatHttpClient();
  final WebRunStreamBackend _events = WebRunStreamBackend();
  final TextEditingController _tokenController = TextEditingController();
  final TextEditingController _messageController = TextEditingController();
  final TextEditingController _workspaceEditorController =
      TextEditingController();
  final TextEditingController _browserUrlController = TextEditingController();
  final ScrollController _chatScrollController = ScrollController();

  StreamSubscription<WebChatEvent>? _eventsSubscription;
  Timer? _workspaceAutoRefreshTimer;

  bool _booting = true;
  bool _authenticated = false;
  bool _loadingConversations = false;
  bool _sendingMessage = false;
  bool _workspaceBusy = false;
  bool _workspaceReloading = false;
  bool _browserBusy = false;
  bool _archivedOnly = false;
  bool _workspaceDirty = false;
  String? _error;
  String? _workspaceCurrentPath;
  String? _workspaceSelectedFilePath;
  String? _activeClarifyTaskId;
  int _workspaceReloadRequestSerial = 0;
  int _browserFrameSeed = 0;
  _ShellSection _mobileSection = _ShellSection.chat;
  double _desktopLeftPaneWidth = 320;
  double _desktopRightPaneWidth = 360;

  List<ConversationModel> _conversations = <ConversationModel>[];
  ConversationModel? _selectedConversation;
  List<ChatMessageModel> _messages = <ChatMessageModel>[];
  final List<_PendingAttachment> _pendingAttachments = <_PendingAttachment>[];
  List<Map<String, dynamic>> _workspaceItems = <Map<String, dynamic>>[];
  Map<String, dynamic>? _workspaceInfo;
  Map<String, dynamic>? _browserSnapshot;

  @override
  void initState() {
    super.initState();
    _restoreDesktopPanePreferences();
    _messageController.addListener(_handleComposerChanged);
    _workspaceEditorController.addListener(() {
      if (_workspaceSelectedFilePath != null) {
        setState(() {
          _workspaceDirty = true;
        });
      }
    });
    _tryBootstrapFromSavedToken();
  }

  @override
  void dispose() {
    _eventsSubscription?.cancel();
    _workspaceAutoRefreshTimer?.cancel();
    _events.dispose();
    _client.dispose();
    _tokenController.dispose();
    _messageController.dispose();
    _workspaceEditorController.dispose();
    _browserUrlController.dispose();
    _chatScrollController.dispose();
    super.dispose();
  }

  void _handleComposerChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  KeyEventResult _handleComposerKeyEvent(KeyEvent event) {
    if (event is! KeyDownEvent ||
        event.logicalKey != LogicalKeyboardKey.enter) {
      return KeyEventResult.ignored;
    }
    final isComposing =
        _messageController.value.composing.isValid &&
        !_messageController.value.composing.isCollapsed;
    if (isComposing ||
        HardwareKeyboard.instance.isShiftPressed ||
        HardwareKeyboard.instance.isControlPressed ||
        HardwareKeyboard.instance.isAltPressed ||
        HardwareKeyboard.instance.isMetaPressed) {
      return KeyEventResult.ignored;
    }
    final hasPayload =
        _messageController.text.trim().isNotEmpty ||
        _pendingAttachments.isNotEmpty;
    if (_sendingMessage || !hasPayload) {
      return KeyEventResult.handled;
    }
    unawaited(_submitComposer());
    return KeyEventResult.handled;
  }

  void _restoreDesktopPanePreferences() {
    final rawLeft = html.window.localStorage['omnibot_webchat_left_pane_width'];
    final rawRight =
        html.window.localStorage['omnibot_webchat_right_pane_width'];
    final parsedLeft = double.tryParse(rawLeft ?? '');
    final parsedRight = double.tryParse(rawRight ?? '');
    if (parsedLeft != null && parsedLeft.isFinite) {
      _desktopLeftPaneWidth = parsedLeft;
    }
    if (parsedRight != null && parsedRight.isFinite) {
      _desktopRightPaneWidth = parsedRight;
    }
  }

  void _persistDesktopPanePreferences() {
    html.window.localStorage['omnibot_webchat_left_pane_width'] =
        _desktopLeftPaneWidth.toStringAsFixed(0);
    html.window.localStorage['omnibot_webchat_right_pane_width'] =
        _desktopRightPaneWidth.toStringAsFixed(0);
  }

  Future<void> _tryBootstrapFromSavedToken() async {
    final queryToken = Uri.base.queryParameters['token']?.trim();
    final savedToken = html.window.localStorage['omnibot_webchat_token']
        ?.trim();
    final token =
        (queryToken?.isNotEmpty == true ? queryToken : savedToken) ?? '';
    if (token.isEmpty) {
      setState(() {
        _booting = false;
      });
      return;
    }
    _tokenController.text = token;
    await _bootstrap(token);
  }

  Future<void> _bootstrap(String token) async {
    setState(() {
      _booting = true;
      _error = null;
    });
    try {
      await _client.bootstrapSession(token);
      html.window.localStorage['omnibot_webchat_token'] = token;
      final bootstrap = await _client.bootstrap();
      final workspace = Map<String, dynamic>.from(
        (bootstrap['workspace'] as Map?) ?? const <String, dynamic>{},
      );
      final browser = Map<String, dynamic>.from(
        (bootstrap['browser'] as Map?) ?? const <String, dynamic>{},
      );
      _workspaceInfo = Map<String, dynamic>.from(
        (workspace['workspace'] as Map?) ?? const <String, dynamic>{},
      );
      _workspaceCurrentPath =
          (workspace['root'] as Map?)?['path']?.toString() ??
          _workspaceInfo?['rootPath']?.toString();
      _workspaceItems =
          ((workspace['root'] as Map?)?['items'] as List?)
              ?.whereType<Map>()
              .map((item) => Map<String, dynamic>.from(item))
              .toList() ??
          <Map<String, dynamic>>[];
      _browserSnapshot = browser;
      _browserUrlController.text = (browser['currentUrl'] ?? '').toString();
      await _loadConversations(preserveSelection: false);
      _connectEvents();
      _startWorkspaceAutoRefresh();
      setState(() {
        _authenticated = true;
        _booting = false;
      });
      unawaited(_reloadWorkspace(reportError: false));
    } catch (error) {
      setState(() {
        _error = error.toString();
        _authenticated = false;
        _booting = false;
      });
    }
  }

  void _connectEvents() {
    _eventsSubscription?.cancel();
    _eventsSubscription = _events.connect().listen(_handleEvent);
  }

  Future<void> _loadConversations({required bool preserveSelection}) async {
    setState(() {
      _loadingConversations = true;
    });
    final conversations =
        (await _client.listConversations(
            includeArchived: _archivedOnly,
            archivedOnly: _archivedOnly,
          )).where((conversation) {
            return _archivedOnly
                ? conversation.isArchived
                : !conversation.isArchived;
          }).toList()
          ..sort((left, right) => right.updatedAt.compareTo(left.updatedAt));
    ConversationModel? nextSelection;
    if (preserveSelection && _selectedConversation != null) {
      for (final conversation in conversations) {
        if (conversation.id == _selectedConversation!.id &&
            conversation.mode == _selectedConversation!.mode) {
          nextSelection = conversation;
          break;
        }
      }
    }
    nextSelection ??= conversations.isNotEmpty ? conversations.first : null;
    setState(() {
      _conversations = conversations;
      _selectedConversation = nextSelection;
      _loadingConversations = false;
    });
    if (nextSelection != null) {
      await _selectConversation(nextSelection);
    } else {
      setState(() {
        _messages = <ChatMessageModel>[];
      });
    }
  }

  Future<void> _selectConversation(ConversationModel conversation) async {
    final messages = await _client.getMessages(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) return;
    setState(() {
      _selectedConversation = conversation;
      _messages = messages;
    });
    _refreshBrowserSnapshotForMessages(messages);
    _scrollChatToBottom();
  }

  Future<void> _createConversation() async {
    final conversation = await _client.createConversation(
      title: LegacyTextLocalizer.isEnglish ? 'New conversation' : '新对话',
      mode: ConversationMode.normal,
    );
    setState(() {
      _archivedOnly = false;
      _selectedConversation = conversation;
    });
    await _loadConversations(preserveSelection: true);
  }

  Future<void> _sendMessage({
    String? overrideText,
    List<_PendingAttachment>? overrideAttachments,
  }) async {
    final text = (overrideText ?? _messageController.text).trim();
    final attachments = overrideAttachments ?? _pendingAttachments;
    if (text.isEmpty && attachments.isEmpty) {
      return;
    }
    setState(() {
      _sendingMessage = true;
      _error = null;
    });
    try {
      var conversation = _selectedConversation;
      if (conversation == null) {
        conversation = await _client.createConversation(
          title: text.isEmpty
              ? (LegacyTextLocalizer.isEnglish ? 'New conversation' : '新对话')
              : text,
          mode: ConversationMode.normal,
        );
        setState(() {
          _selectedConversation = conversation;
        });
        await _loadConversations(preserveSelection: true);
      }
      await _client.startRun(
        conversation.id,
        userMessage: text,
        mode: conversation.mode,
        attachments: attachments.map((item) => item.toPayload()).toList(),
      );
      if (overrideText == null) {
        _messageController.clear();
      }
      if (overrideAttachments == null) {
        _pendingAttachments.clear();
      }
      setState(() {
        _sendingMessage = false;
      });
      _scrollChatToBottom(animate: true);
    } catch (error) {
      setState(() {
        _sendingMessage = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _submitComposer() async {
    if (_activeClarifyTaskId != null) {
      final text = _messageController.text.trim();
      if (text.isEmpty) {
        return;
      }
      await _client.clarifyTask(_activeClarifyTaskId!, text);
      _messageController.clear();
      setState(() {
        _activeClarifyTaskId = null;
      });
      return;
    }
    await _sendMessage();
  }

  Future<void> _pickAttachments() async {
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: true,
      withData: true,
      type: FileType.any,
    );
    if (result == null) {
      return;
    }
    final attachments = result.files.where((file) => file.bytes != null).map((
      file,
    ) {
      final mimeType = file.extension == null
          ? 'application/octet-stream'
          : _mimeTypeForExtension(file.extension!);
      final bytes = file.bytes!;
      final dataUrl =
          'data:$mimeType;base64,${base64Encode(Uint8List.fromList(bytes))}';
      return _PendingAttachment(
        name: file.name,
        mimeType: mimeType,
        size: bytes.length,
        dataUrl: dataUrl,
        isImage: mimeType.startsWith('image/'),
      );
    }).toList();
    setState(() {
      _pendingAttachments.addAll(attachments);
    });
  }

  Future<void> _updateArchiveState(bool archived) async {
    final conversation = _selectedConversation;
    if (conversation == null) return;
    final updated = await _client.updateConversation(
      conversation.copyWith(isArchived: archived),
    );
    setState(() {
      _selectedConversation = updated;
    });
    await _loadConversations(preserveSelection: true);
  }

  Future<void> _deleteSelectedConversation() async {
    final conversation = _selectedConversation;
    if (conversation == null) return;
    await _client.deleteConversation(conversation.id);
    await _loadConversations(preserveSelection: false);
  }

  void _startWorkspaceAutoRefresh() {
    _workspaceAutoRefreshTimer?.cancel();
    _workspaceAutoRefreshTimer = Timer.periodic(const Duration(seconds: 2), (
      _,
    ) {
      if (!_authenticated ||
          !mounted ||
          _workspaceBusy ||
          _workspaceReloading ||
          html.document.hidden == true) {
        return;
      }
      if ((_workspaceCurrentPath ?? '').trim().isEmpty) {
        return;
      }
      unawaited(_reloadWorkspace(reportError: false));
    });
  }

  String _normalizeWorkspacePath(String path) {
    final trimmed = path.trim();
    if (trimmed.isEmpty || trimmed == '/') {
      return '/';
    }
    return trimmed.replaceFirst(RegExp(r'/+$'), '');
  }

  String? get _workspaceRootPath {
    final root = (_workspaceInfo?['rootPath'] ?? '').toString().trim();
    if (root.isNotEmpty) {
      return _normalizeWorkspacePath(root);
    }
    final current = (_workspaceCurrentPath ?? '').trim();
    if (current.isEmpty) {
      return null;
    }
    return _normalizeWorkspacePath(current);
  }

  bool _isWorkspacePathUnderRoot(String path, String rootPath) {
    if (path == rootPath) return true;
    if (rootPath == '/') return path.startsWith('/');
    return path.startsWith('$rootPath/');
  }

  List<_WorkspaceBreadcrumbSegment> get _workspaceBreadcrumbs {
    final currentPath = (_workspaceCurrentPath ?? '').trim();
    if (currentPath.isEmpty) {
      return const <_WorkspaceBreadcrumbSegment>[];
    }

    final normalizedCurrent = _normalizeWorkspacePath(currentPath);
    final normalizedRoot =
        _workspaceRootPath ?? _normalizeWorkspacePath(normalizedCurrent);

    if (!_isWorkspacePathUnderRoot(normalizedCurrent, normalizedRoot)) {
      return <_WorkspaceBreadcrumbSegment>[
        _WorkspaceBreadcrumbSegment(
          label: normalizedCurrent,
          path: normalizedCurrent,
          isCurrent: true,
        ),
      ];
    }

    final segments = <_WorkspaceBreadcrumbSegment>[
      _WorkspaceBreadcrumbSegment(
        label: normalizedRoot,
        path: normalizedRoot,
        isCurrent: normalizedCurrent == normalizedRoot,
      ),
    ];

    final relative = normalizedCurrent == normalizedRoot
        ? ''
        : normalizedCurrent.substring(
            normalizedRoot == '/' ? 1 : normalizedRoot.length + 1,
          );
    if (relative.isEmpty) {
      return segments;
    }

    final parts = relative
        .split('/')
        .where((segment) => segment.trim().isNotEmpty)
        .toList(growable: false);
    var runningPath = normalizedRoot;
    for (final part in parts) {
      runningPath = runningPath == '/' ? '/$part' : '$runningPath/$part';
      segments.add(
        _WorkspaceBreadcrumbSegment(
          label: part,
          path: runningPath,
          isCurrent: runningPath == normalizedCurrent,
        ),
      );
    }
    return segments;
  }

  Future<void> _reloadWorkspace({String? path, bool reportError = true}) async {
    final targetPath = path ?? _workspaceCurrentPath;
    if ((targetPath ?? '').trim().isEmpty) {
      return;
    }
    final requestId = ++_workspaceReloadRequestSerial;
    _workspaceReloading = true;
    try {
      final payload = await _client.list(path: targetPath);
      if (!mounted || requestId != _workspaceReloadRequestSerial) return;
      setState(() {
        _workspaceCurrentPath = (payload['path'] ?? _workspaceCurrentPath ?? '')
            .toString();
        _workspaceItems = ((payload['items'] as List?) ?? const <dynamic>[])
            .whereType<Map>()
            .map((item) => Map<String, dynamic>.from(item))
            .toList();
      });
    } catch (error) {
      if (!mounted ||
          !reportError ||
          requestId != _workspaceReloadRequestSerial) {
        return;
      }
      setState(() {
        _error = error.toString();
      });
    } finally {
      if (requestId == _workspaceReloadRequestSerial) {
        _workspaceReloading = false;
      }
    }
  }

  Future<void> _openWorkspaceEntry(Map<String, dynamic> entry) async {
    final isDirectory = entry['isDirectory'] == true;
    final path = (entry['path'] ?? '').toString();
    if (path.isEmpty) {
      return;
    }
    if (isDirectory) {
      await _reloadWorkspace(path: path);
      return;
    }
    setState(() {
      _workspaceBusy = true;
      _workspaceSelectedFilePath = path;
      _workspaceDirty = false;
    });
    try {
      final payload = await _client.readFile(path);
      if (!mounted) return;
      setState(() {
        _workspaceSelectedFilePath = path;
        _workspaceEditorController.text = (payload['content'] ?? '').toString();
        _workspaceDirty = false;
      });
    } finally {
      if (mounted) {
        setState(() {
          _workspaceBusy = false;
        });
      }
    }
  }

  Future<void> _saveWorkspaceFile() async {
    final path = _workspaceSelectedFilePath;
    if (path == null) return;
    setState(() {
      _workspaceBusy = true;
    });
    try {
      await _client.writeFile(path, _workspaceEditorController.text);
      await _reloadWorkspace();
      if (!mounted) return;
      setState(() {
        _workspaceDirty = false;
      });
    } finally {
      if (mounted) {
        setState(() {
          _workspaceBusy = false;
        });
      }
    }
  }

  Future<void> _refreshBrowserSnapshot({bool reportError = true}) async {
    try {
      final snapshot = await _client.snapshot();
      if (!mounted) return;
      setState(() {
        _browserSnapshot = snapshot;
        _browserUrlController.text = (snapshot['currentUrl'] ?? '').toString();
        _browserFrameSeed++;
      });
    } catch (error) {
      if (!mounted || !reportError) {
        return;
      }
      setState(() {
        _error = error.toString();
      });
    }
  }

  bool _containsBrowserToolCard(List<ChatMessageModel> messages) {
    for (final message in messages) {
      if (message.type != 2) {
        continue;
      }
      final cardData = message.cardData;
      if (cardData == null) {
        continue;
      }
      if ((cardData['type'] ?? '').toString() != 'agent_tool_summary') {
        continue;
      }
      if ((cardData['toolType'] ?? '').toString() == 'browser') {
        return true;
      }
    }
    return false;
  }

  void _refreshBrowserSnapshotForMessages(List<ChatMessageModel> messages) {
    if (_containsBrowserToolCard(messages)) {
      unawaited(_refreshBrowserSnapshot(reportError: false));
    }
  }

  Future<void> _navigateBrowser() async {
    final url = _browserUrlController.text.trim();
    if (url.isEmpty) return;
    await _runBrowserAction(<String, dynamic>{
      'action': 'navigate',
      'url': url,
      'tool_title': 'Web Chat Navigate',
    });
  }

  Future<void> _runBrowserAction(Map<String, dynamic> payload) async {
    setState(() {
      _browserBusy = true;
    });
    try {
      final result = await _client.action(payload);
      final snapshot = Map<String, dynamic>.from(
        (result['snapshot'] as Map?) ?? const <String, dynamic>{},
      );
      if (!mounted) return;
      setState(() {
        _browserSnapshot = snapshot;
        _browserUrlController.text = (snapshot['currentUrl'] ?? '').toString();
        _browserFrameSeed++;
      });
    } finally {
      if (mounted) {
        setState(() {
          _browserBusy = false;
        });
      }
    }
  }

  void _handleEvent(WebChatEvent event) {
    final data = event.data;
    switch (event.event) {
      case 'conversation_created':
      case 'conversation_updated':
      case 'conversation_deleted':
        unawaited(_loadConversations(preserveSelection: true));
        break;
      case 'messages_replaced':
        final key = _conversationKeyFromPayload(data);
        if (key != null && _selectedConversation != null) {
          if (key == _conversationKey(_selectedConversation!)) {
            final messages = ((data['messages'] as List?) ?? const <dynamic>[])
                .whereType<Map>()
                .map(
                  (item) => ChatMessageModel.fromJson(
                    Map<String, dynamic>.from(item.cast<String, dynamic>()),
                  ),
                )
                .toList();
            setState(() {
              _messages = messages;
            });
            _scrollChatToBottom();
          }
        }
        break;
      case 'agent_stream_event':
        _handleAgentStreamEventPayload(data);
        break;
      case 'browser_snapshot_updated':
        final snapshot = Map<String, dynamic>.from(
          (data['snapshot'] as Map?) ?? const <String, dynamic>{},
        );
        setState(() {
          _browserSnapshot = snapshot;
          _browserUrlController.text = (snapshot['currentUrl'] ?? '')
              .toString();
          _browserFrameSeed++;
        });
        break;
      case 'workspace_changed':
        if (_workspaceCurrentPath != null) {
          unawaited(_reloadWorkspace(reportError: false));
        }
        break;
    }
  }

  void _handleAgentStreamEventPayload(Map<String, dynamic> payload) {
    late final AgentStreamEvent event;
    try {
      event = AgentStreamEvent.fromMap(payload);
    } catch (_) {
      return;
    }

    switch (event.kind) {
      case AgentStreamEventKind.toolCompleted:
        if ((event.raw['toolType'] ?? '').toString().trim() == 'browser') {
          unawaited(_refreshBrowserSnapshot(reportError: false));
        }
        break;
      case AgentStreamEventKind.clarifyRequired:
        setState(() {
          _activeClarifyTaskId = event.taskId;
        });
        break;
      case AgentStreamEventKind.completed:
      case AgentStreamEventKind.error:
        if (mounted) {
          setState(() {
            _activeClarifyTaskId = null;
          });
        }
        break;
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
      case AgentStreamEventKind.textSnapshot:
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.permissionRequired:
        break;
    }
  }

  String? _conversationKeyFromPayload(Map<String, dynamic> payload) {
    final conversationId = (payload['conversationId'] as num?)?.toInt();
    final rawMode =
        payload['conversationMode'] as String? ?? payload['mode'] as String?;
    if (conversationId == null) {
      return null;
    }
    return '${ConversationMode.fromStorageValue(rawMode).storageValue}:$conversationId';
  }

  String _conversationKey(ConversationModel conversation) {
    return '${conversation.mode.storageValue}:${conversation.id}';
  }

  void _scrollChatToBottom({bool animate = false}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_chatScrollController.hasClients) {
        return;
      }
      final target = _chatScrollController.position.maxScrollExtent;
      if (animate) {
        _chatScrollController.animateTo(
          target,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
        );
        return;
      }
      _chatScrollController.jumpTo(target);
    });
  }

  List<ChatMessageModel> get _displayMessages {
    final conversation = _selectedConversation;
    if (conversation == null) {
      return _messages.reversed.toList();
    }
    final merged = <ChatMessageModel>[..._messages];
    merged.sort((a, b) => a.createAt.compareTo(b.createAt));
    return merged;
  }

  @override
  Widget build(BuildContext context) {
    if (_booting) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!_authenticated) {
      return _buildLogin(context);
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final isDesktop = constraints.maxWidth >= 980;
        if (!isDesktop) {
          return _buildMobileShell(context);
        }
        return _buildDesktopShell(context, constraints.maxWidth);
      },
    );
  }

  Widget _buildLogin(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            child: Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Omnibot Web Chat',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    Localizations.localeOf(context).languageCode == 'en'
                        ? 'Enter local MCP Server Token to exchange for a Web Chat session cookie.'
                        : '输入本机 MCP Server Token 以换取 Web Chat 会话 Cookie。',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 20),
                  TextField(
                    controller: _tokenController,
                    decoration: const InputDecoration(
                      labelText: 'Server Token',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: () => _bootstrap(_tokenController.text.trim()),
                    child: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'Connect'
                          : '连接',
                    ),
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    Text(_error!, style: const TextStyle(color: Colors.red)),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  _DesktopPaneLayout _resolveDesktopLayout(double maxWidth) {
    final availableWidth = math.max(
      0,
      maxWidth - (_kDesktopDividerHitWidth * 2),
    );

    var leftWidth = _desktopLeftPaneWidth.clamp(
      _kDesktopLeftPaneMinWidth,
      _kDesktopLeftPaneMaxWidth,
    );
    var rightWidth = _desktopRightPaneWidth.clamp(
      _kDesktopRightPaneMinWidth,
      _kDesktopRightPaneMaxWidth,
    );

    final maxLeftBySpace = math.max(
      _kDesktopLeftPaneMinWidth,
      availableWidth - rightWidth - _kDesktopCenterPaneMinWidth,
    );
    leftWidth = leftWidth.clamp(_kDesktopLeftPaneMinWidth, maxLeftBySpace);

    final maxRightBySpace = math.max(
      _kDesktopRightPaneMinWidth,
      availableWidth - leftWidth - _kDesktopCenterPaneMinWidth,
    );
    rightWidth = rightWidth.clamp(_kDesktopRightPaneMinWidth, maxRightBySpace);

    var centerWidth = availableWidth - leftWidth - rightWidth;
    if (centerWidth < _kDesktopCenterPaneMinWidth) {
      final rightFlexible = rightWidth - _kDesktopRightPaneMinWidth;
      if (rightFlexible > 0) {
        final delta = math.min(
          _kDesktopCenterPaneMinWidth - centerWidth,
          rightFlexible,
        );
        rightWidth -= delta;
        centerWidth += delta;
      }
    }
    if (centerWidth < _kDesktopCenterPaneMinWidth) {
      final leftFlexible = leftWidth - _kDesktopLeftPaneMinWidth;
      if (leftFlexible > 0) {
        final delta = math.min(
          _kDesktopCenterPaneMinWidth - centerWidth,
          leftFlexible,
        );
        leftWidth -= delta;
        centerWidth += delta;
      }
    }

    return _DesktopPaneLayout(
      leftWidth: leftWidth,
      centerWidth: centerWidth,
      rightWidth: rightWidth,
    );
  }

  Widget _buildDesktopShell(BuildContext context, double maxWidth) {
    final layout = _resolveDesktopLayout(maxWidth);
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              SizedBox(
                width: layout.leftWidth,
                child: _buildPaneSurface(
                  child: _buildConversationSidebar(context),
                ),
              ),
              _PaneResizeHandle(
                onDragUpdate: (delta) {
                  setState(() {
                    _desktopLeftPaneWidth = layout.leftWidth + delta;
                    _persistDesktopPanePreferences();
                  });
                },
              ),
              SizedBox(
                width: layout.centerWidth,
                child: _buildPaneSurface(child: _buildChatPane(context)),
              ),
              _PaneResizeHandle(
                onDragUpdate: (delta) {
                  setState(() {
                    _desktopRightPaneWidth = layout.rightWidth - delta;
                    _persistDesktopPanePreferences();
                  });
                },
              ),
              SizedBox(
                width: layout.rightWidth,
                child: _buildPaneSurface(child: _buildSidePanels(context)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPaneSurface({required Widget child}) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _kPanelSurface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: _kPanelBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x121A2433),
            blurRadius: 28,
            offset: Offset(0, 12),
          ),
        ],
      ),
      child: ClipRRect(borderRadius: BorderRadius.circular(24), child: child),
    );
  }

  Widget _buildMobileShell(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _selectedConversation?.title ?? 'Omnibot Web Chat',
          overflow: TextOverflow.ellipsis,
        ),
      ),
      body: SafeArea(
        child: switch (_mobileSection) {
          _ShellSection.chat => Column(
            children: [
              SizedBox(height: 220, child: _buildConversationSidebar(context)),
              Expanded(child: _buildChatPane(context)),
            ],
          ),
          _ShellSection.workspace => _buildWorkspacePanel(context),
          _ShellSection.browser => _buildBrowserPanel(context),
        },
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _mobileSection.index,
        onDestinationSelected: (index) {
          setState(() {
            _mobileSection = _ShellSection.values[index];
          });
        },
        destinations: [
          NavigationDestination(
            icon: const Icon(Icons.chat_bubble_outline),
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Chat'
                : '聊天',
          ),
          NavigationDestination(
            icon: const Icon(Icons.folder_outlined),
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Workspace'
                : '工作区',
          ),
          NavigationDestination(
            icon: const Icon(Icons.language_outlined),
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Browser'
                : '浏览器',
          ),
        ],
      ),
    );
  }

  Widget _buildConversationSidebar(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'Conversations'
                          : '聊天记录',
                      style: const TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                        color: _kPrimaryText,
                      ),
                    ),
                  ),
                  FilledButton.icon(
                    onPressed: _createConversation,
                    icon: const Icon(Icons.add, size: 18),
                    label: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'New'
                          : '新建',
                    ),
                    style: FilledButton.styleFrom(
                      backgroundColor: _kAccentBlue,
                      foregroundColor: Colors.white,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              SegmentedButton<bool>(
                style: SegmentedButton.styleFrom(
                  selectedBackgroundColor: const Color(0xFFEAF3FF),
                  selectedForegroundColor: _kPrimaryText,
                  foregroundColor: _kSecondaryText,
                  side: const BorderSide(color: _kPanelBorder),
                ),
                segments: [
                  ButtonSegment<bool>(
                    value: false,
                    label: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'Active'
                          : '活跃',
                    ),
                  ),
                  ButtonSegment<bool>(
                    value: true,
                    label: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'Archived'
                          : '归档',
                    ),
                  ),
                ],
                selected: <bool>{_archivedOnly},
                onSelectionChanged: (selection) {
                  setState(() {
                    _archivedOnly = selection.first;
                  });
                  unawaited(_loadConversations(preserveSelection: false));
                },
              ),
            ],
          ),
        ),
        if (_loadingConversations) const LinearProgressIndicator(minHeight: 2),
        Expanded(
          child: _conversations.isEmpty
              ? Center(
                  child: Text(
                    _archivedOnly
                        ? context.l10n.chatHistoryNoArchived
                        : context.l10n.webchatNoChats,
                    style: const TextStyle(color: _kSecondaryText),
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
                  itemCount: _conversations.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 4),
                  itemBuilder: (context, index) {
                    final conversation = _conversations[index];
                    final selected =
                        _selectedConversation?.threadKey ==
                        conversation.threadKey;
                    final preview = conversation.summary?.isNotEmpty == true
                        ? conversation.summary!
                        : conversation.lastMessage?.toString() ?? '';
                    return Material(
                      color: selected
                          ? const Color(0xFFEAF3FF)
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(18),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(18),
                        onTap: () =>
                            unawaited(_selectConversation(conversation)),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        Expanded(
                                          child: Text(
                                            conversation.title,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: const TextStyle(
                                              fontSize: 14,
                                              fontWeight: FontWeight.w600,
                                              color: _kPrimaryText,
                                            ),
                                          ),
                                        ),
                                        const SizedBox(width: 8),
                                        Text(
                                          conversation.timeDisplay,
                                          style: const TextStyle(
                                            fontSize: 11,
                                            color: _kSubtleText,
                                            fontWeight: FontWeight.w500,
                                          ),
                                        ),
                                      ],
                                    ),
                                    if (preview.trim().isNotEmpty) ...[
                                      const SizedBox(height: 6),
                                      Text(
                                        preview,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(
                                          fontSize: 12,
                                          color: _kSecondaryText,
                                          height: 1.45,
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                              PopupMenuButton<String>(
                                splashRadius: 18,
                                onSelected: (value) {
                                  switch (value) {
                                    case 'archive':
                                      unawaited(
                                        _client
                                            .updateConversation(
                                              conversation.copyWith(
                                                isArchived: true,
                                              ),
                                            )
                                            .then(
                                              (_) => _loadConversations(
                                                preserveSelection: true,
                                              ),
                                            ),
                                      );
                                      break;
                                    case 'unarchive':
                                      unawaited(
                                        _client
                                            .updateConversation(
                                              conversation.copyWith(
                                                isArchived: false,
                                              ),
                                            )
                                            .then(
                                              (_) => _loadConversations(
                                                preserveSelection: true,
                                              ),
                                            ),
                                      );
                                      break;
                                    case 'delete':
                                      unawaited(
                                        _client
                                            .deleteConversation(conversation.id)
                                            .then(
                                              (_) => _loadConversations(
                                                preserveSelection: false,
                                              ),
                                            ),
                                      );
                                      break;
                                  }
                                },
                                itemBuilder: (context) => [
                                  PopupMenuItem<String>(
                                    value: conversation.isArchived
                                        ? 'unarchive'
                                        : 'archive',
                                    child: Text(
                                      conversation.isArchived
                                          ? (Localizations.localeOf(
                                                      context,
                                                    ).languageCode ==
                                                    'en'
                                                ? 'Unarchive'
                                                : '取消归档')
                                          : (Localizations.localeOf(
                                                      context,
                                                    ).languageCode ==
                                                    'en'
                                                ? 'Archive'
                                                : '归档'),
                                    ),
                                  ),
                                  PopupMenuItem<String>(
                                    value: 'delete',
                                    child: Text(
                                      Localizations.localeOf(
                                                context,
                                              ).languageCode ==
                                              'en'
                                          ? 'Delete'
                                          : '删除',
                                    ),
                                  ),
                                ],
                                child: const Padding(
                                  padding: EdgeInsets.all(6),
                                  child: Icon(
                                    Icons.more_horiz_rounded,
                                    color: _kSecondaryText,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildChatPane(BuildContext context) {
    return Column(
      children: [
        Container(
          height: 72,
          padding: const EdgeInsets.symmetric(horizontal: 20),
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: _kPanelBorder)),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _selectedConversation?.title ??
                          (Localizations.localeOf(context).languageCode == 'en'
                              ? 'Select a conversation to start chatting'
                              : '选择一个对话开始聊天'),
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: _kPrimaryText,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _selectedConversation == null
                          ? (Localizations.localeOf(context).languageCode ==
                                    'en'
                                ? 'Full chat, tool calls, workspace and browser mirror support'
                                : '支持完整聊天、工具调用、工作区与浏览器镜像')
                          : _selectedConversation!.mode.displayLabel,
                      style: const TextStyle(
                        fontSize: 12,
                        color: _kSecondaryText,
                      ),
                    ),
                  ],
                ),
              ),
              if (_selectedConversation != null) ...[
                IconButton(
                  tooltip: _selectedConversation!.isArchived
                      ? (Localizations.localeOf(context).languageCode == 'en'
                            ? 'Unarchive'
                            : '取消归档')
                      : (Localizations.localeOf(context).languageCode == 'en'
                            ? 'Archive'
                            : '归档'),
                  onPressed: () =>
                      _updateArchiveState(!_selectedConversation!.isArchived),
                  icon: Icon(
                    _selectedConversation!.isArchived
                        ? Icons.unarchive_outlined
                        : Icons.archive_outlined,
                    color: _kSecondaryText,
                  ),
                ),
                IconButton(
                  tooltip: Localizations.localeOf(context).languageCode == 'en'
                      ? 'Delete'
                      : '删除',
                  onPressed: _deleteSelectedConversation,
                  icon: const Icon(
                    Icons.delete_outline,
                    color: _kSecondaryText,
                  ),
                ),
              ],
            ],
          ),
        ),
        if (_error != null)
          Container(
            width: double.infinity,
            color: const Color(0xFFFFF0F0),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Text(
              _error!,
              style: const TextStyle(color: Color(0xFFB42318)),
            ),
          ),
        Expanded(
          child: _displayMessages.isEmpty
              ? Center(
                  child: Text(
                    Localizations.localeOf(context).languageCode == 'en'
                        ? 'How can I help you?'
                        : '有什么可以帮助你的？',
                    style: const TextStyle(
                      color: _kSecondaryText,
                      fontSize: 14,
                    ),
                  ),
                )
              : ListView.builder(
                  controller: _chatScrollController,
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
                  itemCount: _displayMessages.length,
                  itemBuilder: (context, index) {
                    final message = _displayMessages[index];
                    final isOldestMessage = index == 0;
                    final needTopPadding = isOldestMessage && message.user != 1;
                    return Padding(
                      padding: EdgeInsets.only(
                        top: needTopPadding ? 24 : 0,
                        bottom: index == _displayMessages.length - 1 ? 10 : 0,
                      ),
                      child: _buildMessageBubble(context, message),
                    );
                  },
                ),
        ),
        if (_activeClarifyTaskId != null)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFEAF3FF),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: _kPanelBorder),
              ),
              child: Row(
                children: [
                  const Icon(Icons.help_outline, size: 18, color: _kAccentBlue),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      Localizations.localeOf(context).languageCode == 'en'
                          ? 'The current Agent needs your clarification. It will continue after you send input.'
                          : '当前 Agent 需要你的澄清回复，发送输入内容后会继续执行。',
                      style: const TextStyle(
                        fontSize: 12,
                        color: _kPrimaryText,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        Container(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
          decoration: const BoxDecoration(
            border: Border(top: BorderSide(color: _kPanelBorder)),
          ),
          child: _buildChatComposer(context),
        ),
      ],
    );
  }

  Widget _buildChatComposer(BuildContext context) {
    final hasPayload =
        _messageController.text.trim().isNotEmpty ||
        _pendingAttachments.isNotEmpty;
    return Container(
      constraints: const BoxConstraints(minHeight: 72),
      padding: const EdgeInsets.fromLTRB(14, 10, 12, 10),
      decoration: BoxDecoration(
        color: _kPanelSurface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: _kPanelBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1A2F7BFF),
            blurRadius: 18,
            offset: Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_pendingAttachments.isNotEmpty) ...[
            _buildComposerAttachmentPreview(),
            const SizedBox(height: 8),
          ],
          Focus(
            onKeyEvent: (_, event) => _handleComposerKeyEvent(event),
            child: TextField(
              controller: _messageController,
              minLines: 1,
              maxLines: 6,
              textInputAction: TextInputAction.send,
              style: const TextStyle(
                fontSize: 14,
                color: _kPrimaryText,
                height: 1.5,
              ),
              decoration: InputDecoration(
                hintText: LegacyTextLocalizer.isEnglish
                    ? 'Chat with the agent...'
                    : '直接和 Agent 对话...',
                hintStyle: TextStyle(color: _kSubtleText),
                border: InputBorder.none,
                isCollapsed: true,
              ),
              onSubmitted: (_) {
                if (!_sendingMessage && hasPayload) {
                  _submitComposer();
                }
              },
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              IconButton(
                onPressed: _pickAttachments,
                icon: const Icon(Icons.add_rounded, color: _kSecondaryText),
                tooltip: LegacyTextLocalizer.isEnglish
                    ? 'Add attachment'
                    : '添加附件',
              ),
              const Spacer(),
              AnimatedOpacity(
                duration: const Duration(milliseconds: 180),
                opacity: hasPayload || _sendingMessage ? 1 : 0.38,
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: _sendingMessage || !hasPayload
                      ? null
                      : _submitComposer,
                  child: Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      color: const Color(0xFFEAF3FF),
                      shape: BoxShape.circle,
                      border: Border.all(color: const Color(0xFFD5E4FF)),
                    ),
                    child: Center(
                      child: _sendingMessage
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: _kAccentBlue,
                              ),
                            )
                          : const Icon(
                              Icons.arrow_upward_rounded,
                              size: 18,
                              color: _kAccentBlue,
                            ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildComposerAttachmentPreview() {
    return SizedBox(
      height: 72,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _pendingAttachments.length,
        separatorBuilder: (_, __) => const SizedBox(width: 10),
        itemBuilder: (context, index) {
          final attachment = _pendingAttachments[index];
          return Stack(
            clipBehavior: Clip.none,
            children: [
              attachment.isImage
                  ? ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: _buildPendingAttachmentImage(attachment),
                    )
                  : Container(
                      width: 156,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: _kAttachmentSurface,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: _kAttachmentBorder),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.insert_drive_file_outlined,
                            color: _kAttachmentIcon,
                            size: 16,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              attachment.name,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: _kAttachmentText,
                                height: 1.25,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
              Positioned(
                top: -4,
                right: -4,
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () {
                    setState(() {
                      _pendingAttachments.removeAt(index);
                    });
                  },
                  child: Container(
                    width: 18,
                    height: 18,
                    decoration: BoxDecoration(
                      color: const Color(0xFF54627A).withValues(alpha: 0.12),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.close_rounded,
                      size: 12,
                      color: Color(0xFF54627A),
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildPendingAttachmentImage(_PendingAttachment attachment) {
    final bytes = _bytesFromDataUrl(attachment.dataUrl);
    if (bytes == null) {
      return Container(
        width: 72,
        height: 72,
        color: _kAttachmentSurface,
        alignment: Alignment.center,
        child: const Icon(
          Icons.broken_image_outlined,
          size: 18,
          color: _kAttachmentIcon,
        ),
      );
    }
    return Image.memory(
      bytes,
      width: 72,
      height: 72,
      fit: BoxFit.cover,
      gaplessPlayback: true,
    );
  }

  Widget _buildSidePanels(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Column(
        children: [
          Material(
            color: Colors.white,
            child: TabBar(
              tabs: [
                Tab(text: LegacyTextLocalizer.isEnglish ? 'Workspace' : '工作区'),
                Tab(text: LegacyTextLocalizer.isEnglish ? 'Browser' : '浏览器'),
              ],
            ),
          ),
          Expanded(
            child: TabBarView(
              children: [
                _buildWorkspacePanel(context),
                _buildBrowserPanel(context),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildWorkspacePanel(BuildContext context) {
    final breadcrumbs = _workspaceBreadcrumbs;
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: Color(0xFFE3EAF7))),
            color: Colors.white,
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      LegacyTextLocalizer.isEnglish ? 'Workspace' : '工作区',
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: _kPrimaryText,
                      ),
                    ),
                    const SizedBox(height: 6),
                    breadcrumbs.isEmpty
                        ? Text(
                            LegacyTextLocalizer.isEnglish
                                ? 'Loading workspace...'
                                : '加载工作区中...',
                            style: TextStyle(color: _kSecondaryText),
                          )
                        : Wrap(
                            crossAxisAlignment: WrapCrossAlignment.center,
                            spacing: 2,
                            runSpacing: 2,
                            children: [
                              for (
                                var index = 0;
                                index < breadcrumbs.length;
                                index++
                              ) ...[
                                if (index > 0)
                                  const Padding(
                                    padding: EdgeInsets.symmetric(
                                      horizontal: 2,
                                    ),
                                    child: Icon(
                                      Icons.chevron_right_rounded,
                                      size: 16,
                                      color: _kSubtleText,
                                    ),
                                  ),
                                _buildWorkspaceBreadcrumbChip(
                                  breadcrumbs[index],
                                ),
                              ],
                            ],
                          ),
                  ],
                ),
              ),
              IconButton(
                onPressed: _workspaceReloading
                    ? null
                    : () => _reloadWorkspace(),
                icon: const Icon(Icons.refresh),
                tooltip: LegacyTextLocalizer.isEnglish ? 'Refresh now' : '立即刷新',
              ),
              if (_workspaceSelectedFilePath != null)
                FilledButton(
                  onPressed: _workspaceBusy || !_workspaceDirty
                      ? null
                      : _saveWorkspaceFile,
                  child: Text(LegacyTextLocalizer.isEnglish ? 'Save' : '保存'),
                ),
            ],
          ),
        ),
        Expanded(
          child: Row(
            children: [
              SizedBox(
                width: 180,
                child: ListView.builder(
                  itemCount: _workspaceItems.length,
                  itemBuilder: (context, index) {
                    final entry = _workspaceItems[index];
                    final isDirectory = entry['isDirectory'] == true;
                    final path = (entry['path'] ?? '').toString();
                    final selected = path == _workspaceSelectedFilePath;
                    return ListTile(
                      dense: true,
                      selected: selected,
                      leading: Icon(
                        isDirectory
                            ? Icons.folder_outlined
                            : Icons.description_outlined,
                        size: 18,
                      ),
                      title: Text(
                        (entry['name'] ?? '').toString(),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      onTap: () => unawaited(_openWorkspaceEntry(entry)),
                    );
                  },
                ),
              ),
              const VerticalDivider(width: 1),
              Expanded(
                child: _workspaceSelectedFilePath == null
                    ? Center(
                        child: Text(
                          LegacyTextLocalizer.isEnglish
                              ? 'Select a file to view or edit'
                              : '选择一个文件开始查看或编辑',
                        ),
                      )
                    : Padding(
                        padding: const EdgeInsets.all(12),
                        child: TextField(
                          controller: _workspaceEditorController,
                          expands: true,
                          maxLines: null,
                          minLines: null,
                          decoration: InputDecoration(
                            labelText: _workspaceSelectedFilePath,
                            border: const OutlineInputBorder(),
                            alignLabelWithHint: true,
                          ),
                        ),
                      ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildWorkspaceBreadcrumbChip(_WorkspaceBreadcrumbSegment segment) {
    final labelStyle = TextStyle(
      fontSize: 12,
      fontWeight: segment.isCurrent ? FontWeight.w700 : FontWeight.w500,
      color: segment.isCurrent ? _kPrimaryText : _kAccentBlue,
    );
    return Material(
      color: segment.isCurrent ? const Color(0xFFEAF3FF) : Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: segment.isCurrent
            ? null
            : () => unawaited(_reloadWorkspace(path: segment.path)),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Text(segment.label, style: labelStyle),
        ),
      ),
    );
  }

  Widget _buildBrowserPanel(BuildContext context) {
    final snapshot = _browserSnapshot ?? const <String, dynamic>{};
    final available = snapshot['available'] == true;
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: Color(0xFFE3EAF7))),
            color: Colors.white,
          ),
          child: Column(
            children: [
              TextField(
                controller: _browserUrlController,
                decoration: InputDecoration(
                  hintText: LegacyTextLocalizer.isEnglish
                      ? 'Enter URL to navigate remotely'
                      : '输入网址并远程导航',
                  border: OutlineInputBorder(),
                ),
                onSubmitted: (_) => _navigateBrowser(),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  FilledButton(
                    onPressed: _browserBusy ? null : _navigateBrowser,
                    child: Text(LegacyTextLocalizer.isEnglish ? 'Open' : '打开'),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: _browserBusy
                        ? null
                        : () => _runBrowserAction(<String, dynamic>{
                            'action': 'scroll',
                            'direction': 'up',
                            'amount': 420,
                            'tool_title': 'Web Chat Scroll Up',
                          }),
                    child: Text(
                      LegacyTextLocalizer.isEnglish ? 'Swipe up' : '上滑',
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: _browserBusy
                        ? null
                        : () => _runBrowserAction(<String, dynamic>{
                            'action': 'scroll',
                            'direction': 'down',
                            'amount': 420,
                            'tool_title': 'Web Chat Scroll Down',
                          }),
                    child: Text(
                      LegacyTextLocalizer.isEnglish ? 'Swipe down' : '下滑',
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                LegacyTextLocalizer.isEnglish ? 'Current page' : '当前页面',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 6),
              Text(
                (snapshot['title'] ??
                        (LegacyTextLocalizer.isEnglish ? 'No session' : '暂无会话'))
                    .toString(),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 4),
              Text(
                (snapshot['currentUrl'] ?? '').toString(),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        Expanded(
          child: Center(
            child: available
                ? Padding(
                    padding: const EdgeInsets.all(12),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(16),
                      child: Image.network(
                        _client.frameUrl(seed: _browserFrameSeed),
                        fit: BoxFit.contain,
                        errorBuilder: (_, __, ___) => Center(
                          child: Text(
                            LegacyTextLocalizer.isEnglish
                                ? 'Browser view unavailable'
                                : '浏览器画面暂不可用',
                          ),
                        ),
                      ),
                    ),
                  )
                : Text(
                    LegacyTextLocalizer.isEnglish
                        ? 'No browser session available for mirroring'
                        : '当前没有可镜像的浏览器会话',
                  ),
          ),
        ),
      ],
    );
  }

  Widget _buildMessageBubble(BuildContext context, ChatMessageModel message) {
    if (message.type == 2) {
      return Container(
        margin: const EdgeInsets.only(top: 8, bottom: 0),
        child: _buildCard(
          context,
          message.cardData ?? const <String, dynamic>{},
        ),
      );
    }
    final isUser = message.user == 1;
    final attachments = _extractAttachments(message);
    if (isUser) {
      return Container(
        margin: const EdgeInsets.only(top: 24, bottom: 16),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.end,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Flexible(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onLongPressStart: (details) => _showUserMessageActions(
                  context,
                  message,
                  details.globalPosition,
                ),
                onSecondaryTapDown: (details) => _showUserMessageActions(
                  context,
                  message,
                  details.globalPosition,
                ),
                child: Container(
                  constraints: BoxConstraints(
                    maxWidth: math.min(
                      MediaQuery.of(context).size.width * 0.75,
                      680,
                    ),
                  ),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 6,
                  ),
                  decoration: ShapeDecoration(
                    color: _kUserBubble,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if ((message.text ?? '').isNotEmpty)
                        Text(
                          message.text!,
                          style: const TextStyle(
                            color: _kPrimaryText,
                            fontSize: 14,
                            fontWeight: FontWeight.w400,
                            height: 1.43,
                            letterSpacing: 0.33,
                          ),
                        ),
                      if (attachments.isNotEmpty) ...[
                        if ((message.text ?? '').isNotEmpty)
                          const SizedBox(height: 8),
                        _buildAttachmentWrap(context, attachments),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    return Container(
      margin: const EdgeInsets.only(top: 8, bottom: 0, right: 18),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (message.isLoading && (message.text ?? '').trim().isEmpty)
                  _buildThinkingIndicator()
                else if ((message.text ?? '').isNotEmpty)
                  _buildAssistantText(context, message),
                if (attachments.isNotEmpty) ...[
                  if ((message.text ?? '').isNotEmpty)
                    const SizedBox(height: 8),
                  _buildAttachmentWrap(context, attachments),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _showUserMessageActions(
    BuildContext context,
    ChatMessageModel message,
    Offset globalPosition,
  ) async {
    final selected = await showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(
        globalPosition.dx,
        globalPosition.dy,
        globalPosition.dx,
        globalPosition.dy,
      ),
      items: [
        PopupMenuItem<String>(
          value: 'retry',
          child: Text(LegacyTextLocalizer.isEnglish ? 'Retry message' : '重试消息'),
        ),
      ],
    );
    if (selected == 'retry') {
      await _retryMessage(message);
    }
  }

  Future<void> _retryMessage(ChatMessageModel message) async {
    final attachments = _extractAttachments(message)
        .map(
          (item) => _PendingAttachment(
            name:
                (item['fileName'] ??
                        item['name'] ??
                        (LegacyTextLocalizer.isEnglish ? 'Attachment' : '附件'))
                    .toString(),
            mimeType: (item['mimeType'] ?? 'application/octet-stream')
                .toString(),
            size: (item['size'] as num?)?.toInt() ?? 0,
            dataUrl: (item['dataUrl'] ?? '').toString(),
            isImage: item['isImage'] == true,
          ),
        )
        .where((item) => item.dataUrl.isNotEmpty)
        .toList();
    await _sendMessage(
      overrideText: message.text ?? '',
      overrideAttachments: attachments,
    );
  }

  Widget _buildAssistantText(BuildContext context, ChatMessageModel message) {
    if (message.isSummarizing && (message.text ?? '').trim().isEmpty) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.auto_awesome_rounded, size: 16, color: _kAccentBlue),
          const SizedBox(width: 6),
          Text(
            LegacyTextLocalizer.isEnglish ? 'Summarizing...' : '总结中',
            style: const TextStyle(
              fontSize: 14,
              color: _kAccentBlue,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      );
    }

    final text = message.text ?? '';
    final style = const TextStyle(
      fontSize: 14,
      color: _kPrimaryText,
      height: 1.57,
    );
    final isSummaryContent =
        message.id.startsWith('vlm-summary-') ||
        message.id.startsWith('task-summary-');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (isSummaryContent) ...[
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.check_circle_outline_rounded,
                size: 16,
                color: _kAccentGreen,
              ),
              const SizedBox(width: 6),
              Text(
                LegacyTextLocalizer.isEnglish ? 'Summary:' : '总结如下',
                style: const TextStyle(
                  fontSize: 14,
                  color: _kAccentGreen,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
        ],
        MarkdownBody(
          data: text,
          selectable: true,
          styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
            p: style,
            code: style.copyWith(
              fontFamily: 'monospace',
              fontSize: 13,
              backgroundColor: Colors.transparent,
            ),
            codeblockDecoration: BoxDecoration(
              color: const Color(0xFFF2F5FB),
              borderRadius: BorderRadius.circular(14),
            ),
            blockquoteDecoration: const BoxDecoration(
              color: Color(0xFFF3F7FE),
              border: Border(left: BorderSide(color: _kAccentBlue, width: 4)),
            ),
            blockquotePadding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
            tableHead: style.copyWith(fontWeight: FontWeight.w600),
            tableBody: style,
          ),
        ),
      ],
    );
  }

  Widget _buildThinkingIndicator() {
    return const _ThinkingDots();
  }

  Widget _buildAttachmentWrap(
    BuildContext context,
    List<Map<String, dynamic>> attachments,
  ) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: attachments
          .map((item) => _buildAttachmentChip(context, item))
          .toList(),
    );
  }

  Widget _buildCard(BuildContext context, Map<String, dynamic> cardData) {
    final type = (cardData['type'] ?? '').toString();
    switch (type) {
      case 'deep_thinking':
        return Container(
          width: double.infinity,
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: const Color(0xFFF8FBFF),
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: const Color(0xFFD6E4FA)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.psychology_alt_outlined, size: 18),
                  const SizedBox(width: 8),
                  Text(
                    (cardData['stage'] == 4 || cardData['isLoading'] == false)
                        ? (LegacyTextLocalizer.isEnglish
                              ? 'Thinking complete'
                              : '思考完成')
                        : (LegacyTextLocalizer.isEnglish
                              ? 'Thinking...'
                              : '正在思考'),
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      color: _kPrimaryText,
                    ),
                  ),
                  const Spacer(),
                  if (cardData['isLoading'] == true)
                    const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                ],
              ),
              const SizedBox(height: 10),
              SelectableText(
                (cardData['thinkingContent'] ?? '').toString().trim().isEmpty
                    ? (LegacyTextLocalizer.isEnglish
                          ? 'Generating thinking content...'
                          : '正在生成思考内容...')
                    : (cardData['thinkingContent'] ?? '').toString(),
                style: const TextStyle(color: _kSecondaryText, height: 1.55),
              ),
            ],
          ),
        );
      case 'agent_tool_summary':
        final status = (cardData['status'] ?? 'running').toString();
        final title = resolveAgentToolTitle(cardData);
        final statusLabel = resolveAgentToolStatusLabel(cardData);
        final typeLabel = resolveAgentToolTypeLabel(cardData);
        final color = switch (status) {
          'success' => const Color(0xFF2F8F4E),
          'error' => const Color(0xFFFF6464),
          'interrupted' => const Color(0xFFFFAA2C),
          _ => const Color(0xFF00AEFF),
        };
        return Align(
          alignment: Alignment.centerLeft,
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: MediaQuery.of(context).size.width * 0.78,
              minHeight: 34,
            ),
            child: Container(
              margin: const EdgeInsets.only(top: 6, bottom: 2),
              padding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 16,
                    height: 16,
                    decoration: BoxDecoration(
                      color: color.withValues(alpha: 0.12),
                      shape: BoxShape.circle,
                    ),
                    child: Center(
                      child: status == 'running'
                          ? SizedBox(
                              width: 8,
                              height: 8,
                              child: CircularProgressIndicator(
                                strokeWidth: 1.4,
                                valueColor: AlwaysStoppedAnimation<Color>(
                                  color,
                                ),
                              ),
                            )
                          : Icon(
                              status == 'error'
                                  ? Icons.error_outline_rounded
                                  : status == 'interrupted'
                                  ? Icons.stop_circle_outlined
                                  : Icons.check_circle_outline_rounded,
                              size: 10,
                              color: color,
                            ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: _kPrimaryText,
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                        height: 1.15,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 7,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.78),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      status == 'running' ? typeLabel : statusLabel,
                      style: TextStyle(
                        color: color,
                        fontSize: 10,
                        fontWeight: FontWeight.w600,
                        height: 1,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      default:
        return Container(
          width: double.infinity,
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: const Color(0xFFE3EAF7)),
          ),
          child: SelectableText(jsonEncode(cardData)),
        );
    }
  }

  List<Map<String, dynamic>> _extractAttachments(ChatMessageModel message) {
    final raw = message.content?['attachments'];
    if (raw is! List) {
      return const <Map<String, dynamic>>[];
    }
    return raw
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
  }

  Widget _buildAttachmentChip(BuildContext context, Map<String, dynamic> item) {
    final dataUrl = (item['dataUrl'] ?? '').toString();
    final fileName =
        (item['fileName'] ??
                item['name'] ??
                item['path'] ??
                (LegacyTextLocalizer.isEnglish ? 'Attachment' : '附件'))
            .toString();
    final mimeType = (item['mimeType'] ?? '').toString();
    if (dataUrl.startsWith('data:image/')) {
      final bytes = _bytesFromDataUrl(dataUrl);
      if (bytes != null) {
        return GestureDetector(
          onTap: () => _openImagePreview(context, bytes),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Image.memory(
              bytes,
              width: 84,
              height: 84,
              fit: BoxFit.cover,
              gaplessPlayback: true,
            ),
          ),
        );
      }
    }
    return Container(
      constraints: const BoxConstraints(maxWidth: 220, minHeight: 40),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: _kAttachmentSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: _kAttachmentBorder),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            mimeType.startsWith('image/')
                ? Icons.image_outlined
                : Icons.insert_drive_file_outlined,
            size: 15,
            color: _kAttachmentIcon,
          ),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              fileName,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: _kAttachmentText,
                fontSize: 12,
                fontWeight: FontWeight.w500,
                height: 1.25,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openImagePreview(BuildContext context, Uint8List bytes) {
    return showDialog<void>(
      context: context,
      builder: (dialogContext) {
        return Dialog(
          backgroundColor: Colors.transparent,
          insetPadding: const EdgeInsets.all(24),
          child: Stack(
            children: [
              InteractiveViewer(
                minScale: 0.8,
                maxScale: 4,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: Image.memory(bytes, fit: BoxFit.contain),
                ),
              ),
              Positioned(
                top: 12,
                right: 12,
                child: IconButton.filledTonal(
                  onPressed: () => Navigator.of(dialogContext).pop(),
                  icon: const Icon(Icons.close_rounded),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Uint8List? _bytesFromDataUrl(String dataUrl) {
    final marker = dataUrl.indexOf(',');
    if (marker < 0) return null;
    return Uint8List.fromList(base64Decode(dataUrl.substring(marker + 1)));
  }

  String _mimeTypeForExtension(String extension) {
    switch (extension.toLowerCase()) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'md':
        return 'text/markdown';
      case 'json':
        return 'application/json';
      case 'txt':
      case 'log':
        return 'text/plain';
      default:
        return 'application/octet-stream';
    }
  }
}

class _PaneResizeHandle extends StatelessWidget {
  const _PaneResizeHandle({required this.onDragUpdate});

  final ValueChanged<double> onDragUpdate;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      cursor: SystemMouseCursors.resizeColumn,
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragUpdate: (details) => onDragUpdate(details.delta.dx),
        child: SizedBox(
          width: _kDesktopDividerHitWidth,
          child: Center(
            child: Container(
              width: 4,
              height: 64,
              decoration: BoxDecoration(
                color: const Color(0xFFD7E5FB),
                borderRadius: BorderRadius.circular(999),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ThinkingDots extends StatefulWidget {
  const _ThinkingDots();

  @override
  State<_ThinkingDots> createState() => _ThinkingDotsState();
}

class _ThinkingDotsState extends State<_ThinkingDots>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 960),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: List.generate(3, (index) {
            final phase = ((_controller.value + (index * 0.18)) % 1.0);
            final opacity =
                0.28 + ((1 - (phase - 0.5).abs() * 2).clamp(0.0, 1.0) * 0.72);
            return Opacity(
              opacity: opacity,
              child: Container(
                width: 6,
                height: 6,
                margin: EdgeInsets.only(right: index == 2 ? 0 : 6),
                decoration: const BoxDecoration(
                  color: _kSecondaryText,
                  shape: BoxShape.circle,
                ),
              ),
            );
          }),
        );
      },
    );
  }
}
