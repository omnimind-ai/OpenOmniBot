import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/quick_log_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

class QuickLogsPage extends StatefulWidget {
  const QuickLogsPage({super.key});

  @override
  State<QuickLogsPage> createState() => _QuickLogsPageState();
}

class _QuickLogsPageState extends State<QuickLogsPage> {
  final TextEditingController _composerController = TextEditingController();
  bool _isLoading = true;
  bool _isSaving = false;
  List<QuickLogItem> _items = const [];
  int _totalCount = 0;

  bool get _isEnglish => LegacyTextLocalizer.isEnglish;

  String _t(String zh, String en) => _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    _loadLogs();
  }

  @override
  void dispose() {
    _composerController.dispose();
    super.dispose();
  }

  Future<void> _loadLogs({bool silent = false}) async {
    if (!silent) {
      setState(() {
        _isLoading = true;
      });
    }
    try {
      final snapshot = await QuickLogService.listLogs();
      if (!mounted) return;
      setState(() {
        _items = snapshot.items;
        _totalCount = snapshot.totalCount;
        _isLoading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
      });
      _showMessage(
        _t('\u52a0\u8f7d\u65e5\u5fd7\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', 'Failed to load logs. Please try again.'),
      );
    }
  }

  Future<void> _handleAddLog() async {
    final content = _composerController.text.trim();
    if (content.isEmpty) {
      _showMessage(
        _t('\u5148\u5199\u70b9\u5185\u5bb9\u518d\u4fdd\u5b58\u5427', 'Write something before saving.'),
      );
      return;
    }
    setState(() {
      _isSaving = true;
    });
    try {
      await QuickLogService.addLog(content);
      _composerController.clear();
      await _loadLogs(silent: true);
      if (!mounted) return;
      _showMessage(
        _t(
          '\u65e5\u5fd7\u5df2\u4fdd\u5b58\uff0c\u5e76\u540c\u6b65\u5230\u77ed\u671f\u8bb0\u5fc6',
          'Saved and synced to short memories.',
        ),
      );
    } catch (_) {
      if (!mounted) return;
      _showMessage(
        _t('\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', 'Save failed. Please try again.'),
      );
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }

  Future<void> _handleEditLog(QuickLogItem item) async {
    final controller = TextEditingController(text: item.content);
    final nextContent = await showDialog<String>(
      context: context,
      builder: (context) {
        final palette = context.omniPalette;
        return AlertDialog(
          title: Text(_t('\u7f16\u8f91\u65e5\u5fd7', 'Edit log')),
          content: TextField(
            controller: controller,
            maxLines: 6,
            decoration: InputDecoration(
              hintText: _t('\u4fee\u6539\u8fd9\u6761\u65e5\u5fd7', 'Update this log'),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(14),
                borderSide: BorderSide(color: palette.accentPrimary),
              ),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(_t('\u53d6\u6d88', 'Cancel')),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(controller.text.trim()),
              child: Text(_t('\u4fdd\u5b58', 'Save')),
            ),
          ],
        );
      },
    );
    controller.dispose();
    if (!mounted || nextContent == null || nextContent.isEmpty) {
      return;
    }
    try {
      await QuickLogService.updateLog(item.id, nextContent);
      await _loadLogs(silent: true);
      if (!mounted) return;
      _showMessage(_t('\u65e5\u5fd7\u5df2\u66f4\u65b0', 'Log updated.'));
    } catch (_) {
      if (!mounted) return;
      _showMessage(
        _t('\u66f4\u65b0\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', 'Update failed. Please try again.'),
      );
    }
  }

  Future<void> _handleDeleteLog(QuickLogItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(_t('\u5220\u9664\u8fd9\u6761\u65e5\u5fd7\uff1f', 'Delete this log?')),
          content: Text(
            _t(
              '\u5df2\u540c\u6b65\u5230\u77ed\u671f\u8bb0\u5fc6\u7684\u5185\u5bb9\u4e0d\u4f1a\u56de\u6eda\uff0c\u4f46\u8fd9\u6761\u65e5\u5fd7\u4f1a\u4ece\u5217\u8868\u79fb\u9664\u3002',
              'Synced short memories stay as-is, but this log will be removed from the list.',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(_t('\u53d6\u6d88', 'Cancel')),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(_t('\u5220\u9664', 'Delete')),
            ),
          ],
        );
      },
    );
    if (confirmed != true) {
      return;
    }
    try {
      await QuickLogService.deleteLog(item.id);
      await _loadLogs(silent: true);
      if (!mounted) return;
      _showMessage(_t('\u65e5\u5fd7\u5df2\u5220\u9664', 'Log deleted.'));
    } catch (_) {
      if (!mounted) return;
      _showMessage(
        _t('\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5', 'Delete failed. Please try again.'),
      );
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  String _formatTime(int millis) {
    return DateFormat('yyyy-MM-dd HH:mm').format(
      DateTime.fromMillisecondsSinceEpoch(millis),
    );
  }

  String _sourceLabel(String source) {
    return source == 'widget'
        ? _t('\u684c\u9762\u5c0f\u7ec4\u4ef6', 'Home widget')
        : _t('\u5e94\u7528\u5185', 'In app');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final topPadding = MediaQuery.paddingOf(context).top;
    final surfaceColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : Colors.white;
    final borderColor = palette.borderSubtle.withValues(alpha: 0.7);

    return Scaffold(
      backgroundColor: context.isDarkTheme
          ? palette.pageBackground
          : const Color(0xFFF4F7FB),
      appBar: PreferredSize(
        preferredSize: Size.fromHeight(44 + topPadding),
        child: CommonAppBar(
          title: _t('\u65e5\u5fd7\u8bb0\u5f55', 'Quick Logs'),
          primary: true,
        ),
      ),
      body: RefreshIndicator(
        onRefresh: _loadLogs,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: surfaceColor,
                borderRadius: BorderRadius.circular(24),
                border: Border.all(color: borderColor),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          color: const Color(0xFF0EA5E9).withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: const Icon(
                          Icons.edit_note_rounded,
                          color: Color(0xFF0284C7),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _t('\u968f\u624b\u8bb0\u4e00\u6761', 'Capture a quick note'),
                              style: TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w700,
                                color: palette.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _t(
                                '\u65b0\u589e\u65e5\u5fd7\u4f1a\u81ea\u52a8\u540c\u6b65\u5230\u77ed\u671f\u8bb0\u5fc6\uff0c\u65b9\u4fbf\u5c0f\u4e07\u5728\u540e\u7eed\u4f1a\u8bdd\u91cc\u8bb0\u4f4f\u4eca\u5929\u53d1\u751f\u7684\u4e8b\u3002',
                                'New logs sync into short memories so Omnibot can recall them later.',
                              ),
                              style: TextStyle(
                                fontSize: 13,
                                color: palette.textSecondary,
                                height: 1.45,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: _composerController,
                    minLines: 3,
                    maxLines: 6,
                    decoration: InputDecoration(
                      hintText: _t(
                        '\u5199\u4e0b\u5f85\u529e\u3001\u7075\u611f\u3001\u4fbf\u7b7e\uff0c\u6216\u8005\u4e00\u53e5\u4eca\u5929\u60f3\u8bb0\u4f4f\u7684\u8bdd',
                        'Write a task, idea, note, or one thing you want remembered today',
                      ),
                      filled: true,
                      fillColor: context.isDarkTheme
                          ? palette.surfacePrimary
                          : const Color(0xFFF8FBFF),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: BorderSide(color: borderColor),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: BorderSide(color: borderColor),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: BorderSide(
                          color: palette.accentPrimary,
                          width: 1.4,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Text(
                        _t('\u5171 $_totalCount \u6761', '$_totalCount logs'),
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: palette.textSecondary,
                        ),
                      ),
                      const Spacer(),
                      FilledButton.icon(
                        onPressed: _isSaving ? null : _handleAddLog,
                        icon: _isSaving
                            ? SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white.withValues(alpha: 0.95),
                                ),
                              )
                            : const Icon(Icons.add_rounded),
                        label: Text(_t('\u4fdd\u5b58\u65e5\u5fd7', 'Save log')),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            if (_isLoading)
              const Padding(
                padding: EdgeInsets.only(top: 48),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_items.isEmpty)
              Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: surfaceColor,
                  borderRadius: BorderRadius.circular(24),
                  border: Border.all(color: borderColor),
                ),
                child: Column(
                  children: [
                    Icon(
                      Icons.sticky_note_2_outlined,
                      size: 40,
                      color: palette.textSecondary,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _t('\u8fd8\u6ca1\u6709\u65e5\u5fd7', 'No logs yet'),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _t(
                        '\u4f60\u53ef\u4ee5\u5728\u8fd9\u91cc\u968f\u624b\u8bb0\uff0c\u4e5f\u53ef\u4ee5\u4ece\u684c\u9762\u5c0f\u7ec4\u4ef6\u5feb\u901f\u8bb0\u5f55\u3002',
                        'You can write here or capture from the home widget.',
                      ),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 13,
                        color: palette.textSecondary,
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              )
            else
              ..._items.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _QuickLogCard(
                    item: item,
                    title: _formatTime(item.updatedAtMillis),
                    sourceLabel: _sourceLabel(item.source),
                    syncedLabel: item.shortMemorySynced
                        ? _t(
                            '\u5df2\u540c\u6b65\u5230\u77ed\u671f\u8bb0\u5fc6',
                            'Synced to short memories',
                          )
                        : _t(
                            '\u672a\u540c\u6b65\u8bb0\u5fc6',
                            'Memory sync unavailable',
                          ),
                    synced: item.shortMemorySynced,
                    onEdit: () => _handleEditLog(item),
                    onDelete: () => _handleDeleteLog(item),
                    resolve: _t,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _QuickLogCard extends StatelessWidget {
  const _QuickLogCard({
    required this.item,
    required this.title,
    required this.sourceLabel,
    required this.syncedLabel,
    required this.synced,
    required this.onEdit,
    required this.onDelete,
    required this.resolve,
  });

  final QuickLogItem item;
  final String title;
  final String sourceLabel;
  final String syncedLabel;
  final bool synced;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final String Function(String zh, String en) resolve;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final borderColor = palette.borderSubtle.withValues(alpha: 0.7);
    final surfaceColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : Colors.white;

    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: borderColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textSecondary,
                  ),
                ),
              ),
              PopupMenuButton<String>(
                onSelected: (value) {
                  if (value == 'edit') {
                    onEdit();
                  } else if (value == 'delete') {
                    onDelete();
                  }
                },
                itemBuilder: (context) => [
                  PopupMenuItem<String>(
                    value: 'edit',
                    child: Text(resolve('\u7f16\u8f91', 'Edit')),
                  ),
                  PopupMenuItem<String>(
                    value: 'delete',
                    child: Text(resolve('\u5220\u9664', 'Delete')),
                  ),
                ],
                child: Icon(
                  Icons.more_horiz_rounded,
                  color: palette.textSecondary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            item.content,
            style: TextStyle(
              fontSize: 15,
              height: 1.6,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _QuickLogTag(
                label: sourceLabel,
                backgroundColor: const Color(0xFF0EA5E9).withValues(alpha: 0.12),
                textColor: const Color(0xFF0284C7),
              ),
              _QuickLogTag(
                label: syncedLabel,
                backgroundColor: synced
                    ? const Color(0xFF22C55E).withValues(alpha: 0.12)
                    : const Color(0xFFF59E0B).withValues(alpha: 0.15),
                textColor: synced
                    ? const Color(0xFF15803D)
                    : const Color(0xFFB45309),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _QuickLogTag extends StatelessWidget {
  const _QuickLogTag({
    required this.label,
    required this.backgroundColor,
    required this.textColor,
  });

  final String label;
  final Color backgroundColor;
  final Color textColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: textColor,
        ),
      ),
    );
  }
}
