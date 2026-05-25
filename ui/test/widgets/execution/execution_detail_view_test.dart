import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/execution/execution_detail_view.dart';
import 'package:ui/widgets/execution/execution_models.dart';

void main() {
  testWidgets('ExecutionDetailView labels reusable commands without raw schema copy', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        supportedLocales: [Locale('zh'), Locale('en')],
        localizationsDelegates: [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Scaffold(
          body: ExecutionDetailView(
            detail: ExecutionDetail(
              id: 'open_settings',
              type: ExecutionDetailType.function,
              goal: '打开设置',
              success: true,
            ),
            showStats: false,
            showTimeline: false,
            showAssetRefs: false,
          ),
        ),
      ),
    );

    expect(find.text('复用指令'), findsOneWidget);
    expect(find.text('Function'), findsNothing);
  });
}
