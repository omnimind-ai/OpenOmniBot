import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/features/welcome/widgets/onboarding_choice_card.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/gradient_button.dart';

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

class _OnboardingChoicePageState extends ConsumerState<OnboardingChoicePage> {
  @override
  void initState() {
    super.initState();
    // Check persisted state for crash recovery
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(onboardingStateProvider).checkExistingState();
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final state = ref.watch(onboardingStateProvider);
    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Column(
            children: [
              const Spacer(flex: 2),
              // App logo
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  color: palette.surfacePrimary,
                  borderRadius: BorderRadius.circular(20),
                  boxShadow: [
                    BoxShadow(
                      color: palette.shadowColor,
                      blurRadius: 16,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: Image.asset(
                    'assets/loading/loading_icon.png',
                    width: 56,
                    height: 56,
                    errorBuilder: (_, __, ___) => Icon(
                      Icons.smart_toy_outlined,
                      size: 36,
                      color: palette.accentPrimary,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 24),
              // Title
              Text(
                context.trLegacy('配置你的 AI 助手'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  color: palette.textPrimary,
                  height: 1.3,
                ),
              ),
              const SizedBox(height: 8),
              // Subtitle
              Text(
                context.trLegacy('选择一种方式开始使用智能助手'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w400,
                  color: palette.textSecondary,
                  height: 1.5,
                ),
              ),
              const Spacer(flex: 1),
              // Choice cards
              OnboardingChoiceCard(
                svgIcon: _kCloudSvg,
                title: context.trLegacy('云 AI 服务'),
                subtitle: context.trLegacy('连接 OpenAI、Anthropic 或兼容的 API 服务'),
                completed: state.cloudConfigured,
                onTap: () => GoRouterManager.push('/home/vlm_model_setting'),
              ),
              const SizedBox(height: 16),
              OnboardingChoiceCard(
                svgIcon: _kDeviceSvg,
                title: context.trLegacy('本地模型'),
                subtitle: context.trLegacy('在设备上运行 AI，离线可用，隐私安全'),
                completed: state.localModelReady,
                onTap: () => GoRouterManager.push('/welcome/local_intro'),
              ),
              const Spacer(flex: 2),
              // Skip text
              GestureDetector(
                onTap: () async {
                  await StorageService.setBool(
                      StorageKeys.welcomeCompleted, true);
                  GoRouterManager.clearAndNavigateTo('/home/chat');
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
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
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }
}
