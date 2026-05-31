from contextlib import redirect_stdout
from io import StringIO
import json
import os
import tempfile
from pathlib import Path
import unittest

from omniflow_agentkit import OmniFlowAgentKit, OmniFlowMcpClient, RepoProbe
from omniflow_agentkit.__main__ import main


class OmniFlowAgentKitTest(unittest.TestCase):
    def test_package_contains_skill_and_sample(self):
        kit = OmniFlowAgentKit()
        package = kit.package(include_docs=False)
        self.assertIn("GUIAgent OmniFlow Skill", package["skill"])
        self.assertEqual(
            package["activation_tools"],
            [
                "omniflow.recall",
                "omniflow.call_function",
                "omniflow.ingest_run_log",
                "omniflow.explore_replay",
                "oob_function_list",
                "oob_function_get",
                "oob_function_register",
                "oob_function_guard_check",
                "oob_function_run",
                "oob_run_log_list",
                "oob_run_log_get",
                "oob_run_log_convert",
            ],
        )
        self.assertEqual(
            package["sample_function"]["schema_version"],
            "oob.reusable_function.v1",
        )
        self.assertEqual(package["sample_function"]["function_id"], "settings_click_path_demo")
        steps = package["sample_function"]["execution"]["steps"]
        self.assertEqual(len(steps), 7)
        self.assertEqual(sum(1 for step in steps if step["tool"] == "click"), 4)

    def test_agent_prompt_mentions_task(self):
        prompt = OmniFlowAgentKit().agent_prompt("Run saved function")
        self.assertIn("Run saved function", prompt)
        self.assertIn("Guard", prompt)

    def test_sample_function_json_is_valid(self):
        path = Path("docs/omniflow/samples/open-settings-function.json")
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(data["function_id"], "settings_click_path_demo")
        self.assertEqual(data["execution"]["step_count"], 7)
        self.assertEqual(sum(1 for step in data["execution"]["steps"] if step["tool"] == "click"), 4)

    def test_mcp_client_run_function_can_resume_from_step(self):
        class CapturingClient(OmniFlowMcpClient):
            def __init__(self):
                super().__init__("http://127.0.0.1/mcp")
                self.calls = []

            def call_tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                return {"success": True}

        client = CapturingClient()
        result = client.run_function(
            "fill_form",
            {"name": "Eve"},
            resume_from_step=3,
            fallback_session_id="fallback_1",
            fallback_attempt=1,
        )

        self.assertTrue(result["success"])
        self.assertEqual(client.calls[0][0], "oob_function_run")
        self.assertEqual(client.calls[0][1]["functionId"], "fill_form")
        self.assertEqual(client.calls[0][1]["arguments"], {"name": "Eve"})
        self.assertEqual(client.calls[0][1]["resume_from_step"], 3)
        self.assertEqual(client.calls[0][1]["fallback_session_id"], "fallback_1")
        self.assertEqual(client.calls[0][1]["fallback_attempt"], 1)

    def test_assets_fallback_without_repo_docs(self):
        with tempfile.TemporaryDirectory() as tmp:
            kit = OmniFlowAgentKit(root=Path(tmp))
            self.assertIn("GUIAgent OmniFlow Skill", kit.skill())

    def test_repo_probe_python_agent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("A mobile GUI agent using OpenAI.", encoding="utf-8")
            (root / "requirements.txt").write_text("openai\n", encoding="utf-8")
            report = RepoProbe(root).run()
            self.assertEqual(report.recommended_mode, "python_skill_plus_mcp")
            self.assertIn("python", report.detected_stack)

    def test_cli_can_use_real_mcp_function_tools_when_configured(self):
        url = os.environ.get("OMNIFLOW_MCP_URL") or os.environ.get("OOB_MCP_URL")
        token = os.environ.get("OMNIFLOW_MCP_TOKEN") or os.environ.get("OOB_MCP_TOKEN")
        if not url:
            self.skipTest("Set OMNIFLOW_MCP_URL or OOB_MCP_URL to run real MCP integration")

        client = OmniFlowMcpClient(url, token=token)
        self.assertTrue(client.has_direct_omniflow())
        self.assertTrue(client.has_canonical_omniflow())

        base_args = ["--mcp-url", url]
        if token:
            base_args += ["--token", token]

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-recall", "open settings", *base_args])
        self.assertEqual(code, 0)
        recalled = json.loads(stdout.getvalue())
        self.assertEqual(recalled["decision"], "hit")
        self.assertEqual(recalled["hit"]["function_id"], "settings_click_path_demo")

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-call-function", "settings_click_path_demo", *base_args])
        self.assertEqual(code, 0)
        called = json.loads(stdout.getvalue())
        self.assertTrue(called["success"])
        self.assertEqual(called["function_id"], "settings_click_path_demo")
        self.assertTrue(called.get("run_id"))

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-ingest-runlog", "runlog_install_demo", *base_args])
        self.assertEqual(code, 0)
        ingested = json.loads(stdout.getvalue())
        self.assertTrue(ingested["accepted"])
        self.assertEqual(ingested["function_id"], "install_sample_apk_demo")

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-list-functions", *base_args])
        self.assertEqual(code, 0)
        function_list = json.loads(stdout.getvalue())
        self.assertGreaterEqual(function_list["count"], 1)

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-get-function", "settings_click_path_demo", *base_args])
        self.assertEqual(code, 0)
        function_get = json.loads(stdout.getvalue())
        self.assertEqual(function_get["function_id"], "settings_click_path_demo")

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-guard-check", "settings_click_path_demo", *base_args])
        self.assertEqual(code, 0)
        guard = json.loads(stdout.getvalue())
        self.assertIn(guard["decision"], {"allow", "needs_agent", "needs_confirmation"})

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-list-runlogs", *base_args])
        self.assertEqual(code, 0)
        runlogs = json.loads(stdout.getvalue())
        self.assertGreaterEqual(runlogs["count"], 1)

        stdout = StringIO()
        with redirect_stdout(stdout):
            code = main(["mcp-convert-runlog", "runlog_install_demo", *base_args])
        self.assertEqual(code, 0)
        converted = json.loads(stdout.getvalue())
        self.assertEqual(converted["function_id"], "install_sample_apk_demo")


if __name__ == "__main__":
    unittest.main()
