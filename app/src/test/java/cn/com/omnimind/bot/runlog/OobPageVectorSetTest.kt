package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `page vector prefers xml package when supplied foreground package is stale`() {
        val vector = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.android.launcher"))

        assertEquals("com.example.settings", vector.packageName)
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
            val activation = nodeSkill?.get("activation") as? Map<*, *>
            assertEquals("page_match", activation?.get("type"))
            assertEquals("decision_context", nodeSkill?.get("role"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkill?.get("decision_path"))
            val frontmatter = nodeSkill?.get("frontmatter") as? Map<*, *>
            assertNotNull(frontmatter?.get("name"))
            assertNotNull(nodeSkill?.get("decision_guidance"))
            assertTrue(nodeSkill?.get("body")?.toString().orEmpty().contains("## Decision Context"))
            assertTrue(nodeSkill?.get("body")?.toString().orEmpty().contains("## Decision Rules"))

            val decisionContext = first.node["decision_context"] as? Map<*, *>
            assertEquals("decision", decisionContext?.get("role"))
            assertEquals("page_match_to_udeg_node", decisionContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, decisionContext?.get("decision_path"))
            val recallMap = first.toMap()
            val recallDecisionContext = recallMap["decision_context"] as? Map<*, *>
            assertEquals("page_match_to_udeg_node", recallDecisionContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, recallDecisionContext?.get("decision_path"))
            val nodeSkillContext = recallMap["node_skill_context"] as? Map<*, *>
            assertEquals("decision", nodeSkillContext?.get("role"))
            assertEquals("udeg_node_skill_like_decision_context", nodeSkillContext?.get("context_kind"))
            assertEquals("page_match_to_udeg_node", nodeSkillContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkillContext?.get("decision_path"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg node recall soft penalizes package mismatch instead of hard filtering`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            store.upsertFunction(
                functionId = "open_network_settings",
                functionSpec = functionSpecWithSourcePage("open_network_settings", SOURCE_XML),
            )

            val staleForegroundMatches = store.recall(SOURCE_XML_VARIANT, "com.android.launcher", topK = 3)
            assertTrue(staleForegroundMatches.isNotEmpty())
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(staleForegroundMatches.first().node))

            val crossPackageMatches = store.recall(SOURCE_XML_OTHER_PACKAGE, "com.example.other", topK = 3)
            assertTrue(crossPackageMatches.isNotEmpty())
            val first = crossPackageMatches.first()
            assertTrue(first.reason.contains("package_soft_mismatch"))
            assertTrue(first.pageSimilarity < OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE)
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(first.node))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `observed page creates reusable udeg node analysis with vector match`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val first = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = SOURCE_XML,
                        packageName = "com.example.settings",
                        screenshotBase64 = "fake-screenshot",
                        goal = "open display settings",
                    )
                )
            )

            assertTrue(first.firstSeen)
            assertEquals(1.0f, first.pageSimilarity)
            val pageAnalysis = first.node["page_analysis"] as? Map<*, *>
            assertNotNull(pageAnalysis)
            val privacy = pageAnalysis?.get("privacy") as? Map<*, *>
            assertEquals(false, privacy?.get("raw_xml_stored"))
            assertEquals(false, privacy?.get("raw_screenshot_stored"))
            val visual = pageAnalysis?.get("visual_observation") as? Map<*, *>
            assertEquals(true, visual?.get("screenshot_present"))
            assertNotNull(visual?.get("screenshot_sha256"))
            assertTrue(pageAnalysis?.toString().orEmpty().contains("Display"))
            val summary = pageAnalysis?.get("summary") as? Map<*, *>
            assertTrue(summary?.get("actionables").toString().contains("Network"))
            assertTrue(first.toMap().toString().contains(OobUdegNodeStore.UDEG_DECISION_PATH))

            val second = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = SOURCE_XML,
                        packageName = "com.example.settings",
                    )
                )
            )
            assertEquals(false, second.firstSeen)
            assertEquals(first.node["node_id"], second.node["node_id"])
            val registry = second.node["_oob_registry"] as? Map<*, *>
            assertEquals(2, (registry?.get("seen_count") as Number).toInt())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `observed page analysis does not persist editable field text`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val observed = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = EDITABLE_SECRET_XML,
                        packageName = "com.example.contacts",
                    )
                )
            )
            val pageAnalysisText = observed.node["page_analysis"].toString()
            assertTrue(pageAnalysisText.contains("Phone"))
            assertFalse(pageAnalysisText.contains("415-555-0130"))
            val privacy = ((observed.node["page_analysis"] as? Map<*, *>)?.get("privacy") as? Map<*, *>)
            assertEquals(false, privacy?.get("editable_text_stored"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg store saves captured state artifacts and exports sanitized bundle`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val observedPage = OobUdegNodeStore.ObservedPage(
                pageXml = SOURCE_XML,
                packageName = "com.example.settings",
                activityName = "SettingsActivity",
                screenshotBase64 = "data:image/jpeg;base64,ZmFrZQ==",
                goal = "save current settings page",
            )
            val observed = requireNotNull(store.observePage(observedPage))
            val artifact = store.saveCapturedState(
                observedPage = observedPage,
                observation = observed,
                screenshotBytes = "fake".toByteArray(),
                capturedAtMs = 1234L,
            )

            assertTrue(java.io.File(artifact.xmlPath).exists())
            assertTrue(java.io.File(artifact.screenshotPath!!).exists())
            assertTrue(java.io.File(artifact.manifestPath).exists())
            assertEquals(observed.node["node_id"], artifact.nodeId)

            val export = store.exportBundle()
            assertEquals(true, export["success"])
            assertEquals("oob.udeg.export.v1", export["schema_version"])
            assertTrue(java.io.File(export["export_path"].toString()).exists())
            val payload = export["payload"] as? Map<*, *>
            assertEquals("oob_udeg_export", payload?.get("kind"))
            assertEquals(false, (payload?.get("privacy") as? Map<*, *>)?.get("export_contains_raw_xml"))
            assertTrue((payload?.get("nodes") as? List<*>)?.isNotEmpty() == true)
            assertFalse(payload.toString().contains(SOURCE_XML.trim()))
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

        const val SOURCE_XML_OTHER_PACKAGE = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.other" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.other" text="Settings" bounds="[40,70][410,165]" />
                <node class="android.widget.TextView" package="com.example.other" text="Network" clickable="true" enabled="true" bounds="[40,250][1040,372]" resource-id="com.example.other:id/network" />
                <node class="android.widget.TextView" package="com.example.other" text="Display" clickable="true" enabled="true" bounds="[40,392][1040,514]" />
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

        const val EDITABLE_SECRET_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.contacts" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.contacts" text="Create contact" bounds="[32,64][520,160]" />
                <node class="android.widget.EditText" package="com.example.contacts" text="415-555-0130" hint-text="Phone" editable="true" focusable="true" bounds="[32,240][1048,340]" />
                <node class="android.widget.TextView" package="com.example.contacts" text="Save" clickable="true" enabled="true" bounds="[850,64][1048,160]" />
              </node>
            </hierarchy>
        """
    }
}
