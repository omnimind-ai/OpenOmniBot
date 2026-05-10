import 'package:go_router/go_router.dart';
import 'pages/task_center/task_center_page.dart';
import 'pages/task_edit/task_edit_page.dart';
import 'pages/task_history/task_execution_history_page.dart';
import 'pages/execution_history/trajectory_page.dart';
import 'pages/execution_history/task_execution_detail_page.dart';
import 'pages/scheduled_tasks/scheduled_task_list_page.dart';
import 'pages/task_modify/task_modify_page.dart';

/// Task模块路由配置
List<GoRoute> taskRoutes = [
  // 任务中心页
  GoRoute(
    path: '/task/task_center',
    name: 'task/task_center',
    builder: (context, state) => const TaskCenterPage(),
  ),

  // 任务编辑页
  GoRoute(
    path: '/task/task_edit/:taskId',
    name: 'task/task_edit',
    builder: (context, state) {
      final taskId = state.pathParameters['taskId'] ?? '';
      return TaskEditPage(taskId: taskId);
    },
  ),
  // 任务执行历史页(新)
  GoRoute(
    path: '/task/execution_history',
    name: 'task/execution_history',
    builder: (context, state) => TrajectoryPage(),
  ),
  // 定时任务列表页
  GoRoute(
    path: '/task/scheduled_tasks',
    name: 'task/scheduled_tasks',
    builder: (context, state) =>
        ScheduledTaskListPage(initialTab: state.uri.queryParameters['tab']),
  ),
  // 任务执行记录详情页
  GoRoute(
    path: '/task/execution_detail',
    name: 'task/execution_detail',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return TaskExecutionDetailPage(
        params: TaskExecutionDetailParams.fromMap(params),
      );
    },
  ),
  // 任务执行历史页
  GoRoute(
    path: '/task/task_history',
    name: 'task/task_history',
    builder: (context, state) => const TaskExecutionHistoryPage(),
  ),

  // 任务修改页
  GoRoute(
    path: '/task/task_modify',
    name: 'task/task_modify',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return TaskModifyPage(
        taskId: params?['taskId'],
        type: params?['type'],
        title: params?['title'],
        payload: params?['payload'],
      );
    },
  ),
];
