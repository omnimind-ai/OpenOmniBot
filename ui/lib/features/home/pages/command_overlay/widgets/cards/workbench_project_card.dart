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
      margin: const EdgeInsets.only(top: 4, bottom: 2),
      padding: const EdgeInsets.fromLTRB(12, 11, 12, 12),
      decoration: BoxDecoration(
        color: palette.surfaceElevated.withValues(alpha: 0.78),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle.withValues(alpha: 0.72)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.folder_outlined,
                  size: 16,
                  color: palette.accentPrimary,
                ),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      _name.isEmpty ? _projectId : _name,
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                        height: 1.12,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _projectId.isEmpty ? 'Workbench Project' : _projectId,
                      style: TextStyle(
                        fontSize: 10.5,
                        color: palette.textTertiary,
                        height: 1.1,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _FrontendBadge(type: _frontendType),
              const SizedBox(width: 4),
              _OpenProjectIconButton(onTap: _openProject),
            ],
          ),
          if (_description.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 9),
              child: Text(
                _description,
                style: TextStyle(
                  fontSize: 12,
                  color: palette.textSecondary,
                  height: 1.35,
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          if (_apis.isNotEmpty) _buildApiActions(context),
        ],
      ),
    );
  }

  Widget _buildApiActions(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: _apis
            .map((api) {
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
            })
            .toList(growable: false),
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
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.78),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10.5,
          color: palette.textSecondary,
          fontWeight: FontWeight.w600,
          height: 1,
        ),
      ),
    );
  }
}

class _OpenProjectIconButton extends StatelessWidget {
  const _OpenProjectIconButton({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: SizedBox(
          width: 30,
          height: 30,
          child: Icon(
            Icons.arrow_outward_rounded,
            size: 17,
            color: palette.textSecondary,
          ),
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
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: busy ? null : onTap,
        child: Ink(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
          decoration: BoxDecoration(
            color: palette.surfaceSecondary.withValues(alpha: 0.62),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: palette.borderSubtle.withValues(alpha: 0.64),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                label.isEmpty ? 'Action' : label,
                style: TextStyle(
                  fontSize: 11.5,
                  height: 1,
                  color: busy ? palette.textTertiary : palette.textPrimary,
                  fontWeight: FontWeight.w500,
                ),
              ),
              if (hasInputs) ...[
                const SizedBox(width: 4),
                Icon(
                  Icons.open_in_new_rounded,
                  size: 11,
                  color: palette.textTertiary,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
