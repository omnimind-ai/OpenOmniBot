import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/codex/codex_remote_workspace_browser.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('loads remote Codex workspace entries from bridge list API', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'ok': true,
        'path': '/repo',
        'cwd': '/repo',
        'parent': '/Users/me',
        'entries': <Map<String, dynamic>>[
          <String, dynamic>{
            'name': 'lib',
            'path': '/repo/lib',
            'type': 'directory',
          },
          <String, dynamic>{
            'name': 'README.md',
            'path': '/repo/README.md',
            'type': 'file',
          },
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: CodexRemoteWorkspaceBrowser(
            workspacePath: '/repo',
            remoteBridgeUrl: 'ws://192.168.1.2:17321/codex',
            remoteBridgeToken: 'token',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('lib'), findsOneWidget);
    expect(find.text('README.md'), findsOneWidget);
    expect(calls.single.method, 'config/remote/fs/list');
    expect(calls.single.arguments, <String, dynamic>{
      'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
      'remoteBridgeToken': 'token',
      'remoteCwd': '/repo',
      'path': '/repo',
    });
  });

  testWidgets('opens remote file preview on file tap', (tester) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'config/remote/fs/read') {
        return <String, dynamic>{
          'ok': true,
          'path': '/repo/README.md',
          'name': 'README.md',
          'type': 'file',
          'previewKind': 'text',
          'mimeType': 'text/plain',
          'content': 'Remote readme content',
        };
      }
      return <String, dynamic>{
        'ok': true,
        'path': '/repo',
        'cwd': '/repo',
        'entries': <Map<String, dynamic>>[
          <String, dynamic>{
            'name': 'README.md',
            'path': '/repo/README.md',
            'type': 'file',
          },
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: CodexRemoteWorkspaceBrowser(
            workspacePath: '/repo',
            remoteBridgeUrl: 'ws://192.168.1.2:17321/codex',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('README.md'));
    await tester.pumpAndSettle();

    expect(find.text('Remote readme content'), findsOneWidget);
    expect(calls.map((call) => call.method), [
      'config/remote/fs/list',
      'config/remote/fs/read',
    ]);
  });
}
