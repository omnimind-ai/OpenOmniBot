import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/vlm_model_setting/vlm_model_setting_page.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

const _modelsDevCatalogJson = '''
{
  "openai": {
    "id": "openai",
    "name": "OpenAI",
    "models": {
      "gpt-4o": {
        "id": "gpt-4o",
        "name": "GPT-4o",
        "limit": {"context": 128000, "input": 96000, "output": 16384},
        "modalities": {"input": ["text", "image", "pdf"], "output": ["text"]},
        "family": "gpt",
        "attachment": true,
        "reasoning": true,
        "tool_call": true,
        "structured_output": true,
        "temperature": true
      },
      "gpt-4o-mini": {
        "id": "gpt-4o-mini",
        "name": "GPT-4o mini",
        "limit": {"context": 128000, "output": 16384},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "reasoning": true
      },
      "text-embedding-3-large": {
        "id": "text-embedding-3-large",
        "name": "Text Embedding 3 Large",
        "limit": {"context": 8191},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "embedding"
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  Map<String, dynamic> profilePayload({
    String baseUrl = 'https://api.openai.com/v1',
    String protocolType = 'openai_compatible',
  }) {
    return <String, dynamic>{
      'profiles': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'provider-1',
          'name': 'DeepSeek',
          'baseUrl': baseUrl,
          'apiKey': 'sk-demo',
          'sourceType': 'custom',
          'readOnly': false,
          'ready': true,
          'statusText': '',
          'configured': true,
          'protocolType': protocolType,
        },
      ],
      'editingProfileId': 'provider-1',
    };
  }

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
      }
      return null;
    });
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, null);
    ModelsDevCatalogService.resetForTesting();
  });

  testWidgets(
    'provider page renders header actions without layout exceptions',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          home: const VlmModelSettingPage(),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(
        find.descendant(
          of: find.byKey(const Key('provider-config-title')),
          matching: find.text('DeepSeek'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byKey(const Key('provider-protocol-type-button')),
          matching: find.text('OpenAI'),
        ),
        findsOneWidget,
      );
      expect(find.text('模型类型'), findsNothing);
      final providerRight = tester
          .getTopRight(find.byKey(const Key('provider-config-title')))
          .dx;
      final protocolLeft = tester
          .getTopLeft(find.byKey(const Key('provider-protocol-type-button')))
          .dx;
      expect(protocolLeft - providerRight, 4);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('base url hint mentions trailing marker override', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const VlmModelSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final baseUrlField = tester.widget<TextField>(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == 'Base URL',
      ),
    );
    expect(baseUrlField.decoration?.hintText, contains('末尾加 #'));
  });

  testWidgets('anthropic profile shows full messages request url', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload(
            baseUrl: 'https://api.anthropic.com',
            protocolType: 'anthropic',
          );
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const VlmModelSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('https://api.anthropic.com/v1/messages'), findsOneWidget);
  });

  testWidgets('provider fields do not auto-save while focused', (tester) async {
    var saveCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'saveModelProviderProfile':
          saveCalls += 1;
          final args = Map<dynamic, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          return <String, dynamic>{
            'id': 'provider-1',
            'name': (args['name'] ?? 'DeepSeek').toString(),
            'baseUrl': (args['baseUrl'] ?? '').toString(),
            'apiKey': (args['apiKey'] ?? '').toString(),
            'sourceType': 'custom',
            'readOnly': false,
            'ready': true,
            'statusText': '',
            'configured': true,
            'protocolType': (args['protocolType'] ?? 'openai_compatible')
                .toString(),
          };
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const VlmModelSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final nameField = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == 'Provider 名称',
    );
    await tester.tap(nameField);
    await tester.pump();
    await tester.enterText(nameField, 'DeepSeek Pro');

    await tester.pump(const Duration(milliseconds: 700));

    expect(saveCalls, 0);

    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));

    expect(saveCalls, 1);
  });

  testWidgets('renders models.dev grouping, context, and input modalities', (
    tester,
  ) async {
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://api.openai.com/v1',
      models: const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'gpt-4o-mini'),
        ProviderModelOption(
          id: 'text-embedding-3-large',
          displayName: 'text-embedding-3-large',
        ),
      ],
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const VlmModelSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.byKey(const Key('provider-logo')), findsNothing);
    expect(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-group-gpt-4o')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-group-text-embedding')),
      findsOneWidget,
    );
    expect(find.text('2'), findsOneWidget);
    expect(find.text('128K'), findsNWidgets(2));
    expect(
      find.descendant(
        of: find.byKey(const Key('provider-model-context-gpt-4o')),
        matching: find.byType(SvgPicture),
      ),
      findsNothing,
    );
    expect(
      find.byKey(const Key('provider-model-reasoning-gpt-4o')),
      findsOneWidget,
    );
    expect(find.text('Context 128K'), findsNothing);
    expect(find.text('Tools'), findsNothing);
    expect(find.text('JSON'), findsNothing);
    expect(find.text('Files'), findsNothing);
    expect(find.text('Temp'), findsNothing);
    expect(find.text('自动'), findsNothing);
    expect(find.text('手动'), findsNothing);
    expect(
      find.byKey(const Key('provider-model-modality-text')),
      findsNWidgets(3),
    );
    expect(
      find.byKey(const Key('provider-model-modality-image')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-modality-pdf')),
      findsOneWidget,
    );

    final groupBody = find.byKey(const Key('provider-model-group-body-gpt-4o'));
    expect(tester.getSize(groupBody).height, greaterThan(0));
    final shortLine = tester.getSize(
      find.byKey(const Key('provider-model-group-line-gpt-4o')),
    );
    final longLine = tester.getSize(
      find.byKey(const Key('provider-model-group-line-text-embedding')),
    );
    expect(shortLine.width, greaterThan(longLine.width));
    final shortHeaderRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-gpt-4o')),
    );
    final shortIconRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-icon-gpt-4o')),
    );
    expect((shortHeaderRight.dx - shortIconRight.dx).abs(), lessThan(6));
    final shortCountRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-count-gpt-4o')),
    );
    final shortLineLeft = tester.getTopLeft(
      find.byKey(const Key('provider-model-group-line-gpt-4o')),
    );
    final shortLineRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-line-gpt-4o')),
    );
    final shortIconLeft = tester.getTopLeft(
      find.byKey(const Key('provider-model-group-icon-gpt-4o')),
    );
    expect(shortLineLeft.dx - shortCountRight.dx, closeTo(10, 0.5));
    expect(shortIconLeft.dx - shortLineRight.dx, closeTo(6, 0.5));
    final firstModel = find.byKey(
      const ValueKey<String>('provider-model-gpt-4o'),
    );
    final secondModel = find.byKey(
      const ValueKey<String>('provider-model-gpt-4o-mini'),
    );
    expect(tester.getSize(firstModel).height, 44);
    expect(tester.getSize(secondModel).height, 44);
    expect(tester.getSize(firstModel).width, tester.getSize(secondModel).width);

    await tester.tap(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, 0);
    expect(
      tester
          .getSize(
            find.byKey(const Key('provider-model-group-body-text-embedding')),
          )
          .height,
      0,
    );

    await tester.tap(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, greaterThan(0));

    await tester.tap(find.byKey(const Key('provider-model-group-gpt-4o')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, 0);

    await tester.tap(find.byKey(const Key('provider-model-group-gpt-4o')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, greaterThan(0));

    expect(tester.takeException(), isNull);
  });

  testWidgets('file sync does not reload provider fields while editing', (
    tester,
  ) async {
    var listCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          listCalls += 1;
          return profilePayload();
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const VlmModelSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(listCalls, 1);

    final nameField = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == 'Provider 名称',
    );
    await tester.tap(nameField);
    await tester.pump();
    await tester.enterText(nameField, 'DeepSeek Pro');

    AssistsMessageService.dispatchAgentAiConfigChanged(
      const AgentAiConfigChangedEvent(source: 'file', path: '/tmp/config.json'),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(listCalls, 1);
    expect(find.text('DeepSeek Pro'), findsOneWidget);
  });
}
