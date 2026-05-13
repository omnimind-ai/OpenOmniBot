import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

class WorkbenchProjectCard extends StatefulWidget {
  const WorkbenchProjectCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<WorkbenchProjectCard> createState() => _WorkbenchProjectCardState();
}

class _WorkbenchProjectCardState extends State<WorkbenchProjectCard> {
  static const _channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  String get _projectId => (widget.cardData['projectId'] ?? '').toString();
  String get _name => (widget.cardData['name'] ?? '').toString();
  String get _description => (widget.cardData['description'] ?? '').toString();
  String get _frontendType =>
      (widget.cardData['frontendType'] ?? 'default').toString();
  String? get _route {
    final directRoute = widget.cardData['route']?.toString().trim();
    if (directRoute != null && directRoute.isNotEmpty) {
      return directRoute;
    }
    final displayRoute = widget.cardData['displayRoute']?.toString().trim();
    if (displayRoute != null && displayRoute.isNotEmpty) {
      return displayRoute;
    }
    if (_projectId.isEmpty) return null;
    return switch (_frontendType) {
      'html' =>
        '/workbench/html?projectId=${Uri.encodeQueryComponent(_projectId)}',
      'markdown' =>
        '/workbench/markdown?projectId=${Uri.encodeQueryComponent(_projectId)}',
      'flutter' =>
        '/workbench/flutter_eval?projectId=${Uri.encodeQueryComponent(_projectId)}',
      _ =>
        '/workbench/project?projectId=${Uri.encodeQueryComponent(_projectId)}',
    };
  }

  List<Map<String, dynamic>> get _apis {
    final raw = widget.cardData['apis'];
    if (raw is! List) return const [];
    return raw.whereType<Map>().map((e) {
      return e.map((k, v) => MapEntry(k.toString(), v));
    }).toList();
  }

  bool _busy = false;

  Future<void> _callApi(String apiId) async {
    if (_busy || _projectId.isEmpty) return;
    setState(() => _busy = true);
    try {
      await _channel.invokeMethod('workbenchApiCall', {
        'projectId': _projectId,
        'apiId': apiId,
        'inputs': <String, dynamic>{},
        'caller': 'ui',
      });
    } on PlatformException catch (e) {
      if (mounted) showToast(e.message ?? '调用失败', type: ToastType.error);
    } catch (e) {
      if (mounted) showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _openProject() {
    if (_projectId.isEmpty) return;
    GoRouterManager.push(_route ?? '/workbench/project?projectId=$_projectId');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      decoration: BoxDecoration(
        color: palette.surfaceElevated,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Header ─────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 0),
            child: Row(
              children: [
                Icon(
                  Icons.widgets_outlined,
                  size: 16,
                  color: palette.accentPrimary,
                ),
                const SizedBox(width: 7),
                Expanded(
                  child: Text(
                    _name.isEmpty ? _projectId : _name,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: palette.textPrimary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 6),
                _FrontendBadge(type: _frontendType),
              ],
            ),
          ),
          // ── Description ────────────────────────────────────────
          if (_description.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 5, 14, 0),
              child: Text(
                _description,
                style: TextStyle(fontSize: 12, color: palette.textSecondary),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          // ── API action chips ────────────────────────────────────
          if (_apis.isNotEmpty) ...[
            const SizedBox(height: 10),
            SizedBox(
              height: 32,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 14),
                itemCount: _apis.length,
                separatorBuilder: (_, __) => const SizedBox(width: 6),
                itemBuilder: (context, i) {
                  final api = _apis[i];
                  final label = (api['displayName'] ?? api['toolId'] ?? '')
                      .toString();
                  final hasInputs = api['hasInputs'] == true;
                  final toolId = (api['toolId'] ?? '').toString();
                  return _ApiChip(
                    label: label,
                    hasInputs: hasInputs,
                    busy: _busy,
                    onTap: hasInputs ? _openProject : () => _callApi(toolId),
                  );
                },
              ),
            ),
          ],
          // ── Open button ─────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
            child: GestureDetector(
              onTap: _openProject,
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 9),
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      '打开项目',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: palette.accentPrimary,
                      ),
                    ),
                    const SizedBox(width: 4),
                    Icon(
                      Icons.arrow_forward_rounded,
                      size: 14,
                      color: palette.accentPrimary,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _FrontendBadge extends StatelessWidget {
  const _FrontendBadge({required this.type});
  final String type;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final label = switch (type) {
      'html' => 'HTML',
      'markdown' => 'MD',
      'flutter' => 'Flutter',
      _ => 'Default',
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.borderSubtle,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          color: palette.textSecondary,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}

class _ApiChip extends StatelessWidget {
  const _ApiChip({
    required this.label,
    required this.hasInputs,
    required this.busy,
    required this.onTap,
  });

  final String label;
  final bool hasInputs;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: busy ? null : onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 0),
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: palette.borderSubtle),
        ),
        alignment: Alignment.center,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: busy ? palette.textSecondary : palette.textPrimary,
              ),
            ),
            if (hasInputs) ...[
              const SizedBox(width: 3),
              Icon(
                Icons.open_in_new_rounded,
                size: 10,
                color: palette.textSecondary,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
