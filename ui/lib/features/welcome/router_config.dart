import 'package:go_router/go_router.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_choice_page.dart';
import 'package:ui/features/welcome/pages/onboarding/local_model_intro_page.dart';

/// Onboarding module route configuration
List<GoRoute> welcomeRoutes = [
  GoRoute(
    path: '/welcome/choice',
    name: 'welcome/choice',
    builder: (context, state) => const OnboardingChoicePage(),
  ),
  GoRoute(
    path: '/welcome/local_intro',
    name: 'welcome/local_intro',
    builder: (context, state) => const LocalModelIntroPage(),
  ),
];
