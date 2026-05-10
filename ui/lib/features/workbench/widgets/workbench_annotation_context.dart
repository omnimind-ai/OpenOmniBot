import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_todo_log_service.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';

String workbenchRouteForDisplay(
  WorkbenchProject project,
  WorkbenchDisplaySpec display, {
  String fallbackRoute = '/workbench/quick_capture',
}) {
  final route = display.route.trim().isEmpty
      ? project.route.trim()
      : display.route.trim();
  if (route.isNotEmpty) {
    return route;
  }
  return '$fallbackRoute?projectId=${Uri.encodeQueryComponent(project.projectId)}';
}

Map<String, Object?> buildWorkbenchAnnotationFrontendContext({
  required WorkbenchProject project,
  required WorkbenchDisplaySpec display,
  required WorkbenchAnnotationPayload payload,
  required String prompt,
  String source = 'workbench_annotation_canvas',
  String fallbackRoute = '/workbench/quick_capture',
}) {
  return payload.toFrontendContext(
    projectId: project.projectId,
    displayId: display.id,
    route: workbenchRouteForDisplay(
      project,
      display,
      fallbackRoute: fallbackRoute,
    ),
    source: source,
    visibleState: _visibleStateForProject(project, display),
  );
}

Map<String, Object?> buildWorkbenchVisibleFrontendContext({
  required WorkbenchProject project,
  required WorkbenchDisplaySpec display,
  String source = 'workbench_flutter_display',
  String fallbackRoute = '/workbench/quick_capture',
  Map<String, Object?> extraVisibleState = const {},
}) {
  return {
    'projectId': project.projectId,
    'displayId': display.id,
    'route': workbenchRouteForDisplay(
      project,
      display,
      fallbackRoute: fallbackRoute,
    ),
    'source': source,
    'visibleState': {
      ..._visibleStateForProject(project, display),
      ...extraVisibleState,
    },
  };
}

Map<String, Object?> _visibleStateForProject(
  WorkbenchProject project,
  WorkbenchDisplaySpec display,
) {
  final common = <String, Object?>{
    'templateId': project.templateId,
    'projectName': project.name,
    'displayTitle': display.label,
    'displayKind': display.kind,
    'displayRoute': display.route,
    'apiIds': project.tools.map((tool) => tool.id).toList(growable: false),
  };
  if (project.templateId == workbenchTodoTemplateId ||
      display.route.startsWith('/workbench/todo_log')) {
    return {
      ...common,
      'openTodoCount': project.openTodos.length,
      'finishedTodoCount': project.finishedTodos.length,
      'todoTitles': project.todos
          .take(8)
          .map((todo) => todo.title)
          .toList(growable: false),
    };
  }
  if (project.templateId == 'schema_app' ||
      display.route.startsWith('/workbench/schema_app')) {
    return {
      ...common,
      'entityName':
          project.schema['entityName']?.toString().trim().isNotEmpty == true
          ? project.schema['entityName'].toString()
          : 'item',
      'description': project.schema['description']?.toString() ?? '',
      'activeItemCount': project.activeItems.length,
      'archivedItemCount': project.archivedItems.length,
      'itemTitles': project.items
          .take(8)
          .map((item) => item.title)
          .toList(growable: false),
      'schemaKeys': project.schema.keys.toList(growable: false),
    };
  }
  if (project.templateId == workbenchQuickCaptureTemplateId ||
      display.route.startsWith('/workbench/quick_capture')) {
    return {
      ...common,
      'activeItemCount': project.activeCaptureItems.length,
      'archivedItemCount': project.archivedCaptureItems.length,
      'itemTitles': project.captureItems
          .take(8)
          .map((item) => item.title)
          .toList(growable: false),
    };
  }
  return common;
}
