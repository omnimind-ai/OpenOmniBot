package cn.com.omnimind.bot.agent.tool.handlers

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal data class HatchPetWriteOverride(
    val path: String,
    val content: String,
    val petJsonPath: String? = null,
    val petJsonContent: String? = null
)

private data class PromptPetDetails(
    val name: String,
    val petType: String? = null,
    val visualStyle: String? = null,
    val personality: String? = null,
    val mainColors: String? = null,
    val signatureElements: String? = null
)

private const val HATCH_PET_SKILL_ID = "hatch-pet"
private const val MAX_PET_DESCRIPTION_CHARS = 34

private val PROMPT_FIELD_LABELS = listOf(
    "宠物名称", "宠物名", "宠物名字", "pet name", "name",
    "宠物类型", "pet type", "type",
    "视觉风格", "visual style", "style",
    "性格设定", "personality", "性格",
    "主要颜色", "主色", "颜色", "main colors", "main color", "colors", "color palette",
    "标志元素", "标志性元素", "标志物", "signature elements", "signature element",
    "iconic element", "distinctive element", "symbol"
)

internal fun normalizeHatchPetWrite(
    rawPath: String,
    rawContent: String,
    userMessage: String,
    activeSkillIds: Set<String>
): HatchPetWriteOverride? {
    if (HATCH_PET_SKILL_ID !in activeSkillIds) {
        return null
    }
    val promptDetails = extractPromptPetDetails(userMessage)
        ?: extractPetDetailsFromJson(rawContent)
        ?: extractPetDetailsFromPath(rawPath)
        ?: return null
    val normalizedPath = rawPath.trim().replace('\\', '/')
    if (normalizedPath.isBlank()) {
        return null
    }
    val lowerPath = normalizedPath.lowercase(Locale.US)
    val fileName = lowerPath.substringAfterLast('/')
    val petRelated = lowerPath.contains("/.omnibot/pets/") ||
        lowerPath.contains("/pets/") ||
        fileName in setOf("pet.json", "pet.svg", "current.svg", "current.png", "current.webp")
    if (!petRelated) {
        return null
    }
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    val targetName = when {
        fileName == "spritesheet.webp" -> "spritesheet.webp"
        fileName == "spritesheet.png" -> "spritesheet.png"
        else -> when (extension) {
        "json" -> "pet.json"
        "svg" -> "current.svg"
        "png" -> "current.png"
        "webp" -> "current.webp"
        "jpg", "jpeg" -> "current.jpg"
        "gif" -> "current.gif"
        else -> return null
        }
    }
    val petId = stablePetIdFromName(promptDetails.name)
    val content = if (targetName == "pet.json") {
        rewritePetJson(rawContent, petId, promptDetails)
    } else {
        rawContent
    }
    val petJsonContent = if (targetName == "pet.json") {
        null
    } else {
        buildPetJson(petId, promptDetails, targetName)
    }
    return HatchPetWriteOverride(
        path = "/workspace/.omnibot/pets/$petId/$targetName",
        content = content,
        petJsonPath = if (petJsonContent == null) null else "/workspace/.omnibot/pets/$petId/pet.json",
        petJsonContent = petJsonContent
    )
}

internal fun decodeImageWriteContentForFileName(fileName: String, content: String): ByteArray? {
    if (!isBinaryImageFileName(fileName)) {
        return null
    }
    val trimmed = content.trim()
    if (trimmed.isBlank()) {
        return null
    }
    val encoded = when {
        trimmed.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = trimmed.indexOf(',')
            if (commaIndex <= 0) return null
            val header = trimmed.substring(0, commaIndex)
            if (!header.contains(";base64", ignoreCase = true)) return null
            trimmed.substring(commaIndex + 1)
        }
        trimmed.startsWith("base64:", ignoreCase = true) -> trimmed.substringAfter(':')
        else -> trimmed
    }.filterNot { it.isWhitespace() }
    if (encoded.length < 16 || !encoded.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
        return null
    }
    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    val bytes = runCatching { Base64.getDecoder().decode(padded) }
        .recoverCatching { Base64.getUrlDecoder().decode(padded) }
        .getOrNull()
        ?: return null
    return bytes.takeIf { bytesMatchImageExtension(fileName, it) }
}

internal fun normalizeSvgWriteContentForFileName(fileName: String, content: String): String {
    if (!fileName.endsWith(".svg", ignoreCase = true)) {
        return content
    }
    val trimmed = content.trim()
    val svgStart = trimmed.indexOf("<svg", ignoreCase = true)
    val svgEnd = trimmed.lastIndexOf("</svg>", ignoreCase = true)
    if (svgStart < 0 || svgEnd < svgStart) {
        return content
    }
    return inlineSimpleSvgClassStyles(trimmed.substring(svgStart, svgEnd + "</svg>".length))
}

private fun inlineSimpleSvgClassStyles(svg: String): String {
    val classStyles = mutableMapOf<String, Map<String, String>>()
    val styleRegex = Regex("""<style\b[^>]*>([\s\S]*?)</style>""", RegexOption.IGNORE_CASE)
    val classRuleRegex = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)\s*\{([^}]*)}""")
    styleRegex.findAll(svg).forEach { styleMatch ->
        classRuleRegex.findAll(styleMatch.groups[1]?.value.orEmpty()).forEach { ruleMatch ->
            val className = ruleMatch.groups[1]?.value.orEmpty()
            val declarations = ruleMatch.groups[2]?.value.orEmpty()
                .split(';')
                .mapNotNull { declaration ->
                    val parts = declaration.split(':', limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val property = parts[0].trim().lowercase(Locale.US)
                    val value = parts[1].trim()
                    if (property.isBlank() || value.isBlank()) null else property to value
                }
                .toMap()
            if (className.isNotBlank() && declarations.isNotEmpty()) {
                classStyles[className] = declarations
            }
        }
    }
    if (classStyles.isEmpty()) {
        return svg
    }

    val withoutStyleBlocks = svg.replace(styleRegex, "")
    val elementWithClassRegex = Regex("""<([A-Za-z][A-Za-z0-9:_-]*)([^<>]*?)\sclass=(["'])([^"']+)\3([^<>]*?)(/?)>""")
    return withoutStyleBlocks.replace(elementWithClassRegex) { match ->
        val tagName = match.groups[1]?.value.orEmpty()
        val beforeClass = match.groups[2]?.value.orEmpty()
        val classNames = match.groups[4]?.value.orEmpty().split(Regex("""\s+"""))
        val afterClass = match.groups[5]?.value.orEmpty()
        val selfClosing = match.groups[6]?.value.orEmpty()
        val declarations = linkedMapOf<String, String>()
        classNames.forEach { className ->
            classStyles[className]?.forEach { (property, value) ->
                declarations[property] = value
            }
        }
        if (declarations.isEmpty()) {
            return@replace match.value
        }
        val rawAttributes = "$beforeClass$afterClass"
        val attributeRegex = Regex("""\s([A-Za-z_:][A-Za-z0-9:_.-]*)=(["'])(.*?)\2""")
        val existingAttributes = attributeRegex.findAll(rawAttributes)
            .map { it.groups[1]?.value.orEmpty().lowercase(Locale.US) }
            .toSet()
        val inlineAttributes = declarations
            .filterKeys { it !in existingAttributes }
            .entries
            .joinToString("") { (property, value) -> " $property=\"$value\"" }
        "<$tagName$rawAttributes$inlineAttributes$selfClosing>"
    }
}

private fun isBinaryImageFileName(fileName: String): Boolean {
    return when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> true
        else -> false
    }
}

private fun bytesMatchImageExtension(fileName: String, bytes: ByteArray): Boolean {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    return when (extension) {
        "png" -> bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        "jpg", "jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        "webp" -> bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte()
        "gif" -> bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()
        "bmp" -> bytes.size >= 2 &&
            bytes[0] == 0x42.toByte() &&
            bytes[1] == 0x4D.toByte()
        else -> false
    }
}

private fun extractPromptPetDetails(userMessage: String): PromptPetDetails? {
    val name = extractPromptField(
        userMessage,
        listOf("宠物名称", "宠物名", "宠物名字", "pet name", "name")
    )?.let(::normalizePromptValue) ?: return null
    return PromptPetDetails(
        name = name,
        petType = extractPromptField(
            userMessage,
            listOf("宠物类型", "pet type", "type")
        )?.let(::normalizePromptValue),
        visualStyle = extractPromptField(
            userMessage,
            listOf("视觉风格", "visual style", "style")
        )?.let(::normalizePromptValue),
        personality = extractPromptField(
            userMessage,
            listOf("性格设定", "personality", "性格")
        )?.let(::normalizePromptValue),
        mainColors = extractPromptField(
            userMessage,
            listOf("主要颜色", "主色", "颜色", "main colors", "main color", "colors", "color palette")
        )?.let(::normalizePromptValue),
        signatureElements = extractPromptField(
            userMessage,
            listOf("标志元素", "标志性元素", "标志物", "signature elements", "signature element", "iconic element", "distinctive element", "symbol")
        )?.let(::normalizePromptValue)
    )
}

private fun extractPetDetailsFromJson(rawContent: String): PromptPetDetails? {
    return runCatching {
        val jsonObject = JsonParser.parseString(rawContent).asJsonObject
        val name = firstJsonString(
            jsonObject,
            "displayName",
            "display_name",
            "name",
            "id"
        )?.let(::normalizePromptValue)
        if (name.isNullOrBlank()) {
            null
        } else {
            PromptPetDetails(
                name = name,
                petType = firstJsonString(jsonObject, "petType", "pet_type", "type")?.let(::normalizePromptValue),
                visualStyle = firstJsonString(jsonObject, "visualStyle", "visual_style", "style")?.let(::normalizePromptValue),
                personality = firstJsonString(jsonObject, "personality", "personalitySetting", "personality_setting")?.let(::normalizePromptValue),
                mainColors = firstJsonString(jsonObject, "mainColors", "main_colors", "colors", "colorPalette", "color_palette")?.let(::normalizePromptValue),
                signatureElements = firstJsonString(jsonObject, "signatureElements", "signature_elements", "signature", "symbol")?.let(::normalizePromptValue)
            )
        }
    }.getOrNull()
}

private fun firstJsonString(jsonObject: com.google.gson.JsonObject, vararg keys: String): String? {
    for (key in keys) {
        val value = runCatching { jsonObject.get(key)?.asString?.trim() }.getOrNull()
        if (!value.isNullOrBlank()) {
            return value
        }
    }
    return null
}

private fun extractPetDetailsFromPath(rawPath: String): PromptPetDetails? {
    val normalized = rawPath.trim().replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    val parentName = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        .substringAfterLast('/')
    val baseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val candidate = when {
        parentName.isNotBlank() &&
            parentName !in setOf("pets", ".omnibot", "workspace") -> parentName
        baseName.isNotBlank() &&
            baseName !in setOf("current", "pet", "spritesheet", "atlas") -> baseName
        else -> null
    } ?: return null
    return PromptPetDetails(name = normalizePromptValue(candidate))
}

private fun extractPromptField(userMessage: String, labels: List<String>): String? {
    val text = userMessage
        .lineSequence()
        .joinToString("\n") { rawLine ->
            rawLine
                .replaceFirst(Regex("""^\s*[-*]\s*"""), "")
                .replaceFirst(Regex("""^\s*#+\s*"""), "")
                .trim()
        }
        .trim()
    if (text.isBlank()) {
        return null
    }

    val allLabelPattern = PROMPT_FIELD_LABELS
        .distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    for (label in labels.distinct().sortedByDescending { it.length }) {
        val pattern = Regex(
            """(?:^|[\s，,。；;])${Regex.escape(label)}\s*[:：]\s*([\s\S]*?)(?=(?:[\s，,。；;]+(?:$allLabelPattern)\s*[:：])|$)""",
            RegexOption.IGNORE_CASE
        )
        val value = pattern.find(text)?.groups?.get(1)?.value?.trim()
        if (!value.isNullOrBlank()) {
            return value
        }
    }
    return null
}

private fun normalizePromptValue(value: String): String {
    return value
        .trim()
        .trim('。', '.', '，', ',', '；', ';', '：', ':')
        .takeIf { it.isNotBlank() }
        .orEmpty()
}

private fun stablePetIdFromName(name: String): String {
    val safeName = name.trim()
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]+"""), "-")
        .replace(Regex("""\s+"""), "-")
        .replace(Regex("""-+"""), "-")
        .trim('-', '.', '_')
    if (safeName.isNotBlank()) {
        return safeName.lowercase(Locale.US).take(80)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(10)
    return "pet-$digest"
}

private fun rewritePetJson(rawContent: String, petId: String, promptDetails: PromptPetDetails): String {
    return runCatching {
        val jsonObject = JsonParser.parseString(rawContent).asJsonObject
        jsonObject.addProperty("id", petId)
        jsonObject.addProperty("displayName", promptDetails.name)
        jsonObject.addProperty("name", promptDetails.name)
        if (jsonObject.has("spritesheetPath") || jsonObject.has("spritesheet_path")) {
            jsonObject.addProperty("spritesheetPath", "spritesheet.webp")
            jsonObject.remove("imagePath")
            jsonObject.remove("image_path")
        } else {
            jsonObject.addProperty("imagePath", "current.svg")
        }
        promptDetails.petType?.takeIf { it.isNotBlank() }?.let {
            jsonObject.addProperty("petType", it)
        }
        promptDetails.visualStyle?.takeIf { it.isNotBlank() }?.let {
            jsonObject.addProperty("visualStyle", it)
        }
        promptDetails.personality?.takeIf { it.isNotBlank() }?.let {
            jsonObject.addProperty("personality", it)
        }
        promptDetails.mainColors?.takeIf { it.isNotBlank() }?.let {
            jsonObject.addProperty("mainColors", it)
        }
        promptDetails.signatureElements?.takeIf { it.isNotBlank() }?.let {
            jsonObject.addProperty("signatureElements", it)
        }
        buildPetDescription(promptDetails)?.let {
            jsonObject.addProperty("description", it)
        }
        GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
            .toJson(jsonObject)
    }.getOrElse {
        rawContent
    }
}

private fun buildPetJson(petId: String, promptDetails: PromptPetDetails, imageFileName: String): String {
    val jsonObject = com.google.gson.JsonObject()
    jsonObject.addProperty("id", petId)
    jsonObject.addProperty("displayName", promptDetails.name)
    jsonObject.addProperty("name", promptDetails.name)
    if (imageFileName == "spritesheet.webp" || imageFileName == "spritesheet.png") {
        jsonObject.addProperty("spritesheetPath", imageFileName)
    } else {
        jsonObject.addProperty("imagePath", imageFileName)
    }
    promptDetails.petType?.takeIf { it.isNotBlank() }?.let {
        jsonObject.addProperty("petType", it)
    }
    promptDetails.visualStyle?.takeIf { it.isNotBlank() }?.let {
        jsonObject.addProperty("visualStyle", it)
    }
    promptDetails.personality?.takeIf { it.isNotBlank() }?.let {
        jsonObject.addProperty("personality", it)
    }
    promptDetails.mainColors?.takeIf { it.isNotBlank() }?.let {
        jsonObject.addProperty("mainColors", it)
    }
    promptDetails.signatureElements?.takeIf { it.isNotBlank() }?.let {
        jsonObject.addProperty("signatureElements", it)
    }
    jsonObject.addProperty(
        "description",
        buildPetDescription(promptDetails) ?: "${promptDetails.name}，适合桌面悬浮的自定义电子宠物。"
    )
    return GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()
        .toJson(jsonObject)
}

private fun buildPetDescription(details: PromptPetDetails): String? {
    val parts = listOfNotNull(
        compactPetDescriptionPart(details.petType, maxSegments = 1),
        compactPetDescriptionPart(
            details.visualStyle,
            maxSegments = 1,
            dropContains = listOf("适合桌面悬浮", "轮廓清晰", "无背景", "缩小后")
        ),
        compactPetDescriptionPart(details.personality, maxSegments = 1)
    ).distinct()
    if (parts.isEmpty()) {
        return null
    }
    val sentence = parts.joinToString("，").trim().let { if (it.endsWith("。")) it else "$it。" }
    return limitPetDescription(sentence)
}

private fun compactPetDescriptionPart(
    value: String?,
    maxSegments: Int,
    dropContains: List<String> = emptyList()
): String? {
    return value
        ?.replace('\n', ' ')
        ?.split(Regex("[，,；;。.]"))
        ?.asSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.filterNot { segment -> dropContains.any { segment.contains(it) } }
        ?.filterNot { segment -> segment.startsWith("要求") }
        ?.take(maxSegments)
        ?.joinToString("，")
        ?.takeIf { it.isNotBlank() }
}

private fun limitPetDescription(value: String): String {
    if (value.length <= MAX_PET_DESCRIPTION_CHARS) {
        return value
    }
    val trimmed = value
        .take(MAX_PET_DESCRIPTION_CHARS - 1)
        .trimEnd('，', ',', '；', ';', '。', '.', ' ')
    return "$trimmed。"
}

private fun looksLikePathDescription(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("/workspace/") ||
        trimmed.startsWith("/") ||
        trimmed.startsWith("custom:") ||
        trimmed.contains("/.omnibot/pets/") ||
        trimmed.contains("/pets/")
}

class FileToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "file_read", "file_write", "file_edit", "file_list", "file_search", "file_stat", "file_move"
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "file_read" -> executeFileRead(args, env.workspaceDescriptor, callback)
            "file_write" -> executeFileWrite(args, env.workspaceDescriptor, env, callback)
            "file_edit" -> executeFileEdit(args, env.workspaceDescriptor, callback)
            "file_list" -> executeFileList(args, env.workspaceDescriptor, callback)
            "file_search" -> executeFileSearch(args, env.workspaceDescriptor, callback)
            "file_stat" -> executeFileStat(args, env.workspaceDescriptor, callback)
            "file_move" -> executeFileMove(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown file tool")
        }
    }

    private suspend fun executeFileRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists()) { "文件不存在：${file.absolutePath}" }
            require(file.isFile) { "目标不是文件：${file.absolutePath}" }
            val maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(128, 64_000)
                ?: SharedHelper.DEFAULT_FILE_READ_MAX_CHARS
            val offset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            val lineStart = args["lineStart"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val lineCount = args["lineCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val shellPath = workspaceManager.shellPathForAndroid(file) ?: file.absolutePath
            val mimeType = workspaceManager.guessMimeType(file)
            val imageReadResult = if (isImageFile(file, mimeType)) {
                AgentImageAttachmentSupport.buildFileReadImageResult(
                    file = file,
                    shellPath = shellPath,
                    mimeTypeHint = mimeType,
                    uri = artifact.uri,
                    sizeBytes = file.length()
                )
            } else {
                null
            }
            val payload = if (imageReadResult != null) {
                imageReadResult.payload
            } else {
                val content = file.readText()
                val sliced = when {
                    lineStart != null -> {
                        val lines = content.lines()
                        val from = (lineStart - 1).coerceAtMost(lines.size)
                        val until = if (lineCount != null) {
                            (from + lineCount).coerceAtMost(lines.size)
                        } else {
                            lines.size
                        }
                        lines.subList(from, until).joinToString("\n")
                    }
                    offset > 0 -> content.drop(offset)
                    else -> content
                }
                linkedMapOf<String, Any?>(
                    "path" to shellPath,
                    "androidPath" to file.absolutePath,
                    "uri" to artifact.uri,
                    "content" to helper.truncateText(sliced, maxChars),
                    "size" to file.length(),
                    "mimeType" to mimeType
                )
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                imageDataUrl = imageReadResult?.imageDataUrl,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取文件失败")
        }
    }

    private fun isImageFile(file: File, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        val lowerName = file.name.lowercase()
        return lowerName.endsWith(".png") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") ||
            lowerName.endsWith(".bmp") ||
            lowerName.endsWith(".heic") ||
            lowerName.endsWith(".heif")
    }

    private suspend fun executeFileWrite(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_write"
        return try {
            val rawPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty()
            val rawContent = args["content"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 content")
            val hatchPetOverride = normalizeHatchPetWrite(
                rawPath = rawPath,
                rawContent = rawContent,
                userMessage = env.userMessage,
                activeSkillIds = env.resolvedSkills.mapTo(mutableSetOf()) { it.skillId }
            )
            val path = hatchPetOverride?.path ?: rawPath
            val content = hatchPetOverride?.content ?: rawContent
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                path
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在写入文件")
            val file = workspaceManager.resolvePath(
                inputPath = path,
                workspace = workspace,
                allowPublicStorage = true
            )
            val append = args["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                val imageBytes = decodeImageWriteContentForFileName(file.name, content)
                if (imageBytes != null) {
                    file.writeBytes(imageBytes)
                } else {
                    file.writeText(normalizeSvgWriteContentForFileName(file.name, content))
                }
            }
            if (!append && hatchPetOverride?.petJsonPath != null && hatchPetOverride.petJsonContent != null) {
                val petJsonFile = workspaceManager.resolvePath(
                    inputPath = hatchPetOverride.petJsonPath,
                    workspace = workspace,
                    allowPublicStorage = true
                )
                petJsonFile.parentFile?.mkdirs()
                petJsonFile.writeText(hatchPetOverride.petJsonContent)
            }
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "append" to append
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (append) "已追加写入文件：${file.name}" else "已写入文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "写入文件失败")
        }
    }

    private suspend fun executeFileEdit(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_edit"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在编辑文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists() && file.isFile) { "目标文件不存在：${file.absolutePath}" }
            val oldText = args["oldText"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 oldText")
            val newText = args["newText"]?.jsonPrimitive?.content ?: ""
            val replaceAll = args["replaceAll"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val original = file.readText()
            require(original.contains(oldText)) { "文件中未找到 oldText" }
            val updated = if (replaceAll) {
                original.replace(oldText, newText)
            } else {
                original.replaceFirst(oldText, newText)
            }
            file.writeText(updated)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "replaceAll" to replaceAll
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已更新文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "编辑文件失败")
        }
    }

    private suspend fun executeFileList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_list"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val recursive = args["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxDepth = args["maxDepth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 2
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: SharedHelper.DEFAULT_FILE_LIST_LIMIT
            val files = if (recursive) {
                directory.walkTopDown().maxDepth(maxDepth).drop(1).take(limit).toList()
            } else {
                directory.listFiles()?.sortedBy { it.name.lowercase() }?.take(limit) ?: emptyList()
            }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to files.size,
                "items" to files.map { entry ->
                    mapOf(
                        "name" to entry.name,
                        "path" to (workspaceManager.shellPathForAndroid(entry) ?: entry.absolutePath),
                        "androidPath" to entry.absolutePath,
                        "isDirectory" to entry.isDirectory,
                        "size" to if (entry.isFile) entry.length() else 0L
                    )
                }
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("共找到 ${files.size} 项"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id,
                actions = listOf(
                    helper.buildOpenDirectoryAction(workspaceManager, workspace, directory, "打开目录")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "列目录失败")
        }
    }

    private suspend fun executeFileSearch(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_search"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.isNotEmpty()) { "缺少 query" }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val caseSensitive = args["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: SharedHelper.DEFAULT_FILE_SEARCH_LIMIT
            val searchNeedle = if (caseSensitive) query else query.lowercase()
            val results = mutableListOf<Map<String, Any?>>()
            directory.walkTopDown().forEach { file ->
                if (results.size >= maxResults) return@forEach
                if (!file.isFile) return@forEach
                val normalizedName = if (caseSensitive) file.name else file.name.lowercase()
                if (normalizedName.contains(searchNeedle)) {
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "file_name",
                            "snippet" to file.name
                        )
                    )
                    return@forEach
                }
                if (file.length() > 512 * 1024) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val haystack = if (caseSensitive) text else text.lowercase()
                val index = haystack.indexOf(searchNeedle)
                if (index >= 0) {
                    val start = (index - 40).coerceAtLeast(0)
                    val end = (index + query.length + 120).coerceAtMost(text.length)
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "content",
                            "snippet" to text.substring(start, end)
                        )
                    )
                }
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to results.size,
                "items" to results
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (results.isEmpty()) "未找到匹配结果" else "找到 ${results.size} 个匹配结果"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "搜索文件失败")
        }
    }

    private suspend fun executeFileStat(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_stat"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowRootDirectories = true,
                allowPublicStorage = true
            )
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val artifact = file.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "name" to file.name,
                "exists" to file.exists(),
                "isDirectory" to file.isDirectory,
                "isFile" to file.isFile,
                "size" to if (file.isFile) file.length() else 0L,
                "lastModified" to file.lastModified(),
                "mimeType" to if (file.isFile) workspaceManager.guessMimeType(file) else "inode/directory",
                "uri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取路径信息：${file.name.ifBlank { workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id,
                actions = if (file.isDirectory) {
                    listOf(helper.buildOpenDirectoryAction(workspaceManager, workspace, file))
                } else {
                    emptyList()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "查看文件信息失败")
        }
    }

    private suspend fun executeFileMove(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_move"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["sourcePath"]?.jsonPrimitive?.contentOrNull,
                args["targetPath"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在移动文件")
            val source = workspaceManager.resolvePath(
                args["sourcePath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            val target = workspaceManager.resolvePath(
                args["targetPath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            require(source.exists()) { "源文件不存在：${source.absolutePath}" }
            val overwrite = args["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            require(overwrite || !target.exists()) { "目标已存在：${target.absolutePath}" }
            target.parentFile?.mkdirs()
            if (overwrite && target.exists()) {
                target.deleteRecursively()
            }
            source.copyRecursively(target, overwrite = overwrite)
            source.deleteRecursively()
            val artifact = target.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "sourcePath" to (workspaceManager.shellPathForAndroid(source) ?: source.absolutePath),
                "androidSourcePath" to source.absolutePath,
                "targetPath" to (workspaceManager.shellPathForAndroid(target) ?: target.absolutePath),
                "androidTargetPath" to target.absolutePath,
                "overwrite" to overwrite,
                "targetUri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已移动到：${target.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "移动文件失败")
        }
    }
}
