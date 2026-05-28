package cn.com.omnimind.bot.agent

internal object AgentAttachmentPromptSupport {
    fun buildUserMessageText(
        text: String,
        attachments: List<Map<String, Any?>>
    ): String {
        val normalizedText = AgentTextSanitizer.sanitizeUtf16(text)
        if (attachments.isEmpty()) {
            return normalizedText
        }

        val pathHint = buildAttachmentPathHint(attachments)
        if (pathHint.isNotEmpty()) {
            return if (normalizedText.trim().isEmpty()) {
                pathHint
            } else {
                "$normalizedText\n$pathHint"
            }
        }

        val names = attachments
            .filterNot(AgentImageAttachmentSupport::isImageAttachment)
            .map(::resolveAttachmentName)
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (names.isEmpty()) {
            return normalizedText
        }
        val attachmentHint = "已附加附件：${names.joinToString("、")}"
        return if (normalizedText.trim().isEmpty()) {
            attachmentHint
        } else {
            "$normalizedText\n$attachmentHint"
        }
    }

    fun shouldSendAttachmentToModel(attachment: Map<String, Any?>): Boolean {
        return when (val raw = attachment["sendToModel"]) {
            is Boolean -> raw
            is String -> !raw.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun buildAttachmentPathHint(attachments: List<Map<String, Any?>>): String {
        val lines = attachments.mapNotNull { attachment ->
            val promptPath = resolveAttachmentPromptPath(attachment)
            if (promptPath.isEmpty()) {
                null
            } else {
                val name = resolveAttachmentName(attachment)
                if (name.isEmpty()) {
                    "- $promptPath"
                } else {
                    "- $name: $promptPath"
                }
            }
        }
        if (lines.isEmpty()) {
            return ""
        }
        return "已添加到 workspace，可通过以下路径读取：\n${lines.joinToString("\n")}"
    }

    private fun resolveAttachmentPromptPath(attachment: Map<String, Any?>): String {
        val promptPath = attachment["promptPath"]?.toString()?.trim().orEmpty()
        if (promptPath.isNotEmpty()) {
            return promptPath
        }
        val workspacePath = attachment["workspacePath"]?.toString()?.trim().orEmpty()
        if (workspacePath.isNotEmpty()) {
            return workspacePath
        }
        if (shouldSendAttachmentToModel(attachment)) {
            return ""
        }
        return attachment["path"]?.toString()?.trim().orEmpty()
    }

    private fun resolveAttachmentName(attachment: Map<String, Any?>): String {
        val name = attachment["name"]?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return name
        }
        val fileName = attachment["fileName"]?.toString()?.trim().orEmpty()
        if (fileName.isNotEmpty()) {
            return fileName
        }
        val path = attachment["path"]?.toString()?.trim().orEmpty()
        if (path.isEmpty()) {
            return ""
        }
        return path.replace('\\', '/').substringAfterLast('/')
    }
}
