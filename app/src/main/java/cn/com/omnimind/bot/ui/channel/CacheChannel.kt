package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史缓存数据
 */
class CacheChannel {
    var TAG = "[CacheChannel]"
    private val EVENT_CHANNEL = "cn.com.omnimind.bot/CacheDataEvent" // Flutter 事件通道
    private var channel: MethodChannel? = null

    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        )
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "doMMKVEncodeString" -> {
                    call.argument<String>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler
                    }

                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVEncodeInt" -> {
                    call.argument<Number>("value")?.toLong()?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )

                }

                "doMMKVEncodeBool" -> {
                    call.argument<Boolean>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVEncodeDouble" -> {
                    call.argument<Double>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVDecodeString" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeString(
                            call.argument<String>("key") ?: "",
                            call.argument<String>("defaultValue") ?: ""
                        )
                    )
                }

                "doMMKVDecodeBoole" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeBool(
                            call.argument<String>("key") ?: "",
                            call.argument<Boolean>("defaultValue") ?: false
                        )
                    )
                }

                "doMMKVDecodeInt" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeLong(
                            call.argument<String>("key") ?: "",
                            call.argument<Number>("defaultValue")?.toLong() ?: 0L
                        )
                    )
                }

                "doMMKVDecodeDouble" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeDouble(
                            call.argument<String>("key") ?: "",
                            call.argument<Double>("defaultValue") ?: 0.0
                        )
                    )
                }


                // AppIcons相关方法
                "getAppIconByPackageName" -> {
                    mainJob.launch {
                        try {
                            val packageName = call.argument<String>("packageName") ?: ""
                            val appIcon = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAppIconByPackageName(packageName)
                            }
                            result.success(appIcon?.let {
                                mapOf(
                                    "id" to it.id,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "icon_base64" to it.icon_base64,
                                    "icon_path" to it.icon_path,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_APP_ICON_ERROR", e.message, null)
                        }
                    }
                }

                "getAppIconsByPackageNames" -> {
                    mainJob.launch {
                        try {
                            val packageNames = call.argument<List<String>>("packageNames") ?: emptyList()
                            val appIcons = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAppIconsByPackageNames(packageNames)
                            }
                            result.success(appIcons.map {
                                mapOf(
                                    "id" to it.id,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "icon_base64" to it.icon_base64,
                                    "icon_path" to it.icon_path,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_APP_ICONS_ERROR", e.message, null)
                        }
                    }
                }

                // StudyRecord相关方法
                "getAllStudyRecords" -> {
                    mainJob.launch {
                        try {
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAllStudyRecords()
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt,
                                    "isFavorite" to it.isFavorite
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_STUDY_RECORDS_ERROR", e.message, null)
                        }
                    }
                }

                "getTaskExecutionInfos" -> {
                    mainJob.launch {
                        try {
                            val limit = call.argument<Number>("limit")?.toInt() ?: 50
                            val offset = call.argument<Number>("offset")?.toInt() ?: 0
                            val infos = withContext(Dispatchers.IO) {
                                DatabaseHelper.getTaskExecutionInfos(limit, offset)
                            }
                            result.success(infos.map {
                                mapOf(
                                    "id" to it.id,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "title" to it.title,
                                    "nodeId" to it.nodeId,
                                    "suggestionId" to it.suggestionId,
                                    "iconUrl" to it.iconUrl,
                                    "type" to it.type,
                                    "content" to it.content,
                                    "count" to it.count,
                                    "lastExecutionTime" to it.lastExecutionTime
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_TASK_EXECUTION_INFOS_ERROR", e.message, null)
                        }
                    }
                }

                "getStudyRecordsByAppName" -> {
                    mainJob.launch {
                        try {
                            val appName = call.argument<String>("appName") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getStudyRecordsByAppName(appName)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt,
                                    "isFavorite" to it.isFavorite
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_STUDY_RECORDS_BY_APP_ERROR", e.message, null)
                        }
                    }
                }

                "getStudyRecordCountByAppName" -> {
                    mainJob.launch {
                        try {
                            val counts = withContext(Dispatchers.IO) {
                                DatabaseHelper.getStudyRecordCountByAppName()
                            }
                            result.success(counts.map {
                                mapOf(
                                    "appName" to it.appName,
                                    "count" to it.count,
                                    "packageName" to it.packageName
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_STUDY_RECORD_COUNT_ERROR", e.message, null)
                        }
                    }
                }

                // FavoriteRecord相关方法
                "getAllFavoriteRecords" -> {
                    mainJob.launch {
                        try {
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAllFavoriteRecords()
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "desc" to it.desc,
                                    "type" to it.type,
                                    "packageName" to it.packageName,
                                    "imagePath" to it.imagePath,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_FAVORITE_RECORDS_ERROR", e.message, null)
                        }
                    }
                }

                "getFavoriteRecordsByType" -> {
                    mainJob.launch {
                        try {
                            val type = call.argument<String>("type") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getFavoriteRecordsByType(type)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "desc" to it.desc,
                                    "type" to it.type,
                                    "imagePath" to it.imagePath,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_FAVORITE_RECORDS_BY_TYPE_ERROR", e.message, null)
                        }
                    }
                }

                "getFavoriteRecordById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val record = withContext(Dispatchers.IO) {
                                DatabaseHelper.getFavoriteRecordById(id)
                            }
                            if (record != null) {
                                result.success(
                                    mapOf(
                                        "id" to record.id,
                                        "title" to record.title,
                                        "desc" to record.desc,
                                        "type" to record.type,
                                        "imagePath" to record.imagePath,
                                        "createdAt" to record.createdAt,
                                        "updatedAt" to record.updatedAt
                                    )
                                )
                            } else {
                                result.success(null)
                            }
                        } catch (e: Exception) {
                            result.error("GET_FAVORITE_RECORDS_BY_TYPE_ERROR", e.message, null)
                        }
                    }
                }

                // 新增插入方法
                "insertAppIcon" -> {
                    mainJob.launch {
                        try {
                            val appName = call.argument<String>("appName") ?: ""
                            val packageName = call.argument<String>("packageName") ?: ""
                            val iconBase64 = call.argument<String>("icon_base64") ?: ""
                            val iconPath = call.argument<String>("icon_path") ?: ""
                            val success = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertAppIcon(appName, packageName, iconBase64)
                            }
                            result.success(success)
                        } catch (e: Exception) {
                            result.error("INSERT_APP_ICON_ERROR", e.message, null)
                        }
                    }
                }

                "insertFavoriteRecord" -> {
                    mainJob.launch {
                        try {
                            val title = call.argument<String>("title") ?: ""
                            val desc = call.argument<String>("desc") ?: ""
                            val type = call.argument<String>("type") ?: ""
                            val imagePath = call.argument<String>("imagePath") ?: ""
                            val packageName = call.argument<String>("packageName") ?: ""
                            val success = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertFavoriteRecord(title, desc, type, imagePath, packageName)
                            }
                            result.success(success)
                        } catch (e: Exception) {
                            result.error("INSERT_FAVORITE_RECORD_ERROR", e.message, null)
                        }
                    }
                }

                // ExecutionRecord相关方法
                "getAllExecutionRecords" -> {
                    mainJob.launch {
                        try {
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAllExecutionRecords()
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "nodeId" to it.nodeId,
                                    "suggestionId" to it.suggestionId,
                                    "iconUrl" to it.iconUrl,
                                    "type" to it.type,
                                    "content" to it.content,
                                    "status" to it.status,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_EXECUTION_RECORDS_ERROR", e.message, null)
                        }
                    }
                }

                "getExecutionRecordsByAppName" -> {
                    mainJob.launch {
                        try {
                            val appName = call.argument<String>("appName") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getExecutionRecordsByAppName(appName)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "nodeId" to it.nodeId,
                                    "suggestionId" to it.suggestionId,
                                    "iconUrl" to it.iconUrl,
                                    "type" to it.type,
                                    "content" to it.content,
                                    "status" to it.status,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_EXECUTION_RECORDS_BY_APP_ERROR", e.message, null)
                        }
                    }
                }

                "getExecutionRecordCountByAppName" -> {
                    mainJob.launch {
                        try {
                            val counts = withContext(Dispatchers.IO) {
                                DatabaseHelper.getExecutionRecordCountByAppName()
                            }
                            result.success(counts.map {
                                mapOf(
                                    "appName" to it.appName,
                                    "count" to it.count,
                                    "packageName" to it.packageName
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_EXECUTION_RECORD_COUNT_ERROR", e.message, null)
                        }
                    }
                }

                "updateExecutionRecordTitle" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val title = call.argument<String>("title") ?: ""
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateExecutionRecordTitle(id, title)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("UPDATE_EXECUTION_RECORD_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "getExecutionRecordsByTitle" -> {
                    mainJob.launch {
                        try {
                            val title = call.argument<String>("title") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getExecutionRecordsByTitle(title)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "nodeId" to it.nodeId,
                                    "suggestionId" to it.suggestionId,
                                    "iconUrl" to it.iconUrl,
                                    "type" to it.type,
                                    "content" to it.content,
                                    "status" to it.status,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_EXECUTION_RECORDS_BY_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "getExecutionRecordCountByTitle" -> {
                    mainJob.launch {
                        try {
                            val counts = withContext(Dispatchers.IO) {
                                DatabaseHelper.getExecutionRecordCountByTitle()
                            }
                            result.success(counts.map {
                                mapOf(
                                    "title" to it.title,
                                    "count" to it.count
                                )
                            })
                        } catch (e: Exception) {
                            result.error(
                                "GET_EXECUTION_RECORD_COUNT_BY_TITLE_ERROR",
                                e.message,
                                null
                            )
                        }
                    }
                }

                "deleteExecutionRecordById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteExecutionRecordById(id)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_EXECUTION_RECORD_BY_ID_ERROR", e.message, null)
                        }
                    }
                }

                "deleteExecutionRecordByNodeAndSuggestionId" -> {
                    mainJob.launch {
                        try {
                            val nodeId = call.argument<String>("nodeId")
                                ?: return@launch result.error("ARG_ERROR", "nodeId 不能为空", null)
                            val suggestionId = call.argument<String>("suggestionId")
                                ?: return@launch result.error("ARG_ERROR", "suggestionId 不能为空", null)
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteExecutionRecordByNodeAndSuggestionId(nodeId, suggestionId)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_EXECUTION_RECORD_BY_NODE_AND_SUGGESTION_ID_ERROR", e.message, null)
                        }
                    }
                }

                "getExecutionRecordsByNodeAndSuggestionId" -> {
                    mainJob.launch {
                        try {
                            val nodeId = call.argument<String>("nodeId")
                                ?: return@launch result.error("ARG_ERROR", "nodeId 不能为空", null)
                            val suggestionId = call.argument<String>("suggestionId")
                                ?: return@launch result.error("ARG_ERROR", "suggestionId 不能为空", null)
                            val limit = call.argument<Number>("limit")?.toInt() ?: 50
                            val offset = call.argument<Number>("offset")?.toInt() ?: 0
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getExecutionRecordsByNodeAndSuggestionId(
                                    nodeId,
                                    suggestionId,
                                    limit,
                                    offset
                                )
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "nodeId" to it.nodeId,
                                    "suggestionId" to it.suggestionId,
                                    "iconUrl" to it.iconUrl,
                                    "type" to it.type,
                                    "content" to it.content,
                                    "status" to it.status,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_EXECUTION_RECORDS_BY_NODE_AND_SUGGESTION_ID_ERROR", e.message, null)
                        }
                    }
                }

                "insertExecutionRecord" -> {
                    mainJob.launch {
                        try {
                            val title = call.argument<String>("title") ?: ""
                            val appName = call.argument<String>("appName") ?: ""
                            val packageName = call.argument<String>("packageName") ?: ""
                            val nodeId = call.argument<String>("nodeId") ?: ""
                            val suggestionId = call.argument<String>("suggestionId") ?: ""
                            val iconUrl = call.argument<String?>("iconUrl")
                            val type = call.argument<String?>("type") ?: "unknown"
                            val content = call.argument<String?>("content")
                            val success = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertExecutionRecord(
                                    title = title,
                                    appName = appName,
                                    packageName = packageName,
                                    nodeId = nodeId,
                                    suggestionId = suggestionId,
                                    iconUrl = iconUrl,
                                    type = type,
                                    content = content
                                )
                            }
                            result.success(success)
                        } catch (e: Exception) {
                            result.error("INSERT_EXECUTION_RECORD_ERROR", e.message, null)
                        }
                    }
                }

                // 新增缺失的方法
                "getFavoriteRecordCountByType" -> {
                    mainJob.launch {
                        try {
                            val counts = withContext(Dispatchers.IO) {
                                DatabaseHelper.getFavoriteRecordCountByType()
                            }
                            result.success(counts.map {
                                mapOf(
                                    "type" to it.type,
                                    "count" to it.count
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_FAVORITE_RECORD_COUNT_BY_TYPE_ERROR", e.message, null)
                        }
                    }
                }

                "deleteFavoriteRecordById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteFavoriteRecordById(id)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_FAVORITE_RECORD_BY_ID_ERROR", e.message, null)
                        }
                    }
                }

                "updateStudyRecordFavoriteStatus" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val isFavorite = call.argument<Boolean>("isFavorite") ?: false
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateStudyRecordFavoriteStatus(id, isFavorite)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error(
                                "UPDATE_STUDY_RECORD_FAVORITE_STATUS_ERROR",
                                e.message,
                                null
                            )
                        }
                    }
                }

                "updateStudyRecordTitle" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val title = call.argument<String>("title") ?: ""
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateStudyRecordTitleAndReturnSuggestionId(id, title)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("UPDATE_STUDY_RECORD_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "getStudyRecordsByTitle" -> {
                    mainJob.launch {
                        try {
                            val title = call.argument<String>("title") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getStudyRecordsByTitle(title)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt,
                                    "isFavorite" to it.isFavorite
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_STUDY_RECORDS_BY_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "updateFavoriteRecordTitle" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val title = call.argument<String>("title") ?: ""
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateFavoriteRecordTitle(id, title)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("UPDATE_FAVORITE_RECORD_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "getFavoriteRecordsByTitle" -> {
                    mainJob.launch {
                        try {
                            val title = call.argument<String>("title") ?: ""
                            val records = withContext(Dispatchers.IO) {
                                DatabaseHelper.getFavoriteRecordsByTitle(title)
                            }
                            result.success(records.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "desc" to it.desc,
                                    "type" to it.type,
                                    "imagePath" to it.imagePath,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            result.error("GET_FAVORITE_RECORDS_BY_TITLE_ERROR", e.message, null)
                        }
                    }
                }

                "deleteStudyRecordById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteStudyRecordById(id)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_STUDY_RECORD_BY_ID_ERROR", e.message, null)
                        }
                    }
                }

                // Message相关方法
                "insertMessage" -> {
                    mainJob.launch {
                        try {
                            val messageId = call.argument<String>("messageId") ?: ""
                            val type = call.argument<Int>("type") ?: 1
                            val user = call.argument<Int>("user") ?: 1
                            val content = call.argument<String>("content") ?: ""

                            val message = cn.com.omnimind.baselib.database.Message(
                                id = 0,
                                messageId = messageId,
                                type = type,
                                user = user,
                                content = content,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )

                            val id = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertMessage(message)
                            }
                            result.success(id)
                        } catch (e: Exception) {
                            result.error("INSERT_MESSAGE_ERROR", e.message, null)
                        }
                    }
                }

                "updateMessage" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val messageId = call.argument<String>("messageId") ?: ""
                            val type = call.argument<Int>("type") ?: 1
                            val user = call.argument<Int>("user") ?: 1
                            val content = call.argument<String>("content") ?: ""
                            val createdAt =
                                call.argument<Long>("createdAt") ?: System.currentTimeMillis()

                            val message = cn.com.omnimind.baselib.database.Message(
                                id = id,
                                messageId = messageId,
                                type = type,
                                user = user,
                                content = content,
                                createdAt = createdAt,
                                updatedAt = System.currentTimeMillis()
                            )

                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateMessage(message)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("UPDATE_MESSAGE_ERROR", e.message, null)
                        }
                    }
                }

                "getMessageById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()

                            val message = withContext(Dispatchers.IO) {
                                DatabaseHelper.getMessageById(id)
                            }
                            if (message != null) {
                                result.success(
                                    mapOf(
                                        "id" to message.id,
                                        "messageId" to message.messageId,
                                        "type" to message.type,
                                        "user" to message.user,
                                        "content" to message.content,
                                        "createdAt" to message.createdAt,
                                        "updatedAt" to message.updatedAt
                                    )
                                )
                            } else {
                                result.success(null)
                            }
                        } catch (e: Exception) {
                            result.error("GET_MESSAGE_BY_ID_ERROR", e.message, null)
                        }
                    }
                }

                "getMessagesByPage" -> {
                    mainJob.launch {
                        try {
                            val pageNum = call.argument<Int>("page") ?: 0
                            val pageSize = call.argument<Int>("pageSize") ?: 20

                            val pagedResult = withContext(Dispatchers.IO) {
                                DatabaseHelper.getMessagesByPage(pageNum, pageSize)
                            }
                            result.success(
                                mapOf(
                                    "messageList" to pagedResult.messageList.map {
                                        mapOf(
                                            "id" to it.id,
                                            "messageId" to it.messageId,
                                            "type" to it.type,
                                            "user" to it.user,
                                            "content" to it.content,
                                            "createdAt" to it.createdAt,
                                            "updatedAt" to it.updatedAt
                                        )
                                    },
                                    "hasMore" to pagedResult.hasMore
                                )
                            )
                        } catch (e: Exception) {
                            result.error("GET_MESSAGES_BY_PAGE_ERROR", e.message, null)
                        }
                    }
                }

                "deleteMessageById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<String>("ids")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()

                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteMessageById(id)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_MESSAGE_BY_ID_ERROR", e.message, null)
                        }
                    }
                }

                "deleteAllMessages" -> {
                    mainJob.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteAllMessages()
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_ALL_MESSAGES_ERROR", e.message, null)
                        }
                    }
                }

                "cacheSuggestions" -> {
                    mainJob.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val ids = call.argument<String>("ids")
                                val packageName = call.argument<String>("packageName")
                                    ?: return@withContext result.error(
                                        "ARG_ERROR",
                                        "packageName 不能为空",
                                        null
                                    )


                                val idList = ids?.split(",") ?: emptyList()
                                OmniLog.d("CacheChannel", "idList: $idList")

                                DatabaseHelper.saveCacheSuggestion(packageName, idList)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DELETE_ALL_MESSAGES_ERROR", e.message, null)
                        }
                    }
                }

                "getCacheSuggestions" -> {
                    mainJob.launch {
                        try {
                            val packageName = call.argument<String>("packageName")
                                ?: return@launch result.error(
                                    "ARG_ERROR",
                                    "packageName 不能为空",
                                    null
                                )
                            val list = ArrayList<String>()
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.getCacheSuggestion(packageName).map { it ->
                                    list.add(it.suggestionId)
                                }
                            }
                            result.success(list)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            result.error("GET_CACHE_SUGGESTIONS_ERROR", e.message, null)
                        }
                    }
                }

                "getPagedConversations" -> {
                    result.error(
                        "NOT_IMPLEMENTED",
                        "getPagedConversations is not implemented in CacheChannel",
                        null
                    )
                }

                "getPagedMessages" -> {
                    result.error(
                        "NOT_IMPLEMENTED",
                        "getPagedMessages is not implemented in CacheChannel",
                        null
                    )
                }

                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
