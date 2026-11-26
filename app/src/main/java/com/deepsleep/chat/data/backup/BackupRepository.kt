package com.deepsleep.chat.data.backup

import android.content.Context
import android.net.Uri
import com.deepsleep.chat.data.repository.ConversationRepository
import com.deepsleep.chat.data.repository.ProviderRepository
import com.deepsleep.chat.data.repository.SettingsRepository
import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.StoredProvider
import com.deepsleep.chat.domain.model.ThemeMode
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class BackupRepository(
    private val context: Context,
    private val conversationRepository: ConversationRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val attachmentRepository: com.deepsleep.chat.data.repository.AttachmentRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    suspend fun inspectDeepSeek(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val file = copyToCache(uri)
        try {
            ZipFile(file).use { zip ->
                val version = readManifestVersion(zip)
                val index = readIndex(zip)
                val sessions = sessionEntries(zip)
                val existingSources = conversationRepository.getAllConversations()
                    .mapNotNull { it.sourceSessionId }
                    .toSet()
                val sessionCount = if (index.isNotEmpty()) index.size else sessions.size
                val messageCount = if (index.isNotEmpty()) {
                    index.sumOf { it.messageCount }
                } else {
                    0
                }
                val duplicateCount = if (index.isNotEmpty()) {
                    index.count { it.id in existingSources }
                } else {
                    sessions.count { entry ->
                        entry.name.removePrefix(PREFIX)
                            .removeSuffix(".json") in existingSources
                    }
                }
                BackupInspection(sessionCount, messageCount, duplicateCount, version)
            }
        } finally {
            file.delete()
        }
    }

    suspend fun importDeepSeek(
        uri: Uri,
        skipDuplicates: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val file = copyToCache(uri)
        try {
            ZipFile(file).use { zip ->
                readManifestVersion(zip)
                val sessions = sessionEntries(zip)
                var conversationsImported = 0
                var messagesImported = 0
                var skipped = 0
                var images = 0
                var droppedBranches = 0
                val warnings = mutableListOf<String>()

                sessions.forEachIndexed { index, entry ->
                    currentCoroutineContext().ensureActive()
                    onProgress(index + 1, sessions.size)
                    val sourceId = entry.name.removePrefix(PREFIX).removeSuffix(".json")
                    if (skipDuplicates && conversationRepository.findBySourceSessionId(sourceId) != null) {
                        skipped++
                        return@forEachIndexed
                    }
                    try {
                        if (entry.size > MAX_SESSION_BYTES) {
                            throw BackupException("单个会话过大，已跳过")
                        }
                        val text = readLimited(zip.getInputStream(entry), MAX_SESSION_BYTES.toInt())
                        val session = json.decodeFromString(DsSessionFile.serializer(), text)
                        if (skipDuplicates &&
                            session.id.isNotBlank() &&
                            conversationRepository.findBySourceSessionId(session.id) != null
                        ) {
                            skipped++
                            return@forEachIndexed
                        }
                        val converted = convertSession(session)
                        if (converted == null) {
                            skipped++
                            warnings += "跳过空会话：${session.title ?: sourceId}"
                        } else {
                            conversationRepository.insertImported(converted.conversation, converted.messages)
                            conversationsImported++
                            messagesImported += converted.messages.size
                            images += converted.imageCount
                            droppedBranches += converted.droppedBranches
                        }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        skipped++
                        warnings += "会话 $sourceId 解析失败：${t.message ?: t.javaClass.simpleName}"
                    }
                }

                if (images > 0) {
                    warnings += "官方备份中的 $images 个图片文件未导入，仅保留文件名"
                }
                if (droppedBranches > 0) {
                    warnings += "DeepSeek 会话存在分支，仅导入当前分支，忽略 $droppedBranches 条其他分支消息"
                }
                BackupImportResult(
                    conversations = conversationsImported,
                    messages = messagesImported,
                    skipped = skipped,
                    warnings = warnings,
                )
            }
        } finally {
            file.delete()
        }
    }

    suspend fun exportDeepSeek(
        uri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val conversations = conversationRepository.getAllConversations()
        val messagesByConversation = conversationRepository.getAllMessages().groupBy { it.conversationId }
        val temp = File.createTempFile("deepsleep-export-", ".zip", context.cacheDir)
        var exported = 0
        var exportedMessages = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        try {
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                zip.putJson(
                    "manifest.json",
                    buildJsonObject { put("export_format_version", EXPORT_FORMAT_VERSION) }.toString(),
                )
                val indexEntries = mutableListOf<DsIndexEntry>()
                conversations.forEachIndexed { index, conversation ->
                    currentCoroutineContext().ensureActive()
                    onProgress(index + 1, conversations.size)
                    val normalized = normalizeForExport(messagesByConversation[conversation.id].orEmpty())
                    if (normalized.isEmpty()) {
                        skipped++
                        warnings += "跳过空会话：${conversation.title}"
                        return@forEachIndexed
                    }
                    val sessionId = conversation.id
                    val messagesJson = buildJsonArray {
                        normalized.forEachIndexed { messageIndex, pair ->
                            add(
                                buildExportMessage(
                                    index = messageIndex,
                                    pair = pair,
                                    conversation = conversation,
                                ),
                            )
                        }
                    }
                    val sessionJson = buildJsonObject {
                        put("id", sessionId)
                        put("title", conversation.title)
                        put("pinned", conversation.pinned)
                        put("current_message_id", normalized.size.toLong())
                        put("inserted_at", iso(conversation.createdAt))
                        put("updated_at", iso(conversation.updatedAt))
                        put("messages", messagesJson)
                    }
                    zip.putJson("$PREFIX$sessionId.json", sessionJson.toString())
                    indexEntries += DsIndexEntry(
                        id = sessionId,
                        title = conversation.title,
                        pinned = conversation.pinned,
                        messageCount = normalized.size,
                        insertedAt = iso(conversation.createdAt),
                        updatedAt = iso(conversation.updatedAt),
                    )
                    exported++
                    exportedMessages += normalized.size
                }
                zip.putJson(
                    "chat_session_index.json",
                    json.encodeToString(ListSerializer(DsIndexEntry.serializer()), indexEntries),
                )
            }
            copyTempToUri(temp, uri)
            BackupExportResult(exported, exportedMessages, skipped, warnings)
        } finally {
            temp.delete()
        }
    }

    suspend fun inspectOwn(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val file = copyToCache(uri)
        try {
            ZipFile(file).use { zip ->
                val backup = readOwnBackup(zip)
                val existing = conversationRepository.getAllConversations().map { it.id }.toSet()
                BackupInspection(
                    sessionCount = backup.conversations.size,
                    messageCount = backup.messages.size,
                    duplicateCount = backup.conversations.count { it.id in existing },
                    formatVersion = backup.version,
                )
            }
        } finally {
            file.delete()
        }
    }

    suspend fun exportOwn(uri: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val providers = providerRepository.providers.first()
        val conversations = conversationRepository.getAllConversations()
        val messages = conversationRepository.getAllMessages()
        val backup = OwnBackupFile(
            exportedAt = System.currentTimeMillis(),
            settings = OwnSettings(
                themeMode = settings.themeMode.name,
                contextTokenLimit = settings.contextTokenLimit,
                autoTitle = settings.autoTitle,
                search = settings.search,
                updateRepo = settings.updateRepo,
                updateManifestUrl = settings.updateManifestUrl,
                autoCheckUpdates = settings.autoCheckUpdates,
            ),
            providers = providers.map { it.toStored() },
            conversations = conversations.map { it.toOwn() },
            messages = messages.map { it.toOwn() },
        )
        val imageNames = messages
            .flatMap { it.attachments }
            .asSequence()
            .filter { it.isImage }
            .mapNotNull { it.localFile }
            .distinct()
            .toList()
        val temp = File.createTempFile("deepsleep-own-export-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                zip.putJson(
                    "deepsleep_backup.json",
                    json.encodeToString(OwnBackupFile.serializer(), backup),
                )
                imageNames.forEach { name ->
                    currentCoroutineContext().ensureActive()
                    val file = File(attachmentRepository.imageDir, File(name).name)
                    if (!file.isFile || file.length() > MAX_BACKUP_IMAGE_BYTES) return@forEach
                    zip.putNextEntry(ZipEntry("images/${file.name}"))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            copyTempToUri(temp, uri)
            BackupExportResult(
                conversations = conversations.size,
                messages = messages.size,
                warnings = if (providers.any { it.hasApiKey }) {
                    listOf("API Key 不会写入备份；换机后需要在设置中重新填写")
                } else {
                    emptyList()
                },
            )
        } finally {
            temp.delete()
        }
    }

    suspend fun importOwn(
        uri: Uri,
        skipDuplicates: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val file = copyToCache(uri)
        try {
            ZipFile(file).use { zip ->
                val backup = readOwnBackup(zip)
                extractOwnBackupImages(zip)
                val existing = conversationRepository.getAllConversations().map { it.id }.toSet()
                var conversationsImported = 0
                var messagesImported = 0
                var providersImported = 0
                var skipped = 0
                val warnings = mutableListOf<String>()

                backup.settings?.let { ownSettings ->
                    runCatching { ThemeMode.valueOf(ownSettings.themeMode) }
                        .getOrNull()?.let { settingsRepository.setThemeMode(it) }
                    settingsRepository.setContextTokenLimit(ownSettings.contextTokenLimit)
                    settingsRepository.setAutoTitle(ownSettings.autoTitle)
                    settingsRepository.setSearchConfig(ownSettings.search)
                    runCatching {
                        com.deepsleep.chat.data.update.UpdateRepository
                            .normalizeRepo(ownSettings.updateRepo)
                    }.getOrNull()?.let { settingsRepository.setUpdateRepo(it) }
                    settingsRepository.setUpdateManifestUrl(ownSettings.updateManifestUrl)
                    settingsRepository.setAutoCheckUpdates(ownSettings.autoCheckUpdates)
                }

                backup.providers.forEach { stored ->
                    val provider = stored.toDomain()
                    providerRepository.upsert(provider, null)
                    providersImported++
                }
                if (backup.providers.isNotEmpty()) {
                    val defaultProvider = providerRepository.providers.first().firstOrNull { it.isDefault }
                    settingsRepository.setDefaultProvider(defaultProvider?.id)
                }

                val messagesByConversation = backup.messages.groupBy { it.conversationId }
                backup.conversations.forEachIndexed { index, ownConversation ->
                    currentCoroutineContext().ensureActive()
                    onProgress(index + 1, backup.conversations.size)
                    if (skipDuplicates && ownConversation.id in existing) {
                        skipped++
                        return@forEachIndexed
                    }
                    val newConversationId = if (skipDuplicates) {
                        ownConversation.id
                    } else {
                        UUID.randomUUID().toString()
                    }
                    val conversation = ownConversation.toDomain(newConversationId)
                    val messages = messagesByConversation[ownConversation.id].orEmpty().map { ownMessage ->
                        ownMessage.toDomain(
                            conversationId = newConversationId,
                            newId = UUID.randomUUID().toString(),
                        )
                    }
                    conversationRepository.insertImported(conversation, messages)
                    conversationsImported++
                    messagesImported += messages.size
                }

                if (backup.providers.any { it.id.isNotBlank() }) {
                    warnings += "本 App 备份不包含 API Key 与搜索 API Key，请到设置中检查并重新填写"
                }
                if (backup.conversations.size > conversationsImported) {
                    warnings += "跳过 ${backup.conversations.size - conversationsImported} 个已存在的对话"
                }
                BackupImportResult(
                    conversations = conversationsImported,
                    messages = messagesImported,
                    providers = providersImported,
                    skipped = skipped,
                    warnings = warnings,
                )
            }
        } finally {
            file.delete()
        }
    }

    private fun convertSession(session: DsSessionFile): ConvertedSession? {
        if (session.messages.isEmpty()) return null
        val byId = session.messages.associateBy { it.id }
        val chain = ArrayList<DsMessage>()
        val visited = HashSet<Long>()
        var cursor = session.currentMessageId?.let { byId[it] }
            ?: session.messages.maxByOrNull { it.id }
        while (cursor != null && visited.add(cursor.id)) {
            chain.add(cursor)
            cursor = cursor.parentId?.let { byId[it] }
        }
        chain.reverse()
        val ordered = if (chain.isEmpty()) session.messages.sortedBy { it.id } else chain
        val droppedBranches = (session.messages.size - ordered.size).coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val baseTime = parseTime(session.insertedAt, now)
        val conversationId = UUID.randomUUID().toString()
        val convertedMessages = ArrayList<ChatMessage>()
        var imageCount = 0

        ordered.forEachIndexed { index, message ->
            val role = when (message.role.uppercase()) {
                "USER" -> MessageRole.USER
                "ASSISTANT" -> MessageRole.ASSISTANT
                else -> null
            } ?: return@forEachIndexed

            val fragments = message.fragments.mapNotNull { it as? JsonObject }
            fun contents(type: String): String = fragments
                .filter { it["type"].str() == type }
                .mapNotNull { it["content"].str() }
                .joinToString("\n\n")
                .trim()

            val content = if (role == MessageRole.USER) contents("REQUEST") else contents("RESPONSE")
            val reasoning = contents("THINK")
            val template = contents("TEMPLATE_RESPONSE")
            val sources = fragments
                .filter { it["type"].str() == "TOOL_SEARCH" }
                .flatMap { fragment -> parseSearchResults(fragment) }
                .distinctBy { it.url }
            val attachments = fragments
                .filter { it["type"].str() == "FILE" }
                .flatMap { fragment -> parseFiles(fragment) }
                .distinctBy { it.id }
            val elapsedMs = fragments
                .filter { it["type"].str() == "THINK" }
                .mapNotNull { it["elapsed_secs"].asDouble() }
                .maxOrNull()
                ?.times(1000)
                ?.toLong()
            val hasAnything = content.isNotBlank() || reasoning.isNotBlank() ||
                sources.isNotEmpty() || attachments.isNotEmpty() || template.isNotBlank()
            if (!hasAnything) return@forEachIndexed

            val time = parseTime(message.insertedAt, baseTime + index * 1000L)
            val status = if (role == MessageRole.USER) {
                MessageStatus.COMPLETE
            } else {
                mapOfficialStatus(message.status, content.isNotBlank() || reasoning.isNotBlank())
            }
            val effectiveStatus = if (role == MessageRole.ASSISTANT &&
                content.isBlank() &&
                reasoning.isBlank() &&
                template.isNotBlank()
            ) {
                MessageStatus.ERROR
            } else {
                status
            }
            val error = if (effectiveStatus == MessageStatus.ERROR) {
                template.ifBlank { "DeepSeek 导出状态：${message.status ?: "UNKNOWN"}" }
            } else {
                null
            }

            convertedMessages += ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                seq = convertedMessages.size + 1L,
                role = role,
                content = content,
                reasoning = reasoning,
                status = effectiveStatus,
                thinkingEnabled = message.thinkingEnabled || reasoning.isNotBlank(),
                attachments = attachments,
                sources = sources,
                reasoningMs = elapsedMs,
                error = error,
                createdAt = time,
            )
            imageCount += attachments.count { it.isImage }
        }

        if (convertedMessages.isEmpty()) return null
        val firstUser = convertedMessages.firstOrNull { it.role == MessageRole.USER }
        val lastAssistant = convertedMessages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        }
        val title = session.title?.takeIf { it.isNotBlank() }
            ?: firstUser?.content?.lineSequence()?.firstOrNull()?.trim()?.take(24)?.takeIf { it.isNotBlank() }
            ?: "未命名对话"
        val conversation = Conversation(
            id = conversationId,
            title = title,
            createdAt = parseTime(session.insertedAt, convertedMessages.first().createdAt),
            updatedAt = parseTime(session.updatedAt, convertedMessages.last().createdAt),
            pinned = session.pinned,
            providerId = null,
            chatModelId = null,
            reasoningModelId = null,
            thinkingEnabled = ordered.lastOrNull()?.thinkingEnabled ?: false,
            webSearchEnabled = ordered.lastOrNull()?.searchEnabled ?: false,
            systemPrompt = null,
            lastPreview = (lastAssistant?.content ?: convertedMessages.last().content).take(120),
            source = "deepseek",
            sourceSessionId = session.id,
        )
        return ConvertedSession(
            conversation = conversation,
            messages = convertedMessages,
            imageCount = imageCount,
            droppedBranches = droppedBranches,
        )
    }

    private fun normalizeForExport(messages: List<ChatMessage>): List<ExportPair> {
        val pairs = mutableListOf<ExportPair>()
        messages.sortedBy { it.seq }.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    if (message.content.isBlank() && message.attachments.isEmpty()) return@forEach
                    val last = pairs.lastOrNull()
                    if (last?.role == MessageRole.USER) {
                        pairs[pairs.lastIndex] = last.copy(
                            content = joinText(last.content, message.content),
                            attachments = (last.attachments + message.attachments).distinctBy { it.id },
                            sources = emptyList(),
                        )
                    } else {
                        pairs += ExportPair(
                            role = MessageRole.USER,
                            content = message.content,
                            reasoning = "",
                            sources = emptyList(),
                            attachments = message.attachments,
                            createdAt = message.createdAt,
                            reasoningMs = null,
                        )
                    }
                }
                MessageRole.ASSISTANT -> {
                    if (message.content.isBlank() && message.reasoning.isBlank() && message.sources.isEmpty()) {
                        return@forEach
                    }
                    val last = pairs.lastOrNull()
                    if (last?.role == MessageRole.ASSISTANT) {
                        pairs[pairs.lastIndex] = last.copy(
                            content = joinText(last.content, message.content),
                            reasoning = joinText(last.reasoning, message.reasoning),
                            sources = (last.sources + message.sources).distinctBy { it.url },
                            reasoningMs = last.reasoningMs ?: message.reasoningMs,
                        )
                    } else {
                        pairs += ExportPair(
                            role = MessageRole.ASSISTANT,
                            content = message.content,
                            reasoning = message.reasoning,
                            sources = message.sources,
                            attachments = emptyList(),
                            createdAt = message.createdAt,
                            reasoningMs = message.reasoningMs,
                        )
                    }
                }
                MessageRole.SYSTEM -> Unit
            }
        }
        while (pairs.firstOrNull()?.role == MessageRole.ASSISTANT) {
            pairs.removeAt(0)
        }
        return pairs
    }

    private fun buildExportMessage(
        index: Int,
        pair: ExportPair,
        conversation: Conversation,
    ): JsonObject = buildJsonObject {
        put("id", index + 1L)
        if (index == 0) put("parent_id", JsonNull) else put("parent_id", index.toLong())
        put("role", if (pair.role == MessageRole.USER) "USER" else "ASSISTANT")
        put("status", "FINISHED")
        put("thinking_enabled", conversation.thinkingEnabled)
        put("search_enabled", conversation.webSearchEnabled || pair.sources.isNotEmpty())
        put("accumulated_token_usage", 0L)
        put("inserted_at", iso(pair.createdAt))
        put(
            "fragments",
            buildJsonArray {
                if (pair.role == MessageRole.USER) {
                    add(buildJsonObject {
                        put("type", "REQUEST")
                        put("content", pair.content)
                    })
                    if (pair.attachments.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "FILE")
                            put(
                                "files",
                                buildJsonArray {
                                    pair.attachments.forEach { attachment ->
                                        add(buildJsonObject {
                                            put("id", attachment.id.removePrefix("file-"))
                                            put("file_name", attachment.name)
                                            put("file_size", attachment.sizeBytes)
                                            put("token_usage", JsonNull)
                                            put("inserted_at", iso(pair.createdAt))
                                            put("export_path", JsonNull)
                                            put("is_image", attachment.isImage)
                                            put("width", JsonNull)
                                            put("height", JsonNull)
                                        })
                                    }
                                },
                            )
                        })
                    }
                } else {
                    if (pair.reasoning.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "THINK")
                            put("content", pair.reasoning)
                            if (pair.reasoningMs != null) {
                                put("elapsed_secs", pair.reasoningMs / 1000.0)
                            } else {
                                put("elapsed_secs", JsonNull)
                            }
                        })
                    }
                    add(buildJsonObject {
                        put("type", "RESPONSE")
                        put("content", pair.content)
                    })
                    if (pair.sources.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "TOOL_SEARCH")
                            put("queries", buildJsonArray {})
                            put(
                                "results",
                                buildJsonArray {
                                    pair.sources.forEach { source ->
                                        add(buildJsonObject {
                                            put("url", source.url)
                                            put("title", source.title)
                                            put("snippet", source.snippet)
                                            put("published_at", JsonNull)
                                            put("site_name", JsonNull)
                                        })
                                    }
                                },
                            )
                            put("status", "FINISHED")
                        })
                    }
                }
            },
        )
    }

    private fun parseSearchResults(fragment: JsonObject): List<SearchSource> {
        val array = fragment["results"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].str() ?: return@mapNotNull null
            SearchSource(
                title = obj["title"].str() ?: url,
                url = url,
                snippet = obj["snippet"].str().orEmpty(),
            )
        }
    }

    private fun parseFiles(fragment: JsonObject): List<Attachment> {
        val array = fragment["files"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj["file_name"].str() ?: return@mapNotNull null
            val isImage = obj["is_image"].asBool() ?: false
            Attachment(
                id = obj["id"].str() ?: UUID.randomUUID().toString(),
                name = name,
                mime = guessMime(name),
                sizeBytes = obj["file_size"].asLong() ?: 0L,
                text = "",
                isImage = isImage,
            )
        }
    }

    private fun readOwnBackup(zip: ZipFile): OwnBackupFile {
        val entry = zip.getEntry(OWN_BACKUP_ENTRY)
            ?: throw BackupException("不是有效的 deepsleep 备份（缺少 $OWN_BACKUP_ENTRY）")
        if (entry.size > MAX_OWN_BACKUP_BYTES) {
            throw BackupException("备份文件过大（超过 1GB）")
        }
        val text = readLimited(zip.getInputStream(entry), MAX_OWN_BACKUP_BYTES.toInt())
        val backup = runCatching {
            json.decodeFromString(OwnBackupFile.serializer(), text)
        }.getOrElse { throw BackupException("本 App 备份内容无法解析：${it.message}") }
        if (backup.format != OwnBackupFile.FORMAT) throw BackupException("备份格式不匹配")
        if (backup.version > OwnBackupFile.VERSION) {
            throw BackupException("备份版本过高，请升级 App")
        }
        return backup
    }

    /** Copies image entries from an own backup into the app's private image dir. */
    private fun extractOwnBackupImages(zip: ZipFile) {
        var totalBytes = 0L
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("images/") }
            .take(MAX_BACKUP_IMAGE_COUNT)
            .forEach { entry ->
                val name = File(entry.name).name
                if (name.isBlank()) return@forEach
                val declared = entry.size
                if (declared <= 0L || declared > MAX_BACKUP_IMAGE_BYTES) return@forEach
                if (totalBytes + declared > MAX_BACKUP_IMAGES_TOTAL_BYTES) return@forEach
                val target = File(attachmentRepository.imageDir, name)
                if (target.parentFile != attachmentRepository.imageDir) return@forEach
                if (target.exists()) return@forEach
                runCatching {
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > MAX_BACKUP_IMAGE_BYTES) {
                                    throw BackupException("备份中的图片过大，已跳过")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    totalBytes += declared
                }.onFailure { target.delete() }
            }
    }

    private fun readManifestVersion(zip: ZipFile): Int {
        val entry = zip.getEntry("manifest.json")
            ?: throw BackupException("不是有效的 DeepSeek 备份（缺少 manifest.json）")
        val text = readLimited(zip.getInputStream(entry), 1024 * 1024)
        val version = runCatching {
            json.decodeFromString(DsManifest.serializer(), text).exportFormatVersion
        }.getOrNull() ?: throw BackupException("manifest.json 无法解析")
        if (version > EXPORT_FORMAT_VERSION) {
            throw BackupException("备份版本 $version 高于当前支持版本 $EXPORT_FORMAT_VERSION，请升级 App")
        }
        if (version != EXPORT_FORMAT_VERSION) {
            throw BackupException("只支持 DeepSeek 导出格式 v$EXPORT_FORMAT_VERSION，当前文件为 v$version")
        }
        return version
    }

    private fun readIndex(zip: ZipFile): List<DsIndexEntry> {
        val entry = zip.getEntry("chat_session_index.json") ?: return emptyList()
        return runCatching {
            val text = readLimited(zip.getInputStream(entry), MAX_OWN_BACKUP_BYTES.toInt())
            json.decodeFromString(ListSerializer(DsIndexEntry.serializer()), text)
        }.getOrDefault(emptyList())
    }

    private fun sessionEntries(zip: ZipFile): List<ZipEntry> {
        val entries = zip.entries().asSequence()
            .filter { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith(PREFIX) &&
                    entry.name.endsWith(".json") &&
                    !entry.name.removePrefix(PREFIX).contains('/')
            }
            .toList()
        if (entries.size > MAX_SESSION_COUNT) {
            throw BackupException("备份包含超过 $MAX_SESSION_COUNT 个会话，已拒绝导入")
        }
        return entries
    }

    private fun copyToCache(uri: Uri): File {
        val file = File.createTempFile("deepsleep-import-", ".zip", context.cacheDir)
        try {
            val sourceSize = querySourceSize(uri)
            if (sourceSize != null && sourceSize > MAX_BACKUP_BYTES) {
                throw BackupException("备份文件超过 1GB，无法导入")
            }
            if (sourceSize != null && sourceSize > 0) {
                val required = sourceSize + 16L * 1024 * 1024
                if (context.cacheDir.usableSpace < required) {
                    val gb = required / (1024.0 * 1024.0 * 1024.0)
                    throw BackupException(
                        "手机存储空间不足，导入需要约 ${"%.1f".format(gb)}GB 临时缓存空间，请清理后重试",
                    )
                }
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BackupException("无法打开备份文件")
            input.use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BACKUP_BYTES) throw BackupException("备份文件超过 1GB，无法导入")
                        output.write(buffer, 0, read)
                    }
                }
            }
            return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    private fun querySourceSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    private fun copyTempToUri(temp: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("无法写入备份文件")
        output.use { destination ->
            temp.inputStream().use { source -> source.copyTo(destination) }
        }
    }

    private fun ZipOutputStream.putJson(name: String, text: String) {
        putNextEntry(ZipEntry(name).apply { time = System.currentTimeMillis() })
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun readLimited(input: InputStream?, maxBytes: Int): String {
        if (input == null) throw BackupException("无法读取备份条目")
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw BackupException("备份条目过大")
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun mapOfficialStatus(raw: String?, hasContent: Boolean): MessageStatus =
        when (raw?.uppercase()) {
            "FINISHED" -> MessageStatus.COMPLETE
            "WIP", "INCOMPLETE" -> MessageStatus.STOPPED
            "FAILED", "TIMEOUT", "CONTENT_FILTER", "CONTEXT_LENGTH_EXCEEDED" -> MessageStatus.ERROR
            null -> if (hasContent) MessageStatus.COMPLETE else MessageStatus.STOPPED
            else -> if (hasContent) MessageStatus.COMPLETE else MessageStatus.ERROR
        }

    private fun parseTime(value: String?, fallback: Long): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: fallback

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun joinText(first: String, second: String): String = when {
        first.isBlank() -> second
        second.isBlank() -> first
        else -> "$first\n\n$second"
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) || name.endsWith(".markdown", true) -> "text/markdown"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        else -> "text/plain"
    }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.longOrNull

    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private fun Provider.toStored(): StoredProvider = StoredProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        models = models,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        transcriptionModelId = transcriptionModelId,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = extraHeaders,
        isDefault = isDefault,
    )

    private fun StoredProvider.toDomain(): Provider = Provider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        models = models,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        transcriptionModelId = transcriptionModelId,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = extraHeaders,
        isDefault = isDefault,
        hasApiKey = false,
    )

    private fun Conversation.toOwn(): OwnConversation = OwnConversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        providerId = providerId,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        thinkingEnabled = thinkingEnabled,
        webSearchEnabled = webSearchEnabled,
        systemPrompt = systemPrompt,
        lastPreview = lastPreview,
        source = source,
        sourceSessionId = sourceSessionId,
    )

    private fun OwnConversation.toDomain(id: String = this.id): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        providerId = providerId,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        thinkingEnabled = thinkingEnabled,
        webSearchEnabled = webSearchEnabled,
        systemPrompt = systemPrompt,
        lastPreview = lastPreview,
        source = source,
        sourceSessionId = sourceSessionId,
    )

    private fun ChatMessage.toOwn(): OwnMessage = OwnMessage(
        id = id,
        conversationId = conversationId,
        seq = seq,
        role = role.name,
        content = content,
        reasoning = reasoning,
        status = status.name,
        thinkingEnabled = thinkingEnabled,
        modelId = modelId,
        providerId = providerId,
        attachments = attachments,
        sources = sources,
        promptTokens = usage?.promptTokens,
        completionTokens = usage?.completionTokens,
        totalTokens = usage?.totalTokens,
        reasoningMs = reasoningMs,
        error = error,
        createdAt = createdAt,
    )

    private fun OwnMessage.toDomain(conversationId: String, newId: String): ChatMessage = ChatMessage(
        id = newId,
        conversationId = conversationId,
        seq = seq,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        reasoning = reasoning,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
        thinkingEnabled = thinkingEnabled || reasoning.isNotBlank(),
        modelId = modelId,
        providerId = providerId,
        attachments = attachments,
        sources = sources,
        usage = if (promptTokens == null && completionTokens == null && totalTokens == null) {
            null
        } else {
            com.deepsleep.chat.domain.model.TokenUsage(
                promptTokens = promptTokens ?: 0,
                completionTokens = completionTokens ?: 0,
                totalTokens = totalTokens ?: ((promptTokens ?: 0) + (completionTokens ?: 0)),
            )
        },
        reasoningMs = reasoningMs,
        error = error,
        createdAt = createdAt,
    )

    private data class ConvertedSession(
        val conversation: Conversation,
        val messages: List<ChatMessage>,
        val imageCount: Int,
        val droppedBranches: Int,
    )

    private data class ExportPair(
        val role: MessageRole,
        val content: String,
        val reasoning: String,
        val sources: List<SearchSource>,
        val attachments: List<Attachment>,
        val createdAt: Long,
        val reasoningMs: Long?,
    )

    private companion object {
        const val PREFIX = "chat_sessions/"
        const val EXPORT_FORMAT_VERSION = 3
        const val OWN_BACKUP_ENTRY = "deepsleep_backup.json"
        const val MAX_BACKUP_BYTES = 1024L * 1024 * 1024
        const val MAX_SESSION_BYTES = 32L * 1024 * 1024
        const val MAX_OWN_BACKUP_BYTES = 1024L * 1024 * 1024
        const val MAX_SESSION_COUNT = 20_000
        const val MAX_BACKUP_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_BACKUP_IMAGES_TOTAL_BYTES = 200L * 1024 * 1024
        const val MAX_BACKUP_IMAGE_COUNT = 200
    }
}