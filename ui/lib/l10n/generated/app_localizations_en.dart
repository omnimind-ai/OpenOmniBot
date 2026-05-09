// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appName => 'Omnibot';

  @override
  String get brandName => 'Omnibot';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => 'Loading';

  @override
  String get homeDrawerSearchHint => 'Search';

  @override
  String get homeDrawerClearSearch => 'Clear search';

  @override
  String get themeModeTitle => 'Theme Mode';

  @override
  String get themeModeSubtitle =>
      'Switch between light, dark, or system appearance';

  @override
  String get themeModeLight => 'Light';

  @override
  String get themeModeDark => 'Dark';

  @override
  String get themeModeSystem => 'System';

  @override
  String get languageTitle => 'Language';

  @override
  String get languageSubtitle =>
      'Choose the display language for the app UI, agent prompts, and tool text';

  @override
  String get languageFollowSystem => 'System';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get settingsSectionModelMemory => 'Models & Memory';

  @override
  String get settingsSectionServiceEnvironment => 'Services & Environment';

  @override
  String get settingsSectionExperienceAppearance => 'Experience & Appearance';

  @override
  String get settingsSectionPermissionInfo => 'Permissions & Info';

  @override
  String get settingsModelProviderTitle => 'Model Providers';

  @override
  String get settingsModelProviderSubtitle =>
      'Configure model endpoints, API keys, and model lists';

  @override
  String get settingsSceneModelTitle => 'Scene Model Config';

  @override
  String get settingsSceneModelSubtitle =>
      'Bind models by scene and use the default model for unbound scenes';

  @override
  String get settingsLocalModelsTitle => 'Local Model Service';

  @override
  String get settingsLocalModelsSubtitle =>
      'Manage local models, inference, API services, and speech models';

  @override
  String get settingsWorkspaceMemoryTitle => 'Workspace Memory';

  @override
  String get settingsWorkspaceMemoryLoading => 'Loading...';

  @override
  String get settingsWorkspaceMemoryEnabled =>
      'Workspace memory enabled (embedding retrieval available)';

  @override
  String get settingsWorkspaceMemoryLexical =>
      'Use workspace memory (currently lexical retrieval)';

  @override
  String get settingsMcpToolsTitle => 'MCP Tools';

  @override
  String get settingsMcpToolsSubtitle =>
      'Add, enable, and manage remote MCP services';

  @override
  String get settingsLocalServiceTitle => 'Local Service';

  @override
  String get settingsLocalServiceSubtitle =>
      'Access Omnibot MCP and webchat over your local network';

  @override
  String get settingsAlpineTitle => 'Alpine Environment';

  @override
  String get settingsAlpineSubtitle =>
      'View and open the built-in Alpine terminal environment';

  @override
  String get settingsHideRecentsTitle => 'Hide from Recents';

  @override
  String get settingsHideRecentsSubtitle =>
      'Hide the app from the recent tasks list when enabled';

  @override
  String get settingsAlarmTitle => 'Alarm Settings';

  @override
  String get settingsAlarmSubtitle =>
      'Configure the default ringtone, a local mp3, or an mp3 URL';

  @override
  String get settingsAppearanceTitle => 'Appearance';

  @override
  String get settingsAppearanceSubtitle =>
      'Configure theme mode, language, shared background, chat font size, and text color';

  @override
  String get settingsVibrationTitle => 'Vibration Feedback';

  @override
  String get settingsVibrationSubtitle =>
      'Use vibration to signal task progress while executing';

  @override
  String get settingsAutoBackTitle => 'Return to Chat After Tasks';

  @override
  String get settingsAutoBackSubtitle =>
      'When disabled, the task result page stays open after completion';

  @override
  String get settingsCompanionPermissionTitle => 'Companion App Permissions';

  @override
  String get settingsCompanionPermissionSubtitle =>
      'Only access apps you authorize for better privacy and safety';

  @override
  String get settingsAboutTitle => 'About Omnibot';

  @override
  String get settingsHideRecentsFailed => 'Failed to update hide-from-recents';

  @override
  String get settingsSaveFailed => 'Failed to save settings';

  @override
  String get settingsAutoBackEnabledToast =>
      'The app will return to chat after tasks finish';

  @override
  String get settingsAutoBackDisabledToast =>
      'The app will stay on the current page after tasks finish';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP enabled: $endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP disabled';

  @override
  String get settingsMcpToggleFailed => 'Failed to toggle MCP';

  @override
  String get settingsCopiedAddress => 'Address copied';

  @override
  String get settingsCopiedToken => 'Token copied';

  @override
  String get settingsTokenRefreshed => 'Token refreshed';

  @override
  String get settingsTokenRefreshFailed => 'Failed to refresh token';

  @override
  String get settingsMcpLocalService => 'Local Service';

  @override
  String get settingsMcpAddress => 'Address';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => 'Not generated';

  @override
  String get settingsCopyAddress => 'Copy Address';

  @override
  String get settingsCopyToken => 'Copy Token';

  @override
  String get settingsRefreshToken => 'Refresh Token';

  @override
  String get settingsMcpSecurityNotice =>
      'Call /mcp/v1/task/vlm on the same LAN with Authorization: Bearer <Token>, and avoid exposing the address or token to the public internet.';

  @override
  String get settingsInstalledAppsPermissionFailed =>
      'Failed to request installed apps permission';

  @override
  String get appearanceTitle => 'Appearance';

  @override
  String get appearanceAutoSaving => 'Saving changes…';

  @override
  String get appearanceAutosaveHint => 'Changes are saved automatically';

  @override
  String get appearanceBackgroundSource => 'Background Source';

  @override
  String get appearancePreview => 'Preview';

  @override
  String get appearanceAdjustments => 'Adjustments';

  @override
  String get appearancePreviewChat => 'Chat';

  @override
  String get appearancePreviewWorkspace => 'Workspace';

  @override
  String get appearanceEnableBackground => 'Enable background image';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      'Apply it to both Chat and Workspace pages and save automatically';

  @override
  String get appearanceSourceLocal => 'Local Image';

  @override
  String get appearanceSourceRemote => 'Image URL';

  @override
  String get appearanceNoLocalImage => 'No local image selected yet';

  @override
  String get appearancePickImage => 'Choose Image';

  @override
  String get appearanceRepickImage => 'Choose Again';

  @override
  String get appearanceRemoteImageUrl => 'Image URL';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => 'Background Blur';

  @override
  String get appearanceBackgroundBlurSubtitle =>
      'Adjust the blur of the overlay above the image';

  @override
  String get appearanceOverlayIntensity => 'Overlay Strength';

  @override
  String get appearanceOverlayIntensitySubtitle =>
      'Increase the unified overlay to make the UI cleaner';

  @override
  String get appearanceOverlayBrightness => 'Overlay Brightness';

  @override
  String get appearanceOverlayBrightnessSubtitle =>
      'Brighten or darken the overlay without modifying the image itself';

  @override
  String get appearanceChatTextSize => 'Chat Text Size';

  @override
  String get appearanceChatTextSizeSubtitle =>
      'Only affects user messages, AI replies, and the thinking panel';

  @override
  String get appearanceTextColorTitle => 'Chat Text Color';

  @override
  String get appearanceTextColorSubtitle =>
      'By default it adapts to the background, or you can pin a custom color';

  @override
  String get appearanceTextColorAuto => 'Auto';

  @override
  String get appearanceCustomColorLabel => 'Custom Color';

  @override
  String get appearanceCustomColorHint => '#FFFFFF or #FF112233';

  @override
  String get appearancePreviewTip =>
      'You can drag the image and pinch to zoom in the preview above. The preview stays close to the actual effect.';

  @override
  String get appearanceColorWhite => 'White';

  @override
  String get appearanceColorDarkGray => 'Dark Gray';

  @override
  String get appearanceColorLightBlue => 'Light Blue';

  @override
  String get appearanceColorNavy => 'Navy';

  @override
  String get appearanceColorTeal => 'Teal';

  @override
  String get appearanceColorWarmYellow => 'Warm Yellow';

  @override
  String get appearanceInvalidHttpUrl => 'Enter a valid http(s) image URL';

  @override
  String get appearanceInvalidHexColor => 'Enter #RRGGBB or #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => 'Invalid color code';

  @override
  String appearancePickImageFailed(Object error) {
    return 'Failed to pick image: $error';
  }

  @override
  String get appearancePickLocalImageFirst => 'Select a local image first';

  @override
  String get appearanceLocalImageMissing =>
      'The local image no longer exists. Please choose it again';

  @override
  String appearanceAutosaveFailed(Object error) {
    return 'Auto-save failed: $error';
  }

  @override
  String get chatToolCalling => 'Calling tool';

  @override
  String get chatFallbackReply =>
      'I can\'t generate a reply right now. Please try again.';

  @override
  String get chatPermissionRequired =>
      'Permissions must be enabled before running tasks';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return 'Enable these permissions before running tasks: $names';
  }

  @override
  String get chatRecentTerminalOutputNotice =>
      '[Only the most recent terminal output is shown]\n';

  @override
  String chatUserPrefix(Object text) {
    return 'User: $text\n';
  }

  @override
  String get permissionAccessibility => 'Accessibility';

  @override
  String get permissionOverlay => 'Overlay';

  @override
  String get permissionInstalledApps => 'Installed Apps Access';

  @override
  String get permissionPublicStorage => 'Public Storage Access';

  @override
  String get browserOverlayTitle => 'Agent Browser';

  @override
  String get browserOverlayClose => 'Close browser window';

  @override
  String get browserOverlayUnsupported =>
      'Browser tool view is not supported on this platform yet';

  @override
  String get networkErrorMessage =>
      'Sorry, the network stumbled just now. Please try sending it again.';

  @override
  String get rateLimitErrorMessage =>
      'Omnibot is busy right now. Please try again in a moment.';

  @override
  String get chatHistoryArchivedTitle => 'Archived Conversations';

  @override
  String get chatHistoryTitle => 'Chat History';

  @override
  String get chatHistoryNoArchived => 'No archived conversations';

  @override
  String get chatHistoryEmpty => 'No conversations yet';

  @override
  String get chatHistoryArchivedToast => 'Archived';

  @override
  String get chatHistoryUnarchivedToast => 'Moved out of archive';

  @override
  String get chatHistoryArchiveFailed => 'Couldn\'t archive the conversation';

  @override
  String get chatHistoryUnarchiveFailed => 'Couldn\'t restore the conversation';

  @override
  String get chatHistoryArchiveHint =>
      'Swipe left on a conversation to archive it';

  @override
  String get conversationStatusRunning => 'Running';

  @override
  String get conversationStatusCompleted => 'Completed';

  @override
  String get homeDrawerArchive => 'Archive';

  @override
  String get homeDrawerNewChat => 'New conversation';

  @override
  String get webchatNoChats => 'Start a new conversation';

  @override
  String get memoryCenterTitle => 'Memory Center';

  @override
  String get memoryShortTermTitle => 'Short-term Memory';

  @override
  String get memoryLongTermTitle => 'Long-term Memory';

  @override
  String get memoryNoShortTerm => 'No short-term memory yet';

  @override
  String get memoryNoShortTermDesc =>
      'Process information from conversations settles into short-term memory and later gets organized into long-term memory.';

  @override
  String get memoryFilteredNoShortTerm =>
      'No short-term memory under current filter';

  @override
  String get memoryFilteredNoShortTermDesc =>
      'Check back later, new short-term memories will appear gradually.';

  @override
  String get memoryNoLongTerm => 'Long-term memory not yet initialized';

  @override
  String get memoryNoLongTermDesc =>
      'Once memory capability is enabled, your cross-session long-term memories will accumulate here.';

  @override
  String get memoryDeleteConfirmTitle => 'Are you sure you want to delete?';

  @override
  String get memoryDeleteWarning => 'This action cannot be undone';

  @override
  String get memoryEditDisabled => 'Editing short-term memory is not supported';

  @override
  String get memoryDeleteDisabled =>
      'Deleting short-term memory is not supported';

  @override
  String get memoryGreeting =>
      'Hi there,\nWe\'ll keep your memories together here.';

  @override
  String memorySelectedCount(Object n) {
    return '$n selected';
  }

  @override
  String get memoryDeselectAll => 'Deselect all';

  @override
  String get memoryEditTitle => 'Edit Memory';

  @override
  String get memoryIdLabel => 'Memory ID';

  @override
  String get memoryMatchScore => 'Match Score';

  @override
  String get memoryAdditionalInfo => 'Additional Info';

  @override
  String get memoryAddLongTerm => 'Add Long-term Memory';

  @override
  String get memorySaveToLongTerm => 'Save to Long-term Memory';

  @override
  String get memoryLongTermAdded => 'Long-term memory added';

  @override
  String get memoryEditLongTerm => 'Edit Long-term Memory';

  @override
  String get memorySaveChanges => 'Save changes';

  @override
  String get memoryDeleteLongTermConfirm => 'Delete this long-term memory?';

  @override
  String get memoryLongTermDeleted => 'Long-term memory deleted';

  @override
  String memoryLongTermFailed(Object error) {
    return 'Long-term memory operation failed: $error';
  }

  @override
  String get memoryNoMemories => 'No memories';

  @override
  String get memoryNoMemoriesDesc => 'Start exploring and add content you like';

  @override
  String get skillStoreTitle => 'Skill Store';

  @override
  String get skillBuiltin => 'Built-in';

  @override
  String get skillUser => 'User';

  @override
  String get skillInstalled => 'Installed';

  @override
  String get skillNotInstalled => 'Not installed';

  @override
  String get skillEnabled => 'Enabled';

  @override
  String get skillDisabled => 'Disabled';

  @override
  String get skillInstall => 'Install';

  @override
  String get skillDelete => 'Delete';

  @override
  String get skillEmpty => 'No skills available';

  @override
  String get skillNoDescription => 'No description';

  @override
  String get skillBuiltinRemovedDesc =>
      'This built-in skill has been removed from the workspace. You can reinstall it anytime.';

  @override
  String get skillDeleteTitle => 'Delete Skill';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return 'Delete \"$name\"?';
  }

  @override
  String get skillDeleted => 'Deleted';

  @override
  String get skillDeleteFailed => 'Failed to delete';

  @override
  String skillInstalledMsg(Object name) {
    return 'Installed $name';
  }

  @override
  String get skillInstallFailed => 'Failed to install';

  @override
  String skillEnabledMsg(Object name) {
    return 'Enabled $name';
  }

  @override
  String skillDisabledMsg(Object name) {
    return 'Disabled $name';
  }

  @override
  String get skillToggleFailed => 'Failed to toggle';

  @override
  String get skillLoadFailed => 'Failed to load skills';

  @override
  String get trajectoryTitle => 'Trajectory';

  @override
  String get trajectoryNoRecords => 'No execution records';

  @override
  String get trajectoryNoRecordsDesc => 'VLM tasks will be displayed here';

  @override
  String get trajectoryAll => 'All';

  @override
  String get trajectoryTaskRecords => 'Task Records';

  @override
  String trajectorySelectedCount(Object n) {
    return '$n selected';
  }

  @override
  String get trajectoryUnknownDate => 'Unknown date';

  @override
  String get trajectoryThreeDaysAgo => '3 days ago';

  @override
  String get executionHistoryTitle => 'Execution History';

  @override
  String get executionHistorySubtitle => 'Recent 3 task executions';

  @override
  String get executionHistoryEmpty => 'No execution history';

  @override
  String executionHistoryTaskLabel(Object option) {
    return '$option Tasks';
  }

  @override
  String get modelProviderConfigTitle => 'Provider Configuration';

  @override
  String get modelProviderConfigDesc =>
      'Add, switch, and maintain model service provider names, addresses, and keys.';

  @override
  String get modelProviderName => 'Provider Name';

  @override
  String get modelProviderNameHint => 'e.g., DeepSeek';

  @override
  String get modelProviderBaseUrlHint =>
      'Append # to disable auto-complete request path';

  @override
  String get modelProviderApiKeyHint =>
      'Requests will be made without authentication when API Key is not filled in.';

  @override
  String get modelListTitle => 'Model List';

  @override
  String get modelListDesc =>
      'Supports manually adding models or fetching the remote model list from the current Provider.';

  @override
  String modelListCount(Object count) {
    return '$count models in total';
  }

  @override
  String get modelAddPrompt => 'Please add a model!';

  @override
  String get modelBuiltinProvider => 'Built-in Provider';

  @override
  String get modelIdEmpty =>
      'Model ID cannot be empty and cannot start with \'scene.\'';

  @override
  String get modelAlreadyExists => 'Model already exists';

  @override
  String get modelAdded => 'Model added';

  @override
  String get modelDeleted => 'Model deleted';

  @override
  String get modelDeleteFailed => 'Failed to delete model';

  @override
  String get modelIdHint => 'Enter model ID';

  @override
  String get modelAddProviderTitle => 'Add Provider';

  @override
  String get modelAddButton => 'Add';

  @override
  String get modelProviderAdded => 'Provider added';

  @override
  String modelProviderAddFailed(Object error) {
    return 'Failed to add Provider: $error';
  }

  @override
  String get modelDeleteProviderTitle => 'Delete Provider';

  @override
  String modelDeleteProviderMsg(Object name) {
    return 'Delete \"$name\"? Scene bindings will be preserved, but you need to reselect an available Provider.';
  }

  @override
  String get modelProviderDeleted => 'Provider deleted';

  @override
  String modelProviderDeleteFailed(Object error) {
    return 'Failed to delete Provider: $error';
  }

  @override
  String get modelProviderLoadFailed =>
      'Failed to load model provider settings';

  @override
  String modelProviderSwitchFailed(Object error) {
    return 'Failed to switch providers: $error';
  }

  @override
  String get modelProviderBaseUrlRequired => 'Enter a Base URL first';

  @override
  String get modelProviderInvalidBaseUrl => 'Enter a valid http(s) Base URL';

  @override
  String modelProviderFetchedModels(Object count) {
    return 'Fetched $count models';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return 'Failed to fetch the model list: $error';
  }

  @override
  String get sceneModelMapping => 'Scene Mapping';

  @override
  String get sceneModelMappingDesc =>
      'Bind Providers and models by scene. Unbound scenes will continue using the default model.';

  @override
  String get sceneModelRefreshList => 'Refresh model list';

  @override
  String get sceneModelSearchHint =>
      'Click the button on the right to search, collapse, and select models by Provider; the top search bar stays fixed.';

  @override
  String get sceneModelNoScenes => 'No configurable scenes';

  @override
  String get sceneModelLoadFailed => 'Failed to load scene model settings';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return 'Updated some models, but these providers failed: $profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return 'Updated $count models';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return 'Failed to refresh the model list: $error';
  }

  @override
  String get sceneModelInvalidModelId => 'Model ID can\'t start with scene.';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '$scene is now using $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return 'Failed to save $scene: $error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return 'Cleared the binding for $scene';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene is back to the default model';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return 'Failed to clear $scene: $error';
  }

  @override
  String sceneVoiceSaveFailed(Object error) {
    return 'Failed to save voice settings: $error';
  }

  @override
  String get localModelsTitle => 'Local Models';

  @override
  String get localModelsAutoPreheat => 'Auto preheat on app open';

  @override
  String get localModelsAutoPreheatDesc =>
      'Automatically start local service and load the current model when entering the app.';

  @override
  String get localModelsInstalled => 'Installed Models';

  @override
  String get localModelsInstalledDesc =>
      'Search, switch default model, or delete models on the current device.';

  @override
  String get localModelsSearchHint => 'Search model name, ID, or tag';

  @override
  String get localModelsEmpty => 'No local models available';

  @override
  String get localModelsEmptyDesc =>
      'Download a model from the market, or manually place an MNN model directory.';

  @override
  String get localModelsServiceControl => 'Service Control';

  @override
  String get localModelsServiceControlDesc =>
      'Switch inference backend, current model, and listening port.';

  @override
  String get localModelsInferenceBackend => 'Inference Backend';

  @override
  String get localModelsCurrentModel => 'Current Model';

  @override
  String get localModelsCurrentModelHint =>
      'The selected model will be loaded when the service starts.';

  @override
  String get localModelsNoAvailableModels => 'No models available';

  @override
  String get localModelsSelectModel => 'Select a model';

  @override
  String get localModelsServicePort => 'Service Port';

  @override
  String get localModelsServicePortHint => 'Enter port number';

  @override
  String get localModelsCurrentlyLoaded => 'Currently Loaded';

  @override
  String get localModelsAutoPreheatSection => 'Auto Preheat';

  @override
  String get localModelsAutoPreheatSectionDesc =>
      'Automatically start the local service and load the current model when the app opens.';

  @override
  String get localModelsLocalInference => 'Local Inference Model';

  @override
  String get localModelsStopping => 'Stopping…';

  @override
  String get localModelsStarting => 'Starting…';

  @override
  String get localModelsStopService => 'Stop Service';

  @override
  String get localModelsStartService => 'Start Service';

  @override
  String get localModelsConfigLoadFailed => 'Failed to load local model config';

  @override
  String get localModelsConfigLoadFailedDesc => 'Please try again later.';

  @override
  String get localModelsInstalledLoadFailed =>
      'Failed to load installed models';

  @override
  String get localModelsMarketLoadFailed => 'Failed to load model market';

  @override
  String get localModelsSwitchBackendFailed =>
      'Failed to switch inference backend';

  @override
  String get localModelsActiveModelUpdated => 'Current model updated';

  @override
  String get localModelsSetActiveFailed => 'Failed to set current model';

  @override
  String get localModelsPortInvalid => 'Invalid port number';

  @override
  String get localModelsPortUpdated => 'Service port updated';

  @override
  String get localModelsPortSaveFailed => 'Failed to save port';

  @override
  String get localModelsAutoPreheatSaveFailed =>
      'Failed to save auto preheat setting';

  @override
  String get localModelsDownloadSourceSwitchFailed =>
      'Failed to switch download source';

  @override
  String get localModelsServiceStarted => 'Local service started';

  @override
  String get localModelsStartFailed => 'Failed to start service';

  @override
  String get localModelsStopFailed => 'Failed to stop service';

  @override
  String get localModelsServiceStopped => 'Local service stopped';

  @override
  String get localModelsDownloadStartFailed => 'Failed to start download';

  @override
  String get localModelsDownloadPauseFailed => 'Failed to pause download';

  @override
  String localModelsDownloadStartedToast(String modelName) {
    return 'Download started: $modelName';
  }

  @override
  String localModelsDownloadPausedToast(String modelName) {
    return 'Download paused: $modelName';
  }

  @override
  String localModelsDownloadCompletedToast(String modelName) {
    return 'Download completed: $modelName';
  }

  @override
  String localModelsDownloadFailedToast(String modelName, String reason) {
    return 'Download failed: $modelName — $reason';
  }

  @override
  String localModelsDownloadCancelledToast(String modelName, String reason) {
    return 'Download cancelled: $modelName — $reason';
  }

  @override
  String get localModelsDownloadErrorUnknown => 'Unknown error';

  @override
  String get localModelsFilterAndSource => 'Filter & Source';

  @override
  String get localModelsFilterAndSourceDesc =>
      'Switch inference backend and download source; affects the current market list.';

  @override
  String get localModelsDownloadSource => 'Download Source';

  @override
  String get localModelsSelectDownloadSource => 'Select download source';

  @override
  String get localModelsMarketModels => 'Market Models';

  @override
  String get localModelsMarketModelsDesc =>
      'Search, download, pause, or delete models from the market.';

  @override
  String get localModelsMarketSearchHint =>
      'Search market model name, description, or tag';

  @override
  String get localModelsMarketEmpty => 'Model market is temporarily empty';

  @override
  String get localModelsMarketEmptyDesc =>
      'Please check the download source, or pull down to refresh and try again.';

  @override
  String get localModelsCurrentDefault => 'Default';

  @override
  String get localModelsLoaded => 'Loaded';

  @override
  String get localModelsFileSize => 'File Size';

  @override
  String get localModelsModelDir => 'Model Directory';

  @override
  String get localModelsManualDir =>
      'This is a manually placed directory. Deletion is not available in-app.';

  @override
  String get localModelsOmniInferLoadable =>
      'This model can be loaded directly by OmniInfer.';

  @override
  String get localModelsSetAsCurrent => 'Set as Current';

  @override
  String get localModelsDelete => 'Delete';

  @override
  String get localModelsHasUpdate => 'Update';

  @override
  String get localModelsStage => 'Stage';

  @override
  String get localModelsErrorInfo => 'Error Info';

  @override
  String get localModelsResumeDownload => 'Resume Download';

  @override
  String get localModelsRetryDownload => 'Retry Download';

  @override
  String get localModelsDownloadModel => 'Download Model';

  @override
  String get localModelsPause => 'Pause';

  @override
  String get localModelsDeleteOldVersion => 'Delete Old Version';

  @override
  String get localModelsTabService => 'Service';

  @override
  String get localModelsTabMarket => 'Market';

  @override
  String get localModelsRefresh => 'Refresh';

  @override
  String get localModelsDownloadPreparing => 'Preparing';

  @override
  String get localModelsDownloading => 'Downloading';

  @override
  String get localModelsDownloadPaused => 'Paused';

  @override
  String get localModelsDownloadCompleted => 'Completed';

  @override
  String get localModelsDownloadFailed => 'Download Failed';

  @override
  String get localModelsDownloadCancelled => 'Cancelled';

  @override
  String get localModelsNotDownloaded => 'Not Downloaded';

  @override
  String get localModelsImportFromDevice => 'Import from Device';

  @override
  String get localModelsImportSuccess => 'Model imported successfully';

  @override
  String localModelsImportFailed(String reason) {
    return 'Import failed: $reason';
  }

  @override
  String localModelsImporting(String modelId) {
    return 'Importing $modelId...';
  }

  @override
  String get alarmSaved => 'Alarm settings saved';

  @override
  String get alarmRingtoneSource => 'Ringtone Source';

  @override
  String get alarmSystemDefault => 'System Default';

  @override
  String get alarmSystemDefaultDesc =>
      'No extra configuration needed, best compatibility';

  @override
  String get alarmLocalMp3 => 'Local MP3';

  @override
  String get alarmLocalMp3Desc =>
      'Select an MP3 file on your phone as the alarm ringtone';

  @override
  String get alarmMp3Url => 'MP3 URL';

  @override
  String get alarmMp3UrlDesc => 'Use an HTTP(S) URL to play an online MP3';

  @override
  String get alarmAudioPermissionDenied => 'Audio read permission not granted';

  @override
  String get alarmInvalidFilePath => 'Invalid file path, please select again';

  @override
  String get alarmSelectLocalFirst => 'Please select a local MP3 file first';

  @override
  String get alarmEnterHttpsUrl => 'Please enter an HTTP(S) MP3 URL';

  @override
  String get alarmLocalFile => 'Local File';

  @override
  String get alarmSelectMp3 => 'Select MP3 File';

  @override
  String get authorizePageTitle => 'App Permission Authorization';

  @override
  String get authorizeReceiveNotifications => 'Receive message notifications';

  @override
  String get authorizeNotificationsDesc =>
      'Enable this to get task progress updates in time';

  @override
  String get companionPermissionManagement => 'Companion Permission Management';

  @override
  String get companionPermissionDesc =>
      'After revoking authorization, Omnibot will still be displayed but task execution content will be hidden';

  @override
  String get companionPermissionNote => 'Permission Notes';

  @override
  String get companionAuthorizedApps => 'Authorized Apps';

  @override
  String get storageUsageTitle => 'Storage Usage';

  @override
  String get storageUsageSubtitle =>
      'View storage usage details and clean up by category';

  @override
  String get storageAnalyzeFailed =>
      'Storage analysis failed, please try again';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return 'Cleaned $name, freed $size';
  }

  @override
  String get storageCleanFailed => 'Cleanup failed, please try again later';

  @override
  String storageCleanCategory(Object name) {
    return 'Clean $name';
  }

  @override
  String get storageCleanConfirmMsg => 'Confirm cleanup of this category?';

  @override
  String get storageCleanScope => 'Cleanup Scope';

  @override
  String get storageCleanAll => 'All';

  @override
  String get storageClean7Days => '7 days ago';

  @override
  String get storageClean30Days => '30 days ago';

  @override
  String storageStrategyName(Object name) {
    return 'Strategy: $name';
  }

  @override
  String storageStrategyDone(Object size) {
    return 'Strategy completed, freed $size';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return 'Strategy completed, freed $size, $count items not fully successful';
  }

  @override
  String get storageStrategyFailed => 'Strategy failed, please try again later';

  @override
  String get storageLoadFailed => 'Failed to load';

  @override
  String get storageReanalyze => 'Reanalyze';

  @override
  String get storageTotalUsage => 'Total Usage';

  @override
  String get storageAppSize => 'App Size';

  @override
  String get storageUserData => 'User Data';

  @override
  String get storageCleanable => 'Cleanable';

  @override
  String storageStatsSource(Object source) {
    return 'Statistics source: $source';
  }

  @override
  String storagePackageName(Object name) {
    return 'Current package: $name';
  }

  @override
  String get storageTrendFirst =>
      'This is the first analysis. Usage trends will be shown in future analyses.';

  @override
  String get storageSmartCleanup => 'Smart Cleanup';

  @override
  String get storageExecute => 'Execute';

  @override
  String get storageUsageAnalysis => 'Usage Analysis';

  @override
  String get storageClean => 'Clean';

  @override
  String get storageRiskLow => 'Low Risk';

  @override
  String get storageRiskCaution => 'Caution';

  @override
  String get storageRiskHigh => 'High Risk';

  @override
  String get storageReadOnly => 'Read Only';

  @override
  String get storageSystemStats =>
      'System statistics (closer to system settings)';

  @override
  String get storageDirectoryScan => 'Directory scan estimate';

  @override
  String get storageAdditionalInfo => 'Additional Info';

  @override
  String get storageCatAppBinary => 'App Binary';

  @override
  String get storageCatAppBinaryDesc => 'Installed app files (APK/AAB split)';

  @override
  String get storageCatCache => 'Cache';

  @override
  String get storageCatCacheDesc =>
      'Temporary files and image cache, safe to clean';

  @override
  String get storageCatCacheHint =>
      'Will regenerate automatically during use after cleanup';

  @override
  String get storageCatConversation => 'Conversation History';

  @override
  String get storageCatConversationDesc =>
      'Chat and tool execution history (estimated)';

  @override
  String get storageCatConversationHint =>
      'Will delete historical message records and cannot be recovered';

  @override
  String get storageCatDatabaseOther => 'Other Database';

  @override
  String get storageCatDatabaseOtherDesc => 'Indexes and system tables';

  @override
  String get storageCatWorkspaceBrowser => 'Workspace Browser Artifacts';

  @override
  String get storageCatWorkspaceBrowserDesc =>
      'Browser screenshots, downloads, and intermediate files';

  @override
  String get storageCatWorkspaceBrowserHint =>
      'Will delete browser tool intermediate files';

  @override
  String get storageCatWorkspaceOffloads => 'Workspace Offloads';

  @override
  String get storageCatWorkspaceOffloadsDesc =>
      'Tool offline outputs and temporary files';

  @override
  String get storageCatWorkspaceOffloadsHint =>
      'Only deletes offline artifacts, does not affect core functionality';

  @override
  String get storageCatWorkspaceAttachments => 'Workspace Attachments';

  @override
  String get storageCatWorkspaceAttachmentsDesc =>
      'Attachment files used by historical tasks';

  @override
  String get storageCatWorkspaceAttachmentsHint =>
      'May affect viewing attachments in historical tasks';

  @override
  String get storageCatWorkspaceShared => 'Workspace Shared';

  @override
  String get storageCatWorkspaceSharedDesc =>
      'Shared workspace files across tasks';

  @override
  String get storageCatWorkspaceSharedHint =>
      'May affect subsequent tasks reusing shared files';

  @override
  String get storageCatWorkspaceMemory => 'Workspace Memory Data';

  @override
  String get storageCatWorkspaceMemoryDesc =>
      'Long/short-term memory and index data';

  @override
  String get storageCatWorkspaceUserFiles => 'Workspace User Files';

  @override
  String get storageCatWorkspaceUserFilesDesc =>
      'Files manually saved to workspace by user';

  @override
  String get storageCatLocalModelsFiles => 'Local Model Files';

  @override
  String get storageCatLocalModelsFilesDesc => 'Model files under .mnnmodels';

  @override
  String get storageCatLocalModelsFilesHint =>
      'Will delete model files, need to re-download later';

  @override
  String get storageCatLocalModelsCache => 'Model Inference Cache';

  @override
  String get storageCatLocalModelsCacheDesc =>
      'mmap and local inference temporary directories';

  @override
  String get storageCatLocalModelsCacheHint =>
      'Will regenerate during inference after cleanup';

  @override
  String get storageCatTerminalLocal => 'Terminal Runtime (local)';

  @override
  String get storageCatTerminalLocalDesc =>
      'Alpine terminal local runtime directory';

  @override
  String get storageCatTerminalLocalHint =>
      'Will delete terminal local directory, needs re-initialization';

  @override
  String get storageCatTerminalBootstrap => 'Terminal Runtime (bootstrap)';

  @override
  String get storageCatTerminalBootstrapDesc =>
      'proot/lib/alpine bootstrap files';

  @override
  String get storageCatTerminalBootstrapHint =>
      'Will delete terminal bootstrap files, needs re-initialization';

  @override
  String get storageCatSharedDrafts => 'Shared Drafts';

  @override
  String get storageCatSharedDraftsDesc =>
      'Draft cache from external sharing imports';

  @override
  String get storageCatSharedDraftsHint =>
      'Will delete unsent draft attachments';

  @override
  String get storageCatMcpInbox => 'MCP Inbox';

  @override
  String get storageCatMcpInboxDesc => 'MCP file transfer receive directory';

  @override
  String get storageCatMcpInboxHint => 'Will delete files in MCP inbox';

  @override
  String get storageCatLegacyWorkspace => 'Legacy Data';

  @override
  String get storageCatLegacyWorkspaceDesc =>
      'Old workspace directories possibly left after upgrade';

  @override
  String get storageCatLegacyWorkspaceHint =>
      'Confirm it is no longer needed before cleanup';

  @override
  String get storageCatOtherUserData => 'Other Data';

  @override
  String get storageCatOtherUserDataDesc =>
      'Data not matched to any category rule';

  @override
  String get storageStrategySafeQuick => 'Safe Quick Cleanup';

  @override
  String get storageStrategySafeQuickDesc =>
      'Prioritize cleaning low-risk cache and temporary artifacts';

  @override
  String get storageStrategyBalanceDeep => 'Balanced Deep Cleanup';

  @override
  String get storageStrategyBalanceDeepDesc =>
      'Free more space while keeping core models and user files';

  @override
  String get storageStrategyFree1gb => 'Target Free 1GB';

  @override
  String get storageStrategyFree1gbDesc =>
      'Clean in high-value order, aiming for 1GB release target';

  @override
  String get storageHintConversation =>
      'If history is not released, re-enter the page and run \"Reanalyze\"';

  @override
  String get storageHintLocalModels =>
      'After models are cleaned, you can re-download from the Local Model Service page';

  @override
  String get storageHintTerminal =>
      'After terminal runtime is cleaned, you can re-initialize from the Alpine Environment page';

  @override
  String get storageHintGeneral =>
      'If cleanup fails, try again later or restart the app';

  @override
  String get storageHintNotCleanable =>
      'This category is currently not cleanable';

  @override
  String get storageHintSkipped => 'This category was skipped (optional)';

  @override
  String storageCleanPartialFailed(Object hint) {
    return 'Some cleanup failed: $hint';
  }

  @override
  String get storageCleanPartialFailedGeneric =>
      'Some files failed to clean up, please try again later';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return 'Vs last analysis: total $total, cleanable $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return 'Last analyzed: $time';
  }

  @override
  String get aboutDescription =>
      'Omnibot is an AI assistant app centered on\nintelligent conversation, using semantic understanding\nand continuous learning to help with information\nprocessing, decision support, and daily management.';

  @override
  String get aboutBetaProgramTitle => 'Join beta testing';

  @override
  String get aboutBetaProgramDescription =>
      'Get faster four-part beta updates.';

  @override
  String get aboutBetaProgramToggleFailed =>
      'Failed to update beta testing preference';

  @override
  String get aboutPreferencesSectionTitle => 'Update & Testing';

  @override
  String get aboutApkSourceTitle => 'APK Download Source';

  @override
  String get aboutApkSourceDescription =>
      'Choose the source used for update installs.';

  @override
  String get aboutApkSourceOptionCnb => 'CNB';

  @override
  String get aboutApkSourceOptionCnbDescription => 'Best for mainland China';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription => 'Official release source';

  @override
  String get aboutApkSourceSwitchFailed =>
      'Failed to switch APK download source';

  @override
  String get aboutUpdateHintDefault =>
      'Check for updates to get the latest version';

  @override
  String get workspaceMemoryLoadFailed =>
      'Failed to load workspace memory config';

  @override
  String get workspaceSoulSaved => 'SOUL.md saved';

  @override
  String get workspaceSoulSaveFailed => 'Failed to save SOUL.md';

  @override
  String get workspaceChatSaved => 'CHAT.md saved';

  @override
  String get workspaceChatSaveFailed => 'Failed to save CHAT.md';

  @override
  String get workspaceMemorySaved => 'MEMORY.md saved';

  @override
  String get workspaceMemorySaveFailed => 'Failed to save MEMORY.md';

  @override
  String get workspaceEmbeddingToggleFailed =>
      'Failed to update memory embedding toggle';

  @override
  String get workspaceRollupToggleFailed =>
      'Failed to update nightly rollup toggle';

  @override
  String get workspaceRollupDone => 'Rollup completed';

  @override
  String get workspaceRollupFailed => 'Rollup failed';

  @override
  String get workspaceNone => 'None';

  @override
  String get workspaceMemoryTitle => 'Workspace Memory';

  @override
  String get workspaceMemoryCapability => 'Memory Capability';

  @override
  String get workspaceEmbeddingReady =>
      'Configured, vector retrieval available';

  @override
  String get workspaceEmbeddingNotReady =>
      'Not configured, will fall back to lexical retrieval';

  @override
  String get workspaceGoToConfig =>
      'Go to scene model config to set up embedding model';

  @override
  String get workspaceNightlyRollup => 'Nightly Memory Rollup (22:00)';

  @override
  String workspaceLastRun(Object time) {
    return 'Last run: $time';
  }

  @override
  String workspaceNextRun(Object time) {
    return 'Next run: $time';
  }

  @override
  String get workspaceRollupNow => 'Rollup now';

  @override
  String get workspaceDocContent => 'Document Content';

  @override
  String get workspaceSoulMd => 'SOUL.md (Agent Soul)';

  @override
  String get workspaceChatMd => 'CHAT.md (Chat-only system prompt)';

  @override
  String get workspaceMemoryMd => 'MEMORY.md (Long-term Memory)';

  @override
  String get alpineNodeJs => 'Node.js Runtime';

  @override
  String get alpineNpm => 'Node.js Package Manager';

  @override
  String get alpineGit => 'Git Version Control';

  @override
  String get alpinePython => 'Python Interpreter';

  @override
  String get alpinePip => 'Python Projects & Packages';

  @override
  String get alpinePipInstall => 'Python Package Installer';

  @override
  String get alpineCodex => 'OpenAI Codex CLI and app-server bridge';

  @override
  String get alpineSshClient => 'SSH Client';

  @override
  String get alpineSshpass => 'SSH Password Helper';

  @override
  String get alpineOpenSshServer => 'OpenSSH Server';

  @override
  String get alpineDetectFailed => 'Failed to detect Alpine environment';

  @override
  String get alpineBootTasksLoadFailed => 'Failed to load boot tasks';

  @override
  String get alpineConfigOpenFailed =>
      'Failed to open terminal environment config';

  @override
  String get alpineBootTaskAdded => 'Boot task added';

  @override
  String get alpineBootTaskUpdated => 'Boot task updated';

  @override
  String get alpineBootTaskSaveFailed => 'Failed to save boot task';

  @override
  String get alpineBootEnabled => 'Enabled auto-start on app launch';

  @override
  String get alpineBootDisabled => 'Disabled auto-start';

  @override
  String get alpineBootTaskUpdateFailed => 'Failed to update task';

  @override
  String get alpineDeleteBootTask => 'Delete Boot Task';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return 'Delete \"$name\"?';
  }

  @override
  String get alpineBootTaskDeleted => 'Boot task deleted';

  @override
  String get alpineBootTaskDeleteFailed => 'Failed to delete task';

  @override
  String get alpineCommandSent => 'Start command sent';

  @override
  String get alpineStartFailed => 'Failed to start task';

  @override
  String get alpineDetecting => 'Detecting environment';

  @override
  String alpineStartConfig(Object count) {
    return 'Start configuration ($count items)';
  }

  @override
  String get alpineAllReady => 'All ready';

  @override
  String get alpineDetectingDesc =>
      'Detecting version info of common development tools in Alpine in the background.';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '$ready/$total items ready. Check missing items and auto-configure in ReTerminal.';
  }

  @override
  String get alpineBootTasks => 'Boot Tasks';

  @override
  String get alpineBootTasksDesc =>
      'When Omnibot opens, enabled tasks are checked in the background and commands are started in the corresponding ReTerminal session. Suitable for persistent services.';

  @override
  String get alpineAddTask => 'Add Task';

  @override
  String get alpineOpenTerminal => 'Open Terminal';

  @override
  String get alpineNoTasksDesc =>
      'No tasks. You can add persistent commands like `python app.py`, `node server.js`, or `./start.sh`.';

  @override
  String get alpineBootOnAppOpen => 'Start after app opens on boot';

  @override
  String get alpineNotEnabled => 'Not enabled';

  @override
  String get alpineRunning => 'Running';

  @override
  String get alpineStartNow => 'Start Now';

  @override
  String get alpineEdit => 'Edit';

  @override
  String get alpineVersionDetected => 'Version detected';

  @override
  String get alpineVersionNotFound => 'Not detected';

  @override
  String get alpineTaskNameHint => 'Enter task name';

  @override
  String get alpineCommandHint => 'Enter start command';

  @override
  String get alpineEditBootTask => 'Edit Boot Task';

  @override
  String get alpineAddBootTask => 'Add Boot Task';

  @override
  String get alpineTaskName => 'Task Name';

  @override
  String get alpineTaskNameExample => 'e.g., Local API service';

  @override
  String get alpineStartCommand => 'Start Command';

  @override
  String get alpineCommandExample => 'e.g., python app.py or pnpm start';

  @override
  String get alpineWorkDir => 'Working Directory';

  @override
  String get alpineBootAutoStart => 'Auto-start when Omnibot opens';

  @override
  String get alpineDevEnv => 'Dev Environment';

  @override
  String get alpineAiAgent => 'AI Agent';

  @override
  String get alpineEnvConfig => 'Environment Config';

  @override
  String alpineWorkDirValue(Object dir) {
    return 'Working directory: $dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => 'Memory Embedding Retrieval';

  @override
  String get chatHistoryStartConversation => 'Start a conversation';

  @override
  String get homeDrawerSearching => 'Searching conversations...';

  @override
  String get homeDrawerNoResults => 'No matching conversations found';

  @override
  String get homeDrawerSearchHint2 =>
      'Try shorter keywords or rephrase your search';

  @override
  String get homeDrawerSearchResults => 'Search results';

  @override
  String get homeDrawerResultCount => 'results';

  @override
  String get homeDrawerScheduled => 'Scheduled';

  @override
  String get homeDrawerGreeting => 'Hello!';

  @override
  String get homeDrawerWelcome => 'Welcome to Omnibot';

  @override
  String get homeDrawerDawnGreeting => 'Late night';

  @override
  String get homeDrawerDawnSub => 'Still awake?';

  @override
  String get homeDrawerDawnGreeting2 => 'Before dawn';

  @override
  String get homeDrawerDawnSub2 => 'Early bird, take care!';

  @override
  String get homeDrawerDawnGreeting3 => 'Quiet midnight';

  @override
  String get homeDrawerDawnSub3 => 'Remember to get some rest.';

  @override
  String get homeDrawerMorningGreeting => 'Good morning!';

  @override
  String get homeDrawerMorningSub => 'Start your day with energy';

  @override
  String get homeDrawerMorningGreeting2 => 'Morning!';

  @override
  String get homeDrawerMorningSub2 => 'A new day has begun';

  @override
  String get homeDrawerForenoonGreeting => 'Good forenoon!';

  @override
  String get homeDrawerForenoonSub => 'Take a quick shoulder stretch';

  @override
  String get homeDrawerForenoonGreeting2 => 'Great momentum!';

  @override
  String get homeDrawerForenoonSub2 => 'Keep it going';

  @override
  String get homeDrawerLunchGreeting => 'Lunch time!';

  @override
  String get homeDrawerLunchSub => 'Have a proper meal';

  @override
  String get homeDrawerLunchGreeting2 => 'Good noon~';

  @override
  String get homeDrawerLunchSub2 => 'Take a short break after lunch';

  @override
  String get homeDrawerLunchGreeting3 => 'Not sure what to eat?';

  @override
  String get homeDrawerLunchSub3 => 'Let Omnibot recommend for you';

  @override
  String get homeDrawerAfternoonGreeting => 'Time for a tea break';

  @override
  String get homeDrawerAfternoonSub => 'You\'ve got this!';

  @override
  String get homeDrawerAfternoonGreeting2 => 'Look away for a bit';

  @override
  String get homeDrawerAfternoonSub2 => 'Refresh your eyes for a moment';

  @override
  String get homeDrawerEveningGreeting => 'Take it easy on the way home';

  @override
  String get homeDrawerEveningSub => 'Relax tonight';

  @override
  String get homeDrawerEveningGreeting2 => 'Evening breeze';

  @override
  String get homeDrawerEveningSub2 => 'Feels nice, doesn\'t it?';

  @override
  String get homeDrawerEveningGreeting3 => 'Long day today';

  @override
  String get homeDrawerEveningSub3 => 'Treat yourself to a good meal';

  @override
  String get homeDrawerNightGreeting => 'Good evening!';

  @override
  String get homeDrawerNightSub => 'Enjoy your own time';

  @override
  String get homeDrawerNightGreeting2 => 'Night is settling in';

  @override
  String get homeDrawerNightSub2 => 'Get ready to rest earlier';

  @override
  String get homeDrawerNightGreeting3 => 'Time to rest';

  @override
  String get homeDrawerNightSub3 => 'Let Omnibot set an alarm for you';

  @override
  String get homeDrawerLateNightGreeting =>
      'Put the phone down and sleep earlier';

  @override
  String get homeDrawerLateNightSub => 'Recharge for tomorrow';

  @override
  String get homeDrawerLateNightGreeting2 => 'It is late';

  @override
  String get homeDrawerLateNightSub2 => 'Say good night to today';

  @override
  String get omniflowPanelTitle => 'OmniFlow Trajectory Panel';

  @override
  String get omniflowPanelDesc =>
      'Manage OmniFlow Functions: view, execute, or delete Function assets.';

  @override
  String get omniflowFunctionList => 'Function List';

  @override
  String get omniflowFunctionSearch => 'Search Functions';

  @override
  String get omniflowFunctionSearchHint => 'Filter by name, description, etc.';

  @override
  String get omniflowSettings => 'OmniFlow Settings';

  @override
  String get omniflowSettingsSubtitle =>
      'Record reusable action sequences to accelerate tasks';

  @override
  String get omniflowEnablePreHook => 'Enable OmniFlow Acceleration';

  @override
  String get omniflowAutoStartProvider => 'OmniFlow Auto-start';

  @override
  String get omniflowRefresh => 'Refresh';

  @override
  String get omniflowProviderStart => 'Start';

  @override
  String get omniflowProviderStop => 'Stop';

  @override
  String get omniflowProviderRestart => 'Restart';

  @override
  String get omniflowSaveConfig => 'Save';

  @override
  String get omniflowConfigSaved => 'OmniFlow config saved';

  @override
  String get omniflowConfigSaveFailed => 'Failed to save OmniFlow config';

  @override
  String get omniflowConfigLoadFailed => 'Failed to load OmniFlow config';

  @override
  String get omniflowFunctionsLoadFailed => 'Failed to load Functions';

  @override
  String get omniflowTempFunctions => 'Temporary Functions';

  @override
  String get omniflowReadyFunctions => 'Ready Functions';

  @override
  String get omniflowServiceAddressNotConfigured =>
      'Service address not configured';

  @override
  String get omniflowSkillLibrary => 'OmniFlow Skill Library';

  @override
  String get omniflowServiceStatus => 'Service Status';

  @override
  String get omniflowServiceStatusRunning => 'Running';

  @override
  String get omniflowServiceStatusStopped => 'Not Running';

  @override
  String get omniflowServiceAddress => 'Service Address';

  @override
  String get omniflowDataDirectory => 'Data Directory';

  @override
  String get omniflowNotSet => 'Not set';

  @override
  String get omniflowEnableAccelerationDesc =>
      'Match learned skills before executing tasks';

  @override
  String get omniflowAutoStartDesc => 'Auto-start skill service when app opens';

  @override
  String get omniflowStarting => 'Starting...';

  @override
  String get omniflowRestarting => 'Restarting...';

  @override
  String get omniflowStopping => 'Stopping...';

  @override
  String get omniflowViewSkillLibrary => 'View Skill Library';

  @override
  String get omniflowViewFunctionLibrary => 'View Functions';

  @override
  String get omniflowClearAllData => 'Clear All Data';

  @override
  String get omniflowClearAllDataTitle => 'Clear All Data';

  @override
  String get omniflowClearAllDataConfirm =>
      'Confirm clear all OmniFlow data?\n\nThis will delete:\n• All Functions\n• All Run Logs\n• All Shared Pages\n\nThis action cannot be undone!';

  @override
  String get omniflowCancel => 'Cancel';

  @override
  String get omniflowClear => 'Clear';

  @override
  String omniflowClearSuccess(Object functions, Object runLogs) {
    return 'Cleared: $functions functions, $runLogs run_logs';
  }

  @override
  String get omniflowClearFailed => 'Clear failed';

  @override
  String omniflowProviderActionSuccess(Object action) {
    return 'provider $action success';
  }

  @override
  String omniflowProviderActionFailed(Object action) {
    return 'provider $action failed';
  }

  @override
  String get functionLibraryTitle => 'Functions';

  @override
  String get functionLibrarySearchHint => 'Search functions or apps';

  @override
  String get functionLibraryEmpty => 'No learned functions yet';

  @override
  String get functionLibraryEmptyDesc =>
      'Frequently used actions will be saved here after task execution';

  @override
  String get functionLibrarySteps => 'steps';

  @override
  String get functionLibraryHasParams => 'has params';

  @override
  String get functionLibraryRunCount => 'runs';

  @override
  String get functionLibraryId => 'ID';

  @override
  String get functionLibraryParams => 'Params';

  @override
  String get functionLibrarySource => 'Source';

  @override
  String get functionLibraryCreatedAt => 'Created';

  @override
  String get functionLibraryEdit => 'Edit';

  @override
  String get functionLibraryEditTitle => 'Edit Function';

  @override
  String get functionLibraryEditHint => 'Modify function description';

  @override
  String get functionLibraryEditPlaceholder => 'Enter new description';

  @override
  String get functionLibraryEditSuccess => 'Updated';

  @override
  String get functionLibraryEditFailed => 'Update failed';

  @override
  String get functionLibraryDelete => 'Delete';

  @override
  String get functionLibraryDeleteTitle => 'Delete Function';

  @override
  String functionLibraryDeleteConfirm(Object name) {
    return 'Delete \"$name\"?';
  }

  @override
  String get functionLibraryDeleted => 'Deleted';

  @override
  String get functionLibraryDeleteFailed => 'Delete failed';

  @override
  String get functionLibraryUpload => 'Upload';

  @override
  String get functionLibraryUploadTitle => 'Upload to Cloud';

  @override
  String get functionLibraryUploadSuccess => 'Upload successful';

  @override
  String get functionLibraryUploadFailed => 'Upload failed';

  @override
  String get functionLibraryDownload => 'Download from Cloud';

  @override
  String get functionLibraryDownloadTitle => 'Download from Cloud';

  @override
  String get functionLibraryDownloadSuccess => 'Download successful';

  @override
  String get functionLibraryDownloadFailed => 'Download failed';

  @override
  String get functionLibraryCloudUrlHint => 'Enter cloud service URL';

  @override
  String get functionLibraryConfirm => 'Confirm';

  @override
  String get functionLibrarySyncStatus => 'Sync Status';

  @override
  String get functionLibrarySynced => 'Synced';

  @override
  String get functionLibraryLocalOnly => 'Local Only';

  @override
  String get functionLibraryCloudOnly => 'Cloud Only';

  @override
  String get functionLibraryStartNode => 'Start Page';

  @override
  String get functionLibraryEndNode => 'End Page';

  @override
  String get functionLibraryLastRun => 'Last Run';

  @override
  String get functionLibraryLastRunSuccess => 'Success';

  @override
  String get functionLibraryLastRunFailed => 'Failed';

  @override
  String get functionLibraryLastRunGoal => 'Task';

  @override
  String get functionLibraryNoDescription => 'No description';

  @override
  String functionLibrarySummaryOnPage(Object page) {
    return 'On \"$page\" page';
  }

  @override
  String functionLibrarySummaryFromTo(Object from, Object to) {
    return 'From \"$from\" to \"$to\"';
  }

  @override
  String functionLibrarySummaryFrom(Object from) {
    return 'Starting from \"$from\"';
  }

  @override
  String functionLibrarySummaryTo(Object to) {
    return 'Navigate to \"$to\"';
  }

  @override
  String functionLibrarySummarySteps(Object count) {
    return '$count steps';
  }

  @override
  String functionLibrarySummaryParams(Object count) {
    return 'requires $count params';
  }

  @override
  String get functionLibraryTest => 'Test';

  @override
  String functionLibraryTestNeedParams(Object params) {
    return 'Parameters needed: $params';
  }

  @override
  String get functionLibraryTestStarted => 'Test execution started';

  @override
  String get functionLibraryViewDetails => 'View Details';

  @override
  String get functionLibraryDetailCompileSurface => 'Compile Surface';

  @override
  String get functionLibraryDetailGraphAnchors => 'Graph Anchors';

  @override
  String get functionLibraryDetailRunUsage => 'Run Usage';

  @override
  String get functionLibraryDetailLifecycle => 'Lifecycle';

  @override
  String get functionLibraryDetailExamples => 'Parameter Examples';

  @override
  String get functionLibraryDetailDerivedFrom => 'Derived Raw Function';

  @override
  String get functionLibraryDetailRuns => 'Run Count';

  @override
  String get functionLibraryDetailSuccessFail => 'Success / Fail';

  @override
  String get functionLibraryDetailUpdatedAt => 'Updated At';

  @override
  String get functionLibraryDetailBundleBacking => 'Bundle Backing Asset';

  @override
  String get functionLibraryDetailActionCount => 'Action Count';

  @override
  String get functionLibraryDetailActionPreview => 'Action Preview';

  @override
  String get functionLibraryDetailNoActionPreview => 'No actions';

  @override
  String functionLibraryDetailStepIndex(String index) {
    return 'Step $index';
  }

  @override
  String get functionLibraryDetailBundleFunction => 'Bundle Function';

  @override
  String get functionLibraryDetailInternalBlocks => 'Internal Function Blocks';

  @override
  String get functionLibraryDetailNoBlocks =>
      'No internal function blocks are available';

  @override
  String get functionLibraryDetailNoBundle => 'No bundle function is available';

  @override
  String get functionLibraryDetailFunctionSchema => 'Function Schema';

  @override
  String get executionCompileHit => 'Reuse Skill';

  @override
  String executionCompileHitWithFunction(Object functionId) {
    return 'Reuse Skill · $functionId';
  }

  @override
  String get executionVlmExecution => 'VLM Execution';

  @override
  String get executionActionOpenApp => 'Open App';

  @override
  String get executionActionClick => 'Click';

  @override
  String get executionActionClickNode => 'Click Element';

  @override
  String get executionActionLongPress => 'Long Press';

  @override
  String get executionActionInputText => 'Input Text';

  @override
  String get executionActionSwipe => 'Swipe';

  @override
  String get executionActionScroll => 'Scroll';

  @override
  String get executionActionPressKey => 'Press Key';

  @override
  String get executionActionWait => 'Wait';

  @override
  String get executionActionFinished => 'Finished';

  @override
  String get executionActionCallFunction => 'Call Skill';

  @override
  String get executionActionDefault => 'Action';

  @override
  String executionStepLabel(Object count) {
    return '$count steps';
  }

  @override
  String get executionSuccess => 'Success';

  @override
  String get executionFailed => 'Failed';

  @override
  String get memorySaveAsSkillTitle => 'Save as Function';

  @override
  String get memorySaveAsSkillContent =>
      'Save this execution as a reusable function?\n\nYou can view and manage it in the Function Library.';

  @override
  String get memorySavingProgress => 'Saving function...';

  @override
  String memorySaveSuccess(String functionId) {
    return 'Saved as function: $functionId';
  }

  @override
  String get memorySaveSuccessSimple => 'Saved as Function';

  @override
  String get memorySaveSuccessHint =>
      'Function saved to local library.\nYou can view, edit or upgrade it in the Function Library.';

  @override
  String get memoryViewInLibrary => 'View Library';

  @override
  String get memorySaveCannotImport =>
      'This execution cannot be saved as a function';

  @override
  String get memorySaveFailed => 'Failed to save function';

  @override
  String memorySaveFailedWithMessage(String message) {
    return 'Failed to save function: $message';
  }

  @override
  String get memoryRunIdMissing => 'Execution record ID missing, cannot save';

  @override
  String get omniflowAssetSuccess => 'Success';

  @override
  String get omniflowAssetFailed => 'Failed';

  @override
  String get omniflowAssetRunning => 'Running';

  @override
  String get omniflowAssetUnknown => 'Unknown';

  @override
  String get omniflowAssetCompileHit => 'Reuse Function';

  @override
  String get omniflowAssetCompileMiss => 'VLM Execution';

  @override
  String omniflowAssetSteps(int count) {
    return '$count steps';
  }

  @override
  String get omniflowAssetId => 'ID';

  @override
  String get omniflowAssetPackage => 'Package';

  @override
  String get omniflowAssetSourceRuns => 'Source Runs';

  @override
  String get omniflowAssetLinkedFunction => 'Linked Function';

  @override
  String get omniflowAssetCopyId => 'Copy ID';

  @override
  String get omniflowAssetEdit => 'Edit';

  @override
  String get omniflowAssetMemory => 'Memory';

  @override
  String get omniflowAssetReplay => 'Replay';

  @override
  String get omniflowAssetDelete => 'Delete';

  @override
  String get omniflowAssetEnrich => 'Enrich';

  @override
  String get omniflowAssetUpload => 'Upload';

  @override
  String get omniflowAssetIdCopied => 'ID copied';

  @override
  String get omniflowAssetJsonCopied => 'JSON copied';

  @override
  String get omniflowAssetFunctionDetail => 'Function Details';

  @override
  String get omniflowAssetRunLogDetail => 'Run Log Details';

  @override
  String get omniflowAssetCopyJson => 'Copy JSON';

  @override
  String get omniflowAssetClose => 'Close';

  @override
  String get omniflowAssetStartPage => 'Start Page';

  @override
  String get omniflowAssetEndPage => 'End Page';

  @override
  String get omniflowAssetCreatedAt => 'Created At';

  @override
  String get omniflowAssetGoal => 'Goal';

  @override
  String get omniflowAssetStartedAt => 'Started At';

  @override
  String get omniflowAssetDoneReason => 'Done Reason';

  @override
  String get omniflowAssetView => 'View';

  @override
  String get omniflowAssetLoadFailed => 'Failed to load';

  @override
  String get omniflowAssetRunLogNotReady => 'Run log not yet persisted';

  @override
  String get omniflowAssetRunLogIndexFailed => 'Failed to read run log index';

  @override
  String get omniflowAssetReplayTitle => 'Replay Run Log';

  @override
  String get omniflowAssetReplayConfirm => 'Replay this execution?';

  @override
  String get omniflowAssetReplayProgress => 'Replaying...';

  @override
  String get omniflowAssetReplaySuccess => 'Replay successful';

  @override
  String omniflowAssetReplaySuccessWithId(String functionId) {
    return 'Replay successful: $functionId';
  }

  @override
  String get omniflowAssetReplayFailed => 'Replay failed';

  @override
  String omniflowAssetReplayFailedWithMessage(String message) {
    return 'Replay failed: $message';
  }

  @override
  String get omniflowAssetCancel => 'Cancel';

  @override
  String get omniflowAssetConfirm => 'Confirm';

  @override
  String omniflowAssetCopySuccess(String label) {
    return '$label copied';
  }

  @override
  String omniflowAssetCopyFailed(String label) {
    return 'Failed to copy $label';
  }

  @override
  String omniflowAssetEmpty(String label) {
    return '$label is empty';
  }

  @override
  String get omniflowAssetNoSteps => 'No steps to display';

  @override
  String get functionLibraryEnrich => 'Upgrade';

  @override
  String get functionLibraryEnrichTitle => 'Upgrade Function';

  @override
  String get functionLibraryEnrichConfirm =>
      'Use AI to enrich this function\'s semantic info?\n\nWill auto-generate: description, parameter slots, pre/post conditions, etc.';

  @override
  String get functionLibraryEnrichProgress => 'Upgrading function...';

  @override
  String get functionLibraryEnrichSuccess => 'Function upgraded successfully';

  @override
  String get functionLibraryEnrichFailed => 'Upgrade failed';

  @override
  String functionLibraryEnrichFailedWithMessage(String message) {
    return 'Upgrade failed: $message';
  }

  @override
  String get functionLibrarySplit => 'Split';

  @override
  String get functionLibrarySplitTitle => 'Split Function';

  @override
  String get functionLibrarySplitConfirm =>
      'Use AI to split this function into smaller ones?';

  @override
  String get functionLibrarySplitProgress => 'Splitting function...';

  @override
  String functionLibrarySplitSuccess(int count) {
    return 'Function split successfully, created $count new functions';
  }

  @override
  String get functionLibrarySplitFailed => 'Split failed';

  @override
  String get actionTypeOpenApp => 'Open App';

  @override
  String get actionTypeClick => 'Click';

  @override
  String get actionTypeClickNode => 'Click Node';

  @override
  String get actionTypeLongPress => 'Long Press';

  @override
  String get actionTypeInputText => 'Input Text';

  @override
  String get actionTypeSwipe => 'Swipe';

  @override
  String get actionTypePressKey => 'Press Key';

  @override
  String get actionTypeWait => 'Wait';

  @override
  String get actionTypeFinished => 'Finished';

  @override
  String get actionTypeCallFunction => 'Call Function';

  @override
  String get actionTypeDefault => 'Action';

  @override
  String get omniflowProviderUpdate => 'Provider Update';

  @override
  String get omniflowConnectionMode => 'Connection Mode';

  @override
  String get omniflowConnectionModeBridge => 'Bridge Connection';

  @override
  String get omniflowConnectionModeEmbedded => 'Local Embedded';

  @override
  String get omniflowProviderVersion => 'Provider Version';

  @override
  String get omniflowProviderPort => 'Provider Port';

  @override
  String get omniflowProviderStore => 'Provider Store';

  @override
  String get omniflowCurrentVersion => 'Current Version';

  @override
  String get omniflowLatestVersion => 'Latest Version';

  @override
  String get omniflowUpdateAvailable => 'Update Available';

  @override
  String get omniflowUpdateNotSupported =>
      'Update available, but host Provider does not support auto-update (use git pull)';

  @override
  String get omniflowStartProviderFirst =>
      'Please start Provider first to check for updates';

  @override
  String get omniflowCheckUpdate => 'Check Update';

  @override
  String get omniflowCheckingUpdate => 'Checking...';

  @override
  String get omniflowApplyUpdate => 'Update Now';

  @override
  String get omniflowApplyingUpdate => 'Updating...';

  @override
  String get omniflowCheckUpdateFailed => 'Check update failed';

  @override
  String omniflowNewVersionFound(String version) {
    return 'New version found: $version';
  }

  @override
  String get omniflowPackageNotInstalled => 'Not installed on this device';

  @override
  String get omniflowAlreadyLatest => 'Already up to date';

  @override
  String omniflowUpdateSuccess(String version) {
    return 'Updated current device to $version';
  }

  @override
  String get omniflowUpdateBridgeModeHint =>
      'Current connection remains Bridge; only the device package was updated.';

  @override
  String get omniflowUpdateRestartRequired =>
      'Device package updated; restart the local embedded Provider to apply it.';

  @override
  String get omniflowUpdateFailed => 'Update failed';

  @override
  String get executionRouteMemorized => '⚡ Memorized';

  @override
  String get executionRouteAiPlanning => '🤔 AI Planning';

  @override
  String get runLogTimelineTitle => 'Execution Steps';

  @override
  String get runLogTimelineViewSteps => 'View Steps';

  @override
  String runLogTimelineStepCount(int count) {
    return '$count steps';
  }

  @override
  String get runLogTimelineLoadFailed => 'Failed to load steps';

  @override
  String get runLogTimelineEmpty => 'No step data';

  @override
  String get runLogTimelineUnknown => 'Unknown';

  @override
  String get chatInputCommandTooltip => 'Command';

  @override
  String get workbenchTitle => 'Workbench';

  @override
  String get workbenchWorkspaceTitle => 'Workspace';

  @override
  String get workbenchWorkspaceOpenWorkbench => 'Open Workbench';

  @override
  String get workbenchWorkspaceOpenProjectConsole => 'Open Project console';

  @override
  String get workbenchWorkspaceWorkMode => 'Work';

  @override
  String get workbenchWorkspaceProjectMode => 'Project';

  @override
  String get workbenchWorkspaceProjectFrontendsTitle => 'Project window';

  @override
  String get workbenchWorkspaceProjectFrontendsSubtitle =>
      'After Project mode is enabled, this area hosts the active Project\'s OOB-native frontend like an embedded child window.';

  @override
  String get workbenchWorkspaceProjectFrontendsEmpty =>
      'No Project frontends yet. Describe the project in chat and the Agent can create a visible Workbench Project.';

  @override
  String get workbenchWorkspaceProjectOpenFailed =>
      'Failed to open Project frontend';

  @override
  String get workbenchWorkspaceProjectUnsupportedDisplay =>
      'This Display is not supported in the embedded Project window yet. Open it as a full page from the top-right action.';

  @override
  String get workbenchWorkspaceGuideTooltip => 'View Project Workbench guide';

  @override
  String get workbenchWorkspaceGuideClose => 'Close guide';

  @override
  String get workbenchWorkspaceGuideTitle => 'How Project Workbench works';

  @override
  String get workbenchWorkspaceGuideIntro =>
      'Project mode is not another chat page. It is the native OOB workbench for vibe projects, linking generated frontends, backend APIs, Workspace files, skills, and persistent data into one editable unit.';

  @override
  String get workbenchWorkspaceGuideFlowTitle => 'Interaction flow';

  @override
  String get workbenchWorkspaceGuideFlowPrompt =>
      'Prompt + Skill split the requirement';

  @override
  String get workbenchWorkspaceGuideFlowProject =>
      'Project registry records the container';

  @override
  String get workbenchWorkspaceGuideFlowApi =>
      'Project API Registry registers business backend APIs';

  @override
  String get workbenchWorkspaceGuideFlowDisplay =>
      'Flutter Display renders the business frontend';

  @override
  String get workbenchWorkspaceGuideFlowPersist =>
      'data/ + logs/ persist AI and UI calls';

  @override
  String get workbenchWorkspaceGuideProjectTitle => 'What a Project binds';

  @override
  String get workbenchWorkspaceGuideProjectBody =>
      'A Project binds the goal, skill, Workspace files, Display list, business APIs, data, and logs. It is not an MCP tool list and it is not a loose generated HTML page.';

  @override
  String get workbenchWorkspaceGuideFrontendTitle =>
      'How the frontend is shown';

  @override
  String get workbenchWorkspaceGuideFrontendBody =>
      'The generated frontend is an OOB-native Flutter Display. After Workspace switches to Project mode, it no longer shows a large manager list; it hosts the active Project home like an embedded browser window. One Project can own multiple Displays, selected from the small menu.';

  @override
  String get workbenchWorkspaceGuideBackendTitle => 'How the backend is called';

  @override
  String get workbenchWorkspaceGuideBackendBody =>
      'Backend capabilities are registered in the Project API Registry, such as todo.add and todo.finish. The AI layer and frontend buttons call the same workbenchApiCall(projectId, apiId, inputs) path. Project create, export, and delete remain control APIs and do not mix into business APIs.';

  @override
  String get workbenchWorkspaceGuideDataTitle => 'How data flows';

  @override
  String get workbenchWorkspaceGuideDataBody =>
      'A call goes through Flutter -> MethodChannel -> OOB native executor, then writes to the Project data/ and logs/. Frontend refreshes, AI execution stats, and state after restart all come from this persisted data.';

  @override
  String get workbenchWorkspaceGuideVibeTitle => 'How to keep editing';

  @override
  String get workbenchWorkspaceGuideVibeBody =>
      'To continue vibe coding, go back to the main Home input and describe the change. The Workbench Skill decides whether to create a new Project, extend backend APIs, adjust Displays, or hot update the current Project.';

  @override
  String get workbenchWorkspaceGuideExtendTitle => 'Extending backend tools';

  @override
  String get workbenchWorkspaceGuideExtendBody =>
      'When adding a capability, define apiId, input/output schemas, executorKind, persistence files, and the frontend trigger first. Then register the Project API through Workbench interfaces; do not hand-edit registry files.';

  @override
  String workbenchWorkspaceProjectApiStats(int apiCount, int executionCount) {
    return '$apiCount APIs · $executionCount executions';
  }

  @override
  String get workbenchSubtitle =>
      'A native OOB project example that registers Project APIs, persists state, and renders inside the Workbench.';

  @override
  String get workbenchVibeSubtitle =>
      'Prompt-built native frontend, project APIs, and workspace files stay connected inside OOB.';

  @override
  String get workbenchGeneratedTodoSubtitle =>
      'Todo Log (TODO) is one business frontend in this Project. It is separate from the Project control surface and calls the registered APIs directly.';

  @override
  String get workbenchMockProjectName => 'Todo Log Mock';

  @override
  String get workbenchTemplateProjectName => 'Todo Log Workbench';

  @override
  String get workbenchNativeUi => 'OOB native UI';

  @override
  String get workbenchProjectSection => 'Project';

  @override
  String get workbenchProjectIdLabel => 'Project ID';

  @override
  String get workbenchTemplateIdLabel => 'Template ID';

  @override
  String get workbenchRouteLabel => 'Route';

  @override
  String get workbenchSpacePathLabel => 'Space path';

  @override
  String get workbenchPageIdsLabel => 'Pages';

  @override
  String get workbenchDevelopmentMode => 'Development mode';

  @override
  String get workbenchProjectRegistryPath => 'Project registry';

  @override
  String get workbenchApiRegistryPath => 'API registry';

  @override
  String get workbenchProjectFilePath => 'Project file';

  @override
  String get workbenchDataFilePath => 'Data file';

  @override
  String get workbenchLogFilePath => 'API log';

  @override
  String get workbenchBackendTools => 'Backend tools';

  @override
  String get workbenchFrontendBinding => 'Frontend binding';

  @override
  String get workbenchTodoLog => 'Todo log';

  @override
  String get workbenchCallApi => 'Call API';

  @override
  String get workbenchGeneratedFrontend => 'Generated frontend';

  @override
  String get workbenchGeneratedFrontendSubtitle =>
      'Open the OOB-native preview that a prompt-generated page should target. It uses the same Project APIs and persistent data as the AI layer.';

  @override
  String get workbenchOpenGeneratedFrontend => 'Open generated frontend';

  @override
  String get workbenchPreviewClose => 'Close preview';

  @override
  String get workbenchToolList => 'Project APIs';

  @override
  String get workbenchProjectControlSubtitle =>
      'Only registered business APIs are shown here. Project create/open stays in the OOB Workbench control surface.';

  @override
  String get workbenchOpenWorkspace => 'Open Workspace';

  @override
  String get workbenchApiEmpty => 'No registered Project APIs';

  @override
  String get workbenchToolListDefaultTodo =>
      'Project API clicked the shared backend';

  @override
  String workbenchToolExecutionCount(int count) {
    return 'Executed $count';
  }

  @override
  String get workbenchToolAddTodoTitle => 'Add todo';

  @override
  String get workbenchToolAddTodoDesc =>
      'Creates a todo item through the registered native Project API.';

  @override
  String get workbenchToolFinishTodoTitle => 'Archive todo';

  @override
  String get workbenchToolFinishTodoDesc =>
      'Archives a todo item through the registered native Project API.';

  @override
  String get workbenchFlowAddTodo => 'Page input -> todo.add -> todo list';

  @override
  String get workbenchFlowFinishTodo =>
      'Finish button -> todo.finish -> completed list';

  @override
  String get workbenchAddTodoHint => 'Add a todo';

  @override
  String get workbenchAddTodo => 'Add todo';

  @override
  String get workbenchTodoEmpty => 'No todo yet';

  @override
  String get workbenchTodoOpen => 'Open';

  @override
  String get workbenchTodoFinished => 'Archived';

  @override
  String get workbenchFinishTodo => 'Archive todo';

  @override
  String get workbenchTodoAdded => 'Todo added';

  @override
  String get workbenchTodoFinishedToast => 'Todo archived';

  @override
  String get workbenchTodoInputRequired => 'Enter a todo first';

  @override
  String get workbenchSchemaDefaultEntity => 'Item';

  @override
  String workbenchSchemaCreateTitle(String entity) {
    return 'Create $entity';
  }

  @override
  String workbenchSchemaInputHint(String entity) {
    return 'Enter $entity name';
  }

  @override
  String workbenchSchemaItemsTitle(String entity) {
    return '$entity list';
  }

  @override
  String workbenchSchemaEmpty(String entity) {
    return 'No $entity yet';
  }

  @override
  String get workbenchSchemaActive => 'Active';

  @override
  String get workbenchSchemaArchived => 'Archived';

  @override
  String get workbenchSchemaArchiveAction => 'Archive';

  @override
  String get workbenchSchemaMissingCreateApi =>
      'This Project has no create API';

  @override
  String get workbenchSchemaMissingArchiveApi =>
      'This Project has no archive API';

  @override
  String workbenchSchemaInputRequired(String entity) {
    return 'Enter $entity first';
  }

  @override
  String workbenchSchemaItemCreated(String entity) {
    return '$entity created';
  }

  @override
  String workbenchSchemaItemArchived(String entity) {
    return '$entity archived';
  }

  @override
  String get workbenchNoOpenTodo => 'No open todo to finish';

  @override
  String get workbenchLoadFailed => 'Load failed';

  @override
  String get workbenchUnknownTool => 'Workbench tool failed';

  @override
  String get workbenchStatusOpen => 'Waiting';

  @override
  String get workbenchStatusFinished => 'Archived';

  @override
  String get workbenchAssistantName => 'Xiaowan';

  @override
  String get workbenchAssistantTooltip => 'Open Xiaowan';

  @override
  String get workbenchAssistantPromptHint => 'Describe the change to apply now';

  @override
  String get workbenchAssistantSend => 'Hot update current Project';

  @override
  String get workbenchAssistantApplied => 'Project hot updated';

  @override
  String get workbenchAssistantPromptRequired => 'Enter the change first';

  @override
  String get workbenchAssistantNoProject => 'Select a Project first';

  @override
  String get workbenchAssistantHotUpdateFailed => 'Project hot update failed';

  @override
  String get workbenchProjectModeTitle => 'Project Workbench';

  @override
  String get workbenchFlutterDisplay => 'Flutter Display';

  @override
  String get workbenchProjectSwitcher => 'Switch Project';

  @override
  String get workbenchProjectGenerateTitle => 'Project container';

  @override
  String get workbenchProjectGenerateSubtitle =>
      'This page only selects and opens Project containers. Create, edit, and hot update from the Home input with the active Project toolbox.';

  @override
  String get workbenchProjectPromptHint =>
      'Return Home to describe the Project';

  @override
  String get workbenchProjectDefaultPrompt =>
      'I want to create a simple todolist management system that can add todos and archive todos';

  @override
  String get workbenchProjectGenerateButton => 'Continue from Home';

  @override
  String get workbenchInputProjectTooltip => 'Open Project Workbench';

  @override
  String get workbenchGeneratedTodoProjectName => 'Todo List Workbench';

  @override
  String get workbenchPromptSeedAddTodo => 'Verify adding a todo';

  @override
  String get workbenchPromptSeedArchiveTodo => 'Verify archiving a todo';

  @override
  String get workbenchProjectPlanTitle => 'Decomposition plan';

  @override
  String get workbenchProjectPlanProject =>
      'Create Project registry and editable Workspace';

  @override
  String get workbenchProjectPlanFrontend =>
      'Generate an OOB-native Flutter frontend';

  @override
  String get workbenchProjectPlanApi =>
      'Register business APIs shared by AI and UI';

  @override
  String get workbenchProjectPlanData => 'Persist data and API call logs';

  @override
  String get workbenchUseMode => 'Use mode';

  @override
  String get workbenchDebugMode => 'Debug mode';

  @override
  String get workbenchDisplaysTitle => 'Frontend Displays';

  @override
  String workbenchDisplayCount(int count) {
    return '$count displays';
  }

  @override
  String get workbenchUnnamedDisplay => 'Unnamed display';

  @override
  String get workbenchOpenDisplay => 'Open this frontend';

  @override
  String get workbenchDebugDisplay => 'Debug this frontend';

  @override
  String get workbenchProjectCurrentTitle => 'Project use surface';

  @override
  String get workbenchProjectCurrentSubtitle =>
      'Default opens return to Home; debug opens return to the Workbench. Hot updates run from the Home input with the active Project.';

  @override
  String get workbenchProjectModeCreateTitle => 'Vibe project entry';

  @override
  String get workbenchProjectModeSubtitle =>
      'Select a Project as the Agent workspace, open its Flutter displays, inspect API execution counts, and manage Workspace, export, and delete. Creation and editing return to the Home input so the Agent can run the Workbench tools.';

  @override
  String get workbenchProjectModeCreateButton => 'Create from Home';

  @override
  String get workbenchProjectCreateFromHome =>
      'Return to the Home input and say create project or describe the page you want.';

  @override
  String get workbenchProjectModeProjectsTitle => 'Current Project APIs';

  @override
  String workbenchProjectApiForProject(String projectName) {
    return '$projectName APIs';
  }

  @override
  String get workbenchProjectModeOpen => 'Open project';

  @override
  String get workbenchProjectModeEmpty => 'No Workbench projects yet';

  @override
  String get workbenchProjectModeLoadFailed => 'Project mode failed to load';

  @override
  String get workbenchProjectPromptRequired =>
      'Enter a Project requirement first';

  @override
  String get workbenchProjectGenerated => 'Project generated';

  @override
  String get workbenchDeleteProject => 'Delete Project';

  @override
  String get workbenchDeleteProjectTitle => 'Delete Project';

  @override
  String workbenchDeleteProjectMessage(String projectId) {
    return 'Delete $projectId? This removes its Project registry entry, business API registrations, and Workspace project files.';
  }

  @override
  String get workbenchDeleteProjectCancel => 'Cancel';

  @override
  String get workbenchDeleteProjectConfirm => 'Delete';

  @override
  String get workbenchDeleteProjectFailed => 'Project delete failed';

  @override
  String get workbenchProjectDeleted => 'Project deleted';

  @override
  String get workbenchProjectIdRequired => 'Enter a project ID';

  @override
  String get workbenchProjectCreated => 'Project created';

  @override
  String get workbenchProjectInfoTitle => 'Project info';

  @override
  String get workbenchProjectInfoDisplayTitle => 'Display route';

  @override
  String get workbenchProjectInfoSourceTitle => 'Source specs';

  @override
  String get workbenchProjectInfoSourceValue =>
      'README.md / frontend/page_spec.json / backend/api_spec.json';

  @override
  String get workbenchProjectInfoRuntimeTitle => 'Runtime state';

  @override
  String get workbenchProjectInfoRuntimeValue =>
      'data/todos.json / logs/api_calls.jsonl';

  @override
  String get workbenchDebugToolsTitle => 'Debug tools';

  @override
  String get workbenchDebugHotUpdate =>
      'Use floating Xiaowan to hot-update this Project';

  @override
  String get workbenchDebugHotUpdateHomeInput =>
      'Return to the Home input and describe the edit; the Agent will use the active Project toolbox for the hot update';

  @override
  String get workbenchDebugFloatingXiaowan =>
      'Floating Xiaowan can attach the current frontend context, select page information, and call workbench_project_hot_update to iterate this Project.';

  @override
  String get workbenchDebugVlmInput =>
      'VLM input can also attach the current Display, visible state, selected control, or screenshot summary as frontendContext for the Project Skill.';

  @override
  String workbenchDebugContextProject(String projectId) {
    return 'Project $projectId';
  }

  @override
  String workbenchDebugContextDisplay(String displayId) {
    return 'Display $displayId';
  }

  @override
  String workbenchDebugContextRoute(String route) {
    return 'Route $route';
  }

  @override
  String get workbenchDebugVlmTest => 'Run a VLM human-operation simulation';

  @override
  String get workbenchDebugComingSoon => 'Pending';

  @override
  String get workbenchExportProjectPackage => 'Export package';

  @override
  String get workbenchProjectExportFailed => 'Project export failed';

  @override
  String workbenchProjectExported(String packageName) {
    return 'Exported $packageName';
  }

  @override
  String workbenchProjectExportPath(String path) {
    return 'Export path: $path';
  }

  @override
  String get workbenchAndroidAssetsTitle => 'Android assets';

  @override
  String get workbenchAndroidSourceHint =>
      'Enter an APK or Android project path, for example /workspace/apps/demo.apk';

  @override
  String get workbenchAndroidIngestButton => 'Import into current Project';

  @override
  String get workbenchAndroidSourceRequired =>
      'Enter an Android app or project path';

  @override
  String get workbenchAndroidIngestFailed => 'Android asset import failed';

  @override
  String workbenchAndroidIngested(String name) {
    return 'Imported $name';
  }

  @override
  String get workbenchAndroidAssetsEmpty =>
      'No imported Android apps or projects yet';

  @override
  String get workbenchProjectActivateFailed => 'Project activation failed';

  @override
  String workbenchProjectActivated(String projectName) {
    return 'Activated $projectName';
  }

  @override
  String get workbenchActiveProject => 'Active';

  @override
  String get workbenchInactiveProject => 'Inactive';

  @override
  String get workbenchContinueInHome => 'Activate and return Home';

  @override
  String get workbenchProjectHelpTooltip => 'Project Workbench help';

  @override
  String get workbenchProjectHelpTitle => 'Project Workbench';

  @override
  String get workbenchProjectHelpHomeInput =>
      'Create, edit, and hot update Projects from the Home input.';

  @override
  String get workbenchProjectHelpSelect =>
      'Select a Project here to activate it as the Agent workspace.';

  @override
  String get workbenchProjectHelpDisplays =>
      'Each Project can own multiple Flutter Displays; open containers from here.';

  @override
  String get workbenchProjectHelpApis =>
      'Project APIs are the current Project business toolbox and stay separate from MCP tools.';

  @override
  String workbenchActiveProjectChip(String projectName) {
    return 'Project: $projectName';
  }

  @override
  String workbenchAndroidAssetCount(int count) {
    return '$count Android assets';
  }

  @override
  String workbenchTodoCount(int openCount, int finishedCount) {
    return '$openCount open / $finishedCount finished';
  }

  @override
  String workbenchSchemaItemCount(int activeCount, int archivedCount) {
    return '$activeCount active / $archivedCount archived';
  }

  @override
  String workbenchApiCount(int count) {
    return '$count APIs';
  }
}
