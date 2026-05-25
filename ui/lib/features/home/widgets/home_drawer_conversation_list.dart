// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

const String _kExpandedConversationSectionsStorageKey =
    'home_drawer_expanded_sections_v1';
const String _kPinnedConversationSectionKey = '__home_drawer_pinned__';
const String _kScheduledConversationSectionKey = '__home_drawer_scheduled__';

extension _HomeDrawerConversationList on HomeDrawerState {
  Widget _buildConversationSection() {
    final visibleConversationResults = _visibleConversationResults;
    final hasPromotedConversations =
        !_isSearchActive &&
        (_scheduledConversationGroups.isNotEmpty ||
            _pinnedConversationResults.isNotEmpty);
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
                ? hasPromotedConversations
                      ? SlidableAutoCloseBehavior(
                          child: ListView(
                            padding: EdgeInsets.zero,
                            children: _buildConversationTimelineChildren(
                              visibleConversationResults,
                            ),
                          ),
                        )
                      : _isSearchActive
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
    final scheduledGroups = _scheduledConversationGroups;
    final pinnedResults = _pinnedConversationResults;
    final sections = _buildConversationSections(results);
    final children = <Widget>[];
    if (scheduledGroups.isNotEmpty) {
      children.add(_buildScheduledConversationSection(scheduledGroups));
    }
    if (pinnedResults.isNotEmpty) {
      if (children.isNotEmpty) {
        children.add(const SizedBox(height: 12));
      }
      children.add(_buildPinnedConversationSection(pinnedResults));
    }
    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      final section = sections[sectionIndex];
      if (children.isNotEmpty || sectionIndex > 0) {
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
      final sectionKey = _dateConversationSectionKeyForConversation(
        conversation,
      );
      if (sections.isEmpty || sections.last.sectionKey != sectionKey) {
        sections.add(
          _ConversationSection(
            label: label,
            sectionKey: sectionKey,
            results: <_ConversationSearchResult>[result],
          ),
        );
      } else {
        sections.last.results.add(result);
      }
    }
    return sections;
  }

  bool _isConversationSectionExpanded(String sectionKey) =>
      _expandedConversationSections[sectionKey] ?? true;

  void _toggleConversationSection(String sectionKey) {
    setState(() {
      _expandedConversationSections[sectionKey] =
          !_isConversationSectionExpanded(sectionKey);
    });
    _persistExpandedConversationSections();
  }

  String _dateConversationSectionKeyForConversation(
    ConversationModel conversation,
  ) {
    final date = conversation.updatedDate;
    final year = date.year.toString().padLeft(4, '0');
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '__home_drawer_date__$year-$month-$day';
  }

  String _scheduledParentConversationSectionKey(ConversationModel parent) =>
      '__home_drawer_scheduled_${parent.threadKey}';

  void _restoreExpandedConversationSections() {
    _expandedConversationSections
      ..clear()
      ..addAll(_loadExpandedConversationSectionsFromStorage());
  }

  Map<String, bool> _loadExpandedConversationSectionsFromStorage() {
    final raw = StorageService.getString(
      _kExpandedConversationSectionsStorageKey,
    );
    if (raw == null || raw.trim().isEmpty) {
      return <String, bool>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        return <String, bool>{};
      }
      return <String, bool>{
        for (final entry in decoded.entries)
          if (entry.value is bool) entry.key.toString(): entry.value as bool,
      };
    } catch (error) {
      debugPrint('[HomeDrawer] Failed to load expanded sections: $error');
      return <String, bool>{};
    }
  }

  void _persistExpandedConversationSections() {
    final snapshot = Map<String, bool>.from(_expandedConversationSections);
    unawaited(
      StorageService.setString(
        _kExpandedConversationSectionsStorageKey,
        jsonEncode(snapshot),
      ),
    );
  }

  Widget _buildPinnedConversationSection(
    List<_ConversationSearchResult> results,
  ) {
    return _buildPromotedConversationSection(
      sectionKey: _kPinnedConversationSectionKey,
      label: context.trLegacy('置顶会话'),
      itemCount: results.length,
      backgroundColor: _promotedSectionBackgroundColor,
      children: [
        for (int itemIndex = 0; itemIndex < results.length; itemIndex++)
          _buildSwipeConversationItem(
            results[itemIndex],
            showDivider: itemIndex != results.length - 1,
          ),
      ],
    );
  }

  Widget _buildScheduledConversationSection(
    List<_ScheduledConversationGroup> groups,
  ) {
    final itemCount = groups.fold<int>(
      0,
      (count, group) => count + 1 + group.children.length,
    );
    return _buildPromotedConversationSection(
      sectionKey: _kScheduledConversationSectionKey,
      label: context.trLegacy('定时任务'),
      itemCount: itemCount,
      backgroundColor: _promotedSectionBackgroundColor,
      children: [
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++)
          _buildScheduledConversationGroup(
            groups[groupIndex],
            showDivider: groupIndex != groups.length - 1,
          ),
      ],
    );
  }

  Widget _buildPromotedConversationSection({
    required String sectionKey,
    required String label,
    required int itemCount,
    required Color backgroundColor,
    required List<Widget> children,
  }) {
    final expanded = _isConversationSectionExpanded(sectionKey);
    final items = Column(children: [const SizedBox(height: 2), ...children]);
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          _buildConversationSectionHeader(
            label,
            expanded: expanded,
            itemCount: itemCount,
            onTap: () => _toggleConversationSection(sectionKey),
          ),
          TweenAnimationBuilder<double>(
            tween: Tween<double>(
              begin: expanded ? 1 : 0,
              end: expanded ? 1 : 0,
            ),
            duration: HomeDrawerState._sectionToggleDuration,
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
      ),
    );
  }

  Color get _promotedSectionBackgroundColor {
    final palette = context.omniPalette;
    return context.isDarkTheme
        ? Color.lerp(palette.surfaceSecondary, palette.accentPrimary, 0.08)!
        : palette.accentPrimary.withValues(alpha: 0.045);
  }

  Widget _buildScheduledConversationGroup(
    _ScheduledConversationGroup group, {
    required bool showDivider,
  }) {
    final parentSectionKey = _scheduledParentConversationSectionKey(
      group.parent,
    );
    final expanded = _isConversationSectionExpanded(parentSectionKey);
    final children = Column(
      children: [
        for (
          int childIndex = 0;
          childIndex < group.children.length;
          childIndex++
        )
          _buildScheduledChildConversationItem(
            group.children[childIndex],
            showDivider: childIndex != group.children.length - 1,
          ),
      ],
    );

    return Column(
      children: [
        _buildScheduledParentConversationRow(
          group,
          onToggle: () => _toggleConversationSection(parentSectionKey),
        ),
        TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
          duration: HomeDrawerState._sectionToggleDuration,
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
          child: children,
        ),
        if (showDivider) const SizedBox(height: 6),
      ],
    );
  }

  Widget _buildScheduledParentConversationRow(
    _ScheduledConversationGroup group, {
    required VoidCallback onToggle,
  }) {
    final palette = context.omniPalette;
    final title = _resolveConversationTitle(group.parent);
    final childCount = group.children.length;
    final countText = childCount > 0 ? '$childCount' : '${group.taskCount}';
    final isEditing = _editingThreadKey == group.parent.threadKey;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: isEditing
            ? null
            : () => _openConversationFromDrawer(group.parent),
        onLongPress: isEditing ? null : () => _startEditingTitle(group.parent),
        borderRadius: BorderRadius.circular(8),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 8, 2, 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: onToggle,
                child: SizedBox(
                  width: 24,
                  height: 24,
                  child: Center(
                    child: SvgPicture.asset(
                      'assets/home/git_fork_icon.svg',
                      width: 16,
                      height: 16,
                      colorFilter: ColorFilter.mode(
                        palette.textTertiary,
                        BlendMode.srcIn,
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 2),
              Expanded(
                child: _buildEditableConversationTitle(
                  title: title,
                  isEditing: isEditing,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                countText,
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
      ),
    );
  }

  Widget _buildScheduledChildConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
  }) {
    return Padding(
      padding: const EdgeInsets.only(left: 26),
      child: _buildSwipeConversationItem(
        result,
        showDivider: showDivider,
        includePinAction: false,
      ),
    );
  }

  Widget _buildConversationDateSection(_ConversationSection section) {
    final expanded = _isConversationSectionExpanded(section.sectionKey);
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
          onTap: () => _toggleConversationSection(section.sectionKey),
        ),
        TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
          duration: HomeDrawerState._sectionToggleDuration,
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
          child: Semantics(
            button: true,
            toggled: expanded,
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
                  const Spacer(),
                ],
              ),
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

  Widget _buildSwipeConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
    bool includePinAction = true,
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
      actions: _buildDrawerActions(
        conversation,
        includePinAction: includePinAction,
      ),
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
                          child: _buildEditableConversationTitle(
                            title: title,
                            isEditing: isEditing,
                          ),
                        ),
                        if (showArchivedBadge) ...[
                          const SizedBox(width: 10),
                          _buildArchivedBadge(),
                        ],
                      ],
                    ),
                    _buildConversationImagePreviewStrip(conversation),
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

  Widget _buildEditableConversationTitle({
    required String title,
    required bool isEditing,
    FontWeight fontWeight = FontWeight.w500,
  }) {
    if (isEditing) {
      return TextField(
        controller: _titleEditingController,
        focusNode: _titleEditingFocusNode,
        maxLines: 1,
        cursorColor: _drawerTextColor.withValues(alpha: 0.6),
        cursorWidth: 1.5,
        style: TextStyle(
          fontSize: 13,
          fontWeight: fontWeight,
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
      );
    }

    return Text(
      title,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(
        fontSize: 13,
        fontWeight: fontWeight,
        color: _drawerTextColor,
        height: 1.35,
        fontFamily: 'PingFang SC',
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
