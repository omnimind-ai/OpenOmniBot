import json
import tempfile
from pathlib import Path
import unittest

from omniflow_agentkit import OmniFlowAgentKit, RepoProbe


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


if __name__ == "__main__":
    unittest.main()
