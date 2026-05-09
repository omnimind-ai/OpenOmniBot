package com.ai.assistance.operit.terminal.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvironmentSetupLogicTest {

    @Test
    fun buildInstallCommands_usesAlpinePackagesAndUvBootstrap() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.startsWith("apk add ") }
        assertTrue(apkAdd.contains("python3"))
        assertTrue(apkAdd.contains("py3-pip"))
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("openssh-client-default"))

        assertTrue(commands.contains("ln -sf /usr/bin/python3 /usr/local/bin/python || true"))
        assertTrue(commands.contains("ln -sf /usr/bin/pip3 /usr/local/bin/pip || true"))
        assertTrue(
            commands.contains(
                "if ! apk add --no-cache uv; then python3 -m pip install --break-system-packages --upgrade uv; fi"
            )
        )
    }

    @Test
    fun buildInstallCommands_prependsRepositorySetupWhenProvided() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("curl"),
            repositorySetupCommand = "echo mirror-ready"
        )

        assertEquals("echo mirror-ready", commands.first())
        assertTrue(commands.any { it == "apk add --no-cache curl" })
    }

    @Test
    fun buildInstallCommands_codexInstallsOfficialCliAndRuntimeDependencies() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("codex"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.startsWith("apk add ") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("git"))
        assertTrue(apkAdd.contains("bash"))
        assertTrue(apkAdd.contains("curl"))
        assertTrue(apkAdd.contains("ripgrep"))
        assertTrue(commands.contains("mkdir -p /root/.npm-global/bin"))
        assertTrue(commands.contains("npm config set prefix /root/.npm-global"))
        assertTrue(commands.contains("export PATH=\"/root/.npm-global/bin:\$PATH\""))
        assertTrue(commands.contains("npm install -g @openai/codex@latest"))
        assertTrue(commands.contains("ln -sf /root/.npm-global/bin/codex /usr/local/bin/codex || true"))
    }

    @Test
    fun buildInventoryProbeCommand_codexChecksVersionAndAppServer() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex"))

        assertTrue(command.contains("command -v codex"))
        assertTrue(command.contains("codex app-server --help"))
        assertTrue(command.contains("codex --version"))
    }

    @Test
    fun buildSetupScript_isShellSafeForEveryPackageCombination() {
        val packageIds = EnvironmentSetupLogic.packageDefinitions.map { it.id }
        val tempDir = Files.createTempDirectory("omni-setup-script-test").toFile()

        try {
            val total = 1 shl packageIds.size
            for (mask in 1 until total) {
                val selectedPackageIds = packageIds.filterIndexed { index, _ ->
                    mask and (1 shl index) != 0
                }
                val commands = EnvironmentSetupLogic.buildInstallCommands(
                    selectedPackageIds = selectedPackageIds,
                    repositorySetupCommand = ""
                )
                val scriptFile = File(tempDir, "setup-$mask.sh")
                scriptFile.writeText(EnvironmentSetupLogic.buildSetupScript(commands))

                val process = ProcessBuilder("/bin/sh", "-n", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()

                assertEquals(
                    "Shell syntax check failed for $selectedPackageIds: $output",
                    0,
                    exitCode
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
