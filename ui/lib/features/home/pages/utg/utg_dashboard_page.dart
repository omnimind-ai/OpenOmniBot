import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class UtgDashboardPage extends StatefulWidget {
  const UtgDashboardPage({super.key});

  @override
  State<UtgDashboardPage> createState() => _UtgDashboardPageState();
}

class _UtgDashboardPageState extends State<UtgDashboardPage> {
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _startCommandController = TextEditingController();
  final TextEditingController _workingDirectoryController =
      TextEditingController();
  final TextEditingController _functionSearchController =
      TextEditingController();

  bool _loading = true;
  bool _saving = false;
  bool _loadingFunctions = false;
  bool _utgEnabled = true;
  bool _providerAutoStartEnabled = true;
  String? _runningFunctionId;
  String? _deletingFunctionId;
  String? _expandedFunctionId;
  String? _viewingFunctionId;
  String? _providerControlAction;
  String? _highlightedFunctionId;
  bool _resettingAllData = false;
  bool _checkingUpdate = false;
  bool _applyingUpdate = false;
  UtgUpdateCheckResult? _updateCheckResult;
  final Map<String, Map<String, dynamic>> _functionBundleCache = {};
  final Map<String, String> _functionBundleErrorById = {};
  final Set<String> _expandedStepKeys = <String>{};
  final Set<String> _expandedFunctionKeys = <String>{};

  UtgBridgeConfig? _config;
  UtgFunctionsSnapshot? _functionsSnapshot;
  UtgRunLogsSnapshot? _runLogsSnapshot;
  String? _functionsError;

  @override
  void initState() {
    super.initState();
    _refreshAll();
  }

  @override
  void dispose() {
    _baseUrlController.dispose();
    _startCommandController.dispose();
    _workingDirectoryController.dispose();
    _functionSearchController.dispose();
    super.dispose();
  }

  Future<void> _refreshAll() async {
    setState(() => _loading = true);
    try {
      final config = await AssistsMessageService.getUtgBridgeConfig();
      if (!mounted) return;
      _applyConfig(config);
      await Future.wait([
        _loadFunctions(baseUrl: config.resolvedOmniflowBaseUrl, silent: true),
        _loadRunLogs(baseUrl: config.resolvedOmniflowBaseUrl, silent: true),
      ]);
    } on PlatformException catch (e) {
      showToast(e.message ?? '加载 OmniFlow 配置失败', type: ToastType.error);
    } catch (e) {
      showToast('加载 OmniFlow 配置失败', type: ToastType.error);
      debugPrint('Load OmniFlow config failed: $e');
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  void _applyConfig(UtgBridgeConfig config) {
    _config = config;
    _utgEnabled = config.utgEnabled;
    _providerAutoStartEnabled = config.providerAutoStartEnabled;
    _baseUrlController.text = config.omniflowBaseUrl;
    _startCommandController.text = config.providerStartCommand;
    _workingDirectoryController.text = config.providerWorkingDirectory ?? '';
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _saveConfig() async {
    setState(() => _saving = true);
    try {
      final saved = await AssistsMessageService.saveUtgBridgeConfig(
        utgEnabled: _utgEnabled,
        providerAutoStartEnabled: _providerAutoStartEnabled,
        omniflowBaseUrl: _baseUrlController.text.trim(),
        providerStartCommand: _startCommandController.text.trim(),
        providerWorkingDirectory: _workingDirectoryController.text.trim(),
      );
      if (!mounted) return;
      _applyConfig(saved);
      await Future.wait([
        _loadFunctions(baseUrl: saved.resolvedOmniflowBaseUrl, silent: true),
        _loadRunLogs(baseUrl: saved.resolvedOmniflowBaseUrl, silent: true),
      ]);
      showToast('OmniFlow 配置已保存', type: ToastType.success);
    } on PlatformException catch (e) {
      showToast(e.message ?? '保存 OmniFlow 配置失败', type: ToastType.error);
    } catch (e) {
      showToast('保存 OmniFlow 配置失败', type: ToastType.error);
      debugPrint('Save OmniFlow config failed: $e');
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  void _openFunctionsBrowser() {
    // 跳转到 Flutter 原生功能库页面
    context.push('/task/function_library');
  }

  Future<void> _loadFunctions({String? baseUrl, bool silent = false}) async {
    if (!silent) {
      setState(() {
        _loadingFunctions = true;
        _functionsError = null;
      });
    } else {
      _loadingFunctions = true;
      _functionsError = null;
    }
    try {
      final snapshot = await AssistsMessageService.getUtgFunctions(
        baseUrl: baseUrl,
      );
      if (!mounted) return;
      setState(() {
        _functionsSnapshot = snapshot;
        _functionsError = null;
      });
    } catch (e) {
      final errorText = e.toString().replaceFirst('Exception: ', '');
      if (!mounted) return;
      setState(() {
        _functionsSnapshot = null;
        _functionsError = errorText;
      });
      if (!silent) {
        showToast('加载 OmniFlow 资产失败：$errorText', type: ToastType.error);
      }
    } finally {
      if (mounted) {
        setState(() => _loadingFunctions = false);
      } else {
        _loadingFunctions = false;
      }
    }
  }

  Future<void> _loadRunLogs({String? baseUrl, bool silent = false}) async {
    try {
      final snapshot = await AssistsMessageService.getUtgRunLogs(
        baseUrl: baseUrl,
      );
      if (!mounted) return;
      setState(() {
        _runLogsSnapshot = snapshot;
      });
    } catch (e) {
      if (!mounted) return;
      if (!silent) {
        final errorText = e.toString().replaceFirst('Exception: ', '');
        showToast('加载 OmniFlow run_logs 失败：$errorText', type: ToastType.error);
      }
    }
  }

  Widget _buildCard({required Widget child}) {
    return Container(
      decoration: BoxDecoration(
        color: context.omniPalette.surfacePrimary,
        borderRadius: BorderRadius.circular(18),
        boxShadow: [
          BoxShadow(
            color: context.omniPalette.shadowColor,
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      padding: const EdgeInsets.all(16),
      child: child,
    );
  }

  Widget _buildPill(
    String text, {
    Color? backgroundColor,
    Color? textColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: textColor ?? palette.textSecondary,
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    final display = value.trim().isEmpty ? '未设置' : value.trim();
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: context.omniPalette.textSecondary,
            ),
          ),
          const SizedBox(height: 4),
          SelectableText(
            display,
            style: TextStyle(fontSize: 13, color: context.omniPalette.textPrimary),
          ),
        ],
      ),
    );
  }

  Widget _buildFunctionCard(UtgFunctionSummary function) {
    final description = function.description.trim().isEmpty
        ? '无描述'
        : function.description;
    final running = _runningFunctionId == function.functionId;
    final deleting = _deletingFunctionId == function.functionId;
    final viewing = _viewingFunctionId == function.functionId;
    final expanded = _expandedFunctionId == function.functionId;
    final highlighted = _highlightedFunctionId == function.functionId;
    final bundle = _functionBundleCache[function.functionId];
    final bundleError = _functionBundleErrorById[function.functionId];
    final lastRun = function.lastRun;
    final lastRunSuccess = lastRun['success'] == true;
    final hasLastRun = lastRun.isNotEmpty;
    return Container(
      decoration: BoxDecoration(
        color: highlighted ? const Color(0xFFFFFBF0) : context.omniPalette.surfacePrimary,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: highlighted ? const Color(0xFFF59E0B) : Colors.transparent,
          width: highlighted ? 1.5 : 1,
        ),
        boxShadow: const [
          BoxShadow(
            color: Color(0x11000000),
            blurRadius: 18,
            offset: Offset(0, 8),
          ),
        ],
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            function.functionId,
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: context.omniPalette.textPrimary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            description,
            style: TextStyle(
              fontSize: 13,
              color: context.omniPalette.textSecondary,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (highlighted) _buildPill('新导入'),
              _buildPill(_assetKindLabel(function)),
              if (function.appName.trim().isNotEmpty)
                _buildPill(function.appName),
              _buildPill(_syncStatusLabel(function.syncStatus)),
              if (hasLastRun)
                _buildPill(
                  lastRunSuccess ? '最近成功' : '最近失败',
                  backgroundColor: lastRunSuccess
                      ? const Color(0xFFE8F7EE)
                      : const Color(0xFFFDECEC),
                  textColor: lastRunSuccess
                      ? const Color(0xFF117A37)
                      : const Color(0xFFB42318),
                ),
              _buildPill('${function.stepCount} steps'),
              _buildPill('parameters ${function.parameterNames.length}'),
              if (function.parameterNames.isEmpty) _buildPill('无参数'),
              if (function.parameterNames.isNotEmpty) _buildPill('需填写参数'),
              ...function.parameterNames.map(
                (parameter) => _buildPill(parameter),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _buildInfoRow(
            'app',
            [
              function.appName,
              function.packageName,
            ].where((e) => e.trim().isNotEmpty).join(' · '),
          ),
          if (function.parameterExamples.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              'parameter 示例',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: context.omniPalette.textSecondary,
              ),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: function.parameterExamples.entries
                  .map((entry) => _buildPill('${entry.key}=${entry.value}'))
                  .toList(),
            ),
          ],
          const SizedBox(height: 16),
          Align(
            alignment: Alignment.centerRight,
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed:
                      _runningFunctionId != null ||
                          _deletingFunctionId != null ||
                          _viewingFunctionId != null
                      ? null
                      : () => _viewFunctionBundle(function),
                  icon: viewing
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.account_tree_outlined),
                  label: Text(viewing ? '加载中...' : '查看资产'),
                ),
                OutlinedButton.icon(
                  onPressed:
                      _runningFunctionId != null ||
                          _deletingFunctionId != null ||
                          _viewingFunctionId != null
                      ? null
                      : () => _memorizeFunction(function),
                  icon: const Icon(Icons.bookmark_add_outlined),
                  label: const Text('记忆'),
                ),
                OutlinedButton.icon(
                  onPressed:
                      _runningFunctionId != null ||
                          _deletingFunctionId != null ||
                          _viewingFunctionId != null
                      ? null
                      : () => _deleteFunctionFromDashboard(function),
                  icon: deleting
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.delete_outline),
                  label: Text(deleting ? '删除中...' : '删除'),
                ),
                FilledButton.icon(
                  onPressed:
                      _runningFunctionId != null ||
                          _deletingFunctionId != null ||
                          _viewingFunctionId != null
                      ? null
                      : () => _runFunctionFromDashboard(function),
                  icon: running
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.play_arrow_outlined),
                  label: Text(running ? '执行中...' : '执行'),
                ),
              ],
            ),
          ),
          if (expanded || viewing || bundleError != null) ...[
            const SizedBox(height: 16),
            if (viewing)
              const Padding(
                padding: EdgeInsets.only(bottom: 8),
                child: LinearProgressIndicator(minHeight: 3),
              ),
            if (bundleError != null)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFFFFF4F4),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  bundleError,
                  style: TextStyle(color: Color(0xFFB42318), height: 1.5),
                ),
              )
            else if (bundle != null)
              _buildFunctionBundleView(function, bundle),
          ],
        ],
      ),
    );
  }

  Widget _buildFunctionBundleView(
    UtgFunctionSummary function,
    Map<String, dynamic> payload,
  ) {
    final graph =
        (payload['graph'] as Map<String, dynamic>?) ??
        const <String, dynamic>{};
    final functions =
        (graph['functions'] as List<dynamic>?) ?? const <dynamic>[];
    final nodes = (graph['nodes'] as List<dynamic>?) ?? const <dynamic>[];
    final functionPayload = functions.isNotEmpty && functions.first is Map
        ? Map<String, dynamic>.from(functions.first as Map)
        : const <String, dynamic>{};
    final nodeById = <String, Map<String, dynamic>>{};
    for (final raw in nodes) {
      if (raw is! Map) continue;
      final node = Map<String, dynamic>.from(raw);
      final nodeId = (node['id'] ?? '').toString();
      if (nodeId.isNotEmpty) {
        nodeById[nodeId] = node;
      }
    }
    final stepEntries = <MapEntry<String, dynamic>>[];
    final steps =
        (functionPayload['steps'] as Map<dynamic, dynamic>?) ?? const {};
    for (final entry in steps.entries) {
      stepEntries.add(MapEntry(entry.key.toString(), entry.value));
    }
    stepEntries.sort((a, b) {
      final av = a.value is Map ? (a.value as Map)['index'] : null;
      final bv = b.value is Map ? (b.value as Map)['index'] : null;
      final ai = av is num ? av.toInt() : 0;
      final bi = bv is num ? bv.toInt() : 0;
      return ai.compareTo(bi);
    });
    final previewParts = <String>[];
    for (final entry in stepEntries) {
      final nodeId = entry.key;
      final step = entry.value is Map
          ? Map<String, dynamic>.from(entry.value as Map)
          : const <String, dynamic>{};
      final node = nodeById[nodeId] ?? const <String, dynamic>{};
      final functions =
          (node['functions'] as Map<dynamic, dynamic>?) ?? const {};
      final functionNames =
          (step['functions'] as List<dynamic>?) ?? const <dynamic>[];
      for (final functionNameRaw in functionNames) {
        final functionName = functionNameRaw.toString();
        final functionPayload = functions[functionName] is Map
            ? Map<String, dynamic>.from(functions[functionName] as Map)
            : const <String, dynamic>{};
        final actions =
            (functionPayload['actions'] as List<dynamic>?) ?? const <dynamic>[];
        for (final item in actions) {
          final rawAction = item is Map
              ? Map<String, dynamic>.from(item)
              : <String, dynamic>{'value': item};
          previewParts.add(_buildActionPreviewText(rawAction));
        }
      }
    }
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFF7F9FC),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Text('资产详情', style: TextStyle(fontWeight: FontWeight.w700)),
              const Spacer(),
              OutlinedButton(
                onPressed: () => _copyText(
                  '资产 JSON',
                  const JsonEncoder.withIndent('  ').convert(payload),
                ),
                child: const Text('复制 JSON'),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: () {
                  setState(() {
                    if (_expandedFunctionId == function.functionId) {
                      _expandedFunctionId = null;
                    }
                  });
                },
                child: const Text('收起'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            previewParts.isEmpty
                ? '执行预览：无动作'
                : '执行预览：${previewParts.join(' -> ')}',
            style: TextStyle(
              color: context.omniPalette.textSecondary,
              fontSize: 12,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 8),
          if (function.parameterNames.isNotEmpty) ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: function.parameterNames.map(_buildPill).toList(),
            ),
            const SizedBox(height: 12),
          ],
          if (stepEntries.isEmpty)
            Text('这条资产暂时没有步骤。', style: TextStyle(color: context.omniPalette.textSecondary))
          else
            ...stepEntries.map((entry) {
              final nodeId = entry.key;
              final stepKey = '${function.functionId}::$nodeId';
              final stepExpanded = _expandedStepKeys.contains(stepKey);
              final step = entry.value is Map
                  ? Map<String, dynamic>.from(entry.value as Map)
                  : const <String, dynamic>{};
              final node = nodeById[nodeId] ?? const <String, dynamic>{};
              final repr = (node['repr'] as Map<dynamic, dynamic>?) ?? const {};
              final functions =
                  (node['functions'] as Map<dynamic, dynamic>?) ?? const {};
              final functionNames =
                  (step['functions'] as List<dynamic>?) ?? const <dynamic>[];
              final stepIndex = step['index'] is num
                  ? (step['index'] as num).toInt()
                  : 0;
              final nodeDescription = (repr['description'] ?? '')
                  .toString()
                  .trim();
              return Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: context.omniPalette.surfacePrimary,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: context.omniPalette.borderSubtle),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    InkWell(
                      onTap: () {
                        setState(() {
                          if (stepExpanded) {
                            _expandedStepKeys.remove(stepKey);
                          } else {
                            _expandedStepKeys.add(stepKey);
                          }
                        });
                      },
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              'Step ${stepIndex + 1} · $nodeId',
                              style: TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          _buildPill('${functionNames.length} functions'),
                          const SizedBox(width: 8),
                          Icon(
                            stepExpanded
                                ? Icons.expand_less
                                : Icons.expand_more,
                            color: context.omniPalette.textSecondary,
                          ),
                        ],
                      ),
                    ),
                    if (nodeDescription.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        nodeDescription,
                        style: TextStyle(color: context.omniPalette.textSecondary),
                      ),
                    ],
                    const SizedBox(height: 8),
                    if (!stepExpanded)
                      Text(
                        functionNames.map((e) => e.toString()).join(' · '),
                        style: TextStyle(
                          color: context.omniPalette.textSecondary,
                          fontSize: 12,
                          height: 1.5,
                        ),
                      )
                    else
                      ...functionNames.map((functionNameRaw) {
                        final functionName = functionNameRaw.toString();
                        final functionKey = '$stepKey::$functionName';
                        final functionExpanded = _expandedFunctionKeys.contains(
                          functionKey,
                        );
                        final functionPayload = functions[functionName] is Map
                            ? Map<String, dynamic>.from(
                                functions[functionName] as Map,
                              )
                            : const <String, dynamic>{};
                        final actions =
                            (functionPayload['actions'] as List<dynamic>?) ??
                            const <dynamic>[];
                        return Container(
                          width: double.infinity,
                          margin: const EdgeInsets.only(bottom: 8),
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: const Color(0xFFF7F9FC),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              InkWell(
                                onTap: () {
                                  setState(() {
                                    if (functionExpanded) {
                                      _expandedFunctionKeys.remove(functionKey);
                                    } else {
                                      _expandedFunctionKeys.add(functionKey);
                                    }
                                  });
                                },
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        functionName,
                                        style: TextStyle(
                                          fontWeight: FontWeight.w700,
                                        ),
                                      ),
                                    ),
                                    _buildPill('${actions.length} actions'),
                                    const SizedBox(width: 8),
                                    Icon(
                                      functionExpanded
                                          ? Icons.expand_less
                                          : Icons.expand_more,
                                      color: context.omniPalette.textSecondary,
                                    ),
                                  ],
                                ),
                              ),
                              if ((functionPayload['description'] ?? '')
                                  .toString()
                                  .trim()
                                  .isNotEmpty) ...[
                                const SizedBox(height: 4),
                                Text(
                                  (functionPayload['description'] ?? '')
                                      .toString(),
                                  style: TextStyle(
                                    color: context.omniPalette.textSecondary,
                                  ),
                                ),
                              ],
                              const SizedBox(height: 8),
                              if (!functionExpanded)
                                Text(
                                  actions
                                      .map((item) {
                                        final rawAction = item is Map
                                            ? Map<String, dynamic>.from(item)
                                            : <String, dynamic>{'value': item};
                                        return _actionDisplayName(
                                          (rawAction['type'] ?? '').toString(),
                                        );
                                      })
                                      .join(' -> '),
                                  style: TextStyle(
                                    color: context.omniPalette.textSecondary,
                                    fontSize: 12,
                                    height: 1.5,
                                  ),
                                )
                              else if (actions.isEmpty)
                                Text(
                                  '无动作',
                                  style: TextStyle(color: context.omniPalette.textSecondary),
                                )
                              else
                                ...actions.asMap().entries.map((actionEntry) {
                                  final rawAction = actionEntry.value is Map
                                      ? Map<String, dynamic>.from(
                                          actionEntry.value as Map,
                                        )
                                      : <String, dynamic>{
                                          'value': actionEntry.value,
                                        };
                                  final actionType = (rawAction['type'] ?? '')
                                      .toString()
                                      .trim();
                                  final summaryParts = <String>[];
                                  final packageName =
                                      (rawAction['packageName'] ??
                                              rawAction['package_name'] ??
                                              '')
                                          .toString()
                                          .trim();
                                  final textValue = (rawAction['text'] ?? '')
                                      .toString()
                                      .trim();
                                  final keyValue = (rawAction['key'] ?? '')
                                      .toString()
                                      .trim();
                                  final directionValue =
                                      (rawAction['direction'] ?? '')
                                          .toString()
                                          .trim();
                                  final xValue = rawAction['x'];
                                  final yValue = rawAction['y'];
                                  final durationValue =
                                      rawAction['duration_ms'];
                                  final timeValue = rawAction['time_s'];
                                  if (packageName.isNotEmpty) {
                                    summaryParts.add('package=$packageName');
                                  }
                                  if (textValue.isNotEmpty) {
                                    summaryParts.add('text=$textValue');
                                  }
                                  if (keyValue.isNotEmpty) {
                                    summaryParts.add('key=$keyValue');
                                  }
                                  if (xValue != null || yValue != null) {
                                    summaryParts.add('pos=($xValue, $yValue)');
                                  }
                                  if (directionValue.isNotEmpty) {
                                    summaryParts.add(
                                      'direction=$directionValue',
                                    );
                                  }
                                  if (durationValue != null) {
                                    summaryParts.add(
                                      'duration_ms=$durationValue',
                                    );
                                  }
                                  if (timeValue != null) {
                                    summaryParts.add('time_s=$timeValue');
                                  }
                                  if (summaryParts.isEmpty) {
                                    for (final entry in rawAction.entries) {
                                      if (entry.key == 'type') continue;
                                      summaryParts.add(
                                        '${entry.key}=${entry.value}',
                                      );
                                    }
                                  }
                                  return Padding(
                                    padding: const EdgeInsets.only(bottom: 8),
                                    child: Container(
                                      width: double.infinity,
                                      padding: const EdgeInsets.all(10),
                                      decoration: BoxDecoration(
                                        color: context.omniPalette.surfacePrimary,
                                        borderRadius: BorderRadius.circular(8),
                                        border: Border.all(
                                          color: context.omniPalette.borderSubtle,
                                        ),
                                      ),
                                      child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Row(
                                            children: [
                                              Expanded(
                                                child: Text(
                                                  '${actionEntry.key + 1}. ${_actionDisplayName(actionType)}',
                                                  style: TextStyle(
                                                    fontSize: 13,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                                ),
                                              ),
                                              TextButton(
                                                onPressed: () => _copyText(
                                                  'action json',
                                                  const JsonEncoder.withIndent(
                                                    '  ',
                                                  ).convert(rawAction),
                                                ),
                                                child: const Text('复制 JSON'),
                                              ),
                                            ],
                                          ),
                                          if (summaryParts.isNotEmpty) ...[
                                            const SizedBox(height: 4),
                                            Text(
                                              summaryParts.join(' · '),
                                              style: TextStyle(
                                                fontSize: 12,
                                                color: context.omniPalette.textSecondary,
                                                height: 1.5,
                                              ),
                                            ),
                                          ],
                                        ],
                                      ),
                                    ),
                                  );
                                }),
                            ],
                          ),
                        );
                      }),
                  ],
                ),
              );
            }),
        ],
      ),
    );
  }


  Future<void> _copyText(String label, String value) async {
    if (value.trim().isEmpty) {
      showToast('$label 为空', type: ToastType.error);
      return;
    }
    final copied = await AssistsMessageService.copyToClipboard(value);
    if (!mounted) return;
    showToast(
      copied ? '$label 已复制' : '$label 复制失败',
      type: copied ? ToastType.success : ToastType.error,
    );
  }

  String _actionDisplayName(String actionType) {
    final l10n = context.l10n;
    switch (actionType.trim()) {
      case 'open_app':
        return l10n.actionTypeOpenApp;
      case 'click':
        return l10n.actionTypeClick;
      case 'click_node':
        return l10n.actionTypeClickNode;
      case 'long_press':
        return l10n.actionTypeLongPress;
      case 'input_text':
        return l10n.actionTypeInputText;
      case 'swipe':
        return l10n.actionTypeSwipe;
      case 'press_key':
        return l10n.actionTypePressKey;
      case 'wait':
        return l10n.actionTypeWait;
      case 'finished':
        return l10n.actionTypeFinished;
      case 'call_function':
        return l10n.actionTypeCallFunction;
      default:
        return actionType.trim().isEmpty ? l10n.actionTypeDefault : actionType.trim();
    }
  }

  String _buildActionPreviewText(Map<String, dynamic> rawAction) {
    final actionType = (rawAction['type'] ?? '').toString().trim();
    final label = _actionDisplayName(actionType);
    final params = (rawAction['params'] as Map<dynamic, dynamic>?) ?? const {};
    final packageName =
        (rawAction['packageName'] ??
                rawAction['package_name'] ??
                params['packageName'] ??
                params['package_name'] ??
                '')
            .toString()
            .trim();
    final textValue = (rawAction['text'] ?? params['text'] ?? '')
        .toString()
        .trim();
    final keyValue = (rawAction['key'] ?? params['key'] ?? '')
        .toString()
        .trim();
    final directionValue = (rawAction['direction'] ?? params['direction'] ?? '')
        .toString()
        .trim();
    final xValue = rawAction['x'] ?? params['x'];
    final yValue = rawAction['y'] ?? params['y'];
    final targetDescription =
        (rawAction['targetDescription'] ??
                rawAction['target_description'] ??
                params['targetDescription'] ??
                params['target_description'] ??
                '')
            .toString()
            .trim();
    if (actionType == 'click') {
      if (xValue != null && yValue != null) {
        return 'click ($xValue, $yValue)';
      }
      if (targetDescription.isNotEmpty) {
        return 'click $targetDescription';
      }
      return 'click';
    }
    if (packageName.isNotEmpty) {
      return '$label($packageName)';
    }
    if (textValue.isNotEmpty) {
      return '$label($textValue)';
    }
    if (keyValue.isNotEmpty) {
      return '$label($keyValue)';
    }
    if (directionValue.isNotEmpty) {
      return '$label($directionValue)';
    }
    return label;
  }

  String _syncStatusLabel(String syncStatus) {
    switch (syncStatus.trim()) {
      case 'downloaded_from_cloud':
        return '来自云端';
      case 'uploaded_to_cloud':
        return '已上传云端';
      case 'imported_bundle':
        return '已导入 bundle';
      case 'local_only':
        return '仅本地';
      default:
        return syncStatus.trim().isEmpty ? '同步未知' : syncStatus.trim();
    }
  }

  String _assetKindLabel(UtgFunctionSummary function) {
    final state = function.assetState.trim().toLowerCase();
    return state.isEmpty ? 'function' : state;
  }

  void _memorizeFunction(UtgFunctionSummary function) {
    // 从 lastRun 或 functionId 中提取 run_id
    // functionId 格式: func_xxx 对应 run_id: xxx
    final lastRunId = function.lastRun['run_id']?.toString() ?? '';
    final runId = lastRunId.isNotEmpty
        ? lastRunId
        : function.functionId.replaceFirst('func_', '');

    if (runId.isEmpty) {
      showToast('无法获取 run_id', type: ToastType.error);
      return;
    }

    showToast('正在导入 ${function.description}...', type: ToastType.info);
    // 异步执行，不等待结果
    AssistsMessageService.importRunLog(
      runId: runId,
      baseUrl: _baseUrlController.text.trim(),
    ).then((result) {
      if (!mounted) return;
      if (result.success) {
        showToast('已导入 ${function.description}', type: ToastType.success);
        _loadFunctions(baseUrl: _baseUrlController.text.trim(), silent: true);
      } else {
        showToast(
          result.errorMessage ?? '导入失败：${function.description}',
          type: ToastType.error,
        );
      }
    }).catchError((e) {
      if (!mounted) return;
      showToast('导入失败：$e', type: ToastType.error);
      debugPrint('Import run_log failed: $e');
    });
  }

  Future<void> _controlProvider(String action) async {
    try {
      setState(() => _providerControlAction = action);
      final result = await AssistsMessageService.controlUtgProvider(
        action: action,
      );
      if (!mounted) return;
      _applyConfig(result.config);
      await _loadFunctions(
        baseUrl: result.config.resolvedOmniflowBaseUrl,
        silent: true,
      );
      await _loadRunLogs(
        baseUrl: result.config.resolvedOmniflowBaseUrl,
        silent: true,
      );
      if (!mounted) return;
      showToast(
        result.success
            ? 'provider $action 成功'
            : 'provider $action 失败：${result.message}',
        type: result.success ? ToastType.success : ToastType.error,
      );
    } catch (e) {
      final errorText = e.toString().replaceFirst('Exception: ', '');
      if (!mounted) return;
      showToast('provider $action 失败：$errorText', type: ToastType.error);
      debugPrint('Control provider failed: $e');
    } finally {
      if (mounted) {
        setState(() => _providerControlAction = null);
      }
    }
  }


  Future<Map<String, String>?> _confirmFunctionRun(
    UtgFunctionSummary function,
    UtgBridgeExecutionContext executionContext,
  ) async {
    final controllers = {
      for (final parameter in function.parameterNames)
        parameter: TextEditingController(
          text: function.parameterExamples[parameter] ?? '',
        ),
    };
    try {
      return await showDialog<Map<String, String>>(
        context: context,
        builder: (dialogContext) {
          return AlertDialog(
            title: const Text('执行 OmniFlow 资产'),
            content: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  SelectableText('function_id: ${function.functionId}'),
                  const SizedBox(height: 8),
                  Text(
                    function.description.trim().isEmpty
                        ? '无描述'
                        : function.description,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    executionContext.providerHealthy
                        ? 'provider health: ok'
                        : 'provider health: ${executionContext.providerMessage}',
                    style: TextStyle(
                      color: executionContext.providerHealthy
                          ? Colors.green.shade700
                          : Colors.red.shade700,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 8),
                  SelectableText(
                    'bridge: ${executionContext.bridgeBaseUrl}',
                    style: TextStyle(
                      fontSize: 12,
                      color: context.omniPalette.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (controllers.isEmpty)
                    const Text('此资产无需填写参数。')
                  else
                    ...controllers.entries.map(
                      (entry) => Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: TextField(
                          controller: entry.value,
                          decoration: InputDecoration(
                            labelText: entry.key,
                            hintText:
                                function.parameterExamples[entry.key] ?? '',
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () {
                  Navigator.of(dialogContext).pop({
                    for (final entry in controllers.entries)
                      entry.key: entry.value.text.trim(),
                  });
                },
                child: const Text('执行'),
              ),
            ],
          );
        },
      );
    } finally {
      for (final controller in controllers.values) {
        controller.dispose();
      }
    }
  }

  Future<void> _viewFunctionBundle(UtgFunctionSummary function) async {
    if (_expandedFunctionId == function.functionId) {
      setState(() {
        _expandedFunctionId = null;
        _functionBundleErrorById.remove(function.functionId);
      });
      return;
    }
    if (_functionBundleCache.containsKey(function.functionId)) {
      setState(() {
        _expandedFunctionId = function.functionId;
        _functionBundleErrorById.remove(function.functionId);
      });
      return;
    }
    try {
      setState(() => _viewingFunctionId = function.functionId);
      final payload = await AssistsMessageService.getUtgFunctionBundle(
        functionId: function.functionId,
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      setState(() {
        _functionBundleCache[function.functionId] = payload;
        _functionBundleErrorById.remove(function.functionId);
        _expandedFunctionId = function.functionId;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _functionBundleErrorById[function.functionId] = '加载资产详情失败：$e';
        _expandedFunctionId = function.functionId;
      });
      showToast('加载资产详情失败', type: ToastType.error);
      debugPrint('Load OmniFlow trajectory bundle failed: $e');
    } finally {
      if (mounted) {
        setState(() => _viewingFunctionId = null);
      }
    }
  }

  Future<void> _runFunctionFromDashboard(UtgFunctionSummary function) async {
    try {
      final executionContext =
          await AssistsMessageService.getUtgBridgeExecutionContext();
      if (!mounted) return;
      final arguments = await _confirmFunctionRun(function, executionContext);
      if (!mounted || arguments == null) {
        return;
      }
      setState(() => _runningFunctionId = function.functionId);
      final result = await AssistsMessageService.runUtgFunction(
        functionId: function.functionId,
        arguments: arguments,
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      await _loadFunctions(
        baseUrl: _baseUrlController.text.trim(),
        silent: true,
      );
      await _loadRunLogs(baseUrl: _baseUrlController.text.trim(), silent: true);
      if (!mounted) return;
      showToast(
        result.success
            ? '已通过 OmniFlow 执行 ${function.functionId}'
            : (result.errorMessage?.trim().isNotEmpty == true
                  ? 'OmniFlow 执行失败：${result.errorMessage}'
                  : 'OmniFlow 执行失败：${function.functionId}'),
        type: result.success ? ToastType.success : ToastType.error,
      );
      await AppStateService.navigateBackToChat();
    } on PlatformException catch (e) {
      if (!mounted) return;
      showToast(e.message ?? '获取 OmniFlow bridge 失败', type: ToastType.error);
    } catch (e) {
      if (!mounted) return;
      showToast('执行 OmniFlow 资产失败', type: ToastType.error);
      debugPrint('Run OmniFlow trajectory failed: $e');
    } finally {
      if (mounted) {
        setState(() => _runningFunctionId = null);
      }
    }
  }

  Future<void> _deleteFunctionFromDashboard(UtgFunctionSummary function) async {
    final shouldDelete = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('删除 OmniFlow 资产'),
          content: SelectableText(
            '确认删除 function_id=${function.functionId}？\n\n删除后会直接从当前本地 provider 的 OmniFlow store 移除。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('删除'),
            ),
          ],
        );
      },
    );
    if (shouldDelete != true || !mounted) {
      return;
    }
    try {
      setState(() => _deletingFunctionId = function.functionId);
      final result = await AssistsMessageService.deleteUtgFunction(
        functionId: function.functionId,
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      if (!result.success) {
        showToast(
          result.errorMessage ?? '删除失败：${function.functionId}',
          type: ToastType.error,
        );
        return;
      }
      await _loadFunctions(
        baseUrl: _baseUrlController.text.trim(),
        silent: true,
      );
      if (!mounted) return;
      setState(() {
        if (_highlightedFunctionId == function.functionId) {
          _highlightedFunctionId = null;
        }
        if (_expandedFunctionId == function.functionId) {
          _expandedFunctionId = null;
        }
        _functionBundleCache.remove(function.functionId);
        _functionBundleErrorById.remove(function.functionId);
      });
      showToast('已删除 ${function.functionId}', type: ToastType.success);
    } catch (e) {
      if (!mounted) return;
      showToast('删除 OmniFlow 资产失败', type: ToastType.error);
      debugPrint('Delete OmniFlow trajectory failed: $e');
    } finally {
      if (mounted) {
        setState(() => _deletingFunctionId = null);
      }
    }
  }

  Future<void> _resetAllData() async {
    final shouldReset = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('清空所有数据'),
          content: const Text(
            '确认清空所有 OmniFlow 数据？\n\n这将删除：\n• 所有 Functions\n• 所有 Run Logs\n• 所有 Shared Pages\n\n此操作不可恢复！',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              child: const Text('清空'),
            ),
          ],
        );
      },
    );
    if (shouldReset != true || !mounted) return;

    try {
      setState(() => _resettingAllData = true);
      final result = await AssistsMessageService.resetAllData(
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      if (result['success'] != true) {
        showToast(
          result['error_message']?.toString() ?? '清空失败',
          type: ToastType.error,
        );
        return;
      }
      final deleted = result['deleted'] as Map<String, dynamic>? ?? {};
      showToast(
        '已清空: ${deleted['functions']} functions, ${deleted['run_logs']} run_logs',
        type: ToastType.success,
      );
      await _refreshAll();
    } catch (e) {
      if (!mounted) return;
      showToast('清空数据失败: $e', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _resettingAllData = false);
      }
    }
  }

  Future<void> _checkForUpdate() async {
    setState(() => _checkingUpdate = true);
    try {
      final result = await AssistsMessageService.checkForUpdate(
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      setState(() => _updateCheckResult = result);
      if (result.updateAvailable) {
        showToast(
          '发现新版本: ${result.latestVersion}',
          type: ToastType.info,
        );
      } else {
        showToast('已是最新版本', type: ToastType.success);
      }
    } catch (e) {
      if (!mounted) return;
      showToast('检查更新失败: $e', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _checkingUpdate = false);
      }
    }
  }

  Future<void> _applyUpdate() async {
    setState(() => _applyingUpdate = true);
    try {
      final result = await AssistsMessageService.applyUpdate(
        baseUrl: _baseUrlController.text.trim(),
      );
      if (!mounted) return;
      if (result.success) {
        showToast(
          '更新成功: ${result.installedVersion}。需要重启 Provider。',
          type: ToastType.success,
        );
        setState(() => _updateCheckResult = null);
        // 重新检查更新状态
        await _checkForUpdate();
      } else {
        showToast(
          '更新失败: ${result.error ?? "未知错误"}',
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast('更新失败: $e', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _applyingUpdate = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final config = _config;
    final allFunctions =
        _functionsSnapshot?.functions ?? const <UtgFunctionSummary>[];
    final searchQuery = _functionSearchController.text.trim().toLowerCase();
    final filteredFunctions = searchQuery.isEmpty
        ? allFunctions
        : allFunctions.where((function) {
            final haystack = <String>[
              function.functionId,
              function.description,
              function.assetKind,
              function.assetState,
              function.derivedFromRawFunctionId,
              function.startNodeId,
              function.endNodeId,
              function.startNodeDescription,
              function.endNodeDescription,
              ...function.parameterNames,
            ].join(' ').toLowerCase();
            return haystack.contains(searchQuery);
          }).toList();

    Map<String, List<UtgFunctionSummary>> groupByApp(
      List<UtgFunctionSummary> functions,
    ) {
      final grouped = <String, List<UtgFunctionSummary>>{};
      for (final function in functions) {
        final groupName = function.groupName.trim().isEmpty
            ? '未分组'
            : function.groupName.trim();
        grouped
            .putIfAbsent(groupName, () => <UtgFunctionSummary>[])
            .add(function);
      }
      return grouped;
    }

    return Scaffold(
      backgroundColor: context.omniPalette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.omniflowPanelTitle,
        primary: true,
        actions: [
          IconButton(
            onPressed: _loading || _resettingAllData ? null : _resetAllData,
            icon: _resettingAllData
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.delete_sweep_outlined),
            tooltip: context.l10n.omniflowClearAllData,
          ),
          IconButton(
            onPressed: _loading ? null : _refreshAll,
            icon: const Icon(Icons.refresh_outlined),
            tooltip: '刷新',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refreshAll,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _buildCard(
                    child: Text(
                      context.l10n.omniflowPanelDesc,
                      style: TextStyle(
                        fontSize: 13,
                        color: context.omniPalette.textSecondary,
                        height: 1.7,
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          context.l10n.omniflowSettings,
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 12),
                        SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          value: _utgEnabled,
                          title: Text(context.l10n.omniflowEnablePreHook),
                          subtitle: Text(
                            context.l10n.omniflowEnableAccelerationDesc,
                            style: TextStyle(fontSize: 12, color: context.omniPalette.textTertiary),
                          ),
                          onChanged: _saving
                              ? null
                              : (value) => setState(() => _utgEnabled = value),
                        ),
                        SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          value: _providerAutoStartEnabled,
                          title: Text(context.l10n.omniflowAutoStartProvider),
                          subtitle: Text(
                            context.l10n.omniflowAutoStartDesc,
                            style: TextStyle(fontSize: 12, color: context.omniPalette.textTertiary),
                          ),
                          onChanged: _saving
                              ? null
                              : (value) => setState(
                                  () => _providerAutoStartEnabled = value,
                                ),
                        ),
                        const SizedBox(height: 8),
                        _buildInfoRow(
                          context.l10n.omniflowServiceStatus,
                          config == null
                              ? context.l10n.commonLoading
                              : (config.providerHealthy ? context.l10n.omniflowServiceStatusRunning : context.l10n.omniflowServiceStatusStopped),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _baseUrlController,
                          decoration: InputDecoration(
                            labelText: context.l10n.omniflowServiceAddress,
                            hintText: 'http://localhost:8765',
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                          onEditingComplete: () {
                            FocusScope.of(context).unfocus();
                            _saveConfig();
                          },
                        ),
                        if (config?.providerRunLogPath != null &&
                            config!.providerRunLogPath.isNotEmpty)
                          _buildInfoRow(
                            context.l10n.omniflowDataDirectory,
                            config.providerRunLogPath.replaceAll(
                              RegExp(r'/run_log\.jsonl$'),
                              '',
                            ),
                          ),
                        const SizedBox(height: 16),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            FilledButton.icon(
                              onPressed:
                                  _saving || _providerControlAction != null
                                  ? null
                                  : () => _controlProvider('start'),
                              icon: _providerControlAction == 'start'
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: Colors.white,
                                      ),
                                    )
                                  : const Icon(Icons.play_circle_outline),
                              label: Text(
                                _providerControlAction == 'start'
                                    ? context.l10n.omniflowStarting
                                    : context.l10n.omniflowProviderStart,
                              ),
                            ),
                            OutlinedButton.icon(
                              onPressed:
                                  _saving || _providerControlAction != null
                                  ? null
                                  : () => _controlProvider('restart'),
                              icon: _providerControlAction == 'restart'
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.restart_alt_outlined),
                              label: Text(
                                _providerControlAction == 'restart'
                                    ? context.l10n.omniflowRestarting
                                    : context.l10n.omniflowProviderRestart,
                              ),
                            ),
                            OutlinedButton.icon(
                              onPressed:
                                  _saving || _providerControlAction != null
                                  ? null
                                  : () => _controlProvider('stop'),
                              icon: _providerControlAction == 'stop'
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.stop_circle_outlined),
                              label: Text(
                                _providerControlAction == 'stop'
                                    ? context.l10n.omniflowStopping
                                    : context.l10n.omniflowProviderStop,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  // Provider 更新卡片
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Provider 更新',
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 12),
                        if (_updateCheckResult != null) ...[
                          _buildInfoRow(
                            '当前版本',
                            _updateCheckResult!.currentVersion,
                          ),
                          if (_updateCheckResult!.latestVersion != null)
                            _buildInfoRow(
                              '最新版本',
                              _updateCheckResult!.latestVersion!,
                            ),
                          if (_updateCheckResult!.updateAvailable)
                            Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  color: const Color(0xFFFFF4E5),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Row(
                                  children: [
                                    Icon(
                                      Icons.system_update_outlined,
                                      size: 18,
                                      color: const Color(0xFFF59E0B),
                                    ),
                                    const SizedBox(width: 8),
                                    Text(
                                      '有新版本可用',
                                      style: TextStyle(
                                        color: const Color(0xFFF59E0B),
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          const SizedBox(height: 12),
                        ],
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            OutlinedButton.icon(
                              onPressed: config == null ||
                                      !config.providerHealthy ||
                                      _checkingUpdate ||
                                      _applyingUpdate
                                  ? null
                                  : _checkForUpdate,
                              icon: _checkingUpdate
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.refresh_outlined),
                              label: Text(
                                _checkingUpdate ? '检查中...' : '检查更新',
                              ),
                            ),
                            if (_updateCheckResult?.updateAvailable == true)
                              FilledButton.icon(
                                onPressed: _applyingUpdate ? null : _applyUpdate,
                                icon: _applyingUpdate
                                    ? const SizedBox(
                                        width: 14,
                                        height: 14,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          color: Colors.white,
                                        ),
                                      )
                                    : const Icon(Icons.download_outlined),
                                label: Text(
                                  _applyingUpdate ? '更新中...' : '立即更新',
                                ),
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                context.l10n.omniflowFunctionList,
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                            if (_loadingFunctions)
                              const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            else
                              _buildPill('${allFunctions.length}'),
                          ],
                        ),
                        const SizedBox(height: 12),
                        SizedBox(
                          width: double.infinity,
                          child: OutlinedButton.icon(
                            onPressed: config == null || !config.providerHealthy
                                ? null
                                : () => _openFunctionsBrowser(),
                            icon: const Icon(Icons.bolt_outlined),
                            label: Text(context.l10n.omniflowViewFunctionLibrary),
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
