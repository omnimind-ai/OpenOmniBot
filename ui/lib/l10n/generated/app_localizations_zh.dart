// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get appName => '小万';

  @override
  String get brandName => '小万';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => '加载中';

  @override
  String get homeDrawerSearchHint => '搜索全部对话';

  @override
  String get homeDrawerClearSearch => '清空搜索';

  @override
  String get themeModeTitle => '主题模式';

  @override
  String get themeModeSubtitle => '切换浅色、深色或跟随系统外观';

  @override
  String get themeModeLight => '浅色';

  @override
  String get themeModeDark => '深色';

  @override
  String get themeModeSystem => '系统';

  @override
  String get languageTitle => '语言';

  @override
  String get languageSubtitle => '设置应用界面、Agent 提示词与工具文案的显示语言';

  @override
  String get languageFollowSystem => '跟随系统';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get settingsTitle => '设置';

  @override
  String get settingsSectionModelMemory => '模型与记忆';

  @override
  String get settingsSectionServiceEnvironment => '服务与环境';

  @override
  String get settingsSectionExperienceAppearance => '体验与外观';

  @override
  String get settingsSectionPermissionInfo => '权限与信息';

  @override
  String get settingsModelProviderTitle => '模型提供商';

  @override
  String get settingsModelProviderSubtitle => '配置模型地址、密钥与模型列表';

  @override
  String get settingsSceneModelTitle => '场景模型配置';

  @override
  String get settingsSceneModelSubtitle => '按场景绑定模型，未绑定场景使用默认模型';

  @override
  String get settingsLocalModelsTitle => '本地模型服务';

  @override
  String get settingsLocalModelsSubtitle => '管理本地模型、推理、API 服务与语音模型';

  @override
  String get settingsWorkspaceMemoryTitle => 'Workspace 记忆配置';

  @override
  String get settingsWorkspaceMemoryLoading => '加载中...';

  @override
  String get settingsWorkspaceMemoryEnabled => '已启用 workspace 记忆（嵌入检索可用）';

  @override
  String get settingsWorkspaceMemoryLexical => '使用 workspace 记忆（当前为词法检索）';

  @override
  String get settingsMcpToolsTitle => 'MCP 工具';

  @override
  String get settingsMcpToolsSubtitle => '添加、启停和管理远端 MCP 服务';

  @override
  String get settingsLocalServiceTitle => '本机服务';

  @override
  String get settingsLocalServiceSubtitle => '在局域网内访问小万 MCP 和 webchat 服务';

  @override
  String get settingsAlpineTitle => 'Alpine 环境';

  @override
  String get settingsAlpineSubtitle => '查看与打开应用内 Alpine 终端环境';

  @override
  String get settingsHideRecentsTitle => '后台隐藏';

  @override
  String get settingsHideRecentsSubtitle => '开启后应用将从最近任务列表中隐藏';

  @override
  String get settingsAlarmTitle => '闹钟设置';

  @override
  String get settingsAlarmSubtitle => '配置默认铃声、本地 mp3 或 mp3 直链';

  @override
  String get settingsAppearanceTitle => '外观设置';

  @override
  String get settingsAppearanceSubtitle => '配置主题模式、语言、共享背景图、聊天字号和文本颜色';

  @override
  String get settingsVibrationTitle => '振动反馈';

  @override
  String get settingsVibrationSubtitle => '执行任务时，通过振动进行操作提醒';

  @override
  String get settingsIndependentSendButtonTitle => '使用独立的发送按钮';

  @override
  String get settingsIndependentSendButtonSubtitle =>
      '开启后，聊天页键盘回车为换行；关闭后，回车直接发送';

  @override
  String get settingsAutoBackTitle => '任务完成后自动回聊天';

  @override
  String get settingsAutoBackSubtitle => '关闭后，任务结束将停留在当前完成页面';

  @override
  String get settingsHabitualHandTitle => '惯用手';

  @override
  String get settingsHabitualHandSubtitle => '影响聊天历史记录的侧滑菜单方向';

  @override
  String get settingsHabitualHandLeft => '左手';

  @override
  String get settingsHabitualHandRight => '右手';

  @override
  String get settingsCompanionPermissionTitle => '陪伴权限授权';

  @override
  String get settingsCompanionPermissionSubtitle => '仅访问您授权的 App，隐私安全更有保障';

  @override
  String get settingsAboutTitle => '关于小万';

  @override
  String get settingsHideRecentsFailed => '设置后台隐藏失败';

  @override
  String get settingsSaveFailed => '设置失败';

  @override
  String get settingsAutoBackEnabledToast => '任务完成后将自动返回聊天';

  @override
  String get settingsAutoBackDisabledToast => '任务完成后将停留在当前页面';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP 已开启：$endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP 已关闭';

  @override
  String get settingsMcpToggleFailed => 'MCP 开关失败';

  @override
  String get settingsOobFunctionAsToolTitle => 'OOB 函数接入工具';

  @override
  String get settingsOobFunctionAsToolSubtitle => '开启后 agent 可以直接调用已保存的 OOB 函数';

  @override
  String get settingsOobFunctionAsToolToggleFailed => 'OOB 函数工具开关失败';

  @override
  String get settingsCopiedAddress => '已复制访问地址';

  @override
  String get settingsCopiedToken => '已复制 Token';

  @override
  String get settingsTokenRefreshed => '已刷新 Token';

  @override
  String get settingsTokenRefreshFailed => '刷新 Token 失败';

  @override
  String get settingsMcpLocalService => '本机服务';

  @override
  String get settingsMcpAddress => '地址';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => '未生成';

  @override
  String get settingsCopyAddress => '复制地址';

  @override
  String get settingsCopyToken => '复制 Token';

  @override
  String get settingsRefreshToken => '刷新 Token';

  @override
  String get settingsMcpSecurityNotice =>
      '请在同一局域网内使用 Authorization: Bearer <Token> 调用 /mcp/v1/task/vlm，避免将地址或 Token 暴露到公网。';

  @override
  String get settingsInstalledAppsPermissionFailed => '请求应用列表权限失败';

  @override
  String get appearanceTitle => '外观设置';

  @override
  String get appearanceAutoSaving => '正在自动保存…';

  @override
  String get appearanceAutosaveHint => '更改会自动保存';

  @override
  String get appearanceBackgroundSource => '背景来源';

  @override
  String get appearancePreview => '效果预览';

  @override
  String get appearanceAdjustments => '效果调整';

  @override
  String get appearancePreviewChat => '聊天';

  @override
  String get appearancePreviewWorkspace => '工作区';

  @override
  String get appearanceEnableBackground => '启用背景图';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      '同时作用于聊天页和 Workspace 页面，并自动保存';

  @override
  String get appearanceSourceLocal => '本地图片';

  @override
  String get appearanceSourceRemote => '图片直链';

  @override
  String get appearanceNoLocalImage => '尚未选择本地图片';

  @override
  String get appearancePickImage => '选择图片';

  @override
  String get appearanceRepickImage => '重新选择';

  @override
  String get appearanceRemoteImageUrl => '图片直链';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => '背景柔化';

  @override
  String get appearanceBackgroundBlurSubtitle => '调节图片上方蒙版的柔化程度';

  @override
  String get appearanceOverlayIntensity => '蒙版强度';

  @override
  String get appearanceOverlayIntensitySubtitle => '增强统一蒙版，让页面元素更干净';

  @override
  String get appearanceOverlayBrightness => '蒙版明暗';

  @override
  String get appearanceOverlayBrightnessSubtitle => '提亮或压暗蒙版，不会直接修改原图';

  @override
  String get appearanceChatTextSize => '聊天文本大小';

  @override
  String get appearanceChatTextSizeSubtitle => '仅调整用户消息、AI 回复与思考区字号';

  @override
  String get appearanceTextColorTitle => '聊天文本颜色';

  @override
  String get appearanceTextColorSubtitle => '默认会自动跟随背景明暗，也可以改成固定颜色';

  @override
  String get appearanceTextColorAuto => '自动';

  @override
  String get appearanceCustomColorLabel => '自定义色号';

  @override
  String get appearanceCustomColorHint => '#FFFFFF 或 #FF112233';

  @override
  String get appearancePreviewTip => '图片可直接在上方预览里拖动和双指缩放，预览会尽量贴近实际效果。';

  @override
  String get appearanceColorWhite => '白';

  @override
  String get appearanceColorDarkGray => '深灰';

  @override
  String get appearanceColorLightBlue => '浅蓝';

  @override
  String get appearanceColorNavy => '藏蓝';

  @override
  String get appearanceColorTeal => '青绿';

  @override
  String get appearanceColorWarmYellow => '暖黄';

  @override
  String get appearanceInvalidHttpUrl => '请输入有效的 http(s) 图片直链';

  @override
  String get appearanceInvalidHexColor => '请输入 #RRGGBB 或 #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => '色号格式不正确';

  @override
  String appearancePickImageFailed(Object error) {
    return '选择图片失败：$error';
  }

  @override
  String get appearancePickLocalImageFirst => '请先选择本地图片';

  @override
  String get appearanceLocalImageMissing => '本地图片不存在，请重新选择';

  @override
  String appearanceAutosaveFailed(Object error) {
    return '自动保存失败：$error';
  }

  @override
  String get chatToolCalling => '正在调用工具';

  @override
  String get chatFallbackReply => '暂时无法生成回复，请重试。';

  @override
  String get chatPermissionRequired => '执行任务前需要先开启权限';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return '执行任务前，请先开启：$names';
  }

  @override
  String get chatRecentTerminalOutputNotice => '[只显示最近的部分终端输出]\n';

  @override
  String chatUserPrefix(Object text) {
    return '用户: $text\n';
  }

  @override
  String get permissionAccessibility => '无障碍权限';

  @override
  String get permissionOverlay => '悬浮窗权限';

  @override
  String get permissionInstalledApps => '应用列表读取权限';

  @override
  String get permissionPublicStorage => '公共文件访问';

  @override
  String get browserOverlayTitle => 'Agent Browser';

  @override
  String get browserOverlayClose => '关闭浏览器窗口';

  @override
  String get browserOverlayUnsupported => '当前平台暂不支持浏览器工具视图';

  @override
  String get networkErrorMessage => '抱歉，刚刚网络开小差了。再发一次试试？';

  @override
  String get rateLimitErrorMessage => '小万忙不过来了，等会儿再试试吧';

  @override
  String get chatHistoryArchivedTitle => '归档对话';

  @override
  String get chatHistoryTitle => '聊天记录';

  @override
  String get chatHistoryNoArchived => '暂无归档对话';

  @override
  String get chatHistoryEmpty => '暂无聊天记录';

  @override
  String get chatHistoryArchivedToast => '已归档';

  @override
  String get chatHistoryUnarchivedToast => '已移出归档';

  @override
  String get chatHistoryArchiveFailed => '归档对话失败';

  @override
  String get chatHistoryUnarchiveFailed => '移出归档失败';

  @override
  String get chatHistoryArchiveHint => '左滑对话即可归档';

  @override
  String get conversationStatusRunning => '执行中';

  @override
  String get conversationStatusCompleted => '已完成';

  @override
  String get homeDrawerArchive => '归档对话';

  @override
  String get homeDrawerNewChat => '新对话';

  @override
  String get webchatNoChats => '开始一个新的对话吧';

  @override
  String get memoryCenterTitle => '记忆中心';

  @override
  String get memoryShortTermTitle => '短期记忆';

  @override
  String get memoryLongTermTitle => '长期记忆';

  @override
  String get memoryCommandsTitle => '指令';

  @override
  String get memoryNoShortTerm => '还没有短期记忆';

  @override
  String get memoryNoShortTermDesc => '会话中的过程性信息会沉淀到短期记忆，并在后续整理后转入长期记忆。';

  @override
  String get memoryFilteredNoShortTerm => '当前筛选下还没有短期记忆';

  @override
  String get memoryFilteredNoShortTermDesc => '稍后再来看看，新的短期记忆会逐步出现。';

  @override
  String get memoryNoLongTerm => '长期记忆还未初始化';

  @override
  String get memoryNoLongTermDesc => '记忆能力启用后，你的跨会话长期记忆会在这里持续沉淀。';

  @override
  String get memoryDeleteConfirmTitle => '确定删除吗？';

  @override
  String get memoryDeleteWarning => '删除后该内容将不可找回';

  @override
  String get memoryEditDisabled => '短期记忆暂不支持编辑';

  @override
  String get memoryDeleteDisabled => '短期记忆暂不支持删除';

  @override
  String get memoryGreeting => '你好呀，\n欢迎回来，我们会在这里慢慢整理你的记忆。';

  @override
  String memorySelectedCount(Object n) {
    return '已选择$n项';
  }

  @override
  String get memoryDeselectAll => '全不选';

  @override
  String get memoryEditTitle => '编辑记忆';

  @override
  String get memoryIdLabel => '记忆 ID';

  @override
  String get memoryMatchScore => '匹配度';

  @override
  String get memoryAdditionalInfo => '附加信息';

  @override
  String get memoryAddLongTerm => '新增长期记忆';

  @override
  String get memorySaveToLongTerm => '保存到长期记忆';

  @override
  String get memoryLongTermAdded => '长期记忆已新增';

  @override
  String get memoryEditLongTerm => '编辑长期记忆';

  @override
  String get memorySaveChanges => '保存修改';

  @override
  String get memoryDeleteLongTermConfirm => '删除这条长期记忆？';

  @override
  String get memoryLongTermDeleted => '长期记忆已删除';

  @override
  String memoryLongTermFailed(Object error) {
    return '长期记忆操作失败：$error';
  }

  @override
  String memoryLongTermLoadFailed(Object error) {
    return '长期记忆加载失败：$error';
  }

  @override
  String get memoryNoMemories => '暂无记忆';

  @override
  String get memoryNoMemoriesDesc => '快去探索，添加喜欢的内容吧';

  @override
  String get skillStoreTitle => '技能仓库';

  @override
  String get skillBuiltin => '内置';

  @override
  String get skillOfficial => '官方';

  @override
  String get skillUser => '用户';

  @override
  String get skillInstalled => '已安装';

  @override
  String get skillNotInstalled => '未安装';

  @override
  String get skillEnabled => '启用中';

  @override
  String get skillDisabled => '已禁用';

  @override
  String get skillInstall => '安装';

  @override
  String get skillDelete => '删除';

  @override
  String get skillEmpty => '暂无已接入的技能';

  @override
  String get skillNoDescription => '暂无描述';

  @override
  String get skillBuiltinRemovedDesc => '该内置技能已从工作区移除，可随时重新安装。';

  @override
  String get skillDeleteTitle => '删除技能';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return '确认删除\"$name\"？';
  }

  @override
  String get skillDeleted => '已删除';

  @override
  String get skillDeleteFailed => '删除失败';

  @override
  String skillInstalledMsg(Object name) {
    return '已安装 $name';
  }

  @override
  String get skillInstallFailed => '安装失败';

  @override
  String skillEnabledMsg(Object name) {
    return '已启用 $name';
  }

  @override
  String skillDisabledMsg(Object name) {
    return '已禁用 $name';
  }

  @override
  String get skillToggleFailed => '切换失败';

  @override
  String get skillSyncOfficialTooltip => '安装/更新官方 Skills';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return '官方 Skills 已同步（$count 个）';
  }

  @override
  String get skillSyncOfficialFailed => '同步官方 Skills 失败';

  @override
  String get skillLoadFailed => '加载技能仓库失败';

  @override
  String get trajectoryTitle => '轨迹';

  @override
  String get trajectoryNoRecords => '暂无执行记录';

  @override
  String get trajectoryNoRecordsDesc => '小万为你执行的视觉任务，都会在此展示';

  @override
  String get trajectoryAll => '全部';

  @override
  String get trajectoryTaskRecords => '任务记录';

  @override
  String trajectorySelectedCount(Object n) {
    return '已选择$n项';
  }

  @override
  String get trajectoryUnknownDate => '未知日期';

  @override
  String get trajectoryThreeDaysAgo => '三天前';

  @override
  String get executionHistoryTitle => '执行历史';

  @override
  String get executionHistorySubtitle => '近3次任务执行历史';

  @override
  String get executionHistoryEmpty => '暂无执行历史';

  @override
  String executionHistoryTaskLabel(Object option) {
    return '$option任务';
  }

  @override
  String get modelProviderConfigTitle => 'Provider 配置';

  @override
  String get modelProviderConfigDesc => '新增、切换并维护模型服务提供商的名称、地址与密钥。';

  @override
  String get modelProviderName => 'Provider 名称';

  @override
  String get modelProviderNameHint => '例如：DeepSeek';

  @override
  String get modelProviderBaseUrlHint => '末尾加 # 可禁用自动补全请求路径';

  @override
  String get modelProviderApiKeyHint => '未填写 API Key 时，会以无鉴权方式请求 Provider。';

  @override
  String get modelListTitle => '模型列表';

  @override
  String get modelListDesc => '支持手动补充模型，也可从当前 Provider 拉取远端模型清单。';

  @override
  String modelListCount(Object count) {
    return '共 $count 个模型';
  }

  @override
  String get modelAddPrompt => '请添加模型！';

  @override
  String get modelBuiltinProvider => '内置 Provider';

  @override
  String get modelIdEmpty => '模型 ID 不能为空且不能以 scene. 开头';

  @override
  String get modelAlreadyExists => '模型已存在';

  @override
  String get modelAdded => '已添加模型';

  @override
  String get modelDeleted => '已删除模型';

  @override
  String get modelDeleteFailed => '删除模型失败';

  @override
  String get modelIdHint => '请输入模型 ID';

  @override
  String get modelAddProviderTitle => '新增 Provider';

  @override
  String get modelAddButton => '新增';

  @override
  String get modelProviderAdded => '已新增 Provider';

  @override
  String modelProviderAddFailed(Object error) {
    return '新增 Provider 失败：$error';
  }

  @override
  String get modelDeleteProviderTitle => '删除 Provider';

  @override
  String modelDeleteProviderMsg(Object name) {
    return '确定删除\"$name\"吗？场景绑定会保留，但需要重新选择可用 Provider。';
  }

  @override
  String get modelProviderDeleted => '已删除 Provider';

  @override
  String modelProviderDeleteFailed(Object error) {
    return '删除 Provider 失败：$error';
  }

  @override
  String get modelProviderLoadFailed => '加载模型提供商配置失败';

  @override
  String modelProviderSwitchFailed(Object error) {
    return '切换 Provider 失败：$error';
  }

  @override
  String get modelProviderBaseUrlRequired => '请先填写 Base URL';

  @override
  String get modelProviderInvalidBaseUrl => '请输入有效的 http(s) Base URL';

  @override
  String modelProviderFetchedModels(Object count) {
    return '已获取 $count 个模型';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return '拉取模型列表失败：$error';
  }

  @override
  String get sceneModelMapping => '场景映射';

  @override
  String get sceneModelMappingDesc => '按场景绑定 Provider 与模型，未绑定的场景会继续使用默认模型。';

  @override
  String get sceneModelRefreshList => '刷新模型列表';

  @override
  String get sceneModelSearchHint =>
      '点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；顶部搜索框固定不随列表滚动。';

  @override
  String get sceneModelNoScenes => '暂无可配置场景';

  @override
  String get sceneModelLoadFailed => '加载场景模型配置失败';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return '部分模型已更新，但这些 Provider 刷新失败：$profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return '已更新 $count 个模型';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return '刷新模型列表失败：$error';
  }

  @override
  String get sceneModelInvalidModelId => '模型 ID 不能以 scene. 开头';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '已将 $scene 绑定到 $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return '保存 $scene 配置失败：$error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return '已清除 $scene 的绑定';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene 已恢复为默认模型';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return '清除 $scene 配置失败：$error';
  }

  @override
  String sceneVoiceSaveFailed(Object error) {
    return '保存语音配置失败：$error';
  }

  @override
  String get localModelsTitle => '本地模型';

  @override
  String get localModelsAutoPreheat => '打开 App 时自动预热';

  @override
  String get localModelsAutoPreheatDesc => '进入应用后自动启动本地服务，并直接加载当前模型。';

  @override
  String get localModelsInstalled => '已安装模型';

  @override
  String get localModelsInstalledDesc => '搜索、切换默认模型或删除当前设备上的模型。';

  @override
  String get localModelsSearchHint => '搜索模型名称、ID 或标签';

  @override
  String get localModelsEmpty => '还没有可用的本地模型';

  @override
  String get localModelsEmptyDesc => '先去模型市场下载一个模型，或者手动放置 MNN 模型目录。';

  @override
  String get localModelsServiceControl => '服务控制';

  @override
  String get localModelsServiceControlDesc => '切换推理后端、当前模型和监听端口。';

  @override
  String get localModelsInferenceBackend => '推理后端';

  @override
  String get localModelsCurrentModel => '当前模型';

  @override
  String get localModelsCurrentModelHint => '启动服务时会加载这里选择的模型。';

  @override
  String get localModelsNoAvailableModels => '暂无可用模型';

  @override
  String get localModelsSelectModel => '选择一个模型';

  @override
  String get localModelsServicePort => '服务端口';

  @override
  String get localModelsServicePortHint => '请输入端口号';

  @override
  String get localModelsCurrentlyLoaded => '当前已加载';

  @override
  String get localModelsAutoPreheatSection => '自动预热';

  @override
  String get localModelsAutoPreheatSectionDesc => '打开 App 后自动启动本地服务并加载当前模型。';

  @override
  String get localModelsLocalInference => '本地推理模型';

  @override
  String get localModelsStopping => '停止中…';

  @override
  String get localModelsStarting => '启动中…';

  @override
  String get localModelsStopService => '停止服务';

  @override
  String get localModelsStartService => '启动服务';

  @override
  String get localModelsConfigLoadFailed => '无法加载本地模型配置';

  @override
  String get localModelsConfigLoadFailedDesc => '请稍后重试。';

  @override
  String get localModelsInstalledLoadFailed => '加载已安装模型失败';

  @override
  String get localModelsMarketLoadFailed => '加载模型市场失败';

  @override
  String get localModelsSwitchBackendFailed => '切换推理后端失败';

  @override
  String get localModelsActiveModelUpdated => '已更新当前模型';

  @override
  String get localModelsSetActiveFailed => '设置当前模型失败';

  @override
  String get localModelsPortInvalid => '端口号无效';

  @override
  String get localModelsPortUpdated => '已更新服务端口';

  @override
  String get localModelsPortSaveFailed => '保存端口失败';

  @override
  String get localModelsAutoPreheatSaveFailed => '保存自动预热设置失败';

  @override
  String get localModelsDownloadSourceSwitchFailed => '切换下载源失败';

  @override
  String get localModelsServiceStarted => '本地服务已启动';

  @override
  String get localModelsStartFailed => '启动服务失败';

  @override
  String get localModelsStopFailed => '停止服务失败';

  @override
  String get localModelsServiceStopped => '本地服务已停止';

  @override
  String get localModelsDownloadStartFailed => '启动下载失败';

  @override
  String get localModelsDownloadPauseFailed => '暂停下载失败';

  @override
  String localModelsDownloadStartedToast(String modelName) {
    return '开始下载：$modelName';
  }

  @override
  String localModelsDownloadPausedToast(String modelName) {
    return '下载已暂停：$modelName';
  }

  @override
  String localModelsDownloadCompletedToast(String modelName) {
    return '下载完成：$modelName';
  }

  @override
  String localModelsDownloadFailedToast(String modelName, String reason) {
    return '下载失败：$modelName — $reason';
  }

  @override
  String localModelsDownloadCancelledToast(String modelName, String reason) {
    return '下载已取消：$modelName — $reason';
  }

  @override
  String get localModelsDownloadErrorUnknown => '未知错误';

  @override
  String get localModelsFilterAndSource => '筛选与来源';

  @override
  String get localModelsFilterAndSourceDesc => '切换推理后端和下载源，影响当前市场列表。';

  @override
  String get localModelsDownloadSource => '下载源';

  @override
  String get localModelsSelectDownloadSource => '选择下载源';

  @override
  String get localModelsMarketModels => '市场模型';

  @override
  String get localModelsMarketModelsDesc => '搜索、下载、暂停或删除市场中的模型。';

  @override
  String get localModelsMarketSearchHint => '搜索市场模型名称、描述或标签';

  @override
  String get localModelsMarketEmpty => '模型市场暂时为空';

  @override
  String get localModelsMarketEmptyDesc => '请检查下载源，或者下拉刷新重试。';

  @override
  String get localModelsCurrentDefault => '当前默认';

  @override
  String get localModelsLoaded => '已加载';

  @override
  String get localModelsFileSize => '文件大小';

  @override
  String get localModelsModelDir => '模型目录';

  @override
  String get localModelsManualDir => '这是手动放置目录，App 内不提供删除。';

  @override
  String get localModelsOmniInferLoadable => '该模型可由 OmniInfer 直接加载。';

  @override
  String get localModelsSetAsCurrent => '设为当前';

  @override
  String get localModelsDelete => '删除';

  @override
  String get localModelsHasUpdate => '有更新';

  @override
  String get localModelsStage => '阶段';

  @override
  String get localModelsErrorInfo => '错误信息';

  @override
  String get localModelsResumeDownload => '继续下载';

  @override
  String get localModelsRetryDownload => '重新下载';

  @override
  String get localModelsDownloadModel => '下载模型';

  @override
  String get localModelsPause => '暂停';

  @override
  String get localModelsDeleteOldVersion => '删除旧版本';

  @override
  String get localModelsTabService => '服务';

  @override
  String get localModelsTabMarket => '市场';

  @override
  String get localModelsRefresh => '刷新';

  @override
  String get localModelsDownloadPreparing => '准备中';

  @override
  String get localModelsDownloading => '下载中';

  @override
  String get localModelsDownloadPaused => '已暂停';

  @override
  String get localModelsDownloadCompleted => '已完成';

  @override
  String get localModelsDownloadFailed => '下载失败';

  @override
  String get localModelsDownloadCancelled => '已取消';

  @override
  String get localModelsNotDownloaded => '未下载';

  @override
  String get localModelsImportFromDevice => '从设备导入';

  @override
  String get localModelsImportSuccess => '模型导入成功';

  @override
  String localModelsImportFailed(String reason) {
    return '导入失败：$reason';
  }

  @override
  String localModelsImporting(String modelId) {
    return '正在导入 $modelId...';
  }

  @override
  String get alarmSaved => '闹钟设置已保存';

  @override
  String get alarmRingtoneSource => '铃声来源';

  @override
  String get alarmSystemDefault => '系统默认铃声';

  @override
  String get alarmSystemDefaultDesc => '无需额外配置，兼容性最好';

  @override
  String get alarmLocalMp3 => '本地 mp3';

  @override
  String get alarmLocalMp3Desc => '选择手机内 mp3 作为闹钟铃声';

  @override
  String get alarmMp3Url => 'mp3 直链';

  @override
  String get alarmMp3UrlDesc => '使用 http(s) 直链播放在线 mp3';

  @override
  String get alarmAudioPermissionDenied => '读取音频权限未授予';

  @override
  String get alarmInvalidFilePath => '文件路径无效，请重新选择';

  @override
  String get alarmSelectLocalFirst => '请先选择本地 mp3 文件';

  @override
  String get alarmEnterHttpsUrl => '请输入 http(s) 开头的 mp3 直链';

  @override
  String get alarmLocalFile => '本地文件';

  @override
  String get alarmSelectMp3 => '选择 mp3 文件';

  @override
  String get authorizePageTitle => '应用权限授权';

  @override
  String get authorizeReceiveNotifications => '接收消息通知';

  @override
  String get authorizeNotificationsDesc => '打开后可以及时了解任务进展';

  @override
  String get companionPermissionManagement => '陪伴权限管理';

  @override
  String get companionPermissionDesc => '关闭对应的授权后，小万仍会显示，但不会展示任务执行内容';

  @override
  String get companionPermissionNote => '权限说明';

  @override
  String get companionAuthorizedApps => '授权应用';

  @override
  String get storageUsageTitle => '存储占用';

  @override
  String get storageUsageSubtitle => '查看空间占用明细，支持分项清理';

  @override
  String get storageAnalyzeFailed => '存储分析失败，请重试';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return '已清理$name，释放 $size';
  }

  @override
  String get storageCleanFailed => '清理失败，请稍后重试';

  @override
  String storageCleanCategory(Object name) {
    return '清理$name';
  }

  @override
  String get storageCleanConfirmMsg => '确认清理该分类数据吗？';

  @override
  String get storageCleanScope => '清理范围';

  @override
  String get storageCleanAll => '全部';

  @override
  String get storageClean7Days => '7天前';

  @override
  String get storageClean30Days => '30天前';

  @override
  String storageStrategyName(Object name) {
    return '执行策略：$name';
  }

  @override
  String storageStrategyDone(Object size) {
    return '策略执行完成，释放 $size';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return '策略完成，释放 $size，$count 项未完全成功';
  }

  @override
  String get storageStrategyFailed => '策略执行失败，请稍后重试';

  @override
  String get storageLoadFailed => '加载失败';

  @override
  String get storageReanalyze => '重新分析';

  @override
  String get storageTotalUsage => '总占用';

  @override
  String get storageAppSize => '应用大小';

  @override
  String get storageUserData => '用户数据';

  @override
  String get storageCleanable => '可清理';

  @override
  String storageStatsSource(Object source) {
    return '统计口径：$source';
  }

  @override
  String storagePackageName(Object name) {
    return '当前包名：$name';
  }

  @override
  String get storageTrendFirst => '这是首次分析，后续将展示占用变化趋势';

  @override
  String get storageSmartCleanup => '智能清理策略';

  @override
  String get storageExecute => '执行';

  @override
  String get storageUsageAnalysis => '占用分析';

  @override
  String get storageClean => '清理';

  @override
  String get storageRiskLow => '低风险';

  @override
  String get storageRiskCaution => '谨慎';

  @override
  String get storageRiskHigh => '高风险';

  @override
  String get storageReadOnly => '只读';

  @override
  String get storageSystemStats => '系统统计（与系统设置更接近）';

  @override
  String get storageDirectoryScan => '目录扫描估算';

  @override
  String get storageAdditionalInfo => '附加信息';

  @override
  String get storageCatAppBinary => '应用安装包';

  @override
  String get storageCatAppBinaryDesc => '应用安装文件占用（APK/AAB split）';

  @override
  String get storageCatCache => '缓存';

  @override
  String get storageCatCacheDesc => '临时文件与图片缓存，可安全清理';

  @override
  String get storageCatCacheHint => '清理后会在使用中自动重新生成';

  @override
  String get storageCatConversation => '会话历史';

  @override
  String get storageCatConversationDesc => '对话与工具执行历史（估算）';

  @override
  String get storageCatConversationHint => '会删除历史消息记录，且不可恢复';

  @override
  String get storageCatDatabaseOther => '数据库其他占用';

  @override
  String get storageCatDatabaseOtherDesc => '索引与系统表等数据库占用';

  @override
  String get storageCatWorkspaceBrowser => 'Workspace 浏览器产物';

  @override
  String get storageCatWorkspaceBrowserDesc => '浏览器截图、下载文件和中间产物';

  @override
  String get storageCatWorkspaceBrowserHint => '会删除浏览器工具相关的中间文件';

  @override
  String get storageCatWorkspaceOffloads => 'Workspace Offloads';

  @override
  String get storageCatWorkspaceOffloadsDesc => '工具离线输出与临时文件';

  @override
  String get storageCatWorkspaceOffloadsHint => '仅删除离线产物，不影响核心功能';

  @override
  String get storageCatWorkspaceAttachments => 'Workspace 附件';

  @override
  String get storageCatWorkspaceAttachmentsDesc => '历史任务使用的附件文件';

  @override
  String get storageCatWorkspaceAttachmentsHint => '可能影响历史任务对附件的回看';

  @override
  String get storageCatWorkspaceShared => 'Workspace 共享区';

  @override
  String get storageCatWorkspaceSharedDesc => '跨任务共享的工作区文件';

  @override
  String get storageCatWorkspaceSharedHint => '可能影响后续任务复用共享文件';

  @override
  String get storageCatWorkspaceMemory => 'Workspace 记忆数据';

  @override
  String get storageCatWorkspaceMemoryDesc => '长期/短期记忆与索引数据';

  @override
  String get storageCatWorkspaceUserFiles => 'Workspace 用户文件';

  @override
  String get storageCatWorkspaceUserFilesDesc => '用户主动保存到 workspace 的文件';

  @override
  String get storageCatLocalModelsFiles => '本地模型文件';

  @override
  String get storageCatLocalModelsFilesDesc => '.mnnmodels 下的模型文件';

  @override
  String get storageCatLocalModelsFilesHint => '会删除模型文件，后续需重新下载';

  @override
  String get storageCatLocalModelsCache => '模型推理缓存';

  @override
  String get storageCatLocalModelsCacheDesc => 'mmap 与本地推理临时目录';

  @override
  String get storageCatLocalModelsCacheHint => '清理后会在推理时重新生成';

  @override
  String get storageCatTerminalLocal => '终端运行时（local）';

  @override
  String get storageCatTerminalLocalDesc => 'Alpine 终端 local 运行目录';

  @override
  String get storageCatTerminalLocalHint => '会删除终端 local 目录，需重新初始化';

  @override
  String get storageCatTerminalBootstrap => '终端运行时（引导文件）';

  @override
  String get storageCatTerminalBootstrapDesc => 'proot/lib/alpine 引导文件';

  @override
  String get storageCatTerminalBootstrapHint => '会删除终端引导文件，需重新初始化';

  @override
  String get storageCatSharedDrafts => '共享草稿';

  @override
  String get storageCatSharedDraftsDesc => '外部分享导入的草稿缓存';

  @override
  String get storageCatSharedDraftsHint => '会删除未发送的草稿附件';

  @override
  String get storageCatMcpInbox => 'MCP 收件箱';

  @override
  String get storageCatMcpInboxDesc => 'MCP 文件传输接收目录';

  @override
  String get storageCatMcpInboxHint => '会删除 MCP 收件箱中的文件';

  @override
  String get storageCatLegacyWorkspace => '旧版遗留数据';

  @override
  String get storageCatLegacyWorkspaceDesc => '升级后可能残留的旧 workspace 目录';

  @override
  String get storageCatLegacyWorkspaceHint => '建议确认无用后再清理';

  @override
  String get storageCatOtherUserData => '其他数据';

  @override
  String get storageCatOtherUserDataDesc => '未命中分类规则的数据';

  @override
  String get storageStrategySafeQuick => '安全快速清理';

  @override
  String get storageStrategySafeQuickDesc => '优先清理低风险缓存与临时产物';

  @override
  String get storageStrategyBalanceDeep => '平衡深度清理';

  @override
  String get storageStrategyBalanceDeepDesc => '释放更多空间，保留核心模型与用户文件';

  @override
  String get storageStrategyFree1gb => '目标释放 1GB';

  @override
  String get storageStrategyFree1gbDesc => '按高收益顺序清理，尽量达到 1GB 释放目标';

  @override
  String get storageHintConversation => '如历史未释放，请重新进入页面执行「重新分析」';

  @override
  String get storageHintLocalModels => '模型被清理后，可在「本地模型服务」页面重新下载';

  @override
  String get storageHintTerminal => '终端运行时被清理后，可在 Alpine 环境页重新初始化';

  @override
  String get storageHintGeneral => '若清理失败，可稍后重试或重启应用后再次清理';

  @override
  String get storageHintNotCleanable => '该分类当前不可清理';

  @override
  String get storageHintSkipped => '该分类已跳过（可选项）';

  @override
  String storageCleanPartialFailed(Object hint) {
    return '部分清理失败：$hint';
  }

  @override
  String get storageCleanPartialFailedGeneric => '部分文件清理失败，请稍后重试';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return '对比上次分析：总计 $total，可清理 $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return '上次分析时间：$time';
  }

  @override
  String get aboutDescription =>
      '小万，是一款以智能对话为核心的手机AI助\n手，通过语义理解与持续学习能力，协助用户\n完成信息处理、决策辅助和日常管理。';

  @override
  String get aboutBetaProgramTitle => '加入 beta 测试';

  @override
  String get aboutBetaProgramDescription => '接收更快的四段版更新。';

  @override
  String get aboutBetaProgramToggleFailed => 'beta 测试设置更新失败';

  @override
  String get aboutPreferencesSectionTitle => '更新与测试';

  @override
  String get aboutApkSourceTitle => '安装包下载源';

  @override
  String get aboutApkSourceDescription => '选择安装更新时使用的下载源。';

  @override
  String get aboutApkSourceOptionCnb => 'Cloudflare R2';

  @override
  String get aboutApkSourceOptionCnbDescription => '通过更新 Worker 分发';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription => '官方 Release';

  @override
  String get aboutApkSourceSwitchFailed => '安装包下载源切换失败';

  @override
  String get aboutUpdateHintDefault => '检查更新获取最新版本';

  @override
  String get workspaceMemoryLoadFailed => '加载 workspace 记忆配置失败';

  @override
  String get workspaceSoulSaved => 'SOUL.md 已保存';

  @override
  String get workspaceSoulSaveFailed => 'SOUL.md 保存失败';

  @override
  String get workspaceChatSaved => 'CHAT.md 已保存';

  @override
  String get workspaceChatSaveFailed => 'CHAT.md 保存失败';

  @override
  String get workspaceMemorySaved => 'MEMORY.md 已保存';

  @override
  String get workspaceMemorySaveFailed => 'MEMORY.md 保存失败';

  @override
  String get workspaceEmbeddingToggleFailed => '记忆嵌入开关更新失败';

  @override
  String get workspaceRollupToggleFailed => '夜间整理开关更新失败';

  @override
  String get workspaceRollupDone => '整理完成';

  @override
  String get workspaceRollupFailed => '立即整理失败';

  @override
  String get workspaceNone => '暂无';

  @override
  String get workspaceMemoryTitle => 'Workspace 记忆';

  @override
  String get workspaceMemoryCapability => '记忆能力';

  @override
  String get workspaceEmbeddingReady => '已配置，可使用向量检索';

  @override
  String get workspaceEmbeddingNotReady => '未配置，将自动降级为词法检索';

  @override
  String get workspaceGoToConfig => '去场景模型配置记忆嵌入模型';

  @override
  String get workspaceNightlyRollup => '夜间记忆整理（22:00）';

  @override
  String workspaceLastRun(Object time) {
    return '最近运行：$time';
  }

  @override
  String workspaceNextRun(Object time) {
    return '下次运行：$time';
  }

  @override
  String get workspaceRollupNow => '立即整理一次';

  @override
  String get workspaceDocContent => '文档内容';

  @override
  String get workspaceSoulMd => 'SOUL.md（Agent 灵魂）';

  @override
  String get workspaceChatMd => 'CHAT.md（纯聊天系统提示词）';

  @override
  String get workspaceMemoryMd => 'MEMORY.md（长期记忆）';

  @override
  String get alpineNodeJs => 'Node.js 运行时';

  @override
  String get alpineNpm => 'Node.js 包管理器';

  @override
  String get alpineGit => 'Git 版本控制';

  @override
  String get alpinePython => 'Python 解释器';

  @override
  String get alpinePip => 'Python 项目与包工具';

  @override
  String get alpinePipInstall => 'Python 包安装器';

  @override
  String get alpineCodex => 'OpenAI Codex CLI 与 app-server 桥接';

  @override
  String get alpineSshClient => 'SSH 客户端';

  @override
  String get alpineSshpass => 'SSH 密码辅助工具';

  @override
  String get alpineOpenSshServer => 'OpenSSH 服务器';

  @override
  String get alpineDetectFailed => '检测 Alpine 环境失败';

  @override
  String get alpineBootTasksLoadFailed => '读取自启动任务失败';

  @override
  String get alpineConfigOpenFailed => '打开终端环境配置失败';

  @override
  String get alpineBootTaskAdded => '已新增自启动任务';

  @override
  String get alpineBootTaskUpdated => '已更新自启动任务';

  @override
  String get alpineBootTaskSaveFailed => '保存自启动任务失败';

  @override
  String get alpineBootEnabled => '已开启应用启动时自启动';

  @override
  String get alpineBootDisabled => '已关闭自动启动';

  @override
  String get alpineBootTaskUpdateFailed => '更新任务失败';

  @override
  String get alpineDeleteBootTask => '删除自启动任务';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return '确认删除\"$name\"吗？';
  }

  @override
  String get alpineBootTaskDeleted => '已删除自启动任务';

  @override
  String get alpineBootTaskDeleteFailed => '删除任务失败';

  @override
  String get alpineCommandSent => '启动命令已发送';

  @override
  String get alpineStartFailed => '启动任务失败';

  @override
  String get alpineDetecting => '正在检测环境';

  @override
  String alpineStartConfig(Object count) {
    return '开始配置（$count 项）';
  }

  @override
  String get alpineAllReady => '全部已就绪';

  @override
  String get alpineDetectingDesc => '正在后台检测 Alpine 内常见开发环境的版本信息。';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '已就绪 $ready/$total 项，可直接勾选缺失项并进入 ReTerminal 自动配置。';
  }

  @override
  String get alpineBootTasks => '自启动任务';

  @override
  String get alpineBootTasksDesc =>
      '打开 Omnibot 时会在后台检查已启用的任务，并在对应 ReTerminal 会话内启动命令，适合常驻服务。';

  @override
  String get alpineAddTask => '新增任务';

  @override
  String get alpineOpenTerminal => '打开终端';

  @override
  String get alpineNoTasksDesc =>
      '暂无任务。你可以添加例如 `python app.py`、`node server.js`、`./start.sh` 之类的常驻命令。';

  @override
  String get alpineBootOnAppOpen => '开机打开 app 后启动';

  @override
  String get alpineNotEnabled => '未启用';

  @override
  String get alpineRunning => '已在运行';

  @override
  String get alpineStartNow => '立即启动';

  @override
  String get alpineEdit => '编辑';

  @override
  String get alpineVersionDetected => '已检测到可用版本';

  @override
  String get alpineVersionNotFound => '未检测到';

  @override
  String get alpineTaskNameHint => '请输入任务名称';

  @override
  String get alpineCommandHint => '请输入启动命令';

  @override
  String get alpineEditBootTask => '编辑自启动任务';

  @override
  String get alpineAddBootTask => '新增自启动任务';

  @override
  String get alpineTaskName => '任务名称';

  @override
  String get alpineTaskNameExample => '例如：本地 API 服务';

  @override
  String get alpineStartCommand => '启动命令';

  @override
  String get alpineCommandExample => '例如：python app.py 或 pnpm start';

  @override
  String get alpineWorkDir => '工作目录';

  @override
  String get alpineBootAutoStart => '打开小万时自动启动';

  @override
  String get alpineDevEnv => '开发环境';

  @override
  String get alpineAiAgent => 'AI Agent';

  @override
  String get alpineEnvConfig => '环境配置';

  @override
  String alpineWorkDirValue(Object dir) {
    return '工作目录：$dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => '记忆嵌入检索';

  @override
  String get chatHistoryStartConversation => '开始对话';

  @override
  String get homeDrawerSearching => '正在搜索对话内容…';

  @override
  String get homeDrawerNoResults => '没有找到相关对话';

  @override
  String get homeDrawerSearchHint2 => '试试更短的关键词，或换一种说法';

  @override
  String get homeDrawerSearchResults => '搜索结果';

  @override
  String get homeDrawerResultCount => '条';

  @override
  String get homeDrawerScheduled => '定时';

  @override
  String get homeDrawerGreeting => '你好！';

  @override
  String get homeDrawerWelcome => '欢迎使用小万';

  @override
  String get homeDrawerDawnGreeting => '凌晨啦';

  @override
  String get homeDrawerDawnSub => '还没休息吗？';

  @override
  String get homeDrawerDawnGreeting2 => '天还没亮';

  @override
  String get homeDrawerDawnSub2 => '早起的你辛苦啦～';

  @override
  String get homeDrawerDawnGreeting3 => '深夜的时光很静';

  @override
  String get homeDrawerDawnSub3 => '但也要记得给身体留些休息呀～';

  @override
  String get homeDrawerMorningGreeting => '早安！';

  @override
  String get homeDrawerMorningSub => '开启元气一天';

  @override
  String get homeDrawerMorningGreeting2 => '早呀！';

  @override
  String get homeDrawerMorningSub2 => '新的一天开始啦';

  @override
  String get homeDrawerForenoonGreeting => '上午好！';

  @override
  String get homeDrawerForenoonSub => '再忙也别忘了活动下肩膀';

  @override
  String get homeDrawerForenoonGreeting2 => '上午的效率超棒！';

  @override
  String get homeDrawerForenoonSub2 => '继续加油';

  @override
  String get homeDrawerLunchGreeting => '午饭时间到！';

  @override
  String get homeDrawerLunchSub => '好好吃饭，别凑合';

  @override
  String get homeDrawerLunchGreeting2 => '午安～';

  @override
  String get homeDrawerLunchSub2 => '吃完记得歇会儿';

  @override
  String get homeDrawerLunchGreeting3 => '午餐不知道吃什么？';

  @override
  String get homeDrawerLunchSub3 => '让小万帮你推荐吧！';

  @override
  String get homeDrawerAfternoonGreeting => '喝杯茶提提神';

  @override
  String get homeDrawerAfternoonSub => '剩下的任务也能轻松搞定～';

  @override
  String get homeDrawerAfternoonGreeting2 => '工作间隙看看窗外';

  @override
  String get homeDrawerAfternoonSub2 => '让眼睛歇一歇～';

  @override
  String get homeDrawerEveningGreeting => '回家路上慢点';

  @override
  String get homeDrawerEveningSub => '今晚好好放松～';

  @override
  String get homeDrawerEveningGreeting2 => '傍晚了';

  @override
  String get homeDrawerEveningSub2 => '吹来的晚风很舒服呀！～';

  @override
  String get homeDrawerEveningGreeting3 => '忙了一天';

  @override
  String get homeDrawerEveningSub3 => '吃顿好的犒劳自己～';

  @override
  String get homeDrawerNightGreeting => '晚上好！';

  @override
  String get homeDrawerNightSub => '享受属于自己的时光吧～';

  @override
  String get homeDrawerNightGreeting2 => '夜色渐浓';

  @override
  String get homeDrawerNightSub2 => '准备下早点休息啦～';

  @override
  String get homeDrawerNightGreeting3 => '该休息了';

  @override
  String get homeDrawerNightSub3 => '让小万帮你定个闹钟吧！';

  @override
  String get homeDrawerLateNightGreeting => '放下手机早点睡';

  @override
  String get homeDrawerLateNightSub => '明天才能元气满满～';

  @override
  String get homeDrawerLateNightGreeting2 => '深夜了';

  @override
  String get homeDrawerLateNightSub2 => '好好和今天说晚安～';

  @override
  String get omniflowPanelTitle => 'OmniFlow 轨迹面板';

  @override
  String get omniflowPanelDesc => '管理 OmniFlow Function：查看、执行或删除 Function 资产。';

  @override
  String get omniflowFunctionList => 'Function 列表';

  @override
  String get omniflowFunctionSearch => '搜索 Function';

  @override
  String get omniflowFunctionSearchHint => '按名称、描述等关键字过滤';

  @override
  String get omniflowSettings => 'OmniFlow 设置';

  @override
  String get omniflowSettingsSubtitle => '记录高频可复用操作段，加速任务执行';

  @override
  String get omniflowEnablePreHook => '启用 OmniFlow 执行加速';

  @override
  String get omniflowAutoStartProvider => 'OmniFlow 自启动';

  @override
  String get omniflowRefresh => '刷新';

  @override
  String get omniflowProviderStart => '启动';

  @override
  String get omniflowProviderStop => '停止';

  @override
  String get omniflowProviderRestart => '重启';

  @override
  String get omniflowSaveConfig => '保存';

  @override
  String get omniflowConfigSaved => 'OmniFlow 配置已保存';

  @override
  String get omniflowConfigSaveFailed => '保存 OmniFlow 配置失败';

  @override
  String get omniflowConfigLoadFailed => '加载 OmniFlow 配置失败';

  @override
  String get omniflowFunctionsLoadFailed => '加载 Function 列表失败';

  @override
  String get omniflowTempFunctions => '临时 Function';

  @override
  String get omniflowReadyFunctions => '可用 Function';

  @override
  String get omniflowServiceAddressNotConfigured => '服务地址未配置';

  @override
  String get omniflowSkillLibrary => 'OmniFlow 技能库';

  @override
  String get omniflowServiceStatus => '服务状态';

  @override
  String get omniflowServiceStatusRunning => '运行中';

  @override
  String get omniflowServiceStatusStopped => '未运行';

  @override
  String get omniflowServiceAddress => '服务地址';

  @override
  String get omniflowDataDirectory => '数据目录';

  @override
  String get omniflowNotSet => '未设置';

  @override
  String get omniflowEnableAccelerationDesc => '执行任务前优先匹配已学习的技能';

  @override
  String get omniflowAutoStartDesc => '打开应用时自动启动技能服务';

  @override
  String get omniflowStarting => '启动中...';

  @override
  String get omniflowRestarting => '重启中...';

  @override
  String get omniflowStopping => '停止中...';

  @override
  String get omniflowViewSkillLibrary => '查看技能库';

  @override
  String get omniflowViewFunctionLibrary => '查看功能库';

  @override
  String get omniflowClearAllData => '清空所有数据';

  @override
  String get omniflowClearAllDataTitle => '清空所有数据';

  @override
  String get omniflowClearAllDataConfirm =>
      '确认清空所有 OmniFlow 数据？\n\n这将删除：\n• 所有 Functions\n• 所有 Run Logs\n• 所有 Shared Pages\n\n此操作不可恢复！';

  @override
  String get omniflowCancel => '取消';

  @override
  String get omniflowClear => '清空';

  @override
  String omniflowClearSuccess(Object functions, Object runLogs) {
    return '已清空: $functions functions, $runLogs run_logs';
  }

  @override
  String get omniflowClearFailed => '清空失败';

  @override
  String omniflowProviderActionSuccess(Object action) {
    return 'provider $action 成功';
  }

  @override
  String omniflowProviderActionFailed(Object action) {
    return 'provider $action 失败';
  }

  @override
  String get functionLibraryTitle => '功能库';

  @override
  String get functionLibrarySearchHint => '搜索功能名称或应用';

  @override
  String get functionLibraryEmpty => '暂无已学习的功能';

  @override
  String get functionLibraryEmptyDesc => '执行任务后，高频操作会自动沉淀到这里';

  @override
  String get functionLibrarySteps => '步';

  @override
  String get functionLibraryHasParams => '有参数';

  @override
  String get functionLibraryRunCount => '执行';

  @override
  String get functionLibraryId => 'ID';

  @override
  String get functionLibraryParams => '参数';

  @override
  String get functionLibrarySource => '来源';

  @override
  String get functionLibraryCreatedAt => '创建时间';

  @override
  String get functionLibraryEdit => '编辑';

  @override
  String get functionLibraryEditTitle => '编辑功能';

  @override
  String get functionLibraryEditHint => '修改功能的描述名称';

  @override
  String get functionLibraryEditPlaceholder => '输入新的描述';

  @override
  String get functionLibraryEditSuccess => '已更新';

  @override
  String get functionLibraryEditFailed => '更新失败';

  @override
  String get functionLibraryDelete => '删除';

  @override
  String get functionLibraryDeleteTitle => '删除功能';

  @override
  String functionLibraryDeleteConfirm(Object name) {
    return '确认删除「$name」？';
  }

  @override
  String get functionLibraryDeleted => '已删除';

  @override
  String get functionLibraryDeleteFailed => '删除失败';

  @override
  String get functionLibraryUpload => '上传';

  @override
  String get functionLibraryUploadTitle => '上传到云端';

  @override
  String get functionLibraryUploadSuccess => '上传成功';

  @override
  String get functionLibraryUploadFailed => '上传失败';

  @override
  String get functionLibraryDownload => '从云端下载';

  @override
  String get functionLibraryDownloadTitle => '从云端下载';

  @override
  String get functionLibraryDownloadSuccess => '下载成功';

  @override
  String get functionLibraryDownloadFailed => '下载失败';

  @override
  String get functionLibraryCloudUrlHint => '输入云端服务地址';

  @override
  String get functionLibraryConfirm => '确定';

  @override
  String get functionLibrarySyncStatus => '同步状态';

  @override
  String get functionLibrarySynced => '已同步';

  @override
  String get functionLibraryLocalOnly => '仅本地';

  @override
  String get functionLibraryCloudOnly => '仅云端';

  @override
  String get functionLibraryStartNode => '起始页面';

  @override
  String get functionLibraryEndNode => '结束页面';

  @override
  String get functionLibraryLastRun => '最近执行';

  @override
  String get functionLibraryLastRunSuccess => '成功';

  @override
  String get functionLibraryLastRunFailed => '失败';

  @override
  String get functionLibraryLastRunGoal => '任务';

  @override
  String get functionLibraryNoDescription => '无描述';

  @override
  String functionLibrarySummaryOnPage(Object page) {
    return '在「$page」页面';
  }

  @override
  String functionLibrarySummaryFromTo(Object from, Object to) {
    return '从「$from」到「$to」';
  }

  @override
  String functionLibrarySummaryFrom(Object from) {
    return '从「$from」开始';
  }

  @override
  String functionLibrarySummaryTo(Object to) {
    return '到达「$to」';
  }

  @override
  String functionLibrarySummarySteps(Object count) {
    return '共 $count 步操作';
  }

  @override
  String functionLibrarySummaryParams(Object count) {
    return '需要 $count 个参数';
  }

  @override
  String get functionLibraryTest => '测试';

  @override
  String functionLibraryTestNeedParams(Object params) {
    return '需要输入参数：$params';
  }

  @override
  String get functionLibraryTestStarted => '已发起测试执行';

  @override
  String get functionLibraryViewDetails => '查看详情';

  @override
  String get functionLibraryDetailCompileSurface => '编译面';

  @override
  String get functionLibraryDetailGraphAnchors => '图锚点';

  @override
  String get functionLibraryDetailRunUsage => '运行统计';

  @override
  String get functionLibraryDetailLifecycle => '生命周期';

  @override
  String get functionLibraryDetailExamples => '参数示例';

  @override
  String get functionLibraryDetailDerivedFrom => '来源 raw function';

  @override
  String get functionLibraryDetailRuns => '执行次数';

  @override
  String get functionLibraryDetailSuccessFail => '成功 / 失败';

  @override
  String get functionLibraryDetailUpdatedAt => '更新时间';

  @override
  String get functionLibraryDetailBundleBacking => 'Bundle 资产';

  @override
  String get functionLibraryDetailActionCount => '动作数';

  @override
  String get functionLibraryDetailActionPreview => '动作预览';

  @override
  String get functionLibraryDetailNoActionPreview => '无动作';

  @override
  String functionLibraryDetailStepIndex(String index) {
    return '步骤 $index';
  }

  @override
  String get functionLibraryDetailBundleFunction => 'Bundle Function';

  @override
  String get functionLibraryDetailInternalBlocks => '内部 function block';

  @override
  String get functionLibraryDetailNoBlocks => '当前没有展开的内部 function block';

  @override
  String get functionLibraryDetailNoBundle => '当前没有可展示的 bundle function';

  @override
  String get functionLibraryDetailFunctionSchema => 'Function Schema';

  @override
  String get executionCompileHit => '复用技能';

  @override
  String executionCompileHitWithFunction(Object functionId) {
    return '复用技能 · $functionId';
  }

  @override
  String get executionVlmExecution => 'VLM 执行';

  @override
  String get executionActionOpenApp => '打开应用';

  @override
  String get executionActionClick => '点击';

  @override
  String get executionActionClickNode => '点击元素';

  @override
  String get executionActionLongPress => '长按';

  @override
  String get executionActionInputText => '输入文本';

  @override
  String get executionActionSwipe => '滑动';

  @override
  String get executionActionScroll => '滚动';

  @override
  String get executionActionPressKey => '按键';

  @override
  String get executionActionWait => '等待';

  @override
  String get executionActionFinished => '完成';

  @override
  String get executionActionCallFunction => '调用技能';

  @override
  String get executionActionDefault => '动作';

  @override
  String executionStepLabel(Object count) {
    return '$count steps';
  }

  @override
  String get executionSuccess => '成功';

  @override
  String get executionFailed => '失败';

  @override
  String get memorySaveAsSkillTitle => '保存为功能（Function）';

  @override
  String get memorySaveAsSkillContent =>
      '是否将这次执行记录保存为可复用的功能？\n\n保存后可在「功能库」中查看和管理。';

  @override
  String get memorySavingProgress => '正在保存功能...';

  @override
  String memorySaveSuccess(String functionId) {
    return '已保存为功能：$functionId';
  }

  @override
  String get memorySaveSuccessSimple => '已保存为功能';

  @override
  String get memorySaveSuccessHint => '功能已保存到本地功能库。\n你可以在功能库中查看、编辑或升级此功能。';

  @override
  String get memoryViewInLibrary => '查看功能库';

  @override
  String get memorySaveCannotImport => '该执行记录无法保存为功能';

  @override
  String get memorySaveFailed => '保存功能失败';

  @override
  String memorySaveFailedWithMessage(String message) {
    return '保存功能失败：$message';
  }

  @override
  String get memoryRunIdMissing => '执行记录 ID 缺失，无法保存';

  @override
  String get omniflowAssetSuccess => '成功';

  @override
  String get omniflowAssetFailed => '失败';

  @override
  String get omniflowAssetRunning => '运行中';

  @override
  String get omniflowAssetUnknown => '未知';

  @override
  String get omniflowAssetCompileHit => '复用功能';

  @override
  String get omniflowAssetCompileMiss => 'VLM 执行';

  @override
  String omniflowAssetSteps(int count) {
    return '$count 步';
  }

  @override
  String get omniflowAssetId => 'ID';

  @override
  String get omniflowAssetPackage => '应用包名';

  @override
  String get omniflowAssetSourceRuns => '来源执行';

  @override
  String get omniflowAssetLinkedFunction => '关联功能';

  @override
  String get omniflowAssetCopyId => '复制 ID';

  @override
  String get omniflowAssetEdit => '编辑';

  @override
  String get omniflowAssetMemory => '记忆';

  @override
  String get omniflowAssetReplay => '重放';

  @override
  String get omniflowAssetDelete => '删除';

  @override
  String get omniflowAssetEnrich => '升级';

  @override
  String get omniflowAssetUpload => '上传';

  @override
  String get omniflowAssetIdCopied => 'ID 已复制';

  @override
  String get omniflowAssetJsonCopied => 'JSON 已复制';

  @override
  String get omniflowAssetFunctionDetail => '功能详情';

  @override
  String get omniflowAssetRunLogDetail => '执行记录详情';

  @override
  String get omniflowAssetCopyJson => '复制 JSON';

  @override
  String get omniflowAssetClose => '关闭';

  @override
  String get omniflowAssetStartPage => '起始页面';

  @override
  String get omniflowAssetEndPage => '结束页面';

  @override
  String get omniflowAssetCreatedAt => '创建时间';

  @override
  String get omniflowAssetGoal => '目标';

  @override
  String get omniflowAssetStartedAt => '开始时间';

  @override
  String get omniflowAssetDoneReason => '完成原因';

  @override
  String get omniflowAssetView => '查看';

  @override
  String get omniflowAssetLoadFailed => '加载失败';

  @override
  String get omniflowAssetRunLogNotReady => '执行记录尚未落盘';

  @override
  String get omniflowAssetRunLogIndexFailed => '读取执行记录索引失败';

  @override
  String get omniflowAssetReplayTitle => '重放执行记录';

  @override
  String get omniflowAssetReplayConfirm => '是否确定重放这次执行记录？';

  @override
  String get omniflowAssetReplayProgress => '正在重放执行记录...';

  @override
  String get omniflowAssetReplaySuccess => '重放成功';

  @override
  String omniflowAssetReplaySuccessWithId(String functionId) {
    return '重放成功：$functionId';
  }

  @override
  String get omniflowAssetReplayFailed => '重放失败';

  @override
  String omniflowAssetReplayFailedWithMessage(String message) {
    return '重放失败：$message';
  }

  @override
  String get omniflowAssetCancel => '取消';

  @override
  String get omniflowAssetConfirm => '确定';

  @override
  String omniflowAssetCopySuccess(String label) {
    return '$label 已复制';
  }

  @override
  String omniflowAssetCopyFailed(String label) {
    return '$label 复制失败';
  }

  @override
  String omniflowAssetEmpty(String label) {
    return '$label 为空';
  }

  @override
  String get omniflowAssetNoSteps => '没有可展示的步骤';

  @override
  String get functionLibraryEnrich => '升级';

  @override
  String get functionLibraryEnrichTitle => '升级功能';

  @override
  String get functionLibraryEnrichConfirm =>
      '使用 AI 补齐此功能的语义信息？\n\n将自动生成：描述、参数槽位、前置/后置条件等。';

  @override
  String get functionLibraryEnrichProgress => '正在升级功能...';

  @override
  String get functionLibraryEnrichSuccess => '功能升级成功';

  @override
  String get functionLibraryEnrichFailed => '升级失败';

  @override
  String functionLibraryEnrichFailedWithMessage(String message) {
    return '升级失败：$message';
  }

  @override
  String get functionLibrarySplit => '拆分';

  @override
  String get functionLibrarySplitTitle => '拆分功能';

  @override
  String get functionLibrarySplitConfirm => '使用 AI 将此功能拆分为多个更小的功能？';

  @override
  String get functionLibrarySplitProgress => '正在拆分功能...';

  @override
  String functionLibrarySplitSuccess(int count) {
    return '功能拆分成功，生成了 $count 个新功能';
  }

  @override
  String get functionLibrarySplitFailed => '拆分失败';

  @override
  String get actionTypeOpenApp => '打开应用';

  @override
  String get actionTypeClick => '点击';

  @override
  String get actionTypeClickNode => '点击节点';

  @override
  String get actionTypeLongPress => '长按';

  @override
  String get actionTypeInputText => '输入文本';

  @override
  String get actionTypeSwipe => '滑动';

  @override
  String get actionTypePressKey => '按键';

  @override
  String get actionTypeWait => '等待';

  @override
  String get actionTypeFinished => '结束';

  @override
  String get actionTypeCallFunction => '调用功能';

  @override
  String get actionTypeDefault => '动作';

  @override
  String get omniflowProviderUpdate => 'Provider 更新';

  @override
  String get omniflowConnectionMode => '当前连接';

  @override
  String get omniflowConnectionModeBridge => 'Bridge 连接';

  @override
  String get omniflowConnectionModeEmbedded => '本地内置';

  @override
  String get omniflowProviderVersion => 'Provider 版本';

  @override
  String get omniflowProviderPort => 'Provider 端口';

  @override
  String get omniflowProviderStore => 'Provider Store';

  @override
  String get omniflowCurrentVersion => '当前版本';

  @override
  String get omniflowLatestVersion => '最新版本';

  @override
  String get omniflowUpdateAvailable => '有新版本可用';

  @override
  String get omniflowUpdateNotSupported =>
      '有新版本，但主机 Provider 不支持自动更新（请手动 git pull）';

  @override
  String get omniflowStartProviderFirst => '请先启动 Provider 后再检查更新';

  @override
  String get omniflowCheckUpdate => '检查更新';

  @override
  String get omniflowCheckingUpdate => '检查中...';

  @override
  String get omniflowApplyUpdate => '立即更新';

  @override
  String get omniflowApplyingUpdate => '更新中...';

  @override
  String get omniflowCheckUpdateFailed => '检查更新失败';

  @override
  String omniflowNewVersionFound(String version) {
    return '发现新版本: $version';
  }

  @override
  String get omniflowPackageNotInstalled => '当前设备未安装';

  @override
  String get omniflowAlreadyLatest => '已是最新版本';

  @override
  String omniflowUpdateSuccess(String version) {
    return '当前设备已更新到 $version';
  }

  @override
  String get omniflowUpdateBridgeModeHint =>
      '当前连接仍是 Bridge；这次只更新了设备里的 OmniFlow 包。';

  @override
  String get omniflowUpdateRestartRequired => '设备包已更新；请手动重启本地内置 Provider 后生效。';

  @override
  String get omniflowUpdateFailed => '更新失败';

  @override
  String get executionRouteMemorized => '⚡ 已记忆';

  @override
  String get executionRouteAiPlanning => '🤔 AI规划';

  @override
  String get runLogTimelineTitle => '执行步骤';

  @override
  String get runLogTimelineViewSteps => '查看步骤';

  @override
  String runLogTimelineStepCount(int count) {
    return '$count 步';
  }

  @override
  String get runLogTimelineLoadFailed => '加载步骤失败';

  @override
  String get runLogTimelineEmpty => '暂无步骤数据';

  @override
  String get runLogTimelineUnknown => '未知';

  @override
  String get chatInputCommandTooltip => '命令';

  @override
  String get workbenchTitle => '工作台';

  @override
  String get workbenchWorkspaceTitle => '工作区';

  @override
  String get workbenchWorkspaceOpenWorkbench => '打开工作台';

  @override
  String get workbenchWorkspaceOpenProjectConsole => '进入管理';

  @override
  String get workbenchWorkspaceWorkMode => '文件';

  @override
  String get workbenchWorkspaceProjectMode => '项目';

  @override
  String get workbenchWorkspaceProjectFrontendsTitle => '项目窗口';

  @override
  String get workbenchWorkspaceProjectFrontendsSubtitle =>
      '开启项目模式后，这里像子窗口一样直接承载当前激活项目的 OOB 原生前端。';

  @override
  String get workbenchWorkspaceProjectFrontendsEmpty =>
      '暂无项目前端。回到对话里描述需求后，Agent 会通过工作台创建可显示的项目。';

  @override
  String get workbenchWorkspaceProjectOpenFailed => '打开项目前端失败';

  @override
  String get workbenchWorkspaceProjectUnsupportedDisplay =>
      '这个显示页暂不支持内嵌窗口显示，请用右上角打开为完整页面。';

  @override
  String get workbenchWorkspaceGuideTooltip => '查看项目工作台说明';

  @override
  String get workbenchWorkspaceGuideClose => '关闭说明';

  @override
  String get workbenchWorkspaceGuideTitle => '项目工作台怎么工作';

  @override
  String get workbenchWorkspaceGuideIntro =>
      '项目模式不是新的聊天页，而是 OOB 里用来承载 vibe project 的原生工作台。它把生成前端、项目工具、工作区文件、Skill 和持久化数据连成一个可继续编辑的单位。';

  @override
  String get workbenchWorkspaceGuideFlowTitle => '交互链路';

  @override
  String get workbenchWorkspaceGuideFlowPrompt => '提示词 + Skill 拆解需求';

  @override
  String get workbenchWorkspaceGuideFlowProject => '项目注册表记录容器';

  @override
  String get workbenchWorkspaceGuideFlowApi => '项目工具注册业务能力';

  @override
  String get workbenchWorkspaceGuideFlowDisplay => 'Flutter 显示页展示业务前端';

  @override
  String get workbenchWorkspaceGuideFlowPersist =>
      'data/ + logs/ 持久化 AI 与 UI 调用';

  @override
  String get workbenchWorkspaceGuideProjectTitle => '项目绑定什么';

  @override
  String get workbenchWorkspaceGuideProjectBody =>
      '一个项目会绑定目标、Skill、工作区文件、显示页列表、项目工具、数据和日志。它不是 MCP 工具列表，也不是随手生成的 HTML。';

  @override
  String get workbenchWorkspaceGuideFrontendTitle => '前端怎么显示';

  @override
  String get workbenchWorkspaceGuideFrontendBody =>
      '生成前端是 OOB 原生 Flutter 显示页。工作区切到项目后，不再显示大型管理列表，而是像浏览器子窗口一样直接承载当前激活项目的首页；一个项目可以有多个显示页，可用小菜单切换。';

  @override
  String get workbenchWorkspaceGuideBackendTitle => '后端怎么被调用';

  @override
  String get workbenchWorkspaceGuideBackendBody =>
      '后端能力注册为项目工具，例如 todo.add、todo.finish。AI 层和前端按钮都调用同一条 workbenchApiCall(projectId, toolId, inputs)，项目创建、导出、删除等控制接口不会混进业务工具。';

  @override
  String get workbenchWorkspaceGuideDataTitle => '数据怎么流';

  @override
  String get workbenchWorkspaceGuideDataBody =>
      '调用会经过 Flutter -> MethodChannel -> OOB native executor，然后写入项目的 data/ 和 logs/。前端刷新、AI 调用统计和重启后的状态都来自这份持久化数据。';

  @override
  String get workbenchWorkspaceGuideVibeTitle => '怎么继续改';

  @override
  String get workbenchWorkspaceGuideVibeBody =>
      '要继续 vibe coding，回到首页大输入框说需求。工作台 Skill 会判断是创建新项目、扩充项目工具、调整显示页，还是对当前项目做热更新。';

  @override
  String get workbenchWorkspaceGuideExtendTitle => '扩充后端工具';

  @override
  String get workbenchWorkspaceGuideExtendBody =>
      '新增能力时先定义 toolId、输入输出 schema、executorKind、持久化文件和前端触发位置，再通过工作台接口注册项目工具；不要手写 registry 文件。';

  @override
  String workbenchWorkspaceProjectApiStats(int apiCount, int executionCount) {
    return '$apiCount 个工具 · 已执行 $executionCount 次';
  }

  @override
  String get workbenchSubtitle => '一个 OOB 原生项目示例，用来验证项目工具注册、状态持久化和工作台内显示。';

  @override
  String get workbenchVibeSubtitle => '提示词生成的原生前端、项目工具和工作区文件在 OOB 内保持关联。';

  @override
  String get workbenchProjectDisplay => '项目显示';

  @override
  String get workbenchProjectSection => '项目';

  @override
  String get workbenchProjectIdLabel => '项目 ID';

  @override
  String get workbenchRouteLabel => '路由';

  @override
  String get workbenchSpacePathLabel => 'Space 路径';

  @override
  String get workbenchPageIdsLabel => '页面';

  @override
  String get workbenchDevelopmentMode => '开发模式';

  @override
  String get workbenchProjectRegistryPath => '项目注册表';

  @override
  String get workbenchApiRegistryPath => '工具注册表';

  @override
  String get workbenchProjectFilePath => '项目文件';

  @override
  String get workbenchDataFilePath => '数据文件';

  @override
  String get workbenchLogFilePath => '工具日志';

  @override
  String get workbenchBackendTools => '后端工具';

  @override
  String get workbenchFrontendBinding => '前后端绑定';

  @override
  String get workbenchCallApi => '调用工具';

  @override
  String get workbenchGeneratedFrontend => '生成的前端';

  @override
  String get workbenchGeneratedFrontendSubtitle =>
      '打开提示词生成页面应该挂载的 OOB 原生预览容器。它和 AI 层共用同一组项目工具与持久化数据。';

  @override
  String get workbenchOpenGeneratedFrontend => '打开生成前端';

  @override
  String get workbenchPreviewClose => '关闭预览';

  @override
  String get workbenchToolList => '项目工具';

  @override
  String get workbenchProjectControlSubtitle =>
      '这里只展示已注册的业务工具。项目创建和打开仍属于 OOB 工作台控制面。';

  @override
  String get workbenchOpenWorkspace => '打开工作区';

  @override
  String get workbenchApiEmpty => '暂无工具';

  @override
  String get workbenchToolListDefaultTodo => '项目工具点击了同一个后端';

  @override
  String workbenchToolExecutionCount(int count) {
    return '已执行 $count 次';
  }

  @override
  String get workbenchProjectDefaultEntity => '条目';

  @override
  String workbenchProjectCreateTitle(String entity) {
    return '新增 $entity';
  }

  @override
  String workbenchProjectInputHint(String entity) {
    return '输入 $entity 名称';
  }

  @override
  String workbenchProjectItemsTitle(String entity) {
    return '$entity 列表';
  }

  @override
  String workbenchProjectEmpty(String entity) {
    return '暂无 $entity';
  }

  @override
  String get workbenchProjectActiveItems => '进行中';

  @override
  String get workbenchProjectArchivedItems => '已归档';

  @override
  String get workbenchProjectEditAction => '编辑';

  @override
  String get workbenchProjectEditTitle => '编辑条目';

  @override
  String get workbenchProjectArchiveAction => '归档';

  @override
  String get workbenchProjectMissingCreateApi => '这个项目没有可用的新增工具';

  @override
  String get workbenchProjectMissingUpdateApi => '这个项目没有可用的编辑工具';

  @override
  String get workbenchProjectMissingArchiveApi => '这个项目没有可用的归档工具';

  @override
  String workbenchProjectInputRequired(String entity) {
    return '请先输入 $entity';
  }

  @override
  String workbenchProjectItemCreated(String entity) {
    return '$entity 已新增';
  }

  @override
  String workbenchProjectItemUpdated(String entity) {
    return '$entity 已保存';
  }

  @override
  String workbenchProjectItemArchived(String entity) {
    return '$entity 已归档';
  }

  @override
  String get workbenchLoadFailed => '加载失败';

  @override
  String get workbenchUnknownTool => '工作台工具执行失败';

  @override
  String get workbenchStatusOpen => '等待处理';

  @override
  String get workbenchStatusFinished => '已归档';

  @override
  String get workbenchAssistantName => '小万';

  @override
  String get workbenchAssistantTooltip => '打开小万';

  @override
  String get workbenchAssistantPromptHint => '说出你想实时调整的地方';

  @override
  String get workbenchAssistantSend => '热更新当前项目';

  @override
  String get workbenchAssistantApplied => '项目已热更新';

  @override
  String get workbenchAssistantPromptRequired => '请先输入要调整的内容';

  @override
  String get workbenchAssistantNoProject => '请先选择一个项目';

  @override
  String get workbenchAssistantHotUpdateFailed => '项目热更新失败';

  @override
  String get workbenchProjectModeTitle => '项目';

  @override
  String get workbenchFlutterDisplay => 'Flutter 显示页';

  @override
  String get workbenchFlutterEvalTitle => 'Flutter 运行页';

  @override
  String get workbenchFlutterEvalNoSource =>
      '当前项目还没有可运行的 Flutter 源码。请在 frontend/flutter/lib/main.dart 定义 OobProjectWidget。';

  @override
  String get workbenchFlutterEvalCompileFailed =>
      'Flutter 源码暂不可运行，请回到输入框让小万修复这个页面。';

  @override
  String get workbenchProjectSwitcher => '切换项目';

  @override
  String get workbenchProjectGenerateTitle => '项目容器';

  @override
  String get workbenchProjectGenerateSubtitle =>
      '这里只选择和打开项目容器。创建、编辑和热更新继续回到首页大输入框，由当前激活的项目 toolbox 承接。';

  @override
  String get workbenchProjectPromptHint => '回到首页输入项目需求';

  @override
  String get workbenchProjectDefaultPrompt =>
      '我想创建一个简单的 todolist 管理系统，要求可以增加 todo，归档 todo';

  @override
  String get workbenchProjectGenerateButton => '回到首页继续';

  @override
  String get workbenchInputProjectTooltip => '打开项目工作台';

  @override
  String get workbenchGeneratedTodoProjectName => 'Todo List 工作台';

  @override
  String get workbenchPromptSeedAddTodo => '验证可以增加 todo';

  @override
  String get workbenchPromptSeedArchiveTodo => '验证可以归档 todo';

  @override
  String get workbenchProjectPlanTitle => '拆分计划';

  @override
  String get workbenchProjectPlanProject => '创建项目注册和可编辑工作区';

  @override
  String get workbenchProjectPlanFrontend => '生成 OOB 原生 Flutter 前端';

  @override
  String get workbenchProjectPlanApi => '注册 AI/UI 共用项目工具';

  @override
  String get workbenchProjectPlanData => '写入持久化数据和工具日志';

  @override
  String get workbenchUseMode => '使用模式';

  @override
  String get workbenchDebugMode => 'Debug 模式';

  @override
  String get workbenchDisplaysTitle => '页面';

  @override
  String workbenchDisplayCount(int count) {
    return '$count 个前端';
  }

  @override
  String get workbenchUnnamedDisplay => '未命名前端';

  @override
  String get workbenchOpenDisplay => '打开这个前端';

  @override
  String get workbenchDebugDisplay => '调试这个前端';

  @override
  String get workbenchProjectCurrentTitle => '项目使用台';

  @override
  String get workbenchProjectCurrentSubtitle =>
      '默认打开前端会回到首页；调试打开会回到工作台。热更新通过首页大输入框和当前激活项目完成。';

  @override
  String get workbenchProjectModeCreateTitle => 'Vibe 项目入口';

  @override
  String get workbenchProjectModeSubtitle => '这里只显示项目和当前激活项。';

  @override
  String get workbenchProjectActiveTitle => '当前项目';

  @override
  String get workbenchProjectActiveEmpty => '尚未激活项目';

  @override
  String get workbenchProjectListTitle => '项目';

  @override
  String get workbenchProjectDetailTitle => '项目';

  @override
  String get workbenchProjectModeCreateButton => '去首页创建';

  @override
  String get workbenchProjectCreateFromHome => '回到首页输入框，直接说创建项目或描述你想做的页面。';

  @override
  String get workbenchProjectModeProjectsTitle => '当前工具';

  @override
  String get workbenchProjectApiForProject => '工具';

  @override
  String get workbenchProjectModeOpen => '打开项目';

  @override
  String get workbenchProjectModeEmpty => '暂无工作台项目';

  @override
  String get workbenchProjectModeLoadFailed => '项目模式加载失败';

  @override
  String get workbenchProjectPromptRequired => '请先输入项目需求';

  @override
  String get workbenchProjectGenerated => '项目已生成';

  @override
  String get workbenchDeleteProject => '删除项目';

  @override
  String get workbenchDeleteProjectTitle => '删除项目';

  @override
  String workbenchDeleteProjectMessage(String projectId) {
    return '确定删除 $projectId？它会移除项目注册、业务工具注册和工作区项目文件。';
  }

  @override
  String get workbenchDeleteProjectCancel => '取消';

  @override
  String get workbenchDeleteProjectConfirm => '删除';

  @override
  String get workbenchDeleteProjectFailed => '项目删除失败';

  @override
  String get workbenchProjectDeleted => '项目已删除';

  @override
  String get workbenchProjectIdRequired => '请输入项目 ID';

  @override
  String get workbenchProjectCreated => '项目已创建';

  @override
  String get workbenchProjectInfoTitle => '项目信息';

  @override
  String get workbenchProjectInfoDisplayTitle => '显示入口';

  @override
  String get workbenchProjectInfoSourceTitle => '源码规格';

  @override
  String get workbenchProjectInfoSourceValue =>
      'README.md / frontend/page_spec.json / backend/api_spec.json';

  @override
  String get workbenchProjectInfoRuntimeTitle => '运行态';

  @override
  String get workbenchProjectInfoRuntimeValue =>
      'data/todos.json / logs/api_calls.jsonl';

  @override
  String get workbenchDebugToolsTitle => '调试工具';

  @override
  String get workbenchDebugHotUpdate => '悬浮小万实时修改当前项目';

  @override
  String get workbenchDebugHotUpdateHomeInput =>
      '回到首页大输入框描述修改，Agent 会带着当前项目 toolbox 执行热更新';

  @override
  String get workbenchDebugFloatingXiaowan =>
      '悬浮小万可以带上当前前端上下文，选择页面信息后调用 workbench_project_hot_update 迭代这个项目。';

  @override
  String get workbenchDebugVlmInput =>
      'VLM 输入也可以附带当前显示页、可见状态、选中控件或截图摘要，作为 frontendContext 交给项目 Skill。';

  @override
  String workbenchDebugContextProject(String projectId) {
    return '项目 $projectId';
  }

  @override
  String workbenchDebugContextDisplay(String displayId) {
    return '显示页 $displayId';
  }

  @override
  String workbenchDebugContextRoute(String route) {
    return '路由 $route';
  }

  @override
  String get workbenchDebugVlmTest => '根据 VLM 模拟人类操作测试';

  @override
  String get workbenchDebugComingSoon => '待接入';

  @override
  String get workbenchAnnotationTitle => '标注画布';

  @override
  String get workbenchAnnotationDrawMode => '画笔';

  @override
  String get workbenchAnnotationBrowseMode => '浏览页面';

  @override
  String get workbenchAnnotationUndo => '撤销';

  @override
  String get workbenchAnnotationClear => '清空';

  @override
  String get workbenchAnnotationApply => '应用标注';

  @override
  String get workbenchAnnotationApplying => '应用中';

  @override
  String get workbenchAnnotationPromptHint => '补充修改说明，例如：把这里改成主按钮';

  @override
  String get workbenchAnnotationNoStrokes => '先在页面上画出要修改的区域';

  @override
  String get workbenchAnnotationNoShape => '未标注';

  @override
  String workbenchAnnotationShapeCount(int count) {
    return '已标注 $count 笔';
  }

  @override
  String get workbenchAnnotationDefaultPrompt => '根据画布标注调整当前项目前端。';

  @override
  String get workbenchAnnotationHotUpdateSuccess => '已把标注应用到项目';

  @override
  String get workbenchAnnotationHotUpdateFailed => '标注热更新失败';

  @override
  String get workbenchExportProjectPackage => '导出分发包';

  @override
  String get workbenchProjectExportFailed => '项目导出失败';

  @override
  String workbenchProjectExported(String packageName) {
    return '已导出 $packageName';
  }

  @override
  String workbenchProjectExportPath(String path) {
    return '导出位置：$path';
  }

  @override
  String get workbenchAndroidAssetsTitle => '应用';

  @override
  String get workbenchAndroidSourceHint =>
      '输入 APK 或 Android 项目路径，例如 /workspace/apps/demo.apk';

  @override
  String get workbenchAndroidIngestButton => '导入到当前项目';

  @override
  String get workbenchAndroidSourceRequired => '请输入 Android 应用或项目路径';

  @override
  String get workbenchAndroidIngestFailed => 'Android 资产导入失败';

  @override
  String workbenchAndroidIngested(String name) {
    return '已导入 $name';
  }

  @override
  String get workbenchAndroidAssetsEmpty => '暂无导入的 Android 应用或项目';

  @override
  String get workbenchProjectActivateFailed => '项目激活失败';

  @override
  String workbenchProjectActivated(String projectName) {
    return '已激活 $projectName';
  }

  @override
  String get workbenchProjectDeactivateFailed => '项目取消激活失败';

  @override
  String get workbenchProjectDeactivated => '已取消激活项目';

  @override
  String get workbenchActivateProject => '激活项目';

  @override
  String get workbenchDeactivateProject => '取消激活';

  @override
  String get workbenchEditProjectLabels => '编辑名称';

  @override
  String get workbenchProjectNameLabel => '名称';

  @override
  String get workbenchProjectShortNameLabel => '简写';

  @override
  String get workbenchSaveProjectLabels => '保存';

  @override
  String get workbenchProjectNameRequired => '请输入名称';

  @override
  String get workbenchProjectLabelsUpdated => '已保存';

  @override
  String get workbenchProjectLabelsUpdateFailed => '保存失败';

  @override
  String get workbenchProjectMoreActions => '更多操作';

  @override
  String get workbenchActiveProject => '已激活';

  @override
  String get workbenchInactiveProject => '未激活';

  @override
  String get workbenchContinueInHome => '激活项目';

  @override
  String get workbenchProjectHelpTooltip => '项目工作台说明';

  @override
  String get workbenchProjectHelpTitle => '项目工作台';

  @override
  String get workbenchProjectHelpHomeInput => '创建、编辑和热更新都在首页大输入框里完成。';

  @override
  String get workbenchProjectHelpSelect => '这里选择一个项目，把它激活为 Agent 当前工作环境。';

  @override
  String get workbenchProjectHelpDisplays => '每个项目可以有多个 Flutter 前端显示页，从这里打开容器。';

  @override
  String get workbenchProjectHelpApis =>
      '项目工具是当前项目的业务 toolbox，和 MCP tools 分开管理。';

  @override
  String workbenchActiveProjectChip(String projectName) {
    return '项目：$projectName';
  }

  @override
  String workbenchProjectSummaryGeneric(String entityName) {
    return '管理 $entityName 记录，并保留状态和快捷操作。';
  }

  @override
  String workbenchAndroidAssetCount(int count) {
    return '$count 个 Android 资产';
  }

  @override
  String workbenchProjectItemCount(int activeCount, int archivedCount) {
    return '$activeCount 条进行中 / $archivedCount 条归档';
  }

  @override
  String workbenchApiCount(int count) {
    return '$count 个工具';
  }

  @override
  String get workbenchPhilosophyBadge => '了解工作台';

  @override
  String get workbenchPhilosophyClose => '关闭';

  @override
  String get workbenchPhilosophyTitle => 'AI 产品展示工作台';

  @override
  String get workbenchPhilosophyTagline => '让 AI 的结果立刻变成可看、可点、可继续修改的界面';

  @override
  String get workbenchPhilosophySubtitle =>
      'Workbench 不是模板生成器，而是 AI 产品的展示与运行层。Agent 产出的报告、数据、状态和操作会落到 Project 中，通过 HTML、Markdown 或 Flutter 显示，并通过 Project API 连接手机能力与持久化数据。';

  @override
  String get workbenchPhilosophyPillarsTitle => '当前核心闭环';

  @override
  String get workbenchPhilosophyComposable => '显示层';

  @override
  String get workbenchPhilosophyComposableDesc =>
      'HTML / Markdown / Flutter 都是 Project Display，用来承载 AI 输出';

  @override
  String get workbenchPhilosophyAIDriven => '交互层';

  @override
  String get workbenchPhilosophyAIDrivenDesc =>
      '用户点击、填写、选择后，通过 Project API 触发下一步 Agent 或工具';

  @override
  String get workbenchPhilosophyMobileNative => '能力层';

  @override
  String get workbenchPhilosophyMobileNativeDesc =>
      '需要操控手机、读屏、文件、脚本时，再走 OOB 原生能力';

  @override
  String get workbenchPhilosophyStrengthsTitle => '三件事';

  @override
  String get workbenchPhilosophyBackendTitle => 'Project API';

  @override
  String get workbenchPhilosophyBackendDesc =>
      '白名单工具、持久化数据、运行日志和手机能力统一挂到 Project 上';

  @override
  String get workbenchPhilosophyFrontendTitle => 'Display';

  @override
  String get workbenchPhilosophyFrontendDesc =>
      '普通交互 UI 默认 HTML；报告用 Markdown / HTML；Flutter 保留为容器和受限补充';

  @override
  String get workbenchPhilosophyRuntimeTitle => 'Hot update';

  @override
  String get workbenchPhilosophyRuntimeDesc =>
      '用户一句话或一次选区标注后，AI 只改必要的前端文件或 API，右侧立即刷新';

  @override
  String get workbenchPhilosophyHowToTitle => '使用方式';

  @override
  String get workbenchPhilosophyStep1Label => '生成';

  @override
  String get workbenchPhilosophyStep1Desc => 'Agent 创建 Project，写入 API 与显示文件';

  @override
  String get workbenchPhilosophyStep2Label => '查看';

  @override
  String get workbenchPhilosophyStep2Desc =>
      '右侧 Workspace 直接预览 HTML / Markdown / Flutter';

  @override
  String get workbenchPhilosophyStep3Label => '修改';

  @override
  String get workbenchPhilosophyStep3Desc => '用悬浮输入或标注提出修改，Project 热更新';

  @override
  String get workbenchPhilosophyActivateHint =>
      '激活项目后，右侧 Workspace 显示它的 Display；继续输入或标注会作为上下文传给 hot update。';

  @override
  String get sourceTextf9dfa89402 => '小万悬浮窗';

  @override
  String get sourceTextea6631ac86 => '关闭后不再显示桌面悬浮球、半屏输入层和运行胶囊';

  @override
  String get sourceText60d33fd58f => '小万悬浮窗已开启';

  @override
  String get sourceText9803e0f8d8 => '小万悬浮窗已关闭';

  @override
  String get sourceText8ed5fe74f6 => '设置悬浮窗失败';

  @override
  String get sourceText2a4a4de806 => '手动';

  @override
  String get sourceText76c9741888 => 'Shizuku 权限';

  @override
  String get sourceText5e04ad1c9a => '正在调用内嵌 Alpine 终端执行命令';

  @override
  String get sourceTextc0b7ed8600 => '正在执行内嵌 Alpine 终端命令';

  @override
  String get sourceText60cf09e22d => '终端输出更新中';

  @override
  String get sourceText140c80c696 => '🎉Hi，我是小万，我会做很多事，让我展示给你下！';

  @override
  String get sourceText82347f1be8 => 'Hi，我是小万';

  @override
  String get sourceText5167632783 => '你的 AI 助手，随时准备就绪';

  @override
  String get sourceText63a921a287 => '无需网络，完全免费';

  @override
  String get sourceText112e197134 =>
      '数据完全留在设备上，不会发送到任何服务器。对话内容、个人偏好等敏感信息始终由你掌控。';

  @override
  String get sourceText8de8b69cc9 =>
      '无需网络连接即可运行 AI 助手。无论在飞机上、地铁里还是偏远地区，随时随地可用。';

  @override
  String get sourceTexteac537b43e => '无需 API 费用或订阅。模型下载后可无限次使用，没有任何隐藏费用。';

  @override
  String get sourceTexte8b806ace2 =>
      '端侧模型较小，回复质量不如云端模型，暂不支持复杂 Agent 任务，适合日常对话与问答。';

  @override
  String get sourceText7e1cc2fc3f => '换一换';

  @override
  String get sourceText63e272f624 => '小万正在思考...';

  @override
  String get sourceTextd9f594509d => '总结中';

  @override
  String get sourceText9384e034e5 => '总结如下';

  @override
  String get sourceText3e44b2a933 => '全选';

  @override
  String get sourceText4edd1d0087 => '复制';

  @override
  String get sourceTextb56d9ac6c5 => '确认';

  @override
  String get sourceTextf526c89937 => '确定';

  @override
  String get sourceText4d0b3bb4e9 => '请稍候...';

  @override
  String get sourceTextee5037d25d => '保存并发送';

  @override
  String get sourceTextbe15d6f28c => '未设置模型';

  @override
  String get sourceText01047404ef => '发现新版本';

  @override
  String get sourceText1722589489 => '打开终端';

  @override
  String get sourceText649fc10b46 => '管理终端环境变量';

  @override
  String get sourceTextd8f03e50ea => '打开当前会话浏览器';

  @override
  String get sourceTextc1c986937d => '当前会话还没有可用的浏览器会话';

  @override
  String get sourceText31b7c8d175 => '纯聊天';

  @override
  String get sourceText7cda072d45 => '普通';

  @override
  String get sourceText17e83cc25e => '今天';

  @override
  String get sourceText59c4fcb09e => '昨天';

  @override
  String get sourceText1f425b6bf0 => '执行中';

  @override
  String get sourceText6c189aad4d => '执行成功';

  @override
  String get sourceText9746cfc7d2 => '执行失败';

  @override
  String get sourceTextd0de773436 => '等待执行';

  @override
  String get sourceText2029839d84 => '总结';

  @override
  String get sourceText6c2b60f0ee => '识图';

  @override
  String get sourceTexte9649f84f9 => '未知类型';

  @override
  String get sourceText756eae0324 => '正在回复...';

  @override
  String get sourceText292eea5849 => '永不';

  @override
  String get sourceText08d65bdbc3 => '每日';

  @override
  String get sourceTexta93b55d8bf => '每周';

  @override
  String get sourceText24aedc3608 => '每月';

  @override
  String get sourceText4a9ee561f9 => '每年';

  @override
  String get sourceText89b4aa6364 => '时间';

  @override
  String get sourceTextb6fed9af83 => '日期';

  @override
  String get sourceText6e708ba759 => '重复';

  @override
  String get sourceTextc1cb3fc29f => '任务选项';

  @override
  String get sourceText39797f7a92 => '请选择一个任务';

  @override
  String get sourceTexte03304491a => '请选择你想执行的任务';

  @override
  String get sourceTextb4a7ea5533 => '请选择一个应用程序';

  @override
  String get sourceText1354374f76 => '已过期';

  @override
  String get sourceText36d2d01f31 => '即将执行';

  @override
  String get sourceText13794e1f43 => '好，我来帮你完成';

  @override
  String get sourceTextbaa298fbe1 => '用户操作';

  @override
  String get sourceText86e8d12a79 => '删除成功';

  @override
  String get sourceText9abb465039 => '修改失败';

  @override
  String get sourceTextf8913eb433 => '修改成功';

  @override
  String get sourceText65fdeb927b => '桌面';

  @override
  String get sourceText322eceb785 => '内存中';

  @override
  String get sourceTextf90d5c751e => '云内存中';

  @override
  String get sourceText7e68eb622d => '保存成功';

  @override
  String get sourceText6a6b660ba8 => '编辑你的消息';

  @override
  String get sourceTextfcbd093292 => '创建';

  @override
  String get sourceText8200c3d50b => '未命名对话';

  @override
  String get sourceText229127ec8d => '折叠全部日期';

  @override
  String get sourceTextbc51af6ffc => '展开全部日期';

  @override
  String get sourceText72be511e05 => '最近执行';

  @override
  String get sourceText818a1f7be3 => '暂无总结内容';

  @override
  String get sourceTextc76c74e809 => '检查更新失败';

  @override
  String get sourceTextae4535ef13 => '已是最新版';

  @override
  String get sourceText00f512b5e8 => '检查 GitHub Release 获取最新版本';

  @override
  String get sourceText9afc832d99 => '查看新版本';

  @override
  String get sourceTexta6df38586d => '检查更新';

  @override
  String get sourceText8ff0439ff9 => '已关闭思考';

  @override
  String get sourceTextd9d4d4e7dd => '请求日志';

  @override
  String get sourceTexta8ce402665 => '运行日志';

  @override
  String get sourceText4c685c0454 => '使用手册';

  @override
  String get sourceText5060421d15 => '概览';

  @override
  String get sourceText9f14a3f4dd => '最近记录';

  @override
  String get sourceTextb01090a29c => '最近 10 条 AI 请求，按时间倒序展示。';

  @override
  String get sourceTextc740eb5be5 => '点击条目展开查看请求与响应正文。';

  @override
  String get sourceTextcb80eb03ea => '最近 200 条错误和崩溃日志，按时间倒序展示。';

  @override
  String get sourceText8334b58cfa => '含堆栈的条目可展开查看。';

  @override
  String get sourceTextfe12b789bf => '导出运行日志';

  @override
  String get sourceText88f6dbf1a3 => '已复制全部运行日志';

  @override
  String get sourceText8b06115d35 => '导出运行日志失败';

  @override
  String get sourceTextd6c8084d07 => '崩溃';

  @override
  String get sourceText367ff5ddd2 => '总数';

  @override
  String get sourceText71bd34d484 => '最近一条';

  @override
  String get sourceText41654e0268 => '基础信息';

  @override
  String get sourceText7364999103 => '载荷';

  @override
  String get sourceTextd70d425039 => '保存中...';

  @override
  String get sourceTextdbb4430dc0 => '未选择文件';

  @override
  String get sourceText1e620e20a1 => '远程地址';

  @override
  String get sourceTextdde21b2cec => '后台运行权限';

  @override
  String get sourceText135f1636e4 => '应用列表读取';

  @override
  String get sourceTextf80103fee9 => '无障碍辅助权限';

  @override
  String get sourceTextd78cde076b => '已开启';

  @override
  String get sourceText13ec170881 => '去开启';

  @override
  String get sourceText291952a2ab => '清除缓存';

  @override
  String get sourceText3d0c8b9d9f => '小万可以在陪伴时更了解您的喜好';

  @override
  String get sourceText86890292b6 => '小万可在屏幕中实时活动，随时给予陪伴';

  @override
  String get sourceTexta86909c7ea => '小万可以知道能帮你做什么事情';

  @override
  String get sourceText56735a4ab7 => '小万执行任务时，需要给予我操作的权限';

  @override
  String get sourceText99ad612dd1 => '设置权限';

  @override
  String get sourceTextaef926661d => '请放心，这些权限你随时可以收回';

  @override
  String get sourceText02a75489b2 => '查看并配置无障碍、悬浮窗、Shizuku 等权限';

  @override
  String get sourceText75b40989f3 => '权限检查中...';

  @override
  String get sourceText2599599947 => '继续任务';

  @override
  String get sourceText14411ce362 => '继续任务仅要求';

  @override
  String get sourceTextf739c7d4a8 => 'Termux 终端能力';

  @override
  String get sourceText98bd36febc => '可选，允许 Agent 通过 Termux 执行终端命令';

  @override
  String get sourceText53e32830a5 => '可选';

  @override
  String get sourceTexte5d269502c => '让小万带你执行一次任务吧！';

  @override
  String get sourceText1aca95f544 => '其中 Termux 终端能力为可选项，未开启也不影响基础自动化';

  @override
  String get sourceText3bf179d8d0 => '未绑定';

  @override
  String get sourceText2a30881946 => '清除绑定';

  @override
  String get sourceTexta191935bc6 => '恢复默认';

  @override
  String get sourceText8988c04935 =>
      '点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；Voice 的音色与自动播放可通过调节按钮展开。';

  @override
  String get sourceText2415f124bd => 'AI 响应完成后自动播放';

  @override
  String get sourceTextc4301894a2 => '音色';

  @override
  String get sourceTextc0ae8ba446 =>
      '例如：default_zh / mimo_default / default_en';

  @override
  String get sourceTexta4ce420c69 => '风格';

  @override
  String get sourceText6614801dcd => '自定义补充';

  @override
  String get sourceText558a2f3fd0 => '唱歌模式下不支持附加风格';

  @override
  String get sourceTextfa12d9ef1b => '例如：更温柔、节奏慢一点、偏播客感';

  @override
  String get sourceText2601f9e3cb => '收起语音设置';

  @override
  String get sourceTextbc2c7387f0 => '展开语音设置';

  @override
  String get sourceText6a7d5cd91d => '没有匹配的模型';

  @override
  String get sourceText7b0de927a6 => '搜索模型 ID';

  @override
  String get sourceTexte5463e3a94 => '请先在模型提供商页配置 Provider';

  @override
  String get sourceText13c9595745 => '该 Provider 暂无可选模型';

  @override
  String get sourceText90bfe72640 => '已进入仅聊天模式';

  @override
  String get sourceText9c1153036d => '已退出仅聊天模式';

  @override
  String get sourceTextd1a19c24c7 => '搜索技能名称或描述';

  @override
  String get sourceTextd636ae3e01 => '未找到匹配的技能';

  @override
  String get sourceTexte4d8c16cd2 => '流式';

  @override
  String get sourceText36e8d9631f => '非流式';

  @override
  String get sourceText0e84ef42ae => '请求地址';

  @override
  String get sourceText4d150364fe => '请求方法';

  @override
  String get sourceTexta38a81c9d5 => '错误信息';

  @override
  String get sourceText0228e74add => '请求 JSON';

  @override
  String get sourceText9f062a0dac => '响应 JSON';

  @override
  String get sourceTexte2d53a6d3a => '重试';

  @override
  String get sourceText661b2db84d => '加载请求日志失败';

  @override
  String get sourceTextfa604c3dba => '最近还没有 AI 请求日志';

  @override
  String get sourceTexta22889b61d => '加载运行日志失败';

  @override
  String get sourceText71a159aa14 => '暂无运行日志';

  @override
  String get sourceText7b15e5e8e7 => '清除';

  @override
  String get sourceTextfb57d700b9 => 'AI 请求';

  @override
  String get sourceText7a42fe12dc => '次对话';

  @override
  String get sourceTextadf4707731 => '天连续';

  @override
  String get sourceText0fe8227aa4 => '无对话';

  @override
  String get sourceText7a54a1229e => '暂无 Token 消耗数据';

  @override
  String get sourceTexte8666c377c => '本地';

  @override
  String get sourceText565481c9be => '云端';

  @override
  String get sourceText54c727b452 => '无消耗';

  @override
  String get sourceText7fe4999970 => '长期记忆未就绪';

  @override
  String get sourceTextb87a8a83f5 => '完成记忆初始化后，这里会展示跨会话沉淀的偏好与事实。';

  @override
  String get sourceTextb92a2068aa => '长期记忆暂时不可用';

  @override
  String get sourceTextd3a2b13fc2 => '长期记忆还是空的';

  @override
  String get sourceTexte8c59faf6d => '当 Agent 主动写入长期偏好后，这里会逐渐丰富起来。';

  @override
  String get sourceText495c0debaf => '新增长期记忆';

  @override
  String get sourceText4398777297 => '刷新长期记忆';

  @override
  String get sourceText9e636642d6 => '刚刚';

  @override
  String get sourceTextedab852efe => '思考完成';

  @override
  String get sourceText774d85ae0a => '正在思考';

  @override
  String get sourceTexte4b6477e6e => '用时';

  @override
  String get sourceText15fc7643c5 => '准备执行任务...';

  @override
  String get sourceTextd258a63cad => '取消任务';

  @override
  String get sourceText6df9b76521 => '任务已取消';

  @override
  String get sourceText038d05ca8c => '停止工具';

  @override
  String get sourceText4078ac16b6 => '正在停止工具';

  @override
  String get sourceTextcb1115d8c1 => '停止工具调用失败，请稍后重试';

  @override
  String get sourceTexteac987a597 => '无法打开 Agent 管理面板';

  @override
  String get sourceTexte3f4d6bd9d => '关闭悬浮球失败';

  @override
  String get sourceTextf6d7e0312c => '悬浮球已关闭，可在设置里重新开启';

  @override
  String get sourceText066af21f55 => '点开';

  @override
  String get sourceText15197efe93 => '隐藏悬浮球';

  @override
  String get sourceText5d5815647c => '收起';

  @override
  String get sourceText151eeabaf6 => '停止失败';

  @override
  String get sourceText6ef1200428 => '运行中的 Agent';

  @override
  String get sourceText5bd8f4879e => '当前没有后端任务';

  @override
  String get sourceTextfaeb185030 => '当前没有任何 Agent';

  @override
  String get sourceText68685fc5c4 => '停止全部';

  @override
  String get sourceTextf0b2cef7b0 => '没有正在执行的 Agent 后端任务';

  @override
  String get sourceText65fc81e161 => '打开';

  @override
  String get sourceText645fc8d22d => '停止这个 Agent';

  @override
  String get sourceText5e59efab1e => 'Agent 后端空闲。轻点打开管理面板。';

  @override
  String get sourceText0b961ab4d9 => '正在整理方案';

  @override
  String get sourceText91796bb70a => '正在输出';

  @override
  String get sourceText33fe6867a2 => '开始调用工具';

  @override
  String get sourceText76a18aa532 => '工具执行中';

  @override
  String get sourceTextd333e5691f => '工具完成';

  @override
  String get sourceTextcc1f7be0b2 => '等待权限确认';

  @override
  String get sourceTextf7d01365f2 => '等待补充信息';

  @override
  String get sourceText9617084ded => '运行出错';

  @override
  String get sourceText832451d2f4 => '即将完成';

  @override
  String get sourceTextc6dc0ad888 => 'Agent 后端任务';

  @override
  String get sourceTextbdde1def59 => '等待模型响应';

  @override
  String get sourceText3d4d1075e7 => '工具调用';

  @override
  String get sourceTextff06c243d7 => '超时';

  @override
  String get sourceText44e681a374 => '中断';

  @override
  String get sourceText71757f8d79 => '浏览中';

  @override
  String get sourceTextda3d2d1482 => '响应中';

  @override
  String get sourceTextfcb979ef0b => '处理中';

  @override
  String get sourceText7f55a26d7d => '终端';

  @override
  String get sourceText88d650dd4f => '浏览器';

  @override
  String get sourceText81944e48a3 => '提醒';

  @override
  String get sourceText2ecbc11608 => '日历';

  @override
  String get sourceText2a8ce33ff0 => '子任务';

  @override
  String get sourceTexta72ef18d9a => '工具';

  @override
  String get sourceText15ec50fe7d => '[更早记录已省略]';

  @override
  String get sourceTexta5dda12242 => '等待龙虾烹饪';

  @override
  String get sourceText70c53b8ac3 => '配置你的 AI 助手';

  @override
  String get sourceTextf7f58b95a7 => '选择一种方式开始使用小万';

  @override
  String get sourceText1670225703 => '云 AI 服务';

  @override
  String get sourceText90f71a54c8 => '连接 OpenAI、Anthropic 或兼容的 API 服务';

  @override
  String get sourceTextb0253cd034 => '本地模型';

  @override
  String get sourceText10691e242c => '在设备上运行本地 AI，离线可用，隐私安全';

  @override
  String get sourceText1fc1afc5c5 => '继续';

  @override
  String get sourceText184913c0f3 => '跳过，稍后在设置中配置';

  @override
  String get sourceText1e797c0dac => '云 AI 服务配置';

  @override
  String get sourceText79973caeef => '配置云端 AI 服务商，使用更强大的模型能力';

  @override
  String get sourceText993df7d096 => '协议类型';

  @override
  String get sourceText530aafb12a => '例如：我的 OpenAI';

  @override
  String get sourceText10b7d8eccc => '测试连接';

  @override
  String get sourceTexteb06635875 => '连接成功';

  @override
  String get sourceText523e40a074 => '发现';

  @override
  String get sourceText674373aef1 => '个模型';

  @override
  String get sourceText2c056f182f => '连接失败';

  @override
  String get sourceTextd15ae9ad81 => '在设备上运行本地 AI';

  @override
  String get sourceText4bbe706f8d => '优势';

  @override
  String get sourceText37dea6b39e => '隐私安全';

  @override
  String get sourceText6bcfca9d58 => '数据完全留在设备上，不会发送到任何服务器';

  @override
  String get sourceText270d12d95b => '离线可用';

  @override
  String get sourceText97fcfbb5dd => '无需网络连接，随时随地使用 AI 助手';

  @override
  String get sourceTextdd70b93ad6 => '完全免费';

  @override
  String get sourceText5d4061aac5 => '无需 API 费用或订阅，没有使用限制';

  @override
  String get sourceText498c2879b0 => '局限性';

  @override
  String get sourceTexta597376852 => '性能受限';

  @override
  String get sourceTextb4d10c670a => '端侧模型较小，能力有限，回复质量不如云端模型';

  @override
  String get sourceText390d11af9b => '任务受限';

  @override
  String get sourceText7b5c99ecb8 => '目前无法处理复杂的 Agent 任务，适合简单对话和问答';

  @override
  String get sourceTextea0ef2ae72 => '下一步';

  @override
  String get sourceText68e23d8fac => '下载本地模型';

  @override
  String get sourceText405be21f38 => '推荐模型';

  @override
  String get sourceTextf430f6d1d1 => '根据你的设备推荐的轻量模型，适合日常对话';

  @override
  String get sourceText1b58744dff => '正在加载模型信息...';

  @override
  String get sourceText3f9550508b => '继续下载';

  @override
  String get sourceText4bbcf94739 => '下载完成';

  @override
  String get sourceText33246f6a5e => '完成';

  @override
  String get sourceText11d0241540 => '返回';

  @override
  String get sourceText85d011402f => '暂时无法获取推荐模型';

  @override
  String get sourceText1b2fe43b5e => '请检查网络连接，或稍后在设置中手动下载';

  @override
  String get sourceText2bc19ec67e => '开始体验';

  @override
  String get sourceTextc55627eba1 => 'API Key（可选）';

  @override
  String get sourceText90988df4ff => '浏览模型市场';

  @override
  String get sourceText62b46f24ae => '推荐';

  @override
  String get sourceTextc59773a6a4 => '选择方式';

  @override
  String get sourceTextb62ed716e3 => '特性';
}
