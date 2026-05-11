import 'package:go_router/go_router.dart';
import 'package:ui/features/workbench/pages/workbench_flutter_eval_page.dart';
import 'package:ui/features/workbench/pages/workbench_html_display_page.dart';
import 'package:ui/features/workbench/pages/workbench_project_display_page.dart';
import 'package:ui/features/workbench/pages/workbench_project_mode_page.dart';

List<GoRoute> workbenchRoutes = [
  GoRoute(
    path: '/workbench/projects',
    name: 'workbench/projects',
    pageBuilder: (context, state) => NoTransitionPage(
      key: state.pageKey,
      name: 'workbench/projects',
      child: WorkbenchProjectModePage(
        initialProjectId: state.uri.queryParameters['projectId'],
      ),
    ),
  ),
  GoRoute(
    path: '/workbench/project',
    name: 'workbench/project',
    pageBuilder: (context, state) => NoTransitionPage(
      key: state.pageKey,
      name: 'workbench/project',
      child: WorkbenchProjectDisplayPage(
        projectId: state.uri.queryParameters['projectId'],
        displayId: state.uri.queryParameters['displayId'],
        returnTo: state.uri.queryParameters['returnTo'],
        debugMode:
            state.uri.queryParameters['debug'] == '1' ||
            state.uri.queryParameters['debug'] == 'true',
      ),
    ),
  ),
  GoRoute(
    path: '/workbench/html',
    name: 'workbench/html',
    pageBuilder: (context, state) => NoTransitionPage(
      key: state.pageKey,
      name: 'workbench/html',
      child: WorkbenchHtmlDisplayPage(
        projectId: state.uri.queryParameters['projectId'],
        displayId: state.uri.queryParameters['displayId'],
        returnTo: state.uri.queryParameters['returnTo'],
      ),
    ),
  ),
  GoRoute(
    path: '/workbench/flutter_eval',
    name: 'workbench/flutter_eval',
    pageBuilder: (context, state) => NoTransitionPage(
      key: state.pageKey,
      name: 'workbench/flutter_eval',
      child: WorkbenchFlutterEvalPage(
        projectId: state.uri.queryParameters['projectId'],
        displayId: state.uri.queryParameters['displayId'],
        returnTo: state.uri.queryParameters['returnTo'],
      ),
    ),
  ),
];
