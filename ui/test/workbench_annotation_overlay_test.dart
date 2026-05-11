import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';

void main() {
  test('computes bounds for a red annotation stroke', () {
    final points = <Offset>[
      const Offset(12, 12),
      const Offset(180, 12),
      const Offset(180, 110),
      const Offset(12, 110),
      const Offset(12, 12),
    ];

    expect(
      WorkbenchAnnotationGeometry.boundsFor(points),
      const Rect.fromLTRB(12, 12, 180, 110),
    );
  });

  test('builds frontend context with raw drawing paths for VLM analysis', () {
    final payload = WorkbenchAnnotationPayload(
      strokes: [
        WorkbenchAnnotationStroke(
          id: 'stroke-1',
          points: const [
            Offset(10, 10),
            Offset(120, 10),
            Offset(120, 80),
            Offset(10, 80),
            Offset(10, 10),
          ],
          color: const Color(0xFFE13D56),
          strokeWidth: 4,
        ),
      ],
      canvasSize: const Size(240, 160),
      prompt: '把这里改成主按钮',
    );

    final context = payload.toFrontendContext(
      projectId: 'demo-project',
      displayId: 'demo-display',
      route: '/workbench/project?projectId=demo-project',
      visibleState: const {'renderer': 'oob_project_display'},
    );

    expect(context['projectId'], 'demo-project');
    expect(context['drawingPaths'], isA<List>());
    expect(context['drawingShapes'], isNull);
    expect(context['sketchExtraction'], isNull);
    expect(context['analysisOwner'], 'vlm');
    expect(context['annotationMeta'], isA<Map>());
    expect(context['selectedRegion'], isA<Map>());
  });
}
