import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';

enum _MarkdownDisplayMode { preview, edit, split }

class WorkbenchMarkdownDisplayPage extends StatefulWidget {
  const WorkbenchMarkdownDisplayPage({
    super.key,
    WorkbenchProjectService? service,
    WorkbenchProject? initialProject,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool embedded = false,
  }) : _service = service,
       _initialProject = initialProject,
       _projectId = projectId,
       _displayId = displayId,
       _returnTo = returnTo,
       _embedded = embedded;

  final WorkbenchProjectService? _service;
  final WorkbenchProject? _initialProject;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _embedded;

  @override
  State<WorkbenchMarkdownDisplayPage> createState() =>
      _WorkbenchMarkdownDisplayPageState();
}

class _WorkbenchMarkdownDisplayPageState
    extends State<WorkbenchMarkdownDisplayPage> {
  late final WorkbenchProjectService _service;
  late final bool _ownsService = widget._service == null;
  final TextEditingController _controller = TextEditingController();
  _MarkdownDisplayMode _mode = _MarkdownDisplayMode.preview;
  String _entryFile = 'index.md';
  String _originalText = '';
  String _loadedSignature = '';
  bool _dirty = false;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final projectId = widget._projectId?.trim().isNotEmpty == true
        ? widget._projectId!.trim()
        : widget._initialProject?.projectId ?? '';
    _service =
        widget._service ??
        WorkbenchProjectService(
          backend: NativeWorkbenchProjectBackend(),
          projectId: projectId,
          initialProject: widget._initialProject,
          autoCreateIfMissing: false,
        );
    _controller.addListener(_handleTextChanged);
    _service.initialize();
  }

  @override
  void dispose() {
    _controller.removeListener(_handleTextChanged);
    _controller.dispose();
    if (_ownsService) {
      _service.dispose();
    }
    super.dispose();
  }

  void _handleTextChanged() {
    final nextDirty = _controller.text != _originalText;
    final needsPreviewRefresh = _mode == _MarkdownDisplayMode.split;
    if (nextDirty == _dirty && !needsPreviewRefresh) return;
    setState(() => _dirty = nextDirty);
  }

  void _syncProjectMarkdown(WorkbenchProject project) {
    final payload = project.frontendMarkdown;
    final sources = payload['sources'];
    final sourceMap = sources is Map ? Map<String, Object?>.from(sources) : {};
    final requestedEntryFile = widget._displayId?.trim() ?? '';
    final entryFile =
        (requestedEntryFile.isNotEmpty &&
                sourceMap.containsKey(requestedEntryFile)
            ? requestedEntryFile
            : null) ??
        payload['entryFile']?.toString().trim().takeIfNotEmpty ??
        (sourceMap.containsKey('index.md')
            ? 'index.md'
            : (sourceMap.keys.isNotEmpty ? sourceMap.keys.first : 'index.md'));
    final text = sourceMap[entryFile]?.toString() ?? '';
    final signature = '${project.projectId}:$entryFile:${text.hashCode}';
    if (_dirty || signature == _loadedSignature) return;
    _entryFile = entryFile;
    _originalText = text;
    _loadedSignature = signature;
    _controller.text = text;
  }

  Future<void> _save(WorkbenchProject project) async {
    if (_saving || !_dirty) return;
    setState(() => _saving = true);
    final updated = await _service.updateProjectMetadata(
      project,
      markdownFiles: [
        {'path': _entryFile, 'content': _controller.text},
      ],
    );
    if (!mounted) return;
    setState(() => _saving = false);
    if (updated == null) {
      showToast(_t('保存失败', 'Save failed'), type: ToastType.error);
      return;
    }
    _originalText = _controller.text;
    _dirty = false;
    showToast(_t('已保存 Markdown', 'Markdown saved'), type: ToastType.success);
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
        if (project == null) {
          return _buildStatus(
            _service.errorMessage?.trim().isNotEmpty == true
                ? _service.errorMessage!
                : _t('加载中...', 'Loading...'),
          );
        }
        _syncProjectMarkdown(project);
        return Column(
          children: [
            if (_service.loading || _saving)
              const LinearProgressIndicator(minHeight: 2),
            _buildToolbar(project),
            Expanded(child: _buildBody()),
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
          title: _service.project?.name ?? _t('Markdown', 'Markdown'),
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(child: body),
      ),
    );
  }

  Widget _buildToolbar(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        border: Border(bottom: BorderSide(color: palette.borderSubtle)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              _entryFile,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 8),
          SegmentedButton<_MarkdownDisplayMode>(
            segments: [
              ButtonSegment(
                value: _MarkdownDisplayMode.preview,
                icon: const Icon(Icons.visibility_outlined, size: 17),
                tooltip: _t('预览', 'Preview'),
              ),
              ButtonSegment(
                value: _MarkdownDisplayMode.edit,
                icon: const Icon(Icons.edit_outlined, size: 17),
                tooltip: _t('编辑', 'Edit'),
              ),
              ButtonSegment(
                value: _MarkdownDisplayMode.split,
                icon: const Icon(Icons.vertical_split_outlined, size: 17),
                tooltip: _t('分屏', 'Split'),
              ),
            ],
            selected: {_mode},
            showSelectedIcon: false,
            onSelectionChanged: (value) {
              setState(() => _mode = value.first);
            },
            style: const ButtonStyle(
              visualDensity: VisualDensity.compact,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
          ),
          const SizedBox(width: 8),
          IconButton.filled(
            tooltip: _dirty ? _t('保存', 'Save') : _t('无改动', 'No changes'),
            onPressed: _dirty && !_saving ? () => _save(project) : null,
            constraints: const BoxConstraints.tightFor(width: 40, height: 40),
            padding: EdgeInsets.zero,
            icon: _saving
                ? const SizedBox(
                    width: 17,
                    height: 17,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.save_outlined, size: 19),
          ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_controller.text.isEmpty && !_dirty) {
      return _buildStatus(_t('暂无 Markdown 内容', 'No Markdown content'));
    }
    return switch (_mode) {
      _MarkdownDisplayMode.preview => _buildPreview(),
      _MarkdownDisplayMode.edit => _buildEditor(),
      _MarkdownDisplayMode.split => LayoutBuilder(
        builder: (context, constraints) {
          final wide = constraints.maxWidth >= 720;
          final children = [
            Expanded(child: _buildEditor()),
            wide
                ? VerticalDivider(
                    width: 1,
                    thickness: 1,
                    color: context.omniPalette.borderSubtle,
                  )
                : Divider(height: 1, color: context.omniPalette.borderSubtle),
            Expanded(child: _buildPreview()),
          ];
          return wide ? Row(children: children) : Column(children: children);
        },
      ),
    };
  }

  Widget _buildEditor() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.all(10),
      child: TextField(
        controller: _controller,
        expands: true,
        maxLines: null,
        minLines: null,
        keyboardType: TextInputType.multiline,
        textAlignVertical: TextAlignVertical.top,
        decoration: InputDecoration(
          filled: true,
          fillColor: palette.surfaceSecondary,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide(color: palette.borderSubtle),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide(color: palette.borderSubtle),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide(color: palette.accentPrimary),
          ),
          contentPadding: const EdgeInsets.all(12),
        ),
        style: TextStyle(
          color: palette.textPrimary,
          fontFamily: 'monospace',
          fontSize: 13,
          height: 1.45,
        ),
      ),
    );
  }

  Widget _buildPreview() {
    final palette = context.omniPalette;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 28),
      child: OmnibotMarkdownBody(
        data: _controller.text,
        baseStyle: TextStyle(
          color: palette.textPrimary,
          fontSize: 14,
          height: 1.5,
        ),
        selectable: true,
      ),
    );
  }

  Widget _buildStatus(String message) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Text(
          message,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: context.omniPalette.textSecondary,
            fontSize: 13,
          ),
        ),
      ),
    );
  }

  String _t(String zh, String en) {
    return Localizations.localeOf(context).languageCode == 'en' ? en : zh;
  }
}

extension _NullableStringTrim on String? {
  String? get takeIfNotEmpty {
    final value = this?.trim();
    return value == null || value.isEmpty ? null : value;
  }
}
