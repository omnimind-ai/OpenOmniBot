#!/usr/bin/env python3
"""AndroidWorld method recorder for OOB Kotlin-native VLM, RunLog, replay, and recall.

By default this writes the M3A/OOB adapter method only and does not load
AndroidWorld or run emulator episodes. A live runner remains behind the explicit
--run-live opt-in for later external validation. AndroidWorld owns task
initialization and success checks in that mode; the OOB APK owns online VLM
execution, RunLog reusable-command generation, replay, and recall.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import time
import traceback
from typing import Any


DEFAULT_ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"
DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"
DEFAULT_SKILL_ID = "vlm-android-gui"
DEFAULT_PROFILE_ID = "profile-dashscope"
DEFAULT_MODEL_ID = os.environ.get("OMNIMIND_MODEL") or os.environ.get("OPENAI_MODEL") or "qwen-vl-max-latest"
DEFAULT_BASE_URL = os.environ.get("OMNIMIND_API_BASE_URL") or "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_API_KEY_ENV = os.environ.get("OMNIMIND_API_KEY_ENV") or "DASHSCOPE_API_KEY"
VLM_ACTION = "cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG"
FUNCTION_ACTION = "cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION"
VLM_RESULT_FILE = "files/debug-vlm-runlog-result.json"
FUNCTION_RESULT_FILE = "files/debug-oob-function-run-result.json"

SIMPLE_VALIDATION_TASKS = [
    "OpenAppTaskEval",
    "SettingsShowVersion",
    "ClockStopWatchRunning",
]

APP_PACKAGE_BY_ANDROIDWORLD_NAME = {
    "camera": "com.android.camera2",
    "clock": "com.google.android.deskclock",
    "contacts": "com.google.android.contacts",
    "dialer": "com.google.android.dialer",
    "settings": "com.android.settings",
}


def find_adb() -> str:
    candidates = [
        Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb",
        Path.home() / "Android" / "Sdk" / "platform-tools" / "adb",
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    return "adb"


def load_android_world(root: Path) -> None:
    if root.exists():
        sys.path.insert(0, str(root))


def run_command(
    command: list[str],
    *,
    timeout: int = 60,
    check: bool = True,
    cwd: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        rendered = " ".join(command)
        raise RuntimeError(
            f"Command failed ({result.returncode}): {rendered}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result


class OobAdbClient:
    def __init__(
        self,
        *,
        adb_path: str,
        device_serial: str,
        package_name: str,
        repo_root: Path,
        timeout_seconds: int,
    ) -> None:
        self.adb_path = adb_path
        self.device_serial = device_serial
        self.package_name = package_name
        self.repo_root = repo_root
        self.timeout_seconds = timeout_seconds
        self.vlm_receiver = f"{package_name}/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
        self.function_receiver = f"{package_name}/cn.com.omnimind.bot.debug.DebugOobFunctionRunReceiver"

    def adb(self, *args: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_command(
            [self.adb_path, "-s", self.device_serial, *args],
            timeout=timeout,
            check=check,
        )

    def configure_provider(
        self,
        *,
        base_url: str,
        api_key_env: str,
        model_id: str,
        profile_id: str,
        skip_configure: bool,
    ) -> None:
        if skip_configure:
            return
        script = self.repo_root / "scripts" / "configure-oob-model-provider.sh"
        run_command(
            [
                "bash",
                str(script),
                "--device",
                self.device_serial,
                "--package",
                self.package_name,
                "--base-url",
                base_url,
                "--api-key-env",
                api_key_env,
                "--model",
                model_id,
                "--profile-id",
                profile_id,
                "--name",
                "DashScope",
                "--no-read-result",
            ],
            cwd=self.repo_root,
            timeout=120,
        )

    def prepare_device(self) -> None:
        oob_component = f"{self.package_name}/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        current_services = self.adb(
            "shell",
            "settings",
            "get",
            "secure",
            "enabled_accessibility_services",
            timeout=30,
            check=False,
        ).stdout.strip()
        if current_services in {"", "null"}:
            enabled_services = oob_component
        else:
            parts = [part for part in current_services.split(":") if part]
            if oob_component not in parts:
                parts.append(oob_component)
            enabled_services = ":".join(parts)
        self.adb("shell", "appops", "set", self.package_name, "SYSTEM_ALERT_WINDOW", "allow")
        self.adb(
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            enabled_services,
        )
        self.adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        self.adb(
            "shell",
            "monkey",
            "-p",
            self.package_name,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
            timeout=30,
            check=False,
        )

    def restore_wall_clock(self) -> None:
        # AndroidWorld freezes device time to October 2023. That is fine for
        # host-side agents such as M3A, but OOB calls the model provider from
        # the Android app, so TLS certificate validation needs a real clock.
        now_utc = dt.datetime.utcnow()
        self.adb(
            "shell",
            "date",
            now_utc.strftime("%m%d%H%M%y.%S"),
            timeout=30,
            check=False,
        )

    def clear_result_files(self) -> None:
        self.adb(
            "shell",
            "run-as",
            self.package_name,
            "rm",
            "-f",
            VLM_RESULT_FILE,
            FUNCTION_RESULT_FILE,
            timeout=30,
            check=False,
        )

    def receiver_start_timeout(self) -> int:
        # AndroidWorld task setup can clear app data and trigger a busy system
        # broadcast queue. Starting OOB from a cold process is still just a
        # control-plane operation, but 30s is too tight on emulator boots.
        return min(max(self.timeout_seconds, 120), 300)

    def force_stop_package(self, package_name: str) -> None:
        self.adb(
            "shell",
            "am",
            "force-stop",
            package_name,
            timeout=8,
            check=False,
        )

    def read_app_file(self, path: str) -> str:
        try:
            result = self.adb(
                "shell",
                "run-as",
                self.package_name,
                "cat",
                path,
                timeout=5,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return ""
        if result.returncode != 0:
            return ""
        return result.stdout.strip()

    def wait_for_json(self, path: str, label: str) -> dict[str, Any]:
        deadline = time.time() + self.timeout_seconds
        last_content = ""
        consecutive_read_misses = 0
        while time.time() < deadline:
            content = self.read_app_file(path)
            if content:
                consecutive_read_misses = 0
                last_content = content
                try:
                    return json.loads(content)
                except json.JSONDecodeError:
                    time.sleep(1)
                    continue
            else:
                consecutive_read_misses += 1
                if consecutive_read_misses >= 6 and not self.is_device_online():
                    raise RuntimeError(
                        f"Timed out reading {label}: device {self.device_serial} is offline; "
                        f"path={path}; last_content={last_content[:500]}"
                    )
            time.sleep(2)
        raise TimeoutError(f"Timed out waiting for {label}: {path}; last_content={last_content[:500]}")

    def is_device_online(self) -> bool:
        try:
            result = self.adb("get-state", timeout=3, check=False)
        except subprocess.TimeoutExpired:
            return False
        return result.returncode == 0 and result.stdout.strip() == "device"

    def run_vlm(
        self,
        *,
        goal: str,
        package_name: str | None,
        max_steps: int,
        register: bool,
        profile_id: str,
        model_id: str,
        skill_id: str,
        prelaunch: bool,
        start_from_current: bool,
        skip_go_home: bool,
        disable_omniflow_recall: bool,
    ) -> dict[str, Any]:
        self.clear_result_files()
        goal_b64 = base64.b64encode(goal.encode("utf-8")).decode("ascii")
        command = [
            "shell",
            "am",
            "broadcast",
            "-a",
            VLM_ACTION,
            "-n",
            self.vlm_receiver,
            "--es",
            "goalBase64",
            goal_b64,
            "--ez",
            "prelaunch",
            str(prelaunch).lower(),
            "--ez",
            "startFromCurrent",
            str(start_from_current).lower(),
            "--ez",
            "skipGoHome",
            str(skip_go_home).lower(),
            "--ez",
            "disableOmniFlowRecall",
            str(disable_omniflow_recall).lower(),
            "--ei",
            "maxSteps",
            str(max_steps),
            "--el",
            "timeoutMs",
            str(self.timeout_seconds * 1000),
            "--ez",
            "register",
            str(register).lower(),
            "--es",
            "profileId",
            profile_id,
            "--es",
            "modelId",
            model_id,
            "--es",
            "skillId",
            skill_id,
        ]
        if package_name:
            command.extend(["--es", "packageName", package_name])
        self.adb(*command, timeout=self.receiver_start_timeout())
        return self.wait_for_json(VLM_RESULT_FILE, "OOB VLM result")

    def run_function(self, *, function_id: str, goal: str) -> dict[str, Any]:
        self.adb(
            "shell",
            "run-as",
            self.package_name,
            "rm",
            "-f",
            FUNCTION_RESULT_FILE,
            timeout=30,
            check=False,
        )
        goal_b64 = base64.b64encode(goal.encode("utf-8")).decode("ascii")
        self.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            FUNCTION_ACTION,
            "-n",
            self.function_receiver,
            "--es",
            "function_id",
            function_id,
            "--es",
            "goalBase64",
            goal_b64,
            timeout=self.receiver_start_timeout(),
        )
        return self.wait_for_json(FUNCTION_RESULT_FILE, "OOB reusable command replay result")


def boolish(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.lower() in {"true", "1", "yes", "finished", "success"}
    return bool(value)


def nested_get(data: dict[str, Any], *keys: str) -> Any:
    current: Any = data
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def first_present(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def phase_timing(started_at_ms: int) -> dict[str, int]:
    finished_at_ms = now_ms()
    return {
        "started_at_ms": started_at_ms,
        "finished_at_ms": finished_at_ms,
        "duration_ms": max(0, finished_at_ms - started_at_ms),
    }


def current_activity(env: Any) -> str:
    try:
        from android_world.env import adb_utils

        return str(adb_utils.get_current_activity(env.controller)[0])
    except Exception as error:  # pylint: disable=broad-exception-caught
        return f"unavailable:{error}"


def evaluate_androidworld_success(
    *,
    env: Any,
    task: Any,
    timeout_seconds: float,
    interval_seconds: float,
) -> tuple[float, list[dict[str, Any]]]:
    """Poll AndroidWorld's validator after OOB reports completion.

    M3A waits for the device transition before the next observe. OOB executes the
    whole task inside the APK, so the host-side harness must do the equivalent
    settle before asking AndroidWorld for reward.
    """

    deadline = time.time() + max(0.0, timeout_seconds)
    attempts: list[dict[str, Any]] = []
    last_reward = 0.0
    while True:
        elapsed = 0.0 if not attempts else round(timeout_seconds - max(0.0, deadline - time.time()), 3)
        activity = current_activity(env)
        try:
            reward = float(task.is_successful(env))
            error = None
        except Exception as exc:  # pylint: disable=broad-exception-caught
            reward = 0.0
            error = str(exc)
        last_reward = reward
        attempts.append(
            {
                "elapsed_seconds": elapsed,
                "reward": reward,
                "current_activity": activity,
                **({"error": error} if error else {}),
            }
        )
        if reward == 1.0 or time.time() >= deadline:
            return last_reward, attempts
        time.sleep(max(0.1, min(interval_seconds, deadline - time.time())))


def extract_function_id(vlm_result: dict[str, Any]) -> str:
    convert = vlm_result.get("convert") or {}
    if isinstance(convert, dict):
        value = (
            convert.get("function_id")
            or convert.get("created_function_id")
            or nested_get(convert, "result", "function_id")
        )
        if value:
            return str(value)
    for key in ("function_id", "created_function_id"):
        value = vlm_result.get(key)
        if value:
            return str(value)
    return ""


def summarize_vlm_result(result: dict[str, Any]) -> dict[str, Any]:
    outcome = as_dict(result.get("outcome"))
    token_usage = as_dict(result.get("token_usage"))
    convert = as_dict(result.get("convert"))
    timing = as_dict(result.get("timing"))
    return {
        "success": result.get("success"),
        "run_id": result.get("run_id"),
        "outcome_status": outcome.get("status"),
        "execution_route": outcome.get("executionRoute") or outcome.get("execution_route"),
        "runlog_found": result.get("runlog_found"),
        "runlog_success": result.get("runlog_success"),
        "runlog_card_count": result.get("runlog_card_count"),
        "token_usage_total": result.get("token_usage_total") or token_usage.get("total_tokens"),
        "token_usage_call_count": token_usage.get("call_count"),
        "token_usage_by_step_count": len(result.get("token_usage_by_step") or []),
        "token_usage_by_call_count": len(result.get("token_usage_by_call") or []),
        "convert_success": convert.get("success"),
        "function_id": extract_function_id(result),
        "direct_recall_completed": result.get("direct_recall_completed"),
        "started_at_ms": first_present(
            result.get("started_at_ms"),
            timing.get("started_at_ms"),
        ),
        "finished_at_ms": first_present(
            result.get("finished_at_ms"),
            timing.get("finished_at_ms"),
        ),
        "duration_ms": first_present(
            result.get("duration_ms"),
            timing.get("duration_ms"),
            timing.get("runner_duration_ms"),
        ),
        "phase_ms": first_present(result.get("phase_ms"), timing.get("phase_ms")),
    }


def summarize_function_result(result: dict[str, Any]) -> dict[str, Any]:
    step_results = result.get("step_results") or nested_get(result, "result", "step_results") or []
    timing = as_dict(result.get("timing") or nested_get(result, "result", "timing"))
    return {
        "success": result.get("success"),
        "fallback": result.get("fallback"),
        "guard_decision": nested_get(result, "guard", "decision") or result.get("guard_decision"),
        "step_count": len(step_results) if isinstance(step_results, list) else None,
        "runner_duration_ms": timing.get("runner_duration_ms"),
        "started_at_ms": first_present(
            result.get("started_at_ms"),
            timing.get("started_at_ms"),
        ),
        "finished_at_ms": first_present(
            result.get("finished_at_ms"),
            timing.get("finished_at_ms"),
        ),
        "duration_ms": first_present(
            result.get("duration_ms"),
            timing.get("duration_ms"),
            timing.get("runner_duration_ms"),
        ),
        "phase_ms": first_present(result.get("phase_ms"), timing.get("phase_ms")),
    }


def summarize_exception(error: BaseException) -> dict[str, Any]:
    return {
        "type": error.__class__.__name__,
        "message": str(error),
        "traceback": traceback.format_exc(limit=8),
    }


def task_package(task: Any) -> str | None:
    params = getattr(task, "params", {}) or {}
    if task.name == "OpenAppTaskEval":
        return APP_PACKAGE_BY_ANDROIDWORLD_NAME.get(params.get("app_name"))
    app_names = tuple(getattr(task, "app_names", ()) or ())
    if len(app_names) == 1:
        return APP_PACKAGE_BY_ANDROIDWORLD_NAME.get(app_names[0])
    if "settings" in app_names:
        return APP_PACKAGE_BY_ANDROIDWORLD_NAME["settings"]
    return None


def instantiate_task(task_type: Any, env: Any, params: dict[str, Any] | None, seed: int | None) -> Any:
    task_type.set_device_time(env)
    if params is None:
        if seed is not None:
            random.seed(seed)
        params = task_type.generate_random_params()
    params = dict(params)
    if seed is not None and "seed" not in params:
        params["seed"] = seed
    return task_type(params)


class OobVlmAndroidWorldAgent:
    """AndroidWorld agent interface that delegates one episode to OOB VLM."""

    def __init__(
        self,
        env: Any,
        *,
        client: OobAdbClient,
        package_name: str | None,
        max_steps: int,
        register: bool,
        profile_id: str,
        model_id: str,
        skill_id: str,
        prelaunch: bool,
        start_from_current: bool,
        skip_go_home: bool,
        disable_omniflow_recall: bool,
    ) -> None:
        from android_world.agents import base_agent

        self._base_agent = base_agent
        self.env = env
        self.name = "oob_vlm"
        self._max_steps = None
        self.client = client
        self.package_name = package_name
        self.oob_max_steps = max_steps
        self.register = register
        self.profile_id = profile_id
        self.model_id = model_id
        self.skill_id = skill_id
        self.prelaunch = prelaunch
        self.start_from_current = start_from_current
        self.skip_go_home = skip_go_home
        self.disable_omniflow_recall = disable_omniflow_recall
        self.last_result: dict[str, Any] | None = None

    def reset(self, go_home: bool = False) -> None:
        self.env.reset(go_home=go_home)

    def set_max_steps(self, max_steps: int) -> None:
        self._max_steps = max_steps

    def step(self, goal: str) -> Any:
        result = self.client.run_vlm(
            goal=goal,
            package_name=self.package_name,
            max_steps=self.oob_max_steps,
            register=self.register,
            profile_id=self.profile_id,
            model_id=self.model_id,
            skill_id=self.skill_id,
            prelaunch=self.prelaunch,
            start_from_current=self.start_from_current,
            skip_go_home=self.skip_go_home,
            disable_omniflow_recall=self.disable_omniflow_recall,
        )
        self.last_result = result
        outcome = result.get("outcome") if isinstance(result.get("outcome"), dict) else {}
        done = result.get("success") is True or outcome.get("status") == "FINISHED"
        data = {
            "oob_vlm_result": result,
            "oob_vlm_summary": summarize_vlm_result(result),
        }
        return self._base_agent.AgentInteractionResult(done=done, data=data)


def run_online_vlm_task(
    *,
    env: Any,
    task_type: Any,
    params: dict[str, Any] | None,
    seed: int | None,
    client: OobAdbClient,
    max_steps: int,
    profile_id: str,
    model_id: str,
    skill_id: str,
    prelaunch: bool,
    start_from_current: bool,
    skip_go_home: bool,
    disable_omniflow_recall: bool,
    restore_wall_clock: bool,
    settle_timeout_seconds: float,
    settle_interval_seconds: float,
) -> dict[str, Any]:
    from android_world import episode_runner

    phase_started_at_ms = now_ms()
    task = instantiate_task(task_type, env, params, seed)
    initialized = False
    try:
        task.initialize_task(env)
        initialized = True
        if restore_wall_clock:
            client.restore_wall_clock()
        package_name = None if start_from_current or skip_go_home else task_package(task)
        agent = OobVlmAndroidWorldAgent(
            env,
            client=client,
            package_name=package_name,
            max_steps=max_steps,
            register=True,
            profile_id=profile_id,
            model_id=model_id,
            skill_id=skill_id,
            prelaunch=prelaunch,
            start_from_current=start_from_current,
            skip_go_home=skip_go_home,
            disable_omniflow_recall=disable_omniflow_recall,
        )
        episode = episode_runner.run_episode(
            goal=task.goal,
            agent=agent,
            max_n_steps=1,
            start_on_home_screen=task.start_on_home_screen,
        )
        androidworld_reward, reward_attempts = evaluate_androidworld_success(
            env=env,
            task=task,
            timeout_seconds=settle_timeout_seconds,
            interval_seconds=settle_interval_seconds,
        )
        vlm_result = agent.last_result or {}
        return {
            "task_name": task.name,
            "goal": task.goal,
            "params": task.params,
            "package_name": package_name,
            "episode_done": episode.done,
            "androidworld_reward": androidworld_reward,
            "androidworld_success": androidworld_reward == 1.0 and episode.done,
            "androidworld_reward_attempts": reward_attempts,
            "androidworld_settle_timeout_seconds": settle_timeout_seconds,
            "oob_vlm": summarize_vlm_result(vlm_result),
            "oob_vlm_raw": vlm_result,
            "disable_omniflow_recall": disable_omniflow_recall,
            "phase_timing": phase_timing(phase_started_at_ms),
            "device_online_after_phase": client.is_device_online(),
        }
    finally:
        if initialized:
            try:
                task.tear_down(env)
            except Exception as error:  # pylint: disable=broad-exception-caught
                print(f"warning: failed to tear down {task.name}: {error}", file=sys.stderr)


def run_function_validation(
    *,
    env: Any,
    task_type: Any,
    params: dict[str, Any] | None,
    seed: int | None,
    client: OobAdbClient,
    function_id: str,
    goal: str,
    restore_wall_clock: bool,
    settle_timeout_seconds: float,
    settle_interval_seconds: float,
) -> dict[str, Any]:
    phase_started_at_ms = now_ms()
    task = instantiate_task(task_type, env, params, seed)
    initialized = False
    try:
        task.initialize_task(env)
        initialized = True
        if restore_wall_clock:
            client.restore_wall_clock()
        package_name = task_package(task)
        if package_name:
            client.force_stop_package(package_name)
        env.reset(go_home=task.start_on_home_screen)
        result = client.run_function(function_id=function_id, goal=goal)
        androidworld_reward, reward_attempts = evaluate_androidworld_success(
            env=env,
            task=task,
            timeout_seconds=settle_timeout_seconds,
            interval_seconds=settle_interval_seconds,
        )
        return {
            "task_name": task.name,
            "goal": task.goal,
            "params": task.params,
            "function_id": function_id,
            "androidworld_reward": androidworld_reward,
            "androidworld_success": androidworld_reward == 1.0 and result.get("success") is True,
            "androidworld_reward_attempts": reward_attempts,
            "androidworld_settle_timeout_seconds": settle_timeout_seconds,
            "oob_function": summarize_function_result(result),
            "oob_function_raw": result,
            "phase_timing": phase_timing(phase_started_at_ms),
            "device_online_after_phase": client.is_device_online(),
        }
    finally:
        if initialized:
            try:
                task.tear_down(env)
            except Exception as error:  # pylint: disable=broad-exception-caught
                print(f"warning: failed to tear down {task.name}: {error}", file=sys.stderr)


def parse_params(raw: str | None) -> dict[str, Any] | None:
    if not raw:
        return None
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("--params-json must be a JSON object")
    return value


def default_output_path(repo_root: Path) -> Path:
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    return repo_root / "output" / f"androidworld-oob-eval-{timestamp}.json"


def build_method_record(args: argparse.Namespace) -> dict[str, Any]:
    """Describe the AndroidWorld/OOB adapter method without running episodes."""
    tasks = args.task or []
    return {
        "schema_version": "oob.androidworld_method.v1",
        "mode": "method_only",
        "generated_at": dt.datetime.now().isoformat(),
        "scope": {
            "purpose": "Record how OOB connects to AndroidWorld-style evaluation without claiming benchmark results.",
            "does_not_run_androidworld_episode": True,
            "does_not_replace_oob_kotlin_runtime": True,
            "live_runner_requires_explicit_run_live": True,
            "tasks_requested": tasks,
            "simple_validation_tasks": SIMPLE_VALIDATION_TASKS,
        },
        "m3a_reference_method": {
            "source_files": [
                "android_world/agents/m3a.py",
                "android_world/agents/m3a_utils.py",
            ],
            "observation": [
                "Each agent step observes AndroidWorld state from the host.",
                "The model sees the raw screenshot, a marked screenshot, and a UI element list with numeric indexes.",
                "The model outputs one JSON action; AndroidWorld executes it and waits for stabilization.",
                "A second multimodal summary call compares before/after screenshots and UI lists, then appends concise history.",
                "Completion is an explicit status action after the current visible state satisfies the goal.",
            ],
            "important_properties_to_match": [
                "visible indexed target grounding",
                "one action per decision turn",
                "before/after observation feedback",
                "short action history summaries",
                "explicit completion decision",
            ],
        },
        "mobilerun_reference_method": {
            "source": "droidrun/mobilerun is recorded as a process reference only, not as an OOB runtime dependency.",
            "reviewed_flow_sources": [
                "mobilerun/agent/droid/droid_agent.py",
                "mobilerun/agent/fast_agent/fast_agent.py",
                "mobilerun/agent/manager/manager_agent.py",
                "mobilerun/agent/executor/executor_agent.py",
                "mobilerun/tools/ui/provider.py",
                "mobilerun/tools/formatters/indexed_formatter.py",
            ],
            "observed_flow": [
                "Fetch Accessibility tree, phone state, screen bounds, and optional screenshot every turn.",
                "Retry transient state-read failures before letting the agent act on stale page evidence.",
                "Format the tree into indexed UI evidence.",
                "Build one LLM turn from goal, indexed state, screenshot, short memory/history, and previous tool result.",
                "Parse a structured tool block and dispatch through a small action registry.",
                "Feed structured tool results back into the next turn.",
                "Persist trajectory artifacts for debugging.",
            ],
            "oob_borrowed_properties": [
                "indexed screenshot-grounded observations",
                "stable structured tool result feedback",
                "small deterministic action surface",
                "short task memory/history",
                "trajectory artifacts with token and timing diagnostics",
            ],
            "oob_native_mapping": {
                "state_fetch": "OOB readCurrentPackage/readCurrentPage/screenshot capture plus post-action observation.",
                "indexed_elements": "OOB indexed Accessibility evidence and marked screenshot labels.",
                "tool_registry": "OOB VLMToolDefinitions and native DeviceOperator actions.",
                "tool_results": "OOB structured tool results and RunLog post-action fields.",
                "trajectory": "OOB RunLog, reusable command registration, replay, UDEG recall, token usage, and timing diagnostics.",
            },
            "explicit_non_goals": [
                "Do not call the Mobilerun Portal app.",
                "Do not call the Mobilerun Python agent loop.",
                "Do not import or install Mobilerun as part of OOB validation.",
                "Do not invoke Mobilerun CLI or MCP tools from OOB validation.",
                "Do not use Mobilerun macro replay as OOB replay.",
                "Do not replace native Kotlin VLM, RunLog, reusable command registration, UDEG recall, or replay.",
            ],
        },
        "diagnosis_of_oob_gap": {
            "main_gap": "A full OOB episode was previously wrapped as a single AndroidWorld agent step, while M3A externally observes, acts, settles, and summarizes every step.",
            "kotlin_alignment": [
                "OOB must provide raw and marked screenshots plus indexed Accessibility evidence every VLM turn.",
                "Every executed action must persist before/after XML, package, screen_changed, appeared_texts, disappeared_texts, visible texts, token usage, and timings.",
                "Python must remain a suite shell; it should not implement GUI heuristics or replay policy."
            ],
        },
        "oob_adapter_method": {
            "entry": "scripts/androidworld_oob_eval.py delegates to DebugVlmRunLogReceiver through adb broadcast.",
            "execution_owner": "OOB APK Kotlin native VLM and OmniFlow runtime",
            "python_role": "Thin control-plane adapter only; it must not reimplement GUI policy or AndroidWorld task logic.",
            "default_mode": "method-only record; no AndroidWorld import, emulator setup, episode, or reward check.",
            "live_opt_in": "Use --run-live only for later external validation.",
                "live_phases": [
                "online_vlm",
                "replay",
                "recall_repeat"
            ],
            "control_fields": {
                "goal": "AndroidWorld task goal text",
                "packageName": "Known target package when available",
                "maxSteps": args.max_steps,
                "timeoutMs": args.timeout * 1000,
                "skillId": args.skill_id,
                "disableOmniFlowRecall": "true for online_vlm validation, false for recall_repeat",
            },
            "method_only_output": "This JSON record is sufficient for design review; real reward runs are intentionally skipped.",
        },
        "oob_runtime_alignment": {
            "online_vlm": [
                "Inject vlm-android-gui skill guidance into the VLM task.",
                "Use indexed page evidence and marked screenshots for element grounding.",
                "Keep one native tool call per VLM turn.",
                "Persist post-action XML/package evidence, appeared/disappeared text feedback, token usage, and timing into RunLog cards.",
                "Use waitTimeoutMs only as control-plane wait budget; maxSteps remains the task execution bound.",
                "Validation can set disableOmniFlowRecall=true to force fresh online VLM RunLog capture instead of direct reusable command execution.",
            ],
            "runlog_replay": [
                "Generate native OmniFlow reusable commands from successful VLM RunLogs.",
                "Replay concrete actions through OobRunLogReplayService and OmniflowStepExecutor.",
                "Use recorded post-action page similarity as compatibility gate rather than proof of task success.",
            ],
            "recall": [
                "Recall follows page match -> UDEG node -> node skill-like decision context -> attached reusable commands.",
                "Direct no-argument hits may run locally; failed hits fall back to bounded VLM.",
                "Timing fields are internal stats and are not injected into the agent prompt.",
            ],
        },
        "validation_without_androidworld_run": {
            "test_layering": {
                "ux_tests": [
                    "Flutter RunLog timeline tests cover RunLog registration, local replay trigger, result sheets, and user-visible wording.",
                    "Flutter reusable command library tests cover grouping, detail surface, run button, running state, and result sheet internal-field hiding.",
                    "Flutter localization and execution widget tests guard user-visible route/reuse wording.",
                ],
                "runtime_method_tests": [
                    "Python AndroidWorld method-only tests record the adapter contract without importing AndroidWorld or starting an emulator.",
                    "Kotlin unit tests cover RunLog collection, native reusable command registration/replay, UDEG recall timing, segment reuse, and token/timing persistence.",
                    "Live AndroidWorld reward runs stay behind --run-live and are not required for method-only evidence.",
                ],
                "separation_rule": "UX tests must not import AndroidWorld or start an emulator; runtime method tests must not assert Flutter layout details.",
            },
            "static_checks": [
                "Kotlin build/unit tests for VLM request, indexed page grounding, post-action observation, RunLog registration/replay, UDEG recall.",
                "Python adapter method-only export and syntax check.",
            ],
            "artifact_checks": [
                "RunLog contains token_usage_total, token_usage_by_step, token_usage_by_call.",
                "RunLog cards include started_at_ms, finished_at_ms, duration_ms.",
                "Recall timing includes parse_request_ms, read_current_package_ms, read_current_page_ms, page_match_ms, rank_functions_ms, segment_match_ms.",
            ],
            "deferred_runtime_checks": [
                "With --run-live, run online_vlm, replay, and recall_repeat on the same simple task params.",
                "Mobilerun/DroidRun remains a method reference only for this work; do not call its Portal or Python runtime during OOB validation.",
                "Do not replace the Kotlin RunLog, replay, recall, or VLM decision runtime with Mobilerun/DroidRun internals.",
            ],
        },
    }


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-world-root", default=str(DEFAULT_ANDROID_WORLD_ROOT))
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--adb-path", default=find_adb())
    parser.add_argument("--console-port", type=int, default=5554)
    parser.add_argument("--grpc-port", type=int, default=None)
    parser.add_argument("--device", default=None, help="adb serial. Defaults to emulator-<console-port>.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--task", action="append", help="AndroidWorld task name. Repeat for multiple tasks.")
    parser.add_argument(
        "--simple-suite",
        action="store_true",
        help="Use the built-in simple AndroidWorld validation task list when --task is not supplied.",
    )
    parser.add_argument("--params-json", default=None, help="JSON object used for every task, mainly for single-task debugging.")
    parser.add_argument("--seed", type=int, default=30)
    parser.add_argument("--max-steps", type=int, default=12)
    parser.add_argument("--timeout", type=int, default=360)
    parser.add_argument("--model", default=DEFAULT_MODEL_ID)
    parser.add_argument("--profile-id", default=DEFAULT_PROFILE_ID)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--api-key-env", default=DEFAULT_API_KEY_ENV)
    parser.add_argument("--skill-id", default=DEFAULT_SKILL_ID)
    parser.add_argument("--skip-configure-provider", action="store_true")
    parser.add_argument("--perform-emulator-setup", action="store_true")
    parser.add_argument("--no-replay", action="store_true")
    parser.add_argument("--recall-repeat", action="store_true", help="Run the same task again after conversion to validate recall.")
    parser.add_argument("--start-from-current", action="store_true")
    parser.add_argument("--skip-go-home", action="store_true")
    parser.add_argument("--no-prelaunch", action="store_true")
    parser.add_argument("--post-action-settle-timeout", type=float, default=6.0)
    parser.add_argument("--post-action-settle-interval", type=float, default=1.0)
    parser.add_argument(
        "--method-only",
        action="store_true",
        help="Write the AndroidWorld/M3A/OOB adapter method record without loading AndroidWorld or running emulator tasks. This is the default.",
    )
    parser.add_argument(
        "--run-live",
        action="store_true",
        help="Explicitly run AndroidWorld episodes through the OOB control-plane adapter. Default is method-only.",
    )
    parser.add_argument(
        "--keep-androidworld-frozen-time",
        action="store_true",
        help="Keep AndroidWorld's frozen 2023 device time. Default restores wall clock so on-device OOB HTTPS model calls work.",
    )
    parser.add_argument("--output", default=None)
    return parser


def resolve_live_tasks(args: argparse.Namespace) -> list[str]:
    if args.task:
        return list(args.task)
    if args.simple_suite:
        return list(SIMPLE_VALIDATION_TASKS)
    return []


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    repo_root = Path(args.repo_root).resolve()
    android_world_root = Path(args.android_world_root).resolve()
    output_path = Path(args.output).resolve() if args.output else default_output_path(repo_root)
    if args.method_only or not args.run_live:
        method_record = build_method_record(args)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(method_record, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote_method_record={output_path}")
        return 0

    live_tasks = resolve_live_tasks(args)
    if not live_tasks:
        parser.error("--task is required unless --method-only is set")
    args.task = live_tasks

    load_android_world(android_world_root)

    from android_world import registry
    from android_world.env import env_launcher

    device_serial = args.device or f"emulator-{args.console_port}"
    grpc_port = args.grpc_port if args.grpc_port is not None else args.console_port + 3000
    params = parse_params(args.params_json)

    client = OobAdbClient(
        adb_path=args.adb_path,
        device_serial=device_serial,
        package_name=args.package,
        repo_root=repo_root,
        timeout_seconds=args.timeout,
    )

    client.configure_provider(
        base_url=args.base_url,
        api_key_env=args.api_key_env,
        model_id=args.model,
        profile_id=args.profile_id,
        skip_configure=args.skip_configure_provider,
    )
    env = env_launcher.load_and_setup_env(
        console_port=args.console_port,
        emulator_setup=args.perform_emulator_setup,
        adb_path=args.adb_path,
        grpc_port=grpc_port,
    )
    client.prepare_device()

    task_registry = registry.TaskRegistry().get_registry(registry.TaskRegistry.ANDROID_WORLD_FAMILY)
    results: list[dict[str, Any]] = []
    started_at = time.time()
    try:
        for task_name in args.task:
            if task_name not in task_registry:
                raise ValueError(f"Task {task_name} not found in AndroidWorld registry")
            task_type = task_registry[task_name]
            print(f"== AndroidWorld task: {task_name} ==")
            task_result: dict[str, Any] = {"task_name": task_name}
            try:
                online = run_online_vlm_task(
                    env=env,
                    task_type=task_type,
                    params=params,
                    seed=args.seed,
                    client=client,
                    max_steps=args.max_steps,
                    profile_id=args.profile_id,
                    model_id=args.model,
                    skill_id=args.skill_id,
                    prelaunch=not args.no_prelaunch,
                    start_from_current=args.start_from_current,
                    skip_go_home=args.skip_go_home,
                    disable_omniflow_recall=True,
                    restore_wall_clock=not args.keep_androidworld_frozen_time,
                    settle_timeout_seconds=args.post_action_settle_timeout,
                    settle_interval_seconds=args.post_action_settle_interval,
                )
                task_result["online_vlm"] = online
                print(json.dumps({
                    "phase": "online_vlm",
                    "task": task_name,
                    "androidworld_success": online["androidworld_success"],
                    "androidworld_reward": online["androidworld_reward"],
                    "oob_vlm": online["oob_vlm"],
                }, ensure_ascii=False))
            except Exception as error:  # pylint: disable=broad-exception-caught
                task_result["online_vlm_error"] = summarize_exception(error)
                task_result["online_vlm_error"]["device_online_after_phase"] = client.is_device_online()
                task_result["online_vlm_error"]["phase"] = "online_vlm"
                results.append(task_result)
                print(json.dumps({
                    "phase": "online_vlm",
                    "task": task_name,
                    "error": task_result["online_vlm_error"],
                }, ensure_ascii=False), file=sys.stderr)
                continue

            online = task_result["online_vlm"]
            function_id = online["oob_vlm"].get("function_id") or ""
            if function_id and not args.no_replay:
                try:
                    replay = run_function_validation(
                        env=env,
                        task_type=task_type,
                        params=online["params"],
                        seed=args.seed,
                        client=client,
                        function_id=function_id,
                        goal=online["goal"],
                        restore_wall_clock=not args.keep_androidworld_frozen_time,
                        settle_timeout_seconds=args.post_action_settle_timeout,
                        settle_interval_seconds=args.post_action_settle_interval,
                    )
                    task_result["replay"] = replay
                    print(json.dumps({
                        "phase": "replay",
                        "task": task_name,
                        "androidworld_success": replay["androidworld_success"],
                        "androidworld_reward": replay["androidworld_reward"],
                        "oob_function": replay["oob_function"],
                    }, ensure_ascii=False))
                except Exception as error:  # pylint: disable=broad-exception-caught
                    task_result["replay_error"] = summarize_exception(error)
                    task_result["replay_error"]["device_online_after_phase"] = client.is_device_online()
                    task_result["replay_error"]["phase"] = "replay"
                    print(json.dumps({
                        "phase": "replay",
                        "task": task_name,
                        "error": task_result["replay_error"],
                    }, ensure_ascii=False), file=sys.stderr)
            elif not function_id:
                task_result["replay_skipped_reason"] = "no_function_id_from_online_vlm"

            if args.recall_repeat:
                try:
                    recall = run_online_vlm_task(
                        env=env,
                        task_type=task_type,
                        params=online["params"],
                        seed=args.seed,
                        client=client,
                        max_steps=args.max_steps,
                        profile_id=args.profile_id,
                        model_id=args.model,
                        skill_id=args.skill_id,
                        prelaunch=not args.no_prelaunch,
                        start_from_current=args.start_from_current,
                        skip_go_home=args.skip_go_home,
                        disable_omniflow_recall=False,
                        restore_wall_clock=not args.keep_androidworld_frozen_time,
                        settle_timeout_seconds=args.post_action_settle_timeout,
                        settle_interval_seconds=args.post_action_settle_interval,
                    )
                    task_result["recall_repeat"] = recall
                    print(json.dumps({
                        "phase": "recall_repeat",
                        "task": task_name,
                        "androidworld_success": recall["androidworld_success"],
                        "androidworld_reward": recall["androidworld_reward"],
                        "oob_vlm": recall["oob_vlm"],
                    }, ensure_ascii=False))
                except Exception as error:  # pylint: disable=broad-exception-caught
                    task_result["recall_repeat_error"] = summarize_exception(error)
                    task_result["recall_repeat_error"]["device_online_after_phase"] = client.is_device_online()
                    task_result["recall_repeat_error"]["phase"] = "recall_repeat"
                    print(json.dumps({
                        "phase": "recall_repeat",
                        "task": task_name,
                        "error": task_result["recall_repeat_error"],
                    }, ensure_ascii=False), file=sys.stderr)

            results.append(task_result)
    finally:
        env.close()

    finished_at = time.time()
    summary = {
        "started_at": dt.datetime.fromtimestamp(started_at).isoformat(),
        "finished_at": dt.datetime.fromtimestamp(finished_at).isoformat(),
        "duration_seconds": round(finished_at - started_at, 3),
        "device": device_serial,
        "console_port": args.console_port,
        "grpc_port": grpc_port,
        "model": args.model,
        "skill_id": args.skill_id,
        "restored_wall_clock_for_oob": not args.keep_androidworld_frozen_time,
        "post_action_settle_timeout": args.post_action_settle_timeout,
        "post_action_settle_interval": args.post_action_settle_interval,
        "tasks": results,
        "online_success_count": sum(1 for item in results if item.get("online_vlm", {}).get("androidworld_success")),
        "replay_success_count": sum(1 for item in results if item.get("replay", {}).get("androidworld_success")),
        "recall_success_count": sum(1 for item in results if item.get("recall_repeat", {}).get("androidworld_success")),
        "phase_error_count": sum(
            1
            for item in results
            for key in ("online_vlm_error", "replay_error", "recall_repeat_error")
            if key in item
        ),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote_result={output_path}")

    required_online = len(args.task)
    if summary["online_success_count"] < required_online:
        return 2
    if not args.no_replay and summary["replay_success_count"] == 0:
        return 3
    if args.recall_repeat and summary["recall_success_count"] == 0:
        return 4
    if summary["phase_error_count"] > 0:
        return 5
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
