import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/widgets/settings_section_title.dart';

const double _kSceneSelectionPopupMaxHeight = 420;

Widget _buildSceneModelIdTooltip({
  required String modelId,
  required Widget child,
}) {
  return Tooltip(
    message: modelId,
    triggerMode: TooltipTriggerMode.longPress,
    waitDuration: Duration.zero,
    showDuration: const Duration(seconds: 3),
    preferBelow: false,
    textAlign: TextAlign.start,
    constraints: const BoxConstraints(maxWidth: 320),
    child: child,
  );
}

class SceneModelSettingPage extends StatefulWidget {
  const SceneModelSettingPage({super.key});

  @override
  State<SceneModelSettingPage> createState() => _SceneModelSettingPageState();
}

class _SceneModelSettingPageState extends State<SceneModelSettingPage> {
  static const bool _showManualRefreshButton = false;

  static const List<String> _sceneOrder = [
    'scene.dispatch.model',
    'scene.voice',
    'scene.vlm.operation.primary',
    'scene.compactor.context',
    'scene.compactor.context.chat',
    'scene.loading.sprite',
    'scene.memory.embedding',
    'scene.memory.rollup',
  ];

  static const Map<String, String> _sceneDisplayNameMap = {
    'scene.dispatch.model': 'Agent',
    'scene.voice': 'Voice',
    'scene.vlm.operation.primary': 'Operation',
    'scene.compactor.context': 'Compactor',
    'scene.compactor.context.chat': 'Chat Compactor',
    'scene.loading.sprite': 'Loading',
    'scene.memory.embedding': 'Memory Embed',
    'scene.memory.rollup': 'Memory Rollup',
  };

  static const Map<String, String> _sceneTooltipMap = {
    'scene.dispatch.model': '负责任务理解与分流决策',
    'scene.voice': '负责 AI 回复文本的语音合成与播放',
    'scene.vlm.operation.primary': '负责执行 UI 操作主链路',
    'scene.compactor.context': '负责 VLM 执行链的上下文压缩与纠错',
    'scene.compactor.context.chat': '负责聊天历史压缩总结',
    'scene.loading.sprite': '负责生成加载状态文案',
    'scene.memory.embedding': '负责 workspace 记忆向量检索的嵌入模型',
    'scene.memory.rollup': '负责夜间记忆整理策略模型',
  };

  bool _isLoading = true;
  bool _isRefreshingModels = false;
  bool _isSavingVoiceConfig = false;

  List<SceneCatalogItem> _catalog = const [];
  List<SceneModelBindingEntry> _bindings = const [];
  List<ModelProviderProfileSummary> _profiles = const [];
  Map<String, List<ProviderModelOption>> _providerModelsByProfileId = {};
  Set<String> _savingSceneIds = <String>{};
  Set<String> _expandedSceneIds = <String>{};
  SceneVoiceConfig _voiceConfig = const SceneVoiceConfig();
  late final TextEditingController _voiceIdController;
  late final TextEditingController _voiceCustomStyleController;
  Timer? _voiceConfigSaveDebounce;
  SceneVoiceConfig? _pendingVoiceConfig;
  DateTime? _suppressExternalReloadUntil;
  StreamSubscription<AgentAiConfigChangedEvent>? _configChangedSubscription;
  static const List<String> _voiceStylePresets = <String>[
    '默认',
    '自然对话',
    '温柔陪伴',
    '专业播报',
    '活泼元气',
    '睡前轻声',
    '唱歌',
  ];

  @override
  void initState() {
    super.initState();
    _voiceIdController = TextEditingController();
    _voiceCustomStyleController = TextEditingController();
    _loadData();
    _configChangedSubscription = AssistsMessageService
        .agentAiConfigChangedStream
        .listen((event) {
          if ((event.source != 'file' && event.source != 'store') || !mounted) {
            return;
          }
          final suppressUntil = _suppressExternalReloadUntil;
          if (suppressUntil != null && DateTime.now().isBefore(suppressUntil)) {
            return;
          }
          unawaited(
            _loadData(showLoading: false, refreshProviderModels: false),
          );
        });
  }

  @override
  void dispose() {
    _configChangedSubscription?.cancel();
    _voiceConfigSaveDebounce?.cancel();
    _voiceIdController.dispose();
    _voiceCustomStyleController.dispose();
    super.dispose();
  }

  List<SceneCatalogItem> get _orderedCatalog {
    final map = {for (final item in _catalog) item.sceneId: item};

    final ordered = <SceneCatalogItem>[];
    for (final sceneId in _sceneOrder) {
      final item = map.remove(sceneId);
      if (item != null) {
        ordered.add(item);
      }
    }
    ordered.addAll(map.values);
    return ordered;
  }

  Map<String, SceneModelBindingEntry> get _bindingMap {
    return {for (final item in _bindings) item.sceneId: item};
  }

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

  String _sceneDisplayName(String sceneId) {
    return _sceneDisplayNameMap[sceneId] ?? sceneId;
  }

  String _sceneTooltip(SceneCatalogItem item) {
    final mapped = _sceneTooltipMap[item.sceneId];
    if (mapped != null) {
      return context.trLegacy(mapped);
    }
    if (item.description.trim().isNotEmpty) {
      return context.trLegacy(item.description.trim());
    }
    return item.sceneId;
  }

  bool _isSavingScene(String sceneId) {
    return _savingSceneIds.contains(sceneId);
  }

  bool _isAgentScene(String sceneId) {
    return sceneId == 'scene.dispatch.model';
  }

  bool _isVoiceScene(String sceneId) {
    return sceneId == 'scene.voice';
  }

  void _syncVoiceControllers(SceneVoiceConfig config) {
    if (_voiceIdController.text != config.voiceId) {
      _voiceIdController.value = TextEditingValue(
        text: config.voiceId,
        selection: TextSelection.collapsed(offset: config.voiceId.length),
      );
    }
    if (_voiceCustomStyleController.text != config.customStyle) {
      _voiceCustomStyleController.value = TextEditingValue(
        text: config.customStyle,
        selection: TextSelection.collapsed(offset: config.customStyle.length),
      );
    }
  }

  void _updateVoiceConfig(
    SceneVoiceConfig nextConfig, {
    bool saveImmediately = false,
  }) {
    if (_voiceConfig == nextConfig) {
      return;
    }
    setState(() => _voiceConfig = nextConfig);
    if (saveImmediately) {
      unawaited(_enqueueVoiceConfigSave(nextConfig));
      return;
    }
    _voiceConfigSaveDebounce?.cancel();
    _voiceConfigSaveDebounce = Timer(const Duration(milliseconds: 450), () {
      unawaited(_enqueueVoiceConfigSave(nextConfig));
    });
  }

  Future<void> _loadData({
    bool showLoading = true,
    bool refreshProviderModels = true,
  }) async {
    if (showLoading && mounted) {
      setState(() => _isLoading = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
        SceneModelConfigService.getSceneVoiceConfig(),
      ]);
      if (!mounted) return;

      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profilesPayload = results[2] as ModelProviderProfilesPayload;
      final voiceConfig = results[3] as SceneVoiceConfig;
      final providerModelsByProfileId = <String, List<ProviderModelOption>>{};
      for (final profile in profilesPayload.profiles) {
        providerModelsByProfileId[profile.id] =
            await ModelProviderConfigService.getStoredModelOptionsForProfile(
              profile.id,
            );
      }

      final enriched = _mergeBindingModels(
        providerModelsByProfileId: providerModelsByProfileId,
        bindings: bindings,
      );

      setState(() {
        _catalog = catalog;
        _bindings = bindings;
        _profiles = profilesPayload.profiles;
        _providerModelsByProfileId = enriched;
        _voiceConfig = voiceConfig;
      });
      _syncVoiceControllers(voiceConfig);
      if (refreshProviderModels &&
          _profiles.any((profile) => profile.configured)) {
        unawaited(_refreshProviderModels());
      }
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
    } finally {
      if (showLoading && mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Map<String, List<ProviderModelOption>> _mergeBindingModels({
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
    required List<SceneModelBindingEntry> bindings,
  }) {
    final result = {
      for (final entry in providerModelsByProfileId.entries)
        entry.key: [...entry.value],
    };
    for (final binding in bindings) {
      final bucket = result.putIfAbsent(binding.providerProfileId, () => []);
      final exists = bucket.any((item) => item.id == binding.modelId);
      if (!exists) {
        bucket.add(
          ProviderModelOption(
            id: binding.modelId,
            displayName: binding.modelId,
            ownedBy: 'binding',
          ),
        );
      }
    }
    return result;
  }

  Future<void> _refreshProviderModels() async {
    if (_isRefreshingModels) return;
    setState(() => _isRefreshingModels = true);
    try {
      final nextModels = <String, List<ProviderModelOption>>{};
      var refreshedCount = 0;
      final failedProfiles = <String>[];
      for (final profile in _profiles) {
        if (!profile.configured) {
          nextModels[profile.id] =
              await ModelProviderConfigService.getStoredModelOptionsForProfile(
                profile.id,
              );
          continue;
        }
        try {
          final remoteModels = await ModelProviderConfigService.fetchModels(
            apiBase: profile.baseUrl,
            apiKey: profile.apiKey,
            profileId: profile.id,
          );
          final manualModelIds =
              await ModelProviderConfigService.getManualModelIds(
                profileId: profile.id,
              );
          nextModels[profile.id] = ModelProviderConfigService.mergeModelOptions(
            remoteModels: remoteModels,
            manualModelIds: manualModelIds,
          );
          refreshedCount += remoteModels.length;
        } catch (e) {
          //允许部分成功，不让一个 Provider 的失败拖垮整次刷新。
          nextModels[profile.id] =
              await ModelProviderConfigService.getStoredModelOptionsForProfile(
                profile.id,
              );
          failedProfiles.add(profile.name);
        }
      }

      if (!mounted) return;
      // 一次性更新页面模型数据
      setState(() {
        _providerModelsByProfileId = _mergeBindingModels(
          providerModelsByProfileId: nextModels,
          bindings: _bindings,
        );
      });
      if (failedProfiles.isNotEmpty) {
        final preview = failedProfiles.take(2).join(', ');
        final extraCount = failedProfiles.length - 2;
        final suffix = extraCount > 0 ? ' (+$extraCount)' : '';
        showToast(
          context.l10n.sceneModelPartialUpdateFailed('$preview$suffix'),
          type: ToastType.warning,
        );
        return;
      }
      showToast(
        refreshedCount == 0
            ? context.l10n.localModelsNoAvailableModels
            : context.l10n.sceneModelUpdatedModels(refreshedCount),
        type: refreshedCount == 0 ? ToastType.warning : ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelRefreshFailed(e.toString()),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isRefreshingModels = false);
      }
    }
  }

  Future<void> _saveSceneBinding({
    required SceneCatalogItem scene,
    required String providerProfileId,
    required String modelId,
  }) async {
    final sceneId = scene.sceneId;
    final current = _bindingMap[sceneId];
    if (current?.providerProfileId == providerProfileId &&
        current?.modelId == modelId) {
      return;
    }
    if (!SceneModelConfigService.isValidModelName(modelId)) {
      showToast(context.l10n.sceneModelInvalidModelId, type: ToastType.error);
      return;
    }

    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = await SceneModelConfigService.saveSceneModelBinding(
        sceneId: sceneId,
        providerProfileId: providerProfileId,
        modelId: modelId,
      );
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
        _providerModelsByProfileId = _mergeBindingModels(
          providerModelsByProfileId: _providerModelsByProfileId,
          bindings: bindings,
        );
      });
      showToast(
        context.l10n.sceneModelBoundToast(_sceneDisplayName(sceneId), modelId),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelSaveFailed(
          _sceneDisplayName(sceneId),
          e.toString(),
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _savingSceneIds = {..._savingSceneIds}..remove(sceneId);
        });
      }
    }
  }

  Future<void> _clearSceneBinding(SceneCatalogItem scene) async {
    final sceneId = scene.sceneId;
    if (!_bindingMap.containsKey(sceneId)) {
      return;
    }
    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = await SceneModelConfigService.clearSceneModelBinding(
        sceneId,
      );
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
      });
      final toastText = _isVoiceScene(sceneId)
          ? '${_sceneDisplayName(sceneId)} 已清除绑定'
          : '${_sceneDisplayName(sceneId)} 已恢复默认模型';
      showToast(
        LegacyTextLocalizer.localize(toastText),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelClearFailed(
          _sceneDisplayName(sceneId),
          e.toString(),
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _savingSceneIds = {..._savingSceneIds}..remove(sceneId);
        });
      }
    }
  }

  Future<void> _clearSceneBindingLocalized(SceneCatalogItem scene) async {
    final sceneId = scene.sceneId;
    if (!_bindingMap.containsKey(sceneId)) {
      return;
    }
    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = await SceneModelConfigService.clearSceneModelBinding(
        sceneId,
      );
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
      });
      final toastText = _isVoiceScene(sceneId)
          ? context.l10n.sceneModelBindingCleared(_sceneDisplayName(sceneId))
          : context.l10n.sceneModelDefaultRestored(_sceneDisplayName(sceneId));
      showToast(toastText, type: ToastType.success);
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelClearFailed(
          _sceneDisplayName(sceneId),
          e.toString(),
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _savingSceneIds = {..._savingSceneIds}..remove(sceneId);
        });
      }
    }
  }

  void _toggleSceneExpanded(String sceneId) {
    if (!_isVoiceScene(sceneId)) {
      return;
    }
    setState(() {
      if (_expandedSceneIds.contains(sceneId)) {
        _expandedSceneIds.remove(sceneId);
      } else {
        _expandedSceneIds = <String>{sceneId};
      }
    });
  }

  Future<void> _saveVoiceConfig(SceneVoiceConfig nextConfig) async {
    _voiceConfigSaveDebounce?.cancel();
    if (_isSavingVoiceConfig) {
      _pendingVoiceConfig = nextConfig;
      return;
    }
    _suppressExternalReloadUntil = DateTime.now().add(
      const Duration(seconds: 2),
    );
    setState(() => _isSavingVoiceConfig = true);
    try {
      final saved = await SceneModelConfigService.saveSceneVoiceConfig(
        nextConfig,
      );
      if (!mounted) return;
      if (_voiceConfig == nextConfig || _voiceConfig == saved) {
        setState(() {
          _voiceConfig = saved;
        });
        _syncVoiceControllers(saved);
      }
    } catch (e) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.localize('保存 Voice 配置失败：$e'),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isSavingVoiceConfig = false);
      }
      final pending = _pendingVoiceConfig;
      _pendingVoiceConfig = null;
      if (pending != null && pending != nextConfig) {
        unawaited(_saveVoiceConfig(pending));
      }
    }
  }

  Future<void> _enqueueVoiceConfigSave(SceneVoiceConfig nextConfig) async {
    _pendingVoiceConfig = nextConfig;
    await _saveVoiceConfig(nextConfig);
  }

  Future<void> _openSceneSelector(
    SceneCatalogItem scene,
    BuildContext anchorContext,
  ) async {
    final binding = _bindingMap[scene.sceneId];
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
    final popupWidth = anchorBox.size.width
        .clamp(160.0, overlay.size.width - 16.0)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: _kSceneSelectionPopupMaxHeight,
      reservedBottom: (() {
        final viewInsetBottom = MediaQuery.of(context).viewInsets.bottom;
        return viewInsetBottom > 0 ? viewInsetBottom : 280.0;
      })(),
    );

    final result = await showMenu<_SceneSelectionAction>(
      context: context,
      color: _cardColor,
      elevation: 8,
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      items: [
        _SceneSelectionPopupEntry(
          width: popupWidth,
          estimatedHeight: _kSceneSelectionPopupMaxHeight,
          scene: scene,
          profiles: _profiles,
          providerModelsByProfileId: _providerModelsByProfileId,
          currentBinding: binding,
        ),
      ],
    );
    if (result == null) {
      return;
    }
    if (result.restoreDefault) {
      await _clearSceneBindingLocalized(scene);
      return;
    }
    if (result.providerProfileId.isNotEmpty && result.modelId.isNotEmpty) {
      await _saveSceneBinding(
        scene: scene,
        providerProfileId: result.providerProfileId,
        modelId: result.modelId,
      );
    }
  }

  Widget _buildCard({required Widget child}) {
    return SizedBox(width: double.infinity, child: child);
  }

  String _selectionLabel(SceneCatalogItem scene) {
    final binding = _bindingMap[scene.sceneId];
    if (binding == null) {
      if (scene.defaultModel.trim().isEmpty) {
        return context.trLegacy('未绑定');
      }
      return context.trLegacy('默认：${scene.defaultModel}');
    }
    final profile = _profiles.where(
      (item) => item.id == binding.providerProfileId,
    );
    final profileName = profile.isEmpty
        ? 'Provider unavailable'
        : profile.first.name;
    return '$profileName / ${binding.modelId}';
  }

  Widget _buildSceneLabel(SceneCatalogItem scene) {
    return Tooltip(
      message: _sceneTooltip(scene),
      triggerMode: TooltipTriggerMode.tap,
      showDuration: const Duration(seconds: 3),
      child: Row(
        children: [
          Flexible(
            child: Text(
              _sceneDisplayName(scene.sceneId),
              style: TextStyle(
                color: _primaryTextColor,
                fontSize: 14,
                fontWeight: FontWeight.w600,
                fontFamily: 'PingFang SC',
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 6),
          if (_isAgentScene(scene.sceneId)) ...[
            const AgentAvatarButton(size: 30, showEditBadge: true),
            const SizedBox(width: 6),
          ],
          Icon(Icons.info_outline, size: 15, color: _tertiaryTextColor),
        ],
      ),
    );
  }

  Widget _buildSceneSelectorField(
    SceneCatalogItem scene, {
    required bool isSaving,
  }) {
    return Builder(
      builder: (fieldContext) {
        return InkWell(
          onTap: isSaving
              ? null
              : () => _openSceneSelector(scene, fieldContext),
          borderRadius: BorderRadius.circular(8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 11),
            decoration: BoxDecoration(
              color: _cardColor,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: _isDarkTheme
                    ? context.omniPalette.borderSubtle
                    : const Color(0x1A000000),
              ),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _selectionLabel(scene),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: _primaryTextColor,
                      fontSize: 13,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  Icons.keyboard_arrow_down_rounded,
                  size: 18,
                  color: _tertiaryTextColor,
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildVoiceSettings() {
    final isSinging = _voiceConfig.stylePreset == '唱歌';
    final borderColor = _isDarkTheme
        ? context.omniPalette.borderSubtle
        : const Color(0x1A000000);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                context.trLegacy('AI 响应完成后自动播放'),
                style: TextStyle(
                  color: _primaryTextColor,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            Switch(
              value: _voiceConfig.autoPlay,
              onChanged: (value) {
                final next = _voiceConfig.copyWith(autoPlay: value);
                _updateVoiceConfig(next, saveImmediately: true);
              },
            ),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          context.trLegacy('音色'),
          style: TextStyle(
            color: _primaryTextColor,
            fontSize: 13,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          key: const Key('voice-scene-voice-id-field'),
          controller: _voiceIdController,
          maxLines: 1,
          decoration: InputDecoration(
            hintText: context.trLegacy(
              '例如：default_zh / mimo_default / default_en',
            ),
            border: const OutlineInputBorder(),
            isDense: true,
            suffixIcon: _isSavingVoiceConfig
                ? const Padding(
                    padding: EdgeInsets.all(10),
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  )
                : null,
          ),
          onChanged: (value) {
            final next = _voiceConfig.copyWith(voiceId: value);
            _updateVoiceConfig(next);
          },
        ),
        const SizedBox(height: 12),
        Text(
          context.trLegacy('风格'),
          style: TextStyle(
            color: _primaryTextColor,
            fontSize: 13,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(height: 8),
        Container(
          decoration: BoxDecoration(
            color: _cardColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: borderColor),
          ),
          child: Column(
            children: [
              for (var i = 0; i < _voiceStylePresets.length; i++) ...[
                _buildVoiceStyleOption(_voiceStylePresets[i]),
                if (i != _voiceStylePresets.length - 1)
                  Divider(height: 1, thickness: 1, color: borderColor),
              ],
              Divider(height: 1, thickness: 1, color: borderColor),
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      context.trLegacy('自定义补充'),
                      style: TextStyle(
                        color: _primaryTextColor,
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 6),
                    TextField(
                      key: const Key('voice-scene-custom-style-field'),
                      controller: _voiceCustomStyleController,
                      enabled: !isSinging,
                      maxLines: 2,
                      minLines: 1,
                      decoration: InputDecoration(
                        hintText: isSinging
                            ? context.trLegacy('唱歌模式下不支持附加风格')
                            : context.trLegacy('例如：更温柔、节奏慢一点、偏播客感'),
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        disabledBorder: InputBorder.none,
                        errorBorder: InputBorder.none,
                        focusedErrorBorder: InputBorder.none,
                        isDense: true,
                        contentPadding: EdgeInsets.zero,
                      ),
                      onChanged: (value) {
                        final next = _voiceConfig.copyWith(customStyle: value);
                        _updateVoiceConfig(next);
                      },
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildVoiceStyleOption(String preset) {
    final selected = _voiceConfig.stylePreset == preset;
    return InkWell(
      key: Key('voice-style-option-$preset'),
      borderRadius: BorderRadius.circular(12),
      onTap: () {
        final next = _voiceConfig.copyWith(
          stylePreset: preset,
          customStyle: preset == '唱歌' ? '' : _voiceCustomStyleController.text,
        );
        if (preset == '唱歌' && _voiceCustomStyleController.text.isNotEmpty) {
          _syncVoiceControllers(next);
        }
        _updateVoiceConfig(next, saveImmediately: true);
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked_rounded
                  : Icons.radio_button_off_rounded,
              size: 18,
              color: selected
                  ? Theme.of(context).colorScheme.primary
                  : _tertiaryTextColor,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                context.trLegacy(preset),
                style: TextStyle(
                  color: _primaryTextColor,
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDefaultSceneRow(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(flex: 4, child: _buildSceneLabel(scene)),
          const SizedBox(width: 10),
          Expanded(
            flex: 6,
            child: _buildSceneSelectorField(scene, isSaving: isSaving),
          ),
          if (isSaving) ...[
            const SizedBox(width: 8),
            const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildVoiceSceneRow(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);
    final isExpanded = _expandedSceneIds.contains(scene.sceneId);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            children: [
              Expanded(
                flex: 4,
                child: Row(
                  children: [
                    Expanded(child: _buildSceneLabel(scene)),
                    const SizedBox(width: 6),
                    IconButton(
                      key: const Key('voice-scene-expand-button'),
                      visualDensity: VisualDensity.compact,
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints.tightFor(
                        width: 22,
                        height: 22,
                      ),
                      splashRadius: 14,
                      tooltip: context.trLegacy(
                        isExpanded ? '收起语音设置' : '展开语音设置',
                      ),
                      onPressed: () => _toggleSceneExpanded(scene.sceneId),
                      icon: Icon(
                        isExpanded
                            ? Icons.expand_less_rounded
                            : Icons.tune_rounded,
                        size: 18,
                        color: _tertiaryTextColor,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 6,
                child: _buildSceneSelectorField(scene, isSaving: isSaving),
              ),
              if (isSaving) ...[
                const SizedBox(width: 8),
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ],
            ],
          ),
        ),
        if (isExpanded)
          Container(
            width: double.infinity,
            margin: const EdgeInsets.only(top: 2, bottom: 6),
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
            decoration: BoxDecoration(
              color: _cardColor,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: _isDarkTheme
                    ? context.omniPalette.borderSubtle
                    : const Color(0x14000000),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _sceneTooltip(scene),
                  style: TextStyle(
                    color: _secondaryTextColor,
                    fontSize: 12,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 12),
                _buildVoiceSettings(),
              ],
            ),
          ),
      ],
    );
  }

  Widget _buildSceneRow(SceneCatalogItem scene) {
    if (_isVoiceScene(scene.sceneId)) {
      return _buildVoiceSceneRow(scene);
    }
    return _buildDefaultSceneRow(scene);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsSceneModelTitle,
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
                    label: context.l10n.sceneModelMapping,
                    subtitle: context.l10n.sceneModelMappingDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (_showManualRefreshButton)
                          Align(
                            alignment: Alignment.centerLeft,
                            child: OutlinedButton.icon(
                              onPressed: _isRefreshingModels
                                  ? null
                                  : _refreshProviderModels,
                              icon: _isRefreshingModels
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.refresh, size: 16),
                              label: Text(context.l10n.sceneModelRefreshList),
                            ),
                          ),
                        if (_showManualRefreshButton)
                          const SizedBox(height: 12),
                        Text(
                          context.trLegacy(
                            '点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；Voice 的音色与自动播放可通过调节按钮展开。',
                          ),
                          style: TextStyle(
                            color: _secondaryTextColor,
                            fontSize: 12,
                            height: 1.5,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        const SizedBox(height: 12),
                        if (_orderedCatalog.isEmpty)
                          Padding(
                            padding: EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              context.l10n.sceneModelNoScenes,
                              style: TextStyle(
                                color: _secondaryTextColor,
                                fontSize: 12,
                                fontFamily: 'PingFang SC',
                              ),
                            ),
                          )
                        else
                          ListView.separated(
                            physics: const NeverScrollableScrollPhysics(),
                            shrinkWrap: true,
                            itemCount: _orderedCatalog.length,
                            itemBuilder: (context, index) {
                              final scene = _orderedCatalog[index];
                              return _buildSceneRow(scene);
                            },
                            separatorBuilder: (_, _) => Divider(
                              height: 20,
                              thickness: 0.6,
                              color: context.omniPalette.borderSubtle
                                  .withValues(alpha: 0.9),
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

class _SceneSelectionAction {
  final bool restoreDefault;
  final String providerProfileId;
  final String modelId;

  const _SceneSelectionAction.restore()
    : restoreDefault = true,
      providerProfileId = '',
      modelId = '';

  const _SceneSelectionAction.select({
    required this.providerProfileId,
    required this.modelId,
  }) : restoreDefault = false;
}

class _SceneSelectionPopupEntry extends PopupMenuEntry<_SceneSelectionAction> {
  const _SceneSelectionPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.scene,
    required this.profiles,
    required this.providerModelsByProfileId,
    required this.currentBinding,
  });

  final double width;
  final double estimatedHeight;
  final SceneCatalogItem scene;
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final SceneModelBindingEntry? currentBinding;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(_SceneSelectionAction? value) => false;

  @override
  State<_SceneSelectionPopupEntry> createState() =>
      _SceneSelectionPopupEntryState();
}

class _SceneSelectionPopupEntryState extends State<_SceneSelectionPopupEntry> {
  final TextEditingController _searchController = TextEditingController();
  late final Set<String> _expandedProfileIds;

  bool get _hasSearchQuery => _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _expandedProfileIds = <String>{
      if (widget.currentBinding != null)
        widget.currentBinding!.providerProfileId,
    };
    if (_expandedProfileIds.isEmpty && widget.profiles.isNotEmpty) {
      _expandedProfileIds.add(widget.profiles.first.id);
    }
    _searchController.addListener(() {
      setState(() {});
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<ProviderModelOption> _filteredModels(String profileId) {
    final query = _searchController.text.trim().toLowerCase();
    final models = widget.providerModelsByProfileId[profileId] ?? const [];
    if (query.isEmpty) {
      return models;
    }
    return models
        .where((item) => item.id.toLowerCase().contains(query))
        .toList();
  }

  List<ModelProviderProfileSummary> get _visibleProfiles {
    if (!_hasSearchQuery) {
      return widget.profiles;
    }
    return widget.profiles.where((profile) {
      return _filteredModels(profile.id).isNotEmpty;
    }).toList();
  }

  bool _isExpanded(String profileId) {
    if (_hasSearchQuery) {
      return true;
    }
    return _expandedProfileIds.contains(profileId);
  }

  bool get _isDarkTheme => context.isDarkTheme;
  Color get _surfaceColor => _isDarkTheme
      ? context.omniPalette.surfaceSecondary
      : const Color(0xFFF8FAFD);
  Color get _selectedSurfaceColor =>
      _isDarkTheme ? context.omniPalette.segmentThumb : const Color(0xFFEAF3FF);
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor => _isDarkTheme
      ? context.omniPalette.textSecondary
      : const Color(0xFF64748B);
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : const Color(0xFF94A3B8);

  Widget _buildSearchRow() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Row(
        children: [
          Icon(Icons.search, size: 18, color: _tertiaryTextColor),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _searchController,
              autofocus: false,
              scrollPadding: EdgeInsets.zero,
              style: TextStyle(
                fontSize: 13,
                color: _primaryTextColor,
                fontWeight: FontWeight.w500,
                fontFamily: 'PingFang SC',
              ),
              decoration: InputDecoration(
                isDense: true,
                hintText: 'Filter model ID',
                hintStyle: TextStyle(
                  fontSize: 13,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w500,
                  fontFamily: 'PingFang SC',
                ),
                border: InputBorder.none,
                focusedBorder: InputBorder.none,
                enabledBorder: InputBorder.none,
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRestoreDefaultTile() {
    final selected = widget.currentBinding == null;
    final label = widget.scene.sceneId == 'scene.voice'
        ? context.trLegacy('清除绑定')
        : context.trLegacy('恢复默认（${widget.scene.defaultModel}）');
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 4),
      child: InkWell(
        onTap: () {
          Navigator.of(context).pop(const _SceneSelectionAction.restore());
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: selected ? _selectedSurfaceColor : _surfaceColor,
            borderRadius: BorderRadius.circular(12),
            border: _isDarkTheme
                ? Border.all(color: context.omniPalette.borderSubtle)
                : null,
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: _primaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  Icons.check_rounded,
                  size: 15,
                  color: _isDarkTheme
                      ? context.omniPalette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSectionHeader(ModelProviderProfileSummary profile) {
    final expanded = _isExpanded(profile.id);
    final models = _filteredModels(profile.id);
    final isCurrent = widget.currentBinding?.providerProfileId == profile.id;

    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () {
          if (_hasSearchQuery) {
            return;
          }
          setState(() {
            if (expanded) {
              _expandedProfileIds.remove(profile.id);
            } else {
              _expandedProfileIds.add(profile.id);
            }
          });
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: _surfaceColor,
            borderRadius: BorderRadius.circular(12),
            border: _isDarkTheme
                ? Border.all(color: context.omniPalette.borderSubtle)
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
                    fontSize: 12,
                    color: _secondaryTextColor,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              Text(
                profile.configured ? '${models.length}' : 'Not configured',
                style: TextStyle(
                  fontSize: 11,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w600,
                  fontFamily: 'PingFang SC',
                ),
              ),
              if (isCurrent) ...[
                const SizedBox(width: 6),
                Icon(
                  Icons.check_circle_rounded,
                  size: 13,
                  color: _isDarkTheme
                      ? context.omniPalette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
              ],
              const SizedBox(width: 6),
              Icon(
                _hasSearchQuery
                    ? Icons.unfold_more_rounded
                    : expanded
                    ? Icons.expand_less_rounded
                    : Icons.expand_more_rounded,
                size: 16,
                color: _tertiaryTextColor,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModelRow({
    required ModelProviderProfileSummary profile,
    required ProviderModelOption item,
  }) {
    final selected =
        widget.currentBinding?.providerProfileId == profile.id &&
        widget.currentBinding?.modelId == item.id;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: _buildSceneModelIdTooltip(
        modelId: item.id,
        child: InkWell(
          onTap: () {
            Navigator.of(context).pop(
              _SceneSelectionAction.select(
                providerProfileId: profile.id,
                modelId: item.id,
              ),
            );
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: selected ? _selectedSurfaceColor : _surfaceColor,
              borderRadius: BorderRadius.circular(12),
              border: _isDarkTheme
                  ? Border.all(color: context.omniPalette.borderSubtle)
                  : null,
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    item.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: _primaryTextColor,
                      fontWeight: FontWeight.w500,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
                if (selected)
                  Icon(
                    Icons.check_rounded,
                    size: 15,
                    color: _isDarkTheme
                        ? context.omniPalette.accentPrimary
                        : const Color(0xFF2C7FEB),
                  ),
              ],
            ),
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
            .clamp(220.0, _kSceneSelectionPopupMaxHeight)
            .toDouble();
    final visibleProfiles = _visibleProfiles;
    return SizedBox(
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildSearchRow(),
            _buildRestoreDefaultTile(),
            if (widget.profiles.isEmpty)
              Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No available Providers yet. Please configure a model provider first.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _tertiaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            else if (visibleProfiles.isEmpty)
              Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No matching models',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _tertiaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            else
              Flexible(
                child: Scrollbar(
                  child: ListView.builder(
                    padding: const EdgeInsets.only(bottom: 8),
                    itemCount: visibleProfiles.length,
                    itemBuilder: (context, index) {
                      final profile = visibleProfiles[index];
                      final expanded = _isExpanded(profile.id);
                      final models = _filteredModels(profile.id);
                      return Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _buildSectionHeader(profile),
                          if (expanded)
                            profile.configured
                                ? models.isEmpty
                                      ? Padding(
                                          padding: EdgeInsets.fromLTRB(
                                            12,
                                            4,
                                            12,
                                            8,
                                          ),
                                          child: Text(
                                            'No selectable models for this Provider',
                                            style: TextStyle(
                                              fontSize: 12,
                                              color: _tertiaryTextColor,
                                              fontFamily: 'PingFang SC',
                                            ),
                                          ),
                                        )
                                      : Column(
                                          children: models.map((item) {
                                            return _buildModelRow(
                                              profile: profile,
                                              item: item,
                                            );
                                          }).toList(),
                                        )
                                : Padding(
                                    padding: EdgeInsets.fromLTRB(12, 4, 12, 8),
                                    child: Text(
                                      'Please configure this Provider in the model provider settings first',
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: _tertiaryTextColor,
                                        fontFamily: 'PingFang SC',
                                      ),
                                    ),
                                  ),
                          if (index != visibleProfiles.length - 1)
                            const SizedBox(height: 6),
                        ],
                      );
                    },
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
