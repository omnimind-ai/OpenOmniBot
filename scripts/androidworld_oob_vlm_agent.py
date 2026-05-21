#!/usr/bin/env python3
"""Run AndroidWorld tasks through OOB's debug VLM RunLog receiver.

This adapter implements AndroidWorld's EnvironmentInteractingAgent interface and
delegates each task step to OOB VLM execution. Prompt and validation guidance
stay in the selected OOB builtin skill; this script only bridges process APIs.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import subprocess
import time
import traceback
from pathlib import Path
from typing import Any

from android_world import episode_runner
from android_world import registry
from android_world.agents import base_agent
from android_world.env import env_launcher


class OobVlmAndroidWorldAgent(base_agent.EnvironmentInteractingAgent):
    """AndroidWorld agent that executes one AndroidWorld step via OOB VLM."""

    RESULT_FILE = "files/debug-vlm-runlog-result.json"

    def __init__(
        self,
        env,
        *,
        adb_path: str,
        serial: str,
        package: str,
        receiver_package: str,
        max_steps: int,
        timeout_seconds: int,
        skill_id: str,
        profile_id: str,
        model_id: str,
        oob_package_name: str,
        start_from_current: bool,
    ) -> None:
        super().__init__(env=env, name="OOB-VLM", transition_pause=0)
        self.adb_path = adb_path
        self.serial = serial
        self.package = package
        self.receiver = f"{package}/{receiver_package}.debug.DebugVlmRunLogReceiver"
        self.max_steps = max_steps
        self.timeout_seconds = timeout_seconds
        self.skill_id = skill_id
        self.profile_id = profile_id
        self.model_id = model_id
        self.oob_package_name = oob_package_name
        self.start_from_current = start_from_current

    def _adb(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.adb_path, "-s", self.serial, *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=check,
        )

    def _prepare_oob(self) -> None:
        service = (
            f"{self.package}/"
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        )
        self._adb("shell", "settings", "put", "secure", "enabled_accessibility_services", service)
        self._adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    def _read_result(self) -> dict[str, Any] | None:
        proc = self._adb(
            "shell",
            "run-as",
            self.package,
            "cat",
            self.RESULT_FILE,
            check=False,
        )
        if proc.returncode != 0:
            return None
        text = proc.stdout.strip()
        if not text:
            return None
        return json.loads(text)

    def step(self, goal: str) -> base_agent.AgentInteractionResult:
        self._prepare_oob()
        self._adb(
            "shell",
            "run-as",
            self.package,
            "rm",
            "-f",
            self.RESULT_FILE,
            check=False,
        )
        goal_b64 = base64.b64encode(goal.encode("utf-8")).decode("ascii")
        cmd = [
            "shell",
            "am",
            "broadcast",
            "-a",
            f"{self.package}.RUN_VLM_RUNLOG",
            "-n",
            self.receiver,
            "--es",
            "goalBase64",
            goal_b64,
            "--ez",
            "prelaunch",
            "false" if self.start_from_current else "true",
            "--ez",
            "startFromCurrent",
            "true" if self.start_from_current else "false",
            "--ez",
            "skipGoHome",
            "true" if self.start_from_current else "false",
            "--ei",
            "maxSteps",
            str(self.max_steps),
            "--ez",
            "register",
            "false",
            "--es",
            "skillId",
            self.skill_id,
        ]
        if self.oob_package_name:
            cmd.extend(["--es", "packageName", self.oob_package_name])
        if self.profile_id:
            cmd.extend(["--es", "profileId", self.profile_id])
        if self.model_id:
            cmd.extend(["--es", "modelId", self.model_id])
        self._adb(*cmd)

        deadline = time.time() + self.timeout_seconds
        result = None
        while time.time() < deadline:
            result = self._read_result()
            if result is not None:
                break
            time.sleep(2)

        if result is None:
            result = {
                "success": False,
                "phase": "timeout",
                "error_message": f"Timed out after {self.timeout_seconds}s",
            }
        outcome = result.get("outcome") or {}
        done = outcome.get("status") == "FINISHED"
        return base_agent.AgentInteractionResult(
            done=done,
            data={
                "oob_done": done,
                "oob_success": bool(result.get("success")),
                "oob_status": outcome.get("status"),
                "oob_message": outcome.get("message"),
                "run_id": result.get("run_id"),
                "runlog_success": result.get("runlog_success"),
                "runlog_card_count": result.get("runlog_card_count"),
                "step_skill_guidance_chars": result.get("step_skill_guidance_chars"),
                "raw_oob_result": result,
            },
        )


def run_one(args: argparse.Namespace) -> int:
    env = env_launcher.load_and_setup_env(
        console_port=args.console_port,
        grpc_port=args.grpc_port,
        adb_path=args.adb_path,
        emulator_setup=False,
        freeze_datetime=args.freeze_device_date,
    )
    task_registry = registry.TaskRegistry().get_registry(args.family)
    task_cls = task_registry[args.task]
    task_cls.set_device_time(env)
    params = json.loads(args.params) if args.params else task_cls.generate_random_params()
    params.setdefault("seed", args.seed)
    task = task_cls(params)
    if args.preserve_current_date:
        task.device_time = dt.datetime.now()

    agent = OobVlmAndroidWorldAgent(
        env,
        adb_path=args.adb_path,
        serial=args.serial,
        package=args.package,
        receiver_package=args.receiver_package,
        max_steps=args.oob_max_steps,
        timeout_seconds=args.timeout_seconds,
        skill_id=args.skill_id,
        profile_id=args.profile_id,
        model_id=args.model_id,
        oob_package_name=args.oob_package_name,
        start_from_current=args.start_from_current,
    )
    started = time.time()
    error = None
    episode = None
    score = 0.0
    try:
        task.initialize_task(env)
        episode = episode_runner.run_episode(
            goal=task.goal,
            agent=agent,
            max_n_steps=args.androidworld_max_steps,
            start_on_home_screen=task.start_on_home_screen,
        )
        score = task.is_successful(env) if episode.done else 0.0
    except Exception:
        error = traceback.format_exc()
    finally:
        try:
            task.tear_down(env)
        except Exception:
            if error is None:
                error = traceback.format_exc()

    result = {
        "task": args.task,
        "family": args.family,
        "goal": task.goal,
        "params": params,
        "androidworld_done": bool(episode.done) if episode else False,
        "androidworld_success": float(score),
        "episode_length": len(episode.step_data.get("step_number", [])) if episode else 0,
        "run_time_seconds": time.time() - started,
        "error": error,
        "episode_data": episode.step_data if episode else None,
    }
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if error is None else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5556")
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--adb-path", default="/opt/homebrew/bin/adb")
    parser.add_argument("--package", default="cn.com.omnimind.bot.debug")
    parser.add_argument("--receiver-package", default="cn.com.omnimind.bot")
    parser.add_argument("--family", default="android")
    parser.add_argument("--task", default="OpenAppTaskEval")
    parser.add_argument("--params", default='{"app_name":"settings"}')
    parser.add_argument("--seed", type=int, default=123)
    parser.add_argument("--oob-max-steps", type=int, default=8)
    parser.add_argument("--androidworld-max-steps", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=int, default=240)
    parser.add_argument("--skill-id", default="vlm-android-gui")
    parser.add_argument("--profile-id", default="")
    parser.add_argument("--model-id", default="")
    parser.add_argument("--oob-package-name", default="")
    parser.add_argument("--start-from-current", action="store_true")
    parser.add_argument("--freeze-device-date", action="store_true")
    parser.add_argument("--preserve-current-date", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--output", default="/private/tmp/oob_androidworld_result.json")
    return run_one(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
