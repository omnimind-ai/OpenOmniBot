import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

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

  testWidgets('records pointer strokes and submits annotation payload', (
    tester,
  ) async {
    WorkbenchAnnotationPayload? submittedPayload;
    String? submittedPrompt;

    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SizedBox(
            width: 360,
            height: 420,
            child: WorkbenchAnnotationOverlay(
              onSubmit: (payload, prompt) async {
                submittedPayload = payload;
                submittedPrompt = prompt;
                return true;
              },
              child: const ColoredBox(color: Colors.white),
            ),
          ),
        ),
      ),
    );

    await tester.dragFrom(const Offset(48, 48), const Offset(96, 72));
    await tester.pump();

    await tester.tap(find.byIcon(Icons.auto_fix_high_rounded));
    await tester.pump();

    expect(submittedPayload, isNotNull);
    expect(submittedPayload!.strokes, hasLength(1));
    expect(submittedPayload!.strokes.single.points.length, greaterThan(1));
    expect(submittedPayload!.canvasSize, const Size(360, 420));
    expect(submittedPrompt, isNotEmpty);
  });
}
