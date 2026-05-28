import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:ui/features/task/run_log/run_log_replay_policy.dart';
import 'package:ui/services/assists_core_service.dart';

class RunLogReusableFunctionSpec {
  const RunLogReusableFunctionSpec({
    required this.json,
    required this.agentPrompt,
    required this.aiEnhanced,
    this.warning,
    this.rawAiText,
  });

  final Map<String, dynamic> json;
  final String agentPrompt;
  final bool aiEnhanced;
  final String? warning;
  final String? rawAiText;

  String get functionId => (json['function_id'] ?? '').toString();
  String get name => (json['name'] ?? '').toString();

  RunLogReusableFunctionSpec copyWith({
    Map<String, dynamic>? json,
    String? agentPrompt,
    bool? aiEnhanced,
    String? warning,
    String? rawAiText,
  }) {
    return RunLogReusableFunctionSpec(
      json: json ?? this.json,
      agentPrompt: agentPrompt ?? this.agentPrompt,
      aiEnhanced: aiEnhanced ?? this.aiEnhanced,
      warning: warning ?? this.warning,
      rawAiText: rawAiText ?? this.rawAiText,
    );
  }

  int get stepCount {
    final execution = _asStringKeyMap(json['execution']);
    final steps = execution['steps'];
    return steps is List ? steps.length : 0;
  }

  int get parameterCount {
    final parameters = json['parameters'];
    return parameters is List ? parameters.length : 0;
  }

  String get prettyJson => const JsonEncoder.withIndent('  ').convert(json);
}

class _LabelEnhancementPatchResult {
  const _LabelEnhancementPatchResult({
    required this.json,
    required this.rawTexts,
  });

  final Map<String, dynamic>? json;
  final List<String> rawTexts;
}

class RunLogReusableFunctionConverter {
  const RunLogReusableFunctionConverter._();

  static Future<RunLogReusableFunctionSpec> convert({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useAi = true,
    bool useEnglish = false,
  }) async {
    final baseJson = await buildLocalFunctionJsonAsync(
      runId: runId,
      title: title,
      payload: payload,
      cards: cards,
      useEnglish: useEnglish,
    );

    if (!useAi || cards.isEmpty) {
      final agentPrompt = await buildAgentPromptAsync(
        baseJson,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
      );
    }

    final prompt = await buildAiPromptAsync(baseJson, useEnglish: useEnglish);
    try {
      final raw = await AssistsMessageService.postLLMChat(
        text: prompt,
        model: 'scene.compactor.context',
        responseJsonObject: true,
      );
      var aiJson = await extractJsonObjectAsync(raw ?? '');
      String? repairRaw;
      if (aiJson == null && (raw ?? '').trim().isNotEmpty) {
        repairRaw = await AssistsMessageService.postLLMChat(
          text: await buildJsonRepairPromptAsync(
            invalidOutput: raw ?? '',
            fallbackJson: baseJson,
            useEnglish: useEnglish,
          ),
          model: 'scene.compactor.context',
          responseJsonObject: true,
        );
        aiJson = await extractJsonObjectAsync(repairRaw ?? '');
      }
      if (aiJson == null) {
        final agentPrompt = await buildAgentPromptAsync(
          baseJson,
          useEnglish: useEnglish,
        );
        return RunLogReusableFunctionSpec(
          json: baseJson,
          agentPrompt: agentPrompt,
          aiEnhanced: false,
          rawAiText: repairRaw ?? raw,
          warning: _text(
            useEnglish,
            'AI 未返回可解析 JSON，已使用本地规则生成，可继续注册和执行。',
            'AI did not return parseable JSON. Using the local conversion.',
          ),
        );
      }
      final normalized = await normalizeAiJsonAsync(aiJson, baseJson);
      final agentPrompt = await buildAgentPromptAsync(
        normalized,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: normalized,
        agentPrompt: agentPrompt,
        aiEnhanced: true,
        rawAiText: raw,
      );
    } catch (error) {
      final agentPrompt = await buildAgentPromptAsync(
        baseJson,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: baseJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
        warning: useEnglish
            ? 'AI conversion failed. Using the local conversion: $error'
            : 'AI 转换失败，已使用本地转换结果：$error',
      );
    }
  }

  static Map<String, dynamic> buildLocalFunctionJson({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useEnglish = false,
  }) {
    final goal = _firstNonBlank([
      payload['goal'],
      payload['task_goal'],
      payload['operation_description'],
      payload['operationDescription'],
      title,
    ]);
    final steps = <Map<String, dynamic>>[];
    final parametersBySignature = <String, Map<String, dynamic>>{};
    final seenParameterNames = <String>{};
    final snapshots = cards
        .asMap()
        .entries
        .map(
          (entry) => _RunLogActionSnapshot.fromCard(
            entry.value,
            fallbackIndex: entry.key,
          ),
        )
        .toList(growable: false);
    final hasRecordedReplayStep = snapshots.any(
      (snapshot) =>
          snapshot.success != false &&
          _replayActionForSnapshot(snapshot) != null,
    );

    for (var index = 0; index < snapshots.length; index++) {
      final snapshot = snapshots[index];
      if (snapshot.success == false) continue;
      if (RunLogReplayPolicy.shouldSkipTool(snapshot.toolName)) continue;
      final rawArgs = _replayArgsForSnapshot(snapshot);
      final args = _canonicalCallToolArgs(snapshot.toolName, rawArgs);
      final shouldSkipPerceptionStep =
          RunLogReplayPolicy.isPerceptionTool(snapshot.toolName) &&
          hasRecordedReplayStep;
      // Skip vlm_task outer calls entirely when concrete recorded actions are
      // present. The VLM-driven actions (click/scroll/input_text with legacy route kind
      // metadata) are recorded as separate omniflow cards with source_context
      // for coordinate remapping.
      if (shouldSkipPerceptionStep) continue;

      final executionIndex = steps.length;
      final stepId = 'step_${executionIndex + 1}';
      final executor = _executorForSnapshot(
        snapshot,
        args: args,
        skipPerceptionStep: shouldSkipPerceptionStep,
      );
      final replayAction = _replayActionForSnapshot(snapshot);
      final modelFree = executor == 'omniflow';
      final emittedToolName = modelFree && replayAction != null
          ? replayAction
          : _canonicalToolNameForStep(snapshot.toolName, args);
      final scriptable = executor != 'agent';
      final coordinateHook = _buildCoordinateHookMetadata(
        snapshot: snapshot,
        args: args,
      );
      final prompt = snapshot.prompt;
      final fallbackPrompt = _stepFallbackPrompt(
        title: snapshot.title,
        toolName: snapshot.toolName,
        args: args,
        prompt: prompt,
        useEnglish: useEnglish,
      );
      final summary = _stepSummary(
        title: snapshot.title,
        toolName: snapshot.toolName,
        prompt: prompt,
        args: args,
        result: snapshot.result,
      );
      final observedResult = _compactObservedResult(snapshot.result);
      final step = {
        'id': stepId,
        'index': executionIndex,
        if (executionIndex != index) 'source_index': index,
        'kind': _stepKindForToolName(
          emittedToolName,
          snapshot.route,
          args: args,
        ),
        'title': snapshot.title.isNotEmpty ? snapshot.title : emittedToolName,
        if (summary.isNotEmpty) 'summary': summary,
        'tool': emittedToolName,
        'callable_tool': executor == 'agent'
            ? 'oob.agent.run'
            : emittedToolName,
        if (emittedToolName != snapshot.toolName)
          'source_tool': snapshot.toolName,
        'executor': executor,
        'scriptable': scriptable,
        if (modelFree) 'model_free': true,
        if (replayAction != null) 'omniflow_action': replayAction,
        if (coordinateHook != null) ...coordinateHook,
        'args': args,
        if (prompt.isNotEmpty)
          'prompt': {
            'text': prompt,
            'preview': _compactPreview(prompt, maxLength: 240),
            'source': snapshot.promptSource,
          },
        'tool_binding': {
          'kind': executor == 'agent'
              ? 'agent_replan'
              : RunLogReplayPolicy.isOmniflowGraphTool(emittedToolName)
              ? 'omniflow_graph'
              : RunLogReplayPolicy.isOmniflowFunctionTool(emittedToolName) ||
                    _callToolFunctionId(args).isNotEmpty
              ? 'omniflow_function'
              : executor == 'omniflow'
              ? 'omniflow_action'
              : 'oob_agent_tool',
          'name': emittedToolName,
          if (executor == 'agent') 'callable_tool': 'oob.agent.run',
        },
        if (executor == 'agent')
          'agent_call': {
            'tool': 'oob.agent.run',
            'args': {
              'prompt': fallbackPrompt,
              'original_tool': snapshot.toolName,
              'original_args': args,
              if (prompt.isNotEmpty) 'original_prompt': prompt,
            },
            'reason': RunLogReplayPolicy.agentStepReason(snapshot.toolName),
          },
        if (snapshot.route.isNotEmpty) 'route': snapshot.route,
        if (snapshot.success != null) 'success': snapshot.success,
        if (snapshot.durationMs != null) 'duration_ms': snapshot.durationMs,
        if (snapshot.packageName.isNotEmpty)
          'package_name': snapshot.packageName,
        if (!_isEmptyJsonValue(observedResult))
          'observed_result': observedResult,
        if (snapshot.beforeSummary.isNotEmpty)
          'before_state': snapshot.beforeSummary,
        if (snapshot.afterSummary.isNotEmpty)
          'after_state': snapshot.afterSummary,
        'validation': {
          'mode': 'soft_state_match',
          if (snapshot.packageName.isNotEmpty)
            'package_name': snapshot.packageName,
          if (snapshot.beforeSummary.isNotEmpty)
            'expected_before_state': snapshot.beforeSummary,
        },
        'fallback': {
          'kind': 'agent_replan',
          'tool': 'oob.agent.run',
          'when': scriptable
              ? 'state_or_selector_mismatch'
              : 'always_for_agent_executor',
          'prompt': fallbackPrompt,
        },
        'reuse_policy': {
          'mode': 'prefer_script_then_agent_replan',
          'allow_agent_replan_on_mismatch': true,
          'requires_runtime_validation': true,
        },
      };
      if (_isDuplicateTextInputStep(steps.isEmpty ? null : steps.last, step)) {
        continue;
      }
      steps.add(step);

      _collectParametersFromArgs(
        stepId: stepId,
        stepIndex: executionIndex,
        toolName: snapshot.toolName,
        args: args,
        parametersBySignature: parametersBySignature,
        seenNames: seenParameterNames,
        useEnglish: useEnglish,
        bindAgentOriginalArgs: executor == 'agent',
      );
    }

    final parameters = parametersBySignature.values.toList(growable: false);
    final packageName = _firstNonBlank([
      ...steps.map((step) => step['package_name']),
      payload['final_package_name'],
      payload['package_name'],
    ]);
    final name = _normalizeFunctionName(
      goal.isNotEmpty ? goal : title,
      fallback: 'reusable_run_${_compactId(runId)}',
    );
    final now = DateTime.now().toUtc().toIso8601String();

    final startState = _firstNonBlank([
      _asStringKeyMap(
        cards.isEmpty ? null : cards.first['before'],
      )['page_title'],
      _asStringKeyMap(cards.isEmpty ? null : cards.first['before'])['activity'],
      _asStringKeyMap(
        cards.isEmpty ? null : cards.first['before'],
      )['package_name'],
    ]);
    final endState = _firstNonBlank([
      _asStringKeyMap(cards.isEmpty ? null : cards.last['after'])['page_title'],
      _asStringKeyMap(cards.isEmpty ? null : cards.last['after'])['activity'],
      _asStringKeyMap(
        cards.isEmpty ? null : cards.last['after'],
      )['package_name'],
    ]);

    return {
      'schema_version': 'oob.reusable_function.v1',
      'function_id': 'runlog_${_compactId(runId)}',
      'name': name,
      'description': goal.isNotEmpty ? goal : name,
      'source': {
        'kind': 'run_log',
        'run_id': runId,
        'title': title,
        'converted_at': now,
        'converter': 'oob_run_log_reusable_function_converter',
      },
      'parameters': parameters,
      'constraints': {
        if (packageName.isNotEmpty) 'package_name': packageName,
        if (startState.isNotEmpty) 'start_state': startState,
        if (endState.isNotEmpty) 'end_state': endState,
      },
      'execution': {
        'kind': 'tool_sequence',
        'runner': 'oob_tool_sequence',
        'entrypoint': 'execute',
        'capabilities': {
          'scriptable_step_count': steps
              .where((step) => step['scriptable'] == true)
              .length,
          'model_free_step_count': steps
              .where((step) => step['model_free'] == true)
              .length,
          'omniflow_step_count': steps
              .where((step) => step['executor'] == 'omniflow')
              .length,
          'agent_step_count': steps
              .where((step) => step['executor'] == 'agent')
              .length,
          'requires_agent_fallback': steps.any(
            (step) => step['executor'] == 'agent',
          ),
        },
        'fallback_runner': 'oob.agent.run',
        'steps': steps,
      },
    };
  }

  static Future<Map<String, dynamic>> buildLocalFunctionJsonAsync({
    required String runId,
    required String title,
    required Map<String, dynamic> payload,
    required List<Map<String, dynamic>> cards,
    bool useEnglish = false,
  }) {
    return compute(_buildLocalFunctionJsonInIsolate, {
      'runId': runId,
      'title': title,
      'payload': _jsonSafeMap(payload),
      'cards': cards.map(_jsonSafeMap).toList(growable: false),
      'useEnglish': useEnglish,
    });
  }

  static Future<String> buildAiPromptAsync(
    Map<String, dynamic> baseJson, {
    bool useEnglish = false,
  }) {
    return compute(_buildAiPromptInIsolate, {
      'baseJson': _jsonSafeMap(baseJson),
      'useEnglish': useEnglish,
    });
  }

  static Future<String> buildJsonRepairPromptAsync({
    required String invalidOutput,
    required Map<String, dynamic> fallbackJson,
    bool useEnglish = false,
  }) {
    return compute(_buildJsonRepairPromptInIsolate, {
      'invalidOutput': invalidOutput,
      'fallbackJson': _jsonSafeMap(fallbackJson),
      'useEnglish': useEnglish,
    });
  }

  static Future<RunLogReusableFunctionSpec> enhanceLabels({
    required Map<String, dynamic> functionJson,
    bool useEnglish = false,
  }) async {
    final fallbackJson = _jsonSafeMap(functionJson);
    final patches = <Map<String, dynamic>>[];
    final rawParts = <String>[];
    try {
      Future<void> requestPart({
        required String partName,
        required String prompt,
        required Map<String, dynamic> fallbackPatch,
      }) async {
        final result = await _requestLabelEnhancementPatch(
          partName: partName,
          prompt: prompt,
          fallbackPatch: fallbackPatch,
          useEnglish: useEnglish,
        );
        rawParts.addAll(result.rawTexts);
        if (result.json != null && result.json!.isNotEmpty) {
          patches.add(result.json!);
        }
      }

      await requestPart(
        partName: 'header',
        prompt: _buildLabelHeaderEnhancementPrompt(
          fallbackJson,
          useEnglish: useEnglish,
        ),
        fallbackPatch: _labelHeaderFallbackPatch(fallbackJson),
      );

      for (final chunk in _labelStepPromptChunks(fallbackJson)) {
        await requestPart(
          partName: 'steps',
          prompt: _buildLabelStepsEnhancementPrompt(
            chunk,
            useEnglish: useEnglish,
          ),
          fallbackPatch: {'steps': _stepPromptFallback(chunk)},
        );
      }

      await requestPart(
        partName: 'parameters',
        prompt: _buildLabelParametersEnhancementPrompt(
          fallbackJson,
          useEnglish: useEnglish,
        ),
        fallbackPatch: _labelParametersFallbackPatch(fallbackJson),
      );

      await requestPart(
        partName: 'agent_reuse',
        prompt: _buildLabelReuseEnhancementPrompt(
          fallbackJson,
          useEnglish: useEnglish,
        ),
        fallbackPatch: const {
          'agent_reuse': {
            'reuse_when': [],
            'avoid_when': [],
            'success_signal': '',
            'key_actions': [],
            'segments': [],
          },
        },
      );

      final aiJson = _mergeLabelEnhancementPatches(patches);
      if (aiJson.isEmpty) {
        final agentPrompt = await buildAgentPromptAsync(
          fallbackJson,
          useEnglish: useEnglish,
        );
        return RunLogReusableFunctionSpec(
          json: fallbackJson,
          agentPrompt: agentPrompt,
          aiEnhanced: false,
          rawAiText: rawParts.join('\n\n'),
          warning: _text(
            useEnglish,
            'Agent 增强没有返回任何可解析 JSON 片段，已保留当前复用指令。',
            'Agent enhancement did not return any parseable JSON fragments. Keeping the current command.',
          ),
        );
      }
      final enhanced = _applyLabelEnhancement(aiJson, fallbackJson);
      final agentPrompt = await buildAgentPromptAsync(
        enhanced,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: enhanced,
        agentPrompt: agentPrompt,
        aiEnhanced: !_valuesEquivalent(enhanced, fallbackJson),
        rawAiText: rawParts.join('\n\n'),
      );
    } catch (error) {
      final agentPrompt = await buildAgentPromptAsync(
        fallbackJson,
        useEnglish: useEnglish,
      );
      return RunLogReusableFunctionSpec(
        json: fallbackJson,
        agentPrompt: agentPrompt,
        aiEnhanced: false,
        warning: _text(
          useEnglish,
          'Agent 增强失败，已保留当前复用指令：$error',
          'Agent enhancement failed. Keeping the current command: $error',
        ),
      );
    }
  }

  static Future<_LabelEnhancementPatchResult> _requestLabelEnhancementPatch({
    required String partName,
    required String prompt,
    required Map<String, dynamic> fallbackPatch,
    required bool useEnglish,
  }) async {
    final raw = await AssistsMessageService.postLLMChat(
      text: prompt,
      model: 'scene.compactor.context',
      responseJsonObject: true,
    );
    var aiJson = _extractJsonObject(raw ?? '');
    final rawTexts = <String>[
      if ((raw ?? '').trim().isNotEmpty) '[$partName]\n${raw!.trim()}',
    ];
    if (aiJson == null && (raw ?? '').trim().isNotEmpty) {
      final repairRaw = await AssistsMessageService.postLLMChat(
        text: _buildLabelEnhancementPartRepairPrompt(
          partName: partName,
          invalidOutput: raw ?? '',
          fallbackPatch: fallbackPatch,
          useEnglish: useEnglish,
        ),
        model: 'scene.compactor.context',
        responseJsonObject: true,
      );
      if ((repairRaw ?? '').trim().isNotEmpty) {
        rawTexts.add('[$partName repair]\n${repairRaw!.trim()}');
      }
      aiJson = _extractJsonObject(repairRaw ?? '');
    }
    return _LabelEnhancementPatchResult(json: aiJson, rawTexts: rawTexts);
  }

  static Future<String> buildLabelEnhancementPromptAsync(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    return compute(_buildLabelEnhancementPromptInIsolate, {
      'functionJson': _jsonSafeMap(functionJson),
      'useEnglish': useEnglish,
    });
  }

  static Future<Map<String, dynamic>> applyLabelEnhancementAsync(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    return compute(_applyLabelEnhancementInIsolate, {
      'aiJson': _jsonSafeMap(aiJson),
      'fallback': _jsonSafeMap(fallback),
    });
  }

  static Future<Map<String, dynamic>?> extractJsonObjectAsync(String raw) {
    return compute(_extractJsonObjectInIsolate, raw);
  }

  static Future<Map<String, dynamic>> normalizeAiJsonAsync(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    return compute(_normalizeAiJsonInIsolate, {
      'aiJson': _jsonSafeMap(aiJson),
      'fallback': _jsonSafeMap(fallback),
    });
  }

  static Future<String> buildAgentPromptAsync(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    return compute(_buildAgentPromptInIsolate, {
      'functionJson': _jsonSafeMap(functionJson),
      'useEnglish': useEnglish,
    });
  }

  static String buildAgentPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final functionId = (functionJson['function_id'] ?? '').toString();
    final name = (functionJson['name'] ?? functionId).toString();
    final description = (functionJson['description'] ?? '').toString();
    final parameters = functionJson['parameters'];
    final parameterLines = parameters is List && parameters.isNotEmpty
        ? parameters
              .map((item) {
                final map = _asStringKeyMap(item);
                final defaultValue = map.containsKey('default')
                    ? useEnglish
                          ? ' default=${map['default']}'
                          : ' 默认值=${map['default']}'
                    : '';
                return '- ${map['name']}: ${map['description'] ?? map['type']}$defaultValue';
              })
              .join('\n')
        : useEnglish
        ? '- No explicit parameters'
        : '- 无显式参数';

    if (useEnglish) {
      return [
        'You can reuse this OOB reusable command.',
        '',
        'Reusable command: $name',
        'Reusable command ID: $functionId',
        if (description.isNotEmpty) 'Goal: $description',
        '',
        'Parameters:',
        parameterLines,
        '',
        'Execution strategy:',
        '1. Prefer executing materialized execution.steps in order from the JSON.',
        '2. For executor=omniflow/model_free, execute the local replay action without a model call.',
        '3. For executor=tool, call step.callable_tool with the materialized step.args after validation.',
        '4. For executor=agent or validation mismatch, call step.agent_call.tool / fallback.tool and re-plan that step from the current screen.',
        '5. Runtime arguments are applied through parameters.bindings before execution.',
        '',
        'Reusable command JSON:',
        const JsonEncoder.withIndent('  ').convert(functionJson),
      ].join('\n');
    }

    return [
      '你可以复用这个 OOB 复用指令。',
      '',
      '复用指令：$name',
      '复用指令 ID：$functionId',
      if (description.isNotEmpty) '目标: $description',
      '',
      '参数:',
      parameterLines,
      '',
      '执行策略:',
      '1. 优先按已物化的 execution.steps 顺序执行。',
      '2. executor=omniflow/model_free 时直接执行本地动作，不需要模型调用。',
      '3. executor=tool 时，先检查 validation，再用 step.callable_tool 和已物化 step.args 调工具。',
      '4. executor=agent 或状态不匹配时，调用 step.agent_call.tool / fallback.tool，从当前屏幕重规划该步。',
      '5. 运行时参数会先通过 parameters.bindings 写入对应 step args。',
      '',
      '复用指令 JSON:',
      const JsonEncoder.withIndent('  ').convert(functionJson),
    ].join('\n');
  }

  static String _buildAiPrompt(
    Map<String, dynamic> baseJson, {
    bool useEnglish = false,
  }) {
    final compact = const JsonEncoder.withIndent('  ').convert(baseJson);
    if (useEnglish) {
      return '''
You are the OOB/OmniFlow trajectory organizer. Convert the draft extracted from RunLog below into reusable command JSON.

Requirements:
- Output exactly one JSON object. Do not use Markdown and do not explain.
- The first non-whitespace character must be "{" and the last non-whitespace character must be "}".
- Do not wrap the JSON in code fences. Do not include comments, prose, XML, YAML, or bullet lists.
- Keep schema_version = "oob.reusable_function.v1" and keep function_id exactly unchanged.
- Preserve execution.steps order, tool names, and key args. Do not invent tools that do not exist.
- You may rewrite name/description to make it a clearer reusable command name.
- You may refine parameters: abstract hard-coded user input, search terms, message text, URLs, and target objects into parameters; do not abstract coordinate x/y into user parameters.
- Every parameter must include name/type/description/bindings/default. bindings must be a JSONPath string array pointing to execution.steps[*].args.
- Preserve or improve step executor/model_free/scriptable/omniflow_action/callable_tool/agent_call/validation/fallback fields.
- Keep model_free omniflow actions model-free; do not turn click/scroll/input_text/open_app/back/home/hot_key into agent steps.
- Drop legacy wait cards. Page settling is handled internally by OmniFlow/VLM stability logic and must not become a replay step.
- Keep data-flow and perception tools as executor=agent with callable_tool=oob.agent.run; do not turn browser_use/web_search/memory/oob_run_log tools into direct tool replay.
- Output must be consumable by both the agent and the script runner.

Draft JSON:
$compact
''';
    }
    return '''
你是 OOB/OmniFlow 的轨迹整理器。请把下面由 RunLog 抽取得到的草稿，整理成复用指令 JSON。

要求：
- 只能输出一个 JSON object，不要 Markdown，不要解释。
- 第一个非空白字符必须是 "{"，最后一个非空白字符必须是 "}"。
- 不要使用代码块，不要包含注释、说明文字、XML、YAML 或列表解释。
- 保持 schema_version = "oob.reusable_function.v1"，并保持 function_id 完全不变。
- 保留 execution.steps 的顺序、工具名和关键 args，不要编造不存在的工具。
- 可以重写 name/description，使其更像复用指令名称。
- 可以整理 parameters：把硬编码的用户输入、搜索词、消息文本、URL、目标对象抽象成参数；不要把坐标 x/y 抽象成用户参数。
- 每个 parameter 必须包含 name/type/description/bindings/default，其中 bindings 是 JSONPath 字符串数组，指向 execution.steps[*].args。
- 保留或优化每步的 executor/model_free/scriptable/omniflow_action/callable_tool/agent_call/validation/fallback 字段。
- 保持 model_free omniflow 动作无模型执行，不要把 click/scroll/input_text/open_app/back/home/hot_key 改成 agent 步骤。
- 丢弃旧版 wait 卡片。页面停留由 OmniFlow/VLM 内部稳定逻辑处理，不能生成回放步骤。
- 保持 data-flow 和感知工具为 executor=agent 且 callable_tool=oob.agent.run；不要把 browser_use/web_search/memory/oob_run_log 工具改成直接 tool replay。
- 输出必须能被 agent 和 script 执行器共同消费。

草稿 JSON：
$compact
''';
  }

  static String _buildJsonRepairPrompt({
    required String invalidOutput,
    required Map<String, dynamic> fallbackJson,
    bool useEnglish = false,
  }) {
    final fallback = const JsonEncoder.withIndent('  ').convert(fallbackJson);
    if (useEnglish) {
      return '''
Repair the model output into exactly one valid JSON object.

Hard requirements:
- Return raw JSON only. The first non-whitespace character must be "{" and the last must be "}".
- Do not use Markdown, code fences, comments, or explanations.
- Keep schema_version = "oob.reusable_function.v1" and keep function_id exactly unchanged.
- If the invalid output is unusable, return a valid improved version of the fallback JSON.

Invalid output:
$invalidOutput

Fallback JSON:
$fallback
''';
    }
    return '''
把模型输出修复成一个合法 JSON object。

硬性要求：
- 只返回原始 JSON。第一个非空白字符必须是 "{"，最后一个必须是 "}"。
- 不要 Markdown、代码块、注释或解释。
- 保持 schema_version = "oob.reusable_function.v1"，并保持 function_id 完全不变。
- 如果原输出不可用，就基于 fallback JSON 返回一个合法的优化版本。

无效输出：
$invalidOutput

Fallback JSON：
$fallback
''';
  }

  static String _buildLabelEnhancementPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final compact = const JsonEncoder.withIndent(
      '  ',
    ).convert(_buildLabelEnhancementPromptInput(functionJson));
    if (useEnglish) {
      return '''
You are an OOB/OmniFlow reusable trajectory editor.

Work one section at a time:
1. Name and description.
2. Per-step titles/descriptions.
3. Runtime parameters from candidate_bindings only.
4. Non-executable agent_reuse metadata.

Return exactly one JSON object. Use this example shape:
{
  "name": "short reusable command name",
  "description": "one sentence describing when and why to use it",
  "parameters": [
    {
      "name": "contact_name",
      "type": "string",
      "description": "runtime contact name",
      "default": "Mom",
      "bindings": ["\$.execution.steps[0].args.text"]
    }
  ],
  "steps": [
    {"index": 0, "title": "short action title", "description": "what this action does", "importance": "key"}
  ],
  "agent_reuse": {
    "reuse_when": ["when this recorded trajectory matches the current app/page"],
    "avoid_when": ["when the target app/page is different"],
    "success_signal": "visible state that confirms success",
    "key_actions": [
      {"step_index": 1, "reason": "writes the runtime contact name", "parameter_names": ["contact_name"]}
    ],
    "segments": [
      {
        "name": "Fill contact fields",
        "start_step_index": 1,
        "end_step_index": 2,
        "description": "contiguous slice that can be considered for future split",
        "inputs": ["contact_name", "phone_number"]
      }
    ]
  }
}

Rules:
- Do not use Markdown or explanations.
- Do not change function_id, tools, executors, arguments, parameters, validation, fallback, or step order.
- You may add or rename parameter descriptors only from candidate_bindings in the input digest. Do not bind coordinates, bounds, widths, heights, or invented paths.
- Do not rewrite execution.steps or tool arguments. Parameter abstraction is metadata + bindings only; the runner applies fresh arguments later.
- Prefer reusable slots for user-entered text, contact names, phone numbers, search terms, message text, dates, URLs, and target object names.
- Use agent_reuse only as non-executable metadata for key actions, reuse conditions, avoid conditions, success signal, and contiguous segment candidates.
- Segment candidates must use inclusive contiguous step indexes from the existing execution.steps. Do not claim a segment is already registered as a new command.
- Keep titles concise and action-oriented.
- Include every input step index from execution.steps.

Input digest:
$compact
''';
    }
    return '''
你是 OOB/OmniFlow 的复用轨迹整理器。

按顺序逐项处理：
1. 名称和简介。
2. 每个 step 的标题/描述。
3. 只从 candidate_bindings 中选择运行时参数。
4. 非执行的 agent_reuse 元数据。

只返回一个 JSON object。使用这个样例结构：
{
  "name": "简短的复用指令名称",
  "description": "一句话说明它什么时候、为什么可复用",
  "parameters": [
    {
      "name": "contact_name",
      "type": "string",
      "description": "运行时联系人姓名",
      "default": "妈妈",
      "bindings": ["\$.execution.steps[0].args.text"]
    }
  ],
  "steps": [
    {"index": 0, "title": "简短动作标题", "description": "这个动作做了什么", "importance": "key"}
  ],
  "agent_reuse": {
    "reuse_when": ["当前 app/页面与记录轨迹一致时"],
    "avoid_when": ["目标 app/页面不同或字段语义不匹配时"],
    "success_signal": "可见的完成状态",
    "key_actions": [
      {"step_index": 1, "reason": "写入运行时联系人姓名", "parameter_names": ["contact_name"]}
    ],
    "segments": [
      {
        "name": "填写联系人字段",
        "start_step_index": 1,
        "end_step_index": 2,
        "description": "未来可考虑拆分的连续步骤片段",
        "inputs": ["contact_name", "phone_number"]
      }
    ]
  }
}

规则：
- 不要 Markdown，不要解释。
- 不要改 function_id、tool、executor、arguments、parameters、validation、fallback 或 step 顺序。
- 可以新增或重命名参数描述，但只能从输入摘要的 candidate_bindings 中选择。不要绑定坐标、bounds、宽高或不存在的路径。
- 不要重写 execution.steps 或工具参数。参数抽象只落成 metadata + bindings，回放时由 runner 注入新的运行时参数。
- 优先抽象用户输入文本、联系人姓名、手机号、搜索词、消息正文、日期、URL 和目标对象名。
- agent_reuse 只作为非执行元数据，用来记录 key action、复用条件、避免条件、成功信号和连续 segment 候选。
- segment 候选必须使用现有 execution.steps 的闭区间连续 step index。不要声称 segment 已经注册成新的复用指令。
- 标题要短，像动作说明。
- execution.steps 里的每个 step index 都要覆盖。

输入摘要：
$compact
''';
  }

  static String _buildLabelHeaderEnhancementPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final input = const JsonEncoder.withIndent(
      '  ',
    ).convert(_labelHeaderPromptInput(functionJson));
    if (useEnglish) {
      return '''
You are editing only the name and description of an OOB reusable command.

Return exactly one JSON object:
{"name":"short reusable command name","description":"one sentence describing when and why to use it"}

Rules:
- Return raw JSON only. Do not use Markdown or explanations.
- Do not include steps, parameters, execution, tools, or agent_reuse.
- Keep the name concise and action-oriented.

Input digest:
$input
''';
    }
    return '''
你只负责整理 OOB 复用指令的名称和简介。

只返回一个 JSON object：
{"name":"简短复用指令名称","description":"一句话说明它什么时候、为什么可复用"}

规则：
- 只返回原始 JSON。不要 Markdown，不要解释。
- 不要包含 steps、parameters、execution、tools 或 agent_reuse。
- 名称要短，像可执行指令。

输入摘要：
$input
''';
  }

  static String _buildLabelStepsEnhancementPrompt(
    List<Map<String, dynamic>> steps, {
    bool useEnglish = false,
  }) {
    final input = const JsonEncoder.withIndent('  ').convert({'steps': steps});
    if (useEnglish) {
      return '''
You are editing only per-step titles and descriptions for an OOB reusable command.

Return exactly one JSON object:
{"steps":[{"index":0,"title":"short action title","description":"what this action does","importance":"key"}]}

Rules:
- Return raw JSON only. Do not use Markdown or explanations.
- Include every input step index exactly once.
- Use only indexes from the input digest.
- Do not include name, parameters, execution, tools, args, or agent_reuse.
- Keep titles concise and action-oriented.

Input digest:
$input
''';
    }
    return '''
你只负责整理 OOB 复用指令中每个 step 的标题和描述。

只返回一个 JSON object：
{"steps":[{"index":0,"title":"简短动作标题","description":"这个动作做了什么","importance":"key"}]}

规则：
- 只返回原始 JSON。不要 Markdown，不要解释。
- 输入摘要里的每个 step index 都必须出现一次。
- 只能使用输入摘要中已有的 index。
- 不要包含 name、parameters、execution、tools、args 或 agent_reuse。
- 标题要短，像动作说明。

输入摘要：
$input
''';
  }

  static String _buildLabelParametersEnhancementPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final input = const JsonEncoder.withIndent(
      '  ',
    ).convert(_labelParametersPromptInput(functionJson));
    if (useEnglish) {
      return '''
You are editing only runtime parameter metadata for an OOB reusable command.

Return exactly one JSON object:
{"parameters":[{"name":"contact_name","type":"string","description":"runtime contact name","default":"Mom","bindings":["\$.execution.steps[0].args.text"]}]}

Rules:
- Return raw JSON only. Do not use Markdown or explanations.
- Bindings must be copied exactly from candidate_bindings[*].binding.
- Do not bind coordinates, bounds, widths, heights, or invented paths.
- Prefer slots for user-entered text, contact names, phone numbers, search terms, message text, dates, URLs, and target object names.
- Do not include name, steps, execution, tools, args, or agent_reuse.

Input digest:
$input
''';
    }
    return '''
你只负责整理 OOB 复用指令的运行时参数 metadata。

只返回一个 JSON object：
{"parameters":[{"name":"contact_name","type":"string","description":"运行时联系人姓名","default":"妈妈","bindings":["\$.execution.steps[0].args.text"]}]}

规则：
- 只返回原始 JSON。不要 Markdown，不要解释。
- bindings 必须从 candidate_bindings[*].binding 原样复制。
- 不要绑定坐标、bounds、宽高或不存在的路径。
- 优先抽象用户输入文本、联系人姓名、手机号、搜索词、消息正文、日期、URL 和目标对象名。
- 不要包含 name、steps、execution、tools、args 或 agent_reuse。

输入摘要：
$input
''';
  }

  static String _buildLabelReuseEnhancementPrompt(
    Map<String, dynamic> functionJson, {
    bool useEnglish = false,
  }) {
    final input = const JsonEncoder.withIndent(
      '  ',
    ).convert(_labelReusePromptInput(functionJson));
    if (useEnglish) {
      return '''
You are editing only non-executable agent_reuse metadata for an OOB reusable command.

Return exactly one JSON object:
{"agent_reuse":{"reuse_when":["when this recorded trajectory matches the current app/page"],"avoid_when":["when the target app/page is different"],"success_signal":"visible state that confirms success","key_actions":[{"step_index":1,"reason":"writes the runtime contact name","parameter_names":["contact_name"]}],"segments":[{"name":"Fill contact fields","start_step_index":1,"end_step_index":2,"description":"contiguous slice that can be considered for future split","inputs":["contact_name","phone_number"]}]}}

Rules:
- Return raw JSON only. Do not use Markdown or explanations.
- agent_reuse is metadata only. Do not claim a segment is registered as a new command.
- Segment candidates must use inclusive contiguous step indexes from the input digest.
- key_actions and segment inputs may reference only listed parameter names.
- Do not include name, steps, parameters, execution, tools, or args.

Input digest:
$input
''';
    }
    return '''
你只负责整理 OOB 复用指令的非执行 agent_reuse metadata。

只返回一个 JSON object：
{"agent_reuse":{"reuse_when":["当前 app/页面与记录轨迹一致时"],"avoid_when":["目标 app/页面不同或字段语义不匹配时"],"success_signal":"可见的完成状态","key_actions":[{"step_index":1,"reason":"写入运行时联系人姓名","parameter_names":["contact_name"]}],"segments":[{"name":"填写联系人字段","start_step_index":1,"end_step_index":2,"description":"未来可考虑拆分的连续步骤片段","inputs":["contact_name","phone_number"]}]}}

规则：
- 只返回原始 JSON。不要 Markdown，不要解释。
- agent_reuse 只是 metadata。不要声称 segment 已经注册成新的复用指令。
- segment 候选必须使用输入摘要里的闭区间连续 step index。
- key_actions 和 segment inputs 只能引用已列出的参数名。
- 不要包含 name、steps、parameters、execution、tools 或 args。

输入摘要：
$input
''';
  }

  static String _buildLabelEnhancementPartRepairPrompt({
    required String partName,
    required String invalidOutput,
    required Map<String, dynamic> fallbackPatch,
    bool useEnglish = false,
  }) {
    final fallback = const JsonEncoder.withIndent('  ').convert(fallbackPatch);
    if (useEnglish) {
      return '''
Repair the previous OOB reusable-command $partName output into exactly one valid JSON object.

Return raw JSON only. Do not use Markdown, code fences, comments, or explanations.
If the invalid output is unusable, return the fallback JSON object.

Previous invalid output:
$invalidOutput

Fallback JSON:
$fallback
''';
    }
    return '''
把上一次 OOB 复用指令 $partName 输出修复成一个合法 JSON object。

只返回原始 JSON。不要 Markdown、代码块、注释或解释。
如果原输出不可用，就返回 fallback JSON object。

上一次无效输出：
$invalidOutput

Fallback JSON：
$fallback
''';
  }

  static Map<String, dynamic> _labelHeaderPromptInput(
    Map<String, dynamic> functionJson,
  ) {
    final digest = _buildLabelEnhancementPromptInput(functionJson);
    final rawSteps = digest['steps'] is List
        ? (digest['steps'] as List).map(_asStringKeyMap)
        : const Iterable<Map<String, dynamic>>.empty();
    return {
      'function_id': digest['function_id'],
      'current_name': digest['name'],
      'current_description': digest['description'],
      'constraints': digest['constraints'],
      'steps': rawSteps
          .take(12)
          .map(
            (step) => {
              'index': step['index'],
              'tool': step['tool'],
              'title': step['title'],
              'summary': step['summary'],
            },
          )
          .toList(growable: false),
    };
  }

  static Map<String, dynamic> _labelParametersPromptInput(
    Map<String, dynamic> functionJson,
  ) {
    final digest = _buildLabelEnhancementPromptInput(functionJson);
    return {
      'function_id': digest['function_id'],
      'name': digest['name'],
      'description': digest['description'],
      'existing_parameters': digest['existing_parameters'],
      'steps': digest['steps'],
      'candidate_bindings': digest['candidate_bindings'],
    };
  }

  static Map<String, dynamic> _labelReusePromptInput(
    Map<String, dynamic> functionJson,
  ) {
    final digest = _buildLabelEnhancementPromptInput(functionJson);
    final parameterNames = _parameterNames(functionJson['parameters']);
    return {
      'function_id': digest['function_id'],
      'name': digest['name'],
      'description': digest['description'],
      'steps': digest['steps'],
      'parameter_names': parameterNames.toList(growable: false),
    };
  }

  static Map<String, dynamic> _labelHeaderFallbackPatch(
    Map<String, dynamic> functionJson,
  ) {
    return {
      'name': _firstNonBlank([functionJson['name']]),
      'description': _firstNonBlank([functionJson['description']]),
    };
  }

  static Map<String, dynamic> _labelParametersFallbackPatch(
    Map<String, dynamic> functionJson,
  ) {
    final digest = _buildLabelEnhancementPromptInput(functionJson);
    return {
      'parameters': digest['existing_parameters'] is List
          ? digest['existing_parameters']
          : const <dynamic>[],
    };
  }

  static List<List<Map<String, dynamic>>> _labelStepPromptChunks(
    Map<String, dynamic> functionJson, {
    int chunkSize = 6,
  }) {
    final digest = _buildLabelEnhancementPromptInput(functionJson);
    final steps = digest['steps'] is List
        ? (digest['steps'] as List)
              .map(_asStringKeyMap)
              .where((step) => step.isNotEmpty)
              .toList(growable: false)
        : const <Map<String, dynamic>>[];
    final chunks = <List<Map<String, dynamic>>>[];
    for (var start = 0; start < steps.length; start += chunkSize) {
      final end = (start + chunkSize) > steps.length
          ? steps.length
          : start + chunkSize;
      chunks.add(steps.sublist(start, end));
    }
    return chunks;
  }

  static List<Map<String, dynamic>> _stepPromptFallback(
    List<Map<String, dynamic>> steps,
  ) {
    return steps
        .map(
          (step) => {
            'index': step['index'],
            'title': step['title'],
            'description': step['summary'],
          },
        )
        .toList(growable: false);
  }

  static Map<String, dynamic> _mergeLabelEnhancementPatches(
    List<Map<String, dynamic>> patches,
  ) {
    final merged = <String, dynamic>{};
    final steps = <dynamic>[];
    final parameters = <dynamic>[];
    var agentReuse = <String, dynamic>{};
    for (final patch in patches) {
      final name = _firstNonBlank([patch['name'], patch['title']]);
      if (name.isNotEmpty) {
        merged['name'] = name;
      }
      final description = _firstNonBlank([
        patch['description'],
        patch['summary'],
      ]);
      if (description.isNotEmpty) {
        merged['description'] = description;
      }
      steps.addAll(_firstListValue(patch, const ['steps', 'actions']));
      parameters.addAll(
        _firstListValue(patch, const [
          'parameters',
          'slots',
          'arguments',
          'parameter_suggestions',
          'parameterSuggestions',
        ]),
      );
      agentReuse = _mergeMaps(
        agentReuse,
        _asStringKeyMap(patch['agent_reuse']),
      );
      for (final key in const [
        'reuse_when',
        'avoid_when',
        'success_signal',
        'key_actions',
        'segments',
      ]) {
        if (patch.containsKey(key)) {
          agentReuse[key] = patch[key];
        }
      }
    }
    if (steps.isNotEmpty) {
      merged['steps'] = steps;
    }
    if (parameters.isNotEmpty) {
      merged['parameters'] = parameters;
    }
    if (agentReuse.isNotEmpty) {
      merged['agent_reuse'] = agentReuse;
    }
    return merged;
  }

  static Map<String, dynamic> _buildLabelEnhancementPromptInput(
    Map<String, dynamic> functionJson,
  ) {
    final steps = _executionSteps(functionJson);
    final rawParameters = functionJson['parameters'];
    return {
      'function_id': _firstNonBlank([functionJson['function_id']]),
      'name': _firstNonBlank([functionJson['name']]),
      'description': _firstNonBlank([functionJson['description']]),
      'constraints': _asStringKeyMap(functionJson['constraints']),
      'existing_parameters': rawParameters is List
          ? rawParameters
                .map(_asStringKeyMap)
                .where((item) => item.isNotEmpty)
                .map(
                  (item) => {
                    'name': _firstNonBlank([item['name']]),
                    'type': _firstNonBlank([item['type'], 'string']),
                    'description': _firstNonBlank([item['description']]),
                    if (item.containsKey('default')) 'default': item['default'],
                    'bindings': item['bindings'] is List
                        ? (item['bindings'] as List)
                              .map((entry) => entry.toString())
                              .toList(growable: false)
                        : const <String>[],
                  },
                )
                .toList(growable: false)
          : const <dynamic>[],
      'steps': [
        for (var index = 0; index < steps.length; index++)
          {
            'index': index,
            'id': _firstNonBlank([steps[index]['id'], 'step_${index + 1}']),
            'tool': _firstNonBlank([steps[index]['tool']]),
            'executor': _firstNonBlank([steps[index]['executor']]),
            'title': _firstNonBlank([steps[index]['title']]),
            'summary': _firstNonBlank([
              steps[index]['description'],
              steps[index]['summary'],
            ]),
            'args_preview': _enhancementArgsPreview(steps[index]['args']),
          },
      ],
      'candidate_bindings': _enhancementBindingCandidates(functionJson),
    };
  }

  static Map<String, dynamic> _applyLabelEnhancement(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    final result = _jsonSafeMap(fallback);
    final fallbackName = _firstNonBlank([
      fallback['name'],
      fallback['function_id'],
    ]);
    final name = _firstNonBlank([aiJson['name'], aiJson['title']]);
    if (name.isNotEmpty) {
      result['name'] = _normalizeFunctionName(
        name,
        fallback: fallbackName.isEmpty ? 'reusable_command' : fallbackName,
      );
    }
    final description = _firstNonBlank([
      aiJson['description'],
      aiJson['summary'],
    ]);
    if (description.isNotEmpty) {
      result['description'] = description;
    }

    final execution = _asStringKeyMap(result['execution']);
    final rawSteps = execution['steps'];
    if (rawSteps is List) {
      final steps = rawSteps.map(_asStringKeyMap).toList(growable: true);
      final aiStepsRaw = aiJson['steps'] is List
          ? aiJson['steps'] as List
          : aiJson['actions'] is List
          ? aiJson['actions'] as List
          : const <dynamic>[];
      for (
        var fallbackIndex = 0;
        fallbackIndex < aiStepsRaw.length;
        fallbackIndex++
      ) {
        final aiStep = _asStringKeyMap(aiStepsRaw[fallbackIndex]);
        if (aiStep.isEmpty) continue;
        final index =
            _asInt(
              aiStep['index'] ?? aiStep['step_index'] ?? aiStep['stepIndex'],
            ) ??
            fallbackIndex;
        if (index < 0 || index >= steps.length) continue;
        final title = _firstNonBlank([
          aiStep['title'],
          aiStep['summary'],
          aiStep['name'],
        ]);
        final stepDescription = _firstNonBlank([
          aiStep['description'],
          aiStep['detail'],
          aiStep['intent'],
        ]);
        if (title.isNotEmpty) {
          steps[index]['title'] = title;
          steps[index]['summary'] = title;
        }
        if (stepDescription.isNotEmpty) {
          steps[index]['description'] = stepDescription;
          steps[index].putIfAbsent('summary', () => stepDescription);
          steps[index].putIfAbsent('title', () => stepDescription);
        }
      }
      execution['steps'] = steps;
      result['execution'] = execution;

      final rawActions = result['actions'];
      if (rawActions is List) {
        final actions = rawActions.map(_asStringKeyMap).toList(growable: true);
        for (
          var index = 0;
          index < steps.length && index < actions.length;
          index++
        ) {
          final stepDescription = _firstNonBlank([
            steps[index]['description'],
            steps[index]['summary'],
            steps[index]['title'],
          ]);
          if (stepDescription.isNotEmpty) {
            actions[index]['description'] = stepDescription;
          }
        }
        result['actions'] = actions;
      }
    }
    result['parameters'] = _applyParameterEnhancement(aiJson, result);
    final agentReuse = _agentReuseEnhancement(aiJson, result);
    if (agentReuse.isNotEmpty) {
      result['agent_reuse'] = _mergeMaps(
        _asStringKeyMap(result['agent_reuse']),
        agentReuse,
      );
    }
    return result;
  }

  static List<dynamic> _applyParameterEnhancement(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> functionJson,
  ) {
    final existing = functionJson['parameters'] is List
        ? (functionJson['parameters'] as List)
              .map(_asStringKeyMap)
              .where((item) => item.isNotEmpty)
              .toList(growable: false)
        : const <Map<String, dynamic>>[];
    final aiParameters = _firstListValue(aiJson, const [
      'parameters',
      'slots',
      'arguments',
      'parameter_suggestions',
      'parameterSuggestions',
    ]);
    if (aiParameters.isEmpty) {
      return existing;
    }

    final steps = _executionSteps(functionJson);
    final existingBindingTargets = <String, _ParameterBindingTarget>{};
    final existingByBinding = <String, Map<String, dynamic>>{};
    for (final parameter in existing) {
      for (final target in _validBindingTargets(parameter['bindings'], steps)) {
        existingBindingTargets[target.binding] = target;
        existingByBinding[target.binding] = parameter;
      }
    }

    final usedNames = <String>{};
    final consumedBindings = <String>{};
    final output = <Map<String, dynamic>>[];

    for (final rawParameter in aiParameters) {
      final parameter = _asStringKeyMap(rawParameter);
      if (parameter.isEmpty) {
        continue;
      }
      final directTargets = _validBindingTargets(parameter['bindings'], steps)
          .where((target) => !consumedBindings.contains(target.binding))
          .toList(growable: false);
      if (directTargets.isEmpty) {
        continue;
      }
      final compatibleTargets = _compatibleParameterTargets(
        directTargets,
        existingBindingTargets,
        existingByBinding,
        steps,
      );
      if (compatibleTargets.isEmpty ||
          !_bindingTargetValuesMatch(compatibleTargets)) {
        continue;
      }
      final defaultValue = _jsonSafe(compatibleTargets.first.value);
      final baseName = _firstNonBlank([
        parameter['name'],
        parameter['id'],
        parameter['role'],
        _parameterBaseName(compatibleTargets.first.leafKey, ''),
      ]);
      final name = _uniqueParameterName(baseName, usedNames);
      final description = _firstNonBlank([
        parameter['description'],
        parameter['summary'],
        parameter['role'],
        name,
      ]);
      final type = _safeParameterType(parameter['type'], defaultValue);
      final bindings = <String>[];
      final sourceSteps = <String>[];
      for (final target in compatibleTargets) {
        if (!bindings.contains(target.binding)) {
          bindings.add(target.binding);
        }
        if (target.stepId.isNotEmpty && !sourceSteps.contains(target.stepId)) {
          sourceSteps.add(target.stepId);
        }
        consumedBindings.add(target.binding);
      }
      final enhanced = <String, dynamic>{
        'name': name,
        'type': type,
        'description': description,
        'default': defaultValue,
        'required': parameter['required'] == true,
        'bindings': bindings,
        if (sourceSteps.isNotEmpty) 'source_steps': sourceSteps,
      };
      final reuseRole = _firstNonBlank([
        parameter['reuse_role'],
        parameter['reuseRole'],
        parameter['role'],
        parameter['semantic'],
      ]);
      if (reuseRole.isNotEmpty) {
        enhanced['reuse_role'] = _compactPreview(reuseRole, maxLength: 80);
      }
      output.add(enhanced);
    }

    for (final parameter in existing) {
      final targets = _validBindingTargets(parameter['bindings'], steps);
      if (targets.isEmpty) {
        final name = _uniqueParameterName(
          _firstNonBlank([parameter['name'], 'parameter']),
          usedNames,
        );
        output.add({...parameter, 'name': name});
        continue;
      }
      final remainingBindings = targets
          .map((target) => target.binding)
          .where((binding) => !consumedBindings.contains(binding))
          .toList(growable: false);
      if (remainingBindings.isEmpty) {
        continue;
      }
      final name = _uniqueParameterName(
        _firstNonBlank([parameter['name'], 'parameter']),
        usedNames,
      );
      output.add({...parameter, 'name': name, 'bindings': remainingBindings});
    }

    return output.isEmpty ? existing : output;
  }

  static Map<String, dynamic> _agentReuseEnhancement(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> functionJson,
  ) {
    final raw = _mergeMaps(_asStringKeyMap(aiJson['agent_reuse']), {
      if (aiJson['reuse_when'] != null) 'reuse_when': aiJson['reuse_when'],
      if (aiJson['avoid_when'] != null) 'avoid_when': aiJson['avoid_when'],
      if (aiJson['success_signal'] != null)
        'success_signal': aiJson['success_signal'],
      if (aiJson['key_actions'] != null) 'key_actions': aiJson['key_actions'],
      if (aiJson['segments'] != null) 'segments': aiJson['segments'],
    });
    if (raw.isEmpty) {
      return const <String, dynamic>{};
    }

    final steps = _executionSteps(functionJson);
    if (steps.isEmpty) {
      return const <String, dynamic>{};
    }
    final parameterNames = _parameterNames(functionJson['parameters']);
    final reuseWhen = _safeStringList(raw['reuse_when'], maxItems: 6);
    final avoidWhen = _safeStringList(raw['avoid_when'], maxItems: 6);
    final successSignal = _boundedText(raw['success_signal'], maxLength: 240);
    final keyActions = _safeKeyActions(
      raw['key_actions'],
      steps,
      parameterNames,
    );
    final segments = _safeReuseSegments(raw['segments'], steps, parameterNames);

    final output = <String, dynamic>{
      'schema_version': 'oob.agent_reuse.v1',
      'mode': 'metadata_only',
      'execution_rewrite_allowed': false,
      if (reuseWhen.isNotEmpty) 'reuse_when': reuseWhen,
      if (avoidWhen.isNotEmpty) 'avoid_when': avoidWhen,
      if (successSignal.isNotEmpty) 'success_signal': successSignal,
      if (keyActions.isNotEmpty) 'key_actions': keyActions,
      if (segments.isNotEmpty) 'segments': segments,
    };
    return output.length <= 3 ? const <String, dynamic>{} : output;
  }

  static Map<String, dynamic> _normalizeAiJson(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    final normalized = Map<String, dynamic>.from(aiJson);
    normalized['schema_version'] = 'oob.reusable_function.v1';
    normalized['function_id'] = _normalizeFunctionId(
      _firstNonBlank([fallback['function_id'], normalized['function_id']]),
      fallback: _firstNonBlank([fallback['function_id']]),
    );
    normalized['source'] = _mergeMaps(
      _asStringKeyMap(fallback['source']),
      _asStringKeyMap(normalized['source']),
    );
    normalized['parameters'] = _normalizeParameters(
      normalized['parameters'],
      fallback['parameters'],
    );
    normalized['constraints'] = _mergeMaps(
      _asStringKeyMap(fallback['constraints']),
      _asStringKeyMap(normalized['constraints']),
    );

    final fallbackExecution = _asStringKeyMap(fallback['execution']);
    final execution = _asStringKeyMap(normalized['execution']);
    final aiSteps = execution['steps'];
    final fallbackSteps = fallbackExecution['steps'];
    final normalizedSteps = _normalizeExecutionSteps(aiSteps, fallbackSteps);
    normalized['execution'] = {
      ...fallbackExecution,
      ...execution,
      'capabilities': _executionCapabilitiesForSteps(
        normalizedSteps,
        fallback: _mergeMaps(
          _asStringKeyMap(fallbackExecution['capabilities']),
          _asStringKeyMap(execution['capabilities']),
        ),
      ),
      'steps': normalizedSteps,
    };
    for (final key in const [
      'tags',
      'runtime_targets',
      'call_contract',
      'agent_reuse',
      'script_reuse',
    ]) {
      normalized.remove(key);
    }
    if (_firstNonBlank([normalized['name']]).isEmpty) {
      normalized['name'] = fallback['name'];
    }
    if (_firstNonBlank([normalized['description']]).isEmpty) {
      normalized['description'] = fallback['description'];
    }
    final safe = _jsonSafe(normalized);
    if (safe is Map<String, dynamic>) {
      return safe;
    }
    if (safe is Map) {
      return safe.map((key, value) => MapEntry(key.toString(), value));
    }
    return Map<String, dynamic>.from(fallback);
  }

  @visibleForTesting
  static Map<String, dynamic> normalizeAiJsonForTesting(
    Map<String, dynamic> aiJson,
    Map<String, dynamic> fallback,
  ) {
    return _normalizeAiJson(aiJson, fallback);
  }
}

Map<String, dynamic> _buildLocalFunctionJsonInIsolate(
  Map<String, dynamic> args,
) {
  final payload = _asStringKeyMap(args['payload']);
  final rawCards = args['cards'] is List ? args['cards'] as List : const [];
  final cards = rawCards
      .map(_asStringKeyMap)
      .where((card) => card.isNotEmpty)
      .toList(growable: false);
  return RunLogReusableFunctionConverter.buildLocalFunctionJson(
    runId: (args['runId'] ?? '').toString(),
    title: (args['title'] ?? '').toString(),
    payload: payload,
    cards: cards,
    useEnglish: args['useEnglish'] == true,
  );
}

String _buildAiPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._buildAiPrompt(
    _asStringKeyMap(args['baseJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

String _buildJsonRepairPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._buildJsonRepairPrompt(
    invalidOutput: (args['invalidOutput'] ?? '').toString(),
    fallbackJson: _asStringKeyMap(args['fallbackJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

Map<String, dynamic>? _extractJsonObjectInIsolate(String raw) {
  return _extractJsonObject(raw);
}

Map<String, dynamic> _normalizeAiJsonInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._normalizeAiJson(
    _asStringKeyMap(args['aiJson']),
    _asStringKeyMap(args['fallback']),
  );
}

String _buildLabelEnhancementPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter._buildLabelEnhancementPrompt(
    _asStringKeyMap(args['functionJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

Map<String, dynamic> _applyLabelEnhancementInIsolate(
  Map<String, dynamic> args,
) {
  return RunLogReusableFunctionConverter._applyLabelEnhancement(
    _asStringKeyMap(args['aiJson']),
    _asStringKeyMap(args['fallback']),
  );
}

String _buildAgentPromptInIsolate(Map<String, dynamic> args) {
  return RunLogReusableFunctionConverter.buildAgentPrompt(
    _asStringKeyMap(args['functionJson']),
    useEnglish: args['useEnglish'] == true,
  );
}

class _RunLogActionSnapshot {
  const _RunLogActionSnapshot({
    required this.title,
    required this.toolName,
    required this.args,
    required this.result,
    required this.route,
    required this.success,
    required this.durationMs,
    required this.packageName,
    required this.beforeSummary,
    required this.afterSummary,
    required this.beforeXml,
    required this.prompt,
    required this.promptSource,
  });

  final String title;
  final String toolName;
  final dynamic args;
  final dynamic result;
  final String route;
  final bool? success;
  final int? durationMs;
  final String packageName;
  final String beforeSummary;
  final String afterSummary;
  final String beforeXml;
  final String prompt;
  final String promptSource;

  factory _RunLogActionSnapshot.fromCard(
    Map<String, dynamic> card, {
    required int fallbackIndex,
  }) {
    final header = _asStringKeyMap(card['header']);
    final before = _asStringKeyMap(card['before']).isNotEmpty
        ? _asStringKeyMap(card['before'])
        : _asStringKeyMap(card['observation_before_act']).isNotEmpty
        ? _asStringKeyMap(card['observation_before_act'])
        : _asStringKeyMap(card['before_observation']).isNotEmpty
        ? _asStringKeyMap(card['before_observation'])
        : _asStringKeyMap(card['observation']);
    final after = _asStringKeyMap(card['after']);
    final toolCall = _extractToolCall(card);
    final function = _asStringKeyMap(toolCall['function']);
    final args = _extractArgs(card, toolCall, function);
    final argsMap = _asStringKeyMap(args);
    final promptHit = _extractPromptHit([
      _PromptSearchRoot('args', args),
      _PromptSearchRoot('tool_call', toolCall),
      _PromptSearchRoot('function', function),
      _PromptSearchRoot('card', card),
    ]);
    final toolName = _firstNonBlank([
      toolCall['name'],
      toolCall['tool_name'],
      toolCall['toolName'],
      function['name'],
      card['tool_name'],
      card['toolName'],
      card['action_type'],
      card['actionType'],
      header['tool_name'],
      header['toolName'],
    ]);
    return _RunLogActionSnapshot(
      title: _firstNonBlank([
        header['title'],
        card['title'],
        card['summary'],
        card['operation_description'],
        card['operationDescription'],
        toolName,
        'Step ${fallbackIndex + 1}',
      ]),
      toolName: toolName.isEmpty ? 'unknown_tool' : toolName,
      args: args,
      result: _extractResult(card),
      route: _firstNonBlank([
        header['compile_kind'],
        header['compileKind'],
        card['compile_kind'],
        card['compileKind'],
        card['selection_source'] == 'vlm' ? 'miss' : null,
      ]),
      success:
          _asBool(_firstPresentValue(header, const ['success'])) ??
          _asBool(_firstPresentValue(card, const ['success'])),
      durationMs: _asInt(
        _firstPresentValue(header, const ['duration_ms', 'durationMs']) ??
            _firstPresentValue(card, const ['duration_ms', 'durationMs']),
      ),
      packageName: _firstNonBlank([
        before['package_name'],
        before['packageName'],
        after['package_name'],
        after['packageName'],
        argsMap['package_name'],
        argsMap['packageName'],
        card['package_name'],
        card['packageName'],
      ]),
      beforeSummary: _stateSummary(before),
      afterSummary: _stateSummary(after),
      beforeXml: _firstNonBlank([
        before['observation_xml'],
        before['observationXml'],
        before['xml'],
        before['page'],
      ]),
      prompt: promptHit.text,
      promptSource: promptHit.source,
    );
  }
}

class _ParameterBindingTarget {
  const _ParameterBindingTarget({
    required this.binding,
    required this.stepIndex,
    required this.stepId,
    required this.pathSuffix,
    required this.leafKey,
    required this.value,
  });

  final String binding;
  final int stepIndex;
  final String stepId;
  final String pathSuffix;
  final String leafKey;
  final dynamic value;
}

List<dynamic> _firstListValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final value = source[key];
    if (value is List && value.isNotEmpty) {
      return value;
    }
  }
  return const <dynamic>[];
}

List<Map<String, dynamic>> _executionSteps(Map<String, dynamic> functionJson) {
  final execution = _asStringKeyMap(functionJson['execution']);
  final rawSteps = execution['steps'];
  if (rawSteps is! List) {
    return const <Map<String, dynamic>>[];
  }
  return rawSteps
      .map(_asStringKeyMap)
      .where((step) => step.isNotEmpty)
      .toList(growable: false);
}

Map<String, dynamic> _enhancementArgsPreview(dynamic rawArgs) {
  final args = _asStringKeyMap(rawArgs);
  if (args.isEmpty) {
    return const <String, dynamic>{};
  }
  final output = <String, dynamic>{};
  for (final entry in args.entries) {
    final key = entry.key.trim();
    if (key.isEmpty || _isCoordinateLikeKey(key.toLowerCase())) {
      continue;
    }
    final preview = _enhancementPreviewValue(entry.value, depth: 0);
    if (!_isEmptyJsonValue(preview)) {
      output[key] = preview;
    }
    if (output.length >= 12) {
      output['__truncated__'] = true;
      break;
    }
  }
  return output;
}

dynamic _enhancementPreviewValue(dynamic raw, {required int depth}) {
  final value = _jsonSafe(raw);
  if (value == null || value is num || value is bool) {
    return value;
  }
  if (value is String) {
    return _compactPreview(value, maxLength: 160);
  }
  if (depth >= 2) {
    return '<nested>';
  }
  if (value is Map) {
    final output = <String, dynamic>{};
    for (final entry in value.entries) {
      final key = entry.key.toString();
      if (_isCoordinateLikeKey(key.toLowerCase())) {
        continue;
      }
      output[key] = _enhancementPreviewValue(entry.value, depth: depth + 1);
      if (output.length >= 8) {
        output['__truncated__'] = true;
        break;
      }
    }
    return output;
  }
  if (value is Iterable) {
    final output = <dynamic>[];
    for (final item in value) {
      output.add(_enhancementPreviewValue(item, depth: depth + 1));
      if (output.length >= 5) {
        output.add('<truncated>');
        break;
      }
    }
    return output;
  }
  return _compactPreview(value.toString(), maxLength: 120);
}

List<Map<String, dynamic>> _enhancementBindingCandidates(
  Map<String, dynamic> functionJson,
) {
  final steps = _executionSteps(functionJson);
  final output = <Map<String, dynamic>>[];
  for (var stepIndex = 0; stepIndex < steps.length; stepIndex++) {
    final step = steps[stepIndex];
    final args = _asStringKeyMap(step['args']);
    if (args.isEmpty) {
      continue;
    }
    final toolName = _firstNonBlank([step['tool'], step['source_tool']]);
    _collectEnhancementBindingCandidates(
      output: output,
      step: step,
      stepIndex: stepIndex,
      toolName: toolName,
      value: args,
      path: const <String>[],
    );
    if (output.length >= 80) {
      break;
    }
  }
  return output;
}

void _collectEnhancementBindingCandidates({
  required List<Map<String, dynamic>> output,
  required Map<String, dynamic> step,
  required int stepIndex,
  required String toolName,
  required dynamic value,
  required List<String> path,
}) {
  if (output.length >= 80) {
    return;
  }
  final decoded = _jsonSafe(value);
  if (decoded is Map) {
    for (final entry in decoded.entries) {
      final key = entry.key.toString().trim();
      if (key.isEmpty) {
        continue;
      }
      _collectEnhancementBindingCandidates(
        output: output,
        step: step,
        stepIndex: stepIndex,
        toolName: toolName,
        value: entry.value,
        path: [...path, key],
      );
    }
    return;
  }
  if (decoded is Iterable && decoded is! String) {
    var index = 0;
    for (final item in decoded) {
      _collectEnhancementBindingCandidates(
        output: output,
        step: step,
        stepIndex: stepIndex,
        toolName: toolName,
        value: item,
        path: [
          ...path.take(path.length > 1 ? path.length - 1 : path.length),
          path.isEmpty ? 'item[$index]' : '${path.last}[$index]',
        ],
      );
      index++;
      if (index >= 8) {
        break;
      }
    }
    return;
  }
  if (path.isEmpty) {
    return;
  }
  final leafKey = path.last.split('[').first;
  if (_isBlockedParameterPath(path.join('.')) ||
      !_isParameterCandidate(toolName, leafKey, decoded)) {
    return;
  }
  if (decoded is String && decoded.trim().isEmpty) {
    return;
  }
  final suffix = _jsonPathSuffix(path);
  if (suffix.isEmpty) {
    return;
  }
  output.add({
    'binding': '\$.execution.steps[$stepIndex].args.$suffix',
    'step_index': stepIndex,
    'step_id': _firstNonBlank([step['id'], 'step_${stepIndex + 1}']),
    'step_title': _firstNonBlank([step['title']]),
    'arg_key': leafKey,
    'recorded_value': _enhancementPreviewValue(decoded, depth: 0),
    'value_type': decoded is num
        ? 'number'
        : decoded is bool
        ? 'boolean'
        : 'string',
  });
}

List<_ParameterBindingTarget> _validBindingTargets(
  dynamic rawBindings,
  List<Map<String, dynamic>> steps,
) {
  if (rawBindings is! List || rawBindings.isEmpty) {
    return const <_ParameterBindingTarget>[];
  }
  final output = <_ParameterBindingTarget>[];
  final seen = <String>{};
  for (final rawBinding in rawBindings) {
    final binding = rawBinding?.toString().trim() ?? '';
    if (binding.isEmpty || seen.contains(binding)) {
      continue;
    }
    final target = _bindingTargetForPath(binding, steps);
    if (target == null) {
      continue;
    }
    seen.add(binding);
    output.add(target);
  }
  return output;
}

_ParameterBindingTarget? _bindingTargetForPath(
  String binding,
  List<Map<String, dynamic>> steps,
) {
  final match = RegExp(
    r'^\$\.execution\.steps\[(\d+)\]\.(args|agent_call\.args\.original_args)\.([A-Za-z0-9_]+(?:\[\d+\])?(?:\.[A-Za-z0-9_]+(?:\[\d+\])?)*)$',
  ).firstMatch(binding);
  if (match == null) {
    return null;
  }
  final stepIndex = int.tryParse(match.group(1) ?? '');
  if (stepIndex == null || stepIndex < 0 || stepIndex >= steps.length) {
    return null;
  }
  final rootKind = match.group(2) ?? '';
  final pathSuffix = match.group(3) ?? '';
  if (_isBlockedParameterPath(pathSuffix)) {
    return null;
  }
  final step = steps[stepIndex];
  final root = rootKind == 'args'
      ? _asStringKeyMap(step['args'])
      : _asStringKeyMap(
          _asStringKeyMap(
            _asStringKeyMap(step['agent_call'])['args'],
          )['original_args'],
        );
  if (root.isEmpty) {
    return null;
  }
  final value = _valueAtPathSuffix(root, pathSuffix);
  if (value == null || value is Map || value is Iterable) {
    return null;
  }
  if (value is String && value.trim().isEmpty) {
    return null;
  }
  final leafKey = pathSuffix.split('.').last.split('[').first;
  return _ParameterBindingTarget(
    binding: binding,
    stepIndex: stepIndex,
    stepId: _firstNonBlank([step['id'], 'step_${stepIndex + 1}']),
    pathSuffix: pathSuffix,
    leafKey: leafKey,
    value: value,
  );
}

dynamic _valueAtPathSuffix(Map<String, dynamic> root, String pathSuffix) {
  dynamic current = root;
  for (final part in pathSuffix.split('.')) {
    final match = RegExp(r'^([A-Za-z0-9_]+)(?:\[(\d+)])?$').firstMatch(part);
    if (match == null) {
      return null;
    }
    final key = match.group(1) ?? '';
    final index = int.tryParse(match.group(2) ?? '');
    if (current is! Map) {
      return null;
    }
    current = _asStringKeyMap(current)[key];
    if (index != null) {
      if (current is! List || index < 0 || index >= current.length) {
        return null;
      }
      current = current[index];
    }
  }
  return current;
}

bool _isBlockedParameterPath(String pathSuffix) {
  if (pathSuffix.trim().isEmpty) {
    return true;
  }
  for (final part in pathSuffix.split('.')) {
    final key = part.split('[').first.toLowerCase();
    if (_isCoordinateLikeKey(key)) {
      return true;
    }
  }
  return false;
}

List<_ParameterBindingTarget> _compatibleParameterTargets(
  List<_ParameterBindingTarget> directTargets,
  Map<String, _ParameterBindingTarget> existingBindingTargets,
  Map<String, Map<String, dynamic>> existingByBinding,
  List<Map<String, dynamic>> steps,
) {
  final output = <_ParameterBindingTarget>[];
  final seen = <String>{};
  void addTarget(_ParameterBindingTarget target) {
    if (seen.add(target.binding)) {
      output.add(target);
    }
  }

  for (final target in directTargets) {
    addTarget(target);
    final existingParameter = existingByBinding[target.binding];
    if (existingParameter == null) {
      continue;
    }
    for (final sibling in _validBindingTargets(
      existingParameter['bindings'],
      steps,
    )) {
      if (_valuesEquivalent(sibling.value, target.value)) {
        addTarget(sibling);
      }
    }
  }
  for (final target in directTargets) {
    final existingTarget = existingBindingTargets[target.binding];
    if (existingTarget != null &&
        _valuesEquivalent(existingTarget.value, target.value)) {
      addTarget(existingTarget);
    }
  }
  return output;
}

bool _bindingTargetValuesMatch(List<_ParameterBindingTarget> targets) {
  if (targets.isEmpty) {
    return false;
  }
  final first = targets.first.value;
  return targets.every((target) => _valuesEquivalent(target.value, first));
}

bool _valuesEquivalent(dynamic left, dynamic right) {
  return const JsonEncoder().convert(_jsonSafe(left)) ==
      const JsonEncoder().convert(_jsonSafe(right));
}

String _safeParameterType(dynamic rawType, dynamic defaultValue) {
  final type = rawType?.toString().trim().toLowerCase() ?? '';
  if (const {'string', 'number', 'integer', 'boolean'}.contains(type)) {
    return type;
  }
  if (defaultValue is num) {
    return 'number';
  }
  if (defaultValue is bool) {
    return 'boolean';
  }
  return 'string';
}

String _uniqueParameterName(String rawName, Set<String> usedNames) {
  var base = rawName
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]}_${m[2]}')
      .replaceAll(RegExp(r'[^A-Za-z0-9_]+'), '_')
      .toLowerCase()
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^_|_$'), '');
  if (base.isEmpty) {
    base = 'parameter';
  }
  if (RegExp(r'^[0-9]').hasMatch(base)) {
    base = 'parameter_$base';
  }
  if (base.length > 48) {
    base = base.substring(0, 48).replaceAll(RegExp(r'_+$'), '');
  }
  var name = base;
  var suffix = 2;
  while (usedNames.contains(name)) {
    final suffixText = '_$suffix';
    final prefixLimit = 48 - suffixText.length;
    final prefixLength = base.length < prefixLimit ? base.length : prefixLimit;
    name = '${base.substring(0, prefixLength)}$suffixText';
    suffix++;
  }
  usedNames.add(name);
  return name;
}

Set<String> _parameterNames(dynamic rawParameters) {
  if (rawParameters is List) {
    return rawParameters
        .map(_asStringKeyMap)
        .map((item) => _firstNonBlank([item['name']]))
        .where((name) => name.isNotEmpty)
        .toSet();
  }
  final schema = _asStringKeyMap(rawParameters);
  return _asStringKeyMap(schema['properties']).keys.toSet();
}

List<String> _safeStringList(
  dynamic value, {
  int maxItems = 8,
  int maxLength = 160,
}) {
  final rawItems = value is List
      ? value
      : value is String
      ? <dynamic>[value]
      : const <dynamic>[];
  final output = <String>[];
  for (final raw in rawItems) {
    final text = _boundedText(raw, maxLength: maxLength);
    if (text.isEmpty || output.contains(text)) {
      continue;
    }
    output.add(text);
    if (output.length >= maxItems) {
      break;
    }
  }
  return output;
}

String _boundedText(dynamic value, {int maxLength = 160}) {
  final text = _firstNonBlank([value]);
  if (text.isEmpty) {
    return '';
  }
  return _compactPreview(text, maxLength: maxLength);
}

List<Map<String, dynamic>> _safeKeyActions(
  dynamic value,
  List<Map<String, dynamic>> steps,
  Set<String> parameterNames,
) {
  if (value is! List || value.isEmpty) {
    return const <Map<String, dynamic>>[];
  }
  final output = <Map<String, dynamic>>[];
  final seenIndexes = <int>{};
  for (final raw in value) {
    final item = _asStringKeyMap(raw);
    final index = _asInt(
      item['step_index'] ?? item['stepIndex'] ?? item['index'],
    );
    if (index == null ||
        index < 0 ||
        index >= steps.length ||
        seenIndexes.contains(index)) {
      continue;
    }
    seenIndexes.add(index);
    final step = steps[index];
    final reason = _boundedText(
      _firstNonBlank([item['reason'], item['description'], item['summary']]),
      maxLength: 180,
    );
    final parameters = _safeParameterNameList(
      item['parameter_names'] ?? item['parameters'] ?? item['inputs'],
      parameterNames,
    );
    output.add({
      'step_index': index,
      'step_id': _firstNonBlank([step['id'], 'step_${index + 1}']),
      'title': _boundedText(
        _firstNonBlank([item['title'], item['name'], step['title']]),
        maxLength: 80,
      ),
      if (reason.isNotEmpty) 'reason': reason,
      if (parameters.isNotEmpty) 'parameter_names': parameters,
      if (_safeImportance(item['importance']).isNotEmpty)
        'importance': _safeImportance(item['importance']),
    });
    if (output.length >= 8) {
      break;
    }
  }
  return output;
}

List<Map<String, dynamic>> _safeReuseSegments(
  dynamic value,
  List<Map<String, dynamic>> steps,
  Set<String> parameterNames,
) {
  if (value is! List || value.isEmpty) {
    return const <Map<String, dynamic>>[];
  }
  final output = <Map<String, dynamic>>[];
  for (final raw in value) {
    final item = _asStringKeyMap(raw);
    final start = _asInt(
      item['start_step_index'] ??
          item['startStepIndex'] ??
          item['start_index'] ??
          item['start'],
    );
    final end = _asInt(
      item['end_step_index'] ??
          item['endStepIndex'] ??
          item['end_index'] ??
          item['end'],
    );
    if (start == null ||
        end == null ||
        start < 0 ||
        end < start ||
        end >= steps.length) {
      continue;
    }
    final parameters = _safeParameterNameList(
      item['input_parameters'] ??
          item['parameter_names'] ??
          item['inputs'] ??
          item['parameters'],
      parameterNames,
    );
    final name = _boundedText(
      _firstNonBlank([
        item['name'],
        item['title'],
        'steps_${start + 1}_${end + 1}',
      ]),
      maxLength: 80,
    );
    final description = _boundedText(
      _firstNonBlank([item['description'], item['summary'], item['reason']]),
      maxLength: 220,
    );
    output.add({
      'name': name,
      'kind': 'contiguous_step_slice',
      'materialization': 'metadata_only',
      'split_candidate': true,
      'start_step_index': start,
      'end_step_index': end,
      'step_ids': [
        for (var index = start; index <= end; index++)
          _firstNonBlank([steps[index]['id'], 'step_${index + 1}']),
      ],
      if (description.isNotEmpty) 'description': description,
      if (parameters.isNotEmpty) 'input_parameters': parameters,
      if (_safeImportance(item['importance']).isNotEmpty)
        'importance': _safeImportance(item['importance']),
    });
    if (output.length >= 8) {
      break;
    }
  }
  return output;
}

List<String> _safeParameterNameList(dynamic value, Set<String> allowedNames) {
  final rawItems = value is List
      ? value
      : value is String
      ? value.split(RegExp(r'[,，\s]+'))
      : const <dynamic>[];
  final output = <String>[];
  for (final raw in rawItems) {
    final name = raw?.toString().trim() ?? '';
    if (name.isEmpty || !allowedNames.contains(name) || output.contains(name)) {
      continue;
    }
    output.add(name);
  }
  return output;
}

String _safeImportance(dynamic value) {
  final text = value?.toString().trim().toLowerCase() ?? '';
  return switch (text) {
    'key' || 'critical' || 'important' => 'key',
    'support' || 'supporting' => 'support',
    'normal' => 'normal',
    _ => '',
  };
}

void _collectParametersFromArgs({
  required String stepId,
  required int stepIndex,
  required String toolName,
  required dynamic args,
  required Map<String, Map<String, dynamic>> parametersBySignature,
  required Set<String> seenNames,
  required bool useEnglish,
  bool bindAgentOriginalArgs = false,
  List<String> path = const [],
}) {
  final argMap = _asStringKeyMap(args);
  if (argMap.isEmpty) {
    return;
  }
  for (final entry in argMap.entries) {
    final key = entry.key.trim();
    if (key.isEmpty) {
      continue;
    }
    final value = _decodeJsonIfNeeded(entry.value);
    final nextPath = [...path, key];
    if (value is Map) {
      _collectParametersFromArgs(
        stepId: stepId,
        stepIndex: stepIndex,
        toolName: toolName,
        args: value,
        parametersBySignature: parametersBySignature,
        seenNames: seenNames,
        useEnglish: useEnglish,
        bindAgentOriginalArgs: bindAgentOriginalArgs,
        path: nextPath,
      );
      continue;
    }
    if (value is Iterable) {
      var itemIndex = 0;
      for (final rawItem in value) {
        final item = _decodeJsonIfNeeded(rawItem);
        if (item is Map) {
          _collectParametersFromArgs(
            stepId: stepId,
            stepIndex: stepIndex,
            toolName: toolName,
            args: item,
            parametersBySignature: parametersBySignature,
            seenNames: seenNames,
            useEnglish: useEnglish,
            bindAgentOriginalArgs: bindAgentOriginalArgs,
            path: [...path, '$key[$itemIndex]'],
          );
        }
        itemIndex++;
      }
      continue;
    }
    final parameter = _parameterFromArg(
      stepId: stepId,
      stepIndex: stepIndex,
      toolName: toolName,
      key: key,
      path: nextPath,
      value: value,
      useEnglish: useEnglish,
      bindAgentOriginalArgs: bindAgentOriginalArgs,
    );
    if (parameter == null) {
      continue;
    }
    final signature = _parameterSignature(parameter);
    final existing = parametersBySignature[signature];
    if (existing != null) {
      _appendUnique(existing, 'bindings', parameter['bindings']);
      _appendUnique(existing, 'source_steps', parameter['source_steps']);
      continue;
    }
    final baseName = parameter['name'].toString();
    var name = baseName;
    var suffix = 2;
    while (seenNames.contains(name)) {
      name = '${baseName}_$suffix';
      suffix++;
    }
    seenNames.add(name);
    parametersBySignature[signature] = {...parameter, 'name': name};
  }
}

Map<String, dynamic>? _parameterFromArg({
  required String stepId,
  required int stepIndex,
  required String toolName,
  required String key,
  required List<String> path,
  required dynamic value,
  required bool useEnglish,
  required bool bindAgentOriginalArgs,
}) {
  final normalizedKey = key.trim();
  if (normalizedKey.isEmpty || value == null) {
    return null;
  }
  if (!_isParameterCandidate(toolName, normalizedKey, value)) {
    return null;
  }
  final defaultValue = value is String ? value.trim() : value;
  if (defaultValue is String && defaultValue.isEmpty) {
    return null;
  }
  final pathSuffix = _jsonPathSuffix(path);
  if (pathSuffix.isEmpty) {
    return null;
  }
  final bindings = <String>[
    '\$.execution.steps[$stepIndex].args.$pathSuffix',
    if (bindAgentOriginalArgs)
      '\$.execution.steps[$stepIndex].agent_call.args.original_args.$pathSuffix',
  ];
  final baseName = _parameterBaseName(normalizedKey, toolName);
  return {
    'name': baseName,
    'type': value is num
        ? 'number'
        : value is bool
        ? 'boolean'
        : 'string',
    'description': _parameterDescription(
      normalizedKey,
      toolName,
      useEnglish: useEnglish,
    ),
    'default': defaultValue,
    'required': false,
    'bindings': bindings,
    'source_steps': [stepId],
  };
}

String _parameterSignature(Map<String, dynamic> parameter) {
  return const JsonEncoder().convert({
    'name': parameter['name'],
    'type': parameter['type'],
    'default': parameter['default'],
  });
}

void _appendUnique(Map<String, dynamic> target, String key, dynamic rawValues) {
  final current = target[key] is List ? List<dynamic>.from(target[key]) : [];
  final incoming = rawValues is Iterable ? rawValues : [rawValues];
  for (final value in incoming) {
    if (value == null) continue;
    if (!current.contains(value)) {
      current.add(value);
    }
  }
  target[key] = current;
}

bool _isParameterCandidate(String toolName, String key, dynamic value) {
  if (value is Map || value is Iterable) {
    return false;
  }
  final normalizedKey = key.toLowerCase();
  if (_isCoordinateLikeKey(normalizedKey)) {
    return false;
  }
  const candidateKeys = {
    'text',
    'content',
    'message',
    'prompt',
    'instruction',
    'question',
    'query',
    'keyword',
    'url',
    'target',
    'target_description',
    'targetdescription',
    'title',
    'name',
    'label',
    'value',
    'value_text',
    'valuetext',
    'package_name',
    'packagename',
    'app_name',
    'appname',
  };
  if (candidateKeys.contains(normalizedKey)) {
    return true;
  }
  final normalizedTool = toolName.toLowerCase();
  return normalizedTool.contains('input') ||
      normalizedTool.contains('type') ||
      normalizedTool.contains('search') ||
      normalizedTool.contains('open');
}

bool _isCoordinateLikeKey(String key) {
  const blocked = {
    'x',
    'y',
    'left',
    'top',
    'right',
    'bottom',
    'width',
    'height',
    'center_x',
    'centery',
    'center_y',
    'centerx',
    'bounds',
    'rect',
  };
  return blocked.contains(key);
}

String _jsonPathSuffix(List<String> segments) {
  if (segments.isEmpty) {
    return '';
  }
  final safeSegments = <String>[];
  for (final raw in segments) {
    final segment = raw.trim();
    if (!RegExp(r'^[A-Za-z0-9_]+(?:\[\d+\])?$').hasMatch(segment)) {
      return '';
    }
    safeSegments.add(segment);
  }
  return safeSegments.join('.');
}

String _canonicalToolNameForStep(String toolName, dynamic args) {
  if (RunLogReplayPolicy.isOmniflowFunctionTool(toolName) ||
      RunLogReplayPolicy.isOmniflowToolCallTool(toolName)) {
    return 'call_tool';
  }
  return toolName;
}

Map<String, dynamic> _canonicalCallToolArgs(String toolName, dynamic args) {
  final normalizedTool = RunLogReplayPolicy.normalizeToolName(toolName);
  if (!RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool) &&
      !RunLogReplayPolicy.isOmniflowToolCallTool(normalizedTool)) {
    final safe = _jsonSafe(args);
    return safe is Map<String, dynamic> ? safe : _asStringKeyMap(safe);
  }
  final mapped = Map<String, dynamic>.from(_asStringKeyMap(args));
  final functionId = _firstNonBlank([
    mapped['function_id'],
    mapped['functionId'],
    mapped['oob_function_id'],
    mapped['oobFunctionId'],
  ]);
  if (functionId.isNotEmpty) {
    mapped['function_id'] = functionId;
  }
  final targetTool = _firstNonBlank([
    mapped['tool_name'],
    mapped['toolName'],
    mapped['target_tool'],
    mapped['targetTool'],
    mapped['tool'],
  ]);
  if (targetTool.isNotEmpty &&
      !RunLogReplayPolicy.isOmniflowFunctionTool(normalizedTool)) {
    mapped['tool_name'] = targetTool;
  }
  return mapped;
}

String _callToolFunctionId(dynamic args) {
  final mapped = _asStringKeyMap(args);
  return _firstNonBlank([
    mapped['function_id'],
    mapped['functionId'],
    mapped['oob_function_id'],
    mapped['oobFunctionId'],
  ]);
}

String _stepKindForToolName(String toolName, String route, {dynamic args}) {
  if (RunLogReplayPolicy.isOmniflowToolCallTool(toolName)) {
    return _callToolFunctionId(args).isNotEmpty
        ? 'omniflow_function'
        : 'tool_call';
  }
  final executor = _executorForToolName(toolName, route);
  if (executor == 'agent') return 'agent_call';
  if (RunLogReplayPolicy.isOmniflowGraphTool(toolName)) {
    return 'omniflow_graph';
  }
  if (RunLogReplayPolicy.isOmniflowFunctionTool(toolName)) {
    return 'omniflow_function';
  }
  return executor == 'omniflow' ? 'omniflow_action' : 'tool_call';
}

String _executorForSnapshot(
  _RunLogActionSnapshot snapshot, {
  required dynamic args,
  required bool skipPerceptionStep,
}) {
  if (RunLogReplayPolicy.isPerceptionTool(snapshot.toolName) &&
      !skipPerceptionStep) {
    return 'agent';
  }
  if (_replayActionForSnapshot(snapshot) != null) {
    return 'omniflow';
  }
  if (RunLogReplayPolicy.isOmniflowToolCallTool(snapshot.toolName)) {
    return _callToolFunctionId(args).isNotEmpty ? 'omniflow' : 'tool';
  }
  return _executorForToolName(snapshot.toolName, snapshot.route);
}

String _executorForToolName(String toolName, String route) {
  final normalizedTool = RunLogReplayPolicy.normalizeToolName(toolName);
  final normalizedRoute = route.trim().toLowerCase();
  if (normalizedTool.isEmpty || normalizedTool == 'unknown_tool') {
    return 'agent';
  }
  if (RunLogReplayPolicy.omniflowActionForToolName(normalizedTool) != null) {
    return 'omniflow';
  }
  if (RunLogReplayPolicy.isOmniflowExecutionTool(normalizedTool)) {
    return 'omniflow';
  }
  if (normalizedRoute == 'miss' || normalizedRoute == 'vlm') {
    return 'agent';
  }
  if (normalizedTool.contains('agent') ||
      normalizedTool.contains('llm') ||
      normalizedTool.contains('vlm')) {
    return 'agent';
  }
  // Data-flow and perception tools require live context.
  if (RunLogReplayPolicy.isAgentTool(normalizedTool)) {
    return 'agent';
  }
  return 'tool';
}

String _stepFallbackPrompt({
  required String title,
  required String toolName,
  required dynamic args,
  required String prompt,
  required bool useEnglish,
}) {
  final argsText = const JsonEncoder.withIndent('  ').convert(_jsonSafe(args));
  final normalizedPrompt = prompt.trim();
  if (useEnglish) {
    return [
      'Re-plan this step from the current screen.',
      if (title.trim().isNotEmpty) 'Step goal: ${title.trim()}',
      if (normalizedPrompt.isNotEmpty) ...[
        'Original prompt:',
        normalizedPrompt,
      ],
      'Original tool: $toolName',
      'Original args:',
      argsText,
    ].join('\n');
  }
  return [
    '请从当前屏幕重新规划并执行这一步。',
    if (title.trim().isNotEmpty) '步骤目标：${title.trim()}',
    if (normalizedPrompt.isNotEmpty) ...['原始 prompt：', normalizedPrompt],
    '原始工具：$toolName',
    '原始参数：',
    argsText,
  ].join('\n');
}

String _stepSummary({
  required String title,
  required String toolName,
  required String prompt,
  required dynamic args,
  required dynamic result,
}) {
  final promptPreview = _compactPreview(prompt, maxLength: 180);
  if (promptPreview.isNotEmpty) {
    return 'Prompt: $promptPreview';
  }
  final argsMap = _asStringKeyMap(args);
  final resultMap = _asStringKeyMap(result);
  final preview = _compactPreview(
    _firstNonBlank([
      argsMap['target_description'],
      argsMap['targetDescription'],
      argsMap['text'],
      argsMap['content'],
      argsMap['message'],
      argsMap['query'],
      argsMap['url'],
      resultMap['summary'],
      resultMap['message'],
    ]),
    maxLength: 180,
  );
  if (preview.isNotEmpty) {
    return preview;
  }
  return _firstNonBlank([title, toolName]);
}

Map<String, dynamic>? _buildCoordinateHookMetadata({
  required _RunLogActionSnapshot snapshot,
  required dynamic args,
}) {
  final replayAction = _replayActionForSnapshot(snapshot);
  if (replayAction == null ||
      !RunLogReplayPolicy.isCoordinateAction(replayAction)) {
    return null;
  }
  // No source XML → no coordinate remapping possible; use original coordinates.
  if (snapshot.beforeXml.isEmpty) {
    return null;
  }
  final argsMap = _asStringKeyMap(args);
  final sourceAction = <String, dynamic>{
    'tool': replayAction,
    if (_firstNonBlank([
      argsMap['target_description'],
      argsMap['targetDescription'],
    ]).isNotEmpty)
      'target_description': _firstNonBlank([
        argsMap['target_description'],
        argsMap['targetDescription'],
      ]),
  };
  for (final key in const [
    'x',
    'y',
    'x1',
    'y1',
    'x2',
    'y2',
    'duration',
    'duration_ms',
    'durationMs',
  ]) {
    if (argsMap.containsKey(key) && argsMap[key] != null) {
      sourceAction[key] = argsMap[key];
    }
  }

  return {
    'coordinate_hook': 'omniflow',
    'coordinate_hook_policy': {
      'mode': 'coordinate_remap',
      'source_context_required': true,
    },
    'replay_policy': {
      'mode': 'coordinate_remap',
      'coordinate_transform': true,
      'source_context_required': true,
    },
    'source_context': {
      'src_ctx': {
        'page': snapshot.beforeXml,
        'require_unique_action_signature': false,
      },
      'action': sourceAction,
    },
  };
}

String? _replayActionForSnapshot(_RunLogActionSnapshot snapshot) {
  final direct = RunLogReplayPolicy.omniflowActionForToolName(
    snapshot.toolName,
  );
  if (direct != null) {
    return direct;
  }
  if (!_isAndroidPrivilegedAction(snapshot.toolName)) {
    return null;
  }
  final argsMap = _asStringKeyMap(snapshot.args);
  return RunLogReplayPolicy.omniflowActionForToolName(
    _firstNonBlank([argsMap['action'], argsMap['omniflow_action']]),
  );
}

bool _isDuplicateTextInputStep(
  Map<String, dynamic>? previous,
  Map<String, dynamic> current,
) {
  if (previous == null) return false;
  if (!_isTextInputStep(previous) || !_isTextInputStep(current)) return false;
  final previousText = _textInputValue(previous);
  final currentText = _textInputValue(current);
  if (previousText.isEmpty || previousText != currentText) return false;
  final previousTarget = _textInputTargetSignature(previous);
  final currentTarget = _textInputTargetSignature(current);
  return previousTarget.isNotEmpty && previousTarget == currentTarget;
}

bool _isTextInputStep(Map<String, dynamic> step) {
  final rawAction = _firstNonBlank([
    step['omniflow_action'],
    step['local_action'],
    step['tool'],
    step['callable_tool'],
  ]);
  final action =
      RunLogReplayPolicy.omniflowActionForToolName(rawAction) ??
      RunLogReplayPolicy.normalizeToolName(rawAction);
  return action == 'input_text' || action == 'type';
}

String _textInputValue(Map<String, dynamic> step) {
  final args = _asStringKeyMap(step['args']);
  return _firstNonBlank([args['text'], args['content'], args['value']]);
}

String _textInputTargetSignature(Map<String, dynamic> step) {
  final args = _asStringKeyMap(step['args']);
  final sourceContext = _asStringKeyMap(step['source_context']);
  final action = _asStringKeyMap(sourceContext['action']);
  return _firstNonBlank([
    args['node_resource_id'],
    action['node_resource_id'],
    args['selector'],
    action['selector'],
    args['bounds'],
    action['bounds'],
    args['target_description'],
    args['targetDescription'],
    action['target_description'],
    action['targetDescription'],
  ]);
}

dynamic _replayArgsForSnapshot(_RunLogActionSnapshot snapshot) {
  final rawArgs = _jsonSafe(snapshot.args);
  if (!_isAndroidPrivilegedAction(snapshot.toolName) ||
      _replayActionForSnapshot(snapshot) == null) {
    return rawArgs;
  }
  final argsMap = _asStringKeyMap(rawArgs);
  final nestedArguments = _asStringKeyMap(argsMap['arguments']);
  final flattened = <String, dynamic>{};
  for (final entry in argsMap.entries) {
    if (entry.key == 'action' ||
        entry.key == 'omniflow_action' ||
        entry.key == 'arguments') {
      continue;
    }
    flattened[entry.key] = entry.value;
  }
  flattened.addAll(nestedArguments);
  return flattened;
}

bool _isAndroidPrivilegedAction(String toolName) {
  return RunLogReplayPolicy.normalizeToolName(toolName) ==
      'android_privileged_action';
}

String _parameterBaseName(String key, String toolName) {
  final normalized = key
      .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]}_${m[2]}')
      .replaceAll(RegExp(r'[^A-Za-z0-9_]+'), '_')
      .toLowerCase()
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^_|_$'), '');
  if (normalized == 'target_description' || normalized == 'targetdescription') {
    return 'target';
  }
  if (normalized == 'package_name' || normalized == 'packagename') {
    return 'package_name';
  }
  if (normalized == 'app_name' || normalized == 'appname') {
    return 'app_name';
  }
  return normalized.isEmpty ? '${toolName}_value' : normalized;
}

String _parameterDescription(
  String key,
  String toolName, {
  required bool useEnglish,
}) {
  switch (key.toLowerCase()) {
    case 'text':
    case 'content':
    case 'message':
      return _text(useEnglish, '运行时输入的文本内容', 'Text entered at runtime');
    case 'query':
    case 'keyword':
      return _text(
        useEnglish,
        '运行时搜索或匹配的查询词',
        'Search or matching query at runtime',
      );
    case 'url':
      return _text(useEnglish, '运行时打开或访问的 URL', 'URL opened at runtime');
    case 'package_name':
    case 'packagename':
      return _text(
        useEnglish,
        '目标 Android 应用包名',
        'Target Android package name',
      );
    case 'target_description':
    case 'targetdescription':
    case 'target':
      return _text(
        useEnglish,
        '目标控件或目标对象描述',
        'Target control or object description',
      );
    default:
      return useEnglish ? '$key parameter for $toolName' : '$toolName 的参数 $key';
  }
}

String _text(bool useEnglish, String zh, String en) {
  return useEnglish ? en : zh;
}

Map<String, dynamic> _extractToolCall(Map<String, dynamic> card) {
  final explicit = _firstMap(card, const [
    'tool_call',
    'toolCall',
    'action',
    'call',
  ]);
  if (explicit.isNotEmpty) {
    return explicit;
  }
  final toolName = _firstNonBlank([
    card['tool_name'],
    card['toolName'],
    card['action_type'],
    card['actionType'],
  ]);
  if (toolName.isEmpty) {
    return const {};
  }
  return <String, dynamic>{
    'name': toolName,
    if (card.containsKey('params')) 'params': card['params'],
    if (card.containsKey('arguments')) 'arguments': card['arguments'],
  };
}

class _PromptHit {
  const _PromptHit(this.text, this.source);

  final String text;
  final String source;
}

class _PromptSearchRoot {
  const _PromptSearchRoot(this.name, this.value);

  final String name;
  final dynamic value;
}

const Set<String> _promptKeyNames = {
  'prompt',
  'subagentprompt',
  'agentprompt',
  'augmentprompt',
  'augumentprompt',
  'systemprompt',
  'userprompt',
  'instruction',
  'instructions',
  'query',
  'question',
  'message',
  'usermessage',
  'input',
  'task',
  'goal',
  'request',
};

_PromptHit _extractPromptHit(List<_PromptSearchRoot> roots) {
  for (final root in roots) {
    final hit = _findPromptInValue(
      root.value,
      path: root.name,
      visited: <Object>{},
    );
    if (hit.text.trim().isNotEmpty) {
      return hit;
    }
  }
  return const _PromptHit('', '');
}

_PromptHit _findPromptInValue(
  dynamic raw, {
  required String path,
  required Set<Object> visited,
}) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is Map) {
    if (!visited.add(value)) {
      return const _PromptHit('', '');
    }
    final map = value.map((key, item) => MapEntry(key.toString(), item));
    for (final entry in map.entries) {
      final key = entry.key.trim();
      final normalizedKey = _normalizePromptKey(key);
      if (_promptKeyNames.contains(normalizedKey)) {
        final text = _promptTextFromValue(entry.value);
        if (text.isNotEmpty) {
          return _PromptHit(text, '$path.$key');
        }
      }
    }
    for (final entry in map.entries) {
      final hit = _findPromptInValue(
        entry.value,
        path: '$path.${entry.key}',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
    }
  } else if (value is Iterable) {
    var index = 0;
    for (final item in value) {
      final hit = _findPromptInValue(
        item,
        path: '$path[$index]',
        visited: visited,
      );
      if (hit.text.isNotEmpty) {
        return hit;
      }
      index++;
    }
  }
  return const _PromptHit('', '');
}

String _promptTextFromValue(dynamic raw) {
  final value = _decodeJsonIfNeeded(raw);
  if (value is String) {
    return value.trim();
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  if (value is Map) {
    return _firstNonBlank([
      value['text'],
      value['content'],
      value['message'],
      value['prompt'],
      value['value'],
    ]);
  }
  return '';
}

String _normalizePromptKey(String key) {
  return key.replaceAll(RegExp(r'[^A-Za-z0-9]+'), '').toLowerCase().trim();
}

dynamic _extractArgs(
  Map<String, dynamic> card,
  Map<String, dynamic> toolCall,
  Map<String, dynamic> function,
) {
  final value = _firstPresent([
    toolCall['params'],
    toolCall['arguments'],
    toolCall['args'],
    function['arguments'],
    card['params'],
    card['arguments'],
    card['args'],
  ]);
  return _decodeJsonIfNeeded(value) ?? const <String, dynamic>{};
}

dynamic _extractResult(Map<String, dynamic> card) {
  final value = _firstPresent([
    card['result'],
    card['tool_result'],
    card['toolResult'],
    card['execution_result'],
    card['executionResult'],
    card['raw_result_json'],
    card['rawResultJson'],
    card['resultPreviewJson'],
    card['output'],
    card['error'],
    card['error_message'],
    card['errorMessage'],
  ]);
  return _decodeJsonIfNeeded(value);
}

const int _observedResultStringLimit = 2000;
const int _observedResultListLimit = 20;
const int _observedResultMapLimit = 40;
const int _observedResultMaxDepth = 4;

dynamic _compactObservedResult(dynamic value) {
  return _compactObservedJson(_jsonSafe(value), depth: 0);
}

dynamic _compactObservedJson(dynamic value, {required int depth}) {
  if (value == null || value is num || value is bool) {
    return value;
  }
  if (value is String) {
    return _compactObservedString(value);
  }
  if (depth >= _observedResultMaxDepth) {
    return const <String, dynamic>{
      '__truncated__': true,
      'reason': 'max_depth',
    };
  }
  if (value is Map) {
    final result = <String, dynamic>{};
    var count = 0;
    var omittedCount = 0;
    for (final entry in value.entries) {
      if (count < _observedResultMapLimit) {
        result[entry.key.toString()] = _compactObservedJson(
          entry.value,
          depth: depth + 1,
        );
      } else {
        omittedCount++;
      }
      count++;
    }
    if (omittedCount > 0) {
      result['__truncated__'] = true;
      result['__omitted_entry_count__'] = omittedCount;
    }
    return result;
  }
  if (value is Iterable) {
    final result = <dynamic>[];
    var count = 0;
    var omittedCount = 0;
    for (final item in value) {
      if (count < _observedResultListLimit) {
        result.add(_compactObservedJson(item, depth: depth + 1));
      } else {
        omittedCount++;
      }
      count++;
    }
    if (omittedCount > 0) {
      result.add({
        '__truncated__': true,
        '__omitted_item_count__': omittedCount,
      });
    }
    return result;
  }
  return _compactObservedString(value.toString());
}

String _compactObservedString(String value) {
  if (value.length <= _observedResultStringLimit) {
    return value;
  }
  final head = value.substring(0, _observedResultStringLimit).trimRight();
  return '$head... [truncated, original_length=${value.length}]';
}

Map<String, dynamic>? _extractJsonObject(String raw) {
  final text = raw.trim();
  if (text.isEmpty) {
    return null;
  }
  final candidates = <String>[text];
  for (final match in RegExp(
    r'```(?:json)?\s*([\s\S]*?)```',
    caseSensitive: false,
  ).allMatches(text)) {
    final fenced = match.group(1)?.trim();
    if (fenced != null && fenced.isNotEmpty) {
      candidates.add(fenced);
    }
  }

  for (final candidate in candidates) {
    final direct = _tryDecodeMap(candidate);
    if (direct != null) {
      return direct;
    }

    Map<String, dynamic>? best;
    var bestScore = -1;
    for (final objectText in _balancedJsonObjectCandidates(candidate)) {
      final parsed = _tryDecodeMap(objectText);
      if (parsed == null) {
        continue;
      }
      final score = _jsonObjectCandidateScore(parsed);
      if (score >= bestScore) {
        best = parsed;
        bestScore = score;
      }
    }
    if (best != null) {
      return best;
    }
  }
  return null;
}

Map<String, dynamic>? _tryDecodeMap(String value) {
  try {
    final decoded = jsonDecode(value);
    if (decoded is Map) {
      return _unwrapJsonObject(
        decoded.map((key, item) => MapEntry(key.toString(), item)),
      );
    }
    if (decoded is String && decoded.trim() != value.trim()) {
      return _extractJsonObject(decoded);
    }
    if (decoded is List) {
      for (final item in decoded) {
        if (item is Map) {
          final unwrapped = _unwrapJsonObject(
            item.map((key, value) => MapEntry(key.toString(), value)),
          );
          if (unwrapped != null) {
            return unwrapped;
          }
        }
      }
    }
  } catch (_) {
    return null;
  }
  return null;
}

Iterable<String> _balancedJsonObjectCandidates(String value) sync* {
  var depth = 0;
  var start = -1;
  var inString = false;
  var escaped = false;
  for (var index = 0; index < value.length; index++) {
    final char = value[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char == '\\') {
        escaped = true;
      } else if (char == '"') {
        inString = false;
      }
      continue;
    }
    if (char == '"') {
      inString = true;
      continue;
    }
    if (char == '{') {
      if (depth == 0) {
        start = index;
      }
      depth++;
      continue;
    }
    if (char == '}' && depth > 0) {
      depth--;
      if (depth == 0 && start >= 0) {
        yield value.substring(start, index + 1);
        start = -1;
      }
    }
  }
}

int _jsonObjectCandidateScore(Map<String, dynamic> value) {
  var score = 0;
  for (final key in const [
    'schema_version',
    'function_id',
    'execution',
    'agent_reuse',
  ]) {
    if (value.containsKey(key)) {
      score += 12;
    }
  }
  if (_firstNonBlank([value['name'], value['title']]).isNotEmpty) {
    score +=
        _looksLikePlaceholderJsonText(
          _firstNonBlank([value['name'], value['title']]),
        )
        ? 1
        : 8;
  }
  if (_firstNonBlank([value['description'], value['summary']]).isNotEmpty) {
    score +=
        _looksLikePlaceholderJsonText(
          _firstNonBlank([value['description'], value['summary']]),
        )
        ? 1
        : 8;
  }
  final steps = value['steps'];
  if (steps is List && steps.isNotEmpty) {
    score += 6;
    for (final item in steps.take(4)) {
      final step = _asStringKeyMap(item);
      final title = _firstNonBlank([
        step['title'],
        step['description'],
        step['summary'],
      ]);
      if (title.isNotEmpty && !_looksLikePlaceholderJsonText(title)) {
        score += 2;
      }
    }
  }
  final parameters = value['parameters'];
  if (parameters is List && parameters.isNotEmpty) {
    score += 4;
  }
  return score;
}

bool _looksLikePlaceholderJsonText(String value) {
  final normalized = value.trim().toLowerCase();
  if (normalized.isEmpty) {
    return true;
  }
  return const {
    'short reusable command name',
    'one sentence',
    'runtime_slot',
    'runtime value',
    'recorded value',
    'short action title',
    'what this action does',
    '简短复用指令名称',
    '简短的复用指令名称',
    '一句话简介',
    '运行时值',
    '记录值',
    '简短动作标题',
    '这个动作做了什么',
  }.contains(normalized);
}

Map<String, dynamic>? _unwrapJsonObject(Map<String, dynamic> decoded) {
  if (_looksLikeEnhancementOrFunctionJson(decoded)) {
    return decoded;
  }
  for (final key in const [
    'enhancement',
    'function',
    'function_json',
    'functionJson',
    'json',
    'data',
    'result',
    'response',
    'parsed',
    'value',
    'object',
    'output',
    'output_text',
    'text',
    'input',
    'arguments',
    'arguments_json',
    'args_json',
    'message',
    'content',
  ]) {
    final nested = _extractJsonObjectFromValue(decoded[key]);
    if (nested != null) {
      return nested;
    }
  }
  final choices = decoded['choices'];
  if (choices is List) {
    for (final rawChoice in choices) {
      final choice = _asStringKeyMap(rawChoice);
      for (final rawRoot in [choice['message'], choice['delta'], choice]) {
        final root = _asStringKeyMap(rawRoot);
        final nestedRoot = _unwrapJsonObject(root);
        if (nestedRoot != null) {
          return nestedRoot;
        }
        final content = _extractJsonObjectFromValue(root['content']);
        if (content != null) {
          return content;
        }
        final toolCalls = root['tool_calls'] ?? root['toolCalls'];
        final toolJson = _extractJsonObjectFromToolCalls(toolCalls);
        if (toolJson != null) {
          return toolJson;
        }
      }
    }
  }
  return null;
}

bool _looksLikeEnhancementOrFunctionJson(Map<String, dynamic> value) {
  for (final key in const [
    'schema_version',
    'function_id',
    'name',
    'title',
    'description',
    'summary',
    'parameters',
    'steps',
    'actions',
    'agent_reuse',
    'execution',
  ]) {
    if (value.containsKey(key)) {
      return true;
    }
  }
  return false;
}

Map<String, dynamic>? _extractJsonObjectFromValue(dynamic value) {
  if (value is String) {
    return _extractJsonObject(value);
  }
  if (value is Map) {
    return _unwrapJsonObject(
      value.map((key, item) => MapEntry(key.toString(), item)),
    );
  }
  if (value is List) {
    for (final item in value) {
      final nested = _extractJsonObjectFromValue(item);
      if (nested != null) {
        return nested;
      }
      final itemMap = _asStringKeyMap(item);
      for (final key in const [
        'text',
        'content',
        'parsed',
        'value',
        'object',
        'input',
        'arguments',
        'arguments_json',
        'args_json',
        'output_text',
      ]) {
        final blockJson = _extractJsonObjectFromValue(itemMap[key]);
        if (blockJson != null) {
          return blockJson;
        }
      }
    }
  }
  return null;
}

Map<String, dynamic>? _extractJsonObjectFromToolCalls(dynamic toolCalls) {
  if (toolCalls is! List) {
    return null;
  }
  for (final rawCall in toolCalls) {
    final call = _asStringKeyMap(rawCall);
    final function = _asStringKeyMap(call['function']);
    for (final value in [
      function['arguments'],
      function['arguments_json'],
      call['arguments'],
      call['args'],
    ]) {
      final parsed = _extractJsonObjectFromValue(value);
      if (parsed != null) {
        return parsed;
      }
    }
  }
  return null;
}

String _stateSummary(Map<String, dynamic> state) {
  return _firstNonBlank([
    state['page_title'],
    state['pageTitle'],
    state['activity'],
    state['package_name'],
    state['packageName'],
    state['description'],
    state['summary'],
  ]);
}

Map<String, dynamic> _mergeMaps(
  Map<String, dynamic> base,
  Map<String, dynamic> override,
) {
  return <String, dynamic>{...base, ...override};
}

List<dynamic> _normalizeParameters(dynamic value, dynamic fallback) {
  final candidates = value is List && value.isNotEmpty ? value : fallback;
  if (candidates is! List) {
    return const [];
  }
  return candidates
      .map((item) => _asStringKeyMap(item))
      .where((item) => item.isNotEmpty)
      .map((item) {
        final bindings = item['bindings'];
        return {
          'name': _firstNonBlank([item['name']]),
          'type': _firstNonBlank([item['type'], 'string']),
          'description': _firstNonBlank([item['description'], item['name']]),
          if (item.containsKey('default')) 'default': item['default'],
          'required': item['required'] == true,
          'bindings': bindings is List
              ? bindings.map((entry) => entry.toString()).toList()
              : const <String>[],
          if (item['source_steps'] is List)
            'source_steps': item['source_steps'],
        };
      })
      .where((item) => item['name'].toString().trim().isNotEmpty)
      .toList(growable: false);
}

List<dynamic> _normalizeExecutionSteps(dynamic value, dynamic fallback) {
  final fallbackSteps = fallback is List ? fallback : const <dynamic>[];
  final candidates = value is List && value.isNotEmpty ? value : fallbackSteps;
  return List<dynamic>.generate(candidates.length, (index) {
    final step = _asStringKeyMap(candidates[index]);
    final fallbackStep = index < fallbackSteps.length
        ? _asStringKeyMap(fallbackSteps[index])
        : const <String, dynamic>{};
    final merged = <String, dynamic>{...fallbackStep, ...step};
    final toolName = _firstNonBlank([
      merged['tool'],
      fallbackStep['tool'],
      'unknown_tool',
    ]);
    final route = _firstNonBlank([merged['route'], fallbackStep['route']]);
    final normalizedArgs = _canonicalCallToolArgs(
      toolName,
      merged['args'] ?? fallbackStep['args'],
    );
    final canonicalToolName = _canonicalToolNameForStep(
      toolName,
      normalizedArgs,
    );
    final isCallTool =
        RunLogReplayPolicy.isOmniflowToolCallTool(toolName) ||
        RunLogReplayPolicy.isOmniflowToolCallTool(canonicalToolName);
    final inferredExecutor = isCallTool
        ? (_callToolFunctionId(normalizedArgs).isNotEmpty ? 'omniflow' : 'tool')
        : _executorForToolName(toolName, route);
    final executor = switch (inferredExecutor) {
      'omniflow' => 'omniflow',
      'agent' => 'agent',
      _ => _firstNonBlank([
        merged['executor'],
        fallbackStep['executor'],
        inferredExecutor,
      ]),
    };
    final rawReplayAction = _firstNonBlank([
      if (executor == 'omniflow') merged['omniflow_action'],
      if (executor == 'omniflow') fallbackStep['omniflow_action'],
      if (executor == 'omniflow')
        RunLogReplayPolicy.omniflowActionForToolName(toolName),
    ]);
    final replayAction = executor == 'omniflow'
        ? (RunLogReplayPolicy.omniflowActionForToolName(rawReplayAction) ??
              RunLogReplayPolicy.omniflowActionForToolName(toolName) ??
              rawReplayAction)
        : '';
    final emittedToolName = executor == 'omniflow' && replayAction.isNotEmpty
        ? replayAction
        : canonicalToolName;
    final modelFree =
        executor == 'omniflow' ||
        (executor != 'agent' &&
            (_asBool(merged['model_free']) == true ||
                _asBool(fallbackStep['model_free']) == true));
    final rawScriptable = merged['scriptable'] is bool
        ? merged['scriptable']
        : fallbackStep['scriptable'];
    final scriptable = executor == 'agent'
        ? false
        : rawScriptable is bool
        ? rawScriptable
        : true;
    final callableTool = executor == 'agent'
        ? 'oob.agent.run'
        : executor == 'omniflow' && replayAction.isNotEmpty
        ? replayAction
        : canonicalToolName;
    final fallback = _mergeMaps(
      _asStringKeyMap(fallbackStep['fallback']),
      _asStringKeyMap(merged['fallback']),
    );
    final agentCall = _normalizeAgentCall(
      raw: _mergeMaps(
        _asStringKeyMap(fallbackStep['agent_call']),
        _asStringKeyMap(merged['agent_call']),
      ),
      enabled: executor == 'agent',
      fallback: fallback,
      originalTool: toolName,
      originalArgs: _jsonSafe(merged['args'] ?? fallbackStep['args']),
      originalPrompt: _extractStepPromptText(merged, fallbackStep),
      reason: _firstNonBlank([
        _asStringKeyMap(merged['agent_call'])['reason'],
        _asStringKeyMap(fallbackStep['agent_call'])['reason'],
        RunLogReplayPolicy.agentStepReason(toolName),
      ]),
    );
    final prompt = _extractStepPromptText(merged, fallbackStep);
    return {
      ..._stepBaseWithoutDerivedFields(merged),
      'id': _firstNonBlank([
        merged['id'],
        fallbackStep['id'],
        'step_${index + 1}',
      ]),
      'index': _asInt(merged['index']) ?? index,
      'kind': _stepKindForToolName(
        emittedToolName,
        route,
        args: normalizedArgs,
      ),
      'tool': emittedToolName,
      'callable_tool': callableTool,
      if (emittedToolName != toolName) 'source_tool': toolName,
      'executor': executor,
      'scriptable': scriptable,
      if (modelFree) 'model_free': true,
      if (replayAction.isNotEmpty) 'omniflow_action': replayAction,
      'args': _jsonSafe(normalizedArgs),
      'tool_binding': _mergeMaps(
        _mergeMaps(
          _asStringKeyMap(fallbackStep['tool_binding']),
          _asStringKeyMap(merged['tool_binding']),
        ),
        {
          'kind': executor == 'agent'
              ? 'agent_replan'
              : RunLogReplayPolicy.isOmniflowGraphTool(emittedToolName)
              ? 'omniflow_graph'
              : RunLogReplayPolicy.isOmniflowFunctionTool(emittedToolName) ||
                    _callToolFunctionId(normalizedArgs).isNotEmpty
              ? 'omniflow_function'
              : executor == 'omniflow'
              ? 'omniflow_action'
              : 'oob_agent_tool',
          'name': emittedToolName,
          if (executor == 'agent') 'callable_tool': 'oob.agent.run',
        },
      ),
      if (prompt.isNotEmpty)
        'prompt': {
          ..._asStringKeyMap(fallbackStep['prompt']),
          ..._asStringKeyMap(merged['prompt']),
          'text': prompt,
          'preview': _compactPreview(prompt, maxLength: 240),
        },
      if (executor == 'omniflow' &&
          _firstNonBlank([
            merged['coordinate_hook'],
            fallbackStep['coordinate_hook'],
          ]).isNotEmpty)
        'coordinate_hook': _firstNonBlank([
          merged['coordinate_hook'],
          fallbackStep['coordinate_hook'],
        ]),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['coordinate_hook_policy']).isNotEmpty ||
              _asStringKeyMap(
                fallbackStep['coordinate_hook_policy'],
              ).isNotEmpty))
        'coordinate_hook_policy': _mergeMaps(
          _asStringKeyMap(fallbackStep['coordinate_hook_policy']),
          _asStringKeyMap(merged['coordinate_hook_policy']),
        ),
      if (executor == 'omniflow' &&
          _firstNonBlank([
            merged['replay_engine'],
            fallbackStep['replay_engine'],
          ]).isNotEmpty)
        'replay_engine': _firstNonBlank([
          merged['replay_engine'],
          fallbackStep['replay_engine'],
        ]),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['replay_policy']).isNotEmpty ||
              _asStringKeyMap(fallbackStep['replay_policy']).isNotEmpty))
        'replay_policy': _mergeMaps(
          _asStringKeyMap(fallbackStep['replay_policy']),
          _asStringKeyMap(merged['replay_policy']),
        ),
      if (executor == 'omniflow' &&
          (_asStringKeyMap(merged['source_context']).isNotEmpty ||
              _asStringKeyMap(fallbackStep['source_context']).isNotEmpty))
        'source_context': _mergeMaps(
          _asStringKeyMap(fallbackStep['source_context']),
          _asStringKeyMap(merged['source_context']),
        ),
      'validation': _mergeMaps(
        _asStringKeyMap(fallbackStep['validation']),
        _asStringKeyMap(merged['validation']),
      ),
      'fallback': fallback,
      if (agentCall.isNotEmpty) 'agent_call': agentCall,
      'reuse_policy': _mergeMaps(
        _asStringKeyMap(fallbackStep['reuse_policy']),
        _asStringKeyMap(merged['reuse_policy']),
      ),
    };
  }, growable: false);
}

Map<String, dynamic> _stepBaseWithoutDerivedFields(Map<String, dynamic> step) {
  final copy = Map<String, dynamic>.from(step);
  for (final key in const [
    'kind',
    'callable_tool',
    'executor',
    'scriptable',
    'model_free',
    'modelFree',
    'omniflow_action',
    'local_action',
    'tool_binding',
    'agent_call',
    'coordinate_hook',
    'coordinate_hook_policy',
    'replay_engine',
    'replay_policy',
    'source_context',
    'validation',
    'fallback',
    'reuse_policy',
  ]) {
    copy.remove(key);
  }
  return copy;
}

Map<String, dynamic> _executionCapabilitiesForSteps(
  List<dynamic> steps, {
  required Map<String, dynamic> fallback,
}) {
  final stepMaps = steps.map(_asStringKeyMap).toList(growable: false);
  return {
    ...fallback,
    'scriptable_step_count': stepMaps
        .where((step) => step['scriptable'] == true)
        .length,
    'model_free_step_count': stepMaps
        .where((step) => step['model_free'] == true)
        .length,
    'omniflow_step_count': stepMaps
        .where((step) => step['executor'] == 'omniflow')
        .length,
    'agent_step_count': stepMaps
        .where((step) => step['executor'] == 'agent')
        .length,
    'requires_agent_fallback': stepMaps.any(
      (step) => step['executor'] == 'agent',
    ),
  };
}

Map<String, dynamic> _normalizeAgentCall({
  required Map<String, dynamic> raw,
  required bool enabled,
  required Map<String, dynamic> fallback,
  required String originalTool,
  required dynamic originalArgs,
  required String originalPrompt,
  required String reason,
}) {
  if (!enabled && raw.isEmpty) {
    return const <String, dynamic>{};
  }
  final rawArgs = _asStringKeyMap(raw['args']);
  final prompt = _firstNonBlank([
    rawArgs['prompt'],
    raw['prompt'],
    fallback['prompt'],
  ]);
  return {
    ...raw,
    'tool': enabled
        ? 'oob.agent.run'
        : _firstNonBlank([raw['tool'], 'oob.agent.run']),
    'args': {
      ...rawArgs,
      if (prompt.isNotEmpty) 'prompt': prompt,
      'original_tool': _firstNonBlank([rawArgs['original_tool'], originalTool]),
      if (!_isEmptyJsonValue(originalArgs))
        'original_args': rawArgs['original_args'] ?? originalArgs,
      if (originalPrompt.trim().isNotEmpty)
        'original_prompt': _firstNonBlank([
          rawArgs['original_prompt'],
          originalPrompt,
        ]),
    },
    'reason': _firstNonBlank([
      raw['reason'],
      reason,
      enabled ? 'non_scriptable_or_vlm_step' : 'agent_fallback',
    ]),
  };
}

String _extractStepPromptText(
  Map<String, dynamic> step,
  Map<String, dynamic> fallbackStep,
) {
  return _firstNonBlank([
    _asStringKeyMap(step['prompt'])['text'],
    step['prompt'],
    _asStringKeyMap(fallbackStep['prompt'])['text'],
    fallbackStep['prompt'],
    _asStringKeyMap(
      _asStringKeyMap(step['agent_call'])['args'],
    )['original_prompt'],
    _asStringKeyMap(
      _asStringKeyMap(fallbackStep['agent_call'])['args'],
    )['original_prompt'],
  ]);
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded is! Map) {
    return const <String, dynamic>{};
  }
  return decoded.map((key, item) => MapEntry(key.toString(), item));
}

Map<String, dynamic> _firstMap(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final map = _asStringKeyMap(source[key]);
    if (map.isNotEmpty) {
      return map;
    }
  }
  return const <String, dynamic>{};
}

dynamic _firstPresentValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    if (source.containsKey(key) && source[key] != null) {
      return source[key];
    }
  }
  return null;
}

dynamic _firstPresent(List<dynamic> values) {
  for (final value in values) {
    if (value == null) {
      continue;
    }
    if (value is String && value.trim().isEmpty) {
      continue;
    }
    return value;
  }
  return null;
}

String _firstNonBlank(List<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

String _compactPreview(String value, {int maxLength = 160}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  if (maxLength <= 1) {
    return normalized.substring(0, maxLength);
  }
  return '${normalized.substring(0, maxLength - 1).trimRight()}…';
}

bool? _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') {
    return true;
  }
  if (text == 'false') {
    return false;
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString().trim() ?? '');
}

dynamic _decodeJsonIfNeeded(dynamic value) {
  if (value is! String) {
    return value;
  }
  final trimmed = value.trim();
  if (trimmed.isEmpty) {
    return value;
  }
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return value;
  }
  try {
    return jsonDecode(trimmed);
  } catch (_) {
    return value;
  }
}

bool _isEmptyJsonValue(dynamic value) {
  if (value == null) {
    return true;
  }
  if (value is String) {
    return value.trim().isEmpty;
  }
  if (value is Map || value is Iterable) {
    return value.isEmpty;
  }
  return false;
}

dynamic _jsonSafe(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded == null ||
      decoded is String ||
      decoded is num ||
      decoded is bool) {
    return decoded;
  }
  if (decoded is Map) {
    return decoded.map(
      (key, item) => MapEntry(key.toString(), _jsonSafe(item)),
    );
  }
  if (decoded is Iterable) {
    return decoded.map(_jsonSafe).toList(growable: false);
  }
  return decoded.toString();
}

Map<String, dynamic> _jsonSafeMap(Map<String, dynamic> value) {
  final safe = _jsonSafe(value);
  return safe is Map<String, dynamic> ? safe : _asStringKeyMap(safe);
}

String _normalizeFunctionName(String value, {required String fallback}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.isEmpty) {
    return fallback;
  }
  return normalized.length <= 60 ? normalized : normalized.substring(0, 60);
}

String _normalizeFunctionId(String value, {required String fallback}) {
  final trimmed = value.trim();
  if (RegExp(r'^[A-Za-z0-9_-]{1,64}$').hasMatch(trimmed)) {
    return trimmed;
  }
  final normalized = trimmed
      .replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '_')
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^[_-]+|[_-]+$'), '');
  if (normalized.isEmpty) {
    return fallback.trim().isNotEmpty ? fallback.trim() : 'oob_function';
  }
  final safe = RegExp(r'^[A-Za-z]').hasMatch(normalized)
      ? normalized
      : 'oob_$normalized';
  return safe.length <= 64 ? safe : safe.substring(0, 64);
}

String _compactId(String value) {
  final normalized = value
      .trim()
      .replaceAll(RegExp(r'[^A-Za-z0-9_]+'), '_')
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^_|_$'), '');
  if (normalized.isEmpty) {
    return DateTime.now().millisecondsSinceEpoch.toString();
  }
  return normalized.length <= 48 ? normalized : normalized.substring(0, 48);
}
