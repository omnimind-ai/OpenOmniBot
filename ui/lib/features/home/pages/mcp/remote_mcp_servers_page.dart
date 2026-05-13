import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/remote_mcp_server.dart';
import 'package:ui/services/remote_mcp_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class RemoteMcpServersPage extends StatefulWidget {
  const RemoteMcpServersPage({super.key});

  @override
  State<RemoteMcpServersPage> createState() => _RemoteMcpServersPageState();
}

class _RemoteMcpServersPageState extends State<RemoteMcpServersPage> {
  bool _loading = true;
  final Set<String> _busyIds = {};
  List<RemoteMcpServer> _servers = [];

  @override
  void initState() {
    super.initState();
    _loadServers();
  }

  String _text(String text) {
    return AppTextLocalizer.text(text, locale: Localizations.localeOf(context));
  }

  String _choose({required String zh, required String en}) {
    return AppTextLocalizer.choose(
      zh: zh,
      en: en,
      locale: Localizations.localeOf(context),
    );
  }

  Future<void> _loadServers() async {
    setState(() => _loading = true);
    try {
      final servers = await RemoteMcpConfigService.listServers();
      if (!mounted) return;
      setState(() {
        _servers = servers;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast(
        _choose(zh: '加载 MCP 工具失败', en: 'Failed to load MCP tools'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _toggleServer(RemoteMcpServer server, bool enabled) async {
    _setBusy(server.id, true);
    try {
      final updated = await RemoteMcpConfigService.setServerEnabled(
        server.id,
        enabled,
      );
      if (!mounted) return;
      setState(() {
        _servers = _servers.map((item) {
          if (item.id != server.id) return item;
          return updated ?? item.copyWith(enabled: enabled);
        }).toList();
      });
    } catch (e) {
      showToast(_text('切换失败'), type: ToastType.error);
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _refreshTools(RemoteMcpServer server) async {
    _setBusy(server.id, true);
    try {
      final updated = await RemoteMcpConfigService.refreshServerTools(
        server.id,
      );
      if (!mounted) return;
      setState(() {
        _servers = _servers.map((item) {
          if (item.id != server.id) return item;
          return updated ?? item;
        }).toList();
      });
      showToast(_choose(zh: '工具列表已刷新', en: 'Tool list refreshed'));
    } on PlatformException catch (e) {
      showToast(e.message ?? _text('刷新失败'), type: ToastType.error);
    } catch (_) {
      showToast(_text('刷新失败'), type: ToastType.error);
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _deleteServer(RemoteMcpServer server) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final locale = Localizations.localeOf(context);
        return AlertDialog(
          title: Text(
            AppTextLocalizer.choose(
              zh: '删除 MCP 服务',
              en: 'Delete MCP Service',
              locale: locale,
            ),
          ),
          content: Text(
            AppTextLocalizer.choose(
              zh: '确认删除“${server.name}”？',
              en: 'Confirm deleting "${server.name}"?',
              locale: locale,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(AppTextLocalizer.text('取消', locale: locale)),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(AppTextLocalizer.text('删除', locale: locale)),
            ),
          ],
        );
      },
    );
    if (confirmed != true) return;

    _setBusy(server.id, true);
    try {
      await RemoteMcpConfigService.deleteServer(server.id);
      if (!mounted) return;
      setState(() {
        _servers.removeWhere((item) => item.id == server.id);
      });
      showToast(_text('已删除'));
    } catch (e) {
      showToast(_text('删除失败'), type: ToastType.error);
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _showServerEditor({RemoteMcpServer? server}) async {
    final saved = await showModalBottomSheet<RemoteMcpServer>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.isDarkTheme
          ? context.omniPalette.surfacePrimary
          : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _RemoteMcpServerEditorSheet(server: server),
    );

    if (saved == null) return;
    try {
      final result = await RemoteMcpConfigService.upsertServer(saved);
      if (result == null || !mounted) return;
      setState(() {
        final index = _servers.indexWhere((item) => item.id == result.id);
        if (index == -1) {
          _servers = [result, ..._servers];
        } else {
          _servers[index] = result;
        }
      });
      showToast(_choose(zh: '已保存', en: 'Saved'));
    } catch (e) {
      showToast(_text('保存失败'), type: ToastType.error);
    }
  }

  void _setBusy(String id, bool busy) {
    if (!mounted) return;
    setState(() {
      if (busy) {
        _busyIds.add(id);
      } else {
        _busyIds.remove(id);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: context.isDarkTheme
          ? palette.pageBackground
          : AppColors.background,
      appBar: CommonAppBar(title: _text('MCP 工具'), primary: true),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _showServerEditor(),
        child: const Icon(Icons.add),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _servers.isEmpty
          ? _buildEmpty()
          : RefreshIndicator(
              onRefresh: _loadServers,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
                children: [
                  SettingsSectionTitle(
                    label: _choose(zh: '远端服务', en: 'Remote Services'),
                  ),
                  for (int index = 0; index < _servers.length; index++) ...[
                    _buildServerCard(_servers[index]),
                    if (index != _servers.length - 1) const Divider(height: 24),
                  ],
                ],
              ),
            ),
    );
  }

  Widget _buildEmpty() {
    final palette = context.omniPalette;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const SizedBox(height: 120),
        Icon(
          Icons.extension,
          size: 48,
          color: context.isDarkTheme ? palette.textTertiary : AppColors.text50,
        ),
        const SizedBox(height: 12),
        Center(
          child: Text(
            _choose(zh: '暂无远端 MCP 服务', en: 'No remote MCP services'),
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildServerCard(RemoteMcpServer server) {
    final busy = _busyIds.contains(server.id);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    server.name,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    server.endpointUrl,
                    style: TextStyle(
                      fontSize: 12,
                      color: context.omniPalette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Switch(
              value: server.enabled,
              onChanged: busy ? null : (value) => _toggleServer(server, value),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _MetaChip(label: _healthLabel(server.lastHealth)),
            _MetaChip(
              label: _choose(
                zh: '工具 ${server.toolCount}',
                en: 'Tools ${server.toolCount}',
              ),
            ),
            if ((server.lastError ?? '').isNotEmpty)
              _MetaChip(
                label: server.lastError!,
                color: AppColors.alertRed.withValues(alpha: 0.08),
                textColor: AppColors.alertRed,
              ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            TextButton(
              onPressed: busy ? null : () => _refreshTools(server),
              child: Text(_choose(zh: '刷新工具', en: 'Refresh tools')),
            ),
            TextButton(
              onPressed: busy ? null : () => _showServerEditor(server: server),
              child: Text(_text('编辑')),
            ),
            const Spacer(),
            TextButton(
              onPressed: busy ? null : () => _deleteServer(server),
              child: Text(_text('删除')),
            ),
          ],
        ),
      ],
    );
  }

  String _healthLabel(String health) {
    switch (health) {
      case 'healthy':
        return _choose(zh: '连接正常', en: 'Connected');
      case 'error':
        return _choose(zh: '连接异常', en: 'Connection error');
      default:
        return _choose(zh: '状态未知', en: 'Unknown');
    }
  }
}

class _InputField extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String label;
  final String? hint;

  const _InputField({
    required this.controller,
    this.focusNode,
    required this.label,
    this.hint,
  });

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      focusNode: focusNode,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }
}

class _RemoteMcpServerEditorSheet extends StatefulWidget {
  const _RemoteMcpServerEditorSheet({this.server});

  final RemoteMcpServer? server;

  @override
  State<_RemoteMcpServerEditorSheet> createState() =>
      _RemoteMcpServerEditorSheetState();
}

class _RemoteMcpServerEditorSheetState
    extends State<_RemoteMcpServerEditorSheet> {
  late final TextEditingController _nameController;
  late final TextEditingController _endpointController;
  late final TextEditingController _tokenController;
  final FocusNode _nameFocusNode = FocusNode();
  late bool _enabled;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.server?.name ?? '');
    _endpointController = TextEditingController(
      text: widget.server?.endpointUrl ?? '',
    );
    _tokenController = TextEditingController(
      text: widget.server?.bearerToken ?? '',
    );
    _enabled = widget.server?.enabled ?? true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _nameFocusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _nameFocusNode.dispose();
    _nameController.dispose();
    _endpointController.dispose();
    _tokenController.dispose();
    super.dispose();
  }

  void _close([RemoteMcpServer? value]) {
    FocusScope.of(context).unfocus();
    Navigator.of(context).pop(value);
  }

  String _text(String text) {
    return AppTextLocalizer.text(text, locale: Localizations.localeOf(context));
  }

  String _choose({required String zh, required String en}) {
    return AppTextLocalizer.choose(
      zh: zh,
      en: en,
      locale: Localizations.localeOf(context),
    );
  }

  void _submit() {
    final name = _nameController.text.trim();
    final endpoint = _endpointController.text.trim();
    if (name.isEmpty || endpoint.isEmpty) {
      showToast(
        _choose(zh: '请填写名称和地址', en: 'Please enter both name and address'),
        type: ToastType.error,
      );
      return;
    }
    _close(
      (widget.server ??
              const RemoteMcpServer(
                id: '',
                name: '',
                endpointUrl: '',
                bearerToken: '',
                enabled: true,
                lastHealth: 'unknown',
                toolCount: 0,
              ))
          .copyWith(
            name: name,
            endpointUrl: endpoint,
            bearerToken: _tokenController.text.trim(),
            enabled: _enabled,
          ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.server == null
                ? _choose(zh: '添加 MCP 服务', en: 'Add MCP Service')
                : _choose(zh: '编辑 MCP 服务', en: 'Edit MCP Service'),
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 16),
          _InputField(
            controller: _nameController,
            focusNode: _nameFocusNode,
            label: _text('名称'),
          ),
          const SizedBox(height: 12),
          _InputField(
            controller: _endpointController,
            label: 'Endpoint URL',
            hint: 'https://example.com/mcp',
          ),
          const SizedBox(height: 12),
          _InputField(
            controller: _tokenController,
            label: _choose(
              zh: 'Bearer Token（可选）',
              en: 'Bearer Token (Optional)',
            ),
          ),
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: _enabled,
            onChanged: (value) => setState(() => _enabled = value),
            title: Text(_choose(zh: '启用', en: 'Enabled')),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(onPressed: _submit, child: Text(_text('保存'))),
          ),
        ],
      ),
    );
  }
}

class _MetaChip extends StatelessWidget {
  final String label;
  final Color? color;
  final Color? textColor;

  const _MetaChip({required this.label, this.color, this.textColor});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color:
            color ??
            (context.isDarkTheme
                ? palette.surfaceSecondary
                : AppColors.background),
        borderRadius: BorderRadius.circular(999),
        border: color == null && context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color:
              textColor ??
              (context.isDarkTheme ? palette.textSecondary : AppColors.text70),
        ),
      ),
    );
  }
}
