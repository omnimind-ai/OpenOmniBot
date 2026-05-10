package cn.com.omnimind.bot.omniinfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniInferMnnModelsManagerTest {

    @Test
    fun dedupeInstalledRecords_mergesEntriesSharingSameModelId() {
        val records = listOf(
            record(
                id = "Qwen3.5-2B",
                path = "/data/user/0/cn.com.omnimind.bot/files/.mnnmodels/modelscope/Qwen3.5-2B-MNN",
            ),
            record(
                id = "Qwen3.5-2B",
                path = "/data/user/0/cn.com.omnimind.bot/files/.mnnmodels/hf/Qwen3.5-2B-MNN",
            ),
            record(
                id = "Qwen3.5-7B",
                path = "/data/user/0/cn.com.omnimind.bot/files/.mnnmodels/modelscope/Qwen3.5-7B-MNN",
            ),
        )

        val deduped = OmniInferMnnModelsManager.dedupeInstalledRecords(records)

        assertEquals(2, deduped.size)
        assertEquals("Qwen3.5-2B", deduped[0].id)
        assertEquals("Qwen3.5-7B", deduped[1].id)
    }

    @Test
    fun dedupeInstalledRecords_prefersWritableRecordWhenModelIdsMatch() {
        val records = listOf(
            record(
                id = "Qwen3.5-2B",
                path = "/data/user/0/cn.com.omnimind.bot/files/.mnnmodels/modelscope/Qwen3.5-2B-MNN",
                readOnly = true,
            ),
            record(
                id = "Qwen3.5-2B",
                path = "/data/user/0/cn.com.omnimind.bot/files/.mnnmodels/hf/Qwen3.5-2B-MNN",
                readOnly = false,
            ),
        )

        val deduped = OmniInferMnnModelsManager.dedupeInstalledRecords(records)

        assertEquals(1, deduped.size)
        assertTrue(deduped.single().path.contains("/hf/"))
    }

    private fun record(
        id: String,
        path: String,
        readOnly: Boolean = false,
    ): OmniInferMnnModelsManager.InstalledModelRecord {
        return OmniInferMnnModelsManager.InstalledModelRecord(
            id = id,
            name = id.substringAfterLast('/'),
            path = path,
            configPath = "$path/config.json",
            downloadModelId = null,
            source = id.substringBefore('/'),
            description = "",
            vendor = "",
            tags = listOf("MNN"),
            extraTags = emptyList(),
            fileSize = 0L,
            downloadedAt = 0L,
            readOnly = readOnly,
            downloadInfo = MnnDownloadInfo(),
        )
    }
}
