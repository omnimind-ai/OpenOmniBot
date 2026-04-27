import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat_history/widgets/chat_history_conversation_item.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class ChatHistoryPage extends StatefulWidget {
  const ChatHistoryPage({super.key, this.archivedOnly = false});

  final bool archivedOnly;

  @override
  State<ChatHistoryPage> createState() => _ChatHistoryPageState();
}

class _ChatHistoryPageState extends State<ChatHistoryPage> {
  static const BorderRadius _deleteActionRadius = BorderRadius.only(
    topRight: Radius.circular(8),
    bottomRight: Radius.circular(8),
  );

  List<ConversationModel> _conversations = const [];
  final Set<String> _busyKeys = <String>{};
  bool _isLoading = true;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;

  Future<void> _triggerDeleteHaptic() async {
    try {
      final enabled = await CacheUtil.getBool(
        'app_vibrate',
        defaultValue: true,
      );
      if (!enabled) {
        return;
      }
      await HapticFeedback.mediumImpact();
    } catch (error) {
      debugPrint('[ChatHistoryPage] failed to trigger delete haptic: $error');
    }
  }

  @override
  void initState() {
    super.initState();
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_loadConversations());
        });
    _loadConversations();
  }

  @override
  void dispose() {
    _conversationListChangedSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadConversations() async {
    if (mounted) {
      setState(() {
        _isLoading = true;
      });
    }

    try {
      final loadedConversations = await ConversationService.getAllConversations(
        archivedOnly: widget.archivedOnly,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _conversations = loadedConversations;
        _isLoading = false;
      });
    } catch (error) {
      debugPrint('[ChatHistoryPage] failed to load conversations: $error');
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _deleteConversation(ConversationModel conversation) async {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _conversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyKeys.add(conversation.threadKey);
      _conversations = List<ConversationModel>.from(_conversations)
        ..removeAt(originalIndex);
    });

    final deleted = await ConversationService.deleteConversation(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) {
      return;
    }
    if (deleted) {
      unawaited(_triggerDeleteHaptic());
    }

    setState(() {
      _busyKeys.remove(conversation.threadKey);
      if (!deleted) {
        final restoredIndex = originalIndex <= _conversations.length
            ? originalIndex
            : _conversations.length;
        _conversations = List<ConversationModel>.from(_conversations)
          ..insert(restoredIndex, conversation);
      }
    });

    showToast(
      deleted ? '\u5df2\u5220\u9664' : '\u5220\u9664\u5931\u8d25',
      type: deleted ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _setConversationArchived(
    ConversationModel conversation, {
    required bool archived,
  }) async {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _conversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyKeys.add(conversation.threadKey);
      _conversations = List<ConversationModel>.from(_conversations)
        ..removeAt(originalIndex);
    });

    final success = archived
        ? await ConversationService.archiveConversation(conversation)
        : await ConversationService.unarchiveConversation(conversation);
    if (!mounted) {
      return;
    }

    setState(() {
      _busyKeys.remove(conversation.threadKey);
      if (!success) {
        final restoredIndex = originalIndex <= _conversations.length
            ? originalIndex
            : _conversations.length;
        _conversations = List<ConversationModel>.from(_conversations)
          ..insert(restoredIndex, conversation);
      }
    });

    showToast(
      success
          ? (archived
                ? context.l10n.chatHistoryArchivedToast
                : context.l10n.chatHistoryUnarchivedToast)
          : (archived
                ? context.l10n.chatHistoryArchiveFailed
                : context.l10n.chatHistoryUnarchiveFailed),
      type: success ? ToastType.success : ToastType.error,
    );
  }

  void _openConversation(ConversationModel conversation) {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }
    GoRouterManager.push(
      '/home/chat',
      extra: ConversationThreadTarget.existing(
        conversationId: conversation.id,
        mode: conversation.mode,
      ),
    );
  }

  void _createConversation() {
    GoRouterManager.push(
      '/home/chat',
      extra: ConversationThreadTarget.newConversation(
        requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
      ),
    );
  }

  String get _pageTitle => widget.archivedOnly
      ? context.l10n.chatHistoryArchivedTitle
      : context.l10n.chatHistoryTitle;

  String get _emptyTitle => widget.archivedOnly
      ? context.l10n.chatHistoryNoArchived
      : context.l10n.chatHistoryEmpty;

  List<ConversationSlideAction> _buildActions(ConversationModel conversation) {
    final palette = context.omniPalette;
    final primaryAction = ConversationSlideAction(
      onPressed: () => _setConversationArchived(
        conversation,
        archived: !widget.archivedOnly,
      ),
      backgroundColor: context.isDarkTheme
          ? Color.lerp(palette.surfaceElevated, palette.accentPrimary, 0.3)!
          : AppColors.buttonPrimary,
      child: Center(
        child: widget.archivedOnly
            ? const Icon(
                Icons.unarchive_outlined,
                color: Colors.white,
                size: 22,
              )
            : SvgPicture.asset(
                'assets/home/archive_icon.svg',
                width: 20,
                height: 20,
                colorFilter: const ColorFilter.mode(
                  Colors.white,
                  BlendMode.srcIn,
                ),
              ),
      ),
    );

    return [
      primaryAction,
      ConversationSlideAction(
        onPressed: () => _deleteConversation(conversation),
        backgroundColor: AppColors.alertRed,
        borderRadius: _deleteActionRadius,
        child: Center(
          child: SvgPicture.asset(
            'assets/memory/memory_delete.svg',
            width: 20,
            height: 20,
            colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
          ),
        ),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: context.isDarkTheme
          ? palette.pageBackground
          : AppColors.background,
      appBar: CommonAppBar(
        title: _pageTitle,
        primary: true,
        trailing: widget.archivedOnly
            ? null
            : IconButton(
                icon: Icon(
                  Icons.add,
                  color: context.isDarkTheme
                      ? palette.textSecondary
                      : Colors.grey[600],
                  size: 24,
                ),
                onPressed: _createConversation,
                tooltip: '\u65b0\u5efa\u5bf9\u8bdd',
              ),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    final palette = context.omniPalette;
    if (_isLoading) {
      return Center(
        child: CircularProgressIndicator(
          valueColor: AlwaysStoppedAnimation<Color>(
            context.isDarkTheme
                ? palette.accentPrimary
                : const Color(0xFF1930D9),
          ),
        ),
      );
    }

    if (_conversations.isEmpty) {
      return _buildEmptyState();
    }

    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: _conversations.length,
      itemBuilder: (context, index) {
        final conversation = _conversations[index];
        return ChatHistoryConversationItem(
          conversation: conversation,
          actions: _buildActions(conversation),
          isBusy: _busyKeys.contains(conversation.threadKey),
          compact: widget.archivedOnly,
          showLeadingIcon: !widget.archivedOnly,
          onTap: () => _openConversation(conversation),
          onDelete: () => _deleteConversation(conversation),
        );
      },
    );
  }

  Widget _buildEmptyHint() {
    final palette = context.omniPalette;
    if (!widget.archivedOnly) {
      return GestureDetector(
        onTap: _createConversation,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: context.isDarkTheme
                  ? <Color>[
                      Color.lerp(
                        palette.surfaceElevated,
                        palette.accentPrimary,
                        0.24,
                      )!,
                      Color.lerp(
                        palette.surfaceSecondary,
                        palette.accentPrimary,
                        0.36,
                      )!,
                    ]
                  : const <Color>[Color(0xFF1930D9), Color(0xFF2CA5F0)],
            ),
            borderRadius: BorderRadius.all(Radius.circular(24)),
            border: context.isDarkTheme
                ? Border.all(color: palette.borderSubtle)
                : null,
          ),
          child: Text(
            '\u5f00\u59cb\u5bf9\u8bdd',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              color: context.isDarkTheme ? palette.textPrimary : Colors.white,
            ),
          ),
        ),
      );
    }

    return Text(
      context.l10n.chatHistoryArchiveHint,
      style: TextStyle(
        fontSize: 13,
        color: context.isDarkTheme ? palette.textSecondary : Colors.grey[500],
      ),
    );
  }

  Widget _buildEmptyState() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            widget.archivedOnly
                ? Icons.archive_outlined
                : Icons.chat_bubble_outline,
            size: 64,
            color: context.isDarkTheme
                ? palette.borderStrong
                : Colors.grey[300],
          ),
          const SizedBox(height: 16),
          Text(
            _emptyTitle,
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : Colors.grey[500],
            ),
          ),
          const SizedBox(height: 24),
          _buildEmptyHint(),
        ],
      ),
    );
  }
}
