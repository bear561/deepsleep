package com.deepsleep.chat.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.deepsleep.chat.BuildConfig
import com.deepsleep.chat.data.remote.HttpClients
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val digest: String?,
    val versionCode: Int? = null,
)

class UpdateException(message: String) : Exception(message)

/**
 * Checks GitHub Releases for a newer signed APK and downloads it. No server is
 * needed: the app only reads the public `releases/latest` endpoint of a repo.
 */
class UpdateRepository(
    private val context: Context,
    private val clients: HttpClients,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Prefers [manifestUrl] when non-blank (self-hosted JSON), otherwise reads
     * the latest GitHub release of [repo].
     */
    suspend fun check(
        repo: String,
        manifestUrl: String,
        currentVersion: String,
        currentVersionCode: Int,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val custom = manifestUrl.trim()
        if (custom.isNotEmpty()) {
            checkManifest(custom, currentVersion, currentVersionCode)
        } else {
            checkGitHub(repo, currentVersion)
        }
    }

    private fun checkGitHub(repo: String, currentVersion: String): UpdateInfo? {
        val repoPath = normalizeRepo(repo)
            ?: throw UpdateException("更新仓库格式应为 owner/repo，例如 bear561/deepsleep")
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoPath/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "deepsleep-android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        clients.downloads.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw UpdateException(githubError(response.code, raw))
            }
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw UpdateException("GitHub 返回内容无法解析")
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val remoteVersion = versionFromTag(tag)
                ?: throw UpdateException("无法从发布标签“$tag”识别版本号")
            if (!isNewer(remoteVersion, currentVersion)) return null

            val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
            val candidates = assets.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                ApkAsset(
                    name = name,
                    url = url,
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    digest = obj["digest"]?.jsonPrimitive?.contentOrNull,
                )
            }
            val asset = candidates.firstOrNull {
                it.name.startsWith("DeepSleep", ignoreCase = true) &&
                    !it.name.contains("debug", ignoreCase = true)
            } ?: candidates.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
                ?: candidates.firstOrNull()
                ?: throw UpdateException("最新版本没有附带 APK 文件")

            return UpdateInfo(
                versionName = remoteVersion,
                tagName = tag,
                notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = asset.url,
                sizeBytes = asset.size,
                digest = asset.digest,
            )
        }
    }

    /**
     * Self-hosted manifest, useful for private distribution or local testing:
     * `{ "versionCode": 3, "versionName": "0.2.1", "apkUrl": "https://…/app.apk",
     *    "notes": "…", "sizeBytes": 2120000, "sha256": "…" }`
     */
    private fun checkManifest(
        manifestUrl: String,
        currentVersion: String,
        currentVersionCode: Int,
    ): UpdateInfo? {
        if (!manifestUrl.startsWith("http://", ignoreCase = true) &&
            !manifestUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw UpdateException("自定义更新地址必须以 http:// 或 https:// 开头")
        }
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "deepsleep-android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        clients.downloads.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw UpdateException("无法获取更新信息（HTTP ${response.code}）")
            }
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw UpdateException("更新地址返回的不是有效 JSON")
            val remoteCode = root["versionCode"]?.jsonPrimitive?.intOrNull
            val remoteName = root["versionName"]?.jsonPrimitive?.contentOrNull?.trim()
                .orEmpty()
                .ifBlank { remoteCode?.toString().orEmpty() }
            val rawApkUrl = root["apkUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawApkUrl.isEmpty()) throw UpdateException("更新信息缺少 apkUrl")
            val newer = if (remoteCode != null) {
                remoteCode > currentVersionCode
            } else {
                remoteName.isNotBlank() && isNewer(remoteName, currentVersion)
            }
            if (!newer) return null
            val apkUrl = runCatching {
                java.net.URI(manifestUrl).resolve(rawApkUrl).toString()
            }.getOrDefault(rawApkUrl)
            return UpdateInfo(
                versionName = remoteName.ifBlank { "新版本" },
                tagName = remoteName,
                notes = root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = apkUrl,
                sizeBytes = root["sizeBytes"]?.jsonPrimitive?.longOrNull
                    ?: root["size"]?.jsonPrimitive?.longOrNull
                    ?: 0L,
                digest = root["sha256"]?.jsonPrimitive?.contentOrNull
                    ?: root["digest"]?.jsonPrimitive?.contentOrNull,
                versionCode = remoteCode,
            )
        }
    }

    /**
     * Downloads and verifies the APK. [onProgress] receives null when the
     * server does not report a content length.
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATES_DIR).apply { if (!isDirectory) mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "deepsleep-${info.versionName}.apk.part")
        val target = File(dir, "deepsleep-${info.versionName}.apk")
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "deepsleep-android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        try {
            clients.downloads.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("下载更新失败（HTTP ${response.code}）")
                }
                val body = response.body ?: throw UpdateException("下载更新失败：响应为空")
                val total = body.contentLength().takeIf { it > 0L }
                    ?: info.sizeBytes.takeIf { it > 0L }
                    ?: 0L
                onProgress(if (total > 0L) 0f else null)
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= 256 * 1024) {
                                lastReported = downloaded
                                onProgress(
                                    if (total > 0L) {
                                        (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
                onProgress(1f)
            }
            verifyApk(part, info)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target
    }

    private fun verifyApk(file: File, info: UpdateInfo) {
        val expected = info.digest
            ?.removePrefix("sha256:")
            ?.removePrefix("sha256=")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (expected.length == 64) {
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw UpdateException("安装包校验失败（SHA-256 不匹配），已取消下载")
            }
        }
        val archive = readArchiveInfo(file)
            ?: throw UpdateException("下载的文件不是有效的 APK")
        if (archive.packageName != context.packageName) {
            throw UpdateException("下载的文件不是本应用的安装包")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= BuildConfig.VERSION_CODE) {
            throw UpdateException("下载的安装包版本不高于当前版本")
        }
        if (!sameSignatureAsInstalled(archive)) {
            throw UpdateException("安装包签名与当前应用不一致，已拒绝安装")
        }
    }

    private fun readArchiveInfo(file: File): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }.getOrNull()

    private fun sameSignatureAsInstalled(archive: PackageInfo): Boolean {
        val current = runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            context.packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return false
        val archiveSignatures = signaturesOf(archive).toSet()
        val currentSignatures = signaturesOf(current).toSet()
        return archiveSignatures.isNotEmpty() &&
            currentSignatures.isNotEmpty() &&
            archiveSignatures.any { it in currentSignatures }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.let { signers ->
                return signers.map { it.toCharsString() }
            }
        }
        return info.signatures?.map { it.toCharsString() }.orEmpty()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun githubError(code: Int, raw: String): String = when {
        code == 404 -> "仓库没有发布版本，或更新仓库地址不正确"
        code == 403 && raw.contains("rate limit", ignoreCase = true) ->
            "GitHub 接口请求过于频繁，请稍后再试"
        code == 403 -> "GitHub 拒绝访问（HTTP 403）"
        code >= 500 -> "GitHub 暂时不可用（HTTP $code）"
        else -> "检查更新失败（HTTP $code）"
    }

    private data class ApkAsset(
        val name: String,
        val url: String,
        val size: Long,
        val digest: String?,
    )

    companion object {
        const val UPDATES_DIR = "updates"

        fun normalizeRepo(input: String): String? {
            var value = input.trim().trimEnd('/')
            if (value.endsWith(".git", ignoreCase = true)) {
                value = value.dropLast(4)
            }
            value = value
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
            val parts = value.split('/').filter { it.isNotBlank() }
            if (parts.size != 2) return null
            val owner = parts[0]
            val repo = parts[1]
            val valid = Regex("^[A-Za-z0-9_.-]+$")
            if (!valid.matches(owner) || !valid.matches(repo)) return null
            return "$owner/$repo"
        }

        /** Pulls the first dotted numeric version out of tags like `v0.2.1`. */
        fun versionFromTag(tag: String): String? =
            Regex("\\d+(?:\\.\\d+)*").find(tag)?.value

        fun isNewer(remote: String, current: String): Boolean =
            compareVersions(remote, current) > 0

        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val l = left.getOrElse(index) { 0 }
                val r = right.getOrElse(index) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }
    }
}