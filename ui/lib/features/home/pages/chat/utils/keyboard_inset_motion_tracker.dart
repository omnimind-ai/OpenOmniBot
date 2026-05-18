import 'dart:math' as math;

class KeyboardInsetMotionTracker {
  static const double defaultVisibleInsetThreshold = 0.5;
  static const double defaultMotionEpsilon = 1.0;

  final double visibleInsetThreshold;
  final double motionEpsilon;

  double _lastInset = 0;
  bool _hasSample = false;
  bool _shouldLift = false;

  KeyboardInsetMotionTracker({
    this.visibleInsetThreshold = defaultVisibleInsetThreshold,
    this.motionEpsilon = defaultMotionEpsilon,
  });

  bool get hasSample => _hasSample;

  bool get shouldLift => _shouldLift;

  bool update(double bottomInset) {
    final normalizedInset = bottomInset.isFinite
        ? math.max(0.0, bottomInset)
        : 0.0;
    final previousInset = _lastInset;
    final hadSample = _hasSample;
    final nextShouldLift = _resolveShouldLift(
      normalizedInset: normalizedInset,
      previousInset: previousInset,
      hadSample: hadSample,
    );

    _lastInset = normalizedInset;
    _hasSample = true;
    if (_shouldLift == nextShouldLift) {
      return false;
    }
    _shouldLift = nextShouldLift;
    return true;
  }

  bool resolveForBuild(double bottomInset) {
    if (!_hasSample) {
      update(bottomInset);
    }
    return _shouldLift;
  }

  bool _resolveShouldLift({
    required double normalizedInset,
    required double previousInset,
    required bool hadSample,
  }) {
    if (normalizedInset <= visibleInsetThreshold) {
      return false;
    }
    if (!hadSample || previousInset <= visibleInsetThreshold) {
      return true;
    }
    if (normalizedInset > previousInset + motionEpsilon) {
      return true;
    }
    if (normalizedInset < previousInset - motionEpsilon) {
      return false;
    }
    return _shouldLift;
  }
}
