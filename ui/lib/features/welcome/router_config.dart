import 'package:go_router/go_router.dart';
import 'package:ui/features/welcome/pages/onboarding/configuration_guide_page.dart';

/// Onboarding module route configuration
List<GoRoute> welcomeRoutes = [
  GoRoute(
    path: '/welcome/guide',
    name: 'welcome/guide',
    builder: (context, state) => ConfigurationGuidePage(
      replay: state.uri.queryParameters['replay'] == 'true',
    ),
  ),
  GoRoute(
    path: '/welcome/choice',
    name: 'welcome/choice',
    builder: (context, state) => ConfigurationGuidePage(
      replay: state.uri.queryParameters['replay'] == 'true',
    ),
  ),
];
