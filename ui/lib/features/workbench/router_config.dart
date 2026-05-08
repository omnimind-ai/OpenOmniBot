import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/pages/workbench_project_mode_page.dart';
import 'package:ui/features/workbench/pages/workbench_todo_log_page.dart';

List<GoRoute> workbenchRoutes = [
  GoRoute(
    path: '/workbench/projects',
    name: 'workbench/projects',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'workbench/projects',
      child: const WorkbenchProjectModePage(),
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
      ),
    ),
  ),
];
