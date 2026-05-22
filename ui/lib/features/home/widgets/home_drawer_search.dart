// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

extension _HomeDrawerSearch on HomeDrawerState {
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
    final generation = ++_conversationLoadGeneration;
    debugPrint('[HomeDrawer] Loading conversations...');
    setState(() {
      isLoadingConversations =
          _allConversations.isEmpty &&
          !HomeDrawerState._hasConversationSnapshotCache;
    });

    try {
      final loadedConversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      debugPrint(
        '[HomeDrawer] Loaded ${loadedConversations.length} conversations',
      );
      if (!mounted || generation != _conversationLoadGeneration) return;
      final visibleThreadKeys = loadedConversations
          .map((conversation) => conversation.threadKey)
          .toSet();
      _conversationSearchCache.removeWhere(
        (threadKey, _) => !visibleThreadKeys.contains(threadKey),
      );
      _pruneConversationImagePreviewState(visibleThreadKeys);
      if (!mounted || generation != _conversationLoadGeneration) return;
      setState(() {
        _allConversations = loadedConversations;
        isLoadingConversations = false;
      });
      _rememberConversationSnapshotCache(loadedConversations);
      _scheduleDrawerSnapshotPersist(loadedConversations);
      _refreshConversationImagePreviewsInBackground(
        loadedConversations
            .where((conversation) => !conversation.isArchived)
            .toList(growable: false),
        generation: generation,
      );
      if (_isSearchActive) {
        _scheduleConversationSearch(immediate: true);
      }
    } catch (e) {
      debugPrint('[HomeDrawer] Failed to load conversations: $e');
      if (!mounted || generation != _conversationLoadGeneration) return;
      setState(() {
        isLoadingConversations = false;
      });
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
    _searchDebounceTimer = Timer(
      HomeDrawerState._searchDebounceDuration,
      callback,
    );
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
}
