package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OobProjectBuilderScriptTest {
    private val gson = Gson()

    @Test
    fun todoCheckInProjectGeneratesRunnableHtmlWithUpdateTrigger() {
        val builder = builtinSkillsRoot()
            .resolve("oob-project/scripts/build_project_from_contract.py")
        assertTrue("missing oob-project builder", builder.isFile)

        val result = ProcessBuilder(
            "python3",
            builder.absolutePath,
            "--contract",
            todoCheckInContract,
        )
            .start()
            .also { it.waitFor() }

        val output = result.inputStream.bufferedReader().readText()
        val stderr = result.errorStream.bufferedReader().readText()
        assertEquals(stderr.ifBlank { output }, 0, result.exitValue())
        assertTrue(output.contains("todo.checkIn"))
        assertTrue(output.contains("toggleItem"))
        assertTrue(output.contains("window.oob.callApi('todo.checkIn'"))
        assertTrue(stderr.contains("PASS"))

        val payload = gson.fromJson(output, Map::class.java) as Map<*, *>
        val htmlFiles = payload["htmlFiles"] as List<*>
        val baseCss = htmlFiles
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["path"] == "base.css" }
            ?.get("content")
            ?.toString()
            ?: error("base.css content not found")
        val html = htmlFiles
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["path"] == "index.html" }
            ?.get("content")
            ?.toString()
            ?: error("index.html content not found")
        assertTrue(html.contains("color-scheme: light dark"))
        assertTrue(baseCss.contains("data-oob-color-scheme=\"dark\""))
        assertTrue(baseCss.contains(":root:not([data-oob-color-scheme])"))

        val node = findExecutable("node")
        assumeTrue("node not available; skipping generated JS parse check", node != null)
        val htmlFile = File.createTempFile("oob-project-html", ".html").apply {
            writeText(html)
        }
        val parserScript = File.createTempFile("oob-project-html-parse", ".js").apply {
            writeText(
                """
                const fs = require('fs');
                const html = fs.readFileSync(process.argv[2], 'utf8');
                const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)]
                  .map((m) => m[1])
                  .join('\n');
                new Function(scripts);
                """.trimIndent()
            )
        }
        val nodeResult = ProcessBuilder(node, parserScript.absolutePath, htmlFile.absolutePath)
            .redirectErrorStream(true)
            .start()
            .also { it.waitFor() }
        val nodeOutput = nodeResult.inputStream.bufferedReader().readText()
        assertEquals(nodeOutput, 0, nodeResult.exitValue())
    }

    private fun builtinSkillsRoot(): File {
        val candidates = listOf(
            File("src/main/assets/builtin_skills"),
            File("app/src/main/assets/builtin_skills")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate builtin skills root from ${File(".").absolutePath}")
    }

    private fun findExecutable(name: String): String? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return path.map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
    }

    private val todoCheckInContract = """
        {
          "projectId": "oob-workbench-todo-checkin-test",
          "name": "Todo 打卡清单",
          "entity": {"name": "Todo", "primaryAction": "打卡待办"},
          "fields": [
            {"name": "title", "type": "string", "required": true},
            {"name": "checked", "type": "boolean"},
            {"name": "note", "type": "string"},
            {"name": "createdAt", "type": "date"}
          ],
          "actions": [
            {
              "id": "todo.create",
              "executor": "native.collection.create",
              "displayName": "新增待办",
              "inputs": {
                "title": "string",
                "note": "string?",
                "checked": "boolean?",
                "createdAt": "date?"
              }
            },
            {
              "id": "todo.checkIn",
              "executor": "native.collection.update",
              "displayName": "打卡",
              "inputs": {"item_id": "string", "checked": "boolean"}
            },
            {
              "id": "todo.archive",
              "executor": "native.collection.archive",
              "displayName": "归档",
              "inputs": {"item_id": "string"}
            },
            {
              "id": "todo.list",
              "executor": "native.collection.list",
              "displayName": "列表",
              "inputs": {}
            }
          ],
          "views": {
            "primary": "完成进度",
            "list": "按创建时间倒序",
            "empty": "暂无待办，添加第一条开始打卡"
          }
        }
    """.trimIndent()
}
