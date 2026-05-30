import '../models/app_count.dart';
import '../models/app_icons.dart';
import '../models/execution_record.dart';
import '../models/favorite_count.dart';
import '../models/favorite_record.dart';
import '../models/paged_messages_result.dart';
import '../models/task_execution_info.dart';
import '../models/title_count.dart';
import '../services/cache.dart';

class CacheUtil {
  /// 缓存字符串值到MMKV
  /// [key] 键
  /// [value] 要缓存的字符串值
  static cacheString(String key, String value) async {
    await cacheEvent.invokeMethod("doMMKVEncodeString", {
      "key": key,
      "value": value,
    });
  }

  /// 缓存布尔值到MMKV
  /// [key] 键
  /// [value] 要缓存的布尔值
  static cacheBool(String key, bool value) async {
    await cacheEvent.invokeMethod("doMMKVEncodeBool", {
      "key": key,
      "value": value,
    });
  }

  /// 缓存整数值到MMKV
  /// [key] 键
  /// [value] 要缓存的整数值
  static cacheInt(String key, int value) async {
    await cacheEvent.invokeMethod("doMMKVEncodeInt", {
      "key": key,
      "value": value,
    });
  }

  /// 缓存双精度浮点数值到MMKV
  /// [key] 键
  /// [value] 要缓存的双精度浮点数值
  static cacheDouble(String key, double value) async {
    await cacheEvent.invokeMethod("doMMKVEncodeDouble", {
      "key": key,
      "value": value,
    });
  }

  /// 从MMKV获取字符串值
  /// [key] 键
  /// [defaultValue] 默认值，当键不存在时返回
  /// 返回对应的字符串值或默认值
  static Future<String> getString(
    String key, {
    String defaultValue = "",
  }) async {
    return await cacheEvent.invokeMethod("doMMKVDecodeString", {
      "key": key,
      "defaultValue": defaultValue,
    });
  }

  /// 从MMKV获取布尔值
  /// [key] 键
  /// [defaultValue] 默认值，当键不存在时返回
  /// 返回对应的布尔值或默认值
  static Future<bool> getBool(String key, {bool defaultValue = false}) async {
    return await cacheEvent.invokeMethod("doMMKVDecodeBoole", {
      "key": key,
      "defaultValue": defaultValue,
    });
  }

  /// 从MMKV获取整数值
  /// [key] 键
  /// [defaultValue] 默认值，当键不存在时返回
  /// 返回对应的整数值或默认值
  static Future<int> getInt(String key, {int defaultValue = 0}) async {
    final result = await cacheEvent.invokeMethod("doMMKVDecodeInt", {
      "key": key,
      "defaultValue": defaultValue,
    });
    if (result is int) {
      return result;
    }
    if (result is num) {
      return result.toInt();
    }
    return defaultValue;
  }

  /// 从MMKV获取双精度浮点数值
  /// [key] 键
  /// [defaultValue] 默认值，当键不存在时返回
  /// 返回对应的双精度浮点数值或默认值
  static Future<String> getDouble(
    String key, {
    double defaultValue = 0.0,
  }) async {
    return await cacheEvent.invokeMethod("doMMKVDecodeDouble", {
      "key": key,
      "defaultValue": defaultValue,
    });
  }

  // AppIcons相关方法
  static Future<AppIcons?> getAppIconByPackageName(String packageName) async {
    final Map<dynamic, dynamic>? result = await cacheEvent.invokeMethod(
      "getAppIconByPackageName",
      {"packageName": packageName},
    );
    return result != null ? AppIcons.fromMap(result) : null;
  }

  static Future<List<AppIcons>> getAppIconsByPackageNames(
    List<String> packageNames,
  ) async {
    final List<dynamic>? result = await cacheEvent.invokeMethod(
      "getAppIconsByPackageNames",
      {"packageNames": packageNames},
    );
    return result?.map((e) => AppIcons.fromMap(e)).toList() ?? [];
  }

  static Future<bool> insertAppIcon({
    required String appName,
    required String packageName,
    required String iconBase64,
    String iconPath = "",
  }) async {
    try {
      final result = await cacheEvent.invokeMethod("insertAppIcon", {
        "appName": appName,
        "packageName": packageName,
        "icon_base64": iconBase64,
        "icon_path": iconPath,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  // StudyRecord相关方法
  // FavoriteRecord相关方法
  static Future<List<FavoriteRecord>> getAllFavoriteRecords() async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getAllFavoriteRecords",
    );
    return result
        .map((item) => FavoriteRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<bool> insertFavoriteRecord({
    required String title,
    required String desc,
    required String type,
    required String imagePath,
    String packageName = '',
  }) async {
    try {
      final result = await cacheEvent.invokeMethod("insertFavoriteRecord", {
        "title": title,
        "desc": desc,
        "type": type,
        "imagePath": imagePath,
        "packageName": packageName,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  static Future<List<FavoriteRecord>> getFavoriteRecordsByType(
    String type,
  ) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getFavoriteRecordsByType",
      {"type": type},
    );
    return result
        .map((item) => FavoriteRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<FavoriteRecord?> getFavoriteRecordById(int id) async {
    final dynamic result = await cacheEvent.invokeMethod(
      "getFavoriteRecordById",
      {"id": id},
    );
    if (result != null) {
      return FavoriteRecord.fromMap(result as Map<dynamic, dynamic>);
    } else {
      return null;
    }
  }

  static Future<List<FavoriteCount>> getFavoriteRecordCountByType() async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getFavoriteRecordCountByType",
    );
    return result
        .map((item) => FavoriteCount.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<bool> deleteFavoriteRecordById(int id) async {
    return await cacheEvent.invokeMethod("deleteFavoriteRecordById", {
      "id": id,
    });
  }

  // FavoriteRecord
  static Future<bool> updateFavoriteRecordTitle({
    required int id,
    required String title,
  }) async {
    return await cacheEvent.invokeMethod("updateFavoriteRecordTitle", {
      "id": id,
      "title": title,
    });
  }

  static Future<List<FavoriteRecord>> getFavoriteRecordsByTitle(
    String title,
  ) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getFavoriteRecordsByTitle",
      {"title": title},
    );
    return result
        .map((item) => FavoriteRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  // ExecutionRecord相关方法
  static Future<List<ExecutionRecord>> getAllExecutionRecords() async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getAllExecutionRecords",
    );
    return result
        .map((item) => ExecutionRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<bool> insertExecutionRecord({
    required String title,
    required String appName,
    required String packageName,
  }) async {
    try {
      final result = await cacheEvent.invokeMethod("insertExecutionRecord", {
        "title": title,
        "appName": appName,
        "packageName": packageName,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  static Future<List<ExecutionRecord>> getExecutionRecordsByAppName(
    String appName,
  ) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getExecutionRecordsByAppName",
      {"appName": appName},
    );
    return result
        .map((item) => ExecutionRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<AppCount>> getExecutionRecordCountByAppName() async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getExecutionRecordCountByAppName",
    );
    return result
        .map((item) => AppCount.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<bool> updateExecutionRecordTitle({
    required int id,
    required String title,
  }) async {
    return await cacheEvent.invokeMethod("updateExecutionRecordTitle", {
      "id": id,
      "title": title,
    });
  }

  static Future<List<ExecutionRecord>> getExecutionRecordsByTitle(
    String title,
  ) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getExecutionRecordsByTitle",
      {"title": title},
    );
    return result
        .map((item) => ExecutionRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<TitleCount>> getExecutionRecordCountByTitle() async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getExecutionRecordCountByTitle",
    );
    return result
        .map((item) => TitleCount.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<TaskExecutionInfo>> getTaskExecutionInfos({
    int limit = 50,
    int offset = 0,
  }) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getTaskExecutionInfos",
      {"limit": limit, "offset": offset},
    );
    return result
        .map((item) => TaskExecutionInfo.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<bool> deleteExecutionRecordById(int id) async {
    return await cacheEvent.invokeMethod("deleteExecutionRecordById", {
      "id": id,
    });
  }

  /// 使用 nodeId 和 suggestionId 删除执行记录
  static Future<bool> deleteExecutionRecordByNodeAndSuggestionId(
    String nodeId,
    String suggestionId,
  ) async {
    return await cacheEvent.invokeMethod(
      "deleteExecutionRecordByNodeAndSuggestionId",
      {"nodeId": nodeId, "suggestionId": suggestionId},
    );
  }

  /// 使用 nodeId 和 suggestionId 获取执行记录列表, 不包括 running 状态的记录
  static Future<List<ExecutionRecord>> getExecutionRecordsByNodeAndSuggestionId(
    String nodeId,
    String suggestionId, {
    int limit = 50,
    int offset = 0,
  }) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getExecutionRecordsByNodeAndSuggestionId",
      {
        "nodeId": nodeId,
        "suggestionId": suggestionId,
        "limit": limit,
        "offset": offset,
      },
    );
    return result
        .map((item) => ExecutionRecord.fromMap(item as Map<dynamic, dynamic>))
        .toList();
  }

  // Message相关方法
  static Future<int> insertMessage({
    required String messageId,
    required int type,
    required int user,
    required String content,
  }) async {
    return await cacheEvent.invokeMethod("insertMessage", {
      "messageId": messageId,
      "type": type,
      "user": user,
      "content": content,
    });
  }

  static Future<bool> updateMessage({
    required int id,
    required String messageId,
    required int type,
    required int user,
    required String content,
    required int createdAt,
  }) async {
    return await cacheEvent.invokeMethod("updateMessage", {
      "id": id,
      "messageId": messageId,
      "type": type,
      "user": user,
      "content": content,
      "createdAt": createdAt,
    });
  }

  static Future<Map<String, dynamic>?> getMessageById(int id) async {
    final dynamic result = await cacheEvent.invokeMethod("getMessageById", {
      "id": id,
    });
    if (result != null) {
      return result as Map<String, dynamic>;
    } else {
      return null;
    }
  }

  static Future<PagedMessagesResult> getMessagesByPage({
    required int page,
    required int pageSize,
  }) async {
    final Map<dynamic, dynamic> result = await cacheEvent.invokeMethod(
      "getMessagesByPage",
      {"page": page, "pageSize": pageSize},
    );
    return PagedMessagesResult.fromMap(result);
  }

  static Future<bool> deleteMessageById(int id) async {
    return await cacheEvent.invokeMethod("deleteMessageById", {"id": id});
  }

  static Future<bool> deleteAllMessages() async {
    return await cacheEvent.invokeMethod("deleteAllMessages");
  }

  // Cache Suggestion methods
  static Future<bool> saveCacheSuggestions({
    required String packageName,
    required List<String> ids,
  }) async {
    try {
      final result = await cacheEvent.invokeMethod("cacheSuggestions", {
        "packageName": packageName,
        "ids": ids.join(","),
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  static Future<List<dynamic>> getCacheSuggestions({
    required String packageName,
  }) async {
    final List<dynamic> result = await cacheEvent.invokeMethod(
      "getCacheSuggestions",
      {"packageName": packageName},
    );
    return result;
  }
}
