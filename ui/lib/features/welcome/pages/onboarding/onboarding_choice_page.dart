import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/features/welcome/widgets/onboarding_choice_card.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

const String _kCloudSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>
</svg>
''';

const String _kDeviceSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <rect width="14" height="20" x="5" y="2" rx="2" ry="2"/>
  <path d="M12 18h.01"/>
</svg>
''';

class OnboardingChoicePage extends ConsumerStatefulWidget {
  const OnboardingChoicePage({super.key});

  @override
  ConsumerState<OnboardingChoicePage> createState() =>
      _OnboardingChoicePageState();
}

class _OnboardingChoicePageState extends ConsumerState<OnboardingChoicePage>
    with TickerProviderStateMixin {
  late final AnimationController _controller;

  // Staggered animations
  late final Animation<double> _logoScale;
  late final Animation<double> _titleOffset;
  late final Animation<double> _titleOpacity;
  late final Animation<double> _subtitleOffset;
  late final Animation<double> _subtitleOpacity;
  late final Animation<double> _card1Offset;
  late final Animation<double> _card1Opacity;
  late final Animation<double> _card2Offset;
  late final Animation<double> _card2Opacity;
  late final Animation<double> _skipOpacity;

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(onboardingStateProvider).checkExistingState();
    });

    _controller = AnimationController(
      duration: const Duration(milliseconds: 1200),
      vsync: this,
    );

    // Logo: 0ms-600ms, elasticOut scale + fade
    _logoScale = Tween<double>(begin: 0.5, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.5, curve: Curves.elasticOut),
      ),
    );
    // Title: 150ms-650ms, slide up 24px + fade
    _titleOffset = Tween<double>(begin: 24.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.125, 0.54, curve: Curves.easeOutCubic),
      ),
    );
    _titleOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.125, 0.42, curve: Curves.easeOut),
      ),
    );

    // Subtitle: 250ms-750ms
    _subtitleOffset = Tween<double>(begin: 24.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.21, 0.625, curve: Curves.easeOutCubic),
      ),
    );
    _subtitleOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.21, 0.5, curve: Curves.easeOut),
      ),
    );

    // Card 1: 400ms-1000ms, slide up 32px + fade
    _card1Offset = Tween<double>(begin: 32.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.33, 0.83, curve: Curves.easeOutBack),
      ),
    );
    _card1Opacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.33, 0.58, curve: Curves.easeOut),
      ),
    );

    // Card 2: 520ms-1120ms
    _card2Offset = Tween<double>(begin: 32.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.43, 0.93, curve: Curves.easeOutBack),
      ),
    );
    _card2Opacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.43, 0.68, curve: Curves.easeOut),
      ),
    );

    // Skip: 700ms-1100ms, fade only
    _skipOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.58, 0.92, curve: Curves.easeOut),
      ),
    );

    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final state = ref.watch(onboardingStateProvider);

    return Scaffold(
      backgroundColor: palette.pageBackground,
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Column(
                  children: [
                    const Spacer(flex: 3),

                    // Title with inline logo
                    Transform.translate(
                      offset: Offset(0, _titleOffset.value),
                      child: Opacity(
                        opacity: _titleOpacity.value,
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            ShaderMask(
                              shaderCallback: (bounds) =>
                                  const LinearGradient(
                                colors: [
                                  Color(0xFF1930D9),
                                  Color(0xFF2DA5F0),
                                ],
                              ).createShader(bounds),
                              child: Text(
                                context.trLegacy('Hi，我是小万'),
                                style: const TextStyle(
                                  fontSize: 28,
                                  fontWeight: FontWeight.w800,
                                  color: Colors.white,
                                  height: 1.4,
                                  letterSpacing: -0.5,
                                ),
                              ),
                            ),
                            const SizedBox(width: 4),
                            Transform.scale(
                              scale: _logoScale.value,
                              child: SizedBox(
                                width: 48,
                                height: 48,
                                child: ClipRect(
                                  child: Transform.scale(
                                    scale: 1.8,
                                    child: Image.asset(
                                      'assets/loading/loading_icon3x.png',
                                      width: 48,
                                      height: 48,
                                      fit: BoxFit.contain,
                                      errorBuilder: (_, __, ___) => Icon(
                                        Icons.smart_toy_outlined,
                                        size: 24,
                                        color: palette.accentPrimary,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),

                    // Subtitle
                    Transform.translate(
                      offset: Offset(0, _subtitleOffset.value),
                      child: Opacity(
                        opacity: _subtitleOpacity.value,
                        child: Text(
                          context.trLegacy('你的 AI 助手，随时准备就绪'),
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 15,
                            color: palette.textSecondary,
                            height: 1.5,
                          ),
                        ),
                      ),
                    ),

                    const Spacer(flex: 2),

                    // Card 1 — Cloud AI
                    Transform.translate(
                      offset: Offset(0, _card1Offset.value),
                      child: Opacity(
                        opacity: _card1Opacity.value,
                        child: OnboardingChoiceCard(
                          svgIcon: _kCloudSvg,
                          title: context.trLegacy('云 AI 服务'),
                          subtitle: context.trLegacy(
                            '连接 OpenAI、Anthropic 或兼容的 API 服务',
                          ),
                          gradientColors: const [
                            Color(0xFF2C7FEB),
                            Color(0xFF64B5F6),
                          ],
                          completed: state.cloudConfigured,
                          onTap: () async {
                            await StorageService.setBool(
                              StorageKeys.welcomeCompleted,
                              true,
                            );
                            GoRouterManager.clearAndNavigateTo('/home/chat');
                            GoRouterManager.push('/home/vlm_model_setting');
                          },
                        ),
                      ),
                    ),
                    const SizedBox(height: 14),

                    // Card 2 — Local Model
                    Transform.translate(
                      offset: Offset(0, _card2Offset.value),
                      child: Opacity(
                        opacity: _card2Opacity.value,
                        child: OnboardingChoiceCard(
                          svgIcon: _kDeviceSvg,
                          title: context.trLegacy('本地模型'),
                          subtitle: context.trLegacy(
                            '在设备上运行本地 AI，离线可用，隐私安全',
                          ),
                          gradientColors: const [
                            Color(0xFF7C4DFF),
                            Color(0xFFB388FF),
                          ],
                          completed: state.localModelReady,
                          onTap: () =>
                              GoRouterManager.push('/welcome/local_intro'),
                        ),
                      ),
                    ),

                    const Spacer(flex: 3),

                    // Skip text
                    Opacity(
                      opacity: _skipOpacity.value,
                      child: GestureDetector(
                        onTap: () async {
                          await StorageService.setBool(
                            StorageKeys.welcomeCompleted,
                            true,
                          );
                          GoRouterManager.clearAndNavigateTo('/home/chat');
                        },
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Text(
                            context.trLegacy('跳过，稍后在设置中配置'),
                            style: TextStyle(
                              fontSize: 14,
                              color: palette.textTertiary,
                              height: 1.5,
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 28),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
