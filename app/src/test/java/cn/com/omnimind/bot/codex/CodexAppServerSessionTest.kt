package cn.com.omnimind.bot.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections

class CodexAppServerSessionTest {

    @Test
    fun startSendsInitializeAndInitializedThenEmitsConnected() = runBlocking {
        val harness = CodexSessionHarness(
            """
            read first
            printf '%s\n' "${'$'}first" >> "${'$'}OMNI_TEST_LOG"
            printf '{"id":1,"result":{"server":"ok"}}\n'
            read second
            printf '%s\n' "${'$'}second" >> "${'$'}OMNI_TEST_LOG"
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil { harness.logFile.readLinesOrEmpty().size >= 2 }

            val lines = harness.logFile.readLines()
            assertTrue(lines[0].contains("\"method\":\"initialize\""))
            assertTrue(lines[0].contains("\"experimentalApi\":true"))
            assertTrue(lines[0].contains("\"version\":\"1.2.3\""))
            assertTrue(lines[1].contains("\"method\":\"initialized\""))
            assertTrue(harness.events.any { it["method"] == "codex/connected" })
            assertTrue(harness.startCommand.contains("codex app-server"))
            assertEquals(CodexAppServerDefaults.CODEX_HOME, harness.startEnvironment["CODEX_HOME"])
        } finally {
            harness.close()
        }
    }

    @Test
    fun sendRequestCorrelatesResponseAndRoutesNotifications() = runBlocking {
        val harness = CodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            read request
            printf '{"method":"turn/started","params":{"threadId":"thread-1","turnId":"turn-1"}}\n'
            printf '{"id":2,"result":{"ok":true,"threadId":"thread-1"}}\n'
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            val response = harness.session.sendRequest(
                method = "thread/list",
                params = mapOf("limit" to 10)
            )

            val result = response["result"] as? Map<*, *>
            assertEquals(true, result?.get("ok"))
            waitUntil { harness.events.any { it["method"] == "turn/started" } }
        } finally {
            harness.close()
        }
    }

    @Test
    fun stdoutParseErrorIsForwardedAsEvent() = runBlocking {
        val harness = CodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            printf 'not-json\n'
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil {
                harness.events.any { it["method"] == "codex/parseError" }
            }
            val parseError = harness.events.first { it["method"] == "codex/parseError" }
            val params = parseError["params"] as? Map<*, *>
            assertEquals("not-json", params?.get("raw"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun processExitCompletesPendingStateAndBlocksFutureRequests() = runBlocking {
        val harness = CodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            exit 7
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil {
                harness.events.any { it["method"] == "codex/disconnected" }
            }
            assertTrue(!harness.session.isRunning)

            val error = runCatching {
                harness.session.sendRequest(method = "thread/list")
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is IllegalStateException)
        } finally {
            harness.close()
        }
    }

    private class CodexSessionHarness(scriptBody: String) {
        private val tempDir: File = Files.createTempDirectory("codex-session-test").toFile()
        val logFile: File = File(tempDir, "stdin.log")
        private val scriptFile: File = File(tempDir, "fake-app-server.sh")
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
        val events: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())

        var startCommand: String = ""
            private set
        var startEnvironment: Map<String, String> = emptyMap()
            private set

        val session = CodexAppServerSession(
            scope = scope,
            onServerMessage = { message -> events.add(message) },
            processStarter = { command, environment ->
                startCommand = command
                startEnvironment = environment
                scriptFile.writeText("#!/bin/sh\nset -eu\n$scriptBody\n")
                ProcessBuilder("/bin/sh", scriptFile.absolutePath)
                    .apply {
                        environment()["OMNI_TEST_LOG"] = logFile.absolutePath
                    }
                    .start()
            }
        )

        suspend fun close() {
            session.disconnect()
            scopeJob.cancelAndJoin()
            tempDir.deleteRecursively()
        }
    }
}

private suspend fun waitUntil(predicate: () -> Boolean) {
    withTimeout(5_000L) {
        while (!predicate()) {
            delay(10L)
        }
    }
}

private fun File.readLinesOrEmpty(): List<String> {
    return if (exists()) readLines() else emptyList()
}
