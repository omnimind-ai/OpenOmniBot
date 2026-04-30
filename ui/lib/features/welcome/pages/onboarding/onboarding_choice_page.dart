import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/features/welcome/widgets/onboarding_choice_card.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_section_title.dart';

const String _kCloudSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>
</svg>
''';

const String _kDeviceSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M12 20v2"/>
  <path d="M12 2v2"/>
  <path d="M17 20v2"/>
  <path d="M17 2v2"/>
  <path d="M2 12h2"/>
  <path d="M2 17h2"/>
  <path d="M2 7h2"/>
  <path d="M20 12h2"/>
  <path d="M20 17h2"/>
  <path d="M20 7h2"/>
  <path d="M7 20v2"/>
  <path d="M7 2v2"/>
  <rect x="4" y="4" width="16" height="16" rx="2"/>
  <rect x="8" y="8" width="8" height="8" rx="1"/>
</svg>
''';

class OnboardingChoicePage extends ConsumerStatefulWidget {
  const OnboardingChoicePage({super.key});

  @override
  ConsumerState<OnboardingChoicePage> createState() =>
      _OnboardingChoicePageState();
}

class _OnboardingChoicePageState extends ConsumerState<OnboardingChoicePage>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  // Simple staggered fade + slide animations
  late final Animation<double> _heroOffset;
  late final Animation<double> _heroOpacity;
  late final Animation<double> _subtitleOpacity;
  late final Animation<double> _contentOffset;
  late final Animation<double> _contentOpacity;
  late final Animation<double> _skipOpacity;

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(onboardingStateProvider).checkExistingState();
    });

    _controller = AnimationController(
      duration: const Duration(milliseconds: 900),
      vsync: this,
    );

    // Hero (title + logo): 0%-40%
    _heroOffset = Tween<double>(begin: 20.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.4, curve: Curves.easeOutCubic),
      ),
    );
    _heroOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.3, curve: Curves.easeOut),
      ),
    );

    // Subtitle: 10%-45%
    _subtitleOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.1, 0.45, curve: Curves.easeOut),
      ),
    );

    // Items section: 25%-70%
    _contentOffset = Tween<double>(begin: 16.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.25, 0.7, curve: Curves.easeOutCubic),
      ),
    );
    _contentOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.25, 0.6, curve: Curves.easeOut),
      ),
    );

    // Skip: 55%-90%
    _skipOpacity = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.55, 0.9, curve: Curves.easeOut),
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
        builder: (context, _) {
          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Column(
                children: [
                  const Spacer(flex: 3),

                  // Hero: gradient title + inline logo
                  Transform.translate(
                    offset: Offset(0, _heroOffset.value),
                    child: Opacity(
                      opacity: _heroOpacity.value,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
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
                          SizedBox(
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
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),

                  // Subtitle
                  Opacity(
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

                  const Spacer(flex: 2),

                  // Flat items section
                  Transform.translate(
                    offset: Offset(0, _contentOffset.value),
                    child: Opacity(
                      opacity: _contentOpacity.value,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Section header (settings-style)
                          SettingsSectionTitle(
                            label: '选择方式',
                            bottomPadding: 0,
                          ),

                          // Cloud AI
                          OnboardingChoiceCard(
                            svgIcon: _kCloudSvg,
                            title: context.trLegacy('云 AI 服务'),
                            subtitle: context.trLegacy(
                              '连接 OpenAI、Anthropic 或兼容的 API 服务',
                            ),
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

                          // Divider (settings-style, left-padded past icon)
                          Padding(
                            padding: const EdgeInsets.only(left: 32),
                            child: Divider(
                              height: 1,
                              thickness: 1,
                              color: palette.borderSubtle.withValues(
                                alpha: context.isDarkTheme ? 0.5 : 0.78,
                              ),
                            ),
                          ),

                          // Local Model
                          OnboardingChoiceCard(
                            svgIcon: _kDeviceSvg,
                            title: context.trLegacy('本地模型'),
                            subtitle: context.trLegacy(
                              '在设备上运行本地 AI，离线可用，隐私安全',
                            ),
                            completed: state.localModelReady,
                            onTap: () =>
                                GoRouterManager.push('/welcome/local_intro'),
                          ),
                        ],
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
          );
        },
      ),
    );
  }
}
