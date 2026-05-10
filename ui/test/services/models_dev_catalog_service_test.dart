import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/models_dev_catalog_service.dart';

const _catalogJson = '''
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
      },
      "gpt-4o-mini": {
        "id": "gpt-4o-mini",
        "name": "GPT-4o mini",
        "limit": {"context": 128000},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "tool_call": true
      }
    }
  },
  "openrouter": {
    "id": "openrouter",
    "name": "OpenRouter",
    "api": "https://openrouter.ai/api/v1",
    "models": {
      "openai/gpt-4o-mini:free": {
        "id": "openai/gpt-4o-mini:free",
        "name": "GPT-4o mini (free)",
        "limit": {"context": 64000},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "tool_call": false
      }
    }
  },
  "alibaba": {
    "id": "alibaba",
    "name": "Alibaba",
    "api": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    "models": {
      "qwen-max": {
        "id": "qwen-max",
        "name": "Qwen Max",
        "limit": {"context": 1000000},
        "modalities": {"input": ["text"]}
      },
      "qwen3.5-plus": {
        "id": "qwen3.5-plus",
        "name": "Qwen 3.5 Plus",
        "limit": {"context": 262144},
        "modalities": {"input": ["text"]}
      }
    }
  }
}
''';

void main() {
  test('parses models.dev provider and model metadata', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final openai = catalog.providers['openai'];
    final model = openai?.findModel('gpt-4o');

    expect(openai?.name, 'OpenAI');
    expect(openai?.logoUrl, 'https://models.dev/logos/openai.svg');
    expect(model?.contextLimit, 128000);
    expect(model?.inputLimit, 96000);
    expect(model?.outputLimit, 16384);
    expect(model?.inputModalities, ['text', 'image', 'pdf']);
    expect(model?.outputModalities, ['text']);
    expect(model?.family, 'gpt');
    expect(model?.attachment, isTrue);
    expect(model?.toolCall, isTrue);
    expect(model?.structuredOutput, isTrue);
    expect(model?.temperature, isTrue);
  });

  test('matches providers by name, id, and API host', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);

    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerName: 'OpenAI',
      )?.id,
      'openai',
    );
    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerId: 'alibaba',
      )?.id,
      'alibaba',
    );
    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        apiBase: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
      )?.id,
      'alibaba',
    );
  });

  test('groups models with Cherry Studio compatible rules', () {
    expect(
      ModelsDevCatalogService.defaultGroupName('claude-3-5-sonnet'),
      'claude-3',
    );
    expect(
      ModelsDevCatalogService.defaultGroupName('deepseek/deepseek-r1'),
      'deepseek',
    );
    expect(
      ModelsDevCatalogService.defaultGroupName(
        'vendor-qwen-max',
        providerId: 'siliconflow-cn',
      ),
      'vendor',
    );
    expect(
      ModelsDevCatalogService.groupModelId(
        'qwen-max-latest',
        providerId: 'alibaba',
      ),
      'qwen-max',
    );
    expect(
      ModelsDevCatalogService.groupModelId(
        'qwen3.5-plus-thinking-thu',
        providerId: 'alibaba',
      ),
      'qwen3.5-plus',
    );
  });

  test('matches model ids with prefixes, variants, and provider inference', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final openai = catalog.providers['openai'];
    final openrouter = catalog.providers['openrouter'];

    final officialPrefixed = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      provider: openai,
      modelId: 'openai/gpt-4o:free',
    );
    expect(officialPrefixed?.provider.id, 'openai');
    expect(officialPrefixed?.metadata.id, 'gpt-4o');

    final routerExact = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      provider: openrouter,
      modelId: 'openai/gpt-4o-mini:free',
    );
    expect(routerExact?.provider.id, 'openrouter');
    expect(routerExact?.metadata.contextLimit, 64000);

    final inferred = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      modelId: 'gpt-4o',
    );
    expect(inferred?.provider.id, 'openai');
    expect(inferred?.metadata.contextLimit, 128000);
  });

  test('fuzzy matches model metadata with known variant suffixes', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final alibaba = catalog.providers['alibaba'];

    expect(
      ModelsDevCatalogService.modelLookupCandidates(
        'qwen3.5-plus-thinking-thu',
      ),
      contains('qwen3.5-plus'),
    );
    expect(
      alibaba?.findModel('qwen3.5-plus-thinking-thu')?.contextLimit,
      262144,
    );
  });
}
