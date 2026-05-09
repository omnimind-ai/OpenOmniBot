import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

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
        "reasoning": false,
        "tool_call": true,
        "structured_output": true,
        "temperature": true
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(ModelsDevCatalogService.resetForTesting);

  test('builds request urls from root base url', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl(
        'https://api.example.com',
      ),
      'https://api.example.com/v1/models',
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com',
      ),
      'https://api.example.com/v1/chat/completions',
    );
  });

  test('allows trailing marker to bypass automatic request suffixes', () {
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com/custom/chat#',
      ),
      'https://api.example.com/custom/chat',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.example.com/custom/messages#',
      ),
      'https://api.example.com/custom/messages',
    );
  });

  test('builds request urls without duplicating v1 suffix', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl(
        'https://api.example.com/v1',
      ),
      'https://api.example.com/v1/models',
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com/v1',
      ),
      'https://api.example.com/v1/chat/completions',
    );
  });

  test(
    'normalizes explicit endpoint inputs before rebuilding request urls',
    () {
      expect(
        ModelProviderConfigService.buildModelsRequestUrl(
          'https://api.example.com/v1/chat/completions',
        ),
        'https://api.example.com/v1/models',
      );
      expect(
        ModelProviderConfigService.buildChatCompletionsRequestUrl(
          'https://api.example.com/v1/models',
        ),
        'https://api.example.com/v1/chat/completions',
      );
    },
  );

  test('builds anthropic request urls from base url', () {
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com',
      ),
      'https://api.anthropic.com/v1/messages',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com/v1',
      ),
      'https://api.anthropic.com/v1/messages',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com/v1/messages',
      ),
      'https://api.anthropic.com/v1/messages',
    );
  });

  test('returns null for invalid base url input', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl('api.example.com'),
      isNull,
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(''),
      isNull,
    );
  });

  test('parses legacy cached model options without metadata', () {
    final option = ProviderModelOption.fromMap({
      'id': 'legacy-model',
      'displayName': 'Legacy Model',
      'ownedBy': 'remote',
    });

    expect(option.id, 'legacy-model');
    expect(option.displayName, 'Legacy Model');
    expect(option.contextLimit, isNull);
    expect(option.inputModalities, isEmpty);
  });

  test('enriches model options with models.dev metadata', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );

    final enriched = await ModelProviderConfigService.enrichModelsForProfile(
      profileId: 'provider-1',
      providerName: 'OpenAI',
      apiBase: 'https://api.openai.com/v1',
      models: const [ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o')],
    );

    expect(enriched.single.displayName, 'GPT-4o');
    expect(enriched.single.contextLimit, 128000);
    expect(enriched.single.inputLimit, 96000);
    expect(enriched.single.outputLimit, 16384);
    expect(enriched.single.inputModalities, ['text', 'image', 'pdf']);
    expect(enriched.single.outputModalities, ['text']);
    expect(enriched.single.attachment, isTrue);
    expect(enriched.single.toolCall, isTrue);
    expect(enriched.single.structuredOutput, isTrue);
    expect(enriched.single.temperature, isTrue);
    expect(
      enriched.single.providerLogoUrl,
      'https://models.dev/logos/openai.svg',
    );
    expect(enriched.single.group, 'gpt-4o');
  });

  test(
    'enriches common model ids even when provider is a custom proxy',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      ModelsDevCatalogService.setCatalogForTesting(
        ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
      );

      final enriched = await ModelProviderConfigService.enrichModelsForProfile(
        profileId: 'custom-proxy',
        providerName: 'My Proxy',
        apiBase: 'https://llm.example.com/v1',
        models: const [
          ProviderModelOption(id: 'openai/gpt-4o:free', displayName: 'gpt-4o'),
        ],
      );

      expect(enriched.single.contextLimit, 128000);
      expect(enriched.single.modelsDevProviderId, 'openai');
      expect(
        enriched.single.providerLogoUrl,
        'https://models.dev/logos/openai.svg',
      );
      expect(enriched.single.toolCall, isTrue);
    },
  );
}
