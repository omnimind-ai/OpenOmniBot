import 'package:go_router/go_router.dart';
import 'package:ui/features/welcome/pages/onboarding/local_model_intro_page.dart';

List<GoRoute> welcomeLocalModelRoutes = [
  GoRoute(
    path: '/welcome/local_intro',
    name: 'welcome/local_intro',
    builder: (context, state) => const LocalModelIntroPage(),
  ),
];
