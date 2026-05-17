from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

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

    def test_cli_can_use_canonical_function_tools(self):
        calls = []

        class FakeResponse:
            def __init__(self, payload):
                self.payload = payload

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                return False

            def read(self):
                return json.dumps(self.payload).encode("utf-8")

        def fake_urlopen(req, timeout):
            payload = json.loads(req.data.decode("utf-8"))
            method = payload["method"]
            result = {}
            if method == "tools/list":
                result = {
                    "tools": [
                        {"name": "omniflow.recall"},
                        {"name": "omniflow.call_function"},
                        {"name": "omniflow.ingest_run_log"},
                    ]
                }
            elif method == "tools/call":
                params = payload.get("params", {})
                name = params.get("name")
                arguments = params.get("arguments", {})
                calls.append(name)
                if name == "omniflow.recall":
                    result = {
                        "success": True,
                        "decision": "hit",
                        "hit": {
                            "function_id": "settings_click_path_demo",
                            "inputSchema": {"type": "object", "properties": {}, "required": []},
                        },
                        "candidates": [],
                    }
                elif name == "omniflow.call_function":
                    result = {
                        "success": True,
                        "fallback": False,
                        "error": None,
                        "function_id": arguments.get("function_id"),
                        "run_id": "mock-run-1",
                        "actions_executed": 2,
                    }
                elif name == "omniflow.ingest_run_log":
                    result = {
                        "success": True,
                        "accepted": True,
                        "function_id": "install_sample_apk_demo",
                        "status": "created",
                    }
            return FakeResponse({"jsonrpc": "2.0", "id": payload.get("id"), "result": result})

        with patch("omniflow_agentkit.mcp.request.urlopen", fake_urlopen):
            url = "http://127.0.0.1:8765/mcp"
            client = OmniFlowMcpClient(url)
            self.assertTrue(client.has_direct_omniflow())
            self.assertTrue(client.has_canonical_omniflow())

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-recall", "open settings", "--mcp-url", url])
            self.assertEqual(code, 0)
            recalled = json.loads(stdout.getvalue())
            self.assertEqual(recalled["decision"], "hit")
            self.assertEqual(recalled["hit"]["function_id"], "settings_click_path_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-call-function", "settings_click_path_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            called = json.loads(stdout.getvalue())
            self.assertTrue(called["success"])
            self.assertEqual(called["function_id"], "settings_click_path_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-ingest-runlog", "runlog_install_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            ingested = json.loads(stdout.getvalue())
            self.assertTrue(ingested["accepted"])
            self.assertEqual(ingested["function_id"], "install_sample_apk_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-call-function", "install_sample_apk_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            installed = json.loads(stdout.getvalue())
            self.assertTrue(installed["success"])
            self.assertEqual(installed["function_id"], "install_sample_apk_demo")

            self.assertEqual(calls, [
                "omniflow.recall",
                "omniflow.call_function",
                "omniflow.ingest_run_log",
                "omniflow.call_function",
            ])

            legacy_cmd = "mcp-" + "run-function"
            stderr = StringIO()
            with redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as raised:
                    main([legacy_cmd, "settings_click_path_demo", "--mcp-url", url])
            self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
