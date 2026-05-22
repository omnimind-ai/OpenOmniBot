package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobPageVectorSetTest {
    @Test
    fun `page vector set encodes normalized 512 dimension page vector`() {
        val vector = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.example.settings"))

        assertEquals(OobPageVectorSet.PAGE_DIM, vector.vector.size)
        assertEquals("com.example.settings", vector.packageName)
        assertTrue(vector.elementCount >= 3)
        assertTrue(vector.actionableCount >= 1)
        assertEquals(false, ((vector.toMap()["privacy"] as Map<*, *>)["raw_xml_stored"]))
    }

    @Test
    fun `page similarity is high for same page family and lower for different page`() {
        val source = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.example.settings"))
        val sameFamily = requireNotNull(OobPageVectorSet.encode(SOURCE_XML_VARIANT, "com.example.settings"))
        val other = requireNotNull(OobPageVectorSet.encode(OTHER_XML, "com.example.settings"))

        val sameScore = OobPageVectorSet.cosine(source.vector, sameFamily.vector)
        val otherScore = OobPageVectorSet.cosine(source.vector, other.vector)

        assertTrue("same page family should match, score=$sameScore", sameScore > 0.80f)
        assertTrue("different page should be lower, same=$sameScore other=$otherScore", sameScore > otherScore)
    }

    @Test
    fun `udeg node store recalls node by current page match`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val result = store.upsertFunction(
                functionId = "open_network_settings",
                functionSpec = functionSpecWithSourcePage("open_network_settings", SOURCE_XML),
            )
            assertEquals(true, result["success"])
            assertEquals(true, result["indexed"])

            val matches = store.recall(SOURCE_XML_VARIANT, "com.example.settings", topK = 3)
            assertTrue(matches.isNotEmpty())
            val first = matches.first()
            assertTrue(first.pageSimilarity > 0.80f)
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(first.node))
            val nodeSkill = first.node["skill"] as? Map<*, *>
            assertEquals("udeg_node_skill", nodeSkill?.get("kind"))
            assertEquals("page_match", nodeSkill?.get("activation"))
            assertEquals("decision_context", nodeSkill?.get("role"))
            assertNotNull(nodeSkill?.get("decision_guidance"))
            assertTrue(nodeSkill?.get("body")?.toString().orEmpty().contains("## Decision Context"))

            val decisionContext = first.node["decision_context"] as? Map<*, *>
            assertEquals("decision", decisionContext?.get("role"))
            assertEquals("page_match_to_udeg_node", decisionContext?.get("entry_policy"))
            val recallMap = first.toMap()
            val recallDecisionContext = recallMap["decision_context"] as? Map<*, *>
            assertEquals("page_match_to_udeg_node", recallDecisionContext?.get("entry_policy"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun functionSpecWithSourcePage(functionId: String, xml: String): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to "Open network settings",
        "description" to "Open network settings from the Settings page.",
        "parameters" to emptyList<Map<String, Any?>>(),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "title" to "Tap Network",
                    "tool" to "click",
                    "omniflow_action" to "click",
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to xml,
                            "package_name" to "com.example.settings",
                        )
                    )
                )
            )
        )
    )

    private companion object {
        const val SOURCE_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[32,240][1048,360]" resource-id="com.example.settings:id/network" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """

        const val SOURCE_XML_VARIANT = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[40,70][410,165]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[40,250][1040,372]" resource-id="com.example.settings:id/network" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[40,392][1040,514]" />
              </node>
            </hierarchy>
        """

        const val OTHER_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Contacts" bounds="[32,64][400,160]" />
                <node class="android.widget.EditText" package="com.example.settings" text="" hint-text="First name" editable="true" focusable="true" bounds="[32,240][1048,340]" />
                <node class="android.widget.EditText" package="com.example.settings" text="" hint-text="Phone" editable="true" focusable="true" bounds="[32,380][1048,480]" />
              </node>
            </hierarchy>
        """
    }
}
