package cn.com.omnimind.baselib.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.OmniLog
import org.json.JSONObject

object DatabaseHelper {
    // 保留既有 OSS 数据库文件名，避免用户升级后丢失本地数据。
    private const val LOCAL_DATABASE_NAME = AppDatabase.DATABASE_NAME + "oss"
    private var database: AppDatabase? = null

    // Migration from version 1 to 2 - adding cache_suggestion table
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create cache_suggestion table
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cache_suggestion` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `suggestionId` TEXT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `indexNum` INTEGER NOT NULL
                )
            """.trimIndent()
            )
        }
    }

    // Migration from version 2 to 3 - updating execution_records table
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Since we're adding non-nullable columns, we need to recreate the table
            // First, rename the existing table
            database.execSQL("ALTER TABLE execution_records RENAME TO execution_records_old")
            
            // Create the new table with updated schema
            database.execSQL(
                """
                CREATE TABLE execution_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    appName TEXT NOT NULL,
                    packageName TEXT NOT NULL,
                    nodeId TEXT NOT NULL,
                    suggestionId TEXT NOT NULL,
                    iconUrl TEXT,
                    type TEXT NOT NULL DEFAULT 'unknown',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            
            // Copy data from old table (filling default values for new columns)
            database.execSQL(
                """
                INSERT INTO execution_records (id, title, appName, packageName, nodeId, suggestionId, iconUrl, type, createdAt, updatedAt)
                SELECT id, title, appName, packageName, '' AS nodeId, '' AS suggestionId, NULL AS iconUrl, 'unknown' AS type, createdAt, updatedAt
                FROM execution_records_old
                """.trimIndent()
            )
            
            // Drop the old table
            database.execSQL("DROP TABLE execution_records_old")
        }
    }

    // Migration from version 3 to 4 - adding packageName column to favorite_records table
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Since we're adding a new column with a default value, we can simply alter the table
            database.execSQL("ALTER TABLE favorite_records ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
        }
    }

    // Migration from version 4 to 5 - adding content and status columns to execution_records table
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE execution_records ADD COLUMN content TEXT")
            database.execSQL("ALTER TABLE execution_records ADD COLUMN status TEXT NOT NULL DEFAULT 'success'")
        }
    }

    // Migration from version 5 to 6 - adding conversations table
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Keep the released v6 schema exact so later migrations can apply cleanly.
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `summary` TEXT,
                    `status` INTEGER NOT NULL DEFAULT 0,
                    `lastMessage` TEXT,
                    `messageCount` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'"
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_conversation_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `conversationMode` TEXT NOT NULL,
                    `entryId` TEXT NOT NULL,
                    `entryType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_entryId`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `entryId`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_updatedAt`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `updatedAt`)
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummary TEXT")
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummaryCutoffEntryDbId INTEGER")
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummaryUpdatedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE conversations ADD COLUMN latestPromptTokens INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE conversations ADD COLUMN promptTokenThreshold INTEGER NOT NULL DEFAULT 128000")
            database.execSQL("ALTER TABLE conversations ADD COLUMN latestPromptTokensUpdatedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `token_usage_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `isLocal` INTEGER NOT NULL DEFAULT 0,
                    `model` TEXT NOT NULL DEFAULT '',
                    `promptTokens` INTEGER NOT NULL DEFAULT 0,
                    `completionTokens` INTEGER NOT NULL DEFAULT 0,
                    `reasoningTokens` INTEGER NOT NULL DEFAULT 0,
                    `textTokens` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_token_usage_records_createdAt`
                ON `token_usage_records` (`createdAt`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_token_usage_records_conversationId`
                ON `token_usage_records` (`conversationId`)
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE token_usage_records ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `codex_thread_bindings` (
                    `conversationId` INTEGER NOT NULL,
                    `threadId` TEXT NOT NULL,
                    `cwd` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`conversationId`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_codex_thread_bindings_threadId`
                ON `codex_thread_bindings` (`threadId`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_codex_thread_bindings_updatedAt`
                ON `codex_thread_bindings` (`updatedAt`)
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN parentConversationId INTEGER"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN parentConversationMode TEXT"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN scheduledTaskId TEXT"
            )
        }
    }

    internal val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14
    )

    fun init(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, LOCAL_DATABASE_NAME
        ).addMigrations(*ALL_MIGRATIONS).build()

    }

    fun getDatabase(): AppDatabase {
        return database ?: throw IllegalStateException("Database not initialized")
    }

    // AppIcons相关方法
    suspend fun getAppIconByPackageName(packageName: String): AppIcons? {
        return getDatabase().appIconsDao().getByPackageName(packageName)
    }

    suspend fun getAppIconsByPackageNames(packageNames: List<String>): List<AppIcons> {
        return getDatabase().appIconsDao().getByPackageNames(packageNames)
    }

    // StudyRecord相关方法
    suspend fun getAllStudyRecords(): List<StudyRecord> {
        return getDatabase().studyRecordDao().getAll()
    }

    suspend fun getStudyRecordsByAppName(appName: String): List<StudyRecord> {
        return getDatabase().studyRecordDao().getByAppName(appName)
    }

    suspend fun getStudyRecordCountByAppName(): List<StudyRecordDao.AppNameCount> {
        return getDatabase().studyRecordDao().getNumGroupByAppName()
    }

    suspend fun deleteStudyRecordById(id: Long) {
        getDatabase().studyRecordDao().deleteById(id)
    }

    // FavoriteRecord新增方法
    suspend fun getAllFavoriteRecords(): List<FavoriteRecord> {
        return getDatabase().favoriteRecordDao().getAll()
    }

    // StudyRecord新增方法
    suspend fun updateStudyRecordFavoriteStatus(id: Long, isFavorite: Boolean) {
        getDatabase().studyRecordDao().updateIsFavoriteById(id, isFavorite)
    }

    suspend fun updateStudyRecordTitleAndReturnSuggestionId(id: Long, title: String): String {
        getDatabase().studyRecordDao().updateTitleById(id, title)
        var record = getDatabase().studyRecordDao().getById(id)
        return record.suggestionId
    }

    suspend fun getStudyRecordById(id: Long): StudyRecord {
        return getDatabase().studyRecordDao().getById(id)
    }

    suspend fun getFavoriteRecordsByType(type: String): List<FavoriteRecord> {
        return getDatabase().favoriteRecordDao().getByType(type)
    }

    suspend fun getFavoriteRecordById(id: Long): FavoriteRecord? {
        return getDatabase().favoriteRecordDao().getById(id)
    }

    suspend fun getFavoriteRecordCountByType(): List<FavoriteRecordDao.FavoriteCount> {
        return getDatabase().favoriteRecordDao().getFavoriteRecordCountByType()
    }

    suspend fun deleteFavoriteRecordById(id: Long) {
        getDatabase().favoriteRecordDao().deleteById(id)
    }

    suspend fun getFavoriteRecordsByTitle(title: String): List<FavoriteRecord> {
        return getDatabase().favoriteRecordDao().getByTitle(title)
    }

    suspend fun getStudyRecordsByTitle(title: String): List<StudyRecord> {
        return getDatabase().studyRecordDao().getByTitle(title)
    }

    // ExecutionRecord相关方法
    suspend fun getAllExecutionRecords(): List<ExecutionRecord> {
        return getDatabase().executionRecordDao().getAll()
    }

    suspend fun getExecutionRecordsByAppName(appName: String): List<ExecutionRecord> {
        return getDatabase().executionRecordDao().getByAppName(appName)
    }

    suspend fun getExecutionRecordCountByAppName(): List<ExecutionRecordDao.ExecutionRecordCount> {
        return getDatabase().executionRecordDao().getNumGroupByAppName()
    }

    suspend fun updateExecutionRecordTitle(id: Long, title: String) {
        getDatabase().executionRecordDao().updateTitleById(id, title)
    }

    suspend fun getExecutionRecordsByTitle(title: String): List<ExecutionRecord> {
        return getDatabase().executionRecordDao().getByTitle(title)
    }

    suspend fun getExecutionRecordCountByTitle(): List<ExecutionRecordDao.ExecutionRecordTitleCount> {
        return getDatabase().executionRecordDao().getNumGroupByTitle()
    }

    suspend fun deleteExecutionRecordById(id: Long) {
        getDatabase().executionRecordDao().deleteById(id)
    }

    suspend fun deleteExecutionRecordByNodeAndSuggestionId(nodeId: String, suggestionId: String) {
        getDatabase().executionRecordDao().deleteByNodeAndSuggestionId(nodeId, suggestionId)
    }

    suspend fun getExecutionRecordsByNodeAndSuggestionId(nodeId: String, suggestionId: String): List<ExecutionRecord> {
        return getDatabase().executionRecordDao().getByNodeAndSuggestionId(nodeId, suggestionId)
    }

    suspend fun getTaskExecutionInfos(): List<ExecutionRecordDao.TaskExecutionInfoDTO> {
        return getDatabase().executionRecordDao().getTaskExecutionInfos()
    }

    // Message相关方法
    suspend fun insertMessage(message: Message): Long {
        return getDatabase().messageDao().insert(message)
    }

    suspend fun updateMessage(message: Message) {
        getDatabase().messageDao().update(message)
    }

    suspend fun getMessageById(id: Long): Message? {
        return getDatabase().messageDao().getById(id)
    }

    suspend fun getMessagesByPage(page: Int, pageSize: Int): PagedMessagesResult {
        val offset = page * pageSize
        val messages = getDatabase().messageDao().getMessagesByPage(offset, pageSize)
        val totalMessageCount = getDatabase().messageDao().getMessageCount()
        val hasMore = offset + messages.size < totalMessageCount
        return PagedMessagesResult(
            messageList = messages, hasMore = hasMore
        )
    }

    suspend fun deleteMessageById(id: Long): Int {
        return getDatabase().messageDao().deleteById(id)
    }

    suspend fun deleteAllMessages(): Int {
        return getDatabase().messageDao().deleteAll()
    }

    // 新增insert方法
    suspend fun insertAppIcon(
        appName: String,
        packageName: String,
        iconBase64: String,
        iconPath: String = ""
    ): Boolean {
        return getDatabase().appIconsDao().insert(
            AppIcons(
                id = 0,
                appName = appName,
                packageName = packageName,
                icon_base64 = iconBase64,
                icon_path = iconPath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        ) > 0
    }


    /**
     * 沉淀学习记录方法
     */
    suspend fun saveStudyRecord(
        context: Context,
        title: String,
        packageName: String,
        suggestionId: String
    ): Long {
        var appName = APPPackageUtil.getAppName(context, packageName)
        val appIconBase64 = APPPackageUtil.getAppIconBase64(context, packageName)
        val appIconPath = APPPackageUtil.getAppIconFilePath(context, packageName)

        val app = getDatabase().appIconsDao().getByPackageName(packageName)
        if (appName.isNotEmpty() && appIconBase64.isNotEmpty()) {
            if (app == null) {
                getDatabase().appIconsDao().insert(
                    AppIcons(
                        id = 0,
                        appName = APPPackageUtil.getAppName(context, packageName),
                        packageName = packageName,
                        icon_base64 = APPPackageUtil.getAppIconBase64(context, packageName),
                        icon_path = appIconPath,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                getDatabase().appIconsDao().update(
                    AppIcons(
                        id = app.id,
                        appName = appName,
                        packageName = packageName,
                        icon_base64 = appIconBase64,
                        icon_path = appIconPath,
                        createdAt = app.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            if (app != null) {
                appName = app.appName
            }
        }

        var id = getDatabase().studyRecordDao().insert(
            StudyRecord(
                id = 0,
                suggestionId = suggestionId,
                title = "${appName}-${title}",
                appName = appName,
                packageName = packageName,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return id
    }

    suspend fun insertExecutionRecord(
        title: String, 
        appName: String, 
        packageName: String,
        nodeId: String,
        suggestionId: String,
        iconUrl: String? = null,
        type: String = "unknown",
        content: String? = null
    ): Boolean {
        return getDatabase().executionRecordDao().insert(
            ExecutionRecord(
                id = 0,
                title = title,
                appName = appName,
                packageName = packageName,
                nodeId = nodeId,
                suggestionId = suggestionId,
                iconUrl = iconUrl,
                type = type,
                content = content,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        ) > 0
    }

    /**
     * 沉淀学习记录方法
     * @return 返回新插入记录的 ID，如果插入失败返回 -1
     */
    suspend fun saveExecutionRecord(
        context: Context, 
        title: String, 
        packageName: String,
        nodeId: String,
        suggestionId: String,
        iconUrl: String? = null,
        type: String = "unknown",
        content: String? = null
    ): Long {
        return try {
            var appName = APPPackageUtil.getAppName(context, packageName)
            val appIconBase64 = APPPackageUtil.getAppIconBase64(context, packageName)
            val appIconPath = APPPackageUtil.getAppIconFilePath(context, packageName)

            val app = getDatabase().appIconsDao().getByPackageName(packageName)
            if (appName.isNotEmpty() && appIconBase64.isNotEmpty()) {
                if (app == null) {
                    getDatabase().appIconsDao().insert(
                        AppIcons(
                            id = 0,
                            appName = appName,
                            packageName = packageName,
                            icon_path = appIconPath,
                            icon_base64 = appIconPath,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    getDatabase().appIconsDao().update(
                        AppIcons(
                            id = app.id,
                            appName = appName,
                            packageName = packageName,
                            icon_base64 = appIconBase64,
                            icon_path = appIconPath,
                            createdAt = app.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                if (app != null) {
                    appName = app.appName
                }
            }
            getDatabase().executionRecordDao().insert(
                ExecutionRecord(
                    id = 0,
                    title = title,
                    appName = appName,
                    packageName = packageName,
                    nodeId = nodeId,
                    suggestionId = suggestionId,
                    iconUrl = iconUrl,
                    type = type,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            OmniLog.e("DatabaseHelper", "Failed to save execution record: ${e.message}")
            -1L
        }
    }

    /**
     * 更新执行记录的状态
     */
    suspend fun updateExecutionRecordStatus(id: Long, status: String) {
        if (id > 0) {
            getDatabase().executionRecordDao().updateStatusById(id, status, System.currentTimeMillis())
        }
    }

    suspend fun insertFavoriteRecord(
        title: String, desc: String, type: String, imagePath: String, packageName: String = ""
    ): Long {
        return getDatabase().favoriteRecordDao().insert(
            FavoriteRecord(
                id = 0,
                title = title,
                desc = desc,
                type = type,
                imagePath = imagePath,
                packageName = packageName, // 添加packageName参数
                status = 0, // 默认为识别中
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveFavoriteRecord(
        context: Context, title: String, desc: String, type: String, imagePath: String, packageName: String = ""
    ): Long {
        var appName = APPPackageUtil.getAppName(context, packageName)
        val appIconBase64 = APPPackageUtil.getAppIconBase64(context, packageName)
        val appIconPath = APPPackageUtil.getAppIconFilePath(context, packageName)

        val app = getDatabase().appIconsDao().getByPackageName(packageName)
        if (appName.isNotEmpty() && appIconBase64.isNotEmpty()) {
            if (app == null) {
                getDatabase().appIconsDao().insert(
                    AppIcons(
                        id = 0,
                        appName = appName,
                        packageName = packageName,
                        icon_path = appIconPath,
                        icon_base64 = appIconPath,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                getDatabase().appIconsDao().update(
                    AppIcons(
                        id = app.id,
                        appName = appName,
                        packageName = packageName,
                        icon_base64 = appIconBase64,
                        icon_path = appIconPath,
                        createdAt = app.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            if (app != null) {
                appName = app.appName
            }
        }

        return getDatabase().favoriteRecordDao().insert(
            FavoriteRecord(
                id = 0,
                title = title,
                desc = desc,
                type = type,
                imagePath = imagePath,
                packageName = packageName, // 添加packageName参数
                status = 0, // 默认为识别中
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // 添加更新状态的方法
    suspend fun updateFavoriteRecordStatus(id: Long, status: Int) {
        val record = getDatabase().favoriteRecordDao().getById(id)
        if (record != null) {
            val updatedRecord = record.copy(status = status, updatedAt = System.currentTimeMillis())
            getDatabase().favoriteRecordDao().update(updatedRecord)
        }
    }

    // 添加更新标题的方法
    suspend fun updateFavoriteRecordTitle(id: Long, title: String) {
        val record = getDatabase().favoriteRecordDao().getById(id)
        if (record != null) {
            val updatedRecord = record.copy(title = title, updatedAt = System.currentTimeMillis())
            getDatabase().favoriteRecordDao().update(updatedRecord)
        }
    }

    // 添加更新标题的方法
    suspend fun updateFavoriteRecordDesc(id: Long, desc: String) {
        val record = getDatabase().favoriteRecordDao().getById(id)
        if (record != null) {
            val updatedRecord = record.copy(desc = desc, updatedAt = System.currentTimeMillis())
            getDatabase().favoriteRecordDao().update(updatedRecord)
        }
    }

    // 保存缓存的suggestion
    suspend fun saveCacheSuggestion(packageName: String, suggestionIds: List<String>) {
        getDatabase().cacheSuggestionDao().deleteByPackageName(packageName)
        if (!suggestionIds.isEmpty()) {
            val cacheSuggestions = suggestionIds.map { suggestionId ->
                CacheSuggestion(
                    id = 0,
                    suggestionId = suggestionId,
                    packageName = packageName,
                    indexNum = suggestionIds.indexOf(suggestionId)
                )
            }
            getDatabase().cacheSuggestionDao().insertList(cacheSuggestions)
        }

    }

    // 通过 ID 更新执行记录的总结内容（精确更新单条记录，避免覆盖历史）
    suspend fun updateExecutionRecordContentById(
        id: Long,
        content: String
    ) {
        getDatabase().executionRecordDao().updateContentById(
            id = id,
            content = content,
            updatedAt = System.currentTimeMillis()
        )
    }

    // 获取缓存的suggestion
    suspend fun getCacheSuggestion(packageName: String): List<CacheSuggestion> {
        return getDatabase().cacheSuggestionDao().getListByPackageName(packageName)
    }

    // TokenUsageRecord相关方法
    suspend fun insertTokenUsageRecord(record: TokenUsageRecord): Long {
        return getDatabase().tokenUsageRecordDao().insert(record)
    }

    suspend fun getTokenUsageRecordsSince(since: Long): List<TokenUsageRecord> {
        return getDatabase().tokenUsageRecordDao().getRecordsSince(since)
    }

    // Conversation相关方法
    suspend fun insertConversation(conversation: Conversation): Long {
        return getDatabase().conversationDao().insert(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        getDatabase().conversationDao().update(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        getDatabase().conversationDao().delete(conversation)
    }

    suspend fun deleteConversationById(id: Long): Int {
        return getDatabase().conversationDao().deleteById(id)
    }

    suspend fun getConversationById(id: Long): Conversation? {
        return getDatabase().conversationDao().getById(id)
    }

    suspend fun getAllConversations(): List<Conversation> {
        return getDatabase().conversationDao().getAll()
    }

    suspend fun getConversationsByPage(offset: Int, limit: Int): List<Conversation> {
        return getDatabase().conversationDao().getConversationsByPage(offset, limit)
    }

    suspend fun getConversationCount(): Int {
        return getDatabase().conversationDao().getConversationCount()
    }

    suspend fun upsertAgentConversationEntry(entry: AgentConversationEntry): Long {
        return getDatabase().agentConversationEntryDao().upsert(entry)
    }

    suspend fun getAgentConversationEntriesAsc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesAsc(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun getAgentConversationEntriesAscSafe(
        conversationId: Long,
        conversationMode: String,
        payloadLimit: Int,
        summaryLimit: Int
    ): List<AgentConversationEntryRecord> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesAscSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            payloadLimit = payloadLimit,
            summaryLimit = summaryLimit
        )
    }

    suspend fun getAgentConversationEntriesDesc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDesc(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun getAgentConversationEntriesDescSafe(
        conversationId: Long,
        conversationMode: String,
        payloadLimit: Int,
        summaryLimit: Int
    ): List<AgentConversationEntryRecord> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDescSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            payloadLimit = payloadLimit,
            summaryLimit = summaryLimit
        )
    }

    suspend fun getAgentConversationEntryByThreadAndId(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getByThreadAndEntryId(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId
        )
    }

    suspend fun getAgentConversationEntryByThreadAndIdSafe(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        payloadLimit: Int,
        summaryLimit: Int
    ): AgentConversationEntryRecord? {
        return getDatabase().agentConversationEntryDao().getByThreadAndEntryIdSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            payloadLimit = payloadLimit,
            summaryLimit = summaryLimit
        )
    }

    suspend fun deleteAgentConversationThread(
        conversationId: Long,
        conversationMode: String
    ): Int {
        return getDatabase().agentConversationEntryDao().deleteThreadEntries(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun deleteAgentConversationEntries(conversationId: Long): Int {
        return getDatabase().agentConversationEntryDao().deleteConversationEntries(conversationId)
    }

    suspend fun getLatestAgentConversationEntry(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getLatestConversationEntry(conversationId)
    }

    suspend fun getLatestAgentConversationEntryHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getLatestConversationEntryHeader(
            conversationId
        )
    }

    suspend fun getLatestAgentConversationUpdate(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getLatestConversationUpdate(conversationId)
    }

    suspend fun getLatestAgentConversationUpdateHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getLatestConversationUpdateHeader(
            conversationId
        )
    }

    suspend fun getEarliestAgentConversationEntry(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getEarliestConversationEntry(conversationId)
    }

    suspend fun getEarliestAgentConversationEntryHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getEarliestConversationEntryHeader(
            conversationId
        )
    }

    suspend fun countAgentConversationEntries(conversationId: Long): Int {
        return getDatabase().agentConversationEntryDao().countConversationEntries(conversationId)
    }

    suspend fun getAgentConversationEntriesDescPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDescPaged(
            conversationId = conversationId,
            conversationMode = conversationMode,
            limit = limit,
            offset = offset
        )
    }

    suspend fun getAgentConversationEntriesDescPagedSafe(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int,
        payloadLimit: Int,
        summaryLimit: Int
    ): List<AgentConversationEntryRecord> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDescPagedSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            limit = limit,
            offset = offset,
            payloadLimit = payloadLimit,
            summaryLimit = summaryLimit
        )
    }

    suspend fun countAgentConversationThreadEntries(
        conversationId: Long,
        conversationMode: String
    ): Int {
        return getDatabase().agentConversationEntryDao().countThreadEntries(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun incrementConversationMessageCount(id: Long) {
        getDatabase().conversationDao().incrementMessageCount(id, System.currentTimeMillis())
    }

    suspend fun upsertCodexThreadBinding(binding: CodexThreadBinding) {
        getDatabase().codexThreadBindingDao().upsert(binding)
    }

    suspend fun getCodexThreadBindingByConversationId(conversationId: Long): CodexThreadBinding? {
        return getDatabase().codexThreadBindingDao().getByConversationId(conversationId)
    }

    suspend fun getCodexThreadBindingByThreadId(threadId: String): CodexThreadBinding? {
        return getDatabase().codexThreadBindingDao().getByThreadId(threadId)
    }

    suspend fun deleteCodexThreadBindingByConversationId(conversationId: Long): Int {
        return getDatabase().codexThreadBindingDao().deleteByConversationId(conversationId)
    }

    /**
     * 保存任务结果消息到Message表
     * 用于将任务总结结果包含在聊天上下文中
     *
     * @param messageId 消息ID（如 "vlm-summary-123"）
     * @param taskType 任务类型（如 "vlm_summary"）
     * @param content 任务结果文本内容
     * @param executionRecordId 关联的执行记录ID
     * @param metadata 额外的元数据（如goal, finishType等）
     * @return 新插入消息的行ID，失败返回-1
     */
    suspend fun insertTaskResultMessage(
        messageId: String,
        taskType: String,
        content: String,
        executionRecordId: Long,
        metadata: Map<String, Any> = emptyMap()
    ): Long {
        return try {
            val payload = JSONObject().apply {
                put("text", content)
                put("taskType", taskType)
                put("executionRecordId", executionRecordId)
                metadata.forEach { (key, value) ->
                    put(key, value.toString())
                }
            }.toString()

            insertMessage(
                Message(
                    messageId = messageId,
                    type = 1,  // 普通消息
                    user = 2,  // AI消息
                    content = payload,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            OmniLog.e("DatabaseHelper", "Failed to insert task result message: ${e.message}")
            -1L
        }
    }
}
