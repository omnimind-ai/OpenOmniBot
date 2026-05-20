import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';

void main() {
  setUp(() {
    AppTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    AppTextLocalizer.clearResolvedLocale();
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

  test('TerminalOutputUtils uses shared terminal trim policy with notice', () {
    final output = TerminalOutputUtils.trim(
      List.generate(605, (index) => 'line $index').join('\n'),
    );

    expect(output, startsWith('[更早输出已省略]\n'));
    expect(output, isNot(contains('line 0')));
    expect(output, contains('line 604'));
  });

  test('TerminalOutputUtils localizes display notices in English', () {
    AppTextLocalizer.setResolvedLocale(const Locale('en'));

    final trimmed = TerminalOutputUtils.trim(
      List.generate(605, (index) => 'line $index').join('\n'),
    );
    final fallback = TerminalOutputUtils.buildDisplayOutput(
      terminalOutput: '',
      rawResultJson: jsonEncode({
        'liveFallbackReason': 'shared storage unavailable',
        'stdout': 'hello',
      }),
      resultPreviewJson: '',
    );

    expect(trimmed, startsWith('[Earlier output omitted]\n'));
    expect(fallback, contains('[Live output fallback]'));
    expect(trimmed, isNot(contains('[更早输出已省略]')));
    expect(fallback, isNot(contains('[实时输出已回退]')));
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
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
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

  test('tool card title and preview ignore generic placeholders', () {
    final cardData = {
      'status': 'running',
      'toolType': 'browser',
      'summary': 'Preparing tool call...',
      'progress': 'Preparing tool call...',
    };

    expect(resolveAgentToolTitle(cardData), '工具调用');
    expect(resolveAgentToolPreview(cardData), '浏览中');
  });

  testWidgets('tool card opens detail sheet when tapped', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
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
      find.descendant(of: sheet, matching: find.text('输入')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('结果')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('终端')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('已完成')),
      findsWidgets,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('Terminal')),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('Command')),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('Output')),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('Input')),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('Result')),
      findsNothing,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.byKey(kAgentToolDetailCopyButtonKey),
      ),
      findsOneWidget,
    );
    final outputPanel = tester.widget<DecoratedBox>(
      find.descendant(
        of: sheet,
        matching: find.byKey(kAgentToolDetailOutputPanelKey),
      ),
    );
    expect(
      (outputPanel.decoration as BoxDecoration).color,
      isNot(kTerminalSurfaceBlack),
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('git status', findRichText: true),
      ),
      findsAtLeastNWidgets(1),
    );

    await tester.tapAt(const Offset(12, 12));
    await tester.pumpAndSettle();

    expect(sheet, findsNothing);
  });

  testWidgets(
    'non-terminal tool detail uses the same generic input/result shell',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          supportedLocales: const [Locale('zh'), Locale('en')],
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
          ],
          home: Scaffold(
            body: Center(
              child: AgentToolSummaryCard(
                cardData: {
                  'status': 'success',
                  'displayName': '读取文件',
                  'toolTitle': '查看 README',
                  'toolName': 'file_read',
                  'toolType': 'workspace',
                  'argsJson': jsonEncode({'path': '/workspace/README.md'}),
                  'resultPreviewJson': jsonEncode({
                    'path': '/workspace/README.md',
                    'content': 'hello',
                  }),
                },
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('查看 README'));
      await tester.pumpAndSettle();

      final sheet = find.byKey(kAgentToolDetailSheetKey);
      expect(sheet, findsOneWidget);
      expect(
        find.descendant(of: sheet, matching: find.text('输入')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: sheet, matching: find.text('结果')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: sheet, matching: find.text('工作区')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: sheet, matching: find.text('命令')),
        findsNothing,
      );
      expect(
        find.descendant(of: sheet, matching: find.text('输出')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'interrupted status shows stopped state without loading spinner',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          supportedLocales: const [Locale('zh'), Locale('en')],
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
          ],
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
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
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
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
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
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
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

  testWidgets('VLM tool card uses visual task label and target title', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'toolType': 'vlm',
              'toolName': 'click',
              'toolTitle': '点击 设置按钮',
              'summary': '点击 设置按钮',
              'argsJson': jsonEncode({
                'target_description': '设置按钮',
                'x': 120,
                'y': 240,
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('设置按钮'), findsOneWidget);
    expect(find.text('视觉执行'), findsOneWidget);
    expect(find.text('网页搜索'), findsNothing);
  });

  testWidgets('VLM wrapper card localizes without explicit toolType', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'toolName': 'vlm_task',
              'compile_kind': 'vlm',
              'argsJson': jsonEncode({'goal': '打开设置'}),
            },
          ),
        ),
      ),
    );

    expect(find.text('打开设置'), findsOneWidget);
    expect(find.text('视觉执行'), findsOneWidget);
    expect(find.text('Tool call'), findsNothing);
  });
}
