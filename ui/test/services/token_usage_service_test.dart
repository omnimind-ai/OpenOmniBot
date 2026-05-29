import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/token_usage_service.dart';

void main() {
  test('normalizes provider-prefixed model ids for token charts', () {
    expect(
      TokenUsageService.normalizeModelId('openai/gpt-4o-mini'),
      'gpt-4o-mini',
    );
    expect(
      TokenUsageService.normalizeModelId('Qwen/Qwen2.5-7B-Instruct'),
      'Qwen2.5-7B-Instruct',
    );
    expect(TokenUsageService.normalizeModelId('openai:gpt-4o'), 'gpt-4o');
    expect(
      TokenUsageService.normalizeModelId(
        'openrouter|meta-llama/llama-3.1:free',
      ),
      'llama-3.1:free',
    );
    expect(TokenUsageService.normalizeModelId('  '), 'unknown');
  });

  test('modelId getter uses normalized id', () {
    final record = TokenUsageRecord(
      id: 1,
      conversationId: 0,
      isLocal: false,
      model: 'anthropic/claude-3-5-sonnet',
      promptTokens: 12,
      completionTokens: 34,
      reasoningTokens: 0,
      textTokens: 0,
      cachedTokens: 0,
      createdAt: 1000,
    );

    expect(record.modelId, 'claude-3-5-sonnet');
    expect(record.totalTokens, 34);
  });
}
