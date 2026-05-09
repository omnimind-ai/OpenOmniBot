import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/pages/workbench_project_mode_page.dart';
import 'package:ui/features/workbench/pages/workbench_schema_project_page.dart';
import 'package:ui/features/workbench/pages/workbench_todo_log_page.dart';

List<GoRoute> workbenchRoutes = [
  GoRoute(
    path: '/workbench/projects',
    name: 'workbench/projects',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'workbench/projects',
      child: WorkbenchProjectModePage(
        initialProjectId: state.uri.queryParameters['projectId'],
      ),
    ),
  ),
  GoRoute(
    path: '/workbench/schema_app',
    name: 'workbench/schema_app',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'workbench/schema_app',
      child: WorkbenchSchemaProjectPage(
        projectId: state.uri.queryParameters['projectId'],
        displayId: state.uri.queryParameters['displayId'],
        returnTo: state.uri.queryParameters['returnTo'],
      ),
    ),
  ),
  GoRoute(
    path: '/workbench/todo_log',
    name: 'workbench/todo_log',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'workbench/todo_log',
      child: WorkbenchTodoLogPage(
        projectId: state.uri.queryParameters['projectId'],
        displayId: state.uri.queryParameters['displayId'],
        returnTo: state.uri.queryParameters['returnTo'],
        debugMode:
            state.uri.queryParameters['debug'] == '1' ||
            state.uri.queryParameters['debug'] == 'true',
      ),
    ),
  ),
];
