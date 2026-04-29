import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/gradient_button.dart';

const String _kShieldSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>
  <path d="m9 12 2 2 4-4"/>
</svg>
''';

const String _kWifiOffSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M12 20h.01"/>
  <path d="M8.5 16.429a5 5 0 0 1 7 0"/>
  <path d="M5 12.859a10 10 0 0 1 5.17-2.69"/>
  <path d="M19 12.859a10 10 0 0 0-2.007-1.523"/>
  <line x1="2" x2="22" y1="2" y2="22"/>
</svg>
''';

const String _kZapSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>
</svg>
''';

const String _kAlertSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/>
  <path d="M12 9v4"/>
  <path d="M12 17h.01"/>
</svg>
''';

class LocalModelIntroPage extends StatelessWidget {
  const LocalModelIntroPage({super.key});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.trLegacy('本地模型'),
        primary: true,
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Hero section
                    Center(
                      child: Container(
                        width: 80,
                        height: 80,
                        decoration: BoxDecoration(
                          color: palette.accentPrimary.withOpacity(0.1),
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.memory,
                          size: 40,
                          color: palette.accentPrimary,
                        ),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Center(
                      child: Text(
                        context.trLegacy('在设备上运行本地 AI'),
                        style: TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w700,
                          color: palette.textPrimary,
                          height: 1.3,
                        ),
                      ),
                    ),
                    const SizedBox(height: 32),

                    // Advantages
                    Text(
                      context.trLegacy('优势'),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _FeatureItem(
                      svgIcon: _kShieldSvg,
                      title: context.trLegacy('隐私安全'),
                      description:
                          context.trLegacy('数据完全留在设备上，不会发送到任何服务器'),
                      color: isDark
                          ? const Color(0xFF7BC67E)
                          : const Color(0xFF4CAF50),
                    ),
                    const SizedBox(height: 12),
                    _FeatureItem(
                      svgIcon: _kWifiOffSvg,
                      title: context.trLegacy('离线可用'),
                      description:
                          context.trLegacy('无需网络连接，随时随地使用 AI 助手'),
                      color: isDark
                          ? const Color(0xFF64B5F6)
                          : const Color(0xFF2196F3),
                    ),
                    const SizedBox(height: 12),
                    _FeatureItem(
                      svgIcon: _kZapSvg,
                      title: context.trLegacy('完全免费'),
                      description:
                          context.trLegacy('无需 API 费用或订阅，没有使用限制'),
                      color: isDark
                          ? const Color(0xFFFFD54F)
                          : const Color(0xFFFFC107),
                    ),

                    const SizedBox(height: 28),

                    // Limitations
                    Text(
                      context.trLegacy('局限性'),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _FeatureItem(
                      svgIcon: _kAlertSvg,
                      title: context.trLegacy('性能受限'),
                      description:
                          context.trLegacy('端侧模型较小，能力有限，回复质量不如云端模型'),
                      color: isDark
                          ? const Color(0xFFE57373)
                          : const Color(0xFFEF5350),
                    ),
                    const SizedBox(height: 12),
                    _FeatureItem(
                      svgIcon: _kAlertSvg,
                      title: context.trLegacy('任务受限'),
                      description: context.trLegacy(
                          '目前无法处理复杂的 Agent 任务，适合简单对话和问答'),
                      color: isDark
                          ? const Color(0xFFE57373)
                          : const Color(0xFFEF5350),
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),
            // Bottom button
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
              child: GradientButton(
                width: screenWidth - 48,
                height: 48,
                text: context.trLegacy('浏览模型市场'),
                onTap: () async {
                  // Complete onboarding so user won't return to guide
                  await StorageService.setBool(
                      StorageKeys.welcomeCompleted, true);
                  // Navigate to home first, then push market page on top
                  GoRouterManager.clearAndNavigateTo('/home/chat');
                  GoRouterManager.push(
                    '/home/local_models?tab=market&pinned=$kOnboardingRecommendedModelId',
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FeatureItem extends StatelessWidget {
  final String svgIcon;
  final String title;
  final String description;
  final Color color;

  const _FeatureItem({
    required this.svgIcon,
    required this.title,
    required this.description,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: color.withOpacity(0.12),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Center(
              child: SvgPicture.string(
                svgIcon,
                width: 20,
                height: 20,
                colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.3,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  description,
                  style: TextStyle(
                    fontSize: 13,
                    color: palette.textSecondary,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
