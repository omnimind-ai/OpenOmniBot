import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';

class ArtifactCard extends StatelessWidget {
  final Map<String, dynamic> artifact;

  const ArtifactCard({super.key, required this.artifact});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final title = (artifact['title'] ?? artifact['fileName'] ?? 'artifact')
        .toString();
    final mimeType = (artifact['mimeType'] ?? '').toString();
    final size = artifact['size']?.toString() ?? '';
    final shellPath = artifact['workspacePath']?.toString() ?? '';
    final path = artifact['androidPath']?.toString() ?? '';

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isDark ? palette.borderSubtle : const Color(0xFFE0E0E0),
        ),
        color: isDark ? palette.surfaceSecondary : const Color(0xFFF8F9FB),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            [
              mimeType,
              if (size.isNotEmpty) '$size bytes',
            ].where((item) => item.isNotEmpty).join(' · '),
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
          if (shellPath.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              shellPath,
              style: TextStyle(fontSize: 11, color: palette.textTertiary),
            ),
          ],
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => _openPreview(context),
                child: const Text('预览'),
              ),
              if (path.isNotEmpty)
                TextButton(
                  onPressed: () {
                    OmnibotResourceService.saveToLocal(
                      sourcePath: path,
                      fileName:
                          (artifact['fileName'] ??
                                  artifact['title'] ??
                                  'artifact')
                              .toString(),
                      mimeType: mimeType,
                    );
                  },
                  child: const Text('保存'),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _openPreview(BuildContext context) async {
    final title = (artifact['title'] ?? artifact['fileName'] ?? 'artifact')
        .toString();
    final mimeType = (artifact['mimeType'] ?? '').toString();
    final shellPath = artifact['workspacePath']?.toString() ?? '';
    final path = artifact['androidPath']?.toString() ?? '';
    final uri = artifact['uri']?.toString();
    final previewKind = artifact['previewKind']?.toString();

    if (path.isNotEmpty) {
      final metadata = OmnibotResourceService.describePath(
        path,
        uri: uri,
        title: title,
        previewKind: previewKind,
        mimeType: mimeType,
        shellPath: shellPath.isEmpty ? null : shellPath,
      );
      await showOmnibotArtifactPreviewSheet(context, metadata);
      return;
    }
    if (uri == null || uri.isEmpty) {
      return;
    }
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    if (!context.mounted) return;
    final metadata = OmnibotResourceService.resolveUri(uri);
    if (metadata != null) {
      await showOmnibotArtifactPreviewSheet(context, metadata);
      return;
    }
    await OmnibotResourceService.openUri(uri);
  }
}
