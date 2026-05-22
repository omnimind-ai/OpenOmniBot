package cn.com.omnimind.bot.codex

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal suspend fun defaultLocalProcessStarter(
    context: Context?,
    command: String,
    extraEnvironment: Map<String, String>
): Process {
    val safeContext = requireNotNull(context) {
        "Context is required when using the default Codex process starter."
    }
    return TerminalManager.getInstance(safeContext).startLongLivedAlpineProcess(
        command = command,
        executorKey = "codex-app-server",
        redirectErrorStream = false,
        extraEnvironment = extraEnvironment
    )
}

internal class LocalCodexAppServerConnection(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    private val command: String,
    private val environment: Map<String, String>,
    private val processStarter: suspend (String, Map<String, String>) -> Process = { command, extraEnvironment ->
        val safeContext = requireNotNull(context) {
            "Context is required when using the default Codex process starter."
        }
        TerminalManager.getInstance(safeContext).startLongLivedAlpineProcess(
            command = command,
            executorKey = "codex-app-server",
            redirectErrorStream = false,
            extraEnvironment = extraEnvironment
        )
    }
) : CodexAppServerConnection {
    @Volatile
    private var process: Process? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null

    override val isRunning: Boolean
        get() = process?.isAlive == true

    override suspend fun start(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
        onExit: suspend (Int?) -> Unit
    ) {
        if (isRunning) {
            return
        }
        val startedProcess = processStarter(command, environment)
        process = startedProcess

        stdoutJob = scope.launch(Dispatchers.IO) {
            runCatching {
                startedProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onStdoutLine(line)
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Codex stdout reader stopped: ${error.message}")
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            runCatching {
                startedProcess.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onStderrLine(line)
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Codex stderr reader stopped: ${error.message}")
            }
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { startedProcess.waitFor() }.getOrNull()
            if (process === startedProcess) {
                process = null
                onExit(exitCode)
            }
        }
    }

    override suspend fun writeLine(line: String) {
        val output = process?.outputStream
            ?: throw IllegalStateException("Codex app-server stdin is closed.")
        withContext(Dispatchers.IO) {
            OutputStreamWriter(output, StandardCharsets.UTF_8).apply {
                write(line)
                flush()
            }
        }
    }

    override suspend fun close() {
        val currentProcess = process
        process = null
        runCatching { currentProcess?.outputStream?.close() }
        runCatching { currentProcess?.inputStream?.close() }
        runCatching { currentProcess?.errorStream?.close() }
        runCatching { currentProcess?.destroy() }
        stdoutJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        waitJob?.cancelAndJoin()
        stdoutJob = null
        stderrJob = null
        waitJob = null
    }

    private companion object {
        private const val TAG = "LocalCodexConnection"
    }
}
