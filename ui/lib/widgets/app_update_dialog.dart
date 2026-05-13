import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart' show LaunchMode;
import 'package:url_launcher/url_launcher_string.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/utils/ui.dart';

Future<void> showAppUpdateDialog(
  BuildContext context,
  AppUpdateStatus status,
) async {
  final hasDirectInstall = status.canInstall;
  final locale = Localizations.localeOf(context);
  String text(String value) => AppTextLocalizer.text(value, locale: locale);
  String choose({required String zh, required String en}) {
    return AppTextLocalizer.choose(zh: zh, en: en, locale: locale);
  }

  final confirmed = await AppDialog.confirm(
    context,
    title: text('发现新版本'),
    cancelText: choose(zh: '稍后', en: 'Later'),
    confirmText: hasDirectInstall
        ? choose(zh: '立即更新', en: 'Update now')
        : choose(zh: '前往 Release', en: 'Go to Release'),
    confirmButtonColor: AppColors.buttonPrimary,
    content: _AppUpdateDialogContent(status: status),
    barrierDismissible: true,
  );

  if (confirmed != true || !context.mounted) {
    return;
  }

  if (!hasDirectInstall) {
    if (status.releaseUrl.isEmpty) {
      showToast(
        choose(zh: '缺少可用的 Release 地址', en: 'No available Release URL'),
        type: ToastType.error,
      );
      return;
    }
    final launched = await launchUrlString(
      status.releaseUrl,
      mode: LaunchMode.externalApplication,
    );
    if (!launched) {
      showToast(
        choose(zh: '打开 Release 页面失败', en: 'Failed to open Release page'),
        type: ToastType.error,
      );
    }
    return;
  }

  try {
    final notificationGranted = await ensureNotificationPermission();
    if (!notificationGranted) {
      showToast(
        choose(
          zh: '未授予通知权限，下载仍会继续，但不会显示系统下载进度',
          en: 'Notification permission is not granted. Download will continue, but system download progress will not be shown.',
        ),
        type: ToastType.warning,
      );
    }
    final result = await AppUpdateService.installLatestApk();
    final toastType = result.success ? ToastType.success : ToastType.warning;
    final message = result.message.isEmpty
        ? choose(zh: '更新安装失败', en: 'Update installation failed')
        : result.message;
    showToast(message, type: toastType);
  } catch (_) {
    showToast(
      choose(zh: '拉起更新失败', en: 'Failed to start update'),
      type: ToastType.error,
    );
  }
}

class _AppUpdateDialogContent extends StatelessWidget {
  final AppUpdateStatus status;

  const _AppUpdateDialogContent({required this.status});

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    String choose({required String zh, required String en}) {
      return AppTextLocalizer.choose(zh: zh, en: en, locale: locale);
    }

    final publishedAt = status.publishedAt > 0
        ? DateTime.fromMillisecondsSinceEpoch(status.publishedAt)
        : null;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _InfoRow(
          label: choose(zh: '当前版本', en: 'Current version'),
          value: status.currentVersionLabel,
        ),
        const SizedBox(height: 8),
        _InfoRow(
          label: choose(zh: '最新版本', en: 'Latest version'),
          value: status.latestVersionLabel,
        ),
        if (publishedAt != null) ...[
          const SizedBox(height: 8),
          _InfoRow(
            label: choose(zh: '发布时间', en: 'Published at'),
            value:
                '${publishedAt.year.toString().padLeft(4, '0')}-${publishedAt.month.toString().padLeft(2, '0')}-${publishedAt.day.toString().padLeft(2, '0')}',
          ),
        ],
        if (status.releaseNotes.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(
            choose(zh: '更新说明', en: 'Release notes'),
            style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: AppColors.text,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            constraints: const BoxConstraints(maxHeight: 140),
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xFFF6F8FA),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFFE6EDF5)),
            ),
            child: SingleChildScrollView(
              child: Text(
                status.releaseNotes,
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.6,
                  color: AppColors.text70,
                ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 68,
          child: Text(
            label,
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.text50,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 13,
              color: AppColors.text,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
  }
}
