import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';

void main() {
  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
  });

  test('TerminalOutputUtils builds readable output from result json', () {
    final output = TerminalOutputUtils.buildDisplayOutput(
      terminalOutput: '',
      rawResultJson: jsonEncode({
        'liveFallbackReason': '共享存储未就绪',
        'stdout': 'hello',
        'stderr': 'warning',
      }),
      resultPreviewJson: '',
    );

    expect(output, contains('hello'));
    expect(output, contains('[stderr]'));
    expect(output, contains('warning'));
  });

  test('AnsiTextSpanBuilder applies color and bold to sgr spans', () {
    const baseStyle = TextStyle(fontSize: 12, color: Colors.white);
    final span = AnsiTextSpanBuilder.build(
      '\u001B[31;1merror\u001B[0m',
      baseStyle,
    );

    final children = span.children!;
    final styledChild = children.first as TextSpan;
    expect(styledChild.text, 'error');
    expect(styledChild.style?.fontWeight, FontWeight.w700);
    expect(styledChild.style?.color, const Color(0xFFE06C75));
  });

  testWidgets('tool card prefers toolTitle when rendering compact chip', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'displayName': '终端执行',
              'toolTitle': '检查仓库状态',
              'toolType': 'terminal',
              'summary': '终端命令执行成功',
              'argsJson': jsonEncode({
                'command': 'ls -la',
                'executionMode': 'termux',
                'timeoutSeconds': 60,
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('检查仓库状态'), findsOneWidget);
    expect(find.text('终端执行'), findsNothing);
  });

  testWidgets('tool card opens detail sheet when tapped', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: AgentToolSummaryCard(
              cardData: {
                'status': 'success',
                'displayName': '终端执行',
                'toolTitle': '检查仓库状态',
                'toolType': 'terminal',
                'summary': '终端命令执行成功',
                'argsJson': jsonEncode({
                  'command': 'git status',
                  'workingDirectory': '/workspace',
                }),
                'terminalOutput': 'On branch main',
              },
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('检查仓库状态'));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);
    expect(sheet, findsOneWidget);
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('git status', findRichText: true),
      ),
      findsOneWidget,
    );

    await tester.tapAt(const Offset(12, 12));
    await tester.pumpAndSettle();

    expect(sheet, findsNothing);
  });

  testWidgets(
    'interrupted status shows stopped state without loading spinner',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AgentToolSummaryCard(
              cardData: {
                'status': 'interrupted',
                'displayName': 'tool',
                'toolType': 'builtin',
                'summary': 'stopped',
              },
            ),
          ),
        ),
      );

      expect(find.text('\u4E2D\u65AD'), findsOneWidget);
      expect(find.byIcon(Icons.stop_circle_outlined), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    },
  );

  testWidgets('timeout status shows dedicated timeout badge and icon', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'timeout',
              'displayName': '终端执行',
              'toolType': 'terminal',
              'summary': '终端命令等待超时',
            },
          ),
        ),
      ),
    );

    expect(find.text('超时'), findsOneWidget);
    expect(find.byIcon(Icons.hourglass_top_rounded), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('tool card falls back to args tool_title when field missing', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'displayName': '读取文件',
              'toolType': 'workspace',
              'summary': '已读取文件',
              'argsJson': jsonEncode({
                'tool_title': '查看配置',
                'path': 'README.md',
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('查看配置'), findsOneWidget);
    expect(find.text('工作区'), findsOneWidget);
  });

  testWidgets('tool card title follows appearance text color', (tester) async {
    const customTextColor = Color(0xFFEEE6D7);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'toolTitle': '同步索引',
              'toolType': 'workspace',
              'summary': '已完成同步',
            },
            visualProfile: const AppBackgroundVisualProfile(
              sampledImageLuminance: 0.12,
              effectiveLuminance: 0.24,
              textTone: AppBackgroundTextTone.light,
              customPrimaryTextColor: customTextColor,
            ),
          ),
        ),
      ),
    );

    final title = tester.widget<Text>(find.text('同步索引'));
    expect(title.style?.color, customTextColor);
    expect(title.style?.fontSize, 12);
  });

  testWidgets('subagent card shows status line and expands timeline', (
    tester,
  ) async {
    const thinkingLine = 'SubAgent #1 思考：检查数据来源';
    const firstStatusLine = 'SubAgent #1 调用工具：file_search';
    const resultLine = 'SubAgent #1 得到结果：完成摘要';
    const secondStatusLine = 'SubAgent #2 思考：整理最终摘要';

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'displayName': '分派子任务',
              'toolName': 'subagent_dispatch',
              'toolType': 'subagent',
              'subagentStatusText': firstStatusLine,
              'subagentEvents': [
                {
                  'id': 'subagent-event-1',
                  'seq': 1,
                  'createdAt': 10,
                  'kind': 'thinking',
                  'summary': thinkingLine,
                  'status': 'running',
                  'taskIndex': 0,
                },
                {
                  'id': 'subagent-event-2',
                  'seq': 2,
                  'createdAt': 20,
                  'kind': 'tool_started',
                  'summary': firstStatusLine,
                  'status': 'running',
                  'taskIndex': 0,
                  'toolName': 'file_search',
                },
                {
                  'id': 'subagent-event-3',
                  'seq': 3,
                  'createdAt': 30,
                  'kind': 'subagent_completed',
                  'summary': resultLine,
                  'status': 'completed',
                  'taskIndex': 0,
                },
                {
                  'id': 'subagent-event-4',
                  'seq': 4,
                  'createdAt': 40,
                  'kind': 'thinking',
                  'summary': secondStatusLine,
                  'status': 'running',
                  'taskIndex': 1,
                },
              ],
            },
          ),
        ),
      ),
    );

    expect(find.text('分派子任务'), findsOneWidget);
    expect(find.text(resultLine), findsOneWidget);
    expect(find.text(secondStatusLine), findsOneWidget);
    expect(find.text(thinkingLine), findsNothing);
    expect(find.text(firstStatusLine), findsNothing);

    await tester.tap(find.text(resultLine));
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('检查数据来源'), findsOneWidget);
    expect(find.text('file_search'), findsOneWidget);
    expect(find.text('完成摘要'), findsOneWidget);
    expect(find.text(thinkingLine), findsNothing);
    expect(find.text(firstStatusLine), findsNothing);
    expect(find.text(resultLine), findsOneWidget);
    expect(find.byIcon(Icons.psychology_alt_rounded), findsOneWidget);
    expect(find.byIcon(Icons.build_circle_outlined), findsOneWidget);
  });
}
