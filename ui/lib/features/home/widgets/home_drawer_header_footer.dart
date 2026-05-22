part of 'home_drawer.dart';

extension _HomeDrawerHeaderFooter on HomeDrawerState {
  Map<String, String> _getGreetingByTime() {
    final hour = DateTime.now().hour;

    if (hour >= 2 && hour < 6) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerDawnGreeting,
          'subtitle': context.l10n.homeDrawerDawnSub,
        },
        {
          'title': context.l10n.homeDrawerDawnGreeting2,
          'subtitle': context.l10n.homeDrawerDawnSub2,
        },
        {
          'title': context.l10n.homeDrawerDawnGreeting3,
          'subtitle': context.l10n.homeDrawerDawnSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 6 && hour < 8) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerMorningGreeting,
          'subtitle': context.l10n.homeDrawerMorningSub,
        },
        {
          'title': context.l10n.homeDrawerMorningGreeting2,
          'subtitle': context.l10n.homeDrawerMorningSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 8 && hour < 12) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerForenoonGreeting,
          'subtitle': context.l10n.homeDrawerForenoonSub,
        },
        {
          'title': context.l10n.homeDrawerForenoonGreeting2,
          'subtitle': context.l10n.homeDrawerForenoonSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 12 && hour < 14) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerLunchGreeting,
          'subtitle': context.l10n.homeDrawerLunchSub,
        },
        {
          'title': context.l10n.homeDrawerLunchGreeting2,
          'subtitle': context.l10n.homeDrawerLunchSub2,
        },
        {
          'title': context.l10n.homeDrawerLunchGreeting3,
          'subtitle': context.l10n.homeDrawerLunchSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 14 && hour < 18) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerAfternoonGreeting,
          'subtitle': context.l10n.homeDrawerAfternoonSub,
        },
        {
          'title': context.l10n.homeDrawerAfternoonGreeting2,
          'subtitle': context.l10n.homeDrawerAfternoonSub2,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 18 && hour < 20) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerEveningGreeting,
          'subtitle': context.l10n.homeDrawerEveningSub,
        },
        {
          'title': context.l10n.homeDrawerEveningGreeting2,
          'subtitle': context.l10n.homeDrawerEveningSub2,
        },
        {
          'title': context.l10n.homeDrawerEveningGreeting3,
          'subtitle': context.l10n.homeDrawerEveningSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    if (hour >= 20 && hour < 22) {
      final greetings = [
        {
          'title': context.l10n.homeDrawerNightGreeting,
          'subtitle': context.l10n.homeDrawerNightSub,
        },
        {
          'title': context.l10n.homeDrawerNightGreeting2,
          'subtitle': context.l10n.homeDrawerNightSub2,
        },
        {
          'title': context.l10n.homeDrawerNightGreeting3,
          'subtitle': context.l10n.homeDrawerNightSub3,
        },
      ];
      return greetings[DateTime.now().minute % greetings.length];
    }

    final greetings = [
      {
        'title': context.l10n.homeDrawerLateNightGreeting,
        'subtitle': context.l10n.homeDrawerLateNightSub,
      },
      {
        'title': context.l10n.homeDrawerLateNightGreeting2,
        'subtitle': context.l10n.homeDrawerLateNightSub2,
      },
    ];
    return greetings[DateTime.now().minute % greetings.length];
  }

  Color get _drawerBackgroundColor {
    if (!context.isDarkTheme) {
      return AppColors.background;
    }
    return context.omniPalette.pageBackground;
  }

  Color get _drawerTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text;
    }
    return context.omniPalette.textPrimary;
  }

  Color get _drawerSecondaryTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text.withValues(alpha: 0.4);
    }
    return context.omniPalette.textSecondary;
  }

  Widget _buildUserHeader() {
    final greeting = _getGreetingByTime();

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            greeting['title'] ?? context.l10n.homeDrawerGreeting,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w400,
              color: _drawerTextColor,
              height: 1.5,
            ),
          ),
          Text(
            greeting['subtitle'] ?? context.l10n.homeDrawerWelcome,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w400,
              color: _drawerTextColor,
              height: 1.5,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFooterShortcutBar() {
    final items = <_DrawerShortcutAction>[
      _DrawerShortcutAction(
        label: context.l10n.settingsTitle,
        assetPath: 'assets/home/setting_icon.svg',
        onTap: () => _navigateTo('/home/settings'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.memoryCenterTitle,
        svgString: _kDrawerMemoryIconSvg,
        onTap: () => _navigateTo('/memory/memory_center_page'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.skillStoreTitle,
        svgString: _kDrawerSkillStoreIconSvg,
        onTap: () => _navigateTo('/home/skill_store'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.trajectoryTitle,
        svgString: _kDrawerTaskHistoryIconSvg,
        onTap: () => _navigateTo('/task/execution_history'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.homeDrawerScheduled,
        assetPath: 'assets/common/schedule_icon.svg',
        onTap: () => _navigateTo('/task/scheduled_tasks'),
      ),
    ];

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: items
            .map((item) => Expanded(child: _buildFooterShortcutButton(item)))
            .toList(growable: false),
      ),
    );
  }

  Widget _buildFooterShortcutButton(_DrawerShortcutAction item) {
    final palette = context.omniPalette;
    final circleColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : Colors.white;
    final iconColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final icon = item.assetPath != null
        ? SvgPicture.asset(
            item.assetPath!,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          )
        : SvgPicture.string(
            item.svgString!,
            width: 18,
            height: 18,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          );

    return Tooltip(
      message: item.label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: item.onTap,
          borderRadius: BorderRadius.circular(24),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Center(
              child: Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: circleColor,
                  shape: BoxShape.circle,
                  boxShadow: context.isDarkTheme
                      ? const []
                      : [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.04),
                            blurRadius: 10,
                            offset: const Offset(0, 3),
                          ),
                        ],
                ),
                alignment: Alignment.center,
                child: icon,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
