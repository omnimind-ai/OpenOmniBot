import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'pages/local_models/local_models_page.dart';

List<GoRoute> homeLocalModelRoutes = [
  GoRoute(
    path: '/home/local_models',
    name: 'home/local_models',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/local_models',
      child: LocalModelsPage(
        initialTab: state.uri.queryParameters['tab'] ?? 'service',
        initialBackend: state.uri.queryParameters['backend'],
        pinnedModelId: state.uri.queryParameters['pinned'],
      ),
    ),
  ),
];
