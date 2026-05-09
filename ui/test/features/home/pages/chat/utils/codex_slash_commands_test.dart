import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/codex_slash_commands.dart';

void main() {
  test('routes codex model command intents', () {
    expect(
      resolveCodexSlashSubmitIntent('/model').kind,
      CodexSlashSubmitKind.openModelPicker,
    );

    final intent = resolveCodexSlashSubmitIntent('/model gpt-5-codex');
    expect(intent.kind, CodexSlashSubmitKind.selectModel);
    expect(intent.value, 'gpt-5-codex');
  });

  test('routes codex review init and plan command intents', () {
    expect(
      resolveCodexSlashSubmitIntent('/review').kind,
      CodexSlashSubmitKind.startReview,
    );
    expect(
      resolveCodexSlashSubmitIntent('/init').kind,
      CodexSlashSubmitKind.startInit,
    );
    expect(
      resolveCodexSlashSubmitIntent('/plan').kind,
      CodexSlashSubmitKind.activatePlan,
    );

    final planIntent = resolveCodexSlashSubmitIntent('/plan inspect the diff');
    expect(planIntent.kind, CodexSlashSubmitKind.startPlan);
    expect(planIntent.value, 'inspect the diff');
  });

  test('rejects agent-only slash commands in codex mode', () {
    expect(
      resolveCodexSlashSubmitIntent('/compact').kind,
      CodexSlashSubmitKind.unsupported,
    );
    expect(
      resolveCodexSlashSubmitIntent('/effort high').kind,
      CodexSlashSubmitKind.unsupported,
    );
    expect(
      resolveCodexSlashSubmitIntent('/openclaw http://example.com').kind,
      CodexSlashSubmitKind.unsupported,
    );
  });
}
