from contextlib import redirect_stdout
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
            package["sample_function"]["schema_version"],
            "oob.reusable_function.v1",
        )

    def test_agent_prompt_mentions_task(self):
        prompt = OmniFlowAgentKit().agent_prompt("Run saved function")
        self.assertIn("Run saved function", prompt)
        self.assertIn("Guard", prompt)

    def test_sample_function_json_is_valid(self):
        path = Path("docs/omniflow/samples/open-settings-function.json")
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(data["function_id"], "open_settings_demo")

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

    def test_cli_triggers_existing_function_over_mcp(self):
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
                        {"name": "oob_function_list"},
                        {"name": "oob_function_register"},
                        {"name": "oob_function_guard_check"},
                        {"name": "oob_function_run"},
                        {"name": "oob_run_log_list"},
                        {"name": "oob_run_log_convert"},
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
                            "function_id": "open_settings_demo",
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
                        "accepted": True,
                        "function_id": "install_sample_apk_demo",
                        "status": "created",
                    }
                elif name == "oob_function_list":
                    result = {"functions": [{"function_id": "open_settings_demo"}]}
                elif name == "oob_function_guard_check":
                    result = {
                        "function_id": arguments.get("functionId"),
                        "decision": "allow",
                        "risk_level": "low",
                    }
                elif name == "oob_function_run":
                    function_id = arguments.get("functionId")
                    if arguments.get("executionMode") == "background":
                        result = {
                            "success": True,
                            "function_id": function_id,
                            "runner": "mock_oob_background_worker",
                            "guard_decision": "allow",
                            "execution_mode": "background",
                            "run_id": "mock-bg-run-1",
                        }
                    else:
                        result = {
                            "success": True,
                            "function_id": function_id,
                            "runner": "mock_oob_mcp",
                            "guard_decision": "allow",
                            "run_id": "mock-run-1",
                        }
                elif name == "oob_run_log_convert":
                    result = {
                        "success": True,
                        "registered": arguments.get("register"),
                        "function_id": arguments.get("functionId"),
                        "function_spec": {"function_id": arguments.get("functionId")},
                    }
            return FakeResponse({"jsonrpc": "2.0", "id": payload.get("id"), "result": result})

        with patch("omniflow_agentkit.mcp.request.urlopen", fake_urlopen):
            url = "http://127.0.0.1:8765/mcp"
            client = OmniFlowMcpClient(url)
            self.assertTrue(client.has_direct_omniflow())
            self.assertTrue(client.has_canonical_omniflow())

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-run-function", "open_settings_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            result = json.loads(stdout.getvalue())
            self.assertTrue(result["success"])
            self.assertEqual(result["function_id"], "open_settings_demo")
            self.assertEqual(result["guard_decision"], "allow")
            self.assertEqual(result["result"]["run_id"], "mock-run-1")
            self.assertEqual(calls, ["oob_function_guard_check", "oob_function_run"])
            calls.clear()

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-recall", "open settings", "--mcp-url", url])
            self.assertEqual(code, 0)
            recalled = json.loads(stdout.getvalue())
            self.assertEqual(recalled["decision"], "hit")
            self.assertEqual(recalled["hit"]["function_id"], "open_settings_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-call-function", "open_settings_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            called = json.loads(stdout.getvalue())
            self.assertTrue(called["success"])
            self.assertEqual(called["function_id"], "open_settings_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(["mcp-ingest-runlog", "runlog_install_demo", "--mcp-url", url])
            self.assertEqual(code, 0)
            ingested = json.loads(stdout.getvalue())
            self.assertTrue(ingested["accepted"])
            self.assertEqual(calls, [
                "omniflow.recall",
                "omniflow.call_function",
                "omniflow.ingest_run_log",
            ])
            calls.clear()

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(
                    [
                        "mcp-convert-runlog",
                        "runlog_install_demo",
                        "--mcp-url",
                        url,
                        "--register",
                        "--function-id",
                        "install_sample_apk_demo",
                    ]
                )
            self.assertEqual(code, 0)
            converted = json.loads(stdout.getvalue())
            self.assertTrue(converted["registered"])
            self.assertEqual(converted["function_id"], "install_sample_apk_demo")

            stdout = StringIO()
            with redirect_stdout(stdout):
                code = main(
                    [
                        "mcp-run-function",
                        "install_sample_apk_demo",
                        "--mcp-url",
                        url,
                        "--background",
                    ]
                )
            self.assertEqual(code, 0)
            background = json.loads(stdout.getvalue())
            self.assertTrue(background["success"])
            self.assertEqual(background["execution_mode"], "background")
            self.assertEqual(background["result"]["run_id"], "mock-bg-run-1")


if __name__ == "__main__":
    unittest.main()
