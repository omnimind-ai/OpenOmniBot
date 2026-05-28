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

  /// No description provided for @settingsIndependentSendButtonTitle.
  ///
  /// In zh, this message translates to:
  /// **'使用独立的发送按钮'**
  String get settingsIndependentSendButtonTitle;

  /// No description provided for @settingsIndependentSendButtonSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后，聊天页键盘回车为换行；关闭后，回车直接发送'**
  String get settingsIndependentSendButtonSubtitle;

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

  /// No description provided for @settingsHabitualHandTitle.
  ///
  /// In zh, this message translates to:
  /// **'惯用手'**
  String get settingsHabitualHandTitle;

  /// No description provided for @settingsHabitualHandSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'影响聊天历史记录的侧滑菜单方向'**
  String get settingsHabitualHandSubtitle;

  /// No description provided for @settingsHabitualHandLeft.
  ///
  /// In zh, this message translates to:
  /// **'左手'**
  String get settingsHabitualHandLeft;

  /// No description provided for @settingsHabitualHandRight.
  ///
  /// In zh, this message translates to:
  /// **'右手'**
  String get settingsHabitualHandRight;

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

  /// No description provided for @settingsOobFunctionAsToolTitle.
  ///
  /// In zh, this message translates to:
  /// **'OOB 复用指令接入工具'**
  String get settingsOobFunctionAsToolTitle;

  /// No description provided for @settingsOobFunctionAsToolSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后 agent 可以直接调用已保存的 OOB 复用指令'**
  String get settingsOobFunctionAsToolSubtitle;

  /// No description provided for @settingsOobFunctionAsToolToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'OOB 复用指令工具开关失败'**
  String get settingsOobFunctionAsToolToggleFailed;

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

  /// No description provided for @chatToolOutputCopied.
  ///
  /// In zh, this message translates to:
  /// **'已复制工具输出'**
  String get chatToolOutputCopied;

  /// No description provided for @chatViewRunLogTooltip.
  ///
  /// In zh, this message translates to:
  /// **'查看执行记录'**
  String get chatViewRunLogTooltip;

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

  /// No description provided for @conversationStatusRunning.
  ///
  /// In zh, this message translates to:
  /// **'执行中'**
  String get conversationStatusRunning;

  /// No description provided for @conversationStatusCompleted.
  ///
  /// In zh, this message translates to:
  /// **'已完成'**
  String get conversationStatusCompleted;

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

  /// No description provided for @memoryCommandsTitle.
  ///
  /// In zh, this message translates to:
  /// **'复用指令'**
  String get memoryCommandsTitle;

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

  /// No description provided for @memoryLongTermLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆加载失败：{error}'**
  String memoryLongTermLoadFailed(Object error);

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

  /// No description provided for @skillOfficial.
  ///
  /// In zh, this message translates to:
  /// **'官方'**
  String get skillOfficial;

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

  /// No description provided for @skillSyncOfficialTooltip.
  ///
  /// In zh, this message translates to:
  /// **'安装/更新官方 Skills'**
  String get skillSyncOfficialTooltip;

  /// No description provided for @skillSyncOfficialSuccess.
  ///
  /// In zh, this message translates to:
  /// **'官方 Skills 已同步（{count} 个）'**
  String skillSyncOfficialSuccess(Object count);

  /// No description provided for @skillSyncOfficialFailed.
  ///
  /// In zh, this message translates to:
  /// **'同步官方 Skills 失败'**
  String get skillSyncOfficialFailed;

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
  /// **'Cloudflare R2'**
  String get aboutApkSourceOptionCnb;

  /// No description provided for @aboutApkSourceOptionCnbDescription.
  ///
  /// In zh, this message translates to:
  /// **'通过更新 Worker 分发'**
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

  /// No description provided for @homeDrawerScheduledTasks.
  ///
  /// In zh, this message translates to:
  /// **'定时任务'**
  String get homeDrawerScheduledTasks;

  /// No description provided for @homeDrawerPinnedConversations.
  ///
  /// In zh, this message translates to:
  /// **'置顶会话'**
  String get homeDrawerPinnedConversations;

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
  /// **'管理 OmniFlow 复用指令：查看、执行或删除复用指令资产。'**
  String get omniflowPanelDesc;

  /// No description provided for @omniflowFunctionList.
  ///
  /// In zh, this message translates to:
  /// **'复用指令列表'**
  String get omniflowFunctionList;

  /// No description provided for @omniflowFunctionSearch.
  ///
  /// In zh, this message translates to:
  /// **'搜索复用指令'**
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
  /// **'加载复用指令列表失败'**
  String get omniflowFunctionsLoadFailed;

  /// No description provided for @omniflowTempFunctions.
  ///
  /// In zh, this message translates to:
  /// **'临时复用指令'**
  String get omniflowTempFunctions;

  /// No description provided for @omniflowReadyFunctions.
  ///
  /// In zh, this message translates to:
  /// **'可用复用指令'**
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
  /// **'查看复用指令库'**
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
  /// **'确认清空所有 OmniFlow 数据？\n\n这将删除：\n• 所有复用指令\n• 所有 Run Logs\n• 所有 Shared Pages\n\n此操作不可恢复！'**
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
  /// **'已清空：{functions} 条复用指令，{runLogs} 条 RunLog'**
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
  /// **'复用指令库'**
  String get functionLibraryTitle;

  /// No description provided for @functionLibrarySearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索复用指令或应用'**
  String get functionLibrarySearchHint;

  /// No description provided for @functionLibraryEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无复用指令'**
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
  /// **'编辑复用指令'**
  String get functionLibraryEditTitle;

  /// No description provided for @functionLibraryEditHint.
  ///
  /// In zh, this message translates to:
  /// **'修改复用指令的描述名称'**
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

  /// No description provided for @functionLibraryStepEditTitle.
  ///
  /// In zh, this message translates to:
  /// **'编辑步骤'**
  String get functionLibraryStepEditTitle;

  /// No description provided for @functionLibraryStepTitleLabel.
  ///
  /// In zh, this message translates to:
  /// **'步骤标题'**
  String get functionLibraryStepTitleLabel;

  /// No description provided for @functionLibraryStepToolLabel.
  ///
  /// In zh, this message translates to:
  /// **'动作工具'**
  String get functionLibraryStepToolLabel;

  /// No description provided for @functionLibraryStepArgsLabel.
  ///
  /// In zh, this message translates to:
  /// **'参数 JSON'**
  String get functionLibraryStepArgsLabel;

  /// No description provided for @functionLibraryStepToolRequired.
  ///
  /// In zh, this message translates to:
  /// **'动作工具不能为空'**
  String get functionLibraryStepToolRequired;

  /// No description provided for @functionLibraryStepArgsInvalid.
  ///
  /// In zh, this message translates to:
  /// **'参数必须是有效的 JSON 对象'**
  String get functionLibraryStepArgsInvalid;

  /// No description provided for @functionLibraryStepArgsObjectRequired.
  ///
  /// In zh, this message translates to:
  /// **'参数必须是 JSON 对象'**
  String get functionLibraryStepArgsObjectRequired;

  /// No description provided for @functionLibraryStepEditMissing.
  ///
  /// In zh, this message translates to:
  /// **'找不到要修改的步骤'**
  String get functionLibraryStepEditMissing;

  /// No description provided for @functionLibraryStepSaved.
  ///
  /// In zh, this message translates to:
  /// **'步骤已保存'**
  String get functionLibraryStepSaved;

  /// No description provided for @functionLibraryStepKeepOne.
  ///
  /// In zh, this message translates to:
  /// **'复用指令至少保留一个步骤'**
  String get functionLibraryStepKeepOne;

  /// No description provided for @functionLibraryStepDeleteTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除步骤'**
  String get functionLibraryStepDeleteTitle;

  /// No description provided for @functionLibraryStepDeleteConfirm.
  ///
  /// In zh, this message translates to:
  /// **'确定删除「{name}」？保存后该动作不再重放。'**
  String functionLibraryStepDeleteConfirm(Object name);

  /// No description provided for @functionLibraryStepDeleteMissing.
  ///
  /// In zh, this message translates to:
  /// **'找不到要删除的步骤'**
  String get functionLibraryStepDeleteMissing;

  /// No description provided for @functionLibraryStepDeleted.
  ///
  /// In zh, this message translates to:
  /// **'步骤已删除'**
  String get functionLibraryStepDeleted;

  /// No description provided for @functionLibraryStepSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存步骤失败'**
  String get functionLibraryStepSaveFailed;

  /// No description provided for @functionLibraryDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get functionLibraryDelete;

  /// No description provided for @functionLibraryDeleteTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除复用指令'**
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

  /// No description provided for @functionLibraryDetailExecutionSurface.
  ///
  /// In zh, this message translates to:
  /// **'执行面'**
  String get functionLibraryDetailExecutionSurface;

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
  /// **'来源复用指令'**
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
  /// **'Bundle 复用指令'**
  String get functionLibraryDetailBundleFunction;

  /// No description provided for @functionLibraryDetailInternalBlocks.
  ///
  /// In zh, this message translates to:
  /// **'内部复用指令片段'**
  String get functionLibraryDetailInternalBlocks;

  /// No description provided for @functionLibraryDetailNoBlocks.
  ///
  /// In zh, this message translates to:
  /// **'当前没有展开的内部复用指令片段'**
  String get functionLibraryDetailNoBlocks;

  /// No description provided for @functionLibraryDetailNoBundle.
  ///
  /// In zh, this message translates to:
  /// **'当前没有可展示的 bundle 复用指令'**
  String get functionLibraryDetailNoBundle;

  /// No description provided for @functionLibraryDetailFunctionSchema.
  ///
  /// In zh, this message translates to:
  /// **'复用指令 Schema'**
  String get functionLibraryDetailFunctionSchema;

  /// No description provided for @executionReuseHit.
  ///
  /// In zh, this message translates to:
  /// **'复用指令'**
  String get executionReuseHit;

  /// No description provided for @executionReuseHitWithFunction.
  ///
  /// In zh, this message translates to:
  /// **'复用指令 · {functionId}'**
  String executionReuseHitWithFunction(Object functionId);

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
  /// **'执行复用指令'**
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
  /// **'保存为复用指令'**
  String get memorySaveAsSkillTitle;

  /// No description provided for @memorySaveAsSkillContent.
  ///
  /// In zh, this message translates to:
  /// **'是否将这次执行记录保存为复用指令？\n\n保存后可在「复用指令库」中查看和管理。'**
  String get memorySaveAsSkillContent;

  /// No description provided for @memorySavingProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在保存复用指令...'**
  String get memorySavingProgress;

  /// No description provided for @memorySaveSuccess.
  ///
  /// In zh, this message translates to:
  /// **'已保存为复用指令：{functionId}'**
  String memorySaveSuccess(String functionId);

  /// No description provided for @memorySaveSuccessSimple.
  ///
  /// In zh, this message translates to:
  /// **'已保存为复用指令'**
  String get memorySaveSuccessSimple;

  /// No description provided for @memorySaveSuccessHint.
  ///
  /// In zh, this message translates to:
  /// **'复用指令已保存到本地复用指令库。\n你可以在复用指令库中查看、编辑或升级。'**
  String get memorySaveSuccessHint;

  /// No description provided for @memoryViewInLibrary.
  ///
  /// In zh, this message translates to:
  /// **'查看复用指令库'**
  String get memoryViewInLibrary;

  /// No description provided for @memorySaveCannotImport.
  ///
  /// In zh, this message translates to:
  /// **'该执行记录无法保存为复用指令'**
  String get memorySaveCannotImport;

  /// No description provided for @memorySaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存复用指令失败'**
  String get memorySaveFailed;

  /// No description provided for @memorySaveFailedWithMessage.
  ///
  /// In zh, this message translates to:
  /// **'保存复用指令失败：{message}'**
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

  /// No description provided for @omniflowAssetReuseHit.
  ///
  /// In zh, this message translates to:
  /// **'复用指令'**
  String get omniflowAssetReuseHit;

  /// No description provided for @omniflowAssetVlmExecution.
  ///
  /// In zh, this message translates to:
  /// **'VLM 执行'**
  String get omniflowAssetVlmExecution;

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
  /// **'关联复用指令'**
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
  /// **'复用指令详情'**
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
  /// **'升级复用指令'**
  String get functionLibraryEnrichTitle;

  /// No description provided for @functionLibraryEnrichConfirm.
  ///
  /// In zh, this message translates to:
  /// **'使用 AI 补齐此复用指令的语义信息？\n\n将自动生成：描述、参数槽位、前置/后置条件等。'**
  String get functionLibraryEnrichConfirm;

  /// No description provided for @functionLibraryEnrichProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在升级复用指令...'**
  String get functionLibraryEnrichProgress;

  /// No description provided for @functionLibraryEnrichSuccess.
  ///
  /// In zh, this message translates to:
  /// **'复用指令升级成功'**
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
  /// **'拆分复用指令'**
  String get functionLibrarySplitTitle;

  /// No description provided for @functionLibrarySplitConfirm.
  ///
  /// In zh, this message translates to:
  /// **'使用 AI 将此复用指令拆分为多个更小的指令？'**
  String get functionLibrarySplitConfirm;

  /// No description provided for @functionLibrarySplitProgress.
  ///
  /// In zh, this message translates to:
  /// **'正在拆分复用指令...'**
  String get functionLibrarySplitProgress;

  /// No description provided for @functionLibrarySplitSuccess.
  ///
  /// In zh, this message translates to:
  /// **'复用指令拆分成功，生成了 {count} 个新指令'**
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
  /// **'执行复用指令'**
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

  /// No description provided for @chatInputTrajectoryTooltip.
  ///
  /// In zh, this message translates to:
  /// **'轨迹'**
  String get chatInputTrajectoryTooltip;

  /// No description provided for @chatInputViewTrajectories.
  ///
  /// In zh, this message translates to:
  /// **'已有轨迹'**
  String get chatInputViewTrajectories;

  /// No description provided for @chatInputViewTrajectoriesTooltip.
  ///
  /// In zh, this message translates to:
  /// **'查看已有轨迹'**
  String get chatInputViewTrajectoriesTooltip;

  /// No description provided for @chatInputViewCurrentTrajectory.
  ///
  /// In zh, this message translates to:
  /// **'当前轨迹'**
  String get chatInputViewCurrentTrajectory;

  /// No description provided for @chatInputViewCurrentTrajectoryTooltip.
  ///
  /// In zh, this message translates to:
  /// **'查看上一条轨迹'**
  String get chatInputViewCurrentTrajectoryTooltip;

  /// No description provided for @chatInputRecordTrajectory.
  ///
  /// In zh, this message translates to:
  /// **'录制轨迹'**
  String get chatInputRecordTrajectory;

  /// No description provided for @chatInputRecordTrajectoryTooltip.
  ///
  /// In zh, this message translates to:
  /// **'开始录制一条轨迹'**
  String get chatInputRecordTrajectoryTooltip;

  /// No description provided for @workbenchTitle.
  ///
  /// In zh, this message translates to:
  /// **'工作台'**
  String get workbenchTitle;

  /// No description provided for @workbenchWorkspaceTitle.
  ///
  /// In zh, this message translates to:
  /// **'工作区'**
  String get workbenchWorkspaceTitle;

  /// No description provided for @workbenchWorkspaceOpenWorkbench.
  ///
  /// In zh, this message translates to:
  /// **'打开工作台'**
  String get workbenchWorkspaceOpenWorkbench;

  /// No description provided for @workbenchWorkspaceOpenProjectConsole.
  ///
  /// In zh, this message translates to:
  /// **'进入管理'**
  String get workbenchWorkspaceOpenProjectConsole;

  /// No description provided for @workbenchWorkspaceWorkMode.
  ///
  /// In zh, this message translates to:
  /// **'文件'**
  String get workbenchWorkspaceWorkMode;

  /// No description provided for @workbenchWorkspaceProjectMode.
  ///
  /// In zh, this message translates to:
  /// **'项目'**
  String get workbenchWorkspaceProjectMode;

  /// No description provided for @workbenchWorkspaceProjectFrontendsTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目窗口'**
  String get workbenchWorkspaceProjectFrontendsTitle;

  /// No description provided for @workbenchWorkspaceProjectFrontendsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启项目模式后，这里像子窗口一样直接承载当前激活项目的 OOB 原生前端。'**
  String get workbenchWorkspaceProjectFrontendsSubtitle;

  /// No description provided for @workbenchWorkspaceProjectFrontendsEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无项目前端。回到对话里描述需求后，Agent 会通过工作台创建可显示的项目。'**
  String get workbenchWorkspaceProjectFrontendsEmpty;

  /// No description provided for @workbenchWorkspaceProjectOpenFailed.
  ///
  /// In zh, this message translates to:
  /// **'打开项目前端失败'**
  String get workbenchWorkspaceProjectOpenFailed;

  /// No description provided for @workbenchWorkspaceProjectUnsupportedDisplay.
  ///
  /// In zh, this message translates to:
  /// **'这个显示页暂不支持内嵌窗口显示，请用右上角打开为完整页面。'**
  String get workbenchWorkspaceProjectUnsupportedDisplay;

  /// No description provided for @workbenchWorkspaceGuideTooltip.
  ///
  /// In zh, this message translates to:
  /// **'查看项目工作台说明'**
  String get workbenchWorkspaceGuideTooltip;

  /// No description provided for @workbenchWorkspaceGuideClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭说明'**
  String get workbenchWorkspaceGuideClose;

  /// No description provided for @workbenchWorkspaceGuideTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目工作台怎么工作'**
  String get workbenchWorkspaceGuideTitle;

  /// No description provided for @workbenchWorkspaceGuideIntro.
  ///
  /// In zh, this message translates to:
  /// **'项目模式不是新的聊天页，而是 OOB 里用来承载 vibe project 的原生工作台。它把生成前端、项目工具、工作区文件、Skill 和持久化数据连成一个可继续编辑的单位。'**
  String get workbenchWorkspaceGuideIntro;

  /// No description provided for @workbenchWorkspaceGuideFlowTitle.
  ///
  /// In zh, this message translates to:
  /// **'交互链路'**
  String get workbenchWorkspaceGuideFlowTitle;

  /// No description provided for @workbenchWorkspaceGuideFlowPrompt.
  ///
  /// In zh, this message translates to:
  /// **'提示词 + Skill 拆解需求'**
  String get workbenchWorkspaceGuideFlowPrompt;

  /// No description provided for @workbenchWorkspaceGuideFlowProject.
  ///
  /// In zh, this message translates to:
  /// **'项目注册表记录容器'**
  String get workbenchWorkspaceGuideFlowProject;

  /// No description provided for @workbenchWorkspaceGuideFlowApi.
  ///
  /// In zh, this message translates to:
  /// **'项目工具注册业务能力'**
  String get workbenchWorkspaceGuideFlowApi;

  /// No description provided for @workbenchWorkspaceGuideFlowDisplay.
  ///
  /// In zh, this message translates to:
  /// **'Flutter 显示页展示业务前端'**
  String get workbenchWorkspaceGuideFlowDisplay;

  /// No description provided for @workbenchWorkspaceGuideFlowPersist.
  ///
  /// In zh, this message translates to:
  /// **'data/ + logs/ 持久化 AI 与 UI 调用'**
  String get workbenchWorkspaceGuideFlowPersist;

  /// No description provided for @workbenchWorkspaceGuideProjectTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目绑定什么'**
  String get workbenchWorkspaceGuideProjectTitle;

  /// No description provided for @workbenchWorkspaceGuideProjectBody.
  ///
  /// In zh, this message translates to:
  /// **'一个项目会绑定目标、Skill、工作区文件、显示页列表、项目工具、数据和日志。它不是 MCP 工具列表，也不是随手生成的 HTML。'**
  String get workbenchWorkspaceGuideProjectBody;

  /// No description provided for @workbenchWorkspaceGuideFrontendTitle.
  ///
  /// In zh, this message translates to:
  /// **'前端怎么显示'**
  String get workbenchWorkspaceGuideFrontendTitle;

  /// No description provided for @workbenchWorkspaceGuideFrontendBody.
  ///
  /// In zh, this message translates to:
  /// **'生成前端是 OOB 原生 Flutter 显示页。工作区切到项目后，不再显示大型管理列表，而是像浏览器子窗口一样直接承载当前激活项目的首页；一个项目可以有多个显示页，可用小菜单切换。'**
  String get workbenchWorkspaceGuideFrontendBody;

  /// No description provided for @workbenchWorkspaceGuideBackendTitle.
  ///
  /// In zh, this message translates to:
  /// **'后端怎么被调用'**
  String get workbenchWorkspaceGuideBackendTitle;

  /// No description provided for @workbenchWorkspaceGuideBackendBody.
  ///
  /// In zh, this message translates to:
  /// **'后端能力注册为项目工具，例如 todo.add、todo.finish。AI 层和前端按钮都调用同一条 workbenchApiCall(projectId, toolId, inputs)，项目创建、导出、删除等控制接口不会混进业务工具。'**
  String get workbenchWorkspaceGuideBackendBody;

  /// No description provided for @workbenchWorkspaceGuideDataTitle.
  ///
  /// In zh, this message translates to:
  /// **'数据怎么流'**
  String get workbenchWorkspaceGuideDataTitle;

  /// No description provided for @workbenchWorkspaceGuideDataBody.
  ///
  /// In zh, this message translates to:
  /// **'调用会经过 Flutter -> MethodChannel -> OOB native executor，然后写入项目的 data/ 和 logs/。前端刷新、AI 调用统计和重启后的状态都来自这份持久化数据。'**
  String get workbenchWorkspaceGuideDataBody;

  /// No description provided for @workbenchWorkspaceGuideVibeTitle.
  ///
  /// In zh, this message translates to:
  /// **'怎么继续改'**
  String get workbenchWorkspaceGuideVibeTitle;

  /// No description provided for @workbenchWorkspaceGuideVibeBody.
  ///
  /// In zh, this message translates to:
  /// **'要继续 vibe coding，回到首页大输入框说需求。工作台 Skill 会判断是创建新项目、扩充项目工具、调整显示页，还是对当前项目做热更新。'**
  String get workbenchWorkspaceGuideVibeBody;

  /// No description provided for @workbenchWorkspaceGuideExtendTitle.
  ///
  /// In zh, this message translates to:
  /// **'扩充后端工具'**
  String get workbenchWorkspaceGuideExtendTitle;

  /// No description provided for @workbenchWorkspaceGuideExtendBody.
  ///
  /// In zh, this message translates to:
  /// **'新增能力时先定义 toolId、输入输出 schema、executorKind、持久化文件和前端触发位置，再通过工作台接口注册项目工具；不要手写 registry 文件。'**
  String get workbenchWorkspaceGuideExtendBody;

  /// No description provided for @workbenchWorkspaceProjectApiStats.
  ///
  /// In zh, this message translates to:
  /// **'{apiCount} 个工具 · 已执行 {executionCount} 次'**
  String workbenchWorkspaceProjectApiStats(int apiCount, int executionCount);

  /// No description provided for @workbenchSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'一个 OOB 原生项目示例，用来验证项目工具注册、状态持久化和工作台内显示。'**
  String get workbenchSubtitle;

  /// No description provided for @workbenchVibeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'提示词生成的原生前端、项目工具和工作区文件在 OOB 内保持关联。'**
  String get workbenchVibeSubtitle;

  /// No description provided for @workbenchProjectDisplay.
  ///
  /// In zh, this message translates to:
  /// **'项目显示'**
  String get workbenchProjectDisplay;

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

  /// No description provided for @workbenchRouteLabel.
  ///
  /// In zh, this message translates to:
  /// **'页面路径'**
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
  /// **'项目注册表'**
  String get workbenchProjectRegistryPath;

  /// No description provided for @workbenchApiRegistryPath.
  ///
  /// In zh, this message translates to:
  /// **'工具注册表'**
  String get workbenchApiRegistryPath;

  /// No description provided for @workbenchProjectFilePath.
  ///
  /// In zh, this message translates to:
  /// **'项目文件'**
  String get workbenchProjectFilePath;

  /// No description provided for @workbenchDataFilePath.
  ///
  /// In zh, this message translates to:
  /// **'数据文件'**
  String get workbenchDataFilePath;

  /// No description provided for @workbenchLogFilePath.
  ///
  /// In zh, this message translates to:
  /// **'工具日志'**
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

  /// No description provided for @workbenchCallApi.
  ///
  /// In zh, this message translates to:
  /// **'调用工具'**
  String get workbenchCallApi;

  /// No description provided for @workbenchGeneratedFrontend.
  ///
  /// In zh, this message translates to:
  /// **'生成的前端'**
  String get workbenchGeneratedFrontend;

  /// No description provided for @workbenchGeneratedFrontendSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'打开提示词生成页面应该挂载的 OOB 原生预览容器。它和 AI 层共用同一组项目工具与持久化数据。'**
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
  /// **'项目工具'**
  String get workbenchToolList;

  /// No description provided for @workbenchProjectControlSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'这里只展示已注册的业务工具。项目创建和打开仍属于 OOB 工作台控制面。'**
  String get workbenchProjectControlSubtitle;

  /// No description provided for @workbenchOpenWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'打开工作区'**
  String get workbenchOpenWorkspace;

  /// No description provided for @workbenchApiEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无工具'**
  String get workbenchApiEmpty;

  /// No description provided for @workbenchToolListDefaultTodo.
  ///
  /// In zh, this message translates to:
  /// **'项目工具点击了同一个后端'**
  String get workbenchToolListDefaultTodo;

  /// No description provided for @workbenchToolExecutionCount.
  ///
  /// In zh, this message translates to:
  /// **'已执行 {count} 次'**
  String workbenchToolExecutionCount(int count);

  /// No description provided for @workbenchProjectDefaultEntity.
  ///
  /// In zh, this message translates to:
  /// **'条目'**
  String get workbenchProjectDefaultEntity;

  /// No description provided for @workbenchProjectCreateTitle.
  ///
  /// In zh, this message translates to:
  /// **'新增 {entity}'**
  String workbenchProjectCreateTitle(String entity);

  /// No description provided for @workbenchProjectInputHint.
  ///
  /// In zh, this message translates to:
  /// **'输入 {entity} 名称'**
  String workbenchProjectInputHint(String entity);

  /// No description provided for @workbenchProjectItemsTitle.
  ///
  /// In zh, this message translates to:
  /// **'{entity} 列表'**
  String workbenchProjectItemsTitle(String entity);

  /// No description provided for @workbenchProjectEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无 {entity}'**
  String workbenchProjectEmpty(String entity);

  /// No description provided for @workbenchProjectActiveItems.
  ///
  /// In zh, this message translates to:
  /// **'进行中'**
  String get workbenchProjectActiveItems;

  /// No description provided for @workbenchProjectArchivedItems.
  ///
  /// In zh, this message translates to:
  /// **'已归档'**
  String get workbenchProjectArchivedItems;

  /// No description provided for @workbenchProjectEditAction.
  ///
  /// In zh, this message translates to:
  /// **'编辑'**
  String get workbenchProjectEditAction;

  /// No description provided for @workbenchProjectEditTitle.
  ///
  /// In zh, this message translates to:
  /// **'编辑条目'**
  String get workbenchProjectEditTitle;

  /// No description provided for @workbenchProjectArchiveAction.
  ///
  /// In zh, this message translates to:
  /// **'归档'**
  String get workbenchProjectArchiveAction;

  /// No description provided for @workbenchProjectMissingCreateApi.
  ///
  /// In zh, this message translates to:
  /// **'这个项目没有可用的新增工具'**
  String get workbenchProjectMissingCreateApi;

  /// No description provided for @workbenchProjectMissingUpdateApi.
  ///
  /// In zh, this message translates to:
  /// **'这个项目没有可用的编辑工具'**
  String get workbenchProjectMissingUpdateApi;

  /// No description provided for @workbenchProjectMissingArchiveApi.
  ///
  /// In zh, this message translates to:
  /// **'这个项目没有可用的归档工具'**
  String get workbenchProjectMissingArchiveApi;

  /// No description provided for @workbenchProjectInputRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入 {entity}'**
  String workbenchProjectInputRequired(String entity);

  /// No description provided for @workbenchProjectItemCreated.
  ///
  /// In zh, this message translates to:
  /// **'{entity} 已新增'**
  String workbenchProjectItemCreated(String entity);

  /// No description provided for @workbenchProjectItemUpdated.
  ///
  /// In zh, this message translates to:
  /// **'{entity} 已保存'**
  String workbenchProjectItemUpdated(String entity);

  /// No description provided for @workbenchProjectItemArchived.
  ///
  /// In zh, this message translates to:
  /// **'{entity} 已归档'**
  String workbenchProjectItemArchived(String entity);

  /// No description provided for @workbenchLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载失败'**
  String get workbenchLoadFailed;

  /// No description provided for @workbenchUnknownTool.
  ///
  /// In zh, this message translates to:
  /// **'工作台工具执行失败'**
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
  /// **'热更新当前项目'**
  String get workbenchAssistantSend;

  /// No description provided for @workbenchAssistantApplied.
  ///
  /// In zh, this message translates to:
  /// **'项目已热更新'**
  String get workbenchAssistantApplied;

  /// No description provided for @workbenchAssistantPromptRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入要调整的内容'**
  String get workbenchAssistantPromptRequired;

  /// No description provided for @workbenchAssistantNoProject.
  ///
  /// In zh, this message translates to:
  /// **'请先选择一个项目'**
  String get workbenchAssistantNoProject;

  /// No description provided for @workbenchAssistantHotUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'项目热更新失败'**
  String get workbenchAssistantHotUpdateFailed;

  /// No description provided for @workbenchProjectModeTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目'**
  String get workbenchProjectModeTitle;

  /// No description provided for @workbenchFlutterDisplay.
  ///
  /// In zh, this message translates to:
  /// **'Flutter 显示页'**
  String get workbenchFlutterDisplay;

  /// No description provided for @workbenchFlutterEvalTitle.
  ///
  /// In zh, this message translates to:
  /// **'Flutter 运行页'**
  String get workbenchFlutterEvalTitle;

  /// No description provided for @workbenchFlutterEvalNoSource.
  ///
  /// In zh, this message translates to:
  /// **'当前项目还没有可运行的 Flutter 源码。请在 frontend/flutter/lib/main.dart 定义 OobProjectWidget。'**
  String get workbenchFlutterEvalNoSource;

  /// No description provided for @workbenchFlutterEvalRuntimeFailed.
  ///
  /// In zh, this message translates to:
  /// **'Flutter 源码暂不可运行，请回到输入框让小万修复这个页面。'**
  String get workbenchFlutterEvalRuntimeFailed;

  /// No description provided for @workbenchProjectSwitcher.
  ///
  /// In zh, this message translates to:
  /// **'切换项目'**
  String get workbenchProjectSwitcher;

  /// No description provided for @workbenchProjectGenerateTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目容器'**
  String get workbenchProjectGenerateTitle;

  /// No description provided for @workbenchProjectGenerateSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'这里只选择和打开项目容器。创建、编辑和热更新继续回到首页大输入框，由当前激活的项目 toolbox 承接。'**
  String get workbenchProjectGenerateSubtitle;

  /// No description provided for @workbenchProjectPromptHint.
  ///
  /// In zh, this message translates to:
  /// **'回到首页输入项目需求'**
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
  /// **'打开项目工作台'**
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
  /// **'创建项目注册和可编辑工作区'**
  String get workbenchProjectPlanProject;

  /// No description provided for @workbenchProjectPlanFrontend.
  ///
  /// In zh, this message translates to:
  /// **'生成 OOB 原生 Flutter 前端'**
  String get workbenchProjectPlanFrontend;

  /// No description provided for @workbenchProjectPlanApi.
  ///
  /// In zh, this message translates to:
  /// **'注册 AI/UI 共用项目工具'**
  String get workbenchProjectPlanApi;

  /// No description provided for @workbenchProjectPlanData.
  ///
  /// In zh, this message translates to:
  /// **'写入持久化数据和工具日志'**
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
  /// **'页面'**
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
  /// **'项目使用台'**
  String get workbenchProjectCurrentTitle;

  /// No description provided for @workbenchProjectCurrentSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'默认打开前端会回到首页；调试打开会回到工作台。热更新通过首页大输入框和当前激活项目完成。'**
  String get workbenchProjectCurrentSubtitle;

  /// No description provided for @workbenchProjectModeCreateTitle.
  ///
  /// In zh, this message translates to:
  /// **'Vibe 项目入口'**
  String get workbenchProjectModeCreateTitle;

  /// No description provided for @workbenchProjectModeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'这里只显示项目和当前激活项。'**
  String get workbenchProjectModeSubtitle;

  /// No description provided for @workbenchProjectActiveTitle.
  ///
  /// In zh, this message translates to:
  /// **'当前项目'**
  String get workbenchProjectActiveTitle;

  /// No description provided for @workbenchProjectActiveEmpty.
  ///
  /// In zh, this message translates to:
  /// **'尚未激活项目'**
  String get workbenchProjectActiveEmpty;

  /// No description provided for @workbenchProjectListTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目'**
  String get workbenchProjectListTitle;

  /// No description provided for @workbenchProjectDetailTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目'**
  String get workbenchProjectDetailTitle;

  /// No description provided for @workbenchProjectModeCreateButton.
  ///
  /// In zh, this message translates to:
  /// **'去首页创建'**
  String get workbenchProjectModeCreateButton;

  /// No description provided for @workbenchProjectCreateFromHome.
  ///
  /// In zh, this message translates to:
  /// **'回到首页输入框，直接说创建项目或描述你想做的页面。'**
  String get workbenchProjectCreateFromHome;

  /// No description provided for @workbenchProjectModeProjectsTitle.
  ///
  /// In zh, this message translates to:
  /// **'当前工具'**
  String get workbenchProjectModeProjectsTitle;

  /// No description provided for @workbenchProjectApiForProject.
  ///
  /// In zh, this message translates to:
  /// **'工具'**
  String get workbenchProjectApiForProject;

  /// No description provided for @workbenchProjectModeOpen.
  ///
  /// In zh, this message translates to:
  /// **'打开项目'**
  String get workbenchProjectModeOpen;

  /// No description provided for @workbenchProjectModeEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无工作台项目'**
  String get workbenchProjectModeEmpty;

  /// No description provided for @workbenchProjectModeLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'项目模式加载失败'**
  String get workbenchProjectModeLoadFailed;

  /// No description provided for @workbenchProjectPromptRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先输入项目需求'**
  String get workbenchProjectPromptRequired;

  /// No description provided for @workbenchProjectGenerated.
  ///
  /// In zh, this message translates to:
  /// **'项目已生成'**
  String get workbenchProjectGenerated;

  /// No description provided for @workbenchDeleteProject.
  ///
  /// In zh, this message translates to:
  /// **'删除项目'**
  String get workbenchDeleteProject;

  /// No description provided for @workbenchDeleteProjectTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除项目'**
  String get workbenchDeleteProjectTitle;

  /// No description provided for @workbenchDeleteProjectMessage.
  ///
  /// In zh, this message translates to:
  /// **'确定删除 {projectId}？它会移除项目注册、业务工具注册和工作区项目文件。'**
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
  /// **'项目删除失败'**
  String get workbenchDeleteProjectFailed;

  /// No description provided for @workbenchProjectDeleted.
  ///
  /// In zh, this message translates to:
  /// **'项目已删除'**
  String get workbenchProjectDeleted;

  /// No description provided for @workbenchProjectIdRequired.
  ///
  /// In zh, this message translates to:
  /// **'请输入项目 ID'**
  String get workbenchProjectIdRequired;

  /// No description provided for @workbenchProjectCreated.
  ///
  /// In zh, this message translates to:
  /// **'项目已创建'**
  String get workbenchProjectCreated;

  /// No description provided for @workbenchProjectInfoTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目信息'**
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
  /// **'悬浮小万实时修改当前项目'**
  String get workbenchDebugHotUpdate;

  /// No description provided for @workbenchDebugHotUpdateHomeInput.
  ///
  /// In zh, this message translates to:
  /// **'回到首页大输入框描述修改，Agent 会带着当前项目 toolbox 执行热更新'**
  String get workbenchDebugHotUpdateHomeInput;

  /// No description provided for @workbenchDebugFloatingXiaowan.
  ///
  /// In zh, this message translates to:
  /// **'悬浮小万可以带上当前前端上下文，选择页面信息后调用 workbench_project_hot_update 迭代这个项目。'**
  String get workbenchDebugFloatingXiaowan;

  /// No description provided for @workbenchDebugVlmInput.
  ///
  /// In zh, this message translates to:
  /// **'VLM 输入也可以附带当前显示页、可见状态、选中控件或截图摘要，作为 frontendContext 交给项目 Skill。'**
  String get workbenchDebugVlmInput;

  /// No description provided for @workbenchDebugContextProject.
  ///
  /// In zh, this message translates to:
  /// **'项目 {projectId}'**
  String workbenchDebugContextProject(String projectId);

  /// No description provided for @workbenchDebugContextDisplay.
  ///
  /// In zh, this message translates to:
  /// **'显示页 {displayId}'**
  String workbenchDebugContextDisplay(String displayId);

  /// No description provided for @workbenchDebugContextRoute.
  ///
  /// In zh, this message translates to:
  /// **'页面路径 {route}'**
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

  /// No description provided for @workbenchAnnotationTitle.
  ///
  /// In zh, this message translates to:
  /// **'标注画布'**
  String get workbenchAnnotationTitle;

  /// No description provided for @workbenchAnnotationDrawMode.
  ///
  /// In zh, this message translates to:
  /// **'画笔'**
  String get workbenchAnnotationDrawMode;

  /// No description provided for @workbenchAnnotationBrowseMode.
  ///
  /// In zh, this message translates to:
  /// **'浏览页面'**
  String get workbenchAnnotationBrowseMode;

  /// No description provided for @workbenchAnnotationUndo.
  ///
  /// In zh, this message translates to:
  /// **'撤销'**
  String get workbenchAnnotationUndo;

  /// No description provided for @workbenchAnnotationClear.
  ///
  /// In zh, this message translates to:
  /// **'清空'**
  String get workbenchAnnotationClear;

  /// No description provided for @workbenchAnnotationApply.
  ///
  /// In zh, this message translates to:
  /// **'应用标注'**
  String get workbenchAnnotationApply;

  /// No description provided for @workbenchAnnotationApplying.
  ///
  /// In zh, this message translates to:
  /// **'应用中'**
  String get workbenchAnnotationApplying;

  /// No description provided for @workbenchAnnotationPromptHint.
  ///
  /// In zh, this message translates to:
  /// **'补充修改说明，例如：把这里改成主按钮'**
  String get workbenchAnnotationPromptHint;

  /// No description provided for @workbenchAnnotationNoStrokes.
  ///
  /// In zh, this message translates to:
  /// **'先在页面上画出要修改的区域'**
  String get workbenchAnnotationNoStrokes;

  /// No description provided for @workbenchAnnotationNoShape.
  ///
  /// In zh, this message translates to:
  /// **'未标注'**
  String get workbenchAnnotationNoShape;

  /// No description provided for @workbenchAnnotationShapeCount.
  ///
  /// In zh, this message translates to:
  /// **'已标注 {count} 笔'**
  String workbenchAnnotationShapeCount(int count);

  /// No description provided for @workbenchAnnotationDefaultPrompt.
  ///
  /// In zh, this message translates to:
  /// **'根据画布标注调整当前项目前端。'**
  String get workbenchAnnotationDefaultPrompt;

  /// No description provided for @workbenchAnnotationHotUpdateSuccess.
  ///
  /// In zh, this message translates to:
  /// **'已把标注应用到项目'**
  String get workbenchAnnotationHotUpdateSuccess;

  /// No description provided for @workbenchAnnotationHotUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'标注热更新失败'**
  String get workbenchAnnotationHotUpdateFailed;

  /// No description provided for @workbenchExportProjectPackage.
  ///
  /// In zh, this message translates to:
  /// **'导出分发包'**
  String get workbenchExportProjectPackage;

  /// No description provided for @workbenchProjectExportFailed.
  ///
  /// In zh, this message translates to:
  /// **'项目导出失败'**
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
  /// **'应用'**
  String get workbenchAndroidAssetsTitle;

  /// No description provided for @workbenchAndroidSourceHint.
  ///
  /// In zh, this message translates to:
  /// **'输入 APK 或 Android 项目路径，例如 /workspace/apps/demo.apk'**
  String get workbenchAndroidSourceHint;

  /// No description provided for @workbenchAndroidIngestButton.
  ///
  /// In zh, this message translates to:
  /// **'导入到当前项目'**
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
  /// **'项目激活失败'**
  String get workbenchProjectActivateFailed;

  /// No description provided for @workbenchProjectActivated.
  ///
  /// In zh, this message translates to:
  /// **'已激活 {projectName}'**
  String workbenchProjectActivated(String projectName);

  /// No description provided for @workbenchProjectDeactivateFailed.
  ///
  /// In zh, this message translates to:
  /// **'项目取消激活失败'**
  String get workbenchProjectDeactivateFailed;

  /// No description provided for @workbenchProjectDeactivated.
  ///
  /// In zh, this message translates to:
  /// **'已取消激活项目'**
  String get workbenchProjectDeactivated;

  /// No description provided for @workbenchActivateProject.
  ///
  /// In zh, this message translates to:
  /// **'激活项目'**
  String get workbenchActivateProject;

  /// No description provided for @workbenchDeactivateProject.
  ///
  /// In zh, this message translates to:
  /// **'取消激活'**
  String get workbenchDeactivateProject;

  /// No description provided for @workbenchEditProjectLabels.
  ///
  /// In zh, this message translates to:
  /// **'编辑名称'**
  String get workbenchEditProjectLabels;

  /// No description provided for @workbenchProjectNameLabel.
  ///
  /// In zh, this message translates to:
  /// **'名称'**
  String get workbenchProjectNameLabel;

  /// No description provided for @workbenchProjectShortNameLabel.
  ///
  /// In zh, this message translates to:
  /// **'简写'**
  String get workbenchProjectShortNameLabel;

  /// No description provided for @workbenchSaveProjectLabels.
  ///
  /// In zh, this message translates to:
  /// **'保存'**
  String get workbenchSaveProjectLabels;

  /// No description provided for @workbenchProjectNameRequired.
  ///
  /// In zh, this message translates to:
  /// **'请输入名称'**
  String get workbenchProjectNameRequired;

  /// No description provided for @workbenchProjectLabelsUpdated.
  ///
  /// In zh, this message translates to:
  /// **'已保存'**
  String get workbenchProjectLabelsUpdated;

  /// No description provided for @workbenchProjectLabelsUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存失败'**
  String get workbenchProjectLabelsUpdateFailed;

  /// No description provided for @workbenchProjectMoreActions.
  ///
  /// In zh, this message translates to:
  /// **'更多操作'**
  String get workbenchProjectMoreActions;

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
  /// **'激活项目'**
  String get workbenchContinueInHome;

  /// No description provided for @workbenchProjectHelpTooltip.
  ///
  /// In zh, this message translates to:
  /// **'项目工作台说明'**
  String get workbenchProjectHelpTooltip;

  /// No description provided for @workbenchProjectHelpTitle.
  ///
  /// In zh, this message translates to:
  /// **'项目工作台'**
  String get workbenchProjectHelpTitle;

  /// No description provided for @workbenchProjectHelpHomeInput.
  ///
  /// In zh, this message translates to:
  /// **'创建、编辑和热更新都在首页大输入框里完成。'**
  String get workbenchProjectHelpHomeInput;

  /// No description provided for @workbenchProjectHelpSelect.
  ///
  /// In zh, this message translates to:
  /// **'这里选择一个项目，把它激活为 Agent 当前工作环境。'**
  String get workbenchProjectHelpSelect;

  /// No description provided for @workbenchProjectHelpDisplays.
  ///
  /// In zh, this message translates to:
  /// **'每个项目可以有多个 Flutter 前端显示页，从这里打开容器。'**
  String get workbenchProjectHelpDisplays;

  /// No description provided for @workbenchProjectHelpApis.
  ///
  /// In zh, this message translates to:
  /// **'项目工具是当前项目的业务 toolbox，和 MCP tools 分开管理。'**
  String get workbenchProjectHelpApis;

  /// No description provided for @workbenchActiveProjectChip.
  ///
  /// In zh, this message translates to:
  /// **'项目：{projectName}'**
  String workbenchActiveProjectChip(String projectName);

  /// No description provided for @workbenchProjectSummaryGeneric.
  ///
  /// In zh, this message translates to:
  /// **'管理 {entityName} 记录，并保留状态和快捷操作。'**
  String workbenchProjectSummaryGeneric(String entityName);

  /// No description provided for @workbenchAndroidAssetCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 个 Android 资产'**
  String workbenchAndroidAssetCount(int count);

  /// No description provided for @workbenchProjectItemCount.
  ///
  /// In zh, this message translates to:
  /// **'{activeCount} 条进行中 / {archivedCount} 条归档'**
  String workbenchProjectItemCount(int activeCount, int archivedCount);

  /// No description provided for @workbenchApiCount.
  ///
  /// In zh, this message translates to:
  /// **'{count} 个工具'**
  String workbenchApiCount(int count);

  /// No description provided for @workbenchPhilosophyBadge.
  ///
  /// In zh, this message translates to:
  /// **'了解工作台'**
  String get workbenchPhilosophyBadge;

  /// No description provided for @workbenchPhilosophyClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭'**
  String get workbenchPhilosophyClose;

  /// No description provided for @workbenchPhilosophyTitle.
  ///
  /// In zh, this message translates to:
  /// **'AI 产品展示工作台'**
  String get workbenchPhilosophyTitle;

  /// No description provided for @workbenchPhilosophyTagline.
  ///
  /// In zh, this message translates to:
  /// **'让 AI 的结果立刻变成可看、可点、可继续修改的界面'**
  String get workbenchPhilosophyTagline;

  /// No description provided for @workbenchPhilosophySubtitle.
  ///
  /// In zh, this message translates to:
  /// **'Workbench 不是模板生成器，而是 AI 产品的展示与运行层。Agent 产出的报告、数据、状态和操作会落到 Project 中，通过 HTML、Markdown 或 Flutter 显示，并通过 Project API 连接手机能力与持久化数据。'**
  String get workbenchPhilosophySubtitle;

  /// No description provided for @workbenchPhilosophyPillarsTitle.
  ///
  /// In zh, this message translates to:
  /// **'当前核心闭环'**
  String get workbenchPhilosophyPillarsTitle;

  /// No description provided for @workbenchPhilosophyComposable.
  ///
  /// In zh, this message translates to:
  /// **'显示层'**
  String get workbenchPhilosophyComposable;

  /// No description provided for @workbenchPhilosophyComposableDesc.
  ///
  /// In zh, this message translates to:
  /// **'HTML / Markdown / Flutter 都是 Project Display，用来承载 AI 输出'**
  String get workbenchPhilosophyComposableDesc;

  /// No description provided for @workbenchPhilosophyAIDriven.
  ///
  /// In zh, this message translates to:
  /// **'交互层'**
  String get workbenchPhilosophyAIDriven;

  /// No description provided for @workbenchPhilosophyAIDrivenDesc.
  ///
  /// In zh, this message translates to:
  /// **'用户点击、填写、选择后，通过 Project API 触发下一步 Agent 或工具'**
  String get workbenchPhilosophyAIDrivenDesc;

  /// No description provided for @workbenchPhilosophyMobileNative.
  ///
  /// In zh, this message translates to:
  /// **'能力层'**
  String get workbenchPhilosophyMobileNative;

  /// No description provided for @workbenchPhilosophyMobileNativeDesc.
  ///
  /// In zh, this message translates to:
  /// **'需要操控手机、读屏、文件、脚本时，再走 OOB 原生能力'**
  String get workbenchPhilosophyMobileNativeDesc;

  /// No description provided for @workbenchPhilosophyStrengthsTitle.
  ///
  /// In zh, this message translates to:
  /// **'三件事'**
  String get workbenchPhilosophyStrengthsTitle;

  /// No description provided for @workbenchPhilosophyBackendTitle.
  ///
  /// In zh, this message translates to:
  /// **'Project API'**
  String get workbenchPhilosophyBackendTitle;

  /// No description provided for @workbenchPhilosophyBackendDesc.
  ///
  /// In zh, this message translates to:
  /// **'白名单工具、持久化数据、运行日志和手机能力统一挂到 Project 上'**
  String get workbenchPhilosophyBackendDesc;

  /// No description provided for @workbenchPhilosophyFrontendTitle.
  ///
  /// In zh, this message translates to:
  /// **'Display'**
  String get workbenchPhilosophyFrontendTitle;

  /// No description provided for @workbenchPhilosophyFrontendDesc.
  ///
  /// In zh, this message translates to:
  /// **'普通交互 UI 默认 HTML；报告用 Markdown / HTML；Flutter 保留为容器和受限补充'**
  String get workbenchPhilosophyFrontendDesc;

  /// No description provided for @workbenchPhilosophyRuntimeTitle.
  ///
  /// In zh, this message translates to:
  /// **'Hot update'**
  String get workbenchPhilosophyRuntimeTitle;

  /// No description provided for @workbenchPhilosophyRuntimeDesc.
  ///
  /// In zh, this message translates to:
  /// **'用户一句话或一次选区标注后，AI 只改必要的前端文件或 API，右侧立即刷新'**
  String get workbenchPhilosophyRuntimeDesc;

  /// No description provided for @workbenchPhilosophyHowToTitle.
  ///
  /// In zh, this message translates to:
  /// **'使用方式'**
  String get workbenchPhilosophyHowToTitle;

  /// No description provided for @workbenchPhilosophyStep1Label.
  ///
  /// In zh, this message translates to:
  /// **'生成'**
  String get workbenchPhilosophyStep1Label;

  /// No description provided for @workbenchPhilosophyStep1Desc.
  ///
  /// In zh, this message translates to:
  /// **'Agent 创建 Project，写入 API 与显示文件'**
  String get workbenchPhilosophyStep1Desc;

  /// No description provided for @workbenchPhilosophyStep2Label.
  ///
  /// In zh, this message translates to:
  /// **'查看'**
  String get workbenchPhilosophyStep2Label;

  /// No description provided for @workbenchPhilosophyStep2Desc.
  ///
  /// In zh, this message translates to:
  /// **'右侧 Workspace 直接预览 HTML / Markdown / Flutter'**
  String get workbenchPhilosophyStep2Desc;

  /// No description provided for @workbenchPhilosophyStep3Label.
  ///
  /// In zh, this message translates to:
  /// **'修改'**
  String get workbenchPhilosophyStep3Label;

  /// No description provided for @workbenchPhilosophyStep3Desc.
  ///
  /// In zh, this message translates to:
  /// **'用悬浮输入或标注提出修改，Project 热更新'**
  String get workbenchPhilosophyStep3Desc;

  /// No description provided for @workbenchPhilosophyActivateHint.
  ///
  /// In zh, this message translates to:
  /// **'激活项目后，右侧 Workspace 显示它的 Display；继续输入或标注会作为上下文传给 hot update。'**
  String get workbenchPhilosophyActivateHint;

  /// No description provided for @sourceTextf9dfa89402.
  ///
  /// In zh, this message translates to:
  /// **'小万悬浮窗'**
  String get sourceTextf9dfa89402;

  /// No description provided for @sourceTextea6631ac86.
  ///
  /// In zh, this message translates to:
  /// **'关闭后不再显示桌面悬浮球、半屏输入层和运行胶囊'**
  String get sourceTextea6631ac86;

  /// No description provided for @sourceText60d33fd58f.
  ///
  /// In zh, this message translates to:
  /// **'小万悬浮窗已开启'**
  String get sourceText60d33fd58f;

  /// No description provided for @sourceText9803e0f8d8.
  ///
  /// In zh, this message translates to:
  /// **'小万悬浮窗已关闭'**
  String get sourceText9803e0f8d8;

  /// No description provided for @sourceText8ed5fe74f6.
  ///
  /// In zh, this message translates to:
  /// **'设置悬浮窗失败'**
  String get sourceText8ed5fe74f6;

  /// No description provided for @sourceText2a4a4de806.
  ///
  /// In zh, this message translates to:
  /// **'手动'**
  String get sourceText2a4a4de806;

  /// No description provided for @sourceText76c9741888.
  ///
  /// In zh, this message translates to:
  /// **'Shizuku 权限'**
  String get sourceText76c9741888;

  /// No description provided for @sourceText5e04ad1c9a.
  ///
  /// In zh, this message translates to:
  /// **'正在调用内嵌 Alpine 终端执行命令'**
  String get sourceText5e04ad1c9a;

  /// No description provided for @sourceTextc0b7ed8600.
  ///
  /// In zh, this message translates to:
  /// **'正在执行内嵌 Alpine 终端命令'**
  String get sourceTextc0b7ed8600;

  /// No description provided for @sourceText60cf09e22d.
  ///
  /// In zh, this message translates to:
  /// **'终端输出更新中'**
  String get sourceText60cf09e22d;

  /// No description provided for @sourceText140c80c696.
  ///
  /// In zh, this message translates to:
  /// **'🎉Hi，我是小万，我会做很多事，让我展示给你下！'**
  String get sourceText140c80c696;

  /// No description provided for @sourceText82347f1be8.
  ///
  /// In zh, this message translates to:
  /// **'Hi，我是小万'**
  String get sourceText82347f1be8;

  /// No description provided for @sourceText5167632783.
  ///
  /// In zh, this message translates to:
  /// **'你的 AI 助手，随时准备就绪'**
  String get sourceText5167632783;

  /// No description provided for @sourceText63a921a287.
  ///
  /// In zh, this message translates to:
  /// **'无需网络，完全免费'**
  String get sourceText63a921a287;

  /// No description provided for @sourceText112e197134.
  ///
  /// In zh, this message translates to:
  /// **'数据完全留在设备上，不会发送到任何服务器。对话内容、个人偏好等敏感信息始终由你掌控。'**
  String get sourceText112e197134;

  /// No description provided for @sourceText8de8b69cc9.
  ///
  /// In zh, this message translates to:
  /// **'无需网络连接即可运行 AI 助手。无论在飞机上、地铁里还是偏远地区，随时随地可用。'**
  String get sourceText8de8b69cc9;

  /// No description provided for @sourceTexteac537b43e.
  ///
  /// In zh, this message translates to:
  /// **'无需 API 费用或订阅。模型下载后可无限次使用，没有任何隐藏费用。'**
  String get sourceTexteac537b43e;

  /// No description provided for @sourceTexte8b806ace2.
  ///
  /// In zh, this message translates to:
  /// **'端侧模型较小，回复质量不如云端模型，暂不支持复杂 Agent 任务，适合日常对话与问答。'**
  String get sourceTexte8b806ace2;

  /// No description provided for @sourceText7e1cc2fc3f.
  ///
  /// In zh, this message translates to:
  /// **'换一换'**
  String get sourceText7e1cc2fc3f;

  /// No description provided for @sourceText63e272f624.
  ///
  /// In zh, this message translates to:
  /// **'小万正在思考...'**
  String get sourceText63e272f624;

  /// No description provided for @sourceTextd9f594509d.
  ///
  /// In zh, this message translates to:
  /// **'总结中'**
  String get sourceTextd9f594509d;

  /// No description provided for @sourceText9384e034e5.
  ///
  /// In zh, this message translates to:
  /// **'总结如下'**
  String get sourceText9384e034e5;

  /// No description provided for @sourceText3e44b2a933.
  ///
  /// In zh, this message translates to:
  /// **'全选'**
  String get sourceText3e44b2a933;

  /// No description provided for @sourceText4edd1d0087.
  ///
  /// In zh, this message translates to:
  /// **'复制'**
  String get sourceText4edd1d0087;

  /// No description provided for @sourceTextb56d9ac6c5.
  ///
  /// In zh, this message translates to:
  /// **'确认'**
  String get sourceTextb56d9ac6c5;

  /// No description provided for @sourceTextf526c89937.
  ///
  /// In zh, this message translates to:
  /// **'确定'**
  String get sourceTextf526c89937;

  /// No description provided for @sourceText4d0b3bb4e9.
  ///
  /// In zh, this message translates to:
  /// **'请稍候...'**
  String get sourceText4d0b3bb4e9;

  /// No description provided for @sourceTextee5037d25d.
  ///
  /// In zh, this message translates to:
  /// **'保存并发送'**
  String get sourceTextee5037d25d;

  /// No description provided for @sourceTextbe15d6f28c.
  ///
  /// In zh, this message translates to:
  /// **'未设置模型'**
  String get sourceTextbe15d6f28c;

  /// No description provided for @sourceText01047404ef.
  ///
  /// In zh, this message translates to:
  /// **'发现新版本'**
  String get sourceText01047404ef;

  /// No description provided for @sourceText1722589489.
  ///
  /// In zh, this message translates to:
  /// **'打开终端'**
  String get sourceText1722589489;

  /// No description provided for @sourceText649fc10b46.
  ///
  /// In zh, this message translates to:
  /// **'管理终端环境变量'**
  String get sourceText649fc10b46;

  /// No description provided for @sourceTextd8f03e50ea.
  ///
  /// In zh, this message translates to:
  /// **'打开当前会话浏览器'**
  String get sourceTextd8f03e50ea;

  /// No description provided for @sourceTextc1c986937d.
  ///
  /// In zh, this message translates to:
  /// **'当前会话还没有可用的浏览器会话'**
  String get sourceTextc1c986937d;

  /// No description provided for @sourceText31b7c8d175.
  ///
  /// In zh, this message translates to:
  /// **'纯聊天'**
  String get sourceText31b7c8d175;

  /// No description provided for @sourceText7cda072d45.
  ///
  /// In zh, this message translates to:
  /// **'普通'**
  String get sourceText7cda072d45;

  /// No description provided for @sourceText17e83cc25e.
  ///
  /// In zh, this message translates to:
  /// **'今天'**
  String get sourceText17e83cc25e;

  /// No description provided for @sourceText59c4fcb09e.
  ///
  /// In zh, this message translates to:
  /// **'昨天'**
  String get sourceText59c4fcb09e;

  /// No description provided for @sourceText1f425b6bf0.
  ///
  /// In zh, this message translates to:
  /// **'执行中'**
  String get sourceText1f425b6bf0;

  /// No description provided for @sourceText6c189aad4d.
  ///
  /// In zh, this message translates to:
  /// **'执行成功'**
  String get sourceText6c189aad4d;

  /// No description provided for @sourceText9746cfc7d2.
  ///
  /// In zh, this message translates to:
  /// **'执行失败'**
  String get sourceText9746cfc7d2;

  /// No description provided for @sourceTextd0de773436.
  ///
  /// In zh, this message translates to:
  /// **'等待执行'**
  String get sourceTextd0de773436;

  /// No description provided for @sourceText2029839d84.
  ///
  /// In zh, this message translates to:
  /// **'总结'**
  String get sourceText2029839d84;

  /// No description provided for @sourceText6c2b60f0ee.
  ///
  /// In zh, this message translates to:
  /// **'识图'**
  String get sourceText6c2b60f0ee;

  /// No description provided for @sourceTexte9649f84f9.
  ///
  /// In zh, this message translates to:
  /// **'未知类型'**
  String get sourceTexte9649f84f9;

  /// No description provided for @sourceText756eae0324.
  ///
  /// In zh, this message translates to:
  /// **'正在回复...'**
  String get sourceText756eae0324;

  /// No description provided for @sourceText292eea5849.
  ///
  /// In zh, this message translates to:
  /// **'永不'**
  String get sourceText292eea5849;

  /// No description provided for @sourceText08d65bdbc3.
  ///
  /// In zh, this message translates to:
  /// **'每日'**
  String get sourceText08d65bdbc3;

  /// No description provided for @sourceTexta93b55d8bf.
  ///
  /// In zh, this message translates to:
  /// **'每周'**
  String get sourceTexta93b55d8bf;

  /// No description provided for @sourceText24aedc3608.
  ///
  /// In zh, this message translates to:
  /// **'每月'**
  String get sourceText24aedc3608;

  /// No description provided for @sourceText4a9ee561f9.
  ///
  /// In zh, this message translates to:
  /// **'每年'**
  String get sourceText4a9ee561f9;

  /// No description provided for @sourceText89b4aa6364.
  ///
  /// In zh, this message translates to:
  /// **'时间'**
  String get sourceText89b4aa6364;

  /// No description provided for @sourceTextb6fed9af83.
  ///
  /// In zh, this message translates to:
  /// **'日期'**
  String get sourceTextb6fed9af83;

  /// No description provided for @sourceText6e708ba759.
  ///
  /// In zh, this message translates to:
  /// **'重复'**
  String get sourceText6e708ba759;

  /// No description provided for @sourceTextc1cb3fc29f.
  ///
  /// In zh, this message translates to:
  /// **'任务选项'**
  String get sourceTextc1cb3fc29f;

  /// No description provided for @sourceText39797f7a92.
  ///
  /// In zh, this message translates to:
  /// **'请选择一个任务'**
  String get sourceText39797f7a92;

  /// No description provided for @sourceTexte03304491a.
  ///
  /// In zh, this message translates to:
  /// **'请选择你想执行的任务'**
  String get sourceTexte03304491a;

  /// No description provided for @sourceTextb4a7ea5533.
  ///
  /// In zh, this message translates to:
  /// **'请选择一个应用程序'**
  String get sourceTextb4a7ea5533;

  /// No description provided for @sourceText1354374f76.
  ///
  /// In zh, this message translates to:
  /// **'已过期'**
  String get sourceText1354374f76;

  /// No description provided for @sourceText36d2d01f31.
  ///
  /// In zh, this message translates to:
  /// **'即将执行'**
  String get sourceText36d2d01f31;

  /// No description provided for @sourceText13794e1f43.
  ///
  /// In zh, this message translates to:
  /// **'好，我来帮你完成'**
  String get sourceText13794e1f43;

  /// No description provided for @sourceTextbaa298fbe1.
  ///
  /// In zh, this message translates to:
  /// **'用户操作'**
  String get sourceTextbaa298fbe1;

  /// No description provided for @sourceText86e8d12a79.
  ///
  /// In zh, this message translates to:
  /// **'删除成功'**
  String get sourceText86e8d12a79;

  /// No description provided for @sourceText9abb465039.
  ///
  /// In zh, this message translates to:
  /// **'修改失败'**
  String get sourceText9abb465039;

  /// No description provided for @sourceTextf8913eb433.
  ///
  /// In zh, this message translates to:
  /// **'修改成功'**
  String get sourceTextf8913eb433;

  /// No description provided for @sourceText65fdeb927b.
  ///
  /// In zh, this message translates to:
  /// **'桌面'**
  String get sourceText65fdeb927b;

  /// No description provided for @sourceText322eceb785.
  ///
  /// In zh, this message translates to:
  /// **'内存中'**
  String get sourceText322eceb785;

  /// No description provided for @sourceTextf90d5c751e.
  ///
  /// In zh, this message translates to:
  /// **'云内存中'**
  String get sourceTextf90d5c751e;

  /// No description provided for @sourceText7e68eb622d.
  ///
  /// In zh, this message translates to:
  /// **'保存成功'**
  String get sourceText7e68eb622d;

  /// No description provided for @sourceText6a6b660ba8.
  ///
  /// In zh, this message translates to:
  /// **'编辑你的消息'**
  String get sourceText6a6b660ba8;

  /// No description provided for @sourceTextfcbd093292.
  ///
  /// In zh, this message translates to:
  /// **'创建'**
  String get sourceTextfcbd093292;

  /// No description provided for @sourceText8200c3d50b.
  ///
  /// In zh, this message translates to:
  /// **'未命名对话'**
  String get sourceText8200c3d50b;

  /// No description provided for @sourceText229127ec8d.
  ///
  /// In zh, this message translates to:
  /// **'折叠全部日期'**
  String get sourceText229127ec8d;

  /// No description provided for @sourceTextbc51af6ffc.
  ///
  /// In zh, this message translates to:
  /// **'展开全部日期'**
  String get sourceTextbc51af6ffc;

  /// No description provided for @sourceText72be511e05.
  ///
  /// In zh, this message translates to:
  /// **'最近执行'**
  String get sourceText72be511e05;

  /// No description provided for @sourceText818a1f7be3.
  ///
  /// In zh, this message translates to:
  /// **'暂无总结内容'**
  String get sourceText818a1f7be3;

  /// No description provided for @sourceTextc76c74e809.
  ///
  /// In zh, this message translates to:
  /// **'检查更新失败'**
  String get sourceTextc76c74e809;

  /// No description provided for @sourceTextae4535ef13.
  ///
  /// In zh, this message translates to:
  /// **'已是最新版'**
  String get sourceTextae4535ef13;

  /// No description provided for @sourceText00f512b5e8.
  ///
  /// In zh, this message translates to:
  /// **'检查 GitHub Release 获取最新版本'**
  String get sourceText00f512b5e8;

  /// No description provided for @sourceText9afc832d99.
  ///
  /// In zh, this message translates to:
  /// **'查看新版本'**
  String get sourceText9afc832d99;

  /// No description provided for @sourceTexta6df38586d.
  ///
  /// In zh, this message translates to:
  /// **'检查更新'**
  String get sourceTexta6df38586d;

  /// No description provided for @sourceText8ff0439ff9.
  ///
  /// In zh, this message translates to:
  /// **'已关闭思考'**
  String get sourceText8ff0439ff9;

  /// No description provided for @sourceTextd9d4d4e7dd.
  ///
  /// In zh, this message translates to:
  /// **'请求日志'**
  String get sourceTextd9d4d4e7dd;

  /// No description provided for @sourceTexta8ce402665.
  ///
  /// In zh, this message translates to:
  /// **'运行日志'**
  String get sourceTexta8ce402665;

  /// No description provided for @sourceText4c685c0454.
  ///
  /// In zh, this message translates to:
  /// **'使用手册'**
  String get sourceText4c685c0454;

  /// No description provided for @sourceText5060421d15.
  ///
  /// In zh, this message translates to:
  /// **'概览'**
  String get sourceText5060421d15;

  /// No description provided for @sourceText9f14a3f4dd.
  ///
  /// In zh, this message translates to:
  /// **'最近记录'**
  String get sourceText9f14a3f4dd;

  /// No description provided for @sourceTextb01090a29c.
  ///
  /// In zh, this message translates to:
  /// **'最近 10 条 AI 请求，按时间倒序展示。'**
  String get sourceTextb01090a29c;

  /// No description provided for @sourceTextc740eb5be5.
  ///
  /// In zh, this message translates to:
  /// **'点击条目展开查看请求与响应正文。'**
  String get sourceTextc740eb5be5;

  /// No description provided for @sourceTextcb80eb03ea.
  ///
  /// In zh, this message translates to:
  /// **'最近 200 条错误和崩溃日志，按时间倒序展示。'**
  String get sourceTextcb80eb03ea;

  /// No description provided for @sourceText8334b58cfa.
  ///
  /// In zh, this message translates to:
  /// **'含堆栈的条目可展开查看。'**
  String get sourceText8334b58cfa;

  /// No description provided for @sourceTextfe12b789bf.
  ///
  /// In zh, this message translates to:
  /// **'导出运行日志'**
  String get sourceTextfe12b789bf;

  /// No description provided for @sourceText88f6dbf1a3.
  ///
  /// In zh, this message translates to:
  /// **'已复制全部运行日志'**
  String get sourceText88f6dbf1a3;

  /// No description provided for @sourceText8b06115d35.
  ///
  /// In zh, this message translates to:
  /// **'导出运行日志失败'**
  String get sourceText8b06115d35;

  /// No description provided for @sourceTextd6c8084d07.
  ///
  /// In zh, this message translates to:
  /// **'崩溃'**
  String get sourceTextd6c8084d07;

  /// No description provided for @sourceText367ff5ddd2.
  ///
  /// In zh, this message translates to:
  /// **'总数'**
  String get sourceText367ff5ddd2;

  /// No description provided for @sourceText71bd34d484.
  ///
  /// In zh, this message translates to:
  /// **'最近一条'**
  String get sourceText71bd34d484;

  /// No description provided for @sourceText41654e0268.
  ///
  /// In zh, this message translates to:
  /// **'基础信息'**
  String get sourceText41654e0268;

  /// No description provided for @sourceText7364999103.
  ///
  /// In zh, this message translates to:
  /// **'载荷'**
  String get sourceText7364999103;

  /// No description provided for @sourceTextd70d425039.
  ///
  /// In zh, this message translates to:
  /// **'保存中...'**
  String get sourceTextd70d425039;

  /// No description provided for @sourceTextdbb4430dc0.
  ///
  /// In zh, this message translates to:
  /// **'未选择文件'**
  String get sourceTextdbb4430dc0;

  /// No description provided for @sourceText1e620e20a1.
  ///
  /// In zh, this message translates to:
  /// **'远程地址'**
  String get sourceText1e620e20a1;

  /// No description provided for @sourceTextdde21b2cec.
  ///
  /// In zh, this message translates to:
  /// **'后台运行权限'**
  String get sourceTextdde21b2cec;

  /// No description provided for @sourceText135f1636e4.
  ///
  /// In zh, this message translates to:
  /// **'应用列表读取'**
  String get sourceText135f1636e4;

  /// No description provided for @sourceTextf80103fee9.
  ///
  /// In zh, this message translates to:
  /// **'无障碍辅助权限'**
  String get sourceTextf80103fee9;

  /// No description provided for @sourceTextd78cde076b.
  ///
  /// In zh, this message translates to:
  /// **'已开启'**
  String get sourceTextd78cde076b;

  /// No description provided for @sourceText13ec170881.
  ///
  /// In zh, this message translates to:
  /// **'去开启'**
  String get sourceText13ec170881;

  /// No description provided for @sourceText291952a2ab.
  ///
  /// In zh, this message translates to:
  /// **'清除缓存'**
  String get sourceText291952a2ab;

  /// No description provided for @sourceText3d0c8b9d9f.
  ///
  /// In zh, this message translates to:
  /// **'小万可以在陪伴时更了解您的喜好'**
  String get sourceText3d0c8b9d9f;

  /// No description provided for @sourceText86890292b6.
  ///
  /// In zh, this message translates to:
  /// **'小万可在屏幕中实时活动，随时给予陪伴'**
  String get sourceText86890292b6;

  /// No description provided for @sourceTexta86909c7ea.
  ///
  /// In zh, this message translates to:
  /// **'小万可以知道能帮你做什么事情'**
  String get sourceTexta86909c7ea;

  /// No description provided for @sourceText56735a4ab7.
  ///
  /// In zh, this message translates to:
  /// **'小万执行任务时，需要给予我操作的权限'**
  String get sourceText56735a4ab7;

  /// No description provided for @sourceText99ad612dd1.
  ///
  /// In zh, this message translates to:
  /// **'设置权限'**
  String get sourceText99ad612dd1;

  /// No description provided for @sourceTextaef926661d.
  ///
  /// In zh, this message translates to:
  /// **'请放心，这些权限你随时可以收回'**
  String get sourceTextaef926661d;

  /// No description provided for @sourceText02a75489b2.
  ///
  /// In zh, this message translates to:
  /// **'查看并配置无障碍、悬浮窗、Shizuku 等权限'**
  String get sourceText02a75489b2;

  /// No description provided for @sourceText75b40989f3.
  ///
  /// In zh, this message translates to:
  /// **'权限检查中...'**
  String get sourceText75b40989f3;

  /// No description provided for @sourceText2599599947.
  ///
  /// In zh, this message translates to:
  /// **'继续任务'**
  String get sourceText2599599947;

  /// No description provided for @sourceText14411ce362.
  ///
  /// In zh, this message translates to:
  /// **'继续任务仅要求'**
  String get sourceText14411ce362;

  /// No description provided for @sourceTextf739c7d4a8.
  ///
  /// In zh, this message translates to:
  /// **'Termux 终端能力'**
  String get sourceTextf739c7d4a8;

  /// No description provided for @sourceText98bd36febc.
  ///
  /// In zh, this message translates to:
  /// **'可选，允许 Agent 通过 Termux 执行终端命令'**
  String get sourceText98bd36febc;

  /// No description provided for @sourceText53e32830a5.
  ///
  /// In zh, this message translates to:
  /// **'可选'**
  String get sourceText53e32830a5;

  /// No description provided for @sourceTexte5d269502c.
  ///
  /// In zh, this message translates to:
  /// **'让小万带你执行一次任务吧！'**
  String get sourceTexte5d269502c;

  /// No description provided for @sourceText1aca95f544.
  ///
  /// In zh, this message translates to:
  /// **'其中 Termux 终端能力为可选项，未开启也不影响基础自动化'**
  String get sourceText1aca95f544;

  /// No description provided for @sourceText3bf179d8d0.
  ///
  /// In zh, this message translates to:
  /// **'未绑定'**
  String get sourceText3bf179d8d0;

  /// No description provided for @sourceText2a30881946.
  ///
  /// In zh, this message translates to:
  /// **'清除绑定'**
  String get sourceText2a30881946;

  /// No description provided for @sourceTexta191935bc6.
  ///
  /// In zh, this message translates to:
  /// **'恢复默认'**
  String get sourceTexta191935bc6;

  /// No description provided for @sourceText8988c04935.
  ///
  /// In zh, this message translates to:
  /// **'点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；Voice 的音色与自动播放可通过调节按钮展开。'**
  String get sourceText8988c04935;

  /// No description provided for @sourceText2415f124bd.
  ///
  /// In zh, this message translates to:
  /// **'AI 响应完成后自动播放'**
  String get sourceText2415f124bd;

  /// No description provided for @sourceTextc4301894a2.
  ///
  /// In zh, this message translates to:
  /// **'音色'**
  String get sourceTextc4301894a2;

  /// No description provided for @sourceTextc0ae8ba446.
  ///
  /// In zh, this message translates to:
  /// **'例如：default_zh / mimo_default / default_en'**
  String get sourceTextc0ae8ba446;

  /// No description provided for @sourceTexta4ce420c69.
  ///
  /// In zh, this message translates to:
  /// **'风格'**
  String get sourceTexta4ce420c69;

  /// No description provided for @sourceText6614801dcd.
  ///
  /// In zh, this message translates to:
  /// **'自定义补充'**
  String get sourceText6614801dcd;

  /// No description provided for @sourceText558a2f3fd0.
  ///
  /// In zh, this message translates to:
  /// **'唱歌模式下不支持附加风格'**
  String get sourceText558a2f3fd0;

  /// No description provided for @sourceTextfa12d9ef1b.
  ///
  /// In zh, this message translates to:
  /// **'例如：更温柔、节奏慢一点、偏播客感'**
  String get sourceTextfa12d9ef1b;

  /// No description provided for @sourceText2601f9e3cb.
  ///
  /// In zh, this message translates to:
  /// **'收起语音设置'**
  String get sourceText2601f9e3cb;

  /// No description provided for @sourceTextbc2c7387f0.
  ///
  /// In zh, this message translates to:
  /// **'展开语音设置'**
  String get sourceTextbc2c7387f0;

  /// No description provided for @sourceText6a7d5cd91d.
  ///
  /// In zh, this message translates to:
  /// **'没有匹配的模型'**
  String get sourceText6a7d5cd91d;

  /// No description provided for @sourceText7b0de927a6.
  ///
  /// In zh, this message translates to:
  /// **'搜索模型 ID'**
  String get sourceText7b0de927a6;

  /// No description provided for @sourceTexte5463e3a94.
  ///
  /// In zh, this message translates to:
  /// **'请先在模型提供商页配置 Provider'**
  String get sourceTexte5463e3a94;

  /// No description provided for @sourceText13c9595745.
  ///
  /// In zh, this message translates to:
  /// **'该 Provider 暂无可选模型'**
  String get sourceText13c9595745;

  /// No description provided for @sourceText90bfe72640.
  ///
  /// In zh, this message translates to:
  /// **'已进入仅聊天模式'**
  String get sourceText90bfe72640;

  /// No description provided for @sourceText9c1153036d.
  ///
  /// In zh, this message translates to:
  /// **'已退出仅聊天模式'**
  String get sourceText9c1153036d;

  /// No description provided for @sourceTextd1a19c24c7.
  ///
  /// In zh, this message translates to:
  /// **'搜索技能名称或描述'**
  String get sourceTextd1a19c24c7;

  /// No description provided for @sourceTextd636ae3e01.
  ///
  /// In zh, this message translates to:
  /// **'未找到匹配的技能'**
  String get sourceTextd636ae3e01;

  /// No description provided for @sourceTexte4d8c16cd2.
  ///
  /// In zh, this message translates to:
  /// **'流式'**
  String get sourceTexte4d8c16cd2;

  /// No description provided for @sourceText36e8d9631f.
  ///
  /// In zh, this message translates to:
  /// **'非流式'**
  String get sourceText36e8d9631f;

  /// No description provided for @sourceText0e84ef42ae.
  ///
  /// In zh, this message translates to:
  /// **'请求地址'**
  String get sourceText0e84ef42ae;

  /// No description provided for @sourceText4d150364fe.
  ///
  /// In zh, this message translates to:
  /// **'请求方法'**
  String get sourceText4d150364fe;

  /// No description provided for @sourceTexta38a81c9d5.
  ///
  /// In zh, this message translates to:
  /// **'错误信息'**
  String get sourceTexta38a81c9d5;

  /// No description provided for @sourceText0228e74add.
  ///
  /// In zh, this message translates to:
  /// **'请求 JSON'**
  String get sourceText0228e74add;

  /// No description provided for @sourceText9f062a0dac.
  ///
  /// In zh, this message translates to:
  /// **'响应 JSON'**
  String get sourceText9f062a0dac;

  /// No description provided for @sourceTexte2d53a6d3a.
  ///
  /// In zh, this message translates to:
  /// **'重试'**
  String get sourceTexte2d53a6d3a;

  /// No description provided for @sourceText661b2db84d.
  ///
  /// In zh, this message translates to:
  /// **'加载请求日志失败'**
  String get sourceText661b2db84d;

  /// No description provided for @sourceTextfa604c3dba.
  ///
  /// In zh, this message translates to:
  /// **'最近还没有 AI 请求日志'**
  String get sourceTextfa604c3dba;

  /// No description provided for @sourceTexta22889b61d.
  ///
  /// In zh, this message translates to:
  /// **'加载运行日志失败'**
  String get sourceTexta22889b61d;

  /// No description provided for @sourceText71a159aa14.
  ///
  /// In zh, this message translates to:
  /// **'暂无运行日志'**
  String get sourceText71a159aa14;

  /// No description provided for @sourceText7b15e5e8e7.
  ///
  /// In zh, this message translates to:
  /// **'清除'**
  String get sourceText7b15e5e8e7;

  /// No description provided for @sourceTextfb57d700b9.
  ///
  /// In zh, this message translates to:
  /// **'AI 请求'**
  String get sourceTextfb57d700b9;

  /// No description provided for @sourceText7a42fe12dc.
  ///
  /// In zh, this message translates to:
  /// **'次对话'**
  String get sourceText7a42fe12dc;

  /// No description provided for @sourceTextadf4707731.
  ///
  /// In zh, this message translates to:
  /// **'天连续'**
  String get sourceTextadf4707731;

  /// No description provided for @sourceText0fe8227aa4.
  ///
  /// In zh, this message translates to:
  /// **'无对话'**
  String get sourceText0fe8227aa4;

  /// No description provided for @sourceText7a54a1229e.
  ///
  /// In zh, this message translates to:
  /// **'暂无 Token 消耗数据'**
  String get sourceText7a54a1229e;

  /// No description provided for @sourceTexte8666c377c.
  ///
  /// In zh, this message translates to:
  /// **'本地'**
  String get sourceTexte8666c377c;

  /// No description provided for @sourceText565481c9be.
  ///
  /// In zh, this message translates to:
  /// **'云端'**
  String get sourceText565481c9be;

  /// No description provided for @sourceText54c727b452.
  ///
  /// In zh, this message translates to:
  /// **'无消耗'**
  String get sourceText54c727b452;

  /// No description provided for @sourceText7fe4999970.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆未就绪'**
  String get sourceText7fe4999970;

  /// No description provided for @sourceTextb87a8a83f5.
  ///
  /// In zh, this message translates to:
  /// **'完成记忆初始化后，这里会展示跨会话沉淀的偏好与事实。'**
  String get sourceTextb87a8a83f5;

  /// No description provided for @sourceTextb92a2068aa.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆暂时不可用'**
  String get sourceTextb92a2068aa;

  /// No description provided for @sourceTextd3a2b13fc2.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆还是空的'**
  String get sourceTextd3a2b13fc2;

  /// No description provided for @sourceTexte8c59faf6d.
  ///
  /// In zh, this message translates to:
  /// **'当 Agent 主动写入长期偏好后，这里会逐渐丰富起来。'**
  String get sourceTexte8c59faf6d;

  /// No description provided for @sourceText495c0debaf.
  ///
  /// In zh, this message translates to:
  /// **'新增长期记忆'**
  String get sourceText495c0debaf;

  /// No description provided for @sourceText4398777297.
  ///
  /// In zh, this message translates to:
  /// **'刷新长期记忆'**
  String get sourceText4398777297;

  /// No description provided for @sourceText9e636642d6.
  ///
  /// In zh, this message translates to:
  /// **'刚刚'**
  String get sourceText9e636642d6;

  /// No description provided for @sourceTextedab852efe.
  ///
  /// In zh, this message translates to:
  /// **'思考完成'**
  String get sourceTextedab852efe;

  /// No description provided for @sourceText774d85ae0a.
  ///
  /// In zh, this message translates to:
  /// **'正在思考'**
  String get sourceText774d85ae0a;

  /// No description provided for @sourceTexte4b6477e6e.
  ///
  /// In zh, this message translates to:
  /// **'用时'**
  String get sourceTexte4b6477e6e;

  /// No description provided for @sourceText15fc7643c5.
  ///
  /// In zh, this message translates to:
  /// **'准备执行任务...'**
  String get sourceText15fc7643c5;

  /// No description provided for @sourceTextd258a63cad.
  ///
  /// In zh, this message translates to:
  /// **'取消任务'**
  String get sourceTextd258a63cad;

  /// No description provided for @sourceText6df9b76521.
  ///
  /// In zh, this message translates to:
  /// **'任务已取消'**
  String get sourceText6df9b76521;

  /// No description provided for @sourceText038d05ca8c.
  ///
  /// In zh, this message translates to:
  /// **'停止工具'**
  String get sourceText038d05ca8c;

  /// No description provided for @sourceText4078ac16b6.
  ///
  /// In zh, this message translates to:
  /// **'正在停止工具'**
  String get sourceText4078ac16b6;

  /// No description provided for @sourceTextcb1115d8c1.
  ///
  /// In zh, this message translates to:
  /// **'停止工具调用失败，请稍后重试'**
  String get sourceTextcb1115d8c1;

  /// No description provided for @sourceTexteac987a597.
  ///
  /// In zh, this message translates to:
  /// **'无法打开 Agent 管理面板'**
  String get sourceTexteac987a597;

  /// No description provided for @sourceTexte3f4d6bd9d.
  ///
  /// In zh, this message translates to:
  /// **'关闭悬浮球失败'**
  String get sourceTexte3f4d6bd9d;

  /// No description provided for @sourceTextf6d7e0312c.
  ///
  /// In zh, this message translates to:
  /// **'悬浮球已关闭，可在设置里重新开启'**
  String get sourceTextf6d7e0312c;

  /// No description provided for @sourceText066af21f55.
  ///
  /// In zh, this message translates to:
  /// **'点开'**
  String get sourceText066af21f55;

  /// No description provided for @sourceText15197efe93.
  ///
  /// In zh, this message translates to:
  /// **'隐藏悬浮球'**
  String get sourceText15197efe93;

  /// No description provided for @sourceText5d5815647c.
  ///
  /// In zh, this message translates to:
  /// **'收起'**
  String get sourceText5d5815647c;

  /// No description provided for @sourceText151eeabaf6.
  ///
  /// In zh, this message translates to:
  /// **'停止失败'**
  String get sourceText151eeabaf6;

  /// No description provided for @sourceText6ef1200428.
  ///
  /// In zh, this message translates to:
  /// **'运行中的 Agent'**
  String get sourceText6ef1200428;

  /// No description provided for @sourceText5bd8f4879e.
  ///
  /// In zh, this message translates to:
  /// **'当前没有后端任务'**
  String get sourceText5bd8f4879e;

  /// No description provided for @sourceTextfaeb185030.
  ///
  /// In zh, this message translates to:
  /// **'当前没有任何 Agent'**
  String get sourceTextfaeb185030;

  /// No description provided for @sourceText68685fc5c4.
  ///
  /// In zh, this message translates to:
  /// **'停止全部'**
  String get sourceText68685fc5c4;

  /// No description provided for @sourceTextf0b2cef7b0.
  ///
  /// In zh, this message translates to:
  /// **'没有正在执行的 Agent 后端任务'**
  String get sourceTextf0b2cef7b0;

  /// No description provided for @sourceText65fc81e161.
  ///
  /// In zh, this message translates to:
  /// **'打开'**
  String get sourceText65fc81e161;

  /// No description provided for @sourceText645fc8d22d.
  ///
  /// In zh, this message translates to:
  /// **'停止这个 Agent'**
  String get sourceText645fc8d22d;

  /// No description provided for @sourceText5e59efab1e.
  ///
  /// In zh, this message translates to:
  /// **'Agent 后端空闲。轻点打开管理面板。'**
  String get sourceText5e59efab1e;

  /// No description provided for @sourceText0b961ab4d9.
  ///
  /// In zh, this message translates to:
  /// **'正在整理方案'**
  String get sourceText0b961ab4d9;

  /// No description provided for @sourceText91796bb70a.
  ///
  /// In zh, this message translates to:
  /// **'正在输出'**
  String get sourceText91796bb70a;

  /// No description provided for @sourceText33fe6867a2.
  ///
  /// In zh, this message translates to:
  /// **'开始调用工具'**
  String get sourceText33fe6867a2;

  /// No description provided for @sourceText76a18aa532.
  ///
  /// In zh, this message translates to:
  /// **'工具执行中'**
  String get sourceText76a18aa532;

  /// No description provided for @sourceTextd333e5691f.
  ///
  /// In zh, this message translates to:
  /// **'工具完成'**
  String get sourceTextd333e5691f;

  /// No description provided for @sourceTextcc1f7be0b2.
  ///
  /// In zh, this message translates to:
  /// **'等待权限确认'**
  String get sourceTextcc1f7be0b2;

  /// No description provided for @sourceTextf7d01365f2.
  ///
  /// In zh, this message translates to:
  /// **'等待补充信息'**
  String get sourceTextf7d01365f2;

  /// No description provided for @sourceText9617084ded.
  ///
  /// In zh, this message translates to:
  /// **'运行出错'**
  String get sourceText9617084ded;

  /// No description provided for @sourceText832451d2f4.
  ///
  /// In zh, this message translates to:
  /// **'即将完成'**
  String get sourceText832451d2f4;

  /// No description provided for @sourceTextc6dc0ad888.
  ///
  /// In zh, this message translates to:
  /// **'Agent 后端任务'**
  String get sourceTextc6dc0ad888;

  /// No description provided for @sourceTextbdde1def59.
  ///
  /// In zh, this message translates to:
  /// **'等待模型响应'**
  String get sourceTextbdde1def59;

  /// No description provided for @sourceText3d4d1075e7.
  ///
  /// In zh, this message translates to:
  /// **'工具调用'**
  String get sourceText3d4d1075e7;

  /// No description provided for @sourceTextff06c243d7.
  ///
  /// In zh, this message translates to:
  /// **'超时'**
  String get sourceTextff06c243d7;

  /// No description provided for @sourceText44e681a374.
  ///
  /// In zh, this message translates to:
  /// **'中断'**
  String get sourceText44e681a374;

  /// No description provided for @sourceText71757f8d79.
  ///
  /// In zh, this message translates to:
  /// **'浏览中'**
  String get sourceText71757f8d79;

  /// No description provided for @sourceTextda3d2d1482.
  ///
  /// In zh, this message translates to:
  /// **'响应中'**
  String get sourceTextda3d2d1482;

  /// No description provided for @sourceTextfcb979ef0b.
  ///
  /// In zh, this message translates to:
  /// **'处理中'**
  String get sourceTextfcb979ef0b;

  /// No description provided for @sourceText7f55a26d7d.
  ///
  /// In zh, this message translates to:
  /// **'终端'**
  String get sourceText7f55a26d7d;

  /// No description provided for @sourceText88d650dd4f.
  ///
  /// In zh, this message translates to:
  /// **'浏览器'**
  String get sourceText88d650dd4f;

  /// No description provided for @sourceText81944e48a3.
  ///
  /// In zh, this message translates to:
  /// **'提醒'**
  String get sourceText81944e48a3;

  /// No description provided for @sourceText2ecbc11608.
  ///
  /// In zh, this message translates to:
  /// **'日历'**
  String get sourceText2ecbc11608;

  /// No description provided for @sourceText2a8ce33ff0.
  ///
  /// In zh, this message translates to:
  /// **'子任务'**
  String get sourceText2a8ce33ff0;

  /// No description provided for @sourceTexta72ef18d9a.
  ///
  /// In zh, this message translates to:
  /// **'工具'**
  String get sourceTexta72ef18d9a;

  /// No description provided for @sourceText15ec50fe7d.
  ///
  /// In zh, this message translates to:
  /// **'[更早记录已省略]'**
  String get sourceText15ec50fe7d;

  /// No description provided for @sourceTexta5dda12242.
  ///
  /// In zh, this message translates to:
  /// **'等待龙虾烹饪'**
  String get sourceTexta5dda12242;

  /// No description provided for @sourceText70c53b8ac3.
  ///
  /// In zh, this message translates to:
  /// **'配置你的 AI 助手'**
  String get sourceText70c53b8ac3;

  /// No description provided for @sourceTextf7f58b95a7.
  ///
  /// In zh, this message translates to:
  /// **'选择一种方式开始使用小万'**
  String get sourceTextf7f58b95a7;

  /// No description provided for @sourceText1670225703.
  ///
  /// In zh, this message translates to:
  /// **'云 AI 服务'**
  String get sourceText1670225703;

  /// No description provided for @sourceText90f71a54c8.
  ///
  /// In zh, this message translates to:
  /// **'连接 OpenAI、Anthropic 或兼容的 API 服务'**
  String get sourceText90f71a54c8;

  /// No description provided for @sourceTextb0253cd034.
  ///
  /// In zh, this message translates to:
  /// **'本地模型'**
  String get sourceTextb0253cd034;

  /// No description provided for @sourceText10691e242c.
  ///
  /// In zh, this message translates to:
  /// **'在设备上运行本地 AI，离线可用，隐私安全'**
  String get sourceText10691e242c;

  /// No description provided for @sourceText1fc1afc5c5.
  ///
  /// In zh, this message translates to:
  /// **'继续'**
  String get sourceText1fc1afc5c5;

  /// No description provided for @sourceText184913c0f3.
  ///
  /// In zh, this message translates to:
  /// **'跳过，稍后在设置中配置'**
  String get sourceText184913c0f3;

  /// No description provided for @sourceText1e797c0dac.
  ///
  /// In zh, this message translates to:
  /// **'云 AI 服务配置'**
  String get sourceText1e797c0dac;

  /// No description provided for @sourceText79973caeef.
  ///
  /// In zh, this message translates to:
  /// **'配置云端 AI 服务商，使用更强大的模型能力'**
  String get sourceText79973caeef;

  /// No description provided for @sourceText993df7d096.
  ///
  /// In zh, this message translates to:
  /// **'协议类型'**
  String get sourceText993df7d096;

  /// No description provided for @sourceText530aafb12a.
  ///
  /// In zh, this message translates to:
  /// **'例如：我的 OpenAI'**
  String get sourceText530aafb12a;

  /// No description provided for @sourceText10b7d8eccc.
  ///
  /// In zh, this message translates to:
  /// **'测试连接'**
  String get sourceText10b7d8eccc;

  /// No description provided for @sourceTexteb06635875.
  ///
  /// In zh, this message translates to:
  /// **'连接成功'**
  String get sourceTexteb06635875;

  /// No description provided for @sourceText523e40a074.
  ///
  /// In zh, this message translates to:
  /// **'发现'**
  String get sourceText523e40a074;

  /// No description provided for @sourceText674373aef1.
  ///
  /// In zh, this message translates to:
  /// **'个模型'**
  String get sourceText674373aef1;

  /// No description provided for @sourceText2c056f182f.
  ///
  /// In zh, this message translates to:
  /// **'连接失败'**
  String get sourceText2c056f182f;

  /// No description provided for @sourceTextd15ae9ad81.
  ///
  /// In zh, this message translates to:
  /// **'在设备上运行本地 AI'**
  String get sourceTextd15ae9ad81;

  /// No description provided for @sourceText4bbe706f8d.
  ///
  /// In zh, this message translates to:
  /// **'优势'**
  String get sourceText4bbe706f8d;

  /// No description provided for @sourceText37dea6b39e.
  ///
  /// In zh, this message translates to:
  /// **'隐私安全'**
  String get sourceText37dea6b39e;

  /// No description provided for @sourceText6bcfca9d58.
  ///
  /// In zh, this message translates to:
  /// **'数据完全留在设备上，不会发送到任何服务器'**
  String get sourceText6bcfca9d58;

  /// No description provided for @sourceText270d12d95b.
  ///
  /// In zh, this message translates to:
  /// **'离线可用'**
  String get sourceText270d12d95b;

  /// No description provided for @sourceText97fcfbb5dd.
  ///
  /// In zh, this message translates to:
  /// **'无需网络连接，随时随地使用 AI 助手'**
  String get sourceText97fcfbb5dd;

  /// No description provided for @sourceTextdd70b93ad6.
  ///
  /// In zh, this message translates to:
  /// **'完全免费'**
  String get sourceTextdd70b93ad6;

  /// No description provided for @sourceText5d4061aac5.
  ///
  /// In zh, this message translates to:
  /// **'无需 API 费用或订阅，没有使用限制'**
  String get sourceText5d4061aac5;

  /// No description provided for @sourceText498c2879b0.
  ///
  /// In zh, this message translates to:
  /// **'局限性'**
  String get sourceText498c2879b0;

  /// No description provided for @sourceTexta597376852.
  ///
  /// In zh, this message translates to:
  /// **'性能受限'**
  String get sourceTexta597376852;

  /// No description provided for @sourceTextb4d10c670a.
  ///
  /// In zh, this message translates to:
  /// **'端侧模型较小，能力有限，回复质量不如云端模型'**
  String get sourceTextb4d10c670a;

  /// No description provided for @sourceText390d11af9b.
  ///
  /// In zh, this message translates to:
  /// **'任务受限'**
  String get sourceText390d11af9b;

  /// No description provided for @sourceText7b5c99ecb8.
  ///
  /// In zh, this message translates to:
  /// **'目前无法处理复杂的 Agent 任务，适合简单对话和问答'**
  String get sourceText7b5c99ecb8;

  /// No description provided for @sourceTextea0ef2ae72.
  ///
  /// In zh, this message translates to:
  /// **'下一步'**
  String get sourceTextea0ef2ae72;

  /// No description provided for @sourceText68e23d8fac.
  ///
  /// In zh, this message translates to:
  /// **'下载本地模型'**
  String get sourceText68e23d8fac;

  /// No description provided for @sourceText405be21f38.
  ///
  /// In zh, this message translates to:
  /// **'推荐模型'**
  String get sourceText405be21f38;

  /// No description provided for @sourceTextf430f6d1d1.
  ///
  /// In zh, this message translates to:
  /// **'根据你的设备推荐的轻量模型，适合日常对话'**
  String get sourceTextf430f6d1d1;

  /// No description provided for @sourceText1b58744dff.
  ///
  /// In zh, this message translates to:
  /// **'正在加载模型信息...'**
  String get sourceText1b58744dff;

  /// No description provided for @sourceText3f9550508b.
  ///
  /// In zh, this message translates to:
  /// **'继续下载'**
  String get sourceText3f9550508b;

  /// No description provided for @sourceText4bbcf94739.
  ///
  /// In zh, this message translates to:
  /// **'下载完成'**
  String get sourceText4bbcf94739;

  /// No description provided for @sourceText33246f6a5e.
  ///
  /// In zh, this message translates to:
  /// **'完成'**
  String get sourceText33246f6a5e;

  /// No description provided for @sourceText11d0241540.
  ///
  /// In zh, this message translates to:
  /// **'返回'**
  String get sourceText11d0241540;

  /// No description provided for @sourceText85d011402f.
  ///
  /// In zh, this message translates to:
  /// **'暂时无法获取推荐模型'**
  String get sourceText85d011402f;

  /// No description provided for @sourceText1b2fe43b5e.
  ///
  /// In zh, this message translates to:
  /// **'请检查网络连接，或稍后在设置中手动下载'**
  String get sourceText1b2fe43b5e;

  /// No description provided for @sourceText2bc19ec67e.
  ///
  /// In zh, this message translates to:
  /// **'开始体验'**
  String get sourceText2bc19ec67e;

  /// No description provided for @sourceTextc55627eba1.
  ///
  /// In zh, this message translates to:
  /// **'API Key（可选）'**
  String get sourceTextc55627eba1;

  /// No description provided for @sourceText90988df4ff.
  ///
  /// In zh, this message translates to:
  /// **'浏览模型市场'**
  String get sourceText90988df4ff;

  /// No description provided for @sourceText62b46f24ae.
  ///
  /// In zh, this message translates to:
  /// **'推荐'**
  String get sourceText62b46f24ae;

  /// No description provided for @sourceTextc59773a6a4.
  ///
  /// In zh, this message translates to:
  /// **'选择方式'**
  String get sourceTextc59773a6a4;

  /// No description provided for @sourceTextb62ed716e3.
  ///
  /// In zh, this message translates to:
  /// **'特性'**
  String get sourceTextb62ed716e3;
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
