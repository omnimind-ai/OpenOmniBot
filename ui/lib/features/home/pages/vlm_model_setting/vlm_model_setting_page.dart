import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

const String _kArrowBigDownSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M15 11a1 1 0 0 0 1 1h2.939a1 1 0 0 1 .75 1.811l-6.835 6.836a1.207 1.207 0 0 1-1.707 0L4.31 13.81a1 1 0 0 1 .75-1.811H8a1 1 0 0 0 1-1V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1z"/>
</svg>
''';

const String _kPlusSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M5 12h14"/>
  <path d="M12 5v14"/>
</svg>
''';

const String _kPackageSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z"/>
  <path d="M12 22V12"/>
  <polyline points="3.29 7 12 12 20.71 7"/>
  <path d="m7.5 4.27 9 5.15"/>
</svg>
''';

const String _kInputImageSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-image-icon lucide-image"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>
''';

const String _kInputTextSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-file-text-icon lucide-file-text"><path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/></svg>
''';

const String _kInputPdfSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-file-icon lucide-file"><path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/></svg>
''';

const String _kReasoningSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.524 5.77 4 4 0 0 0 1.07 6.046A3.5 3.5 0 0 0 12 18.5"/><path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.524 5.77 4 4 0 0 1-1.07 6.046A3.5 3.5 0 0 1 12 18.5"/><path d="M12 5v13.5"/><path d="M8 14h.01"/><path d="M16 14h.01"/><path d="M9 9h.01"/><path d="M15 9h.01"/></svg>
''';

const String _kGroupToggleClosedIconAsset =
    'assets/home/chat/mode_menu_closed.svg';
const String _kGroupToggleOpenIconAsset = 'assets/home/chat/mode_menu_open.svg';
const double _kProviderSwitchPopupMaxHeight = 320;

enum _ProviderModelSource { manual, remote }

class _ProtocolTypeOption {
  const _ProtocolTypeOption({required this.value, required this.label});

  final String value;
  final String label;
}

const List<_ProtocolTypeOption> _kProtocolTypeOptions = <_ProtocolTypeOption>[
  _ProtocolTypeOption(value: 'deepseek', label: 'DeepSeek'),
  _ProtocolTypeOption(value: 'openai_compatible', label: 'OpenAI'),
  _ProtocolTypeOption(value: 'anthropic', label: 'Anthropic'),
];

class _ProviderModelItem {
  const _ProviderModelItem({required this.model, required this.source});

  final ProviderModelOption model;
  final _ProviderModelSource source;

  String get id => model.id;
}

class VlmModelSettingPage extends StatefulWidget {
  const VlmModelSettingPage({super.key});

  @override
  State<VlmModelSettingPage> createState() => _VlmModelSettingPageState();
}

class _VlmModelSettingPageState extends State<VlmModelSettingPage> {
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final FocusNode _nameFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();

  static const Duration _autoSaveDebounce = Duration(milliseconds: 600);
  static const Duration _modelGroupToggleDuration = Duration(milliseconds: 240);
  static const double _modelDeleteExtentRatio = 0.24;
  static const double _modelDeleteIconSize = 18;
  static const BorderRadius _modelDeleteActionRadius = BorderRadius.only(
    topRight: Radius.circular(4),
    bottomRight: Radius.circular(4),
  );

  bool _isLoading = true;
  bool _isFetchingModels = false;
  bool _obscureApiKey = true;
  bool _isSyncingControllers = false;
  bool _isSavingProfile = false;
  bool _saveQueued = false;
  bool _isSwitchingProfile = false;
  String _selectedProtocolType = 'openai_compatible';

  Timer? _autoSaveTimer;
  StreamSubscription<AgentAiConfigChangedEvent>? _configChangedSubscription;

  List<ModelProviderProfileSummary> _profiles = const [];
  String _editingProfileId = '';
  List<ProviderModelOption> _manualModels = const [];
  List<ProviderModelOption> _remoteModels = const [];
  List<String> _manualModelIds = const [];
  Set<String> _deletingModelIds = <String>{};
  final Map<String, bool> _expandedModelGroups = <String, bool>{};

  ModelProviderProfileSummary? get _currentProfile {
    for (final profile in _profiles) {
      if (profile.id == _editingProfileId) {
        return profile;
      }
    }
    return _profiles.isEmpty ? null : _profiles.first;
  }

  bool get _hasAnyProfileFieldFocus =>
      _nameFocusNode.hasFocus ||
      _baseUrlFocusNode.hasFocus ||
      _apiKeyFocusNode.hasFocus;

  bool get _isDarkTheme => context.isDarkTheme;
  Color get _pageBackground =>
      _isDarkTheme ? context.omniPalette.pageBackground : AppColors.background;
  Color get _cardColor =>
      _isDarkTheme ? context.omniPalette.surfacePrimary : Colors.white;
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor =>
      _isDarkTheme ? context.omniPalette.textSecondary : AppColors.text70;
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : AppColors.text50;
  BorderSide get _subtleBorder => BorderSide(
    color: _isDarkTheme
        ? context.omniPalette.borderSubtle
        : const Color(0x1A000000),
  );

  String get _selectedProtocolLabel {
    for (final option in _kProtocolTypeOptions) {
      if (option.value == _selectedProtocolType) {
        return option.label;
      }
    }
    return 'OpenAI';
  }

  List<_ProviderModelItem> get _modelItems {
    final items = <_ProviderModelItem>[];
    final seen = <String>{};
    final manualModels = _manualModels.isNotEmpty || _manualModelIds.isEmpty
        ? _manualModels
        : _manualModelIds
              .map(
                (id) => ProviderModelOption(
                  id: id,
                  displayName: id,
                  ownedBy: 'manual',
                ),
              )
              .toList();

    for (final model in manualModels) {
      final normalized = model.id.trim();
      if (!ModelProviderConfigService.isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        items.add(
          _ProviderModelItem(model: model, source: _ProviderModelSource.manual),
        );
      }
    }

    for (final model in _remoteModels) {
      final normalized = model.id.trim();
      if (!ModelProviderConfigService.isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        items.add(
          _ProviderModelItem(model: model, source: _ProviderModelSource.remote),
        );
      }
    }

    return items;
  }

  List<MapEntry<String, List<_ProviderModelItem>>> get _modelGroups {
    final groups = <String, List<_ProviderModelItem>>{};
    final current = _currentProfile;
    final providerGroupId = current?.id.trim().isNotEmpty == true
        ? current!.id.trim()
        : current?.name.trim() ?? '';
    for (final item in _modelItems) {
      final group =
          (item.model.group?.trim().isNotEmpty == true
                  ? item.model.group!.trim()
                  : ModelProviderConfigService.defaultModelGroupName(
                      item.id,
                      providerId: providerGroupId,
                    ))
              .trim();
      final groupName = group.isEmpty ? 'other' : group;
      groups.putIfAbsent(groupName, () => <_ProviderModelItem>[]).add(item);
    }
    return groups.entries.toList();
  }

  String _modelGroupExpansionKey(String groupName) {
    final profileKey = _editingProfileId.trim().isEmpty
        ? 'default'
        : _editingProfileId.trim();
    return '$profileKey::$groupName';
  }

  bool _isModelGroupExpanded(String groupName) {
    return _expandedModelGroups[_modelGroupExpansionKey(groupName)] ?? true;
  }

  void _toggleModelGroup(String groupName) {
    setState(() {
      final key = _modelGroupExpansionKey(groupName);
      _expandedModelGroups[key] = !_isModelGroupExpanded(groupName);
    });
  }

  bool _hasAnyExpandedModelGroup(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    return groups.any((group) => _isModelGroupExpanded(group.key));
  }

  bool _areAllModelGroupsCollapsed(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    return groups.isNotEmpty &&
        groups.every((group) => !_isModelGroupExpanded(group.key));
  }

  void _toggleAllModelGroups(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    if (groups.isEmpty) {
      return;
    }
    final shouldExpand = _areAllModelGroupsCollapsed(groups);
    setState(() {
      for (final group in groups) {
        _expandedModelGroups[_modelGroupExpansionKey(group.key)] = shouldExpand;
      }
    });
  }

  @override
  void initState() {
    super.initState();
    _loadData();
    _configChangedSubscription = AssistsMessageService
        .agentAiConfigChangedStream
        .listen((event) {
          if (event.source != 'file' || !mounted) {
            return;
          }
          if (_hasAnyProfileFieldFocus ||
              _isSavingProfile ||
              _isSwitchingProfile) {
            return;
          }
          unawaited(_loadData());
        });
    _nameController.addListener(_onProfileChanged);
    _baseUrlController.addListener(_onProfileChanged);
    _apiKeyController.addListener(_onProfileChanged);
    _nameFocusNode.addListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.addListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.addListener(_onProfileFieldFocusChanged);
  }

  @override
  void dispose() {
    _configChangedSubscription?.cancel();
    _autoSaveTimer?.cancel();
    if (_shouldAutoSaveDraft) {
      unawaited(_persistProfileDraft());
    }
    unawaited(_persistManualModelIds());
    _nameFocusNode.removeListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.removeListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.removeListener(_onProfileFieldFocusChanged);
    _nameController.removeListener(_onProfileChanged);
    _baseUrlController.removeListener(_onProfileChanged);
    _apiKeyController.removeListener(_onProfileChanged);
    _nameController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _nameFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    super.dispose();
  }

  void _onProfileChanged() {
    if (_isSyncingControllers || _isLoading || _isSwitchingProfile) {
      return;
    }
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _onProfileFieldFocusChanged() {
    if (_isSyncingControllers || _isLoading || _isSwitchingProfile) {
      return;
    }
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _scheduleAutoSave() {
    _autoSaveTimer?.cancel();
    if (!_shouldAutoSaveDraft) {
      return;
    }
    _autoSaveTimer = Timer(_autoSaveDebounce, () {
      _autoSaveTimer = null;
      unawaited(_persistProfileDraft());
    });
  }

  bool get _shouldAutoSaveDraft {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return false;
    }
    final normalizedBaseUrl = ModelProviderConfigService.normalizeApiBase(
      _baseUrlController.text,
    );
    final currentBaseUrl =
        ModelProviderConfigService.normalizeApiBase(current.baseUrl) ?? '';
    final nextBaseUrl = normalizedBaseUrl ?? '';
    return _nameController.text.trim() != current.name ||
        nextBaseUrl != currentBaseUrl ||
        _apiKeyController.text.trim() != current.apiKey;
  }

  Future<void> _persistManualModelIds() async {
    final current = _currentProfile;
    if (current == null) {
      return;
    }
    try {
      await ModelProviderConfigService.saveManualModelIds(
        profileId: current.id,
        ids: _manualModelIds,
      );
    } catch (_) {
      // no-op
    }
  }

  Future<void> _persistProfileDraft() async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    _autoSaveTimer?.cancel();
    _autoSaveTimer = null;

    if (_isSavingProfile) {
      _saveQueued = true;
      return;
    }

    do {
      _saveQueued = false;
      final nextName = _nameController.text.trim();
      final nextBaseUrl =
          ModelProviderConfigService.normalizeApiBase(
            _baseUrlController.text,
          ) ??
          '';
      final nextApiKey = _apiKeyController.text.trim();
      final currentBaseUrl =
          ModelProviderConfigService.normalizeApiBase(current.baseUrl) ?? '';

      if (nextName == current.name &&
          nextBaseUrl == currentBaseUrl &&
          nextApiKey == current.apiKey) {
        return;
      }

      _isSavingProfile = true;
      try {
        final saved = await ModelProviderConfigService.saveProfile(
          id: current.id,
          name: nextName.isEmpty ? current.name : nextName,
          baseUrl: _baseUrlController.text.trim(),
          apiKey: nextApiKey,
          protocolType: _selectedProtocolType,
        );
        if (!mounted) return;
        setState(() {
          _profiles = _profiles
              .map((profile) => profile.id == saved.id ? saved : profile)
              .toList();
          _editingProfileId = saved.id;
        });
      } catch (_) {
        // Auto-save failures should not interrupt typing.
      } finally {
        _isSavingProfile = false;
      }
    } while (_saveQueued && mounted);
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      final payload = await ModelProviderConfigService.listProfiles();
      if (!mounted) return;

      final editingProfile = payload.profiles.firstWhere(
        (profile) => profile.id == payload.editingProfileId,
        orElse: () => payload.profiles.first,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: editingProfile.id,
      );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(editingProfile, manualModelIds),
        _loadRemoteModelsForProfile(editingProfile),
      ]);
      if (!mounted) return;

      _applyProfile(
        profiles: payload.profiles,
        editingProfileId: editingProfile.id,
        manualModelIds: manualModelIds,
        manualModels: storedModels[0] as List<ProviderModelOption>,
        remoteModels: storedModels[1] as List<ProviderModelOption>,
        syncControllers: true,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.modelProviderLoadFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _applyProfile({
    required List<ModelProviderProfileSummary> profiles,
    required String editingProfileId,
    required List<String> manualModelIds,
    required List<ProviderModelOption> manualModels,
    required List<ProviderModelOption> remoteModels,
    required bool syncControllers,
  }) {
    final current = profiles.firstWhere(
      (profile) => profile.id == editingProfileId,
      orElse: () => profiles.first,
    );
    if (syncControllers) {
      _syncController(_nameController, current.name);
      _syncController(_baseUrlController, current.baseUrl);
      _syncController(_apiKeyController, current.apiKey);
    }
    setState(() {
      _profiles = profiles;
      _editingProfileId = current.id;
      _manualModelIds = manualModelIds;
      _manualModels = manualModels;
      _remoteModels = remoteModels;
      _selectedProtocolType = current.protocolType;
    });
  }

  void _syncController(TextEditingController controller, String value) {
    if (controller.text == value) {
      return;
    }
    _isSyncingControllers = true;
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
    _isSyncingControllers = false;
  }

  Future<List<ProviderModelOption>> _loadRemoteModelsForProfile(
    ModelProviderProfileSummary profile,
  ) async {
    final isBuiltinLocalProvider =
        profile.readOnly && profile.sourceType == 'omniinfer';
    if (isBuiltinLocalProvider) {
      try {
        return await ModelProviderConfigService.fetchModels(
          profileId: profile.id,
          providerName: profile.name,
        );
      } catch (_) {
        final cached = await ModelProviderConfigService.getCachedFetchedModels(
          profileId: profile.id,
        );
        return _enrichModelsForProfile(profile, cached);
      }
    }
    final cached = await ModelProviderConfigService.getCachedFetchedModels(
      profileId: profile.id,
      apiBase: profile.baseUrl,
    );
    return _enrichModelsForProfile(profile, cached);
  }

  Future<List<ProviderModelOption>> _loadManualModelsForProfile(
    ModelProviderProfileSummary profile,
    List<String> manualModelIds,
  ) {
    final manualModels = manualModelIds
        .map(
          (modelId) => ProviderModelOption(
            id: modelId,
            displayName: modelId,
            ownedBy: 'manual',
          ),
        )
        .toList();
    return _enrichModelsForProfile(profile, manualModels);
  }

  Future<List<ProviderModelOption>> _enrichModelsForProfile(
    ModelProviderProfileSummary profile,
    List<ProviderModelOption> models,
  ) {
    return ModelProviderConfigService.enrichModelsForProfile(
      profileId: profile.id,
      providerName: profile.name,
      apiBase: profile.baseUrl,
      models: models,
    );
  }

  String? _buildBaseUrlHelperText(String rawValue) {
    final input = rawValue.trim();
    if (input.isEmpty) {
      return null;
    }

    if (_selectedProtocolType == 'anthropic') {
      return ModelProviderConfigService.buildAnthropicMessagesRequestUrl(input);
    }
    return ModelProviderConfigService.buildChatCompletionsRequestUrl(input);
  }

  Future<void> _switchToProfile(String profileId) async {
    if (_isSwitchingProfile || profileId == _editingProfileId) {
      return;
    }
    final index = _profiles.indexWhere((profile) => profile.id == profileId);
    if (index == -1) {
      return;
    }
    _isSwitchingProfile = true;
    try {
      if (_shouldAutoSaveDraft) {
        await _persistProfileDraft();
      }
      final selected = await ModelProviderConfigService.setEditingProfile(
        profileId,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: selected.id,
      );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(selected, manualModelIds),
        _loadRemoteModelsForProfile(selected),
      ]);
      if (!mounted) return;
      _applyProfile(
        profiles: _profiles,
        editingProfileId: selected.id,
        manualModelIds: manualModelIds,
        manualModels: storedModels[0] as List<ProviderModelOption>,
        remoteModels: storedModels[1] as List<ProviderModelOption>,
        syncControllers: true,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.modelProviderSwitchFailed(e.toString()),
        type: ToastType.error,
      );
    } finally {
      _isSwitchingProfile = false;
    }
  }

  Future<void> _promptAddProfile() async {
    if (!mounted) {
      return;
    }
    FocusScope.of(context).unfocus();
    _autoSaveTimer?.cancel();
    if (_shouldAutoSaveDraft) {
      await _persistProfileDraft();
      if (!mounted) {
        return;
      }
    }
    final name = (await AppDialog.input(
      context,
      title: context.l10n.modelAddProviderTitle,
      hintText: context.l10n.modelProviderNameHint,
      confirmText: context.l10n.modelAddButton,
      cancelText: context.trLegacy('取消'),
    ))?.trim();
    if (name == null || name.isEmpty) {
      return;
    }
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        name: name,
        baseUrl: '',
        apiKey: '',
      );
      if (!mounted) return;
      final nextProfiles = [..._profiles, saved];
      _applyProfile(
        profiles: nextProfiles,
        editingProfileId: saved.id,
        manualModelIds: const [],
        manualModels: const [],
        remoteModels: const [],
        syncControllers: true,
      );
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        showToast(context.l10n.modelProviderAdded, type: ToastType.success);
      });
    } catch (e) {
      if (!mounted) return;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        showToast(
          context.l10n.modelProviderAddFailed(e),
          type: ToastType.error,
        );
      });
    }
  }

  Future<void> _fetchModelsLocalized({bool silentError = false}) async {
    final current = _currentProfile;
    if (current == null || _isFetchingModels) return;
    final baseUrl = _baseUrlController.text.trim();
    final apiKey = _apiKeyController.text.trim();

    if (baseUrl.isEmpty) {
      if (!silentError) {
        showToast(
          context.l10n.modelProviderBaseUrlRequired,
          type: ToastType.warning,
        );
      }
      return;
    }
    if (!ModelProviderConfigService.isValidApiBase(baseUrl)) {
      if (!silentError) {
        showToast(
          context.l10n.modelProviderInvalidBaseUrl,
          type: ToastType.error,
        );
      }
      return;
    }

    setState(() => _isFetchingModels = true);
    try {
      final models = await ModelProviderConfigService.fetchModels(
        apiBase: baseUrl,
        apiKey: apiKey,
        profileId: current.id,
        providerName: current.name,
      );
      if (!mounted) return;
      setState(() {
        _remoteModels = models;
      });
      if (!silentError) {
        final message = models.isEmpty
            ? context.l10n.localModelsNoAvailableModels
            : context.l10n.modelProviderFetchedModels(models.length);
        showToast(
          message,
          type: models.isEmpty ? ToastType.warning : ToastType.success,
        );
      }
    } catch (e) {
      if (!mounted || silentError) return;
      showToast(
        context.l10n.modelProviderFetchFailed(e.toString()),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isFetchingModels = false);
      }
    }
  }

  Future<void> _deleteCurrentProfile() async {
    final current = _currentProfile;
    if (current == null || current.readOnly || _profiles.length <= 1) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(context.l10n.modelDeleteProviderTitle),
          content: Text(context.l10n.modelDeleteProviderMsg(current.name)),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(context.trLegacy('取消')),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(context.l10n.skillDelete),
            ),
          ],
        );
      },
    );
    if (confirmed != true) {
      return;
    }
    try {
      final payload = await ModelProviderConfigService.deleteProfile(
        current.id,
      );
      if (!mounted) return;
      final fallback = payload.profiles.firstWhere(
        (profile) => profile.id == payload.editingProfileId,
        orElse: () => payload.profiles.first,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: fallback.id,
      );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(fallback, manualModelIds),
        _loadRemoteModelsForProfile(fallback),
      ]);
      if (!mounted) return;
      _applyProfile(
        profiles: payload.profiles,
        editingProfileId: fallback.id,
        manualModelIds: manualModelIds,
        manualModels: storedModels[0] as List<ProviderModelOption>,
        remoteModels: storedModels[1] as List<ProviderModelOption>,
        syncControllers: true,
      );
      showToast(context.l10n.modelProviderDeleted, type: ToastType.success);
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.modelProviderDeleteFailed(e),
        type: ToastType.error,
      );
    }
  }

  Future<void> _promptAddModel() async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final modelId = await showDialog<String>(
      context: context,
      useRootNavigator: false,
      builder: (_) => const _AddModelIdDialog(),
    );

    if (!mounted) return;
    if (modelId == null) {
      return;
    }
    final normalized = modelId.trim();
    if (!ModelProviderConfigService.isValidModelName(normalized)) {
      showToast(context.l10n.modelIdEmpty, type: ToastType.error);
      return;
    }

    final existsInManual = _manualModelIds.any((item) => item == normalized);
    final existsInRemote = _remoteModels.any((item) => item.id == normalized);
    if (existsInManual || existsInRemote) {
      showToast(context.l10n.modelAlreadyExists, type: ToastType.warning);
      return;
    }

    final nextManual = [..._manualModelIds, normalized];
    final nextManualModels = await _loadManualModelsForProfile(
      current,
      nextManual,
    );
    if (!mounted) return;
    setState(() {
      _manualModelIds = nextManual;
      _manualModels = nextManualModels;
    });

    await ModelProviderConfigService.saveManualModelIds(
      profileId: current.id,
      ids: nextManual,
    );
    if (!mounted) return;
    showToast(context.l10n.modelAdded, type: ToastType.success);
  }

  Future<void> _deleteModel(_ProviderModelItem item) async {
    final current = _currentProfile;
    if (current == null || _deletingModelIds.contains(item.id)) {
      return;
    }

    final prevManual = List<String>.from(_manualModelIds);
    final prevManualModels = List<ProviderModelOption>.from(_manualModels);
    final prevRemote = List<ProviderModelOption>.from(_remoteModels);

    setState(() {
      _deletingModelIds = {..._deletingModelIds, item.id};
      _manualModelIds = _manualModelIds.where((id) => id != item.id).toList();
      _manualModels = _manualModels.where((m) => m.id != item.id).toList();
      _remoteModels = _remoteModels.where((m) => m.id != item.id).toList();
    });

    try {
      await Future.wait([
        ModelProviderConfigService.saveManualModelIds(
          profileId: current.id,
          ids: _manualModelIds,
        ),
        ModelProviderConfigService.saveCachedFetchedModels(
          profileId: current.id,
          apiBase: _baseUrlController.text.trim(),
          models: _remoteModels,
        ),
      ]);

      if (!mounted) return;
      showToast(context.l10n.modelDeleted, type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _manualModelIds = prevManual;
        _manualModels = prevManualModels;
        _remoteModels = prevRemote;
      });
      showToast(context.l10n.modelDeleteFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _deletingModelIds = {..._deletingModelIds}..remove(item.id);
        });
      }
    }
  }

  Future<void> _selectProtocolType(String value) async {
    if (_selectedProtocolType == value) {
      return;
    }
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final previousValue = _selectedProtocolType;
    setState(() => _selectedProtocolType = value);
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: current.id,
        name: _nameController.text.trim().isEmpty
            ? current.name
            : _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        protocolType: value,
      );
      if (!mounted) return;
      setState(() {
        _profiles = _profiles.map((p) => p.id == saved.id ? saved : p).toList();
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _selectedProtocolType = previousValue);
    }
  }

  Future<void> _openProtocolTypeMenu(BuildContext anchorContext) async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final popupWidth = anchorRect.width.clamp(132.0, 180.0).toDouble();
    final estimatedHeight = (_kProtocolTypeOptions.length * 48 + 24)
        .clamp(120.0, _kProviderSwitchPopupMaxHeight)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    final selected = await showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _ProtocolTypePopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          options: _kProtocolTypeOptions,
          selectedValue: _selectedProtocolType,
        ),
      ],
    );
    if (selected == null) {
      return;
    }
    await _selectProtocolType(selected);
  }

  Widget _buildCard({required Widget child}) {
    return SizedBox(width: double.infinity, child: child);
  }

  InputDecoration _buildInputDecoration({
    required String label,
    String? hint,
    Widget? suffixIcon,
  }) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      labelStyle: TextStyle(
        color: _secondaryTextColor,
        fontSize: 13,
        fontFamily: 'PingFang SC',
      ),
      hintStyle: TextStyle(
        color: _tertiaryTextColor,
        fontSize: 13,
        fontFamily: 'PingFang SC',
      ),
      filled: true,
      fillColor: _cardColor,
      suffixIcon: suffixIcon,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: _subtleBorder,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: _subtleBorder,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(
          color: _isDarkTheme
              ? context.omniPalette.accentPrimary
              : const Color(0xFF2C7FEB),
        ),
      ),
    );
  }

  Widget _buildModelActionButton({
    required String svg,
    required VoidCallback? onPressed,
    bool highlighted = false,
    bool loading = false,
  }) {
    final isEnabled = onPressed != null;
    final useHighlightStyle = highlighted || loading;
    final backgroundColor = !isEnabled && !loading
        ? (_isDarkTheme
              ? context.omniPalette.surfaceElevated
              : const Color(0xFFE8ECF3))
        : useHighlightStyle
        ? (_isDarkTheme
              ? context.omniPalette.accentPrimary
              : const Color(0xFF2C7FEB))
        : _cardColor;
    final iconColor = !isEnabled && !loading
        ? _tertiaryTextColor
        : useHighlightStyle
        ? (_isDarkTheme
              ? Theme.of(context).colorScheme.onPrimary
              : Colors.white)
        : _primaryTextColor;

    return Material(
      color: backgroundColor,
      borderRadius: BorderRadius.circular(10),
      child: Ink(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          border: !useHighlightStyle && _isDarkTheme
              ? Border.all(color: context.omniPalette.borderSubtle)
              : null,
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onPressed,
          child: SizedBox(
            width: 42,
            height: 42,
            child: Center(
              child: loading
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    )
                  : SvgPicture.string(
                      svg,
                      width: 20,
                      height: 20,
                      colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
                    ),
            ),
          ),
        ),
      ),
    );
  }

  String _formatTokenLimit(int? value) {
    if (value == null || value <= 0) {
      return '--';
    }
    if (value >= 1000000) {
      final formatted = value % 1000000 == 0
          ? '${value ~/ 1000000}'
          : (value / 1000000).toStringAsFixed(1);
      return '${formatted}M';
    }
    if (value >= 1000) {
      final formatted = value % 1000 == 0
          ? '${value ~/ 1000}'
          : (value / 1000).toStringAsFixed(1);
      return '${formatted}K';
    }
    return value.toString();
  }

  Widget _buildCompactIconChip({
    required String key,
    required String svg,
    required String tooltip,
    String? label,
  }) {
    return Tooltip(
      message: tooltip,
      child: Container(
        key: Key(key),
        height: 22,
        constraints: const BoxConstraints(minWidth: 22),
        padding: label == null
            ? EdgeInsets.zero
            : const EdgeInsets.symmetric(horizontal: 6),
        decoration: BoxDecoration(
          color: _cardColor,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: _isDarkTheme
                ? context.omniPalette.borderSubtle
                : const Color(0x14000000),
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SvgPicture.string(
              svg,
              width: 14,
              height: 14,
              colorFilter: ColorFilter.mode(
                _secondaryTextColor,
                BlendMode.srcIn,
              ),
            ),
            if (label != null) ...[
              const SizedBox(width: 4),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w700,
                  fontFamily: 'PingFang SC',
                  letterSpacing: 0,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildModalityIcon({required String svg, required String tooltip}) {
    return _buildCompactIconChip(
      key: 'provider-model-modality-${tooltip.toLowerCase().split(' ').first}',
      svg: svg,
      tooltip: tooltip,
    );
  }

  Widget _buildContextLimitChip(String modelId, String label) {
    return Tooltip(
      message: 'Context limit $label',
      child: Container(
        key: Key('provider-model-context-$modelId'),
        height: 22,
        padding: const EdgeInsets.symmetric(horizontal: 7),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: _cardColor,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: _isDarkTheme
                ? context.omniPalette.borderSubtle
                : const Color(0x14000000),
          ),
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 11,
            color: _tertiaryTextColor,
            fontWeight: FontWeight.w700,
            fontFamily: 'PingFang SC',
            letterSpacing: 0,
          ),
        ),
      ),
    );
  }

  Widget _buildModalityTextChip(String modality) {
    return Container(
      height: 22,
      padding: const EdgeInsets.symmetric(horizontal: 6),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: _isDarkTheme
              ? context.omniPalette.borderSubtle
              : const Color(0x14000000),
        ),
      ),
      child: Text(
        modality.toUpperCase(),
        style: TextStyle(
          color: _secondaryTextColor,
          fontSize: 9,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
      ),
    );
  }

  List<Widget> _buildModelMetadataWidgets(ProviderModelOption model) {
    final widgets = <Widget>[];
    final contextLimit = _formatTokenLimit(model.contextLimit);
    widgets.add(_buildContextLimitChip(model.id, contextLimit));
    if (model.reasoning == true) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-reasoning-${model.id}',
          svg: _kReasoningSvg,
          tooltip: 'Reasoning',
        ),
      );
    }
    widgets.addAll(_buildInputModalityWidgets(model));
    return widgets;
  }

  List<Widget> _buildInputModalityWidgets(ProviderModelOption model) {
    final modalities = model.inputModalities
        .map((item) => item.trim().toLowerCase())
        .where((item) => item.isNotEmpty)
        .toSet();
    if (modalities.isEmpty) {
      return const [];
    }
    final widgets = <Widget>[];
    if (modalities.contains('text')) {
      widgets.add(
        _buildModalityIcon(svg: _kInputTextSvg, tooltip: 'Text input'),
      );
    }
    if (modalities.contains('image')) {
      widgets.add(
        _buildModalityIcon(svg: _kInputImageSvg, tooltip: 'Image input'),
      );
    }
    if (modalities.contains('pdf')) {
      widgets.add(_buildModalityIcon(svg: _kInputPdfSvg, tooltip: 'PDF input'));
    }
    for (final modality in modalities) {
      if (modality == 'text' || modality == 'image' || modality == 'pdf') {
        continue;
      }
      widgets.add(_buildModalityTextChip(modality));
    }
    return widgets;
  }

  Widget _buildModelGroupSection(
    MapEntry<String, List<_ProviderModelItem>> group, {
    required bool isLast,
  }) {
    final expanded = _isModelGroupExpanded(group.key);
    final items = Column(
      children: [
        const SizedBox(height: 2),
        for (var itemIndex = 0; itemIndex < group.value.length; itemIndex++)
          _buildSwipeModelItem(group.value[itemIndex]),
      ],
    );

    return Padding(
      padding: EdgeInsets.only(bottom: isLast ? 0 : 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildModelGroupHeader(
            group.key,
            group.value.length,
            expanded: expanded,
            onTap: () => _toggleModelGroup(group.key),
          ),
          TweenAnimationBuilder<double>(
            tween: Tween<double>(
              begin: expanded ? 1 : 0,
              end: expanded ? 1 : 0,
            ),
            duration: _modelGroupToggleDuration,
            curve: Curves.easeInOutCubicEmphasized,
            builder: (context, value, child) {
              return ClipRect(
                child: Align(
                  key: Key('provider-model-group-body-${group.key}'),
                  alignment: Alignment.topCenter,
                  heightFactor: value,
                  child: Opacity(
                    opacity: value.clamp(0.0, 1.0).toDouble(),
                    child: IgnorePointer(ignoring: value < 0.99, child: child),
                  ),
                ),
              );
            },
            child: items,
          ),
        ],
      ),
    );
  }

  Widget _buildModelGroupHeader(
    String groupName,
    int count, {
    required bool expanded,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    final labelStyle = TextStyle(
      color: _tertiaryTextColor,
      fontSize: 11,
      fontWeight: FontWeight.w700,
      fontFamily: 'PingFang SC',
      letterSpacing: 0.4,
    );
    final countStyle = TextStyle(
      color: _tertiaryTextColor,
      fontSize: 11,
      fontWeight: FontWeight.w500,
      fontFamily: 'PingFang SC',
    );
    const labelToCountGap = 8.0;
    const countToLineGap = 10.0;
    const lineToIconGap = 6.0;
    const iconSlotWidth = 20.0;
    const minLineWidth = 48.0;

    return Padding(
      padding: const EdgeInsets.only(bottom: 5),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: Key('provider-model-group-$groupName'),
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Container(
            width: double.infinity,
            constraints: const BoxConstraints(minHeight: 28),
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.fromLTRB(4, 5, 2, 5),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final countWidth = _measureSingleLineTextWidth(
                  '$count',
                  countStyle,
                );
                final fixedTrailingWidth =
                    labelToCountGap +
                    countWidth +
                    countToLineGap +
                    lineToIconGap +
                    iconSlotWidth;
                final labelAndLineWidth = math.max(
                  0.0,
                  constraints.maxWidth - fixedTrailingWidth,
                );
                final reservedLineWidth = math.min(
                  minLineWidth,
                  labelAndLineWidth * 0.32,
                );
                final groupNameMaxWidth = math.max(
                  0.0,
                  labelAndLineWidth - reservedLineWidth,
                );
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    ConstrainedBox(
                      constraints: BoxConstraints(maxWidth: groupNameMaxWidth),
                      child: Text(
                        key: Key('provider-model-group-label-$groupName'),
                        groupName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: labelStyle,
                      ),
                    ),
                    const SizedBox(width: labelToCountGap),
                    Text(
                      key: Key('provider-model-group-count-$groupName'),
                      '$count',
                      style: countStyle,
                    ),
                    const SizedBox(width: countToLineGap),
                    Expanded(
                      child: Container(
                        key: Key('provider-model-group-line-$groupName'),
                        height: 1,
                        color: _isDarkTheme
                            ? palette.borderSubtle.withValues(alpha: 0.56)
                            : const Color(0x16000000),
                      ),
                    ),
                    const SizedBox(width: lineToIconGap),
                    SizedBox(
                      key: Key('provider-model-group-icon-$groupName'),
                      width: iconSlotWidth,
                      child: Align(
                        alignment: Alignment.centerRight,
                        child: AnimatedRotation(
                          turns: expanded ? 0 : -0.25,
                          duration: _modelGroupToggleDuration,
                          curve: Curves.easeInOutCubicEmphasized,
                          child: Icon(
                            Icons.keyboard_arrow_down_rounded,
                            size: 18,
                            color: _tertiaryTextColor,
                          ),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  double _measureSingleLineTextWidth(String text, TextStyle style) {
    final textPainter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: Directionality.of(context),
      maxLines: 1,
    )..layout();
    return textPainter.width;
  }

  Widget _buildModelGroupToggleButton(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    final palette = context.omniPalette;
    final enabled = groups.isNotEmpty;
    final isOpen = enabled && _hasAnyExpandedModelGroup(groups);
    final iconColor = !enabled
        ? palette.textTertiary
        : isOpen
        ? palette.accentPrimary
        : palette.textSecondary;
    final tooltip = !enabled
        ? context.trLegacy('暂无模型分组')
        : _areAllModelGroupsCollapsed(groups)
        ? context.trLegacy('展开全部分组')
        : context.trLegacy('折叠全部分组');

    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        key: const ValueKey('provider-model-group-toggle-button'),
        onTap: enabled ? () => _toggleAllModelGroups(groups) : null,
        behavior: HitTestBehavior.opaque,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: enabled ? 1 : 0.36,
          child: SizedBox(
            width: 48,
            height: 44,
            child: Center(
              child: SvgPicture.asset(
                isOpen
                    ? _kGroupToggleOpenIconAsset
                    : _kGroupToggleClosedIconAsset,
                width: 20,
                height: 20,
                colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSwipeModelItem(_ProviderModelItem item) {
    final isDeleting = _deletingModelIds.contains(item.id);
    final metadataWidgets = _buildModelMetadataWidgets(item.model);

    return IgnorePointer(
      ignoring: isDeleting,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 180),
        opacity: isDeleting ? 0.72 : 1,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final initialActionWidth =
                constraints.maxWidth * _modelDeleteExtentRatio;
            final deleteIconRightPadding =
                ((initialActionWidth - _modelDeleteIconSize) / 2)
                    .clamp(0.0, double.infinity)
                    .toDouble();
            final metadataMaxWidth = (constraints.maxWidth * 0.46)
                .clamp(116.0, 190.0)
                .toDouble();

            return Slidable(
              key: ValueKey<String>('provider-model-${item.id}'),
              groupTag: 'provider-model-items',
              closeOnScroll: true,
              endActionPane: ActionPane(
                motion: const BehindMotion(),
                extentRatio: _modelDeleteExtentRatio,
                dismissible: DismissiblePane(
                  dismissThreshold: 0.4,
                  closeOnCancel: true,
                  motion: const InversedDrawerMotion(),
                  onDismissed: () => _deleteModel(item),
                ),
                children: [
                  CustomSlidableAction(
                    onPressed: (_) => _deleteModel(item),
                    backgroundColor: AppColors.alertRed,
                    borderRadius: _modelDeleteActionRadius,
                    padding: EdgeInsets.zero,
                    alignment: Alignment.centerRight,
                    child: Padding(
                      padding: EdgeInsets.only(right: deleteIconRightPadding),
                      child: SvgPicture.asset(
                        'assets/memory/memory_delete.svg',
                        width: _modelDeleteIconSize,
                        height: _modelDeleteIconSize,
                        colorFilter: const ColorFilter.mode(
                          Colors.white,
                          BlendMode.srcIn,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: () {},
                  borderRadius: BorderRadius.circular(10),
                  splashColor: context.omniPalette.accentPrimary.withValues(
                    alpha: 0.06,
                  ),
                  highlightColor: Colors.transparent,
                  child: SizedBox(
                    width: double.infinity,
                    height: 44,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              item.id,
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                                color: _primaryTextColor,
                                fontFamily: 'PingFang SC',
                                height: 1.2,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 10),
                          SizedBox(
                            width: metadataMaxWidth,
                            child: SingleChildScrollView(
                              scrollDirection: Axis.horizontal,
                              physics: const BouncingScrollPhysics(),
                              reverse: true,
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  for (
                                    var index = 0;
                                    index < metadataWidgets.length;
                                    index++
                                  ) ...[
                                    if (index > 0) const SizedBox(width: 6),
                                    metadataWidgets[index],
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Future<void> _openProviderSwitchMenu(BuildContext anchorContext) async {
    if (_profiles.isEmpty) {
      return;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final popupWidth = anchorRect.width.clamp(220.0, 300.0).toDouble();
    final estimatedHeight = (_profiles.length * 48 + 24)
        .clamp(120.0, _kProviderSwitchPopupMaxHeight)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    final selected = await showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _ProviderSwitchPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          profiles: _profiles,
          selectedProfileId: _editingProfileId,
        ),
      ],
    );
    if (selected == null) {
      return;
    }
    await _switchToProfile(selected);
  }

  Widget _buildProviderConfigTitle({double? maxWidth}) {
    final current = _currentProfile;
    final name = current?.name.trim();
    final displayName = (name == null || name.isEmpty) ? 'Provider' : name;
    final textMaxWidth = maxWidth ?? double.infinity;
    return Builder(
      builder: (anchorContext) {
        return InkWell(
          key: const Key('provider-config-title'),
          onTap: _profiles.isEmpty
              ? null
              : () {
                  unawaited(_openProviderSwitchMenu(anchorContext));
                },
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: textMaxWidth),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        displayName,
                        key: const Key('provider-config-title-text'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: _primaryTextColor,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                      if (current != null &&
                          (current.readOnly || current.statusText.isNotEmpty))
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            current.statusText.isNotEmpty
                                ? current.statusText
                                : (current.ready
                                      ? context.l10n.modelBuiltinProvider
                                      : '${context.l10n.modelBuiltinProvider} not ready'),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: _tertiaryTextColor,
                              fontSize: 11,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                if (current?.readOnly == true)
                  Padding(
                    padding: const EdgeInsets.only(left: 6),
                    child: Icon(
                      Icons.lock_outline,
                      size: 14,
                      color: _tertiaryTextColor,
                    ),
                  ),
                const SizedBox(width: 2),
                Icon(
                  Icons.keyboard_arrow_down_rounded,
                  size: 18,
                  color: _secondaryTextColor,
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildProtocolTypeButton() {
    final current = _currentProfile;
    final enabled = !(current?.readOnly ?? false);
    return Builder(
      builder: (anchorContext) {
        return Opacity(
          opacity: enabled ? 1 : 0.68,
          child: InkWell(
            key: const Key('provider-protocol-type-button'),
            onTap: enabled
                ? () {
                    unawaited(_openProtocolTypeMenu(anchorContext));
                  }
                : null,
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 88),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          _selectedProtocolLabel,
                          key: const Key('provider-protocol-type-text'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: _primaryTextColor,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 2),
                  Icon(
                    Icons.keyboard_arrow_down_rounded,
                    size: 18,
                    color: _secondaryTextColor,
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final modelItems = _modelItems;
    final modelGroups = _modelGroups;

    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsModelProviderTitle,
        primary: true,
      ),
      body: SafeArea(
        top: false,
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
                children: [
                  SettingsSectionTitle(
                    label: context.l10n.modelProviderConfigTitle,
                    subtitle: context.l10n.modelProviderConfigDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: LayoutBuilder(
                                builder: (context, constraints) {
                                  const protocolButtonReservedWidth = 116.0;
                                  const titleSpacing = 4.0;
                                  final providerTitleMaxWidth =
                                      (constraints.maxWidth -
                                              protocolButtonReservedWidth -
                                              titleSpacing)
                                          .clamp(72.0, constraints.maxWidth)
                                          .toDouble();
                                  return Align(
                                    alignment: Alignment.centerLeft,
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        _buildProviderConfigTitle(
                                          maxWidth: providerTitleMaxWidth,
                                        ),
                                        const SizedBox(width: titleSpacing),
                                        _buildProtocolTypeButton(),
                                      ],
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              svg: '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M3 6h18"/>
  <path d="M8 6V4h8v2"/>
  <path d="M19 6l-1 14H6L5 6"/>
  <path d="M10 11v6"/>
  <path d="M14 11v6"/>
</svg>
''',
                              onPressed:
                                  _profiles.length <= 1 ||
                                      _currentProfile?.readOnly == true
                                  ? null
                                  : _deleteCurrentProfile,
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              svg: _kPlusSvg,
                              onPressed: _promptAddProfile,
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _nameController,
                          focusNode: _nameFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          decoration: _buildInputDecoration(
                            label: context.l10n.modelProviderName,
                            hint: context.l10n.modelProviderNameHint,
                          ),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _baseUrlController,
                          focusNode: _baseUrlFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          decoration: _buildInputDecoration(
                            label: 'Base URL',
                            hint: context.l10n.modelProviderBaseUrlHint,
                          ),
                        ),
                        const SizedBox(height: 8),
                        ValueListenableBuilder<TextEditingValue>(
                          valueListenable: _baseUrlController,
                          builder: (context, value, child) {
                            final url = _buildBaseUrlHelperText(value.text);
                            if (url == null) {
                              return const SizedBox.shrink();
                            }
                            return Text(
                              url,
                              style: TextStyle(
                                color: _tertiaryTextColor,
                                fontSize: 12,
                                fontFamily: 'PingFang SC',
                              ),
                            );
                          },
                        ),
                        const SizedBox(height: 14),
                        TextField(
                          controller: _apiKeyController,
                          focusNode: _apiKeyFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          obscureText: _obscureApiKey,
                          decoration: _buildInputDecoration(
                            label: 'API Key',
                            hint: 'e.g., sk-xxxx',
                            suffixIcon: IconButton(
                              splashRadius: 18,
                              onPressed: () {
                                setState(() {
                                  _obscureApiKey = !_obscureApiKey;
                                });
                              },
                              icon: Icon(
                                _obscureApiKey
                                    ? Icons.visibility_off
                                    : Icons.visibility,
                                color: _tertiaryTextColor,
                                size: 18,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          context.l10n.modelProviderApiKeyHint,
                          style: TextStyle(
                            color: _tertiaryTextColor,
                            fontSize: 12,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  SettingsSectionTitle(
                    label: context.l10n.modelListTitle,
                    subtitle: context.l10n.modelListDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                context.l10n.modelListCount(modelItems.length),
                                style: TextStyle(
                                  color: _secondaryTextColor,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            _buildModelActionButton(
                              svg: _kPlusSvg,
                              onPressed: _currentProfile?.readOnly == true
                                  ? null
                                  : _promptAddModel,
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              svg: _kArrowBigDownSvg,
                              onPressed: _isFetchingModels
                                  ? null
                                  : _fetchModelsLocalized,
                              highlighted: true,
                              loading: _isFetchingModels,
                            ),
                            const SizedBox(width: 4),
                            _buildModelGroupToggleButton(modelGroups),
                          ],
                        ),
                        const SizedBox(height: 12),
                        SizedBox(
                          height: 280,
                          child: modelItems.isEmpty
                              ? Padding(
                                  padding: const EdgeInsets.all(10),
                                  child: Center(
                                    child: Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        SvgPicture.string(
                                          _kPackageSvg,
                                          width: 64,
                                          height: 64,
                                          colorFilter: ColorFilter.mode(
                                            _tertiaryTextColor,
                                            BlendMode.srcIn,
                                          ),
                                        ),
                                        const SizedBox(height: 10),
                                        Text(
                                          context.l10n.modelAddPrompt,
                                          style: TextStyle(
                                            color: _secondaryTextColor,
                                            fontSize: 14,
                                            fontWeight: FontWeight.w600,
                                            fontFamily: 'PingFang SC',
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                )
                              : ListView.builder(
                                  padding: const EdgeInsets.fromLTRB(
                                    2,
                                    2,
                                    2,
                                    8,
                                  ),
                                  itemCount: modelGroups.length,
                                  itemBuilder: (context, index) {
                                    final group = modelGroups[index];
                                    return _buildModelGroupSection(
                                      group,
                                      isLast: index == modelGroups.length - 1,
                                    );
                                  },
                                ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _ProviderSwitchPopupEntry extends PopupMenuEntry<String> {
  const _ProviderSwitchPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.profiles,
    required this.selectedProfileId,
  });

  final double width;
  final double estimatedHeight;
  final List<ModelProviderProfileSummary> profiles;
  final String selectedProfileId;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_ProviderSwitchPopupEntry> createState() =>
      _ProviderSwitchPopupEntryState();
}

class _ProviderSwitchPopupEntryState extends State<_ProviderSwitchPopupEntry> {
  Widget _buildProviderTile(ModelProviderProfileSummary profile) {
    final palette = context.omniPalette;
    final selected = profile.id == widget.selectedProfileId;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () {
          Navigator.of(context).pop(profile.id);
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected
                ? (_isDarkTheme(context)
                      ? palette.segmentThumb
                      : const Color(0xFFEAF3FF))
                : (_isDarkTheme(context)
                      ? palette.surfaceSecondary
                      : const Color(0xFFF8FAFD)),
            borderRadius: BorderRadius.circular(12),
            border: _isDarkTheme(context)
                ? Border.all(color: palette.borderSubtle)
                : null,
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  profile.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: _isDarkTheme(context)
                        ? palette.textPrimary
                        : AppColors.text,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  Icons.check_rounded,
                  size: 16,
                  color: _isDarkTheme(context)
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: widget.profiles.isEmpty
            ? Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No providers',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _isDarkTheme(context)
                        ? palette.textTertiary
                        : const Color(0xFF94A3B8),
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            : Scrollbar(
                child: ListView.builder(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  itemCount: widget.profiles.length,
                  itemBuilder: (context, index) {
                    return _buildProviderTile(widget.profiles[index]);
                  },
                ),
              ),
      ),
    );
  }
}

class _ProtocolTypePopupEntry extends PopupMenuEntry<String> {
  const _ProtocolTypePopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.options,
    required this.selectedValue,
  });

  final double width;
  final double estimatedHeight;
  final List<_ProtocolTypeOption> options;
  final String selectedValue;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_ProtocolTypePopupEntry> createState() =>
      _ProtocolTypePopupEntryState();
}

class _ProtocolTypePopupEntryState extends State<_ProtocolTypePopupEntry> {
  Widget _buildProtocolTile(_ProtocolTypeOption option) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selected = option.value == widget.selectedValue;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () => Navigator.of(context).pop(option.value),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected
                ? (isDark ? palette.segmentThumb : const Color(0xFFEAF3FF))
                : (isDark ? palette.surfaceSecondary : const Color(0xFFF8FAFD)),
            borderRadius: BorderRadius.circular(12),
            border: isDark ? Border.all(color: palette.borderSubtle) : null,
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  option.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? palette.textPrimary : AppColors.text,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  Icons.check_rounded,
                  size: 16,
                  color: isDark
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Scrollbar(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: widget.options.length,
            itemBuilder: (context, index) {
              return _buildProtocolTile(widget.options[index]);
            },
          ),
        ),
      ),
    );
  }
}

bool _isDarkTheme(BuildContext context) => context.isDarkTheme;

class _AddModelIdDialog extends StatefulWidget {
  const _AddModelIdDialog();

  @override
  State<_AddModelIdDialog> createState() => _AddModelIdDialogState();
}

class _AddModelIdDialogState extends State<_AddModelIdDialog> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _focusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _close([String? value]) {
    _focusNode.unfocus();
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
        title: Text(context.l10n.modelIdHint),
        content: TextField(
          controller: _controller,
          focusNode: _focusNode,
          decoration: InputDecoration(hintText: context.l10n.modelIdHint),
          onSubmitted: (_) => _close(_controller.text.trim()),
        ),
        actions: [
          TextButton(
            onPressed: () => _close(),
            child: Text(context.trLegacy('取消')),
          ),
          TextButton(
            onPressed: () => _close(_controller.text.trim()),
            child: Text(context.l10n.modelAddButton),
          ),
        ],
      ),
    );
  }
}
