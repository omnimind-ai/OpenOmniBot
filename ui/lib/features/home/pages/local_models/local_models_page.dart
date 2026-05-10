import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/mnn_local_models_service.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/settings_section_title.dart';

enum _LocalModelsTab { service, market }

enum _AccentTone { neutral, accent, success, info, warning, danger }

const String _llamaCppBackend = 'llama.cpp';
const String _omniinferMnnBackend = 'omniinfer-mnn';
const String _omniinferQnnBackend = 'executorch-qnn';
const String _omniinferLiteRtBackend = 'litert';

class LocalModelsPage extends StatefulWidget {
  const LocalModelsPage({
    super.key,
    this.initialTab = 'service',
    this.initialBackend,
    this.pinnedModelId,
  });

  final String initialTab;
  final String? initialBackend;

  /// When set, the market tab will pin this model to the top of the list.
  final String? pinnedModelId;

  @override
  State<LocalModelsPage> createState() => _LocalModelsPageState();
}

class _LocalModelsPageState extends State<LocalModelsPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  OmniThemePalette get _palette => context.omniPalette;
  bool get _isDarkTheme => context.isDarkTheme;
  Color get _cardColor => _palette.surfacePrimary;
  Color get _panelColor =>
      _blend(_palette.surfacePrimary, _palette.surfaceSecondary, 0.58);
  Color get _borderColor => _palette.borderSubtle;
  Color get _primaryTextColor => _palette.textPrimary;
  Color get _secondaryTextColor => _palette.textSecondary;
  Color get _tertiaryTextColor => _palette.textTertiary;
  Color get _tagSurfaceColor =>
      _blend(_palette.surfaceSecondary, _palette.surfacePrimary, 0.34);

  final TextEditingController _installedSearchController =
      TextEditingController();
  final TextEditingController _marketSearchController = TextEditingController();
  final TextEditingController _portController = TextEditingController();

  StreamSubscription<MnnLocalEvent>? _eventSubscription;
  Timer? _pollTimer;
  Timer? _configSaveDebounce;

  MnnLocalConfig? _config;
  List<MnnLocalModel> _installedModels = const [];
  List<MnnLocalModel> _marketModels = const [];

  bool _loadingInstalled = true;
  bool _loadingMarket = true;
  bool _loadingConfig = true;

  bool _importing = false;
  double _importProgress = 0.0;
  String _importModelId = '';

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: _LocalModelsTab.values.length,
      vsync: this,
      initialIndex: _tabIndexFromName(widget.initialTab),
    );
    _tabController.addListener(_handleTabChanged);
    _eventSubscription = MnnLocalModelsService.eventStream.listen(_handleEvent);
    _bootstrap(preferredBackend: widget.initialBackend);
    _startPolling();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _configSaveDebounce?.cancel();
    _eventSubscription?.cancel();
    _tabController
      ..removeListener(_handleTabChanged)
      ..dispose();
    _installedSearchController.dispose();
    _marketSearchController.dispose();
    _portController.dispose();
    super.dispose();
  }

  int _tabIndexFromName(String raw) {
    switch (raw.trim().toLowerCase()) {
      case 'market':
        return _LocalModelsTab.market.index;
      case 'benchmark':
      case 'installed':
      case 'service':
      default:
        return _LocalModelsTab.service.index;
    }
  }

  List<MnnLocalModel> get _filteredInstalledModels {
    final normalizedQuery = _installedSearchController.text
        .trim()
        .toLowerCase();
    return _installedModels.where((item) {
      if (normalizedQuery.isEmpty) {
        return true;
      }
      final haystacks = <String>[
        item.name,
        item.id,
        item.vendor,
        item.description,
        item.path,
        ...item.tags,
        ...item.extraTags,
      ];
      return haystacks.any(
        (value) => value.toLowerCase().contains(normalizedQuery),
      );
    }).toList();
  }

  List<MnnLocalModel> get _serviceModels =>
      _installedModels.where((item) => item.category == 'llm').toList();

  List<MnnLocalModel> get _sortedMarketModels {
    final sorted = List<MnnLocalModel>.from(_marketModels);
    final pinId = widget.pinnedModelId;
    sorted.sort((a, b) {
      // Pin the recommended model to the top
      if (pinId != null && pinId.isNotEmpty) {
        final aPin = a.id.contains(pinId) || a.name.contains(pinId);
        final bPin = b.id.contains(pinId) || b.name.contains(pinId);
        if (aPin && !bPin) return -1;
        if (!aPin && bPin) return 1;
      }
      final sizeCompare = _modelSizeSortValue(
        a,
      ).compareTo(_modelSizeSortValue(b));
      if (sizeCompare != 0) {
        return sizeCompare;
      }
      return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    });
    return sorted;
  }

  Future<void> _bootstrap({String? preferredBackend}) async {
    try {
      final normalizedPreferredBackend = _normalizeBackendName(
        preferredBackend,
      );
      if (normalizedPreferredBackend != null) {
        await MnnLocalModelsService.setBackend(normalizedPreferredBackend);
      }
      final overview = await MnnLocalModelsService.getOverview(
        installedQuery: _installedSearchController.text.trim(),
        marketQuery: _marketSearchController.text.trim(),
        marketCategory: 'llm',
      );
      if (!mounted) return;
      setState(() {
        _config = overview.config;
        _installedModels = overview.installedModels;
        _marketModels = overview.market.models;
        _loadingConfig = false;
        _loadingInstalled = false;
        _loadingMarket = false;
      });
      _syncConfigControllers(overview.config);
    } catch (_) {
      await Future.wait([
        _refreshConfig(silent: true),
        _refreshInstalled(silent: true),
        _refreshMarket(silent: true),
      ]);
    }
  }

  String? _normalizeBackendName(String? raw) {
    switch (raw?.trim().toLowerCase()) {
      case _llamaCppBackend:
        return _llamaCppBackend;
      case 'mnn':
      case _omniinferMnnBackend:
        return _omniinferMnnBackend;
      case 'qnn':
      case _omniinferQnnBackend:
        return _omniinferQnnBackend;
      case 'litert':
      case 'litert-lm':
      case 'litertlm':
        return _omniinferLiteRtBackend;
      default:
        return null;
    }
  }

  void _syncConfigControllers(MnnLocalConfig config) {
    final nextPort = config.apiPort.toString();
    if (_portController.text != nextPort) {
      _portController.text = nextPort;
    }
  }

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (!mounted) return;
      switch (_LocalModelsTab.values[_tabController.index]) {
        case _LocalModelsTab.service:
          _refreshConfig(silent: true);
          _refreshInstalled(silent: true);
          break;
        case _LocalModelsTab.market:
          _refreshMarket(silent: true);
          break;
      }
    });
  }

  void _handleTabChanged() {
    if (_tabController.indexIsChanging) {
      return;
    }
    switch (_LocalModelsTab.values[_tabController.index]) {
      case _LocalModelsTab.service:
        _refreshConfig(silent: true);
        _refreshInstalled(silent: true);
        break;
      case _LocalModelsTab.market:
        _refreshMarket(silent: true);
        break;
    }
  }

  Future<void> _refreshConfig({bool silent = false}) async {
    if (!silent && mounted) {
      setState(() => _loadingConfig = true);
    }
    try {
      final config = await MnnLocalModelsService.getConfig();
      if (!mounted) return;
      setState(() {
        _config = config;
        _loadingConfig = false;
      });
      _syncConfigControllers(config);
    } catch (_) {
      if (!mounted) return;
      if (!silent) {
        showToast(context.l10n.localModelsConfigLoadFailed, type: ToastType.error);
      }
      setState(() => _loadingConfig = false);
    }
  }

  Future<void> _refreshInstalled({bool silent = false}) async {
    if (!silent && mounted) {
      setState(() => _loadingInstalled = true);
    }
    try {
      final models = await MnnLocalModelsService.listInstalledModels();
      if (!mounted) return;
      setState(() {
        _installedModels = models;
        _loadingInstalled = false;
      });
    } catch (_) {
      if (!mounted) return;
      if (!silent) {
        showToast(context.l10n.localModelsInstalledLoadFailed, type: ToastType.error);
      }
      setState(() => _loadingInstalled = false);
    }
  }

  Future<void> _refreshMarket({
    bool silent = false,
    bool refresh = false,
  }) async {
    if (!silent && mounted) {
      setState(() => _loadingMarket = true);
    }
    try {
      final payload = await MnnLocalModelsService.listMarketModels(
        query: _marketSearchController.text.trim(),
        category: 'llm',
        refresh: refresh,
      );
      if (!mounted) return;
      setState(() {
        _marketModels = payload.models;
        _loadingMarket = false;
      });
    } catch (_) {
      if (!mounted) return;
      if (!silent) {
        showToast(context.l10n.localModelsMarketLoadFailed, type: ToastType.error);
      }
      setState(() => _loadingMarket = false);
    }
  }

  void _handleEvent(MnnLocalEvent event) {
    if (!mounted) return;
    debugPrint('[LocalModels] event: ${event.type}, payload keys: ${event.payload.keys}');
    switch (event.type) {
      case 'download_update':
        final modelId = (event.payload['modelId'] ?? '').toString();
        final rawDownload = event.payload['download'];
        final download = rawDownload is Map
            ? MnnLocalDownloadInfo.fromMap(rawDownload)
            : null;
        final prevState = _marketModels
            .where((m) => m.id == modelId)
            .firstOrNull
            ?.download
            ?.stateLabel ?? 'not_started';
        debugPrint('[LocalModels] download_update: model=$modelId, '
            'prevState=$prevState -> newState=${download?.stateLabel}, '
            'progress=${download?.progress.toStringAsFixed(2)}, '
            'error=${download?.errorMessage}');
        _showDownloadStateToast(modelId, download);
        _updateMarketDownloadState(modelId, download);
        if (download?.isCompleted == true) {
          _refreshInstalled(silent: true);
        }
        break;
      case 'download_error':
        final errorModelId = (event.payload['modelId'] ?? '').toString();
        final errorMsg = (event.payload['error'] ?? '').toString();
        debugPrint('[LocalModels] download_error: model=$errorModelId, error=$errorMsg');
        if (errorModelId.isNotEmpty) {
          final name = _displayModelName(errorModelId);
          final reason = errorMsg.trim().isNotEmpty
              ? errorMsg.trim()
              : context.l10n.localModelsDownloadErrorUnknown;
          showToast(
            context.l10n.localModelsDownloadFailedToast(name, reason),
            type: ToastType.error,
          );
          _refreshMarket(silent: true);
        }
        break;
      case 'downloads_changed':
        debugPrint('[LocalModels] downloads_changed: ${event.payload}');
        _refreshInstalled(silent: true);
        _refreshMarket(silent: true);
        break;
      case 'import_progress':
        final importModelId = (event.payload['modelId'] ?? '').toString();
        final importProgress =
            (event.payload['progress'] as num?)?.toDouble() ?? 0.0;
        if (mounted) {
          setState(() {
            _importProgress = importProgress;
            _importModelId = importModelId;
            if (importProgress >= 1.0) {
              _importing = false;
              _refreshInstalled(silent: true);
            }
          });
        }
        break;
      case 'config_changed':
        final configPayload = event.payload['config'];
        if (configPayload is Map) {
          final config = MnnLocalConfig.fromMap(configPayload);
          setState(() => _config = config);
          _syncConfigControllers(config);
        } else {
          _refreshConfig(silent: true);
        }
        break;
      default:
        break;
    }
  }

  void _showDownloadStateToast(String modelId, MnnLocalDownloadInfo? download) {
    if (download == null || modelId.isEmpty) return;
    final newState = download.stateLabel;
    // Look up previous state from the current market model list.
    final prevModel = _marketModels.where((m) => m.id == modelId).firstOrNull;
    final prevState = prevModel?.download?.stateLabel ?? 'not_started';
    if (newState == prevState) return;

    final name = _displayModelName(modelId);
    final l10n = context.l10n;
    debugPrint('[LocalModels] state transition: model=$modelId ($name), '
        '$prevState -> $newState');

    // Toast for terminal/async state transitions only.
    // User-initiated actions (start/pause) are toasted from button handlers.
    switch (newState) {
      case 'completed':
        debugPrint('[LocalModels] toast: download completed -> $name');
        showToast(l10n.localModelsDownloadCompletedToast(name), type: ToastType.success);
        break;
      case 'failed':
        final reason = download.errorMessage.trim().isNotEmpty
            ? download.errorMessage.trim()
            : l10n.localModelsDownloadErrorUnknown;
        debugPrint('[LocalModels] toast: download failed -> $name, reason=$reason');
        showToast(l10n.localModelsDownloadFailedToast(name, reason), type: ToastType.error);
        break;
      case 'cancelled':
        final reason = download.errorMessage.trim().isNotEmpty
            ? download.errorMessage.trim()
            : l10n.localModelsDownloadErrorUnknown;
        debugPrint('[LocalModels] toast: download cancelled -> $name, reason=$reason');
        showToast(l10n.localModelsDownloadCancelledToast(name, reason), type: ToastType.error);
        break;
    }
  }

  Future<void> _switchInferenceBackend(String backend) async {
    try {
      await MnnLocalModelsService.setBackend(backend);
      if (!mounted) return;
      setState(() {
        _loadingConfig = true;
        _loadingInstalled = true;
        _loadingMarket = true;
      });
      await _bootstrap();
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.localModelsSwitchBackendFailed, type: ToastType.error);
    }
  }

  Future<void> _setActiveModel(String? modelId) async {
    try {
      final config = await MnnLocalModelsService.setActiveModel(modelId);
      if (!mounted) return;
      setState(() => _config = config);
      _refreshInstalled(silent: true);
      showToast(context.l10n.localModelsActiveModelUpdated);
    } catch (_) {
      showToast(context.l10n.localModelsSetActiveFailed, type: ToastType.error);
    }
  }

  Future<void> _savePortConfig({bool silent = false}) async {
    final text = _portController.text.trim();
    final port = int.tryParse(text);
    if (port == null || port <= 0) {
      if (!silent) {
        showToast(context.l10n.localModelsPortInvalid, type: ToastType.error);
      }
      if (_config != null) {
        _syncConfigControllers(_config!);
      }
      return;
    }
    try {
      final config = await MnnLocalModelsService.saveConfig(apiPort: port);
      if (!mounted) return;
      setState(() => _config = config);
      _syncConfigControllers(config);
      if (!silent) {
        showToast(context.l10n.localModelsPortUpdated);
      }
    } catch (_) {
      if (!silent) {
        showToast(context.l10n.localModelsPortSaveFailed, type: ToastType.error);
      }
    }
  }

  void _schedulePortSave() {
    _configSaveDebounce?.cancel();
    _configSaveDebounce = Timer(
      const Duration(milliseconds: 500),
      () => _savePortConfig(silent: true),
    );
  }

  Future<void> _toggleAutoStart(bool value) async {
    try {
      final config = await MnnLocalModelsService.saveConfig(
        autoStartOnAppOpen: value,
      );
      if (!mounted) return;
      setState(() => _config = config);
    } catch (_) {
      showToast(context.l10n.localModelsAutoPreheatSaveFailed, type: ToastType.error);
    }
  }

  Future<void> _changeDownloadProvider(String value) async {
    try {
      final config = await MnnLocalModelsService.saveConfig(
        downloadProvider: value,
      );
      if (!mounted) return;
      setState(() => _config = config);
      _refreshMarket(silent: true);
    } catch (_) {
      showToast(context.l10n.localModelsDownloadSourceSwitchFailed, type: ToastType.error);
    }
  }

  Future<void> _importModel() async {
    setState(() {
      _importing = true;
      _importProgress = 0.0;
      _importModelId = '';
    });

    try {
      final response = await MnnLocalModelsService.importModel();
      if (!mounted) return;

      final success = response['success'] == true;
      final error = (response['error'] ?? '').toString();

      if (error == 'cancelled') {
        // User cancelled the file picker, do nothing
      } else if (success) {
        showToast(
          context.l10n.localModelsImportSuccess,
          type: ToastType.success,
        );
        _refreshInstalled(silent: true);
      } else {
        showToast(
          context.l10n.localModelsImportFailed(error),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (mounted) {
        showToast(
          context.l10n.localModelsImportFailed(e.toString()),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) {
        setState(() => _importing = false);
      }
    }
  }

  void _updateMarketDownloadState(
    String modelId,
    MnnLocalDownloadInfo? download,
  ) {
    if (modelId.isEmpty || !mounted) {
      return;
    }
    final targetIndex = _marketModels.indexWhere((item) => item.id == modelId);
    if (targetIndex < 0) {
      return;
    }
    final current = _marketModels[targetIndex];
    final updated = MnnLocalModel(
      id: current.id,
      name: current.name,
      category: current.category,
      source: current.source,
      description: current.description,
      path: current.path,
      vendor: current.vendor,
      tags: current.tags,
      extraTags: current.extraTags,
      active: current.active,
      isLocal: current.isLocal,
      isPinned: current.isPinned,
      hasUpdate: current.hasUpdate,
      fileSize: current.fileSize,
      sizeB: current.sizeB,
      formattedSize: current.formattedSize,
      lastUsedAt: current.lastUsedAt,
      downloadedAt: current.downloadedAt,
      readOnly: current.readOnly,
      download: download,
    );
    setState(() {
      _marketModels = List<MnnLocalModel>.from(_marketModels)
        ..[targetIndex] = updated;
    });
  }

  MnnLocalDownloadInfo _downloadPlaceholder({
    required String stateLabel,
    double progress = 0,
    String progressStage = '',
  }) {
    return MnnLocalDownloadInfo(
      state: 0,
      stateLabel: stateLabel,
      progress: progress,
      savedSize: 0,
      totalSize: 0,
      speedInfo: '',
      errorMessage: '',
      progressStage: progressStage,
      currentFile: '',
      hasUpdate: false,
    );
  }

  String _downloadStatusLabel(MnnLocalDownloadInfo download) {
    final l10n = context.l10n;
    switch (download.stateLabel) {
      case 'preparing':
        return l10n.localModelsDownloadPreparing;
      case 'downloading':
        return download.progressStage.trim().isNotEmpty
            ? download.progressStage.trim()
            : l10n.localModelsDownloading;
      case 'paused':
        return l10n.localModelsDownloadPaused;
      case 'completed':
        return l10n.localModelsDownloadCompleted;
      case 'failed':
        return l10n.localModelsDownloadFailed;
      case 'cancelled':
        return l10n.localModelsDownloadCancelled;
      default:
        return l10n.localModelsNotDownloaded;
    }
  }

  Color _blend(Color first, Color second, double t) {
    return Color.lerp(first, second, t.clamp(0.0, 1.0))!;
  }

  Color _alpha(Color color, double opacity) {
    final safe = opacity.clamp(0.0, 1.0);
    return color.withAlpha((safe * 255).round());
  }

  Color _toneBaseColor(_AccentTone tone) {
    switch (tone) {
      case _AccentTone.accent:
        return _palette.accentPrimary;
      case _AccentTone.success:
        return const Color(0xFF3FA66C);
      case _AccentTone.info:
        return const Color(0xFF4C8DFF);
      case _AccentTone.warning:
        return const Color(0xFFD69A41);
      case _AccentTone.danger:
        return const Color(0xFFD86473);
      case _AccentTone.neutral:
        return _secondaryTextColor;
    }
  }

  Color _toneBackgroundColor(_AccentTone tone) {
    return _blend(
      _panelColor,
      _toneBaseColor(tone),
      _isDarkTheme ? 0.28 : 0.14,
    );
  }

  Color _toneBorderColor(_AccentTone tone) {
    return _blend(
      _borderColor,
      _toneBaseColor(tone),
      _isDarkTheme ? 0.58 : 0.36,
    );
  }

  Color _toneTextColor(_AccentTone tone) {
    return _blend(
      _primaryTextColor,
      _toneBaseColor(tone),
      _isDarkTheme ? 0.52 : 0.72,
    );
  }

  Color _buttonForegroundColor(Color background) {
    return ThemeData.estimateBrightnessForColor(background) == Brightness.dark
        ? Colors.white
        : const Color(0xFF111315);
  }

  String _displayModelName(String modelId) {
    final normalized = modelId.trim();
    if (normalized.isEmpty) {
      return '';
    }
    for (final model in [..._installedModels, ..._marketModels]) {
      if (model.id == normalized) {
        return model.name.trim().isEmpty ? normalized : model.name;
      }
    }
    return normalized;
  }

  String _modelSubtitle(MnnLocalModel model) {
    final parts = <String>[];
    if (model.vendor.trim().isNotEmpty) {
      parts.add(model.vendor.trim());
    }
    if (model.id.trim().isNotEmpty) {
      parts.add(model.id.trim());
    }
    if (parts.isEmpty) {
      return context.l10n.localModelsLocalInference;
    }
    return parts.join('  ·  ');
  }

  ButtonStyle _filledButtonStyle({required _AccentTone tone}) {
    final background = tone == _AccentTone.accent
        ? _blend(_palette.accentPrimary, _cardColor, _isDarkTheme ? 0.08 : 0.0)
        : _toneBaseColor(tone);
    return FilledButton.styleFrom(
      backgroundColor: background,
      foregroundColor: _buttonForegroundColor(background),
      disabledBackgroundColor: _alpha(
        _secondaryTextColor,
        _isDarkTheme ? 0.18 : 0.1,
      ),
      disabledForegroundColor: _tertiaryTextColor,
      elevation: 0,
      textStyle: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        fontFamily: AppTextStyles.fontFamily,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    );
  }

  ButtonStyle _softButtonStyle({required _AccentTone tone}) {
    return FilledButton.styleFrom(
      backgroundColor: _toneBackgroundColor(tone),
      foregroundColor: _toneTextColor(tone),
      disabledBackgroundColor: _alpha(
        _secondaryTextColor,
        _isDarkTheme ? 0.18 : 0.1,
      ),
      disabledForegroundColor: _tertiaryTextColor,
      elevation: 0,
      textStyle: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        fontFamily: AppTextStyles.fontFamily,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    );
  }

  ButtonStyle _outlinedButtonStyle({required _AccentTone tone}) {
    return OutlinedButton.styleFrom(
      foregroundColor: _toneTextColor(tone),
      disabledForegroundColor: _tertiaryTextColor,
      side: BorderSide(color: _toneBorderColor(tone)),
      textStyle: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        fontFamily: AppTextStyles.fontFamily,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    );
  }

  Widget _buildFieldShell({
    required String label,
    String? helper,
    IconData? icon,
    required Widget child,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: _primaryTextColor,
              fontFamily: AppTextStyles.fontFamily,
            ),
          ),
        ),
        const SizedBox(height: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          decoration: BoxDecoration(
            color: _panelColor,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: _borderColor),
          ),
          child: Row(
            children: [
              if (icon != null) ...[
                Icon(icon, size: 18, color: _secondaryTextColor),
                const SizedBox(width: 10),
              ],
              Expanded(child: child),
            ],
          ),
        ),
        if (helper != null) ...[
          const SizedBox(height: 6),
          Padding(
            padding: const EdgeInsets.only(left: 4),
            child: Text(
              helper,
              style: TextStyle(
                color: _secondaryTextColor,
                height: 1.45,
                fontFamily: AppTextStyles.fontFamily,
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildDropdownField({
    required String label,
    String? helper,
    IconData? icon,
    required String? value,
    required List<DropdownMenuItem<String>> items,
    required ValueChanged<String?>? onChanged,
    String? hintText,
    Key? key,
  }) {
    return _buildFieldShell(
      label: label,
      helper: helper,
      icon: icon,
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          key: key,
          value: value,
          isExpanded: true,
          hint: hintText == null
              ? null
              : Text(
                  hintText,
                  style: TextStyle(
                    color: _tertiaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: AppTextStyles.fontFamily,
                  ),
                ),
          dropdownColor: _cardColor,
          borderRadius: BorderRadius.circular(16),
          icon: Icon(
            Icons.keyboard_arrow_down_rounded,
            color: _secondaryTextColor,
          ),
          style: TextStyle(
            color: _primaryTextColor,
            fontWeight: FontWeight.w600,
            fontFamily: AppTextStyles.fontFamily,
          ),
          items: items,
          onChanged: onChanged,
        ),
      ),
    );
  }

  Widget _buildTextFieldShell({
    required String label,
    String? helper,
    IconData? icon,
    required TextEditingController controller,
    TextInputType? keyboardType,
    required ValueChanged<String> onChanged,
    required ValueChanged<String> onSubmitted,
    String? hintText,
  }) {
    return _buildFieldShell(
      label: label,
      helper: helper,
      icon: icon,
      child: TextField(
        controller: controller,
        keyboardType: keyboardType,
        onChanged: onChanged,
        onSubmitted: onSubmitted,
        style: TextStyle(
          color: _primaryTextColor,
          fontWeight: FontWeight.w600,
          fontFamily: AppTextStyles.fontFamily,
        ),
        decoration: InputDecoration(
          isCollapsed: true,
          isDense: true,
          hintText: hintText,
          border: InputBorder.none,
          enabledBorder: InputBorder.none,
          focusedBorder: InputBorder.none,
          disabledBorder: InputBorder.none,
          errorBorder: InputBorder.none,
          focusedErrorBorder: InputBorder.none,
          contentPadding: EdgeInsets.zero,
          hintStyle: TextStyle(
            color: _tertiaryTextColor,
            fontWeight: FontWeight.w500,
            fontFamily: AppTextStyles.fontFamily,
          ),
        ),
      ),
    );
  }

  Widget _buildInlineNotice({
    required _AccentTone tone,
    required IconData icon,
    required String title,
    required String message,
  }) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: _toneBackgroundColor(tone),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: _toneBorderColor(tone)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: _toneTextColor(tone)),
          const SizedBox(width: 10),
          Expanded(
            child: Text.rich(
              TextSpan(
                children: [
                  TextSpan(
                    text: '$title: ',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: _toneTextColor(tone),
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                  TextSpan(
                    text: message,
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.5,
                      fontWeight: FontWeight.w600,
                      color: _toneTextColor(tone),
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSwitchRow({
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => onChanged(!value),
        borderRadius: BorderRadius.circular(14),
        splashColor: _palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 6, 0, 6),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: _primaryTextColor,
                        fontFamily: AppTextStyles.fontFamily,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 12,
                        height: 1.55,
                        color: _secondaryTextColor,
                        fontFamily: AppTextStyles.fontFamily,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Switch.adaptive(
                value: value,
                onChanged: onChanged,
                activeThumbColor: Colors.white,
                activeTrackColor: _palette.accentPrimary,
                inactiveThumbColor: Colors.white,
                inactiveTrackColor: _alpha(_secondaryTextColor, 0.28),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildListDivider() {
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Divider(
        height: 1,
        thickness: 1,
        color: _borderColor.withValues(alpha: _isDarkTheme ? 0.52 : 0.84),
      ),
    );
  }

  Widget _buildModelList<T>({
    required List<T> items,
    required Widget Function(T item) itemBuilder,
  }) {
    return Column(
      children: List.generate(items.length, (index) {
        final isLast = index == items.length - 1;
        return Column(
          children: [
            itemBuilder(items[index]),
            if (!isLast) _buildListDivider(),
          ],
        );
      }),
    );
  }

  Widget _buildMetaLine({
    required String label,
    required String value,
    bool monospace = false,
    Color? valueColor,
  }) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
              color: _tertiaryTextColor,
              fontFamily: AppTextStyles.fontFamily,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            maxLines: monospace ? 3 : 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 13,
              height: 1.45,
              color: valueColor ?? _secondaryTextColor,
              fontWeight: monospace ? FontWeight.w500 : FontWeight.w600,
              fontFamily: monospace ? 'monospace' : AppTextStyles.fontFamily,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBackendDropdown({required String backend}) {
    return _buildDropdownField(
      key: ValueKey('backend-$backend'),
      label: context.l10n.localModelsInferenceBackend,
      icon: Icons.settings_input_component_rounded,
      value: backend,
      items: const [
        DropdownMenuItem(
          value: _llamaCppBackend,
          child: Text('OmniInfer-llama'),
        ),
        DropdownMenuItem(
          value: _omniinferMnnBackend,
          child: Text('omniinfer-mnn'),
        ),
        DropdownMenuItem(
          value: _omniinferQnnBackend,
          child: Text('omniinfer-npu'),
        ),
        DropdownMenuItem(
          value: _omniinferLiteRtBackend,
          child: Text('LiteRT-LM'),
        ),
      ],
      onChanged: (value) async {
        if (value == null || value == backend) return;
        await _switchInferenceBackend(value);
      },
    );
  }

  Widget _buildServiceTab() {
    if (_loadingConfig && _config == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final config = _config;
    if (config == null) {
      return _buildEmptyState(title: context.l10n.localModelsConfigLoadFailed, subtitle: context.l10n.localModelsConfigLoadFailedDesc);
    }
    return RefreshIndicator(
      color: _palette.accentPrimary,
      backgroundColor: _cardColor,
      onRefresh: () async {
        await Future.wait([
          _refreshConfig(silent: true),
          _refreshInstalled(silent: true),
        ]);
      },
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
        children: [
          SettingsSectionTitle(
            label: context.l10n.localModelsServiceControl,
            subtitle: context.l10n.localModelsServiceControlDesc,
          ),
          _buildBackendDropdown(backend: config.backend),
          const SizedBox(height: 12),
          _buildDropdownField(
            key: ValueKey(
              'active-model-${config.backend}-${config.activeModelId}',
            ),
            label: context.l10n.localModelsCurrentModel,
            helper: context.l10n.localModelsCurrentModelHint,
            icon: Icons.memory_rounded,
            value:
                config.activeModelId.isNotEmpty &&
                    _serviceModels.any(
                      (item) => item.id == config.activeModelId,
                    )
                ? config.activeModelId
                : null,
            hintText: _serviceModels.isEmpty ? context.l10n.localModelsNoAvailableModels : context.l10n.localModelsSelectModel,
            items: _serviceModels
                .map(
                  (item) => DropdownMenuItem<String>(
                    value: item.id,
                    child: Text(item.name),
                  ),
                )
                .toList(),
            onChanged: _serviceModels.isEmpty ? null : _setActiveModel,
          ),
          const SizedBox(height: 12),
          _buildTextFieldShell(
            label: context.l10n.localModelsServicePort,
            icon: Icons.settings_ethernet_rounded,
            controller: _portController,
            keyboardType: TextInputType.number,
            hintText: context.l10n.localModelsServicePortHint,
            onChanged: (_) {
              setState(() {});
              _schedulePortSave();
            },
            onSubmitted: (_) => _savePortConfig(),
          ),
          if (config.loadedModelId.isNotEmpty) ...[
            const SizedBox(height: 12),
            _buildInlineNotice(
              tone: _AccentTone.success,
              icon: Icons.check_circle_rounded,
              title: context.l10n.localModelsCurrentlyLoaded,
              message:
                  '${_backendLabel(config.loadedBackend)} / ${_displayModelName(config.loadedModelId)}',
            ),
          ],
          const SizedBox(height: 24),
          SettingsSectionTitle(
            label: context.l10n.localModelsAutoPreheatSection,
            subtitle: context.l10n.localModelsAutoPreheatSectionDesc,
          ),
          _buildSwitchRow(
            title: context.l10n.localModelsAutoPreheat,
            subtitle: context.l10n.localModelsAutoPreheatDesc,
            value: config.autoStartOnAppOpen,
            onChanged: _toggleAutoStart,
          ),
          const SizedBox(height: 24),
          SettingsSectionTitle(
            label: context.l10n.localModelsInstalled,
            subtitle: context.l10n.localModelsInstalledDesc,
          ),
          if (_importing)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    context.l10n.localModelsImporting(_importModelId),
                    style: TextStyle(
                      fontSize: 13,
                      color: _secondaryTextColor,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(999),
                    child: LinearProgressIndicator(
                      value: _importProgress,
                      minHeight: 8,
                      backgroundColor: _alpha(_secondaryTextColor, 0.14),
                      valueColor: AlwaysStoppedAnimation<Color>(
                        _toneBaseColor(_AccentTone.info),
                      ),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${(_importProgress * 100).toStringAsFixed(1)}%',
                    style: TextStyle(
                      fontSize: 12,
                      color: _secondaryTextColor,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                ],
              ),
            )
          else
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: FilledButton.icon(
                onPressed: _importModel,
                style: _softButtonStyle(tone: _AccentTone.accent),
                icon: const Icon(Icons.folder_open_rounded, size: 18),
                label: Text(context.l10n.localModelsImportFromDevice),
              ),
            ),
          _buildSearchBar(
            controller: _installedSearchController,
            hintText: context.l10n.localModelsSearchHint,
            onSubmitted: (_) => setState(() {}),
            onRefresh: () => _refreshInstalled(),
          ),
          const SizedBox(height: 12),
          if (_loadingInstalled && _installedModels.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 40),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_filteredInstalledModels.isEmpty)
            _buildEmptyState(
              title: context.l10n.localModelsEmpty,
              subtitle: context.l10n.localModelsEmptyDesc,
            )
          else
            _buildModelList(
              items: _filteredInstalledModels,
              itemBuilder: _buildInstalledCard,
            ),
        ],
      ),
    );
  }

  Widget _buildMarketTab() {
    final config = _config;
    return RefreshIndicator(
      color: _palette.accentPrimary,
      backgroundColor: _cardColor,
      onRefresh: () => _refreshMarket(refresh: true),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
        children: [
          SettingsSectionTitle(
            label: context.l10n.localModelsFilterAndSource,
            subtitle: context.l10n.localModelsFilterAndSourceDesc,
          ),
          _buildBackendDropdown(backend: config?.backend ?? _llamaCppBackend),
          if (config?.availableSources.isNotEmpty == true) ...[
            const SizedBox(height: 12),
            _buildDropdownField(
              key: ValueKey(
                'download-provider-${config?.downloadProvider ?? 'ModelScope'}',
              ),
              label: context.l10n.localModelsDownloadSource,
              icon: Icons.public_rounded,
              value: config?.downloadProvider.isNotEmpty == true
                  ? config!.downloadProvider
                  : null,
              hintText: context.l10n.localModelsSelectDownloadSource,
              items: config!.availableSources
                  .map(
                    (value) => DropdownMenuItem<String>(
                      value: value,
                      child: Text(value),
                    ),
                  )
                  .toList(),
              onChanged: (value) {
                if (value == null) return;
                _changeDownloadProvider(value);
              },
            ),
          ],
          const SizedBox(height: 24),
          SettingsSectionTitle(
            label: context.l10n.localModelsMarketModels,
            subtitle: context.l10n.localModelsMarketModelsDesc,
          ),
          _buildSearchBar(
            controller: _marketSearchController,
            hintText: context.l10n.localModelsMarketSearchHint,
            onSubmitted: (_) => _refreshMarket(),
            onRefresh: () => _refreshMarket(refresh: true),
          ),
          const SizedBox(height: 12),
          if (_loadingMarket && _marketModels.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 40),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_marketModels.isEmpty)
            _buildEmptyState(title: context.l10n.localModelsMarketEmpty, subtitle: context.l10n.localModelsMarketEmptyDesc)
          else
            _buildModelList(
              items: _sortedMarketModels,
              itemBuilder: _buildMarketCard,
            ),
        ],
      ),
    );
  }

  Widget _buildInstalledCard(MnnLocalModel model) {
    final modelSizeText = _resolvedModelSizeText(model);
    final isLoaded = _config?.loadedModelId == model.id;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 4, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      model.name,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: _primaryTextColor,
                        fontFamily: AppTextStyles.fontFamily,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _modelSubtitle(model),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 12,
                        color: _secondaryTextColor,
                        fontWeight: FontWeight.w500,
                        fontFamily: AppTextStyles.fontFamily,
                      ),
                    ),
                  ],
                ),
              ),
              if (model.active || isLoaded)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: _toneBackgroundColor(
                      model.active ? _AccentTone.accent : _AccentTone.success,
                    ),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: _toneBorderColor(
                        model.active ? _AccentTone.accent : _AccentTone.success,
                      ),
                    ),
                  ),
                  child: Text(
                    model.active ? context.l10n.localModelsCurrentDefault : context.l10n.localModelsLoaded,
                    style: TextStyle(
                      fontSize: 12,
                      color: _toneTextColor(
                        model.active ? _AccentTone.accent : _AccentTone.success,
                      ),
                      fontWeight: FontWeight.w700,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildTag(model.category.toUpperCase()),
              if (model.source.isNotEmpty) _buildTag(model.source),
              if (model.vendor.isNotEmpty) _buildTag(model.vendor),
              for (final tag in model.tags.take(4)) _buildTag(tag),
            ],
          ),
          if (modelSizeText != null)
            _buildMetaLine(label: context.l10n.localModelsFileSize, value: modelSizeText),
          if (model.path.isNotEmpty)
            _buildMetaLine(label: context.l10n.localModelsModelDir, value: model.path, monospace: true),
          const SizedBox(height: 10),
          Text(
            model.readOnly ? context.l10n.localModelsManualDir : context.l10n.localModelsOmniInferLoadable,
            style: TextStyle(
              fontSize: 12,
              height: 1.5,
              color: model.readOnly
                  ? _toneTextColor(_AccentTone.warning)
                  : _secondaryTextColor,
              fontFamily: AppTextStyles.fontFamily,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (!model.active)
                FilledButton.icon(
                  onPressed: () => _setActiveModel(model.id),
                  style: _softButtonStyle(tone: _AccentTone.accent),
                  icon: const Icon(
                    Icons.playlist_add_check_circle_rounded,
                    size: 18,
                  ),
                  label: Text(context.l10n.localModelsSetAsCurrent),
                ),
              if (!model.readOnly)
                OutlinedButton.icon(
                  onPressed: () async {
                    await MnnLocalModelsService.deleteModel(model.id);
                    _refreshInstalled(silent: true);
                    _refreshMarket(silent: true);
                  },
                  style: _outlinedButtonStyle(tone: _AccentTone.danger),
                  icon: const Icon(Icons.delete_outline_rounded, size: 18),
                  label: Text(context.l10n.localModelsDelete),
                ),
            ],
          ),
        ],
      ),
    );
  }

  bool _isPinnedModel(MnnLocalModel model) {
    final pinId = widget.pinnedModelId;
    if (pinId == null || pinId.isEmpty) return false;
    return model.id.contains(pinId) || model.name.contains(pinId);
  }

  Widget _buildMarketCard(MnnLocalModel model) {
    final download = model.download;
    final isCompleted = download?.isCompleted == true;
    final isDownloading = download?.isDownloading == true;
    final isPaused = download?.isPaused == true;
    final isFailed = download?.stateLabel == 'failed';
    final modelSizeText = _resolvedModelSizeText(model);
    final tone = isCompleted
        ? _AccentTone.success
        : isDownloading
        ? _AccentTone.info
        : isPaused
        ? _AccentTone.warning
        : isFailed
        ? _AccentTone.danger
        : _AccentTone.neutral;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 4, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      model.name,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: _primaryTextColor,
                        fontFamily: AppTextStyles.fontFamily,
                      ),
                    ),
                    if (model.description.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text(
                        model.description,
                        style: TextStyle(
                          color: _secondaryTextColor,
                          height: 1.5,
                          fontFamily: AppTextStyles.fontFamily,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (download != null)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: _toneBackgroundColor(tone),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: _toneBorderColor(tone)),
                  ),
                  child: Text(
                    _downloadStatusLabel(download),
                    style: TextStyle(
                      fontSize: 12,
                      color: _toneTextColor(tone),
                      fontWeight: FontWeight.w700,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (_isPinnedModel(model)) _buildAccentTag(context.trLegacy('推荐')),
              _buildTag(model.category.toUpperCase()),
              if (model.source.isNotEmpty) _buildTag(model.source),
              if (model.vendor.isNotEmpty) _buildTag(model.vendor),
              if (model.hasUpdate) _buildTag(context.l10n.localModelsHasUpdate),
              for (final tag in model.tags.take(4)) _buildTag(tag),
            ],
          ),
          if (modelSizeText != null)
            _buildMetaLine(label: context.l10n.localModelsFileSize, value: modelSizeText),
          if (download != null && (isDownloading || isPaused || isFailed))
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(999),
                    child: LinearProgressIndicator(
                      value: download.progress,
                      minHeight: 8,
                      backgroundColor: _alpha(_secondaryTextColor, 0.14),
                      valueColor: AlwaysStoppedAnimation<Color>(
                        _toneBaseColor(
                          tone == _AccentTone.neutral ? _AccentTone.info : tone,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    '${(download.progress * 100).toStringAsFixed(1)}%  ${download.speedInfo}'
                        .trim(),
                    style: TextStyle(
                      fontSize: 12,
                      color: _secondaryTextColor,
                      fontWeight: FontWeight.w600,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                  if (download.progressStage.trim().isNotEmpty)
                    _buildMetaLine(
                      label: context.l10n.localModelsStage,
                      value: download.progressStage.trim(),
                    ),
                  if (isFailed && download.errorMessage.trim().isNotEmpty)
                    _buildMetaLine(
                      label: context.l10n.localModelsErrorInfo,
                      value: download.errorMessage.trim(),
                      valueColor: _toneTextColor(_AccentTone.danger),
                    ),
                ],
              ),
            ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              if (!isCompleted && !isDownloading)
                FilledButton.icon(
                  onPressed: () async {
                    debugPrint('[LocalModels] button: startDownload model=${model.id} (${model.name}), isPaused=$isPaused, isFailed=$isFailed');
                    _updateMarketDownloadState(
                      model.id,
                      _downloadPlaceholder(
                        stateLabel: 'preparing',
                        progress: download?.progress ?? 0,
                        progressStage: download?.progressStage ?? '',
                      ),
                    );
                    try {
                      await MnnLocalModelsService.startDownload(model.id);
                      debugPrint('[LocalModels] button: startDownload success model=${model.id}');
                      if (mounted) {
                        showToast(
                          context.l10n.localModelsDownloadStartedToast(model.name),
                          type: ToastType.success,
                        );
                      }
                      _refreshMarket(silent: true);
                    } catch (e) {
                      debugPrint('[LocalModels] button: startDownload error model=${model.id}, error=$e');
                      _refreshMarket(silent: true);
                      showToast(context.l10n.localModelsDownloadStartFailed, type: ToastType.error);
                    }
                  },
                  style: _filledButtonStyle(tone: _AccentTone.accent),
                  icon: Icon(
                    isPaused
                        ? Icons.play_arrow_rounded
                        : Icons.download_rounded,
                    size: 18,
                  ),
                  label: Text(
                    isPaused
                         ? context.l10n.localModelsResumeDownload
                        : isFailed
                         ? context.l10n.localModelsRetryDownload
                         : context.l10n.localModelsDownloadModel,
                  ),
                ),
              if (isDownloading)
                OutlinedButton.icon(
                  onPressed: () async {
                    debugPrint('[LocalModels] button: pauseDownload model=${model.id} (${model.name})');
                    _updateMarketDownloadState(
                      model.id,
                      _downloadPlaceholder(
                        stateLabel: 'paused',
                        progress: download?.progress ?? 0,
                        progressStage: download?.progressStage ?? '',
                      ),
                    );
                    try {
                      await MnnLocalModelsService.pauseDownload(model.id);
                      debugPrint('[LocalModels] button: pauseDownload success model=${model.id}');
                      if (mounted) {
                        showToast(
                          context.l10n.localModelsDownloadPausedToast(model.name),
                          type: ToastType.warning,
                        );
                      }
                      _refreshMarket(silent: true);
                    } catch (e) {
                      debugPrint('[LocalModels] button: pauseDownload error model=${model.id}, error=$e');
                      _refreshMarket(silent: true);
                      showToast(context.l10n.localModelsDownloadPauseFailed, type: ToastType.error);
                    }
                  },
                  style: _outlinedButtonStyle(tone: _AccentTone.warning),
                  icon: const Icon(Icons.pause_rounded, size: 18),
                  label: Text(context.l10n.localModelsPause),
                ),
              if (isPaused || isFailed)
                OutlinedButton.icon(
                  onPressed: () async {
                    debugPrint('[LocalModels] button: deletePartFiles model=${model.id} (${model.name})');
                    try {
                      await MnnLocalModelsService.deleteModel(model.id);
                      if (mounted) {
                        showToast(
                          context.l10n.localModelsDelete,
                          type: ToastType.success,
                        );
                      }
                      _refreshMarket(silent: true);
                      _refreshInstalled(silent: true);
                    } catch (e) {
                      debugPrint('[LocalModels] button: deletePartFiles error model=${model.id}, error=$e');
                      _refreshMarket(silent: true);
                    }
                  },
                  style: _outlinedButtonStyle(tone: _AccentTone.danger),
                  icon: const Icon(Icons.delete_outline_rounded, size: 18),
                  label: Text(context.l10n.localModelsDelete),
                ),
              if (isCompleted)
                FilledButton.icon(
                  onPressed: () async {
                    await MnnLocalModelsService.deleteModel(model.id);
                    _refreshMarket(silent: true);
                    _refreshInstalled(silent: true);
                  },
                  style: _softButtonStyle(
                    tone: model.hasUpdate
                        ? _AccentTone.warning
                        : _AccentTone.neutral,
                  ),
                  icon: const Icon(Icons.delete_outline_rounded, size: 18),
                  label: Text(model.hasUpdate ? context.l10n.localModelsDeleteOldVersion : context.l10n.localModelsDelete),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildTabSwitcher() {
    return AnimatedBuilder(
      animation: _tabController.animation!,
      builder: (context, _) {
        final tabAnimationValue =
            _tabController.animation?.value ?? _tabController.index.toDouble();
        final options = <OmniSegmentedOption<_LocalModelsTab>>[
          OmniSegmentedOption<_LocalModelsTab>(
            value: _LocalModelsTab.service,
            label: context.l10n.localModelsTabService,
            icon: Icons.hub_rounded,
            id: 'service',
          ),
          OmniSegmentedOption<_LocalModelsTab>(
            value: _LocalModelsTab.market,
            label: context.l10n.localModelsTabMarket,
            icon: Icons.storefront_rounded,
            id: 'market',
          ),
        ];
        return Padding(
          padding: const EdgeInsets.fromLTRB(18, 10, 18, 6),
          child: OmniSegmentedSlider<_LocalModelsTab>(
            key: const ValueKey('local-models-tab-slider'),
            value:
                _LocalModelsTab.values[tabAnimationValue.round().clamp(
                  0,
                  _LocalModelsTab.values.length - 1,
                )],
            options: options,
            position: tabAnimationValue,
            keyPrefix: 'local-models-tab',
            onChanged: (nextTab) {
              if (_tabController.index != nextTab.index) {
                _tabController.animateTo(nextTab.index);
              }
            },
          ),
        );
      },
    );
  }

  Widget _buildSearchBar({
    required TextEditingController controller,
    required String hintText,
    required ValueChanged<String> onSubmitted,
    required VoidCallback onRefresh,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: _borderColor),
      ),
      child: Row(
        children: [
          Icon(Icons.search_rounded, size: 18, color: _secondaryTextColor),
          const SizedBox(width: 10),
          Expanded(
            child: TextField(
              controller: controller,
              textInputAction: TextInputAction.search,
              onChanged: (_) => setState(() {}),
              onSubmitted: onSubmitted,
              style: TextStyle(
                fontSize: 14,
                color: _primaryTextColor,
                fontWeight: FontWeight.w600,
                fontFamily: AppTextStyles.fontFamily,
              ),
              decoration: InputDecoration(
                isCollapsed: true,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                disabledBorder: InputBorder.none,
                errorBorder: InputBorder.none,
                focusedErrorBorder: InputBorder.none,
                isDense: true,
                hintText: hintText,
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),
          if (controller.text.isNotEmpty) ...[
            const SizedBox(width: 8),
            GestureDetector(
              onTap: () {
                controller.clear();
                setState(() {});
                onSubmitted('');
              },
              child: Icon(
                Icons.close_rounded,
                size: 18,
                color: _secondaryTextColor,
              ),
            ),
          ],
          const SizedBox(width: 8),
          Container(width: 1, height: 18, color: _borderColor),
          const SizedBox(width: 8),
          IconButton(
            tooltip: context.l10n.localModelsRefresh,
            onPressed: onRefresh,
            style: IconButton.styleFrom(
              minimumSize: const Size(34, 34),
              padding: EdgeInsets.zero,
              backgroundColor: Colors.transparent,
              foregroundColor: _primaryTextColor,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            icon: const Icon(Icons.refresh_rounded, size: 18),
          ),
        ],
      ),
    );
  }

  Widget _buildAccentTag(String value) {
    final accentColor = _palette.accentPrimary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: accentColor.withOpacity(0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: accentColor.withOpacity(0.3)),
      ),
      child: Text(
        value,
        style: TextStyle(
          fontSize: 12,
          color: accentColor,
          fontWeight: FontWeight.w700,
          fontFamily: AppTextStyles.fontFamily,
        ),
      ),
    );
  }

  Widget _buildTag(String value) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: _tagSurfaceColor,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: _borderColor),
      ),
      child: Text(
        value,
        style: TextStyle(
          fontSize: 12,
          color: _secondaryTextColor,
          fontWeight: FontWeight.w600,
          fontFamily: AppTextStyles.fontFamily,
        ),
      ),
    );
  }

  Widget _buildEmptyState({required String title, required String subtitle}) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 42),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 54,
              height: 54,
              decoration: BoxDecoration(
                color: _panelColor,
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: _borderColor),
              ),
              child: Icon(
                Icons.offline_bolt_outlined,
                size: 28,
                color: _tertiaryTextColor,
              ),
            ),
            const SizedBox(height: 16),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                color: _primaryTextColor,
                fontFamily: AppTextStyles.fontFamily,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                height: 1.6,
                color: _secondaryTextColor,
                fontFamily: AppTextStyles.fontFamily,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _backendLabel(String backend) {
    switch (backend) {
      case _omniinferMnnBackend:
        return 'omniinfer-mnn';
      case _omniinferQnnBackend:
        return 'omniinfer-npu';
      case _omniinferLiteRtBackend:
        return 'LiteRT-LM';
      case _llamaCppBackend:
      default:
        return 'OmniInfer-llama';
    }
  }

  double _modelSizeSortValue(MnnLocalModel model) {
    if (model.sizeB > 0) {
      return model.sizeB;
    }
    if (model.fileSize > 0) {
      return model.fileSize.toDouble();
    }
    final totalSize = model.download?.totalSize.toDouble() ?? 0;
    if (totalSize > 0) {
      return totalSize;
    }
    return double.infinity;
  }

  String? _resolvedModelSizeText(MnnLocalModel model) {
    final formatted = model.formattedSize.trim();
    if (formatted.isNotEmpty) {
      return formatted;
    }
    final bytes = model.fileSize > 0
        ? model.fileSize.toDouble()
        : model.download?.totalSize.toDouble() ?? 0;
    if (bytes <= 0) {
      return null;
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var size = bytes;
    var unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex += 1;
    }
    final fractionDigits = size >= 100 || unitIndex == 0 ? 0 : 1;
    return '${size.toStringAsFixed(fractionDigits)} ${units[unitIndex]}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _palette.pageBackground,
      appBar: CommonAppBar(title: context.l10n.localModelsTitle, primary: true),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            _buildTabSwitcher(),
            Expanded(
              child: TabBarView(
                controller: _tabController,
                physics: const BouncingScrollPhysics(),
                children: [_buildServiceTab(), _buildMarketTab()],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
