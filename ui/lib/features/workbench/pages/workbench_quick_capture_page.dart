import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_context.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

enum _QuickCaptureFilter { all, todo, summary, link, later, archived }

enum _QuickCapturePage { capture, inbox, archive }

class WorkbenchQuickCapturePage extends StatefulWidget {
  const WorkbenchQuickCapturePage({
    super.key,
    WorkbenchTodoLogService? service,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool debugMode = false,
    bool embedded = false,
  }) : _service = service,
       _projectId = projectId,
       _displayId = displayId,
       _returnTo = returnTo,
       _debugMode = debugMode,
       _embedded = embedded;

  final WorkbenchTodoLogService? _service;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _debugMode;
  final bool _embedded;

  @override
  State<WorkbenchQuickCapturePage> createState() =>
      _WorkbenchQuickCapturePageState();
}

class _WorkbenchQuickCapturePageState extends State<WorkbenchQuickCapturePage> {
  late final WorkbenchTodoLogService _service;
  late final bool _ownsService = widget._service == null;
  final TextEditingController _captureController = TextEditingController();
  _QuickCaptureFilter _filter = _QuickCaptureFilter.all;
  late _QuickCapturePage _page;
  String? _lastReportedFrontendKey;

  @override
  void initState() {
    super.initState();
    _page = _pageFromDisplayId(widget._displayId);
    _service =
        widget._service ??
        WorkbenchTodoLogService(
          backend: NativeWorkbenchProjectBackend(),
          projectId: widget._projectId ?? workbenchQuickCaptureProjectId,
          initialProject: WorkbenchQuickCaptureProjectFactory.create(),
          autoCreateTodoIfMissing: false,
        );
    _service.initialize();
  }

  @override
  void didUpdateWidget(covariant WorkbenchQuickCapturePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget._displayId != widget._displayId) {
      _page = _pageFromDisplayId(widget._displayId);
    }
  }

  @override
  void dispose() {
    _captureController.dispose();
    if (_ownsService) {
      _service.dispose();
    }
    super.dispose();
  }

  Future<void> _ingest() async {
    final text = _captureController.text.trim();
    if (text.isEmpty) {
      showToast(
        context.l10n.workbenchQuickCaptureInputRequired,
        type: ToastType.error,
      );
      return;
    }
    final result = await _service.runTool(WorkbenchQuickCaptureToolIds.ingest, {
      'text': text,
      if (_urlFromText(text).isNotEmpty) 'url': _urlFromText(text),
    });
    if (!mounted) return;
    if (!result.success) {
      showToast(
        result.errorMessage ?? context.l10n.workbenchUnknownTool,
        type: ToastType.error,
      );
      return;
    }
    _captureController.clear();
    showToast(
      context.l10n.workbenchQuickCaptureCaptured,
      type: ToastType.success,
    );
  }

  Future<void> _archive(WorkbenchQuickCaptureItem item) async {
    final result = await _service.runTool(
      WorkbenchQuickCaptureToolIds.archive,
      {'item_id': item.id},
    );
    if (!mounted) return;
    showToast(
      result.success
          ? context.l10n.workbenchQuickCaptureArchivedToast
          : context.l10n.workbenchUnknownTool,
      type: result.success ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _promoteToTodo(WorkbenchQuickCaptureItem item) async {
    final result = await _service.runTool(
      WorkbenchQuickCaptureToolIds.promoteToTodo,
      {'item_id': item.id, 'todo_title': item.title},
    );
    if (!mounted) return;
    showToast(
      result.success
          ? context.l10n.workbenchQuickCapturePromotedToast
          : context.l10n.workbenchUnknownTool,
      type: result.success ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _summarize(WorkbenchQuickCaptureItem item) async {
    final result = await _service.runTool(
      WorkbenchQuickCaptureToolIds.summarize,
      {'item_id': item.id},
    );
    if (!mounted) return;
    showToast(
      result.success
          ? context.l10n.workbenchQuickCaptureSummarizedToast
          : context.l10n.workbenchUnknownTool,
      type: result.success ? ToastType.success : ToastType.error,
    );
  }

  void _handleBackNavigation() {
    final returnTo = widget._returnTo?.trim();
    if (returnTo != null && returnTo.isNotEmpty) {
      context.go(returnTo);
      return;
    }
    if (GoRouterManager.canPop()) {
      GoRouterManager.pop();
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  void _reportVisibleDisplay(WorkbenchProject project) {
    final display = _selectedDisplay(project);
    final route = workbenchRouteForDisplay(
      project,
      display,
      fallbackRoute: '/workbench/quick_capture',
    );
    final key = '${project.projectId}:${display.id}:$route:${widget._embedded}';
    if (_lastReportedFrontendKey == key) return;
    _lastReportedFrontendKey = key;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(
        NativeWorkbenchProjectBackend().setActiveFrontendContext(
          buildWorkbenchVisibleFrontendContext(
            project: project,
            display: display,
            source: 'workbench_quick_capture_page',
            fallbackRoute: '/workbench/quick_capture',
            extraVisibleState: {'embedded': widget._embedded},
          ),
        ),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final body = AnimatedBuilder(
      animation: _service,
      builder: (context, _) {
        final project = _service.project;
        _reportVisibleDisplay(project);
        final content = ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
          children: [
            if (_service.loading) ...[
              const LinearProgressIndicator(minHeight: 2),
              const SizedBox(height: 12),
            ],
            _buildHeader(project),
            if (widget._debugMode) ...[
              const SizedBox(height: 12),
              _buildDebugBanner(project),
            ],
            const SizedBox(height: 12),
            _buildDisplayTabs(),
            const SizedBox(height: 12),
            if (_page == _QuickCapturePage.capture) ...[
              _buildCaptureCard(),
              const SizedBox(height: 12),
              _buildItems(project),
            ] else if (_page == _QuickCapturePage.inbox) ...[
              _buildFilterChips(),
              const SizedBox(height: 12),
              _buildItems(project),
            ] else
              _buildItems(project),
          ],
        );
        return content;
      },
    );
    if (widget._embedded) {
      return Material(color: palette.pageBackground, child: body);
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: _displayTitle(_service.project),
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(child: body),
      ),
    );
  }

  Widget _buildHeader(WorkbenchProject project) {
    final palette = context.omniPalette;
    final display = _selectedDisplay(project);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          display.title.trim().isEmpty
              ? context.l10n.workbenchQuickCaptureTitle
              : display.title,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 24,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          _pageSubtitle(project),
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            height: 1.35,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _buildDisplayTabs() {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: _QuickCapturePage.values
            .map((page) {
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  selected: _page == page,
                  avatar: Icon(_pageIcon(page), size: 16),
                  label: Text(_pageLabel(page)),
                  onSelected: (_) => setState(() {
                    _page = page;
                    if (page == _QuickCapturePage.archive) {
                      _filter = _QuickCaptureFilter.archived;
                    } else if (_filter == _QuickCaptureFilter.archived) {
                      _filter = _QuickCaptureFilter.all;
                    }
                  }),
                ),
              );
            })
            .toList(growable: false),
      ),
    );
  }

  Widget _buildDebugBanner(WorkbenchProject project) {
    final palette = context.omniPalette;
    final display = _selectedDisplay(project);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          _buildPill(
            Icons.bug_report_outlined,
            context.l10n.workbenchDebugMode,
          ),
          _buildPill(
            Icons.folder_open_rounded,
            context.l10n.workbenchDebugContextProject(project.projectId),
          ),
          _buildPill(
            Icons.widgets_outlined,
            context.l10n.workbenchDebugContextDisplay(display.id),
          ),
        ],
      ),
    );
  }

  Widget _buildCaptureCard() {
    final palette = context.omniPalette;
    return _buildSurface(
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _captureController,
              minLines: 1,
              maxLines: 3,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _ingest(),
              decoration: InputDecoration(
                hintText: context.l10n.workbenchQuickCaptureInputHint,
                filled: true,
                fillColor: palette.surfaceSecondary,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),
          IconButton.filled(
            onPressed: _ingest,
            tooltip: context.l10n.workbenchQuickCaptureAction,
            icon: const Icon(Icons.add_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterChips() {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: _activeFilters
            .map((filter) {
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  selected: _filter == filter,
                  label: Text(_filterLabel(filter)),
                  avatar: Icon(_filterIcon(filter), size: 16),
                  onSelected: (_) => setState(() => _filter = filter),
                ),
              );
            })
            .toList(growable: false),
      ),
    );
  }

  Widget _buildItems(WorkbenchProject project) {
    final items = _filteredItems(project);
    if (items.isEmpty) {
      return _buildSurface(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 18),
          child: Center(
            child: Text(
              context.l10n.workbenchQuickCaptureEmpty,
              style: TextStyle(
                color: context.omniPalette.textSecondary,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      );
    }
    return Column(children: items.map(_buildItemCard).toList(growable: false));
  }

  Widget _buildItemCard(WorkbenchQuickCaptureItem item) {
    final palette = context.omniPalette;
    final archived = item.isArchived;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: archived
            ? palette.surfaceSecondary.withValues(alpha: 0.68)
            : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
        boxShadow: archived
            ? null
            : [
                BoxShadow(
                  color: palette.shadowColor,
                  blurRadius: 16,
                  offset: const Offset(0, 6),
                ),
              ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: _itemColor(item).withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(_itemIcon(item), size: 19, color: _itemColor(item)),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: archived
                            ? palette.textSecondary
                            : palette.textPrimary,
                        fontSize: 15,
                        height: 1.25,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        _buildTinyTag(_typeLabel(item)),
                        if (item.sourceApp?.trim().isNotEmpty == true)
                          _buildTinyTag(item.sourceApp!.trim()),
                        if (item.dueHint?.trim().isNotEmpty == true)
                          _buildTinyTag(item.dueHint!.trim()),
                        if (archived)
                          _buildTinyTag(
                            context.l10n.workbenchQuickCaptureArchived,
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (item.summary.trim().isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              item.summary,
              maxLines: 4,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
          if (item.url?.trim().isNotEmpty == true) ...[
            const SizedBox(height: 8),
            Text(
              item.url!.trim(),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.accentPrimary,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
          const SizedBox(height: 10),
          Row(
            children: [
              if (!item.isTodo && !archived)
                _buildIconAction(
                  icon: Icons.task_alt_rounded,
                  tooltip: context.l10n.workbenchQuickCapturePromoteToTodo,
                  onPressed: () => _promoteToTodo(item),
                ),
              if (!item.isTodo && !archived) const SizedBox(width: 6),
              if (!archived)
                _buildIconAction(
                  icon: Icons.summarize_outlined,
                  tooltip: context.l10n.workbenchQuickCaptureSummarize,
                  onPressed: () => _summarize(item),
                ),
              const Spacer(),
              if (!archived)
                _buildIconAction(
                  icon: Icons.archive_outlined,
                  tooltip: context.l10n.workbenchQuickCaptureArchive,
                  onPressed: () => _archive(item),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSurface({required Widget child}) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: child,
    );
  }

  Widget _buildPill(IconData icon, String label) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 15, color: palette.accentPrimary),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTinyTag(String label) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 11,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }

  Widget _buildIconAction({
    required IconData icon,
    required String tooltip,
    required VoidCallback onPressed,
  }) {
    return IconButton.filledTonal(
      visualDensity: VisualDensity.compact,
      tooltip: tooltip,
      onPressed: onPressed,
      icon: Icon(icon, size: 18),
    );
  }

  List<WorkbenchQuickCaptureItem> _filteredItems(WorkbenchProject project) {
    final items = project.captureItems;
    if (_page == _QuickCapturePage.capture) {
      return items
          .where((item) => !item.isArchived)
          .take(5)
          .toList(growable: false);
    }
    if (_page == _QuickCapturePage.archive) {
      return items.where((item) => item.isArchived).toList(growable: false);
    }
    return switch (_filter) {
      _QuickCaptureFilter.all =>
        items.where((item) => !item.isArchived).toList(growable: false),
      _QuickCaptureFilter.todo =>
        items
            .where((item) => item.isTodo && !item.isArchived)
            .toList(growable: false),
      _QuickCaptureFilter.summary =>
        items
            .where((item) => item.isSummary && !item.isArchived)
            .toList(growable: false),
      _QuickCaptureFilter.link =>
        items
            .where((item) => item.isLink && !item.isArchived)
            .toList(growable: false),
      _QuickCaptureFilter.later =>
        items
            .where((item) => item.isLater && !item.isArchived)
            .toList(growable: false),
      _QuickCaptureFilter.archived =>
        items.where((item) => item.isArchived).toList(growable: false),
    };
  }

  List<_QuickCaptureFilter> get _activeFilters => const [
    _QuickCaptureFilter.all,
    _QuickCaptureFilter.todo,
    _QuickCaptureFilter.summary,
    _QuickCaptureFilter.link,
    _QuickCaptureFilter.later,
  ];

  _QuickCapturePage _pageFromDisplayId(String? displayId) {
    final id = displayId?.trim().toLowerCase() ?? '';
    if (id.contains('archive')) return _QuickCapturePage.archive;
    if (id.contains('inbox')) return _QuickCapturePage.inbox;
    return _QuickCapturePage.capture;
  }

  String _pageLabel(_QuickCapturePage page) {
    return switch (page) {
      _QuickCapturePage.capture =>
        context.l10n.workbenchQuickCapturePageCapture,
      _QuickCapturePage.inbox => context.l10n.workbenchQuickCapturePageInbox,
      _QuickCapturePage.archive => context.l10n.workbenchQuickCaptureArchived,
    };
  }

  String _pageSubtitle(WorkbenchProject project) {
    return switch (_page) {
      _QuickCapturePage.capture => context.l10n.workbenchQuickCaptureSubtitle,
      _QuickCapturePage.inbox =>
        context.l10n.workbenchQuickCaptureInboxSubtitle(
          project.activeCaptureItems.length,
        ),
      _QuickCapturePage.archive =>
        context.l10n.workbenchQuickCaptureArchiveSubtitle(
          project.archivedCaptureItems.length,
        ),
    };
  }

  IconData _pageIcon(_QuickCapturePage page) {
    return switch (page) {
      _QuickCapturePage.capture => Icons.add_circle_outline_rounded,
      _QuickCapturePage.inbox => Icons.inbox_outlined,
      _QuickCapturePage.archive => Icons.archive_outlined,
    };
  }

  String _filterLabel(_QuickCaptureFilter filter) {
    return switch (filter) {
      _QuickCaptureFilter.all => context.l10n.workbenchQuickCaptureFilterAll,
      _QuickCaptureFilter.todo => context.l10n.workbenchQuickCaptureTypeTodo,
      _QuickCaptureFilter.summary =>
        context.l10n.workbenchQuickCaptureTypeSummary,
      _QuickCaptureFilter.link => context.l10n.workbenchQuickCaptureTypeLink,
      _QuickCaptureFilter.later => context.l10n.workbenchQuickCaptureTypeLater,
      _QuickCaptureFilter.archived =>
        context.l10n.workbenchQuickCaptureArchived,
    };
  }

  IconData _filterIcon(_QuickCaptureFilter filter) {
    return switch (filter) {
      _QuickCaptureFilter.all => Icons.inbox_outlined,
      _QuickCaptureFilter.todo => Icons.check_circle_outline_rounded,
      _QuickCaptureFilter.summary => Icons.subject_rounded,
      _QuickCaptureFilter.link => Icons.link_rounded,
      _QuickCaptureFilter.later => Icons.bookmark_border_rounded,
      _QuickCaptureFilter.archived => Icons.archive_outlined,
    };
  }

  String _typeLabel(WorkbenchQuickCaptureItem item) {
    return switch (item.type) {
      'summary' => context.l10n.workbenchQuickCaptureTypeSummary,
      'link' => context.l10n.workbenchQuickCaptureTypeLink,
      'later' => context.l10n.workbenchQuickCaptureTypeLater,
      _ => context.l10n.workbenchQuickCaptureTypeTodo,
    };
  }

  IconData _itemIcon(WorkbenchQuickCaptureItem item) {
    return switch (item.type) {
      'summary' => Icons.subject_rounded,
      'link' => Icons.link_rounded,
      'later' => Icons.bookmark_border_rounded,
      _ => Icons.check_circle_outline_rounded,
    };
  }

  Color _itemColor(WorkbenchQuickCaptureItem item) {
    return switch (item.type) {
      'summary' => const Color(0xFF2563EB),
      'link' => const Color(0xFF0F766E),
      'later' => const Color(0xFF7C3AED),
      _ => const Color(0xFF16A34A),
    };
  }

  WorkbenchDisplaySpec _selectedDisplay(WorkbenchProject project) {
    final displayId = widget._displayId?.trim();
    if (displayId != null && displayId.isNotEmpty) {
      for (final display in project.displays) {
        if (display.id == displayId) return display;
      }
    }
    return project.primaryDisplay;
  }

  String _displayTitle(WorkbenchProject project) {
    final label = _selectedDisplay(project).label.trim();
    return label.isEmpty ? context.l10n.workbenchQuickCaptureTitle : label;
  }

  String _urlFromText(String text) {
    return RegExp(
          r"""https?://[^\s，。；、)）\]】"'<>]+""",
        ).firstMatch(text)?.group(0)?.trim() ??
        '';
  }
}
