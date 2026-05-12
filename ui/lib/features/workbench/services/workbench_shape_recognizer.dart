import 'package:flutter/material.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';

/// Result of annotation analysis: DOM hit-test point + optional matched element.
class WorkbenchAnnotationTarget {
  const WorkbenchAnnotationTarget({
    required this.hitTestPoint,
    required this.normalizedHitTestPoint,
    this.hitElement,
  });

  final Offset hitTestPoint;
  final Offset normalizedHitTestPoint;
  final WorkbenchHitElement? hitElement;

  Map<String, Object?> toMap() => {
    'hitTestPoint': {'x': hitTestPoint.dx, 'y': hitTestPoint.dy},
    'normalizedHitTestPoint': {
      'x': normalizedHitTestPoint.dx,
      'y': normalizedHitTestPoint.dy,
    },
    if (hitElement != null) ...hitElement!.toMap(),
  };

  /// Natural-language hint for the agent about which element was targeted.
  String toAnnotationDescription() {
    final el = hitElement;
    if (el == null || (el.oobId.isEmpty && el.tagName.isEmpty)) {
      final nx = normalizedHitTestPoint.dx.toStringAsFixed(2);
      final ny = normalizedHitTestPoint.dy.toStringAsFixed(2);
      return 'User annotated the area at normalized position ($nx, $ny) on screen.';
    }
    final tag = el.tagName.isNotEmpty ? el.tagName : 'element';
    final label = el.text.isNotEmpty ? " labeled '${el.text}'" : '';
    final id = el.oobId.isNotEmpty ? ' (data-oob-id: ${el.oobId})' : '';
    return 'User annotated the $tag$label$id.';
  }
}

class WorkbenchHitElement {
  const WorkbenchHitElement({
    required this.oobId,
    required this.tagName,
    required this.text,
    required this.className,
    this.outerHtml = '',
  });

  final String oobId;
  final String tagName;
  final String text;
  final String className;
  final String outerHtml;

  Map<String, Object?> toMap() => {
    if (oobId.isNotEmpty) 'oobId': oobId,
    'tagName': tagName,
    if (text.isNotEmpty) 'elementText': text,
    if (className.isNotEmpty) 'elementClass': className,
    if (outerHtml.isNotEmpty) 'outerHtml': outerHtml,
  };
}

/// Singleton bridge: WorkbenchHtmlDisplayPage registers its hit-test handler
/// here so annotation submitters can call it without a direct reference.
class WorkbenchHtmlHitTestBridge {
  WorkbenchHtmlHitTestBridge._();

  static Future<WorkbenchHitElement?> Function(Offset point)? _handler;

  static void register(Future<WorkbenchHitElement?> Function(Offset) handler) {
    _handler = handler;
  }

  static void unregister() {
    _handler = null;
  }

  static Future<WorkbenchHitElement?> hitTest(Offset point) async {
    try {
      return await _handler?.call(point);
    } catch (_) {
      return null;
    }
  }
}

/// Resolves the annotation target for a payload using only DOM hit-test.
/// Geometry heuristic determines the best hit-test point (circle center
/// or arrow tip); the WebView resolves it to a concrete DOM element.
///
/// This is fast (~10 ms). Shape classification is left to VLM via the
/// composite screenshot that the caller attaches separately.
Future<WorkbenchAnnotationTarget> resolveAnnotationTarget(
  WorkbenchAnnotationPayload payload,
) async {
  final strokes = payload.strokes;
  final canvasSize = payload.canvasSize;
  final region = payload.selectedRegion;

  // Geometry heuristic: closed loop → use center; open stroke → use tip.
  final hitPoint = _resolveHitPoint(strokes: strokes, region: region);

  final w = canvasSize.width.clamp(1.0, double.infinity);
  final h = canvasSize.height.clamp(1.0, double.infinity);
  final normalizedPoint = Offset(hitPoint.dx / w, hitPoint.dy / h);

  final hitElement = await WorkbenchHtmlHitTestBridge.hitTest(hitPoint);

  return WorkbenchAnnotationTarget(
    hitTestPoint: hitPoint,
    normalizedHitTestPoint: normalizedPoint,
    hitElement: hitElement,
  );
}

Offset _resolveHitPoint({
  required List<WorkbenchAnnotationStroke> strokes,
  required Rect? region,
}) {
  if (strokes.isEmpty) return region?.center ?? Offset.zero;

  final stroke = strokes.first;
  final points = stroke.points;
  if (points.length < 3) return region?.center ?? Offset.zero;

  final start = points.first;
  final end = points.last;
  var arcLen = 0.0;
  for (var i = 1; i < points.length; i++) {
    arcLen += (points[i] - points[i - 1]).distance;
  }
  final closureRatio = arcLen < 1 ? 0.0 : (end - start).distance / arcLen;

  // Closed loop (circle/rectangle) → center; open stroke (arrow) → tip.
  if (closureRatio < 0.3) {
    return region?.center ?? Offset.zero;
  } else {
    final lastPoints = strokes.last.points;
    return lastPoints.isNotEmpty ? lastPoints.last : (region?.center ?? Offset.zero);
  }
}
