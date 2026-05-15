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
  String get settingsHabitualHandTitle => 'Dominant Hand';

  @override
  String get settingsHabitualHandSubtitle =>
      'Changes the swipe direction for chat history menus';

  @override
  String get settingsHabitualHandLeft => 'Left';

  @override
  String get settingsHabitualHandRight => 'Right';

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
  String get settingsOobFunctionAsToolTitle => 'OOB Functions as Tools';

  @override
  String get settingsOobFunctionAsToolSubtitle =>
      'Let the agent call saved OOB functions as built-in tools';

  @override
  String get settingsOobFunctionAsToolToggleFailed =>
      'Failed to update OOB function tool setting';

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
  String get memoryCommandsTitle => 'Commands';

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
  String memoryLongTermLoadFailed(Object error) {
    return 'Long-term memory load failed: $error';
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
  String get skillOfficial => 'Official';

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
  String get skillSyncOfficialTooltip => 'Install/update official skills';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return 'Official skills synced ($count)';
  }

  @override
  String get skillSyncOfficialFailed => 'Failed to sync official skills';

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
  String get aboutApkSourceOptionCnb => 'Cloudflare R2';

  @override
  String get aboutApkSourceOptionCnbDescription => 'Served by update worker';

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
      'Project mode is not another chat page. It is the native OOB workbench for vibe projects, linking generated frontends, Project Tools, Workspace files, skills, and persistent data into one editable unit.';

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
      'Project Tools register business capabilities';

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
      'A Project binds the goal, skill, Workspace files, Display list, Project Tools, data, and logs. It is not an MCP tool list and it is not a loose generated HTML page.';

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
      'Backend capabilities are registered as Project Tools, such as todo.add and todo.finish. The AI layer and frontend buttons call the same workbenchApiCall(projectId, toolId, inputs) path. Project create, export, and delete remain control APIs and do not mix into business tools.';

  @override
  String get workbenchWorkspaceGuideDataTitle => 'How data flows';

  @override
  String get workbenchWorkspaceGuideDataBody =>
      'A call goes through Flutter -> MethodChannel -> OOB native executor, then writes to the Project data/ and logs/. Frontend refreshes, AI execution stats, and state after restart all come from this persisted data.';

  @override
  String get workbenchWorkspaceGuideVibeTitle => 'How to keep editing';

  @override
  String get workbenchWorkspaceGuideVibeBody =>
      'To continue vibe coding, go back to the main Home input and describe the change. The Workbench Skill decides whether to create a new Project, extend Project Tools, adjust Displays, or hot update the current Project.';

  @override
  String get workbenchWorkspaceGuideExtendTitle => 'Extending backend tools';

  @override
  String get workbenchWorkspaceGuideExtendBody =>
      'When adding a capability, define toolId, input/output schemas, executorKind, persistence files, and the frontend trigger first. Then register the Project Tool through Workbench interfaces; do not hand-edit registry files.';

  @override
  String workbenchWorkspaceProjectApiStats(int apiCount, int executionCount) {
    return '$apiCount tools · $executionCount executions';
  }

  @override
  String get workbenchSubtitle =>
      'A native OOB project example that registers Project Tools, persists state, and renders inside the Workbench.';

  @override
  String get workbenchVibeSubtitle =>
      'Prompt-built native frontend, Project Tools, and workspace files stay connected inside OOB.';

  @override
  String get workbenchProjectDisplay => 'Project Display';

  @override
  String get workbenchProjectSection => 'Project';

  @override
  String get workbenchProjectIdLabel => 'Project ID';

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
  String get workbenchApiRegistryPath => 'Tool registry';

  @override
  String get workbenchProjectFilePath => 'Project file';

  @override
  String get workbenchDataFilePath => 'Data file';

  @override
  String get workbenchLogFilePath => 'Tool log';

  @override
  String get workbenchBackendTools => 'Backend tools';

  @override
  String get workbenchFrontendBinding => 'Frontend binding';

  @override
  String get workbenchCallApi => 'Call tool';

  @override
  String get workbenchGeneratedFrontend => 'Generated frontend';

  @override
  String get workbenchGeneratedFrontendSubtitle =>
      'Open the OOB-native preview that a prompt-generated page should target. It uses the same Project Tools and persistent data as the AI layer.';

  @override
  String get workbenchOpenGeneratedFrontend => 'Open generated frontend';

  @override
  String get workbenchPreviewClose => 'Close preview';

  @override
  String get workbenchToolList => 'Project Tools';

  @override
  String get workbenchProjectControlSubtitle =>
      'Only registered business tools are shown here. Project create/open stays in the OOB Workbench control surface.';

  @override
  String get workbenchOpenWorkspace => 'Open Workspace';

  @override
  String get workbenchApiEmpty => 'No tools yet';

  @override
  String get workbenchToolListDefaultTodo =>
      'Project Tool clicked the shared backend';

  @override
  String workbenchToolExecutionCount(int count) {
    return 'Executed $count';
  }

  @override
  String get workbenchProjectDefaultEntity => 'Item';

  @override
  String workbenchProjectCreateTitle(String entity) {
    return 'Create $entity';
  }

  @override
  String workbenchProjectInputHint(String entity) {
    return 'Enter $entity name';
  }

  @override
  String workbenchProjectItemsTitle(String entity) {
    return '$entity list';
  }

  @override
  String workbenchProjectEmpty(String entity) {
    return 'No $entity yet';
  }

  @override
  String get workbenchProjectActiveItems => 'Active';

  @override
  String get workbenchProjectArchivedItems => 'Archived';

  @override
  String get workbenchProjectEditAction => 'Edit';

  @override
  String get workbenchProjectEditTitle => 'Edit item';

  @override
  String get workbenchProjectArchiveAction => 'Archive';

  @override
  String get workbenchProjectMissingCreateApi =>
      'This Project has no create tool';

  @override
  String get workbenchProjectMissingUpdateApi =>
      'This Project has no edit tool';

  @override
  String get workbenchProjectMissingArchiveApi =>
      'This Project has no archive tool';

  @override
  String workbenchProjectInputRequired(String entity) {
    return 'Enter $entity first';
  }

  @override
  String workbenchProjectItemCreated(String entity) {
    return '$entity created';
  }

  @override
  String workbenchProjectItemUpdated(String entity) {
    return '$entity saved';
  }

  @override
  String workbenchProjectItemArchived(String entity) {
    return '$entity archived';
  }

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
  String get workbenchProjectModeTitle => 'Projects';

  @override
  String get workbenchFlutterDisplay => 'Flutter Display';

  @override
  String get workbenchFlutterEvalTitle => 'Flutter runtime';

  @override
  String get workbenchFlutterEvalNoSource =>
      'This Project has no runnable Flutter source yet. Define OobProjectWidget in frontend/flutter/lib/main.dart.';

  @override
  String get workbenchFlutterEvalCompileFailed =>
      'Flutter source is not runnable yet. Return to the input box and ask Xiaowan to fix this page.';

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
      'Register Project Tools shared by AI and UI';

  @override
  String get workbenchProjectPlanData => 'Persist data and tool call logs';

  @override
  String get workbenchUseMode => 'Use mode';

  @override
  String get workbenchDebugMode => 'Debug mode';

  @override
  String get workbenchDisplaysTitle => 'Pages';

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
      'This page only shows projects and the active item.';

  @override
  String get workbenchProjectActiveTitle => 'Active';

  @override
  String get workbenchProjectActiveEmpty => 'No active Project yet';

  @override
  String get workbenchProjectListTitle => 'Projects';

  @override
  String get workbenchProjectDetailTitle => 'Project';

  @override
  String get workbenchProjectModeCreateButton => 'Create from Home';

  @override
  String get workbenchProjectCreateFromHome =>
      'Return to the Home input and say create project or describe the page you want.';

  @override
  String get workbenchProjectModeProjectsTitle => 'Current tools';

  @override
  String get workbenchProjectApiForProject => 'Tools';

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
    return 'Delete $projectId? This removes its Project registry entry, business tool registrations, and Workspace project files.';
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
  String get workbenchAnnotationTitle => 'Annotation canvas';

  @override
  String get workbenchAnnotationDrawMode => 'Draw';

  @override
  String get workbenchAnnotationBrowseMode => 'Browse page';

  @override
  String get workbenchAnnotationUndo => 'Undo';

  @override
  String get workbenchAnnotationClear => 'Clear';

  @override
  String get workbenchAnnotationApply => 'Apply annotation';

  @override
  String get workbenchAnnotationApplying => 'Applying';

  @override
  String get workbenchAnnotationPromptHint =>
      'Add edit notes, for example: turn this into the primary button';

  @override
  String get workbenchAnnotationNoStrokes => 'Draw the area to edit first';

  @override
  String get workbenchAnnotationNoShape => 'No marks';

  @override
  String workbenchAnnotationShapeCount(int count) {
    return '$count strokes marked';
  }

  @override
  String get workbenchAnnotationDefaultPrompt =>
      'Adjust the current Project frontend according to the canvas annotation.';

  @override
  String get workbenchAnnotationHotUpdateSuccess =>
      'Applied the annotation to the Project';

  @override
  String get workbenchAnnotationHotUpdateFailed =>
      'Annotation hot update failed';

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
  String get workbenchAndroidAssetsTitle => 'Apps';

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
  String get workbenchProjectDeactivateFailed => 'Project deactivation failed';

  @override
  String get workbenchProjectDeactivated => 'Project deactivated';

  @override
  String get workbenchActivateProject => 'Activate Project';

  @override
  String get workbenchDeactivateProject => 'Deactivate';

  @override
  String get workbenchEditProjectLabels => 'Edit labels';

  @override
  String get workbenchProjectNameLabel => 'Name';

  @override
  String get workbenchProjectShortNameLabel => 'Short name';

  @override
  String get workbenchSaveProjectLabels => 'Save';

  @override
  String get workbenchProjectNameRequired => 'Enter a name';

  @override
  String get workbenchProjectLabelsUpdated => 'Saved';

  @override
  String get workbenchProjectLabelsUpdateFailed => 'Save failed';

  @override
  String get workbenchProjectMoreActions => 'More actions';

  @override
  String get workbenchActiveProject => 'Active';

  @override
  String get workbenchInactiveProject => 'Inactive';

  @override
  String get workbenchContinueInHome => 'Activate Project';

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
      'Project Tools are the current Project business toolbox and stay separate from MCP tools.';

  @override
  String workbenchActiveProjectChip(String projectName) {
    return 'Project: $projectName';
  }

  @override
  String workbenchProjectSummaryGeneric(String entityName) {
    return 'Manage $entityName records with saved status and quick actions.';
  }

  @override
  String workbenchAndroidAssetCount(int count) {
    return '$count Android assets';
  }

  @override
  String workbenchProjectItemCount(int activeCount, int archivedCount) {
    return '$activeCount active / $archivedCount archived';
  }

  @override
  String workbenchApiCount(int count) {
    return '$count tools';
  }

  @override
  String get workbenchPhilosophyBadge => 'What is Workbench?';

  @override
  String get workbenchPhilosophyClose => 'Close';

  @override
  String get workbenchPhilosophyTitle => 'AI Product Display Workbench';

  @override
  String get workbenchPhilosophyTagline =>
      'Turn AI results into interfaces users can see, tap, and keep changing';

  @override
  String get workbenchPhilosophySubtitle =>
      'Workbench is not a template generator. It is the display and runtime layer for AI products. Agent reports, data, state, and actions land in a Project, render through HTML, Markdown, or Flutter, and connect to phone capabilities and persisted data through Project APIs.';

  @override
  String get workbenchPhilosophyPillarsTitle => 'Current loop';

  @override
  String get workbenchPhilosophyComposable => 'Display';

  @override
  String get workbenchPhilosophyComposableDesc =>
      'HTML / Markdown / Flutter are Project Displays for presenting AI output';

  @override
  String get workbenchPhilosophyAIDriven => 'Interaction';

  @override
  String get workbenchPhilosophyAIDrivenDesc =>
      'User clicks, form input, and selections call Project APIs to trigger the next Agent or tool step';

  @override
  String get workbenchPhilosophyMobileNative => 'Capabilities';

  @override
  String get workbenchPhilosophyMobileNativeDesc =>
      'Screen control, reading UI, files, and scripts go through OOB native capabilities only when needed';

  @override
  String get workbenchPhilosophyStrengthsTitle => 'Three essentials';

  @override
  String get workbenchPhilosophyBackendTitle => 'Project API';

  @override
  String get workbenchPhilosophyBackendDesc =>
      'Whitelisted tools, persisted data, run logs, and phone capabilities are mounted on the Project';

  @override
  String get workbenchPhilosophyFrontendTitle => 'Display';

  @override
  String get workbenchPhilosophyFrontendDesc =>
      'Normal interactive UI defaults to HTML; reports use Markdown / HTML; Flutter stays as the container and constrained supplement';

  @override
  String get workbenchPhilosophyRuntimeTitle => 'Hot update';

  @override
  String get workbenchPhilosophyRuntimeDesc =>
      'After one sentence or one annotated selection, AI edits only the necessary frontend files or APIs and the right side refreshes';

  @override
  String get workbenchPhilosophyHowToTitle => 'Flow';

  @override
  String get workbenchPhilosophyStep1Label => 'Generate';

  @override
  String get workbenchPhilosophyStep1Desc =>
      'The Agent creates a Project and writes APIs plus display files';

  @override
  String get workbenchPhilosophyStep2Label => 'View';

  @override
  String get workbenchPhilosophyStep2Desc =>
      'The right Workspace previews HTML / Markdown / Flutter directly';

  @override
  String get workbenchPhilosophyStep3Label => 'Update';

  @override
  String get workbenchPhilosophyStep3Desc =>
      'Use the floating input or annotation to request a Project hot update';

  @override
  String get workbenchPhilosophyActivateHint =>
      'After activation, the right Workspace shows the Project Display. Further input or annotations are passed as context to hot update.';

  @override
  String get sourceTextf9dfa89402 => 'Omnibot Floating Window';

  @override
  String get sourceTextea6631ac86 =>
      'Hide the desktop floating ball, half-screen input, and running capsule';

  @override
  String get sourceText60d33fd58f => 'Omnibot floating window enabled';

  @override
  String get sourceText9803e0f8d8 => 'Omnibot floating window disabled';

  @override
  String get sourceText8ed5fe74f6 => 'Failed to update floating window setting';

  @override
  String get sourceText2a4a4de806 => 'Manual';

  @override
  String get sourceText76c9741888 => 'Shizuku Permission';

  @override
  String get sourceText5e04ad1c9a =>
      'Running a command in the embedded Alpine terminal';

  @override
  String get sourceTextc0b7ed8600 =>
      'Executing a command in the embedded Alpine terminal';

  @override
  String get sourceText60cf09e22d => 'Updating terminal output';

  @override
  String get sourceText140c80c696 =>
      '🎉Hi, I\'m Omnibot. I can do many things, let me show you!';

  @override
  String get sourceText82347f1be8 => 'Hi, I\'m Omnibot';

  @override
  String get sourceText5167632783 => 'Your AI assistant, always ready';

  @override
  String get sourceText63a921a287 => 'No internet needed, completely free';

  @override
  String get sourceText112e197134 =>
      'Data stays entirely on your device and is never sent to any server. Conversations, preferences, and sensitive info always remain under your control.';

  @override
  String get sourceText8de8b69cc9 =>
      'Run the AI assistant without any network connection. Whether on a plane, in the subway, or in a remote area — it\'s always available.';

  @override
  String get sourceTexteac537b43e =>
      'No API costs or subscriptions. Once downloaded, use the model unlimited times with no hidden charges.';

  @override
  String get sourceTexte8b806ace2 =>
      'On-device models are smaller with lower quality than cloud models. Complex Agent tasks are not yet supported — best for everyday chat and Q&A.';

  @override
  String get sourceText7e1cc2fc3f => 'Shuffle';

  @override
  String get sourceText63e272f624 => 'Omnibot is thinking...';

  @override
  String get sourceTextd9f594509d => 'Summarizing';

  @override
  String get sourceText9384e034e5 => 'Summary';

  @override
  String get sourceText3e44b2a933 => 'Select all';

  @override
  String get sourceText4edd1d0087 => 'Copy';

  @override
  String get sourceTextb56d9ac6c5 => 'Confirm';

  @override
  String get sourceTextf526c89937 => 'OK';

  @override
  String get sourceText4d0b3bb4e9 => 'Please wait...';

  @override
  String get sourceTextee5037d25d => 'Save & Send';

  @override
  String get sourceTextbe15d6f28c => 'No model set';

  @override
  String get sourceText01047404ef => 'New version available';

  @override
  String get sourceText1722589489 => 'Open terminal';

  @override
  String get sourceText649fc10b46 => 'Manage terminal environment variables';

  @override
  String get sourceTextd8f03e50ea => 'Open browser for current session';

  @override
  String get sourceTextc1c986937d => 'No browser session available';

  @override
  String get sourceText31b7c8d175 => 'Chat Only';

  @override
  String get sourceText7cda072d45 => 'Normal';

  @override
  String get sourceText17e83cc25e => 'Today';

  @override
  String get sourceText59c4fcb09e => 'Yesterday';

  @override
  String get sourceText1f425b6bf0 => 'Executing';

  @override
  String get sourceText6c189aad4d => 'Succeeded';

  @override
  String get sourceText9746cfc7d2 => 'Failed';

  @override
  String get sourceTextd0de773436 => 'Pending';

  @override
  String get sourceText2029839d84 => 'Summary';

  @override
  String get sourceText6c2b60f0ee => 'Image Recognition';

  @override
  String get sourceTexte9649f84f9 => 'Unknown type';

  @override
  String get sourceText756eae0324 => 'Replying...';

  @override
  String get sourceText292eea5849 => 'Never';

  @override
  String get sourceText08d65bdbc3 => 'Daily';

  @override
  String get sourceTexta93b55d8bf => 'Weekly';

  @override
  String get sourceText24aedc3608 => 'Monthly';

  @override
  String get sourceText4a9ee561f9 => 'Yearly';

  @override
  String get sourceText89b4aa6364 => 'Time';

  @override
  String get sourceTextb6fed9af83 => 'Date';

  @override
  String get sourceText6e708ba759 => 'Repeat';

  @override
  String get sourceTextc1cb3fc29f => 'Task options';

  @override
  String get sourceText39797f7a92 => 'Please select a task';

  @override
  String get sourceTexte03304491a => 'Please select a task to execute';

  @override
  String get sourceTextb4a7ea5533 => 'Please select an application';

  @override
  String get sourceText1354374f76 => 'Expired';

  @override
  String get sourceText36d2d01f31 => 'Starting soon';

  @override
  String get sourceText13794e1f43 => 'OK, I\'ll help you complete it';

  @override
  String get sourceTextbaa298fbe1 => 'User action';

  @override
  String get sourceText86e8d12a79 => 'Deleted';

  @override
  String get sourceText9abb465039 => 'Modify failed';

  @override
  String get sourceTextf8913eb433 => 'Modified successfully';

  @override
  String get sourceText65fdeb927b => 'Desktop';

  @override
  String get sourceText322eceb785 => 'Local Memory';

  @override
  String get sourceTextf90d5c751e => 'Cloud Memory';

  @override
  String get sourceText7e68eb622d => 'Saved successfully';

  @override
  String get sourceText6a6b660ba8 => 'Edit your message';

  @override
  String get sourceTextfcbd093292 => 'Create';

  @override
  String get sourceText8200c3d50b => 'Untitled conversation';

  @override
  String get sourceText229127ec8d => 'Collapse all dates';

  @override
  String get sourceTextbc51af6ffc => 'Expand all dates';

  @override
  String get sourceText72be511e05 => 'Last executed';

  @override
  String get sourceText818a1f7be3 => 'No summary content';

  @override
  String get sourceTextc76c74e809 => 'Failed to check for updates';

  @override
  String get sourceTextae4535ef13 => 'Already up to date';

  @override
  String get sourceText00f512b5e8 =>
      'Checking GitHub Release for the latest version';

  @override
  String get sourceText9afc832d99 => 'View new version';

  @override
  String get sourceTexta6df38586d => 'Check for updates';

  @override
  String get sourceText8ff0439ff9 => 'Thinking disabled';

  @override
  String get sourceTextd9d4d4e7dd => 'Request logs';

  @override
  String get sourceTexta8ce402665 => 'Runtime logs';

  @override
  String get sourceText4c685c0454 => 'User guide';

  @override
  String get sourceText5060421d15 => 'Overview';

  @override
  String get sourceText9f14a3f4dd => 'Recent logs';

  @override
  String get sourceTextb01090a29c =>
      'The latest 10 AI requests are shown in reverse chronological order.';

  @override
  String get sourceTextc740eb5be5 =>
      'Tap an entry to expand the request and response payloads.';

  @override
  String get sourceTextcb80eb03ea =>
      'The latest 200 error and crash logs are shown in reverse chronological order.';

  @override
  String get sourceText8334b58cfa =>
      'Entries with stack traces can be expanded for details.';

  @override
  String get sourceTextfe12b789bf => 'Export runtime logs';

  @override
  String get sourceText88f6dbf1a3 => 'All runtime logs copied';

  @override
  String get sourceText8b06115d35 => 'Failed to export runtime logs';

  @override
  String get sourceTextd6c8084d07 => 'Crash';

  @override
  String get sourceText367ff5ddd2 => 'Total';

  @override
  String get sourceText71bd34d484 => 'Latest entry';

  @override
  String get sourceText41654e0268 => 'Basic info';

  @override
  String get sourceText7364999103 => 'Payloads';

  @override
  String get sourceTextd70d425039 => 'Saving...';

  @override
  String get sourceTextdbb4430dc0 => 'No file selected';

  @override
  String get sourceText1e620e20a1 => 'Remote URL';

  @override
  String get sourceTextdde21b2cec => 'Background run permission';

  @override
  String get sourceText135f1636e4 => 'Installed apps access';

  @override
  String get sourceTextf80103fee9 => 'Accessibility permission';

  @override
  String get sourceTextd78cde076b => 'Enabled';

  @override
  String get sourceText13ec170881 => 'Enable';

  @override
  String get sourceText291952a2ab => 'Clear cache';

  @override
  String get sourceText3d0c8b9d9f =>
      'Help Omnibot better understand your preferences during companion mode';

  @override
  String get sourceText86890292b6 =>
      'Allow Omnibot to stay active on screen and accompany you anytime';

  @override
  String get sourceTexta86909c7ea =>
      'Let Omnibot know what it can help you with';

  @override
  String get sourceText56735a4ab7 =>
      'Omnibot needs permission to perform actions when executing tasks';

  @override
  String get sourceText99ad612dd1 => 'Set up permissions';

  @override
  String get sourceTextaef926661d => 'You can revoke these permissions anytime';

  @override
  String get sourceText02a75489b2 =>
      'Review and configure Accessibility, Overlay, Shizuku, and related permissions';

  @override
  String get sourceText75b40989f3 => 'Checking permissions...';

  @override
  String get sourceText2599599947 => 'Continue task';

  @override
  String get sourceText14411ce362 => 'Continue requires only';

  @override
  String get sourceTextf739c7d4a8 => 'Termux terminal capability';

  @override
  String get sourceText98bd36febc =>
      'Optional: allow the Agent to run terminal commands via Termux';

  @override
  String get sourceText53e32830a5 => 'Optional';

  @override
  String get sourceTexte5d269502c => 'Let Omnibot walk you through one task!';

  @override
  String get sourceText1aca95f544 =>
      'Termux capability is optional; leaving it off will not affect basic automation';

  @override
  String get sourceText3bf179d8d0 => 'Unbound';

  @override
  String get sourceText2a30881946 => 'Clear binding';

  @override
  String get sourceTexta191935bc6 => 'Restore default';

  @override
  String get sourceText8988c04935 =>
      'After tapping the button on the right, you can search, collapse, and select models by Provider; voice tone and auto-play can be adjusted below.';

  @override
  String get sourceText2415f124bd => 'Auto-play after AI response';

  @override
  String get sourceTextc4301894a2 => 'Voice';

  @override
  String get sourceTextc0ae8ba446 =>
      'e.g. default_zh / mimo_default / default_en';

  @override
  String get sourceTexta4ce420c69 => 'Style';

  @override
  String get sourceText6614801dcd => 'Custom note';

  @override
  String get sourceText558a2f3fd0 =>
      'Additional style is not supported in singing mode';

  @override
  String get sourceTextfa12d9ef1b => 'e.g. softer, slower, podcast-like';

  @override
  String get sourceText2601f9e3cb => 'Collapse voice settings';

  @override
  String get sourceTextbc2c7387f0 => 'Expand voice settings';

  @override
  String get sourceText6a7d5cd91d => 'No matching models';

  @override
  String get sourceText7b0de927a6 => 'Search model ID';

  @override
  String get sourceTexte5463e3a94 =>
      'Please configure a provider in Model Providers first';

  @override
  String get sourceText13c9595745 => 'No selectable models for this provider';

  @override
  String get sourceText90bfe72640 => 'Entered chat-only mode';

  @override
  String get sourceText9c1153036d => 'Exited chat-only mode';

  @override
  String get sourceTextd1a19c24c7 => 'Search skill name or description';

  @override
  String get sourceTextd636ae3e01 => 'No matching skills found';

  @override
  String get sourceTexte4d8c16cd2 => 'Streaming';

  @override
  String get sourceText36e8d9631f => 'Non-streaming';

  @override
  String get sourceText0e84ef42ae => 'Request URL';

  @override
  String get sourceText4d150364fe => 'Request Method';

  @override
  String get sourceTexta38a81c9d5 => 'Error';

  @override
  String get sourceText0228e74add => 'Request JSON';

  @override
  String get sourceText9f062a0dac => 'Response JSON';

  @override
  String get sourceTexte2d53a6d3a => 'Retry';

  @override
  String get sourceText661b2db84d => 'Failed to load request logs';

  @override
  String get sourceTextfa604c3dba => 'No AI request logs yet';

  @override
  String get sourceTexta22889b61d => 'Failed to load runtime logs';

  @override
  String get sourceText71a159aa14 => 'No runtime logs yet';

  @override
  String get sourceText7b15e5e8e7 => 'Clear';

  @override
  String get sourceTextfb57d700b9 => 'AI Request';

  @override
  String get sourceText7a42fe12dc => 'conversations';

  @override
  String get sourceTextadf4707731 => 'day streak';

  @override
  String get sourceText0fe8227aa4 => 'No conversations';

  @override
  String get sourceText7a54a1229e => 'No token usage data yet';

  @override
  String get sourceTexte8666c377c => 'Local';

  @override
  String get sourceText565481c9be => 'Cloud';

  @override
  String get sourceText54c727b452 => 'No usage';

  @override
  String get sourceText7fe4999970 => 'Long-term memory is not ready';

  @override
  String get sourceTextb87a8a83f5 =>
      'After memory initialization, cross-session preferences and facts will appear here.';

  @override
  String get sourceTextb92a2068aa =>
      'Long-term memory is temporarily unavailable';

  @override
  String get sourceTextd3a2b13fc2 => 'Long-term memory is still empty';

  @override
  String get sourceTexte8c59faf6d =>
      'After the Agent writes long-term preferences, this section will gradually fill up.';

  @override
  String get sourceText495c0debaf => 'Add long-term memory';

  @override
  String get sourceText4398777297 => 'Refresh long-term memory';

  @override
  String get sourceText9e636642d6 => 'Just now';

  @override
  String get sourceTextedab852efe => 'Thinking complete';

  @override
  String get sourceText774d85ae0a => 'Thinking';

  @override
  String get sourceTexte4b6477e6e => 'Elapsed';

  @override
  String get sourceText15fc7643c5 => 'Preparing to execute task...';

  @override
  String get sourceTextd258a63cad => 'Cancel task';

  @override
  String get sourceText6df9b76521 => 'Task canceled';

  @override
  String get sourceText038d05ca8c => 'Stop tool';

  @override
  String get sourceText4078ac16b6 => 'Stopping tool';

  @override
  String get sourceTextcb1115d8c1 =>
      'Failed to stop tool call. Please try again later.';

  @override
  String get sourceTexteac987a597 => 'Unable to open Agent manager';

  @override
  String get sourceTexte3f4d6bd9d => 'Failed to hide floating capsule';

  @override
  String get sourceTextf6d7e0312c =>
      'Floating capsule hidden. You can re-enable it in Settings.';

  @override
  String get sourceText066af21f55 => 'Open';

  @override
  String get sourceText15197efe93 => 'Hide floating capsule';

  @override
  String get sourceText5d5815647c => 'Collapse';

  @override
  String get sourceText151eeabaf6 => 'Failed to stop';

  @override
  String get sourceText6ef1200428 => 'Running Agents';

  @override
  String get sourceText5bd8f4879e => 'No backend tasks running';

  @override
  String get sourceTextfaeb185030 => 'No Agents running';

  @override
  String get sourceText68685fc5c4 => 'Stop all';

  @override
  String get sourceTextf0b2cef7b0 => 'No running Agent backend tasks';

  @override
  String get sourceText65fc81e161 => 'Open';

  @override
  String get sourceText645fc8d22d => 'Stop this Agent';

  @override
  String get sourceText5e59efab1e => 'Agent backend idle. Tap to open manager.';

  @override
  String get sourceText0b961ab4d9 => 'Organizing plan';

  @override
  String get sourceText91796bb70a => 'Writing response';

  @override
  String get sourceText33fe6867a2 => 'Starting tool call';

  @override
  String get sourceText76a18aa532 => 'Tool running';

  @override
  String get sourceTextd333e5691f => 'Tool completed';

  @override
  String get sourceTextcc1f7be0b2 => 'Waiting for permission';

  @override
  String get sourceTextf7d01365f2 => 'Waiting for more information';

  @override
  String get sourceText9617084ded => 'Run failed';

  @override
  String get sourceText832451d2f4 => 'Finishing';

  @override
  String get sourceTextc6dc0ad888 => 'Agent backend task';

  @override
  String get sourceTextbdde1def59 => 'Waiting for model response';

  @override
  String get sourceText3d4d1075e7 => 'Tool call';

  @override
  String get sourceTextff06c243d7 => 'Timeout';

  @override
  String get sourceText44e681a374 => 'Interrupted';

  @override
  String get sourceText71757f8d79 => 'Browsing';

  @override
  String get sourceTextda3d2d1482 => 'Responding';

  @override
  String get sourceTextfcb979ef0b => 'Processing';

  @override
  String get sourceText7f55a26d7d => 'Terminal';

  @override
  String get sourceText88d650dd4f => 'Browser';

  @override
  String get sourceText81944e48a3 => 'Reminder';

  @override
  String get sourceText2ecbc11608 => 'Calendar';

  @override
  String get sourceText2a8ce33ff0 => 'Subtask';

  @override
  String get sourceTexta72ef18d9a => 'Tool';

  @override
  String get sourceText15ec50fe7d => '[Earlier records omitted]';

  @override
  String get sourceTexta5dda12242 => 'Waiting for OpenClaw processing';

  @override
  String get sourceText70c53b8ac3 => 'Configure Your AI Assistant';

  @override
  String get sourceTextf7f58b95a7 =>
      'Choose a way to start using the assistant';

  @override
  String get sourceText1670225703 => 'Cloud AI Service';

  @override
  String get sourceText90f71a54c8 =>
      'Connect to OpenAI, Anthropic, or compatible APIs';

  @override
  String get sourceTextb0253cd034 => 'Local Model';

  @override
  String get sourceText10691e242c =>
      'Run local AI on your device, offline and private';

  @override
  String get sourceText1fc1afc5c5 => 'Continue';

  @override
  String get sourceText184913c0f3 => 'Skip, configure later in Settings';

  @override
  String get sourceText1e797c0dac => 'Cloud AI Setup';

  @override
  String get sourceText79973caeef =>
      'Configure a cloud AI provider for more powerful models';

  @override
  String get sourceText993df7d096 => 'Protocol Type';

  @override
  String get sourceText530aafb12a => 'e.g., My OpenAI';

  @override
  String get sourceText10b7d8eccc => 'Test Connection';

  @override
  String get sourceTexteb06635875 => 'Connected';

  @override
  String get sourceText523e40a074 => 'found';

  @override
  String get sourceText674373aef1 => 'models';

  @override
  String get sourceText2c056f182f => 'Connection failed';

  @override
  String get sourceTextd15ae9ad81 => 'Run Local AI on Your Device';

  @override
  String get sourceText4bbe706f8d => 'Advantages';

  @override
  String get sourceText37dea6b39e => 'Privacy';

  @override
  String get sourceText6bcfca9d58 =>
      'Data stays entirely on your device, never sent to any server';

  @override
  String get sourceText270d12d95b => 'Offline';

  @override
  String get sourceText97fcfbb5dd =>
      'No internet needed, use AI assistant anytime';

  @override
  String get sourceTextdd70b93ad6 => 'Free';

  @override
  String get sourceText5d4061aac5 =>
      'No API costs or subscriptions, no usage limits';

  @override
  String get sourceText498c2879b0 => 'Limitations';

  @override
  String get sourceTexta597376852 => 'Limited Performance';

  @override
  String get sourceTextb4d10c670a =>
      'On-device models are smaller with limited quality vs. cloud models';

  @override
  String get sourceText390d11af9b => 'Limited Tasks';

  @override
  String get sourceText7b5c99ecb8 =>
      'Cannot handle complex Agent tasks yet, suitable for simple chat';

  @override
  String get sourceTextea0ef2ae72 => 'Next';

  @override
  String get sourceText68e23d8fac => 'Download Local Model';

  @override
  String get sourceText405be21f38 => 'Recommended Model';

  @override
  String get sourceTextf430f6d1d1 =>
      'A lightweight model recommended for your device';

  @override
  String get sourceText1b58744dff => 'Loading model info...';

  @override
  String get sourceText3f9550508b => 'Resume';

  @override
  String get sourceText4bbcf94739 => 'Download Complete';

  @override
  String get sourceText33246f6a5e => 'Done';

  @override
  String get sourceText11d0241540 => 'Back';

  @override
  String get sourceText85d011402f => 'Unable to fetch recommended model';

  @override
  String get sourceText1b2fe43b5e =>
      'Check your network or download manually in Settings later';

  @override
  String get sourceText2bc19ec67e => 'Get Started';

  @override
  String get sourceTextc55627eba1 => 'API Key (optional)';

  @override
  String get sourceText90988df4ff => 'Browse Model Market';

  @override
  String get sourceText62b46f24ae => 'Recommended';

  @override
  String get sourceTextc59773a6a4 => 'Choose Method';

  @override
  String get sourceTextb62ed716e3 => 'Features';
}
