from contextlib import redirect_stdout
from io import StringIO
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "androidworld_oob_eval.py"


def load_script_module():
    spec = importlib.util.spec_from_file_location("androidworld_oob_eval", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class AndroidWorldOobEvalTest(unittest.TestCase):
    def test_default_mode_writes_method_record_without_androidworld(self):
        module = load_script_module()
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "method.json"
            stdout = StringIO()
            with mock.patch.object(
                module,
                "load_android_world",
                side_effect=AssertionError("method-only must not load AndroidWorld"),
            ):
                with redirect_stdout(stdout):
                    code = module.main(
                        [
                            "--task",
                            "ClockStopWatchRunning",
                            "--timeout",
                            "420",
                            "--max-steps",
                            "12",
                            "--output",
                            str(output),
                        ]
                    )

            self.assertEqual(code, 0)
            self.assertIn("wrote_method_record=", stdout.getvalue())
            record = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(record["mode"], "method_only")
            self.assertTrue(record["scope"]["does_not_run_androidworld_episode"])
            self.assertTrue(record["scope"]["live_runner_requires_explicit_run_live"])
            self.assertIn("OpenAppTaskEval", record["scope"]["simple_validation_tasks"])
            self.assertEqual(record["oob_adapter_method"]["control_fields"]["timeoutMs"], 420000)
            self.assertEqual(
                record["oob_adapter_method"]["control_fields"]["disableOmniFlowRecall"],
                "true for online_vlm validation, false for recall_repeat",
            )
            self.assertEqual(
                record["oob_adapter_method"]["live_phases"],
                ["online_vlm", "replay", "recall_repeat"],
            )
            self.assertIn(
                "disableOmniFlowRecall=true",
                " ".join(record["oob_runtime_alignment"]["online_vlm"]),
            )
            self.assertEqual(
                record["mobilerun_reference_method"]["source"],
                "droidrun/mobilerun is recorded as a process reference only, not as an OOB runtime dependency.",
            )
            self.assertIn(
                "mobilerun/agent/fast_agent/fast_agent.py",
                record["mobilerun_reference_method"]["reviewed_flow_sources"],
            )
            self.assertEqual(
                record["mobilerun_reference_method"]["oob_native_mapping"]["tool_registry"],
                "OOB VLMToolDefinitions and native DeviceOperator actions.",
            )
            self.assertIn(
                "Do not call the Mobilerun Python agent loop.",
                record["mobilerun_reference_method"]["explicit_non_goals"],
            )
            self.assertIn(
                "Do not invoke Mobilerun CLI or MCP tools from OOB validation.",
                record["mobilerun_reference_method"]["explicit_non_goals"],
            )
            self.assertIn("diagnosis_of_oob_gap", record)
            layering = record["validation_without_androidworld_run"]["test_layering"]
            self.assertIn(
                "Flutter reusable command library tests cover grouping, detail surface, run button, running state, and result sheet internal-field hiding.",
                layering["ux_tests"],
            )
            self.assertIn(
                "Python AndroidWorld method-only tests record the adapter contract without importing AndroidWorld or starting an emulator.",
                layering["runtime_method_tests"],
            )
            self.assertIn("UX tests must not import AndroidWorld", layering["separation_rule"])
            rendered = json.dumps(record, ensure_ascii=False).lower()
            self.assertNotIn("compile", rendered)
            self.assertNotIn("编译", rendered)

    def test_live_mode_requires_task_before_loading_androidworld(self):
        module = load_script_module()
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "live.json"
            with mock.patch.object(
                module,
                "load_android_world",
                side_effect=AssertionError("missing-task validation should run first"),
            ):
                with self.assertRaises(SystemExit) as error:
                    module.main(["--run-live", "--output", str(output)])

        self.assertEqual(error.exception.code, 2)
        self.assertFalse(output.exists())

    def test_live_simple_suite_expands_tasks_before_loading_androidworld(self):
        module = load_script_module()
        parser = module.build_arg_parser()
        args = parser.parse_args(["--run-live", "--simple-suite"])

        self.assertEqual(module.resolve_live_tasks(args), module.SIMPLE_VALIDATION_TASKS)

    def test_oob_summaries_preserve_runtime_statistics(self):
        module = load_script_module()

        vlm_summary = module.summarize_vlm_result(
            {
                "success": True,
                "run_id": "run-1",
                "outcome": {"status": "FINISHED", "executionRoute": "vlm"},
                "token_usage": {"total_tokens": 42, "call_count": 2},
                "token_usage_by_step": [{"step_index": 0}],
                "token_usage_by_call": [{"call_index": 0}, {"call_index": 1}],
                "convert": {"success": True, "function_id": "fn-1"},
                "timing": {
                    "started_at_ms": 100,
                    "finished_at_ms": 250,
                    "duration_ms": 150,
                    "phase_ms": {
                        "parse_request_ms": 1,
                        "read_current_page_ms": 2,
                    },
                },
            }
        )
        self.assertEqual(vlm_summary["token_usage_total"], 42)
        self.assertEqual(vlm_summary["token_usage_call_count"], 2)
        self.assertEqual(vlm_summary["token_usage_by_step_count"], 1)
        self.assertEqual(vlm_summary["token_usage_by_call_count"], 2)
        self.assertEqual(vlm_summary["started_at_ms"], 100)
        self.assertEqual(vlm_summary["finished_at_ms"], 250)
        self.assertEqual(vlm_summary["duration_ms"], 150)
        self.assertEqual(vlm_summary["phase_ms"]["read_current_page_ms"], 2)

        function_summary = module.summarize_function_result(
            {
                "success": True,
                "guard": {"decision": "allow"},
                "result": {
                    "step_results": [{"success": True}],
                    "timing": {
                        "started_at_ms": 300,
                        "finished_at_ms": 340,
                        "runner_duration_ms": 40,
                        "phase_ms": {"execute_steps_ms": 39},
                    },
                },
            }
        )
        self.assertEqual(function_summary["step_count"], 1)
        self.assertEqual(function_summary["guard_decision"], "allow")
        self.assertEqual(function_summary["started_at_ms"], 300)
        self.assertEqual(function_summary["finished_at_ms"], 340)
        self.assertEqual(function_summary["duration_ms"], 40)
        self.assertEqual(function_summary["runner_duration_ms"], 40)
        self.assertEqual(function_summary["phase_ms"]["execute_steps_ms"], 39)


if __name__ == "__main__":
    unittest.main()
