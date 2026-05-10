import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/chat_terminal_environment_service.dart';

void main() {
  List<ChatTerminalEnvironmentVariable> variables() {
    return const [
      ChatTerminalEnvironmentVariable(key: 'FIRST', value: '1'),
      ChatTerminalEnvironmentVariable(key: 'SECOND', value: '2'),
      ChatTerminalEnvironmentVariable(key: 'THIRD', value: '3'),
    ];
  }

  test('replaceVariable edits a value without moving the variable', () {
    final next = ChatTerminalEnvironmentService.replaceVariable(
      variables(),
      originalKey: 'SECOND',
      replacement: const ChatTerminalEnvironmentVariable(
        key: 'SECOND',
        value: 'updated',
      ),
    );

    expect(next.map((item) => item.key).toList(), [
      'FIRST',
      'SECOND',
      'THIRD',
    ]);
    expect(next.map((item) => item.value).toList(), ['1', 'updated', '3']);
  });

  test('replaceVariable renames a variable in place', () {
    final next = ChatTerminalEnvironmentService.replaceVariable(
      variables(),
      originalKey: 'SECOND',
      replacement: const ChatTerminalEnvironmentVariable(
        key: 'RENAMED',
        value: 'updated',
      ),
    );

    expect(next.map((item) => item.key).toList(), [
      'FIRST',
      'RENAMED',
      'THIRD',
    ]);
    expect(next.map((item) => item.value).toList(), ['1', 'updated', '3']);
  });

  test('containsKey ignores the variable currently being edited', () {
    expect(
      ChatTerminalEnvironmentService.containsKey(
        variables(),
        'SECOND',
        exceptKey: 'SECOND',
      ),
      isFalse,
    );
    expect(
      ChatTerminalEnvironmentService.containsKey(
        variables(),
        'FIRST',
        exceptKey: 'SECOND',
      ),
      isTrue,
    );
  });
}
