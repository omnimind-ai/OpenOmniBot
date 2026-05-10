import 'package:ui/features/local_model/local_model_feature.dart';

class _StandardLocalModelFeature extends LocalModelFeature {
  @override
  bool get enabled => false;
}

void configureStandardLocalModelFeature() {
  setLocalModelFeature(_StandardLocalModelFeature());
}
