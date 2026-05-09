import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchSchemaProjectPage extends StatefulWidget {
  const WorkbenchSchemaProjectPage({
    super.key,
    WorkbenchTodoLogService? service,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool embedded = false,
  }) : _service = service,
       _projectId = projectId,
       _displayId = displayId,
       _returnTo = returnTo,
       _embedded = embedded;

  final WorkbenchTodoLogService? _service;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _embedded;

  @override
  State<WorkbenchSchemaProjectPage> createState() =>
      _WorkbenchSchemaProjectPageState();
}

class _WorkbenchSchemaProjectPageState
    extends State<WorkbenchSchemaProjectPage> {
  late final WorkbenchTodoLogService _service;
  late final bool _ownsService = widget._service == null;
  final TextEditingController _itemController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _service =
        widget._service ??
        WorkbenchTodoLogService.native(
          projectId: widget._projectId,
          autoCreateTodoIfMissing: false,
        );
    _service.initialize();
  }

  @override
  void dispose() {
    _itemController.dispose();
    if (_ownsService) {
      _service.dispose();
    }
    super.dispose();
  }

  Future<void> _createItem(WorkbenchProject project) async {
    final api = _createApi(project);
    final title = _itemController.text.trim();
    if (api == null) {
      showToast(
        context.l10n.workbenchSchemaMissingCreateApi,
        type: ToastType.error,
      );
      return;
    }
    final result = await _service.runTool(api.id, {'title': title});
    if (!mounted) return;
    if (!result.success) {
      showToast(
        context.l10n.workbenchSchemaInputRequired(_entityName(project)),
        type: ToastType.error,
      );
      return;
    }
    _itemController.clear();
    showToast(
      context.l10n.workbenchSchemaItemCreated(_entityName(project)),
      type: ToastType.success,
    );
  }

  Future<void> _archiveItem(
    WorkbenchProject project,
    WorkbenchSchemaItem item,
  ) async {
    final api = _archiveApi(project);
    if (api == null) {
      showToast(
        context.l10n.workbenchSchemaMissingArchiveApi,
        type: ToastType.error,
      );
      return;
    }
    final result = await _service.runTool(api.id, {'item_id': item.id});
    if (!mounted) return;
    if (!result.success) {
      showToast(context.l10n.workbenchUnknownTool, type: ToastType.error);
      return;
    }
    showToast(
      context.l10n.workbenchSchemaItemArchived(_entityName(project)),
      type: ToastType.success,
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

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final body = AnimatedBuilder(
      animation: _service,
      builder: (context, _) {
        final project = _service.project;
        return ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
          children: [
            if (_service.loading) ...[
              const LinearProgressIndicator(minHeight: 2),
              const SizedBox(height: 12),
            ],
            _buildHeader(project),
            const SizedBox(height: 12),
            _buildCreateCard(project),
            const SizedBox(height: 12),
            _buildItemsCard(project),
          ],
        );
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
    final description = _schemaString(project, 'description').isEmpty
        ? display.description
        : _schemaString(project, 'description');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          display.title.trim().isEmpty ? project.name : display.title,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 24,
            fontWeight: FontWeight.w800,
          ),
        ),
        if (description.trim().isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(
            description,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 14,
              height: 1.35,
            ),
          ),
        ],
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _buildPill(
              Icons.dataset_outlined,
              context.l10n.workbenchSchemaItemCount(
                project.activeItems.length,
                project.archivedItems.length,
              ),
            ),
            _buildPill(Icons.widgets_rounded, context.l10n.workbenchNativeUi),
            _buildPill(
              Icons.api_rounded,
              context.l10n.workbenchApiCount(project.tools.length),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildCreateCard(WorkbenchProject project) {
    return _buildSectionCard(
      title: context.l10n.workbenchSchemaCreateTitle(_entityName(project)),
      icon: Icons.add_box_outlined,
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _itemController,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _createItem(project),
              decoration: InputDecoration(
                hintText: context.l10n.workbenchSchemaInputHint(
                  _entityName(project),
                ),
                filled: true,
                fillColor: context.omniPalette.surfaceSecondary,
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
            onPressed: () => _createItem(project),
            tooltip: context.l10n.workbenchSchemaCreateTitle(
              _entityName(project),
            ),
            icon: const Icon(Icons.add_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildItemsCard(WorkbenchProject project) {
    final items = project.items;
    return _buildSectionCard(
      title: context.l10n.workbenchSchemaItemsTitle(_entityName(project)),
      icon: Icons.view_list_rounded,
      child: items.isEmpty
          ? _buildEmpty(project)
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (project.activeItems.isNotEmpty) ...[
                  _buildGroupLabel(context.l10n.workbenchSchemaActive),
                  const SizedBox(height: 8),
                  ...project.activeItems.map(
                    (item) => _buildActiveItemTile(project, item),
                  ),
                ],
                if (project.archivedItems.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  _buildGroupLabel(context.l10n.workbenchSchemaArchived),
                  const SizedBox(height: 8),
                  ...project.archivedItems.map(_buildArchivedItemTile),
                ],
              ],
            ),
    );
  }

  Widget _buildActiveItemTile(
    WorkbenchProject project,
    WorkbenchSchemaItem item,
  ) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: ListTile(
        leading: Icon(
          Icons.radio_button_unchecked,
          color: palette.textTertiary,
        ),
        title: Text(
          item.title,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w700,
          ),
        ),
        subtitle: _buildFieldSummary(item),
        trailing: IconButton(
          tooltip: context.l10n.workbenchSchemaArchiveAction,
          onPressed: () => _archiveItem(project, item),
          icon: const Icon(Icons.archive_outlined),
        ),
      ),
    );
  }

  Widget _buildArchivedItemTile(WorkbenchSchemaItem item) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(8),
      ),
      child: ListTile(
        leading: Icon(Icons.archive_rounded, color: palette.textTertiary),
        title: Text(
          item.title,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            decoration: TextDecoration.lineThrough,
          ),
        ),
        subtitle: Text(
          context.l10n.workbenchSchemaArchived,
          style: TextStyle(color: palette.textTertiary, fontSize: 12),
        ),
      ),
    );
  }

  Widget _buildFieldSummary(WorkbenchSchemaItem item) {
    final visibleFields = item.fields.entries
        .where((entry) => entry.value?.toString().trim().isNotEmpty == true)
        .take(2)
        .map((entry) => '${entry.key}: ${entry.value}')
        .join(' · ');
    return Text(
      visibleFields.isEmpty
          ? context.l10n.workbenchSchemaActive
          : visibleFields,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(color: context.omniPalette.textSecondary, fontSize: 12),
    );
  }

  Widget _buildEmpty(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        context.l10n.workbenchSchemaEmpty(_entityName(project)),
        textAlign: TextAlign.center,
        style: TextStyle(color: palette.textSecondary, fontSize: 13),
      ),
    );
  }

  Widget _buildSectionCard({
    required String title,
    required IconData icon,
    required Widget child,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: palette.accentPrimary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          child,
        ],
      ),
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
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGroupLabel(String label) {
    return Text(
      label,
      style: TextStyle(
        color: context.omniPalette.textSecondary,
        fontSize: 12,
        fontWeight: FontWeight.w700,
      ),
    );
  }

  WorkbenchToolSpec? _createApi(WorkbenchProject project) {
    for (final tool in project.tools) {
      final id = tool.id.toLowerCase();
      if (id.endsWith('.create') || id.endsWith('.add')) return tool;
    }
    return null;
  }

  WorkbenchToolSpec? _archiveApi(WorkbenchProject project) {
    for (final tool in project.tools) {
      final id = tool.id.toLowerCase();
      if (id.endsWith('.archive') ||
          id.endsWith('.finish') ||
          id.endsWith('.complete')) {
        return tool;
      }
    }
    return null;
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
    return label.isEmpty ? project.name : label;
  }

  String _entityName(WorkbenchProject project) {
    final entity = _schemaString(project, 'entityName');
    return entity.isEmpty ? context.l10n.workbenchSchemaDefaultEntity : entity;
  }

  String _schemaString(WorkbenchProject project, String key) {
    return project.schema[key]?.toString().trim() ?? '';
  }
}
