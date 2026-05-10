import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/local_model/local_model_feature.dart';
import 'package:ui/features/local_model/local_model_feature_omniinfer.dart';
import 'package:ui/features/local_model/local_model_feature_standard.dart';

void main() {
  setUp(configureStandardLocalModelFeature);
  tearDown(configureStandardLocalModelFeature);

  test('standard edition local model feature is a no-op', () async {
    expect(localModelFeature.enabled, isFalse);
    expect(
      localModelFeature.isBuiltinLocalProvider('omniinfer-local'),
      isFalse,
    );
    expect(await localModelFeature.findInstalledRecommendedModelId(), isNull);
    expect(
      await localModelFeature.preloadModelIfNeeded(
        providerProfileId: 'omniinfer-local',
        modelId: 'Qwen3',
      ),
      isNull,
    );
  });

  test('omniinfer edition recognizes builtin local provider ids', () {
    configureOmniinferLocalModelFeature();

    expect(localModelFeature.enabled, isTrue);
    expect(localModelFeature.isBuiltinLocalProvider('omniinfer-local'), isTrue);
    expect(localModelFeature.isBuiltinLocalProvider('mnn-local'), isTrue);
    expect(localModelFeature.isBuiltinLocalProvider('profile-1'), isFalse);
  });
}
