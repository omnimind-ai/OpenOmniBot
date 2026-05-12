import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

class WorkbenchAnnotationStroke {
  const WorkbenchAnnotationStroke({
    required this.id,
    required this.points,
    required this.color,
    required this.strokeWidth,
  });

  final String id;
  final List<Offset> points;
  final Color color;
  final double strokeWidth;

  Rect get bounds => WorkbenchAnnotationGeometry.boundsFor(points);

  Map<String, Object?> toMap(Size canvasSize) {
    final sampledPoints = WorkbenchAnnotationGeometry.samplePoints(points);
    return {
      'id': id,
      'strokeWidth': _round(strokeWidth, digits: 2),
      'color': _colorToHex(color),
      'bounds': _rectToMap(bounds),
      'normalizedBounds': _normalizedRectToMap(bounds, canvasSize),
      'points': sampledPoints
          .map((point) => {'x': _round(point.dx), 'y': _round(point.dy)})
          .toList(growable: false),
    };
  }
}

class WorkbenchAnnotationPayload {
  WorkbenchAnnotationPayload({
    required this.strokes,
    required this.canvasSize,
    required this.prompt,
    DateTime? createdAt,
  }) : createdAt = createdAt ?? DateTime.now();

  final List<WorkbenchAnnotationStroke> strokes;
  final Size canvasSize;
  final String prompt;
  final DateTime createdAt;

  Rect? get selectedRegion {
    Rect? region;
    for (final stroke in strokes) {
      final bounds = stroke.bounds;
      if (bounds.isEmpty) continue;
      region = region == null ? bounds : region.expandToInclude(bounds);
    }
    return region == null ? null : _inflateWithin(region, 8, canvasSize);
  }

  Map<String, Object?> toFrontendContext({
    required String projectId,
    required String displayId,
    required String route,
    String source = 'workbench_annotation_canvas',
    Map<String, Object?> visibleState = const {},
  }) {
    final region = selectedRegion;
    return {
      'projectId': projectId,
      'displayId': displayId,
      'route': route,
      'source': source,
      'visibleState': visibleState,
      'annotationKind': 'transparent_red_line_canvas',
      'analysisOwner': 'vlm',
      'selectedRegion': region == null
          ? null
          : {
              ..._rectToMap(region),
              'normalized': _normalizedRectToMap(region, canvasSize),
            },
      'canvasSize': {
        'width': _round(canvasSize.width),
        'height': _round(canvasSize.height),
      },
      'drawingPaths': strokes
          .map((stroke) => stroke.toMap(canvasSize))
          .toList(growable: false),
      'annotationMeta': {
        'strokeCount': strokes.length,
        'strokeColor': strokes.isEmpty ? null : _colorToHex(strokes.last.color),
        'instruction':
            'Use the current screen screenshot plus these red annotation paths to infer the marked shape and target UI. Do not rely on client-side shape extraction.',
      },
      'prompt': prompt,
      'createdAt': createdAt.toIso8601String(),
    };
  }
}

class WorkbenchAnnotationGeometry {
  const WorkbenchAnnotationGeometry._();

  static const int maxWirePoints = 96;

  static Rect boundsFor(List<Offset> points) {
    if (points.isEmpty) return Rect.zero;
    var minX = points.first.dx;
    var maxX = points.first.dx;
    var minY = points.first.dy;
    var maxY = points.first.dy;
    for (final point in points.skip(1)) {
      minX = math.min(minX, point.dx);
      maxX = math.max(maxX, point.dx);
      minY = math.min(minY, point.dy);
      maxY = math.max(maxY, point.dy);
    }
    return Rect.fromLTRB(minX, minY, maxX, maxY);
  }

  static List<Offset> samplePoints(List<Offset> points) {
    if (points.length <= maxWirePoints) {
      return List<Offset>.unmodifiable(points);
    }
    final sampled = <Offset>[];
    final step = (points.length - 1) / (maxWirePoints - 1);
    for (var i = 0; i < maxWirePoints; i++) {
      final index = (i * step).round().clamp(0, points.length - 1).toInt();
      sampled.add(points[index]);
    }
    return List<Offset>.unmodifiable(sampled);
  }
}

class WorkbenchAnnotationOverlay extends StatefulWidget {
  const WorkbenchAnnotationOverlay({
    super.key,
    required this.child,
    required this.onSubmit,
    this.initialDrawingEnabled = true,
    this.toolbarBottomInset = 12,
    this.onClose,
    this.onPayloadPreview,
  });

  final Widget child;
  final Future<bool> Function(WorkbenchAnnotationPayload payload, String prompt)
  onSubmit;
  final bool initialDrawingEnabled;
  final double toolbarBottomInset;
  final VoidCallback? onClose;
  /// Called after every stroke, undo, or clear so the caller can pre-compute
  /// shape recognition while the user types their prompt.
  final void Function(WorkbenchAnnotationPayload previewPayload)? onPayloadPreview;

  @override
  State<WorkbenchAnnotationOverlay> createState() =>
      _WorkbenchAnnotationOverlayState();
}

class _WorkbenchAnnotationOverlayState
    extends State<WorkbenchAnnotationOverlay> {
  final TextEditingController _promptController = TextEditingController();
  final List<WorkbenchAnnotationStroke> _strokes = [];
  final List<Offset> _currentPoints = [];
  bool _drawingEnabled = true;
  bool _submitting = false;
  Size _canvasSize = Size.zero;
  int _nextStrokeId = 0;
  int? _activePointer;

  static const Color _strokeColor = Color(0xFFE13D56);
  static const double _strokeWidth = 4;

  @override
  void initState() {
    super.initState();
    _drawingEnabled = widget.initialDrawingEnabled;
  }

  @override
  void dispose() {
    _promptController.dispose();
    super.dispose();
  }

  void _startStroke(PointerDownEvent event) {
    if (_submitting) return;
    if (_activePointer != null) return;
    setState(() {
      _activePointer = event.pointer;
      _currentPoints
        ..clear()
        ..add(event.localPosition);
    });
  }

  void _appendStrokePoint(PointerMoveEvent event) {
    if (_submitting ||
        _activePointer != event.pointer ||
        _currentPoints.isEmpty) {
      return;
    }
    setState(() {
      _currentPoints.add(event.localPosition);
    });
  }

  void _finishStroke([PointerEvent? event]) {
    if (event != null && _activePointer != event.pointer) return;
    _activePointer = null;
    if (_submitting || _currentPoints.length < 2) {
      setState(_currentPoints.clear);
      return;
    }
    final points = List<Offset>.unmodifiable(_currentPoints);
    setState(() {
      _strokes.add(
        WorkbenchAnnotationStroke(
          id: 'stroke-${_nextStrokeId++}',
          points: points,
          color: _strokeColor,
          strokeWidth: _strokeWidth,
        ),
      );
      _currentPoints.clear();
    });
    _notifyPayloadPreview();
  }

  void _undo() {
    if (_strokes.isEmpty || _submitting) return;
    setState(() {
      _strokes.removeLast();
    });
    _notifyPayloadPreview();
  }

  void _clear() {
    if (_submitting || (_strokes.isEmpty && _currentPoints.isEmpty)) return;
    setState(() {
      _activePointer = null;
      _strokes.clear();
      _currentPoints.clear();
    });
    _notifyPayloadPreview();
  }

  void _notifyPayloadPreview() {
    final preview = widget.onPayloadPreview;
    if (preview == null || _strokes.isEmpty) return;
    preview(
      WorkbenchAnnotationPayload(
        strokes: List<WorkbenchAnnotationStroke>.unmodifiable(_strokes),
        canvasSize: _canvasSize,
        prompt: '',
      ),
    );
  }

  Future<void> _submit() async {
    if (_submitting) return;
    if (_strokes.isEmpty) {
      showToast(
        context.l10n.workbenchAnnotationNoStrokes,
        type: ToastType.warning,
      );
      return;
    }
    final fallbackPrompt = context.l10n.workbenchAnnotationDefaultPrompt;
    final prompt = _promptController.text.trim().isEmpty
        ? fallbackPrompt
        : _promptController.text.trim();
    final payload = WorkbenchAnnotationPayload(
      strokes: List<WorkbenchAnnotationStroke>.unmodifiable(_strokes),
      canvasSize: _canvasSize,
      prompt: prompt,
    );
    setState(() => _submitting = true);
    final success = await widget.onSubmit(payload, prompt);
    if (!mounted) return;
    setState(() {
      _submitting = false;
      if (success) {
        _strokes.clear();
        _currentPoints.clear();
        _promptController.clear();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        _canvasSize = Size(
          constraints.maxWidth.isFinite ? constraints.maxWidth : 0,
          constraints.maxHeight.isFinite ? constraints.maxHeight : 0,
        );
        return Stack(
          fit: StackFit.expand,
          children: [
            widget.child,
            Positioned.fill(
              child: IgnorePointer(
                ignoring: !_drawingEnabled || _submitting,
                child: RawGestureDetector(
                  behavior: HitTestBehavior.translucent,
                  gestures: {
                    EagerGestureRecognizer:
                        GestureRecognizerFactoryWithHandlers<
                          EagerGestureRecognizer
                        >(EagerGestureRecognizer.new, (_) {}),
                  },
                  child: Listener(
                    behavior: HitTestBehavior.translucent,
                    onPointerDown: _startStroke,
                    onPointerMove: _appendStrokePoint,
                    onPointerUp: _finishStroke,
                    onPointerCancel: _finishStroke,
                    child: CustomPaint(
                      painter: WorkbenchAnnotationPainter(
                        strokes: _strokes,
                        currentPoints: _currentPoints,
                        currentColor: _strokeColor,
                        currentStrokeWidth: _strokeWidth,
                        drawingEnabled: _drawingEnabled,
                      ),
                      child: const SizedBox.expand(),
                    ),
                  ),
                ),
              ),
            ),
            Positioned(
              left: 12,
              right: 12,
              bottom: widget.toolbarBottomInset +
                  MediaQuery.of(context).viewInsets.bottom,
              child: _buildToolbar(),
            ),
          ],
        );
      },
    );
  }

  Widget _buildToolbar() {
    final palette = context.omniPalette;
    final shapeCount = _strokes.length;
    return Material(
      color: Colors.transparent,
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: palette.surfacePrimary.withValues(alpha: 0.96),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor,
              blurRadius: 18,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.draw_outlined, color: palette.accentPrimary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    context.l10n.workbenchAnnotationTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 13,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Flexible(
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: _buildAnnotationBadge(shapeCount),
                  ),
                ),
                const SizedBox(width: 8),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: _drawingEnabled
                          ? context.l10n.workbenchAnnotationBrowseMode
                          : context.l10n.workbenchAnnotationDrawMode,
                      visualDensity: VisualDensity.compact,
                      constraints: const BoxConstraints.tightFor(
                        width: 34,
                        height: 34,
                      ),
                      padding: EdgeInsets.zero,
                      onPressed: _submitting
                          ? null
                          : () => setState(() {
                              _drawingEnabled = !_drawingEnabled;
                            }),
                      icon: Icon(
                        _drawingEnabled
                            ? Icons.pan_tool_alt_outlined
                            : Icons.edit_outlined,
                        size: 20,
                      ),
                    ),
                    IconButton(
                      tooltip: context.l10n.workbenchAnnotationUndo,
                      visualDensity: VisualDensity.compact,
                      constraints: const BoxConstraints.tightFor(
                        width: 34,
                        height: 34,
                      ),
                      padding: EdgeInsets.zero,
                      onPressed: _strokes.isEmpty || _submitting ? null : _undo,
                      icon: const Icon(Icons.undo_rounded, size: 20),
                    ),
                    IconButton(
                      tooltip: context.l10n.workbenchAnnotationClear,
                      visualDensity: VisualDensity.compact,
                      constraints: const BoxConstraints.tightFor(
                        width: 34,
                        height: 34,
                      ),
                      padding: EdgeInsets.zero,
                      onPressed:
                          (_strokes.isEmpty && _currentPoints.isEmpty) ||
                              _submitting
                          ? null
                          : _clear,
                      icon: const Icon(Icons.delete_sweep_outlined, size: 20),
                    ),
                    if (widget.onClose != null)
                      IconButton(
                        tooltip: MaterialLocalizations.of(
                          context,
                        ).closeButtonTooltip,
                        visualDensity: VisualDensity.compact,
                        constraints: const BoxConstraints.tightFor(
                          width: 34,
                          height: 34,
                        ),
                        padding: EdgeInsets.zero,
                        onPressed: _submitting ? null : widget.onClose,
                        icon: const Icon(Icons.close_rounded, size: 20),
                      ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: _promptController,
                    minLines: 1,
                    maxLines: 2,
                    enabled: !_submitting,
                    decoration: InputDecoration(
                      hintText: context.l10n.workbenchAnnotationPromptHint,
                      isDense: true,
                      filled: true,
                      fillColor: palette.surfaceSecondary,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide.none,
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 9,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.icon(
                  onPressed: _submitting ? null : _submit,
                  icon: _submitting
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.auto_fix_high_rounded, size: 18),
                  label: Text(
                    _submitting
                        ? context.l10n.workbenchAnnotationApplying
                        : context.l10n.workbenchAnnotationApply,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAnnotationBadge(int strokeCount) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Text(
        strokeCount == 0
            ? context.l10n.workbenchAnnotationNoShape
            : context.l10n.workbenchAnnotationShapeCount(strokeCount),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class WorkbenchAnnotationPainter extends CustomPainter {
  const WorkbenchAnnotationPainter({
    required this.strokes,
    required this.currentPoints,
    required this.currentColor,
    required this.currentStrokeWidth,
    required this.drawingEnabled,
  });

  final List<WorkbenchAnnotationStroke> strokes;
  final List<Offset> currentPoints;
  final Color currentColor;
  final double currentStrokeWidth;
  final bool drawingEnabled;

  @override
  void paint(Canvas canvas, Size size) {
    for (final stroke in strokes) {
      _drawPolyline(canvas, stroke.points, stroke.color, stroke.strokeWidth);
    }
    _drawPolyline(canvas, currentPoints, currentColor, currentStrokeWidth);
  }

  void _drawPolyline(
    Canvas canvas,
    List<Offset> points,
    Color color,
    double strokeWidth,
  ) {
    if (points.length < 2) return;
    final path = Path()..moveTo(points.first.dx, points.first.dy);
    for (final point in points.skip(1)) {
      path.lineTo(point.dx, point.dy);
    }
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant WorkbenchAnnotationPainter oldDelegate) {
    return true;
  }
}

double _round(double value, {int digits = 1}) {
  if (!value.isFinite) return 0;
  final factor = math.pow(10, digits).toDouble();
  return (value * factor).round() / factor;
}

Rect _inflateWithin(Rect rect, double delta, Size size) {
  final inflated = rect.inflate(delta);
  if (size.width <= 0 || size.height <= 0) return inflated;
  return Rect.fromLTRB(
    inflated.left.clamp(0, size.width).toDouble(),
    inflated.top.clamp(0, size.height).toDouble(),
    inflated.right.clamp(0, size.width).toDouble(),
    inflated.bottom.clamp(0, size.height).toDouble(),
  );
}

Map<String, Object?> _rectToMap(Rect rect) {
  return {
    'x': _round(rect.left),
    'y': _round(rect.top),
    'width': _round(rect.width),
    'height': _round(rect.height),
  };
}

Map<String, Object?> _normalizedRectToMap(Rect rect, Size canvasSize) {
  final width = math.max(canvasSize.width, 1);
  final height = math.max(canvasSize.height, 1);
  return {
    'x': _round(rect.left / width, digits: 4),
    'y': _round(rect.top / height, digits: 4),
    'width': _round(rect.width / width, digits: 4),
    'height': _round(rect.height / height, digits: 4),
  };
}

String _colorToHex(Color color) {
  final value = color.toARGB32();
  return '#${value.toRadixString(16).padLeft(8, '0').toUpperCase()}';
}
