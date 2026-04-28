import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/features/home/pages/alarm_setting/alarm_setting_page.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/features/home/pages/companion_setting/companion_setting_page.dart';
import 'package:ui/features/home/pages/chat_history/chat_history_page.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_detail_page.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_page.dart';
import 'pages/authorize/authorize_page.dart';
import 'pages/authorize/authorize_page_args.dart';
import 'pages/chat/chat_page.dart';
import 'pages/command_overlay/command_overlay.dart';
import 'pages/edit_profile/edit_profile_page.dart';
import 'pages/settings/workspace_memory_setting_page.dart';
import 'pages/settings/background_setting_page.dart';
import 'pages/settings/storage_usage_page.dart';
import 'pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'pages/omnibot_workspace/omnibot_workspace_page.dart';
import 'pages/webview/webview_page.dart';
import 'pages/settings/settings_page.dart';
import 'pages/mcp/remote_mcp_servers_page.dart';
import 'pages/skill_store/skill_store_page.dart';
import 'pages/termux_setting/termux_setting_page.dart';
import 'pages/scene_model_setting/scene_model_setting_page.dart';
import 'pages/vlm_model_setting/vlm_model_setting_page.dart';
import 'pages/local_models/local_models_page.dart';

/// Home模块路由配置
const String kNativeRouteFlag = '__from_native__';

ConversationMode _parseConversationMode(String? rawValue) {
  return ConversationMode.fromStorageValue(rawValue);
}

ConversationThreadTarget? _parseChatThreadTarget(GoRouterState state) {
  final queryConversationId =
      state.uri.queryParameters['conversationId']?.trim() ?? '';
  final queryMode = _parseConversationMode(state.uri.queryParameters['mode']);
  final queryRequestKey = state.uri.queryParameters['requestKey']?.trim();
  if (queryConversationId.isNotEmpty) {
    if (queryConversationId == 'new' || queryConversationId == '__new__') {
      return ConversationThreadTarget.newConversation(
        mode: queryMode,
        fromNativeRoute: true,
        requestKey: queryRequestKey?.isEmpty == true ? null : queryRequestKey,
      );
    }
    final conversationId = int.tryParse(queryConversationId);
    if (conversationId != null) {
      return ConversationThreadTarget.existing(
        conversationId: conversationId,
        mode: queryMode,
        fromNativeRoute: true,
        requestKey: queryRequestKey?.isEmpty == true ? null : queryRequestKey,
      );
    }
  }

  final extra = state.extra;
  if (extra is ConversationThreadTarget) {
    return extra;
  }

  final argsFromExtra = extra as List<String>? ?? const <String>[];
  if (argsFromExtra.isEmpty) {
    return null;
  }

  final first = argsFromExtra.first.trim();
  final requestKey = argsFromExtra
      .skip(1)
      .map((item) => item.trim())
      .firstWhere(
        (item) =>
            item.isNotEmpty &&
            item != kNativeRouteFlag &&
            !item.startsWith('mode=') &&
            ConversationMode.values.every(
              (mode) => mode.storageValue != item.toLowerCase(),
            ),
        orElse: () => '',
      );
  final modeRaw = argsFromExtra
      .skip(1)
      .map((item) => item.trim())
      .firstWhere(
        (item) =>
            item.startsWith('mode=') ||
            ConversationMode.values.any(
              (mode) => mode.storageValue == item.toLowerCase(),
            ),
        orElse: () => '',
      );
  final mode = modeRaw.startsWith('mode=')
      ? _parseConversationMode(modeRaw.substring(5))
      : _parseConversationMode(modeRaw);
  final fromNativeRoute = argsFromExtra.contains(kNativeRouteFlag);

  if (first == 'new' || first == '__new__') {
    return ConversationThreadTarget.newConversation(
      mode: mode,
      fromNativeRoute: fromNativeRoute,
      requestKey: requestKey.isEmpty ? null : requestKey,
    );
  }

  final conversationId = int.tryParse(first);
  if (conversationId == null) {
    return null;
  }
  return ConversationThreadTarget.existing(
    conversationId: conversationId,
    mode: mode,
    fromNativeRoute: fromNativeRoute,
    requestKey: requestKey.isEmpty ? null : requestKey,
  );
}

List<GoRoute> homeRoutes = [
  // 兼容旧首页路由，统一落到聊天页
  GoRoute(
    path: '/home/home',
    name: 'home/home',
    builder: (context, state) {
      return ChatPage(threadTarget: _parseChatThreadTarget(state));
    },
  ),
  GoRoute(
    path: '/home/blank_page',
    name: 'home/blank_page',
    builder: (context, state) => const SizedBox.shrink(),
  ),

  // 聊天页
  GoRoute(
    path: '/home/chat',
    name: 'home/chat',
    builder: (context, state) {
      return ChatPage(threadTarget: _parseChatThreadTarget(state));
    },
  ),

  // 聊天归档页（保留旧路径兼容）
  GoRoute(
    path: '/home/chat_history',
    name: 'home/chat_history',
    builder: (context, state) => const ChatHistoryPage(archivedOnly: true),
  ),
  GoRoute(
    path: '/home/archived_conversations',
    name: 'home/archived_conversations',
    builder: (context, state) => const ChatHistoryPage(archivedOnly: true),
  ),

  // 输入框悬浮窗
  GoRoute(
    path: '/home/command_overlay',
    name: 'home/command_overlay',
    builder: (context, state) {
      final scene = state.uri.queryParameters['scene'];
      return CommandOverlay(scene: scene);
    },
  ),

  GoRoute(
    path: '/home/omnibot_artifact_preview',
    name: 'home/omnibot_artifact_preview',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>? ?? const {};
      return OmnibotArtifactPreviewPage(
        path: (extra['path'] ?? '').toString(),
        uri: extra['uri']?.toString(),
        title: (extra['title'] ?? '文件预览').toString(),
        previewKind: (extra['previewKind'] ?? 'file').toString(),
        mimeType: (extra['mimeType'] ?? 'application/octet-stream').toString(),
        shellPath: extra['shellPath']?.toString(),
        exists: extra['exists'] != false,
        startInEditMode: extra['startInEditMode'] == true,
      );
    },
  ),

  GoRoute(
    path: '/home/omnibot_workspace',
    name: 'home/omnibot_workspace',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>? ?? const {};
      return OmnibotWorkspacePage(
        workspacePath: (extra['workspacePath'] ?? '').toString(),
        workspaceId: extra['workspaceId']?.toString(),
        workspaceShellPath: extra['workspaceShellPath']?.toString(),
      );
    },
  ),

  // 授权页
  GoRoute(
    path: '/home/authorize',
    name: 'home/authorize',
    builder: (context, state) =>
        AuthorizePage(args: state.extra as AuthorizePageArgs?),
  ),

  // 编辑资料页
  GoRoute(
    path: '/home/edit_profile',
    name: 'home/edit_profile',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>?;
      return EditProfilePage(
        initialAvatarIndex: extra?['initialAvatarIndex'] as int?,
        initialNickname: extra?['initialNickname'] as String?,
      );
    },
  ),

  // Webview通用页面
  GoRoute(
    path: '/webview/webview_page',
    name: 'webview/webview_page',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return WebViewPage(
        url: params?['url'] ?? '',
        title: params?['title'] ?? '',
        showAppBar: params?['showAppBar'] ?? true,
        enableJavaScript: params?['enableJavaScript'] ?? true,
        enableZoom: params?['enableZoom'] ?? true,
        showRefreshButton: params?['showRefreshButton'] ?? false,
      );
    },
  ),

  // 设置页
  GoRoute(
    path: '/home/settings',
    name: 'home/settings',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/settings',
      child: const SettingsPage(),
    ),
  ),

  GoRoute(
    path: '/home/permission_guide',
    name: 'home/permission_guide',
    builder: (context, state) =>
        PermissionGuidePage(initialBrand: state.uri.queryParameters['brand']),
  ),

  GoRoute(
    path: '/home/permission_guide/detail',
    name: 'home/permission_guide/detail',
    builder: (context, state) => PermissionGuideDetailPage(
      type: state.uri.queryParameters['type'] ?? '',
      initialBrand: state.uri.queryParameters['brand'],
    ),
  ),

  // 闹钟设置页
  GoRoute(
    path: '/home/alarm_setting',
    name: 'home/alarm_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/alarm_setting',
      child: const AlarmSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/mcp_tools',
    name: 'home/mcp_tools',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/mcp_tools',
      child: const RemoteMcpServersPage(),
    ),
  ),

  GoRoute(
    path: '/home/skill_store',
    name: 'home/skill_store',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/skill_store',
      child: const SkillStorePage(),
    ),
  ),

  GoRoute(
    path: '/home/workspace_memory_setting',
    name: 'home/workspace_memory_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/workspace_memory_setting',
      child: const WorkspaceMemorySettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/background_setting',
    name: 'home/background_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/background_setting',
      child: const BackgroundSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/storage_usage',
    name: 'home/storage_usage',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/storage_usage',
      child: const StorageUsagePage(),
    ),
  ),

  // VLM 模型配置页
  GoRoute(
    path: '/home/vlm_model_setting',
    name: 'home/vlm_model_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/vlm_model_setting',
      child: const VlmModelSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/scene_model_setting',
    name: 'home/scene_model_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/scene_model_setting',
      child: const SceneModelSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/local_models',
    name: 'home/local_models',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/local_models',
      child: LocalModelsPage(
        initialTab: state.uri.queryParameters['tab'] ?? 'service',
      ),
    ),
  ),

  GoRoute(
    path: '/home/termux_setting',
    name: 'home/termux_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/termux_setting',
      child: const TermuxSettingPage(),
    ),
  ),

  // 应用权限授权页
  GoRoute(
    path: '/home/authorize_setting',
    name: 'home/authorize_setting',
    builder: (context, state) => const AuthorizeSettingPage(),
  ),

  // 陪伴权限授权页
  GoRoute(
    path: '/home/companion_setting',
    name: 'home/companion_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/companion_setting',
      child: const CompanionSettingPage(),
    ),
  ),
];
