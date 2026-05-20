import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/workbench/widgets/workbench_layout_profile.dart';

void main() {
  testWidgets('workbench theme profile follows active brightness', (
    tester,
  ) async {
    Map<String, Object?>? profile;

    Widget harness(ThemeMode themeMode) {
      return MaterialApp(
        theme: ThemeData.light(),
        darkTheme: ThemeData.dark(),
        themeMode: themeMode,
        home: Builder(
          builder: (context) {
            profile = buildWorkbenchThemeProfile(context);
            return const SizedBox.shrink();
          },
        ),
      );
    }

    await tester.pumpWidget(harness(ThemeMode.dark));
    await tester.pumpAndSettle();
    expect(profile?['colorScheme'], 'dark');
    expect(profile?['isDarkMode'], isTrue);

    await tester.pumpWidget(harness(ThemeMode.light));
    await tester.pumpAndSettle();
    expect(profile?['colorScheme'], 'light');
    expect(profile?['isDarkMode'], isFalse);
  });
}
