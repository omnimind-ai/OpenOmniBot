import 'package:flutter/widgets.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';

Map<String, Object?> buildWorkbenchLayoutProfile({
  required BuildContext context,
  required BoxConstraints constraints,
  required String source,
  WorkbenchProject? project,
  WorkbenchDisplaySpec? display,
  String? route,
}) {
  final media = MediaQuery.of(context);
  final viewportWidth = _finitePositive(
    constraints.maxWidth,
    fallback: media.size.width,
  );
  final viewportHeight = _finitePositive(
    constraints.maxHeight,
    fallback: media.size.height,
  );
  return <String, Object?>{
    'source': source,
    'viewportWidthDp': _roundOne(viewportWidth),
    'viewportHeightDp': _roundOne(viewportHeight),
    'screenWidthDp': _roundOne(media.size.width),
    'screenHeightDp': _roundOne(media.size.height),
    'devicePixelRatio': _roundOne(media.devicePixelRatio),
    'orientation': viewportWidth <= viewportHeight ? 'portrait' : 'landscape',
    'safeAreaTopDp': _roundOne(media.padding.top),
    'safeAreaRightDp': _roundOne(media.padding.right),
    'safeAreaBottomDp': _roundOne(media.padding.bottom),
    'safeAreaLeftDp': _roundOne(media.padding.left),
    if (project != null) 'projectId': project.projectId,
    if (display != null) 'displayId': display.id,
    if (route != null && route.trim().isNotEmpty) 'route': route.trim(),
    'measuredAtMillis': DateTime.now().millisecondsSinceEpoch,
  };
}

bool workbenchLayoutProfilesEquivalent(
  Map<String, Object?>? a,
  Map<String, Object?> b,
) {
  if (a == null) return false;
  const keys = <String>[
    'source',
    'projectId',
    'displayId',
    'route',
    'viewportWidthDp',
    'viewportHeightDp',
    'screenWidthDp',
    'screenHeightDp',
    'orientation',
  ];
  for (final key in keys) {
    if (a[key] != b[key]) return false;
  }
  return true;
}

double _finitePositive(double value, {required double fallback}) {
  if (value.isFinite && value > 0) return value;
  if (fallback.isFinite && fallback > 0) return fallback;
  return 0;
}

double _roundOne(double value) => (value * 10).round() / 10;
