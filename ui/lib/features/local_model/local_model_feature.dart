abstract class LocalModelFeature {
  bool get enabled;

  bool isBuiltinLocalProvider(String? profileId) {
    return false;
  }

  Future<String?> findInstalledRecommendedModelId() async {
    return null;
  }

  Future<Map<String, dynamic>?> preloadModelIfNeeded({
    required String providerProfileId,
    required String modelId,
  }) async {
    return null;
  }
}

class _DisabledLocalModelFeature extends LocalModelFeature {
  @override
  bool get enabled => false;
}

LocalModelFeature _localModelFeature = _DisabledLocalModelFeature();

LocalModelFeature get localModelFeature => _localModelFeature;

void setLocalModelFeature(LocalModelFeature feature) {
  _localModelFeature = feature;
}
