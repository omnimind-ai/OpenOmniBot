import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/features/home/widgets/home_drawer_search_field.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';

const String _kDrawerMemoryIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-brain-icon lucide-brain">'
    '<path d="M12 18V5"/>'
    '<path d="M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4"/>'
    '<path d="M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5"/>'
    '<path d="M17.997 5.125a4 4 0 0 1 2.526 5.77"/>'
    '<path d="M18 18a4 4 0 0 0 2-7.464"/>'
    '<path d="M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517"/>'
    '<path d="M6 18a4 4 0 0 1-2-7.464"/>'
    '<path d="M6.003 5.125a4 4 0 0 0-2.526 5.77"/>'
    '</svg>';

const String _kDrawerSkillStoreIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-container-icon lucide-container">'
    '<path d="M22 7.7c0-.6-.4-1.2-.8-1.5l-6.3-3.9a1.72 1.72 0 0 0-1.7 0l-10.3 '
    '6c-.5.2-.9.8-.9 1.4v6.6c0 .5.4 1.2.8 1.5l6.3 3.9a1.72 1.72 0 0 0 1.7 0'
    'l10.3-6c.5-.3.9-1 .9-1.5Z"/>'
    '<path d="M10 21.9V14L2.1 9.1"/>'
    '<path d="m10 14 11.9-6.9"/>'
    '<path d="M14 19.8v-8.1"/>'
    '<path d="M18 17.5V9.4"/>'
    '</svg>';

const String _kDrawerTaskHistoryIconSvg =
    '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">'
    '<rect x="0.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="0.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="0" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="0" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '</svg>';

/// 首页侧边栏
class HomeDrawer extends ConsumerStatefulWidget {
  const HomeDrawer({
    super.key,
    this.memoryCount,
    this.newConversationMode = ConversationMode.normal,
    this.embedded = false,
    this.closeOnNavigate = true,
    this.onThreadTargetSelected,
  });

  final int? memoryCount;
  final ConversationMode newConversationMode;
  final bool embedded;
  final bool closeOnNavigate;
  final ValueChanged<ConversationThreadTarget>? onThreadTargetSelected;

  @override
  ConsumerState<HomeDrawer> createState() => HomeDrawerState();
}

class HomeDrawerState extends ConsumerState<HomeDrawer> {
  static const double _conversationActionIconSize = 18;
  static const Duration _searchDebounceDuration = Duration(milliseconds: 220);
  static const Duration _sectionToggleDuration = Duration(milliseconds: 260);
  static const BorderRadius _drawerTrailingActionRadius = BorderRadius.only(
    topRight: Radius.circular(4),
    bottomRight: Radius.circular(4),
  );

  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  final Map<String, _ConversationSearchIndex> _conversationSearchCache =
      <String, _ConversationSearchIndex>{};
  final Map<String, bool> _expandedConversationSections = <String, bool>{};
  final Set<String> _busyConversationKeys = <String>{};
  final TextEditingController _titleEditingController = TextEditingController();
  final FocusNode _titleEditingFocusNode = FocusNode();
  List<ConversationModel> _allConversations = <ConversationModel>[];
  List<_ConversationSearchResult> _searchResults =
      <_ConversationSearchResult>[];
  bool isLoadingConversations = true;
  bool _isSearching = false;
  int _searchGeneration = 0;
  Timer? _searchDebounceTimer;
  String? _editingThreadKey;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;

  Map<String, String> _getGreetingByTime() {
    final hour = DateTime.now().hour;

    if (hour >= 2 && hour < 6) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerDawnGreeting,
          'subtitle': context.l10n.homeDrawerDawnSub,
        },
        {
          'title': context.l10n.homeDrawerDawnGreeting2,
          'subtitle': context.l10n.homeDrawerDawnSub2,
        },
        {
          'title': context.l10n.homeDrawerDawnGreeting3,
          'subtitle': context.l10n.homeDrawerDawnSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 6 && hour < 8) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerMorningGreeting,
          'subtitle': context.l10n.homeDrawerMorningSub,
        },
        {
          'title': context.l10n.homeDrawerMorningGreeting2,
          'subtitle': context.l10n.homeDrawerMorningSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 8 && hour < 12) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerForenoonGreeting,
          'subtitle': context.l10n.homeDrawerForenoonSub,
        },
        {
          'title': context.l10n.homeDrawerForenoonGreeting2,
          'subtitle': context.l10n.homeDrawerForenoonSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 12 && hour < 14) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerLunchGreeting,
          'subtitle': context.l10n.homeDrawerLunchSub,
        },
        {
          'title': context.l10n.homeDrawerLunchGreeting2,
          'subtitle': context.l10n.homeDrawerLunchSub2,
        },
        {
          'title': context.l10n.homeDrawerLunchGreeting3,
          'subtitle': context.l10n.homeDrawerLunchSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 14 && hour < 18) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerAfternoonGreeting,
          'subtitle': context.l10n.homeDrawerAfternoonSub,
        },
        {
          'title': context.l10n.homeDrawerAfternoonGreeting2,
          'subtitle': context.l10n.homeDrawerAfternoonSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 18 && hour < 20) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerEveningGreeting,
          'subtitle': context.l10n.homeDrawerEveningSub,
        },
        {
          'title': context.l10n.homeDrawerEveningGreeting2,
          'subtitle': context.l10n.homeDrawerEveningSub2,
        },
        {
          'title': context.l10n.homeDrawerEveningGreeting3,
          'subtitle': context.l10n.homeDrawerEveningSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 20 && hour < 22) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerNightGreeting,
          'subtitle': context.l10n.homeDrawerNightSub,
        },
        {
          'title': context.l10n.homeDrawerNightGreeting2,
          'subtitle': context.l10n.homeDrawerNightSub2,
        },
        {
          'title': context.l10n.homeDrawerNightGreeting3,
          'subtitle': context.l10n.homeDrawerNightSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    final greetings = [
      {
        'title': context.l10n.homeDrawerLateNightGreeting,
        'subtitle': context.l10n.homeDrawerLateNightSub,
      },
      {
        'title': context.l10n.homeDrawerLateNightGreeting2,
        'subtitle': context.l10n.homeDrawerLateNightSub2,
      },
    ];
    return greetings[DateTime.now().minute % greetings.length];
  }

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_handleSearchQueryChanged);
    _searchFocusNode.addListener(_handleSearchFocusChanged);
    _titleEditingFocusNode.addListener(_handleTitleEditingFocusChanged);
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_loadConversations());
        });
    _loadConversations();
  }

  @override
  void dispose() {
    _searchDebounceTimer?.cancel();
    _conversationListChangedSubscription?.cancel();
    _searchController
      ..removeListener(_handleSearchQueryChanged)
      ..dispose();
    _searchFocusNode
      ..removeListener(_handleSearchFocusChanged)
      ..dispose();
    _titleEditingFocusNode
      ..removeListener(_handleTitleEditingFocusChanged)
      ..dispose();
    _titleEditingController.dispose();
    super.dispose();
  }

  void reloadConversations() {
    _loadConversations();
  }

  bool get _isSearchActive => _searchQuery.isNotEmpty;

  String get _searchQuery => _searchController.text.trim();

  List<_ConversationSearchResult> get _visibleConversationResults {
    if (_isSearchActive) {
      return _searchResults;
    }
    return _allConversations
        .where((conversation) => !conversation.isArchived)
        .map(
          (conversation) =>
              _ConversationSearchResult(conversation: conversation),
        )
        .toList(growable: false);
  }

  Future<void> _loadConversations() async {
    debugPrint('[HomeDrawer] Loading conversations...');
    setState(() {
      isLoadingConversations = true;
    });

    try {
      final loadedConversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      debugPrint(
        '[HomeDrawer] Loaded ${loadedConversations.length} conversations',
      );
      if (!mounted) return;
      final visibleThreadKeys = loadedConversations
          .map((conversation) => conversation.threadKey)
          .toSet();
      setState(() {
        _allConversations = loadedConversations;
        _conversationSearchCache.removeWhere(
          (threadKey, _) => !visibleThreadKeys.contains(threadKey),
        );
        isLoadingConversations = false;
      });
      if (_isSearchActive) {
        _scheduleConversationSearch(immediate: true);
      }
    } catch (e) {
      debugPrint('[HomeDrawer] Failed to load conversations: $e');
      if (!mounted) return;
      setState(() {
        isLoadingConversations = false;
      });
    }
  }

  bool get _shouldCloseOnNavigate => widget.closeOnNavigate && !widget.embedded;

  Color get _drawerBackgroundColor {
    if (!context.isDarkTheme) {
      return AppColors.background;
    }
    return context.omniPalette.pageBackground;
  }

  Color get _drawerTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text;
    }
    return context.omniPalette.textPrimary;
  }

  Color get _drawerSecondaryTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text.withValues(alpha: 0.4);
    }
    return context.omniPalette.textSecondary;
  }

  void _maybeCloseDrawer() {
    if (!_shouldCloseOnNavigate || !Navigator.of(context).canPop()) {
      return;
    }
    Navigator.pop(context);
  }

  void _openThreadTarget(ConversationThreadTarget target) {
    if (widget.embedded && widget.onThreadTargetSelected != null) {
      widget.onThreadTargetSelected!(target);
      return;
    }
    _maybeCloseDrawer();
    GoRouterManager.push(
      '/home/chat',
      extra: target,
      queryParams: _threadTargetQueryParams(target),
    );
  }

  Map<String, dynamic> _threadTargetQueryParams(
    ConversationThreadTarget target,
  ) {
    return <String, dynamic>{
      'conversationId': target.conversationId?.toString() ?? 'new',
      'mode': target.mode.storageValue,
      'requestKey':
          target.requestKey ?? DateTime.now().microsecondsSinceEpoch.toString(),
    };
  }

  void _navigateTo(String route) {
    _maybeCloseDrawer();
    GoRouterManager.push(route);
  }

  void _openNewConversation() {
    _openThreadTarget(
      ConversationThreadTarget.newConversation(
        mode: widget.newConversationMode,
        requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
      ),
    );
  }

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
      debugPrint('[HomeDrawer] failed to trigger delete haptic: $error');
    }
  }

  void _handleSearchFocusChanged() {
    if (!mounted) {
      return;
    }
    setState(() {});
  }

  void _handleSearchQueryChanged() {
    if (!mounted) {
      return;
    }
    _searchGeneration += 1;
    _searchDebounceTimer?.cancel();

    if (_searchQuery.isEmpty) {
      setState(() {
        _searchResults = <_ConversationSearchResult>[];
        _isSearching = false;
      });
      return;
    }

    setState(() {});
    _scheduleConversationSearch();
  }

  void _scheduleConversationSearch({bool immediate = false}) {
    final query = _searchQuery;
    if (query.isEmpty) {
      return;
    }

    final generation = _searchGeneration;
    _searchDebounceTimer?.cancel();
    void callback() {
      unawaited(_performConversationSearch(query, generation: generation));
    }

    if (immediate) {
      callback();
      return;
    }
    _searchDebounceTimer = Timer(_searchDebounceDuration, callback);
  }

  Future<void> _performConversationSearch(
    String query, {
    required int generation,
  }) async {
    final trimmedQuery = query.trim();
    if (trimmedQuery.isEmpty || generation != _searchGeneration) {
      return;
    }

    final queryTokens = _tokenizeSearchQuery(trimmedQuery);
    if (queryTokens.isEmpty) {
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      setState(() {
        _searchResults = <_ConversationSearchResult>[];
        _isSearching = false;
      });
      return;
    }

    if (mounted) {
      setState(() {
        _isSearching = true;
      });
    }

    final snapshot = List<ConversationModel>.from(_allConversations);
    final results = <_ConversationSearchResult>[];

    for (final conversation in snapshot) {
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      final result = await _matchConversationAgainstQuery(
        conversation,
        queryTokens,
      );
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      if (result != null) {
        results.add(result);
      }
    }

    if (!mounted || generation != _searchGeneration) {
      return;
    }
    setState(() {
      _searchResults = results;
      _isSearching = false;
    });
  }

  Future<_ConversationSearchResult?> _matchConversationAgainstQuery(
    ConversationModel conversation,
    List<String> queryTokens,
  ) async {
    final metadataCandidates = _buildConversationMetadataCandidates(
      conversation,
    );
    if (_matchesSearchTokens(
      _normalizeSearchText(metadataCandidates.join('\n')),
      queryTokens,
    )) {
      return _ConversationSearchResult(
        conversation: conversation,
        matchedPreview: _resolveMatchedPreview(
          candidates: metadataCandidates,
          conversation: conversation,
          queryTokens: queryTokens,
        ),
      );
    }

    final searchIndex = await _ensureConversationSearchIndex(conversation);
    if (!_matchesSearchTokens(searchIndex.searchableText, queryTokens)) {
      return null;
    }

    return _ConversationSearchResult(
      conversation: conversation,
      matchedPreview: _resolveMatchedPreview(
        candidates: searchIndex.candidates,
        conversation: conversation,
        queryTokens: queryTokens,
      ),
    );
  }

  Future<_ConversationSearchIndex> _ensureConversationSearchIndex(
    ConversationModel conversation,
  ) async {
    final signature = _conversationSearchSignature(conversation);
    final cacheKey = conversation.threadKey;
    final cached = _conversationSearchCache[cacheKey];
    if (cached != null && cached.signature == signature) {
      return cached;
    }

    final candidates = _buildConversationMetadataCandidates(conversation);
    final seenCandidates = candidates
        .map(_normalizeSearchText)
        .where((value) => value.isNotEmpty)
        .toSet();
    final messages = await ConversationHistoryService.getConversationMessages(
      conversation.id,
      mode: conversation.mode,
      expectedMessageCount: conversation.messageCount,
    );

    for (final message in messages) {
      for (final fragment in _collectSearchableText(message)) {
        _addUniqueCandidate(candidates, seenCandidates, fragment);
      }
    }

    final searchIndex = _ConversationSearchIndex(
      signature: signature,
      candidates: List<String>.unmodifiable(candidates),
      searchableText: _normalizeSearchText(candidates.join('\n')),
    );
    _conversationSearchCache[cacheKey] = searchIndex;
    return searchIndex;
  }

  List<String> _buildConversationMetadataCandidates(
    ConversationModel conversation,
  ) {
    final candidates = <String>[];
    final seenCandidates = <String>{};
    _addUniqueCandidate(
      candidates,
      seenCandidates,
      _resolveConversationTitle(conversation),
    );
    _addUniqueCandidate(candidates, seenCandidates, conversation.summary);
    _addUniqueCandidate(
      candidates,
      seenCandidates,
      conversation.contextSummary,
    );
    _addUniqueCandidate(candidates, seenCandidates, conversation.lastMessage);
    return candidates;
  }

  List<String> _collectSearchableText(ChatMessageModel message) {
    final fragments = <String>[];
    final seenCandidates = <String>{};
    _collectSearchableTextFromValue(
      message.content,
      sink: fragments,
      seenNormalized: seenCandidates,
    );
    return fragments;
  }

  void _collectSearchableTextFromValue(
    dynamic value, {
    required List<String> sink,
    required Set<String> seenNormalized,
  }) {
    if (value == null) {
      return;
    }
    if (value is String) {
      _addUniqueCandidate(sink, seenNormalized, value);
      return;
    }
    if (value is List) {
      for (final item in value) {
        _collectSearchableTextFromValue(
          item,
          sink: sink,
          seenNormalized: seenNormalized,
        );
      }
      return;
    }
    if (value is Map) {
      for (final item in value.values) {
        _collectSearchableTextFromValue(
          item,
          sink: sink,
          seenNormalized: seenNormalized,
        );
      }
    }
  }

  void _addUniqueCandidate(
    List<String> candidates,
    Set<String> seenNormalized,
    String? rawValue,
  ) {
    final normalized = _normalizeSearchText(rawValue ?? '');
    if (normalized.isEmpty || !seenNormalized.add(normalized)) {
      return;
    }
    candidates.add((rawValue ?? '').replaceAll(RegExp(r'\s+'), ' ').trim());
  }

  List<String> _tokenizeSearchQuery(String value) {
    return _normalizeSearchText(value)
        .split(RegExp(r'\s+'))
        .where((token) => token.isNotEmpty)
        .toList(growable: false);
  }

  String _normalizeSearchText(String value) {
    return value.replaceAll(RegExp(r'\s+'), ' ').trim().toLowerCase();
  }

  bool _matchesSearchTokens(String searchableText, List<String> queryTokens) {
    if (searchableText.isEmpty || queryTokens.isEmpty) {
      return false;
    }
    return queryTokens.every(searchableText.contains);
  }

  String? _resolveMatchedPreview({
    required List<String> candidates,
    required ConversationModel conversation,
    required List<String> queryTokens,
  }) {
    final title = _resolveConversationTitle(conversation);
    for (final candidate in candidates) {
      final normalized = _normalizeSearchText(candidate);
      if (_matchesSearchTokens(normalized, queryTokens) &&
          candidate.trim() != title) {
        return candidate.trim();
      }
    }
    for (final candidate in candidates) {
      final normalized = _normalizeSearchText(candidate);
      if (queryTokens.any(normalized.contains) && candidate.trim() != title) {
        return candidate.trim();
      }
    }
    return null;
  }

  String _conversationSearchSignature(ConversationModel conversation) {
    return [
      conversation.threadKey,
      conversation.updatedAt,
      conversation.messageCount,
      conversation.isArchived ? 1 : 0,
      conversation.title,
      conversation.summary ?? '',
      conversation.contextSummary ?? '',
      conversation.lastMessage ?? '',
    ].join('|');
  }

  void _replaceConversationInState(ConversationModel updatedConversation) {
    final allConversations = List<ConversationModel>.from(_allConversations);
    final allIndex = allConversations.indexWhere(
      (item) => item.threadKey == updatedConversation.threadKey,
    );
    if (allIndex >= 0) {
      allConversations[allIndex] = updatedConversation;
    }

    final searchResults = List<_ConversationSearchResult>.from(_searchResults);
    final searchIndex = searchResults.indexWhere(
      (item) => item.conversation.threadKey == updatedConversation.threadKey,
    );
    if (searchIndex >= 0) {
      searchResults[searchIndex] = searchResults[searchIndex].copyWith(
        conversation: updatedConversation,
      );
    }

    _conversationSearchCache.remove(updatedConversation.threadKey);
    _allConversations = allConversations;
    _searchResults = searchResults;
  }

  void _removeConversationFromState(ConversationModel conversation) {
    _allConversations = List<ConversationModel>.from(_allConversations)
      ..removeWhere((item) => item.threadKey == conversation.threadKey);
    _searchResults = List<_ConversationSearchResult>.from(_searchResults)
      ..removeWhere(
        (item) => item.conversation.threadKey == conversation.threadKey,
      );
    _conversationSearchCache.remove(conversation.threadKey);
  }

  @override
  Widget build(BuildContext context) {
    final backgroundColor = _drawerBackgroundColor;
    final content = ColoredBox(
      color: backgroundColor,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 16),
            _buildUserHeader(),
            const SizedBox(height: 20),
            Expanded(child: _buildConversationSection()),
            const SizedBox(height: 12),
            _buildFooterShortcutBar(),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
    if (widget.embedded) {
      return content;
    }
    return Drawer(
      width: MediaQuery.of(context).size.width * 0.8,
      backgroundColor: backgroundColor,
      child: content,
    );
  }

  Widget _buildUserHeader() {
    final greeting = _getGreetingByTime();

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            greeting['title'] ?? context.l10n.homeDrawerGreeting,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w400,
              color: _drawerTextColor,
              height: 1.5,
            ),
          ),
          Text(
            greeting['subtitle'] ?? context.l10n.homeDrawerWelcome,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w400,
              color: _drawerTextColor,
              height: 1.5,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildConversationSection() {
    final visibleConversationResults = _visibleConversationResults;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Expanded(
                child: HomeDrawerSearchField(
                  controller: _searchController,
                  focusNode: _searchFocusNode,
                  isSearching: _isSearching,
                  textColor: _drawerTextColor,
                ),
              ),
              const SizedBox(width: 10),
              _buildSectionActionButton(
                iconPath: 'assets/home/archive_icon.svg',
                tooltip: context.l10n.homeDrawerArchive,
                onTap: () => _navigateTo('/home/archived_conversations'),
              ),
              const SizedBox(width: 10),
              _buildSectionActionButton(
                iconPath: 'assets/home/chat_add_icon.svg',
                tooltip: context.l10n.homeDrawerNewChat,
                onTap: _openNewConversation,
                isPrimary: true,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: isLoadingConversations
                ? Center(
                    child: SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          _drawerTextColor,
                        ),
                      ),
                    ),
                  )
                : visibleConversationResults.isEmpty
                ? _isSearchActive
                      ? (_isSearching
                            ? _buildSearchingConversationState()
                            : _buildEmptySearchResult())
                      : _buildEmptyConversation()
                : SlidableAutoCloseBehavior(
                    child: ListView(
                      padding: EdgeInsets.zero,
                      children: _isSearchActive
                          ? _buildSearchResultChildren(
                              visibleConversationResults,
                            )
                          : _buildConversationTimelineChildren(
                              visibleConversationResults,
                            ),
                    ),
                  ),
          ),
        ),
      ],
    );
  }

  Widget _buildEmptyConversation() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              context.l10n.chatHistoryEmpty,
              style: TextStyle(fontSize: 14, color: _drawerSecondaryTextColor),
            ),
            const SizedBox(height: 12),
            GestureDetector(
              onTap: _openNewConversation,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: context.omniPalette.accentPrimary,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  context.l10n.chatHistoryStartConversation,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSearchingConversationState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 22,
            height: 22,
            child: CircularProgressIndicator(
              strokeWidth: 2.2,
              valueColor: AlwaysStoppedAnimation<Color>(
                context.omniPalette.accentPrimary,
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            context.l10n.homeDrawerSearching,
            style: TextStyle(
              fontSize: 14,
              color: _drawerSecondaryTextColor,
              fontFamily: 'PingFang SC',
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptySearchResult() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfaceSecondary
                    : palette.previewFallback,
                shape: BoxShape.circle,
              ),
              alignment: Alignment.center,
              child: Icon(
                Icons.search_off_rounded,
                size: 22,
                color: palette.textSecondary,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              context.l10n.homeDrawerNoResults,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w500,
                color: _drawerTextColor,
                fontFamily: 'PingFang SC',
              ),
            ),
            const SizedBox(height: 6),
            Text(
              context.l10n.homeDrawerSearchHint2,
              style: TextStyle(
                fontSize: 12,
                color: _drawerSecondaryTextColor,
                fontFamily: 'PingFang SC',
              ),
            ),
            const SizedBox(height: 14),
            GestureDetector(
              onTap: _searchController.clear,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: context.isDarkTheme
                      ? palette.surfaceSecondary
                      : Colors.white,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: Text(
                  context.l10n.homeDrawerClearSearch,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color: _drawerTextColor,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _buildSearchResultChildren(
    List<_ConversationSearchResult> results,
  ) {
    final palette = context.omniPalette;
    final children = <Widget>[
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
        child: Row(
          children: [
            Icon(
              Icons.manage_search_rounded,
              size: 16,
              color: palette.textSecondary,
            ),
            const SizedBox(width: 6),
            Text(
              context.l10n.homeDrawerSearchResults,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.6,
                color: palette.textTertiary,
                fontFamily: 'PingFang SC',
              ),
            ),
            const Spacer(),
            Text(
              '${results.length} ${context.l10n.homeDrawerResultCount}',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w500,
                color: palette.textTertiary,
                fontFamily: 'PingFang SC',
              ),
            ),
          ],
        ),
      ),
    ];

    for (int index = 0; index < results.length; index++) {
      children.add(
        _buildSwipeConversationItem(
          results[index],
          showDivider: index != results.length - 1,
        ),
      );
    }
    return children;
  }

  List<Widget> _buildConversationTimelineChildren(
    List<_ConversationSearchResult> results,
  ) {
    final sections = _buildConversationSections(results);
    final children = <Widget>[];
    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      final section = sections[sectionIndex];
      if (sectionIndex > 0) {
        children.add(const SizedBox(height: 14));
      }
      children.add(_buildConversationDateSection(section));
    }
    return children;
  }

  List<_ConversationSection> _buildConversationSections(
    List<_ConversationSearchResult> results,
  ) {
    final sections = <_ConversationSection>[];
    for (final result in results) {
      final conversation = result.conversation;
      final label = conversation.timeDisplay;
      if (sections.isEmpty || sections.last.label != label) {
        sections.add(
          _ConversationSection(
            label: label,
            results: <_ConversationSearchResult>[result],
          ),
        );
      } else {
        sections.last.results.add(result);
      }
    }
    return sections;
  }

  bool _isConversationSectionExpanded(String label) =>
      _expandedConversationSections[label] ?? true;

  void _toggleConversationSection(String label) {
    setState(() {
      _expandedConversationSections[label] = !_isConversationSectionExpanded(
        label,
      );
    });
  }

  Widget _buildConversationDateSection(_ConversationSection section) {
    final expanded = _isConversationSectionExpanded(section.label);
    final items = Column(
      children: [
        const SizedBox(height: 4),
        for (int itemIndex = 0; itemIndex < section.results.length; itemIndex++)
          _buildSwipeConversationItem(
            section.results[itemIndex],
            showDivider: itemIndex != section.results.length - 1,
          ),
      ],
    );

    return Column(
      children: [
        _buildConversationSectionHeader(
          section.label,
          expanded: expanded,
          itemCount: section.results.length,
          onTap: () => _toggleConversationSection(section.label),
        ),
        TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
          duration: _sectionToggleDuration,
          curve: Curves.easeInOutCubicEmphasized,
          builder: (context, value, child) {
            return ClipRect(
              child: Align(
                alignment: Alignment.topCenter,
                heightFactor: value,
                child: Opacity(
                  opacity: value.clamp(0.0, 1.0).toDouble(),
                  child: IgnorePointer(ignoring: value < 0.99, child: child),
                ),
              ),
            );
          },
          child: items,
        ),
      ],
    );
  }

  Widget _buildConversationSectionHeader(
    String label, {
    required bool expanded,
    required int itemCount,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Container(
            constraints: const BoxConstraints(minHeight: 28),
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.fromLTRB(4, 5, 4, 5),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.6,
                    color: palette.textTertiary,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  '$itemCount',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                    color: palette.textTertiary.withValues(alpha: 0.82),
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Container(
                    height: 1,
                    color: palette.borderSubtle.withValues(
                      alpha: context.isDarkTheme ? 0.56 : 0.8,
                    ),
                  ),
                ),
                const SizedBox(width: 6),
                AnimatedRotation(
                  turns: expanded ? 0 : -0.25,
                  duration: _sectionToggleDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  child: Icon(
                    Icons.keyboard_arrow_down_rounded,
                    size: 18,
                    color: palette.textTertiary,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSectionActionButton({
    required String iconPath,
    required String tooltip,
    required VoidCallback onTap,
    bool isPrimary = false,
  }) {
    final palette = context.omniPalette;
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 32,
          height: 32,
          decoration: BoxDecoration(
            color: isPrimary
                ? context.omniPalette.accentPrimary
                : context.isDarkTheme
                ? palette.surfaceSecondary
                : Colors.white,
            shape: BoxShape.circle,
            boxShadow: [
              if (!isPrimary && !context.isDarkTheme)
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.05),
                  blurRadius: 4,
                  offset: const Offset(0, 2),
                ),
            ],
          ),
          padding: const EdgeInsets.all(8),
          child: SvgPicture.asset(
            iconPath,
            width: 16,
            height: 16,
            colorFilter: ColorFilter.mode(
              isPrimary
                  ? Theme.of(context).colorScheme.onPrimary
                  : _drawerTextColor,
              BlendMode.srcIn,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFooterShortcutBar() {
    final items = <_DrawerShortcutAction>[
      _DrawerShortcutAction(
        label: context.l10n.settingsTitle,
        assetPath: 'assets/home/setting_icon.svg',
        onTap: () => _navigateTo('/home/settings'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.memoryCenterTitle,
        svgString: _kDrawerMemoryIconSvg,
        onTap: () => _navigateTo('/memory/memory_center_page'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.skillStoreTitle,
        svgString: _kDrawerSkillStoreIconSvg,
        onTap: () => _navigateTo('/home/skill_store'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.trajectoryTitle,
        svgString: _kDrawerTaskHistoryIconSvg,
        onTap: () => _navigateTo('/task/execution_history'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.homeDrawerScheduled,
        assetPath: 'assets/common/schedule_icon.svg',
        onTap: () => _navigateTo('/task/scheduled_tasks'),
      ),
    ];

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: items
            .map((item) => Expanded(child: _buildFooterShortcutButton(item)))
            .toList(growable: false),
      ),
    );
  }

  Widget _buildFooterShortcutButton(_DrawerShortcutAction item) {
    final palette = context.omniPalette;
    final circleColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : Colors.white;
    final iconColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final icon = item.assetPath != null
        ? SvgPicture.asset(
            item.assetPath!,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          )
        : SvgPicture.string(
            item.svgString!,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          );

    return Tooltip(
      message: item.label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: item.onTap,
          borderRadius: BorderRadius.circular(24),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Center(
              child: Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: circleColor,
                  shape: BoxShape.circle,
                  boxShadow: context.isDarkTheme
                      ? const []
                      : [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.04),
                            blurRadius: 10,
                            offset: const Offset(0, 3),
                          ),
                        ],
                ),
                alignment: Alignment.center,
                child: icon,
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _startEditingTitle(ConversationModel conversation) {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    final title = _resolveConversationTitle(conversation);
    _titleEditingController.text = title;
    _titleEditingController.selection = TextSelection(
      baseOffset: 0,
      extentOffset: title.length,
    );
    setState(() {
      _editingThreadKey = conversation.threadKey;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _titleEditingFocusNode.requestFocus();
    });
  }

  void _handleTitleEditingFocusChanged() {
    if (!_titleEditingFocusNode.hasFocus && _editingThreadKey != null) {
      _commitTitleEdit();
    }
  }

  Future<void> _commitTitleEdit() async {
    final threadKey = _editingThreadKey;
    if (threadKey == null) return;

    final newTitle = _titleEditingController.text.trim();
    final conversation = _allConversations
        .cast<ConversationModel?>()
        .firstWhere((c) => c!.threadKey == threadKey, orElse: () => null);

    setState(() {
      _editingThreadKey = null;
    });

    if (conversation == null) return;

    final oldTitle = _resolveConversationTitle(conversation);
    if (newTitle.isEmpty || newTitle == oldTitle) return;

    final updated = conversation.copyWith(title: newTitle);
    setState(() {
      _replaceConversationInState(updated);
    });

    final success = await ConversationService.updateConversationTitle(
      conversationId: conversation.id,
      newTitle: newTitle,
      mode: conversation.mode,
    );

    if (!mounted) return;
    if (!success) {
      setState(() {
        _replaceConversationInState(conversation);
      });
      showToast(context.trLegacy('重命名失败'), type: ToastType.error);
    }
  }

  void _openConversationFromDrawer(ConversationModel conversation) {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    _openThreadTarget(
      ConversationThreadTarget.existing(
        conversationId: conversation.id,
        mode: conversation.mode,
      ),
    );
  }

  Future<void> _deleteConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _removeConversationFromState(conversation);
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
      _busyConversationKeys.remove(conversation.threadKey);
      if (!deleted) {
        final restoredIndex = originalIndex <= _allConversations.length
            ? originalIndex
            : _allConversations.length;
        _allConversations = List<ConversationModel>.from(_allConversations)
          ..insert(restoredIndex, conversation);
        if (_isSearchActive) {
          _scheduleConversationSearch(immediate: true);
        }
      }
    });

    showToast(
      deleted ? context.trLegacy('已删除') : context.trLegacy('删除失败'),
      type: deleted ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _archiveConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.threadKey == conversation.threadKey,
    );
    if (originalIndex < 0) {
      return;
    }

    final originalConversation = _allConversations[originalIndex];
    final archivedConversation = originalConversation.copyWith(
      isArchived: true,
    );

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _replaceConversationInState(archivedConversation);
    });

    final archived = await ConversationService.archiveConversation(
      originalConversation,
    );
    if (!mounted) {
      return;
    }

    setState(() {
      _busyConversationKeys.remove(conversation.threadKey);
      if (!archived) {
        _replaceConversationInState(originalConversation);
      }
    });

    showToast(
      archived ? context.trLegacy('已归档') : context.trLegacy('归档失败'),
      type: archived ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _unarchiveConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.threadKey == conversation.threadKey,
    );
    if (originalIndex < 0) {
      return;
    }

    final originalConversation = _allConversations[originalIndex];
    final restoredConversation = originalConversation.copyWith(
      isArchived: false,
    );

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _replaceConversationInState(restoredConversation);
    });

    final restored = await ConversationService.unarchiveConversation(
      originalConversation,
    );
    if (!mounted) {
      return;
    }

    setState(() {
      _busyConversationKeys.remove(conversation.threadKey);
      if (!restored) {
        _replaceConversationInState(originalConversation);
      }
    });

    showToast(
      restored ? context.trLegacy('已取消归档') : context.trLegacy('取消归档失败'),
      type: restored ? ToastType.success : ToastType.error,
    );
  }

  List<ConversationSlideAction> _buildDrawerActions(
    ConversationModel conversation,
  ) {
    return [
      ConversationSlideAction(
        onPressed: () => _deleteConversation(conversation),
        backgroundColor: AppColors.alertRed,
        child: Center(
          child: SvgPicture.asset(
            'assets/memory/memory_delete.svg',
            width: _conversationActionIconSize,
            height: _conversationActionIconSize,
            colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
          ),
        ),
      ),
      ConversationSlideAction(
        onPressed: () => conversation.isArchived
            ? _unarchiveConversation(conversation)
            : _archiveConversation(conversation),
        backgroundColor: context.isDarkTheme
            ? Color.lerp(
                context.omniPalette.surfaceElevated,
                context.omniPalette.accentPrimary,
                0.3,
              )!
            : AppColors.buttonPrimary,
        borderRadius: _drawerTrailingActionRadius,
        child: Center(
          child: conversation.isArchived
              ? Icon(
                  Icons.unarchive_outlined,
                  size: _conversationActionIconSize,
                  color: Colors.white,
                )
              : SvgPicture.asset(
                  'assets/home/archive_icon.svg',
                  width: _conversationActionIconSize,
                  height: _conversationActionIconSize,
                  colorFilter: const ColorFilter.mode(
                    Colors.white,
                    BlendMode.srcIn,
                  ),
                ),
        ),
      ),
    ];
  }

  String _resolveConversationTitle(ConversationModel conversation) {
    final title = conversation.title.trim();
    if (title.isNotEmpty) {
      return title;
    }
    final summary = (conversation.summary ?? '').trim();
    return summary.isNotEmpty ? summary : context.trLegacy('未命名对话');
  }

  Widget _buildSwipeConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
  }) {
    final conversation = result.conversation;
    final isBusy = _busyConversationKeys.contains(conversation.threadKey);
    final title = _resolveConversationTitle(conversation);
    final showArchivedBadge = _isSearchActive && conversation.isArchived;

    final isEditing = _editingThreadKey == conversation.threadKey;

    return ConversationSlidable(
      itemKey: conversation.threadKey,
      groupTag: 'home-drawer-conversations',
      isBusy: isBusy,
      actions: _buildDrawerActions(conversation),
      onDismissed: () => _deleteConversation(conversation),
      onFullSwipe: () => conversation.isArchived
          ? _unarchiveConversation(conversation)
          : _archiveConversation(conversation),
      child: Column(
        children: [
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: isEditing
                  ? null
                  : () => _openConversationFromDrawer(conversation),
              onLongPress: isEditing
                  ? null
                  : () => _startEditingTitle(conversation),
              borderRadius: BorderRadius.circular(14),
              splashColor: context.omniPalette.accentPrimary.withValues(
                alpha: 0.08,
              ),
              highlightColor: Colors.transparent,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(4, 9, 2, 9),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Expanded(
                          child: isEditing
                              ? TextField(
                                  controller: _titleEditingController,
                                  focusNode: _titleEditingFocusNode,
                                  maxLines: 1,
                                  cursorColor: _drawerTextColor.withValues(
                                    alpha: 0.6,
                                  ),
                                  cursorWidth: 1.5,
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w500,
                                    color: _drawerTextColor,
                                    height: 1.35,
                                    fontFamily: 'PingFang SC',
                                  ),
                                  decoration: const InputDecoration(
                                    isDense: true,
                                    contentPadding: EdgeInsets.zero,
                                    border: InputBorder.none,
                                    focusedBorder: InputBorder.none,
                                    enabledBorder: InputBorder.none,
                                    disabledBorder: InputBorder.none,
                                    errorBorder: InputBorder.none,
                                    focusedErrorBorder: InputBorder.none,
                                  ),
                                  onTapOutside: (_) => _commitTitleEdit(),
                                  onSubmitted: (_) => _commitTitleEdit(),
                                )
                              : Text(
                                  title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w500,
                                    color: _drawerTextColor,
                                    height: 1.35,
                                    fontFamily: 'PingFang SC',
                                  ),
                                ),
                        ),
                        if (showArchivedBadge) ...[
                          const SizedBox(width: 10),
                          _buildArchivedBadge(),
                        ],
                      ],
                    ),
                    if (_isSearchActive && result.matchedPreview != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        result.matchedPreview!,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w400,
                          color: _drawerSecondaryTextColor,
                          height: 1.4,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
          if (showDivider) const SizedBox(height: 2),
        ],
      ),
    );
  }

  Widget _buildArchivedBadge() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.previewFallback,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.archive_outlined, size: 11, color: palette.textSecondary),
          const SizedBox(width: 4),
          Text(
            context.trLegacy('已归档'),
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: palette.textSecondary,
              fontFamily: 'PingFang SC',
            ),
          ),
        ],
      ),
    );
  }
}

class _DrawerShortcutAction {
  const _DrawerShortcutAction({
    required this.label,
    required this.onTap,
    this.assetPath,
    this.svgString,
  }) : assert(
         assetPath != null || svgString != null,
         'assetPath or svgString is required',
       );

  final String label;
  final VoidCallback onTap;
  final String? assetPath;
  final String? svgString;
}

class _ConversationSection {
  _ConversationSection({required this.label, required this.results});

  final String label;
  final List<_ConversationSearchResult> results;
}

class _ConversationSearchIndex {
  const _ConversationSearchIndex({
    required this.signature,
    required this.candidates,
    required this.searchableText,
  });

  final String signature;
  final List<String> candidates;
  final String searchableText;
}

class _ConversationSearchResult {
  const _ConversationSearchResult({
    required this.conversation,
    this.matchedPreview,
  });

  final ConversationModel conversation;
  final String? matchedPreview;

  _ConversationSearchResult copyWith({
    ConversationModel? conversation,
    String? matchedPreview,
  }) {
    return _ConversationSearchResult(
      conversation: conversation ?? this.conversation,
      matchedPreview: matchedPreview ?? this.matchedPreview,
    );
  }
}
