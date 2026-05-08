import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchTodoLogPage extends StatefulWidget {
  const WorkbenchTodoLogPage({
    super.key,
    WorkbenchTodoLogService? service,
    String? projectId,
  }) : _service = service,
       _projectId = projectId;

  final WorkbenchTodoLogService? _service;
  final String? _projectId;

  @override
  State<WorkbenchTodoLogPage> createState() => _WorkbenchTodoLogPageState();
}

class _WorkbenchTodoLogPageState extends State<WorkbenchTodoLogPage> {
  late final WorkbenchTodoLogService _service;
  final TextEditingController _todoController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _service =
        widget._service ??
        WorkbenchTodoLogService.native(projectId: widget._projectId);
    _service.initialize();
  }

  @override
  void dispose() {
    _todoController.dispose();
    super.dispose();
  }

  Future<void> _addTodo() async {
    final title = _todoController.text.trim();
    final result = await _service.runTool(WorkbenchTodoToolIds.addTodo, {
      'title': title,
    });
    if (!mounted) {
      return;
    }
    if (!result.success) {
      showToast(context.l10n.workbenchTodoInputRequired, type: ToastType.error);
      return;
    }
    _todoController.clear();
    showToast(context.l10n.workbenchTodoAdded, type: ToastType.success);
  }

  Future<void> _finishTodo(WorkbenchTodoItem todo) async {
    final result = await _service.runTool(WorkbenchTodoToolIds.finishTodo, {
      'todo_id': todo.id,
    });
    if (!mounted) {
      return;
    }
    if (!result.success) {
      showToast(context.l10n.workbenchUnknownTool, type: ToastType.error);
      return;
    }
    showToast(context.l10n.workbenchTodoFinishedToast, type: ToastType.success);
  }

  void _handleBackNavigation() {
    if (context.canPop()) {
      context.pop();
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) {
          return;
        }
        _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: context.l10n.workbenchGeneratedFrontend,
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(
          child: AnimatedBuilder(
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
                  _buildTodoCard(project),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildHeader(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          context.l10n.workbenchTodoLog,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 24,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          context.l10n.workbenchGeneratedTodoSubtitle,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            height: 1.35,
          ),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _buildPill(
              Icons.folder_open_rounded,
              context.l10n.workbenchTodoCount(
                project.openTodos.length,
                project.finishedTodos.length,
              ),
            ),
            _buildPill(Icons.widgets_rounded, context.l10n.workbenchNativeUi),
            if (_service.errorMessage != null)
              _buildPill(
                Icons.error_outline_rounded,
                context.l10n.workbenchLoadFailed,
              ),
          ],
        ),
      ],
    );
  }

  Widget _buildTodoCard(WorkbenchProject project) {
    return _buildSectionCard(
      title: context.l10n.workbenchTodoLog,
      icon: Icons.checklist_rounded,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _todoController,
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _addTodo(),
                  decoration: InputDecoration(
                    hintText: context.l10n.workbenchAddTodoHint,
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
                onPressed: _addTodo,
                tooltip: context.l10n.workbenchAddTodo,
                icon: const Icon(Icons.add_rounded),
              ),
            ],
          ),
          const SizedBox(height: 14),
          if (project.todos.isEmpty)
            _buildEmptyTodos()
          else ...[
            if (project.openTodos.isNotEmpty) ...[
              _buildTodoGroupLabel(context.l10n.workbenchTodoOpen),
              const SizedBox(height: 8),
              ...project.openTodos.map(_buildOpenTodoTile),
            ],
            if (project.finishedTodos.isNotEmpty) ...[
              const SizedBox(height: 12),
              _buildTodoGroupLabel(context.l10n.workbenchTodoFinished),
              const SizedBox(height: 8),
              ...project.finishedTodos.map(_buildFinishedTodoTile),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildOpenTodoTile(WorkbenchTodoItem todo) {
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
          todo.title,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
        ),
        subtitle: Text(
          context.l10n.workbenchStatusOpen,
          style: TextStyle(color: palette.textSecondary, fontSize: 12),
        ),
        trailing: IconButton(
          tooltip: context.l10n.workbenchFinishTodo,
          onPressed: () => _finishTodo(todo),
          icon: const Icon(Icons.check_circle_outline_rounded),
        ),
      ),
    );
  }

  Widget _buildFinishedTodoTile(WorkbenchTodoItem todo) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(8),
      ),
      child: ListTile(
        leading: const Icon(
          Icons.check_circle_rounded,
          color: Color(0xFF16A34A),
        ),
        title: Text(
          todo.title,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            decoration: TextDecoration.lineThrough,
          ),
        ),
        subtitle: Text(
          context.l10n.workbenchStatusFinished,
          style: TextStyle(color: palette.textTertiary, fontSize: 12),
        ),
      ),
    );
  }

  Widget _buildEmptyTodos() {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        context.l10n.workbenchTodoEmpty,
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
              Text(
                title,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
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

  Widget _buildTodoGroupLabel(String label) {
    return Text(
      label,
      style: TextStyle(
        color: context.omniPalette.textSecondary,
        fontSize: 12,
        fontWeight: FontWeight.w700,
      ),
    );
  }
}
