import 'package:flutter/widgets.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';
import 'package:ui/features/workbench/widgets/workbench_layout_profile.dart';

String workbenchRouteForDisplay(
  WorkbenchProject project,
  WorkbenchDisplaySpec display, {
  String fallbackRoute = '/workbench/project',
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
  required Map<String, Object?> themeProfile,
  required WorkbenchProject project,
  required WorkbenchDisplaySpec display,
  required WorkbenchAnnotationPayload payload,
  required String prompt,
  String source = 'workbench_annotation_canvas',
  String fallbackRoute = '/workbench/project',
}) {
  return {
    ...payload.toFrontendContext(
      projectId: project.projectId,
      displayId: display.id,
      route: workbenchRouteForDisplay(
        project,
        display,
        fallbackRoute: fallbackRoute,
      ),
      source: source,
      visibleState: _visibleStateForProject(project, display, themeProfile),
    ),
    ...themeProfile,
  };
}

Map<String, Object?> buildWorkbenchVisibleFrontendContext({
  required BuildContext context,
  required WorkbenchProject project,
  required WorkbenchDisplaySpec display,
  String source = 'workbench_flutter_display',
  String fallbackRoute = '/workbench/project',
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
    ...buildWorkbenchThemeProfile(context),
    'visibleState': {
      ..._visibleStateForProject(
        project,
        display,
        buildWorkbenchThemeProfile(context),
      ),
      ...extraVisibleState,
    },
  };
}

Map<String, Object?> _visibleStateForProject(
  WorkbenchProject project,
  WorkbenchDisplaySpec display,
  Map<String, Object?> themeProfile,
) {
  final common = <String, Object?>{
    'projectName': project.name,
    'displayTitle': display.label,
    'displayKind': display.kind,
    'displayRoute': display.route,
    ...themeProfile,
    'hasMarkdown': project.frontendMarkdown.isNotEmpty,
    'markdownEntryFile': project.frontendMarkdown['entryFile']?.toString(),
    'apiIds': project.tools.map((tool) => tool.id).toList(growable: false),
  };
  return {
    ...common,
    'entityName':
        project.pageSpec['entityName']?.toString().trim().isNotEmpty == true
        ? project.pageSpec['entityName'].toString()
        : 'item',
    'description': project.pageSpec['description']?.toString() ?? '',
    'activeItemCount': project.activeItems.length,
    'archivedItemCount': project.archivedItems.length,
    'itemTitles': project.items
        .take(8)
        .map((item) => item.title)
        .toList(growable: false),
    'pageSpecKeys': project.pageSpec.keys.toList(growable: false),
  };
}
