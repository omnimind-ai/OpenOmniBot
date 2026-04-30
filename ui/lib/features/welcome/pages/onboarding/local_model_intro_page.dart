import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/gradient_button.dart';

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

  // Animations
  late final Animation<double> _backOpacity;
  late final Animation<double> _titleOffset;
  late final Animation<double> _titleOpacity;
  late final Animation<double> _subtitleOffset;
  late final Animation<double> _subtitleOpacity;
  // 3 cards staggered
  late final List<Animation<double>> _cardOffsets;
  late final List<Animation<double>> _cardOpacities;
  late final Animation<double> _noteOpacity;
  late final Animation<double> _buttonOffset;
  late final Animation<double> _buttonOpacity;

  static const _cardCount = 3;

  @override
  void initState() {
    super.initState();

    _controller = AnimationController(
      duration: const Duration(milliseconds: 1300),
      vsync: this,
    );

    _backOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.2, curve: Curves.easeOut),
      ),
    );

    _titleOffset = Tween<double>(begin: 30.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.08, 0.38, curve: Curves.easeOutCubic),
      ),
    );
    _titleOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.08, 0.28, curve: Curves.easeOut),
      ),
    );

    _subtitleOffset = Tween<double>(begin: 24.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.15, 0.45, curve: Curves.easeOutCubic),
      ),
    );
    _subtitleOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.15, 0.35, curve: Curves.easeOut),
      ),
    );

    // 3 cards staggered: start at 0.28, each 0.08 apart
    _cardOffsets = List.generate(_cardCount, (i) {
      final start = 0.28 + i * 0.08;
      final end = (start + 0.35).clamp(0.0, 1.0);
      return Tween<double>(begin: 32.0, end: 0.0).animate(
        CurvedAnimation(
          parent: _controller,
          curve: Interval(start, end, curve: Curves.easeOutBack),
        ),
      );
    });
    _cardOpacities = List.generate(_cardCount, (i) {
      final start = 0.28 + i * 0.08;
      final end = (start + 0.2).clamp(0.0, 1.0);
      return Tween<double>(begin: 0.0, end: 1.0).animate(
        CurvedAnimation(
          parent: _controller,
          curve: Interval(start, end, curve: Curves.easeOut),
        ),
      );
    });

    _noteOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.6, 0.8, curve: Curves.easeOut),
      ),
    );

    _buttonOffset = Tween<double>(begin: 20.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.7, 1.0, curve: Curves.easeOutCubic),
      ),
    );
    _buttonOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.7, 0.9, curve: Curves.easeOut),
      ),
    );

    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Widget _buildCard(int index, Widget child) {
    return Transform.translate(
      offset: Offset(0, _cardOffsets[index].value),
      child: Opacity(
        opacity: _cardOpacities[index].value,
        child: child,
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
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Column(
                        children: [
                          const SizedBox(height: 72),

                          // Title — gradient text
                          Transform.translate(
                            offset: Offset(0, _titleOffset.value),
                            child: Opacity(
                              opacity: _titleOpacity.value,
                              child: ShaderMask(
                                shaderCallback: (bounds) =>
                                    const LinearGradient(
                                  colors: [
                                    Color(0xFF1930D9),
                                    Color(0xFF2DA5F0),
                                  ],
                                ).createShader(bounds),
                                child: Text(
                                  context.trLegacy('在设备上运行本地 AI'),
                                  textAlign: TextAlign.center,
                                  style: const TextStyle(
                                    fontSize: 26,
                                    fontWeight: FontWeight.w800,
                                    color: Colors.white,
                                    height: 1.2,
                                    letterSpacing: -0.5,
                                  ),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(height: 8),

                          // Subtitle
                          Transform.translate(
                            offset: Offset(0, _subtitleOffset.value),
                            child: Opacity(
                              opacity: _subtitleOpacity.value,
                              child: Text(
                                context.trLegacy('无需网络，完全免费'),
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  fontSize: 15,
                                  color: palette.textSecondary,
                                  height: 1.5,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(height: 32),

                          // Card 1: Privacy
                          _buildCard(
                            0,
                            _FeatureCard(
                              svgIcon: _kShieldSvg,
                              title: context.trLegacy('隐私安全'),
                              description: context.trLegacy(
                                '数据完全留在设备上，不会发送到任何服务器。对话内容、个人偏好等敏感信息始终由你掌控。',
                              ),
                            ),
                          ),
                          const SizedBox(height: 12),

                          // Card 2: Offline
                          _buildCard(
                            1,
                            _FeatureCard(
                              svgIcon: _kWifiOffSvg,
                              title: context.trLegacy('离线可用'),
                              description: context.trLegacy(
                                '无需网络连接即可运行 AI 助手。无论在飞机上、地铁里还是偏远地区，随时随地可用。',
                              ),
                            ),
                          ),
                          const SizedBox(height: 12),

                          // Card 3: Free
                          _buildCard(
                            2,
                            _FeatureCard(
                              svgIcon: _kZapSvg,
                              title: context.trLegacy('完全免费'),
                              description: context.trLegacy(
                                '无需 API 费用或订阅。模型下载后可无限次使用，没有任何隐藏费用。',
                              ),
                            ),
                          ),
                          const SizedBox(height: 20),

                          // Limitation note
                          Opacity(
                            opacity: _noteOpacity.value,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 12,
                              ),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Padding(
                                    padding: const EdgeInsets.only(top: 1),
                                    child: SvgPicture.string(
                                      _kInfoSvg,
                                      width: 16,
                                      height: 16,
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
                                        fontSize: 12,
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

                  // Bottom button
                  Transform.translate(
                    offset: Offset(0, _buttonOffset.value),
                    child: Opacity(
                      opacity: _buttonOpacity.value,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                        child: GradientButton(
                          width: screenWidth - 48,
                          height: 48,
                          text: context.trLegacy('浏览模型市场'),
                          onTap: () async {
                            await StorageService.setBool(
                              StorageKeys.welcomeCompleted,
                              true,
                            );
                            GoRouterManager.clearAndNavigateTo('/home/chat');
                            GoRouterManager.push(
                              '/home/local_models?tab=market&pinned=$kOnboardingRecommendedModelId',
                            );
                          },
                        ),
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

class _FeatureCard extends StatelessWidget {
  final String svgIcon;
  final String title;
  final String description;

  const _FeatureCard({
    required this.svgIcon,
    required this.title,
    required this.description,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 16,
            spreadRadius: 0,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [Color(0xFF1930D9), Color(0xFF2DA5F0)],
              ),
            ),
            child: Center(
              child: SvgPicture.string(
                svgIcon,
                width: 22,
                height: 22,
                colorFilter: const ColorFilter.mode(
                  Colors.white,
                  BlendMode.srcIn,
                ),
              ),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.3,
                    letterSpacing: -0.2,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: TextStyle(
                    fontSize: 13,
                    color: palette.textSecondary,
                    height: 1.5,
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
