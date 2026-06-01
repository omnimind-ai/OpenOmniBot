package com.rk.terminal.runtime

import android.content.Context
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.terminal.ui.screens.terminal.stat
import com.rk.terminal.ui.screens.terminal.vmstat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

object EmbeddedRuntimeInstaller {
    private data class RuntimeAssetSpec(
        val outputName: String,
        val assetCandidates: List<String>,
        val executable: Boolean = false
    )

    data class InstallStatus(
        val success: Boolean,
        val installed: Boolean,
        val message: String
    )

    private const val ASSET_ROOT = "embedded-terminal-runtime"
    private val installMutex = Mutex()
    private val runtimeAssets = listOf(
        RuntimeAssetSpec(
            outputName = "proot",
            assetCandidates = listOf("proot"),
            executable = true
        ),
        RuntimeAssetSpec(
            outputName = "libtalloc.so.2",
            assetCandidates = listOf("libtalloc.so.2")
        ),
        RuntimeAssetSpec(
            outputName = "alpine.tar.gz",
            assetCandidates = listOf("alpine.tar.gz", "alpine.tar")
        )
    )

    suspend fun ensureRuntimeInstalled(
        context: Context,
        onProgress: suspend (String) -> Unit = {}
    ): InstallStatus = withContext(Dispatchers.IO) {
        installMutex.withLock {
            try {
                onProgress("正在校验 Alpine 终端运行资源")
                val resolvedAssets = runtimeAssets.associateWith { spec ->
                    spec.assetCandidates.firstOrNull { assetName ->
                        runCatching {
                            context.assets.open("$ASSET_ROOT/$assetName").close()
                        }.isSuccess
                    }
                }
                val missingAssets = resolvedAssets.filterValues { it == null }.keys
                if (missingAssets.isNotEmpty()) {
                    return@withLock InstallStatus(
                        success = false,
                        installed = false,
                        message = "缺少内置 Alpine 运行资源，请重新安装包含终端资源的构建。"
                    )
                }

                onProgress("正在安装 Alpine 终端运行资源")
                var refreshedFiles = 0
                val installedFiles = mutableMapOf<String, File>()
                runtimeAssets.forEach { spec ->
                    val assetName = resolvedAssets.getValue(spec)
                        ?: error("Missing runtime asset mapping for ${spec.outputName}")
                    val target = File(context.filesDir, spec.outputName)
                    if (copyAssetIfChanged(
                            context = context,
                            assetPath = "$ASSET_ROOT/$assetName",
                            target = target,
                            executable = spec.executable
                        )
                    ) {
                        refreshedFiles++
                    }
                    installedFiles[spec.outputName] = target
                }

                // ReTerminal init-host.sh expects these runtime helpers to exist under $PREFIX/local.
                localDir().mkdirs()
                localBinDir().mkdirs()
                localLibDir().mkdirs()
                installedFiles["proot"]?.let { source ->
                    if (copyFileIfChanged(source, File(localBinDir(), "proot"), executable = true)) {
                        refreshedFiles++
                    }
                }
                installedFiles["libtalloc.so.2"]?.let { source ->
                    if (copyFileIfChanged(source, File(localLibDir(), "libtalloc.so.2"), executable = false)) {
                        refreshedFiles++
                    }
                }
                if (writeTextIfChanged(File(localDir(), "stat"), stat)) {
                    refreshedFiles++
                }
                if (writeTextIfChanged(File(localDir(), "vmstat"), vmstat)) {
                    refreshedFiles++
                }

                InstallStatus(
                    success = true,
                    installed = true,
                    message = if (refreshedFiles > 0) {
                        "Alpine 终端运行资源已刷新。"
                    } else {
                        "Alpine 终端运行资源已就绪。"
                    }
                )
            } catch (error: Exception) {
                InstallStatus(
                    success = false,
                    installed = false,
                    message = error.message ?: "安装 Alpine 终端运行资源失败。"
                )
            }
        }
    }

    private fun copyAssetIfChanged(
        context: Context,
        assetPath: String,
        target: File,
        executable: Boolean
    ): Boolean {
        val assetDigest = context.assets.open(assetPath).use { input ->
            sha256(input)
        }
        val targetDigest = sha256OrNull(target)
        val changed = targetDigest != assetDigest
        if (changed) {
            replaceFile(target, executable) { temp ->
                context.assets.open(assetPath).use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        applyPermissions(target, executable)
        return changed
    }

    private fun copyFileIfChanged(
        source: File,
        target: File,
        executable: Boolean
    ): Boolean {
        val sourceDigest = sha256OrNull(source)
            ?: error("Missing embedded terminal runtime file: ${source.absolutePath}")
        val targetDigest = sha256OrNull(target)
        val changed = targetDigest != sourceDigest
        if (changed) {
            replaceFile(target, executable) { temp ->
                source.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        applyPermissions(target, executable)
        return changed
    }

    private fun writeTextIfChanged(target: File, content: String): Boolean {
        target.parentFile?.mkdirs()
        val changed = !target.exists() || target.readText() != content
        if (changed) {
            replaceFile(target, executable = false) { temp ->
                temp.writeText(content)
            }
        }
        applyPermissions(target, executable = false)
        return changed
    }

    private fun replaceFile(
        target: File,
        executable: Boolean,
        writer: (File) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val parent = target.parentFile ?: error("Missing parent directory for ${target.absolutePath}")
        val temp = File.createTempFile("${target.name}.", ".tmp", parent)
        try {
            writer(temp)
            applyPermissions(temp, executable)
            if (target.exists() && !target.delete()) {
                error("Failed to replace ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                error("Failed to move refreshed runtime file to ${target.absolutePath}")
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
        applyPermissions(target, executable)
    }

    private fun applyPermissions(file: File, executable: Boolean) {
        if (!file.exists()) {
            return
        }
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (executable) {
            file.setExecutable(true, false)
        }
    }

    private fun sha256OrNull(file: File): String? {
        if (!file.exists() || file.length() == 0L || !file.isFile) {
            return null
        }
        return file.inputStream().use { input ->
            sha256(input)
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val readBytes = input.read(buffer)
            if (readBytes < 0) {
                break
            }
            digest.update(buffer, 0, readBytes)
        }
        return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
    }
}
