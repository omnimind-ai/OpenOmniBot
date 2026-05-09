import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/gradient_button.dart';
import 'package:ui/widgets/settings_section_title.dart';

// ---------- SVG icons ----------

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

const String _kInfoSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <circle cx="12" cy="12" r="10"/>
  <path d="M12 16v-4"/>
  <path d="M12 8h.01"/>
</svg>
''';

const String _kBackSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="m15 18-6-6 6-6"/>
</svg>
''';

class LocalModelIntroPage extends StatefulWidget {
  const LocalModelIntroPage({super.key});

  @override
  State<LocalModelIntroPage> createState() => _LocalModelIntroPageState();
}

class _LocalModelIntroPageState extends State<LocalModelIntroPage>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  late final Animation<double> _backOpacity;
  late final Animation<double> _headerOffset;
  late final Animation<double> _headerOpacity;
  late final Animation<double> _contentOffset;
  late final Animation<double> _contentOpacity;
  late final Animation<double> _buttonOpacity;

  @override
  void initState() {
    super.initState();

    _controller = AnimationController(
      duration: const Duration(milliseconds: 900),
      vsync: this,
    );

    // Back button: 0%-20%
    _backOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.2, curve: Curves.easeOut),
      ),
    );

    // Header (title + subtitle): 5%-40%
    _headerOffset = Tween<double>(begin: 20.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.05, 0.4, curve: Curves.easeOutCubic),
      ),
    );
    _headerOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.05, 0.3, curve: Curves.easeOut),
      ),
    );

    // Content (section + items + note): 20%-65%
    _contentOffset = Tween<double>(begin: 16.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.2, 0.65, curve: Curves.easeOutCubic),
      ),
    );
    _contentOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.2, 0.55, curve: Curves.easeOut),
      ),
    );

    // Button: 60%-90%
    _buttonOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.6, 0.9, curve: Curves.easeOut),
      ),
    );

    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Widget _buildDivider() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 28),
      child: Divider(
        height: 1,
        thickness: 1,
        color: palette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) {
          return SafeArea(
            child: Column(
              children: [
                // Floating back button
                Align(
                  alignment: Alignment.centerLeft,
                  child: Opacity(
                    opacity: _backOpacity.value,
                    child: GestureDetector(
                      onTap: () => GoRouterManager.pop(),
                      behavior: HitTestBehavior.opaque,
                      child: Padding(
                        padding: const EdgeInsets.only(
                          left: 12,
                          top: 8,
                          right: 24,
                          bottom: 8,
                        ),
                        child: SvgPicture.string(
                          _kBackSvg,
                          width: 24,
                          height: 24,
                          colorFilter: ColorFilter.mode(
                            palette.textPrimary,
                            BlendMode.srcIn,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                // Scrollable content
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 48),

                        // Gradient title + subtitle
                        Transform.translate(
                          offset: Offset(0, _headerOffset.value),
                          child: Opacity(
                            opacity: _headerOpacity.value,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                ShaderMask(
                                  shaderCallback: (bounds) =>
                                      LinearGradient(
                                    colors: context.isDarkTheme
                                        ? [
                                            palette.accentPrimary,
                                            Color.lerp(palette.accentPrimary,
                                                palette.textPrimary, 0.3)!,
                                          ]
                                        : const [
                                            Color(0xFF1930D9),
                                            Color(0xFF2DA5F0),
                                          ],
                                  ).createShader(bounds),
                                  child: Text(
                                    context.trLegacy('在设备上运行本地 AI'),
                                    style: const TextStyle(
                                      fontSize: 26,
                                      fontWeight: FontWeight.w800,
                                      color: Colors.white,
                                      height: 1.2,
                                      letterSpacing: -0.5,
                                    ),
                                  ),
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  context.trLegacy('无需网络，完全免费'),
                                  style: TextStyle(
                                    fontSize: 15,
                                    color: palette.textSecondary,
                                    height: 1.5,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 28),

                        // Features section (flat, settings-style)
                        Transform.translate(
                          offset: Offset(0, _contentOffset.value),
                          child: Opacity(
                            opacity: _contentOpacity.value,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                // Section header
                                SettingsSectionTitle(
                                  label: '特性',
                                  bottomPadding: 0,
                                ),

                                // Feature 1: Privacy
                                _FeatureItem(
                                  svgIcon: _kShieldSvg,
                                  title: context.trLegacy('隐私安全'),
                                  description: context.trLegacy(
                                    '数据完全留在设备上，不会发送到任何服务器。对话内容、个人偏好等敏感信息始终由你掌控。',
                                  ),
                                ),
                                _buildDivider(),

                                // Feature 2: Offline
                                _FeatureItem(
                                  svgIcon: _kWifiOffSvg,
                                  title: context.trLegacy('离线可用'),
                                  description: context.trLegacy(
                                    '无需网络连接即可运行 AI 助手。无论在飞机上、地铁里还是偏远地区，随时随地可用。',
                                  ),
                                ),
                                _buildDivider(),

                                // Feature 3: Free
                                _FeatureItem(
                                  svgIcon: _kZapSvg,
                                  title: context.trLegacy('完全免费'),
                                  description: context.trLegacy(
                                    '无需 API 费用或订阅。模型下载后可无限次使用，没有任何隐藏费用。',
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 20),

                        // Limitation note (flat footnote)
                        Opacity(
                          opacity: _contentOpacity.value,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 4,
                              vertical: 8,
                            ),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Padding(
                                  padding: const EdgeInsets.only(top: 1),
                                  child: SvgPicture.string(
                                    _kInfoSvg,
                                    width: 14,
                                    height: 14,
                                    colorFilter: ColorFilter.mode(
                                      palette.textTertiary,
                                      BlendMode.srcIn,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    context.trLegacy(
                                      '端侧模型较小，回复质量不如云端模型，暂不支持复杂 Agent 任务，适合日常对话与问答。',
                                    ),
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: palette.textTertiary,
                                      height: 1.5,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 24),
                      ],
                    ),
                  ),
                ),

                // Bottom CTA button
                Opacity(
                  opacity: _buttonOpacity.value,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                    child: GradientButton(
                      width: screenWidth - 48,
                      height: 48,
                      text: context.trLegacy('浏览模型市场'),
                      gradientColors: context.isDarkTheme
                          ? [
                              Color.lerp(palette.surfaceElevated,
                                  palette.accentPrimary, 0.55)!,
                              Color.lerp(palette.surfaceSecondary,
                                  palette.accentPrimary, 0.70)!,
                            ]
                          : const [Color(0xFF1930D9), Color(0xFF2DA5F0)],
                      onTap: () async {
                        await StorageService.setBool(
                          StorageKeys.welcomeCompleted,
                          true,
                        );
                        GoRouterManager.clearAndNavigateTo('/home/chat');
                        GoRouterManager.push(
                          '/home/local_models?tab=market&backend=$kOnboardingRecommendedBackend&pinned=$kOnboardingRecommendedModelId',
                        );
                      },
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

// ---------- Private widgets ----------

/// Flat feature item matching the settings page design language.
class _FeatureItem extends StatelessWidget {
  final String svgIcon;
  final String title;
  final String description;

  const _FeatureItem({
    required this.svgIcon,
    required this.title,
    required this.description,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 4, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: SvgPicture.string(
              svgIcon,
              width: 18,
              height: 18,
              colorFilter: ColorFilter.mode(
                palette.accentPrimary,
                BlendMode.srcIn,
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                    height: 1.5,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  description,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w400,
                    color: palette.textSecondary,
                    height: 1.5,
                    fontFamily: 'PingFang SC',
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
