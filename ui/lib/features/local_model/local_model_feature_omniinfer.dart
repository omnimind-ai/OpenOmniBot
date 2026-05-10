import 'package:ui/features/local_model/local_model_feature.dart';
import 'package:ui/services/mnn_local_models_service.dart';

class _OmniinferLocalModelFeature extends LocalModelFeature {
  static const String _builtinProfileId = 'omniinfer-local';
  static const String _legacyBuiltinProfileId = 'mnn-local';
  static const String _recommendedBackend = 'litert';
  static const String _recommendedModelId = 'gemma-4-E2B-it';

  @override
  bool get enabled => true;

  @override
  bool isBuiltinLocalProvider(String? profileId) {
    final normalized = profileId?.trim();
    return normalized == _builtinProfileId ||
        normalized == _legacyBuiltinProfileId;
  }

  @override
  Future<String?> findInstalledRecommendedModelId() async {
    await MnnLocalModelsService.setBackend(_recommendedBackend);
    final installed = await MnnLocalModelsService.listInstalledModels();
    for (final model in installed) {
      final normalized = model.id.trim().toLowerCase();
      if (normalized == _recommendedModelId.toLowerCase()) {
        return model.id;
      }
    }
    return null;
  }

  @override
  Future<Map<String, dynamic>?> preloadModelIfNeeded({
    required String providerProfileId,
    required String modelId,
  }) async {
    if (!isBuiltinLocalProvider(providerProfileId)) {
      return null;
    }
    final result = await MnnLocalModelsService.preloadModel(modelId: modelId);
    return result?.cast<String, dynamic>();
  }
}

void configureOmniinferLocalModelFeature() {
  setLocalModelFeature(_OmniinferLocalModelFeature());
}
