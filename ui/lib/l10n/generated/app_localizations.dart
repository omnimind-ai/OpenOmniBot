import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// No description provided for @appName.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get appName;

  /// No description provided for @brandName.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get brandName;

  /// No description provided for @brandNameEnglish.
  ///
  /// In zh, this message translates to:
  /// **'Omnibot'**
  String get brandNameEnglish;

  /// No description provided for @commonLoading.
  ///
  /// In zh, this message translates to:
  /// **'加载中'**
  String get commonLoading;

  /// No description provided for @homeDrawerSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索全部对话'**
  String get homeDrawerSearchHint;

  /// No description provided for @homeDrawerClearSearch.
  ///
  /// In zh, this message translates to:
  /// **'清空搜索'**
  String get homeDrawerClearSearch;

  /// No description provided for @themeModeTitle.
  ///
  /// In zh, this message translates to:
  /// **'主题模式'**
  String get themeModeTitle;

  /// No description provided for @themeModeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'切换浅色、深色或跟随系统外观'**
  String get themeModeSubtitle;

  /// No description provided for @themeModeLight.
  ///
  /// In zh, this message translates to:
  /// **'浅色'**
  String get themeModeLight;

  /// No description provided for @themeModeDark.
  ///
  /// In zh, this message translates to:
  /// **'深色'**
  String get themeModeDark;

  /// No description provided for @themeModeSystem.
  ///
  /// In zh, this message translates to:
  /// **'系统'**
  String get themeModeSystem;

  /// No description provided for @languageTitle.
  ///
  /// In zh, this message translates to:
  /// **'语言'**
  String get languageTitle;

  /// No description provided for @languageSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'设置应用界面、Agent 提示词与工具文案的显示语言'**
  String get languageSubtitle;

  /// No description provided for @languageFollowSystem.
  ///
  /// In zh, this message translates to:
  /// **'跟随系统'**
  String get languageFollowSystem;

  /// No description provided for @languageZhHans.
  ///
  /// In zh, this message translates to:
  /// **'简体中文'**
  String get languageZhHans;

  /// No description provided for @languageEnglish.
  ///
  /// In zh, this message translates to:
  /// **'English'**
  String get languageEnglish;

  /// No description provided for @settingsTitle.
  ///
  /// In zh, this message translates to:
  /// **'设置'**
  String get settingsTitle;

  /// No description provided for @settingsSectionModelMemory.
  ///
  /// In zh, this message translates to:
  /// **'模型与记忆'**
  String get settingsSectionModelMemory;

  /// No description provided for @settingsSectionServiceEnvironment.
  ///
  /// In zh, this message translates to:
  /// **'服务与环境'**
  String get settingsSectionServiceEnvironment;

  /// No description provided for @settingsSectionExperienceAppearance.
  ///
  /// In zh, this message translates to:
  /// **'体验与外观'**
  String get settingsSectionExperienceAppearance;

  /// No description provided for @settingsSectionPermissionInfo.
  ///
  /// In zh, this message translates to:
  /// **'权限与信息'**
  String get settingsSectionPermissionInfo;

  /// No description provided for @settingsModelProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'模型提供商'**
  String get settingsModelProviderTitle;

  /// No description provided for @settingsModelProviderSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置模型地址、密钥与模型列表'**
  String get settingsModelProviderSubtitle;

  /// No description provided for @settingsSceneModelTitle.
  ///
  /// In zh, this message translates to:
  /// **'场景模型配置'**
  String get settingsSceneModelTitle;

  /// No description provided for @settingsSceneModelSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'按场景绑定模型，未绑定场景使用默认模型'**
  String get settingsSceneModelSubtitle;

  /// No description provided for @settingsLocalModelsTitle.
  ///
  /// In zh, this message translates to:
  /// **'本地模型服务'**
  String get settingsLocalModelsTitle;

  /// No description provided for @settingsLocalModelsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'管理本地模型、推理、API 服务与语音模型'**
  String get settingsLocalModelsSubtitle;

  /// No description provided for @settingsWorkspaceMemoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆配置'**
  String get settingsWorkspaceMemoryTitle;

  /// No description provided for @settingsWorkspaceMemoryLoading.
  ///
  /// In zh, this message translates to:
  /// **'加载中...'**
  String get settingsWorkspaceMemoryLoading;

  /// No description provided for @settingsWorkspaceMemoryEnabled.
  ///
  /// In zh, this message translates to:
  /// **'已启用 workspace 记忆（嵌入检索可用）'**
  String get settingsWorkspaceMemoryEnabled;

  /// No description provided for @settingsWorkspaceMemoryLexical.
  ///
  /// In zh, this message translates to:
  /// **'使用 workspace 记忆（当前为词法检索）'**
  String get settingsWorkspaceMemoryLexical;

  /// No description provided for @settingsMcpToolsTitle.
  ///
  /// In zh, this message translates to:
  /// **'MCP 工具'**
  String get settingsMcpToolsTitle;

  /// No description provided for @settingsMcpToolsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'添加、启停和管理远端 MCP 服务'**
  String get settingsMcpToolsSubtitle;

  /// No description provided for @settingsLocalServiceTitle.
  ///
  /// In zh, this message translates to:
  /// **'本机服务'**
  String get settingsLocalServiceTitle;

  /// No description provided for @settingsLocalServiceSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'在局域网内访问小万 MCP 和 webchat 服务'**
  String get settingsLocalServiceSubtitle;

  /// No description provided for @settingsAlpineTitle.
  ///
  /// In zh, this message translates to:
  /// **'Alpine 环境'**
  String get settingsAlpineTitle;

  /// No description provided for @settingsAlpineSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'查看与打开应用内 Alpine 终端环境'**
  String get settingsAlpineSubtitle;

  /// No description provided for @settingsHideRecentsTitle.
  ///
  /// In zh, this message translates to:
  /// **'后台隐藏'**
  String get settingsHideRecentsTitle;

  /// No description provided for @settingsHideRecentsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后应用将从最近任务列表中隐藏'**
  String get settingsHideRecentsSubtitle;

  /// No description provided for @settingsAlarmTitle.
  ///
  /// In zh, this message translates to:
  /// **'闹钟设置'**
  String get settingsAlarmTitle;

  /// No description provided for @settingsAlarmSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置默认铃声、本地 mp3 或 mp3 直链'**
  String get settingsAlarmSubtitle;

  /// No description provided for @settingsAppearanceTitle.
  ///
  /// In zh, this message translates to:
  /// **'外观设置'**
  String get settingsAppearanceTitle;

  /// No description provided for @settingsAppearanceSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置主题模式、语言、共享背景图、聊天字号和文本颜色'**
  String get settingsAppearanceSubtitle;

  /// No description provided for @settingsVibrationTitle.
  ///
  /// In zh, this message translates to:
  /// **'振动反馈'**
  String get settingsVibrationTitle;

  /// No description provided for @settingsVibrationSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'执行任务时，通过振动进行操作提醒'**
  String get settingsVibrationSubtitle;

  /// No description provided for @settingsAutoBackTitle.
  ///
  /// In zh, this message translates to:
  /// **'任务完成后自动回聊天'**
  String get settingsAutoBackTitle;

  /// No description provided for @settingsAutoBackSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'关闭后，任务结束将停留在当前完成页面'**
  String get settingsAutoBackSubtitle;

  /// No description provided for @settingsCompanionPermissionTitle.
  ///
  /// In zh, this message translates to:
  /// **'陪伴权限授权'**
  String get settingsCompanionPermissionTitle;

  /// No description provided for @settingsCompanionPermissionSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'仅访问您授权的 App，隐私安全更有保障'**
  String get settingsCompanionPermissionSubtitle;

  /// No description provided for @settingsAboutTitle.
  ///
  /// In zh, this message translates to:
  /// **'关于小万'**
  String get settingsAboutTitle;

  /// No description provided for @settingsHideRecentsFailed.
  ///
  /// In zh, this message translates to:
  /// **'设置后台隐藏失败'**
  String get settingsHideRecentsFailed;

  /// No description provided for @settingsSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'设置失败'**
  String get settingsSaveFailed;

  /// No description provided for @settingsAutoBackEnabledToast.
  ///
  /// In zh, this message translates to:
  /// **'任务完成后将自动返回聊天'**
  String get settingsAutoBackEnabledToast;

  /// No description provided for @settingsAutoBackDisabledToast.
  ///
  /// In zh, this message translates to:
  /// **'任务完成后将停留在当前页面'**
  String get settingsAutoBackDisabledToast;

  /// No description provided for @settingsMcpEnabledToast.
  ///
  /// In zh, this message translates to:
  /// **'MCP 已开启：{endpoint}'**
  String settingsMcpEnabledToast(Object endpoint);

  /// No description provided for @settingsMcpDisabledToast.
  ///
  /// In zh, this message translates to:
  /// **'MCP 已关闭'**
  String get settingsMcpDisabledToast;

  /// No description provided for @settingsMcpToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'MCP 开关失败'**
  String get settingsMcpToggleFailed;

  /// No description provided for @settingsCopiedAddress.
  ///
  /// In zh, this message translates to:
  /// **'已复制访问地址'**
  String get settingsCopiedAddress;

  /// No description provided for @settingsCopiedToken.
  ///
  /// In zh, this message translates to:
  /// **'已复制 Token'**
  String get settingsCopiedToken;

  /// No description provided for @settingsTokenRefreshed.
  ///
  /// In zh, this message translates to:
  /// **'已刷新 Token'**
  String get settingsTokenRefreshed;

  /// No description provided for @settingsTokenRefreshFailed.
  ///
  /// In zh, this message translates to:
  /// **'刷新 Token 失败'**
  String get settingsTokenRefreshFailed;

  /// No description provided for @settingsMcpLocalService.
  ///
  /// In zh, this message translates to:
  /// **'本机服务'**
  String get settingsMcpLocalService;

  /// No description provided for @settingsMcpAddress.
  ///
  /// In zh, this message translates to:
  /// **'地址'**
  String get settingsMcpAddress;

  /// No description provided for @settingsMcpToken.
  ///
  /// In zh, this message translates to:
  /// **'Token'**
  String get settingsMcpToken;

  /// No description provided for @settingsNotGenerated.
  ///
  /// In zh, this message translates to:
  /// **'未生成'**
  String get settingsNotGenerated;

  /// No description provided for @settingsCopyAddress.
  ///
  /// In zh, this message translates to:
  /// **'复制地址'**
  String get settingsCopyAddress;

  /// No description provided for @settingsCopyToken.
  ///
  /// In zh, this message translates to:
  /// **'复制 Token'**
  String get settingsCopyToken;

  /// No description provided for @settingsRefreshToken.
  ///
  /// In zh, this message translates to:
  /// **'刷新 Token'**
  String get settingsRefreshToken;

  /// No description provided for @settingsMcpSecurityNotice.
  ///
  /// In zh, this message translates to:
  /// **'请在同一局域网内使用 Authorization: Bearer <Token> 调用 /mcp/v1/task/vlm，避免将地址或 Token 暴露到公网。'**
  String get settingsMcpSecurityNotice;

  /// No description provided for @settingsInstalledAppsPermissionFailed.
  ///
  /// In zh, this message translates to:
  /// **'请求应用列表权限失败'**
  String get settingsInstalledAppsPermissionFailed;

  /// No description provided for @appearanceTitle.
  ///
  /// In zh, this message translates to:
  /// **'外观设置'**
  String get appearanceTitle;

  /// No description provided for @appearanceAutoSaving.
  ///
  /// In zh, this message translates to:
  /// **'正在自动保存…'**
  String get appearanceAutoSaving;

  /// No description provided for @appearanceAutosaveHint.
  ///
  /// In zh, this message translates to:
  /// **'更改会自动保存'**
  String get appearanceAutosaveHint;

  /// No description provided for @appearanceBackgroundSource.
  ///
  /// In zh, this message translates to:
  /// **'背景来源'**
  String get appearanceBackgroundSource;

  /// No description provided for @appearancePreview.
  ///
  /// In zh, this message translates to:
  /// **'效果预览'**
  String get appearancePreview;

  /// No description provided for @appearanceAdjustments.
  ///
  /// In zh, this message translates to:
  /// **'效果调整'**
  String get appearanceAdjustments;

  /// No description provided for @appearancePreviewChat.
  ///
  /// In zh, this message translates to:
  /// **'聊天'**
  String get appearancePreviewChat;

  /// No description provided for @appearancePreviewWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'工作区'**
  String get appearancePreviewWorkspace;

  /// No description provided for @appearanceEnableBackground.
  ///
  /// In zh, this message translates to:
  /// **'启用背景图'**
  String get appearanceEnableBackground;

  /// No description provided for @appearanceEnableBackgroundSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'同时作用于聊天页和 Workspace 页面，并自动保存'**
  String get appearanceEnableBackgroundSubtitle;

  /// No description provided for @appearanceSourceLocal.
  ///
  /// In zh, this message translates to:
  /// **'本地图片'**
  String get appearanceSourceLocal;

  /// No description provided for @appearanceSourceRemote.
  ///
  /// In zh, this message translates to:
  /// **'图片直链'**
  String get appearanceSourceRemote;

  /// No description provided for @appearanceNoLocalImage.
  ///
  /// In zh, this message translates to:
  /// **'尚未选择本地图片'**
  String get appearanceNoLocalImage;

  /// No description provided for @appearancePickImage.
  ///
  /// In zh, this message translates to:
  /// **'选择图片'**
  String get appearancePickImage;

  /// No description provided for @appearanceRepickImage.
  ///
  /// In zh, this message translates to:
  /// **'重新选择'**
  String get appearanceRepickImage;

  /// No description provided for @appearanceRemoteImageUrl.
  ///
  /// In zh, this message translates to:
  /// **'图片直链'**
  String get appearanceRemoteImageUrl;

  /// No description provided for @appearanceRemoteImageUrlHint.
  ///
  /// In zh, this message translates to:
  /// **'https://example.com/background.jpg'**
  String get appearanceRemoteImageUrlHint;

  /// No description provided for @appearanceBackgroundBlur.
  ///
  /// In zh, this message translates to:
  /// **'背景柔化'**
  String get appearanceBackgroundBlur;

  /// No description provided for @appearanceBackgroundBlurSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'调节图片上方蒙版的柔化程度'**
  String get appearanceBackgroundBlurSubtitle;

  /// No description provided for @appearanceOverlayIntensity.
  ///
  /// In zh, this message translates to:
  /// **'蒙版强度'**
  String get appearanceOverlayIntensity;

  /// No description provided for @appearanceOverlayIntensitySubtitle.
  ///
  /// In zh, this message translates to:
  /// **'增强统一蒙版，让页面元素更干净'**
  String get appearanceOverlayIntensitySubtitle;

  /// No description provided for @appearanceOverlayBrightness.
  ///
  /// In zh, this message translates to:
  /// **'蒙版明暗'**
  String get appearanceOverlayBrightness;

  /// No description provided for @appearanceOverlayBrightnessSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'提亮或压暗蒙版，不会直接修改原图'**
  String get appearanceOverlayBrightnessSubtitle;

  /// No description provided for @appearanceChatTextSize.
  ///
  /// In zh, this message translates to:
  /// **'聊天文本大小'**
  String get appearanceChatTextSize;

  /// No description provided for @appearanceChatTextSizeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'仅调整用户消息、AI 回复与思考区字号'**
  String get appearanceChatTextSizeSubtitle;

  /// No description provided for @appearanceTextColorTitle.
  ///
  /// In zh, this message translates to:
  /// **'聊天文本颜色'**
  String get appearanceTextColorTitle;

  /// No description provided for @appearanceTextColorSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'默认会自动跟随背景明暗，也可以改成固定颜色'**
  String get appearanceTextColorSubtitle;

  /// No description provided for @appearanceTextColorAuto.
  ///
  /// In zh, this message translates to:
  /// **'自动'**
  String get appearanceTextColorAuto;

  /// No description provided for @appearanceCustomColorLabel.
  ///
  /// In zh, this message translates to:
  /// **'自定义色号'**
  String get appearanceCustomColorLabel;

  /// No description provided for @appearanceCustomColorHint.
  ///
  /// In zh, this message translates to:
  /// **'#FFFFFF 或 #FF112233'**
  String get appearanceCustomColorHint;

  /// No description provided for @appearancePreviewTip.
  ///
  /// In zh, this message translates to:
  /// **'图片可直接在上方预览里拖动和双指缩放，预览会尽量贴近实际效果。'**
  String get appearancePreviewTip;

  /// No description provided for @appearanceColorWhite.
  ///
  /// In zh, this message translates to:
  /// **'白'**
  String get appearanceColorWhite;

  /// No description provided for @appearanceColorDarkGray.
  ///
  /// In zh, this message translates to:
  /// **'深灰'**
  String get appearanceColorDarkGray;

  /// No description provided for @appearanceColorLightBlue.
  ///
  /// In zh, this message translates to:
  /// **'浅蓝'**
  String get appearanceColorLightBlue;

  /// No description provided for @appearanceColorNavy.
  ///
  /// In zh, this message translates to:
  /// **'藏蓝'**
  String get appearanceColorNavy;

  /// No description provided for @appearanceColorTeal.
  ///
  /// In zh, this message translates to:
  /// **'青绿'**
  String get appearanceColorTeal;

  /// No description provided for @appearanceColorWarmYellow.
  ///
  /// In zh, this message translates to:
  /// **'暖黄'**
  String get appearanceColorWarmYellow;

  /// No description provided for @appearanceInvalidHttpUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入有效的 http(s) 图片直链'**
  String get appearanceInvalidHttpUrl;

  /// No description provided for @appearanceInvalidHexColor.
  ///
  /// In zh, this message translates to:
  /// **'请输入 #RRGGBB 或 #AARRGGBB'**
  String get appearanceInvalidHexColor;

  /// No description provided for @appearanceInvalidHexColorFormat.
  ///
  /// In zh, this message translates to:
  /// **'色号格式不正确'**
  String get appearanceInvalidHexColorFormat;

  /// No description provided for @appearancePickImageFailed.
  ///
  /// In zh, this message translates to:
  /// **'选择图片失败：{error}'**
  String appearancePickImageFailed(Object error);

  /// No description provided for @appearancePickLocalImageFirst.
  ///
  /// In zh, this message translates to:
  /// **'请先选择本地图片'**
  String get appearancePickLocalImageFirst;

  /// No description provided for @appearanceLocalImageMissing.
  ///
  /// In zh, this message translates to:
  /// **'本地图片不存在，请重新选择'**
  String get appearanceLocalImageMissing;

  /// No description provided for @appearanceAutosaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'自动保存失败：{error}'**
  String appearanceAutosaveFailed(Object error);

  /// No description provided for @chatToolCalling.
  ///
  /// In zh, this message translates to:
  /// **'正在调用工具'**
  String get chatToolCalling;

  /// No description provided for @chatFallbackReply.
  ///
  /// In zh, this message translates to:
  /// **'暂时无法生成回复，请重试。'**
  String get chatFallbackReply;

  /// No description provided for @chatPermissionRequired.
  ///
  /// In zh, this message translates to:
  /// **'执行任务前需要先开启权限'**
  String get chatPermissionRequired;

  /// No description provided for @chatPermissionRequiredWithNames.
  ///
  /// In zh, this message translates to:
  /// **'执行任务前，请先开启：{names}'**
  String chatPermissionRequiredWithNames(Object names);

  /// No description provided for @chatRecentTerminalOutputNotice.
  ///
  /// In zh, this message translates to:
  /// **'[只显示最近的部分终端输出]\n'**
  String get chatRecentTerminalOutputNotice;

  /// No description provided for @chatUserPrefix.
  ///
  /// In zh, this message translates to:
  /// **'用户: {text}\n'**
  String chatUserPrefix(Object text);

  /// No description provided for @permissionAccessibility.
  ///
  /// In zh, this message translates to:
  /// **'无障碍权限'**
  String get permissionAccessibility;

  /// No description provided for @permissionOverlay.
  ///
  /// In zh, this message translates to:
  /// **'悬浮窗权限'**
  String get permissionOverlay;

  /// No description provided for @permissionInstalledApps.
  ///
  /// In zh, this message translates to:
  /// **'应用列表读取权限'**
  String get permissionInstalledApps;

  /// No description provided for @permissionPublicStorage.
  ///
  /// In zh, this message translates to:
  /// **'公共文件访问'**
  String get permissionPublicStorage;

  /// No description provided for @browserOverlayTitle.
  ///
  /// In zh, this message translates to:
  /// **'Agent Browser'**
  String get browserOverlayTitle;

  /// No description provided for @browserOverlayClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭浏览器窗口'**
  String get browserOverlayClose;

  /// No description provided for @browserOverlayUnsupported.
  ///
  /// In zh, this message translates to:
  /// **'当前平台暂不支持浏览器工具视图'**
  String get browserOverlayUnsupported;

  /// No description provided for @networkErrorMessage.
  ///
  /// In zh, this message translates to:
  /// **'抱歉，刚刚网络开小差了。再发一次试试？'**
  String get networkErrorMessage;

  /// No description provided for @rateLimitErrorMessage.
  ///
  /// In zh, this message translates to:
  /// **'小万忙不过来了，等会儿再试试吧'**
  String get rateLimitErrorMessage;

  /// No description provided for @chatHistoryArchivedTitle.
  ///
  /// In zh, this message translates to:
  /// **'归档对话'**
  String get chatHistoryArchivedTitle;

  /// No description provided for @chatHistoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'聊天记录'**
  String get chatHistoryTitle;

  /// No description provided for @chatHistoryNoArchived.
  ///
  /// In zh, this message translates to:
  /// **'暂无归档对话'**
  String get chatHistoryNoArchived;

  /// No description provided for @chatHistoryEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无聊天记录'**
  String get chatHistoryEmpty;

  /// No description provided for @chatHistoryArchivedToast.
  ///
  /// In zh, this message translates to:
  /// **'已归档'**
  String get chatHistoryArchivedToast;

  /// No description provided for @chatHistoryUnarchivedToast.
  ///
  /// In zh, this message translates to:
  /// **'已移出归档'**
  String get chatHistoryUnarchivedToast;

  /// No description provided for @chatHistoryArchiveFailed.
  ///
  /// In zh, this message translates to:
  /// **'归档对话失败'**
  String get chatHistoryArchiveFailed;

  /// No description provided for @chatHistoryUnarchiveFailed.
  ///
  /// In zh, this message translates to:
  /// **'移出归档失败'**
  String get chatHistoryUnarchiveFailed;

  /// No description provided for @chatHistoryArchiveHint.
  ///
  /// In zh, this message translates to:
  /// **'左滑对话即可归档'**
  String get chatHistoryArchiveHint;

  /// No description provided for @homeDrawerArchive.
  ///
  /// In zh, this message translates to:
  /// **'归档对话'**
  String get homeDrawerArchive;

  /// No description provided for @homeDrawerNewChat.
  ///
  /// In zh, this message translates to:
  /// **'新对话'**
  String get homeDrawerNewChat;

  /// No description provided for @webchatNoChats.
  ///
  /// In zh, this message translates to:
  /// **'开始一个新的对话吧'**
  String get webchatNoChats;

  /// No description provided for @memoryCenterTitle.
  ///
  /// In zh, this message translates to:
  /// **'记忆中心'**
  String get memoryCenterTitle;

  /// No description provided for @memoryShortTermTitle.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆'**
  String get memoryShortTermTitle;

  /// No description provided for @memoryLongTermTitle.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆'**
  String get memoryLongTermTitle;

  /// No description provided for @memoryNoShortTerm.
  ///
  /// In zh, this message translates to:
  /// **'还没有短期记忆'**
  String get memoryNoShortTerm;

  /// No description provided for @memoryNoShortTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'会话中的过程性信息会沉淀到短期记忆，并在后续整理后转入长期记忆。'**
  String get memoryNoShortTermDesc;

  /// No description provided for @memoryFilteredNoShortTerm.
  ///
  /// In zh, this message translates to:
  /// **'当前筛选下还没有短期记忆'**
  String get memoryFilteredNoShortTerm;

  /// No description provided for @memoryFilteredNoShortTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'稍后再来看看，新的短期记忆会逐步出现。'**
  String get memoryFilteredNoShortTermDesc;

  /// No description provided for @memoryNoLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆还未初始化'**
  String get memoryNoLongTerm;

  /// No description provided for @memoryNoLongTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'记忆能力启用后，你的跨会话长期记忆会在这里持续沉淀。'**
  String get memoryNoLongTermDesc;

  /// No description provided for @memoryDeleteConfirmTitle.
  ///
  /// In zh, this message translates to:
  /// **'确定删除吗？'**
  String get memoryDeleteConfirmTitle;

  /// No description provided for @memoryDeleteWarning.
  ///
  /// In zh, this message translates to:
  /// **'删除后该内容将不可找回'**
  String get memoryDeleteWarning;

  /// No description provided for @memoryEditDisabled.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆暂不支持编辑'**
  String get memoryEditDisabled;

  /// No description provided for @memoryDeleteDisabled.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆暂不支持删除'**
  String get memoryDeleteDisabled;

  /// No description provided for @memoryGreeting.
  ///
  /// In zh, this message translates to:
  /// **'你好呀，\n欢迎回来，我们会在这里慢慢整理你的记忆。'**
  String get memoryGreeting;

  /// No description provided for @memorySelectedCount.
  ///
  /// In zh, this message translates to:
  /// **'已选择{n}项'**
  String memorySelectedCount(Object n);

  /// No description provided for @memoryDeselectAll.
  ///
  /// In zh, this message translates to:
  /// **'全不选'**
  String get memoryDeselectAll;

  /// No description provided for @memoryEditTitle.
  ///
  /// In zh, this message translates to:
  /// **'编辑记忆'**
  String get memoryEditTitle;

  /// No description provided for @memoryIdLabel.
  ///
  /// In zh, this message translates to:
  /// **'记忆 ID'**
  String get memoryIdLabel;

  /// No description provided for @memoryMatchScore.
  ///
  /// In zh, this message translates to:
  /// **'匹配度'**
  String get memoryMatchScore;

  /// No description provided for @memoryAdditionalInfo.
  ///
  /// In zh, this message translates to:
  /// **'附加信息'**
  String get memoryAdditionalInfo;

  /// No description provided for @memoryAddLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'新增长期记忆'**
  String get memoryAddLongTerm;

  /// No description provided for @memorySaveToLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'保存到长期记忆'**
  String get memorySaveToLongTerm;

  /// No description provided for @memoryLongTermAdded.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆已新增'**
  String get memoryLongTermAdded;

  /// No description provided for @memoryEditLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'编辑长期记忆'**
  String get memoryEditLongTerm;

  /// No description provided for @memorySaveChanges.
  ///
  /// In zh, this message translates to:
  /// **'保存修改'**
  String get memorySaveChanges;

  /// No description provided for @memoryDeleteLongTermConfirm.
  ///
  /// In zh, this message translates to:
  /// **'删除这条长期记忆？'**
  String get memoryDeleteLongTermConfirm;

  /// No description provided for @memoryLongTermDeleted.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆已删除'**
  String get memoryLongTermDeleted;

  /// No description provided for @memoryLongTermFailed.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆操作失败：{error}'**
  String memoryLongTermFailed(Object error);

  /// No description provided for @memoryNoMemories.
  ///
  /// In zh, this message translates to:
  /// **'暂无记忆'**
  String get memoryNoMemories;

  /// No description provided for @memoryNoMemoriesDesc.
  ///
  /// In zh, this message translates to:
  /// **'快去探索，添加喜欢的内容吧'**
  String get memoryNoMemoriesDesc;

  /// No description provided for @skillStoreTitle.
  ///
  /// In zh, this message translates to:
  /// **'技能仓库'**
  String get skillStoreTitle;

  /// No description provided for @skillBuiltin.
  ///
  /// In zh, this message translates to:
  /// **'内置'**
  String get skillBuiltin;

  /// No description provided for @skillUser.
  ///
  /// In zh, this message translates to:
  /// **'用户'**
  String get skillUser;

  /// No description provided for @skillInstalled.
  ///
  /// In zh, this message translates to:
  /// **'已安装'**
  String get skillInstalled;

  /// No description provided for @skillNotInstalled.
  ///
  /// In zh, this message translates to:
  /// **'未安装'**
  String get skillNotInstalled;

  /// No description provided for @skillEnabled.
  ///
  /// In zh, this message translates to:
  /// **'启用中'**
  String get skillEnabled;

  /// No description provided for @skillDisabled.
  ///
  /// In zh, this message translates to:
  /// **'已禁用'**
  String get skillDisabled;

  /// No description provided for @skillInstall.
  ///
  /// In zh, this message translates to:
  /// **'安装'**
  String get skillInstall;

  /// No description provided for @skillDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get skillDelete;

  /// No description provided for @skillEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无已接入的技能'**
  String get skillEmpty;

  /// No description provided for @skillNoDescription.
  ///
  /// In zh, this message translates to:
  /// **'暂无描述'**
  String get skillNoDescription;

  /// No description provided for @skillBuiltinRemovedDesc.
  ///
  /// In zh, this message translates to:
  /// **'该内置技能已从工作区移除，可随时重新安装。'**
  String get skillBuiltinRemovedDesc;

  /// No description provided for @skillDeleteTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除技能'**
  String get skillDeleteTitle;

  /// No description provided for @skillDeleteConfirmMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认删除\"{name}\"？'**
  String skillDeleteConfirmMsg(Object name);

  /// No description provided for @skillDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除'**
  String get skillDeleted;

  /// No description provided for @skillDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除失败'**
  String get skillDeleteFailed;

  /// No description provided for @skillInstalledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已安装 {name}'**
  String skillInstalledMsg(Object name);

  /// No description provided for @skillInstallFailed.
  ///
  /// In zh, this message translates to:
  /// **'安装失败'**
  String get skillInstallFailed;

  /// No description provided for @skillEnabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已启用 {name}'**
  String skillEnabledMsg(Object name);

  /// No description provided for @skillDisabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已禁用 {name}'**
  String skillDisabledMsg(Object name);

  /// No description provided for @skillToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换失败'**
  String get skillToggleFailed;

  /// No description provided for @skillLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载技能仓库失败'**
  String get skillLoadFailed;

  /// No description provided for @trajectoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'轨迹'**
  String get trajectoryTitle;

  /// No description provided for @trajectoryNoRecords.
  ///
  /// In zh, this message translates to:
  /// **'暂无执行记录'**
  String get trajectoryNoRecords;

  /// No description provided for @trajectoryNoRecordsDesc.
  ///
  /// In zh, this message translates to:
  /// **'小万为你执行的视觉任务，都会在此展示'**
  String get trajectoryNoRecordsDesc;

  /// No description provided for @trajectoryAll.
  ///
  /// In zh, this message translates to:
  /// **'全部'**
  String get trajectoryAll;

  /// No description provided for @trajectoryTaskRecords.
  ///
  /// In zh, this message translates to:
  /// **'任务记录'**
  String get trajectoryTaskRecords;

  /// No description provided for @trajectorySelectedCount.
  ///
  /// In zh, this message translates to:
  /// **'已选择{n}项'**
  String trajectorySelectedCount(Object n);

  /// No description provided for @trajectoryUnknownDate.
  ///
  /// In zh, this message translates to:
  /// **'未知日期'**
  String get trajectoryUnknownDate;

  /// No description provided for @trajectoryThreeDaysAgo.
  ///
  /// In zh, this message translates to:
  /// **'三天前'**
  String get trajectoryThreeDaysAgo;

  /// No description provided for @executionHistoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'执行历史'**
  String get executionHistoryTitle;

  /// No description provided for @executionHistorySubtitle.
  ///
  /// In zh, this message translates to:
  /// **'近3次任务执行历史'**
  String get executionHistorySubtitle;

  /// No description provided for @executionHistoryEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无执行历史'**
  String get executionHistoryEmpty;

  /// No description provided for @executionHistoryTaskLabel.
  ///
  /// In zh, this message translates to:
  /// **'{option}任务'**
  String executionHistoryTaskLabel(Object option);

  /// No description provided for @modelProviderConfigTitle.
  ///
  /// In zh, this message translates to:
  /// **'Provider 配置'**
  String get modelProviderConfigTitle;

  /// No description provided for @modelProviderConfigDesc.
  ///
  /// In zh, this message translates to:
  /// **'新增、切换并维护模型服务提供商的名称、地址与密钥。'**
  String get modelProviderConfigDesc;

  /// No description provided for @modelProviderName.
  ///
  /// In zh, this message translates to:
  /// **'Provider 名称'**
  String get modelProviderName;

  /// No description provided for @modelProviderNameHint.
  ///
  /// In zh, this message translates to:
  /// **'例如：DeepSeek'**
  String get modelProviderNameHint;

  /// No description provided for @modelProviderBaseUrlHint.
  ///
  /// In zh, this message translates to:
  /// **'末尾加 # 可禁用自动补全请求路径'**
  String get modelProviderBaseUrlHint;

  /// No description provided for @modelProviderApiKeyHint.
  ///
  /// In zh, this message translates to:
  /// **'未填写 API Key 时，会以无鉴权方式请求 Provider。'**
  String get modelProviderApiKeyHint;

  /// No description provided for @modelListTitle.
  ///
  /// In zh, this message translates to:
  /// **'模型列表'**
  String get modelListTitle;

  /// No description provided for @modelListDesc.
  ///
  /// In zh, this message translates to:
  /// **'支持手动补充模型，也可从当前 Provider 拉取远端模型清单。'**
  String get modelListDesc;

  /// No description provided for @modelListCount.
  ///
  /// In zh, this message translates to:
  /// **'共 {count} 个模型'**
  String modelListCount(Object count);

  /// No description provided for @modelAddPrompt.
  ///
  /// In zh, this message translates to:
  /// **'请添加模型！'**
  String get modelAddPrompt;

  /// No description provided for @modelBuiltinProvider.
  ///
  /// In zh, this message translates to:
  /// **'内置 Provider'**
  String get modelBuiltinProvider;

  /// No description provided for @modelIdEmpty.
  ///
  /// In zh, this message translates to:
  /// **'模型 ID 不能为空且不能以 scene. 开头'**
  String get modelIdEmpty;

  /// No description provided for @modelAlreadyExists.
  ///
  /// In zh, this message translates to:
  /// **'模型已存在'**
  String get modelAlreadyExists;

  /// No description provided for @modelAdded.
  ///
  /// In zh, this message translates to:
  /// **'已添加模型'**
  String get modelAdded;

  /// No description provided for @modelDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除模型'**
  String get modelDeleted;

  /// No description provided for @modelDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除模型失败'**
  String get modelDeleteFailed;

  /// No description provided for @modelIdHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入模型 ID'**
  String get modelIdHint;

  /// No description provided for @modelAddProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'新增 Provider'**
  String get modelAddProviderTitle;

  /// No description provided for @modelAddButton.
  ///
  /// In zh, this message translates to:
  /// **'新增'**
  String get modelAddButton;

  /// No description provided for @modelProviderAdded.
  ///
  /// In zh, this message translates to:
  /// **'已新增 Provider'**
  String get modelProviderAdded;

  /// No description provided for @modelProviderAddFailed.
  ///
  /// In zh, this message translates to:
  /// **'新增 Provider 失败：{error}'**
  String modelProviderAddFailed(Object error);

  /// No description provided for @modelDeleteProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除 Provider'**
  String get modelDeleteProviderTitle;

  /// No description provided for @modelDeleteProviderMsg.
  ///
  /// In zh, this message translates to:
  /// **'确定删除\"{name}\"吗？场景绑定会保留，但需要重新选择可用 Provider。'**
  String modelDeleteProviderMsg(Object name);

  /// No description provided for @modelProviderDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除 Provider'**
  String get modelProviderDeleted;

  /// No description provided for @modelProviderDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除 Provider 失败：{error}'**
  String modelProviderDeleteFailed(Object error);

  /// No description provided for @modelProviderLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载模型提供商配置失败'**
  String get modelProviderLoadFailed;

  /// No description provided for @modelProviderSwitchFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换 Provider 失败：{error}'**
  String modelProviderSwitchFailed(Object error);

  /// No description provided for @modelProviderBaseUrlRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先填写 Base URL'**
  String get modelProviderBaseUrlRequired;

  /// No description provided for @modelProviderInvalidBaseUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入有效的 http(s) Base URL'**
  String get modelProviderInvalidBaseUrl;

  /// No description provided for @modelProviderFetchedModels.
  ///
  /// In zh, this message translates to:
  /// **'已获取 {count} 个模型'**
  String modelProviderFetchedModels(Object count);

  /// No description provided for @modelProviderFetchFailed.
  ///
  /// In zh, this message translates to:
  /// **'拉取模型列表失败：{error}'**
  String modelProviderFetchFailed(Object error);

  /// No description provided for @sceneModelMapping.
  ///
  /// In zh, this message translates to:
  /// **'场景映射'**
  String get sceneModelMapping;

  /// No description provided for @sceneModelMappingDesc.
  ///
  /// In zh, this message translates to:
  /// **'按场景绑定 Provider 与模型，未绑定的场景会继续使用默认模型。'**
  String get sceneModelMappingDesc;

  /// No description provided for @sceneModelRefreshList.
  ///
  /// In zh, this message translates to:
  /// **'刷新模型列表'**
  String get sceneModelRefreshList;

  /// No description provided for @sceneModelSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；顶部搜索框固定不随列表滚动。'**
  String get sceneModelSearchHint;

  /// No description provided for @sceneModelNoScenes.
  ///
  /// In zh, this message translates to:
  /// **'暂无可配置场景'**
  String get sceneModelNoScenes;

  /// No description provided for @sceneModelLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载场景模型配置失败'**
  String get sceneModelLoadFailed;

  /// No description provided for @sceneModelPartialUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'部分模型已更新，但这些 Provider 刷新失败：{profiles}'**
  String sceneModelPartialUpdateFailed(Object profiles);

  /// No description provided for @sceneModelUpdatedModels.
  ///
  /// In zh, this message translates to:
  /// **'已更新 {count} 个模型'**
  String sceneModelUpdatedModels(Object count);

  /// No description provided for @sceneModelRefreshFailed.
  ///
  /// In zh, this message translates to:
  /// **'刷新模型列表失败：{error}'**
  String sceneModelRefreshFailed(Object error);

  /// No description provided for @sceneModelInvalidModelId.
  ///
  /// In zh, this message translates to:
  /// **'模型 ID 不能以 scene. 开头'**
  String get sceneModelInvalidModelId;

  /// No description provided for @sceneModelBoundToast.
  ///
  /// In zh, this message translates to:
  /// **'已将 {scene} 绑定到 {model}'**
  String sceneModelBoundToast(Object scene, Object model);

  /// No description provided for @sceneModelSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存 {scene} 配置失败：{error}'**
  String sceneModelSaveFailed(Object scene, Object error);

  /// No description provided for @sceneModelBindingCleared.
  ///
  /// In zh, this message translates to:
  /// **'已清除 {scene} 的绑定'**
  String sceneModelBindingCleared(Object scene);

  /// No description provided for @sceneModelDefaultRestored.
  ///
  /// In zh, this message translates to:
  /// **'{scene} 已恢复为默认模型'**
  String sceneModelDefaultRestored(Object scene);

  /// No description provided for @sceneModelClearFailed.
  ///
  /// In zh, this message translates to:
  /// **'清除 {scene} 配置失败：{error}'**
  String sceneModelClearFailed(Object scene, Object error);

  /// No description provided for @sceneVoiceSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存语音配置失败：{error}'**
  String sceneVoiceSaveFailed(Object error);

  /// No description provided for @localModelsTitle.
  ///
  /// In zh, this message translates to:
  /// **'本地模型'**
  String get localModelsTitle;

  /// No description provided for @localModelsAutoPreheat.
  ///
  /// In zh, this message translates to:
  /// **'打开 App 时自动预热'**
  String get localModelsAutoPreheat;

  /// No description provided for @localModelsAutoPreheatDesc.
  ///
  /// In zh, this message translates to:
  /// **'进入应用后自动启动本地服务，并直接加载当前模型。'**
  String get localModelsAutoPreheatDesc;

  /// No description provided for @localModelsInstalled.
  ///
  /// In zh, this message translates to:
  /// **'已安装模型'**
  String get localModelsInstalled;

  /// No description provided for @localModelsInstalledDesc.
  ///
  /// In zh, this message translates to:
  /// **'搜索、切换默认模型或删除当前设备上的模型。'**
  String get localModelsInstalledDesc;

  /// No description provided for @localModelsSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索模型名称、ID 或标签'**
  String get localModelsSearchHint;

  /// No description provided for @localModelsEmpty.
  ///
  /// In zh, this message translates to:
  /// **'还没有可用的本地模型'**
  String get localModelsEmpty;

  /// No description provided for @localModelsEmptyDesc.
  ///
  /// In zh, this message translates to:
  /// **'先去模型市场下载一个模型，或者手动放置 MNN 模型目录。'**
  String get localModelsEmptyDesc;

  /// No description provided for @localModelsServiceControl.
  ///
  /// In zh, this message translates to:
  /// **'服务控制'**
  String get localModelsServiceControl;

  /// No description provided for @localModelsServiceControlDesc.
  ///
  /// In zh, this message translates to:
  /// **'切换推理后端、当前模型和监听端口。'**
  String get localModelsServiceControlDesc;

  /// No description provided for @localModelsInferenceBackend.
  ///
  /// In zh, this message translates to:
  /// **'推理后端'**
  String get localModelsInferenceBackend;

  /// No description provided for @localModelsCurrentModel.
  ///
  /// In zh, this message translates to:
  /// **'当前模型'**
  String get localModelsCurrentModel;

  /// No description provided for @localModelsCurrentModelHint.
  ///
  /// In zh, this message translates to:
  /// **'启动服务时会加载这里选择的模型。'**
  String get localModelsCurrentModelHint;

  /// No description provided for @localModelsNoAvailableModels.
  ///
  /// In zh, this message translates to:
  /// **'暂无可用模型'**
  String get localModelsNoAvailableModels;

  /// No description provided for @localModelsSelectModel.
  ///
  /// In zh, this message translates to:
  /// **'选择一个模型'**
  String get localModelsSelectModel;

  /// No description provided for @localModelsServicePort.
  ///
  /// In zh, this message translates to:
  /// **'服务端口'**
  String get localModelsServicePort;

  /// No description provided for @localModelsServicePortHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入端口号'**
  String get localModelsServicePortHint;

  /// No description provided for @localModelsCurrentlyLoaded.
  ///
  /// In zh, this message translates to:
  /// **'当前已加载'**
  String get localModelsCurrentlyLoaded;

  /// No description provided for @localModelsAutoPreheatSection.
  ///
  /// In zh, this message translates to:
  /// **'自动预热'**
  String get localModelsAutoPreheatSection;

  /// No description provided for @localModelsAutoPreheatSectionDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开 App 后自动启动本地服务并加载当前模型。'**
  String get localModelsAutoPreheatSectionDesc;

  /// No description provided for @localModelsLocalInference.
  ///
  /// In zh, this message translates to:
  /// **'本地推理模型'**
  String get localModelsLocalInference;

  /// No description provided for @localModelsStopping.
  ///
  /// In zh, this message translates to:
  /// **'停止中…'**
  String get localModelsStopping;

  /// No description provided for @localModelsStarting.
  ///
  /// In zh, this message translates to:
  /// **'启动中…'**
  String get localModelsStarting;

  /// No description provided for @localModelsStopService.
  ///
  /// In zh, this message translates to:
  /// **'停止服务'**
  String get localModelsStopService;

  /// No description provided for @localModelsStartService.
  ///
  /// In zh, this message translates to:
  /// **'启动服务'**
  String get localModelsStartService;

  /// No description provided for @localModelsConfigLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'无法加载本地模型配置'**
  String get localModelsConfigLoadFailed;

  /// No description provided for @localModelsConfigLoadFailedDesc.
  ///
  /// In zh, this message translates to:
  /// **'请稍后重试。'**
  String get localModelsConfigLoadFailedDesc;

  /// No description provided for @localModelsInstalledLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载已安装模型失败'**
  String get localModelsInstalledLoadFailed;

  /// No description provided for @localModelsMarketLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载模型市场失败'**
  String get localModelsMarketLoadFailed;

  /// No description provided for @localModelsSwitchBackendFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换推理后端失败'**
  String get localModelsSwitchBackendFailed;

  /// No description provided for @localModelsActiveModelUpdated.
  ///
  /// In zh, this message translates to:
  /// **'已更新当前模型'**
  String get localModelsActiveModelUpdated;

  /// No description provided for @localModelsSetActiveFailed.
  ///
  /// In zh, this message translates to:
  /// **'设置当前模型失败'**
  String get localModelsSetActiveFailed;

  /// No description provided for @localModelsPortInvalid.
  ///
  /// In zh, this message translates to:
  /// **'端口号无效'**
  String get localModelsPortInvalid;

  /// No description provided for @localModelsPortUpdated.
  ///
  /// In zh, this message translates to:
  /// **'已更新服务端口'**
  String get localModelsPortUpdated;

  /// No description provided for @localModelsPortSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存端口失败'**
  String get localModelsPortSaveFailed;

  /// No description provided for @localModelsAutoPreheatSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存自动预热设置失败'**
  String get localModelsAutoPreheatSaveFailed;

  /// No description provided for @localModelsDownloadSourceSwitchFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换下载源失败'**
  String get localModelsDownloadSourceSwitchFailed;

  /// No description provided for @localModelsServiceStarted.
  ///
  /// In zh, this message translates to:
  /// **'本地服务已启动'**
  String get localModelsServiceStarted;

  /// No description provided for @localModelsStartFailed.
  ///
  /// In zh, this message translates to:
  /// **'启动服务失败'**
  String get localModelsStartFailed;

  /// No description provided for @localModelsStopFailed.
  ///
  /// In zh, this message translates to:
  /// **'停止服务失败'**
  String get localModelsStopFailed;

  /// No description provided for @localModelsServiceStopped.
  ///
  /// In zh, this message translates to:
  /// **'本地服务已停止'**
  String get localModelsServiceStopped;

  /// No description provided for @localModelsDownloadStartFailed.
  ///
  /// In zh, this message translates to:
  /// **'启动下载失败'**
  String get localModelsDownloadStartFailed;

  /// No description provided for @localModelsDownloadPauseFailed.
  ///
  /// In zh, this message translates to:
  /// **'暂停下载失败'**
  String get localModelsDownloadPauseFailed;

  /// No description provided for @localModelsDownloadStartedToast.
  ///
  /// In zh, this message translates to:
  /// **'开始下载：{modelName}'**
  String localModelsDownloadStartedToast(String modelName);

  /// No description provided for @localModelsDownloadPausedToast.
  ///
  /// In zh, this message translates to:
  /// **'下载已暂停：{modelName}'**
  String localModelsDownloadPausedToast(String modelName);

  /// No description provided for @localModelsDownloadCompletedToast.
  ///
  /// In zh, this message translates to:
  /// **'下载完成：{modelName}'**
  String localModelsDownloadCompletedToast(String modelName);

  /// No description provided for @localModelsDownloadFailedToast.
  ///
  /// In zh, this message translates to:
  /// **'下载失败：{modelName} — {reason}'**
  String localModelsDownloadFailedToast(String modelName, String reason);

  /// No description provided for @localModelsDownloadCancelledToast.
  ///
  /// In zh, this message translates to:
  /// **'下载已取消：{modelName} — {reason}'**
  String localModelsDownloadCancelledToast(String modelName, String reason);

  /// No description provided for @localModelsDownloadErrorUnknown.
  ///
  /// In zh, this message translates to:
  /// **'未知错误'**
  String get localModelsDownloadErrorUnknown;

  /// No description provided for @localModelsFilterAndSource.
  ///
  /// In zh, this message translates to:
  /// **'筛选与来源'**
  String get localModelsFilterAndSource;

  /// No description provided for @localModelsFilterAndSourceDesc.
  ///
  /// In zh, this message translates to:
  /// **'切换推理后端和下载源，影响当前市场列表。'**
  String get localModelsFilterAndSourceDesc;

  /// No description provided for @localModelsDownloadSource.
  ///
  /// In zh, this message translates to:
  /// **'下载源'**
  String get localModelsDownloadSource;

  /// No description provided for @localModelsSelectDownloadSource.
  ///
  /// In zh, this message translates to:
  /// **'选择下载源'**
  String get localModelsSelectDownloadSource;

  /// No description provided for @localModelsMarketModels.
  ///
  /// In zh, this message translates to:
  /// **'市场模型'**
  String get localModelsMarketModels;

  /// No description provided for @localModelsMarketModelsDesc.
  ///
  /// In zh, this message translates to:
  /// **'搜索、下载、暂停或删除市场中的模型。'**
  String get localModelsMarketModelsDesc;

  /// No description provided for @localModelsMarketSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索市场模型名称、描述或标签'**
  String get localModelsMarketSearchHint;

  /// No description provided for @localModelsMarketEmpty.
  ///
  /// In zh, this message translates to:
  /// **'模型市场暂时为空'**
  String get localModelsMarketEmpty;

  /// No description provided for @localModelsMarketEmptyDesc.
  ///
  /// In zh, this message translates to:
  /// **'请检查下载源，或者下拉刷新重试。'**
  String get localModelsMarketEmptyDesc;

  /// No description provided for @localModelsCurrentDefault.
  ///
  /// In zh, this message translates to:
  /// **'当前默认'**
  String get localModelsCurrentDefault;

  /// No description provided for @localModelsLoaded.
  ///
  /// In zh, this message translates to:
  /// **'已加载'**
  String get localModelsLoaded;

  /// No description provided for @localModelsFileSize.
  ///
  /// In zh, this message translates to:
  /// **'文件大小'**
  String get localModelsFileSize;

  /// No description provided for @localModelsModelDir.
  ///
  /// In zh, this message translates to:
  /// **'模型目录'**
  String get localModelsModelDir;

  /// No description provided for @localModelsManualDir.
  ///
  /// In zh, this message translates to:
  /// **'这是手动放置目录，App 内不提供删除。'**
  String get localModelsManualDir;

  /// No description provided for @localModelsOmniInferLoadable.
  ///
  /// In zh, this message translates to:
  /// **'该模型可由 OmniInfer 直接加载。'**
  String get localModelsOmniInferLoadable;

  /// No description provided for @localModelsSetAsCurrent.
  ///
  /// In zh, this message translates to:
  /// **'设为当前'**
  String get localModelsSetAsCurrent;

  /// No description provided for @localModelsDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get localModelsDelete;

  /// No description provided for @localModelsHasUpdate.
  ///
  /// In zh, this message translates to:
  /// **'有更新'**
  String get localModelsHasUpdate;

  /// No description provided for @localModelsStage.
  ///
  /// In zh, this message translates to:
  /// **'阶段'**
  String get localModelsStage;

  /// No description provided for @localModelsErrorInfo.
  ///
  /// In zh, this message translates to:
  /// **'错误信息'**
  String get localModelsErrorInfo;

  /// No description provided for @localModelsResumeDownload.
  ///
  /// In zh, this message translates to:
  /// **'继续下载'**
  String get localModelsResumeDownload;

  /// No description provided for @localModelsRetryDownload.
  ///
  /// In zh, this message translates to:
  /// **'重新下载'**
  String get localModelsRetryDownload;

  /// No description provided for @localModelsDownloadModel.
  ///
  /// In zh, this message translates to:
  /// **'下载模型'**
  String get localModelsDownloadModel;

  /// No description provided for @localModelsPause.
  ///
  /// In zh, this message translates to:
  /// **'暂停'**
  String get localModelsPause;

  /// No description provided for @localModelsDeleteOldVersion.
  ///
  /// In zh, this message translates to:
  /// **'删除旧版本'**
  String get localModelsDeleteOldVersion;

  /// No description provided for @localModelsTabService.
  ///
  /// In zh, this message translates to:
  /// **'服务'**
  String get localModelsTabService;

  /// No description provided for @localModelsTabMarket.
  ///
  /// In zh, this message translates to:
  /// **'市场'**
  String get localModelsTabMarket;

  /// No description provided for @localModelsRefresh.
  ///
  /// In zh, this message translates to:
  /// **'刷新'**
  String get localModelsRefresh;

  /// No description provided for @localModelsDownloadPreparing.
  ///
  /// In zh, this message translates to:
  /// **'准备中'**
  String get localModelsDownloadPreparing;

  /// No description provided for @localModelsDownloading.
  ///
  /// In zh, this message translates to:
  /// **'下载中'**
  String get localModelsDownloading;

  /// No description provided for @localModelsDownloadPaused.
  ///
  /// In zh, this message translates to:
  /// **'已暂停'**
  String get localModelsDownloadPaused;

  /// No description provided for @localModelsDownloadCompleted.
  ///
  /// In zh, this message translates to:
  /// **'已完成'**
  String get localModelsDownloadCompleted;

  /// No description provided for @localModelsDownloadFailed.
  ///
  /// In zh, this message translates to:
  /// **'下载失败'**
  String get localModelsDownloadFailed;

  /// No description provided for @localModelsDownloadCancelled.
  ///
  /// In zh, this message translates to:
  /// **'已取消'**
  String get localModelsDownloadCancelled;

  /// No description provided for @localModelsNotDownloaded.
  ///
  /// In zh, this message translates to:
  /// **'未下载'**
  String get localModelsNotDownloaded;

  /// No description provided for @localModelsImportFromDevice.
  ///
  /// In zh, this message translates to:
  /// **'从设备导入'**
  String get localModelsImportFromDevice;

  /// No description provided for @localModelsImportSuccess.
  ///
  /// In zh, this message translates to:
  /// **'模型导入成功'**
  String get localModelsImportSuccess;

  /// No description provided for @localModelsImportFailed.
  ///
  /// In zh, this message translates to:
  /// **'导入失败：{reason}'**
  String localModelsImportFailed(String reason);

  /// No description provided for @localModelsImporting.
  ///
  /// In zh, this message translates to:
  /// **'正在导入 {modelId}...'**
  String localModelsImporting(String modelId);

  /// No description provided for @alarmSaved.
  ///
  /// In zh, this message translates to:
  /// **'闹钟设置已保存'**
  String get alarmSaved;

  /// No description provided for @alarmRingtoneSource.
  ///
  /// In zh, this message translates to:
  /// **'铃声来源'**
  String get alarmRingtoneSource;

  /// No description provided for @alarmSystemDefault.
  ///
  /// In zh, this message translates to:
  /// **'系统默认铃声'**
  String get alarmSystemDefault;

  /// No description provided for @alarmSystemDefaultDesc.
  ///
  /// In zh, this message translates to:
  /// **'无需额外配置，兼容性最好'**
  String get alarmSystemDefaultDesc;

  /// No description provided for @alarmLocalMp3.
  ///
  /// In zh, this message translates to:
  /// **'本地 mp3'**
  String get alarmLocalMp3;

  /// No description provided for @alarmLocalMp3Desc.
  ///
  /// In zh, this message translates to:
  /// **'选择手机内 mp3 作为闹钟铃声'**
  String get alarmLocalMp3Desc;

  /// No description provided for @alarmMp3Url.
  ///
  /// In zh, this message translates to:
  /// **'mp3 直链'**
  String get alarmMp3Url;

  /// No description provided for @alarmMp3UrlDesc.
  ///
  /// In zh, this message translates to:
  /// **'使用 http(s) 直链播放在线 mp3'**
  String get alarmMp3UrlDesc;

  /// No description provided for @alarmAudioPermissionDenied.
  ///
  /// In zh, this message translates to:
  /// **'读取音频权限未授予'**
  String get alarmAudioPermissionDenied;

  /// No description provided for @alarmInvalidFilePath.
  ///
  /// In zh, this message translates to:
  /// **'文件路径无效，请重新选择'**
  String get alarmInvalidFilePath;

  /// No description provided for @alarmSelectLocalFirst.
  ///
  /// In zh, this message translates to:
  /// **'请先选择本地 mp3 文件'**
  String get alarmSelectLocalFirst;

  /// No description provided for @alarmEnterHttpsUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入 http(s) 开头的 mp3 直链'**
  String get alarmEnterHttpsUrl;

  /// No description provided for @alarmLocalFile.
  ///
  /// In zh, this message translates to:
  /// **'本地文件'**
  String get alarmLocalFile;

  /// No description provided for @alarmSelectMp3.
  ///
  /// In zh, this message translates to:
  /// **'选择 mp3 文件'**
  String get alarmSelectMp3;

  /// No description provided for @authorizePageTitle.
  ///
  /// In zh, this message translates to:
  /// **'应用权限授权'**
  String get authorizePageTitle;

  /// No description provided for @authorizeReceiveNotifications.
  ///
  /// In zh, this message translates to:
  /// **'接收消息通知'**
  String get authorizeReceiveNotifications;

  /// No description provided for @authorizeNotificationsDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开后可以及时了解任务进展'**
  String get authorizeNotificationsDesc;

  /// No description provided for @companionPermissionManagement.
  ///
  /// In zh, this message translates to:
  /// **'陪伴权限管理'**
  String get companionPermissionManagement;

  /// No description provided for @companionPermissionDesc.
  ///
  /// In zh, this message translates to:
  /// **'关闭对应的授权后，小万仍会显示，但不会展示任务执行内容'**
  String get companionPermissionDesc;

  /// No description provided for @companionPermissionNote.
  ///
  /// In zh, this message translates to:
  /// **'权限说明'**
  String get companionPermissionNote;

  /// No description provided for @companionAuthorizedApps.
  ///
  /// In zh, this message translates to:
  /// **'授权应用'**
  String get companionAuthorizedApps;

  /// No description provided for @storageUsageTitle.
  ///
  /// In zh, this message translates to:
  /// **'存储占用'**
  String get storageUsageTitle;

  /// No description provided for @storageUsageSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'查看空间占用明细，支持分项清理'**
  String get storageUsageSubtitle;

  /// No description provided for @storageAnalyzeFailed.
  ///
  /// In zh, this message translates to:
  /// **'存储分析失败，请重试'**
  String get storageAnalyzeFailed;

  /// No description provided for @storageCategoryCleaned.
  ///
  /// In zh, this message translates to:
  /// **'已清理{name}，释放 {size}'**
  String storageCategoryCleaned(Object name, Object size);

  /// No description provided for @storageCleanFailed.
  ///
  /// In zh, this message translates to:
  /// **'清理失败，请稍后重试'**
  String get storageCleanFailed;

  /// No description provided for @storageCleanCategory.
  ///
  /// In zh, this message translates to:
  /// **'清理{name}'**
  String storageCleanCategory(Object name);

  /// No description provided for @storageCleanConfirmMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认清理该分类数据吗？'**
  String get storageCleanConfirmMsg;

  /// No description provided for @storageCleanScope.
  ///
  /// In zh, this message translates to:
  /// **'清理范围'**
  String get storageCleanScope;

  /// No description provided for @storageCleanAll.
  ///
  /// In zh, this message translates to:
  /// **'全部'**
  String get storageCleanAll;

  /// No description provided for @storageClean7Days.
  ///
  /// In zh, this message translates to:
  /// **'7天前'**
  String get storageClean7Days;

  /// No description provided for @storageClean30Days.
  ///
  /// In zh, this message translates to:
  /// **'30天前'**
  String get storageClean30Days;

  /// No description provided for @storageStrategyName.
  ///
  /// In zh, this message translates to:
  /// **'执行策略：{name}'**
  String storageStrategyName(Object name);

  /// No description provided for @storageStrategyDone.
  ///
  /// In zh, this message translates to:
  /// **'策略执行完成，释放 {size}'**
  String storageStrategyDone(Object size);

  /// No description provided for @storageStrategyPartialDone.
  ///
  /// In zh, this message translates to:
  /// **'策略完成，释放 {size}，{count} 项未完全成功'**
  String storageStrategyPartialDone(Object count, Object size);

  /// No description provided for @storageStrategyFailed.
  ///
  /// In zh, this message translates to:
  /// **'策略执行失败，请稍后重试'**
  String get storageStrategyFailed;

  /// No description provided for @storageLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载失败'**
  String get storageLoadFailed;

  /// No description provided for @storageReanalyze.
  ///
  /// In zh, this message translates to:
  /// **'重新分析'**
  String get storageReanalyze;

  /// No description provided for @storageTotalUsage.
  ///
  /// In zh, this message translates to:
  /// **'总占用'**
  String get storageTotalUsage;

  /// No description provided for @storageAppSize.
  ///
  /// In zh, this message translates to:
  /// **'应用大小'**
  String get storageAppSize;

  /// No description provided for @storageUserData.
  ///
  /// In zh, this message translates to:
  /// **'用户数据'**
  String get storageUserData;

  /// No description provided for @storageCleanable.
  ///
  /// In zh, this message translates to:
  /// **'可清理'**
  String get storageCleanable;

  /// No description provided for @storageStatsSource.
  ///
  /// In zh, this message translates to:
  /// **'统计口径：{source}'**
  String storageStatsSource(Object source);

  /// No description provided for @storagePackageName.
  ///
  /// In zh, this message translates to:
  /// **'当前包名：{name}'**
  String storagePackageName(Object name);

  /// No description provided for @storageTrendFirst.
  ///
  /// In zh, this message translates to:
  /// **'这是首次分析，后续将展示占用变化趋势'**
  String get storageTrendFirst;

  /// No description provided for @storageSmartCleanup.
  ///
  /// In zh, this message translates to:
  /// **'智能清理策略'**
  String get storageSmartCleanup;

  /// No description provided for @storageExecute.
  ///
  /// In zh, this message translates to:
  /// **'执行'**
  String get storageExecute;

  /// No description provided for @storageUsageAnalysis.
  ///
  /// In zh, this message translates to:
  /// **'占用分析'**
  String get storageUsageAnalysis;

  /// No description provided for @storageClean.
  ///
  /// In zh, this message translates to:
  /// **'清理'**
  String get storageClean;

  /// No description provided for @storageRiskLow.
  ///
  /// In zh, this message translates to:
  /// **'低风险'**
  String get storageRiskLow;

  /// No description provided for @storageRiskCaution.
  ///
  /// In zh, this message translates to:
  /// **'谨慎'**
  String get storageRiskCaution;

  /// No description provided for @storageRiskHigh.
  ///
  /// In zh, this message translates to:
  /// **'高风险'**
  String get storageRiskHigh;

  /// No description provided for @storageReadOnly.
  ///
  /// In zh, this message translates to:
  /// **'只读'**
  String get storageReadOnly;

  /// No description provided for @storageSystemStats.
  ///
  /// In zh, this message translates to:
  /// **'系统统计（与系统设置更接近）'**
  String get storageSystemStats;

  /// No description provided for @storageDirectoryScan.
  ///
  /// In zh, this message translates to:
  /// **'目录扫描估算'**
  String get storageDirectoryScan;

  /// No description provided for @storageAdditionalInfo.
  ///
  /// In zh, this message translates to:
  /// **'附加信息'**
  String get storageAdditionalInfo;

  /// No description provided for @storageCatAppBinary.
  ///
  /// In zh, this message translates to:
  /// **'应用安装包'**
  String get storageCatAppBinary;

  /// No description provided for @storageCatAppBinaryDesc.
  ///
  /// In zh, this message translates to:
  /// **'应用安装文件占用（APK/AAB split）'**
  String get storageCatAppBinaryDesc;

  /// No description provided for @storageCatCache.
  ///
  /// In zh, this message translates to:
  /// **'缓存'**
  String get storageCatCache;

  /// No description provided for @storageCatCacheDesc.
  ///
  /// In zh, this message translates to:
  /// **'临时文件与图片缓存，可安全清理'**
  String get storageCatCacheDesc;

  /// No description provided for @storageCatCacheHint.
  ///
  /// In zh, this message translates to:
  /// **'清理后会在使用中自动重新生成'**
  String get storageCatCacheHint;

  /// No description provided for @storageCatConversation.
  ///
  /// In zh, this message translates to:
  /// **'会话历史'**
  String get storageCatConversation;

  /// No description provided for @storageCatConversationDesc.
  ///
  /// In zh, this message translates to:
  /// **'对话与工具执行历史（估算）'**
  String get storageCatConversationDesc;

  /// No description provided for @storageCatConversationHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除历史消息记录，且不可恢复'**
  String get storageCatConversationHint;

  /// No description provided for @storageCatDatabaseOther.
  ///
  /// In zh, this message translates to:
  /// **'数据库其他占用'**
  String get storageCatDatabaseOther;

  /// No description provided for @storageCatDatabaseOtherDesc.
  ///
  /// In zh, this message translates to:
  /// **'索引与系统表等数据库占用'**
  String get storageCatDatabaseOtherDesc;

  /// No description provided for @storageCatWorkspaceBrowser.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 浏览器产物'**
  String get storageCatWorkspaceBrowser;

  /// No description provided for @storageCatWorkspaceBrowserDesc.
  ///
  /// In zh, this message translates to:
  /// **'浏览器截图、下载文件和中间产物'**
  String get storageCatWorkspaceBrowserDesc;

  /// No description provided for @storageCatWorkspaceBrowserHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除浏览器工具相关的中间文件'**
  String get storageCatWorkspaceBrowserHint;

  /// No description provided for @storageCatWorkspaceOffloads.
  ///
  /// In zh, this message translates to:
  /// **'Workspace Offloads'**
  String get storageCatWorkspaceOffloads;

  /// No description provided for @storageCatWorkspaceOffloadsDesc.
  ///
  /// In zh, this message translates to:
  /// **'工具离线输出与临时文件'**
  String get storageCatWorkspaceOffloadsDesc;

  /// No description provided for @storageCatWorkspaceOffloadsHint.
  ///
  /// In zh, this message translates to:
  /// **'仅删除离线产物，不影响核心功能'**
  String get storageCatWorkspaceOffloadsHint;

  /// No description provided for @storageCatWorkspaceAttachments.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 附件'**
  String get storageCatWorkspaceAttachments;

  /// No description provided for @storageCatWorkspaceAttachmentsDesc.
  ///
  /// In zh, this message translates to:
  /// **'历史任务使用的附件文件'**
  String get storageCatWorkspaceAttachmentsDesc;

  /// No description provided for @storageCatWorkspaceAttachmentsHint.
  ///
  /// In zh, this message translates to:
  /// **'可能影响历史任务对附件的回看'**
  String get storageCatWorkspaceAttachmentsHint;

  /// No description provided for @storageCatWorkspaceShared.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 共享区'**
  String get storageCatWorkspaceShared;

  /// No description provided for @storageCatWorkspaceSharedDesc.
  ///
  /// In zh, this message translates to:
  /// **'跨任务共享的工作区文件'**
  String get storageCatWorkspaceSharedDesc;

  /// No description provided for @storageCatWorkspaceSharedHint.
  ///
  /// In zh, this message translates to:
  /// **'可能影响后续任务复用共享文件'**
  String get storageCatWorkspaceSharedHint;

  /// No description provided for @storageCatWorkspaceMemory.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆数据'**
  String get storageCatWorkspaceMemory;

  /// No description provided for @storageCatWorkspaceMemoryDesc.
  ///
  /// In zh, this message translates to:
  /// **'长期/短期记忆与索引数据'**
  String get storageCatWorkspaceMemoryDesc;

  /// No description provided for @storageCatWorkspaceUserFiles.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 用户文件'**
  String get storageCatWorkspaceUserFiles;

  /// No description provided for @storageCatWorkspaceUserFilesDesc.
  ///
  /// In zh, this message translates to:
  /// **'用户主动保存到 workspace 的文件'**
  String get storageCatWorkspaceUserFilesDesc;

  /// No description provided for @storageCatLocalModelsFiles.
  ///
  /// In zh, this message translates to:
  /// **'本地模型文件'**
  String get storageCatLocalModelsFiles;

  /// No description provided for @storageCatLocalModelsFilesDesc.
  ///
  /// In zh, this message translates to:
  /// **'.mnnmodels 下的模型文件'**
  String get storageCatLocalModelsFilesDesc;

  /// No description provided for @storageCatLocalModelsFilesHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除模型文件，后续需重新下载'**
  String get storageCatLocalModelsFilesHint;

  /// No description provided for @storageCatLocalModelsCache.
  ///
  /// In zh, this message translates to:
  /// **'模型推理缓存'**
  String get storageCatLocalModelsCache;

  /// No description provided for @storageCatLocalModelsCacheDesc.
  ///
  /// In zh, this message translates to:
  /// **'mmap 与本地推理临时目录'**
  String get storageCatLocalModelsCacheDesc;

  /// No description provided for @storageCatLocalModelsCacheHint.
  ///
  /// In zh, this message translates to:
  /// **'清理后会在推理时重新生成'**
  String get storageCatLocalModelsCacheHint;

  /// No description provided for @storageCatTerminalLocal.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时（local）'**
  String get storageCatTerminalLocal;

  /// No description provided for @storageCatTerminalLocalDesc.
  ///
  /// In zh, this message translates to:
  /// **'Alpine 终端 local 运行目录'**
  String get storageCatTerminalLocalDesc;

  /// No description provided for @storageCatTerminalLocalHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除终端 local 目录，需重新初始化'**
  String get storageCatTerminalLocalHint;

  /// No description provided for @storageCatTerminalBootstrap.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时（引导文件）'**
  String get storageCatTerminalBootstrap;

  /// No description provided for @storageCatTerminalBootstrapDesc.
  ///
  /// In zh, this message translates to:
  /// **'proot/lib/alpine 引导文件'**
  String get storageCatTerminalBootstrapDesc;

  /// No description provided for @storageCatTerminalBootstrapHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除终端引导文件，需重新初始化'**
  String get storageCatTerminalBootstrapHint;

  /// No description provided for @storageCatSharedDrafts.
  ///
  /// In zh, this message translates to:
  /// **'共享草稿'**
  String get storageCatSharedDrafts;

  /// No description provided for @storageCatSharedDraftsDesc.
  ///
  /// In zh, this message translates to:
  /// **'外部分享导入的草稿缓存'**
  String get storageCatSharedDraftsDesc;

  /// No description provided for @storageCatSharedDraftsHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除未发送的草稿附件'**
  String get storageCatSharedDraftsHint;

  /// No description provided for @storageCatMcpInbox.
  ///
  /// In zh, this message translates to:
  /// **'MCP 收件箱'**
  String get storageCatMcpInbox;

  /// No description provided for @storageCatMcpInboxDesc.
  ///
  /// In zh, this message translates to:
  /// **'MCP 文件传输接收目录'**
  String get storageCatMcpInboxDesc;

  /// No description provided for @storageCatMcpInboxHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除 MCP 收件箱中的文件'**
  String get storageCatMcpInboxHint;

  /// No description provided for @storageCatLegacyWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'旧版遗留数据'**
  String get storageCatLegacyWorkspace;

  /// No description provided for @storageCatLegacyWorkspaceDesc.
  ///
  /// In zh, this message translates to:
  /// **'升级后可能残留的旧 workspace 目录'**
  String get storageCatLegacyWorkspaceDesc;

  /// No description provided for @storageCatLegacyWorkspaceHint.
  ///
  /// In zh, this message translates to:
  /// **'建议确认无用后再清理'**
  String get storageCatLegacyWorkspaceHint;

  /// No description provided for @storageCatOtherUserData.
  ///
  /// In zh, this message translates to:
  /// **'其他数据'**
  String get storageCatOtherUserData;

  /// No description provided for @storageCatOtherUserDataDesc.
  ///
  /// In zh, this message translates to:
  /// **'未命中分类规则的数据'**
  String get storageCatOtherUserDataDesc;

  /// No description provided for @storageStrategySafeQuick.
  ///
  /// In zh, this message translates to:
  /// **'安全快速清理'**
  String get storageStrategySafeQuick;

  /// No description provided for @storageStrategySafeQuickDesc.
  ///
  /// In zh, this message translates to:
  /// **'优先清理低风险缓存与临时产物'**
  String get storageStrategySafeQuickDesc;

  /// No description provided for @storageStrategyBalanceDeep.
  ///
  /// In zh, this message translates to:
  /// **'平衡深度清理'**
  String get storageStrategyBalanceDeep;

  /// No description provided for @storageStrategyBalanceDeepDesc.
  ///
  /// In zh, this message translates to:
  /// **'释放更多空间，保留核心模型与用户文件'**
  String get storageStrategyBalanceDeepDesc;

  /// No description provided for @storageStrategyFree1gb.
  ///
  /// In zh, this message translates to:
  /// **'目标释放 1GB'**
  String get storageStrategyFree1gb;

  /// No description provided for @storageStrategyFree1gbDesc.
  ///
  /// In zh, this message translates to:
  /// **'按高收益顺序清理，尽量达到 1GB 释放目标'**
  String get storageStrategyFree1gbDesc;

  /// No description provided for @storageHintConversation.
  ///
  /// In zh, this message translates to:
  /// **'如历史未释放，请重新进入页面执行「重新分析」'**
  String get storageHintConversation;

  /// No description provided for @storageHintLocalModels.
  ///
  /// In zh, this message translates to:
  /// **'模型被清理后，可在「本地模型服务」页面重新下载'**
  String get storageHintLocalModels;

  /// No description provided for @storageHintTerminal.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时被清理后，可在 Alpine 环境页重新初始化'**
  String get storageHintTerminal;

  /// No description provided for @storageHintGeneral.
  ///
  /// In zh, this message translates to:
  /// **'若清理失败，可稍后重试或重启应用后再次清理'**
  String get storageHintGeneral;

  /// No description provided for @storageHintNotCleanable.
  ///
  /// In zh, this message translates to:
  /// **'该分类当前不可清理'**
  String get storageHintNotCleanable;

  /// No description provided for @storageHintSkipped.
  ///
  /// In zh, this message translates to:
  /// **'该分类已跳过（可选项）'**
  String get storageHintSkipped;

  /// No description provided for @storageCleanPartialFailed.
  ///
  /// In zh, this message translates to:
  /// **'部分清理失败：{hint}'**
  String storageCleanPartialFailed(Object hint);

  /// No description provided for @storageCleanPartialFailedGeneric.
  ///
  /// In zh, this message translates to:
  /// **'部分文件清理失败，请稍后重试'**
  String get storageCleanPartialFailedGeneric;

  /// No description provided for @storageTrendVsLast.
  ///
  /// In zh, this message translates to:
  /// **'对比上次分析：总计 {total}，可清理 {cleanable}'**
  String storageTrendVsLast(Object cleanable, Object total);

  /// No description provided for @storageLastAnalyzed.
  ///
  /// In zh, this message translates to:
  /// **'上次分析时间：{time}'**
  String storageLastAnalyzed(Object time);

  /// No description provided for @aboutDescription.
  ///
  /// In zh, this message translates to:
  /// **'小万，是一款以智能对话为核心的手机AI助\n手，通过语义理解与持续学习能力，协助用户\n完成信息处理、决策辅助和日常管理。'**
  String get aboutDescription;

  /// No description provided for @aboutBetaProgramTitle.
  ///
  /// In zh, this message translates to:
  /// **'加入 beta 测试'**
  String get aboutBetaProgramTitle;

  /// No description provided for @aboutBetaProgramDescription.
  ///
  /// In zh, this message translates to:
  /// **'接收更快的四段版更新。'**
  String get aboutBetaProgramDescription;

  /// No description provided for @aboutBetaProgramToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'beta 测试设置更新失败'**
  String get aboutBetaProgramToggleFailed;

  /// No description provided for @aboutPreferencesSectionTitle.
  ///
  /// In zh, this message translates to:
  /// **'更新与测试'**
  String get aboutPreferencesSectionTitle;

  /// No description provided for @aboutApkSourceTitle.
  ///
  /// In zh, this message translates to:
  /// **'安装包下载源'**
  String get aboutApkSourceTitle;

  /// No description provided for @aboutApkSourceDescription.
  ///
  /// In zh, this message translates to:
  /// **'选择安装更新时使用的下载源。'**
  String get aboutApkSourceDescription;

  /// No description provided for @aboutApkSourceOptionCnb.
  ///
  /// In zh, this message translates to:
  /// **'CNB'**
  String get aboutApkSourceOptionCnb;

  /// No description provided for @aboutApkSourceOptionCnbDescription.
  ///
  /// In zh, this message translates to:
  /// **'国内网络优先'**
  String get aboutApkSourceOptionCnbDescription;

  /// No description provided for @aboutApkSourceOptionGithub.
  ///
  /// In zh, this message translates to:
  /// **'GitHub'**
  String get aboutApkSourceOptionGithub;

  /// No description provided for @aboutApkSourceOptionGithubDescription.
  ///
  /// In zh, this message translates to:
  /// **'官方 Release'**
  String get aboutApkSourceOptionGithubDescription;

  /// No description provided for @aboutApkSourceSwitchFailed.
  ///
  /// In zh, this message translates to:
  /// **'安装包下载源切换失败'**
  String get aboutApkSourceSwitchFailed;

  /// No description provided for @aboutUpdateHintDefault.
  ///
  /// In zh, this message translates to:
  /// **'检查更新获取最新版本'**
  String get aboutUpdateHintDefault;

  /// No description provided for @workspaceMemoryLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载 workspace 记忆配置失败'**
  String get workspaceMemoryLoadFailed;

  /// No description provided for @workspaceSoulSaved.
  ///
  /// In zh, this message translates to:
  /// **'SOUL.md 已保存'**
  String get workspaceSoulSaved;

  /// No description provided for @workspaceSoulSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'SOUL.md 保存失败'**
  String get workspaceSoulSaveFailed;

  /// No description provided for @workspaceChatSaved.
  ///
  /// In zh, this message translates to:
  /// **'CHAT.md 已保存'**
  String get workspaceChatSaved;

  /// No description provided for @workspaceChatSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'CHAT.md 保存失败'**
  String get workspaceChatSaveFailed;

  /// No description provided for @workspaceMemorySaved.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md 已保存'**
  String get workspaceMemorySaved;

  /// No description provided for @workspaceMemorySaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md 保存失败'**
  String get workspaceMemorySaveFailed;

  /// No description provided for @workspaceEmbeddingToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'记忆嵌入开关更新失败'**
  String get workspaceEmbeddingToggleFailed;

  /// No description provided for @workspaceRollupToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'夜间整理开关更新失败'**
  String get workspaceRollupToggleFailed;

  /// No description provided for @workspaceRollupDone.
  ///
  /// In zh, this message translates to:
  /// **'整理完成'**
  String get workspaceRollupDone;

  /// No description provided for @workspaceRollupFailed.
  ///
  /// In zh, this message translates to:
  /// **'立即整理失败'**
  String get workspaceRollupFailed;

  /// No description provided for @workspaceNone.
  ///
  /// In zh, this message translates to:
  /// **'暂无'**
  String get workspaceNone;

  /// No description provided for @workspaceMemoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆'**
  String get workspaceMemoryTitle;

  /// No description provided for @workspaceMemoryCapability.
  ///
  /// In zh, this message translates to:
  /// **'记忆能力'**
  String get workspaceMemoryCapability;

  /// No description provided for @workspaceEmbeddingReady.
  ///
  /// In zh, this message translates to:
  /// **'已配置，可使用向量检索'**
  String get workspaceEmbeddingReady;

  /// No description provided for @workspaceEmbeddingNotReady.
  ///
  /// In zh, this message translates to:
  /// **'未配置，将自动降级为词法检索'**
  String get workspaceEmbeddingNotReady;

  /// No description provided for @workspaceGoToConfig.
  ///
  /// In zh, this message translates to:
  /// **'去场景模型配置记忆嵌入模型'**
  String get workspaceGoToConfig;

  /// No description provided for @workspaceNightlyRollup.
  ///
  /// In zh, this message translates to:
  /// **'夜间记忆整理（22:00）'**
  String get workspaceNightlyRollup;

  /// No description provided for @workspaceLastRun.
  ///
  /// In zh, this message translates to:
  /// **'最近运行：{time}'**
  String workspaceLastRun(Object time);

  /// No description provided for @workspaceNextRun.
  ///
  /// In zh, this message translates to:
  /// **'下次运行：{time}'**
  String workspaceNextRun(Object time);

  /// No description provided for @workspaceRollupNow.
  ///
  /// In zh, this message translates to:
  /// **'立即整理一次'**
  String get workspaceRollupNow;

  /// No description provided for @workspaceDocContent.
  ///
  /// In zh, this message translates to:
  /// **'文档内容'**
  String get workspaceDocContent;

  /// No description provided for @workspaceSoulMd.
  ///
  /// In zh, this message translates to:
  /// **'SOUL.md（Agent 灵魂）'**
  String get workspaceSoulMd;

  /// No description provided for @workspaceChatMd.
  ///
  /// In zh, this message translates to:
  /// **'CHAT.md（纯聊天系统提示词）'**
  String get workspaceChatMd;

  /// No description provided for @workspaceMemoryMd.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md（长期记忆）'**
  String get workspaceMemoryMd;

  /// No description provided for @alpineNodeJs.
  ///
  /// In zh, this message translates to:
  /// **'Node.js 运行时'**
  String get alpineNodeJs;

  /// No description provided for @alpineNpm.
  ///
  /// In zh, this message translates to:
  /// **'Node.js 包管理器'**
  String get alpineNpm;

  /// No description provided for @alpineGit.
  ///
  /// In zh, this message translates to:
  /// **'Git 版本控制'**
  String get alpineGit;

  /// No description provided for @alpinePython.
  ///
  /// In zh, this message translates to:
  /// **'Python 解释器'**
  String get alpinePython;

  /// No description provided for @alpinePip.
  ///
  /// In zh, this message translates to:
  /// **'Python 项目与包工具'**
  String get alpinePip;

  /// No description provided for @alpinePipInstall.
  ///
  /// In zh, this message translates to:
  /// **'Python 包安装器'**
  String get alpinePipInstall;

  /// No description provided for @alpineCodex.
  ///
  /// In zh, this message translates to:
  /// **'OpenAI Codex CLI 与 app-server 桥接'**
  String get alpineCodex;

  /// No description provided for @alpineSshClient.
  ///
  /// In zh, this message translates to:
  /// **'SSH 客户端'**
  String get alpineSshClient;

  /// No description provided for @alpineSshpass.
  ///
  /// In zh, this message translates to:
  /// **'SSH 密码辅助工具'**
  String get alpineSshpass;

  /// No description provided for @alpineOpenSshServer.
  ///
  /// In zh, this message translates to:
  /// **'OpenSSH 服务器'**
  String get alpineOpenSshServer;

  /// No description provided for @alpineDetectFailed.
  ///
  /// In zh, this message translates to:
  /// **'检测 Alpine 环境失败'**
  String get alpineDetectFailed;

  /// No description provided for @alpineBootTasksLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'读取自启动任务失败'**
  String get alpineBootTasksLoadFailed;

  /// No description provided for @alpineConfigOpenFailed.
  ///
  /// In zh, this message translates to:
  /// **'打开终端环境配置失败'**
  String get alpineConfigOpenFailed;

  /// No description provided for @alpineBootTaskAdded.
  ///
  /// In zh, this message translates to:
  /// **'已新增自启动任务'**
  String get alpineBootTaskAdded;

  /// No description provided for @alpineBootTaskUpdated.
  ///
  /// In zh, this message translates to:
  /// **'已更新自启动任务'**
  String get alpineBootTaskUpdated;

  /// No description provided for @alpineBootTaskSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存自启动任务失败'**
  String get alpineBootTaskSaveFailed;

  /// No description provided for @alpineBootEnabled.
  ///
  /// In zh, this message translates to:
  /// **'已开启应用启动时自启动'**
  String get alpineBootEnabled;

  /// No description provided for @alpineBootDisabled.
  ///
  /// In zh, this message translates to:
  /// **'已关闭自动启动'**
  String get alpineBootDisabled;

  /// No description provided for @alpineBootTaskUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'更新任务失败'**
  String get alpineBootTaskUpdateFailed;

  /// No description provided for @alpineDeleteBootTask.
  ///
  /// In zh, this message translates to:
  /// **'删除自启动任务'**
  String get alpineDeleteBootTask;

  /// No description provided for @alpineDeleteBootTaskMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认删除\"{name}\"吗？'**
  String alpineDeleteBootTaskMsg(Object name);

  /// No description provided for @alpineBootTaskDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除自启动任务'**
  String get alpineBootTaskDeleted;

  /// No description provided for @alpineBootTaskDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除任务失败'**
  String get alpineBootTaskDeleteFailed;

  /// No description provided for @alpineCommandSent.
  ///
  /// In zh, this message translates to:
  /// **'启动命令已发送'**
  String get alpineCommandSent;

  /// No description provided for @alpineStartFailed.
  ///
  /// In zh, this message translates to:
  /// **'启动任务失败'**
  String get alpineStartFailed;

  /// No description provided for @alpineDetecting.
  ///
  /// In zh, this message translates to:
  /// **'正在检测环境'**
  String get alpineDetecting;

  /// No description provided for @alpineStartConfig.
  ///
  /// In zh, this message translates to:
  /// **'开始配置（{count} 项）'**
  String alpineStartConfig(Object count);

  /// No description provided for @alpineAllReady.
  ///
  /// In zh, this message translates to:
  /// **'全部已就绪'**
  String get alpineAllReady;

  /// No description provided for @alpineDetectingDesc.
  ///
  /// In zh, this message translates to:
  /// **'正在后台检测 Alpine 内常见开发环境的版本信息。'**
  String get alpineDetectingDesc;

  /// No description provided for @alpineReadyCount.
  ///
  /// In zh, this message translates to:
  /// **'已就绪 {ready}/{total} 项，可直接勾选缺失项并进入 ReTerminal 自动配置。'**
  String alpineReadyCount(Object ready, Object total);

  /// No description provided for @alpineBootTasks.
  ///
  /// In zh, this message translates to:
  /// **'自启动任务'**
  String get alpineBootTasks;

  /// No description provided for @alpineBootTasksDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开 Omnibot 时会在后台检查已启用的任务，并在对应 ReTerminal 会话内启动命令，适合常驻服务。'**
  String get alpineBootTasksDesc;

  /// No description provided for @alpineAddTask.
  ///
  /// In zh, this message translates to:
  /// **'新增任务'**
  String get alpineAddTask;

  /// No description provided for @alpineOpenTerminal.
  ///
  /// In zh, this message translates to:
  /// **'打开终端'**
  String get alpineOpenTerminal;

  /// No description provided for @alpineNoTasksDesc.
  ///
  /// In zh, this message translates to:
  /// **'暂无任务。你可以添加例如 `python app.py`、`node server.js`、`./start.sh` 之类的常驻命令。'**
  String get alpineNoTasksDesc;

  /// No description provided for @alpineBootOnAppOpen.
  ///
  /// In zh, this message translates to:
  /// **'开机打开 app 后启动'**
  String get alpineBootOnAppOpen;

  /// No description provided for @alpineNotEnabled.
  ///
  /// In zh, this message translates to:
  /// **'未启用'**
  String get alpineNotEnabled;

  /// No description provided for @alpineRunning.
  ///
  /// In zh, this message translates to:
  /// **'已在运行'**
  String get alpineRunning;

  /// No description provided for @alpineStartNow.
  ///
  /// In zh, this message translates to:
  /// **'立即启动'**
  String get alpineStartNow;

  /// No description provided for @alpineEdit.
  ///
  /// In zh, this message translates to:
  /// **'编辑'**
  String get alpineEdit;

  /// No description provided for @alpineVersionDetected.
  ///
  /// In zh, this message translates to:
  /// **'已检测到可用版本'**
  String get alpineVersionDetected;

  /// No description provided for @alpineVersionNotFound.
  ///
  /// In zh, this message translates to:
  /// **'未检测到'**
  String get alpineVersionNotFound;

  /// No description provided for @alpineTaskNameHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入任务名称'**
  String get alpineTaskNameHint;

  /// No description provided for @alpineCommandHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入启动命令'**
  String get alpineCommandHint;

  /// No description provided for @alpineEditBootTask.
  ///
  /// In zh, this message translates to:
  /// **'编辑自启动任务'**
  String get alpineEditBootTask;

  /// No description provided for @alpineAddBootTask.
  ///
  /// In zh, this message translates to:
  /// **'新增自启动任务'**
  String get alpineAddBootTask;

  /// No description provided for @alpineTaskName.
  ///
  /// In zh, this message translates to:
  /// **'任务名称'**
  String get alpineTaskName;

  /// No description provided for @alpineTaskNameExample.
  ///
  /// In zh, this message translates to:
  /// **'例如：本地 API 服务'**
  String get alpineTaskNameExample;

  /// No description provided for @alpineStartCommand.
  ///
  /// In zh, this message translates to:
  /// **'启动命令'**
  String get alpineStartCommand;

  /// No description provided for @alpineCommandExample.
  ///
  /// In zh, this message translates to:
  /// **'例如：python app.py 或 pnpm start'**
  String get alpineCommandExample;

  /// No description provided for @alpineWorkDir.
  ///
  /// In zh, this message translates to:
  /// **'工作目录'**
  String get alpineWorkDir;

  /// No description provided for @alpineBootAutoStart.
  ///
  /// In zh, this message translates to:
  /// **'打开小万时自动启动'**
  String get alpineBootAutoStart;

  /// No description provided for @alpineDevEnv.
  ///
  /// In zh, this message translates to:
  /// **'开发环境'**
  String get alpineDevEnv;

  /// No description provided for @alpineAiAgent.
  ///
  /// In zh, this message translates to:
  /// **'AI Agent'**
  String get alpineAiAgent;

  /// No description provided for @alpineEnvConfig.
  ///
  /// In zh, this message translates to:
  /// **'环境配置'**
  String get alpineEnvConfig;

  /// No description provided for @alpineWorkDirValue.
  ///
  /// In zh, this message translates to:
  /// **'工作目录：{dir}'**
  String alpineWorkDirValue(Object dir);

  /// No description provided for @workspaceEmbeddingRetrieval.
  ///
  /// In zh, this message translates to:
  /// **'记忆嵌入检索'**
  String get workspaceEmbeddingRetrieval;

  /// No description provided for @chatHistoryStartConversation.
  ///
  /// In zh, this message translates to:
  /// **'开始对话'**
  String get chatHistoryStartConversation;

  /// No description provided for @homeDrawerSearching.
  ///
  /// In zh, this message translates to:
  /// **'正在搜索对话内容…'**
  String get homeDrawerSearching;

  /// No description provided for @homeDrawerNoResults.
  ///
  /// In zh, this message translates to:
  /// **'没有找到相关对话'**
  String get homeDrawerNoResults;

  /// No description provided for @homeDrawerSearchHint2.
  ///
  /// In zh, this message translates to:
  /// **'试试更短的关键词，或换一种说法'**
  String get homeDrawerSearchHint2;

  /// No description provided for @homeDrawerSearchResults.
  ///
  /// In zh, this message translates to:
  /// **'搜索结果'**
  String get homeDrawerSearchResults;

  /// No description provided for @homeDrawerResultCount.
  ///
  /// In zh, this message translates to:
  /// **'条'**
  String get homeDrawerResultCount;

  /// No description provided for @homeDrawerScheduled.
  ///
  /// In zh, this message translates to:
  /// **'定时'**
  String get homeDrawerScheduled;

  /// No description provided for @homeDrawerGreeting.
  ///
  /// In zh, this message translates to:
  /// **'你好！'**
  String get homeDrawerGreeting;

  /// No description provided for @homeDrawerWelcome.
  ///
  /// In zh, this message translates to:
  /// **'欢迎使用小万'**
  String get homeDrawerWelcome;

  /// No description provided for @homeDrawerDawnGreeting.
  ///
  /// In zh, this message translates to:
  /// **'凌晨啦'**
  String get homeDrawerDawnGreeting;

  /// No description provided for @homeDrawerDawnSub.
  ///
  /// In zh, this message translates to:
  /// **'还没休息吗？'**
  String get homeDrawerDawnSub;

  /// No description provided for @homeDrawerDawnGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'天还没亮'**
  String get homeDrawerDawnGreeting2;

  /// No description provided for @homeDrawerDawnSub2.
  ///
  /// In zh, this message translates to:
  /// **'早起的你辛苦啦～'**
  String get homeDrawerDawnSub2;

  /// No description provided for @homeDrawerDawnGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'深夜的时光很静'**
  String get homeDrawerDawnGreeting3;

  /// No description provided for @homeDrawerDawnSub3.
  ///
  /// In zh, this message translates to:
  /// **'但也要记得给身体留些休息呀～'**
  String get homeDrawerDawnSub3;

  /// No description provided for @homeDrawerMorningGreeting.
  ///
  /// In zh, this message translates to:
  /// **'早安！'**
  String get homeDrawerMorningGreeting;

  /// No description provided for @homeDrawerMorningSub.
  ///
  /// In zh, this message translates to:
  /// **'开启元气一天'**
  String get homeDrawerMorningSub;

  /// No description provided for @homeDrawerMorningGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'早呀！'**
  String get homeDrawerMorningGreeting2;

  /// No description provided for @homeDrawerMorningSub2.
  ///
  /// In zh, this message translates to:
  /// **'新的一天开始啦'**
  String get homeDrawerMorningSub2;

  /// No description provided for @homeDrawerForenoonGreeting.
  ///
  /// In zh, this message translates to:
  /// **'上午好！'**
  String get homeDrawerForenoonGreeting;

  /// No description provided for @homeDrawerForenoonSub.
  ///
  /// In zh, this message translates to:
  /// **'再忙也别忘了活动下肩膀'**
  String get homeDrawerForenoonSub;

  /// No description provided for @homeDrawerForenoonGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'上午的效率超棒！'**
  String get homeDrawerForenoonGreeting2;

  /// No description provided for @homeDrawerForenoonSub2.
  ///
  /// In zh, this message translates to:
  /// **'继续加油'**
  String get homeDrawerForenoonSub2;

  /// No description provided for @homeDrawerLunchGreeting.
  ///
  /// In zh, this message translates to:
  /// **'午饭时间到！'**
  String get homeDrawerLunchGreeting;

  /// No description provided for @homeDrawerLunchSub.
  ///
  /// In zh, this message translates to:
  /// **'好好吃饭，别凑合'**
  String get homeDrawerLunchSub;

  /// No description provided for @homeDrawerLunchGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'午安～'**
  String get homeDrawerLunchGreeting2;

  /// No description provided for @homeDrawerLunchSub2.
  ///
  /// In zh, this message translates to:
  /// **'吃完记得歇会儿'**
  String get homeDrawerLunchSub2;

  /// No description provided for @homeDrawerLunchGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'午餐不知道吃什么？'**
  String get homeDrawerLunchGreeting3;

  /// No description provided for @homeDrawerLunchSub3.
  ///
  /// In zh, this message translates to:
  /// **'让小万帮你推荐吧！'**
  String get homeDrawerLunchSub3;

  /// No description provided for @homeDrawerAfternoonGreeting.
  ///
  /// In zh, this message translates to:
  /// **'喝杯茶提提神'**
  String get homeDrawerAfternoonGreeting;

  /// No description provided for @homeDrawerAfternoonSub.
  ///
  /// In zh, this message translates to:
  /// **'剩下的任务也能轻松搞定～'**
  String get homeDrawerAfternoonSub;

  /// No description provided for @homeDrawerAfternoonGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'工作间隙看看窗外'**
  String get homeDrawerAfternoonGreeting2;

  /// No description provided for @homeDrawerAfternoonSub2.
  ///
  /// In zh, this message translates to:
  /// **'让眼睛歇一歇～'**
  String get homeDrawerAfternoonSub2;

  /// No description provided for @homeDrawerEveningGreeting.
  ///
  /// In zh, this message translates to:
  /// **'回家路上慢点'**
  String get homeDrawerEveningGreeting;

  /// No description provided for @homeDrawerEveningSub.
  ///
  /// In zh, this message translates to:
  /// **'今晚好好放松～'**
  String get homeDrawerEveningSub;

  /// No description provided for @homeDrawerEveningGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'傍晚了'**
  String get homeDrawerEveningGreeting2;

  /// No description provided for @homeDrawerEveningSub2.
  ///
  /// In zh, this message translates to:
  /// **'吹来的晚风很舒服呀！～'**
  String get homeDrawerEveningSub2;

  /// No description provided for @homeDrawerEveningGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'忙了一天'**
  String get homeDrawerEveningGreeting3;

  /// No description provided for @homeDrawerEveningSub3.
  ///
  /// In zh, this message translates to:
  /// **'吃顿好的犒劳自己～'**
  String get homeDrawerEveningSub3;

  /// No description provided for @homeDrawerNightGreeting.
  ///
  /// In zh, this message translates to:
  /// **'晚上好！'**
  String get homeDrawerNightGreeting;

  /// No description provided for @homeDrawerNightSub.
  ///
  /// In zh, this message translates to:
  /// **'享受属于自己的时光吧～'**
  String get homeDrawerNightSub;

  /// No description provided for @homeDrawerNightGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'夜色渐浓'**
  String get homeDrawerNightGreeting2;

  /// No description provided for @homeDrawerNightSub2.
  ///
  /// In zh, this message translates to:
  /// **'准备下早点休息啦～'**
  String get homeDrawerNightSub2;

  /// No description provided for @homeDrawerNightGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'该休息了'**
  String get homeDrawerNightGreeting3;

  /// No description provided for @homeDrawerNightSub3.
  ///
  /// In zh, this message translates to:
  /// **'让小万帮你定个闹钟吧！'**
  String get homeDrawerNightSub3;

  /// No description provided for @homeDrawerLateNightGreeting.
  ///
  /// In zh, this message translates to:
  /// **'放下手机早点睡'**
  String get homeDrawerLateNightGreeting;

  /// No description provided for @homeDrawerLateNightSub.
  ///
  /// In zh, this message translates to:
  /// **'明天才能元气满满～'**
  String get homeDrawerLateNightSub;

  /// No description provided for @homeDrawerLateNightGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'深夜了'**
  String get homeDrawerLateNightGreeting2;

  /// No description provided for @homeDrawerLateNightSub2.
  ///
  /// In zh, this message translates to:
  /// **'好好和今天说晚安～'**
  String get homeDrawerLateNightSub2;

  /// No description provided for @omniflowPanelTitle.
  ///
  /// In zh, this message translates to:
  /// **'OmniFlow 轨迹面板'**
  String get omniflowPanelTitle;

  /// No description provided for @omniflowPanelDesc.
  ///
  /// In zh, this message translates to:
  /// **'管理 OmniFlow Function：查看、执行或删除 Function 资产。'**
  String get omniflowPanelDesc;

  /// No description provided for @omniflowFunctionList.
  ///
  /// In zh, this message translates to:
  /// **'Function 列表'**
  String get omniflowFunctionList;

  /// No description provided for @omniflowFunctionSearch.
  ///
  /// In zh, this message translates to:
  /// **'搜索 Function'**
  String get omniflowFunctionSearch;

  /// No description provided for @omniflowFunctionSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'按名称、描述等关键字过滤'**
  String get omniflowFunctionSearchHint;

  /// No description provided for @omniflowSettings.
  ///
  /// In zh, this message translates to:
  /// **'OmniFlow 设置'**
  String get omniflowSettings;

  /// No description provided for @omniflowSettingsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'记录高频可复用操作段，加速任务执行'**
  String get omniflowSettingsSubtitle;

  /// No description provided for @omniflowEnablePreHook.
  ///
  /// In zh, this message translates to:
  /// **'启用 OmniFlow 执行加速'**
  String get omniflowEnablePreHook;

  /// No description provided for @omniflowAutoStartProvider.
  ///
  /// In zh, this message translates to:
  /// **'OmniFlow 自启动'**
  String get omniflowAutoStartProvider;

  /// No description provided for @omniflowRefresh.
  ///
  /// In zh, this message translates to:
  /// **'刷新'**
  String get omniflowRefresh;

  /// No description provided for @omniflowProviderStart.
  ///
  /// In zh, this message translates to:
  /// **'启动'**
  String get omniflowProviderStart;

  /// No description provided for @omniflowProviderStop.
  ///
  /// In zh, this message translates to:
  /// **'停止'**
  String get omniflowProviderStop;

  /// No description provided for @omniflowProviderRestart.
  ///
  /// In zh, this message translates to:
  /// **'重启'**
  String get omniflowProviderRestart;

  /// No description provided for @omniflowSaveConfig.
  ///
  /// In zh, this message translates to:
  /// **'保存'**
  String get omniflowSaveConfig;

  /// No description provided for @omniflowConfigSaved.
  ///
  /// In zh, this message translates to:
  /// **'OmniFlow 配置已保存'**
  String get omniflowConfigSaved;

  /// No description provided for @omniflowConfigSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存 OmniFlow 配置失败'**
  String get omniflowConfigSaveFailed;

  /// No description provided for @omniflowConfigLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载 OmniFlow 配置失败'**
  String get omniflowConfigLoadFailed;

  /// No description provided for @omniflowFunctionsLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载 Function 列表失败'**
  String get omniflowFunctionsLoadFailed;

  /// No description provided for @omniflowTempFunctions.
  ///
  /// In zh, this message translates to:
  /// **'临时 Function'**
  String get omniflowTempFunctions;

  /// No description provided for @omniflowReadyFunctions.
  ///
  /// In zh, this message translates to:
  /// **'可用 Function'**
  String get omniflowReadyFunctions;

  /// No description provided for @omniflowServiceAddressNotConfigured.
  ///
  /// In zh, this message translates to:
  /// **'服务地址未配置'**
  String get omniflowServiceAddressNotConfigured;

  /// No description provided for @omniflowSkillLibrary.
  ///
  /// In zh, this message translates to:
  /// **'OmniFlow 技能库'**
  String get omniflowSkillLibrary;

  /// No description provided for @omniflowServiceStatus.
  ///
  /// In zh, this message translates to:
  /// **'服务状态'**
  String get omniflowServiceStatus;

  /// No description provided for @omniflowServiceStatusRunning.
  ///
  /// In zh, this message translates to:
  /// **'运行中'**
  String get omniflowServiceStatusRunning;

  /// No description provided for @omniflowServiceStatusStopped.
  ///
  /// In zh, this message translates to:
  /// **'未运行'**
  String get omniflowServiceStatusStopped;

  /// No description provided for @omniflowServiceAddress.
  ///
  /// In zh, this message translates to:
  /// **'服务地址'**
  String get omniflowServiceAddress;

  /// No description provided for @omniflowDataDirectory.
  ///
  /// In zh, this message translates to:
  /// **'数据目录'**
  String get omniflowDataDirectory;

  /// No description provided for @omniflowNotSet.
  ///
  /// In zh, this message translates to:
  /// **'未设置'**
  String get omniflowNotSet;

  /// No description provided for @omniflowEnableAccelerationDesc.
  ///
  /// In zh, this message translates to:
  /// **'执行任务前优先匹配已学习的技能'**
  String get omniflowEnableAccelerationDesc;

  /// No description provided for @omniflowAutoStartDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开应用时自动启动技能服务'**
  String get omniflowAutoStartDesc;

  /// No description provided for @omniflowStarting.
  ///
  /// In zh, this message translates to:
  /// **'启动中...'**
  String get omniflowStarting;

  /// No description provided for @omniflowRestarting.
  ///
  /// In zh, this message translates to:
  /// **'重启中...'**
  String get omniflowRestarting;

  /// No description provided for @omniflowStopping.
  ///
  /// In zh, this message translates to:
  /// **'停止中...'**
  String get omniflowStopping;

  /// No description provided for @omniflowViewSkillLibrary.
  ///
  /// In zh, this message translates to:
  /// **'查看技能库'**
  String get omniflowViewSkillLibrary;

  /// No description provided for @omniflowViewFunctionLibrary.
  ///
  /// In zh, this message translates to:
  /// **'查看功能库'**
  String get omniflowViewFunctionLibrary;

  /// No description provided for @omniflowClearAllData.
  ///
  /// In zh, this message translates to:
  /// **'清空所有数据'**
  String get omniflowClearAllData;

  /// No description provided for @omniflowClearAllDataTitle.
  ///
  /// In zh, this message translates to:
  /// **'清空所有数据'**
  String get omniflowClearAllDataTitle;

  /// No description provided for @omniflowClearAllDataConfirm.
  ///
  /// In zh, this message translates to:
  /// **'确认清空所有 OmniFlow 数据？\n\n这将删除：\n• 所有 Functions\n• 所有 Run Logs\n• 所有 Shared Pages\n\n此操作不可恢复！'**
  String get omniflowClearAllDataConfirm;

  /// No description provided for @omniflowCancel.
  ///
  /// In zh, this message translates to:
  /// **'取消'**
  String get omniflowCancel;

  /// No description provided for @omniflowClear.
  ///
  /// In zh, this message translates to:
  /// **'清空'**
  String get omniflowClear;

  /// No description provided for @omniflowClearSuccess.
  ///
  /// In zh, this message translates to:
  /// **'已清空: {functions} functions, {runLogs} run_logs'**
  String omniflowClearSuccess(Object functions, Object runLogs);

  /// No description provided for @omniflowClearFailed.
  ///
  /// In zh, this message translates to:
  /// **'清空失败'**
  String get omniflowClearFailed;

  /// No description provided for @omniflowProviderActionSuccess.
  ///
  /// In zh, this message translates to:
  /// **'provider {action} 成功'**
  String omniflowProviderActionSuccess(Object action);

  /// No description provided for @omniflowProviderActionFailed.
  ///
  /// In zh, this message translates to:
  /// **'provider {action} 失败'**
  String omniflowProviderActionFailed(Object action);

  /// No description provided for @functionLibraryTitle.
  ///
  /// In zh, this message translates to:
  /// **'功能库'**
  String get functionLibraryTitle;

  /// No description provided for @functionLibrarySearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索功能名称或应用'**
  String get functionLibrarySearchHint;

  /// No description provided for @functionLibraryEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无已学习的功能'**
  String get functionLibraryEmpty;

  /// No description provided for @functionLibraryEmptyDesc.
  ///
  /// In zh, this message translates to:
  /// **'执行任务后，高频操作会自动沉淀到这里'**
  String get functionLibraryEmptyDesc;

  /// No description provided for @functionLibrarySteps.
  ///
  /// In zh, this message translates to:
  /// **'步'**
  String get functionLibrarySteps;

  /// No description provided for @functionLibraryHasParams.
  ///
  /// In zh, this message translates to:
  /// **'有参数'**
  String get functionLibraryHasParams;

  /// No description provided for @functionLibraryRunCount.
  ///
  /// In zh, this message translates to:
  /// **'执行'**
  String get functionLibraryRunCount;

  /// No description provided for @functionLibraryId.
  ///
  /// In zh, this message translates to:
  /// **'ID'**
  String get functionLibraryId;

  /// No description provided for @functionLibraryParams.
  ///
  /// In zh, this message translates to:
  /// **'参数'**
  String get functionLibraryParams;

  /// No description provided for @functionLibrarySource.
  ///
  /// In zh, this message translates to:
  /// **'来源'**
  String get functionLibrarySource;

  /// No description provided for @functionLibraryCreatedAt.
  ///
  /// In zh, this message translates to:
  /// **'创建时间'**
  String get functionLibraryCreatedAt;

  /// No description provided for @functionLibraryEdit.
  ///
  /// In zh, this message translates to:
  /// **'编辑'**
  String get functionLibraryEdit;

  /// No description provided for @functionLibraryEditTitle.
  ///
  /// In zh, this message translates to:
  /// **'编辑功能'**
  String get functionLibraryEditTitle;

  /// No description provided for @functionLibraryEditHint.
  ///
  /// In zh, this message translates to:
  /// **'修改功能的描述名称'**
  String get functionLibraryEditHint;

  /// No description provided for @functionLibraryEditPlaceholder.
  ///
  /// In zh, this message translates to:
  /// **'输入新的描述'**
  String get functionLibraryEditPlaceholder;

  /// No description provided for @functionLibraryEditSuccess.
  ///
  /// In zh, this message translates to:
  /// **'已更新'**
  String get functionLibraryEditSuccess;

  /// No description provided for @functionLibraryEditFailed.
  ///
  /// In zh, this message translates to:
  /// **'更新失败'**
  String get functionLibraryEditFailed;

  /// No description provided for @functionLibraryDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get functionLibraryDelete;

  /// No description provided for @functionLibraryDeleteTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除功能'**
  String get functionLibraryDeleteTitle;

  /// No description provided for @functionLibraryDeleteConfirm.
  ///
  /// In zh, this message translates to:
  /// **'确认删除「{name}」？'**
  String functionLibraryDeleteConfirm(Object name);

  /// No description provided for @functionLibraryDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除'**
  String get functionLibraryDeleted;

  /// No description provided for @functionLibraryDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除失败'**
  String get functionLibraryDeleteFailed;

  /// No description provided for @functionLibraryUpload.
  ///
  /// In zh, this message translates to:
  /// **'上传'**
  String get functionLibraryUpload;

  /// No description provided for @functionLibraryUploadTitle.
  ///
  /// In zh, this message translates to:
  /// **'上传到云端'**
  String get functionLibraryUploadTitle;

  /// No description provided for @functionLibraryUploadSuccess.
  ///
  /// In zh, this message translates to:
  /// **'上传成功'**
  String get functionLibraryUploadSuccess;

  /// No description provided for @functionLibraryUploadFailed.
  ///
  /// In zh, this message translates to:
  /// **'上传失败'**
  String get functionLibraryUploadFailed;

  /// No description provided for @functionLibraryDownload.
  ///
  /// In zh, this message translates to:
  /// **'从云端下载'**
  String get functionLibraryDownload;

  /// No description provided for @functionLibraryDownloadTitle.
  ///
  /// In zh, this message translates to:
  /// **'从云端下载'**
  String get functionLibraryDownloadTitle;

  /// No description provided for @functionLibraryDownloadSuccess.
  ///
  /// In zh, this message translates to:
  /// **'下载成功'**
  String get functionLibraryDownloadSuccess;

  /// No description provided for @functionLibraryDownloadFailed.
  ///
  /// In zh, this message translates to:
  /// **'下载失败'**
  String get functionLibraryDownloadFailed;

  /// No description provided for @functionLibraryCloudUrlHint.
  ///
  /// In zh, this message translates to:
  /// **'输入云端服务地址'**
  String get functionLibraryCloudUrlHint;

  /// No description provided for @functionLibraryConfirm.
  ///
  /// In zh, this message translates to:
  /// **'确定'**
  String get functionLibraryConfirm;

  /// No description provided for @functionLibrarySyncStatus.
  ///
  /// In zh, this message translates to:
  /// **'同步状态'**
  String get functionLibrarySyncStatus;

  /// No description provided for @functionLibrarySynced.
  ///
  /// In zh, this message translates to:
  /// **'已同步'**
  String get functionLibrarySynced;

  /// No description provided for @functionLibraryLocalOnly.
  ///
  /// In zh, this message translates to:
  /// **'仅本地'**
  String get functionLibraryLocalOnly;

  /// No description provided for @functionLibraryCloudOnly.
  ///
  /// In zh, this message translates to:
  /// **'仅云端'**
  String get functionLibraryCloudOnly;

  /// No description provided for @functionLibraryStartNode.
  ///
  /// In zh, this message translates to:
  /// **'起始页面'**
  String get functionLibraryStartNode;

  /// No description provided for @functionLibraryEndNode.
  ///
  /// In zh, this message translates to:
  /// **'结束页面'**
  String get functionLibraryEndNode;

  /// No description provided for @functionLibraryLastRun.
  ///
  /// In zh, this message translates to:
  /// **'最近执行'**
  String get functionLibraryLastRun;

  /// No description provided for @functionLibraryLastRunSuccess.
  ///
  /// In zh, this message translates to:
  /// **'成功'**
  String get functionLibraryLastRunSuccess;

  /// No description provided for @functionLibraryLastRunFailed.
  ///
  /// In zh, this message translates to:
  /// **'失败'**
  String get functionLibraryLastRunFailed;

  /// No description provided for @functionLibraryLastRunGoal.
  ///
  /// In zh, this message translates to:
  /// **'任务'**
  String get functionLibraryLastRunGoal;

  /// No description provided for @functionLibraryNoDescription.
  ///
  /// In zh, this message translates to:
  /// **'无描述'**
  String get functionLibraryNoDescription;

  /// No description provided for @functionLibrarySummaryOnPage.
  ///
  /// In zh, this message translates to:
  /// **'在「{page}」页面'**
  String functionLibrarySummaryOnPage(Object page);

  /// No description provided for @functionLibrarySummaryFromTo.
  ///
  /// In zh, this message translates to:
  /// **'从「{from}」到「{to}」'**
  String functionLibrarySummaryFromTo(Object from, Object to);

  /// No description provided for @functionLibrarySummaryFrom.
  ///
  /// In zh, this message translates to:
  /// **'从「{from}」开始'**
  String functionLibrarySummaryFrom(Object from);

  /// No description provided for @functionLibrarySummaryTo.
  ///
  /// In zh, this message translates to:
  /// **'到达「{to}」'**
  String functionLibrarySummaryTo(Object to);

  /// No description provided for @functionLibrarySummarySteps.
  ///
  /// In zh, this message translates to:
  /// **'共 {count} 步操作'**
  String functionLibrarySummarySteps(Object count);

  /// No description provided for @functionLibrarySummaryParams.
  ///
  /// In zh, this message translates to:
  /// **'需要 {count} 个参数'**
  String functionLibrarySummaryParams(Object count);

  /// No description provided for @functionLibraryTest.
  ///
  /// In zh, this message translates to:
  /// **'测试'**
  String get functionLibraryTest;

  /// No description provided for @functionLibraryTestNeedParams.
  ///
  /// In zh, this message translates to:
  /// **'需要输入参数：{params}'**
  String functionLibraryTestNeedParams(Object params);

  /// No description provided for @functionLibraryTestStarted.
  ///
  /// In zh, this message translates to:
  /// **'已发起测试执行'**
  String get functionLibraryTestStarted;

  /// No description provided for @functionLibraryViewDetails.
  ///
  /// In zh, this message translates to:
  /// **'查看详情'**
  String get functionLibraryViewDetails;

  /// No description provided for @functionLibraryDetailCompileSurface.
  ///
  /// In zh, this message translates to:
  /// **'编译面'**
  String get functionLibraryDetailCompileSurface;

  /// No description provided for @functionLibraryDetailGraphAnchors.
  ///
  /// In zh, this message translates to:
  /// **'图锚点'**
  String get functionLibraryDetailGraphAnchors;

  /// No description provided for @functionLibraryDetailRunUsage.
  ///
  /// In zh, this message translates to:
  /// **'运行统计'**
  String get functionLibraryDetailRunUsage;

  /// No description provided for @functionLibraryDetailLifecycle.
  ///
  /// In zh, this message translates to:
  /// **'生命周期'**
  String get functionLibraryDetailLifecycle;

  /// No description provided for @functionLibraryDetailExamples.
  ///
  /// In zh, this message translates to:
  /// **'参数示例'**
  String get functionLibraryDetailExamples;

  /// No description provided for @functionLibraryDetailDerivedFrom.
  ///
  /// In zh, this message translates to:
  /// **'来源 raw function'**
  String get functionLibraryDetailDerivedFrom;

  /// No description provided for @functionLibraryDetailRuns.
  ///
  /// In zh, this message translates to:
  /// **'执行次数'**
  String get functionLibraryDetailRuns;

  /// No description provided for @functionLibraryDetailSuccessFail.
  ///
  /// In zh, this message translates to:
  /// **'成功 / 失败'**
  String get functionLibraryDetailSuccessFail;

  /// No description provided for @functionLibraryDetailUpdatedAt.
  ///
  /// In zh, this message translates to:
  /// **'更新时间'**
  String get functionLibraryDetailUpdatedAt;

  /// No description provided for @functionLibraryDetailBundleBacking.
  ///
  /// In zh, this message translates to:
  /// **'Bundle 资产'**
  String get functionLibraryDetailBundleBacking;

  /// No description provided for @functionLibraryDetailActionCount.
  ///
  /// In zh, this message translates to:
  /// **'动作数'**
  String get functionLibraryDetailActionCount;

  /// No description provided for @functionLibraryDetailActionPreview.
  ///
  /// In zh, this message translates to:
  /// **'动作预览'**
  String get functionLibraryDetailActionPreview;

  /// No description provided for @functionLibraryDetailNoActionPreview.
  ///
  /// In zh, this message translates to:
  /// **'无动作'**
  String get functionLibraryDetailNoActionPreview;

  /// No description provided for @functionLibraryDetailStepIndex.
  ///
  /// In zh, this message translates to:
  /// **'步骤 {index}'**
  String functionLibraryDetailStepIndex(String index);

  /// No description provided for @functionLibraryDetailBundleFunction.
  ///
  /// In zh, this message translates to:
  /// **'Bundle Function'**
  String get functionLibraryDetailBundleFunction;

  /// No description provided for @functionLibraryDetailInternalBlocks.
  ///
  /// In zh, this message translates to:
  /// **'内部 function block'**
  String get functionLibraryDetailInternalBlocks;

  /// No description provided for @functionLibraryDetailNoBlocks.
  ///
  /// In zh, this message translates to:
  /// **'当前没有展开的内部 function block'**
  String get functionLibraryDetailNoBlocks;

  /// No description provided for @functionLibraryDetailNoBundle.
  ///
  /// In zh, this message translates to:
  /// **'当前没有可展示的 bundle function'**
  String get functionLibraryDetailNoBundle;

  /// No description provided for @functionLibraryDetailFunctionSchema.
  ///
  /// In zh, this message translates to:
  /// **'Function Schema'**
  String get functionLibraryDetailFunctionSchema;

  /// No description provided for @executionCompileHit.
  ///
  /// In zh, this message translates to:
  /// **'复用技能'**
  String get executionCompileHit;

  /// No description provided for @executionCompileHitWithFunction.
  ///
  /// In zh, this message translates to:
  /// **'复用技能 · {functionId}'**
  String executionCompileHitWithFunction(Object functionId);

  /// No description provided for @executionVlmExecution.
  ///
  /// In zh, this message translates to:
  /// **'VLM 执行'**
  String get executionVlmExecution;

  /// No description provided for @executionActionOpenApp.
  ///
  /// In zh, this message translates to:
  /// **'打开应用'**
  String get executionActionOpenApp;

  /// No description provided for @executionActionClick.
  ///
  /// In zh, this message translates to:
  /// **'点击'**
  String get executionActionClick;

  /// No description provided for @executionActionClickNode.
  ///
  /// In zh, this message translates to:
  /// **'点击元素'**
  String get executionActionClickNode;

  /// No description provided for @executionActionLongPress.
  ///
  /// In zh, this message translates to:
  /// **'长按'**
  String get executionActionLongPress;

  /// No description provided for @executionActionInputText.
  ///
  /// In zh, this message translates to:
  /// **'输入文本'**
  String get executionActionInputText;

  /// No description provided for @executionActionSwipe.
  ///
  /// In zh, this message translates to:
  /// **'滑动'**
  String get executionActionSwipe;

  /// No description provided for @executionActionScroll.
  ///
  /// In zh, this message translates to:
  /// **'滚动'**
  String get executionActionScroll;

  /// No description provided for @executionActionPressKey.
  ///
  /// In zh, this message translates to:
  /// **'按键'**
  String get executionActionPressKey;

  /// No description provided for @executionActionWait.
  ///
  /// In zh, this message translates to:
  /// **'等待'**
  String get executionActionWait;

  /// No description provided for @executionActionFinished.
  ///
  /// In zh, this message translates to:
  /// **'完成'**
  String get executionActionFinished;

  /// No description provided for @executionActionCallFunction.
  ///
  /// In zh, this message translates to:
  /// **'调用技能'**
  String get executionActionCallFunction;

  /// No description provided for @executionActionDefault.
  ///
  /// In zh, this message translates to:
  /// **'动作'**
  String get executionActionDefault;

  /// No description provided for @executionStepLabel.
  ///
  /// In zh, this message translates to:
  /// **'{count} steps'**
  String executionStepLabel(Object count);

  /// No description provided for @executionSuccess.
  ///
  /// In zh, this message translates to:
  /// **'成功'**
  String get executionSuccess;

  /// No description provided for @executionFailed.
  ///
  /// In zh, this message translates to:
  /// **'失败'**
  String get executionFailed;

  /// No description provided for @memorySaveAsSkillTitle.
  ///
  /// In zh, this message translates to:
  /// **'保存为功能（Function）'**
  String get memorySaveAsSkillTitle;

  /// No description provided for @memorySaveAsSkillContent.
  ///
  /// In zh, this message translates to:
  /// **'是否将这次执行记录保存为可复用的功能？\n\n保存后可在「功能库」中查看和管理。'**
  String get memorySaveAsSkillContent;

  /// No description provided for @memorySavingProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在保存功能...'**
  String get memorySavingProgress;

  /// No description provided for @memorySaveSuccess.
  ///
  /// In zh, this message translates to:
  /// **'已保存为功能：{functionId}'**
  String memorySaveSuccess(String functionId);

  /// No description provided for @memorySaveSuccessSimple.
  ///
  /// In zh, this message translates to:
  /// **'已保存为功能'**
  String get memorySaveSuccessSimple;

  /// No description provided for @memorySaveSuccessHint.
  ///
  /// In zh, this message translates to:
  /// **'功能已保存到本地功能库。\n你可以在功能库中查看、编辑或升级此功能。'**
  String get memorySaveSuccessHint;

  /// No description provided for @memoryViewInLibrary.
  ///
  /// In zh, this message translates to:
  /// **'查看功能库'**
  String get memoryViewInLibrary;

  /// No description provided for @memorySaveCannotImport.
  ///
  /// In zh, this message translates to:
  /// **'该执行记录无法保存为功能'**
  String get memorySaveCannotImport;

  /// No description provided for @memorySaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存功能失败'**
  String get memorySaveFailed;

  /// No description provided for @memorySaveFailedWithMessage.
  ///
  /// In zh, this message translates to:
  /// **'保存功能失败：{message}'**
  String memorySaveFailedWithMessage(String message);

  /// No description provided for @memoryRunIdMissing.
  ///
  /// In zh, this message translates to:
  /// **'执行记录 ID 缺失，无法保存'**
  String get memoryRunIdMissing;

  /// No description provided for @omniflowAssetSuccess.
  ///
  /// In zh, this message translates to:
  /// **'成功'**
  String get omniflowAssetSuccess;

  /// No description provided for @omniflowAssetFailed.
  ///
  /// In zh, this message translates to:
  /// **'失败'**
  String get omniflowAssetFailed;

  /// No description provided for @omniflowAssetRunning.
  ///
  /// In zh, this message translates to:
  /// **'运行中'**
  String get omniflowAssetRunning;

  /// No description provided for @omniflowAssetUnknown.
  ///
  /// In zh, this message translates to:
  /// **'未知'**
  String get omniflowAssetUnknown;

  /// No description provided for @omniflowAssetCompileHit.
  ///
  /// In zh, this message translates to:
  /// **'复用功能'**
  String get omniflowAssetCompileHit;

  /// No description provided for @omniflowAssetCompileMiss.
  ///
  /// In zh, this message translates to:
  /// **'VLM 执行'**
  String get omniflowAssetCompileMiss;

  /// No description provided for @omniflowAssetSteps.
  ///
  /// In zh, this message translates to:
  /// **'{count} 步'**
  String omniflowAssetSteps(int count);

  /// No description provided for @omniflowAssetId.
  ///
  /// In zh, this message translates to:
  /// **'ID'**
  String get omniflowAssetId;

  /// No description provided for @omniflowAssetPackage.
  ///
  /// In zh, this message translates to:
  /// **'应用包名'**
  String get omniflowAssetPackage;

  /// No description provided for @omniflowAssetSourceRuns.
  ///
  /// In zh, this message translates to:
  /// **'来源执行'**
  String get omniflowAssetSourceRuns;

  /// No description provided for @omniflowAssetLinkedFunction.
  ///
  /// In zh, this message translates to:
  /// **'关联功能'**
  String get omniflowAssetLinkedFunction;

  /// No description provided for @omniflowAssetCopyId.
  ///
  /// In zh, this message translates to:
  /// **'复制 ID'**
  String get omniflowAssetCopyId;

  /// No description provided for @omniflowAssetEdit.
  ///
  /// In zh, this message translates to:
  /// **'编辑'**
  String get omniflowAssetEdit;

  /// No description provided for @omniflowAssetMemory.
  ///
  /// In zh, this message translates to:
  /// **'记忆'**
  String get omniflowAssetMemory;

  /// No description provided for @omniflowAssetReplay.
  ///
  /// In zh, this message translates to:
  /// **'重放'**
  String get omniflowAssetReplay;

  /// No description provided for @omniflowAssetDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get omniflowAssetDelete;

  /// No description provided for @omniflowAssetEnrich.
  ///
  /// In zh, this message translates to:
  /// **'升级'**
  String get omniflowAssetEnrich;

  /// No description provided for @omniflowAssetUpload.
  ///
  /// In zh, this message translates to:
  /// **'上传'**
  String get omniflowAssetUpload;

  /// No description provided for @omniflowAssetIdCopied.
  ///
  /// In zh, this message translates to:
  /// **'ID 已复制'**
  String get omniflowAssetIdCopied;

  /// No description provided for @omniflowAssetJsonCopied.
  ///
  /// In zh, this message translates to:
  /// **'JSON 已复制'**
  String get omniflowAssetJsonCopied;

  /// No description provided for @omniflowAssetFunctionDetail.
  ///
  /// In zh, this message translates to:
  /// **'功能详情'**
  String get omniflowAssetFunctionDetail;

  /// No description provided for @omniflowAssetRunLogDetail.
  ///
  /// In zh, this message translates to:
  /// **'执行记录详情'**
  String get omniflowAssetRunLogDetail;

  /// No description provided for @omniflowAssetCopyJson.
  ///
  /// In zh, this message translates to:
  /// **'复制 JSON'**
  String get omniflowAssetCopyJson;

  /// No description provided for @omniflowAssetClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭'**
  String get omniflowAssetClose;

  /// No description provided for @omniflowAssetStartPage.
  ///
  /// In zh, this message translates to:
  /// **'起始页面'**
  String get omniflowAssetStartPage;

  /// No description provided for @omniflowAssetEndPage.
  ///
  /// In zh, this message translates to:
  /// **'结束页面'**
  String get omniflowAssetEndPage;

  /// No description provided for @omniflowAssetCreatedAt.
  ///
  /// In zh, this message translates to:
  /// **'创建时间'**
  String get omniflowAssetCreatedAt;

  /// No description provided for @omniflowAssetGoal.
  ///
  /// In zh, this message translates to:
  /// **'目标'**
  String get omniflowAssetGoal;

  /// No description provided for @omniflowAssetStartedAt.
  ///
  /// In zh, this message translates to:
  /// **'开始时间'**
  String get omniflowAssetStartedAt;

  /// No description provided for @omniflowAssetDoneReason.
  ///
  /// In zh, this message translates to:
  /// **'完成原因'**
  String get omniflowAssetDoneReason;

  /// No description provided for @omniflowAssetView.
  ///
  /// In zh, this message translates to:
  /// **'查看'**
  String get omniflowAssetView;

  /// No description provided for @omniflowAssetLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载失败'**
  String get omniflowAssetLoadFailed;

  /// No description provided for @omniflowAssetRunLogNotReady.
  ///
  /// In zh, this message translates to:
  /// **'执行记录尚未落盘'**
  String get omniflowAssetRunLogNotReady;

  /// No description provided for @omniflowAssetRunLogIndexFailed.
  ///
  /// In zh, this message translates to:
  /// **'读取执行记录索引失败'**
  String get omniflowAssetRunLogIndexFailed;

  /// No description provided for @omniflowAssetReplayTitle.
  ///
  /// In zh, this message translates to:
  /// **'重放执行记录'**
  String get omniflowAssetReplayTitle;

  /// No description provided for @omniflowAssetReplayConfirm.
  ///
  /// In zh, this message translates to:
  /// **'是否确定重放这次执行记录？'**
  String get omniflowAssetReplayConfirm;

  /// No description provided for @omniflowAssetReplayProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在重放执行记录...'**
  String get omniflowAssetReplayProgress;

  /// No description provided for @omniflowAssetReplaySuccess.
  ///
  /// In zh, this message translates to:
  /// **'重放成功'**
  String get omniflowAssetReplaySuccess;

  /// No description provided for @omniflowAssetReplaySuccessWithId.
  ///
  /// In zh, this message translates to:
  /// **'重放成功：{functionId}'**
  String omniflowAssetReplaySuccessWithId(String functionId);

  /// No description provided for @omniflowAssetReplayFailed.
  ///
  /// In zh, this message translates to:
  /// **'重放失败'**
  String get omniflowAssetReplayFailed;

  /// No description provided for @omniflowAssetReplayFailedWithMessage.
  ///
  /// In zh, this message translates to:
  /// **'重放失败：{message}'**
  String omniflowAssetReplayFailedWithMessage(String message);

  /// No description provided for @omniflowAssetCancel.
  ///
  /// In zh, this message translates to:
  /// **'取消'**
  String get omniflowAssetCancel;

  /// No description provided for @omniflowAssetConfirm.
  ///
  /// In zh, this message translates to:
  /// **'确定'**
  String get omniflowAssetConfirm;

  /// No description provided for @omniflowAssetCopySuccess.
  ///
  /// In zh, this message translates to:
  /// **'{label} 已复制'**
  String omniflowAssetCopySuccess(String label);

  /// No description provided for @omniflowAssetCopyFailed.
  ///
  /// In zh, this message translates to:
  /// **'{label} 复制失败'**
  String omniflowAssetCopyFailed(String label);

  /// No description provided for @omniflowAssetEmpty.
  ///
  /// In zh, this message translates to:
  /// **'{label} 为空'**
  String omniflowAssetEmpty(String label);

  /// No description provided for @omniflowAssetNoSteps.
  ///
  /// In zh, this message translates to:
  /// **'没有可展示的步骤'**
  String get omniflowAssetNoSteps;

  /// No description provided for @functionLibraryEnrich.
  ///
  /// In zh, this message translates to:
  /// **'升级'**
  String get functionLibraryEnrich;

  /// No description provided for @functionLibraryEnrichTitle.
  ///
  /// In zh, this message translates to:
  /// **'升级功能'**
  String get functionLibraryEnrichTitle;

  /// No description provided for @functionLibraryEnrichConfirm.
  ///
  /// In zh, this message translates to:
  /// **'使用 AI 补齐此功能的语义信息？\n\n将自动生成：描述、参数槽位、前置/后置条件等。'**
  String get functionLibraryEnrichConfirm;

  /// No description provided for @functionLibraryEnrichProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在升级功能...'**
  String get functionLibraryEnrichProgress;

  /// No description provided for @functionLibraryEnrichSuccess.
  ///
  /// In zh, this message translates to:
  /// **'功能升级成功'**
  String get functionLibraryEnrichSuccess;

  /// No description provided for @functionLibraryEnrichFailed.
  ///
  /// In zh, this message translates to:
  /// **'升级失败'**
  String get functionLibraryEnrichFailed;

  /// No description provided for @functionLibraryEnrichFailedWithMessage.
  ///
  /// In zh, this message translates to:
  /// **'升级失败：{message}'**
  String functionLibraryEnrichFailedWithMessage(String message);

  /// No description provided for @functionLibrarySplit.
  ///
  /// In zh, this message translates to:
  /// **'拆分'**
  String get functionLibrarySplit;

  /// No description provided for @functionLibrarySplitTitle.
  ///
  /// In zh, this message translates to:
  /// **'拆分功能'**
  String get functionLibrarySplitTitle;

  /// No description provided for @functionLibrarySplitConfirm.
  ///
  /// In zh, this message translates to:
  /// **'使用 AI 将此功能拆分为多个更小的功能？'**
  String get functionLibrarySplitConfirm;

  /// No description provided for @functionLibrarySplitProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在拆分功能...'**
  String get functionLibrarySplitProgress;

  /// No description provided for @functionLibrarySplitSuccess.
  ///
  /// In zh, this message translates to:
  /// **'功能拆分成功，生成了 {count} 个新功能'**
  String functionLibrarySplitSuccess(int count);

  /// No description provided for @functionLibrarySplitFailed.
  ///
  /// In zh, this message translates to:
  /// **'拆分失败'**
  String get functionLibrarySplitFailed;

  /// No description provided for @actionTypeOpenApp.
  ///
  /// In zh, this message translates to:
  /// **'打开应用'**
  String get actionTypeOpenApp;

  /// No description provided for @actionTypeClick.
  ///
  /// In zh, this message translates to:
  /// **'点击'**
  String get actionTypeClick;

  /// No description provided for @actionTypeClickNode.
  ///
  /// In zh, this message translates to:
  /// **'点击节点'**
  String get actionTypeClickNode;

  /// No description provided for @actionTypeLongPress.
  ///
  /// In zh, this message translates to:
  /// **'长按'**
  String get actionTypeLongPress;

  /// No description provided for @actionTypeInputText.
  ///
  /// In zh, this message translates to:
  /// **'输入文本'**
  String get actionTypeInputText;

  /// No description provided for @actionTypeSwipe.
  ///
  /// In zh, this message translates to:
  /// **'滑动'**
  String get actionTypeSwipe;

  /// No description provided for @actionTypePressKey.
  ///
  /// In zh, this message translates to:
  /// **'按键'**
  String get actionTypePressKey;

  /// No description provided for @actionTypeWait.
  ///
  /// In zh, this message translates to:
  /// **'等待'**
  String get actionTypeWait;

  /// No description provided for @actionTypeFinished.
  ///
  /// In zh, this message translates to:
  /// **'结束'**
  String get actionTypeFinished;

  /// No description provided for @actionTypeCallFunction.
  ///
  /// In zh, this message translates to:
  /// **'调用功能'**
  String get actionTypeCallFunction;

  /// No description provided for @actionTypeDefault.
  ///
  /// In zh, this message translates to:
  /// **'动作'**
  String get actionTypeDefault;

  /// No description provided for @omniflowProviderUpdate.
  ///
  /// In zh, this message translates to:
  /// **'Provider 更新'**
  String get omniflowProviderUpdate;

  /// No description provided for @omniflowConnectionMode.
  ///
  /// In zh, this message translates to:
  /// **'当前连接'**
  String get omniflowConnectionMode;

  /// No description provided for @omniflowConnectionModeBridge.
  ///
  /// In zh, this message translates to:
  /// **'Bridge 连接'**
  String get omniflowConnectionModeBridge;

  /// No description provided for @omniflowConnectionModeEmbedded.
  ///
  /// In zh, this message translates to:
  /// **'本地内置'**
  String get omniflowConnectionModeEmbedded;

  /// No description provided for @omniflowProviderVersion.
  ///
  /// In zh, this message translates to:
  /// **'Provider 版本'**
  String get omniflowProviderVersion;

  /// No description provided for @omniflowProviderPort.
  ///
  /// In zh, this message translates to:
  /// **'Provider 端口'**
  String get omniflowProviderPort;

  /// No description provided for @omniflowProviderStore.
  ///
  /// In zh, this message translates to:
  /// **'Provider Store'**
  String get omniflowProviderStore;

  /// No description provided for @omniflowCurrentVersion.
  ///
  /// In zh, this message translates to:
  /// **'当前版本'**
  String get omniflowCurrentVersion;

  /// No description provided for @omniflowLatestVersion.
  ///
  /// In zh, this message translates to:
  /// **'最新版本'**
  String get omniflowLatestVersion;

  /// No description provided for @omniflowUpdateAvailable.
  ///
  /// In zh, this message translates to:
  /// **'有新版本可用'**
  String get omniflowUpdateAvailable;

  /// No description provided for @omniflowUpdateNotSupported.
  ///
  /// In zh, this message translates to:
  /// **'有新版本，但主机 Provider 不支持自动更新（请手动 git pull）'**
  String get omniflowUpdateNotSupported;

  /// No description provided for @omniflowStartProviderFirst.
  ///
  /// In zh, this message translates to:
  /// **'请先启动 Provider 后再检查更新'**
  String get omniflowStartProviderFirst;

  /// No description provided for @omniflowCheckUpdate.
  ///
  /// In zh, this message translates to:
  /// **'检查更新'**
  String get omniflowCheckUpdate;

  /// No description provided for @omniflowCheckingUpdate.
  ///
  /// In zh, this message translates to:
  /// **'检查中...'**
  String get omniflowCheckingUpdate;

  /// No description provided for @omniflowApplyUpdate.
  ///
  /// In zh, this message translates to:
  /// **'立即更新'**
  String get omniflowApplyUpdate;

  /// No description provided for @omniflowApplyingUpdate.
  ///
  /// In zh, this message translates to:
  /// **'更新中...'**
  String get omniflowApplyingUpdate;

  /// No description provided for @omniflowCheckUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'检查更新失败'**
  String get omniflowCheckUpdateFailed;

  /// No description provided for @omniflowNewVersionFound.
  ///
  /// In zh, this message translates to:
  /// **'发现新版本: {version}'**
  String omniflowNewVersionFound(String version);

  /// No description provided for @omniflowPackageNotInstalled.
  ///
  /// In zh, this message translates to:
  /// **'当前设备未安装'**
  String get omniflowPackageNotInstalled;

  /// No description provided for @omniflowAlreadyLatest.
  ///
  /// In zh, this message translates to:
  /// **'已是最新版本'**
  String get omniflowAlreadyLatest;

  /// No description provided for @omniflowUpdateSuccess.
  ///
  /// In zh, this message translates to:
  /// **'当前设备已更新到 {version}'**
  String omniflowUpdateSuccess(String version);

  /// No description provided for @omniflowUpdateBridgeModeHint.
  ///
  /// In zh, this message translates to:
  /// **'当前连接仍是 Bridge；这次只更新了设备里的 OmniFlow 包。'**
  String get omniflowUpdateBridgeModeHint;

  /// No description provided for @omniflowUpdateRestartRequired.
  ///
  /// In zh, this message translates to:
  /// **'设备包已更新；请手动重启本地内置 Provider 后生效。'**
  String get omniflowUpdateRestartRequired;

  /// No description provided for @omniflowUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'更新失败'**
  String get omniflowUpdateFailed;

  /// No description provided for @executionRouteMemorized.
  ///
  /// In zh, this message translates to:
  /// **'⚡ 已记忆'**
  String get executionRouteMemorized;

  /// No description provided for @executionRouteAiPlanning.
  ///
  /// In zh, this message translates to:
  /// **'🤔 AI规划'**
  String get executionRouteAiPlanning;

  /// No description provided for @runLogTimelineTitle.
  ///
  /// In zh, this message translates to:
  /// **'执行步骤'**
  String get runLogTimelineTitle;

  /// No description provided for @runLogTimelineViewSteps.
  ///
  /// In zh, this message translates to:
  /// **'查看步骤'**
  String get runLogTimelineViewSteps;

  /// No description provided for @runLogTimelineStepCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 步'**
  String runLogTimelineStepCount(int count);

  /// No description provided for @runLogTimelineLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载步骤失败'**
  String get runLogTimelineLoadFailed;

  /// No description provided for @runLogTimelineEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无步骤数据'**
  String get runLogTimelineEmpty;

  /// No description provided for @runLogTimelineUnknown.
  ///
  /// In zh, this message translates to:
  /// **'未知'**
  String get runLogTimelineUnknown;

  /// No description provided for @chatInputCommandTooltip.
  ///
  /// In zh, this message translates to:
  /// **'命令'**
  String get chatInputCommandTooltip;

  /// No description provided for @workbenchTitle.
  ///
  /// In zh, this message translates to:
  /// **'工作台'**
  String get workbenchTitle;

  /// No description provided for @workbenchWorkspaceTitle.
  ///
  /// In zh, this message translates to:
  /// **'Workspace'**
  String get workbenchWorkspaceTitle;

  /// No description provided for @workbenchWorkspaceOpenWorkbench.
  ///
  /// In zh, this message translates to:
  /// **'打开 Workbench'**
  String get workbenchWorkspaceOpenWorkbench;

  /// No description provided for @workbenchWorkspaceOpenProjectConsole.
  ///
  /// In zh, this message translates to:
  /// **'打开 Project 控制台'**
  String get workbenchWorkspaceOpenProjectConsole;

  /// No description provided for @workbenchWorkspaceWorkMode.
  ///
  /// In zh, this message translates to:
  /// **'Work'**
  String get workbenchWorkspaceWorkMode;

  /// No description provided for @workbenchWorkspaceProjectMode.
  ///
  /// In zh, this message translates to:
  /// **'Project'**
  String get workbenchWorkspaceProjectMode;

  /// No description provided for @workbenchWorkspaceProjectFrontendsTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 窗口'**
  String get workbenchWorkspaceProjectFrontendsTitle;

  /// No description provided for @workbenchWorkspaceProjectFrontendsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启 Project 模式后，这里像子窗口一样直接承载当前激活 Project 的 OOB 原生前端。'**
  String get workbenchWorkspaceProjectFrontendsSubtitle;

  /// No description provided for @workbenchWorkspaceProjectFrontendsEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无 Project 前端。回到对话里描述需求后，Agent 会通过 Workbench 创建可显示的 Project。'**
  String get workbenchWorkspaceProjectFrontendsEmpty;

  /// No description provided for @workbenchWorkspaceProjectOpenFailed.
  ///
  /// In zh, this message translates to:
  /// **'打开 Project 前端失败'**
  String get workbenchWorkspaceProjectOpenFailed;

  /// No description provided for @workbenchWorkspaceProjectUnsupportedDisplay.
  ///
  /// In zh, this message translates to:
  /// **'这个 Display 暂不支持内嵌窗口显示，请用右上角打开为完整页面。'**
  String get workbenchWorkspaceProjectUnsupportedDisplay;

  /// No description provided for @workbenchWorkspaceGuideTooltip.
  ///
  /// In zh, this message translates to:
  /// **'查看 Project 工作台说明'**
  String get workbenchWorkspaceGuideTooltip;

  /// No description provided for @workbenchWorkspaceGuideClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭说明'**
  String get workbenchWorkspaceGuideClose;

  /// No description provided for @workbenchWorkspaceGuideTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 工作台怎么工作'**
  String get workbenchWorkspaceGuideTitle;

  /// No description provided for @workbenchWorkspaceGuideIntro.
  ///
  /// In zh, this message translates to:
  /// **'Project 模式不是新的聊天页，而是 OOB 里用来承载 vibe project 的原生工作台。它把生成前端、后端 API、Workspace 文件、Skill 和持久化数据连成一个可继续编辑的单位。'**
  String get workbenchWorkspaceGuideIntro;

  /// No description provided for @workbenchWorkspaceGuideFlowTitle.
  ///
  /// In zh, this message translates to:
  /// **'交互链路'**
  String get workbenchWorkspaceGuideFlowTitle;

  /// No description provided for @workbenchWorkspaceGuideFlowPrompt.
  ///
  /// In zh, this message translates to:
  /// **'Prompt + Skill 拆解需求'**
  String get workbenchWorkspaceGuideFlowPrompt;

  /// No description provided for @workbenchWorkspaceGuideFlowProject.
  ///
  /// In zh, this message translates to:
  /// **'Project registry 记录容器'**
  String get workbenchWorkspaceGuideFlowProject;

  /// No description provided for @workbenchWorkspaceGuideFlowApi.
  ///
  /// In zh, this message translates to:
  /// **'Project API Registry 注册业务后端'**
  String get workbenchWorkspaceGuideFlowApi;

  /// No description provided for @workbenchWorkspaceGuideFlowDisplay.
  ///
  /// In zh, this message translates to:
  /// **'Flutter Display 展示业务前端'**
  String get workbenchWorkspaceGuideFlowDisplay;

  /// No description provided for @workbenchWorkspaceGuideFlowPersist.
  ///
  /// In zh, this message translates to:
  /// **'data/ + logs/ 持久化 AI 与 UI 调用'**
  String get workbenchWorkspaceGuideFlowPersist;

  /// No description provided for @workbenchWorkspaceGuideProjectTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 绑定什么'**
  String get workbenchWorkspaceGuideProjectTitle;

  /// No description provided for @workbenchWorkspaceGuideProjectBody.
  ///
  /// In zh, this message translates to:
  /// **'一个 Project 会绑定目标、Skill、Workspace 文件、Display 列表、业务 API、数据和日志。它不是 MCP 工具列表，也不是随手生成的 HTML。'**
  String get workbenchWorkspaceGuideProjectBody;

  /// No description provided for @workbenchWorkspaceGuideFrontendTitle.
  ///
  /// In zh, this message translates to:
  /// **'前端怎么显示'**
  String get workbenchWorkspaceGuideFrontendTitle;

  /// No description provided for @workbenchWorkspaceGuideFrontendBody.
  ///
  /// In zh, this message translates to:
  /// **'生成前端是 OOB 原生 Flutter Display。Workspace 切到 Project 后，不再显示大型管理列表，而是像浏览器子窗口一样直接承载当前激活 Project 的首页；一个 Project 可以有多个 Display，可用小菜单切换。'**
  String get workbenchWorkspaceGuideFrontendBody;

  /// No description provided for @workbenchWorkspaceGuideBackendTitle.
  ///
  /// In zh, this message translates to:
  /// **'后端怎么被调用'**
  String get workbenchWorkspaceGuideBackendTitle;

  /// No description provided for @workbenchWorkspaceGuideBackendBody.
  ///
  /// In zh, this message translates to:
  /// **'后端能力注册在 Project API Registry 里，例如 todo.add、todo.finish。AI 层和前端按钮都调用同一条 workbenchApiCall(projectId, apiId, inputs)，Project 创建、导出、删除等控制接口不会混进业务 API。'**
  String get workbenchWorkspaceGuideBackendBody;

  /// No description provided for @workbenchWorkspaceGuideDataTitle.
  ///
  /// In zh, this message translates to:
  /// **'数据怎么流'**
  String get workbenchWorkspaceGuideDataTitle;

  /// No description provided for @workbenchWorkspaceGuideDataBody.
  ///
  /// In zh, this message translates to:
  /// **'调用会经过 Flutter -> MethodChannel -> OOB native executor，然后写入 Project 的 data/ 和 logs/。前端刷新、AI 调用统计和重启后的状态都来自这份持久化数据。'**
  String get workbenchWorkspaceGuideDataBody;

  /// No description provided for @workbenchWorkspaceGuideVibeTitle.
  ///
  /// In zh, this message translates to:
  /// **'怎么继续改'**
  String get workbenchWorkspaceGuideVibeTitle;

  /// No description provided for @workbenchWorkspaceGuideVibeBody.
  ///
  /// In zh, this message translates to:
  /// **'要继续 vibe coding，回到 Home 大输入框说需求。Workbench Skill 会判断是创建新 Project、扩充后端 API、调整 Display，还是对当前 Project 做 hot update。'**
  String get workbenchWorkspaceGuideVibeBody;

  /// No description provided for @workbenchWorkspaceGuideExtendTitle.
  ///
  /// In zh, this message translates to:
  /// **'扩充后端工具'**
  String get workbenchWorkspaceGuideExtendTitle;

  /// No description provided for @workbenchWorkspaceGuideExtendBody.
  ///
  /// In zh, this message translates to:
  /// **'新增能力时先定义 apiId、输入输出 schema、executorKind、持久化文件和前端触发位置，再通过 Workbench 接口注册 Project API；不要手写 registry 文件。'**
  String get workbenchWorkspaceGuideExtendBody;

  /// No description provided for @workbenchWorkspaceProjectApiStats.
  ///
  /// In zh, this message translates to:
  /// **'{apiCount} 个 API · 已执行 {executionCount} 次'**
  String workbenchWorkspaceProjectApiStats(int apiCount, int executionCount);

  /// No description provided for @workbenchSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'一个 OOB 原生 Project 示例，用来验证 Project API 注册、状态持久化和 Workbench 内显示。'**
  String get workbenchSubtitle;

  /// No description provided for @workbenchVibeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'Prompt 生成的原生前端、Project API 和 Workspace 文件在 OOB 内保持关联。'**
  String get workbenchVibeSubtitle;

  /// No description provided for @workbenchGeneratedTodoSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'Todo 日志 (TODO) 是这个 Project 的业务前端之一。它和 Project 控制面分开，并直接调用已注册的业务 API。'**
  String get workbenchGeneratedTodoSubtitle;

  /// No description provided for @workbenchMockProjectName.
  ///
  /// In zh, this message translates to:
  /// **'Todo 日志 Mock'**
  String get workbenchMockProjectName;

  /// No description provided for @workbenchTemplateProjectName.
  ///
  /// In zh, this message translates to:
  /// **'Todo 日志 Workbench'**
  String get workbenchTemplateProjectName;

  /// No description provided for @workbenchNativeUi.
  ///
  /// In zh, this message translates to:
  /// **'OOB 原生显示'**
  String get workbenchNativeUi;

  /// No description provided for @workbenchProjectSection.
  ///
  /// In zh, this message translates to:
  /// **'项目'**
  String get workbenchProjectSection;

  /// No description provided for @workbenchProjectIdLabel.
  ///
  /// In zh, this message translates to:
  /// **'项目 ID'**
  String get workbenchProjectIdLabel;

  /// No description provided for @workbenchTemplateIdLabel.
  ///
  /// In zh, this message translates to:
  /// **'模板 ID'**
  String get workbenchTemplateIdLabel;

  /// No description provided for @workbenchRouteLabel.
  ///
  /// In zh, this message translates to:
  /// **'路由'**
  String get workbenchRouteLabel;

  /// No description provided for @workbenchSpacePathLabel.
  ///
  /// In zh, this message translates to:
  /// **'Space 路径'**
  String get workbenchSpacePathLabel;

  /// No description provided for @workbenchPageIdsLabel.
  ///
  /// In zh, this message translates to:
  /// **'页面'**
  String get workbenchPageIdsLabel;

  /// No description provided for @workbenchDevelopmentMode.
  ///
  /// In zh, this message translates to:
  /// **'开发模式'**
  String get workbenchDevelopmentMode;

  /// No description provided for @workbenchProjectRegistryPath.
  ///
  /// In zh, this message translates to:
  /// **'Project 注册表'**
  String get workbenchProjectRegistryPath;

  /// No description provided for @workbenchApiRegistryPath.
  ///
  /// In zh, this message translates to:
  /// **'API 注册表'**
  String get workbenchApiRegistryPath;

  /// No description provided for @workbenchProjectFilePath.
  ///
  /// In zh, this message translates to:
  /// **'Project 文件'**
  String get workbenchProjectFilePath;

  /// No description provided for @workbenchDataFilePath.
  ///
  /// In zh, this message translates to:
  /// **'数据文件'**
  String get workbenchDataFilePath;

  /// No description provided for @workbenchLogFilePath.
  ///
  /// In zh, this message translates to:
  /// **'API 日志'**
  String get workbenchLogFilePath;

  /// No description provided for @workbenchBackendTools.
  ///
  /// In zh, this message translates to:
  /// **'后端工具'**
  String get workbenchBackendTools;

  /// No description provided for @workbenchFrontendBinding.
  ///
  /// In zh, this message translates to:
  /// **'前后端绑定'**
  String get workbenchFrontendBinding;

  /// No description provided for @workbenchTodoLog.
  ///
  /// In zh, this message translates to:
  /// **'Todo 日志'**
  String get workbenchTodoLog;

  /// No description provided for @workbenchCallApi.
  ///
  /// In zh, this message translates to:
  /// **'调用 API'**
  String get workbenchCallApi;

  /// No description provided for @workbenchGeneratedFrontend.
  ///
  /// In zh, this message translates to:
  /// **'生成的前端'**
  String get workbenchGeneratedFrontend;

  /// No description provided for @workbenchGeneratedFrontendSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'打开 prompt 生成页面应该挂载的 OOB 原生预览容器。它和 AI 层共用同一组 Project API 与持久化数据。'**
  String get workbenchGeneratedFrontendSubtitle;

  /// No description provided for @workbenchOpenGeneratedFrontend.
  ///
  /// In zh, this message translates to:
  /// **'打开生成前端'**
  String get workbenchOpenGeneratedFrontend;

  /// No description provided for @workbenchPreviewClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭预览'**
  String get workbenchPreviewClose;

  /// No description provided for @workbenchToolList.
  ///
  /// In zh, this message translates to:
  /// **'Project API'**
  String get workbenchToolList;

  /// No description provided for @workbenchProjectControlSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'这里只展示已注册的业务 API。Project 创建和打开仍属于 OOB Workbench 控制面。'**
  String get workbenchProjectControlSubtitle;

  /// No description provided for @workbenchOpenWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'打开 Workspace'**
  String get workbenchOpenWorkspace;

  /// No description provided for @workbenchApiEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无已注册 Project API'**
  String get workbenchApiEmpty;

  /// No description provided for @workbenchToolListDefaultTodo.
  ///
  /// In zh, this message translates to:
  /// **'Project API 点击了同一个后端'**
  String get workbenchToolListDefaultTodo;

  /// No description provided for @workbenchToolExecutionCount.
  ///
  /// In zh, this message translates to:
  /// **'已执行 {count} 次'**
  String workbenchToolExecutionCount(int count);

  /// No description provided for @workbenchToolAddTodoTitle.
  ///
  /// In zh, this message translates to:
  /// **'新增 Todo'**
  String get workbenchToolAddTodoTitle;

  /// No description provided for @workbenchToolAddTodoDesc.
  ///
  /// In zh, this message translates to:
  /// **'通过已注册的原生 Project API 创建一条 todo。'**
  String get workbenchToolAddTodoDesc;

  /// No description provided for @workbenchToolFinishTodoTitle.
  ///
  /// In zh, this message translates to:
  /// **'归档 Todo'**
  String get workbenchToolFinishTodoTitle;

  /// No description provided for @workbenchToolFinishTodoDesc.
  ///
  /// In zh, this message translates to:
  /// **'通过已注册的原生 Project API 把一条 todo 归档。'**
  String get workbenchToolFinishTodoDesc;

  /// No description provided for @workbenchFlowAddTodo.
  ///
  /// In zh, this message translates to:
  /// **'页面输入 -> todo.add -> todo 列表'**
  String get workbenchFlowAddTodo;

  /// No description provided for @workbenchFlowFinishTodo.
  ///
  /// In zh, this message translates to:
  /// **'完成按钮 -> todo.finish -> 完成列表'**
  String get workbenchFlowFinishTodo;

  /// No description provided for @workbenchAddTodoHint.
  ///
  /// In zh, this message translates to:
  /// **'添加一条 todo'**
  String get workbenchAddTodoHint;

  /// No description provided for @workbenchAddTodo.
  ///
  /// In zh, this message translates to:
  /// **'添加 Todo'**
  String get workbenchAddTodo;

  /// No description provided for @workbenchTodoEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无 todo'**
  String get workbenchTodoEmpty;

  /// No description provided for @workbenchTodoOpen.
  ///
  /// In zh, this message translates to:
  /// **'待完成'**
  String get workbenchTodoOpen;

  /// No description provided for @workbenchTodoFinished.
  ///
  /// In zh, this message translates to:
  /// **'已归档'**
  String get workbenchTodoFinished;

  /// No description provided for @workbenchFinishTodo.
  ///
  /// In zh, this message translates to:
  /// **'归档 todo'**
  String get workbenchFinishTodo;

  /// No description provided for @workbenchTodoAdded.
  ///
  /// In zh, this message translates to:
  /// **'Todo 已添加'**
  String get workbenchTodoAdded;

  /// No description provided for @workbenchTodoFinishedToast.
  ///
  /// In zh, this message translates to:
  /// **'Todo 已归档'**
  String get workbenchTodoFinishedToast;

  /// No description provided for @workbenchTodoInputRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入 todo'**
  String get workbenchTodoInputRequired;

  /// No description provided for @workbenchNoOpenTodo.
  ///
  /// In zh, this message translates to:
  /// **'没有可完成的 todo'**
  String get workbenchNoOpenTodo;

  /// No description provided for @workbenchLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载失败'**
  String get workbenchLoadFailed;

  /// No description provided for @workbenchUnknownTool.
  ///
  /// In zh, this message translates to:
  /// **'Workbench 工具执行失败'**
  String get workbenchUnknownTool;

  /// No description provided for @workbenchStatusOpen.
  ///
  /// In zh, this message translates to:
  /// **'等待处理'**
  String get workbenchStatusOpen;

  /// No description provided for @workbenchStatusFinished.
  ///
  /// In zh, this message translates to:
  /// **'已归档'**
  String get workbenchStatusFinished;

  /// No description provided for @workbenchAssistantName.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get workbenchAssistantName;

  /// No description provided for @workbenchAssistantTooltip.
  ///
  /// In zh, this message translates to:
  /// **'打开小万'**
  String get workbenchAssistantTooltip;

  /// No description provided for @workbenchAssistantPromptHint.
  ///
  /// In zh, this message translates to:
  /// **'说出你想实时调整的地方'**
  String get workbenchAssistantPromptHint;

  /// No description provided for @workbenchAssistantSend.
  ///
  /// In zh, this message translates to:
  /// **'热更新当前 Project'**
  String get workbenchAssistantSend;

  /// No description provided for @workbenchAssistantApplied.
  ///
  /// In zh, this message translates to:
  /// **'Project 已热更新'**
  String get workbenchAssistantApplied;

  /// No description provided for @workbenchAssistantPromptRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入要调整的内容'**
  String get workbenchAssistantPromptRequired;

  /// No description provided for @workbenchAssistantNoProject.
  ///
  /// In zh, this message translates to:
  /// **'请先选择一个 Project'**
  String get workbenchAssistantNoProject;

  /// No description provided for @workbenchAssistantHotUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'Project 热更新失败'**
  String get workbenchAssistantHotUpdateFailed;

  /// No description provided for @workbenchProjectModeTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 工作台'**
  String get workbenchProjectModeTitle;

  /// No description provided for @workbenchFlutterDisplay.
  ///
  /// In zh, this message translates to:
  /// **'Flutter 显示器'**
  String get workbenchFlutterDisplay;

  /// No description provided for @workbenchProjectSwitcher.
  ///
  /// In zh, this message translates to:
  /// **'切换 Project'**
  String get workbenchProjectSwitcher;

  /// No description provided for @workbenchProjectGenerateTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 容器'**
  String get workbenchProjectGenerateTitle;

  /// No description provided for @workbenchProjectGenerateSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'这里只选择和打开 Project 容器。创建、编辑和热更新继续回到首页大输入框，由当前激活的 Project toolbox 承接。'**
  String get workbenchProjectGenerateSubtitle;

  /// No description provided for @workbenchProjectPromptHint.
  ///
  /// In zh, this message translates to:
  /// **'回到首页输入 Project 需求'**
  String get workbenchProjectPromptHint;

  /// No description provided for @workbenchProjectDefaultPrompt.
  ///
  /// In zh, this message translates to:
  /// **'我想创建一个简单的 todolist 管理系统，要求可以增加 todo，归档 todo'**
  String get workbenchProjectDefaultPrompt;

  /// No description provided for @workbenchProjectGenerateButton.
  ///
  /// In zh, this message translates to:
  /// **'回到首页继续'**
  String get workbenchProjectGenerateButton;

  /// No description provided for @workbenchInputProjectTooltip.
  ///
  /// In zh, this message translates to:
  /// **'打开 Project 工作台'**
  String get workbenchInputProjectTooltip;

  /// No description provided for @workbenchGeneratedTodoProjectName.
  ///
  /// In zh, this message translates to:
  /// **'Todo List 工作台'**
  String get workbenchGeneratedTodoProjectName;

  /// No description provided for @workbenchPromptSeedAddTodo.
  ///
  /// In zh, this message translates to:
  /// **'验证可以增加 todo'**
  String get workbenchPromptSeedAddTodo;

  /// No description provided for @workbenchPromptSeedArchiveTodo.
  ///
  /// In zh, this message translates to:
  /// **'验证可以归档 todo'**
  String get workbenchPromptSeedArchiveTodo;

  /// No description provided for @workbenchProjectPlanTitle.
  ///
  /// In zh, this message translates to:
  /// **'拆分计划'**
  String get workbenchProjectPlanTitle;

  /// No description provided for @workbenchProjectPlanProject.
  ///
  /// In zh, this message translates to:
  /// **'创建 Project 注册和可编辑 Workspace'**
  String get workbenchProjectPlanProject;

  /// No description provided for @workbenchProjectPlanFrontend.
  ///
  /// In zh, this message translates to:
  /// **'生成 OOB 原生 Flutter 前端'**
  String get workbenchProjectPlanFrontend;

  /// No description provided for @workbenchProjectPlanApi.
  ///
  /// In zh, this message translates to:
  /// **'注册 AI/UI 共用业务 API'**
  String get workbenchProjectPlanApi;

  /// No description provided for @workbenchProjectPlanData.
  ///
  /// In zh, this message translates to:
  /// **'写入持久化数据和 API 日志'**
  String get workbenchProjectPlanData;

  /// No description provided for @workbenchUseMode.
  ///
  /// In zh, this message translates to:
  /// **'使用模式'**
  String get workbenchUseMode;

  /// No description provided for @workbenchDebugMode.
  ///
  /// In zh, this message translates to:
  /// **'Debug 模式'**
  String get workbenchDebugMode;

  /// No description provided for @workbenchDisplaysTitle.
  ///
  /// In zh, this message translates to:
  /// **'前端 Displays'**
  String get workbenchDisplaysTitle;

  /// No description provided for @workbenchDisplayCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 个前端'**
  String workbenchDisplayCount(int count);

  /// No description provided for @workbenchUnnamedDisplay.
  ///
  /// In zh, this message translates to:
  /// **'未命名前端'**
  String get workbenchUnnamedDisplay;

  /// No description provided for @workbenchOpenDisplay.
  ///
  /// In zh, this message translates to:
  /// **'打开这个前端'**
  String get workbenchOpenDisplay;

  /// No description provided for @workbenchDebugDisplay.
  ///
  /// In zh, this message translates to:
  /// **'调试这个前端'**
  String get workbenchDebugDisplay;

  /// No description provided for @workbenchProjectCurrentTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 使用台'**
  String get workbenchProjectCurrentTitle;

  /// No description provided for @workbenchProjectCurrentSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'默认打开前端会回到首页；调试打开会回到工作台。热更新通过首页大输入框和当前激活 Project 完成。'**
  String get workbenchProjectCurrentSubtitle;

  /// No description provided for @workbenchProjectModeCreateTitle.
  ///
  /// In zh, this message translates to:
  /// **'Vibe Project 入口'**
  String get workbenchProjectModeCreateTitle;

  /// No description provided for @workbenchProjectModeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'选择一个 Project 作为 Agent 当前工作环境；打开它的 Flutter 前端、查看 API 执行次数，并管理 Workspace、导出和删除。'**
  String get workbenchProjectModeSubtitle;

  /// No description provided for @workbenchProjectModeCreateButton.
  ///
  /// In zh, this message translates to:
  /// **'创建 Todo demo'**
  String get workbenchProjectModeCreateButton;

  /// No description provided for @workbenchProjectModeProjectsTitle.
  ///
  /// In zh, this message translates to:
  /// **'当前 Project API'**
  String get workbenchProjectModeProjectsTitle;

  /// No description provided for @workbenchProjectApiForProject.
  ///
  /// In zh, this message translates to:
  /// **'{projectName} 的 API'**
  String workbenchProjectApiForProject(String projectName);

  /// No description provided for @workbenchProjectModeOpen.
  ///
  /// In zh, this message translates to:
  /// **'打开 Project'**
  String get workbenchProjectModeOpen;

  /// No description provided for @workbenchProjectModeEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无 Workbench Project'**
  String get workbenchProjectModeEmpty;

  /// No description provided for @workbenchProjectModeLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'Project 模式加载失败'**
  String get workbenchProjectModeLoadFailed;

  /// No description provided for @workbenchProjectPromptRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入 Project 需求'**
  String get workbenchProjectPromptRequired;

  /// No description provided for @workbenchProjectGenerated.
  ///
  /// In zh, this message translates to:
  /// **'Project 已生成'**
  String get workbenchProjectGenerated;

  /// No description provided for @workbenchDeleteProject.
  ///
  /// In zh, this message translates to:
  /// **'删除 Project'**
  String get workbenchDeleteProject;

  /// No description provided for @workbenchDeleteProjectTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除 Project'**
  String get workbenchDeleteProjectTitle;

  /// No description provided for @workbenchDeleteProjectMessage.
  ///
  /// In zh, this message translates to:
  /// **'确定删除 {projectId}？它会移除 Project 注册、业务 API 注册和 Workspace 项目文件。'**
  String workbenchDeleteProjectMessage(String projectId);

  /// No description provided for @workbenchDeleteProjectCancel.
  ///
  /// In zh, this message translates to:
  /// **'取消'**
  String get workbenchDeleteProjectCancel;

  /// No description provided for @workbenchDeleteProjectConfirm.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get workbenchDeleteProjectConfirm;

  /// No description provided for @workbenchDeleteProjectFailed.
  ///
  /// In zh, this message translates to:
  /// **'Project 删除失败'**
  String get workbenchDeleteProjectFailed;

  /// No description provided for @workbenchProjectDeleted.
  ///
  /// In zh, this message translates to:
  /// **'Project 已删除'**
  String get workbenchProjectDeleted;

  /// No description provided for @workbenchProjectIdRequired.
  ///
  /// In zh, this message translates to:
  /// **'请输入 Project ID'**
  String get workbenchProjectIdRequired;

  /// No description provided for @workbenchProjectCreated.
  ///
  /// In zh, this message translates to:
  /// **'Project 已创建'**
  String get workbenchProjectCreated;

  /// No description provided for @workbenchProjectInfoTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 信息'**
  String get workbenchProjectInfoTitle;

  /// No description provided for @workbenchProjectInfoDisplayTitle.
  ///
  /// In zh, this message translates to:
  /// **'显示入口'**
  String get workbenchProjectInfoDisplayTitle;

  /// No description provided for @workbenchProjectInfoSourceTitle.
  ///
  /// In zh, this message translates to:
  /// **'源码规格'**
  String get workbenchProjectInfoSourceTitle;

  /// No description provided for @workbenchProjectInfoSourceValue.
  ///
  /// In zh, this message translates to:
  /// **'README.md / frontend/page_spec.json / backend/api_spec.json'**
  String get workbenchProjectInfoSourceValue;

  /// No description provided for @workbenchProjectInfoRuntimeTitle.
  ///
  /// In zh, this message translates to:
  /// **'运行态'**
  String get workbenchProjectInfoRuntimeTitle;

  /// No description provided for @workbenchProjectInfoRuntimeValue.
  ///
  /// In zh, this message translates to:
  /// **'data/todos.json / logs/api_calls.jsonl'**
  String get workbenchProjectInfoRuntimeValue;

  /// No description provided for @workbenchDebugToolsTitle.
  ///
  /// In zh, this message translates to:
  /// **'调试工具'**
  String get workbenchDebugToolsTitle;

  /// No description provided for @workbenchDebugHotUpdate.
  ///
  /// In zh, this message translates to:
  /// **'悬浮小万实时修改当前 Project'**
  String get workbenchDebugHotUpdate;

  /// No description provided for @workbenchDebugHotUpdateHomeInput.
  ///
  /// In zh, this message translates to:
  /// **'回到首页大输入框描述修改，Agent 会带着当前 Project toolbox 执行热更新'**
  String get workbenchDebugHotUpdateHomeInput;

  /// No description provided for @workbenchDebugFloatingXiaowan.
  ///
  /// In zh, this message translates to:
  /// **'悬浮小万可以带上当前前端上下文，选择页面信息后调用 workbench_project_hot_update 迭代这个 Project。'**
  String get workbenchDebugFloatingXiaowan;

  /// No description provided for @workbenchDebugVlmInput.
  ///
  /// In zh, this message translates to:
  /// **'VLM 输入也可以附带当前 Display、可见状态、选中控件或截图摘要，作为 frontendContext 交给 Project Skill。'**
  String get workbenchDebugVlmInput;

  /// No description provided for @workbenchDebugContextProject.
  ///
  /// In zh, this message translates to:
  /// **'Project {projectId}'**
  String workbenchDebugContextProject(String projectId);

  /// No description provided for @workbenchDebugContextDisplay.
  ///
  /// In zh, this message translates to:
  /// **'Display {displayId}'**
  String workbenchDebugContextDisplay(String displayId);

  /// No description provided for @workbenchDebugContextRoute.
  ///
  /// In zh, this message translates to:
  /// **'Route {route}'**
  String workbenchDebugContextRoute(String route);

  /// No description provided for @workbenchDebugVlmTest.
  ///
  /// In zh, this message translates to:
  /// **'根据 VLM 模拟人类操作测试'**
  String get workbenchDebugVlmTest;

  /// No description provided for @workbenchDebugComingSoon.
  ///
  /// In zh, this message translates to:
  /// **'待接入'**
  String get workbenchDebugComingSoon;

  /// No description provided for @workbenchExportProjectPackage.
  ///
  /// In zh, this message translates to:
  /// **'导出分发包'**
  String get workbenchExportProjectPackage;

  /// No description provided for @workbenchProjectExportFailed.
  ///
  /// In zh, this message translates to:
  /// **'Project 导出失败'**
  String get workbenchProjectExportFailed;

  /// No description provided for @workbenchProjectExported.
  ///
  /// In zh, this message translates to:
  /// **'已导出 {packageName}'**
  String workbenchProjectExported(String packageName);

  /// No description provided for @workbenchProjectExportPath.
  ///
  /// In zh, this message translates to:
  /// **'导出位置：{path}'**
  String workbenchProjectExportPath(String path);

  /// No description provided for @workbenchAndroidAssetsTitle.
  ///
  /// In zh, this message translates to:
  /// **'Android 资产'**
  String get workbenchAndroidAssetsTitle;

  /// No description provided for @workbenchAndroidSourceHint.
  ///
  /// In zh, this message translates to:
  /// **'输入 APK 或 Android 项目路径，例如 /workspace/apps/demo.apk'**
  String get workbenchAndroidSourceHint;

  /// No description provided for @workbenchAndroidIngestButton.
  ///
  /// In zh, this message translates to:
  /// **'导入到当前 Project'**
  String get workbenchAndroidIngestButton;

  /// No description provided for @workbenchAndroidSourceRequired.
  ///
  /// In zh, this message translates to:
  /// **'请输入 Android 应用或项目路径'**
  String get workbenchAndroidSourceRequired;

  /// No description provided for @workbenchAndroidIngestFailed.
  ///
  /// In zh, this message translates to:
  /// **'Android 资产导入失败'**
  String get workbenchAndroidIngestFailed;

  /// No description provided for @workbenchAndroidIngested.
  ///
  /// In zh, this message translates to:
  /// **'已导入 {name}'**
  String workbenchAndroidIngested(String name);

  /// No description provided for @workbenchAndroidAssetsEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无导入的 Android 应用或项目'**
  String get workbenchAndroidAssetsEmpty;

  /// No description provided for @workbenchProjectActivateFailed.
  ///
  /// In zh, this message translates to:
  /// **'Project 激活失败'**
  String get workbenchProjectActivateFailed;

  /// No description provided for @workbenchProjectActivated.
  ///
  /// In zh, this message translates to:
  /// **'已激活 {projectName}'**
  String workbenchProjectActivated(String projectName);

  /// No description provided for @workbenchActiveProject.
  ///
  /// In zh, this message translates to:
  /// **'已激活'**
  String get workbenchActiveProject;

  /// No description provided for @workbenchInactiveProject.
  ///
  /// In zh, this message translates to:
  /// **'未激活'**
  String get workbenchInactiveProject;

  /// No description provided for @workbenchContinueInHome.
  ///
  /// In zh, this message translates to:
  /// **'激活并回到首页输入'**
  String get workbenchContinueInHome;

  /// No description provided for @workbenchProjectHelpTooltip.
  ///
  /// In zh, this message translates to:
  /// **'Project 工作台说明'**
  String get workbenchProjectHelpTooltip;

  /// No description provided for @workbenchProjectHelpTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project 工作台'**
  String get workbenchProjectHelpTitle;

  /// No description provided for @workbenchProjectHelpHomeInput.
  ///
  /// In zh, this message translates to:
  /// **'创建、编辑和热更新都在首页大输入框里完成。'**
  String get workbenchProjectHelpHomeInput;

  /// No description provided for @workbenchProjectHelpSelect.
  ///
  /// In zh, this message translates to:
  /// **'这里选择一个 Project，把它激活为 Agent 当前工作环境。'**
  String get workbenchProjectHelpSelect;

  /// No description provided for @workbenchProjectHelpDisplays.
  ///
  /// In zh, this message translates to:
  /// **'每个 Project 可以有多个 Flutter 前端 Display，从这里打开容器。'**
  String get workbenchProjectHelpDisplays;

  /// No description provided for @workbenchProjectHelpApis.
  ///
  /// In zh, this message translates to:
  /// **'Project API 是当前 Project 的业务 toolbox，和 MCP tools 分开管理。'**
  String get workbenchProjectHelpApis;

  /// No description provided for @workbenchActiveProjectChip.
  ///
  /// In zh, this message translates to:
  /// **'Project：{projectName}'**
  String workbenchActiveProjectChip(String projectName);

  /// No description provided for @workbenchAndroidAssetCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 个 Android 资产'**
  String workbenchAndroidAssetCount(int count);

  /// No description provided for @workbenchTodoCount.
  ///
  /// In zh, this message translates to:
  /// **'{openCount} 条待完成 / {finishedCount} 条已完成'**
  String workbenchTodoCount(int openCount, int finishedCount);

  /// No description provided for @workbenchApiCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 个 API'**
  String workbenchApiCount(int count);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
