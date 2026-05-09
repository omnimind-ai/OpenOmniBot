import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_browser_overlay.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  testWidgets('renders unsupported fallback and rich prompts', (tester) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ChatBrowserOverlay(
            snapshot: const ChatBrowserSessionSnapshot(
              available: true,
              workspaceId: 'conversation_1',
              activeTabId: 2,
              currentUrl: 'https://example.com',
              title: '示例页面',
              isBookmarked: true,
              canGoBack: true,
              tabs: <AgentBrowserTab>[
                AgentBrowserTab(
                  tabId: 2,
                  url: 'https://example.com',
                  title: '示例页面',
                  isActive: true,
                ),
              ],
              externalOpenPrompt: BrowserExternalOpenPrompt(
                requestId: 'external-1',
                title: '打开应用',
                target: 'intent://demo',
              ),
              pendingDialog: BrowserDialogPrompt(
                requestId: 'dialog-1',
                type: 'prompt',
                message: '请输入内容',
                defaultValue: '默认值',
              ),
              permissionPrompt: BrowserPermissionPrompt(
                requestId: 'permission-1',
                kind: 'geolocation',
                origin: 'https://example.com',
                resources: <String>['android.permission.ACCESS_FINE_LOCATION'],
              ),
              userscriptSummary: BrowserUserscriptSummary(
                pendingInstall: BrowserUserscriptInstallPreview(
                  id: 4,
                  name: 'Demo Script',
                  description: '',
                  version: '1.0.0',
                  isUpdate: false,
                ),
              ),
            ),
            onSnapshotChanged: (_) {},
            onClose: () {},
            onDragDelta: (_) {},
            onResizeLeftDelta: (_) {},
            onResizeRightDelta: (_) {},
          ),
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(find.text('https://example.com'), findsOneWidget);
    expect(find.text('是否打开外部链接？'), findsOneWidget);
    expect(find.text('页面请求权限'), findsOneWidget);
    expect(find.text('页面输入'), findsOneWidget);
    expect(find.text('当前平台暂不支持浏览器工具视图'), findsOneWidget);
    expect(find.byIcon(Icons.star_rounded), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back_rounded), findsOneWidget);
  });
}
