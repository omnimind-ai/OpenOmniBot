import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

@immutable
class AgentAvatarState {
  const AgentAvatarState({this.presetIndex = 0, this.customImagePath = ''});

  final int presetIndex;
  final String customImagePath;

  bool get hasCustomImage => customImagePath.trim().isNotEmpty;
}

class AgentAvatarService {
  AgentAvatarService._();

  static const String storageKey = 'agentAvatarIndex';
  static const String customImagePathKey = 'agentAvatarCustomImagePath';

  static const List<String> presetAvatars = <String>[
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  static final ValueNotifier<AgentAvatarState> avatarStateNotifier =
      ValueNotifier<AgentAvatarState>(const AgentAvatarState());
  static bool _isLoaded = false;

  static int normalizeIndex(int? index) {
    if (index == null || index < 0 || index >= presetAvatars.length) {
      return 0;
    }
    return index;
  }

  static String assetForIndex(int? index) {
    return presetAvatars[normalizeIndex(index)];
  }

  static Future<AgentAvatarState> load() async {
    final prefs = await SharedPreferences.getInstance();
    final avatarIndex = normalizeIndex(prefs.getInt(storageKey));
    var customImagePath = prefs.getString(customImagePathKey)?.trim() ?? '';
    if (customImagePath.isNotEmpty && !await File(customImagePath).exists()) {
      customImagePath = '';
      await prefs.remove(customImagePathKey);
    }
    final state = AgentAvatarState(
      presetIndex: avatarIndex,
      customImagePath: customImagePath,
    );
    avatarStateNotifier.value = state;
    _isLoaded = true;
    return state;
  }

  static Future<AgentAvatarState> ensureLoaded() async {
    if (_isLoaded) {
      return avatarStateNotifier.value;
    }
    return load();
  }

  static Future<AgentAvatarState> setPresetAvatarIndex(int index) async {
    final avatarIndex = normalizeIndex(index);
    final prefs = await SharedPreferences.getInstance();
    final previousCustomPath =
        prefs.getString(customImagePathKey)?.trim() ??
        avatarStateNotifier.value.customImagePath;
    await prefs.setInt(storageKey, avatarIndex);
    await prefs.remove(customImagePathKey);
    final state = AgentAvatarState(presetIndex: avatarIndex);
    _isLoaded = true;
    avatarStateNotifier.value = state;
    await _deleteManagedAvatar(previousCustomPath);
    return state;
  }

  static Future<AgentAvatarState> setCustomAvatarBytes(Uint8List bytes) async {
    final prefs = await SharedPreferences.getInstance();
    final previousCustomPath =
        prefs.getString(customImagePathKey)?.trim() ??
        avatarStateNotifier.value.customImagePath;
    final directory = await _avatarDirectory();
    final targetPath =
        '${directory.path}/agent_avatar_${DateTime.now().millisecondsSinceEpoch}.png';
    await File(targetPath).writeAsBytes(bytes, flush: true);
    final state = AgentAvatarState(
      presetIndex: avatarStateNotifier.value.presetIndex,
      customImagePath: targetPath,
    );
    await prefs.setString(customImagePathKey, targetPath);
    await prefs.setInt(storageKey, state.presetIndex);
    _isLoaded = true;
    avatarStateNotifier.value = state;
    if (previousCustomPath != targetPath) {
      await _deleteManagedAvatar(previousCustomPath);
    }
    return state;
  }

  static Future<Directory> _avatarDirectory() async {
    final baseDirectory = await getApplicationSupportDirectory();
    final directory = Directory('${baseDirectory.path}/agent_avatars');
    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }
    return directory;
  }

  static Future<void> _deleteManagedAvatar(String path) async {
    final trimmed = path.trim();
    if (trimmed.isEmpty) {
      return;
    }
    final file = File(trimmed);
    if (await file.exists()) {
      await file.delete();
    }
  }
}
