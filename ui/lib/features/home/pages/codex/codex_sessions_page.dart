import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class CodexSessionsPage extends StatefulWidget {
  const CodexSessionsPage({super.key});

  @override
  State<CodexSessionsPage> createState() => _CodexSessionsPageState();
}

class _CodexSessionsPageState extends State<CodexSessionsPage> {
  List<_CodexSessionSummary> _sessions = const <_CodexSessionSummary>[];
  CodexStatus _status = CodexStatus.disconnected;
  String? _error;
  bool _isLoading = true;
  String? _openingThreadId;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    unawaited(_loadSessions());
  }

  Future<void> _loadSessions() async {
    if (mounted) {
      setState(() {
        _isLoading = true;
        _error = null;
      });
    }
    try {
      var status = await CodexAppServerService.status();
      if (status.ready && !status.connected) {
        status = await CodexAppServerService.connect();
      }
      if (!status.ready) {
        throw StateError(
          status.error ??
              (_isEnglish ? 'Codex runtime is unavailable' : 'Codex 运行时不可用'),
        );
      }
      final payloads = <Map<String, dynamic>>[];
      String? cursor;
      for (var page = 0; page < 8; page++) {
        final payload = await CodexAppServerService.listThreads(
          limit: 100,
          cursor: cursor,
        );
        payloads.add(payload);
        final nextCursor = _stringValue(
          payload['nextCursor'] ??
              payload['next_cursor'] ??
              payload['nextPageCursor'] ??
              payload['next_page_cursor'],
        );
        if (nextCursor == null || nextCursor == cursor) {
          break;
        }
        cursor = nextCursor;
      }
      final sessions = _extractCodexSessions(payloads)
        ..sort((a, b) {
          final byUpdatedAt = (b.updatedAtMs ?? 0).compareTo(
            a.updatedAtMs ?? 0,
          );
          if (byUpdatedAt != 0) return byUpdatedAt;
          return a.title.compareTo(b.title);
        });
      if (!mounted) return;
      setState(() {
        _status = status;
        _sessions = sessions;
        _isLoading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _openSession(_CodexSessionSummary session) async {
    if (_openingThreadId != null) {
      return;
    }
    setState(() {
      _openingThreadId = session.threadId;
    });
    try {
      if (_status.runtime == 'remote' || _status.remoteEnabled) {
        if (!mounted) return;
        GoRouterManager.push(
          '/home/chat',
          extra: ConversationThreadTarget.codexSession(
            threadId: session.threadId,
            runtime: 'remote',
            requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
          ),
        );
        return;
      }
      final response = await CodexAppServerService.resumeThread(
        threadId: session.threadId,
      );
      final conversationId = _intValue(response['conversationId']);
      if (conversationId == null) {
        throw StateError(
          _isEnglish
              ? 'Codex session did not return a local conversation'
              : 'Codex session 未返回本地对话',
        );
      }
      if (!mounted) return;
      GoRouterManager.push(
        '/home/chat',
        extra: ConversationThreadTarget.existing(
          conversationId: conversationId,
          mode: ConversationMode.codex,
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Failed to open session: $error' : '打开 session 失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _openingThreadId = null;
        });
      }
    }
  }

  String get _title => _status.runtime == 'remote'
      ? (_isEnglish ? 'Remote Codex Sessions' : '远程 Codex Sessions')
      : (_isEnglish ? 'Local Codex Sessions' : '本地 Codex Sessions');

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: _title, primary: true),
      body: SafeArea(
        child: RefreshIndicator(onRefresh: _loadSessions, child: _buildBody()),
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator.adaptive());
    }
    if (_error != null) {
      return _CodexSessionsStateView(
        icon: Icons.error_outline_rounded,
        title: _isEnglish ? 'Unable to load sessions' : '无法加载 Sessions',
        subtitle: _error!,
        actionLabel: _isEnglish ? 'Retry' : '重试',
        onAction: () => unawaited(_loadSessions()),
      );
    }
    if (_sessions.isEmpty) {
      return _CodexSessionsStateView(
        icon: Icons.history_rounded,
        title: _isEnglish ? 'No Codex sessions' : '暂无 Codex Sessions',
        subtitle: _status.runtime == 'remote'
            ? (_isEnglish
                  ? 'The remote PC Bridge returned no sessions.'
                  : '远程 PC Bridge 暂无可用 session。')
            : (_isEnglish
                  ? 'Local Codex returned no sessions.'
                  : '本地 Codex 暂无可用 session。'),
        actionLabel: _isEnglish ? 'Reload' : '刷新',
        onAction: () => unawaited(_loadSessions()),
      );
    }
    final palette = context.omniPalette;
    return ListView.separated(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
      itemCount: _sessions.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        final session = _sessions[index];
        final opening = _openingThreadId == session.threadId;
        return Material(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(8),
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: opening ? null : () => unawaited(_openSession(session)),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
              child: Row(
                children: [
                  Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      color: palette.accentPrimary.withValues(alpha: 0.12),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.code_rounded,
                      size: 18,
                      color: palette.accentPrimary,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          session.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textPrimary,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            height: 1.35,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          [
                            if (session.archived)
                              LegacyTextLocalizer.localize('已归档'),
                            if (session.cwdLabel.isNotEmpty) session.cwdLabel,
                            if (session.timeLabel.isNotEmpty) session.timeLabel,
                          ].join(' · '),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 11,
                            height: 1.35,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  opening
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Icon(
                          Icons.chevron_right_rounded,
                          color: palette.textTertiary,
                        ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _CodexSessionsStateView extends StatelessWidget {
  const _CodexSessionsStateView({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        SizedBox(height: MediaQuery.sizeOf(context).height * 0.18),
        Icon(icon, size: 34, color: palette.textTertiary),
        const SizedBox(height: 12),
        Text(
          title,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(
            subtitle,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              height: 1.45,
            ),
          ),
        ),
        const SizedBox(height: 14),
        Center(
          child: OutlinedButton.icon(
            onPressed: onAction,
            icon: const Icon(Icons.refresh_rounded, size: 17),
            label: Text(actionLabel),
          ),
        ),
      ],
    );
  }
}

class _CodexSessionSummary {
  const _CodexSessionSummary({
    required this.threadId,
    required this.title,
    required this.cwd,
    required this.updatedAtMs,
    required this.archived,
  });

  final String threadId;
  final String title;
  final String cwd;
  final int? updatedAtMs;
  final bool archived;

  String get cwdLabel {
    final segment = _lastPathSegment(cwd);
    if (segment == null) {
      return '';
    }
    return segment;
  }

  String get timeLabel => _formatSessionTime(updatedAtMs);
}

List<_CodexSessionSummary> _extractCodexSessions(List<dynamic> payloads) {
  final sessionsById = <String, _CodexSessionSummary>{};

  void visit(dynamic value, [String? parentKey]) {
    if (value is List) {
      for (final item in value) {
        visit(item, parentKey);
      }
      return;
    }
    if (value is! Map) {
      return;
    }
    final map = value.map((key, nestedValue) {
      return MapEntry(key.toString(), nestedValue);
    });
    final threadMap = map['thread'] is Map
        ? (map['thread'] as Map).map(
            (key, nestedValue) => MapEntry(key.toString(), nestedValue),
          )
        : null;
    final threadId = _threadEntryId(map, threadMap, parentKey);
    if (threadId != null) {
      sessionsById[threadId] = _CodexSessionSummary(
        threadId: threadId,
        title:
            _stringValue(
              map['name'] ??
                  map['title'] ??
                  map['preview'] ??
                  map['threadName'] ??
                  map['thread_name'] ??
                  threadMap?['name'] ??
                  threadMap?['title'] ??
                  threadMap?['preview'],
            ) ??
            'Codex ${threadId.length > 6 ? threadId.substring(threadId.length - 6) : threadId}',
        cwd:
            _stringValue(map['cwd'] ?? threadMap?['cwd'] ?? map['worktree']) ??
            '',
        updatedAtMs: _timeValueMs(
          map['lastActivityAt'] ??
              map['last_activity_at'] ??
              map['updatedAt'] ??
              map['updated_at'] ??
              map['createdAt'] ??
              map['created_at'] ??
              threadMap?['lastActivityAt'] ??
              threadMap?['updatedAt'] ??
              threadMap?['createdAt'],
        ),
        archived:
            _boolValue(
              map['archived'] ??
                  map['isArchived'] ??
                  map['is_archived'] ??
                  threadMap?['archived'] ??
                  threadMap?['isArchived'],
            ) ??
            false,
      );
    }
    for (final entry in map.entries) {
      if (_threadNestedSkipKeys.contains(entry.key)) {
        continue;
      }
      visit(entry.value, entry.key);
    }
  }

  for (final payload in payloads) {
    visit(payload);
  }
  return sessionsById.values.toList(growable: true);
}

String? _threadEntryId(
  Map<String, dynamic> map,
  Map<String, dynamic>? threadMap,
  String? parentKey,
) {
  final direct = _stringValue(
    map['threadId'] ?? map['thread_id'] ?? threadMap?['id'],
  );
  if (direct != null) {
    return direct;
  }
  if (!_looksLikeThreadEntry(map, threadMap, parentKey)) {
    return null;
  }
  return _stringValue(map['id']);
}

bool _looksLikeThreadEntry(
  Map<String, dynamic> map,
  Map<String, dynamic>? threadMap,
  String? parentKey,
) {
  if (threadMap != null ||
      map.containsKey('threadId') ||
      map.containsKey('thread_id')) {
    return true;
  }
  if (!map.containsKey('id')) {
    return false;
  }
  final normalizedParentKey = parentKey?.toLowerCase() ?? '';
  if (normalizedParentKey == 'threads') {
    return true;
  }
  final type = _stringValue(map['type'])?.toLowerCase() ?? '';
  if (_nonThreadItemTypes.contains(type)) {
    return false;
  }
  return map.keys.any(_threadSummaryKeys.contains);
}

String? _stringValue(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

int? _intValue(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

bool? _boolValue(dynamic value) {
  if (value is bool) return value;
  if (value is num) return value.toInt() != 0;
  final normalized = value?.toString().trim().toLowerCase() ?? '';
  return switch (normalized) {
    'true' || '1' || 'yes' => true,
    'false' || '0' || 'no' => false,
    _ => null,
  };
}

int? _timeValueMs(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is num) {
    final raw = value.toInt();
    return raw < 100000000000 ? raw * 1000 : raw;
  }
  final text = value.toString().trim();
  if (text.isEmpty) {
    return null;
  }
  final rawInt = int.tryParse(text);
  if (rawInt != null) {
    return rawInt < 100000000000 ? rawInt * 1000 : rawInt;
  }
  return DateTime.tryParse(text)?.millisecondsSinceEpoch;
}

String? _lastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'/+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized.split('/').where((part) => part.isNotEmpty).toList();
  if (parts.isEmpty) {
    return normalized == '/' ? '/' : null;
  }
  return parts.last;
}

String _formatSessionTime(int? timestampMs) {
  if (timestampMs == null || timestampMs <= 0) {
    return '';
  }
  final time = DateTime.fromMillisecondsSinceEpoch(timestampMs);
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(time.year, time.month, time.day);
  final hh = time.hour.toString().padLeft(2, '0');
  final mm = time.minute.toString().padLeft(2, '0');
  if (day == today) {
    return '${LegacyTextLocalizer.localize('今天')} $hh:$mm';
  }
  if (day == today.subtract(const Duration(days: 1))) {
    return '${LegacyTextLocalizer.localize('昨天')} $hh:$mm';
  }
  return '${time.month.toString().padLeft(2, '0')}/${time.day.toString().padLeft(2, '0')} $hh:$mm';
}

const Set<String> _threadNestedSkipKeys = <String>{
  'messages',
  'turns',
  'input',
  'events',
};

const Set<String> _threadSummaryKeys = <String>{
  'cwd',
  'worktree',
  'name',
  'title',
  'preview',
  'threadName',
  'thread_name',
  'archived',
  'isArchived',
  'is_archived',
  'sourceKind',
  'source_kind',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'lastActivityAt',
  'last_activity_at',
};

const Set<String> _nonThreadItemTypes = <String>{
  'agentmessage',
  'reasoning',
  'commandexecution',
  'filechange',
  'tool',
  'mcptoolcall',
  'usermessage',
  'plan',
  'serverrequest',
};
